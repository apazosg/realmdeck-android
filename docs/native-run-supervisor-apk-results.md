# Supervisor nativo desde APK56: UNKNOWN antes de READY

2026-10-09. Operación nativa exclusiva aprobada mediante «sigue porfavor» y
continuaciones posteriores. **NO-GO para ownership/cancelación desde el APK**:
el primer RUN real terminó antes de READY. No se ejecutó Wine ni WoW.

## Candidato construido y conservado

| Artefacto | Bytes | SHA256 |
|---|---:|---|
| APK56 / `0.2.32-automated` | 370931159 | `aff5f8a8d72f0f348c771d53480908a8dcae9a981e0ad1db1f6e79c8be09ae6c` |
| `libforever_run_supervisor.so` | 27224 | `c870323b5cc84305d3082f445ba8a6ee299a8086e78f173cdd07547cd7402460` |
| `libforever_run_fixture.so` | 12784 | `7f1b6d256939c9a7310475c3d3bc7dbdfeb22ad46e3de4270413db05a663e67f` |
| Manifest nativo | 3662 | `e51242cfbdc5b27678dcfe63aacd446b3065a8c8e6666914352bda94e059bf8c` |

Package `com.wowforever.arm64probe`, certificado debug
`2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b`,
AA64/min29/target36. ELFs PIE/libc-only/LOAD16KiB; extracción auditada y ejecución
desde nativeLibraryDir, no binario writable ni fallback run-as.

Retención externa:
`/home/adrian/wowforever-artifacts/arm64-prototype/delivery/retained-0.2.32-code56.apk`.
Auditoría retenida `automated-0.2.32-code56-validated-apk-audit.json`: PASS. Recibo
`retained-0.2.32-code56-receipt.json`, copias de ambos ELF y manifest, fsync y hashes.
El campo physicalTrialExecutedAtRetention=false describe la fecha de retención,
no el estado posterior a la prueba.

Comparación independiente con APK55 retenido: 161→164 assets/libs, **160 idénticos**,
sin removals; solo se añaden dos ELF/manifest y cambia provenance. Wine/WCP/PanVK/
noGL/JNI/PE/client/catalog intactos. Entrega normal33 sin publicar ni modificar.

## Implementación y validación

- Contrato congelado: `scripts/diagnostics/android-process/NATIVE-SUPERVISOR-IPC.md`.
  Process Java-owned, READY antes de START, dos fases, ACK de nacimiento/adopción,
  señales solo hijos directos/adoptados no reaped; testigo Java de otra rama.
- Actividad DUMP-protected en `:run_supervisor`; inicialización normal de GameNative,
  MainActivity/aliases y entradas heredadas incompatibles deshabilitadas para56.
  No XEnvironment, prefix, compiler repair ni autenticación en este control.
- Readers separados por proceso, startup asíncrono, jobs con finalización real,
  stdin envenenado tras fallo, terminal durable antes de liberar ownership.
  UNKNOWN permanece bloqueado aunque terminen jobs locales.
- `supervisor_failure` que destruye la raíz queda solo host/JVM. Los cinco escenarios
  físicos aprobados eran normal, cancel_before_start, cancel_fork, eof, invalid_protocol.
  Solo normal alcanzó un RUN: **cancelación y faults físicos no verificados**.

Evidencia de validación, sin sumar rondas solapadas:

- C/IPC y packaging verificados en rondas dirigidas; exit no cero nunca PHASE_DONE PASS.
- 23 casos JVM resueltos por rondas: 21 en filtro completo y dos negativos luego;
  no una ejecución única final de los23 ni suite JVM completa.
- Receta fresh/idempotent final: **96 inputs equivalentes**.
- Build Gradle offline/JDK17/CPU2/Xmx4096m/1worker, rerun solo mergeAssets: PASS.
- Auditoría APK final y comparación55: PASS.
- Collector host, última suite completa tras corregir foco inicial: **63 PASS**.
  Revisiones independientes anteriores condicionaron GO a checks y packaging.
  Los cambios posteriores de orquestación se probaron con fakes, no equivalen a
  nueva revisión del contrato nativo ni a capacidad física positiva.
- No suite global; fallos históricos de ejecuciones JVM amplias siguen fuera de
  esta validación. Warnings AGP8.8/compileSdk36 y extractNativeLibs no se ocultaron.

## Entrada USB: fallos preservados, sin entrada en UI ajena

Todos los controles usaron SDK adb con serial fijo `DEVICE_SERIAL`, xaga/
22041216G/Xiaomi/API34, UID10330, hashes instalados exactos y boot verificado.
Los primeros intentos no alcanzaron consentimiento ni RUN. Se corrigieron causas
concretas del host, conservando las evidencias anteriores:

1. dumpsys muestra una Signature corta, no el SHA256 del certificado. La firma se
   liga por auditoría del APK y SHA256 exacto de base.apk instalado.
2. exec-out mezclaba un error de head por current.json inexistente con rc0. Lectura
   privada ahora usa shell-v2 sin PTY, status y stderr separados.
3. Evidence.action tenía colisión entre parámetro reason y detalle reason. Un
   intento parcial quedó sin seal; cierre suplementario, sin inventar result.json.
4. Se conserva un único contexto AOA2/HID a través de wake/dismiss/Activity/consent.
   Ese arreglo por sí solo no resolvió la entrada; no atribuirle causalidad total.
5. Una comprobación posterior vio Activity propia reanudada, pero móvil dormido y
   keyguard visible. RC0 de dismiss no demuestra estado. Polls acotados verifican
   Awake y KeyguardServiceDelegate mostrando=false/secure=false antes de Activity.
   Android real omite ':' en el heading; ambas formas exactas están cubiertas.
6. UI propia inicial tenía cero nodos focused y botones ALLCAPS. Se permite Tab de
   adquisición solo en jerarquía exclusivamente propia y estado inicial conocido;
   Space/Enter siguen exigiendo foco único en checkbox/start. No click arbitrario.

Un timeout/error transitorio de policy se conservó como NOT_MEASURED; no se relajó
la política ni se reenvió START. No XML/imagen/contenido de otras apps persistido.

## Primer RUN físico

Preflight: `native56-preflight-20261009T172958Z-9a9b438a12`.
Boot: `3370c8dc-85b4-4975-91a6-352d14271fbd`.
Evidencia bajo `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`:
`native-supervisor-trial-a534f2fd28544f4bba0c45b2e349a26d`.

RUN `NS-30823b6b5acd81b24830be89b809141d`, scenario normal. Una secuencia verificada
Tab/Space/Tab/Enter, nunca reenvío. Supervisor PID14233/startTicks10551/PPID12689,
UID4=10330. Resultado app/native **UNKNOWN**, nativeExit2, echild=false,
witnessProof=UNPROVEN. Solo intent RUN; **cero READY/SPAWN/ADOPT/EXIT**; stdout,
stderr y streams del testigo vacíos. `/proc/14233/stat ENOENT` se obtuvo tras la
salida: no identifica por sí solo la causa nativa.

El collector capturó antes de force-stop del paquete propio. Seal original
preservado. No repetir sobre ese UNKNOWN ni borrar current.json para continuar.

## Diagnóstico reproducido: `/proc/self/exe` bajo cargador explícito

`native_run_same_parent_fixture()` en native_run_common.h314–342 exige basename
`libforever_run_supervisor.so` en `/proc/self/exe`. Pero Java lanza:

```text
/system/bin/linker64 <supervisor> --run <NS> --nonce <nonce> --fixture <fixture>
```

El ejecutable kernel es el cargador explícito. Reproducción host con los sources
**sin modificar**: ejecución directa emite READY; ejecución mediante ld-linux sale2
con stdout vacío. Un probe separado verifica self/exe=supervisor/probe en directo
y self/exe=ld-linux con cargador. Es una incompatibilidad reproducida de la guard,
no prueba de que Android niegue prctl ni una conclusión sobre Wine.

Reproducción retenida:
`/home/adrian/wowforever-artifacts/work/native56-loader-reproduction-0cbd7fd0-246c-4b08-8d7b-3eacfc72f752.json`,
SHA `a0cec28ad077c0b985689e79de42bdc3823aef923d83cdd6f27eefa32573384a`.
No se capturó self/exe del PID Android antes de que saliera; la reproducción host
confirma el defecto de código, no todos los permisos del siguiente candidato.

## Cierre y siguiente gate

Post-collection: current UNKNOWN estable, APK56 hash intacto, compiler AA64
`c374b107…ed3add` y backup original54 `06dc23aa…b58e52` con stat/hash estables.
Muestra propia solo ps: **no prueba de contención**. post-trial-state y closure-seal
añadidos sin sobrescribir seal/result originales. Un helper de preservación repitió
force-stop del paquete después de recoger evidencia, pese a su asignación solo
lectura; `closure-action-clarification.json` liga esa desviación explícitamente.
No reset, nuevo RUN ni afirmación de quiescence por ello.

El intento parcial `native-supervisor-trial-a5abbc0eb6744e9e943edcb3a9a67e07` tiene
closure-note/supplemental-seal: PRELAUNCH_ABORT, sin nuevo result.json ni prueba nativa.

Plan de esta operación archivado: `.agents/planning/done/native-run-supervisor-apk-plan.md`.
La continuación loader fue autorizada y cerrada en
`.agents/planning/done/native-supervisor-loader-plan.md`: APK57 falló en política
Java antes de crear Process. Ver `docs/native-supervisor-loader-results.md` y la
propuesta de disposición `.agents/planning/done/native-prelaunch-disposition-plan.md`.
Su ejecución posterior y GO app-domain están en `docs/native-prelaunch-disposition-results.md`.
El UNKNOWN56 se conserva; no cambiar su resultado ni inferir ECHILD retroactivo.
Wine, prefijo/wineserver, identidad PE, visual y WoW siguen gates independientes.
