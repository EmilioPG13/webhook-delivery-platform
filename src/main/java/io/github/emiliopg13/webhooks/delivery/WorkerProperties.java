package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Tuning of the {@code worker} role.
 *
 * @param dispatchInterval pause between two runs of the dispatcher
 * @param dispatchBatchSize deliveries the dispatcher takes from Postgres per statement
 * @param reaperInterval pause between two runs of the reaper
 * @param stuckAfter how long a delivery may sit in {@code enqueued} before the reaper gives it back
 * @param lease how long a claim protects a delivery; longer than the HTTP timeout, shorter than the queue's
 * visibility timeout, so a lost message reappears after the lease expired
 * @param consumers long-polling loops reading the queue
 * @param maxConcurrentDeliveries deliveries in flight at once in this worker
 * @param maxConcurrentPerEndpoint deliveries in flight at once to one endpoint, so a slow one cannot take every slot
 * @param deferDelay how long a delivery waits in {@code pending} when its endpoint had no free place
 * @param connectTimeout how long to wait for a TCP and TLS connection
 * @param deliveryTimeout hard deadline for the whole exchange, from connecting to the last byte read
 * @param virtualThreads true runs every delivery on its own virtual thread; false runs them on a fixed pool of
 * {@code maxConcurrentDeliveries} platform threads. Exists to measure the difference, not as a tuning knob.
 */
@ConfigurationProperties("webhooks.worker")
@Validated
record WorkerProperties(@NotNull Duration dispatchInterval, @Positive int dispatchBatchSize,
		@NotNull Duration reaperInterval, @NotNull Duration stuckAfter, @NotNull Duration lease,
		@Positive int consumers, @Positive int maxConcurrentDeliveries, @Positive int maxConcurrentPerEndpoint,
		@NotNull Duration deferDelay, @NotNull Duration connectTimeout, @NotNull Duration deliveryTimeout,
		@DefaultValue("true") boolean virtualThreads) {
}
