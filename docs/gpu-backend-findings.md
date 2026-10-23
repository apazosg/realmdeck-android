# Backends por GPU: investigación previa al soporte multiplataforma

2026-10-03. Investigación inicial del baseline code 40; posteriormente la sonda
code 41 midió capacidades efectivas sin cambiar driver/runtime/renderer. Véase
`mali-graphics-probe-results.md` para la prueba física y el candidato Bionic.
Objetivo del usuario: Mali primero en el laboratorio USB, después Adreno y
PowerVR. Una familia detectada no equivale a soporte de WoW probado.

## Estado de partida

El control GL privado permite crear la ventana de WoW 70009 y leer la configuración
Forever. Con DXVK AA64 2.4.1 se enumera `Wrapper(Mali-G610 MC6)` pero el nivel D3D
máximo es 0. GX/frame/login siguen sin aceptación; memoria desactivada por prioridad
del usuario. Inputs y evidencias: `scoped-no-opengl-results.md`.

El driver físico xaga informa Vulkan 1.1.177, el wrapper seleccionado 1.3.177.
La lectura adicional `cmd gpu vkjson` del serial `DEVICE_SERIAL` confirma:

| Feature Vulkan física | Valor |
|---|---|
| `fillModeNonSolid` | false |
| `shaderClipDistance` | false |
| `textureCompressionBC` | false |
| `geometryShader` | true |
| `tessellationShader` | true |
| `sampleRateShading`, `samplerAnisotropy` | true |
| `shaderInt64`, `shaderFloat64` | false |

Esto describe el driver Android, no el conjunto efectivo que recibe DXVK mediante
winevulkan/wrapper. No convertir automáticamente tres features físicas ausentes
en una acusación exclusiva contra el hardware; un backend puede implementar
compatibilidad, pero debe comprobarse en vez de anunciar números ficticios.

## Qué significa el nivel 0 de upstream DXVK 2.4.1

La fuente upstream permite acotar el camino:

```text
D3D11InternalCreateDevice
  -> D3D11Device::GetMaxFeatureLevel
     -> GetDeviceFeatures
     -> DxvkAdapter::checkFeatureSupport
        false -> D3D_FEATURE_LEVEL(0)
```

La selección posterior de feature levels tiene mínimo 10_1; 0 es un sentinel del
chequeo base, no el resultado de probar individualmente 9_1. Ese chequeo exige,
entre otros, fillModeNonSolid, shaderClipDistance, BC, geometryShader,
sampleRateShading, shaderDrawParameters, samplerMirrorClampToEdge,
shaderDemoteToHelperInvocation y transform feedback. No existe un switch upstream
de logging que enumere automáticamente cada `required && !supported`.

Synchronization2/dynamic rendering/robustness2 siguen siendo gates relevantes,
pero no deben citarse como causa exacta de esta rama sin medirla. Forzar
`d3d11.maxFeatureLevel=9_1` no elude el chequeo base ni crea capacidad.

Fuentes fijadas:

- <https://github.com/doitsujin/dxvk/blob/v2.4.1/src/d3d11/d3d11_device.cpp>
- <https://github.com/doitsujin/dxvk/blob/v2.4.1/src/d3d11/d3d11_features.cpp>
- <https://github.com/doitsujin/dxvk/blob/v2.4.1/src/dxvk/dxvk_adapter.cpp>

Nuestro WCP está fijado por SHA a `f660b5c6422b3f93cec65664f309718942e58581b5b96fa2902d55d63ce1db8a`.
Su perfil anuncia `2.4.1-wow-aarch64-test`; no disponemos todavía de una prueba de
equivalencia de cada modificación con upstream. Validar el DLL realmente cargado
y su lineage antes de tratar este camino fuente como diagnóstico definitivo.

## Disponibilidad real de alternativas

La inspección con `llvm-readobj` del Proton materializado confirma **COFF ARM64
0xAA64 / PE32+** para `wined3d.dll`, `d3d11.dll` y `dxgi.dll` de
`lib/wine/aarch64-windows/`. No se ejecutaron esas bibliotecas ni se sustituyeron
los DXVK del prefijo activo.

VirGL tiene servidor JNI ARM64 en `modern/jniLibs`, pero falta el lado guest para
nuestro contenedor Bionic:

- `XServerScreen.kt` conecta `VirGLRendererComponent` si el driver es virgl, y
  `VirGLRendererComponent.getSharedEGLContext` exige el renderer GL.
- La extracción `virgl-23.1.9` y `GALLIUM_DRIVER=virpipe` existen en la rama GLIBC.
  La rama Bionic fuerza Zink/wrapper y no instala VirGL.
- El archivo local `legacy/assets/graphics_driver/virgl-23.1.9.tzst` mide
  3.324.475 bytes. Contiene libGL (13.049.008 bytes) y libglapi (317.608), ELF AA64.
  **No basta su arquitectura:** DT_NEEDED exige libc.so.6, libpthread.so.0 y
  ld-linux-aarch64.so.1, además de X11 y otras dependencias GLIBC. No cargarlo
  directamente en Wine Bionic ni copiarlo a su ImageFS.
- El manifest modern menciona el mismo nombre remoto, pero eso no prueba otra ABI
  ni un payload Bionic. No se descargó ni se activó.

Por tanto **WineD3D + VirGL es un PoC condicionado**, no un preset listo que
garantice Mali. Necesita un componente Mesa/GL guest compatible con Bionic y una
ruta de provisión revisada, además del servidor ya existente. No migrar a GLIBC
para ocultar esta dependencia. El shim no-OpenGL es incompatible con WineD3D/GL.

La investigación posterior encontró una receta Bionic oficial de Termux con
Mesa/VirGL/vtest. Los paquetes aarch64 Mesa 26.2.4/libglvnd se verificaron por hash y
ELF de forma estática. No se han instalado: su cierre LLVM/XCB/GLVND y protocolo
con el servidor actual están pendientes. Esto supera la falta de **candidato**,
no prueba que tengamos ya un backend integrable/jugable. No hace falta asumir un
rebuild propio de Mesa antes de comprobar estos componentes publicados.

## Matriz de candidatos, no tabla de soporte aceptado

| GPU | Primer camino | Alternativa condicionada | Estado |
|---|---|---|---|
| Mali-G610 xaga | Sonda AA64 medida: demote/sync2/dynamicRendering false efectivos; creación D3D rechazada | WineD3D + VirGL/EGL con candidato Termux Bionic, cierre/protocolo pendientes | Ventana sí; 0 dispositivos/presents, render pendiente |
| Adreno | Tuple AA64 DXVK + Turnip compatible con el modelo/versión | Otros componentes solo con evidencia | Referencias externas; no aceptación en nuestro banco |
| PowerVR BXM | Vulkan del fabricante + DXVK AA64 con features medidas | WineD3D/GL solo tras comprobar puente compatible | Inventario histórico Vulkan 1.4; no render AA64 actual aceptado |

El Poco PowerVR anterior sí anuncia Vulkan **1.4** y varias extensiones modernas
(`device-poco-m7-pro-5g.md`); no asumir el límite 1.1 del Mali. Turnip no es un
driver para Mali/PowerVR. DXVK 1.10.3 tolera Vulkan 1.1 a nivel de instancia, pero
no hay un par AA64 verificado ni prueba de que resuelva sus features base; no
instalar DLLs x86/x64 por su número de versión. Software Vulkan solo sería control
diagnóstico, no promesa de jugabilidad. No se implementa renderer propio.

## PanVK: soporte del GPU frente al port Android

La documentación upstream Mesa declara PanVK conforme en **Mali-G610/Vulkan 1.4**.
No descartar el hardware por la API 1.1 de su driver stock. Sin embargo, el port
kbase Android de zenithblue cualifica actualmente G615; su perfil G610 es TODO.
El port BkaNeko específico xaga usa reemplazo HAL con root y declara features no
implementadas para HWUI, por lo que no se adopta como arreglo D3D11. La arquitectura
de ICD privado Bionic sí es una opción de investigación, pendiente de cualificar
kernel/UAPI/CSF-v10/loader/WSI. Evidencia, refs y límites en
`panvk-mali-findings.md`; no se instaló ni ejecutó PanVK.
