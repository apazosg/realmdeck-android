---
id: client-resume-catalog-pin-2026-10-20
state: in-progress
date: 2026-10-20
---

## Summary

Incidencia encontrada al reanudar después del cambio de marca C116. No se ha
confirmado ninguna descarga nueva ni ejecutado recuperación/reset. El bloqueo
no es una recuperación fallida: el planner encuentra el staging de la descarga
original, pero el catálogo activo ya no corresponde al que lo creó.

## Content

### Causa comprobada mediante lectura, sin cambiar datos

- Misma versión visible70450 y build-config
  `84bea4233331e390fda8724c6f91ffa5`; CDN-config cambió de
  `f065eade5276a5ac05606e60a4c5a1f7` a `86e142712006071d5eb4b60e825c1f6e`.
- `preparePublishedVersion()` publicó el catálogo nuevo antes de planificar.
  Catálogo anterior SHA256`8f77052def9bb39375bafa9b195904da2e5151cd6b986951899312b5ff4e2be2`;
  nuevo`17a60f2633adce0859a3d1fed6a4a3b82fbd346296337ef297d0129576789e14`.
  Ambos archivos cacheados y sus `target.json` siguen presentes.
- La clave de workspace incluye el hash del catálogo. Con el catálogo anterior
  se reproduce exactamente `0c337db837ad1fcbf521e2585d17416ca9dff2fe67e7143999adf55f33f8a1a1`,
  donde está el recibo/checkpoint preservado. Con el nuevo da
  `5be78cb0740047c3dbb24c69c42ad3ee8c8af601a8ec132c09deb9e41c445071`, inexistente.
- Journal privado: solo `.lock`; no journal/tmp. No existe namespace de backup.
  Staging USB contiene solo el UUID del recibo, con `.owner` SHA256
  `9d90e6b55b01f9bdf013b05c71c835883003e2ea966366f4cd47b72c27f55011`, correcto.
  No hay evidencia de journal interrumpido ni staging ajeno. El planner nuevo no
  encuentra su recibo porque busca con otra identidad y falla cerrado.
- Tres ficheros de control idénticos y938819objetos/74581621578bytes encoded
  registrados; esto no sustituye la verificación completa de los segmentos CASC.
  Evidencia en `/home/adrian/wowforever-artifacts/work/launcher-branding-20261020/forensics/`.

### Propuesta mínima aprobada

1. Validar completamente el contexto original cacheado contra destino, recibo,
   marcador, catálogo y configs. No inferir contenido equivalente a partir del
   número de versión ni migrar el checkpoint al catálogo nuevo.
2. Restaurar el enlace al contexto original solo con evidencia suficiente y los
   validadores existentes; conservar la admisión nueva como evidencia/rollback.
   No editar recibo, checkpoint, owner, identidad ni ficheros de cliente.
3. Corregir el flujo para no sustituir el contexto de una descarga pendiente al
   consultar metadatos nuevos. Reanudar la descarga congelada; si cambia realmente
   el objetivo o falta evidencia, parar y pedir decisión, no resetear en silencio.
4. Corregir el mensaje de esta puerta: necesita contexto/recuperación antes de
   planificar; no afirmar que una recuperación falló si no se ejecutó.

No ampliación de formatos ni opciones generales, refactor del motor o migración
entre catálogos. No `recover()` a ciegas, clear-data, repair, reset ni nueva descarga
completa. Identidades/allowlists/baseline/payloads intactos. El trabajo del icono
de Android queda separado y no condiciona dejar la transferencia funcionando.

## Implementation / Action Items

- [x] 1. Usuario aprueba «Reparar y reanudar (Recomendado)» el2026-10-20 antes
  de escribir estado/código o reintentar. Candidata117/`0.2.93-pinned-resume`.
- [x] 2. Reproducir con dos catálogos: misma versión, distinto CDN/hash; probar el
  vínculo al workspace original y validar íntegramente sus metadatos cacheados.
  Si no se puede acreditar un campo, no reconstruirlo ni inventar su procedencia.
- [x] 3. Implementar el menor guard de conservación de contexto y mensaje correcto;
  pocos tests de regresión con fixtures existentes, sin harness nuevo ni matriz.
- [x] 4. Revisar independientemente los límites de identidad/datos y los tests;
  reconstruir/compilar/auditar nueva candidata sin relajar procedencia.
- [ ] 5. Con ejecución detenida, aplicar la restauración autorizada e instalar sin
  borrar datos. Tres controles byte-idénticos; no tocar los segmentos ni staging.
- [ ] 6. Un único intento nuevo autorizado con contexto original validado, misma
 70450 ARM64/enUS y USB6AD6-EF0E, costes≤31,6GiB/33,5GiB. Confirmar solo al cumplir
  todo; probar nuevos commits, cola0/objeto cifrado/FGS y dejar Inicio descargando.
- [ ] 7. Actualizar handoff/estado/evidencia y cerrar en `done/` si pasa. No declarar
  instalación final, activación, juego ni suite global verde.

## Open Issues

- **Aprobado expresamente reparar y reanudar.** No autoriza resetear CASC,
  migrar el checkpoint ni cambiar de cliente/destino.
- Contexto original y procedencia disponible validados; C117 compilada/auditada
  y seis tests JVM pasan, ninguno omitido. Despliegue/readmisión/reanudación
  autorizados en ejecución; falta comprobar sus informes físicos, sin presumir
  éxito ni modificar los límites de los tres controles.
- El icono antiguo en la ficha de Android sigue sin causa probada; arranque nativo
  y UI C115 preservada sí están comprobados. No limpiar caché/tema/launcher.

### Evidencia durante la ejecución

- Copiados solo los dos catálogos cacheados y configs a artefactos externos, sin
  escribir estado en el dispositivo. Ambos SQLite tienen99774464bytes y los SHA
  anteriores. El original pasa `quick_check`; identidad, arquitectura/locale y
  entrada del ejecutable coinciden con su `target.json`. Configs originales:
  tamaño/SHA256/MD5 correctos; el vínculo al workspace original se reproduce.
- El digest oficial original no se inventa: el propio SQLite original registra
  `metadata.official_versions_sha256` =
  `ab8dab6f16863c5b8a6186e3d851c335e3d6981c12c154e0956a3147be9cec43`.
  Se conserva esa evidencia ligada al hash del catálogo; no se dispone aquí del
  cuerpo original de la respuesta oficial y no se afirma haberlo revalidado.
- Candidata de admisión original reconstruida **solo en el host**, SHA256
  `7f78b6eb5c620e3080592d99b70dc535b1d04916500186b24f2bebe25bd21b44`.
  Se invocó el validador estándar sin modificar de C116
  `parseActiveCatalogReceipt$app_modernDebug` mediante una sonda externa pequeña:
  **PASS**. Único fichero auxiliar temporal en `code_cache`, retirado al terminar;
  no publicación de admisión ni modificación de los controles de descarga.
- Checkpoint/recibo/preferencias conservan sus tres SHA y la admisión activa
  sigue siendo la nueva (`7dde58b0f4a555894ce982d0fb7aaf92b04f539fc30b416abce608079652ae88`).
  Evidencia externa: `work/client-resume-pin-20261020/`
  (`original-context-validation.json`, `standard-app-validator.log`,
  `after-validator-control-sha256.txt`).
- Primera revisión del guard: requiere cerrar el intervalo entre comprobar
  pendientes y publicar metadatos, y no ignorar workspaces de otros destinos.
  Reparación acotada solicitada antes de compilar; no basta con operar a mano
  en una ventana quieta para dar por correcto el comportamiento de producción.
- Reparación final revisada independientemente sin bloqueantes: lease global
  mantenido hasta publicar, guard también sin admisión previa, evidencia privada
  de cualquier workspace (incluido el seleccionado sin recibo) y estados
  ambiguos bloqueados sin recuperar/resetear. `hasPendingTransaction` original
  conservado byte-idéntico. Los tests nuevos comparten realmente los directorios
  privados de la app entre destinos y comparan bytes, no solo tamaños.
- Límite de pruebas explícito: no se añadió harness para simular un volumen
  seleccionado en Robolectric. Los seis métodos focales cubren el Updater, la
  decisión de identidad y los mensajes; el cableado completo del Service debe
  verificarse también en la reanudación física, sin afirmar una prueba de
  integración JVM que no existe.
- Ayudante externo `RestoreOriginalContext.java` compilado pero **no ejecutado**:
  exige C117, candidata con SHA exacto y admisión actual conocida; llama al parser
  y publicador atómico estándar de la propia app, no escribe el estado CASC.
  Solo se usará en la ventana detenida tras validar la APK y la ausencia de
  procesos/servicio que puedan cachear o modificar el contexto.
- Sonda externa del icono de Android abortada por el sistema (salida137 al
  inicializar contexto Android); no prueba la causa del icono antiguo de MIUI.
  Temporal retirado y los cuatro SHA (tres controles + admisión activa) siguen
  idénticos. No se repite a ciegas ni condiciona la reanudación de la descarga.
- C117/`0.2.93-pinned-resume`: seis casos JVM focales pasan (4Updater,1Service
  de identidad,1UiText), cero fallos/errores/omitidos, según los XML JUnit reales.
  Compilación y auditoría canónicas pasan:2196fuentes/178payloads, APK484002123bytes,
  SHA256`085bf2633124068501720846aa022e020a95f2acd25356e4d105c72787efc1c6`.
  Árbol inmutable: `arm64-prototype/client-manager-code117-pinned-resume/`.
  Sin relajar baseline/allowlists ni repetir la suite global histórica.
