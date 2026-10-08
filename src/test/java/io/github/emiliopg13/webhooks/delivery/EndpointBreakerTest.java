package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import io.github.emiliopg13.webhooks.WorkerIntegrationTest;
import io.github.emiliopg13.webhooks.common.Db;

/**
 * An endpoint that stays broken is switched off, so it stops costing attempts, and the owner brings it back with
 * {@code enable}. Failure matrix rows 5 and 23, with the demo-sized breaker (5 failures over a minute).
 */
class EndpointBreakerTest extends WorkerIntegrationTest {

	@Test
	void anEndpointThatKeepsFailingIsSwitchedOffAndResumesWhenEnabled() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(500)));
		TestTenant tenant = createTenant();
		UUID endpointId = UUID.fromString(createEndpoint(tenant, receiver.baseUrl() + path, "order.paid").get("id").asString());
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		// Four failures: still on, because the breaker looks at the streak and how long it has lasted.
		for (int attempt = 1; attempt <= 4; attempt++) {
			awaitAttempts(deliveryId, attempt);
			clock.advance(Duration.ofHours(2));
		}
		awaitAttempts(deliveryId, 5);
		assertThat(endpoint(endpointId)).containsEntry("active", false)
			.containsEntry("disabled_reason", "failing")
			.containsEntry("consecutive_failures", 5);

		// The fifth failure left a retry scheduled, but a switched-off endpoint is not dispatched to.
		clock.advance(Duration.ofHours(2));
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(attempts(deliveryId)).hasSize(5);
			assertThat(delivery(deliveryId)).containsEntry("status", "pending");
		});

		// The receiver recovers and the owner switches the endpoint back on: the waiting delivery goes out.
		receiver.stubFor(post(path).atPriority(1).willReturn(aResponse().withStatus(200)));
		mvc.perform(as(tenant, MockMvcRequestBuilders.post("/v1/endpoints/" + endpointId + "/enable")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.consecutive_failures").value(0))
			.andExpect(jsonPath("$.disabled_reason").doesNotExist());
		awaitStatus(deliveryId, "delivered");

		assertThat(attempts(deliveryId)).hasSize(6);
		assertThat(endpoint(endpointId)).containsEntry("active", true)
			.containsEntry("consecutive_failures", 0)
			.containsEntry("failing_since", null);
	}

	@Test
	void goneEndsTheDeliveryAndSwitchesTheEndpointOff() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(410)));
		TestTenant tenant = createTenant();
		UUID endpointId = UUID.fromString(createEndpoint(tenant, receiver.baseUrl() + path, "order.paid").get("id").asString());
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		awaitStatus(deliveryId, "failed");

		assertThat(attempts(deliveryId)).hasSize(1);
		assertThat(endpoint(endpointId)).containsEntry("active", false).containsEntry("disabled_reason", "gone");
		// An event published now finds no active endpoint, so nothing is created for the dead one.
		assertThat(deliveriesOf(publishEvent(tenant, "order.paid", "{}"))).isEmpty();
		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(path)))).hasSize(1);
	}

	@Test
	void pendingDeliveriesOfAnEndpointThatStaysOffAreCancelledAfterAWeek() throws Exception {
		TestTenant tenant = createTenant();
		UUID endpointId = UUID.fromString(createEndpoint(tenant, receiver.baseUrl() + freshPath(), "order.paid").get("id").asString());
		jdbc.sql("UPDATE endpoints SET active = false, disabled_reason = 'failing', disabled_at = :at WHERE id = :id")
			.param("at", Db.timestamp(clock.instant()))
			.param("id", endpointId)
			.update();
		UUID eventId = publishEvent(tenant, "order.paid", "{}");
		// Publishing skips inactive endpoints, so plant the delivery that was waiting when the endpoint went off.
		UUID deliveryId = jdbc.sql("""
				INSERT INTO deliveries (tenant_id, event_id, endpoint_id) VALUES (:tenant, :event, :endpoint)
				RETURNING id
				""")
			.param("tenant", tenant.id())
			.param("event", eventId)
			.param("endpoint", endpointId)
			.query(UUID.class)
			.single();

		clock.advance(Duration.ofDays(6));
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(delivery(deliveryId)).containsEntry("status", "pending"));

		clock.advance(Duration.ofDays(2));
		awaitStatus(deliveryId, "cancelled");
		assertThat(attempts(deliveryId)).isEmpty();
	}

}
