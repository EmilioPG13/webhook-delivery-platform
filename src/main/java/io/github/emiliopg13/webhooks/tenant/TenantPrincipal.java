package io.github.emiliopg13.webhooks.tenant;

import java.util.UUID;

/**
 * The authenticated caller of {@code /v1/**}. This is the only place a tenant id enters a request: controllers
 * take it from here, never from the URL or the body. It also carries the tenant's publish rate, which the same
 * lookup reads, so the rate limiter needs no query of its own.
 */
public record TenantPrincipal(UUID tenantId, UUID apiKeyId, int publishRatePerMin) {
}
