package io.github.emiliopg13.webhooks.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.NetworkFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.Success;
import io.github.emiliopg13.webhooks.delivery.DeliveryWorkRepository.WorkItem;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.Decision;
import io.github.emiliopg13.webhooks.endpoint.EndpointSecretCipher;
import io.github.emiliopg13.webhooks.queue.DeliveryQueue;
import io.github.emiliopg13.webhooks.queue.DeliveryQueue.Message;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Handles one queue message: claim, deliver, record. Each step is its own unit of work, and nothing holds a
 * database connection while the HTTP call is in flight.
 *
 * <p>The queue message is deleted only after the outcome is recorded. A crash anywhere before that leaves the
 * message to reappear, which is how a worker dying after the HTTP call but before recording ends in a second
 * delivery: at least once, by design.
 */
@Component
@Profile("worker")
class DeliveryProcessor {

	private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

	private final DeliveryWorkRepository work;

	private final EndpointSecretCipher cipher;

	private final WebhookSender sender;

	private final RetryPolicy retryPolicy;

	private final AttemptRecorder recorder;

	private final DeliveryQueue queue;

	private final WorkerProperties properties;

	private final Clock clock;

	private final JsonMapper json;

	DeliveryProcessor(DeliveryWorkRepository work, EndpointSecretCipher cipher, WebhookSender sender,
			RetryPolicy retryPolicy, AttemptRecorder recorder, DeliveryQueue queue, WorkerProperties properties,
			Clock clock, JsonMapper json) {
		this.work = work;
		this.cipher = cipher;
		this.sender = sender;
		this.retryPolicy = retryPolicy;
		this.recorder = recorder;
		this.queue = queue;
		this.properties = properties;
		this.clock = clock;
		this.json = json;
	}

	/**
	 * The limit bounds how many deliveries this worker has in flight, however many consumer loops feed it. Callers
	 * past the limit wait here, holding no connection.
	 */
	@ConcurrencyLimit(limitString = "${webhooks.worker.max-concurrent-deliveries}")
	void process(Message message) {
		UUID deliveryId = message.deliveryId();
		Instant claimedAt = clock.instant();
		Optional<WorkItem> claimed = work.claim(deliveryId, claimedAt, claimedAt.plus(properties.lease()));
		if (claimed.isEmpty()) {
			// Another worker holds it, or it already finished (a duplicate message). Not ours to touch.
			queue.delete(message);
			return;
		}
		WorkItem item = claimed.get();
		int attemptNumber = item.attemptCount() + 1;
		Instant startedAt = clock.instant();
		long startedNanos = System.nanoTime();

		DeliveryOutcome outcome = deliver(item, startedAt);

		long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
		Decision next = outcome instanceof Success ? null : retryPolicy.decide(attemptNumber, outcome, clock.instant());
		boolean recorded = recorder.record(deliveryId, item.endpointId(), attemptNumber, startedAt, durationMs, outcome,
				next, clock.instant());
		log.debug("Delivery {} attempt {}: {} ({} ms){}", deliveryId, attemptNumber, describe(outcome), durationMs,
				recorded ? "" : ", already recorded by another worker");
		queue.delete(message);
	}

	private DeliveryOutcome deliver(WorkItem item, Instant startedAt) {
		byte[] secret;
		try {
			secret = cipher.decrypt(item.secretCiphertext(), item.endpointId());
		}
		catch (IllegalArgumentException ex) {
			// Never guess: a wrong key must not produce a signature, so the request is not sent at all.
			log.error("Cannot decrypt the secret of endpoint {}; delivery {} is not sent", item.endpointId(),
					item.deliveryId());
			return new NetworkFailure(ErrorKind.UNEXPECTED, "The endpoint secret could not be decrypted");
		}
		byte[] body = body(item);
		String messageId = item.eventId().toString();
		long timestamp = startedAt.getEpochSecond();
		return sender.post(item.url(),
				Map.of(Signer.ID_HEADER, messageId, Signer.TIMESTAMP_HEADER, Long.toString(timestamp),
						Signer.SIGNATURE_HEADER, Signer.sign(messageId, timestamp, body, secret)),
				body);
	}

	/** The documented envelope. The exact bytes returned here are both signed and sent. */
	private byte[] body(WorkItem item) {
		ObjectNode envelope = json.createObjectNode();
		envelope.put("id", item.eventId().toString());
		envelope.put("type", item.eventType());
		envelope.put("created_at", item.eventCreatedAt().toString());
		envelope.set("data", json.readTree(item.payloadJson()));
		return json.writeValueAsBytes(envelope);
	}

	private static String describe(DeliveryOutcome outcome) {
		return outcome.errorKind() == null ? "HTTP " + outcome.statusCode()
				: outcome.errorKind().value() + (outcome.statusCode() == null ? "" : " HTTP " + outcome.statusCode());
	}

}
