# Metadata-only Windows ARM64 accessibility probe

This is an **MSAA-only feasibility experiment**, not an autologin detector or an
input sender. It contains no client code and does not modify WoW or Wine. It does
not query UI Automation directly: an empty MSAA result cannot rule out a UIA
provider. There has been no runtime test yet.

## Build on the Linux host

Reuse the existing pinned llvm-mingw toolchain described in
[`../arm64-probes/README.md`](../arm64-probes/README.md). No downloads or package
installation are performed. An alternate extracted toolchain can be specified with
`TOOLCHAIN`.

```bash
bash scripts/diagnostics/wine-accessibility/build.sh \
  /home/adrian/wowforever-artifacts/arm64-prototype/controls/accessibility-probe-20261022
```

The output directory must be outside the repository. It receives the executable,
PE-header/import inspection, and a receipt with executable/source/recipe SHA-256.
The recipe verifies PE/ARM64 (`0xaa64`), not x86-64. Windows system/CRT DLLs are still
imports; successful cross-compilation is not proof that the runtime implements
them. No APK or runtime rebuild is necessary.

## Coordinated trial only — not executed automatically

After security review and agreement on an idle test session, use the **Windows
PID** of the owned WoW instance in the same Wine prefix/desktop, not its Android or
Linux PID. Selecting that PID and transporting/running the helper must be
coordinated separately; this directory deliberately contains no ADB launcher or
automatic process discovery.

```text
AccessibilityProbe-ARM64.exe WINDOWS_PID
```

Only a decimal PID is accepted. The helper neither launches WoW nor accesses the
APK's account store. Do not enter credentials during the trial. Disable general
screenshots, memory captures and verbose game/runtime diagnostics; retain only
this helper's bounded metadata stdout and exit code outside the repository. Do
not export shared game logs as part of the trial.

First observe a fresh, empty login form. In separate coordinated samples, manually
change focus between the empty account and password fields and open/close a safe
menu/dialog without logging in. The diagnostic must not itself change focus or
send input. If running the console helper steals foreground focus, record that
limitation: do not treat those results as proof of the in-game focused field.

## Data and scope

- Open the explicit target process with synchronization and limited query access;
  hold its handle to detect exit rather than trusting a reused PID.
- Enumerate top-level windows, retain only those of that live process, and query
  their `OBJID_CLIENT` MSAA trees. Window titles and class strings are not read.
- Before querying each accessible node's metadata, require
  `WindowFromAccessibleObject` to resolve to that same process. Objects without
  this proof are skipped with a numeric error, even if potentially useful.
- Emit only fixed JSONL keys, numeric roles, focus/password/invisible/unavailable
  flags, geometry, HWND/PID and validity/count fields. Never request accessible
  `Name`, `Value`, descriptions, text, clipboard or game arguments. Unexpected
  role/state variants are discarded without logging their contents.
- At most 16 owned top-level windows, 128 nodes, depth 8. A watchdog terminates
  **only the helper itself** after 10 seconds if a provider/COM call hangs.
  No external process is terminated; no process-memory access is requested.
- No input, setters, invocation patterns, hooks, code injection or DLL changes.

`role` is a numeric MSAA role, not a UIA control type. `password` means
`STATE_SYSTEM_PROTECTED`; it is only a provider assertion. Dialog roles and disabled
windows may be clues, **not proof of modality or of absence of other overlays**.
`focus_hwnd` is Windows window focus, while node `focused` comes from MSAA state.

Exit codes: `0` = enumeration returned without reported errors/truncation and the
target remained alive; `2` = invalid arguments; `3` = process/COM/query failure,
truncated enumeration or target exit; `4` = helper watchdog timeout. A missing
`end` event means an incomplete sample regardless of exit code. There is no claim
that the observations form an atomic snapshot; `foreground_unchanged` compares only
the foreground HWND at start and end and cannot detect intervening changes.

Every completed sample explicitly emits `readiness_proven: false`, including exit
`0`. Empty/generic trees, skipped nodes, partial samples and errors are inconclusive,
not proof that WoW lacks accessibility. Never connect credentials based solely on
this helper's exit code. A useful result would need distinct field identities and
correct observed focus transitions, plus a separate reliable modal exclusion gate.

The start/end `foreground_owned` fields identify whether the foreground window
belongs to the selected live process at those instants; false also invalidates any
interpretation as a ready-to-type sample.
