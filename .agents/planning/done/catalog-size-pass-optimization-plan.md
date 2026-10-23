---
id: catalog-size-pass-optimization-2026-10-18
state: done
date: 2026-10-18
---

## Summary

El usuario pide seguir buscando cuellos y trabajo duplicado, preservando
integridad tras cierres inesperados y actualizaciones parciales sin descargar
todo otra vez. Siguiente candidato acotado: evitar materializar1.144.978registros
completos solo para obtener el mayor tamaño encoded al preparar un plan.
El usuario aprobó ambos trabajos (catálogo ydiagnóstico de espera) con
«perfecto hagámoslo». C104ya cerró la medición;no se cambia su APK retenida.
La observación del fetch será solo diagnóstico de baja frecuencia,sin cambiar
cliente HTTP,URLs,rangos,reintentos,hashes ni durabilidad.

## Content

### Evidencia y alcance del beneficio

- `ForeverClientUpdater.buildPlan`357-365abre `catalog.withObjects`,convierte tres
  claves por fila yvalida tamaños para calcular únicamente max(encodedSize).
- El catálogo ya valida todas las filas al abrirse,yel scan principal posterior
  vuelve a leerlas/verificarlas para construir el plan. Ese scan se conserva.
- Comparación local sobre la copia READONLY del catálogo real70450:agregado
  COUNT/MIN/MAX de tamaños0,236s;streamPython con checks2,590s,mismos resultados:
  1.144.978filas,encoded47..141.273.036,decoded2..141.574.945bytes.
  **Son tiempos del host Python/SQLite,no Android/Kotlin ni una mejora prometida.**
- Este ahorro afectaría preparación/reanudación,no directamente los MiB/s de la
  descarga de nuevos objetos. C104ya mide por separado comprobación USB yespera
  del objeto verificado;no atribuir sus resultados a esta propuesta no aplicada.

### Cambio mínimo propuesto

1. En `ForeverClientUpdateCatalog`,consulta numérica fresca READONLY de una fila:
   COUNT,MIN/MAX encoded yMIN/MAX decoded,sin convertir keys/sourcehash/URLs ni
   materializar el millón de objetos. Validar no-null,COUNT contra summary
   admitida,positividad ytodos los límites actuales. No usar un máximo cacheado
   de otra sesión ni confiar únicamente en metadata declarada del catálogo.
2. `Updater.buildPlan` usa los máximos para las mismas reservas/preflight,en lugar
   del primer cursor de registros. Si los límites Catalog/Updater difieren algún
   día,ambos deben seguir aplicándose;no inferir equivalencia permanente.
3. Mantener validación SHA/tamaño/schema/filas al abrir catálogo yTODOel scan
   principal,los checks por registro,los objetos reutilizables yel re-plan dentro
   de execute. No quitar gates de TOCTOU/consentimiento ni adelantar activación.
4. Mantener exactamente planId/snapshot/costes para el mismo catálogo/destino;
   ningún cambio de formatos persistidos,fuentes admitidas,descarga,hashes,fsync,
   checkpoint,leases,identidad de volumen ni reglas de actualización por delta.
5. Cancelación antes/después de la consulta nativa readonly acotada. No threads
   auxiliares/framework de cancelación nuevo. Si la consulta tarda demasiado en
   Android o cambia alguna garantía,parar para reconsiderarlo antes de entregar.

### Aceptación y seguridad

- Agregado coincide con stream de tamaños sobre catálogo válido,con memoria
  constante;plan/coste no cambia. Probar con infraestructura pequeña existente.
- Cuenta/cotas encoded/decoded inválidas o cambiado el número de filas fallan
  cerrado. Las comprobaciones completas de claves/source binding se conservan
  en apertura yscan principal antes de cualquier escritura de cliente.
- Tests puntuales de plan readonly,coste ycancel/resume/reuso serial relevantes;
  corrupción no se acepta,sin borrar datos ajenos ni descargar de nuevo por error.
- Revisión independiente de equivalencia de validaciones yorden de mutaciones.
  Fuentes inmutables code59/apply_overlay yartefactos de releases retenidos intactos.
- Medir duración real de la preparación en Android antes/después;no presentar un
  aumento del throughput global como consecuencia de ahorrar un paso de planificación.

### No objetivos

- No fusionar los lectores de checkpoint,omitir replay ni conservar flags
  «ya verificado» entre sesiones. No saltar el re-plan tras confirmar.
- No cambiar capacidad de índices,ventana/conexiones,HTTP/pool/mirrors,hashes ni
  durabilidad. No resolver Transaction no-op ni tocar cliente70009/WTF/wowserver.
- No usar el agregado como sustituto de la validación completa del catálogo.

## Implementation / Action Items

- [x] 1. Aprobación del plan;cerrar primero las medidas de c104 sin cambiar su APK.
  C104:replay52.789objetos en572,446s. Foreground600,801s:302.594.755bytes /
  4.121objetos,0,48032MiB/s;fondo120,295s:66.895.446bytes/767objetos,
  0,53033MiB/s,FGSy16workers en todas muestras,RAM192,4–199,5MiB. Fuente1.024:
  revalidate42,44ms/obj frente72,77c103(-41,7%),espera verificada48,21ms/obj.
  Resumen `work/c104-single-snapshot-20261018/c104-measurement-summary.json`.
- [x] 2. Implementar agregado fresco yconsumo de máximos con las mismas cotas.
  Catalog+Updater:una fila fresca count/minmax de tamaños,rechazo de null/tipo
  agregado/count/cotas,caller aplica también sus máximos. Validación completa al
  abrir,en scan principal yre-plan intacta;Summary/formatos de estado intactos.
  Producción congelada:Catalogeb330f17...,Updater9a8e251c....
- [x] 3. Pruebas enfocadas de igualdad de plan/coste yrechazo de corrupción.
  Primera corrida20tests(13Catalog+7Updater),0fallos/0skips. Foco posterior2tests
  Updater(unoextendido,uno nuevo):costes/snapshot estables yRANGE crecido tras
  admisión rechazado antes de crear scratch/cliente,0fetch.2/0fallos/0skips,XML
  19:12posterior a clases/fuentes;sync repo/clon exacta.
  Transferdiagnóstico:9regresiones+1nuevo muestreado con MD5terminado00,
  ambos verdes/0skips;handler fallido no aborta publicación,caché/headers/drenado/
  force/cancel intactos. No se repitieron suites exitosas sin motivo.
- [x] 4. Revisión,build/auditoría e instalación sin perder checkpoint/recibo.
  Revisión de catálogo,Transfer y2testsUpdater finales aprobada,sin bloqueos.
  C105105/0.2.81-catalog-query-fetch-timings compilada3m21s y auditada
  PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT. Instalada tras pausa controlada con0workers;
  checkpoint/recibo byte-idénticos antes/después,71.749objetos/5.668.212.326bytes
  encoded conservados yobjetoE original presente. SHA256APK
  `4b9cc52410ce7de6b59ad9fbe5070dd7729a49e462b7fa7ff9bc13a2ddfa16c1`.
  Fuentes prod candidatas coinciden repo;retenciónC104 intacta.
- [x] 5. Medir preparación real yconfirmar reanudación/reuso;documentar alcance.
  Preparación,coste,replay,10min nuevos commits y2min fondo encadenados por evento.
  Muestras fetch exitosas se resumen después de la ventana:tiempos por objeto,
  no sumas de CPU ni walltime de16workers. Cachehit/attempts/bytes(wholeencoded)
  desambiguan reanudaciones;no calcular throughput de red con esosbytes.
  Completado:query fresco166/208ms en Android,mismos1.144.978objetos/maxEncoded
  141.273.036/maxDecoded141.574.945. No había timer equivalente del viejo barrido
  en app:no presentar el contraste host como speedup Android de la preparación.
  Replay71.749objetos en772,924s,seguido de nuevos commits;objetoE conservado.
  Foreground599,724s:389.836.395bytes/5.301objetos,**0,61991MiB/s**;background
  120,659s:58.325.721bytes/743objetos,**0,46100MiB/s**. RSS174–188,8MiB,16workers
  yFGS en todas las muestras;resultado `REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED`.
  Resumen ydatos en `work/c105-catalog-fetch-20261018/c105-{physical-result,measurement-summary}.json`.
  Fetch20muestras exitosas:media289,25ms,total5.785ms muestreados,headers4.541ms
  (~78,5%,mediana77ms/media227,05ms/pico1664ms),body685ms,force231ms,verify61ms,
  progress23ms,publish108ms,write6ms. No son sumas de walltime ni red pura.

## Open Issues

- El ahorro exacto del primer barrido en Android no se ha aislado aún. La consulta
  sigue leyendo la tabla internamente,pero evita construir/convertir cada registro.
- No es prioritario frente a un fetch lento durante descarga:la espera de future
  es otro coste ymezcla red/verificación/sincronización privada. Investigar sin
  cambiar el pool a ciegas ni atribuir todo aTLS.
- C104:primeros1.024objetos,revalidate43,455s(42,44ms/obj)vs74,516s(72,77ms/obj)
  c103,aprox42% menos en esa fase;ventanas completas cerradas yconsignadas arriba.
- Diagnóstico paralelo del objeto:tiempos de respuesta HTTP(que incluyen DNS/TCP/
  TLS/TTFB),lectura del cuerpo,escritura privada,force,verificación/publicación y
  callback de progreso si ayuda a separar el coste. Solo muestra dispersa acotada,
  no log por objeto ni framework/config nueva. Etiquetas no deben llamar«red pura»
  al tiempo que incluya disco,descifrado TLS,planificación o callback. Sin cambios
  funcionales de transferencia;se documentará si no hay evidencia suficiente.
- Trabajo posterior solo lectura:30snapshots muestran co-existencia de writer
  en futex,15outputs.blte listos y14–15workers en futex mientras1worker hacepoll.
  Es compatible con bloqueo de cabeza por ventana ordenada,no prueba la duración
  total del fenómeno ni una causa única. No ampliar cola/cotas sin revisar
  recuperación ypresupuesto ante crashentre checkpoint yborrado de raw cache.
- Prueba externa acotada de reutilización HTTP en proceso aislado pendiente;
  no cambiar conexiones/pool por el valor estático5idle ni por solo mirar latencia.
