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
