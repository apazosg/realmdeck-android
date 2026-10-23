#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
CANDIDATE="$CONTROLS/virgl-audit/private_egl_candidate-v1"
OUT="$CONTROLS/guest-egl-profiles"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ELF="$OUT/GuestEglProfiles"
READELF_OUTPUT="$OUT/GuestEglProfiles.readelf.txt"
MANIFEST="$OUT/manifest.json"

# Never create the external output until the approved parent and candidate
# have been checked.  This recipe performs no device, server, or network work.
ls -ld -- "$ARTIFACT_ROOT" "$CONTROLS" "$CANDIDATE" >/dev/null
test -d "$ARTIFACT_ROOT"
test -d "$CONTROLS"
test -d "$CANDIDATE"
test -x "$CLANG"
test -x "$READELF"
test -f "$CANDIDATE/candidate-manifest.json"
test -f "$ROOT/GuestEglProfiles.c"
test -f "$ROOT/GuestEglContext.c"
test -f "$ROOT/GuestEglLoader.c"
test -f "$ROOT/README-guest-profiles.md"

# The same full candidate validator used by the existing guest-loader control
# runs before this probe is compiled.  Importing the validator is offline.
python3 - "$ROOT" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
sys.path.insert(0, str(root))
import run_guest_loader_usb

document, files, manifest_raw = run_guest_loader_usb.load_candidate()
assert hashlib.sha256(manifest_raw).hexdigest() == "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9"
assert document["selectedRegularFileCount"] == 28
assert document["selectedRegularFileBytes"] == 159453576
assert len(document["approvedSymlinkAliases"]) == 13
assert len(files) == 28
assert run_guest_loader_usb.TREE_SHA256 == "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216"
for relative, expected in {
    "lib/libEGL.so.1.1.0": "88ccad71e437eadaead1729d43ff8bd0fed9a5a70956d0dcd98532f0558ee6e8",
    "lib/libEGL_mesa.so": "7908687035ae31cc24db3face426554e5c0262edd620ea4e13865f9aa474bda5",
    "share/glvnd/egl_vendor.d/50_mesa.json": "cd3881df4227c13ca7f9cfe6c78820f21d5f7a00a41b299214f6f827e63be369",
}.items():
    path = run_guest_loader_usb.CANDIDATE_ROOT / relative
    assert path.is_file() and not path.is_symlink()
    with path.open("rb") as stream:
        assert hashlib.file_digest(stream, "sha256").hexdigest() == expected
vendor = json.loads((run_guest_loader_usb.CANDIDATE_ROOT / "share/glvnd/egl_vendor.d/50_mesa.json").read_text())
assert vendor["ICD"]["library_path"] == "libEGL_mesa.so.0"
PY

mkdir -p -- "$OUT"

BUILD_TMP=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_TMP"' EXIT
cp -- "$ROOT/GuestEglProfiles.c" "$ROOT/GuestEglContext.c" "$ROOT/GuestEglLoader.c" "$BUILD_TMP/"
python3 - "$BUILD_TMP/GuestEglContext.c" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
needle = "int main(int argc, char **argv) {"
assert source.count(needle) == 1
path.write_text(source.replace(needle, "int unused_context_main(int argc, char **argv) {", 1), encoding="utf-8")
PY

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--gc-sections \
  -Wl,--no-undefined -Wl,-z,relro,-z,now -pie \
  -o "$ELF" "$BUILD_TMP/GuestEglProfiles.c" -ldl -lc
chmod 0755 "$ELF"

"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms "$ELF" > "$READELF_OUTPUT"

python3 - "$ELF" "$ROOT" "$READELF_OUTPUT" "$MANIFEST" "$CLANG" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

elf, root, readelf_output, manifest, compiler = map(pathlib.Path, sys.argv[1:])
data = elf.read_bytes()
assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert len(data) <= 64 * 1024
readelf = readelf_output.read_text(encoding="ascii")
assert "Type:                              DYN" in readelf
assert "Machine:                           AArch64" in readelf
assert "Requesting program interpreter: /system/bin/linker64" in readelf
needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", readelf)))
assert set(needed) == {"libc.so", "libdl.so"}

def sha256(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

digest = sha256(elf)
source_names = ["GuestEglProfiles.c", "GuestEglContext.c", "GuestEglLoader.c", "build_guest_profiles.sh", "README-guest-profiles.md"]
payload = {
    "schema": 1,
    "file": elf.name,
    "size": len(data),
    "sha256": digest,
    "nativeArtifact": {"file": elf.name, "size": len(data), "sha256": digest},
    "elfType": "ET_DYN",
    "machine": 183,
    "pie": True,
    "interpreter": "/system/bin/linker64",
    "neededLibraries": needed,
    "guest": {
        "manifestSha256": "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9",
        "treeSha256": "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216",
        "loaderSha256": "88ccad71e437eadaead1729d43ff8bd0fed9a5a70956d0dcd98532f0558ee6e8",
        "vendorSha256": "7908687035ae31cc24db3face426554e5c0262edd620ea4e13865f9aa474bda5",
        "vendorJsonSha256": "cd3881df4227c13ca7f9cfe6c78820f21d5f7a00a41b299214f6f827e63be369",
    },
    "serverManifestSha256": "b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28",
    "sourceSha256": {name: sha256(root / name) for name in source_names},
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": sha256(compiler),
    "compilerSize": compiler.stat().st_size,
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "--build-id=none"],
    "linkLibraries": ["dl", "c"],
    "readelf": readelf_output.name,
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": elf.name, "size": len(data), "sha256": digest, "manifest": str(manifest)}, sort_keys=True))
PY
