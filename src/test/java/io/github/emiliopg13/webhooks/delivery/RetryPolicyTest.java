package io.github.emiliopg13.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.HttpFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.NetworkFailure;
import io.github.emiliopg13.webhooks.delivery.DeliveryOutcome.Success;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.Decision;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.GiveUp;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.RetryAt;

class RetryPolicyTest {

	private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

	// nextDouble() derives from nextLong(): Long.MIN_VALUE gives exactly 0.5, so the jitter factor is exactly 1.
	private static final RandomGenerator NO_JITTER = () -> Long.MIN_VALUE;

	private static final RandomGenerator LOWEST = () -> 0L;

	private static final RandomGenerator HIGHEST = () -> -1L;

	private static final DeliveryOutcome SERVER_ERROR = new HttpFailure(500, null, null);

	private final RetryPolicy policy = new RetryPolicy(NO_JITTER);

	@ParameterizedTest
	@CsvSource({ "1,30", "2,120", "3,600", "4,1800", "5,3600", "6,10800", "7,21600", "8,43200", "9,86400" })
	void waitAfterEachAttemptFollowsTheSchedule(int attemptsMade, long expectedSeconds) {
		Decision decision = policy.decide(attemptsMade, SERVER_ERROR, NOW);

		assertThat(decision).isEqualTo(new RetryAt(NOW.plusSeconds(expectedSeconds)));
	}

	@Test
	void theWholeScheduleSpansAboutFortySevenHours() {
		Instant at = NOW;
		for (int attempt = 1; attempt < RetryPolicy.MAX_ATTEMPTS; attempt++) {
			at = ((RetryAt) policy.decide(attempt, SERVER_ERROR, at)).at();
		}

		assertThat(Duration.between(NOW, at)).isEqualTo(Duration.ofHours(46).plusMinutes(42).plusSeconds(30));
	}

	@Test
	void goneEndsTheDeliveryAtOnce() {
		assertThat(policy.decide(1, new HttpFailure(410, null, null), NOW)).isEqualTo(new GiveUp());
	}

	@Test
	void theTenthFailureGivesUp() {
		assertThat(policy.decide(RetryPolicy.MAX_ATTEMPTS, SERVER_ERROR, NOW)).isEqualTo(new GiveUp());
	}

	@Test
	void jitterStaysWithinTwentyPercentOfTheScheduledWait() {
		Instant lowest = ((RetryAt) new RetryPolicy(LOWEST).decide(3, SERVER_ERROR, NOW)).at();
		Instant highest = ((RetryAt) new RetryPolicy(HIGHEST).decide(3, SERVER_ERROR, NOW)).at();

		assertThat(Duration.between(NOW, lowest)).isEqualTo(Duration.ofSeconds(480));
		assertThat(Duration.between(NOW, highest)).isBetween(Duration.ofSeconds(719), Duration.ofSeconds(720));
	}

	@Test
	void networkFailuresFollowTheSameSchedule() {
		DeliveryOutcome timeout = new NetworkFailure(ErrorKind.TIMEOUT, "read timed out");

		assertThat(policy.decide(2, timeout, NOW)).isEqualTo(new RetryAt(NOW.plusSeconds(120)));
	}

	@Test
	void retryAfterLongerThanTheScheduleWins() {
		DeliveryOutcome unavailable = new HttpFailure(503, null, Duration.ofHours(1));

		assertThat(policy.decide(1, unavailable, NOW)).isEqualTo(new RetryAt(NOW.plusSeconds(3600)));
	}

	@Test
	void retryAfterShorterThanTheScheduleIsIgnored() {
		DeliveryOutcome tooMany = new HttpFailure(429, null, Duration.ofSeconds(60));

		assertThat(policy.decide(5, tooMany, NOW)).isEqualTo(new RetryAt(NOW.plusSeconds(3600)));
	}

	@Test
	void retryAfterIsCappedAtTwentyFourHours() {
		DeliveryOutcome unavailable = new HttpFailure(503, null, Duration.ofDays(30));

		assertThat(policy.decide(1, unavailable, NOW)).isEqualTo(new RetryAt(NOW.plus(Duration.ofHours(24))));
	}

	@Test
	void retryAfterOnOtherStatusesIsIgnored() {
		DeliveryOutcome serverError = new HttpFailure(500, null, Duration.ofHours(1));

		assertThat(policy.decide(1, serverError, NOW)).isEqualTo(new RetryAt(NOW.plusSeconds(30)));
	}

	@Test
	void tooManyRequestsWithoutRetryAfterFollowsTheSchedule() {
		assertThat(policy.decide(1, new HttpFailure(429, null, null), NOW)).isEqualTo(new RetryAt(NOW.plusSeconds(30)));
	}

	@Test
	void aSuccessIsNotRetried() {
		assertThatThrownBy(() -> policy.decide(1, new Success(200, null), NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void zeroAttemptsIsRejected() {
		assertThatThrownBy(() -> policy.decide(0, SERVER_ERROR, NOW)).isInstanceOf(IllegalArgumentException.class);
	}

}
