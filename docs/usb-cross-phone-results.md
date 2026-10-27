# USB entre dos móviles: datos parciales visibles, reanudación no demostrada

Evidencia del 27-10-2026: inspección USB cerrada 19:39:44 UTC y actualización
expresamente aprobada de la app cerrada 19:52:23. Alcance en
[el plan](../.agents/planning/usb-cross-phone-plan.md): lectura del cliente y
reemplazo de la app, sin descargar, reparar, preparar carpeta, migrar metadatos
privados ni cambiar permisos o ajustes.

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

No se ha observado aún el veredicto de la interfaz del segundo móvil ni se ha
intentado reanudar. El checkpoint y el recibo pertenecen al almacenamiento privado
del original: ver los archivos USB no migra ese estado. No se han copiado esos
metadatos ni el staging, ni falseado una instalación terminada.

No existe un inventario USB anterior válido para comparar todos los tamaños, ni se
han autenticado de nuevo todos los cuerpos. Coincidir el marcador confirma la
identidad del staging observada, **no la integridad de todos sus bytes**. El último
checkpoint original validado conserva 822.658 objetos, antes del traslado; falta
devolver el USB y verificar continuidad allí. Una Resume posterior se acuerda aparte.

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
en cliente completo ni demuestra reanudación. Siguen pendientes el veredicto de
la app y la devolución del USB al original para comprobar su checkpoint.
