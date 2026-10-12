# 5. The queue is SQS, and PostgreSQL stays the source of truth

Status: accepted

## Context

Deliveries have to reach workers somehow, and the platform must not lose an event when the queue is down. The
candidates were SQS, RabbitMQ, Redis, and PostgreSQL itself with `FOR UPDATE SKIP LOCKED`.

At the scale this project targets, PostgreSQL alone would be enough. That is the honest baseline: everything below
is a price paid for something other than raw necessity.

## Decision

`deliveries` is a transactional outbox. Publishing inserts the event and one delivery per subscribed endpoint in a
single transaction. A dispatcher marks due rows `enqueued`, commits, and only then sends the delivery ids (never
payloads) to SQS. Consumers claim the row with a lease before doing anything. A reaper returns rows stuck in
`enqueued` to `pending`.

Retries are scheduled in PostgreSQL (`next_attempt_at`), not in SQS. SQS can delay a message by 15 minutes at most
and the retry schedule ([ADR 6](0006-retry-schedule.md)) reaches 24 hours. A message that fails to be processed
five times lands in a dead-letter queue.

Locally and in tests ElasticMQ stands in for SQS, behind a `DeliveryQueue` interface.

## Why SQS at all

- A managed dead-letter queue and visibility timeout, instead of building both.
- Workers can later scale on queue depth.
- It is the AWS service most Java back-end postings name, and the project is also a way to learn it.

## Consequences

- Two systems must stay coherent. The reaper exists for that, and the failure it covers is always a delay, never a
  loss. `InfrastructureOutageTest` shows deliveries waiting in `enqueued` while the queue is down and flowing once it
  is back, and a message kept when the database fails at the claim.
- A worker that dies after delivering and before recording produces a duplicate ([ADR 1](0001-at-least-once-delivery.md)).
- SQS costs another failure mode to operate and another IAM surface. The queue permissions are in Terraform and
  follow what the code calls, no more.

## Alternatives considered

- **Queue only in PostgreSQL.** The simplest option and probably sufficient. Rejected because it teaches nothing
  about AWS and leaves nothing to scale workers on. If this were a product with no learning goal, this would win.
- **RabbitMQ.** Another stateful service to run, or Amazon MQ billed by the hour.
- **Redis.** Another store whose durability depends on how it is configured.
- **LocalStack for a local SQS.** Needs an account and an auth token since March 2026. ElasticMQ needs neither.
