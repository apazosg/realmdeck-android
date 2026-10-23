#!/usr/bin/env bash
# Compila el cdylib del patcher para Android y actualiza `android/app/src/main/jniLibs/`.
#
# Las `.so` NO se versionan (convención del repo: no commitear binarios). Hay que
# ejecutar esto antes de ensamblar el APK:
#
#     scripts/build-android-native.sh
#     cd android && ./gradlew :app:assembleDebug
#
# Variables: ANDROID_SDK_ROOT, NDK_VERSION, ANDROID_API, CARGO.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/.local/opt/android-sdk}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
API="${ANDROID_API:-26}"
CRATE="${CRATE:-forever-patcher-jni}"
LIB="libforever_patcher_jni.so"
CARGO="${CARGO:-$HOME/.cargo/bin/cargo}"

NDK="$SDK/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$TOOLCHAIN" ]; then
  echo "error: no encuentro el NDK en $TOOLCHAIN" >&2
  echo "       instálalo con: \$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager 'ndk;$NDK_VERSION'" >&2
  exit 1
fi

export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN/x86_64-linux-android${API}-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android${API}-clang"
# Android 15+ exige páginas de 16 KB en las librerías nativas.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

cd "$REPO"
"$CARGO" build --release -p "$CRATE" \
  --target x86_64-linux-android --target aarch64-linux-android

install -D "target/x86_64-linux-android/release/$LIB"  "android/app/src/main/jniLibs/x86_64/$LIB"
install -D "target/aarch64-linux-android/release/$LIB" "android/app/src/main/jniLibs/arm64-v8a/$LIB"

echo "OK: jniLibs actualizados ($LIB: x86_64, arm64-v8a)"
