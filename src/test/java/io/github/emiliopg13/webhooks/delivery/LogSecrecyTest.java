package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import io.github.emiliopg13.webhooks.WorkerIntegrationTest;
import tools.jackson.databind.JsonNode;

/**
 * Failure matrix row 26: API keys and endpoint secrets must never reach a log. The application and the web and
 * security layers are put at DEBUG, the most talkative setting, and a full session runs through them: a tenant
 * and its key are issued, the key is used, a wrong and a revoked key are refused, an endpoint and its secret are
 * created, and a signed delivery goes out, fails, and is retried. Every secret the session touched is then
 * searched for in everything that was printed.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = { "test.database=worker-log-secrecy", "logging.level.io.github.emiliopg13=DEBUG",
		"logging.level.org.springframework.web=DEBUG", "logging.level.org.springframework.security=DEBUG" })
class LogSecrecyTest extends WorkerIntegrationTest {

	private static final String MASTER_KEY = "dGVzdC1vbmx5LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=";

	@Test
	void noKeyOrSecretIsEverPrinted(CapturedOutput output) throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(500)));
		TestTenant tenant = createTenant();
		String apiKey = tenant.apiKey();

		// Use the key, then refuse a wrong one and a revoked one.
		mvc.perform(as(tenant, get("/v1/endpoints")));
		String wrongKey = apiKey.substring(0, apiKey.length() - 4) + "XXXX";
		mvc.perform(get("/v1/endpoints").header(AUTHORIZATION, "Bearer " + wrongKey));
		mvc.perform(delete("/admin/api-keys/" + tenant.apiKeyId())
			.header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN));
		mvc.perform(as(tenant, get("/v1/endpoints")));
		TestTenant live = createTenant();

		// An endpoint, its secret, and a signed delivery that fails and is retried.
		JsonNode endpoint = createEndpoint(live, receiver.baseUrl() + path, "order.paid");
		String secret = endpoint.get("secret").asString();
		String secretBase64 = secret.substring("whsec_".length());
		UUID deliveryId = deliveriesOf(publishEvent(live, "order.paid", "{\"card\":\"4242\"}")).getFirst();
		awaitAttempts(deliveryId, 1);

		String printed = output.getAll();
		assertThat(printed).as("the session printed something at all").contains("order.paid");
		assertThat(printed).doesNotContain(apiKey)
			.doesNotContain(wrongKey)
			.doesNotContain(live.apiKey())
			.doesNotContain(secret)
			.doesNotContain(secretBase64)
			.doesNotContain(MASTER_KEY)
			.doesNotContain(ADMIN_TOKEN);
		// Nor a signature: it is not a secret, but it proves the HMAC key was not printed alongside it.
		assertThat(printed).doesNotContain("whsec_");
	}

}
