variable "region" {
  description = "AWS region for the state bucket. The same region as the main stack, so state reads are local."
  type        = string
  default     = "ca-central-1"
}

variable "state_bucket_prefix" {
  description = "Start of the bucket's name; the account ID and region are appended to make it globally unique."
  type        = string
  default     = "grain-bin-tfstate"
}
