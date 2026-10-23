#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")/../.." rev-parse --show-toplevel)"
ARTIFACT_ROOT="${RUNTIME_PROTOTYPE_ARTIFACT_ROOT:-/home/adrian/wowforever-artifacts/integrated-runtime}"
BASE_BUILD="$ARTIFACT_ROOT/base-build"
PROBE_BUILD="$ARTIFACT_ROOT/probe-build"
PROBE_SOURCE="$ARTIFACT_ROOT/probe-sources"
PROBE_BIN="$ARTIFACT_ROOT/probes"
SOURCE_COMMIT="0c8068f617737d9c0fb6246f37b54f85b620b09d"
JDK_HOME="${RUNTIME_PROTOTYPE_JAVA_HOME:-$ARTIFACT_ROOT/toolchains/jdk-17.0.20.1+1}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/adrian/android-sdk}}"
OVERLAY="$ROOT/scripts/runtime-prototype/overlays"
PATCH_DIR="$ROOT/scripts/runtime-prototype/patches"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }

[[ -d "$BASE_BUILD/.git" ]] || fail "clean base checkout missing: $BASE_BUILD"
[[ "$(git -C "$BASE_BUILD" rev-parse HEAD)" == "$SOURCE_COMMIT" ]] || fail "base checkout is not pinned to $SOURCE_COMMIT"
[[ -z "$(git -C "$BASE_BUILD" status --porcelain)" ]] || fail "base-build is not clean; it was not modified by this script"

available_bytes=$(( $(stat -f --format='%a' "$ARTIFACT_ROOT") * $(stat -f --format='%S' "$ARTIFACT_ROOT") ))
(( available_bytes >= 3 * 1024 * 1024 * 1024 )) || fail "less than 3 GiB available for probe-build/tool outputs"

mkdir -p "$ARTIFACT_ROOT"

if [[ -e "$PROBE_BUILD" ]]; then
    [[ -e "$PROBE_BUILD/.git" ]] || fail "probe-build exists but is not a managed git worktree"
    [[ "$(git -C "$PROBE_BUILD" rev-parse --show-toplevel 2>/dev/null)" == "$PROBE_BUILD" ]] \
        || fail "probe-build is not a standalone managed worktree"
    git -C "$BASE_BUILD" worktree remove --force "$PROBE_BUILD"
fi
git -C "$BASE_BUILD" worktree add --detach "$PROBE_BUILD" "$SOURCE_COMMIT"
[[ "$(git -C "$PROBE_BUILD" rev-parse HEAD)" == "$SOURCE_COMMIT" ]] || fail "probe-build HEAD drifted"

apply_project_patch() {
    local patch_file="$1"
    if git -C "$PROBE_BUILD" apply --recount --reverse --check "$patch_file" >/dev/null 2>&1; then
        return
    fi
    git -C "$PROBE_BUILD" apply --recount --check "$patch_file" || fail "patch does not apply: $patch_file"
    git -C "$PROBE_BUILD" apply --recount "$patch_file"
}

apply_project_patch "$PATCH_DIR/wow-mobile-runtime.patch"
apply_project_patch "$PATCH_DIR/wow-mobile-manifest.patch"
apply_project_patch "$PATCH_DIR/wow-mobile-app-path.patch"
apply_project_patch "$PATCH_DIR/xserver-probe-mode.patch"
apply_project_patch "$PATCH_DIR/process-helper-diagnostics.patch"
apply_project_patch "$PATCH_DIR/box64-repair-diagnostics.patch"
apply_project_patch "$PATCH_DIR/native-package-path.patch"

cp -a "$OVERLAY/." "$PROBE_BUILD/"

BOX64_ASSET="$PROBE_BUILD/app/src/main/assets/box64/box64-0.4.0.tzst"
[[ -f "$BOX64_ASSET" ]] || fail "Box64 0.4.0 asset missing: $BOX64_ASSET"
BOX64_ASSET_REPORT="$ARTIFACT_ROOT/box64-asset-verification.txt"
"$ROOT/scripts/runtime-prototype/verify-box64-asset.sh" "$BOX64_ASSET" | tee "$BOX64_ASSET_REPORT"

command -v x86_64-w64-mingw32-g++ >/dev/null || fail "MinGW-w64 compiler is not installed; no download is attempted"
mkdir -p "$PROBE_SOURCE" "$PROBE_BIN"
cp "$ROOT/scripts/diagnostics/probes/ApiProbe.c" "$PROBE_SOURCE/ApiProbe.c"
cp "$ROOT/scripts/diagnostics/probes/DynCodeProbe.c" "$PROBE_SOURCE/DynCodeProbe.c"
x86_64-w64-mingw32-g++ -O2 -static -static-libgcc -static-libstdc++ -mwindows \
    -o "$PROBE_BIN/ApiProbe.exe" "$PROBE_SOURCE/ApiProbe.c"
x86_64-w64-mingw32-g++ -O2 -static -static-libgcc -static-libstdc++ -mwindows \
    -o "$PROBE_BIN/DynCodeProbe.exe" "$PROBE_SOURCE/DynCodeProbe.c"
mkdir -p "$PROBE_BUILD/app/src/main/assets/probes"
cp "$PROBE_BIN/ApiProbe.exe" "$PROBE_BUILD/app/src/main/assets/probes/ApiProbe.exe"
cp "$PROBE_BIN/DynCodeProbe.exe" "$PROBE_BUILD/app/src/main/assets/probes/DynCodeProbe.exe"

[[ -x "$JDK_HOME/bin/java" ]] || fail "JDK 17 not found: $JDK_HOME"
[[ -d "$SDK_ROOT/platforms/android-34" ]] || fail "SDK platform android-34 missing under $SDK_ROOT"
[[ -d "$SDK_ROOT/ndk/24.0.8215888" ]] || fail "NDK 24.0.8215888 missing under $SDK_ROOT"
[[ -x "$SDK_ROOT/cmake/3.22.1/bin/cmake" ]] || fail "CMake 3.22.1 missing under $SDK_ROOT"

export JAVA_HOME="$JDK_HOME"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export CMAKE_BIN_PATH="$SDK_ROOT/cmake/3.22.1/bin"
"$PROBE_BUILD/gradlew" -p "$PROBE_BUILD" --no-daemon --max-workers=1 --offline assembleDebug

APK="$PROBE_BUILD/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || fail "Gradle reported success without producing $APK"
APK_SIZE_BYTES="$(stat --format='%s' "$APK")"
[[ "$APK_SIZE_BYTES" =~ ^[1-9][0-9]*$ ]] || fail "APK has an invalid size: $APK_SIZE_BYTES"
APK_SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
BUILD_TOOLS=("$SDK_ROOT"/build-tools/*)
APKSIGNER=""
AAPT=""
for tool_dir in "${BUILD_TOOLS[@]}"; do
    [[ -x "$tool_dir/apksigner" ]] && APKSIGNER="$tool_dir/apksigner"
    [[ -x "$tool_dir/aapt" ]] && AAPT="$tool_dir/aapt"
done
[[ -n "$APKSIGNER" && -n "$AAPT" ]] || fail "aapt/apksigner are missing from SDK build-tools"

EXPECTED_PACKAGE="com.wowforever.runtimeprobe"
EXPECTED_VERSION_CODE="4"
EXPECTED_VERSION_NAME="0.4.0-runtime-diagnostics-experimental4"
EXPECTED_ABI="arm64-v8a"
EXPECTED_COMPILE_SDK="34"
EXPECTED_MIN_SDK="26"
EXPECTED_TARGET_SDK="28"
APK_BADGING_OUTPUT="$ARTIFACT_ROOT/apk-badging.txt"
APK_PERMISSIONS_OUTPUT="$ARTIFACT_ROOT/apk-permissions.txt"
APK_ZIP_ENTRIES="$ARTIFACT_ROOT/apk-zip-entries.txt"

"$AAPT" dump badging "$APK" > "$APK_BADGING_OUTPUT"
"$AAPT" dump permissions "$APK" > "$APK_PERMISSIONS_OUTPUT"
unzip -Z1 "$APK" > "$APK_ZIP_ENTRIES"

grep -Fq "package: name='$EXPECTED_PACKAGE'" "$APK_BADGING_OUTPUT" \
    || fail "APK package is not $EXPECTED_PACKAGE"
grep -Fq "versionCode='$EXPECTED_VERSION_CODE' versionName='$EXPECTED_VERSION_NAME'" "$APK_BADGING_OUTPUT" \
    || fail "APK version is not $EXPECTED_VERSION_NAME ($EXPECTED_VERSION_CODE)"
grep -Fq "compileSdkVersion='$EXPECTED_COMPILE_SDK'" "$APK_BADGING_OUTPUT" \
    || fail "APK compile SDK is not $EXPECTED_COMPILE_SDK"
grep -Fq "platformBuildVersionCode='$EXPECTED_COMPILE_SDK'" "$APK_BADGING_OUTPUT" \
    || fail "APK platform build SDK is not $EXPECTED_COMPILE_SDK"
grep -Fxq "sdkVersion:'$EXPECTED_MIN_SDK'" "$APK_BADGING_OUTPUT" \
    || fail "APK min SDK is not $EXPECTED_MIN_SDK"
grep -Fxq "targetSdkVersion:'$EXPECTED_TARGET_SDK'" "$APK_BADGING_OUTPUT" \
    || fail "APK target SDK is not $EXPECTED_TARGET_SDK"
grep -Fxq "native-code: '$EXPECTED_ABI'" "$APK_BADGING_OUTPUT" \
    || fail "aapt did not report native ABI $EXPECTED_ABI"

for permission in \
    android.permission.INTERNET \
    android.permission.ACCESS_NETWORK_STATE \
    android.permission.ACCESS_WIFI_STATE \
    android.permission.MODIFY_AUDIO_SETTINGS \
    android.permission.VIBRATE; do
    grep -Fxq "uses-permission: name='$permission'" "$APK_PERMISSIONS_OUTPUT" \
        || fail "APK is missing permission $permission"
done
for permission in \
    android.permission.READ_EXTERNAL_STORAGE \
    android.permission.WRITE_EXTERNAL_STORAGE; do
    if grep -Fxq "uses-permission: name='$permission'" "$APK_PERMISSIONS_OUTPUT"; then
        fail "APK unexpectedly requests permission $permission"
    fi
done

ARM64_LIB_COUNT="$(grep -Ec '^lib/arm64-v8a/[^/]+\.so$' "$APK_ZIP_ENTRIES" || true)"
(( ARM64_LIB_COUNT > 0 )) || fail "APK has no $EXPECTED_ABI native library entries"

require_zip_asset() {
    local asset="$1"
    local size
    grep -Fxq "$asset" "$APK_ZIP_ENTRIES" || fail "APK is missing asset $asset"
    size="$(unzip -p "$APK" "$asset" | wc -c)"
    [[ "$size" =~ ^[1-9][0-9]*$ ]] || fail "APK asset is empty: $asset"
}

require_zip_asset "assets/rootfs.tzst"
require_zip_asset "assets/rootfs_patches.tzst"
require_zip_asset "assets/probes/ApiProbe.exe"
require_zip_asset "assets/probes/DynCodeProbe.exe"
require_zip_asset "assets/NOTICE"

# Box64 keeps the upstream PT_INTERP as provenance; native app libraries must
# not keep an absolute path into the upstream package sandbox.
for native_entry in $(grep -E '^lib/arm64-v8a/[^/]+\.so$' "$APK_ZIP_ENTRIES"); do
    if unzip -p "$APK" "$native_entry" | LC_ALL=C grep -aFq "/data/data/it.wowmobile"; then
        fail "native library retains upstream package path: $native_entry"
    fi
done

APK_CERT_OUTPUT="$ARTIFACT_ROOT/apk-cert.txt"
"$APKSIGNER" verify --verbose --print-certs "$APK" > "$APK_CERT_OUTPUT"
grep -Fxq "Verifies" "$APK_CERT_OUTPUT" || fail "APK signature verification did not pass"
APK_CERT_SHA256="$(while IFS= read -r line; do
    case "$line" in *"certificate SHA-256 digest:"*) printf '%s\n' "${line##*: }";; esac
done < "$APK_CERT_OUTPUT")"
[[ "$APK_CERT_SHA256" =~ ^[0-9A-Fa-f]{64}$ ]] || fail "APK certificate SHA-256 digest was not reported"
printf 'APK=%s\nSIZE_BYTES=%s\nSHA256=%s\nSOURCE_COMMIT=%s\nBOX64_ASSET_SHA256=%s\n' \
    "$APK" "$APK_SIZE_BYTES" "$APK_SHA256" "$SOURCE_COMMIT" \
    "$(sha256sum "$BOX64_ASSET" | cut -d' ' -f1)" \
    | tee "$ARTIFACT_ROOT/build-result.txt"

{
    printf 'source_commit=%s\n' "$SOURCE_COMMIT"
    printf 'source_tree=%s\n' "$PROBE_BUILD"
    printf 'license=app/LICENSE (LGPL-2.1)\n'
    printf 'upstream=https://github.com/marcocastignoli/wow-mobile\n'
    printf 'apk_size_bytes=%s\n' "$APK_SIZE_BYTES"
    printf 'apk_sha256=%s\n' "$APK_SHA256"
    printf 'apk_certificate_sha256=%s\n' "$APK_CERT_SHA256"
    printf 'box64_asset=%s\n' "$BOX64_ASSET"
    printf 'box64_asset_sha256=%s\n' "$(sha256sum "$BOX64_ASSET" | cut -d' ' -f1)"
    printf 'modified_sources=app/src/main/java/com/wowforever/runtimeprobe app/src/main/java/com/winlator/XServerDisplayActivity.java app/src/main/java/com/winlator/core/AppUtils.java app/src/main/java/com/winlator/core/ProcessHelper.java app/src/main/java/com/winlator/core/RuntimeProcessDiagnostics.java\n'
    printf 'probe_sources=%s\n' "$PROBE_SOURCE"
    printf 'toolchain=JDK17 Gradle7.3.3 SDK34 NDK24.0.8215888 CMake3.22.1\n'
    printf '\npatch_sha256\n'
    for file in "$PATCH_DIR"/*.patch; do sha256sum "$file"; done
    printf '\noverlay_sha256\n'
    while IFS= read -r -d '' file; do
        sha256sum "$ROOT/$file"
    done < <(git -C "$ROOT" ls-files --cached --others --exclude-standard -z -- scripts/runtime-prototype/overlays)
    printf '\nasset_inventory_sha256\n'
    while IFS= read -r -d '' file; do
        sha256sum "$PROBE_BUILD/$file"
    done < <(git -C "$PROBE_BUILD" ls-files -z app/src/main/assets app/src/main/jniLibs)
    sha256sum "$PROBE_BUILD/app/src/main/assets/probes/ApiProbe.exe" "$PROBE_BUILD/app/src/main/assets/probes/DynCodeProbe.exe"
} > "$ARTIFACT_ROOT/provenance-inventory.txt"
