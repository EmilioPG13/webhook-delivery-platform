# The expensive part of the demo: a VPC, an EKS cluster and its nodes. Everything here exists only while
# demo_window is true, so the data layer (queue, secrets, roles) can stay applied between demos at almost no cost.
# Turning the window off and applying is the destroy step for this half; infra/README.md has the checklist.
#
# No NAT gateway and no load balancer. The nodes sit in public subnets so they can pull images and reach SQS and
# Secrets Manager; nothing listens on the internet (security groups allow no inbound traffic from outside the
# cluster) and the API is used through kubectl port-forward. A real deployment would use private subnets and a NAT.
locals {
  window       = var.demo_window ? 1 : 0
  cluster_name = "webhooks-demo"
  # Two zones, because an RDS subnet group needs them even for a single database instance.
  zones = slice(data.aws_availability_zones.available.names, 0, 2)
}

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

# ---- Network ---------------------------------------------------------------------------------------------------------

resource "aws_vpc" "demo" {
  count = local.window

  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.cluster_name }
}

resource "aws_internet_gateway" "demo" {
  count = local.window

  vpc_id = aws_vpc.demo[0].id

  tags = { Name = local.cluster_name }
}

resource "aws_subnet" "public" {
  count = var.demo_window ? length(local.zones) : 0

  vpc_id                  = aws_vpc.demo[0].id
  availability_zone       = local.zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  map_public_ip_on_launch = true

  tags = { Name = "${local.cluster_name}-public-${local.zones[count.index]}" }
}

resource "aws_route_table" "public" {
  count = local.window

  vpc_id = aws_vpc.demo[0].id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.demo[0].id
  }

  tags = { Name = "${local.cluster_name}-public" }
}

resource "aws_route_table_association" "public" {
  count = var.demo_window ? length(local.zones) : 0

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public[0].id
}

# ---- Control plane ---------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "eks_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  count = local.window

  name               = "${local.cluster_name}-cluster"
  assume_role_policy = data.aws_iam_policy_document.eks_trust.json
}

resource "aws_iam_role_policy_attachment" "cluster" {
  count = local.window

  role       = aws_iam_role.cluster[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "demo" {
  count = local.window

  name     = local.cluster_name
  role_arn = aws_iam_role.cluster[0].arn
  # null lets EKS use its current default version.
  version = var.kubernetes_version

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true
  }

  vpc_config {
    subnet_ids              = aws_subnet.public[*].id
    endpoint_public_access  = true
    endpoint_private_access = false
    public_access_cidrs     = var.api_access_cidrs
  }

  depends_on = [aws_iam_role_policy_attachment.cluster]
}

# ---- Nodes -----------------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  count = local.window

  name               = "${local.cluster_name}-node"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = var.demo_window ? toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
  ]) : toset([])

  role       = aws_iam_role.node[0].name
  policy_arn = "arn:aws:iam::aws:policy/${each.value}"
}

# IMDSv2 only, with a hop limit of 1: a pod cannot reach the instance metadata service through the node's network
# namespace, so a webhook URL that tricked the worker into asking for 169.254.169.254 would get nothing. The cluster's
# own components run on the host network and are not affected. (Defence in depth: the SSRF guard and the worker's
# NetworkPolicy come first.)
resource "aws_launch_template" "nodes" {
  count = local.window

  name_prefix = "${local.cluster_name}-"

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_size = 20
      volume_type = "gp3"
      encrypted   = true
    }
  }
}

resource "aws_eks_node_group" "demo" {
  count = local.window

  cluster_name    = aws_eks_cluster.demo[0].name
  node_group_name = "main"
  node_role_arn   = aws_iam_role.node[0].arn
  subnet_ids      = aws_subnet.public[*].id
  instance_types  = [var.node_instance_type]
  ami_type        = "AL2023_x86_64_STANDARD"

  scaling_config {
    desired_size = var.node_count
    min_size     = var.node_count
    max_size     = var.node_count
  }

  launch_template {
    id      = aws_launch_template.nodes[0].id
    version = aws_launch_template.nodes[0].latest_version
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# ---- Add-ons ---------------------------------------------------------------------------------------------------------

# The VPC CNI enforces NetworkPolicy only when asked to. Without this the worker's egress policy would be accepted
# by the API and then ignored.
resource "aws_eks_addon" "vpc_cni" {
  count = local.window

  cluster_name         = aws_eks_cluster.demo[0].name
  addon_name           = "vpc-cni"
  configuration_values = jsonencode({ enableNetworkPolicy = "true" })

  depends_on = [aws_eks_node_group.demo]
}

resource "aws_eks_addon" "pod_identity_agent" {
  count = local.window

  cluster_name = aws_eks_cluster.demo[0].name
  addon_name   = "eks-pod-identity-agent"

  depends_on = [aws_eks_node_group.demo]
}

resource "aws_eks_addon" "coredns" {
  count = local.window

  cluster_name = aws_eks_cluster.demo[0].name
  addon_name   = "coredns"

  depends_on = [aws_eks_node_group.demo]
}

resource "aws_eks_addon" "kube_proxy" {
  count = local.window

  cluster_name = aws_eks_cluster.demo[0].name
  addon_name   = "kube-proxy"

  depends_on = [aws_eks_node_group.demo]
}

# ---- Pod Identity ----------------------------------------------------------------------------------------------------

# Each role of the application gets its own IAM role through its ServiceAccount, which the Helm chart creates. Pod
# Identity needs no OIDC provider, which matters here: this AWS project's policies deny creating one.
resource "aws_eks_pod_identity_association" "api" {
  count = local.window

  cluster_name    = aws_eks_cluster.demo[0].name
  namespace       = "webhooks"
  service_account = "webhook-platform-api"
  role_arn        = aws_iam_role.api.arn

  depends_on = [aws_eks_addon.pod_identity_agent]
}

resource "aws_eks_pod_identity_association" "worker" {
  count = local.window

  cluster_name    = aws_eks_cluster.demo[0].name
  namespace       = "webhooks"
  service_account = "webhook-platform-worker"
  role_arn        = aws_iam_role.worker.arn

  depends_on = [aws_eks_addon.pod_identity_agent]
}
