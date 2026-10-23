# Handoff Wine: APK61 probado; puerta server aún sin cualificar

Estado del plan aprobado:
[wine-supervisor-handoff-plan.md](../.agents/planning/wine-supervisor-handoff-plan.md).
**No hay todavía ejecución acreditada de Wine bajo este supervisor en Android.**
APK60 se construyó, auditó, retuvo e instaló. Su intento interrumpido se conservó
mediante una disposición exacta por nuevo boot, consumida por APK61. El nuevo
intento61 falló en preparación de prefijo y quedó UNKNOWN, sin prueba de Wine.
Los históricos59 y anteriores, incluido EOF UNKNOWN, permanecen intactos.
Resultado y evidencia actuales:
[wine-interrupted-run-disposition-results.md](wine-interrupted-run-disposition-results.md).

## Qué se ha implementado

### Prefix pristine privado

`scripts/diagnostics/android-process/wine_prefix_template.py` y su manifiesto
`wine-prefix-template.json` fijan el archivo pristine Proton, no el prefijo vivo.
Archivo22067952/SHAade99dc80b17086a269c038c9bc50167902103a9a61df763cba4d613543ff12a:
575 entradas:163 directorios,408 regulares y4 symlinks. Se verifican todos los
regulares y sus hashes. Los links del archivo nunca se extraen; se reconstruyen
solo `a:`, `c:` y `z:` con bindings explícitos. No `d:`/`e:` de otra app.

Solo se añaden los DLL AA64 fijados compiler47/DXGI/D3D11. Las tres registries
se conservan byte-exactas. El árbol preparado real contiene163 directorios,
411 regulares y3 links,168698139 bytes de contenido regular.

Seguridad: fuentes con ancestors no-follow y FD/identidad/hash, compressed bytes
autenticados sobre **el mismo FD** antes y después de XZ/TAR, staging anclado
por FD/inode, destino privado700, publicación `renameat2(RENAME_NOREPLACE)`
relativa al parent FD. No sobrescribir destinos ni limpiar árboles ajenos.
Verificador read-only exige árbol completo, permisos, identidad y hashes.

Manifiesto133753/SHA306bed4ade9aac21809919f1b1272cdbc9c389e77704a13374033dcee7b0bcfa.
Módulo actual SHAdcb8a994486f5a91459f1d8f8276fb98bf00b06fa183ad0c0caf7deb19d52c2f.

Referencia host real:
`/home/adrian/wowforever-artifacts/work/unique-wine-prefix-reference-20261010-ade99dc80b17-anchor2/`.
El report original registra la revisión del materializador usada entonces;
precede al endurecimiento posterior del descriptor XZ/TAR. No se reescribe
ese report como si hubiera ejecutado la revisión actual. La revisión actual
verificó de nuevo el inventario real mediante el FD autenticado y32 tests.
Los roots `imagefs`/`probe` de esa referencia son vacíos de host, no Android.

### Supervisor Wine separado

Fuentes nuevas `wine_run_supervisor.c`, `wine_run_supervisor_common.h` y receta
`build_wine_run_supervisor.sh`. No cambian supervisor/fixtures v2 de APK59.
Contrato cerrado [WINE-SUPERVISOR-HANDOFF.md](../scripts/diagnostics/android-process/WINE-SUPERVISOR-HANDOFF.md).

Primera puerta: `server_isolation`, sin GUI. Solo puede ejecutar el wineserver
fijado `-f -p10` y el cliente Wine fijado `cmd.exe /c exit 0`, con args/env/cwd
generados por el perfil; no executable/argv arbitrarios. IPCschema2 privado,
READY/prctl antes de START, procesos kernel-own observados por UID4/PID/ticks/PPID,
adopción sin inventar nacimiento, `waitid(WNOWAIT)` antes de reap, señales
exclusivamente a hijos directos/adoptados no reaped. Nada de sweeps UID/nombre
ni `wineserver -k` o adopción de un socket preexistente.

El selector real Proton se inspeccionó estáticamente: WINEPREFIX dev/inode,
`.wineserver/server-<dev>-<ino>`, chdir al directorio y socket/lock **relativos**.
La longitud del path absoluto no excede sun_path porque no se pasa como dirección.
WINESERVERSOCKET es un override por FD del cliente y queda prohibido; TMPDIR
no selecciona el namespace del server. Namespace, lock holder y socket se
revalidan antes de client/exec, no solo en preflight.

Fuentes WCP cache son app-owned y SHA-pinned, no se confunden con la autoridad
inmutable de nativeLibraryDir. Lecturas de metadata en el dispositivo confirmaron
Unix ELF UID/GID10330 y0755; sus padres son0771 con grupo privado10330. Se permite
ese grupo propio del cache, no world-write ni grupo ajeno. El loader fijo se
resuelve de `/system/bin/linker64` a `/apex/com.android.runtime/bin/linker64`,
con autoridad plataforma root/system; no alias o loader del caller.

HOME/TMP privados700 y logs CREATE_NEW0600 antes de READY, **después** de todas
las validaciones read-only. Logs de server/client stdout/stderr separados de
IPC:64KiB/stream,256KiB combinados, EOF/fsync y bindings de pathname/FD necesarios
para closure positiva. Overflow/pérdida del lector no interrumpe cleanup propio
ni produce PASS. Un preflight parcialmente escrito se conserva y no se reutiliza;
no rollback genérico o borrado del prefix. Budget45s+10s cleanup, sin promesa de
hard preemption o supervivencia a SIGKILL.

Artefacto actual externo:
`/home/adrian/wowforever-artifacts/arm64-prototype/controls/wine-run-supervisor-v1/`.

- `libforever_wine_supervisor.so`:45056 bytes,
  SHA6f641c3683b239eb58a3986e947afe2099555ef138d495f8490a000b5b52f0da.
- Manifest SHA8c9bcde26c1ae3a7ebcb620db47b5a5d4f9dc450f021ae4b4d97b28785b3b894.
- AArch64 ET_DYN PIE, linker64, libc-only y LOAD alignment16384.
- NDK24/API29, compiler y fuentes registrados en manifest.

## Validación y límites

- Prefix:32 tests dirigidos actuales PASS; inventario real575 verificado.
- Supervisor: rondas host con **ejecutables fake**, incluida una de20 casos y
  tres dirigidos posteriores a la revalidación FD/hash. Counts por rondas, no
  suma de todas las ejecuciones ni suite completa del repo.
- Cross-build producción final PASS con el pin anterior. No real Wine/ELF
  Android ejecutado; las pruebas host no acreditan Proton/SELinux/app-domain.
- Revisión independiente detectó fallos de SIGPIPE/output cleanup, fuente TAR,
  publicación, namespace entre fases, server exit, log paths y preflight mutador;
  correcciones y regresiones incorporadas. La conservación de un prefix
  parcialmente escrito es deliberadamente fail-closed, no un retry autorizado.
- Kotlin/Store/prefix/IPC:33 casos Wine validados por rondas y18 de regresión
  legacy state/recovery. Fresh recipe64 entradas verificadas, build/audit APK60
  PASS;164 miembros antiguos byte-idénticos y seis adiciones deliberadas.
- Se hicieron install/reboot/UI del propio APK60 y un Start que publicó binding.
  No READY/cadena Wine/cierre acreditados. El collector corregido pasó58 tests.
  La evidencia física y el closeout read-only mantienen el RUN activo bloqueado;
  no nueva admisión o recuperación del store fixture.

## Estado de integración y siguiente gate

El perfil SERVER_ISOLATION ya usa Java Process/IPC y preparación privada ligados
a **ForeverRunStore como autoridad única**, sin entrar a GameNative/Bionic genérico.
El intento interrumpido `RUN-1791718684192-65783495695` sigue `ACTIVE/PREFLIGHT`.
La nueva decisión de admisión está escrita, no ejecutada, en
[wine-interrupted-run-disposition-plan.md](../.agents/planning/done/wine-interrupted-run-disposition-plan.md).
Esa disposición se consumió por61; el nuevo prelaunch UNKNOWN no la hereda.
No confundir un nuevo boot con cierre/reaping retrospectivo del intento anterior.
XServer/Pulse/SysV se integran para la sonda gráfica solamente después del gate
de server isolation; Pulse upstream necesita un branch foreground/handle propio.

No GO de Wine ownership, API, visual ni WoW a partir de esta preparación host.
PE anónimo sigue pendiente de prueba independiente; no se relaja el analizador.
