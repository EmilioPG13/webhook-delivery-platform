package io.github.emiliopg13.webhooks.delivery;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes events older than the retention period together with their deliveries and attempts, so the tables do not
 * grow forever. Runs in batches until nothing old is left; each batch is one short transaction.
 */
@Component
@Profile("worker")
class RetentionJob {

	private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

	private final DeliveryWorkRepository work;

	private final RetentionProperties properties;

	private final Clock clock;

	private final DeliveryMetrics metrics;

	RetentionJob(DeliveryWorkRepository work, RetentionProperties properties, Clock clock, DeliveryMetrics metrics) {
		this.work = work;
		this.properties = properties;
		this.clock = clock;
		this.metrics = metrics;
	}

	@Scheduled(fixedDelayString = "${webhooks.retention.interval}")
	void purge() {
		Instant cutoff = clock.instant().minus(properties.period());
		int total = 0;
		int batch;
		do {
			batch = work.deleteExpiredEvents(cutoff, properties.batchSize());
			total += batch;
		}
		while (batch == properties.batchSize());
		if (total > 0) {
			metrics.purged(total);
			log.info("Deleted {} events older than {} with their deliveries and attempts", total, properties.period());
		}
	}

}
