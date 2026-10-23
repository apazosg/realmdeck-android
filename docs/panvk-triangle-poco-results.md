# POCO: PanVK dibuja un triángulo fuera de pantalla (Gate C)

2026-10-07. Solo POCO `DEVICE_SERIAL`, UID10330, API34. No se modificó APK,
Wine, DXVK, driver del sistema, HAL, ImageFS, cliente, datos, root ni permisos.
Sigue `code41/0.2.17` en el laboratorio y `code33` como entrega normal; memoria
desactivada. El ICD es el mismo beta.17 ya auditado.

## Resultado

RUN **`PVK-bca53d0c1e404f4fbcf44f134d50d8b0`**, evidencia en
`physical-trials/xaga-20261002/native-PVK-bca53d0c1e404f4fbcf44f134d50d8b0/`.
El binario nativo terminó con éxito y dibujó el triángulo:

- `status TRIANGLE_OFFSCREEN_OBSERVED`, `nativeExit 0`, sin timeout ni force-stop.
- Imagen 64×64 `R8G8B8A8_UNORM` (`format 37`, `usage 33`), memoria `typeIndex 0`
  con `propertyFlags 1` (`DEVICE_LOCAL`), `requirementsSize 73728`.
- Render pass, framebuffer, dos módulos SPIR-V, pipeline layout y graphics
  pipeline creados con `result 0`.
- Staging `16384` bytes `hostVisible+hostCoherent`; command pool `flags 2`.
- Un único `vkQueueSubmit`, fence **señalada** (`waited true`, `timeout 1e9`),
  `invalidate.required false`.
- Readback: `pixelCount 4096`, `clearPixels 3584`, `trianglePixels 512`,
  `hashHex 7c50dcf1945d9325`, `verified true`.
- `cleanup.complete true`; `gpuWorkload` y `triangleRendered` true;
  `presentation/d3d/wow/apiComplete` false.

El triángulo cubre 512 de 4096 píxeles (una octava parte), coherente con un
triángulo de pantalla completa centrado: **el fondo permanece** y el draw se lee.

## Corrección de nuestro parser, no del driver

El runner HOST rechazó primero este mismo stdout con
`triangle image memory property DEVICE_LOCAL contradiction`: comprobaba el bit
`0x4` en lugar de `VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT = 0x1`. Era un error
nuestro. Se corrigió el bit, se acotó además `requestedBytes <= 16 MiB` en imagen
y staging, y se ajustó el fixture del test (que usaba el valor equivocado). Tras
eso el **mismo stdout grabado** se reclasifica offline como
`TRIANGLE_OFFSCREEN_OBSERVED` (`interpretation.json`, con hashes de los ficheros
originales). El `summary.json` original se conserva como estaba.

No se repitió el ensayo en el dispositivo para arreglar una etiqueta.

## Qué NO demuestra

Es renderizado **fuera de pantalla**: no hay swapchain, WSI, presentación, frame
en pantalla, D3D/DXVK, Wine, WoW ni login. `textureCompressionBC` y
`robustBufferAccess2` siguen reportándose `false` y no se han probado ni forzado.

## Pendiente conocido (revisión independiente)

La revisión previa señaló que la dependencia de subpase solo cubre
`EXTERNAL→0`; no hay dependencia `0→EXTERNAL`
(`COLOR_ATTACHMENT_WRITE → TRANSFER_READ`) antes del `vkCmdCopyImageToBuffer`.
El readback registrado contiene 512 píxeles de triángulo, pero eso no demuestra
que la sincronización sea correcta para todas las ejecuciones. Se retira la
afirmación anterior de que el defecto solo podía causar un falso fallo: una
dependencia incompleta no permite garantizar ese comportamiento. Antes de usar
una variante como evidencia nueva se añadirá la dependencia explícita y se
exigirá una cola gráfica. Se preservan el binario y el resultado originales.

## Siguiente gate

Presentación/WSI (superficie + swapchain + `vkQueuePresentKHR`) probaría el camino
que DXVK necesita para mostrar. Requiere aprobación propia por su alcance. Después
vendrían la integración en el perfil de la APK y la repetición de la sonda D3D.

Pins: ICD beta.17 `31142504`/`5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`;
helper `cc8ea0c352ff582de80ef493d6060504a5bd1b11057604a1d55dd59875b3a4b7`;
nativo `PanvkTriangle` `57200`/`2e757f2bcc6be23fe0b40cd6994cb00d3b8ca7388ccc8af91ca1efcf228013d0`;
manifest `009b09db7b3eb717b060dd16fa5cd86b7ef2b4c37d4614688b4790fcdb7a4ded`;
SPIR-V vert `660936e4339d872b8c13d5b32e11bec2fe2cf85711ff4417212fa89356ec2ad2`
(944 B) y frag `c27f9452b2a911ea1f66aa06a4ff721b1641ac7d3b86007fb376bfedd27db7c2`
(304 B), generados con el `glslc` del NDK 24 y validados con `spirv-val`.
