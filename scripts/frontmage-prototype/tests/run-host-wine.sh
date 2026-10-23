#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")/../../.." rev-parse --show-toplevel)"
ARTIFACT_ROOT="${FRONTMAGE_PROTOTYPE_ARTIFACT_ROOT:-/home/adrian/wowforever-artifacts/frontmage-prototype}"
APK="${FRONTMAGE_PROTOTYPE_APK:-}"
PROBE_SOURCE="${FRONTMAGE_PROTOTYPE_PROBES:-$ARTIFACT_ROOT/fex-probes/bin}"
WINE_BIN="${FRONTMAGE_PROTOTYPE_WINE:-$(command -v wine)}"
JDK_HOME="${FRONTMAGE_PROTOTYPE_JAVA_HOME:-$ARTIFACT_ROOT/../integrated-runtime/toolchains/jdk-17.0.20.1+1}"
TIMEOUT_SECONDS="${FRONTMAGE_PROTOTYPE_WINE_TIMEOUT:-60}"

fail() { printf 'HOST-WINE-FAIL: %s\n' "$*" >&2; exit 1; }

[[ -x "$WINE_BIN" ]] || fail "Wine executable missing: $WINE_BIN"
[[ -x "$JDK_HOME/bin/javac" && -x "$JDK_HOME/bin/java" ]] || fail "JDK 17 missing: $JDK_HOME"
if [[ -n "$APK" ]]; then
    [[ -f "$APK" ]] || fail "APK missing; set FRONTMAGE_PROTOTYPE_APK to the current compiled APK: $APK"
fi

RUN_ROOT="$ARTIFACT_ROOT/host-protocol-validation"
mkdir -p "$RUN_ROOT"
RUN_DIR="$(mktemp -d "$RUN_ROOT/run-XXXXXX")"
PROBES="$RUN_DIR/probes"
CLASSES="$RUN_DIR/classes"
mkdir -p "$PROBES" "$CLASSES"

for name in HelloProbe ApiProbe DynCodeProbe; do
    if [[ -n "$APK" ]] && unzip -p "$APK" "assets/probes/$name.exe" > "$PROBES/$name.exe" 2>/dev/null \
            && [[ -s "$PROBES/$name.exe" ]]; then
        :
    else
        cp "$PROBE_SOURCE/$name.exe" "$PROBES/$name.exe" \
            || fail "probe missing from APK and source directory: $name.exe"
    fi
    [[ -s "$PROBES/$name.exe" ]] || fail "empty APK asset: $name.exe"
done

"$JDK_HOME/bin/javac" -encoding UTF-8 -d "$CLASSES" \
    "$ROOT/scripts/frontmage-prototype/overlays/app/src/main/java/com/wowforever/fexprobe/ProbeProtocol.java" \
    "$ROOT/scripts/frontmage-prototype/tests/ProbeProtocolHostTest.java"

exec "$JDK_HOME/bin/java" -cp "$CLASSES" ProbeProtocolHostTest \
    --wine "$WINE_BIN" --probes "$PROBES" --run-dir "$RUN_DIR" \
    --id "$(date -u +%Y%m%d-%H%M%S)-host" --timeout-seconds "$TIMEOUT_SECONDS"
