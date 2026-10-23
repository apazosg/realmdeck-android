# PanVK Android WSI control

`libpanvk_wsi_probe.so` is a separate, private JNI control for the approved
SurfaceView gate. It owns no launcher, patcher, Wine, D3D, or Vulkan loader
state. The native call is expected to run on the Activity's one worker, not on
the UI thread.

## Fixed JNI ABI

```text
Java_app_gamenative_PanvkWsiBridge_nativeRun(
    Surface surface, String icdPath, String outputPath, String runId,
    long generation): String
Java_app_gamenative_PanvkWsiBridge_nativeCancel(long generation): void
```

`outputPath` is the full `.../<RUN>/native.jsonl` path, not a directory or an
arbitrary output file. Native derives the directory only from the exact
canonical ICD parent and requires this full path to match. The only accepted
shape is the canonical private directory
`files/forever/controls/panvk-wsi/PWSI-<32 lowercase hex>` (either Android
`/data/user/0` or `/data/data` spelling), and `icdPath` must be its exact
`libvulkan_panfrost.so` child. The process must be UID 10330 and
`com.wowforever.arm64probe:panvk_wsi`. Native checks
the directory and ICD with `lstat`, `O_NOFOLLOW`, owner/size checks, a 64 KiB
streaming SHA-256 pass, and canonical-path equality before `dlopen`.

The native side appends only `native.jsonl`; Kotlin owns `result.json`.
`native.jsonl` is at most 256 rows and 64 KiB, with at most 96 lifecycle call
witnesses and 64 PFN records; each sensitive call
has a fsynced `before` and `after` record. The returned object is bounded to
64 KiB and includes the actual signed `VkResult`
and `callStage`, PID, generation, ICD owner, counts, cancellation,
`unknownResourceRetained`, and cleanup evidence. Strings are encoded by the
native JSON encoder; Java strings are never used as paths without the scope
check.

Cancellation is an atomic monotonic generation watermark. A cancellation
received before `nativeRun` remains effective; Run never resets it. There is
one worker/run per process. Cancellation stops new acquire/submit work, but
does not destroy a UI surface or GPU object from the cancel callback. A queue
idle failure leaves in-flight resources and the window retained and reports
`UNKNOWN`; it is never converted into a successful cleanup claim.

## Vulkan control

The window reference comes only from `ANativeWindow_fromSurface`; its current
size must be exactly 320×240. The private beta.17 ICD is loaded only after
the pinned identity check. GIPA/GDPA resolve every function and `dladdr`
requires the canonical ICD as owner. No `libvulkan.so`, direct Vulkan import,
fallback driver, or arbitrary public CLI exists.

The control requires both `VK_KHR_surface` and `VK_KHR_android_surface`, with
instance extension enumeration capped at 256. It accepts only the exact
Mali-G610 (`0x13b5`, `0xa8670000`), a graphics queue with surface support,
`VK_KHR_swapchain`, R8G8B8A8_UNORM, FIFO, a 320×240 current extent, and one
queue at priority 1.0 with `pEnabledFeatures == NULL`. Format and mode counts
are capped at 32 and 16; swapchain images are bounded to four, preferring
two.

The embedded validated Gate-C SPIR-V is unchanged: vertex 944 bytes and
fragment 304 bytes. Each of at most three frames uses fresh acquire/present
semaphores and a fence (no pending semaphore reuse), one submit, a 500 ms
acquire timeout, and a 1 s fence wait. The render pass clears green, draws
the embedded red triangle, and has explicit attachment-to-present dependency
metadata. A single final `vkQueueWaitIdle` proves retirement before normal
destruction. The native code does not use Canvas, CPU drawing, screenshots,
or readback to claim presentation.

Only three exact `VK_SUCCESS` acquire/submit/present/fence frames make
`apiPresentSuccess` true; it is not a visual observation. Native always reports
`androidPresentationObserved`, `wine`, `d3d`, and `wow` as `false`.

## Offline host seam and build

The host seam compiles the same C file with fake window dimensions, fake
policy/PFN bounds, generation cancellation, and the real bounded JSON encoder.
It never loads the ICD or calls an Android/Vulkan implementation. Run:

```bash
uv run pytest tests/test_android_panvk_wsi_native.py -q
scripts/diagnostics/android-panvk/build_wsi.sh
```

The build is offline and refuses an existing artifact directory. It uses only
the existing NDK 24 compiler (`aarch64-linux-android29`), the pinned Vulkan
header, the existing Gate-C SPIR-V header, `libandroid.so`, `libdl.so`, and
`libc.so`. The resulting AArch64 shared object is copied to:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-wsi-v5/libpanvk_wsi_probe.so
```

The manifest records source/header/shader/compiler/ICD pins, exact JNI
exports and ELF dependencies. It does not perform ADB, device, Gradle,
runtime, driver, download, or ICD modification work. `API_PRESENT_COMPLETED`
is an API/lifecycle result only; it is not phone qualification or acceptance
of Wine/DXVK/WoW.
