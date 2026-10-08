package io.github.emiliopg13.webhooks.event;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.emiliopg13.webhooks.common.ApiException;
import io.github.emiliopg13.webhooks.common.ApiException.FieldViolation;
import io.github.emiliopg13.webhooks.common.PageResponse;
import io.github.emiliopg13.webhooks.common.Paging;
import io.github.emiliopg13.webhooks.event.EventPublishService.PublishResult;
import io.github.emiliopg13.webhooks.event.EventRepository.Stored;
import io.github.emiliopg13.webhooks.tenant.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/v1/events")
@Tag(name = "Events")
class EventController {

	static final int MAX_BODY_BYTES = 256 * 1024;

	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

	record PublishEventRequest(@NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String type,
			@NotNull JsonNode data) {
	}

	record PublishEventResponse(UUID id, String type, Instant createdAt, int deliveries) {
	}

	record EventSummary(UUID id, String type, Instant createdAt) {
	}

	record EventDetail(UUID id, String type, Instant createdAt, JsonNode data) {
	}

	private final EventPublishService publishService;

	private final EventRepository events;

	private final JsonMapper jsonMapper;

	private final Validator validator;

	EventController(EventPublishService publishService, EventRepository events, JsonMapper jsonMapper,
			Validator validator) {
		this.publishService = publishService;
		this.events = events;
		this.jsonMapper = jsonMapper;
		this.validator = validator;
	}

	/**
	 * Takes the raw bytes instead of a parsed object because the idempotency check compares a hash of what the
	 * client actually sent, not of a re-serialized copy.
	 */
	@PostMapping
	@Operation(summary = "Publish an event",
			description = "Stores the event and creates one delivery per subscribed endpoint, then returns 202. "
					+ "Nothing is sent before the response. With an Idempotency-Key, repeating the same request "
					+ "returns the original result (header Idempotent-Replayed: true); the same key with a "
					+ "different body is rejected with 422.",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(schema = @Schema(implementation = PublishEventRequest.class))))
	@ApiResponse(responseCode = "202", description = "Event accepted")
	ResponseEntity<PublishEventResponse> publish(
			@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = true) byte[] body) {
		if (body.length > MAX_BODY_BYTES) {
			throw ApiException.payloadTooLarge("Request body must be at most " + MAX_BODY_BYTES + " bytes.");
		}
		if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH)) {
			throw ApiException.invalidField("Idempotency-Key",
					"must be between 1 and " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
		}
		PublishEventRequest request = parse(body);
		PublishResult result = publishService.publish(tenant.tenantId(), idempotencyKey, request.type(),
				jsonMapper.writeValueAsString(request.data()), sha256(body));

		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED)
			.location(URI.create("/v1/events/" + result.id()));
		if (result.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.body(new PublishEventResponse(result.id(), result.type(), result.createdAt(),
				result.deliveries()));
	}

	@GetMapping
	@Operation(summary = "List events, newest first")
	PageResponse<EventSummary> list(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
		int pageSize = Paging.limit(limit);
		List<Stored> fetched = events.list(tenant.tenantId(), Paging.cursor(cursor), pageSize + 1);
		PageResponse<Stored> page = Paging.page(fetched, pageSize, Stored::id);
		return new PageResponse<>(
				page.data().stream().map(event -> new EventSummary(event.id(), event.type(), event.createdAt())).toList(),
				page.nextCursor());
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get an event with its payload")
	EventDetail get(@Parameter(hidden = true) @AuthenticationPrincipal TenantPrincipal tenant,
			@PathVariable UUID id) {
		Stored event = events.findById(tenant.tenantId(), id).orElseThrow(() -> ApiException.notFound("Event"));
		return new EventDetail(event.id(), event.type(), event.createdAt(), jsonMapper.readTree(event.payload()));
	}

	private PublishEventRequest parse(byte[] body) {
		PublishEventRequest request;
		try {
			request = jsonMapper.readValue(body, PublishEventRequest.class);
		}
		catch (JacksonException ex) {
			throw ApiException.badRequest("Request body is not valid JSON.");
		}
		if (request == null) {
			throw ApiException.badRequest("Request body is required.");
		}
		List<FieldViolation> violations = validator.validate(request)
			.stream()
			.map(violation -> new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
			.collect(Collectors.toCollection(ArrayList::new));
		if (request.data() != null && !request.data().isObject()) {
			violations.add(new FieldViolation("data", "must be a JSON object"));
		}
		if (!violations.isEmpty()) {
			throw ApiException.invalid(violations);
		}
		return request;
	}

	private static byte[] sha256(byte[] body) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(body);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
		}
	}

}
