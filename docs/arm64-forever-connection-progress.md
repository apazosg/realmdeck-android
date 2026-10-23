# Compatibilidad ARM64/Forever — registro de avance

Modo de trabajo autónomo autorizado2026-10-15: cambios reversibles, hipótesis
verificables y cuenta de test; sin modificar Btrfs, seguridad permanente o cuentas
reales. Login y entrada al juego confirmados por el usuario en C127, sin glitches
gráficos observados dentro del juego. Estabilidad prolongada y rendimiento pendientes.

**Prioridad vigente:** entregar el parche adelantado C128 sin interrumpir la
partida actual y validar estabilidad/gameplay prolongado. El usuario ya confirmó
acceso al juego; no se limita la evidencia actual a una pantalla de Login.
[Estado consolidado](launcher-app-status.md) y
[plan aprobado C128](../.agents/planning/early-group8-apply-plan.md).
D3/D4 y sus hipótesis se conservan como historia; no reabrirlas por defecto.

## 21oct — C127: Login confirmado tras aplicar la clave del grupo8

**Validación posterior del usuario:** «he accedido y he confirmado que funciona y
no hay glitches gráficos tampoco dentro del juego». Confirma acceso al juego y
ausencia de glitches observados en esa prueba; no aporta duración ni mediciones
de FPS, audio o controles. No equivale aún a validar una sesión de30–60min.

En el reintento con USB conectado, el lector/aplicador registra una aplicación
y mantenimiento `ALREADY_PATCHED`. La comprobación inmediatamente anterior a
pedir otro Login confirma el mismo WoW23794 vivo (startTicks5351664), USB6AD6-EF0E
montado como `public:8,49` y la clave ya aplicada. Tras pedir un único Login, el
usuario responde **«funcionó!!!»**. Resultado: Login positivo comunicado por el
usuario; esa primera confirmación no acreditaba todavía entrada al mundo. La
validación posterior anterior amplía la evidencia, sin demostrar estabilidad prolongada.
Evidencia: `work/client127-usb-retry-20261021/first-application-observation.json`,
log `run-1792615126263.jsonl` y confirmación del usuario. No se capturan cuentas
ni se interrumpe la sesión para obtener más registros.

La solución queda en la fuente y APK C127, no en una DLL externa ni en un parche
del ejecutable instalado. C127 aún espera el primer Enter. **C128 está lista,
no instalada:** inicia el escritor inmediatamente, conserva el máximo total900s
y el primer Enter solo limita el resto a180s; observador/captura sin cambios.
No basta con adelantar el escaneo para demostrar que el bloque exista antes del
Login: esa comprobación física está pendiente, sin automatizar credenciales.

C128:32 tests JVM sin fallos/errores/omitidos, dos comprobaciones host focales,
revisión de concurrencia cerrada, build4m44s y `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`.
No nuevas excepciones de payload/DEX; nativo C127 sin recompilar ni cambiar.
APK484443715B, SHA256 `6a4e842f84f174d4df293a72c4ed0a4935ac9a1da29d646efc15a6357fde8d59`.
Receipt, XML y auditoría en `arm64-prototype/client128-early-group8-reviewed-20261021/`
bajo la raíz externa de artefactos. No instalar durante la sesión actual.

### Historial C127: primera aplicación y prueba interrumpida por USB

C127 incorpora `nativeApply(token)` con prueba fresca del bloque/proceso/mapeo,
solo32 bytes del grupo8 y relectura completa. Revisión nativa/Kotlin cerrada,
29 tests JVM/32 host y auditoría APK correctos. Instalada sin reboot,7 hashes
privados preservados; API existente recupera reserva a `IDLE`.

Prueba real: primer Login vuelve al formulario **antes** de aplicar. Tras40 polls
sin candidato, el siguiente valida un bloque; `patch_apply` informa **APPLIED,
32 bytes**, a1792612886776.54 relecturas posteriores informan ALREADY_PATCHED,
cero escrituras adicionales; después DENIED/identity con0 bytes y ENDED. Total
escrito32 bytes. No se atribuye el DENIED final a una causa no demostrada ni se
confunde con un fallo de readback. El cierre del observador no restaura la clave.
Se solicita un único reintento manual, sin cerrar/reabrir WoW; acceso al reino y
personajes aún pendientes de confirmar. Evidencia
`work/client127-guarded-apply-20261021/{manual-first-apply-observation,applied-and-maintained-snapshot}.json`.

**Cierre posterior explicado por desconexión USB:** el usuario confirma que no
pulsó Salir/Atrás. Android registra `public:8,49 destroy` a22:02:24.674 CEST,
eject a22:02:24.904 y remonta el mismo UUID6AD6-EF0E como `public:8,65` a22:02:29.
La última relectura ALREADY_PATCHED fue22:02:24.418; DENIED/identity22:02:25.540;
el launcher recibe `guest_terminated` a22:02:25.979 y `wine_exit`22:02:26.797.
El WoW real y el wrapper quedaron Z con wait status0; el app no murió. La UI
descarta `prepared` al salir, por eso vuelve a Preparar. La notificación al agente
del APPLIED llegó después de ese cierre: no hubo un segundo Login confirmado.
No se atribuye el cierre al temporizador/parche, ni se conoce si el corte físico
fue cable, hub, alimentación o controlador. Logs Connection/General permanecen0B
tras perder el volumen, no prueban nada sobre una autenticación posterior.
Evidencia: `usb-exit-correlated-public-events.txt`,
`launcher-return-public-logcat-events.txt`, `closed-process-wait-statuses.json`.
Reintento solicitado por el usuario con USB estable, mismo APK y sin reboot.

## Historial C126 — candidato estricto ARM64 sin aplicación

Prueba manual posterior:87 polls, cero bytes escritos. Tres polls contienen
`validatedStoreCount=1`: bloque que cumple IDs1–12, todas las claves/flags y
relleno válido. La exploración global quedó incompleta por huecos/cambios de
mapas; no demuestra unicidad, ausencia en otras zonas ni el valor concreto del
byte37. Login vuelve al formulario según el usuario. El puente instalado sigue
sololectura. El usuario autoriza «aplica el fix para que funcione el login»:
la operación explícita `nativeApply(token)` está en implementación conforme al
[plan](../.agents/planning/done/arm64-server-identity-plan.md). No se ha instalado ni
aplicado aún: solo32 bytes de la clave pública del grupo8, revalidación completa
del bloque/proceso/mapeo, escritura privada RW/RWX y comprobación de resultado.
La exploración seguirá sin escribir; no se desactivan comprobaciones TLS ni
se modifica el ejecutable. Captura opt-in permanece ajena a la aplicación.

## Historial21oct — relleno variable tras referencia de wowserver

La prueba manual de C125 volvió al formulario tras Login, según el usuario.
Se recogieron92 polls y el observador terminó; ningún almacén validado, con
huecos de lectura y cambios de mapas. No son búsquedas completas sin resultado.
`Connection.log` seguía vacío al leerlo, por lo que aún no se atribuye un nuevo
error semántico concreto a este intento. Evidencia externa:
`work/client125-manual-retry-20261021/native-manual-login-events.json`.

El usuario aporta `wowserver/docs/INCIDENTE-70450.md`, sección5: en x86_64 el
almacén correcto tenía `070000` en las12 entradas. La corrección allí conserva
IDs/claves/flags y exige únicamente cero en los bytes38–39, ignorando el37;
después se confirmó Login en ambos jugadores. Se contrastó con su código.
Nuestro núcleo y el lector completo todavía comparan el triplete con una lista
que solo contiene `7f0000`/`000000`: rechazarían esa variante.

**Instalación anterior C126:** `0.2.102-variable-padding`, APK SHA256
`7ad48a455bd0ecc95dc5a1db0a0c406caa4c8236da080cedd4c266bbdf599725`.
Regla compartida del relleno en núcleo/validadores/diagnósticos; regresiones y
revisión correctas, nueva biblioteca con23 hashes fuente estables y auditoría APK
aprobada.32 pruebas host de receta/auditoría correctas; se conservan los pins
históricos y solo cambia el JNI candidato. Instalación sin reiniciar ni limpiar
datos,7 hashes privados idénticos; recuperación existente deja la reserva IDLE.
Preparación en curso, sin nuevo lanzamiento todavía. No se ha demostrado aún el
relleno real ARM64 ni se habilitan escrituras JNI; esta discrepancia no explica
por sí sola los huecos de lectura ni garantiza Login. El log de C125 ya cerrado
sí confirma un nuevo fallo de identidad grupo8; solo se extrajo su contador.

## Historial21oct — ampliación diagnóstica aprobada

Referencia aportada por el usuario: `legustoss/Classic-Forever-Android`, cuyo
`version.c` usa exactamente nuestro contrato de claves, pero busca desde una DLL
dentro del proceso con `VirtualQuery`. Declara70425, no acredita70450/Mali.
No se ha instalado su DLL ni copiado código. Diferencia verificada: incluye RWX;
nuestros filtros anteriores excluyen esas regiones. No es aún la causa demostrada.
El usuario aprueba ahora recorrer **todas** las regiones legibles del WoW verificado,
con progreso entre tandas y cobertura explícita, sin volcados ni escrituras.
Implementación nativa revisada y compilada; [plan y límites](../.agents/planning/done/arm64-server-identity-plan.md).

**C125 instalada:** `0.2.101-full-readable-observation`, APK SHA256
`0f2829af8ca4deb9f082d25dfe20d4a86b3ef0f0c587eacdd3e49dd2f805843a`.
Auditoría de fuentes/payloads aprobada; el nuevo JNI tiene procedencia propia
verificada, sin cambiar runtime/driver ni afirmar paridad con el JNI histórico.
Pruebas del bucle nativo y reanudación aprobadas; no es todavía una solución de clave.
El usuario autorizó sustituir el reinicio para esta prueba: detener solo el paquete,
verificar UID10330 sin procesos y recuperar mediante la API existente. Las tres
operaciones terminaron correctamente; reserva IDLE, mismo boot y siete hashes
privados iguales tras instalar. Preparación/lanzamiento en curso, sin nuevo Login
confirmado aún. Evidencia externa: `work/client125-full-readable-20261021/`.

C124 mejora únicamente la temporización del observador:22 tests LauncherUx y
ensamblado correctos; auditoría APK aprobada (SHA256
`3dd0c794d695ebec6cb4459706b4dadf2883396081eb2ec19e4995978109a931`).
No instalada; mantiene el nativo anterior. No resuelve por sí sola la clave.

## 21oct — C123: Login autónomo ejecutado; rechazo confirmado de identidad grupo8

Un único Enter de Login mediante UHID, tras verificar el mapeo público y campos
vacíos. Valores de cuenta entregados solo por canal efímero RAM; sin archivos de
credenciales, portapapeles ni capturas persistidas de campos reales. El controlador
confirma `credentialsReceived=true`, `loginEnterAttempted=true`, `loginSubmitted=true`.
Después reaparece el formulario con contraseña vacía, sin selección de personaje.

Tras cerrar normalmente la ventana mediante un único Alt+F4 guardado, los logs se
vacían. Se extraen en el dispositivo **solo etiquetas públicas y horas**, no líneas
completas:18:11:29.602 formulario aceptado;18:11:30.105 login completado;
18:11:30.592 lista de reinos;18:11:31.073 reino conectado;
**18:11:37.410 identidad del servidor rechazada, grupo8**. El1016 es posterior.
La cuenta autentica: el bloqueo sigue siendo la clave/representación ARM64, no
la contraseña ni CASC. No hubo otra pulsación de Login ni reinicio.

El observador de180s ya había terminado antes de este Login: no se atribuye
cobertura de memoria a esa fase. No se cambió ninguna clave. La comprobación
offline de coordenadas Ed25519 públicas en varias representaciones de limbs tampoco
encontró un objetivo estático; no habilita un parche ni amplía lecturas del proceso.

C123 permanece viva tras salir y no hay excepción en el crash buffer de su PID.
No equivale a limpieza completa: el juego y varios descendientes son zombis `Z`
con padre14420; queda un daemon del propio `libpulseaudio.so` activo (wrapper
`linker64`, grupo/sesión24875). No es evidencia de Proot todavía ejecutándose.
La reserva sigue
`GUEST_STARTED`, motivo `proc_exe_unreadable`; no se elimina ni se declara quiescencia.
Evidencia externa en `work/client123-resumed-20261021T125840Z/`:
`post-single-submit-status.json`, `single-submit-public-connection-events.txt`,
`normal-close-kernel-states.json` y `closed-main-thread-crash-summary.json`.

### Historia del mismo boot: acceso y observación preautenticación

ADB recuperado por130:42299; boot `5e1775b1-801b-4464-8e9b-ae5d4518d250`.
La API existente recuperó el guest anterior bajo quiescencia y dejó `IDLE`.
Con opt-in del observador existente y captura de código desactivada se realizó un
único lanzamiento a13:29:22Z. Tras un Escape se muestra el fondo de login; todavía
no se escribió texto ni se envió la cuenta autorizada. El perfil de entrada70009
rechaza la geometría actual y se adapta de forma focal antes de introducir datos.

Las lecturas preautenticación no validaron ningún almacén; algunas fueron
incompletas y la comparación de imagen sigue ligada al tamaño70009. No permiten
afirmar ausencia global ni describen la fase auth→reino. Las dos secuencias
estáticas12×40 de70450 vuelven a tener doce relocaciones DIR64 en `+32`: el byte
`+36` pertenece a un puntero, igual que el falso candidato70009 ya documentado.
No prueban claves diferentes ni un objetivo de parcheo. Evidencia externa en
`work/client123-resumed-20261021T125840Z/` y
`work/arm64-server-identity-20261020/offline-structural-candidates-20261021.json`.
Sin escritura de memoria, nuevo escaneo ampliado, captura de código ni reinicio.

## 20oct — Causa directa en70450 ARM64: identidad del servidor, grupo8

El usuario envía Login desde C121 tras la corrección experimental de reserva KMT.
Al cerrar el juego se vuelcan registros antes vacíos: `Connection.log` acredita
login completado y lista de reinos22:54:48, conexión al reino22:54:49 y
`Protocol error: server identity check failed regionGroup="8"` a22:54:55.382.
Luego se desconecta. Ahora hay causa semántica directa, no solo tráfico TLS ni
retorno visual al formulario. No es selección de personaje ni entrada al mundo.

Se conservaron únicamente extractos filtrados externos en
`work/native-kmt-reserve-20261020/Connection.log.closed-redacted.txt`.
No se leyeron credenciales ni repitió Login por el agente. El log no prueba el
layout12×40 de x86_64 ni autoriza aplicarlo sin validación a ARM64.
[Adaptación aprobada](../.agents/planning/done/arm64-server-identity-plan.md), en investigación;
capturador, escrituras de memoria y bypass TLS siguen sin activarse. El script
público actual del CDN confirma la misma clave del contrato (`NEW_KEY`, grupo8),
SHA256 `7b773fb16928ac998532271bdeee7c835d452e095caad813e8c994612a5e07c5`.
Se inspeccionó como texto/AST, sin ejecutarlo; no demuestra un objeto ARM64.

La salida23:05:18 también produjo un crash Java independiente del rechazo:
`CalledFromWrongThreadException` al actualizar presentación desde
`gamenative-process-wait`. Boot intacto y sin app/Wine a23:08; no se ha relanzado.
La corrección independiente C123 despacha la presentación al hilo principal;
regresión focal, compilación y auditoría aprobadas. No adapta todavía la clave;
instalada el21oct por130:37601, con7 hashes privados conservados. Comprobación
física pendiente; reinicio enviado y esperando confirmar nuevo boot/desbloqueo.
El registro
durable sigue `GUEST_STARTED/UNKNOWN`: debe recuperarse mediante la API segura
existente bajo quiescencia, nunca borrarse para eludir el bloqueo.

## D3 — Corregir D2 y atribuir sockets (2026-10-15)

- **Cambio:** observador armado antes de lanzar, identificación Wine+comm+UID4 y
  marcador de entorno propio. Solo llamadas de control de sockets, sin E/S genérica.
  Capturador/escánerOFF. Attach al WoW30060 a05:28:02UTC, aproximadamente7s tras
  Enter de lanzamiento05:27:55.201, no113s como D2.
- **Incidencia:** strace imprime señales aun filtrando syscalls. El primer archivo
  alcanzó el cap principalmente por SIGILL/SIGUSR2; no prueba fallo de auth. Se
  corrigió únicamente el registro con `-e signal=none`; el cliente llegó al Login.
- **Ensayo:** un Login test enviado05:33:01.764UTC con guardas existentes. Sin
  escritura de memoria. Segunda preparación de entrada falló antes de escribir;
  no cuenta como segundo Login. No se repitieron intentos idénticos.
- **Hallazgo de método:** Wine11 delega connect TCP al wineserver mediante AFD.
  Observar solo connect(AF_INET) en WoW era insuficiente; DNS sí se vio en WoW.
  Fuente: `ws2_32/socket.c::WS_connect` y `server/sock.c::sock_ioctl` de Wine11.
- **Evidencia nueva:** wineserver29785 creó socket TCP inode2032842/FD650 y
  conectó a34.110.236.123:443; EINPROGRESS seguido de SO_ERROR0. El **mismo inode**
  se comprobó en WoW30060/FD386. AmbosUID10330, mismo prefix y marcador propio.
  Esto acredita TCP del WoW vía su wineserver, no Android nc ni solo un UID.
- **Límite:** ese peer es443, no el portal Forever1119. No afirmar que sea auth,
  que se ignore portal o que deba bloquearse: falta identificar DNS/SNI/servicio.
  Los argumentos reales del WoW incluyen el WTF derivado correcto y `-d3d11`.
- **Siguiente hipótesis:** identificar con logging estándar DNS/SNI qué servicio
  corresponde al HTTPS y qué llamada/fallo aparece después. No más claves/capturador.
- Entorno de D3 restaurado al cerrar, un Login, instrumentosOFF. Artefactos
  privados: `/home/adrian/wowforever-artifacts/work/wow-net-d3-20261015T052204Z/`.
  `wineserver-network.txt:92–115` y comprobación de FD sustentan la atribución.

## Hipótesis próxima, no resultado

Hipótesis evaluada: traducción incompleta ChaCha20/AEAD de Wine. D4 no registra
ese fallo en los contextos de auth y ambos handshakes completan: **no se ejecutó
un A/B de cipher ni se aplicó política GnuTLS**, al no estar justificado.

## D4 — Transporte auth completado; cuenta válida (2026-10-15)

- **Cambio:** logging estándar `trace+winsock,trace+secur32`, GnuTLS nivel1,
  sin WinHTTP/cookies ni nuevo APK. DNS/SNI identifican el HTTPS inicial como
  `breaking-news.support.blizzard.com`; su TLS completa. No era auth Forever.
- **Entrada:** formulario visible pero sin foco; dos Tab del teclado HID existente
  resolvieron el foco, sin cambiar APK. ADB tap fue denegado por INJECT_EVENTS;
  no se cambiaron permisos ni seguridad del dispositivo. Los primeros intentos
  de preparación no escribieron credenciales/no cuentan como Login.
- **Ensayo efectivo:** un Login de test en este control, Enter finalizado
  06:08:14.084UTC. WoW resuelve `auth.gpon.com.co`; socket Win0x698 a132.145.132.10:1119,
  contextoTLS0x4e939e18, handshake completo06:08:14.151UTC y tráfico cifrado
  bidireccional. Es evidencia del cliente real, no Android nc.
- **Segundo canal:** DNS/SNI auth.gpon.com.co:8081, socket0x6a8,
  contextoTLS0x51a725e0, handshake completo06:08:15.744UTC. DecryptMessage recibe
  607/1437/338 bytes; EncryptMessage envía153/898/1588. El cliente inicia cierre
  TLS ordenado de8081 a06:08:25.197UTC, luego envía58 bytes en1119 y lo cierra.
  El cierre HTTP puede ser normal: no atribuir error de aplicación por ese orden.
- **Control independiente de cuenta, host:** GET estricto al servicio8081 devuelve
  HTTP200 `LOGIN_FORM` y `srp_url=/bnetserver/login/srp/`. Un POST autorizado de
  cuenta test a `/bnetserver/login/`, con TLS/hostname verificados, devuelve
  HTTP200, `authentication_state=DONE`, ticket presente y ningún error. El ticket
  no se retuvo. **Esto valida la cuenta en REST, no la autenticación del juego.**
- **Resultado:** no se acreditó reino/personaje/mundo. Transporte TCP/TLS y datos
  auth sí funcionan en esta ejecución; queda la semántica de auth/SRP/BGS y su
  respuesta. Los logs no muestran contenido HTTP/protobuf ni código de rechazo.
  Los metadatos de cipher no justifican desactivar validaciones o cambiar claves.
- Evidencia: `/home/adrian/wowforever-artifacts/work/wow-net-d4-20261015T054200Z/`.
  `wine-network-login.txt:185–215,487–549,698–740,1013–1291,2801–2856` y
  `host-rest-account-control.json`. Logs393778B/598516B, sin cap alcanzado.

## Bloqueo físico y checkpoint

**Resuelto:** el informe privado
`wow-net-d4-20261015T054200Z/restored-after-reconnect-20261015T164917Z.json`
acredita restauración del logging D4 a16:49:17UTC, otros campos preservados y
captura/parcheOFF. El texto siguiente describe el bloqueo anterior, no uno actual.

A06:27:45UTC y06:28:40UTC el serialxaga ya no aparece en ADB ni enUSB (`lsusb`
solo hubs y disco externo). Se requiere reconectar el móvil para continuar:
no es un bloqueo de credenciales, TLS o capturador. No se hicieron más Logins.

**Restauración entonces pendiente por desconexión:** el contenedor conservaba el WINEDEBUG
temporal de D4, `GNUTLS_DEBUG_LEVEL=1` y `WOWFOREVER_NET_DIAG=NET-D4-20261015T054200Z`.
No afirmar cleanup completo. Al reconectar, cerrar el paquete propio y restaurar
solo esos cambios desde `container-original.json`, conservando otros campos y
manteniendo ambos instrumentos de memoriaOFF. No hay política de cipher ni
bypass aplicado. Fichero de credenciales consumido/eliminado, tokens no guardados.

Después: obtener el resultado semántico del flujo auth del cliente o contrastar
su rutaSRP contra el servicio que ya acepta la cuenta; no repetir DNS/TCP/TLS
como si siguieran sin probar, ni volver al escaneo12x40 por defecto.

## Último intento visual y cambio de prioridad (2026-10-15)

- InstrumentosOFF y portal correcto tras restaurar el logging. El primer intento
  automático terminó en `ui-dump-failed`, sin envío de Login.
- Con el campo de contraseña seleccionado, confirmado por el usuario, se
  introdujo solo contraseña y un Enter a17:02:22.768401UTC; `submitCount=1`,
  fichero de credenciales consumido/eliminado.
- El usuario observó «Logging in to game server» o equivalente y después retorno
  aparente al formulario sin error visible. No se acreditó reino/personaje/mundo.
  No interpretar esta transición como éxito de autenticación ni rechazo causal.
- Evidencia externa: `/home/adrian/wowforever-artifacts/work/wow-login-visual-20261015T164917Z/`,
  `password-only-result.json`, acciones y `observed-result-170402.png` enmascarada.
- Decisión: avanzar en el launcher sin bloquearse en auth. El Login se da por
  provisional para priorizar, no por técnicamente validado. No más cuentas,
  capturas o diagnósticos de auth para implementar la primera UX.
