package io.github.emiliopg13.webhooks.endpoint;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.emiliopg13.webhooks.common.PageResponse;
import io.github.emiliopg13.webhooks.endpoint.EndpointService.CreatedEndpoint;
import io.github.emiliopg13.webhooks.tenant.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/v1/endpoints")
@Tag(name = "Endpoints")
class EndpointController {

	private static final String EVENT_TYPE_PATTERN = "\\*|[A-Za-z0-9][A-Za-z0-9._-]*";

	record CreateEndpointRequest(@NotBlank @Size(max = EndpointUrlValidator.MAX_LENGTH) String url,
			@Size(max = 500) String description,
			@NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 128) @Pattern(regexp = EVENT_TYPE_PATTERN) String> eventTypes) {
	}

	/** Every field is optional; an absent field is left unchanged. An empty description clears it. */
	record UpdateEndpointRequest(@Size(max = EndpointUrlValidator.MAX_LENGTH) String url,
			@Size(max = 500) String description,
			@Size(min = 1, max = 50) List<@NotBlank @Size(max = 128) @Pattern(regexp = EVENT_TYPE_PATTERN) String> eventTypes) {
	}

	record EndpointResponse(UUID id, String url, String description, List<String> eventTypes, boolean active,
			int consecutiveFailures, Instant failingSince, String disabledReason, Instant disabledAt,
			Instant createdAt, Instant updatedAt, @JsonInclude(JsonInclude.Include.NON_NULL) String secret) {

		static EndpointResponse from(Endpoint endpoint) {
			return from(endpoint, null);
		}

		static EndpointResponse from(Endpoint endpoint, String secret) {
			return new EndpointResponse(endpoint.getId(), endpoint.getUrl(), endpoint.getDescription(),
					endpoint.getEventTypes(), endpoint.isActive(), endpoint.getConsecutiveFailures(),
					endpoint.getFailingSince(), endpoint.getDisabledReason(), endpoint.getDisabledAt(),
					endpoint.getCreatedAt(), endpoint.getUpdatedAt(), secret);
		}

	}

	private final EndpointService service;

	EndpointController(EndpointService service) {
		this.service = service;
	}

	@PostMapping
	@Operation(summary = "Register an endpoint",
			description = "The signing secret is only returned in this response. Store it now.")
	@ApiResponse(responseCode = "201", description = "Endpoint registered")
	ResponseEntity<EndpointResponse> create(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@Valid @RequestBody CreateEndpointRequest request) {
		CreatedEndpoint created = service.create(tenant.tenantId(), request.url(), request.description(),
				request.eventTypes());
		Endpoint endpoint = created.endpoint();
		return ResponseEntity.created(URI.create("/v1/endpoints/" + endpoint.getId()))
			.cacheControl(CacheControl.noStore())
			.body(EndpointResponse.from(endpoint, created.secret()));
	}

	@GetMapping
	@Operation(summary = "List endpoints, newest first")
	PageResponse<EndpointResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
		PageResponse<Endpoint> page = service.list(tenant.tenantId(), limit, cursor);
		return new PageResponse<>(page.data().stream().map(EndpointResponse::from).toList(), page.nextCursor());
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get an endpoint")
	EndpointResponse get(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@PathVariable UUID id) {
		return EndpointResponse.from(service.get(tenant.tenantId(), id));
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Update an endpoint's URL, description or subscribed event types")
	EndpointResponse update(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@PathVariable UUID id, @Valid @RequestBody UpdateEndpointRequest request) {
		return EndpointResponse.from(service.update(tenant.tenantId(), id, request.url(), request.description(),
				request.eventTypes()));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete an endpoint", description = "Pending deliveries to it are cancelled.")
	@ApiResponse(responseCode = "204", description = "Endpoint deleted")
	ResponseEntity<Void> delete(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@PathVariable UUID id) {
		service.delete(tenant.tenantId(), id);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}

}
