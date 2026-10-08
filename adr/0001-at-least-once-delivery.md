# 1. Deliveries are at least once, never exactly once

Status: accepted

## Context

The platform sends an HTTP request to a customer's server and waits for an answer. When no answer comes
back, there is no way to tell two situations apart: the request never arrived, or it arrived and the
answer was lost. Retrying risks a duplicate. Not retrying risks a loss. Between two machines with a
network in the middle, no protocol removes that choice.

The same ambiguity exists inside the platform. A worker delivers a webhook and then writes the result
to PostgreSQL. If it dies between the two, the receiver has the event and the database does not know.

## Decision

Every event is attempted until one attempt receives a 2xx or the attempts run out. When in doubt the
platform delivers again, so a receiver may see the same event more than once and must never miss one
that could have been delivered.

Duplicates are made harmless on the receiver's side. All attempts of one event carry the same
`webhook-id` header (the event id), which is part of the signed content. The receiver stores the ids it
has processed and drops repeats. Because retries can arrive hours later with a fresh timestamp, the
5-minute timestamp window does not do this job; deduplication by id, for at least 48 hours, does.

The order of the steps keeps this the only failure mode:

1. Claim the delivery with a lease, in its own statement.
2. Make the HTTP call, holding no database transaction.
3. Record the attempt and the next state, in one short transaction.
4. Delete the queue message, only after step 3 committed.

A crash before step 4 leaves the message in the queue, and a crash before step 3 leaves the delivery
`enqueued` with an expiring lease. Either way the work reappears and is done again. The worst case is
a second HTTP call. Nothing is ever silently dropped.

## Proof

`WorkerCrashTest` kills the worker at the worst moment: after the HTTP request left, before the attempt
is recorded. It then lets the lease expire and shows that the receiver gets the request twice, with the
same `webhook-id`, and that the delivery still ends `delivered`. Only the recorded attempt appears in
the history; the crashed one is visible only in the receiver's log.

## Consequences

- The README documents the receiver's duty: verify the signature over the raw bytes, then deduplicate
  by `webhook-id`.
- An attempt that was sent but never recorded does not count toward the 10 attempts.

## Alternatives considered

- **Exactly once.** Rejected: it cannot be offered honestly over a network. Systems that claim it mean
  at least once plus deduplication, which is what this decision builds explicitly.
- **At most once** (never retry an attempt whose outcome is unknown). Rejected: it turns every network
  blip into a lost event, and the customer who complains "it never arrived" is right.
- **Recording the attempt before the HTTP call.** Rejected: it narrows the window but does not close
  it, and it holds a database row (or a transaction) across a call that can take 15 seconds, which
  exhausts the connection pool under load.
