package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import io.github.emiliopg13.webhooks.WorkerIntegrationTest;

/**
 * A slow endpoint must not take every slot of the worker. The worker has room for two deliveries and one per
 * endpoint: three events for the slow endpoint go one at a time, and a healthy endpoint is served in the slot the
 * slow one cannot use. Without the per-endpoint cap the slow endpoint would hold both slots and the healthy one
 * would wait behind it.
 */
@TestPropertySource(properties = { "test.database=worker-endpoint-cap", "webhooks.worker.max-concurrent-deliveries=2",
		"webhooks.worker.max-concurrent-per-endpoint=1", "webhooks.worker.defer-delay=300ms" })
class EndpointConcurrencyTest extends WorkerIntegrationTest {

	private static final int SLOW_MS = 1_500;

	@Test
	void aSlowEndpointKeepsToItsShareAndAnotherEndpointIsNotStarved() throws Exception {
		String slow = freshPath();
		String fast = freshPath();
		receiver.stubFor(post(slow).willReturn(aResponse().withStatus(200).withFixedDelay(SLOW_MS)));
		receiver.stubFor(post(fast).willReturn(aResponse().withStatus(200)));
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + slow, "slow.event");
		createEndpoint(tenant, receiver.baseUrl() + fast, "fast.event");
		List<UUID> slowDeliveries = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			slowDeliveries.addAll(deliveriesOf(publishEvent(tenant, "slow.event", "{\"n\":" + i + "}")));
		}
		UUID fastDelivery = deliveriesOf(publishEvent(tenant, "fast.event", "{}")).getFirst();

		awaitStatus(fastDelivery, "delivered");
		slowDeliveries.forEach(delivery -> awaitStatus(delivery, "delivered"));

		List<Instant> slowArrivals = arrivals(slow);
		assertThat(slowArrivals).hasSize(3);
		// One at a time: each starts only after the previous one was answered.
		for (int i = 1; i < slowArrivals.size(); i++) {
			assertThat(Duration.between(slowArrivals.get(i - 1), slowArrivals.get(i)))
				.isGreaterThanOrEqualTo(Duration.ofMillis(SLOW_MS - 200));
		}
		// The healthy endpoint was served while the slow one was still on its first call.
		assertThat(arrivals(fast).getFirst()).isBefore(slowArrivals.get(1));
		// Waiting its turn used none of the slow deliveries' attempts.
		slowDeliveries.forEach(delivery -> assertThat(delivery(delivery)).containsEntry("attempt_count", 1));
	}

	private List<Instant> arrivals(String path) {
		return receiver.findAll(postRequestedFor(urlEqualTo(path)))
			.stream()
			.map(request -> request.getLoggedDate().toInstant())
			.sorted()
			.toList();
	}

}
