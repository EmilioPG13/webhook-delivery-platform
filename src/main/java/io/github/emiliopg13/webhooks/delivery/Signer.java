package io.github.emiliopg13.webhooks.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs deliveries as the <a href="https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md">
 * Standard Webhooks</a> specification asks: HMAC-SHA256 over {@code id "." timestamp "." body}, base64 encoded
 * and prefixed with the scheme version. The message id is part of the signed content, so a captured body and
 * signature cannot be reused under another id.
 */
final class Signer {

	static final String ID_HEADER = "webhook-id";

	static final String TIMESTAMP_HEADER = "webhook-timestamp";

	static final String SIGNATURE_HEADER = "webhook-signature";

	private static final String VERSION = "v1";

	private static final String ALGORITHM = "HmacSHA256";

	private Signer() {
	}

	/**
	 * @param key the raw secret bytes, not the {@code whsec_} text shown to the customer
	 * @param body the exact bytes that go on the wire: the receiver verifies them as received, not re-parsed
	 * @return the header value, for example {@code v1,K5oZfzN95Z9UVu1EsfQmfVNQhnkZ2pj9o9NDN/H/pI4=}
	 */
	static String sign(String messageId, long timestampSeconds, byte[] body, byte[] key) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(key, ALGORITHM));
			mac.update(messageId.getBytes(UTF_8));
			mac.update((byte) '.');
			mac.update(Long.toString(timestampSeconds).getBytes(UTF_8));
			mac.update((byte) '.');
			mac.update(body);
			return VERSION + "," + Base64.getEncoder().encodeToString(mac.doFinal());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 is not available", ex);
		}
	}

}
