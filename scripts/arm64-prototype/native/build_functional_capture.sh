#!/usr/bin/env bash
set -euo pipefail

# Host-only cross-build recipe for the functional-session JNI (read-only scan
# and explicit guarded key application). It never provisions an APK, starts a
# runtime, or touches the client.
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
CARGO=$(/home/adrian/.cargo/bin/rustup which cargo)
RUSTC=$(/home/adrian/.cargo/bin/rustup which rustc)
export RUSTC
ARTIFACT_PARENT=/home/adrian/wowforever-artifacts/arm64-prototype
NDK=/home/adrian/android-sdk/ndk/24.0.8215888
LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

[[ -d "$ARTIFACT_PARENT" && ! -L "$ARTIFACT_PARENT" ]] || { printf '%s\n' "artifact parent is missing or a symlink" >&2; exit 2; }
[[ -x "$RUSTC" && ! -L "$RUSTC" && -x "$CARGO" && ! -L "$CARGO" && -x "$LINKER" && -x "$READELF" && -x "$STRIP" ]] || { printf '%s\n' "NDK24/API29 toolchain is unavailable" >&2; exit 2; }
[[ -f "$ROOT/Cargo.lock" && ! -L "$ROOT/Cargo.lock" && -d "$ROOT/crates/forever-patcher-jni/src" && ! -L "$ROOT/crates/forever-patcher-jni/src" ]] || { printf '%s\n' "JNI source or Cargo.lock is missing" >&2; exit 2; }

PROVENANCE_TOOL="$ROOT/scripts/arm64-prototype/native/capture_provenance.py"
ELF_AUDIT_TOOL="$ROOT/scripts/arm64-prototype/native/elf16k_audit.py"
[[ -f "$PROVENANCE_TOOL" && ! -L "$PROVENANCE_TOOL" && -f "$ELF_AUDIT_TOOL" && ! -L "$ELF_AUDIT_TOOL" ]] || { printf '%s\n' "provenance or ELF audit helper is missing" >&2; exit 2; }

umask 077
WORK=$(mktemp -d "$ARTIFACT_PARENT/functional-capture-build.XXXXXX")
chmod 700 "$WORK"
export CARGO_NET_OFFLINE=true
export CARGO_TARGET_DIR="$WORK/cargo-target"

/home/adrian/.local/bin/uv run python "$PROVENANCE_TOOL" \
  --root "$ROOT" --ndk "$NDK" --rustc "$RUSTC" --cargo "$CARGO" --strip "$STRIP" \
  --linker "$LINKER" --readelf "$READELF" \
  --phase before --output "$WORK/provenance-before.json"

"$CARGO" build --release \
  --manifest-path "$ROOT/crates/forever-patcher-jni/Cargo.toml" \
  --target aarch64-linux-android \
  --config "target.aarch64-linux-android.linker=\"$LINKER\"" \
  --config 'target.aarch64-linux-android.rustflags=["-C","link-arg=-Wl,-z,max-page-size=16384"]'

SO="$WORK/libforever_patcher_jni.so"
"$STRIP" -o "$SO" "$CARGO_TARGET_DIR/aarch64-linux-android/release/libforever_patcher_jni.so"
chmod 600 "$SO"

/home/adrian/.local/bin/uv run python "$ROOT/scripts/arm64-prototype/native/elf16k_audit.py" "$SO"

SO_SHA=$(sha256sum "$SO" | cut -d' ' -f1)
SO_SIZE=$(stat -c '%s' "$SO")
/home/adrian/.local/bin/uv run python "$PROVENANCE_TOOL" \
  --root "$ROOT" --ndk "$NDK" --rustc "$RUSTC" --cargo "$CARGO" --strip "$STRIP" \
  --linker "$LINKER" --readelf "$READELF" \
  --so "$SO" --phase after --output "$WORK/provenance-after.json"
/home/adrian/.local/bin/uv run python - "$WORK/provenance-before.json" "$WORK/provenance-after.json" "$WORK/provenance.json" <<'PY'
import json
import sys
from pathlib import Path

before = json.loads(Path(sys.argv[1]).read_text())
after = json.loads(Path(sys.argv[2]).read_text())
if before.get("schema") != 1 or before.get("phase") != "before" or after.get("schema") != 1 or after.get("phase") != "after":
    raise SystemExit("native provenance phases are invalid")
if "nativeElf" in before or "nativeElf" not in after:
    raise SystemExit("native ELF must be present only in after provenance")
before_core = {key: value for key, value in before.items() if key not in {"phase", "nativeElf"}}
after_core = {key: value for key, value in after.items() if key not in {"phase", "nativeElf"}}
if before_core != after_core:
    raise SystemExit("source/toolchain provenance changed during native build")
Path(sys.argv[3]).write_text(json.dumps({"schema": 1, "before": before, "after": after}, sort_keys=True, indent=2) + "\n")
PY
chmod 600 "$WORK/provenance.json"
printf 'FUNCTIONAL_CAPTURE_NATIVE_BUILD PASS\nSO=%s\nPROVENANCE=%s\nSHA256=%s\nBYTES=%s\n' "$SO" "$WORK/provenance.json" "$SO_SHA" "$SO_SIZE"
