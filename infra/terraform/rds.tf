# The database: PostgreSQL 16 on the smallest burstable instance, single-AZ,
# in the private subnets, reachable only from the API's tasks.

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "PostgreSQL, reachable only from the API tasks"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_api" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from the API tasks only"
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# Which subnets RDS may place the instance in: the private ones.
resource "aws_db_subnet_group" "main" {
  name       = "${var.name}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier = "${var.name}-db"

  engine = "postgres"
  # The major version only: RDS picks the newest 16.x, and applies minor
  # (bug-fix and security) updates in the maintenance window. Matches the
  # local and test databases (postgres:16-alpine).
  engine_version             = "16"
  auto_minor_version_upgrade = true

  # The smallest burstable class: 2 vCPU (burstable), 1 GB. Graviton (t4g)
  # is cheaper than the equivalent Intel class.
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "grain"
  username = "grain"

  # RDS generates the master password and keeps it in Secrets Manager, in a
  # secret it creates and rotates. Terraform never sees the password; the
  # ECS task reads it from that secret (ecs.tf). README requirement.
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false

  # Single-AZ: a standby in a second zone would double the cost. For a demo,
  # an outage while RDS replaces a failed instance is acceptable.
  multi_az = false

  # --- demo settings: each would be different for real data --------------
  # One day of automated backups -- enough to recover from a bad migration
  # made today.
  backup_retention_period = 1
  # No final snapshot on destroy, and destroy is allowed: the stack is torn
  # down whenever it is not being demoed, and the data is simulated.
  skip_final_snapshot = true
  deletion_protection = false
  # Apply changes now rather than in the next maintenance window.
  apply_immediately = true
}
