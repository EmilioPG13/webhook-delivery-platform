package io.github.emiliopg13.webhooks.delivery;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.common.CachedSnapshot;
import io.github.emiliopg13.webhooks.delivery.DeliveryWorkRepository.Backlog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The health of the work queue as PostgreSQL sees it, which is the source of truth: how many deliveries are due and
 * how long the oldest has waited. Read at most every few seconds however often Prometheus scrapes.
 */
@Component
@Profile("worker")
class BacklogMetrics {

	private static final Duration REFRESH = Duration.ofSeconds(5);

	private final CachedSnapshot<Backlog> backlog;

	BacklogMetrics(MeterRegistry registry, DeliveryWorkRepository work, Clock clock) {
		this.backlog = new CachedSnapshot<>("the delivery backlog", clock, REFRESH, () -> work.backlog(clock.instant()),
				new Backlog(0, Duration.ZERO));
		Gauge.builder("webhooks.deliveries.due", this, metrics -> metrics.backlog.get().due())
			.description("Pending deliveries whose time has come and that no dispatcher has taken yet")
			.register(registry);
		Gauge.builder("webhooks.deliveries.oldest.due.age", this,
				metrics -> metrics.backlog.get().oldestWait().toMillis() / 1000.0)
			.description("How long the oldest due delivery has been waiting")
			.baseUnit("seconds")
			.register(registry);
	}

}
