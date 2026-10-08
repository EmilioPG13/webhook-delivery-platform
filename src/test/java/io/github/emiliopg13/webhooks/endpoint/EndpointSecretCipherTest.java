package io.github.emiliopg13.webhooks.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EndpointSecretCipherTest {

	private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

	private final EndpointSecretCipher cipher = new EndpointSecretCipher(
			new SecretsProperties(randomKey(32), 1));

	@Test
	void decryptReturnsWhatWasEncrypted() {
		UUID endpointId = UUID.randomUUID();

		byte[] stored = cipher.encrypt(SECRET, endpointId);

		assertThat(cipher.decrypt(stored, endpointId)).isEqualTo(SECRET);
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

		assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void tamperedCiphertextFailsAuthentication() {
		UUID endpointId = UUID.randomUUID();
		byte[] stored = cipher.encrypt(SECRET, endpointId);
		stored[stored.length - 1] ^= 0x01;

		assertThatThrownBy(() -> cipher.decrypt(stored, endpointId)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anotherMasterKeyCannotDecrypt() {
		UUID endpointId = UUID.randomUUID();
		byte[] stored = cipher.encrypt(SECRET, endpointId);
		EndpointSecretCipher other = new EndpointSecretCipher(new SecretsProperties(randomKey(32), 1));

		assertThatThrownBy(() -> other.decrypt(stored, endpointId)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void masterKeyOfTheWrongLengthIsRejectedAtStartup() {
		assertThatThrownBy(() -> new EndpointSecretCipher(new SecretsProperties(randomKey(16), 1)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32 bytes");
	}

	@Test
	void masterKeyThatIsNotBase64IsRejectedAtStartup() {
		assertThatThrownBy(() -> new EndpointSecretCipher(new SecretsProperties("***not base64***", 1)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("base64");
	}

	private static String randomKey(int bytes) {
		byte[] key = new byte[bytes];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
