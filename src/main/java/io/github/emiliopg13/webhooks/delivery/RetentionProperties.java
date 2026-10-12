package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * How long history is kept. An event, its deliveries and their attempts are one unit: they go together once the
 * event is older than {@code period}.
 *
 * @param period age of an event after which it is deleted, measured from its creation
 * @param interval pause between two runs of the cleanup job
 * @param batchSize events deleted per statement, so no single transaction holds locks for long
 */
@ConfigurationProperties("webhooks.retention")
@Validated
record RetentionProperties(@NotNull Duration period, @NotNull Duration interval, @Positive int batchSize) {
}
