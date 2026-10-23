---
id: launcher-client-updater-2026-10-16
state: in-progress
date: 2026-10-16
---

## Summary

El usuario pide implementar la actualización del cliente desde el APK («hazlo»),
tras confirmar que code79 no ofrece esa función. Concreta DATA1/2 y el bloque4 del
[plan de producto](arm64-launcher-app-polish-plan.md). Debe ser una operación real,
no un botón que instale el bootstrap o prometa una build inexistente.

Aprobado por el usuario con «venga adelante», incluida la comprobación manual de
novedades explicada después del plan. Comienza la implementación y validación de
gates; no autoriza descargar decenas de GB ni modificar el cliente funcional sin
confirmar destino y coste. El APK79 instalado se conserva hasta una entrega admitida.

Ampliación confirmada: el usuario indica que Classic Forever usa ahora **70425** y
pide continuar hasta tener descargador/actualizador listos.70425 pasa a ser destino
objetivo;70009 es el origen conocido. La versión del servidor queda confirmada por
el usuario, no mediante una prueba propia de Login. Restan evidencia ARM64,
fingerprint/manifiestos y prueba end-to-end. No se necesita nueva aprobación para
cada corrección técnica cubierta; se preserva consentimiento para descargas grandes
y mutación de la instalación real, y bloqueo ante formato/identidad no demostrados.

Trabajo host70425: la admisión mínima confirma tags ARM64 con36 archivos. Dos
intentos de ampliar metadatos fallaron tras transferir encoding/download/size y
descartaron sus temporales; consumo contabilizado442.488.165 bytes, no payloads.
Corregido antes de reintentar: caché externa persistente de raw verificados por
EKey/tamaño y ledger duradero; tres regresiones específicas aprobadas. Se autoriza
un reintento host acotado de221.174.450 bytes adicionales para completar metadatos,
sin reiniciar contadores ni usar otra carpeta para eludirlos. Techo de ingeniería
acumulado revisado a1GiB para metadatos (incluidos índices CDN si fueran necesarios),
no autorización de descarga del juego, PE ni escritura sobre el cliente del móvil.
Los fallos y consumo previo se mantienen en el informe.

## Content

### Mantenimiento repetible entre versiones

**Sustituido por el requisito posterior:** el usuario quiere el flujo normal desde
la APK, sin generar otro APK por versión. La propuesta host/empaquetada de este
apartado se conserva como historia y herramienta de diagnóstico, no como solución
final. Nuevo diseño pendiente de aprobación:
[actualización desde el dispositivo](launcher-on-device-updates-plan.md).

El usuario recalca que no debe ser una actualización única a70425. Se concreta un
comando host por versión que verifica metadatos oficiales, genera SQLite/paquete y
publica una admisión declarativa en `forever/client-target.json`; no sustituye el
contrato Forever x86 ni exige editar constantes Kotlin por cada build.

Primera ruta de distribución segura: el catálogo y su admisión viajan auditados en
el APK del launcher. Preparar otra admisión puede requerir reconstruir/actualizar
el launcher; detectar novedades desde el APK no lo requiere. No se añade ahora un
feed remoto de catálogos arbitrarios ni importaciones sin confianza establecida.
Esta frontera debe aparecer explícita en el procedimiento; no prometer actualización
universal de builds no admitidas sin nueva entrega. El script no ejecuta descargas
del juego ni cambia el cliente instalado. La aceptación nativa queda pendiente por
defecto y no se eleva por completar metadatos.

Contrato de admisión versionado: fingerprints de build/CDN, hashes/tamaños del
catálogo/comprimido/configs, tags/producto, PE esperado por CKey/tamaño/machine,
evidencia de versión servidor y estado de validación nativa. La receta verifica
los bytes y registra origen; Android consume el mismo contrato desde un asset
genérico, no una lista de números incrustada en sus clases.

### Evidencia y límites de partida

| Área | Hecho observado | Implicación |
|---|---|---|
| Cliente funcional | APK79 usa cliente existente ARM64 1.60.1.70009, runtime/driver preservados | No sustituir la instalación para ensayar el actualizador. |
| Catálogo | `scripts/arm64-prototype/client/catalog.py` fija build config `05215079e3905ef5922ae0b03ffefb73`, CDN config `9b3c456dbb837d133a026d380c7c13e9`, Windows/arm64/enUS | Solo70009 catalogada. Un número de versión x86_64 admitido no aprueba ARM64. |
| Bootstrap |35 loose,832 objetos runtime; `completeness=finite-bootstrap` | No contiene el conjunto completo de datos. Su verificación no acredita instalación completa. |
| Transporte | `ForeverClientProvisioner.downloadRecord/streamHttp` verifica rangos, tamaños y hashes; `ForeverBlte` es streaming N/Z | Reutilizable; reevaluar límites/encodings contra el manifiesto completo antes de aprobarlo. |
| CASC | `ForeverCasc.materialize()` crea Data.new completo; verifyExisting lee generaciones de índices | Falta escritor incremental y aceptación por el cliente. No reutilizar materialize como actualización normal. |
| Integración | PoC usa almacenamiento elegido; `ForeverLaunchController.prepare()` antiguo usa root fijo | El updater debe integrarse en la PoC funcional y recibir su destino validado, no copiar la ruta experimental. |
| Integridad instalada | PE fijado y Data presente, sin inventario completo ni fingerprint de build de todos los objetos | Verificación/adopción explícita de instalación existente antes de mutación; no confiar solo en `.exe`/marker. |
| Exclusión | ProcessGuard vive en proceso; lock/journal duradero ausente | Muerte del launcher y guest huérfano/desconocido deben bloquear cambios, no permitirlos. |
| Espacio/memoria | Manifiestos grandes y OOM registrados en instalador Python completo | No meter millones de entradas en JSON/listas/mapas o colas de futures en RAM del móvil. |

Fuentes: `docs/arm64-client-bootstrap.md`, `docs/fase-1-instalacion-cliente.md`,
`ForeverClientCatalog.kt`, `ForeverClientProvisioner.kt`, `ForeverCasc.kt`,
`ForeverBlte.kt`, `ForeverProvisioningPort.kt`, `ForeverFunctionalClientStorage.kt`,
`ForeverFunctionalPoc.kt`; `src/forever_client/casc.py` y `forever/contract.json`.
No se inspeccionaron secretos, otras infraestructuras ni el cliente en vivo.

### Alcance propuesto

**Un apartado «Cliente y actualizaciones»** con versión/destino/estado reales y
acciones explícitas para comprobar, descargar si no está instalado, verificar,
reparar y actualizar cuando exista un destino admitido. Se implementan sobre un
único motor de planificación/transferencia/activación, sin engines paralelos.

- Abrir el apartado o comprobar versiones no modifica la instalación ni llama a
  `ensure()`, que puede reparar/escribir. La comprobación sí puede consultar la
  CDN oficial y conservar metadatos pequeños en estado privado del launcher.
- Antes de descargar: mostrar build, destino, bytes pendientes, pico de espacio
  adicional y consentimiento. No lanzar descargas masivas al preparar/arrancar.
- Estados comprensibles: comprobando, verificando instalación, descarga pausada,
  descargando, verificando cambios, aplicando, recuperando y error recuperable.
- Completitud separada: presente/no verificado, bootstrap, instalación verificada
  contra manifiesto. «Verificada» no significa Login/gameplay demostrado.
- Ausencia de otra build aprobada: «No hay otra versión ARM64 verificada»; fallo
  de red: «No se pudo comprobar». No afirmar «Al día» sin comparación verificable.
- No inventar un historial de builds: NGDP versions devuelve la activa del
  producto, no garantiza arquitectura ni catálogo histórico completo.

### Decisiones de implementación propuestas

1. **Catálogo y manifiesto completo.** Fingerprint por producto, arquitectura,
   idioma, build/CDN config y hashes de manifiestos; disponibilidad y compatibilidad
   Forever diferenciadas.70009 se conserva como origen experimental conocido;
   70425 es el destino solicitado y confirmado por el usuario para el servidor.
   No llamarlo autenticación propia validada. Nuevos destinos
   requieren admisión explícita, sin aceptar cualquier PE ARM64 ni cambiar el
   contrato x86_64. Obtener selección completa desde download/size/encoding y tags
   documentados. Procesamiento incremental/paginado con índice de trabajo en disco;
   no expandir millones de objetos en el asset JSON acotado actual. Si no puede
   resolverse la selección completa, bloquear la función completa, no llamarla lista.
2. **Destino y adopción.** API de operación sobre root app-owned existente/vacío,
   separada de resolveForPrepare (que exige instalación). Congelar y revalidar UUID,
   mount/root/base/archivos críticos; no-follow/rutas del catálogo seguras. Verificar
   inventario y procedencia existentes sin escribir; ante archivos ajenos o metadata
   ambigua, detenerse. Guardar fingerprint/inventario solo después de aceptación.
   Verificador PE basado en pins de la build admitida, no relajar el pin actual.
3. **Planner delta.** Reusar objetos solo por EKey completo, tamaño codificado e
   identidad decodificada verificados. Los índices con prefijo corto requieren
   resolver colisiones, no usarlos como prueba de identidad completa. Reusar archivos
   loose iguales; descargar faltantes/corruptos. Calcular red, staging, índices,
   backup loose/metadata, mayor objeto en tránsito y margen antes de escribir.
4. **Escritura CASC incremental.** Conservar segmentos viejos, escribir segmentos
   nuevos con solo el delta y publicar índices coherentes antiguos+nuevos. Probar
   numeración, offsets, capacidad/generaciones y límites del escritor V7. Evitar
   copias de grandes segmentos y hardlinks sobre datos mutables. Una descarga en
   vuelo, buffers acotados, estado/cola en disco y temporales reutilizables limitados;
   reusar Range y verificación existentes. Formato/BLTE no admitido bloquea con
   explicación, sin bypass o aceptación parcial.
5. **Transacción y recuperación.** Journal app-owned duradero ligado al destino;
   escribir/forzar límites antes de sustituir metadata/loose, conservar respaldo
   proporcional al delta y sincronizar directorios cuando el FS lo permita. Recuperar
   hacia un estado coherente al reiniciar, incluso si el volumen está desconectado.
   Cancelar/pausar en puntos seguros, nunca abandonar media activación. Un rename
   individual ni fallback de move equivalen a transacción. Si el FS no ofrece las
   garantías mínimas, bloquear, no degradar silenciosamente.
6. **Exclusión duradera.** Coordinación común del updater y launch: lease en proceso,
   registro/journal y comprobación de identidad/salida de procesos propios. Lanzamiento
   bloqueado durante activación/recuperación; mutación bloqueada con guest vivo o
   terminación desconocida tras muerte del host. No liberar por desaparición de UI,
   Boolean `running=false`, tiempo transcurrido o PID reutilizado. Sin matar procesos
   ajenos, root ni nuevos permisos. Identidad no demostrable: error seguro y revisión
   explícita, no botón para ignorar el bloqueo.
7. **Retención mínima.** Primera entrega no compacta ni borra segmentos grandes
   antiguos automáticamente. Reportar espacio retenido; eliminar únicamente propios
   temporales/respaldo de transacción tras commit seguro. GC/compactación general,
   actualizaciones en background y feed multi-build quedan fuera. Si se acumula espacio
   insuficiente, bloquear con coste real, no reinstalar todo silenciosamente.

### Gates y criterios de aceptación

- Comprobación de versiones sin escrituras en cliente, sin auto-descarga y con
  mensajes distintos para error/no candidato aprobado/actualización disponible.
- Un manifiesto completo real procesado con memoria/cola acotadas, sin descarga de
  todo el cliente como requisito de validar el parser. Medir RAM/espacio, no asumir
  que los límites actuales del bootstrap bastan.
- Dos builds sintéticas con90% de objetos compartidos: transferencia solo del10%
  nuevo y corruptos; segmentos grandes anteriores no copiados; no escritura en
  cliente fuente ajeno. También reparación de la misma build, no solo salto de versión.
- Verificador independiente del escritor lee todos los objetos/índices resultantes;
  ensayo de aceptación con cliente real en fixture/snapshot aislado antes de habilitar
  mutación en la instalación funcional. No se duplica el cliente vivo para este gate.
- Fallos de espacio, Range, desconexión, hash/BLTE, symlink/archivo ajeno, cambio de
  volumen y límites de índices dejan intacta o recuperable la instalación anterior.
- Inyectar interrupción en cada frontera de activación, recuperar sin permitir
  launch con mezcla de builds; proceso guest vivo/salida desconocida bloquean cambios
  incluso después de reiniciar el host. Probar dos operaciones concurrentes.
- Preservar originales `Config.wtf` y `WTF/BetaSuspendedTest.wtf`, perfiles derivados,
  credenciales y runtime; las mutaciones solo afectan archivos admitidos del catálogo.
- Review independiente de journal/exclusión/rutas antes de entregar. Build aislado,
  auditoría de APK/firma/runtime y recorrido físico de UI sin Login ni credenciales.
- No prometer ahorro fijo o actualizaciones disponibles: depende del contenido
  compartido y de builds realmente admitidas. Sin USB físico, explicitar ese gate.

## Implementation / Action Items

- [ ] 1. Tras aprobación, concretar specs y contrato de catálogo/inventario/operación,
  incluyendo mensajes de completitud y frontera de admisión de builds.
  - [x] Aprobación registrada y contratos de prototipo: fingerprint/publicación,
    evidencia ARM64 separada, cierre de manifiesto en disco e incremental aislado.
  - [ ] Specs finales e integración del contrato de operación/journal pendientes
    de resolver el gate de capacidad; no se da por implementado un updater completo.
- [ ] 2. Validar manifiesto completo y resolver/planner acotado, pruebas de selección
  ARM64/tags/encodings, disponibilidad metadata oficial y coste medido.
  - [x] Manifiesto completo70009 host por streaming/SQLite y comprobador manual
    Kotlin de metadatos oficiales, ambos probados; no integrados en APK.
  - [ ] Planner completo Android/transporte del conjunto pendientes; cierre host
    no acredita fuentes disponibles ni contenidos de todos los objetos.
- [ ] 3. Probar escritor CASC incremental y reparación con verificador independiente;
  resolver gate de aceptación por cliente real aislado. Si falla, parar y revisar
  diseño con el usuario; no sustituirlo por Data.new completo.
  - [x] Prototipo sintético de reutilización/reparación, EKeys chunked, colisiones,
    límites y no-follow; reader independiente verifica índices/objetos exportados.
  - [x] Corrección de escala aprobada: índices variables y trabajo en disco probados
    con1.319.619 registros sintéticos y lector independiente, sin copiar segmentos.
  - [ ] Bloqueado: aceptación nativa y variante observada de índices64KiB con cola
    no nula, todavía no admitida. No activar updater sobre el cliente existente.
- [ ] 4. Implementar journal/recuperación y exclusión duradera compartida con launch,
  resolución de destino elegido/adopción explícita e interrupciones dirigidas.
- [ ] 5. Integrar transferencia/cancelación/reanudación/verificación y activación;
  tests de delta/espacio/error. Mantener un solo motor y reutilizar piezas existentes.
- [ ] 6. Añadir apartado Cliente y actualizaciones con acciones realmente respaldadas,
  progreso/costes y consentimiento. No habilitar writes antes de superar gates.
- [ ] 7. Review de integridad, build/auditoría e instalación conservando datos;
  prueba física acotada y bitácora de lo demostrado y lo bloqueado.

## Open Issues

### Gate de capacidad descubierto durante ejecución (primer prototipo)

El manifiesto completo70009 selecciona1.319.619 EKeys distintos. El escritor V7
acotado del bootstrap/prototipo admite10.920 entradas por bucket,16 buckets:
no cabe el conjunto completo. **Bloqueado el trabajo downstream de activación,
journal/UI de actualización habilitada hasta resolver escalabilidad y aceptación
del cliente**, conforme al paso3. Se validan los prototipos y se presenta el cambio
de diseño necesario, no se fuerza Data.new ni se prueba sobre el cliente funcional.

La selección completa se procesa en host por streaming/SQLite; reporte corregido
con EKey BLTE y límites de descriptores en `client-updater-manifest-gate/real-run-5/`
fuera del repo:1.319.619 objetos,107.213.933.722 bytes codificados (metadatos, no
transferencia pendiente del móvil),98.796KiB RSS máximo,1m47s. No se adquirieron
payloads del conjunto ni se acreditó jugabilidad. Encoding conserva61 bytes finales
sin semántica interpretada, reportados con hash.

La comprobación pública oficial limitada de versions devolvió1.60.1.70425;
el usuario confirma que el servidor usa esa versión. ARM64 y ejecución propia aún
no confirmadas: no se ofrece instalarla sin evidencia. Lectura del
manifiesto completo actual y detección de versión son gates distintos.

EKey no equivale siempre a MD5 del objeto completo: con BLTE chunked cubre solo
header+tabla. Corregido en ambos prototipos; objeto real181 bytes
`bc0fe26fca9629c46c33c478077ecd4d` prueba header36 bytes con MD5 igual al EKey,
frente a MD5 total `3e222c9afb604e4ede21cbcecb861796`. Chunk hashes y CKey/size
decodificados se verifican independientemente.

Procedencia: `overlay/ForeverCasc.kt` y su test son entradas históricas fijadas.
La primera reconstrucción rechazó modificarlas; originales restaurados exactamente
y postimágenes de prueba aisladas en `app/updater-gate/`, sin relajar recibos/pins.
Wrapper externo registra preimagen y postimagen exactas para pruebas, no release.

### Cierre de pruebas del prototipo (2026-10-16)

- Host:10 tests de manifiesto aprobados; revisión independiente cerró hallazgos de
  binding EKey, límites de descriptors BLTE y contabilidad de bitmaps. Se conservan
  los resultados reales y las limitaciones del reporte de metadatos.
- JVM inicial:18 casos,12 aprobados y6 fallos. Cinco fixtures del checker pasaban
  una fila PSV sola, sin cabecera/newline; el caso de colisión esperaba que no
  existiera el directorio de staging aunque el contrato admite salida parcial
  no activable. Corregidos únicamente los tests, sin debilitar validadores.
- Ejecución dirigida reparada: checker8/8, colisión1/1 y exportación1/1 aprobados,
  SUCCESSFUL1m58s. Los demás casos CASC ya aprobados no se repitieron. No afirmar
  una sola ejecución18/18 ni APK construido/instalado por este gate.
- Lector independiente Cascette + checksums explícitos:16 índices nueva generación,
  43 registros;11 objetos normales decodificados/verificados, incluidos10 destino.
  Nueve objetos reutilizados, uno nuevo escrito; origen intacto y sin copia de
  segmento antiguo. Solo fixture pequeño, no prueba del cliente nativo.
- Evidencia externa `client-updater-gate-final/` (fallos preservados),
  `client-updater-gate-repaired/`, `client-updater-independent-fixture/`; wrapper
  y lector bajo `/home/adrian/wowforever-artifacts/arm64-prototype/`.
- `app/updater-gate/README.md` registra contrato y límites. El checker nuevo no está
  conectado a UI, la receta normal mantiene79 y el APK del teléfono no cambió.

**Corrección aprobada («so, hazlo»):** reemplazar el límite artificial del escritor
por representación de índices compatible con el conjunto completo, con planificación
en disco y memoria acotada. Validar primero formato/aceptación aislados, antes de
journal, UI y cualquier operación física. No es una limitación demostrada de CASC
en general, sino del escritor actual. Se inicia este gate de escalabilidad; siguen
bloqueados journal/UI de mutación y uso de la instalación funcional hasta aceptación.

### Resultado de la corrección de índices escalables

- Implementado en postimágenes `app/updater-gate/ForeverCasc.kt` y test: índices
  V7 variables alineados4KiB, tabla de registros en disco y ordenación externa.
  No se modifican fuentes históricas/pins de `overlay/` ni la receta normal79.
- Límites:2M registros unidos/1M por bucket/16M históricos/256 índices. Lotes8192
  por bucket (16 como máximo) y merge32 canales. Tabla160MiB lógicos; resize hasta
  240MiB de tablas simultáneas más runs/índices/temporal de un objeto. No confundir
  límites de heap con RSS o garantizar coste físico en cualquier FS Android.
- Primera revisión: compilación Kotlin2.1.21/JVM17 y13 tests aprobados. Review
  encontró el verificador legacy que aún filtraba índices grandes: reproducción
  antes del fix, parser compartido/lookup acotado y tres casos afectados aprobados.
  La API de verificación List sigue proporcional a petición, no es full-manifest.
- Exportación inicial grande detectó prefijo sintético cero reservado al leer con
  Cascette. Se corrigió el fixture (counter1), se añadió rechazo seguro y se
  ejecutaron cinco regresiones afectadas más el exportador, no la suite completa.
  Sin alterar el verificador independiente para esconder la diferencia de cuenta.
- Fixture final v2:1.319.619 registros flags1 sintéticos, sin payloads normales y
  con secuencia destino vacía.16 índices por generación de1.486.848 bytes cada uno;
  salida23.789.568 bytes, sin segmentos copiados. No prueba full payload update.
  Exportador25,82s/heap128MiB/RSS282.588KiB. Lector independiente validó todas las
  entradas/checksums/orden/cabeceras en36,41s/RSS113.596KiB, exit0.
- Review de integridad final sin P1/P2 pendientes en alcance probado. Evidencia
  externa `client-updater-large-index-fixture-v2/independent-verification.json`,
  runners `RunCascJUnitMethod.java`/`RunCascLargeMetadataJUnitMethod.java` y
  `verify-large-updater-indexes.py` bajo artefactos/arm64-prototype. El fixture
  inicial con clave reservada se conserva separado, no se da por válido.
- Inspección readonly del cliente móvil:16 índices64KiB; muestra bucket0 V7/9/5/4/30
  con9.030 bytes no nulos tras sección ordenada. Parser independiente dio warnings
  de guard al interpretar esa cola: no afirmar corrupción ni contar como válidas
  sus supuestas entradas. Esa variante es aún unsupported/failclosed. Run-as no
  pudo leer; ADB normal sí, sin modificar permisos, archivos o estado del móvil.
  Comprobación posterior del sample en host: guards de cabecera/bloque coinciden,
  686 registros, fin0x3064/gap cero hasta0x4000 y cola no nula. Una pareja de
  prefijos iguales adyacentes, ninguna descendente. Esas diferencias de formato
  no demuestran corrupción; el prototipo estricto todavía no las admite.
  `native-current-index-layout.json` y muestra SHA-256
  `ef6b9a2675aaa99582315e4c13b1de3dd6c1e05b7a3f28ddcbb70d2a8a11bbe6`.

**Estado del gate:** capacidad sintética resuelta; compatibilidad nativa y formato
del índice original siguen pendientes. El siguiente trabajo requiere investigar
esa cola con evidencia de solo lectura y validar una copia aislada; no normalizarla
como ceros ni ignorarla para poder actualizar. Sin APK nuevo ni mutación del cliente.

1. **Aprobado:** este documento concreta cambios de datos, contratos y
   arranque; no es autorización para descargar decenas de GB o sobrescribir cliente.
   Cada operación física mostrará destino/coste y requerirá consentimiento explícito.
2. **Destino70425 confirmado por el usuario para Classic Forever:** metadata oficial
   publica esa versión; únicamente70009 tenía catálogo ARM64 local acreditado al
   iniciar. Falta acreditar ARM64/fingerprint/manifiestos70425 antes de permitir el
   salto. Su publicación y uso por el servidor no son evidencia propia de Login.
3. **Aceptación CASC:** no demostrada para append/merge; primer gate antes de invertir
   en UI de actualización activable. Datos de prueba grandes/snapshots fuera del repo;
   no tar en vivo, no tocar wowserver/Btrfs u otras infraestructuras.
4. **Manifiesto completo:** número/tamaño/selección ARM6470009 medidos en host;
   los payloads completos y sus encodings no se adquirieron/verificaron. No se
   adopta una medida x86 histórica como sustituto de esta evidencia.
5. **Procesos huérfanos/durabilidad de FS:** si Android no permite demostrar salida o
   el volumen no soporta garantías mínimas, mantener bloqueo e informar el problema.
6. **Riesgo residual:** ninguna comprobación por paths crea un snapshot atómico contra
   mutación externa hostil; no prometer inmunidad. No autologin, gameplay, fullscreen
   o compatibilidad de un nuevo driver como parte del updater.
