# Prototipo runtime integrado — estado de trabajo

## Entrega experimental4 — corrección de arranque ELF

Build generado después de aplicar todos los parches sobre el baseline limpio.
La corrección evita el `ENOENT` de Box64 invocando explícitamente el loader AArch64
del rootfs propio con `--library-path`; no modifica el binario upstream ni usa el
sandbox de `it.wowmobile`.

- APK: `/home/adrian/wowforever-artifacts/delivery/experimental4/wowforever-runtime-probe-experimental4.apk`.
- SHA-256: `3b8b2796a64dab9cb493e9fa93a2c1e332c85a9498c4f59c72ae4ca9d74e228f`.
- Tamaño: `166472281` bytes; paquete `com.wowforever.runtimeprobe`;
  `versionCode=4`; `versionName=0.4.0-runtime-diagnostics-experimental4`.
- Firma: `apksigner verify` PASS; certificado SHA-256
  `2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b`.
- Asset Box64 upstream: SHA-256
  `a46b9835bb3778ebeaccfd2efa0b5e9163ce4e772288d2fc7e00bb16b7899b53`.
- Build: `bootstrap.sh` PASS; baseline `0c8068f617737d9c0fb6246f37b54f85b620b09d`.

### Instalación y evidencia

Instalar sobre la instalación del prototipo sin borrar datos; si Android rechaza
la actualización por firma o versión, no limpiar el cliente/runtime existente:
reportar el error al parent. Ejecutar las sondas y exportar el informe completo.
La evidencia esperada incluye `BOX64-BEFORE`, `BOX64-AFTER`,
`BOX64-MARKER-COMMITTED` y `BOX64-EXEC` con `loaderMode=explicit-library-path`.

La APK no contiene cliente WoW ni credenciales. El build y la inspección de bytes
confirman la versión, ABI arm64, firma, el comando `--library-path` y ausencia de
la ruta upstream en las bibliotecas nativas. Esto no demuestra todavía ejecución
ARM64 en dispositivo ni éxito del runtime; requiere revisión del parent y prueba
experimental4 en Android.

## Entrega experimental2 final

Actualiza la primera prueba que produjo TIMEOUT/124 sin evidencia de sondas
(`20260929-141415-74165ddc`, informe aportado por el usuario). No atribuir causa
al driver ni a FEX/Box64 a partir de ese resultado.

- APK: `/home/adrian/wowforever-artifacts/delivery/experimental2/wowforever-runtime-probe-experimental2.apk`.
- SHA-256 final: `e058bcd2df9c43ed448fefafd5a555f08451867ab61113193f294e1eec3b1d54`.
- Tamaño: 166466145 bytes. Build limpio correcto; firma verificada y certificado
  igual al candidato anterior. Sustituye hash intermedio b0ee7702 de experimental2.
- Harness PASS: argv, fallo PID, fallo start, streams, wait, aislamiento sinks,
  línea parcial sin newline y espera de limpieza acotada. readLine sí conserva
  fragmento final al EOF; no se cambió por la observación inicial incorrecta.
- Timeout/driver/runtime conservados; supervisión recoge errores antes ocultos,
  conserva Process incluso sin PID y captura streams por ejecución.
- Cierre forzado produce SNAPSHOT/termination_unknown y bloqueo de otra corrida
  en la sesión: no promete terminar todos los descendientes ni reporte completo.
- Pendiente prueba integrada ARM64 del usuario. No es reparación confirmada.

## Candidato técnico actual: experimental2

- APK entregable: `/home/adrian/wowforever-artifacts/delivery/experimental2/wowforever-runtime-probe-experimental2.apk`.
- SHA-256: `b0ee77021b0cb6696ddc8ae98bf7f96411f2d66fee5b074bf70e2421b4751af0`.
- Tamaño: `166463233` bytes; paquete `com.wowforever.runtimeprobe`; `versionCode=2`; `versionName=0.2.0-runtime-diagnostics-experimental2`.
- Firma: `apksigner verify` PASS, certificado SHA-256 `2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b`; coincide con el candidato anterior.
- Build: `bootstrap.sh` PASS con base `0c8068f617737d9c0fb6246f37b54f85b620b09d`, JDK 17, Gradle 7.3.3, SDK 34, NDK 24 y CMake 3.22.1.
- Checks focalizados: `PATCH-CHECK-PASS`, `RUNTIME-PROCESS-DIAGNOSTICS-PASS parser-pid-failure-start-failure-streams-wait-stop` y `PROBE-RUNTIME-INVARIANTS-PASS concrete-prefix-and-single-terminal-winner`.

experimental2 es diagnóstico, no un arreglo probado: conserva el timeout de 120000 ms,
no cambia el driver ni el runtime, y no demuestra ejecución ARM64 ni resuelve por sí
solo el bloqueo de arranque. No contiene cliente WoW ni credenciales.

### Uso y evidencia esperada

1. Instalar el APK en una instalación compatible; mantiene el paquete y la firma del
   candidato previo, aunque incrementa la versión para identificar experimental2.
2. Ejecutar `RUN API + DYNCODE PROBES`, esperar la finalización o el timeout y usar
   `EXPORT LATEST REPORT`.
3. El informe incluye `run.started`, `run.finished`, `host-runtime` y breadcrumbs
   `PROCESS-EXEC`, `PROCESS-PID`/`PROCESS-PID-ERROR`, `PROCESS-STDOUT`,
   `PROCESS-STDERR`, `PROCESS-WAIT` y `PROCESS-CALLBACK`. Las variables de entorno
   solo se registran mediante la allowlist de diagnóstico. Cada ejecución conserva
   su snapshot de callbacks y cierra su sink antes de publicar el informe.
4. Un timeout o destrucción produce `outcome=SNAPSHOT`,
   `termination_unknown=true` y `cleanup_quiescent=<bool>`; solo se destruyen y
   esperan los procesos propietarios retenidos, no se afirma que sus descendientes
   hayan terminado. La APK bloquea otra ejecución en el mismo proceso y requiere
   reiniciar la app.
5. Entregar el informe completo para revisión; una captura de timeout sigue siendo
   evidencia diagnóstica, no evidencia de una causa corregida.

No se afirma ejecución en ARM64, ejecución del cliente WoW ni validación en dispositivo
para este candidato. La revisión de seguridad y la prueba privada del APK quedan a cargo
del parent.

## Candidato técnico anterior (posterior a los bloqueos históricos)

- Bootstrap limpio compilado y postchecks de firma/ABI/assets correctos.
- APK externo entregable para prueba privada en instalación limpia:
  `/home/adrian/wowforever-artifacts/delivery/wowforever-runtime-probe-experimental-61b916d3.apk`.
- SHA-256: `61b916d39a952bce3cbde24fa9bb260090a89b7ace88f87eccc5cf709afe15bb`.
- 166460789 bytes; paquete `com.wowforever.runtimeprobe`, ARM64.
- Corregido orden: contenedor primero, sondas en prefijo concreto; metadata
  conserva ese prefijo para recoger resultados. Activación verificada.
- Finalización única mediante CAS entre timeout, callback y lifecycle.
- `test-invariants.sh`: PASS prefijo concreto y único ganador terminal.
- Control host previo: ApiProbe exit0, DynCodeProbe exit0, binario ausente exit53;
  no demuestra ejecución del batch integrado Android ni casos negativos WinAPI.
- Revisión independiente: apto para ensayo técnico privado en instalación limpia.
  Si existiera instalación antigua de este prototipo, el directorio xuser real
  residual bloquearía activación; no borrar automáticamente datos desconocidos.
- Pendiente: ejecución ARM64 por usuario, extracción/arranque/export real y
  auditoría completa de licencias de terceros. No es go de WoW ni release pública.

Las secciones posteriores conservan el historial del gate inicial; donde indican
ausencia de APK/build, quedan superadas por esta evidencia.

## Objetivo aprobado

Una APK que ejecute ApiProbe y DynCodeProbe dentro de Wine/Box64 sin Winlator
externo ni ADB. No se ejecutará ni modificará el cliente WoW en este hito.

Base experimental: `marcocastignoli/wow-mobile`, commit
`0c8068f617737d9c0fb6246f37b54f85b620b09d` (LGPL-2.1 en raíz).
La elección no demuestra compatibilidad con Classic Beta ni reemplaza Fase 2.

## Estado real

**Baseline y prototipo integrado compilados correctamente.** La base limpia y
el APK de sondas producen `BUILD SUCCESSFUL`; el postcheck ahora valida el
manifiesto con `aapt`, las entradas reales del ZIP y la firma. Esto todavía no
valida ejecución del runtime ARM64.

Workspace externo:
`/home/adrian/wowforever-artifacts/integrated-runtime/source/`.
Contiene una adaptación inicial de paquete, UI, almacenamiento de informes y
arranque de sondas que todavía no se ha compilado ni validado.

El checkout aislado para la base limpia es:
`/home/adrian/wowforever-artifacts/integrated-runtime/base-build/`.
Está en detached HEAD, limpio, y apunta a
`0c8068f617737d9c0fb6246f37b54f85b620b09d`. No se modificó `source/`.

`scripts/runtime-prototype/` contiene el bootstrap y los parches del prototipo:

- La receta aplica los cuatro parches en un worktree limpio y construye fuera
  del repositorio; no modifica `base-build`.
- Auditoría del checkout sparse: 96 assets rastreados, 4 presentes, 92 faltantes.
  Faltan también bibliotecas JNI requeridas. No basta empaquetar rootfs y Box64.
- CMake 3.22.1 y compatibilidad del toolchain quedaron validados por el build
  upstream exitoso.
- Inventario del checkout base: 932 ficheros rastreados (178.275.629 bytes),
  72 assets (158.247.156 bytes) y 24 JNI arm64 (11.136.992 bytes). No hay
  submódulos/gitlinks. El asset mayor es `rootfs.tzst` (64.125.012 bytes);
  no hay blobs rastreados de más de 1 GiB.
- Auditoría inicial: solo estaban disponibles Java 21, SDK platform 36 y
  build-tools 35/36; faltaban NDK 24, CMake 3.22.1 y AGP 7.2.2. Gradle 8.11.1
  y AGP 8.9.1 estaban cacheados, pero no se usaron para no convertir la base
  upstream en una migración no justificada.
- El wrapper descargó Gradle 7.3.3 (115.768.236 bytes) durante el intento,
  superando el umbral de 100 MB de tooling; queda reportado.
- Primer error real: `./gradlew --offline --no-daemon --max-workers=1
  assembleDebug` (con SDK_ROOT apuntando a `/home/adrian/android-sdk`) inicia
  Gradle 7.3.3 y termina con `Unsupported class file major version 65` al
  compilar `settings.gradle`, porque Gradle 7.3.3 no soporta ejecutarse con
  Java 21.
- Resolución mecánica: se descargó Temurin JDK 17.0.20.1 de Adoptium en
  `toolchains/`, tamaño declarado 193.252.603 bytes, SHA-256
  `3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e`, y se
  usó solo mediante `JAVA_HOME` en el comando de Gradle. No se cambió Java
  global.
- El primer build offline con Java 17 falló únicamente por falta cacheada de
  `com.android.tools.build:gradle:7.2.2`. El build online estándar descargó las
  dependencias AGP upstream y detectó/instaló side-by-side NDK 24.0.8215888;
  después se instaló CMake 3.22.1 con `sdkmanager`. También quedó instalado
  SDK platform 34. Las licencias ya estaban aceptadas: `sdkmanager --licenses`
  informó `All SDK package licenses accepted` y Gradle confirmó la licencia
  del NDK sin solicitar términos nuevos.
- Build final: `JAVA_HOME=.../jdk-17.0.20.1+1 ANDROID_SDK_ROOT=.../android-sdk
  ./gradlew --no-daemon --max-workers=1 assembleDebug` terminó `BUILD
  SUCCESSFUL` (32 tareas). APK: `app/build/outputs/apk/debug/app-debug.apk`,
  166.218.066 bytes, SHA-256
  `83842f25da2a2b682e3386903cfda7a0d6aa075dcd20eb9a2e3191168a8fef90`.
- Verificación de versiones: Temurin `17.0.20.1`, Gradle `7.3.3`, NDK
  `24.0.8215888`, CMake `3.22.1`, platform `android-34`. El NDK ocupa
  aproximadamente 2,2 GiB expandido y CMake 61 MiB; debe conservarse como
  advertencia de consumo frente al límite de descargas autorizado.
- Inventario de licencias, fuentes y procedencia de binarios pendiente; no
  redistribuir artefactos upstream como entrega auditada.

## Postcheck del APK

El mensaje `POSTCHECK ERROR: APK has no arm64-v8a native ABI` no se reproduce
con el APK inspeccionado: el ZIP no necesita contener una entrada de directorio
literal `lib/arm64-v8a/`, solo sus ficheros. La salida tabular de `unzip -l`
tampoco es una interfaz estable. La expresión heredada del checkout actual
devuelve 0, así que el mensaje provino de una variante previa del chequeo o de
un regex que trataba el prefijo como entrada/directorio; no hay evidencia de
que faltase el ABI en el APK.

El bootstrap usa ahora `aapt dump badging` para paquete, SDK y
`native-code: 'arm64-v8a'`, y `unzip -Z1` para comprobar nombres exactos y
tamaño no cero. También valida permisos requeridos y ausencia de permisos de
almacenamiento, `apksigner verify`, el digest SHA-256 del certificado, y los
assets `rootfs.tzst`, `rootfs_patches.tzst`, ambas sondas y `NOTICE`. El APK
existente inspeccionado reportó 35 bibliotecas `arm64-v8a`, paquete
`com.wowforever.runtimeprobe`, compile SDK 34, min SDK 26, target SDK 28 y
firma v2 válida.

## Próximo gate

1. Conservar cambios externos e inspeccionar diffs canónicos antes de reconstruir
   la receta. Validar aplicación en base limpia, no deducirla del checkout sucio.
2. Inventariar tamaños y hashes de JNI/assets necesarios antes de ampliar descarga.
3. Mantener este baseline sin cambios y usarlo como referencia antes de añadir
   lógica del prototipo; no migrar el toolchain sin evidencia.
4. Solo tras build correcto revisar ejecución, procedencia de logs, lifecycle y
   exportación, y entregar con fuentes/parches/avisos correspondientes.

Se detiene la ampliación funcional hasta superar este gate. No se ha probado
runtime ARM64, ninguna sonda bajo este runtime ni cliente WoW.
