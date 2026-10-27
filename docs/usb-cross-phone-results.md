# USB entre dos móviles: datos parciales visibles, reanudación no demostrada

Evidencia del 27-10-2026, corte 19:39:44 UTC. Alcance aprobado en
[el plan](../.agents/planning/usb-cross-phone-plan.md): inspección de solo lectura
en el teléfono personal, sin descargar, reparar, preparar carpeta, instalar APK,
migrar metadatos privados ni cambiar permisos o ajustes.

## Resultado observado

- ADB conectado mediante el nuevo endpoint facilitado por el usuario; identificador
  guardado únicamente en `private/`, no en este informe.
- Segundo móvil: Android SDK36; app **C146 / 0.2.122-performance-preview**, frente a
  C160 del original. No se instaló ni sustituyó ninguna app.
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
