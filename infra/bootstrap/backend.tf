# The state lives in the bucket this configuration creates, so the very first apply runs without this file
# (rename it to backend.tf.off), and then `terraform init -migrate-state -backend-config=backend.hcl` moves the
# state here. See README.md. The bucket name carries the account id, so it comes from the gitignored
# backend.hcl instead of this file.
terraform {
  backend "s3" {
    key          = "bootstrap/terraform.tfstate"
    use_lockfile = true
  }
}
