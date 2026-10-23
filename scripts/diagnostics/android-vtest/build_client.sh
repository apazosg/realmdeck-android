#!/usr/bin/env bash
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
SOURCE="$ROOT/scripts/diagnostics/android-vtest/VtestHandshake.c"
ARTIFACT_PARENT="/home/adrian/wowforever-artifacts/arm64-prototype/controls"
ARTIFACT_DIR="$ARTIFACT_PARENT/vtest-client"
BINARY="$ARTIFACT_DIR/VtestHandshake"
MANIFEST="$ARTIFACT_DIR/manifest.json"
NDK="/home/adrian/android-sdk/ndk/24.0.8215888"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

# Do not create an output tree after an unexpected parent path.
ls -ld -- "$ARTIFACT_PARENT" >/dev/null
test -f "$SOURCE"
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

size=$(stat -c '%s' "$BINARY")
test "$size" -le 65536
source_sha=$(sha256sum "$SOURCE" | cut -d ' ' -f 1)
compiler_sha=$(sha256sum "$CLANG" | cut -d ' ' -f 1)
binary_sha=$(sha256sum "$BINARY" | cut -d ' ' -f 1)
compiler_version=$($CLANG --version)
compiler_version=${compiler_version%%$'\n'*}

printf '%b\n' \
    "{\n  \"schema\": \"android-vtest-handshake\",\n  \"artifact\": \"VtestHandshake\",\n  \"bytes\": $size,\n  \"sha256\": \"$binary_sha\",\n  \"machine\": \"AArch64\",\n  \"elfType\": \"ET_DYN\",\n  \"interpreter\": \"/system/bin/linker64\",\n  \"needed\": [\"libc.so\"],\n  \"source\": {\"path\": \"$SOURCE\", \"sha256\": \"$source_sha\"},\n  \"compiler\": {\"path\": \"$CLANG\", \"sha256\": \"$compiler_sha\", \"version\": \"$compiler_version\"},\n  \"flags\": [\"-std=c11\", \"-Wall\", \"-Wextra\", \"-Werror\", \"-fPIE\", \"-pie\", \"-Wl,--as-needed\", \"-Wl,--build-id=none\"]\n}" \
    > "$MANIFEST"

chmod 0755 "$BINARY"
chmod 0644 "$MANIFEST"
printf 'artifact=%s\nbytes=%s\nsha256=%s\nmanifest=%s\n' "$BINARY" "$size" "$binary_sha" "$MANIFEST"
