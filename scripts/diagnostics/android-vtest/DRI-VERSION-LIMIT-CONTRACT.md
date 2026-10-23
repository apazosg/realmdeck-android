# Último control core: contrato aprobado

Solo POCO USB `DEVICE_SERIAL`, package `com.wowforever.arm64probe`, UID10330;
plan `.agents/planning/done/dri-version-limit-plan.md` aprobado y completado. No modificar el
observer anterior, el contexto original, server/guest, APK, Wine o drivers.

## Autoridad

Provider privado `guest/lib/libgallium-26.2.4.so`, SHA-256
`910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5`.
La función AA64 `driCreateContextAttribs` en VA `0xaa856c` lee el máximo core
uint32 little-endian a screen+64 en `0xaa88b4`. La selección API3 y el bloque
`0xaa88b4–0xaa88c4` deben verificarse contra bytes del ELF fijado antes de leer.
Solo se aplica a API3/atributos exactamente major3/minor3, dos pares únicos.

## Lectura y forwarding

Observer hermano `DriVersionObserver.c`; hijo `GuestEglDriVersion`, biblioteca
`libdri_version_observer.so`, artefactos en controles externos `dri-version-limit`.
Mis­ma secuencia exacta de nueve parámetros/provider retenido/errno/recursión.
Usar `process_vm_readv` dirigido únicamente a `getpid()` para una iovec de 4 bytes
antes y otra después: retorno exactamente4 es lectura válida. Error, parcial,
syscall denegada/no disponible, overflow o cambio de campo => medida UNKNOWN.
Nada de desreferenciar screen, `/proc/.../mem`, ptrace, handlers o rescates.
Las pruebas de lectura ilegible se ejecutan exclusivamente en hijos sintéticos.

No registrar direcciones. Hasta4 eventos/4KiB, campo uint32 antes/después y
estado/error/tamaño de ambas lecturas. Rango observado razonable: 0 o versión
decimal10..99. Nunca inferir versión máxima a partir de GLSL/capset version.

## Evento

Prefijo `WFDRI_VERSION`, schema `wowforever.android-dri-version-observer/version1`.
Conservar campos del observer de retorno (RUN/PID/UID/secuencia/API/pares,
forwarded/providerVerified/result/error/errno). Añadir `layoutVerified`,
`scopeMatches`, `requestedVersion`, `maximumBefore`, `maximumAfter`,
`beforeReadBytes`, `afterReadBytes`, `beforeReadErrno`, `afterReadErrno`,
`measurementValid`, `checkInference`. Sin valores no leídos: null, no0 supuesto.

Inferencias permitidas SOLO con los witnesses íntegros y campo estable:
`EARLY_CHECK_WOULD_REJECT` (máximo0 o33>máximo con retorno null/BAD_VERSION3),
`NOT_EXPLAINED_BY_EARLY_CHECK` (máximo>=33 pero null/BAD_VERSION3),
`NO_BAD_VERSION` (resultado compatible sin error3); lo demás `UNKNOWN`.
La inferencia no es un PC de rama observado ni identifica la feature causante.

## Runner

Nuevo runner fijo, mismo server negociación `e1aa871d...` y guest manifest
`30c438...`/tree `1cc803...`; solo preload del hijo sustituido por el hermano.
Pins completos reconciliados tras build y review. Fuente/binary/provider/layout
HOST y device hash comprobados, stderr estricto, binding a resultado nativo válido
antes de atribuir la medida. Mantener stdout/retorno/raw anteriores y no ejecutar
nada si la autoridad no coincide. <=64KiB artefactos, <=32MiB workspace, NDK24.

La observación no acepta contexto/render/D3D/WoW. Un fallo de lectura o scope no
se convierte en diagnóstico GPU. Una medida sin corrección pequeña cierra esta
pareja; PanVK requiere su propio gate antes de instalar o ejecutar un ICD.
