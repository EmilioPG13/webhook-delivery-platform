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

variable "demo_window" {
  description = "Creates the VPC, EKS cluster and RDS database. Leave false except for the 3 to 5 days of the demo."
  type        = bool
  default     = false
}

variable "vpc_cidr" {
  description = "The demo VPC. The chart's NetworkPolicy allows the worker to reach the database inside 10.0.0.0/16."
  type        = string
  default     = "10.0.0.0/16"
}

variable "kubernetes_version" {
  description = "EKS version, for example \"1.34\". Null takes EKS's current default."
  type        = string
  default     = null
}

variable "node_instance_type" {
  description = "Worker node size. The Free plan only launches Free Tier eligible types (aws ec2 describe-instance-types --filters Name=free-tier-eligible,Values=true), which rules out t3.medium. Pods get their IPs from the VPC, so types with few network interfaces run out of pod slots quickly."
  type        = string
  default     = "c7i-flex.large"
}

variable "node_count" {
  description = "Fixed number of nodes."
  type        = number
  default     = 2
}

variable "api_access_cidrs" {
  description = "Who may reach the cluster's API endpoint (IAM still decides who may do anything). Narrow it to your own address."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "postgres_version" {
  description = "RDS PostgreSQL version. The application needs 18 (uuidv7)."
  type        = string
  default     = "18"
}

variable "database_instance_class" {
  description = "RDS instance size."
  type        = string
  default     = "db.t4g.micro"
}
