package io.github.emiliopg13.webhooks.tenant;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A customer of the platform. The id is assigned by the application, so the entity implements
 * {@link Persistable}: without it Spring Data would treat a non-null id as "already exists" and issue a
 * SELECT before every insert.
 */
@Entity
@Table(name = "tenants")
public class Tenant implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "name")
	private String name;

	@Column(name = "publish_rate_per_min")
	private int publishRatePerMin;

	@Column(name = "created_at")
	private Instant createdAt;

	@Transient
	private boolean isNew = true;

	protected Tenant() {
	}

	public Tenant(UUID id, String name, int publishRatePerMin, Instant createdAt) {
		this.id = id;
		this.name = name;
		this.publishRatePerMin = publishRatePerMin;
		this.createdAt = createdAt;
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

	public String getName() {
		return name;
	}

	public int getPublishRatePerMin() {
		return publishRatePerMin;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
