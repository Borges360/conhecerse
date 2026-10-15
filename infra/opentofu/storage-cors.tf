variable "application_origin" {
  type = string
  validation {
    condition     = startswith(var.application_origin, "https://")
    error_message = "Informe a origem HTTPS exata da aplicação para os uploads privados."
  }
}
resource "aws_s3_bucket_cors_configuration" "clinical" {
  bucket = aws_s3_bucket.clinical.id
  cors_rule {
    allowed_methods = ["PUT"]
    allowed_origins = [var.application_origin]
    allowed_headers = ["content-type", "x-amz-checksum-*", "x-amz-sdk-checksum-algorithm"]
    expose_headers  = ["ETag"]
    max_age_seconds = 600
  }
}
