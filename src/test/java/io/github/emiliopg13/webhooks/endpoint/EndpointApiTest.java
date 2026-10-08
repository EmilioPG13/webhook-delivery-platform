package io.github.emiliopg13.webhooks.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import io.github.emiliopg13.webhooks.IntegrationTest;
import tools.jackson.databind.JsonNode;

class EndpointApiTest extends IntegrationTest {

	@Autowired
	private EndpointSecretCipher cipher;

	@Test
	void createsAnEndpointAndShowsTheSecretOnce() throws Exception {
		TestTenant tenant = createTenant();

		var created = mvc
			.perform(as(tenant, post("/v1/endpoints")).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"url":"https://example.com/hooks","description":"Accounting","event_types":["order.paid","order.refunded"]}
						"""))
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", containsString("no-store")))
			.andExpect(jsonPath("$.url").value("https://example.com/hooks"))
			.andExpect(jsonPath("$.description").value("Accounting"))
			.andExpect(jsonPath("$.event_types[0]").value("order.paid"))
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.consecutive_failures").value(0))
			.andReturn();
		JsonNode body = json.readTree(created.getResponse().getContentAsString());
		String id = body.get("id").asString();

		assertThat(created.getResponse().getHeader("Location")).isEqualTo("/v1/endpoints/" + id);
		assertThat(body.get("secret").asString()).startsWith("whsec_");

		mvc.perform(as(tenant, get("/v1/endpoints/" + id)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.secret").doesNotExist());
		mvc.perform(as(tenant, get("/v1/endpoints")))
			.andExpect(jsonPath("$.data[0].secret").doesNotExist());
	}

	@Test
	void secretIsRandom32BytesStoredEncryptedAndBoundToTheEndpoint() throws Exception {
		TestTenant tenant = createTenant();
		JsonNode created = createEndpoint(tenant, "https://example.com/a", "*");
		UUID id = UUID.fromString(created.get("id").asString());
		String secret = created.get("secret").asString();
		byte[] plaintext = Base64.getDecoder().decode(secret.substring("whsec_".length()));

		byte[] stored = jdbc.sql("SELECT secret_ciphertext FROM endpoints WHERE id = :id")
			.param("id", id)
			.query(byte[].class)
			.single();
		int version = jdbc.sql("SELECT secret_key_version FROM endpoints WHERE id = :id")
			.param("id", id)
			.query(Integer.class)
			.single();

		assertThat(plaintext).hasSize(32);
		assertThat(new String(stored, StandardCharsets.ISO_8859_1))
			.doesNotContain(new String(plaintext, StandardCharsets.ISO_8859_1));
		assertThat(cipher.decrypt(stored, id)).isEqualTo(plaintext);
		assertThat(version).isEqualTo(1);
		JsonNode another = createEndpoint(tenant, "https://example.com/a", "*");
		assertThat(another.get("secret").asString()).isNotEqualTo(secret);
	}

	@Test
	void rejectsInvalidUrls() throws Exception {
		TestTenant tenant = createTenant();

		for (String url : List.of("http://example.com/h", "https://user:pw@example.com/h", "ftp://example.com",
				"not a url", "https://example.com/" + "a".repeat(2049))) {
			mvc.perform(as(tenant, post("/v1/endpoints")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"" + url + "\",\"event_types\":[\"*\"]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("url"));
		}
	}

	@Test
	void rejectsInvalidEventTypes() throws Exception {
		TestTenant tenant = createTenant();

		for (String types : List.of("[]", "[\"bad type!\"]", "[\"\"]", "[\" \"]", "null")) {
			mvc.perform(as(tenant, post("/v1/endpoints")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://example.com/h\",\"event_types\":" + types + "}"))
				.andExpect(status().isBadRequest());
		}
	}

	@Test
	void rejectsMalformedJson() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, post("/v1/endpoints")).contentType(MediaType.APPLICATION_JSON).content("{nope"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void duplicatedEventTypesAreCollapsed() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, post("/v1/endpoints")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://example.com/h\",\"event_types\":[\"a\",\"b\",\"a\"]}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.event_types.length()").value(2));
	}

	@Test
	void updatesOnlyTheFieldsSent() throws Exception {
		TestTenant tenant = createTenant();
		String id = createEndpoint(tenant, "https://example.com/old", "a.b").get("id").asString();

		mvc.perform(as(tenant, patch("/v1/endpoints/" + id)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://example.com/new\",\"description\":\"hello\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.url").value("https://example.com/new"))
			.andExpect(jsonPath("$.description").value("hello"))
			.andExpect(jsonPath("$.event_types[0]").value("a.b"));

		mvc.perform(as(tenant, patch("/v1/endpoints/" + id)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"event_types\":[\"x.y\",\"*\"],\"description\":\"\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.url").value("https://example.com/new"))
			.andExpect(jsonPath("$.description").doesNotExist())
			.andExpect(jsonPath("$.event_types.length()").value(2));
	}

	@Test
	void updateRevalidatesTheUrl() throws Exception {
		TestTenant tenant = createTenant();
		String id = createEndpoint(tenant, "https://example.com/old", "a.b").get("id").asString();

		mvc.perform(as(tenant, patch("/v1/endpoints/" + id)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"http://example.com/insecure\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("url"));
	}

	@Test
	void deletingHidesTheEndpointAndCancelsItsOpenDeliveries() throws Exception {
		TestTenant tenant = createTenant();
		String id = createEndpoint(tenant, "https://example.com/h", "order.paid").get("id").asString();
		mvc.perform(as(tenant, post("/v1/events")).contentType(MediaType.APPLICATION_JSON)
			.content(publishBody("order.paid", "{}"))).andExpect(status().isAccepted());

		mvc.perform(as(tenant, delete("/v1/endpoints/" + id))).andExpect(status().isNoContent());

		mvc.perform(as(tenant, get("/v1/endpoints/" + id))).andExpect(status().isNotFound());
		mvc.perform(as(tenant, get("/v1/endpoints"))).andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(as(tenant, delete("/v1/endpoints/" + id))).andExpect(status().isNotFound());
		List<String> statuses = jdbc.sql("SELECT status FROM deliveries WHERE endpoint_id = :id")
			.param("id", UUID.fromString(id))
			.query(String.class)
			.list();
		assertThat(statuses).containsExactly("cancelled");
	}

	@Test
	void deletedEndpointsStopReceivingNewEvents() throws Exception {
		TestTenant tenant = createTenant();
		String id = createEndpoint(tenant, "https://example.com/h", "*").get("id").asString();
		mvc.perform(as(tenant, delete("/v1/endpoints/" + id))).andExpect(status().isNoContent());

		mvc.perform(as(tenant, post("/v1/events")).contentType(MediaType.APPLICATION_JSON)
			.content(publishBody("order.paid", "{}")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.deliveries").value(0));
	}

	@Test
	void listsNewestFirstWithCursorPagination() throws Exception {
		TestTenant tenant = createTenant();
		List<String> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			created.add(createEndpoint(tenant, "https://example.com/" + i, "*").get("id").asString());
		}

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			String url = "/v1/endpoints?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
			JsonNode page = json.readTree(mvc.perform(as(tenant, get(url)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString());
			page.get("data").forEach(item -> seen.add(item.get("id").asString()));
			JsonNode next = page.get("next_cursor");
			cursor = next.isNull() ? null : next.asString();
			pages++;
		}
		while (cursor != null);

		assertThat(pages).isEqualTo(3);
		assertThat(seen).containsExactlyElementsOf(created.reversed());
	}

	@Test
	void rejectsBadPaginationParameters() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, get("/v1/endpoints?limit=0"))).andExpect(status().isBadRequest());
		mvc.perform(as(tenant, get("/v1/endpoints?limit=101"))).andExpect(status().isBadRequest());
		mvc.perform(as(tenant, get("/v1/endpoints?cursor=garbage"))).andExpect(status().isBadRequest());
	}

	@Test
	void unknownIdIsNotFound() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, get("/v1/endpoints/" + UUID.randomUUID()))).andExpect(status().isNotFound());
		mvc.perform(as(tenant, get("/v1/endpoints/not-a-uuid"))).andExpect(status().isBadRequest());
	}

}
