package io.github.emiliopg13.webhooks.endpoint;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** The master key that encrypts endpoint secrets at rest, as base64 of 32 bytes, and its version number. */
@ConfigurationProperties("webhooks.secrets")
@Validated
record SecretsProperties(@NotBlank String masterKey, @Positive int keyVersion) {
}
