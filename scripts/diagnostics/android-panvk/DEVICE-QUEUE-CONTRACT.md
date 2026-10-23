# PanVK: control A de dispositivo/cola propios

Plan aprobado: `.agents/planning/panvk-device-transfer-plan.md`. No implementar ni
invocar llamadas de transfer/buffer/memoria/fence/submit en este control A.

## Inputs fijos y ownership

- POCO `DEVICE_SERIAL`, SDK adb existente, debug41/UID10330/API34; `PVK-` +
  32 hex minúsculas, nuevo directorio privado dentro de
  `files/forever/controls/panvk-loader/`. Ningún parámetro de ruta/driver/ioctl/PID.
- ICD 30.972.472 bytes SHA
  `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`,
  regular0600/UID propio, hash e identidad antes/después. MinApi publicado35
  permanece; loader/metadata no prueban API completo.
- Reutilizar sin editar helpers `PanvkPrivateLoader.c` SHA
  `c15a849033826a881f1da424de1f2560a5dafabb13da298f1ce380e3639af29a`
  y/o `PanvkInstanceMetadata.c` SHA
  `a98b7d46ef12b985f6ab5ae37a759a3117e32a2361297bf2434c46b95e87b9a3`.
  Header Vulkan fijo SHA
  `c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71`.
- Toolchain NDK24 Android29, libc/libdl únicamente, ELF AA64 PIE <=64KiB;
  artefacto/manifest/source pins externos nuevos, sin sobrescribir instancia v4.

## Call graph A

1. Misma carga privada y negotiation/input7 con PFNs propietario exacto del ICD.
   Instancia allocator NULL/API1.3/layers0/ext0. Guardar results firmados y handles
   únicamente válidos si VK_SUCCESS y non-null. No consultas sobre failed handles.
2. Enumeración completa de físicos <=8; exigir exactamente uno, vendor0x13b5,
   deviceID0xa8670000, nombre medido Mali-G610 MC6. No buscar otra GPU ni renombrar.
3. `vkGetPhysicalDeviceQueueFamilyProperties`: query/fill completos <=16,
   counts explícitos y válidos. Una familia de transfer o graphics/compute
   (transfer implícito), queueCount>=1. Selección determinista primera elegible.
   Registrar index/flags/count, no anunciar soporte del workload por esa metadata.
4. `vkCreateDevice`: una queueCreateInfo, index medido, count1, priority1.0,
   features NULL, extensions0, layers0, allocator NULL. Resolver funciones de
   cleanup antes de crear el objeto; PFNs de device via ICD GDPA y owner exacto.
5. `vkGetDeviceQueue` queueIndex0, exigir non-null; no queue/deviceWaitIdle ni
   submit. `vkDestroyDevice`, `vkDestroyInstance`, `dlclose`, evidencia de cada
   intento/retorno válido. Las funciones void no equivalen a resultado VkResult.

No GPU allocation/buffer/image/cmdpool/cmd/fence/semaphore/submit/shader/WSI;
el driver sí puede inicializar contextos/colas internamente al crear el device.
No tratar esto como inocuo o como ejecución correcta de trabajo GPU.

## Output y límites

Una línea JSON <=64KiB con README de schema/keysets/tipos exactos antes del runner.
RUN/PID/UID/PGID/SID/tiempos, loader/hash/owner, negotiate/API/instance, selección
física, counts de queues, create result/handles-presence, queue-observed y cleanup.
No punteros/estructuras privadas ni credentials. Version/feature metadata previos
siguen siendo reportados, no se habilitan BC/robustness/features para pasar.

`DEVICE_QUEUE_OBSERVED` requiere todos los witnesses consistentes y native0;
`VK_ERROR`, `NO_QUEUE`, `LOADER_FAILED`, `UNKNOWN` conservan el fallo real/exit
no cero. Count parcial/oversized/resultado positivo inesperado/owner incorrecto,
NULL, clock/IO/hash/cleanup fallidos no son PASS. Broader hardware/render/triangle/
presentation/D3D/WoW siempre false. Device-loss/timeout implica STOP y decisión
de recuperación, no gate B ni otro intento automático.

Plazo HOST10s, cleanup5s best-effort con Popen handles retenidos y framework
force-stop del paquete propio ante incertidumbre/timeouts, no phone-PID/group
guessing ni reset. Capture fsync antes de exec/stdin cerrado/pipes bounded64KiB;
kernel no preemptible garantizado. Prohibidos root/HAL/new permissions/handlers/
ptrace/grupos/servicios/driverbuild/downloads/APK o datos del runtime.

Tests fake PFNs: exact create arguments, selección/counts, errores firmados,
create-failure/non-null anomalía sin usar/destruir el handle, queue-null,
cleanup antes dlclose, deadline/output, y ausencia de todas las llamadas B.
HOST parser recomputa cada witness, rejects bool-as-int/contradicciones, permite
failed observations sin false success. Review antes de UNA ejecución USB.
