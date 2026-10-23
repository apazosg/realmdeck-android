# Descargador Android — punto de reanudación

**C127 instalada:** operación explícita `nativeApply(token)` implementada y
revisada, solo32 bytes de la clave pública del grupo8 en el hijo y bloque
revalidados.29 tests JVM,32 host y auditoría APK correctos. APK SHA256
`bcc67f3f05c581267f83ff3a1588eed7821be3c2f617fea75d5c7d59bcf18ff7`.
Siete hashes privados conservados; mismo boot y recuperación por la API existente
a `IDLE`. **Login confirmado por el usuario («funcionó!!!»)** después de verificar
la clave aplicada en el reintento con USB conectado. La prueba anterior aplicó32
bytes y54 ALREADY_PATCHED antes de perder el USB; no era un fallo probado del
parche. No interrumpir la sesión actual ni descargar/repair/reiniciar Android.
El usuario confirma después entrada al juego sin glitches gráficos observados;
estabilidad prolongada/FPS/audio/controles todavía sin validar.
La búsqueda/aplicación antes del primer Enter
ya está implementada en C128, **no incluida en la APK C127 instalada**. C128 tiene
32 pruebas JVM, revisión y auditoría APK correctas; artefacto listo, no instalado.
Estado y evidencia vigentes en
`docs/arm64-forever-connection-progress.md`.

## Corte operativo vigente —2026-10-21

- **El usuario está jugando.** No detener la app/Wine, instalar APK, reiniciar
  Android, mover el USB ni iniciar verify/repair/descarga. No hay que esperar a
  C117: terminó la publicación el20oct y el cliente70450 ya fue abierto por WoW.
- C128 `0.2.104-early-group8-apply` está en
  `/home/adrian/wowforever-artifacts/arm64-prototype/client128-early-group8-reviewed-20261021/`.
  SHA256 APK `6a4e842f84f174d4df293a72c4ed0a4935ac9a1da29d646efc15a6357fde8d59`;
  `apk-audit.json` PASS,32 tests JVM sin fallos y nativo C127 intacto. No repetir
  compilación/tests ya correctos salvo cambio o defecto concreto.
- Pendiente: confirmación de cierre del usuario; después quiescencia del UID,
  instalación conservando datos y recuperación **por la API existente** si hay
  reserva pendiente. No borrar registros ni dar por muerto todo Wine por no ver ventana.
- Próxima prueba: observar APPLIED/ALREADY antes de Enter, un primer Login manual
  y su resultado. Hasta entonces solo está probado el adelanto en tests, no en WoW.
- Último endpoint observado `192.168.1.130:42299`, paquete
  `com.wowforever.arm64probe`, UID10330. No tratar endpoint/PID como permanentes:
  comprobar acceso y propiedad antes de cualquier acción futura. No escanear
  puertos ni cambiar seguridad para recuperar acceso.
- Fuente y árbol de hitos: [estado consolidado](launcher-app-status.md). Los
  apartados siguientes son historia: sus «pendiente»/«bloqueado» no anulan C127.

## Historial C126: sin nueva descarga del cliente

C126 `0.2.102-variable-padding` incorpora la corrección del relleno del almacén
(byte37 variable;38–39 cero), pruebas/revisión y JNI candidato de procedencia
verificada. Auditoría APK aprobada; SHA256
`7ad48a455bd0ecc95dc5a1db0a0c406caa4c8236da080cedd4c266bbdf599725`.
Instalada sin reinicio Android ni reset,7 hashes privados conservados y reserva
recuperada mediante la API existente. No se ha habilitado escritura JNI ni
demostrado Login ARM64: la prueba manual volvió al formulario. Tres de87 polls
reconocen un candidato de12 entradas, sin cobertura global completa ni unicidad.
La operación de aplicación guardada fue aprobada y se implementa en C127.
No repetir
instalación/repair/descarga: el bloqueo pendiente sigue en compatibilidad Forever.

## Historial C123: Login autónomo probado, bloqueo de identidad grupo8

Endpoint130:42299, boot `5e1775b1-801b-4464-8e9b-ae5d4518d250`. Un único Login
con cuenta autorizada: autenticación y lista de reinos correctas; rechazo explícito
de identidad grupo8 a18:11:37.410 del21oct. Sin selección de personaje ni cambio
de clave. Se cerró normalmente WoW para vaciar logs, sin reiniciar el dispositivo.
Solo etiquetas/horas públicas extraídas; sin credenciales en artefactos.

App14420 sigue viva, sin crash Java observado; WoW y varios hijos quedan zombis
`Z` y queda el daemon propio de PulseAudio activo. Reserva `GUEST_STARTED/proc_exe_unreadable`:
**no lanzar otra vez, borrar reservas ni considerar esto quiescencia**. Revisar
limpieza real y sincronización del observador (expiró antes del Login). No reparar,
resetear, descargar el cliente ni reiniciar Android sin confirmación/reconexión.
Detalles vigentes: `docs/arm64-forever-connection-progress.md`.

### Historia: recuperación de acceso antes del intento

**Acceso recuperado posteriormente:** endpoint130:42299, boot
`5e1775b1-801b-4464-8e9b-ae5d4518d250`, USB montado y C123 confirmada. La recuperación
por API devolvió `IDLE/QUIESCENT`. Ningún Wine/Login en este boot todavía. El control
por JDB al preparar el opt-in falló y la UI muestra ANR: se abandona ese método y se
restaura solo la app en reposo. No otro reinicio del móvil ni borrado de registros.
Evidencia: `work/client123-resumed-20261021T125840Z/`. El bloque siguiente describe
la interrupción anterior, no el endpoint actual.

**Pruebas físicas bloqueadas:** el reinicio cortó ADB y el usuario está fuera de
casa, sin posibilidad de reactivarlo ahora. No reiniciar de nuevo sin confirmación
y reconexión disponible; no cambiar seguridad ni escanear puertos. No hay nuevos
intentos de login. C123 estaba instalada con7 hashes privados conservados antes
del reinicio; montaje USB y recuperación de guest posteriores aún no comprobados.

Plan ARM64/grupo8 aprobado, sin cambio de clave todavía. C123 corrige la presentación
desde el hilo de espera de Wine y conserva la reserva CASC de C122; compilación,
test focal y auditoría correctos. SHA256
`a00c2590b476cc9df1b1a1908e2f78f0b32b56b626df1f0c318afcc6e89c3263`.
ADB recuperado en130:37601: C123 instalada y7 hashes privados conservados.
Reinicio enviado para otro intento limpio; confirmar nuevo boot/endpoint tras
desbloqueo. Ningún nuevo Wine/Login ni cambios de seguridad. La recuperación
operativa no se ejecutó todavía (inicialización Kotlin/breakpoint con pantalla
bloqueada), no es un fallo demostrado de sus guardas.
Guest durable último observado `GUEST_STARTED/UNKNOWN` tras el crash: recuperar
solo mediante la API existente bajo quiescencia, nunca borrar el registro.

**Tras Exit Game:** logs ya volcados. Login y lista de reinos completados; rechazo
posterior explícito de identidad del servidor, **grupo8**, a22:54:55.382.
No es el error CASC ni un rechazo de contraseña. Adaptación ARM64 todavía sin
implementar; plan aprobado en `.agents/planning/done/arm64-server-identity-plan.md`.
La app sí crasheó al salir23:05:18 por vistas actualizadas desde un hilo Wine.
A23:08 no quedan app/Wine, mismo boot y ADB nuevo192.168.1.130:36351. No relanzar
sin revisar el registro durable de guest; no borrarlo ni saltar guardas.

### Historial anterior a los registros de cierre

El usuario informa de Login enviado: «login to server» y retorno a contraseña,
sin error ni selección de personaje. El motivo de autenticación no está aislado;
no confundir la corrección de índices con acceso Forever completado. A22:57 el
juego sigue activo, registros de conexión0B y portal derivado `auth.gpon.com.co`.
No repetir credenciales, modificar autenticación ni instalar C122 sobre el guest vivo.

**22:47 CEST:** el intento actual muestra cinemática con subtítulos, en vez de negro.
`gx.log`:70450 ARM64 y creación D3D11 correcta sobre Mali-G610 MC6. Aún no login,
escena3D interactiva ni mundo; no instalar ni relanzar mientras siga activo.

C122 `0.2.98-native-index-reserve` ya compila y pasa auditoría,8 tests JVM focales
y5 del auditor host. Reserva corregida desde el escritor y estimación de espacio,
también tras reanudar una finalización. APK SHA256
`dd2b530cd07f8e04f0968c9d95f5eed7d9f1bc114159e18d12dd43acce32e560`;
artefactos `arm64-prototype/client122-native-index-reserve-reviewed-v2-20261020/`.
No instalar mientras Wine siga activo en la prueba actual. A22:42 ya están presentes
los1504 nombres de índices CDN referenciados; a22:43 se observan lecturas nativas de
archivos data.NNN. Sigue pantalla negra, sin login ni render; diagnóstico posterior
a la caché CDN en curso. Tact nuevo aún vacío, no usarlo como prueba de éxito.

**20oct22:32 CEST:** prueba aprobada tras reiniciar y respaldar16 índices y auxiliares.
Se publicó únicamente48KiB vacíos adicionales por índice. El juego ya mapea los16
índices, abre89 data.NNN y prepara `Data/indices` (942/1424 índices CDN, ~95,5MiB).
Pantalla negra todavía, sin login; no confundir esta caché de índices con una nueva
descarga completa de WoW. No tocar cliente/auxiliares ni instalar APK mientras Wine
esté activo. La corrección permanente del escritor/estimación está en curso paraC122.
ADB actual192.168.1.130:46601; boot2b014ccb-6c94-4593-850f-f10c5dfb9fc1.
La reserva antigua GUEST_RESERVED se recuperó mediante la API segura existente;
la UI normal aún carece de una entrada visible para esa recuperación.

### Historial C121: primer lanzamiento

**Resultado posterior:** WoW abre un diálogo «installation is damaged».
Proceso de juego y Logs confirmados; `Tact.log` rechaza la sección actualizada KMT
y mapping table0 del índice `Data/data` (NeedsRepair). Diagnóstico de lectura en
curso; no ejecutar repair, reset, nueva descarga ni otro lanzamiento. No hay login.

**20oct21:43 CEST:** C121 compilada, auditada e instalada conservando siete hashes
privados. El panel de lanzamiento ya aparece. Jugar reutiliza ImageFS; un clic
en Lanzar WoW abre presentación negra y comienza la copia de DLLs del primer
prefijo Proton. Proceso WowB, render y login aún sin confirmar. No repetir clics
ni interrumpir la inicialización por asumir que la pantalla negra es un fallo.

### C120: preparación completada

**20oct21:31 CEST:** C120 instalada conservando preferencias y admisión anterior.
La migración local a identidad durable pasó y publicó admisión v2 sin releer CASC.
El primer Jugar descargó y preparó ImageFS automáticamente. No hubo nueva descarga
del cliente. La interfaz confirma «Entorno preparado», pero oculta Lanzar WoW por
exigir el estado de presencia READY aunque la preparación fuerte ya terminó.
Corrección de esa puerta de interfaz en curso para C121; todavía no instalada.
No repetir Jugar ni asumir que Wine o el login ya se han alcanzado.

## Corte operativo: C117 terminó; prueba de arranque autorizada

**20oct19:29 CEST:** finalización observada a19:22:28 (`foreground execution
finished id=1 cancelled=false`), sin FGS ni journal pendiente; admisión privada
de70450 ARM64/enUS publicada. Los36 archivos sueltos pasan CKey/MD5 desde el USB,
sin releer CASC. No hubo cancelación ni reset. Evidencia:
`work/client70450-first-launch-20261020/` y `work/c117-completion-readonly-20261020/`.

Usuario aprueba actualizar conservando datos para probar arranque y login. La
discrepancia de Inicio proviene de la validación legacy70009 y de exigir un WTF
que el descargador no incluye. Corrección revisada y desplegada como C118
(`0.2.94-remote-diagnostics`):82 tests JVM focales y auditoría APK correctos;
admisión y preferencias byte-idénticas tras `install -r`. Control retomado con
scrcpy5.0.1/UHID, sin cambiar permisos de seguridad. El gestor reconoce70450 y
ofrece Jugar; el primer intento falla en preparación (`error=Exception`) antes de
Wine. WTF contractual creada; login/render no alcanzados. Causa comprobada:
ImageFS (`home/`, `.img_version`) y contenedor Wine ausentes. Inicialización del
entorno existente autorizada, automática al primer Jugar: C119 compilada,
110 tests JVM y19 tests host focales correctos, revisión sin bloqueantes y
auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`. C119 instalada y actividad abierta:
6 ficheros privados de preferencias/admisión byte-idénticos tras `install -r`.
ImageFS se descarga desde la app (~175MiB), sin incluirlo en la APK;
coste comunicado y confirmado. Tras instalar C119 el usuario autoriza («adelante»)
probar Jugar, preparación automática y arranque. Prueba en curso; no repetir el clic.
No asumir descarga incompleta.
No repetir descarga, repair, reset ni drivers. Una pasada no está
implementada; la optimización de dos pasadas sigue sin medición física.

## Corte anterior: C117 en publicación — histórico

**Inspección solo lectura del2026-10-20:** instalada C117/`0.2.93-pinned-resume`,
PID11675 yservicio foreground activo. El journal está **ACTIVE**:108 de144
operaciones tienen `phase=2` (rename realizado),36 siguen enphase0 y
`.build.info` continúa enphase0. **No es75%deltiempo ni instalación terminada.**
Esta es una fotografía; comprobar estado real antesde cualquier actuación.

La incorporación CASC terminó a14:03:29 delmóvil:938.819objetos previos y
206.159nuevos (~90,12GB depayload encoded entotal). La finalizaciónposterior
relee ficheros; lamuestra previa aljournalobservó~35,6MiB/s yavance de
stagingdata.005→data.006. No inferir atasco por la UI «Aplicando» ni tiempo
restante por esatasa. Evidencia: `work/c117-finalization-readonly-20261020/`.

**Decisión de despliegue entendida por el usuario:** dejar terminar esta
publicación. `install -r` detendría el proceso; la nuevaAPK tendría que usar
recovery estricto deljournal actual, no la finalizaciónrápida, ypodría repetir
lecturas. No pausar/reiniciar/actualizar para intentar ahorrar esta espera.
No reset, repair, clear-data ni drivers deoperaciones anteriores.

La optimización de **ocho a dos lecturas bulk SHA-256** está implementada y
revisada **offline**, NOdesplegada ni medida en90GB. Solo sirve para futuras
operaciones frescas elegibles:admisión staged única+readback porarchivo publicado,
con`.build.info` físicamenteúltimo trasvalidartodoslospayloads. Sus pruebas
efímeras pertenecen a esa instancia/operación; no pueden fabricarse paraC117
ni restaurarse desde journal/CRC/mtime tras cierre. Updates yrecovery siguen
estrictos. Mantienehashesoficiales yfsync;75%menoslecturas no garantiza75%menos
tiempototal. [Plan aprobado](../.agents/planning/client-finalization-two-pass-plan.md).

## Cortes anteriores: C116 y restauración del contexto (históricos)

**Corte anterior2026-10-20: instalada C116/`0.2.92-app-branding`.** Actualización
de marca autorizada, no optimización del motor. Pausa cooperativa con938819objetos/
74581621578bytes encoded, cola parcial0 y objeto cifrado retenido; checkpoint,
recibo y preferencias byte-idénticos tras `install -r`. Arranque nativo correcto;
icono de la ficha Android en diagnóstico solo lectura. **Reanudación bloqueada
antes de costes:** `plan()` detecta una transacción pendiente; no se llamó a
`recover()` pese a la frase UI «Falló la recuperación…». FGS0/PID4811, tres hashes
durables intactos (`c116-resume-blocker.json`, `c116-recovery-logcat.txt`). No hubo
confirmación, reset ni repair. No ejecutar drivers, repetir confirmación, borrar
estado o invocar recuperación a ciegas: algunas rutas pueden resetear CASC.
Inspección solo lectura de journal/staging completada. El corte C115 siguiente es
histórico, no estado actual.

**Diagnóstico confirmado:** no hay journal/tmp ni backup; staging y owner coinciden
con el recibo. Rotó CDN-config con versión/build-config iguales y preparar metadatos
publicó un catálogo distinto, cuya clave no encuentra el workspace original.
Catálogo anterior cacheado; NO es autorización para borrar estado huérfano.
Restauración/conservación del contexto original aprobada expresamente en
`../.agents/planning/client-resume-catalog-pin-plan.md`, actualmente en ejecución.
Evidencia: `launcher-branding-20261020/forensics/findings.json` y comparación de claves.

Contexto original validado también por el parser estándar de C116, sin publicarlo:
catálogo/configs cacheados y digest oficial conservado dentro del SQLite original.
Los tres controles y la admisión activa seguían sin cambios antes del despliegue.
C117 ya pasa seis tests JVM y auditoría canónica; readmisión/reanudación autorizadas
en curso. Consultar sus nuevos informes antes de actuar: aún no hay un resultado
físico de esta operación registrado aquí. La reparación no autoriza reset,
migración de checkpoint ni repetir los drivers anteriores. Evidencia adicional:
`work/client-resume-pin-20261020/original-context-validation.json` y
`standard-app-validator.log`.

Candidata C117/`0.2.93-pinned-resume`, SHA256
`085bf2633124068501720846aa022e020a95f2acd25356e4d105c72787efc1c6`,
2196fuentes/178payloads. Artefactos/procedimientos de **esta operación** en
`work/client-resume-pin-20261020/`; no ejecutar los drivers anteriores. Los límites
siguen siendo31,6GiB/33,5GiB, misma70450ARM64/enUS y USB6AD6-EF0E.

**Corte: 2026-10-19, C115/geometría de Ajustes.** El trabajo de optimización del
descargador sigue aparcado. El usuario autorizó otra actualización conservando
datos y reanudación para revisar los iconos; esa operación ya terminó. Después
señaló desalineación/espaciado irregular y autorizó las iteraciones visuales
necesarias, con pausas controladas, preservación y reanudación al terminar.
**C115 instalada y descarga reanudada; interfaz aprobada**. Pausa cooperativa sin kill;
719.879objetos/57.334.811.304bytes encoded preservados, checkpoint/recibo/preferencias
byte-idénticos. `c115-installed-preserved.json` confirma115; XML demuestra cinco
botones iguales y12dp entre ellos. Captura C115 aprobada por el usuario:
«si, mucho mejor». Reanudación probada con nuevos commits; al cerrar se dejó
Inicio visible y la transferencia en ejecución, sin otra pausa.
No detener, reinstalar ni volver a confirmar la descarga al retomar sin comprobar
primero su estado y la operación en curso. Los datos C114 están marcados como
históricos; no confundirlos con la ejecución C115.

## Estado instalado: C115

- **C115 / `0.2.91-settings-grid`**, paquete `com.wowforever.arm64probe`.
- Cliente admitido: **Windows ARM64/enUS, `1.60.1.70450`**; destino USB
  **`6AD6-EF0E`**. No implica compatibilidad Forever/login acreditada.
- APK compilada y auditada: `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,
  2.187 fuentes y 178 payloads. SHA-256:
  `2a9eddedc57d57bbefe75b98409a3c6f5539fa5b9744812460c46903b3b2317c`.
- Consentimiento C115: **31,6GiB restantes/33,5GiB adicionales**, por debajo de
  los32,5/34,4GiB anteriores; mismos70450/USB. Confirmado una sola vez.
- Muestra **2026-10-19,22:01:12UTC**: PID20462, foreground y16fetch workers;
  **720.047objetos/57.348.415.110bytes encoded**, cola parcial0 y objeto cifrado
  retenido. Son168objetos nuevos respecto a la pausa, no solo prefetch ni prueba
  de instalación/integridad final. No es un contador permanente.
- `Ver descarga`→gestor→`Volver` conserva foreground. Captura final en Inicio
  muestra la fase de descarga y Pausar, sin Preparar/Lanzar ni tarjeta GPU.

### Corte completo anterior: C114 (histórico)

- Al reconectar, C113 ya mostraba descarga fallida, sin FGS ni fetch workers;
  causa no determinada, no atribuirla al cambio de red sin evidencia. No hizo
  falta pulsar Pausar. Se conservaron **708.312objetos/56.408.614.061bytes encoded**,
  checkpoint/recibo/preferencias byte-idénticos al instalar C114 con `install -r`,
  sin clear-data y objeto cifrado de referencia retenido.
- Última muestra retenida: **21:13:48 UTC / 23:13:48 del dispositivo**,
  PID20461, servicio foreground y 16 fetch workers; **708.404 objetos /
  56.415.189.328 bytes encoded**, checkpoint sin cola parcial. Hay92commits nuevos
  tras reanudar, no solo prefetch. Es una fotografía,
  **no un contador permanente ni una prueba de instalación completa**.
- Pantalla de consentimiento C114: **32,5 GiB restantes / 34,4 GiB adicionales**.
  Mismos70450/USB, sin aumentar los34,0/35,9GiB de C113 ni los límites originales
  de85,0GiB de descarga/86,1GiB adicionales autorizados. Confirmación una sola vez.
  Si cambia build, destino o coste autorizado, parar y consultar.

## Resultado de las mejoras

Las mediciones siguientes son históricas C110–C112. C113 cambia la UX y C114 sus
iconos, no el motor; no abren otra medición de rendimiento. Inicio muestra fase/progreso,
Pausar y Ver descarga; salir del gestor con Volver conserva el foreground.

| Medida real | C110 | C111 | C112 al corte |
|---|---:|---:|---:|
| Preparar → primera solicitud nueva | ~75 min12 s | ~16 min40 s | **~12 min42 s** |
| Confirmar → primera solicitud nueva | ~67 min05 s | ~8 min35 s | **~6 min51 s** |
| Dos scans de planificación | — | 224,584 +225,166 s | **94,181 +100,709 s** |
| Writer → primera solicitud nueva | 61 min35 s | 2 min45 s | **3 min02 s** |
| Verificación completa del prefijo | 468.177 objetos,61 min35 s de writer | 562.509 objetos,29 min18 s del verificador | **Pendiente en la captura al corte** |

Son prefijos, condiciones y relojes de fase distintos; no es un A/B controlado.
La reducción del scan de planificación C112 ronda el57%; el inicio completo
baja unos4min frente aC111. No atribuir todo el tiempo de inicio al catálogo ni
sumar tiempos de workers concurrentes como si fueran walltime.

**Velocidad aún no resuelta.** C111 midió0,60748MiB/s foreground y0,58017 fondo
durante solape;0,73111MiB/s en10min después de verificar. **C112 terminó su ventana
10+2 sin errores:**0,29939MiB/s foreground(593,387s/2.463objetos nuevos) y
0,36717MiB/s fondo(118,197s/675objetos). Informe
`EARLY_DOWNLOAD_AND_SUSTAINED_COMMITS_CONFIRMED`,objetoEretenido; el verificador
del prefijo aún no figuraba completo en el informe. No es instalación terminada.
Hay otras descargas x86_64 en la misma red, confirmadas por el usuario: marcar
estas tasas como **red compartida**, no prueba causal de regresión de C112.
No están acreditados2,1MiB/s sostenidos ni menor tiempo total hasta cliente listo.

## Qué hace cada versión relevante

- **C109:** revalidación USB dirigida. Consulta fresca de UUID/ruta/estado del
  volumen admitido, sin reconstruir todos los destinos por objeto; conserva los
  controles físicos. Primario/legacy y casos no elegibles mantienen la ruta previa.
- **C110:** parser BLTE compartido File/rango y lector Android `Os` de un pase,
  sin temporal por objeto. Gate físico dentro de la app con scratch propio;
  solo un PASS permite usarlo en esa invocación. No prueba atomicidad ABA ni cortes
  eléctricos. Mejoró replay, pero no alcanzó la reducción del50% buscada.
- **C111:** verificador secuencial por segmentos y writer de objetos nuevos en
  **otros archivos de segmento**, simultáneos. El prefijo sigue pendiente hasta
  su validación. La barrera conjunta impide FINISH/índices/publicación/activación
  antes de que todo pase. Formato checkpoint compatible, mismo orden durable.
  Se probó progreso sostenido:35 commits nuevos con V bloqueado y34 raws viejos
  ocupando el cupo; no solo prefetch.32 tests dirigidos,0 fallos/errores/skips.
- **C112:** solo planificación sin fuente: cursor EKey estrictamente ordenado,
  comprobado por fila; duplicado/prefijo de9bytes por adyacencia, sin tabla hash
  temporal `target-records.bin`. Packing/costes exactos compartidos con el camino
  genérico. Fuente existente conserva su ruta.8 tests focales,0 fallos/errores/
  skips; revisión independiente aprobada. No se retira el replan fresco.
- **C113:** Inicio contextual y perfil Classic Forever fijado por compilación;
  diagnóstico gráfico en Ajustes y consentimiento de lanzamiento único. Solo se
  añade detalle UI al hub existente; no cambian hashes/checkpoint/journal ni motor.
- **C114:** seis vectores originales pequeños en Inicio/Ajustes; conserva etiquetas,
  navegación y toda la lógica C113. Capturas reales inspeccionadas sin recortes;
  texto largo de resolución envuelve a dos líneas sin perder legibilidad.

Archivos principales, bajo `scripts/arm64-prototype/app/`:

- `updater-gate/ForeverCasc.kt`: planner ordenado/genérico, checkpoint,
  freeze, grupos, barrera y writer.
- `updater-gate/ForeverClientUpdater.kt`: costes, consentimiento, prefetch,
  presupuesto y coordinación; `ForeverClientUpdateService.kt`: admisión/gate.
- `updater-gate/ForeverBlte.kt`: parser único y verificación EKey/BLTE por rango.
- `overlay/ForeverCascReplayReaderAndroid.kt` y `ForeverCascReplayPreflight.kt`:
  identidad path/FD, tamaños sellados, sonda acotada dentro del namespace de app.
- `reconstruct_functional_source.py` y `audit_functional_ui_apk.py`:
  versión115, postimágenes autenticadas y allowlist DEX exacta. **No modificar**
  `code59-source-baseline.json` ni `apply_overlay.py` para admitir cambios.

## Retomar sin perder otra hora

1. Leer este corte y los informes externos **antes de ejecutar drivers**.
2. Reconectar ADB si hace falta: último endpoint `192.168.1.130:35279`,
   binario `/home/adrian/android-sdk/platform-tools/adb`. Comprobar versión,
   PID, servicio y logcat; no confundir desconexión ADB con fallo de la app.
3. **No relanzar** los drivers de instalación/preparación/confirmación C115 ni
   C114/C113/C112: sus marcadores de intento único y capturas son evidencia, no
   una invitación a repetir. La instalación115 está confirmada; consultar sus
    informes de reanudación y el estado real antes de actuar. Última ejecución
    observada: C115/PID20462; PID20461 es histórico C114.
4. Consultar `c115-resumed-first-new-commit.json` y observar el proceso actual.
   Para comparar las mejoras anteriores, consultar `c112-physical-result.json`
   y `c112-live-phases.log`. Un nuevo commit no certifica verificación completa
   del prefijo ni instalación terminada.
5. Si hay error, preservar todo y diagnosticar logcat antes de reintentar.
   Ante pérdida de captura, recuperar historial por PID y restaurar **solo la
   observación**, nunca reiniciar el proceso por tener un log antiguo.
6. Cuando la red quede sin competencia, medir otra ventana en la misma ejecución,
   sin reinstalar ni repetir replay. Las lecturas grandes de checkpoint requieren
   timeout180s; un timeout20s de ADB ya interrumpió una actualización antes de
   instalar, sin pérdida de datos.

## Evidencia externa y estado recuperable

Base: `/home/adrian/wowforever-artifacts/` (no versionar APKs/cliente/logs grandes).

- `arm64-prototype/client-manager-code115-settings-grid/`: fuente final,
   `reconstruction-receipt.json`, `gradle.log`, `apk-audit.json` y APK instalada.
- `work/launcher-ux-visual-20261019/`: `c115-installed-preserved.json`, bytes
  before/after y capturas `c115-*.png`; `c115-settings-geometry.json` registra la
  medida física de botones y huecos. Reanudación: `c115-cost-screen.json`,
  `c115-resume-confirmation.json`, `c115-resumed-first-new-commit.json` y
  `c115-home-returned-without-pausing.png`. No repetir scripts de operación.
- `arm64-prototype/client-manager-code114-original-icons/`: fuente y APK
  históricas C114, SHA`f474d4968977e51bca5473fe0ed488120ca416a360a23e00e04909399248979b`.
- `work/launcher-ux-visual-20261019/`: informes históricos C114
  `c114-installed-preserved.json`, `c114-cost-screen.json`,
  `c114-resume-confirmation.json`, `c114-resumed-first-new-commit.json`;
  bytes before/after y capturas `c114-*.png`. No repetir scripts de operación.
- `arm64-prototype/client-manager-code113-contextual-home/`: fuente y APK históricas
  C113, SHA`a0d0609f8e523de03a29355dc24df7e6608d0b3ad2c1a63a8d396f508898aa9c`.
- `work/launcher-ux-visual-20261019/`: `c113-installed-preserved.json`,
  `c113-visual-iteration-preserved.json`, capturas de checkpoint/recibo/preferencias,
  `c113-cost-screen.json`, `c113-resume-confirmation.json`,
  `c113-resumed-first-new-commit.json`, capturas reales `after-*.png` y revisión
  `visual-review.md`. No repetir sus scripts de operación.
- `arm64-prototype/client-manager-code112-ordered-planning/`: fuente retenida,
  `reconstruction-receipt.json`, `gradle.log`, `apk-audit.json` y APK.
- `work/c112-ordered-planning-20261019/`: `c112-installed-preserved.json`,
  capturas before/after de checkpoint/receipt, `c112-cost-screen.json`,
  `c112-download-confirmation.json`, `c112-startup-partial.json`, logs y métricas.
  Resultados10+2: `c112-physical-result.json`, `c112-comparison.json`,
  `c112-foreground-throughput.json`, `c112-background-throughput.json`.
- `work/c112-ordered-plan-tests/`: log final y XML6Casc+2Updater;
  `parent-final-source-check.json` confirma fuentes finales/stage/review.
- `work/c111-overlapped-resume-20261019/`: comparaciones/startup yresultado
  posterior al prefijo, referencia PC activa; `work/c111-overlap-tests/logs/`:
  evidencia32 tests. No usar el progreso histórico PC del04oct como actual.
- Workspace privado estable de app:
  `no_backup/forever-client-update-work/.forever-client-update-work/0c337db837ad1fcbf521e2585d17416ca9dff2fe67e7143999adf55f33f8a1a1/`.
  Críticos: `.forever-casc-resume-checkpoint`, `.transaction-stage-receipt.json`,
  `objects/`. El staging CASC está bajo el root USB, no es cliente publicado.

## Límites que siguen abiertos

- Completar descarga70450, verificación final, índices/activación, reconocimiento
  de instalación gestionada y arranque/login. **No declarar descargador terminado**.
- Actualización física entre dos instalaciones completas y reparación/reapply
  end-to-end aún pendientes. Los tests no sustituyen esa aceptación.
- Corrupción del prefijo en modo solapado aborta preservando stage viejo+nuevo;
  reparación local de stagedCASC **no implementada**. No usar reset/rollback como
  si reparase un bloque: puede descartar staging. No reparación in-place autorizada.
- Pausa viva no implementada: Pausar sigue cerrando la operación; reanudar vuelve
  a preparar/recuperar, ahora con solape. No prometer continuidad tras muerte/reboot.
- Bug Transaction no-op previo, fuera de este alcance: reaplicar bytes idénticos
  puede fallar con `published replacement has no verified original backup` y
  recovery `published replacement has an unexpected/missing backup`.
- Sin pruebas de corte eléctrico físico ni hot-unplug destructivo. Preflight,
  fixtures y actualización de APK conservando estado no equivalen a esas pruebas.
- Mantener16 workers, cola30,34 claves old+new y1GiB de raw/reservas en disco
  (no RAM). No liberar cupo antes del unlink+dirforce. Hashes, almacenamiento,
  consentimiento y exclusión con Wine siguen obligatorios.
- No borrar CASC incorporado/checkpoints/archivos ajenos; no tocar70009/WTF,
  no root/all-files/TLS bypass. Sin commits ni pushes de esta sesión.

Planes: [preparación ordenada](../.agents/planning/done/catalog-preparation-ordered-scan-plan.md),
[reanudación solapada](../.agents/planning/fast-resume-redesign-plan.md),
[objetivo global del launcher](../.agents/planning/launcher-functional-completion-plan.md).
La pausa es del **trabajo de mejora**, no una orden de detener la app.
