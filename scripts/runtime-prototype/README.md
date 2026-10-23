# Integrated runtime prototype

This directory contains the reproducible bootstrap and source patches for the
isolated `wow-mobile` runtime probe APK. It deliberately contains no APKs,
client files, compressed rootfs, Wine/Box64 binaries, or generated probe
executables.

The external build workspace is fixed by the bootstrap at:

`/home/adrian/wowforever-artifacts/integrated-runtime/`

Run the bootstrap from the repository root:

```bash
bash scripts/runtime-prototype/bootstrap.sh
```

The bootstrap creates a git worktree named `probe-build` from the already-clean
`base-build` checkout. Git objects are shared; the baseline itself is never
written. It copies only the small source overlays, applies checked patches,
compiles the two existing probes, and builds the APK outside this repository.
It does not clone, download SDK packages, or install toolchains. It requires the
JDK 17 / Gradle 7.3.3 / SDK 34 / NDK 24 / CMake 3.22.1 baseline recorded in the
prototype document.

The resulting APK is a diagnostic runtime only. It does not contain a WoW
client, Forever patcher, realm settings, addon, or client configuration. Its
launcher installs the bundled rootfs privately, creates its own container and
shortcut, starts the upstream XServer activity in probe mode, and exposes the
latest run report through Android's Storage Access Framework.
