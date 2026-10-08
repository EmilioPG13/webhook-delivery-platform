package io.github.emiliopg13.webhooks.delivery;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.queue.DeliveryQueue;

/**
 * Moves due deliveries from PostgreSQL to the queue. The order matters: mark {@code enqueued} and commit first,
 * send to the queue after. If it were the other way round, a worker could receive a message before the row says
 * {@code enqueued}, fail its claim, and leave the delivery marked with no message. With this order the worst case
 * is a delivery marked {@code enqueued} whose message was never sent, and the reaper repairs that.
 */
@Component
@Profile("worker")
class DeliveryDispatcher {

	private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

	private final DeliveryWorkRepository work;

	private final DeliveryQueue queue;

	private final WorkerProperties properties;

	private final Clock clock;

	DeliveryDispatcher(DeliveryWorkRepository work, DeliveryQueue queue, WorkerProperties properties, Clock clock) {
		this.work = work;
		this.queue = queue;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${webhooks.worker.dispatch-interval}")
	void dispatch() {
		int batchSize = properties.dispatchBatchSize();
		List<UUID> ids;
		do {
			// Each pass is one statement and so its own transaction: it has committed before anything is sent.
			ids = work.markDueAsEnqueued(clock.instant(), batchSize);
			for (int from = 0; from < ids.size(); from += DeliveryQueue.MAX_BATCH) {
				queue.send(ids.subList(from, Math.min(from + DeliveryQueue.MAX_BATCH, ids.size())));
			}
			if (!ids.isEmpty()) {
				log.debug("Dispatched {} deliveries", ids.size());
			}
			// A full pass means there may be more due right now; do not make them wait for the next tick.
		}
		while (ids.size() == batchSize);
	}

}
