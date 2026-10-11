output "queue_url" {
  value = aws_sqs_queue.deliveries.id
}

output "dead_letter_queue_url" {
  value = aws_sqs_queue.dlq.id
}

output "worker_role_arn" {
  value = aws_iam_role.worker.arn
}

output "api_role_arn" {
  value = aws_iam_role.api.arn
}

output "master_key_secret_arn" {
  value = aws_secretsmanager_secret.master_key.arn
}

output "admin_token_secret_arn" {
  value = aws_secretsmanager_secret.admin_token.arn
}

# Set only while the demo window is open.
output "cluster_name" {
  value = one(aws_eks_cluster.demo[*].name)
}

output "database_endpoint" {
  value = one(aws_db_instance.demo[*].address)
}

output "database_password_secret_arn" {
  description = "The secret RDS created for the master password. The chart's Secret takes DATABASE_PASSWORD from it."
  value       = one(aws_db_instance.demo[*].master_user_secret[0].secret_arn)
}
