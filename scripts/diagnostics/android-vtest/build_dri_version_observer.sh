#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
OUT="$CONTROLS/dri-version-limit"
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
HOST_CC=/usr/bin/cc
PROVIDER="$CONTROLS/virgl-audit/private_egl_candidate-v1/lib/libgallium-26.2.4.so"
PROVIDER_SHA256=910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5
PROVIDER_SIZE=17656040

SOURCE_CONTEXT="$ROOT/GuestEglContext.c"
SOURCE_LOADER="$ROOT/GuestEglLoader.c"
SOURCE_OBSERVER="$ROOT/DriVersionObserver.c"
LOGGING_PATCH="$ROOT/guest-egl-local-logs.patch"
VERSION_PATCH="$ROOT/guest-egl-dri-version.patch"
RECIPE="$ROOT/build_dri_version_observer.sh"
README="$ROOT/README-dri-version-observer.md"
EXPECTED_CONTEXT_SHA256=32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f
EXPECTED_LOADER_SHA256=4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6
EXPECTED_LOGGING_PATCH_SHA256=46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d

for flag in CFLAGS CPPFLAGS CXXFLAGS LDFLAGS; do
    if [[ -n ${!flag-} ]]; then
        printf 'refusing ambient %s\n' "$flag" >&2
        exit 2
    fi
done

test -d "$ARTIFACT_ROOT"; test -d "$CONTROLS"
test -f "$SOURCE_CONTEXT"; test -f "$SOURCE_LOADER"; test -f "$SOURCE_OBSERVER"
test -f "$LOGGING_PATCH"; test -f "$VERSION_PATCH"; test -f "$README"
test -f "$PROVIDER"; test -x "$CLANG"; test -x "$READELF"; test -x "$PATCH"
test -x "$SHA256SUM"; test -x "$PYTHON"; test -x "$INSTALL"; test -x "$MKDIR"; test -x "$CP"; test -x "$MKTemp"
test -x "$HOST_CC"

sha256() { local actual; actual=$("$SHA256SUM" "$1"); printf '%s\n' "${actual%% *}"; }
test "$(sha256 "$SOURCE_CONTEXT")" = "$EXPECTED_CONTEXT_SHA256"
test "$(sha256 "$SOURCE_LOADER")" = "$EXPECTED_LOADER_SHA256"
test "$(sha256 "$LOGGING_PATCH")" = "$EXPECTED_LOGGING_PATCH_SHA256"
test "$(stat -c '%s' "$PROVIDER")" = "$PROVIDER_SIZE"
test "$(sha256 "$PROVIDER")" = "$PROVIDER_SHA256"

"$MKDIR" -p -- "$OUT"
STAGE=$("$MKTemp" -d "$CONTROLS/.dri-version-limit.XXXXXX")
cleanup() { rm -rf -- "$STAGE"; }
trap cleanup EXIT
"$MKDIR" -p -- "$STAGE/source"
"$CP" -- "$SOURCE_CONTEXT" "$STAGE/source/GuestEglContext.c"
"$CP" -- "$SOURCE_LOADER" "$STAGE/source/GuestEglLoader.c"
"$CP" -- "$SOURCE_OBSERVER" "$STAGE/source/DriVersionObserver.c"
for input in "$LOGGING_PATCH" "$VERSION_PATCH"; do
    "$PATCH" --dry-run --batch --fuzz=0 --forward --no-backup-if-mismatch --directory="$STAGE/source" < "$input" >/dev/null
    "$PATCH" --batch --fuzz=0 --forward --no-backup-if-mismatch --directory="$STAGE/source" < "$input" >/dev/null
done
cmp -s "$SOURCE_CONTEXT" "$STAGE/source/GuestEglContext.c"
test ! -e "$STAGE/source/GuestEglLoader.c.orig"; test ! -e "$STAGE/source/GuestEglLoader.c.rej"

cat > "$STAGE/layout_preflight.c" <<EOF
#define _GNU_SOURCE
#define DRI_VERSION_OBSERVER_HOST_TEST 1
#include "$SOURCE_OBSERVER"
int main(int argc, char **argv) {
    return argc == 2 && dri_observer_test_provider_layout_path(argv[1]) &&
           dri_observer_test_provider_file_verified(argv[1]) ? 0 : 1;
}
EOF
"/usr/bin/env" -i PATH=/usr/bin:/bin \
    "$HOST_CC" -std=c11 -Wall -Wextra -Werror -DDRI_VERSION_OBSERVER_HOST_TEST \
    -o "$STAGE/layout_preflight" "$STAGE/layout_preflight.c" -ldl
"$STAGE/layout_preflight" "$PROVIDER"

"/usr/bin/env" -i PATH=/usr/bin:/bin \
    "$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
    -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
    -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined \
    -Wl,-z,relro,-z,now -pie -o "$STAGE/GuestEglDriVersion" \
    "$STAGE/source/GuestEglContext.c" -ldl -lc
"/usr/bin/env" -i PATH=/usr/bin:/bin \
    "$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
    -Wall -Wextra -Werror -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden \
    -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined \
    -Wl,-z,relro,-z,now -shared -o "$STAGE/libdri_version_observer.so" \
    "$STAGE/source/DriVersionObserver.c" -ldl -lc
chmod 0755 "$STAGE/GuestEglDriVersion" "$STAGE/libdri_version_observer.so"

"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
    "$STAGE/GuestEglDriVersion" > "$STAGE/GuestEglDriVersion.readelf.txt"
"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
    "$STAGE/libdri_version_observer.so" > "$STAGE/libdri_version_observer.readelf.txt"

"$MKDIR" -p -- "$OUT/source" "$OUT/result"
"$INSTALL" -m 0644 "$STAGE/source/GuestEglContext.c" "$OUT/source/GuestEglContext.c"
"$INSTALL" -m 0644 "$STAGE/source/GuestEglLoader.c" "$OUT/source/GuestEglLoader.c"
"$INSTALL" -m 0644 "$STAGE/source/DriVersionObserver.c" "$OUT/source/DriVersionObserver.c"
"$INSTALL" -m 0644 "$STAGE/source/GuestEglContext.c" "$OUT/result/GuestEglContext.c"
"$INSTALL" -m 0644 "$STAGE/source/GuestEglLoader.c" "$OUT/result/GuestEglLoader.c"
"$INSTALL" -m 0644 "$STAGE/source/DriVersionObserver.c" "$OUT/result/DriVersionObserver.c"
"$INSTALL" -m 0644 "$LOGGING_PATCH" "$OUT/guest-egl-local-logs.patch"
"$INSTALL" -m 0644 "$VERSION_PATCH" "$OUT/guest-egl-dri-version.patch"
"$INSTALL" -m 0755 "$STAGE/GuestEglDriVersion" "$OUT/GuestEglDriVersion"
"$INSTALL" -m 0755 "$STAGE/libdri_version_observer.so" "$OUT/libdri_version_observer.so"
"$INSTALL" -m 0644 "$STAGE/GuestEglDriVersion.readelf.txt" "$OUT/GuestEglDriVersion.readelf.txt"
"$INSTALL" -m 0644 "$STAGE/libdri_version_observer.readelf.txt" "$OUT/libdri_version_observer.readelf.txt"

"$PYTHON" - "$OUT/GuestEglDriVersion" "$OUT/libdri_version_observer.so" \
    "$OUT/GuestEglDriVersion.readelf.txt" "$OUT/libdri_version_observer.readelf.txt" \
    "$OUT/source/GuestEglContext.c" "$OUT/source/GuestEglLoader.c" "$OUT/source/DriVersionObserver.c" \
    "$OUT/result/GuestEglContext.c" "$OUT/result/GuestEglLoader.c" "$OUT/result/DriVersionObserver.c" \
    "$OUT/guest-egl-local-logs.patch" "$OUT/guest-egl-dri-version.patch" \
    "$RECIPE" "$README" "$CLANG" "$PATCH" "$PROVIDER" "$OUT/manifest.json" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def elf_record(elf: pathlib.Path, readelf_path: pathlib.Path) -> dict:
    data = elf.read_bytes()
    assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
    assert struct.unpack_from("<H", data, 16)[0] == 3
    assert struct.unpack_from("<H", data, 18)[0] == 183
    assert len(data) <= 64 * 1024
    text = readelf_path.read_text(encoding="ascii")
    assert "Type:                              DYN" in text
    assert "Machine:                           AArch64" in text
    interpreter = "/system/bin/linker64" if "Requesting program interpreter: /system/bin/linker64" in text else None
    needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", text)))
    imports = sorted(set(re.findall(r"\bUND\s+([^\s]+)$", text, re.MULTILINE)))
    exports = sorted(set(m.group(1) for m in re.finditer(
        r"^\s*\d+:\s+[0-9a-f]+\s+\d+\s+\S+\s+GLOBAL\s+DEFAULT\s+[1-9]\d*\s+(\S+)",
        text, re.MULTILINE)))
    assert set(needed) == {"libc.so", "libdl.so"}
    return {"file": elf.name, "size": len(data), "sha256": sha256(elf),
            "elfType": "ET_DYN", "machine": 183, "pie": interpreter is not None,
            "interpreter": interpreter, "neededLibraries": needed, "imports": imports,
            "exports": exports, "readelf": readelf_path.name}


(child, observer, child_readelf, observer_readelf, context, loader, observer_source,
 result_context, result_loader, result_observer, logging_patch, version_patch,
 recipe, readme, compiler, patch_tool, provider, manifest) = map(pathlib.Path, sys.argv[1:])
payload = {
    "schema": "wowforever.android-dri-version-observer",
    "schemaVersion": 1,
    "artifacts": {"child": elf_record(child, child_readelf), "observer": elf_record(observer, observer_readelf)},
    "sourceSha256": {p.name: sha256(p) for p in (context, loader, observer_source)},
    "resultSourceSha256": {p.name: sha256(p) for p in (result_context, result_loader, result_observer)},
    "patchSha256": {logging_patch.name: sha256(logging_patch), version_patch.name: sha256(version_patch)},
    "recipe": {"file": recipe.name, "sha256": sha256(recipe)},
    "readme": {"file": readme.name, "sha256": sha256(readme)},
    "originalSourceSha256": {"GuestEglContext.c": "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f", "GuestEglLoader.c": "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6"},
    "loggingPatchSha256": "46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d",
    "provider": {"path": str(provider), "relativePath": "guest/lib/libgallium-26.2.4.so", "size": provider.stat().st_size, "sha256": sha256(provider), "functionVirtualAddress": "0xaa856c", "screenFieldOffset": 64, "maximumFieldVirtualAddress": "0xaa88b4", "layout": "ELF PT_LOAD mapped symbol/API3 selection/maximum check", "layoutPreflight": {"method": "dri_observer_test_provider_file_verified", "actualProvider": True, "instructionBytesSha256": hashlib.sha256(bytes.fromhex("9f1100716009467ae8f7ff549f0d0071ee179f1a7f0d0071eb938e1a9f0d00714e00805223f7ff540bf707370b088052050000146b010c2a7f09007142f6ff540b0980520b686bb86e008052ebf5ff34bf010b6ba8f5ff54")).hexdigest()}},
    "ndk": "24.0.8215888", "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": sha256(compiler), "compilerSize": compiler.stat().st_size,
    "patchTool": {"path": str(patch_tool), "sha256": sha256(patch_tool)},
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "--build-id=none"],
    "linkLibraries": ["dl", "c"], "providerContract": {"symbol": "driCreateContextAttribs", "abiArguments": 9, "resolution": "RTLD_NOLOAD|RTLD_NOW|RTLD_LOCAL"},
}
assert payload["artifacts"]["child"]["interpreter"] == "/system/bin/linker64"
assert payload["artifacts"]["child"]["exports"] == []
assert payload["artifacts"]["observer"]["interpreter"] is None
assert payload["artifacts"]["observer"]["exports"] == ["driCreateContextAttribs"]
for artifact in payload["artifacts"].values():
    assert not any(name.startswith(("egl", "gl", "vk")) for name in artifact["imports"])
assert payload["provider"]["size"] == 17656040
assert payload["provider"]["sha256"] == "910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5"
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"manifest": str(manifest), "artifacts": payload["artifacts"]}, sort_keys=True))
PY
