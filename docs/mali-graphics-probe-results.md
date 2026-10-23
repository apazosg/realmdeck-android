# Mali: capacidades efectivas medidas, D3D11 rechazado

## Ensayo válido de Phase A — 2026-10-03

La sonda propia **PE32+ AA64** se ejecutó automáticamente en el laboratorio USB
`DEVICE_SERIAL`, Xiaomi xaga/Android 14/Mali-G610 MC6. Mismo tuple que el
control WoW: Proton `11.0-90624-arm64ec`, DXVK `2.4.1-wow-aarch64-test`, wrapper
System Vulkan, shim GL privado y memoria desactivada. No WARP, features falsas,
driver nuevo ni un renderer implementado por el proyecto.

```text
physical-trials/xaga-20261002/trial-20261003T112334Z-c40919bc71
RUN-1791026632504-55097624541
graphics-probe.jsonl: 2.864 bytes
SHA-256: 320a94e22e67e8216cc7f2d8106f6b2f49541c0b223efc98f06a43e8a19b9148
```

El log durable completo tiene siete registros schema 1 del mismo RUN, con inicio,
módulos, capacidades, adaptador, dos intentos y terminal **DEVICE_REJECTED**.
Se recuperó por lectura acotada del archivo fijo de la app, comprobando el RUN
privado antes/después; no se ejecutó otro ensayo para recogerlo. La ausencia de
stdout (`stdoutMirrored=false`) no invalida este archivo, pero se conserva como
límite de transporte. El exit 0 del wrapper Wine/Explorer **no es PASS de la sonda**.

## Capacidad física frente a efectiva

La consulta Android anterior muestra Vulkan 1.1.177 y fill/clip/BC false. La sonda
consulta `winevulkan.dll` y enumera `Wrapper(Mali-G610 MC6)`, API efectiva 1.3.177
(instancia 1.4.0, petición 1.3.0), 71 extensiones sin truncación.

| Feature | Resultado efectivo observado |
|---|---|
| fillModeNonSolid, shaderClipDistance, textureCompressionBC | true |
| geometryShader, tessellationShader, sampleRateShading | true |
| robustBufferAccess, shaderDrawParameters, samplerMirrorClampToEdge | true |
| shaderDemoteToHelperInvocation | **false** |
| synchronization2, dynamicRendering | **false** |
| transformFeedback | true |
| geometryStreams | false |
| VK_EXT_robustness2 | no anunciada; robustBufferAccess2/nullDescriptor **UNKNOWN** |

Esto demuestra una diferencia con la identidad física, no que las capacidades
anunciadas estén implementadas correctamente. La medición usa el primer dispositivo
Vulkan; no se probó una correlación LUID completa con DXGI en sistemas multi-GPU.

## Resultado D3D y límites causales

DXGI enumera el adaptador hardware Wrapper(Mali-G610 MC6). Se llama
`D3D11CreateDevice` con adapter explícito/`D3D_DRIVER_TYPE_UNKNOWN`, sin software,
con la lista 11_1, 11_0, 10_1, 10_0, 9_3, 9_2, 9_1; se repite sin 11_1.
Ambas llamadas devuelven **0x80070057**, nivel 0. No dispositivo D3D creado,
triángulo, readback ni present; `successfulPresents=0`, `d3dTriangleResult=false`.
El compilador 47 no se cargó, pero las llamadas D3D ocurrieron antes de utilizarlo:
no explica este rechazo de creación. Es una dependencia pendiente para un futuro
tuple que sí consiga el dispositivo.

En upstream DXVK 2.4.1, `shaderDemoteToHelperInvocation` requerido y false puede
explicar el sentinel 0 del chequeo base. `geometryStreams=false` no es la misma
rama. Sync2/dynamic rendering/robustness son gates posteriores. La interpretación
es **condicional a equivalencia upstream**: el WCP experimental está fijado por
hash, pero sus modificaciones no tienen todavía lineage completo verificado.
No demostrar soporte fingiendo bits/API ni atribuir todo al fabricante Mali.

Los módulos cargados se identificaron en C:\windows\system32. Los hashes
post-trial de los archivos del prefijo coinciden con los componentes materializados:

| Archivo | SHA-256 |
|---|---|
| d3d11.dll | `5e2167882c88e8e4013e5d5aa1ba221ce9a3ddb847ed901c06b652ce97a07c0f` |
| dxgi.dll | `136a8703e9f6aede96ae671ba16f88de1191e79d59e18a617bbbe85fad0c74fc` |
| winevulkan.dll | `4fdba0d5d7b1deea17cd3f40d490cacdc07d4d201e1c708a286265ccf811224c` |

No prueba por sí solo la equivalencia fuente ni el contenido de todos los mapas.
Memoria privada de WoW no inspeccionada/escrita. Render, login y gameplay siguen
sin aceptación; no extender el resultado a todas las Mali, Adreno o PowerVR.

## Artefacto y verificación

- Sonda: 89.600 bytes, SHA `a19e6721647fae0f31052133c585c91194823883d6be9bb81c6d0d342abfee9f`.
- Code 41 / `0.2.17-automated`: 23 tests JVM afectados pasan sin skips; build offline,
  receta fresca/idempotente **70 inputs**, auditoría del APK real PASS.
- APK de laboratorio: 362.150.264 bytes, SHA
  `121a5d41b6d67586fe58468c8b1b6fb671877857d07a41e938d1afa1b6764407`.
  Instalado y probado, **no publicado como un launcher WoW jugable en delivery**.
- El collector ahora conserva el log externo fijo con schema/RUN/cupo/terminal
  comprobados; dos regresiones de binding, truncación, duplicados y falso PASS más
  la regresión de presupuesto stdout/stderr: **3 tests Python pasan**.
- El agente detuvo solamente el paquete propio después de conservar evidencias;
  no fue una muerte natural ni prueba de containment. UID propio sin procesos al
  acabar; la política de reinicio vigente no cambia.

## Fase B: candidato nuevo, no backend aceptado

El VirGL guest local de GameNative es GLIBC e incompatible con Bionic. La receta
oficial **Termux** fijada a `27da60be397a6e9eb898bba8d03bcd648782ccd5` sí utiliza
toolchain Android y Mesa 26.2.4 con `llvmpipe,softpipe,virgl,zink`, GLX DRI y parches
vtest. Mesa/loader se auditaron **estáticamente**, sin instalar ni ejecutar:

| Paquete aarch64 | Bytes | SHA-256 |
|---|---:|---|
| mesa 26.2.4 | 3.146.408 | `d072b0144a9a6f4f80bd08797893aa37d4b75209aae810f2e84843a8fa23342e` |
| libglvnd 1.7.0-1 | 178.340 | `67f3fd247e4279a7fceddbd2929f8b53e87c255edfc9c07d0dee7df5fe243d2c` |

La Release HTTPS del 2026-10-03 y su índice gzip se ligaron por SHA; no se presenta
como verificación GPG del repositorio apt. El índice declara 40.461.140 bytes de
paquetes/238.690.304 expandidos para el cierre completo de dependencias; no se han
descargado ese cierre ni LLVM ni instalado Termux en el móvil.

ELF AA64/Bionic comprobados (`libc.so`, no libc.so.6). `libgallium-26.2.4.so`
(17.656.040 bytes) contiene referencias `virpipe`, `.virgl_test`, `VTEST_SOCKET_NAME`
y necesita `libLLVM.so`, XCB y otras bibliotecas. `libGL.so.1.7.0` necesita
libGLdispatch/libGLX; libGLX_mesa requiere su cierre X11/XCB. Su RUNPATH apunta al
prefijo Termux, así que **no es una copia drop-in** a ImageFS. Las strings más la
receta prueban un candidato, no un servidor/protocolo/loader funcional.

Auditoría fuera del repo: `arm64-prototype/controls/virgl-audit/payload-audit.json`.
Antes de cambiar el renderer: resolver el cierre ELF privado, comprobar protocolo
con el servidor JNI/EGL existente y aislar WineD3D AA64/DLLs/prefijo del baseline.
No alterar el host Android, no reemplazar ImageFS global ni conservar el shim GL
en el perfil WineD3D. El primer gate vuelve a ser esta misma sonda/triángulo, no
instalar otro preset y llamar al teléfono compatible.
