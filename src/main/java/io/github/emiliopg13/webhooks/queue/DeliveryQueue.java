package io.github.emiliopg13.webhooks.queue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The work queue between the dispatcher and the consumers. A message carries only a delivery id: PostgreSQL stays
 * the source of truth, so losing, duplicating or delaying a message can never lose a delivery.
 *
 * <p>The delivery code depends on this interface and never on the AWS SDK.
 */
public interface DeliveryQueue {

	/** Most ids {@link #send} accepts at once; it is the SQS batch limit. */
	int MAX_BATCH = 10;

	/** A received message. It stays invisible to other readers until deleted or its visibility timeout runs out. */
	record Message(UUID deliveryId, String receiptHandle) {
	}

	/** Enqueues up to {@link #MAX_BATCH} delivery ids. Transient failures are retried; the last one is thrown. */
	void send(List<UUID> deliveryIds);

	/** Long polls for up to {@code maxWait}. Returns at most {@code max} messages, none if the wait passed empty. */
	List<Message> receive(int max, Duration maxWait);

	/** Removes a processed message. Without it the message reappears once its visibility timeout runs out. */
	void delete(Message message);

}
