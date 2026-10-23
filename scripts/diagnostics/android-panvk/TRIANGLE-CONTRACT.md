# Contrato: gate PanVK de triángulo fuera de pantalla (Gate C)

2026-10-07. Siguiente gate tras `TRANSFER_READBACK_OBSERVED`. Solo POCO
`DEVICE_SERIAL`, UID10330, API34, mismo ICD beta.17 (31.142.504 bytes, SHA
`5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`), mismo helper
`PanvkPrivateLoader.c` (`cc8ea0c352ff582de80ef493d6060504a5bd1b11057604a1d55dd59875b3a4b7`)
y misma cabecera Vulkan (`c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71`).
No APK/Wine/DXVK/driver/HAL/ImageFS/cliente/datos/root/permisos/Pixel. Memoria
desactivada. Sin descargas nuevas; el toolchain SPIR-V ya existe (ver abajo).

## Objetivo y límites

Demostrar un **pipeline gráfico completo fuera de pantalla**: shaders SPIR-V,
render pass, framebuffer, draw de un triángulo y lectura real de píxeles. Es el
gate inmediatamente anterior a WSI/presentación y a DXVK/D3D.

- Éxito = `TRIANGLE_OFFSCREEN_OBSERVED`: imagen 64×64, clear de fondo, draw, copia
  a buffer y readback con **píxeles distintos del clear** además del fondo.
- NO es presentación, swapchain, imagen en pantalla, D3D/DXVK, WoW ni login.
- `apiComplete`, `presentation`, `d3d`, `wow` siempre `false`; `gpuWorkload` true y
  `triangleRendered` true solo con todos los witnesses.

## Shaders (compilados en build, no en el dispositivo)

Origen en repo: `scripts/diagnostics/android-panvk/shaders/triangle.vert` y
`triangle.frag`, GLSL 450, sin vertex buffer (posiciones derivadas de
`gl_VertexIndex` en el vertex; color constante en el fragment). Deben compilar a
SPIR-V con `glslc` del NDK **existente**:

```text
/home/adrian/android-sdk/ndk/24.0.8215888/shader-tools/linux-x86_64/glslc
-fshader-stage=vertex|fragment --target-env=vulkan1.0 -O -o <out>.spv <in>
```

y validarse con `spirv-val --target-env vulkan1.0`. El build debe hash-ear ambos
binarios `glslc`/`spirv-val`, registrar sus SHA en el manifiesto y generar un
`#include` C con los bytes SPIR-V (p. ej. `triangle_spirv.h`) reproducible. El
dispositivo **no** compila GLSL: solo consume los arrays.

## Secuencia nativa (una sola llamada por paso, en orden)

Reutiliza Gate A/B (dlopen privado/negociación/instancia/dispositivo/cola) y añade,
tras `queue_observed`:

1. `vkCreateImage` 64×64 `VK_FORMAT_R8G8B8A8_UNORM`, `tiling OPTIMAL`,
   `usage = COLOR_ATTACHMENT | TRANSFER_SRC`, `samples 1`, `mipLevels 1`,
   `arrayLayers 1`, `sharingMode EXCLUSIVE`, `initialLayout UNDEFINED`.
2. `vkGetImageMemoryRequirements`; seleccionar tipo `DEVICE_LOCAL` (preferir
   `DEVICE_LOCAL` puro; si no existe, el primer tipo compatible) y
   `vkAllocateMemory` (alineado, `allocator NULL`, límite duro ≤ 16 MiB).
3. `vkBindImageMemory`; `vkCreateImageView` (2D, `R8G8B8A8_UNORM`,
   `COLOR`, aspect `COLOR`).
4. `vkCreateRenderPass`: 1 attachment de color, `loadOp CLEAR`, `storeOp STORE`,
   `initialLayout UNDEFINED`, `finalLayout TRANSFER_SRC_OPTIMAL`; 1 subpass;
   `VkAttachmentReference` layout `COLOR_ATTACHMENT_OPTIMAL`; dependencia externa
   única si hace falta.
5. `vkCreateFramebuffer` con la vista, 64×64.
6. `vkCreateShaderModule` ×2 desde los arrays SPIR-V.
7. `vkCreatePipelineLayout` sin descriptor sets ni push constants.
8. `vkCreateGraphicsPipelines`: vertex input vacío, input assembly TRIANGLE_LIST,
   viewport y scissor **estáticos** 64×64, rasterizer `fillMode FILL`,
   `cullMode NONE`, `frontFace CW`, `lineWidth 1.0`, **sin depth/stencil**, un
   color blend attachment con `blendEnable FALSE` y write mask RGBA,
   `multisample 1`, sin tessellation. Solo shaders vertex+fragment.
9. `vkCreateBuffer` host-visible 64×64×4 = 16384 bytes, `usage TRANSFER_DST`,
   memory `HOST_VISIBLE` (preferir `HOST_COHERENT`), alloc ≤ 16 MiB, bind, map.
10. `vkCreateCommandPool(family, flags = 2 RESET_COMMAND_BUFFER_BIT)`,
    `vkAllocateCommandBuffers` PRIMARY; `vkBeginCommandBuffer(flags 0)`:
    `vkCmdBeginRenderPass` con `clearValue` de fondo conocido (p. ej.
    `{0.0, 1.0, 0.0, 1.0}` verde), `vkCmdBindPipeline`,
    `vkCmdDraw(3, 1, 0, 0)`, `vkCmdEndRenderPass`,
    `vkCmdCopyImageToBuffer(image, TRANSFER_SRC_OPTIMAL, buffer,
    region { bufferOffset 0, bufferRowLength 0, bufferImageHeight 0, imageSubresource
    {COLOR,0,0,1}, imageOffset{0,0,0}, imageExtent{64,64,1} })`,
    `vkEndCommandBuffer`.
11. `vkCreateFence` (unsignaled), **un** `vkQueueSubmit` con la fence,
    `vkWaitForFences` 1e9 ns, `vkResetFences` no necesario.
12. Si la memoria no es coherente: `vkInvalidateMappedMemoryRanges` del rango.
13. Leer 4096 píxeles (`uint32`); calcular: número de píxeles que **no** son el
    color de clear (contando RGB; ignorar posible componente alfa según formato),
    número de píxeles que **sí** son el clear, suma total = 4096, y hash FNV-1a
    64-bit (16 hex). Aceptación: `clearPixels > 0` y `trianglePixels > 0`
    (el triángulo no cubre todo el frame y el fondo permanece). Documentar el
    umbral mínimo exacto exigido en el README.

## Timeout y limpieza

Si `vkWaitForFences` no señaliza en 1e9 ns: **no** liberar recursos en vuelo, **no**
destruir dispositivo/instancia, **no** `dlclose`; `cleanup.complete = false` y
estado `TIMEOUT`/`UNKNOWN` (el proceso al salir reclama). Con éxito: destruir en
orden pipeline, pipelineLayout, shaderModules, framebuffer, renderPass, imageView,
image, buffer, memory(×2), commandPool, fence, device, instance, `dlclose`; todo
con `Result` registrado. Ningún handler de señales, ptrace, hook ni permiso nuevo.

## Esquema JSON

Una sola línea UTF-8 ≤ 65536 bytes, `schema "wowforever.android-panvk-triangle"`,
`version 1`, todas las claves de Gate A/B presentes (`icd`, `interface`, `api`,
`instance`, `physical`, `queues`, `selection`, `device`, `queue`, `cleanup`) más un
bloque `triangle` con: `image {createResult, format, extent, usage}`, `imageMemory
{requirementsSize, typeIndex, flags, allocateResult, size}`, `renderPass...`,
`framebuffer...`, `shaderModules {vertBytes, fragBytes, ...}`, `pipeline
{createResult, ...}`, `readback {createResult, submitResult, fenceSignaled,
waitResult, invalidateAttempted, invalidateOk, byteSize, pixelCount, clearPixels,
trianglePixels, hashHex, mismatched?}`, y la lista ordenada `functions` con cada
PFN invocada exactamente una vez (orden determinista). `triangleRendered`,
`gpuWorkload` true solo con aceptación completa; `presentation/d3d/wow/apiComplete`
siempre false.

## Verificación previa al ensayo

Build estricto NDK24 `aarch64-linux-android29` `-Wall -Wextra -Werror` PIE, solo
`libc.so`/`libdl.so`, sin imports directos VK/GL/EGL, ELF ≤ 65536 bytes, manifiesto
con pins de fuente/helper/cabecera/compilador/glslc/spirv-val/ICD. Tests HOST con
PFNs falsos (éxito completo, clear-only rechazado, triángulo-cubre-todo rechazado,
timeout sin limpieza, errores en cada paso, bounds de memoria, orden de llamadas,
validación de esquema/tipos). Runner HOST con parser estricto y re-derivación de
witnesses, `capture.json` antes de `Popen`, `stdin DEVNULL`, pipes ≤64 KiB, deadline
10 s, limpieza del hijo propio y `am force-stop` solo del paquete propio ante
incertidumbre. Review independiente con `go-muse-review` antes de un único ensayo USB.
