#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
OUT="$CONTROLS/guest-egl-context"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ELF="$OUT/GuestEglContext"
READELF_OUTPUT="$OUT/GuestEglContext.readelf.txt"
MANIFEST="$OUT/manifest.json"

# The parent control directory must exist before this recipe can create its
# new output.  No candidate, APK, device, or downloaded input is created here.
ls -ld -- "$ARTIFACT_ROOT" "$CONTROLS" >/dev/null
test -d "$ARTIFACT_ROOT"
test -d "$CONTROLS"
test -f "$ROOT/GuestEglContext.c"
test -f "$ROOT/GuestEglLoader.c"
test -f "$ROOT/README-guest-context.md"
test -x "$CLANG"
test -x "$READELF"

mkdir -p -- "$OUT"

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined \
  -Wl,-z,relro,-z,now -pie -o "$ELF" "$ROOT/GuestEglContext.c" -ldl -lc
chmod 0755 "$ELF"

"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms "$ELF" > "$READELF_OUTPUT"

python3 - "$ELF" "$ROOT/GuestEglContext.c" "$ROOT/GuestEglLoader.c" \
  "$ROOT/build_guest_context.sh" "$ROOT/README-guest-context.md" \
  "$READELF_OUTPUT" "$MANIFEST" "$CLANG" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

elf, source, included, recipe, readme, readelf_output, manifest, compiler = map(pathlib.Path, sys.argv[1:])
data = elf.read_bytes()
assert data[:4] == b"\x7fELF"
assert data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert len(data) <= 64 * 1024
readelf = readelf_output.read_text(encoding="ascii")
assert "Type:                              DYN" in readelf
assert "Machine:                           AArch64" in readelf
assert "Requesting program interpreter: /system/bin/linker64" in readelf
needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", readelf)))
assert set(needed) == {"libc.so", "libdl.so"}

digest = hashlib.sha256(data).hexdigest()
payload = {
    "schema": 1,
    "file": elf.name,
    "size": len(data),
    "sha256": digest,
    "nativeArtifact": {"file": elf.name, "size": len(data), "sha256": digest},
    "elfType": "ET_DYN",
    "machine": 183,
    "pie": True,
    "interpreter": "/system/bin/linker64",
    "neededLibraries": needed,
    "sourceSha256": {
        source.name: hashlib.sha256(source.read_bytes()).hexdigest(),
        included.name: hashlib.sha256(included.read_bytes()).hexdigest(),
        recipe.name: hashlib.sha256(recipe.read_bytes()).hexdigest(),
        readme.name: hashlib.sha256(readme.read_bytes()).hexdigest(),
    },
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": hashlib.sha256(compiler.read_bytes()).hexdigest(),
    "compilerSize": compiler.stat().st_size,
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "--build-id=none"],
    "linkLibraries": ["dl", "c"],
    "readelf": readelf_output.name,
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": elf.name, "size": len(data), "sha256": digest, "manifest": str(manifest)}, sort_keys=True))
PY
