---
id: download-transfer-speed-2026-10-26
state: in-progress
date: 2026-10-26
---

# Velocidad de transferencia: objetos pequeños y grandes

## Summary

El usuario solicita un nuevo goal para investigar y optimizar la descarga de
datos del cliente tanto con archivos pequeños como grandes: comunica velocidades
de «5mb» que en ocasiones caen hasta «0.1». Las unidades y condiciones de esas
observaciones aún no están confirmadas; no se convierten en una línea base medida
ni en un mínimo de velocidad prometido.

Goal original: `5caa6403-5b8f-47dc-ba95-ba40fbbe686e`; continuación de una hora
`fd7baae8-8a14-47b0-8452-6d8f7434217e`; sucesor actual de seis horas autorizado:
`0d3ea00f-a91e-4702-88ad-febb37adb87f`. Este trabajo parte de C152 y se centra
en transferencia efectiva, no en volver a optimizar el inicio de la descarga.
El usuario aprobó este plan el 2026-10-26: «adelante. vamos a ello». Se ejecutan
diagnóstico, cambios mínimos y ensayos acotados dentro de las garantías y límites
descritos; no se repite esa aprobación tras un reinicio del servidor.
Tras agotar el límite temporal, el usuario autorizó «perfecto sigue . crea un
nuevo objetivo». El sucesor conserva el objetivo, los cinco criterios originales
y las fases verificadas; no reinicia mediciones ni debilita la aceptación.
El usuario aprobó después ampliar ese margen a seis horas. Es un límite de
seguridad, no una razón para consumirlo ni para cerrar con evidencia insuficiente.

## Content

### Punto de continuación comprobado

- Rama `perf/download-bottlenecks`, HEAD inicial `3c96b7e`, árbol limpio antes de
  crear este plan. No cambiar de rama, hacer merge, publicar ni push.
- [C151 → C152](../../docs/download-startup-followup-results.md) ya concluyó:
  confirmar → transferencia visible 422,589 s y Resume → transferencia visible
  667,502 s. Sus builds, pruebas, revisión y ensayo de inicio no se repiten.
  Las exposiciones breves de payload de ese ensayo no miden throughput sostenido.
- El último estado documentado de C152 es En pausa/Reanudar y cero servicios
  updater. Es histórico, no una comprobación fresca del teléfono.
- [C145](../../docs/download-performance-investigation.md) ya implementó grupos
  durables de hasta 16 objetos. Su ventana de 610,573 s obtuvo 0,40085 MB/s;
  el coste local de force bajó, pero no se acreditó una mejora global.
  La disponibilidad de fuentes y el backpressure siguen siendo pistas, no una
  causa aislada ni una razón para volver a cambiar force o cuotas.
- Los ensayos C143/C144/C145 recorrieron cohortes distintas. No son un A/B por
  tamaño y no explican por sí solos la caída reportada ahora.

### Alcance y contratos

Alcance principal: descargador Android funcional, bajo
`scripts/arm64-prototype/app/updater-gate/`, en particular
`ForeverClientUpdater.kt`, `ForeverClientUpdateTransfer.kt` y `ForeverCasc.kt`.
El cliente host no se modifica salvo que una causa demostrada lo haga necesario;
ese cambio de alcance se explicitaría antes de ejecutarse.

[US-008](../specs/US-008.md), especialmente AC-03/04/09/11/12 y
FR-003/004/010/012, ya contempla optimizaciones con mediciones comparables y
garantías intactas. También aplican [US-007](../specs/US-007.md) para almacenamiento
y [SYSTEM](../specs/SYSTEM.md). No se reescriben criterios aprobados para acomodar
una optimización. Si hace falta cambiar un contrato, primero se presenta esa
decisión y se actualiza la trazabilidad con la aprobación correspondiente.

Se mantienen:

- 16 fetch workers, 34 claves raw y presupuesto raw de 1 GiB; colas y memoria
  acotadas. Grupos durables de hasta 16 con flushes parciales por presión,
  cambio de segmento y fin.
- Batches HTTP de objetos exactamente contiguos, sin overfetch; validaciones
  estrictas de respuesta/range/tamaño y autenticación individual EKey/BLTE/CKey.
- Data force → checkpoint force → ACK → retiro de raw; mismo formato e identidad
  del checkpoint y recuperación del progreso ya reconocido.
- Política full/light de almacenamiento ya aprobada, rutas propias no-follow,
  exclusión Wine/updater, consentimiento y Wi-Fi-only sin fallback celular.

No se autorizan de forma implícita más workers/cuotas, otro orden de escritura,
menos verificaciones, cambios de formato o nuevas opciones. Tampoco reset,
clear-data, desinstalación, copia de toda la instalación, migración de volumen,
reinicio del teléfono ni descarga completa del cliente para medir.

### Método mínimo de diagnóstico

1. Distinguir archivo visible, objeto CASC y petición HTTP: varios objetos pequeños
   pueden compartir un batch y un archivo grande puede depender de varios objetos.
   Fijar cohortes por tamaño encoded real antes de comparar; documentar sus
   límites a partir del catálogo, sin ajustar grupos al resultado deseado.
2. Reutilizar los contadores existentes de pipeline, HTTP y writer. Actualmente
   hay histogramas por número de miembros del batch, esperas de futuros, claves/
   bytes reservados y relojes HTTP/writer, pero no un desglose completo por tamaño.
   Añadir solo agregados acotados si el análisis no puede resolver esa carencia;
   sin telemetría por objeto masiva, dependencias ni un framework nuevo.
3. Separar espera de respuesta, lectura de cuerpo, escritura privada, autenticación,
   disponibilidad de fuentes, reserva/backpressure y escritura/commit CASC. No
   llamar TTFB puro al reloj de cabeceras ni sumar tiempos concurrentes como fases
   consecutivas. Mantener preparación y activación fuera de la ventana payload.
4. Medir MB/s decimales de bytes útiles de red y objetos/s; distinguir bytes
   reintentados, reusados, escritos y reconocidos. Preferir contadores exactos;
   declarar incertidumbre si solo hay valores UI redondeados. Registrar tamaños,
   distribución de batches, actividad HTTP, esperas y variación por subventana.
5. Línea base con C152, o con el mismo código de transferencia más instrumentación
   mínima si es indispensable: ventanas payload de unos diez minutos y subventanas
   de dos minutos, con representación suficiente de tamaños pequeños y grandes.
   Si una ventana no contiene ambos, no inventar una comparación por tamaño.
   Las ventanas agregadas C152/C155 ya completadas se conservan. El seguimiento
   instrumental por tamaño utiliza seis minutos visibles por variante, fijados
   antes de observar resultados (tres subventanas de dos minutos). Es evaluación
   adicional de todos los callbacks, no sustitución del benchmark de diez minutos;
   si la representación resulta insuficiente, la aceptación permanece abierta.
6. Comparar antes/después en el mismo dispositivo, destino, runtime y build de
   cliente, declarando avance del checkpoint, objetos distintos, caché, red y
   temperatura. Priorizar cohortes equivalentes sin resetear progreso. Una
   comparación secuencial no controlada se etiqueta como observacional; una mejora
   sintética o local no se presenta como throughput físico demostrado.

Hipótesis iniciales, pendientes de medición: coste fijo por petición/objeto en
cohortes pequeñas; espera de la cabeza ordenada frente a objetos ya disponibles;
presión del presupuesto o del writer que deje menos descargas útiles activas.
No se presume que aumentar paralelismo, cambiar write order o ampliar buffers
resuelva ninguna de ellas. Se investiga primero el componente dominante actual.

### Seguridad del ensayo y continuidad

- Antes de operar: comprobar estado fresco, candidata instalada, Wi-Fi, destino,
  espacio, plan y ausencia de juego/reservas incompatibles. No interrumpir una
  sesión activa para medir ni reutilizar endpoints/PID históricos.
- El dispositivo dedicado dispone de autorización operativa general según la
  [referencia scrcpy](../references/device-control-scrcpy.md). Se mantienen el
  consentimiento real de la app y sus costes: no aceptar otro destino/coste ni
  dejar transfiriendo todo el cliente por una autorización de ensayo acotado.
- Reutilizar el control UHID y los mecanismos de observación existentes; capturar
  únicamente fases/contadores necesarios. Identificadores y configuración en
  `private/`; logs y artefactos fuera del repositorio.
- Acciones y observaciones se registran de forma durable. Tras un reinicio del
  servidor, comprobar el controlador y el estado antes de repetir acciones;
  nunca repetir Download, confirmaciones, Resume o ensayos por perder el Shell.
- Terminar cada ensayo mediante pausa cooperativa y comprobar progreso retenido
  y ausencia de trabajo de red/updater activo. No sustituir pausa por un kill.

### Revisión metodológica: descarga mixta y camino crítico (pendiente de aprobación)

El usuario pide medir más tiempo y recuerda que pequeños y grandes convivirán.
La prueba principal será **mixta, sobre el recorrido real**, no la suma de pruebas
puras ni una mejora de una clase que perjudique al conjunto. Los controles aislados
solo se usarán para distinguir hipótesis concretas.

1. **Autoridad y carga.** Usar el catálogo activo admitido de `1.60.1.70532`, no el
   asset bootstrap `1.60.1.70425`. Ya se copió en lectura, con la app idle, cotejando
   receipt/target/tamaño/SHA-256; los 9.344 y 9.857 frames nuevos C156/C157 coinciden
   exactamente con sus prefijos físicos activos. Al checkpoint 454.511, el siguiente
   prefijo de 20.000 contiene 16.010 pequeños, 3.747 intermedios y 243 grandes,
   1.484.255.557 bytes encoded. Es una previsión de orden, no de tiempo ni bytes red.
   Es un tramo `range`, no representación suficiente por sí solo del tráfico
   `direct` posterior; no se prometerá cobertura global basándose en ese tramo.
2. **Instrumentación, no tuning.** Mantener la estrategia actual. Añadir solo
   estados/contadores acotados en updater/transfer: muestreo de pared de 1 Hz con
   progreso lógico exacto, callbacks/HTTP activos, reserva y ready-ahead, espera
   real de la cabeza y su tamaño; actividad de cabeceras, lectura, IO privado,
   autenticación, publicación y callback de progreso. Registrar bytes consumidos
   del cuerpo HTTP (incluidos reintentos) separados de progreso útil/reuso; no
   llamarlos bytes wire ni sumar relojes concurrentes. Acumular espera de cabeza
   por tamaño; costes compartidos de batches mixtos permanecen compartidos.
   Sin telemetría por objeto masiva, tipos DEX auxiliares, dependencias ni opciones.
3. **Calibración focal.** Verificar que una espera conocida de futuro/lectura se
   observa en la categoría correcta y que excepciones/cancelación dejan contadores
   activos a cero y el sampler parado. Reutilizar latches/fixtures existentes, sin
   matrices de tests ni volver a correr los diez checks históricos sin motivo.
   Revisar de forma independiente la concurrencia diagnóstica y auditar la APK
   exacta con el auditor intacto antes de instalarla conservando el cliente.
4. **Diagnóstico mixto prolongado.** Ventana prefijada de **1.200 s de payload
   visible**, excluyendo preparación, subventanas de 120 s y umbral de pausa de
   6 GiB de progreso nuevo. El primer umbral alcanzado causa pausa cooperativa,
   no un corte destructivo; el drenaje conserva las cotas existentes de raws.
   Alcanzar el umbral de datos antes de tiempo deja una ventana censurada, no prueba de estabilidad de
   veinte minutos. Capturar mezcla real, tamaños/percentiles, origen range/direct,
   CPU/IO legibles y temperatura sin tocar seguridad ni datos del juego. Si una
   clase/origen no aparece suficientemente, queda explícitamente sin validar.
5. **Predicciones antes de cambios.** Distinguir cabeza pendiente con ready-ahead
   (bloqueo ordenado), cabeza pendiente sin fuentes listas y HTTP en lectura/cabeceras
   (espera de transporte/fuente), reserva llena sin espera de cabeza (consumidor/
   commit) y actividad de auth/progreso/IO. Son señales que pueden coexistir, no
   una partición causal automática ni prueba de limitación CDN. Elegir el coste
   dominante solo después de correlacionar tiempo de pared, bytes y sus relojes.
6. **Control discriminante y A/B.** Antes de optimizar, fijar un control capaz de
   descartar la hipótesis seleccionada. Si hace falta replay mixto en scratch,
   proponer primero su aislamiento, manifiesto fijo autenticado, orden/tamaños/
   agrupación, frío/caliente y límites; no inventar autoridad de catálogo ni
   modificar el checkpoint real. No implementar todavía otro harness o controles
   que omitan verificaciones. La comparación final requiere carga equivalente y
   evaluar el conjunto y cada tamaño/origen, no declarar éxito con el +17 % previo.

Esta revisión no modifica los cinco criterios ni autoriza nuevas cuotas, otro
orden o contratos. El trabajo ya verificado permanece verificado. **Solo está
completado el punto 1; no se ejecutarán los nuevos puntos hasta aprobar el diseño.**

### Aceptación y validación

- Línea base actual y comparación posterior con categorías pequeñas/grandes,
  unidades y límites explícitos; causa de la lentitud respaldada por evidencia,
  sin atribuir una diferencia entre cohortes al cambio de código.
- Una intervención mínima sobre el cuello probado y mejora de transferencia
  sustentada por mediciones. Evaluar ambos tamaños y cualquier regresión; no
  prometer sostener 5 MB/s ni considerar suficiente una preparación más rápida.
- Pruebas focales para el comportamiento tocado y fallos relevantes, reutilizando
  infraestructura. No repetir suites/builds ya verdes sin cambio o riesgo concreto.
- Si se toca concurrencia, integridad, recuperación o autoridad de release:
  revisión independiente del diff y sus evidencias antes de aceptar la candidata.
- Antes del ensayo de una APK nueva: reconstrucción autenticada, cotejo de fuentes,
  build y auditoría del APK exacto. Actualización conservando datos y progreso.
  Compilar/auditar no demuestra rendimiento.
- Informe con antes/después, método, límites y riesgos; commits atómicos solo de
  la tarea, staged diff revisado y estado final comprobado. Sin push/publicación.

## Implementation / Action Items

- [x] 1. Recuperar objetivo, rama, especificaciones y resultados ya completados.
- [x] 2. Aprobar este plan; aprobación explícita «adelante. vamos a ello».
- [x] 3. Caracterizar tamaños y revisar datos disponibles; precisar el desglose
  mínimo que falta, sin repetir las investigaciones de inicio C151/C152.
- [ ] 4. Obtener línea base actual de payload pequeño/grande y seleccionar el
  cuello de botella con evidencia suficiente.
- [x] 5. Implementar la mejora mínima y regresiones focales; revisar garantías.
- [ ] 6. Validar candidata autenticada y medir antes/después bajo condiciones
  comparables, con pausa final y progreso preservado.
- [ ] 6a. Completar la evaluación por tamaño: agregados acotados de callbacks
  homogéneos pequeños/intermedios/grandes, separando grupos mixtos, bytes útiles,
  reutilización y tiempo de servicio concurrente. Comparar código de referencia
  y optimizado con idéntica instrumentación y ensayos preservadores; no presentar
  ese tiempo como throughput global ni repetir heurísticas no justificadas.
- [ ] 7. Auditar todos los criterios, documentar límites, hacer commits propios
  y archivar el plan únicamente al completar la validación.

## Open Issues

- Pendiente aprobar la revisión de método anterior: observación acotada del camino
  crítico y diagnóstico mixto de veinte minutos antes de otro tuning; replay solo
  si una hipótesis lo necesita y con diseño específico previo. No hay descarga ni
  build nuevos activos. La caracterización de catálogo fue solo lectura.
- **Diagnóstico reabierto; no más ajustes heurísticos antes de aislar el cuello.**
  El usuario cuestiona representatividad y si los cambios atacan el coste dominante.
  C157 terminó 19:41:11 UTC, pausada, checkpoint 454.511 válido y cero trabajo:
  0,986562 MB/s frente a 1,881607 de C156, pero tamaños medios 38.891/77.227 bytes
  y solo 5/151 grandes. Small MB/servicio-s aumenta 12,53 %, con objetos y grupos
  distintos; tampoco prueba ganancia causal. La línea base y los checks locales
  siguen verificados, pero la selección del cuello dominante no lo está.
- Corrección metodológica antes de otro cambio de estrategia: definir cargas
  reproducibles pequeñas/grandes/mixtas con distribución justificada por el catálogo;
  controlar y declarar frío/caliente, red y almacenamiento; separar relojes de
  servicio concurrente de tiempo de pared/ocupación/esperas del camino crítico.
  Formular una predicción observable y un experimento de confirmación/descarte por
  hipótesis. Evaluar primero cómo hacerlo sin resetear progreso ni debilitar guardas;
  no crear otro benchmark o APK hasta tener ese diseño justificado.
- C157 instalada una vez, final 18:29:14 UTC, conservando el checkpoint exacto de
  444.654 objetos. Preparación terminada 18:35:11 sin confirmar payload, mismo
  cliente/USB y costes 57,8/60,0/1,7/58,3 GiB dentro de los límites anteriores.
  El controlador de ensayo de 360 s y su análisis terminaron una sola vez. No se
  repite C156/C157 ni se acepta el resultado como una mejora general.
- C156 terminó una vez 18:19:27 UTC en pausa y sin trabajo activo: 367,636 s
  visibles, 1,881607 MB/s; checkpoint 435.310 → 444.654, prefix/CRC válidos,
  9.344 objetos (7.611/1.582/151). Desglose homogéneo obtenido, sin comparativa
  optimizada todavía. La cadena falló solo en el informe por duplicar el último
  extremo de subventana; fix offline mínimo y partición de duraciones/bytes
  verificados, sin repetir el ensayo. Continuación separada y protegida únicamente
  para instalar/preparar C157, sin confirmar payload ni reiniciar progreso.
- Pareja instrumental nueva autenticada: C156/C157 Modern offline PASS en
  5 min 2 s / 5 min 23 s, ambas auditorías exactas PASS (2.226 fuentes matching,
  178 payloads; 2.222 idénticas C152). Solo updater/test/versión/marker difieren;
  misma instrumentación y auditor intacto. Pipeline host terminado 18:02:55 UTC.
  C156 instalada una vez 17:57:59 conservando 435.310 objetos, sin payload en el
  instalador; al corte 18:03:44 seguía preparando sin confirmación de payload.
  Ese corte es histórico; la referencia terminó después y se recuperó su informe.
  No repetir instalaciones/acciones/ensayos ya completados.
- La primera referencia C156 compiló, pero su auditoría rechazó la nueva clase
  auxiliar DEX de contadores; no se instaló. Se simplifican a siete
  `AtomicLongArray(4)` sin ampliar allowlists ni cambiar la auditoría. Dos checks
  focales nuevos PASS, JUnit 17:48:06 UTC, fuente `eb840e5a…` igual al repositorio.
  Nuevos receipts/builds/APKs en workspace externo separado; no reutilizar como
  aceptada la APK rechazada. Host build optimizado puede solaparse con preparación
  del teléfono de referencia, pero cada instalación exige su propia auditoría PASS.
- Desglose agregado implementado sin cambiar la estrategia: cuatro cohortes,
  siete contadores cada una, capturados antes de publicar futuros. Cuatro checks
  focales verdes y hashes iguales al repositorio; service concurrente no se llama
  throughput global. Se prepararán C156 de referencia (algoritmo C152) y C157
  optimizada (algoritmo C155), con exactamente el mismo delta instrumental,
  fuentes/inputs cotejados y APK auditada antes de cada ensayo preservador.
- C155 terminó 17:07:37 UTC en pausa, checkpoint 435.310 válido y cero trabajo
  activo. Ventana visible 615,474 s: 2,266 MB/s frente a 1,939 de C152, +16,90 %
  observado; pressure-tail singles 118 frente a 7.900 de C154. **No basta para
  cerrar la aceptación por tamaño:** el tamaño medio del intervalo pasó de
  48.760 a 93.745 bytes. La revisión independiente exige no confundir esa mezcla
  con una mejora de ambos tamaños. Los samples existentes son solo seis pequeños
  y un grande en C152, uno de cada categoría en C155; no se usan como prueba.
  Se añade el desglose mínimo previsto por el método y una comparación con la
  misma instrumentación, sin cambiar el algoritmo ni resetear el progreso.
- Aprobación recibida; línea base C152 y ensayo C153 terminados con ambos tamaños
  y pausa final. **C153 no supera la aceptación:** 0,872 MB/s durante 612,446 s
  frente a 1,939 MB/s/609,206 s de C152. Es una comparación secuencial con cohortes
  distintas, no una atribución causal del descenso del 55 %. Se descartó el cap 2
  y se verificó una alternativa; no se cierra el goal ni se prueban caps a ciegas.
- El código C153, siete regresiones focales, revisión independiente y auditoría
  canónica siguen verificados para esa versión; no acreditan rendimiento. No se
  repiten ese build, pruebas ni ensayo. La alternativa restaura la agrupación
  original y ha pasado sus propias verificaciones por la nueva concurrencia.
- C153 conservó al instalar los 394.140 objetos y requirió recuperar el resumen
  desde Inicio. Se confirmó una sola vez el mismo cliente/USB, 61,2 GiB de datos y
  63,4 GiB adicionales dentro de los límites consentidos. Fin 14:38:42 UTC:
  En pausa/Reanudar, checkpoint 407.694, cero HTTP/servicios y 7.648 callbacks
  finalizados. Prefix y CRC válidos; ningún raw inicial perdido. No se repite Download.
- Revisión del diagnóstico: más callbacks concurrentes no elevaron las peticiones/s
  observadas (11,23 → 11,76), y la espera de fuente aumentó. Se detectó polling
  incondicional de 100 ms en el presupuesto, sin aviso al liberar capacidad. La
  alternativa implementada despierta por liberación, con recheck atómico antes de
  esperar y fallback de 100 ms; no se afirma que sea la causa única. Regresión
  determinista RED→GREEN, nueve pruebas focales sin fallos/skips y revisión
  independiente sin defectos accionables. Sin cambiar cuotas, ACK/retiro, forces
  ni contrato. Su ensayo C154 no demuestra mejora; el riesgo de fragmentar batches
  que se había advertido requiere corregir la granularidad del despertar.
- C154 (`154`/`0.2.130-budget-wakeup-test`), fuente `41aa40e`, reconstruida y
  auditada: solo cuatro rutas fuente/test/versión/marker difieren de C152;
  2.222/2.226 iguales, mismos inputs, lineage, privacidad y 178 payloads. Build
  offline JDK 17 verde en 5 min 17 s y auditoría exacta PASS. Controlador único de
  instalación preservadora/preparación/ensayo terminado una vez. **C154 tampoco
  supera aceptación:** 1,447 MB/s/613,252 s frente a 1,939 MB/s de C152, diferencia
  observada −25,38 %, no causal A/B. Fin 15:59:12 UTC En pausa/Reanudar,
  checkpoint 420.170 con prefix/CRC válidos, cero HTTP/servicios y 10.246 callbacks
  finalizados. No se repite instalación, preparación, Download ni ensayo.
- Segunda revisión del plan, no prueba de otro cap: C154 registra 7.900 singles
  por fragmentación bajo presión frente a 379 de C152. Liberar/notificar tras cada
  raw permite que el productor consuma un único hueco mientras el writer retira
  el resto del grupo durable. Se investigará liberar esas reservas conjuntamente
  antes de avanzar el writer o esperar otra entrada, manteniendo los retiros y
  forces individuales. No inferir el fin del grupo de `commitEntries.isEmpty()`:
  durante rollover puede contener la siguiente fuente ya resuelta. No hace falta
  cambiar el writer CASC, sus pins o contrato para acumular retiros hasta ese
  punto del consumidor. La mejora de throughput sigue pendiente.
- Corrección local verificada: reservas de raws ya retirados se liberan en un
  solo monitor al avanzar el consumidor, tras flush parcial antes de esperar y
  al retornar el writer. Contador drenable reducido después de liberar; error
  conserva reservas hasta join/reset. Sin cambios CASC/pins ni cuotas. Misma
  regresión RED→GREEN, diez checks focales sin fallo/error/skip, copia probada
  igual al repositorio y revisión independiente del snapshot sin defectos
  accionables. C155 ya fue reconstruida, auditada y medida; la evaluación por
  tamaño permanece abierta, sin otra modificación de la estrategia.
- C155 (`155`/`0.2.131-coalesced-budget-test`), fuente `101a1cd`: mismas 2.226
  rutas, 2.222 idénticas a C152 y únicamente updater/test/versión/marker revisados;
  hashes generados iguales a GREEN, inputs/lineage/privacidad/payloads intactos.
  Build Modern offline JDK 17 PASS en 5 min 11 s; APK exacta 484.929.311 bytes,
  SHA-256 `6a5d9bae6beec1da56aa233aed329ef9747f4e2d4ccca3fb6c124b00b176acb3`,
  auditoría PASS con 2.226 fuentes coincidentes con el receipt y 178 payloads.
  Controlador físico único; instalación terminada 16:42:02 UTC preservando el
  checkpoint exacto de 420.170 objetos, sin Resume ni updater activo. Recupera
  el mismo cliente/USB dentro de los costes consentidos y termina en pausa
  cooperativa. Ensayo terminado una vez: 2,266 MB/s agregados; referencia primaria
  C152, no C153/C154. La comparación instrumentada por tamaño no repetirá ese
  ensayo ni reutilizará esos pocos samples como prueba.
- «5mb»/«0.1» son observaciones reportadas sin unidad confirmada. Las mediciones
  usarán MB/s decimales y condiciones explícitas; no hace falta suponer la unidad.
- Cohortes previas: pequeño ≤64 KiB; intermedio >64 KiB–1 MiB; grande >1 MiB.
  Catálogo base y frames se distinguen; se reutilizan contadores e inventarios
  antes de decidir si hace falta instrumentación adicional. Véase el
  [registro de diagnóstico](../../docs/download-transfer-speed-results.md).
- Línea base visible: 609,206 s, 1,939 MB/s, con límites por redondeo y categorías
  a partir del delta de frames; no se infieren velocidades aisladas por tamaño.
  La candidata debe contrastar mayor ocupación útil contra más coste de petición.
- Preflight fresco completado: C152 En pausa/Reanudar, sin updater ni guest,
  Wi-Fi y espacio disponibles, checkpoint de 369.794 objetos conservado. Ensayo
  C152 lanzado una sola vez con controlador durable en el directorio externo
  `download-transfer-speed-20261026/`; terminado en pausa con 394.140 objetos,
  cero HTTP/servicios y todos los callbacks cerrados. No se repite Resume.
  Si falta evidencia o acceso se registra el bloqueo, no se declara mejora.
- La intervención no altera contrato ni límites de seguridad. Cualquier cambio
  de seguridad, cuotas, orden de escritura o contrato requiere decisión separada.
