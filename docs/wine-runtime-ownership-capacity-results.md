# Android: capacidad subreaper validada, integración Wine pendiente

Estado 2026-10-09. Continúa `wine-aa64-start-readiness-results.md` y cierra
`.agents/planning/done/wine-runtime-ownership-plan.md`, aprobado con «si, hagamoslo».
**GO de capacidad kernel en el microcontrol; no GO del runtime Wine/APK.**

## Alcance y artefacto final

Solo POCO xaga/22041216G Xiaomi, Android14/API34, serial `DEVICE_SERIAL`,
package `com.wowforever.arm64probe`, UID4=10330. Binario privado ejecutado mediante
`run-as` y `/system/bin/linker64`. No root, ptrace, UI, Wine, instalación/rebuild del
APK, cambios del cliente, PE, compiler, driver, autenticación o parcheo.
APK55 instalado conserva SHA-256
`4818835af27aad8c9196af15976d9debeef933193a03f8cb360ceb35bd60d514`.

Fuentes nuevas:

- `scripts/diagnostics/android-process/run_supervisor_capacity.c`
- `scripts/diagnostics/android-process/build_run_supervisor_capacity.sh`
- `scripts/diagnostics/android-process/run_supervisor_capacity.py`
- `scripts/diagnostics/android-process/README-run-supervisor.md`
- `tests/test_android_run_supervisor.py`
- `tests/test_android_run_supervisor_native_failures.py`

Artefacto externo `arm64-prototype/controls/run-supervisor-capacity-v1/` bajo
`/home/adrian/wowforever-artifacts/`:

| Campo final | Valor |
|---|---|
| ELF `RunSupervisorCapacity` | 32144 bytes |
| SHA-256 ELF | `519bf45e260940eda6f5a8611b182bffec65e95f57c8840795b4c074b2745761` |
| Manifest | 1551 bytes |
| SHA-256 manifest | `a6b8683e999e54abbc000371f82845f5b3a96659496da6c9046e20c1917fc92c` |
| SHA-256 C | `c284a1b359065ba3be9407360d879bf4c03cc22af21bbf98f954fd434ebc406b` |
| Build | NDK24, Android29, AArch64 PIE, libc-only, LOAD 16KiB, `-Werror` |
| Schema nativo | `wowforever.android-run-supervisor/version2` |

La receta fija tanto el launcher NDK de 174 bytes como el compilador real
`clang-14` de 97704928 bytes/SHA
`f62d877b60e6428c42a304508cfb57990b947b604c70f026384571dd8ffa98c7`.
El runner comprueba pins del ELF, manifest y fuentes; no los actualiza al vuelo.

## Protocolo y seguridad del microtest

El orquestador diagnóstico se declara subreaper como red de seguridad **solo del
microtest**, crea un testigo hermano del supervisor, y un supervisor dedicado crea
dos familias double-fork. IPC/ACK registra nacimiento antes de permitir que muera
el intermedio. Se observan UID4, PID/startTicks/PPID, adopción, estados de salida y
`waitpid` hasta `ECHILD`. Esto no instala un subreaper global en la aplicación.

Cleanup señaliza únicamente hijos propios aún no reaped, con revalidación de
PID/startTicks/UID4/PPID antes de TERM y de KILL. No barrido por UID, nombre, edad o
grupo; identidad no verificable implica UNKNOWN/no señal. Root presupuesto15s+
cleanup5s, supervisor watchdog12s, streams64KiB y stdout no bloqueante. Son límites
cooperativos/watchdogs, no garantía de hard preemption del kernel.

El runner acota también upload/stdin/stdout/ADB, cita scripts completos de `sh -c`,
rechaza symlinks y exige fichero privado regular UID10330/mode700/nlink1 con
stat/hash estable. El parser rechaza claves JSON duplicadas, roles repetidos,
PPID incoherente, birth/adoption con identidad distinta y PRCTL no positivo.
PASS exige reaping del testigo con exit0 y hechos independientes de ambos ECHILD.

## Tres controles físicos, con diagnóstico entre ejecuciones

Raíz de evidencia: `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`.
Cada control usa preflight, reboot nuevo, verificación del APK y backup, una sola
ejecución por invocación y su directorio privado; no retry inline.

| Campo | Primero | Corrección adoption | Corrección testigo |
|---|---|---|---|
| RUN | `RS-738bd97f321b4a4bbac1926041e32064` | `RS-1729f858f73d4ffab58a70a433186c14` | `RS-f22a4b8fa2bb4a418ab142d4e8b58186` |
| Directorio | `native-RS-738bd97f321b4a4bbac1926041e32064` | `native-RS-1729f858f73d4ffab58a70a433186c14` | `native-RS-f22a4b8fa2bb4a418ab142d4e8b58186` |
| Boot | `9627bec5-9d93-4e63-b46b-af3d299c2ef2` | `9623e630-e0a4-47e6-b898-37e6490cc2da` | `e86f1c63-4047-4ff8-9811-cdc250886d6e` |
| ELF bytes | 31960 | 31960 | 32144 |
| SHA-256 ELF | `ef9664e85ab9e52ba4dd542c4066c01c9edf3cd4badadee4904111072443299c` | `560f1260b3ca01b79e7cb6252a93fdb1a9d2dc86e78aa8dac92962fafb712a46` | `519bf45e260940eda6f5a8611b182bffec65e95f57c8840795b4c074b2745761` |
| Native / runner | FAIL / UNKNOWN | FAIL / FAIL | PASS / PASS |

1. **Carrera de adopción:** el nieto enviaba READY y luego tomaba `getppid()` como
   padre original; si el intermedio ya había salido, esperaba erróneamente la
   desaparición del supervisor. El primer control conserva adopción normal, pero
   la familia cleanup termina en la red de seguridad raíz. No se inventa un número
   de señal para el supervisor. Corrección: conservar PPID del birth pre-exit.
   Regresión host determinista con demora100ms reproduce old no-PASS/fixed PASS.
2. **Carrera de salida del testigo:** tras enviar Q, el root entraba inmediatamente
   en TERM antes del exit0 cooperativo. El segundo control ya verifica adopciones,
   estados41/42/43/143 y ambos ECHILD, pero falla el criterio de salida del testigo.
   Corrección: gracia acotada200ms antes del fallback TERM/KILL y evento explícito
   `witness_exit`. Regresión host con demora50ms: gracia0 FAIL/exit143;200ms PASS/exit0.
3. **Control final:** ambas correcciones pasan en el dispositivo, sin timeout,
   stderr ni error de cleanup host. Ningún fallo anterior fue sobrescrito ni
   convertido retrospectivamente en PASS.

## Hechos del PASS final

`stdout.jsonl` contiene nueve eventos coherentes. UID4=10330 en todos los roles.

| Rol / hecho | Identidad / resultado |
|---|---|
| Root diagnóstico | PID6830/startTicks2717/PPID6820 |
| Supervisor | PID6844/startTicks2724/PPID6830 |
| PR_SET / PR_GET / value | 0 / 0 / 1 tanto root como supervisor |
| Nieto normal | PID6846/startTicks2725, PPID6845 al nacer →6844 adoptado |
| Nieto cleanup | PID6854/startTicks2728, PPID6852 al nacer →6844 adoptado |
| Estados familias | Intermedio normal41, nieto42; intermedio cleanup43, nieto143 |
| Supervisor | exit0, `waitpidEchild=true` |
| Root | `rootWaitpidEchild=true`, `cleanupConfirmed=true` |
| Testigo hermano | PID6840/startTicks2722/PPID6830 estable; exit0/reapedtrue |
| Host | nativeExit0, timedOutfalse, stdout3870 bytes, stderr0 |

GO: API34 permite subreaper/adopción/reaping en este contexto diagnóstico. No prueba
el dominio SELinux/seccomp de un proceso iniciado por el APK normal, supervivencia
tras crash/SIGKILL del supervisor, ejecución desde `nativeLibraryDir`, ni contención
de Wine/wineserver. El atributo subreaper no mata ni reap automáticamente.

## Verificación y preservación

Cross-build final local PASS. Suite final exacta:

```bash
/home/adrian/.local/bin/uv run --offline pytest -q \
  tests/test_android_run_supervisor.py \
  tests/test_android_run_supervisor_native_failures.py
```

**28 PASS en60.69s**, incluidos fallos y ambas reproducciones deterministas.
Revisión independiente GO para control normal aislado después de nuevo reboot.
No suite global, JVM, APK build ni pruebas Wine en esta operación; no sumar rondas.

Recogida posterior solo lectura: los tres payloads del dispositivo se copiaron a
`retained-RunSupervisorCapacity` en su propia evidencia y se verificaron contra
stat/dev/inode/mode/size/hash antes/después. Cada carpeta tiene `preservation.json`,
`source-at-collection.json` y `evidence-sha256.json` que sella11archivos (sin incluirse
a sí mismo), escrituras exclusivas0600/fsync y fsync de directorio. El source actual
no se presenta como el ejecutado en los dos controles anteriores: los pins históricos
están en sus result/manifest originales y en el ELF retenido.

Post-collection observa solo el ps UID10330; esto no demuestra quiescence de otras
sesiones. Compiler AA64 c374b107... y backup original06dc23... siguen intactos.
No rollback, limpieza global ni eliminación de evidencia; reboot antes de otro Wine.

## Diseño posterior y límites restantes

Diseño: `docs/wine-run-supervisor-design.md`. La operación posterior nativa se aprobó
y cerró en `.agents/planning/done/native-run-supervisor-apk-plan.md`: APK56 UNKNOWN
antes de READY, no GO app-domain. Ver `docs/native-run-supervisor-apk-results.md`.
Corrección loader posterior cerrada en `.agents/planning/done/native-supervisor-loader-plan.md`:
APK57 todavía NO-GO, ver `docs/native-supervisor-loader-results.md`.
La adopción de wineserver/prefix compartido y la identidad independiente del PE
anónimo siguen gates separados; el resultado de este microcontrol no cambia.

Residuales del microcontrol: testigo watchdog10s frente a15s+5s puede causar fallo
conservador en controles lentos; un flag de reaping en fault no se actualiza en
cierta ruta exitosa. No habilitan PASS falso ni señales ajenas. No reutilizar este
orquestador de test como supervisor de producción sin un contrato nuevo.
