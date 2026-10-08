package io.github.emiliopg13.webhooks.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.emiliopg13.webhooks.OffsetClock;
import io.github.emiliopg13.webhooks.ratelimit.PublishRateLimiter.Verdict;

/** The token bucket's behaviour, with no database. The Postgres limiter must behave identically. */
class InMemoryPublishRateLimiterTest {

	private final OffsetClock clock = new OffsetClock();

	private final PublishRateLimiter limiter = new InMemoryPublishRateLimiter(clock);

	@Test
	void aTenantCanBurstUpToItsRateAndThenIsRejected() {
		UUID tenant = UUID.randomUUID();

		for (int i = 0; i < 60; i++) {
			assertThat(limiter.tryAcquire(tenant, 60).admitted()).as("request %d", i + 1).isTrue();
		}

		Verdict rejected = limiter.tryAcquire(tenant, 60);
		assertThat(rejected.admitted()).isFalse();
		// 60 per minute is one token per second.
		assertThat(rejected.retryAfterSeconds()).isBetween(1L, 2L);
	}

	@Test
	void tokensComeBackAtTheRateOverTime() {
		UUID tenant = UUID.randomUUID();
		for (int i = 0; i < 60; i++) {
			limiter.tryAcquire(tenant, 60);
		}
		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isFalse();

		clock.advance(Duration.ofSeconds(3));

		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isTrue();
		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isTrue();
		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isTrue();
		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isFalse();
	}

	@Test
	void waitingNeverBuysMoreThanOneFullBucket() {
		UUID tenant = UUID.randomUUID();
		limiter.tryAcquire(tenant, 5);

		clock.advance(Duration.ofHours(1));

		for (int i = 0; i < 5; i++) {
			assertThat(limiter.tryAcquire(tenant, 5).admitted()).isTrue();
		}
		assertThat(limiter.tryAcquire(tenant, 5).admitted()).isFalse();
	}

	@Test
	void retryAfterReflectsTheSlowestRates() {
		UUID tenant = UUID.randomUUID();
		limiter.tryAcquire(tenant, 1);

		// One per minute: the next token is about a minute away.
		assertThat(limiter.tryAcquire(tenant, 1).retryAfterSeconds()).isBetween(59L, 60L);
	}

	@Test
	void tenantsDoNotShareABucket() {
		UUID busy = UUID.randomUUID();
		UUID quiet = UUID.randomUUID();
		for (int i = 0; i < 10; i++) {
			limiter.tryAcquire(busy, 10);
		}

		assertThat(limiter.tryAcquire(busy, 10).admitted()).isFalse();
		assertThat(limiter.tryAcquire(quiet, 10).admitted()).isTrue();
	}

	@Test
	void aChangedRateStartsAFreshBucket() {
		UUID tenant = UUID.randomUUID();
		for (int i = 0; i < 2; i++) {
			limiter.tryAcquire(tenant, 2);
		}
		assertThat(limiter.tryAcquire(tenant, 2).admitted()).isFalse();

		assertThat(limiter.tryAcquire(tenant, 100).admitted()).isTrue();
	}

}
