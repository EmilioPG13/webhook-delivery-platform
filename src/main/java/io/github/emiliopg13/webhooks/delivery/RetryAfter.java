package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parses the {@code Retry-After} header: either a number of seconds or an HTTP date (RFC 9110, 10.2.3). */
final class RetryAfter {

	// Ten digits already exceed any wait the retry policy would honor; longer values would overflow a long.
	private static final int MAX_DIGITS = 10;

	private RetryAfter() {
	}

	/** The wait the header asks for, or null if it is absent or not understood. Never negative. */
	static Duration parse(String header, Instant now) {
		if (header == null || header.isBlank()) {
			return null;
		}
		String value = header.trim();
		if (value.chars().allMatch(Character::isDigit)) {
			return Duration.ofSeconds(Long.parseLong(value.length() > MAX_DIGITS ? "9".repeat(MAX_DIGITS) : value));
		}
		try {
			Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
			Duration wait = Duration.between(now, date);
			return wait.isNegative() ? Duration.ZERO : wait;
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

}
