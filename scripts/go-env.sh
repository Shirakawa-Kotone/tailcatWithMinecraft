#!/usr/bin/env bash
# Source this file to use the pinned Tailscale Go compiler required by Tailcat.
set -euo pipefail
TC_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TC_GO_REV=63ae404c8203317fd3c82d972e5dc8f0fcb425cb
TC_CACHE=${TC_BUILD_CACHE:-"$TC_ROOT/.cache/mclink"}
TC_HOST_OS=$(uname -s | tr '[:upper:]' '[:lower:]')
case $(uname -m) in
  x86_64|amd64) TC_HOST_ARCH=amd64 ;;
  arm64|aarch64) TC_HOST_ARCH=arm64 ;;
  *) echo 'Unsupported build host architecture' >&2; return 1 2>/dev/null || exit 1 ;;
esac
case "$TC_HOST_OS" in
  darwin|linux) ;;
  *) echo 'Build helpers from macOS, Linux, or WSL' >&2; return 1 2>/dev/null || exit 1 ;;
esac
export GOROOT="$TC_CACHE/tsgo/$TC_GO_REV"
if [[ ! -x "$GOROOT/bin/go" ]]; then
  mkdir -p "$GOROOT"
  TC_ARCHIVE="$TC_CACHE/tsgo/$TC_HOST_OS-$TC_HOST_ARCH.tar.gz"
  curl --retry 3 -fsSL "https://github.com/tailscale/go/releases/download/build-$TC_GO_REV/$TC_HOST_OS-$TC_HOST_ARCH.tar.gz" -o "$TC_ARCHIVE"
  # SHA-256 digests published by github.com/tailscale/go for this exact revision.
  case "$TC_HOST_OS-$TC_HOST_ARCH" in
    darwin-amd64) TC_GO_SHA=c0372692986cfe983e1b772848c0d3ef6864054c20f4b90d9fe114f6b5eb68b3 ;;
    darwin-arm64) TC_GO_SHA=dca8c2dcbc61c355536ef9fd953da0efd67f9066949289e77a3aa2b96da35287 ;;
    linux-amd64) TC_GO_SHA=d06972c01a6d2b32a2eabd09179f48d888b7dc29ae051a72eea5972e4e085800 ;;
    linux-arm64) TC_GO_SHA=a551877a249858e035c778e1ee89dde70c226298e7520cdc1cf3a4f798a1fde1 ;;
  esac
  python3 - "$TC_ARCHIVE" "$TC_GO_SHA" <<'PY'
import hashlib, pathlib, sys
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() != sys.argv[2]:
    raise SystemExit('Tailscale Go archive checksum mismatch')
PY
  tar -xzf "$TC_ARCHIVE" -C "$GOROOT" --strip-components=1
  rm -f "$TC_ARCHIVE"
fi
export TC_GO="$GOROOT/bin/go"
[[ $("$TC_GO" env GOVERSION) == go1.26.5 ]] || { echo 'Tailscale Go compiler version mismatch' >&2; return 1 2>/dev/null || exit 1; }
export GOCACHE="$TC_CACHE/go-build/$TC_GO_REV"
export GOPATH="${TC_GOPATH:-$TC_CACHE/gopath}"
export GOTOOLCHAIN=local
export CGO_ENABLED=0
export GOMAXPROCS=${GOMAXPROCS:-4}
mkdir -p "$GOCACHE" "$GOPATH"
