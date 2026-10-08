package io.github.emiliopg13.webhooks.event;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.emiliopg13.webhooks.common.Db;

@Repository
class EventRepository {

	record Created(UUID id, Instant createdAt) {
	}

	record Existing(UUID id, String type, byte[] requestHash, Instant createdAt) {
	}

	/** {@code payload} is the stored JSON text; it is null in listings, which do not select it. */
	record Stored(UUID id, String type, String payload, Instant createdAt) {
	}

	private final JdbcClient jdbc;

	EventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts the event unless the tenant already used this idempotency key. The unique constraint decides, so
	 * concurrent publishes with the same key cannot both win: the loser waits for the winner and gets no row back.
	 * A null key never conflicts.
	 */
	Optional<Created> insertIfAbsent(UUID tenantId, String type, String payloadJson, String idempotencyKey,
			byte[] requestHash) {
		return jdbc.sql("""
				INSERT INTO events (tenant_id, event_type, payload, idempotency_key, request_hash)
				VALUES (:tenantId, :type, CAST(:payload AS jsonb), :key, :hash)
				ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
				RETURNING id, created_at
				""")
			.param("tenantId", tenantId)
			.param("type", type)
			.param("payload", payloadJson)
			.param("key", idempotencyKey)
			.param("hash", requestHash)
			.query((rs, rowNum) -> new Created(rs.getObject("id", UUID.class), Db.instant(rs, "created_at")))
			.optional();
	}

	Optional<Existing> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
		return jdbc.sql("""
				SELECT id, event_type, request_hash, created_at FROM events
				WHERE tenant_id = :tenantId AND idempotency_key = :key
				""")
			.param("tenantId", tenantId)
			.param("key", idempotencyKey)
			.query((rs, rowNum) -> new Existing(rs.getObject("id", UUID.class), rs.getString("event_type"),
					rs.getBytes("request_hash"), Db.instant(rs, "created_at")))
			.optional();
	}

	Optional<Stored> findById(UUID tenantId, UUID id) {
		return jdbc.sql("""
				SELECT id, event_type, CAST(payload AS text) AS payload, created_at FROM events
				WHERE tenant_id = :tenantId AND id = :id
				""")
			.param("tenantId", tenantId)
			.param("id", id)
			.query((rs, rowNum) -> new Stored(rs.getObject("id", UUID.class), rs.getString("event_type"),
					rs.getString("payload"), Db.instant(rs, "created_at")))
			.optional();
	}

	/** Newest first, without payloads. {@code fetch} is the page size plus one. */
	List<Stored> list(UUID tenantId, UUID cursor, int fetch) {
		StringBuilder sql = new StringBuilder("SELECT id, event_type, created_at FROM events WHERE tenant_id = :tenantId");
		Map<String, Object> params = new HashMap<>();
		params.put("tenantId", tenantId);
		if (cursor != null) {
			sql.append(" AND id < :cursor");
			params.put("cursor", cursor);
		}
		sql.append(" ORDER BY id DESC LIMIT :fetch");
		params.put("fetch", fetch);
		return jdbc.sql(sql.toString())
			.params(params)
			.query((rs, rowNum) -> new Stored(rs.getObject("id", UUID.class), rs.getString("event_type"), null,
					Db.instant(rs, "created_at")))
			.list();
	}

}
