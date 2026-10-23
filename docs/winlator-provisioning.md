# Provisionar Winlator sin tocar la UI (Fase 2/5)

Técnica verificada el 2026-09-27 en el POCO M7 Pro 5G con **Winlator oficial v11.2**.
Permite crear un contenedor completo (incluido el Wine prefix) **solo con ADB**, sin root
y sin simular toques. Es la base de lo que tendrá que hacer el APK de la Fase 5.

## Por qué funciona: la app es *debuggable*

`adb shell run-as com.winlator <cmd>` **funciona** (el APK oficial se distribuye debug).
Eso da acceso de lectura/escritura al directorio privado de la app:
`/data/data/com.winlator/files/`.

Comprobación:

```bash
adb -s <serial> shell run-as com.winlator id
# uid=10525(u0_a525) ... context=u:r:runas_app:s0
```

## Estructura de un contenedor (leída del código de `brunodev85/winlator-app`)

- Raíz del rootfs: `/data/data/com.winlator/files/rootfs/`
- Home de contenedores: `rootfs/home/<USER>-<id>/` con `USER = "xuser"` → `xuser-0`, `xuser-1`, …
- Config del contenedor: `<home>/xuser-<id>/.container` (JSON).
- Wine prefix: `<home>/xuser-<id>/.wine/drive_c/...`
- Contenedor "activo": symlink `rootfs/home/xuser` → `xuser-<id>`.
- `ContainerManager.loadContainers()` **solo escanea** `home/*` con prefijo `xuser-` y lee su
  `.container`: con crear el directorio y el JSON, la app ya lo lista.

### Claves del `.container` (todas opcionales salvo `name`; se aplican los defaults)

`id`, `name`, `screenSize` (def. `1280x720`), `envVars`, `cpuList`, `cpuListWoW64`,
`graphicsDriver`, `dxwrapper`, `dxwrapperConfig`, `graphicsDriverConfig`, `audioDriverConfig`,
`audioDriver` (def. `alsa`), `wincomponents`, `drives`, `hudMode`, `startupSelection`
(0 normal / 1 essential / 2 aggressive), `box64Preset`, `desktopTheme`,
`extraData` (incluye `appVersion`), `wineVersion` (solo si NO es el principal).

- Drivers: Vulkan = `turnip` \| `vortek`; OpenGL = `zink` \| `virgl` \| `gladio`.
  Por defecto `graphicsDriver = "vortek,gladio"` (Vortek es la capa Vulkan genérica para
  GPUs sin Turnip → **es la que necesita PowerVR**). `GraphicsDrivers.toString()` une con coma.
- DX wrappers: `wined3d` \| `dxvk` \| `vkd3d` \| `cnc-ddraw` \| `d7vk` (def. `dxvk`).
- `box64Preset`: `STABILITY` \| `CONSERVATIVE` \| `INTERMEDIATE` \| `PERFORMANCE` (def.).
- `drives`: formato concatenado `D:<ruta>E:<ruta>` sin separador. Por defecto
  `D:` = `/storage/emulated/0/Download`, `E:` = `/data/data/com.winlator/storage`.

Ejemplo mínimo usado:

```json
{"id":0,"name":"WoW-Forever","screenSize":"1280x720","graphicsDriver":"vortek,gladio",
 "dxwrapper":"dxvk","drives":"D:/storage/emulated/0/Download","startupSelection":1,
 "box64Preset":"PERFORMANCE","extraData":{"appVersion":"33"}}
```

## El Wine prefix: extraer `container_pattern.tzst`

Winlator crea el prefix extrayendo `assets/container_pattern.tzst` (dentro del APK) en el
directorio del contenedor. Se puede hacer desde fuera:

1. Sacar el asset del APK: `unzip -o -j Winlator_11.2.apk assets/container_pattern.tzst`
2. En el móvil **no hay `zstd`** (y `toybox tar -Z` intenta ejecutar un `zstd` externo →
   `exec zstd: Permission denied`). Solución: descomprimir en el host y enviar el tar ya
   expandido por **stdin** (binario-seguro; verificado con md5):
   ```bash
   zstd -d -c container_pattern.tzst > pattern.tar
   adb -s <serial> shell "run-as com.winlator sh -c 'cd files/rootfs/home/xuser-0 && tar xf -'" < pattern.tar
   ```
   (54 MB de prefix: `drive_c/{Program Files, Program Files (x86), ProgramData, users, windows}`)

Nota: `run-as` **no puede leer `/sdcard`** (namespace/SELinux distintos); por eso el envío va
por stdin al directorio privado, no por `adb push` + `run-as tar`.

## Lo que NO se puede automatizar (tal cual está el dispositivo)

- **`adb shell input tap/swipe/text`** → `SecurityException: INJECT_EVENTS`. HyperOS lo
  bloquea salvo que se active **"Depuración USB (Ajustes de seguridad)"** en Opciones de
  desarrollador (Xiaomi pide cuenta Mi + SIM + red).
- **Lanzar la actividad de juego** `com.winlator/.XServerDisplayActivity`: **no está
  exportada**. `am start` desde shell falla
  (`not exported from uid 10525`), y desde `run-as` falla porque el paquete llamante
  declarado es `com.android.shell` y no coincide con el uid 10525.
  Acepta extras: `container_id` (int), `exec_path` (ruta UNIX; se convierte con
  `unixToDOSPath`), `shortcut_path`, `wine_info`, `generate_wineprefix`.
  → Lanzar el juego **requiere un toque humano** hasta que el APK propio haga esta llamada
  como proceso propio (uid correcto), que es precisamente lo que hará la Fase 5.

## Comandos de referencia

```bash
ADB=~/.local/opt/platform-tools/adb; D="-s <serial>"
R="run-as com.winlator"
$ADB $D shell "$R mkdir -p files/rootfs/home/xuser-0"
$ADB $D shell "$R sh -c 'cat > files/rootfs/home/xuser-0/.container'" < container.json
$ADB $D shell "$R sh -c 'cd files/rootfs/home && rm -f xuser && ln -s xuser-0 xuser'"
$ADB $D shell "am force-stop com.winlator"; $ADB $D shell am start -n com.winlator/.MainActivity
$ADB $D exec-out screencap -p > screen.png   # verificación visual
```

## Lanzar un ejecutable: formato EXACTO del acceso directo (crítico)

Los accesos directos son ficheros `.desktop` en
`<prefix>/drive_c/users/xuser/Desktop/` y aparecen en la pantalla *Shortcuts* de la app.
La app los lanza con `XServerDisplayActivity` + `shortcut_path`.

`Shortcut.java` deriva la ruta del ejecutable **del campo `Exec`**, tomando todo lo que
hay **después del último `"wine "`**, y luego `XServerDisplayActivity.getWineStartCommand()`
hace `FileUtils.getDirname(execPath)`, que corta por el último separador.

- **Correcto** (barras normales + al menos un directorio, como el propio `.startmenu` de Winlator):
  ```
  [Desktop Entry]
  Type=Application
  Name=WoW Beta
  Exec=wine D:/wowforever/client/WowB.exe
  Icon=wine
  [Extra Data]
  container_id=0
  ```
- **Incorrecto** y causa crash de la app: `Exec=wine "D:\WowB.exe"` ⇒ `getDirname` no
  encuentra separador (`substring(0, -1)`) ⇒
  `StringIndexOutOfBoundsException` en `FileUtils.getDirname` ← `getWineStartCommand` ← `setupXEnvironment`.
  También falla `D:/WowB.exe` (sin directorio): `D:/` no tiene subcarpeta.

## Caminos descartados (con evidencia)

- **Lanzar Wine desde `adb shell run-as`**: inviable. `run-as` no puede leer `/sdcard`
  (dominio SELinux `runas_app`) y el rootfs provoca `noexec` ⇒
  `map_image_into_view failed to set protection on ...ntdll.dll` + aborto de glibc.
- **`RunOnce` del registro de Wine**: el `explorer.exe` de Winlator no lo procesa
  (verificado: arranca sin lanzar el programa).
- **`am start` con extras** (`--es exec_path` / `--es shortcut_path`):
  `SecurityException: ... not exported from uid 10525`. Sin extras sí sirve para traer la tarea al frente.

## Estado del lanzamiento de `WowB.exe` (verificado)

Con el acceso directo corregido, `WowB.exe` **se ejecuta** en el POCO: el logcat muestra
el proceso (`pid` propio) ejecutando `ntdll.dll` y creando ficheros temporales de Wine,
y su RSS crece hasta ~500 MB. Después **termina** (~5-40 s) y Winlator cierra el
escritorio; no se crea `Logs/` en el directorio del juego.

Pendiente de diagnosticar por qué termina. Hipótesis, en orden:
1. datos CASC insuficientes (`Data/data` = 395 MB de un total de ~100 GiB) y el cliente
   no completa el arranque;
2. el directorio del juego está en `/sdcard` (fuse) y el cliente no puede escribir
   `WTF/`, `Cache/` ni `Logs/`;
3. inicialización gráfica (DXVK sobre Vulkan propietario PowerVR).

Experimento de aislamiento recomendado: lanzar `Z:/opt/apps/TestD3D.exe` (probador D3D
que trae Winlator) antes de culpar al juego.
