# The container registry for the API image. deploy.yml pushes one image per
# commit to main, tagged with the commit SHA; ECS pulls from here.

resource "aws_ecr_repository" "api" {
  name = "${var.name}-api"

  # A tag can never be overwritten: the image called <sha> is always exactly
  # the build of that commit, so a rollback to an older SHA runs what it ran
  # before.
  image_tag_mutability = "IMMUTABLE"

  # Scans each pushed image for known vulnerabilities in its OS packages and
  # libraries; results show in the ECR console.
  image_scanning_configuration {
    scan_on_push = true
  }

  # Lets `terraform destroy` delete the repository even with images in it.
  # For a demo stack that is destroyed when idle this is the point; a
  # long-lived production registry would leave it off.
  force_delete = true
}

# Keep the ten newest images and delete the rest, so storage does not grow
# with every commit. Ten is plenty to roll back to.
resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the 10 most recent images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
