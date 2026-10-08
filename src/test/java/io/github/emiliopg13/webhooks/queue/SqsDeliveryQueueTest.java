package io.github.emiliopg13.webhooks.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

/**
 * Runs the queue inside a minimal Spring context, because the point is the proxy: {@code @Retryable} only works if
 * the call goes through it. The SQS client is a mock that fails on demand.
 */
@SpringJUnitConfig(SqsDeliveryQueueTest.Config.class)
@ActiveProfiles("worker")
@TestPropertySource(properties = "webhooks.queue.send-retry-delay=1ms")
class SqsDeliveryQueueTest {

	@Configuration(proxyBeanMethods = false)
	@EnableResilientMethods(proxyTargetClass = true)
	@Import(SqsDeliveryQueue.class)
	static class Config {

		@Bean
		SqsClient sqsClient() {
			return mock(SqsClient.class);
		}

		@Bean
		QueueProperties queueProperties() {
			return new QueueProperties("deliveries", "us-east-1", null, false, Duration.ofSeconds(60), 5);
		}

	}

	@Autowired
	private DeliveryQueue queue;

	@Autowired
	private SqsClient sqs;

	@BeforeEach
	void resetMock() {
		reset(sqs);
		when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
			.thenReturn(GetQueueUrlResponse.builder().queueUrl("http://queue/deliveries").build());
	}

	@Test
	void theQueueIsProxiedSoThatTheRetryAnnotationApplies() {
		// With interface proxies the annotation on the implementation is never found and nothing is retried.
		assertThat(org.springframework.aop.support.AopUtils.isAopProxy(queue)).isTrue();
	}

	@Test
	void sendRetriesATransientFailureAndThenSucceeds() {
		when(sqs.sendMessageBatch(any(SendMessageBatchRequest.class))).thenThrow(SdkClientException.create("connection reset"))
			.thenThrow(SdkClientException.create("connection reset"))
			.thenReturn(SendMessageBatchResponse.builder().build());

		queue.send(List.of(UUID.randomUUID()));

		verify(sqs, times(3)).sendMessageBatch(any(SendMessageBatchRequest.class));
	}

	@Test
	void sendGivesUpAfterTheRetriesAreSpent() {
		when(sqs.sendMessageBatch(any(SendMessageBatchRequest.class))).thenThrow(SdkClientException.create("down"));

		assertThatThrownBy(() -> queue.send(List.of(UUID.randomUUID()))).isInstanceOf(SdkClientException.class);

		// The first try plus three retries.
		verify(sqs, times(4)).sendMessageBatch(any(SendMessageBatchRequest.class));
	}

	@Test
	void messagesSqsRejectsCountAsAFailureAndAreRetried() {
		SendMessageBatchResponse rejected = SendMessageBatchResponse.builder()
			.failed(BatchResultErrorEntry.builder().id("x").code("InternalError").message("try later").build())
			.build();
		when(sqs.sendMessageBatch(any(SendMessageBatchRequest.class))).thenReturn(rejected)
			.thenReturn(SendMessageBatchResponse.builder().build());

		queue.send(List.of(UUID.randomUUID()));

		verify(sqs, times(2)).sendMessageBatch(any(SendMessageBatchRequest.class));
	}

	@Test
	void aBatchLargerThanSqsAllowsIsRefusedWithoutRetrying() {
		List<UUID> eleven = IntStream.range(0, DeliveryQueue.MAX_BATCH + 1).mapToObj(i -> UUID.randomUUID()).toList();

		assertThatThrownBy(() -> queue.send(eleven)).isInstanceOf(IllegalArgumentException.class);

		verify(sqs, never()).sendMessageBatch(any(SendMessageBatchRequest.class));
	}

	@Test
	void sendPutsTheDeliveryIdAndNothingElseInTheMessage() {
		UUID id = UUID.randomUUID();
		when(sqs.sendMessageBatch(any(SendMessageBatchRequest.class))).thenReturn(SendMessageBatchResponse.builder().build());

		queue.send(List.of(id));

		verify(sqs).sendMessageBatch(org.mockito.ArgumentMatchers.<SendMessageBatchRequest>argThat(
				request -> request.entries().size() == 1 && request.entries().getFirst().messageBody().equals(id.toString())));
	}

	@Test
	void receiveIgnoresMessagesThatAreNotDeliveryIds() {
		UUID id = UUID.randomUUID();
		when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
			.messages(Message.builder().messageId("1").body("garbage").receiptHandle("r1").build(),
					Message.builder().messageId("2").body(id.toString()).receiptHandle("r2").build())
			.build());

		List<DeliveryQueue.Message> messages = queue.receive(10, Duration.ofSeconds(20));

		assertThat(messages).containsExactly(new DeliveryQueue.Message(id, "r2"));
	}

}
