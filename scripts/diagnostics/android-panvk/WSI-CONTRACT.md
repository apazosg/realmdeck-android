# Control Android WSI privado — contrato de integración

Plan aprobado y gate cerrado: `.agents/planning/done/panvk-presentation-plan.md`. POCO USB
`DEVICE_SERIAL`, package `com.wowforever.arm64probe`, UID10330. No otro GPU/app,
HAL/root/cuentas/runtime/JNI patcher. Proceso debug `:panvk_wsi`, Activity ordinaria
`app.gamenative.PanvkWsiActivity` protegida por `android.permission.DUMP`.

## Java/Kotlin ↔ JNI (se fija antes de los escritores)

```kotlin
package app.gamenative
object PanvkWsiBridge {
    // Carga solamente libpanvk_wsi_probe.so, nunca el patcher.
    @JvmStatic external fun nativeRun(
        surface: android.view.Surface,
        icdPath: String,
        outputPath: String,
        runId: String,
        generation: Long,
    ): String
    @JvmStatic external fun nativeCancel(generation: Long)
}
```

`nativeRun` bloquea solo un worker dedicado, nunca UI. Retorna JSON único <=64KiB
schema `wowforever.android-panvk-wsi`, version1. NativeCancel solo marca cancelación
atómica; no destruye recursos ni señaliza procesos. Cancel previo al start no se
borra al entrar en Run. Generation positiva, un RUN/worker por proceso.

RUN generado internamente `PWSI-<32hex>`; ninguna ruta/driver/generation de Intent.
Directorio nuevo `files/forever/controls/panvk-wsi/<RUN>` (aliases appfiles
/data/user/0 o /data/data). Únicos archivos `libvulkan_panfrost.so`, `native.jsonl`,
`state.json`, `result.json`. ICD31.142.504 bytes SHA
`5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`.
Asset `forever/panvk-wsi/libvulkan_panfrost.so`; nuevo archivo exclusivo0600/fsync,
verificación streaming del pin antes de publicar path, NOFOLLOW/canonical scope.

## Orquestación Activity / host

- Primera apertura ADB sin extra de start: genera RUN, prepara solo ICD propio y
  SurfaceView320×240 (píxeles), publica READY con PID/generation/bounds/size/RUN.
- Único Intent extra reconocido `start=true` mediante `onNewIntent`/singleTop:
  inicia UNA vez el mismo RUN READY. No Intent de comando/path/cancel arbitrary.
  Si llega antes de SurfaceReady, queda pendiente una vez; nunca respawn/retry.
- Host toma captura negra READY con checks foreground/component/RUN/surfacebounds
  antes/después. Entonces envía el mismo Intent start y espera <=20s; captura
  posterior concordante. No keys/otrasApps/keyguard/notificaciones.
- `surfaceDestroyed`, resize distinto, stop/destroy o pause tras worker iniciado marcan cancelled y llaman
  nativeCancel si armado; callbacks no liberan handles/window del worker. No nuevo
  worker al recrear Activity ni reasociación a RUN/proceso anterior.
- El Intent singleTop puede pausar brevemente la Activity antes de onNewIntent y
  onResume: sin worker previo no cancela por esa sola pausa, pero nunca empieza
  trabajo nativo hasta hostResumed. onStop sigue terminal incluso antes de start.
- Mantener Surface viva al retornar Run para capturar hasta cierre explícito host.
  Estado RESULT guarda JSON nativo, API result != resultado visual. No Canvas/CPU
  dibujando el triángulo: contenido rojo/verde procede únicamente de la GPU.
- `PanvkWsiPolicy`/flags congelados+DEBUG+process exacto: no iniciar ni consultar
  servicios/cuentas/preloads/migrations del Application normal. Parent añadirá el
  hook de integración tras inspeccionar Hilt/SplitCompat; el worker no edita fork.

## Native gate y resultado

Validar UID10330/process propio/scope paths/RUN/generation/exacto ICD hash/ELF
AA64 antes de dlopen; GIPA/GDPA/resolved PFN owners del ICD, NO libvulkan fallback.
ANativeWindow_fromSurface referencia propia; buffer320×240 ya válido, releases
solo tras retirar trabajo o conservar reference si UNKNOWN/inflight hasta exit.

Enumerar instance extensiones requeridas surface/android_surface (cap256), physical
únicoG610, queueGRAPHICS+surface support, capabilities/formats/modes (caps16/32).
Solo FIFO+UNORMcompatible/2preferidas<=4imágenes/320×240/1cola/priority1/extension
swapchain/default features. Vertex944/fragment304 SPIR-V del GateC existentes.
Rojo sobre clearverde,3framesmáximo/1submit porframe. Acquire500ms/fence1s,
semaphores sin reuso pendiente, attachment→PRESENT dependencies explícitas.
Una QueueWaitIdle final si workretirable, fueraUI, sin promesa de timeout Vulkan.
DriverPresent puede llamar DeviceWaitIdle internamente y mentir éxito de lock/post.

Journal <=256 líneas /64KiB privado, fsync antes/después de cada call sensible;
la cota de filas se ajusta al par before/after de setup y tres frames; el límite
de bytes no aumenta. Call witnesses <=96, PFNs <=64, jamás truncación silenciosa.
fila esquema/version/RUN/pid/generation/event/resultado bounded, nada de memoria
de juego/cmdlines/credenciales. Return incluye scope/ICD owners/surface/queue/
swapchain/frames (<=3)/apiPresentSuccess/cancel/cleanup/failureStage/error real.
Statuses `API_PRESENT_COMPLETED`, `VK_ERROR`, `UNSUPPORTED`, `CANCELLED`, `UNKNOWN`;
ninguno significa screenshot/PRESENT aceptado. `androidPresentationObserved`,
`wine`, `d3d`, `wow` false siempre en native/Activity; HOST acepta pantalla aparte.

Error/out-of-date/lostSurface/deviceLost/cancel: parar newacquire/submits. No retry,
resize recreation/reset ni liberar en vuelo si fence/queue no demostró retiro;
cleanup false/UNKNOWN implica recuperación host limitada al paquete propio.

## Ownership de archivos para escritores

- Native builder: solo NUEVOS `PanvkWsi.c`, `build_wsi.sh`, `README-wsi.md`,
  `tests/test_android_panvk_wsi_native.py`. Toolchains/payload existing; SO<=256KiB
  externo `controls/panvk-wsi-v6/libpanvk_wsi_probe.so` tras reparar/revisar v1-v5;
  se preservan variantes anteriores. No parent Gradle/ADB/fork.
- Kotlin builder: solo NUEVOS overlays `PanvkWsiActivity.kt`, `PanvkWsiBridge.kt`,
  `PanvkWsiPolicy.kt`, `PanvkWsiStore.kt`, `PanvkWsiPolicyTest.kt` (todos mismo
  package app.gamenative). No recipe/fork/manifest/PluviaApp/build/ADB/native edits.
- Parent integra Application/manifest/assets/recipe/version/provenance/audit,
  sincronización previa y host captura. No dos writers en mismos paths.
