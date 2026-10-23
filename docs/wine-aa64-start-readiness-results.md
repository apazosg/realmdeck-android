# Wine AA64: arranque estable y frontera real de ownership

Estado 2026-10-09. Continúa `wine-aa64-observation-results.md`. El usuario aprobó
la espera de UI y levantó el límite de número de pruebas en el POCO. Se mantienen
los presupuestos por ejecución, aislamiento y criterios de evidencia.

## Cambios y verificación

- `scripts/diagnostics/usb_forever_trial.py`: espera top propio sin input,
  máximo 8s/3dumps, deadlines de operación y reserva de cleanup. Cada dump usa
  ruta UUID, clear previo y eliminación exacta en finally; cleanup fallido o
  resultado tardío no autoriza entrada. Timeouts cooperativos, no hard preemption.
  Se eliminó también el único path fijo legado del harness
  `/data/local/tmp/wowforever-usb-lab.xml`, confirmando su ausencia sin leer XML.
- Consentimiento y prepare siguen revalidándose antes de teclas existentes;
  no clicks, dismiss extra, teclas genéricas ni repetición inline de Enter.
- Tras diagnóstico del primer control, los candidatos de la misma prioridad se
  ordenan por menor número de muestras antes del corte de seis. Los hints de nombre
  nunca son identidad; se conservan UID/stat antes/después, cuotas y proof estricto.
- Suite dirigida del builder: 57 PASS (readiness, observation, streams, sampling,
  graphics log); revisión independiente GO. Tras el ajuste fresh-first: 28 PASS
  (`test_usb_forever_observation.py`, `test_usb_forever_probe_sampling.py`). Los
  conjuntos solapan; no sumar como tests distintos ni afirmar suite completa.
- Sin rebuild/install/download ni cambios de APK55, PE, Wine, DXVK, PanVK, shaders,
  pausa, compiler repair o analizador. Autenticación/parcheo/WoW siguen OFF.

APK retenido: `0.2.31-automated`, code55, 371186229 bytes,
SHA-256 `4818835af27aad8c9196af15976d9debeef933193a03f8cb360ceb35bd60d514`.
PE `GraphicsProbeReadback-ARM64.exe`, 96768 bytes,
SHA-256 `80c1c260d7781bda73e3d5552c7ca730e72382288a9256b4a78c7899d3f0c3de`.
Preflight verifica artefactos retenidos, APK instalado, dispositivo/UID, reboot
nuevo y hashes de compiler/backup antes de cada control.

## Dos controles físicos, no un resultado reciclado

Raíz externa: `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`.
Solo serial `DEVICE_SERIAL`, package `com.wowforever.arm64probe`, UID10330.
Cada invocación: host120s + cleanup20s, un prepare/Enter y evidencia separada.

| Control | Primero | Fresh-first |
|---|---|---|
| Directorio | `trial-20261009T092605Z-8ce4a59916` | `trial-20261009T093649Z-edc75efc25` |
| RUN | `RUN-1791537989019-51573685771` | `RUN-1791538631990-49550235694` |
| Boot | `c9101359-b5c8-41b1-9f81-aa448d0441ab` | `2ec87938-53f8-4dc4-81fd-121f13b5f017` |
| UI ready | 5734ms / 2dumps | 5539ms / 2dumps |
| Root Wine / startTicks | 13499 / 6451 | 13409 / 6322 |
| Candidato sonda | 13939, no maps capturados | 14064 / startTicks6831 / PPID1 |
| Muestras guardadas | 30 | 28 |
| Readbacks / Present | 4 VERIFIED / 30 S_OK | 4 VERIFIED / 30 S_OK |
| Estado global | CONTROL_INVALID | CONTROL_INVALID |

En ambos: clear76800/colored0/FNV3375453637; offscreen_draw y backbuffer_draw
clear57984/colored18816/FNV2943428253. Map/staging/RTV/texture S_OK, pData válido,
RowPitch1280, cinco muestras coherentes, DeviceRemovedReason S_OK antes de Present.
Compiler carga y shaders vs_5_0/ps_5_0 compilan. No timeout nativo registrado.

Las dos capturas finales pasaron guards propios, pero muestran el launcher tras
terminar Wine, con `termination_unknown`, **no el triángulo**. Screenshot guardado
no equivale a prueba visual. No render WoW, login ni gameplay probado.

## Pérdida de cobertura reproducida y corregida

Primer control: `ps-raw-20.txt` sí enumera PID13939 NAME=wine/PPID1. Logcat del
mismo PID contiene comm GraphicsProbeRe y carga privada PanVK. En
`process-observations.jsonl:74–82`, seis runtime con seen1–2 fueron elegidos antes
del candidato seen0, rechazado por selection-cap. Esto sí demuestra el descarte
host; no prueba su PE ni ancestry. Fresh-first preserva prioridad de hints y
selecciona dentro de la misma prioridad primero los menos observados.

Segundo control: PID14064 aparece seleccionado primero en sample22 y pasa
UID4/stat antes/después. `process-snapshots.jsonl:16` liga maps109143 bytes a SHA
`5c5d020b077d5b95632556c4b9c7360e831d71e0caac975c7405351c60530447`.

## Qué queda demostrado y qué no

En `maps-22-14064-1.txt:434–440`, PanVK tiene mapping RX en línea436, ruta privada
del RUN, dev fe:37 (65079), inode2089381. Coincide con `driver-identity.json`,
archivo estable UID10330/SHA `5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`.
Es evidencia kernel de ese driver mapeado en el candidato capturado, no una
cualificación completa de la sonda ni fallback del sistema.

Dos blockers independientes:

1. `maps-22-14064-1.txt:85–89`: regiones 0x140000000, incluida RX
   0x140001000–0x140007000, **anónimas** (00:00/inode0), sin ruta canónica del PE.
   La forma/tamaño/dirección, el nombre y el archivo de entrada pinneado no prueban
   por sí solos qué PE está ejecutándose en ese PID.
2. UID10330/startTicks6831/PPID1 son estables antes/después. El root13409 tiene
   PPID6441; no hay cadena kernel conservada entre root y candidato. Estar en el
   mismo UID no autoriza adoptarlo ni señalarlo como hijo del RUN.

El analizador intacto conserva `reportedStatus=PASS` nativo y
`status=CONTROL_INVALID`: `no same-process PE plus pinned PanVK mapping proof`.
Top-level driverMappingObserved/apiQualificationObserved/visualPresentationObserved
siguen false; el mapping PanVK parcial está en los registros, no sustituye el gate
compuesto PE+driver+ownership. Offscreen/backbuffer/API Present positivos, wow=false.

## Preservación y límites

Cada control tiene `wine-qualification.json`, transaction exacta REUSED,
`post-trial-state.json`, manifest SHA/size `evidence-sha256.json` y provenance.
Sellados 85 y 86 archivos respectivamente, sin sobrescribir artefactos previos.
Primer control: el provenance se tomó después del arreglo de scheduling; la
aclaración adicional `collector-provenance-clarification.json` liga el seal original
y distingue source-at-seal de código ejecutado. El segundo preflight sí conserva
`collectorSha256BeforeTrial`, para ligar el source ejecutado al source-at-seal.

Compiler AA64/source c374b107... y backup original06dc23... siguen estables con
UID10330/mode771/nlink1; conservar reparación y backup. Tras cada force-stop solo
se observó el ps de colección, pero eso **no prueba contención/quiescence kernel**.
Reboot obligatorio antes de otro Wine. No más controles idénticos: las incógnitas
ya requieren ownership del runtime e identidad del PE, no muestreo adicional.

## Siguiente gate hacia el juego

La investigación del runtime identifica root `Process` más sampling de children,
no supervisor/subreaper por RUN. La ruta llamada direct sigue prefijada con
`wine explorer /desktop=...`; eliminar Explorer no garantiza contención.

La propuesta arquitectónica ya aprobada está cerrada:
`.agents/planning/done/wine-runtime-ownership-plan.md`. El microcontrol nativo
no-root pasó; ver `docs/wine-runtime-ownership-capacity-results.md`. Se diseñó la
frontera de integración en `docs/wine-run-supervisor-design.md`, sin Wine ni nuevo
APK durante ese diseño. El control posterior Java/packaging/IPC fue aprobado y
cerrado en `.agents/planning/done/native-run-supervisor-apk-plan.md`: APK56 UNKNOWN
antes de READY, ver `docs/native-run-supervisor-apk-results.md`. Corrección loader
cerrada en `.agents/planning/done/native-supervisor-loader-plan.md`, ver
`docs/native-supervisor-loader-results.md`: no integración Wine aprobada. No se
rebaja el analizador, no se instala un subreaper global en la app, ni se adopta o
mata por UID/nombre/tiempo. Visual y WoW siguen gates independientes.
