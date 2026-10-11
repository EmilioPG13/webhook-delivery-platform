# Updates the worker (or the API) with `helm upgrade` while k6 publishes from inside the cluster, then compares what the
# platform accepted with what the receiver saw. The pass condition is zero lost deliveries; duplicates are allowed
# (delivery is at least once, see adr/0001) and counted.
#
#   .\deploy\local\rollout-test.ps1 -Label rollout-worker-1
#   .\deploy\local\rollout-test.ps1 -Label rollout-api-1 -Roll api
#
# Needs the kind cluster, the chart installed (see deploy/local/README.md) and the receiver running. k6 shares the
# node's CPU with the application, so the throughput here is not a benchmark: this run checks correctness.
param(
    [Parameter(Mandatory)] [string] $Label,
    [ValidateSet("worker", "api")] [string] $Roll = "worker",
    [int] $Rate = 40,
    [string] $Duration = "120s",
    [string] $ReceiverPath = "/slow",
    [int] $Endpoints = 20,
    # Seconds of publishing before the upgrade starts, so deliveries are in flight when pods are replaced.
    [int] $UpgradeAfterSec = 25,
    [int] $DrainTimeoutSec = 300,
    # Extra `helm --set` values for the upgrade. The negative control uses it to cut the shutdown short:
    #   -ExtraSet "worker.terminationGracePeriodSeconds=1"
    # A terminating pod keeps the grace period it was created with, so for that to bite, the running pods must
    # already carry it (see deploy/local/README.md).
    [string[]] $ExtraSet = @()
)

$ErrorActionPreference = "Continue"
Set-Location (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent)
New-Item -ItemType Directory -Force load/results | Out-Null
$out = "load/results/$Label.txt"
$ns = "webhooks"

function Psql([string] $sql) {
    kubectl -n $ns exec postgres-0 -- psql -U webhooks -d webhooks -At -c $sql
}

# Reach the receiver's admin API from here, to read what it was sent.
$forward = Start-Process kubectl -ArgumentList "-n", $ns, "port-forward", "svc/receiver", "8090:8080" -PassThru -WindowStyle Hidden
try {
    Start-Sleep -Seconds 3
    Invoke-RestMethod -Method Delete -Uri http://localhost:8090/__admin/requests | Out-Null

    kubectl -n $ns delete job k6 --ignore-not-found | Out-Null
    kubectl -n $ns create configmap k6-script --from-file=publish.js=load/publish.js --dry-run=client -o yaml | kubectl apply -f - | Out-Null
    @"
apiVersion: batch/v1
kind: Job
metadata:
  name: k6
  namespace: $ns
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:1.3.0
          args: ["run", "--quiet", "/load/publish.js"]
          env:
            - { name: BASE_URL, value: "http://webhook-platform-api:8080" }
            - { name: RECEIVER_URL, value: "http://receiver:8080" }
            - { name: RECEIVER_PATH, value: "$ReceiverPath" }
            - { name: RATE, value: "$Rate" }
            - { name: DURATION, value: "$Duration" }
            - { name: ENDPOINTS, value: "$Endpoints" }
          volumeMounts:
            - { name: script, mountPath: /load }
      volumes:
        - name: script
          configMap:
            name: k6-script
"@ | kubectl apply -f - | Out-Null

    $commit = (git rev-parse --short HEAD).Trim()
    $started = Get-Date
    Start-Sleep -Seconds $UpgradeAfterSec

    # Any change to the pod template rolls the deployment; a new memory request is the smallest one.
    $request = "$(Get-Random -Minimum 400 -Maximum 500)Mi"
    Write-Host "helm upgrade: $Roll memory request -> $request"
    $upgradeStart = Get-Date
    $sets = @("$Roll.resources.requests.memory=$request") + $ExtraSet | ForEach-Object { "--set"; $_ }
    helm upgrade webhook-platform deploy/helm/webhook-platform -n $ns -f deploy/helm/webhook-platform/values-kind.yaml `
        @sets --wait --timeout 5m | Out-Null
    $upgradeSec = [int]((Get-Date) - $upgradeStart).TotalSeconds

    # Wait for k6 to finish publishing.
    do {
        Start-Sleep -Seconds 3
        $state = kubectl -n $ns get job k6 -o jsonpath='{.status.succeeded}/{.status.failed}'
    } until ($state -ne "/")
    $publishEnded = Get-Date

    # Wait for the backlog to drain.
    $deadline = (Get-Date).AddSeconds($DrainTimeoutSec)
    $drained = $true
    while ([int](Psql "SELECT count(*) FROM deliveries WHERE status IN ('pending', 'enqueued')") -gt 0) {
        if ((Get-Date) -gt $deadline) { $drained = $false; break }
        Start-Sleep -Seconds 3
    }
    $finished = Get-Date

    $k6Log = kubectl -n $ns logs job/k6 2>$null | Out-String

    # The receiver's journal is the ground truth: every request it was sent, with the delivery's webhook-id header.
    $journal = Invoke-RestMethod -Uri "http://localhost:8090/__admin/requests?limit=100000"
    $ids = $journal.requests | ForEach-Object { $_.request.headers.'webhook-id' } | Where-Object { $_ }
    $received = @($ids).Count
    $distinct = @($ids | Sort-Object -Unique).Count

    $published = [int](Psql "SELECT count(*) FROM events e JOIN tenants t ON t.id = e.tenant_id WHERE t.name LIKE 'k6-%' AND e.created_at >= '$($started.ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))+00'")
    $byStatus = Psql "SELECT string_agg(status || '=' || n, ' ') FROM (SELECT d.status, count(*) n FROM deliveries d JOIN events e ON e.id = d.event_id WHERE e.created_at >= '$($started.ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))+00' GROUP BY 1) s"
    $lost = $published - $distinct
    $duplicates = $received - $distinct
    $verdict = if ($lost -eq 0 -and $drained) { "PASS" } else { "FAIL" }

    $text = @"
run:        $Label
commit:     $commit
when:       $($started.ToString('yyyy-MM-dd HH:mm:ss')) local
settings:   roll=$Roll rate=$Rate/s duration=$Duration receiver=$ReceiverPath endpoints=$Endpoints upgrade_after=${UpgradeAfterSec}s extra=$($ExtraSet -join ' ')
rollout:    helm upgrade took $upgradeSec s (replicas: api=$(kubectl -n $ns get deploy webhook-platform-api -o jsonpath='{.spec.replicas}'), worker=$(kubectl -n $ns get deploy webhook-platform-worker -o jsonpath='{.spec.replicas}'))
drain:      $([int]($finished - $publishEnded).TotalSeconds) s after publishing ended, drained=$drained

events accepted by the API:        $published
deliveries by status:              $byStatus
requests the receiver got:         $received
distinct webhook-ids it got:       $distinct
LOST (accepted, never received):   $lost
DUPLICATES (extra requests):       $duplicates
result:     $verdict

--- k6 ---
$k6Log
"@
    $text | Set-Content -Path $out -Encoding utf8
    $text.Substring(0, $text.IndexOf("--- k6 ---"))
}
finally {
    Stop-Process -Id $forward.Id -ErrorAction SilentlyContinue
}
