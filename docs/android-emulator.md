# Emulador Android x86_64 headless (host Linux)

> Instalado y verificado el 2026-09-28 en `hermes` (Ubuntu 26.04, x86_64).
> Propósito: desarrollar y probar el **futuro launcher Android** del proyecto.
> **No** sirve para el runtime del juego (ver Limitaciones).

## Qué hay instalado

| Componente | Versión exacta | Ruta |
|---|---|---|
| Command line tools | 15859902 (`commandlinetools-linux-15859902_latest.zip`, sdkmanager 22.0) | `~/.local/opt/android-sdk/cmdline-tools/latest/` |
| Emulator | 37.1.11 (build_id 15917651) | `~/.local/opt/android-sdk/emulator/` |
| platform-tools (adb, fastboot) | 37.0.1 | `~/.local/opt/android-sdk/platform-tools/` |
| platforms;android-35 | rev 2 (Android 15, API 35, ext level 13) | `~/.local/opt/android-sdk/platforms/android-35/` |
| build-tools;35.0.0 | 35.0.0 | `~/.local/opt/android-sdk/build-tools/35.0.0/` |
| Imagen de sistema | `system-images;android-35;google_apis;x86_64` **rev 9** (`x86_64-35_r09`) | `~/.local/opt/android-sdk/system-images/android-35/google_apis/x86_64/` |
| AVD `wowtest` | perfil `pixel_5`, API 35, ABI x86_64 | `~/.android/avd/wowtest.avd/config.ini` |
| Artefactos (log, PID, capturas, APK trivial) | — | `/home/adrian/wowforever-artifacts/android-emulator/` (fuera del repo) |

Configuración relevante del AVD (`config.ini`): `hw.ramSize=4096M`,
`hw.gpu.mode=swiftshader_indirect` + `hw.gpu.enabled=yes` (render por software,
sin GPU física), `hw.audioInput=no`, `hw.audioOutput=no`,
`hw.camera.back=none`, `hw.camera.front=none`. Arranque con
`-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 4096`.

Notas de instalación:

- `/dev/kvm` existe; el usuario pertenece al grupo `kvm`, pero la sesión puede
  no tenerlo activo (comprobar con `id -nG`). Si falta, los comandos del
  emulador se lanzan con `sg kvm -c '<comando>'` (el script ya lo hace solo).
- Al crear el AVD, `avdmanager` dejó placeholders de plantilla en
  `config.ini` (`avd.id=<build>`, `avd.name=<build>`,
  `disk.dataPartition.path=<temp>`): se corrigieron a mano
  (`avd.id/name=wowtest`, línea `<temp>` eliminada). Sin esto el AVD igual
  aparece en `-list-avds`, pero conviene dejarlo sano.

## Uso (vía script)

```bash
scripts/android-emulator.sh start            # arranca y espera a sys.boot_completed=1 (timeout 600 s)
scripts/android-emulator.sh stop             # apagado limpio (adb emu kill / PID guardado)
scripts/android-emulator.sh status           # proceso + estado adb + boot
scripts/android-emulator.sh screencap [ruta] # PNG de la pantalla (defecto: $EMU_DIR/screencap-<fecha>.png)
```

Variables: `ANDROID_SDK_ROOT` (defecto `~/.local/opt/android-sdk`),
`ANDROID_EMU_DIR` (defecto
`/home/adrian/wowforever-artifacts/android-emulator`),
`ANDROID_EMU_AVD` (defecto `wowtest`),
`ANDROID_EMU_SERIAL` (defecto `emulator-5554`),
`ANDROID_EMU_BOOT_TIMEOUT` (defecto 600 s).

Equivalentes manuales:

```bash
export ANDROID_SDK_ROOT=$HOME/.local/opt/android-sdk
sg kvm -c "nohup $ANDROID_SDK_ROOT/emulator/emulator -avd wowtest -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 4096 > /home/adrian/wowforever-artifacts/android-emulator/emulator.log 2>&1 & echo \$! > /home/adrian/wowforever-artifacts/android-emulator/emulator.pid"
$ANDROID_SDK_ROOT/platform-tools/adb wait-for-device
$ANDROID_SDK_ROOT/platform-tools/adb shell getprop sys.boot_completed  # 1 = listo
$ANDROID_SDK_ROOT/platform-tools/adb -s emulator-5554 emu kill         # parar
```

**Aviso:** para parar, usar siempre el PID guardado (`emulator.pid`) o
`adb emu kill`. **No** usar `pkill -f`/`grep -f` con un patrón que aparezca
literalmente en la propia línea de comandos: mataría la propia shell.

## Ver la pantalla sin ventana

```bash
$ANDROID_SDK_ROOT/platform-tools/adb -s emulator-5554 exec-out screencap -p > /home/adrian/wowforever-artifacts/android-emulator/boot.png
```

Verificado: PNG de 1080×2340 con el launcher y las apps de Google visibles.

## Instalar APKs de prueba

`adb install` funciona. Verificado con un APK trivial (`org.wowforever.trivial`,
en `android-emulator/trivial-apk/trivial.apk`): `Success` y aparece en
`pm list packages`.

**Hallazgo:** en Android 15 un APK **sin `classes.dex` no instala**
(`INSTALL_FAILED_INVALID_APK: … code is missing`), aunque no declare ninguna
activity. Hay que incluir un `classes.dex` mínimo (generado con
`javac` + `d8 --min-api 21`), añadirlo al APK tras `aapt2 link`, y después
`zipalign` + `apksigner sign`. Orden correcto:

```bash
aapt2 link -o trivial-unsigned.apk --manifest AndroidManifest.xml -I android.jar …
# añadir classes.dex al zip (zip -j / python zipfile), NO como argumento de aapt2 link
zipalign -f 4 trivial-unsigned.apk trivial-aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out trivial.apk trivial-aligned.apk
adb -s emulator-5554 install -r trivial.apk
```

## Proyecto del launcher (Fase 5)

El proyecto Android vive en `android/` (Gradle Kotlin DSL, Kotlin, Views, sin
Compose). Toolchain:

| Componente | Versión | Nota |
|---|---|---|
| NDK | **27.0.12077973** | Primer NDK estable con cabeceras finales de `android-35`; encaja con `build-tools 35.0.0` |
| AGP / Gradle | 8.9.1 / 8.11.1 | Ya en caché local; el build funciona con `--offline` |
| Kotlin | 2.1.21 | — |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 28 / 35 | `abiFilters`: `x86_64` (emulador) + `arm64-v8a` (móvil) |

La biblioteca nativa es el puente JNI al patcher (`crates/forever-patcher-jni`,
crate aparte; el núcleo `crates/forever-patcher` sigue sin `unsafe`). **Las `.so`
no se versionan**: se regeneran antes de ensamblar el APK.

```bash
scripts/build-android-native.sh          # cross-compila y copia a jniLibs/
cd android && ./gradlew :app:assembleDebug
$ANDROID_SDK_ROOT/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_SDK_ROOT/platform-tools/adb -s emulator-5554 shell am start -n com.wowforever.launcher/.MainActivity
```

## Smoke test reproducible sin UI

El autotest cross-proceso se dispara con un `Intent` (`run_selftest=true`), por lo
que no requiere tocar la pantalla. `--serial` es obligatorio: el runner nunca
elige un dispositivo implícito. Cada ejecución genera un `run_id` único, lo pasa a
la Activity y acepta únicamente una línea `ForeverJNI` con ese identificador.
No borra el buffer global de logcat; los logs antiguos quedan en la evidencia,
pero no pueden producir un resultado de la ejecución actual.

```bash
scripts/android-launcher-smoke.py \
  --serial emulator-5554 \
  --apk android/app/build/outputs/apk/debug/app-debug.apk
```

Los códigos son `0=PASS`, `1=FAIL` y `2=TIMEOUT`. La evidencia queda fuera del
repositorio, en `/home/adrian/wowforever-artifacts/android-launcher/smoke/<run_id>/`:
`run.json`, `result.json`, `commands.txt`, salida de instalación/arranque y
`logcat.txt`. Se puede cambiar la raíz con `--output-dir`, pero el runner rechaza
rutas dentro del repositorio y genera siempre el `run_id`; no hay override público
que permita reutilizar uno antiguo. Si `adb` no está en `PATH`, el runner usa
también `/home/adrian/.local/opt/android-sdk/platform-tools/adb`.

El asset `contract.json` ya no se mantiene a mano: la tarea incremental
`:app:generateContractAsset` copia `forever/contract.json` mediante una dependencia
de entrada/salida declarada y lo incluye en el APK durante `preBuild`. Para
comprobarlo offline:

```bash
cd android
./gradlew --offline :app:generateContractAsset
```

La prueba de invalidación de Gradle es opt-in y construye un proyecto mínimo en
un directorio temporal, sin modificar `android/app/build/`:

```bash
WOWFOREVER_RUN_GRADLE_INTEGRATION=1 \
  /home/adrian/.local/bin/uv run pytest tests/test_android_contract_asset.py -q
```

## Banco de pruebas diagnóstico

La pantalla Views del APK mantiene el smoke anterior y añade una batería
secuencial de tres pruebas reales: `JNI + contrato`, `ExecProbe` y
`crossProcessSelfTest`. Cada corrida se guarda en el almacenamiento privado de la
app como `files/diagnostics/runs/<run_id>.json` más un log `<run_id>.log`. El JSON
incluye versión de app, ABI, SDK/`targetSdk`, tiempos por prueba y los estados
`PASS`, `FAIL`, `TIMEOUT` o `UNSUPPORTED`; `runtime=none` y `gpu=not_measured` son
explícitos. Un timeout no mata ni declara cancelada la llamada JNI: la cola espera
a que termine antes de iniciar otra prueba.

Los botones de exportación usan `ACTION_CREATE_DOCUMENT` (SAF), sin permisos de
almacenamiento ni dependencias nuevas, y ofrecen JSON o texto. La cola vive en un
singleton del proceso y la Activity solo se vuelve a suscribir al estado, por lo
que una recreación no duplica sondas ni pierde los resultados ya escritos. Tras la
muerte completa del proceso, un run que quedó `RUNNING` se conserva como
`TIMEOUT` incierto al reabrirlo; no se finge cancelación nativa.

La compatibilidad automatizada existente permanece: `run_selftest=true` y
`run_id=<id>` disparan únicamente `crossProcessSelfTest`, y el runner acepta el
marcador `ForeverJNI` de esa corrida. No se empaquetan Winlator/Wine ni se
ejecutan perfiles externos.

El parser estricto de ExecProbe no convierte un control C2 ausente en PASS:
`SKIP (sin .so en nativeLibraryDir)` se informa como `UNSUPPORTED`. Con una `.so`
presente, acepta el rechazo esperado `ENOEXEC`/`EACCES` **o la ejecución confirmada
por `execve` seguida de una señal del proceso** (por ejemplo, el `.so` intentado
como ejecutable termina con `SIGSEGV`). Esta última evidencia demuestra que el
kernel permitió `execve`; no equivale a afirmar que la sonda o la aplicación se
crasheó. El parser del
autotest exige campos únicos (`result`, `stores`, `patched`, confirmación del hijo
y `first_err`) y valores exactos, sin rechazar el diagnóstico adicional con
espacios que emite Rust.

La entrega diagnóstica usa `useLegacyPackaging=true` para que las bibliotecas JNI
se extraigan a `nativeLibraryDir`, sin permisos de almacenamiento adicionales,
sin cambiar `targetSdk=28` y sin incluir Winlator/Wine/WoW.

Verificación histórica en `emulator-5554` antes del ajuste de empaquetado: batería
`run-4d2f6f603cc146a0a4cafedab746936f` terminó `UNSUPPORTED` de forma honesta
(`JNI + contrato PASS`, `ExecProbe UNSUPPORTED`, `crossProcessSelfTest PASS`). La
evidencia está en
`/home/adrian/wowforever-artifacts/android-testbench/run-4d2f6f603cc146a0a4cafedab746936f/`.
El smoke compatible final terminó `PASS` con run ID
`run-3f22d5b280c24156aa35124436b46765` en
`/home/adrian/wowforever-artifacts/android-testbench/smoke/run-3f22d5b280c24156aa35124436b46765/`.

Detalle del cross-compile: sin `cargo-ndk`; se fijan
`CARGO_TARGET_{X86_64,AARCH64}_LINUX_ANDROID_LINKER` al clang del NDK y
`-C link-arg=-Wl,-z,max-page-size=16384` (Android 15+ exige páginas de 16 KB).

Verificado el 2026-09-28: APK instalado y arrancado en `emulator-5554`; `logcat`
tag `ForeverJNI` muestra `nativeVersion=… (core KEY_LEN=32)` y
`contractSummary=entries=12 entry_size=40 target_group=8 anchor_group=1`.
La validación automatizada posterior queda registrada en la sección de evidencia
de `docs/fase-5-launcher.md`.

Entrega local diagnóstica verificada: `/home/adrian/wowforever-artifacts/delivery/`
contiene `wowforever-diagnostic-0.1.1-code2-debug.apk` (1.760.556 bytes,
SHA-256 `b68eb89efb3156b3e266a618ef7752e5dd0c7b5e81c570abc0473848c90ce54e`).
El APK es debug, `minSdk=26`, `targetSdk=28`, declara cero permisos y contiene
`arm64-v8a` y `x86_64`; la firma v2 es válida. La batería PASS, el JSON exportado
por SAF y la relanzada persistente están documentados en `docs/fase-5-launcher.md`.

## Limitaciones (importante)

- El emulador es **x86_64**. **No** puede ejecutar **Winlator ni FrontMage**
  (runtimes arm64-only) ni reproduce el driver Vulkan de ninguna GPU real
  (aquí todo es SwiftShader por software).
- Sirve para desarrollar y probar el **launcher** (instalación, UI, adb,
  gestión del cliente), **no** para el runtime del juego ni para pruebas de
  rendimiento gráfico.
- Sin cámara, sin audio, sin Play Store (`PlayStore.enabled=no`, imagen
  `google_apis`, no `google_apis_playstore`).

## Comandos de referencia

```bash
adb devices -l
adb -s emulator-5554 shell getprop ro.product.cpu.abi      # x86_64
adb -s emulator-5554 shell getprop ro.build.version.release # 15
adb -s emulator-5554 shell getprop ro.build.version.sdk     # 35
adb -s emulator-5554 shell getprop ro.product.model         # sdk_gphone64_x86_64
adb -s emulator-5554 shell cat /proc/meminfo | head -3
adb -s emulator-5554 shell pm list packages org.wowforever.trivial
```
