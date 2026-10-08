package io.github.emiliopg13.webhooks.queue;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Where the work queue lives.
 *
 * @param name queue name; the dead-letter queue is {@code <name>-dlq}
 * @param region AWS region; ElasticMQ ignores it
 * @param endpoint overrides the service URL, to point the client at ElasticMQ. Empty means real AWS with the
 * default credential chain; set, the client signs with dummy credentials.
 * @param createIfMissing creates the queue and its dead-letter queue when absent. For local runs and tests: in
 * AWS the queues come from Terraform.
 * @param visibilityTimeout how long a received message stays hidden; used only when the queue is created
 * @param maxReceiveCount deliveries of one message before SQS moves it to the dead-letter queue; used only when
 * the queue is created
 */
@ConfigurationProperties("webhooks.queue")
@Validated
public record QueueProperties(@NotBlank String name, @NotBlank String region, String endpoint, boolean createIfMissing,
		@NotNull Duration visibilityTimeout, @Positive int maxReceiveCount) {
}
