package io.github.emiliopg13.webhooks.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.emiliopg13.webhooks.IntegrationTest;
import tools.jackson.databind.JsonNode;

/** springdoc 3.1.1 is documented for Boot 4.x; this proves it actually works on 4.1. */
class OpenApiTest extends IntegrationTest {

	@Test
	void apiDocsAreServedWithoutCredentials() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.openapi", startsWith("3.")))
			.andExpect(jsonPath("$.info.title").value("Webhook delivery platform"))
			.andExpect(jsonPath("$.paths['/v1/events']").exists())
			.andExpect(jsonPath("$.paths['/v1/endpoints']").exists())
			.andExpect(jsonPath("$.paths['/v1/deliveries/{id}/attempts']").exists())
			.andExpect(jsonPath("$.paths['/admin/tenants']").exists())
			.andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
	}

	@Test
	void documentedSuccessCodesMatchWhatTheApiReturns() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/v1/events'].post.responses['202']").exists())
			.andExpect(jsonPath("$.paths['/v1/events'].post.responses['200']").doesNotExist())
			.andExpect(jsonPath("$.paths['/v1/endpoints'].post.responses['201']").exists())
			.andExpect(jsonPath("$.paths['/v1/endpoints/{id}'].delete.responses['204']").exists())
			.andExpect(jsonPath("$.paths['/admin/tenants'].post.responses['201']").exists());
	}

	@Test
	void schemasUseTheSameSnakeCaseNamesAsTheJsonOnTheWire() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.components.schemas.CreateEndpointRequest.properties.event_types").exists())
			.andExpect(jsonPath("$.components.schemas.PublishEventResponse.properties.created_at").exists())
			.andExpect(jsonPath("$.components.schemas.PageResponseEventSummary.properties.next_cursor").exists());
	}

	/** Guards against a new DTO whose properties slip past the renaming and document a name clients cannot use. */
	@Test
	void noSchemaPropertyIsCamelCase() throws Exception {
		JsonNode docs = json.readTree(mvc.perform(get("/v3/api-docs"))
			.andReturn()
			.getResponse()
			.getContentAsString());

		List<String> camelCase = new ArrayList<>();
		docs.get("components").get("schemas").forEach(schema -> {
			JsonNode properties = schema.get("properties");
			if (properties != null) {
				properties.propertyNames().forEach(name -> {
					if (!name.equals(name.toLowerCase())) {
						camelCase.add(name);
					}
				});
			}
		});

		assertThat(camelCase).isEmpty();
	}

	@Test
	void swaggerUiIsServed() throws Exception {
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

}
