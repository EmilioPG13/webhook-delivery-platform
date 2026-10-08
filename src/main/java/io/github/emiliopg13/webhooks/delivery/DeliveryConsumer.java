package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.queue.DeliveryQueue;
import io.github.emiliopg13.webhooks.queue.DeliveryQueue.Message;

/**
 * Reads the queue with a few long-polling loops and hands every message to {@link DeliveryProcessor} on its own
 * virtual thread. Waiting for a slow receiver is cheap on a virtual thread, so one worker can have many
 * deliveries in flight; the processor's concurrency limit keeps that number sane.
 *
 * <p>Stopping is graceful: the loops stop reading, and deliveries already started are given time to finish.
 */
@Component
@Profile("worker")
class DeliveryConsumer implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(DeliveryConsumer.class);

	/** SQS long polling limit. */
	private static final Duration POLL_WAIT = Duration.ofSeconds(20);

	private static final Duration ERROR_BACKOFF = Duration.ofSeconds(2);

	private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

	private final DeliveryQueue queue;

	private final DeliveryProcessor processor;

	private final WorkerProperties properties;

	private final List<Thread> loops = new ArrayList<>();

	private ExecutorService handlers;

	private volatile boolean running;

	DeliveryConsumer(DeliveryQueue queue, DeliveryProcessor processor, WorkerProperties properties) {
		this.queue = queue;
		this.processor = processor;
		this.properties = properties;
	}

	@Override
	public synchronized void start() {
		running = true;
		handlers = Executors.newVirtualThreadPerTaskExecutor();
		for (int i = 0; i < properties.consumers(); i++) {
			loops.add(Thread.ofVirtual().name("delivery-consumer-" + i).start(this::pollLoop));
		}
		log.info("Started {} delivery consumers", properties.consumers());
	}

	@Override
	public synchronized void stop() {
		running = false;
		// Wake the loops out of their long poll or their wait on a batch. Deliveries already running are in
		// separate threads that are not interrupted.
		loops.forEach(Thread::interrupt);
		loops.clear();
		handlers.shutdown();
		try {
			if (!handlers.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
				log.warn("Deliveries still running after {}; abandoning them to the lease", SHUTDOWN_GRACE);
				handlers.shutdownNow();
			}
		}
		catch (InterruptedException ex) {
			handlers.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void pollLoop() {
		while (running) {
			try {
				List<Message> messages = queue.receive(DeliveryQueue.MAX_BATCH, POLL_WAIT);
				List<Future<?>> inFlight = new ArrayList<>();
				for (Message message : messages) {
					inFlight.add(handlers.submit(() -> handle(message)));
				}
				for (Future<?> future : inFlight) {
					future.get();
				}
			}
			catch (InterruptedException ex) {
				return;
			}
			catch (ExecutionException ex) {
				// handle() catches everything; this is not expected.
				log.error("Delivery task failed", ex.getCause());
			}
			catch (RuntimeException ex) {
				if (!running) {
					return;
				}
				log.error("Cannot read the delivery queue; retrying in {}", ERROR_BACKOFF, ex);
				pause(ERROR_BACKOFF);
			}
		}
	}

	private void handle(Message message) {
		try {
			processor.process(message);
		}
		catch (RuntimeException ex) {
			// The message is left alone: once its visibility timeout runs out it comes back and the delivery is
			// tried again (or discarded if it has meanwhile finished).
			log.error("Delivery {} failed before it was recorded", message.deliveryId(), ex);
		}
	}

	private static void pause(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
