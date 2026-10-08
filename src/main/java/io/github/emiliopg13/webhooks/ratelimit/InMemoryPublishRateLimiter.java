package io.github.emiliopg13.webhooks.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Buckets held in this JVM's memory. Free of I/O, so as cheap as a limiter gets, but each API instance counts for
 * itself: with N instances a tenant can publish N times its rate. Right for a single instance, wrong for a fleet;
 * see {@link PostgresPublishRateLimiter}.
 */
@Component
@ConditionalOnProperty(name = "webhooks.rate-limit.store", havingValue = "memory")
class InMemoryPublishRateLimiter implements PublishRateLimiter {

	private record Entry(int ratePerMinute, Bucket bucket) {
	}

	private final ConcurrentHashMap<UUID, Entry> buckets = new ConcurrentHashMap<>();

	private final ClockTimeMeter timeMeter;

	InMemoryPublishRateLimiter(Clock clock) {
		this.timeMeter = new ClockTimeMeter(clock);
	}

	@Override
	public Verdict tryAcquire(UUID tenantId, int ratePerMinute) {
		// A changed rate starts a fresh, full bucket: simple, and the change is rare.
		Entry entry = buckets.compute(tenantId, (id, current) -> current != null && current.ratePerMinute() == ratePerMinute
				? current : new Entry(ratePerMinute, newBucket(ratePerMinute)));
		ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
		return probe.isConsumed() ? Verdict.ADMITTED : Verdict.rejected(probe.getNanosToWaitForRefill());
	}

	private Bucket newBucket(int ratePerMinute) {
		return Bucket.builder()
			.addLimit(Bandwidth.builder().capacity(ratePerMinute).refillGreedy(ratePerMinute, Duration.ofMinutes(1)).build())
			.withCustomTimePrecision(timeMeter)
			.build();
	}

}
