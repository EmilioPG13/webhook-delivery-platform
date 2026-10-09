-- What a load run did, read back from PostgreSQL for the most recent k6 tenant.
--
--   Get-Content load/report.sql | docker compose exec -T postgres psql -U webhooks -d webhooks
--
-- The lag is from the event being stored to the first attempt starting: the dispatcher tick, the queue trip and any
-- wait for a free worker slot. The database clock produced both ends, so there is no clock skew in the number.
WITH run AS (
    SELECT id FROM tenants WHERE name LIKE 'k6-%' ORDER BY created_at DESC LIMIT 1
),
first_attempt AS (
    SELECT d.status,
           extract(epoch FROM (a.started_at - e.created_at)) AS lag_s,
           a.started_at + a.duration_ms * interval '1 millisecond' AS finished_at,
           e.created_at AS published_at
    FROM deliveries d
    JOIN events e ON e.id = d.event_id
    LEFT JOIN delivery_attempts a ON a.delivery_id = d.id AND a.attempt_number = 1
    WHERE d.tenant_id = (SELECT id FROM run)
)
SELECT count(*)                                             AS deliveries,
       count(*) FILTER (WHERE status = 'delivered')         AS delivered,
       count(*) FILTER (WHERE status IN ('pending', 'enqueued')) AS still_waiting,
       round((percentile_cont(0.50) WITHIN GROUP (ORDER BY lag_s))::numeric, 3) AS lag_p50_s,
       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY lag_s))::numeric, 3) AS lag_p95_s,
       round((percentile_cont(0.99) WITHIN GROUP (ORDER BY lag_s))::numeric, 3) AS lag_p99_s,
       round(extract(epoch FROM (max(finished_at) - min(published_at)))::numeric, 1) AS first_publish_to_last_attempt_s,
       round((count(*) FILTER (WHERE status = 'delivered')
              / nullif(extract(epoch FROM (max(finished_at) - min(published_at))), 0))::numeric, 1) AS delivered_per_s
FROM first_attempt;
