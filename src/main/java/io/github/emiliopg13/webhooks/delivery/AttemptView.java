package io.github.emiliopg13.webhooks.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * What one HTTP attempt did. {@code responseSnippet} is the first bytes of the receiver's reply: content from a
 * third party, so clients must never render it as HTML.
 */
public record AttemptView(UUID id, UUID deliveryId, int attemptNumber, Instant startedAt, int durationMs,
		Integer responseStatus, String errorKind, String errorMessage, String responseSnippet) {
}
