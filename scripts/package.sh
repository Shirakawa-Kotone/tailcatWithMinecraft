#!/usr/bin/env bash
# Compile, verify, export, then clean project caches.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec python3 "$ROOT/scripts/release.py" "$@"
