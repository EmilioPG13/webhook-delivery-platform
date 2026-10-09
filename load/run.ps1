# One measured run: k6 publishes, the platform delivers, then everything worth writing down is collected into
# load/results/<Label>.txt (k6 summary, the PostgreSQL report, and the peaks Prometheus saw during the run).
#
#   .\load\run.ps1 -Label a-healthy-1 -Rate 200 -Duration 2m
#   .\load\run.ps1 -Label c-slow-1 -Rate 50 -Duration 2m -ReceiverPath /slow
#
# It starts from a clean slate: deliveries still waiting from an earlier run are cancelled first, because the
# database is a local throwaway and leftovers would be delivered into this run.
param(
    [Parameter(Mandatory)] [string] $Label,
    [Parameter(Mandatory)] [int] $Rate,
    [string] $Duration = "2m",
    [string] $ReceiverPath = "/ok",
    [int] $Endpoints = 20,
    [int] $P95Ms = 200,
    # How long to wait for the backlog to empty after the publishing stops. 0 skips the wait (the storm scenario).
    [int] $DrainTimeoutSec = 600,
    # Seconds to keep watching after publishing stops, for scenarios that never drain (the storm).
    [int] $SettleSec = 0
)

# Native commands write progress to stderr, which Windows PowerShell 5.1 would turn into errors under "Stop".
$ErrorActionPreference = "Continue"
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force load/results | Out-Null
$out = "load/results/$Label.txt"

function Psql([string] $sql) {
    $sql | docker compose exec -T postgres psql -U webhooks -d webhooks -At
}

function Prom([string] $query) {
    $r = Invoke-RestMethod -Uri "http://localhost:9090/api/v1/query" -Method Post -Body @{ query = $query }
    if ($r.data.result.Count -eq 0) { return "n/a" }
    ($r.data.result | ForEach-Object { $_.value[1] }) -join " / "
}

Psql "UPDATE deliveries SET status = 'cancelled' WHERE status IN ('pending', 'enqueued')" | Out-Null
Start-Sleep -Seconds 10

$commit = (git rev-parse --short HEAD).Trim()
$dirty = if ((git status --porcelain).Count -gt 0) { "+uncommitted changes" } else { "" }
$started = Get-Date

$k6 = docker compose run --rm -e RATE=$Rate -e DURATION=$Duration -e RECEIVER_PATH=$ReceiverPath `
    -e ENDPOINTS=$Endpoints -e P95_MS=$P95Ms k6 --quiet /load/publish.js 2>$null | Out-String
$publishEnded = Get-Date

$drained = $true
if ($DrainTimeoutSec -gt 0) {
    $deadline = (Get-Date).AddSeconds($DrainTimeoutSec)
    while ([int](Psql "SELECT count(*) FROM deliveries WHERE status IN ('pending', 'enqueued')") -gt 0) {
        if ((Get-Date) -gt $deadline) { $drained = $false; break }
        Start-Sleep -Seconds 2
    }
}
if ($SettleSec -gt 0) { Start-Sleep -Seconds $SettleSec }
$finished = Get-Date
Start-Sleep -Seconds 6  # let Prometheus take one more sample

$window = [int](($finished - $started).TotalSeconds) + 10
$report = Get-Content load/report.sql | docker compose exec -T postgres psql -U webhooks -d webhooks | Out-String
$disabled = Psql "SELECT count(*) || ' (first ' || coalesce(round(extract(epoch FROM min(e.disabled_at) - '$($started.ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))+00'::timestamptz)::numeric), 0) || ' s, last ' || coalesce(round(extract(epoch FROM max(e.disabled_at) - '$($started.ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))+00'::timestamptz)::numeric), 0) || ' s after the start)' FROM endpoints e JOIN tenants t ON t.id = e.tenant_id WHERE t.name LIKE 'k6-%' AND NOT e.active AND e.disabled_at >= '$($started.ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))+00'::timestamptz"

$text = @"
run:        $Label
commit:     $commit $dirty
when:       $($started.ToString('yyyy-MM-dd HH:mm:ss')) local
settings:   rate=$Rate/s duration=$Duration receiver=$ReceiverPath endpoints=$Endpoints p95_threshold=${P95Ms}ms
worker env: virtual_threads=$($env:WORKER_VIRTUAL_THREADS) max_concurrent=$($env:WORKER_MAX_CONCURRENT_DELIVERIES) breaker=$($env:BREAKER_FAILURE_THRESHOLD)/$($env:BREAKER_FAILING_FOR)
publishing: $([int]($publishEnded - $started).TotalSeconds) s, drain: $([int]($finished - $publishEnded).TotalSeconds) s, drained=$drained
endpoints disabled during the run: $disabled

--- peaks seen by Prometheus during the run (window ${window}s) ---
due deliveries (max):          $(Prom "max(max_over_time(webhooks_deliveries_due[${window}s]))")
oldest due age s (max):        $(Prom "max(max_over_time(webhooks_deliveries_oldest_due_age_seconds[${window}s]))")
queue messages (max, by state): $(Prom "max by (state) (max_over_time(webhooks_queue_messages[${window}s]))")
hikari active (max, by role):  $(Prom "max by (role) (max_over_time(hikaricp_connections_active[${window}s]))")
hikari pending (max, by role): $(Prom "max by (role) (max_over_time(hikaricp_connections_pending[${window}s]))")
process cpu (max, by role):    $(Prom "max by (role) (max_over_time(process_cpu_usage[${window}s]))")
attempts by outcome (increase): $(Prom "sum by (outcome) (increase(webhooks_delivery_attempts_seconds_count[${window}s]))")
live threads (max, by role):   $(Prom "max by (role) (max_over_time(jvm_threads_live_threads[${window}s]))")
heap used MiB (max, by role):  $(Prom "max by (role) (max_over_time(jvm_memory_used_bytes{area=`"heap`"}[${window}s])) / 1048576")

--- postgres report ---
$report
--- k6 ---
$k6
"@
$text | Set-Content -Path $out -Encoding utf8
$text.Substring(0, $text.IndexOf("--- k6 ---"))
($k6 -split "`n") | Select-String -Pattern "p(95)<|rate<|http_req_duration.|http_reqs.|dropped_iterations|iterations." | ForEach-Object { $_.Line.Trim() }
