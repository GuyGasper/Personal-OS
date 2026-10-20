# Automated OKE deployment

A successful push to `main` now follows **build/test → publish → deploy → HTTPS
verification**. Pull requests and other branches only build/test. Manual runs
publish when **publish** is checked; leave **deploy** checked to deploy afterward.
A manual run with publish unchecked only verifies the application.

The deployment job consumes the immutable image digests published by that same
run. It does not rebuild images or apply infrastructure manifests. It patches the
API and frontend container images, waits for both deployments, and tests HTTPS
against the existing load balancer for both domains.

## Production settings

Open [Personal-OS production settings](https://github.com/GuyGasper/Personal-OS/settings/environments/24750609737/edit).
The existing registry settings stay in place. Add these deployment settings:

| Type | Name | Value |
| --- | --- | --- |
| Variable | `OCI_REGION` | `us-ashburn-1` |
| Variable | `OKE_CLUSTER_ID` | The `personal-os` cluster OCID from OCI or infrastructure outputs |
| Variable, optional | `OKE_ENDPOINT_NSG_ID` | Only needed if the API endpoint has multiple NSGs |
| Secret | `OCI_TENANCY_OCID` | Tenancy OCID from the OCI API credential configuration |
| Secret | `OCI_USER_OCID` | User OCID for the deployment identity |
| Secret | `OCI_FINGERPRINT` | Fingerprint of that user's registered API signing key |
| Secret | `OCI_PRIVATE_KEY` | Complete PEM contents of that API signing private key |

The OCI API credential configuration is usually `~/.oci/config`; its `key_file`
entry identifies the API private key. These are the same setting names used by
the infrastructure workflow. Secrets in another repository are not inherited.
Use an unencrypted API signing key; this workflow does not accept a passphrase.

The API signing key authenticates OCI requests. The OCIR auth token publishes
images. The TLS private key handles HTTPS at the load balancer. These are separate
credentials. The TLS key and kubeconfig do not need to be uploaded to GitHub.
The existing `personal-os-tls` and `ocir-pull` Kubernetes secrets are reused.

Keep the production environment restricted to `main`. Protect main and review
workflow changes because production credentials are available to its jobs.

## OCI and Kubernetes access

The deployment identity needs to read the selected cluster and add, list, and
remove security rules in the NSG attached to its API endpoint. Limit OCI IAM
permissions to the intended compartment, cluster, and endpoint NSG. Existing
infrastructure credentials may already have these permissions.

A dedicated deployment user also needs Kubernetes access to the existing two
Deployments and web Service. An administrator can apply the namespace Role:

```bash
export KUBECONFIG="$HOME/.kube/personal-os"
kubectl --context '<admin-context>' apply -f kubernetes/deployer-role.yaml
```

Copy `kubernetes/deployer-rolebinding.yaml.example` to an ignored local file,
replace its subject with the same `OCI_USER_OCID` used by Actions, and apply it
using the administrator context. This grants image patching and rollout watching
for the two named Deployments, plus reading the named Service. It grants no
access to Kubernetes Secrets. If this OCI user already has sufficient cluster
permissions, an additional binding is unnecessary.

See Oracle's [OKE access control](https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengaboutaccesscontrol.htm)
and [IAM policy reference](https://docs.oracle.com/en-us/iaas/Content/Identity/policyreference/contengpolicyreference.htm)
for the interaction between OCI IAM and Kubernetes RBAC.

## Temporary network access

Each deploy job discovers its public IPv4 address and creates one stateful
inbound TCP 6443 rule from that address's `/32` in the API endpoint NSG.
The helper verifies that the NSG belongs to the selected cluster. Existing admin
rules and the load balancer NSG are preserved.

The rule description identifies this repository, Actions run, attempt, and
creation time. An `always()` cleanup step removes that exact rule even if a
previous step fails or the workflow is canceled while the runner remains alive.
Temporary OCI config, API key, and kubeconfig files are removed afterward.

OCI NSG rules have no expiry enforced by this workflow. If GitHub loses the runner
or forcibly terminates it before cleanup, its rule may remain. The next deployment
removes matching rules from this repository older than one hour, checking that
they are still single-address TCP 6443 rules. This is cleanup on the next run,
not a scheduled expiry. After an interrupted run, inspect the API endpoint NSG
for `personal-os-ci:GuyGasper/Personal-OS:` rules and remove only the abandoned
run's rule if immediate cleanup is needed. Cleanup failure makes the job fail.

## Failure and recovery

Production deployment jobs run one at a time. Before deployment, a run checks
that its commit is still the head of main and skips an obsolete release.

Both image patches must pass a server-side dry run before either is applied.
The job records the previous images in the `deployment-<sha>-<attempt>` artifact,
then updates only the named application containers. Resource versions and image
checks reject concurrent modifications.

If patching, rollout, or HTTPS verification fails, the script attempts to restore
both previous image references and waits for recovery. The job still fails so
the failed release is visible in Actions. If someone changed an image outside
this job, rollback leaves that change untouched and reports that manual attention
is required. A runner forcibly terminated during deployment may also need manual
recovery using the saved previous-image artifact.

This is a rolling deployment of two independent workloads; it is not an atomic
switch of both services. Future API/frontend changes must remain compatible
during their rolling updates.

The `release-<sha>` artifact records the intended images. The
`deployment-<sha>-<attempt>` artifact records the previous images. Both are
retained for 30 days. If rollback needs help, use those references with
`kubectl set image` on the corresponding deployment/container and wait for
`kubectl rollout status`. Restore the matching API/frontend pair.

## Local validation

```bash
python3 -m unittest discover -s scripts/tests -v
export KUBECONFIG="$HOME/.kube/personal-os"
python3 scripts/deploy-release.py \
  --release-file .local/release.json --source-sha '<full-commit-sha>' \
  --registry iad.ocir.io --namespace '<oci-namespace>' \
  --context '<selected-context>' --dry-run
```

Removing `--dry-run` deploys that release and runs the HTTPS checks.
`scripts/deploy.sh` remains the bootstrap path for namespace, secrets, and
infrastructure manifests; routine application releases use the new job.
