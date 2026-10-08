package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.emiliopg13.webhooks.WorkerIntegrationTest;

/**
 * The question this design exists to answer: <em>what if the worker dies right after delivering the webhook and
 * before writing that down?</em> The honest answer, proved here, is that the receiver gets it twice.
 */
class WorkerCrashTest extends WorkerIntegrationTest {

	@MockitoSpyBean
	private AttemptRecorder recorder;

	@Test
	void aWorkerThatDiesAfterDeliveringAndBeforeRecordingDeliversAgain() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200)));
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		// The first call to record() fails as a crash would, after the HTTP request went out; later calls work.
		doThrow(new IllegalStateException("simulated crash after the HTTP call")).doCallRealMethod()
			.when(recorder)
			.record(any(UUID.class), any(UUID.class), anyInt(), any(), anyLong(), any(), any(), any());
		UUID eventId = publishEvent(tenant, "order.paid", "{}");
		UUID deliveryId = deliveriesOf(eventId).getFirst();

		// The receiver got the webhook, but nothing was written down: the delivery still looks in flight.
		await().atMost(Duration.ofSeconds(30))
			.untilAsserted(() -> assertThat(receiver.findAll(postRequestedFor(urlEqualTo(path)))).hasSize(1));
		assertThat(delivery(deliveryId)).containsEntry("status", "enqueued").containsEntry("attempt_count", 0);
		assertThat(attempts(deliveryId)).isEmpty();

		// The dead worker's lease runs out. The queue's visibility timeout is what brings the message back;
		// if it comes back first and loses the claim, the reaper hands the delivery to the dispatcher instead.
		clock.advance(Duration.ofMinutes(5));
		awaitStatus(deliveryId, "delivered");

		List<LoggedRequest> requests = receiver.findAll(postRequestedFor(urlEqualTo(path)));
		assertThat(requests).hasSize(2);
		// Same message id both times: this is what lets the receiver recognise and drop the duplicate.
		assertThat(requests).extracting(request -> request.getHeader("webhook-id")).containsOnly(eventId.toString());
		// Only the attempt that was recorded exists. The crashed one left no trace except in the receiver's log.
		assertThat(attempts(deliveryId)).singleElement()
			.satisfies(attempt -> assertThat(attempt).containsEntry("attempt_number", 1).containsEntry("response_status", 200));
	}

}
