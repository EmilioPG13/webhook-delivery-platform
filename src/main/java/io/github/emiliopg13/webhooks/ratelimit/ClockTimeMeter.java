package io.github.emiliopg13.webhooks.ratelimit;

import java.time.Clock;
import java.time.Instant;

import io.github.bucket4j.TimeMeter;

/**
 * Bucket4j's notion of time, taken from the injected {@link Clock} so tests can move it instead of waiting. It
 * counts from the epoch, which makes it wall-clock based: the only kind that means the same thing on every
 * instance sharing a bucket.
 */
final class ClockTimeMeter implements TimeMeter {

	private final Clock clock;

	ClockTimeMeter(Clock clock) {
		this.clock = clock;
	}

	@Override
	public long currentTimeNanos() {
		Instant now = clock.instant();
		return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
	}

	@Override
	public boolean isWallClockBased() {
		return true;
	}

}
