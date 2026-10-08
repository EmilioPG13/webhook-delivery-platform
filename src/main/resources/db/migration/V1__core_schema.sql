CREATE TABLE tenants (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    name                  text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    publish_rate_per_min  integer     NOT NULL DEFAULT 600 CHECK (publish_rate_per_min > 0),
    created_at            timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    prefix        text        NOT NULL,  -- visible part, e.g. "whk_a1b2c3d4"
    key_hash      bytea       NOT NULL,  -- SHA-256 of the full key
    status        text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    last_used_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz
);
CREATE UNIQUE INDEX api_keys_hash_uq   ON api_keys (key_hash);
CREATE INDEX        api_keys_tenant_ix ON api_keys (tenant_id);

CREATE TABLE endpoints (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id             uuid        NOT NULL REFERENCES tenants (id),
    url                   text        NOT NULL CHECK (length(url) <= 2048),
    description           text,
    event_types           text[]      NOT NULL CHECK (cardinality(event_types) > 0),  -- '{*}' = all
    secret_ciphertext     bytea       NOT NULL,
    secret_key_version    integer     NOT NULL,
    active                boolean     NOT NULL DEFAULT true,
    consecutive_failures  integer     NOT NULL DEFAULT 0,
    failing_since         timestamptz,
    disabled_reason       text,
    disabled_at           timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX endpoints_tenant_active_ix ON endpoints (tenant_id) WHERE active;

CREATE TABLE events (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    event_type       text        NOT NULL,
    payload          jsonb       NOT NULL,
    idempotency_key  text        CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    request_hash     bytea       NOT NULL,  -- SHA-256 of the received body
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT events_idempotency_uq UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX events_tenant_ix ON events (tenant_id, id DESC);

CREATE TABLE deliveries (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL REFERENCES tenants (id),
    event_id          uuid        NOT NULL REFERENCES events (id),
    endpoint_id       uuid        NOT NULL REFERENCES endpoints (id),
    replay_of         uuid        REFERENCES deliveries (id),
    status            text        NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending', 'enqueued', 'delivered', 'failed', 'cancelled')),
    attempt_count     integer     NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz NOT NULL DEFAULT now(),
    enqueued_at       timestamptz,
    lease_until       timestamptz,
    last_status_code  integer,
    last_error_kind   text,
    delivered_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX deliveries_due_ix      ON deliveries (next_attempt_at) WHERE status = 'pending';
CREATE INDEX deliveries_enqueued_ix ON deliveries (enqueued_at)     WHERE status = 'enqueued';
CREATE INDEX deliveries_event_ix    ON deliveries (event_id);
CREATE INDEX deliveries_endpoint_ix ON deliveries (endpoint_id, id DESC);
CREATE INDEX deliveries_tenant_ix   ON deliveries (tenant_id, id DESC);

CREATE TABLE delivery_attempts (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    delivery_id       uuid        NOT NULL REFERENCES deliveries (id),
    attempt_number    integer     NOT NULL,
    started_at        timestamptz NOT NULL,
    duration_ms       integer     NOT NULL,
    response_status   integer,
    error_kind        text,  -- timeout, connect, tls, blocked_destination, http_status, unexpected
    error_message     text,
    response_snippet  text,  -- first 1024 bytes; foreign content, never rendered as HTML
    CONSTRAINT delivery_attempts_uq UNIQUE (delivery_id, attempt_number)
);
