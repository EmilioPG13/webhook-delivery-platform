package io.github.emiliopg13.webhooks.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.TestPropertySource;

import io.github.emiliopg13.webhooks.IntegrationTest;

/**
 * The scrape endpoint with a real Prometheus registry. The default test context swaps it for an in-memory one, so
 * this class turns the exporter back on, and so needs a context and a database of its own.
 */
@AutoConfigureMetrics
@TestPropertySource(properties = "test.database=metrics-endpoint")
class MetricsEndpointTest extends IntegrationTest {

	@Test
	void scrapingNeedsTheOperatorToken() throws Exception {
		mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

		TestTenant tenant = createTenant();
		mvc.perform(get("/actuator/prometheus").header(AUTHORIZATION, "Bearer " + tenant.apiKey()))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/prometheus").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk());
	}

	@Test
	void publishingAndRateLimitingShowUpInTheScrape() throws Exception {
		TestTenant tenant = createTenant(1);
		publishEvent(tenant, "order.paid", "{}");
		mvc.perform(as(tenant, post("/v1/events")
			.contentType("application/json")
			.content("{\"type\":\"order.paid\",\"data\":{}}"))).andExpect(status().isTooManyRequests());

		String scrape = mvc.perform(get("/actuator/prometheus").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(scrape).contains("webhooks_events_published_total{result=\"created\"")
			.contains("webhooks_publish_rate_limited_total")
			// The connection pool, for the "is the database the bottleneck" question.
			.contains("hikaricp_connections_active");
	}

}
