#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")/../.." rev-parse --show-toplevel)"
ARTIFACT_ROOT="${RUNTIME_PROTOTYPE_ARTIFACT_ROOT:-/home/adrian/wowforever-artifacts/integrated-runtime}"
OUT="$ARTIFACT_ROOT/process-diagnostics-check"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/stubs/android/os" "$OUT/stubs/android/system" "$OUT/stubs/androidx/annotation" "$OUT/stubs/com/winlator"

printf '%s\n' 'package android.os; public final class Process { public static void sendSignal(int p, byte s) {} public static void killProcess(int p) {} }' \
    > "$OUT/stubs/android/os/Process.java"
printf '%s\n' 'package android.system; public final class Os { public static int getpid() { return 1; } }' \
    > "$OUT/stubs/android/system/Os.java"
printf '%s\n' 'package androidx.annotation; public @interface NonNull {}' \
    > "$OUT/stubs/androidx/annotation/NonNull.java"
printf '%s\n' 'package com.winlator; public final class MainActivity { public static final boolean DEBUG_MODE = false; }' \
    > "$OUT/stubs/com/winlator/MainActivity.java"

javac -d "$OUT/classes" \
    "$OUT/stubs/android/os/Process.java" "$OUT/stubs/android/system/Os.java" \
    "$OUT/stubs/androidx/annotation/NonNull.java" "$OUT/stubs/com/winlator/MainActivity.java" \
    "$ARTIFACT_ROOT/probe-build/app/src/main/java/com/winlator/core/Callback.java" \
    "$ARTIFACT_ROOT/probe-build/app/src/main/java/com/winlator/core/EnvVars.java" \
    "$ARTIFACT_ROOT/probe-build/app/src/main/java/com/winlator/core/ProcessHelper.java" \
    "$ROOT/scripts/runtime-prototype/overlays/app/src/main/java/com/winlator/core/RuntimeProcessDiagnostics.java" \
    "$ROOT/scripts/runtime-prototype/tests/RuntimeProcessDiagnosticsHarness.java"
java -cp "$OUT/classes" com.winlator.core.RuntimeProcessDiagnosticsHarness
