# Infrastructure

Terraform, in two parts with different lifetimes. Every resource carries the tag `project = webhooks`.

| Directory | Creates | Lifetime |
|---|---|---|
| `bootstrap/` | Budget with email alerts, S3 bucket for the Terraform state, ECR repository, optionally the GitHub OIDC provider and the CI role (off, see below) | Permanent, costs almost nothing |
| `demo/` | SQS queue with its dead-letter queue, two Secrets Manager secrets, IAM roles for the worker and the API. Behind `demo_window`: a VPC, an EKS cluster and an RDS database | The data part is created to test and destroyed afterwards. The window part lives 3 to 5 days |

The account is a managed AWS project in `us-east-2`; its service control policies restrict some IAM actions.
All commands below run with `AWS_PROFILE=webhooks` (`aws login --profile webhooks` renews the session).

## Bootstrap, first time

The state bucket is created by the same configuration that stores its state in it, so the first apply is local.

```powershell
cd infra\bootstrap
Rename-Item backend.tf backend.tf.off                 # no backend yet
"alert_email = `"you@example.com`"" | Set-Content terraform.tfvars   # gitignored
terraform init
terraform apply "-target=aws_budgets_budget.monthly"  # the budget goes first, before any other resource
terraform plan -out=tfplan; terraform apply tfplan    # everything else
Rename-Item backend.tf.off backend.tf
Copy-Item backend.hcl.example backend.hcl             # put the real bucket name in it
terraform init -migrate-state -backend-config=backend.hcl
```

**GitHub OIDC is off (`enable_github_oidc = false`).** The service control policy of the AWS project this was built
in explicitly denies `iam:CreateOpenIDConnectProvider`, so keyless publishing from GitHub Actions to ECR cannot work
there. CI publishes the image to GitHub Container Registry instead (`ghcr.io/<owner>/<repo>`, tagged with the commit
and `latest`), authenticated with the workflow's own token. The first push creates the package as private; make it
public in the repository's package settings, or give the cluster an image pull secret.

The configuration in `github_oidc.tf` stays for an account without that restriction: with the variable set to
`true` it creates the `webhooks-ci` role, which trusts only this repository's `main` branch and may only push to the
ECR repository. The ECR repository exists either way, for pushing by hand with temporary credentials.

## Demo, data part

```powershell
cd infra\demo
Copy-Item ..\bootstrap\backend.hcl backend.hcl
terraform init -backend-config=backend.hcl
terraform plan -out=tfplan; terraform apply tfplan
```

The secrets are created empty on purpose, so their values never reach the state:

```powershell
aws secretsmanager put-secret-value --secret-id webhooks/master-key --secret-string <base64 of 32 random bytes>
aws secretsmanager put-secret-value --secret-id webhooks/admin-token --secret-string <random token>
```

## Running the application against the real queue

Short-lived credentials from the worker role, no permanent keys:

```powershell
$c = aws sts assume-role --role-arn <worker_role_arn> --role-session-name local | ConvertFrom-Json
$env:AWS_ACCESS_KEY_ID = $c.Credentials.AccessKeyId
$env:AWS_SECRET_ACCESS_KEY = $c.Credentials.SecretAccessKey
$env:AWS_SESSION_TOKEN = $c.Credentials.SessionToken
$env:AWS_REGION = "us-east-2"
# The dev profile points at ElasticMQ. An empty endpoint means real AWS, and the queues come from Terraform. These go
# in as arguments because PowerShell deletes an environment variable set to "".
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev,worker" "-Dspring-boot.run.arguments=--webhooks.queue.endpoint= --webhooks.queue.create-if-missing=false"
```

Only PostgreSQL and the receiver need to run locally (`docker compose up -d --wait postgres receiver`).

Dead-letter test: send a body that is not a delivery id. The consumer ignores it, SQS hands it out again every
visibility timeout, and after `max_receive_count` receives it moves to the dead-letter queue.

```powershell
aws sqs send-message --queue-url <queue_url> --message-body poison
aws sqs get-queue-attributes --queue-url <dead_letter_queue_url> --attribute-names ApproximateNumberOfMessages
```

## Demo window: EKS, RDS and the run in the cluster

`cluster.tf` and `database.tf` create a VPC (public subnets, no NAT gateway, no load balancer), an EKS cluster with two
`c7i-flex.large` nodes (the Free plan refuses `t3.medium`: only Free Tier eligible types launch), an RDS PostgreSQL 18 instance and the Pod Identity associations. All of it is behind
`demo_window`, which is `false` by default, so the data layer above stays as it is between demos. Nothing in the
window is exposed to the internet; the API is used through `kubectl port-forward`.

**Before opening the window**

- `aws login --profile webhooks`, then `aws freetier get-account-plan-state`. EKS is not on every plan: if it is not on
  the list for the plan the account is on, either move to the paid plan (the remaining credit is kept) or use k3s on one
  EC2 instance, which this directory does not cover.
- Rough cost, **unverified against the pricing pages**: EKS control plane 0.10 USD/h, two `c7i-flex.large` about 0.085 USD/h
  each, `db.t4g.micro` under 0.02 USD/h, plus storage and public IPv4 addresses. Around 7 USD a day, 30 USD for four days.
  Check it in the AWS pricing calculator and write the total in the plan first. The monthly budget from `bootstrap/`
  is 5 USD, so it **will** send its alerts: raise it for the window, or expect the emails.
- Put a calendar reminder on the day the window must close.

**Open**

```powershell
cd infra\demo
terraform plan -var demo_window=true -out=tfplan; terraform apply tfplan
aws eks update-kubeconfig --name webhooks-demo --region us-east-2

# The master key is read by the pods from Secrets Manager (see the data part above for put-secret-value).
# The database password was created by RDS; the chart's Secret takes it from there.
$pw = aws secretsmanager get-secret-value --secret-id (terraform output -raw database_password_secret_arn) --query SecretString --output text | ConvertFrom-Json
kubectl create namespace webhooks
kubectl -n webhooks create secret generic webhooks --from-literal=DATABASE_PASSWORD=$($pw.password) --from-literal=WEBHOOKS_ADMIN_TOKEN=<the admin token>

cd ..\..
$db = terraform -chdir=infra/demo output -raw database_endpoint
helm upgrade --install webhook-platform deploy/helm/webhook-platform -n webhooks `
  -f deploy/helm/webhook-platform/values-eks.yaml `
  --set "config.DATABASE_URL=jdbc:postgresql://${db}:5432/webhooks?sslmode=require" --wait
kubectl -n webhooks port-forward svc/webhook-platform-api 8081:8080
```

Two things that cost time in the first window. Every `terraform plan` and `apply` here needs `-var demo_window=true`
while the window is open: without it the plan proposes destroying the whole window. And the VPC CNI applies a
NetworkPolicy to a new pod a few seconds after it starts, so test the policy with `kubectl exec` into a running pod, not
from a command that runs at start.

The image comes from GHCR: make the package public (see above) or add an image pull secret and set
`image.pullSecrets`. The chart's `ServiceMonitor` stays off unless kube-prometheus-stack is installed on the cluster.

**The run in the cluster.** Add the test receiver and the load overlay, run k6 as a Job, read the summary, then take the
overlay off again:

```powershell
kubectl -n webhooks create configmap receiver-mappings --from-file=load/receiver/mappings
kubectl apply -f deploy/local/receiver.yaml
helm upgrade webhook-platform deploy/helm/webhook-platform -n webhooks -f deploy/helm/webhook-platform/values-eks.yaml `
  -f deploy/helm/webhook-platform/values-eks-load.yaml --set "config.DATABASE_URL=jdbc:postgresql://${db}:5432/webhooks?sslmode=require" --wait
kubectl -n webhooks create configmap k6-script --from-file=publish.js=load/publish.js
kubectl apply -f deploy/eks/k6-job.yaml
kubectl -n webhooks wait --for=condition=complete job/k6 --timeout=10m; kubectl -n webhooks logs job/k6
```

## Tear down

```powershell
cd infra\demo
kubectl delete namespace webhooks     # first, so nothing created from inside the cluster outlives it
terraform apply -var demo_window=false    # destroys the VPC, cluster and database; the data layer stays
# or, for everything in this directory: terraform destroy      (bootstrap always stays)
```

Terraform does not always clean up after itself. Check by hand in the console, in `us-east-2`:

- [ ] EKS clusters and node groups, and the EC2 instances behind them
- [ ] Load balancers and target groups (there should be none)
- [ ] EBS volumes left by PersistentVolumeClaims (Terraform does not delete these)
- [ ] RDS instances and snapshots (none should be left: the instance skips its final snapshot)
- [ ] Elastic IPs and NAT gateways (there should be none)
- [ ] CloudWatch log groups
- [ ] Secrets Manager secrets, including the one RDS created for the master password
- [ ] Old images in ECR
- [ ] The next day, Cost Explorer filtered by the tag `project = webhooks`: the daily cost must be close to zero
