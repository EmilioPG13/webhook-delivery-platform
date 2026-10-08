package io.github.emiliopg13.webhooks.endpoint;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.emiliopg13.webhooks.common.ApiException;
import io.github.emiliopg13.webhooks.common.PageResponse;
import io.github.emiliopg13.webhooks.common.Paging;
import io.github.emiliopg13.webhooks.common.UuidV7Generator;

@Service
class EndpointService {

	private static final String SECRET_PREFIX = "whsec_";

	private static final int SECRET_BYTES = 32;

	record CreatedEndpoint(Endpoint endpoint, String secret) {
	}

	private final EndpointRepository endpoints;

	private final EndpointSecretCipher cipher;

	private final EndpointUrlValidator urlValidator;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final UuidV7Generator uuids;

	private final SecureRandom random = new SecureRandom();

	EndpointService(EndpointRepository endpoints, EndpointSecretCipher cipher, EndpointUrlValidator urlValidator,
			JdbcClient jdbc, Clock clock, UuidV7Generator uuids) {
		this.endpoints = endpoints;
		this.cipher = cipher;
		this.urlValidator = urlValidator;
		this.jdbc = jdbc;
		this.clock = clock;
		this.uuids = uuids;
	}

	@Transactional
	CreatedEndpoint create(UUID tenantId, String url, String description, List<String> eventTypes) {
		urlValidator.validate(url);
		byte[] secret = new byte[SECRET_BYTES];
		random.nextBytes(secret);
		UUID id = uuids.next();
		Endpoint endpoint = new Endpoint(id, tenantId, url, blankToNull(description), distinct(eventTypes),
				cipher.encrypt(secret, id), cipher.keyVersion(), clock.instant());
		endpoints.save(endpoint);
		// The plaintext secret leaves the system exactly once, in the response to this call.
		return new CreatedEndpoint(endpoint, SECRET_PREFIX + Base64.getEncoder().encodeToString(secret));
	}

	@Transactional(readOnly = true)
	Endpoint get(UUID tenantId, UUID id) {
		return endpoints.findVisible(id, tenantId).orElseThrow(() -> ApiException.notFound("Endpoint"));
	}

	@Transactional(readOnly = true)
	PageResponse<Endpoint> list(UUID tenantId, Integer requestedLimit, String requestedCursor) {
		int limit = Paging.limit(requestedLimit);
		UUID cursor = Paging.cursor(requestedCursor);
		List<Endpoint> fetched = cursor == null
				? endpoints.findFirstPage(tenantId, Limit.of(limit + 1))
				: endpoints.findPageAfter(tenantId, cursor, Limit.of(limit + 1));
		return Paging.page(fetched, limit, Endpoint::getId);
	}

	@Transactional
	Endpoint update(UUID tenantId, UUID id, String url, String description, List<String> eventTypes) {
		Endpoint endpoint = get(tenantId, id);
		if (url != null) {
			urlValidator.validate(url);
		}
		endpoint.update(url, description, eventTypes == null ? null : distinct(eventTypes), clock.instant());
		return endpoint;
	}

	@Transactional
	void delete(UUID tenantId, UUID id) {
		Endpoint endpoint = get(tenantId, id);
		Instant now = clock.instant();
		endpoint.markDeleted(now);
		// Work that was waiting for this endpoint will never be sent. The delivery history stays.
		jdbc.sql("""
				UPDATE deliveries SET status = 'cancelled', updated_at = now()
				WHERE endpoint_id = :endpointId AND status IN ('pending', 'enqueued')
				""")
			.param("endpointId", id)
			.update();
	}

	private static List<String> distinct(List<String> eventTypes) {
		return List.copyOf(new LinkedHashSet<>(eventTypes));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

}
