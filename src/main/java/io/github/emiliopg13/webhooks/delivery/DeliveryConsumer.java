package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
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
 * deliveries in flight.
 *
 * <p>A loop reads only as many messages as there are free delivery slots, and never waits for the others of its
 * batch: one slow receiver must not hold back nine fast ones. Reading only into a free slot also means a message
 * never sits received, with its visibility timeout running, waiting for its turn.
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

	/** One permit per delivery this worker may have in flight. */
	private final Semaphore slots;

	DeliveryConsumer(DeliveryQueue queue, DeliveryProcessor processor, WorkerProperties properties) {
		this.queue = queue;
		this.processor = processor;
		this.properties = properties;
		this.slots = new Semaphore(properties.maxConcurrentDeliveries());
	}

	@Override
	public synchronized void start() {
		running = true;
		boolean virtual = properties.virtualThreads();
		handlers = virtual ? Executors.newVirtualThreadPerTaskExecutor()
				: Executors.newFixedThreadPool(properties.maxConcurrentDeliveries(),
						Thread.ofPlatform().name("delivery-", 0).factory());
		for (int i = 0; i < properties.consumers(); i++) {
			Thread.Builder builder = virtual ? Thread.ofVirtual() : Thread.ofPlatform();
			loops.add(builder.name("delivery-consumer-" + i).start(this::pollLoop));
		}
		log.info("Started {} delivery consumers on {} threads", properties.consumers(),
				virtual ? "virtual" : "platform");
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
			int granted = 0;
			try {
				granted = acquireSlots();
				List<Message> messages = queue.receive(granted, POLL_WAIT);
				// Slots the poll did not fill go back at once; the others come back as their deliveries finish.
				slots.release(granted - messages.size());
				granted = 0;
				for (Message message : messages) {
					submit(message);
				}
			}
			catch (InterruptedException ex) {
				slots.release(granted);
				return;
			}
			catch (RuntimeException ex) {
				slots.release(granted);
				if (!running) {
					return;
				}
				log.error("Cannot read the delivery queue; retrying in {}", ERROR_BACKOFF, ex);
				pause(ERROR_BACKOFF);
			}
		}
	}

	/** Waits for one free slot, then takes as many more as are free right now, up to one queue batch. */
	private int acquireSlots() throws InterruptedException {
		slots.acquire();
		int granted = 1;
		while (granted < DeliveryQueue.MAX_BATCH && slots.tryAcquire()) {
			granted++;
		}
		return granted;
	}

	private void submit(Message message) {
		try {
			handlers.execute(() -> {
				try {
					handle(message);
				}
				finally {
					slots.release();
				}
			});
		}
		catch (RejectedExecutionException ex) {
			// Stopping. The message is not deleted, so it reappears and another worker takes it.
			slots.release();
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
