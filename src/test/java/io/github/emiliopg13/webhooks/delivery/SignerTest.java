package io.github.emiliopg13.webhooks.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookVerificationException;

/** The reference implementation of the specification is the judge: what it signs, we sign; what we sign, it verifies. */
class SignerTest {

	private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"order.paid\",\"data\":{\"total\":\"9.99\"}}";

	private final byte[] key = randomKey();

	private final Webhook reference;

	SignerTest() throws Exception {
		reference = new Webhook("whsec_" + Base64.getEncoder().encodeToString(key));
	}

	@Test
	void producesTheSameSignatureAsTheReferenceLibrary() throws Exception {
		long timestamp = 1_760_000_000L;

		String ours = Signer.sign("msg_1", timestamp, BODY.getBytes(UTF_8), key);

		assertThat(ours).isEqualTo(reference.sign("msg_1", timestamp, BODY));
	}

	@Test
	void theReferenceLibraryVerifiesOurSignature() throws Exception {
		long timestamp = Instant.now().getEpochSecond();
		String signature = Signer.sign("msg_1", timestamp, BODY.getBytes(UTF_8), key);

		reference.verify(BODY, headers("msg_1", timestamp, signature));
	}

	@Test
	void aTamperedBodyFailsVerification() {
		long timestamp = Instant.now().getEpochSecond();
		String signature = Signer.sign("msg_1", timestamp, BODY.getBytes(UTF_8), key);

		assertThatThrownBy(() -> reference.verify(BODY.replace("9.99", "0.01"), headers("msg_1", timestamp, signature)))
			.isInstanceOf(WebhookVerificationException.class);
	}

	@Test
	void theMessageIdIsPartOfWhatIsSigned() {
		long timestamp = Instant.now().getEpochSecond();
		String signature = Signer.sign("msg_1", timestamp, BODY.getBytes(UTF_8), key);

		assertThatThrownBy(() -> reference.verify(BODY, headers("msg_2", timestamp, signature)))
			.isInstanceOf(WebhookVerificationException.class);
	}

	@Test
	void theTimestampIsPartOfWhatIsSigned() {
		long timestamp = Instant.now().getEpochSecond();
		String signature = Signer.sign("msg_1", timestamp, BODY.getBytes(UTF_8), key);

		assertThatThrownBy(() -> reference.verify(BODY, headers("msg_1", timestamp + 1, signature)))
			.isInstanceOf(WebhookVerificationException.class);
	}

	@Test
	void anotherKeyProducesAnotherSignature() {
		String first = Signer.sign("msg_1", 1L, BODY.getBytes(UTF_8), key);

		assertThat(Signer.sign("msg_1", 1L, BODY.getBytes(UTF_8), randomKey())).isNotEqualTo(first);
	}

	@Test
	void signsBytesNotCharacters() {
		byte[] body = { (byte) 0xC3, (byte) 0xA9 };

		assertThat(Signer.sign("msg_1", 1L, body, key)).isEqualTo(Signer.sign("msg_1", 1L, "é".getBytes(UTF_8), key));
	}

	private static Map<String, List<String>> headers(String id, long timestamp, String signature) {
		return Map.of(Signer.ID_HEADER, List.of(id), Signer.TIMESTAMP_HEADER, List.of(Long.toString(timestamp)),
				Signer.SIGNATURE_HEADER, List.of(signature));
	}

	private static byte[] randomKey() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}

}
