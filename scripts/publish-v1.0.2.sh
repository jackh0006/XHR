#!/usr/bin/env bash
# Upload the Linux and Android 1.0.2 release assets. Safe to rerun.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${XHR_RELEASE_REPO:-jackh0006/XHR}"
TAG="${XHR_RELEASE_TAG:-v1.0.2}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

cp "$ROOT/dist/xhr-client_1.0.2_amd64.deb" "$STAGE/XHR-linux-amd64-1.0.2.deb"
for apk in "$ROOT"/dist/XHR-1.0.2-*.apk; do
  base="$(basename "$apk")"
  abi="${base#XHR-1.0.2-}"; abi="${abi%.apk}"
  cp "$apk" "$STAGE/XHR-android-${abi}-1.0.2.apk"
done
(cd "$STAGE" && sha256sum XHR-linux-amd64-1.0.2.deb XHR-android-*-1.0.2.apk > SHA256SUMS-ports.txt)
(cd "$STAGE" && sha256sum XHR-linux-amd64-1.0.2.deb > XHR-linux-amd64-1.0.2.deb.sha256)
for f in "$STAGE"/*; do
  gh release upload "$TAG" "$f" --repo "$REPO" --clobber
done
