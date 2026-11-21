#!/usr/bin/env bash
# Build a self-contained Debian package for the XHR 1.0.1 SOCKS5 client.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ARCH="${1:-amd64}"
case "$ARCH" in
    amd64) GOARCH=amd64 ;;
    arm64) GOARCH=arm64 ;;
    *) echo "supported Debian architectures: amd64, arm64" >&2; exit 2 ;;
esac

VERSION="$(sed -n 's/^## \[\([^]]*\)\].*/\1/p' "$ROOT/CHANGELOG.md" | head -1)"
[[ "$VERSION" == "1.0.1" ]] || { echo "expected XHR 1.0.1, got $VERSION" >&2; exit 1; }

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PKG="$STAGE/xhr-client_${VERSION}_${ARCH}"

mkdir -p "$PKG/DEBIAN" "$PKG/usr/bin" \
    "$PKG/usr/share/doc/xhr-client/examples" "$PKG/usr/lib/systemd/user" \
    "$PKG/usr/share/applications" "$PKG/usr/share/icons/hicolor/1024x1024/apps"

sed "s/^Architecture: .*/Architecture: $ARCH/" "$ROOT/packaging/debian/control" > "$PKG/DEBIAN/control"
CGO_ENABLED=0 GOOS=linux GOARCH="$GOARCH" \
    go build -trimpath -ldflags='-s -w' -o "$PKG/usr/bin/xhr-client" "$ROOT/cmd/xhr-client"
install -m 0644 "$ROOT/LICENSE" "$PKG/usr/share/doc/xhr-client/copyright"
install -m 0644 "$ROOT/packaging/debian/README.Debian" "$PKG/usr/share/doc/xhr-client/README.Debian"
install -m 0644 "$ROOT/packaging/debian/config.json" "$PKG/usr/share/doc/xhr-client/examples/config.json"
install -m 0644 "$ROOT/packaging/debian/xhr-client.service" "$PKG/usr/lib/systemd/user/xhr-client.service"
install -m 0644 "$ROOT/packaging/debian/xhr-client.desktop" "$PKG/usr/share/applications/xhr-client.desktop"
install -m 0644 "$ROOT/branding/xhr-icon-1024.png" "$PKG/usr/share/icons/hicolor/1024x1024/apps/xhr.png"

mkdir -p "$ROOT/dist"
dpkg-deb --build --root-owner-group "$PKG" "$ROOT/dist/xhr-client_${VERSION}_${ARCH}.deb"
(cd "$ROOT/dist" && sha256sum "xhr-client_${VERSION}_${ARCH}.deb" > "xhr-client_${VERSION}_${ARCH}.deb.sha256")
echo "built $ROOT/dist/xhr-client_${VERSION}_${ARCH}.deb"
