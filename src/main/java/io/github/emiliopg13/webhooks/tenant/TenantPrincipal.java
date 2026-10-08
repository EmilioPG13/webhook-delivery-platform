package io.github.emiliopg13.webhooks.tenant;

import java.util.UUID;

/**
 * The authenticated caller of {@code /v1/**}. This is the only place a tenant id enters a request: controllers
 * take it from here, never from the URL or the body.
 */
public record TenantPrincipal(UUID tenantId, UUID apiKeyId) {
}
