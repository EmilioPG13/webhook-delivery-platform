package io.github.emiliopg13.webhooks.tenant;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.emiliopg13.webhooks.common.ApiException;
import io.github.emiliopg13.webhooks.common.UuidV7Generator;
import io.github.emiliopg13.webhooks.tenant.ApiKeyService.IssuedApiKey;

@Service
class TenantAdminService {

	private static final int DEFAULT_PUBLISH_RATE_PER_MIN = 600;

	record CreatedTenant(Tenant tenant, IssuedApiKey apiKey) {
	}

	private final TenantRepository tenants;

	private final ApiKeyService apiKeys;

	private final Clock clock;

	private final UuidV7Generator uuids;

	TenantAdminService(TenantRepository tenants, ApiKeyService apiKeys, Clock clock, UuidV7Generator uuids) {
		this.tenants = tenants;
		this.apiKeys = apiKeys;
		this.clock = clock;
		this.uuids = uuids;
	}

	@Transactional
	CreatedTenant createTenant(String name, Integer publishRatePerMin) {
		int rate = publishRatePerMin != null ? publishRatePerMin : DEFAULT_PUBLISH_RATE_PER_MIN;
		// saveAndFlush, not save: JPA delays the INSERT until commit, but the API key is inserted with plain
		// SQL right after and its foreign key needs the tenant row to exist already.
		Tenant tenant = tenants.saveAndFlush(new Tenant(uuids.next(), name, rate, clock.instant()));
		return new CreatedTenant(tenant, apiKeys.issue(tenant.getId()));
	}

	@Transactional
	IssuedApiKey issueApiKey(UUID tenantId) {
		if (!tenants.existsById(tenantId)) {
			throw ApiException.notFound("Tenant");
		}
		return apiKeys.issue(tenantId);
	}

	@Transactional
	void revokeApiKey(UUID apiKeyId) {
		if (!apiKeys.revoke(apiKeyId)) {
			throw ApiException.notFound("API key");
		}
	}

}
