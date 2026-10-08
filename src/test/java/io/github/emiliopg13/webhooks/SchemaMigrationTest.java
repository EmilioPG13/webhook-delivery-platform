package io.github.emiliopg13.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Checks V1 against a real PostgreSQL 18: objects exist and the constraints actually reject bad rows. */
class SchemaMigrationTest extends IntegrationTest {

	@Test
	void flywayAppliedVersion1() {
		List<String> versions = jdbc
			.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
			.query(String.class)
			.list();

		assertThat(versions).startsWith("1");
	}

	@Test
	void allTablesExist() {
		List<String> tables = jdbc
			.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
			.query(String.class)
			.list();

		assertThat(tables).contains("tenants", "api_keys", "endpoints", "events", "deliveries", "delivery_attempts");
	}

	@Test
	void allIndexesExist() {
		List<String> indexes = jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
			.query(String.class)
			.list();

		assertThat(indexes).contains("api_keys_hash_uq", "api_keys_tenant_ix", "endpoints_tenant_active_ix",
				"events_tenant_ix", "events_idempotency_uq", "deliveries_due_ix", "deliveries_enqueued_ix",
				"deliveries_event_ix", "deliveries_endpoint_ix", "deliveries_tenant_ix", "delivery_attempts_uq");
	}

	@Test
	void dispatcherAndReaperIndexesArePartial() {
		String due = indexDefinition("deliveries_due_ix");
		String enqueued = indexDefinition("deliveries_enqueued_ix");

		assertThat(due).contains("WHERE").contains("'pending'");
		assertThat(enqueued).contains("WHERE").contains("'enqueued'");
	}

	@Test
	void deliveryStatusIsConstrained() {
		UUID tenant = insertTenant();
		UUID event = insertEvent(tenant, null);
		UUID endpoint = insertEndpoint(tenant);

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO deliveries (tenant_id, event_id, endpoint_id, status)
				VALUES (:t, :e, :p, 'bogus')
				""").param("t", tenant).param("e", event).param("p", endpoint).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void idempotencyKeyIsUniquePerTenantButNullNeverConflicts() {
		UUID tenant = insertTenant();
		UUID otherTenant = insertTenant();

		insertEvent(tenant, "key-1");
		insertEvent(otherTenant, "key-1");
		insertEvent(tenant, null);
		insertEvent(tenant, null);

		assertThatThrownBy(() -> insertEvent(tenant, "key-1")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void endpointNeedsAtLeastOneEventTypeAndABoundedUrl() {
		UUID tenant = insertTenant();

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO endpoints (tenant_id, url, event_types, secret_ciphertext, secret_key_version)
				VALUES (:t, 'https://example.com', '{}', '\\x00', 1)
				""").param("t", tenant).update()).isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO endpoints (tenant_id, url, event_types, secret_ciphertext, secret_key_version)
				VALUES (:t, :url, '{*}', '\\x00', 1)
				""").param("t", tenant).param("url", "https://example.com/" + "a".repeat(2049)).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rowsCannotReferenceMissingParents() {
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO api_keys (tenant_id, prefix, key_hash) VALUES (:t, 'p', '\\x01')")
			.param("t", UUID.randomUUID())
			.update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	private String indexDefinition(String name) {
		return jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = :name")
			.param("name", name)
			.query(String.class)
			.single();
	}

	private UUID insertTenant() {
		return jdbc.sql("INSERT INTO tenants (name) VALUES ('schema-test') RETURNING id").query(UUID.class).single();
	}

	private UUID insertEvent(UUID tenant, String idempotencyKey) {
		return jdbc.sql("""
				INSERT INTO events (tenant_id, event_type, payload, idempotency_key, request_hash)
				VALUES (:t, 'a.b', '{}'::jsonb, :k, '\\x01') RETURNING id
				""").param("t", tenant).param("k", idempotencyKey).query(UUID.class).single();
	}

	private UUID insertEndpoint(UUID tenant) {
		return jdbc.sql("""
				INSERT INTO endpoints (tenant_id, url, event_types, secret_ciphertext, secret_key_version)
				VALUES (:t, 'https://example.com', '{*}', '\\x00', 1) RETURNING id
				""").param("t", tenant).query(UUID.class).single();
	}

}
