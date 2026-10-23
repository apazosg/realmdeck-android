---
id: client-single-pass-review-2026-10-20
state: in-progress
date: 2026-10-20
---

## Summary

**Aprobado por el usuario (2026-10-20):** «si, hazlo. el objetivo ahora es
probar el cliente descargado e intentar un login». Se prioriza diagnosticar la
admisión/arranque de70450 y realizar una prueba controlada de login; la revisión
de una pasada queda pendiente y no bloquea esta prueba ni autoriza retirar
verificaciones. Se permiten actualización conservando datos y lanzamiento/login,
no reset/repair, descarga nueva, cambios de runtime/drivers ni bypass de auth.

Petición: parar C117, actualizar la app con las mejoras y asegurar una única
pasada de verificación. Cambio material respecto al plan aprobado de dos pasadas:
no eliminar garantías de contenido/persistencia para cumplir una cifra.

Antes de actuar se observó que C117 ya terminó por sí sola, a las 19:22:28 del
móvil, con log `foreground execution finished id=1 cancelled=false`. A las19:25
no hay servicio foreground ni journal pendiente. El proceso dejó de leer CASC.
No se canceló, instaló ni borró nada.

La pantalla actual es contradictoria: «Cliente actualizado. Prepara WoW para
iniciar», pero también «Cliente incompleto» y «Completa la instalación».
Por tanto no se declara cliente listo ni se pulsa completar/reparar a ciegas.
Captura: artefactos externos `work/c117-completion-readonly-20261020/current-screen.png`.

## Content

- Las mejoras compiladas y auditadas reducen la finalización fresca de ocho a
  **dos** lecturas completas por segmento (staged y destino publicado), no una.
- Los ~808 GB físicos leídos son acumulados de todo el proceso, no una medida
  exclusiva de la finalización ni prueba de bucle infinito. La ejecución acabó.
- Una sola lectura total requiere definir cómo se acredita el contenido escrito
  y publicado, preservando hashes oficiales, barreras, identidad USB y recovery.
  No se decide todavía retirar readback ni usar fechas/tamaños como prueba.
- No volver a descargar, resetear, reparar ni cambiar volumen como parte del
  diagnóstico. Tras resolver admisión, el usuario autoriza arrancar el cliente e
  intentar login usando el flujo/configuración ya existentes; no parches nuevos.

## Implementation / Action Items

- [x] 1. Diagnosticar solo lectura la discrepancia de Inicio: admisión gestionada,
  build publicado y causa concreta del estado incompleto; usar primero metadatos
  y código, sin provocar otro escaneo completo del cliente.
- [ ] 2. Revisar el presupuesto de lecturas del camino final de dos pasadas y
  decidir con evidencia si es viable UNA sola lectura completa adicional sin
  degradar garantías. Separar descarga/replay, finalización y recuperación.
- [ ] 3. Presentar diseño mínimo, riesgos y coste real. Si una sola pasada exige
  cambiar el contrato aprobado de integridad o recovery, pedir aprobación antes
  de implementar. No prometer una pasada para todos los casos.
- [ ] 4. Implementar solo la solución acordada; pruebas focales de presupuesto de
  bytes y corrupción, revisión independiente y auditoría de APK. Reusar los tests
  existentes; sin framework ni refactor del motor.
- [x] 5. Con el diagnóstico resuelto y la candidata validada, actualizar la app
  conservando datos y comprobar reconocimiento del cliente sin confirmar una
  descarga/reparación nueva. Detenerse si vuelve a proponer decenas de GB.
- [ ] 6. Prioridad actual: arrancar el cliente70450 descargado, observar render y
  realizar un intento de login Forever. Distinguir formulario, conexión,
  autenticación, reinos/personajes y mundo. No inventar credenciales ni extraer
  contraseñas del almacenamiento; si no existe un flujo autorizado usable, pedir
   al usuario introducirlas. Retener logs sanitizados y capturas sin credenciales.
- [x] 7. Corrección autorizada: preparar automáticamente ImageFS/runtime y el
  contenedor faltantes mediante las rutas existentes al pulsar Jugar. Mantener
  validación previa del cliente, exclusión de E/S, destino USB y runtime fijado;
  reutilizar componentes locales sin descargas ocultas ni reset del entorno
  existente. Pruebas focales, revisión, APK auditada e instalación conservando datos.
  Requisito explícito del usuario: «debería ser transparente para el usuario»;
  integrarlo en Jugar con progreso normal, sin pasos manuales ni nuevos ajustes.
  ImageFS requerido:183506500 bytes (~175MiB), HEAD HTTP200 del origen existente
  `https://downloads.gamenative.app/imagefs_bionic.txz`. Coste comunicado al usuario.
  Reusar el bootstrap y descargador existentes con progreso al primer Jugar;
  no añadir otro descargador ni bloquear exigiendo instalación manual. Primer
  corte: instalar sin descarga. Tras confirmar C119 instalada, el usuario dice
  «adelante»: autoriza ahora pulsar Jugar y probar preparación/arranque.
  El usuario confirma explícitamente descargar desde la app para no inflar la APK
  («perfecto», tras explicar primer Jugar, progreso y reutilización posterior).
  Revisión independiente del registro automático cerrada sin bloqueantes tras
  exigir revalidación antes de mutar y rechazar dueño/pin ajeno, corrupto o enlace.
  El scanner existente puede generar un icono `.extracted.ico`: residuo aceptado;
  `inspectInstalled` admite extras cuando existe prueba del build gestionado
  (líneas826–845), y `preflightClientTargets` solo verifica destinos del catálogo.
  No ampliar esta tarea con lógica de iconos ni limpieza de archivos ajenos.
  C119 instalada (`0.2.95-first-run-runtime`);110 tests JVM focales y19 host pasan,
  auditoría de APK correcta, SHA256
  `5ab4abe84abdf0d4f774815a40d2b52105b50a0e28de2bea2d46cae565c8d6a9`.
  Seis hashes privados coinciden antes/después de instalar; actividad abierta,
  sin FGS. Implementación entregada; falta validar físicamente primer Jugar,
  inicialización/descarga y arranque/login (paso6). No se inició la descarga.
  Posterior «adelante»: primer Jugar C119 falla antes de ImageFS en la admisión
  del ejecutable (`client_executable_invalid`). MD5, tamaño, PE y `.build.info`
  intactos; posible identidad FUSE distinta entre ejecuciones, todavía sin
  confirmar desde el proceso Java. No modificar la guarda por esta hipótesis.

## Open Issues

### Bloqueo confirmado20oct20:05: entorno de ejecución sin inicializar

`files/imagefs/home/` y `files/imagefs/.img_version` no existen; tampoco el
contenedor `xuser-CUSTOM_GAME_1368588668`. Solo existe el marcador de migración
en `.winlator`. `prepareContainer()` llama al `ContainerUtils.getContainer()`
estricto, que lanza `Exception("Container does not exist for game …")`; coincide
con el intento. El fallo no indica archivos del juego pendientes de descarga.

**Aprobado por el usuario:** «haz la corrección e instalamos la app»; corregir en
la app el flujo automático, no provisionar manualmente por fuera. Inicializar
ImageFS y el contenedor por
la vía existente, usando el runtime ARM64EC/Wine/DXVK ya fijado. Primero determinar
artefactos locales, costes y punto de inicialización; no sustituir la guarda por
autocreación indiscriminada ni disparar descargas ocultas. Conservar instalación
USB, admisión y configuración; sin cambiar drivers, credenciales ni método de
compatibilidad. Si faltan descargas adicionales, presentar su coste antes de
confirmarlas. No se autoriza cambiar de runtime ni de drivers; solo inicializar
automáticamente los componentes existentes que faltan para el arranque.

### Evidencia inicial de la prueba70450 (20oct19:31)

Sin FGS ni journal pendiente; admisión privada de70450 ARM64/enUS y `.build.info`
coincidentes. Ejecutable MD5/CKey `2a93fe1927d24816603f10d7a351d017` verificado
desde el USB. Los36 archivos sueltos de la admisión coinciden con sus CKeys
(0,76s, sin releer archivos CASC). Evidencia externa:
`/home/adrian/wowforever-artifacts/work/client70450-first-launch-20261020/`
(`baseline-metadata.txt`, `managed-install-admission.json`, `loose-file-check.json`).
Esto no prueba aún arranque ni compatibilidad de login. Causa confirmada en código:
`PinnedExecutableVerifier` solo admite tamaño/SHA70009 y `resolveForPrepare()` exige
`WTF/BetaSuspendedTest.wtf`, que el instalador no distribuye. La corrección usa la
admisión privada gestionada y los parsers del updater, verifica ejecutable y
`.build.info`, y provisiona configuración mediante el escritor del contrato ya
existente cuando falta. Revisión independiente sin bloqueantes; compilación y
tests JVM completados:82/82 correctos (Storage57, Ux10, Service15), APK auditada
`851bd8778602b1454b39a551570134629f00788b9ecb7c4a917ddc4c6d73ecd0`.
C118 instalada conservando datos; los hashes de preferencias/admisión coinciden
antes y después de `install -r`. Control recuperado con scrcpy5.0.1/UHID existente.
Gestor reconoce70450 actualizado y permite Jugar, sin nueva descarga. Primer
intento falla en preparación (`error=Exception`), antes de Wine y sin log PoC;
WTF contractual creada. Diagnóstico solo lectura en curso, sin segundo intento.
Los19 tests host focales pasan (una aserción de autologin anterior, ajena al cambio,
excluida). Riesgo menor revisado: la revalidación congelada comprueba fingerprint
del mismo build, pero no repite el SHA de `.build.info`; la resolución inicial sí
exige los bytes exactos y el ejecutable queda vinculado por identidad.

- La causa legacy70009/WTF ausente está confirmada arriba; la resolución funcional
  todavía requiere la prueba física de arranque.
- ¿Puede eliminarse una de las dos pasadas manteniendo prueba de bytes publicados?
  Pendiente de diseño/revisión; no resuelto por el éxito de compilación actual.
- Plan aprobado; la petición no autoriza sustituir hashes por metadatos ni
  fabricar pruebas de verificación. Una sola pasada no está implementada.
