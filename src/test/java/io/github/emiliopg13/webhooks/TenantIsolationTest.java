package io.github.emiliopg13.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** A tenant must never see, change, or learn about another tenant's data: every such request is a plain 404. */
class TenantIsolationTest extends IntegrationTest {

	private TestTenant owner;

	private TestTenant intruder;

	private String endpointId;

	private String eventId;

	private String deliveryId;

	@BeforeEach
	void setUp() throws Exception {
		owner = createTenant();
		intruder = createTenant();
		endpointId = createEndpoint(owner, "https://example.com/h", "*").get("id").asString();
		eventId = json.readTree(mvc.perform(as(owner, post("/v1/events")).contentType(MediaType.APPLICATION_JSON)
			.content(publishBody("order.paid", "{\"secret\":\"owner-data\"}")))
			.andReturn()
			.getResponse()
			.getContentAsString()).get("id").asString();
		deliveryId = jdbc.sql("SELECT id FROM deliveries WHERE event_id = :e")
			.param("e", UUID.fromString(eventId))
			.query(UUID.class)
			.single()
			.toString();
		jdbc.sql("""
				INSERT INTO delivery_attempts (delivery_id, attempt_number, started_at, duration_ms, response_status)
				VALUES (:d, 1, now(), 12, 500)
				""").param("d", UUID.fromString(deliveryId)).update();
	}

	@Test
	void ownerCanReadItsOwnData() throws Exception {
		mvc.perform(as(owner, get("/v1/endpoints/" + endpointId))).andExpect(status().isOk());
		mvc.perform(as(owner, get("/v1/events/" + eventId))).andExpect(status().isOk());
		mvc.perform(as(owner, get("/v1/deliveries/" + deliveryId))).andExpect(status().isOk());
		mvc.perform(as(owner, get("/v1/deliveries/" + deliveryId + "/attempts")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1));
	}

	@Test
	void anotherTenantCannotReadChangeOrDeleteAnEndpoint() throws Exception {
		mvc.perform(as(intruder, get("/v1/endpoints/" + endpointId))).andExpect(status().isNotFound());
		mvc.perform(as(intruder, patch("/v1/endpoints/" + endpointId)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://evil.example.com/steal\"}")).andExpect(status().isNotFound());
		mvc.perform(as(intruder, delete("/v1/endpoints/" + endpointId))).andExpect(status().isNotFound());

		mvc.perform(as(owner, get("/v1/endpoints/" + endpointId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.url").value("https://example.com/h"));
		String deliveryStatus = jdbc.sql("SELECT status FROM deliveries WHERE id = :d")
			.param("d", UUID.fromString(deliveryId))
			.query(String.class)
			.single();
		assertThat(deliveryStatus).isEqualTo("pending");
	}

	@Test
	void anotherTenantCannotEnableAnEndpointOrReplayADelivery() throws Exception {
		jdbc.sql("UPDATE endpoints SET active = false, disabled_reason = 'failing' WHERE id = :e")
			.param("e", UUID.fromString(endpointId))
			.update();
		jdbc.sql("UPDATE deliveries SET status = 'failed' WHERE id = :d").param("d", UUID.fromString(deliveryId)).update();

		mvc.perform(as(intruder, post("/v1/endpoints/" + endpointId + "/enable"))).andExpect(status().isNotFound());
		mvc.perform(as(intruder, post("/v1/deliveries/" + deliveryId + "/replay"))).andExpect(status().isNotFound());

		mvc.perform(as(owner, get("/v1/endpoints/" + endpointId)))
			.andExpect(jsonPath("$.active").value(false))
			.andExpect(jsonPath("$.disabled_reason").value("failing"));
	}

	@Test
	void anotherTenantCannotReadEventsDeliveriesOrAttempts() throws Exception {
		mvc.perform(as(intruder, get("/v1/events/" + eventId))).andExpect(status().isNotFound());
		mvc.perform(as(intruder, get("/v1/deliveries/" + deliveryId))).andExpect(status().isNotFound());
		mvc.perform(as(intruder, get("/v1/deliveries/" + deliveryId + "/attempts"))).andExpect(status().isNotFound());
	}

	@Test
	void listingsOfAnotherTenantAreEmpty() throws Exception {
		mvc.perform(as(intruder, get("/v1/endpoints"))).andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(as(intruder, get("/v1/events"))).andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(as(intruder, get("/v1/deliveries"))).andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(as(intruder, get("/v1/deliveries?endpoint_id=" + endpointId)))
			.andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(as(intruder, get("/v1/deliveries?event_id=" + eventId)))
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	void publishingNeverReachesAnotherTenantsEndpoints() throws Exception {
		mvc.perform(as(intruder, post("/v1/events")).contentType(MediaType.APPLICATION_JSON)
			.content(publishBody("order.paid", "{}")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.deliveries").value(0));

		int deliveriesToOwnerEndpoint = jdbc.sql("SELECT count(*) FROM deliveries WHERE endpoint_id = :e")
			.param("e", UUID.fromString(endpointId))
			.query(Integer.class)
			.single();
		assertThat(deliveriesToOwnerEndpoint).isEqualTo(1);
	}

}
