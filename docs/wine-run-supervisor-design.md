# Supervisor por RUN: diseño y gates, todavía sin integración Wine

2026-10-09. Stage B del plan de capacidad aprobado. Evidencia kernel GO:
`wine-runtime-ownership-capacity-results.md`. Este documento no cambia el contrato
del analizador, no autoriza señales adicionales, ni describe comportamiento ya
implementado en APK55. El objetivo sigue siendo un launcher que posea el proceso
del juego y pueda cerrarlo/repararlo con certeza, no pasar un gate por heurísticas.

## Decisiones de frontera

- Supervisor **por RUN**, persistente y Java-owned; nunca subreaper global de app.
- Java obtiene `java.lang.Process` del supervisor. Este no hace exec-away: configura
  PRCTL, permanece vivo y hace fork/exec del child de cada fase.
- No copiar el orquestador safety-net del microtest como implementación de producto.
- Native `READY` antes de START/child. Registro durable e identidad root antes de
  autorizar START; cancelación linealizada con esa autorización.
- IPC por pipes del `Process` para el primer control: canal ya poseído, framing
  versionado, límites y RUN/nonce/sequence. No sockets globales ni comandos shell.
- Cleanup solo identidades kernel propias. Señal por PID solo cuando es hijo
  directo/adoptado no reaped y se revalida UID4/startTicks/PPID. Un descendiente no
  directo no ofrece por sí solo esa garantía contra PID reuse: no señalizarlo sin
  un handle kernel apropiado y contrato probado. Si no se puede completar: UNKNOWN.
- Descriptor/EOF/error/nonce o secuencia incorrectos no prueban cleanup. RUN_DONE
  exige todos los hijos registrados resueltos, final ECHILD y supervisor exit0.
- Mantener supervisor entre fases futuras (preinstall/Wine/game/stop), pero esta
  capacidad del runtime completo no está probada por el microtest.

Interfaz conceptual, no API pública aprobada:

```text
start(spec): SupervisorHandle
startPhase(handle, approvedPhase): ChildIdentity
stopPhase(handle): PhaseResult
close(handle): RunResult
```

Eventos propuestos: READY, SPAWN, ADOPT, EXIT, PHASE_DONE, RUN_DONE, ERROR.
Identidades incluyen PID/startTicks/UID4/PPID, rol/relación y RUN/phase/sequence.
Una notificación nativa se contrasta con hechos `/proc` y reaping del owner; un
nonce protege coherencia/canales, no demuestra por sí mismo qué PE ejecuta el PID.

## Seams del runtime efectivo, no solo upstream vanilla

Checkout externo actual `arm64-prototype/toolchain/GameNative`, overlay versionado
en `scripts/arm64-prototype/app/apply_overlay.py`. Inspección del source efectivo:

| Componente | Frontera y riesgo |
|---|---|
| `BionicProgramLauncherComponent.start/execGuestProgram/stop` | start llama stop; stop mata PID y `ProcessHelper.listSubProcesses()`, luego `wineserver -k`. No es stop seguro por RUN. |
| `ProcessHelper.exec` | Runtime.exec devuelve root Process y observer global; no seguimiento nativo de grandchildren. |
| `ProcessHelper.listSubProcesses` | Enumera procesos del mismo usuario excepto app; no equivale a descendientes del RUN. |
| `ForeverProcessDiagnostics` | Owner del Process wrapper + children sampling; declara no kernel containment. |
| `ForeverRunStore` / `ForeverLaunchController` | Estado/intent/cancelación application-scoped; deben separar supervisor de Wine/PE y conservar cleanup incompleto. |
| `XServerScreen` | Invoca stale-process sweeps, setup/preinstall y teardown. La ruta direct se prefija con wine explorer. |
| `PluviaApp.shutdownEnvironment` | `env.stopEnvironmentComponents()` también llega al stop; Activity destruction no es un cierre kernel probado. |
| `execShellCommand/execWithOutput` | Puede saltarse el observer; no dejar preinstalls/wineserver en una vía no supervisada. |

Antes de Wine deberán cubrirse todas las rutas efectivas de managed-RUN, incluyendo
start, exit, lifecycle, cancelación, errores de setup y pause/resume. No añadir un
wrapper dejando intactos barridos same-UID ni `wineserver -k` genérico. Las rutas
no managed se preservan: no es un refactor global de GameNative.

## Aislamiento Wine aún no resuelto

El prefix/home actual es compartido. Un wineserver preexistente no es hijo nuevo
del supervisor aunque lo use el nuevo Wine. Subreaper no adopta procesos que nunca
pertenecieron a su árbol. La integración Wine debe acordar prefijo/socket privado
por RUN o demostrar exclusión de daemons preexistentes y abortar ante ambigüedad.
No efectuar ahora clones, Wineboot, registry, repair ni `wineserver -k`.

Un supervisor muerto por SIGKILL puede perder hijos hacia init. No declarar cierre
correcto a partir de supervisor exit, lista vacía muestreada o timeout del host.
Setsid por sí solo no rompe adopción, pero process-group no sustituye ownership.
Kernel/process namespace/SELinux y ejecución desde app deben verificarse aparte.

## Packaging y siguiente control mínimo

Para app target36/min29, `run-as` + ownfiles no prueba el camino normal del APK.
Propuesta: supervisor y child nativo inocuo como ELF PIE AArch64/16KiB empaquetados
inmutables en `nativeLibraryDir`, con extracción/configuración auditadas, ejecutados
por argv exacto vía linker64 desde Java. El nombre `.so` es un mecanismo de
packaging, no convierte el ejecutable en biblioteca JNI. No W^X bypass ni fallback
a binario descargado/writable. Control real del APK es obligatorio.

Siguiente operación propuesta: APK de laboratorio nuevo, ruta **nativa exclusiva**
que no entre en XEnvironment/Wine ni toque el prefix. Java posee supervisor; child
inocuo double-fork y testigo hermano confirman IPC, adopción, cierre natural,
cancelación y errores. No integración parcial con GameNative stop inseguro.
La operación nativa fue aprobada y ejecutada; plan archivado en
`.agents/planning/done/native-run-supervisor-apk-plan.md`. APK56 alcanzó un RUN pero
salió antes de READY y quedó UNKNOWN: `native-run-supervisor-apk-results.md`.
El defecto de guard bajo linker64 y la separación del laboratorio están implementados,
plan `.agents/planning/done/native-supervisor-loader-plan.md`. APK57 todavía UNKNOWN
por política Java previa al launch: `native-supervisor-loader-results.md`.
La disposición de ese prelaunch en el mismo store requiere el nuevo contrato
`.agents/planning/done/native-prelaunch-disposition-plan.md`, sin integración Wine.
APK59 obtuvo GO app-domain y cancelaciones verificadas; EOF conserva UNKNOWN:
`docs/native-prelaunch-disposition-results.md`. El siguiente contrato propuesto
es `.agents/planning/wine-supervisor-handoff-plan.md` (draft, sin implementación).

## Identidad PE anónimo: gate independiente

Supervisor prueba ownership, no identidad del PE. GetCurrentProcessId Win32 no es
Unix PID. No aceptar nombre, base0x140, hash del fichero de entrada, timing, RUN o
auto-reporte como sustituto de imagen efectiva.

Opción a evaluar después: lectura nativa/JNI **solo lectura** del proceso poseído,
mediante `/proc/<pid>/mem` o process_vm_readv si lo permiten kernel/SELinux. Validar
MZ/PE/AA64/PE32+, layout y secciones invariantes contra el PE pinneado; normalización
explícita de relocaciones/imports y manifest versionado. Infraestructura de lectura
en `crates/forever-patcher-jni` no prueba permisos bajo esta nueva topología Java/
supervisor/Wine. No root/ptrace ni escribir memoria para conseguir identidad.
Bridge/nonce puede complementar esa prueba, no reemplazarla. Si lectura denegada
o identidad no demostrable: INCOMPLETE/CONTROL_INVALID, nuevo diseño, no relajar gate.

## Criterios antes de pasar al juego

1. Java-owned supervisor y ejecución APK nativa verificadas; cancelación segura.
2. Integración managed Wine con prefijo/socket/daemons aislados y todos los stop
   paths sin sweeps; hijos adoptados/reaped, testigo intacto, cierre kernel probado.
3. PE y PanVK ligados al mismo proceso poseído por método independiente aprobado.
4. API/readback/Present y evidencia visual de la sonda, cada una por separado.
5. Solo entonces control del cliente WoW; login/patch siguen otro gate.

No confundir ningún GO parcial con una entrega jugable. No build/install de APK
ni ejecución Wine realizados durante la etapa de diseño.
