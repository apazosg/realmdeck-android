#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")/../.." rev-parse --show-toplevel)"
ARTIFACT_ROOT="${FRONTMAGE_PROTOTYPE_ARTIFACT_ROOT:-/home/adrian/wowforever-artifacts/frontmage-prototype}"
SOURCE_DIR="$ARTIFACT_ROOT/source"
BUILD_DIR="$ARTIFACT_ROOT/build"
GENERATED_ASSETS="$ARTIFACT_ROOT/generated-assets/app/src/main/assets"
SOURCE_URL="https://github.com/FrontMage/winlator-llm.git"
SOURCE_COMMIT="4293b75b449d11fdf66da567791c9165fa6df6a0"
JDK_HOME="${FRONTMAGE_PROTOTYPE_JAVA_HOME:-$ARTIFACT_ROOT/../integrated-runtime/toolchains/jdk-17.0.20.1+1}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/adrian/android-sdk}}"
GRADLE_EXPECTED="7.5.1"
AGP_EXPECTED="7.4.2"
NDK_EXPECTED="22.1.7171670"
CMAKE_EXPECTED="3.22.1"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
note() { printf '%s\n' "$*"; }

USE_RECOVERED_ASSETS=0
USE_FEX_PROBE=0
for arg in "$@"; do
    case "$arg" in
        --with-recovered-assets) USE_RECOVERED_ASSETS=1 ;;
        --fex-probe) USE_FEX_PROBE=1 ;;
        *) fail "usage: $0 [--fex-probe] [--with-recovered-assets]" ;;
    esac
done

[[ -d "$ARTIFACT_ROOT" ]] || fail "artifact root does not exist: $ARTIFACT_ROOT"
mkdir -p "$ARTIFACT_ROOT"

if [[ ! -d "$SOURCE_DIR/.git" ]]; then
    note "SOURCE: cloning $SOURCE_COMMIT from $SOURCE_URL"
    git clone --filter=blob:none "$SOURCE_URL" "$SOURCE_DIR"
fi

[[ "$(git -C "$SOURCE_DIR" rev-parse --is-inside-work-tree 2>/dev/null)" == true ]] \
    || fail "source checkout is not a git worktree: $SOURCE_DIR"
git -C "$SOURCE_DIR" fetch --filter=blob:none origin "$SOURCE_COMMIT"
git -C "$SOURCE_DIR" checkout --detach "$SOURCE_COMMIT"
[[ "$(git -C "$SOURCE_DIR" rev-parse HEAD)" == "$SOURCE_COMMIT" ]] || fail "source commit drifted"
[[ -z "$(git -C "$SOURCE_DIR" status --porcelain)" ]] || fail "source checkout is dirty"
[[ -f "$SOURCE_DIR/app/.gitmodules" ]] || fail "upstream app submodule manifest missing"
hydrate_openxr() {
    local tree="$1"
    local module="$tree/app/src/main/cpp/OpenXR-SDK"
    local expected
    expected="$(git -C "$tree" ls-tree "$SOURCE_COMMIT" app/src/main/cpp/OpenXR-SDK | cut -f1 | cut -d' ' -f3)"
    [[ "$expected" =~ ^[0-9a-f]{40}$ ]] || fail "OpenXR gitlink is missing from pinned source"
    if [[ ! -d "$module/.git" ]]; then
        if [[ -d "$module" ]]; then
            rmdir "$module" 2>/dev/null || fail "OpenXR submodule path is not empty: $module"
        else
            [[ ! -e "$module" ]] || fail "OpenXR submodule path is not empty: $module"
        fi
        git clone "https://github.com/KhronosGroup/OpenXR-SDK.git" "$module"
    fi
    git -C "$module" fetch --filter=blob:none origin "$expected"
    git -C "$module" checkout --detach "$expected"
    [[ "$(git -C "$module" rev-parse HEAD)" == "$expected" ]] || fail "OpenXR submodule drifted"
}
hydrate_openxr "$SOURCE_DIR"

SOURCE_LICENSE="$SOURCE_DIR/LICENSE"
[[ -f "$SOURCE_LICENSE" ]] || fail "upstream source license missing"
printf 'source_commit=%s\nsource_url=%s\nsource_license_sha256=%s\n' \
    "$SOURCE_COMMIT" "$SOURCE_URL" "$(sha256sum "$SOURCE_LICENSE" | cut -d' ' -f1)" \
    > "$ARTIFACT_ROOT/source-provenance.txt"
printf 'openxr_commit=%s\n' "$(git -C "$SOURCE_DIR/app/src/main/cpp/OpenXR-SDK" rev-parse HEAD)" >> "$ARTIFACT_ROOT/source-provenance.txt"

[[ -x "$JDK_HOME/bin/java" ]] || fail "JDK 17 missing: $JDK_HOME"
[[ -d "$SDK_ROOT/platforms/android-30" ]] || fail "required SDK platform missing: $SDK_ROOT/platforms/android-30"
[[ -d "$SDK_ROOT/ndk/$NDK_EXPECTED" ]] || fail "required NDK missing: $SDK_ROOT/ndk/$NDK_EXPECTED"
[[ -x "$SDK_ROOT/cmake/$CMAKE_EXPECTED/bin/cmake" ]] || fail "required CMake missing: $SDK_ROOT/cmake/$CMAKE_EXPECTED"

grep -Fq "com.android.tools.build:gradle:$AGP_EXPECTED" "$SOURCE_DIR/build.gradle" \
    || fail "source AGP is not the pinned expected version $AGP_EXPECTED"
grep -Fq "gradle-$GRADLE_EXPECTED" "$SOURCE_DIR/gradle/wrapper/gradle-wrapper.properties" \
    || fail "source Gradle wrapper is not $GRADLE_EXPECTED"
grep -Fq "ndkVersion '$NDK_EXPECTED'" "$SOURCE_DIR/app/build.gradle" \
    || fail "source NDK declaration is not $NDK_EXPECTED"
grep -Fq "compileSdk 30" "$SOURCE_DIR/app/build.gradle" \
    || fail "source compile SDK is not 30"

if [[ -e "$BUILD_DIR" ]]; then
    [[ -e "$BUILD_DIR/.git" ]] || fail "build directory exists and is not a git worktree: $BUILD_DIR"
    git -C "$SOURCE_DIR" worktree remove --force "$BUILD_DIR"
fi
git -C "$SOURCE_DIR" worktree add --detach "$BUILD_DIR" "$SOURCE_COMMIT"
hydrate_openxr "$BUILD_DIR"
[[ -z "$(git -C "$BUILD_DIR" status --porcelain)" ]] || fail "build checkout is dirty before overlays"

apply_patch_checked() {
    local patch="$1"
    git -C "$BUILD_DIR" apply --recount --unidiff-zero --reverse --check "$patch" >/dev/null 2>&1 && return 0
    git -C "$BUILD_DIR" apply --recount --unidiff-zero --check "$patch" || fail "patch does not apply: $patch"
    git -C "$BUILD_DIR" apply --recount --unidiff-zero "$patch"
}

if [[ "$USE_FEX_PROBE" == 1 ]]; then
    [[ "$USE_RECOVERED_ASSETS" == 1 ]] || fail "--fex-probe requires --with-recovered-assets"
    PATCH_DIR="$ROOT/scripts/frontmage-prototype/overlays/patches"
    for patch in application-identity.patch manifest-fexprobe.patch imagefs-callback.patch process-capture.patch guest-runtime-path.patch context-storage-path.patch runtime-package-paths.patch xserver-fex-probe.patch xserver-safety.patch; do
        apply_patch_checked "$PATCH_DIR/$patch"
    done
    cp -a "$ROOT/scripts/frontmage-prototype/overlays/." "$BUILD_DIR/"
    [[ -f "$BUILD_DIR/app/src/main/java/com/wowforever/fexprobe/ProbeProtocol.java" ]] \
        || fail "FEX probe protocol source was not copied into generated build"
    grep -Fq 'public final class ProbeProtocol' "$BUILD_DIR/app/src/main/java/com/wowforever/fexprobe/ProbeProtocol.java" \
        || fail "generated ProbeProtocol source is not the current protocol"
    grep -Fq 'PROCESS-ENV HODLL=' "$BUILD_DIR/app/src/main/java/com/wowforever/fexprobe/FexProcessDiagnostics.java" \
        || fail "generated process diagnostics lacks the final environment audit"
    grep -Fq 'direct Proton10 ARM64EC wine' "$BUILD_DIR/app/src/main/java/com/wowforever/fexprobe/ProbeRunStore.java" \
        || fail "generated runtime metadata still claims a generic PRoot launch"
    command -v x86_64-w64-mingw32-gcc >/dev/null || fail "MinGW-w64 compiler is required"
    PROBE_ROOT="$ARTIFACT_ROOT/fex-probes"
    mkdir -p "$PROBE_ROOT/source" "$PROBE_ROOT/bin" "$BUILD_DIR/app/src/main/assets/probes"
    cp "$ROOT/scripts/frontmage-prototype/probes/HelloProbe.c" "$PROBE_ROOT/source/HelloProbe.c"
    cp "$ROOT/scripts/diagnostics/probes/ApiProbe.c" "$PROBE_ROOT/source/ApiProbe.c"
    cp "$ROOT/scripts/diagnostics/probes/DynCodeProbe.c" "$PROBE_ROOT/source/DynCodeProbe.c"
    x86_64-w64-mingw32-gcc -O2 -static -static-libgcc -static-libstdc++ -mwindows -o "$PROBE_ROOT/bin/HelloProbe.exe" "$PROBE_ROOT/source/HelloProbe.c"
    x86_64-w64-mingw32-gcc -O2 -static -static-libgcc -static-libstdc++ -mwindows -o "$PROBE_ROOT/bin/ApiProbe.exe" "$PROBE_ROOT/source/ApiProbe.c"
    x86_64-w64-mingw32-gcc -O2 -static -static-libgcc -static-libstdc++ -mwindows -o "$PROBE_ROOT/bin/DynCodeProbe.exe" "$PROBE_ROOT/source/DynCodeProbe.c"
    cp "$PROBE_ROOT/bin/"*.exe "$BUILD_DIR/app/src/main/assets/probes/"
fi

if [[ "$USE_RECOVERED_ASSETS" == 1 ]]; then
    for asset in imagefs.txz imagefs_patches.tzst preinstall/proton-10-arm64ec.wcp.xz; do
        [[ -f "$GENERATED_ASSETS/$asset" ]] || fail "recovered asset missing; run recover-release-assets.sh: $GENERATED_ASSETS/$asset"
    done
    note "ASSETS: copying exact release entries from $GENERATED_ASSETS"
    cp -f "$GENERATED_ASSETS/imagefs.txz" "$BUILD_DIR/app/src/main/assets/imagefs.txz"
    cp -f "$GENERATED_ASSETS/imagefs_patches.tzst" "$BUILD_DIR/app/src/main/assets/imagefs_patches.tzst"
    mkdir -p "$BUILD_DIR/app/src/main/assets/preinstall"
    cp -f "$GENERATED_ASSETS/preinstall/proton-10-arm64ec.wcp.xz" "$BUILD_DIR/app/src/main/assets/preinstall/proton-10-arm64ec.wcp.xz"
fi

export JAVA_HOME="$JDK_HOME"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
note "BUILD: JAVA_HOME=$JAVA_HOME ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
    "$BUILD_DIR/gradlew" -p "$BUILD_DIR" --offline --no-daemon --max-workers=1 assembleDebug

APK="$BUILD_DIR/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || fail "Gradle succeeded without producing $APK"

BUILD_TOOLS=("$SDK_ROOT"/build-tools/*)
AAPT=""
APKSIGNER=""
for tool_dir in "${BUILD_TOOLS[@]}"; do
    [[ -x "$tool_dir/aapt" ]] && AAPT="$tool_dir/aapt"
    [[ -x "$tool_dir/apksigner" ]] && APKSIGNER="$tool_dir/apksigner"
done
[[ -n "$AAPT" ]] || fail "aapt missing from SDK build-tools"
[[ -n "$APKSIGNER" ]] || fail "apksigner missing from SDK build-tools"

APK_SIZE="$(stat --format='%s' "$APK")"
APK_SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
"$AAPT" dump badging "$APK" > "$ARTIFACT_ROOT/apk-badging.txt"
"$AAPT" dump permissions "$APK" > "$ARTIFACT_ROOT/apk-permissions.txt"
unzip -Z1 "$APK" > "$ARTIFACT_ROOT/apk-zip-entries.txt"
"$APKSIGNER" verify --verbose --print-certs "$APK" > "$ARTIFACT_ROOT/apk-cert.txt"

EXPECTED_PACKAGE=com.winlator.llm
if [[ "$USE_FEX_PROBE" == 1 ]]; then EXPECTED_PACKAGE=com.wowforever.fexprobe; fi
grep -Fq "package: name='$EXPECTED_PACKAGE'" "$ARTIFACT_ROOT/apk-badging.txt" \
    || fail "APK package identity differs from $EXPECTED_PACKAGE"
grep -Fxq "native-code: 'arm64-v8a'" "$ARTIFACT_ROOT/apk-badging.txt" \
    || fail "baseline is not arm64-v8a"

if [[ "$USE_RECOVERED_ASSETS" == 1 ]]; then
    while IFS=$'\t' read -r entry expected; do
        [[ -n "$entry" ]] || continue
        tmp="$ARTIFACT_ROOT/.asset-verify"
        unzip -p "$APK" "assets/$entry" > "$tmp" || fail "built APK missing recovered asset: $entry"
        actual="$(sha256sum "$tmp" | cut -d' ' -f1)"
        rm -f "$tmp"
        [[ "$actual" == "$expected" ]] || fail "built APK asset hash mismatch: $entry ($actual)"
    done <<'ASSET_MANIFEST'
imagefs.txz	f96d362b7e148e86ab0d2c290978bf39b38e5c7ffc8ae4adf1d2a65c62bbb780
imagefs_patches.tzst	398f863a69957da8be6ee9b73134cecead64b833f82afcb84b60d1a6725eef5a
preinstall/proton-10-arm64ec.wcp.xz	2501af75ffe4cffad0671e4e6613f0c8e6113fcf16f5a474a4dd3569135010d5
fexcore/fexcore-2508.tzst	0afc4ded5830c2038237498ffe34c830533c6152ca7197349c06de4f44aee4a3
ASSET_MANIFEST
    printf 'asset_manifest=complete\nasset_provenance=%s\n' "$ARTIFACT_ROOT/generated-assets/asset-provenance.txt" \
        > "$ARTIFACT_ROOT/recovered-asset-build.txt"
fi

{
    printf 'apk=%s\nsize_bytes=%s\nsha256=%s\nsource_commit=%s\nasset_mode=%s\n' \
        "$APK" "$APK_SIZE" "$APK_SHA256" "$SOURCE_COMMIT" \
        "$([[ "$USE_RECOVERED_ASSETS" == 1 ]] && printf exact-release-recovered || printf source-only)"
    printf 'gradle=%s\nagp=%s\njdk=17\ncompile_sdk=30\nndk=%s\ncmake=%s\n' \
        "$GRADLE_EXPECTED" "$AGP_EXPECTED" "$NDK_EXPECTED" "$CMAKE_EXPECTED"
    printf '\nentrypoint_audit\n'
    while IFS= read -r entry; do
        case "$entry" in
            lib/arm64-v8a/*.so)
                tmp="$ARTIFACT_ROOT/.native-audit"
                unzip -p "$APK" "$entry" > "$tmp"
                interp="$(readelf -l "$tmp" 2>/dev/null | python3 -c 'import sys; print(next((x.strip() for x in sys.stdin if "Requesting program interpreter" in x), "NONE"))')"
                printf '%s\t%s\t%s\n' "$entry" "$(stat --format='%s' "$tmp")" "$interp"
                printf '  sha256=%s\n' "$(sha256sum "$tmp" | cut -d' ' -f1)"
                if strings "$tmp" | grep -m 20 -F -e 'it.wowmobile' -e '/data/data/com.winlator' -e 'com.winlator'; then
                    printf '  package_path_match=YES\n'
                else
                    printf '  package_path_match=NO\n'
                fi
                rm -f "$tmp"
                ;;
        esac
    done < "$ARTIFACT_ROOT/apk-zip-entries.txt"
    printf '\npackage_identity_and_compile_args\n'
    git -C "$BUILD_DIR" grep -n -I -E 'applicationId|PACKAGE_NAME|CMAKE_(C|CXX)_FLAGS|add_compile_definitions|target_compile_definitions' -- \
        app/build.gradle app/src/main/cpp app/src/main/AndroidManifest.xml \
        || printf 'none\n'
    printf '\ncustom_absolute_package_paths\n'
    git -C "$BUILD_DIR" grep -n -I -E 'it\.wowmobile|/data/data/com\.winlator(\.cmod|\.llm)?' -- \
        app/src/main/java app/src/main/cpp app/src/main/AndroidManifest.xml \
        || printf 'none\n'
} > "$ARTIFACT_ROOT/native-entrypoint-audit.txt"

printf 'APK=%s\nSIZE_BYTES=%s\nSHA256=%s\nSOURCE_COMMIT=%s\nASSET_MODE=%s\n' \
    "$APK" "$APK_SIZE" "$APK_SHA256" "$SOURCE_COMMIT" \
    "$([[ "$USE_RECOVERED_ASSETS" == 1 ]] && printf exact-release-recovered || printf source-only)" \
    | tee "$ARTIFACT_ROOT/build-result.txt"
