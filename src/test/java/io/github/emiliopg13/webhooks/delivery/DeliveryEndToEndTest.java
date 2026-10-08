package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.standardwebhooks.Webhook;

import io.github.emiliopg13.webhooks.WorkerIntegrationTest;
import tools.jackson.databind.JsonNode;

/** Publish an event and watch it travel: Postgres, queue, worker, HTTP, and back into Postgres. */
class DeliveryEndToEndTest extends WorkerIntegrationTest {

	@Test
	void anEventReachesTheReceiverSignedAndIsRecordedAsDelivered() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200).withBody("received")));
		TestTenant tenant = createTenant();
		JsonNode endpoint = createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		UUID eventId = publishEvent(tenant, "order.paid", "{\"total\":\"9.99\"}");
		UUID deliveryId = deliveriesOf(eventId).getFirst();

		awaitStatus(deliveryId, "delivered");

		LoggedRequest request = onlyRequestTo(path);
		String body = request.getBodyAsString();
		assertThat(request.getHeader("webhook-id")).isEqualTo(eventId.toString());
		assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
		// What the reference implementation would sign for this exact message, id, timestamp and body.
		Webhook reference = new Webhook(endpoint.get("secret").asString());
		assertThat(request.getHeader("webhook-signature"))
			.isEqualTo(reference.sign(eventId.toString(), Long.parseLong(request.getHeader("webhook-timestamp")), body));
		JsonNode envelope = json.readTree(body);
		assertThat(envelope.get("id").asString()).isEqualTo(eventId.toString());
		assertThat(envelope.get("type").asString()).isEqualTo("order.paid");
		assertThat(envelope.get("data").get("total").asString()).isEqualTo("9.99");
		assertThat(Instant.parse(envelope.get("created_at").asString())).isNotNull();

		assertThat(delivery(deliveryId)).containsEntry("attempt_count", 1).containsEntry("last_status_code", 200);
		assertThat(attempts(deliveryId)).singleElement()
			.satisfies(attempt -> assertThat(attempt).containsEntry("response_status", 200)
				.containsEntry("response_snippet", "received")
				.containsEntry("error_kind", null));
	}

	@Test
	void aReceiverThatFailsTwiceIsRetriedOnSchedule() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).inScenario(path)
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("second"));
		receiver.stubFor(post(path).inScenario(path)
			.whenScenarioStateIs("second")
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("third"));
		receiver.stubFor(post(path).inScenario(path).whenScenarioStateIs("third").willReturn(aResponse().withStatus(200)));
		TestTenant tenant = createTenant();
		UUID endpointId = UUID.fromString(createEndpoint(tenant, receiver.baseUrl() + path, "order.paid").get("id").asString());
		UUID eventId = publishEvent(tenant, "order.paid", "{}");
		UUID deliveryId = deliveriesOf(eventId).getFirst();

		// First failure: back to pending, due in 30 s give or take 20% jitter. Nothing happens until the clock moves.
		awaitAttempts(deliveryId, 1);
		awaitStatus(deliveryId, "pending");
		assertThat(Duration.between(clock.instant(), instant(delivery(deliveryId), "next_attempt_at")))
			.isBetween(Duration.ofSeconds(20), Duration.ofSeconds(40));
		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(path)))).hasSize(1);

		clock.advance(Duration.ofMinutes(1));
		awaitAttempts(deliveryId, 2);
		awaitStatus(deliveryId, "pending");
		// Second failure: two minutes, give or take 20%.
		assertThat(Duration.between(clock.instant(), instant(delivery(deliveryId), "next_attempt_at")))
			.isBetween(Duration.ofSeconds(90), Duration.ofSeconds(150));
		assertThat(endpoint(endpointId)).containsEntry("consecutive_failures", 2).doesNotContainEntry("failing_since", null);

		clock.advance(Duration.ofMinutes(5));
		awaitStatus(deliveryId, "delivered");

		assertThat(attempts(deliveryId)).extracting(attempt -> attempt.get("response_status")).containsExactly(500, 500, 200);
		assertThat(delivery(deliveryId)).containsEntry("attempt_count", 3);
		assertThat(endpoint(endpointId)).containsEntry("consecutive_failures", 0).containsEntry("failing_since", null);
		List<LoggedRequest> requests = receiver.findAll(postRequestedFor(urlEqualTo(path)));
		assertThat(requests).hasSize(3);
		// Every attempt carries the same message id, which is how a receiver recognises a retry.
		assertThat(requests).extracting(request -> request.getHeader("webhook-id")).containsOnly(eventId.toString());
	}

	@Test
	void anEventIsDeliveredToEveryEndpointSubscribedToItAndToNoOther() throws Exception {
		String subscribed = freshPath();
		String wildcard = freshPath();
		String other = freshPath();
		for (String path : List.of(subscribed, wildcard, other)) {
			receiver.stubFor(post(path).willReturn(aResponse().withStatus(204)));
		}
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + subscribed, "order.paid");
		createEndpoint(tenant, receiver.baseUrl() + wildcard, "*");
		createEndpoint(tenant, receiver.baseUrl() + other, "user.created");
		UUID eventId = publishEvent(tenant, "order.paid", "{}");

		List<UUID> deliveries = deliveriesOf(eventId);
		assertThat(deliveries).hasSize(2);
		deliveries.forEach(delivery -> awaitStatus(delivery, "delivered"));

		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(subscribed)))).hasSize(1);
		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(wildcard)))).hasSize(1);
		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(other)))).isEmpty();
	}

	/** Failure matrix row 3. */
	@Test
	void aRetryAfterLongerThanTheScheduleMovesTheNextAttemptThatFarAhead() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "3600")));
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		awaitAttempts(deliveryId, 1);
		awaitStatus(deliveryId, "pending");

		// The schedule alone would say 30 s; the receiver asked for an hour and the longer wait wins.
		assertThat(Duration.between(clock.instant(), instant(delivery(deliveryId), "next_attempt_at")))
			.isBetween(Duration.ofMinutes(55), Duration.ofMinutes(61));
	}

	/** Failure matrix row 4. */
	@Test
	void tooManyRequestsWithoutRetryAfterFollowsTheNormalSchedule() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(429)));
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		awaitAttempts(deliveryId, 1);
		awaitStatus(deliveryId, "pending");

		assertThat(Duration.between(clock.instant(), instant(delivery(deliveryId), "next_attempt_at")))
			.isBetween(Duration.ofSeconds(20), Duration.ofSeconds(40));
	}

	/** Failure matrix row 25: a secret that fails authentication must never produce a signature. */
	@Test
	void aTamperedSecretIsNeverUsedAndNothingIsSent() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200)));
		TestTenant tenant = createTenant();
		UUID endpointId = UUID.fromString(createEndpoint(tenant, receiver.baseUrl() + path, "order.paid").get("id").asString());
		jdbc.sql("""
				UPDATE endpoints SET secret_ciphertext = set_byte(secret_ciphertext, 20, get_byte(secret_ciphertext, 20) # 1)
				WHERE id = :id
				""").param("id", endpointId).update();
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		awaitAttempts(deliveryId, 1);

		assertThat(attempts(deliveryId)).singleElement()
			.satisfies(attempt -> assertThat(attempt).containsEntry("error_kind", "unexpected")
				.containsEntry("error_message", "The endpoint secret could not be decrypted")
				.containsEntry("response_status", null));
		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(path)))).isEmpty();
	}

	private LoggedRequest onlyRequestTo(String path) {
		List<LoggedRequest> requests = receiver.findAll(postRequestedFor(urlEqualTo(path)));
		assertThat(requests).hasSize(1);
		return requests.getFirst();
	}

}
