---
id: launcher-gpu-compatibility-2026-10-15
state: done
date: 2026-10-15
---

## Summary

El usuario pide que el launcher detecte la GPU y compruebe compatibilidad, pensando
en ampliar a otros dispositivos Mali. Propuesta: preflight informativo y matriz
versionada de evidencia por dispositivo/backend, no whitelist por nombre «Mali».
Aprobado por el usuario: identificar combinaciones conocidas, reflejar la
configuración usada y señalar desconocidas dentro del APK. Implementación
informativa, sin modificar runtime, driver, datos, permisos ni reglas de lanzamiento.

## Content

### Hallazgos concretos

- El launcher funcional code67 aún no tiene tarjeta de compatibilidad gráfica.
  El esqueleto `android/` muestra «not measured»; no es su implementación.
- En la fuente GameNative fijada de la reconstrucción hay APIs reutilizables:
  `com/winlator/core/GPUInformation.java` obtiene identidad GLES con EGL y cache;
  `GPUHelper.java` ofrece `vkGetApiVersion()` y `vkGetDeviceExtensions()` por JNI.
  No acreditan por sí solas compatibilidad con el perfil efectivo de WoW.
- **No usar `vkGetApiVersionSafe()` como evidencia:** devuelve Vulkan1.3 por
  defecto mientras la consulta está pendiente o falla (`GPUHelper.java:41–47`).
  Un fallback debe mostrarse como desconocido, nunca como capacidad medida.
- `GPUInformation.loadGPUInformation()` usa un wait sin timeout y no garantiza
  limpieza EGL en todos los caminos; no llamarlo en UI ni envolverlo en un
  timeout que deje trabajo nativo sin supervisión. Elegir un lector acotado.
- `ForeverGraphicsProbe.kt` es una sonda Windows AA64, asociada al contenedor
  managed histórico. No ejecutarla silenciosamente desde la PoC unmanaged ni
  reabrir el diagnóstico antiguo para presentar información del dispositivo.
- Históricamente este mismo Mali-G610 falló con driver stock y consiguió render
  con PanVK/DXVK/Wine. GPU/API física, backend efectivo y render son evidencias
  distintas. Referencias:
  [backends](../../../docs/gpu-backend-findings.md),
  [plan histórico de GPU](../gpu-platform-support-plan.md),
  [estado actual](../../../docs/launcher-app-status.md).

### Presentación propuesta

Tarjeta «Compatibilidad gráfica» en la bienvenida, con resumen legible y detalles:

- Modelo de GPU y familia, modelo de dispositivo y Android/ABI.
- API/driver del sistema cuando realmente se midan; origen de cada dato y
  «Sin comprobar» si no se pudo obtener. No convertir GLES en Vulkan.
- Perfil seleccionado (PanVK/DXVK/Wine fijados), separado del driver del sistema.
- Estado de requisitos y nivel de evidencia independientes:
  - **Probado para arranque/render**: coincidencia con una combinación registrada;
    indicar hasta qué etapa se validó, sin atribuir gameplay al teléfono actual.
  - **Experimental / no probado**: requisitos medidos sin fallo conocido, pero
    combinación sin evidencia suficiente. No llamarlo «compatible».
  - **No compatible con este perfil**: incompatibilidad concreta demostrada y
    motivo; no equivale a hardware imposible con cualquier runtime futuro.
  - **Sin comprobar**: medición pendiente, fallida o incompleta; no inferir soporte.
- Acción explícita para volver a comprobar y consultar/exportar un informe
  mínimo sin serial, IMEI, cuentas, credenciales, directorios personales o tokens.

### Contrato de compatibilidad propuesto

1. Snapshot inmutable con versión de esquema, fecha, fuente, estado de cada
   lectura y error/timeout. No llenar campos desconocidos con valores optimistas.
2. Matriz de reglas verificable por combinación de GPU/SoC, Android/kernel o
   interfaz necesaria, driver/backend y versiones/hashes de Wine/DXVK/PanVK,
   arquitectura/build del cliente y etapa de evidencia. No extrapolar una prueba
   G610 a todos los G610, G615 o a toda la familia Mali.
3. Distinguir datos físicos de capacidades efectivas y evidencia estática de
   resultados de una sonda. Falta física de BC no debe rechazar automáticamente
   un perfil que lo gestione/emule y tenga evidencia real.
4. Resultado cacheado con invalidación al cambiar SO/build fingerprint,
   driver identificado, componentes del perfil, cliente o versión del detector.
   Un resultado incompleto no se convierte en validación permanente.
5. IO/consultas fuera de UI, trabajo acotado, cancelación y errores controlados.
   Ni Wine ni WoW se arrancan para mostrar la tarjeta. Sin root, ADB, cambios de
   permisos o modificación de drivers como dependencia del usuario final.

### Límites de la primera entrega

- Detección informativa y clasificación conservadora; **sin selección automática
  ni descarga de drivers**, sin cambiar el perfil probado en este teléfono.
- No instalar Turnip en Mali ni elegir PanVK para todo Mali por el nombre.
- No nuevo benchmark continuo, sonda D3D o rebuild nativo/driver sin aprobar
  específicamente fuente, ABI, coste y aislamiento. Reutilizar lo existente solo
  tras demostrar que informa correctamente y falla de forma acotada.
- **Aprobado:** no introducir todavía un bloqueo duro de «Lanzar» para
  desconocidos. Mostrar advertencia experimental y motivo; un futuro gate de
  rechazo se decide cuando haya requisitos fiables y pruebas. No presentar
  «sin medir» como incompatibilidad para bloquear esta PoC funcional.
- Matriz local/versionada para comenzar, no telemetría, servidor o actualización
  remota de reglas. Ampliaciones Mali requieren dispositivos/evidencias concretas.

## Implementation / Action Items

- [x] 1. Aprobar estados, alcance informativo y ausencia de bloqueo duro inicial.
- [x] 2. Alinear requisito/spec y definir snapshot, reglas de evidencia y cache.
- [x] 3. Implementar lector acotado y política pura con fixtures Mali conocidos,
  desconocidos, datos falsos/fallbacks, error y cambios de perfil/driver.
- [x] 4. Integrar tarjeta y detalles en la bienvenida, con el estilo de code67,
  sin alargar el arranque ni perder estados/progreso existentes.
- [x] 5. Tests dirigidos de política/cache y UI; APK aislado auditado con los
  mismos controles de procedencia, firma y payloads que la entrega visual.
- [x] 6. Validar en el G610 actual, manteniendo runtime/cliente intactos. Otros
  Mali permanecen experimentales hasta disponer de evidencia de esos dispositivos.

## Open Issues

- Aprobado: informativo, sin bloquear «Lanzar» por GPU desconocida.
- La consulta Vulkan actual no expone toda la identidad/driver/features necesarias;
  si hace falta ampliar JNI, presentar el cambio acotado y review antes de
  incorporarlo, no deducir datos de un nombre de GPU o de marketing del SoC.
- Definir exactamente las capacidades mínimas del **perfil custom real**, no las
  del DXVK upstream por su versión. No publicar una tabla de soporte inventada.
- «Probado» para xaga/G610 significa arranque/render observado en su perfil
  histórico, no autenticación/mundo/gameplay, ni una prueba de WoW en code67.

### Decisiones de implementación

- Primera matriz local v1: xaga/G610MC6/API34/kernel5.10.209 y perfil fijo
  PanVK+Proton11ARM64EC+DXVKcustom/cliente70009. Coincidencia **histórica** separada
  de carga efectiva, no verificada al abrir el launcher. Otros dispositivos son
  «Compatibilidad no comprobada». No se inventan incompatibilidades ni se activa
  un perfil distinto automáticamente.
- Identidad GLES leída con EGL14 pbuffer1×1 en worker con cleanup; features/version
  Vulkan **declaradas por PackageManager**, no presentación ni prueba física de
  todas las capacidades del perfil. No usar el fallback1.3 del helper existente.
- Sin caché persistente: snapshot en sesión, recomprobación explícita. Timeout
  UI1500ms con estado desconocido. Lease global del proceso: un EGL bloqueado
  puede retener un único worker hasta retornar; recrear Activity no crea más.
- La revisión independiente pidió resolver recomprobación tras timeout y pruebas
  deterministas de liberación/callback tardío. Se incorporaron antes del build.
- Candidato code68, sin cambio de payloads nativos/runtime; wiring en ambas
  recetas, matriz y política cubiertas por tests JVM dirigidos.

### Entrega y comprobaciones

- Instalado `com.wowforever.arm64probe`, UID10330, code68,
  `0.2.44-functional-launcher-gpu`, actualización sin borrar datos.
- APK SHA-256 `35e75d8f993d98c53ea69c3bf67f233561f5f0c5cc9ecb8ed72692eeb67a70c6`,
  418779356 bytes.174 payloads iguales a code64 salvo marker; firma y JNI
  conservados.2130 archivos registrados sin cambios durante compilación.
- Workspace externo:
  `/home/adrian/wowforever-artifacts/arm64-prototype/launcher-gpu-code68-reviewed/`.
  Recibo, log Gradle, auditoría, package dump, XML y screenshot de bienvenida.
- `/home/adrian/.local/bin/uv run --offline pytest -q tests/test_reconstruct_functional_source.py tests/test_functional_poc_overlay.py tests/test_audit_functional_ui_apk.py`
  →20 passed antes del ajuste del allowlist de lambdas GPU.
- `/home/adrian/.local/bin/uv run --offline pytest -q tests/test_functional_gpu_wiring.py tests/test_functional_launcher_ux.py`
  →10 passed. Incluye concordancia de lo mostrado con constantes reales de
  PanVK/Wine/DXVK/cliente y staging de ambos archivos en ambas recetas.
- Gradle offline con JDK17/SDK existentes:
  `:app:testModernDebugUnitTest --tests app.gamenative.ForeverFunctionalGpuCompatibilityTest :app:assembleModernDebug`
  →BUILD SUCCESSFUL,8m9s;13 tests JVM,0 failures,0 errors. Incluye lease global,
  cierre, timeout, respuesta tardía y casos de GPU/perfil desconocidos.
- Auditoría se detuvo ante seis descriptores Compose singleton nuevos del archivo
  GPU. Se identificaron sus nombres y se admitió solo ese prefijo de fuente;
  tests de auditoría →2 passed, rechazo de singleton ajeno conservado. No ampliar
  allowlist arbitrariamente ni afirmar equivalencia semántica de todo DEX.
- En el teléfono: `Mali-G610 MC6 · arranque/render probado (histórico)` y
  configuración prevista Proton/DXVK/PanVK/D3D11/Vulkan visibles en vertical.
  Esto valida lectura EGL y tarjeta, no backend Wine efectivo ni gameplay.
- PE y `BetaSuspendedTest.wtf` originales conservan SHA-256; captura/parcheOFF,
  perfil Forever intacto. Sin Login, arranque de WoW, cambio de driver o descarga.
- Desconocidos verificados con fixtures, no con otro dispositivo físico. Timeout
  real de driver y share system chooser no forzados en el teléfono; límites
  explícitos, no bloquean la entrega informativa de esta matriz inicial.
