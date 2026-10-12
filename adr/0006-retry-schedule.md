# 6. Ten attempts over about 47 hours, with jitter

Status: accepted

## Context

A receiver that fails is usually having an outage, a deploy, or a bad hour. Retrying immediately mostly hits the same
problem; retrying forever grows a backlog nobody reads. The schedule decides how long the platform is patient.

## Decision

An event gets at most 10 attempts. After attempt *n* the wait is:

| After attempt | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|
| Wait | 30 s | 2 min | 10 min | 30 min | 1 h | 3 h | 6 h | 12 h | 24 h |

The waits add up to about 46 h 43 min, so the last attempt is roughly 47 hours after the first. The tenth failure
marks the delivery `failed`.

- **Jitter of plus or minus 20%** on every wait, so a thousand deliveries that failed together do not come back
  together and knock the receiver over again.
- **`Retry-After` is honored** on `429` and `503`. The longer of the schedule and the header wins, capped at 24 hours,
  so a receiver cannot park a delivery for a week.
- **Some failures are not retried.** `410 Gone` ends the delivery and switches the endpoint off, and so does a
  destination the SSRF guard refused ([ADR 7](0007-ssrf-guard-at-connect-time.md)).
- The schedule is a pure function of the attempt count, the outcome and the time, with the clock and the random source
  injected, so `RetryPolicyTest` checks it with a table and no waiting.

The circuit breaker is the other half of the policy. An endpoint is switched off after 50 failures in a row that have
lasted at least 24 hours, and its pending deliveries are cancelled after 7 more days. Retries stop costing attempts
long before the schedule runs out for a receiver that is plainly gone.

## Consequences

- The 47 hours and the individual waits are my judgment, not a measurement and not copied from a standard. They are
  meant to ride out a weekend-sized problem without turning into a backlog.
- Receivers must deduplicate on `webhook-id` for at least 48 hours, which is why that number is in the README.
- A delivery that sat in the queue while the worker was down does not use up an attempt, because only a recorded
  attempt counts.

## Alternatives considered

- **A fixed interval.** Simple, and it hammers a struggling receiver at a steady rate for as long as it lasts.
- **Pure exponential backoff without a table.** Close to what is built, but the doubling gets either too slow or too
  fast at one end, and a table can be read and argued about.
- **A longer window, a week or more.** More forgiving for the receiver, a bigger backlog and a longer wait for the
  tenant to learn that something is wrong. The replay endpoint covers the rare receiver that needs more time.
- **Retrying in SQS with delays.** Limited to 15 minutes ([ADR 5](0005-queue-sqs-with-postgres-as-source-of-truth.md)).
