---
id: panvk-presentation-2026-10-07
state: done
date: 2026-10-07
---

## Summary

El usuario aprueba la dirección «sigamos si» después del triángulo PanVK
offscreen. Este documento concreta el cambio pendiente: mostrar ese triángulo
mediante una superficie Android real, antes de integrar Wine/DXVK/WoW.
Plan escrito aprobado posteriormente: «si hagamoslo». Se implementa la Activity/JNI
diagnóstica manteniendo los límites que siguen.

Único teléfono: POCO xaga `DEVICE_SERIAL`, Android14/API34, Mali-G610 MC6.
Mismo ICD beta.17, 31.142.504 bytes, SHA
`5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`.
No Turnip, root, HAL, firmware, otro móvil, features ficticias ni memoria Forever.

## Content

### Hechos y brechas

- Device/queue, transfer4KiB y shader/triángulo64×64/readback ya medidos en hijos
  `runas_app`; no son prueba de Activity, SurfaceFlinger o presentación.
- El CLI no tiene un `ANativeWindow` real. Se necesita una Activity y SurfaceView;
  no extraer superficies de otra app ni inventar un puntero desde ADB.
- Fuente port fijada en `b4ee2e2490ffad0e6625ecbd78ef016bdf6cf5ce`:
  `patches/wsi/016-android-surface-swapchain.patch` implementa superficie Android,
  AHBs y una copia CPU de píxeles GPU hacia `ANativeWindow`; patch017 limita el
  fallback de layout a AHBs propios. Es WSI con copia, no promesa de zero-copy.
- Esa referencia llama internamente a `DeviceWaitIdle()` y puede devolver éxito
  aunque falle `ANativeWindow_lock`/AHB lock. No se prueba equivalencia de fuente
  y todos los caminos del binario: registrar resultados y exigir evidencia visual.
  El deadline host no garantiza preempción de una llamada kernel/driver bloqueada.
- El readback original tenía dependencia render→copy incompleta. Corregirla antes
  del siguiente control; no conservar la afirmación «solo puede causar falso fallo».
- WSI Android no es WSI Xlib de Wine. Incluso un triángulo visible no acepta aún
  DXVK/D3D, WoW, BC, robustness2 ni el bridge Wine/X11.

### Ruta mínima elegida

1. **Cerrar la sincronización previa:** nueva variante de Gate C, preservando v1,
   añade dependencia `0→EXTERNAL` COLOR_ATTACHMENT_OUTPUT/COLOR_ATTACHMENT_WRITE
   → TRANSFER/TRANSFER_READ; exige queue GRAPHICS. Tests de packet/createInfo y
   parser, build/pins/audit y un readback corregido. Ninguna nueva feature o shader.
2. **Activity debug dedicada dentro del APK existente**, con SurfaceView limitada
   a buffer320×240 y JNI `ANativeWindow_fromSurface`. Se mantiene package, firma,
   UID y datos. No MainActivity/Wine/XServer/control de juego durante este ensayo.
3. Activity en proceso propio `:panvk_wsi`, protegida por `android.permission.DUMP`
   y BuildConfig.DEBUG/flag congelado. ADB shell tiene DUMP (lectura comprobada);
   no nuevo permiso concedido ni launcher externo de comandos. Nombre/RUN/cwd/ICD
   son fijos o generados internamente, no rutas/offsets/driver provenientes del Intent.
4. Hook mínimo de Application solo para ese proceso debug: evita migrations,
   preload de runtimes, cuentas/analytics/downloads y servicios GameNative que
   `PluviaApp.onCreate` normalmente inicia. Proceso normal y ruta de juego intactos.
   No nuevo servicio foreground ni sesiones/grupos; review de esa separación.
5. Asset ICD reutilizado, copiado exclusivamente a nuevo directorio privado con
   SHA/tamaño/mode/ELF/owner/identidad comprobados. Bridge nativo separado del JNI
   patcher, sin cargar `forever_patcher_jni` o iniciar sus workers.

### Control Vulkan fijo

- Enumerar instance extensions (no inferirlas desde las device extensions previas):
  requerir VK_KHR_surface y VK_KHR_android_surface. Crear instancia con esos dos
  nombres; resolver PFNs únicamente mediante GIPA/GDPA del ICD privado comprobado.
- `vkCreateAndroidSurfaceKHR` recibe exactamente nuestra Surface. Medir soporte,
  capacidades, formatos y modos con counts acotados y selección determinista.
  Queue family debe tener GRAPHICS y surface-support; no aceptar solo TRANSFER.
- Device: una cola/priority1.0, extension VK_KHR_swapchain, features mínimas sin
  override. Swapchain preferido2 imágenes, máximo4, formato UNORM compatible,
  FIFO, extent real320×240; una superficie mayor/indefinida fuera de cap se rechaza.
- Reutilizar SPIR-V validado del triángulo (vértice944B, fragmento304B), shader
  rojo y clear verde. No dibujar el triángulo con Canvas/CPU desde la Activity.
- Máximo3 frames, un submit por frame. Acquire timeout500ms, fence wait1s,
  semaphores propios sin reciclar mientras puedan estar pendientes;
  layouts/dependencies de attachment a PRESENT explícitos. Una sola
  `vkQueueWaitIdle` al final para retirar operaciones propias antes de destruir
  objetos: no tiene timeout Vulkan, se ejecuta fuera del hilo UI bajo deadline
  host20s. Si no retorna exitosamente, no frees en vuelo ni cleanup=true. Advertir
  también el WaitIdle interno que documenta el driver, no fingir preempción kernel
  ni observación de sus llamadas internas por una mera referencia fuente.
- Error, surfaceDestroyed/pause/cancel, OUT_OF_DATE/SURFACE_LOST/DEVICE_LOST o
  espera incompleta detienen nuevas adquisiciones/submits. No recreaciones,
  retries automáticos, reset GPU, otras colas ni otro backend para aparentar PASS.
- Refs ANativeWindow retenidas mientras sean usadas, liberar una vez por propiedad;
  el callback UI no destruye handles que el worker aún usa. Un solo worker/RUN,
  cancelación atómica y revalidación de generation antes de cada frame.
- Destruir solo recursos válidos cuyo trabajo se demuestre retirado. Un present
  bloqueado o trabajo incierto no se resuelve liberando objetos en vuelo. Registrar
  UNKNOWN y terminar el paquete propio mediante framework bajo supervisión host;
  no afirmar cleanup completo o containment. Antes de repetir tras hang/timeout
  se decide recuperación explícita, nunca un bucle de pruebas.

### Aceptación y captura

- `VK_SUCCESS` de Present es solo `apiPresentSuccess`, NO screenshot ni frame.
- Capturas ADB acotadas en foreground verificado de la Activity/proceso/RUN propio,
  con bounds/tiempo de la Surface; nunca keyguard/notificaciones/otra aplicación.
  Registrar captura previa en negro y posterior: triángulo rojo sobre verde en
  región esperada y correspondencia temporal. No usar readback offscreen solo como
  evidencia de pantalla ni una etiqueta UI como prueba gráfica.
- Resultado `ANDROID_PRESENTATION_OBSERVED` solo API/identity/frames/lifecycle y
  captura visual real concordantes; si APIs pasan sin imagen => OBSERVATION_INCOMPLETE.
  Hardware general/DXVK/D3D/Wine/WoW/login siempre no verificados en este gate.
- Journal/output/paths privados con cota, capture fsync antes del arranque, stdin
  cerrado, screenshot/maps/errores bajo UID y componente propios. Max20s ejecución
  host + cleanup acotado; propio package force-stop solo por incertidumbre o tras
  conservar evidencia al cerrar el ensayo, no señales a PIDs/grupos adivinados.

### Verificación, presupuesto y límites

- Revisión independiente de JNI/window lifetime, fence/present/cancel y entrypoint
  propio. Tests host fakePFN de extensión ausente/counts/bounds/family/error, flags,
  screenshots no coincidentes, surface destruida/resize/latecallback/one-spawn y
  que el proceso normal no inicia WSI ni el proceso WSI servicios o memoria.
- Native propio <=256KiB, ICD existente sin modificación, toolchains JDK17/NDK24/
  SDK/Gradle existentes. Sin herramientas nuevas, rebuild Mesa/Wine, cloud o pagos.
  Build incremental offline single-worker; fuentes/binaries/evidencias grandes
  fuera del repo. Audit APK real/provenance/cert/ABI/assets/native current hashes,
  receta fresca/idempotente y version nueva, preservando APK41 y controles anteriores.
- Una prueba USB AOA/ADB automatizada después del paso de sincronización, no SIM/
  input-security toggle/root/cuentas/manual installs. Se medirá el dominio real
  `untrusted_app` de Activity; runas metadata anterior no lo acepta de antemano.
- El success WSI Android no autoriza integrar automáticamente Wine/Xlib; ese
  bridge y la sonda D3D existente son gates posteriores con sus propias pruebas.

## Implementation / Action Items

- [x] 1. Contrastar WSI privado, necesidad de Surface y limits del port fijado.
- [x] 2. Confirmar el plan escrito de Activity/JNI/presentación aislada.
  Usuario: «si hagamoslo».
- [x] 3. Corregir/render→copy y cola gráfica; verificar readback de variante nueva.
  Native v2-sync, 75 tests, RUN PVK-d127284c82924061a811d0077b1a804d.
- [x] 4. Implementar Activity/proceso debug/bridge/hostcapture con límites definidos.
- [x] 5. Revisar, testear, construir/auditar y ejecutar una prueba WSI automática.
  Code47/0.2.23; 12 casos JVM, fixtures nativos/HOST, fresh78 y auditPASS.
- [x] 6. Clasificar resultado y gate Wine/Xlib/DXVK siguiente sin promesa jugable.
  PWSI-94e1a0a08761465f912127847b743ce8: 3 frames, screenshot rojo12,5%/verde87,5%,
  journal y lifetime concordantes, ACCEPTED/ANDROID_PRESENTATION_OBSERVED.
  Ver `docs/panvk-presentation-poco-results.md`. No se acepta Wine/D3D/WoW.

## Open Issues

- Alcance escrito aprobado. No se amplía a Wine/Xlib, DXVK o WoW por esta aceptación.
- Esta Activity y el WSI privado pasan el ensayo fijo G610/API34. No se retuvo la
  etiqueta SELinux exacta, no inferir certificación universal del dominio.
  Driver minApi35 se conserva; no falso rebuild/API completo claim.
- Calls internas WaitIdle/locks y comportamiento de fuente/binario no garantizan
  plazo o post exitoso; no quitar este riesgo del informe aunque exista screenshot.
- BC/robustness2, soporte DXVK, Wine/Xlib, WoW/frame/login siguen pendientes.
- Journal conserva 64KiB pero el cupo pasa a 256 filas para pares before/after
  de setup y tres frames (96 calls máximas); I/O/cupo agotado detiene trabajo nuevo.
  No se añade ninguna llamada Vulkan ni capacidad por aumentar las filas.
