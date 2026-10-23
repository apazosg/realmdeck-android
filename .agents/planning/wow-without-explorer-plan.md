---
id: wow-without-explorer-2026-10-02
state: in-progress
date: 2026-10-02
---

## Summary

El usuario pide ir a la solución más sencilla, sugiere lanzar WoW sin Explorer y
aclara: «nuestro objetivo no es tener Windows o Explorer funcionando. nos llega
si funciona WoW. de hecho cuantos menos cosas lanzadas innecesarias mejor».

Code 33 ya prepara el ImageFS y mantiene Wine vivo en el laboratorio USB, pero el
primer fallo registrado pertenece a Explorer: execute violation/PC 0 y fallo de
inicialización de `winex11.drv`, antes de WoW validado. El siguiente ensayo mínimo
quita solo la solicitud de Explorer/escritorio virtual, no añade otro wrapper ni
mezcla cambios de startup, driver, Wine, datos o patcher.

US-003 sigue exigiendo el PE verificado, flags Forever, un único proceso propio y
evidencia sin afirmar render/login por el spawn. No obliga a usar Explorer.
El cambio afecta al contrato interno de arranque: este documento se presenta para
aprobación antes de modificar la receta o ejecutar el siguiente intento.

## Content

### Comprobaciones previas

- Fuente fijada GameNative `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`:
  `XServerScreen.kt:3985–3987` construye `wine explorer /desktop=shell,<size>`
  alrededor del resultado ya validado por `ForeverArm64Launch.command`.
- `BionicProgramLauncherComponent.getFinalCommand` ante ARM64EC antepone el
  `winePath` al comando guest y conserva HODLL auxiliar. No exige que el guest sea
  Explorer. El loader real sigue siendo `/system/bin/linker64` y Wine Bionic.
- `ProcessHelper.splitCommand` conserva las comillas como bytes. En la ruta nueva
  se exige argv real con `A:\WowB-ARM64.exe` sin comillas literales: este nombre
  fijo no contiene espacios; el cwd Linux sí puede contenerlos y se pasa separado.
  No cambiar el parser global ni los comandos upstream de otros programas.
- Evidencia code 33:
  `physical-trials/xaga-20261002/trial-0.2.9-automated-180738-eec097cc`,
  RUN `RUN-1790964468529-2491120949686`, guest Explorer `00cc:00d0`,
  `stderr-head.bin:503–515`. Los mapas recogidos pertenecen al root Linux
  `start.exe`, no al Explorer que falla. No identifica aún el módulo del fault.

### Una diferencia funcional

```text
Antes: wine explorer /desktop=shell,1280x720 "A:\WowB-ARM64.exe" -config BetaSuspendedTest.wtf -d3d11
Ensayo: wine A:\WowB-ARM64.exe -config BetaSuspendedTest.wtf -d3d11
```

- Solo el cliente gestionado con PE AA64, MD5/tamaño, ruta/cwd/A:, RUN y flags
  actualmente verificados. No ruta por nombre ni bypass del gate.
- No `explorer`, `/desktop`, `start.exe` o `winhandler.exe` añadidos como wrapper
  explícito del comando. Si Wine crea procesos auxiliares internos, se observan:
  no se matan automáticamente ni se supone que sean innecesarios.
- Mantener XServer Android, controles Java/IPC, audio, SHM/evshim/WX y los servicios
  indispensables para ejecutar la aplicación Windows. No arrancar un Windows ni
  exigir que su escritorio funcione: Wine es compatibilidad WinAPI, no el objetivo.
- Mantener startup ESSENTIAL, WCP Proton/DXVK, Vulkan del sistema, cliente/CASC,
  `BetaSuspendedTest.wtf`, flags, package/firma, datos y lectura/escrituras guardadas.
- No NORMAL startup, no nuevo driver/GLX shim, no retirada simultánea de JNI o
  servicios, no cambio de sesión/grupo. Esas decisiones necesitan evidencia aparte.
- Native SIGINT sigue UNSUPPORTED; no ignorar señales ni prometer containment.
  Tras el Wine anterior, reinicio Android antes del ensayo; automatizado por USB.

### Verificación y resultado discriminante

1. Tests del comando gestionado con el **splitter Java real**: argv exacto, cwd con
   espacios, flags íntegros, ausencia de wrappers y comillas literales; inválidos y
   rutas no gestionadas mantienen su rechazo/comportamiento anterior.
2. Pruebas afectadas de ruta AA64/ownership/spawn único, no repetir suites exitosas
   sin motivo. Receta fresca/idempotente, build offline single-worker y audit real
   de APK/package/firma/ABI/provenance/inputs. Versión nueva, sin borrar code 33.
3. Instalación, reinicio y activación AOA USB de un intento en
   `DEVICE_SERIAL`, con logcat externo y salida privada acotada. Sin taps
   manuales, SIM, credenciales o cambios de permisos.
4. Registrar proceso que recibe el fallo y recoger mapas de ese proceso propio
   con PID/startTicks/UID/ancestría revalidados; no sustituirlos por mapas del root.
5. Si WoW aparece, continuar evaluando datos, render y CertStore por separado.
   Si falla también `winex11.drv` en WoW, queda demostrado que evitar Explorer no
   elimina el bloqueo común: parar el bucle y trabajar sobre ese fallo concreto.

## Implementation / Action Items

- [x] 1. Contrastar el resultado code 33 y viabilidad de la invocación directa.
- [x] 2. Aprobar este ensayo mínimo sin Explorer/escritorio virtual.
  Aprobación: «venga probemoslo» (2026-10-02).
- [ ] 3. Adaptar solamente el comando gestionado, tests y provenance/receta.
- [ ] 4. Compilar/auditar y ejecutar la prueba USB automática.
- [ ] 5. Conservar el resultado y decidir el siguiente cambio por evidencia.

## Open Issues

- Aprobado el cambio acotado; implementación y resultado USB aún por verificar.
- WoW también usa el driver de ventanas: no Explorer no equivale a no X11/OpenGL.
  El mismo fault puede aparecer en el juego y debe registrarse, no ocultarse.
- Vulkan 1.1 del Mali frente a los requisitos DXVK es un límite posterior posible;
  no se atribuye a él la inicialización fallida actual sin prueba.
- Juego real, suficiente CASC, render y login Forever siguen sin aceptación; este
  ensayo no recupera el emisor del SIGINT anterior del Poco PowerVR.
