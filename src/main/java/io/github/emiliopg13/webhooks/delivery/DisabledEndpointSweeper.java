package io.github.emiliopg13.webhooks.delivery;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gives up on deliveries waiting for an endpoint that stayed switched off. They sit in {@code pending} while the
 * owner can still fix the endpoint and call {@code enable}; after {@code cancel-after} they become
 * {@code cancelled}, so the backlog cannot grow forever.
 */
@Component
@Profile("worker")
class DisabledEndpointSweeper {

	private static final Logger log = LoggerFactory.getLogger(DisabledEndpointSweeper.class);

	private final DeliveryWorkRepository work;

	private final BreakerProperties properties;

	private final Clock clock;

	private final DeliveryMetrics metrics;

	DisabledEndpointSweeper(DeliveryWorkRepository work, BreakerProperties properties, Clock clock,
			DeliveryMetrics metrics) {
		this.work = work;
		this.properties = properties;
		this.clock = clock;
		this.metrics = metrics;
	}

	@Scheduled(fixedDelayString = "${webhooks.breaker.sweep-interval}")
	void sweep() {
		Instant now = clock.instant();
		int cancelled = work.cancelPendingOfLongDisabledEndpoints(now, now.minus(properties.cancelAfter()));
		if (cancelled > 0) {
			metrics.cancelled(cancelled);
			log.warn("Cancelled {} pending deliveries of endpoints that stayed disabled", cancelled);
		}
	}

}
