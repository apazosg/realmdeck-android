# Provision Winlator without touching the UI (Phase 2/5)

> Historical, platform-specific procedure, verified on 2026-09-27. This is not a
> description of the current functional launcher's runtime. See
> [current status](current-status.md) before treating an old result as current evidence.

Technique verified on 2026-09-27 on the POCO M7 Pro 5G with **official Winlator
v11.2**. It allows creating a complete container (including the Wine prefix) **using
ADB only**, without root and without simulating taps. It is the basis for what the
Phase 5 APK would need to do.

## Why it works: the app is *debuggable*

`adb shell run-as com.winlator <cmd>` **works** (the official APK is distributed as debug).
This provides read/write access to the app's private directory:
`/data/data/com.winlator/files/`.

Check:

```bash
adb -s <serial> shell run-as com.winlator id
# uid=10525(u0_a525) ... context=u:r:runas_app:s0
```

## Container structure (read from the `brunodev85/winlator-app` code)

- Rootfs root: `/data/data/com.winlator/files/rootfs/`
- Container home: `rootfs/home/<USER>-<id>/` with `USER = "xuser"` → `xuser-0`, `xuser-1`, …
- Container config: `<home>/xuser-<id>/.container` (JSON).
- Wine prefix: `<home>/xuser-<id>/.wine/drive_c/...`
- "Active" container: symlink `rootfs/home/xuser` → `xuser-<id>`.
- `ContainerManager.loadContainers()` **only scans** `home/*` with the `xuser-`
  prefix and reads its `.container`: creating the directory and JSON is enough for
  the app to list it.

### `.container` keys (all optional except `name`; defaults are applied)

`id`, `name`, `screenSize` (default `1280x720`), `envVars`, `cpuList`, `cpuListWoW64`,
`graphicsDriver`, `dxwrapper`, `dxwrapperConfig`, `graphicsDriverConfig`, `audioDriverConfig`,
`audioDriver` (default `alsa`), `wincomponents`, `drives`, `hudMode`, `startupSelection`
(0 normal / 1 essential / 2 aggressive), `box64Preset`, `desktopTheme`,
`extraData` (includes `appVersion`), `wineVersion` (only if it is NOT the primary version).

- Drivers: Vulkan = `turnip` \| `vortek`; OpenGL = `zink` \| `virgl` \| `gladio`.
  Default `graphicsDriver = "vortek,gladio"` (Vortek is the generic Vulkan layer for
  GPUs without Turnip → **the one needed by PowerVR**). `GraphicsDrivers.toString()` joins with commas.
- DX wrappers: `wined3d` \| `dxvk` \| `vkd3d` \| `cnc-ddraw` \| `d7vk` (default `dxvk`).
- `box64Preset`: `STABILITY` \| `CONSERVATIVE` \| `INTERMEDIATE` \| `PERFORMANCE` (default).
- `drives`: concatenated format `D:<path>E:<path>` with no separator. Defaults:
  `D:` = `/storage/emulated/0/Download`, `E:` = `/data/data/com.winlator/storage`.

Minimal example used:

```json
{"id":0,"name":"WoW-Forever","screenSize":"1280x720","graphicsDriver":"vortek,gladio",
 "dxwrapper":"dxvk","drives":"D:/storage/emulated/0/Download","startupSelection":1,
 "box64Preset":"PERFORMANCE","extraData":{"appVersion":"33"}}
```

## The Wine prefix: extracting `container_pattern.tzst`

Winlator creates the prefix by extracting `assets/container_pattern.tzst` (inside the
APK) into the container directory. This can be done externally:

1. Extract the asset from the APK: `unzip -o -j Winlator_11.2.apk assets/container_pattern.tzst`
2. The phone has **no `zstd`** (and `toybox tar -Z` tries to run an external `zstd` →
   `exec zstd: Permission denied`). Solution: decompress on the host and send the
   already expanded tar over **stdin** (binary-safe; verified with md5):
   ```bash
   zstd -d -c container_pattern.tzst > pattern.tar
   adb -s <serial> shell "run-as com.winlator sh -c 'cd files/rootfs/home/xuser-0 && tar xf -'" < pattern.tar
   ```
    (54 MB prefix: `drive_c/{Program Files, Program Files (x86), ProgramData, users, windows}`)

Note: `run-as` **cannot read `/sdcard`** (different namespace/SELinux); therefore,
the data is sent over stdin to the private directory, not with `adb push` + `run-as tar`.

## What CANNOT be automated (with the device as configured)

- **`adb shell input tap/swipe/text`** → `SecurityException: INJECT_EVENTS`. HyperOS
  blocks it unless **"Depuración USB (Ajustes de seguridad)"** is enabled in Developer
  options (Xiaomi requires a Mi account + SIM + network).
- **Launching the game activity** `com.winlator/.XServerDisplayActivity`: it is **not
  exported**. `am start` from the shell fails (`not exported from uid 10525`), and
  from `run-as` it fails because the declared caller package is `com.android.shell`
  and does not match uid 10525.
  It accepts extras: `container_id` (int), `exec_path` (UNIX path; converted with
  `unixToDOSPath`), `shortcut_path`, `wine_info`, `generate_wineprefix`.
  → Launching the game **requires a human tap** until the project's own APK makes
  this call as its own process (correct uid), which is exactly what Phase 5 was to do.

## Reference commands

```bash
ADB=~/.local/opt/platform-tools/adb; D="-s <serial>"
R="run-as com.winlator"
$ADB $D shell "$R mkdir -p files/rootfs/home/xuser-0"
$ADB $D shell "$R sh -c 'cat > files/rootfs/home/xuser-0/.container'" < container.json
$ADB $D shell "$R sh -c 'cd files/rootfs/home && rm -f xuser && ln -s xuser-0 xuser'"
$ADB $D shell "am force-stop com.winlator"; $ADB $D shell am start -n com.winlator/.MainActivity
$ADB $D exec-out screencap -p > screen.png   # verificación visual
```

## Launching an executable: EXACT shortcut format (critical)

Shortcuts are `.desktop` files in `<prefix>/drive_c/users/xuser/Desktop/` and appear
on the app's *Shortcuts* screen. The app launches them with
`XServerDisplayActivity` + `shortcut_path`.

`Shortcut.java` derives the executable path **from the `Exec` field**, taking
everything **after the last `"wine "`**, and then
`XServerDisplayActivity.getWineStartCommand()` calls `FileUtils.getDirname(execPath)`,
which cuts at the last separator.

- **Correct** (forward slashes + at least one directory, as in Winlator's own `.startmenu`):
  ```
  [Desktop Entry]
  Type=Application
  Name=WoW Beta
  Exec=wine D:/wowforever/client/WowB.exe
  Icon=wine
  [Extra Data]
  container_id=0
  ```
- **Incorrect** and causes an app crash: `Exec=wine "D:\WowB.exe"` ⇒ `getDirname` finds
  no separator (`substring(0, -1)`) ⇒
  `StringIndexOutOfBoundsException` in `FileUtils.getDirname` ← `getWineStartCommand` ← `setupXEnvironment`.
  `D:/WowB.exe` (without a directory) also fails: `D:/` has no subdirectory.

## Rejected approaches (with evidence)

- **Launching Wine via `adb shell run-as`**: infeasible. `run-as` cannot read `/sdcard`
  (SELinux domain `runas_app`), and the rootfs triggers `noexec` ⇒
  `map_image_into_view failed to set protection on ...ntdll.dll` + glibc abort.
- **Wine registry `RunOnce`**: Winlator's `explorer.exe` does not process it
  (verified: it starts without launching the program).
- **`am start` with extras** (`--es exec_path` / `--es shortcut_path`):
  `SecurityException: ... not exported from uid 10525`. Without extras, it can bring
  the task to the foreground.

## `WowB.exe` launch status (verified)

With the corrected shortcut, `WowB.exe` **runs** on the POCO: logcat shows the
process (its own `pid`) executing `ntdll.dll` and creating Wine temporary files,
and its RSS grows to ~500 MB. It then **exits** (~5-40 s) and Winlator closes the
desktop; no `Logs/` directory is created in the game directory.

Why it exits remains to be diagnosed. Hypotheses, in order:
1. insufficient CASC data (`Data/data` = 395 MB out of ~100 GiB total), so the client
   does not finish starting;
2. the game directory is on `/sdcard` (fuse), and the client cannot write to
   `WTF/`, `Cache/`, or `Logs/`;
3. graphics initialization (DXVK on proprietary PowerVR Vulkan).

Recommended isolation experiment: launch `Z:/opt/apps/TestD3D.exe` (the D3D tester
included with Winlator) before blaming the game.
