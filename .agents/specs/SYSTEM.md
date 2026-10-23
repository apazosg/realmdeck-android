---
id: SYSTEM
description: "System-wide architecture, technology, security, artifact-handling, and evidence constraints for wowforever."
state: draft
---

# SYSTEM: Global Constraints

## System Overview

wowforever is a Linux-hosted and Android-targeted project for provisioning and
experimentally running WoW Classic Beta 1.60.1 against Classic Forever. Its broad
components are:

| Component | Responsibility |
|---|---|
| Host tooling | Resolve and provision client data, validate repository contracts, and run host-side tests. |
| Android application | Manage the local client and coordinate the Android launch experience. |
| Compatibility/runtime layers | Integrate third-party Windows-application compatibility and graphics components with Android. |
| Forever integration | Apply the project’s server-compatibility behavior through separately specified components. |

Component-specific behavior and acceptance criteria belong in the relevant User
Story, not in this system document. Current implementation and device evidence are
dated in the project status documents; a successful trial on one device does not
imply general compatibility or product readiness.

## Technology and Platform Constraints

- Development and host-side tooling target Linux x86-64.
- Python tooling uses Python 3.12 or later managed by `uv`.
- Android application components target Android ARM64. Compatibility/runtime
  implementations may rely on separately versioned third-party projects and their
  licenses; do not treat an experimental pairing as universal platform support.
- Native components use Rust where present; component-level language and protocol
  details belong in their feature specifications and implementation documentation.
- Host-side verification must remain usable without requiring a connected Android
  device when the tested component is host-only.

## Global Non-Functional Requirements

- **SYS-NFR-001 — Proprietary artifact exclusion:** The project SHALL NOT commit or
  redistribute proprietary client content, CASC data, private runtime payloads, or
  generated APKs. Build inputs and generated artifacts must remain outside version
  control unless their redistribution is explicitly licensed and approved.
- **SYS-NFR-002 — Evidence-scoped claims:** Documentation and test reports SHALL
  distinguish source inspection, host tests, device observations, and sustained
  gameplay evidence. Claims about compatibility must identify the relevant device,
  client build, runtime, and test scope when known, and must not generalize beyond
  the available evidence.
- **SYS-NFR-003 — Secret handling:** Credentials and other secrets SHALL NOT be
  committed or emitted in logs, screenshots, or generated reports.

## Cross-Cutting Constraints

- The game and compatibility processes must not be run as root.
- External dependencies and compatibility layers retain their own licenses and
  provenance; the project must not imply that upstream software or Blizzard content
  is part of, or licensed by, this repository.
- Feature-specific integrity, resume, network, authentication, and process-safety
  behavior is defined by the relevant User Story. Do not infer broader guarantees
  from a host-only verification command or a single successful device trial.

## Assumptions

- None currently recorded at system scope. Feature-level assumptions belong in the
  corresponding User Story.

## Open Questions

- None currently recorded at system scope. Device/runtime uncertainty is tracked in
  the dated Android status and test-result documents rather than as a global
  architectural decision.
