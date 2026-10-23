---
id: install-time-performance-2026-10-19
state: in-progress
date: 2026-10-19
---

## Summary

**Corte 2026-10-19:** trabajo de rendimiento del descargador aparcado a petición
del usuario. Última entregaC112instalada;referencia vigente ypendientes en
[docs/downloader-handoff.md](../../docs/downloader-handoff.md). Lo siguiente es
bitácora deexperimentación,no lista deacciones a ejecutar automáticamente.
No se acreditó2,1MiB/s sostenidos ni instalaciónlista;no parar/reinstalarlaapp
por este cierre documental. Redcompartida limita comparación de velocidades.

Petición: «ok hagámoslo pero con un objetivo concreto, desbloquear más velocidad
de descarga y menor tiempo total hasta tener una instalación lista».
El resultado buscado no es más telemetría: reducir tiempo útil hasta instalación
verificada/lista, conservando recuperación e integridad. No prometer velocidad PC.

## Content

### Baseline y criterio de éxito

- C106:0,60301MiB/s foreground600,580s y0,60593MiB/s fondo122,063s;
  writer hasta nuevo source22min27s para122.954objetos. Replay no es descarga.
- Prefetch32 sigue con27–32claves y8–15workers en futex en muestras no atómicas.
  Eso es compatible con consumidor serial/HOL, no prueba de causa única.
- Objetivo experimental concreto: ≥25% de mejora sostenida de bytes encoded
  comprometidos por segundo (referencia inicial ≥0,75MiB/s), sin trasladar el
  coste al replay/finalización. Aspiración replay: reducir ≥50% su tiempo por
  objeto/byte en prefijos comparables. Son umbrales de éxito, no garantías.
- Comparación con mediciones de10min foreground y2min fondo, registrando bytes,
  número/tamaños de objetos y condiciones. No declarar causalidad por rangos
  distintos. Reportar también objetos/s para descubrir costes fijos.
- Contabilidad separada: preparación, replay, descarga nueva, índices/verificación,
  activación. Medir tiempo hasta lista al completar realmente la instalación;
  antes, dar estimación por fases y límites, nunca declararla instalada.
- No aceptar una mejora de red que empeore el tiempo total previsto. Valorar
  también coste de desarrollo/pruebas/pausas y evitar sucesión de APKs diagnósticas.

### Alcance y decisiones

1. Usar primero los logs ya retenidos deC106:sourceResolve/privateVerify/append,
   revalidate/verifiedWait yfetch. Algunas fases ya están instrumentadas: no
   duplicarlas. Obtener contabilidad del writer sin sumar tiempos de workers
   concurrentes ni confundir espera de future/cabeceras con red pura.
2. C107 ya compilada/auditada mide replay. Solo si quedan huecos decisivos,
   completar en una única candidata diagnóstica la separación del commit nuevo:
   copia/hash/BLTE, escritura/flush/forceData, checkpoint/forceDir yretirada raw.
   Agregados de bajo coste, sin logs por objeto ni nuevos frameworks/API/UI.
3. Tras medir, elegir una sola optimización mínima del coste dominante con
   estimación del ahorro máximo yprueba de recuperación. Si el ahorro posible
   no alcanza el objetivo, decirlo; no cambiar colas/pool por intuición.
4. Cambios de cadencia fsync/checkpoint, formatos persistidos, confianza de
   verificación oidentidad de almacenamiento requieren decisión explícita y
   aprobación adicional antes de implementar. No están autorizados por este plan.
5. El objetivo puede exigir tocar writer/revalidación yno HTTP. No asumir que
   reutilizar FD sea seguro:un descriptor antiguo no debe ocultar path reemplazado.

### Garantías y límites

- Mantener EKey/frameMD5/BLTE/CKey/tamaños, passthroughE autenticado, checks frescos
  de destino, cotas34old+new/1GiB, orden durable yreutilización de updates.
- No borrar Data/checkpoint/caché/archivos ajenos para producir un ensayo limpio.
  No tocar cliente70009/WTF, root, all-files access, TLS ni baseline congelado.
- Una instalación diagnóstica requiere pausa controlada, cero workers ycapturas
  checkpoint/recibo antes/después, update sin clear-data yrollback compatible34.
- Solo build1.60.1.70450/USB6AD6-EF0E dentro del consentimiento existente
  (hasta85,0GiB);si cambia build/destino/coste autorizado, detenerse yconsultar.
- Evidencia real Android/USB;tests focales no equivalen a prueba física.
- Sin commits/push, refactors ni suites generales. Fuentes/APK/logs fuera del repo.

## Implementation / Action Items

- [x] 1. Extraer contabilidadC106 ydeterminar el hueco mínimo de medición.
  Evidencia externa `work/c106-lookahead-20261018/c106-writer-accounting.json`:
  delta256→28.928objetos,2.198.801.932bytes/3.554,297s=0,58997MiB/s,
  revalidate34,10%,future40,94%,privateVerify1,91%,appendForce10,39%,
  checkpoint6,75%,resto5,91%. Intervalo temprano256→5120 confirma37,2%/36,36%.
  Contabilidad aproximada:timerswritertruncanms por objeto;resto incluye redondeo
  ytrabajo sin timer,no es una fase pura. Estos deltas excluyen replay yno son
  exactamente la ventana oficial600s. No hace falta más instrumentación del
  commit para priorizar;reusarC107ya auditada para el hueco de replay.
- [x] 2. ReutilizarC107 ocompletar una única candidata, pruebas focales yrevisión
  independiente de las rutas de integridad/durabilidad tocadas;build+audit.
- [x] 3. Pausar/actualizar preservando estado;medir replay temprano ycommit nuevo.
  La descarga continúa después del muestreo, sin esperar otra APK innecesariamente.
  C107 instalada tras pausa/0workers;checkpoint yrecibo byte-idénticos,
  324.662objetos/25.614.228.707bytes conservados. Perfil4.096completo sin error:
  totalVerify38.735ms,openHeader24.057ms(~62%),copy7.215ms,BLTE5.925ms,
  EKey754ms,cleanup465ms,index342ms. Elapsedwriter90.660ms incluye inicio/carga,
  no dividirlo como si todo fuese verify ni sumar totalVerify con sus subfases.
  Tabla índice no prioritaria(<1%delverify);reusarFDsinprobarbindingpathnoadmitido.
  C108instalada tras pausa/0workers;checkpoint/recibo byte-idénticos,igual prefijo,
  objetoE presente. Preparación/confirmación/log ymedición10min+2fondo lanzados.
  Replay crece con prefijo324.662,no confundirlo con22min del prefijo122.954.
  Incidencia observabilidad:ADB/capturas dejaron de registrar a08:11:12del
  dispositivo;no la app. Reconexión confirmó PID5301,servicio y16workersactivos,
  329.648objetos/26.006.915.743bytes sin tail parcial,objetoE conservado.
  Logcat recuperado acredita primer source08:49:41.504,324.662verificados,
  elapsedwriter3.631,725s(~60min32s). No repetir instalación/replay.
  Observadores anteriores ya no estaban ejecutándose;se relanzó SOLO watcher de
  medición+log tras recuperar historial. Ventana fresca10+2min empieza tras
  reconexión,no en ese evento histórico;timestamps de samples son referencia.
  Artefactos:c108-recovered-logcat.log,c108-reconnected-snapshot.json,
  c108-measurement-reconnected.log,c108-live-phases-reconnected.log.
- [x] 4. Publicar cuello dominante, ahorro esperado yoptimización mínima elegida.
  Si cambia garantías/contrato, parar ypedir aprobación específica.
  Candidato seleccionado para preparación paralela:no consultas de metadatos
  cliente/StatFs deUI en snapshot privado del updater. Mantener API snapshot
  completa para UI/prepare/guest yTODOS los guards de identidad. No duplica
  cuerpos ni introduce caché. Techo aritmético<34%wall;ahorro real desconocido,
  no se presenta≥25%como garantizado. No tocar durabilidad niHTTP.
- [x] 5. Implementar optimización segura dentro del alcance;tests de recuperación,
  cancelación yreuso tocados,revisión yuna medición física comparable.
  C108 implementada:+snapshot privado con flag;solo updater omite consultasUI,
  sin duplicar cuerpos. StorageSHA253591a024074aabf63819d05f29072f40b54796b73a0ba8dc610cbc52f88fb4.
  Cinco tests focales enuna invocación:fail0/error0/skip0,XML preservado en
  work/c108-update-storage-tests/. Padreinspeccionó diff yXML;revisión independiente
  APTO,sin bloqueos:campos omitidos no alimentan admisión/capacidad niUpdateTarget,
  snapshot público completo yguards intactos. Build4m41s y
  PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT;2testsPythonreconstructor verdes.
  APK SHA256efa14d068219c3002e22be355547e362718df1f990e56c07fa08fc5874ea25e7.
  Instalada108 con mismo prefijo;medición física pendiente,no declarar éxito.
  C107confirmada build70450/UnidadUSB61,2GiBrestantes y63,1GiBadicionales,
  dentro de consentimiento;sin error en preparación.No duplicar confirmación.
  No esperar replayC107completo sólo para
  perfilar:medir4096 ypausar paraC108 cuando esté validada evita duplicar una
  hora entera de replay. El coste de reanudar el prefijo completo sigue pendiente.
- [ ] 6. Evaluar umbrales ytiempo total por fases;documentar beneficio/no-beneficio,
  pendientes hasta instalación lista yrollback compatible si procede.
  MediciónC108 completada,error=null:foreground600,923s/8.321objetos a
  1,06908MiB/s(+77,29%vsC106);fondo120,172s/1.927objetos a1,33882MiB/s
  (+120,95%). Objetivo≥0,75MiB/s observado,peroNOdemuestra≥25%causado por código:
  rangos ycondiciones distintas. Writer10.240objetos/720,656s:revalidate36,436ms/
  objeto(vs42,268≈−13,8%),future3,141ms(vs50,749),privateVerify2,305ms,
  appendForce13,639ms,checkpoint7,853ms. Granparte del salto coincide con menor
  espera defuture,NOatribuirlo a consultasUI. Fases ythroughput ventanas no idénticas.
  Revalidate es ahora51,77%wall;future4,46%,append19,38%,checkpoint11,16%.
  RSS172.800–191.616KiB enforeground,objetoE conservado,tambiénenfondo.
  Evidencia:c108-comparison.json yphysical-result/throughputs/logreconnected.
  Replay NOmejorado:60min32s para324.662objetos;no se cumplió objetivo−50%.
  Instalación completa/verificación final/activación siguen pendientes. Mantener
  descarga sin más pausas/APKs hasta justificar ahorro neto frente aotro replay.

## Open Issues

- Ampliación aprobada por el usuario: «hazlo» tras el objetivo2x. La descarga
  sigue activa;investigar primero sin pausarla ni instalar otra APK sin ahorro neto.
- Plan aprobado por el usuario: «hazlo». Se inicia por contabilidad retenidaC106.
- No sabemos qué fracción de114ms/objeto es revalidación, incorporación durable,
  verificación oespera en todo rango futuro. ParaC106 ya existe desglose agregado
  anterior;los27fetches no separan cuánto de futureWait es red/HOL/publicación.
- Candidato investigado,no implementado:omitir sólo metadatos cliente yStatFs de
  UI en snapshot para updater,con enumeración fresca ychecks de confianza,
  marker,inode,base/root/mount intactos. Es subconjunto del34%,beneficio pendiente.
  Evitar duplicar cuerpos snapshot/inspectCandidate;mantener snapshot público
  completo para UI/prepare/guest yusar parámetro privado si se adopta.
- PC configura checkpoints5.000objetos/300s;no atribuirle toda la diferencia ni
  copiar su política sin demostrar garantías equivalentes yautorización adicional.
- Si una única ronda diagnóstica no permite decidir, presentar el bloqueo antes
  de extender instrumentación oencadenar entregas exploratorias.

## Fase adicional aprobada: objetivo 2x sobre C108

ReferenciaPCverificada de nuevo sólolectura:`tool/forever_client/bounded.py`
yield en orden de envío(await del primer task),no por orden de terminación;
`bounded_runner.py:967-989` ordena por prioridad,consume ywrite_content,checkpoint
por política. Por tanto HOL/consumidor ordenadoNOson una diferencia exclusiva de
Android. No proponer reordenar commits como si explicase por sísolo el saltoPC.
PC conserva trabajo acotado;su cadencia de durabilidad no se copia implícitamente.

Objetivo experimental nuevo:≥2,1MiB/s sostenidos foreground frente a1,069MiB/s,
sin regresión de fondo/replay/finalización ni de garantías. No es promesa ni
comparación causal de rangos distintos. Completar instalación sigue siendo el
resultado final;no reinstalar repetidamente por pequeñas ganancias.

Contabilidad relevante:writerC108≈70,4ms/objeto yrevalidate≈36,4ms(51,8%).
A igual mezcla de objetos,se necesitaría bajar a≈35,2ms/objeto para2x. Incluso
eliminar75%de revalidación sólo daría≈1,64x en ese writer idealizado;por tanto
no prometer2x sólo eliminando unas consultas ni extrapolar linealmenteMiB/s.

- [x] A. Investigación acotada,sin nueva APK:descomponer operaciones de revalidate
  ylocalizar syscalls/trabajo repetido que se puedan fusionar dentro de UNA
  comprobación fresca. Preservar checks por objeto,selección/revisión/volumen,
  mount/base/root/marker/symlinks yrechazo de reemplazos. Sin caché entre objetos.
  Investigación de código inicial:no se encontró una gran eliminación de trabajo
  cuya equivalencia dentro del contrato actual esté demostrada. No implementar
  fusiones de stats sólo por repetición aparente:pueden cambiar detección de
  reemplazos entre lecturas. Las estimaciones de1–3ms/objeto de un informe NO
  están medidas yno se adoptan como techo. Sigue pendiente contraste del parser
  mountinfo/fusiones con igual selección ylecturas frescas por invocación.
  Revisión de incorporación:no quitarflush previo aforce,ni retenerFDsin probar
  bindingpath. Los tiempos append/checkpoint incluyen copia,open/attrs yotras
  operaciones:el30,5%NOequivale a un perfil puro de fsync. No afirmar imposibilidad
  física de2x ni latenciasCPU sin medición por subfase.
  Investigación cerrada sin implementación:quedan enumeración Android/Binder,
  canonicalización,marker/identidades/mount yguardas por objeto. Hay posibles
  fusiones locales de stats/parsing,prefs ylecturasmountinfo,pero ningún ahorro
  grande medido/justificado. No lanzarAPKporconteos de llamadas. Atención:
  `candidate.mountIdentity ?: volume.mountIdentity ?: mountIdentityFor(...)`
  es cortocircuitado;NOadoptar3lecturasmountinfo como supuesto típico sin trazar
  valores reales (interno+USB puede ser2tanto con appdirUSB como transient).
- [ ] B. Elegir cambio mínimo respaldado por equivalencia de seguridad ytecho de
  ahorro;revisar presupuesto neto de tiempo ahorrado frente aotra preparación+
  replay(~1h actual). Si no hay candidato suficiente,comunicarlo antes de editar.
  **Puerta alcanzada:**no se ha acreditado candidato grande dentro del contrato
  de enumeración completa por objeto. No cambios de código/APK trasC108.
  Propuesta para aprobación separada:estudiar sustituir redescubrimiento completo
  de todos los volúmenes por validación directa yFRESCA del destino admitido en
  cada objeto,con enumeración completa en admisión/reanudación. No TTL ni menos
  hashes/durabilidad. Antes de implementar,documentar equivalencia orefuerzo para
  selección/revisión,prefs/registry,namespaceautorizado,symlinks/canonicalización,
  mount/desconexión/remount/read-only,base/rootinode ymarker;si no puede probarse,
  rechazar fastpath. Contrato observable deenumeración/códigos de error podría
  cambiar:requiere aprobación específica,no implícita en objetivo2x.
  Usuario autoriza el ESTUDIO del rediseño:«si, hagámoslo». Este permiso permite
  documentar yrevisar el diseño,NOimplementar ni instalar todavía el fastpath.
  Entregable:tabla de garantías viejo/nuevo,algoritmo concreto,riesgos/resolución
  de read-only/desmontaje/remount/reselección/marker/inodes yplan de pruebas;
  revisión independiente antes de presentar decisión de implementación.
- [ ] C. Implementar sólo candidato seguro dentro del contrato actual,pruebas
  focales,revisión independiente yuna entrega con medición física10+2min.
  Instrumentación adicional sólo integrada si decide una cuestión material,
  no entregas diagnósticas sucesivas por defecto.
- [ ] D. Recalcular costes dominantes ytiempo total;si2x exige cadenciafsync,
  checkpoints,formatos,orden de validación oconfianza distintos,parar ypresentar
  propuesta específica de recuperación antes de pedir otra aprobación.

No autorización implícita de batch-fsync,menos hashes,menos checks frescos,
root/permisos ampliados,FDcache sin prueba path↔objeto,ni másworkers/cola/pool
por intuición. No ampliarbuild/destino/coste aprobado ni borrar datos durables.

## Diseño en estudio: revalidación dirigida con metadata pública fresca

Estado:revisión independiente APTO condicionado,sin bloqueos de diseño tras
precisiones finales;implementación aprobada por el usuario:«hagámoslo. tengamos
en mente como funcionaba en el PC... allí iba muchísimo más rápido. apliquemos
las mejoras necesarias». PC es referencia de rendimiento,no autorización para
copiar cadencia de checkpoints ni rebajar garantías. Condiciones de implementación:seam
fresco,proof efímera all-or-none,admisión completa+ligadura,0fullsnapshot/objeto
dirigido,UUID/path/state/mount/atributos/marker frescos ysin fallback tras fallo.

### Hallazgos que corrigen el diseño preliminar

- Las specs US-007-FR-005 yUS-008-FR-002/004 exigen identidad/revisión/medio y
  recuperación válidos,no el mecanismo de enumeración completo por objeto.
  El test updateRevalidationUsesASingleFreshSnapshotWithoutCaching usa primario:
  conservar su expectativa para el fallback completo. Añadir cobertura dirigida
  específica de USB nativo;no reemplazar sus negativas ni inventar que ese test
  ya cubre la ruta nueva.
- NOcongelar state niUUID:el código viejo compara selected.identity obtenido de
  enumeración fresca;un UUID cambiado puede cambiar esa identidad. El informe
  preliminar que afirmaba que el viejo tampoco comprobaba UUID es incorrecto.
- NOcongelar presencia del marker ni exigir ausencia→presencia inmutable:el
  resolver actual permite marker ausente ycomprueba su contenido si existe.
  Mantener exactamente esa regla,sin refuerzo ajeno al objetivo que rompa flujos.
- AOSP android15-release:Environment.getExternalStorageState(File) llama a
  StorageManager.getStorageVolume(path,user),que llama getVolumeList(user,0) y
  canonicaliza paths. NOes un check directo sin enumeración ni necesariamente
  equivalente a storageVolumes(FLAG_REAL_STATE|FLAG_INCLUDE_INVISIBLE).
  Fuentes:https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/os/Environment.java
  ycore/java/android/os/storage/StorageManager.java en la misma rama.
- Evidencia real SOLOLECTURA:mountinfo del PID5301 guardado en
  work/c108-update-storage-20261019/design-app-mountinfo.txt. USB visible en
  /storage/6AD6-EF0E es FUSE(id2491,0:422,rw);debajo hayvfat8:65 con
  dirsync/errors=remount-ro. Por tanto FUSErwNOprueba estado rw del medio:
  conservar metadata pública fresca de estado,no fiarse sólo de mountinfo.

### Alcance mínimo propuesto

Primera implementación únicamente para USB/removible con nativeVolumeRoot
demostrado por API pública(API≥30),UUID ynamespace app-specific exacto al admitir.
Primario/legacy,targets sintéticos ycasos sin prueba suficiente conservan ruta
completa anterior. No mapa global ni TTL:la prueba se transporta en UpdateTarget
como datos inmutables de namespace autorizado,no como snapshot de estado confiado.
Datos mínimos:namespace léxico admitido,base yroot admitidos,API volume root yUUID
admitidos;reutilizar identity/revision/rootIdentity/baseIdentity/mountIdentity ya
presentes. No serializar ni alterar operationStorageId,checkpoint orecibo.

### Algoritmo por objeto propuesto

1. Leer selección/prefs/registry con validación estricta actual ycomparar
   identity/revision del target. ProcessGuard/leases sin cambios. No fallback a
   primario cuando falta USB,no selección silenciosa.
2. Una consulta FRESCA a StorageManager.storageVolumes(API pública) por objeto.
   NOllamar getExternalFilesDirs,getStorageVolume por cada appdir,Environment
   getExternalStorageState ni reconstruir/inspeccionar todas las StorageOptions.
   La API sigue devolviendo una lista de metadata:la propuesta reduce el pipeline
   completo,no promete cero Binder ni cero enumeración interna.
3. Localizar un único volumen por UUID admitido;verificar su directory pública
   contra namespace admitido,kind/removable yestadoMEDIA_MOUNTED. Ausente,
   duplicado,UUID/path/kind distinto,read-only/unknown oconsulta fallida:rechazar
   sin readmitir automáticamente otra ruta. Descripciones nunca deciden confianza.
4. Reconstruir sólo el candidato seleccionado desde SUdirectory pública actual
   ynamespace app-specific exacto;no adivinar /storage/<UUID>. Ejecutar los mismos
   guards trusted/rootShape/nativeNamespace/safeSegments del candidato yel mismo
   resolveForUpdate(selected) físico,sin optimizar/omitir sus checks redundantes.
   Releer mountinfo acotado paraese candidato,canonicalización,marker yatributos
   base/root por objeto ycomparar con target admitido. NoFDretenido niattrsTTL.
5. El resto del pipeline queda igual:revalidate antes del acceso a destino,
   sourcefuture/EKey/BLTE/CKey,Dataforce/checkpoint/dirforce yretiro durable raw.

La validación dirigida conserva metadata fresca UUID/path/state además de los
checks físicos;no supleUUIDconmount/inode. En caso de fallo se cierra la operación;
volver a admisión completa es una acción explícita,nunca modo de aceptar un cambio.
No reutilizar un candidato que falló ni ocultar fallos con fallback permisivo.

### Matriz de garantías y pruebas focales previstas

| Caso | Barrera propuesta |
|---|---|
| Reselección/revisión/prefs corruptas | Guard actual de selección+registry por objeto |
| USB ausente/UUID cambiado/estado desconocido/ro | Metadata pública fresca,única ycompatible |
| Mismo UUID en otra ruta | Directory/namespace exactos,ninguna readmisión implícita |
| Mismo path,otro mount | mountinfo fresco ycomparación con target congelado |
| Root/base recreado | fileIdentity NOFOLLOW fresco,sin cambiar helper |
| Symlink/escape/marker ajeno | Guards trusted+resolve actuales,marker acotado |
| Root inicialmente ausente | Igual regla de resolve ycomparación rootIdentity;no crear |
| Caída/reanudación | Nueva admisión completa;ninguna prueba efímera recuperada de disco |
| UI/prepare/guest/primario | Camino completo sin cambios |

Tests mínimos:metadata-dirigida fresca/no fullsnapshot,UUID/path/state(unknown/ro/
ausente),mount/root/base/symlinks/marker+selección/revisión;reusar fixtures actuales.
Necesario un seam interno de consulta de metadata pública para fakear cambios en
tests,sin opciones públicas ni framework. No aceptar tests que prueben sólo no
enumeración perdiendo las negativas. Mapear códigos de error antes de editar;
rechazo igualomásestricto puede cambiar código enambigüedad,documentarlo.

ImplementaciónC109 encurso,sóloStorage/Test. Primera ronda focal10tests:fallo
de conteo porque select() también enumera;ajustado baseline. Segunda ronda9/10:
fixturedestructivoroot-recreate dejaba marker ausente para siguiente variante.
Padre inspeccionó código/test yreevaluó antes de permitir reparación focal:
aislarrootfixture tras variante destructiva yrestaurar bytes originales del
marker después de probar preservación de foreign (nunca autorreparar en app).
También se pide no capturarThrowable/cancelación ni recogerdescriptionUI en
provider dirigido. No nuevaAPK ni cambios de dispositivo. Verificación final
pendiente;no se acepta candidato por9/10 ni se atribuye fallo a producción sin
evidencia. PadreauditóService.withStorageIo/destination/execute/recover/qualify
yUpdater.execute/requireSamePlan:sin resolve de fallback tras revalidatefallida.
Verificación final tras reparación focal:10tests/0fallos/0errors/0skips enuna
invocación,XML08:03:49 posterior a clase08:03:29 yfuente08:02:53.
MainSHA256eee8866da6725f5c0c025dce0dc5123c8b61bceca0740928d205ec550360c5e4;
Test2135177bbbd2f3151f0d58d62bf69af8962bc66baf19ea05867dc480febe49c5,
repo/clonidénticos verificados por padre. Provider sin getterdescriptionUI;
queryCancellation propaga,Exception→unavailable,noThrowable. Se omite telemetría
nueva por KISS:timerUpdaterrevalidate existente se conserva. Revisión de código
independiente APTO,sin bloqueos;build/auditC109en curso,no instalada.
Antes de decidir instalación se cierran3gaps focales TESTONLY:mountIdentitynull
usa mountinfo real delhost/base sinroot yrechaza expectedmountfalso;UUIDausente
mantiene fallbackcompleto explícito;cancelación enadmisión propaga. No repetir
los10checksverdes:guardarXMLy ejecutar sólo3métodos afectados/adicionales.
No son pruebas de hotunplug físico ni de rendimiento;mainno cambia por este cierre.
Gaps cerrados:3métodos focales/0fallos/0errors/0skips,XML08:07:57;10verdesprevios
preservados en evidence-10-green.xml(1método solapa,no suite única de13).
TestfinalSHA256f99de07006211f3ae84c1fcbf5569c5ee8778337761620d763c851c251f16f06;
main eee8866...0360c5e4 intacto. Nullmount ejercita mountinfo real delhost/base
conrootausente yrechaza target.mount falso,no hotunplug real. UUIDless no consulta
seamnativo ymantiene ruta completa;cancelación de admisión propaga.
C109compilada4m56s,2testsPythonreconstructorverdes y
PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT. APK SHA256
7eb30b0b9f5426bba589c0663cfd5f5b3cc548b89b7b26e9658bef9250497c5f.
Retención client-manager-code109-directed-usb-revalidation/ conreceipt/audit.
Instalación experimental autorizada por el usuario:«si, hagámoslo». Requiere pausa controlada ypreservar
checkpoint/recibo;coste orientativo~73minreplay+preparación paraúltimoprefijo390k.
El diseño no acelera ese replay. Ahorro físico yobjetivo2x siguen sin comprobar.
C109instalada tras autorización ypausa/0workers:401.259objetos/31.585.607.500bytes
encoded conservados,objetoE presente,checkpoint sin tail parcial. Checkpoint/
recibo byte-idénticos antes/después;capturas enwork/c109-directed-storage-20261019.
Preparación/confirmación/replay/medición10+2min lanzados;no duplicarconfirmación.
ReconexiónADBsolicitada porusuario09:06UTC:daemon noestabaactivo,conectado de
nuevo sin reiniciarapp. PID13009/C109FGSactivo,checkpoint401.259objetos/
31.585.607.500bytes intacto,objetoE conservado ysin tail parcial. Replaycapturado
11:06:46del dispositivo:173.056/401.259(~43%),elapsedwriter2.036,566s;
no nuevosource todavía. Historialrecuperado mezclaPID5301C108ysupausa autorizada
conPID13009C109:NOatribuir esoscommit/pausa antiguos alrunactual.
Observadoresanteriores ya noestabanactivos;se relanzaronSÓLOwatcher/logfiltrados
porPIDactual,conarchivosreconnected separados,sin volverconfirmar/instalar/replay.
Medición C109 completada,error=null,REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED:
foreground600,323s/9.478objetos/769.627.752bytes=1,22263MiB/s(+14,36%vsC108);
fondo121,619s/1.461objetos/222.435.796bytes=1,74422MiB/s(+30,28%).
Objetivo2,1MiB/sforegroundNOalcanzado. Tramosdistintos,noA/Bcausal.
Writercapturado11.008objetos/730,033s:revalidate29,798ms/obj(~−18,2%vsC108),
future1,370ms,privateVerify2,409ms,appendForce14,538ms,checkpoint10,023ms;
respectivosporcentajes44,93/2,07/3,63/21,92/15,11. Ventanasnoidénticasthroughput.
Replay hasta primer source11:51:38.395dispositivo:401.259verificados,
elapsedwriter4.728,656s(~78min49s),excluido delthroughput. No reducciónreplayC109.
MuestrasRSSforeground176.852–192.844KiB,fondo153.440–189.116KiB,objetoEretenido,
maxrawkeys32 ymaxrawbytes3.943.459;snapshotsnoatómicos,nopruebanlímitesduros.
Evidencia work/c109-directed-storage-20261019/c109-comparison.json y
c109-physical-result.json/throughputs/logreconnected. No pausar descarga por
este resultado. C110lectorofflineyaAPTO,gateFUSEfísicopendiente sinactivación.
Usuario pregunta impactoUXdepreparación/replay:registrar coste como problema de
producto separado,nosólotextoUI. Reanudar hoy revalida TODOel prefijo;continuar
enun mismo proceso(backgroundFGS incluido)no reinicia replay por objeto. No
confundir esa espera con descarga nueva ni afirmar que afecta aarranque normal
de una instalación completa. Mejoraralgoritmo de replay requiere diseño propio,
no saltarhashes ni confiar sóloencheckpoint/fechas/tamaños.
Snapshot de decisión sinpausa:C108390.017objetos/30.697.564.657bytes encoded,
servicioforeground/PID5301/16workers yobjetoE conservados,sin tail parcial.
No confundir16futex deuna muestra noatómica con16workers permanentementeparados.
Un nuevo replay ahora tiene un prefijo mayor que324.662;antes de instalar,
considerar coste orientativo~73min según ritmoC108,más preparación. No esETA
garantizada;el objetivo es ahorro neto,no sólo tests verdes.

### Riesgos y puerta de implementación

- Cambian mecanismo yorden entre descubrimiento ychecks físicos. No afirmar
  equivalencia bajo todos los races:documentar ventanas ycomparar contra el viejo
  TOCTOU no atómico,con review independiente. No quitar checkscheck→use por objeto.
- La query pública conserva un Binder ylista;canonicalización/marker/atributos
  permanecen. Sin perfil físico no hay ahorro garantizado ni promesa2x.
- La medida debe separar query metadata/checks físicos para explicar beneficio;
  si se implementa,integrar counters agregados mínimos enesa misma entrega,
  no otra APK dedicada sólo a diagnóstico.
- Fallback completo sólo para targetsineligible definido enadmisión;no después de
  detectar una discrepancia activa. Una API inusable es bloqueo,nocachépermitida.
- Pendiente revisión independiente de este diseño. Si es apto,presentar alcance
  exacto ypedir aprobación de implementación antes de modificar fuentes.
  Cerrado:reviewAPTO condicionado yusuarioaprueba implementar/probarantes de
  decidir instalación. Se inicia cambioStorage/Test acotado;no pausar descarga.

### Cierre de revisión de diseño: precisión requerida antes de implementar

Primera revisión:NOAPTO por ambigüedades de seam,prueba efímera,errores yfallback;
se precisa aquí el contrato. Sin cambios de fuentes ni instalación.

**Seam interno:**inyectar enService una función
`(Context) -> List<NativeVolumeMetadata>` de consulta pública fresca,distinta del
`volumes` completo. En producción llamaAPIstorageVolumes yno expone configuración
al usuario. TestService permite fakearla conlistas mutables contadas;la estructura
NativeVolumeMetadata ysu mountIdentity de fixtures ya existen. Producción no
rellena mountIdentity desde un caché:se lee del namespace app propio por objeto.
No introducir otro harness ni helper JNI. Mantener el enumerador completo actual
para todos los casos no elegibles ypara admisión.

**Prueba efímera exacta:**añadir aUpdateTarget4campos opcionales condefaultnull:
admittedUuid:String,admittedVolumeDirectory:File,admittedAppFilesDirectory:File,
admittedLexicalRoot:File. Visibilidad interna de propiedades,tipos públicos ya
existentes,sin cambiar campos de identidad actuales. Ausencia de TODOS significa
fallback completo fijado al admitir;presencia deTODOS significa ruta dirigida.
Estado parcial es inválido yse rechaza,no vuelve a fallback. No añadir boolean
isDirected redundante,mapa global,serialización ni clavespersistidas nuevas.
Paths admitidos sonabsolute.toPath().normalize(),no sustitutos de canonicalizar:
cada revalidación repite trusted/safeSegments yresolve físico antes de usarlos.

**Admisión concreta:**hacer primero snapshot completo yresolve físico actuales.
Elegibilidad preliminar:API≥30,!legacy,Kind.REMOVABLE,nativeVolumeRoot no null.
Sólo entonces consultar metadata pública fresca yexigir un único volumen cuyo
UUID real genere la identity.id admitida (misma regla stableId),con directory
igual al nativeVolumeRoot admitido,non-primary/removable yestado compatible.
Si esas precondiciones existen pero la consulta demuestra cambio/ambigüedad/falla,
rechazar admisión,no marcar ineligible para esconderlo. Ausencia estática de
precondiciones genera fallback completo como hoy. Root no necesita existir.
Por tanto admisión usa1snapshot completo y1query dirigida adicional para ligar
prueba al target;cada objeto dirigido usa1query fresca y0snapshots completos.
No anunciar resolve+2revalidate=3queries sin distinguir enum de admisión.

**Uso dirigido:**leer registry/prefs/selección/revisión como hoy;query fresca,
buscar porUUIDadmitido;comprobar dir/kind/state ygenerar SÓLO candidato app-specific
desde ese directory público. Exigir igualdad léxica normalizada con los4campos
admitidos antes de ejecutar inspectCandidate(false)+resolveForUpdate(selected).
Mount se obtiene de fresh externalFilesDir/base,NOroot que puede estar ausente;
mismo mountIdentityFor bounded/longestprefix/escapes actual. Comparar después
root/rootIdentity/baseIdentity/mount/revisión contarget talcomo hoy.
No congelar ni construir enbase a descripción,estado anterior oUUIDadivinado.

| Caso dirigido | Resultado propuesto |
|---|---|
| Prefs/registry corruptos | storage_selection_corrupt,helpers actuales |
| Selección/revisión/kind cambió | storage_selection_changed |
| UUID admitido ausente/UUID cambió/directory null | storage_mount_unproven |
| UUID duplicado | storage_unavailable,rechazo más estricto quefirst-wins viejo |
| MismoUUID,otro directory/namespace | storage_root_changed |
| Consulta pública lanza/null no admitido/estado desconocido oRO | storage_unavailable |
| Montaje físico no probado | storage_mount_unproven |
| Otro mount probado bajo el mismo path | storage_mount_changed |
| Root ausente conbase/mount válidos | Admitir igual que hoy,sin crear nada |
| Root/base recreado,symlink,escape,marker ajeno | Mismos guards/códigos actuales |

Prioridad exacta de guards físicos sigue funciones existentes;no afirmar que
ante múltiples fallos simultáneos todos los códigos deban coincidir. Ambigüedad y
metadata desconocida son rechazos explícitos,nunca aceptación más permisiva.
Regla marker idéntica:permitidoausente,si existeverificarcontenido acotado.

**Sin bypass:**nullableproof fija ruta para la vida del target. revalidate nunca
atrapa fallo dirigido para snapshot/resolve completo ni refresca su propia prueba.
No añadir reintento/readmisión dentro del execute oclosure destination.revalidate.
Las resolve ya existentes de planificación/recovery no son un fallback automático:
revisar sus callers antes de editar,conservar consentimiento yrequireSamePlan.
Readmisión tras fallo necesita nuevo flujo explícito;no relajar el plan aceptado.

**Pruebas concretas:**conservar5regresiones existentes de ruta completa;añadir
fixturesUSBnative usando querymetadata contada para:1)frescura por objeto y0full
snapshot después de admisión,2)UUID/path/state/duplicado/ausente/querythrow sin
fallback,3)remount mismo path yROconFUSErw,4)root/base/marker/symlink+revision,
5)root ausente vsvolumen ausente yprueba parcial rechazada. Agrupar variantes
en pocos tests,NOmatriz exhaustiva ni testRoot que desconecteUSBfísico vivo.
