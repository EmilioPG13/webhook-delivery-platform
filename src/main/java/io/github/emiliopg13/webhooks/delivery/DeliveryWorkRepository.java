package io.github.emiliopg13.webhooks.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.emiliopg13.webhooks.common.Db;

/**
 * The statements the worker runs, as plain SQL on purpose. They carry the concurrency guarantees of the whole
 * system, so they should read as exactly what the database executes. Time is a parameter taken from the injected
 * {@code Clock}, never {@code now()}, so tests can move it.
 *
 * <p>There is no tenant filter here: the worker acts on behalf of no caller. It only ever follows ids that come
 * from {@code deliveries} rows, which carry their own tenant.
 */
@Repository
class DeliveryWorkRepository {

	/** Everything needed to make one HTTP attempt, read after the claim succeeded. */
	record WorkItem(UUID deliveryId, UUID eventId, String eventType, String payloadJson, Instant eventCreatedAt,
			UUID endpointId, String url, byte[] secretCiphertext, int secretKeyVersion, int attemptCount) {
	}

	private final JdbcClient jdbc;

	DeliveryWorkRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Dispatcher: takes up to {@code limit} due deliveries and marks them {@code enqueued}. {@code SKIP LOCKED} lets
	 * several dispatchers run at once: each skips the rows another one holds instead of waiting for it. The caller
	 * sends the returned ids to the queue <em>after</em> this statement committed.
	 */
	List<UUID> markDueAsEnqueued(Instant now, int limit) {
		return jdbc.sql("""
				WITH due AS (
				    SELECT d.id
				    FROM deliveries d
				    JOIN endpoints e ON e.id = d.endpoint_id
				    WHERE d.status = 'pending' AND d.next_attempt_at <= :now AND e.active
				    ORDER BY d.next_attempt_at
				    LIMIT :limit
				    FOR UPDATE OF d SKIP LOCKED
				)
				UPDATE deliveries d
				SET status = 'enqueued', enqueued_at = :now, updated_at = :now
				FROM due
				WHERE d.id = due.id
				RETURNING d.id
				""")
			.param("now", Db.timestamp(now))
			.param("limit", limit)
			.query((rs, rowNum) -> rs.getObject("id", UUID.class))
			.list();
	}

	/**
	 * Consumer: takes a lease on the delivery. Succeeds only if it is {@code enqueued} and nobody holds a live
	 * lease, so of two workers holding the same message exactly one proceeds. Empty means somebody else has it, or
	 * it already finished.
	 */
	Optional<WorkItem> claim(UUID deliveryId, Instant now, Instant leaseUntil) {
		record Claimed(UUID eventId, UUID endpointId, int attemptCount) {
		}
		Optional<Claimed> claimed = jdbc.sql("""
				UPDATE deliveries
				SET lease_until = :leaseUntil, updated_at = :now
				WHERE id = :id AND status = 'enqueued'
				  AND (lease_until IS NULL OR lease_until < :now)
				RETURNING event_id, endpoint_id, attempt_count
				""")
			.param("id", deliveryId)
			.param("now", Db.timestamp(now))
			.param("leaseUntil", Db.timestamp(leaseUntil))
			.query((rs, rowNum) -> new Claimed(rs.getObject("event_id", UUID.class),
					rs.getObject("endpoint_id", UUID.class), rs.getInt("attempt_count")))
			.optional();
		return claimed.map(c -> load(deliveryId, c.eventId(), c.endpointId(), c.attemptCount()));
	}

	private WorkItem load(UUID deliveryId, UUID eventId, UUID endpointId, int attemptCount) {
		return jdbc.sql("""
				SELECT ev.event_type, CAST(ev.payload AS text) AS payload, ev.created_at,
				       en.url, en.secret_ciphertext, en.secret_key_version
				FROM events ev, endpoints en
				WHERE ev.id = :eventId AND en.id = :endpointId
				""")
			.param("eventId", eventId)
			.param("endpointId", endpointId)
			.query((rs, rowNum) -> new WorkItem(deliveryId, eventId, rs.getString("event_type"),
					rs.getString("payload"), Db.instant(rs, "created_at"), endpointId, rs.getString("url"),
					rs.getBytes("secret_ciphertext"), rs.getInt("secret_key_version"), attemptCount))
			.single();
	}

	/**
	 * Gives back a delivery this worker claimed but cannot take on right now: it returns to {@code pending}, due at
	 * {@code retryAt}, with its attempt count untouched. A deferral is not an attempt and must not use one up.
	 */
	void deferClaimed(UUID deliveryId, Instant retryAt, Instant now) {
		jdbc.sql("""
				UPDATE deliveries
				SET status = 'pending', lease_until = NULL, next_attempt_at = :retryAt, updated_at = :now
				WHERE id = :id AND status = 'enqueued'
				""")
			.param("id", deliveryId)
			.param("retryAt", Db.timestamp(retryAt))
			.param("now", Db.timestamp(now))
			.update();
	}

	/**
	 * Reaper: gives deliveries stuck in {@code enqueued} back to {@code pending}. Covers a crash between marking
	 * and sending, a lost SQS message, and a worker that died holding a lease. A live lease protects a delivery
	 * that is merely slow.
	 */
	int returnStuckToPending(Instant now, Instant enqueuedBefore) {
		return jdbc.sql("""
				UPDATE deliveries
				SET status = 'pending', lease_until = NULL, updated_at = :now
				WHERE status = 'enqueued'
				  AND enqueued_at < :enqueuedBefore
				  AND (lease_until IS NULL OR lease_until < :now)
				""")
			.param("now", Db.timestamp(now))
			.param("enqueuedBefore", Db.timestamp(enqueuedBefore))
			.update();
	}

	/**
	 * Stores the attempt. The unique {@code (delivery_id, attempt_number)} decides a race between two workers that
	 * both finished the same attempt: the second inserts nothing.
	 */
	int insertAttempt(UUID deliveryId, int attemptNumber, Instant startedAt, long durationMs, DeliveryOutcome outcome) {
		return jdbc.sql("""
				INSERT INTO delivery_attempts (delivery_id, attempt_number, started_at, duration_ms, response_status,
				                               error_kind, error_message, response_snippet)
				VALUES (:deliveryId, :attemptNumber, :startedAt, :durationMs, :status, :errorKind, :errorMessage, :snippet)
				ON CONFLICT (delivery_id, attempt_number) DO NOTHING
				""")
			.param("deliveryId", deliveryId)
			.param("attemptNumber", attemptNumber)
			.param("startedAt", Db.timestamp(startedAt))
			.param("durationMs", Math.toIntExact(durationMs))
			.param("status", outcome.statusCode())
			.param("errorKind", outcome.errorKind() == null ? null : outcome.errorKind().value())
			.param("errorMessage", outcome.errorMessage())
			.param("snippet", outcome.responseSnippet())
			.update();
	}

	/**
	 * Moves the delivery out of {@code enqueued}: to {@code delivered}, back to {@code pending} with the next
	 * date, or to {@code failed}. The status guard makes it a no-op when the delivery is no longer in flight.
	 */
	int finishAttempt(UUID deliveryId, String status, int attemptCount, Instant nextAttemptAt, DeliveryOutcome outcome,
			Instant now) {
		return jdbc.sql("""
				UPDATE deliveries
				SET status = :status, attempt_count = :attemptCount,
				    next_attempt_at = COALESCE(:nextAttemptAt, next_attempt_at), lease_until = NULL,
				    last_status_code = :statusCode, last_error_kind = :errorKind,
				    delivered_at = CASE WHEN :status = 'delivered' THEN :now ELSE delivered_at END,
				    updated_at = :now
				WHERE id = :id AND status = 'enqueued'
				""")
			.param("id", deliveryId)
			.param("status", status)
			.param("attemptCount", attemptCount)
			.param("nextAttemptAt", Db.timestamp(nextAttemptAt))
			.param("statusCode", outcome.statusCode())
			.param("errorKind", outcome.errorKind() == null ? null : outcome.errorKind().value())
			.param("now", Db.timestamp(now))
			.update();
	}

	/**
	 * A success clears the endpoint's failure streak. The row is touched only if there is a streak to clear: every
	 * delivery to an endpoint updates this one row, so a healthy endpoint must not pay a write per delivery.
	 * {@code updated_at} is left alone; it tracks configuration changes, not counters.
	 */
	void recordEndpointSuccess(UUID endpointId) {
		jdbc.sql("""
				UPDATE endpoints
				SET consecutive_failures = 0, failing_since = NULL
				WHERE id = :id AND (consecutive_failures <> 0 OR failing_since IS NOT NULL)
				""")
			.param("id", endpointId)
			.update();
	}

	/**
	 * The circuit breaker: switches the endpoint off once the streak is long enough in both count and duration.
	 * Runs right after {@link #recordEndpointFailure}, in the same transaction, so it sees the streak that failure
	 * just extended. An endpoint that is already off, deleted ones included, keeps its original reason.
	 */
	void disableEndpointIfFailing(UUID endpointId, int failureThreshold, Instant failingSinceAtLatest, Instant now) {
		jdbc.sql("""
				UPDATE endpoints
				SET active = false, disabled_reason = 'failing', disabled_at = :now
				WHERE id = :id AND active
				  AND consecutive_failures >= :threshold AND failing_since <= :failingSinceAtLatest
				""")
			.param("id", endpointId)
			.param("threshold", failureThreshold)
			.param("failingSinceAtLatest", Db.timestamp(failingSinceAtLatest))
			.param("now", Db.timestamp(now))
			.update();
	}

	/** Switches the endpoint off for a reason that needs no streak, such as a 410 Gone. */
	void disableEndpoint(UUID endpointId, String reason, Instant now) {
		jdbc.sql("""
				UPDATE endpoints
				SET active = false, disabled_reason = :reason, disabled_at = :now
				WHERE id = :id AND active
				""")
			.param("id", endpointId)
			.param("reason", reason)
			.param("now", Db.timestamp(now))
			.update();
	}

	/**
	 * Cancels the pending deliveries of endpoints that have been off since {@code disabledAtLatest}. Their
	 * receivers had their chance; the history stays, and a replay can still send them later.
	 */
	int cancelPendingOfLongDisabledEndpoints(Instant now, Instant disabledAtLatest) {
		return jdbc.sql("""
				UPDATE deliveries d
				SET status = 'cancelled', updated_at = :now
				FROM endpoints e
				WHERE e.id = d.endpoint_id AND NOT e.active AND e.disabled_at <= :disabledAtLatest
				  AND d.status = 'pending'
				""")
			.param("now", Db.timestamp(now))
			.param("disabledAtLatest", Db.timestamp(disabledAtLatest))
			.update();
	}

	/** A failure extends the streak; {@code failing_since} keeps the date of its first failure. */
	void recordEndpointFailure(UUID endpointId, Instant now) {
		jdbc.sql("""
				UPDATE endpoints
				SET consecutive_failures = consecutive_failures + 1, failing_since = COALESCE(failing_since, :now)
				WHERE id = :id
				""")
			.param("id", endpointId)
			.param("now", Db.timestamp(now))
			.update();
	}

}
