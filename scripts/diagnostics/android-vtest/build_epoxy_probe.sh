#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
OUT="$ARTIFACT_ROOT/controls/egl-epoxy"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ELF="$OUT/EglEpoxyProbe"
READELF_OUTPUT="$OUT/EglEpoxyProbe.readelf.txt"
MANIFEST="$OUT/manifest.json"

test -d "$ARTIFACT_ROOT"
test -d "$ARTIFACT_ROOT/controls"
test -x "$CLANG"
test -x "$READELF"
mkdir -p "$OUT"

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--no-undefined -Wl,-z,relro,-z,now \
  -pie -o "$ELF" "$ROOT/EglEpoxyProbe.c" -ldl

"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms "$ELF" > "$READELF_OUTPUT"

python3 - "$ELF" "$ROOT/EglEpoxyProbe.c" "$ROOT/build_epoxy_probe.sh" "$ROOT/README-epoxy.md" \
  "$READELF_OUTPUT" "$MANIFEST" "$CLANG" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

elf, source, recipe, readme, readelf_output, manifest, compiler_path = map(pathlib.Path, sys.argv[1:])
data = elf.read_bytes()
assert data[:4] == b"\x7fELF"
assert data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert len(data) <= 64 * 1024

readelf_text = readelf_output.read_text(encoding="ascii")
assert "Type:                              DYN" in readelf_text
assert "Machine:                           AArch64" in readelf_text
assert "Requesting program interpreter: /system/bin/linker64" in readelf_text
needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", readelf_text)))
assert set(needed) <= {"libdl.so", "libc.so"}
assert set(needed) == {"libdl.so", "libc.so"}

sha256 = hashlib.sha256(data).hexdigest()
payload = {
    "schema": 1,
    "file": elf.name,
    "size": len(data),
    "sha256": sha256,
    "payloadSha256": sha256,
    "nativeArtifact": {"file": elf.name, "size": len(data), "sha256": sha256},
    "elfType": "ET_DYN",
    "machine": 183,
    "pie": True,
    "interpreter": "/system/bin/linker64",
    "neededLibraries": needed,
    "sourceSha256": {
        source.name: hashlib.sha256(source.read_bytes()).hexdigest(),
        recipe.name: hashlib.sha256(recipe.read_bytes()).hexdigest(),
        readme.name: hashlib.sha256(readme.read_bytes()).hexdigest(),
    },
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": hashlib.sha256(compiler_path.read_bytes()).hexdigest(),
    "compilerSize": compiler_path.stat().st_size,
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "--build-id=none"],
    "linkLibraries": ["dl"],
    "readelf": readelf_output.name,
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": elf.name, "size": len(data), "sha256": sha256, "manifest": str(manifest)}, sort_keys=True))
PY
