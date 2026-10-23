# Fixed Mesa guest context gate (POCO only)

Continues the approved GPU Phase B, after the guest-only loader gate passed.
No APK, Wine, DLL, Vulkan/driver, ImageFS or account changes. No presentation,
draw, D3D or WoW acceptance follows from a context.

## Immutable components and isolation

- Device `DEVICE_SERIAL`, package `com.wowforever.arm64probe`, UID 10330,
  installed debug code 41; existing SDK ADB/NDK24 only.
- Server: `run_source_handshake_usb.ANDROID_DEFAULT`, manifest
  `b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28`.
  Same ten ELF files and three aliases, same single-client protocol/server flags:
  `--no-loop-or-fork --use-gles --socket-path vtest.sock`.
- Guest: `run_guest_loader_usb` manifest
  `30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9`,
  tree `1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216`.
  28 ELF/159453576 bytes plus 105-byte vendor JSON, 13 relative aliases.
- New exclusive private RUN root under `files/forever/controls/vtest-loader/`.
  Server uses root `lib/` and its Android EGL/GLES aliases. Client uses **only**
  `guest/lib/` plus `/system/lib64`. Never prepend Mesa EGL to server environment.
- New native `GuestEglContext --run-id VTEST-<32 lower hex>`; PIE AA64 <=64 KiB,
  only `libc.so`/`libdl.so`, outside repo. No arbitrary library/path/env options.

## Native measurement

Reuse the fixed guest loader's scope/owner/map helpers where practical without
modifying `GuestEglLoader.c` or its artifact. Validate same exact clean client
environment, paths and private vendor JSON. Server identity file is written only
after HOST proves fixed PID/UID/startTicks/executable/arguments; native reads and
revalidates that known instance before EGL work and before terminating display.
No signal, socket modification or resource command from the probe itself.

1. `dlopen` private GLVND, resolve public functions through that loader only.
2. `eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL)` through
   GLVND, `eglInitialize`, query actual display EGL strings/extensions/errors.
3. Bind **desktop OpenGL** `EGL_OPENGL_API`, choose one RGBA8/OpenGL config with
   `EGL_SURFACE_TYPE=0` (do not inherit EGL's default window requirement).
4. Request an explicit OpenGL 3.3 core context via `EGL_KHR_create_context`, then
   `eglMakeCurrent(... EGL_NO_SURFACE ...)` only with actual surfaceless support.
   No version/capability overrides, software fallback or second configuration.
5. Resolve `glGetString`/`glGetError` through `eglGetProcAddress`; record bounded
   actual vendor/renderer/GL/GLSL strings. Software renderer is a reported outcome,
   not hardware support. Requested `GALLIUM_DRIVER=virpipe` alone proves nothing.
6. Read bounded self maps to prove private GLVND/Mesa/Gallium/LLVM and absence of
   system EGL/GLES. Revalidate expected server identity while display is active.
7. Unbind/destroy/terminate/dlclose with real error accounting, even on failure.

Exactly ten ordered JSONL records, schema
`wowforever.android-guest-egl-context/version1`:
`begin`, `scope`, `library`, `exports`, `display`, `context`, `gl`, `mappings`,
`cleanup`, `end`. Common schema/RUN/event/positive pid/uid/pgid/sid/monotonic time.
Document exact scalar keys/types in README, provide HOST fixture emitted by the
actual encoder; no omitted/ambiguous event fields. Preview <=512 bytes, extension
scan <=16 KiB, maps <=2 MiB, stdout64 KiB/stderr128 KiB. Clock/output failure cannot
pass. End `GUEST_CONTEXT_PASS` means private context + renderer containing `virgl`
(case-insensitive) with known live server, exact mappings and clean exit; software,
missing drivers/extensions or rejected context remain UNKNOWN/CONTEXT_FAILED.
Keep hardwareRendering/triangle/presentation/D3D/WoW verified flags false always.

## HOST orchestration

Only fixed candidate/probe inputs and `--output` (existing external parent).
Reuse existing manifest/hash/device mode/UID/cwd/shell-v2 sentinel/owned capture
gates; no monkeypatch of global module authority. If extending the guest copier
to attach under the already-created server RUN, require the exact immutable
server files/manifest and fresh absent `guest/` before mutation; default loader
behavior remains unchanged. Capture guest/server pins and both exact environments
before Popen. Stdin DEVNULL, 30 s host native deadline, bounded continuous drains.
Bind HOST server PID/startTicks before client, record on disk, check native result
against that same record. Context PASS also requires server context-created witness,
no renderer failure and clean server/client completion. If this Mesa client makes
temporary connections and the fixed server exits early, preserve the result;
do not silently switch loop/multi-client modes in the same run.

No new package/download/guest driver blob or RUNPATH patch in this first context
attempt. In particular a missing DRI module is evidence, not permission to point
at system/ImageFS drivers or force llvmpipe. Timeout/error cleanup remains exact
package framework force-stop and held HOST handles, never guessed PIDs/groups.
Unknown ownership/forced cleanup never yields PASS. Leave previous RUNs intact.
