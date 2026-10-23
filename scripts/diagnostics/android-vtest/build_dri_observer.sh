#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
OUT="$CONTROLS/dri-return-observer"
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

SOURCE_CONTEXT="$ROOT/GuestEglContext.c"
SOURCE_LOADER="$ROOT/GuestEglLoader.c"
SOURCE_OBSERVER="$ROOT/DriReturnObserver.c"
LOGGING_PATCH="$ROOT/guest-egl-local-logs.patch"
OBSERVER_PATCH="$ROOT/guest-egl-dri-observer.patch"
RECIPE="$ROOT/build_dri_observer.sh"
README="$ROOT/README-dri-observer.md"
EXPECTED_CONTEXT_SHA256=32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f
EXPECTED_LOADER_SHA256=4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6
EXPECTED_LOGGING_PATCH_SHA256=46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d

for flag in CFLAGS CPPFLAGS CXXFLAGS LDFLAGS; do
    if [[ -n ${!flag-} ]]; then
        printf 'refusing ambient %s\n' "$flag" >&2
        exit 2
    fi
done

test -d "$ARTIFACT_ROOT"
test -d "$CONTROLS"
test -f "$SOURCE_CONTEXT"
test -f "$SOURCE_LOADER"
test -f "$SOURCE_OBSERVER"
test -f "$LOGGING_PATCH"
test -f "$OBSERVER_PATCH"
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
test "$(sha256 "$LOGGING_PATCH")" = "$EXPECTED_LOGGING_PATCH_SHA256"

"$MKDIR" -p -- "$OUT"
STAGE=$("$MKTemp" -d "$CONTROLS/.dri-return-observer.XXXXXX")
cleanup() { rm -rf -- "$STAGE"; }
trap cleanup EXIT
"$MKDIR" -p -- "$STAGE/source"
"$CP" -- "$SOURCE_CONTEXT" "$STAGE/source/GuestEglContext.c"
"$CP" -- "$SOURCE_LOADER" "$STAGE/source/GuestEglLoader.c"
"$CP" -- "$SOURCE_OBSERVER" "$STAGE/source/DriReturnObserver.c"
test "$(sha256 "$STAGE/source/GuestEglContext.c")" = "$EXPECTED_CONTEXT_SHA256"
test "$(sha256 "$STAGE/source/GuestEglLoader.c")" = "$EXPECTED_LOADER_SHA256"
test "$(sha256 "$STAGE/source/DriReturnObserver.c")" = "$(sha256 "$SOURCE_OBSERVER")"

"$PATCH" --dry-run --batch --fuzz=0 --forward --no-backup-if-mismatch --directory="$STAGE/source" < "$LOGGING_PATCH" >/dev/null
"$PATCH" --batch --fuzz=0 --forward --no-backup-if-mismatch --directory="$STAGE/source" < "$LOGGING_PATCH" >/dev/null
"$PATCH" --dry-run --batch --fuzz=0 --forward --no-backup-if-mismatch --directory="$STAGE/source" < "$OBSERVER_PATCH" >/dev/null
"$PATCH" --batch --fuzz=0 --forward --no-backup-if-mismatch --directory="$STAGE/source" < "$OBSERVER_PATCH" >/dev/null
cmp -s "$SOURCE_CONTEXT" "$STAGE/source/GuestEglContext.c"
test "$(sha256 "$STAGE/source/GuestEglLoader.c")" != "$EXPECTED_LOADER_SHA256"
test "$(sha256 "$STAGE/source/GuestEglLoader.c")" = "480631fd093493585b7d461a32a30133e83c90d2064c3d6d66ca25e7fddad584"
test ! -e "$STAGE/source/GuestEglLoader.c.orig"
test ! -e "$STAGE/source/GuestEglLoader.c.rej"

"/usr/bin/env" -i PATH=/usr/bin:/bin \
    "$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
    -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
    -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined \
    -Wl,-z,relro,-z,now -pie -o "$STAGE/GuestEglDriObservation" \
    "$STAGE/source/GuestEglContext.c" -ldl -lc
"/usr/bin/env" -i PATH=/usr/bin:/bin \
    "$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
    -Wall -Wextra -Werror -fPIC -fdata-sections -ffunction-sections -fvisibility=hidden \
    -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined \
    -Wl,-z,relro,-z,now -shared -o "$STAGE/libdri_return_observer.so" \
    "$STAGE/source/DriReturnObserver.c" -ldl -lc
chmod 0755 "$STAGE/GuestEglDriObservation" "$STAGE/libdri_return_observer.so"

"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
    "$STAGE/GuestEglDriObservation" > "$STAGE/GuestEglDriObservation.readelf.txt"
"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
    "$STAGE/libdri_return_observer.so" > "$STAGE/libdri_return_observer.readelf.txt"

"$MKDIR" -p -- "$OUT/source"
"$INSTALL" -m 0644 "$STAGE/source/GuestEglContext.c" "$OUT/source/GuestEglContext.c"
"$INSTALL" -m 0644 "$STAGE/source/GuestEglLoader.c" "$OUT/source/GuestEglLoader.c"
"$INSTALL" -m 0644 "$STAGE/source/DriReturnObserver.c" "$OUT/source/DriReturnObserver.c"
"$INSTALL" -m 0644 "$LOGGING_PATCH" "$OUT/guest-egl-local-logs.patch"
"$INSTALL" -m 0644 "$OBSERVER_PATCH" "$OUT/guest-egl-dri-observer.patch"
"$INSTALL" -m 0755 "$STAGE/GuestEglDriObservation" "$OUT/GuestEglDriObservation"
"$INSTALL" -m 0755 "$STAGE/libdri_return_observer.so" "$OUT/libdri_return_observer.so"
"$INSTALL" -m 0644 "$STAGE/GuestEglDriObservation.readelf.txt" "$OUT/GuestEglDriObservation.readelf.txt"
"$INSTALL" -m 0644 "$STAGE/libdri_return_observer.readelf.txt" "$OUT/libdri_return_observer.readelf.txt"

"$PYTHON" - "$OUT/GuestEglDriObservation" "$OUT/libdri_return_observer.so" \
    "$OUT/GuestEglDriObservation.readelf.txt" "$OUT/libdri_return_observer.readelf.txt" \
    "$OUT/source/GuestEglContext.c" "$OUT/source/GuestEglLoader.c" "$OUT/source/DriReturnObserver.c" \
    "$OUT/guest-egl-local-logs.patch" "$OUT/guest-egl-dri-observer.patch" \
    "$RECIPE" "$README" "$CLANG" "$PATCH" "$OUT/manifest.json" <<'PY'
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
    assert data[:4] == b"\x7fELF"
    assert data[4:6] == b"\x02\x01"
    assert struct.unpack_from("<H", data, 16)[0] == 3
    assert struct.unpack_from("<H", data, 18)[0] == 183
    assert len(data) <= 64 * 1024
    text = readelf_path.read_text(encoding="ascii")
    assert "Type:                              DYN" in text
    assert "Machine:                           AArch64" in text
    interpreter = "/system/bin/linker64" if "Requesting program interpreter: /system/bin/linker64" in text else None
    needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", text)))
    assert set(needed) == {"libc.so", "libdl.so"}
    imports = sorted(set(re.findall(r"\bUND\s+([^\s]+)$", text, re.MULTILINE)))
    exports = sorted(set(
        match.group(1) for match in re.finditer(
            r"^\s*\d+:\s+[0-9a-f]+\s+\d+\s+\S+\s+GLOBAL\s+DEFAULT\s+[1-9]\d*\s+(\S+)",
            text, re.MULTILINE,
        )
    ))
    return {
        "file": elf.name,
        "size": len(data),
        "sha256": sha256(elf),
        "elfType": "ET_DYN",
        "machine": 183,
        "pie": interpreter is not None,
        "interpreter": interpreter,
        "neededLibraries": needed,
        "imports": imports,
        "exports": exports,
        "readelf": readelf_path.name,
    }


(child, observer, child_readelf, observer_readelf, context, loader, observer_source,
 logging_patch, observer_patch, recipe, readme, compiler, patch_tool, manifest) = map(pathlib.Path, sys.argv[1:])
payload = {
    "schema": "wowforever.android-dri-return-observer",
    "schemaVersion": 1,
    "artifacts": {
        "child": elf_record(child, child_readelf),
        "observer": elf_record(observer, observer_readelf),
    },
    "sourceSha256": {
        "GuestEglContext.c": sha256(context),
        "GuestEglLoader.c": sha256(loader),
        "DriReturnObserver.c": sha256(observer_source),
        "result/GuestEglContext.c": sha256(context),
        "result/GuestEglLoader.c": sha256(loader),
        "result/DriReturnObserver.c": sha256(observer_source),
    },
    "patchSha256": {
        logging_patch.name: sha256(logging_patch),
        observer_patch.name: sha256(observer_patch),
    },
    "recipe": {"file": recipe.name, "sha256": sha256(recipe)},
    "readme": {"file": readme.name, "sha256": sha256(readme)},
    "originalSourceSha256": {
        "GuestEglContext.c": "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f",
        "GuestEglLoader.c": "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6",
    },
    "loggingPatchSha256": "46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d",
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": sha256(compiler),
    "compilerSize": compiler.stat().st_size,
    "patchTool": {"path": str(patch_tool), "sha256": sha256(patch_tool)},
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "--build-id=none"],
    "linkLibraries": ["dl", "c"],
    "provider": {
        "relativePath": "guest/lib/libgallium-26.2.4.so",
        "resolution": "RTLD_NOLOAD|RTLD_NOW|RTLD_LOCAL",
        "symbol": "driCreateContextAttribs",
        "abiArguments": 9,
    },
}
assert payload["artifacts"]["child"]["interpreter"] == "/system/bin/linker64"
assert payload["artifacts"]["child"]["exports"] == []
assert payload["artifacts"]["observer"]["interpreter"] is None
assert payload["artifacts"]["observer"]["exports"] == ["driCreateContextAttribs"]
for artifact in payload["artifacts"].values():
    assert not any(name.startswith(("egl", "gl", "vk")) for name in artifact["imports"])
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"manifest": str(manifest), "artifacts": payload["artifacts"]}, sort_keys=True))
PY
