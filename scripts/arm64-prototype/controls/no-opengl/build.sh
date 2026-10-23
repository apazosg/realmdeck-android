#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK="${NDK:-/home/adrian/android-sdk/ndk/24.0.8215888}"
CLANG="${CLANG:-$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang}"
READelf="${LLVM_READELF:-$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf}"
OUT_ROOT="${1:-/home/adrian/wowforever-artifacts/arm64-prototype/controls/no-opengl}"
LIB="$OUT_ROOT/libGL.so.1"
MANIFEST="$OUT_ROOT/manifest.json"

test -x "$CLANG"
test -x "$READelf"
mkdir -p "$OUT_ROOT"

"$CLANG" -shared -fPIC -nostdlib \
  -Wl,--no-undefined -Wl,--build-id=none -Wl,-soname,libGL.so.1 \
  -o "$LIB" "$ROOT/no_opengl_shim.c"
chmod 0644 "$LIB"

HEADER="$OUT_ROOT/readelf-header.txt"
DYNAMIC="$OUT_ROOT/readelf-dynamic.txt"
SYMBOLS="$OUT_ROOT/readelf-symbols.txt"
DYNAMIC_SYMBOLS="$OUT_ROOT/readelf-dynsym.txt"
"$READelf" -h "$LIB" >"$HEADER"
"$READelf" -d "$LIB" >"$DYNAMIC"
"$READelf" -s "$LIB" >"$SYMBOLS"
"$READelf" --dyn-syms "$LIB" >"$DYNAMIC_SYMBOLS"

grep -Eq 'Class:[[:space:]]+ELF64' "$HEADER"
grep -Eq 'Data:[[:space:]]+2.s complement, little endian' "$HEADER"
grep -Eq 'Machine:[[:space:]]+AArch64' "$HEADER"
grep -Eq 'SONAME.*\[libGL\.so\.1\]' "$DYNAMIC"
if grep -Eq '\(NEEDED\)' "$DYNAMIC"; then
  printf '%s\n' 'unexpected DT_NEEDED in data-only no-OpenGL control' >&2
  exit 1
fi

export DYNAMIC_SYMBOLS
python3 - <<'PY'
import os
from pathlib import Path

rows = []
for line in Path(os.environ["DYNAMIC_SYMBOLS"]).read_text().splitlines():
    fields = line.split()
    if len(fields) >= 7 and fields[0].endswith(":") and fields[0][:-1].isdigit():
        rows.append(fields)

exports = [row for row in rows if row[4] in {"GLOBAL", "WEAK"}]
if len(exports) != 1:
    raise SystemExit(f"unexpected dynamic export count: {len(exports)}")
for row in rows:
    if row[4] not in {"GLOBAL", "WEAK"} and (row[4] != "LOCAL" or row[6] != "UND"):
        raise SystemExit(f"unexpected non-export dynamic symbol: {' '.join(row)}")
only = exports[0]
if len(only) < 8 or only[3] != "OBJECT" or only[4] != "GLOBAL" or only[5] != "DEFAULT" or only[6] == "UND" or only[7] != "wow_diagnostic_no_opengl":
    raise SystemExit(f"unexpected dynamic export: {' '.join(only)}")
PY

export LIB MANIFEST
python3 - <<'PY'
import hashlib
import json
import os
from pathlib import Path

library = Path(os.environ["LIB"])
manifest = Path(os.environ["MANIFEST"])
payload = library.read_bytes()
manifest.write_text(json.dumps({
    "schema": 1,
    "sha256": hashlib.sha256(payload).hexdigest(),
    "size": len(payload),
    "elfMachine": 183,
    "soname": "libGL.so.1",
}, separators=(",", ":")) + "\n", encoding="utf-8")
PY

chmod 0644 "$MANIFEST"

# Keep these bounded readelf reports beside the external artifact as audit
# evidence; they are intentionally never copied into APK assets.
printf 'NO_OPENGL_ARTIFACT=%s\n' "$LIB"
printf 'NO_OPENGL_MANIFEST=%s\n' "$MANIFEST"
printf 'NO_OPENGL_READELF_HEADER=%s\n' "$HEADER"
printf 'NO_OPENGL_READELF_DYNAMIC=%s\n' "$DYNAMIC"
printf 'NO_OPENGL_READELF_SYMBOLS=%s\n' "$SYMBOLS"
printf 'NO_OPENGL_READELF_DYNSYM=%s\n' "$DYNAMIC_SYMBOLS"
sha256sum "$LIB" "$MANIFEST"
