# Native libepoxy EGL context probe

`EglEpoxyProbe.c` is a bounded, read-only AArch64 Android control for the
private Termux libepoxy candidate.  It is not a vtest client, renderer test,
Wine probe, or runtime change.  It distinguishes libepoxy's loader boundary
from the already-qualified direct system EGL control on the permanently
connected POCO Xaga.

## Build

The build uses only the installed NDK 24 toolchain and writes outside the
repository:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/egl-epoxy
```

Run:

```bash
scripts/diagnostics/android-vtest/build_epoxy_probe.sh
```

The output contains the AArch64 PIE, `EglEpoxyProbe.readelf.txt`, and a
deterministic `manifest.json`.  The build is `-Wall -Wextra -Werror`, has no
build ID, requires only `libdl.so` and `libc.so`, and rejects an artifact over
64 KiB.  The manifest records the source, recipe, README, compiler recipe,
needed libraries, and payload SHA-256 values.  This worker never executes the
ARM64 artifact on the host.

The parent pins the reviewed ten-file candidate before launching the probe.
Its only EGL/GLES aliases are `libEGL.so.1` to `/system/lib64/libEGL.so` and
`libGLESv2.so.2` to `/system/lib64/libGLESv2.so`; this probe does not select,
replace, or introduce GLVND or Mesa EGL.

## Fixed interface and scope

```text
EglEpoxyProbe --run-id VTEST-<32 lowercase hexadecimal characters>
```

No path, plugin, or library-name argument is accepted.  The process must have
UID 10330, run from the canonical run directory
`/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/<run>`
(the equivalent `/data/data/...` form is accepted), and have exactly:

```text
LD_LIBRARY_PATH=<cwd>/lib:/system/lib64
```

The probe calls `dlopen("libepoxy.so", ...)` only.  It resolves every required
`epoxy_egl*`/`epoxy_gl*` export with `dlsym`, reads the exported data slot as a
function pointer, and never calls the `dlsym` result as if it were code.  The
`epoxy_eglGetDisplay` slot's `dladdr` owner is canonicalized and must equal
`<cwd>/lib/libepoxy.so`; this is the library-scope proof.  After the first
resolved call, the probe rereads the get-display data slot and records that
function's implementation path with `dladdr` when available.  If the function
still belongs to libepoxy, the row says `EPOXY_RESOLVER` rather than claiming
an Android system implementation.  An unavailable path is explicit
`UNKNOWN`/`null` and never passes the private-library proof.

## Measurements and output

Exactly eight flushed JSONL events are emitted in this order:

```text
begin, library, client_extensions, egl, context, caps, cleanup, end
```

Every row uses schema `wowforever.android-egl-epoxy/version1`, the exact run
ID, monotonic `timeNs`, and PID/UID/PGID/SID.  Strings are read and escaped
within fixed bounds and stdout is capped at 64 KiB.  The fixed sequence is:

1. Query client EGL extensions with `EGL_NO_DISPLAY`, recording
   `EGL_EXT_platform_base`, `EGL_KHR_create_context`, and
   `EGL_KHR_surfaceless_context` as exact-token nullable values.  This is an
   optional diagnostic: a null/error result is drained, reported separately,
   and does not block the fixed default-display/context measurement.  Its JSON
   preview is capped at 512 bytes after escaping and reports explicit
   truncation plus the full bounded scan byte count.
2. Get the default display, initialize it, query vendor/version/client APIs,
   and scan the complete display extension string within 16 KiB.  Both
   extension rows report scan bytes, validity, and whether their bounded JSON
   preview was truncated; an over-bound/nonterminated scan is UNKNOWN with
   null capability tokens.
3. Bind OpenGL ES, choose an RGBA8 `EGL_WINDOW_BIT`/`EGL_OPENGL_ES2_BIT`
   config, create an ES2 context, and make it current with
   `EGL_NO_SURFACE` for both surfaces.
4. Query the three independent GL vendor/renderer/version strings and GL
   error, then unbind, destroy,
   terminate, and close only the probe's own handle.

There is no draw, resource, vtest, socket, present, platform-MESA selection,
ptrace, ioctl, process signalling, signal handler, or hard-preemption timer.
The parent owns the host-side 25-second execution bound and must not inherit
stdin.  Renderer text alone is never treated as hardware proof.

`end.status` is exactly `EPOXY_CONTEXT_PASS` only when the private loader
proof, display, initialization, config, current context, three GL strings,
zero EGL/GL errors, cleanup, and output gates all pass.  Otherwise it reports
`CONTEXT_FAILED` or `UNKNOWN` with stage-specific evidence.  Only a pass has
`eglContextVerified:true` and `nativeExit:0`; graphics, hardware, vtest,
presentation, D3D, and WoW verification remain false in every result.
