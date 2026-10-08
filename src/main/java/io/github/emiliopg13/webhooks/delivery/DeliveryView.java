package io.github.emiliopg13.webhooks.delivery;

import java.time.Instant;
import java.util.UUID;

/** One event addressed to one endpoint, with its progress. A replay is a new delivery pointing at the original. */
public record DeliveryView(UUID id, UUID eventId, UUID endpointId, UUID replayOf, String status, int attemptCount,
		Instant nextAttemptAt, Integer lastStatusCode, String lastErrorKind, Instant deliveredAt, Instant createdAt,
		Instant updatedAt) {
}
