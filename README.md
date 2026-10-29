# Personal OS

A personal operating system for budgeting, running, and network calculations.

This first release provides a React + TypeScript landing page, a Java 21 / Spring
Boot API, and a live browser-to-API connection check. The three feature areas are
marked **Planned** in the UI. The API connects to private PostgreSQL with Flyway
migrations. A single-athlete Strava backend now imports activity summaries through
an opt-in hourly OKE job and serves private database-backed read endpoints.
See [Strava setup and operations](docs/strava.md). The running UI and application
login are still planned.

## Layout and request flow

- `frontend/`: Vite builds React assets; unprivileged Nginx serves them on 8080.
- `backend/`: Spring Boot API on 8080, running as UID/GID 10001.
- `scripts/`: ARM64 publication, deployment, and HTTPS smoke checks.
- `../OCI-Infrastructure/`: existing Terraform, Kubernetes manifests, and renderer.

```text
Browser → OCI load balancer :443 (TLS) → Nginx :8080
                                        ├── /api/* → Java ClusterIP :8080
                                        └── /*     → React assets / SPA fallback
```

Authoritative application and infrastructure manifests stay in the infrastructure repo. Its Nginx ConfigMap
overrides the image's bundled local configuration, preserves the API prefix, and
sets forwarded HTTPS headers. Both containers match its users, probes, ports,
read-only filesystems, and writable `/tmp` volumes.

## Run locally

With Docker and Compose:

```bash
docker compose up --build
# App: http://localhost:8080
# API health (localhost only): http://localhost:8081/health/ready
docker compose down
```

Compose uses the host's architecture and starts PostgreSQL 16 with a named data volume.
Only localhost can reach its port 5434. `docker compose down` retains local data;
add `--volumes` only to reset this disposable database. Checked-in passwords are
local test fixtures and are unrelated to production credentials.

For hot reload, install Java 21, Maven 3.9+, and Node 24, then start the local
database and use two terminals:

```bash
docker compose up -d --wait database
# Disposable local credentials only; production uses Kubernetes Secrets.
export JDBC_URL='jdbc:postgresql://localhost:5434/personal_os'
export DB_USERNAME=personal_os
export DB_PASSWORD=local-personal-os-only
mvn -f backend/pom.xml spring-boot:run
```

```bash
cd frontend
npm ci
npm run dev
# http://localhost:5173; Vite proxies /api to localhost:8080
```

## API contract

| Route | Purpose |
| --- | --- |
| `GET /api/status` | Service name, UP status, version, UTC time, and planned modules |
| `GET /api`, `GET /api/` | Same status response |
| `GET /api/strava/activities` | Private, paginated locally stored Strava activities |
| `GET /api/strava/sync` | Private sync timestamps, activity count and safe error codes |
| `GET /health/live` | Kubernetes liveness; independent of external systems |
| `GET /health/ready` | Kubernetes readiness; 503 when refusing traffic or the database is unavailable |

Health groups use Spring Boot's availability lifecycle. They are accessed directly
by Kubernetes, outside the Nginx `/api/` proxy. Only status-only health information
is exposed; environment and management endpoints are disabled. The `ready` group
also checks PostgreSQL; `live` remains independent of the database.
`APP_VERSION` can override the API's default `0.1.0`.

## Build and test

```bash
mvn -B -ntp -f backend/pom.xml verify
cd frontend
npm ci
npm run build
```

Backend tests require Docker and start disposable PostgreSQL containers with
Testcontainers. They verify real application-role migrations and writes, schema
ownership, preservation of the infrastructure restore probe, repeat migration
safety, the HTTP API contract, and readiness/liveness behavior during a database
outage and recovery. Container builds package the API; CI runs `mvn verify`
separately before building either image.

## PostgreSQL

Production uses the private shared PostgreSQL VM's `personal_os` database and
ordinary `personal_os` login. The `database-credentials` Secret must already be
installed in the `personal-os` namespace using the infrastructure repository's
`scripts/install-postgres-secrets.py`. No production database passwords are needed
in GitHub settings or committed files.

| Secret key | Purpose |
| --- | --- |
| `JDBC_URL` | Private JDBC URL with `sslmode=verify-full&sslrootcert=/etc/database/root.crt` |
| `DB_USERNAME` | `personal_os` |
| `DB_PASSWORD` | Generated application-role password |
| `DB_SSL_ROOT_CERT` | PostgreSQL CA mounted read-only at `/etc/database/root.crt` |

The API reads required individual Secret keys; Kubernetes resolves them without
giving the deployment identity access to credential values. Missing keys or the
CA prevent container startup. Invalid credentials, TLS verification failures, or
failed migrations prevent the API from becoming ready, and deployment rolls back.
The normal pipeline patches these references and the CA mount with the API image.

Flyway creates and migrates the application-owned `personal_os` schema on startup.
It preserves `public.infrastructure_restore_probe`, used by infrastructure backup
checks. V1 initializes the schema and its migration history; V2 adds Strava
activities and encrypted sync credentials. Budgeting and network feature tables
will be added with their features. Application queries
use this schema by default, with a pool capped at five connections per API replica.

Add future migrations under `backend/src/main/resources/db/migration` using names
such as `V2__description.sql`. Never edit an applied migration or enable Flyway
clean/baseline against production. Image rollback does not reverse migrations, so
new migrations must remain compatible with the previous API version. Backups and
restores remain managed by shared infrastructure.

## GitHub Actions CI/CD

The **CI / CD** workflow runs on pushes, pull requests, and manual dispatches.
It calls one shared verification workflow on `ubuntu-24.04-arm`, matching the
OKE workers' architecture:

1. Compile Java 21 and run the PostgreSQL migration and API HTTP/lifecycle tests.
2. Install locked frontend dependencies, type-check, and build React.
3. Validate scripts and run publication, deployment/rollback, and network-access tests.
4. Build both native `linux/arm64` container images.
5. Start the containers with read-only filesystems. Check API liveness/readiness,
   Nginx health, API proxying, static JavaScript, client routes, and API 404s.
6. Run Playwright against those containers in desktop and mobile Chromium:
   connection, retry, client routes, failure recovery, and invalid responses.

On a successful push to **main**, publishing runs automatically in the
`production` environment. It loads the exact images tested above, checks their
commit, archive checksum, architecture, and image identities, then tags and pushes
them to the existing private OCIR repositories. It does not rebuild after testing.
Pull requests and other branches run verification without registry credentials.
A failed verification prevents publication and deployment. After publication, the
deployment job temporarily permits the runner's own IP to reach OKE, updates both
application image digests in order (API rollout, then frontend rollout), wires the
API to its database Secret and CA mount, verifies
HTTPS, and removes the temporary rule. Failed rollout or HTTPS checks trigger restoration of the previous
images.

### One-time GitHub configuration

In **this application repository**, open **Settings → Environments**, create
`production`, and configure:

| Type | Name | Value |
| --- | --- | --- |
| Variable | `OCIR_REGISTRY` | Regional registry hostname, e.g. `iad.ocir.io` |
| Variable | `OCI_NAMESPACE` | Tenancy's OCIR namespace |
| Secret | `OCIR_USERNAME` | Full registry login, e.g. `<namespace>/<user>` or `<namespace>/<domain>/<user>` |
| Secret | `OCIR_AUTH_TOKEN` | OCI auth token for a user allowed to push both repositories |

The repositories are `personal-os/api` and `personal-os/frontend`. An OCI API
signing key is different from an OCIR auth token. Secrets configured only in
`OCI-Infrastructure` do not automatically become available to this repository.
Keep the TLS private key out of GitHub; these workflows reuse the existing
Kubernetes TLS secret.

Automated deployment additionally needs production variables `OCI_REGION` and
`OKE_CLUSTER_ID`, and secrets `OCI_TENANCY_OCID`, `OCI_USER_OCID`,
`OCI_FINGERPRINT`, and `OCI_PRIVATE_KEY`. See
[deployment setup and recovery](docs/deployment.md) for values, access, and cleanup.

Restrict the `production` environment's deployment branches to `main`. Any
environment approval rules you configure also apply before publication. To make
passing CI a merge requirement, require the workflow's **Java, React, and ARM64
containers** check in a branch ruleset.

After pushing these files, open **Actions → CI / CD**. Pull requests and pushes
run tests automatically; pushes to `main` also publish and deploy. **Run workflow**
can rerun verification; check **publish** to publish a tested `main` commit and
leave **deploy** checked to deploy it afterward. Configure both the registry and
deployment settings before enabling the new job.

### Reports and release artifacts

Each run uploads Java test reports, a Playwright HTML report and failure traces,
and container logs as `test-reports-<run-id>-<attempt>` (retained for 7 days).
Publication runs additionally transfer a tested ARM64 image bundle between jobs
(retained for 2 days) and upload `release-<commit-sha>` containing
`release.json` (retained for 30 days).

Published tags use the full commit SHA. The release JSON contains immutable
registry digest references for both images. The deployment job consumes this
artifact automatically and saves previous image references in
`deployment-<commit-sha>-<attempt>` for recovery (retained for 30 days).

Routine deployments reuse the load balancer, TLS/pull secrets, and existing
workload configuration. The bootstrap script below still handles initial
manifest and secret creation. See [automated deployment](docs/deployment.md)
for temporary network access, rollback behavior, and interrupted-run cleanup.

### Run the browser checks locally

With both containers running via `docker compose up --build -d`:

```bash
cd frontend
npm ci
npx playwright install chromium
npm run test:e2e
# Optional: npx playwright show-report
```

The default target is `http://127.0.0.1:8080`. To test the Vite development
server instead, set `PLAYWRIGHT_BASE_URL=http://127.0.0.1:5173`.

The publishing script is also available for an already-tested bundle downloaded
from a successful run:

```bash
export REGISTRY=iad.ocir.io
export OCI_NAMESPACE='<tenancy-namespace>'
export SOURCE_SHA='<full-commit-sha-of-the-tested-run>'
export IMAGE_BUNDLE_DIR='<directory-containing-bundle.json-and-images.tar>'
docker login "$REGISTRY"
bash scripts/publish-images.sh
# Writes .local/release.json with the published image digests.
```

## Configure secrets and deploy to OKE

Required: OCI CLI, kubectl, Python 3, OpenSSL, a reachable OKE API endpoint, and
Terraform initialized against the infrastructure backend (or saved Terraform
outputs). Use a dedicated read-only OCIR credential for the cluster. A Docker
config backed by a desktop credential helper cannot be used as a Kubernetes pull
secret: create a dedicated config with inline registry auth.

```bash
# A dedicated login stores the auth entry outside the repository.
docker --config "$HOME/.config/personal-os-pull" login "$REGISTRY"
export DOCKER_CONFIG_JSON="$HOME/.config/personal-os-pull/config.json"

export KUBECONFIG="$HOME/.kube/personal-os"
kubectl config get-contexts
export KUBE_CONTEXT='<context-name-from-the-command-above>'
export INFRA_REPO="$PWD/../OCI-Infrastructure"
export TLS_CERT_FILE='<path-to-fullchain.pem>'
export TLS_KEY_FILE='<path-to-private-key.pem>'

# Assemble fullchain.pem outside Git: leaf certificate first, then CA bundle.
# cat personal-os_tech.crt personal-os_tech.ca-bundle > <fullchain.pem>
# The private key must be the key used to create this certificate's CSR.

# If Terraform was applied remotely, download its outputs as JSON:
# export TERRAFORM_OUTPUT_JSON='<path-to-terraform-output.json>'
bash scripts/deploy.sh
```

The deployment script checks the selected cluster, certificate hostnames/expiry,
matching private key, image references, and pull config before creating secrets.
It invokes the existing infrastructure renderer, rejects unresolved manifest
values, applies the namespace and the `ocir-pull` / `personal-os-tls` secrets,
runs a server-side dry run, then deploys and waits for both workloads.

Applying the `personal-os-web` Service creates the public flexible OCI load
balancer using the infra repo's subnet/NSG and TLS settings. Its initial bandwidth
is 10 Mbps. Only HTTPS port 443 is exposed. DNS is updated separately after
verification. Certificate and auth-token rotation are operational tasks; this
scaffold does not automate renewal.

## Verify HTTPS and DNS

```bash
kubectl --context "$KUBE_CONTEXT" -n personal-os get service personal-os-web
bash scripts/smoke-test.sh '<external-ip>'
```

This verifies both `personal-os.tech` and `www.personal-os.tech` with normal
certificate verification before DNS changes: homepage, real built asset, client
route fallback, API status, and API 404 handling. The script never disables TLS
verification.

Then point the existing Namecheap `@` and `www` A records to the external IP,
remove conflicting records, and rerun `bash scripts/smoke-test.sh` through public
DNS. Keep certificate validation records. For rollback, use
`kubectl rollout undo deployment/<name> -n personal-os` or redeploy previous digest
references using the infra renderer.

Infrastructure details and the authoritative deployment contract are in
[OCI-Infrastructure/docs/personal-os.md](../OCI-Infrastructure/docs/personal-os.md).

References: [Spring health groups](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html),
[Oracle OKE load balancer TLS](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengcreatingloadbalancers-subtopic.htm).
