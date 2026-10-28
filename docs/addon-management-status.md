# Gestor de addons — evidencia de desarrollo

Fecha: 2026-10-28. Comportamiento autorizado: [US-010](../.agents/specs/US-010.md).
Plan activo: [addon-management-plan](../.agents/planning/addon-management-plan.md).

## Alcance y límites

Implementación local en desarrollo; **no es una entrega ni una integración externa
aceptada**. No se ha desplegado el backend, configurado una clave, aceptado términos
de CurseForge ni realizado una prueba física del gestor. El endpoint de diagnósticos
existente no acredita que las nuevas rutas estén desplegadas.

- Backend: rutas independientes `GET /api/addons/search`, `/file`, `/download`, en
  `services/diagnostics/`. El secreto `CURSEFORGE_API_KEY` solo pertenece al servidor;
  no se entrega al APK. Véase [contrato y límites](../services/diagnostics/ADDONS.md).
- Compatibilidad: se exige metadata exacta Forever y `1.60.1`; si el proveedor no
  acredita ese esquema, se rechaza en vez de ofrecer Classic/Retail. Este criterio
  aún necesita contraste autorizado con el catálogo real.
- Núcleo local: `Interface/AddOns` del cliente seleccionado. La desactivación mueve
  carpetas a `Interface/.forever-addons/disabled`, fuera del directorio de carga.
  No edita preferencias por personaje ni elimina SavedVariables. Es una convención
  implementada y probada en host, no evidencia de carga/descarga dentro del juego.
- Agrupación: paquetes instalados por el gestor conservan identidad y carpetas;
  instalaciones manuales se gestionan por carpeta con TOC identificable. Carpetas
  `Blizzard_` no se ofrecen. Identidad dudosa o estructura inconsistente bloquea
  acciones destructivas; no se deducen paquetes por nombres parecidos.
- Seguridad de instalación: descarga con tamaño/SHA-1, ZIP acotado y validado,
  staging sin sobrescritura y journal persistente para publicación multifolder.
  Tras interrupción se completa explícitamente la operación ya confirmada antes
  de permitir operaciones incompatibles. No se ejecuta el contenido del addon.
- Exclusión: propietario durable `ADDONS`, separado de `UPDATING`, bajo el bloqueo
  existente; una recuperación del updater no puede limpiar una operación de addons.
- UI: entrada Addons en Home, pestañas Instalados/Buscar, disponibilidad reversible,
  desinstalación confirmada e instalación explícita con archivo y destino visibles.
  La búsqueda no se dispara al abrir; dependencias ausentes y paquetes ya instalados
  no ofrecen instalación. El progreso representa bytes reales o fases, no porcentajes
  inventados. Los errores no conservan un inventario obsoleto como si fuera vigente.

## Verificación registrada

- Backend: `npm run typecheck` y `npm test`: 54 pruebas correctas, incluidas las de
  diagnósticos existentes. Commit `4005100`.
- JVM: núcleo local, cliente HTTP y gate compilan; siete pruebas del núcleo pasan.
  Cubren inventario offline, paquetes multifolder, desactivar/reactivar,
  desinstalación aislada, preferencias, dependencias/conflictos, ZIP peligroso y
  recuperación tras interrupciones de publicación de carpetas y de `state.json`.
- Revisión independiente limitada a extractos de transacciones/gate, cancelación UI
  y frontera de credenciales/SSRF/streaming: sin hallazgos pendientes. No fue una
  revisión completa del repositorio, de la UI visual o del despliegue.
- Reconstrucción canónica C161 (`0.2.137-addons-test`) completada, sin modificar los
  pins históricos de fuentes o payloads. Auditoría: 17 pruebas host correctas;
  admite solo las cinco raíces de fuentes addon revisadas y sus clases anidadas,
  no un prefijo indiscriminado `ForeverAddons*`.
- Pruebas host de reconstrucción/almacenamiento: 44 correctas y un fallo estático
  preexistente en `test_storage_selector_is_an_actual_identity_based_root_contract`:
  exige `storage.prepareContainer(resolved)`, ausente también en el commit base
  `4005100`, antes de los cambios Android. No se alteró ese contrato ni se cambió
  el test para ocultarlo.
- Android: `assembleModernDebug` y `testModernDebugUnitTest` focalizado terminan
  correctamente (6m43s): 7 pruebas `ForeverAddonsTest` y 10
  `ForeverClientOperationLeaseTest`, sin fallos ni omisiones. Código `c26ca47`.
  No es la suite global.
- APK: `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2231 fuentes sin cambios y 178
  payloads. SHA-256:
  `3650b7b17fbe119878a6ca9f5de760aaa57573fef4dd65f32ca1e0facd9d3f45`;
  tamaño 485171895 bytes. Recibo, XML, APK y auditoría se conservan fuera del repo en
  `/home/adrian/wowforever-artifacts/work/addon-manager-20261028-r2/`.
- No hay evidencia visual o física del gestor: la comprobación de conexiones ADB
  no encontró ningún dispositivo conectado. No se instaló este APK ni se inició
  el juego para estas pruebas.

## Antes de habilitar acceso real

1. Obtener acceso autorizado de CurseForge y confirmar que permite esta integración
   y su persistencia mínima. No enviar claves por chat ni versionarlas.
2. Verificar metadata Forever, permisos de distribución, dependencias y descarga
   autenticada real; no rebajar compatibilidad para hacer pasar una prueba.
3. Autorizar el despliegue y comprobar secretos, streaming, límites y consumo del
   proyecto Vercel. Añadir protección distribuida contra abuso antes de publicar:
   el límite por proceso no constituye una cuota global.
4. Validar en dispositivo el flujo visual, exclusión, almacenamiento seleccionado,
   desactivación reversible y carga de un addon compatible. Guardar artefactos fuera
   del repositorio; una prueba puntual no demuestra estabilidad de juego sostenida.

`npm audit --omit=dev` detectó cuatro vulnerabilidades altas en dependencias
preexistentes (`@vercel/blob`, `@vercel/cli-config`, `@vercel/oidc`, `zod`). No se
alteraron versiones ajenas a esta función ni se considera este resultado apto para
publicar sin evaluar esas dependencias.
