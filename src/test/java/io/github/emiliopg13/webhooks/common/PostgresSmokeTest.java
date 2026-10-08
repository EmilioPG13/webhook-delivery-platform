package io.github.emiliopg13.webhooks.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.emiliopg13.webhooks.IntegrationTest;

/** Proves the tests run against a real PostgreSQL 18, which is where {@code uuidv7()} comes from. */
class PostgresSmokeTest extends IntegrationTest {

	@Test
	void databaseGeneratesVersion7Uuids() {
		UUID id = jdbc.sql("SELECT uuidv7()").query(UUID.class).single();

		assertThat(id.version()).isEqualTo(7);
	}

	@Test
	void serverIsPostgres18OrNewer() {
		int version = jdbc.sql("SHOW server_version_num").query(Integer.class).single();

		assertThat(version).isGreaterThanOrEqualTo(180000);
	}

}
