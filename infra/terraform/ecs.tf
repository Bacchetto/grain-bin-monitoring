# The API: one Fargate task running the image from ECR.
#
# Fargate runs containers without servers to manage -- AWS provides the
# machine; this file only says how much CPU and memory, which image, and what
# configuration to give it.

resource "aws_ecs_cluster" "main" {
  name = var.name

  # Container Insights adds per-task CPU and memory metrics, at extra cost
  # (charged as custom metrics). The service-level CPU metric the alarms use
  # is free without it.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name = "/ecs/${var.name}-api"
  # A week is enough to investigate a problem; longer costs storage.
  retention_in_days = 7
}

# ---------------------------------------------------------------------------
# What the task may do while starting
# ---------------------------------------------------------------------------
# The EXECUTION role is used by ECS itself, before the application starts:
# to pull the image from ECR, write logs, and fetch the secrets that become
# environment variables. The application code gets no AWS permissions at all
# -- it never calls AWS -- so there is no task role.

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "api_execution" {
  name               = "${var.name}-api-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# AWS's standard policy for pulling from ECR and writing to CloudWatch Logs.
resource "aws_iam_role_policy_attachment" "api_execution_standard" {
  role       = aws_iam_role.api_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Read exactly two secrets -- the database credentials RDS manages and the
# admin token -- and nothing else in Secrets Manager.
data "aws_iam_policy_document" "api_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_db_instance.main.master_user_secret[0].secret_arn,
      aws_secretsmanager_secret.admin_token.arn,
    ]
  }
}

resource "aws_iam_role_policy" "api_secrets" {
  name   = "read-api-secrets"
  role   = aws_iam_role.api_execution.id
  policy = data.aws_iam_policy_document.api_secrets.json
}

# ---------------------------------------------------------------------------
# The task
# ---------------------------------------------------------------------------

locals {
  db_secret_arn = aws_db_instance.main.master_user_secret[0].secret_arn
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"

  # 0.5 vCPU and 1 GB. The JVM needs more than the 512 MB minimum to run
  # Spring Boot comfortably; the image sizes its heap to 75% of this.
  cpu    = 512
  memory = 1024

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  execution_role_arn = aws_iam_role.api_execution.arn

  container_definitions = jsonencode([{
    name      = "api"
    image     = "${aws_ecr_repository.api.repository_url}:${var.image_tag}"
    essential = true

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    # Plain configuration. sslmode=require: the connection to RDS is
    # encrypted (RDS for PostgreSQL 16 requires it by default anyway).
    environment = [
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/grain?sslmode=require" },
    ]

    # Secrets: ECS fetches these from Secrets Manager at start-up and passes
    # them in as environment variables, so they never appear in the task
    # definition, the console, or this repository. The ":username::" form
    # picks one JSON key out of the RDS-managed secret.
    secrets = [
      { name = "DB_USER", valueFrom = "${local.db_secret_arn}:username::" },
      { name = "DB_PASSWORD", valueFrom = "${local.db_secret_arn}:password::" },
      { name = "ADMIN_TOKEN", valueFrom = aws_secretsmanager_secret.admin_token.arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.api.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "api"
      }
    }
  }])
}

# ---------------------------------------------------------------------------
# The service: keeps desired_count tasks running, behind the ALB
# ---------------------------------------------------------------------------

resource "aws_security_group" "api" {
  name        = "${var.name}-api"
  description = "API tasks: inbound only from the load balancer"
  vpc_id      = aws_vpc.main.id
}

# The only way in. The tasks have public IPs (no NAT gateway, network.tf),
# but nothing on the internet can use them: this is the only inbound rule.
resource "aws_vpc_security_group_ingress_rule" "api_from_alb" {
  security_group_id            = aws_security_group.api.id
  description                  = "HTTP from the load balancer only"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# Outbound anywhere: ECR and Secrets Manager over the internet, RDS inside
# the VPC.
resource "aws_vpc_security_group_egress_rule" "api_out" {
  security_group_id = aws_security_group.api.id
  description       = "ECR, Secrets Manager, CloudWatch Logs and RDS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_ecs_service" "api" {
  name            = "${var.name}-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  # Startup includes Flyway migrations; don't let the ALB's health check kill
  # a task that is still starting.
  health_check_grace_period_seconds = 120

  # If a new deployment's tasks keep failing to become healthy, stop and roll
  # back to the last working task definition instead of retrying forever.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # The target group must be attached to a listener before a service can use
  # it.
  depends_on = [aws_lb_listener_rule.api]
}
