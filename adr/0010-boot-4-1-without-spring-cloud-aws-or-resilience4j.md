# 10. Spring Boot 4.1, the AWS SDK directly, no Resilience4j

Status: accepted

## Context

Spring Boot 4 is a recent major version, and the libraries around it do not all support it at the same moment. The
stack had to be fixed before writing code, and a library that fails at startup is an expensive thing to find later.

## Decision

Spring Boot 4.1.1 on Java 25. SQS is called with the AWS SDK for Java v2 directly. There is no Spring Cloud AWS and no
Resilience4j.

- The circuit breaker lives in the database (`consecutive_failures`, `failing_since`, `active` on `endpoints`), so every
  worker and every API instance sees the same state.
- Retries between attempts are scheduled in PostgreSQL ([ADR 6](0006-retry-schedule.md)).
- In-memory needs use Spring 7's own `@ConcurrencyLimit` and `@Retryable`. They need
  `@EnableResilientMethods(proxyTargetClass = true)`: with interface proxies `@Retryable` on an implementation is
  silently ignored, and `SqsDeliveryQueueTest` fails if that regresses.

## Consequences

- More code to write, read and explain: the SQS consumer, the receive loop and the shutdown are hand written. That is
  also the part worth knowing how it works.
- Less exposure to libraries that lag Boot. The ones that were verified to work with Boot 4.1 are in use: Bucket4j
  ([ADR 2](0002-rate-limiting.md)) and springdoc (with a rename step for Jackson 3, see `OpenApiConfig`).
- To revisit when Boot 4.2 is released, planned for November 2026.

## Alternatives considered

- **Boot 3.5.** Free support ended on 2026-06-30. Starting a new project on it would mean a forced migration soon after.
- **Boot 4.0.** Free support ends on 2026-12-31, before this project would be finished.
- **Spring Cloud AWS.** Its compatibility table listed only Boot 4.0.x when this was decided. Writing the consumer by
  hand also means understanding visibility timeouts, long polling and deletion order rather than hiding them.
- **Resilience4j.** Boot 4 support could not be confirmed, and a per-process circuit breaker would not be shared
  between workers anyway, which defeats its purpose here.
