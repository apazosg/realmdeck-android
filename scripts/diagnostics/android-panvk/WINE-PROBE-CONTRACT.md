# Wine/PanVK fixed profile — writer contract

Approved `.agents/planning/panvk-wine-probe-plan.md`. POCO serial DEVICE_SERIAL,
package com.wowforever.arm64probe, debug48 /0.2.24 proposed same certificate/UID.
Fixed Windows GraphicsProbe-ARM64.exe89.600/SHAa19e6721647fae0f31052133c585c91194823883d6be9bb81c6d0d342abfee9f.
No native WSI rerun, no new probe/C/DLL/driver/wrapper/renderer/runtime/feature bits.

## Kotlin callable and parent integration

`object ForeverPanvkWine` in app.gamenative:

```kotlin
@JvmStatic fun applyManagedEnvironment(context: Context, container: Container,
    env: com.winlator.core.envvars.EnvVars)
```

Called at FINAL Bionic environment merge AFTER existing noGL path helper, BEFORE
ProcessHelper.exec, after RuntimeSetup's baseline managed values. Flag frozen
`ForeverLaunchDebug.PANVK_WINE_PROBE=true`; flag false MUST no-op before assets
and no change to any env/extra. Also require debug, fixed package, graphics-probe
flag/launchKind/executable, managed=true/current RUN ACTIVE/profileoptin true,
strict RUN/source folder verification. No arbitrary input path from intent/env.
Repeated helper calls in the same owned RUN verify/reuse the exact same file.

Only asset `forever/panvk-wsi/libvulkan_panfrost.so` (already packaged), size
31142504/SHA5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9,
AA64 ELF ET_DYN copied streamed bounded to
`context.filesDir/forever/controls/panvk-wine/<RUN>/libvulkan_panfrost.so`.
Private canonical root, exclusive NOFOLLOW files/directories, SHA/size/header
verification before final publish, chmod0600 checked. Unique same-directory temp
owned/fd.force+verify then NOREPLACE publish; unknown existing target untouched.
Path aliases data/data/data-user accepted only within fixed caller root/RUN.

Require own ImageFS/usr/lib directory plus wrapper (same pinned baseline) and
hooks libhook_impl.so and libmain_hook.so present/AA64/header/type/nonempty with
safe canonical containment; no dependency copying or installer. Preserve wrapper
ICD VK_ICD_FILENAMES and all baseline environment entries. Actual three mutations:

```
ADRENOTOOLS_DRIVER_PATH=<verified own RUN directory>/
ADRENOTOOLS_DRIVER_NAME=libvulkan_panfrost.so
ADRENOTOOLS_HOOKS_PATH=<ImageFS own usr/lib>
```

No REDIRECT_DIR, LD_PRELOAD replacement, VK_ICD_FILE direct-PanVK JSON, feature
or API overrides. Driver custom route may fallback System: HOST must detect.
Journal `panvk_wine_selection` via shared run store includes RUN/path/hash/hooks,
expected wrapper hash b598a54166790a6fc071f08495f141f92dc192f1eb339e029bfc7a303c7486ce;
it is declared selection ONLY, not driver mapping/D3D/present acceptance.

Builder exclusive ownership ONLY new ForeverPanvkWine.kt + Test.kt and
ForeverLaunchDebug.kt/ForeverRuntimeSetup.kt (frozen flag + optin extra). Parent
owns recipe/Bionic import/finalhook, code48/version/provenance/audit/deploy and
host maps/evidence; no external GameNative/fork/Gradle/ADB writes by builder.
Use established tests/scoped helper seams where Android Os stat unavailable in
Robolectric; production no unsafe test bypass. No full-suite reruns or binary DL.

## HOST qualification

Same existing UI/one-run/boot-policy supervisor, newversion48 strictallowlist.
Fixed graphics result file parsed under current RUN; no system driver result
counted as PanVK. Own UID/PID/startTicks/root ancestry and canonical PanVK mappings
under EXACT same frozen RUN; mapped file SHA matches pin. Preserve actual
source-loaded modules, effective feature/adapter/DXVK messages. No PanVK mapping
in the Windows-probe process, or ambiguous unrelated nativeWSI mapping =>
CONTROL_INVALID. NativeAndroid WSI success isn't Wine/Xlib evidence.
