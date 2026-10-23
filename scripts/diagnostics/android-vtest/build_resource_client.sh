#!/usr/bin/env bash
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
SOURCE="$ROOT/scripts/diagnostics/android-vtest/VtestResource.c"
INCLUDED_SOURCE="$ROOT/scripts/diagnostics/android-vtest/VtestHandshake.c"
ARTIFACT_PARENT="/home/adrian/wowforever-artifacts/arm64-prototype/controls"
ARTIFACT_DIR="$ARTIFACT_PARENT/vtest-resource"
BINARY="$ARTIFACT_DIR/VtestResource"
MANIFEST="$ARTIFACT_DIR/manifest.json"
NDK="/home/adrian/android-sdk/ndk/24.0.8215888"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

# Never create an artifact tree after an unexpected parent path.
ls -ld -- "$ARTIFACT_PARENT" >/dev/null
test -f "$SOURCE"
test -f "$INCLUDED_SOURCE"
test -x "$CLANG"
test -x "$READELF"
mkdir -p -- "$ARTIFACT_DIR"

"$CLANG" \
    -std=c11 -Wall -Wextra -Werror -O2 -fPIE -pie -Wl,--as-needed \
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
compiler_version=$($CLANG --version)
compiler_version=${compiler_version%%$'\n'*}

printf '%b\n' \
    "{\n  \"schema\": \"android-vtest-resource\",\n  \"artifact\": \"VtestResource\",\n  \"bytes\": $size,\n  \"mode\": \"$mode\",\n  \"sha256\": \"$binary_sha\",\n  \"machine\": \"AArch64\",\n  \"elfType\": \"ET_DYN\",\n  \"interpreter\": \"/system/bin/linker64\",\n  \"needed\": [\"libc.so\"],\n  \"source\": {\"path\": \"$SOURCE\", \"sha256\": \"$source_sha\"},\n  \"includedSource\": {\"path\": \"$INCLUDED_SOURCE\", \"sha256\": \"$included_source_sha\"},\n  \"recipe\": {\"path\": \"$ROOT/scripts/diagnostics/android-vtest/build_resource_client.sh\", \"sha256\": \"$recipe_sha\"},\n  \"compiler\": {\"path\": \"$CLANG\", \"sha256\": \"$compiler_sha\", \"version\": \"$compiler_version\"},\n  \"flags\": [\"-std=c11\", \"-Wall\", \"-Wextra\", \"-Werror\", \"-O2\", \"-fPIE\", \"-pie\", \"-Wl,--as-needed\", \"-Wl,--build-id=none\"]\n}" \
    > "$MANIFEST"
chmod 0644 "$MANIFEST"
printf 'artifact=%s\nbytes=%s\nsha256=%s\nmanifest=%s\n' "$BINARY" "$size" "$binary_sha" "$MANIFEST"
