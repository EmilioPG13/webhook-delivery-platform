package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.HttpFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.NetworkFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.Success;

/** The HTTP step on its own, against a receiver that misbehaves on purpose. No database, no Spring. */
class WebhookSenderTest {

	private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(UTF_8);

	private static final Map<String, String> HEADERS = Map.of("webhook-id", "evt_1", "webhook-timestamp", "1760000000",
			"webhook-signature", "v1,abc");

	@RegisterExtension
	static WireMockExtension receiver = WireMockExtension.newInstance()
		.options(wireMockConfig().dynamicPort().dynamicHttpsPort())
		.build();

	// Short deadlines keep the timeout tests quick; the production values are 5 s and 15 s.
	private final WebhookSender sender = new WebhookSender(new WorkerProperties(Duration.ofSeconds(1), 100,
			Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofSeconds(45), 2, 10, Duration.ofSeconds(1),
			Duration.ofSeconds(1)), Clock.systemUTC());

	/** The extension's own {@code url()} answers with https once an https port is enabled. */
	private static String httpUrl(String path) {
		return "http://localhost:" + receiver.getPort() + path;
	}

	@AfterEach
	void closeSender() {
		sender.close();
	}

	@Test
	void aTwoHundredIsASuccessAndTheReceiverSeesTheSignedRequest() {
		receiver.stubFor(post("/ok").willReturn(aResponse().withStatus(200).withBody("received")));

		DeliveryOutcome outcome = sender.post(httpUrl("/ok"), HEADERS, BODY);

		assertThat(outcome).isEqualTo(new Success(200, "received"));
		receiver.verify(postRequestedFor(urlEqualTo("/ok")).withHeader("webhook-id", equalTo("evt_1"))
			.withHeader("webhook-timestamp", equalTo("1760000000"))
			.withHeader("webhook-signature", equalTo("v1,abc"))
			.withHeader("Content-Type", equalTo("application/json"))
			.withRequestBody(equalTo("{\"id\":\"evt_1\"}")));
	}

	@Test
	void anyOtherStatusIsAFailureWithTheStatus() {
		receiver.stubFor(post("/boom").willReturn(aResponse().withStatus(500).withBody("oops")));

		assertThat(sender.post(httpUrl("/boom"), HEADERS, BODY)).isEqualTo(new HttpFailure(500, "oops", null));
	}

	@Test
	void retryAfterInSecondsIsParsed() {
		receiver.stubFor(post("/busy").willReturn(aResponse().withStatus(503).withHeader("Retry-After", "3600")));

		assertThat(sender.post(httpUrl("/busy"), HEADERS, BODY))
			.isEqualTo(new HttpFailure(503, null, Duration.ofHours(1)));
	}

	@Test
	void aRedirectIsNotFollowed() {
		receiver.stubFor(post("/moved")
			.willReturn(aResponse().withStatus(301).withHeader("Location", "http://169.254.169.254/latest/meta-data/")));

		DeliveryOutcome outcome = sender.post(httpUrl("/moved"), HEADERS, BODY);

		assertThat(outcome).isInstanceOf(HttpFailure.class);
		assertThat(outcome.statusCode()).isEqualTo(301);
		assertThat(receiver.getAllServeEvents()).hasSize(1);
	}

	@Test
	void onlyTheFirstKilobyteOfTheAnswerIsKept() {
		receiver.stubFor(post("/big").willReturn(aResponse().withStatus(500).withBody("a".repeat(100_000))));

		DeliveryOutcome outcome = sender.post(httpUrl("/big"), HEADERS, BODY);

		assertThat(outcome.responseSnippet()).as(outcome.toString()).hasSize(WebhookSender.MAX_SNIPPET_BYTES);
	}

	@Test
	void aNulByteInTheAnswerIsReplacedBecausePostgresTextCannotStoreIt() {
		receiver.stubFor(post("/nul").willReturn(aResponse().withStatus(500).withBody("a\u0000b")));

		assertThat(sender.post(httpUrl("/nul"), HEADERS, BODY).responseSnippet()).isEqualTo("a�b");
	}

	@Test
	void anAnswerSlowerThanTheDeadlineIsATimeout() {
		receiver.stubFor(post("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
		long started = System.nanoTime();

		DeliveryOutcome outcome = sender.post(httpUrl("/slow"), HEADERS, BODY);

		assertThat(outcome).isInstanceOfSatisfying(NetworkFailure.class,
				failure -> assertThat(failure.kind()).isEqualTo(ErrorKind.TIMEOUT));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void aReceiverThatDripsBytesCannotHoldTheWorkerPastTheDeadline() {
		// Each pause is shorter than the read timeout, so only the deadline for the whole exchange can stop this.
		receiver.stubFor(post("/drip")
			.willReturn(aResponse().withStatus(200).withBody("0123456789").withChunkedDribbleDelay(5, 4_000)));
		long started = System.nanoTime();

		DeliveryOutcome outcome = sender.post(httpUrl("/drip"), HEADERS, BODY);

		assertThat(outcome).isInstanceOfSatisfying(NetworkFailure.class,
				failure -> assertThat(failure.kind()).isEqualTo(ErrorKind.TIMEOUT));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
	}

	@ParameterizedTest
	@EnumSource(value = Fault.class, names = { "CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE", "MALFORMED_RESPONSE_CHUNK",
			"RANDOM_DATA_THEN_CLOSE" })
	void aBrokenConnectionIsAFailureAndNeverAnException(Fault fault) {
		receiver.stubFor(post("/fault").willReturn(aResponse().withFault(fault)));

		assertThat(sender.post(httpUrl("/fault"), HEADERS, BODY)).isInstanceOf(NetworkFailure.class);
	}

	@Test
	void aRefusedConnectionIsAConnectFailure() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		DeliveryOutcome outcome = sender.post("http://localhost:" + closedPort + "/", HEADERS, BODY);

		assertThat(outcome).isInstanceOfSatisfying(NetworkFailure.class,
				failure -> assertThat(failure.kind()).isEqualTo(ErrorKind.CONNECT));
	}

	@Test
	void aCertificateTheClientDoesNotTrustIsATlsFailureAndNothingIsDelivered() {
		receiver.stubFor(post("/tls").willReturn(aResponse().withStatus(200)));

		DeliveryOutcome outcome = sender.post("https://localhost:" + receiver.getHttpsPort() + "/tls", HEADERS, BODY);

		assertThat(outcome).isInstanceOfSatisfying(NetworkFailure.class,
				failure -> assertThat(failure.kind()).isEqualTo(ErrorKind.TLS));
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void anUnparseableUrlIsAFailureAndNeverAnException() {
		assertThat(sender.post("not a url", HEADERS, BODY)).isInstanceOf(NetworkFailure.class);
	}

}
