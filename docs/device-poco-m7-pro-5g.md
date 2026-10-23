# Dispositivo de pruebas (Fase 2): POCO M7 Pro 5G

> Baseline de hardware medida el 2026-09-27 vía ADB (WiFi, mDNS). Objetivo: fijar la
> combinación de runtime Android de la Fase 2 (`docs/architecture.md` §4).

## Identidad y conexión

| Dato | Valor |
|---|---|
| Modelo | POCO M7 Pro 5G (`2409FPCC4G`), Xiaomi |
| Nombre de dispositivo | `citrine` |
| SoC | MediaTek **Dimensity 7025** (`mt6855`), 8 núcleos |
| GPU | Imagination **PowerVR B-Series BXM-8-256** |
| Android | **16** (SDK 36) |
| Capa OEM | HyperOS **OS3.0V816** (`OS3.0.302.0.WOQMIXM`) |
| ABI | `arm64-v8a` (+ `armeabi-v7a`, `armeabi`) |
| Kernel | 6.12.30-android16 aarch64 |
| RAM | 7.660.508 kB (~7,3 GiB) total; ~1,9 GiB disponibles en reposo |
| Almacenamiento | `/data` 228 G, **62 G libres** (73 % usado) |
| Root | **No** (`ro.build.type=user`, `ro.debuggable=0`, sin `su`/magisk) |
| ADB | WiFi por mDNS (`_adb-tls-pairing`/`_adb-tls-connect`), puertos efímeros |

## GPU: capacidad Vulkan (lo que decide la Fase 2)

Driver: `PowerVR B-Series Vulkan Driver` (vendorID 4112 = Imagination, driverVersion 6715691).
**apiVersion = Vulkan 1.4.0**. GLES 3.2 (misma build 25.1). Imagen de esta GPU capturada con
`adb shell cmd gpu vkjson`.

Extensiones/características relevantes:

| Requisito | Estado |
|---|---|
| `VK_KHR_swapchain`, `create_renderpass2`, `imageless_framebuffer` | ✅ |
| `VK_KHR_timeline_semaphore`, `synchronization2` | ✅ |
| `VK_KHR_descriptor_update_template`, `draw_indirect_count` | ✅ |
| `VK_EXT_robustness2` | ✅ |
| `VK_KHR_shader_float16_int8`, `16bit_storage`, `8bit_storage` | ✅ |
| `VK_EXT_transform_feedback`, `vertex_attribute_divisor` | ✅ |
| `VK_EXT_custom_border_color`, `texel_buffer_alignment` | ✅ |
| `VK_KHR_buffer_device_address`, `depth_stencil_resolve` | ✅ |
| `VK_EXT_extended_dynamic_state`, `4444_formats`, `swapchain_colorspace` | ✅ |
| `VK_EXT_memory_budget` | ❌ (no exigido por DXVK) |
| `VK_EXT_graphics_pipeline_library` | ❌ (opcional) |
| `VK_EXT_hdr_metadata` | ❌ (irrelevante) |

Límites: `maxImageDimension2D` 16384, `geometryShader`/`tessellationShader`/`depthClamp`
✅, `samplerAnisotropy` ✅, `maxColorAttachments` 8, **`maxVertexOutputComponents` 72**
(bajo; la norma ronda 128).

## Implicaciones para el runtime

- **Turnip no aplica**: es exclusivo de Adreno. Aquí el driver Vulkan es el **propietario de
  PowerVR**; no hay driver alternativo (a diferencia de Adreno+Turnip o Mali+Panfrost).
- **DXVK irá sobre el driver del vendor.** El conjunto Vulkan 1.4 es amplio y moderno para un
  SoC de gama media, lo que hace plausible DXVK; el riesgo real es de **estabilidad y
  rendimiento** (PowerVR tiene poca exposición comunitaria en DXVK frente a Adreno/Mali).
- **`maxVertexOutputComponents=72`** es el punto más flojo: puede limitar shaders complejos.
- **Sin root**: el runtime debe funcionar sin privilegios (Winlator y derivados lo hacen).
- **Android 16 + HyperOS**: comprobar compatibilidad del runtime elegido (los Winlator conocidos
  se mueven sobre Android 13–15).
- **Espacio**: 62 G libres. El build completo con todos los tags son ~136 GB; hay que medir el
  subconjunto `Windows`+`x86_64`+`enUS` antes de descargar en el dispositivo.

## Espacio: tamaño real del cliente (medido 2026-09-27)

Filtrando el download manifest del build `1.60.1.70009` por `Windows`+`x86_64`+`enUS`:

| Concepto | Bytes | GiB |
|---|---|---|
| Download manifest filtrado (1.319.626 entradas de 2.505.571) | 107.259.748.246 | **99,89** |
| — prioridad 0 | 474.867.528 | 0,44 |
| — prioridad 1 | 95.876.672.805 | 89,29 |
| — prioridad 2 | 10.908.207.913 | 10,16 |
| Install manifest (loose) filtrado | 369.449.420 | 0,34 |
| **Total (sin overhead)** | **107.629.197.666** | **100,24** |

**No cabe en los 62 GB libres**: faltan **~45,6 GB**. El build es un cliente de motor
moderno (`client-version 3.12.0`), de ahí el tamaño tipo retail.

Opciones: (a) liberar ~46 GB más en el dispositivo, o (b) **instalación parcial**
(prioridad 0 ≈ 0,44 GiB) y dejar que el cliente descargue el resto bajo demanda.
Para la Fase 2 (arrancar `WowB.exe` y llegar al login) la (b) es la que desbloquea antes.

> Nota metodológica (posible bug upstream de `cascette-tools`): `install_to_casc` prefiere
> los tags del *size manifest*, pero en este build su orden no es el mismo que el del
> download manifest, así que esa vía selecciona un conjunto desalineado (mismas 1.319.626
> entradas, 84,72 GiB). No cambia el veredicto: tampoco cabría.

## Consecuencia para la decisión

Fase 2 sigue siendo la puerta del proyecto, ahora con un matiz: **no habrá Turnip**, así que la
vía es DXVK sobre Vulkan propietario de PowerVR. La combinación candidata deja de ser
"Adreno+Turnip" y pasa a "PowerVR driver + DXVK + FEX/Box64 sobre Wine en Winlator (o equivalente)".

## Cómo se obtuvo

```bash
export ADB=~/.local/opt/platform-tools/adb   # platform-tools oficiales (sin root)
$ADB pair <ip>:<puerto-emparejamiento> <codigo>   # requiere el diálogo abierto y pantalla encendida
$ADB connect <ip>:<puerto-conexion>
$ADB -s <ip>:<puerto-conexion> shell getprop ...
$ADB -s <ip>:<puerto-conexion> shell "cmd gpu vkjson"
```
