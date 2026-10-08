package io.github.emiliopg13.webhooks.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;

/**
 * Buckets stored in PostgreSQL, so every API instance draws from the same one and the limit holds however many
 * instances run, without adding Redis. Each decision is a short transaction of its own ({@code SELECT ... FOR
 * UPDATE} on the tenant's row), separate from whatever the request does afterwards. The price is a database round
 * trip per publish and a lock per tenant: measured in {@code adr/0002-rate-limiting.md}.
 *
 * <p>If the database is unreachable the exception propagates and the publish fails, which is right: publishing
 * needs the same database anyway.
 */
@Component
@ConditionalOnProperty(name = "webhooks.rate-limit.store", havingValue = "postgres", matchIfMissing = true)
class PostgresPublishRateLimiter implements PublishRateLimiter {

	private final ProxyManager<UUID> buckets;

	PostgresPublishRateLimiter(DataSource dataSource, Clock clock) {
		PrimaryKeyMapper<UUID> uuidKey = (statement, position, tenantId) -> statement.setObject(position, tenantId);
		this.buckets = Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
			.primaryKeyMapper(uuidKey)
			.table("rate_limit_buckets")
			.idColumn("tenant_id")
			.stateColumn("state")
			.clientClock(new ClockTimeMeter(clock))
			.build();
	}

	@Override
	public Verdict tryAcquire(UUID tenantId, int ratePerMinute) {
		// The configuration only applies when the bucket is first created; a changed rate is not picked up for an
		// existing tenant. There is no way to change a tenant's rate yet, so this is a known limit, not a bug.
		ConsumptionProbe probe = buckets.builder()
			.build(tenantId, () -> BucketConfiguration.builder()
				.addLimit(Bandwidth.builder().capacity(ratePerMinute).refillGreedy(ratePerMinute, Duration.ofMinutes(1)).build())
				.build())
			.tryConsumeAndReturnRemaining(1);
		return probe.isConsumed() ? Verdict.ADMITTED : Verdict.rejected(probe.getNanosToWaitForRefill());
	}

}
