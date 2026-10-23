---
id: panvk-kbase-context-metadata-2026-10-06
state: done
date: 2026-10-06
---

## Summary

El primer gate PanVK aprobado abrió el GPU propio y midió CSF UAPI1.18, pero la
lectura de propiedades devuelve EPERM. El control previo prohíbe SET_FLAGS; no
se ha llamado. Se propone autorizar únicamente la inicialización documentada del
contexto propio para repetir metadata, no cargar el ICD ni enviar trabajo GPU.
Ver `docs/panvk-poco-kernel-results.md`.

## Content

- Solo POCO `DEVICE_SERIAL`/UID10330/Android14, mismo nodo char10:113,
  toolchain NDK24, nuevas copias privadas con pins. Preservar el control original.
- Fuentes UAPI/port fijadas a `de577411e8f27da9de50aec26a0d8cc43890745e`:
  `kbase_uapi.h` define `{u32 create_flags}` y `_IOW(0x80,1,4)`; `kbase_kmod.c`
  usa flags0 tras VERSION_CHECK. Código esperado **0x40048001**; confirmar con
  static_assert Android/host y review antes de ejecutar.
- Nuevo hijo: fresh fd propio, VERSION_CSF una vez; solo si devuelve **1.18**,
  **SET_FLAGS una vez con create_flags=0**, después GET_GPUPROPS tamaño/fill
  acotados. No reinterpretar EPERM como una autorización ni intentar otro layout.
- SET_FLAGS crea un contexto kernel propio: **no es una consulta de solo lectura**.
  Cerrar ese fd al terminar; no tocar contextos de otras apps. No tracking mmap,
  memoria GPU, JIT/EXEC_INIT, colas, fence, shader, reset ni prioridad elevada.
- Si requiere otra operación o devuelve error: STOP y registrar; no ampliar
  automáticamente al siguiente paso del port. Resultado metadata no es GPU PASS.
- Mismos guards de nodo/UID/RUN/cwd/hash/FD/errores/tiempo: 5 s cooperativos,
  10 s host y cleanup limitado al paquete propio/handles retenidos. No promesa de
  que un syscall bloqueado se pueda preemptar; ningún handler o sesión nuevos.
- Presupuesto existente: probe/runner propios, artefacto <=64 KiB, sin descargas
  o builds de Mesa/Wine/ICD. Tests mocks de orden/versiones/flags únicos/errores/
  límites/close/booleans y review independiente antes de **un** control USB.
- El ICD beta16 declara minApi35 y el POCO tiene34. Mantener ese segundo gate
  bloqueado; solo auditar símbolos/configuración de loader como evidencia, no
  ejecutar el ICD ni falsear su mínimo API o features. Si requiere rebuild/API34
  o otro payload, propuesta de fuente/hash/ABI/coste separada.
- No cambios de APK41, Wine/DXVK/JNI, driver/HAL, permisos/root/Pixel/datos,
  memory patch, credenciales ni limpieza de artefactos.

## Implementation / Action Items

- [x] 1. Medir el handshake1.18 y conservar EPERM sin SET_FLAGS.
- [x] 2. Aprobar el contexto metadata y su nuevo ioctl documentado.
  Usuario: «adelante». Solo contexto propio con flags cero y consulta acotada.
- [x] 3. Construir/testear/revisar copia propia con orden estricto y pins.
  23 casos iniciales, 16 casos HOST tras fixes y un caso nuevo de cleanup de
  staging; review final sin P0/P1. Baseline original conservado byte a byte.
- [x] 4. Ejecutar un control USB y evaluar GPU-ID/texture fields sin fake fallback.
  RUN `PVK-939499c38ec84523b7cd09ee91c87dfb`: versión1.18, SET_FLAGS0 ret0,
  size/fill723 bytes, GPU-ID0xa8670000, cierre0. Ningún workload o ICD.
- [x] 5. Decidir cómo calificar API34/ICD después de la metadata, sin saltar gates.
  Auditoría estática de símbolos y política minApi; el siguiente loader control
  se cualificó después según `panvk-api34-loader-plan.md`, ahora cerrado.
  La metadata por sí sola nunca aceptó ese loader ni Vulkan/GPU.

## Open Issues

- Aprobación obtenida para la copia nueva; el plan/ELF anterior siguen excluyendo
  SET_FLAGS y se conservan sin cambios.
- EPERM puede persistir o exigir otra condición; el nuevo control no autoriza
  tracking/alloc ni probar ioctls aleatorios si falla.
- API35/G615 publicado no acepta API34/G610/UAPI1.18; colas v10 y BC todavía sin
  aceptación. No presentation/D3D/WoW antes de gates propios.
- Cierre solo de metadata: GPU-ID y UAPI medidos no cualifican el ICD. Ver
  `docs/panvk-poco-kernel-results.md` y `docs/panvk-api34-symbol-audit.md`.
