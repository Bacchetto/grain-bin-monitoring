# What the rest of the project needs to know about the deployed stack:
# `terraform output` prints these after an apply. deploy.yml reads several;
# the GitHub repository variables are set from the role ARNs.

output "site_url" {
  description = "The dashboard and API, over HTTPS. Point the simulator here with --url."
  value       = var.domain_name != "" ? "https://${var.domain_name}" : "https://${aws_cloudfront_distribution.main.domain_name}"
}

output "cloudfront_distribution_id" {
  description = "For cache invalidations after a frontend deploy."
  value       = aws_cloudfront_distribution.main.id
}

output "ecr_repository_url" {
  description = "Where deploy.yml pushes the API image."
  value       = aws_ecr_repository.api.repository_url
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  value = aws_ecs_service.api.name
}

output "frontend_bucket" {
  description = "Where deploy.yml syncs the built dashboard."
  value       = aws_s3_bucket.frontend.bucket
}

output "admin_token_secret_name" {
  description = "The Secrets Manager secret holding ADMIN_TOKEN. Its value is not in Terraform state."
  value       = aws_secretsmanager_secret.admin_token.name
}

output "github_plan_role_arn" {
  description = "Repository variable AWS_PLAN_ROLE_ARN, for plan.yml."
  value       = aws_iam_role.github_plan.arn
}

output "github_deploy_role_arn" {
  description = "Repository variable AWS_DEPLOY_ROLE_ARN, for deploy.yml."
  value       = aws_iam_role.github_deploy.arn
}
