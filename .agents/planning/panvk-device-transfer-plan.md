---
id: panvk-device-transfer-2026-10-07
state: in-progress
date: 2026-10-07
---

## Summary

PanVK privado ya creó instancia y enumeró un G610/API1.4.363 en el POCO, sin device
o workload. Próximo gate propuesto: un dispositivo lógico/cola propia y después,
solo si pasa, un transfer de 4KiB con fence/readback. No mezclar aún shader,
presentación, DXVK o WoW. Ver `docs/panvk-instance-poco-results.md`.

Invocar device/alloc/submit es nueva ejecución kernel/GPU respecto al gate anterior
que lo prohibía; requiere aprobación antes de código o ensayo. No implica instalar
un driver del sistema ni declarar soporte jugable.

## Content

- Único POCO DEVICE_SERIAL / UID10330/API34/debug41. ICD vigente: beta.17
  31142504 bytes SHA 5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9
  (beta16 `c0e54909…` queda como historial del gate fallido). MinApi35 permanece;
  loader/instance no cualifican API completo o el dominio APK.
- Probes propios fijos AA64<=64KiB cada uno, NDK24/headers existentes,
  libc/libdl únicamente, workspace<=64MiB externos. No payload nuevo o driver
  rebuild, Wine/Mesa/LLVM/NDK/DLL updates, root/HAL/permisos/Pixel/source spoof.
- Mantener exactos pins/owner/cwd/RUN/UID/hash/ELF/modes antes y después,
  instancia allocator NULL/API1.3/layers0/ext0; seleccionar solo el único G610
  observado. Múltiples/ambiguos/otro vendor-ID => parar, no alterar etiquetas.
- **Control A, device/queue:** query queue families<=16 con counts completos;
  una familia con transfer o graphics/compute implícito, count>=1. Crear un
  device con una cola, priority1.0, sin features/extension adicionales, allocator
  NULL. Obtener su VkQueue y destruir el device/instance. No buffer, memoria,
  cmdpool, fence o submit en este primer control. Resolver funciones del ICD
  propio antes de uso/cleanup; no handle indefinido tras VK_ERROR.
- Driver puede crear contextos/colas internas en vkCreateDevice: no garantía de
  cero efectos GPU por no tener submit. Fallo/ABI/kernel/permiso => evidencia y STOP.
- **Control B, solo si A pasa:** mismo componente y selección. Un buffer4096 bytes
  TRANSFER_DST, requirements/memory allocation<=256KiB, tipo HOST_VISIBLE compatible
  y preferiblemente COHERENT. Si no existe, no software fallback. Map propio4096,
  inicializar0 (flush cuando no coherente), command pool/buffer propio, un
  vkCmdFillBuffer patrón0xa1b2c3d4 + barrera TRANSFER_WRITE→HOST_READ. Un submit,
  una fence wait acotada1s, invalidar rango legal/alineado si no coherente,
  comprobar todos los DWORDs y hash. No SPIR-V/compiler/shader/image/renderpass.
- Vulkan no falseado: BC=false y robustBufferAccess2=false se conservan. Ese
  transfer no necesita habilitarlos y **no acepta DXVK/D3D ni WoW**. Es un gate
  de device/submission/lectura de un buffer, no triángulo o benchmark hardware.
- Cleanup solo de objetos válidos propios tras trabajo retirado. Timeout o fence
  no señalada no permite frees ciegos de recursos aún en vuelo, ni cleanup=true.
  Conservar UNKNOWN/fallo y parar; no reintentos, reset GPU, otra cola/layout,
  fuente/candidato alternativo ni espera ilimitada. Un hang kernel no tiene
  preempción garantizada; cerrar el proceso no demuestra reset del hardware.
- Captura fsync antes Popen/stdin cerrado/deadline host10s y pipes<=64KiB/RUNPIDUID,
  ownership de handles HOST, framework force-stop del paquete propio solo ante
  incertidumbre/timeouts; sin señales a PIDs/grupos ajenos, handlers/ptrace,
  servicios/sesiones nuevas ni contención inventada. Sin cuentas ni memory patch.
- Cada control en un RUN privado nuevo, evidencia separada. Antes de otro ensayo
  tras timeout/device-loss/fallo kernel, detener investigación y decidir recuperación
  explícita; no bucle automatizado. Baseline/reportes/cliente/cache preservados.
- Estados solo DEVICE_QUEUE_OBSERVED o TRANSFER_READBACK_OBSERVED si hay witnesses
  reales/returns/counts/owner/cleanup/tiempo. Hardware-general, presentation/D3D/WoW
  false siempre; GPU-work requested/reportado se distingue de aceptación gráfica.
- Tests HOST fake-PFN de lifecycle/errors/null handles/counts/alloc cap/alineación/
  fence timeout/output gates y que A no use llamadas B. ELF/ABI/provenance/review
  independiente antes de un único USB por gate. Nunca ICD/ARM real en host.

## Implementation / Action Items

- [x] 1. Conservar la instancia y metadata PanVK propias, incluido BC/robustness false.
- [x] 2. Aprobar device/queue y transfer condicional acotado, sin shaders o WSI.
  Usuario: «si hagámoslo. sigamos». Gate B no se ejecuta si A no pasa.
- [x] 3. Construir/testear/revisar control A con pins y funciones válidas.
  Artifact V3/manifest fijados, 11 tests nativos+17 HOST en rondas dirigidas,
  review final sin P0/P1; controles anteriores V1/V2 preservados sin ejecución.
- [x] 4. Ejecutar A y decidir si procede B según resultado real.
  RUN `PVK-39337e01c1bc453d82b61371e0ba667b` devuelve
  `VK_ERROR_OUT_OF_DEVICE_MEMORY` (-2) desde vkCreateDevice, sin handle ni cola;
  limpieza completa, no timeout/force-stop/procesos propios. Gate B **no procede**
  con beta16.
- [x] 5. Auditar y probar beta.17 como corrección dirigida del fallo kernel.
  ZIP/ICD beta.17 fijados y auditados; helper avanzado a su pin; probe v5.
  RUN `PVK-65a65bc814064b4aa280cf04db131805` devuelve **DEVICE_QUEUE_OBSERVED**:
  instancia, un G610 exacto, `vkCreateDevice` result0, `vkGetDeviceQueue` nonNull,
  limpieza completa. `stopBeforeTransferRequired=false`, luego **Gate B queda
  habilitado**. Ver `docs/panvk-device-queue-poco-results.md`.
- [x] 6. B habilitado: construir/testear/revisar el transfer 4KiB y ejecutarlo.
  `PanvkTransfer` 53280 bytes SHA `be1daa40…`; RUN
  `PVK-09d328d7c6fc436ca79767cd0f508cc8` da **`TRANSFER_READBACK_OBSERVED`**:
  1024/1024 DWORD `0xa1b2c3d4`, hash `6edf00772ce66325`, fence señalada,
  limpieza completa. Un RUN previo del mismo binario logró el mismo resultado y
  fue rechazado solo por bugs del parser HOST, ya corregidos. Ver
  `docs/panvk-device-transfer-poco-results.md`.
- [x] 7. Decidir shader/present/DXVK posteriores, sin activar perfil por este gate.
  Gate C (triángulo fuera de pantalla) construido, revisado y **ejecutado con éxito**
  (`PVK-bca53d0c1e404f4fbcf44f134d50d8b0`, 512/4096 píxeles de triángulo, fondo
  conservado, limpieza completa). Un bug de nuestro parser HOST (bit `DEVICE_LOCAL`
  mal probado) rechazó primero el mismo stdout; corregido sin recompilar el binario.
  Ver `docs/panvk-triangle-poco-results.md`. Presentación/WSI y DXVK/D3D quedan como
  gates separados, con aprobación propia por su alcance. BC/R2 siguen false.

## Open Issues

- Alcance aprobado: gates en orden y una ejecución por control. Ante fallo kernel,
  device loss o timeout se para y se decide recuperación explícita antes de repetir.
- El bloqueo CSF1.18 que rompía `vkCreateDevice` **estaba en el driver beta16**, no
  en el kernel ni la GPU: `KBASE_IOCTL_MEM_ALLOC_EX` → `ENOTTY` y
  `KBASE_IOCTL_MEM_EXEC_INIT` → `EPERM`. beta.17 lo resuelve en este G610 concreto.
  Ver `docs/panvk-device-queue-poco-results.md`.
- La mejora de BC/robustness que anuncia beta.17 es «built, NOT tested on that
  hardware»; no se ha medido todavía. No cambiar bits para pasar DXVK.
- No primer frame, WSI/gralloc, D3D, WoW ni login privado aceptados.
- Gate B ya ejecutado con beta.17: device+queue creados y **transfer real de 4 KiB
  verificado** (1024/1024 DWORD, fence señalada, limpieza completa). Ver
  `docs/panvk-device-transfer-poco-results.md`. El siguiente gate real es
  shader/triángulo fuera de pantalla; presentación/WSI y DXVK/D3D son posteriores
  y separados. Sigue sin haber frame, WSI, login ni memoria.
- Un defecto de nuestro parser HOST rechazó el primer RUN correcto; corregido sin
  recompilar el binario ni tocar la evidencia. Documentado en el informe.
- **Gate C completado:** el triángulo fuera de pantalla se dibuja y se lee con el
  mismo ICD beta.17 (`TRIANGLE_OFFSCREEN_OBSERVED`, 512 píxeles de triángulo y 3584
  de fondo, fence señalada, `cleanup.complete`). No es presentación, swapchain, D3D,
  DXVK, WoW ni frame en pantalla; `textureCompressionBC`/`robustBufferAccess2` siguen
  false sin probar. Siguiente gate candidato: presentación/WSI, con aprobación
  separada. Ver `docs/panvk-triangle-poco-results.md`.
- Zona formalmente indefinida señalada por la revisión: falta la dependencia de
  subpase `0→EXTERNAL` (`COLOR_ATTACHMENT_WRITE→TRANSFER_READ`) antes del
   `vkCmdCopyImageToBuffer`. El resultado original se preserva, pero no permite
   afirmar que el defecto solo cause falsos fallos. Corregir esa dependencia y
   exigir GRAPHICS antes de emplear una variante como nueva evidencia WSI.
