package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.emiliopg13.webhooks.WorkerIntegrationTest;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

/**
 * The numbers the dashboard is built on. The registry outlives a test, so each test compares against what it saw
 * before it started instead of expecting an absolute count.
 */
class DeliveryMetricsTest extends WorkerIntegrationTest {

	/**
	 * Every tag key the application uses. A tenant or endpoint id is not here and must never be: each distinct value
	 * would become its own time series in Prometheus. {@code le} is Micrometer's own bucket bound on a histogram,
	 * ten values for the attempt timer.
	 */
	private static final Set<String> LOW_CARDINALITY_TAGS = Set.of("outcome", "error_kind", "reason", "result", "state",
			"le");

	@Autowired
	private MeterRegistry registry;

	@Test
	void aDeliveredAttemptIsCountedWithItsDuration() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200)));
		long before = attempts("delivered", "none");
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		awaitStatus(deliveryId, "delivered");

		assertThat(attempts("delivered", "none")).isEqualTo(before + 1);
		assertThat(registry.get("webhooks.delivery.attempts").tag("outcome", "delivered").timer().totalTime(TimeUnit.MILLISECONDS))
			.isPositive();
	}

	@Test
	void aFailedAttemptThatWillBeRetriedCarriesItsErrorKind() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(500)));
		long before = attempts("retry", "http_status");
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		awaitAttempts(deliveryId, 1);

		assertThat(attempts("retry", "http_status")).isEqualTo(before + 1);
	}

	@Test
	void goneEndsTheDeliveryAndCountsTheEndpointAsDisabled() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(410)));
		long failedBefore = attempts("failed", "http_status");
		double disabledBefore = disabled("gone");
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		awaitStatus(deliveryId, "failed");

		assertThat(attempts("failed", "http_status")).isEqualTo(failedBefore + 1);
		assertThat(disabled("gone")).isEqualTo(disabledBefore + 1);
	}

	@Test
	void theBacklogAndQueueGaugesAreRegisteredAndReadable() {
		assertThat(registry.get("webhooks.deliveries.due").gauge().value()).isGreaterThanOrEqualTo(0);
		assertThat(registry.get("webhooks.deliveries.oldest.due.age").gauge().value()).isGreaterThanOrEqualTo(0);
		assertThat(registry.get("webhooks.queue.messages").tag("state", "waiting").gauge().value())
			.isGreaterThanOrEqualTo(0);
		assertThat(registry.get("webhooks.queue.messages").tag("state", "in_flight").gauge().value())
			.isGreaterThanOrEqualTo(0);
	}

	@Test
	void onlyLowCardinalityTagsAreUsed() throws Exception {
		// Make sure the delivery meters exist before looking at their tags.
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200)));
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		awaitStatus(deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst(), "delivered");

		var ownTags = registry.getMeters()
			.stream()
			.filter(meter -> meter.getId().getName().startsWith("webhooks."))
			.map(Meter::getId)
			.flatMap(id -> id.getTags().stream())
			.map(Tag::getKey)
			.collect(Collectors.toSet());

		assertThat(ownTags).isNotEmpty().isSubsetOf(LOW_CARDINALITY_TAGS);
	}

	private long attempts(String outcome, String errorKind) {
		var timer = registry.find("webhooks.delivery.attempts").tag("outcome", outcome).tag("error_kind", errorKind).timer();
		return timer == null ? 0 : timer.count();
	}

	private double disabled(String reason) {
		var counter = registry.find("webhooks.endpoints.disabled").tag("reason", reason).counter();
		return counter == null ? 0 : counter.count();
	}

}
