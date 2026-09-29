# Bootstrap: the Terraform state bucket

Creates one private, encrypted, versioned S3 bucket that holds the main
stack's Terraform state (`infra/terraform`). **Applied once, by hand, before
anything else**, and then almost never touched.

This config keeps its own state as a local `terraform.tfstate` file, which
git ignores. Keep that file: without it, Terraform no longer knows it created
the bucket. (Losing it is not a disaster -- `terraform import` can re-adopt
the bucket -- but it is easier not to.)

## Apply it (the owner does this)

Run in PowerShell from the repository root, signed in with the **admin**
session.

```powershell
aws sso login --sso-session grain-admin
$env:AWS_PROFILE = "grain-admin"

# Keep Terraform's working folder (.terraform, with the provider programs) on
# the local disk. Windows can lock executables that are run from a network
# drive, which breaks later re-initialisation; see ADR 0021.
$env:TF_DATA_DIR = "$env:LOCALAPPDATA\grain-bin-terraform\bootstrap"

cd infra\bootstrap
terraform init
terraform plan -out bootstrap.tfplan      # read it: 7 to add, 0 to change, 0 to destroy
terraform apply bootstrap.tfplan          # applies exactly the plan you just read
terraform output state_bucket             # note this name for the main stack
cd ..\..

aws sso logout                            # clears every cached token
aws sso login --sso-session grain         # read-only back on
```

Saving the plan to a file and applying *that file* means the apply does
exactly what you reviewed, and nothing that changed in between.

## What it creates

| Resource | Why |
|---|---|
| `aws_s3_bucket.state` | The bucket, named `grain-bin-tfstate-<account>-<region>` so it is globally unique. `prevent_destroy` stops Terraform ever deleting it. |
| Versioning | Every state write keeps the previous version, so a bad one can be rolled back. |
| Encryption (SSE-S3) | State can contain secrets, such as the generated `ADMIN_TOKEN`. |
| Public access block | No ACL or policy can ever make the bucket public. |
| Ownership controls | ACLs off; access decided by IAM and the bucket policy alone. |
| Bucket policy | Refuses any request not made over HTTPS. |
| Lifecycle rule | Old state versions are deleted after 90 days, so the bucket does not grow forever. |

Cost: a few cents a month at most -- state files are kilobytes.
