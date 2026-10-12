# 9. Virtual threads and blocking code, not WebFlux

Status: accepted

## Context

Delivering a webhook is mostly waiting: for a receiver that answers in 800 ms, or times out after 15 seconds. A worker
needs hundreds of those waits open at once. The two ways to do that on the JVM are many cheap threads (virtual threads,
final in Java 21) and a non-blocking, reactive pipeline (WebFlux, R2DBC).

## Decision

Spring MVC, JDBC and Apache HttpClient's blocking API, all running on virtual threads
(`spring.threads.virtual.enabled`). Each delivery gets its own virtual thread and reads top to bottom. What limits the
work is not the thread model but explicit limits: `maxConcurrentDeliveries` per worker and
`maxConcurrentPerEndpoint`, so one slow receiver cannot take every slot ([ADR 4](0004-consumer-concurrency.md)).

A setting, `webhooks.worker.virtual-threads=false`, runs the same code on a fixed pool of platform threads. It exists
to measure the difference, not as a tuning knob.

## Measured

240 events per second to a slow receiver, about 300 deliveries in flight, three runs each
(`load/RESULTS.md`, section e):

| | Deliveries per second | Worker threads |
|---|---|---|
| Virtual threads | 226.9 to 230.2 | 36 |
| 1,000 platform threads | 229.3 to 231.2 | about 1,036 |

Throughput and latency are the same. The difference is the thread count. This workload never reaches the point where
platform threads give out, so the measurement does not show an advantage in throughput, and this ADR does not claim
one. The reason to choose virtual threads is that the code stays simple at the same speed.

## Consequences

- The real ceiling is the database connection pool (10 connections by default), which neither model changes. With
  virtual threads more deliveries queue for a connection at once (34 to 57 waiting against 8 to 22), because waiting is
  cheap. It cost no throughput here.
- Whether `synchronized` pins a virtual thread to its carrier in the libraries used under JDK 25 is not verified.
- Everything is ordinary blocking code, so stack traces and a debugger work as usual.

## Alternatives considered

- **WebFlux with Reactor Netty and R2DBC.** Not built, so there is no measurement of it and no claim that it would be
  slower. It would need the data layer rewritten to R2DBC, the claim and record statements restructured as reactive
  chains, and tests that are harder to read, for a workload where the simple version already keeps up.
- **A platform thread pool.** Equivalent at this load, as measured, at about 30 times the threads.
