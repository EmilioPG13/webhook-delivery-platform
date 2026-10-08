package io.github.emiliopg13.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import io.github.emiliopg13.webhooks.IntegrationTest;
import tools.jackson.databind.JsonNode;

class DeliveryQueryTest extends IntegrationTest {

	@Test
	void listsEventsNewestFirstWithoutPayloadsAndPaginates() throws Exception {
		TestTenant tenant = createTenant();
		List<String> published = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			published.add(publish(tenant, "order.paid", "{\"n\":" + i + "}"));
		}

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			JsonNode page = getJson(tenant, "/v1/events?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
			page.get("data").forEach(item -> {
				seen.add(item.get("id").asString());
				assertThat(item.has("data")).isFalse();
			});
			cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
			pages++;
		}
		while (cursor != null);

		assertThat(pages).isEqualTo(3);
		assertThat(seen).containsExactlyElementsOf(published.reversed());
	}

	@Test
	void listsDeliveriesAndFiltersThemByEndpointEventAndStatus() throws Exception {
		TestTenant tenant = createTenant();
		String endpointA = createEndpoint(tenant, "https://example.com/a", "*").get("id").asString();
		String endpointB = createEndpoint(tenant, "https://example.com/b", "only.this").get("id").asString();
		String event1 = publish(tenant, "only.this", "{}");
		String event2 = publish(tenant, "something.else", "{}");
		jdbc.sql("UPDATE deliveries SET status = 'delivered' WHERE event_id = :e AND endpoint_id = :p")
			.param("e", UUID.fromString(event1))
			.param("p", UUID.fromString(endpointA))
			.update();

		assertThat(ids(getJson(tenant, "/v1/deliveries"))).hasSize(3);
		assertThat(ids(getJson(tenant, "/v1/deliveries?endpoint_id=" + endpointA))).hasSize(2);
		assertThat(ids(getJson(tenant, "/v1/deliveries?endpoint_id=" + endpointB))).hasSize(1);
		assertThat(ids(getJson(tenant, "/v1/deliveries?event_id=" + event1))).hasSize(2);
		assertThat(ids(getJson(tenant, "/v1/deliveries?event_id=" + event2))).hasSize(1);
		assertThat(ids(getJson(tenant, "/v1/deliveries?status=delivered"))).hasSize(1);
		assertThat(ids(getJson(tenant, "/v1/deliveries?status=pending"))).hasSize(2);
		assertThat(ids(getJson(tenant, "/v1/deliveries?status=failed"))).isEmpty();
		assertThat(ids(getJson(tenant, "/v1/deliveries?event_id=" + event1 + "&status=pending"))).hasSize(1);
	}

	@Test
	void deliveryDetailShowsProgress() throws Exception {
		TestTenant tenant = createTenant();
		String endpoint = createEndpoint(tenant, "https://example.com/a", "*").get("id").asString();
		String event = publish(tenant, "a.b", "{}");
		String deliveryId = ids(getJson(tenant, "/v1/deliveries")).getFirst();

		mvc.perform(as(tenant, get("/v1/deliveries/" + deliveryId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event_id").value(event))
			.andExpect(jsonPath("$.endpoint_id").value(endpoint))
			.andExpect(jsonPath("$.status").value("pending"))
			.andExpect(jsonPath("$.attempt_count").value(0))
			.andExpect(jsonPath("$.replay_of").doesNotExist())
			.andExpect(jsonPath("$.last_status_code").doesNotExist());
	}

	@Test
	void paginatesDeliveries() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/a", "*");
		for (int i = 0; i < 5; i++) {
			publish(tenant, "a.b", "{}");
		}

		List<String> seen = new ArrayList<>();
		String cursor = null;
		do {
			JsonNode page = getJson(tenant, "/v1/deliveries?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
			seen.addAll(ids(page));
			cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
		}
		while (cursor != null);

		assertThat(seen).hasSize(5).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
	}

	@Test
	void listsAttemptsOldestFirstWithCursorPagination() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/a", "*");
		publish(tenant, "a.b", "{}");
		UUID delivery = UUID.fromString(ids(getJson(tenant, "/v1/deliveries")).getFirst());
		for (int attempt = 1; attempt <= 3; attempt++) {
			jdbc.sql("""
					INSERT INTO delivery_attempts (delivery_id, attempt_number, started_at, duration_ms, response_status,
					                               error_kind, response_snippet)
					VALUES (:d, :n, now(), :ms, 503, 'http_status', 'Service Unavailable')
					""").param("d", delivery).param("n", attempt).param("ms", attempt * 100).update();
		}

		JsonNode first = getJson(tenant, "/v1/deliveries/" + delivery + "/attempts?limit=2");
		JsonNode second = getJson(tenant,
				"/v1/deliveries/" + delivery + "/attempts?limit=2&cursor=" + first.get("next_cursor").asString());

		assertThat(first.get("data")).hasSize(2);
		assertThat(first.get("data").get(0).get("attempt_number").asInt()).isEqualTo(1);
		assertThat(first.get("data").get(1).get("attempt_number").asInt()).isEqualTo(2);
		assertThat(first.get("data").get(0).get("response_status").asInt()).isEqualTo(503);
		assertThat(first.get("data").get(0).get("error_kind").asString()).isEqualTo("http_status");
		assertThat(first.get("data").get(0).get("response_snippet").asString()).isEqualTo("Service Unavailable");
		assertThat(second.get("data")).hasSize(1);
		assertThat(second.get("data").get(0).get("attempt_number").asInt()).isEqualTo(3);
		assertThat(second.get("next_cursor").isNull()).isTrue();
	}

	@Test
	void rejectsBadFilters() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, get("/v1/deliveries?status=bogus"))).andExpect(status().isBadRequest());
		mvc.perform(as(tenant, get("/v1/deliveries?endpoint_id=not-a-uuid"))).andExpect(status().isBadRequest());
		mvc.perform(as(tenant, get("/v1/deliveries?limit=0"))).andExpect(status().isBadRequest());
		mvc.perform(as(tenant, get("/v1/events?cursor=garbage"))).andExpect(status().isBadRequest());
	}

	@Test
	void unknownIdsAreNotFound() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, get("/v1/events/" + UUID.randomUUID()))).andExpect(status().isNotFound());
		mvc.perform(as(tenant, get("/v1/deliveries/" + UUID.randomUUID()))).andExpect(status().isNotFound());
		mvc.perform(as(tenant, get("/v1/deliveries/" + UUID.randomUUID() + "/attempts")))
			.andExpect(status().isNotFound());
	}

	private String publish(TestTenant tenant, String type, String data) throws Exception {
		return json.readTree(mvc.perform(as(tenant, post("/v1/events")).contentType(MediaType.APPLICATION_JSON)
			.content(publishBody(type, data)))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString()).get("id").asString();
	}

	private JsonNode getJson(TestTenant tenant, String url) throws Exception {
		return json.readTree(mvc.perform(as(tenant, get(url)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private static List<String> ids(JsonNode page) {
		List<String> ids = new ArrayList<>();
		page.get("data").forEach(item -> ids.add(item.get("id").asString()));
		return ids;
	}

}
