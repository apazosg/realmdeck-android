---
id: documentation-refresh-2026-10-21
state: done
date: 2026-10-21
---

## Summary

Prepare the repository's primary agent guidance, behavioral specifications, and root README for an English-language spec-driven workflow. The README should read like a complete product overview while accurately distinguishing implemented, physically verified, and still-pending capabilities. Specifications remain the behavioral source of truth and must be maintained alongside development.

The working tree already contains extensive uncommitted implementation and documentation changes, including edits to `README.md`, `US-002.md`, `US-004.md`, and `US-008.md`. Those changes are user work and must be preserved and reconciled rather than overwritten. No product code is in scope.

## Content

### Scope

- `AGENTS.md`: English project overview and operating guidance; explicit spec-first maintenance loop; KISS and risk-proportionate testing; atomic task-owned commits and repository hygiene; accurate paths and task entry points; no duplicated feature requirements or stale project claims.
- `.agents/specs/SYSTEM.md`: English global constraints only; reconcile with current evidence and keep feature-specific behavior and roadmap content in feature specs or existing status documents.
- `.agents/specs/US-001.md` through `US-009.md`: English, traceable, self-contained specifications. Review existing behavior against source, tests, and current evidence; retain valid decisions and user edits; distinguish approved requirements from inferred or pending content.
- `README.md`: English, public-facing, complete product/project overview with clear maturity and support limitations, actual implemented functionality, installation/development guidance supported by the repository, architecture/project links, licensing and non-affiliation notice, and a feature screenshot gallery.
- A small gallery of genuine, privacy-safe screenshots previously captured from the functional launcher on the user-confirmed Wi-Fi test phone. Do not expose credentials or private client data; do not disturb an active game/session or change device/app state.

`docs/README.md` and historical investigation logs are not in scope unless required to repair a link introduced in the root README. No code, release artifacts, or runtime/device state will be changed.

### Findings and constraints

- The repository has `.agents/specs/SYSTEM.md` and nine User Story files. `US-009` is draft; the others currently say ready, so behavioral changes to approved specs need explicit impact handling rather than silent reinterpretation.
- The specs, `AGENTS.md`, and root `README.md` are currently Spanish. The user requested English.
- The root README and several specs have pre-existing uncommitted edits. Preserve their current content and verify any reconciliation against the current working tree.
- No screenshot assets were tracked in the repository. The user confirmed the Wi-Fi-connected xaga test phone (`192.168.1.130:42299`) and that genuine screenshots had already been captured. The actual functional launcher source is under `scripts/arm64-prototype/app/`; `android/` currently contains a diagnostic test app, not that functional launcher. Reuse genuine prior captures from the functional launcher only; never substitute the diagnostic app or present mockups as captures.
- Present product maturity honestly: the latest documented ARM64/Classic Forever success is device-specific and does not establish a public release, broad device support, or long-session gameplay stability.

### Documentation quality gates

- Every spec has valid frontmatter (`id`, agent-discovery `description`, `state`) and unique IDs following the existing `US-NNN` / `FR` / `NFR` / `TC` conventions.
- Preserve traceability from Use Cases and Acceptance Criteria to Functional Requirements and from Functional Requirements to Test Cases. Use `[INFERRED]`, `[ASSUMPTION]`, and `[PENDING]` where evidence or user confirmation is absent; do not turn unknowns into product promises.
- Keep `SYSTEM.md` limited to system-wide constraints. Keep roadmap/status reporting in existing project status documents and link to them instead of duplicating volatile histories.
- The root README's claims, commands, support statements, and screenshot labels must be supported by current source or documented evidence. All local links and screenshot references must resolve.
- Add/update the agent instructions so behavior changes begin with the relevant spec, requirements and test cases are reconciled as implementation changes, unresolved requirement decisions are surfaced instead of silently drifting, engineering favors the simplest correct solution with risk-proportionate tests (KISS), and task-owned changes are committed atomically without absorbing pre-existing work.

## Implementation / Action Items

- [x] 1. Re-read this approved plan and take the current working-tree documentation as the baseline; preserve pre-existing user edits.
- [x] 2. Audit `AGENTS.md`, `SYSTEM.md`, and all nine User Stories against the current application/source, tests, and authoritative status documents; record contradictions and requirement-level open questions before editing.
- [x] 3. Update `AGENTS.md` in English with the spec discovery mechanism, a practical ongoing spec-maintenance workflow, KISS/risk-proportionate testing, and atomic-commit/repository-hygiene guidance, consistent with the repository's structure and loaded spec-as-source guidance.
- [x] 4. Reconcile `SYSTEM.md` as an English global-constraints document; retain genuinely system-wide rules and move or link feature/roadmap detail without changing product decisions.
- [x] 5. Translate and reconcile `US-001.md`–`US-009.md` in English, preserving traceability and valid approvals. Review `US-004.md` last as requested; its keyboard requirements now match the implemented source, with physical-device validation explicitly pending.
- [x] 6. Reuse a small set of genuine, privacy-safe screenshots previously captured from the functional launcher on the user-confirmed Wi-Fi test phone, without changing device/app state; rewrite the root `README.md` in English as a complete, accurate product-facing overview with a feature gallery using those captures.
- [x] 7. Validate frontmatter, IDs/references, local links, claims against current evidence, screenshot presence/privacy, and consistency between README, `AGENTS.md`, `SYSTEM.md`, and User Stories. Summarize any unresolved spec decisions for the user.
- [x] 8. Mark this plan `done` and move it to `.agents/planning/done/` after all approved work and validation are complete.

## Open Issues

- **Status-only reconciliation — resolved:** the old Fase 2/3 narrative in `AGENTS.md`, `SYSTEM.md`, and US-003 predated the documented C127 single-device login/in-game result. Updated only evidence/status wording and retained experimental status and gameplay/device-matrix limitations.
- **Draft system NFR scope — resolved:** `SYSTEM.md` now contains global constraints only and does not overstate `forever-client verify`, which checks loose manifest files but not CASC objects under `Data/`.
- **US-004 keyboard reconciliation — source reconciled; device check pending (2026-10-21):** after the user confirmed implementation, restored AC-08 and aligned FR-008–FR-012 and TC-013–TC-017 with the accessory row, actual labels, key events, modifier cleanup, and hide action. Source and the helper test do not prove physical IME rendering, key delivery to WoW, or hide/focus behavior; these remain `[PENDING]`. Do not disturb the active device/session.
- **US-001 verification conflict — resolved by user (2026-10-21):** user directed that the specification reflect current code. Narrow AC-06/FR-005 and their test cases to loose install-manifest files; explicitly state that `Data/` CASC objects are not verified. Do not change downloader code or imply whole-client integrity.
- **Current-code precedence — user direction (2026-10-21):** when documenting a completed code/spec mismatch, describe current code rather than asserting unsupported behavior. US-004 was held until its keyboard implementation was ready; its source behavior is now reconciled, with physical-device evidence still pending.
- **Requirement conflicts discovered during the full spec/code audit:** if any conflict changes intended behavior or an approved requirement, pause that affected update and present the impact and decision needed; this plan does not pre-approve product behavior changes.
- **Test validation note:** two focused launcher presentation/IME and gamepad checks passed. The full `tests/test_functional_launcher_ux.py` run had 16 passes and one unrelated failure: an existing assertion expects account-settings copy that is absent from the current source. This was outside the documentation task and was not changed. No device test was run.
