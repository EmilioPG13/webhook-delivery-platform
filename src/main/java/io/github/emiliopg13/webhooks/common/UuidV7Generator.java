package io.github.emiliopg13.webhooks.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Time-ordered UUIDs (RFC 9562 version 7) generated in the application, for rows whose id is needed before the
 * INSERT (an endpoint's id is part of the associated data of its encrypted secret). Rows inserted by SQL use the
 * database's {@code uuidv7()} instead.
 *
 * <p>Ids from one generator are strictly increasing, even within the same millisecond: a 12-bit counter in the
 * {@code rand_a} field breaks ties (RFC 9562, method 1). If the counter overflows, the timestamp moves one
 * millisecond ahead of the clock rather than repeating.
 */
@Component
public class UuidV7Generator {

	private static final int COUNTER_BITS = 12;

	private static final int COUNTER_MAX = (1 << COUNTER_BITS) - 1;

	// A new millisecond starts the counter in the lower half, leaving room to count up without overflowing.
	private static final int COUNTER_START_BOUND = 1 << (COUNTER_BITS - 1);

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	private long lastMillis = Long.MIN_VALUE;

	private int counter;

	public UuidV7Generator(Clock clock) {
		this.clock = clock;
	}

	public synchronized UUID next() {
		long now = clock.millis();
		if (now > lastMillis) {
			lastMillis = now;
			counter = random.nextInt(COUNTER_START_BOUND);
		}
		else if (counter < COUNTER_MAX) {
			counter++;
		}
		else {
			lastMillis++;
			counter = random.nextInt(COUNTER_START_BOUND);
		}
		long mostSignificant = (lastMillis << 16) | 0x7000L | counter;
		long leastSignificant = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
		return new UUID(mostSignificant, leastSignificant);
	}

}
