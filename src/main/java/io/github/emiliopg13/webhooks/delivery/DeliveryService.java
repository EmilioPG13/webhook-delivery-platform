package io.github.emiliopg13.webhooks.delivery;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.emiliopg13.webhooks.common.ApiException;

@Service
class DeliveryService {

	private final DeliveryRepository deliveries;

	private final Clock clock;

	DeliveryService(DeliveryRepository deliveries, Clock clock) {
		this.deliveries = deliveries;
		this.clock = clock;
	}

	/**
	 * Sends the same event to the same endpoint again, as a new delivery with fresh attempts. Only a delivery that
	 * has reached an end state can be replayed: one still in flight will already try again by itself.
	 */
	DeliveryView replay(UUID tenantId, UUID deliveryId) {
		DeliveryView original = deliveries.findById(tenantId, deliveryId)
			.orElseThrow(() -> ApiException.notFound("Delivery"));
		UUID replayId = deliveries.replay(tenantId, deliveryId, clock.instant())
			.orElseThrow(() -> ApiException.conflict(
					switch (original.status()) {
						case "pending", "enqueued" -> "The delivery is still in progress; wait for it to finish.";
						default -> "The endpoint of this delivery was deleted.";
					}));
		return deliveries.findById(tenantId, replayId).orElseThrow();
	}

}
