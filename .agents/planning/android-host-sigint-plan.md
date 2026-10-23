---
id: android-host-sigint-2026-10-01
state: in-progress
date: 2026-10-01
---

## Summary

El usuario probó `0.2.4-automated` / code 28: negro con controles y, sin
minimizar, cierre al **home de Android**. Dejó el informe
`forever-RUN-1790893361326-187779452318.zip` en `delivery/`.

La evidencia identifica una terminación del **host Android por SIGINT (2)**,
no una mera salida de Wine ni un SIGSEGV demostrado. Falta identificar el
emisor. Se propone un ensayo integrado que conserve el final de la traza y
registre el origen de esa señal, sin cambiar runtime/driver ni bloquear señales.
La instrumentación de señales afecta al proceso Android y requiere aprobación
explícita y revisión independiente antes de implementarla.

Continúa el objetivo de `automated-arm64-launcher-plan.md` / US-003: una sola
APK y una sola acción para intentar WoW Forever. No hay tres sondas manuales,
ADB, root ni un nuevo runtime. Este plan no declara go de ejecución o login.

## Content

### Evidencia confirmada

Informe protegido, fuera del repo:

```text
/home/adrian/wowforever-artifacts/delivery/forever-RUN-1790893361326-187779452318.zip
SHA-256: 4892cd08b427bfc9b75e8c2c93820caa2e2f494860e094107b1d504db89aabfb
```

| Observación | Resultado y límite |
|---|---|
| APK | Provenance code 28, `0.2.4-automated`, package `com.wowforever.arm64probe`; inputs corresponden al candidato entregado. |
| Host original | PID 6424; el nuevo inicio es PID 12686. |
| Wine | PID 10903, startTicks 24718, spawn `1790893420814`; inicialización de Wine/wineboot/services observada. |
| Salida Android | `ApplicationExitInfo`: reason 2 / status 2, PID 6424, mismo package, resumen único de RUN y tiempo coincidentes. Timestamp `1790893447504`: 26,690 s después del spawn. |
| Recuperación | Corrida `INTERRUPTED`, `supervision_lost_reboot_required`, terminación desconocida; no se ha iniciado automáticamente otro Wine. |
| Corrección de package | Ambos aliases apuntan a Forever; redirecciones del stderr apuntan a su ImageFS. SHM, evshim y WX son ELF AA64 con hashes registrados. El defecto anterior está corregido. |
| WoW | 45 descubrimientos sin candidato validado, gamePid null, render/login no verificados. No demuestra ausencia absoluta del proceso. |
| Logs del juego | Índice exportado vacío; el filtro del exportador no permite afirmar que no exista ningún log fuera de ese conjunto. |
| Captura stderr | Exactamente 524.288 bytes. El último texto conservado es del spawn +11,834 s, dejando 14,856 s sin stderr retenido antes de la señal. El pipe sí sigue drenándose. |
| Helper | El preflight revisó 12 rutas sin encontrarlo, pero su orden agotó el cupo antes de los candidatos ImageFS. No prueba que `winhandler.exe` no exista o falle. |

La inspección de código no encontró una ruta que envíe SIGINT al host: el
cleanup Java observado usa SIGTERM/SIGKILL y procesos hijos. Una señal externa
o dirigida a un grupo es una **hipótesis**, no causalidad demostrada. El launch
actual usa `Runtime.exec` sin una sesión explícita propia; los PGID/SID reales
de esta corrida no están en el informe.

### Decisiones y límites

- Mantener Wine/Proton, DXVK AA64, Vulkan del sistema, ImageFS y preloads actuales.
  No Turnip, no no-OpenGL shim, no sustitución del blob WX, no bypass del helper.
- No ignorar SIGINT, no convertirla en éxito ni atribuirla a Wine por proximidad.
  La acción previa de la señal se conserva: la captura no es un mecanismo para
  impedir el cierre ni para anular cancelaciones legítimas.
- No introducir todavía `setsid`, grupos de procesos nuevos, servicio foreground
  o containment. Primero identificar el emisor y el alcance observado.
- Capturar únicamente metadata de señal/proceso: PID/TID/UID/emisor/si_code,
  tiempo y vínculo con RUN/boot. No memoria del juego, credenciales ni cmdlines
  completos. Un emisor desconocido/kernel sigue siendo desconocido.
- Conservar checks del patcher, identidad/ancestría/startTicks y el límite de
  escritura del grupo 8. La instrumentación es independiente de su núcleo.
- Mantener la política de un intento tras spawn y reinicio Android antes del
  siguiente. Ninguna metadata de PGID/SID prueba por sí sola quiescencia completa.
- No descargar nuevos runtimes, NDK, cliente completo ni archivos CASC. Usar JDK,
  NDK 24 y targets Rust existentes; binarios y evidencias quedan fuera del repo.
- No eliminar más artefactos. Conservar code 28, ambos informes y la caché válida.

### Cambio propuesto

1. **Final de logs utilizable:** sustituir la retención solo inicial por una
   política acotada de inicio + cola reciente, con snapshots persistidos durante
   la ejecución, sin depender de un callback final que no ocurre si muere Android.
   Mantener el drenaje continuo, límites de memoria/archivo y evidencia explícita
   de truncación. Reducir el ruido `trace:module` sin perder errores/SEH/procesos.
2. **Identidad de grupo:** registrar PGID/SID del host y raíz propia desde `/proc`
   con revalidación de PID/startTicks; mantener UNKNOWN ante datos ilegibles.
   No señalizar ningún grupo ni usar esos campos para ampliar la autorización JNI.
3. **Origen de SIGINT:** añadir un adaptador nativo pequeño que escriba una
   evidencia fija y acotada usando operaciones async-signal-safe sobre un destino
   privado ya abierto. Conservar/encadenar la disposición previa, incluyendo
   default/ignore/handlers. Nada de JNI, alloc, mutex, JSON o logging Android dentro
   del handler. Solo instalarlo cuando pueda demostrarse esa preservación; un
   fallo de instalación se informa, no se presenta como cobertura de señal.
4. **Exportación y helper:** importar la evidencia nativa al volver a abrir,
   ligarla estrictamente a PID/RUN/boot/tiempo y exportarla sin atribuciones
   heurísticas. Priorizar rutas efectivas del prefijo/ImageFS/helper antes de
   candidatos fallback, sin subir el cupo ni recorrer cuentas o el cliente entero.
5. **Un candidato:** actualizar versión y provenance conservando package/firma;
   build y auditoría del APK real, fuentes/receta e instrucciones por el mismo SFTP.
   No prometer que el cambio soluciona el negro: su puerta es identificar el cierre.

### Áreas afectadas y coordinación

- Nativo: adaptador separado en `crates/forever-patcher-jni/` o componente pequeño
  adyacente; no modificar `crates/forever-patcher/` ni su contrato de escrituras.
- Android: overlays `ForeverProcessDiagnostics`, `ForeverProcessIdentity`,
  `ForeverRunStore`, `ForeverRuntimeEvidence`, puente y tests dirigidos.
- Integración: receta `apply_overlay.py`, provenance, audit y entrega versionada.
- El contrato de registro nativo/lectura se fija antes de delegar consumidores.
  No escritores concurrentes en las mismas rutas; revisión independiente de la
  seguridad de señales y de la vinculación de evidencias.

### Criterios de aceptación y verificación

- Reproducir con una salida sintética mayor de 512 KiB: conservar evidencia final
  posterior al límite inicial, sin bloquear al escritor y sin crecer sin cota.
- Tests nativos en **hijos sintéticos propios**: SIGINT enviada por un padre con
  identidad conocida, default mantiene terminación por 2, disposición ignore y
  handlers previos conservan su comportamiento; captura ausente/fallida no afirma
  un emisor. No enviar señales al proceso de la herramienta, Wine ajeno o teléfono.
- Tests de parser/recovery: registros truncados, viejos, PID reutilizado, RUN/boot
  incorrectos y errores de IO no se atribuyen ni desbloquean una corrida.
- Tests de grupos: campos stat correctos/ilegibles y cambio de identidad, sin usar
  un PGID compartido para señalar procesos. Tests de cupo helper con candidatos
  ImageFS antes de fallbacks y symlinks fuera de raíces rechazados.
- Revisión independiente antes de trial; preservar tests exitosos no afectados.
  Cross-build arm64 con toolchain ya instalada, build Gradle offline single-worker,
  receta fresca/idempotente y auditoría del APK empaquetado/ABI/firma/assets/exports.
- En teléfono: una acción en foreground, tras reiniciar Android por el Wine previo;
  exportar si se cierra. Identificar evidencia obtenida y límites, no declarar WoW,
  primer frame ni login por la mera presencia de controles o de Wine.

## Implementation / Action Items

- [x] 1. Revisar el ZIP y confirmar identidad/tipo/tiempo de la salida del host.
- [x] 2. Contrastar retención del stderr y rutas que señalizan desde el código.
- [x] 3. Obtener aprobación de este límite nativo de diagnóstico de SIGINT.
- [x] 4. Fijar contrato seguro del registro y reproducir en hijos propios.
  Linux sintético; Android queda UNSUPPORTED, no activación del handler.
- [x] 5. Implementar captura inicio/cola, metadata de grupos, lectura y exportación.
  Subconjunto seguro; captura real de emisor Android pendiente.
- [x] 6. Revisar señales/concurrencia, ejecutar checks afectados y construir/auditar.
- [ ] 7. Entregar candidato e instrucciones; recoger señal y final de traza en móvil.
- [ ] 8. Decidir la corrección de arranque según el emisor real; no suponerla ahora.

## Open Issues

- **Aprobado por el usuario:** «vale genera el apk a ver si tenemos avances».
  Se conserva la acción previa de SIGINT. La primera implementación se limita
  a instrumentar `SIG_DFL`; `SIG_IGN`, handlers personalizados y disposiciones
  incompatibles quedan intactos y se informan como `UNSUPPORTED`, no como cobertura.
  El descriptor permanece ligado a un solo RUN durante la vida del host para
  evitar cierres/reutilizaciones concurrentes desde el handler.
- Emisor de SIGINT y grupos reales desconocidos. El informe histórico no conserva
  `siginfo`; no puede reconstruirse retrospectivamente.
- La última parte del stderr ya descartada no puede recuperarse del ZIP existente.
- Compatibilidad con la disposición/libsigchain de Android: si no puede validarse
  la preservación de handlers y terminación, parar y proponer una alternativa,
  nunca instalar un handler que silencie el cierre para aparentar progreso.
- **Revisión independiente, límite aplicado:** no existe CAS de `sigaction` que
  preserve un handler instalado concurrentemente por otro componente, ni prueba
  suficiente de libsigchain en el dispositivo. Se detiene esa instalación: en
  Android la API responde `UNSUPPORTED/ANDROID_SIGNAL_OWNERSHIP_UNVERIFIED` sin
  tocar ninguna disposición. El candidato contiene los avances aprobados de
  logs finales, grupos y helper, pero **no identifica todavía el emisor**. La
  captura nativa queda pendiente de un mecanismo seguro; no se declara este plan
  terminado por entregar el subconjunto seguro.
- Aparición de WoW, suficiente CASC, render PowerVR y CertStore/login siguen siendo
  puertas independientes, no se resuelven por observar una señal.
- El subconjunto seguro se combina en code 29 con la ruta directa aprobada en
  `direct-arm64-launch-plan.md`. Snapshot exportado significa bytes acotados
  verificados, no stream final completo. Ante fallos IO se conserva UNKNOWN;
  publicación exitosa limpia el sidecar de error anterior. No se declara resuelto
  el objetivo de identificar el emisor de SIGINT ni se cierra este plan.
