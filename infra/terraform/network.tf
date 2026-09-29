# The network: one VPC across two availability zones.
#
#   public subnets  -- ECS tasks, with public IPs (see "No NAT gateway" below)
#   private subnets -- RDS and the internal ALB, no route to the internet
#
# NO NAT GATEWAY (README requirement). A NAT gateway lets private resources
# reach the internet without being reachable from it, and costs about $35 a
# month per AZ, before data -- more than the rest of this stack. Instead the
# ECS tasks sit in public subnets with public IPs, so they can pull their
# image from ECR and read Secrets Manager directly. Their security group
# accepts inbound traffic only from the ALB, so the public IP gives the
# internet no way in. Recorded in an ADR.

# Two zones, excluding cac1-az3: CloudFront VPC origins -- how CloudFront
# reaches the internal ALB (frontend.tf) -- are not supported in that zone.
# Zone IDs rather than names, because zone names are shuffled per account
# (your ca-central-1a is not necessarily mine) while IDs are fixed.
data "aws_availability_zones" "available" {
  state            = "available"
  exclude_zone_ids = ["cac1-az3"]
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

resource "aws_vpc" "main" {
  cidr_block = "10.20.0.0/16"

  # Lets RDS's endpoint name resolve inside the VPC.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.name}-vpc" }
}

# The VPC's door to the internet. Used by the public subnets; also required
# for CloudFront VPC origins, although CloudFront does not route through it.
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-igw" }
}

resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  cidr_block        = "10.20.${count.index}.0/24"

  # Public IPs are assigned per task by ECS (assign_public_ip in ecs.tf),
  # not to everything launched here.
  map_public_ip_on_launch = false

  tags = { Name = "${var.name}-public-${local.azs[count.index]}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  cidr_block        = "10.20.${count.index + 10}.0/24"

  tags = { Name = "${var.name}-private-${local.azs[count.index]}" }
}

# Public route table: anything not inside the VPC goes out the internet
# gateway.
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Private route table: only the VPC's own implicit local route. That is what
# makes these subnets private -- nothing in them can reach the internet, or
# be reached from it.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-private" }
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}
