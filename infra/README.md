# Infrastructure

Terraform, in two parts with different lifetimes. Every resource carries the tag `project = webhooks`.

| Directory | Creates | Lifetime |
|---|---|---|
| `bootstrap/` | Budget with email alerts, S3 bucket for the Terraform state, ECR repository, optionally the GitHub OIDC provider and the CI role (off, see below) | Permanent, costs almost nothing |
| `demo/` | SQS queue with its dead-letter queue, two Secrets Manager secrets, IAM roles for the worker and the API | Created to test and destroyed afterwards |

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

## Tear down

```powershell
cd infra\demo; terraform destroy      # bootstrap stays
```
