# Load scenarios

One k6 script, [publish.js](publish.js), drives every scenario; what changes is the receiver path and a few worker
settings. k6 runs in the compose network, so it competes with the application for the same CPU. Say so next to every
figure that comes from here. The results so far are in [RESULTS.md](RESULTS.md).

Start the stack first, and open the dashboard at <http://localhost:3000> to watch a run:

```powershell
docker compose up -d --build
```

[run.ps1](run.ps1) does one measured run: it cancels deliveries left over from earlier runs, runs k6, waits for the
backlog to drain, and writes everything worth keeping to `results/<label>.txt` (k6 summary, the lag from publish to the
first attempt read from PostgreSQL through [report.sql](report.sql), and the peaks Prometheus saw).

```powershell
.\load\run.ps1 -Label a-1 -Rate 250 -Duration 90s
```

Give the stack about 30 seconds after any `docker compose up -d` before a run.

## Scenarios

| | Scenario | Command |
|---|---|---|
| a | Publish rate and publish to first attempt, healthy receiver | `.\load\run.ps1 -Label a-1 -Rate 250 -Duration 90s` |
| b | Rate limiter in memory against PostgreSQL | see below |
| c | Slow receiver, lognormal delay (median 800 ms) | `.\load\run.ps1 -Label c-1 -Rate 50 -Duration 90s -ReceiverPath /slow` |
| d | Failure storm until the breaker trips | see below |
| e | Virtual against platform threads, slow receiver | see below |

**b, rate limiter.** The default stores the bucket in PostgreSQL. Switch the API to memory and repeat the same run at a
rate where the difference shows (800 events per second for one tenant):

```powershell
.\load\run.ps1 -Label b-postgres-1 -Rate 800 -Duration 60s
$env:RATE_LIMIT_STORE = "memory"; docker compose up -d api worker
.\load\run.ps1 -Label b-memory-1 -Rate 800 -Duration 60s
```

**d, failure storm.** The production breaker needs 50 failures over 24 hours, too slow for a run. Shorten it on the
worker, then publish to the endpoint that always answers 500. Deliveries to a switched-off endpoint never drain, so the
run watches for 90 s instead of waiting:

```powershell
$env:BREAKER_FAILURE_THRESHOLD = "5"; $env:BREAKER_FAILING_FOR = "30s"
docker compose up -d worker
.\load\run.ps1 -Label d-1 -Rate 50 -Duration 60s -ReceiverPath /fail500 -DrainTimeoutSec 0 -SettleSec 90
```

**e, threads.** Raise the worker's delivery limit well above the default 50 so threads, not the limit, are the ceiling,
spread the load over enough endpoints (5 deliveries at a time per endpoint are allowed), and run the slow receiver once
per thread model:

```powershell
$env:WORKER_MAX_CONCURRENT_DELIVERIES = "1000"
$env:WORKER_VIRTUAL_THREADS = "true";  docker compose up -d worker
.\load\run.ps1 -Label e-virtual-1 -Rate 240 -Duration 90s -ReceiverPath /slow -Endpoints 100
$env:WORKER_VIRTUAL_THREADS = "false"; docker compose up -d worker
.\load\run.ps1 -Label e-platform-1 -Rate 240 -Duration 90s -ReceiverPath /slow -Endpoints 100
```

Unset the variables (`Remove-Item Env:WORKER_*, Env:BREAKER_*, Env:RATE_LIMIT_STORE`) and run
`docker compose up -d api worker` to go back to the defaults.

## Gotchas that cost a measurement

- **WireMock loads its stubs when it starts.** After adding or editing a file in `receiver/mappings/`, run
  `docker compose restart receiver`, or the new path answers 404 and every delivery to it is retried.
- **The receiver can be the bottleneck.** It runs with 1,000 container threads and request logging off for this reason.
  Before trusting a slow-receiver result, check that the receiver answers 100 parallel `/slow` calls in about the
  median time.
- **k6 and the application share the machine.** Compare configurations, not absolute figures.

## Rules for a figure that goes in the README

- At least 3 runs per scenario, on the same machine, with the hardware, commit and settings written down.
- The publish threshold (`-P95Ms`, default 200) is set before the first measurement and changed only with a note
  saying why.
- A run with `http_req_failed` above 1% is not a valid capacity run. A 429 counts as a failure here.
- Throw a result away when the instrument was at fault, and say so (see RESULTS.md).
