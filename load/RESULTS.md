# Load test results

Every figure below comes from `load/run.ps1`; the raw output of each run is in [results/](results/). Three runs per
scenario, one value per run, in run order.

## Setup, for every run

- **Machine:** AMD Ryzen 7 5700X (8 cores, 16 threads), 31.9 GB RAM, Windows 11 Pro. Docker Desktop 29.5.3 with all
  16 CPUs and about 15.6 GiB visible to the containers.
- **Everything shares that machine:** k6, the API, the worker, PostgreSQL 18, ElasticMQ, the WireMock receiver,
  Prometheus and Grafana. The load generator competes with the application for CPU, so absolute figures are
  lower bounds, and the comparison between two configurations is the part to trust.
- **Code:** commit `25b907e`, 2026-10-08. The runs were made on that tree before it was committed, except the
  `c-before-fix` runs, which used the consumer loop of commit `a96b869` (copied back in for the comparison). Settings are the application's defaults unless a scenario says otherwise: Java 25, virtual threads,
  2 consumer loops, 50 deliveries in flight, 5 per endpoint, 1 s dispatch interval, database pool of 10 per role,
  rate limiter in PostgreSQL.
- **Workload:** one tenant (the worst case for the rate limiter, which keeps one row per tenant), events spread over
  20 endpoints (100 in scenario e), one delivery per event.
- **Publish threshold**, set before the first measurement: p95 of `POST /v1/events` under 200 ms and under 1% failed
  requests. A 429 counts as a failure.
- **Receiver:** WireMock with 1,000 container threads. `/ok` answers at once. `/slow` answers after a lognormal delay
  with a median of 800 ms (mean about 870 ms). `/fail500` always answers 500.

### Two earlier rounds were discarded

The first runs used a receiver with WireMock's default 10 threads and verbose logging. Once the platform sent more
requests in parallel, the receiver became the limit, and the figures described the receiver, not the platform. Those
runs, and everything measured with them, were thrown away and repeated after the receiver was fixed and shown to answer
100 parallel slow requests without queueing. The lesson: check that the instrument can do what the test asks before
drawing conclusions from what it reports.

## a. Sustained publishing, healthy receiver (rate limiter in PostgreSQL)

250 events per second for 90 s, `/ok`.

| Run | Publish p95 | Accepted/s | Publish to first attempt p50 / p95 / p99 | Delivered |
|---|---|---|---|---|
| 1 | 8.9 ms | 250.0 | 0.588 s / 1.005 s / 1.051 s | 22,493 of 22,493 |
| 2 | 7.4 ms | 250.1 | 0.572 s / 0.997 s / 1.035 s | 22,500 of 22,500 |
| 3 | 7.3 ms | 250.1 | 0.567 s / 0.995 s / 1.034 s | 22,501 of 22,501 |

The p95 of about 1 s is the dispatcher's one-second tick, not a queueing delay.

## b. Publishing capacity and the cost of the rate limiter

Same workload at higher rates, with the limiter in PostgreSQL (the default) and in memory
(`webhooks.rate-limit.store=memory`), 60 s per run.

| Rate | Limiter | Publish p95 per run | Publish mean per run | Accepted/s per run |
|---|---|---|---|---|
| 400/s | PostgreSQL | 11.0, 10.0, 9.5 ms | 5.7, 5.4, 5.4 ms | 400, 400, 400 |
| 400/s | memory | 8.1, 5.9, 4.8 ms | 3.7, 3.5, 2.9 ms | 398, 400, 400 |
| 800/s | PostgreSQL | **1.53, 1.41, 1.40 s** | 939, 884, 877 ms | **516, 546, 549** |
| 800/s | memory | 18.4, 20.4, 5.6 ms | 7.2, 5.6, 3.6 ms | 795, 799, 799 |

- At 400/s both pass; PostgreSQL costs about 2 ms of mean latency per publish, consistent with
  [adr/0002](../adr/0002-rate-limiting.md).
- At 800/s PostgreSQL does not hold: p95 above 1.4 s, between 14,700 and 16,600 iterations dropped per run, and the API's pool had 490
  threads waiting for a connection (pending 490 in all three runs). In memory the same load passes.
- The wait is on the tenant's bucket row (`SELECT ... FOR UPDATE`), seen in `pg_stat_activity` during a run. With many
  tenants the rows differ and this ceiling does not apply; it is the cost of one hot tenant.
- The knee is between 400 and about 540 per second; it was not located more precisely.

## c. Slow receiver: the bottleneck that was fixed

50 events per second for 90 s to `/slow`. The only difference between the two sets is the consumer loop
([adr/0004](../adr/0004-consumer-concurrency.md)).

| | Run | Deliveries/s | Drain after publishing | Publish to first attempt p50 / p99 | Peak in flight / waiting in queue |
|---|---|---|---|---|---|
| Before | 1 | 13.1 | 253 s | 120 s / 249 s | 20 / 3,590 |
| Before | 2 | 12.9 | 261 s | 130 s / 253 s | 20 / 3,763 |
| Before | 3 | 12.8 | 263 s | 129 s / 297 s | 20 / 3,529 |
| After | 1 | 47.9 | 5 s | 0.66 s / 3.65 s | 50 / 24 |
| After | 2 | 47.7 | 5 s | 0.65 s / 3.67 s | 50 / 21 |
| After | 3 | 47.1 | 8 s | 0.67 s / 3.69 s | 50 / 22 |

Before the change the worker never had more than 20 deliveries in flight (2 loops of 10), with its CPU at 3% to 7%
and its pool idle. Worker pool after the change: 1 to 10 connections active, at most 4 waiting.

## d. Failure storm until the breaker trips

50 events per second for 60 s to `/fail500`, breaker shortened to 5 failures over at least 30 s
(`BREAKER_FAILURE_THRESHOLD=5`, `BREAKER_FAILING_FOR=30s`; the default is 50 over 24 h). 20 endpoints.

| Run | Endpoints switched off | Time after the start | Deliveries created of 3,000 events |
|---|---|---|---|
| 1 | 20 of 20 | 33 s to 35 s | 1,579 |
| 2 | 20 of 20 | 34 s to 35 s | 1,576 |
| 3 | 20 of 20 | 33 s to 34 s | 1,574 |

All endpoints are switched off about 3 s after the 30 s the breaker was told to wait. From then on, publishing
creates no deliveries for them, so the storm stops growing; the deliveries already created stay pending for the owner
to re-enable the endpoint. No delivery succeeded, as expected. Publish p95 stayed under 10 ms.

## e. Virtual threads against platform threads

240 events per second for 90 s to `/slow` over 100 endpoints, with `max-concurrent-deliveries=1000` so the thread model,
not the limit, is the ceiling. "Platform" is a fixed pool of 1,000 platform threads.

| | Run | Deliveries/s | Publish to first attempt p50 / p99 | Peak in flight | Worker threads | Worker heap | Worker pool waiting |
|---|---|---|---|---|---|---|---|
| Virtual | 1 | 229.1 | 0.65 s / 4.93 s | 332 | 36 | 74 MiB | 34 |
| Virtual | 2 | 230.2 | 0.63 s / 4.90 s | 305 | 36 | 72 MiB | 57 |
| Virtual | 3 | 226.9 | 0.62 s / 4.82 s | 304 | 36 | 70 MiB | 53 |
| Platform | 1 | 229.3 | 0.61 s / 4.65 s | 306 | 1,035 | 79 MiB | 22 |
| Platform | 2 | 229.3 | 0.60 s / 4.62 s | 334 | 1,037 | 77 MiB | 8 |
| Platform | 3 | 231.2 | 0.60 s / 4.60 s | 307 | 1,037 | 78 MiB | 13 |

- **Throughput and latency are the same.** At about 300 deliveries in flight, 1,000 platform threads are enough.
- The difference is the thread count: 36 against about 1,036. Heap is within 10%. Worker CPU peaks (17, 6, 7% against
  11, 4, 3%) are too noisy to rank.
- More threads waiting for a database connection with virtual threads (34 to 57 against 8 to 22): when waiting is
  cheap, more deliveries reach the 10-connection pool at once. It did not cost throughput here.
- This does not show where platform threads give out. That needs thousands of deliveries in flight, which this
  workload does not reach.

## Not measured yet

Receiver down for 10 minutes and back (Phase 4 scenario list does not include it), rolling update of the worker
(Phase 6), image start time (Phase 5), clean clone to first delivery (Phase 7).
