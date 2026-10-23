# Android EGL/GLES offscreen baseline

`EglBaseline.c` is a small, standalone Android arm64 diagnostic for the Phase B
native EGL qualification. It measures the system EGL/GLES implementation under
the calling app UID; it is not a Wine, D3D, presentation, WoW, or memory-patching
probe.

The only accepted argument is:

```text
EglBaseline --run-id ID
```

`ID` is ASCII, 1–100 characters, and uses only letters, digits, `_`, `-`, or
`.`. The executable writes only bounded JSONL to stdout, in this fixed event
order:

```text
begin egl context caps shaders draw readback end
```

Each record has schema `wowforever.android-egl-baseline/version1`. Driver strings
are reported as returned by EGL/GLES and are explicitly bounded. The terminal
`OFFSCREEN_PASS` classification requires a current ES 3 context, successful
fixed shaders, clean GLES errors, and at least 128 non-black pixels from the
64×64 RGBA8 pbuffer readback. It does not claim a display frame. `presentationVerified`,
`d3dVerified`, and `wowVerified` are always false.

The cross-build uses only the pinned existing NDK and Android `libEGL`,
`libGLESv3`, `libc`, and as-needed `libdl` imports. It creates the ELF and its
readelf/manifest evidence outside the repository:

```bash
scripts/diagnostics/android-egl/build.sh
```

The build verifies an arm64 `ET_DYN` PIE, a maximum size of 256 KiB, and an
allowlisted dynamic dependency set. The arm64 executable is not run on the
host by the offline tests.
