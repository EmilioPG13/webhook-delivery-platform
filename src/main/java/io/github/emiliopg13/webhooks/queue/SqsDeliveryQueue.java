package io.github.emiliopg13.webhooks.queue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

/**
 * {@link DeliveryQueue} on Amazon SQS, through the synchronous AWS SDK v2 client. Plain blocking calls are fine
 * here: the consumers run on virtual threads, which make waiting cheap.
 */
@Component
@Profile("worker")
class SqsDeliveryQueue implements DeliveryQueue {

	private static final Logger log = LoggerFactory.getLogger(SqsDeliveryQueue.class);

	private final SqsClient sqs;

	private final QueueProperties properties;

	private volatile String queueUrl;

	SqsDeliveryQueue(SqsClient sqs, QueueProperties properties) {
		this.sqs = sqs;
		this.properties = properties;
	}

	/**
	 * Short in-memory retries for transient SQS errors. They are unrelated to delivery retries, which are stored in
	 * PostgreSQL and last for days. If this still fails, the deliveries stay {@code enqueued} and the reaper hands
	 * them back to the dispatcher.
	 */
	@Override
	@Retryable(includes = SdkException.class, maxRetriesString = "${webhooks.queue.send-max-retries:3}",
			delayString = "${webhooks.queue.send-retry-delay:200ms}", multiplier = 2)
	public void send(List<UUID> deliveryIds) {
		if (deliveryIds.isEmpty()) {
			return;
		}
		if (deliveryIds.size() > MAX_BATCH) {
			throw new IllegalArgumentException("SQS takes at most " + MAX_BATCH + " messages per batch");
		}
		List<SendMessageBatchRequestEntry> entries = deliveryIds.stream()
			.map(id -> SendMessageBatchRequestEntry.builder().id(id.toString()).messageBody(id.toString()).build())
			.toList();
		SendMessageBatchResponse response = sqs
			.sendMessageBatch(SendMessageBatchRequest.builder().queueUrl(queueUrl()).entries(entries).build());
		if (response.hasFailed() && !response.failed().isEmpty()) {
			// Resending the whole batch may duplicate messages that did get through. That is harmless: a second
			// message for the same delivery loses the claim.
			throw SdkClientException.create(response.failed().size() + " of " + entries.size()
					+ " messages were rejected by SQS: " + response.failed().getFirst().message());
		}
	}

	@Override
	public List<Message> receive(int max, Duration maxWait) {
		var response = sqs.receiveMessage(ReceiveMessageRequest.builder()
			.queueUrl(queueUrl())
			.maxNumberOfMessages(max)
			.waitTimeSeconds(Math.toIntExact(maxWait.toSeconds()))
			.build());
		return response.messages().stream().<Message>mapMulti((message, sink) -> {
			try {
				sink.accept(new Message(UUID.fromString(message.body()), message.receiptHandle()));
			}
			catch (IllegalArgumentException ex) {
				// Not ours to process. Left alone it comes back until SQS moves it to the dead-letter queue.
				log.warn("Ignoring SQS message {} whose body is not a delivery id", message.messageId());
			}
		}).toList();
	}

	@Override
	public Depth depth() {
		Map<QueueAttributeName, String> attributes = sqs
			.getQueueAttributes(request -> request.queueUrl(queueUrl())
				.attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
			.attributes();
		return new Depth(Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)),
				Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)));
	}

	@Override
	public void delete(Message message) {
		sqs.deleteMessage(
				DeleteMessageRequest.builder().queueUrl(queueUrl()).receiptHandle(message.receiptHandle()).build());
	}

	private String queueUrl() {
		String url = queueUrl;
		if (url == null) {
			synchronized (this) {
				if (queueUrl == null) {
					queueUrl = resolveQueueUrl();
				}
				url = queueUrl;
			}
		}
		return url;
	}

	private String resolveQueueUrl() {
		try {
			return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(properties.name()).build()).queueUrl();
		}
		catch (QueueDoesNotExistException ex) {
			if (!properties.createIfMissing()) {
				throw ex;
			}
			return createQueues();
		}
	}

	private String createQueues() {
		String deadLetterUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(properties.name() + "-dlq").build())
			.queueUrl();
		String deadLetterArn = sqs
			.getQueueAttributes(request -> request.queueUrl(deadLetterUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
			.attributes()
			.get(QueueAttributeName.QUEUE_ARN);
		String redrivePolicy = "{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"%d\"}".formatted(deadLetterArn,
				properties.maxReceiveCount());
		String url = sqs.createQueue(CreateQueueRequest.builder()
			.queueName(properties.name())
			.attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT,
					Long.toString(properties.visibilityTimeout().toSeconds()), QueueAttributeName.REDRIVE_POLICY,
					redrivePolicy))
			.build()).queueUrl();
		log.info("Created SQS queue {} with dead-letter queue {}-dlq", properties.name(), properties.name());
		return url;
	}

}
