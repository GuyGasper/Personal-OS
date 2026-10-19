#!/usr/bin/env bash
set -euo pipefail
# Pass an LB IP to test certificates/routing before changing DNS.
# Without an IP, test the public DNS records.
lb_ip="${1:-}"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
for host in personal-os.tech www.personal-os.tech; do
  options=(--silent --show-error --connect-timeout 10 --max-time 30)
  [[ -z "$lb_ip" ]] || options+=(--resolve "$host:443:$lb_ip")
  base="https://$host"
  curl --fail "${options[@]}" "$base/" > "$temp/index.html"
  grep -q '<div id="root">' "$temp/index.html"
  curl --fail "${options[@]}" "$base/workspace" > "$temp/route.html"
  cmp "$temp/index.html" "$temp/route.html"
  asset="$(python3 - "$temp/index.html" <<'PY'
import re, sys
html = open(sys.argv[1]).read()
match = re.search(r'src="(/assets/[^"]+\.js)"', html)
if not match:
    raise SystemExit("No built JavaScript asset found")
print(match.group(1))
PY
)"
  curl --fail "${options[@]}" "$base$asset" > /dev/null
  curl --fail "${options[@]}" "$base/api/status" | python3 -c 'import json,sys; p=json.load(sys.stdin); assert p["service"]=="Personal OS API" and p["status"]=="UP"; print("API version:",p["version"])'
  code="$(curl "${options[@]}" -o /dev/null -w '%{http_code}' "$base/api/missing")"
  [[ "$code" == 404 ]] || { echo "Expected API 404, got $code" >&2; exit 1; }
  echo "$host: certificate, homepage, client route, static asset, and API passed"
done
