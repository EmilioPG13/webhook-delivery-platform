package io.github.emiliopg13.webhooks.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC helpers for {@code timestamptz}: the PostgreSQL driver takes {@link OffsetDateTime}, not {@link Instant}. */
public final class Db {

	private Db() {
	}

	public static OffsetDateTime timestamp(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	public static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	/** Reads a nullable integer column: {@code getInt} would turn SQL NULL into 0. */
	public static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

}
