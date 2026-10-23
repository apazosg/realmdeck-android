#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
TOOLCHAIN=${TOOLCHAIN:-"$ARTIFACT_ROOT/toolchain/llvm-mingw-20260922-ucrt-ubuntu-22.04-x86_64"}
OUT=${1:?Usage: bash build.sh OUTPUT_DIRECTORY_OUTSIDE_REPOSITORY}
REPO=$(git -C "$ROOT" rev-parse --show-toplevel)
OUT=$(realpath -m -- "$OUT")
case "$OUT/" in "$REPO/"*) echo 'Output must be outside repository' >&2; exit 2;; esac
mkdir -p "$OUT"
EXE="$OUT/AccessibilityProbe-ARM64.exe"
"$TOOLCHAIN/bin/aarch64-w64-mingw32-clang++" -std=c++11 -O2 -Wall -Wextra -Werror \
  -D_WIN32_WINNT=0x0602 -static -fno-exceptions -fno-rtti \
  -Wl,--no-insert-timestamp -o "$EXE" "$ROOT/AccessibilityProbe.cpp" \
  -loleacc -lole32 -loleaut32 -luuid
"$TOOLCHAIN/bin/llvm-readobj" --file-headers --coff-imports "$EXE" > "$OUT/AccessibilityProbe-ARM64.readobj.txt"
python3 - "$EXE" "$ROOT/AccessibilityProbe.cpp" "$ROOT/build.sh" <<'PY'
import hashlib
import json
from pathlib import Path
import struct
import sys
exe, source, recipe = map(Path, sys.argv[1:])
data = exe.read_bytes()
offset = struct.unpack_from('<I', data, 0x3c)[0]
assert data[:2] == b'MZ' and data[offset:offset+4] == b'PE\0\0'
assert struct.unpack_from('<H', data, offset+4)[0] == 0xaa64
assert struct.unpack_from('<H', data, offset+24)[0] == 0x20b
receipt = {'schema': 1, 'machine': 'ARM64', 'runtime_tested': False,
           'sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in (exe, source, recipe)}}
(exe.parent / 'manifest.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt))
PY
