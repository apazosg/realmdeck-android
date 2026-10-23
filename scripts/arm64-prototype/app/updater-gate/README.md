# Prototipo aislado de actualización CASC

Estas fuentes son **postimágenes de prueba**, no un updater integrado ni fuentes
admitidas por la receta de release. El APK instalado sigue siendo code79.

`overlay/ForeverCasc.kt` y `ForeverCascTest.kt` pertenecen al baseline histórico
con hashes fijados; no sustituirlos ni actualizar sus pins para compilar este
prototipo. El arnés externo aplica estas postimágenes después de validar las
preimágenes exactas y produce un recibo test-only independiente.

## Contrato probado

`ForeverCasc.writeIncremental(sourceDataFiles, outputDataFiles, objects,
sourceFor, cancelled)` recibe objetos como `Sequence<IncrementalObjectSpec>`:
EKey completo, CKey, tamaño codificado sin cabecera local y tamaño decodificado.
El origen es de solo lectura y el destino de staging debe ser nuevo y disjunto.

- Reutilización tras comprobar cabecera/ubicación, EKey, BLTE y CKey/tamaños;
  faltantes o corruptos requieren fuente válida y se escriben en segmentos nuevos.
- EKey BLTE chunked = MD5 de header+tabla, no del objeto entero. Sin tabla, MD5
  del objeto entero. Checksums de chunks y contenido decodificado son independientes.
- Colisiones de prefijos de9 bytes, límites de formato, fuentes/rutas no admitidas
  y estructuras corruptas rechazan la operación. Puede quedar staging parcial:
  **una excepción nunca permite activarlo**.
- Los índices de nueva generación conservan referencias a segmentos antiguos.
  No hay activación, recuperación/journal, descarga real ni GC en este módulo.

## Escalado del índice (corrección aprobada)

El primer prototipo fijaba índices de `0x30000` bytes:10.920 entradas por bucket,
insuficientes para el manifiesto completo. La postimagen actual escribe índices V7
de longitud variable alineada a4KiB (mínimo de salida legacy `0x30000`), con checksums
y tamaños de sección reales. Además reserva al menos48KiB vacíos **después** del fin
alineado de las entradas ordenadas; la estimación de espacio usa el mismo tamaño.
Los índices grandes sin esa reserva provocaron «Truncated updated section» en el
cliente70450. Añadirla conservando las entradas permitió superar ese rechazo y
preparar la caché de índices CDN.48KiB es la variante comprobada, no un mínimo
nativo demostrado; esto no acredita por sí solo login ni todos los objetos CASC.
El antiguo límite de entradas no era intrínseco de CASC.

La unión usa tabla de registros en disco y ordenación externa, no un HashMap de
todo el origen. Límites explícitos:2.000.000 registros unidos,1.000.000 por bucket,
16.000.000 registros históricos y256 ficheros de índices. Lotes de8.192 registros
por bucket (hasta16 lotes en memoria) y32 runs/canales por merge. Tabla de hasta
160MiB lógicos; un resize puede retener240MiB entre tabla vieja/nueva, más runs,
índices y temporal de un objeto. No se midió la asignación física máxima en Android.

`verifyExisting(List<RuntimeObjectSpec>)` comparte el parser variable y no retiene
todos los registros fuente: busca solo prefijos solicitados. Esa API sigue siendo
proporcional a su lista de petición y **no es un verificador del manifiesto completo**.
Se rechazan generaciones inválidas sin fallback, colisiones, prefijos reservados
de9 bytes cero y colas de actualización no admitidas. Staging nunca se activa aquí.

## Bloqueo de compatibilidad nativa

Superar el volumen de metadatos no acredita aceptación de WoW ni actualización
completa. Una inspección read-only del móvil encontró16 índices de64KiB. La muestra
del bucket0 es V7 con campos9/5/4/30, pero tiene9.030 bytes no nulos después de la
sección ordenada. Cascette produjo advertencias al intentar interpretar esa zona:
no tratar sus supuestas entradas como verificadas ni afirmar corrupción del cliente.
Esa variante sigue fuera de lo admitido por este prototipo y se rechaza, sin ignorar
la cola o adaptar a ciegas límites. Falta investigar/validar el formato, trabajar con
una copia aislada y acreditar aceptación nativa antes de integrar el updater.
La inspección adicional del sample confirma guard de cabecera y bloque ordenado,
686 registros, fin `0x3064`, gap hasta `0x4000` a cero y cola no nula desde ahí.
Hay una pareja de prefijos adyacentes iguales y ninguna descendente; el lector
estricto del prototipo tampoco admite esa variante. No inferir corrupción de esa
diferencia. SHA-256 de la muestra:
`ef6b9a2675aaa99582315e4c13b1de3dd6c1e05b7a3f28ddcbb70d2a8a11bbe6`.
Semántica de cola/duplicados sin determinar; `native-current-index-layout.json`
registra hechos separados de las suposiciones del parser externo.

## Evidencia externa, 2026-10-16

- `client-updater-gate-final/`: primera ejecución JVM18 casos,12 aprobados y6 fallos
  de fixtures (tabla PSV sin cabecera/newline; expectativa de staging inexistente).
- `client-updater-gate-repaired/`: checker8/8, regresión de colisión1/1 y exportación
  independiente1/1; build de tests SUCCESSFUL1m58s. Producción no cambió al corregir
  esos fixtures; no presentar ambas rondas como una única ejecución18/18.
- `client-updater-independent-fixture/independent-verification.json`: lector
  Cascette independiente y validación explícita de checksums leen16 índices,
  43 registros (32 reconstrucción y11 objetos), incluidos los10 objetos destino.
  Fixture9 reutilizados/1 escrito; hashes del origen intactos y segmento antiguo
  no copiado. No es aceptación nativa del cliente ni prueba sobre CASC real completo.
- `reconstruct-updater-gate.py`, `UpdaterGateExportTest.kt` y
  `verify-updater-gate-fixture.py`: arneses externos, no empaquetados en el APK.

Todas las rutas anteriores están bajo
`/home/adrian/wowforever-artifacts/arm64-prototype/`. Plan y siguiente gate:
[launcher-client-updater-plan.md](../../../../.agents/planning/launcher-client-updater-plan.md).

### Evidencia de escalado

- Compilación standalone Kotlin2.1.21/JVM17 de las fuentes con JUnit:13 casos
  aprobados sobre la primera revisión escalable, con heap máximo128MiB. No Gradle,
  APK o prueba Android por esta ejecución.
- Review detectó que el verificador legacy aún descartaba índices grandes. Se
  reprodujo el fallo y se corrigió parser/discovery/lookup. Tres regresiones
  afectadas aprobadas. También se comprobó que un temp/symlink ajeno no se borra.
- El lector independiente detectó una clave sintética reservada vacía en el primer
  fixture grande. Se corrigió el generador, no el lector; rechazo de prefijos cero
  en fuente/spec/salida y regresiones dirigidas aprobadas. Fallos históricos no
  convertidos en éxitos; no afirmar suite única13/13 sobre la postimagen final.
- Exportación final `client-updater-large-index-fixture-v2/`,1.319.619 cabeceras
  sintéticas flags1, **sin objetos payload normales**, secuencia destino vacía.
  Demuestra escaneo/unión/ordenación de metadatos a esa escala, no descarga/reutilización
  de1,3M objetos del juego.16 índices por generación,1.486.848 bytes cada uno;
  salida23.789.568 bytes y ningún archivo data copiado. Origen preservado.
- Ejecución dirigida del exportador:25,82s, heap128MiB, RSS máximo282.588KiB
  (el RSS incluye JVM/source launcher y no equivale al heap). Pruebas dirigidas
  adicionales de claves cero, índice grande/verificador, generaciones y reparación
  aprobadas; no se repitió la clase completa sin necesidad.
- `verify-large-updater-indexes.py`: lector Cascette independiente y checksums/
  orden/cabeceras explícitos verificaron las1.319.619 entradas. PASS,36,41s,
  RSS113.596KiB; `independent-verification.json` en el fixture v2. Solo metadatos.
- `native-current-index-inspection.json` y `native-current-bucket0.idx`: lectura
  acotada de64KiB por ADB normal, sin root/permisos nuevos. Run-as no tenía acceso;
  no se alteraron permisos. No se escribió ningún fichero en el teléfono.
- Review de integridad cerrada sin P1/P2 pendientes dentro del alcance probado;
  aceptación nativa y variante de cola real siguen sin validar.
