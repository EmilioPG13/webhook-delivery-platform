package io.github.emiliopg13.webhooks.delivery;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.emiliopg13.webhooks.delivery.RetryPolicy.Decision;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.GiveUp;
import io.github.emiliopg13.webhooks.delivery.RetryPolicy.RetryAt;

/**
 * The third step of claim, deliver, record: one short transaction that stores the attempt, moves the delivery to
 * its next state and updates the endpoint's failure counters. It never spans the HTTP call.
 */
@Service
class AttemptRecorder {

	private final DeliveryWorkRepository work;

	private final BreakerProperties breaker;

	AttemptRecorder(DeliveryWorkRepository work, BreakerProperties breaker) {
		this.work = work;
		this.breaker = breaker;
	}

	/**
	 * @param attemptNumber the number of this attempt, 1 for the first
	 * @param next what the retry policy decided; null when the attempt succeeded
	 * @return false if another worker already recorded this very attempt, in which case nothing changed
	 * @throws IllegalStateException if the delivery is no longer in flight; the transaction rolls back
	 */
	@Transactional
	boolean record(UUID deliveryId, UUID endpointId, int attemptNumber, Instant startedAt, long durationMs,
			DeliveryOutcome outcome, Decision next, Instant now) {
		if (work.insertAttempt(deliveryId, attemptNumber, startedAt, durationMs, outcome) == 0) {
			return false;
		}
		String status;
		Instant nextAttemptAt = null;
		switch (next) {
			case null -> status = "delivered";
			case RetryAt retry -> {
				status = "pending";
				nextAttemptAt = retry.at();
			}
			case GiveUp _ -> status = "failed";
		}
		if (work.finishAttempt(deliveryId, status, attemptNumber, nextAttemptAt, outcome, now) != 1) {
			throw new IllegalStateException("Delivery " + deliveryId + " is no longer in flight; attempt "
					+ attemptNumber + " is discarded");
		}
		if (next == null) {
			work.recordEndpointSuccess(endpointId);
		}
		else {
			work.recordEndpointFailure(endpointId, now);
			String fatalReason = outcome.fatalReason();
			if (fatalReason != null) {
				work.disableEndpoint(endpointId, fatalReason, now);
			}
			else {
				work.disableEndpointIfFailing(endpointId, breaker.failureThreshold(), now.minus(breaker.failingFor()),
						now);
			}
		}
		return true;
	}

}
