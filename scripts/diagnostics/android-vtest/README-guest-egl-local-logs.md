# Guest EGL local-logs control

This is a separate, offline-built diagnostic ELF for the exact local Mesa
EGL/DRI rejection. It reuses verified copies of `GuestEglContext.c` and
`GuestEglLoader.c`; the repository sources and the existing control are not
changed. The canonical patch (`--fuzz=0`) changes only the loader environment
allowlist and exact-value comparison.

The eight existing environment entries and values remain unchanged. The only
new entries are these three exact values:

```text
EGL_LOG_LEVEL=debug
MESA_LOG=file
MESA_LOG_LEVEL=debug
```

The complete fixed environment is:

```text
PATH=/system/bin:/system/xbin
ANDROID_ROOT=/system
ANDROID_DATA=/data
LD_LIBRARY_PATH=<RUN>/guest/lib:/system/lib64
TMPDIR=<RUN>
__EGL_VENDOR_LIBRARY_FILENAMES=<RUN>/guest/share/glvnd/egl_vendor.d/50_mesa.json
GALLIUM_DRIVER=virpipe
VTEST_SOCKET_NAME=vtest.sock
```

Unknown names, missing names, duplicate names, and other values are rejected.
There are no Mesa/Wine/backend changes, driver paths, feature overrides, or
logging path overrides. Context attributes, OpenGL 3.3 core request, UID/RUN,
argv, output limits, maps, JSON schema, identity gates, and cleanup are the
existing context control.

## Build

Run from the repository root:

```text
bash scripts/diagnostics/android-vtest/build_guest_egl_log.sh
```

The recipe first verifies both fixed source SHA-256 values, requires the
existing external controls parent, copies into a private temporary stage, and
applies the canonical patch with fuzz zero. It refuses ambient C/C++/linker
flags. It uses only the existing NDK `24.0.8215888` compiler targeting
`aarch64-linux-android29`, `/usr/bin/patch`, and the system linker inputs.

Output is exclusively under:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-local-logs/
```

The manifest records the original and result source hashes, patch, recipe,
compiler, NDK target, readelf contract, and resulting ELF SHA-256. The ELF is
AA64 PIE/`ET_DYN`, uses only `libc.so` and `libdl.so`, requests
`/system/bin/linker64`, and is limited to 64 KiB.

This build does not run an Android executable, use ADB, alter an APK/runtime,
start a server, send signals, access Wine/Mesa source, or claim a context or
graphics pass. Device orchestration and interpretation remain with the parent
control. The native result must preserve `CONTEXT_FAILED`; generic
`dri2_create_context` → `EGL_BAD_MATCH` output is not an identified API,
version, or flag cause.
