# A monthly cost budget, emailed to alert_email. README: "Create this
# first." The first apply targets this resource alone, so the budget exists
# before anything that costs money:
#   terraform apply -target=aws_budgets_budget.monthly
#
# A budget never stops or blocks anything -- it only makes sure someone finds
# out. The real cost control is `terraform destroy` when the stack is not
# being demoed.

resource "aws_budgets_budget" "monthly" {
  name         = "${var.name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Early warning: actual spend has reached 80% of the budget.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  # Earlier warning: AWS forecasts the month will end over budget.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}
