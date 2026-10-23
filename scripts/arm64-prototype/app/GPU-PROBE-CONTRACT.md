# Contrato del ensayo gráfico Mali (baseline40 y control55)

No es login ni soporte GPU aceptado. El baseline aprobado el2026-10-03 fue code40:
Proton AA64, DXVK AA64 2.4.1, wrapper/System Vulkan, no-OpenGL y memoria apagada.

## Sonda

- Fuente propia en `scripts/diagnostics/arm64-graphics/`, PE32+ machine AA64.
- Nombre fijo **`GraphicsProbe-ARM64.exe`**; archivo verificado por SHA-256/tamaño
  de manifiesto generado fuera del repo e incorporado al APK, no nombre heurístico.
- CLI único: `--log A:\Logs\<RUN>-graphics-probe.jsonl --run-id <RUN>`.
  RUN ASCII estricto; log Windows absoluto. No espacios/quotes en estos argumentos.
- Líneas JSON schema 1 con `runId`, `event`, datos acotados; stdout duplicado para
  captura existente y fichero durable. No secretos ni dumps de memoria.
- `probe_begin`, identidad PE/componentes (`modules`), loader/capabilities Vulkan,
  `d3d_attempt` (HRESULT hex, driver type, feature levels pedidos/resultante),
  `device_created`, shader/window/swapchain y `present` si se consiguen.
- Terminal `probe_end` incluye estado/cuentas de presentación. Cero exige
  dispositivo D3D válido, shaders, triángulo, presents correctos y cierre/log durable;
  no se afirma éxito por crear ventana/adapter o por devolver HRESULT aceptable.
- Inicialización válida D3D: adapter explícito + UNKNOWN, o null + HARDWARE;
  ningún override de features. Rechazo por GPU es resultado, no error del test.
- Vulkan consultado por la interfaz Windows de Wine, preferentemente la misma
  `vkGetInstanceProcAddr` de winevulkan; loader no disponible se declara UNKNOWN.
  No alterar capacidades/API del proveedor ni sustituir librerías para medir.
- Medir campos DXVK requeridos y extensiones/identidad efectiva; reportar de qué
  loader y función procede. Límite de tiempo/presents y liberación recursos propios.

## Integración en el APK

- Perfil diagnóstico **frozen por APK** `GRAPHICS_PROBE_ENABLED=true`; rama WoW
  normal intacta cuando false. No extras/Intents públicos de comando/path arbitrario.
- Mismo directorio gestionado/drive A:/prefijo/backend; no tocar el exe oficial ni
  su Config.wtf. Copiar solo la sonda fija si manifiesto/hash/tamaño/PE coinciden.
- Preparación y bootstrapping stock existentes; la ruta seleccionada y args se
  congelan antes del spawn. Journal distingue `graphics_probe` de `wow_client`.
- Mantener un token de spawn para el **archivo exacto**, cwd, RUN y generación;
  no ampliar a todo `*.exe`. No armar memoria ni interpretar el probe como WoW.
- Los resultados viven en A:\Logs y en los logs stdout/privados; el host recoge por
  ADB el JSONL exacto del RUN sin requerir SAF o pasos manuales.
- Persistir perfil/PE/hash/argv/preload/ICD. Ningún PASS de sonda implica render
  WoW, suficiente CASC, CertStore, autenticación o soporte de otra familia GPU.
- Una ejecución AOA USB en `DEVICE_SERIAL`, previo reboot por Wine anterior;
  captura externa y stop del paquete propio según la política actual.

## Límites de esta fase

Sin nuevo driver/tuple/guest VirGL hasta demostrar ABI y presupuesto. No usar el
VirGL GLIBC local como Bionic. No rebuild Wine/Mesa, gastos cloud, root/permisos,
grupos/servicios/handler SIGINT ni señales a PIDs ajenos. Artefactos fuera del repo.

## Variante readback-v1 (code55, aprobación «si hazlo»)

- Plan: `.agents/planning/done/wine-aa64-readback-plan.md`. Tuple lab-only
  `55 / 0.2.31-automated`; no sustituye la entrega normal code33.
- PE propio **`GraphicsProbeReadback-ARM64.exe`**, machineAA64/96768bytes/SHA
  `80c1c260d7781bda73e3d5552c7ca730e72382288a9256b4a78c7899d3f0c3de`.
  Los dos PE anteriores y sus pins permanecen empaquetados sin overwrite.
- Pila de54 intacta: Proton/DXVK AA64, noGL, compiler47 AA64 reparado y PanVK
  beta17 privado por RUN mediante las tres variables Adrenotools; no HAL global.
  Memoria/auth/WoW OFF; ninguna ruta o reparación nueva por extras.
- Tras los mismos prerrequisitos ventana/swapchain/shaders, marker READY y pausa
  cooperativa<=5s dentro de30s, seguida de RESUMED. No garantiza captura ancestry.
- Fases únicas y ordenadas: `offscreen_clear`, `offscreen_draw`,
  `backbuffer_clear`, `backbuffer_draw` (última: `triangle_readback`). Textura
  independiente320x240/RGBA8/DEFAULT/RENDER_TARGET, sin cambiar geometría, shader,
  viewport, culling/blend/depth ni añadir Flush/query/async/GPL.
- Cada fase registra recursos/HRESULT, CopyResource `VOID_SUBMITTED`, Map,
  pData/RowPitch/dimensiones, counts/FNV y cinco RGBA fijos. Clear uniforme no es
  triángulo. Draw requiere>=128píxeles coloreados; health posterior precede Present.
- El terminal API exige las cuatro fases verificadas, health S_OK y30Present S_OK.
  Timeout nunca puede coexistir con PASS. Los límites son cooperativos: no prometen
  preemption de llamadas GPU ni contención kernel; cleanup propio sigue bounded.
- Fallo anterior a la pausa conserva FAILED/DEVICE_REJECTED sin inventar offscreen;
  no se reordena la WSI en este control. Mismo-PID/UID/startTicks/RUN/ancestry y
  PE+PanVK RX siguen obligatorios; ausencia => CONTROL_INVALID/UNKNOWN.
- `reportedStatus` es el terminal nativo; `apiQualificationObserved` exige todos
  los witnesses de API/identidad. Sin prueba visual independiente, el veredicto
  global no puede ser PASS (`INCOMPLETE` incluso con API válida).
  `visualPresentationObserved=false` y `wow=false`: screenshots no se autocalifican.
- Único intento USB en el POCO autorizado tras reboot/bootId nuevo y muestra de
  UID propio vacío, host<=120s más cleanup bounded; sin retry ni login.
- Resultado cerrado en `docs/wine-aa64-readback-results.md`: cuatro fases/30Present
  positivas, global CONTROL_INVALID por mapping/ownership, visual no observada.
