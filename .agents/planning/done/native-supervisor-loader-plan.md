---
id: native-supervisor-loader-2026-10-09
state: done
date: 2026-10-09
---

## Summary

APK56 llegó a un RUN Java-owned pero salió2 antes de READY. Reproducción con sources
sin modificar confirma que `/proc/self/exe` identifica el cargador explícito y hace
fallar native_run_same_parent_fixture. UNKNOWN permanece en cuarentena durable.
Resultado y evidencia: `docs/native-run-supervisor-apk-results.md`.

Proponer candidato nativo nuevo, sin Wine: corregir esa comprobación sin eliminar
la frontera de fixture y separar el laboratorio versionado después de reboot.
El usuario delegó la continuación autónoma tras presentar esta propuesta: no se
borra ni rearma UNKNOWN silenciosamente; se ejecuta esta separación conservadora.

## Content

### Candidato y guard de carga

- Confirmar code57/`0.2.33-automated` disponible, mismo package/cert/ABI/min29/target36.
  Conservar APK56/55/33, sources/ELFs/evidencia histórica. No retag ni sobrescribir56.
- Supervisor PIE inmutable en nativeLibraryDir, argv/linker64 exactos. Sustituir
  autoridad self/exe por una prueba de **imagen realmente cargada**: localizar el
  módulo de una dirección propia/AT_PHDR usando APIs nativas apropiadas y cruzar
  path canónico, segmento, dev/inode y supervisor aprobado. argv[0], basename o
  auto-reporte solos no bastan. Java sigue validando hashes de supervisor/fixture.
- Fixture exacto aprobado, regular/no symlink, mismo directorio inmutable del
  supervisor real; nunca aceptar un hermano de linker64 ni path writable arbitrario.
  No fallback si no se puede demostrar. Registrar fallo previo a READY con motivo
  acotado, sin convertir error ni salida en prueba positiva.
- Reproducciones host direct/loader, fixture externo/symlink/nombre falso/argv falso;
  cross-build local y validación de segment/layout16KiB. No cambiar IPC de ownership
  para aceptar nonce/nombre como prueba, ni señales fuera de hijos propios.

### Estado del laboratorio: nueva versión, no recovery del RUN56

- Conservar intacto `files/forever/controls/native-supervisor/current.json` y el
  RUN UNKNOWN56 completo, tanto dispositivo como evidencia externa sellada.
- Nuevo store **native-supervisor-v2** para este candidato, no cambiar UNKNOWN a
  terminal, eliminarlo, desbloquearlo o reconstruir ECHILD. Nuevos RUN/nonce y parser
  versionado sin compartir writers con56. El registro histórico se sigue auditando.
- Primer control57 requiere installed hash auditado, preservación/hash estable del
  UNKNOWN56 conocido y **boot nuevo respecto al último control56**. La terminación
  del boot anterior, no un ps vacío/force-stop, separa los procesos de aquella prueba.
  No declarar cleanup del RUN56; simplemente no recuperar su autoridad.
- Cualquier UNKNOWN nuevo sigue bloqueando el nuevo store. No auto-reset ni cambio
  sucesivo de namespace para evitar fallos; diagnóstico/plan si no hay cierre probado.

### Verificación y controles

- Receta fresh/idempotent, pins y tuple del collector, audit y conservación nueva.
  Baseline runtime/WCP/PE/PanVK/noGL/compiler/client/catalog byte-identical a56.
- Tests C/Python/JVM dirigidos: guard loader, estado antiguo intacto, namespace/RUN
  cruzados rechazados, intención durable, deadline/jobs, cancelación y testigo.
  Revisión independiente de guard y frontera de persistencia antes del dispositivo.
- Gradle offline/JDK17/CPU2/1worker/Xmx4096m; assets rerun únicamente. Solo instalar
  candidato verificado en POCO serialDEVICE_SERIAL/UID10330, preflight/reboot.
- Controles físicos normal dos fases, cancel_before_start, cancel_fork, eof,
  invalid_protocol. Supervisor_failure sigue solo JVM. 15+5s app,60+10s host;
  consentimiento y jerarquía propia, sin input ajeno ni retry inline.
- GO exige READY prctl, Java/kernel/IPC ligados, adopción/reaping/ECHILD, salidas,
  testigo estable/Q/reaped, evidencia completa durable. Fallos no se convierten en
  PASS; UNKNOWN nuevo detiene controles. Solo API34 probado, no matriz29/16KiB real.

### No-goals

Sin Wine/XEnvironment/stop sweeps/prefix/CASC/login/memory/PE-identity/ptrace/root,
driver/DLL/shader/visual ni WowB. No borrar históricos, stage_delivery, commit o
publicación. Integración Wine requiere otro contrato después de GO nativo real.

## Implementation / Action Items

- [x] 1. Continuación autorizada por el usuario tras presentar el plan: «no tienes
  que estar preguntándome cada pequeño cambio que hagas. quiero que seas más autónomo.
  solo tienes que recordar cuál es nuestro objetivo». Se conserva UNKNOWN56.
- [x] 2. Implementar guard de imagen y namespace/pins/receta/collector, tests negativos.
- [x] 3. Revisión de seguridad y checks dirigidos, fresh overlay/build/audit/retención.
- [x] 4. Nuevo boot y control app-domain: UNKNOWN antes del launch por Os.access EACCES.
  No READY/cierre/testigo positivo; cancelación/faults no ejecutados después del bloqueo.
- [x] 5. Preservar NO-GO, límites e históricos intactos; cerrar y archivar plan.

## Open Issues

- Resultado57 en docs/native-supervisor-loader-results.md. Boot66f3530d…,
  RUNNS-b0a3fe48f925d7d656f597f740b4b413 UNKNOWN/nativeExitnull/identities[];
  la resolución de artefactos falla antes de startProcess. No otro control ni reset.
- Fix fuente Os.access: únicamente EACCES es write denial; true/otros errno rechazan.
  Dos tests JVM nuevos PASS; fuente posterior no está en el APK57 retenido/instalado.
- Preflight de migración exige boot NUEVO diferente del previo y de56; permitir que
  el previo sea56 es necesario y no debilita reboot. Corrección6 tests dirigidos PASS.
- Current v2 exacto73428e2c…c57244d y v1c9c899f5…64685c estables; cierre solo lectura
  suplemental sellado sin nuevos stop/input. Nueva propuesta de recuperación exacta
  .agents/planning/done/native-prelaunch-disposition-plan.md, no namespace nuevo ni recovery
  ya autorizado. Mantener ambos UNKNOWN. No afirmación ECHILD retroactiva.

- Candidato retenido57: APK370931339 SHA8d0c0a86b1ca1571736e399d08f9ae7e36b9b7ec2f6b73075867c0a5fb747572;
  supervisor30344/fd8727…7385c, fixture12784/7f1b6d…e67f. JVM dirigido24PASS,
  fresh/idempotent96PASS, build y audit PASS. Comparación56:161 miembros idénticos,
  solo supervisor/manifest/provenance cambian. Tests nativos ejecutados por rondas.

- Interfaz congelada para builders: output externo native-run-supervisor-v2,
  manifest/store variant native-run-supervisor-v2, store nativo native-supervisor-v2,
  code57/0.2.33-automated. IPC schema1, NS/nonce, nombres ELF y escenarios sin cambio.
  Pins nuevos se propagan solo después del cross-build, no aceptan placeholders.

- Método exacto de imagen propia debe probarse bajo bionic explícito; no asumir que
  dladdr/AT_PHDR siempre devuelve path suficiente. Si no hay evidencia robusta,
  parar antes de empaquetar, no eliminar la guard.
- No se ha medido prctl desde APK:56 salió antes. Ningún microtest run-as sustituye
  ese resultado. Tampoco está probada la cancelación física del nuevo supervisor.
- Variante nueva del store es solo laboratorio aprobado si el usuario acepta;
  no es una política de recuperación general ni de producto ante crash.
