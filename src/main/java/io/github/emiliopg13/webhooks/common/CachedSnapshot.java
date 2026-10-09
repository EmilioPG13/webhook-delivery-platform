package io.github.emiliopg13.webhooks.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A value that costs a query or a remote call to read, kept for a short time so that several gauges, or several
 * scrapers, share one read. If a refresh fails the previous value stays: a gauge that reports the last known number
 * is more useful than one that vanishes exactly when something is wrong.
 */
public final class CachedSnapshot<T> {

	private static final Logger log = LoggerFactory.getLogger(CachedSnapshot.class);

	private final String name;

	private final Clock clock;

	private final Duration ttl;

	private final Supplier<T> loader;

	private T value;

	private Instant loadedAt;

	/** @param initial what to report if the very first load fails */
	public CachedSnapshot(String name, Clock clock, Duration ttl, Supplier<T> loader, T initial) {
		this.name = name;
		this.clock = clock;
		this.ttl = ttl;
		this.loader = loader;
		this.value = initial;
	}

	public synchronized T get() {
		Instant now = clock.instant();
		if (loadedAt == null || now.isAfter(loadedAt.plus(ttl))) {
			try {
				value = loader.get();
			}
			catch (RuntimeException ex) {
				log.warn("Could not refresh {}; reporting the last known value: {}", name, ex.toString());
			}
			// Also after a failure: otherwise every gauge read would retry a call that is already failing.
			loadedAt = now;
		}
		return value;
	}

}
