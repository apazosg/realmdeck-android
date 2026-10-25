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
  optimización.** Se corrigió el diagnóstico del worker y se solicitó completar
  esa dependencia antes del siguiente intento; resultado pendiente.
- La suite `ForeverClientUpdateForegroundServiceTest` se lanzó offline con JDK17
  en otra copia aislada bajo
  `/home/adrian/wowforever-artifacts/work/download-resume-gate-20261025/GameNative`.
  No se ha recibido todavía resultado del test de la fuente final. Sus tres
  regresiones nuevas comprueban el protocolo pending/segundo reset/start, no
  interacción Compose. No se presenta como suite aprobada.
- Revisión independiente del algoritmo: sin hallazgos bloqueantes. Revisión UI:
  admisión pending validada; ajustes de Pausar y apertura pending inspeccionados
  por el responsable tras la corrección, sin prueba de pulsaciones en dispositivo.

Optimización versionada en `c1bc7a1`; no APK, operación de dispositivo ni mejora
física acreditada. La aceptación integrada y de recorrido completo sigue abierta.

## Auditoría del objetivo (cierre pendiente)

| Entregable | Evidencia | Pendiente |
|---|---|---|
| Menor preparación al iniciar/reanudar | Menos rehashes y tiempos host del inspector sin fuente; `c1bc7a1`, cuatro focales PASS | Recorrido completo y primer byte Android, sin confundir pausa viva y proceso muerto |
| Primera pulsación efectiva, sin repetir | Guard síncrono y pending handoff revisados en `ForeverClientManagement.kt`; conserva flujo habitual 3/1 acciones | Veredicto de suite final y prueba real de interacción; cambios UI aún sin commit |
| Garantías intactas | Review independiente de capacidad/cotas; qualify, replan, consentimiento, crypto, Wi-Fi y durabilidad no eliminados | Suite integrada completa no acreditada |
| Entrega versionada y honesta | Optimización y notas en commits separados; sin APK ni operación física | Versionar UI después de verificación; no cerrar objetivo/plan por el ahorro de un subcomponente |
