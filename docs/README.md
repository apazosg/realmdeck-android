# Documentation index

This index separates current English product guidance from the dated research
archive. Historical reports retain their original language, evidence, limitations,
and context; links below do not imply that every archived result is current.

## Current product guidance

- [Current status](current-status.md): C142 preview, most recent device evidence,
  and limits on installation, updates, and source completeness.
- [C142 release notes](releases/c142-0.2.118.md): user-facing announcement and
  links to the release assets.
- [Releasing](releasing.md) and the [release-notes template](release-notes-template.md):
  version/tag/asset consistency and publication guidance.
- [Historical launcher status archive](launcher-app-status.md): dated technical
  and device records; older claims are preserved as historical evidence.
- [Project README](../README.md): overview, repository contents, and host setup.
- [Repository privacy](repository-privacy.md): private inputs and safe public-history
  handling; [diagnostics API](diagnostics-api.md): read-only observation contracts.
- [Android emulator](android-emulator.md) and [Winlator provisioning](winlator-provisioning.md):
  operational guides with their historical platform and device limits preserved.

## Architecture and client provisioning

- [Architecture](architecture.md): baseline and design decisions.
- [Phase 1 installation](fase-1-instalacion-cliente.md): TACT/CASC research,
  resumable installation, and known limits.
- [ARM64 client bootstrap](arm64-client-bootstrap.md): host-only catalog, objects,
  and installation limits; not a playable client by itself.

## ARM64 runtime, Wine, and GPU

- [Android runtime test results](android-runtime-test-results.md): candidate
  evidence and Phase 2 gates.
- [Automated ARM64 launcher](automated-arm64-launcher.md): private code33 variant
  and its limits; distinct from the functional Mali proof of concept.
- [xaga USB lab](usb-lab-xaga.md): Wine/Explorer and graphics controls.
- [Mali graphics results](mali-graphics-probe-results.md) and
  [GPU backend findings](gpu-backend-findings.md).
- [Windows ARM64 runtime](windows-arm64-runtime.md) and
  [runtime candidates](android-runtime-candidates.md).
- [Winlator provisioning](winlator-provisioning.md), [Android emulator](android-emulator.md),
  and [launcher design](launcher-design.md).

## Classic Forever compatibility

- [Connection progress log](arm64-forever-connection-progress.md): historical
  group8 identity rejection, validated ARM64 block, saved patch, and C127 login/world
  evidence; D3/D4 are retained as history, not current blockers.

- [WoW socket observation](arm64-wine-tcp-observation.md): D2 attach capability,
  truncated trace, and attribution limits.

- [Direct ARM64 Wine diagnosis](arm64-wine-auth-diagnosis.md): scoped D1, connection
  stages, authorized Login, and first pre-authentication error.

- [TLS and internal trust](arm64-forever-tls-diagnostics.md): strict handshakes
  without credentials, bypass alternatives, and evidence limits.

- [Profiles and functional proof of concept](functional-forever-profiles-results.md):
  visual login, two historical sends, and read-only diagnostics.
- [ARM64 CertStore/patch exploration](arm64-certstore-patch-exploration.md):
  x86_64 contract compared with ARM64 observations.
- [Static PC investigation](arm64-certstore-pc-investigation.md),
  [primitive consumers](arm64-certstore-primitive-consumers.md), and
  [Ghidra results](arm64-certstore-ghidra-results.md).
- [Ed25519 static-slot data flow](ed25519-static-slot-dataflow.md): observed
  fragments, aliases, and analysis limits.
- [`wow-patcher` investigation](wow-patcher-arm64-investigation.md): static dry run,
  not a functional ARM64 test.
- [x64 CertStore initialization](x64-certstore-initialization.md): reference
  suspended by the CASC blocker.
- [Phase 3 patcher](fase-3-patcher.md): historical x86_64 core and later validation
  of the Android applier against real ARM64 WoW.

## Launcher, testing, and references

- [Phase 5 launcher](fase-5-launcher.md): JNI, supervision, and validation without
  root in the app domain.
- [Public ARM64 client references](arm64-private-server-public-references.md) and
  [community findings](community-arm64-findings.md).
- Device, Wine, PanVK, EGL, supervision, and control reports remain in this
  directory. The [central ARM64/Forever status](arm64-forever-status.md) links only
  those that affect current decisions.

## Reading historical reports

Keep **observation**, **interpretation**, and **next action** distinct. The ARM64
proof of concept with visible login, automated code33 variant blocked in Explorer,
and x64 baseline are separate experiments. Authentication, entering the world,
rendering, and applying a patch are independent gates.
