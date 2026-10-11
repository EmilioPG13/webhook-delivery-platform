data "aws_caller_identity" "current" {}

# Anyone in this account who is allowed to call sts:AssumeRole can take the roles below, which is how a run on a
# laptop gets short-lived credentials instead of permanent keys. The second statement is EKS Pod Identity: the
# pods of a ServiceAccount that cluster.tf associates with a role get its credentials from the node's agent.
# sts:TagSession is required by that service, which tags the session with the cluster and pod.
data "aws_iam_policy_document" "trust_account" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "read_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.master_key.arn,
      aws_secretsmanager_secret.admin_token.arn,
    ]
  }
}

# The worker: dispatcher (sends), consumers (receive, delete) and queue depth metrics (attributes).
# ChangeMessageVisibility is deliberately absent: the application never calls it.
data "aws_iam_policy_document" "worker_queue" {
  statement {
    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:GetQueueUrl",
    ]
    resources = [aws_sqs_queue.deliveries.arn]
  }
}

resource "aws_iam_role" "worker" {
  name               = "webhooks-worker"
  assume_role_policy = data.aws_iam_policy_document.trust_account.json
}

resource "aws_iam_role_policy" "worker_queue" {
  name   = "queue"
  role   = aws_iam_role.worker.id
  policy = data.aws_iam_policy_document.worker_queue.json
}

resource "aws_iam_role_policy" "worker_secrets" {
  name   = "secrets"
  role   = aws_iam_role.worker.id
  policy = data.aws_iam_policy_document.read_secrets.json
}

# The API role reads the same two secrets and never touches SQS.
resource "aws_iam_role" "api" {
  name               = "webhooks-api"
  assume_role_policy = data.aws_iam_policy_document.trust_account.json
}

resource "aws_iam_role_policy" "api_secrets" {
  name   = "secrets"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.read_secrets.json
}
