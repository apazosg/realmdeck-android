---
id: storage-revalidation-single-snapshot-2026-10-18
state: done
date: 2026-10-18
---

## Summary

Tras probar16conexiones,el usuario pidió evaluar el siguiente cuello. C103solo
añadió tiempos separados,sin cambiar comportamiento. En1.024objetos reales:
**74.516ms comprobando el destino USB** y63.544ms esperando el objeto verificado.
Son72,77ms y62,05ms por objeto,respectivamente. La comprobación representa~46%
de162.062ms de recorrido nuevo,la espera~39%. No atribuir ambas a red.

`revalidateForUpdate` llama a `snapshot()` y luego a `resolveForUpdate()`,que
vuelve a llamar a `snapshot()`. Cada snapshot enumera todos los volúmenes,
inspecciona rutas/metadatos yselección. Propuesta:un único snapshot fresco por
revalidación,conservando todas las condiciones de seguridad. Por tocar identidad
de almacenamiento,requiere aprobación del plan yrevisión independiente.
El usuario aprobó:«si,hazlo» yreiteró preservar integridad ante cierres inesperados
yactualizaciones parciales sin redescargar todo. No se retiran checks ni se cambia
durabilidad;la búsqueda de otros duplicados se limita primero a investigación.

## Content

### Cambio mínimo

- Archivos funcionales: `scripts/arm64-prototype/app/overlay/ForeverFunctionalClientStorage.kt`
  ysu test. No figuran en el baseline histórico code59;se admiten como fuentes
  funcionales por `stage_functional_sources`. No tocar baselineJSON/apply_overlay
  ni inventar un reemplazo de preimagen para archivos no históricos.
- Extraer solo el cuerpo actual de resolución a un helper privado que reciba
  `StorageOption` de un snapshot recién obtenido. La entrada pública
  `resolveForUpdate()` seguirá obteniendo su propio snapshot fresco ydelegando.
- `revalidateForUpdate(target)` conservará su snapshot inicial,comparación de
  selección/revisión yrequireSelectable;usará el mismo selected en ese helper
  en lugar de redescubrir todas las unidades otra vez en la misma llamada.
- Mantener sin omitir:ancla nativa/rutas canónicas/disjuntas/no-symlink,marker,
  tipo de raíz/base,identidades root/base,montaje,selección yrevisión. Rechazar
  cambios ydesconexión;sin fallback silencioso ni escritura/probe al resolver.
- **Nada se cachea entre objetos ni se reduce frecuencia**:cada objeto sigue
  ejecutando una revalidación fresca. No TTL ni validación por intervalo.
- No tocar runtime/guest,hashes,HTTP,fsync/checkpoint,prefetch16/14/18,presupuesto
  1GiB ni comportamiento de actualizaciones. El mismo chequeo sirve a instalación
  yactualización;no se reduce la integridad de bloques reutilizados.

### Riesgos ycriterios de aceptación

1. Una enumeración por llamada de revalidate;la siguiente vuelve a consultar.
   No reutilizar StorageOption de otro objeto ni una captura almacenada.
2. Pruebas con infraestructura actual:destino válido,cambio de selección/revisión,
   mismoUUID con raíz o montaje distintos,desconexión,símbolos/marker ajeno.
   Ejercitar específicamente UpdateTarget,no solo el ResolvedTarget del juego.
3. Reusar pruebas de guest/selección pertinentes para comprobar que sus rutas no
   cambiaron;no suite exhaustiva ni fixture/framework nuevo. Ningún permiso nuevo.
4. Review independiente de equivalencia de condiciones yventanas de carrera;
   si no puede demostrarse equivalencia,parar sin relajar checks para ganar tiempo.
5. Nueva compilación/auditoría e instalación sin pérdida. Mantener los contadores
   c103;comparar revalidateMs por objeto ydescarga10min/fondo2min,con RAM estable.
   No prometer que eliminar una llamada duplicará el throughput global ni dará7h.

## Implementation / Action Items

- [x] 1. Aprobar plan yconservar baseline c103/estado antes del cambio.
  Evidencia en `work/c103-source-timings-20261018/c103-source-{result,summary}.json`.
  Snapshot20:05:C103descargando,48.054objetos/3.881.084.824bytes,16workers,
  FGSactivo,RSS194.676KiB. Artefacto `work/c104-single-snapshot-20261018/`.
  Se guardará checkpoint/recibo tras pausa controlada antes de instalar.
- [x] 2. Helper privado mínimo,una captura fresca por revalidación,mismas guardas.
  Diff de6líneas contra c103:helper privado con cuerpo original yreutilización
  del selected de la llamada. Revisión independiente de producción aprobada;
  selección/revisión/estado,paths/no-follow,marker,root/base/mount intactos.
  Sin estado ni caché entre llamadas. La relectura adicional de preferencias
  corruptas a mitad de llamada se difiere al siguiente objeto;selección concurrente
  está excluida por lease yprefs se publican atómicamente. No relajar más guardas.
- [x] 3. Pruebas puntuales de UpdateTarget yregresiones guest pertinentes.
  5tests nuevos+3regresiones existentes:8tests/0fallos/0errores/0skips,primera
  corrida2m50s. RutaUpdateTarget directa:readonly,snapshot único ycada llamada
  fresca,selección/revisión/desconexión,root/path/base/mount,marker ysymlink.
  Ningún test antiguo alterado. Hashesrepo/clon iguales:maincf51bfa12a3100175c668622cefd1c3369e8585bf5516d105dd16ead77b641b5,
  testa8885e2b9aab8535dda9f31e1928a0c781957937c0a3c481d36b15bba8bf930b.
  XML18:13posterior a clases18:12yfuente18:10. No se corrió la clase completa
  ni se presenta Robolectric como prueba de hot-unplug físico.
- [x] 4. Revisión independiente,versión nueva,reconstrucción/build/auditoría.
  Producción ydiff aditivo de tests aprobados sin bloqueos. C104 compilada3m40s,
  auditoríaPASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT,instalada como
  104/0.2.80-storage-snapshot-once;SHA256APK
  `c89155b49667466cb3c18031895985cd0ff3340c5179a460790e17b1129141bc`.
  Dos tests Python de versión/staging funcional en verde. No cambiaCasc/Updater/Transfer/Blte,
  ni el formato de estado ni las reglas de delta;soloStorage ysuTest.
- [x] 5. Instalación conservando checkpoint/recibo ymedición real comparativa.
  C103 pausada por UI,0workers después;52.789objetos/4.219.808.339bytes conservados.
  Checkpoint yrecibo byte-idénticos después de instalarC104. Flujo automático
  preparar→confirmar→replay→10minnuevos commits→2minfondo en curso. Se recogerán
  también1.024fuentes para comparar revalidateMs por objeto contra72,77ms de c103.
  Completado,sin error:52.789objetos revalidados en572,446s,continuación con
  nuevos commits yobjetoE conservado. Foreground10min0,48032MiB/s,fondo2min
  0,53033MiB/s,16workers/FGS en todas muestras,RAM192,4–199,5MiB.
  Primeros1.024:revalidate43.455ms=42,44ms/obj(-41,7%vs c103),espera del future
  49.368ms. No atribuir toda variación del throughput aeste cambio:objetos distintos
  ylatencia variable. Resultado `REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED` en
  `work/c104-single-snapshot-20261018/c104-physical-result.json`;resumenvecino
  `c104-measurement-summary.json`. La descarga completa yupdate real entre builds
  siguen pendientes;no se han reducido checks ni formato de recuperación.

## Open Issues

- C103 mide1.024objetos ytramos posteriores tienen distinta latencia CDN. El ahorro
  potencial de una enumeración no equivale a ese mismo ahorro de tiempo total.
- En la muestra sourceWait subió mucho entre512y768objetos;existe también espera
  variable de fetch/verify. Se mantiene como otro coste,no se declara red resuelta.
- Este cambio no resuelve Transaction no-op ni garantiza arranque70450;no afecta
  al cliente70009/WTF ni a producción wowserver.
- Investigación paralela de otros duplicados,solo lectura:el barrido completo
  para `largestEncoded` es candidato a agregado fresco de SQLite con validaciones
  equivalentes (encoded/decoded/count),sin cambiar plan ni consentimiento. Aún
  sin medición de ese ahorro;no incluirlo a ciegas en C104.
- No tratar todos los pases del catálogo/checkpoint como idénticos:el re-plan al
  ejecutar protege TOCTOU/consentimiento yel checkpoint consume solo hasta su
  último ekey,no necesariamente todo el catálogo. No quitar esos gates por contar
  llamadas ni fusionar estructuras sin prueba de memoria/integridad.
- Propuesta de espaciar revalidate por tiempo/objetos **rechazada en este alcance**:
  mantener identidad por objeto. Doble hash Transfer→Casc se conserva como defensa
  ante cambios del File entre etapas;su coste medido~1,4% no justifica relajarla.
  `verifiedWaitMs` no es red/TLS pura:incluye trabajo pendiente del worker.
- Revisión HTTP paralela:el módulo real del dispositivo(API34)usa pool compartido
  pordefecto de5conexiones **idle**,no un máximo de5activas,con propiedades leídas
  en inicialización de clase. Se comprobó por lectura/dexdump del jar del sistema,
  sin hiddenAPI ni cambios de seguridad. No demuestra valor efectivo ni churn
  en la app;no se cambian pool/host/cola a ciegas. Evidencia externa
  `work/c104-single-snapshot-20261018/HTTP-POOL-FINDINGS.md`.
