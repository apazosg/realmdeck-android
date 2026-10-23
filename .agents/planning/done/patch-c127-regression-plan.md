---
id: patch-c127-regression-2026-10-22
state: done
date: 2026-10-22
---

## Summary

El usuario recuerda que C127 ya consiguió Login y no quiere repetir el rastreo
de claves. Reutilizar esa solución y aislar una diferencia posterior, sin abrir
otra investigación genérica ni modificar el escritor nativo.

Propuesta aprobada: una candidata comparativa que conserve C133
y revierta **solo el adelanto del polling de C128**, recuperando el inicio tras
el primer Enter de C127. No es un diagnóstico confirmado ni un rollback completo.

El 2026-10-22 el usuario aprueba este alcance con «vale, hagámoslo». Se conserva
la solución C127 y se aísla esa única diferencia; no se reabre el rastreo de claves.
La candidata entregada es C134 / `0.2.110-c127-enter-gate`; el usuario confirma
Login correcto tras el segundo Enter y una espera adicional. Se cierra esta
comparación acotada, no la investigación de todos los tiempos de arranque.

## Content

### Baseline recuperada

- C127 `0.2.103-guarded-group8-apply`, evidencia real de aplicación y Login/mundo;
  [registro causal](../../../docs/arm64-forever-connection-progress.md) y
  [solución aprobada](arm64-server-identity-plan.md).
- El run conocido `1792615126263` tiene 39 scans INCOMPLETE, APPLIED32 y
  98 ALREADY_PATCHED. No necesitó cobertura global completa para validar su bloque.
- C133 falla en el ensayo manual sin alcanzar aplicación: 238 scans y cero
  candidatos observados o `patch_apply` en el snapshot posterior al Login.
- Nativo idéntico SHA256
  `2f7014d4133ba17d668e193b5aea0e7290d5a093168c58ace22b83647c69bd67`;
  coinciden 16 campos del lanzamiento. USB conocido montado en la comprobación
  actual. Evidencia saneada bajo el canonical C133 final, archivos
  `known-good-comparison.json`, `current-usb-comparison.json` y
  `manual-login-summary.json`. No se publican JSONL privados ni memoria.
- No hay éxito intermedio documentado: C128 no instalada, C129 instalada sin
  ensayo positivo y C130 no instalada. La última referencia positiva es C127.

### Única variable de la comparación

- Recuperar el gate de lectura tras Enter de C127. `nativeBegin` continúa antes
  de Wine para demostrar la pertenencia del hijo. Conservar watchdog y límites
  existentes, aislamiento por intento y cierre exactamente una vez.
- Conservar los arreglos de Ajustes/gráficos, datos y perfil derivado C131–C133;
  no reinstalar C127 completa, reconstruir WTF ni resetear opciones guardadas.
- El estado debe ser veraz: pendiente antes de comenzar las lecturas, buscando
  cuando empiecen y verificado solo tras APPLIED/ALREADY_PATCHED de apply.
- Sin cambios Rust/JNI, ancla, relleno, claves, permisos, contrato, runtime,
  captura, opt-in o selección de candidato. Sin nuevas opciones de diagnóstico.
- Es una excepción experimental al [adelanto aprobado](../early-group8-apply-plan.md),
  no una modificación silenciosa de aquella decisión. La aprobación del usuario
  cubre esta comparación temporal.

### Criterios de aceptación y parada

1. Una diferencia temporal acotada; las pruebas existentes comprueban ausencia
   de polls antes de Enter, inicio posterior, cancelación, límites y estado veraz.
2. Revisión independiente de concurrencia, build auténtico y auditoría APK con
   el mismo nativo. No recompilar ni sustituir la biblioteca que ya funcionó.
3. Instalación conservando datos solo después del cierre normal del juego,
   coordinada con el usuario y verificando versión/hash/UID.
4. Un Login manual usando Enter como en C127; contrastar los mismos eventos
   scan/apply. No exigir verificación previa ni automatizar credenciales.
5. Registrar el resultado. Si sigue sin aplicar, detenerse y descartar esta
   comparación como solución: no encadenar cambios del lector ni búsquedas nuevas
   sin presentar la evidencia y decidir el siguiente paso.

## Implementation / Action Items

- [x] 1. Recuperar el éxito C127 y comparar evidencia existente con C133.
- [x] 2. Aprobar la única variable y reflejar su impacto en requisitos/pruebas.
  `US-004-AC-12/FR-018/TC-024` explicitan el estado pendiente antes de buscar;
  no cambian el contrato ni las guardas de escritura de US-002. Las regresiones
  temporales existentes comprobarán el gate recuperado.
- [x] 3. Implementar la candidata mínima y revisar concurrencia.
- [x] 4. Compilar, probar y auditar sin cambiar el nativo.
- [x] 5. Coordinar cierre, instalar, realizar un Login manual y registrar resultado.
  Instalación completada tras confirmar «Ya está cerrado» y verificar quiescencia;
  ensayo manual positivo después de un fallo inicial y un segundo Enter, sin
  automatizar credenciales ni exigir estado verificado antes del primer envío.

### Resultado local y despliegue

- Código `b92e193`: solo gate y estados de inicio en producción; Activity, hook de
  lanzamiento, Rust, JNI, contrato, preferencias y datos no se modifican.
- Revisión independiente: sin bloqueo de producción; detectó dos pruebas con índices
  de estado obsoletos, corregidas para localizar el primer final. La regresión del
  escritor comprueba pendiente sin lecturas antes de Enter y buscando/verificado
  tras armar, manteniendo las pruebas de cancelación e historial tardío.
- 46 JVM pasan; 24 host de reconstrucción pasan. Fixtures/UX: 27 correctas, 6 fallos
  previos conocidos. Build correcto en 6min54s; no se afirma suite global verde.
- Auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2215 fuentes sin cambios,
  178 payloads, mismo nativo C127; sin modificar el auditor ni sus admisiones.
- APK SHA256 `0c2eca5a7f573311a976e13f75c330a5bde76fbd25e20743da1b747d7f52a8d4`.
  Evidencia en `/home/adrian/wowforever-artifacts/arm64-prototype/client134-c127-enter-gate-20261022-isolated/`:
  `recipe-snapshot-manifest.json`, `reconstruction-receipt.json`, `build.log`, XML,
  `apk-audit.json` y `deployment-receipt.json`.
- Instalación conservadora verificada: versión134 / `0.2.110-c127-enter-gate`,
  UID10330 sin cambios, hash instalado igual al auditado, sin limpiar datos ni abrir
  el juego desde el agente.

### Resultado físico de la comparación

- Al principio el usuario comunica que falla y sigue apareciendo buscando durante
  la pantalla negra. No se cambia código, configuración ni lector tras ese aviso.
  El mismo intento `1792698913622` ya registra 41 scans INCOMPLETE, un APPLIED de
  32 bytes y 16 ALREADY_PATCHED en el primer snapshot.
- El usuario pide esperar y después confirma: «tardo un poco más tras el segundo
  enter ponía parche aplicado y si funcionó el login». El snapshot posterior del
  mismo intento conserva APPLIED32 y 66 ALREADY_PATCHED, sin reiniciar el juego
  desde el agente. `successful-login-summary.json` guarda la confirmación y los
  contadores saneados; los JSONL completos permanecen privados fuera de Git.
- El primer scan registrado llega 142940ms después de Begin y APPLIED a211645ms
  de Begin. No hay timestamps de Enter: estos números no identifican cuál Enter
  abrió el gate ni miden la espera desde el segundo. Los límites no se amplían.
- C134 vuelve a tener prueba real de aplicación y Login en este dispositivo.
  C133 no aplicaba en su ensayo. La comparación positiva no demuestra por sí sola
  que el adelanto C128 fuera la única causa de la regresión ni estabilidad general.

## Open Issues

- La reconstrucción inicial se bloquea correctamente por trabajo concurrente de
  privacidad en `apply_overlay.py` y el contrato histórico de disposición:
  cambiarían dos payloads congelados. No se revierten esos cambios ajenos ni se
  relaja la paridad. Se usó un snapshot de fuentes versionadas de `9e168db`
  más únicamente los cinco archivos de código/pruebas propios de C134, ejecutando
  la receta y el auditor canónicos desde ese snapshot externo. Quedan conservados
  el log del bloqueo y el manifiesto de hashes del snapshot para trazabilidad.
- El inicio temprano es una hipótesis de regresión, no una causa demostrada.
  Los campos nominales iguales no acreditan contenidos idénticos de WTF/PE.
- Persisten demora y percepción de búsqueda durante la pantalla negra. No se
  declara corregida esa presentación ni se cambia el gate o el lector para
  perseguirla dentro de esta comparación ya cerrada.
- No se cambia la política de candidato observado ni la relación entre cobertura
  y autorización; la tensión documental FR-002/TC-002 queda fuera de esta prueba.
- [Lote A](memory-patch-hardening-plan.md) completado; los riesgos del CLI,
  mantenimiento permanente y redescubrimiento global permanecen fuera de alcance.
