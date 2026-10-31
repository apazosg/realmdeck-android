---
id: usb-portable-recovery-goal-2026-10-31
state: approved
date: 2026-10-31
---

# Goal: preparación y recuperación portátil en 15–30 minutos

## Summary

El usuario quiere un goal de iteraciones con APKs instaladas para ajustar la
**preparación y recuperación de una instalación parcial iniciada en otro
dispositivo** hasta que el proceso medido tarde **entre 15 y 30 minutos**. No se
acepta degradar la autenticación, el consentimiento, los límites de espacio ni la
protección de los datos existentes. El hito local `b42dc54` implementa la revisión
y adopción por cabeceras con bitset durable, pero **no se ha construido, instalado
ni medido en el dispositivo**.

Contexto de la fixture real: C168 en Xaga mostró una revisión de 822.658 objetos
reutilizables; la adopción antigua avanzaba a ~2,35 registros/s y llevaba ~4,46 %
tras horas. El nuevo flujo evita autenticar cuerpos en revisión y adopción, por lo
que la ventana de 15–30 min debe redefinirse y medirse, no heredarse.

## Objetivo propuesto

En Xaga (Xiaomi 22041216G), con el SSD USB conectado y una instalación parcial
cuyo origen es otro dispositivo, el flujo **desde que el usuario confirma la
recuperación hasta que la recuperación queda durable y el cliente puede continuar**
debe completarse en **15–30 minutos de reloj hasta el primer byte de descarga**, con verificación independiente de
los objetos reutilizados y sin superar los máximos informados.

La instalación puede quedar incompleta: la descarga masiva posterior **no** entra en la ventana; esta termina con el primer byte de payload (P4).

## Fases medidas

| Fase | Inicio | Fin |
|---|---|---|
| P0 | El usuario inicia la revisión de datos localizados | — |
| P1 | — | Revisión de cabeceras completa y resumen confirmable |
| P2 | Tras la confirmación del usuario | Adopción durable (prefijo, checkpoint, bitset, intención) |
| P3 | — | Plan actualizado + handoff listo para descargar |
| P4 | — | Primer byte de payload |
| P5 | — | Descarga del resto completada |
| P6 | — | Verificación independiente y cliente listo |

Ventana objetivo aprobada: **P0–P4** (incluye el primer byte de descarga). Se registran P0–P6 siempre para no perder
información; P4–P6 se reportan aparte.

## Fixture y preservación

- Fuente reproducible: el parcial verificado de la fixture cross-phone (segundo
  móvil o árbol de artefactos), no una copia inventada. Documentar catálogo,
  segmentos, bytes, objetos y marcador.
- Antes de instalar: inventariar el parcial real (nombres, tamaños, hashes del
  marcador, receipt y checkpoint; sin copiar segmentos si no hay espacio), y
  conservar una copia del checkpoint/bitset si cabe. No borrar ni reparar el USB.
- La instalación `adb install -r` conserva datos de la app, pero mata el proceso y
  la revisión en memoria. Aceptar que la primera iteración reinicie la revisión;
  el parcial del USB permanece.
- Validación final sobre el parcial real C168; las iteraciones usan la fixture
  controlada para no arriesgar la única copia.

## Protocolo de iteración

1. **Preflight (≤5 min)**: dispositivo alcanzable (`~/.local/opt/platform-tools/adb`,
   endpoint privado), batería ≥80 %, térmica estable, espacio libre en app y USB,
   APK/commit/hash registrados, logcat limpio.
2. Instalar la APK con `adb install -r`; force-stop previo y posterior; comprobar
   versión y que el workspace/parcial sigue presente.
3. Ejecutar el flujo con cronómetro y `logcat -v monotonic` (tag de rendimiento),
   capturando P0–P6 y contadores (objetos, bytes, candidatas, reintentos, errores).
4. Analizar el desglose por fase; elegir **una** palanca por iteración.
5. Reconstruir/auditar la APK, instalarla y repetir.
6. Parar al lograr 3 ejecuciones consecutivas en ventana, o tras 8 iteraciones sin
   mejora, o ante cualquier regresión de seguridad/pérdida de datos.

Cada ejecución se registra en una tabla: fecha, APK, commit, fixture, P0–P6,
throughput, térmica/batería, anomalías y veredicto.

## Palancas permitidas (sin debilitar autenticación/consentimiento)

- Revisión: lectura adelantada por segmento, workers acotados, lotes de membership
  ya existentes, cadencia de revalidación ligera, evitar `stat`/hash repetidos.
- Adopción: reutilizar la prueba de prefijo de la revisión si el lease no se
  interrumpió (requiere revisión de seguridad), evitar el tercer pase de catálogo,
  reutilizar el plan adoptado.
- Plan/handoff: no recalcular agregados ya medidos; progreso por fase en la UI.
- Descarga (fuera de la ventana, sin tocar aún): concurrencia, lotes, grupo de
  commit y fsync; solo si P4–P6 se incluyen.

## Instrumentación

Añadir un log acotado `FWCascPerf` (solo fases, contadores y milisegundos; sin
rutas, claves ni datos del cliente) en revisión, adopción, plan y handoff, más los
timestamps de UI. No cambia el comportamiento ni el consentimiento. Se elimina o
se deja inerte al cerrar el goal.

## No objetivos

- Publicar la APK o cambiar la especificación aprobada.
- Descargar el cliente completo si la ventana termina en P3.
- Reemplazar la verificación por cabeceras por una confianza implícita.

## Riesgos

- Pérdida del progreso real C168 al reinstalar o reiniciar; mitigar con inventario
  y copia del estado pequeño, nunca tocando los segmentos.
- Térmica/batería y variabilidad Wi-Fi; preflight y repeticiones.
- La APK debe reconstruirse desde fuentes con pins; los pins actuales no incluyen
  `b42dc54`, así que el primer paso es actualizarlos o usar el árbol aislado ya
  compilado, registrando el recibo.
- Si el flujo honesto tarda <15 min con verificación completa, añadir retardo
  artificial sería incorrecto; se propone considerarlo objetivo superado.

## Open Issues

1. **Alcance de la ventana**: decidido P0–P4 (incluye el primer byte). P5–P6 se
   reportan aparte.
2. **Fixture de iteración**: parcial controlado del segundo móvil (recomendado) o
   duplicar C168 si el USB tiene espacio; validación final en C168.
3. **Límite inferior**: si se mide <15 min con verificación íntegra, ¿se acepta como
   superado o se exige permanecer en la banda?
4. **Build**: actualizar pins del reconstructor (auditable, más lento) o construir
   desde el árbol aislado con las fuentes actuales y registrar recibo (más rápido).

## Corte 2026-10-31 (mediciones y cambio de adopción)

- Iteración 1 (v169, escaneo serial): P0 11:29:52; preparación/versión 15,8 min;
  revisión 11:48:45–12:08:57 (~20,2 min); P1 39,1 min. El flujo de orden de archivo
  costó 20,1 min (`elapsedMs=1208602`) y la revalidación ligera 108 s/50.944 llamadas.
  Resumen: 822.658 candidatos, 322.320 pendientes, 85,0 GiB de descarga prevista,
  39,4 GiB adicionales. No se alcanzó adopción ni P4.
- Iteración 2 (v170, escaneo paralelo, catálogo cacheado): P0 12:13:19; catálogo
  reutilizado en ~2,5 min; revisión ~674 objetos/s frente a ~650/s seriales: **sin
  ganancia real**. Proyección P1 ~20 min y P4 ~47 min. Detenida.
- El USB entrega ~42 MB/s secuenciales (117 MB en 2,78 s) y ~700 `pread` de 30 B/s;
  leer los 48 GB seguidos también costaría ~19 min. El escaneo está limitado por
  latencia de peticiones FUSE, no por ancho de banda; paralelizar segmentos no lo
  mejora.
- Coste duplicado confirmado: `confirmAndAdoptPartialPortability` reenumera todas las
  cabeceras (`expectedPrefix`) tras `revalidateSource(inventory = true)`, que ya
  comprueba marcador, tamaño/mtime/fileKey de segmentos y admisión del catálogo.
- Cambio aplicado (v171): la adopción reutiliza `reviewed.prefix` y
  `reviewed.candidateOrdinals` y reconstruye el plan desde el catálogo admitido, sin
  la segunda enumeración. No autentica cuerpos en ningún caso. Pruebas focalizadas
  `ForeverClientUpdaterTest.portability*`: BUILD SUCCESSFUL (44 s).
  Pendiente: revisión independiente y reconciliación de US-009 (FR/TC) antes de
  declarar el comportamiento aprobado.
- Cota absoluta: 37,4 GiB a la velocidad medida (~1–2 MB/s) ≈5,3 h; el flujo debe
  quedar muy por debajo. El cruce con una red de 14 MB/s es el límite a vigilar.

## Corte 2026-10-31 (iteración 3, plan delta y ejecución)

- v172 (`0.2.148-...-iter4-deltaplan`) confirmó el plan delta: «Espacio adicional:
  39.1 GiB», «Descarga: hasta 85.0 GiB», «Volumen del cliente: hasta 37.4 GiB».
  Al pulsar Descargar reutilizó el checkpoint solo-prefijo y arrancó el escritor a
  las 14:57:38.
- El escritor **no se bloqueó**: verificaba candidatos en serie e insertaba sus
  registros en `DiskIndexTable`. A las 15:11:28 llevaba 629.145 de 822.658
  candidatos (rehash de 629.145→capacidad 2.097.152 en 13,6 s). El proceso se
  mató a las 15:14:22, antes de terminar; `queueDepth=30`, `httpActive=0` y
  `sourceOutstanding=0` son coherentes con una cola llena de candidatos sin
  fetch, no con un deadlock. La verificación restante se estima en ~3–5 min más.
- La verificación de cuerpos de candidatos es el coste dominante del escritor
  (~18 min para 822.658). Es una frontera de seguridad: el bitset solo acredita
  cabeceras, no cuerpos; el escritor debe probar cada cuerpo antes de reutilizarlo.
  Paralelizarla o persistir pruebas por objeto exige una decisión de diseño aparte.
- Triaje de los 3 tests que fallaban en la suite reconstruida: (1) el fichero de
  plan cacheado es opcional en el workspace preparado, no obligatorio; (2) el
  recuento de prefijo debe excluir el frame FINISH; (3) el test de cadencia debía
  medir la revisión (que sigue usando group checks) y no la adopción de una
  pasada. Suite `ForeverCascTest`+`ForeverClientUpdaterTest`: BUILD SUCCESSFUL.
- Pendiente: repetir la ejecución sin matarla, capturar P4 y decidir si la
  ventana 15–30 min es alcanzable con verificación íntegra o debe redefinirse.

## Corte 2026-10-31 (iteración 4, v173 y verificación paralela pendiente)

- v173 (`0.2.149-usb-goal-1530-iter5-planmigrate`) instala sobre el checkpoint
  adoptado de v172. El plan se mostró a las 16:08:54 (~5,2 min desde el inicio)
  con «Espacio adicional: 39.0 GiB»; el handoff se persistió en el workspace.
- Pulsado «Descargar» a las 16:11:20; `CASC writer begin` a las 16:13:59
  (2 min 39 s). `checkpoint loaded objects=0` a las 16:33:17: el checkpoint
  adoptado solo tiene cabeceras, no frames de objetos.
- El escritor vuelve a verificar cuerpos en serie: rehash de 19.660 a 629.145
  registros entre 16:14:28 y 16:27:53. Entre 16:38:40 y 16:38:55 leyó 15,3 MB
  con ~5.250 `read()` (~350/s, ~3 KB por lectura), ~1 MB/s; CPU 20–30 %. No hubo
  `first source request` ni P4 antes de pausar a las 16:41:20.
- Causa: `overlapArmed` exige `resumableCheckpointHasObjects` (size >
  cabecera). El prefijo solo-cabecera da `false`, así que se usa el camino
  serial `findCanonicalCandidate`/`verifyExistingIncrementalObject` para los
  822.658 candidatos. El bitset acredita cabeceras, no cuerpos; la prueba de
  cuerpo sigue siendo obligatoria. A ~700 preads de 30 B/s, solo las cabeceras
  cuestan ~19 min; los cuerpos agravan el coste. La ventana P0–P4 de 15–30 min
  no es alcanzable con esta verificación serial.
- Arreglos de la iteración: 3 tests verdes (workspace de transición sin el
  handoff opcional, frames OBJECT sin contar FINISH, cadencia de la revisión
  dirigida), y el fallback de `buildPlan` ahora persiste
  `.forever-casc-candidate-plan` para migrar checkpoints antiguos. Suite
  `ForeverCascTest`+`ForeverClientUpdaterTest` y test de migración: OK.
- Decisión pendiente (frontera de seguridad): paralelizar la verificación de
  cuerpos con un pool acotado de `ForeignCandidateVerifier` (un reader por
  worker) y un mapa de pruebas ya verificadas, o persistir pruebas por objeto
  en el checkpoint. Recomendado: pool acotado + mapa de pruebas, conservando
  exactamente la misma verificación EKey/BLTE y el mismo binding de prefijo.
  Sin esa decisión, P4 queda fuera de la ventana.
- v174 (`0.2.150-...-iter6-readahead`) prueba primero la vía de menor riesgo:
  `ForeverCascReplayReaderAndroid.readAt` mantiene un bloque de lectura
  anticipada de 1 MiB por segmento y sirve desde él las lecturas contenidas;
  solo rellena en avance secuencial. La verificación conserva el mismo `pread`
  bajo el mismo FD y binding, pero las cabeceras de 30 B y los cuerpos
  contiguos dejan de costar una syscall cada uno. Suite completa y test de
  migración: OK. Pendiente: instalar, reanudar y medir P0–P4.
- v174 medido: reutilizó el handoff de plan (plan listo en 2m43s), writer begin
  a 2m37s y `checkpoint loaded objects=0` a los 18m37s. La verificación serial
  posterior no llegó a `first source` en 30 min; el read-ahead no atacó el
  cuello real.
- v175 (`0.2.151-...-iter7-hintio`) añadió read-ahead de 1 MiB a las cabeceras
  de 30 B y buffer al spool de hints: **más lento** (39k→78k en 93 s frente a
  39 s), porque el bloque recorre los cuerpos y multiplica los bytes leídos.
  Revertido en v177.
- v176 (`0.2.152-...-iter8-memhints`) sustituyó el store de hints en disco por
  arrays en memoria (~63 MB para 1,14 M hints). Dos tests fallaron porque el
  store de disco antepone hints nuevos y el de memoria los añadía al final; se
  corrigió a prepend y la suite `ForeverCascTest` (92 tests) quedó verde.
- v177 (`0.2.153-...-iter9-memhints-direct`) volvió a leer cabeceras de 30 B
  directamente y mantuvo el store en memoria. `Descargar` 17:05:19 → writer
  begin 18:07:57 → `checkpoint loaded objects=0` 18:13:34: la fase de hints
  bajó a 5m37s (antes 18m37s), sin `rehash begin`, con PSS total ~184 MB y sin
  OOM. A las 18:47:51 (42,5 min) seguía **sin `first source`**: CPU ~1 %,
  rchar ~0,5 MB/s, syscr ~248/s. El cuello es la verificación serial de los
  ~822k candidatos del prefijo (stat/binding + BLTE por objeto), no la lectura
  de cabeceras. Se pausó el flujo a las 18:49.
- Límite observado: con la verificación serial actual, P4 no entra en 30 min
  ni con la fase de hints optimizada. La paralelización recomendada no es un
  cambio acotado: el writer recibe `objects` como `Sequence` (no indexable),
  y cada cuerpo requiere el `decodedSize`/`contentKey` del spec de destino para
  repetir el veredicto EKey/BLTE/content-key; un preverificado solo por cabecera
  debilitaría la prueba por objeto. Opciones para la próxima iteración: (a)
  materializar specs y preverificar en un pool con mapa de pruebas, o (b)
  cachear el chequeo de segmento/binding por lote acotado en el verificador
  serial. Ambas requieren revisión de seguridad antes de ejecutarse.
