#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
OUT="$ARTIFACT_ROOT/controls/run-supervisor-capacity-v1"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
REAL_CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang-14"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ELF="$OUT/RunSupervisorCapacity"
MANIFEST="$OUT/manifest.json"

ls -ld -- "$ARTIFACT_ROOT" "$ARTIFACT_ROOT/controls" >/dev/null
test -x "$CLANG" && test -x "$READELF"
test -x "$REAL_CLANG"
test -f "$ROOT/run_supervisor_capacity.c" && test -f "$ROOT/README-run-supervisor.md"
mkdir -p -- "$OUT"
BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT
cp -- "$ROOT/run_supervisor_capacity.c" "$BUILD_TMP/run_supervisor_capacity.c"

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections \
  -Wl,--no-undefined -Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384 \
  -Wl,-z,common-page-size=16384 -pie -o "$ELF" "$BUILD_TMP/run_supervisor_capacity.c" -lc
chmod 0755 "$ELF"
"$READELF" --file-header --program-headers --dynamic --needed-libs "$ELF" > "$OUT/readelf.txt"

python3 - "$ROOT" "$ELF" "$OUT/readelf.txt" "$MANIFEST" "$CLANG" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

root, elf, readelf_path, manifest, compiler = map(pathlib.Path, sys.argv[1:])
data = elf.read_bytes()
report = readelf_path.read_text(encoding="ascii")
assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert "Type:                              DYN" in report
assert "Machine:                           AArch64" in report
assert "Requesting program interpreter: /system/bin/linker64" in report
loads = [int(value, 16) for value in re.findall(r"^\s*LOAD\b.*\s(0x[0-9a-f]+)\s*$", report, re.MULTILINE)]
assert loads and all(value == 0x4000 for value in loads)
needed = re.findall(r"Shared library: \[([^\]]+)\]", report)
assert needed == ["libc.so"]

def digest(path: pathlib.Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()

source_names = ["run_supervisor_capacity.c", "build_run_supervisor_capacity.sh", "README-run-supervisor.md"]
payload = {
    "schema": "wowforever.android-run-supervisor-build/version1",
    "file": elf.name,
    "size": len(data),
    "sha256": digest(elf),
    "mode": "0755",
    "elfType": "ET_DYN",
    "machine": "AArch64",
    "pie": True,
    "interpreter": "/system/bin/linker64",
    "loadAlignment": "0x4000",
    "neededLibraries": needed,
    "sourceSha256": {name: digest(root / name) for name in source_names},
    "sourceBytes": {name: (root / name).stat().st_size for name in source_names},
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": digest(compiler),
    "compilerBytes": compiler.stat().st_size,
    "actualCompiler": "llvm/prebuilt/linux-x86_64/bin/clang-14",
    "actualCompilerSha256": digest(pathlib.Path(sys.argv[5]).with_name("clang-14")),
    "actualCompilerBytes": pathlib.Path(sys.argv[5]).with_name("clang-14").stat().st_size,
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "-z,max-page-size=16384", "--build-id=none"],
    "linkLibraries": ["c"],
    "readelf": readelf_path.name,
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"artifact": str(elf), "bytes": len(data), "sha256": payload["sha256"], "manifest": str(manifest)}, sort_keys=True))
PY
