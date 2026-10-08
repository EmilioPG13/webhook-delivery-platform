package io.github.emiliopg13.webhooks.endpoint;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emiliopg13.webhooks.common.ApiException;

class EndpointUrlValidatorTest {

	private final EndpointUrlValidator strict = new EndpointUrlValidator(new EndpointProperties(false));

	private final EndpointUrlValidator lenient = new EndpointUrlValidator(new EndpointProperties(true));

	@ParameterizedTest
	@ValueSource(strings = { "https://example.com", "https://example.com/hooks/a?x=1", "https://example.com:8443/h",
			"HTTPS://Example.com/h" })
	void acceptsHttpsUrls(String url) {
		assertThatCode(() -> strict.validate(url)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://example.com", "ftp://example.com", "javascript:alert(1)", "file:///etc/passwd",
			"//example.com", "example.com/hook" })
	void rejectsOtherSchemesAndRelativeUrls(String url) {
		assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(ApiException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://user:pass@example.com/h", "https://user@example.com/h" })
	void rejectsCredentialsInTheUrl(String url) {
		assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(ApiException.class)
			.hasMessage("Validation failed.");
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://", "https:///path", "https://:443/h", "https://exa mple.com", "https://example.com:0/h",
			"https://example.com:70000/h" })
	void rejectsMalformedHostsAndPorts(String url) {
		assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(ApiException.class);
	}

	@Test
	void rejectsUrlsLongerThanTheLimit() {
		String url = "https://example.com/" + "a".repeat(EndpointUrlValidator.MAX_LENGTH);

		assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(ApiException.class);
	}

	@Test
	void plainHttpIsAllowedOnlyWhenExplicitlyEnabled() {
		assertThatThrownBy(() -> strict.validate("http://localhost:8080/h")).isInstanceOf(ApiException.class);
		assertThatCode(() -> lenient.validate("http://localhost:8080/h")).doesNotThrowAnyException();
	}

}
