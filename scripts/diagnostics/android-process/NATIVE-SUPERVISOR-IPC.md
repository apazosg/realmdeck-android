# Native supervisor APK lab — IPC schema1, laboratory v2

Current scope: code57 / 0.2.33-automated, package com.wowforever.arm64probe, API29+ ARM64.
IPC schema1 is unchanged from code56. The new laboratory uses native-supervisor-v2
storage and preserves the original code56 UNKNOWN/store/evidence without recovery.
This is a fixture-only supervisor, not Wine integration. No global app subreaper,
UID/name/group sweeps, arbitrary commands, memory reads, or writable ELF fallback.

## Artifacts and invocation

Build separately from the historical capacity control, using the existing NDK24,
libc-only PIE, linker64 and 16KiB LOAD alignment. Output in the external controls
directory `native-run-supervisor-v2`: `libforever_run_supervisor.so`,
`libforever_run_fixture.so`, and `manifest.json` with bytes/SHA/source/compiler pins.
Package the two executable PIE files in extracted immutable nativeLibraryDir;
never System.loadLibrary them or copy to filesDir for execution.

Java argv (no shell), after canonical path/header/size/SHA validation:

```
/system/bin/linker64 <nativeLibraryDir>/libforever_run_supervisor.so
  --run NS-<32 lowercase hex> --nonce <32 lowercase hex>
  --fixture <same directory>/libforever_run_fixture.so
```

The supervisor validates its own code address against the file-backed RX mapping,
canonical path and device/inode, then the fixed fixture basename/common parent.
It does not assume `/proc/self/exe` identifies the ELF under direct linker invocation.
Android paths are root/system-owned, not group/other writable nor app-writable;
the retained fixture identity is revalidated immediately before fork/exec.
It remains alive, sets/gets PR_SET_CHILD_SUBREAPER and owns fork/exec/reaping.
Fixture internal IPC/arguments are implementation-private and may not expose an
arbitrary exec API. A separate Java-held witness uses the same pinned fixture with
`--witness <runId> <nonce>`; it emits READY before blocking for `Q\n` and exits 0.
Witness process is NOT under the supervisor; no supervisor signal may target it.

## Framing / exact schemas

UTF-8 JSON objects, one LF-terminated line, <=8192 bytes including LF, <=65536 bytes
per stream including stderr. Duplicate keys, unknown keys, non-ASCII identifiers,
wrong types, EOF mid-line, reordered sequence, wrong nonce/RUN/phase fail closed.
Sequence starts at 1 separately in each direction, no reset between phases.
No trusting nonce/name/timing as kernel ownership. No logging foreign proc content.

Commands have exactly these keys:

```
schema:1, runId:string, nonce:string, seq:positive integer,
phase:0..2, command:START|ACK|STOP|CLOSE,
scenario:normal|hold|none, pid:integer, startTicks:integer, eventSeq:integer
```

START: next phase 1 then 2, scenario normal or hold, pid/startTicks/eventSeq=0.
ACK: pending SPAWN/ADOPT event's phase, PID/startTicks and eventSeq; scenario none.
STOP: current phase (or 0 before first START), scenario none, identity fields=0.
CLOSE: phase 0, scenario none, identity fields=0. No START after STOP/CLOSE/EOF.
Cancellation before START sends CLOSE only and must create zero fixture children.
Normal completes phase1, phase2, then CLOSE; hold enables deterministic fork cancel.

Events always have exactly these keys:

```
schema:1, runId:string, nonce:string, seq:positive integer,
phase:0..2, event:READY|SPAWN|ADOPT|EXIT|PHASE_DONE|RUN_DONE|ERROR,
pid:positive integer, startTicks:positive integer, ppid:positive integer,
uid0:integer, uid1:integer, uid2:integer, uid3:integer,
role:supervisor|fixture|intermediate|grandchild|witness,
status:READY|RUNNING|REAPED|PASS|CANCELLED|UNKNOWN|ERROR,
exitCode:integer, childCount:nonnegative integer, echild:boolean,
setResult:integer, getResult:integer, getValue:integer
```

Unused exitCode/prctl fields are -1. READY supervisor phase0 records prctl 0/0/1;
witness READY uses -1/-1/-1 (it is not a subreaper). UID4 must equal the application's
current UID. Supervisor READY must match the Java-held Process PID, /proc identity
and app PPID. Child SPAWN records actual reserved births, including non-direct
children; ADOPT records kernel PPID changed to supervisor with SAME PID/startTicks/
UID4. Native verifies identities; Java independently re-reads own kernel identity
before ACK. Children stay held for these acknowledgments; stale/missing/mismatched
ACK fails closed. Native reports EXIT only on actual waitpid of its child, linked
to a previously registered identity. Reaped processes are never signaled.

The deterministic cancel_fork scenario sends STOP after the grandchild's SPAWN
has been independently kernel-verified, before its ACK releases the intermediate.
Cleanup ADOPT/EXIT may arrive after native waitpid has already reaped the child:
there is then no Java ACK and no invented fresh /proc observation. Those events
must cross-link to the previously kernel-verified birth and native owned-Process
history. They prove native owner adoption/reaping, not a fresh Java observation.
Cancellation before that complete birth record is conservatively UNKNOWN; it
must not be reported as a successful cancellation control by a shortened fixture.

PHASE_DONE has supervisor identity, active phase, resolved childCount=0/echild=true.
RUN_DONE is phase0, requires all registered children resolved and final ECHILD.
PASS/confirmed cleanup additionally requires supervisor waitFor exit0, coherent
full event history, no exceeded deadline or invalid protocol, stable witness and
witness waitFor exit0. Cancelled clean closure is CANCELLED, not normal PASS.
EOF/protocol error may clean up but never yield a successful control. Supervisor
unexpected death is UNKNOWN even if finite fixtures later disappear.

## Safety and deadlines

Signal only verified direct/adopted, unreaped children with freshly checked
PID/startTicks/UID4/PPID; TERM then bounded KILL with revalidation. Non-direct
reservations remain held until adoption; no signaling by naked PID. Unknown
children, identity loss, expired cleanup or unresolved wait => UNKNOWN, not ECHILD
proof by empty samples. Do not copy the capacity-control safety-net orchestrator.

Java durable RUN intent precedes spawn and durable START intent precedes write;
cancel and START authorization share a monitor. Application-scoped controller keeps
Process, readers and wait handle until completion even after Activity destruction.
RUN-specific callbacks cannot mutate a different RUN. No Process.destroy as proof.
15s operation plus 5s cleanup; all native I/O and output deadline-bound, fixture/
witness finite TTL >=30s. Host 60s plus 10s cleanup. Late result never qualifies.

Dedicated DUMP-protected Activity in :run_supervisor process bypasses GameNative
application initialization before Hilt/services. Own UI/consent initiates one run;
allowlisted scenarios: normal, cancel_before_start, cancel_fork, eof,
invalid_protocol, supervisor_failure. Only immutable scenario input, no path/env/
argv extras. Normal scenario is two phases. All fault controls are finite and retain
UNKNOWN/FAIL rather than inventing cleanup. No Wine/XEnvironment/prefix side effect.
