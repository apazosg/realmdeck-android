#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
OUT="$CONTROLS/wine-run-supervisor-v1"
MANIFEST="$OUT/manifest.json"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$BIN/aarch64-linux-android29-clang"
REAL_CLANG="$BIN/clang-14"
READELF="$BIN/llvm-readelf"

ls -ld -- "$ARTIFACT_ROOT" "$CONTROLS" >/dev/null
test -x "$CLANG" && test -x "$REAL_CLANG" && test -x "$READELF"
test -f "$ROOT/wine_run_supervisor_common.h"
test -f "$ROOT/wine_run_supervisor.c"
test -f "$ROOT/build_wine_run_supervisor.sh"
mkdir -p -- "$OUT"
BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT

COMMON_FLAGS=(
  --target=aarch64-linux-android29 -std=c11 -O2 -Wall -Wextra -Werror
  -fPIE -ffunction-sections -fdata-sections -fno-ident
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections -Wl,--no-undefined
  -Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -pie
)

"$CLANG" "${COMMON_FLAGS[@]}" -o "$OUT/libforever_wine_supervisor.so" \
  "$ROOT/wine_run_supervisor.c" -lc
chmod 0755 "$OUT/libforever_wine_supervisor.so"

"$READELF" --file-header --program-headers --dynamic --needed-libs --symbols \
  "$OUT/libforever_wine_supervisor.so" > "$OUT/readelf.txt"

python3 - "$ROOT" "$OUT" "$CLANG" "$REAL_CLANG" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

root, out, launcher, actual, manifest = map(pathlib.Path, sys.argv[1:])

def digest(path: pathlib.Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()

binary = out / "libforever_wine_supervisor.so"
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

sources = ["wine_run_supervisor_common.h", "wine_run_supervisor.c", "build_wine_run_supervisor.sh"]
source_hashes = {name: digest(root / name) for name in sources}
payload = {
    "schema": 1,
    "variant": "wine-run-supervisor-v1",
    "artifacts": {
        "libforever_wine_supervisor.so": {
            "file": binary.name,
            "bytes": len(data),
            "sha256": digest(binary),
            "mode": "0755",
            "elfType": "ET_DYN",
            "machine": 183,
            "elfMachine": 183,
            "pie": True,
            "interpreter": "/system/bin/linker64",
            "loadAlignment": 16384,
            "loadSegmentAlignment": 16384,
            "neededLibraries": ["libc.so"],
        }
    },
    "source": {
        "sha256": source_hashes,
        "bytes": {name: (root / name).stat().st_size for name in sources},
    },
    "recipe": {
        "file": "build_wine_run_supervisor.sh",
        "sha256": source_hashes["build_wine_run_supervisor.sh"],
    },
    "provenance": {
        "ndk": "24.0.8215888",
        "target": "aarch64-linux-android29",
        "launcherSHA256": digest(launcher),
        "actualClangSHA256": digest(actual),
        "flags": [
            "--target=aarch64-linux-android29", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-fPIE", "-ffunction-sections", "-fdata-sections", "-fno-ident", "-pie",
            "-z,relro", "-z,now", "-z,max-page-size=16384", "-z,common-page-size=16384",
            "--build-id=none", "--as-needed", "--gc-sections", "--no-undefined",
        ],
        "linkLibraries": ["c"],
        "linker": "linker64",
    },
    "pins": {
        "proton": "11.0-90624-arm64ec",
        "wineserver": {
            "path": "bin/wineserver",
            "sha256": "804d676d11f228c565182d9b49317f42d0fca093b5c1c20a0960ba5753c199f2",
        },
        "wine": {
            "path": "lib/wine/aarch64-unix/wine",
            "sha256": "389c7e15f699471042845a24fa47ce7d4e3276fd23181e0afb78b63b91cecc3f",
        },
        "ntdll": {
            "path": "lib/wine/aarch64-unix/ntdll.so",
            "sha256": "4239686f121164bd3d96f8a44db81eb708ac9e30fe783c53d0291d93dbade42c",
        },
    },
    "protocol": {
        "schema": 2,
        "commandKeys": ["schema", "runId", "nonce", "seq", "command", "phase"],
        "eventKeys": ["schema", "runId", "nonce", "seq", "event", "phase", "pid", "startTicks", "ppid", "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode", "childCount", "echild", "setResult", "getResult", "getValue", "prefixDev", "prefixIno", "socketDev", "socketIno", "lockDev", "lockIno"],
        "operationSeconds": 45,
        "cleanupSeconds": 10,
        "watchdogSeconds": 55,
        "maxLineBytes": 8192,
        "maxOutputBytes": 262144,
    },
    "environment": {
        "runtimeDllPath": "lib/wine/aarch64-windows",
        "forbiddenInherited": ["WINESERVERSOCKET", "LD_PRELOAD", "WINEPREFIX", "WINEDLLPATH"],
        "standardHandles": "stdin-dev-null-stdout-stderr-private-bounded-phase-pipes",
    },
    "ownership": {
        "prefix": "fd-dev-ino-uid-mode-revalidated-before-each-phase-and-exec",
        "children": "proc-task-children64-uid4-ppid-startTicks-waitid-WNOWAIT",
        "finalChildCheck": "waitid-P_ALL-WNOHANG-WNOWAIT-no-raw-waitpid-sweep",
        "sigchld": "SIG_DFL-required-no-SA_NOCLDWAIT",
    },
    "logCapture": {
        "directory": ".supervisor-output",
        "files": ["server.stdout", "server.stderr", "client.stdout", "client.stderr"],
        "directoryMode": "0700",
        "fileMode": "0600",
        "create": "mkdirat-prefix-fd-and-openat-CREATE_NEW",
        "perStreamBytes": 65536,
        "combinedBytes": 262144,
        "ipcSeparate": True,
        "stdin": "/dev/null",
        "terminalRequires": "all-pipes-EOF-fsync-files-fsync-directory",
    },
    "sourceSha256": source_hashes,
    "sourceBytes": {name: (root / name).stat().st_size for name in sources},
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"output": str(out), "manifest": str(manifest), "bytes": len(data), "sha256": digest(binary)}, sort_keys=True))
PY
