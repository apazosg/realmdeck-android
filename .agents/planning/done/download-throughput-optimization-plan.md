---
id: download-throughput-optimization-2026-10-18
state: done
date: 2026-10-18
---

## Summary

El usuario pide optimizar más la descarga y comparar con `wowserver`, donde
recuerda unas7h. C100 ya resolvió el temporal de verificación en USB:18.362objetos
revalidados en179,659s; descarga nueva y fondo comprobados. La tasa de nuevos
commits sigue~0,3–0,6MiB/s. Este plan propone una primera optimización acotada de
concurrencia, acompañada de tiempos de fetch/commit, SIN cambiar durabilidad.
Aprobado por el usuario: «vale. debemos tener en cuenta también las
actualizaciones. probemos primero como dices». Se mantiene explícitamente la
reutilización de bloques y el fallback serial de actualizaciones existentes;
no se declara resuelto el bug previo Transaction de reaplicar bytes idénticos.

## Content

### Comparación acreditada

| Aspecto | wowserver PC | Android c100 |
|---|---|---|
| Concurrencia |16 global/host/in-flight |4workers,cola6,presupuesto1GiB|
| HTTP |httpx.AsyncClient compartido,1Range/objeto |HttpsURLConnection con cuerpo drenado/pool,1Range/objeto|
| Orden |prioridad de manifiesto |ekey (exigido por checkpoint actual)|
| Escritura |directa a CASC |part privado,verificación,temporal privado,append CASC|
| Durabilidad |checkpoint5000objetos/300s;sin fsync por objeto |force/dir-force por objeto|
| CDN |mirrors oficiales resueltos por Ribbit |host fijo us.cdn.blizzard.com|
| Memoria |índices10–15GiB,reinicios por OOM |streaming acotado;no importar estructuras PC|

Fuentes PC solo lectura:/home/adrian/dev/wowserver/README.md,
`tool/forever_client/{bounded_runner,bounded,durability,progress}.py`,
`systemd/wowserver-client-provision.service`,logs/state de /opt/wowserver.
El tramo final4oct14:07:55→15:15:49procesó518.353objetos/16.476.264.367bytes en
~68min (~4MiB/s,~127objetos/s). No hay una única pasada registrada de7h: hubo
tandas/reinicios. El recuerdo de7h es compatible con el orden de magnitud del
tiempo activo total para~84,7GB más preparación/verificación; no es un benchmark
directamente equivalente a70450ARM64/85GiB.

La diferencia16vs4 es real, pero **no demuestra una mejora lineal4x**. Tampoco
justifica copiar la memoria ni reducir durabilidad del PC. Ambas implementaciones
hacen un Range por objeto: no hay evidencia de agrupación de rangos en el PC.

### Medidas Android adicionales

- Muestra nueva de80instantes:writer espera futex41/80;4workers185/320muestras
  en poll y116/320en futex. Compatible con espera a fetch;no identifica por sí sola
  latencia HTTP,verificación privada o cola. No atribuir todo a fsync/TLS.
- Misma sonda Java HttpsURLConnection de8rangos reales/437.402bytes:PC1,560s,
  Android3,186s contra us.cdn;sin FS de payloads,cuerpo drenado,validación TLS normal.
  Level3~20s en ambos: no cambiar el host por tener ping bajo.
- Línea móvil medida OVH10MiB/0,733s≈114,5Mbps. No equivale al throughput de cada
  pequeño Range contra Blizzard. Artefactos `work/c100-private-temp-20261018/`.

### Decisiones propuestas / alcance mínimo

1. Pasar de4a**8workers** inicialmente; no saltar directamente a16en Android.
   Conservar la cota dura1GiB de archivos en vuelo y el mismo algoritmo de commit
   ordenado. Comprobar que la cola6permite8fetches reales y que su máximo de
   archivos sigue dentro de la cota de recuperación; no ampliar límites a ciegas.
2. Añadir tiempos agregados de espera de fuente/fetch frente a verificación y
   commit CASC, emitidos por lotes acotados (no log por objeto). Sin framework
   de métricas, configuración nueva ni dependencia HTTP.
3. Mantener TODOS los hashes,tamaños,fsync/checkpoint por objeto,revalidate,
   consentimiento y recuperación; no modificar formato de journal/checkpoint ni
   orden del catálogo. Mantener fallback serial al actualizar una fuente existente.
4. Observar memoria,workers en vuelo,obj/s yMiB/s reales tras reconstruir/auditar
   e instalar sin borrar datos. Comparar con ventana larga c100, aclarando que las
   muestras avanzan por objetos distintos y no son un benchmark idéntico.
5. Si8no mejora o aumenta memoria/errores, no escalar a16ni debilitar garantías.
   Decidir la siguiente acción a partir del reparto de tiempos y presentar otro
   plan solo si cambia durabilidad,host/autoridad o arquitectura de almacenamiento.

### No objetivos

- No copiar checkpoint5000/300s: cambia la ventana de pérdida de trabajo y exige
  contrato/revisión propios. No omitir verificación para ganar velocidad.
- No memorizar índices completos del PC,reiniciar para tratar OOM como normal,
  añadir mirrors arbitrarios,bypass TLS ni rangos sintéticos.
- No desarrollar instalación mínima/streaming jugable ni resolver Transaction
  no-op en esta operación. No tocar cliente70009,WTF ni producción wowserver.

## Implementation / Action Items

- [x] 1. Aprobar plan y registrar ventana larga de tasa/memoria c100.
  C100:185.490.279bytes y2.287objetos nuevos comprometidos en600,445s:
  **0,29461MiB/s /3,809objetos/s**; FGS y objeto cifrado conservados en todas
  las muestras. Proyección entonces80,4h, no ETA garantizada. PID28819 a17:36:
  RSS192.996KiB (~188MiB),swap25.016KiB,53threads. Artefactos
  `work/c100-private-temp-20261018/c100-ten-minute-{throughput,summary}.json` y
  `work/c101-throughput-20261018/before-c101-memory.txt`.
- [x] 2. Confirmar máximo de tareas/archivos/memoria por8workers y conservar cotas.
  Revisión independiente:8máximo(pending1+cola6+productor1),cota de recuperación10
  intacta,presupuesto1GiB intacto. BLTE streaming64KiB y lista8192chunks acotada;
  no se reserva decoded completo. Métrica de RAM real c101 pendiente.
- [x] 3. Cambio cohesionado8workers+tiempos agregados,tests puntuales de solape,
  límite,cancelación y reanudación con infraestructura actual;no suites rutinarias.
  Solape real8workers por latch determinista y commit ordenado acreditados. Run3
  1test/0skips;run4cancelación-resume,reuso serial y3Casc=5tests/0skips.6/6verdes;
  fuentes repo/clon/candidato idénticas,no cambios funcionales tras las pruebas.
- [x] 4. Revisión independiente de concurrencia/recuperación;actualizar pins y
  versión,reconstruir,compilar y auditar sin tocar recibos congelados anteriores.
  Revisión de producción y test de solape aprobada sin bloqueos. El fallo de
  conteo9vs8 era la expectativa de claves distintas:8CASC+1loose=9llamadas/8claves;
  prueba corregida sin cambiar producción,run3verde(1test/0skips). Cancelación/reuso
  yCasc restantes verdes;compilación3m27s,auditoríaPASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT.
  Fuentes congeladas para
  build:Updater07048cad...,Casc3bf8b69f...,CascTestinalterado2cf99617....
  Instalada101/0.2.77-eight-fetch-workers;SHA256APK
  `d234b4e9b9344da53f3513c7b76a92093c68e872ff3d89e2fbe396a75e43536f`.
  Checkpoint/recibo byte-idénticos antes/después;fuentes entregadas coinciden repo.
- [x] 5. Pausa controlada,instalación sin pérdida,validar replay,descarga y fondo;
  medir tasa/objetos/memoria y documentar ganancia o ausencia de ella.
  C100 pausada por UI a17:58 con727,1MiB de sesión;27.816objetos /
  2.194.565.341bytes encoded comprometidos,frameE conservado y0workersfetch tras
  pausa. Baseline guardado en `work/c101-throughput-20261018/`,incluidos checkpoint
  y recibo antes de instalar. No iniciar ventana10min hasta nueva descarga c101.
  C101 completó el replay de27.816objetos en291,249s desde inicio del writer y
  pidió nuevos payloads a18:19:04.794. Plan83,0GiB/84,9GiB adicionales confirmado
  dentro de lo autorizado. «10min» fue duración de muestra DESPUÉSdel replay,
  no estimación del ciclo de pruebas/build/instalación.
  Foreground600,068s:404.391.891bytes/4.841objetos,**0,64269MiB/s /8,067obj/s**,
  ~2,18x la ventana larga c100. RAM187,7–205,4MiB,8workers yFGS en todas muestras.
  Background120,207s:48.784.826bytes/629objetos,**0,38704MiB/s /5,233obj/s**;
  launcherMIUI arriba,FGS conservado,8workers,RAM191,7–194,4MiB. Objeto cifrado
  original conservado,checkpoint ordenado/CRC válido,sin error del driver.
  Resultado `REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED` y resumen en
  `work/c101-throughput-20261018/c101-{physical-result,measurement-summary}.json`.

## Open Issues

- El presupuesto en disco1GiB no equivale a presupuesto de RAM;revisar buffers/
  verificación BLTE antes de aumentar hilos y observar memoria real del móvil.
- Tasa dependiente de tamaño/ubicación en CDN y ejecución foreground/background;
  no extrapolar una ventana de segundos ni prometer7h por copiar16conexiones.
- Cierre:las ventanas avanzan por objetos distintos,no A/B idéntico. Estimación
  a esas tasas:~36,5h foreground/~60,6h background,no garantía de ETA ni de
  compatibilidad del cliente. Sigue lejos de7h;no subir automáticamente a16.
- Métricas de los primeros4.864objetos nuevos:sourceResolve476.800ms (~79% de
  602.692ms),privateVerify11.415ms,appendAndForce64.689ms,checkpoint36.703ms.
  `sourceResolve` incluye revalidate y espera de future,no red pura;sumas ms
  truncan por objeto. Separar ambas partes antes de atribuir ese79% aHTTP oUSB.
- Reuso de actualizaciones protegido por pruebas puntuales y fallback serial;
  actualización entre dos instalaciones completas aún no probada físicamente.
  El bug previo Transaction al reaplicar una instalación idéntica sigue separado.
- Aprobado primer paso a8sin tocar fsync, con especial atención a actualizaciones.
  No hay otras decisiones de usuario pendientes;si requiere ampliar coste o
  garantías,se detendrá para presentarlo.
