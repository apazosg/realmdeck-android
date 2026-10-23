---
id: wine-aa64-readback-2026-10-08
state: done
date: 2026-10-08
---

## Summary

Code54 carga el compiler47 AA64 y compila shaders, pero termina en readbackFAILED:
Map S_OK, coloredPixels0, hash3375453637, consistente con solo el clear de fondo.
No llama Present. La causa de Draw/pipeline/backbuffer/copia no está demostrada;
también falta el proof same-process PE+PanVK/rootUID/startTicks. Ver
`docs/wine-aa64-shader-compiler-results.md`.

Se propone cambiar **solo la sonda propia de diagnóstico**, no el driver/runtime,
para comparar una textura render-target independiente con el backbuffer. Como
los planes anteriores congelaron el PE/shaders y limitaron la prueba física,
este alcance requiere aprobación antes de implementar o repetir Wine.

## Content

### Baseline protegido

- Solo POCO xaga `DEVICE_SERIAL`, package `com.wowforever.arm64probe`, UID10330,
  Android14/API34/Mali-G610. No Pixel, root, cuentas, permisos ni apps ajenas.
- Preservar APK54/370710845 bytes/SHA
  `cee6ffaf9f4a6b7f28b402139dcbd4c7e6d1022e77e6358136697a94370005d9`, su audit y
  `trial-20261008T220723Z-00219df91e` antes de otro build. Ya están retenidos.
- Mantener PanVK beta17/SHA5d9fdb88..., Proton11.0-90624-arm64ec,
  DXVK2.4.1-wow-aarch64-test AA64, wrapper/Xlib, las tres variables Adrenotools,
  noGL, startup/audio/FEX/display/desktop y entorno final igual a54.
- Mantener compiler47 AA64/c374b107... y `d3dcompiler_47=b`. La transacción de54
  y backup x64/06dc23... quedan conservados en teléfono/host. El próximo RUN puede
  verificar/reutilizar el AA64 exacto; no otra reparación, DLL externa o rollback.
- Memoria Forever/login/WoW siguen OFF. No alterar anuncios Vulkan, BC/R2,
  minApi35 publicado ni flags DXVK/async/GPL para intentar ocultar el fallo.

### Variante discriminante de la sonda

1. Generar con la toolchain ya existente una variante AA64 propia y versionada,
   `GraphicsProbeReadback-ARM64.exe`, con manifest/pin nuevos. Conservar sin
   overwrite los dos PE anteriores y sus evidencias. No descargar/recompilar
   Wine, DXVK o Mesa ni añadir librerías/runtime alternativo.
2. Conservar geometría, shaders, feature-level request, stride/input layout,
   viewport, culling/blending/depth defaults y swapchain de54. Sin CullNone,
   invertir vértices, Flush/query añadido o warmup de Draw en este control:
   esas variables solo se considerarían después de la comparación.
3. Tras cargar módulos y crear D3D/shaders, emitir marker y una pausa cooperativa
   máxima5s, incluida en los30s totales, para muestrear maps de la sonda propia.
   No ptrace, memoria de otro proceso, señales nuevas ni sustitución de ancestry
   por coincidencia UID/nombre. No tocar el gate del launcher por la pausa.
4. Crear textura320x240/R8G8B8A8_UNORM/DEFAULT/RENDER_TARGET independiente del
   swapchain. Registrar HRESULT de texture/RTV. Reutilizar el mismo pipeline:
   - clear-only y staging-readback, para comprobar traslado del color esperado;
   - Draw del mismo triángulo y staging-readback;
   - repetir clear/Draw/readback sobre el backbuffer existente, sin mezclar RTVs.
5. Cada lectura registra fase, Map/HRESULT, pData-valid, RowPitch, dimensiones,
   conteo de píxeles del clear/coloreados, FNV y muestras RGBA acotadas de puntos
   fijos. Registrar DeviceRemovedReason posterior. Solo texturas propias de la
   sonda, no dump de memoria/juego ni screenshot de apps ajenas.
6. Mantener el criterio del triángulo real: Map válido y >=128píxeles coloreados;
   clear uniforme o shader COMPILED no cuentan como dibujo. Diagnósticos nuevos
   no producen PASS por sí solos. Si el backbuffer no verifica, 0Presents y fallo
   explícito; si verifica, conservar30Present S_OK y captura visual separada.

### Integración, interpretación y límites

- Selección del PE nuevo únicamente en el profile debug gestionado/RUN activo,
  con filename/size/SHA/machineAA64/manifest auditados y un nuevo tuple de versión.
  Actualizar consumidores y tests de pins/metadata; ninguna ruta arbitraria por
  extras o ampliación de la autorización de reparación del prefijo.
- Preservar el analizador estricto para PE+PanVK RX en mismo PID/startTicks/UID,
  RUN y vínculo a root. Los nuevos eventos se reportan por fase sin reutilizar
  la aceptación nativeAndroidWSI ni aceptar mapas host como Wine.
- Clear-readback fallido: localizar copia/RT/Map antes de culpar a Draw. Clear
  válido pero ambos dibujos fallidos: queda pendiente pipeline/shader/estado,
  no causa driver demostrada. Offscreen positivo y backbuffer fallido acotan el
  siguiente diagnóstico al camino swapchain/RT/WSI, no prueban presentación.
- Un único intento USB AOA normal después de **reboot Android** por el RUN54;
  verificar bootId nuevo y ausencia muestreada del UID propio antes de intentarlo.
  Hostdeadline<=120s, probe<=30s, cleanup propio bounded y evidencia externa antes
  de UI. No force-stop como prueba de contención ni retry automático.
- No cambiar cuotas/budgets actuales, toolchains o descargas. Probes/logs/backup
  fuera del repo; no tocar instalador/cliente activo ni borrar cachés/datos.
- Cualquier nueva dependencia, pin desconocido, IO/cancel/ownership incierto o
  cambio de alcance => STOP. No encadenar swaps, drivers o nuevas variantes físicas.
- No avanzar a WoW/login ni publicar APK jugable por un control parcial.

## Implementation / Action Items

- [x] 1. Preservar y analizar el control54, recibo y backup; identificar límites.
- [x] 2. Aprobar esta variante de sonda y un único control físico descrito.
  Usuario: «si hazlo», después de presentar variante, límites y prueba única.
- [x] 3. Implementar variante/eventos/consumidores con el baseline intacto.
- [x] 3a. Corregir antes de física los gaps detectados en review: captura/deadline
  por tuple55, fases de readback estrictas, hints del PE nuevo y guards de Present.
- [x] 4. Tests dirigidos de sonda/encoder/identidad/análisis/captura/presupuestos;
  build offline, receta fresca/idempotente y audit del APK real, mismafirma/package.
- [x] 5. Reiniciar el POCO y ejecutar una vez; preservar maps/readbacks/logs/captura.
- [x] 6. Clasificar cada fase sin relajar proof ni presentación; cerrar decisión.

## Open Issues

- **Alcance aprobado:** únicamente la variante de sonda/lecturas/pausa y un
  control físico después de reboot. No amplía la reparación de DLLs ya cerrada.
- El hash32 compatible con clear no establece causa única; la prueba es
  diagnóstica, no un arreglo de driver ni promesa de frame.
- La pausa mejora la oportunidad de captura pero no garantiza ancestry Linux
  completa bajo Wine/explorer. Si falta, conservar CONTROL_INVALID/UNKNOWN y
  proponer por separado otra prueba de ownership, no inventar una equivalencia.
- La pausa y offscreen previo alteran tiempos/orden de inicialización. Reportar
  ambos explícitamente; un cambio de resultado no prueba causalidad por sí solo.
- **Reevaluación antes de física:** la primera implementación compila y sus
  tests dirigidos pasan, pero review encontró colecciones que aún excluían55 y
  validación insuficiente de fases/Map/copia. No se ha usado el intento aprobado.
  Se corrigen esos gaps y los guards cooperativos de Present, sin cambio de pila.
- **API no equivale a visual:** `reportedStatus` conserva el terminal de la sonda;
  una API íntegra puede registrarse como `apiQualificationObserved=true`, pero
  el veredicto global permanece `INCOMPLETE` si falta evaluación visual. La
  variante no añade aceptación automática de screenshots/ROI; visual y WoW no
  se infieren de readback/Present. `wow=false` siempre. Es corrección del límite
  ya exigido, no autorización de un nuevo sistema de prueba visual.
- **Prerrequisitos conservados:** este control compara offscreen/backbuffer una
  vez creados ventana/swapchain/shaders, como54. Un fallo anterior conserva su
  terminal/etapa y no verifica offscreen. No se reordena la creación WSI para
  eludirlo; ese aislamiento adicional quedaría fuera de este intento aprobado.
- **Verificación parcial JVM:** el builder ejecutó además una suite amplia no
  solicitada, que agotó300s y mostró ClassCastException/IllegalStateException.
  No se ha demostrado que sean ajenas/flaky. Los filtros gráficos/policy y
  PanVK pasan; el límite de heap/CPU tampoco se pasó explícitamente en esa ronda.
  No presentar esa ronda como suite verde ni como build con CPU2/Xmx4096m.
- **Cierre de gates antes de física:** mismo control y pins, fases/health/muestras
  estrictas, timeout incompatible con PASS y health entre readback y primerPresent.
  Tests negativos dirigidos54PASS después del último ajuste host; sonda C y APK
  no cambiaron en ese ajuste. El build final del parent sí usó JDK17/offline,
  1worker/CPU2/Xmx4096m y force exclusivo de mergeAssets;8tests gráficos PASS.
  CompilerPrefixRepair aislado23PASS. La suite JVM amplia sigue sin cualificar.
- **Candidato retenido:** code55/0.2.31-automated/371186229bytes/SHA
  `4818835af27aad8c9196af15976d9debeef933193a03f8cb360ceb35bd60d514`;
  PE96768/SHA80c1c260... y manifest92910f63... preservados con copia/SHA/fsync.
  Audit real PASS en delivery/automated-0.2.31-code55-validated-apk-audit.json,
  mismafirma/package/ABI/min29/target36. Comparación final contra54:158miembros
  assets/lib idénticos, solo PE+manifest nuevos y provenance distinto, sin removidos.
  Evidencia externa work/code55-baseline-assets-validated.json. Audits provisionales
  anteriores no describen este APK y se conservan solo como historial.
- **Preflight solo lectura:** el primer guard temporal confundió el nombre
  descriptivo Xiaomi22041216G con `ro.product.model` (`22041216G`). Se detuvo antes
  de instalar/reiniciar/UI/Wine; serial y device xaga coincidían. Se corrigió el
  guard al modelo exacto más fabricante Xiaomi, sin ampliar dispositivos ni gastar
  el intento físico. STOP preservado en readback-preflight-20261009T071618Z-2ed3645593.
- **Control cerrado2026-10-09:** reboot6147a0fe..., targetAA64/backup54 intactos;
  trial-20261009T071857Z-0591b198e4/RUN-1791530356088-76612335773. Cuatro fases
  VERIFIED, ambos Draw18816píxeles/FNV2943428253,30Present S_OK y terminal PASS.
  Pausa solicitada5000/medida5001ms, sin timeout global. Compiler REUSED verificado
  post-stop. Analizador global CONTROL_INVALID:24maps sin PE+PanVK samePID/root;
  rawAPIpositive no cualifica driver/API global/visual. PID18206 es solo candidato
  logcat sin stat/startTicks/PPID/maps; no cambiar proof. Screenshot finalfalse,
  causa del rechazo no conservada. Estado termination_unknown/quiescencefalse,
  postUID solo recolector y reboot obligatorio antes de otro Wine.42archivos sellados.
  Resultado íntegro `docs/wine-aa64-readback-results.md`. Se cierra este alcance,
  no se repite el intento; observabilidad requiere otro plan/aprobación.
