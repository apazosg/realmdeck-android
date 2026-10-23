---
id: panvk-instance-enumeration-2026-10-07
state: done
date: 2026-10-07
---

## Summary

El ICD fijado carga privadamente en POCO API34; GPU-ID0xa8670000/CSF1.18 ya
medidos por separado. No se llamó Vulkan. Se propone un gate de instance y
physical-device metadata, sin device/colas/trabajo GPU/Wine ni nueva APK. Es
ejecución adicional al loader-only; requiere aprobación antes de implementar.

## Content

- Solo DEVICE_SERIAL/UID10330/debug41/API34, no Pixel. ICD existente30972472,
  SHA `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
  MinApi35/metadata G615 permanecen sin modificar y sin aceptación automática.
- Probe AA64 propio <=64KiB, NDK24/headers ya existentes, libc/libdl; workspace
  <=64MiB externo. Sin nuevas descargas/rebuild de driver/Mesa/Wine/LLVM/NDK.
- RUN/cwd/UID/serial/hash/ELF/mode/dladdr-owner/archivo exclusivo/environment
  limpios, capture fsync antes Popen, stdin cerrado/deadline10s/pipes<=64KiB;
  ningún CLI de artefactos/env/flags/punteros arbitrarios. Review previo al exec.
- Cualificar ABI ICD estándar, negociar interface y medir API anunciado; resolver
  funcs solo del owner fijado. Crear instancia con allocator NULL y sin layers
  o extensiones adicionales; no forzar API/features/versiones para fingir soporte.
- Enumerar hasta8 physical devices, consultar properties/features/extensiones
  acotadas por handle/índice. Identidad ARM/G610 y capabilities medidas, no
  renombrar G615 ni derivar Vulkan deviceID de RAW_GPU_ID sin prueba. Asociación
  DXGI/Wine no aceptada por este gate.
- No vkCreateDevice/queues/memoria GPU/submit/shader/fence/commandbuffer/
  surfaces/swapchain/WSI/EGL/Wine/DXVK/parcheo. Probe no abre/ioctla GPU: el ICD
  puede realizar su propia inicialización kernel al crear/enumerar. Es código
  opaco adicional, no lectura inocua ni garantía de cero efectos internos.
- Errores VK/UAPI/permisos => conservar y parar, sin reintentar layouts/flags o
  candidatos alternativos. vkDestroyInstance/dlclose propios siempre que sean
  válidos; no editar ready/minApi. Cuelgue kernel no es preemptible por promesa;
  cleanup de handles host y paquete exacto solo si necesario, nada de PIDs/grupos
  ajenos, handlers, ptrace, sesiones/servicios/permisos nuevos/root/HAL.
- Resultado solo instanceObserved/physicalDevicesEnumerated/featuresReported
  cuando medidos, nunca GPU-workload/triángulo/present/D3D/WoW. Cero devices o
  VK_ERROR no es aceptación de driver. Domain runas_app no cualifica Activity.
- Tests HOST inertes de ABI/types/counts/errors/partial arrays/owner/pins/cleanup
  y ELF audit; jamás ICD real en host. Un intento USB automático con evidencia.

## Implementation / Action Items

- [x] 1. Conservar loader y metadata kernel aprobados como gates separados.
- [x] 2. Aprobar funciones Vulkan instance/physical metadata.
  Usuario: «si, adelante». Solo el gate fijado; no device/workload ni WSI.
- [x] 3. Fijar ABI/límites, construir/testear/revisar probe y runner propios.
  Build v4, tests dirigidos y review sin P0/P1; originales/quarantine preservados.
- [x] 4. Ejecutar un único control USB y conservar identidad/features/errores.
  RUN `PVK-81cc697088b7492395d471c73399bbcc`: una instancia, G610/API1.4.363,
  185 extensiones completas, destroy/dlclose0, sin procesos restantes. Metadata only.
- [x] 5. Decidir device/offscreen posterior solo con metadata y ABI cualificados.
  Plan separado device/transfer, no ejecución adicional implícita. Ver resultados.

## Open Issues

- Alcance aprobado: el loader-only anterior se conserva sin cambios. La nueva
  copia invoca solo funciones instance/physical metadata con pins y revisión.
- API34/G610/CSF1.18, BC y demásfeatures/workloads completos todavía no probados.
- Un cleanup de proceso no prueba reset/quiescencia completa del kernel/GPU.
- Dependencia ausente o build fuera del presupuesto => nueva decisión, no shim
  de símbolos ni root/fake bits. Ver `docs/panvk-private-loader-poco-results.md`.
- Gate cerrado como metadata, no un perfil aceptado: BC=false y
  robustBufferAccess2=false; ningún workload, present/D3D/WoW. Evidencia y límites
  en `docs/panvk-instance-poco-results.md`.
