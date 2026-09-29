# Alarms: CloudWatch watches three things and emails alert_email when one
# goes wrong. The ECS logs themselves are in the log group in ecs.tf.
#
# AWS sends a "Subscription Confirmation" email to alert_email after the
# first apply; alarms are only delivered once it is confirmed.

resource "aws_sns_topic" "alerts" {
  name = "${var.name}-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  alarm_actions = [aws_sns_topic.alerts.arn]
}

# ---------------------------------------------------------------------------
# 1. API errors: the share of requests answered with a 5xx
# ---------------------------------------------------------------------------
# A rate, not a count, so a busy minute with one error does not page anyone.
# It counts two kinds of 5xx: the API's own (HTTPCode_Target_5XX_Count), and
# the load balancer's (HTTPCode_ELB_5XX_Count) -- a 503 when no healthy task
# is running shows up only in the second.
resource "aws_cloudwatch_metric_alarm" "api_5xx_rate" {
  alarm_name          = "${var.name}-api-5xx-rate"
  alarm_description   = "More than 5% of API requests failed with a server error for 10 minutes."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 5
  evaluation_periods  = 2
  # No traffic is not a failure.
  treat_missing_data = "notBreaching"
  alarm_actions      = local.alarm_actions
  ok_actions         = local.alarm_actions

  metric_query {
    id          = "rate"
    label       = "5xx responses, % of requests"
    expression  = "IF(requests > 0, 100 * (target5xx + elb5xx) / requests, 0)"
    return_data = true
  }

  metric_query {
    id = "target5xx"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      period      = 300
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.api.arn_suffix }
    }
  }

  metric_query {
    id = "elb5xx"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_ELB_5XX_Count"
      period      = 300
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.api.arn_suffix }
    }
  }

  metric_query {
    id = "requests"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      period      = 300
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.api.arn_suffix }
    }
  }
}

# ---------------------------------------------------------------------------
# 2. ECS CPU: the task is saturated
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "api_cpu" {
  alarm_name          = "${var.name}-api-cpu"
  alarm_description   = "The API tasks averaged over 80% CPU for 10 minutes."
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    ClusterName = aws_ecs_cluster.main.name
    ServiceName = aws_ecs_service.api.name
  }
}

# ---------------------------------------------------------------------------
# 3. RDS storage: running out of disk
# ---------------------------------------------------------------------------
# Readings accumulate forever (no retention yet -- enhancement E9), so this is
# the alarm most likely to fire first under a long load test.
resource "aws_cloudwatch_metric_alarm" "db_free_storage" {
  alarm_name          = "${var.name}-db-free-storage"
  alarm_description   = "The database has less than 2 GB of free storage."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 2 * 1024 * 1024 * 1024 # bytes
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
}
