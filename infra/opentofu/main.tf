variable "name" {
  type    = string
  default = "conhecerse"
}
variable "vpc_id" {
  type = string
}
variable "private_subnet_ids" {
  type = list(string)
}
variable "public_subnet_ids" {
  type = list(string)
}
variable "application_security_group_id" {
  type = string
}
variable "video_ami" {
  type        = string
  description = "Hardened AMI with Docker, AWS CLI and SSM; do not use an unreviewed image."
}
variable "video_config_secret_arns" {
  type        = list(string)
  description = "Exactly two Secrets Manager references containing LiveKit config incl. TURN/TLS certificates; no values in Git."
}
variable "redis_auth_token" {
  type      = string
  sensitive = true
}
variable "release_approved" {
  type    = bool
  default = false
}

resource "aws_kms_key" "clinical" {

  description             = "Conhecer-se clinical object encryption; OpenBao seals may use a dedicated separate key"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  lifecycle {
    prevent_destroy = true
  }

}
resource "aws_kms_key" "backup" {

  description             = "Conhecer-se segregated backup encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  lifecycle {
    prevent_destroy = true
  }

}
resource "aws_s3_bucket" "clinical" {

  bucket_prefix       = "${var.name}-clinical-"
  object_lock_enabled = true
  lifecycle {
    prevent_destroy = true
  }

}
resource "aws_s3_bucket_public_access_block" "clinical" {

  bucket                  = aws_s3_bucket.clinical.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true

}
resource "aws_s3_bucket_versioning" "clinical" {

  bucket = aws_s3_bucket.clinical.id
  versioning_configuration {
    status = "Enabled"
  }

}
resource "aws_s3_bucket_server_side_encryption_configuration" "clinical" {

  bucket = aws_s3_bucket.clinical.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.clinical.arn
    }
  }

}
resource "aws_s3_bucket" "backup" {

  bucket_prefix       = "${var.name}-backup-"
  object_lock_enabled = true
  lifecycle {
    prevent_destroy = true
  }

}
resource "aws_s3_bucket_public_access_block" "backup" {

  bucket                  = aws_s3_bucket.backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true

}
resource "aws_s3_bucket_versioning" "backup" {

  bucket = aws_s3_bucket.backup.id
  versioning_configuration {
    status = "Enabled"
  }

}
resource "aws_s3_bucket_object_lock_configuration" "backup" {

  bucket = aws_s3_bucket.backup.id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = 35
    }
  }
  depends_on = [aws_s3_bucket_versioning.backup]

}
resource "aws_s3_bucket_server_side_encryption_configuration" "backup" {

  bucket = aws_s3_bucket.backup.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.backup.arn
    }
  }

}
resource "aws_db_subnet_group" "db" {
  name       = var.name
  subnet_ids = var.private_subnet_ids
}
resource "aws_security_group" "db" {
  name_prefix = "${var.name}-db-"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_ingress_rule" "postgres" {

  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = var.application_security_group_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"

}
resource "aws_db_instance" "db" {

  identifier                  = var.name
  engine                      = "postgres"
  engine_version              = "17.11"
  instance_class              = "db.t4g.medium"
  allocated_storage           = 100
  max_allocated_storage       = 500
  storage_encrypted           = true
  kms_key_id                  = aws_kms_key.clinical.arn
  multi_az                    = true
  db_subnet_group_name        = aws_db_subnet_group.db.name
  vpc_security_group_ids      = [aws_security_group.db.id]
  publicly_accessible         = false
  db_name                     = "conhecerse"
  username                    = "platform_migrator"
  manage_master_user_password = true
  backup_retention_period     = 35
  deletion_protection         = true
  skip_final_snapshot         = false
  final_snapshot_identifier   = "${var.name}-final"
  auto_minor_version_upgrade  = false
  lifecycle {
    prevent_destroy = true
  }

}
resource "aws_elasticache_subnet_group" "video" {
  name       = "${var.name}-video"
  subnet_ids = var.private_subnet_ids
}
resource "aws_security_group" "video" {
  name_prefix = "${var.name}-video-"
  vpc_id      = var.vpc_id
}
resource "aws_security_group" "redis" {
  name_prefix = "${var.name}-redis-"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_ingress_rule" "redis" {

  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = aws_security_group.video.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"

}
resource "aws_elasticache_replication_group" "video" {

  replication_group_id       = "${var.name}-video"
  description                = "LiveKit coordination only; core jobs stay in PostgreSQL"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = "cache.t4g.small"
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = var.redis_auth_token
  subnet_group_name          = aws_elasticache_subnet_group.video.name
  security_group_ids         = [aws_security_group.redis.id]

}
resource "aws_vpc_security_group_ingress_rule" "video_tcp" {

  for_each          = toset(["5349", "7881"])
  security_group_id = aws_security_group.video.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.key)
  to_port           = tonumber(each.key)

}
resource "aws_vpc_security_group_ingress_rule" "video_udp" {

  security_group_id = aws_security_group.video.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "udp"
  from_port         = 50000
  to_port           = 60000

}
resource "aws_vpc_security_group_ingress_rule" "turn_udp" {

  security_group_id = aws_security_group.video.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "udp"
  from_port         = 3478
  to_port           = 3478

}
resource "aws_vpc_security_group_egress_rule" "video" {
  security_group_id = aws_security_group.video.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
data "aws_iam_policy_document" "video_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}
resource "aws_iam_role" "video" {
  name               = "${var.name}-video"
  assume_role_policy = data.aws_iam_policy_document.video_trust.json
}
resource "aws_iam_role_policy" "video_config" {

  role = aws_iam_role.video.id
  policy = jsonencode({
    Version = "2012-10-17", Statement = [{
      Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = var.video_config_secret_arns
    }]
  })

}
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.video.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
resource "aws_iam_instance_profile" "video" {
  name = "${var.name}-video"
  role = aws_iam_role.video.name
}
resource "aws_instance" "video" {

  count                       = 2
  ami                         = var.video_ami
  instance_type               = "c7i.large"
  subnet_id                   = var.public_subnet_ids[count.index]
  associate_public_ip_address = true
  vpc_security_group_ids      = [aws_security_group.video.id]
  iam_instance_profile        = aws_iam_instance_profile.video.name
  metadata_options {
    http_tokens = "required"
  }
  root_block_device {
    encrypted   = true
    volume_size = 30
  }
  user_data = templatefile("${path.module}/video-init.sh.tftpl", {
    secret_arn = var.video_config_secret_arns[count.index]
  })
  lifecycle {

    precondition {
      condition     = var.release_approved
      error_message = "Public video nodes require approved network, clinical protocol and E2EE evidence."
    }
    precondition {
      condition     = length(var.video_config_secret_arns) == 2 && length(var.public_subnet_ids) >= 2
      error_message = "Two independent public subnets and two configuration secret references are required."
    }

  }
  tags = {
    Name = "${var.name}-video-${count.index + 1}"
  }

}
output "postgres_endpoint" {
  value = aws_db_instance.db.address
}
output "clinical_bucket" {
  value = aws_s3_bucket.clinical.id
}
output "backup_bucket" {
  value = aws_s3_bucket.backup.id
}
output "video_public_ips" {
  value = aws_instance.video[*].public_ip
}
output "redis_endpoint" {
  value = aws_elasticache_replication_group.video.primary_endpoint_address
}
