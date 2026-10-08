package io.github.emiliopg13.webhooks.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.github.emiliopg13.webhooks.IntegrationTest;

class PublishEventTest extends IntegrationTest {

	private MockHttpServletRequestBuilder publish(TestTenant tenant, String body) {
		return as(tenant, post("/v1/events")).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MockHttpServletRequestBuilder publishWithKey(TestTenant tenant, String key, String body) {
		return publish(tenant, body).header("Idempotency-Key", key);
	}

	@Test
	void createsOneDeliveryPerSubscribedActiveEndpoint() throws Exception {
		TestTenant tenant = createTenant();
		String exact = createEndpoint(tenant, "https://example.com/exact", "order.paid").get("id").asString();
		String wildcard = createEndpoint(tenant, "https://example.com/all", "*").get("id").asString();
		createEndpoint(tenant, "https://example.com/other", "order.refunded");
		String deleted = createEndpoint(tenant, "https://example.com/gone", "order.paid").get("id").asString();
		mvc.perform(as(tenant, delete("/v1/endpoints/" + deleted))).andExpect(status().isNoContent());

		MvcResult result = mvc.perform(publish(tenant, publishBody("order.paid", "{\"order_id\":42}")))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Location", startsWith("/v1/events/")))
			.andExpect(jsonPath("$.type").value("order.paid"))
			.andExpect(jsonPath("$.deliveries").value(2))
			.andExpect(jsonPath("$.created_at").exists())
			.andReturn();
		UUID eventId = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());

		List<UUID> targets = jdbc.sql("SELECT endpoint_id FROM deliveries WHERE event_id = :id")
			.param("id", eventId)
			.query(UUID.class)
			.list();
		assertThat(targets).containsExactlyInAnyOrder(UUID.fromString(exact), UUID.fromString(wildcard));
		List<String> states = jdbc.sql("SELECT DISTINCT status FROM deliveries WHERE event_id = :id")
			.param("id", eventId)
			.query(String.class)
			.list();
		assertThat(states).containsExactly("pending");
	}

	@Test
	void deliveriesCarryTheEventsTenantNotAnythingFromTheClient() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/h", "*");

		mvc.perform(publish(tenant, publishBody("a.b", "{}"))).andExpect(status().isAccepted());

		Set<UUID> tenants = Set.copyOf(jdbc.sql("""
				SELECT DISTINCT d.tenant_id FROM deliveries d JOIN events e ON e.id = d.event_id
				WHERE e.tenant_id = :t
				""").param("t", tenant.id()).query(UUID.class).list());
		assertThat(tenants).containsExactly(tenant.id());
	}

	@Test
	void publishingWithoutSubscribersStillStoresTheEvent() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(publish(tenant, publishBody("nobody.listens", "{\"x\":1}")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.deliveries").value(0));

		int events = jdbc.sql("SELECT count(*) FROM events WHERE tenant_id = :t")
			.param("t", tenant.id())
			.query(Integer.class)
			.single();
		assertThat(events).isEqualTo(1);
	}

	@Test
	void withoutAnIdempotencyKeyEveryPublishIsANewEvent() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/h", "*");

		mvc.perform(publish(tenant, publishBody("a.b", "{}"))).andExpect(status().isAccepted());
		mvc.perform(publish(tenant, publishBody("a.b", "{}"))).andExpect(status().isAccepted());

		int events = jdbc.sql("SELECT count(*) FROM events WHERE tenant_id = :t")
			.param("t", tenant.id())
			.query(Integer.class)
			.single();
		assertThat(events).isEqualTo(2);
	}

	@Test
	void repeatingARequestWithTheSameKeyReturnsTheOriginalResult() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/a", "*");
		createEndpoint(tenant, "https://example.com/b", "*");
		String body = publishBody("order.paid", "{\"order_id\":7}");

		MvcResult first = mvc.perform(publishWithKey(tenant, "order-7", body))
			.andExpect(status().isAccepted())
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andReturn();
		MvcResult second = mvc.perform(publishWithKey(tenant, "order-7", body))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.deliveries").value(2))
			.andReturn();

		assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
		assertThat(count("events", tenant)).isEqualTo(1);
		assertThat(count("deliveries", tenant)).isEqualTo(2);
	}

	@Test
	void sameKeyWithADifferentBodyIsRejected() throws Exception {
		TestTenant tenant = createTenant();
		mvc.perform(publishWithKey(tenant, "k-1", publishBody("order.paid", "{\"n\":1}")))
			.andExpect(status().isAccepted());

		mvc.perform(publishWithKey(tenant, "k-1", publishBody("order.paid", "{\"n\":2}")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.status").value(422))
			.andExpect(jsonPath("$.detail").value("Idempotency-Key was already used with a different request body."));

		assertThat(count("events", tenant)).isEqualTo(1);
	}

	@Test
	void sameBodyWithDifferentWhitespaceCountsAsADifferentRequest() throws Exception {
		TestTenant tenant = createTenant();
		mvc.perform(publishWithKey(tenant, "k-ws", "{\"type\":\"a.b\",\"data\":{}}")).andExpect(status().isAccepted());

		mvc.perform(publishWithKey(tenant, "k-ws", "{\"type\": \"a.b\", \"data\": {}}"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void idempotencyKeysAreScopedToTheTenant() throws Exception {
		TestTenant first = createTenant();
		TestTenant second = createTenant();
		String body = publishBody("a.b", "{}");

		String firstId = json.readTree(mvc.perform(publishWithKey(first, "shared-key", body))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString()).get("id").asString();
		MvcResult other = mvc.perform(publishWithKey(second, "shared-key", body))
			.andExpect(status().isAccepted())
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andReturn();

		assertThat(json.readTree(other.getResponse().getContentAsString()).get("id").asString()).isNotEqualTo(firstId);
	}

	@Test
	void storesThePayloadAndServesItBack() throws Exception {
		TestTenant tenant = createTenant();
		String id = json.readTree(mvc.perform(publish(tenant, publishBody("order.paid", "{\"order\":{\"id\":1,\"items\":[1,2]}}")))
			.andReturn()
			.getResponse()
			.getContentAsString()).get("id").asString();

		mvc.perform(as(tenant, get("/v1/events/" + id)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("order.paid"))
			.andExpect(jsonPath("$.data.order.id").value(1))
			.andExpect(jsonPath("$.data.order.items[1]").value(2));
	}

	@Test
	void rejectsInvalidRequests() throws Exception {
		TestTenant tenant = createTenant();

		for (String body : List.of("{\"data\":{}}", "{\"type\":\"\",\"data\":{}}", "{\"type\":\"bad type\",\"data\":{}}",
				"{\"type\":\"a.b\"}", "{\"type\":\"a.b\",\"data\":null}", "{\"type\":\"a.b\",\"data\":[1,2]}",
				"{\"type\":\"a.b\",\"data\":\"text\"}", "{\"type\":\"" + "x".repeat(129) + "\",\"data\":{}}")) {
			mvc.perform(publish(tenant, body)).andExpect(status().isBadRequest());
		}
	}

	@Test
	void reportsTheOffendingFieldForInvalidData() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(publish(tenant, "{\"type\":\"a.b\",\"data\":[1]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("data"));
	}

	@Test
	void rejectsBodiesThatAreNotJson() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(publish(tenant, "not json at all")).andExpect(status().isBadRequest());
		mvc.perform(publish(tenant, "")).andExpect(status().isBadRequest());
	}

	@Test
	void rejectsOversizedBodiesWith413() throws Exception {
		TestTenant tenant = createTenant();
		String huge = "{\"type\":\"a.b\",\"data\":{\"blob\":\"" + "a".repeat(256 * 1024) + "\"}}";

		mvc.perform(publish(tenant, huge))
			.andExpect(status().isContentTooLarge())
			.andExpect(jsonPath("$.status").value(413));
		assertThat(count("events", tenant)).isZero();
	}

	@Test
	void acceptsABodyJustUnderTheLimit() throws Exception {
		TestTenant tenant = createTenant();
		String prefix = "{\"type\":\"a.b\",\"data\":{\"blob\":\"";
		String suffix = "\"}}";
		String body = prefix + "a".repeat(256 * 1024 - prefix.length() - suffix.length()) + suffix;

		mvc.perform(publish(tenant, body)).andExpect(status().isAccepted());
	}

	@Test
	void rejectsBadIdempotencyKeys() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(publishWithKey(tenant, "k".repeat(256), publishBody("a.b", "{}")))
			.andExpect(status().isBadRequest());
		mvc.perform(publishWithKey(tenant, " ", publishBody("a.b", "{}"))).andExpect(status().isBadRequest());
	}

	@Test
	void aFailedPublishLeavesNothingBehind() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/h", "*");

		mvc.perform(publish(tenant, "{\"type\":\"a.b\",\"data\":[1]}")).andExpect(status().isBadRequest());

		assertThat(count("events", tenant)).isZero();
		assertThat(count("deliveries", tenant)).isZero();
	}

	private int count(String table, TestTenant tenant) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
			.param("t", tenant.id())
			.query(Integer.class)
			.single();
	}

}
