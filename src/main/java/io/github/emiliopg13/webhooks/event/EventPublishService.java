package io.github.emiliopg13.webhooks.event;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.emiliopg13.webhooks.common.ApiException;
import io.github.emiliopg13.webhooks.delivery.DeliveryRepository;
import io.github.emiliopg13.webhooks.event.EventRepository.Created;
import io.github.emiliopg13.webhooks.event.EventRepository.Existing;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishing is one short transaction: the event row and one delivery row per subscribed endpoint, or nothing.
 * Nothing is sent here; the worker picks the deliveries up later.
 */
@Service
class EventPublishService {

	record PublishResult(UUID id, String type, Instant createdAt, int deliveries, boolean replayed) {
	}

	private final EventRepository events;

	private final DeliveryRepository deliveries;

	private final MeterRegistry meters;

	EventPublishService(EventRepository events, DeliveryRepository deliveries, MeterRegistry meters) {
		this.events = events;
		this.deliveries = deliveries;
		this.meters = meters;
	}

	@Transactional
	PublishResult publish(UUID tenantId, String idempotencyKey, String type, String payloadJson, byte[] requestHash) {
		Optional<Created> created = events.insertIfAbsent(tenantId, type, payloadJson, idempotencyKey, requestHash);
		if (created.isPresent()) {
			int fanOut = deliveries.fanOut(tenantId, created.get().id(), type);
			// Counted before the commit, like the attempt metrics: a rollback here is a rare failure, not a flow.
			meters.counter("webhooks.events.published", "result", "created").increment();
			return new PublishResult(created.get().id(), type, created.get().createdAt(), fanOut, false);
		}
		// No row came back, so (tenant, idempotency key) already exists. Read it in a fresh statement: under
		// READ COMMITTED it sees the winner's committed row.
		Existing existing = events.findByIdempotencyKey(tenantId, idempotencyKey)
			.orElseThrow(() -> new IllegalStateException("Idempotency conflict without a stored event"));
		if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
			throw ApiException.unprocessable("Idempotency-Key was already used with a different request body.");
		}
		int fanOut = deliveries.countOriginalsForEvent(tenantId, existing.id());
		meters.counter("webhooks.events.published", "result", "replayed").increment();
		return new PublishResult(existing.id(), existing.type(), existing.createdAt(), fanOut, true);
	}

}
