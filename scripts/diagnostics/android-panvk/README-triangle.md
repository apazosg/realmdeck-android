# PanVK Gate C: one bounded 64×64 offscreen triangle

This control is the approved, bounded POCO gate from
`scripts/diagnostics/android-panvk/TRIANGLE-CONTRACT.md` (Gate C, the gate
immediately after `TRANSFER_READBACK_OBSERVED`). It reuses the Gate A/B
private loader/instance negotiation, one-device/one-queue lifecycle
byte-faithfully in behaviour, and then performs exactly one bounded
offscreen triangle: image, render pass, framebuffer, two SPIR-V shader
modules, graphics pipeline, draw, copy to a host-visible staging buffer,
one fenced submit and readback. It does not perform surface, swapchain,
presentation, D3D, DXVK, Wine, or WoW work. Device creation can still
initialize opaque driver/kernel context state; this probe does not claim
that it is side-effect free or that the runtime passed.

## Invocation and fixed provenance

The native command accepts exactly:

```text
PanvkTriangle --run-id PVK-<32 lowercase hexadecimal characters>
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
`aarch64-linux-android29`, `libc.so`, and `libdl.so`, with `-Os` (the Gate
A/B `-O2` setting overflows the 64 KiB ELF budget for this larger probe by
608 bytes; `-Os` keeps byte-identical behavior with margin, and the exact
flags are recorded in `manifest.json`). It refuses an existing
artifact directory and never writes the ICD.

## Shaders (compiled on the host, never on the device)

Sources in the repo, GLSL 450, no vertex buffers (positions derived from
`gl_VertexIndex`; constant red fragment output against a green clear):

```text
scripts/diagnostics/android-panvk/shaders/triangle.vert
scripts/diagnostics/android-panvk/shaders/triangle.frag
```

Compiled with the existing NDK toolchain (deterministic flags) and
validated; the device only consumes the embedded byte arrays via the
generated `triangle_spirv.h` include:

```text
glslc -fshader-stage=vertex   --target-env=vulkan1.0 -O -o triangle.vert.spv shaders/triangle.vert
glslc -fshader-stage=fragment --target-env=vulkan1.0 -O -o triangle.frag.spv shaders/triangle.frag
spirv-val --target-env vulkan1.0 triangle.vert.spv
spirv-val --target-env vulkan1.0 triangle.frag.spv
```

Pinned toolchain and outputs (also recorded in `manifest.json` under
`glslc`, `spirvVal` and `spirv`):

```text
glslc     sha256 0d902d250691d4ced0dea33ed3ab6076477093bb1534e6e040d10faa79cb352b
spirv-val sha256 eb6411ded18618ae2a1d41a652c3514c62e153576c7a08ea7f1f2efaf84139b7
triangle.vert.spv 944 bytes sha256 660936e4339d872b8c13d5b32e11bec2fe2cf85711ff4417212fa89356ec2ad2
triangle.frag.spv 304 bytes sha256 c27f9452b2a911ea1f66aa06a4ff721b1641ac7d3b86007fb376bfedd27db7c2
```

## Gate A witnesses (unchanged behaviour)

The private instance path uses allocator NULL, zero layers, zero extensions,
and the measured API request capped at Vulkan 1.3. Physical enumeration has a
capacity of eight and must be complete, contain exactly one handle, and match
vendor `0x13b5`, device `0xa8670000`, and name `Mali-G610 MC6` exactly.

Queue-family enumeration has capacity 16 and records both complete counts. The
selected family is the first eligible family in index order; when a family has
multiple eligible flags, its recorded kind is deterministically preferred as
`transfer`, then `graphics`, then `compute`, but only a family with the
`VK_QUEUE_GRAPHICS_BIT` is eligible for this graphics workload. Thus a family
with unified flags `7` is selected and described as `transfer`, while a
transfer-only family is rejected before device creation. Each candidate must
have `queueCount >= 1`.

The device create witness is exactly one `VkDeviceQueueCreateInfo`, measured
family index, `queueCount=1`, priority `1.0`, `pEnabledFeatures=NULL`, zero
layers, zero extensions, and allocator NULL. The queue witness is
`vkGetDeviceQueue(device, selectedFamily, 0, &queue)` with a non-null queue.

## Triangle sequence (exactly one of each, no extras)

1. `vkCreateImage`: 64×64×1, `VK_FORMAT_R8G8B8A8_UNORM` (37), 2D,
   `VK_IMAGE_TILING_OPTIMAL`, usage `COLOR_ATTACHMENT | TRANSFER_SRC`
   (`0x21`), `VK_SHARING_MODE_EXCLUSIVE`, samples 1, `mipLevels` 1,
   `arrayLayers` 1, `initialLayout UNDEFINED`.
2. `vkGetImageMemoryRequirements`; `vkGetPhysicalDeviceMemoryProperties`
   (queried once and reused for both allocations); select a
   `memoryTypeBits`-allowed type preferring pure `DEVICE_LOCAL`, else any
   `DEVICE_LOCAL` type, else the first compatible type. If none qualifies,
   status `NO_MEMORY_TYPE`, stop, no allocation.
3. `vkAllocateMemory` for the image with `allocationSize` = requirements size
   rounded up to alignment as required (must be `<= 16777216`); if
   requirements size exceeds `16777216`, stop without allocating. Allocator
   NULL.
4. `vkBindImageMemory`.
5. `vkCreateImageView` (2D, `R8G8B8A8_UNORM`, aspect `COLOR`).
6. `vkCreateRenderPass`: 1 color attachment, `loadOp CLEAR`,
   `storeOp STORE`, `initialLayout UNDEFINED`,
   `finalLayout TRANSFER_SRC_OPTIMAL`; 1 graphics subpass with the color
    reference in `COLOR_ATTACHMENT_OPTIMAL`; two explicit dependencies: external
    to subpass 0 for color-attachment writes, then subpass 0 to external for
    color-attachment writes visible to transfer reads.
7. `vkCreateFramebuffer` with the view, 64×64×1.
8. `vkCreateShaderModule` ×2 from the embedded SPIR-V arrays (944-byte
   vertex, 304-byte fragment).
9. `vkCreatePipelineLayout` with no descriptor sets and no push constants.
10. `vkCreateGraphicsPipelines`: vertex+fragment stages (`main`), empty
    vertex input, `TRIANGLE_LIST`, static viewport and scissor 64×64,
    `FILL`, `cullMode NONE`, `frontFace CW`, `lineWidth 1.0`, no
    depth/stencil state, one blend attachment with `blendEnable FALSE` and
    RGBA write mask, samples 1.
11. `vkCreateBuffer`: 16384 bytes (64×64×4), usage
    `VK_BUFFER_USAGE_TRANSFER_DST_BIT`, sharing mode
    `VK_SHARING_MODE_EXCLUSIVE`; `vkGetBufferMemoryRequirements`; select a
    `memoryTypeBits`-allowed type with `HOST_VISIBLE`, preferably
    `HOST_COHERENT` (else `NO_MEMORY_TYPE`); `vkAllocateMemory` (aligned,
    `<= 16777216`, allocator NULL); `vkBindBufferMemory`; `vkMapMemory`
    (offset 0, size 16384, flags 0) requiring non-null. No host write and no
    flush: the staging buffer is only read.
12. `vkCreateCommandPool` (selected family, flags
    `VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT`, allocator NULL);
    `vkAllocateCommandBuffers` (1, level `PRIMARY`);
    `vkBeginCommandBuffer` with flags 0; `vkCmdBeginRenderPass` with one
    clear value green `{0.0, 1.0, 0.0, 1.0}`; `vkCmdBindPipeline`
    (graphics); `vkCmdDraw(3, 1, 0, 0)`; `vkCmdEndRenderPass`;
    `vkCmdCopyImageToBuffer` (whole 64×64×1 image,
    `TRANSFER_SRC_OPTIMAL`, zero buffer/image offsets, tight rows);
    `vkEndCommandBuffer`.
13. `vkCreateFence` (flags 0, allocator NULL); `vkQueueSubmit` with exactly
    one `VkSubmitInfo` (1 command buffer, no wait/signal semaphores, fence);
    `vkWaitForFences` (fence, 1, `VK_TRUE`, timeout `1000000000` ns = 1s).
    If the result is not `VK_SUCCESS` (including `VK_TIMEOUT`), stop with
    `TIMEOUT`/`UNKNOWN`, do not free resources that may still be in flight,
    and do not claim cleanup complete.
14. If the staging type is not host-coherent:
    `vkInvalidateMappedMemoryRanges` (offset 0, size 16384), require
    `VK_SUCCESS`.
15. Read back all 4096 pixels as bytes; a pixel counts as clear when its RGB
    is exactly `(0, 255, 0)` (alpha ignored), otherwise it counts as a
    triangle pixel; `clearPixels + trianglePixels` is always 4096. Compute a
    64-bit FNV-1a hash over the 16384 bytes and emit lowercase hex when
    verified (else empty string). Acceptance requires exactly
    `clearPixels > 0 AND trianglePixels > 0`: a clear-only frame and a
    fully covered frame are both rejected as `UNKNOWN`.
16. Cleanup only after the fence waited successfully, in order:
    `vkDestroyPipeline`, `vkDestroyPipelineLayout`, `vkDestroyShaderModule`
    ×2, `vkDestroyFramebuffer`, `vkDestroyRenderPass`,
    `vkDestroyImageView`, `vkDestroyImage`, `vkUnmapMemory`,
    `vkFreeCommandBuffers`, `vkDestroyBuffer`, `vkFreeMemory` ×2,
    `vkDestroyCommandPool`, `vkDestroyFence`, `vkDestroyDevice`,
    `vkDestroyInstance`, `dlclose`.

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
queue, triangle, timing, cleanup, icdCodeLoaded, constructorsMayHaveExecuted,
vulkanFunctionsInvoked, apiComplete, gpuWorkload, triangleRendered,
presentation, d3d, wow, status, failureStage, failureDetail, nativeExit
```

`schema` is the string `wowforever.android-panvk-triangle`, `version` is
integer `1`, and all other scalar integers are JSON integers, never booleans.
`runId` has the fixed format above. `icdIdentityBefore` and
`icdIdentityAfter` each have exactly `regular` (boolean), `ownerUid`,
`device`, `inode`, `mode`, and `size` (integer) and failed snapshots are
`regular:false` with zero values. No pointer, private structure, credential,
or address is emitted.

The `load`, `exports`, `interface`, `api`, `instance`, `physical`, `queues`,
`selection`, `device`, `queue`, `timing`, and `cleanup` shapes are identical
to `README-transfer.md`:

```text
load = {attempted: bool, loaded: bool, dlerror: string}
exports = array[3] of {name: string, found: bool, owner: string,
                        ownerExact: bool, error: string}
interface = {input: int, result: int, output: int, supported: bool}
functions = array[51] of {name: string, resolved: bool, owner: string,
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
`vk_icdNegotiateLoaderICDInterfaceVersion`. The 51 dynamic function records
are ordered `vkEnumerateInstanceVersion`, `vkCreateInstance`,
`vkDestroyInstance`, `vkEnumeratePhysicalDevices`,
`vkGetPhysicalDeviceProperties`,
`vkGetPhysicalDeviceQueueFamilyProperties`,
`vkGetPhysicalDeviceMemoryProperties`, `vkCreateDevice`, `vkDestroyDevice`,
`vkGetDeviceProcAddr`, `vkGetDeviceQueue`, `vkCreateImage`,
`vkGetImageMemoryRequirements`, `vkAllocateMemory`, `vkFreeMemory`,
`vkBindImageMemory`, `vkCreateImageView`, `vkDestroyImageView`,
`vkDestroyImage`, `vkCreateRenderPass`, `vkDestroyRenderPass`,
`vkCreateFramebuffer`, `vkDestroyFramebuffer`, `vkCreateShaderModule`,
`vkDestroyShaderModule`, `vkCreatePipelineLayout`, `vkDestroyPipelineLayout`,
`vkCreateGraphicsPipelines`, `vkDestroyPipeline`, `vkCreateBuffer`,
`vkDestroyBuffer`, `vkGetBufferMemoryRequirements`, `vkBindBufferMemory`,
`vkMapMemory`, `vkUnmapMemory`, `vkInvalidateMappedMemoryRanges`,
`vkCreateCommandPool`, `vkDestroyCommandPool`, `vkAllocateCommandBuffers`,
`vkFreeCommandBuffers`, `vkBeginCommandBuffer`, `vkEndCommandBuffer`,
`vkCmdBeginRenderPass`, `vkCmdBindPipeline`, `vkCmdDraw`,
`vkCmdEndRenderPass`, `vkCmdCopyImageToBuffer`, `vkCreateFence`,
`vkDestroyFence`, `vkWaitForFences`, `vkQueueSubmit`. Every called PFN must
resolve from the canonical ICD path; a failed owner check is not usable for
a call or cleanup.

The `triangle` key set is exactly:

```text
image = {createAttempted: bool, created: bool, handleReturnedOnError: bool,
         result: int, format: uint, extentWidth: uint, extentHeight: uint,
         usage: uint, tiling: uint, samples: uint, mipLevels: uint,
         arrayLayers: uint, sharingMode: uint}
imageMemory = {requirementsQueried: bool, requirementsSize: uint,
               alignment: uint, memoryTypeBits: uint, typeSelected: bool,
               typeIndex: uint, propertyFlags: uint, deviceLocal: bool,
               allocateAttempted: bool, allocated: bool,
               handleReturnedOnError: bool, allocateResult: int,
               requestedBytes: uint, capacityBytes: uint}
bind = {attempted: bool, ok: bool}
view = {createAttempted: bool, created: bool, handleReturnedOnError: bool,
        result: int}
renderPass = {createAttempted: bool, created: bool,
              handleReturnedOnError: bool, result: int}
framebuffer = {createAttempted: bool, created: bool,
               handleReturnedOnError: bool, result: int}
shaders = {vertBytes: uint, fragBytes: uint, vertCreateAttempted: bool,
           vertCreated: bool, vertResult: int, fragCreateAttempted: bool,
           fragCreated: bool, fragResult: int}
pipelineLayout = {createAttempted: bool, created: bool, result: int}
pipeline = {createAttempted: bool, created: bool,
            handleReturnedOnError: bool, result: int}
staging = {bufferCreateAttempted: bool, bufferCreated: bool,
           handleReturnedOnError: bool, bufferResult: int, bufferSize: uint,
           bufferUsage: uint, memoryQueried: bool, memorySize: uint,
           alignment: uint, memoryTypeBits: uint, memoryTypeSelected: bool,
           typeIndex: uint, hostVisible: bool, hostCoherent: bool,
           allocateAttempted: bool, allocated: bool, allocateResult: int,
           requestedBytes: uint, capacityBytes: uint, bindAttempted: bool,
           bindOk: bool, mapAttempted: bool, mapped: bool, nonNull: bool}
commandPool = {createAttempted: bool, created: bool, result: int,
               flags: uint, queueFamilyIndex: uint}
commandBuffer = {allocateAttempted: bool, allocated: bool, result: int,
                 level: uint}
record = {beginAttempted: bool, begun: bool, beginResult: int,
          drawAttempted: bool, drawOk: bool, drawVertexCount: uint,
          copyAttempted: bool, copyOk: bool, endAttempted: bool, ended: bool,
          endResult: int}
fence = {createAttempted: bool, created: bool, result: int, flags: uint}
submit = {attempted: bool, result: int, submitCount: int,
          waitAttempted: bool, waitResult: int, waited: bool,
          timeoutNs: uint, signaled: bool}
invalidate = {required: bool, attempted: bool, ok: bool, offset: uint,
              size: uint}
readback = {attempted: bool, verified: bool, byteSize: uint,
            pixelCount: uint, clearPixels: uint, trianglePixels: uint,
            hashHex: string}
```

## Gate witnesses and statuses

`TRIANGLE_OFFSCREEN_OBSERVED` (native exit 0) requires all success
witnesses, canonical ICD identity/hash stability before and after, exact PFN
owners, non-null image/view/render-pass/framebuffer/shader/pipeline/buffer
handles, one submit, fence signaled, invalidate ok when the staging memory
is not coherent, `byteSize` 16384, `pixelCount` 4096, `clearPixels > 0`,
`trianglePixels > 0`, `clearPixels + trianglePixels == 4096`, verified true,
and complete owned cleanup. The readback pixel split is workload evidence, not
a universal pixel-count proof; the required synchronization gate is the
explicit render-to-copy dependency above. `VK_ERROR` preserves a signed
negative `VkResult`, including device loss. `NO_MEMORY_TYPE` means no
`memoryTypeBits`-allowed type carried `DEVICE_LOCAL` (image) or
`HOST_VISIBLE` (staging). `TIMEOUT` means the bounded 1s fence wait did not
return `VK_SUCCESS` (for `VK_TIMEOUT` the detail is deterministic; in-flight
resources are left alone and cleanup is not claimed complete).
`LOADER_FAILED` is only a real failed `dlopen` with no returned handle and
complete truthful cleanup. A requirements size above 16 MiB stops without
allocating (`UNKNOWN`). A clear-only or fully covered readback leaves
`verified=false` with the pixel counts and an empty hash (`UNKNOWN`). A
missing eligible queue family also stops as `UNKNOWN` (failure stage
`no_queue`); this probe intentionally has no `NO_QUEUE` status. All other
failures are `UNKNOWN`, nonzero, and preserve the failure detail. Timeout or
host uncertainty is not a native success witness and must remain a stop
condition for the parent runner.

The following are always literal `false`: `apiComplete`, `presentation`,
`d3d`, and `wow`. `gpuWorkload` and `triangleRendered` are true only when
status is `TRIANGLE_OFFSCREEN_OBSERVED`. `vulkanFunctionsInvoked` is true
only when a native call was actually entered. No runner, USB invocation, ICD
execution, download, APK, or phone-side action is part of this bounded
change.

## Boundary

This triangle does not qualify presentation, D3D, DXVK, Wine, or WoW. It is
a device/graphics-pipeline/readback gate over one 64×64 frame, not a
swapchain or a hardware benchmark. Texture-compression and robustness
feature bits remain false (features stay NULL); enabling them is out of
scope. A timeout stops the investigation: no blind free of in-flight
resources, no retry loop, no device reset, no second candidate, and no
alternate queue or layout.
