package io.github.emiliopg13.webhooks.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EndpointSecretCipherTest {

	private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

	private final EndpointSecretCipher cipher = new EndpointSecretCipher(
			new SecretsProperties(randomKey(32), 1, Map.of()));

	@Test
	void decryptReturnsWhatWasEncrypted() {
		UUID endpointId = UUID.randomUUID();

		byte[] stored = cipher.encrypt(SECRET, endpointId);

		assertThat(cipher.decrypt(stored, endpointId, 1)).isEqualTo(SECRET);
	}

	@Test
	void storedBytesDoNotContainThePlaintext() {
		byte[] stored = cipher.encrypt(SECRET, UUID.randomUUID());

		assertThat(new String(stored, StandardCharsets.ISO_8859_1))
			.doesNotContain(new String(SECRET, StandardCharsets.ISO_8859_1));
	}

	@Test
	void encryptingTwiceGivesDifferentCiphertextBecauseTheNonceIsRandom() {
		UUID endpointId = UUID.randomUUID();

		assertThat(cipher.encrypt(SECRET, endpointId)).isNotEqualTo(cipher.encrypt(SECRET, endpointId));
	}

	@Test
	void ciphertextCopiedToAnotherEndpointFailsAuthentication() {
		byte[] stored = cipher.encrypt(SECRET, UUID.randomUUID());

		assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID(), 1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void tamperedCiphertextFailsAuthentication() {
		UUID endpointId = UUID.randomUUID();
		byte[] stored = cipher.encrypt(SECRET, endpointId);
		stored[stored.length - 1] ^= 0x01;

		assertThatThrownBy(() -> cipher.decrypt(stored, endpointId, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anotherMasterKeyCannotDecrypt() {
		UUID endpointId = UUID.randomUUID();
		byte[] stored = cipher.encrypt(SECRET, endpointId);
		EndpointSecretCipher other = new EndpointSecretCipher(new SecretsProperties(randomKey(32), 1, Map.of()));

		assertThatThrownBy(() -> other.decrypt(stored, endpointId, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void afterRotationNewSecretsUseTheNewKeyAndOldOnesStillDecryptThroughTheRetiredOne() {
		String oldKey = randomKey(32);
		UUID oldEndpoint = UUID.randomUUID();
		UUID newEndpoint = UUID.randomUUID();
		EndpointSecretCipher before = new EndpointSecretCipher(new SecretsProperties(oldKey, 1, Map.of()));
		byte[] storedBefore = before.encrypt(SECRET, oldEndpoint);

		EndpointSecretCipher after = new EndpointSecretCipher(
				new SecretsProperties(randomKey(32), 2, Map.of(1, oldKey)));
		byte[] storedAfter = after.encrypt(SECRET, newEndpoint);

		assertThat(after.keyVersion()).isEqualTo(2);
		assertThat(after.decrypt(storedBefore, oldEndpoint, 1)).isEqualTo(SECRET);
		assertThat(after.decrypt(storedAfter, newEndpoint, 2)).isEqualTo(SECRET);
		// Using the wrong version for a row fails authentication instead of guessing.
		assertThatThrownBy(() -> after.decrypt(storedBefore, oldEndpoint, 2)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> after.decrypt(storedAfter, newEndpoint, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aRowWhoseKeyVersionIsNotConfiguredCannotBeDecrypted() {
		byte[] stored = cipher.encrypt(SECRET, UUID.randomUUID());

		assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID(), 7)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("version 7");
	}

	@Test
	void aRetiredKeyCannotShareTheCurrentVersion() {
		assertThatThrownBy(() -> new EndpointSecretCipher(
				new SecretsProperties(randomKey(32), 2, Map.of(2, randomKey(32)))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("current key's version");
	}

	@Test
	void aRetiredKeyOfTheWrongLengthIsRejectedAtStartup() {
		assertThatThrownBy(() -> new EndpointSecretCipher(
				new SecretsProperties(randomKey(32), 2, Map.of(1, randomKey(16)))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("retired-keys.1");
	}

	@Test
	void masterKeyOfTheWrongLengthIsRejectedAtStartup() {
		assertThatThrownBy(() -> new EndpointSecretCipher(new SecretsProperties(randomKey(16), 1, Map.of())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32 bytes");
	}

	@Test
	void masterKeyThatIsNotBase64IsRejectedAtStartup() {
		assertThatThrownBy(() -> new EndpointSecretCipher(new SecretsProperties("***not base64***", 1, Map.of())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("base64");
	}

	private static String randomKey(int bytes) {
		byte[] key = new byte[bytes];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
