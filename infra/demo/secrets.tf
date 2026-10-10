# Only the containers are created here. The values are put in by hand (see README.md) so they never reach the
# Terraform state, which would hold them in plain text.
resource "aws_secretsmanager_secret" "master_key" {
  name        = "webhooks/master-key"
  description = "Base64 of the 32-byte key that encrypts endpoint secrets (WEBHOOKS_MASTER_KEY)"

  # The demo is torn down and rebuilt, and a secret scheduled for deletion keeps its name for days.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret" "admin_token" {
  name                    = "webhooks/admin-token"
  description             = "Operator token for /admin/** and the metrics endpoint (WEBHOOKS_ADMIN_TOKEN)"
  recovery_window_in_days = 0
}
