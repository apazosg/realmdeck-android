#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
ARTIFACT_PARENT="$ARTIFACT_ROOT/controls"
OUT="$ARTIFACT_PARENT/panvk-private-loader-v2"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ICD_SOURCE="$ARTIFACT_PARENT/panvk-audit-beta16/icd/libvulkan_panfrost.so"
ELF="$OUT/PanvkPrivateLoader"
READELF_OUTPUT="$OUT/PanvkPrivateLoader.readelf.txt"
MANIFEST="$OUT/manifest.json"

# This recipe owns only this new directory.  Refusing an existing directory
# prevents an accidental overwrite of an older or unrelated control.
ls -ld -- "$ARTIFACT_ROOT" "$ARTIFACT_PARENT" >/dev/null
test -d "$ARTIFACT_ROOT" && test -d "$ARTIFACT_PARENT"
test -x "$CLANG" && test -x "$READELF"
test -f "$ROOT/PanvkPrivateLoader.c" && test -f "$ROOT/README-private-loader.md"
test -f "$ICD_SOURCE"
test ! -e "$OUT"
mkdir -- "$OUT"
BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections \
  -Wl,--no-undefined -Wl,-z,relro,-z,now -pie \
  -o "$BUILD_TMP/PanvkPrivateLoader" "$ROOT/PanvkPrivateLoader.c" -ldl -lc
chmod 0755 "$BUILD_TMP/PanvkPrivateLoader"
"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms \
  "$BUILD_TMP/PanvkPrivateLoader" > "$BUILD_TMP/PanvkPrivateLoader.readelf.txt"

python3 - "$BUILD_TMP/PanvkPrivateLoader" "$BUILD_TMP/PanvkPrivateLoader.readelf.txt" \
  "$ROOT" "$ICD_SOURCE" "$READELF" "$CLANG" "$ELF" "$READELF_OUTPUT" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import re
import shutil
import stat
import struct
import subprocess
import sys

(built, readelf_path, root, icd, readelf_tool, compiler, final_elf,
 final_readelf, manifest) = map(pathlib.Path, sys.argv[1:])

def digest(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def elf_report(path: pathlib.Path) -> str:
    return subprocess.check_output(
        [str(readelf_tool), "--file-header", "--program-headers", "--dynamic", "--needed-libs", str(path)],
        text=True,
        encoding="ascii",
    )

report = readelf_path.read_text(encoding="ascii")
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

icd_report = elf_report(icd)
icd_stat = icd.lstat()
assert stat.S_ISREG(icd_stat.st_mode) and not stat.S_ISLNK(icd_stat.st_mode)
assert "Class:                             ELF64" in icd_report
assert "Data:                              2's complement, little endian" in icd_report
assert "Type:                              DYN" in icd_report
assert "Machine:                           AArch64" in icd_report
assert icd.stat().st_size == 30_972_472
assert stat.S_IMODE(icd_stat.st_mode) == 0o600
assert digest(icd) == "c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c"

source_names = ["PanvkPrivateLoader.c", "build_private_loader.sh", "README-private-loader.md"]
payload = {
    "schema": "wowforever.android-panvk-private-loader-build/version1",
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
    "readelf": final_readelf.name,
    "icdPin": {
        "sourcePath": str(icd),
        "bytes": icd.stat().st_size,
        "mode": format(stat.S_IMODE(icd_stat.st_mode), "04o"),
        "sha256": digest(icd),
        "elf": {
            "type": "ET_DYN",
            "machine": "AArch64",
            "reportSha256": hashlib.sha256(icd_report.encode("ascii")).hexdigest(),
        },
    },
}
final_elf.write_bytes(data)
shutil.copymode(built, final_elf)
final_readelf.write_text(report, encoding="ascii")
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": str(final_elf), "size": len(data), "sha256": payload["sha256"], "manifest": str(manifest)}, sort_keys=True))
PY

test -f "$ELF" && test -f "$READELF_OUTPUT" && test -f "$MANIFEST"
