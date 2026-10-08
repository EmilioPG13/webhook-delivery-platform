package io.github.emiliopg13.webhooks.delivery;

import java.util.Locale;

/** Why an attempt failed. The lowercase name is what {@code delivery_attempts.error_kind} stores. */
enum ErrorKind {

	TIMEOUT, CONNECT, TLS, HTTP_STATUS, BLOCKED_DESTINATION, UNEXPECTED;

	String value() {
		return name().toLowerCase(Locale.ROOT);
	}

}
