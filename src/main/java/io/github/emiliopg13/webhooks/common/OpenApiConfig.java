package io.github.emiliopg13.webhooks.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import tools.jackson.databind.PropertyNamingStrategies;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	private static final String BEARER = "bearer";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
			.info(new Info().title("Webhook delivery platform")
				.description("Publish events once; they are signed and delivered to every subscribed endpoint, "
						+ "with retries. /v1 uses a tenant API key, /admin uses the operator token. Both go in "
						+ "'Authorization: Bearer ...'.")
				.version("v1"))
			.components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER));
	}

	/**
	 * The JSON on the wire is snake_case (Spring's Jackson 3 mapper), but springdoc builds schemas with its own
	 * Jackson 2 mapper, which does not see that setting. Rename the schema properties with the same strategy so
	 * the documentation matches what clients actually send and receive.
	 */
	@Bean
	OpenApiCustomizer snakeCaseSchemaProperties() {
		return openApi -> {
			if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
				openApi.getComponents().getSchemas().values().forEach(OpenApiConfig::renameProperties);
			}
		};
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void renameProperties(Schema<?> schema) {
		if (schema == null) {
			return;
		}
		Map<String, Schema> properties = schema.getProperties();
		if (properties != null) {
			Map<String, Schema> renamed = new LinkedHashMap<>();
			properties.forEach((name, property) -> {
				renameProperties(property);
				renamed.put(snakeCase(name), property);
			});
			schema.setProperties(renamed);
		}
		if (schema.getRequired() != null) {
			schema.setRequired(schema.getRequired().stream().map(OpenApiConfig::snakeCase).toList());
		}
		renameProperties(schema.getItems());
	}

	// nameForField ignores its configuration and field arguments for the built-in strategies; it is the public
	// way to apply exactly the translation Jackson uses on the wire.
	private static String snakeCase(String name) {
		return PropertyNamingStrategies.SNAKE_CASE.nameForField(null, null, name);
	}

}
