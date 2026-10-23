# Private guest EGL context gate

`GuestEglContext` is the next bounded POCO Phase-B control after the private
guest loader gate. It starts no client workload and performs no vtest socket
traffic, drawing, triangle, presentation, D3D, Wine, or WoW operation. The
fixed Android-default source server is started by the HOST runner only so its
context-created stdout witness and exact PID/start-time identity can be
captured.

## Build and artifact

```text
scripts/diagnostics/android-vtest/build_guest_context.sh
```

The recipe requires the existing external controls parent and writes only:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-context/
```

It uses Android NDK `24.0.8215888`, target `aarch64-linux-android29`,
`-O2 -Wall -Wextra -Werror -fPIE -pie`, no build ID, and only `libdl.so` and
`libc.so`. The PIE and its readelf/source/include/compiler manifest are bounded
to 64 KiB. `GuestEglLoader.c` is included only for its already reviewed pure
bounded helpers; its old main is renamed and is never called.

## Fixed invocation and environment

```text
GuestEglContext --run-id VTEST-<32 lowercase hexadecimal characters>
```

The HOST runner attaches to a new, existing private server run under
`files/forever/controls/vtest-loader/<RUN>`. The server keeps the Android-root
`lib/` and Android EGL/GLES aliases and runs exactly:

```text
virgl_test_server --no-loop-or-fork --use-gles --socket-path vtest.sock
```

The context child uses the same fixed clean environment as the loader:

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

There is no `DISPLAY` or `LD_PRELOAD`, and no caller-facing executable, path,
environment, server, or driver option. The only `dlopen` is the fixed,
relative `guest/lib/libEGL.so.1.1.0` with `RTLD_NOW | RTLD_LOCAL`. The ten
public GLVND exports are resolved and owner-checked against that private
loader. `eglDestroyContext`, `glGetString`, and `glGetError` are obtained only
through the already loaded loader's `eglGetProcAddress`; the latter two are
not dlsym exports.

## Native measurement

Before EGL work the probe loads `server.identity`, requiring a regular 0600
file owned by UID 10330, and revalidates its PID/start ticks and UID. It then:

1. queries client extensions and obtains
   `eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL)`;
2. initializes the display and scans the actual display extension string,
   bounded to 16 KiB, requiring `EGL_KHR_create_context` and
   `EGL_KHR_surfaceless_context`;
3. binds `EGL_OPENGL_API`, chooses one RGBA8/OpenGL config with
   `EGL_SURFACE_TYPE=0` (no implicit window requirement), requests an
   explicit OpenGL 3.3 core context, and makes it current with no surface;
4. resolves bounded GL vendor/renderer/version/GLSL strings and GL errors, and
   measures `GL_MAJOR_VERSION`, `GL_MINOR_VERSION`, `GL_CONTEXT_PROFILE_MASK`;
5. recognizes a case-insensitive `virgl` renderer string. `GALLIUM_DRIVER`
   alone is never evidence, and llvmpipe/softpipe/swrast/software renderers
   never pass;
6. reads at most 2 MiB of maps, requiring the private loader/vendor/Gallium/
   LLVM paths and absence of Android-root EGL/GLES mappings; and
7. revalidates the same server identity before unbind/destroy/terminate and
   closes every owned EGL/dlopen resource with real error accounting.

A missing DRI module or any EGL/context/renderer failure is reported honestly;
the probe does not add a driver, use ImageFS/system fallback, force llvmpipe,
or retry another configuration.

## JSONL contract

Exactly ten rows are emitted in this order:

```text
begin, scope, library, exports, display, context, gl, mappings, cleanup, end
```

Every row contains `schema`, `runId`, `event`, positive monotonic `timeNs`,
and positive `pid`, `uid`, `pgid`, `sid`. The schema is
`wowforever.android-guest-egl-context/version1`. All event fields are present;
strings are bounded strings or `null`, and no field is inferred from an
environment variable.

```text
begin:   status:string (STARTED)
scope:   status, scopeValid, runIdValid, uidValid, cwdValid,
         environmentValid, selectionValid, cwd, libraryPath,
         serverIdentityLoaded, serverIdentityVerified,
         expectedServerPid, expectedServerStartTicks
library: status, loaded, privatePath, canonicalPath, loadError
exports: status, exportCount, expectedExportCount, allExports, ownerExact,
         ownerPath, missingSymbol, wrongOwnerSymbol
display: status, clientQueryValid, clientExtensionScanValid, clientExtensions,
         clientExtensionScanBytes, clientExtensionsTruncated,
         hasEGL_EXT_platform_base, displayCreated, displayInitialized,
         displayExtensions, displayExtensionScanBytes, displayExtensionsTruncated,
         hasEGL_KHR_create_context, hasEGL_KHR_surfaceless_context, vendor,
         version, clientApis, major, minor, eglError, serverIdentityVerified
context: status, boundApi, configCount, configChosen, contextCreated,
         contextMajor, contextMinor, profileCore, makeCurrent, eglError,
         serverIdentityVerified
gl:      status, glExportsResolved, vendor, renderer, version, glslVersion,
         rendererContainsVirgl, softwareFallback, glError,
         serverIdentityVerified
mappings:status, mapsRead, mapsComplete, mapsTruncated, mapsBytes, mapLines,
         privateLoaderMapped, privateVendorMapped, privateGalliumMapped,
         privateLlvmMapped, androidEglMapped, androidGlesMapped, mappingError
cleanup: status, unbindAttempted, unbindOk, destroyAttempted, destroyOk,
         terminateAttempted, terminateOk, handleClosed, cleanupFailures,
         serverIdentityRevalidated, cleanupStatus
end:     status, privateLoaderVerified, exportsVerified, displayVerified,
         eglContextVerified, virpipeVerified, glRendererVerified,
         identityVerified, mappingsVerified, cleanupVerified,
         hardwareRenderingVerified=false, triangleVerified=false,
         presentationVerified=false, d3dVerified=false, wowVerified=false,
         nativeExit
```

`GUEST_CONTEXT_PASS` is emitted only for a private current context, a
non-software virgl renderer, the known live server, exact mappings, complete
cleanup, clean native output, and all host gates. It does not imply hardware,
triangle, presentation, D3D, WoW, or Wine support.
