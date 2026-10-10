# GitHub Actions proves who it is with a short-lived token, so the repository holds no AWS keys.
#
# Off by default: the service control policy of this AWS project explicitly denies iam:CreateOpenIDConnectProvider
# (and ListOpenIDConnectProviders), so the provider cannot exist here. The configuration stays so that an account
# without that restriction can switch it on with enable_github_oidc = true.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.enable_github_oidc ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "ci_trust" {
  count = var.enable_github_oidc ? 1 : 0

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github[0].arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Only workflows of this repository running on main. A pull request carries a different sub and is refused.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "ci" {
  count = var.enable_github_oidc ? 1 : 0

  name               = "webhooks-ci"
  assume_role_policy = data.aws_iam_policy_document.ci_trust[0].json
}

data "aws_iam_policy_document" "ci_push_image" {
  # The token call has no resource to scope to.
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = [aws_ecr_repository.app.arn]
  }
}

resource "aws_iam_role_policy" "ci_push_image" {
  count = var.enable_github_oidc ? 1 : 0

  name   = "push-image"
  role   = aws_iam_role.ci[0].id
  policy = data.aws_iam_policy_document.ci_push_image.json
}
