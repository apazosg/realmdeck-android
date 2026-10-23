---
id: apk-update-notice-2026-10-23
state: draft
date: 2026-10-23
---

## Summary

El usuario solicita que la aplicación avise cuando exista una APK nueva y recuerda
que se publicaba en GitHub. Se propone un aviso dentro del launcher al abrirlo,
sin descarga ni instalación automáticas y sin alterar las actualizaciones del
cliente WoW. Pendiente de aprobación y de confirmar la publicación real.

## Content

### Hallazgos

- Rama inicial `main`, árbol de trabajo limpio.
- El remoto es `https://github.com/apazosg/wowforever.git`.
- La API pública de releases de ese repositorio devuelve HTTP 404: no permite
  confirmar las publicaciones; no demuestra que el repositorio no exista.
- `gh` no está instalado en este entorno.
- La UI funcional está en
  `scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt`;
  la receta canónica es `reconstruct_functional_source.py` en su directorio padre.
- US-004-AC-07 actualmente prohíbe comprobar actualizaciones al abrir la app.
  La excepción para avisos de APK requiere aprobación explícita; las consultas
  manuales del cliente bajo US-008 permanecerán sin cambios.

### Comportamiento propuesto para aprobación

- Consultar la publicación de APK en segundo plano al abrir el launcher, con
  límite temporal y sin bloquear la pantalla ni Jugar.
- Mostrar «Nueva versión disponible» únicamente si se demuestra que la versión
  publicada es posterior a la instalada. Comparar `versionCode` numérico,
  no nombres de versión por orden alfabético ni fechas de publicación.
- Ofrecer «Ver actualización», que abre la publicación HTTPS de GitHub en el
  navegador, y permitir cerrar el aviso. No descargar ni instalar la APK.
- Si no hay conexión, la respuesta es inválida o GitHub limita las consultas,
  continuar sin aviso y sin afirmar que la APK está al día.
- No consultar continuamente, pedir permisos de notificaciones ni introducir
  tareas de fondo cuando la app está cerrada. No interrumpir una partida.
- No incluir credenciales de GitHub en la APK. La fuente consultable sin secretos
  debe confirmarse antes de implementar.
- No publicar releases, cambiar su visibilidad, instalar en un dispositivo ni
  modificar el cliente/runtime como parte de este trabajo.

## Implementation / Action Items

- [ ] Confirmar URL de publicación, acceso público, canal y metadatos existentes.
- [ ] Cerrar las decisiones pendientes y obtener aprobación del comportamiento.
- [ ] Actualizar primero UC/AC de US-004 con la excepción exclusiva para APK;
  después derivar FR y casos de prueba trazables, conservando US-008.
- [ ] Implementar la consulta y el aviso mínimos en el launcher funcional,
  reutilizando la infraestructura existente. Acordar un metadato mínimo de
  `versionCode` si la publicación actual no lo ofrece; no inferirlo arbitrariamente
  de un tag. Mantener la reconstrucción y auditoría canónicas.
- [ ] Verificar con pruebas focalizadas: versión posterior frente a igual/anterior,
  datos inválidos/error de red y apertura/cierre del aviso sin bloquear el flujo
  principal. Revisar destinos HTTPS y ausencia de secretos.
- [ ] Validar trazabilidad y enlaces de la especificación; ejecutar los checks de
  reconstrucción/build disponibles sin instalar ni publicar. Registrar cualquier
  validación física o de artefacto que no pueda realizarse.
- [ ] Revisar diff, hacer commit de los cambios propios y archivar el plan al acabar.

## Open Issues

1. ¿Cuál es la URL exacta donde se publican las APK? El remoto local no permite
   confirmar releases mediante la API pública. Si es privado, hace falta decidir
   una fuente de avisos accesible sin incrustar tokens; no se cambiará la
   visibilidad ni se añadirá hosting sin autorización.
2. ¿Qué canal corresponde? Propuesta inicial: releases publicadas no draft y no
   prerelease; confirmar si las APK de prueba se publican como prereleases.
3. Tras inspeccionar una publicación real, confirmar cómo obtener su `versionCode`
   fiable. Cualquier cambio necesario en el proceso de publicación se concretará
   en este plan antes de implementarlo.
