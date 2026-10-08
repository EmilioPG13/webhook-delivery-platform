package io.github.emiliopg13.webhooks.delivery;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.emiliopg13.webhooks.common.ApiException;
import io.github.emiliopg13.webhooks.common.PageResponse;
import io.github.emiliopg13.webhooks.common.Paging;
import io.github.emiliopg13.webhooks.tenant.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1/deliveries")
@Tag(name = "Deliveries")
class DeliveryController {

	private static final Set<String> STATUSES = Set.of("pending", "enqueued", "delivered", "failed", "cancelled");

	private final DeliveryRepository deliveries;

	DeliveryController(DeliveryRepository deliveries) {
		this.deliveries = deliveries;
	}

	@GetMapping
	@Operation(summary = "List deliveries, newest first")
	PageResponse<DeliveryView> list(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@RequestParam(name = "endpoint_id", required = false) UUID endpointId,
			@RequestParam(name = "event_id", required = false) UUID eventId,
			@RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor) {
		if (status != null && !STATUSES.contains(status)) {
			throw ApiException.invalidField("status", "must be one of " + new TreeSet<>(STATUSES));
		}
		int pageSize = Paging.limit(limit);
		List<DeliveryView> fetched = deliveries.list(tenant.tenantId(), endpointId, eventId, status,
				Paging.cursor(cursor), pageSize + 1);
		return Paging.page(fetched, pageSize, DeliveryView::id);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a delivery")
	DeliveryView get(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@PathVariable UUID id) {
		return deliveries.findById(tenant.tenantId(), id).orElseThrow(() -> ApiException.notFound("Delivery"));
	}

	@GetMapping("/{id}/attempts")
	@Operation(summary = "List the attempts of a delivery, oldest first")
	PageResponse<AttemptView> attempts(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@PathVariable UUID id, @RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor) {
		deliveries.findById(tenant.tenantId(), id).orElseThrow(() -> ApiException.notFound("Delivery"));
		int pageSize = Paging.limit(limit);
		List<AttemptView> fetched = deliveries.listAttempts(tenant.tenantId(), id, Paging.cursor(cursor),
				pageSize + 1);
		return Paging.page(fetched, pageSize, AttemptView::id);
	}

}
