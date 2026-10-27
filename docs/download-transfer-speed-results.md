# Velocidad de transferencia: diagnóstico pequeño/grande

Fecha: 2026-10-26. Goal original `5caa6403-5b8f-47dc-ba95-ba40fbbe686e`;
continuaciones autorizadas `fd7baae8-8a14-47b0-8452-6d8f7434217e` y
`0d3ea00f-a91e-4702-88ad-febb37adb87f`; relanzamiento autorizado 2026-10-27:
`5a98db34-7924-4936-86dd-0e9fce64eacd` (actual, otras seis horas de margen).
**Resultados exploratorios; mejora general no validada. Diagnóstico reabierto.**

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

## Corrección de la granularidad: verificación local

Se conserva el monitor de C154, pero no se vuelve a notificar tras cada raw nuevo.
El consumidor acumula, como máximo, 16 reservas de fuentes ya reconocidas,
retiradas y con su unlink forzado; no acumula bytes ni pospone los retiros/forces.
Libera esas reservas atómicamente antes de tomar la siguiente entrada, después
del flush parcial por presión **antes** de esperar la cola, y al retornar el
writer para el último grupo. El contador `submittedUncommitted` se reduce después
de liberar, evitando una ventana de falso «no queda trabajo drenable». Las
reservas de claves con `.part` hermano se conservan. En error/cancelación no se
invita trabajo nuevo: se mantienen hasta el join/reset ya existente.

Todo queda en `ForeverClientUpdater.kt`; no se cambia el writer CASC, sus pins,
API, formato, cadencia ni orden. El estado es exclusivo del writer, no del
verificador de prefix ni de los workers. El rollover no se deduce del mapa de
fuentes pendientes: ese mapa puede contener ya la próxima fuente resuelta.

La regresión existente de regrouping bajo presupuesto para tres objetos se
refuerza con una observación nullable, conforme a los hooks de prueba existentes:
cada raw retirado conserva su reserva hasta el handoff del consumidor. Con la
liberación individual de C154 falla exactamente esa aserción: **RED, 1 prueba / 1
fallo esperado**, sin error ni skip. Sin cambiar el test, la corrección pasa
**10 pruebas / 0 fallos, errores o skips**, offline JDK 17 en **1 min 17 s**:
recheck antes de wait, cuotas/deuda/drain/evicción, batch exacto, presión con un
objeto, regrouping, cancelación y join, paralelismo con orden físico, 16 parciales
y resume, readiness y barrera durable de 16 raws CASC.

Evidencia externa en `coalesced-focused-tests/`: `red-summary.json`,
`green-summary.json`, XML y logs; la copia probada coincide byte a byte con el
repositorio. Revisión independiente del snapshot suministrado sin defectos
accionables (sin acceso a filesystem ni reejecución de checks); GREEN contrastado
localmente con los XML y hashes exactos. Ni estos checks ni la eliminación
de fragmentación local acreditan velocidad física: la reconstrucción y el ensayo
se registran por separado, con comparación primaria contra C152.

## C155: candidata autenticada y ensayo acotado

Fuente `cc36fe6`; retag canónico `101a1cd`, **155 / 0.2.131-coalesced-budget-test**.
Las tres pruebas Python focales de versión/marker/receta pasan (0,10 s); no se
repitieron los diez tests Kotlin verdes. Nueva reconstrucción autenticada en
`download-coalesced-budget-c155-20261026/`, conservando los árboles anteriores:

- Mismas 2.226 rutas, **2.222 idénticas a C152**; solo cambian updater, su test,
  versión Gradle y marker. Los hashes generados coinciden con GREEN. Inputs,
  lineage controller/bridge, sanitización de privacidad y payload parity idénticos.
- Build Modern offline JDK 17: **5 min 11 s**, 68 tareas (45 ejecutadas/23 cache),
  con la configuración SDK local conocida. Pipeline original completo/exit 0;
  no se repitió al perder la notificación Shell por reinicio.
- APK exacta **484.929.311 bytes**, SHA-256
  `6a5d9bae6beec1da56aa233aed329ef9747f4e2d4ccca3fb6c124b00b176acb3`.
  Auditoría **PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT**, 2.226 fuentes coincidentes
  con el receipt y 178 payloads; no significa 2.226 iguales a C152 ni rendimiento.

Controlador físico lanzado una sola vez, con locks/estado atómico/anti-replay:
requiere C154 pausada y el checkpoint exacto de 420.170 objetos antes de instalar
con datos conservados. Recupera el resumen del mismo cliente/USB; solo confirma
payload si respeta los límites ya consentidos. Observa unos diez minutos visibles
y pausa cooperativamente, comprobando ausencia de trabajo activo. Artefactos
`coalesced*`, separados de todas las mediciones anteriores. Instalación finalizada
**16:42:02 UTC**, Inicio inactivo: checkpoint de **420.170 objetos / 30.252.280
bytes idéntico**, cero servicios updater y ninguna acción Resume/Download del
instalador. La preparación posterior terminó 16:48:19 UTC sin confirmar payload:
mismo cliente/USB, 59,8 GiB de descarga, 62,0 adicionales, app hasta 1,7 y cliente
hasta 60,3, dentro de los límites consentidos.

## Ensayo C155: mejora agregada, aceptación por tamaño pendiente

La cadena original terminó una vez, sin error, replay ni fallo de cleanup:

- Download **16:48:47,518082 UTC**; payload visible
  **16:56:41,956351–17:06:57,430443**, **615,474 s**. Delta UI 1.394.940.108,8 bytes,
  **2,266448 MB/s**; límites solo por redondeo 2,179219–2,353677, no de confianza
  ni de latencia de publicación/captura.
- Frente a C152, **+16,90 % observado**; aritmética de redondeo +7,51–+27,18 %.
  No es una atribución causal al código. Subventanas de unos dos minutos:
  **2,240 / 2,677 / 0,947 / 2,793 / 2,670 MB/s**; persiste variación.
- Pause **17:07:28,711184 UTC**; fin **17:07:37,197412** en **En pausa/Reanudar**,
  cero servicios updater/HTTP y **3.830/3.830 callbacks terminados**. Checkpoint
  **420.170→435.310**, prefijo inalterado y todos los CRC válidos. Batería 100 % AC,
  26,2→32,9 °C.

Delta durable de toda la ejecución, no de la ventana UI:

| Categoría | Objetos | Bytes encoded |
|---|---:|---:|
| Pequeño | 11.578 | 210.778.639 |
| Intermedio | 3.294 | 826.191.785 |
| Grande | 268 | 377.245.629 |
| Total | 15.140 | 1.414.216.053 |

Raw pendiente: 22 claves/543.273 bytes antes, 2/59.766 después; ninguna clave
inicial desaparecida sin commit o retención. Balance de entrada de toda la
ejecución **1.413.732.546 bytes**, no bytes wire/reintentos ni numerador UI.

La fragmentación registrada baja: **118 pressure-tail singles**, 2.300 grupos
(media 5,925 miembros) y 1.530 singles del productor, frente a 7.900/911/9.335 en
C154. En el intervalo periódico de 646,144 s: 15.040 commits, 23,277 objetos/s y
1.409.928.231 bytes CASC encoded (**2,182065 MB/s, no tasa de red**). HTTP 5,907
inicios/s, headers 320,796 ms/inicio aproximado; source wait 369,931 s, budget
434,807 s, headers 1.224,480 s y body 987,868 s concurrentes. No se suman esos
relojes; los 3,736 callback-segundos por segundo no son ocupación HTTP continua.

La revisión de aceptación identifica una carencia real: el objeto medio del
intervalo pasa de **48.760 a 93.745 bytes** y cambia la mezcla de tamaños. Menos
objetos/s no prueba una regresión, pero más MB/s tampoco prueba mejora de pequeños
y grandes por separado. Los samples existentes de singles son insuficientes:
C152 seis pequeños/un intermedio/un grande; C155 uno de cada categoría. No se
extrapolan ni se seleccionan ejemplos favorables.

Se conserva el resultado agregado como prometedor, **sin cerrar el criterio de
rendimiento**. Siguiente comprobación mínima, ya prevista por el plan: agregados
fijos de callbacks homogéneos pequeños/intermedios/grandes y grupos mixtos aparte,
bytes útiles frente a encoded/reuso y tiempo de servicio concurrente. La referencia
y el optimizado usarán la misma instrumentación, con nuevas APK autenticadas,
ensayos acotados y progreso retenido; no se cambia otra heurística. Ese tiempo de
servicio no se presentará como throughput global ni resolverá por sí solo la
variación temporal de red.

Artefactos externos: `coalesced/{summary,analysis,steady-pipeline}.json`, checkpoints,
`updater-filtered.log`, `coalesced-comparison.json`; pipeline completo/exit 0.

## Instrumentación mínima para la comparación por tamaño

Se añaden cuatro cohortes fijas de callbacks: todos los miembros pequeños,
todos intermedios, todos grandes o **mixto**, sin imputar un grupo mixto a una
categoría pura. Cada una conserva siete contadores acotados: éxitos, objetos,
bytes encoded declarados, bytes útiles de progreso, nanosegundos de servicio,
fallos/cancelaciones y éxitos sin bytes útiles. Se capturan antes de publicar los
futuros, cuando las entradas todavía no pueden ser retiradas por el writer.

El servicio termina al retornar el fetch, antes de publicar futuros: sus relojes
son concurrentes, no throughput global, ocupación exacta ni tiempo wire. Los bytes
útiles conservan la contabilidad existente: máximo del progreso descontando el
prefijo inicial, sin sumar reuso ni duplicaciones de reintento. Fallos no aportan
bytes/servicio exitoso; deben declararse al comparar intervalos. Los campos de un
snapshot son atómicos individualmente, no un vector transaccional; sus fronteras
siguen censuradas por callbacks concurrentes.

**Versión inicial: cuatro pruebas focales PASS**, cero errores/fallos/skips, JUnit 17:29:05 UTC,
Gradle offline JDK 17 en 3 min 37 s: contadores puros/mixtos/reuso/fallo, wiring de
readiness, batch contiguo y cancelación/join. La copia probada coincide con el
repositorio: updater SHA-256
`5d29e6664c2e7d4e71743190522ba682f2d95b54a12abcdf07b7ee721946e4dc`, test
`280ef7cf190fc5e344a2882fb2582b64f70e9a89b8d1c0a89b3b3e2aa8144f77`.
Máxima línea observada 1.860 bytes UTF-8, dentro del límite de log Android. Los
intentos anteriores de la copia externa no se aceptan como prueba: contenían el
test por error bajo `src/main`; se diagnosticó y corrigió antes del run válido.

No cambia la estrategia C155. La revisión independiente de esa concurrencia y sus
diez regresiones sigue vigente; el nuevo intento de revisar únicamente métricas
no pudo ejecutarse por indisponibilidad del modelo. El diff diagnóstico y las
cuatro pruebas nuevas se verificaron en el proceso principal, sin atribuirles una
revisión independiente que no ocurrió. Evidencia: `cohort-focused-tests/` externo.

La referencia C156 de esa versión compiló en 5 min 11 s, pero la auditoría exacta
rechazó `FetchPipelineDiagnostics$CohortCounters`: descriptor DEX nuevo no admitido.
**No se instaló ni se amplió la allowlist.** Se elimina la clase auxiliar mediante
siete `AtomicLongArray(4)`, conservando los 28 campos y sus significados.
Dos checks focales adicionales PASS (contabilidad pura/mixta/reuso/fallo y wiring
readiness), cero fallos/errores/skips, JUnit 17:48:06 UTC; fuente probada idéntica al
repositorio, SHA-256
`eb840e5af21774afebd05851988f77a397fbf85535e0692f45ee72f1e73b4e39`;
test sin cambios. Evidencia: `cohort-array-focused-tests/`. La auditoría permanece
intacta; hacen falta receipts/builds/APKs nuevos, separados de la referencia rechazada.

El seguimiento instrumental fija **seis minutos visibles por variante** antes de
observar resultados, con subventanas de dos minutos y todos los callbacks agregados.
Complementa, no reemplaza, las ventanas sostenidas de diez minutos C152/C155.
Se informarán cantidades, tamaños medios, miembros por callback, reuso/fallos y
relojes concurrentes; una muestra insuficiente no permite cerrar la aceptación.

## Pareja instrumental C156/C157: fuente y APK verificadas

Fuente optimizada `d5e7b93`; referencia basada en el C152 autenticado. Ambas llevan
exactamente el mismo delta instrumental (11 bloques updater, dos test), comprobado
contra sus preimágenes, sin introducir una clase DEX ni modificar el auditor.
Los hashes de las fuentes optimizadas coinciden con las dos pruebas focales nuevas.

| Variante | Build Modern offline JDK 17 | Bytes APK | Auditoría exacta |
|---|---|---:|---|
| C156 `0.2.132-cohort-reference-test` | PASS, 5 min 2 s | 484.930.867 | PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT |
| C157 `0.2.133-cohort-optimized-test` | PASS, 5 min 23 s | 484.934.223 | PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT |

SHA-256 C156:
`1eed56ca0f6b4a5968b80f1c9627a84d6e2a7aa3720de5ac8416c112d0d79e8b`.
SHA-256 C157:
`3bcd5444f811c4f10adcf5c3dfcccde1e7f28ea781a49936630b188977f1244d`.
Pipeline de host terminado 18:02:55 UTC. Cada auditoría coteja 2.226 fuentes con su
receipt y 178 payloads; **2.222**, no 2.226, son idénticas a C152. Las únicas cuatro
rutas distintas son updater/test/versión/marker. Inputs, lineage, privacidad y
payloads se cotejan íntegros; solo se declara el alias de ubicación del recipe
congelado, sin ignorar diferencias de hash.

C156 se instaló una vez, final 17:57:59 UTC: checkpoint de 435.310 objetos
byte-idéntico, cero servicios y cold Home confirmado, sin Resume/Download en el
instalador. Al corte de 18:03:44 UTC la preparación tenía dos acciones UI y ninguna
confirmación de payload. La cadena física es secuencial y exige la auditoría propia
antes de cada instalación; únicamente el segundo build del host se solapó con la
preparación del primer ensayo. Compilar/auditar **no** acredita throughput.

Evidencia externa `download-cohort-arrays-pair-20261026/`: identidad instrumental,
revalidación del auditor intacto, receipts/source-comparison, build/audit por
variante y `completed-build-evidence.json`. La pareja anterior rechazada permanece
separada; ninguna de sus APK se acepta como sustituto.

## C156: referencia instrumental terminada; análisis recuperado

Download 18:04:29 UTC; payload visible 18:12:40,873–18:18:48,509:
**367,636 s, 1,881607 MB/s** útiles mostrados, límites solo de redondeo
1,881321–1,881892. Tres subventanas de unos 122 s: 0,823 / 1,964 / 2,858 MB/s.
Pause 18:19:17; final 18:19:27: En pausa/Reanudar, cero servicios/HTTP/callbacks
activos y 2.324 callbacks finalizados. Checkpoint 435.310 → **444.654**, prefix y
CRC válidos; 9.344 objetos nuevos: 7.611 pequeños, 1.582 intermedios, 151 grandes.
Los 721.611.555 bytes encoded durables de toda la ejecución no son los bytes de
esa ventana UI ni un contador wire.

Intervalo separado de logs periódicos: 405,941 s. Los siguientes ratios son
**MB útiles por segundo de servicio de callbacks completados, no throughput
global ni velocidad de red aislada por clase**:

| Cohorte | Callbacks exitosos | Objetos | Encoded medio/objeto, bytes | Objetos/callback | MB/servicio-s |
|---|---:|---:|---:|---:|---:|
| Pequeña homogénea | 1.427 | 6.404 | 15.647 | 4,488 | 0,167164 |
| Intermedia homogénea | 437 | 978 | 269.941 | 2,238 | 0,666883 |
| Grande homogénea | 151 | 151 | 1.458.163 | 1 | 1,196391 |
| Mixta | 298 | 1.747 | 77.548 | 5,862 | 0,619571 |

Un fallo pequeño y cero en las otras cohortes; fallos no aportan bytes/servicio
exitoso. Ningún éxito sin bytes útiles en ese intervalo. Bytes útiles y encoded
declarados coinciden para sus éxitos, sin convertirlos en contabilidad de reintentos.
Los extremos concurrentes y el intervalo distinto de la UI siguen siendo límites.

La cadena se detuvo **después** del ensayo por un `ZeroDivisionError` del informe:
una frontera de dos minutos coincidía con el último sample, añadido dos veces.
Se conserva el helper original y se corrige solo la selección de fronteras
interiores estrictamente crecientes. El análisis de los datos ya capturados pasa:
subventanas positivas y suma exacta de duraciones/bytes de la ventana; todos los
controles de checkpoint, parada y contadores también pasan. **No se repitió ninguna
acción UI ni descarga de referencia.** Evidencia: `analysis-boundary-repair.diff`,
`analysis-boundary-repair-verification.json` y `reference/trial/{analysis,cohort-service}.json`
en el workspace externo. Falta la comparación y no se declara mejora por tamaño.

C157 se instaló una vez, final 18:29:14 UTC: checkpoint exacto de 444.654 objetos
preservado, cero servicios y cold Home, sin acciones de descarga en el instalador.
La preparación terminó 18:35:11 sin confirmar payload: mismo cliente/USB,
57,8 GiB de descarga, 60,0 adicionales, app ≤1,7 y volumen cliente ≤58,3 GiB,
dentro de los límites consentidos. El límite de tiempo detuvo el goal original;
reanudarlo no renovó su presupuesto. El usuario autorizó expresamente un sucesor
de una hora, con el mismo alcance, plan y cinco criterios. Se ejecuta únicamente
el controlador C157 pendiente, con guardas frescas y pausa automática; no se
reinstala, repite la preparación ni reduce la ventana fijada por el tiempo disponible.
El usuario autorizó después sustituir el margen de una hora por seis, sin cambiar
objetivo ni criterios; el mismo controlador continuó, sin otro Download.

## C157: ensayo terminado, comparación no representativa

Download 19:26:11 UTC; payload visible 19:34:25,305–19:40:32,523:
**367,218 s, 0,986562 MB/s**, límites solo de redondeo 0,986276–0,986847.
Subventanas de unos 122 s: 1,333 / 0,899 / 0,728 MB/s. Pause 19:41:02;
final 19:41:11: En pausa/Reanudar, cero servicios/HTTP/callbacks activos y los
1.672 callbacks finalizados. Cadena de continuación y análisis terminó exit 0.

Checkpoint 444.654 → **454.511**, prefix/CRC válidos: 9.857 objetos nuevos
(8.046 pequeños, 1.806 intermedios, **5 grandes**), 383.352.987 bytes encoded.
Raw inicial 4 claves/98.311 bytes → final 17/266.841; ninguna clave inicial perdida
sin commit o retención. Balance útil de entrada de toda la ejecución 383.521.517
bytes, distinto de los 362.283.008 bytes mostrados en la ventana y no contador wire.

La caída UI observada frente a C156 es **47,57 %**, no una regresión causal probada:
el tamaño encoded medio durable pasa de **77.227 a 38.891 bytes** y los grandes de
151 a 5. No se acepta el anterior +16,90 % agregado de C155 como prueba general
ignorando este resultado ni se concluye que el cambio de código causó la caída.

Intervalos periódicos distintos de las ventanas UI: C156 405,941 s; C157 398,070 s.
Los ratios siguientes son **MB útiles por segundo de servicio de callbacks
completados**, no throughput global o velocidad aislada de red por clase:

| Cohorte homogénea/mixta | Callbacks C156 → C157 | MB/servicio-s C156 → C157 | Encoded medio/objeto, bytes | Objetos/callback |
|---|---:|---:|---:|---:|
| Pequeña | 1.427 → 1.041 | 0,167164 → 0,188114 | 15.647 → 17.365 | 4,488 → 5,485 |
| Intermedia | 437 → 130 | 0,666883 → 0,508438 | 269.941 → 129.720 | 2,238 → 2,977 |
| Grande | 151 → 5 | 1,196391 → 0,586619 | 1.458.163 → 1.370.838 | 1 → 1 |
| Mixta | 298 → 481 | 0,619571 → 0,570282 | 77.548 → 60.353 | 5,862 → 7,686 |

En esos intervalos: un fallo pequeño en C156, cero en C157; ningún éxito sin bytes
útiles. Los fallos finales completos son tres/dos respectivamente y no se confunden
con esos deltas periódicos. Éxitos excluyen bytes/servicio fallidos; fronteras
censuradas, tamaños, membresía, red y cachés siguen siendo factores de confusión.
Small aumenta 12,53 % en este ratio, pero el servicio medio por objeto es
93,604 → 92,314 ms con tamaños distintos: no demuestra por sí solo una mejora causal.

Los tails por presión bajan 149 → 44 y los miembros medios de batch suben
5,636 → 7,273. Eso respalda la corrección local de fragmentación, **no** que fuera
el cuello dominante. La espera de fuente del writer sigue siendo 268,051/283,176 s
en sus respectivos 411,917/407,132 s posteriores a la primera fuente; no se divide
por el intervalo UI ni se suman esos relojes a los de HTTP concurrente.

Evidencia: `optimized/trial/{summary,analysis,cohort-service}.json`, checkpoint,
inventarios raw y `updater-filtered.log`, `comparison.json` y marcadores de la
continuación en `download-cohort-arrays-pair-20261026/`. Ambos informes particionan
duraciones/bytes UI en subventanas positivas sin repetir ensayos.

### Revisión de método antes de otra optimización

La objeción del usuario es válida: estas ventanas secuenciales son exploratorias,
no un experimento representativo que aísle el coste dominante. Las regresiones
focales prueban integridad/liveness y las correcciones locales; no prueban velocidad.
Se reabre el diagnóstico y se congelan nuevos cambios de estrategia basados solo
en MB/s agregados. Próximo diseño: carga reproducible por tamaños y mezcla,
condiciones frío/caliente declaradas, métricas de pared/ocupación/esperas del camino
crítico y controles que separen disponibilidad/red, autenticación/IO privado y
writer/commit sin debilitar el recorrido real. Cada hipótesis debe tener una
predicción y un experimento que pueda descartarla antes de cambiar código.
Se justificará primero cómo lograrlo conservando progreso y límites, sin montar
un framework ni ejecutar automáticamente otro ensayo por tener más presupuesto.

## Fuente activa y representatividad: revisión de solo lectura

El 20:14:22–20:14:43 UTC se leyó el receipt dinámico activo de C157 y se copió
solo su catálogo/target, no payload del cliente. App instalada 157, cero servicios
updater antes/después, ningún guest observado, cero acciones UI ni descarga.
Checksum del envelope, identidad `1.60.1.70532`, tamaño 99.770.368 bytes y SHA-256
`cf2da57cc3523238cfc70a8b7c773f92454b58fe83af7f01d5044414c125b032`
coinciden; el receipt permaneció idéntico durante la copia.

| Clase del catálogo activo | Objetos | % objetos | % bytes encoded | Mediana encoded | P99 encoded |
|---|---:|---:|---:|---:|---:|
| Pequeña | 938.288 | 81,95 | 17,24 | 11.283 | 62.870 |
| Intermedia | 191.363 | 16,71 | 46,93 | 122.686 | 977.781 |
| Grande | 15.327 | 1,34 | 35,83 | 1.576.355 | 8.690.262 |

El objeto mayor es de 141.273.036 bytes. Los grandes se reparten entre 10.386
`range` y 4.941 `direct`: medir unos pocos grandes de ~1,4 MB en un tramo range
no cubre automáticamente los grandes direct ni la cola de tamaños. Debe evaluarse
la mezcla tanto por objetos como por bytes, además de su contigüidad real.

Con **este catálogo activo**, todos los nuevos frames C156/C157 coinciden en orden
con sus respectivos prefijos no checkpointed: 9.344/9.857 comparaciones completas,
sin mismatch. El anterior forecast basado en el asset bootstrap se descarta.
Al checkpoint 454.511, los siguientes 20.000 objetos físicos contienen
16.010 pequeños/280.005.673 bytes, 3.747 intermedios/862.538.415 y
243 grandes/341.711.469. No predice duración, bytes útiles de red o cobertura direct
ni permite ejecutar otra ventana sin comprobar que el estado sigue igual.

Artefactos externos: `download-controlled-method-20261026/active-catalog/` y
`catalog-characterization.json`. Las consultas usan main read-only y tablas TEMP
en memoria, sin modificar el catálogo. Estos datos sustentan el diseño mixto
prolongado en el plan, no una nueva mejora medida. El usuario aprobó expresamente
esa revisión con «adelante» antes de implementar la instrumentación.

## C158: observación de camino crítico calibrada, sin tuning

Se mantiene la estrategia C157. El observador por ejecución emite una línea de
pared por segundo con progreso lógico exacto, cola, reservas, callbacks/HTTP,
ready-ahead y la cabeza realmente esperando un futuro. Cuenta esas esperas por
tamaño del objeto bloqueado, **no** como coste exclusivo del objeto cuando comparte
un batch. Lee atómicos sin tomar el lock de progreso; se interrumpe y une antes
de retornar, también tras cancelación/error. No modifica cuotas ni el recorrido
de validación, escritura, fuerza, checkpoint o retiro.

Transfer añade gauges actuales de cabeceras/cuerpo/escritura/force/auth/publicación/
progreso y bytes que sus lecturas consumen del cuerpo, incluidos reintentos y un
byte extra inesperado; no cuenta lectura de disco ni prefetch aún no consumido,
y no son bytes TLS/IP wire. El reloj de cuerpo incluye ahora obtener el stream;
progreso se mide también en grupos y si el callback lanza. Estos relojes siguen
siendo concurrentes y algunos anidados, acumulados al terminar invocaciones; los
samples atómicos no forman una partición causal ni ocupación exacta.

**Cinco calibraciones focales PASS**, cero fallos/errores/skips, JUnit
2026-10-26 20:57:20 UTC, Gradle offline JDK 17 en 4 min 15 s:

- Head retenida mediante latch: sample de espera pequeña, ready-ahead existente;
  futuros ya listos no contados como espera. Sampler parado al terminar.
- Cancelación del batch: trabajadores y nuevo sampler unidos antes de retornar.
- Lectura retenida: gauge body activo; callback de progreso activo y body inactivo;
  cancelación deja los ocho gauges HTTP/fase en cero.
- Cuerpos de single/grupo contabilizados exactamente y reuso sin nuevos bytes.
- Cuerpo corrupto reintentado: dos cuerpos consumidos, no un único progreso útil.

La copia aislada probada coincide byte a byte con las cuatro fuentes del repo.
Máxima línea wall observada: 892 bytes UTF-8 (<4.000). Evidencia externa
`download-controlled-method-20261026/critical-path-focused-tests/`:
`summary.json`, inputs con cuatro SHA-256, logs y dos XML frescos; comando exacto
guardado en `verify-critical-path.sh`. Tres checks Python del retag C158 PASS
(0,10 s), sin repetir suites históricas. Revisión independiente del **código
aportado como snapshot**, sin acceso al filesystem ni reruns, sin defectos
accionables; recibió después el GREEN y los hashes exactos. No se presenta como
inspección independiente de archivos.

Estas calibraciones comprueban el instrumento, no una mejora de velocidad ni
su overhead real en el dispositivo.

## C158 autenticada: diagnóstico mixto aprobado

Fuente `673c50e`, versión `158`/`0.2.134-mixed-diagnostics-test`. Reconstrucción
canónica y build Modern offline JDK 17 PASS en **5 min 9 s**, terminado 21:08:40 UTC.
Auditoría exacta **PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT**: 2.226 fuentes matching con
su receipt y 178 payloads. Frente a C152, 2.220 fuentes idénticas; solo cambian
updater/transfer, sus dos tests, versión y marker. Inputs, lineage, privacidad y
payloads intactos; los cuatro hashes generados coinciden con las cinco calibraciones.
Sin relajar el auditor ni añadir una clase DEX.

APK **484.950.239 bytes**, SHA-256
`e8e8e5b9a20a44fecfbfdcd66a87ce7889a1f7f6b80b03c0127ecb11c58d6dbe`.
Evidencia externa: `download-controlled-method-20261026/c158/`, receipt,
source-comparison, build/audit y `completed-build-evidence.json`.

Cadena física nueva lanzada una vez, no repetición C157: preflight fresco, reemplazo
preservador desde el checkpoint exacto 454.511, mismo cliente/USB y costes dentro
de los límites consentidos; preparación nunca confirma payload. Controlador de
ensayo fija 1.200 s visibles o señal de pausa cooperativa al alcanzar 6 GiB de
progreso lógico nuevo. Usa el contador exacto del sampler, no el número UI
redondeado; incluye alineación de recepción de logs con el reloj del controlador.
La señal no es un hard cap de bytes wire: permite la pausa y el drenaje acotados.
Si se alcanza antes del tiempo, la ventana se declara censurada, no veinte minutos
de estabilidad.

Recursos CPU/IO/batería/Wi-Fi cada unos 120 s, solo lectura desde UID shell; no se
crean procesos nativos del UID de la app mientras actualiza. Permisos denegados o
timeouts se conservan como datos no disponibles, sin root ni cambios de seguridad.
Guardas de replay/acciones durables y verificación final En pausa/Reanudar/cero
servicios. Falta observar el resultado original, validar continuidad e interpretar
camino crítico y representación real, sin aceptar rendimiento por compilar/auditar.

## C158 parcial: espera de fuente y timeout de cuerpo HTTP

La cadena original terminó exit 1, **sin reintentar**. La app falló antes de la
pausa temporal/de datos: `SocketTimeoutException` en
`Http1xStream$FixedLengthSource.read` → `fetchBatchInternal:258`, confirmado en el
log del mismo PID. No es un error deducido solo de los contadores. El final
21:45:30 UTC quedó En pausa/Reanudar, cero servicios/HTTP/callbacks y las siete
fases activas a cero. El postmortem de solo lectura a 21:49:37 confirmó cero
servicios y el mensaje genérico de error; ninguna nueva acción UI o descarga.

Ventana visible parcial 21:30:37,379–21:44:58,667: **861,288 s, 0,024016 MB/s**
(límites solo de redondeo 0,023955–0,024077). No son veinte minutos completados ni
una prueba de estabilidad. El intervalo wall alineado dentro de esas recepciones
cubre 859,768 s, 859 muestras, sin huecos excluidos; progreso lógico exacto y cuerpo
consumido aumentan ambos 20.640.113 bytes, 0,024007 MB/s. No son bytes TLS/IP wire.

| Estado observado a 1 Hz | Fracción del intervalo cubierto |
|---|---:|
| Writer esperando el futuro de cabeza | 98,49 % |
| Cabeza pendiente con alguna fuente ready-ahead | 74,16 % |
| Lectura de cuerpo HTTP activa en algún worker | 94,18 % |
| Cabeceras activas en algún worker | 18,86 % |
| Reserva en el máximo de 34 claves | 100 % |
| Callback de progreso activo | 0,70 % |
| Force privado de transfer activo | 0,12 % |

Estados no exclusivos, vectores individualmente atómicos y estado sostenido entre
muestras: no se suman como partición causal. HTTP activo medio 1,91, mediana 2,
máximo 5, pese al pool de 16; ready-ahead mediano 13. Esto acota el coste actual a
disponibilidad de fuentes, amplificado por la espera ordenada y la presión de
claves; **no identifica todavía una causa única CDN/ruta/Wi-Fi/stack Android**.
El writer registró 2,540 s de verificación privada, 2,663 s append/force y 0,462 s
checkpoint, frente a 739,860 s de resolución exitosa de fuentes. La espera del
último futuro fallido también aparece en head/source-wait pero no en el contador
de resoluciones exitosas; no es un error de reloj.

CPU de la app de unos 105–116 % durante varios intervalos (**100 % = un core**),
con verificador del prefijo todavía concurrente; no se atribuye toda esa CPU a un
hilo sin profiling. Ese verificador finalizó 21:42:59 y el timeout llegó 21:45:03.
Wi-Fi observado: RSSI −41 a −43 dBm, enlace 1.201 Mbps a 5.180 MHz; es tasa PHY,
**no ancho de banda de Internet**. Temperatura 29,8–31,5 °C. `/proc/PID/io` denegado
en todas las capturas: IO no disponible, no IO cero.
En el log capturado del intervalo visible hay 29 eventos GC: suman 0,217 s de
pausas reportadas, máximo 10,2 ms por evento. No explican por sí solos minutos de
espera, pero ese log no es un profiler completo ni atribuye la CPU a un hilo.

Continuidad: checkpoint **454.511 → 455.407**, 896 objetos durables y 21.192.677
bytes encoded, prefix/CRC válidos. Solo 867 pequeños y 29 intermedios, todos range;
ningún grande. Raw 17 claves/266.841 bytes → 30/413.626; ninguna clave inicial
perdida sin commit o retención. Balance de entrada completo 21.339.462 bytes.
El contador writer incluye **902 append**, seis sin completar la barrera durable
force/checkpoint/ACK del grupo parcial cuando falló la red. Se comprueba retención,
no se los presenta como durables ni se exige igualdad de esos contadores en un fallo
no cooperativo.

Evidencia externa `c158/trial/partial-diagnostic-analysis.json`, `analyze_partial.py`,
1.077 walls con alineación, recursos y `postmortem-complete/` privado. El análisis
solo leyó datos ya capturados; no repitió el ensayo.

### Control auxiliar de fuente desde host: seis respuestas autenticadas

Manifiesto fijado antes del primer GET: el rango exacto del batch fallido de doce
pequeños (215.868 bytes), un grande range (1.576.355) y otro direct (2.097.166).
Orden prefijado `[0,1,2,2,1,0]`, HTTP/1.1 HTTPS oficial, sin redirects/reintentos,
≤15 s por petición y ≤16 MiB esperado total. Antes de transferir se comprobó que
el mínimo direct grande supera 2 MiB; el límite individual se fijó en 3 MiB,
conservando los límites globales y sin elegir por resultados de velocidad.

Terminado 22:16:39 UTC: **6/6 autenticadas**, 7.778.778 bytes. Se verificaron status,
Content-Range numérico/tamaño/encoding, EKey, tablas/chunks BLTE y CKey decoded.
Tiempos totales en orden: **1,239 / 0,579 / 0,612 / 0,614 / 2,022 / 0,453 s**.
El mismo rango fallido puede servirse rápidamente ahora desde Linux; **no demuestra
que el peer/ruta/Wi-Fi/stack Android fueran correctos durante el fallo**. No es
throughput Android, carga mixta real ni A/B. No se instaló ningún cuerpo ni se
modificó el cliente. Evidencia: `c158/source-control/{manifest,summary}.json`,
headers, cuerpos y verificaciones, más `source_control.py` externo.

Siguiente control discriminante: los mismos objetivos y orden usando el
`HttpURLConnection` de Android, ligado a una única red Wi-Fi validada, en proceso
auxiliar **UID shell 2000**, no UID de la app. Sin instalar APK, iniciar UI,
escribir datos del cliente o tocar su journal; checkpoint antes/después idéntico,
sin updater/guest. Cada petición conserva deadline de 15 s y los cuerpos se
autentican en host antes de usarse como evidencia; nunca como datos instalados.
Fuente/dex y runner se revisan y compilan primero. Si también es lento, apoya una
limitación fuera del writer; si es rápido, siguen pendientes app-UID, conexiones,
concurrencia, cachés y diferencias temporales. No autoriza un cambio de algoritmo
ni equivale a un replay del pipeline mixto.

El primer proceso auxiliar compiló, pero falló cerrado al obtener el contexto
Android (`InvocationTargetException`), **antes de cualquier GET**. Cero respuestas,
checkpoint idéntico, cero servicios y scratch propio eliminado. Se conservan
fuente/dex y reporte; la revisión inicializa el Looper de forma normal y registra
causas anidadas, sin desactivar restricciones ni modificar C158. Ese fallo del
instrumento no aporta evidencia de velocidad del teléfono.

### Android: fuente y concurrencia fuera de la app

El segundo bootstrap falló cerrado, sin GET: AppOps rechazó atribuir `android` al
UID shell. Se conserva el fallo; la consulta de capacidades usa después el paquete
propio `com.android.shell`, comprobado con PackageManager, mediante el mismo Binder
de solo lectura. No se desactivó ninguna restricción, permiso ni comprobación Wi-Fi.

Control V3 terminado 23:03:43 UTC: **6/6 autenticadas**, mismo manifiesto, 7.778.778
bytes, checkpoint idéntico y cero servicios/UI/instalaciones. Tiempos en orden:
0,492 / 0,231 / 0,244 / 0,176 / 0,375 / 0,071 s. El campo legacy `statusLine`
tomaba un header por índice, que aquí era Date: no se usa como prueba de protocolo.
Status numérico, rango, tamaño y autenticación sí se verificaron.

Contraste adicional prefijado **serial/paralelo/paralelo/serial**, tres objetivos
idénticos por fase, un proceso y una instancia Network/pool: 12 GET, 15.557.556 bytes
totales ≤16 MiB, sin reintentos, 15 s por petición. RAM acotada; todo el volcado de
cuerpos se hizo después de las cuatro fases. Compilación javac/d8 y sintaxis PASS;
revisión independiente del snapshot del control, sin acceso a archivos ni reruns.
Terminado 23:24:26: **12/12 autenticadas**, HTTP/1.1 observado con el header correcto,
checkpoint idéntico, cero servicios/UI y scratch propio eliminado.

| Fase | Segundos para 3.889.389 bytes | Máximo de scopes de petición solapados |
|---|---:|---:|
| Serial inicial | 0,952 | 1 |
| Paralelo primero | 2,544 | 3 |
| Paralelo segundo | 0,730 | 3 |
| Serial final | 0,326 | 1 |

Los intervalos monotónicos confirman el solapamiento, no ocupación exacta de sockets.
Hay variabilidad y penalización de algunas conexiones paralelas, **pero no se
reprodujeron los minutos bloqueados ni el timeout**. No se cambia el número de
workers por estas cuatro fases. Son pocos cuerpos, cacheados previamente, UID shell
y otro momento; no exoneran al proceso de la app ni acreditan rendimiento mixto.
Evidencia: `device-source-control-v3/`, `device-concurrency-control/{summary,analysis}.json`,
manifiestos y fuentes/dex/runner externos separados. C158 no se recompiló.

Solo lectura de política actual 23:13:35: app TOP sin bloqueo efectivo, Data Saver,
ahorro y Doze inactivos, pantalla encendida; timeout de pantalla 2.147.483.647 ms.
No explica retroactivamente el fallo ni justifica cambiar ajustes de batería.

Próximo contraste dentro del diagnóstico aprobado: **misma C158**, nuevo Resume
desde 455.407, sin instalar ni repetir los controladores terminados; límite de
1.200 s visibles/6 GiB lógicos y pausa conservados. Si el camino real vuelve a
mostrar durante diez muestras-segundo cabeza pendiente, cuerpo activo y ready-ahead,
se ejecutará una sola vez el control shell de seis GET fijado, durante esa espera.
Su tráfico ≤8 MiB se marca como perturbación auxiliar, no progreso ni tasa de la app.
Se añaden lecturas shell de CPU por hilo/status y estado UID, sin procesos nativos
de la app. Si no aparece el trigger, no se lanza el control. Si fuente auxiliar es
rápida mientras la app sigue esperando, acota contexto/conexiones/pipeline; si ambas
se degradan, apoya un recurso/ruta compartido. Ningún resultado autoriza por sí solo
un ajuste: se contrastará además el periodo de verificación del prefijo.

### Misma C158: veinte minutos mixtos y control simultáneo

Resume 23:53:53 UTC, sin cambiar APK ni algoritmo. Payload visible
27-10 00:06:08,996–00:26:19,970: **1.210,974 s, 1,504145 MB/s UI**
(solo redondeo 1,459768–1,548523). El sampler exacto cubre 1.209,902 s y mide
**1,505921 MB/s** de progreso lógico; los relojes/bordes no son idénticos.
Diez subventanas UI de unos dos minutos: 2,509 / 1,047 / 1,380 / 2,628 / 1,997 /
1,020 / 0,875 / 0,878 / 1,747 / 0,878 MB/s. Duraciones y bytes particionan la ventana.
Esto mejora la representación temporal, **no demuestra un efecto de código**:
la misma APK había observado 0,024 MB/s y timeout en el ensayo anterior.

Pause 00:26:47; final 00:27:07, En pausa/Reanudar y cero servicios/HTTP/active gauges;
los 6.019 callbacks finalizaron. Checkpoint 455.407 → **482.513**, 27.106 nuevos
objetos y 1.857.291.826 bytes encoded, prefix/CRC válidos. Raw 30 claves/413.626
bytes → 6/192.264, ninguna inicial perdida. Balance completo de entrada 1.857.070.464
bytes, no wire ni el numerador de la ventana UI.

Mezcla durable: **22.001 pequeños**, 449.574.475 bytes; **4.842 intermedios**,
1.040.150.213; **263 grandes**, 367.567.138. Todos range. Grandes entre 1.049.203 y
2.094.110 bytes: no representan aún direct grandes ni la cola hasta 141 MB.

En el intervalo visible muestreado:

| Observación no exclusiva | Valor |
|---|---:|
| Cabeza esperando future | 65,18 % |
| Cabeza esperando con ready-ahead | 51,94 % |
| Reserva en el límite de 34 claves | 92,97 % |
| HTTP activos, media ponderada | 2,985 |
| Callbacks activos, media ponderada | 3,166 |
| Ready-ahead, media ponderada | 11,489 |
| Cuerpo/cabeceras activos | 67,83 % / 52,19 % |
| Autenticación/force privado/progreso activos | 8,11 % / 3,97 % / 2,23 % |

Hay bloqueo de cabeza y contrapresión observados; estos estados se solapan, no
atribuyen todas las esperas a una causa ni explican por sí solos la baja ocupación.
El prefijo terminó 00:19:34,679: la cabeza esperó aproximadamente 65 % tanto antes
como después. El progreso muestreado pasó de 1,749 a 1,019 MB/s con objetos distintos;
no se atribuye al verificador. CPU de proceso cayó de intervalos 140–150 % de un
core a 104/53/51 %; las tablas cortas de hilos mostraron prefix 45–80 % y HeapTaskDaemon
11–59 % mientras verificaba. Son snapshots, no un perfil temporal aditivo.
UID TOP, RSSI −41 dBm, PHY 1.201 Mbps; IO por `/proc` no disponible, no cero.

El control shell fijo de seis GET se lanzó una sola vez durante la espera:
00:16:38–00:16:46, **6/6 autenticadas**. La VM estuvo 5,317 s dentro de la ventana
visible; los cinco samples interiores mantuvieron cabeza+ready y reserva al límite.
No se aseguró que fuera la misma cabeza todo ese intervalo, y los objetivos
auxiliares eran otros ya consultados. Sus tiempos 1,061 / 0,994 / 0,768 / 0,752 /
0,822 / 0,425 s no exoneran peer, UID, pool o caché de la petición real.

Evidencia: `c158/paired-trial/paired-diagnostic-analysis.json`, suplemento de
subventanas/tablas `captured-supplement.json`, `paired-source-control/` y observer.
Análisis offline, sin repetir el ensayo ni las consultas ya completadas.

### Contraste de la cabeza derivada, sin cambiar el cliente

Para no comparar otro objeto caliente, el siguiente control usa una sola petición
≤3 MiB/15 s del objeto LargeRange pendiente, excluyendo raws heredados. Su identidad
es **derivada, no un EKey/socket directamente trazado**: un único writer, ACKs físicos
y grupo pendiente 0–15 sitúan la cabeza entre `committed` y `committed+15`; se exige
un único tamaño coincidente y tres segundos con el mismo contador/tamaño.
El orden del catálogo activo coincidió con los 27.106 frames anteriores completos;
se congela un lookahead de 65.536. Si es ambiguo, heredado, fuera del lookahead o
ya terminó antes del GET, no se consulta. Revisión del snapshot condicionada a esa
prueba de pertenencia; el proceso principal inspeccionó los sitios de ACK/source/flush.

Un primer setup terminó antes de Resume/GET por un campo del informe del propio
controlador (`inspectedConsentLabels` frente a `resumedConsentLabels`). Cero acciones;
se conserva intacto. V2 reproduce el error con el JSON capturado y exige que el
consentimiento retomado sea exactamente el original; sin relajar coste/cliente/USB.
No se reconstruye la APK ni el jar compilado para corregir esos metadatos.

V2 es un **control diagnóstico auxiliar, no otro benchmark de estabilidad**: máximo
1.200 s visibles/6 GiB, pero solicita pausa tras al menos dos minutos del intento auxiliar completo,
independientemente de su éxito. Un cierre temprano no se presenta como veinte minutos
y no reemplaza la captura mixta ya completada. Source GET y samples se alinearán
por epoch/monotonic y orden durable posterior; UID/pool/peer y el posible calentamiento
del CDN siguen siendo límites. Esperar el controlador original, no repetirlo.

### Resultado del control de cabeza derivada

El controlador V2 terminó una vez, exit 0, **27-10 01:38:48 UTC**. Resume 01:11:01;
payload visible 01:23:52,316–01:37:55,243: **842,927 s**, 0,876999 MB/s UI
(solo redondeo 0,876875–0,877124). Es la ventana parcial del control prefijado,
**no** otra prueba de veinte minutos. Sampler interior 841,625 s: 0,879828 MB/s.
No se compara causalmente esa tasa con la de la captura mixta anterior.

Pausa enviada 01:38:22; final En pausa/Reanudar, cero servicios/HTTP/gauges y los
3.895 callbacks finalizados. Checkpoint **482.513 → 503.039**, 20.526 nuevos objetos,
910.735.469 bytes encoded, prefix/CRC válidos. Los 20.526 EKeys nuevos coinciden
con el prefijo completo del orden congelado. Raw 6 claves/192.264 bytes → 20/46.650;
ninguna inicial perdida. No quedan append sin checkpoint. Mezcla durable range:
18.733 pequeños, 1.688 intermedios y 105 grandes (1.056.926–2.068.863 bytes).
No se extiende esa cobertura a direct ni a la cola grande.

El control consultó **una sola vez** el objeto de 1.376.883 bytes, candidato único
a offset 3 tras el último ACK. Respuesta 206 autenticada por EKey/BLTE/CKey:
**0,572301 s total**, 0,276366 s cabeceras y 0,294754 s lectura del cuerpo. Ningún
acceso `run-as`/checkpoint durante la actualización; ningún blob se instaló.

La identidad derivada se postvalida con el orden durable y la misma cabeza en los
samples que rodean la petición: el anterior está 0,946 s antes y el posterior
0,482 s después; los tres tienen todos los callbacks activos dentro del scope BODY.
La diferencia entre duración epoch y monotónica del control es 0,000699 s.
La misma cabeza permanece observada en **15 samples durante 14,014 s**; trece
coinciden con todos los callbacks activos en BODY. Es un límite conservador del
scope de espera observado, **no** catorce segundos de lectura de un socket trazado.
El objeto original terminó durable y no era un raw heredado.

Esto descarta tratar ese cuerpo como intrínsecamente lento o atribuir la espera
directamente a escribirlo en CASC: otra conexión en el teléfono lo sirvió rápidamente
mientras el writer seguía esperando. **No identifica una causa única**: se mantienen
distintos UID, proceso/pool, conexión/peer y contexto de ejecución; la petición
auxiliar también puede calentar CDN o competir. No prueba que el CDN o el cliente
estén globalmente libres de problemas, ni valida un nuevo tuning.

En la ventana visible, cabeza pendiente 62,07 %, con ready-ahead 47,21 %; reserva
de 34 claves 95,60 %, media HTTP 2,515 y ready-ahead 10,423. Cabeceras/cuerpo activos
57,67/51,25 %, estados solapados. El prefijo termina 01:37:43,192: solo quedan diez
segundos visibles después, insuficientes para declarar causal su tasa de 4 MB/s.
No se aumenta la cuota ni se cambia el timeout a partir de estos datos.

La inspección de fuentes confirma que ya se conserva la instancia `Network` y no
se desconecta una respuesta correctamente drenada. No se encontró un override
global de keepalive/proxy/user-agent en las fuentes de la app; **eso no verifica
la reutilización real de sockets**. Falta discriminar proceso/UID/conexiones y
validar una corrección sobre cargas equivalentes antes de aceptar mejora general.

Evidencia externa: `exact-head-trial-v2/exact-head-analysis.json`,
`derived-head-interval.json`, `exact-head-source-control-v2/`, contexto/manifiesto
congelados, calibraciones y marcadores `exact-head-v2-run-*`. Análisis solo offline;
ninguna acción ni petición se repitió para cerrar el informe.

### Instrumento de CPU/scheduling: calibración y contraste prefijado

El 27-10 se comprobó solo lectura de capacidades, sin reanudar el cliente. Se evitó
`am profile`, que comparte un slot global y no da exclusión atómica de perfiles
ajenos, y `debuggerd`, cuya implementación exige root. No se cambió seguridad.
Una primera calibración de scheduling dejó TCP/símbolos de bloqueo no utilizables;
no se interpretaron eventos ausentes como red sana ni se repitió esa captura.

La configuración reducida de sesión **propia** Perfetto (4 s, CPU 19 Hz y sched,
8 MiB DISCARD, sin frames de kernel) terminó 07:17:13 UTC. Traza de 173.102 bytes,
18 muestras CPU del PID 11485 con callsites y nombres Java reales; cero errores
de unwind o pérdida reportados. Se verificó en el SDK 34 del teléfono, sin asumir
compatibilidad por la versión de una guía. Fue en reposo, con cero updater/guest:
demuestra que el instrumento funciona, **no** el cuello de la descarga activa.
Herramienta de análisis oficial, fijada por SHA, instalada solo en artifacts externos.

Control prefijado entonces, antes de tuning: **misma C158 y checkpoint 503.039**, nueva
reanudación, sin reinstalar. Tras diez segundos muestreados head+body+ready, una
única captura propia de **20 s a 19 Hz**, con la configuración calibrada salvo la
duración. Se conserva máximo de 1.200 s visibles/6 GiB de progreso nuevo y pausa;
se solicita también pausa a los 120 s de acabar el intento de captura, cualquiera
que sea su resultado. Un fin anterior no sustituye los veinte minutos ya medidos.
Se marcan la captura y su posible perturbación; ningún GET auxiliar nuevo.

Predicciones: workers con demora runnable apreciable y CPU competida por prefix/GC
apoyan un cuello de scheduling/CPU; workers dormidos sin demora runnable apreciable
con head/body activo apoyan espera fuera de CPU. Se examinarán stacks CPU y estados
por TID, sin sumar tiempos de hilos como pared ni atribuir automáticamente un hilo
idle del pool al callback bloqueado. **Los stacks CPU no muestran todos los métodos
bloqueados ni identifican por sí solos un socket/CDN**. El resultado decidirá el
siguiente contraste o cambio mínimo, no se presupone una causa.

Protocolo: guardas frescas de versión, Wi-Fi, guest, consentimiento y checkpoint;
solo procesos/lecturas UID shell durante el updater. Sesión/PID/startticks/nonce
propios, sin stop global ni tocar otro perfil; límites de tiempo, buffer y pull,
y espera de terminación antes del cleanup exclusivo. Calibraciones, SQL y trazas
privadas en `download-transport-diagnosis-20261027/`, nunca en Git ni subidas.

Controladores nuevos revisados y calibrados solo en host antes de ejecutarlos:
sintaxis Python/shell, consentimiento exacto de los JSON previos, predicado de
trigger con datos ya capturados y cinco casos de ownership simulado (propio,
desaparecido, PID reutilizado, UID ajeno, comando ajeno). Solo el primero permite
una señal simulada; los otros no señalan. Configuración idéntica a la calibrada
salvo 4 → 20 s. Hashes congelados en `active-profile-controller-calibration.json`.
La frescura se toma de la última línea wall/alineación coherente, no del JSON UI
que se publica más lentamente. Revisión independiente de snapshot, sin inspección
de archivos ni reruns. El check de ownership y la señal no constituyen un pidfd
atómico; ese límite no se oculta como exclusión absoluta de carreras.

### Captura mixta terminada sin trigger; selección temporal independiente

La cadena anterior terminó 27-10 **08:31:42 UTC**, en pausa y sin trabajo activo:
1.211,056 s visibles, **2,190826 MB/s** UI (redondeo solo 2,146452–2,235200).
Checkpoint **503.039 → 564.690**, prefix/CRC y retención válidos; 61.651 durables,
2.744.229.763 bytes encoded. Mezcla real, toda range: **53.329 pequeños, 8.011
intermedios y 311 grandes**; direct/cola grande siguen sin validar. Misma APK y
algoritmo: no es una mejora de código frente a los anteriores 1,50 MB/s.

El predicado se evaluó correctamente: 121 muestras calificaban, pero su secuencia
continua más larga duró **3,002 s**, no diez. Observer `NO_TRIGGER_BEFORE_MAIN_ENDED`,
sin directorio de captura CPU: **no se inició profiling activo**. No es un fallo de
permisos ni una traza vacía. El análisis de los datos ya cerrados no repitió acciones.
Los subintervalos UI de dos minutos variaron 0,538–4,116 MB/s.

En 1.210,008 s alineados: head wait 22,91 %, reservas al cap 96,03 %, cap **sin head
wait 74,77 %**, cuerpo activo 20,76 %, headers 54,76 %, fuerza privada 16,05 %,
autenticación 12,49 %. Estados solapados, no partición causal; esta mezcla no estaba
dominada por el mismo atasco largo de cuerpo observado antes. Evidencia externa:
`active-profile-trial/flow-analysis.json`, walls/recursos y `profile-observer/summary.json`.

Siguiente control de observación: **misma C158 desde 564.690**, una captura de 20 s
a 19 Hz en el primer sample fresco posterior a **60 s visibles**, independiente de
tasa/head/body/ready. Se registra la fase real en lugar de seleccionar solo un
atasco de red. Configuración y cleanup de sesión propia idénticos a los calibrados;
los máximos 1.200 s/6 GiB y pausa a 120 s del intento, cualquiera que sea su resultado,
se conservan. Un fin temprano no será un nuevo benchmark de veinte minutos.

`fixed-profile-controller-calibration.json` verifica sintaxis, seis casos de la
nueva selección temporal, consentimiento/checkpoint previo y AST idéntico de los
guards de ownership; reutiliza las cinco pruebas anteriores sin repetirlas. Solo
cambia duración respecto a la calibración de CPU, no fuentes ni permisos. Aún no
hay una causa demostrada ni un cambio de estrategia seleccionado.

### Perfil a tiempo fijo: coste del consumidor y corrección seleccionada

El control terminó 27-Oct 09:26:11 en pausa, sin servicios/HTTP/workers activos.
Checkpoint **564.690 → 573.476**, prefix/CRC y retención válidos: **8.786 objetos**,
489.393.133 bytes encoded; pequeños/intermedios/grandes 7.275/1.498/13, todos range.
Raw 18 claves/487.571 bytes → 14/3.417.331, ninguna inicial perdida. Ventana visible
**211,231 s, 2,053163 MB/s** (solo redondeo 2,052667–2,053660); final prefijado tras
el intento de perfilado, **no** un nuevo benchmark de veinte minutos ni ganancia de código.

Perfetto escribió normalmente su traza de **4.463.916 bytes**. El controlador falló
después al asumir que `adb exec-out cat /proc/PID/stat` rc=0 significaba un stat válido.
Se reprodujo solo esa lectura: exec-out devolvía rc=0 con error de fichero ausente;
`adb shell` y la ausencia de `/proc/PID` confirmaron que el consumidor ya había salido.
Se conserva el fallo y se recupera la **misma traza**, sin nuevo perfil, señales,
Resume ni descarga; limpieza de sus propios archivos completada.

Traza SHA-256 `7dc785ac3b7a20bc99241e5de63edd7c91f1d0c3b824990464555097a191bcd8`:
19,986527 s, 686 muestras del proceso objetivo; 13 unwinds parciales declarados,
sin error/pérdida no-cero en stats. CPU de un core: prefijo 52,60 %, GC 35,98 %,
writer 22,64 %. Son hilos concurrentes, no una partición de pared. El writer pasa
6,748 s en D y 0,675 s en runnable R/R+; los nombres de pool no identifican qué
callback está activo. No se deduce una causa única de red ni se justifica cambiar
prioridades o workers. Los frames de prefijo muestran inflate/MD5 legítimos, no
una razón para quitar comprobaciones.

Clock snapshots de la propia traza sitúan el intervalo real en los logs del
dispositivo; dos offsets REALTIME coinciden a 77 ns, sin prueba de pasos posteriores.
Dentro: 19,019 s muestreados, head 15,78 %, reservas a 34 el 94,74 % y a 34
**sin** head el 78,96 %, no aditivos. Los contadores que encierran la traza abarcan
21,593 s/896 objetos: **7,052 s en retiro de raws**, con bordes censurados.
En la ejecución completa, ese retiro cuesta **53,575 s** frente a 242,938 s después
de la primera fuente. `appendAndForceMs` incluye su propio `groupForceNanos`:
no se suman padre e hijo. El código de raw retirement añade por objeto unlink,
force del directorio privado y comprobación de hermano `.part`.

**Cambio mínimo a verificar:** mantener cada validación/unlink posterior a ACK,
pero sincronizar juntos los unlinks del grupo retirado en el handoff ya existente,
**antes de liberar reservas**. No cambia force de Data/checkpoint, máximo durable
16, orden físico, EKey/BLTE/CKey, identidad/formato, cuotas ni guardas. El último
grupo parcial en pausa/error se fuerza tras unir workers, sin abrir capacidad a
nuevos envíos; un fallo no se convierte en éxito. El tiempo movido al handoff
seguirá incluido en el reloj raw y se añadirán llamadas/tiempo de esa barrera.
Se requiere RED→GREEN focal, fallo/reanudación y revisión sensible antes de una
APK; después evidencia física mixta, sin atribuir diferencias de cohortes al código.

Evidencia externa `download-transport-diagnosis-20261027/`: `fixed-profile-trial/flow-analysis.json`,
`fixed-cpu-recovery/summary.json`, `recovered-fixed-cpu-analysis/{analysis,wall-correlation}.json`
y SQL/CSV/logs. No se repiten capturas o consultas ya verdes; direct y cola grande
siguen pendientes de validación.

### Barrera privada: reproducción y verificación local

Se conserva cada unlink tras ACK y sus reservas; el force del directorio privado
se realiza una vez en el handoff del grupo y solo después se libera capacidad.
En cancelación se fuerza el parcial tras unir workers. Un force fallido no se
reintenta ocultamente; errores de limpieza se conservan y un error de almacenamiento
no se presenta como una pausa normal. El contador raw incluye la barrera movida;
dos agregados nuevos miden sus intentos y nanosegundos como subcoste, no adicional
al total raw. No se modifica ForeverCasc, BLTE, Data/checkpoint ni cuotas.

**RED:** una prueba, un fallo esperado «one directory barrier covers more than one
retired raw», sin errores/skips; Gradle 4 min 13 s, JUnit 10:21:32 UTC. Hook nulo en
producción de referencia, sin cambiar todavía su force por objeto.
**GREEN:** mismo archivo de tests, nueve checks focales PASS, cero fallos/errores/skips;
Gradle 1 min 19 s, JUnit updater 10:29:08 y CASC 10:29:18 UTC.
Presión/regrouping, fallo de barrera sin admitir el siguiente raw ni retry,
reanudación sin volver a descargar la primera fuente durable, cancelación de un
parcial de dos objetos con una barrera, joins/cancelación, orden físico concurrente,
dieciséis parciales/reanudación, sampler y barrera durable CASC de dieciséis.

Hashes probados idénticos al repositorio:
updater `722f3e7ec1e41aa75c97c4eda2d89b5a8e55cb7cce510d9021676aa2f2ad92f3`;
test `e2c4e8e1f790f28cb58a87b3771213a575363e37c059958b6977c7d143b2be4d`.
La revisión independiente recibió el snapshot real del flujo y después los nueve
GREEN/hashes; sin defectos accionables, **sin acceso a archivos ni reruns**.
Evidencia `download-transport-diagnosis-20261027/private-retirement-focused-tests/`
(inputs, XML nuevos, logs y resúmenes). No se repite la suite ni los verdes previos.
Estas pruebas validan el protocolo local, **no** velocidad física.

### C159 autenticada; ensayo mixto prefijado

Fuente `d9b976e` (cambio `5b5bb49`), versión `159`/
`0.2.135-grouped-raw-retirement-test`. Build Modern offline JDK17 **PASS 5 min 13 s**,
terminado 27-10 10:50:45 UTC. Auditoría exacta **PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT**:
2.226 fuentes matching con receipt y 178 payloads; **2.220** idénticas a C152 y
**2.222** a C158. Frente a C158 cambian solo updater/test/versión/marker; transfer,
su test, inputs, lineage, privacidad, payloads y auditor permanecen intactos. Las
fuentes generadas coinciden con los hashes GREEN indicados arriba.

APK **484.953.483 bytes**, SHA-256
`617d5a24581414d94ce33a44024790d77bf226cf9448bc7103c87befe900266f`.
Receipts, source-comparison, build/audit y APK externa en
`download-grouped-raw-retirement-c159-20261027/`; no se repiten build/auditoría/tests.

Ensayo nuevo sobre el cliente real desde checkpoint **573.476**: reemplazo `-r`
preservador, preparación sin confirmar payload y un único Download tras inspeccionar
el mismo cliente/USB y los costes dentro del consentimiento previo. Los controles
se revisaron completos y pasaron sintaxis/AST antes de operar. Se fijan **1.200 s
visibles o señal de pausa a 6 GiB lógicos**, pausa cooperativa y verificación final
de continuidad/retención/cero trabajo activo; no profiling ni GET auxiliares.

Predicción local: menos barreras privadas por raw retirado y menor coste raw
inclusivo, sin liberar capacidad antes de force ni perder durabilidad. Se informarán
ambos contadores nuevos, composición/tamaños/origen, fases y límites; no se sumará
el subcoste de force otra vez al total raw. La evolución de MB/s en objetos
secuenciales distintos **no** se usará por sí sola como ganancia causal. La
comparación mixta equivalente y la cobertura pendiente de direct/cola grande no
se dan por satisfechas al compilar, instalar o superar las regresiones locales.

## Matriz de aceptación del objetivo

El usuario autorizó «relancemos el goal otras 6 horas». La nueva planificación
conserva íntegros los cinco criterios y todo el trabajo verificado, sin marcar el
anterior como completado ni repetir los ensayos. Rama/artefactos comprobados al
retomar: `42ae0bd` limpio; último checkpoint probado 503.039 y controles terminados.

Lectura de capacidades del dispositivo 27-10 06:26:00–06:26:02 UTC: instalada C158,
cero servicios updater antes/después y sin guest; ningún click, GET, tracing o ajuste.
Perfetto v34 registra `linux.ftrace`, `linux.perf` y `linux.process_stats`; atrace
anuncia sched/network/disk y `am help` profiling por muestreo. El fichero de eventos
ftrace no es legible desde shell: no se cambian permisos ni se asume que un productor
registrado garantice una captura utilizable. Evidencia privada externa:
`download-transport-diagnosis-20261027/profiler-capabilities/`. Se evaluará captura
acotada del proceso real, sin heap dumps/payloads ni otro control de fuente ya medido;
al corte de esa lectura todavía no había profiling ni otra APK/corrección de transporte.

| Criterio | Evidencia y estado |
|---|---|
| Línea base con unidades, ambos tamaños y preparación separada | C152: 609,206 s; UI, frames y raw balance distinguidos. Comprobado. |
| Diagnóstico sin sumar relojes concurrentes | C158 distingue espera de fuentes y consumidor; perfil real correlacionado cuantifica retiro raw como coste secuencial relevante. Seleccionada barrera privada agrupada, sin causa única general de red demostrada. |
| Mejora medida y evaluación por tamaño | C156/C157 medidas, pero solo 151/5 grandes y tamaños distintos. +16,90 % C155 y −47,57 % C157 observacionales no validan mejora general. **[PENDING]** comparación representativa. |
| Garantías, pruebas, revisión sensible y APK autenticada | Verificaciones anteriores preservadas. Cambio C159: nueve checks focales, tres de versión y revisión snapshot sensible; nueva APK exacta auditada. **[PENDING]** continuidad física de C159. |
| Informe, pausa final, progreso y commits propios | Control del 27-10 terminó 09:26:11 en pausa, checkpoint 573.476 válido, cero trabajo activo y raws retenidos. **[PENDING]** mejora demostrada y objetivo completo antes de archivar. Sin publicación/push. |

## Pendiente

Diseñar un diagnóstico discriminante y una validación representativa antes de otro
cambio de estrategia. C153/C154/C155/C156/C157 terminaron pausadas; no se repiten
sus ensayos ni sus checks verdes.
El goal **no** cumple todavía la mejora exigida.
La línea base mide variación actual, pero no demuestra una causa única de todos
los descensos históricos ni mejora aislada por tamaño; el uplift agregado de C155
no elimina esa carencia.
