package io.github.emiliopg13.webhooks.endpoint;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.common.ApiException;

/** Shape checks on a destination URL. Rejections are reported as a 400 on the {@code url} field. */
@Component
class EndpointUrlValidator {

	static final int MAX_LENGTH = 2048;

	private final boolean allowInsecureHttp;

	EndpointUrlValidator(EndpointProperties properties) {
		this.allowInsecureHttp = properties.allowInsecureHttp();
	}

	void validate(String url) {
		if (url.length() > MAX_LENGTH) {
			throw reject("must be at most " + MAX_LENGTH + " characters");
		}
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException ex) {
			throw reject("must be a valid absolute URL");
		}
		String scheme = uri.getScheme();
		boolean schemeAllowed = "https".equalsIgnoreCase(scheme) || (allowInsecureHttp && "http".equalsIgnoreCase(scheme));
		if (!schemeAllowed) {
			throw reject(allowInsecureHttp ? "must use http or https" : "must use https");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw reject("must include a host");
		}
		if (uri.getUserInfo() != null) {
			throw reject("must not contain credentials");
		}
		if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
			throw reject("has an invalid port");
		}
	}

	private static ApiException reject(String message) {
		return ApiException.invalidField("url", message);
	}

}
