# POCO: presentación Android PanVK observada en pantalla

2026-10-08. Control aprobado, solo POCO `DEVICE_SERIAL`, xaga/Android14/API34,
Mali-G610 MC6. No Wine, DXVK, memoria Forever, root, HAL o driver del sistema
sustituido. APK normal/datos y todos los informes anteriores preservados.

## Aceptación precisa

**ANDROID_PRESENTATION_OBSERVED**: triángulo rojo sobre verde visible en nuestra
Activity. Capturas negra antes/coloreada después vinculadas a mismo RUN/PID/ticks,
superficie/bounds, proceso foreground, journal y reporte nativo de tres frames.
No se acepta solo `vkQueuePresentKHR==VK_SUCCESS`: el port puede devolverlo aunque
falle su copia AHB→ANativeWindow. La imagen concordante se ha comprobado además.

Esto no acepta Wine/Xlib, el wrapper Vulkan de Wine, DXVK/D3D, BC/robustness2,
WoW, estabilidad, rendimiento o login. No se retuvo la etiqueta SELinux concreta:
el proceso fue iniciado por framework como Activity `:panvk_wsi`, no por run-as.
La metadata del ICD mantiene minApi35; el ensayo no certifica todo API34.

## Inputs y checks

- APK `0.2.23-automated` / code47, mismo package/firma/UID10330, arm64/min29/target36.
  370.552.765 bytes, SHA
  `411d8600cee525ab720e853259d2a76a47f22990aef009ed0fdc8ce0e0592919`.
  Audit externo `arm64-prototype/delivery/automated-0.2.23-apk-audit.json`.
  No release normal nueva publicada en delivery por este gate diagnóstico.
- SO v9 49.352 bytes, SHA
  `16e7ef86cfaadc56c40b3b97df8be16b07d9f9296623edfe7ba87eab6e0eb7ec`,
  NDK24/API29/AA64, JNI Run/Cancel separado del patcher.
- ICD beta17 31.142.504 bytes, SHA
  `5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`,
  sin modificación de binario, features o metadata.
- Activity debug/DUMP y proceso separado, SurfaceView320×240; hook específico de
  Application evita Hilt.onCreate y extras GameNative de ese proceso. Framework/
  attachBaseContext aún existen. No Canvas/CPU dibujando el triángulo en Activity.
- Offscreen v2-sync corrigió dependencia0→EXTERNAL COLOR_ATTACHMENT_WRITE→
  TRANSFER_READ y exige GRAPHICS/alloc bounds. 75 tests, SHA
  `85f8577fb35dc84ace3b513d9554defa8aca14072087fb9b2c05147ff4fbf8a7`,
  RUN `PVK-d127284c82924061a811d0077b1a804d`. El v1 original se conserva, sin
  repetir la afirmación errónea «sync incompleta solo puede causar falso fallo».
- WSI: semaphores/fences propias, acquire500ms/fence1s/una QueueWaitIdle final
  fuera de UI. El idle no tiene timeout Vulkan; driver puede bloquear internamente.
  No kernel-preemption/containment prometidos; no frees en vuelo ante incertidumbre.
- Build offline, 12 casos JVM de policy/store/lifecycle pasan, fixtures nativos y
  HOST dirigidos, receta fresca/idempotente de78inputs y audit real PASS.

## Prueba física final

Carpeta externa `physical-trials/xaga-20261002/panvk-wsi-1004530d3dda4de6be1b3b0809351ac0`.
RUN `PWSI-94e1a0a08761465f912127847b743ce8`, PID15556/startTicks41129464/UID10330,
processName `com.wowforever.arm64probe:panvk_wsi`, generation1. ROI físico
`[0,188,320,428]` en pantalla1080×2460; foreground y keyguard unlocked comprobados.

| Witness | Resultado |
|---|---|
| Native | API_PRESENT_COMPLETED, exit0, failureStage none |
| Scope/driver | SHA/identidad antes-después y PFN owners exactos |
| Surface/swapchain | 320×240, UNORM formato37, FIFO, 2 imágenes |
| Frames | 3 Acquire/Submit/Present/Fence VK_SUCCESS |
| Retiro | QueueWaitIdle0, queueRetiredtrue, cleanup completo |
| Journal | 73 calls, 147 filas, 28.831 bytes, sin error IO |
| Captura READY | negro100%, rojo0%, verde0% |
| Captura final | negro0%, rojo12,5%, verde87,5% |
| HOST | ACCEPTED; androidPresentationObservedtrue; wine/d3d/wowfalse |

before.png40.140 bytes SHA
`e230e4faa6259d5508b0754a5217bd09abde58efc874d1bf28618d46f66bd60e`;
after.png40.064 bytes SHA
`6f19bd31dcd90f23ffcc8bd1199931721c0f9b6b109e736179fff8bc0fbb3f91`.
Native, Store-wrapper, estado y journal están conservados juntos. Tras capturar,
framework force-stop solo del paquete propio return0; ningún UID propio restante.

## Defectos de integración previos conservados, no fallos del driver

Dos intentos se cancelaron antes de JNI por pause transitorio del Intent singleTop:
se espera onResume y solo se cancela una ejecución realmente iniciada. Se corrigió
el alias de paquete data/data↔data/user/0 sin aceptar fuera del mismo RUN, una tabla
SHA truncada/equivocada con tests vacío/abc/bordes/31MB, y GIPA(NULL) para
DestroyInstance (ahora tras Create válido, antes de journal que pueda fallar).

Code46 completó APIs, pero reportó imageCount0 por no copiar el conteo real al
resultado. El HOST lo rechazó antes de tomar la captura final. Code47 añade la
asignación real y fake-PFN sobre la función de producción. Esta aceptación usa
UN ensayo nuevo con screenshot, no una reclasificación del control sin imagen.

## Siguiente gate

Fijar la carga PanVK/bridge que usa **Wine/DXVK**, y repetir la sonda AA64 D3D/present
antes de WoW. WSI Android no equivale a WSI Xlib ni al wrapper de GameNative.
Investigar primero una ruta existente, no escribir nuevo glue por hipótesis.
BC/R2, frame del juego y aceptación completa del driver siguen abiertos.
