#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
ARTIFACT_PARENT="$ARTIFACT_ROOT/controls"
OUT="$ARTIFACT_PARENT/kbase-context-metadata"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ELF="$OUT/KbaseContextMetadata"
READELF_OUTPUT="$OUT/KbaseContextMetadata.readelf.txt"
MANIFEST="$OUT/manifest.json"

# This recipe owns only the new external artifact directory and never touches a
# device, loader, ICD, or the original context-free metadata control.
ls -ld -- "$ARTIFACT_ROOT" "$ARTIFACT_PARENT" >/dev/null
test -d "$ARTIFACT_ROOT" && test -d "$ARTIFACT_PARENT"
test -x "$CLANG" && test -x "$READELF"
test -f "$ROOT/KbaseContextMetadata.c" && test -f "$ROOT/README-context.md"

mkdir -p -- "$OUT"
BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT
cp -- "$ROOT/KbaseContextMetadata.c" "$BUILD_TMP/KbaseContextMetadata.c"

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections \
  -Wl,--no-undefined -Wl,-z,relro,-z,now -pie \
  -o "$ELF" "$BUILD_TMP/KbaseContextMetadata.c" -lc
chmod 0755 "$ELF"

"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms "$ELF" > "$READELF_OUTPUT"

python3 - "$ELF" "$ROOT" "$READELF_OUTPUT" "$MANIFEST" "$CLANG" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

elf, root, readelf_path, manifest, compiler = map(pathlib.Path, sys.argv[1:])
data = elf.read_bytes()
readelf = readelf_path.read_text(encoding="ascii")
assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert len(data) <= 64 * 1024
assert "Type:                              DYN" in readelf
assert "Machine:                           AArch64" in readelf
assert "Requesting program interpreter: /system/bin/linker64" in readelf
needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", readelf)))
assert needed == ["libc.so"]

def sha256(path: pathlib.Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()

source_names = ["KbaseContextMetadata.c", "build_context_probe.sh", "README-context.md"]
symbols = [line.strip() for line in readelf.splitlines() if " FUNC " in line or " OBJECT " in line]
payload = {
    "schema": 1,
    "file": elf.name,
    "size": len(data),
    "sha256": sha256(elf),
    "elfType": "ET_DYN",
    "machine": 183,
    "pie": True,
    "interpreter": "/system/bin/linker64",
    "neededLibraries": needed,
    "sourceAuthority": {
        "portRepository": "https://github.com/zenithblue-oss/panvk-kbase-android",
        "portCommit": "de577411e8f27da9de50aec26a0d8cc43890745e",
        "sourceHash": "de577411e8f27da9de50aec26a0d8cc43890745e",
        "kbaseKmodSource": "patches/kbase-common/files/src/panfrost/lib/kmod/kbase_kmod.c",
        "kbaseUapiSource": "patches/kbase-common/files/src/panfrost/lib/kmod/kbase_uapi.h",
        "ioctlHeader": "patches/kbase-common/files/include/drm-uapi/mali_kbase_ioctl.h",
        "armCsfHeader": "patches/kbase-common/files/src/panfrost/lib/kmod/mali_base_csf_kernel.h (Arm public CSF UAPI)",
    },
    "sourceSha256": {name: sha256(root / name) for name in source_names},
    "sourceBytes": {name: (root / name).stat().st_size for name in source_names},
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": sha256(compiler),
    "compilerBytes": compiler.stat().st_size,
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "--build-id=none"],
    "linkLibraries": ["c"],
    "symbols": symbols,
    "readelf": readelf_path.name,
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": elf.name, "size": len(data), "sha256": payload["sha256"], "manifest": str(manifest)}, sort_keys=True))
PY
