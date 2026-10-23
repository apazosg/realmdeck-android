#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ARTIFACT_ROOT=/home/adrian/wowforever-artifacts/arm64-prototype
CONTROLS="$ARTIFACT_ROOT/controls"
CANDIDATE="$CONTROLS/virgl-audit/private_egl_candidate-v1"
OUT="$CONTROLS/guest-egl-loader"
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ELF="$OUT/GuestEglLoader"
READELF_OUTPUT="$OUT/GuestEglLoader.readelf.txt"
MANIFEST="$OUT/manifest.json"

test -d "$ARTIFACT_ROOT"
test -d "$CONTROLS"
test -d "$CANDIDATE"
test -x "$CLANG"
test -x "$READELF"
test -f "$CANDIDATE/candidate-manifest.json"

# Verify the already approved candidate before creating this control's output.
python3 - "$CANDIDATE" "$ROOT" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
sys.path.insert(0, sys.argv[2])
import run_guest_loader_usb
_, verified_files, _ = run_guest_loader_usb.load_candidate()
assert len(verified_files) == 28
def file_sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()
manifest_path = root / "candidate-manifest.json"
manifest_bytes = manifest_path.read_bytes()
assert hashlib.sha256(manifest_bytes).hexdigest() == "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9"
document = json.loads(manifest_bytes)
assert document["selectedRegularFileCount"] == 28
assert document["selectedRegularFileBytes"] == 159453576
assert document["selectedAliasCount"] == 13
files = document["materializedFiles"]
assert len(files) == 28
total = 0
for item in files:
    path = root / item["path"]
    assert path.is_file() and not path.is_symlink()
    assert path.stat().st_size == item["size"]
    assert file_sha(path) == item["sha256"]
    total += item["size"]
assert total == 159453576
assert len(document["approvedSymlinkAliases"]) == 13
for relative, expected in {
    "lib/libEGL.so.1.1.0": "88ccad71e437eadaead1729d43ff8bd0fed9a5a70956d0dcd98532f0558ee6e8",
    "lib/libEGL_mesa.so": "7908687035ae31cc24db3face426554e5c0262edd620ea4e13865f9aa474bda5",
    "share/glvnd/egl_vendor.d/50_mesa.json": "cd3881df4227c13ca7f9cfe6c78820f21d5f7a00a41b299214f6f827e63be369",
}.items():
    # The first loader pin is also checked below; keeping these values beside
    # the manifest check makes accidental candidate refreshes fail closed.
    actual = file_sha(root / relative)
    assert actual == expected
vendor = json.loads((root / "share/glvnd/egl_vendor.d/50_mesa.json").read_text())
assert vendor["ICD"]["library_path"] == "libEGL_mesa.so.0"
PY

mkdir -p "$OUT"

"$CLANG" --target=aarch64-linux-android29 -std=c11 -O2 \
  -Wall -Wextra -Werror -fPIE \
  -Wl,--build-id=none -Wl,--as-needed -Wl,--no-undefined -Wl,-z,relro,-z,now \
  -pie -o "$ELF" "$ROOT/GuestEglLoader.c" -ldl
chmod 0755 "$ELF"

"$READELF" --file-header --program-headers --dynamic --needed-libs --dyn-syms "$ELF" > "$READELF_OUTPUT"

python3 - "$ELF" "$ROOT/GuestEglLoader.c" "$ROOT/build_guest_loader.sh" "$ROOT/README-guest-loader.md" \
  "$READELF_OUTPUT" "$MANIFEST" "$CLANG" "$CANDIDATE" <<'PY'
import hashlib
import json
import pathlib
import re
import struct
import sys

elf, source, recipe, readme, readelf_output, manifest, compiler_path, candidate = map(pathlib.Path, sys.argv[1:])
data = elf.read_bytes()
assert data[:4] == b"\x7fELF"
assert data[4:6] == b"\x02\x01"
assert struct.unpack_from("<H", data, 16)[0] == 3
assert struct.unpack_from("<H", data, 18)[0] == 183
assert len(data) <= 64 * 1024
readelf_text = readelf_output.read_text(encoding="ascii")
assert "Type:                              DYN" in readelf_text
assert "Machine:                           AArch64" in readelf_text
assert "Requesting program interpreter: /system/bin/linker64" in readelf_text
needed = sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", readelf_text)))
assert set(needed) == {"libdl.so", "libc.so"}

sha256 = hashlib.sha256(data).hexdigest()
payload = {
    "schema": 1,
    "file": elf.name,
    "size": len(data),
    "sha256": sha256,
    "payloadSha256": sha256,
    "nativeArtifact": {"file": elf.name, "size": len(data), "sha256": sha256},
    "elfType": "ET_DYN",
    "machine": 183,
    "pie": True,
    "interpreter": "/system/bin/linker64",
    "neededLibraries": needed,
    "candidate": {
        "manifestSha256": hashlib.sha256((candidate / "candidate-manifest.json").read_bytes()).hexdigest(),
        "treeSha256": "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216",
        "regularFileCount": 28,
        "regularFileBytes": 159453576,
        "loaderSha256": "88ccad71e437eadaead1729d43ff8bd0fed9a5a70956d0dcd98532f0558ee6e8",
        "vendorSha256": "7908687035ae31cc24db3face426554e5c0262edd620ea4e13865f9aa474bda5",
        "vendorJsonSha256": "cd3881df4227c13ca7f9cfe6c78820f21d5f7a00a41b299214f6f827e63be369",
    },
    "sourceSha256": {
        source.name: hashlib.sha256(source.read_bytes()).hexdigest(),
        recipe.name: hashlib.sha256(recipe.read_bytes()).hexdigest(),
        readme.name: hashlib.sha256(readme.read_bytes()).hexdigest(),
    },
    "ndk": "24.0.8215888",
    "target": "aarch64-linux-android29",
    "compiler": "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
    "compilerSha256": hashlib.sha256(compiler_path.read_bytes()).hexdigest(),
    "compilerSize": compiler_path.stat().st_size,
    "compileFlags": ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "--build-id=none"],
    "linkLibraries": ["dl"],
    "readelf": readelf_output.name,
}
manifest.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="ascii")
print(json.dumps({"file": elf.name, "size": len(data), "sha256": sha256, "manifest": str(manifest)}, sort_keys=True))
PY
