# RealmDeck Android

An experimental project to run **WoW Classic Beta 1.60.1** on Android ARM64 and
connect to **Classic Forever**—without Battle.net or a Windows host. The project
combines a Linux client-provisioning tool, an Android launcher/runtime prototype,
and native Forever-compatibility components. Project site: [realmdeck.fyi](https://realmdeck.fyi).

RealmDeck Android is the project and repository brand; existing in-app names and
the Android package ID are unchanged.

> **Experimental preview, not a general release.** A user reported a successful
> first login on C141 on one device. C142 has not been installed or visually tested
> on a device. There is no evidence of long-session stability, measured performance,
> broad device support, or a one-click install. The proprietary game client is not
> included or redistributed here.

## Current status

Evidence snapshot: **2026-10-23**. The latest published launcher is C142,
version `0.2.118-apk-update-notice` (version code 142). The latest positive
device report is for C141: the user reported a successful first login. That result
does not validate C142.

The evidence remains deliberately narrow:

- C142 has not been installed or visually tested on a physical device. No measured
  performance or long-session result is reported.
- No multi-device compatibility matrix or general product release has been
  validated. The ARM64 launcher remains experimental.
- A successful first login is not evidence of sustained gameplay, performance,
  audio, or controller support.

See [current status](docs/current-status.md) for release and source limitations,
and the [historical evidence archive](docs/launcher-app-status.md) for dated
implementation and device records. The host provisioning tool and Android ARM64
client pipeline are separate: the host tool's current contract targets Windows
`x86_64`/`enUS` builds.

## Android preview release

[RealmDeck Android 0.2.118 · Preview (C142)](https://github.com/apazosg/realmdeck-android/releases/tag/c142-0.2.118-test)
is available as a manual APK download. It is an experimental, debug-signed ARM64
build; review its [release notes](docs/releases/c142-0.2.118.md) and installation
limitations before trying it. The app does not provide a reliable automatic update
path after the repository rename.

## Launcher preview

These are genuine captures from the functional launcher prototype, not mockups.
They show older prototype revisions (C116 and code79), with Spanish UI; they are
included to illustrate the interface, **not** to represent the current build,
current client state, or a release-ready app.

| Launcher home | Display resolution settings |
|---|---|
| ![Older C116 launcher home prototype. Spanish UI; client not installed in this capture.](docs/images/launcher-home-prototype-c116.png) | ![Older code79 resolution selector prototype. Spanish UI.](docs/images/resolution-selector-prototype-code79.png) |

## What is in this repository

- **Host client provisioning:** `forever-client` resolves a CDN build and installs,
  verifies, or repairs a client using the NGDP/TACT implementation. The host
  contract currently records the Windows `x86_64`/`enUS` builds
  `1.60.1.69913`, `1.60.1.69977`, and `1.60.1.70009`.
- **Android launcher prototype:** the functional ARM64 launcher sources are under
  [`scripts/arm64-prototype/app/`](scripts/arm64-prototype/app/). It includes
  client preparation and local reuse flows, storage and display settings, and
  explicit update controls. The separate root [`android/`](android/) project is a
  diagnostic app, **not** the functional launcher.
- **Forever compatibility:** the Android path uses a native bridge and a guarded
  in-memory compatibility patch; the host Rust patcher and versioned contract
  provide related tooling and checks. The patch does not modify the installed
  executable.
- **Behavior specifications:** [`.agents/specs/`](.agents/specs/) is the source
  of truth for expected behavior. See [`AGENTS.md`](AGENTS.md) for the spec-first
  development workflow.

The Android prototype depends on externally provisioned, pinned build inputs and
artifacts. The C142 preview APK is published in GitHub Releases, but its attached
source archive is partial and this repository is not a self-contained Android
build environment. See the
[prototype build/history notes](scripts/arm64-prototype/app/README.md) and
[launcher design](docs/launcher-design.md).

## Host setup: download a client

The provisioning CLI is for a Linux x86-64 development host, with Python 3.12 or
newer managed by [`uv`](https://docs.astral.sh/uv/). From a repository checkout:

```bash
uv sync
uv run forever-client --help
uv run forever-client resolve
uv run forever-client install /path/to/client
uv run forever-client verify /path/to/client
uv run forever-client repair /path/to/client
```

To install only the loose files (a small bootstrap, **not a playable full client**):

```bash
uv run forever-client install /path/to/client --loose-only
```

**Verification scope:** `forever-client verify` checks the loose files listed by
the install manifest using MD5. It does **not** verify CASC objects under `Data/`,
so it must not be treated as a complete whole-client integrity check.

**Disk space:** client size depends on build, architecture, locale, and selected
content. A historical Windows `x86_64`/`enUS` size-manifest measurement was 84.7 GB;
it is not an estimate for the separately tested ARM64 build. The
[installation findings](docs/fase-1-instalacion-cliente.md) explain the measured
variants and memory constraints. Leave substantial free space for downloads,
temporary data, and the installed client; check the selected build's manifests
rather than relying on a single fixed size.

## Development and tests

Install the development test dependency group and run the repository's tests:

```bash
uv sync --group dev
uv run pytest tests/ -q
scripts/contract-drift-check
```

The contract check compares the versioned Forever compatibility contract against
its upstream reference. Rust patcher tests are available with:

```bash
~/.cargo/bin/cargo test --manifest-path crates/forever-patcher/Cargo.toml
```

Do not run the client or Wine as root. Do not archive a live client installation;
use a completed snapshot. Keep client files, CASC data, APKs, runtime binaries, and
private device captures outside version control. See
[repository privacy](docs/repository-privacy.md) for local inputs, device selection,
and the limits of `.gitignore`.

## Project documentation

- [Current status and release limitations](docs/current-status.md)
- [Historical product status and test evidence](docs/launcher-app-status.md)
- [Release process](docs/releasing.md) and [release-notes template](docs/release-notes-template.md)
- [Architecture baseline and decisions](docs/architecture.md)
- [Launcher design](docs/launcher-design.md)
- [Client installation findings](docs/fase-1-instalacion-cliente.md)
- [Android runtime test results](docs/android-runtime-test-results.md)
- [Documentation index](docs/README.md)
- [Forever compatibility contract](forever/contract.json)
- [Full specification set](.agents/specs/)

## License and trademarks

Project code and documentation are provided under the [MIT License](LICENSE),
unless a file states otherwise. The client and its assets remain proprietary to
their respective owners and are not included. This is an independent fan project,
not affiliated with, endorsed by, or sponsored by Blizzard Entertainment or
Classic Forever. World of Warcraft and related marks belong to their respective
owners.
