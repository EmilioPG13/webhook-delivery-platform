-- One row per tenant holding the serialized token bucket of its publish rate limit. The column names and the
-- bytea state are what Bucket4j's PostgreSQL proxy manager expects.
CREATE TABLE rate_limit_buckets (
    tenant_id  uuid  PRIMARY KEY REFERENCES tenants (id),
    state      bytea
);
