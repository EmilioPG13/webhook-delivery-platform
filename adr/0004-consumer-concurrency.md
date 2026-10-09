# 4. Consumers read into free delivery slots instead of waiting for their batch

Status: accepted

## Context

The worker reads the queue with a few long-polling loops. Each read returns up to 10 messages, and each message is
delivered on its own virtual thread. `webhooks.worker.max-concurrent-deliveries` (50) is meant to be how many
deliveries one worker has in flight.

The first load run against a slow receiver (median answer 800 ms, lognormal, see `load/README.md`) showed that
number was never reached. Publishing 50 events per second for 90 seconds, the worker delivered about 13 per second,
the queue grew to between 3,500 and 3,800 messages, and the last delivery finished 4 minutes after publishing stopped. Nothing
was saturated: worker CPU 3% to 7%, the database pool idle, and the receiver, checked separately, answering 100
parallel slow requests without queueing.

## Cause

The loop submitted the 10 messages of a read and then waited for **all** of them before reading again. With a
lognormal answer time, the slowest of 10 takes about 1.5 seconds, so a loop completed 10 deliveries per 1.5 seconds.
Two loops make 13 per second, and at most 20 deliveries in flight against a configured limit of 50. The limit
could not be reached whatever its value, because the loop count and the batch size capped it first.

## Decision

A semaphore holds one permit per delivery slot (`max-concurrent-deliveries`). A loop waits for one free slot, takes
as many more as are free right now (up to the queue's batch size), reads that many messages, gives back the slots
the read did not fill, and goes straight back to the queue. A slot returns when its delivery finishes.

The loop no longer waits for its batch, and it reads only into a free slot. The second part matters as much as the
first: a message that is received while all slots are busy sits invisible to other readers, with its visibility
timeout running, until its turn comes. Too long a wait and it reappears and is delivered twice. Reading only into a
free slot removes that wait.

## Result

Same workload, same machine, three runs each (`load/RESULTS.md`):

| | Before | After |
|---|---|---|
| Deliveries per second | 13.1, 12.9, 12.8 | 47.9, 47.7, 47.1 |
| Time to drain after publishing stops | 253 to 263 s | 5 to 8 s |
| Publish to first attempt, p50 | 120 to 130 s | 0.65 to 0.67 s |
| Publish to first attempt, p99 | 249 to 297 s | 3.65 to 3.69 s |

Deliveries now keep pace with the 50 per second published (the small gap is the few seconds the last deliveries take after publishing ends). The change is
the only difference between the two sets of runs.

## Consequences

- `max-concurrent-deliveries` now means what it says. It also decides how many database connections and how much
  memory a worker needs under load, so raising it is a decision, not a free knob. The worker's database pool (10) already shows waiting
  threads at 200 or more deliveries in flight (`load/RESULTS.md`).
- The processor keeps its own `@ConcurrencyLimit`. It is now redundant, because the loops never submit more than the
  limit, and is kept as a second guard for callers that bypass the consumer.
- The platform-thread mode (`webhooks.worker.virtual-threads=false`) uses a fixed pool of the same size, so the
  semaphore and the pool agree.

## Alternatives considered

- **More consumer loops.** It raises the cap (loops times 10) but leaves the head-of-line blocking inside each
  batch where it is, and it multiplies idle long polls.
- **Smaller batches.** Smaller batches wait for fewer slow messages, at the price of more SQS requests, which are
  billed per request.
