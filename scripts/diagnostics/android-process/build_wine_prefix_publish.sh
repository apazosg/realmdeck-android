#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
OUT="$CONTROLS/wine-prefix-v1"
RUNTIME="$ARTIFACT_ROOT/runtime"
ARCHIVE="$RUNTIME/proton-11.0-90624-arm64ec/prefixPack.txz"
COMPILER="$RUNTIME/proton-11.0-90624-arm64ec/lib/wine/aarch64-windows/d3dcompiler_47.dll"
DXVK="$RUNTIME/dxvk-2.4.1-wow-aarch64-test/system32"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$BIN/aarch64-linux-android29-clang"
REAL_CLANG="$BIN/clang-14"
READELF="$BIN/llvm-readelf"

ls -ld -- "$ARTIFACT_ROOT" "$CONTROLS" "$RUNTIME" >/dev/null
test -x "$CLANG" && test -x "$REAL_CLANG" && test -x "$READELF"
test -f "$ROOT/build_wine_prefix_image.py" && test -f "$ROOT/wine_prefix_publish.c"
test -f "$ARCHIVE" && test -f "$COMPILER" && test -f "$DXVK/dxgi.dll" && test -f "$DXVK/d3d11.dll"
mkdir -p -- "$OUT"
BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT

python3 "$ROOT/build_wine_prefix_image.py" \
  --archive "$ARCHIVE" --compiler "$COMPILER" --dxgi "$DXVK/dxgi.dll" --d3d11 "$DXVK/d3d11.dll" \
  --output-dir "$OUT" --header-output "$BUILD_TMP/wine_prefix_publish_manifest.h"

COMMON_FLAGS=(
  --target=aarch64-linux-android29 -std=c11 -O2 -Wall -Wextra -Werror
  -fPIE -ffunction-sections -fdata-sections -fno-ident
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined
  -Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -pie
)
"$CLANG" "${COMMON_FLAGS[@]}" -I"$BUILD_TMP" -o "$OUT/libforever_wine_prefix.so" "$ROOT/wine_prefix_publish.c" -lc
chmod 0755 "$OUT/libforever_wine_prefix.so"
cp -- "$BUILD_TMP/wine_prefix_publish_manifest.h" "$OUT/wine_prefix_publish_manifest.h"

"$READELF" --file-header --program-headers --dynamic --needed-libs --symbols \
  "$OUT/libforever_wine_prefix.so" > "$OUT/readelf.txt"

python3 - "$ROOT" "$OUT" "$CLANG" "$REAL_CLANG" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

root, out, launcher, actual = map(pathlib.Path, sys.argv[1:])

def digest(path: pathlib.Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()

binary = out / "libforever_wine_prefix.so"
report = (out / "readelf.txt").read_text("ascii")
data = binary.read_bytes()
assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert "Type:                              DYN" in report
assert "Machine:                           AArch64" in report
assert "Requesting program interpreter: /system/bin/linker64" in report
loads = [int(value, 16) for value in re.findall(r"^\s*LOAD\b.*\s(0x[0-9a-f]+)\s*$", report, re.MULTILINE)]
assert loads and all(value == 0x4000 for value in loads)
assert re.findall(r"Shared library: \[([^\]]+)\]", report) == ["libc.so"]

source_names = ["wine_prefix_publish.c", "build_wine_prefix_publish.sh", "build_wine_prefix_image.py"]
source_hashes = {name: digest(root / name) for name in source_names}
manifest_path = out / "prefix-image-manifest.json"
image_manifest = json.loads(manifest_path.read_text("ascii"))
publisher = {
    "file": binary.name, "bytes": len(data), "sha256": digest(binary), "mode": "0755",
    "elfType": "ET_DYN", "machine": 183, "pie": True,
    "interpreter": "/system/bin/linker64", "loadAlignment": 16384,
    "loadSegmentAlignment": 16384, "neededLibraries": ["libc.so"],
}
payload = {
    "schema": "wowforever.wine-prefix-publisher/v1",
    "variant": "wine-prefix-v1",
    "publisher": publisher,
    "image": {"manifestSha256": digest(manifest_path), "zipSha256": image_manifest["zip"]["sha256"], "zipBytes": image_manifest["zip"]["bytes"], "entries": image_manifest["counts"]},
    "sourceSha256": source_hashes,
    "sourceBytes": {name: (root / name).stat().st_size for name in source_names},
    "provenance": {
        "ndk": "24.0.8215888", "target": "aarch64-linux-android29",
        "launcherSha256": digest(launcher), "actualClangSha256": digest(actual),
        "compileFlags": ["--target=aarch64-linux-android29", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-fno-ident", "-pie", "-z,max-page-size=16384", "-z,common-page-size=16384", "--build-id=none", "--as-needed", "--gc-sections", "--no-undefined"],
        "linkLibraries": ["c"], "linker": "linker64",
    },
    "contract": {"cli": ["--run", "WS-[0-9a-f]{32}", "--stage", "derived-prefix-stage", "--imagefs", "fixed-imagefs", "--probe-root", "derived-probe-root"], "deadlineSeconds": 120, "rename": "renameat2(RENAME_NOREPLACE)", "links": ["a:probe-root", "c:relative-drive-c", "z:imagefs-root"]},
}
(out / "publisher-manifest.json").write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"publisher": str(binary), "bytes": len(data), "sha256": digest(binary), "imageZipSha256": image_manifest["zip"]["sha256"], "imageManifestSha256": digest(manifest_path)}, sort_keys=True))
PY
