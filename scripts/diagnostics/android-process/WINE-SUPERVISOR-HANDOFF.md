# Wine supervisor: contrato de preparación (schema2)

Perfil Wine separado de las fixtures schema1. Plan aprobado:
`.agents/planning/wine-supervisor-handoff-plan.md`.
La primera puerta es `server_isolation`, luego `graphics_probe`; no cliente WoW.
El store de fixtures v2/UNKNOWN de EOF nunca se modifica ni rehabilita.

## Autoridad de aislamiento ya inspeccionada

Proton WCP137807f4145b1b440075f83b2b1b3501d19c4eb618784b1a28418652cae3db03:

- `bin/wineserver`841096/SHA804d676d11f228c565182d9b49317f42d0fca093b5c1c20a0960ba5753c199f2.
- `lib/wine/aarch64-unix/wine`8808/SHA389c7e15f699471042845a24fa47ce7d4e3276fd23181e0afb78b63b91cecc3f.
- `lib/wine/aarch64-unix/ntdll.so`664240/SHA4239686f121164bd3d96f8a44db81eb708ac9e30fe783c53d0291d93dbade42c.

Disassembly: server `create_server_dir`0x7b058 usa WINEPREFIX, stat dev/inode,
`<prefix>/.wineserver/server-%llx-%llx`; `acquire_lock`0x7b714 usa lock/socket
relativos después de chdir. Cliente ntdll0x624ec/0x62740/0x6277c construye el
mismo directorio y conecta a socket relativo; restaura cwd0x627c4. No problema
sun_path por la longitud del path absoluto. WINESERVERSOCKET es un FD heredado
del cliente, NO un selector de servidor: se prohíbe en el ticket/environment.
TMPDIR tiene efectos fsync, pero no elige ese namespace; XDG_RUNTIME_DIR no es
autoridad de aislamiento en estos binarios. Prueba de filesystem/SELinux queda
pendiente del control real bajo supervisor.

## Pristine prefix y contrato de materialización

`prefixPack.txz`22067952/SHAade99dc80b17086a269c038c9bc50167902103a9a61df763cba4d613543ff12a:
575 entradas, 163 directorios, 408 regulares, cuatro symlinks. Se preservan los
regulares y directorios tipados/pinneados; NO se extrae ningún link del archivo.
Archivo original: c:→../drive_c, d:→/storage/emulated/0/Download,
e:→/data/data/com.winlator.cmod/storage, z:→otro ImageFS. Omitir d:/e:/z:;
reconstruir solo c: relativo, z: ImageFS canónico y a: raíz privada de sonda.
No cliente/CASC/WTF/cookies de usuario ni prefijo compartido como fuente.

Reemplazos exclusivamente en el nuevo prefix:

| Destino system32 | Bytes | SHA256 |
|---|---:|---|
| d3dcompiler_47.dll |1114112|c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add|
| dxgi.dll |3059712|136a8703e9f6aede96ae671ba16f88de1191e79d59e18a617bbbe85fad0c74fc|
| d3d11.dll |4907008|5e2167882c88e8e4013e5d5aa1ba221ce9a3ddb847ed901c06b652ce97a07c0f|

No registry tweaks, wineboot o ejecución durante materialización. Inventario
completo con hashes de cada fichero se genera a partir del archivo exacto,
incluyendo los tres registros preservados. Stage privado700, fuentes stables,
CREATE_NEW/no symlinks/preexistencia/special/hardlinks/duplicates/path escapes,
fsync y publicación sin reemplazar otro destino. Una extracción parcial no
publica nada. Destino padre propiedad de app/RUN, nunca home/xuser activado.

## Primera integración: supervisor persistente, sin GUI todavía

Ticket cerrado APP-generated, versionado schema2, WS-32hex/RUN nonce32hex,
directorio nuevo por RUN y footprint dev/inode del prefix. No argv/env/cwd de
Intent ni socket/server preexistente. Binaries/root son los fijados en WCP,
paths canónicos y hash+identidad estable, no basename ni hash autoatestiguado.

Solo dos comandos START de perfil fijo:
1. SERVER: linker64 + wineserver `-f -p10`, dentro del namespace nuevo.
2. CLIENT_CONTROL: linker64 + Wine + `cmd.exe /c exit 0`, exactamente esos
   argumentos y mismo prefix; no texto arbitrario ni comandos del usuario.

El supervisor se mantiene vivo, activa subreaper y emite READY antes de START.
Socket y lock esperados deben aparecer únicamente en el dev/inode de ese prefix;
el server debe ser el hijo directo poseído. Puede continuar una fase cliente
solo tras ese hecho. Nada de adoptar o señalizar un wineserver ajeno, --kill ni
barridos. Datos socket/lock/prctl/identidades/eventos se registran durables.

Wine no emite birth/ACK del fixture. La lista `/proc/self/task/<pid>/children`
es una fuente kernel de hijos directos/adoptados: cada PID se verifica con
startTicks/UID4/PPID y se retiene sin wait/reap concurrente. Evento OWNED_ADOPT
no inventa una identidad de nacimiento previa. No buscar procesos por UID,
nombre, cmdline o edad. Hijos zombis permanecen reservados hasta wait del mismo
parent; señales solo a identidades directas/adoptadas no reaped, revalidación
antes de TERM/KILL. Ausencia del snapshot no prueba ECHILD.

Normal exige cliente exit0, cierre del server solo mediante handle kernel-owned,
todos los hijos observados reaped, waitpid final ECHILD, supervisor exit0 y
cierre durable/testigo independiente. ERROR conserva transcript de cleanup,
pero ningún EOF, exit del wrapper o force-stop se convierte en GO. Supervisor
SIGKILL deja UNKNOWN; no prometer contención después de su muerte.

## Puertas de implementación

Esta preparación puede producir herramientas host y manifiestos sin ejecutar
Wine. Antes de generar APK60: congelar formato completo ticket/IPC/budgets,
propietario único ForeverRunStore y adapter, preflight histórico, paths lib/Wine,
y tests de la cadena. No conectar el `start()` genérico de Bionic que mata UID.

### Wire congelado para SERVER/CLIENT_CONTROL (antes de la GUI)

Artefacto nuevo `libforever_wine_supervisor.so`; no modificar los ELF fixture v2.
CLI cerrado: `--run WS-32hex --nonce 32hex --runtime-root <canonical-Proton-root>
--prefix <nuevo-private-prefix>`. No argv/env/exec/cwd libres. Binarios reales
canónicos wine/wineserver/ntdll se validan contra los tres pins anteriores y
dev/inode estables antes de fork/exec; no ejecutar por basename o PATH.
En Android los roots se restringen al propio package/user0: runtime Proton
11.0-90624-arm64ec bajo ImageFS, prefix bajo el nuevo RUN Wine, NO shared prefix.
El propio supervisor se acredita por address dentro de RX file-backed noW,
nativeLibraryDir inmutable y basename exacto, como guard v2 corregido.
El runtime WCP es cache **app-owned**, no nativeLibraryDir: source hash compilado,
FD/dev/inode revalidado y UID/GID propios. Ficheros Unix observados0755; padres
observados0771 con UID/GID10330, se permite ese grupo privado de app, NO world
write ni grupo ajeno. No llamar a estos fuentes inmutables. Supervisor nativo
y loader plataforma sí requieren autoridad root/system no escribible por app.
El alias fijo `/system/bin/linker64` resuelve en el dispositivo a
`/apex/com.android.runtime/bin/linker64`; solo estos dos destinos se aceptan.
No aceptar alias, loader ni runtime arbitrarios suministrados por el caller.

Commands: objeto JSON plano con exactamente `schema,runId,nonce,seq,command,phase`.
Schema2; commands START(server), START(client), STOP(none), CLOSE(none).
START(client) solo después de SERVER_READY. No segunda START de la misma fase.
STOP/CLOSE terminan el run y esperan cleanup. CLOSE antes de START es CANCELLED.
Seq comienza1 por cada sentido; duplicados, missing/extra keys, overflow, nonce
incorrecto o EOF producen ERROR/UNKNOWN y cleanup; jamás interpretan otra orden.

Events: exactamente `schema,runId,nonce,seq,event,phase,pid,startTicks,ppid,
uid0,uid1,uid2,uid3,role,status,exitCode,childCount,echild,setResult,getResult,
getValue,prefixDev,prefixIno,socketDev,socketIno,lockDev,lockIno`.
Events READY/SPAWN/OWNED_ADOPT/EXIT/SERVER_READY/PHASE_DONE/RUN_DONE/ERROR;
roles supervisor/server/client/adopted; phase none/server/client; status
READY/RUNNING/REAPED/PASS/CANCELLED/UNKNOWN. ExitCode-1 excepto EXIT.
Solo READY lleva prctl facts; solo SERVER_READY lleva dev/inode socket/lock
positivos. Los demás campos fact no observados son -1, no cero inventado.
Prefix dev/inode y supervisor identity son constantes; child events registran
su identidad kernel observada. RUN_DONE exige supervisor, childCount0 y
waitpid ECHILD real para closure positivo; ERROR/UNKNOWN nunca se oculta.

Antes de READY se crean exclusivamente `home`, `tmp` y `.supervisor-output`
privados700 bajo el prefix, sin adoptar entradas preexistentes. El entorno
usa ese HOME/TMP, verificados también antes de cada fase/exec. Stdin de hijos
es /dev/null; stdout/stderr de cada fase son pipes separados del IPC, drenados
sin bloqueo a files CREATE_NEW0600 `server.stdout`, `server.stderr`,
`client.stdout`, `client.stderr`. Máximo64KiB por stream/256KiB combinados,
EOF/fsync files+directorio necesarios antes de closure positiva. Overflow o
fallo de persistencia fuerza UNKNOWN, continúa cleanup de hijos propios;
contenido de esos logs jamás se interpreta como IPC ni ownership.
Todo preflight de fuentes/namespace es read-only antes de crear datos privados.
Si falla una escritura posterior, se retiene el prefijo parcial para diagnóstico
y se rechaza su reutilización; ningún rollback/retry automático ni cleanup de
árboles ajenos. Un siguiente RUN necesita su nuevo prefijo y admisión propia.

Kernel own child listado en `/proc/self/task/<self>/children`, máximo64.
Antes de wait, usar waitid(WNOWAIT) o equivalente y stat/status exactos: no
perder una identidad zombie reaped. OWNED_ADOPT registra solo parent actual,
sin ficticio birth/ancestry previo. Supervisor single-threaded/default SIGCHLD;
ningún waiter concurrente ni reap automático. Estas condiciones sostienen la
reserva del PID entre validación, señal y wait.
El supervisor verifica SIGCHLD=SIG_DFL sin SA_NOCLDWAIT antes de cualquier fork;
si hereda una disposición de auto-reap, aborta antes de crear hijos. SIGPIPE se
ignora para que pérdida del lector IPC no mate al supervisor antes del cleanup.

Contrato de ejecución inicial: no heredar WINESERVERSOCKET/WINEPREFIX/HOME/
TMPDIR de otra sesión. Entorno allowlisted/reconstruido por el perfil: HOME y
TMPDIR privados, WINEPREFIX exacto, WINELOADER/WINESERVER/WINEDLLPATH derivados
del root; PATH fijo y LD_LIBRARY_PATH derivado de los directorios del runtime
y system lib64, ningún LD_PRELOAD externo en esta primera puerta. No se afirma
que esa configuración mínima sea ya la de gráficos; si falta dependencia,
conservar error, no fallback a procesos/prefix globales.

Budget supervisor45s +cleanup10s, output8KiB/line y256KiB total; native I/O
bounded, TERM→KILL solo hijos kernel-own no reaped, waits nonblocking, watchdog
final55s. Timeout/failed cleanup UNKNOWN; no hard preemption o supervivencia a
SIGKILL. SERVER_READY tras socket/lock tipados600/UID propio y directory
prefix-dev/inode exacto; rechazo de cualquier footprint preexistente. Binaries
foreign y testigos no reciben señales; no lock query sobre otra sesión.

Host tests usan binaries-fixture bajo compile flag **host-only**, pins
fixture compilados en ese test, nunca CLI Android que acepte arbitrary-exec.
Crossbuild de producción no incluye ese seam ni ejecuta Wine en host/dispositivo.

Solo después del control SERVER/CLIENT aceptado se habilita GRAPHICS_PROBE con
XServer/SysV/Pulse de handles Java propios. Pulse upstream daemoniza y hace
barrido por nombre: no puede usarse sin branch manejado foreground/handle.
Todos los paths stop/pause/resume/preinstall/quick-menu/Power deben estar cerrados
para el perfil gestionado, upstream no gestionado se conserva.

Probe80c1c260…f0c3de/runtime/driver/shaders se conservan. PE anónimo seguirá
CONTROL_INVALID sin prueba independiente; API/readback no es visual ni WoW.

## Contrato del primer puente APK (preparación antes de gráficos)

La primera entrada es una Activity DUMP-protected exclusiva en `:wine_supervisor`,
que retorna antes de inicializar Hilt/GameNative. No llama Bionic, XEnvironment,
ProcessHelper, preinstalls, audio ni servicios gráficos. Esto cierra esos paths
por exclusión para SERVER/CLIENT; no afirma que el futuro branch gráfico esté
implementado. MainActivity/aliases y la entrada fixture permanecen deshabilitados
para candidato60; sus assets y metadatos históricos se conservan.

API Kotlin compartida: `ForeverWinePrefix.prepare(context, wsRunId, nonce,
launchPublisher): Prepared`. `Prepared` contiene `wsRunId`, `nonce`, `prefix`,
`runtimeRoot`, `prefixDev`, `prefixIno`, `receipt`. La callback publisher recibe
`argv: List<String>, deadlineNanos: Long` y devuelve el exitCode tras wait real y
drain; el controller retiene cada job/Process y persiste el marcador de creación
ANTES de submit. No ProcessBuilder síncrono oculto dentro del materializador.

Una imagen ZIP determinista nueva contiene SOLO los411 regulares/163directorios
aprobados (sin links). Es derivada del template fijado y los tres DLLs; manifest
completo verifica hash/tamaño/mode/tipo de cada entrada. Kotlin extrae en staging
privado nuevo; un helper PIE `libforever_wine_prefix.so` verifica árbol/hash y links
desde FDs y publica con renameat2(RENAME_NOREPLACE), padres anclados y fsync. Solo
los links a:/c:/z: reconstruidos están admitidos. El helper no fork/exec Wine,
acepta únicamente paths bajo el WS actual y las raíces propias fijadas, nunca
destinos arbitrarios ni rollback sobre árboles ajenos. Stage fallido se retiene,
no se reutiliza y no produce un ticket preparado.

El prefijo final permanece `files/forever/wine-controls/<WS>/prefix/.wine`, runtime
root `files/imagefs_shared/proton/proton-11.0-90624-arm64ec`; probe-root vacío y
privado para esta puerta. Prefix preparation120s; luego supervisor45s+cleanup10s
sin modificar el watchdog nativo. Budget Java total175s+20s de cleanup; collector
240s+20s. Deadlines cooperativos, jamás claims de preemption o cierre por timeout.

`ForeverRunStore` es la única autoridad RUN. WS/nonce se vinculan durables al RUN
antes de cualquier creación; resultado de protocolo vive en ese output directory,
no otro current/store Wine. Store ACTIVE/terminationUnknown no se rearma: un RUN
Wine fallido con pérdida de prueba queda bloqueado. El primer handoff tras los
historiales previos necesita preflight de boot nuevo; eso no reescribe su cleanup.
El testigo Java-owned usa el fixture v2 inmutable sin cambiarlo: su NS-32hex se
deriva del WS y se registra explícitamente como testigo, no como otro RUN dueño.
Q/reap/exit0 y estabilidad antes de Q son requisitos independientes de ECHILD Wine.
