# webhook-delivery-platform

A service that accepts events from other applications and delivers them to the HTTP endpoints its
customers register. The point of the project is what happens when things go wrong: receivers that
are down, slow, or hostile.

**Status: under construction.** Events are accepted, signed, delivered with retries, and the main ways a
receiver can misbehave are handled. Metrics, dashboards and load scripts exist and the first figures are
measured (see Performance). It runs on a local Kubernetes cluster from a Helm chart, with the network-level
defence of [adr/0003-threat-model.md](adr/0003-threat-model.md) (a NetworkPolicy on the worker) checked there. It has not
been deployed to a cloud yet (the Terraform for that is written, not applied), so do not expose it to the internet.

## What works today

- Multi-tenant API authenticated with hashed API keys (`Authorization: Bearer whk_...`).
- Endpoint management. Each endpoint gets a signing secret, shown once and stored encrypted
  (AES-256-GCM).
- `POST /v1/events` stores the event and creates one delivery per subscribed endpoint in a single
  transaction. With an `Idempotency-Key`, concurrent or repeated requests produce exactly one event.
- Cursor-paginated queries for events, deliveries and attempts. Tenants cannot see each other's data.
- Errors as RFC 9457 problem documents. OpenAPI docs at `/swagger-ui/index.html`.
- Delivery. A worker moves due deliveries from PostgreSQL to a queue (SQS; ElasticMQ locally) and
  consumers send each one as a `POST` signed with HMAC-SHA256 following
  [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md).
  A failed attempt is retried up to 10 times over about 47 hours, with jitter, honoring
  `Retry-After`. Every attempt is recorded and can be queried.
- Reliability. A receiver that keeps failing is switched off by a per-endpoint circuit breaker
  (`POST /v1/endpoints/{id}/enable` brings it back), and one that answers `410 Gone` is switched off at
  once. A finished delivery can be sent again with `POST /v1/deliveries/{id}/replay`, which creates a new
  delivery and leaves the original's history alone. A slow endpoint cannot take every delivery slot of a
  worker.
- Security. The worker refuses to connect to private, loopback, link-local and other reserved addresses,
  checked at the moment of connecting so DNS rebinding does not work, and it never follows redirects.
  Endpoint secrets are encrypted at rest under a versioned key. Keys and secrets are never logged. See
  [adr/0003-threat-model.md](adr/0003-threat-model.md), which also says what is not covered.
- Publishing is rate limited per tenant with a token bucket shared by all API instances (`429` with
  `Retry-After`). What that costs is in [adr/0002-rate-limiting.md](adr/0002-rate-limiting.md).
- The guarantee is **at least once**: an event may arrive twice, never zero times if it could be
  delivered. See [adr/0001-at-least-once-delivery.md](adr/0001-at-least-once-delivery.md).

## Stack

Java 25, Spring Boot 4.1, PostgreSQL 18, Flyway, Maven, Amazon SQS through the AWS SDK for Java v2.
Tests run against a real PostgreSQL 18 and a real SQS-compatible server (ElasticMQ) with
Testcontainers.

One jar, two roles. The `api` role serves the HTTP API. The `worker` role (`worker` Spring profile)
runs the dispatcher, the queue consumers and the reaper, and scales separately.

## Run it locally

Requires Docker, and a JDK 25 if you want to run the tests or work on the code.

### The whole stack

```powershell
docker compose up --build -d
```

This starts PostgreSQL, ElasticMQ (a local SQS), the API on port 8081, a worker, and a WireMock
receiver on port 8090 that stands in for a customer's server. Create a tenant with the operator
token, register the receiver as an endpoint, and publish an event with the API key it returned:

```powershell
curl.exe -X POST http://localhost:8081/admin/tenants -H "Authorization: Bearer dev-admin-token" `
  -H "Content-Type: application/json" -d '{\"name\":\"Acme\"}'

curl.exe -X POST http://localhost:8081/v1/endpoints -H "Authorization: Bearer <api key>" `
  -H "Content-Type: application/json" `
  -d '{\"url\":\"http://receiver:8080/ok\",\"event_types\":[\"order.paid\"]}'

curl.exe -X POST http://localhost:8081/v1/events -H "Authorization: Bearer <api key>" `
  -H "Content-Type: application/json" `
  -d '{\"type\":\"order.paid\",\"data\":{\"order_id\":\"A-1001\"}}'
```

Look at what the receiver got, signature headers included, at
http://localhost:8090/__admin/requests, and at the delivery with
`GET /v1/deliveries?event_id=<event id>`. Point an endpoint at `http://receiver:8080/fail500` to watch
retries: the delivery goes back to `pending` with the date of the next attempt.

The secrets in `compose.yaml` are public throwaways for local use. The same file turns the
destination guard off (`WEBHOOKS_ENDPOINTS_ALLOW_PRIVATE_DESTINATIONS`), because the receiver it
starts has a private address; never set that in a real deployment.

### Working on the code

Start only the dependencies and run the application from Maven. Both roles in one process:

```powershell
docker compose up -d --wait postgres elasticmq
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev,worker"
```

The `dev` profile serves on port 8081 and points at the local PostgreSQL and ElasticMQ. Use `dev`
alone to run only the API.

Configuration for anything other than local development is in `.env.example`.

## Receiving webhooks

A receiver that wants to trust what it gets should, in this order:

1. Compute HMAC-SHA256 over `webhook-id`, `.`, `webhook-timestamp`, `.` and the **raw request bytes**,
   with the endpoint's secret (the part after `whsec_`, base64-decoded), and compare it in constant
   time with the `v1,` signature in `webhook-signature`.
2. Reject the request if `webhook-timestamp` is more than 5 minutes from its own clock.
3. Deduplicate by `webhook-id`, remembering ids for at least 48 hours. Every attempt of an event
   carries the same id, and a retry can arrive hours later with a fresh timestamp, so step 2 does not
   protect against duplicates.
4. Answer `2xx` once the event is safely stored; anything else is treated as a failure and retried.

## Observability

`docker compose up` also starts Prometheus (<http://localhost:9090>) and Grafana (<http://localhost:3000>, no
login). The dashboard in [observability/](observability/grafana/dashboards/webhooks.json) shows publish and
delivery rates, attempt duration, failures by cause, the backlog (how many deliveries are due and how long the oldest
has waited, read from PostgreSQL), the queue depth, the database connection pool, CPU and heap.

Metric labels are few and bounded on purpose (`outcome`, `error_kind`, `reason`): a tenant or endpoint id as a label
would create a time series per id. A test fails if one appears. The scrape path `/actuator/prometheus` answers only
to the operator token.

![The dashboard during a k6 run: 240 events per second published and delivered to a slow receiver, about 300 deliveries in flight](observability/dashboard.png)

The dashboard during a k6 run of 240 events per second against a slow receiver (median 800 ms), with the worker
allowed 1,000 deliveries in flight. k6, the application and the receiver shared one machine.

Load scenarios with k6 live in [load/](load/README.md).

## Tests

```powershell
.\mvnw.cmd verify
```

The rate limiter benchmark behind ADR 2 is not part of `verify`; run it with
`.\mvnw.cmd test "-Dtest=RateLimiterBenchmark" "-Dbenchmark=true"`.

## Performance

Measured with k6 on one machine that also ran the application, the database and the receiver: AMD Ryzen 7 5700X
(8 cores), 31.9 GB, Windows 11, Docker Desktop. That makes every absolute figure a lower bound. Three runs per row,
range shown, 2026-10-08, commit `25b907e`, with one exception noted in the setup of load/RESULTS.md. Every run,
the setup and the caveats are in [load/RESULTS.md](load/RESULTS.md).

| Scenario | Metric | Result (3 runs) |
|---|---|---|
| Sustained publishing, healthy receiver | Highest rate tried with publish p95 under 200 ms | 400 events/s (p95 9.5 to 11.0 ms). At 800/s the PostgreSQL rate limiter fails (p95 1.4 to 1.5 s) |
| Same, rate limiter in memory | Same | 800 events/s (p95 5.6 to 20.4 ms) |
| Rate limiter in PostgreSQL against memory | Added mean publish latency at 400/s | about 2 ms (5.4 to 5.7 ms against 2.9 to 3.7 ms) |
| Publish to first attempt, 250 events/s | p50 / p95 / p99 | 0.57 to 0.59 s / 1.00 s / 1.03 to 1.05 s (dominated by the 1 s dispatcher tick) |
| Slow receiver (median 800 ms), 50 events/s | Deliveries per second before the consumer fix ([ADR 4](adr/0004-consumer-concurrency.md)) | 12.8 to 13.1 (drain 253 to 263 s after publishing) |
| Same, after the fix | Deliveries per second | 47.1 to 47.9 (drain 5 to 8 s) |
| Failure storm to `/fail500`, breaker shortened to 30 s | Time until every endpoint is switched off | 33 to 35 s after the start, 20 of 20 endpoints |
| Virtual against platform threads, about 300 deliveries in flight | Deliveries per second | 226.9 to 230.2 against 229.3 to 231.2; worker threads 36 against about 1,036 |
| Container image, on disk (`docker images`, 2026-10-09) | Size | 654 MB: the JRE base image is 490 MB, the application jar and curl for the healthcheck make up the rest |
| Rolling update of the worker under load, local Kubernetes (kind), 15 events/s for 120 s, 5 s receiver | Events lost / duplicated | 0 / 0 in 3 runs (1,800 to 1,801 events each). The same update with the shutdown grace cut to 1 s: 0 lost, 45 duplicated |
| Rolling update of the API under the same load | Events lost, failed publishes | 0 and 0 of about 1,822 requests in 3 runs |
| Image start time on kind | Container start to Ready | median 20 s (19 to 20 s, 12 pods), probe periods included |
| Clean clone to first delivery | | To be measured |

The three Kubernetes rows come from a different setup (2026-10-10, a single-node kind cluster on the same machine, k6 inside
the cluster, one commit later than the rows above); [load/RESULTS.md](load/RESULTS.md) section f has the runs and the caveats.

## Running on Kubernetes

A Helm chart in [deploy/helm/webhook-platform/](deploy/helm/webhook-platform/) deploys the API and the worker from the
one image, with probes, resource limits, a PodDisruptionBudget, a ServiceMonitor and a NetworkPolicy that stops the worker
reaching private address ranges. [deploy/local/](deploy/local/) runs it on `kind` with PostgreSQL and an SQS-compatible
queue, and rehearses a rolling update under load. The AWS version (EKS, RDS, Pod Identity) is Terraform in
[infra/](infra/); it is written and validated but has not been applied yet. There is no domain and no load balancer in
the plan: the demo is reached with `kubectl port-forward`.
