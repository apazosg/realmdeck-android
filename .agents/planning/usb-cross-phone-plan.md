---
id: usb-cross-phone-2026-10-27
state: in-progress
date: 2026-10-27
---

## Summary

El usuario autoriza la propuesta de pausa, inspección sin cambios y devolución:
«es mi móvil. activo el adb wifi también y probamos. avisame y quito el USB de uno
y lo pongo en el nuevo». Después confirma «Ya está enchufado en el mío» y facilita
un endpoint privado. El alcance es reconocimiento/conservación, no migrar o activar
una descarga incompleta. No cambia el objetivo de optimización de descarga.

## Content

- Original: C160 cerró 19:14:11 UTC con **822.658 objetos durables**, prefix/CRC y
  retención raw válidos, callbacks/HTTP/fases inactivos. Lectura fresca 19:16:32:
  pausa, cero servicios updater y cero hilos fetch/prefix. Evidencia externa en
  `usb-cross-phone-20261027/source-preflight/summary.json`.
- Datos CASC parciales en staging USB, checkpoint y recibo en almacenamiento
  privado del original. `readStageReceipt`/`resumeGuardState` rechazan staging sin
  recibo propio: un rechazo del segundo móvil no demuestra pérdida del contenido.
- No copiar recibos/checkpoints, falsear `.build.info`, publicar staging, saltarse
  guardas ni anunciar cliente completo. Aplican [US-007](../specs/US-007.md),
  [US-008](../specs/US-008.md) y [US-009](../specs/US-009.md); sin cambiar requisitos.
- El usuario realiza el traslado físico y la expulsión de Android. La ausencia
  de escritores de la app no garantiza ausencia de escrituras del sistema.
- En el teléfono personal, conectar solo al endpoint facilitado. No heredar la
  autorización amplia del dispositivo dedicado: no inspeccionar otras apps,
  cuentas, mensajes, fotos o credenciales, ni cambiar permisos/seguridad/ajustes.
- Revisión de la app y del volumen sin Descargar/Reanudar/Reparar/Preparar carpeta,
  borrado, formato, copia, adopción o instalación/reemplazo de APK no acordado.
  Si no existe una ruta segura de lectura, detenerse y comunicarlo.
- Identificadores y evidencias fuera de Git, conforme a la
  [referencia de control](../references/device-control-scrcpy.md). Mantener ambos
  endpoints separados; no reutilizar el selector automático del original.
- Baseline privado válido. Los listados USB por `exec-out` contienen errores
  `ls` pese a returncode0: evidencia rechazada en `source-usb-inventory-v2/verification.json`,
  no prueba de datos desaparecidos ni inventario válido. No existe hash de todo
  el volumen; no prometer igualdad binaria o integridad completa de sus bytes.
- El nuevo endpoint facilitado por el usuario conecta: Android SDK36. La lectura
  inicial encontró C146 `0.2.122-performance-preview`; el reemplazo autorizado
  posterior por C160 se registra abajo.
  El volumen esperado está montado. El contexto `run-as` recibe Permission denied
  incluso en la raíz del volumen; shell UID2000 puede leer las rutas exactas.
  Esto no demuestra qué acceso tiene la app ejecutada normalmente.
- Inspección de metadatos 19:39:44 UTC: staging esperado, marcador `.owner` cuyo
  digest coincide con el recibo privado original y 81 archivos `data.000`–`data.080`,
  51.625.635.891 bytes (48,08 GiB). Sin `.build.info` publicada en la raíz.
  Cero servicios updater antes/después, cero acciones UI/escrituras/permisos.
  No se han leído ni autenticado todos los cuerpos CASC; no es una prueba de
  integridad completa o de reanudación en el segundo móvil.
- Resultado y límites en [el informe USB](../../docs/usb-cross-phone-results.md).
- Ampliación explícitamente aprobada: el usuario seleccionó «Actualizar C160».
  Se permite reemplazar únicamente la app personal por la APK C160 exacta ya
  auditada, mediante `adb install -r --no-streaming`, sin desinstalar/borrar datos.
  Antes: comprobar hash/tamaño/auditoría y ausencia de updater/guest de esa app.
  Después: comprobar versión, UID conservado y metadatos del staging sin iniciar
  descarga. No se fuerza una firma incompatible ni se reintenta una instalación
  de resultado incierto; no se cambia seguridad ni se migra el checkpoint.
- Reemplazo ejecutado una vez, final 19:52:23 UTC: Android confirmó `Success`,
  versión C160 `0.2.136-bounded-blte-buffers-test` y mismo UID. Cero servicios updater
  antes/después; `.owner` y diccionario completo de nombres/tamaños de los 81 CASC
  sin cambios. No lanzamiento de app, descarga ni juego. No se cotejaron todos los
  datos privados ni los cuerpos CASC. Evidencia externa:
  `personal-c160-install/summary.json` y `adb-install.txt`.
- El usuario pidió abrir y observar la app: «hazlo tú mejor así ves qué ocurre».
  Lanzamiento único de su actividad, 20:06:37 UTC; sin iniciar juego ni operaciones
  del cliente. Veredicto observado: **«Almacenamiento seleccionado no disponible»**.
  El único botón pulsado fue `Detalles`, que solo despliega la ruta: **«No conectado»**.
  Consulta de la operación `MANAGE_EXTERNAL_STORAGE` de esta app: `default` y
  rechazo reciente; no se ha concedido ni cambiado permiso alguno. Esto no prueba
  una causa única ni que conceder ese permiso permita reanudar el parcial.
- Metadatos del USB antes/después de abrir la app iguales: marcador y nombres/tamaños
  de los 81 CASC, 51.625.635.891 bytes. Cero servicios updater; se verificó que el
  proceso adicional de su UID es `/system/bin/logcat`, no un guest. El primer
  controlador se detuvo por rechazar conservadoramente ese logger; se conservó el
  fallo y se completaron solo lecturas faltantes, sin repetir el lanzamiento.
- No se guardaron XML de interfaz sin filtrar ni etiquetas de otras apps. El
  controlador no envió comandos de ajustes; el código C160 preexistente solicita
  volumen multimedia 1 al abrir. No se midió ni restauró el nivel anterior.

## Implementation / Action Items

- [x] 1. Cerrar el controlador original y verificar pausa/checkpoint/ausencia de trabajo.
- [x] 2. Usuario comunica traslado al segundo teléfono; no se presupone reutilización.
- [x] 3. Conectar ADB al endpoint autorizado; comprobar versión Android y app instalada.
- [x] 3a. Actualización C160 expresamente aprobada: reemplazo preservador y verificación,
  sin operaciones sobre el cliente ni lanzamiento automático del juego.
- [x] 4. Inspeccionar reconocimiento y veredicto sin operaciones del cliente,
  registrando límites: almacenamiento no disponible, ruta «No conectado»;
  conservación de metadatos comprobada, reutilización/reanudación no demostrada.
- [ ] 5. Usuario expulsa/devuelve el USB; verificar checkpoint privado original sin
  cambios y disponibilidad del destino. Una Resume posterior se acuerda aparte.
- [ ] 6. Documentar conservación, reutilización observada o bloqueo seguro y pendientes.

## Open Issues

- Actualización C160 completada y verificada en versión/UID/metadatos. La app se
  abrió después por petición expresa, sin descargar/reparar/adoptar el parcial ni
  migrar el checkpoint. La inspección de archivos del cliente sigue siendo de
  solo lectura.
- El veredicto de la app está observado y no admite el destino seleccionado en
  este estado. No cambiar permisos amplios ni la selección de almacenamiento sin
  decisión expresa; no prometer que eso migre el checkpoint del original.
- Pendiente expulsión/devolución al original y comprobación de su checkpoint.
- Primer intento de conexión 19:24 UTC no conectó; no se ejecutaron consultas
  sobre la app, acciones UI, instalaciones o escrituras de cliente en el personal.
- El traslado fue confirmado; no hay confirmación expresa de expulsión del sistema.
- No se acredita migración del parcial, instalación completa ni arranque del juego.
