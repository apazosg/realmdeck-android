#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
TOOLCHAIN=${TOOLCHAIN:-"$ARTIFACT_ROOT/toolchain/llvm-mingw-20260922-ucrt-ubuntu-22.04-x86_64"}
VULKAN_INCLUDE=${VULKAN_INCLUDE:-"/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include"}
OUT="$ARTIFACT_ROOT/controls/graphics-probe-readback-v1"
CLANG="$TOOLCHAIN/bin/aarch64-w64-mingw32-clang"
READOBJ="$TOOLCHAIN/bin/llvm-readobj"
EXE="$OUT/GraphicsProbeReadback-ARM64.exe"

test -d "$ARTIFACT_ROOT"
test -x "$CLANG"
test -x "$READOBJ"
test -f "$VULKAN_INCLUDE/vulkan/vulkan.h"
mkdir -p "$OUT"

"$CLANG" --target=aarch64-w64-windows-gnu --sysroot="$TOOLCHAIN" \
  -isystem "$VULKAN_INCLUDE" -fuse-ld=lld -std=c11 -O2 \
  -Wall -Wextra -Werror -mwindows -D_WIN32_WINNT=0x0602 -DREADBACK_VARIANT=1 \
  -ffunction-sections -fdata-sections -Wl,--gc-sections,--no-insert-timestamp \
  -o "$EXE" "$ROOT/GraphicsProbe.c"

"$READOBJ" --file-headers --coff-imports "$EXE" > "$OUT/GraphicsProbeReadback-ARM64.readobj.txt"
python3 - "$EXE" "$ROOT/GraphicsProbe.c" "$ROOT/build-readback.sh" "$OUT/manifest.json" <<'PY'
import hashlib
import json
import pathlib
import struct
import sys

exe, source, recipe, manifest = map(pathlib.Path, sys.argv[1:])
data = exe.read_bytes()
pe_offset = struct.unpack_from('<I', data, 0x3c)[0]
assert data[:2] == b'MZ' and data[pe_offset:pe_offset + 4] == b'PE\0\0'
machine = struct.unpack_from('<H', data, pe_offset + 4)[0]
magic = struct.unpack_from('<H', data, pe_offset + 24)[0]
assert machine == 0xaa64 and magic == 0x20b and len(data) <= 1024 * 1024
payload = {
    "schema": 1,
    "variant": "readback-v1",
    "file": exe.name,
    "size": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
    "machine": machine,
    "optionalMagic": magic,
    "sourceSha256": {
        source.name: hashlib.sha256(source.read_bytes()).hexdigest(),
        recipe.name: hashlib.sha256(recipe.read_bytes()).hexdigest(),
    },
    "compilerPin": "llvm-mingw-20260922-ucrt-ubuntu-22.04-x86_64/20260922",
    "importsInspection": "GraphicsProbeReadback-ARM64.readobj.txt",
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": exe.name, "size": len(data), "sha256": payload["sha256"], "manifest": str(manifest)}, sort_keys=True))
PY
