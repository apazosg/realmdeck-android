---
id: launcher-client-storage-2026-10-16
state: done
date: 2026-10-16
---

## Summary

Implementación solicitada explícitamente por el usuario en el móvil USB actual:
seleccionar almacenamiento del cliente y sus datos. Concreta DATA3 del plan de
producto y la corrección propuesta: destinos internos/USB que Android permita,
sin mover, duplicar ni borrar la instalación actual automáticamente.

## Content

### Alcance de esta entrega

- Selector visible «Almacenamiento del cliente» en Ajustes. Un mismo root contiene
  `WowB-ARM64.exe`, `Data/` y `WTF/`; no separar ejecutable/datos con symlinks ni
  cambiar ubicación de Wine, prefix, ImageFS, claves, logs o estado de control.
- Default: root actual de la PoC, sin migración. Enumerar directorios app-specific
  disponibles mediante APIs Android; extraíbles/USB solo si Android los expone
  como ruta real con permisos suficientes. Sin `MANAGE_EXTERNAL_STORAGE`, root
  o conversión falsa de un URI SAF a POSIX.
- Mostrar ruta, disponibilidad, espacio libre y presencia de cliente/datos. Si
  el medio seleccionado desaparece, mantener su identidad y mostrar «No disponible»;
  no escoger almacenamiento interno silenciosamente ni crear otro cliente allí.
- El root seleccionado debe usarse en detección, preparación y mapeo A: de Wine.
  No limitar la implementación a guardar una preferencia sin efecto.
- Selección cambia solo cuando no hay sesión/IO activo. Si había preparación,
  invalidarla y exigir nueva preparación. Congelar identidad/path/volumen por
  intento y revalidar antes de writes y de launch. No actualizar la sesión viva.
- Verificar rutas app-owned, ausencia de enlaces en el subárbol del cliente,
  acceso/escritura/rename pequeño y capacidad. Nunca probarlo escribiendo sobre
  `Data`, el PE, el WTF original o archivos ajenos; probes con nombres únicos.
- Cliente existente: no confiar en `.exe` por nombre; validar PE ARM64/build
  fijado (hash original conocido), WTF regular y `Data` regular. Presencia de
  Data no equivale a integridad completa. No adoptar carpeta personal arbitraria.
- Destino vacío: puede quedar seleccionado, pero se muestra «Cliente no instalado»
  y no se inicia WoW desde el cliente antiguo. No lanzar descargas por inercia.

### Límites importantes

Esta entrega selecciona y utiliza ubicación del cliente y Data; **no convierte
el catálogo bootstrap finito existente en instalador completo de decenas de GB**.
No anunciar «instalación completa/repair/updater» ni añadir un botón de descarga
que oculte esa frontera. Reusar datos ya presentes y futuros descargadores deberán
consumir el mismo root. No copiar automáticamente el cliente para llenar un USB.

Un selector SAF arbitrario no es un destino Wine válido por sí solo. Una posible
fuente de importación/copia SAF es trabajo aparte; no mostrarla como ejecutable.
Si Android no expone el USB, explicar que falta un destino compatible, no solicitar
privilegios de sistema ni inventar una unidad disponible.

### Corrección de navegación asociada

El usuario no encuentra los campos en el móvil conectado. Mover el acceso a
credenciales arriba en Ajustes con etiqueta inequívoca «Usuario y contraseña» y
contraste suficiente. Permitir abrir Ajustes estando preparado pero sin juego/IO
activo; cambios de almacenamiento invalidan preparación y consentimiento.
No modificar cifrado, `SecureOn`, estados de autologin ni habilitar el sender.

## Implementation / Action Items

- [x] 1. Registrar alcance y leer rutas actuales/API de volúmenes. Móvil actual
  solo presenta almacenamiento privado/emulado; no hay USB público montado.
- [x] 2. Implementar enumeración, snapshot/selección persistida por identidad,
  preflight, root conjunto y bloqueo de fallback/selección durante IO/sesión.
- [x] 3. Conectar root a inspect/preparación/A: y revalidación de plan congelado,
  conservando resto de drives y root previo intactos.
- [x] 4. Integrar UI compacta de destinos y acceso visible a usuario/contraseña;
  validar vacío, medio ausente, falta de permisos y datos no verificados.
- [x] 5. Tests dirigidos, review independiente de integridad y rutas, build/APK
  auditados y actualización del móvil sin borrar datos ni cambiar runtime.
- [x] 6. Validar destino por defecto y preservación de originales, UI y selección;
  USB real solo si se conecta medio compatible, sin afirmar prueba inexistente.

## Open Issues

Actualización2026-10-17: el primer punto de ausencia de hardware siguiente queda
superado para selección/preflight. USB vfat6AD6-EF0E montado y enumerado en la app;
selección UI interno→USB→interno confirmada en code79 con preflight de archivos
pequeños, sin copiar cliente ni arrancar juego. Hashes PE/WTF originales intactos.
Evidencia `work/wifi-uhid-control-20261017/` bajo artefactos externos. Persistencia
tras reinicio, desconexión/remount y ejecución/descarga completa USB aún pendientes.

- No USB público montado en el dispositivo conectado: las pruebas de desconexión/
  identidad necesitan fixtures y la ejecución desde USB queda pendiente de hardware.
- La provisión automática completa y actualización incremental siguen en otras
  etapas; no ampliar a descargas masivas o migración por interpretar «datos».
- Un runtime/container ausente en otro móvil sigue siendo un bloqueo de provisión
  independiente del destino elegido; no prometer que elegir USB lo soluciona.
- Si el mapeo A: actual no pertenece a un root previamente reconocido, detenerse
  sin remapear carpetas ajenas. No cambiar bibliotecas genéricas GameNative.

### Entrega privada code76 (2026-10-16)

- Instalado `com.wowforever.arm64probe`, UID10330, code76,
  `0.2.52-functional-client-storage-usb`, sin borrar datos. APK419197192 bytes,
  SHA-256 `08546c9e79be6529ef1e8077773b7fa4a6c80cf1f3a3bab005172c1ce46e995d`.
- Selector real: root elegido compartido por PE/Data/WTF derivado, detección,
  preparación y A:. Identidad de volumen, mount, archivos y revisión congeladas;
  revalidación antes de escrituras y de reservar la sesión de juego.
- Se combinan directorios de Context con `StorageManager.storageVolumes` y
  `StorageVolume.directory` (API30+) para USB temporales que Context no enumera.
  No se construye un path a partir de UUID si no hay directorio público. API29
  conserva destinos de Context; no se inventa soporte OTG.
- Namespace extraíble fijo: `Android/data/<paquete>/files/CustomGames/WoW Forever`.
  Snapshot sin creación, probes, hashing o recorrido de Data. Creación/probe son
  explícitos al confirmar destino. Denegación Android no provoca cambio, copia,
  fallback o solicitud de privilegios para eludirla; mensaje de error acotado.
- USB de solo lectura o montado sin acceso se muestra sin anunciarlo escribible.
  Destino vacío se mantiene seleccionado con «Cliente no instalado»; desconectado
  conserva identidad. No se mueven ni descargan datos para llenarlo.
- Cerrados hallazgos de rutas/symlinks, registro de destinos previos, IO/selección
  exclusivos y reserva de sesión hasta teardown. Las observaciones de review sobre
  separador de drives y binding de ancla de path eran falsos positivos contrastados
  contra la API/código, no motivo para debilitar los checks.
- Code75 intermedio tuvo errores Kotlin de visibilidad/SAM y un fixture remount
  incompleto, corregidos sin falsear sus fallos. El caso corregido se repitió solo;
  recibo derivado test-only preservó inputs de producción/marker. Code76 tuvo
  reconstrucción fresca y **24 tests JVM**,0 fallos/errores, build offline
  SUCCESSFUL8m3s. Los7 tests de perfiles anteriores no se repitieron sin cambios.
- Host wiring/UX/receta:31 passed,1 deselected. No suite global ni equivalencia
  de fixtures con ejecución real desde USB.
- Auditoría76:2142 inputs intactos durante build, firma conservada,174 payloads
  iguales al baseline64 salvo marker. JNI/runtime/driver sin cambios.
- Workspace externo:
  `/home/adrian/wowforever-artifacts/arm64-prototype/client-storage-code76-usb/`:
  recibo, log Gradle, auditoría, capturas y `selected-root-device-preparation.json`.
- UI física: **Almacenamiento del cliente** y **Usuario y contraseña** arriba
  en Ajustes. Editor protegido accesible antes y después de preparar;2 campos,
  password enmascarado,flagSECURE,sin introducir datos ni capturar ese diálogo.
- Preparación física: cliente/Data presentes en root legacy, PE/hash validado,
  A: apunta a ese root y otros drives conservados. Preset de8 claves sin duplicados
  (gamepad/calidad visible1/30FPS), PE y WTF original intactos.
- Continuación: un lanzamiento desde esa preparación, imagen del cliente en
  horizontal sin Login/credenciales. Cleanup propio exit0, runtime ausente y
  bienvenida reabierta. No se probó salida normal sin force-stop; franjas de
  presentación y envío de autologin siguen pendientes.
- **No hay USB público montado**: GUI lo refleja. Vacío/desconexión/remount/
  denegación se cubren con fixtures; lectura/escritura y arranque real requieren
  una unidad montada en Android con acceso directo compatible. El cable al PC
  no equivale a una unidad USB montada.
- Instalador completo, runtime en móvil nuevo, repair y updater continúan fuera
  de esta entrega. Elegir un destino vacío no instala el cliente.
- Exclusión de IO/sesión probada en el proceso y durante recreación de Activity.
  Recuperación tras muerte del host con posibles procesos Wine huérfanos no se
  ejercitó; no presentar esta entrega sin migraciones destructivas como aceptación
  del journal duradero que requerirán instalación/updater de producto.

### Revisión del cambio efectivo y entrega code78 (2026-10-16)

- Usuario: «sería conveniente revisar y probar que el cambio se almacenamiento
  funciona». Revisión de selección→preparación→A: confirma conexión real, no
  preferencia decorativa. Corregido hallazgo de enlaces/special files anidados
  según NFR-002 existente: scan NOFOLLOW de metadatos, profundidad64/100000 entradas,
  sin hashes/contenido y nunca en snapshot. Se repite en selección/preparación/
  revalidación; coste en USB lento aún sin medir y sin snapshot atómico frente
  a mutaciones externas concurrentes.
- Corregida deduplicación Context/StorageVolume: conservar ancla nativa y metadata
  solo con namespace no primario exactamente derivado de la API, no por UUID solo.
- Regresión A→B→A usa servicios recreados, preferencias persistidas y contenedor
  real guardado/recargado, no solo helper de strings. Comprueba A:, demás drives,
  preparaciones obsoletas rechazadas, WTF derivados en A/B y PE/Data/WTF originales
  representativos intactos. Vacío/desconectado con A válido no provoca fallback.
- Host16/16 aprobados. Primera compilación78 falló por APIs Java17 no expuestas
  en stubs Android del fixture de sockets; sustituido por FIFO Linux controlado.
  Después30/31 JVM aprobados; caso restante esperaba `storage_unavailable` en vez
  del bloqueo existente `storage_mount_unproven`. Expectativa corregida sin cambiar
  producción y regresión dirigida1/1 aprobada. No afirmar suite31/31 única.
- Build final reanudado tras interrupción: SUCCESSFUL3m22s; revisión independiente
  sin P1/P2 pendientes.2142 inputs intactos y174 payloads baseline64 salvo marker.
  APK419200680 bytes, SHA-256
  `5b61113fd734f1053fcce5b9d630cee7c62a4ec959430de860dc1938dd403f84`.
- Instalado con `adb install -r`, runtime ausente antes, sin borrar datos:
  `com.wowforever.arm64probe` code78 `0.2.54-functional-client-storage-verified`.
  No se instaló code77 intermedio. Cliente/driver/runtime conservados.
- En móvil: selector/root actual y Data presentes, preparación/A: correctos,
  demás drives preservados, preset8 claves, editor seguro accesible preparado.
  El arnés pidió Cerrar dos veces aunque el primero ya devuelve a bienvenida;
  error conservado y comprobaciones finales de hashes PE/WTF y runtime ausente
  completadas aparte read-only. Sin introducir credenciales/Login o lanzar guest.
- Solo hay almacenamiento interno disponible: ni cambio físico entre volúmenes,
  invalidación real por ese cambio ni selección nueva persistida en móvil probados.
  La ejecución desde USB requiere conectar medio montado compatible. No se cambian
  preferencias por shell ni se copia cliente para simularlo.
- Evidencia externa bajo `/home/adrian/wowforever-artifacts/arm64-prototype/`:
  `client-storage-code78-release/` (APK/recibo/auditoría, XML1/1, UI/capturas,
  preparación parcial y `final-device-integrity.json`), `client-storage-code78-final/`
  (XML30 aprobados/1 fallo de expectativa) y `client-storage-code78-verified/`
  (primer fallo de compilación del fixture). Se mantienen los fallos históricos.
