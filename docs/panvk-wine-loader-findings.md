# Wine/PanVK: ruta custom existente en el wrapper, integración todavía pendiente

2026-10-08. Solo POCO xaga `DEVICE_SERIAL`. Investigación de fuente y bytes
actuales, sin cambiar entorno, perfil, APK, driver, permisos o datos y sin ejecutar
Wine/ICD. Android WSI ya se observó en pantalla; no acepta Wine/Xlib/D3D/WoW.

## Baseline y componente real

El profile normal gestionado sigue wrapper/System, DXVK AA642.4.1, no-OpenGL y
memoria desactivada. `XServerScreen.kt` instala `wrapper_icd.aarch64.json` que
apunta a `libvulkan_wrapper.so`, no a PanVK. `BionicProgramLauncherComponent`
construye LD_LIBRARY_PATH ImageFS/system/Proton y tiene un hook final tras merges.

Se capturó por ADB **solo** la biblioteca privada actual y su JSON, con stat/SHA
antes/después/host y cotas de bytes:

- wrapper SO19.021.832 bytes, SHA
  `b598a54166790a6fc071f08495f141f92dc192f1eb339e029bfc7a303c7486ce`.
- JSON140 bytes: file_format_version1.0.0, ICD.api_version1.3.289,
  ICD.library_path=`libvulkan_wrapper.so`.
- Evidencias host `controls/wine-wrapper-audit-v1/{audit.json,readelf.txt,libvulkan_wrapper.so}`.

El binario importa `adrenotools_open_libvulkan`, `dlopen`, X11/XCB, Android hardware
buffers y SHM. Tiene símbolos/DWARF para `wsi_CreateXlibSurfaceKHR`,
`wsi_QueuePresentKHR` y `wrapper_QueuePresentKHR`. Por tanto **este wrapper contiene
WSI Xlib**; no es correcto inferir que solo Vortek podría proporcionar esa ruta.
La interoperabilidad concreta de este wrapper con PanVK queda por ejecutar.

## Ruta custom existente, no renderer nuevo

GameNative `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`:

- `com/winlator/contents/AdrenotoolsManager.java:188-206` configura driver path,
  name y hooks. No hay una condición por nombre Mali/Adreno en ese setter;
  instalar su ZIP genérico tiene otros riesgos y **no se propone reutilizarlo**.
- Bionic wrapper branch de `XServerScreen.kt:5890-6052` deja System por defecto;
  solo configura las variables custom al elegir un id diferente de System.
- El hook final existente permite afirmar un tuple por RUN después de que el
  upstream seleccione sus defaults; no requiere sustituir el JSON por un ICD
  PanVK directo y perder la ruta Xlib del wrapper.

Fuente publicada `leegao/bionic-vulkan-wrapper@c8baafbd4f4835ca103acb55ec3ac13642b6b7e3`,
branch wrapper, `src/vulkan/wrapper/wrapper_instance.c`:

- `get_vulkan_handle_icd` consume `ADRENOTOOLS_DRIVER_PATH`,
  `ADRENOTOOLS_DRIVER_NAME`, `ADRENOTOOLS_HOOKS_PATH`, comprueba la carpeta y llama
  al loader custom; de lo contrario carga el Vulkan del sistema.
- El handle retornado se usa para obtener `vkCreateInstance`,
  `vkGetInstanceProcAddr` y enumeración de versión/extensiones. Esto no es un
  `dlopen` directo que use solo los tres `vk_icd*` del PanVK privado.

`llvm-objdump` de la biblioteca capturada corrobora la rama custom en
`vulkan_library_init` VA0xcb018 y call a adrenotools VA0xcb1f8. El binario consume
además `ADRENOTOOLS_REDIRECT_DIR`, ausente en ese fragmento fuente publicado; no
afirmar equivalencia binaria completa. No se usará esa cuarta opción en el control.
El path/name/hooks de tres opciones ya tiene un precedente en GameNative.

## Control candidato y límites

Un único profile fijo puede probar la ruta custom usando el ICD beta17 existente
en un directorio privado nuevo/hash-verificado y las tres variables al final.
Mantener wrapper ICD, Wine, DXVK, SHM/evshim/WX/no-GL, renderer, startup, sonda
AA64, datos, flags y guardas. No nuevo driver del sistema, namespace global,
archivo HAL, descarga o implementación de renderer.

No se garantiza que el loader custom acepte PanVK/HMI, que la namespace permita
sus dependencias o que el wrapper interprete todas las features/handles. Puede
haber fallback System en el código del wrapper: **detectar y declarar control
inválido**, no aceptar su resultado como PanVK. Exigir mapas/hash del driver
real del proceso Wine de la sonda antes de interpretar D3D11/present.

Ese profile no está implementado. Plan separado todavía draft:
`.agents/planning/panvk-wine-probe-plan.md`. Una pantalla nativa WSI o un driver
mappeado no bastan para aceptar D3D, BC/R2, shader compiler, frame WoW o login.
