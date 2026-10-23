# FrontMage/FEX runtime prototype recipe

This directory contains only the source-pinned recipe and textual audit
metadata. It deliberately contains no APK, Wine, FEX, imagefs, or other binary
runtime payload. All checkouts, build trees, reports, and APKs belong under the
external artifact root:

`/home/adrian/wowforever-artifacts/frontmage-prototype/`

The default baseline is the immutable `FrontMage/winlator-llm` commit
`4293b75b449d11fdf66da567791c9165fa6df6a0`. It preserves the upstream package
`com.winlator.llm` and build settings. Do not point it at `main` or use it to
overwrite an installed FrontMage APK.

The public source checkout intentionally omits three release payloads. The
known release APK for this same source commit can be checked and extracted into
the external generated-assets directory, without replacing the source FEX
asset:

```bash
scripts/frontmage-prototype/recover-release-assets.sh
scripts/frontmage-prototype/bootstrap.sh --with-recovered-assets
```

Recovery refuses a different release APK, verifies the FEX2508 and Proton 9
hashes already present in the source build, and copies only the exact
`imagefs.txz`, `imagefs_patches.tzst`, and Proton 10 `.wcp.xz` entries. It
writes the full release asset inventory and provenance under the external
artifact root. The recovered build is an external debug APK and is not
installed.

## Usage

```bash
scripts/frontmage-prototype/bootstrap.sh
```

The recipe first pins the source and submodule, checks the upstream Android
toolchain requirements, and only then invokes the upstream Gradle build. It
stops when SDK 30 or NDK 22.1 is unavailable; SDK 34/NDK 24 are not silently
substituted. On a successful build it records APK metadata, native ABI/assets,
ELF `PT_INTERP`, package-path scans, and provenance in the external artifact
root.

`overlays/` is intentionally empty of implementation patches at this gate.
Feature overlays are prohibited until the clean baseline compiles and the
native path/package audit is reviewed.
