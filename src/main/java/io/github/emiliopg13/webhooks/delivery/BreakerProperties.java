package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * The per-endpoint circuit breaker. An endpoint is switched off when it has failed this many times in a row
 * <em>and</em> has been failing for at least {@code failingFor}: the streak alone could be a burst of retries
 * within minutes, the duration alone could be two failures a day apart.
 *
 * @param failureThreshold consecutive failed attempts that count as a broken endpoint
 * @param failingFor how long the streak must have lasted
 * @param cancelAfter how long a switched-off endpoint keeps its pending deliveries before they are cancelled
 * @param sweepInterval pause between two runs of the job that cancels them
 */
@ConfigurationProperties("webhooks.breaker")
@Validated
record BreakerProperties(@Positive int failureThreshold, @NotNull Duration failingFor, @NotNull Duration cancelAfter,
		@NotNull Duration sweepInterval) {
}
