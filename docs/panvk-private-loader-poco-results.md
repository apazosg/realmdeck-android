# POCO API34: PanVK carga de forma privada; Vulkan aún no invocado

2026-10-07. Solo serialDEVICE_SERIAL/UID10330. No cambio de APK41, Wine,
DXVK, JNI, HAL/driver sistema, ImageFS, datos o permisos. MinApi35 publicado
permanece; el control no declara compatibilidad completa con API34/G610.

## Inputs y validación

- ICD beta16, 30.972.472 bytes, SHA
  `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
- Probe AA64 PIE/API29/libc+libdl, 23.392 bytes, SHA
  `7a7861c8b5e6795a8ecee8ee9794a3abcd0d076b5d307c6d2b0d092ce198024a`.
  Manifest v2 SHA `786f25b5fd5637fec3166ea220ef64e1e27924495e4ff40ff76e3d32766be732`;
  C SHA `c15a849033826a881f1da424de1f2560a5dafabb13da298f1ce380e3639af29a`.
- Dos casos C contienen vectores SHA/encoder/adversarios; caso afectado de fallback
  revalidado. 16 HOST tests pasan, pins y copy afectados revalidados después de
  changes; review final sin bloqueos para esta carga privada, no para gráficos.
- Primer staging shell-owned700 impidió atravesarlo desde run-as: preparación
  detenida antes de exec, no fallo de PanVK. Staging solo de binarios públicos
  fijados ahora755; privadoRUN700/probe700/ICD600. Solo staging propio eliminado,
  hash/mode/authority intactos. No repetir una ejecución ICD por ese fallo previo.

## Única ejecución cualificada

Carpeta `physical-trials/xaga-20261002/native-PVK-ec1a455021d0451aa5e30afcddf0d115`.
RUN PVK-ec1a455021d0451aa5e30afcddf0d115, PID20803/UID10330/PGID-SID20801,
SELinux runas_app (no Activity). `capture.json` es el registro preexec y conserva
executionStarted=false; stdout/summary posteriores prueban ejecución real.

- PRIVATE_ICD_LOADER_OBSERVED, native/host0, 325.423.692ns.
- Hash/tamaño reales antes/después, mismo dev65079/inode1179919/owner10330/mode600.
- Una dlopen RTLD_NOW|LOCAL devuelve handle, dlerror vacío.
- Tres dlsym/dladdr encuentran owner privado exacto: vk_icdGetInstanceProcAddr,
  vk_icdGetPhysicalDeviceProcAddr y vk_icdNegotiateLoaderICDInterfaceVersion.
  **No se llama ninguno de esos punteros**.
- dlclose correcto/errno0/dlerror vacío; stdout1998/stderr0, pipes completos,
  sin truncación, timeout, force-stop, ownership/IO errors o procesos restantes.

icdCodeLoaded=true/constructorsMayHaveExecuted=true: constructores y IFUNC pueden
ejecutar código opaco, no prometer ausencia de sus efectos internos. Funciones
Vulkan invocadas=false, API-complete/Vulkan/GPU/triángulo/presentación/D3D/WoW false.
El loader API34 no bloqueó este artefacto concreto; device/colas/shaders/BC/WSI y
compatibilidad completa del API/kernel siguen pendientes. No ready=true ni cambio
de minApi. La APK y los datos siguen intactos; no fue una prueba de Wine.

El siguiente gate fue aprobado y completado posteriormente en
`.agents/planning/done/panvk-instance-enumeration-plan.md`; ver
`docs/panvk-instance-poco-results.md`. Esta prueba histórica de loader-only no
invocó Vulkan y no se reinterpreta como una prueba de device/workload.
