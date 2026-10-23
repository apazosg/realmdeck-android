# `ForeverPatchBridge`

This is the source/build boundary for the automated ARM64 launcher. Copy
`ForeverPatchBridge.kt` into the app package
`com.wowforever.launcher`; the app loads `libforever_patcher_jni.so` with
`System.loadLibrary("forever_patcher_jni")`.

## Callable interface

```kotlin
object ForeverPatchBridge {
    @JvmStatic external fun findOwnedGameProcesses(executablePath: String): String
    @JvmStatic external fun inspectOwnedGame(
        pid: Int, startTicks: Long, executablePath: String,
        contractPath: String, apply: Boolean,
    ): String
    @JvmStatic external fun armSignalTrace(
        outputPath: String, runId: String, bootId: String,
        expectedPid: Int, expectedStartTicks: Long,
    ): String
    @JvmStatic external fun signalTraceStatus(): String
}
```

The native symbols are therefore:

```text
Java_com_wowforever_launcher_ForeverPatchBridge_findOwnedGameProcesses
Java_com_wowforever_launcher_ForeverPatchBridge_inspectOwnedGame
Java_com_wowforever_launcher_ForeverPatchBridge_armSignalTrace
Java_com_wowforever_launcher_ForeverPatchBridge_signalTraceStatus
```

All responses are JSON schema version `1`. `findOwnedGameProcesses` returns
`status`, `candidates` (each with `pid` and `startTicks`), and bounded discovery
coverage. `inspectOwnedGame` returns `status`, `pid`, `startTicks`, `stores`,
`bytesWritten`, mutation counters (including `mutationUnknown`), and `coverage`
(`readBytes`, `readOps`, completeness, read errors, and the limits). Error
responses add `errorKind` and `error`. Statuses are:

```text
NOT_FOUND VALIDATED PATCHED ALREADY_PATCHED REJECTED ERROR INCOMPLETE
```

`apply = false` is read-only. No response contains memory contents or keys.
The only successful write is the 32-byte group-8 key, after the core has
validated the complete store; the group-1 anchor is never written.

## Identity and safety boundary

The bridge obtains the current app PID/UID itself. It enumerates `/proc`, then
accepts only a same-UID process whose parent chain reaches the current app,
whose `/proc/<pid>/stat` starttime equals the caller's `startTicks`, and whose
`/proc/<pid>/exe` or exact executable file-backed mapping in
`/proc/<pid>/maps` matches `executablePath`. The Wine mapping exception requires
executable permissions, matching inode, and an image offset inside the file;
a read-only data mapping is not sufficient. The executable is independently
checked as PE32+ AA64 before
any patch operation. It rechecks this identity before capture and before every
write. The `/proc/<pid>/mem` fd is opened only after validation and remains
pinned for the complete pass, preventing a PID reuse from redirecting memory
access.

The supplied contract file must byte-match the embedded canonical
`forever/contract.json`, with a 64 KiB input limit. Apply calls are serialized.
Each apply pass first completes a read-only scan; any read error or exhausted
budget prevents approval of writes. Every write revalidates the full block,
writes only the target key, and verifies readback. Partial/unknown mutation is
reported explicitly and must not be treated as a successful patch.

The native operation has a cooperative budget (15 seconds, 128 MiB, 8192 read
operations), including proc metadata, scan, guard, and readback. Underlying
reads are charged once. Timeout, permission errors, or incomplete discovery
are reported as `INCOMPLETE`, not false success. A blocking kernel operation
cannot be preempted by this deadline. Target memory can also change between
validation and write; this is not an atomic transaction with the game.
The game-inspection/patch APIs never send signals or own session cleanup. Real Android/Wine
identity and ARM64 CertStore layout remain unverified.

## SIGINT trace

**Android is deliberately unsupported in code 29.** `armSignalTrace` returns
`UNSUPPORTED/ANDROID_SIGNAL_OWNERSHIP_UNVERIFIED` before the lock, any `sigaction`,
file creation or context publication. `signalTraceStatus` remains `NOT_ARMED`.
No default/ignore/custom Android disposition is changed, and no sender coverage
is inferred from the ABI export or from `ApplicationExitInfo`.

The Linux-only isolated-child test implementation creates `<runId>-sigint.bin`
in an owned test directory, fsyncs the 256-byte header and exercises compatible
`SIG_DFL`; ignore/custom dispositions are rejected unchanged. This does not prove
exclusive signal ownership or Android/libsigchain compatibility. The native
observer must not be enabled there merely because these host tests pass.

The isolated host-test handler writes at most four 64-byte little-endian records using a fixed
stack buffer and async-signal-safe primitives. It restores `SIG_DFL` and uses
`tgkill` on the receiving thread, preserving termination by signal 2. A forked
child does not write a record because the immutable armed PID no longer
matches. The binary is bounded to 512 bytes and the descriptor is `CLOEXEC`
and retained for the process lifetime. Each event uses one write attempt, without
unbounded EINTR retries; truncated/error records are not attributable. The safe
Android deliverable provides PGID/SID metadata and bounded stdout/stderr tails,
not the missing SIGINT sender. See `SIGNAL-TRACE-CONTRACT.md`.

## Host build

From the repository root, with the already-installed Rust target and NDK:

```bash
export CARGO_TARGET_DIR=/home/adrian/wowforever-artifacts/arm64-prototype/native/cargo-target
# The target is expected to be installed already; this recipe downloads nothing.
~/.cargo/bin/cargo build --release \
  --manifest-path crates/forever-patcher-jni/Cargo.toml \
  --target aarch64-linux-android \
  --config 'target.aarch64-linux-android.linker="/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"'

/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
  -o /home/adrian/wowforever-artifacts/arm64-prototype/native/libforever_patcher_jni.so \
  "$CARGO_TARGET_DIR/aarch64-linux-android/release/libforever_patcher_jni.so"
sha256sum /home/adrian/wowforever-artifacts/arm64-prototype/native/libforever_patcher_jni.so
```

The artifact is intentionally outside the repository. `cargo test` remains a
host test and preserves the existing cross-process selftest; no ARM64 runtime
or APK test is implied by this recipe.
