# webhook-delivery-platform

A service that accepts events from other applications and delivers them to the HTTP endpoints its
customers register. The point of the project is what happens when things go wrong: receivers that
are down, slow, or hostile.

**Status: under construction.** Events are accepted, signed, delivered with retries, and the main ways a
receiver can misbehave are handled. Not yet deployed anywhere or load tested; the network-level
defences described in [adr/0003-threat-model.md](adr/0003-threat-model.md) do not exist yet, so do not
expose it to the internet.

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

## Tests

```powershell
.\mvnw.cmd verify
```

The rate limiter benchmark behind ADR 2 is not part of `verify`; run it with
`.\mvnw.cmd test "-Dtest=RateLimiterBenchmark" "-Dbenchmark=true"`.

## Performance

To be measured.
