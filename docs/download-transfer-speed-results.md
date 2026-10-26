# Velocidad de transferencia: diagnóstico pequeño/grande

Fecha: 2026-10-26. Goal `5caa6403-5b8f-47dc-ba95-ba40fbbe686e`.
**Línea base completada; candidata seleccionada, mejora todavía no demostrada.**

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

## Pendiente

Intervención mínima, pruebas/revisión, candidata autenticada y comparación física.
La línea base mide variación actual, pero no demuestra una causa única de todos
los descensos históricos, throughput aislado por tamaño ni una mejora candidata.
