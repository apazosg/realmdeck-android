---
id: managed-admission-rebind-2026-10-20
state: in-progress
date: 2026-10-20
---

## Summary

**Resultado C121 posterior:** WoW llega a crear proceso y ventana de error
«installation is damaged». `Tact.log` rechaza KMT/mapping table0 de Data/data con
NeedsRepair. No hay login; investigación de formato/estado del índice sin cambios
de datos ni reparación. Este fallo es posterior a la identidad y preparación.

**Seguimiento físico C120 (20oct21:31 CEST):** migración automática completada,
admisión v2 publicada y ImageFS descargado/preparado por el primer Jugar.
Un defecto de interfaz adicional oculta el panel «Lanzar WoW»: exige HomeState.Ready
aunque el cliente gestionado conserva deliberadamente PRESENT_NOT_VERIFIED en la
inspección superficial. Se corrige esa puerta usando la preparación fuerte ya
obtenida, manteniendo consentimiento, disponibilidad y revalidación antes del
lanzamiento. No se alteran las garantías de integridad ni se repite la descarga.
C121 compilada/auditada e instalada (SHA256
`f7a17046e4d43983235c48e97e934af32d3edbb39e983374958ce3f16d9a3242`),
20 pruebas host focales aprobadas y siete hashes privados conservados. Panel y
consentimiento confirmados físicamente; un Lanzar WoW a21:43:09 abre presentación
y copia DLLs al primer prefijo Proton. Proceso WowB/render/login aún pendientes.

C119 instalada y validada offline, pero Jugar falla antes de descargar ImageFS.
El cliente70450 conserva ejecutable oficial (MD5/tamaño/PE) y `.build.info`
idénticos a la admisión privada de C117. No se perdió esa admisión al actualizar.
Se confirmó desde el propio proceso Java, mediante breakpoint de solo diagnóstico,
que las identidades actuales de raíz/base ya no coinciden con las persistidas.
No basta la observación desde ADB: la comparación Java también está confirmada.

## Content

- Admisión anterior: storage_id `storage-7b6a7bc07233e2182cdbe680913416aa137cd9d533db36f3a6a65c5476e7e29d`,
  root_identity `(dev=1000a6,ino=1053434)`.
- Proceso C119: raíz `(dev=1000a6,ino=1095827)`, base `(dev=1000a6,ino=1095825)`,
  selección `volume:6AD6-EF0E`, mismo destino USB canónico.
- storage_id actual calculado con el algoritmo existente:
  `storage-36bc9cbed990acc40f5c12c3fa838e9caff25fd5fa3eb401d1e4e90f94f53dfd`.
  Su fichero de admisión no existe; hay un único registro anterior.
- Evidencia: `work/client119-first-run-runtime-20261020/` fuera del repositorio:
  `app-namespace-identity-breakpoint.txt`, `rejected-client-disk-proof.txt`,
  `rejected-managed-admission.json`. Breakpoint retirado y proceso reanudado;
  sin credenciales leídas ni condiciones modificadas para pasar la guarda.

### Investigación de causa solicitada por el usuario

La propuesta anterior de simplemente emitir otra admisión queda retirada: no
corregiría que el fallo reaparezca al cambiar los identificadores transitorios.
El usuario pidió «hay que revisar cual fue el motivo de que sucediera esto y
resolverlo». Investigación realizada sin modificar cliente, admisiones ni montajes.

**Defecto confirmado en la app:** se convierte `BasicFileAttributes.fileKey`
en una identidad persistente. `operationStorageId()` incluye el inode de la base
aunque su comentario dice que evita identificadores transitorios. La admisión
privada añade el inode de la raíz como condición persistente. Ambos se comparan
entre ejecuciones; el registro de ubicaciones también conserva `rootKey`.
La primera discrepancia cambia el nombre del fichero privado buscado: no se lee
siquiera la admisión anterior y se termina mostrando `client_executable_invalid`.
El gestor usa otra comprobación del contenido y por eso indica «actualizado».

**Filesystem real, no supuesto:** Android14/SDK34, XiaomiOS2.0.2.0.ULOEUXM;
`/dev/block/vold/public:8,65` montado VFAT en `/mnt/media_rw/6AD6-EF0E`,
expuesto mediante `/dev/fuse` en `/storage/6AD6-EF0E`. No es exFAT.
Uptime439003s, sin reinicio entre las pruebas. La consulta actual conserva
raíz1095827. Las rutas inferiores `media_rw`/`pass_through` están denegadas sin
root: no se intenta eludir esa protección.

**Por qué ese identificador no es durable:** Linux FAT asigna `i_ino` mediante
`iunique()` en `fat_build_inode()`; `fat_evict_inode()` elimina el objeto de su
caché. Una nueva resolución puede asignar otro inode sin borrar el directorio.
AOSP Android14 `FuseDaemon.cpp::pf_getattr()` devuelve atributos obtenidos por
`lstat()` del backing path; no convierte el inode FAT en un ID persistente.
No debe confundirse el `nodeid` interno FUSE con el `st_ino` de esos atributos.

Fuentes contrastadas:
- https://raw.githubusercontent.com/torvalds/linux/v5.10/fs/fat/inode.c
  (`fat_build_inode`, `fat_evict_inode`, comentario sobre ubicación en disco).
- https://raw.githubusercontent.com/aosp-mirror/platform_packages_providers_mediaprovider/android14-release/jni/FuseDaemon.cpp
  (`make_node_entry`, `pf_getattr`). El firmware puede incorporar modificaciones.
- https://docs.oracle.com/javase/8/docs/api/java/nio/file/attribute/BasicFileAttributes.html
  (`fileKey`, garantía limitada; no un contrato de identidad durable).

**Alcance de la conclusión:** está demostrado el error de duración de la
identidad y que cambió en Java real. La reclamación/recreación de inodes FAT es
una explicación compatible con los datos y con el código del filesystem, no una
traza histórica del instante exacto. No se demuestra que `install -r` fuera su
disparador. Tampoco hashes iguales demuestran por sí solos que nunca se reemplazó
un directorio por una copia idéntica. La auditoría de los escritores no encontró
renombrado/reemplazo de la raíz en C117–C119; sí creación de WTF dentro de ella.

El registro privado de selección aún enlaza `volume:6AD6-EF0E`, los fingerprints
de raíz/base y el antiguo rootKey1053434, igual al de la admisión privada. Esto
permite diseñar una migración acotada vinculada a la selección existente, sin
buscar cualquier admisión que tenga un ejecutable coincidente.

### Corrección de causa aprobada

Separar las duraciones de identidad, sin quitar las guardas contra sustituciones:

1. Identidad durable: volumen seleccionado y ruta canónica dentro del namespace
   permitido de la app. No incluir inode de base/raíz en claves persistentes.
   La admisión debe vincular explícitamente ese destino y el build/contenido.
2. Identidad de operación: conservar comparaciones estrictas de base, raíz,
   montaje y archivos congelados durante la misma operación/lease. No aceptar
   cambios a mitad de verificación, configuración, actualización o lanzamiento.
3. Compatibilidad: migración local versionada y acotada de la admisión antigua,
   solo enlazada al registro privado de esa selección/ruta y con comprobación
   existente de `.build.info` y archivos sueltos oficiales. Revalidar destino
   antes y después; ambigüedad/conflicto/alteración falla cerrado. Conservar el
   registro antiguo, no editarlo a mano. No ignorar su inode sin otra vinculación.
4. Revisar los usos persistentes del ID compartido y del registro de ubicaciones
   para que no quede el mismo fallo en otra puerta. No relajar automáticamente
   la cualificación de escritura por montaje: su duración puede ser deliberada.
   Ante journal pendiente antiguo, bloquear con diagnóstico, nunca descartarlo
   ni cambiar su identidad silenciosamente. Actualmente no hay journal pendiente.
5. Mostrar causa distinguible: identidad/admisión no disponible no equivale a
   ejecutable corrupto. No declarar verificación integral a partir de metadatos.

No ejecutar `plan/execute/repair` para migrar: podría recorrer CASC. Reutilizar
parsers/verificadores/escritores existentes, sin framework nuevo ni descarga de
metadatos por sorpresa. No nuevo barrido de ~90GB. Comprobar los archivos sueltos
no vuelve a certificar CASC ni autoriza anunciar integridad completa. No tocar
runtime/drivers/autenticación ni borrar datos. La descarga ImageFS de175MiB ya
autorizada permanece en el flujo normal de primer Jugar, una vez resuelto esto.

## Implementation / Action Items

- [x] 1. Reproducir, auditar la persistencia y contrastar FAT/FUSE reales; documentar
  el defecto confirmado y la incertidumbre sobre el disparador histórico.
- [x] 2. Aprobar la separación durable/operación y la migración acotada propuesta.
  Usuario: «si hazlo»; autoriza implementación, validación e instalación.
- [x] 3. Implementar la corrección mínima en almacenamiento/admisión existentes,
  conservando exclusión de E/S y guardas de sustitución durante la operación.
- [x] 4. Tests focales: mismo destino/contenido con inodes distintos entre
  ejecuciones aceptado; cambio durante operación, UUID/ruta distinta, conflicto,
  migración ambigua, metadatos/ejecutable alterados y journal antiguo rechazados.
  Ningún barrido CASC para lanzar. Reusar infraestructura existente.
- [x] 5. Revisión independiente de la frontera de confianza, compilación,
  auditoría de APK e instalación conservando datos.
  Revisión independiente cerrada: identidad congelada durante la migración,
  segundo leído de `.build.info` vinculado a su SHA anterior, journal real
  comprobado aunque no quede lease y sin veto persistente por inode al retargetear
  un A: previamente registrado. Compilación y auditoría aprobadas; C120 instalada.
- [ ] 6. Un intento de Jugar: preparación ImageFS con progreso, arranque y login
  si se alcanza; no extraer ni inventar credenciales.

## Open Issues

- Diseño aprobado: corregir la identidad persistente, no solo renovar un registro
  que volverá a caducar. La causa histórica exacta no bloquea esta corrección.
- La ruta local debe reusar exactamente las comprobaciones existentes, no crear
  otro parser/verificador ni declarar integridad CASC basándose en metadatos.
- El disparador histórico exacto de la reasignación no está trazado. No atribuirlo
  concluyentemente a `install -r`, ni anunciar prueba de ausencia de sustitución
  física solo porque coincidan hashes. No requiere root ni forzar cachés/montajes.

## Verificación e instalación — 20 de octubre de 2026

- C120 `0.2.96-durable-storage-identity`, APK SHA256
  `96edd094c09ef201b35d71ca3d343898a5efb07dcd84005205f7ee22b0ff3381`.
- Snapshot/auditoría externos:
  `arm64-prototype/client120-durable-storage-reviewed-v3-20261020/`.
  Gradle: 74 tests Storage aprobados y `assembleModernDebug` correcto.
  Snapshot v2: Service15, UX12 y OperationLease7 aprobados; no repetidos porque
  la reparación posterior afectó únicamente a Storage y su fixture.
  Host:19 aprobados,1 exclusión por aserción histórica de texto de autologin.
- Auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,2215 fuentes base intactas,
  178 payloads. No ampliación del allowlist para esta corrección.
- `adb install -r` correcto; seis hashes de preferencias/admisión privada
  idénticos antes/después. Sin journal ni servicio de descarga pendiente.
- Primer Jugar C120 a21:29:52CEST: gestor reconoce70450 actualizado; se publica
  admisiónv2 y se conserva la antigua. La UI pasa a preparación de contenedor;
  todavía sin prueba de Wine/login en esta observación. No descarga del cliente.
