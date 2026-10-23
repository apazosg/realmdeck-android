---
id: memory-patch-hardening-2026-10-22
state: done
date: 2026-10-22
---

## Summary

El usuario solicita buscar debilidades y posibles mejoras de robustez del parcheo
en memoria. La fase inicial fue una auditoría de solo lectura y una propuesta de
trabajo; la autorización posterior del lote A se registra a continuación.

Base revisada: `71ccb1c`; launcher C132 compilado/auditado, no instalado, con
el nativo C127. El fallo de conexión comunicado con C131 sigue sin causa probada:
una debilidad del diseño no demuestra por sí sola que haya causado ese intento.

### Aprobación y lote completado

El 2026-10-22 el usuario responde a la propuesta de empezar por el lote A:
«vale, hazlo. vamos a hacerlo más robusto y después al terminar probaremos un
nuevo login». Se ejecutan **A1 y A2**: cancelación de la sesión de parche al
fallar el arranque y estado veraz visible del intento actual. Los resultados
terminales distinguirán aplicación verificada previa de ausencia de aplicación;
un fallo de cierre no se presentará como cierre correcto. Ningún resultado de
otro intento podrá actualizar el actual.

Se mantienen defaults, presupuestos temporales, opt-in y separación captura/
escritura, identidad, bloques, permisos y native C127. El CLI (lote B), cambios
de ventana y redescubrimiento A3/A4 no están aprobados en este lote. Tras pruebas,
revisión y build/auditoría se coordinará un nuevo Login manual; no se instalará
sobre una partida activa ni se automatizarán credenciales.

Impacto aprobado: `US-004-AC-12` describe estado observable y limpieza de fallo;
`US-004-FR-018/019` derivan de ese criterio y `TC-024/025/026` comprueban estados,
cierre e aislamiento de intentos. `US-003-FR-005` ya exige no publicar resultados
tardíos de una ejecución fallida; US-002 y su política de escritura no cambian.
Los resultados tardíos del mismo intento podrán corregir historial/error terminal,
sin reactivar vigilancia; el cierre nativo exactamente una vez no significa congelar
un resumen inexacto. DENIED vuelve a mostrar búsqueda, no verificación vigente.

## Content

### Alcance y criterios

| Capa | Qué se comprueba |
|---|---|
| Núcleo Rust/CLI histórico | Lecturas acotadas, límites de regiones, validación, revalidación y resultado de escritura, mantenimiento y cancelación. |
| Escritor JNI funcional | Identidad del hijo y del mapeo, exclusión/token, cobertura incompleta, candidato retenido, escritura parcial, readback y cierre de la autorización. |
| Integración Android | Habilitación real, inicio antes del login, mantenimiento y vencimiento, errores visibles, pruebas y correspondencia fuente/payload entregado. |

- Separar defectos reproducidos, debilidades demostrables por inspección,
  decisiones deliberadas y supuestos todavía no observados en el dispositivo.
- No atribuir al Android funcional un defecto exclusivo del escritor histórico.
- Conservar validación estricta de las 12 entradas, ancla intacta, escritura de
  solo los 32 bytes del grupo 8 y prohibición de reintentos/rollback ciegos tras
  una escritura parcial o postcondición fallida.
- No proponer root, desactivar TLS, modificar ejecutables en disco, cambiar
  permisos de memoria ni relajar claves/IDs/flags para conseguir una coincidencia.
- En la auditoría inicial no ejecutar ADB, arrancar/cerrar el juego ni leer o
  modificar su memoria. Las comprobaciones usan tests existentes y dos reproducciones mínimas sobre
  memoria simulada fuera del repositorio, sin un framework nuevo.
- La prueba C127 de aplicación, mantenimiento y Login/mundo es evidencia acotada
  a ese ensayo; no prueba protección durante toda una sesión o tras reconectar.

### Fortalezas comprobadas

Las referencias Rust se encuentran bajo `crates/forever-patcher/src/` o
`crates/forever-patcher-jni/src/`; las Kotlin bajo
`scripts/arm64-prototype/app/overlay/`. `Session.kt` abrevia
`ForeverFunctionalPocPatchSession.kt` y `Activity.kt` abrevia
`ForeverFunctionalPocActivity.kt`. `XServerScreen.kt` pertenece a la fuente
canónica reconstruida C132, no al overlay versionado.

El escritor funcional Android no es el escritor histórico `patch_stores`.
`functional_session.rs:2106–2238,2339–2478` revalida identidad, token, mapeo y
los 480 bytes; escribe solo 32 bytes, comprueba el bloque completo después y
conserva el contador real del syscall ante escritura parcial. Un error posterior
inhibe nuevas aplicaciones; no restaura memoria a ciegas. Poll/captura no escriben.
La revisión acotada no encontró una vía concreta que eluda esas guardas; esto no
equivale a una garantía de ausencia de defectos ni a atomicidad frente al cliente.

### Hallazgos Android, ordenados por impacto práctico

| ID | Evidencia y alcance | Mejora mínima propuesta |
|---|---|---|
| A1 — autorización huérfana tras fallo de Wine | `ForeverFunctionalPocActivity.kt:1358–1391` no llama a `cancelPatch()` en `onGameLaunchError`. El `catch` de setup de `XServerScreen.kt:2424–2432` canónico solo emite ese callback: no pasa necesariamente por `onExit`. El intento puede dejar la autorización y el sondeo vivos hasta el watchdog; el siguiente Begin se rechaza hasta su cierre, hasta 15 minutos en modo parche. Inspección de ambas rutas, no reproducción física. | Cancelar en ese callback, como ya hacen `onExit` y `navigateBack`; cubrir error de setup y cierre exactamente una vez con la infraestructura existente. No hay dos autorizaciones nativas simultáneas: el mutex impide la segunda. |
| A2 — estado real invisible | `ForeverFunctionalPocPatchSession.kt:405–463` registra resultados solo en JSONL; la UI (`Activity.kt:1870–1871,2054–2096`) no distingue desactivado, búsqueda, aplicación confirmada, fallo o mantenimiento terminado. `nativeEnd` no deshace la clave: terminar la vigilancia no significa que se haya eliminado el parche. | Mostrar un estado breve del intento actual y un resultado terminal que distinga nunca aplicado de aplicado pero ya no vigilado. `VALIDATED` de un scan no debe presentarse como `APPLIED`. Sin credenciales/direcciones ni callbacks antiguos que pisen otro intento. |
| A3 — vigilancia temporal, no de toda la partida | `Session.kt:23–24,150–158,211` termina al menor de 15 minutos desde Begin y 3 minutos desde el primer Enter. Una recreación posterior queda fuera de mantenimiento. Es una política aprobada, no una regresión descubierta. | Primero hacer visible el límite (A2). Cualquier extensión o cambio de ciclo de vida requiere aprobación y evidencia de coste/cierre; no sustituirlo por un hilo permanente. |
| A4 — candidato retenido, no redescubrimiento global | `Session.kt:199–233` y `functional_session.rs:2437–2440` mantienen la misma ubicación hasta DENIED. Si sigue válida una copia vieja y aparece otra, no se busca esta última. El punto ciego está demostrado por código; que el cliente real conserve ambas copias es una hipótesis pendiente. | Medir el patrón de recreación antes de cambiarlo. Si se justifica, aprobar redescubrimiento acotado y política segura para varios candidatos, no elegir una copia por heurística ni barrer continuamente. |

**Habilitación real:** con ambas opciones desactivadas (defaults persistidos),
`ForeverFunctionalPoc.kt:540–542` no abre autorización ni observador. Solo el opt-in
de memoria inicia al escritor; captura es mutuamente excluyente y no escribe.
Esto no demuestra que el ajuste se desactivase en el intento C131: no se leyó la
preferencia del móvil. No se propone activarlo silenciosamente por defecto.

### Núcleo/CLI histórico: riesgos separados del Android funcional

- **C1 — seguridad de escritura:** `patch.rs:163–166` usa los hallazgos del
  escaneo sin revalidación inmediatamente anterior ni readback posterior. Entre
  ambas operaciones puede cambiar la asignación; tras escribir puede cambiar el
  contenido sin que se detecte. Añadir revalidación del bloque, mapeo elegible y
  postcondición completa, manteniendo la escritura solo del grupo 8.
- **C2 — continuidad de proceso:** `memory.rs:125–168` guarda únicamente PID
  numérico y reabre `/proc/<pid>/mem` para cada operación. No fija la identidad
  frente a reciclado del PID. Proponer identidad estable y descriptor ligado al
  proceso; no resolver errores con reintentos ciegos de ese número de PID.
- **C3 — contención de región:** el bloque calculado en `patch.rs:85–88` se
  valida leyendo 480 bytes sin comprobar que todos estén en la región candidata.
  La legibilidad de una región vecina no prueba que sea escribible/anónima.
  **Reproducido:** una memoria simulada que expone una región candidata y permite
  lecturas en su vecina produce un hallazgo y una escritura fuera de la región
  candidata. Exigir contención completa antes de admitir el bloque y comprobar
  nuevamente la elegibilidad al escribir. JNI ya comprueba esa contención.
- **C4 — contrato personalizado malformado (prioridad baja):**
  `contract.rs:196–227` valida longitudes y un mínimo de tamaño, pero permite
  `entry_size` enorme antes del producto no comprobado de `block_size()`
  (`:131–133`). Rechazar formatos/tamaños incompatibles al cargar. No es un
  fallo demostrado del contrato canónico fijado en la APK; JNI exige ese contrato
  exacto y su formato 12×40 (`functional_session.rs:463–482`).

`write_all` del CLI no informa éxito con un prefijo incompleto: puede devolver
error después de escribir un prefijo, sin contar sus bytes. No inventar ese
contador a partir de un readback que también puede cambiar concurrentemente.
Si se añade contabilidad, medir los retornos reales de escritura. La política
histórica de parchear varias copias es explícita; cambiarla necesita aprobación,
no debe copiarse inadvertidamente la política de un candidato observado de JNI.

### Mejoras secundarias y propuestas descartadas

- `VALIDATED` en un recorrido diagnóstico puede referirse a un almacén en memoria
  no aplicable: la autoridad final es `nativeApply`, que lo deniega. A2 debe
  respetar esa distinción; no ampliar permisos para convertirlo en aplicable.
- Un fallo al restaurar el bit subreaper deja la autorización cancelada retenida
  (`functional_session.rs:2569–2586`): es fallo cerrado deliberado. No liberarla
  incondicionalmente ni borrar estado para permitir otro Begin. Una recuperación
  controlada del mismo token sería un trabajo separado si se observa ese fallo.
- No introducir ahora backoff, heartbeats, coordinación de autologin o nuevos
  controladores genéricos: no corrigen el fallo de cierre demostrado.
- La auditoría APK comprueba hashes fijados y procedencia declarada coherente
  con las fuentes, no una recompilación binariamente idéntica. No se encontró
  discordancia C132/C127; reproducibilidad nativa sería otro alcance.

### Verificación efectuada

- Núcleo: una ejecución de `cargo test --manifest-path
  crates/forever-patcher/Cargo.toml --locked --offline`, con
  `CARGO_TARGET_DIR=/tmp/opencode/wowforever-patcher-audit-core`: salida correcta,
  cero fallos. Incluye 10 pruebas de parcheo, 5 de solape y una integración con
  un hijo sintético; no es una prueba del juego.
- JNI: prueba existente
  `functional_session::tests::diagnostic_thirty_one_byte_overlap_does_not_double_count`
  con `cargo test --locked --offline --manifest-path
  crates/forever-patcher-jni/Cargo.toml --lib <test>` y target externo
  `/tmp/opencode/wowforever-patcher-audit-jni`: 1 correcta.
- Reproducciones externas revisadas en `/tmp/opencode/region_escape_probe.rs`,
  compiladas con `rustc --extern forever_patcher=<rlib ya construido>` y ejecutadas
  una vez, sin proceso real ni acceso a `/proc`:
  - C3: `stores=1 outcomes=1 write_outside_region=true`.
  - C1: tras devolver el bloque válido al validador, el doble cambia un flag;
    `outcomes=1 block_still_valid=false stale_write=true`: aun así se escribe.
  Ambas reproducciones completan sus aserciones. No demuestran que esos patrones
  hayan ocurrido en el juego ni que sean defectos del escritor funcional JNI.
- Revisiones de Rust, JNI e integración Android, contrastadas por el responsable.
  No se repitieron suites correctas ni se ejecutó otro build/APK o prueba física.
- Ninguno de estos resultados establece la causa del incidente C131.

### Contratos que requieren atención

- `US-002-FR-002` distingue un candidato observado de unicidad global y reconoce
  que un recorrido incompleto no demuestra esa unicidad. Sin embargo,
  `US-002-TC-002` dice que un escaneo incompleto no puede autorizar escritura.
  La interpretación debe reconciliarse antes de cambiar la política de aplicación;
  no corregir código ni relajar validadores para ocultar esta ambigüedad.
- El [plan de adelanto](../early-group8-apply-plan.md) aprobado conserva un máximo
  total de 15 minutos y un corte a los 3 minutos del primer Enter. No bloquea ni
  automatiza el Login. Cambiar esos límites o añadir coordinación del Login
  requiere una decisión explícita, no una modificación incidental de esta auditoría.
- Las fuentes normativas son [US-002](../../specs/US-002.md),
  [US-003](../../specs/US-003.md) y [US-004](../../specs/US-004.md). La referencia
  histórica sobre relleno no sustituye la regla aprobada: byte 37 variable,
  bytes 38–39 cero.

## Implementation / Action Items

- [x] 1. Consolidar auditoría, alcance real, fortalezas, fallos y verificaciones.
- [x] 2. Obtener aprobación del lote mínimo de mejoras y de las decisiones que
  alteren comportamiento; reconciliar UC/AC y derivados afectados antes de código.
- [x] 3. Lote A: A1 (cancelación en error de lanzamiento) y A2 (estado
  veraz y terminal visible), sin cambiar defaults, tiempos ni permisos. Pruebas
  focales de cierre, aplicación/no aplicación, expiración y aislamiento por intento.
- **Lote B diferido, fuera de la aprobación actual:** C1/C2/C3 del CLI histórico,
  conservando su política de copias; usar dobles existentes para cambio de bloque,
  postcondición, identidad y frontera de regiones. C4 requiere también decisión
  sobre endurecimiento del formato de contrato personalizado.
- [x] 5. Revisión independiente de seguridad/concurrencia. Si cambia la entrega,
  reconstruir y auditar el nativo/APK sin reutilizar procedencia desactualizada.
  Contrastar respuestas tardías frente al watchdog/cancelación, aislamiento por
  intento y que los observadores no bloqueen el cierre ni alteren autorización.
- [x] 6. Registrar resultados y límites. Cualquier instalación o ensayo físico se
  coordina por separado; no dar por resuelto el incidente de Login sin evidencia.

### Resultado local del lote A

- C133 / `0.2.109-patch-status`: cancelación en error Wine, estado tipado por
  intento, historial terminal y aislamiento de callbacks. Sin cambios de autoridad,
  defaults, presupuestos, captura ni nativo C127.
- Revisión de concurrencia: SEARCHING antes de hilos; begin fallido/abandono sin
  estado ficticio; DENIED vuelve a búsqueda; respuestas tardías refinan historial
  o FAILED, incluso si el cierre nativo ya falló. Cierre nativo antes del lock de
  observación, exactamente una vez. Callback de producción solo encola en main.
- 57 JVM correctas (46 UX/sesión y 11 perfiles); 24 host de reconstrucción y 8 del
  auditor correctas. 27 correctas/6 fallos previos en los fixtures/UX focales.
  Una prueba JVM nueva consultaba antes de publicarse el terminal: se corrigió
  la espera y se reconstruyó desde cero; no se cambió comportamiento para hacerla pasar.
- Build final correcto en 4min33s y auditoría
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT` (2215 fuentes, 178 payloads). El auditor
  admite solo los cuatro descriptores exactos nuevos revisados, no un prefijo.
  Evidencia final: `/home/adrian/wowforever-artifacts/arm64-prototype/client133-patch-status-20261022-final/`.
  Código y pruebas: `af8569c`.
- C133 instalada por ADB Wi-Fi tras comprobar quiescencia, mediante `install -r`,
  sin borrar datos ni cambiar UID10330. Versión y hash del APK instalado coinciden
  con la auditoría; `deployment-receipt.json` externo conserva la comprobación.
- Ensayo manual: el usuario autoriza el escritor, observa «buscando» sin llegar a
  «verificado» y después intenta Login, que comunica como fallido. Snapshot del
  mismo intento: 238 scans (2 NOT_FOUND, 6 REJECTED de identidad inicial y 230
  INCOMPLETE/read_coverage), cero anclas/candidatos observados y **ningún
  `patch_apply`**. No se ha ejecutado la aplicación; no es prueba de ausencia de
  claves ni de cambio del contrato. `manual-login-summary.json` externo conserva
  solo estados/contadores; los JSONL originales permanecen privados fuera de Git.
- La búsqueda antes de Wine es intencionada. Pedir esperar «verificado» antes de
  intentar Login fue una indicación incorrecta: el plan de adelanto no demuestra
  que las claves existan antes de autenticar. Se corrigió y se hizo el intento
  manual sin bloquear ni automatizar el Login. No se cambió configuración,
  permisos, cliente ni memoria mediante las herramientas de diagnóstico.

## Open Issues

### Protocolo del ensayo coordinado

1. Confirmar destino ADB actual y cierre normal del juego antes de instalar con
   `install -r`; conservar datos, UID, cuenta y configuración. Comprobar versión
   instalada y hash contra la auditoría del nuevo APK.
2. El usuario abre la APK y comprueba el estado de compatibilidad del intento.
   Si el opt-in está desactivado, registrarlo: no habilitarlo silenciosamente ni
   interpretar el arranque sin parche como una aplicación fallida.
3. Con el escritor autorizado explícitamente, observar el estado y los eventos
   `patch_session`/`patch_scan`/`patch_apply` del mismo intento. Captura o `VALIDATED`
   por sí solos no prueban aplicación; `APPLIED`/`ALREADY_PATCHED` de apply sí
   acreditan esa comprobación, no el éxito de autenticación.
4. El usuario introduce sus credenciales dentro del juego y realiza el Login.
   Registrar resultado y orden observado respecto de apply; conservar únicamente
   evidencia saneada, sin credenciales, claves, direcciones ni contenido de memoria.
   No se automatiza ni se bloquea el Login desde el launcher.

- El lote A está aprobado. El lote B y A3/A4 permanecen diferidos.
- El lote A termina con implementación, auditoría, despliegue y resultado físico
  registrados; **no arregla ni da por resuelto el Login**. El intento C133 ya
  demuestra falta de aplicación efectiva, no su causa exacta ni la del C131.
- El siguiente trabajo debe aislar diferencias respecto del éxito C127 ya
  demostrado, no repetir el rastreo desde cero. No relajar el bloque ni ampliar
  escritura para forzar un éxito; un cambio de política necesita aprobación.
- En el preflight previo al build, el usuario facilitó el nuevo puerto Wi-Fi y
  se recuperó ADB. Inspección de solo lectura: 22041216G, C131 instalada entonces,
  UID10330 conservado, app y logcat sin procesos Wine visibles. La instalación
  posterior de C133 y el ensayo manual fallido se registran arriba.
- A3/A4 precisan decisión y evidencia antes de alterar la política aprobada de
  mantenimiento. Un nuevo Login no se considerará resuelto sin observar su resultado.
