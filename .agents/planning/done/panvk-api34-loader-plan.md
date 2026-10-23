---
id: panvk-api34-loader-2026-10-06
state: done
date: 2026-10-06
---

## Summary

El POCO ya confirma GPU-ID0xa8670000/CSF1.18 tras un contexto metadata propio
aprobado. PanVK beta16 declara minApi35, pero el dispositivo tiene34. La política
de fuente permite otro API y el análisis de sus 269 imports encuentra proveedores
estáticos presentes en este API34. Eso no es una calificación del loader.

Se propone autorizar **solo la carga privada experimental de la biblioteca**, sin
modificar su mínimo publicado ni iniciar Vulkan/GPU. Es ejecución nueva de código
del ICD/constructores, no una consulta solo lectura, y requiere aprobación antes
de implementarla. Ver `docs/panvk-api34-symbol-audit.md`.

## Content

- Solo POCO `DEVICE_SERIAL`, UID10330/debug41/API34. Mismo ICD31MB fijado
  SHA `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
  No segunda descarga, rebuild del driver/Mesa/Wine/LLVM/NDK, root ni Pixel.
- Probe propio AA64 <=64KiB con NDK24 existente; new private RUN/cwd/archivo
  exclusivo pinSHA/mode/ELF/identity antes de exec, environment limpio sin
  LD_PRELOAD/wrappers/overrides. No copiar dependencias arbitrarias o falsificar
  funciones ausentes; metadata minApi35 se mantiene como restricción no validada.
- Una `dlopen(..., RTLD_NOW|RTLD_LOCAL)`, registro bounded del error real,
  lookup de los tres entrypoints ICD exportados y `dladdr` con owner exacto.
  **No invocar esos entrypoints**, no `vkCreateInstance`, enumeración física,
  device/queues/shaders/WSI ni open/ioctl/mmap GPU desde nuestro código.
- Los constructores y resolvers del ICD pueden ejecutar código al cargar; el
  loader control no promete conocer o bloquear cada efecto interno del binario.
  Artefacto/scope review previo, no handlers, ptrace, hooks o aislamiento fingido.
- `dlclose` y ownership del hijo/FDs propio; deadline host10s y output <=64KiB,
  capture fsync antes del exec/stdin cerrado/RUNPIDUID/ABI prueba/exits completos.
  Si timeout, cleanup limitado a handles host retenidos y paquete propio exacto;
  un syscall/kernel hang no tiene preempción garantizada. No señales a grupos/PIDs
  adivinados, servicios/sesiones nuevas ni permiso Android extra.
- Resultado solo `PRIVATE_ICD_LOADER_OBSERVED` si carga/owner/cleanup/pin completos;
  `icdCodeLoaded=true`/constructores posibles. Vulkan/GPU/presentation/D3D/WoW
  siempre no verificados. Fallo/namespace/missing symbol/IO => registrar y parar,
  no relajar metadata ni probar otro binario automáticamente.
- Comparar imports estáticos con el fallo dinámico, sin afirmar compatibilidad
  del API o del kernel completo aunque dlopen pase. Device/GPU/feature/BC/CSFv10
  y presentación tienen gates posteriores separados del loader.
- Tests propios de ABI/pins/path/JSON/errors/capture/exits/zero Vulkan calls y
  review independiente antes de **un** intento USB. Preservar los controles
  metadata y los reportes anteriores; no limpiar client/cache ni generar nuevaAPK.
- Presupuesto: payload existente, probe+runner/workspace <=64MiB externos,
  nada de host tools nuevos ni gastos. Dependencia ausente o nuevo build requiere
  propuesta de source/hash/licencia/coste antes de ampliar.

## Implementation / Action Items

- [x] 1. Obtener metadata propia GPU-ID/UAPI y comparar imports contra API34.
- [x] 2. Aprobar carga privada del ICD en API34 como control experimental.
  Usuario: «si». Se autoriza este control, no instance/device/GPU/WSI.
- [x] 3. Construir/testear/revisar probe y binding de artefacto/scope.
  Source/binary pins v2, adversarios C, 16 HOST tests y review final.
- [x] 4. Ejecutar un control USB loader-only y conservar errores/identidades.
  RUN PVK-ec1a455021d0451aa5e30afcddf0d115: handle+3 exports privados,
  hash/identity estable, dlclose0/exits0/sin timeout o procesos restantes.
- [x] 5. Decidir siguiente gate o requisito de build compatible sin cambiar sistema.
  Instance/enumeración posteriores completadas en `panvk-instance-enumeration-plan.md`.
  No Vulkan llamado por esta prueba histórica de loader.

## Open Issues

- Alcance loader-only completado; minApi35 publicado permanece sin modificar.
  Ver `docs/panvk-private-loader-poco-results.md`.
- ELF/269 proveedores no cualifican namespace/ABI/construcción/API behavior; la
  fuente exacta de producción beta16 no es plenamente reproducible con lo leído.
- Context metadata no valida colas, heap, EXEC_INIT ni BC; sin frame/D3D/WoW.
