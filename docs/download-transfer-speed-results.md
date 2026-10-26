# Velocidad de transferencia: diagnóstico pequeño/grande

Fecha: 2026-10-26. Goal `5caa6403-5b8f-47dc-ba95-ba40fbbe686e`.
**C153 y C154 no superan la referencia; se revisa la fragmentación. Goal abierto.**

El [plan aprobado](../.agents/planning/download-transfer-speed-plan.md) se centra
en payload. No repite ni atribuye a throughput el trabajo de
[inicio C151/C152](download-startup-followup-results.md). «5mb»/«0.1» son las
observaciones del usuario, no una línea base medida ni unidades confirmadas.

## Cohortes fijadas antes del ensayo

Se clasifica el **objeto CASC por su tamaño encoded**, no el archivo visible ni
el tamaño de una petición que puede agrupar varios objetos. Los límites coinciden
con el buffer de transferencia (64 KiB) y el máximo de bytes de un batch (1 MiB):

| Categoría | Tamaño encoded | Objetos del catálogo base | Bytes encoded |
|---|---|---:|---:|
| Pequeño | ≤ 65.536 bytes | 938.287 | 15.708.652.734 |
| Intermedio | > 65.536 y ≤ 1.048.576 bytes | 191.364 | 42.761.059.842 |
| Grande | > 1.048.576 bytes | 15.327 | 32.646.622.695 |
| Total | 47–141.273.036 bytes | 1.144.978 | 91.116.335.271 |

Estos son tamaños **del catálogo completo**, no bytes descargados en una ventana.
El asset SQLite de C152 conserva metadatos base `1.60.1.70425`; la selección final
de la app y su resumen de costes son para `1.60.1.70532`. No se equiparan ambos
manifiestos: de los 369.794 objetos ya checkpointed antes del ensayo, 369.791
coinciden con el catálogo base en EKey, CKey y tamaños y tres no figuran en él.
Las cohortes transferidas se deben obtener de los tamaños de sus propios frames,
no suponiendo que todas las claves pertenecen al asset base.

## Instrumentación disponible y método

- Pipeline por ejecución: distribución de miembros por batch, singles y tails por
  presión; callbacks activos/finalizados y espera del executor; cabeza pendiente,
  ready-ahead, espera de fuente; claves/bytes reservados y espera de presupuesto.
- HTTP por proceso: actividad/pico, singles/groups, fallback por progreso retenido;
  relojes agregados de cabeceras, lectura, escritura/force privados, autenticación
  y publicación. `headersWait` incluye conexión/TLS/cabeceras, no es TTFB puro.
- Writer: disponibilidad de fuente, verificación privada, append/force y checkpoint.
  Los relojes de callbacks concurrentes y los contadores anidados **no se suman
  como una partición del tiempo de pared**.
- Frames del checkpoint: EKey, CKey y tamaños exactos de nuevos objetos durables.
  No son un contador de bytes de red: hay que separar raws/partials ya presentes,
  progreso pendiente y overhead CASC. Se conservan inventarios privados antes y
  después y etiquetas UI con sus unidades/redondeo para la ventana/subventanas.

Primera medición con C152 sin nueva instrumentación ni APK. Si los agregados y
frames no permiten atribuir el comportamiento por tamaño, se registra esa carencia
antes de añadir únicamente los contadores necesarios. No se añade un log por
request/objeto ni se confunde un pico de HTTP con ocupación sostenida.

Una revisión de la planificación a partir de una snapshot no justifica cambios:
con 34 claves, batches grandes pueden ocupar pocos callbacks, pero no existe un
techo universal de tres HTTP; las colas incluyen singles y grupos parciales.
El histograma real y las fases deben contrastar esa hipótesis. También siguen
abiertas lentitud HTTP, espera de la cabeza y backpressure del writer.

## Preflight y continuidad

Comprobación de solo lectura a partir de 12:50 UTC:

- Instalado C152, `versionCode=152`, `0.2.128-regex-startup-test`.
- UI En pausa/Reanudar, cero servicios updater y ningún proceso de juego/Wine.
- Wi-Fi conectado; batería al 100 %, temperatura reportada 23,3 °C.
- Espacio disponible 22,74 GB internos y 98,61 GB externos, decimales, a partir
  de 22.208.104 y 96.303.488 KiB respectivamente.
- Checkpoint privado previo: 26.625.208 bytes, 369.794 frames de objeto; cabecera,
  reserved fields y CRC comprobados sin modificar el teléfono. Contiene
  24.346.537.800 bytes encoded ya durables, **no nueva red** de este goal.

Controlador externo de un solo uso: registra intención antes de Resume, usa lock y
estado atómico, se niega a repetir una ejecución existente, no confirma nuevos
costes/destinos, observa aproximadamente diez minutos de payload y termina mediante
Pausar. La preparación se cronometra aparte. No se borra ni reinicia progreso.

Resume se envió una única vez a las 12:55:52,830 UTC. Un reinicio canceló la
notificación Shell, pero la comprobación del PID y del estado durable confirmó
que el controlador seguía vivo, en preparación. No se repitió la acción.

Artefactos privados fuera del repositorio, relativos al directorio de trabajo
externo habitual:

- `download-transfer-speed-20261026/catalog-summary.json`
- `download-transfer-speed-20261026/checkpoint-before-summary.json`
- `download-transfer-speed-20261026/preflight/`
- `download-transfer-speed-20261026/baseline.py`
- `download-transfer-speed-20261026/baseline/`

## Línea base C152: payload sostenido

Resume 12:55:52,830 UTC; primera etiqueta visible de payload 13:06:58,501;
última muestra de la ventana 13:17:07,707; Pause 13:17:25,281. La preparación
no entra en el denominador de la ventana. El control de foco/pausa y el cierre
añaden tiempo fuera de ella; tampoco se presenta como tiempo de transferencia.

| Ventana | Duración, s | MB/s decimales de progreso útil UI |
|---|---:|---:|
| Total visible | 609,206 | 1,939 |
| 1 | 121,638 | 0,696 |
| 2 | 122,253 | 2,091 |
| 3 | 121,759 | 2,479 |
| 4 | 121,770 | 2,637 |
| 5 | 121,787 | 1,789 |

El total cruza de MiB a GiB: considerando **solo** su redondeo, el intervalo
aritmético es 1,851–2,027 MB/s. No es un intervalo estadístico ni incluye retraso
de publicación/captura UI. La última subventana tiene mayor incertidumbre por ese
cambio de unidad (1,348–2,230 MB/s por redondeo solamente).

En la ejecución completa, no solo entre esas dos muestras:

| Categoría durable nueva | Objetos | Bytes encoded |
|---|---:|---:|
| Pequeño | 20.277 | 306.514.692 |
| Intermedio | 3.914 | 686.912.560 |
| Grande | 155 | 202.679.546 |
| Total | 24.346 | 1.196.106.798 |

Las categorías se extraen de los frames, con prefix anterior intacto y todos los
CRC válidos: checkpoint **369.794 → 394.140**. No permiten atribuir MB/s separados
a tamaños intercalados ni corresponden exactamente a los límites UI. Había
4.660.954 bytes en 25 raws/partials no checkpointed y quedaron 792.698 en 26.
Todos los iniciales quedaron durables o retenidos. El balance encoded de entrada
útil sería **1.192.238.542 bytes** para la ejecución completa, incluyendo prefetch
de preparación y pausa/cleanup; no es el contador wire/reintentos ni el numerador
exacto de los 609,206 s.

Fin 13:17:42 UTC: En pausa/Reanudar; cero servicios updater, `httpActive=0` y
7.046/7.046 callbacks finalizados. Se retienen checkpoint y raws. Batería al 100 %,
temperatura reportada 23,3 → 32,4 °C, alimentado por AC. No hubo otra confirmación
de costes ni una descarga completa.

### Diagnóstico y candidata mínima

El writer informa 786,067 s desde su apertura, de los cuales 157,802 preceden a
su primera petición de fuente: **no** se usa todo ese tiempo como payload. En
los 628,265 s posteriores, fuente acumula 264,806 s (42,1 %), append/force
140,698 s, verificación privada 78,346 s y checkpoint 8,349 s. La retirada raw
añade un agregado de 53,301 s. Hay costes no incluidos y relojes anidados; no es
una partición exhaustiva de la ventana UI.

En paralelo, el productor espera presupuesto 497,047 s. Hubo 4.098 batches
(2–16 miembros), 2.948 singles, 1.468 cabezas pendientes de 24.347 resoluciones
y 637 muestras sin ready-ahead. Los callbacks suman 2.008,067 s concurrentes:
el pico 16 **no** acredita ocupación sostenida de los 16 workers. Dividir por el
intervalo posterior a la primera fuente da un equivalente aproximado de 3,2,
no un promedio medido exactamente dentro de los límites UI; incluye trabajo de
prefetch anterior a esa fuente.

Restando el snapshot anterior del **mismo PID**, las peticiones HTTP aumentan en
2.951 singles y 4.096 groups. Cabeceras acumulan 1.332,232 s concurrentes frente a
395,630 de cuerpo; no son segundos consecutivos ni aíslan CDN, DNS o TLS. El
coste de petición y la disponibilidad de fuentes bajo presión de 34 claves
justifican probar una distribución de trabajo menos concentrada, no aumentar
workers/cuotas ni volver a quitar checks/forces. El representante `Network`
Wi-Fi ya se reutiliza; no se repite una supuesta corrección de keepalive.

**Candidata:** limitar únicamente los grupos creados por el productor sourceless
a **dos objetos**. Así 16 callbacks de dos miembros caben en 32 de las 34 claves
sin esperar a un ACK para siquiera arrancar. La API de transporte mantiene su
máximo 16/1 MiB, rangos exactos, autenticación individual y fallback; quedan
iguales los 16 workers, 34 claves/1 GiB, grupos durables de 16, orden físico y
guards. Los objetos >1 MiB siguen por single. No se afirma que dos sea óptimo:
se contrasta una sola intervención con regresión de scheduling y ensayo físico,
incluido el riesgo de más peticiones/coste de cabeceras para objetos pequeños.

Artefactos adicionales: `baseline/analysis.json`, `baseline/updater-filtered.log`,
inventarios/checkpoints privados y `analyze_baseline.py` (análisis offline,
sin control del dispositivo). El ensayo terminado no se vuelve a ejecutar.

## Implementación y regresiones focales

El cambio de producción añade una constante privada y sustituye solo las tres
referencias del productor (capacidad inicial y dos condiciones de flush). No se
cambian transporte, writer, recuperación ni guards. Es una implementación dentro
de AC-12 y FR-003/FR-004/NFR-001 de US-008; UC/AC, requisitos y límites aprobados
siguen iguales, sin convertir el valor heurístico dos en un contrato de producto.

La regresión existente de agrupación se adapta a 32 rangos contiguos: exige que
arranquen 16 callbacks antes de que cualquiera pueda completar sus raws y valida
pares exactos, objetos completos y ausencia de workers vivos. En C152 sin la
corrección falla por la barrera esperada (un caso, sin skips). Con la corrección
pasan **siete casos, cero fallos/errores/skips**: esa regresión, presión de presupuesto
con prefix y tail, cancelación de batch, prefetch con commit físico ordenado,
pausa/reanudación sin overdownload y diagnóstico de readiness/future-wait.

JDK 17.0.20.1+1; Gradle offline `:app:testModernDebugUnitTest --tests ...`, copia
externa aislada del árbol C152. No se altera el árbol autenticado anterior ni se
repite su ensayo. El fuente probado coincide byte a byte con ambos archivos del
repositorio. Artefactos: `focused-tests/{red,green}-summary.json`, XML y logs
Gradle bajo `download-transfer-speed-20261026/`.

Revisión independiente del diff y snapshots de invariantes/test, sin acceso del
revisor al árbol ni reejecución de checks: sin defectos accionables. La anterior
expectativa de tres miembros en el productor deja de aplicar; la API de transporte
sigue independiente y sus pruebas de rangos/autenticación/resume no se modifican.
La revisión no acredita rendimiento: más peticiones pueden contrarrestar el
paralelismo y la comparación física sigue pendiente.

## C153 autenticada e instalada

Fuente `39c71e4`, retag `9053b35`: C153,
`0.2.129-two-object-prefetch-test`. Tres pruebas Python focales del retag y pins
pasan. Reconstrucción canónica nueva: solo difieren de C152 el updater, su test,
la versión Gradle y el marker; **2.222 de 2.226 archivos idénticos**. Inputs
autenticados, lineage de controller/bridge y paridad de payload permanecen iguales.

Build Modern offline con JDK 17: **4 min 59 s**. Requiere el mismo `local.properties`
de C152, que solo declara el SDK: un bootstrap previo de 19 s se detuvo por su
ausencia, antes de compilar; no se repitieron reconstrucción ni pruebas verdes.
Auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2.226 fuentes coincidentes con el
receipt y 178 payloads, además de firma/ABI/manifest/JNI. Ese conteo de auditoría
no significa 2.226 archivos idénticos a C152.

APK exacto: **484.925.975 bytes**, SHA-256
`443114fb12a5876ab188c8e9ec7924edb675b7993c2d79f8c4a4b9c73fe5546a`.
Artefactos externos: `download-two-object-c153-20261026/`, incluidos receipt,
`source-comparison.json`, `build.log` y `apk-audit.json`.

Actualización `install -r` confirmada a las 14:05:08 UTC, sin borrar datos. El
controlador se detuvo al esperar En pausa/Reanudar: un proceso nuevo muestra
Inicio/Cliente no instalado para esta instalación incompleta. La inspección
posterior confirma C153, **checkpoint idéntico de 394.140 objetos y cero servicios
updater**; no hubo Resume ni confirmación de payload ni se vuelve a instalar.
Se recuperó el resumen del mismo cliente `1.60.1.70532`/USB antes del ensayo:
61,2 GiB de descarga, 63,4 GiB adicionales, app ≤1,7 GiB y cliente ≤61,7 GiB,
todos dentro de los límites consentidos. Este arranque frío
es preparación obligatoria de la candidata, no una nueva optimización de inicio.

## C153: ensayo completado, candidata no aceptada

Una sola confirmación Download 14:20:21,108 UTC; payload visible 14:27:42,530
→ 14:37:54,975; Pause 14:38:23,439; final 14:38:42,948. Los reinicios del servidor
no repitieron acciones ni análisis: se esperó a los procesos originales.

| Ventana C153 | Duración, s | MB/s decimales de progreso útil UI |
|---|---:|---:|
| Total visible | 612,446 | 0,872 |
| 1 | 122,618 | 1,151 |
| 2 | 122,067 | 0,631 |
| 3 | 122,378 | 0,739 |
| 4 | 121,912 | 0,796 |
| 5 | 123,471 | 1,039 |

El intervalo aritmético por redondeo es 0,871636–0,871979 MB/s, no un intervalo
estadístico. Frente a los 1,939 MB/s de C152, la diferencia observada es **−55,0 %**.
No se acepta C153 como mejora ni se convierte la diferencia de cohortes en una
causa probada del descenso. C152 usó Resume en el mismo proceso; C153 un proceso
nuevo tras actualizar y Download desde el resumen recuperado. La preparación
está excluida, pero cachés/pools, checkpoint, red y composición siguen distintos.

Delta durable de toda la ejecución: **394.140 → 407.694**, 13.554 objetos,
598.223.733 bytes encoded: pequeños 11.430/282.343.263 bytes, intermedios
2.098/282.306.317 y grandes 26/33.574.153. Hay representación de ambos extremos,
pero no MB/s aislados por categoría. El prefix no cambia y todos los CRC son
válidos. De 792.698 bytes en 26 raws/partials quedan 166.766 en 19; ninguno inicial
desaparece sin quedar durable o retenido. Balance útil encoded 597.597.801 bytes,
no wire ni numerador de la ventana UI. Fin En pausa/Reanudar, cero HTTP/servicios,
7.648/7.648 callbacks completados. Batería 100 % AC, 25,3 → 32,4 °C.

### Reconsideración tras el resultado negativo

Las diferencias entre el primer y último snapshot periódico permiten comparar
fases sin restar procesos distintos. Sus intervalos son **624,090 s C152** y
**642,964 s C153**, no los límites UI:

| Diagnóstico del intervalo periódico | C152 | C153 |
|---|---:|---:|
| Objetos durables/s | 38,87 | 20,90 |
| Bytes encoded durables, MB/s (no red) | 1,895 | 0,923 |
| Peticiones HTTP iniciadas/s | 11,23 | 11,76 |
| Segundos de callback completado / segundo | 3,19 | 5,86 |
| Espera de fuente, s | 263,281 | 443,575 |
| Espera de presupuesto del productor, s | 494,943 | 522,292 |
| Cabeceras concurrentes, s | 1.322,571 | 2.779,005 |
| Cabeceras / petición iniciada, ms aproximados | 188,78 | 367,64 |

Los contadores se atribuyen al completar invocaciones y sufren censura de bordes:
no son ocupación HTTP continua ni TTFB puro. Más paralelismo de callbacks no elevó
proporcionalmente la tasa de peticiones. No se demuestra un límite del CDN.
Los costes privados/append por objeto no muestran una causa equivalente al
descenso: se vuelve a examinar la disponibilidad y admisión, no los forces.

Inspección dirigida: `PrefetchBudget.requestMeasured` duerme **100 ms sin aviso**
cuando falta capacidad; `release` no despierta al productor. Puede dormir incluso
si se liberó capacidad entre el primer check y el sleep. Es una demora artificial
verificable en código, no todavía una explicación única del throughput. Se revisa
una espera notificada con recheck atómico y el mismo fallback de 100 ms, restaurando
antes la agrupación original; sin cambiar límites, orden, ACK/retiro ni cadencia.

Artefactos: `candidate/{summary,analysis,steady-pipeline}.json`,
`baseline/steady-pipeline.json`, `comparison.json`, logs/inventarios privados,
`steady_pipeline.py` y `analyze_candidate.py`, bajo el mismo directorio externo.

## Alternativa verificada: despertar al liberar presupuesto

Se restauró la agrupación original de hasta 16 objetos/1 MiB, retirando el cap 2
de C153. El presupuesto usa un monitor privado común para comprobar capacidad,
esperar y notificar al liberar una reserva o resetear. Recomprueba la capacidad
atómicamente antes de `wait(100)` para no perder una liberación anterior al park.
Cancelación, evacuación y callback de tiempos siguen fuera del monitor; se conserva
el fallback de 100 ms y no se toca el ACK, retiro raw, force ni ningún límite.
La clase interna solo se hace accesible al módulo para la regresión directa.

La prueba `prefetchBudgetRechecksCapacityReleasedImmediatelyBeforeItParks` libera
capacidad deliberadamente desde el callback de cancelación entre el check inicial
y el park: exige admitir sin esperar ni evacuar, sin umbral temporal. En la rama
de sleep original falla exactamente con «released capacity must be rechecked before
parking» (RED, un test/una failure). Con el cambio, **nueve pruebas pasan, sin
fallos, errores ni skips**: carrera, aritmética/cuotas/evacuación, batch contiguo,
presión/prefix/tail, cancelación, orden físico, 16 partials/reanudación y readiness.
Gradle offline JDK 17, 1 min 17 s; fuente y test ensayados idénticos al repositorio.
Artefactos `wakeup-focused-tests/{red,green}-summary.json`, XML, logs y hashes bajo
el directorio externo. No se repitieron las verificaciones verdes de C153.

La revisión independiente del snapshot y evidencias RED/GREEN no halló defectos
accionables; no tuvo acceso a archivos ni repitió checks. El diff completo fue
inspeccionado localmente. Esto verifica el cambio acotado, **no su rendimiento**.
Despertar por cada liberación podría producir más singles por presión y aumentar
el coste HTTP; se comprobarán esos contadores en el ensayo. El reloj de espera
ahora incluye la readquisición del monitor y no es exactamente el antiguo sleep.

## C154: candidata autenticada para el ensayo

Fuente `802f8b6`; retag canónico `41aa40e`, **154 / 0.2.130-budget-wakeup-test**.
Las tres pruebas Python focales de versión/marker/receta pasan (0,10 s); no se
repitieron los nueve tests Kotlin verdes. Nueva reconstrucción autenticada en
`download-budget-wakeup-c154-20261026/`, sin modificar los árboles C152/C153:

- Inventarios con las mismas 2.226 rutas, 2.222 idénticas a C152. Solo cambian
  updater, su test, versión Gradle y marker. Fuente/test generados coinciden con
  los hashes GREEN. Inputs autenticados, lineage controller/bridge, sanitización
  de privacidad y payload parity permanecen idénticos.
- Build Modern offline JDK 17: **5 min 17 s**, 68 tareas (45 ejecutadas/23 cache).
  Se reutiliza la configuración SDK local conocida antes de compilar, no la
  reconstrucción ni los outputs anteriores.
- APK exacta **484.926.695 bytes**, SHA-256
  `bff81e26ac9e7066c39c96d3665fab16a579aafcf359b887162d7215a21f1d05`.
  Auditoría **PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT**, 2.226 fuentes coincidentes
  con el receipt y 178 payloads. Ese contador no significa 2.226 fuentes iguales
  a C152 ni demuestra throughput.

`physical_pipeline.sh` se lanzó una sola vez: instalación `-r` tras preflight
fresco, checkpoint exacto de los 407.694 objetos de C153, recuperación del resumen
del mismo cliente/USB y confirmación solo dentro de los costes ya consentidos.
Cada etapa tiene lock, informe/intent persistido y rechazo de replay; un error
detiene la cadena, no repite Download. La ventana visible se limita a unos diez
minutos y termina en pausa cooperativa. Los artefactos C154 de medición se separan
en `wakeup*`, sin sobrescribir baseline, C153 ni sus análisis.

## Ensayo C154: no aceptado; interacción de retiro y agrupación

El controlador original terminó sin error, sin replay tras los reinicios:

- Instalación preservadora finalizada 15:34:01 UTC: checkpoint de 407.694 objetos
  idéntico byte a byte, sin updater activo. Recuperación del resumen sin payload
  hasta 15:39:59 UTC: mismo cliente/USB, 60,7 GiB de descarga, 62,9 adicionales,
  app hasta 1,7 y cliente hasta 61,1, todos dentro de lo consentido.
- Download único **15:40:26,556469 UTC**. Payload visible
  **15:48:21,640617–15:58:34,892944**, **613,252 s**, delta UI 887.200.153,6 bytes:
  **1,446713 MB/s** decimales, límites solo por redondeo 1,446542–1,446884.
- Frente a C152: **−25,38 % observado** (solo aritmética de redondeo
  −28,63–−21,82 %). Frente a C153 rechazada: +65,94 %, insuficiente para aceptar.
  Siguen siendo cohortes secuenciales distintas, no un A/B causal ni tasas aisladas
  por tamaño. Subventanas de aproximadamente dos minutos:
  **0,235 / 1,038 / 1,449 / 2,927 / 1,572 MB/s**.
- Pause único **15:59:03,465754 UTC**; fin **15:59:12,321363**:
  **En pausa/Reanudar, cero servicios updater/HTTP, 10.246/10.246 callbacks
  terminados**, progreso conservado. Batería 100 % AC, 25,9→32,6 °C.

Checkpoint **407.694→420.170**, prefijo inalterado y CRC de todos los frames
válido. Delta de toda la ejecución, no de la ventana UI:

| Categoría | Objetos nuevos durables | Bytes encoded |
|---|---:|---:|
| Pequeño | 10.688 | 209.773.212 |
| Intermedio | 1.582 | 415.499.398 |
| Grande | 206 | 294.601.694 |
| Total | 12.476 | 919.874.304 |

Raw no comprometido: 19 claves/166.766 bytes antes, 22/543.273 después; ninguna
clave inicial desaparecida sin commit o retención. Balance de entrada de toda
la ejecución **920.250.811 bytes**, no bytes wire/reintentos ni numerador UI.

El intervalo entre snapshots periódicos es 653,710 s: 12.352 commits,
916.938.506 bytes CASC encoded (**1,402669 MB/s, no tasa de red**), 18,895 objetos/s.
Hay 15,536 inicios HTTP/s frente a 11,226 de C152, pero 353,675 ms de headers por
inicio aproximado frente a 188,777; no es TTFB aislado. Source wait 449,363 s,
budget wait 566,503 s, headers 3.591,921 s y body 683,073 s concurrentes. Los
6,834 callback-segundos por segundo de intervalo son atribución al completar,
no ocupación HTTP continua. El reloj del budget cambió; no se interpreta su
diferencia como tiempo puro de polling ni se suman esos relojes.

La señal más concreta es la fragmentación: final **911 grupos / 9.335 singles**,
de ellos **7.900 pressure-tail singles** frente a **379** en C152. Este contador
identifica candidatos contiguos partidos por falta de capacidad; no se explica
simplemente por menos adyacencia en el catálogo. Notificar cada retiro permite
reservar un hueco antes de liberar los restantes del mismo grupo. No se atribuye
todo el descenso a ello ni se afirma un límite CDN. Se revisa el plan antes del
siguiente cambio: acumular exclusivamente reservas de raws ya retirados/forzados
y liberarlas juntas antes de avanzar o esperar el consumidor. Se mantienen cuotas,
ACK, retiros/forces individuales y writer/formato CASC; no se prueba otro cap.

Evidencia externa: `wakeup/{summary,analysis,steady-pipeline}.json`, checkpoints,
`updater-filtered.log`, `wakeup-comparison.json` y pipeline C154 completo/exit 0.

## Pendiente

Corregir y verificar la granularidad del presupuesto, y medir una nueva candidata
autenticada frente a C152. C153/C154 terminaron pausadas; no se repiten sus ensayos.
El goal **no** cumple todavía la mejora exigida.
La línea base mide variación actual, pero no demuestra una causa única de todos
los descensos históricos, throughput aislado por tamaño ni una mejora candidata.
