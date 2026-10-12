# 12. History is kept for 30 days, then deleted in batches

Status: accepted

## Context

Every event produces a delivery per endpoint and every delivery produces up to ten attempt rows with a response
snippet of up to 1 KiB. Without a limit the tables only grow, and the indexes the dispatcher uses slow down with them.
Customers also expect to look at what was sent, for a while.

## Decision

Events older than 30 days are deleted together with their deliveries and attempts, by a job in the `worker` role that
runs hourly (`webhooks.retention`: `period`, `interval`, `batch-size`). It takes the time from the injected `Clock`
like the rest of the worker.

- The unit is the event. Its deliveries, their attempts and any replays go in the same statement, so there is never a
  delivery without its event or an attempt without its delivery.
- An event with a delivery still `pending` or `enqueued` is kept, however old, because a worker still has to read it.
  Deliveries of an endpoint that stayed off are cancelled after 7 days, so they stop blocking this at some point.
- An `Idempotency-Key` is tied to its event and goes with it. After 30 days the same key can publish again.
- It deletes up to `batch-size` events (1,000 by default) per statement and repeats until a batch comes back short, so
  no transaction holds locks for long. Rows another transaction holds are skipped, so a replay in progress is never
  failed by the job.
- Deleted events are counted in `webhooks.events.purged`.

## Consequences

- 30 days is my choice and can be changed in configuration. A tenant needing longer has to export before then; there is
  no export endpoint.
- `delivery_attempts` is the table that will grow fastest. Partitioning it by month, so old data is dropped by detaching
  a partition instead of deleting rows, is the next step and is not built.
- `rate_limit_buckets` has one small row per tenant and is not cleaned.
- Soft-deleted endpoints stay, since old deliveries reference them.

## Alternatives considered

- **Keep everything.** Simple, and wrong for a table that gets ten rows per delivery.
- **Delete by attempt age.** Would leave deliveries that show no history, which is more confusing than no delivery.
- **Partitioned tables from the start.** The better design for volume, with a bigger migration and more operations to
  learn. A measured problem should come first.
- **`ON DELETE CASCADE`.** Fewer statements, and it would let a stray `DELETE` on `events` erase history without
  anyone noticing. Foreign keys that refuse make the deletion order explicit and tested.
