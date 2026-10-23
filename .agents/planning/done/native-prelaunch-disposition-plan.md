---
id: native-prelaunch-disposition-2026-10-09
state: done
date: 2026-10-09
---

## Summary

APK57 falla en la resolución de artefactos Java con EACCES esperado antes de
startProcess. La corrección localizada está probada, pero UNKNOWNv2 bloquea
controles. Proponer recuperación explícita del único prelaunch conocido, sin
borrar historia ni cambiar namespace para esquivar fallos. Objetivo: demostrar
supervisor nativo real antes de integrarlo con Wine y finalmente WoW Forever.

Resultado: `docs/native-supervisor-loader-results.md`.
Contrato aprobado por el usuario: «si continua. se más autónomo para avanzar».
Operación cerrada: APK59 obtuvo GO del control nativo normal y CANCELLED
verificado en ambas cancelaciones. EOF terminó INVALID/UNKNOWN y conserva su
cuarentena. Resultado: `docs/native-prelaunch-disposition-results.md`.

## Content

### Admisión exacta y conservación

- Mantener native-supervisor-v2, IPCschema1 y NS/nonce. No v3, reset, cambios
  UNKNOWN→PASS/CANCELLED, ni inferir ECHILD por ausencia de procesos o reboot.
- Admitir exclusivamente NS-b0a3fe48f925d7d656f597f740b4b413 bajo APK57 exacto
  SHA8d0c0a86b1ca1571736e399d08f9ae7e36b9b7ec2f6b73075867c0a5fb747572,
  source/manifest/provenance y evidencia sellada de esta operación.
- Exigir current v2 SHA73428e2c77bdeafc5687a5860821b67b7f1489966f1cae62d04a12318c57244d,
  run/events/streams originales con hashes independientes congelados ANTES del
  código de admisión, nonce exacto y fallo EACCES en la etapa previa al launch.
  Cualquier diferencia, registro incompleto o resultado ambiguo STOP.
- Añadir disposición append-only PRELAUNCH_ABORT, distinta del status original:
  current/run/event/stream/seal/source/APK/boot pins, causa, actor/versión,
  proceso-creación NOT_REACHED basado en source exacto (no en un PS vacío).
  Nuevo boot distinto de66f3530d… y preserve v1 UNKNOWN56/hash conocido.
- Disposición y su consumo durable forman una transacción monitor/store única;
  consumed receipt ligado al nuevo RUN. Mantener originales y archivo byte-exacto
  del current anterior. Crash entre etapas no autoriza un segundo consumo ni otro
  RUN. No archivo privado genérico que el host pueda rellenar para rehabilitar
  cualquier UNKNOWN; parser y admisión cerrados al caso exacto.
- Auditoría debe distinguir esta autorización histórica explícita de una prueba
  runtime de cleanup. UNKNOWN57 conserva echild:false y witness UNPROVEN.

### Futuras etapas y candidato

- Antes de la primera operación que pueda crear un proceso, registrar durablemente
  PROCESS_CREATE_INTENT; crash/submisión ambigua permanece UNKNOWN bloqueado.
  Validación de artefactos previa a esa intención no equivale a intento nativo.
  Diseñar clasificación PRELAUNCH_ABORT futura por hechos explícitos, no heurística.
- Aplicar corrección Os.access ya probada: solo EACCES acepta write denial.
  No alterar guard C de imagen, hashes, immutable fixture ni señales.
- Nuevo candidato58/0.2.34-automated si disponible; no sobrescribir57/56.
  Mismo package/cert/min29target36, native-only. Conservar mismo ELF/variant v2,
  cambios solo política/control de persistencia y receipt/collector versionado.
- Validar recuperación/consumo contra copia de la evidencia en host/JVM antes de
  tocar dispositivo. Overlay fresh/idempotent, pinned audit, retención y comparación
  assets/lib: todo runtime/PE/driver/client byte-idéntico a57.
- Install own exact APK auditado, reboot nuevo/UID/device/historia guardados, UI
  propia/consentimiento. Primero normal dos fases; después cancel_before_start y
  cancel_fork si normal positivo. EOF/invalid_protocol al final; UNKNOWN nuevo
  STOP, sin recuperación genérica o nuevo namespace. No supervisor_failure físico.
- Mantener15+5s app,60+10s host, una intención START por invocación y evidencia
  privada acotada. No repetir sin diagnóstico. GO solo READY/prctl/identidades,
  adopción/reaping/ECHILD/salidas/testigo/Q/durabilidad reales.

### No-goals

Sin Wine/XEnvironment/prefix/compiler/DLL/driver/shader/PE-memoria/login/patcher,
root/ptrace/foreign UI/signals ajenas, barridos UID, publicación ni commits.
No v1 recovery, ningún historial eliminado o reetiquetado. Integración Wine y
prueba PE anónima siguen contratos separados después de GO nativo.

## Implementation / Action Items

- [x] 1. Aprobar el contrato material de disposición exacta y consumo único.
- [x] 2. Congelar pins de evidencia y diseñar transición durable/etapas/consumo.
- [x] 3. Implementar recuperación limitada, marcadores y tests negativos/de crash.
- [x] 4. Revisión independiente de admisión/seguridad y checks JVM/Python dirigidos.
- [x] 5. Build/audit/fresh/retención de58 y59, comparación de baselines y preservación histórica.
- [x] 6. Controles con boot nuevo, GO normal, dos CANCELLED, EOF INVALID/UNKNOWN,
  conservación de seis resultados originales y cierre RO sellado. STOP antes de
  invalid_protocol por el nuevo UNKNOWN; no recuperación adicional.

## Open Issues

- Candidato actual59/0.2.35-automated retenido: 370965684 bytes,
  SHAb8cace99ded43adb1841a2047f3802976b4b2d113aa3c5a67091bdd7270d2435.
  Frozen contrato2646 bytes/SHA423ed396ac54c9caa5edf43c06ec645dc5b68275928003c064729b85ecb44c62.
  JVMDisposition7/7 actual; Python94/94 y nuevo caso no-follow positivo (3 ramas).
  Fresh99 inputs, build/audit positivos, retención exclusiva y comparación58→59:
  165 miembros, 163 idénticos y solo disposición/provenance cambiadas. Nativos y
   runtime intactos. APK58 nunca instalado; APK59 instalado y probado después de
   nuevos preflights y reboots independientes.

- La preparación del primer APK58 (a29a009b…, retenido y nunca instalado) falló
  ANTES de install/reboot: los cuatro streams del prelaunch57 no existen en el
  dispositivo; el collector había archivado placeholders vacíos. Diagnóstico RO
  exacto verificó ENOENT en los cuatro. No crear ficheros para satisfacer el guard.
  Se conserva esa ausencia explícitamente en el contrato congelado y receipts,
  rechazando incluso un fichero vacío añadido. Nuevo candidato59 para no sobrescribir
  el APK58 retenido; mismo native v2 y autorización histórica, sin otra recuperación.

- Verificación actual: JVM 30/31 positivos en la primera ronda; el único fallo
  era la expectativa incorrecta del test que exigía consumption antes de crearlo.
  Corregido y test específico positivo (sin cambiar producción). Python histórico
  7/7 positivo; fresh/idempotent overlay 99 inputs positivo. Revisión independiente
  no encontró P1/P2 en admisión, cross-binding ni cuarentena. No full-suite claim.

- Pins congelados antes de implementar en
  `scripts/diagnostics/android-process/NATIVE-PRELAUNCH-DISPOSITION.json`.
  El current privado coincide byte a byte con run.json (380 bytes); el current
  capturado inicialmente por el collector es otro snapshot y no se usa para
  autorizar admisión. Consumo reservado durablemente ANTES de cambiar current;
  cualquier interrupción bloquea reutilización, sin recuperación automática.

- El RUN57 no tenía un marker durable PROCESS_CREATE_INTENT. Su admisión histórica
  necesita el source/APK exacto y aprobación de este contrato; no crear retroactivamente
  una afirmación runtime inexistente. Eventos vacíos/nonce/nullExit no bastan.
- La disposición consumida debe autorizar begin sin una ventana de doble admisión;
  fallo de fsync/rename/cancel/no coincidencia mantiene bloqueo.
- La corrección EACCES y prctl app-domain están probados en API34. Normal GO
  (`7e84cca3...`), cancel_before_start CANCELLED (`00236a6b...`) y cancel_fork
  CANCELLED (`829e2758...`) verifican testigo/Q, exit0 y ECHILD. API29 y un
  dispositivo real con páginas16KiB no fueron probados.
- Dos primeros resultados globales UNKNOWN eran negativos del collector: orden
  de validación de receipts tras la admisión y orden EXIT→ADOPT→EXIT del cleanup.
  Se diagnosticaron/corrigieron solo en host y se repitieron con nuevo boot/RUN;
  sus result.json/seal.json originales NO se reescribieron.
- EOF `NS-1951490a6a0bc710f0ea3e219f582e46` termina app UNKNOWN, exit2, echild:false.
  El testigo tiene prueba positiva, pero el journal no contiene prueba completa de
  la familia. Se mantiene current SHAf067e510b36e37e4c7e19c6c0163d36f125c5faa1f7335538271de474cd388d9
  y boot9aaa217e-168e-468e-b5cd-970345b84370. Invalid_protocol físico no ejecutado;
  no más admisión v2, reset o cambio de namespace para repetir esta fixture.
- Cierre suplementario RO de seis controles: originales sellados intactos,
  compiler/backup/v1/old57 conservados; snapshot UID no demuestra quiescencia.
  Integración Wine sigue pendiente de otro contrato:
  `.agents/planning/wine-supervisor-handoff-plan.md`.
