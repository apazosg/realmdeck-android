# PanVK control A: one logical device and one queue

This control is the approved, bounded POCO gate from
`.agents/planning/panvk-device-transfer-plan.md`. It uses the existing private
loader/instance negotiation and then creates exactly one logical device with
one queue. It does not perform a workload, allocation, synchronization,
presentation, rendering, D3D, DXVK, Wine, or WoW operation. Device creation can
still initialize opaque driver/kernel context state; this probe does not claim
that it is side-effect free or that the runtime passed.

## Invocation and fixed provenance

The native command accepts exactly:

```text
PanvkDeviceQueue --run-id PVK-<32 lowercase hexadecimal characters>
```

The immutable helper is included without modification:

```text
PanvkPrivateLoader.c
sha256 c15a849033826a881f1da424de1f2560a5dafabb13da298f1ce380e3639af29a
```

The Vulkan header is the pinned header at
`/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h`
with SHA-256
`c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71`.
The ICD is the existing regular mode-0600 AArch64 file
`libvulkan_panfrost.so`, 30,972,472 bytes, SHA-256
`c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
The build uses only NDK `24.0.8215888`, target
`aarch64-linux-android29`, `libc.so`, and `libdl.so`. It refuses an existing
artifact directory and never writes the ICD.

## Native JSON schema

Native stdout is exactly one UTF-8 JSON object and one newline, strictly less
than 64 KiB. The top-level key set is exactly:

```text
schema, version, runId, pid, uid, pgid, sid, monotonicStartNs,
monotonicEndNs, cwd, scopeValid, icdPath, icdBytes, icdSha256,
icdIdentityBefore, icdIdentityAfter, icdIdentityStable, load, exports,
interface, functions, api, instance, physical, queues, selection, device,
queue, timing, cleanup, icdCodeLoaded, constructorsMayHaveExecuted,
vulkanFunctionsInvoked, apiComplete, gpuWorkload, triangle, presentation,
d3d, wow, status, failureStage, failureDetail, nativeExit
```

`schema` is the string
`wowforever.android-panvk-device-queue`, `version` is integer `1`, and all
other scalar integers are JSON integers, never booleans. `runId` has the fixed
format above. `icdIdentityBefore` and `icdIdentityAfter` each have exactly
`regular` (boolean), `ownerUid`, `device`, `inode`, `mode`, and `size` (integer)
and failed snapshots are `regular:false` with zero values. No pointer, private
structure, credential, or address is emitted.

Nested keysets and types are fixed:

```text
load = {attempted: bool, loaded: bool, dlerror: string}
exports = array[3] of {name: string, found: bool, owner: string,
                        ownerExact: bool, error: string}
interface = {input: int, result: int, output: int, supported: bool}
functions = array[10] of {name: string, resolved: bool, owner: string,
                          ownerExact: bool, error: string}
api = {enumerateInstanceVersionFound: bool, ownerExact: bool,
       legacy10: bool, result: int, reported: uint, requested: uint}
instance = {createAttempted: bool, created: bool,
            handleReturnedOnError: bool, result: int, layerCount: int,
            extensionCount: int, allocatorNull: bool}
physical = {reportedCount: uint, returnedCount: uint, retainedCount: uint,
            capacity: uint, countResult: int, fillResult: int, complete: bool,
            vendorId: uint, deviceId: uint, name: string,
            propertiesValid: bool, targetExact: bool}
queues = {reportedCount: uint, returnedCount: uint, retainedCount: uint,
          capacity: uint,
          complete: bool, families: array of
          {index: uint, flags: uint, queueCount: uint}}
selection = {selected: bool, familyIndex: uint, flags: uint,
             queueCount: uint, kind: string}
device = {createAttempted: bool, created: bool,
          handleReturnedOnError: bool, result: int, queueCreateCount: int,
          queueFamilyIndex: uint, queueCount: uint, priority: number,
          featuresNull: bool, layerCount: int, extensionCount: int,
          allocatorNull: bool}
queue = {getAttempted: bool, index: uint, nonNull: bool, observed: bool}
timing = {callCount: uint, monotonicChecks: uint, valid: bool}
cleanup = {deviceDestroyAttempted: bool, deviceDestroyOk: bool,
           instanceDestroyAttempted: bool, instanceDestroyOk: bool,
           dlcloseAttempted: bool, dlcloseOk: bool, complete: bool}
```

The three exports are ordered
`vk_icdGetInstanceProcAddr`, `vk_icdGetPhysicalDeviceProcAddr`, and
`vk_icdNegotiateLoaderICDInterfaceVersion`. The ten dynamic function records
are ordered `vkEnumerateInstanceVersion`, `vkCreateInstance`, `vkDestroyInstance`,
`vkEnumeratePhysicalDevices`, `vkGetPhysicalDeviceProperties`,
`vkGetPhysicalDeviceQueueFamilyProperties`, `vkCreateDevice`,
`vkDestroyDevice`, `vkGetDeviceProcAddr`, and `vkGetDeviceQueue`.
Every called PFN must resolve from the canonical ICD path; a failed owner check
is not usable for a call or cleanup.

## Gate witnesses and statuses

The private instance path uses allocator NULL, zero layers, zero extensions,
and the measured API request capped at Vulkan 1.3. Physical enumeration has a
capacity of eight and must be complete, contain exactly one handle, and match
vendor `0x13b5`, device `0xa8670000`, and name `Mali-G610 MC6` exactly.

Queue-family enumeration has capacity 16 and records both complete counts. The
selected family is the first eligible family in index order; when a family has
multiple eligible flags, its recorded kind is deterministically preferred as
`transfer`, then `graphics`, then `compute`. Each candidate must have
`queueCount >= 1`. `returnedCount` is the raw driver count and
`retainedCount` is the bounded prefix emitted in `families`; the latter never
exceeds 16, including on an incomplete/oversized fill. This metadata does not
advertise workload support.

The device create witness is exactly one `VkDeviceQueueCreateInfo`, measured
family index, `queueCount=1`, priority `1.0`, `pEnabledFeatures=NULL`, zero
layers, zero extensions, and allocator NULL. The queue witness is
`vkGetDeviceQueue(device, selectedFamily, 0, &queue)` with a non-null queue.
The device is destroyed before the instance and the ICD handle is closed only
after all valid owned handles are cleaned up. Void-returning calls are recorded
as attempted/complete observations, not as fabricated `VkResult` values. The
device-destroy PFN prevalidated through the instance owner is retained as the
cleanup fallback when post-create GDPA resolution is absent or mismatched;
cleanup is still attempted after diagnostic clock, owner, queue, or timing
failures when the device handle is valid.

`DEVICE_QUEUE_OBSERVED` (native exit 0) requires all success witnesses,
canonical ICD identity/hash stability, exact PFN owners, complete queue counts,
valid monotonic checks around native Vulkan calls, non-null device/queue,
successful owned cleanup, and no failure recorded. `VK_ERROR` preserves a
signed negative `VkResult`, including device loss. `NO_QUEUE` means complete
queue-family data had no eligible non-zero family. `LOADER_FAILED` is only a
real failed `dlopen` with no returned handle and complete truthful cleanup.
All other failures are `UNKNOWN`, nonzero, and preserve the failure detail.
Timeout or host uncertainty is not a native success witness and must remain a
stop condition for the parent runner.

The following are always literal `false`: `apiComplete`, `gpuWorkload`,
`triangle`, `presentation`, `d3d`, and `wow`. `vulkanFunctionsInvoked` is true
only when a native call was actually entered. No runner, USB invocation, ICD
execution, download, APK, or phone-side action is part of this bounded change.
