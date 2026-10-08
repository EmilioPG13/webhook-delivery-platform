package io.github.emiliopg13.webhooks.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Key rotation is configured from environment variables in a deployment, so the names documented in
 * {@code .env.example} must really bind to the retired keys. Spring's relaxed binding rules are easy to
 * misremember, so this checks them instead of trusting them.
 */
class SecretsPropertiesBindingTest {

	@Test
	void retiredKeysBindFromEnvironmentVariables() {
		MutablePropertySources sources = new MutablePropertySources();
		sources.addFirst(new SystemEnvironmentPropertySource("systemEnvironment", Map.<String, Object>of(
				"WEBHOOKS_SECRETS_MASTERKEY", "new-key", "WEBHOOKS_SECRETS_KEYVERSION", "3",
				"WEBHOOKS_SECRETS_RETIREDKEYS_1", "first-key", "WEBHOOKS_SECRETS_RETIREDKEYS_2", "second-key")));

		SecretsProperties bound = new Binder(ConfigurationPropertySources.from(sources))
			.bind("webhooks.secrets", SecretsProperties.class)
			.get();

		assertThat(bound.masterKey()).isEqualTo("new-key");
		assertThat(bound.keyVersion()).isEqualTo(3);
		assertThat(bound.retiredKeys()).containsExactlyInAnyOrderEntriesOf(Map.of(1, "first-key", 2, "second-key"));
	}

	@Test
	void withoutRetiredKeysTheMapIsEmptyNotNull() {
		MutablePropertySources sources = new MutablePropertySources();
		sources.addFirst(new MapPropertySource("props",
				Map.of("webhooks.secrets.master-key", "k", "webhooks.secrets.key-version", "1")));

		SecretsProperties bound = new Binder(ConfigurationPropertySources.from(sources))
			.bind("webhooks.secrets", SecretsProperties.class)
			.get();

		assertThat(bound.retiredKeys()).isEmpty();
	}

}
