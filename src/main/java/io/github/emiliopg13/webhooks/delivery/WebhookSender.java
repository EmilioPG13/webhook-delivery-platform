package io.github.emiliopg13.webhooks.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.HttpFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.NetworkFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.Success;
import jakarta.annotation.PreDestroy;

/**
 * Makes the HTTP call of one attempt and turns whatever happens into a {@link DeliveryOutcome}; it never throws for
 * a bad receiver. It runs outside any database transaction.
 *
 * <p>Apache HttpClient 5 is used through its classic API rather than {@code RestClient}, because the delivery
 * contract needs two things the abstraction hides: a hard deadline for the whole exchange (the client's own
 * timeouts only bound each wait, so a receiver that drips bytes could hold a worker for ever), and a cap on how
 * much of the answer is read (closing a response normally drains it to the end).
 *
 * <p>Redirects are not followed and the client does not retry by itself: the platform owns the retry schedule, and
 * a silent second POST would be a duplicate nobody recorded.
 */
@Component
@Profile("worker")
class WebhookSender {

	/** Bytes of the receiver's answer kept for the attempt log. */
	static final int MAX_SNIPPET_BYTES = 1024;

	private static final int MAX_ERROR_MESSAGE_CHARS = 500;

	// Without a charset parameter: JSON is UTF-8 by definition (RFC 8259) and receivers should not have to parse one.
	private static final ContentType JSON = ContentType.create("application/json");

	private final CloseableHttpClient client;

	private final ScheduledThreadPoolExecutor deadlines;

	private final Timeout totalTimeout;

	private final Clock clock;

	WebhookSender(WorkerProperties properties, Clock clock) {
		this.clock = clock;
		this.totalTimeout = Timeout.ofMilliseconds(properties.deliveryTimeout().toMillis());
		Timeout connectTimeout = Timeout.ofMilliseconds(properties.connectTimeout().toMillis());
		this.client = HttpClients.custom()
			.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
				.setDefaultConnectionConfig(ConnectionConfig.custom()
					.setConnectTimeout(connectTimeout)
					.setSocketTimeout(totalTimeout)
					.build())
				.setMaxConnTotal(properties.maxConcurrentDeliveries())
				.setMaxConnPerRoute(properties.maxConcurrentDeliveries())
				.build())
			.setDefaultRequestConfig(RequestConfig.custom()
				.setRedirectsEnabled(false)
				.setConnectionRequestTimeout(connectTimeout)
				.setResponseTimeout(totalTimeout)
				.build())
			.disableAutomaticRetries()
			.disableCookieManagement()
			.setUserAgent("webhook-delivery-platform")
			.build();
		this.deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
			Thread thread = Executors.defaultThreadFactory().newThread(runnable);
			thread.setName("webhook-deadlines");
			thread.setDaemon(true);
			return thread;
		});
		// Every call schedules a deadline and cancels it a moment later; do not let the cancelled ones pile up.
		this.deadlines.setRemoveOnCancelPolicy(true);
	}

	DeliveryOutcome post(String url, Map<String, String> headers, byte[] body) {
		HttpPost request;
		try {
			request = new HttpPost(url);
		}
		catch (IllegalArgumentException ex) {
			// Registration validates URLs, but this class promises never to throw for a bad destination.
			return new NetworkFailure(ErrorKind.UNEXPECTED, "The endpoint URL is not valid");
		}
		headers.forEach(request::setHeader);
		request.setEntity(new ByteArrayEntity(body, JSON));
		AtomicBoolean deadlineHit = new AtomicBoolean();
		ScheduledFuture<?> deadline = deadlines.schedule(() -> {
			deadlineHit.set(true);
			request.cancel();
		}, totalTimeout.toMilliseconds(), TimeUnit.MILLISECONDS);
		ClassicHttpResponse response = null;
		try {
			response = client.executeOpen(null, request, null);
			return outcomeOf(response);
		}
		catch (IOException | RuntimeException ex) {
			return failureOf(ex, deadlineHit.get());
		}
		finally {
			deadline.cancel(false);
			closeQuietly(response);
		}
	}

	/**
	 * Not a try-with-resources on purpose: closing a connection that was already dropped throws, and an exception
	 * from {@code close()} would replace the outcome that was already decided.
	 */
	private static void closeQuietly(ClassicHttpResponse response) {
		if (response != null) {
			try {
				response.close();
			}
			catch (IOException | RuntimeException ignored) {
				// The outcome stands; there is nothing useful to do with a failed close.
			}
		}
	}

	private DeliveryOutcome outcomeOf(ClassicHttpResponse response) throws IOException {
		int status = response.getCode();
		String snippet = readSnippet(response);
		if (status >= 200 && status < 300) {
			return new Success(status, snippet);
		}
		Header retryAfter = response.getFirstHeader("Retry-After");
		return new HttpFailure(status, snippet,
				RetryAfter.parse(retryAfter == null ? null : retryAfter.getValue(), clock.instant()));
	}

	/** Reads at most {@link #MAX_SNIPPET_BYTES} of the body, and drops the connection if there was more. */
	private String readSnippet(ClassicHttpResponse response) throws IOException {
		HttpEntity entity = response.getEntity();
		if (entity == null) {
			return null;
		}
		InputStream in = entity.getContent();
		byte[] head = in.readNBytes(MAX_SNIPPET_BYTES);
		if (head.length == MAX_SNIPPET_BYTES && in.read() != -1 && response instanceof ModalCloseable closeable) {
			// Closing normally would read the rest of the body to reuse the connection; a big or slow answer
			// could hold the worker. Dropping the connection is cheaper.
			closeable.close(CloseMode.IMMEDIATE);
		}
		// PostgreSQL text cannot hold NUL, and a receiver is free to send it.
		String text = new String(head, StandardCharsets.UTF_8).replace('\0', '�');
		return text.isEmpty() ? null : text;
	}

	private static NetworkFailure failureOf(Exception ex, boolean deadlineHit) {
		if (deadlineHit) {
			return new NetworkFailure(ErrorKind.TIMEOUT, "No complete answer before the delivery deadline");
		}
		ErrorKind kind = switch (ex) {
			case ConnectTimeoutException _, SocketTimeoutException _ -> ErrorKind.TIMEOUT;
			case SSLException _ -> ErrorKind.TLS;
			case IOException _ -> ErrorKind.CONNECT;
			default -> ErrorKind.UNEXPECTED;
		};
		String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
		return new NetworkFailure(kind, message.length() > MAX_ERROR_MESSAGE_CHARS
				? message.substring(0, MAX_ERROR_MESSAGE_CHARS) : message);
	}

	@PreDestroy
	void close() {
		deadlines.shutdownNow();
		client.close(CloseMode.GRACEFUL);
	}

}
