#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
OUT="$CONTROLS/guest-egl-local-logs"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
PATCH=/usr/bin/patch
SHA256SUM=/usr/bin/sha256sum
PYTHON=/usr/bin/python3
INSTALL=/usr/bin/install
MKDIR=/bin/mkdir
CP=/bin/cp
MKTemp=/usr/bin/mktemp
ELF="$OUT/GuestEglLocalLogs"
READELF_OUTPUT="$OUT/GuestEglLocalLogs.readelf.txt"
MANIFEST="$OUT/manifest.json"
SOURCE_CONTEXT="$ROOT/GuestEglContext.c"
SOURCE_LOADER="$ROOT/GuestEglLoader.c"
PATCH_FILE="$ROOT/guest-egl-local-logs.patch"
RECIPE="$ROOT/build_guest_egl_log.sh"
README="$ROOT/README-guest-egl-local-logs.md"
EXPECTED_CONTEXT_SHA256=32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f
EXPECTED_LOADER_SHA256=4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6

for flag in CFLAGS CPPFLAGS CXXFLAGS LDFLAGS; do
    if [[ -n ${!flag-} ]]; then
        printf 'refusing ambient %s\n' "$flag" >&2
        exit 2
    fi
done

test -d "$ARTIFACT_ROOT"
ls -ld -- "$ARTIFACT_ROOT" "$CONTROLS" >/dev/null
test -d "$CONTROLS"
test -f "$SOURCE_CONTEXT"
test -f "$SOURCE_LOADER"
test -f "$PATCH_FILE"
test -f "$README"
test -x "$CLANG"
test -x "$READELF"
test -x "$PATCH"
test -x "$SHA256SUM"
test -x "$PYTHON"
test -x "$INSTALL"
test -x "$MKDIR"
test -x "$CP"
test -x "$MKTemp"

sha256() {
    local actual
    actual=$("$SHA256SUM" "$1")
    printf '%s\n' "${actual%% *}"
}

test "$(sha256 "$SOURCE_CONTEXT")" = "$EXPECTED_CONTEXT_SHA256"
test "$(sha256 "$SOURCE_LOADER")" = "$EXPECTED_LOADER_SHA256"

STAGE=$("$MKTemp" -d "$CONTROLS/.guest-egl-local-logs.XXXXXX")
cleanup() { rm -rf -- "$STAGE"; }
trap cleanup EXIT
"$MKDIR" -p -- "$STAGE/source"
"$CP" -- "$SOURCE_CONTEXT" "$STAGE/source/GuestEglContext.c"
"$CP" -- "$SOURCE_LOADER" "$STAGE/source/GuestEglLoader.c"
test "$(sha256 "$STAGE/source/GuestEglContext.c")" = "$EXPECTED_CONTEXT_SHA256"
test "$(sha256 "$STAGE/source/GuestEglLoader.c")" = "$EXPECTED_LOADER_SHA256"

"$PATCH" --dry-run --batch --fuzz=0 --directory="$STAGE/source" < "$PATCH_FILE" >/dev/null
"$PATCH" --batch --fuzz=0 --directory="$STAGE/source" < "$PATCH_FILE" >/dev/null
cmp -s "$SOURCE_CONTEXT" "$STAGE/source/GuestEglContext.c"
test "$(sha256 "$STAGE/source/GuestEglLoader.c")" != "$EXPECTED_LOADER_SHA256"

"/usr/bin/env" -i PATH=/usr/bin:/bin \
    "$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
    -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
    -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined \
    -Wl,-z,relro,-z,now -pie -o "$STAGE/GuestEglLocalLogs" \
    "$STAGE/source/GuestEglContext.c" -ldl -lc
chmod 0755 "$STAGE/GuestEglLocalLogs"

"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
    "$STAGE/GuestEglLocalLogs" > "$STAGE/GuestEglLocalLogs.readelf.txt"

"$MKDIR" -p -- "$OUT/source"
"$INSTALL" -m 0644 "$STAGE/source/GuestEglContext.c" "$OUT/source/GuestEglContext.c"
"$INSTALL" -m 0644 "$STAGE/source/GuestEglLoader.c" "$OUT/source/GuestEglLoader.c"
"$INSTALL" -m 0644 "$PATCH_FILE" "$OUT/guest-egl-local-logs.patch"
"$INSTALL" -m 0755 "$STAGE/GuestEglLocalLogs" "$ELF"
"$INSTALL" -m 0644 "$STAGE/GuestEglLocalLogs.readelf.txt" "$READELF_OUTPUT"

"$PYTHON" - "$ELF" "$OUT/source/GuestEglContext.c" "$OUT/source/GuestEglLoader.c" \
    "$OUT/guest-egl-local-logs.patch" "$RECIPE" "$README" "$READELF_OUTPUT" \
    "$MANIFEST" "$CLANG" "$PATCH" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

elf, context, loader, patch, recipe, readme, readelf_output, manifest, compiler, patch_tool = map(pathlib.Path, sys.argv[1:])
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
    "originalSourceSha256": {
        "GuestEglContext.c": "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f",
        "GuestEglLoader.c": "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6",
    },
    "resultSourceSha256": {
        context.name: hashlib.sha256(context.read_bytes()).hexdigest(),
        loader.name: hashlib.sha256(loader.read_bytes()).hexdigest(),
    },
    "patch": {"file": patch.name, "sha256": hashlib.sha256(patch.read_bytes()).hexdigest()},
    "recipe": {"file": recipe.name, "sha256": hashlib.sha256(recipe.read_bytes()).hexdigest()},
    "readme": {"file": readme.name, "sha256": hashlib.sha256(readme.read_bytes()).hexdigest()},
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": hashlib.sha256(compiler.read_bytes()).hexdigest(),
    "compilerSize": compiler.stat().st_size,
    "patchTool": {"path": str(patch_tool), "sha256": hashlib.sha256(patch_tool.read_bytes()).hexdigest()},
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "--build-id=none"],
    "linkLibraries": ["dl", "c"],
    "readelf": readelf_output.name,
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": elf.name, "size": len(data), "sha256": digest, "manifest": str(manifest)}, sort_keys=True))
PY
