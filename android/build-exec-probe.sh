#!/usr/bin/env bash
# Compila `libexec_probe.so` (sonda execve del experimento targetSdk) con el
# NDK y la instala en `android/app/src/main/jniLibs/<abi>/`.
# Sin CMake (no hay cmake en el SDK): se invoca a clang++ directamente.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/.local/opt/android-sdk}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
API="${ANDROID_API:-26}"
TOOLCHAIN="$SDK/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
SRC="$REPO/android/app/src/main/cpp/exec_probe.cpp"

if [ ! -x "$TOOLCHAIN/x86_64-linux-android${API}-clang++" ]; then
  echo "error: no encuentro el NDK en $TOOLCHAIN" >&2
  exit 1
fi
if [ ! -f "$SRC" ]; then
  echo "error: no existe $SRC" >&2
  exit 1
fi

declare -A TARGETS=(
  [x86_64]="x86_64-linux-android${API}"
  [arm64-v8a]="aarch64-linux-android${API}"
)
for ABI in x86_64 arm64-v8a; do
  CXX="$TOOLCHAIN/${TARGETS[$ABI]}-clang++"
  OUT="$REPO/android/app/src/main/jniLibs/$ABI/libexec_probe.so"
  # shellcheck disable=SC2086
  "$CXX" -shared -fPIC -std=c++17 -O2 -Wall -static-libstdc++ \
    -Wl,-z,max-page-size=16384 \
    "$SRC" -o "$OUT" -llog
  echo "OK: $OUT"
done
