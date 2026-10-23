---
id: wine-runtime-ownership-2026-10-09
state: done
date: 2026-10-09
---

## Summary

Readiness y cobertura del collector ya funcionan. El último control conserva
candidato14064 UID10330/startTicks6831/PPID1 con PanVK privado RX ligado por
RUN/dev/inode/hash, pero PE anónimo y sin ascendencia al rootWine13409. Ver
`docs/wine-aa64-start-readiness-results.md`. Más controles idénticos no resuelven
esta incompatibilidad. El objetivo es que el launcher posea y supervise de verdad
el proceso que después será WoW, no aceptar evidencia incompleta para pasar un gate.

Decisión propuesta: validar un supervisor nativo **por RUN**, no global de app,
antes de invertir en integración y cambios del contrato de identidad. Este plan
requiere aprobación arquitectónica; permiso ilimitado de pruebas no rebaja proof.

## Content

### Etapa A — Capacidad kernel, sin runtime/Wine

- Nuevo microcontrol C/receta/runner/tests bajo
  `scripts/diagnostics/android-process/` y `tests/test_android_run_supervisor*.py`.
  Binarios/logs externos al repo, toolchain local sin descargas. AArch64 PIE/16KiB.
- Solo POCO xaga serialDEVICE_SERIAL/API34 y run-as package/UID10330. Preflight
  exacto y reboot. Runner entrega fichero privado de control bajo ownfiles y lo
  ejecuta por linker64 como los controles bionic existentes; no install/clear-data.
- Microtest se declara child-subreaper, comprueba GET_CHILD_SUBREAPER, crea un
  double-fork propio y valida que el huérfano queda con PPID del supervisor, no1;
  IPC acotado, records PID/startTicks/UID/PPID, exit codes y waitpid hasta ECHILD.
- Deadline total acotado (propuesta15s+cleanup5s) y una ejecución por invocación.
  Solo hijos propios conocidos, identidades validadas, sin barrido UID, nombres,
  process groups ajenos ni ptrace. Ningún child indefinido sin owner/reserva cleanup.
  Un proceso testigo propio separado debe permanecer intacto durante cleanup.
- Resultados PASS/FAIL/UNKNOWN según hechos; seccomp/SELinux/prctl no soportado
  produce NO-GO, no bypass/root. Guardar metadata propia, no UI ni procesos ajenos.

### Etapa B — Solo diseño después del GO de capacidad

- Diseñar wrapper que permanezca vivo, subreaper, fork/exec del comando Wine exacto,
  IPC/status por RUN, Java `Process` del supervisor, reaping y stop acotado.
  Aislar prefijo/wineserver del RUN; no adoptar procesos preexistentes.
- Resolver seam `BionicProgramLauncherComponent.execGuestProgram()`/`stop()` y
  `ProcessHelper.exec(...)` con observer de `ForeverProcessDiagnostics`.
  La integración debe evitar stop global por UID/wineserver-k genérico; no cambiar
  esos componentes en esta etapa de capacidad. Revisar el overlay efectivo antes
  de afirmar qué paths de cleanup ejecuta Forever actualmente.
- Elaborar prueba independiente de identidad del PE anónimo, no por base0x140 ni
  nombre/hash de archivo solamente. Opciones a evaluar: bridge nativo de proceso
  + nonce ligado al supervisor y/o lectura acotada de secciones inmutables del PE
  desde proceso efectivamente poseído, con relocaciones/manifest versionado.
- `GetCurrentProcessId` de Win32 no es Unix PID. Auto-reporte por sí solo no cambia
  política de escritura/ownership. No relajar el analizador ni habilitar patch/auth.
- Escribir plan de integración/contrato separado con decisión y tests concretos.
  **No se construye otro APK ni se envuelve Wine bajo este plan de capacidad.**

### No-goals

Sin cambios del PE55/compiler/PanVK/DXVK/driver/shaders/culling/pausa, no visualROI,
Explorer A/B, cliente/CASC vivo, descargas, cuentas/login/parcheo, root, ptrace,
limpieza global ni señales por UID. Sin publicar ni alterar entrega normal code33.

## Implementation / Action Items

- [x] 1. Aprobar validación de capacidad y diseño de supervisor por RUN.
  Usuario: «si, hagamoslo». Se mantiene la autorización previa de repetir pruebas
  diagnosticadas; no se amplía esta etapa a Wine ni a integración del APK.
- [x] 2. Implementar microcontrol/receta/runner con ownership IPC y cleanup acotados;
  reproducción host cuando posible, tests de errores/prctl/fork/reaping/deadlines.
- [x] 3. Cross-build local, revisar frontera de ownership, validar permisos y pins.
- [x] 4. Ejecutar microcontrol no-root en POCO; repetir solo tras diagnóstico,
  con evidencia separada, reboot y deadline por control.
- [x] 5. Clasificar capacidad GO/NO-GO, preservar hashes/evidencia; si GO documentar
  propuesta concreta de integración + identidad PE para aprobación contractual.

## Open Issues

- Implementación: revisión detectó errores en rutas de cleanup y proof. Se
  reevaluó la entrega y se separó la corrección nativa de la validación Python.
  Primer build llevado al dispositivo: 31960 bytes, SHA256
  `ef9664e85ab9e52ba4dd542c4066c01c9edf3cd4badadee4904111072443299c`.
  GO de revisión exclusivamente para microcontrol normal aislado. 19 tests del
  runner pasaron en la primera ronda final; dos expectativas de fault se
  corrigieron a UNKNOWN conservador y ambos subtests pasaron después. Cinco
  tests nativos adicionales pasaron; no es una suite global ni se suman rondas.
  Después se diagnosticaron dos carreras físicas (PPID post-READY y Q/TERM del
  testigo), reproducidas en host y corregidas. Build final32144 bytes/SHA
  `519bf45e260940eda6f5a8611b182bffec65e95f57c8840795b4c074b2745761`, schema2.
  Suite final28 PASS en60.69s, sin sumar rondas. Revisión final GO normal aislado.
  Tercer control `RS-f22a4b8fa2bb4a418ab142d4e8b58186`: native/runner PASS, PRCTL
  root/supervisor0/0/1, adopción, exits41/42/43/143, ECHILD ambos y testigo exit0.
  Los tres ELF originales fueron retenidos y11archivos sellados por evidencia.
  Ver `docs/wine-runtime-ownership-capacity-results.md`.
- Residuales no bloqueantes del control normal: el testigo tiene watchdog de
  10s frente al presupuesto raíz de 15s+5s, y un flag de reaping en un camino de
  fault no se actualiza al terminar correctamente. Pueden producir UNKNOWN
  conservador; no autorizan PASS ni señales ajenas. La ejecución observada y los
  hechos ECHILD siguen siendo obligatorios para el GO de capacidad.

- Permiso PR_SET_CHILD_SUBREAPER/adopción/reaping probado en microcontrol API34
  por run-as UID10330; no equivale al dominio/política del APK normal.
  Semántica Linux de subreaper no significa reaping/terminación automáticos.
- El microtest sin Wine no demuestra aún adopción del wineserver ni lifecycle Wine.
- Binario diagnóstico desde ownfiles/linker64 validado UID4=10330 sin elevar UID.
- PE anónimo requiere identidad independiente: supervisor no la soluciona por sí
  solo. Readback y PanVK parcial tampoco prueban visual ni funcionamiento de WoW.
- Packaging futuro de supervisor PIE en APK (nombre `.so` no lo hace JNI),16KiB,
  callback/IPC/cancelación y protección PID reuse necesitan contrato nuevo.
- StageB de diseño completada en `docs/wine-run-supervisor-design.md`, con seams
  efectivos y sweeps del runtime identificados. No se integró ni arrancó Wine.
  La propuesta posterior requirió y recibió aprobación; está cerrada en
  `.agents/planning/done/native-run-supervisor-apk-plan.md`: APK56 UNKNOWN antes de
  READY. Resultado docs/native-run-supervisor-apk-results.md; nuevo draft loader
  separado, sin Wine ni continuidad implícita del microcontrol.
