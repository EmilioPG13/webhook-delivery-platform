variable "region" {
  description = "The project's AWS Region. Regional resources cannot be created anywhere else."
  type        = string
  default     = "us-east-2"
}

variable "queue_name" {
  description = "Must match webhooks.queue.name in the application."
  type        = string
  default     = "webhook-deliveries"
}

variable "visibility_timeout_seconds" {
  description = "Must match webhooks.queue.visibility-timeout. It outlasts the worker's lease and delivery timeout."
  type        = number
  default     = 60
}

variable "max_receive_count" {
  description = "Receives of one message before SQS moves it to the dead-letter queue (webhooks.queue.max-receive-count)."
  type        = number
  default     = 5
}
