#!/usr/bin/env bash
# Regenerate docs/FULL-TEXT.md from the canonical source files.
# Usage: scripts/build-full-text.sh [--check]
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/FULL-TEXT.md"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

emit() {
    local path="$1" lang="$2"
    {
        printf '\n## `%s`\n\n' "$path"
        printf '~~~%s\n' "$lang"
        cat "$ROOT/$path"
        printf '\n~~~\n'
    } >>"$TMP"
}

cat >"$TMP" <<'EOF'
# XHR — complete source texts

This file is generated from the canonical files in this repository. The
canonical file is always the source path shown above each block. Do not edit
this page by hand; run:

```bash
scripts/build-full-text.sh
```

The guides are ordinary Markdown files:

* [Android, 0 to 100](GUIDE-ANDROID.md)
* [Google-only, 0 to 100](GUIDE-GOOGLE.md)
* [Google + Cloudflare](GUIDE-CLOUDFLARE.md)
* [Protocol](PROTOCOL.md)
* [Security and limits](../SECURITY.md)
* [On-device test checklist](TEST-CHECKLIST.md)

Every deployable text is below, in full.
EOF

emit "gas/Code.gs" "javascript"
emit "cloudflare/worker.js" "javascript"
emit "cloudflare/frames.js" "javascript"
emit "cloudflare/wrangler.toml" "toml"
emit "cloudflare/package.json" "json"
emit "Dockerfile" "dockerfile"
emit "cmd/xhr-exit/main.go" "go"
emit "internal/proto/proto.go" "go"
emit "internal/wire/wire.go" "go"
emit "internal/tunnel/client.go" "go"
emit "mobile/main.go" "go"
emit "mobile/jni.c" "c"

if [[ "${1:-}" == "--check" ]]; then
    if ! diff -u "$OUT" "$TMP"; then
        echo "docs/FULL-TEXT.md is out of date. Run scripts/build-full-text.sh." >&2
        exit 1
    fi
    echo "docs/FULL-TEXT.md is current."
else
    mv "$TMP" "$OUT"
    echo "wrote $OUT"
fi
