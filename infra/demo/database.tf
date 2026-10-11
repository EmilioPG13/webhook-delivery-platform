# PostgreSQL for the demo window. Same gate as the cluster: it exists only while demo_window is true.
resource "aws_db_subnet_group" "demo" {
  count = local.window

  name       = local.cluster_name
  subnet_ids = aws_subnet.public[*].id
}

# Only the cluster's own security group, which the managed nodes carry, may connect.
resource "aws_security_group" "database" {
  count = local.window

  name        = "${local.cluster_name}-database"
  description = "PostgreSQL from the EKS nodes only"
  vpc_id      = aws_vpc.demo[0].id
}

resource "aws_vpc_security_group_ingress_rule" "database_from_nodes" {
  count = local.window

  security_group_id            = aws_security_group.database[0].id
  referenced_security_group_id = aws_eks_cluster.demo[0].vpc_config[0].cluster_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_instance" "demo" {
  count = local.window

  identifier     = local.cluster_name
  engine         = "postgres"
  engine_version = var.postgres_version
  instance_class = var.database_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "webhooks"
  username = "webhooks"
  # RDS creates the password and keeps it in Secrets Manager, so it never reaches the Terraform state or a file.
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.demo[0].name
  vpc_security_group_ids = [aws_security_group.database[0].id]
  publicly_accessible    = false
  multi_az               = false

  # A demo database that lives a few days: no backups, no final snapshot, nothing left to pay for afterwards.
  backup_retention_period = 0
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true
}
