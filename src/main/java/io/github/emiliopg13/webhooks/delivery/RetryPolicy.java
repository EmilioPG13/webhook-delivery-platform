package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;

import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.HttpFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.NetworkFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.Success;

/**
 * Decides what happens after a failed attempt. A pure function of the attempt count, the outcome and the current
 * time: the clock and the random source come in from outside, so tests are deterministic.
 *
 * <p>Ten attempts over about 47 hours. The wait after attempt <em>n</em> is {@code DELAYS[n - 1]} with +-20%
 * jitter, so a thousand deliveries that failed together do not come back together. A 429 or 503 may ask for a
 * longer wait with {@code Retry-After}; the longer of the two wins, capped at 24 hours.
 */
final class RetryPolicy {

	/** Attempts made before the delivery is marked failed. */
	static final int MAX_ATTEMPTS = 10;

	private static final List<Duration> DELAYS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
			Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3),
			Duration.ofHours(6), Duration.ofHours(12), Duration.ofHours(24));

	private static final Duration MAX_RETRY_AFTER = Duration.ofHours(24);

	private static final double JITTER = 0.2;

	sealed interface Decision {

	}

	/** Try again at this instant. */
	record RetryAt(Instant at) implements Decision {
	}

	/** No attempts left: the delivery becomes {@code failed}. */
	record GiveUp() implements Decision {
	}

	private final RandomGenerator random;

	RetryPolicy(RandomGenerator random) {
		this.random = random;
	}

	/**
	 * @param attemptsMade how many attempts exist so far, including the one that just failed (1 to
	 * {@value #MAX_ATTEMPTS})
	 * @param failure the outcome of that last attempt; a {@link Success} is not retried
	 */
	Decision decide(int attemptsMade, DeliveryOutcome failure, Instant now) {
		if (attemptsMade < 1) {
			throw new IllegalArgumentException("attemptsMade must be at least 1");
		}
		Duration requested = switch (failure) {
			case Success _ -> throw new IllegalArgumentException("A successful attempt is not retried");
			case HttpFailure(int status, var _, Duration after) when after != null && (status == 429 || status == 503) ->
				after.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : after;
			case HttpFailure _, NetworkFailure _ -> Duration.ZERO;
		};
		if (failure.fatalReason() != null || attemptsMade >= MAX_ATTEMPTS) {
			return new GiveUp();
		}
		Duration scheduled = jitter(DELAYS.get(attemptsMade - 1));
		return new RetryAt(now.plus(requested.compareTo(scheduled) > 0 ? requested : scheduled));
	}

	/** Scales the delay by a random factor in [0.8, 1.2). */
	private Duration jitter(Duration base) {
		double factor = 1 - JITTER + 2 * JITTER * random.nextDouble();
		return Duration.ofMillis(Math.round(base.toMillis() * factor));
	}

}
