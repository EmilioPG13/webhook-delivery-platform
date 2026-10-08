package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import io.github.emiliopg13.webhooks.WorkerIntegrationTest;

/**
 * The SSRF guard inside the running system, with the development switch off (the other worker tests turn it on,
 * because their receiver lives on loopback). Failure matrix rows 11 and 12.
 */
@TestPropertySource(properties = { "test.database=worker-ssrf",
		"webhooks.endpoints.allow-private-destinations=false" })
class BlockedDestinationTest extends WorkerIntegrationTest {

	@Test
	void aReceiverThatResolvesToLoopbackIsRefusedAndItsEndpointIsSwitchedOff() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200)));
		TestTenant tenant = createTenant();
		// Registration accepts a public-looking URL. Pointing it at loopback afterwards stands for a name whose DNS
		// record changed between registration and delivery.
		UUID endpointId = UUID.fromString(createEndpoint(tenant, "https://example.com/hook", "order.paid").get("id").asString());
		jdbc.sql("UPDATE endpoints SET url = :url WHERE id = :id")
			.param("url", receiver.baseUrl() + path)
			.param("id", endpointId)
			.update();
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		awaitStatus(deliveryId, "failed");

		assertThat(attempts(deliveryId)).singleElement()
			.satisfies(attempt -> assertThat(attempt).containsEntry("error_kind", "blocked_destination")
				.containsEntry("response_status", null));
		assertThat(endpoint(endpointId)).containsEntry("active", false)
			.containsEntry("disabled_reason", "blocked_destination");
		// The request never left: the receiver, which listens on loopback, saw nothing.
		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(path)))).isEmpty();
	}

}
