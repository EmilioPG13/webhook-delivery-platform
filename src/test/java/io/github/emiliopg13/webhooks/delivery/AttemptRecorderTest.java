package io.github.emiliopg13.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import io.github.emiliopg13.webhooks.IntegrationTest;
import io.github.emiliopg13.webhooks.common.Db;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.HttpFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.NetworkFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.Success;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.GiveUp;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.RetryAt;

/** The record step: one transaction that stores the attempt, moves the delivery and updates the endpoint. */
@TestPropertySource(properties = "test.database=attempt-recorder")
class AttemptRecorderTest extends IntegrationTest {

	private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

	@Autowired
	private AttemptRecorder recorder;

	private UUID endpointId;

	private UUID deliveryId;

	@BeforeEach
	void setUp() throws Exception {
		TestTenant tenant = createTenant();
		endpointId = UUID.fromString(createEndpoint(tenant, "https://example.com/hook", "order.paid").get("id").asString());
		deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();
		enqueue(deliveryId);
	}

	@Test
	void aSuccessMarksTheDeliveryDeliveredAndStoresTheAttempt() {
		boolean recorded = recorder.record(deliveryId, endpointId, 1, NOW, 42, new Success(200, "ok"), null, NOW);

		assertThat(recorded).isTrue();
		assertThat(delivery()).containsEntry("status", "delivered")
			.containsEntry("attempt_count", 1)
			.containsEntry("last_status_code", 200)
			.containsEntry("last_error_kind", null)
			.containsEntry("lease_until", null)
			.doesNotContainEntry("delivered_at", null);
		assertThat(attempts()).singleElement()
			.satisfies(attempt -> assertThat(attempt).containsEntry("attempt_number", 1)
				.containsEntry("duration_ms", 42)
				.containsEntry("response_status", 200)
				.containsEntry("response_snippet", "ok")
				.containsEntry("error_kind", null));
	}

	@Test
	void aFailureWithAttemptsLeftGoesBackToPendingWithTheNextDate() {
		Instant retryAt = NOW.plusSeconds(30);

		recorder.record(deliveryId, endpointId, 1, NOW, 10, new HttpFailure(500, null, null), new RetryAt(retryAt), NOW);

		Map<String, Object> delivery = delivery();
		assertThat(delivery).containsEntry("status", "pending")
			.containsEntry("attempt_count", 1)
			.containsEntry("last_status_code", 500)
			.containsEntry("last_error_kind", "http_status")
			.containsEntry("lease_until", null);
		assertThat(instant(delivery, "next_attempt_at")).isEqualTo(retryAt);
		assertThat(attempts()).singleElement()
			.satisfies(attempt -> assertThat(attempt).containsEntry("error_kind", "http_status"));
	}

	@Test
	void aNetworkFailureStoresItsKindAndMessageButNoStatus() {
		recorder.record(deliveryId, endpointId, 1, NOW, 5000,
				new NetworkFailure(ErrorKind.TIMEOUT, "SocketTimeoutException: Read timed out"), new RetryAt(NOW.plusSeconds(30)),
				NOW);

		assertThat(delivery()).containsEntry("last_status_code", null).containsEntry("last_error_kind", "timeout");
		assertThat(attempts()).singleElement()
			.satisfies(attempt -> assertThat(attempt).containsEntry("response_status", null)
				.containsEntry("error_kind", "timeout")
				.containsEntry("error_message", "SocketTimeoutException: Read timed out"));
	}

	@Test
	void givingUpMarksTheDeliveryFailed() {
		recorder.record(deliveryId, endpointId, 10, NOW, 10, new HttpFailure(500, null, null), new GiveUp(), NOW);

		assertThat(delivery()).containsEntry("status", "failed").containsEntry("attempt_count", 10);
	}

	@Test
	void failuresBuildAStreakOnTheEndpointAndASuccessClearsIt() {
		recorder.record(deliveryId, endpointId, 1, NOW, 10, new HttpFailure(500, null, null), new RetryAt(NOW), NOW);
		enqueue(deliveryId);
		recorder.record(deliveryId, endpointId, 2, NOW, 10, new HttpFailure(500, null, null), new RetryAt(NOW),
				NOW.plusSeconds(60));

		Map<String, Object> streak = endpoint();
		assertThat(streak).containsEntry("consecutive_failures", 2);
		// The streak keeps the date of its first failure.
		assertThat(instant(streak, "failing_since")).isEqualTo(NOW);

		enqueue(deliveryId);
		recorder.record(deliveryId, endpointId, 3, NOW, 10, new Success(200, null), null, NOW.plusSeconds(120));

		assertThat(endpoint()).containsEntry("consecutive_failures", 0).containsEntry("failing_since", null);
	}

	@Test
	void recordingTheSameAttemptTwiceChangesNothingTheSecondTime() {
		assertThat(recorder.record(deliveryId, endpointId, 1, NOW, 10, new Success(200, null), null, NOW)).isTrue();

		assertThat(recorder.record(deliveryId, endpointId, 1, NOW, 10, new HttpFailure(500, null, null),
				new RetryAt(NOW), NOW)).isFalse();

		assertThat(attempts()).hasSize(1);
		assertThat(delivery()).containsEntry("status", "delivered");
	}

	@Test
	void anAttemptForADeliveryNoLongerInFlightIsRolledBack() {
		jdbc.sql("UPDATE deliveries SET status = 'pending' WHERE id = :id").param("id", deliveryId).update();

		assertThatThrownBy(() -> recorder.record(deliveryId, endpointId, 1, NOW, 10, new Success(200, null), null, NOW))
			.isInstanceOf(IllegalStateException.class);

		assertThat(attempts()).isEmpty();
		assertThat(delivery()).containsEntry("status", "pending").containsEntry("attempt_count", 0);
	}

	private void enqueue(UUID id) {
		jdbc.sql("UPDATE deliveries SET status = 'enqueued', enqueued_at = :at WHERE id = :id")
			.param("at", Db.timestamp(NOW.minus(Duration.ofMinutes(1))))
			.param("id", id)
			.update();
	}

	private static Instant instant(Map<String, Object> row, String column) {
		return ((Timestamp) row.get(column)).toInstant();
	}

	private Map<String, Object> delivery() {
		return jdbc.sql("SELECT * FROM deliveries WHERE id = :id").param("id", deliveryId).query().singleRow();
	}

	private Map<String, Object> endpoint() {
		return jdbc.sql("SELECT * FROM endpoints WHERE id = :id").param("id", endpointId).query().singleRow();
	}

	private List<Map<String, Object>> attempts() {
		return jdbc.sql("SELECT * FROM delivery_attempts WHERE delivery_id = :id ORDER BY attempt_number")
			.param("id", deliveryId)
			.query()
			.listOfRows();
	}

}
