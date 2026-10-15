terraform {

  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {

    aws = {
      source = "hashicorp/aws", version = "6.8.0"
    }

  }
  # Configure a remote encrypted state backend with locking before using production credentials.

}
provider "aws" {
  region = "sa-east-1"
}
