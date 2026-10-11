package io.github.emiliopg13.webhooks.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

class MasterKeyFromSecretsManagerTest {

	private final List<String> asked = new ArrayList<>();

	private StandardEnvironment environment(String... pairs) {
		MockPropertySource source = new MockPropertySource();
		for (int i = 0; i < pairs.length; i += 2) {
			source.setProperty(pairs[i], pairs[i + 1]);
		}
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source);
		return environment;
	}

	@Test
	void withoutASecretIdNothingIsFetchedAndTheKeyStaysAsConfigured() {
		StandardEnvironment env = environment(MasterKeyFromSecretsManager.MASTER_KEY, "from-env");

		MasterKeyFromSecretsManager.apply(env, id -> {
			asked.add(id);
			return "unused";
		});

		assertThat(asked).isEmpty();
		assertThat(env.getProperty(MasterKeyFromSecretsManager.MASTER_KEY)).isEqualTo("from-env");
	}

	@Test
	void aBlankSecretIdCountsAsNone() {
		StandardEnvironment env = environment(MasterKeyFromSecretsManager.SECRET_ID, "  ");

		MasterKeyFromSecretsManager.apply(env, id -> {
			asked.add(id);
			return "unused";
		});

		assertThat(asked).isEmpty();
		assertThat(env.getProperty(MasterKeyFromSecretsManager.MASTER_KEY)).isNull();
	}

	@Test
	void theSecretValueBecomesTheMasterKeyWithoutSurroundingWhitespace() {
		StandardEnvironment env = environment(MasterKeyFromSecretsManager.SECRET_ID, " webhooks/master-key ");

		MasterKeyFromSecretsManager.apply(env, id -> {
			asked.add(id);
			return "c2VjcmV0\n";
		});

		assertThat(asked).containsExactly("webhooks/master-key");
		assertThat(env.getProperty(MasterKeyFromSecretsManager.MASTER_KEY)).isEqualTo("c2VjcmV0");
	}

	@Test
	void aKeyInTheEnvironmentAndASecretIdAreRefusedTogether() {
		StandardEnvironment env = environment(MasterKeyFromSecretsManager.SECRET_ID, "webhooks/master-key",
				MasterKeyFromSecretsManager.MASTER_KEY, "from-env");

		assertThatThrownBy(() -> MasterKeyFromSecretsManager.apply(env, id -> "x"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Both");
	}

	@Test
	void anEmptySecretStopsTheStart() {
		StandardEnvironment env = environment(MasterKeyFromSecretsManager.SECRET_ID, "webhooks/master-key");

		assertThatThrownBy(() -> MasterKeyFromSecretsManager.apply(env, id -> " "))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("empty");
	}

}
