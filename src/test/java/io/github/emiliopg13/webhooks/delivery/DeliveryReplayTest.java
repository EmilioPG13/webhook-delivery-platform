package io.github.emiliopg13.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.emiliopg13.webhooks.IntegrationTest;
import tools.jackson.databind.JsonNode;

/**
 * A replay is a new delivery that points at the original and never changes it. Runs without the worker role so a
 * delivery stays exactly where the test put it.
 */
class DeliveryReplayTest extends IntegrationTest {

	private TestTenant tenant;

	private String endpointId;

	private UUID deliveryId;

	@BeforeEach
	void setUp() throws Exception {
		tenant = createTenant();
		endpointId = createEndpoint(tenant, "https://example.com/hook", "order.paid").get("id").asString();
		deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();
	}

	@Test
	void aFailedDeliveryCanBeReplayedAndTheOriginalIsLeftAlone() throws Exception {
		jdbc.sql("UPDATE deliveries SET status = 'failed', attempt_count = 10, last_status_code = 500 WHERE id = :id")
			.param("id", deliveryId)
			.update();

		String response = mvc.perform(as(tenant, post("/v1/deliveries/" + deliveryId + "/replay")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.replay_of").value(deliveryId.toString()))
			.andExpect(jsonPath("$.status").value("pending"))
			.andExpect(jsonPath("$.attempt_count").value(0))
			.andExpect(jsonPath("$.endpoint_id").value(endpointId))
			.andReturn()
			.getResponse()
			.getContentAsString();

		JsonNode replay = json.readTree(response);
		assertThat(replay.get("id").asString()).isNotEqualTo(deliveryId.toString());
		assertThat(replay.get("event_id").asString()).isEqualTo(
				jdbc.sql("SELECT event_id FROM deliveries WHERE id = :id").param("id", deliveryId).query(UUID.class).single().toString());
		assertThat(jdbc.sql("SELECT status || ':' || attempt_count FROM deliveries WHERE id = :id")
			.param("id", deliveryId)
			.query(String.class)
			.single()).isEqualTo("failed:10");
	}

	@Test
	void aDeliveredDeliveryCanBeReplayedToo() throws Exception {
		jdbc.sql("UPDATE deliveries SET status = 'delivered' WHERE id = :id").param("id", deliveryId).update();

		mvc.perform(as(tenant, post("/v1/deliveries/" + deliveryId + "/replay")))
			.andExpect(status().isAccepted())
			.andExpect(header().exists("Location"));
	}

	@Test
	void aDeliveryStillInProgressCannotBeReplayed() throws Exception {
		mvc.perform(as(tenant, post("/v1/deliveries/" + deliveryId + "/replay")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("The delivery is still in progress; wait for it to finish."));

		assertThat(jdbc.sql("SELECT count(*) FROM deliveries WHERE tenant_id = :t")
			.param("t", tenant.id())
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void aDeliveryOfADeletedEndpointCannotBeReplayed() throws Exception {
		mvc.perform(as(tenant, delete("/v1/endpoints/" + endpointId))).andExpect(status().isNoContent());

		mvc.perform(as(tenant, post("/v1/deliveries/" + deliveryId + "/replay")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("The endpoint of this delivery was deleted."));
	}

	@Test
	void aDeliveryOfAnotherTenantIsNotFound() throws Exception {
		jdbc.sql("UPDATE deliveries SET status = 'failed' WHERE id = :id").param("id", deliveryId).update();
		TestTenant intruder = createTenant();

		mvc.perform(as(intruder, post("/v1/deliveries/" + deliveryId + "/replay"))).andExpect(status().isNotFound());
		mvc.perform(as(intruder, post("/v1/deliveries/" + UUID.randomUUID() + "/replay"))).andExpect(status().isNotFound());
	}

}
