---
id: patch-search-priority-2026-10-22
state: in-progress
date: 2026-10-22
---

## Summary

Tras el Login correcto C135, el usuario pide opciones para acelerar búsqueda y
parcheado y aprueba analizar primero los registros existentes para escoger una
sola optimización. Este documento recoge ese análisis y un experimento
de prioridad de lectura, aprobado por el usuario el2026-10-22: «vale».
No se ha accedido al dispositivo ni leído memoria para este análisis.

## Content

### Evidencia existente, no un nuevo rastreo de claves

Referencia: [estado del launcher](../../docs/launcher-app-status.md),
[arranque observado](patch-wow-readiness-plan.md) y
[contrato de parcheo](../specs/US-002.md).

Comparación de los snapshots privados ya guardados:

| Métrica | C134 | C135 |
|---|---:|---:|
| Resultados de scan |41|81|
| Sondeos con cero bytes efectivos leídos |37|73|
| Operaciones fallidas en esos sondeos |306887|604758|
| Errores PermissionDenied contabilizados |0|0|
| Resultados con mapsChanged |39|78|
| Resultados con passComplete |1|2|
| Resultados que comunican candidato validado |1|1|
| Primer resultado → APPLIED, ms |68705|134394|

C135 añade duraciones monotónicas:63653ms sumados dentro de nativePoll y80000ms
nominales de pausas entre81 resultados; el intervalo medido búsqueda→APPLIED es
143802ms. De los63653ms de sondeos,33657ms corresponden a sondeos con cero bytes
efectivos. Los contadores de mapsChanged son resultados con esa marca acumulada
del pase, **no78 cambios distintos**. El tiempo del poll incluye comprobaciones
del proceso, mapas y lectura, no solo búsqueda de bytes.

Resumen saneado externo:
`/home/adrian/wowforever-artifacts/arm64-prototype/client135-wow-startup-20261022/search-optimization-comparison.json`.
No direcciones, claves, rutas de mapas, credenciales ni bytes de memoria en Git.

### Lo que el código explica y lo que no

- `functional_full_scan.rs:124-131` incluye todas las regiones legibles, incluso
  dispositivos y archivos. Recorre el snapshot en orden de direcciones.
- `scan_loop` (:342-355,:447-474) intenta chunks de hasta256KiB; un fallo de IO
  avanza4KiB, registra hueco y continúa. Un presupuesto agotado conserva el cursor.
  Por tanto, los errores repetidos pueden corresponder a posiciones diferentes;
  **no se ha demostrado que se reintente la misma dirección**.
- `ReadOnlyMemory::read` en `functional_session.rs:414-455` carga al presupuesto
  la longitud solicitada antes de la llamada y contabiliza aparte bytes efectivos.
  Esto explica cómo muchos errores pueden agotar presupuestos sin leer bytes.
  No se propone relajar el presupuesto ni omitir comprobaciones de identidad.
- El apply solo permite memoria privada, escribible y anónima, con su predicado
  existente `is_writable_anon_private` y revalidación completa. Un apply positivo
  demuestra esas propiedades en el momento de escritura, no el tamaño, dirección,
  nombre o posición histórica de la región durante sondeos anteriores.
- Los logs no permiten determinar si el bloque existía antes del hallazgo. Tampoco
  permiten reconstruir una región ganadora: `stores` es un contador, no un mapa.
  No se promete recuperar los80s de pausas; quitarlas también adelanta lecturas
  fallidas o lecturas anteriores a la creación del bloque.

### Una candidata propuesta: regiones potencialmente aplicables primero

Priorizar, dentro del mismo proceso e intento, regiones que satisfacen el
predicado existente de memoria privada/anónima/RW o RWX; después continuar por
**todas** las regiones legibles restantes. Orden estable dentro de cada grupo.
Es una hipótesis basada en el requisito necesario del apply, no un patrón de
ubicación deducido de estos dos logs. No usar direcciones persistidas, tamaños
aprendidos, caches entre procesos, regiones omitidas ni nuevos modos/opciones.

No basta con ordenar un vector: el cursor/rebase actual supone orden por dirección
(`rebase_onto_fresh`, `adopt_fresh_after_change`). Antes de implementar hay que
definir la progresión por fases y reanudación ante cambios de mapas, preservando
solapamiento, progreso y fallback, sin reinicios que impidan terminar el pase.
Si exige una arquitectura desproporcionada o no puede preservar esos contratos,
detenerse y presentar la alternativa, no añadir un segundo escáner independiente.

Conservar cadencia1000ms, presupuestos, límites900000/180000ms, disparador C135,
validación480B, ancla/padding/contrato, identidad/parentesco, cancelación, cierre,
protecciones, exclusión de candidatos ambiguos observados y comprobación posterior.
El orden no concede permiso de escritura ni implica unicidad global. Captura y
CLI quedan fuera de alcance. No cambiar a la vez pausa o política de errores.

## Implementation / Action Items

- [x] 1. Analizar snapshots existentes y código, sin interacción con el móvil.
- [x] 2. Aprobar la candidata y resolver en US-002 UC/AC el orden preferente,
  fallback completo y progreso ante cambios; derivar FR/TC. No resolver dentro de
  este trabajo la tensión previa entre FR-002 y TC-002 sobre cobertura incompleta.
- [x] 3. Especificar el cambio mínimo de cursor/rebase; implementar únicamente el
  orden aprobado con pruebas sintéticas focales de prioridad, fallback, reanudación,
  solapamiento y cancelación. Mantener las pruebas existentes de guardas de apply.
- [ ] 4. Revisión independiente de límites de lectura/escritura, identidad y
  concurrencia. La revisión pendiente de C135 no queda satisfecha por este análisis.
- [x] 5. Recompilar el JNI modificado con su procedencia nueva verificable y una
  candidata APK auténtica; nunca reutilizar la etiqueta/hash C127 para un nativo
  nuevo ni debilitar el auditor para aceptar payloads sin procedencia.
- [x] 6. Tras pruebas y auditoría, coordinar instalación sin pérdida de datos y
  un Login manual. Comparar observación→búsqueda→hallazgo→apply, número de sondeos
  y bytes/errores. Conservar C135 recuperable. No prometer aceleración antes de medir.

### Resultado local — C136 instalada, resultados manuales mixtos

- Código `93ecae3`, versión136 / `0.2.112-priority-scan`. Solo cambia el orden del
  escáner funcional no-captura y su rebase. Usa directamente el predicado del
  escritor, sin duplicarlo; snapshot ordenado por (grupo,dirección) tanto al
  inicio como en la comprobación final. Parser compartido, loop de chunks,
  escrituras, intervalos, presupuestos, UI y captura sin modificaciones.
- Cursor conserva el grupo anterior incluso si la región desaparece/cambia de
  categoría; no vuelve del fallback al preferente ni revive un pase agotado.
  Se conservan la invalidación de candidatos y el estado incompleto por cambios.
- 105 pruebas focales `functional_session::` pasan, incluidas tres regresiones
  nuevas de orden/fallback, pausa al entrar en fallback y reclasificación/progreso.
  Las pruebas existentes cubren solapamiento/cancelación y guardas de apply.
- Ejecución inicial de toda la biblioteca:121 correctas y1 fallo del autotest
  histórico multiproceso, que esperaba un almacén y observó124. Ese autotest usa
  el escáner histórico, no el orden modificado; ejecutado aisladamente pasa sin
  cambios. Compatible con contaminación de memoria sintética heredada durante
  tests paralelos, no demostrado como causa definitiva. No declarar suite global
  verde ni ocultar el fallo. Logs privados externos, sin direcciones en este plan.
- 24 pruebas host de reconstrucción pasan. No se repiten las JVM de UI sin cambios;
  las48 seleccionadas pasaron en C135. Nueva build APK correcta en5min54s.
- Nativo nuevo compilado mediante `build_functional_capture.sh`, procedencia
  independiente before/after, NDK24/API29 y ELF16KiB correctos. No es el nativo C127:
  SHA-256 `dc6ccae1f35ea4421ad4b9adf54c2d67f55011547273906a218d24926cc1f19c`,
  1012776bytes; sidecar SHA-256
  `eb97436e6961b298baedadd4a82299198cd8e33b335cebc9cdde0eba2ceedd32`.
  Par SO/sidecar fijado explícitamente; ninguna relajación del auditor.
- Reconstrucción aislada de los inputs exactos auditados C135 más cambios propios
  C136; manifiesto completo de fuentes nativas cotejado con el snapshot. Mantiene
  fuera cambios ajenos/autologin. Evidencia externa en
  `/home/adrian/wowforever-artifacts/arm64-prototype/client136-priority-scan-20261022/`;
  SO/sidecar en `functional-capture-build.TzfR42/` del mismo directorio padre.
- Auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2217 fuentes sin cambios y178
  payloads. APK484564715bytes, SHA-256
  `e296f3e3214e3120a92f116ecf2062fce6c5dfb04463d1005110e5a7bc1701f5`.
- Revisor alternativo GPT-6.1 Sol tampoco arrancó por límite de uso; no se ha
  obtenido revisión independiente. C136 tiene un ensayo fallido y un resultado
  positivo comunicado después por el usuario. C135 sigue siendo una referencia
  positiva recuperable. No se afirma reducción de tiempo ni estabilidad.

## Open Issues

- El usuario autoriza instalar C136 pese a revisión pendiente: «instálala y la
  pruebo». Preflight versión135/UID10330, sin guest vivo ni servicios; audio
  residual identificado. `install -r` termina con código1 sin detalle y ADB pasa
  a offline; una reconexión devuelve Connection refused. **Versión instalada final
  desconocida**, no hay receipt de instalación verificada ni ensayo. Intento
  externo `deployment-attempt.json`; no borrar datos, desinstalar ni reintentar
  instalación hasta recuperar conexión y comprobar versión/hash/estado del guest.
- Conexión recuperada y comprobación posterior: seguía C135. Nuevo preflight sin
  guest vivo y `install -r` correcto; versión136/UID10330/hash del `base.apk`
  verificados contra el APK auditado. Receipt externo `deployment-receipt.json`.
  No se borraron datos ni se abrió el juego. C136 instalada con revisión pendiente
  por autorización del usuario; queda realizar su Login manual y medir el resultado.
- Ensayo posterior: el usuario comunica que no encontró el parche, terminó con
  «parche no aplicado» y Login fallido con mensaje aproximado «you have been
  disconnected». No se ha verificado aún el JSONL de ese intento: ADB no presenta
  dispositivos, y el único endpoint mDNS anunciado rechaza la conexión. No afirmar
  NOT_FOUND nativo, causa del error de servidor ni causalidad del nuevo orden sin
  ese registro. No hay evidencia de aceleración y no se encadenan modificaciones.
  Propuesta de recuperación: C135 auditada y previamente positiva, solo tras
  autorización, cierre normal del guest y preflight; no se ha reinstalado todavía.
- Posteriormente el usuario comunica: «acabo de conseguir hacer funcionar la c136.
  creo que encuentra la sección unos segundos después de hacer el primer login sin
  el parche y que falle». Esto añade un resultado positivo y hace improcedente
  clasificar C136 como regresión universal. No demuestra causalidad: creación o
  accesibilidad del bloque tras Login y coincidencia con avance del escáner siguen
  sin distinguirse. Falta JSONL del intento positivo/hitos manuales. No automatizar
  un fallo de Login ni convertirlo en requisito; no se modifica el escáner ahora.
- Investigación de registros posterior con conexión recuperada y versión136
  comprobada, sin iniciar juego/Login ni leer memoria: intento fallido
  `1792703844638` termina180012ms tras búsqueda,101 INCOMPLETE+1 CANCELLED, sin
  candidato validado comunicado ni apply; intento positivo `1792704498274` aplica
  32bytes a84215ms tras búsqueda y mantiene90 ALREADY_PATCHED. El hallazgo está
  en el primer sondeo de una pasada nueva, como C134/C135. C135 tardó143802ms:
  diferencia observada59587ms, no causalidad probada ni promesa de aceleración.
  Sin timestamps de Login no se distingue creación/accesibilidad inducida por
  Login de aparición tardía/revisita del escáner. No se introduce un Login fallido
  obligatorio ni se automatiza. Resúmenes y JSONL privados externos bajo
  `client136-priority-scan-20261022/login-investigation/`.
- Ensayo manual posterior guiado por mensajes: Jugar→buscando→primer Login
  fallido→parche aplicado/verificado→segundo Login correcto, confirmado por el
  usuario («si y acabo de poder entrar»). Se propuso esperar30s antes del primer
  Login, sin medición precisa ni automatizarlo. Repite el orden comunicado, no
  demuestra causalidad. No hay control sin Login; falta copiar su propio JSONL
  porque ADB volvió a estar desconectado. No reutilizar los timings/contadores
  de otro intento como si pertenecieran a este.
- ADB recuperado posteriormente: JSONL del ensayo guiado `1792705391624`, C136.
  Búsqueda→APPLIED32=83529ms; candidato en sondeo42, primero de una pasada nueva
  tras finalizar la anterior en sondeo41. Positivo previo C136=84215ms. Patrón
  consistente de revisita, no causalidad del Login: su instante sigue sin medirse.
  Snapshot44 INCOMPLETE,1 APPLIED,86 ALREADY_PATCHED y1 DENIED tardío por mapas
  (0bytes) seguido de2 scans antes del plazo; no prueba desparcheado/desconexión.
  JSONL y resúmenes privados `guided-*` externos bajo login-investigation. No se
  modifica código ni se fuerza otra prueba; el usuario ya confirmó entrada.
- Aprobado el orden preferente con fallback completo. Contrato de cursor:
  progresión monotónica por (grupo de prioridad, dirección), conservando ese grupo
  ante un cambio de mapas; invalidar candidatos y marcar pase incompleto como antes.
  Regiones reclasificadas o insertadas detrás del cursor se visitan en el siguiente
  pase; no reiniciar la fase preferente en cada sondeo ni impedir llegar al fallback.
- No se conoce cuándo se crea el bloque: el beneficio real puede ser pequeño o
  inexistente. Un Login exitoso no prueba por sí solo una búsqueda más rápida.
- El trabajador de investigación no pudo arrancar por variante no disponible;
  este análisis es inspección local, no revisión independiente. El límite de uso
  bloqueó también el anterior revisor alternativo. No ocultar esa limitación.
- Trabajo concurrente de autologin y diagnósticos ajenos se preserva sin cambios.
