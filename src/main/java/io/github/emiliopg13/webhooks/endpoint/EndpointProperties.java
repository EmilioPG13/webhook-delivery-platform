package io.github.emiliopg13.webhooks.endpoint;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Development switches, both off in production.
 *
 * @param allowInsecureHttp lets development and tests register plain {@code http://} receivers
 * @param allowPrivateDestinations turns the SSRF guard off, so receivers on localhost or a private network work;
 * a local receiver is exactly what development and tests need, and exactly what the guard exists to refuse
 */
@ConfigurationProperties("webhooks.endpoints")
public record EndpointProperties(boolean allowInsecureHttp, boolean allowPrivateDestinations) {
}
