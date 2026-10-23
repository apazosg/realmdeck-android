---
id: casc-native-index-repair-2026-10-20
state: done
date: 2026-10-20
---

## Summary

El usuario pide «necesito que resuelvas el problema de WoW dañado».
El objetivo es superar el error CASC del cliente70450 y continuar hasta login,
conservando el cliente descargado. No basta que nuestros parsers acepten el índice:
la prueba de aceptación es el consumidor nativo.

El usuario aprueba la prueba («me parece bien») y aporta como referencia
`https://github.com/YoruDev-Ryland/WoWForeverywhere`. Aclara la prioridad:
«el objetivo principal es saber que paso y dejarlo resuelto para futuros apks y
descargas». Se inspeccionará su implementación antes de la prueba. La corrección
permanente y su regresión son obligatorias para cerrar esta tarea; no basta el
arreglo manual del dispositivo.

## Content

### Evidencia confirmada

- C121 instalada; un único lanzamiento real crea WowB PID11603 y muestra
  «Your World of Warcraft installation is damaged». No hubo login ni render3D.
- Tact.log: `Truncated updated section in KMT file detected`, fallo al enlazar
  mapping table0 y `Data/data:9(NeedsRepair)`.
- Dos copias de índices del dispositivo tienen encabezado y bloque ordenado con
  hashes válidos, orden estricto, ubicaciones dentro de límites y distribución por
  bucket explicada por objetos normales y cabeceras de reconstrucción.
- Ambos terminan exactamente en `align4K(0x28 + sortedSize)`: reserva de
  actualizaciones0. El escritor incremental usa
  `max(0x30000, align4K(0x28 + sortedSize))`; al crecer pierde toda la reserva.
- La muestra nativa previamente documentada por
  `ForeverCascTest.cachedNativeIndexDiagnosticIsOptInAndFailsClosedWithStructuredEvidence`
  tiene96 páginas de512B de actualizaciones (48KiB). Sus registros no se copiarán:
  la prueba propuesta solo reserva espacio vacío tras los registros ordenados.
- No se conoce aún el mínimo exacto que exige el binder de70450. Los hashes
  correctos no prueban que el formato sea compatible.48KiB es una variante acotada
  basada en el área observada, no una conclusión sobre el mínimo nativo.
- Artefactos de lectura en
  `/home/adrian/wowforever-artifacts/work/client121-prepared-launch-20261020/`.

### Comparación y preparación tras aprobación

- Referencia fijada a `bf6ad0ee50be19e6ab3b5d05186a3f6165bf5569`.
  `GameInstaller.kt:126–146` descarga configuraciones, índices CDN `.index` y
  archivos sueltos; su comentario deja la creación de índices locales al juego.
  README describe ~0,5GB iniciales y ~67GB descargados después por el juego.
  No aporta una solución de formato para los `.idx` generados por nuestro escritor;
  no se sustituye nuestro caso de instalación completa por ese flujo.
- Copias completas de los16 índices (~20,7MB), shmem y RepairMarker en
  `work/native-kmt-reserve-20261020/original/` externo. Hash SHA256 del origen antes
  y después de copiar coincide con cada copia. Los16 encabezados y bloques ordenados
  pasan sus hashes y los16 tienen reserva0 (`backup-report.json`).
- Preparadas16 copias locales con48KiB adicionales vacíos por índice
  (786432B totales); sus bytes originales se conservan exactamente y el parser
  existente recupera el mismo número de entradas, sin actualizaciones no vacías.
  `candidate-report.json` registra ambos hashes y tamaños en el momento de preparación.
- Reinicio confirmado: boot ID `2b014ccb-6c94-4593-850f-f10c5dfb9fc1`, distinto del
  anterior. ADB restablecido en192.168.1.130:46601. Tras reconectar el adaptador
  completo, Android monta otra vez el volumen6AD6-EF0E; sin app/Wine ni journal activo.
- Publicadas las16 variantes mediante archivos hermanos previamente comprobados y
  renombrado, con identidades de directorios congeladas, exclusión y hashes antes y
  después. Solo786432B adicionales de ceros; auxiliares y data.NNN intactos.
  Evidencia externa: `stage-report.json`, `publish-48k.sh`,
  `publication-transcript.txt` y `publication-report.json`.
- Primer clic bloqueado antes de Wine por una reserva GUEST_RESERVED de21:43.
  Recuperación explícita a través de `recoverPreviousGuestOperation`, invocada con
  el depurador en el host reiniciado: exige destino coincidente y observación
  Quiescent; resultado IDLE. No se borró/editó el registro manualmente. Transcript y
  registros antes/después conservados. Falta una ruta visible de esta recuperación
  para el usuario; no se ha cambiado esa puerta en el APK.
- Tras esa recuperación, un lanzamiento a22:25:56 CEST crea WowB PID24130 y un
  Tact.log nuevo a22:26. Mapea los16 índices y abre89 archivos data.NNN; por sí solo
  eso no sería prueba completa del formato. Una traza acotada sin contenidos de
  read/write muestra después preparación de `Data/indices/*.index`: a22:32 hay942
  archivos (~95,5MiB) de1424 archives de la CDNconfig. Hay progreso nativo posterior
  al rechazo KMT original, pero todavía no login/render ni aceptación de todos los
  objetos CASC. La traza se retiró: TracerPid0 en proceso y todos sus threads.
- Corrección permanente en curso para C122: reservar como mínimo la variante
  comprobada de48KiB tras el bloque ordenado alineado, conservando0x30000 como suelo
  de tamaño total y sin cambiar entradas/semánticas de actualización. El mínimo
  exacto nativo sigue sin determinarse.
- Código y regresiones escritos y revisados independientemente, sin bloqueantes.
  La estimación y ambos escritores usan el mismo tamaño; el límite de lectura se
  amplía solo para admitir esas salidas. Una finalización reanudada elimina índices
  incompletos y los regenera con la nueva regla, sin cambiar el journal. Se conserva
  la lectura de índices antiguos como fuentes, sin afirmar su compatibilidad nativa.
- Primera reconstrucción detenida correctamente por el hash de postimagen CASC
  fijado. Tras revisar el delta se actualizaron únicamente los dos hashes exactos;
  no se relajó la guarda. Reconstrucción v2 auténtica creada para C122
  `0.2.98-native-index-reserve`. Cinco tests host del auditor aprobados.
- C122 terminó compilación en4m44s. Los8 tests focales pasan sin errores ni skips
  (11,229s), incluido el fixture de1.319.619 registros. Auditoría
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2215 fuentes base intactas y178 payloads,
  sin ampliar namespaces. APK484387787B SHA256
  `dd2b530cd07f8e04f0968c9d95f5eed7d9f1bc114159e18d12dd43acce32e560`.
  Evidencia canónica externa: `arm64-prototype/client122-native-index-reserve-reviewed-v2-20261020/`.
  No se ha instalado C122 mientras el juego está activo.
- A22:42:30 existen los1504 nombres referenciados por archives(1424),
  patch-archives(78), file-index y patch-file-index. Es una comprobación de presencia,
  no de hashes ni garantía de fin del arranque. A22:43 la traza muestra lecturas
  reales del juego de data.014/data.019/data.002, con read_bytes381064192, posterior
  a esa fase de metadatos. Todos los threads quedan con TracerPid0 tras retirar la
  traza. Todavía pantalla negra, Tact.log nuevo vacío y login/render pendientes.

### Límites y criterio de éxito

- No descargar de nuevo WoW, recorrer90GB de CASC, ejecutar reparación genérica,
  cambiar ejecutable, build, drivers, runtime, admisión o configuración de cuenta.
- No eliminar archivos del cliente. Conservar copias exactas y hashes de los16
  índices y de los auxiliares que haya producido el juego (`shmem`, RepairMarker).
- No modificar índices con Wine o el juego en ejecución. Comprobar estado y cerrar
  el intento; respetar la restricción vigente de un nuevo intento tras reiniciar
  Android. El reinicio puede exigir reconectar ADB Wi-Fi.
- Cambiar exclusivamente la extensión vacía de los16 índices, manteniendo todos
  sus bytes originales; preparar y comprobar primero las copias en el host.
  Publicar con archivos hermanos y renombrado tras revalidar originales/volumen,
  sin mezclarse con journal/updater activo. Si no se puede asegurar esa exclusión,
  no publicar.
- No tocar auxiliares a ciegas. Inspeccionar su vinculación; si impiden un arranque
  limpio, conservar copia y explicar la necesidad antes de retirarlos del destino.
- Un experimento con48KiB vacíos después del límite alineado permite contrastar
  la hipótesis sin alterar entradas, hashes ordenados ni archivos data.NNN.
  No encadenar tamaños arbitrarios si falla. Restaurar los índices originales,
  solo en reposo y con comprobaciones de identidad/contenido, y conservar logs.
- Si se supera KMT, corregir de forma permanente el escritor y su estimación de
  espacio usando el formato validado, con pocas pruebas de regresión focales.
  No generalizar semánticas de actualizaciones no vacías ni migraciones automáticas.
- Éxito parcial: desaparece el rechazo KMT y se abre el contenedor CASC.
  Éxito del objetivo: pantalla de login y tentativa autorizada de acceso; si aparece
  otro fallo, informar exactamente esa nueva frontera, sin declarar login logrado.
- No leer, exportar ni introducir credenciales almacenadas mediante herramientas.
  Si hacen falta, pedir al usuario que las introduzca en el juego.

## Implementation / Action Items

- [x] 1. Aislar error nativo y comprobar dos índices por lectura acotada.
- [x] 2. Aprobación de esta prueba reversible, incluido cierre/reinicio del teléfono.
  - [x] 2.1 Comparar la generación de índices del repositorio aportado, fijando commit
    y sin tratar su código o documentación como autoridad automática.
- [x] 3. Copiar/verificar los16 índices, documentar estado y preparar copias48KiB.
- [x] 4. Cerrar intento, asegurar exclusión y efectuar prueba reversible aprobada;
  registrar todos los hashes y revalidar tras publicación.
- [x] 5. Un arranque controlado con logs/captura. Evaluar apertura CASC, no solo
  aparición de proceso/ventana. Restaurar si la hipótesis no queda respaldada.
- [x] 6. Con formato validado, corregir escritor/estimación/pruebas focales;
  revisión independiente por tocar datos persistentes, compilar/auditar y entregar
  APK conservando preferencias y admisiones.
  - [x] Código, regresiones, revisión, compilación y auditoría C122.
  - [x] C123 instalada el21oct, conservando la corrección CASC de C122. Versión123
    confirmada y7 hashes privados idénticos antes/después. No se instaló con Wine
    activo. Evidencia externa: `work/client123-server-identity-20261021T054157Z/`.
- [x] 7. Intentar llegar al login y registrar resultado real y pendientes.
  El usuario informa de envío y «login to server», seguido de retorno a contraseña
  sin error ni selección de personaje. Los logs, volcados al salir, acreditan
  autenticación y lista de reinos correctas, seguidas de rechazo de identidad del
  servidor en grupo8. Sin otro envío por el agente ni captura de credenciales.
  Este nuevo bloqueo se trata en `arm64-server-identity-plan.md`; auth/mundo no
  se presentan como resueltos por la reserva de índices.

## Open Issues

- A22:47:25/33 se ven fotogramas diferentes de la cinemática; segunda captura con
  subtítulos. `gx.log` confirma70450 ARM64 y creación D3D11 correcta a22:43:37 sobre
  Mali-G610 MC6. Es evidencia de presentación posterior al fallo KMT original, no
   de una escena3D interactiva. Después se alcanzó el login y el usuario hizo el
   intento autorizado; salió del juego y C123 quedó instalada con la corrección.
- El tamaño mínimo nativo de reserva no está demostrado; se ha validado una
  variante específica de48KiB, no un mínimo ni todos los formatos posibles.
- La exclusión y recuperación deben comprobarse antes de escribir en el USB.
- La recuperación de una reserva de guest tras reinicio existe pero no está
  expuesta en la UI normal; quedó registrada como bloqueo separado del índice.
- El objetivo de imagen/animación de carga tiene plan separado y no forma parte
  de esta corrección.
