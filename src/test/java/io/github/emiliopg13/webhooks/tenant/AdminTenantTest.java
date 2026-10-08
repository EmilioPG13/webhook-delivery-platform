package io.github.emiliopg13.webhooks.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import io.github.emiliopg13.webhooks.IntegrationTest;
import tools.jackson.databind.JsonNode;

@ExtendWith(OutputCaptureExtension.class)
class AdminTenantTest extends IntegrationTest {

	@Test
	void createsATenantAndShowsItsFirstKeyOnce() throws Exception {
		MvcResult result = mvc
			.perform(post("/admin/tenants").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Acme\",\"publish_rate_per_min\":120}"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", containsString("no-store")))
			.andExpect(jsonPath("$.name").value("Acme"))
			.andExpect(jsonPath("$.publish_rate_per_min").value(120))
			.andExpect(jsonPath("$.api_key.key", startsWith("whk_")))
			.andReturn();
		JsonNode body = json.readTree(result.getResponse().getContentAsString());
		String key = body.get("api_key").get("key").asString();

		assertThat(body.get("api_key").get("prefix").asString()).isEqualTo(key.substring(0, "whk_".length() + 8));
		assertThat(key).hasSize("whk_".length() + 43);
	}

	@Test
	void defaultsThePublishRate() throws Exception {
		TestTenant tenant = createTenant();

		int rate = jdbc.sql("SELECT publish_rate_per_min FROM tenants WHERE id = :id")
			.param("id", tenant.id())
			.query(Integer.class)
			.single();

		assertThat(rate).isEqualTo(600);
	}

	@Test
	void storesOnlyTheHashOfTheKey() throws Exception {
		TestTenant tenant = createTenant();

		byte[] stored = jdbc.sql("SELECT key_hash FROM api_keys WHERE id = :id")
			.param("id", tenant.apiKeyId())
			.query(byte[].class)
			.single();

		assertThat(stored).isEqualTo(MessageDigest.getInstance("SHA-256").digest(tenant.apiKey().getBytes()));
		int rowsContainingTheKey = jdbc.sql("SELECT count(*) FROM api_keys WHERE prefix = :key")
			.param("key", tenant.apiKey())
			.query(Integer.class)
			.single();
		assertThat(rowsContainingTheKey).isZero();
	}

	@Test
	void keysNeverAppearInTheLogs(CapturedOutput output) throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, get("/v1/endpoints"))).andExpect(status().isOk());
		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Bearer whk_" + "Z".repeat(43)))
			.andExpect(status().isUnauthorized());

		assertThat(output.getAll()).doesNotContain(tenant.apiKey()).doesNotContain("Z".repeat(43));
	}

	@Test
	void rejectsInvalidInput() throws Exception {
		mvc.perform(post("/admin/tenants").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"  \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("name"));
		mvc.perform(post("/admin/tenants").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"ok\",\"publish_rate_per_min\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("publishRatePerMin"));
	}

	@Test
	void issuesAdditionalKeysThatWorkIndependently() throws Exception {
		TestTenant tenant = createTenant();

		String response = mvc
			.perform(post("/admin/tenants/" + tenant.id() + "/api-keys").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		JsonNode second = json.readTree(response);
		String secondKey = second.get("key").asString();

		assertThat(secondKey).isNotEqualTo(tenant.apiKey());
		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Bearer " + secondKey)).andExpect(status().isOk());

		mvc.perform(delete("/admin/api-keys/" + second.get("id").asString()).header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isNoContent());
		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Bearer " + secondKey))
			.andExpect(status().isUnauthorized());
		mvc.perform(as(tenant, get("/v1/endpoints"))).andExpect(status().isOk());
	}

	@Test
	void unknownTenantOrKeyIsNotFound() throws Exception {
		mvc.perform(post("/admin/tenants/" + UUID.randomUUID() + "/api-keys").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isNotFound());
		mvc.perform(delete("/admin/api-keys/" + UUID.randomUUID()).header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isNotFound());
	}

}
