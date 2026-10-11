package io.github.emiliopg13.webhooks.endpoint;

import java.util.Map;
import java.util.function.Function;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Reads the master key from AWS Secrets Manager when {@code webhooks.secrets.master-key-secret-id} names a secret,
 * and supplies it as {@code webhooks.secrets.master-key}. Outside AWS the property stays unset and the key comes
 * from {@code WEBHOOKS_MASTER_KEY}, as before.
 *
 * <p>It runs before any bean exists, so {@link SecretsProperties} and {@link EndpointSecretCipher} do not know where
 * the key came from. The AWS SDK finds its credentials itself: in a pod that is the EKS Pod Identity role, on a
 * laptop whatever the developer signed in with.
 */
public class MasterKeyFromSecretsManager implements EnvironmentPostProcessor, Ordered {

	static final String SECRET_ID = "webhooks.secrets.master-key-secret-id";

	static final String MASTER_KEY = "webhooks.secrets.master-key";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		apply(environment, MasterKeyFromSecretsManager::fetch);
	}

	/** After the config files are loaded, so a secret id set in {@code application.yml} is seen. */
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	static void apply(ConfigurableEnvironment environment, Function<String, String> fetch) {
		String secretId = environment.getProperty(SECRET_ID);
		if (secretId == null || secretId.isBlank()) {
			return;
		}
		String configured = environment.getProperty(MASTER_KEY);
		if (configured != null && !configured.isBlank()) {
			throw new IllegalStateException(
					"Both %s and %s are set. Keep one: the key either lives in Secrets Manager or in the environment."
						.formatted(SECRET_ID, MASTER_KEY));
		}
		String key = fetch.apply(secretId.trim());
		if (key == null || key.isBlank()) {
			throw new IllegalStateException("The Secrets Manager secret " + secretId + " is empty");
		}
		environment.getPropertySources()
			.addFirst(new MapPropertySource("masterKeyFromSecretsManager", Map.of(MASTER_KEY, key.trim())));
	}

	private static String fetch(String secretId) {
		try (SecretsManagerClient client = SecretsManagerClient.create()) {
			return client.getSecretValue(request -> request.secretId(secretId)).secretString();
		}
		catch (RuntimeException ex) {
			// The message names the secret and the cause, never a value. Failing here stops the start: without the
			// key the application cannot decrypt any endpoint secret, and guessing one would sign with the wrong key.
			throw new IllegalStateException("Cannot read the master key from Secrets Manager secret " + secretId, ex);
		}
	}

}
