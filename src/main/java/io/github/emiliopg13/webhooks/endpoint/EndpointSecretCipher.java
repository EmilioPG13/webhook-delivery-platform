package io.github.emiliopg13.webhooks.endpoint;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Encrypts endpoint signing secrets with AES-256-GCM. Secrets must be readable (they are the HMAC key used on
 * every delivery), so hashing is not an option. The endpoint id is bound in as associated data: a ciphertext
 * copied onto another row fails authentication instead of decrypting.
 *
 * <p>Stored layout: 12-byte random nonce, then ciphertext and 16-byte tag.
 */
@Component
public class EndpointSecretCipher {

	private static final int KEY_BYTES = 32;

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;

	private final int keyVersion;

	private final SecureRandom random = new SecureRandom();

	EndpointSecretCipher(SecretsProperties properties) {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(properties.masterKey().trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("webhooks.secrets.master-key is not valid base64", ex);
		}
		if (raw.length != KEY_BYTES) {
			throw new IllegalStateException("webhooks.secrets.master-key must decode to exactly %d bytes, got %d"
				.formatted(KEY_BYTES, raw.length));
		}
		this.key = new SecretKeySpec(raw, "AES");
		this.keyVersion = properties.keyVersion();
	}

	public int keyVersion() {
		return keyVersion;
	}

	public byte[] encrypt(byte[] plaintext, UUID endpointId) {
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			random.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(associatedData(endpointId));
			byte[] encrypted = cipher.doFinal(plaintext);
			return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not encrypt the endpoint secret", ex);
		}
	}

	public byte[] decrypt(byte[] stored, UUID endpointId) {
		if (stored.length <= NONCE_BYTES) {
			throw new IllegalArgumentException("Stored secret is too short to be valid");
		}
		try {
			byte[] nonce = Arrays.copyOfRange(stored, 0, NONCE_BYTES);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(associatedData(endpointId));
			return cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalArgumentException("Stored secret failed authentication", ex);
		}
	}

	private static byte[] associatedData(UUID endpointId) {
		return ByteBuffer.allocate(16)
			.putLong(endpointId.getMostSignificantBits())
			.putLong(endpointId.getLeastSignificantBits())
			.array();
	}

}
