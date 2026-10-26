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

## Implementation / Action Items

- [x] 1. Conservar la referencia y comprobar repositorio/contratos/artefactos C151.
- [x] 2. Medir el coste restante y elegir una intervención mínima con alcance escrito.
- [ ] 3. Reutilizar patrones constantes sin cambiar la semántica de validación.
- [ ] 4. Validar pruebas focales y diff; revisión independiente del límite sensible.
- [ ] 5. Preparar candidata autenticada, cotejar fuente y auditar APK exacto.
- [ ] 6. Medir otra reducción global y comprobar UX/consentimiento/progreso en Android.
- [ ] 7. Auditar criterios, documentar límites y cerrar con commits propios y árbol limpio.

## Open Issues

- El porcentaje del perfil incluye perturbación del sampler; no predice el ahorro
  global. Confirmar/Resume deben mejorar realmente respecto a C151.
- Quedan costes de tabla, conversiones hexadecimales y E/S, pero no se añaden
  optimizaciones especulativas a este cambio. Si aparece una decisión material,
  se documentará y resolverá antes de ampliar el alcance.
