package io.github.emiliopg13.webhooks.queue;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.common.CachedSnapshot;
import io.github.emiliopg13.webhooks.queue.DeliveryQueue.Depth;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** The queue's own view of its depth. Compare with the PostgreSQL backlog: the two disagree when messages are lost. */
@Component
@Profile("worker")
class QueueMetrics {

	private static final Duration REFRESH = Duration.ofSeconds(5);

	private final CachedSnapshot<Depth> depth;

	QueueMetrics(MeterRegistry registry, DeliveryQueue queue, Clock clock) {
		this.depth = new CachedSnapshot<>("the queue depth", clock, REFRESH, queue::depth, new Depth(0, 0));
		Gauge.builder("webhooks.queue.messages", this, metrics -> metrics.depth.get().waiting())
			.description("Messages waiting in the queue")
			.tag("state", "waiting")
			.register(registry);
		Gauge.builder("webhooks.queue.messages", this, metrics -> metrics.depth.get().inFlight())
			.description("Messages a consumer has received and not yet deleted")
			.tag("state", "in_flight")
			.register(registry);
	}

}
