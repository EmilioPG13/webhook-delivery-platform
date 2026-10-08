package io.github.emiliopg13.webhooks.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.github.emiliopg13.webhooks.WorkerIntegrationTest;
import io.github.emiliopg13.webhooks.queue.DeliveryQueue;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * What the system does when one of the two stores it depends on stops answering. The outage is simulated by
 * making the call fail, not by stopping a container: the behaviour under test is how the code reacts to the
 * failure, and a stopped container would also make the test depend on how fast the driver notices.
 */
@TestPropertySource(properties = "test.database=worker-outage")
class InfrastructureOutageTest extends WorkerIntegrationTest {

	@MockitoSpyBean
	private DeliveryQueue queue;

	@MockitoSpyBean
	private DeliveryWorkRepository work;

	/** Failure matrix row 17. */
	@Test
	void deliveriesWaitInEnqueuedWhileTheQueueIsDownAndFlowOnceItIsBack() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200)));
		doThrow(SdkClientException.create("simulated SQS outage")).when(queue).send(anyList());
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		// The dispatcher marked the delivery and could not send it: it sits in 'enqueued' with no message.
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(queue, atLeastOnce()).send(anyList()));
		assertThat(delivery(deliveryId)).containsEntry("status", "enqueued");
		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(path)))).isEmpty();

		// The queue comes back. The reaper gives the stuck delivery back after two minutes, and it goes out.
		reset(queue);
		clock.advance(Duration.ofMinutes(3));
		awaitStatus(deliveryId, "delivered");

		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(path)))).hasSize(1);
		assertThat(attempts(deliveryId)).hasSize(1);
	}

	/** Failure matrix row 18, the half where the database fails as the message arrives. */
	@Test
	void aMessageIsKeptAndRedeliveredWhenTheDatabaseFailsAtTheClaim() throws Exception {
		String path = freshPath();
		receiver.stubFor(post(path).willReturn(aResponse().withStatus(200)));
		doThrow(new DataAccessResourceFailureException("simulated database outage")).doCallRealMethod()
			.when(work)
			.claim(any(UUID.class), any(), any());
		TestTenant tenant = createTenant();
		createEndpoint(tenant, receiver.baseUrl() + path, "order.paid");
		UUID deliveryId = deliveriesOf(publishEvent(tenant, "order.paid", "{}")).getFirst();

		// The first claim fails, so the consumer neither delivers nor deletes the message. It reappears when the
		// queue's visibility timeout (3 s in tests) runs out, and the second claim goes through.
		awaitStatus(deliveryId, "delivered");

		assertThat(receiver.findAll(postRequestedFor(urlEqualTo(path)))).hasSize(1);
		assertThat(attempts(deliveryId)).hasSize(1);
	}

}
