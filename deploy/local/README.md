# Local Kubernetes

A `kind` cluster that runs the Helm chart against PostgreSQL and an SQS-compatible queue, for developing the chart
and rehearsing rollouts at no cost. Needs Docker, `kind`, `kubectl` and `helm`.

```powershell
kind create cluster --config deploy/local/kind-config.yaml
kubectl apply -f deploy/local/dependencies.yaml
kubectl -n webhooks rollout status statefulset/postgres

# The test receiver, with the stubs of load/receiver/mappings
kubectl -n webhooks create configmap receiver-mappings --from-file=load/receiver/mappings
kubectl apply -f deploy/local/receiver.yaml

# The application. kind has no registry, so the image is loaded into the node.
docker build -t webhooks:dev .
kind load docker-image webhooks:dev --name webhooks
helm upgrade --install webhook-platform deploy/helm/webhook-platform -n webhooks `
  -f deploy/helm/webhook-platform/values-kind.yaml --wait

kubectl -n webhooks port-forward svc/webhook-platform-api 8081:8080
```

After changing the code, rebuild and reload the image, then `kubectl -n webhooks rollout restart deployment/webhook-platform-api deployment/webhook-platform-worker`.

## Monitoring

```powershell
helm upgrade --install kps oci://ghcr.io/prometheus-community/charts/kube-prometheus-stack --version 92.3.0 `
  -n monitoring --create-namespace -f deploy/local/kube-prometheus-stack-values.yaml --wait
kubectl -n monitoring create configmap webhooks-dashboard --from-file=webhooks.json=observability/grafana/dashboards/webhooks.json
kubectl -n monitoring label configmap webhooks-dashboard grafana_dashboard=1
kubectl -n monitoring port-forward svc/kps-grafana 3001:80   # admin / admin
```

`values-kind.yaml` already turns the ServiceMonitor on, so install the stack before the chart or upgrade the chart
afterwards (the ServiceMonitor needs the stack's CRDs).

## Rolling update without losing deliveries

[rollout-test.ps1](rollout-test.ps1) runs k6 as a Job inside the cluster, upgrades the worker (or the API) with
`helm upgrade` while it publishes, and compares what the API accepted with the `webhook-id`s the receiver was sent.
Zero lost is the pass condition; duplicates are counted, not failed.

```powershell
.\deploy\local\rollout-test.ps1 -Label rollout-worker-1 -ReceiverPath /very-slow -Rate 15
.\deploy\local\rollout-test.ps1 -Label rollout-api-1 -Roll api -ReceiverPath /very-slow -Rate 15
```

A negative control shows the test can see a problem. Make the running pods carry a one second shutdown grace first,
because **a terminating pod keeps the grace period it was created with**, not the one in the new template:

```powershell
helm upgrade webhook-platform deploy/helm/webhook-platform -n webhooks -f deploy/helm/webhook-platform/values-kind.yaml `
  --set worker.terminationGracePeriodSeconds=1 --wait
.\deploy\local\rollout-test.ps1 -Label rollout-worker-control-1 -ReceiverPath /very-slow -Rate 15 `
  -ExtraSet "worker.terminationGracePeriodSeconds=1"
```

Run a normal `helm upgrade` afterwards to put the grace period back.

## Network policy check

With the policy on, the worker reaches the receiver, the database, the queue and the public internet, and nothing
else in the private ranges:

```powershell
$w = kubectl -n webhooks get pods -l app.kubernetes.io/component=worker -o jsonpath='{.items[0].metadata.name}'
kubectl -n webhooks exec $w -- curl -s -o /dev/null -m 4 http://webhook-platform-api:8080   # exit 28: blocked
kubectl -n webhooks exec $w -- curl -s -o /dev/null -m 4 http://169.254.169.254/            # exit 28: blocked
kubectl -n webhooks exec $w -- curl -s -o /dev/null -m 4 https://example.com                # exit 0
```

Tear it down with `kind delete cluster --name webhooks`. The database lives in the cluster, so deleting it also
deletes the data.
