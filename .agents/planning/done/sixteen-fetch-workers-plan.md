---
id: sixteen-fetch-workers-2026-10-18
state: done
date: 2026-10-18
---

## Summary

El usuario pregunta si la RAM estable permite probar16conexiones como en PC.
C101 ya midió8workers:0,64269MiB/s foreground10min,0,38704MiB/s background2min,
RSS187,7–205,4MiB yFSGen todas las muestras. Es razonable ensayar16,pero memoria
estable no garantiza mejora ni ausencia de límites delCDN,CPU oalmacenamiento.
Plan aprobado por el usuario: «vale, hagámoslo y después evaluamos lo que
comentabas tu del siguiente cuello». Primero se mide16sin otro cambio;después
se evalúa el siguiente cuello con las métricas,no se relajan garantías.

## Content

### Alcance mínimo propuesto

- Subir pool8→16 y cola6→14 para permitir16objetos realmente en vuelo
  (1consumido+14en cola+1ofrecido por productor). Subir admisión de recuperación
  10→18claves,conservando margen y compatibilidad con restos c98–c101. Comprobar
  cota de tareas durante pruebas,no limitarse a contar threads creados.
- **No aumentar el presupuesto duro1GiB** de objetos en vuelo ni el temporal
  privado mayor ya costeado. Validar recuperación/espacio con16yrestos anteriores.
  El límite de disco no equivale aRAM;BLTE sigue streaming,sin cargar el cliente.
- Mantener hashes,HTTP/TLS/rangos,fsync por objeto,checkpoint,orden de commit,
  revalidate,consentimiento y temporales privados sin cambios.
- Paralelismo solo para instalación sin fuente. Las actualizaciones con fuente
  siguen seriales y reutilizan objetos verificados;no sobredescarga por prefetch.
- Reusar métricas agregadas c101. No añadir framework,configuración,dependencias,
  otros mirrors,batching de fsync ni optimizaciones de índices en esta prueba.

### Aceptación y medida

1. Prueba determinista de16fetches solapados,commit ordenado y cota de tareas/
   archivos;cancel/resume sin workers supervivientes y admitiendo estados de16.
   Conservar el test de reuso serial y los3Casc pertinentes;no suites rutinarias.
2. Revisión independiente por concurrencia/recuperación,build y auditoría con
   baselineJSON/apply_overlay inmutables. Candidato nuevo,no sobrescribirc101.
3. Pausa controlada y checkpoint/recibo preservados al instalar. Revalidar datos
   previos,luego medir10min de nuevos commits y2min de fondo:MiB/s,obj/s,RSS/HWM,
   workers,errores/timeout ydesglose fuente/commit. Comparar con c101 sin afirmar
   A/B idéntico,porque avanza el catálogo y varían los objetos.
4. Si no mejora o empeora memoria/errores,no escalar más. Para volver a8 usar
   una entrega que conserve admisión de los restos creados por16;no reinstalar
   c101(aún limitada a10claves) a ciegas ni borrar datos de reanudación.

### No objetivos

- No prometer duplicar0,64MiB/s ni alcanzar7h. El79% `sourceResolve` observado
  mezcla revalidate+future,no es una medición de red pura.
- No cambiar garantías de persistencia ni semántica del updater;Transaction
  no-op previo y arranque/streaming jugable siguen fuera de este cambio.
- No tocar producción wowserver,cliente70009,WTF ni descargas previas.

## Implementation / Action Items

- [x] 1. Aprobar plan y guardar baseline/preservación c101 antes de cambiar.
  Ventanas c101 completadas guardadas en `work/c101-throughput-20261018/`.
  Snapshot18:37:C101 aún descargando,34.879objetos/2.768.889.652bytes,
  8workers,FGS activo,RSS200.068KiB,HWM369.140KiB. Artefacto
  `work/c102-sixteen-workers-20261018/c101-reference-snapshot.json`.
  Antes de instalar se hará pausa y se guardarán checkpoint/recibo exactos.
- [x] 2. Implementar16workers,cola14yrecuperación18manteniendo1GiB yupdate serial.
  Producción solo3constantes+comentario,Updater SHA256
  `61f5a84f779236bb13a94d6a4a3f523b99d9c079bf13afe565525e04bf3ba565`.
  Casc/Transfer/Blte/Transaction/FGS intactos. Revisión independiente sin bloqueos;
  la cota16=1+14+1y18permite estados viejos y nuevos sin ampliar1GiB.
- [x] 3. Pruebas puntuales yrevisión independiente;resolver defectos antes de APK.
  Corrida cohesiva de7tests verde(Updater4+Casc3),0fallos/0errores/0skips;
  16solapados,18admite/19rechaza,cancel16/resume,reuso serialy3Casc.
  XML16:43:57posterior a clase16:42:51;fuentesclon/repositorio/candidato idénticas.
  Revisión independiente sin bloqueos;no cambios de producción tras ese verde.
- [x] 4. Reconstrucción/build/auditoría e instalación sin perder datos.
  Candidato102/0.2.78-sixteen-fetch-workers compilado3m39s y auditado
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,instalado sin borrar datos. SHA256APK
  `88bc3701fac792c6f99ad818bb3931a95aee2c8c6dd5f5091bff15b42d66737a`.
  C101 pausada a18:42
  por UI;0workers tras pausa,36.437objetos/2.895.532.777bytes encoded conservados.
  Checkpoint SHA256 `8b5ea544b94f45de349649ba08400036440ea200fe93b85d6260dc12d342254b`;
  recibo `8b9f81c3c912382880cb841bd03948e03bb53d06cb7944f85a946612e46bb871`.
  Artefactos `work/c102-sixteen-workers-20261018/before-c102-*`.
  Checkpoint y recibo byte-idénticos después de instalar;fuentes de producción
  candidatas coinciden con las probadas y las revisadas.
- [x] 5. Medición10min+2minfondo,comparación con c101 y decisión de mantener16.
  Flujo encadenado por evento en curso:preparar→confirmar coste dentro del límite
  autorizado→replay→10minnuevos commits→2minfondo. Se notificará primera petición
  nueva;no se cuenta tiempo de preparación/replay como muestra de descarga.
  Completado:replay36.437objetos en382,879s. Foreground599,862s:
  257.586.600bytes/3.248objetos,**0,40952MiB/s**,0,637x la media c101(0,64269).
  Background119,869s:60.416.314bytes/591objetos,**0,48067MiB/s**,1,242x c101.
  RSS181,8–196,7MiB,16workers yFGS conservados,checkpoint válido yobjetoE
  original conservado,sin error del driver. No es A/B idéntico(muestras distintas).
  Resultado `REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED` yresumen en
  `work/c102-sixteen-workers-20261018/c102-{physical-result,measurement-summary}.json`.
  Decisión:16**no demuestra mejora consistente**,no escalar más. Se mantiene
  temporalmente para aislar el siguiente cuello en el mismo contexto,sin promover
  esa concurrencia como mejor ajuste definitivo ni volver aAPK101a ciegas.

## Open Issues

- La estabilidad deRAM con8no excluye picos,competencia deCPU,429/timeouts o
  saturación de la escritura con16. Mantener el cambio experimental hasta medir.
- La vuelta a8debe mantener compatibilidad con el nuevo límite de recuperación;
  no es seguro asumir que un APK viejo podrá abrir todos los estados nuevos.
- Últimas métricas de3.840objetos nuevos:sourceResolve619.599ms (~86% de720.829ms),
  privateVerify10.045ms,appendAndForce49.284ms,checkpoint30.428ms. No decidir por
  el nombre:sourceResolve mezcla comprobación USB yespera de future verificado.
- Evaluación siguiente solicitada por usuario:observabilidad acotada c103,sin
  comportamiento nuevo,separa revalidate yfuture wait enUpdatersolo cada256
  fuentes resueltas. Mantiene16/14/18,hashes,durabilidad,consent yupdates seriales.
  Muestreo ART por `am profile` no produjo datos utilizables(archivo0bytes),
  detenido/limpiado sin cambiar permisos,props de seguridad ni tocar cliente.
