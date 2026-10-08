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
 * A worker with room for one delivery at a time must make the others wait, however many messages the consumer
 * loops hand it. Without the limit all three requests would reach the slow receiver together.
 */
@TestPropertySource(properties = { "test.database=worker-concurrency-limit",
		"webhooks.worker.max-concurrent-deliveries=1" })
class ConcurrencyLimitTest extends WorkerIntegrationTest {

	private static final int RECEIVER_DELAY_MS = 700;

	@Test
	void deliveriesBeyondTheLimitWaitForTheOneInFlight() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200).withFixedDelay(RECEIVER_DELAY_MS)));
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		List<UUID> deliveries = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			deliveries.addAll(deliveriesOf(publishEvent(tenant, "order.paid", "{\"n\":" + i + "}")));
		}

		deliveries.forEach(delivery -> awaitStatus(delivery, "delivered"));

		List<Instant> arrivals = receiver.findAll(postRequestedFor(urlEqualTo(path)))
			.stream()
			.map(request -> request.getLoggedDate().toInstant())
			.sorted()
			.toList();
		assertThat(arrivals).hasSize(3);
		// Each request is answered after 700 ms; with one slot the next cannot start before that.
		for (int i = 1; i < arrivals.size(); i++) {
			assertThat(Duration.between(arrivals.get(i - 1), arrivals.get(i))).isGreaterThanOrEqualTo(Duration.ofMillis(600));
		}
	}

}
