package io.github.emiliopg13.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.emiliopg13.webhooks.IntegrationTest;
import io.github.emiliopg13.webhooks.common.Db;

/**
 * Retention against a real PostgreSQL. The statement acts on every old event in the table, so the class has a
 * database of its own and starts each test from a table with nothing old in it.
 */
@TestPropertySource(properties = "test.database=retention")
class RetentionTest extends IntegrationTest {

	private static final Duration PERIOD = Duration.ofDays(30);

	@Autowired
	private DeliveryWorkRepository work;

	@Autowired
	private DeliveryMetrics metrics;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TestTenant tenant;

	private UUID endpointId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("DELETE FROM delivery_attempts").update();
		jdbc.sql("UPDATE deliveries SET replay_of = NULL").update();
		jdbc.sql("DELETE FROM deliveries").update();
		jdbc.sql("DELETE FROM events").update();
		tenant = createTenant();
		endpointId = UUID.fromString(createEndpoint(tenant, "https://example.com/hook", "order.paid").get("id").asString());
	}

	private RetentionJob job(int batchSize) {
		return new RetentionJob(work, new RetentionProperties(PERIOD, Duration.ofHours(1), batchSize), clock, metrics);
	}

	@Test
	void anOldEventIsDeletedWithItsDeliveriesAndAttempts() throws Exception {
		UUID eventId = publishEvent(tenant, "order.paid", "{}");
		UUID deliveryId = deliveriesOf(eventId).getFirst();
		finish(deliveryId, "delivered");
		attempt(deliveryId);
		age(eventId, Duration.ofDays(31));

		job(100).purge();

		assertThat(count("events", eventId)).isZero();
		assertThat(deliveriesOf(eventId)).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM delivery_attempts WHERE delivery_id = :id")
			.param("id", deliveryId)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void aRecentEventIsKept() throws Exception {
		UUID eventId = publishEvent(tenant, "order.paid", "{}");
		finish(deliveriesOf(eventId).getFirst(), "delivered");
		age(eventId, Duration.ofDays(29));

		job(100).purge();

		assertThat(count("events", eventId)).isOne();
		assertThat(deliveriesOf(eventId)).hasSize(1);
	}

	@Test
	void anOldEventWithADeliveryStillInFlightIsKept() throws Exception {
		UUID waiting = publishEvent(tenant, "order.paid", "{}");
		UUID enqueued = publishEvent(tenant, "order.paid", "{}");
		finish(deliveriesOf(enqueued).getFirst(), "enqueued");
		age(waiting, Duration.ofDays(40));
		age(enqueued, Duration.ofDays(40));

		job(100).purge();

		assertThat(count("events", waiting)).isOne();
		assertThat(count("events", enqueued)).isOne();
	}

	@Test
	void aReplayAndItsOriginalGoTogether() throws Exception {
		UUID eventId = publishEvent(tenant, "order.paid", "{}");
		UUID original = deliveriesOf(eventId).getFirst();
		finish(original, "failed");
		UUID replay = jdbc.sql("""
				INSERT INTO deliveries (tenant_id, event_id, endpoint_id, replay_of, status)
				VALUES (:tenant, :event, :endpoint, :original, 'delivered')
				RETURNING id
				""")
			.param("tenant", tenant.id())
			.param("event", eventId)
			.param("endpoint", endpointId)
			.param("original", original)
			.query(UUID.class)
			.single();
		attempt(replay);
		age(eventId, Duration.ofDays(31));

		job(100).purge();

		assertThat(count("events", eventId)).isZero();
		assertThat(deliveriesOf(eventId)).isEmpty();
	}

	@Test
	void theIdempotencyKeyGoesWithTheEventAndCanBeUsedAgain() throws Exception {
		UUID first = publishWithKey("key-1");
		finish(deliveriesOf(first).getFirst(), "delivered");
		age(first, Duration.ofDays(31));

		job(100).purge();

		UUID second = publishWithKey("key-1");
		assertThat(second).isNotEqualTo(first);
	}

	@Test
	void everyBatchIsRunUntilNothingOldIsLeft() throws Exception {
		List<UUID> events = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			UUID eventId = publishEvent(tenant, "order.paid", "{}");
			finish(deliveriesOf(eventId).getFirst(), "delivered");
			age(eventId, Duration.ofDays(31));
			events.add(eventId);
		}
		UUID recent = publishEvent(tenant, "order.paid", "{}");
		finish(deliveriesOf(recent).getFirst(), "delivered");

		job(2).purge();

		assertThat(events).allSatisfy(id -> assertThat(count("events", id)).isZero());
		assertThat(count("events", recent)).isOne();
	}

	@Test
	void theStatementDeletesAtMostTheLimitAndReportsHowMany() throws Exception {
		for (int i = 0; i < 3; i++) {
			UUID eventId = publishEvent(tenant, "order.paid", "{}");
			finish(deliveriesOf(eventId).getFirst(), "delivered");
			age(eventId, Duration.ofDays(31));
		}
		Instant cutoff = clock.instant().minus(PERIOD);

		assertThat(work.deleteExpiredEvents(cutoff, 2)).isEqualTo(2);
		assertThat(work.deleteExpiredEvents(cutoff, 2)).isEqualTo(1);
		assertThat(work.deleteExpiredEvents(cutoff, 2)).isZero();
	}

	@Test
	void anEventThatAReplayIsAttachingADeliveryToIsSkippedInsteadOfBlockingOrFailingIt() throws Exception {
		UUID eventId = publishEvent(tenant, "order.paid", "{}");
		UUID original = deliveriesOf(eventId).getFirst();
		finish(original, "failed");
		age(eventId, Duration.ofDays(31));
		Instant cutoff = clock.instant().minus(PERIOD);

		// The replay inserts its delivery and has not committed yet: its foreign key holds a lock on the event.
		AtomicInteger deletedMeanwhile = new AtomicInteger(-1);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.sql("""
					INSERT INTO deliveries (tenant_id, event_id, endpoint_id, replay_of)
					VALUES (:tenant, :event, :endpoint, :original)
					""")
				.param("tenant", tenant.id())
				.param("event", eventId)
				.param("endpoint", endpointId)
				.param("original", original)
				.update();
			deletedMeanwhile.set(inAnotherThread(() -> work.deleteExpiredEvents(cutoff, 100)));
		});

		assertThat(deletedMeanwhile).hasValue(0);
		assertThat(count("events", eventId)).isOne();
		assertThat(deliveriesOf(eventId)).hasSize(2);
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private UUID publishWithKey(String key) throws Exception {
		String response = mvc
			.perform(as(tenant, post("/v1/events")).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(publishBody("order.paid", "{}")))
			.andExpect(status().is2xxSuccessful())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(json.readTree(response).get("id").asString());
	}

	private void finish(UUID deliveryId, String status) {
		jdbc.sql("UPDATE deliveries SET status = :status WHERE id = :id")
			.param("status", status)
			.param("id", deliveryId)
			.update();
	}

	private void attempt(UUID deliveryId) {
		jdbc.sql("""
				INSERT INTO delivery_attempts (delivery_id, attempt_number, started_at, duration_ms, response_status)
				VALUES (:id, 1, :at, 12, 200)
				""")
			.param("id", deliveryId)
			.param("at", Db.timestamp(clock.instant()))
			.update();
	}

	private void age(UUID eventId, Duration by) {
		jdbc.sql("UPDATE events SET created_at = :at WHERE id = :id")
			.param("at", Db.timestamp(clock.instant().minus(by)))
			.param("id", eventId)
			.update();
	}

	/** Runs the call on its own thread, so it gets its own connection and cannot join the open transaction. */
	private static int inAnotherThread(Callable<Integer> call) {
		// Not try-with-resources: close() would wait for a blocked statement, which waits for the transaction that
		// is waiting here. shutdownNow() lets the failure propagate, roll the transaction back and free the lock.
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			return executor.submit(call).get(5, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			throw new IllegalStateException("The statement blocked or failed instead of skipping the locked event", e);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private long count(String table, UUID id) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :id").param("id", id).query(Long.class).single();
	}

}
