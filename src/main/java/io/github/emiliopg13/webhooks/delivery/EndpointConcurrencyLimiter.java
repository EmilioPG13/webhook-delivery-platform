package io.github.emiliopg13.webhooks.delivery;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Caps how many deliveries to one endpoint are in flight in this worker. Without it a slow endpoint, whose calls
 * last until the timeout, fills every slot of the worker's global limit and the healthy endpoints wait behind it.
 *
 * <p>It never blocks: a delivery that finds no free place is sent back to {@code pending} for a moment (see
 * {@code DeliveryProcessor}), so it keeps neither a global slot nor its lease while it waits. The count is per
 * worker; with several workers the endpoint can have that many in flight on each.
 */
@Component
@Profile("worker")
class EndpointConcurrencyLimiter {

	private final int maxPerEndpoint;

	private final ConcurrentHashMap<UUID, Integer> inFlight = new ConcurrentHashMap<>();

	EndpointConcurrencyLimiter(WorkerProperties properties) {
		this.maxPerEndpoint = properties.maxConcurrentPerEndpoint();
	}

	/** Takes one place for the endpoint if there is one. Every success must be paired with {@link #release}. */
	boolean tryAcquire(UUID endpointId) {
		boolean[] acquired = new boolean[1];
		inFlight.compute(endpointId, (id, count) -> {
			int current = count == null ? 0 : count;
			if (current >= maxPerEndpoint) {
				return count;
			}
			acquired[0] = true;
			return current + 1;
		});
		return acquired[0];
	}

	void release(UUID endpointId) {
		// An idle endpoint drops out of the map, so it does not grow with every endpoint ever seen.
		inFlight.computeIfPresent(endpointId, (id, count) -> count <= 1 ? null : count - 1);
	}

}
