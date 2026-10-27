# USB entre dos móviles: datos parciales visibles, reanudación no demostrada

Evidencia del 27-10-2026: inspección USB cerrada 19:39:44 UTC, actualización
expresamente aprobada de la app cerrada 19:52:23 y observación de su interfaz
cerrada 20:13:03. Alcance en
[el plan](../.agents/planning/usb-cross-phone-plan.md): lectura del cliente y
reemplazo de la app, sin descargar, reparar, preparar carpeta, migrar metadatos
privados ni conceder permisos. No se enviaron comandos de ajustes; los efectos
propios de abrir la app se acotan más abajo.

## Resultado observado

- ADB conectado mediante el nuevo endpoint facilitado por el usuario; identificador
  guardado únicamente en `private/`, no en este informe.
- Segundo móvil: Android SDK36; inicialmente **C146 / 0.2.122-performance-preview**.
  Después se reemplazó por C160 con autorización separada, como se detalla abajo.
- El volumen USB esperado está montado. La ruta exacta del cliente, el staging y
  su carpeta `Data/data` son legibles por shell UID2000.
- **81 archivos CASC `data.000`–`data.080`, 51.625.635.891 bytes (48,08 GiB)**.
  Son tamaños de archivo, no bytes de descarga ni número de objetos autenticados.
- El SHA-256 del marcador `.owner` coincide con el del recibo privado original.
  La raíz no contiene `.build.info`: se observa staging parcial, no una instalación
  completa publicada.
- Cero servicios updater antes/después de las lecturas; ninguna acción UI,
  descarga, escritura de cliente o cambio de permisos/ajustes solicitado.

El contexto de diagnóstico `run-as` recibió Permission denied desde la raíz del
volumen. Una comprobación limitada de atributos confirmó que shell sí ve las mismas
rutas; se usó esa lectura ordinaria, sin root ni cambios de seguridad. El resultado
**no determina el acceso de la app ejecutada normalmente**, cuyo contexto difiere.

## Qué no demuestra

La interfaz del segundo móvil declara el almacenamiento seleccionado no disponible;
no se ha intentado reanudar. El checkpoint y el recibo pertenecen al almacenamiento privado
del original: ver los archivos USB no migra ese estado. No se han copiado esos
metadatos ni el staging, ni falseado una instalación terminada.

No existe un inventario USB anterior válido para comparar todos los tamaños, ni se
han autenticado de nuevo todos los cuerpos. Coincidir el marcador confirma la
identidad del staging observada, **no la integridad de todos sus bytes**. El último
checkpoint original validado conserva 822.658 objetos, antes del traslado; no se
ha verificado un retorno. El usuario aclara después que el objetivo es resolver
la portabilidad en el segundo móvil, no volver al original como solución.

## Evidencia y diagnóstico de los instrumentos

Workspace externo `usb-cross-phone-20261027/`:

- `personal-preflight-v3/summary.json`: conexión y metadatos limitados de la app.
  El primer parser local de esta nueva conexión falló antes de invocar ADB;
  diagnóstico conservado en `personal-preflight-v2/bootstrap-diagnosis.json`.
- `personal-usb-readonly/summary.json` y `personal-path-diagnosis/`: volumen y
  denegación del contexto `run-as`, contrastados con atributos vía shell.
- `personal-usb-shell-inventory/verified-inventory.json`: listados válidos, tamaños
  y marcador. El parser inicial esperaba nueve campos, pero el formato Android
  usa ocho; se corrigió la interpretación de los listados ya capturados, **sin
  repetirlos**. El informe inicial fallido queda preservado, no se trata como PASS.

Los listados originales anteriores al traslado estaban explícitamente rechazados
por errores `ls`; no se usan como baseline. Endpoints, rutas privadas, IDs del
volumen, listados y capturas permanecen fuera de Git.

## Actualización de la app aprobada y completada

El usuario seleccionó «Actualizar C160»: solo reemplazar la app C146 por la C160
auditada, preservando datos mediante instalación `-r`, sin desinstalar ni forzar
una firma incompatible. Se ejecutó una vez `install -r --no-streaming`; Android
confirmó `Success`. Inicio 19:50:37 UTC, final **19:52:23 UTC**:

- Versión posterior **C160 / 0.2.136-bounded-blte-buffers-test**, mismo UID.
- APK exacta previamente auditada, 484.953.835 bytes, SHA-256
  `9dcc86b4917842285888d4db177fcaab58111f2f9a9ba9c5d9321d735c57d466`;
  su hash se comprobó antes de instalar, sin repetir compilación/auditoría.
- Cero servicios updater antes/después y sin procesos guest de esa app.
- `.owner` y diccionario completo de nombres/tamaños de los **81 CASC y
  51.625.635.891 bytes** iguales antes/después de la actualización.
- Sin desinstalación, borrado, cambio de permisos/ajustes, lanzamiento de app,
  descarga, reparación, adopción del parcial, migración de checkpoint o juego.

Evidencia externa: `personal-c160-install/summary.json`, `adb-install.txt`,
metadatos previos/posteriores y `personal-c160-install-calibration.json`.
El UID y los metadatos comparados no equivalen a cotejar todos los datos privados
ni autenticar todos los cuerpos CASC. La actualización no convierte el staging
en cliente completo ni demuestra reanudación. El veredicto posterior de la app
se detalla abajo; el estado privado original no se ha migrado ni modificado.

## Apertura de C160 y veredicto real de la interfaz

El usuario pidió «hazlo tú mejor así ves qué ocurre». Se abrió una vez la actividad
de la app a las 20:06:37 UTC, sin abrir el juego ni pulsar operaciones del cliente.
La lectura posterior muestra el nombre del USB y **«Almacenamiento seleccionado
no disponible»**. Se pulsó únicamente **Detalles** —despliega texto, no cambia
destino— y la ruta indicada es **«No conectado»**.

Una consulta de solo lectura a la operación `MANAGE_EXTERNAL_STORAGE` de esta app
devolvió `default`, con un rechazo reciente. No se concedió permiso ni se cambió
la selección. La ruta no resuelta y ese registro son observaciones, **no una prueba
de causa única ni garantía de que un permiso amplio resuelva la reutilización**.
La app todavía no reconoce este destino como utilizable; no se ha probado reanudar.

El marcador y el diccionario completo de nombres/tamaños de los 81 CASC coinciden
con la lectura inmediatamente anterior a abrir la app. Cero servicios updater al
cierre; sin Descargar/Reanudar/Reparar/Preparar, adopción o migración. No es una
verificación de todos los cuerpos CASC ni del checkpoint privado del original.

El primer controlador se detuvo después de abrir por una guarda demasiado estricta
contra procesos auxiliares. Una lectura limitada a su UID identificó `logcat`, y
su ejecutable `/system/bin/logcat` se verificó; no era un guest. El informe fallido
queda preservado. Se completaron solo las lecturas faltantes, sin relanzar la app.
La primera lista conservadora de etiquetas omitió el estado; el seguimiento recogió
los textos de estado definidos en la propia app. No se almacenaron XML sin filtrar
ni etiquetas de otras apps o campos de credenciales.

No se enviaron comandos de ajustes ni permisos. Abrir C160 ejecuta su comportamiento
preexistente de solicitar volumen multimedia 1; no se midió ni restauró el valor
anterior. No debe describirse esta apertura como ausencia absoluta de efectos sobre
ajustes del dispositivo.

Evidencia externa: `personal-c160-ui/summary.json`,
`personal-c160-startup-process-diagnosis/summary.json`,
`personal-c160-ui-followup/summary.json` y `personal-c160-storage-details/summary.json`.
La devolución propuesta en ese punto no se realizó. La aclaración posterior del
usuario sustituye ese próximo paso: el USB permanece en el segundo móvil para
resolver su portabilidad, sin forzar reconocimiento ni cambiar permisos.

## Alcance corregido y desajuste concreto de selección

El usuario aclara: «pero tenemos que resolverlo en este móvil no? el objetivo es
poder moverlo de un móvil a otro». Conservar los archivos es una condición necesaria,
no la aceptación de ese objetivo; no se cierra la tarea por devolver el USB.

Lectura dirigida 20:24:37 UTC, sin acciones UI, escritura de preferencias o cliente:
el identificador de volumen guardado por la app **difiere del volumen USB actual**,
también al comparar sin distinguir mayúsculas. El marcador de la raíz sí coincide
con el volumen montado. Ambos identificadores son de tipo `volume`; no es el antiguo
caso de identidad por hash de ruta. Cero servicios updater antes/después.
Esto respalda investigar primero la selección persistida y la enumeración actual,
no conceder permisos amplios ni atribuir el rechazo a pérdida de los CASC.
Evidencia externa: `personal-storage-binding/summary.json`, `identity-shape.json`.
Los valores concretos y el XML específico de almacenamiento quedan fuera de Git.

Corrección de interpretación: el permiso de acceso a todos los archivos **no es
un requisito que falte por conceder** en el diseño aprobado. US-007-NFR-002,
US-009-NFR-002 y US-008-AC-05 exigen operar sin él. Su estado `default` no demuestra
la causa del fallo observado; no se solicita ni se concede como solución rápida.

El plan distingue dos cuestiones: resolver aquí el destino y reutilizar un cliente
terminado; y, si el usuario lo incluye, continuar aquí una descarga a medias. El
segundo caso necesita comportamiento y recuperación especificados, porque los
822.658 objetos pertenecen a un staging cuyo checkpoint/recibo están en el original.
No se ha implementado migración, copiado estado privado, reanudado ni modificado
el cliente. La decisión sobre el parcial está pendiente antes de ampliar UC/AC.
