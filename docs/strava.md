# Single-athlete Strava backend

The hourly OKE CronJob uses the existing API image in the `strava-sync` profile.
It starts without an HTTP server, imports Strava activity summaries into the
private PostgreSQL database, closes its connections and exits. The HTTP API only
queries PostgreSQL. The Running (02) workspace card opens `/running`, where the
rolling mileage graph reads these imported summaries.

```text
Strava OAuth/API → OKE CronJob → PostgreSQL → public daily mileage API → running UI
                      hourly       ↑
Strava deauthorization webhook ────┘
```

## Sync behavior

- One configured athlete ID; verify the authenticated athlete and every activity owner.
- Initial history import, then a 30-day window each hour. Reconcile all history
  every 24 hours to catch older edits, backdated uploads and deletions.
- 200 activities per page, at most 80 pages per run by default. If the last page
  is still full, fail with `PAGE_LIMIT_EXCEEDED`; never prune incomplete history.
  This covers fewer than 16,000 summaries with the default setting. The bound can
  be increased to 90 pages; larger histories need a future resumable backfill.
- Fixed before/after timestamps delimit each scan. All activity changes and
  pruning commit in one transaction after every page succeeds. An upstream failure
  leaves the last successful snapshot and timestamp available.
- Store AES-256-GCM encrypted access/refresh tokens in a separate state table.
  Commit rotated tokens immediately, before fetching activity data. The encryption
  key stays in the worker's Kubernetes Secret and must be backed up with the DB.
- Stop on 429 or when returned application/read rate limits have five requests
  remaining. Requests have connection/read deadlines. The job has no immediate
  retry; a later hourly run retries ordinary failures.
- Kubernetes forbids overlapping scheduled Jobs; a PostgreSQL session advisory
  lock also excludes manually created Jobs. Each job has a 30-minute deadline.
- The webhook clears activities and credentials immediately on deauthorization.
  Import transactions check authorization while locking the state row, so a
  concurrent import cannot restore revoked data. HTTP 401 / refresh HTTP 400 also
  require reauthorization. The seed refresh token is never silently reused after
  revocation.

This version stores summary metrics, not GPS traces, streams, photos, segment
efforts, gear, or detailed activity responses. Distance/elevation are meters,
speed is meters/second, durations are seconds, and timestamps are UTC.
Missing heart-rate/speed measurements remain null. Profile data is used only to
verify the athlete ID.

## One-time authorization

Register your Strava app with its callback domain set to `localhost`. Run from
the application repository with Python 3; IDs are public identifiers:

```bash
python3 scripts/strava-authorize.py --client-id '<app-id>'
```

The helper prompts for the client secret, prints the consent URL, then prompts
for the complete callback URL. It validates OAuth state, the callback destination
and `activity:read_all` scope, then discovers your numeric athlete ID from Strava's
OAuth response. You do not need to find the ID or resolve a vanity username.
Optionally pass `--athlete-id '<numeric-id>'` to verify the authorized account.
No callback server is needed:
copy the URL from the browser even if the localhost page cannot load.

It creates ignored, owner-only files under `.local/strava/`:

| File | Keys |
| --- | --- |
| `sync.env` | `STRAVA_ATHLETE_ID`, `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET`, `STRAVA_REFRESH_TOKEN`, `STRAVA_TOKEN_ENCRYPTION_KEY` |
| `api.env` | `STRAVA_READ_TOKEN`, `STRAVA_WEBHOOK_TOKEN` |

## Tokens and server configuration

The public mileage graph needs no token, login, root `.env`, or frontend secret.
It reads daily totals already stored in PostgreSQL. The remaining credentials
serve the import worker, private read endpoints, and deauthorization webhook:

| Credential | Purpose | Local storage | Production storage |
| --- | --- | --- | --- |
| `STRAVA_CLIENT_SECRET` and `STRAVA_REFRESH_TOKEN` | Authorize the worker to import from Strava; the refresh token is a bootstrap seed | `.local/strava/sync.env` | `strava-sync-credentials` Secret |
| `STRAVA_TOKEN_ENCRYPTION_KEY` | Encrypt stored Strava access/refresh tokens | `.local/strava/sync.env` | `strava-sync-credentials` Secret |
| `STRAVA_READ_TOKEN` | Personal OS Bearer credential for detailed activities and sync status; it is not a Strava OAuth token | `.local/strava/api.env` | `strava-api-credentials` Secret |
| `STRAVA_WEBHOOK_TOKEN` | Shared secret for the deauthorization callback URL and verification | `.local/strava/api.env` | `strava-api-credentials` Secret |

The authorization helper generates the read and webhook tokens independently.
Retain the existing values when updating the running UI; no new token or manual
UI entry is needed. The persisted database refresh token supersedes the bootstrap
seed after rotation.

For local Docker use, `compose.strava.yaml` loads `api.env` into the API and
`sync.env` into the worker. The base `compose.yaml` does not load a root `.env`
into the API container. When running Java directly, supply the required process
environment variables; the application does not automatically import env files.
In production, Kubernetes injects these values from the existing Secrets.

The HTTP API receives neither Strava OAuth credentials nor the encryption key.
The browser receives none of these credentials. Keep the generated files and
database backups private; never put a token in frontend source, a `VITE_*`
variable, or a browser URL. The private read token can be omitted if those read
endpoints are unused; the public mileage graph still works. Keep the webhook
credential configured for deauthorization handling.

## View your running mileage

Click **Running (02)** on the workspace, or open `/running`. The graph loads
public daily running mileage automatically from PostgreSQL. It does not ask for
a token, contact Strava, or download detailed activity records. Anyone who can
reach the site can view these mileage totals.

`GET /api/running/mileage?timezone=America/Chicago` returns exactly 365 daily
records, including today, with only each date and its distance in meters. The
server filters Run, TrailRun, and VirtualRun activities and handles local-day
boundaries across daylight saving. The browser converts meters to miles and
builds the cumulative line. Rest days keep the total steady. The day slider
supports touch and keyboard inspection. **Refresh mileage** rereads imported data;
the existing hourly job handles Strava sync.


## Local run

```bash
docker compose -f compose.yaml -f compose.strava.yaml up --build -d personal-os-api frontend
# Wait for this endpoint to return 200; the API applies V2 first.
curl --fail http://localhost:8081/health/ready
docker compose -f compose.yaml -f compose.strava.yaml run --build --rm strava-sync
```

The worker is opt-in; the normal Compose stack and CI make no Strava requests.
The helper requires real OAuth consent; tests use local HTTP fixtures and
disposable PostgreSQL containers.

## API

The mileage endpoint is public. Supply `Authorization: Bearer <STRAVA_READ_TOKEN>`
to the two private Strava read routes. All read responses use
`Cache-Control: no-store`. Private routes return 401 for missing/invalid
credentials, and 404 when the read token is unconfigured or shorter than 32
characters. This does not affect the public mileage endpoint.

| Route | Access | Response |
| --- | --- | --- |
| `GET /api/running/mileage?timezone=UTC` | Public; no token | `timezone`, `startDate`, `endDate`, and 365 `days` containing `date` and `distanceMeters`; invalid timezone returns 400 |
| `GET /api/strava/activities?limit=50&offset=0` | Bearer `STRAVA_READ_TOKEN` | `activities`, `total`, `limit`, `offset`; newest start date first, then descending ID |
| `GET /api/strava/sync` | Bearer `STRAVA_READ_TOKEN` | `configured`, `authorizationRequired`, last attempt/success/full-sync times, safe error code, activity count |

Limit is 1–200; offset is 0–1,000,000. The status endpoint exposes no credentials.
Before the first job, status is unconfigured and activities are empty. The API
and its health checks do not depend on Strava availability.

## Install on OKE

First deploy the new API image so Flyway creates V2. The worker disables Flyway
and requires that migration to have succeeded. Use the administrator kubeconfig
and explicit context for the existing cluster:

```bash
kubectl --context "$KUBE_CONTEXT" -n personal-os create secret generic strava-sync-credentials \
  --from-env-file=.local/strava/sync.env --dry-run=client -o yaml | \
  kubectl --context "$KUBE_CONTEXT" apply -f -
kubectl --context "$KUBE_CONTEXT" -n personal-os create secret generic strava-api-credentials \
  --from-env-file=.local/strava/api.env --dry-run=client -o yaml | \
  kubectl --context "$KUBE_CONTEXT" apply -f -
```

Render and apply the infrastructure overlay using the new API/frontend digest
pair, following [deployment](deployment.md) and
[the infrastructure contract](../../OCI-Infrastructure/docs/personal-os.md).
The authoritative new manifest is
`OCI-Infrastructure/kubernetes/personal-os/strava-sync.yaml`.
Kustomize substitutes the API image for both the Deployment and CronJob.
The CronJob initially stays suspended and runs at minute 0 of each hour (UTC).
The API manifest adds optional references to the private read/webhook keys.
When updating an existing Secret, restart the API to reload its environment:

```bash
kubectl --context "$KUBE_CONTEXT" -n personal-os rollout restart deployment/personal-os-api
kubectl --context "$KUBE_CONTEXT" -n personal-os rollout status deployment/personal-os-api
kubectl --context "$KUBE_CONTEXT" apply -f kubernetes/deployer-role.yaml
```

Set production GitHub variable `STRAVA_SYNC_ENABLED=true` after applying the
expanded deployer Role and installing the CronJob. Routine deployments then
preflight its image patch and update it after API/frontend/HTTPS verification,
using the exact API digest. A failed patch restores the previous worker and
application images. Scheduling, credentials and suspension are preserved.
Without this variable, the existing application deployment path is unchanged.

## Register the deauthorization webhook

The API implements a verification GET and event POST at
`https://personal-os.tech/api/strava/webhook/<STRAVA_WEBHOOK_TOKEN>`.
Strava does not sign webhook POSTs; the random callback path is a shared secret,
and events must also match the configured subscription and stored athlete.
Keep the callback URL private. Both bundled and infrastructure Nginx configurations
suppress access/error logs for this callback prefix.

After deploying the API with `api.env`, create the subscription from a trusted
terminal. This Python snippet reads credentials from files, never command-line
arguments. It writes only the public subscription ID to the terminal:

```bash
python3 - <<'PY'
import json, pathlib, urllib.parse, urllib.request
folder = pathlib.Path(".local/strava")
def env(name):
    return dict(line.split("=", 1) for line in (folder / name).read_text().splitlines())
worker, api = env("sync.env"), env("api.env")
request = urllib.request.Request("https://www.strava.com/api/v3/push_subscriptions",
    data=urllib.parse.urlencode({
        "client_id": worker["STRAVA_CLIENT_ID"],
        "client_secret": worker["STRAVA_CLIENT_SECRET"],
        "callback_url": "https://personal-os.tech/api/strava/webhook/" + api["STRAVA_WEBHOOK_TOKEN"],
        "verify_token": api["STRAVA_WEBHOOK_TOKEN"],
    }).encode())
with urllib.request.urlopen(request, timeout=30) as response:
    subscription = json.load(response)
with (folder / "api.env").open("a") as stream:
    stream.write("STRAVA_WEBHOOK_SUBSCRIPTION_ID=" + str(subscription["id"]) + "\n")
print("Registered subscription", subscription["id"])
PY
```

Reapply `strava-api-credentials` from the updated `api.env` and restart the API
again. Each Strava app supports one subscription; inspect/reuse an existing
subscription instead of registering duplicates. Activity events are acknowledged;
the scheduled worker handles activity reconciliation.

Create a manual job to verify the initial import, then unsuspend scheduling:

```bash
kubectl --context "$KUBE_CONTEXT" -n personal-os create job \
  --from=cronjob/personal-os-strava-sync strava-initial-sync
kubectl --context "$KUBE_CONTEXT" -n personal-os wait \
  --for=condition=complete job/strava-initial-sync --timeout=1800s
kubectl --context "$KUBE_CONTEXT" -n personal-os logs job/strava-initial-sync
kubectl --context "$KUBE_CONTEXT" -n personal-os patch cronjob personal-os-strava-sync \
  --type=merge -p '{"spec":{"suspend":false}}'
```

Applying the base infrastructure overlay again restores its checked-in
`suspend:true`; explicitly unsuspend after that bootstrap operation.
Routine image deployments preserve the current suspension value.

Optional worker settings: `STRAVA_LOOKBACK_DAYS=30`, `STRAVA_MAX_PAGES=80`,
`STRAVA_FULL_SYNC_HOURS=24` (minimum 24). Keep the configured identity fixed.
Changing athlete requires explicitly removing the previous connection and data.

## Recovery

Inspect `GET /api/strava/sync` using the private read token, and inspect failed Job logs. Ordinary network/server/rate
failures retain cached data and retry on the next schedule. There is no alerting
integration yet; Kubernetes Job failure and the status endpoint expose failures.

For revoked authorization, suspend the CronJob and wait for active jobs to finish.
Repeat consent with a new helper output directory, install the new worker Secret,
then explicitly reset the connection using the application DB role:

```sql
UPDATE personal_os.strava_sync_state
SET access_token=NULL, refresh_token=NULL, expires_at=NULL,
    authorization_required=false, last_full_sync_at=NULL, last_error=NULL
WHERE id=1;
```

Run a manual job before resuming scheduling. Retain the existing API/webhook
credentials unless intentionally rotating them; new helper API credentials do
not automatically replace an existing Strava subscription.

A lost encryption key requires fresh authorization and the same explicit token
reset. Restore the matching DB/key backups together. An abrupt failure between
Strava rotating a token and its database commit can also require fresh consent;
the upstream token endpoint and PostgreSQL cannot form one atomic transaction.

References: [OAuth and rotating tokens](https://developers.strava.com/docs/authentication/),
[activities API](https://developers.strava.com/docs/reference/#api-Activities-getLoggedInAthleteActivities),
[rate limits](https://developers.strava.com/docs/rate-limits/),
[webhooks](https://developers.strava.com/docs/webhooks/).
