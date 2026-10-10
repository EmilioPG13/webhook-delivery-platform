# State in the bucket that infra/bootstrap creates. The bucket name carries the account id, so it comes from the
# gitignored backend.hcl: terraform init -backend-config=backend.hcl
terraform {
  backend "s3" {
    key          = "demo/terraform.tfstate"
    use_lockfile = true
  }
}
