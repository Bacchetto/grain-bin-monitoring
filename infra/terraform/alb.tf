# The load balancer in front of the API tasks.
#
# INTERNAL: it has no public address at all, and sits in the private
# subnets. CloudFront reaches it privately through a "VPC origin"
# (frontend.tf), so the only way to the API from the internet is through
# CloudFront, over HTTPS. That is also why /actuator/prometheus, which needs
# no credentials, is never public (enhancement E15).

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Internal ALB: inbound only from CloudFront"
  vpc_id      = aws_vpc.main.id
}

# AWS's list of the IP ranges CloudFront uses to reach origins, kept up to
# date by AWS. Allowing it -- rather than 0.0.0.0/0 -- means only CloudFront
# can connect.
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from CloudFront only"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "alb_to_api" {
  security_group_id            = aws_security_group.alb.id
  description                  = "To the API tasks"
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_lb" "api" {
  name               = "${var.name}-api"
  load_balancer_type = "application"
  internal           = true
  subnets            = aws_subnet.private[*].id
  security_groups    = [aws_security_group.alb.id]

  # Drop requests with malformed headers rather than pass them to the API.
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "api" {
  name     = "${var.name}-api"
  port     = 8080
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id
  # Fargate tasks are registered by IP address, not by instance.
  target_type = "ip"

  # The same endpoint the Docker HEALTHCHECK uses. A task is taken out of
  # service after 3 failed checks and back in after 2 good ones.
  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # When a task is replaced, give in-flight requests 30 seconds to finish
  # (the default is 300, which slows every deploy for nothing).
  deregistration_delay = 30
}

# HTTP between CloudFront and the ALB is inside AWS's network (the VPC origin
# connection), not the public internet; viewers reach CloudFront over HTTPS.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"

  # Anything that is not the API gets a 404 from the ALB itself, so
  # /actuator/*, among others, is never forwarded.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}
