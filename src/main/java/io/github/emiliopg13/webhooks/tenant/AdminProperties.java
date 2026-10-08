package io.github.emiliopg13.webhooks.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator token for {@code /admin/**}. When empty, the admin API rejects every request. */
@ConfigurationProperties("webhooks.admin")
record AdminProperties(String token) {
}
