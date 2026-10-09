package io.github.emiliopg13.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.emiliopg13.webhooks.queue.DeliveryQueue;
import io.github.emiliopg13.webhooks.queue.DeliveryQueue.Depth;
import io.github.emiliopg13.webhooks.queue.DeliveryQueue.Message;

/**
 * The consumer loop in isolation: a queue that hands out a fixed backlog and a processor that blocks until the test
 * lets it go. What matters is how many deliveries are in flight and when the loop reads again.
 */
class DeliveryConsumerTest {

	private static final Duration PATIENCE = Duration.ofSeconds(10);

	private DeliveryConsumer consumer;

	@AfterEach
	void stop() {
		if (consumer != null) {
			consumer.stop();
		}
	}

	@Test
	void aSlowDeliveryDoesNotHoldBackTheRestOfItsBatch() throws Exception {
		BacklogQueue queue = new BacklogQueue(3);
		DeliveryProcessor processor = mock(DeliveryProcessor.class);
		CountDownLatch slowMayFinish = new CountDownLatch(1);
		CountDownLatch othersDone = new CountDownLatch(2);
		List<Message> seen = new ArrayList<>();
		doAnswer(call -> {
			Message message = call.getArgument(0);
			boolean first;
			synchronized (seen) {
				seen.add(message);
				first = seen.size() == 1;
			}
			if (first) {
				slowMayFinish.await();
			}
			else {
				othersDone.countDown();
			}
			return null;
		}).when(processor).process(any());
		consumer = new DeliveryConsumer(queue, processor, properties(50, 1));
		consumer.start();

		// The two fast deliveries finish, and the loop has gone back to the queue, while the first is still running.
		assertThat(othersDone.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
		assertThat(slowMayFinish.getCount()).isEqualTo(1);
		assertThat(queue.polls()).isGreaterThanOrEqualTo(2);

		slowMayFinish.countDown();
	}

	@Test
	void neverHasMoreDeliveriesInFlightThanTheLimitAndReadsNoMoreThanItHasRoomFor() throws Exception {
		int limit = 4;
		BacklogQueue queue = new BacklogQueue(12);
		DeliveryProcessor processor = mock(DeliveryProcessor.class);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch allDone = new CountDownLatch(12);
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		doAnswer(call -> {
			peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			release.await();
			inFlight.decrementAndGet();
			allDone.countDown();
			return null;
		}).when(processor).process(any());
		consumer = new DeliveryConsumer(queue, processor, properties(limit, 2));
		consumer.start();

		// Full: the limit is reached and the rest of the backlog stays in the queue, not in this worker's hands.
		awaitTrue(() -> inFlight.get() == limit);
		assertThat(queue.handedOut()).isEqualTo(limit);

		release.countDown();
		assertThat(allDone.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
		assertThat(peak.get()).isEqualTo(limit);
		assertThat(queue.handedOut()).isEqualTo(12);
	}

	private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + PATIENCE.toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("condition not reached in time").isLessThan(deadline);
			Thread.onSpinWait();
		}
	}

	private static WorkerProperties properties(int maxConcurrentDeliveries, int consumers) {
		return new WorkerProperties(Duration.ofSeconds(1), 100, Duration.ofSeconds(30), Duration.ofMinutes(2),
				Duration.ofSeconds(45), consumers, maxConcurrentDeliveries, 5, Duration.ofSeconds(2),
				Duration.ofSeconds(1), Duration.ofSeconds(1), true);
	}

	/** Hands out {@code total} messages, at most as many per read as asked for, then reads come back empty. */
	private static final class BacklogQueue implements DeliveryQueue {

		private final AtomicInteger remaining;

		private final AtomicInteger handedOut = new AtomicInteger();

		private final AtomicInteger polls = new AtomicInteger();

		BacklogQueue(int total) {
			this.remaining = new AtomicInteger(total);
		}

		int handedOut() {
			return handedOut.get();
		}

		int polls() {
			return polls.get();
		}

		@Override
		public List<Message> receive(int max, Duration maxWait) {
			polls.incrementAndGet();
			List<Message> batch = new ArrayList<>();
			while (batch.size() < max && remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
				batch.add(new Message(UUID.randomUUID(), "receipt"));
				handedOut.incrementAndGet();
			}
			if (batch.isEmpty()) {
				// An empty long poll takes time; do not spin the loop.
				try {
					new CountDownLatch(1).await(20, TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			return batch;
		}

		@Override
		public void send(List<UUID> deliveryIds) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Depth depth() {
			return new Depth(remaining.get(), 0);
		}

		@Override
		public void delete(Message message) {
			throw new UnsupportedOperationException();
		}

	}

}
