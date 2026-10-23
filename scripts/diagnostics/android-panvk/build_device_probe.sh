#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
ARTIFACT_PARENT="$ARTIFACT_ROOT/controls"
OUT="$ARTIFACT_PARENT/panvk-device-queue-v5"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ICD_SOURCE="$ARTIFACT_PARENT/panvk-audit-beta17/icd/libvulkan_panfrost.so"
HEADER=/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h
HELPER_SOURCE="$ROOT/PanvkPrivateLoader.c"
HELPER_SHA256="cc8ea0c352ff582de80ef493d6060504a5bd1b11057604a1d55dd59875b3a4b7"
HEADER_SHA256="c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71"
ICD_SHA256="5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
ELF="$OUT/PanvkDeviceQueue"
READELF_OUTPUT="$OUT/PanvkDeviceQueue.readelf.txt"
MANIFEST="$OUT/manifest.json"

# The parent is checked before any creation.  A pre-existing output is never
# reused or overwritten; each native control owns a fresh external directory.
ls -ld -- "$ARTIFACT_ROOT" "$ARTIFACT_PARENT" >/dev/null
test -d "$ARTIFACT_ROOT" && test -d "$ARTIFACT_PARENT"
test -x "$CLANG" && test -x "$READELF"
test -f "$ROOT/PanvkDeviceQueue.c" && test -f "$ROOT/README-device.md"
test -f "$HELPER_SOURCE" && test -f "$HEADER" && test -f "$ICD_SOURCE"
test "$(sha256sum "$HELPER_SOURCE" | awk '{print $1}')" = "$HELPER_SHA256"
test "$(sha256sum "$HEADER" | awk '{print $1}')" = "$HEADER_SHA256"
test ! -e "$OUT"
mkdir -- "$OUT"
BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
  -I"$(dirname "$(dirname "$HEADER")")" \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections \
  -Wl,--no-undefined -Wl,-z,relro,-z,now -pie \
  -o "$BUILD_TMP/PanvkDeviceQueue" "$ROOT/PanvkDeviceQueue.c" -ldl -lc
chmod 0755 "$BUILD_TMP/PanvkDeviceQueue"
"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
  "$BUILD_TMP/PanvkDeviceQueue" > "$BUILD_TMP/PanvkDeviceQueue.readelf.txt"

python3 - "$BUILD_TMP/PanvkDeviceQueue" "$BUILD_TMP/PanvkDeviceQueue.readelf.txt" \
  "$ROOT" "$ICD_SOURCE" "$HEADER" "$HELPER_SOURCE" "$HELPER_SHA256" \
  "$READELF" "$CLANG" "$ELF" "$READELF_OUTPUT" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import re
import shutil
import stat
import struct
import subprocess
import sys

(built, report_path, root, icd, header, helper, helper_sha256, readelf_tool,
 compiler, final_elf, final_report, manifest) = map(pathlib.Path, sys.argv[1:])

def digest(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def report_for(path: pathlib.Path) -> str:
    return subprocess.check_output(
        [str(readelf_tool), "--file-header", "--program-headers", "--dynamic",
         "--needed-libs", "--dyn-syms", str(path)], text=True, encoding="ascii"
    )

report = report_path.read_text(encoding="ascii")
data = built.read_bytes()
assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert len(data) <= 64 * 1024
assert "Type:                              DYN" in report
assert "Machine:                           AArch64" in report
assert "Requesting program interpreter: /system/bin/linker64" in report
needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", report)))
assert needed == ["libc.so", "libdl.so"]
assert not re.search(r"Shared library: \[(?:libvulkan|libGL|libEGL)", report)
assert not re.search(r"UND.*\b(?:vk|gl|egl)[A-Za-z0-9_]", report, re.IGNORECASE)
assert stat.S_IMODE(built.stat().st_mode) == 0o755

icd_stat = icd.lstat()
icd_report = report_for(icd)
assert stat.S_ISREG(icd_stat.st_mode) and not stat.S_ISLNK(icd_stat.st_mode)
assert "Class:                             ELF64" in icd_report
assert "Type:                              DYN" in icd_report
assert "Machine:                           AArch64" in icd_report
assert icd.stat().st_size == 31_142_504
assert stat.S_IMODE(icd_stat.st_mode) == 0o600
assert digest(icd) == "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"

source_names = ["PanvkDeviceQueue.c", "build_device_probe.sh", "README-device.md"]
payload = {
    "schema": "wowforever.android-panvk-device-queue-build/version1",
    "file": final_elf.name,
    "size": len(data),
    "sha256": digest(built),
    "mode": "0755",
    "elfType": "ET_DYN",
    "machine": "AArch64",
    "pie": True,
    "interpreter": "/system/bin/linker64",
    "neededLibraries": needed,
    "sourceSha256": {name: digest(root / name) for name in source_names},
    "sourceBytes": {name: (root / name).stat().st_size for name in source_names},
    "helperSource": {"path": str(helper), "bytes": helper.stat().st_size,
                      "sha256": str(helper_sha256)},
    "header": {"path": str(header), "bytes": header.stat().st_size,
               "sha256": digest(header)},
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": str(compiler),
    "compilerSha256": digest(compiler),
    "compilerBytes": compiler.stat().st_size,
    "compileFlags": [
        "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie",
        "--build-id=none", "-Wl,--no-undefined", "-Wl,-z,relro,-z,now",
    ],
    "linkLibraries": ["c", "dl"],
    "readelf": final_report.name,
    "icdPin": {"sourcePath": str(icd), "bytes": icd.stat().st_size,
               "mode": format(stat.S_IMODE(icd_stat.st_mode), "04o"),
               "sha256": digest(icd),
               "elf": {"type": "ET_DYN", "machine": "AArch64",
                       "reportSha256": hashlib.sha256(icd_report.encode("ascii")).hexdigest()}},
}
final_elf.write_bytes(data)
shutil.copymode(built, final_elf)
final_report.write_text(report, encoding="ascii")
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": str(final_elf), "size": len(data),
                  "sha256": payload["sha256"], "manifest": str(manifest)}, sort_keys=True))
PY

test -f "$ELF" && test -f "$READELF_OUTPUT" && test -f "$MANIFEST"
