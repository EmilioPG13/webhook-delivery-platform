package io.github.emiliopg13.webhooks.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import io.github.emiliopg13.webhooks.IntegrationTest;

/** Failure matrix row 22: a burst over the quota gets 429 with Retry-After, and later requests are accepted again. */
class RateLimitApiTest extends IntegrationTest {

	@Test
	void publishingBeyondTheQuotaIsRejectedWithRetryAfterAndRecoversWithTime() throws Exception {
		TestTenant tenant = createTenant(3);
		createEndpoint(tenant, "https://example.com/hook", "order.paid");
		for (int i = 0; i < 3; i++) {
			publishEvent(tenant, "order.paid", "{}");
		}

		MvcResult rejected = mvc
			.perform(as(tenant, post("/v1/events")).contentType(MediaType.APPLICATION_JSON)
				.content(publishBody("order.paid", "{}")))
			.andExpect(status().isTooManyRequests())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(429))
			.andExpect(header().exists("Retry-After"))
			.andReturn();
		// 3 per minute is one token every 20 seconds.
		long retryAfter = Long.parseLong(rejected.getResponse().getHeader("Retry-After"));
		assertThat(retryAfter).isBetween(1L, 20L);
		assertThat(jdbc.sql("SELECT count(*) FROM events WHERE tenant_id = :t").param("t", tenant.id()).query(Integer.class).single())
			.as("a rejected request stores nothing")
			.isEqualTo(3);

		clock.advance(Duration.ofSeconds(retryAfter));

		publishEvent(tenant, "order.paid", "{}");
	}

	@Test
	void theLimitBelongsToTheTenantAndOnlyCoversPublishing() throws Exception {
		TestTenant limited = createTenant(1);
		TestTenant other = createTenant(1);
		publishEvent(limited, "order.paid", "{}");

		mvc.perform(as(limited, post("/v1/events")).contentType(MediaType.APPLICATION_JSON)
			.content(publishBody("order.paid", "{}"))).andExpect(status().isTooManyRequests());
		// Reading is not rate limited, and another tenant has its own bucket.
		mvc.perform(as(limited, get("/v1/events"))).andExpect(status().isOk());
		publishEvent(other, "order.paid", "{}");
	}

}
