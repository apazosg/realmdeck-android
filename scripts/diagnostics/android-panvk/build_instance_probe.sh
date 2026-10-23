#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
ARTIFACT_PARENT="$ARTIFACT_ROOT/controls"
OUT="$ARTIFACT_PARENT/panvk-instance-metadata-v4"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ICD_SOURCE="$ARTIFACT_PARENT/panvk-audit-beta16/icd/libvulkan_panfrost.so"
HEADER=/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h
HELPER_SOURCE="$ROOT/PanvkPrivateLoader.c"
HELPER_SHA256="c15a849033826a881f1da424de1f2560a5dafabb13da298f1ce380e3639af29a"
ELF="$OUT/PanvkInstanceMetadata"
READELF_OUTPUT="$OUT/PanvkInstanceMetadata.readelf.txt"
MANIFEST="$OUT/manifest.json"

ls -ld -- "$ARTIFACT_ROOT" "$ARTIFACT_PARENT" >/dev/null
test -d "$ARTIFACT_ROOT" && test -d "$ARTIFACT_PARENT"
test -x "$CLANG" && test -x "$READELF"
test -f "$ROOT/PanvkInstanceMetadata.c" && test -f "$ROOT/README-instance.md" && test -f "$ROOT/run_instance_usb.py"
test -f "$HELPER_SOURCE"
test -f "$ICD_SOURCE" && test -f "$HEADER"
test "$(sha256sum "$HELPER_SOURCE" | awk '{print $1}')" = "$HELPER_SHA256"
test ! -e "$OUT"
mkdir -- "$OUT"
BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
  -I"$(dirname "$(dirname "$HEADER")")" \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections \
  -Wl,--no-undefined -Wl,-z,relro,-z,now -pie \
  -o "$BUILD_TMP/PanvkInstanceMetadata" "$ROOT/PanvkInstanceMetadata.c" -ldl -lc
chmod 0755 "$BUILD_TMP/PanvkInstanceMetadata"
"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
  "$BUILD_TMP/PanvkInstanceMetadata" > "$BUILD_TMP/PanvkInstanceMetadata.readelf.txt"

python3 - "$BUILD_TMP/PanvkInstanceMetadata" "$BUILD_TMP/PanvkInstanceMetadata.readelf.txt" \
  "$ROOT" "$ICD_SOURCE" "$HEADER" "$HELPER_SOURCE" "$HELPER_SHA256" "$READELF" "$CLANG" "$ELF" "$READELF_OUTPUT" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import re
import shutil
import stat
import struct
import subprocess
import sys

(built, report_path, root, icd, header, helper, helper_sha256, readelf, compiler,
 final_elf, final_report, manifest) = map(pathlib.Path, sys.argv[1:])

def digest(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

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
assert stat.S_ISREG(icd_stat.st_mode) and not stat.S_ISLNK(icd_stat.st_mode)
assert icd.stat().st_size == 30_972_472
assert stat.S_IMODE(icd_stat.st_mode) == 0o600
assert digest(icd) == "c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c"

source_names = ["PanvkInstanceMetadata.c", "build_instance_probe.sh", "README-instance.md"]
assert digest(helper) == str(helper_sha256)
payload = {
    "schema": "wowforever.android-panvk-instance-metadata-build/version1",
    "file": final_elf.name, "size": len(data), "sha256": digest(built), "mode": "0755",
    "elfType": "ET_DYN", "machine": "AArch64", "pie": True,
    "interpreter": "/system/bin/linker64", "neededLibraries": needed,
    "sourceSha256": {name: digest(root / name) for name in source_names},
    "sourceBytes": {name: (root / name).stat().st_size for name in source_names},
    "helperSource": {"path": str(helper), "bytes": helper.stat().st_size,
                      "sha256": str(helper_sha256)},
    "header": {"path": str(header), "bytes": header.stat().st_size, "sha256": digest(header)},
    "ndk": "24.0.8215888", "target": "aarch64-linux-android29",
    "compiler": str(compiler), "compilerSha256": digest(compiler),
    "compilerBytes": compiler.stat().st_size,
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "--build-id=none", "-Wl,--no-undefined", "-Wl,-z,relro,-z,now"],
    "linkLibraries": ["c", "dl"], "readelf": final_report.name,
    "icdPin": {"sourcePath": str(icd), "bytes": icd.stat().st_size, "mode": format(stat.S_IMODE(icd_stat.st_mode), "04o"), "sha256": digest(icd)},
}
final_elf.write_bytes(data)
shutil.copymode(built, final_elf)
final_report.write_text(report, encoding="ascii")
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": str(final_elf), "size": len(data), "sha256": payload["sha256"], "manifest": str(manifest)}, sort_keys=True))
PY

test -f "$ELF" && test -f "$READELF_OUTPUT" && test -f "$MANIFEST"
