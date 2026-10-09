package io.github.emiliopg13.webhooks.delivery;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rescues deliveries stuck in {@code enqueued}: the dispatcher marked them and then died or could not reach the
 * queue, or a worker died holding a lease. They go back to {@code pending} and the dispatcher picks them up again.
 */
@Component
@Profile("worker")
class DeliveryReaper {

	private static final Logger log = LoggerFactory.getLogger(DeliveryReaper.class);

	private final DeliveryWorkRepository work;

	private final WorkerProperties properties;

	private final Clock clock;

	private final DeliveryMetrics metrics;

	DeliveryReaper(DeliveryWorkRepository work, WorkerProperties properties, Clock clock, DeliveryMetrics metrics) {
		this.work = work;
		this.properties = properties;
		this.clock = clock;
		this.metrics = metrics;
	}

	@Scheduled(fixedDelayString = "${webhooks.worker.reaper-interval}")
	void reap() {
		Instant now = clock.instant();
		int returned = work.returnStuckToPending(now, now.minus(properties.stuckAfter()));
		if (returned > 0) {
			metrics.reaped(returned);
			log.warn("Returned {} stuck deliveries to pending", returned);
		}
	}

}
