# Launcher ARM64 automatizado — candidato privado 0.2.9

Estado actual: **bootstrap ImageFS corregido y Wine ejecutándose en el laboratorio
USB xaga con code 33**, pero Explorer falla en `winex11.drv` con un bucle de
excepciones; no hay identidad WoW ni frame. La prueba se automatizó sin SIM,
root ni cambios de permisos de inyección y se detuvo tras conservar evidencia.
Ver [usb-lab-xaga.md](usb-lab-xaga.md). No es todavía una APK jugable.

Antecedentes: **Wine bootstrap observado; el host Android de code 28 terminó por SIGINT
con identidad de corrida comprobada**. El emisor es desconocido y no hubo WoW
validado ni frame. Code 29 adapta la ruta directa AA64 del fork funcional
`jaredgei/wow-forever-android`, con verificación más estricta que su heurística por
nombre, y conserva logs de inicio/cola. En teléfono, code 29 falló antes del spawn
supervisado: su lector local limitaba el catálogo a 2 MiB, menos que los 2.501.689
bytes realmente empaquetados. Code 30 unifica el lector acotado y añade la prueba
del asset real y el gate de tamaño en el audit. Está compilado/auditado en host,
pero todavía no probado en PowerVR. Las correcciones de perfiles, BLTE, CDN, rutas del preload
y recuperación se mantienen. No declara datos suficientes, render ni login.

## Entrega

- APK: `/home/adrian/wowforever-artifacts/delivery/wowforever-automated-0.2.9-code33-debug.apk`
- Tamaño: **362.042.153 bytes** (~362 MB / 345 MiB).
- SHA-256: `ccab6f5241a73a0f45dec15307abca7381c04041cc3e7696146842116ba27b2f`.
- Package: `com.wowforever.arm64probe`; label **Forever**, también en español.
- Versión: `0.2.9-automated`, code **33**; actualiza los codes 23–32 sin borrar datos.
  Codes 31/32 fueron ensayos internos USB, no entregas públicas intermedias.
- ABI `arm64-v8a`, minSdk **29**, targetSdk **36**, compileSdk **36**.
  Son los niveles de la variante GameNative, no los del antiguo APK JNI pequeño.
- Firma debug v2 verificada, mismo certificado que el APK anterior:
  `2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b`.
- Junto al APK: instrucciones, audit JSON, SHA256SUMS y ZIP de recetas/fuentes.
  Las fuentes GameNative modificadas están en el workspace externo indicado en
  las instrucciones. No hay release público; licencias de terceros pendientes de
  auditoría completa para distribución pública.

## Recorrido implementado

Una acción **Preparar y lanzar**, tras autorizar la descarga:

1. Recupera el journal y comprueba si queda una ejecución sin terminación probada.
2. Importa/reutiliza Wine `proton-11.0-90624-arm64ec` y DXVK
   `2.4.1-wow-aarch64-test`, verificando identidad exacta y archivos instalados.
   La lista `files` de Wine/Proton puede estar vacía: se verifican `wine`,
   `wineserver`, las bibliotecas `ntdll` AA64 Unix/Windows y `prefixPack` dentro del
   perfil. DXVK sí exige su lista de copias y `dxgi.dll`/`d3d11.dll` existentes.
3. Descarga de la CDN oficial el catálogo ARM64 fijo de `wow_classic_beta`
   `1.60.1.70009`. Verifica BLTE, tamaños/hashes, PE AA64 y rutas antes de promover.
4. Materializa `.build.info`, configuración e índices CASC V7; conserva el cache
   validado para reintentos. No porta todo cascette/Python a Android.
5. Genera solamente `WTF/BetaSuspendedTest.wtf` con el portal/locale canónicos;
   conserva otras líneas y nunca modifica `Config.wtf`.
6. Configura un custom game **interno** y contenedor dedicado, ocultos del flujo
    del usuario. Solicita `WowB-ARM64.exe -config BetaSuspendedTest.wtf -d3d11`
    solo después del bootstrap Bionic original (descarga context-only y operación
    propia que incluye migración/instalación stock). Verifica raíces/variante antes,
    bibliotecas AA64/versión después y preservación de perfiles. Un marcador
    `.variant` ausente solo se completa tras esas postcondiciones; defaults Proton
    vacíos son placeholders, no autorización para borrar carpetas no vacías.
    mediante `wine explorer /desktop=shell,... "A:\WowB-ARM64.exe" ...`, sin el
    wrapper Windows `winhandler.exe` solo para ese PE/contexto gestionados. Se
    revalidan cabecera AA64/PE32+, MD5/tamaño, ruta/cwd/unidad A, RUN y flags antes
    de seleccionar `verified-aa64-direct`. Las otras ramas upstream, los controles
    X11 y Java WinHandler/IPC no se retiran. Wine iniciado no prueba un hijo WoW.
7. Supervisa el proceso propio y busca el almacén de claves. Empieza por lectura;
   solo permite aplicar tras validar íntegramente US-002, contrato canónico y
   pertenencia al árbol de la corrida. No utiliza offsets x64 ni relaja firmas.

No hay Steam/Battle.net/ADB/root ni selección manual de Wine/driver. GameNative
se fija al commit `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`.
La selección gráfica es wrapper/Vulkan **del sistema** en PowerVR, no Turnip.
La GPU/driver efectivo se etiqueta sin medir. FEX/HODLL puede seguir atendiendo
auxiliares x86; no implica que el EXE AA64 principal pase por Box64.

La primera instalación limpia xaga de code 30 expuso que la ruta propia importaba
WCP pero omitía ImageFS: `CANNOT LINK ... libredirect-bionic-wx.so not found`, salida
1 en 39 ms. Code 33 lo supera usando el productor original, no copiando una SO.
Bootstrap: 18 casos distintos comprobados en rondas dirigidas, incluidos fixtures
de preservación no vacíos y placeholder vacío; perfiles/ruta y concurrencia se
contrastaron previamente. Recipe fresca/idempotente: 61 inputs; audit APK PASS.
La evidencia física llega a Explorer/X11, no a D3D, CertStore o login. La relación
del warning Zink/Mali con el fallo no se presenta como causa exclusiva.

## Límites de datos y recursos

El catálogo contiene **35 loose, 832 objetos runtime/metadatos y 1.402 índices
CDN**. Transferencia inicial: **748.157.712 bytes** (~714 MiB), limitada a 1 GiB.
El preflight contabiliza cache + staging + verificación + margen, aproximadamente
**1,8 GiB**, no solo los ~987 MB estimados del árbol expandido. ImageFS/runtime puede
necesitar varios GB adicionales, con reutilización de lo ya instalado.

El APK empaqueta metadatos y componentes runtime; **no contiene WoW ni CASC**.
El asset de catálogo mide **2.501.689 bytes**. Provisión y ruta directa usan
`ForeverClientCatalog`, con cap **8.388.608 bytes (8 MiB)**, validación completa y
rechazo de UTF-8/JSON inválido; no `readBytes()` sin cota en producción ni límites
distintos según consumidor. El cap no modifica la transferencia del cliente.
El bootstrap no es la instalación completa de ~84,7 GB y no demuestra suficiencia
para render/streaming. Los datos adicionales que necesite el propio juego siguen
siendo una incógnita. No se amplió la descarga al cliente completo.

## Seguridad y restricción de un solo intento

El journal registra la intención antes de `exec`, PID/starttime, raíz/candidato,
generación y boot ID. Un `/proc` ilegible es `UNKNOWN`, no proceso terminado.
Callback, cancelación y estado terminal comparten exclusión; no se aceptan eventos
de otra corrida ni se puede resucitar un estado final. El store es compartido.
Un token admite un único spawn; el veto anterior a `exec` no se presenta como un
fallo de un proceso que nunca existió. Al recuperar una corrida `ACTIVE` sin su
supervisor, se persiste `INTERRUPTED`, no un `RUNNING` obsoleto. No se reanuda un
PID antiguo ni se lanza otra instancia automáticamente.

Un sondeo de descendientes no puede descartar un hijo creado y reparentado entre
dos muestras. Por eso **ningún árbol lanzado se considera completamente terminado
solo por muestreo**. En esta versión privada, tras spawn/salida/cancelación hay que
**reiniciar Android** antes de otra tentativa. Cerrar/reabrir no basta; borrar
datos no es necesario. Los errores antes del spawn sí permiten reintentar.
La cancelación solo actúa sobre handles propios y espera de forma acotada; no hay
señales globales, búsqueda por `PID > parent`, ni borrado de otras apps.

El JNI fija `/proc/<pid>/mem`, exige UID/descendencia/starttime y un mapeo RX de
archivo PE AA64 exacto con inode/offset, y acepta únicamente el contrato canónico
embebido (máximo 64 KiB). Completa un scan de lectura antes de habilitar escritura;
solo cambia los 32 bytes de grupo 8, nunca el ancla. Informa cobertura incompleta,
mutaciones parciales/desconocidas y cuenta cada lectura una sola vez.
Los límites de 15 s / 128 MiB / 8.192 operaciones son cooperativos: una llamada
del kernel bloqueada no se puede interrumpir mágicamente. El layout ARM64 real y
la identidad de Wine en Android todavía necesitan validación.

## Diagnóstico

Exportación SAF ligada al run ID elegido, con snapshot del estado y journal:

- `run.json`, `diagnostics.jsonl` y `provenance.json`;
- argv/cwd, entorno permitido, identidad de proceso y resultado de spawn/salida;
- stdout/stderr: 128 KiB de inicio y 384 KiB de cola por stream, drenando también
  lo descartado; snapshot atómico conjunto bytes/metadata de hasta 800 KiB cada
  ~750 ms y al EOF. Exportación separada del presupuesto JSONL de 4 MiB. El estado
  final del stream se etiqueta `UNKNOWN`: puede perderse la última fracción de
  segundo o un error IO no persistido antes de morir Android;
- preflight de rutas/ELFs del preload y Wine, y candidatos `winhandler.exe` con
  tamaño/SHA/PE; limitado a rutas gestionadas, sin ejecutar los binarios;
- snapshots acotados de hijos propios, con basename/hints permitidos, no argumentos
  completos; un hint no habilita el patcher ni demuestra identidad del juego;
- eventos de Activity create/pause/resume/stop/destroy, sin convertir pausa en exit;
- `ApplicationExitInfo` disponible en el siguiente arranque (API 30+), con PID y
  processName; atribución al launcher solo con PID/package/run summary/tiempo;
- PGID/SID revalidados como metadata, sin inferir containment ni señal a grupos;
- API SIGINT nativa explícitamente `UNSUPPORTED/ANDROID_SIGNAL_OWNERSHIP_UNVERIFIED`
  antes de tocar disposiciones/archivos en Android. Decoder/fixtures no equivalen
  a captura del emisor; no se ignora ninguna señal;
- hasta 16 logs de juego de 1 MiB, con índice que declara que su selección por
  mtime no garantiza exclusividad por corrida.

Una salida al escritorio deja evidencia aunque no exista ningún log de WoW.
El launcher no solicita credenciales, pero el ZIP puede contener salida del juego:
se debe revisar antes de compartir. **Proceso iniciado, parche aplicado, render y
login son resultados distintos**, no un único PASS.

## Verificación realizada

- Tests JVM de catálogo/provisión, BLTE, CASC, perfiles, estado y recuperación;
  el harness del catálogo real se ejecutó en la ronda combinada.
- Tras el fallo de la prueba independiente, corregida la rotación de checksum-B
  de los LocalHeaders CASC: **3 tests CASC pasan**, con igualdad de los 16 índices
  y `data.000` frente a cascette. No se debilitó el golden.
- Code 30: **17 tests JVM pasan sin skips**, cinco del catálogo real/lector acotado,
  siete de PE/ruta/contexto y cinco de provisión. Primero se reprodujo exactamente
  el rechazo de code 29 con el asset real. Ahora el mismo reader usado por el gate
  selecciona el registro oficial y el provisionador calcula el mismo presupuesto;
  EOF justo al límite, límite+1, lecturas fragmentadas/cero, UTF-8/JSON y pins se
  comprueban. No se ejecuta ningún PE, Wine ni nuevo HTTP.
- Historial code 29: **58 casos JVM distintos comprobados en rondas dirigidas**, sin skips: 45 no
  afectados pasan en la primera ronda; los 13 casos modificados de señales/logs,
  ownership y lifecycle pasan en la ronda final. Incluyen 7 casos de ruta AA64,
  perfiles (9), preflight (4), recuperación (14), política de procesos (5), estado
  (4), lifecycle/veto (2), Wine (2), trazas/retención (4) y seguridad/IO (7).
  Se corrigieron dos fixtures, no el contrato: UID de emisor desconocido debe ser
  cero; Robolectric necesita aislar supervisor estático al sustituir Application.
  Las pruebas usan archivos inertes/Process falsos, no ejecutan Wine ni el cliente.
- **16 tests Rust/JNI de host pasan** (12 previos + 4 de señal en hijos propios);
  los 4 afectados se volvieron a comprobar tras el guard Android. Cross-build y
  `.so` AA64 con siete exports JNI verificados. El API de parcheo no cambia.
  SHA de la biblioteca: `2edcd3cf9cc80df58a80d103709e33400080d22f9b0229e90a79e7f4fcdf5bf4`.
- Ronda code 27, corrección CDN: **22 tests Python pasan**; comprobados roles de índices,
  CKey/EKey/URL, tamaños/hashes, fuentes completas, evidencia HTTP y presupuesto.
- En la ronda code 26, **832/832 objetos runtime** verificados con cascette y, en JVM/host, con
  `ForeverBlte`, el lector streaming usado por el APK. El fixture externo está
  ligado al SHA exacto de aquella ronda; los dos tests reales pasaron sin skips.
  Los objetos runtime no cambian con code 30; no se repiten descarga CDN,
  provisión ni lectura de los 832 objetos sin cambios.
- **35/35 rutas loose** devuelven HTTP 206 con rangos válidos de 64 bytes;
  el archivo que causó el 404 se descargó completo con HTTP 200 y MD5 válido.
- Ronda code 27: **9 tests JVM pasan sin skips**, cuatro regresiones CDN con catálogo y objeto
  reales, y cinco de provisión/caché. Cubren descarga directa y reutilización sin
  otro HTTP, rechazo de índices como payloads, identidad MD5, totales de rango y
  conservación del parcial tras un 404. Las pruebas JVM no acceden a la red.
- Receta desde fuente fijada: **59 inputs** coinciden con el árbol construido y
  la segunda aplicación es idempotente.
- `assembleModernDebug --offline --max-workers=1`: **BUILD SUCCESSFUL**.
- Auditoría final: versión/ABI/29 ELFs/firma, catálogo/contrato/JNI byte-identical,
  hashes de todas las fuentes de overlay y provenance actual; sin payload Blizzard.
  Comprueba también el WX preload dentro del APK, idéntico al de code 27.
- Revisión independiente del descargador y de memoria/concurrencia completada;
  readiness limitado al ensayo privado de un solo intento.

Se detectó además provenance antiguo en el merge incremental de assets aunque
las fuentes eran actuales. Se rehízo **solo** `mergeModernDebugAssets --rerun`
y el empaquetado; el audit final del APK confirma todos los hashes actuales.
La receta debe auditar el APK, no fiarse únicamente de `BUILD SUCCESSFUL`.

Pendiente: ejecución real de code 28, estabilidad del runtime PowerVR, suficiencia
CASC para WoW, `CertStore` AA64, render y login Forever. Code 27 pasó los gates de
preparación y dejó un spawn Wine; no demuestra que el cliente aceptara el bootstrap.
El emulador x86_64 no valida este runtime ARM64 y no se presenta como prueba del juego.

## Incidente code 24 y corrección code 25 (2026-10-01)

El usuario pulsó **Preparar y lanzar** y recibió inmediatamente:

```text
Error: runtime profile has no installed file manifest: Proton-proton-11.0-90624-arm64ec-1
runtime: resolving pinned Wine profile
```

Reproducido antes de cambiar el código, con `ContentsManager.readProfile`/`syncContents`
reales y los JSON extraídos de los WCP fijados. Proton declara `files: []` por
diseño. `ContentsManager.applyContent` solo utiliza esa lista para componentes
distintos de Wine/Proton; el verificador del launcher la interpretaba erróneamente
como un inventario obligatorio del árbol instalado. No prueba corrupción ni un
fallo de Wine/PowerVR.

Code 25 valida las piezas propias de Wine y mantiene el rechazo de DXVK sin lista,
archivos ausentes/vacíos, Wine no ejecutable, rutas que escapen del perfil y cambios
de la versión fijada. **6 regresiones + 2 tests de resolución Wine pasan**, sin
skips; el test de reutilización no reimporta ni modifica el perfil existente.
Build offline y audit del APK final pasan. Los 152 assets/bibliotecas coinciden con
code 24 salvo `provenance.json`; no faltan payloads runtime aunque el ZIP final sea
menor. Firma, JNI y componentes fijados no cambian.

El error observado ocurrió antes de crear un proceso Wine: por este error concreto
se puede actualizar y reintentar sin reiniciar Android. La política de reinicio
después de un spawn sigue vigente. No se borran perfiles ni datos. El audit y la
receta de code 24 se conservan como evidencia histórica. Los APKs automatizados
code 24–26 se retiraron en la limpieza aprobada del 2026-10-01; el binario actual
code 27, los informes y las herramientas quedaron intactos. Registro externo:
`delivery/artifact-cleanup-2026-10-01.jsonl`; alcance en
`.agents/planning/done/artifact-cleanup-plan.md`.

## Incidente code 25 y corrección code 26 (2026-10-01)

El usuario llegó a `client: downloading` y recibió:

```text
Error: BLTE decoded size mismatch: 154 != 181
bc0fe26fca9629c46c33c478077ecd4d (181 bytes)
```

El objeto oficial tiene 181 bytes codificados, SHA-256
`c4bdf701d1d21bbaaa675ddcaa2e48450ef2a3473c3574902cd361d79a132122`.
Su contenido descomprimido tiene 154 bytes y MD5/CKey
`001edd43c5b3baa939ebbff41d750e1a`. El generador trataba el tamaño VFS del
buildconfig, que es **codificado**, como si fuese descomprimido. No fue una descarga
truncada ni un fallo de Wine/GPU: el lector rechazó correctamente el catálogo erróneo.

Corregidas **828 de las 832** longitudes de metadatos, contrastando encoding,
buildconfig y decodificación completa con MD5. Todos los objetos son BLTE `0x0f`
con chunks N/Z compatibles con el lector Android. No se relaja ninguna comprobación.
Transferencia y bytes codificados no cambian; el catálogo ahora registra
560.614.688 bytes decodificados de runtime y recalcula el espacio conservador.

Catálogo externo corregido SHA-256:
`061d6cd103b984670ba476660109f4a5a79462da676daddf0d959bf9bbf3a7c0`.
`scripts/arm64-prototype/client/runtime_audit.py` genera el fixture cache-only
`runtime-audit-manifest.json` fuera del repositorio; no se empaquetan sus rutas ni
los objetos CDN en el APK. `ForeverBlteCatalogTest` verifica los 832 objetos con
el lector real, reproduce el rechazo 154/181 y comprueba que un CKey falso sigue
siendo rechazado. El objeto opcional `patch-index` queda como provenance de delta,
no parte del bootstrap de instalación limpia.

La actualización preserva el runtime instalado y los objetos de caché ya
verificados. Como el fallo ocurrió antes del spawn, se puede actualizar y
reintentar sin reiniciar por este error concreto. Sigue vigente el reinicio
obligatorio después de crear Wine. Todavía no se ha ejecutado WoW en esta ronda.

## Incidente code 26 y corrección code 27 (2026-10-01)

El usuario recibió `unexpected CDN HTTP status 404` al descargar
`e092b002f52c0827ce4b65d534d7316d` (3.932.016 bytes), correspondiente a
`Utils/discord_partner_sdk.dll`. La URL anterior usaba
`6589b9d0516fd85ed44ff57302a4cefe` como si fuese un archivo de datos. Ese hash es
el **file-index CDN**, cuyo footer declara `offset_bytes=0`: sus registros anuncian
objetos independientes y no contienen offsets de un archivo. El parser upstream
leía cuatro bytes de la siguiente clave como un offset y el generador confundía
el índice con el payload. La URL incorrecta se reprodujo con HTTP 404.

La ruta correcta es el objeto directo:
`https://us.cdn.blizzard.com/tpr/wow/data/e0/92/e092b002f52c0827ce4b65d534d7316d`.
Descarga completa: HTTP 200, 3.932.016 bytes, SHA-256
`a6fe23b7e54091551c823c90cd5d432cf6211202daa03cd4990ed93eb516f9a0`.
Contenido BLTE: 8.654.776 bytes, MD5/CKey
`d6c3f8e62bc38d8db9cb0cf1f3dee719`. Las 35 rutas se probaron con rangos de 64
bytes; cuerpo total recibido: **3.934.256 bytes**, sin descargar archivos completos
de CDN ni volver a leer el cliente vivo. La investigación quedó por debajo de 16 MiB.

El generador distingue `archive-index`/offset 4 de `file-index`/offset 0 y rechaza
grupos de ancho 6 no demostrados. El objeto independiente solo pasa al APK como
`direct` después de verificar su SHA/BLTE/MD5. Android exige una fuente/EKey y
tamaños coherentes, liga los archivos a índices de archivo fijados y rechaza el
file-index como payload directo o de rango. Un error HTTP exporta ahora tipo,
URL y rango; el lector comprueba también la identidad MD5 y el total HTTP.

Catálogo final SHA-256:
`3cee581d681a5439c138955edd3c39ff2c799689b3889a47cd41c6762e6ff1ce`.
Evidencia externa: `client-bootstrap/cdn-sources-0.2.3.json`, ligada al catálogo
final sin repetir solicitudes. No cambian el límite de descarga, el runtime, el
JNI ni los datos CASC. Se conserva la caché verificada. Este fallo anterior a Wine
permite actualizar y reintentar sin reinicio; tras un spawn sigue siendo necesario
reiniciar Android antes de otro intento. No demuestra todavía ejecución del juego.

## Ensayo code 27 y corrección code 28 (2026-10-01)

Informe conservado en `delivery/forever-RUN-1790883604709-786341652235022.zip`.
El usuario vio controles táctiles, pantalla negra y, tras minimizar y volver, la
pantalla **Preparar y lanzar** de Forever, no el escritorio Android. El journal
pasó preparación/runtime/cliente/configuración y arrancó el wrapper Wine PID
18319/starttime 101969434. Persistía `ACTIVE/RUNNING`, `gamePID=null` y
`DISCOVERY_NOT_FOUND`; el ZIP no contiene logs de juego seleccionados. Los controles
y mensajes SDL/evshim son evidencia del runtime, no de render de WoW.

El stderr contiene redirecciones de bibliotecas de Termux/Winlator hacia
`/data/data/app.gamenative/files/imagefs/usr/lib`, ajeno al package del launcher.
Inspección del ELF AA64 real `libredirect-bionic-wx.so` confirma el contrato:
`WINEMU_HOST_PKG`, después `HOST_PKG`, después el default XOR `app.gamenative`.
Ambas variables faltaban. Code 28 establece el package propio en el último merge
antes de `ProcessHelper.exec`, preservando SysV SHM, evshim y W^X, HODLL, display y
prefijo. La biblioteca **no se sustituyó ni recompiló**, SHA-256 en ambos APKs:
`8f50d8af131dbf1eb008b8a9e48aca110f7cbc02e787959771317dd5c5c56e26`.
Es un defecto de integración demostrado, no una prueba de la única causa del negro.

Ese mismo punto final impone solo `+timestamp,+pid,+process,+module,+seh`; GameNative
sobrescribía el `WINEDEBUG` anterior con `-all`. No hay relay/snoop ni captura de
credenciales solicitadas al usuario. Streams de 512 KiB y journal de 4 MiB siguen
acotados; el JSONL nunca se escribe parcialmente al agotar el margen.

La recuperación ya no muestra una corrida perdida como activa: deja estado
interrumpido, evidencia exportable y bloqueo de otro Wine si la terminación es
desconocida. Los eventos de la Activity y la identidad del host se registran; no
se promete reattach de Surface ni restauración física del juego al minimizar.
El `SIGTERM` histórico del informe antiguo no tenía PID/processName y no puede
atribuirse retrospectivamente a WoW o al launcher. Code 28 exige además un run
summary único para evitar atribuciones por reutilización del PID.

Como la corrida **sí creó Wine**, reiniciar Android antes de probar code 28.
Actualizar sin borrar datos y mantener la app en primer plano en este primer
ensayo. No cambiaron Wine/DXVK/driver, catálogo/CDN/CASC ni JNI, y no se relajan las
validaciones del proceso/almacén para obtener un falso parche aplicado.
