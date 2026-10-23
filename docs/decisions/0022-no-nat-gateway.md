# 0022 - No NAT gateway: the API tasks get public IPs

**Status:** Accepted, 2026-09-29 (recorded 2026-10-23)
**Applies to:** `infra/terraform/network.tf`, `ecs.tf`

## Context

The API runs as Fargate tasks that have to reach three AWS services over the
internet-facing endpoints: ECR, to pull their image; Secrets Manager, to read
the database password and the admin token at start; and CloudWatch Logs. The
database needs no internet access at all.

The textbook layout puts the tasks in private subnets and gives them a way out
through a **NAT gateway**. A NAT gateway costs about $35 a month per
availability zone before any data, and the stack spans two zones. That is more
than the rest of the stack put together (roughly $45-60 a month), for a demo
that is destroyed between showings. The spec rules it out for cost and asks for
the trade-off to be recorded.

## Decision

- **No NAT gateway.** The tasks run in **public subnets with public IPs**
  (`assign_public_ip = true`), so they reach ECR, Secrets Manager and
  CloudWatch Logs directly through the internet gateway.
- **Their security group accepts inbound traffic only from the load balancer's
  security group,** on port 8080. A public IP is then an address the internet
  can see but cannot connect to.
- **RDS and the internal load balancer stay in private subnets,** with no route
  to the internet in either direction.

## Alternatives

**NAT gateway, one per zone.** The standard answer, and the right one for
production. Rejected for cost: about $70 a month for two zones.

**One NAT gateway shared by both zones.** Halves the cost, but a zone outage
then cuts both zones off, and it is still about $35 a month.

**VPC endpoints instead of NAT.** S3's gateway endpoint is free, but ECR (two
endpoints), Secrets Manager and CloudWatch Logs need interface endpoints, at
about $7-8 a month each per zone: around $60 a month for two zones. They would
be the better choice than NAT if the tasks must never touch the internet.

**A NAT instance** (a small EC2 instance doing NAT). Cheap, but it is a server
to patch and a single point of failure, which is the operational work Fargate
exists to avoid.

## Consequences

- Each task's public IPv4 address costs about $3.60 a month, far less than NAT.
- **Inbound, the tasks are as closed as they would be in a private subnet:**
  only the load balancer can connect. **Outbound, they are not:** a compromised
  task could reach anything on the internet. A NAT gateway would not change
  that by itself, because it allows any outbound traffic too. Restricting it
  needs egress rules or VPC endpoints, either way.
- If the stack ever runs real data, the move is to private subnets plus VPC
  endpoints, or NAT. Nothing in the application changes; only `network.tf`,
  `ecs.tf` and the security groups do.
