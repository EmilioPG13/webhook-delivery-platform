package io.github.emiliopg13.webhooks.ratelimit;

import java.util.UUID;

/**
 * Decides whether a tenant may publish another event now. Each tenant has a token bucket that holds up to its
 * per-minute rate in tokens and refills at that rate, so it can burst a full minute's worth and then sustain the
 * rate. One publish takes one token.
 */
public interface PublishRateLimiter {

	/**
	 * @param retryAfterSeconds when rejected, how long until a token is available, rounded up to whole seconds;
	 * zero when admitted
	 */
	record Verdict(boolean admitted, long retryAfterSeconds) {

		static final Verdict ADMITTED = new Verdict(true, 0);

		static Verdict rejected(long nanosToWait) {
			long seconds = Math.max(1, (nanosToWait + 999_999_999L) / 1_000_000_000L);
			return new Verdict(false, seconds);
		}

	}

	Verdict tryAcquire(UUID tenantId, int ratePerMinute);

}
