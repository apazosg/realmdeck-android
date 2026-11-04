# wowforever — Agent Guide

## Project

This repository explores running **WoW Classic Beta 1.60.1** on Android ARM64 and
connecting to **Classic Forever**, without Battle.net or a Windows host. It includes
a Linux client-provisioning tool, Android launcher/runtime experiments, and Forever
compatibility components. The project is still experimental: a single-device
ARM64 trial reached the Classic Forever login and in-game entry, but that does not
establish long-session stability, broad device support, or a general release. See
[`docs/launcher-app-status.md`](docs/launcher-app-status.md) for dated evidence and
limitations.

## Start with the specifications

This is a monorepo. Behavioral specifications live in `.agents/specs/`; `SYSTEM.md`
contains cross-system constraints and each feature spec is a self-contained User
Story document. Before changing behavior, locate relevant stories yourself by
searching `.agents/specs/` and their YAML `description` (or use specs surfaced by
your host when available); do not assume host injection. Load relevant stories and
consult `SYSTEM.md` for cross-cutting work. Do not maintain a duplicate story index
here. For SDD details, load the `spec-as-source-management` skill when available.

### Spec-driven development loop

1. Determine the task's spec scope and inspect relevant UC/AC, FR/NFR, tests, and
   applicable system constraints. A spec's `ready` state indicates approval; in a
   `dirty` spec, use only clearly unaffected, previously approved sections and
   treat changed or dependent sections as unresolved. A `draft` `SYSTEM.md` is not
   blanket approval: use established repository safety rules, and do not treat
   unapproved draft choices as binding requirements.
2. An explicit request authorizes the behavior it clearly specifies, including
   coherent mechanical updates to its spec-derived artifacts; do not add approval
   gates just to derive FR/NFR or tests. Preserve proportional traceability and
   update affected artifacts when useful. Ask before proceeding when intent is
   ambiguous, a consequential product/system decision is needed, or significant
   risk, irreversibility, or cross-feature impact is involved. Do not silently
   contradict an unrelated approved requirement.
3. Keep FRs traceable to their source UC/AC and tests to FRs where those artifacts
   are maintained. Mark dependent specs `dirty` when their approved behavior needs
   review. Label code-derived content `[INFERRED]`, unconfirmed premises
   `[ASSUMPTION]`, unsupported coverage `[PENDING]`, and unresolved decisions under
   `Open Questions`.
4. Validate relevant links, traceability, and tests proportionately to the change.
   Keep operational history and volatile test status in `docs/`, not duplicated in
   behavioral requirements.

Do not put feature requirements in this file or in `SYSTEM.md`; the User Stories are
the behavioral source of truth.

## Engineering judgment and testing

- Prefer the simplest solution that correctly satisfies the approved requirements
  and project constraints (KISS). Avoid speculative generalization, unnecessary
  abstractions, dependencies, and configurability; do not simplify by weakening
  required behavior or safety.
- Keep tests proportionate to risk. Cover important observable behavior, realistic
  failure paths, security-sensitive behavior, and meaningful boundaries. Prefer a
  small set of focused, representative tests over redundant permutations. Do not add
  tests for impossible or extremely unlikely scenarios unless they protect against a
  significant impact or a real platform contract; test-count and coverage targets
  are not goals by themselves.

## Version control and repository hygiene

- Inspect the branch and working tree before making changes. Distinguish task-owned
  edits from pre-existing user work; never stage or commit unrelated changes.
- Commit task-owned changes before closing the task. Keep commits atomic: one
  coherent change per commit, with only its relevant files and passing checks.
  Separate unrelated documentation, code, and formatting changes rather than
  bundling them together.
- Review the staged diff and status before committing, and verify the status again
  afterward. If pre-existing edits overlap the task files and cannot be safely
  separated, do not guess or include them; explain the blocker and ask the user.
- Keep the working tree tidy and the local branch based on the intended current
  project state. Do not push, rewrite history, or change branches unless requested.

## Development environment and commands

- Host: Linux x86-64. Python is managed with `uv` (Python >=3.12); do not assume
  `pip3` is installed.
- Android ARM64 functional launcher sources are under
  `scripts/arm64-prototype/app/`. The root `android/` project is a separate
  diagnostic app, not the functional launcher.
- Large external inputs and generated artifacts belong outside the repository.
  Never commit client files, CASC data, APKs, runtime binaries, device captures with
  private data, or downloaded artifacts.

```bash
# Host client provisioning
uv run forever-client --help
uv run forever-client resolve
uv run forever-client install <dir>
uv run forever-client verify <dir>
uv run forever-client repair <dir>

# Repository tests and Forever contract drift check
uv run pytest tests/ -q
scripts/contract-drift-check

# Rust patcher tests; scan reports findings without writing to the target process
~/.cargo/bin/cargo test --manifest-path crates/forever-patcher/Cargo.toml
~/.cargo/bin/cargo run --release --manifest-path crates/forever-patcher/Cargo.toml \
  --bin forever-patch -- scan --pid <PID>

# Android diagnostics are read-only (no installation or UI interaction)
uv run python scripts/diagnostics/android_diagnostics.py \
  --serial <serial> --package com.winlator.llm --output-dir <dir-outside-repo>

# Shared-skill mirror maintenance
.agents/scripts/sync-shared-skills.sh --check
.agents/scripts/sync-shared-skills.sh --write
```

`forever-client verify` checks the loose files listed by the install manifest; it
does **not** verify CASC objects under `Data/`. Do not describe it as a complete
whole-client integrity check.

## Repository-wide safety

- Keep secrets out of source control, logs, screenshots, and generated reports.
- Local-only configuration and internal notes live under the gitignored
  `private/` directory; never copy their values into versioned files or commits.
- The dedicated development device may be used fully over ADB Wi-Fi and scrcpy
  (install, reboot, UI control, capture, transfer). Keep its identifiers in the
  gitignored `private/` directory; see `.agents/references/device-control-scrcpy.md`.
- Do not run the game or Wine as root (`sudo`); this can trigger client assertions.
- Do not archive a live client installation. Use a completed snapshot so the result
  is not an inconsistent view of active writes.
- Treat device evidence as specific to the recorded hardware, client build, runtime,
  and test. A successful login or entry into the game is not evidence of sustained
  gameplay, measured performance, audio, or controller support.
- Follow `.gitignore` and repository artifact-handling rules before adding binaries,
  client data, or device-generated files.

## Reference documents

- `.agents/specs/` — system constraints and behavior specifications.
- `.agents/references/` — operational reference material, loaded as needed.
- `.agents/references/device-control-scrcpy.md` — scrcpy device-control
  procedure.
- `.agents/planning/` — active plans; completed plans are retained in `done/`.
- Plan states are declared, not inferred from age: confirm evidence and ownership
  before reclassifying or moving plans. `SKILL.md` is active; `.orig` files are
  backups without authority and must be preserved until their origin is clarified.
- `docs/architecture.md` — architecture baseline and decisions.
- `docs/launcher-app-status.md` — functional launcher status and evidence limits.
- `docs/launcher-design.md` — launcher product/design decisions.
- `docs/fase-1-instalacion-cliente.md` — host provisioning findings and resumable
  install history.
- `docs/fase-3-patcher.md` — Forever patcher implementation and verification notes.
- `docs/android-runtime-test-results.md` — Android runtime evidence and remaining
  test gaps.
- `docs/device-poco-m7-pro-5g.md` — test-device hardware and Vulkan capabilities.
- `docs/winlator-provisioning.md` — Winlator container provisioning.
- `docs/fase-2-*.md` — historical runtime startup and API-control investigations.
- `docs/android-runtime-candidates.md` — runtime candidates and comparison.
- `docs/fase-5-launcher.md` — launcher and JNI integration history.
- `docs/android-emulator.md` — launcher toolchain emulator; not a substitute for
  physical ARM64/GPU runtime testing.
- `docs/downloader-handoff.md` — historical operational handoff; consult its
  current evidence and safety notes before any explicitly authorized downloader
  operation.
- `forever/contract.json` and `scripts/contract-drift-check` — versioned upstream
  compatibility contract and drift check.
