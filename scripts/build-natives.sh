#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/go-env.sh"
RES="$ROOT/native/assets/mclink/native"
OUT="$ROOT/helper/bin"
VERSION=${TC_HELPER_VERSION:-0.2.0}
mkdir -p "$OUT" "$RES"
# Override for local iteration; release builds use all six targets.
TARGETS=${TC_NATIVE_TARGETS:-'windows/amd64 windows/arm64 darwin/amd64 darwin/arm64 linux/amd64 linux/arm64'}
for target in $TARGETS; do
  case "$target" in
    windows/amd64|windows/arm64|darwin/amd64|darwin/arm64|linux/amd64|linux/arm64) ;;
    *) echo "Unsupported target: $target" >&2; exit 1 ;;
  esac
  target_os=${target%/*}
  target_arch=${target#*/}
  dir="$target_os-$target_arch"
  name=mclink-helper
  [[ "$target_os" == windows ]] && name=mclink-helper.exe
  mkdir -p "$OUT/$dir"
  echo "Building Tailcat helper $VERSION for $target"
  (cd "$ROOT/helper" && GOOS="$target_os" GOARCH="$target_arch" "$TC_GO" build -p=2 -buildvcs=false -tags=ts_omit_ssh -trimpath \
    -ldflags="-s -w -X main.helperVersion=$VERSION" -o "$OUT/$dir/$name" ./cmd/mclink-helper)
done
# Every helper is an ~19 MiB Go binary. Six of them stored as loose jar entries
# cost ~43 MiB because deflate cannot see across file boundaries; a single solid
# archive lets LZMA reuse the shared runtime across all six (~17 MiB). Keep the
# SHA-256 manifest for the uncompressed binaries so extraction still verifies
# what is actually executed.
python3 - "$OUT" "$RES" <<'PY'
import hashlib, io, json, lzma, pathlib, shutil, sys, tarfile
out = pathlib.Path(sys.argv[1])
res = pathlib.Path(sys.argv[2])
targets = ['darwin-amd64', 'darwin-arm64', 'linux-amd64', 'linux-arm64', 'windows-amd64', 'windows-arm64']
payloads = {}
for target in targets:
    name = 'mclink-helper.exe' if target.startswith('windows') else 'mclink-helper'
    path = out / target / name
    if not path.is_file():
        raise SystemExit(f'Missing helper build output: {path}')
    payloads[f'{target}/{name}'] = path.read_bytes()
for stale in res.iterdir():
    if stale.is_dir():
        shutil.rmtree(stale)
raw = io.BytesIO()
with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as archive:
    for name in sorted(payloads):
        data = payloads[name]
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mtime = 0
        info.mode = 0o755
        info.uid = info.gid = 0
        info.uname = info.gname = ''
        archive.addfile(info, io.BytesIO(data))
# 64 MiB dictionary: cross-file matches sit ~19-100 MiB apart, so smaller
# dictionaries lose most of the win (16 MiB -> ~33 MiB, 64 MiB -> ~17.6 MiB).
(res / 'helpers.tar.xz').write_bytes(lzma.compress(raw.getvalue(), format=lzma.FORMAT_XZ, preset=9))
checksums = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(payloads.items())}
(res / 'checksums.json').write_text(json.dumps(checksums, indent=2) + '\n')
print(f'Native payload: {(res / "helpers.tar.xz").stat().st_size} bytes for {len(payloads)} targets')
PY
echo "Native payload, checksums: $RES"
