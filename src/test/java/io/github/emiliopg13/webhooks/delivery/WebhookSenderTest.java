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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.HttpFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.NetworkFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.Success;
import io.github.emiliopg13.webhooks.security.FakeDns;
import io.github.emiliopg13.webhooks.security.GuardedDnsResolver;

/** The HTTP step on its own, against a receiver that misbehaves on purpose. No database, no Spring. */
class WebhookSenderTest {

	private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(UTF_8);

	private static final Map<String, String> HEADERS = Map.of("webhook-id", "evt_1", "webhook-timestamp", "1760000000",
			"webhook-signature", "v1,abc");

	@RegisterExtension
	static WireMockExtension receiver = WireMockExtension.newInstance()
		.options(wireMockConfig().dynamicPort().dynamicHttpsPort())
		.build();

	private final WebhookSender sender = senderBehind(SystemDefaultDnsResolver.INSTANCE);

	/** Short deadlines keep the timeout tests quick; the production values are 5 s and 15 s. */
	private static WebhookSender senderBehind(DnsResolver dnsResolver) {
		return new WebhookSender(new WorkerProperties(Duration.ofSeconds(1), 100, Duration.ofSeconds(30),
				Duration.ofMinutes(2), Duration.ofSeconds(45), 2, 10, 5, Duration.ofSeconds(2), Duration.ofSeconds(1),
				Duration.ofSeconds(1), true),
				Clock.systemUTC(), dnsResolver);
	}

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
	void theGuardRefusesALoopbackReceiverAndNothingIsSent() {
		receiver.stubFor(post("/guarded").willReturn(aResponse().withStatus(200)));
		WebhookSender guarded = senderBehind(new GuardedDnsResolver(SystemDefaultDnsResolver.INSTANCE));
		try {
			// The receiver listens on loopback, named by host name and then written as an IP address.
			for (String host : new String[] { "localhost", "127.0.0.1" }) {
				DeliveryOutcome outcome = guarded.post("http://" + host + ":" + receiver.getPort() + "/guarded", HEADERS,
						BODY);

				assertThat(outcome).as(host).isInstanceOf(NetworkFailure.class);
				assertThat(outcome.errorKind()).as(host).isEqualTo(ErrorKind.BLOCKED_DESTINATION);
				assertThat(outcome.fatalReason()).as(host).isEqualTo("blocked_destination");
			}
			assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/guarded")))).isEmpty();
		}
		finally {
			guarded.close();
		}
	}

	/** The failure matrix's DNS rebinding row: a public address when the endpoint was registered, loopback now. */
	@Test
	void dnsRebindingIsStoppedAtDeliveryTime() throws Exception {
		receiver.stubFor(post("/rebind").willReturn(aResponse().withStatus(200)));
		AtomicInteger lookups = new AtomicInteger();
		GuardedDnsResolver guard = new GuardedDnsResolver(FakeDns
			.of(host -> new InetAddress[] { lookups.getAndIncrement() == 0 ? FakeDns.ip(93, 184, 216, 34) : FakeDns.ip(127, 0, 0, 1) }));
		assertThat(guard.resolve("rebind.test")).hasSize(1);
		WebhookSender guarded = senderBehind(guard);
		try {
			DeliveryOutcome outcome = guarded.post("http://rebind.test:" + receiver.getPort() + "/rebind", HEADERS, BODY);

			assertThat(outcome.errorKind()).isEqualTo(ErrorKind.BLOCKED_DESTINATION);
			assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/rebind")))).isEmpty();
		}
		finally {
			guarded.close();
		}
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
