package io.github.emiliopg13.webhooks.tenant;

import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletRequest;

final class BearerToken {

	private static final String SCHEME = "Bearer ";

	private BearerToken() {
	}

	/** The token from {@code Authorization: Bearer <token>}, or null if the header is absent or another scheme. */
	static String from(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
			return null;
		}
		String token = header.substring(SCHEME.length()).trim();
		return token.isEmpty() ? null : token;
	}

}
