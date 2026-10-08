package io.github.emiliopg13.webhooks.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.emiliopg13.webhooks.common.Db;

/**
 * API keys are 256 random bits, so a fast SHA-256 is enough to store them: unlike a human password they cannot
 * be guessed, only looked up. The full key exists only in the response that creates it.
 */
@Service
public class ApiKeyService {

	static final String KEY_PREFIX = "whk_";

	private static final int RANDOM_BYTES = 32;

	private static final int VISIBLE_CHARS = 8;

	private static final int MAX_KEY_LENGTH = 128;

	private static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(1);

	public record IssuedApiKey(UUID id, String prefix, String key) {
	}

	private final JdbcClient jdbc;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	ApiKeyService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	IssuedApiKey issue(UUID tenantId) {
		byte[] randomBytes = new byte[RANDOM_BYTES];
		random.nextBytes(randomBytes);
		String secretPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
		String key = KEY_PREFIX + secretPart;
		String prefix = KEY_PREFIX + secretPart.substring(0, VISIBLE_CHARS);
		UUID id = jdbc.sql("""
				INSERT INTO api_keys (tenant_id, prefix, key_hash)
				VALUES (:tenantId, :prefix, :hash)
				RETURNING id
				""")
			.param("tenantId", tenantId)
			.param("prefix", prefix)
			.param("hash", sha256(key))
			.query(UUID.class)
			.single();
		return new IssuedApiKey(id, prefix, key);
	}

	/** Returns the caller for an active key, or empty for unknown, malformed or revoked keys. */
	public Optional<TenantPrincipal> authenticate(String presentedKey) {
		if (presentedKey == null || !presentedKey.startsWith(KEY_PREFIX) || presentedKey.length() > MAX_KEY_LENGTH) {
			return Optional.empty();
		}
		Optional<TenantPrincipal> principal = jdbc
			.sql("""
					SELECT k.id, k.tenant_id, t.publish_rate_per_min
					FROM api_keys k JOIN tenants t ON t.id = k.tenant_id
					WHERE k.key_hash = :hash AND k.status = 'active'
					""")
			.param("hash", sha256(presentedKey))
			.query((rs, rowNum) -> new TenantPrincipal(rs.getObject("tenant_id", UUID.class),
					rs.getObject("id", UUID.class), rs.getInt("publish_rate_per_min")))
			.optional();
		principal.ifPresent(found -> touchLastUsed(found.apiKeyId()));
		return principal;
	}

	/** Returns false when the key does not exist. Revoking twice is fine. */
	boolean revoke(UUID apiKeyId) {
		int updated = jdbc.sql("""
				UPDATE api_keys
				SET status = 'revoked', revoked_at = COALESCE(revoked_at, :now)
				WHERE id = :id
				""")
			.param("now", Db.timestamp(clock.instant()))
			.param("id", apiKeyId)
			.update();
		return updated > 0;
	}

	// At most one write per key per minute, so authenticating does not turn every request into a row update.
	private void touchLastUsed(UUID apiKeyId) {
		Instant now = clock.instant();
		jdbc.sql("""
				UPDATE api_keys SET last_used_at = :now
				WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :threshold)
				""")
			.param("now", Db.timestamp(now))
			.param("id", apiKeyId)
			.param("threshold", Db.timestamp(now.minus(LAST_USED_GRANULARITY)))
			.update();
	}

	static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
		}
	}

}
