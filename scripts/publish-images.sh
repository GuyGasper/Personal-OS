#!/usr/bin/env bash
set -euo pipefail

: "${REGISTRY:?Set REGISTRY, for example iad.ocir.io}"
: "${OCI_NAMESPACE:?Set the OCI tenancy registry namespace}"
: "${SOURCE_SHA:?Set the commit SHA whose tested images should be published}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bundle="${IMAGE_BUNDLE_DIR:-$root/artifacts/tested-images}"
python3 "$root/scripts/image-bundle.py" publish \
  --source-sha "$SOURCE_SHA" --bundle-dir "$bundle" \
  --registry "$REGISTRY" --namespace "$OCI_NAMESPACE" \
  --release-file "$root/.local/release.json"
