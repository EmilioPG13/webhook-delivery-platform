package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.delivery.RetryPolicy.Decision;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.GiveUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * What the delivery pipeline reports. Every tag here has a handful of possible values on purpose: a tenant or
 * endpoint id as a tag would create one Prometheus series per id, so per-tenant questions are answered from the
 * database or the logs, never from a label.
 */
@Component
class DeliveryMetrics {

	/** Upper bounds, in the order Prometheus expects, spanning a fast receiver to the 15 s delivery timeout. */
	private static final Duration[] ATTEMPT_BUCKETS = { Duration.ofMillis(25), Duration.ofMillis(50),
			Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
			Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15) };

	private final MeterRegistry registry;

	DeliveryMetrics(MeterRegistry registry) {
		this.registry = registry;
		// Counters that exist only after their first event make Prometheus's increase() miss that first event, and
		// leave dashboard panels empty until something goes wrong. Register them at zero instead.
		for (String reason : new String[] { "failing", "gone", ErrorKind.BLOCKED_DESTINATION.value() }) {
			disabledCounter(reason);
		}
		registry.counter("webhooks.deliveries.deferred");
		registry.counter("webhooks.deliveries.reaped");
		registry.counter("webhooks.deliveries.cancelled");
	}

	/**
	 * One HTTP attempt that was recorded. {@code outcome} is what became of the delivery: {@code delivered},
	 * {@code retry} (it goes back to pending) or {@code failed} (the schedule ran out or the failure is fatal).
	 * {@code error_kind} is {@code none} for a success.
	 */
	void attempt(DeliveryOutcome outcome, Decision next, long durationMs) {
		String result = next == null ? "delivered" : next instanceof GiveUp ? "failed" : "retry";
		ErrorKind kind = outcome.errorKind();
		Timer.builder("webhooks.delivery.attempts")
			.description("HTTP attempts to deliver a webhook, by what became of the delivery")
			.tag("outcome", result)
			.tag("error_kind", kind == null ? "none" : kind.value())
			.serviceLevelObjectives(ATTEMPT_BUCKETS)
			.register(registry)
			.record(Duration.ofMillis(durationMs));
	}

	/** An endpoint was switched off. {@code reason} is {@code failing}, {@code gone} or {@code blocked_destination}. */
	void endpointDisabled(String reason) {
		disabledCounter(reason).increment();
	}

	private Counter disabledCounter(String reason) {
		return Counter.builder("webhooks.endpoints.disabled")
			.description("Endpoints switched off by the circuit breaker or by a fatal answer")
			.tag("reason", reason)
			.register(registry);
	}

	/** A claimed delivery went back to pending because its endpoint already had its share in flight. */
	void deferred() {
		registry.counter("webhooks.deliveries.deferred").increment();
	}

	/** The reaper gave stuck deliveries back to the dispatcher. */
	void reaped(int count) {
		registry.counter("webhooks.deliveries.reaped").increment(count);
	}

	/** The sweeper cancelled pending deliveries of an endpoint that stayed off. */
	void cancelled(int count) {
		registry.counter("webhooks.deliveries.cancelled").increment(count);
	}

}
