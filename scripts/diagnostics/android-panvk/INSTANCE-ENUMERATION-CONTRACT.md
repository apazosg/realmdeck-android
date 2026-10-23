# PanVK: instance y metadata, no dispositivo lógico

Plan aprobado y metadata completada:
`.agents/planning/done/panvk-instance-enumeration-plan.md`.
Solo POCO DEVICE_SERIAL, package com.wowforever.arm64probe, UID10330,
debug41/API34, RUN PVK-32hex y nuevo cwd privado panvk-loader/RUN.

- ICD existente: 30.972.472 bytes, SHA
  c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c.
  El package conserva minApi35; este control experimental no declara soporte API.
- Sibling `PanvkInstanceMetadata`, libc/libdl solamente, NDK24/API29, <=64KiB.
  No modificar el loader original o su artifact. Headers Vulkan ya existentes en
  `integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include`;
  registrar header/compiler/source hashes; no headers/ABI inventados.
- Reutilizar sus helpers fijados de scope, hash/identity before/after, owner,
  JSON bounded y environment limpio. Copia/include con source SHA autoridad;
  `dlopen NOW|LOCAL` conserva handle hasta `vkDestroyInstance`, luego dlclose.
- Resolver los tres exports ICD con owner exacto. Negociar interface estándar
  (input7; validar result y output soportado) sin escribir tablas dispatch.
  `vkEnumerateInstanceVersion` opcional: si falta, reportar legacy1.0 explícito,
  no inferir API1.3. API solicitada min(reported,1.3) válida para los headers y
  comparable con DXVK; registrar las dos, nunca falsear version/features.
- Resolve solo vkCreateInstance, vkDestroyInstance, vkEnumeratePhysicalDevices,
  vkGetPhysicalDeviceProperties, vkGetPhysicalDeviceFeatures,
  vkEnumerateDeviceExtensionProperties y vkGetPhysicalDeviceFeatures2 cuando
  core1.1+ permita el query. Pointers del ICD propio, no llamar a NULL/wrong-owner.
  Correct PFN_VKAPI_PTR/call ABI; las funciones proceden de ICD GIPA, no del loader
  global Android. Destroy válido en todos los caminos tras instance creada; si
  no puede resolver cleanup, UNKNOWN con estado real, no falso cierre limpio.
- Instance allocator NULL, layer/extension count0; no vkCreateDevice,
  queues/memory/command/fence/submit/shaders/surfaces/swapchain/EGL/Wine/DXVK.
  El ICD puede inicializar kernel internamente; el probe no hace ioctl/open GPU.
- Physical devices <=8, ext <=256/device, nombres bounded256. Count overflow,
  VK_INCOMPLETE, mismatch o strings inválidos conservan metadata parcial UNKNOWN,
  no absent-features falsas ni retries alternativos. Query Features2 únicamente
  pNext legales por instance/device API o extensión observada: 1.1 usa standalone
  shaderDrawParameters, agregados11/12 desde1.2 y13 desde1.3; TF/robustness2 solo
  cuando sus extensiones están presentes. No query cadena1.3 sobre device1.0.
- Reportar vendor/device/API/name, core fillModeNonSolid/clip/BC/geometry/tess/
  sample shading/anisotropy y extended demote/sync2/dynamicRendering/TF/
  geometryStreams/robustBufferAccess2/nullDescriptor. Desconocido es null,
  VkBool32 distinto de0/1 es metadata inválida, no truthy conversion.
- Un único JSON newline <=64KiB, schema/version1 documentados con fieldsets
  exactos antes del runner. PIDUIDRUN/cwd/times, loader witnesses, interface,
  version, instance, enumeración y cleanup tipados, cada llamada real/ret.
  Metadata observada no es driver-ready: gpuWorkload/triangle/presentation/
  d3d/wow/apiComplete false siempre. LOADER/VK_ERROR/zero devices no PASS.
- HOST CLI solo --output, fixed artifact+source+manifest+ICD antes ADB, exclusive
  upload SHA/modes, stdin DEVNULL, capture fsync antes Popen, pipes64KiB y10s
  plus bounded cleanup; held handles propios y paquete exacto si error/timeout.
  Sin grupos/PIDs adivinados/permisos/root. Syscall hang no preempción prometida.
- Tests HOST de fake PFNs/counts/errors/lifecycle/unsupported query/owner/JSON
  encoder+parser/zero prohibitedcalls. Ningún ICD o ARM real ejecutado en host.
  NDK strict Wall/Wextra/Werror, ELF/readelf/imports/provenance, review independiente
  antes de un solo control USB. No repetir suites anteriores no afectadas.
