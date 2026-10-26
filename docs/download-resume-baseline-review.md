# Descarga/reanudación: diagnóstico acotado — 2026-10-25

## Alcance y calidad de la evidencia

**Corte histórico anterior a la aprobación del trabajo local.** Solo pasos 3/4
del [plan](../.agents/planning/download-resume-simplification-plan.md) estaban
autorizados. Inspección del árbol limpio `a4c0f4e`, sin ejecutar app, tests, red de
cliente ni comandos sobre móvil. No hay modificación de código/contratos/garantías.
El recorrido siguiente es una **traza estática**, no reproducción dinámica ni una
medición actual del dispositivo. No existe todavía comparación antes/después de
una mejora en este trabajo.

Se revisaron los resultados ya publicados en
[download-performance-investigation.md](download-performance-investigation.md),
apartados C143/C144/C145. La búsqueda local acotada en el espacio de artefactos
conocido no localizó sus capturas originales; no se recalcularon tasas desde logs
crudos ni se afirma que esos archivos no existan en otra ubicación. Las cifras
siguientes son evidencia histórica documentada, no una línea base C146 nueva.

## Recorrido observado en fuente

Rutas Kotlin bajo `scripts/arm64-prototype/app/updater-gate/`:

| Caso | Secuencia comprobada | Interacciones y límites |
|---|---|---|
| Instalación Home | `ForeverClientManagement.continueInstallation` (263–278): checkVersions → preparePublishedVersion → qualify → plan; `beginDownload` (254–261): qualify → handoff | El contrato fija abrir instalación, Continuar y Descargar. No se ha contado ese recorrido en una ejecución actual. |
| Pausa viva, mismo proceso | `resumeDownload` (280–291): qualify → plan → canResume → handoff; si no coincide, mostrar plan para confirmar | Una acción Reanudar si identidad/revisión/costes siguen admitidos. Coste residual menor por avance no fuerza confirmación por sí solo. No equivale a conservar un writer suspendido. |
| Servicio | `ForeverClientUpdateForegroundService.run` (131–168): construir consentimiento del plan entregado → executeWithAutoRecovery | Ejecuta una recuperación y reintento si encuentra el bloqueo de recuperación reconocido (198–221); no es bypass de las guardas de execute. |
| Ejecución | `ForeverClientUpdateService.execute` (345–379): runtime/calificación → preflight de lector para destino dirigido → destination → engine.execute → inspección independiente final | `ForeverClientUpdater.execute` (360–394) revalida/replanifica bajo lease y compara antes de crear staging o transferir. |
| Reabrir Activity durante descarga | Management (174–218): evita inspección local si ejecución ocupada y reconecta flujos; Back no pausa automáticamente | No se probó físicamente Activity recreation. |
| Muerte del proceso | `ForeverClientUpdateExecution` (27–74): resumePlan/pendingPlan son memoria volátil; journal/checkpoint conservan verdad durable | No hay base para reutilizar consentimiento persistente ni prometer Resume frío de una acción por este mecanismo. |

Dos cálculos del plan están comprobados en la ruta normal: uno antes de mostrar
costes y otro en `Updater.execute` antes de mutar. Resume también planifica antes
del cálculo del ejecutor. Son observaciones en distintos momentos y leases; no se
ha demostrado que una pueda eliminarse con garantías intactas.

## Línea base histórica disponible (no homogénea)

| Fase / candidato | Medida documentada | Interpretación válida |
|---|---|---|
| Inspecciones previas del destino | 225,435 s y 169,203 s; C143 registra otra de 163,663 s | Centenares de segundos en inspección; no suma de fases de una ejecución ni coste de toda preparación. |
| Preparación C144 | Aproximadamente 15 min | Sin desglose completo de qualify/catálogo/plan/tiempo humano; no atribuir todo a hashes o recorridos. |
| C145 transferencia útil | 610,573 s, ≈244,751 MB, 0,40085 MB/s | Ventana completa; no instalación completa ni prueba causal frente a otras cohortes. |
| C145 writer periódico | 627,960 s; source resolution 420,286 s (66,93%); private verification 44,591 s; append/force 61,944 s; checkpoint 6,632 s | Intervalo distinto al de UI. Las cuatro fases no cubren todo el writer. |
| C145 espera final de futuros | 432,024 s; 1.117 cabezas pendientes/19.098 solicitudes y 691 muestras sin ready-ahead | Denominador final distinto del intervalo periódico; no sumar a source resolution. |
| C145 fuerza de grupos | 56,059 s, 3,000 ms/objeto; C144 7,549 ms/objeto | Reducción local por objeto ≈60,26%; cohortes distintas y sin mejora global acreditada. Fuerza es 8,93% del intervalo periódico C145, no todo el cuello. |
| C145 productor | 358,990 s esperando presupuesto | Concurrente con writer/HTTP; no fase adicional de tiempo total ni prueba de que aumentar presupuesto ayude. |

Por tanto, **prioridad de investigación de inicio:** inspección/planificación previa,
por coste histórico y repetición estática; falta medida actual para seleccionar
una eliminación concreta. **Durante transferencia C145:** disponibilidad de fuentes
y backpressure, no fuerza de datos como único cuello. No está aislada la causa
entre CDN, cohortes y efectos de scheduling de grupos. No autoriza ready-first,
más cuotas/workers ni menos chequeos.

## Qué guarda cada comprobación

| Comprobación / ubicación | Qué detecta o protege | Qué puede cambiar / límite; coste disponible |
|---|---|---|
| `Service.withStorageIo` (446–460), `destination` (667–708) | Lease de E/S, resolución y revalidación fresca del almacenamiento; ownership de archivos antes de montar destino para engine | Medio, selección, root/base, marcadores o rutas pueden cambiar entre llamadas. No hay tiempo exclusivo por llamada actual; no todos los checks son redundantes. |
| `Service.qualify` (407–424), `Qualification.run` (31–109) | Sondas scratch en privado/volumen: escritura+force, rename atómico, hardlink solo privado; runtime conocido/quiescente; pequeño objeto CDN íntegro cuando procede | No prueba integridad completa del cliente ni reemplaza comprobaciones runtime/storage en execute. Repite trabajo real y puede transferir una sonda; tiempo por subfase sin medir aquí. |
| `Service.hasCurrentQualificationProof` (1519–1523) | Recibo pasado, ligado a storage key/mount y edad entre cero y seis horas (`MAX_QUALIFICATION_AGE_MILLIS`) | No es permiso genérico para reutilizar prueba: cambios posteriores siguen sujetos a otras guardas. UI renueva al confirmar porque pudo pasar tiempo. Condicionar esa repetición sería cambio de código no autorizado. |
| `Service.preparePublishedVersion` (241 y siguientes) | Admisión/catálogo y guard de descarga pendiente bajo lease; contexto anterior no se sustituye implícitamente por otra metadata | Versión visible igual no demuestra catálogo igual; nunca migrar checkpoint por número de build. Coste de esta fase no aislado en la evidencia disponible. |
| `Updater.buildPlan` (863 y siguientes), `requireSamePlan` (2939–2946) | Catálogo/configs, estado fuente, archivos loose, workspace/checkpoint, inventario y costes. Compara snapshot/revisión, descarga, espacio privado/destino, cuentas y cambios/transacción | Cambios entre mostrar y confirmar invalidan el plan. No basta igualdad de bytes agregados. Las inspecciones históricas son componente, no tiempo exclusivo de todo buildPlan. |
| `Execution.canResume` (51–54) y `Updater.checkConsent`/execute | Identidad de plan/revisión, espacio suficiente y todos los presupuestos residuales dentro de consentimiento; execute vuelve a comprobar plan/espacio | La muerte del proceso pierde candidato en memoria; checkpoint no autoriza red. No se midió coste exclusivo, no se propone omitirlo. |
| `Service.execute` (358–370), `ForeverCascReplayPreflight` | En destino dirigido, lector real path↔FD apto en namespace actual, sin fallback de fallo | Prueba por invocación, no prueba persistida ni integridad de cada objeto. Tiempo no aislado disponible. |
| `destination.revalidateCascSegment/CommitGroup` (702–707); US-007 FR-005 | Full en admisión/resume/staging/nuevo segmento/activación; light antes de force/checkpoint de cada grupo dirigido sourceless | Light revisa selección/revisión y root/base no-follow; marcador/ancestros/metadata de volumen diferidos. No protege universalmente contra sustitución concurrente del pathname de force; no equivalencia full/light. |
| Autenticación por objeto, writer CASC y checkpoint; US-008 FR-003/004 | EKey/BLTE/checksums/CKey/tamaños, fuerza de datos y checkpoint antes de ack/retiro de raw; recuperación preservadora | Metadatos/receipt no prueban bytes actuales. Los grupos16 no son límite de bytes/tiempo. Mantener34 claves/1GiB y flushes parciales. No omitir crypto para acelerar. |
| `Service.execute` (374–378), barrera writer/verificador y transacción | Inspección independiente posterior y publicación solo del conjunto verificado; exclusión/journal | Primer fetch o commit de tail no prueba prefijo íntegro, instalación lista o activación aceptable. Costes finales no medidos aquí. |
| `ForeverWifiDownloads`, US-008 FR-012 | Red cliente solo por Wi-Fi, sin fallback celular; no sustituye consentimiento | Cambia disponibilidad de red. Sin medidas de coste atribuidas; prueba de elegibilidad no es permiso de descargar. |

## Instrumentación existente y dato que falta

- `ForeverCasc.kt:1124/1270` emite `CASC target inspection end elapsedMs`:
  delimita inspección, no todo el trayecto de UI ni calificadores.
- Foreground emite inicio, recuperación y pausa; `publish` conserva cambios de fase
  y contadores con cadencia de UI limitada (250 ms), no timestamps exactos de cada
  suboperación ni de intención del usuario. `ForeverClientDownloadRate` utiliza
  muestras reales, no bytes reutilizados como transferencia.
- La evidencia histórica contiene clocks de writer/futuros/append y contadores de
  storage. Son útiles para separar ámbitos, no para reconstruir por sí solos el
  tiempo humano ni todas las fases previas.
- `prepare_stages` de `ForeverFunctionalPoc` es preparación de lanzamiento: no usarlo
  como si midiera preparación del downloader.

No se ejecutan sondas nuevas ni se altera instrumentación. Tests host existentes
no constituyen línea base de USB y su ejecución no resolvería ese faltante.

## Siguiente paso propuesto — autorización necesaria

Detener aquí la reproducción dinámica: obtener una **línea base actual** requiere
interactuar con la app/dispositivo, expresamente no autorizado. Solicitar permiso
para una observación acotada en ventana sin juego, con costes y destino confirmados:
marcar intención/confirmación/Resume, capturar logs ya existentes y progreso hasta
transferencia sostenida, y separar pausa del mismo proceso de arranque frío. Acordar
antes si se permite pausar/reanudar; no instalar, reiniciar, borrar ni resetear datos.

Alternativa sin operar móvil: proporcionar la ubicación de las capturas C143–C145
y sus marcas de acciones para analizar offline con mayor precisión, sin afirmar
que sean una línea base actual ni comparación controlada. Si los logs no separan
qualify/plan, declarar esa limitación y pedir autorización de instrumentación
antes de introducirla. No se solicita ni presupone permiso de código en este paso.

En ese corte el plan seguía draft; pasos3/4 no completos sin reproducción/línea
base suficiente y decisión de cuello prioritario actual.

## Continuación local autorizada — 2026-10-25

El usuario aprobó diagnóstico, cambios mínimos de código/UI y pruebas locales,
sin operar el teléfono ni publicar una APK. El plan está `in-progress`. La
inspección estática y las cifras históricas anteriores no se convierten por esa
aprobación en una línea base física actual. Los resultados nuevos se documentarán
con su alcance real; no se ha acreditado todavía una mejora de latencia en USB.

### Intervenciones locales seleccionadas

- **Reentrada UI:** `runOperation` no tenía admisión síncrona independiente de la
  recomposición. Dos callbacks de pulsación podían iniciar dos cadenas; las capas
  de exclusión inferiores seguían impidiendo una doble ejecución, pero no el
  trabajo/error innecesario. La revisión detectó además la ventana entre entregar
  el plan y el arranque asíncrono del servicio. La corrección debe cubrir ambas,
  sin añadir confirmaciones ni cambiar los límites de acciones de FR-008/011.
- **Planificación sin fuente:** `ForeverCasc.inspectTarget` iniciaba
  `target-records.bin` con estimado cero aunque `buildPlan` conoce el conteo
  admitido. El [perfil host histórico](launcher-app-status.md) documenta crecimientos
  y rehashes completos de esa tabla. Se ensaya predimensionarla, conservando la
  misma tabla, inserciones, detección de duplicados, validación por objeto, packing
y cross-checks. Esto es una optimización de asignación, no una exención de
  comprobaciones. La medición host actual se resume a continuación.

La segunda planificación del ejecutor, `requireSamePlan`, la calificación fresca,
el consentimiento y las barreras de almacenamiento/durabilidad no se suprimen.
No se modifica US-008: son correcciones de implementación dentro del contrato
aprobado (FR-003/004/008/009/011), no requisitos nuevos.

### Comprobación local de ensamblado y contratos UI

`/home/adrian/.local/bin/uv run --offline pytest
tests/test_reconstruct_functional_source.py tests/test_functional_launcher_ux.py -q`:
**59 passed, 1 failed**. La invocación inicial con `uv` no estaba en `PATH`; se
localizó el ejecutable existente sin instalar dependencias. El fallo es
`test_account_unavailable_state_cannot_offer_a_destructive_save_as_account_save`:
espera una cadena de autologin ausente también en `HEAD`. Ni ese test ni
`overlay/ForeverFunctionalAutologinSettings.kt` cambian en esta tarea. Se registra
como fallo previo fuera del alcance, no se modifica autologin para hacer pasar la
suite. Estos tests Python no prueban compilación ni interacción Compose.

### Comparación host del inspector sin fuente

Fixture sintético de **300.000 objetos en orden arbitrario**, no catálogo ni
almacenamiento del teléfono. Dos ejecuciones por variante en el mismo harness:
la referencia usa la implementación nueva con pista cero, que conserva el
dimensionamiento anterior; no son dos APKs. El orden fijo fue referencia → pista
exacta → pista grande, repetido dos veces (sin aleatorización ni control formal
de JIT/caché).

| Capacidad inicial | Duraciones `inspectTarget` | Rehashes por ejecución |
|---|---|---|
| Anterior: estimado cero | 3.546 / 3.025 ms | 4 |
| Nueva: conteo exacto 300.000 | 2.119 / 2.016 ms | 0 |
| Nueva: pista 1.144.978 | 1.794 / 1.829 ms | 0 |

El `TargetScanResult` y las **300.000 filas de callback** coincidieron en las seis
ejecuciones. Se acredita la eliminación de cuatro reconstrucciones completas de
la tabla para esa entrada; las dos repeticiones no caracterizan variabilidad ni
permiten extrapolar un porcentaje al dispositivo o a toda la preparación. La
pista sobredimensionada también conserva resultados, pero producción usa el
conteo admitido del orden de archivo, no una constante ajustada al benchmark.
No se ha medido tiempo a primer byte, reanudación completa ni progreso sostenido.
La regresión focal y tres vecinas pasaron en el harness host (4/4):
`targetInspectionCapacityHintKeepsIdenticalPlanAndRejections`,
`targetInspectionPlansNineVerifiedAndOneMissingObjectWithoutProducingOutput`,
`targetInspectionRejectsDuplicateAndShortPrefixCollisionAndCleansScratch` y
`targetInspectionRejectsArchiveIdOverflowBeforeRequestingTheNextObject`.
Compilación host de `ForeverBlte.kt`, `ForeverCasc.kt`, `ForeverCascTest.kt` y
`Bench.kt`: exit 0. Esto **no** acredita compilación completa de Updater ni UI.

Auditoría del harness: `/tmp/opencode/opt-harness/Bench.kt`, semilla fija
`0xC10C4A5C`, generación y barajado previos al cronómetro, sin red ni ADB. Comprueba
limpieza del scratch, cuenta logs `disk index rehash begin` y compara resultado
completo y callbacks. Kotlin compiler embeddable 2.1.21, host OpenJDK 21.0.12.1+1;
la comprobación Gradle integrada usa JDK 17, no se confunden ambos entornos.

### Estado de la comprobación integrada

- El primer intento Gradle Legacy en la copia aislada
  `/home/adrian/dev/realmdeck-opt/GameNative` falló en compilación: el staging
  parcial de updater omitió `overlay/ForeverClientUpdateCheck.kt`, que sí existe
  en el repositorio. **No es un defecto acreditado del producto ni de la
   optimización.** Los intentos posteriores con esa copia parcial revelaron más
   diferencias con el ensamblado completo; no se corrige el producto para acomodar
   ese staging. La comprobación focal pasó en el árbol Modern ya compilado.
- La suite `ForeverClientUpdateForegroundServiceTest` pasó offline con JDK17
   en otra copia aislada bajo
   `/home/adrian/wowforever-artifacts/work/download-resume-gate-20261025/GameNative`.
   `:app:testModernDebugUnitTest --tests
   app.gamenative.ForeverClientUpdateForegroundServiceTest --offline --no-daemon`:
   **BUILD SUCCESSFUL**, diez tests, cero fallos/errores/omitidos (XML del
   2026-10-25T16:42:19). El responsable leyó el XML y comprobó con `cmp` las fuentes
   finales de Management, Casc, Updater, UpdateService y el test. Sus tres
   regresiones nuevas comprueban el protocolo pending/segundo reset/start, no
   interacción Compose. La compilación integrada Modern sí está acreditada.
- En ese mismo árbol, `ForeverCascTest.targetInspectionCapacityHintKeepsIdenticalPlanAndRejections`
  y `ForeverClientUpdaterTest.physicalArchiveOrderPlanningKeepsExactConsentCostsStable`
  pasaron: un test por clase, cero fallos/errores/omitidos, XML del
  2026-10-25T16:52:04. El responsable leyó ambos XML y comprobó con `cmp` los dos
  archivos de test contra el repositorio. No se repitieron los diez tests ya verdes
  ni se ejecutó toda la suite. Los XML de Gradle se reemplazan al filtrar otra
  ejecución: la ausencia posterior del XML del servicio no invalida su veredicto
  leído y documentado antes de esta ejecución.
- Revisión independiente del algoritmo: sin hallazgos bloqueantes. Revisión UI:
  admisión pending validada; ajustes de Pausar y apertura pending inspeccionados
  por el responsable tras la corrección, sin prueba de pulsaciones en dispositivo.

Optimización versionada en `c1bc7a1` y corrección UI en `633329e`. La medición
posterior de C146 se describe abajo; todavía no acredita mejora física de estos
cambios ni aceptación del recorrido completo.

### Observación física C146 — 2026-10-25

Autorización posterior del usuario: pruebas locales y Android por ADB Wi-Fi;
ante el servicio activo eligió observar, pausar normalmente y medir reanudación,
sin instalar todavía otra candidata. Dispositivo `22041216G` (`xaga`, API34),
paquete `com.wowforever.arm64probe`, C146 / `0.2.122-performance-preview`.

- A las 17:26–17:27 UTC, el servicio de descarga estaba en primer plano y la UI
  mostraba «Descargando 8.0 GiB de 71.4 GiB», «0.3 MB/s» y «Pausar». Son cifras
  redondeadas instantáneas; no una medida de throughput ni una prueba de preparación.
- Se intentó una sola pulsación normal de «Pausar» mediante ADB. Android la rechazó
  con `SecurityException`: falta `INJECT_EVENTS`. La UI posterior seguía descargando;
  no se envió una alternativa de pausa por servicio, ni se cambió seguridad,
  instaló, mató o reinició nada. Ese bloqueo se resolvió después con scrcpy UHID
  autorizado, sin modificar permisos ni ajustes de seguridad.
- Esta sesión instalada no contiene los cambios nuevos. No se atribuyen a ellos
  sus cifras ni se presenta esta observación como A/B. No hay todavía tiempos a
  consentimiento/primer byte ni medición de la candidata en Android.

### Reanudación activa C146 mediante scrcpy — 17:35–17:56 UTC

El usuario pidió scrcpy. Se reutilizó el servidor local fijado y se verificó su hash,
solo teclado UHID de navegación, sin vídeo/audio/portapapeles/texto ni cambios de
seguridad. La sesión valida app propia en primer plano y ausencia de campos
editables antes de cada tecla; activa la etiqueta exacta una sola vez tras enfocar.
La pausa inicial mostró «En pausa»/«Reanudar».

- **Una acción Reanudar**, Enter enviado a las **17:35:31.349 UTC**. La UI mostró
  «Comprobando descarga pendiente…» a +2,915 s. No se repitió la acción ni se
  confirmó un coste nuevo.
- 69 muestras de UI entre +2,915 y +1.194,746 s, cadencia de 15 s más coste de dump.
  Se observaron comprobación de 1.144.978 archivos, recuperación y otra comprobación;
  no se observó fase «Descargando». No equivale a medir exactamente primer byte ni
  a demostrar ausencia de cualquier tráfico auxiliar entre muestras.
- Logs CASC numéricos retenidos del mismo proceso, filtrados sin datos privados:

  | Inspección sin fuente | Comienzo / fin desde Enter | Duración registrada | Rehashes | Tiempo rehash, incluido en duración |
  |---|---|---|---|---|
  | Primera | +187,265 / +410,277 s | 223,012 s | 6 | 41,579 s |
  | Segunda | +737,339 / +903,447 s | 166,107 s | 6 | 26,521 s |

  Los rehashes pasan de capacidad 32.768 a 2.097.152. No sumar sus 68,100 s otra
  vez a los 389,119 s de inspección, ni adjudicar a esas tablas toda la preparación.
  Hay también rehashes posteriores fuera de las dos inspecciones: no están
  eliminados por la pista de capacidad del inspector ni se incluyen en esa suma.
- Tras alcanzar el límite de observación, **una pausa normal**, Enter a
  **17:56:10.000 UTC**, confirmó «En pausa»/«Reanudar». La navegación/limpieza añade
  tiempo al límite nominal de veinte minutos. No se mató proceso, borró progreso,
  instaló ni reinició durante esta medición.
- Artefactos filtrados: `/tmp/opencode/c146-resume-baseline/{summary,casc-events}.json`;
  script de prueba `/tmp/opencode/c146-resume-baseline.py`. La app aún era C146,
  por lo que esta evidencia no acredita los cambios nuevos. Caché no controlada,
  descarga previa en torno a 8,3 GiB; no constituye un A/B causal de latencia total.

Después el usuario autorizó **C147 local / `0.2.123-download-startup-test`**:
reconstruir con inputs autenticados, auditar e instalar como actualización solo con
pausa confirmada, preservando datos/progreso, sin publicar ni confirmar costes
nuevos. La construcción y actualización posteriores se registran a continuación.

### C147 local: reconstrucción, auditoría y actualización

- Identidad/pins en `fff17ac`. La primera reconstrucción y su test fallaron de forma
  cerrada ante los hashes de salida C146 obsoletos. Se actualizaron únicamente los
  dos postimages CASC a los blobs exactos revisados de `c1bc7a1`, cotejados con Git;
  preimages históricos y pins de inputs externos intactos. No se omite el gate.
- `uv run --offline pytest tests/test_reconstruct_functional_source.py
  tests/test_audit_functional_ui_apk.py -q`: **56 passed**, 2,51 s tras esa corrección.
- Reconstrucción autenticada en
  `/home/adrian/wowforever-artifacts/work/download-startup-c147-authenticated-20261025/`.
  El primer build requería `local.properties`; se añadió solo la ubicación SDK en
  la copia externa, sin secretos ni cambios al código. `:app:assembleModernDebug
  --offline --no-daemon --max-workers=2`, JDK17: **BUILD SUCCESSFUL**, 5 min 2 s,
  68 tareas. No se repiten los tests JVM ya aprobados por un retag.
- `audit_functional_ui_apk.py --receipt … --apk … --output …`:
  **PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT**, 2.226 fuentes sin cambios respecto al
  receipt y 178 payloads. APK 484.904.119 bytes, SHA-256
  `1291a0718bd40c974edf383c525870d15adb43d8a852a87562d0067493548f5c`.
- Comparación de inventarios de receipts C146/C147: **siete diferencias** exactas:
  build/version, marcador de procedencia, `ForeverCasc`, `ForeverClientUpdater`,
  `ForeverClientManagement` y los tests `ForeverCascTest`/servicio foreground.
  Las cinco fuentes/tests críticos cotejados también coinciden byte a byte con repo.
- La revisión independiente previa de algoritmo/concurrencia se mantiene. Un nuevo
  intento de revisión del retag no arrancó por variante de worker no disponible;
  no se afirma una revisión adicional realizada. El responsable inspeccionó los
  seis cambios de línea de identidad/pins/tests y los resultados de auditoría.
- Comprobación inmediatamente anterior: UI «En pausa»/«Reanudar», ningún servicio
  updater activo ni ejecutable Wine/box64/WoW observado. Un primer filtro demasiado
  amplio confundía «wow» del paquete propio con un ejecutable guest; se corrigió a
  nombres de ejecutables antes de intervenir, sin matar nada.
- Actualización autorizada mediante `adb install -r`: **Success**. PackageManager
  confirma C147 / `0.2.123-download-startup-test`, minSdk29/targetSdk36 como C146.
  Sin desinstalar, borrar, publicar, reboot ni cambios de seguridad. No se afirma
  verificación integral de los datos conservados ni mejora física todavía.
- Tras actualizar, la app no estaba en primer plano. El bloqueo de apertura manual
  quedó obsoleto al permitir las reglas vigentes el control completo del dispositivo
  de desarrollo. En la continuación aprobada se comprobó de nuevo C147 instalada,
  ausencia de ejecutables guest y auditoría retenida PASS; la actividad se abrió
  mediante ADB sin reinstalar, reiniciar ni iniciar juego.
- Home mostró «Cliente no instalado»/«Descargar cliente», compatible con un cliente
  parcial todavía no publicado; no demuestra pérdida de progreso. Una pulsación de
  «Descargar cliente» mostró «Destino: Unidad USB» y «Continuar». Se mide ahora una
  preparación desde proceso fresco hasta el resumen, sin confirmar descarga ni
  equipararla a la reanudación del mismo proceso de la baseline C146.

### Primera preparación C147 desde proceso fresco

- Una sola acción «Continuar», Enter a las **18:28:14.889773 UTC**. Primera muestra
  a +2,722 s: «Consultando versión…» y «Pausar». No hubo otra pulsación para hacer
  avanzar la preparación. Esta muestra acredita respuesta antes de 2,722 s, no
  latencia subsegundo medida ni prueba de doble toque deliberado.
- Tras treinta muestras, resumen visible a **+509,206 s**: destino Unidad USB,
  versión `1.60.1.70532`, descarga pendiente **63,0 GiB**, espacio adicional
  **65,2 GiB**. La muestra anterior era de preparación; el intervalo entre muestras
  incluye quince segundos de espera y el coste del dump. No es tiempo a primer byte.
- Logs del inspector: comienzo +199,530 s, fin +378,663 s, duración **179.133 ms**,
  **cero rehashes**. Conteo visible: 1.144.978 objetos. La primera pasada C146 duró
  223.012 ms y la segunda 166.107 ms, ambas con seis rehashes. El tiempo C147 cae
  entre esas dos medidas: **no acredita por sí solo una reducción causal de latencia
  Android**. Sí confirma que la candidata elimina el trabajo de crecimiento de esa
  tabla en dispositivo, coherente con la comparación host controlada.
- C146 se midió como Resume del mismo proceso; aquí se reconstruye el plan tras
  actualizar/reabrir el proceso. No hay control de caché, cohorte ni calentamiento,
  y los 509,206 s hasta el resumen no son comparables al límite de observación de
  C146 hasta transferencia. No se extrapola un porcentaje a toda la preparación.
- Artefactos filtrados externos: `/tmp/opencode/c147-startup-trial/initial-summary.json`
  y `casc-events.json`. Sin capturas de cuenta, identificadores del dispositivo ni
  valores de configuración privada en el repositorio.

El usuario confirmó después **«Confirmar y probar»** para el plan concreto de
63,0/65,2 GiB y destino/versión indicados: ejecución y una reanudación, acabando en
pausa normal, con carga útil limitada a la ventana de prueba de dos minutos. No
autoriza dejar descargando todo el cliente ni aceptar otro plan de costes.

### Confirmación y pausa/reanudación C147

- Un solo «Descargar», Enter **18:41:45.562202 UTC**. Primera muestra a +3,012 s:
  «Comprobando almacenamiento…»/«Pausar». La transferencia fue visible a
  **+984,696 s**: «Descargando 33.9 MiB de 63.0 GiB». Es una cota de observación
  mediante UI, no el instante exacto del primer byte de red.
- Inspector del ejecutor: +225,735 a +359,140 s, **133.405 ms, cero rehashes**.
  Después, la carga del checkpoint de **355.973 objetos** tardó **164.885 ms**;
  cinco rehashes observados fuera del inspector siguen presentes. No se afirma
  que la pista de capacidad elimine esos trabajos de recuperación.
- Primer pedido de fuente registrado a +974,979 s, con verificación de prefijo
  solapada: 41.404 objetos verificados y 314.569 pendientes. Un pedido puede
  reutilizar datos privados: no demuestra por sí solo primer byte nuevo de red.
- Una pausa normal, Enter **18:58:38.215912 UTC**, confirmó «En pausa»/«Reanudar».
  Commit final: **128 objetos, 60.552.826 bytes escritos**; no son necesariamente
  bytes nuevos de red. La pausa llegó unos 28 s después de la primera muestra de
  transferencia, sin dejar descargando el cliente completo.
- Un solo «Reanudar», Enter **18:59:09.282172 UTC**; respuesta de preparación
  observada a +2,774 s. Su inspector duró **177.388 ms, cero rehashes**.
  A **+526,058 s** volvió al resumen con **62,9 GiB / 65,1 GiB** y «Descargar».
  **No se confirmó otra vez**. No se ha validado todavía Resume en una acción:
  hay que distinguir una invalidación incorrecta de un presupuesto por volumen
  legítimamente mayor, aunque el pico combinado redondeado haya bajado.
- Artefacto filtrado: `/tmp/opencode/c147-startup-trial/download-resume-summary.json`.
  El diagnóstico posterior con debugger lee los argumentos de la guarda; no se
  considera una medición de rendimiento. No se elimina ninguna
  comparación de identidad, revisión, espacio o consentimiento para hacer avanzar
  el flujo.

### Causa comprobada de la reconfirmación y regresión local

La lectura de argumentos de `canResume` en C147 debug confirmó **espacio válido,
misma revisión y mismo plan**; no se escribieron campos ni se aceptó otra descarga.
Artefacto externo: `resume-guard-diagnosis.json` en el mismo directorio de prueba.

| Cota (bytes) | Consentida | Recalculada |
|---|---:|---:|
| Descarga | 67.628.383.358 | 67.567.830.532 |
| Privado | 1.831.494.496 | 1.835.889.466 |
| Destino | 68.136.800.030 | 68.076.242.884 |
| Pico combinado | 69.968.294.526 | 69.912.132.350 |

El privado sube **4.394.970 bytes**: la guarda rechaza correctamente ese plan.
El cálculo descontaba toda la ocupación privada del límite consentido; retirar
raws/scratch propios durante progreso normal puede hacer subir de nuevo ese límite.
No se elimina la comparación ni se declara válido el consentimiento antiguo.

Regresión local nueva, con el fixture existente y un parcial sintético sparse de
4 MiB: planificar con el parcial, retirarlo y volver a planificar conservando
destino/revisión. Antes de modificar producción compiló y falló en la aserción
esperada «retiring private scratch must not require another consent» (un test,
un fallo, cero errores; XML 2026-10-25T19:31:46). No se transfieren objetos grandes.

Corrección local `5516cf6`: cota privada conservadora completa para consentir
el camino sin fuente; crédito de ocupación y mínimo de planificación **idénticos
a los anteriores** para comprobar el espacio libre actual. Camino con fuente y
las cuatro guardas intactos. Se muestran también las cotas app/cliente cuando son
volúmenes distintos, como exige AC-02. Compilación Modern y **cinco regresiones
focales PASS**, cero fallos/errores/omitidos (XML 2026-10-25T19:35:59), incluidas la
nueva, rechazo de consentimiento, cancelación de dieciséis descargas y finalización
al reanudar, ownership del temporal y actualización con fuente. Las tres fuentes
modificadas coinciden byte a byte con las compiladas; `git diff --check` limpio.
No se repiten suites previas no afectadas.

Revisión independiente completada, sin hallazgos: tras fallar el worker configurado,
el usuario autorizó explícitamente GPT-6 Luna de OpenAI para esta revisión, sin
cambiar configuración. No se repitieron los checks ya verdes.

C148 local / `0.2.124-download-resume-test` (`47c1158`) prepara la prueba física de
esta corrección. Tres tests de identidad/transformación/marker PASS. Reconstrucción
autenticada completada bajo `work/download-resume-c148-20261025` fuera del repo;
fuentes críticas cotejadas con el repositorio. Frente al recibo C147 cambian solo
la identidad Gradle, el marker, Updater, Management y UpdaterTest (cinco paths).
El primer proceso de compilación quedó interrumpido sin veredicto ni APK; no se
interpretó como éxito ni como defecto de Kotlin. Tras comprobar ese estado se
ejecutó el mismo comando en foreground, con log retenido: `:app:assembleModernDebug
--offline --no-daemon --max-workers=2`, JDK17, **BUILD SUCCESSFUL en 4m 7s** (68
tareas). No se repitieron las suites verdes.

Auditoría del APK exacto: **PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT**, 2.226 fuentes
sin cambios y 178 payloads; 484.904.415 bytes, SHA-256
`a6f4ea27d5e8b7dd960823d249f25c3ccb0fc7335a9be94cc375350a60e3776a`.
Recibo, `build-final.log`, `apk-audit.json` e `install-summary.json` están en el
directorio externo anterior. Con updater inactivo y sin ejecutables Wine/box64,
`adb install -r` finalizó con éxito: PackageManager confirmó C148 y Home abrió a
las 19:57:58 UTC. Sin reset, desinstalación ni publicación. La medición posterior
se describe a continuación; no se da por válido el consentimiento de un proceso
anterior.

### Preparación y reanudación C148 — resultado físico

- Abrir gestor y «Continuar» únicos, a las 19:58:43 y 19:59:05 UTC. Respuesta
  observada a +2,911 s. El observador externo se interrumpió: el resumen solo quedó
  acotado entre +370,786 y +978,803 s, **no** medido exactamente. El inspector sí
  registró **180.801 ms, cero rehashes**, con 1.144.978 objetos. La interrupción del
  observador no se interpreta como fallo del producto ni se repite «Continuar».
- El usuario aprobó el plan concreto `1.60.1.70532`/Unidad USB, **62,9 GiB** de
  descarga, **65,1 GiB** adicionales y cotas app/cliente **1,7/63,4 GiB**. Un único
  «Descargar», Enter **20:22:14.305537 UTC**, dio respuesta observada a +3,031 s y
  transferencia visible a **+948,398 s**. Su inspector tardó **132.096 ms**, sin
  rehashes. Una pausa cooperativa a las **20:38:31.593966 UTC** confirmó pausa.
- Un único «Reanudar», Enter **20:39:10.939764 UTC**, respondió a +2,837 s y llegó
  a transferencia visible a **+1.453,953 s**, **sin otra confirmación**. Esto valida
  físicamente la reparación del consentimiento privado en ese recorrido; no
  acredita que toda la preparación sea rápida. Los dos inspectores de Resume
  tardaron **169.652/135.147 ms**, ambos sin rehashes.
- El arranque sigue siendo lento. C148 tarda unos **16 minutos** desde Descargar y
  **24 minutos** desde Resume hasta transferencia visible. Los 948,398 s frente a
  984,696 s de Descargar en C147 no son un A/B controlado: difieren progreso,
  caché y cohorte. C146 se pausó tras una ventana de observación sin transferencia
  visible; no se inventa un tiempo final para comparar ni una mejora total causal.

Desglose restante de C148, con tiempos relativos a la acción correspondiente:

| Fase registrada | Descargar | Reanudar |
|---|---|---|
| Inspector(es) | +207,603–339,699 s | +182,990–352,643 y +667,626–802,773 s |
| Carga de checkpoint | 356.101 objetos; 150.713 ms; fin +754,059 s | 356.357 objetos; 169.787 ms; fin +1.254,049 s |
| Primer pedido de fuente | +939,605 s; 41.404 verificados/314.697 pendientes | +1.441,823 s; 41.404 verificados/314.953 pendientes |

Hay cinco rehashes durante la carga, **fuera del inspector**, que la primera
optimización no elimina. Sus duraciones y las de carga ya están incluidas en los
relojes superiores; no se suman como fases independientes. Un pedido puede reutilizar
datos privados: no es prueba del primer byte nuevo de red. El commit registrado
tras Descargar incorporó 256 objetos/67.746.954 bytes **escritos**, no necesariamente
bytes nuevos de red. Las esperas previas y los recorridos de checkpoint necesitan
diagnóstico antes de elegir otra optimización; eliminar esos cinco rehashes no
explicaría por sí solo los 24 minutos.

La comprobación de reapertura quedó **incompleta por un error del guion**:
«Volver» a las 21:03:35 UTC mostró Home con descarga activa, pero el guion esperaba
el botón de reposo «Descargar cliente». La implementación ofrece correctamente
«Ver descarga» durante la transferencia. No se atribuye esa aserción al producto
ni se repite toda la prueba para ocultarla. La descarga se pausó normalmente desde
Home a las **21:04:24.523147 UTC**; después se comprobaron «Descarga pausada» y
**cero servicios updater activos**. Las ventanas desde la primera muestra visible
de transferencia hasta Pausar fueron 28,890 y 59,630 s; no son una medida exacta
de duración de red ni una prueba de throughput sostenido.

Los artefactos filtrados persisten en `physical-trial`: `download-resume-summary.json`,
`prepare-observer-partial.json`, `prepare-casc-events.json` y
`cleanup-pause-summary.json`. Se conserva el resultado de error original. Los
guiones de control no forman parte del producto ni sus expectativas sustituyen el
contrato aprobado. La reconexión completa al gestor durante transferencia sigue
pendiente, al igual que acreditar una reducción total de preparación.

### Perfil del trabajo previo a transferir — C148

La prioridad confirmada por el usuario es reducir **acción → transferencia**, no
solo mejorar un inspector. Se muestreó el proceso debug instalado durante una
reanudación diagnóstica, sin aceptar otra descarga ni cambiar la APK. Una única
acción «Reanudar» a las 21:17:34 UTC; ventana de muestreo de 60,155 s iniciada un
minuto después. Pausa normal a las 21:19:56 UTC y estado «En pausa» confirmado.

Traza ART con muestreo de 10 ms, reloj dual, sin desbordamiento: 202.071 eventos
de muestreo, cero salidas de pila desparejadas en el análisis. En el worker que
ejecutaba `checkpointMembership` se atribuyen **44,435 s de pared / 38,761 s CPU**
a ese recorrido dentro de la ventana. Tiempos inclusivos de sus operaciones:

| Operación | Pared muestreada, incluida en los 44,435 s |
|---|---|
| Consulta individual `objectByKey` | 24,260 s |
| Consulta individual `ordinalOf` | 15,490 s |
| Lectura `readFullyAt` | 1,708 s |
| Decodificación `decodeResumeObject` | 0,612 s |

Las dos consultas ocupan **39,750 s (~89,5 %)** del tramo atribuido; son operaciones
secuenciales, no relojes exteriores sumados. Preparar/ejecutar SQL, cursores y
asignaciones dominan el perfil, no la lectura pequeña del checkpoint. El muestreo
añade sobrecoste: estos valores no son duración íntegra de una fase, conteos
exactos de invocaciones, comparación A/B ni porcentaje extrapolable a los 24 minutos.

Se elige amortizar consultas en lotes **de hasta 256 claves**, usando la API de
catálogo ya existente y una consulta acotada de ordinales. Se conservan validación
individual completa, conteo, orden/duplicados globales, cancelación y la posterior
comprobación independiente. No se elimina un recorrido ni se almacena una confianza
persistente. La carga posterior del escritor y sus consultas son trabajo distinto;
no se afirma que este cambio por sí solo quite toda la espera. Implementación,
regresión focal y revisión siguen en curso antes de otra candidata física.

Artefactos externos retenidos en `physical-trial/membership-profile`:
`summary.json`, `sampling.trace`, `attribution.json`. El analizador está junto al
guion diagnóstico en `physical-trial/analyze_membership_trace.py`. La traza cruda
no se versiona y no se copian identificadores del dispositivo ni datos de cuenta.

### C149: consultas agrupadas y preparación inicial

Corrección `a8f086d`: la membresía previa al inspector usa lotes de hasta 256
claves. `ordinalsOf` restaura el orden de entrada y rechaza claves/ordinales
duplicados, ausentes, inválidos o fuera de rango; la ocupación temporal se limita
al lote. Se conservan las comprobaciones individuales del catálogo, el bitset
global, cancelación y el contraste independiente posterior. No se elimina ningún
recorrido ni se reutiliza una prueba de validación entre operaciones.

- Dos regresiones de pausa/reanudación y checkpoint legacy pasaron (XML
  21:18:46); el nuevo focal de orden/rechazos/cancelación/límite de lote pasó
  con la fuente final (XML 21:28:33, un test, cero fallos/errores/omitidos).
  Las tres fuentes coinciden con las compiladas. Revisión independiente sin
  hallazgos, sin repetir los checks ya verdes.
- Microbenchmark **Python/SQLite de host**, no Kotlin/Android: 356.000 claves
  del orden físico, warmup de 2.000 por ruta y tres repeticiones. **712.000 →
  2.782 consultas**; mediana **12,430 → 3,120 s** en Python 3.14.4/SQLite 3.46.1.
  Coinciden todas las tuplas EKey/CKey/tamaños y los ordinales en orden de entrada.
  Orden fijo referencia → lotes; no se extrapola ese cociente a la preparación.
  La captura anterior `.json` empleó otras versiones y otros tiempos: no se
  mezclan sus medianas con el resultado final `.txt`. Script, fixture-hashes y
  resultados se retienen bajo `work/checkpoint-membership-batch-20261025`.

C149 local / `0.2.125-checkpoint-startup-test` (`d6347e1`): tres focales de
identidad aprobados. Reconstrucción autenticada; frente a C148 cambian solo
Gradle, marcador, ArchiveOrder, Updater y UpdaterTest. Tras fallos de compresión
y reinicios del servidor, se esperó al proceso serializado existente sin iniciar
otro build: **BUILD SUCCESSFUL en 9m 36s**, 68 tareas. No se repitió reconstrucción
ni tests completados.

El audit falló cerrado ante el nuevo descriptor D8 exacto
`Lapp/gamenative/ForeverClientArchiveOrder$$ExternalSyntheticLambda0;`. Su DEX se inspeccionó:
delega únicamente al formateador `ordinalsOf` de literales hex validados. Se
admitió solo ese nombre (`569b52f`), con test que sigue rechazando el vecino
`Lambda1` y revisión independiente sin hallazgos; no se añadió un prefijo amplio.
El APK no cambió ni se recompiló para modificar el auditor del host.

Auditoría final **PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT**, 2.226 fuentes y 178
payloads: **484.909.535 bytes**, SHA-256
`2c463ab342b07265834230053d08f380901c8e3b59a0a3ca5545a147605440d6`.
Recibo, logs, pruebas de origen DEX y auditoría persisten bajo
`work/download-checkpoint-c149-20261025`. Con updater inactivo, sin guest y APK
cotejado, `install -r` iniciado a las 21:54:30 UTC confirmó versión 149 y Home.
Sin reset, desinstalación ni publicación.

Preparación observada de C149:

- Abrir gestor único **21:56:49.674104 UTC**; «Continuar» único
  **21:57:11.261832 UTC**. Primera respuesta observada a **+2,963 s**.
- Inspector **+113,067–291,754 s**, **178.687 ms**; 1.144.978 objetos, sin rehashes.
  El stream completo registró 4.473 bloques y 178.688 ms; sus relojes se anidan.
- Resumen visible a **+440,830 s (7 min 21 s)**: versión `1.60.1.70532`, Unidad
  USB, descarga **62,8 GiB**, adicional **65,0 GiB**, cotas app/cliente
  **1,7/63,2 GiB**. Se detuvo **sin confirmar payload**.
- El inspector comenzó antes que en C148 (+201,369 s), pero no hay A/B controlado
  de caché/cohorte ni un tiempo exacto de resumen C148. No confundir estos 440,830 s
  hasta el resumen con los 948/1.454 s posteriores a Descargar/Resume de C148.
  Falta medir el inicio real de transferencia con el plan C149 autorizado.

El usuario respondió después **«Confirmar y medir»** para ese plan exacto. Se
lanzó una única prueba de Descargar/pausa/Resume/reapertura, con transferencia
breve y pausa final; no autoriza otros costes ni descargar todo el cliente. El
resultado completo y la reconexión se registran a continuación. Los artefactos C149
están en `physical-trial/prepare-summary.json` y `download-resume-summary.json`.

### C149: recorrido completo y espera restante

- Un «Descargar», Enter **22:29:36.175175 UTC**, dio respuesta observada a +3,011 s
  y transferencia positiva visible a **+860,836 s (14 min 21 s)**: 25,9 MiB.
  Una pausa normal a las **22:44:25.696522 UTC** conservó el progreso.
- Un «Reanudar», Enter **22:45:05.374072 UTC**, respondió a +2,631 s y llegó a
  transferencia positiva visible a **+1.225,989 s (20 min 26 s)**, sin reconfirmación.
- «Volver» → Home activo → «Ver descarga» reabrió el progreso: **mismo proceso,
  mismo servicio, un servicio activo y sin otra confirmación**. Pausa final a las
  **23:06:25.759508 UTC**; comprobación posterior: «En pausa» y **cero servicios
  updater activos**. Resultado retenido:
  `ONE_ACTION_RESUME_AND_ACTIVE_UI_RECONNECT_OBSERVED`.
- Las ventanas desde transferencia visible hasta Pausar fueron 28,685 y 54,396 s.
  Son observaciones breves, no throughput sostenido ni instante exacto de primer
  byte de red. Los commits finales registraron **800/1.336 objetos** y
  **44.280.540/29.296.869 bytes escritos**, respectivamente; son contadores
  acumulados por ejecución, no cifras para sumar con cada commit intermedio.

| Desde la acción hasta transferencia visible | C148 | C149 | Diferencia observada |
|---|---|---|---|
| Descargar | 948,398 s | 860,836 s | 87,562 s antes |
| Reanudar | 1.453,953 s | 1.225,989 s | 227,964 s antes |

Mismo destino USB, versión 70532 y 1.144.978 objetos objetivo; el prefijo avanzó y
no se controlaron caché, temperatura ni cohorte. El muestreo incluye quince
segundos de espera y el dump. No se presenta esta diferencia con precisión de
milisegundos como una ganancia causal universal. C146 no llegó a transferencia
en su ventana acotada: no se inventa un tiempo final para incluirlo en esa tabla.

La mejora de una fase no resuelve aún la preparación larga. Las cargas C149 de
**357.329/358.129 objetos** tardaron **153.329/153.507 ms**. Sus primeros pedidos de
fuente llegaron a +848,519/+1.213,053 s (un pedido puede reutilizar un raw). Persisten
consultas escalares por frame en inspección/carga/verificador posteriores.
Agrupar esos lectores exige separar validación y mutación/ACK, por lo que esa
propuesta se difirió inicialmente. La alternativa local
verificada reutiliza únicamente los metadatos autoritativos del objeto actual
del cursor, con ordinal de origen y fallback al lookup existente. No rediseña los
lectores, anticipa objetos ni cambia CRC, cola recuperable, `cpByteEnd` o límites.
**14/20 minutos no se dan por aceptación final del objetivo.**

La alternativa `c823708` todavía no está instalada. La inspección del responsable detectó
una consulta al bit de checkpoint con el contador ya incrementado, en vez del
ordinal del objeto actual. Se devolvió a reparación con una regresión de frontera
antes de aceptar el cambio. La prueba legacy falló con el desplazamiento y pasa
tras usar `sourceOrdinal`; también pasan cancelación con dieciséis parciales y
reanudación, y prefijo solapado: **tres tests, cero fallos/errores/omitidos**, XML
2026-10-26T06:17:02. Los XML rojo/verde se conservan en
`work/updater-o1-ordinal-repair-20261026`; el responsable leyó ambos y cotejó las
dos fuentes finales con las compiladas. La prueba de solapamiento anterior no
bastaba para acreditar la clasificación. Este defecto no pertenece a C149 y no
se repitió su ensayo para corregirlo.

La primera revisión independiente no pudo acceder al repositorio y no se cuenta
como aprobada. La revisión posterior analizó una captura del código final y sus
contratos de catálogo/CASC, sin hallazgos accionables; el responsable verificó la
correspondencia de esa captura y sus hashes con los archivos reales. Es revisión
independiente de código capturado, no verificación independiente de filesystem ni
de pruebas. El cambio conserva fallback y comprobaciones existentes; la mejora
de tiempo Android queda pendiente de una candidata diferenciada.

El 2026-10-26 el usuario autorizó el refactor focal: «aplica el refactor si crees
que es la mejor solución». Se elige ampliar los lotes únicamente a inspección,
carga y verificación de prefijo del checkpoint, con validación de CRC/tipo y
metadatos antes de aplicación, recuperación o ACK. `cpByteEnd`, comprobaciones
frescas por grupo, crypto, ordinales globales, cancelación y límites permanecen.
No se anticipará el stream de entrada del escritor. Se requieren regresiones de
más de 256 frames y fallos con cola incompleta, además de revisión independiente.

La instantánea pequeña C150 se reconstruyó/autenticó una vez, sin compilar ni
instalar. Su recibo y árbol se conservan congelados; se pospone esa APK intermedia
para medir ambos cambios juntos después de una reconstrucción autenticada fresca.
La ampliación ya está implementada y verificada localmente; **todavía no está
acreditada en Android**.

### Lectores por lotes: implementación y barreras verificadas

`fba5ac8` agrupa únicamente la admisión de metadatos de los tres lectores del
checkpoint, con lotes locales de hasta 256 registros. CRC/tipo y límites se
comprueban antes de almacenar cada frame; cada resultado autoritativo se contrasta
con su EKey/CKey/tamaños y ordinal, conservando la detección global de duplicados.
La entrada del writer sigue siendo escalar: no se anticipan 256 objetos bajo la
cota raw de 34 claves/1 GiB. La fila admitida actual de `c823708` se conserva.

La carga valida todos los pendientes **antes** de truncar una cola incompleta,
finalizar o reparar. El verificador respeta `cpByteEnd`, crypto y bindings frescos
por grupo. El ACK del grupo anterior espera la admisión de metadatos del primer
registro siguiente, como antes; la crypto del siguiente solo condiciona el ACK
de su propio grupo. No se revoca ni se adelanta una confirmación por agrupar SQL.

**Once focales únicos aprobados** entre las ejecuciones retenidas, no toda la suite:
equivalencia escalar/bulk con 300 frames y duplicado entre lotes; metadata inválida
y cancelación antes de reparar una cola incompleta, seguida de reparación válida;
primera metadata del grupo siguiente inválida sin ACK previo; retiro exacto por
grupo; checkpoint creciente y reemplazo de inodo; caminos legacy y reanudación
con dieciséis parciales. Los últimos tres tests dieron cero fallos/errores/omitidos
(XML 2026-10-26T07:08:43): el fixture corregido, el caso de cola incompleta ampliado
y la nueva barrera de grupo, sin relanzar los demás verdes. Se ajustó la declaración
de las dos claves que el fixture quería retirar en modo exacto, no la política de
ACK de producción. También se
actualizó una aserción de diagnóstico: los lectores ahora prueban llamadas bulk
en lugar de exigir fallbacks escalares. Se retuvieron los fallos y su diagnóstico.

Revisión semántica independiente del código capturado, sin hallazgos accionables;
no tuvo acceso a filesystem ni ejecutó pruebas. El responsable leyó el diff real,
comprobó los cuatro hashes contra las fuentes compiladas y verificó el staged
snapshot antes de commit. Logs y XML permanecen en
`work/checkpoint-readers-refactor-20261026`, fuera del repositorio.

La candidata combinada será **C151 / `0.2.127-checkpoint-readers-test`** (`3ca861a`):
postimages de Casc/CascTest actualizadas solo a los hashes revisados, con preimages
históricas, inputs externos y native/privacy pins intactos. Cinco focales Python de
identidad, staging y rechazo de preimage ajena pasan. Faltan su reconstrucción,
auditoría exacta y medida del recorrido completo; estos checks no demuestran todavía
una reducción del tiempo de Android.

### Retención de evidencia

Los artefactos transitorios anteriores bajo `/tmp/opencode` ya no están
disponibles; sus veredictos y cifras fueron leídos y registrados en este informe
durante cada prueba. No se presentan esos paths como capturas aún recuperables ni
se repiten pruebas verdes o una descarga antigua para recrearlos. Los recibos,
builds y auditorías autenticados siguen fuera del repositorio. La nueva captura
C148 se guarda en `work/download-resume-c148-20261025/physical-trial`, no en `/tmp`.

## Auditoría del objetivo (cierre pendiente)

| Entregable | Evidencia | Pendiente |
|---|---|---|
| Menor preparación al iniciar/reanudar | Menos trabajo host; C149 completa 860,836/1.225,989 s frente a 948,398/1.453,953 s C148 | Aún 14/20 minutos; siguiente optimización local y medida completa pendientes, sin extrapolaciones |
| Primera pulsación efectiva, sin repetir | Flujo físico 3/1 acciones; C149 reanuda y reabre UI con el mismo proceso/servicio, sin reconfirmar ni duplicar | Verificar que la siguiente candidata conserve el resultado; sin medida subsegundo ni doble toque deliberado |
| Garantías intactas | Reviews/focales de la fila actual y de los lectores, recuperación/tail/ACK/cpByteEnd probados; cuatro guardas conservadas | Auditoría y prueba física de la candidata combinada; no se ejecutó toda la suite |
| Entrega versionada y honesta | Baseline y C147–C149 auditadas/instaladas/probadas sin reset; artefactos externos; pausa final y cero servicios | Completar nuevo ajuste y auditoría final del objetivo, no cerrar por ahorro aislado |
