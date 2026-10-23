# Control privado PanVK API34: contrato congelado

Plan aprobado y cerrado: `.agents/planning/done/panvk-api34-loader-plan.md`.
Solo SERIAL `DEVICE_SERIAL`, paquete `com.wowforever.arm64probe`, UID10330,
debug code41/API34. Sin nuevas descargas ni cambios de driver/APK/runtime.

## Artefactos y comando

- ICD ya auditado, 30.972.472 bytes, SHA
  `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
- Nuevo probe `PanvkPrivateLoader`, AA64 PIE/API29, libc/libdl solamente, <=64KiB;
  artefactos externos `controls/panvk-private-loader-v2/`, fuente en este directorio.
  El primer directorio queda conservado; no se reescribe para la revisión final.
- CLI nativo solo `--run-id PVK-<32 hex lowercase>`; nada de paths/entrypoints/env
  arbitrarios. CWD privado exacto `files/forever/controls/panvk-loader/<RUN>` bajo
  `/data/user/0/<pkg>` o su alias `/data/data/<pkg>`; UID10330. Binario ahí y ICD
  regular `libvulkan_panfrost.so` en esa misma carpeta, ambos sin symlinks.
- Entorno limpio de hijo: PATH=/system/bin, ANDROID_ROOT=/system,
  ANDROID_DATA=/data, LD_LIBRARY_PATH=/system/lib64, TMPDIR=<CWD>. No preload,
  wrappers, Mesa/Gallium/Vulkan-ICD variables. No copiar dependencias extra.
- Verificar bytes/hash/ELF/mode del ICD y probe antes de exec; native también
  valida hash streaming/identidad NOFOLLOW/UID del ICD antes de dlopen y después.
- Una `dlopen(absolute_icd, RTLD_NOW|RTLD_LOCAL)`; solo `dlsym` y `dladdr` de
  `vk_icdGetInstanceProcAddr`, `vk_icdGetPhysicalDeviceProcAddr`,
  `vk_icdNegotiateLoaderICDInterfaceVersion`. Nunca llamar los punteros encontrados.
- No vkCreateInstance/device/queue/shader/WSI, no GPU open/ioctl/mmap desde probe,
  no handlers/ptrace/process memory/flags/grupos/sesiones. Constructores/IFUNC de
  dependencias pueden ejecutar código al cargar: no garantizar ausencia de efectos
  internos opacos del driver. Closing handle no significa driver cualificado.

## Evidencia fija

Una línea JSON UTF8 <=16KiB, schema `wowforever.android-panvk-private-loader`,
version1. README del probe fija las keys exactas antes del runner:
RUN/PID/UID/PGID/SID/monotimes/cwd; scope válido; ICD path/hash/bytes/identidad;
load attempted/loaded/dlerror acotado; tres símbolos en orden encontrado/owner
exacto (sin direcciones); dlclose/errno/dlerror; status/nativeExit.

`PRIVATE_ICD_LOADER_OBSERVED` requiere carga real, tres exports del owner privado,
identidad estable, cleanup y exit0. `LOADER_FAILED` conserva error real/exit1;
incomplete scope/IO/timeout => UNKNOWN/nonzero, no replay automático.
`icdCodeLoaded` refleja loaded; `constructorsMayHaveExecuted` refleja attempted.
API-complete/Vulkan/GPU/triángulo/presentación/D3D/WoW siempre no verificados;
`vulkanFunctionsInvoked=false` siempre. Nada de bajar metadata minApi35.

HOST runner solo `--output <parent externo existente>`, pins faltantes fallan
cerrados hasta build/review. 10s tras exec, stdout16KiB/stderr64KiB drenados con
cota. Closed stdin/capture fsync antes Popen/propagación shell_v2 status sentinel.
No procesos propios previos. Upload SDK sync mediante único staging propio y
copias exclusivas, sin hardlinks/reemplazar archivos desconocidos. Reap siempre
los handles HOST; force-stop solo paquete exacto ante timeout/error/ownership
desconocido. Capturas/exits/cleanup completas necesarias, ningún nozero es PASS.

Tests HOST inertes de encoder/schema/hash/path/symlink/bounds/failures y fake
loader/export-owner/cleanup; no dlopen del ICD real en host. NDK24 existente y
readelf de ambos artefactos; revisión independiente antes de un intento USB.
