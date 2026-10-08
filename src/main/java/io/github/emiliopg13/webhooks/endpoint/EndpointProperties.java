package io.github.emiliopg13.webhooks.endpoint;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code allowInsecureHttp} lets development and tests register plain {@code http://} receivers. */
@ConfigurationProperties("webhooks.endpoints")
record EndpointProperties(boolean allowInsecureHttp) {
}
