package io.github.emiliopg13.webhooks.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import io.github.emiliopg13.webhooks.IntegrationTest;
import tools.jackson.databind.JsonNode;

/**
 * The test the whole design leans on: many callers racing to publish the same event must produce exactly one
 * event and one set of deliveries. Uses real HTTP and real threads so the requests genuinely overlap, which
 * MockMvc on one thread would not give.
 */
class ConcurrentPublishTest extends IntegrationTest {

	private static final int CALLERS = 20;

	private final HttpClient client = HttpClient.newHttpClient();

	@RepeatedTest(50)
	void twentyConcurrentPublishesWithTheSameKeyCreateOneEventAndOneSetOfDeliveries() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/a", "order.paid");
		createEndpoint(tenant, "https://example.com/b", "*");
		String key = "order-" + UUID.randomUUID();
		String body = publishBody("order.paid", "{\"order_id\":1}");

		List<HttpResponse<String>> responses = raceToPublish(tenant, key, CALLERS, i -> body);

		assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(202));
		List<String> ids = responses.stream().map(response -> idOf(response)).distinct().toList();
		assertThat(ids).hasSize(1);
		long replays = responses.stream()
			.filter(response -> response.headers().firstValue("Idempotent-Replayed").isPresent())
			.count();
		assertThat(replays).isEqualTo(CALLERS - 1);
		assertThat(responses).allSatisfy(response -> assertThat(deliveriesOf(response)).isEqualTo(2));

		assertThat(count("events", tenant)).isEqualTo(1);
		assertThat(count("deliveries", tenant)).isEqualTo(2);
	}

	@Test
	void concurrentPublishesWithTheSameKeyAndDifferentBodiesLetExactlyOneWin() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/a", "*");
		String key = "order-" + UUID.randomUUID();

		List<HttpResponse<String>> responses = raceToPublish(tenant, key, CALLERS,
				i -> publishBody("order.paid", "{\"attempt\":" + i + "}"));

		long accepted = responses.stream().filter(response -> response.statusCode() == 202).count();
		long rejected = responses.stream().filter(response -> response.statusCode() == 422).count();
		assertThat(accepted).isEqualTo(1);
		assertThat(rejected).isEqualTo(CALLERS - 1);
		assertThat(count("events", tenant)).isEqualTo(1);
		assertThat(count("deliveries", tenant)).isEqualTo(1);
	}

	@Test
	void concurrentPublishesWithoutAKeyAllCreateTheirOwnEvent() throws Exception {
		TestTenant tenant = createTenant();
		createEndpoint(tenant, "https://example.com/a", "*");

		List<HttpResponse<String>> responses = raceToPublish(tenant, null, CALLERS,
				i -> publishBody("order.paid", "{\"same\":true}"));

		assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(202));
		assertThat(count("events", tenant)).isEqualTo(CALLERS);
		assertThat(count("deliveries", tenant)).isEqualTo(CALLERS);
	}

	/** Releases all callers at once from a start gate, so their requests overlap as much as the machine allows. */
	private List<HttpResponse<String>> raceToPublish(TestTenant tenant, String idempotencyKey, int callers,
			java.util.function.IntFunction<String> bodyFor) throws Exception {
		CountDownLatch ready = new CountDownLatch(callers);
		CountDownLatch go = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<HttpResponse<String>>> futures = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				int caller = i;
				futures.add(executor.submit(() -> {
					HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/events"))
						.header("Authorization", "Bearer " + tenant.apiKey())
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(bodyFor.apply(caller)));
					if (idempotencyKey != null) {
						request.header("Idempotency-Key", idempotencyKey);
					}
					ready.countDown();
					go.await();
					return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
				}));
			}
			ready.await();
			go.countDown();
			List<HttpResponse<String>> responses = new ArrayList<>();
			for (Future<HttpResponse<String>> future : futures) {
				responses.add(future.get());
			}
			return responses;
		}
	}

	private String idOf(HttpResponse<String> response) {
		return parse(response).get("id").asString();
	}

	private int deliveriesOf(HttpResponse<String> response) {
		return parse(response).get("deliveries").asInt();
	}

	private JsonNode parse(HttpResponse<String> response) {
		return json.readTree(response.body());
	}

	private int count(String table, TestTenant tenant) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
			.param("t", tenant.id())
			.query(Integer.class)
			.single();
	}

}
