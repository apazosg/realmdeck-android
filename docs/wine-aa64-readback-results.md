# Wine AA64: readbacks y Present positivos; ownership/visual no cualificados

2026-10-09. Plan aprobado por el usuario («si hazlo») y cerrado:
`.agents/planning/done/wine-aa64-readback-plan.md`. Una variante propia y un único
control USB en POCO xaga `DEVICE_SERIAL`, UID10330, Android14/API34/Mali-G610.
**Terminal nativo PASS; veredicto global CONTROL_INVALID.** No WoW/login ni APK
jugable. Las lecturas positivas no se convierten en atribución de driver/visual.

Observación host posterior: `docs/wine-aa64-observation-results.md`. El collector
ya conserva enumeración/rechazos acotados, pero el control siguiente se abortó
antes de Wine por el guard de foco; no altera estas mediciones históricas.

## Variante y candidato preservados

- Code55/`0.2.31-automated`, samepackage/firma/AA64/min29/target36. APK371186229bytes,
  SHA `4818835af27aad8c9196af15976d9debeef933193a03f8cb360ceb35bd60d514`.
- PE fijo `GraphicsProbeReadback-ARM64.exe`,96768bytes, SHA
  `80c1c260d7781bda73e3d5552c7ca730e72382288a9256b4a78c7899d3f0c3de`;
  manifest SHA `92910f6327175e1b144ffe8b53484dd714b972983adbde6eeaa098b263ad7a7b`.
  Los PE anteriores89600/a19e... y90112/795a... permanecen intactos.
- Compilación AA64 `-Wall -Wextra -Werror`, receta fresca/idempotente87inputs,
  build offline JDK17/1worker/CPU2/Xmx4096m y audit del APK real PASS. Se forzó solo
  `mergeModernDebugAssets --rerun`, no todas las tareas.
- Checks dirigidos:61Python sonda/captura/análisis antes del ajuste final host,
  y54Python captura/análisis después de añadir los negativos timeout+PASS y health
  posterior a Present. Ocho JVM gráficos del build final y23CompilerPrefixRepair
  aislados pasan. Los filtros policy/PanVK también pasaron en rondas previas.
  **No suite JVM completa verde:** una ronda amplia no solicitada agotó300s y
  mostró ClassCastException/IllegalStateException; no se demostró que fueran flaky
  ni se investigó toda la suite RuntimeBootstrap.
- Comparación SHA independiente contra54:159→161miembros assets/lib,158idénticos,
  sin removidos. Solo se añaden PE+manifest y cambia provenance. Proton/DXVK/WCP,
  wrapper, noGL, PanVK, bibliotecas nativas y cliente/catálogo permanecen idénticos.
- Copias600 con SHA/fsync en `/home/adrian/wowforever-artifacts/arm64-prototype/delivery/`:
  `retained-0.2.31-code55.apk`, `automated-0.2.31-code55-validated-apk-audit.json`,
  PE/manifest retenidos y recibo. Los audits provisionales de55 son históricos,
  **no** describen este APK. Entrega normal code33 sin cambios/publicación.

## Control único y compiler reutilizado

Se instaló el APK propio preservando datos y se reinició Android antes de Wine.
Preflight externo `readback-preflight-20261009T071728Z-af66c2e0ae`: boot nuevo
`6147a0fe-087d-43c5-9e66-82206d8995eb`, muestra UID10330 solo con el `ps` recolector,
compiler47 AA64 y backup original54 con SHA intactos. Un preflight anterior se
detuvo **solo leyendo** porque el guard temporal esperaba el nombre descriptivo
Xiaomi22041216G en `ro.product.model`; el valor real22041216G y fabricante Xiaomi
se validaron por separado. Ese STOP no instaló, reinició ni lanzó Wine.

Trial: `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`
`trial-20261009T071857Z-0591b198e4`, RUN `RUN-1791530356088-76612335773`.
Host10925; rootWine17387/startTicks9033; el wrapper salió0/17012ms. Se mantuvo
explorer `/desktop=shell,1280x720`, driveA/cwd/prefijo, Proton11.0-90624-arm64ec,
DXVK2.4.1-wow-aarch64-test, PanVK beta17/5d9fdb88..., las tres variables Adrenotools,
noGL y `d3dcompiler_47=b`. Sin cambio de shaders/geometría/viewport/culling/blend/
depth/flags async/GPL ni otro DLL. Memoria/auth/WoW OFF.

El recibo del RUN55 es **REUSED**, no otro swap: compiler47 target/source1114112
bytes/c374b107..., UID10330/modo771/nlink1, mtime original conservado. El backup
original54 sigue4453888bytes/06dc23...; se comprobó después del stop. El recibo y
la colección readonly están guardados en `compiler-prefix-transaction-code55.json`
y `post-trial-state-code55.json`. No rollback ni prueba de corte de energía.

## Mediciones reales

`graphics-probe.jsonl`:48filas/10435bytes, SHA
`03671a8c8d7933221837012084904b31d9c985e6edbdd22cc544c9f93e2d2cfc`.
Compiler LoadLibrary/GetProcAddress pasan, shaders vs_5_0/ps_5_0 COMPILED,
D3D11 S_OK/11_1 y swapchain320x240/dos buffers creados.

Pausa READY→RESUMED:5000ms solicitados,5001ms medidos, `pauseOverrun=true` por1ms,
RESUMED a7353ms del reloj de la sonda. Es límite **cooperativo**, no garantía de
preemption/scheduling; no hubo `probe_budget/TIMEOUT`. Este reloj comienza dentro
del PE y **no** debe sumarse al spawn del wrapper para fechar los eventos del PE.

| Fase | Estado | Clear / coloreados | FNV-1a RGBA | elapsedMs |
|---|---|---|---|---|
| offscreen_clear | VERIFIED | 76800 / 0 | 3375453637 | 7458 |
| offscreen_draw | VERIFIED | 57984 / 18816 | 2943428253 | 7493 |
| backbuffer_clear | VERIFIED | 76800 / 0 | 3375453637 | 7542 |
| backbuffer_draw | VERIFIED | 57984 / 18816 | 2943428253 | 7558 |

En las cuatro: recursos/RTV/staging/Map S_OK, CopyResource `VOID_SUBMITTED`,
pData válido, RowPitch1280 y320x240. Cinco puntos fijos del clear devuelven
RGBA(10,10,20,255). Ambos dibujos dan centro(160,120)=(140,84,83,255) y
(240,180)=(58,207,42,255); sus counts/hash/muestras coinciden. Clear uniforme no
se cuenta como triángulo. DeviceRemovedReason posterior S_OK **antes** de30filas
Present1…30, todas S_OK; terminal PASS/d3dTriangleResulttrue/logDurabletrue.

Esto distingue el triángulo de un clear-only. Pero no demuestra la causa del
FAILED de54: la variante añadió pausa, fases offscreen y orden de llamadas. No
atribuir la mejora a una sola variable, shader/driver, warmup o sincronización.

## Calificación y límite de observabilidad

`wine-qualification-code55.json`, ejecutado una vez: **CONTROL_INVALID**,
reportedStatusPASS, único error `no same-process PE plus pinned PanVK mapping proof`.
Identidades de archivos PE/PanVK estables y pinneadas, pero ninguno de24maps
conservados muestra ambos RX en un mismo PID/startTicks y vínculo al root.

- Raw witnesses: D3DDeviceObserved/offscreenReadbackObserved/
  backbufferReadbackObserved/apiPresentObserved=true.
- Calificación: driverMappingObserved/apiQualificationObserved/
  visualPresentationObserved=false; wow=false. El campo APItrue del terminal
  nativo no reemplaza el gate global de identidad. Aun con ownership íntegro,
  faltaría evaluación visual independiente para un PASS global.
- Logcat956–1005 apunta a **PID18206**, comm `GraphicsProbeRe`, cargando el PanVK
  privado del RUN y reportando Mali-G610. PID18115/explorer también lo carga.
  Son indicios, no sustitutos de UID/stat/PPID/startTicks/maps del PE objetivo.
  No se capturó ninguno de esos dos PID; gamePid/gameStartTicks quedan null.
- PID18206 aparece09:19:35.341 y sigue al menos09:19:43.397 en logcat localUTC+02,
  solapando muestras23/24. No atribuir todo el miss a un proceso demasiado corto.
  El collector no guardó el `ps` bruto ni motivos de rechazos, así que no se sabe
  si lo enumeró, priorizó o rechazó por UID/stat/maps.
- El corte a6candidatos precede a descartar `seen>=3`; podría causar inanición de
  candidatos posteriores. **Hipótesis, no causa demostrada.** El límite global48
  no se agotó: total24. Si NAME fuese GraphicsProbeRe, la prioridad ya sería0.
  Mapas anónimos de otros Wine PID tampoco prueban cómo mapea el PE objetivo.
- Screenshot final=false, sin PNG: no se registró cuál guard (focus propio,
  PNG/bound o segundo focus) falló. X-window/Present/fps no se convierten en visual.

## Cierre y decisión

One-shot prepare documentado; captura/agent-stop del paquete propio completos
a07:20:41UTC. El estado final permanece EXITED/termination_unknown/
quiescenceConfirmedfalse. Post-readonly observa solo el `ps` propio; ni eso ni
force-stop prueban contención kernel: **reboot obligatorio antes de otro Wine**.
Se sellaron42archivos mediante `evidence-sha256-code55.json`, sin borrar evidencia,
datos o backup. No segundo intento bajo este plan.

Se conserva el avance de los readbacks/Present; **no-go para aceptación global,
WoW o release jugable**. La observabilidad de selección/rechazos está implementada
en `.agents/planning/done/wine-aa64-observation-plan.md`, pero su control se abortó
antes de Wine por foco inicial no propio. Ver `docs/wine-aa64-observation-results.md`.
La recuperación prelaunch posterior ya está cerrada:
`.agents/planning/done/wine-aa64-start-readiness-plan.md`. Ver
`docs/wine-aa64-start-readiness-results.md`: dos controles más verificaron gráficos,
y el segundo capturó PanVK RX en el candidato estable, pero con PE anónimo/PPID1.
La capacidad subreaper posterior pasó y su plan está cerrado en
`.agents/planning/done/wine-runtime-ownership-plan.md`; ver
`docs/wine-runtime-ownership-capacity-results.md`. El control APK posterior está
cerrado en `.agents/planning/done/native-run-supervisor-apk-plan.md`: UNKNOWN antes
de READY (`docs/native-run-supervisor-apk-results.md`). Corrección loader cerrada en
`.agents/planning/done/native-supervisor-loader-plan.md`, todavía NO-GO app-domain:
`docs/native-supervisor-loader-results.md`. Sin cambio de driver/DLL ni relajación
de evidencia, todavía sin integración Wine.
