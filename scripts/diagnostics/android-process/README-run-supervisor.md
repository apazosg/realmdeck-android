# Run supervisor capacity microtest

`RunSupervisorCapacity` is a standalone, libc-only AArch64 PIE diagnostic.  It
does not start Wine, an APK activity, a renderer, or any process other than its
own orchestrator, one witness, one subreaper supervisor, and the supervisor's
two double-fork test families.

The normal run proves, in order: independent `PR_SET_CHILD_SUBREAPER` set/GET/
value facts for the orchestrator safety-net root and dedicated supervisor;
pre-exit double-fork birth handshakes that reserve PID/start-ticks/UID4
identities; adoption with cross-linked PPIDs; intermediate and grandchild exit
statuses; termination and reaping of one identity-checked retained child;
`waitpid(-1, WNOHANG)` `ECHILD` in both roots; and that the separate
orchestrator-owned witness remains alive with the same identity while supervisor
cleanup runs.  The orchestrator then stops and reaps that witness itself.

The orchestrator is an explicit **microtest-only safety-net root**.  It is not a
global application subreaper and does not sweep UIDs, names, groups, or `/proc`.
If supervisor IPC, timeout, or termination fails, the root only acts on
pre-reserved direct-child identities, verifies PID/start-ticks/UID4/PPID, uses
one bounded TERM→KILL→`waitpid(WNOHANG)` cleanup path, and checks root
`ECHILD` only after the witness and all known families have been handled.  It
reports `UNKNOWN` when that cleanup cannot be confirmed.

All process lifetimes have a finite watchdog that starts before the first root
output; stdout is nonblocking and all pipe/wait operations have an absolute
deadline.  The C `--fault` options are diagnostic-only controls for `prctl`,
`fork`, `ipc`, `reap`, `deadline`, `family/create`, `eventpipe`,
`supervisorfork`, and `witness-ipc`.
They never target a guessed PID, process group, UID, name, or foreign process.
`PASS` requires every fact; explicit `prctl`, fork, IPC, identity, or reap
failures are structured `FAIL`; timeout/kill outcomes are `UNKNOWN`.  Native
stdout is bounded JSONL and the final line is the only qualification result.

## Build contract

Build offline with the pinned local NDK 24 toolchain:

```text
/home/adrian/dev/wowforever/scripts/diagnostics/android-process/build_run_supervisor_capacity.sh
```

The external output is exactly
`/home/adrian/wowforever-artifacts/arm64-prototype/controls/run-supervisor-capacity-v1`.
The generated manifest pins source bytes and hashes, both the 174-byte NDK
launcher and the actual invoked `clang-14` binary, ELF type/machine/interpreter,
libc-only dependency, and 16 KiB LOAD alignment.
The artifact is executed on Android only by the separate runner via
`/system/bin/linker64` under `run-as`; it is never copied to shared
`/data/local/tmp`.
