#!/usr/bin/env bash
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
SOURCE="$ROOT/scripts/diagnostics/android-vtest/VtestTriangle.c"
INCLUDED_SOURCE="$ROOT/scripts/diagnostics/android-vtest/VtestHandshake.c"
ARTIFACT_PARENT="/home/adrian/wowforever-artifacts/arm64-prototype/controls"
ARTIFACT_DIR="$ARTIFACT_PARENT/vtest-triangle"
BINARY="$ARTIFACT_DIR/VtestTriangle"
MANIFEST="$ARTIFACT_DIR/manifest.json"
NDK="/home/adrian/android-sdk/ndk/24.0.8215888"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
SOURCE_ROOT="/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-android-default-v2/source/virglrenderer-virglrenderer-1.3.0"
CANDIDATE_MANIFEST="/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-android-default-v2/candidate_android_default_v2_verified/candidate-manifest.json"
OFFICIAL_TAR="/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-source-audit-1.3.0/official-source.tar.gz"

# Refuse to create output below an unexpected tree and never execute the target.
ls -ld -- "$ARTIFACT_PARENT" >/dev/null
test -f "$SOURCE"
test -f "$INCLUDED_SOURCE"
test -d "$SOURCE_ROOT"
test -f "$CANDIDATE_MANIFEST"
test -f "$OFFICIAL_TAR"
test -x "$CLANG"
test -x "$READELF"

test "$(sha256sum "$CANDIDATE_MANIFEST" | cut -d ' ' -f 1)" = \
    b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28
test "$(sha256sum "$OFFICIAL_TAR" | cut -d ' ' -f 1)" = \
    56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce

REF_VTEST_PROTOCOL="$SOURCE_ROOT/vtest/vtest_protocol.h"
REF_CMD="$SOURCE_ROOT/tests/test_virgl_cmd.c"
REF_ENCODER="$SOURCE_ROOT/tests/testvirgl_encode.c"
REF_PROTOCOL="$SOURCE_ROOT/src/virgl_protocol.h"
REF_HW="$SOURCE_ROOT/src/virgl_hw.h"
REF_DEFINES="$SOURCE_ROOT/src/gallium/include/pipe/p_defines.h"
for reference in "$REF_VTEST_PROTOCOL" "$REF_CMD" "$REF_ENCODER" "$REF_PROTOCOL" "$REF_HW" "$REF_DEFINES"; do
    test -f "$reference"
done
test "$(sha256sum "$REF_VTEST_PROTOCOL" | cut -d ' ' -f 1)" = d1f83473cd0c6c79ab6f17fe04b7ac4540b6a26f52a710bd5d7f763d632d9fa7
test "$(sha256sum "$REF_CMD" | cut -d ' ' -f 1)" = f3549bed207a955c2a1deccd6c9d026890469ac8d93487730212b7099e824221
test "$(sha256sum "$REF_ENCODER" | cut -d ' ' -f 1)" = e0f0ca545a82c0b5fa86f2fdeef1da3bb390c28e405eae291dd82dc31b174058
test "$(sha256sum "$REF_PROTOCOL" | cut -d ' ' -f 1)" = 094a6e2b210f1d3dd0f1ac254ed8820c1d889e57d2735c0c6194dbc94f3b1ac7
test "$(sha256sum "$REF_HW" | cut -d ' ' -f 1)" = 191ca5cea527949a29e9753b1e31d550f2fed922895d78dabc5c5c3585aa2ff7
test "$(sha256sum "$REF_DEFINES" | cut -d ' ' -f 1)" = 29b02c15399768ab74054e711f6633c407cf5f952f591c046e7542b82e54a008

mkdir -p -- "$ARTIFACT_DIR"
"$CLANG" \
    -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie \
    -Wl,--as-needed \
    -Wl,--build-id=none \
    "$SOURCE" -o "$BINARY"

HEADER=$($READELF -h "$BINARY")
PROGRAM_HEADERS=$($READELF -l "$BINARY")
DYNAMIC=$($READELF -d "$BINARY")
case "$HEADER" in *"Type:                              DYN"*|*"Type: DYN"*) ;; *)
    printf '%s\n' "unexpected ELF type" >&2; exit 1;; esac
case "$HEADER" in *"Machine:                           AArch64"*|*"Machine: AArch64"*) ;; *)
    printf '%s\n' "unexpected ELF machine" >&2; exit 1;; esac
case "$PROGRAM_HEADERS" in *"Requesting program interpreter: /system/bin/linker64"*) ;; *)
    printf '%s\n' "unexpected ELF interpreter" >&2; exit 1;; esac

needed_count=0
while IFS= read -r line; do
    case "$line" in
        *"(NEEDED)"*)
            needed_count=$((needed_count + 1))
            case "$line" in *"Shared library: [libc.so]"*) ;; *)
                printf '%s\n' "unexpected DT_NEEDED" >&2; exit 1;; esac
            ;;
    esac
done <<< "$DYNAMIC"
test "$needed_count" -eq 1

chmod 0755 "$BINARY"
size=$(stat -c '%s' "$BINARY")
mode=$(stat -c '%a' "$BINARY")
test "$mode" = 755
test "$size" -le 65536

source_sha=$(sha256sum "$SOURCE" | cut -d ' ' -f 1)
included_source_sha=$(sha256sum "$INCLUDED_SOURCE" | cut -d ' ' -f 1)
recipe_sha=$(sha256sum "$0" | cut -d ' ' -f 1)
compiler_sha=$(sha256sum "$CLANG" | cut -d ' ' -f 1)
binary_sha=$(sha256sum "$BINARY" | cut -d ' ' -f 1)
candidate_sha=$(sha256sum "$CANDIDATE_MANIFEST" | cut -d ' ' -f 1)
official_tar_sha=$(sha256sum "$OFFICIAL_TAR" | cut -d ' ' -f 1)
compiler_version=$($CLANG --version)
compiler_version=${compiler_version%%$'\n'*}
header_json=${HEADER//$'\n'/\\\\n}
program_headers_json=${PROGRAM_HEADERS//$'\n'/\\\\n}
dynamic_json=${DYNAMIC//$'\n'/\\\\n}

printf '%b\n' \
    "{\n  \"schema\": \"android-vtest-triangle\",\n  \"artifact\": \"VtestTriangle\",\n  \"bytes\": $size,\n  \"mode\": \"$mode\",\n  \"sha256\": \"$binary_sha\",\n  \"machine\": \"AArch64\",\n  \"elfType\": \"ET_DYN\",\n  \"interpreter\": \"/system/bin/linker64\",\n  \"needed\": [\"libc.so\"],\n  \"source\": {\"path\": \"$SOURCE\", \"sha256\": \"$source_sha\"},\n  \"commonHandshake\": {\"path\": \"$INCLUDED_SOURCE\", \"sha256\": \"$included_source_sha\"},\n  \"builder\": {\"path\": \"$ROOT/scripts/diagnostics/android-vtest/build_triangle_client.sh\", \"sha256\": \"$recipe_sha\"},\n  \"candidateManifest\": {\"path\": \"$CANDIDATE_MANIFEST\", \"sha256\": \"$candidate_sha\"},\n  \"officialSourceTar\": {\"path\": \"$OFFICIAL_TAR\", \"sha256\": \"$official_tar_sha\"},\n  \"officialReferences\": {\"vtestProtocol\": \"$(sha256sum "$REF_VTEST_PROTOCOL" | cut -d ' ' -f 1)\", \"officialTriangle\": \"$(sha256sum "$REF_CMD" | cut -d ' ' -f 1)\", \"officialEncoder\": \"$(sha256sum "$REF_ENCODER" | cut -d ' ' -f 1)\", \"virglProtocol\": \"$(sha256sum "$REF_PROTOCOL" | cut -d ' ' -f 1)\", \"virglHardware\": \"$(sha256sum "$REF_HW" | cut -d ' ' -f 1)\", \"pipeDefines\": \"$(sha256sum "$REF_DEFINES" | cut -d ' ' -f 1)\"},\n  \"actualReadelf\": {\"header\": \"$header_json\", \"programHeaders\": \"$program_headers_json\", \"dynamic\": \"$dynamic_json\"},\n  \"compiler\": {\"path\": \"$CLANG\", \"sha256\": \"$compiler_sha\", \"version\": \"$compiler_version\"},\n  \"flags\": [\"-std=c11\", \"-O2\", \"-Wall\", \"-Wextra\", \"-Werror\", \"-fPIE\", \"-pie\", \"-Wl,--as-needed\", \"-Wl,--build-id=none\"]\n}" \
    > "$MANIFEST"
chmod 0644 "$MANIFEST"
printf 'artifact=%s\nbytes=%s\nsha256=%s\nmanifest=%s\n' "$BINARY" "$size" "$binary_sha" "$MANIFEST"
