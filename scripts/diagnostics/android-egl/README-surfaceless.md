# Native EGL surfaceless control

`EglSurfaceless.c` is a bounded, read-only Android control for the GLES2
`EGL_NO_SURFACE` path.  It is deliberately separate from `EglBaseline.c`: the
baseline proves GLES3 with a pbuffer, while this probe chooses an
`EGL_WINDOW_BIT`/`EGL_OPENGL_ES2_BIT` config and never creates a pbuffer or
native window.

## Build

The pinned NDK 24 toolchain is used without downloading or installing anything.
The build writes only to:

`/home/adrian/wowforever-artifacts/arm64-prototype/controls/egl-surfaceless`

```bash
scripts/diagnostics/android-egl/build_surfaceless.sh
```

The script emits the AArch64 PIE, `EglSurfaceless.readelf.txt`, and
`manifest.json`.  The manifest records exact SHA-256 values for the source,
recipe, this README, and the native artifact, plus ELF type `ET_DYN`, machine
183, needed libraries, size, and artifact hash.  The artifact is capped at
256 KiB.  This worker does not execute the ARM64 artifact; the parent owns the
POCO Xaga device run.

## Interface and events

```text
EglSurfaceless --run-id <ASCII-safe-id>
```

`--run-id` must be 1–100 bytes, beginning with an ASCII alphanumeric and then
using only ASCII alphanumerics, `_`, `-`, or `.`.  Valid execution emits exactly
these JSONL events, in order:

`begin`, `egl`, `context`, `caps`, `shaders`, `draw`, `readback`, `end`

Every row has schema `wowforever.android-egl-surfaceless/version1`, `runId`,
`event`, and monotonic `timeNs`.  `begin` and `end` carry PID/UID and PGID/SID
when the platform supplies them.  Each write is flushed and the complete
stdout stream is bounded to 64 KiB; strings are escaped by the native encoder
exactly once.

The EGL row scans the complete extension string only within a 16 KiB bound and
reports exact-token booleans for `EGL_KHR_create_context` and
`EGL_KHR_surfaceless_context` before separately truncating display text.  A
null, unterminated, unreadable, or over-bound string is `UNKNOWN`, never a
capability pass.

The context is ES2, made current with `EGL_NO_SURFACE` for both draw and read
surfaces.  The probe owns a 64x64 RGBA texture and complete framebuffer,
compiles ES2-compatible `#version 100` shaders, draws a VBO triangle, calls
`glFinish`, and reads back at least 128 colored pixels with a non-zero FNV-1a
hash.  EGL and GL errors and cleanup failures are explicit.  `end` can report
`SURFACELESS_OFFSCREEN_PASS` only when every measured stage passes and cleanup
and native exit are successful.  Presentation, D3D, WoW, and vtest are always
reported false; this probe does not classify hardware or claim the exact
upstream vtest binary.
