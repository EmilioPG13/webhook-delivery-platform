# 2. Publish rate limiting: a token bucket per tenant, kept in PostgreSQL

Status: accepted

## Context

A tenant that publishes without limit can fill the delivery tables and starve everyone else, so
`POST /v1/events` is limited per tenant. The limit must hold when the API runs as several instances
behind a load balancer. A counter in each instance's memory does not: with N instances a tenant
publishes N times its rate. The platform already has PostgreSQL and deliberately has no Redis, so the
question is what a shared limit costs on PostgreSQL, and whether that cost is acceptable.

## Decision

Each tenant has a token bucket (Bucket4j 8.21.0) that holds `publish_rate_per_min` tokens and refills at
that rate. A publish takes one token. A tenant may therefore burst a full minute's worth at once and
then continues at the sustained rate. A rejected publish gets `429` with a `Retry-After` header holding
the whole seconds until a token is available; it is checked before the body is parsed and stores
nothing.

The bucket lives behind a `PublishRateLimiter` interface with two implementations, chosen by
`webhooks.rate-limit.store`:

- `postgres` (the default): Bucket4j's `PostgreSQLSelectForUpdateBasedProxyManager`, one row per tenant
  in `rate_limit_buckets`. Every API instance draws from the same bucket.
- `memory`: a `ConcurrentHashMap` of local buckets. Per instance, no database round trip.

Both read time from the injected `Clock`, so tests move it instead of waiting. The tenant's rate is read
by the same query that authenticates the API key, so deciding costs no extra read.

## What it costs

Measured with `RateLimiterBenchmark` (run it with `-Dbenchmark=true`; it asserts nothing). The rate is
set so high that nothing is rejected, so this is the cost of saying yes. Three runs per scenario, medians
shown, in one process on the same machine as PostgreSQL 18 in Docker: AMD Ryzen 7 5700X (8 cores, 16
threads), 32 GB, Windows 11, JDK 25.0.4, Hikari's default pool of 10 connections, commit `5579bf5` plus
the working tree of this change. k6 is not involved; there is no network between the benchmark and the
limiter.

| Scenario | memory decisions/s | memory p50 / p99 | postgres decisions/s | postgres p50 / p99 |
|---|---|---|---|---|
| 1 thread, 1 tenant | 8,178,093 | 0.1 us / 0.2 us | 566 | 1.7 ms / 2.2 ms |
| 32 threads, 1 tenant | 7,814,903 | 0.4 us / 3.7 us | 752 | 4.1 ms / 868 ms |
| 32 threads, 1000 tenants | 38,097,615 | 0.2 us / 0.5 us | 3,570 | 2.8 ms / 101 ms |

Run to run, no cell varies by more than 30% (the memory cells vary most, PostgreSQL's by under 10%). The
full table with every run is written by the benchmark to `target/rate-limiter-benchmark.md`.

What the numbers say:

- A decision in PostgreSQL costs about 1.7 ms, roughly four orders of magnitude more than in memory. It is
  a short transaction that writes (`SELECT ... FOR UPDATE`, then an `UPDATE`) and commits. I expect most
  of the time to be the commit's disk flush plus Docker's network hop, and a native PostgreSQL to be
  somewhat faster, but the measurement does not separate them.
- One hot tenant tops out near 750 decisions per second, because every decision takes the same row lock.
  The default quota is 600 per minute, 10 per second, so honest traffic is nowhere near that.
- With 32 callers sharing 10 connections, the tail grows to hundreds of milliseconds. The limiter shares
  the connection pool with the publish itself, which needs its own connection right afterwards.

## Consequences

- The default is `postgres` because the point of the limit is that it holds across instances, and a
  limit that multiplies with the replica count is not a limit. The price is about 1.7 ms on every
  publish, accepted at this scale.
- A rejected request also costs a database round trip. A tenant that floods the API with requests it
  knows will be rejected keeps the limiter busy at up to the rates above, and can crowd the pool. A
  small in-memory check in front of the shared bucket would absorb that, at the cost of a second layer
  to reason about. It is not built; if a load test (phase 4) shows the pool saturating under a flood, it
  is the first thing to add.
- `memory` is the right setting for a single API instance, and for tests that do not care. It is wrong
  for a fleet.
- A tenant's rate is applied when its bucket is first created. There is no API to change a rate yet; when
  there is, the bucket must be replaced (Bucket4j supports configuration replacement) or deleted.
- If PostgreSQL is unreachable the limiter throws and the publish fails with 500. Publishing needs the
  same database, so there is no state in which failing open would help.
- The `rate_limit_buckets` rows are never deleted: one small row per tenant that has ever published.

## Alternatives considered

- **Redis.** The natural home for shared counters, and orders of magnitude faster. Rejected for now:
  another stateful service to run, secure and pay for, to solve a problem at a scale (10 publishes per
  second per tenant) where PostgreSQL is enough. Bucket4j supports it with the same interface, so this
  stays an open door.
- **Advisory locks instead of `SELECT ... FOR UPDATE`** (Bucket4j's other PostgreSQL strategy). Not
  measured, so no claim either way. It keeps the same round trip and the same commit; I do not expect it
  to change the order of magnitude.
- **A fixed-window counter** (`UPDATE ... SET count = count + 1` per minute). Simpler and the same
  cost, but a tenant can double its rate across a window boundary, and it has no natural `Retry-After`.
- **In-memory only.** Fastest, and wrong behind a load balancer, as above.
- **Limiting in the load balancer or API gateway.** Valid in production, and it would take the cost out
  of the application. Rejected here because the limit is per tenant and the gateway does not know
  tenants; it also would not exist when running the project locally.
