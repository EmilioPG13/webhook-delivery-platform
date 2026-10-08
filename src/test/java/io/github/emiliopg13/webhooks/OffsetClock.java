package io.github.emiliopg13.webhooks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The system clock plus an offset that tests move forward. It keeps ticking in real time, so code that compares
 * instants taken a few milliseconds apart (ordered ids, durations) still sees time pass; and it only ever moves
 * forward, which time-ordered UUIDs require. Tests use it to jump over a retry delay instead of waiting for it.
 */
public final class OffsetClock extends Clock {

	private volatile Duration offset = Duration.ZERO;

	public synchronized void advance(Duration duration) {
		if (duration.isNegative()) {
			throw new IllegalArgumentException("Time only moves forward");
		}
		offset = offset.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return Instant.now().plus(offset);
	}

}
