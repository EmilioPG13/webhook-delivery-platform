package io.github.emiliopg13.webhooks.endpoint;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
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
 *
 * <p>Keys are versioned so the master key can be rotated. New secrets are encrypted with the current key, and
 * every row records the version it was encrypted under ({@code secret_key_version}); decrypting picks the key by
 * that number. A retired key therefore stays configured for as long as any row still uses its version.
 */
@Component
public class EndpointSecretCipher {

	private static final int KEY_BYTES = 32;

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final Map<Integer, SecretKeySpec> keys = new HashMap<>();

	private final int currentVersion;

	private final SecureRandom random = new SecureRandom();

	EndpointSecretCipher(SecretsProperties properties) {
		this.currentVersion = properties.keyVersion();
		keys.put(currentVersion, parse("webhooks.secrets.master-key", properties.masterKey()));
		properties.retiredKeys().forEach((version, key) -> {
			if (version == currentVersion) {
				throw new IllegalStateException(
						"webhooks.secrets.retired-keys has version %d, which is the current key's version".formatted(version));
			}
			keys.put(version, parse("webhooks.secrets.retired-keys." + version, key));
		});
	}

	private static SecretKeySpec parse(String property, String base64) {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(base64.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(property + " is not valid base64", ex);
		}
		if (raw.length != KEY_BYTES) {
			throw new IllegalStateException(
					"%s must decode to exactly %d bytes, got %d".formatted(property, KEY_BYTES, raw.length));
		}
		return new SecretKeySpec(raw, "AES");
	}

	/** The version new secrets are encrypted under, to be stored next to them. */
	public int keyVersion() {
		return currentVersion;
	}

	public byte[] encrypt(byte[] plaintext, UUID endpointId) {
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			random.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentVersion), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(associatedData(endpointId));
			byte[] encrypted = cipher.doFinal(plaintext);
			return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not encrypt the endpoint secret", ex);
		}
	}

	/**
	 * @param keyVersion the version stored with the secret
	 * @throws IllegalArgumentException if no key of that version is configured, or the bytes fail authentication
	 */
	public byte[] decrypt(byte[] stored, UUID endpointId, int keyVersion) {
		SecretKeySpec key = keys.get(keyVersion);
		if (key == null) {
			throw new IllegalArgumentException("No master key is configured for version " + keyVersion);
		}
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
