package io.github.emiliopg13.webhooks.tenant;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.emiliopg13.webhooks.tenant.ApiKeyService.IssuedApiKey;
import io.github.emiliopg13.webhooks.tenant.TenantAdminService.CreatedTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Operator console: bootstraps tenants and manages their API keys. Authenticated with the admin token. */
@RestController
@Tag(name = "Admin")
class AdminTenantController {

	record CreateTenantRequest(@NotBlank @Size(max = 200) String name, @Min(1) Integer publishRatePerMin) {
	}

	record ApiKeyResponse(UUID id, String prefix, String key) {

		static ApiKeyResponse from(IssuedApiKey issued) {
			return new ApiKeyResponse(issued.id(), issued.prefix(), issued.key());
		}

	}

	record TenantCreatedResponse(UUID id, String name, int publishRatePerMin, Instant createdAt,
			ApiKeyResponse apiKey) {
	}

	private final TenantAdminService service;

	AdminTenantController(TenantAdminService service) {
		this.service = service;
	}

	@PostMapping("/admin/tenants")
	@Operation(summary = "Create a tenant and its first API key",
			description = "The full API key is only returned in this response.")
	@ApiResponse(responseCode = "201", description = "Tenant created")
	ResponseEntity<TenantCreatedResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
		CreatedTenant created = service.createTenant(request.name(), request.publishRatePerMin());
		Tenant tenant = created.tenant();
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(new TenantCreatedResponse(tenant.getId(), tenant.getName(), tenant.getPublishRatePerMin(),
					tenant.getCreatedAt(), ApiKeyResponse.from(created.apiKey())));
	}

	@PostMapping("/admin/tenants/{tenantId}/api-keys")
	@Operation(summary = "Issue another API key for a tenant",
			description = "The full API key is only returned in this response.")
	@ApiResponse(responseCode = "201", description = "API key issued")
	ResponseEntity<ApiKeyResponse> issueApiKey(@PathVariable UUID tenantId) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiKeyResponse.from(service.issueApiKey(tenantId)));
	}

	@DeleteMapping("/admin/api-keys/{apiKeyId}")
	@Operation(summary = "Revoke an API key")
	@ApiResponse(responseCode = "204", description = "API key revoked")
	ResponseEntity<Void> revokeApiKey(@PathVariable UUID apiKeyId) {
		service.revokeApiKey(apiKeyId);
		return ResponseEntity.noContent().build();
	}

}
