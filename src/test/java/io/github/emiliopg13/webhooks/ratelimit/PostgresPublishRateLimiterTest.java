package io.github.emiliopg13.webhooks.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.emiliopg13.webhooks.IntegrationTest;
import io.github.emiliopg13.webhooks.ratelimit.PublishRateLimiter.Verdict;

/** The same bucket, kept in PostgreSQL: it must behave like the in-memory one and, in addition, be shared. */
class PostgresPublishRateLimiterTest extends IntegrationTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PublishRateLimiter limiter;

	@Test
	void theConfiguredLimiterIsTheDatabaseBackedOne() {
		assertThat(limiter).isInstanceOf(PostgresPublishRateLimiter.class);
	}

	@Test
	void behavesLikeATokenBucket() throws Exception {
		UUID tenant = createTenant().id();
		for (int i = 0; i < 60; i++) {
			assertThat(limiter.tryAcquire(tenant, 60).admitted()).as("request %d", i + 1).isTrue();
		}

		Verdict rejected = limiter.tryAcquire(tenant, 60);
		assertThat(rejected.admitted()).isFalse();
		assertThat(rejected.retryAfterSeconds()).isBetween(1L, 2L);

		clock.advance(Duration.ofSeconds(3));

		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isTrue();
		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isTrue();
		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isTrue();
		assertThat(limiter.tryAcquire(tenant, 60).admitted()).isFalse();
	}

	@Test
	void tenantsDoNotShareABucket() throws Exception {
		UUID busy = createTenant().id();
		UUID quiet = createTenant().id();
		for (int i = 0; i < 5; i++) {
			limiter.tryAcquire(busy, 5);
		}

		assertThat(limiter.tryAcquire(busy, 5).admitted()).isFalse();
		assertThat(limiter.tryAcquire(quiet, 5).admitted()).isTrue();
	}

	/**
	 * The reason this limiter exists. Two limiter objects stand for two API instances; 100 requests race, from both
	 * at once, for a bucket of 40. Exactly 40 get through: the count lives in the database, not in either instance.
	 */
	@Test
	void instancesShareOneBucketAndNeverAdmitMoreThanItHolds() throws Exception {
		UUID tenant = createTenant().id();
		PublishRateLimiter instanceA = new PostgresPublishRateLimiter(dataSource, clock);
		PublishRateLimiter instanceB = new PostgresPublishRateLimiter(dataSource, clock);
		int requests = 100;
		CountDownLatch start = new CountDownLatch(1);

		List<Future<Boolean>> results = new ArrayList<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < requests; i++) {
				PublishRateLimiter instance = i % 2 == 0 ? instanceA : instanceB;
				results.add(executor.submit(() -> {
					start.await();
					return instance.tryAcquire(tenant, 40).admitted();
				}));
			}
			start.countDown();
		}

		long admitted = 0;
		for (Future<Boolean> result : results) {
			if (result.get()) {
				admitted++;
			}
		}
		assertThat(admitted).isEqualTo(40);
	}

}
