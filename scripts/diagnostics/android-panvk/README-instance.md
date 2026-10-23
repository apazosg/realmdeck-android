# PanVK instance/physical-device metadata control

This is the approved, bounded POCO control for one private ICD observation. It
does **not** create a logical device, queue, allocation, command buffer,
shader, fence, submission, surface, swapchain, EGL, Wine, DXVK, D3D, WoW, or
workload. `PHYSICAL_METADATA_OBSERVED` is an observation classification, not a
compatibility or driver-ready claim. The native command accepts exactly
`--run-id PVK-<32 lowercase hexadecimal characters>`. The HOST command accepts
only `--output <existing external evidence parent>`.

## Fixed build and provenance

- Target: AArch64 `aarch64-linux-android29`, NDK `24.0.8215888`, strict
  `-Wall -Wextra -Werror -fPIE -pie`, only `libc.so` and `libdl.so`.
- Header: `/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h`, SHA-256
  `c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71`.
- Helper source is the unchanged `PanvkPrivateLoader.c`, SHA-256
  `c15a849033826a881f1da424de1f2560a5dafabb13da298f1ce380e3639af29a`.
- ICD: 30,972,472 bytes, mode `0600`, SHA-256
  `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
- Final native artifact: sibling `panvk-instance-metadata-v4/PanvkInstanceMetadata`,
  mode `0755`; size and SHA-256 are frozen in the generated manifest and HOST runner.
  The generated manifest SHA is frozen by `run_instance_usb.py`; it is not
  copied into this source file because the manifest also hashes this README.

## Native JSON schema `wowforever.android-panvk-instance-metadata`, version 1

The native output is exactly one UTF-8 JSON object followed by one newline and
is strictly less than 64 KiB. The top-level key set is exactly:

`schema`, `version`, `runId`, `pid`, `uid`, `pgid`, `sid`,
`monotonicStartNs`, `monotonicEndNs`, `cwd`, `scopeValid`, `icdPath`,
`icdBytes`, `icdSha256`, `icdIdentityBefore`, `icdIdentityAfter`,
`icdIdentityStable`, `load`, `exports`, `interface`, `functions`, `api`,
`instance`, `physicalDevices`, `cleanup`, `icdCodeLoaded`,
`constructorsMayHaveExecuted`, `vulkanFunctionsInvoked`, `apiComplete`,
`gpuWorkload`, `triangle`, `presentation`, `d3d`, `wow`, `status`,
`failureStage`, `failureDetail`, `nativeExit`.

Integers are JSON integers (never booleans); flags are JSON booleans; bounded
text fields are strings. `pid`, `uid`, `pgid`, `sid`, both monotonic times are
positive. `runId` is the fixed run-id format, `uid` is 10330, and `version` is
1. The identity object has exactly `regular`, `ownerUid`, `device`, `inode`,
`mode`, `size`; a valid ICD snapshot has UID 10330, mode 0600, the pinned size,
and positive device/inode. A failed snapshot is all-zero with `regular:false`.

`load` is exactly `{attempted,loaded,dlerror}`. `exports` is exactly three
objects, in this order:
`vk_icdGetInstanceProcAddr`, `vk_icdGetPhysicalDeviceProcAddr`,
`vk_icdNegotiateLoaderICDInterfaceVersion`. Each has exactly
`name,found,owner,ownerExact,error`; an owner is exact only for `icdPath`.
`functions` is exactly eight objects with the same field set and this order:
`vkCreateInstance`, `vkDestroyInstance`, `vkEnumeratePhysicalDevices`,
`vkGetPhysicalDeviceProperties`, `vkGetPhysicalDeviceFeatures`,
`vkEnumerateDeviceExtensionProperties`, `vkGetPhysicalDeviceFeatures2`,
`vkEnumerateInstanceVersion`. A PFN is callable only when it is resolved and
its `ownerExact` is true.

`interface` is exactly `{input,result,output,supported}`. `input` is 7 and
`supported` means result `VK_SUCCESS` and output in 2..7. `api` is exactly
`{enumerateInstanceVersionFound,ownerExact,legacy10,result,reported,requested}`.
When the optional version PFN is absent, `legacy10` is true and both versions
are explicitly Vulkan 1.0. Otherwise its owner, result, variant, and version
are measured; `requested` is `min(reported, 1.3)`.

`instance` is exactly
`{createAttempted,created,result,layerCount,extensionCount,allocatorNull}`.
Layer and extension counts are always zero and allocatorNull is always true.
`physicalDevices` is exactly
`{countQueryResult,fillResult,reportedCount,returnedCount,retainedCount,capacity,complete,devices}`.
`capacity` is 8; there are never more than 8 retained handles. Count overflow,
`VK_INCOMPLETE`, mismatched counts, a null handle, invalid strings, or an
invalid `VkBool32` produce `UNKNOWN` with the bounded prefix actually
retained; they are never silently truncated. A negative Vulkan result does not
authorize reading or querying output handles.

Each device is exactly
`{metadataStatus,vendorId,deviceId,apiVersion,driverVersion,deviceName,propertiesValid,extensionsValid,extensionCount,extensionReportedCount,extensionReturnedCount,extensionRetainedCount,extensionCapacity,extensionQueryResult,extensionFillResult,extensionsComplete,extensions,features,features2,error}`.
`extensionCapacity` is 256. `extensions` has exactly `extensionCount`
retained strings, each at most 255 UTF-8 bytes; reported and returned counts
remain separate when a bounded prefix is retained. Extension overflow and
`VK_INCOMPLETE` never pretend that absent features were queried. The `features`
object has exactly the following keys; `deviceName` is a bounded string or
`null` when its termination is invalid.
`fillModeNonSolid,shaderClipDistance,textureCompressionBC,geometryShader,
tessellationShader,sampleRateShading,samplerAnisotropy,robustBufferAccess`.
`features2` has exactly `queried,core,vulkan11ShaderDrawParameters,vulkan12,
vulkan13,transformFeedback,robustness2,valid`; its nested objects are exactly:

- `vulkan12`: `descriptorIndexing`, `bufferDeviceAddress`;
- `vulkan13`: `shaderDemoteToHelperInvocation`, `synchronization2`,
  `dynamicRendering`;
- `transformFeedback`: `transformFeedback`, `geometryStreams`;
- `robustness2`: `robustBufferAccess2`, `nullDescriptor`.

Feature witnesses are `boolean` or `null`; null means not observed or not
legally queryable. A non-zero/non-one Vulkan `VkBool32` invalidates metadata;
it is never converted by truthiness. The device API variant must be zero,
major one, and at least Vulkan 1.0 before any feature-chain decision. Vulkan
1.1/1.2/1.3 chains are included only when legal for the measured
instance/device API, and transform feedback or robustness2 chains only when
their device extensions were observed. Vulkan 1.1 uses the standalone shader
draw parameters structure; aggregate Vulkan11 starts at 1.2. Eligibility uses
the minimum of instance/device API. Successful extension metadata requires both
results VK_SUCCESS and equal reported/returned/retained counts. Partial arrays
remain UNKNOWN and unqueryable extension features remain null.
Output-budget overflow retains the largest whole-device prefix that fits,
preserves raw counts, sets enumeration incomplete and returns UNKNOWN; it never
claims zero devices or leaves retained counts inconsistent with the encoded array.
UNKNOWN results trigger bounded cleanup of the exact qualification package.

`cleanup` is exactly
`{destroyAttempted,destroyOk,dlcloseAttempted,dlcloseOk,complete}`. A created
instance is destroyed before the ICD handle is closed on every successful or
failed query path. Missing owner/function means it is not called; inability to
destroy leaves cleanup incomplete and cannot pass.

`status` is exactly one of `PHYSICAL_METADATA_OBSERVED`, `VK_ERROR`,
`ZERO_DEVICES`, `LOADER_FAILED`, `UNKNOWN`; only the first exits 0. The
cross-field witnesses required for the first status are pinned before/after
identity equality, valid scope and times, interface result `VK_SUCCESS` with
output 2..7, successful instance creation with a non-null instance, successful
API query or explicit legacy 1.0, `requested=min(reported,1.3)` with patch 0,
complete counts, all required PFN owners exact, every required core feature and
legal Features2 witness valid, non-empty observed device metadata, complete
destroy/dlclose, `failureStage:"none"`, and `failureDetail:""`. Signed
negative Vulkan results remain structured errors, not unsigned schema values.
`apiComplete`, `gpuWorkload`, `triangle`, `presentation`, `d3d`, and `wow` are
always false. `vulkanFunctionsInvoked` records actual instance/physical
metadata calls; it is not the old loader-only false bit.

## Host runner safety

The runner validates source, header, helper, manifest, ELF, ICD, size, mode,
and SHA pins before ADB. It creates an exclusive 0700 private run and a 0755
public staging directory; the probe is 0700 and ICD 0600. The capture file is
exclusive and fsynced before `Popen`; stdin is `DEVNULL`, both pipes drain
continuously with 64 KiB bounds, and the child deadline is 10 seconds. A held
HOST ADB handle is terminated/reaped within bounded cleanup; only the exact
package is force-stopped on runner error, timeout, or unknown/remaining owned
process evidence. No guessed PID, process group, root, environment, artifact
path, or native flag is accepted.
