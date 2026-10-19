#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

docker compose up --no-build -d
python3 - <<'PY'
import json, re, time
import urllib.error
import urllib.request

frontend = "http://127.0.0.1:8080"
api = "http://127.0.0.1:8081"

def get(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        return response.read(), response.headers

deadline = time.monotonic() + 120
while True:
    try:
        for path in ["/health/live", "/health/ready"]:
            body, _ = get(api + path)
            assert json.loads(body)["status"] == "UP"
        body, _ = get(frontend + "/api/status")
        status = json.loads(body)
        assert status["service"] == "Personal OS API" and status["status"] == "UP"
        break
    except (OSError, ValueError, AssertionError) as error:
        if time.monotonic() >= deadline:
            raise SystemExit(f"Containers did not become healthy: {error}") from error
        time.sleep(2)

body, _ = get(frontend + "/healthz")
assert body.strip() == b"ok"
html, _ = get(frontend + "/")
assert b'<div id="root">' in html
route, _ = get(frontend + "/workspace")
assert route == html, "Client routes must serve the React entry document"
asset = re.search(rb'src="(/assets/[^"]+\.js)"', html)
assert asset, "No built JavaScript asset found"
asset_body, headers = get(frontend + asset.group(1).decode())
assert asset_body and "javascript" in headers.get("Content-Type", ""), "Asset must be served as JavaScript"
try:
    get(frontend + "/api/missing")
except urllib.error.HTTPError as error:
    assert error.code == 404, error.code
else:
    raise AssertionError("Unknown API routes must return 404 rather than SPA HTML")
print("Containers passed: live/ready probes, Nginx health, API proxy, built asset, SPA route, and API 404")
PY
