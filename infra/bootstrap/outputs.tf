output "state_bucket" {
  description = "The bucket to name in infra/terraform's backend configuration."
  value       = aws_s3_bucket.state.bucket
}

output "state_bucket_arn" {
  description = "For IAM policies that grant access to the state, such as the GitHub Actions roles."
  value       = aws_s3_bucket.state.arn
}
