# POCO: dispositivo/cola y transfer 4 KiB GPU verificados con PanVK beta.17

2026-10-07. Gate B del plan aprobado
`.agents/planning/panvk-device-transfer-plan.md`. Solo POCO `DEVICE_SERIAL`,
UID10330, Android14/API34. No se modificó la APK41, Wine, DXVK, el driver del
sistema, el HAL, ImageFS, el cliente ni los datos; no hubo root, permisos nuevos,
descargas adicionales ni commits. El proceso nativo corrió bajo `runas_app`, no en
el dominio de la Activity.

## Resultado

El control propio creó un dispositivo lógico con una cola, rellenó un buffer de
4096 bytes con el patrón `0xa1b2c3d4`, ejecutó una barrera TRANSFER_WRITE→HOST_READ,
sometió un único trabajo, esperó una fence (1 s) y leyó de vuelta los 1024 DWORD:

- **`readback.verified=true`**, `dwordCount=1024`, `mismatches=0`,
  `firstMismatchIndex=-1`, `pattern=0xa1b2c3d4`, `hashHex=6edf00772ce66325`.
- `submit.result=0`, `waitResult=0`, `signaled=true`, `timeoutNs=1000000000`.
- Memoria elegida: `propertyFlags=7` (HOST_VISIBLE|HOST_COHERENT|DEVICE_LOCAL),
  `typeIndex=1`; asignación `requestedBytes=4096`, tope aplicado 262144.
- Limpieza completa: device/instance destroy y `dlclose` con resultado 0; sin
  timeout, sin force-stop, sin procesos propios restantes.

Estado final **`TRANSFER_READBACK_OBSERVED`** (native exit 0),
`stopBeforeShaderRequired=false`. `gpuWorkload=true`; `apiComplete`, `triangle`,
`presentation`, `d3d` y `wow` permanecen `false`. Esto es un gate de
device/submission/lectura de buffer, **no** triángulo, benchmark, presentación ni
aceptación gráfica.

## Evidencia y pins

- RUN válido: `PVK-09d328d7c6fc436ca79767cd0f508cc8`
  en `physical-trials/xaga-20261002/native-PVK-09d328d7c6fc436ca79767cd0f508cc8/`
  (`capture.json`, `stdout.txt` 12081 B, `stderr.txt` 0 B, `summary.json`).
- Primer RUN del mismo binario: `PVK-5f55fa72a11d480e8dde5b5665bb9b6d`. Su ejecución
  nativa también terminó en `TRANSFER_READBACK_OBSERVED` con el **mismo**
  `hashHex`; el runner HOST lo rechazó por desajustes de su propio parser
  (longitud de hash, `capacityBytes`, `commandPool.flags` e `invalidate.ok`
  vacío), ya corregidos. La re-ejecución produce evidencia autoconsistente.
- Probe nativo `PanvkTransfer` 53280 bytes, SHA
  `be1daa4089c0f68afc2d65d12b36614fd6af7845b3c79c1f16230389eaf3976e`; manifest
  `a3ac7f96fa17084170d33dace9e2150d591dc7dd10a857850e852811da181aa5`
  (fuentes `PanvkTransfer.c` `04214af2…`, `README-transfer.md` `68055921…`,
  `build_transfer_probe.sh` `30b4c407…`).
- ICD PanVK beta.17 `libvulkan_panfrost.so` 31142504 bytes, SHA
  `5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`.
- Helper compartido `PanvkPrivateLoader.c` SHA `cc8ea0c3…`; cabecera Vulkan
  `c274cdac…`; NDK24, target `aarch64-linux-android29`, `libc`+`libdl`.
- 19 pruebas nativas fake-PFN + 32 pruebas HOST del runner; suite completa
  540 pasan / 4 saltos documentados. `git diff --check` limpio.

## Límites

- `textureCompressionBC` y `robustBufferAccess2` siguen **false**; no se tocaron.
- El transfer no necesita habilitarlos y **no acepta DXVK/D3D ni WoW**.
- No se ha probado shader, triángulo, imagen, renderpass, presentación, WSI/gralloc
  ni CLI completo de CASC/login.
- El siguiente gate sería un shader/triángulo fuera de pantalla y, por separado,
  presentación; la integración en un perfil APK queda para después, sin activar
  nada por este gate.
