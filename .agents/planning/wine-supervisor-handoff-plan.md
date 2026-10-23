---
id: wine-supervisor-handoff-2026-10-10
state: in-progress
date: 2026-10-10
---

## Summary

APK59 acredita supervisor persistente, adopción/reaping y cancelaciones en el
dominio real de la app. Siguiente paso hacia WoW: arrancar **la misma sonda D3D
AA64** mediante un supervisor por RUN, en vez del wrapper Wine que deja PPID1.
No es todavía gameplay, login ni cualificación del PE anónimo.

Este contrato cambia ownership del runtime y admite procesos Wine. Requiere
aprobación antes de implementar; no pide permiso por cada corrección o control.
Autorizado para continuar por el usuario: «sigue», después de escribir este plan
y anunciar el siguiente handoff Wine. Se conserva su delegación de autonomía.
Evidencia previa: `docs/native-prelaunch-disposition-results.md`.

## Content

### Decisiones y frontera

- Mantener APK59, fixtures/IPCschema1 y sus historiales byte-exactos, incluido
  UNKNOWN de EOF. No recuperar ni rearmar aquel store. El perfil Wine es un
  nuevo contrato funcional, no otro namespace para repetir fixtures fallidas.
- Supervisor Wine dedicado por RUN, Java Process propietario y persistente,
  subreaper activado antes de cualquier fase Wine. Nunca exec-away ni subreaper
  global de la app. Señales solo a hijos directos/adoptados no reaped cuya
  identidad kernel está verificada; ningún PID/nombre/UID sweep o wineserver-k.
- Frontera explícita: **cadena Wine**. XServer/audio/SysV componentes Android
  permanecen Java-owned y se cierran con sus handles propios, sin afirmar que
  ECHILD del supervisor cubre todo el runtime/app o sobrevive SIGKILL.
- Reusar pin de GraphicsProbeReadback-ARM64.exe96768/SHA80c1c260…f0c3de,
  Proton11.0-90624-arm64ec, DXVK2.4.1 custom, PanVK beta17/wrapper/noGL y shaders
  exactos. No cliente WoW ni auth/memory patch, driver/async/GPL/culling changes.
- Perfil final `graphics_probe`, precedido por puerta `server_isolation` sin GUI:
  argv/env/cwd generados por app a partir del
  catálogo auditado, nunca shell, executable/argument/environment suministrados
  por Intent. Ticket acotado/nonce/RUN/hash y paths canónicos comprobados por
  ambos extremos; no general-purpose exec API.
- IPC Wine versionado separado del schema1 de fixtures. READY, fases, SPAWN,
  ADOPT, EXIT, ERROR y cierre conservan PID/startTicks/UID4/PPID/seq; marcador
  durable antes de toda posible creación. Native retiene sus propios waits.
  ERROR no puede descartar silenciosamente hechos de cleanup: conserva el
  transcript completo, distingue cleanup observado de cualificación positiva
  y mantiene UNKNOWN si cualquier identidad/job/pipe/cierre es ambiguo.
- Un RUN autoritativo: puente explícito del handle Wine supervisor con
  ForeverRunStore/LaunchController. No dos stores que se declaren dueños del
  mismo Wine. Fuera del perfil gestionado, conservar el comportamiento upstream.

### Prefijo y wineserver: puerta antes del dispositivo

- Prefijo nuevo por RUN; no usar el compartido reparado ni modificarlo.
  Definir snapshot consistente de plantilla sin ejecutar Wine ni tar cliente
  vivo, con copias tipadas/verificadas de los componentes ya fijados. No copiar
  sockets/locks, procesos, credenciales, cuenta, WTF personal ni cliente CASC.
- HOME/WINEPREFIX/TMPDIR y, solo si la build lo usa, XDG_RUNTIME_DIR/socket dir
  privados. Confirmar el mecanismo real de selección de socket/server en este
  Proton; no inferir aislamiento por nombres de variables ignoradas.
- Todas las fases productoras de hijos (init/wineboot/preinstalls si necesarias,
  explorer/probe/wineserver/teardown) pasan por el supervisor. Cualquier ruta
  genérica restart/preinstall o execShellCommand que lo evada debe abortar el
  perfil, no continuar mediante fallback.
- Si aparece servidor/socket preexistente o no se puede demostrar exclusividad:
  STOP. No adoptar ni matar procesos de otra sesión. Mantener compiler/backup
  original en el prefix viejo y registrar hashes antes/después.

### Implementación acotada propuesta

- Nueva receta/C/fixtures host del supervisor Wine y manifest propio, sin
  alterar los ELF v2 usados por APK59. PIE AArch64/libc/16KiB desde
  nativeLibraryDir, guard de imagen loader-compatible ya probado.
- Builder Kotlin: spec cerrado, persistencia/IPC/handles, adapter managed de
  Bionic launch; revisar TODOS los paths efectivos stop/pause/resume/start,
  shutdownEnvironment, preinstalls y observer. Omisión dirigida de sweeps solo
  en este perfil; prohibir fallback a kills globales.
- Builders de prefix/ticket y packaging comparten primero ese contrato fijo;
  no escritores concurrentes en apply_overlay/controller. Overlay reproducible,
  provenance/pins/audit/retenidos; candidato60/0.2.36-automated si disponible.
- Propiedad de ejecutables Wine de app-cache debe fijarse desde WCP auditado y
  revalidarse al handoff con identidad estable; no confundir esa autoridad con
  la inmutabilidad de nativeLibraryDir ni aceptar solo basename/argv/hash enviado.

### Evidencia y pruebas

- Host/JVM: hijo double-fork/desconocido adoptado, cancel durante spawn, EOF/
  error/crash, writer/reader/startup bloqueados, nonce/seq/overflow, PID reuse,
  foreign same-UID witness, socket/prefix ajeno intocado, ausencia de sweeps.
- Revisión ownership antes de dispositivo; fresh overlay/JDK17 offline1worker
  CPU2/Xmx4GiB/assets-rerun-only, APK audit/retención, comparación con59:
  todos los runtime/PE/driver/client bytes existentes idénticos, additions
  deliberadas y provenance explícitas. No publicación/stage/commit/download.
- Primero nuevo boot/historia/pins/UID/device y control Wine inocuo de lifecycle
  en prefix privado; luego exactamente el graphics profile fijado. Deadlines
  app/host acordados antes de build y cooperativos, finite watchdogs y cleanup
  independiente. Repeticiones diagnosticadas autorizadas; no retries de START.
- GO ownership solo tras READY/prctl, ascendencia/adopción real, identidades,
  salidas/reaping/ECHILD y cierre durable/testigo. Mapa PanVK RX en candidato
  propio puede ser prueba parcial; no sustituye la identidad del PE.
- Mantener analizador gráfico estricto. PE anónimo sigue sin cualificar por
  nonce/nombre/base0x140/argv/logcat. Si no hay prueba PE independiente,
  CONTROL_INVALID aunque API/readback sean positivos. No memoria PE ni ptrace
  bajo este plan; diseñar esa verificación en un contrato separado.
- Present S_OK/readback no son proof visual, y ninguna sonda es WoW.
  Objetivo siguiente: ownership Wine → PE/PanVK atribuibles → frame visible
  → WowB.exe real; login/clave/memory patch después, por fases.

## Implementation / Action Items

- [x] 1. Aprobar frontera Wine y aislamiento de prefix/socket, sin fixture recovery.
- [x] 2. Verificar runtime efectivo y congelar schema2/plantilla/aislamiento/budgets
  para la primera puerta SERVER/CLIENT_CONTROL, sin GUI. Contrato en
  `scripts/diagnostics/android-process/WINE-SUPERVISOR-HANDOFF.md`; preparación
  host documentada en `docs/wine-supervisor-handoff-progress.md`. El ticket de
  gráficos se conecta después de probar la puerta de server.
- [ ] 3. Implementar supervisor Wine, adapter managed y tests positivos/negativos.
  - [x] 3.1 Puerta SERVER_ISOLATION aislada Java/native, publisher y Store único;
    no GameNative/Bionic genérico. Tests de lifecycle/IPC/durabilidad por rondas.
  - [ ] 3.2 Branch gráfico managed con XServer/Pulse/SysV propios, tras GO del server.
- [x] 4. Revisión de ownership, JVM/host, fresh/build/audit/retención y baseline
  para APK60 SERVER_ISOLATION; no autorización de gráficos a partir del build.
- [ ] 5. Nuevo boot, controles ownership y sonda fijada; preservar todo resultado.
- [ ] 6. Cerrar GO/NO-GO de Wine ownership, explicitar PE/visual pendiente.

## Open Issues

- Primera integración APK60 implementada: entrada aislada SERVER/CLIENT sin llamar
  GameNative/Bionic/XEnvironment; el branch gráfico no está conectado todavía.
  Prefix-image ZIP47680132/SHA42f9e911…1048853 y manifest2318de72…e625ba9,
  publisher123560/SHAf479ea8d…ad6914; Wine supervisor45056/
  SHA6f641c36…52f0da y manifest8c9bcde2…b3b894. Java/Store/publisher revisados,
  33 casos JVM Wine por rondas y18 legacy, fresh64/build/audit/retención PASS.
  APK60 instalado418266965/SHA76db60be…800c24. No ejecución Wine acreditada todavía.
- Durante packaging un worker sustituyó la receta59 no versionada. Los bytes
  exactos de esa receta SHA752eca7c…f32b62 no se han podido recuperar. No se
  afirma recuperación exacta: se congelan las fuentes efectivas59 aún intactas
  en `code59-source-baseline.json` (831917/SHA0a3d039a…8fdd0d8), con 12
  postimages sobre el archivo upstream fijado y 63 bindings de overlay. 62
  outputs son literales; Arm64Diagnostics deriva tres relocations de assets
  comprobadas byte-exactas. Replay nuevo debe preservar esos outputs, todos los
  assets/libs del APK59 y declarar honestamente la autoridad de esta migración.
  Captura/replay, fresh/idempotencia y equivalencia con source real verificadas;
  comparación ZIP real preserva164 miembros antiguos, añade seis y modifica
  solamente provenance. La autoridad de migración sigue declarada explícitamente.
- Metadata Android leída sin ejecutar Wine: files UID/GID10330 modo0771/nlink14,
  forever y imagefs propios modo0700/nlink4. Nlink de directorios no equivale a
  hardlink de fichero; no exigir nlink1 en directors ni rechazar grupo privado
  de app. Ficheros siguen exigiendo nlink1 y todo root ajeno/world-write se rechaza.

- Selector Android confirmado estáticamente en los binarios exactos: namespace
  WINEPREFIX dev/inode y socket/lock relativos tras chdir. WINESERVERSOCKET se
  prohíbe; TMPDIR/XDG_RUNTIME_DIR no se consideran aisladores de wineserver.
  Prueba runtime/SELinux todavía pendiente de integración APK.
- Existe `prefixPack.txz` pristine (22067952 bytes,
  SHAade99dc80b17086a269c038c9bc50167902103a9a61df763cba4d613543ff12a).
  Listado575/163dirs/408reg/4links auditado sin abrir el prefix compartido;
  manifest hash306bed4a…b0bcfa. Materialización host real411reg/3links verificada.
  Fuentes nuevas/materializador32 tests PASS; no confundir host con app-domain.
- Pulse upstream daemoniza y hace barrido por nombre; el perfil gestionado
  necesita handle exacto propio, no puede dejar ese path sin cambiar. Las rutas
  de Power/quick-menu/preinstall también deben bloquear los barridos genéricos.

- Supervisor Wine nuevo45056/SHA6f641c36…52f0da, schema2, compiler libc-only/16KiB;
 tests fake-host y dirigidos de fuentes por rondas. Fuentes WCP
 app-owned difieren de nativeLibraryDir inmutable; loader APEX alias confirmado
 por lectura únicamente. ERROR conserva logs bounded y nunca false PASS.
- Java/ForeverRunStore y preparación Android implementados para SERVER_ISOLATION;
 APK60 auditado/instalado. Falta prueba runtime de prefix/server/client/cierre.
 No copiar prefix vivo ni derivar GO físico de los tests host/JVM.
- Fallo parcial al crear HOME/TMP/logs se conserva como prefix no reutilizable;
 no rollback o admisión automática. Revisión P2 residual tratado como cuarentena
 deliberada, sin procesos hijos creados en ese preflight y sin claims ECHILD.
- Auditar cobertura exacta de cada lifecycle path del branch gestionado; generic
  Bionic/ProcessHelper contiene sweeps same-UID que NO pueden ejecutarse aquí.
- Wine no coopera con el birth/ACK de fixtures: definir adopciones kernel-own
  incluso de hijos no observados antes de quedar huérfanos, sin inventar nacimiento.
- EOF de APK59 quedó UNKNOWN y no prueba cleanup global. Preservar ese bloqueo
  y evidencia; nuevo boot es una frontera temporal, no retroactivo ECHILD.
- API29, dispositivo real16KiB y pérdida del supervisor por SIGKILL siguen sin
  prueba. No prometer runtime de producto tras este diagnóstico.
- Dos controles60: el primero no pasó readiness; el segundo publicó RUN/WS y el
  collector rechazó una ruta WS fijada, hizo captura incompleta y force-stop propio.
  Error host corregido (allowlist/esperas/snapshots/binding),58 tests actuales PASS.
  No causalidad probada del force-stop sobre creación nativa: acciones sin timestamps.
- Estado retenido `RUN-1791718684192-65783495695`, WS-024cc10c5e18649fad0c6567191aa18b,
  boot06254205-1db4-4aaa-8f4e-7123db10f851, `ACTIVE/PREFLIGHT`. Closeout read-only
  estable de33 paths, sin closure nativa observada. Ausencias/ps/force-stop no
  autorizan nuevo RUN. Resultado en `docs/wine-supervisor-server-isolation-results.md`.
- STOP de admisión: se propone contrato específico append-only de abandono por
  nuevo boot en `.agents/planning/done/wine-interrupted-run-disposition-plan.md`.
  APK61 consumió esa disposición y conservó el historial60; el nuevo intento
  falló antes del receipt por guard filesDir y quedó FAILED/UNKNOWN. No hay GO
  server. Resultado en docs/wine-interrupted-run-disposition-results.md;
   continuación específica ejecutada en .agents/planning/done/wine-prefix-prelaunch-continuation-plan.md.
   APK62 conserva el historial y llega a extraer el prefijo; falla en un directorio
   firmado0777 rechazado como raíz privada. Nuevo RUN-1791752157102-84597710465,
   WS-5df69c2d17d326d0d9e07042119b9988, FAILED/UNKNOWN y stage parcial conservado.
   Resultado en docs/wine-prefix-prelaunch-continuation-results.md. Guard corregido
   en fuente (publisher host5/JVM Prefix11), sin nuevo ELF/APK. Siguiente plan
   .agents/planning/wine-template-mode-continuation-plan.md está draft, sin reset
   genérico ni marcado retroactivo PASS/ECHILD. Todavía no hay GO server/client.
