# Velocidad de transferencia: diagnóstico pequeño/grande

Fecha: 2026-10-26. Goal `5caa6403-5b8f-47dc-ba95-ba40fbbe686e`.
**En curso; todavía no hay una optimización ni una mejora demostrada.**

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

## Pendiente

Resultado de la ventana C152, representación real pequeña/grande, diagnóstico,
intervención mínima, pruebas/revisión, candidata autenticada y comparación física.
No se declara throughput, causalidad ni cierre del goal a partir del preflight.
