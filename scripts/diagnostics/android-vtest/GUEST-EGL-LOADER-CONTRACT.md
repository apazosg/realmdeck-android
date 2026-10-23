# Guest EGL loader gate — POCO-only, no renderer integration

Approved Phase B of `gpu-platform-support-plan.md`. This gate executes one small
owned native diagnostic, not Wine, the APK or a new renderer. Do not mix the guest
Mesa libraries with the server's Android-EGL namespace.

## Inputs and scope

- Device `DEVICE_SERIAL`, package `com.wowforever.arm64probe`, UID 10330,
  installed debug code 41. Existing SDK ADB/NDK24 only.
- HOST guest directory:
  `/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/private_egl_candidate-v1`.
- Manifest SHA `30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9`;
  tree SHA `1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216`.
- Exactly 28 regular ELF files / 159,453,576 bytes, a separate 105-byte vendor JSON,
  and 13 audited relative aliases. Do not refresh packages, build Mesa, patch
  RUNPATHs, load libraries on the host, or modify the existing candidate.
- Each private run has canonical cwd
  `<app-root>/files/forever/controls/vtest-loader/VTEST-<32 lowercase hex>`.
  New exclusive `guest/` subtree, never ImageFS/current prefix/old RUN.
- Guest loader target `guest/lib/libEGL.so.1.1.0`, 73,712 bytes, SHA
  `88ccad71e437eadaead1729d43ff8bd0fed9a5a70956d0dcd98532f0558ee6e8`.
  Mesa vendor target `guest/lib/libEGL_mesa.so`, 362,040 bytes, SHA
  `7908687035ae31cc24db3face426554e5c0262edd620ea4e13865f9aa474bda5`.
- Vendor JSON `guest/share/glvnd/egl_vendor.d/50_mesa.json`, 105 bytes, SHA
  `cd3881df4227c13ca7f9cfe6c78820f21d5f7a00a41b299214f6f827e63be369`,
  `ICD.library_path = libEGL_mesa.so.0`.

## Fixed execution and observations

Native `GuestEglLoader --run-id VTEST-<hex>` only. No arbitrary library, command,
path or environment arguments. Linked only to system libc/libdl, PIE AArch64;
artifact outside repo, <=64 KiB, strict compiler/ELF verification.

Clean child environment:

```text
PATH=/system/bin:/system/xbin
ANDROID_ROOT=/system
ANDROID_DATA=/data
LD_LIBRARY_PATH=<private-root>/guest/lib:/system/lib64
TMPDIR=<private-root>
__EGL_VENDOR_LIBRARY_FILENAMES=<private-root>/guest/share/glvnd/egl_vendor.d/50_mesa.json
GALLIUM_DRIVER=virpipe
VTEST_SOCKET_NAME=vtest.sock
```

No LD_PRELOAD, DISPLAY, Vulkan or server execution. `vtest.sock` has no listening
server in this loader-only gate: no protocol/context/draw qualification is claimed.
Native code must not initialize a display, context, socket, shader or window.

1. Validate UID/RUN/canonical cwd and exact clean paths/selection.
2. `dlopen` the fixed private GLVND loader `RTLD_NOW | RTLD_LOCAL`.
3. Resolve the nine audited public functions (`eglBindAPI`, `eglChooseConfig`,
   `eglCreateContext`, `eglGetError`, `eglGetPlatformDisplay`, `eglGetProcAddress`,
   `eglInitialize`, `eglMakeCurrent`, `eglTerminate`) and `eglQueryString`.
   Check `dladdr` canonical loader owner, not the system EGL or vendor entrypoints.
4. Query client extensions via the GLVND `eglQueryString(EGL_NO_DISPLAY,
   EGL_EXTENSIONS)`, then EGL error. Bounded 16 KiB scan / 512-byte text preview.
   Do not invoke `egl*` directly on the Mesa vendor.
5. Read only bounded `/proc/self/maps` (<=2 MiB, lines <=4096), emit presence flags
   for exact own guest loader/vendor/Gallium/LLVM and Android EGL/GLES filenames.
   No full maps/other process arguments/account files. Library constructors may
   load dependencies; absence of a mapped vendor is UNKNOWN, not proof of rejection.
6. `dlclose` owned handle and report cleanup/errors. No signals/handlers/groups.

## Native JSONL / classification

Schema `wowforever.android-guest-egl-loader/version1`. Exactly eight ordered events:
`begin`, `scope`, `library`, `exports`, `client_extensions`, `mappings`, `cleanup`,
`end`. Every row: schema/runId/event/timeNs/pid/uid/pgid/sid; fixed bounded string
fields, no dynamic keys. All row scalar types explicit. stdout <=64 KiB, stderr
<=128 KiB; native JSON write/clock failures cannot pass.

- `GUEST_LOADER_PASS`: private loader ownership, all function exports, successful
  bounded client query, exact private vendor/Gallium/LLVM mapped, no system EGL/GLES
  fallback observed, and clean native exit 0/complete evidence.
- `LOADER_FAILED`: concrete dlopen/dlsym/query/cleanup failure with error evidence.
- `UNKNOWN`: partial/missing vendor mapping, invalid scope, clock, truncated output,
  timeout, binding or cleanup uncertainty. Do not infer hardware impossibility.
- `eglContextVerified`, `virpipeVerified`, `hardwareRenderingVerified`,
  `triangleVerified`, `presentationVerified`, `d3dVerified`, `wowVerified` are
  **always false**. `GALLIUM_DRIVER` requested alone is not virpipe qualification.

HOST runner only fixed inputs + `--output` existing external parent, host runtime
<=30 s after exec, explicit stdin DEVNULL and bounded drain. Before ANY execution,
verify manifest/hash/count/streamed payload hashes/ELF types/relative aliases,
exclusive private copy and all device hashes/modes. Stage via normal ADB sync,
remove only own staging files; persist capture before Popen. Require actual ADB
shell-v2 nonzero-status sentinel, debug/run-as UID and no existing own processes.
Timeout cleanup only exact package framework force-stop plus held HOST ADB handles,
with unknown ownership never PASS. Preserve raw output on every failure.

The final native artifact pin is filled by the parent after source/build tests;
runner placeholders fail closed until then. No phone execution by builders.
