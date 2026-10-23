---
id: launcher-guest-recovery-2026-10-21
state: in-progress
date: 2026-10-21
---

## Summary

Petición: «la apk debería ser capaz de cerrar las reservas abiertas».
Eliminar la dependencia del depurador para recuperar una sesión antigua, sin
confundir una reserva pendiente con Wine vivo ni quitar exclusión con el updater.
La incidencia C129 se recuperó por la API existente con observación Quiescent;
la recuperación normal desde la APK todavía falta en el flujo de Jugar.

## Content

### Comportamiento propuesto para aprobar

- Al cerrar el juego, intentar finalizar su reserva mediante la API existente y
  prueba de quiescencia; si la prueba falla, conservarla. No inferir cierre por
  desaparición de ventana, tiempo transcurrido o muerte de un único PID.
- Al pulsar Jugar, si queda una reserva de guest anterior, comprobar el runtime
  desde la app y recuperar únicamente la del destino seleccionado/coincidente,
  bajo las exclusiones y validaciones existentes. Si la recuperación termina,
  continuar el flujo de Jugar con preparación/validación fresca y consentimiento.
- Si Wine está activo, no recuperar ni iniciar otro juego. Si el estado es
  desconocido, no liberar: indicar brevemente que no se pudo comprobar el cierre
  y permitir reintentar. Mensajes distintos para activo, desconocido y recuperado.
- No recuperar registros de actualización con esta ruta; no borrar/editar JSON,
  no resetear almacenamiento, no forzar procesos ni reiniciar el teléfono.
- Recuperación en hilo de trabajo y sin duplicarla entre clics concurrentes;
  conservar identidad del destino, propiedad de ticket cuando exista y prueba
  fresca antes de liberar. Evitar nuevos recibos o frameworks de sesión.

### Impacto

- US-004: ampliar UC/AC del flujo Jugar/cierre con recuperación integrada.
- US-008: concretar que no se lanza mientras la recuperación sigue pendiente;
  solo puede continuar después de completarla por la API, no saltando la guarda.
- FR/NFR/TC afectados se derivarán tras aprobar este comportamiento. Conservar
  contratos existentes de integridad, concurrencia y privacidad.
- No mezclar la mejora general de Enviar diagnóstico; tiene plan separado.

## Implementation / Action Items

- [x] 1. Aprobar comportamiento anterior y actualizar UC/AC afectados antes de código.
  Aprobado por el usuario el 2026-10-22: «si hazlo».
- [x] 2. Inspeccionar cierre y recuperación existentes; concretar FR/NFR/TC y
  ejecutar regresiones focales: reserva antigua recuperable, activo/desconocido,
  otro destino y exclusión con updater/clic simultáneo usando fixtures existentes.
- [x] 3. Integrar la API existente con el mínimo cambio y mensajes breves; revisar
  independientemente propiedad de reservas, carreras y comportamiento fail-closed.
- [x] 4. Compilar/auditar candidata identificable y comitear los cambios validados
  localmente sin esperar a la disponibilidad del móvil.
- [ ] 5. Instalar conservando datos y probar Jugar/cierre/reapertura y recuperación
  sin depurador en el móvil. Instalación C130 completada el 2026-10-22 a las16:31 UTC,
  conservando datos y verificando el hash instalado; falta el ensayo funcional.
  Véase [evidencia del despliegue](../../docs/launcher-c130-deployment.md).

## Open Issues

- Comportamiento aprobado el 2026-10-22; la validación física depende de ADB disponible.
- Determinar si el callback normal de salida deja procesos reales pendientes:
  esta mejora no autoriza liberar ante Active/Unknown ni promete resolver un
  cierre incompleto de Wine solo recuperando registros.

## Local validation — 2026-10-22

- C130 `0.2.106-guest-recovery`: recuperación al principio de Jugar mediante la
  API existente; no se recuperan estados del updater. Se conserva la liberación
  asíncrona al cerrar, eliminando la llamada síncrona duplicada a rollback.
- FR/TC derivados de AC-09/10 de US-004 y AC-05 de US-008. Las pruebas focales
  reutilizan las guardas existentes; no hay ensayo físico del flujo completo.
- 32 pruebas host de reconstrucción/auditoría correctas; 131 JVM correctas sin
  fallos, errores ni omisiones. Gradle `BUILD SUCCESSFUL` (6m28s) y auditoría
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, sin ampliar admisión DEX ni cambiar JNI.
- Revisión independiente: aprobada, sin bloqueos de seguridad/concurrencia.
  Quedan mejoras menores: una negativa de la guarda local puede conservar el
  mensaje genérico «Cierra WoW»; `rollbackGuest` queda sin llamadores. No implican
  liberar sin prueba. Falta prueba directa de la orquestación de recuperación,
  además del ensayo físico aplazado.
- No se declara la suite global verde: una ejecución previa a corregir el pin
  C129 de un test dio 43 fallos/1355 correctos/4 omitidos; otra dejó 44 cabeceras
  de fallo sin resumen. No hubo comparación con el commit base ni ejecución
  global posterior al arreglo del pin; su condición preexistente no está probada.
- Artefactos externos: `/home/adrian/wowforever-artifacts/arm64-prototype/client130-guest-recovery-20261022/`.
  Receipt, `gradle-build.log`, XML de tests y `apk-audit.json`; APK SHA256
  `36267225b90853d9fe95b266e3ce594a154365fe5b52a167c3f441cc592e823d`.
  No instalada ni probada en móvil en este corte de validación local; el despliegue
  posterior se documenta en el paso5, que sigue pendiente del ensayo funcional.
