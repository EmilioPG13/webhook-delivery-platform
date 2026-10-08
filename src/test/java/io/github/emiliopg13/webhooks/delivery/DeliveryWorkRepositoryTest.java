package io.github.emiliopg13.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import io.github.emiliopg13.webhooks.IntegrationTest;
import io.github.emiliopg13.webhooks.common.Db;
import io.github.emiliopg13.webhooks.delivery.DeliveryWorkRepository.WorkItem;

/**
 * The worker's SQL against a real PostgreSQL. The dispatcher and reaper statements act on the whole table, so this
 * class has a context, and therefore a database, of its own: it must not flip the deliveries other tests assert on.
 */
@TestPropertySource(properties = "test.database=delivery-work-repository")
class DeliveryWorkRepositoryTest extends IntegrationTest {

	@Autowired
	private DeliveryWorkRepository work;

	private TestTenant tenant;

	private UUID endpointId;

	private UUID eventId;

	@BeforeEach
	void setUp() throws Exception {
		// Tests in this class share one database; start each from a table with nothing in flight.
		jdbc.sql("UPDATE deliveries SET status = 'cancelled' WHERE status IN ('pending', 'enqueued')").update();
		tenant = createTenant();
		endpointId = UUID.fromString(createEndpoint(tenant, "https://example.com/hook", "order.paid").get("id").asString());
		eventId = publishEvent(tenant, "order.paid", "{}");
	}

	// --- dispatcher ---------------------------------------------------------------------------------------------

	@Test
	void dispatcherTakesDueDeliveriesAndMarksThemEnqueued() {
		UUID delivery = deliveriesOf(eventId).getFirst();
		Instant now = later();

		List<UUID> taken = work.markDueAsEnqueued(now, 100);

		assertThat(taken).containsExactly(delivery);
		assertThat(row(delivery)).containsEntry("status", "enqueued").containsKey("enqueued_at");
	}

	@Test
	void dispatcherLeavesDeliveriesThatAreNotDueYet() {
		UUID delivery = deliveriesOf(eventId).getFirst();
		Instant now = later();
		jdbc.sql("UPDATE deliveries SET next_attempt_at = :at WHERE id = :id")
			.param("at", Db.timestamp(now.plusSeconds(60)))
			.param("id", delivery)
			.update();

		assertThat(work.markDueAsEnqueued(now, 100)).isEmpty();
		assertThat(work.markDueAsEnqueued(now.plusSeconds(61), 100)).containsExactly(delivery);
	}

	@Test
	void dispatcherSkipsDeliveriesOfInactiveEndpoints() {
		jdbc.sql("UPDATE endpoints SET active = false WHERE id = :id").param("id", endpointId).update();

		assertThat(work.markDueAsEnqueued(later(), 100)).isEmpty();
	}

	@Test
	void dispatcherTakesOldestFirstAndHonorsTheLimit() {
		insertDeliveries(5);

		List<UUID> first = work.markDueAsEnqueued(later(), 3);
		List<UUID> second = work.markDueAsEnqueued(later(), 3);

		assertThat(first).hasSize(3);
		assertThat(second).hasSize(3);
		assertThat(work.markDueAsEnqueued(later(), 3)).isEmpty();
		assertThat(new HashSet<>(first)).doesNotContainAnyElementsOf(second);
	}

	@Test
	void twoDispatchersRunningTogetherNeverTakeTheSameDelivery() throws Exception {
		int total = 600;
		insertDeliveries(total);
		CountDownLatch go = new CountDownLatch(1);
		List<UUID> taken = Collections.synchronizedList(new ArrayList<>());
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> dispatchers = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				dispatchers.add(pool.submit(() -> {
					go.await();
					List<UUID> batch;
					do {
						batch = work.markDueAsEnqueued(later(), 25);
						taken.addAll(batch);
					}
					while (!batch.isEmpty());
					return null;
				}));
			}
			go.countDown();
			for (Future<?> dispatcher : dispatchers) {
				dispatcher.get();
			}
		}

		Set<UUID> distinct = new HashSet<>(taken);
		assertThat(taken).hasSize(distinct.size());
		assertThat(distinct).hasSize(total + 1); // the 600 plus the delivery created in setUp
	}

	// --- claim --------------------------------------------------------------------------------------------------

	@Test
	void claimReturnsEverythingNeededToDeliver() {
		UUID delivery = enqueue();
		Instant now = Instant.now();

		WorkItem item = work.claim(delivery, now, now.plusSeconds(45)).orElseThrow();

		assertThat(item.deliveryId()).isEqualTo(delivery);
		assertThat(item.eventId()).isEqualTo(eventId);
		assertThat(item.eventType()).isEqualTo("order.paid");
		assertThat(item.payloadJson()).isEqualTo("{}");
		assertThat(item.endpointId()).isEqualTo(endpointId);
		assertThat(item.url()).isEqualTo("https://example.com/hook");
		assertThat(item.secretCiphertext()).isNotEmpty();
		assertThat(item.attemptCount()).isZero();
	}

	@Test
	void claimFailsWhileAnotherLeaseIsLive() {
		UUID delivery = enqueue();
		Instant now = Instant.now();
		assertThat(work.claim(delivery, now, now.plusSeconds(45))).isPresent();

		assertThat(work.claim(delivery, now.plusSeconds(10), now.plusSeconds(55))).isEmpty();
	}

	@Test
	void claimSucceedsOnceTheLeaseHasExpired() {
		UUID delivery = enqueue();
		Instant now = Instant.now();
		assertThat(work.claim(delivery, now, now.plusSeconds(45))).isPresent();

		assertThat(work.claim(delivery, now.plusSeconds(46), now.plusSeconds(91))).isPresent();
	}

	@Test
	void claimFailsForDeliveriesThatAreNotEnqueued() {
		UUID pending = deliveriesOf(eventId).getFirst();
		Instant now = Instant.now();

		assertThat(work.claim(pending, now, now.plusSeconds(45))).isEmpty();
	}

	@Test
	void ofTwoWorkersHoldingTheSameMessageExactlyOneClaims() throws Exception {
		for (int round = 0; round < 25; round++) {
			UUID delivery = enqueue();
			CountDownLatch go = new CountDownLatch(1);
			Instant now = Instant.now();
			try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
				List<Future<Boolean>> workers = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					workers.add(pool.submit(() -> {
						go.await();
						return work.claim(delivery, now, now.plusSeconds(45)).isPresent();
					}));
				}
				go.countDown();
				int winners = 0;
				for (Future<Boolean> worker : workers) {
					winners += worker.get() ? 1 : 0;
				}
				assertThat(winners).as("round %d", round).isEqualTo(1);
			}
			jdbc.sql("UPDATE deliveries SET status = 'cancelled' WHERE id = :id").param("id", delivery).update();
		}
	}

	// --- reaper -------------------------------------------------------------------------------------------------

	@Test
	void reaperReturnsDeliveriesStuckInEnqueued() {
		UUID delivery = deliveriesOf(eventId).getFirst();
		Instant now = Instant.now();
		markEnqueued(delivery, now.minus(Duration.ofMinutes(3)), null);

		assertThat(work.returnStuckToPending(now, now.minus(Duration.ofMinutes(2)))).isEqualTo(1);
		assertThat(row(delivery)).containsEntry("status", "pending");
	}

	@Test
	void reaperLeavesRecentlyEnqueuedDeliveries() {
		UUID delivery = deliveriesOf(eventId).getFirst();
		Instant now = Instant.now();
		markEnqueued(delivery, now.minus(Duration.ofSeconds(30)), null);

		assertThat(work.returnStuckToPending(now, now.minus(Duration.ofMinutes(2)))).isZero();
		assertThat(row(delivery)).containsEntry("status", "enqueued");
	}

	@Test
	void reaperRespectsALiveLeaseButNotAnExpiredOne() {
		UUID delivery = deliveriesOf(eventId).getFirst();
		Instant now = Instant.now();
		markEnqueued(delivery, now.minus(Duration.ofMinutes(5)), now.plusSeconds(20));

		assertThat(work.returnStuckToPending(now, now.minus(Duration.ofMinutes(2)))).isZero();
		assertThat(work.returnStuckToPending(now.plusSeconds(21), now.plusSeconds(21).minus(Duration.ofMinutes(2))))
			.isEqualTo(1);
		assertThat(row(delivery)).containsEntry("status", "pending").containsEntry("lease_until", null);
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	/**
	 * An "now" safely after every row the test just inserted. Those rows get {@code next_attempt_at DEFAULT now()}
	 * from the database's clock; the JVM's clock may differ by a few milliseconds (the database runs in a
	 * container), and a row stamped slightly ahead of the JVM would not be due yet.
	 */
	private static Instant later() {
		return Instant.now().plusSeconds(60);
	}

	/** The delivery from setUp, taken by the dispatcher as in production. */
	private UUID enqueue() {
		UUID delivery = insertDeliveries(1).getFirst();
		jdbc.sql("UPDATE deliveries SET status = 'enqueued', enqueued_at = :at WHERE id = :id")
			.param("at", Db.timestamp(Instant.now()))
			.param("id", delivery)
			.update();
		return delivery;
	}

	private void markEnqueued(UUID delivery, Instant enqueuedAt, Instant leaseUntil) {
		jdbc.sql("UPDATE deliveries SET status = 'enqueued', enqueued_at = :at, lease_until = :lease WHERE id = :id")
			.param("at", Db.timestamp(enqueuedAt))
			.param("lease", Db.timestamp(leaseUntil))
			.param("id", delivery)
			.update();
	}

	/** Bulk-inserts pending deliveries for the event and endpoint of setUp. */
	private List<UUID> insertDeliveries(int count) {
		return jdbc.sql("""
				INSERT INTO deliveries (tenant_id, event_id, endpoint_id)
				SELECT :tenantId, :eventId, :endpointId FROM generate_series(1, :count)
				RETURNING id
				""")
			.param("tenantId", tenant.id())
			.param("eventId", eventId)
			.param("endpointId", endpointId)
			.param("count", count)
			.query(UUID.class)
			.list();
	}

	private Map<String, Object> row(UUID delivery) {
		return jdbc.sql("SELECT * FROM deliveries WHERE id = :id").param("id", delivery).query().singleRow();
	}

}
