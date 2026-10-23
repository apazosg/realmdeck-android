#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
OUT="$ARTIFACT_ROOT/controls/egl-surfaceless"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ELF="$OUT/EglSurfaceless"
READELF_OUTPUT="$OUT/EglSurfaceless.readelf.txt"
MANIFEST="$OUT/manifest.json"

test -d "$ARTIFACT_ROOT"
test -d "$ARTIFACT_ROOT/controls"
ls -ld "$ARTIFACT_ROOT" "$ARTIFACT_ROOT/controls"
test -x "$CLANG"
test -x "$READELF"
mkdir -p "$OUT"

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
  -Wl,--gc-sections -Wl,--as-needed -Wl,--no-undefined -Wl,-z,relro,-z,now \
  -pie -o "$ELF" "$ROOT/EglSurfaceless.c" -lEGL -lGLESv2

"$READELF" --file-header --program-headers --dynamic --needed-libs "$ELF" > "$READELF_OUTPUT"

python3 - "$ELF" "$ROOT/EglSurfaceless.c" "$ROOT/build_surfaceless.sh" "$ROOT/README-surfaceless.md" "$READELF_OUTPUT" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

elf, source, recipe, readme, readelf_output, manifest = map(pathlib.Path, sys.argv[1:])
data = elf.read_bytes()
assert data[:4] == b"\x7fELF"
assert data[4] == 2  # ELFCLASS64
assert data[5] == 1  # ELFDATA2LSB
assert struct.unpack_from("<H", data, 16)[0] == 3  # ET_DYN / PIE
assert struct.unpack_from("<H", data, 18)[0] == 183  # EM_AARCH64
assert len(data) <= 256 * 1024

readelf_text = readelf_output.read_text(encoding="ascii")
assert "Type:                              DYN" in readelf_text
assert "Machine:                           AArch64" in readelf_text
needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", readelf_text)))
assert set(needed) <= {"libEGL.so", "libGLESv2.so", "libc.so"}
assert "libEGL.so" in needed and "libGLESv2.so" in needed and "libc.so" in needed

payload = {
    "schema": 1,
    "file": elf.name,
    "nativeArtifact": {"file": elf.name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()},
    "size": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
    "elfType": "ET_DYN",
    "machine": 183,
    "pie": True,
    "neededLibraries": needed,
    "sourceSha256": {
        source.name: hashlib.sha256(source.read_bytes()).hexdigest(),
        recipe.name: hashlib.sha256(recipe.read_bytes()).hexdigest(),
        readme.name: hashlib.sha256(readme.read_bytes()).hexdigest(),
    },
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "linkLibraries": ["EGL", "GLESv2"],
    "readelf": readelf_output.name,
}
pathlib.Path(manifest).write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": elf.name, "size": len(data), "sha256": payload["sha256"], "manifest": str(manifest)}, sort_keys=True))
PY
