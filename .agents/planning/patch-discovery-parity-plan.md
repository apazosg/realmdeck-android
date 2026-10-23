---
id: patch-discovery-parity-2026-10-23
state: in-progress
date: 2026-10-23
---

## Summary

El usuario pide «volvería a verificar el funcionamiento del parcheador oficial o
del de x86-64 y mejoraría el nuestro de arm64». Se suspenden las rondas A/B sin
datos técnicos correlacionados. Según sus observaciones, sin Login no hubo ninguna
aplicación; con Login siempre terminó aplicándose y la mayoría de segundos envíos
funcionaron. B2 C140 aplicó pero desconectó también con contraseña manual.

La reverificación de fuentes está documentada en
[comparación](../../docs/patch-search-x86-comparison.md). El oficial Linux advierte
expresamente que se reintente sin cerrar el juego si falló el primer acceso. No
hay un hook de Login en esa referencia ni en el supervisor x86-64 inspeccionado.

Candidata aprobada sobre C140 que acorte la revisita de memoria donde el
parche ya está autorizado, con datos mínimos para medir el resultado. No promete
parchear antes de que exista el bloque ni resolver la desconexión B2.

## Content

### Decisión aprobada

El usuario aprueba el2026-10-23: «si, hagamos eso». La aprobación incluye el cambio
explícito de alcance y el resumen observacional descritos abajo; no autoriza input
automatizado ni cambios adicionales de mantenimiento.

- Cambiar el **full scan funcional no-capture** de «prioridad + todas las legibles»
  a «solo regiones que cumplen el predicado existente de aplicación». Reutilizar
  `is_writable_anon_private`: conservar privadas anónimas/pseudo RW y RWX, no el
  filtro RW más estrecho del oficial. El cursor, solape, límites y revalidación
  continúan dentro de ese conjunto. No agregar un selector/opción de escaneo.
- Eliminar el fallback de este modo es un cambio explícito de
  [US-002-AC-010/FR-011/TC-012/013](../specs/US-002.md), no una autorización de
  escritura nueva. Captura/codeCapture, CLI y sus herramientas quedan iguales;
  no afirmar cobertura de toda la memoria legible ni unicidad global.
- No modificar la pausa1s, presupuestos por sondeo, máximo180s/total900s, arranque
  observado de WoW, opt-ins, mantenimiento, grupo8/32B, validación480B, identidad,
  mapeo, cancelación, postcondición o enclavamiento tras fallo parcial.
- Mantener C140, botón de cuenta y arreglos de cierre. No hooks nuevos, intentos
  fallidos automáticos, input de credenciales, cambios de runtime/driver, direcciones
  precalculadas ni cache entre procesos. No buscar claves nuevas.

### Datos de verificación, sin nuevas barreras de usuario

- Un resumen tipado y acotado del último intento: alcance del scan, tiempos
  relativos monotónicos de inicio/primer candidato/primera aplicación, número de
  sondeos/pasadas, bytes realmente leídos, errores y resultados de nativeApply.
  Reutilizar contadores existentes; sin dumps ni guardar el JSON nativo arbitrario.
- Registrar el hito del envío asistido ya existente cuando se complete usuario →
  Tab → contraseña → Enter, como **envío**, no autenticación. No deducir Login de
  cualquier Enter manual ni automatizarlo. No pedir ni registrar valores de cuenta.
- El resumen no contiene claves, direcciones, memoria, credenciales, argumentos,
  logs del cliente ni capturas. No exportar RUNs crudos; no agregar diálogos,
  confirmaciones, permisos ni otro bloqueo al botón. La observación no autoriza
  writes y un fallo al guardar el resumen no altera el parcheado.
- Alcance de cobertura explícito: completar las regiones elegibles no significa
  completar todas las legibles. No usar un tiempo pequeño para afirmar éxito de Login.

## Implementation / Action Items

- [x] 1. Aprobar el alcance elegible y los datos anteriores; actualizar primero
  AC/FR/TC afectados de US-002 y la trazabilidad de los hitos observacionales.
- [x] 2. Implementar la restricción reutilizando el predicado, sin cambios de
  autoridad ni otro algoritmo de mantenimiento. Mantener progreso, identidad y
  cancelación ante mapas nuevos, retirados o reclasificados.
- [x] 3. Añadir el resumen mínimo con aislamiento del intento actual. Pocas
  regresiones: exclusión de regiones no aplicables, revisita tras creación tardía,
  cursor/budget/cancelación, y resumen fiel a resultados reales sin datos de cuenta.
  Reutilizar tests de aplicación/seguridad existentes, no matrices ni frameworks.
- [x] 4. Revisar independientemente seguridad/concurrencia y semántica de cobertura;
  pruebas focales, JNI con procedencia nueva y hashes auténticos, reconstrucción
  canónica y auditoría de APK sin ensanchar excepciones.
- [ ] 5. Coordinar actualización sin borrar datos y **un ensayo manual informado**
  con Login durante búsqueda, correlacionado con su resumen. No esperar un parche
  antes de Login como requisito ni repetir A/B sin datos. Si falla, detenerse y
  analizar ese resultado antes de otro cambio.

## Open Issues

- Aprobación registrada; US-002 y US-004 se actualizan antes de implementar.
- El ahorro de tiempo no está medido en ARM64: puede persistir coste de lectura o
  creación del store. No extender plazos ni cambiar errores/pausas a ciegas.
- El oficial Linux fue descargado con HTTP200 y SHA idéntico a la referencia previa;
  GitHub público devuelve404. No se verifica el binario oficial Windows actual ni
  que el servicio x86 desplegado corresponda a las fuentes locales.
- El indicador aplicado no prueba qué copia usa la autenticación. Mantenimiento de
  segunda copia y causa B2 quedan fuera del primer cambio; compartir esa limitación
  con las referencias no la convierte en un defecto probado de este intento.
- Persiste la tensión histórica entre FR-002 y TC-002 respecto a cobertura
  incompleta/unicidad; no alterar esa política dentro de esta optimización.

## Resultados locales — C141 instalada, ensayo pendiente

- Código `8885eae`, versión141/`0.2.117-eligible-scan`. Solo el full scan funcional
  no-capture cambia de alcance; captura, CLI, escritor, mantenimiento y plazos
  conservados. El parser compartido sigue siendo all-readable. El resumen se
  guarda asincrónicamente en `files/patch-summary.json`, fuera de RUNs sensibles,
  con14 campos fijos y sin valores de cuenta ni texto de errores.
- 107 pruebas nativas focales pasan; 62 JVM (45 UX/sesión,4 resumen,13 entrada
  asistida) y44 host (33 reconstrucción/tripwires,11 auditor) pasan. No se ejecuta
  ni declara verde la suite global. Revisión independiente completada, también
  de las diez admisiones DEX exactas; no hay prefijos ampliados. No se prueba de
  forma determinista el cambio de propietario durante una escritura en vuelo;
  la inspección no encuentra un defecto de orden de publicación.
- JNI nuevo construido con NDK24/API29, procedencia antes/después coherente y ELF
 16KiB: SHA256 `79af82848e15de5395ddfb52f916c95ff3212fdb0e579cc69b96e2d32e4c1bd3`.
- Reconstrucción canónica y build7m7s; auditoría
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,2221 fuentes sin cambios,178 payloads.
  APK484663455bytes, SHA256
  `fa6908c1d1a193975f7451a08b3f36a07effa37a289ea866261c57af5676d5b2`.
  Evidencias bajo `/home/adrian/wowforever-artifacts/arm64-prototype/client141-eligible-scan-20261023/`
  (recibo, build.log, XML, apk-audit.json y logs nativos).
- ADB enumera el móvil, pero el usuario indica «Todavía está abierto». No se
  instala en ese momento ni se solicita cierre al proceso desde el host. Tras
  «sigue», el preflight fresco doble prueba app/logcat y52 grupos terminales propios
  con identidad estable y Threads:1, sin juego vivo ni servicios. install-r correcto,
  versión141/nombre/UID10330 y SHA instalado iguales al auditado; recibo y prueba
  de script externos. Sin borrar datos, matar procesos, abrir juego ni enviar input.
  Aceleración y Login siguen sin verificar físicamente; el punto5 continúa pendiente
  del ensayo manual, sin reanudar rondas A/B ni declarar resuelta B2.

### Ensayo comunicado — primer Login C141 positivo

El usuario confirma un intento sin Login agotado y otro con Login que entra al
primer envío, sin necesitar un segundo. Es el primer resultado manual positivo
de esta candidata; no una garantía universal ni una medición del tiempo ganado.
La lectura exclusiva del resumen tipado no está disponible por desconexión ADB;
no se exportan RUNs ni se sustituyen datos faltantes por inferencias. El punto5
permanece pendiente de recuperar los tiempos/contadores de ese intento y registrar
el resultado correlacionado. No relanzar para medir otra vez a ciegas: primero
reconectar y recuperar el resumen existente. La desconexión histórica B2 sigue sin
causa demostrada.
