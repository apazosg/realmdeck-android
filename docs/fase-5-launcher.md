# Fase 5: launcher Android (bitácora)

> Estado: en curso, corte2026-10-21. C127 funcional alcanza Login y entrada al
> juego; C128 está compilada/auditada, pendiente de instalación y prueba física.
> El cuerpo histórico de esta bitácora describe el esqueleto `android/`
> (módulo/toolchain: NDK 27, `compileSdk 35`/`targetSdk 28`, emulador x86_64) y
> prueba sintética de parcheo cross-proceso. **Ámbito distinto** del prototipo
> funcional GameNative C127 bajo `scripts/arm64-prototype/app`
> (ver [handoff](downloader-handoff.md)). El cliente70450 ya está descargado en USB;
> la optimización singlepass sigue separada y no implementada.
> Skeleton construido y **riesgo de hijo sintético retirado**
> (parcheo cross-proceso hijo propio en emulador, sin root; no es el WoW real
> ni una prueba adicional del WoW real por sí misma).

## Avances posteriores en el launcher funcional

- Provisión Android del cliente en USB, revisión/reutilización de instalaciones
  pegadas y preparación automática del runtime. ImageFS descargado desde la app
  en primer uso (~175MiB), con caché; no requisito de preparación manual.
- Admisión70450 y UUID/ruta durables, identidades físicas congeladas por operación,
  migración probada con archivos sueltos/metadatos, panel de lanzamiento corregido
  y presentación de salida en el hilo principal.
- CASC/KMT corregido desde C122 en escritor/estimador y reanudación. Se validó la
  reserva48KiB; no se ha demostrado que sea el mínimo nativo ni repetido una
  descarga completa para validar el escritor.
- C126 valida el bloque real ARM64 con relleno variable; C127 aplica/revalida solo
  32 bytes públicos del grupo8 en el hijo propio, sin root/EXE modificado/bypass TLS.
  El usuario confirma Login y acceso dentro del juego sin glitches observados.
- C127:29 tests JVM,32 host, revisión nativa/Kotlin y auditoría APK;7 hashes
  privados preservados al actualizar. C128:32 JVM y auditoría correctas, misma
  biblioteca C127, inicio antes de Enter sin ampliar900s totales. No instalada.
- Ruta activa: paquete `com.wowforever.arm64probe`, cliente Windows ARM64,
  Proton11 ARM64EC/DXVK/PanVK en xaga/Mali-G610. Nativo funcional API29/NDK24,
  16KiB y8 exports; no confundirlo con el toolchain del esqueleto de abajo.
- Pendientes: prueba pre-Enter,30–60min/FPS/audio/controles, USB y limpieza
  Wine/PulseAudio/zombis. La reserva se recupera por API bajo quiescencia, no
  eliminando archivos. No interrumpir la partida para instalar C128.

Detalle y artefactos en [estado consolidado](launcher-app-status.md) y
[registro Forever](arm64-forever-connection-progress.md).

## Historial del esqueleto y pruebas sintéticas

## Lo construido

| Pieza | Ruta | Estado |
|---|---|---|
| Proyecto Android | `android/` (Gradle Kotlin DSL, Views, sin Compose/AppCompat) | Compila e instala |
| Puente nativo JNI | `crates/forever-patcher-jni` (cdylib) | 3/3 tests |
| Núcleo del patcher | `crates/forever-patcher` (sin `unsafe`) | 12/12 tests, intacto |
| Cross-compile | `scripts/build-android-native.sh` | Genera `x86_64` + `arm64-v8a` |
| Emulador | `scripts/android-emulator.sh`, `docs/android-emulator.md` | AVD `wowtest`, Android 15 |

Toolchain: NDK **27.0.12077973**, build-tools 35.0.0, `minSdk 26`, `compileSdk 35` y
`targetSdk 28`,
ABIs `x86_64` + `arm64-v8a`. El cross-compile se hace **sin `cargo-ndk`**, fijando el clang
del NDK como linker y `-Wl,-z,max-page-size=16384` (Android 15+ exige páginas de 16 KB;
los `LOAD` de las `.so` quedan alineados a `0x4000`).

## D-L3 validado en Android (el resultado que importaba)

Hipótesis a comprobar: *¿puede una app Android leer y escribir la memoria de un proceso
hijo propio, sin root, con el patcher real?* Android tiene SELinux y restricciones sobre
`/proc`, así que estaba a ciegas.

**Resultado en el emulador** (el hijo construye un almacén sintético, el padre lo parchea
con el núcleo real y el hijo confirma el cambio):

```
result=OK child_pid=4685 stores=1 store_addr=0x79cc97331000
patched=true child_confirm=PATCHED rescan_already_patched=true
scan_ms=17290 child_alive_after_scan=true
maps=2907 rwp_anon=396 legibles=396/396
base_read=Ok(010000009b0671c815dff513bfd4a2b2 match=true) first_err=none
block=read_Ok valid=true container=0x79cc97331000-0x79cc97332000
chunk=Ok(4096) anchor_in_page=1@Some(4)
```

Lectura: **sin ninguna denegación** (`first_err=none`, 396/396 regiones candidatas
legibles), los bytes de la página del hijo son exactamente los del almacén esperado
(el ancla `9b0671c8…` aparece en el offset 4), y el hijo —otro proceso— ve el cambio.

⇒ **D-L3 es viable en Android sin root para un hijo propio sintético.** El launcher
puede lanzar su propio proceso hijo y aplicar Forever por memoria **en ese caso
probado** (hijo sintético con almacén construido por el test, emulador); no es
prueba del layout/método/auth del WoW ARM64 real.

### Dato práctico para el producto

El escaneo completo tardó **~17,3 s** sobre ~2.900 mapeos en el emulador. Consecuencia de
diseño: el parcheo **no puede ser síncrono en el hilo de UI**; el launcher debe escanear
en segundo plano, con reintentos y feedback, porque reaplicar tras un reinicio del
`CertStore` también cuesta lo suyo.

### Dos trampas específicas de Android encontradas

1. **`target_os`**: en Rust, Android es `target_os = "android"`, **no** `"linux"`. Un
   `cfg!(target_os = "linux")` desactivaba el código en el móvil sin avisar.
2. **Páginas de 16 KB**: en Android 15+ las `.so` deben alinearse a 16 KB o no cargan.

## Restricción de ejecución (W^X) — CONFIRMADA y resuelta

Se medía como duda pendiente y ya está comprobada en el emulador (Android 15) con una
sonda `fork`+`execve` que copia un binario real (`/system/bin/sh`) a cada directorio:

| Directorio | `targetSdk 35` | `targetSdk 28` |
|---|---|---|
| `filesDir` (datos internos) | ❌ `EACCES` (13) | ✅ ejecuta (exit 42) |
| `codeCacheDir` | ❌ `EACCES` (13) | ✅ ejecuta |
| `externalFilesDir` (`/sdcard/Android/data/…`) | ❌ `EACCES` | ❌ **sigue `EACCES`** |
| `nativeLibraryDir` (`/data/app/.../lib/...`) | no escribible | no escribible |
| Control: `/system/bin/sh` | ✅ | ✅ |

Conclusiones:

1. **Desde API 29 Android niega a la app ejecutar lo que escribe en su almacenamiento
   interno.** El launcher debe fijar **`targetSdk 28`** — es la misma razón por la que
   Winlator y FrontMage usan 28. Aplicado en `android/app/build.gradle.kts` con comentario.
2. **El almacenamiento externo (`/sdcard/Android/data/...`) nunca es ejecutable**, ni con 28.
   Implicación: el **runtime** (Wine, Box64/FEX) debe vivir en el almacenamiento **interno**.
   Los datos del juego sí pueden estar en `/sdcard` (el cliente no se ejecuta con `execve`,
   lo carga Wine).
3. Consecuencia para distribución: un `targetSdk 28` **no es publicable en Google Play**;
   el reparto será APK directo, igual que los runtimes de los que dependemos.

Evidencia: `/home/adrian/wowforever-artifacts/android-launcher/execve-targetsdk/`
(`logcat-targetsdk35.txt`, `logcat-targetsdk28.txt` + capturas).

## Aprendizaje del proceso

Un `FAIL stage=scan … hallados 0` era **ambiguo**, porque el núcleo salta en silencio las
regiones ilegibles: no distinguía "SELinux me lo niega" de "el test está mal". Se añadió
diagnóstico explícito (regiones legibles, `errno` de la primera lectura, lectura y
validación de la página del hijo) y resultó ser un problema **temporal**: el escaneo
(~17 s) tardaba más que la vida del hijo (10 s). **Sin evidencia no se decide.**

## Validación offline del launcher

El contrato que consume la app se genera desde `forever/contract.json` durante el
build. No existe una segunda copia versionada en `android/app/src/main/assets/`.
La tarea `:app:generateContractAsset` declara la fuente como input y el asset
generado como output; una modificación de un fixture de entrada la invalida sin
modificar el contrato canónico. El runner `scripts/android-launcher-smoke.py`
automatiza el autotest sin UI y exige `--serial` explícito.

Resultados de las pruebas fake del runner (sin tocar ningún dispositivo):

| Escenario | Código | Estado |
|---|---:|---|
| marcador del run actual | 0 | PASS |
| marcador `result=FAIL` | 1 | FAIL |
| sin marcador antes del límite | 2 | TIMEOUT |
| solo marcador de un run antiguo | 2 | TIMEOUT |

Cada smoke real se guarda fuera del repositorio en
`/home/adrian/wowforever-artifacts/android-launcher/smoke/<run_id>/`. La prueba
solo acepta `run_id=<id> phase=selftest result=PASS|FAIL`; no se limpia logcat.

Smoke real ejecutado el 2026-09-28 en `emulator-5554`:

```text
PASS run_id=run-fb7bdb0e0e904a069b63bd7feddee2fe
evidence=/home/adrian/wowforever-artifacts/android-launcher/smoke/run-fb7bdb0e0e904a069b63bd7feddee2fe
```

`result.json` conserva `exit_code=0`, `status=PASS` y el marcador de esta
ejecución. El autotest nativo informó `child_confirm=PATCHED`,
`rescan_already_patched=true`, `scan_ms=17495`, `legibles=386/386` y
`first_err=none`. Esto valida el launcher/puente en el emulador x86_64; no
valida la ABI ARM64, el runtime Wine ni el cliente real.

Tras endurecer el filtrado de tokens del runner y quitar el override de `run_id`,
se repitió el smoke sin rebuild del APK el 2026-09-28:

```text
PASS run_id=run-d21eaae956e148309cbf9bf9d8d737ff
evidence=/home/adrian/wowforever-artifacts/android-launcher/smoke/run-d21eaae956e148309cbf9bf9d8d737ff
```

`result.json` conserva `exit_code=0`, `child_confirm=PATCHED`, `scan_ms=17612`,
`legibles=383/383` y `first_err=none`.

## Banco de pruebas diagnóstico

La UI del APK ahora organiza el catálogo existente en una batería fuera del hilo
de UI y secuencial: `jni_contract`, `exec_probe` y
`cross_process_self_test`. Cada resultado conserva estado, detalle, inicio/fin y
duración; la corrida conserva `run_id`, versión `0.1.1-diagnostic` (versionCode 2), ABI, SDK, `targetSdk=28`,
`runtime=none` y `gpu=not_measured`. Los únicos estados terminales son `PASS`,
`FAIL`, `TIMEOUT` y `UNSUPPORTED`; un timeout no cancela de forma ficticia el
hilo JNI y bloquea el siguiente test hasta que la sonda termine. La persistencia
es privada (`files/diagnostics/runs/*.json` y `*.log`) y la exportación JSON/texto
se hace mediante SAF `ACTION_CREATE_DOCUMENT`.

El parser final de `ExecProbe` exige exactamente un registro por directorio y por
control. `C2_so` es PASS cuando el `.so` devuelve el rechazo esperado
`ENOEXEC`/`EACCES` **o cuando `execve` confirma la ejecución y el proceso termina
después por una señal**; en este último caso la señal del `.so` no equivale a un
crash de la aplicación ni de la sonda. `SKIP (sin .so)` queda `UNSUPPORTED`, nunca PASS. El parser de
`crossProcessSelfTest` exige campos únicos y valores exactos para los testigos de
parcheo, pero deja pasar el texto diagnóstico restante del formato Rust.

Se comprobó manualmente en `emulator-5554` sin detener el proceso del emulador,
tras verificar espacio libre en `/home/adrian/wowforever-artifacts`:

```text
run_id=run-9116b1cd6e0946bb832c85423756a4d0 status=PASS
jni_contract=PASS exec_probe=PASS cross_process_self_test=PASS
runtime=none gpu=not_measured abi=x86_64 targetSdk=28
crossProcessSelfTest child_confirm=PATCHED scan_ms=17310
```

La evidencia de la corrida queda fuera del repositorio en
`/home/adrian/wowforever-artifacts/android-testbench/run-9116b1cd6e0946bb832c85423756a4d0/`;
contiene el JSON persistido, el JSON exportado por SAF, el log de la corrida y
el logcat filtrado. Los controles UI usados fueron `uiautomator dump`,
`input tap` sobre `battery_button`, `input swipe` para los botones de exportación
y `input tap` sobre `EXPORTAR JSON`; el fichero se verificó en
`/sdcard/Download/diagnostic-run-9116b1cd6e0946bb832c85423756a4d0.json`.

Esto valida el banco y sus mecanismos en x86_64/SwiftShader. No valida ABI ARM64,
Wine/Winlator, cliente real, modelo de GPU ni compatibilidad de un driver GPU.

Verificación histórica posterior al endurecimiento de parsers, antes del ajuste
`useLegacyPackaging`:

```text
run_id=run-4d2f6f603cc146a0a4cafedab746936f status=UNSUPPORTED
jni_contract=PASS exec_probe=UNSUPPORTED (C2 SKIP) cross_process_self_test=PASS
evidence=/home/adrian/wowforever-artifacts/android-testbench/run-4d2f6f603cc146a0a4cafedab746936f/
```

El smoke compatible final conservó el contrato externo y dio PASS:

```text
PASS run_id=run-3f22d5b280c24156aa35124436b46765
evidence=/home/adrian/wowforever-artifacts/android-testbench/smoke/run-3f22d5b280c24156aa35124436b46765
```

El autotest informó `child_confirm=PATCHED`, `scan_ms=17296` y
`first_err=none`. El `UNSUPPORTED` de la batería es intencionado: el emulador no
expone una `.so` para el control C2, por lo que no se afirma esa capacidad.

## Pendiente

- Perfiles/adaptadores Winlator y detección/matriz de GPU quedan deliberadamente
  fuera de este banco; `GPU: not measured` evita inferir compatibilidad.
- El emulador es **x86_64**: valida mecanismos, no la ABI final ni GPUs reales.

## Entrega APK diagnóstico autónomo (2026-09-28)

La entrega local `0.1.1-diagnostic` (versionCode 2) incluye `arm64-v8a` y
`x86_64`, está firmada con la clave debug y no declara permisos Android. El ajuste
`useLegacyPackaging=true` hizo que C2 dejara de ser `SKIP`: en el emulador la
línea observada fue `exec=OK pero señal=11 (execve SÍ permitió arrancar)`, que se
clasifica como `PASS` por ejecución confirmada, no como crash de la app.

La batería final verificada en `emulator-5554` fue:

```text
run_id=run-21c42243826c40218eed0c9b5fdad2dd status=PASS
jni_contract=PASS exec_probe=PASS cross_process_self_test=PASS
abi=x86_64 targetSdk=28 runtime=none gpu=not_measured
```

Artefacto local entregable:

```text
APK=/home/adrian/wowforever-artifacts/delivery/wowforever-diagnostic-0.1.1-code2-debug.apk
SHA256=b68eb89efb3156b3e266a618ef7752e5dd0c7b5e81c570abc0473848c90ce54e
size=1760556 bytes
signature=debug APK Signature Scheme v2
minSdk=26 targetSdk=28 permissions=none
```

La persistencia se comprobó cerrando y relanzando la Activity: la corrida
persistida se vuelve a mostrar sin relanzar la batería. La exportación se realiza
con SAF `ACTION_CREATE_DOCUMENT`; no requiere ADB ni permisos de almacenamiento.
La evidencia exportada está en
`/home/adrian/wowforever-artifacts/delivery/diagnostic-0.1.1-emulator/exported-diagnostic-run-21c42243826c40218eed0c9b5fdad2dd.json`
y contiene `status=PASS`, `app_version=0.1.1-diagnostic (versionCode=2)` y las
tres pruebas PASS.
El APK no escribe el juego y no promete crash dumps. La ABI ARM64 se verifica por
empaquetado, no por ejecución en este emulador.
