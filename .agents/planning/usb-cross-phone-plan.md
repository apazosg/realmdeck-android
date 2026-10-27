---
id: usb-cross-phone-2026-10-27
state: in-progress
date: 2026-10-27
---

## Summary

La primera fase autorizada fue pausa, inspección sin cambios y devolución:
«es mi móvil. activo el adb wifi también y probamos. avisame y quito el USB de uno
y lo pongo en el nuevo». Después confirma «Ya está enchufado en el mío» y facilita
un endpoint privado. Después aclara: «pero tenemos que resolverlo en este móvil no?
el objetivo es poder moverlo de un móvil a otro». El USB permanece en el segundo
móvil: devolverlo al original no constituye una solución de portabilidad.
La inspección y C160 ya están verificadas; queda resolver el destino y concretar
si la portabilidad incluye continuar una descarga parcial. No se ha autorizado
una adopción/migración del parcial ni se ha reiniciado el goal de rendimiento.

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
- Lectura dirigida 20:24:37 UTC: el identificador `volume` guardado por la app
  **no coincide** con el USB montado, tampoco normalizando mayúsculas. El marcador
  de la raíz sí coincide con la identidad del volumen montado. No es el caso del
  antiguo identificador basado en hash de ruta. Solo se leyó el fichero privado
  específico de selección de almacenamiento; no se modificaron preferencias.
  Evidencia externa: `personal-storage-binding/summary.json` e `identity-shape.json`.

### Continuación propuesta: portabilidad en el segundo móvil

1. Corregir primero el reconocimiento/selección del USB real mediante el flujo
   normal de la app: enumeración Android, identidad, ruta, marcador y ausencia de
   I/O/guest comprobados. No editar preferencias a mano, elegir por nombre ambiguo,
   crear/preparar carpetas ni borrar el staging. Si el destino no aparece o no pasa
   sus validaciones, diagnosticar ese fallo en lugar de forzar la selección.
   Una selección confirmada puede ejecutar su preflight normal; debe conservar los
   datos existentes conforme a US-007-AC-04/FR-004. Esta corrección aún no se ejecutó.
2. Mantener **sin permiso de acceso a todos los archivos**: US-007-NFR-002,
   US-009-NFR-002 y US-008-AC-05 lo requieren. El estado `default` observado no
   demuestra por sí solo un defecto ni justifica conceder ese permiso. Sin root,
   excepciones de seguridad ni presentar una URI SAF como ruta ejecutable de Wine.
3. Para un cliente terminado, comprobar reconocimiento/revisión y reutilización
   con las verificaciones de US-009, sin descargar o duplicar lo ya válido. El
   runtime del segundo teléfono conserva sus propias precondiciones; reconocer
   archivos no demuestra que el juego arranque en ese hardware.
4. **Decisión pendiente:** ¿también continuar en el segundo móvil una descarga
   parcial? El USB actual no contiene una instalación publicada y el checkpoint/
   recibo siguen en el original. Ese traspaso no está descrito por el flujo actual
   de US-009. Si se incluye, aprobar primero sus UC/AC y derivar requisitos/pruebas
   antes de implementar un protocolo de reutilización/adopción verificada.
   No copiar estado privado, falsear recibos/checkpoints o publicar un parcial
   como solución provisional; no empezar otra descarga completa automáticamente.
5. Antes de cualquier cambio de cliente: plan/coste/consentimiento explícitos,
   integridad, límites, quiescencia y recuperación preservados. Si hace falta otra
   APK, identificar y auditar esa candidata antes de acordar su instalación.
   El comportamiento vigente y la C160 no se modifican en esta fase de definición.

La propuesta no se da por aprobada por escribirla. Las comprobaciones de identidad
ya realizadas son de solo lectura; selección y tratamiento del parcial siguen
pendientes de la decisión concreta de alcance.

## Implementation / Action Items

- [x] 1. Cerrar el controlador original y verificar pausa/checkpoint/ausencia de trabajo.
- [x] 2. Usuario comunica traslado al segundo teléfono; no se presupone reutilización.
- [x] 3. Conectar ADB al endpoint autorizado; comprobar versión Android y app instalada.
- [x] 3a. Actualización C160 expresamente aprobada: reemplazo preservador y verificación,
  sin operaciones sobre el cliente ni lanzamiento automático del juego.
- [x] 4. Inspeccionar reconocimiento y veredicto sin operaciones del cliente,
  registrando límites: almacenamiento no disponible, ruta «No conectado»;
  conservación de metadatos comprobada, reutilización/reanudación no demostrada.
- [ ] 5. Acordar la portabilidad requerida y su impacto en UC/AC, manteniendo el USB
  en el segundo móvil. La devolución prevista inicialmente queda retirada como
  próximo paso; no resolvería el objetivo aclarado por el usuario.
- [ ] 5a. Resolver la selección/acceso del volumen real sin permiso amplio ni pérdida
  de datos; verificar reconocimiento en el segundo teléfono.
- [ ] 5b. Validar reutilización del cliente terminado y, si se aprueba, continuidad
  del parcial con un protocolo especificado y verificado; no asumir que ya existe.
- [ ] 6. Documentar conservación, reutilización observada o bloqueo seguro y pendientes.

## Open Issues

- Actualización C160 completada y verificada en versión/UID/metadatos. La app se
  abrió después por petición expresa, sin descargar/reparar/adoptar el parcial ni
  migrar el checkpoint. La inspección de archivos del cliente sigue siendo de
  solo lectura.
- El usuario pide resolver la portabilidad aquí, no cerrar la prueba devolviendo
  el USB. Pendiente confirmar si incluye continuar descargas parciales además de
  reutilizar instalaciones terminadas, y aprobar el alcance antes de mutaciones.
- La identidad seleccionada no corresponde al volumen actual. Falta comprobar que
  la app lo enumere como destino válido y confirmar la selección por su flujo normal.
- No conceder permiso amplio como atajo: contradice los requisitos vigentes. Si un
  cambio de contrato resultase imprescindible, requiere otra decisión explícita.
- US-007 cubre selección sin migración y US-009 revisión/reutilización de una fuente
  CASC legible con metadatos de instalación. US-008 protege el staging y checkpoint;
  no se ampliarán silenciosamente para confiar en estado privado de otro teléfono.
- Primer intento de conexión 19:24 UTC no conectó; no se ejecutaron consultas
  sobre la app, acciones UI, instalaciones o escrituras de cliente en el personal.
- El traslado fue confirmado; no hay confirmación expresa de expulsión del sistema.
- No se acredita migración del parcial, instalación completa ni arranque del juego.
