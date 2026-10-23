# POCO: historial Wine/PanVK; aceptación global aún pendiente

2026-10-08. Un intento aprobado de code48/0.2.24. Solo POCO
`DEVICE_SERIAL`, UID10330. No cambios de Wine/DXVK/ICD/driver del sistema,
datos, permisos o memoria. Tres variables custom seleccionan un ICD privado por
RUN; se conserva el wrapper Vulkan/Xlib, no-GL y el startup del baseline.

Actualización2026-10-09: code55 reporta ambos triángulos/readbacks y30Present S_OK,
sin proof PE+PanVK/samePID/startTicks/root ni evidencia visual. Ver
`docs/wine-aa64-readback-results.md`. El ensayo48 y actualización54 siguen siendo
históricos; esos witnesses nuevos no aceptan globalmente el renderer ni WoW.

## Inputs y checks

- APK370.586.769 bytes, SHA
  `86719f0e1288ac4d90ddc9879e2a215d4e8d23d4fe95067578577d5ff8885b74`;
  samepackage/firma, build offline y audit real PASS. Diez tests de provisión/flags
  pasan; nueve RuntimeSetup no afectados ya pasaron. Receta fresca de80inputs.
- Gráficos PE AA64 89.600 bytes, SHA
  `a19e6721647fae0f31052133c585c91194823883d6be9bb81c6d0d342abfee9f`.
- PanVK31.142.504 bytes, SHA
  `5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`;
  archivo privado del RUN600/UID10330/hash y stat before-after estables.
- Supervisor usa deadline ADB absoluto120s, captura con reserva y cleanup separado
  acotado; no fuerza estado completo si expira o no confirma stop. Veinte tests
  HOST de evidencia/streams/colección pasan y revisión sin P0/P1 pendientes.
  No se reejecutan gates nativos como prueba de Wine.

## Intento físico

Carpeta `physical-trials/xaga-20261002/trial-20261008T124147Z-0b224d45b3`.
RUN `RUN-1791463324225-119796647929`, host8299, raíz15318/ticks13463,
boot`01be7eee-2b3b-482d-8fec-d7a985016d7f`. Wine/explorer entrega exactamente
`A:\GraphicsProbe-ARM64.exe --log A:\Logs\<RUN>-graphics-probe.jsonl --run-id <RUN>`.
Wine raíz exit0/8.421ms no es el exit del PE ni un resultado gráfico PASS.

Archivo durable de8filas del mismo RUN:

1. Vulkan efectivo `Wrapper(Mali-G610 MC6)`, driverVersion109060195,165extensions;
   demote/sync2/dynamicRendering/BC true reportados, robustness2buffer false.
2. DXGI hardware adapter enumerado, `DriverType=UNKNOWN`, adapter explícito.
3. **D3D11CreateDevice S_OK, feature level11_1**, DeviceRemovedReason S_OK.
4. `shader: COMPILER_UNAVAILABLE`.
5. Terminal **FAILED**, successfulPresents0/readbackfalse. No GPU-wide unsupported,
   no triángulo D3D, Xlib-present, WoW o login aceptados.

Los mapas capturados muestran el PanVK privado correcto (RX y otros segmentos)
en **Wine PID15681/ticks13821**, y el inode1334625 coincide con la identidad del
archivo. No es la Activity `:panvk_wsi`. Pero **no se capturó en el mismo mapa el
PE GraphicsProbe**, ni su cadena completa a la raíz: proceso muy corto, no asumir
que UID compartido o driverVersion son prueba suficiente. El analizador conserva
`CONTROL_INVALID`, D3DDeviceObservedtrue y driverMappingObservedfalse para esa
calificación estricta. No se relaja el requisito ni se atribuye ausencia de prueba
a fallback System. El logcat del renderer Android usa stock; ese host es distinto
del loader de Wine y no niega automáticamente el mapping Wine.

Final se capturó en la app foreground, no en otra app/notificaciones. El paquete
propio se detuvo a las12:43:30 UTC después de datos; ningún UID propio restante.
No SIGINT observado ni cambio de política de reboot después de Wine.

## Dependencia localizada, sin reemplazar DLLs

Lectura estática del dispositivo y copia oficial del Proton coinciden:

| Archivo | Machine / tamaño / SHA |
|---|---|
| Proton `lib/wine/aarch64-windows/d3dcompiler_47.dll` | AA64/PE32+,1.114.112 bytes, `c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add` |
| Prefijo `drive_c/windows/system32/d3dcompiler_47.dll` | x86_64/PE32+,4.453.888 bytes, `06dc23aaa86607e083ed1725f2e99cc40501caf93408569eb9f287b272b58e52` |

El fallo no significa que no haya ningún archivo: el candidato del prefijo es
de otra arquitectura. No se ha probado aún que sea la única causa de LoadLibrary.
El builtin AA64 importa `wined3d.dll`/UCRT/kernel32/ntdll del mismo Wine: calificar
la carga de esa cadena, no copiar una DLL Microsoft/x64 al azar. Todos los archivos
se mantienen intactos. El plan del control actual pide parar ante compiler47 no
cualificado; el próximo control debe separar esa selección del driver sin ampliar
a otro backend o crear un renderer.

## Actualización code54: compiler resuelto, render aún no cualificado

El bloque anterior registra code48. Los controles49–52 seleccionaron el builtin y
localizaron el rechazo del compiler47 x64 del prefijo. Su reparación se aprobó por
separado y se ejecutó en54; ver `docs/wine-aa64-shader-compiler-results.md` y
`.agents/planning/done/wine-aa64-compiler-prefix-plan.md`.

- Trial `trial-20261008T220723Z-00219df91e`, RUN
  `RUN-1791497262380-683075998117`, mismos POCO/UID/Proton/DXVK/PanVK/wrapper/noGL.
  Único DLL reparado: compiler47, desde builtin AA64 pinneado del mismo Proton;
  backup x64 y recibo COMMITTED verificados y conservados en teléfono/host.
- Carga real del compilador y GetProcAddress pasan; D3D11 sigue11_1, swapchain
  creado y shaders COMPILED. ReadbackFAILED/coloredPixels0/hash3375453637,
  consistente con solo el clearRGBA8(10,10,20,255). 0Presents; no primer frame.
- Analizador todavía CONTROL_INVALID:19maps sin proof PE+PanVK/samePID/startTicks
  y vínculo a root. No reinterpretar esa falta como fallback System ni afirmar
  shader execution/renderer correcto por la compilación S_OK.
- Se conserva reparación/backup y APK54 auditado/retained. Agent-stop propio y
  snapshot posterior sin Wine del UID no prueban contención: reboot antes de retry.
  No nueva release normal ni WoW/login; siguiente gate es diagnóstico del dibujo.
