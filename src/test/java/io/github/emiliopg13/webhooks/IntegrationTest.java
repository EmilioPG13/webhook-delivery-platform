package io.github.emiliopg13.webhooks;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for tests that need the whole application against a real PostgreSQL 18. Every subclass shares one Spring
 * context, so one container serves the whole run. Tests do not clean up: each creates its own tenants, which
 * keeps them independent of execution order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
@ActiveProfiles("test")
public abstract class IntegrationTest {

	protected static final String ADMIN_TOKEN = "test-admin-token";

	protected record TestTenant(UUID id, UUID apiKeyId, String apiKey) {
	}

	@Autowired
	protected OffsetClock clock;

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JsonMapper json;

	@Autowired
	protected JdbcClient jdbc;

	@LocalServerPort
	protected int port;

	protected TestTenant createTenant() throws Exception {
		return createTenant("{\"name\":\"tenant-" + UUID.randomUUID() + "\"}");
	}

	/** A tenant allowed to publish only {@code publishRatePerMin} events per minute. */
	protected TestTenant createTenant(int publishRatePerMin) throws Exception {
		return createTenant("{\"name\":\"tenant-" + UUID.randomUUID() + "\",\"publish_rate_per_min\":" + publishRatePerMin + "}");
	}

	private TestTenant createTenant(String requestBody) throws Exception {
		String response = mvc
			.perform(post("/admin/tenants").header(AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		JsonNode body = json.readTree(response);
		return new TestTenant(UUID.fromString(body.get("id").asString()),
				UUID.fromString(body.get("api_key").get("id").asString()), body.get("api_key").get("key").asString());
	}

	protected MockHttpServletRequestBuilder as(TestTenant tenant, MockHttpServletRequestBuilder request) {
		return request.header(AUTHORIZATION, "Bearer " + tenant.apiKey());
	}

	/** Registers an endpoint through the API and returns the response body, which includes the secret. */
	protected JsonNode createEndpoint(TestTenant tenant, String url, String... eventTypes) throws Exception {
		String types = String.join("\",\"", eventTypes);
		String response = mvc
			.perform(as(tenant, post("/v1/endpoints")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"" + url + "\",\"event_types\":[\"" + types + "\"]}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return json.readTree(response);
	}

	/** Publishes through the API and returns the new event's id. */
	protected UUID publishEvent(TestTenant tenant, String type, String dataJson) throws Exception {
		String response = mvc
			.perform(as(tenant, post("/v1/events")).contentType(MediaType.APPLICATION_JSON)
				.content(publishBody(type, dataJson)))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(json.readTree(response).get("id").asString());
	}

	/** Ids of the deliveries an event produced, oldest first. */
	protected List<UUID> deliveriesOf(UUID eventId) {
		return jdbc.sql("SELECT id FROM deliveries WHERE event_id = :eventId ORDER BY id")
			.param("eventId", eventId)
			.query(UUID.class)
			.list();
	}

	protected static String publishBody(String type, String dataJson) {
		return "{\"type\":\"" + type + "\",\"data\":" + dataJson + "}";
	}

}
