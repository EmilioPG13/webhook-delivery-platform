package io.github.emiliopg13.webhooks.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emiliopg13.webhooks.common.ApiException;

class EndpointUrlValidatorTest {

	private final EndpointUrlValidator strict = new EndpointUrlValidator(new EndpointProperties(false, false));

	private final EndpointUrlValidator lenient = new EndpointUrlValidator(new EndpointProperties(true, true));

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

	@ParameterizedTest
	@ValueSource(strings = { "https://127.0.0.1/h", "https://10.0.0.5/h", "https://172.16.0.1/h",
			"https://192.168.1.1:8443/h", "https://169.254.169.254/latest/meta-data", "https://0.0.0.0/h",
			"https://100.64.0.1/h", "https://localhost/h", "https://LOCALHOST./h", "https://metadata.localhost/h",
			"https://[::1]/h", "https://[fe80::1]/h", "https://[fd00::1]/h", "https://[::ffff:127.0.0.1]/h",
			"https://[::ffff:a9fe:a9fe]/h" })
	void rejectsPrivateAndReservedDestinations(String url) {
		assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(ApiException.class)
			.satisfies(ex -> assertThat(((ApiException) ex).errors()).singleElement()
				.satisfies(violation -> assertThat(violation.field()).isEqualTo("url")));
	}

	/** Every one of these is 127.0.0.1 to some parser, and none is a name a customer would really register. */
	@ParameterizedTest
	@ValueSource(strings = { "https://2130706433/h", "https://0177.0.0.1/h", "https://0x7f.0.0.1/h",
			"https://0x7f000001/h", "https://127.1/h", "https://127.0.1/h", "https://017700000001/h",
			"https://127.0.0.1./h", "https://256.1.1.1/h", "https://1.2.3.04/h" })
	void rejectsUnusualIpNotations(String url) {
		assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(ApiException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://8.8.8.8/h", "https://93.184.216.34:8443/h", "https://[2606:4700:4700::1111]/h",
			"https://123.example.com/h", "https://a1.b2.example/h", "https://notlocalhost/h",
			"https://localhost.example.com/h" })
	void acceptsPublicAddressesAndOrdinaryNames(String url) {
		assertThatCode(() -> strict.validate(url)).doesNotThrowAnyException();
	}

	@Test
	void privateDestinationsAreAllowedOnlyWhenExplicitlyEnabled() {
		assertThatCode(() -> lenient.validate("http://127.0.0.1:8080/h")).doesNotThrowAnyException();
		assertThatCode(() -> lenient.validate("http://localhost:9999/h")).doesNotThrowAnyException();
		assertThatCode(() -> lenient.validate("http://[::1]:8080/h")).doesNotThrowAnyException();
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
