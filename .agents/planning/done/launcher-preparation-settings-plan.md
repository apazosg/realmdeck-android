---
id: launcher-preparation-settings-2026-10-22
state: done
date: 2026-10-22
---

## Summary

El usuario encuentra de nuevo el mensaje de «preparación activa» al intentar
restablecer gráficos y exige que la APK cierre su preparación interna en vez de
pedirle gestionarla. El usuario no debe conocer estados internos del launcher.
Aprueba ejecutar este plan el 2026-10-22: «vale, ejecuta el plan».

## Content

### Hechos comprobados al planificar (C131)

- Árbol limpio en `main`, C131 instalada y auditada.
- `ForeverFunctionalPocActivity.kt` incluye `prepared != null` en `controlsLocked`
  y muestra «Resolución: no se puede cambiar con una preparación activa.» en todo
  el panel de Ajustes, también junto a «Restablecer gráficos».
- El bloqueo de restablecimiento no incluye ese `prepared`: el mensaje mostrado
  no demuestra que exista trabajo en curso ni que sea el error de esa acción.
- El reset invalida el consentimiento inmediatamente, pero descarta `prepared`
  solo después de escribir correctamente. Su comprobación inferior exige runtime
  inactivo y no recupera por sí sola una reserva antigua.
- La revisión confirma que `prepare()` cierra su lease de IO antes de devolver
  `Prepared`; los tickets guest se crean solo en `beforeLaunch()`. Una preparación
  lista sin tickets es un snapshot descartable, no trabajo activo. Ajustes ya
  impide entrar durante preparación real: no se añade una cancelación de jobs que
  no puede alcanzarse desde estas acciones. Se mantiene esa exclusión hasta terminar.

### Cambio propuesto

1. Distinguir una preparación lista pero aún no lanzada de una operación que
   sigue trabajando y de una partida realmente abierta.
2. Cuando una acción de Ajustes requiera descartar esa preparación, hacerlo desde
   la APK y continuar con la acción: en particular resolución y restablecimiento
   de gráficos. Eliminar el aviso técnico genérico; no basta con ocultarlo y dejar
   el control bloqueado.
3. Si aún existe trabajo de preparación propio, solicitar su cancelación y esperar
   a su finalización antes de la escritura. Impedir que un resultado tardío vuelva
   a publicar la preparación cancelada o permita lanzar con consentimiento antiguo.
   Reutilizar el ciclo de vida existente; no añadir un framework de operaciones.
4. Si la causa efectiva es una reserva guest antigua con runtime ya cerrado,
   reutilizar la recuperación existente con las mismas pruebas de destino,
   propiedad y quiescencia. No borrar reservas, locks ni datos para desbloquear.
5. No confundir una preparación con una partida viva o una actualización. Este
   cambio no autoriza matar procesos, cancelar una actualización ni escribir la
   configuración mientras el juego la usa. Mostrar progreso breve y comprensible
   cuando haya que finalizar trabajo real.

### Impacto y límites

- Aclarar en US-004 AC-07/AC-11 y FR-016/FR-017 que los ajustes afectados gestionan
  automáticamente la preparación interna, conservando consentimiento fresco y
  exclusión. Ampliar solo las pruebas de estos recorridos.
- Si la investigación confirma que basta con descartar un snapshot ya terminado,
  limitar la implementación a ello: no introducir cancelación compleja sin una
  operación real que la necesite.
- Runtime/driver, configuración ajena al reset, almacenamiento y datos del cliente
  quedan fuera del cambio. La investigación de las franjas sigue en su plan propio.

## Implementation / Action Items

- [x] 1. Confirmar el origen exacto del aviso y los recursos de la preparación.
  Inspección de fuente; el mensaje concreto y el estado del móvil siguen sin captura.
- [x] 2. Aprobar el comportamiento y su impacto en US-004 antes de implementarlo.
  Aprobado por el usuario el 2026-10-22.
- [x] 3. Aplicar la corrección mínima y añadir regresiones de ajustes tras preparar,
  cancelación realmente necesaria y ausencia de lanzamiento/escritura prematuros.
  Snapshot sin handles guest descartado al entrar en resolución o confirmar reset;
  recuperación segura existente antes del reset, sin añadir cancelación de jobs.
  Contratos nuevos reproducidos antes del cambio. Pruebas host: 20 pasan/1 fallo
  previo de texto de autologin; perfil y fixtures históricos: 9 pasan/5 fallos
  previos del permiso ancla. Las 32 pruebas de reconstrucción/auditoría pasan;
  IDs, enlaces y trazabilidad AC→FR→TC de US-004 validados.
- [x] 4. Revisar la exclusión si cambia su ciclo de vida; ejecutar pruebas enfocadas,
  reconstruir, compilar y auditar una nueva candidata si se genera APK.
  Revisión independiente sin bloqueos; eliminado un mensaje opcional con jerga
  interna. Reconstrucción canónica, 28 JVM correctas y auditoría completa PASS.
- [x] 5. Documentar resultados, comprometer cambios propios y cerrar el plan cuando
  exista evidencia suficiente. Instalación/interacción en dispositivo por acordar.
  Código y pruebas comprometidos en `dd41aab`. Cierre de implementación y validación
  local; no acredita reproducción ni resolución física en el móvil desconectado.

## Results

- C132 / `0.2.108-settings-preparation`, compilación correcta en 6min34s.
- 28 JVM sin fallos, errores u omisiones: 7 de `ForeverClientOperationLeaseTest`,
  10 de `ForeverClientRuntimeGateTest`, 11 de `ForeverFunctionalDisplayFitTest`.
- `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2215 fuentes sin cambios y 178 payloads.
  APK SHA-256 `58aa1fec5d59342aafb5af269b91383d24b1a83bc13eb7e5f843c2ff508a6a49`.
- Evidencia externa en
  `/home/adrian/wowforever-artifacts/arm64-prototype/client132-settings-preparation-20261022/`:
  `reconstruction-receipt.json`, `build.log`, XML JVM y `apk-audit.json`.
- No se instala C132 ni se abre el juego: ADB no tiene un dispositivo conectado y
  la instalación/interacción de esta candidata no forma parte de la ejecución local.

## Open Issues

- El aviso genérico está localizado; falta confirmar si hubo además una excepción
  de reset o una reserva residual. No afirmar una reproducción física sin ella.
- Revisión del ciclo de vida completada: descartar solo el snapshot no lanzado y
  conservar los bloqueos de IO real; recuperar únicamente guest antiguo coincidente
  y quiescente mediante la API existente. No se impondrá «pulsa Jugar» como remedio.
- ADB desconectado. El fallo de login comunicado después de C131 se investiga por
  separado y no tiene causa confirmada; esta corrección no debe presentarse como
  solución del login ni modificar parcheado, credenciales o políticas de conexión.
