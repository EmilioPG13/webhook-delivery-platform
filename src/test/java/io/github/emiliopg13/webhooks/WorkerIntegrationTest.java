package io.github.emiliopg13.webhooks;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;


/**
 * The whole system with the {@code worker} role on: a real PostgreSQL, a real SQS-compatible queue (ElasticMQ) and
 * a WireMock receiver that stands for the customer. The dispatcher, consumers and reaper run on their own, as in
 * production; the only thing a test controls is the clock, which it moves to skip retry delays.
 */
@ActiveProfiles("worker")
@Import(ElasticMqConfiguration.class)
@TestPropertySource(properties = { "test.database=worker", "webhooks.endpoints.allow-insecure-http=true",
		"webhooks.queue.create-if-missing=true",
		// Short enough to watch a lost message come back; the lease (45 s of the injected clock) is what blocks it.
		"webhooks.queue.visibility-timeout=3s", "webhooks.queue.send-retry-delay=10ms",
		"webhooks.worker.dispatch-interval=200ms", "webhooks.worker.reaper-interval=500ms" })
public abstract class WorkerIntegrationTest extends IntegrationTest {

	@RegisterExtension
	protected static WireMockExtension receiver = WireMockExtension.newInstance()
		.options(wireMockConfig().dynamicPort())
		.build();

	@BeforeEach
	void abandonWhatEarlierTestsLeftInFlight() {
		// The context, and so the dispatcher, outlives a test. A delivery an earlier test left waiting for a retry
		// would fire as soon as a later test moves the clock, and hit this test's receiver.
		jdbc.sql("UPDATE deliveries SET status = 'cancelled' WHERE status IN ('pending', 'enqueued')").update();
	}

	/** A URL on the receiver that no other test uses, so stubs and requests never mix. */
	protected static String freshPath() {
		return "/hooks/" + UUID.randomUUID();
	}

	protected Map<String, Object> delivery(UUID id) {
		return jdbc.sql("SELECT * FROM deliveries WHERE id = :id").param("id", id).query().singleRow();
	}

	protected List<Map<String, Object>> attempts(UUID deliveryId) {
		return jdbc.sql("SELECT * FROM delivery_attempts WHERE delivery_id = :id ORDER BY attempt_number")
			.param("id", deliveryId)
			.query()
			.listOfRows();
	}

	protected Map<String, Object> endpoint(UUID id) {
		return jdbc.sql("SELECT * FROM endpoints WHERE id = :id").param("id", id).query().singleRow();
	}

	protected static Instant instant(Map<String, Object> row, String column) {
		return ((java.sql.Timestamp) row.get(column)).toInstant();
	}

	protected void awaitStatus(UUID deliveryId, String status) {
		await().atMost(Duration.ofSeconds(30))
			.untilAsserted(() -> assertThat(delivery(deliveryId)).containsEntry("status", status));
	}

	protected void awaitAttempts(UUID deliveryId, int count) {
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(attempts(deliveryId)).hasSize(count));
	}

}
