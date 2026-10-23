---
id: native-run-supervisor-apk-2026-10-09
state: done
date: 2026-10-09
---

## Summary

El microcontrol aislado de subreaper pasó en API34/UID10330, pero se ejecutó por
run-as y no prueba Java-owned Process, packaging APK, IPC ni cancelación real.
`docs/wine-runtime-ownership-capacity-results.md` conserva los tres controles;
`docs/wine-run-supervisor-design.md` concreta Stage B.

Propuesta de siguiente operación: supervisor persistente nativo empaquetado y
poseído por Java en **nuevo APK de laboratorio**, con child nativo inocuo, sin Wine.
No reutilizar el orquestador safety-net como producto ni activar subreaper global.
Permiso ilimitado de controles diagnosticados no implica aprobación de esta nueva
integración APK/contrato IPC. Requiere aprobación antes de modificar código/build.

## Content

### Alcance y archivos

- Nuevo código/receta nativos bajo `scripts/diagnostics/android-process/`, separado
  del microtest histórico; supervisor y child fixture ELF PIE AA64/16KiB.
- Overlay Kotlin del controlador/estado/actividad nativa de laboratorio bajo
  `scripts/arm64-prototype/app/overlay/`, preparación del APK, assets/provenance/
  auditoría y selección host versionadas. No tocar checkout externo a mano: receta
  fresh/idempotent y paths exclusivos de builders con interfaz definida primero.
- Candidato propuesto code56/`0.2.32-automated`, mismo package/cert; confirmar antes
  del build que no existe otro candidato56. Conservar APK55/hash exactos y entrega33.
- Flags de laboratorio excluyentes: ejecución nativa únicamente; no abrir runtime,
  XEnvironment, preinstall, Wineboot, prefix, compiler repair, auth ni memory patch.
  No alterar comportamiento de los anteriores tuples ni default normal delivery.
- `nativeLibraryDir`/extracción auditada, linker64 con argv array y ELF/provenance
  pinneados. Si app-domain no permite ejecución/prctl: NO-GO sin bypass ni ownfiles
  fallback. No descargar runtime/toolchain ni cambiar min29/target36/ABI/cert.

### Contrato propuesto, limitado a fixtures nativos

- Java-held Process del supervisor, RUN/nonce nuevo, registro durable de intent.
  READY versionado con identidad UID4/PID/startTicks antes de cualquier START.
- Pipes stdin/stdout del Process, líneas JSON acotadas (8KiB/linea,64KiB total por
  stream); claves únicas, secuencia monotónica/RUN/nonce/phase y schemas cerrados.
  No socket compartido, shell command ni API de argv/env arbitrarios.
- START selecciona solo fixture pinneado/scenario allowlisted; supervisor fork/exec
  exacto y permanece vivo. Eventos READY/SPAWN/ADOPT/EXIT/PHASE_DONE/RUN_DONE/ERROR.
  Java registra supervisor separado del child y cruza eventos con kernel identity.
- Stop cooperativo, gracia acotada y TERM/KILL solo hijos directos/adoptados propios
  aún no reaped, con UID4/startTicks/PPID revalidados. Descendiente no directo sin
  handle kernel seguro => no señal por PID. No sweep UID/nombre/group/wineserver.
- Un testigo Java-owned separado debe permanecer estable durante cleanup de la
  rama del supervisor y salir normalmente después, con prueba exit/reaped.
- CLOSE/EOF/cancelación/respuesta inválida implican estado explícito; RUN_DONE
  requiere hijos resueltos+ECHILD y supervisor exit0. Supervisor muerto/timeout/
  cleanup incompleto => UNKNOWN, nunca reclamar que no quedaron hijos.
- Inicio/cancelación linealizados: cancel antes de START no lanza; durante spawn
  retiene ownership y resuelve cleanup; callback tardío no cambia otro RUN. No
  fuerza bruta Process.destroy/PID suelto como sustituto de cierre confirmado.
- Por control nativo: presupuesto app15s+cleanup5s; host<=60s+cleanup10s. TTL de
  fixtures debe cubrir el presupuesto completo+gracia, no repetir el watchdog10s
  del microtest. Deadline vencido rechaza resultado tardío. Límites técnicos no
  limitan el número de controles diagnosticados autorizados por el usuario.

### Verificación y dispositivo

- Tests C de protocolo/fork/adopción/reaping/salida/cancelación/EOF/faults; parser
  negativos duplicatekey, nonce/sequence/UID/PPID/startTicks, PID reuse, overflow,
  respuesta tardía, intento de comando fuera de allowlist. Sin señales a foreignUID.
- Tests JVM dirigidos de owner/intent/cancelación/fases/callback tardío/pipe failure;
  testigo de otra rama no recibe señales. Revisión independiente de seguridad.
- Cross-build local; overlay fresh/idempotent; Gradle offline/JDK17/1worker/CPU2/
  Xmx4096m, rerun solo mergeAssets. Auditoría nueva y preservación independiente
  APK/ELFs/manifest; comparación55 que demuestra runtime/PE/driver/client unchanged.
- POCO exacto serialDEVICE_SERIAL/xaga/22041216G/Xiaomi/API34, UID10330. Instalar
  solo candidato verificado, nuevo boot, entrada propia con readiness/consent. No
  dumps/capturas de UI ajena, bypass de lockscreen ni root.
- Controles separados: normal dos fases, cancelación antes de START y durante
  double-fork, EOF/protocolo inválido y supervisor inesperadamente terminado en
  fixture finito. Sin repetición inline; diagnóstico/evidencia/reboot antes de
  repetir. Detenerse si no se puede probar cleanup o aislamiento del testigo.
- Evidence externa privada fsync, source/pins antes de cada control; conservar
  FAIL/UNKNOWN, no convertir ausencia muestreada en kernel containment.

### No-goals y gate siguiente

No envolver Wine, no tocar GameNative stop/sweeps, prefijo/CASC vivo ni analyzer.
Sin PE-identity/lectura memoria/ptrace, driver/DLL/shader/hold/Explorer/visualROI,
cuentas/login/parcheo ni WowB.exe. Sin publicación ni stagedelivery/commits.
GO solo Java/native/packaging/IPC/cancelación en fixtures. Después otro plan deberá
integrar Wine y aislar wineserver/prefix, cubrir teardown managed efectivo y probar
identidad PE anónimo; este plan no los autoriza condicionalmente.

## Implementation / Action Items

- [x] 1. Aprobar alcance APK nativo y contrato IPC/cancelación propuestos: usuario
  «sigue porfavor». Contrato concreto congelado en
  `scripts/diagnostics/android-process/NATIVE-SUPERVISOR-IPC.md` antes de builders.
- [x] 2. Resolver contrato antes de builders; implementar supervisor/fixture y
  controlador Java nativo excluyente con pins/tuple/auditoría, sin Wine.
- [x] 3. Tests dirigidos C/Python/JVM y revisión independiente de ownership:
  23 casos JVM resueltos por rondas (21 en el filtro completo, dos negativos luego);
  collector 35 casos más tres nuevos negativos dirigidos. Receta final 96 inputs
  fresh/idempotent; revisión GO condicional solo fixtures, pendiente APK/audit.
- [x] 4. Build/overlay/audit/preservación: APK56 SHA aff5f8a8…e09ae6c, auditoría
  PASS y copia independiente; comparación55: 160 assets/libs idénticos, tres nuevos
  y solo provenance cambiado. Sin removals ni publicación.
- [x] 5. Controles físicos diagnosticados: entrada corregida hasta primer RUN56,
  UNKNOWN/nativeExit2 antes de READY, sin prueba de cierre/testigo. NO-GO, no
  ejecución de cancelación/faults posterior ni rearme del UNKNOWN.
- [x] 6. Resultado en docs/native-run-supervisor-apk-results.md, evidencia conservada
  y plan archivado. Nueva propuesta loader/store versionado separada, sin Wine.

## Open Issues

- Resultado final: NS-30823b6b5acd81b24830be89b809141d, boot3370c8dc…fbd,
  APK56 aff5…ae6c. Supervisor salió2 antes de READY, solo intent RUN; UNKNOWN
  durable retenido, echild=false, witnessUNPROVEN. No GO para app-domain ownership.
- Defecto reproducido con source sin modificar: ejecución por cargador conserva
  self/exe del loader y falla guard de basename supervisor. Host directo emite
  READY; loader stdout vacío/exit2. No implica prctl denegado ni soporte Wine.
- Host collector final63 tests PASS; wake/keyguard verificados por estado exacto,
  Tab inicial solo con jerarquía propia y controles iniciales conocidos; botones
  Android ALLCAPS cubiertos. Ninguna corrección altera APK56 retenido.
- Cierre sellado más aclaración de desviación: helper asignado solo lectura repitió
  force-stop del paquete propio después de recoger evidencia. Histórico intacto;
  no usar ese acto ni ps vacío como contención. No reset ni nuevo RUN.
- Nuevo candidato con guard loader y store de laboratorio separado ejecutado en
  .agents/planning/done/native-supervisor-loader-plan.md; APK57 NO-GO por política
  previa al launch. Ver docs/native-supervisor-loader-results.md. Desconocido56
  no se recupera mediante cambios silenciosos del store.

- Preflight real: dumpsys Android muestra hash corto de Signature, no SHA256 del
  certificado. El collector liga la firma auditada mediante SHA256 exacto de
  base.apk instalado, además de package/tuple/UID/boot; no aceptar hash distinto.
- Primera validación del collector abortó antes de Activity/HID/RUN: exec-out
  devuelve rc0 y mezcla el error de head por current.json inexistente en stdout.
  Reproducido en el POCO; lectura privada usa shell-v2 sin PTY con status/stderr
  separados. Inexistencia exacta es missing; permisos/errores son unreadable.

- Recuperación conservadora: UNKNOWN sigue en cuarentena durable incluso cuando
  terminan jobs Java; eso no prueba ECHILD de todos los descendientes. start debe
  devolver false, sin excepción ni procesos, si el store bloquea el RUN anterior.
  No rearmar automáticamente controles UNKNOWN mediante una relajación del store.
- Validación host: receta fresca/idempotente equivalente en 96 inputs. Tests JVM
  resueltos por rondas dirigidas (16 casos positivos y el negativo PPID reparado
  por separado), no suite completa. Fallos de modelo corregidos sin relajar IPC:
  PID/UID positivos en Robolectric, testigo estable/Q/reap también en cancelación,
  STOP(1) entre fases como en el C real; fallo de testigo siempre UNKNOWN.
- Revisión final detectó lifecycle Java pendiente antes de APK/dispositivo:
  conservar y drenar readers antes de persistir terminal; arranque asíncrono con
  handoff acotado sin mantener monitor y ownership retenido para Process tardío;
  writer timeout envenena/cierra el canal, nunca permite comandos posteriores.
  La finalización real de jobs, no Future.cancel/isDone, debe controlar release.
  Corregir dentro de este contrato aprobado; no ampliar Wine ni señales por UID.
- Revisión de la reparación: separar readers de supervisor/testigo; no esperar
  EOF del testigo antes de Q. Release además exige terminal duradero confirmado,
  no una marca colocada antes de store.finish. Supervisor_failure que destruye el
  root después de SPAWN queda exclusivamente host/JVM, bloqueado en controlador
  real y collector físico; no admitir huérfanos por una TTL como prueba de cierre.
  El control físico queda reducido conservadoramente a los otros cinco escenarios.
- Aprobación recibida para esta operación nativa exclusiva. Conserva el permiso
  anterior para repetir controles diagnosticados; no autoriza integrar Wine.
- Integración: cancel_fork se mide después de SPAWN del grandchild verificado y
  antes de su ACK. ADOPT/EXIT durante cleanup son hechos del owner nativo poseído,
  ligados al nacimiento ya verificado; no inventar un /proc vivo tras waitpid.
  Cancelación anterior sin transcript completo queda UNKNOWN. Contrato IPC aclara
  esta frontera; el test JVM debe reproducir el transcript C real, no uno más fuerte.

- Capacidad bajo run-as no prueba política SELinux/seccomp del APK ni empaquetado
  ejecutable. nativeLibraryDir/extracción y PIE .so requieren prueba, no suposición.
- Protocolo IPC de este plan es propuesto; deberá congelarse y revisarse antes del
  código. La autoridad nace del Process/canal/kernel, no del nonce auto-reportado.
- Se conserva el fixture histórico y ambos fallos diagnosticados; no corregirlos
  retrospectivamente ni incorporar sus flags/TTL defectuosos al nuevo supervisor.
- Wine comparte prefix/home y puede conectar con daemon preexistente; StageB lo
  identifica pero no decide una migración/clonado del prefix en esta operación.
- PE anónimo/lectura permitida e identidad criptográfica son incertidumbres
  independientes. No modificar el analizador para declarar PASS por ownership.
- APIs29/16KiB: build/static audit no equivale a ejecución en esos dispositivos;
  prueba física inicialmente solo API34 xaga. No afirmar matriz completa.
