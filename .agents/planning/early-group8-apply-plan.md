---
id: early-group8-apply-2026-10-21
state: in-progress
date: 2026-10-21
---

## Summary

El usuario pide «adelanta el parche», después de confirmar Login y entrada al
juego sin glitches gráficos en C127. Se propone iniciar búsqueda/aplicación al
arrancar la sesión de lanzamiento, sin esperar el primer Enter, exclusivamente
cuando está autorizado el parche Classic Forever. No interrumpir la partida actual.
El usuario aprueba este alcance y sus límites con «si adelante».

## Content

### Evidencia y decisión propuesta

- `ForeverFunctionalPoc.patchLaunchHook` establece el nonce y llama a `start`
  antes de Wine. `nativeBegin` debe seguir siendo síncrono y anterior a Wine:
  fija el baseline del que se demuestra la pertenencia del futuro hijo.
- Actualmente `GatedPollSession.runWorker` no llama a `nativePoll` hasta Enter.
  C127 ya demostró APPLIED32 bytes, mantenimiento y Login correcto; ese bloqueo
  de inicio provoca coordinación manual y puede dejar fallar el primer Login.
- Solo las sesiones con callback de aplicación explícitamente habilitado
  empezarán a buscar inmediatamente. El observador sin permiso de escritura y
  la captura mantienen sus comportamientos actuales.
- Antes de existir el hijo correcto, el lector puede devolver WAITING: nunca
  se escribe por encontrar cualquier proceso Wine ni por usar una dirección vieja.
- Conservar el máximo total900000ms desde Begin. El primer Enter deja de iniciar
  la búsqueda y solo acota el tiempo restante a180000ms desde ese Enter, siempre
  dentro del máximo total; otros Enters no extienden nada. Watchdog, cancelación,
  nativeEnd exacto una vez y mantenimiento del candidato permanecen activos.
- Cambia el consumo de lectura: antes de Enter podrá haber búsqueda durante más
  de180s, hasta el máximo total ya existente. No se amplía el tiempo máximo del
  subreaper, ni los presupuestos nativos por poll. Tras APPLIED se mantiene la
  revalidación del mismo candidato en vez de repetir barridos completos.
- No bloquear ni automatizar el Login. La prueba física debe comprobar si se
  obtiene APPLIED antes de Enter; adelantar el escaneo no demuestra por sí solo
  que el objeto de claves exista antes de autenticar.

### Alcance mínimo

- Kotlin: modificar solo el inicio y cálculo dinámico de la fecha límite del
  worker escritor. Recalcular antes/después de cada poll y antes de aplicar para
  respetar un Enter que llegue durante un poll. Sin nuevos temporizadores,
  opciones, detectores visuales, JNI ni cambios Rust.
- Conservar todas las validaciones del escritor C127: mismo token/hijo/bloque/
  mapeo,32 bytes grupo8, readback, latch de error, no TLS bypass/mprotect/DLL.
- Nueva APK C128 con la misma biblioteca nativa y procedencia C127. Receta,
  versión, auditoría y documentación deben describir honestamente el cambio.
- No cambiar runtime, drivers, cliente, cuentas, almacenamiento ni permisos.

### Criterios de aceptación

1. Una sesión escritora realiza polling y puede aplicar antes de cualquier Enter.
2. Un observador sin permiso y la captura no adquieren escritura ni cambian su
   temporización. No escribir mientras falta candidato/identidad completa.
3. Enter durante un poll acorta el límite sin rearmar; cancelación, cierre y
   vencimiento no permiten aplicaciones posteriores. El máximo total no aumenta.
4. APPLIED/ALREADY mantiene el candidato; DENIED vuelve al escaneo y ERROR
   deshabilita aplicaciones, igual que en C127.
5. Pruebas focales, revisión de concurrencia y compilación/auditoría correctas.
6. Con la partida actual ya cerrada por el usuario: instalar conservando datos,
   comprobar quiescencia y recuperar la reserva solo con la API existente; sin
   reiniciar Android. Una prueba manual registra aplicación antes de Enter y
   resultado del primer Login, o documenta el límite observado si no es posible.

## Implementation / Action Items

- [x] 1. Inspeccionar los puntos actuales de inicio/espera/fin y redactar propuesta.
- [x] 2. Aprobar el cambio temporal y mayor lectura antes de Enter.
- [x] 3. Implementar y ajustar unas pocas regresiones de inicio, Enter durante poll,
  límites/cancelación y separación observador/captura.
- [x] 4. Revisión independiente; reconstrucción auténtica, tests JVM y auditoría APK.
- [x] 5. Registrar y preparar entrega C128 sin interrumpir la sesión actual.
- [ ] 6. Con confirmación de cierre del usuario, instalar y probar un Login manual.

## Open Issues

- El 2026-10-22 se aprueba una comparación temporal que recupera el gate C127,
  conservando C133 y el mismo nativo: [plan de regresión](done/patch-c127-regression-plan.md).
  C128 no tuvo un ensayo positivo; esta comparación no cambia el escritor ni
  atribuye todavía el fallo al adelanto. C134 recupera APPLIED32 y Login confirmado
  tras el segundo Enter y una espera adicional; no se instala C128 para repetirlo.

- Falta demostrar que el almacén está disponible antes de autenticar. No ampliar
  búsqueda/escrituras ni prometer primer Login corregido si la prueba no lo acredita.
- El usuario está jugando: no instalar, detener procesos, cerrar el juego ni
  reiniciar el teléfono durante esa sesión. La entrega física requiere coordinación.
- El USB sufrió desconexiones anteriormente. Esta mejora no corrige cable/hub/
  alimentación; registrar por separado cualquier nuevo corte durante la prueba.

## Verificación y entrega preparada

- Implementación final: un único bucle compartido, espera inicial solo si no hay
  callback escritor. Fecha límite dinámica bajo el mismo monitor antes/después de
  poll, antes de aplicar y al dormir; watchdog comparte esa cota. Captura y modo
  sololectura conservados. Revisión independiente sin bloqueantes.
- 32 pruebas JVM `ForeverFunctionalLauncherUxTest`, cero fallos/errores/omitidos,
  9,119s. Incluyen aplicación antes de Enter, observador sin lecturas antes de
  Enter, Enter durante poll, límite total, cancelación y mantenimiento previo.
- Host: tripwire temporal focal correcto tras simplificar el bucle; prueba
  versión/pins C128 correcta. No se repitieron tests nativos de código intacto ni
  se declara una suite completa verde.
- Reconstrucción auténtica y build Gradle correctos4m44s. Canonical externo:
  `/home/adrian/wowforever-artifacts/arm64-prototype/client128-early-group8-reviewed-20261021/`.
  Receipt, `gradle.log`, XML y `apk-audit.json` conservados.
- `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2215 fuentes base,178 payloads,
  mismas excepciones revisadas y nativo C127 sin cambios/recompilación.
- APK `0.2.104-early-group8-apply`,484443715B, SHA256
  `6a4e842f84f174d4df293a72c4ed0a4935ac9a1da29d646efc15a6357fde8d59`.
  Ubicación: `source/GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa/app/build/outputs/apk/modern/debug/app-modern-debug.apk`
  bajo ese canonical.
- **No instalada.** El usuario está jugando con C127; no se han ejecutado acciones
  contra el dispositivo durante esta tarea. Instalación y prueba física pendientes
  de su confirmación de cierre.
