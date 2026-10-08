package io.github.emiliopg13.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterTest {

	private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

	@Test
	void readsSeconds() {
		assertThat(RetryAfter.parse("120", NOW)).isEqualTo(Duration.ofSeconds(120));
	}

	@Test
	void toleratesSurroundingWhitespace() {
		assertThat(RetryAfter.parse("  30 ", NOW)).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void readsAnHttpDateRelativeToNow() {
		assertThat(RetryAfter.parse("Thu, 08 Oct 2026 12:05:00 GMT", NOW)).isEqualTo(Duration.ofMinutes(5));
	}

	@Test
	void aDateInThePastMeansNoWait() {
		assertThat(RetryAfter.parse("Thu, 08 Oct 2026 11:00:00 GMT", NOW)).isEqualTo(Duration.ZERO);
	}

	@Test
	void aHugeNumberDoesNotOverflow() {
		assertThat(RetryAfter.parse("99999999999999999999999", NOW)).isGreaterThan(Duration.ofDays(365));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  ", "soon", "-5", "1.5", "12 seconds" })
	void unreadableValuesAreIgnored(String header) {
		assertThat(RetryAfter.parse(header, NOW)).isNull();
	}

}
