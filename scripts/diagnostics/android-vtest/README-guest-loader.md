# Private guest EGL loader gate

`GuestEglLoader` is the approved POCO Phase-B native control for one narrow
question: can the pinned private GLVND loader load its pinned Mesa vendor ELF
closure and expose the audited public EGL entry points?  It does not test a
display, context, socket, virpipe protocol, renderer, Wine, D3D, or WoW.

The loader is linked only to Android `libc.so` and `libdl.so`.  It never runs
on the host; the build only cross-compiles and inspects the AArch64 ELF.

## Build and artifact

```text
scripts/diagnostics/android-vtest/build_guest_loader.sh
```

The script first verifies the existing, immutable candidate at:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/private_egl_candidate-v1
```

It checks the pinned candidate manifest, 28 selected regular files,
159,453,576 selected bytes, 13 aliases, the loader/vendor/vendor-JSON SHA-256
values, and `ICD.library_path = libEGL_mesa.so.0`.  Only then does it create:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-loader
```

The output is a PIE AArch64 `GuestEglLoader` of at most 64 KiB, with no build
ID, strict `-Wall -Wextra -Werror`, and a readelf manifest.  The manifest
contains payload, compiler, source, recipe, README, ELF, dependency, and
candidate pins.  The NDK recipe is fixed to 24.0.8215888 and
`aarch64-linux-android29`.

## Fixed invocation and scope

```text
GuestEglLoader --run-id VTEST-<32 lowercase hexadecimal characters>
```

The process must run as UID 10330 from:

```text
/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/VTEST-<hex>
```

The equivalent `/data/data/com.wowforever.arm64probe/...` spelling is accepted.
The native code accepts no library, directory, command, or environment
arguments.  Its required clean environment is exactly the contract's PATH,
Android roots, private guest `LD_LIBRARY_PATH`, private `TMPDIR`, Mesa vendor
JSON selection, `GALLIUM_DRIVER=virpipe`, and `VTEST_SOCKET_NAME=vtest.sock`.
It rejects other environment names.

The only `dlopen` target is the fixed relative path from the validated
canonical run directory:

```text
guest/lib/libEGL.so.1.1.0
```

It uses `RTLD_NOW | RTLD_LOCAL`.  The vendor JSON and all transitive private
ELFs are selected by the loader's clean environment; the native code never
opens the vendor directly.

## JSONL contract

Exactly these eight rows are emitted, in order:

```text
begin, scope, library, exports, client_extensions, mappings, cleanup, end
```

Every row has these common fields and types:

```text
schema:string = "wowforever.android-guest-egl-loader/version1"
runId:string = the supplied VTEST-... value
event:string
timeNs:integer (>0, monotonic)
pid:integer, uid:integer, pgid:integer, sid:integer
```

The fixed event fields are as follows.  `string?` is a bounded string or
`null`; `bool?` is a boolean or `null` when the observation is unavailable;
integers are bounded native integers.

```text
begin:
  status:string (STARTED)

scope:
  status:string (VALID|UNKNOWN)
  scopeValid:bool, runIdValid:bool, uidValid:bool, cwdValid:bool
  environmentValid:bool, selectionValid:bool
  cwd:string?, libraryPath:string?

library:
  status:string (LOADED|LOADER_FAILED|UNKNOWN)
  loaded:bool, privatePath:string?, canonicalPath:string?, loadError:string?

exports:
  status:string (EXPORTED|LOADER_FAILED|UNKNOWN)
  exportCount:integer, expectedExportCount:integer (10), allExports:bool
  ownerExact:bool, ownerPath:string?, missingSymbol:string?
  wrongOwnerSymbol:string?

client_extensions:
  status:string (MEASURED|LOADER_FAILED|UNKNOWN)
  queryValid:bool, extensionScanValid:bool, extensions:string?
  extensionScanBytes:integer, extensionsTruncated:bool
  hasEGL_EXT_platform_base:bool?, hasEGL_KHR_create_context:bool?
  hasEGL_KHR_surfaceless_context:bool?, eglError:integer|null

mappings:
  status:string (MAPPED|UNKNOWN)
  mapsRead:bool, mapsComplete:bool, mapsTruncated:bool
  mapsBytes:integer, mapLines:integer
  privateLoaderMapped:bool?, privateVendorMapped:bool?
  privateGalliumMapped:bool?, privateLlvmMapped:bool?
  androidEglMapped:bool?, androidGlesMapped:bool?
  mappingError:string?

cleanup:
  status:string (CLEAN|UNKNOWN)
  cleanupFailures:integer, handleClosed:bool, cleanupStatus:string (CLEAN|UNKNOWN)

end:
  status:string (GUEST_LOADER_PASS|LOADER_FAILED|UNKNOWN)
  privateLoaderVerified:bool, exportsVerified:bool
  clientExtensionsVerified:bool, mappingsVerified:bool, cleanupVerified:bool
  eglContextVerified:bool=false, virpipeVerified:bool=false
  hardwareRenderingVerified:bool=false, triangleVerified:bool=false
  presentationVerified:bool=false, d3dVerified:bool=false, wowVerified:bool=false
  nativeExit:integer (0 on pass, 1 otherwise)
```

The loader resolves exactly these ten public exports with `dlsym`: `eglBindAPI`,
`eglChooseConfig`, `eglCreateContext`, `eglGetError`, `eglGetPlatformDisplay`,
`eglGetProcAddress`, `eglInitialize`, `eglMakeCurrent`, `eglQueryString`, and
`eglTerminate`.  Every symbol's `dladdr` owner is canonicalized and must be
the private `libEGL.so.1.1.0`; vendor entry points are never called.

The sole EGL call is the GLVND `eglQueryString(EGL_NO_DISPLAY,
EGL_EXTENSIONS)`, followed by the resolved `eglGetError`.  The extension scan
is bounded to 16 KiB and the emitted preview to 512 bytes.  No display,
context, initialization, current surface, draw, shader, socket, signal,
process, or driver operation is performed.

Mappings read only `/proc/self/maps`, up to 2 MiB with 4 KiB maximum lines, and
emit flags rather than the map text.  A missing private vendor mapping is
`null`/`UNKNOWN`, never a negative proof.  A pass requires exact private
loader, Mesa vendor, Gallium, and LLVM mappings and no fixed Android EGL/GLES
fallback mapping, in addition to all export/query/cleanup and output gates.

`GUEST_LOADER_PASS` proves only this loader gate.  It does not claim any of the
seven broader verification flags.
