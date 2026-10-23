# PanVK control B: one bounded 4 KiB transfer

This control is the approved, bounded POCO gate from
`.agents/planning/panvk-device-transfer-plan.md` (Gate B, lines 38-65). It
reuses the Gate A private loader/instance negotiation, one-device/one-queue
lifecycle byte-faithfully in behaviour, and then performs exactly one bounded
4 KiB transfer with fence and readback. It does not perform shader, SPIR-V,
compiler, image, renderpass, framebuffer, surface, swapchain, presentation,
rendering, D3D, DXVK, Wine, or WoW work. Device creation can still initialize
opaque driver/kernel context state; this probe does not claim that it is
side-effect free or that the runtime passed.

## Invocation and fixed provenance

The native command accepts exactly:

```text
PanvkTransfer --run-id PVK-<32 lowercase hexadecimal characters>
```

The immutable helper is included without modification:

```text
PanvkPrivateLoader.c
sha256 cc8ea0c352ff582de80ef493d6060504a5bd1b11057604a1d55dd59875b3a4b7
```

The Vulkan header is the pinned header at
`/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h`
with SHA-256
`c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71`.
The ICD is the existing regular mode-0600 AArch64 file
`libvulkan_panfrost.so`, 31,142,504 bytes, SHA-256
`5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`.
The build uses only NDK `24.0.8215888`, target
`aarch64-linux-android29`, `libc.so`, and `libdl.so`. It refuses an existing
artifact directory and never writes the ICD.

## Gate A witnesses (unchanged behaviour)

The private instance path uses allocator NULL, zero layers, zero extensions,
and the measured API request capped at Vulkan 1.3. Physical enumeration has a
capacity of eight and must be complete, contain exactly one handle, and match
vendor `0x13b5`, device `0xa8670000`, and name `Mali-G610 MC6` exactly.

Queue-family enumeration has capacity 16 and records both complete counts. The
selected family is the first eligible family in index order; when a family has
multiple eligible flags, its recorded kind is deterministically preferred as
`transfer`, then `graphics`, then `compute`. Each candidate must have
`queueCount >= 1`.

The device create witness is exactly one `VkDeviceQueueCreateInfo`, measured
family index, `queueCount=1`, priority `1.0`, `pEnabledFeatures=NULL`, zero
layers, zero extensions, and allocator NULL. The queue witness is
`vkGetDeviceQueue(device, selectedFamily, 0, &queue)` with a non-null queue.

## Transfer sequence (exactly one of each, no extras)

1. `vkCreateBuffer`: size 4096, usage `VK_BUFFER_USAGE_TRANSFER_DST_BIT`,
   sharing mode `VK_SHARING_MODE_EXCLUSIVE`.
2. `vkGetBufferMemoryRequirements`.
3. `vkGetPhysicalDeviceMemoryProperties`; select a `memoryTypeBits`-allowed
   type with `HOST_VISIBLE`, preferably `HOST_COHERENT`. If none qualifies,
   status `NO_MEMORY_TYPE`, stop, no allocation.
4. `vkAllocateMemory` with `allocationSize` = requirements size rounded up to
   alignment as required (must be `<= 262144`); if requirements size exceeds
   `262144`, stop without allocating. Allocator NULL.
5. `vkBindBufferMemory`.
6. `vkMapMemory` (offset 0, size 4096, flags 0); require non-null. Host write:
   initialize the 4096 mapped bytes to zero; if the chosen type is not
   host-coherent, `vkFlushMappedMemoryRanges` (offset 0, size 4096) and require
   `VK_SUCCESS` (record `flushOk`); if coherent, `flushAttempted=false`.
7. `vkCreateCommandPool` (selected family, flags
   `VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT`, allocator NULL).
8. `vkAllocateCommandBuffers` (1, level `PRIMARY`).
9. `vkBeginCommandBuffer` with flags 0 (no `ONE_TIME_SUBMIT`).
10. `vkCmdFillBuffer` (dstOffset 0, size 4096, data `0xa1b2c3d4`).
11. `vkCmdPipelineBarrier`: one memory barrier, srcStage `TRANSFER`, dstStage
    `HOST`, srcAccess `TRANSFER_WRITE`, dstAccess `HOST_READ`, no queue-family
    ownership transfer.
12. `vkEndCommandBuffer`.
13. `vkCreateFence` (flags 0, allocator NULL).
14. `vkQueueSubmit` with exactly one `VkSubmitInfo` (1 command buffer, no
    wait/signal semaphores, fence).
15. `vkWaitForFences` (fence, 1, `UINT64_MAX`, timeout `1000000000` ns = 1s).
    If the result is not `VK_SUCCESS` (including `VK_TIMEOUT`), stop with
    `TIMEOUT`/`UNKNOWN`, do not free resources that may still be in flight,
    and do not claim cleanup complete.
16. If not host-coherent: `vkInvalidateMappedMemoryRanges` (offset 0, size
    4096), require `VK_SUCCESS`.
17. Read back all 1024 uint32 from the mapped pointer; require every value
    equal to `0xa1b2c3d4`; count mismatches and the first mismatch index;
    compute a 64-bit FNV-1a hash over the 4096 bytes and emit lowercase hex.
    `verified = (mismatches == 0)`.
18. Cleanup only after the fence waited successfully: `vkUnmapMemory`,
    `vkFreeCommandBuffers`, `vkDestroyCommandPool`, `vkDestroyFence`,
    `vkDestroyBuffer`, `vkFreeMemory`, `vkDestroyDevice`, `vkDestroyInstance`,
    `dlclose`.

Only `dlopen`/`dlsym`/`dladdr`/`dlclose` plus the ICD's own Vulkan
entrypoints are used. There is no queue idle wait, no device reset, no GPU
retry loop, no additional queue or layout fallback, no second candidate, no
infinite wait, and no enabled `pEnabledFeatures` (stays NULL).

## Native JSON schema

Native stdout is exactly one UTF-8 JSON object and one newline, strictly less
than 64 KiB. The top-level key set is exactly:

```text
schema, version, runId, pid, uid, pgid, sid, monotonicStartNs,
monotonicEndNs, cwd, scopeValid, icdPath, icdBytes, icdSha256,
icdIdentityBefore, icdIdentityAfter, icdIdentityStable, load, exports,
interface, functions, api, instance, physical, queues, selection, device,
queue, transfer, timing, cleanup, icdCodeLoaded, constructorsMayHaveExecuted,
vulkanFunctionsInvoked, apiComplete, gpuWorkload, triangle, presentation,
d3d, wow, status, failureStage, failureDetail, nativeExit
```

`schema` is the string `wowforever.android-panvk-transfer`, `version` is
integer `1`, and all other scalar integers are JSON integers, never booleans.
`runId` has the fixed format above. `icdIdentityBefore` and
`icdIdentityAfter` each have exactly `regular` (boolean), `ownerUid`,
`device`, `inode`, `mode`, and `size` (integer) and failed snapshots are
`regular:false` with zero values. No pointer, private structure, credential,
or address is emitted.

The `load`, `exports`, `interface`, `api`, `instance`, `physical`, `queues`,
`selection`, `device`, `queue`, `timing`, and `cleanup` shapes are identical
to `README-device.md`:

```text
load = {attempted: bool, loaded: bool, dlerror: string}
exports = array[3] of {name: string, found: bool, owner: string,
                        ownerExact: bool, error: string}
interface = {input: int, result: int, output: int, supported: bool}
functions = array[33] of {name: string, resolved: bool, owner: string,
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
`vk_icdNegotiateLoaderICDInterfaceVersion`. The 33 dynamic function records
are ordered `vkEnumerateInstanceVersion`, `vkCreateInstance`,
`vkDestroyInstance`, `vkEnumeratePhysicalDevices`,
`vkGetPhysicalDeviceProperties`,
`vkGetPhysicalDeviceQueueFamilyProperties`,
`vkGetPhysicalDeviceMemoryProperties`, `vkCreateDevice`, `vkDestroyDevice`,
`vkGetDeviceProcAddr`, `vkGetDeviceQueue`, `vkCreateBuffer`,
`vkDestroyBuffer`, `vkGetBufferMemoryRequirements`, `vkAllocateMemory`,
`vkFreeMemory`, `vkBindBufferMemory`, `vkMapMemory`, `vkUnmapMemory`,
`vkFlushMappedMemoryRanges`, `vkInvalidateMappedMemoryRanges`,
`vkCreateCommandPool`, `vkDestroyCommandPool`, `vkAllocateCommandBuffers`,
`vkFreeCommandBuffers`, `vkBeginCommandBuffer`, `vkEndCommandBuffer`,
`vkCmdFillBuffer`, `vkCmdPipelineBarrier`, `vkCreateFence`, `vkDestroyFence`,
`vkWaitForFences`, `vkQueueSubmit`. Every called PFN must resolve from the
canonical ICD path; a failed owner check is not usable for a call or cleanup.

The `transfer` key set is exactly:

```text
buffer = {createAttempted: bool, created: bool, handleReturnedOnError: bool,
          result: int, size: uint, usage: uint, sharingMode: uint}
memoryRequirements = {queried: bool, size: uint, alignment: uint,
                      memoryTypeBits: uint}
memoryType = {selected: bool, typeIndex: uint, propertyFlags: uint,
              hostVisible: bool, hostCoherent: bool, deviceLocal: bool}
allocation = {attempted: bool, allocated: bool, handleReturnedOnError: bool,
              result: int, requestedBytes: uint, capacityBytes: uint}
bind = {attempted: bool, ok: bool}
map = {attempted: bool, mapped: bool, nonNull: bool}
hostWrite = {performed: bool, bytes: uint, coherent: bool,
             flushAttempted: bool, flushOk: bool, flushOffset: uint,
             flushSize: uint}
commandPool = {createAttempted: bool, created: bool, result: int,
               flags: uint, queueFamilyIndex: uint}
commandBuffer = {allocateAttempted: bool, allocated: bool, result: int,
                 level: uint}
record = {beginAttempted: bool, begun: bool, beginResult: int,
          fillAttempted: bool, fillOk: bool, fillOffset: uint,
          fillSize: uint, fillData: uint, barrierAttempted: bool,
          barrierOk: bool, endAttempted: bool, ended: bool, endResult: int}
fence = {createAttempted: bool, created: bool, result: int, flags: uint}
submit = {attempted: bool, result: int, waitAttempted: bool, waitResult: int,
          waited: bool, timeoutNs: uint, signaled: bool}
invalidate = {required: bool, attempted: bool, ok: bool, offset: uint,
              size: uint}
readback = {attempted: bool, verified: bool, dwordCount: uint,
            mismatches: uint, firstMismatchIndex: int, pattern: uint,
            hashHex: string}
```

## Gate witnesses and statuses

`TRANSFER_READBACK_OBSERVED` (native exit 0) requires all success witnesses,
canonical ICD identity/hash stability before and after, exact PFN owners,
non-null device/queue/buffer/memory/mapped handles, one submit, fence
signaled, invalidate ok when required, 1024/1024 matching dwords, verified
true, and complete owned cleanup. `VK_ERROR` preserves a signed negative
`VkResult`, including device loss. `NO_QUEUE` means complete queue-family
data had no eligible non-zero family. `NO_MEMORY_TYPE` means no
`memoryTypeBits`-allowed type carried `HOST_VISIBLE`. `TIMEOUT` means the
bounded 1s fence wait did not return `VK_SUCCESS` (for `VK_TIMEOUT` the
detail is deterministic; in-flight resources are left alone and cleanup is
not claimed complete). `LOADER_FAILED` is only a real failed `dlopen` with
no returned handle and complete truthful cleanup. A requirements size above
256 KiB stops without allocating (`UNKNOWN`). A readback mismatch leaves
`verified=false` with the mismatch count, first index, and hash
(`UNKNOWN`). All other failures are `UNKNOWN`, nonzero, and preserve the
failure detail. Timeout or host uncertainty is not a native success witness
and must remain a stop condition for the parent runner.

The following are always literal `false`: `apiComplete`, `triangle`,
`presentation`, `d3d`, and `wow`. `gpuWorkload` is true only when status is
`TRANSFER_READBACK_OBSERVED`. `vulkanFunctionsInvoked` is true only when a
native call was actually entered. No runner, USB invocation, ICD execution,
download, APK, or phone-side action is part of this bounded change.

## Boundary

This transfer does not qualify rendering, presentation, D3D, DXVK, Wine, or
WoW. It is a device/submission/readback gate over one buffer, not a triangle
or a hardware benchmark. Texture-compression and robustness feature bits
remain false (features stay NULL); enabling them is out of scope. A timeout
stops the investigation: no blind free of in-flight resources, no retry loop,
no device reset, no second candidate, and no alternate queue or layout.
