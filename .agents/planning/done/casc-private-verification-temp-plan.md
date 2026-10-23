---
id: casc-private-verification-temp-2026-10-18
state: done
date: 2026-10-18
---

## Summary

Continuación propuesta tras estabilizar y medir c99. El usuario pide eliminar la
lentitud de descarga y diagnosticar «Comprobando archivos». No se propone saltar
hashes ni reducir durabilidad: solo evitar operaciones de temporales en el USB.
Plan aprobado por la orden posterior del usuario de continuar. La prueba de c99
se cerró con pausa controlada; se conserva como baseline, sin descarga activa.

## Content

### Evidencia

- Android HTTPS OVH:10MiB en0,733s (~114,5Mbps). El enlace no explica~0,1–0,3MiB/s.
- Catálogo real:1.144.978objetos. Escaneo aislado91→70,4s tras optimizar conversión
  hex; en la app c99 los dos scans medidos tardan198,827 y200,291s. Esos contextos
  NO se equiparan. Cargar18.362frames del checkpoint tarda1,906s.
- Verificar500objetos reales desde USB bajo shell, usando el método de producción:
  500/500 válidos;2,945–9,219s. No es la latencia del proceso app completo.
- En el replay real de c99,34/40muestras del thread28739 esperan
  `request_wait_answer` (FUSE); operaciones de metadatos predominantes:
  fstatat13,openat10,unlinkat3,fstat1. Las muestras no son un perfil exhaustivo.
- Baseline real c99 cerrado por pausa controlada:checkpoint cargado en1,906s,
  1.024objetos verificados a134,954s y2.048 a264,489s. Proyección~40min para los
  18.362objetos (NO tiempo total medido). Supera ampliamente la estimación de la
  sonda shell. FGS activo, pausa en backend0,460s; checkpoint byte-idéntico y
  1.430.491.739bytes encoded conservados. No se llegó a nuevo payload ni a medir
  tasa/fondo c99; no presentarlos como validados.
- `verifyExistingIncrementalObject` copia un objeto del archivo USB a
  `.forever-casc-incremental-object.tmp`, también en USB, lo relee para EKey/BLTE y
  lo borra. `writeVerifiedSourceToTemporary` usa el mismo temporal antes de append.
- Artefactos externos: `work/c98-resume-review-20261018/` y
  `work/c98-scan-profile-20261018/`. `simpleperf` quedó bloqueado por
  `security.perf_harden`; no se cambió esa propiedad ni permisos.

### Decisiones propuestas

1. Solo para el writer resumable del launcher, ubicar el único temporal de
   verificación en el workspace privado ya ligado a identidad/recibo del stage.
   El writer incremental antiguo sin ese workspace conserva su comportamiento.
2. Mantener lectura del objeto original desde CASC, todas las comprobaciones
   EKey/BLTE/CKey aplicables, tamaños, orden, fsync, checkpoint y revalidate por
   objeto. No declarar un bloque válido por fecha, tamaño o un flag de caché.
3. Mantener segmentos finales en USB. No mover cliente, runtime, WTF ni Data
   publicado. Ninguna opción nueva de usuario ni dependencia.
4. Costear como máximo un temporal adicional del tamaño del objeto mayor en el
   volumen privado, además del prefetch. Recalcular plan y confirmar el coste real;
   no reutilizar un consentimiento que no lo cubra.
5. Reconocer y recuperar tanto el temporal USB anterior como el nuevo privado:
   exigir owner/identidad válidos, nombre exacto, archivo regular/no-symlink y cota
   de tamaño. Nunca borrar archivos desconocidos ni limpiar el workspace a ciegas.

### No objetivos

- No batching de checkpoints/fsync, no commits paralelos, no cambio del formato
  de frames ni nuevas garantías de caché entre sesiones.
- No resolver aquí Transaction byte-idéntica/no-op (bug anterior separado).
- No optimizar a la vez SQL MAX, capacidad inicial de índices, runtime o scheduler.
  Son posibles trabajos posteriores, no requisitos de este cambio.

### Contrato mínimo de implementación

- No añadir parámetros/opciones al writer: el resumable ya recibe
  `checkpointDirectory` privado y ligado a identidad. Usarlo para el temporal
  de nombre exacto `.forever-casc-incremental-object.tmp`. Sin checkpoint privado,
  el writer antiguo mantiene el temporal en su output como antes.
- El nuevo temporal solo puede limpiarse tras demostrar propiedad/identidad del
  workspace y sus marcadores de resume. Si aparece sin owner/checkpoint válido,
  rechazarlo sin borrar ni crear una autoridad retroactiva para ese archivo.
- Aceptar como scratch conocido el nombre privado en inspección/limpieza del
  Updater; validar tipo, no-symlink y tamaño máximo encoded. Costear un objeto
  adicional privado y quitar su reserva del destino. Mantener recuperación del
  nombre USB antiguo con las mismas cotas y sin tocar archivos desconocidos.
- Conservación antes del cambio:18.362objetos/1.430.491.739bytes; checkpoint SHA256
  `be35f98803f6253db6fda16b779016e95270a30965f77f3b91984b67d7a91493`,
  recibo stage `8b9f81c3c912382880cb841bd03948e03bb53d06cb7944f85a946612e46bb871`.
  Artefactos `work/c100-private-temp-20261018/`.

## Implementation / Action Items

- [x] 1. Aprobación del plan; registrar baseline físico final c99 antes del cambio.
- [x] 2. Revisar recuperación/cotas de ambos temporales y cerrar el contrato mínimo
  de ubicación entre Casc y Updater antes de editar.
- [x] 3. Implementar traslado del temporal y contabilidad/validación de workspace;
  mantener baseline JSON/apply_overlay inmutables y actualizar postimage CASC.
  - Implementado en4archivosCasc/Updater y susTests; entregado en c100. Primera
    validación19tests(12Updater+7Casc),0fallos/0skips en clone independiente
    `work/c100-private-temp-tests/c100`. No se modificaron artefactos c99 retenidos.
  - Revisión encontró2correcciones antes de admitirlo: borrar y forzar el
    temporal privado antes de descartar marcadores en reset; y rechazar también
    temporal USB huérfano antes de crear marcadores nuevos. Reparación/pruebas
    puntuales completadas; el primer reporte de orden de borrado no coincidía
    con el código leído, por lo que no se toma como autoridad de cierre.
    Orden final verificado:unlink del temporal→forceDirectory→unlink marcadores→
    force final. USB huérfano rechazado antes de crear marcadores. Hash fuente
    `42fb8bdbf8b76bb3e1d8789421de16263ae2a33df881ebfdc570f9bdce49872a`;
    test `2cf99617c3462df344e95e0378637ae1c0a9ca9b7026c20469e8d5fcf899d8bf`.
- [x] 4. Pruebas puntuales con infraestructura existente: pausa/caída con temporal
  privado, resto USB de c99, rechazo de symlink/desconocidos y mismo CASC resultante.
  No suites rutinarias, frameworks ni fixtures grandes nuevos.
  Inicial19tests/0fallos/0skips. Reparación6testsCasc/0fallos/0skips (incluye
  nuevo reset, orphan extendido y compatibilidad); Updater12byte-idénticos al
  verde previo, no repetidos. Repositorio y clon con hashes iguales; fuente
  compilada después de su edición y XML posterior. Reconstructor:3tests puntuales
  de versión/admisión/preimage en verde. Candidato100/0.2.76.
- [x] 5. Revisión independiente por riesgo de almacenamiento/recuperación; construir
  y auditar nueva versión sin alterar los artefactos c99 retenidos.
  Veredicto final sin bloqueos; c100 compilada3m25s y auditada. Instalada como
  100/0.2.76-private-casc-verification; SHA256APK
  `58f1096cf0d7a99fec772b3fe449f50ebd79978f2c1070986c04ebd5061ec6c2`.
  Receipts/fuentes99retención intactos; checkpoint/recibo móvil byte-idénticos
  antes/después de instalar. Artefactos `arm64-prototype/client-manager-code100-private-casc-verification/`.
- [x] 6. Instalar sin borrar datos; comprobar checkpoint/recibo antes/después y medir
  staged replay y commits reales. Documentar variabilidad y mejora/no mejora.
  Instalación/preservación comprobadas; plan83,7GiB/85,6GiB adicionales confirmado
  por UI dentro del coste autorizado. Revalidación completa de18.362objetos:
  **179,659s** desde inicio del writer;2.048objetos21,461s frente264,489s en c99
  (~12,3x en esa muestra comparable). El temporal privado se observó directamente.
  Primer nuevo payload comprometido tras la revalidación, sin perder el objeto E.
  Descarga en foreground60s y en background120s, FGS activo en todas las muestras;
  bytes committed crecientes,CRC/orden del checkpoint válidos.
  Resultado `REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED` en
  `work/c100-private-temp-20261018/c100-physical-result.json`.

## Open Issues

- La prueba física c99 se cerró con medición parcial del replay y pausa conservando
  los datos. Tiempo total y tasa posterior no medidos; comparar la misma muestra
  staged en la app nueva y no extrapolar la sonda shell como tiempo real del móvil.
- Debe comprobarse el presupuesto privado adicional con tamaños reales. Si rebasa
  el coste autorizado o no cabe, detenerse y presentar el nuevo plan al usuario.
  Medición actual:objeto encoded mayor141.273.036bytes (134,73MiB), extra máximo
  privado para este temporal. Teléfono con18.015.916KiB libres (~17,2GiB) antes del
  cambio. Catálogo real1.144.978filas/99.774.464bytes, copia READONLY externa en
  `work/c100-private-temp-20261018/catalog-70450.sqlite3`; los mismos bytes se
  descuentan del presupuesto del destino, no se presupone un nuevo pico total.
- El almacenamiento app-private evita FUSE del USB para el temporal, pero las
  aperturas del archivo CASC original y los fsync de salida seguirán existiendo.
  No prometer que este cambio por sí solo saturará100Mbps.
- Cierre: la mejora de revalidación está medida; descarga NUEVA sigue lenta:
  0,580MiB/s foreground y0,316MiB/s background en ventanas cortas; media conjunta
  ~0,403MiB/s en180,7s,proyección~59h entonces,no ETA garantizada. Perfil posterior:
  writer espera con frecuencia un future; los4workers pasan185/320muestras en
  poll de socket y116/320 en futex. No atribuir todo el coste restante a fsync.
  Se compara con el instalador `wowserver` en una operación posterior, sin
  cambios funcionales adicionales ni ampliación de este plan ya completado.
