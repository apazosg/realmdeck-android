#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")/../.." rev-parse --show-toplevel)"
ARTIFACT_ROOT="${RUNTIME_PROTOTYPE_ARTIFACT_ROOT:-/home/adrian/wowforever-artifacts/integrated-runtime}"
ARCHIVE="${1:-$ARTIFACT_ROOT/probe-build/app/src/main/assets/box64/box64-0.4.0.tzst}"
ROOTFS_ARCHIVE="${2:-$ARTIFACT_ROOT/probe-build/app/src/main/assets/rootfs.tzst}"
OUT="$ARTIFACT_ROOT/box64-asset-check"
rm -rf "$OUT"
mkdir -p "$OUT/extracted" "$OUT/rootfs" "$OUT/classes"

"$ROOT/scripts/runtime-prototype/verify-box64-asset.sh" "$ARCHIVE" | tee "$OUT/verification.txt"
tar --zstd -xpf "$ARCHIVE" -C "$OUT/extracted"
tar --zstd -xpf "$ROOTFS_ARCHIVE" -C "$OUT/rootfs"
BOX64="$OUT/extracted/usr/local/bin/box64"
[[ -f "$BOX64" ]] || { printf 'BOX64-ASSET-FAIL: extracted path missing\n' >&2; exit 1; }
[[ "$(stat --format='%a' "$BOX64")" == "644" ]] || { printf 'BOX64-ASSET-FAIL: archive permission key changed\n' >&2; exit 1; }
chmod ugo+x "$BOX64"
[[ -x "$BOX64" ]] || { printf 'BOX64-ASSET-FAIL: executable permission could not be applied\n' >&2; exit 1; }

javac -d "$OUT/classes" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/wowforever/runtimeprobe/Box64InstallPolicy.java" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/wowforever/runtimeprobe/Box64Diagnostics.java" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/wowforever/runtimeprobe/Box64LaunchPlan.java" \
    "$ROOT/scripts/runtime-prototype/tests/Box64InstallHarness.java"
java -cp "$OUT/classes" Box64InstallHarness "$BOX64" "$OUT/rootfs"
