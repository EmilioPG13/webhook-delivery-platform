package io.github.emiliopg13.webhooks.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7Test {

	private static UuidV7Generator generatorAt(String instant) {
		return new UuidV7Generator(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
	}

	@Test
	void hasVersion7AndRfc9562Variant() {
		UUID id = new UuidV7Generator(Clock.systemUTC()).next();

		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void embedsTheClockTimeInTheFirst48Bits() {
		Instant moment = Instant.parse("2026-10-08T12:00:00.123Z");

		UUID id = generatorAt("2026-10-08T12:00:00.123Z").next();

		assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(moment.toEpochMilli());
	}

	@Test
	void idsFromLaterMillisecondsSortAfterEarlierOnes() {
		UUID earlier = generatorAt("2026-10-08T12:00:00.000Z").next();
		UUID later = generatorAt("2026-10-08T12:00:00.001Z").next();

		assertThat(later).isGreaterThan(earlier);
	}

	@Test
	void idsGeneratedInTheSameMillisecondAreUniqueAndStrictlyIncreasing() {
		UuidV7Generator generator = generatorAt("2026-10-08T12:00:00Z");
		List<UUID> ids = new ArrayList<>();

		// More than the 4096 a single millisecond can count, to cross the overflow path as well.
		for (int i = 0; i < 10_000; i++) {
			ids.add(generator.next());
		}

		assertThat(new HashSet<>(ids)).hasSize(10_000);
		assertThat(ids).isSorted();
	}

	@Test
	void everyIdKeepsVersionAndVariantAcrossTheCounterOverflow() {
		UuidV7Generator generator = generatorAt("2026-10-08T12:00:00Z");

		for (int i = 0; i < 10_000; i++) {
			UUID id = generator.next();
			assertThat(id.version()).isEqualTo(7);
			assertThat(id.variant()).isEqualTo(2);
		}
	}

}
