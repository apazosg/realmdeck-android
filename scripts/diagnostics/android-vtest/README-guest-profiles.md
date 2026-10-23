# Fixed guest EGL profile matrix

`GuestEglProfiles` is a bounded, guest-only GPU Phase-B diagnostic. It classifies
four actual EGL context requests against the existing POCO vtest server; it does
not claim WineD3D, WoW, hardware rendering, triangle, presentation, D3D, or
gameplay support. It performs no draw, resource, shader, protocol-packet, ADB,
device, APK, network, or server action.

## Build and fixed inputs

```text
scripts/diagnostics/android-vtest/build_guest_profiles.sh
```

The recipe validates the existing private guest candidate with the full loader
validator before compiling, then writes only:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-profiles/
```

The output is an AArch64 Android-29 PIE built by NDK `24.0.8215888` with
`-std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie --build-id=none`. The only
needed libraries are `libdl.so` and `libc.so`; the native artifact and manifest
are each bounded to 64 KiB where applicable. The manifest records the artifact
SHA-256, guest manifest/tree and payload hashes, all included source hashes
(`GuestEglProfiles.c`, `GuestEglContext.c`, `GuestEglLoader.c`), build-script
and README hashes, compiler hash, and readelf output.

The executable accepts exactly:

```text
GuestEglProfiles --run-id VTEST-<32 lowercase hexadecimal characters>
```

There are no path, library, profile, runtime, server, or environment options.
The run must already be the fixed UID-10330 private directory and exact clean
environment validated by the reused context helpers:

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

The loader is only `guest/lib/libEGL.so.1.1.0`, loaded with `RTLD_NOW | RTLD_LOCAL`.
All eleven public EGL exports, including the additional `eglGetConfigAttrib`,
are checked as exact owners of that canonical private loader. The server identity
file is checked for UID, PID, start ticks, linker executable, exact argv, and
live `/proc` state before EGL work and around every attempt.

## Measurement contract

The fixed display is `eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA,
NULL, NULL)`, initialized once, and requires the actual
`EGL_KHR_create_context` and `EGL_KHR_surfaceless_context` extensions. The
fixed API/config request is desktop `EGL_OPENGL_API`, RGBA8,
`EGL_RENDERABLE_TYPE=EGL_OPENGL_BIT`, and `EGL_SURFACE_TYPE=0`. The selected
config's exact `EGL_RENDERABLE_TYPE`, `EGL_CONFORMANT`, `EGL_SURFACE_TYPE`, and
`EGL_CONFIG_ID` values are obtained with `eglGetConfigAttrib`; they are not
inferred from the request.

Exactly four ordered attempts are made in one native process:

1. `OpenGL3.3CORE` — major 3, minor 3, core profile mask.
2. `OpenGL3.3COMPAT` — major 3, minor 3, compatibility profile mask.
3. `OpenGL3.2CORE` — major 3, minor 2, core profile mask.
4. `OpenGL3.0DEFAULT` — major 3, minor 0, no profile mask.

Each create error is captured immediately. A created context is made current
with `EGL_NO_SURFACE`, measured through actual `glGetString`, `glGetError`, and
`glGetIntegerv`, then unbound and destroyed before the next attempt. Profile
measurement is present only for actual versions at least 3.2. Renderer strings
and software observations are measured outcomes; `GALLIUM_DRIVER=virpipe` is
not evidence. No lower context is upgraded or treated as WineD3D/WoW support.

`PROFILE_MATRIX_MEASURED` means all four ordered classifications completed with
valid identity, timing, exact maps, and cleanup. It may therefore contain four
`EGL_BAD_MATCH` failures and still be a valid matrix. `contextQualified` is
true only for an actual current OpenGL 3.3 core result. Missing exports,
failed display/config observation, identity/timing/I/O errors, a dead server,
timeout, or any cleanup error produce `UNKNOWN`. The five support flags are
always JSON boolean `false`.

## Exact JSON schema and scalar types

The native process writes one newline-terminated object, at most 16 KiB:
`wowforever.android-guest-egl-profiles/version1`.

* `schema`, `runId`, `status`: string.
* `pid`, `uid`, `pgid`, `sid`, all `*Ns`, error values, counts, sizes, versions,
  and masks: integer (unavailable measured values are `null`).
* `contextQualified`, all validity/observation/cleanup/support values: boolean.
* `scope`, `server`, `library`, `exports`, `display`, `config`, `maps`, and
  `cleanup`: objects; `attempts`: array of exactly four objects.
* Bounded strings are JSON escaped. Unavailable strings are `null`.

The complete key shape is:

```text
schema, runId, pid, uid, pgid, sid, times{startedNs,finishedNs,durationNs},
status, contextQualified, hardwareRenderingVerified, triangleVerified,
presentationVerified, d3dVerified, wowVerified,
scope{valid,environmentValid,selectionValid,cwd,libraryPath,ldLibraryPath,
vendorJson,guestManifestSha256,guestTreeSha256,serverManifestSha256},
server{expectedPid,expectedStartTicks,identityLoaded,live,liveBeforeTerminate},
library{loaded,privatePath,canonicalPath,loadError},
exports{expectedCount,exportCount,allExports,ownerExact,ownerPath,missingSymbol,
wrongOwnerSymbol},
display{clientExtensions,clientExtensionBytes,displayExtensions,
displayExtensionBytes,vendor,version,clientApis,displayCreated,
displayInitialized,eglError},
config{boundApi,chosen,count,attributesValid,EGL_RENDERABLE_TYPE,EGL_CONFORMANT,
EGL_SURFACE_TYPE,EGL_CONFIG_ID,attributeErrors{EGL_RENDERABLE_TYPE,
EGL_CONFORMANT,EGL_SURFACE_TYPE,EGL_CONFIG_ID}},
attempts[{name,requested{major,minor,profile,core},serverLiveBefore,
createCalled,createError,created,makeCurrentCalled,makeCurrentError,madeCurrent,
actual{valid,major,minor,profileKnown,profileMask,core,vendor,renderer,version,
glslVersion,glError,rendererVirgl,softwareFallback},
cleanup{unbindAttempted,unbindOk,unbindError,destroyAttempted,destroyOk,
destroyError,valid},serverLiveAfter,deadlineValid}],
maps{complete,truncated,bytes,lines,privateLoader,privateVendor,privateGallium,
privateLlvm,androidEgl,androidGles,loaderPath,vendorPath,error},
cleanup{terminateAttempted,terminateOk,terminateError,handleClosed,failures,
serverLiveBeforeTerminate}, nativeExit
```

## Full sample (classification of four rejected contexts)

```json
{
  "schema":"wowforever.android-guest-egl-profiles/version1",
  "runId":"VTEST-0123456789abcdef0123456789abcdef",
  "pid":32111,"uid":10330,"pgid":32111,"sid":32111,
  "times":{"startedNs":1,"finishedNs":2,"durationNs":1},
  "status":"PROFILE_MATRIX_MEASURED","contextQualified":false,
  "hardwareRenderingVerified":false,"triangleVerified":false,
  "presentationVerified":false,"d3dVerified":false,"wowVerified":false,
  "scope":{"valid":true,"environmentValid":true,"selectionValid":true,
    "cwd":"/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/VTEST-0123456789abcdef0123456789abcdef",
    "libraryPath":"guest/lib/libEGL.so.1.1.0","ldLibraryPath":"guest/lib:/system/lib64",
    "vendorJson":"guest/share/glvnd/egl_vendor.d/50_mesa.json",
    "guestManifestSha256":"30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9",
    "guestTreeSha256":"1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216",
    "serverManifestSha256":"b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28"},
  "server":{"expectedPid":32090,"expectedStartTicks":8892956,"identityLoaded":true,"live":true,"liveBeforeTerminate":true},
  "library":{"loaded":true,"privatePath":"guest/lib/libEGL.so.1.1.0","canonicalPath":"/data/.../guest/lib/libEGL.so.1.1.0","loadError":null},
  "exports":{"expectedCount":11,"exportCount":11,"allExports":true,"ownerExact":true,"ownerPath":"/data/.../guest/lib/libEGL.so.1.1.0","missingSymbol":null,"wrongOwnerSymbol":null},
  "display":{"clientExtensions":"EGL_EXT_platform_base","clientExtensionBytes":23,"displayExtensions":"EGL_KHR_create_context EGL_KHR_surfaceless_context","displayExtensionBytes":55,"vendor":"Mesa","version":"EGL 1.5","clientApis":"OpenGL","displayCreated":true,"displayInitialized":true,"eglError":12288},
  "config":{"boundApi":true,"chosen":true,"count":1,"attributesValid":true,"EGL_RENDERABLE_TYPE":8,"EGL_CONFORMANT":8,"EGL_SURFACE_TYPE":0,"EGL_CONFIG_ID":3,"attributeErrors":{"EGL_RENDERABLE_TYPE":12288,"EGL_CONFORMANT":12288,"EGL_SURFACE_TYPE":12288,"EGL_CONFIG_ID":12288}},
  "attempts":[
    {"name":"OpenGL3.3CORE","requested":{"major":3,"minor":3,"profile":"CORE","core":true},"serverLiveBefore":true,"createCalled":true,"createError":12297,"created":false,"makeCurrentCalled":false,"makeCurrentError":null,"madeCurrent":false,"actual":{"valid":false,"major":null,"minor":null,"profileKnown":false,"profileMask":null,"core":null,"vendor":null,"renderer":null,"version":null,"glslVersion":null,"glError":0,"rendererVirgl":false,"softwareFallback":false},"cleanup":{"unbindAttempted":false,"unbindOk":false,"unbindError":null,"destroyAttempted":false,"destroyOk":false,"destroyError":null,"valid":true},"serverLiveAfter":true,"deadlineValid":true},
    {"name":"OpenGL3.3COMPAT","requested":{"major":3,"minor":3,"profile":"COMPAT","core":false},"serverLiveBefore":true,"createCalled":true,"createError":12297,"created":false,"makeCurrentCalled":false,"makeCurrentError":null,"madeCurrent":false,"actual":{"valid":false,"major":null,"minor":null,"profileKnown":false,"profileMask":null,"core":null,"vendor":null,"renderer":null,"version":null,"glslVersion":null,"glError":0,"rendererVirgl":false,"softwareFallback":false},"cleanup":{"unbindAttempted":false,"unbindOk":false,"unbindError":null,"destroyAttempted":false,"destroyOk":false,"destroyError":null,"valid":true},"serverLiveAfter":true,"deadlineValid":true},
    {"name":"OpenGL3.2CORE","requested":{"major":3,"minor":2,"profile":"CORE","core":true},"serverLiveBefore":true,"createCalled":true,"createError":12297,"created":false,"makeCurrentCalled":false,"makeCurrentError":null,"madeCurrent":false,"actual":{"valid":false,"major":null,"minor":null,"profileKnown":false,"profileMask":null,"core":null,"vendor":null,"renderer":null,"version":null,"glslVersion":null,"glError":0,"rendererVirgl":false,"softwareFallback":false},"cleanup":{"unbindAttempted":false,"unbindOk":false,"unbindError":null,"destroyAttempted":false,"destroyOk":false,"destroyError":null,"valid":true},"serverLiveAfter":true,"deadlineValid":true},
    {"name":"OpenGL3.0DEFAULT","requested":{"major":3,"minor":0,"profile":null,"core":null},"serverLiveBefore":true,"createCalled":true,"createError":12297,"created":false,"makeCurrentCalled":false,"makeCurrentError":null,"madeCurrent":false,"actual":{"valid":false,"major":null,"minor":null,"profileKnown":false,"profileMask":null,"core":null,"vendor":null,"renderer":null,"version":null,"glslVersion":null,"glError":0,"rendererVirgl":false,"softwareFallback":false},"cleanup":{"unbindAttempted":false,"unbindOk":false,"unbindError":null,"destroyAttempted":false,"destroyOk":false,"destroyError":null,"valid":true},"serverLiveAfter":true,"deadlineValid":true}
  ],
  "maps":{"complete":true,"truncated":false,"bytes":1000,"lines":10,"privateLoader":true,"privateVendor":true,"privateGallium":true,"privateLlvm":true,"androidEgl":false,"androidGles":false,"loaderPath":"/data/.../guest/lib/libEGL.so.1.1.0","vendorPath":"/data/.../guest/lib/libEGL_mesa.so","error":null},
  "cleanup":{"terminateAttempted":true,"terminateOk":true,"terminateError":12288,"handleClosed":true,"failures":0,"serverLiveBeforeTerminate":true},
  "nativeExit":0
}
```

The sample uses abbreviated paths only for readability; a real result contains
the bounded canonical paths. A real artifact's bytes, SHA-256, source/include,
build, and README hashes are authoritative in its generated `manifest.json`.
# Validation notes

The `config` observation also records integer `bindError` and `chooseError`;
both must be `EGL_SUCCESS` before profile classification can be complete.
Selected `EGL_RENDERABLE_TYPE` and `EGL_SURFACE_TYPE` are measured bitmasks:
requesting surface type zero does not require the selected config to advertise
zero surface capabilities. Server identity reuses the existing reviewed complete
argv/PID/start-time/UID checks. `contextQualified` and all graphics acceptance
flags remain false even if one requested context is successfully measured.
