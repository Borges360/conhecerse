data "aws_iam_policy_document" "clinical_transport" {
  statement {
    sid       = "DenyPlaintextTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.clinical.arn, "${aws_s3_bucket.clinical.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}
resource "aws_s3_bucket_policy" "clinical" {
  bucket = aws_s3_bucket.clinical.id
  policy = data.aws_iam_policy_document.clinical_transport.json
}
resource "aws_s3_bucket_lifecycle_configuration" "quarantine" {
  bucket = aws_s3_bucket.clinical.id
  rule {
    id     = "ExpireUnvalidatedQuarantine"
    status = "Enabled"
    filter { prefix = "quarantine/" }
    expiration { days = 1 }
    noncurrent_version_expiration { noncurrent_days = 1 }
    abort_incomplete_multipart_upload { days_after_initiation = 1 }
  }
}
