#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
OUT="$CONTROLS/native-run-supervisor-v2"
MANIFEST="$OUT/manifest.json"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$BIN/aarch64-linux-android29-clang"
REAL_CLANG="$BIN/clang-14"
READELF="$BIN/llvm-readelf"

# The parents are provisioned artifacts locations.  Verify them before creating
# the one new output directory, so a typo cannot redirect a native build.
ls -ld -- "$ARTIFACT_ROOT" "$CONTROLS" >/dev/null
test -x "$CLANG" && test -x "$REAL_CLANG" && test -x "$READELF"
test -f "$ROOT/native_run_common.h"
test -f "$ROOT/native_run_supervisor.c"
test -f "$ROOT/native_run_fixture.c"
mkdir -p -- "$OUT"
BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT

COMMON_FLAGS=(
  --target=aarch64-linux-android29 -std=c11 -O2 -Wall -Wextra -Werror
  -fPIE -ffunction-sections -fdata-sections -fno-ident
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined
  -Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -pie
)
"$CLANG" "${COMMON_FLAGS[@]}" -o "$OUT/libforever_run_supervisor.so" "$ROOT/native_run_supervisor.c" -lc
"$CLANG" "${COMMON_FLAGS[@]}" -o "$OUT/libforever_run_fixture.so" "$ROOT/native_run_fixture.c" -lc
chmod 0755 "$OUT/libforever_run_supervisor.so" "$OUT/libforever_run_fixture.so"

"$READELF" --file-header --program-headers --dynamic --needed-libs \
  "$OUT/libforever_run_supervisor.so" > "$BUILD_TMP/supervisor.readelf"
"$READELF" --file-header --program-headers --dynamic --needed-libs \
  "$OUT/libforever_run_fixture.so" > "$BUILD_TMP/fixture.readelf"

python3 - "$ROOT" "$OUT" "$BUILD_TMP" "$CLANG" "$REAL_CLANG" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

root, out, reports, launcher, actual, manifest = map(pathlib.Path, sys.argv[1:])
source_names = [
    "native_run_common.h",
    "native_run_supervisor.c",
    "native_run_fixture.c",
    "build_native_run_supervisor.sh",
]
files = ["libforever_run_supervisor.so", "libforever_run_fixture.so"]

def digest(path: pathlib.Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()

def inspect(name: str) -> dict[str, object]:
    path = out / name
    data = path.read_bytes()
    report = (reports / ("supervisor.readelf" if "supervisor" in name else "fixture.readelf")).read_text("ascii")
    assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
    assert struct.unpack_from("<H", data, 16)[0] == 3
    assert struct.unpack_from("<H", data, 18)[0] == 183
    assert "Type:                              DYN" in report
    assert "Machine:                           AArch64" in report
    assert "Requesting program interpreter: /system/bin/linker64" in report
    loads = [int(value, 16) for value in re.findall(r"^\s*LOAD\b.*\s(0x[0-9a-f]+)\s*$", report, re.MULTILINE)]
    assert loads and all(value == 0x4000 for value in loads)
    assert re.findall(r"Shared library: \[([^\]]+)\]", report) == ["libc.so"]
    return {
        "file": name,
        "bytes": len(data),
        "sha256": digest(path),
        "elfType": "ET_DYN",
        "machine": 183,
        "elfMachine": 183,
        "pie": True,
        "interpreter": "/system/bin/linker64",
        "loadAlignment": 16384,
        "loadSegmentAlignment": 16384,
        "mode": "0755",
        "neededLibraries": ["libc.so"],
    }

source_digests = {name: digest(root / name) for name in source_names}
payload = {
    "schema": 1,
    "variant": "native-run-supervisor-v2",
    "artifacts": {name: inspect(name) for name in files},
    "source": {
        "sha256": source_digests,
        "bytes": {name: (root / name).stat().st_size for name in source_names},
    },
    "recipe": {
        "file": "build_native_run_supervisor.sh",
        "sha256": source_digests["build_native_run_supervisor.sh"],
    },
    "provenance": {
        "ndk": "24.0.8215888",
        "target": "aarch64-linux-android29",
        "launcherSHA": digest(launcher),
        "actualclangSHA": digest(actual),
        "flags": [
            "--target=aarch64-linux-android29", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-fPIE", "-fno-ident", "-pie", "-z,max-page-size=16384", "--build-id=none",
        ],
    },
    "sourceSha256": source_digests,
    "sourceBytes": {name: (root / name).stat().st_size for name in source_names},
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": digest(launcher),
    "compilerBytes": launcher.stat().st_size,
    "actualCompiler": "toolchains/llvm/prebuilt/linux-x86_64/bin/clang-14",
    "actualCompilerSha256": digest(actual),
    "actualCompilerBytes": actual.stat().st_size,
    "compileFlags": [
        "--target=aarch64-linux-android29", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-fPIE", "-fno-ident", "-pie", "-z,max-page-size=16384", "--build-id=none",
    ],
    "linkLibraries": ["c"],
    "linker": "linker64",
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"output": str(out), "manifest": str(manifest)}, sort_keys=True))
PY
