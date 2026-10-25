# Descarga: observación pasiva del log — 2026-10-25

## Autorización y procedencia

Solo lectura pasiva de evidencia/logs para pasos3/4 del
[plan](../.agents/planning/download-resume-simplification-plan.md), mantenido en
`draft`. No se ha pausado/reanudado la descarga, interactuado con la app, instalado,
reiniciado, borrado ni modificado código/contratos/garantías. No se pide permiso
adicional para operar el móvil; si falta evidencia, se documenta y se detiene.

Estado inicial del checkout: `59bcd04`, rama `perf/download-bottlenecks`, con dos
archivos Kotlin previamente modificados (`ForeverClientManagement.kt` y
`ForeverClientUpdateForegroundServiceTest.kt`). No son cambios de esta observación
y se conservan intactos. El plan estaba `in-progress` por trabajo posterior al
informe inicial; se restaura `draft` según la instrucción actual sin eliminar
evidencia/antecedentes de ese trabajo.

Se enumeró la conexión ADB ya existente, sin conectar ni reconectar. El ejecutable
no estaba en PATH; se utilizó el SDK local existente. Única lectura en dispositivo:
`adb -s <dispositivo-conectado> logcat -d -v epoch 'ForeverClientUpdate:I' '*:S'`.
No `logcat -c`, sondas, shell de app, capturas de pantalla, UI ni acceso a datos del
cliente. El snapshot filtrado contiene **1.190.173 bytes / 1.162 líneas**, guardado
fuera de Git con permisos restringidos; no se publica su contenido crudo ni claves,
direcciones de red o identificadores privados.

Evidencia local: `updater.log` y `summary.json` en el directorio externo
`/home/adrian/.hermes/cache/scratch/opencode/download-passive-20261025/`.
SHA-256 del log:
`386426d71d35c4f8da1bfa172d985a03d6bd08371514bcc94dfda1f4f9d927b6`.

## Método y límites

Se extrajeron campos numéricos de `CASC commit batch` y `CASC fetch pipeline`.
Las **189 muestras writer** pertenecen al mismo PID y los contadores utilizados
son monótonos, sin reinicio observado. Eso no identifica por sí solo APK/build,
catálogo, hardware, condiciones de caché ni versión de código instalada; no se
consultaron fuera del log. No atribuir esta ejecución a la fuente local modificada
ni a una mejora concreta.

Las duraciones son diferencias de `elapsedMs` del writer, contrastadas con epoch
del log (coinciden a milisegundos en los intervalos usados). Se resta cada contador
cumulativo entre extremos. Para aproximar diez minutos se elige la muestra más
cercana a `elapsedMs_final - 600000`; resultan **594,835 s**, no 600 s exactos.
Cinco límites interiores se aproximan a quintiles temporales con las muestras
disponibles; no son cinco ensayos independientes.

`newObjects` es contador writer de objetos nuevos, no una auditoría independiente
de los marcos durables. `newBytes` son bytes CASC escritos: **no** bytes útiles de
red. No se calcula throughput de descarga ni ETA a partir de ese contador.
`sourceResolveMs` incluye lookup/revalidación aplicable y espera de future; no es
latencia CDN pura. Private verification incluye copia y autenticación. Force es
un hijo de append y no se suma a este. Relojes HTTP/productor son concurrentes.
Las sumas en ms truncadas por operación no son particiones exactas.

Referencias de semántica en fuente actual: `ForeverCasc.kt`, comentarios de clocks
1392–1401 y emisión1892; `ForeverClientUpdater.kt`, espera/diagnóstico2214–2255.
Son referencias explicativas del formato, no prueba de binding del APK observado.

## Resultados actuales del snapshot

| Ámbito writer | Intervalo retenido completo | Ventana ≈10 min |
|---|---:|---:|
| Contador inicial → final de objetos nuevos | 39.424 → 87.552 | 74.752 → 87.552 |
| Duración | 2.321,615 s | 594,835 s |
| Objetos nuevos escritos | 48.128 | 12.800 |
| Bytes CASC nuevos (no red) | 3.812.665.534 | 795.651.173 |
| Source resolution | 1.636,023 s / 70,47% | 434,191 s / **72,99%** |
| Copia/verificación privada | 175,449 s / 7,56% | 43,116 s / 7,25% |
| Append/force | 250,000 s / 10,77% | 49,256 s / **8,28%** |
| Checkpoint | 16,943 s / 0,73% | 4,475 s / 0,75% |
| Group-force helper (ya incluido en append) | 237,233 s | 45,911 s |

La parte fuera de esos cuatro relojes no se atribuye automáticamente a USB,
CPU, crypto ni checks. No es evidencia de instalación completa ni de ausencia de
errores fuera del tag/intervalo retenidos. No se detuvo la ejecución para cerrar
una muestra, ni se afirma que sus callbacks finales estén drenados.

| Subventana | Duración | Objetos writer nuevos | Source resolution / intervalo |
|---|---:|---:|---:|
| 1 | 113,584 s | 2.560 | 71,40% |
| 2 | 129,020 s | 3.072 | 72,24% |
| 3 | 110,398 s | 1.792 | 72,47% |
| 4 | 126,917 s | 2.816 | 72,95% |
| 5 | 114,916 s | 2.560 | 75,96% |

Pipeline muestreado en los mismos contadores 74.752 y 87.552:

- Espera de futuros: **434,585 s** acumulados; está dentro de source resolution,
  no es una fase adicional. La ligera diferencia con 434,191 s refleja ámbitos de
  muestreo/instrumentación y truncado; no forzar igualdad ni inferir overhead negativo.
- Productor esperando presupuesto: **361,457 s**, concurrentes con writer/fetches.
- Executor queue wait: **20,180 s** agregados entre callbacks; no tiempo serial.
- 861 solicitudes de cabeza pendiente, 11.939 listas y 403 muestras ready-ahead
  vacío. No significan 403 paradas independientes ni aísla cada contador su causa.
- Validación/retiro de raws: **29,835 s**, sin confundirlo con fuerza CASC.

Doce agregados de storage-light muestran cero fallos registrados. Entre el primero
y el último: 2.816 llamadas adicionales y 7,847 s, ≈2,79 ms/llamada. Ese intervalo
no es la ventana writer de 594,835 s; no combinar denominadores. Ausencia de logs
full no demuestra ausencia de validaciones full ni redundancia de esas guardas.
La [matriz de garantías](download-resume-baseline-review.md#qué-guarda-cada-comprobación)
sigue siendo aplicable; no se propone suprimir ninguna comprobación.

## Hallazgo prioritario y bloqueo de la línea base de inicio

**Durante el tramo de transferencia observado**, el cuello de espera prioritario
es disponibilidad de fuentes: domina las cinco subventanas y el intervalo amplio.
La espera de futuros respalda esa atribución de etapa, **no** una causa raíz entre
CDN, tamaño/cohorte de objetos, planificación de batches y backpressure. El helper
de fuerza no ocupa la mayor parte del writer en este snapshot. Ni esos porcentajes
ni las esperas concurrentes autorizan más workers/cuotas, ready-first o cambios de
cadencia/garantías.

**Para intención → transferencia inicial, no hay línea base actual:** este buffer
no retiene inicio de foreground, preparación/calificación, inspección del destino,
confirmación, primera petición de fuente ni pausa/reanudación. Ya empieza con
decenas de miles de objetos escritos. No se pueden reconstruir acciones del usuario
ni tiempo de preparación restando clocks de este tramo. No se convierte la evidencia
histórica de 164–225 s por inspección en una nueva medición del inicio.

Obtener esas marcas ahora exigiría otra interacción/ejecución de app y está fuera
del permiso vigente. **Se documenta el bloqueo y se detiene la observación**, sin
solicitar autorización adicional ni iniciar/pausear/reanudar nada para generarlas.
No hay before/after de una mejora nueva ni aceptación global; paso3 y goal siguen
incompletos, plan `draft`. El siguiente trabajo analítico posible es correlacionar
capturas ya existentes que contengan esas marcas, si llegan a estar disponibles;
no sustituir su ausencia por pruebas de UI, código o medidas sintéticas.
