# POCO: PanVK crea instancia y enumera G610; todavía sin device o trabajo GPU

2026-10-07. Gate de instancia/metadata aprobado. Único target
DEVICE_SERIAL / POCO22041216G xaga, API34, UID10330/debug41. No APK,
Wine/DXVK, HAL, driver sistema, datos, permisos, root o memoria de WoW modificados.

## Inputs y verificación

- ICD privado beta16 existente, 30.972.472 bytes, SHA
  `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
  Metadata minApi35/G615 se conserva; no se convierte en driver-ready.
- Probe AA64 PIE API29/NDK24, libc/libdl solo, 37.400 bytes, SHA
  `694c925e61d935b95f6d92fa8f314ad8a5d151d9cd2fb10702fa513012d4bfc1`.
  Manifest `controls/panvk-instance-metadata-v4/manifest.json`, SHA
  `f7bcb9a9eb8af7405de8d0f393fdf7144fa2923db8617b3340e0411f99175e3e`.
- Fuente propia, helper loader anterior y header Vulkan fijados. V1–V3 previos se
  conservaron sin ejecutarse. Review reparó gates de éxito, prefixes parciales,
  handle inválido tras create fallido y versión API. Parent corrigió conteo de
  VkBool32 en structs y standalone shaderDrawParameters para API1.1.
- Ronda final: 20 tests nativos/HOST dirigidos pasan excluyendo el pin antiguo;
  tras build/pins v4, los 18 tests HOST pasan. Review final sin P0/P1; P2 residual:
  overflow JSON usa failureStage output aun si existía otro primer fallo. No ocurrió
  en este control; counts/returns raw siguen disponibles, no permite falso PASS.

## Control físico único

Carpeta externa:
`physical-trials/xaga-20261002/native-PVK-81cc697088b7492395d471c73399bbcc`.
RUN del mismo nombre, nativePID4176/UID10330/PGID/SID4174, HOST ADB1401789;
SELinux runas_app, no aceptación del dominio Activity.

- Interfaz ICD7 negociada a7, result0, exports/pointers con owner exacto ICD.
- API instance anunciada **1.4.363**, solicitada1.3.0 (header/control), query0.
- `vkCreateInstance`: allocator NULL, layers0/extensions0, result0/handle válido.
- Count/fill físicos result0, reported=returned=retained=**1**, sin truncación.
  **Mali-G610 MC6**, vendor5045 (`0x13b5`), deviceID2825322496 (`0xa8670000`),
  deviceAPI1.4.363, driverVersion raw109060195. No renombrar metadatos G615.
- Extensiones query/fill0, las **185** retenidas completas; pNext legales por
  API/extensión, VkBool32 reales0/1. Propiedades y features válidos.

| Feature reportada por este ICD | Valor |
|---|---|
| fillModeNonSolid / shaderClipDistance / geometryShader | true / true / true |
| tessellationShader / sampleRateShading / samplerAnisotropy | true / true / true |
| textureCompressionBC | **false** |
| shaderDrawParameters / descriptorIndexing / bufferDeviceAddress | true / true / true |
| shaderDemoteToHelperInvocation / synchronization2 / dynamicRendering | true / true / true |
| transformFeedback / geometryStreams | true / true |
| robustBufferAccess2 / nullDescriptor | **false** / true |

Esto es metadata real del ICD, no prueba de que sus implementaciones pasen
workloads. Respecto al wrapper de code41 hay mejoras de features reportadas,
pero BC y robustBufferAccess2 siguen siendo gates relevantes para DXVK. La
equivalencia exacta de nuestro DXVK experimental con upstream sigue condicionada.

`vkDestroyInstance` y dlclose completos/owner correcto, native/HOSTexit0,
348.186.538ns, stdout11.168/stderr0. Capture persistido antes del exec, stdin
cerrado, pipes completos, no timeout/force-stop/errores/IO/truncación, ownUIDps[].
Status **PHYSICAL_METADATA_OBSERVED**; instancia/metadata cualificadas solamente.
No `vkCreateDevice`, colas, GPU-memory, submit, shaders, WSI, D3D o WoW.
API complete, hardware/workload/triangle/presentation/D3D/WoW flags false.

## Siguiente gate

No integrar aún en el APK ni falsear BC/robustness. Verificar por separado una
creación de device/queue y, si pasa, un trabajo transfer mínimo con fence y lectura.
Es ejecución kernel/GPU adicional y requiere el plan aprobado antes del ensayo.
No sacar frame/jugabilidad de una lista de extensiones ni de la versión Vulkan.

El control posterior ya fue aprobado y se ejecutó una vez: enumera una familia
de colas válida, pero `vkCreateDevice` devuelve -2 sin handle. No se ejecutó el
transfer condicional. Ver `docs/panvk-device-queue-poco-results.md`; esta
metadata histórica de instancia sigue siendo válida, no se convierte en un
device aceptado.
