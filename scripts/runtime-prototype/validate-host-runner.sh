#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")/../.." rev-parse --show-toplevel)"
ARTIFACT_ROOT="${RUNTIME_PROTOTYPE_ARTIFACT_ROOT:-/home/adrian/wowforever-artifacts/integrated-runtime}"
RUN_ROOT="$ARTIFACT_ROOT/host-runner"
PREFIX="$RUN_ROOT/wineprefix"
RUN_ID="host-$(date -u +%Y%m%dT%H%M%SZ)-$$"
RUN_DIR="$RUN_ROOT/$RUN_ID"
API="$ARTIFACT_ROOT/probes/ApiProbe.exe"
DYN="$ARTIFACT_ROOT/probes/DynCodeProbe.exe"
TIME_LIMIT="${RUNTIME_PROTOTYPE_WINE_TIMEOUT:-30}"

fail() { printf 'HOST-RUNNER-FAIL: %s\n' "$*" >&2; exit 1; }
command -v wine >/dev/null || fail "wine is not installed"
[[ -f "$API" && -f "$DYN" ]] || fail "build probes first with bootstrap.sh"
mkdir -p "$RUN_DIR" "$PREFIX"

run_one() {
    local name="$1" exe="$2" log="$3"
    local start marker exit_code
    start="$(date +%s)"
    marker="$RUN_DIR/$name.started"
    printf 'START %s %s\n' "$RUN_ID" "$start" > "$marker"
    set +e
    timeout --foreground --signal=TERM "${TIME_LIMIT}s" env WINEPREFIX="$PREFIX" wine "$exe" --log "Z:\\$log" >> "$RUN_DIR/host-runtime.log" 2>&1
    exit_code=$?
    set -e
    if [[ "$exit_code" == 124 ]]; then
        printf 'TIMEOUT name=%s seconds=%s\n' "$name" "$TIME_LIMIT" > "$RUN_DIR/$name.finished"
        fail "$name exceeded ${TIME_LIMIT}s"
    fi
    printf '%s\n' "$exit_code" > "$RUN_DIR/$name.exitcode"
    printf 'END name=%s exit=%s\n' "$name" "$exit_code" > "$RUN_DIR/$name.finished"
    printf '%s\n' "$exit_code"
}

printf 'HOST-RUNNER-BEGIN runId=%s prefix=%s\n' "$RUN_ID" "$PREFIX"
api_code="$(run_one api "$API" "$RUN_DIR/api-probe.txt")"
[[ "$api_code" == 0 ]] || fail "ApiProbe returned $api_code"
dyn_code="$(run_one dyn "$DYN" "$RUN_DIR/dyncode-probe.txt")"
[[ "$dyn_code" == 0 ]] || fail "DynCodeProbe returned $dyn_code"

# A stale marker must never turn a failed process into PASS. The missing binary
# is intentional and is run in the same isolated prefix with the same timeout.
printf 'stale\n' > "$RUN_DIR/nonzero.finished"
set +e
timeout --foreground --signal=TERM "${TIME_LIMIT}s" env WINEPREFIX="$PREFIX" wine "$RUN_DIR/does-not-exist.exe" --log "Z:\\$RUN_DIR/nonzero.txt" >> "$RUN_DIR/host-runtime.log" 2>&1
missing_code=$?
set -e
[[ "$missing_code" != 0 && "$missing_code" != 124 ]] || fail "negative case unexpectedly returned $missing_code"
stale_marker="$(<"$RUN_DIR/nonzero.finished")"
if [[ "$stale_marker" == "PASS" ]]; then
    fail "stale marker was accepted"
fi
printf '%s\n' "$missing_code" > "$RUN_DIR/nonzero.exitcode"
printf 'HOST-RUNNER-PASS runId=%s api=%s dyn=%s negative=%s\n' "$RUN_ID" "$api_code" "$dyn_code" "$missing_code"
