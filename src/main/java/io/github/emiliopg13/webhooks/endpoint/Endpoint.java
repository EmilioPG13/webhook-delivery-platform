package io.github.emiliopg13.webhooks.endpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A customer-registered destination. Deleting is soft: deliveries keep a foreign key to the row, so it stays
 * with {@code disabledReason = "deleted"} and the API treats it as gone.
 *
 * <p>{@code @DynamicUpdate} matters: the worker changes the failure counters and {@code active} with plain SQL while
 * this entity may be loaded, and a default Hibernate UPDATE writes every column, which would put stale counters back.
 */
@Entity
@DynamicUpdate
@Table(name = "endpoints")
public class Endpoint implements Persistable<UUID> {

	static final String DELETED = "deleted";

	@Id
	private UUID id;

	@Column(name = "tenant_id")
	private UUID tenantId;

	@Column(name = "url")
	private String url;

	@Column(name = "description")
	private String description;

	@Column(name = "event_types")
	@JdbcTypeCode(SqlTypes.ARRAY)
	private List<String> eventTypes;

	@Column(name = "secret_ciphertext")
	private byte[] secretCiphertext;

	@Column(name = "secret_key_version")
	private int secretKeyVersion;

	@Column(name = "active")
	private boolean active;

	@Column(name = "consecutive_failures")
	private int consecutiveFailures;

	@Column(name = "failing_since")
	private Instant failingSince;

	@Column(name = "disabled_reason")
	private String disabledReason;

	@Column(name = "disabled_at")
	private Instant disabledAt;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@Transient
	private boolean isNew = true;

	protected Endpoint() {
	}

	Endpoint(UUID id, UUID tenantId, String url, String description, List<String> eventTypes,
			byte[] secretCiphertext, int secretKeyVersion, Instant now) {
		this.id = id;
		this.tenantId = tenantId;
		this.url = url;
		this.description = description;
		this.eventTypes = List.copyOf(eventTypes);
		this.secretCiphertext = secretCiphertext;
		this.secretKeyVersion = secretKeyVersion;
		this.active = true;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void update(String url, String description, List<String> eventTypes, Instant now) {
		if (url != null) {
			this.url = url;
		}
		if (description != null) {
			this.description = description.isBlank() ? null : description;
		}
		if (eventTypes != null) {
			this.eventTypes = List.copyOf(eventTypes);
		}
		this.updatedAt = now;
	}

	/** Switches the endpoint back on with a clean slate, so the breaker does not trip again on the old streak. */
	void enable(Instant now) {
		this.active = true;
		this.consecutiveFailures = 0;
		this.failingSince = null;
		this.disabledReason = null;
		this.disabledAt = null;
		this.updatedAt = now;
	}

	void markDeleted(Instant now) {
		this.active = false;
		this.disabledReason = DELETED;
		this.disabledAt = now;
		this.updatedAt = now;
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		this.isNew = false;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getUrl() {
		return url;
	}

	public String getDescription() {
		return description;
	}

	public List<String> getEventTypes() {
		return eventTypes;
	}

	public byte[] getSecretCiphertext() {
		return secretCiphertext;
	}

	public int getSecretKeyVersion() {
		return secretKeyVersion;
	}

	public boolean isActive() {
		return active;
	}

	public int getConsecutiveFailures() {
		return consecutiveFailures;
	}

	public Instant getFailingSince() {
		return failingSince;
	}

	public String getDisabledReason() {
		return disabledReason;
	}

	public Instant getDisabledAt() {
		return disabledAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
