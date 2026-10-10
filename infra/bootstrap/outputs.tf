output "state_bucket" {
  value = aws_s3_bucket.state.id
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "ci_role_arn" {
  description = "The role the CI workflow assumes. Not a secret: only the repository's main branch can use it."
  value       = one(aws_iam_role.ci[*].arn)
}
