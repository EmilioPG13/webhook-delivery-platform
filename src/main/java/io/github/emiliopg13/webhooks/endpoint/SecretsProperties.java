package io.github.emiliopg13.webhooks.endpoint;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * The keys that encrypt endpoint secrets at rest, each as base64 of 32 bytes.
 *
 * <p>To rotate: give the new key a higher {@code keyVersion} as {@code masterKey}, and move the old one to
 * {@code retiredKeys} under its own version number. New secrets use the new key; existing ones keep working
 * through the retired key. There is no job that re-encrypts old rows yet, so a retired key must stay until none
 * of them is left.
 *
 * @param masterKey the key new secrets are encrypted with
 * @param keyVersion its version number
 * @param retiredKeys older keys that only decrypt, by version number
 */
@ConfigurationProperties("webhooks.secrets")
@Validated
record SecretsProperties(@NotBlank String masterKey, @Positive int keyVersion,
		@DefaultValue Map<Integer, String> retiredKeys) {
}
