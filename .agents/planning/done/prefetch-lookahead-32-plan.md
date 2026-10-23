---
id: prefetch-lookahead-32-2026-10-18
state: completed
date: 2026-10-18
---

## Summary

Tras cerrar c105 se propone probar una ventana mayor de trabajo adelantado,
sin más conexiones ni cambios de integridad/durabilidad. Observación real:
el writer espera mientras hay15outputs verificados y14–15workers inactivos,
con1worker en poll. Es compatible con bloqueo por el primer objeto pendiente.
El usuario aprobó:«adelante. No tiene sentido tener14workers parados». La prueba
mantendrá16conexiones;solo se amplía trabajo adelantado si supera primero las
condiciones de recuperación/contabilidad. No se promete mejora lineal ni7h.

## Content

### Evidencia y límites

- C105:0,61991MiB/s foreground10min,0,46100MiB/s background2min,16workers,
  RAM174–188,8MiB,FGS ydatos previos conservados.20fetches muestreados exitosos:
  espera de headers~78,5% del tiempo de esas muestras,no del walltime global.
-30snapshots breves en app muestran co-existencia de writer en futex,15.blte
  listos y14–15workers en futex con1enpoll. Las lecturas no son atómicas ni
  stacks Java:cuantificar el fenómeno yno confundir cualquier futex con HOL.
- Probe aislado40peticiones/~90KiB:reuso7/8en serie;tras burst16 y250msidle,
  segundo burst reutiliza5ycrea11capas TLS. Encaja con5idle por defecto. No prueba
  el valor/coste efectivo del pool en la app yel wrapper cambia su identidad.
  No cambiar propiedades,transportes ni hosts por esa prueba aislada.

### Cambio propuesto

1. Mantener16workers. Ampliar solo lookahead16→32objetos (cola14→30:
   consumer1+cola30+productor1). Commit sigue serial/ordenado por ekey.
2. Mantener cota1GiB de encoded en vuelo ytemporal privado mayor ya costeado.
   No materializar todo el manifiesto ni crear una cola/futuros sin límite.
3. Aumentar recuperación18→34solo si se demuestra cota total de archivos,
   incluyendo restos previos yventanas de crash;si no,parar yresolverla antes.
   Rollback a16mantendrá admisión de estados nuevos,no se reinstala c105a ciegas.
4. No alterar hashes,HTTP/TLS/rangos,fsync/checkpoint,marker/identidad,revalidate
   fresco por objeto,consentimiento ni formatos persistidos. Updates con fuente
   siguen seriales ysolo descargan objetos faltantes/corruptos.

### Precondición de recuperación/contabilidad (no es solo cambiar constantes)

- Reproducir primero kill lógico justo después de checkpoint durable yantes de
  borrar el raw privado. Es una ventana real de código;puede dejar copias de
  objetos ya checkpointed más otras descargas adelantadas.
- Las copias privadas checkpointed no se retirarán hasta verificar la copia CASC
  duradera;no borrar unknowns/identidades distintas/symlinks ni copiar/reescribir
  todo Data. No aceptar datos solo por tamaño,fecha oCRC del checkpoint.
- Presupuesto ycostes deben incluir caché previa ytrabajo nuevo sin doble crédito.
  No empezar otra reserva1GiB ignorando bytes persistidos;evitar deadlocks si
  el presupuesto espera a que se retiren copias de objetos ya verificados.
- Un raw cuyo ekey ya está checkpointed no puede descontarse otra vez de la
  descarga CASC. Si también se necesita para un loose file,definir preservación
  ycontabilidad coherentes;no prometer bytes que luego se descargan fuera del plan.
- Conservar plan readonly yre-plan de consentimiento. Aclarar estas invariantes
  con tests mínimos antes de ampliar la ventana;no generalizar un gestor de caché.

### No objetivos

- No más workers,no ajuste de idle pool,no dependencia HTTP nueva,no batch-fsync.
- No saltar replay/verificación ni reemplazar checkpoints con flags de memoria.
- No arreglar Transaction no-op ni instalar cliente mínimo/streaming jugable.
- No tocar cliente70009,WTF ni producción wowserver.

### Contrato mínimo para reproducción/implementación

- SourceDataFiles!=null conserva fallback serial yreuso actual. Solo la rama
  paralela sin fuente ensaya lookahead32;sin nuevos parámetros públicos.
- Antes de ampliar,constatar el caso checkpoint durable→raw-delete interrumpido
  con fixtures pequeñas ycrashes lógicos repetidos. No se mata el cliente real.
- Una opción mínima para retirar copias raw checkpointed es el tramo posterior
  al `yield` de un spec bare:solo se reanuda al pedir el siguiente spec tras la
  verificación CASC. Debe probarse ese contrato de consumo;si falla/verifica mal,
  no retirar copias. Sin nuevo API de CASC ni listas del manifiesto completo.
- Al retirar raw checkpointed,el plan no lo descontará también como descarga
  CASC ni como cache de loose que luego desaparecería. La ruta de loose actual
  se conserva yse costea íntegra si no se va a preservar esa copia;no se introduce
  extracción/CASC-loose ni retención indefinida que rompa la cota34.
- Reserva keyed de bytes o mecanismo mínimo equivalente:contar restos previos
  aún útiles/reservas de tareas sin doble conteo. No inicializar0ignorando caché;
  la transferencia de reserva al programar/reusar yliberar debe corresponder a
  archivos realmente retirados. Si existe deuda heredada,no iniciar nuevas
  reservas hasta resolverla con propiedad e integridad demostradas,sin deadlock.
- Antes de instalar,se guardarán checkpoint/recibo tras pausa controlada. Snapshot
  de referencia22:33:c105con87.436objetos/6.916.831.418bytes,FGSactivo,16workers,
  RSS173.012KiB. Artefactos `work/c106-lookahead-20261018/`.

## Implementation / Action Items

- [x] 1. Aprobación ybaseline c105;reproducir estados de crash entre checkpoint/raw-delete.
  Aprobación ysnapshot registrados;estados lógicos postcheckpoint/preunlink y
  raw útil para loose reproducidos. No presentar48vs24como fallo originalC105:
  el coste cambia al retirar la copia. Se conserva la prueba del nuevo contrato.
- [x] 2. Cerrar prueba de cotas totalkeys/bytes ycontabilidad de raw checkpointed/loose.
  Diseño reevaluado con aprobación deevicción ydir-force privado:mapa34duro en
  seed yrequest,1GiB old+new,evicción solo heredado unused con costes conservadores
  en modo paralelo;bare outstanding cubre dequeue/hash,retirada tras verifyCASC.
  Serial conserva crédito loose útil,no re-acreditaCASC checkpointed.
- [x] 3. Cambio mínimo de ventana32con recuperación segura;fixtures pequeñas de
  head lento,hilos activos,orden,crashes repetidos yreuso de updates sin redownload.
  Principales10tests anteriores verdes;última corrección de liveness/seed/caso
  serial con2tests focales verdes/0skips. Tres regresiones concurrentes finales
  (head32,far-future64trasreset,cierres repetidos) en curso sobre hashfinal.
  MainSHA256 `7ebbcdb692552eea0f1f780a3d0a67d4f3edb17dcb70cc6ab7557a1688e0bc07`,
  Test `9c09d887055bfac842f5f7bcef14b7c08993a3aea224610f025cab1344f5c5df`.
  Repo/clonidénticos,XML2tests21:54posterioraclases21:51/fuente21:50.
  Verificación final sobre hash7ebb:3tests concurrentes(head32,farFuture64,
  doscrashes)verdes/0skips,XML21:56;no se repitió toda la clase. Revisión final
  aprobada sin bloqueos:seed/request≤34,ghostslotfalla sinfree,unknown/case
  no-canónico rechazado,liveness snapshotúnico yserial crédito loose correcto.
- [x] 4. Revisión independiente de concurrencia/coste/recuperación;build/auditoría
  en nueva versión,reteniendo fuentes/APK/estado anterior.
  ProducciónA/B/D revisada;guardcentral seed34 implementada,foco review final en
  curso. C106106/0.2.82-lookahead-32-recovery en compilación;no se instala antes
  de pruebas finales+auditoría. C105permanece instalada.
  Actualización:buildC1064m48s,auditoríaPASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT,
  instalada como106/0.2.82-lookahead-32-recovery;SHA256APK
  `9e304b6373d061034c8984950e01ccb61f30e0122ea5e0d55f42588f31cb2724`.
  ADBse reconectó antes de pausar;no instalación durante ausencia de conexión.
  Checkpoint/recibo byte-idénticos tras update,122.954objetos/9.637.262.946bytes
  conservados,0workers tras pausa y16raws de2.379.518bytes(baselineantesdeinstalar).
- [x] 5. Pausa controlada,instalación sin pérdida,replay ymedición10min+2minfondo.
  Comparar espera de fuente,ocupación/idle,máximo de archivos,RAM yMiB/s.
  Resultado físico REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED, error=null.
  Primer source nuevo 00:38:24.663 del dispositivo,122.954 objetos verificados,
  elapsed writer1.347,494s (22min27s),excluido del throughput nuevo.
  Foreground600,580s:379.747.671bytes/5.287objetos,0,60301MiB/s;
  fondo122,063s:77.554.019bytes/970objetos,0,60593MiB/s.
  Frente aC105:−2,73%/ +31,44%,respectivamente,pero son rangos de objetos
  diferentes,no A/B controlado:sin mejora consistente demostrada de ventana32.
  Muestras:8–15workers en futex,27–32claves,cache máximo4.215.998bytes,
  RSS181.848–190.772KiB,objetoE conservado ysin tail parcial observado.
  Snapshots no atómicos,no prueba de cotas ni corte eléctrico físico.
  Evidencia:c106-physical-result.json,c106-comparison.json,c106-fetch-summary.json
  bajo work/c106-lookahead-20261018/. C107 diagnóstica queda como continuación,
  no condición pendiente de este ensayo. No cambiar HTTP ni límites por este resultado.

## Open Issues

- Evaluación adicional pedida por el usuario:revalidación~20minesexcesiva. Se
  preparaC107SOLOdiagnóstica para separar coste deíndice,apertura/metadatos/cabecera,
  copia al temp privado,EKey,BLTE ylimpieza,manteniendo todos los hashes/checks.
  No se instalará hasta cerrar mediciónC106,para no invalidarla.
- Perfil80syscalls reales tras cargar checkpoint:33running/12FUSE noatribuibles,
  14pathname metadata/open,4DataUSB,4tempprivado,0índicesclasificados. Es muestra
  corta/no-atómica,no latencias por fase,NOprueba que índices sean dominantes.
  `debuggerd -b` exige root(se denegó,no se elevó);AMprofile previo0bytesno válido.
  No mover scratch niañadirFDcache con estas observaciones insuficientes.
- Candidatosnoaplicados:índice temporal del writer en privado(concuotas/proof de
  recuperación/cancel/oldUSBscratch) oreducción deaperturas por segmento. Cachear
  unFDrequiere conservar enlace path/inode:NOaceptarunFDviejo válido mientras
  el archivo actual del path se reemplazó/corrompió. No flags«ya verificado».
- **Reevaluación obligatoria tras el refinamiento (sin APK):**review final halló
  que el mapa permite66reservas ypuede generar64raws con caché heredada32tras
  rollback/reset(checkpoint vacío,objects no purgado),aunque recuperación solo
  admite34. También `drainPossible=queueNonEmpty||submitted>0` ignora el último
  bare ya dequeued ypuede abortar mientras aún verifica/drena. No continuar con
  parches ciegos:se congela este candidato hasta cerrar admisión yprogreso.
- Simplificación **aprobada por el usuario** («ehazlo»,preguntando si1GiB es
  restrictivo;se mantiene1GiB porque snapshot actual ocupa428.420bytes de raw):
  techo real34claves old+new independiente de cola30 ytope1GiB;contador de bare
  pendientes desde antes de encolarlos hasta después de verificar/retirar(no
  basarse solo en cola vacía). Si no hay trabajo capaz de drenar,liberar espacio
  solo evictando caché privada heredada no programada/no usada,con dueño/tipo/
  clave/cotas demostrados yborrado durable. Nunca segmentosCASC,checkpoint,
  originales,loose decoded ni archivos desconocidos.
- Ese último caso puede obligar a repetir **algunos bloques adelantados** que aún
  no estaban incorporados aCASC;no es una redescarga del cliente ni de bloques
  durables verificados. El plan debe presupuestarlos conservadoramente antes del
  consentimiento(sin créditos de una caché evictable),sin subir1GiB ni aceptar
  que un estado generado exceda34. Sin esa política,una caché far-future llena
  puede impedir traer el primer missing manteniendo orden ypresupuesto.
- Contrato cerrado de admisión:techo del mapa **34**,no66;contar claves heredadas
  yreservas nuevas antes de crear archivos. Al alcanzar cota de bytes oclaves,
  esperar trabajo que realmente pueda retirarse,oevictar solo una copia heredada
  no programada de la cola(no una creada/usada por workers de este run). Elegir
  preferentemente la más tardía del catálogo,no el siguiente objeto necesario.
- Evicción conservadora solo en rama paralela sin fuente:planear el máximo de
  descarga sin crédito por raw privado evictable(dejar créditos decheckpoint y
  loose decoded verdaderamente verificados). Por tanto,si se retira una copia,
  sus bytes vuelven a estar cubiertos por el consentimiento. Updates con fuente
  ysu ruta serial de reuso no se cambian.
- Evicción con dueño/identidad de workspace,clave real del catálogo,nombre exacto,
  archivo regular/no-symlink ycotadeencoded acreditados;`.blte`válido antes de
  borrar y`.part`solo caché no comprometida. Borrar yforzarobjectsDir antes de
  release. No victima ya programada/activa ni datosCASC/checkpoints/loose/unknowns.
- Copia checkpointed se retira solo tras verificaciónCASCdelafter-yield;no se usa
  la evicción para eludir ese gate. Productor conoce el orden de manifiesto yno
  considera evictable un heredado anterior al cursor que todavía debe drenarse.
- `outstandingBare`incrementa ANTESdeemitir ydecrementa DESPUÉSdeverificar/retirar;
  cubre el hueco dequeue→hash. No hacer requests/esperas bajo progressLock ni
  dentro del after-yield. Release corto tras sincronización,no espera de bytes.
- Snapshot23:19:C105sigue descargando105.546objetos/8.271.293.122bytes encoded,
 16workers,14futex/2poll,16claves de caché428.420bytes;confirma que1GiB no estaba
  lleno en esa observación. Es una muestra,no prueba ausencia universal de presión.
- **Bloqueo de entrega detectado en revisión:**el primer C106libera reserva si
  falta`.blte`aunque exista`.part`,yseed colapsa ambas extensiones a1encoded por
  clave sin contemplar ambas ocupaciones. No se acepta reinterpretar la cota
  como«solo red nueva»:se aprobó presupuesto de restos+trabajo nuevo. Se ha
  enviado un repair-loop específico,no se ha construido/instalado APK106.
- **Refinamiento aprobado por el usuario («si,hazlo»):**
  `publishVerifiedPrivateFile` actual solo hace rename atómico+lock,no fuerza
  el directorio deobjects. Tras corte de corriente,unlinks/renames de caché no
  persistidos pueden reaparecercomo`.part`o`.blte`,aun con Data/checkpointdurables.
  Fallar cerrado al superar34preserva integridad,pero no cumple la cota total
  demostrada ni reanudación automática de un estado generado por el propio código.
- Repair-loop acotado completado:5tests focales/0fallos/0skips,descuento loose
  coherente con retiro,lookup solo para raws presentes,dual/unsafe rechazados,
  reserva no se libera si queda`.part`. **Sigue bloqueado:**retener ese`.part`
  sin retirarlo ni fallar ante deuda heredada>1GiB puede dejar producer esperando
  presupuesto yconsumer esperando siguiente missing;que la rama local haga return
  no demuestra ausencia de deadlock global. Los casos pequeños verdes no prueban
  esa frontera. No construir ni instalar este candidato antes del refinamiento.
- Propuesta mínima:tras retirar una copia raw conocida yredundante DESPUÉSde
  verificar la copiaCASC,forzar el directorio privado deobjects antes de liberar
  su reserva/slot. Igual para nuevos objetos consumidos. No cambia fsyncData,
  checkpoint,journal ni su cadencia;añade sincronización de metadatos de caché.
  Medir su coste,no prometer que sea gratis ni saltarla para aumentar throughput.
- Se autoriza explícitamente ese force adicional SOLOdel directorio privado de
  caché antes de reutilizar reserva/slot. El«no nuevos fsync»del alcance inicial
  se refina en este punto de borrado;no se reduce ni cambia cadencia deData,
  checkpoint o journal. No relajar la cota34ni1GiB para eludir esta obligación.
- Parte de ese refinamiento:admitir yretirar`.part`checkpointed solo con dueño/
  identidad/nombre/tipo/tamaño acreditados ycopiaCASC completamente verificada;
  no borrar unknowns/symlinks ni liberar reserva con un archivo persistido.
  Presupuesto porclave incluye ambas extensiones si se admiten;si el caso no se
  puede justificar,se rechaza expresamente,sin espera infinita ni otras reservas.
- La reserva de una clave heredada tampoco autoriza ampliar su`.part`mientras
  la deuda inicial siga por encima de1GiB. El prefijo checkpointed se drena con
  hashes/retirada durable antes de seguir;demostrar que producer/consumer no se
  esperan mutuamente al cruzar ese límite. No llamar«no hang»aunreturnlocal.
- Aclaración de repro:el48vs24del fixture compartidoLOOSE NOdemuestraunbugviejo:
  C105preservaba raw ypodía reutilizarlo sin red.48es el coste conservador del
  NUEVO retiro;no prometer crédito de una copia que se va a borrar. Mantener
  prueba de cierre/consentimiento,pero no presentar ese contraste como fallo real
  de C105sin el caso antiguo adicional que lo demuestre.
- El beneficio del lookahead está por demostrar;latencia variable delCDN yotros
  costes pueden seguir dominando. No presentar la muestra corta como causa única.
- La recuperación actual distingue de forma especial solo `cachedKey==lastEkey`;
  comprobar casos con copias checkpointed más antiguas tras interrupciones
  repetidas antes de usarla como base de una ventana más amplia.
- El diseño final debe justificar34como cota real;si precisa más parámetros o
  cambia garantías,no ampliar silenciosamente el plan ni el presupuesto.
