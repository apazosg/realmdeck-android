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

Goal: `5caa6403-5b8f-47dc-ba95-ba40fbbe686e`. Este trabajo parte de C152 y se centra
en transferencia efectiva, no en volver a optimizar el inicio de la descarga.
El usuario aprobó este plan el 2026-10-26: «adelante. vamos a ello». Se ejecutan
diagnóstico, cambios mínimos y ensayos acotados dentro de las garantías y límites
descritos; no se repite esa aprobación tras un reinicio del servidor.

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
- [x] 4. Obtener línea base actual de payload pequeño/grande y seleccionar el
  cuello de botella con evidencia suficiente.
- [x] 5. Implementar la mejora mínima y regresiones focales; revisar garantías.
- [ ] 6. Validar candidata autenticada y medir antes/después bajo condiciones
  comparables, con pausa final y progreso preservado.
- [ ] 7. Auditar todos los criterios, documentar límites, hacer commits propios
  y archivar el plan únicamente al completar la validación.

## Open Issues

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
  accionables. Falta reconstruir/auditar y medir la nueva candidata.
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
