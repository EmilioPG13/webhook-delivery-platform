package io.github.emiliopg13.webhooks.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import io.github.emiliopg13.webhooks.IntegrationTest;

class AuthenticationTest extends IntegrationTest {

	@Test
	void requestWithoutAKeyIsRejectedAsProblemJson() throws Exception {
		mvc.perform(get("/v1/endpoints"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.title").value("Unauthorized"));
	}

	@Test
	void unknownKeyIsRejected() throws Exception {
		String wellFormedButUnknown = "whk_" + "A".repeat(43);

		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Bearer " + wellFormedButUnknown))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void malformedKeyIsRejected() throws Exception {
		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Bearer not-a-key"))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Bearer whk_" + "x".repeat(500)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void otherAuthorizationSchemesAreRejected() throws Exception {
		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Basic dXNlcjpwYXNz"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void validKeyIsAccepted() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, get("/v1/endpoints"))).andExpect(status().isOk());
	}

	@Test
	void revokedKeyIsRejected() throws Exception {
		TestTenant tenant = createTenant();
		mvc.perform(as(tenant, get("/v1/endpoints"))).andExpect(status().isOk());

		mvc.perform(delete("/admin/api-keys/" + tenant.apiKeyId()).header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isNoContent());

		mvc.perform(as(tenant, get("/v1/endpoints"))).andExpect(status().isUnauthorized());
	}

	@Test
	void usingAKeyRecordsLastUsedAt() throws Exception {
		TestTenant tenant = createTenant();
		assertThat(hasBeenUsed(tenant)).isFalse();

		mvc.perform(as(tenant, get("/v1/endpoints"))).andExpect(status().isOk());

		assertThat(hasBeenUsed(tenant)).isTrue();
	}

	@Test
	void adminApiRejectsMissingAndWrongTokens() throws Exception {
		mvc.perform(post("/admin/tenants").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/admin/tenants").header(AUTHORIZATION, "Bearer wrong-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"x\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void aTenantKeyDoesNotOpenTheAdminApi() throws Exception {
		TestTenant tenant = createTenant();

		mvc.perform(as(tenant, post("/admin/tenants")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"x\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void theAdminTokenDoesNotOpenTheTenantApi() throws Exception {
		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void publicPathsNeedNoCredentials() throws Exception {
		mvc.perform(get("/v1/ping")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void unknownPathsAreNotExposedToAnonymousCallers() throws Exception {
		mvc.perform(get("/internal/anything")).andExpect(status().isUnauthorized());
	}

	private boolean hasBeenUsed(TestTenant tenant) {
		return jdbc.sql("SELECT last_used_at IS NOT NULL FROM api_keys WHERE id = :id")
			.param("id", tenant.apiKeyId())
			.query(Boolean.class)
			.single();
	}

}
