# PanVK private loader-only probe

`PanvkPrivateLoader` is a deliberately narrow API34 control for the already
audited PanVK beta16 ICD. It observes whether the Android linker can load that
one private file and whether the three ICD exports resolve from that exact file.
It does **not** qualify Vulkan, the GPU, the kernel, WSI, or the published
API35 minimum. Constructors and IFUNC resolvers in the ICD or its dependencies
may execute during `dlopen`; this probe cannot make an opaque loaded library
side-effect free.

## Fixed invocation and scope

The only accepted invocation is:

```text
PanvkPrivateLoader --run-id PVK-<32 lowercase hexadecimal characters>
```

The child must already be UID `10330`, with its canonical CWD exactly one of:

```text
/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-loader/<run-id>
/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-loader/<run-id>
```

That directory contains the non-symlink `PanvkPrivateLoader` and
`libvulkan_panfrost.so`. The native environment must contain exactly these five
variables (and no others):

```text
PATH=/system/bin
ANDROID_ROOT=/system
ANDROID_DATA=/data
LD_LIBRARY_PATH=/system/lib64
TMPDIR=<canonical CWD>
```

The ICD is fixed at 30,972,472 bytes with SHA-256
`c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
The probe streams the file itself, checking regular-file/no-follow identity,
owner, size, hash, and identity stability before and after loading. Its
`dladdr` owner must be the exact canonical ICD path.

## Frozen stdout schema

Successful or failed execution emits exactly one UTF-8 JSON object on stdout,
one line, at most 16 KiB. The following field set is frozen for the host
classifier; fields are not omitted on failure.

Top-level fields, in this order, are:

```text
schema: string = "wowforever.android-panvk-private-loader"
version: integer = 1
runId: string
pid: integer
uid: integer
pgid: integer
sid: integer
monotonicStartNs: integer
monotonicEndNs: integer
cwd: string
scopeValid: boolean
icdPath: string
icdBytes: integer
icdSha256: string
icdIdentityBefore: Identity
icdIdentityAfter: Identity
icdIdentityStable: boolean
load: Load
exports: array[3] of Export, in fixed export-name order
cleanup: Cleanup
icdCodeLoaded: boolean
constructorsMayHaveExecuted: boolean
vulkanFunctionsInvoked: boolean
apiComplete: boolean
vulkan: boolean
gpu: boolean
triangle: boolean
presentation: boolean
d3d: boolean
wow: boolean
status: string
failureStage: string
failureDetail: string
nativeExit: integer
```

`Identity` is exactly `{regular, ownerUid, device, inode, mode, size}` with
boolean `regular` and integer values for all other fields. A failed snapshot is
represented by `regular: false` and zero values. `Load` is exactly
`{attempted, loaded, dlerror}`. `Export` is exactly
`{name, found, owner, ownerExact, error}`. `Cleanup` is exactly
`{dlcloseAttempted, dlcloseOk, complete, errno, dlerror}`. Error strings are
bounded to 512 bytes and JSON escaped by the native encoder. The three fixed
export names are, in order:

```text
vk_icdGetInstanceProcAddr
vk_icdGetPhysicalDeviceProcAddr
vk_icdNegotiateLoaderICDInterfaceVersion
```

No discovered pointer or raw address is printed. The three resolved pointers
are never called; `vulkanFunctionsInvoked` is always `false`.

## Classification

`PRIVATE_ICD_LOADER_OBSERVED` with native exit `0` requires a valid positive
monotonic interval, the bound scope, the pinned size/hash and regular-file
identity before and after loading, a real successful `dlopen`, all three
exports found, all three `dladdr` owners exactly equal to the private ICD path,
stable before/after identity, an actually attempted and successful `dlclose`,
cleanup, and `failureStage: "none"`. It says only that this loader observation
completed; every broader boolean remains `false`.

`LOADER_FAILED` with native exit `1` is reserved for a failed `dlopen` with a
non-empty real `dlerror`, valid scope/binding, pinned and stable before/after
identity, valid monotonic timing, no returned handle, and truthful cleanup
(`dlcloseAttempted: false`, `dlcloseOk: false`, `complete: true`). Invalid
scope, hash, identity, I/O, missing exports, owner mismatch, malformed timing,
contradictory cleanup, or output failure is `UNKNOWN` and nonzero. A load
attempt sets `constructorsMayHaveExecuted` true even when `dlopen` fails;
`icdCodeLoaded` reflects only whether `dlopen` returned a handle. The same
post-load identity/hash check is performed after a failed load, and `dlclose`
is attempted once for every returned handle.

If normal JSON encoding cannot fit, the probe retries with a complete,
bounded `UNKNOWN` object using empty sanitized metadata while preserving
process identity, run ID, timestamps, load-attempt/load-state/constructor
truth, and actual cleanup fields. That fallback always has `nativeExit: 1` and
`failureStage: "output"`; a failed print/flush also returns nonzero and can
never become a success classification.

## Build and pins

`build_private_loader.sh` uses only the existing NDK
`/home/adrian/android-sdk/ndk/24.0.8215888` compiler
`aarch64-linux-android29-clang`, strict C11/O2/warnings-as-errors/PIE flags,
and the system linker. The resulting AA64 `ET_DYN` PIE is at most 64 KiB,
uses `/system/bin/linker64`, and has exactly `libc.so` and `libdl.so` as needed
libraries. It must not import Vulkan, GL, or EGL symbols.

The build writes only the new external directory
`/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-private-loader-v2/`:
the ELF, its `llvm-readelf` report, and a manifest containing artifact/source/
README/build-script/compiler pins plus read-only stat/hash/ELF evidence for the
original ICD at
`/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-audit-beta16/icd/libvulkan_panfrost.so`.
It performs no ADB, device, network, ICD loading, or Vulkan operation.

## Host tests

`tests/test_android_panvk_private_loader_probe.py` compiles the source with its
inert host test seam. The seam exercises the actual C SHA-256, scope matcher,
JSON encoder, owner comparison, classification, and cleanup fields with fake
function pointers that are never called. It never loads the real ICD and never
executes an ARM artifact.
