#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")/../.." rev-parse --show-toplevel)"
ARTIFACT_ROOT="${RUNTIME_PROTOTYPE_ARTIFACT_ROOT:-/home/adrian/wowforever-artifacts/integrated-runtime}"
OUT="$ARTIFACT_ROOT/invariant-check"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

javac -d "$OUT/classes" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/wowforever/runtimeprobe/ProbePrefix.java" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/wowforever/runtimeprobe/ProbeTerminalState.java" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/wowforever/runtimeprobe/Box64InstallPolicy.java" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/wowforever/runtimeprobe/Box64Diagnostics.java" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/wowforever/runtimeprobe/Box64LaunchPlan.java" \
    "$ROOT/scripts/runtime-prototype/tests/ProbeRuntimeInvariants.java"
java -cp "$OUT/classes" ProbeRuntimeInvariants
