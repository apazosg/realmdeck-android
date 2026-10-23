# Control GL privado — ventana WoW conseguida, D3D aún bloqueado

## Experimento automático en el laboratorio USB

Dispositivo `DEVICE_SERIAL`, Xiaomi xaga/Android 14/Mali-G610 MC6. No es Adreno
ni sustituye la validación del Poco PowerVR. Pruebas sin login ni worker de memoria,
por prioridad del usuario. Mismo WCP Proton, DXVK 2.4.1 AA64, Vulkan System wrapper,
ESSENTIAL, desktop, flags Forever y datos; no librería global sustituida.

| Candidato | Resultado |
|---|---|
| 37 / 0.2.13, baseline sin memory worker | Reproduce Zink/execute fault y negro. Desactivar el worker no resuelve el fallo de ventana. |
| 38 / 0.2.14, control | Fallo antes del exec por `Files.createLink` denegado en Android. Control inválido, no resultado gráfico. |
| 39 / 0.2.15, publicación exclusiva corregida | Shim realmente cargado, Wine declara GL no disponible, crea ventana WoW; el juego rechaza la creación D3D11. |
| 40 / 0.2.16 | Exactamente mismo control funcional, solo logging DXVK info al final del merge. Confirma el rechazo del nivel D3D. |

## Resolución comprobada

Shim AA64 `libGL.so.1`, 1.944 bytes, SHA
`36e36a7d3f1c6fffcaebe4c8ad3602fd72c974489bee469215fb9ed88c18124b`.
Fuente patrón MIT Thor, marcador único, sin exports GL/Vulkan ni DT_NEEDED.
Se antepone solo para el Wine gestionado bajo `files/forever/controls/no-opengl/`.
El libGL original de 80 MB permanece intacto. La publicación usa CREATE_NEW,
NOFOLLOW, fsync y revalidación del pin antes de exponer el path al proceso.

En code 39, tres mapas de Explorer PID 5224 prueban el shim y no el libGL Zink
original. Stderr: `glGetString not found in libGL, disabling OpenGL`, seguido de
creación de ventana. `final-screenshot.png` muestra título **World of Warcraft:
Forever** y su diálogo de aceleradora 3D no soportada. No es un frame 3D del juego.

Evidencia fuera del repo:

```text
physical-trials/xaga-20261002/trial-20261003T075516Z-713d3afdf4
RUN-1791014132495-35492026568576
physical-trials/xaga-20261002/trial-20261003T084641Z-be90c33199
RUN-1791017219717-69256184310
```

## Primer error posterior: D3D11

El informe actual de WoW 70009 confirma exe AA64/config/portal propio, pero ahora
es `Unexpected GxStartupResult` (Description 6, ConsoleDevice.cpp:1682), no
`CreateWindowEx failed`. GX inexistente, frame 0, CharLogins 0. `gx.log` registra
`D3D11CreateDevice Failed HR: 0x80070057` para Wrapper(Mali-G610 MC6).

E_INVALIDARG aislado no demostraba la causa. Code 40 reasserta únicamente
`DXVK_LOG_LEVEL=info`, `DXVK_LOG_PATH=A:\Logs` después del upstream que apagaba
logs. DXVK escribe los mensajes útiles al stderr; los dos archivos `.log` creados
son de cero bytes y no se presentan como diagnóstico completo.

La cola stderr conserva:

```text
info: Game: WowB-ARM64.exe
info: DXVK: v2.4.1
info: Vulkan: Found vkGetInstanceProcAddr in winevulkan.dll
info: Wrapper(Mali-G610 MC6): Driver : Wrapper driver 32.1.0
info: D3D11InternalCreateDevice: Maximum supported feature level: 0
err: Minimum required feature level D3D_FEATURE_LEVEL_11_1 not supported
info: D3D11InternalCreateDevice: Maximum supported feature level: 0
err: Minimum required feature level D3D_FEATURE_LEVEL_9_1 not supported
```

El adaptador es enumerado pero esta combinación no anuncia ningún nivel D3D
aceptable, tampoco en el fallback 9_1. No demuestra aún qué feature/extensión o
marshaller falla. El Vulkan físico 1.1.177 frente a requerimientos DXVK 1.3 es
relevante, pero el wrapper anuncia 1.3.177; no sustituir evidencia por su número.
No forzar artificialmente un feature level ni mezclar otro driver para aparentar
soporte. Próxima puerta: capacidades efectivas y compatibilidad del tuple actual.

## Verificación y estado del teléfono

- Artefacto shim/manifest/ELF pin y tests NoOpenGL 8/8 comprobados en host;
  publicación sin hardlink probada en el propio Android.
- Code 40: RuntimeSetup 9 tests y assemble offline exitosos, receta fresca
  idempotente de 66 inputs y APK audit PASS.
- APK generado 362.063.248 bytes, SHA
  `a1843e2cc836a2e1e6bcab631f37b3e81ddf247e264d880572cfcd17582368b0`.
  Instalado y probado automáticamente; aún no publicado como nueva entrega normal.
- El supervisor capturó stdout y cerró el paquete propio al finalizar el trial.
  Stderr excedía erróneamente el cap JSON host de 512 KiB por base64; reparado a
  800 KiB por snapshot/1 MiB raw total y recuperado por lectura **sin nuevo trial**
  en `stream-recovery/`, con RUN antes/después coincidentes.
- No juego 3D, gameplay, login ni parcheo privado aceptados. No cambio de driver,
  original GL, firmas, seguridad Android, datos o política de reinicio tras Wine.
