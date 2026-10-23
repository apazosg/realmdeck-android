#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
OUT="$CONTROLS/panvk-wsi-v9"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$BIN/aarch64-linux-android29-clang"
READELF="$BIN/llvm-readelf"
VULKAN_INCLUDE=/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include
SPIRV_INCLUDE="$CONTROLS/panvk-triangle-v1"
ICD="$CONTROLS/panvk-audit-beta17/icd/libvulkan_panfrost.so"
ICD_SHA256=5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9
VULKAN_SHA256=c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71
SPIRV_SHA256=7c089a9b4c00ff5d626e70ab17753ac6a8dfe1c7a656f72ea83b44725de92eb1

ls -ld -- "$ARTIFACT_ROOT" "$CONTROLS" >/dev/null
test -x "$CLANG" && test -x "$READELF"
test -f "$ROOT/PanvkWsi.c" && test -f "$ROOT/README-wsi.md"
test -f "$ICD" && test -f "$SPIRV_INCLUDE/triangle_spirv.h"
test "$(sha256sum "$ICD" | awk '{print $1}')" = "$ICD_SHA256"
test "$(sha256sum "$VULKAN_INCLUDE/vulkan/vulkan_core.h" | awk '{print $1}')" = "$VULKAN_SHA256"
test "$(sha256sum "$SPIRV_INCLUDE/triangle_spirv.h" | awk '{print $1}')" = "$SPIRV_SHA256"
test ! -e "$OUT"
mkdir -- "$OUT"
TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$TMP"' EXIT

"$CLANG" --target=aarch64-linux-android29 -std=c11 -Os \
  -Wall -Wextra -Werror -fPIC -fvisibility=hidden \
  -ffunction-sections -fdata-sections -DVK_NO_PROTOTYPES=1 \
  -DVK_USE_PLATFORM_ANDROID_KHR=1 -I"$VULKAN_INCLUDE" -I"$SPIRV_INCLUDE" \
  -shared -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections \
  -Wl,--no-undefined -Wl,-z,relro,-z,now \
  -o "$TMP/libpanvk_wsi_probe.so" "$ROOT/PanvkWsi.c" -landroid -ldl -lc
chmod 0755 "$TMP/libpanvk_wsi_probe.so"
"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
  "$TMP/libpanvk_wsi_probe.so" > "$TMP/libpanvk_wsi_probe.readelf.txt"

python3 - "$TMP/libpanvk_wsi_probe.so" "$TMP/libpanvk_wsi_probe.readelf.txt" \
  "$OUT" "$ROOT" "$CLANG" "$READELF" "$ICD" "$VULKAN_INCLUDE/vulkan/vulkan_core.h" \
  "$SPIRV_INCLUDE/triangle_spirv.h" <<'PY'
import hashlib
import json
import pathlib
import re
import shutil
import stat
import struct
import subprocess
import sys

(built, report_path, out, root, compiler, readelf, icd, header, spirv) = map(pathlib.Path, sys.argv[1:])

def digest(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

report = report_path.read_text(encoding="ascii")
data = built.read_bytes()
assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert len(data) <= 256 * 1024
assert "Type:                              DYN" in report
assert "Machine:                           AArch64" in report
needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", report)))
assert needed == ["libandroid.so", "libc.so", "libdl.so"]
assert not re.search(r"UND.*\b(?:vk|gl|egl)[A-Za-z0-9_]", report, re.I)
exports = re.findall(r"GLOBAL DEFAULT\s+\d+\s+(Java_app_gamenative_PanvkWsiBridge_native\w+)$", report, re.M)
assert sorted(exports) == [
    "Java_app_gamenative_PanvkWsiBridge_nativeCancel",
    "Java_app_gamenative_PanvkWsiBridge_nativeRun",
]
icd_report = subprocess.check_output(
    [str(readelf), "--file-header", "--dynamic", str(icd)], text=True, encoding="ascii"
)
icd_stat = icd.lstat()
assert stat.S_ISREG(icd_stat.st_mode) and not stat.S_ISLNK(icd_stat.st_mode)
assert "Class:                             ELF64" in icd_report
assert "Machine:                           AArch64" in icd_report
assert icd.stat().st_size == 31_142_504
assert digest(icd) == "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
payload = {
    "schema": "wowforever.android-panvk-wsi-build/version5",
    "file": built.name, "size": len(data), "sha256": digest(built), "mode": "0755",
    "elfType": "ET_DYN", "machine": "AArch64", "shared": True,
    "neededLibraries": needed, "exports": sorted(exports),
    "source": {name: {"bytes": (root / name).stat().st_size, "sha256": digest(root / name)}
               for name in ("PanvkWsi.c", "build_wsi.sh", "README-wsi.md")},
    "ndk": "24.0.8215888", "target": "aarch64-linux-android29",
    "compiler": str(compiler), "compilerBytes": compiler.stat().st_size,
    "compilerSha256": digest(compiler), "readelf": str(readelf),
    "compileFlags": ["-std=c11", "-Os", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared", "-Wl,--no-undefined", "-Wl,-z,relro,-z,now"],
    "linkLibraries": ["android", "dl", "c"],
    "jniAbi": ["Java_app_gamenative_PanvkWsiBridge_nativeRun", "Java_app_gamenative_PanvkWsiBridge_nativeCancel"],
    "vulkanHeader": {"path": str(header), "sha256": digest(header)},
    "spirvHeader": {"path": str(spirv), "bytes": spirv.stat().st_size, "sha256": digest(spirv), "vertexBytes": 944, "fragmentBytes": 304},
    "icdPin": {"path": str(icd), "bytes": icd.stat().st_size, "mode": format(stat.S_IMODE(icd_stat.st_mode), "04o"), "sha256": digest(icd)},
    "limits": {"jsonBytes": 65536, "journalBytes": 65536, "journalRows": 256, "calls": 96, "pfnRecords": 64, "frames": 3, "surface": [320, 240], "instanceExtensions": 256, "surfaceFormats": 32, "presentModes": 16, "images": 4},
}
(out / built.name).write_bytes(data)
shutil.copymode(built, out / built.name)
(out / report_path.name).write_text(report, encoding="ascii")
(out / "manifest.json").write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": str(out / built.name), "size": len(data), "sha256": payload["sha256"]}, sort_keys=True))
PY

test -f "$OUT/libpanvk_wsi_probe.so" && test -f "$OUT/manifest.json"
