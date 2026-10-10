variable "region" {
  description = "The project's AWS Region. Regional resources cannot be created anywhere else."
  type        = string
  default     = "us-east-2"
}

variable "github_repository" {
  description = "The only repository whose workflows may assume the CI role, as owner/name."
  type        = string
  default     = "EmilioPG13/webhook-delivery-platform"
}

variable "enable_github_oidc" {
  description = "Creates the GitHub OIDC provider and the CI role. This AWS project's SCP forbids the provider."
  type        = bool
  default     = false
}

variable "alert_email" {
  description = "Where budget alerts go. Set it in terraform.tfvars, which is gitignored."
  type        = string
}

variable "budget_limit_usd" {
  description = "Monthly cost budget."
  type        = number
  default     = 5
}

variable "actual_alert_thresholds" {
  description = <<-EOT
    Percentages of the budget that trigger an email once the actual spend reaches them. The 1 is a deliberate
    canary, low enough to fire on the first cents of spend so the alert path is proven. Remove it afterwards.
  EOT
  type        = list(number)
  default     = [1, 50, 80, 100]
}
