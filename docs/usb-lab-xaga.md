# Laboratorio USB xaga — bootstrap y siguiente bloqueo

Fecha: 2026-10-02. Dispositivo autorizado `DEVICE_SERIAL`, Xiaomi 22041216G,
Android 14/API 34, Mali-G610 MC6. No es el Poco PowerVR de las pruebas anteriores.

## Automatización comprobada

- APK instalada/actualizada por ADB y journals privados recogidos con `run-as`.
- La opción HyperOS de inyección pide SIM; no se activó ni se burló su permiso.
  Se utilizó teclado USB AOA2 documentado: registro y liberación de HID, sin root,
  detach/reset USB, ACCESSORY_START ni cambio de permisos. Consentimiento y botón
  se activan solo tras comprobar foco/paquete propio; una sola activación por RUN.
- Captura externa de logcat anterior al arranque, screenshots y estado privado.
  La exportación SAF manual no es necesaria en este laboratorio.
- Un modificador Shift de teclado despierta la pantalla. No se elude bloqueo
  seguro; no se ha cambiado la política persistente de permanecer encendido.
- Evidencias fuera del repo en
  `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`.

## Baseline 0.2.6: error real de instalación fresca

`baseline-0.2.6-160139-e4a31768`, RUN
`RUN-1790957069537-3781255405145`: importó WCP y preparó el catálogo, pero omitió
ImageFS del recorrido `PluviaMain.preLaunch` original. Wine PID 6133 salió con
código 1 en 39 ms; el host 22992 siguió vivo. Stderr completo (261 bytes):
`CANNOT LINK EXECUTABLE ... library .../imagefs/usr/lib/libredirect-bionic-wx.so not found`.
No era un frame ni un test Vulkan; `ERROR_ROOT_IDENTITY` seguía a un PID ya expirado.

## Corrección y pruebas internas

Se reutiliza `ImageFsInstaller.installIfNeededFuture`, incluida la migración stock,
en una operación propia que cubre descarga, migración síncrona y extracción. La
descarga usa la API pública context-only `fetchFileWithFallback`, sin servicio
Steam/login ni motor HTTP nuevo. Cancelaciones/timeout retienen y unen la operación
en curso; no lanzan otra extracción ni Wine antes de completar su guard.

Validación previa: raíces y marcadores sin enlaces hacia datos ajenos, variante
vacía/Bionic, rechazo de real home más shared home y de Proton no vacío en conflicto.
Postcondiciones: versión >=31, SysV SHM/WX/evshim ELF AA64 presentes. ICD se genera
después en XServer, no se exige antes de su productor. No se sustituyen WCP/driver.

- **0.2.7/code31:** stock instaló bibliotecas, pero no produce `.variant`. El guard
  nuevo exigía metadata ausente. Se ajustó: commit Bionic mediante API stock solo
  tras instalación y postcondiciones válidas; GLIBC explícito se rechaza antes.
- **0.2.8/code32:** se rechazaron defaults `proton-9.0-arm64ec`/`proton-9.0-x86_64`
  duplicados. Inspección real confirmó que ambas carpetas estaban vacías: son
  placeholders recreados por el archivo stock. Code33 permite solo los vacíos;
  el rechazo de conflictos no vacíos y sus sentinels se mantiene.
- 29 casos de bootstrap/perfiles/ruta comprobados en la primera ronda, con una
  barrera de concurrencia corregida y reprobada. Después 17 casos bootstrap pasan;
  GLIBC previo y el nuevo caso vacío más los dos sentinels no vacíos se revalidan
  específicamente. Fixtures inertes, no ejecución Wine en los tests JVM.
- Review final sin P0/P1 pendientes del bootstrap. Receta fresca/idempotente:
  61 inputs coinciden con el árbol construido. Build offline single-worker y
  auditoría del APK empaquetado correctos.

## Prueba 0.2.9/code33: Wine vivo, Explorer bloqueado

`trial-0.2.9-automated-180738-eec097cc`, RUN
`RUN-1790964468529-2491120949686`, boot
`9df14107-c054-4290-a040-19637b6c273f`. APK 362.042.153 bytes, SHA
`ccab6f5241a73a0f45dec15307abca7381c04041cc3e7696146842116ba27b2f`.

Wine raíz Linux PID 21331/startTicks 260860, host 17307 vivo; selección
`verified-aa64-direct` y argv/cwd/A: correctos. El Linux raíz aparece como
`start.exe`: no se confunde con una identidad WoW. No PID juego validado ni patch
escrito; captura visual negra con controles del runtime. **El CANNOT LINK está
resuelto y Wine no salió en 39 ms**, pero no hay gameplay.

Stderr retenido identifica la cadena:

1. Líneas 396–408: `NtCreateUserProcess explorer.exe`, NT PID `00cc`, TID `00d0`.
   El argumento de WoW aparece en su cmdline; no prueba creación del juego.
2. Línea 502: warning Zink: el dispositivo `Wrapper(Mali-G610 MC6)` no ofrece
   `logicOp`, `fillModeNonSolid`, `shaderClipDistance` base. No se declara causalidad
   exclusiva del warning ni equivalencia con PowerVR.
3. Líneas 503–515: execute fault `c0000005`, PC 0, y
   `Initialization of winex11.drv failed` en Explorer.
4. Cola: mismo NT PID/TID repite execute fault en `0x711d63fbbc`. El mapa recogido
   es del raíz, no de Explorer, y no permite asignar ese PC a un módulo.

El contador superó 26.719.693.420 bytes **drenados**, no almacenados: head/tail se
mantienen acotados. Se conservó evidencia y se forzó la detención del paquete
propio a las 18:20:55 UTC para no dejar el bucle consumiendo recursos. No hay señal
SIGINT espontánea del host en esta corrida; no se resuelve retrospectivamente la
observada en el otro móvil. Los clientes logcat host se detienen solo tras verificar
PID/startTicks/comando/FD del fichero propio. Reboot boundary sigue requerido.

## Próxima puerta

Localizar la inicialización de X11/GLX/Explorer antes de atribuirlo al loader WoW,
JNI, CASC o DXVK. Comparar una variable con el fork funcional (p. ej. startup)
solo con evidencia y capturar el mapa del proceso realmente fallido. Vulkan
1.1.177 del xaga frente a requisitos upstream DXVK 2.4.1 sigue siendo un riesgo
gráfico separado. No se instala Turnip en Mali, no se ignoran señales, no se
cambia runtime/driver, ni se afirma primer frame o login Forever.

## 2026-10-02 — code 36 / 0.2.12: comparación de la ruta de escritorio

La ruta sin Explorer (code 34/35) produjo `nodrv_CreateWindow`: Wine no pudo
proporcionar un driver de ventanas al proceso del juego. Esto no demuestra que
Explorer o un escritorio virtual sean requisitos de Wine. Se comparó el comando
original de GameNative/fork, restaurándolo sin cambiar runtime ni driver:
`wine explorer /desktop=shell,<size> A:\WowB-ARM64.exe -config BetaSuspendedTest.wtf
-d3d11` manteniendo el objetivo AA64 verificado sin el wrapper `winhandler.exe`.
Evidencia: `physical-trials/xaga-20261002/trial-0.2.12-automated-203503-3a9fdc9c`.

Resultado físico (RUN-1790973318554-44122046694, host 6001, Wine pid 11967):

- La provisión terminó; Wine y `WowB-ARM64.exe` se ejecutaron de nuevo y aplicaron
  el portal Forever (`<CVar.portal> auth.gpon.com.co` en el informe del juego).
- Explorer emitió trazas X11 de `init_pixmap_formats`, `init_visuals`,
  `xinerama_init` y `X11DRV_Settings_SetHandler`. Estas prueban avance en la
  inicialización X11, no que la carga completa del user/display driver acabara bien.
- Inmediatamente después de
  `WARNING: ... selected Vulkan device (Wrapper(Mali-G610 MC6)) doesn't support
  base Zink requirements: feats.features.logicOp feats.features.fillModeNonSolid
  feats.features.shaderClipDistance`,
  el proceso del escritorio (`00d0:00d4`) sufre un **execute fault `c0000005`, PC 0**
  y entra en un bucle recursivo de excepciones. No se identifica todavía el módulo
  de la llamada nula: el mapa recogido era del raíz, no del Explorer que falla.
- El juego informa `BC_VERIFY(m_window != 0, ...)` / `CreateWindowEx failed`
  (Error #135, informe `2026-10-02_22.35.59_Error_408.txt`). No hay dispositivo GX,
  ni frame, ni `CharLogins`.

Lectura corregida: provisión, ejecución del cliente y lectura de la configuración
están demostradas; la ventana no llega a crearse. La proximidad del warning Zink
al execute fault es una hipótesis útil, no prueba de causalidad ni de independencia
del launcher. No extrapolar al PowerVR del otro Poco. Vulkan 1.1.177 y los requisitos
de Zink/DXVK son riesgos gráficos independientes. Antes de cambiar esa pila se
debe recoger el mapa del proceso realmente fallido y asignar LR/PC a sus módulos.
El dispositivo USB sigue confirmado por SurfaceFlinger como **ARM Mali-G610 MC6**,
no Adreno. No se cambia driver/Turnip ni se afirma render o login.

## Repetición code 36: dirección de retorno asignada a Zink

Sin modificar APK, configuración, driver ni datos, se repitió el ensayo con captura
externa de mapas de procesos del UID propio (revalidación PID/startTicks/UID).
Carpeta `trial-0.2.12-automated-205408-51990a91`, RUN
`RUN-1790974462486-93037153005`; 65 snapshots acotados. Se detuvo el paquete propio
después de conservar evidencias, sin cambios de permisos ni señales a PIDs ajenos.

- El primer fault vuelve a ser execute AV `c0000005`, PC 0, NT `00d4:00d8`.
  LR `0x7052b34894` corresponde a Linux **Explorer PID 14047/startTicks 10859**,
  no al raíz Wine 13771. Su mmap RX es
  `705240d000-7052b3d000 ... offset 00607000 ... libGL.so.1.5.0`.
- Biblioteca del dispositivo conservada solo fuera del repo, 80.746.784 bytes,
  SHA-256 `dfc0f746d13be84728ac588457b05a5d3f4517d28b0b76199044d448710edc4c`;
  el hash de la copia coincide con el del archivo privado de la app.
- ELF PT_LOAD convierte el offset de archivo `0xd2e894` en VA `0xd32894`.
  `llvm-addr2line` identifica
  `emit_memory_barrier<barrier_type 1>::for_image`,
  `zink_synchronization.cpp:421`, en `zink_resource_image_barrier`.
- La instrucción anterior, VA `0xd32890`, es **`blr x8`**. Se carga desde
  `zink_screen + 17736`. DWARF liga `zink_screen.vk` a `0x33a0` y
  `vk_uncompacted_dispatch_table.CmdPipelineBarrier2` a `0x11a8`:
  `0x33a0 + 0x11a8 = 17736`. El fallo tiene por tanto un callsite concreto en
  Zink/dispatch de **`vkCmdPipelineBarrier2`**, no solo un warning cercano.

Esto localiza el primer caller; no se leyó en vivo el slot de dispatch ni se
demuestra todavía si su estado inválido deriva del wrapper, extensiones ausentes,
la versión anunciada o un defecto de esta combinación. La GPU física expone
Vulkan 1.1.177 y no se observó `VK_KHR_synchronization2` en su inventario anterior.
No extrapolar este diagnóstico al PowerVR ni afirmar que todo fallo es del driver
del fabricante. La creación de ventana y D3D11/frame/login siguen pendientes.

Referencia acotada para el siguiente control: el shim MIT de Thor
`src/no-opengl-shim.c` exporta deliberadamente ninguna API GL, para que la
comprobación Wine de `glGetString` declare GL no disponible. Su uso es aislado
para D3D11/Vulkan; no sustituir bibliotecas globales ni asumir que resolverá DXVK.
