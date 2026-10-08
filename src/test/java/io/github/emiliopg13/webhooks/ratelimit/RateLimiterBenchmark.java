package io.github.emiliopg13.webhooks.ratelimit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.emiliopg13.webhooks.IntegrationTest;

/**
 * Measures what a rate limit decision costs with the buckets in memory and in PostgreSQL. Not a test: it asserts
 * nothing and runs only on request, so the numbers in {@code adr/0002-rate-limiting.md} come from a real run and
 * can be reproduced:
 *
 * <pre>
 * .\mvnw.cmd test "-Dtest=RateLimiterBenchmark" "-Dbenchmark=true" "-Dsurefire.failIfNoSpecifiedTests=false"
 * </pre>
 *
 * The result table lands in {@code target/rate-limiter-benchmark.md}. The rate is set so high that nothing is
 * ever rejected: the cost of the decision is what is measured, not the cost of saying no.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class RateLimiterBenchmark extends IntegrationTest {

	private static final int RATE = 1_000_000_000;

	private static final int RUNS = 3;

	private static final int THREADS = 32;

	private static final long CONCURRENT_MILLIS = 4_000;

	private static final int TENANTS = 1_000;

	@Autowired
	private DataSource dataSource;

	private record Row(String store, String scenario, int run, double opsPerSecond, double p50Micros, double p95Micros,
			double p99Micros) {
	}

	@Test
	void measure() throws Exception {
		List<UUID> tenants = createTenants(TENANTS);
		PublishRateLimiter memory = new InMemoryPublishRateLimiter(clock);
		PublishRateLimiter postgres = new PostgresPublishRateLimiter(dataSource, clock);
		List<Row> rows = new ArrayList<>();

		for (int run = 1; run <= RUNS; run++) {
			for (var store : List.of(new Object[] { "memory", memory, 1_000_000 },
					new Object[] { "postgres", postgres, 5_000 })) {
				String name = (String) store[0];
				PublishRateLimiter limiter = (PublishRateLimiter) store[1];
				int sequentialCalls = (int) store[2];
				UUID one = tenants.getFirst();

				rows.add(sequential(name, "1 thread, 1 tenant", run, sequentialCalls, i -> limiter.tryAcquire(one, RATE)));
				rows.add(concurrent(name, THREADS + " threads, 1 tenant", run, i -> limiter.tryAcquire(one, RATE)));
				rows.add(concurrent(name, THREADS + " threads, " + TENANTS + " tenants", run,
						i -> limiter.tryAcquire(tenants.get(ThreadLocalRandom.current().nextInt(TENANTS)), RATE)));
			}
		}
		report(rows);
	}

	private List<UUID> createTenants(int count) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(jdbc.sql("INSERT INTO tenants (name) VALUES (:name) RETURNING id")
				.param("name", "benchmark-" + UUID.randomUUID())
				.query(UUID.class)
				.single());
		}
		return ids;
	}

	private Row sequential(String store, String scenario, int run, int calls, IntFunction<?> operation) {
		for (int i = 0; i < Math.min(calls, 2_000); i++) {
			operation.apply(i);
		}
		long[] nanos = new long[calls];
		long started = System.nanoTime();
		for (int i = 0; i < calls; i++) {
			long before = System.nanoTime();
			operation.apply(i);
			nanos[i] = System.nanoTime() - before;
		}
		double seconds = (System.nanoTime() - started) / 1e9;
		return row(store, scenario, run, calls / seconds, nanos);
	}

	private Row concurrent(String store, String scenario, int run, IntFunction<?> operation) throws Exception {
		for (int i = 0; i < 2_000; i++) {
			operation.apply(i);
		}
		long deadline = System.nanoTime() + CONCURRENT_MILLIS * 1_000_000L;
		List<Future<long[]>> futures = new ArrayList<>();
		long started = System.nanoTime();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int t = 0; t < THREADS; t++) {
				futures.add(executor.submit(() -> {
					long[] samples = new long[1 << 16];
					int count = 0;
					while (System.nanoTime() < deadline) {
						long before = System.nanoTime();
						operation.apply(count);
						if (count == samples.length) {
							samples = Arrays.copyOf(samples, count * 2);
						}
						samples[count++] = System.nanoTime() - before;
					}
					return Arrays.copyOf(samples, count);
				}));
			}
		}
		double seconds = (System.nanoTime() - started) / 1e9;
		long[] all = new long[0];
		for (Future<long[]> future : futures) {
			long[] part = future.get();
			long[] merged = Arrays.copyOf(all, all.length + part.length);
			System.arraycopy(part, 0, merged, all.length, part.length);
			all = merged;
		}
		return row(store, scenario, run, all.length / seconds, all);
	}

	private static Row row(String store, String scenario, int run, double opsPerSecond, long[] nanos) {
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		return new Row(store, scenario, run, opsPerSecond, percentile(sorted, 0.50), percentile(sorted, 0.95),
				percentile(sorted, 0.99));
	}

	private static double percentile(long[] sorted, double fraction) {
		return sorted[(int) Math.min(sorted.length - 1, Math.floor(fraction * sorted.length))] / 1_000.0;
	}

	private void report(List<Row> rows) throws IOException {
		StringBuilder md = new StringBuilder();
		md.append("Rate limiter benchmark, ").append(Instant.now()).append('\n')
			.append("JDK ").append(Runtime.version()).append(", ").append(System.getProperty("os.name")).append(", ")
			.append(Runtime.getRuntime().availableProcessors()).append(" logical processors, PostgreSQL 18 in a local ")
			.append("Docker container on the same machine, Hikari default pool, k=").append(RUNS).append(" runs\n\n")
			.append("| store | scenario | run | decisions/s | p50 us | p95 us | p99 us |\n")
			.append("|---|---|---|---|---|---|---|\n");
		for (Row row : rows) {
			md.append(String.format("| %s | %s | %d | %,.0f | %.1f | %.1f | %.1f |%n", row.store(), row.scenario(),
					row.run(), row.opsPerSecond(), row.p50Micros(), row.p95Micros(), row.p99Micros()));
		}
		Path target = Path.of("target", "rate-limiter-benchmark.md");
		Files.createDirectories(target.getParent());
		Files.writeString(target, md.toString());
		System.out.println(md);
	}

}
