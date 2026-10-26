---
id: download-startup-followup-2026-10-26
state: in-progress
date: 2026-10-26
---

## Summary

Continuación autorizada por el usuario: «si crees que podemos seguir optimizando
lo. continuemos, crea un goal». Objetivo nuevo
`786f802d-773f-4396-95f0-7126786de75e`; los objetivos anteriores siguen cerrados.
La referencia es C151: confirmar → transferencia visible 494,323 s; Resume →
transferencia visible 772,468 s; Continue → resumen 370,247 s. No se repiten su
build, pruebas ni ensayo completo ya verificados.

## Content

### Diagnóstico y decisión acotada

- Muestreo ART de C151 durante una nueva inspección, 2026-10-26: 60 s solicitados,
  reloj dual, intervalo 10 ms, sin desbordamiento ni desajustes de pila del hilo
  objetivo. Artefactos externos: `download-startup-followup-20261026/inspection-profile/`
  (`summary.json`, `sampling.trace`, `aggregate.json`). La traza no se versiona.
- Resume a las 09:21:53 UTC; captura 09:24:10–09:25:13; pausa normal 09:25:31.
  Estado final observado: En pausa/Reanudar; ningún Download ni coste nuevo
  confirmado. El muestreo instrumentado no es una nueva línea base de latencia.
- De 54,520 s de CPU atribuida al hilo de inspección, `PatternNative.compileImpl`
  representa 12,495 s (22,92 %). `validateIncrementalSpec` y
  `ForeverClientUpdateCatalog.normalizeHex` construyen patrones constantes por
  objeto. Los tiempos inclusivos del perfil se solapan y no se suman.
- Mejora elegida: construir una vez los mismos patrones hexadecimales de 32
  caracteres en CASC y catálogo. Se conserva cada comparación, su sensibilidad a
  mayúsculas, normalización, rechazo y mensaje de error. Solo se comparte el
  patrón inmutable, nunca un matcher ni resultados de validación.
- Es una optimización local reversible dentro del goal autorizado, sin cambio
  de contrato o decisión de producto. No se cambian consultas, slots de la tabla,
  decodificación hexadecimal, cadencia de comprobaciones ni autoridad de datos.
  Esas hipótesis quedan fuera de esta intervención.

### Contratos y verificación

US-008-AC-07/08/10/12 y FR-002/003/004/005/008/009/011 siguen aplicándose.
No se modifica la especificación: el comportamiento aprobado permanece igual.
Se mantienen consentimiento, EKey/CKey/CRC, recuperación, ACK/cpByteEnd, identidad,
espacio, Wi-Fi-only, exclusión Wine/updater y cotas de memoria/colas/prefetch.

Pruebas focales: normalización y rechazo de claves en las rutas afectadas;
inspección/membership, recuperación y solapamiento existentes representativos.
Revisión del pequeño diff de validación, fuente cotejada y auditoría del APK
exacto antes de actualizar. No se repiten checks verdes sin cambio o fallo que lo
justifique. La mejora no se acepta por el perfil ni por un microbenchmark.

La prueba física requiere otra candidata autenticada y consentimiento de sus
costes reales: confirmar → transferencia, pausa, un Resume, reapertura de UI con
una sola instancia y pausa final. Comparar con C151 declarando caché, avance del
checkpoint y muestreo; no inferir el primer byte exacto de la primera muestra UI.
Preservar progreso; sin publicación, reset ni costes implícitos. Configuración e
identificadores del dispositivo permanecen en `private/`.

### Verificación local completada

- Código: `36cb879`, reutilización de los patrones inmutables exactos en CASC y
  catálogo. No se comparten matchers. Seis pruebas focales únicas verdes en
  Modern/JDK17 offline: normalización/rechazo, lectura de catálogo y batching,
  fallo/cancelación antes de reparar cola, metadatos antes de ACK y solapamiento
  real del updater. Los cuatro archivos finales coinciden con la copia compilada.
- XML externos en `download-startup-followup-20261026/focused-tests/`:
  `initial-results/` (cinco verdes, 09:35:32–09:35:38 UTC) y
  `key-validation-results/` (prueba nueva corregida, 09:36:47). El único fallo
  inicial era una expectativa equivocada del test: el callback conserva el
  `originalSpec`, como ya hacía la API; se corrigió esa expectativa, no producción,
  y solo se reejecutó ese fallo. No es una corrección funcional ni una suite completa.
- Revisión independiente del snapshot capturado: ningún hallazgo accionable;
  mismos patrones/case/orden, estado del matcher por llamada y valores inmutables.
  El revisor no tenía acceso al filesystem ni ejecutó pruebas. Cotejos, hashes y
  XML fueron comprobados por el agente principal, no por el revisor.
- Candidata: C152, `0.2.128-regex-startup-test`. Sus hashes CASC revisados
  se autentican sin cambiar preimágenes históricas ni pins nativos/privacidad.
  Retag `269f3b3`; cinco pruebas Python de identidad, transformación y pin
  histórico/postimagen pasan (0,12 s).

### C152 autenticada e inspección inicial

- Reconstrucción fresca en `download-regex-c152-20261026/`, sin modificar los
  árboles C151/C150. Respecto al recibo C151 cambian exactamente seis rutas:
  Gradle, marcador funcional, CASC/catálogo y sus dos tests. Se cotejaron seis
  fuentes críticas; los cambios de patrones coinciden con el código probado.
- Build Modern/JDK17 offline, serializado: `BUILD SUCCESSFUL in 5m 57s`;
  68 tareas, 45 ejecutadas y 23 de caché. Auditoría sin cambios ni excepciones:
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2226 fuentes sin cambios y 178 payloads.
  APK de 484925891 bytes, SHA-256
  `252c87023016f0438588e1d6bb84a2aa9168b744d671878f49fb2933e2b348b6`.
- Precheck fresco: C151 En pausa, ningún servicio updater ni proceso guest.
  Actualización `install -r` sin reset; PackageManager C152 y Home visibles a las
  09:48:06 UTC. Recibo, build, auditoría e instalación se conservan fuera del repo.
- Una acción para abrir gestión y un Continue a las 09:49:28,020624 UTC; primer
  feedback observado a +2,824 s. Resumen a **300,074 s**, frente a 370,247 s de
  C151. Inspector: +111,317–257,830 s, `elapsedMs=146512`, cero rehashes;
  1144978 objetos/4473 bloques. C151 había registrado 188279 ms en su inspección
  inicial. Son observaciones con caché/checkpoint/temperatura no controlados,
  no una prueba causal ni la métrica completa hasta transferencia.
- Estado final: plan 1.60.1.70532, Unidad USB, descarga 62,5 GiB y espacio adicional
  64,7 GiB; app hasta 1,7 GiB y volumen cliente hasta 62,9 GiB. **No se pulsó
  Descargar durante esta observación inicial**; no prueba la reducción global.
- Evidencia externa: `download-regex-c152-20261026/physical-trial/prepare-summary.json`.
  El controlador tiene lock, guardia contra repetir acciones, JSON atómico y
  limpieza cooperativa; la captura conserva solo fases/contadores autorizados.

### Ensayo completo autorizado

El primer intento de preguntar fue interrumpido y no otorgó permiso. El usuario
pidió volver a preguntar y respondió explícitamente **«Confirmar y medir»** a los
costes C152 indicados arriba: prueba breve de Download, pausa, un Resume,
reapertura y pausa final, preservando progreso; no cliente completo ni otros
costes. Esa respuesta ya está registrada en el goal y no se repite tras reinicios.

Se inició una sola vez `physical-trial/download_resume.py`, con lock y guardia
contra sobreescribir/repetir un ensayo existente. Antes de Download exige C152,
el mismo plan aprobado, ningún servicio updater previo ni proceso guest. Registra
JSON atómico, fases/contadores filtrados y tiempos reales de Enter; pausa
cooperativamente tras cada transferencia visible. **Resultado pendiente**: no se
declara aún reducción adicional de confirmar/Resume frente a C151.

## Implementation / Action Items

- [x] 1. Conservar la referencia y comprobar repositorio/contratos/artefactos C151.
- [x] 2. Medir el coste restante y elegir una intervención mínima con alcance escrito.
- [x] 3. Reutilizar patrones constantes sin cambiar la semántica de validación.
- [x] 4. Validar pruebas focales y diff; revisión independiente del límite sensible.
- [x] 5. Preparar candidata autenticada, cotejar fuente y auditar APK exacto.
- [ ] 6. Medir otra reducción global y comprobar UX/consentimiento/progreso en Android.
- [ ] 7. Auditar criterios, documentar límites y cerrar con commits propios y árbol limpio.

## Open Issues

- El porcentaje del perfil incluye perturbación del sampler; no predice el ahorro
  global. Confirmar/Resume deben mejorar realmente respecto a C151.
- Quedan costes de tabla, conversiones hexadecimales y E/S, pero no se añaden
  optimizaciones especulativas a este cambio. Si aparece una decisión material,
  se documentará y resolverá antes de ampliar el alcance.
