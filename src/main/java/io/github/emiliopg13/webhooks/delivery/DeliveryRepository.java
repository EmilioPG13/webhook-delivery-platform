package io.github.emiliopg13.webhooks.delivery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.emiliopg13.webhooks.common.Db;

/**
 * Plain SQL on purpose: these statements are the core of the system and should read as exactly what the database
 * runs. Every query filters by {@code tenant_id}.
 */
@Repository
public class DeliveryRepository {

	private static final RowMapper<DeliveryView> DELIVERY_MAPPER = (rs, rowNum) -> new DeliveryView(
			rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
			rs.getObject("endpoint_id", UUID.class), rs.getObject("replay_of", UUID.class), rs.getString("status"),
			rs.getInt("attempt_count"), Db.instant(rs, "next_attempt_at"), Db.nullableInt(rs, "last_status_code"),
			rs.getString("last_error_kind"), Db.instant(rs, "delivered_at"), Db.instant(rs, "created_at"),
			Db.instant(rs, "updated_at"));

	private static final RowMapper<AttemptView> ATTEMPT_MAPPER = (rs, rowNum) -> new AttemptView(
			rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class), rs.getInt("attempt_number"),
			Db.instant(rs, "started_at"), rs.getInt("duration_ms"), Db.nullableInt(rs, "response_status"),
			rs.getString("error_kind"), rs.getString("error_message"), rs.getString("response_snippet"));

	private static final String DELIVERY_COLUMNS = """
			id, event_id, endpoint_id, replay_of, status, attempt_count, next_attempt_at, last_status_code,
			last_error_kind, delivered_at, created_at, updated_at
			""";

	private final JdbcClient jdbc;

	DeliveryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Creates one delivery per active endpoint of the tenant subscribed to the event type. Meant to run in the
	 * same transaction that inserted the event, so the event and its work appear together or not at all.
	 */
	public int fanOut(UUID tenantId, UUID eventId, String eventType) {
		return jdbc.sql("""
				INSERT INTO deliveries (tenant_id, event_id, endpoint_id)
				SELECT :tenantId, :eventId, e.id
				FROM endpoints e
				WHERE e.tenant_id = :tenantId AND e.active
				  AND ('*' = ANY (e.event_types) OR :eventType = ANY (e.event_types))
				""")
			.param("tenantId", tenantId)
			.param("eventId", eventId)
			.param("eventType", eventType)
			.update();
	}

	/** Deliveries created by publishing the event, not by manual replays. */
	public int countOriginalsForEvent(UUID tenantId, UUID eventId) {
		return jdbc.sql("""
				SELECT count(*) FROM deliveries
				WHERE tenant_id = :tenantId AND event_id = :eventId AND replay_of IS NULL
				""")
			.param("tenantId", tenantId)
			.param("eventId", eventId)
			.query(Integer.class)
			.single();
	}

	Optional<DeliveryView> findById(UUID tenantId, UUID id) {
		return jdbc.sql("SELECT " + DELIVERY_COLUMNS + " FROM deliveries WHERE tenant_id = :tenantId AND id = :id")
			.param("tenantId", tenantId)
			.param("id", id)
			.query(DELIVERY_MAPPER)
			.optional();
	}

	/** Newest first. Filters are optional; {@code fetch} is the page size plus one. */
	List<DeliveryView> list(UUID tenantId, UUID endpointId, UUID eventId, String status, UUID cursor, int fetch) {
		StringBuilder sql = new StringBuilder("SELECT " + DELIVERY_COLUMNS + " FROM deliveries WHERE tenant_id = :tenantId");
		Map<String, Object> params = new HashMap<>();
		params.put("tenantId", tenantId);
		if (endpointId != null) {
			sql.append(" AND endpoint_id = :endpointId");
			params.put("endpointId", endpointId);
		}
		if (eventId != null) {
			sql.append(" AND event_id = :eventId");
			params.put("eventId", eventId);
		}
		if (status != null) {
			sql.append(" AND status = :status");
			params.put("status", status);
		}
		if (cursor != null) {
			sql.append(" AND id < :cursor");
			params.put("cursor", cursor);
		}
		sql.append(" ORDER BY id DESC LIMIT :fetch");
		params.put("fetch", fetch);
		return jdbc.sql(sql.toString()).params(params).query(DELIVERY_MAPPER).list();
	}

	/** Oldest attempt first. The join keeps the tenant filter even though the caller checked the delivery. */
	List<AttemptView> listAttempts(UUID tenantId, UUID deliveryId, UUID cursor, int fetch) {
		StringBuilder sql = new StringBuilder("""
				SELECT a.id, a.delivery_id, a.attempt_number, a.started_at, a.duration_ms, a.response_status,
				       a.error_kind, a.error_message, a.response_snippet
				FROM delivery_attempts a
				JOIN deliveries d ON d.id = a.delivery_id
				WHERE d.tenant_id = :tenantId AND a.delivery_id = :deliveryId
				""");
		Map<String, Object> params = new HashMap<>();
		params.put("tenantId", tenantId);
		params.put("deliveryId", deliveryId);
		if (cursor != null) {
			sql.append(" AND a.id > :cursor");
			params.put("cursor", cursor);
		}
		sql.append(" ORDER BY a.id ASC LIMIT :fetch");
		params.put("fetch", fetch);
		return jdbc.sql(sql.toString()).params(params).query(ATTEMPT_MAPPER).list();
	}

}
