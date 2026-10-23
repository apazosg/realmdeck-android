---
id: fast-resume-redesign-2026-10-19
state: in-progress
date: 2026-10-19
---

## Summary

**Corte 2026-10-19 — mejoras aparcadas por el usuario.** C111 quedó instalada,
medida y posteriormente sustituida por C112. Solape e inicio sostenido acreditados;
no instalación completa ni mejor tiempo total/throughput acreditados. No código
ni otro APK de rendimiento por ahora. Estado para retomar:
[docs/downloader-handoff.md](../../docs/downloader-handoff.md). La transferencia
no se pausa por esta decisión; leer resultados pendientes antes de tocarla.

Petición literal: «mientras avanza y después medimos la velocidad. investiga y
propón posibles mejoras para reducir en gran medida este tiempo de comprobación
y comienzo de descarga. No nos vale una reducción de un 20 o un 40%».

Investigación de código cerrada, sin cambios de producción, sin parar C110 ni
ejecutar sondas o benchmarks en el móvil. Este documento propone un rediseño;
no autoriza implementación, migración, otro APK ni cambios de durabilidad.

## Content

### Objetivo propuesto y métricas separadas

- Objetivo experimental: ≥80% menos bloqueo hasta primera descarga nueva, no
  otro ahorro incremental del 20–40%. No garantía ni cifra medida del rediseño.
- Aspiraciones UX para decidir diseño: pausa viva ≤30 s; cierre real ≤5 min
  hasta transferencia nueva si catálogo/admisión lo permiten. Confirmar viabilidad
  con fases existentes; no prometer segundos para releer decenas de GB.
- Tiempo hasta instalación lista no debe empeorar; registrar también trabajo
  repetido por cada interrupción, lectura/verificación final y reparación.
- No contar como éxito descargar unos pocos bloques y volver a quedar bloqueado.
- C110 continúa sin interrupción;medición completa cerrada sinerror:
  replay468.177objetos61min35s,prefijocomún400.384writer~33%menos/verificador
  ~35%menosvsC109;descarganueva1,16950MiB/sforeground/1,46412fondo.

### Hechos y límites

- Prefix C110: 468.177 objetos / 37.044.580.827 bytes encoded. Comparación parcial
  C109/C110 de 299.008 objetos: totalVerify 3.321.211→2.155.659 ms (~35% menos),
  aún insuficiente. No comparación A/B controlada ni resultado de replay completo.
- A ~307k objetos: binding+cabecera ~60% de totalVerify, rango+crypto ~39%, índice
  ~1%. Binding NO es medición pura de stat: también incluye header30 ysu prueba.
- Incluso quitar TODO binding dejaría ~40% del verificador: no justificar ≥80%
  con esa optimización aislada. También habría que reducir llamadas/lecturas
  pequeñas y acercarse al recorrido secuencial, sin omitir crypto por objeto.
- Leer los37,04GB en10min requiere58,88MiB/s sostenidos; en5min117,76MiB/s, antes
  de otros costes. Velocidad física secuencial de este USB aún no acreditada.
- Checkpoint/recibo prueban identidad, orden ydatos esperados, NOausencia debitrot.
  Añadir HMAC/SHA a metadata no demuestra que losbytes del USBsiguen intactos.
- Prefetch actual se limita por min(1GiB,34claves), a menudo ~32objetos/unosMiB:
  el lookahead anticipado puede llenarse en segundos, no sostener una hora.
- No afirmar dobleverificación completa previa a replay enla instalación sin
  fuente: losscans catálogo/merge checkpoint no equivalen siempre a cryptoData.

### Opción A: grupos/segmentos con lectura secuencial

Conservar validación de CADA cabecera/EKey/BLTE/frameMD5/CKey/tamaño, pero leer
un prefijo contiguo de segmento con buffer compartido yprobar binding físico
antes/después delgrupo, no 4stats+headerpread porobjeto.

- La escritura ycheckpoint actuales exigen offsets contiguos dentro delsegmento;
  comprobar esa precondición, no inferirla de nombres. Sin ordenar elmanifiesto
  ni materializar millones deobjetos. Buffer acotado64–256KiB,segEndadmitido.
- Un buffer de segmento podría preleer físicamente el objeto siguiente dentro
  delsegEnd;cada parser deobjeto sigue limitado aSUventana. Esto cambia la regla
  anterior de buffering ydebe probarse explícitamente, no introducirse a escondidas.
- NOavanzar cursor reutilizable/retirar raw/aceptar grupohasta crypto de todos
  sus objetos+bindingpost. Usar fin de grupo como barrera;cancel/fallo no lo valida.
- Binding pasa deO(objetos) aO(segmentos o grupos acotados),sin cambiar fsync ni
  journal. La ventana check→uso es mayor: aprobar granularidad, no afirmar
  seguridad idéntica bajo todoslos races. ABA/inplace mutación no se elimina.
- Potencial grande para reducir replay REAL, pero ≥80% no acreditado;sin suelo
  físico/CPU no basta para prometer inicio enminutos trasuncierre.

### Opción B: pausa viva con continuación en memoria

Separar Suspended deCancelled:detener red,productor yverificador enborde durable,
liberar wake/wifi locks yretener cursor/tabla/lease/continuación acotados. Reanudar
tras controlesfrescos de destino, sin reconstruir el prefijo delmismoproceso.

- Ningún nuevo token delease persistido como confianza;siAndroid mata proceso,
  recoveryfrío usando CPexistente. No garantía de supervivencia deFGS/proceso.
- Definir FGS/notificación,selección bloqueada mientraslease,tiempomáximo depausa
  yrutas de cancelación/desconexión. No descargar/verificar mientras figura pausado.
- NOes equivalencia deintegridad automática:identidadUSBno detecta bitrot
  in-place durante pausa. Se conserva confianza enbytes verificados deesta
  ejecución durante más tiempo,como enruncontinuo;hay que aprobar esa semántica
  yelbarrier de validación antes depublicar/usar. No confundirla con cierrefrío.
- Potencial de reanudar ensegundos,pero sólo cubre pausa conproceso conservado.

### Opción C (preferida para cierre): descarga sostenida con verificación diferida/solapada

Tras admisión yconsentimiento frescos, tratar CPcontenido anterior como
PENDIENTEdeverificación, NOcomo válido. Permitir descargar eincorporar objetos
nuevos ensegmentospropios distintos de losque seestán comprobando;verificar
prefijo engrupos/paralelo ybloquear índices publicados/activación hasta validar
TODOelconjunto requerido. No se publica `.build.info` niinstalación lista antes.

- Comparar usarCP/receipt/catálogo existentes yestadoverificado efímero por
  ejecución antes de inventar inventarioSHA/listasEKey ocheckpointdoble nuevos.
  Nuevosformatos sólo si el diseño los necesita,con migración/rollback aprobados.
- Separar extents previos read-only ynuevas escrituras;noappend alsegmentoque
  selee. Evaluar sellar colaanterior ycrear siguienteid conservando invariantes
  actuales de CP/índices/canonicalidad, sin asumir que yaestá implementado.
- No limitar progreso sostenido a34raws:consumirlos mediante commitdurable de
  nuevos extents NOACTIVADOS. Losraws mantienen1GiB/34 ysu contabilidad actual.
- Sin nuevotokenconsentimiento si todocomienzaDESPUÉSdefreshbuildPlan+
  requireSamePlan+consentactual ycostes cubiertos. Si sequisiera antesdeese punto,
  sería otrocontrato NOincluido. Nada dedescarga noautorizada porparecer pequeña.
- Verificación diferida NOreduce por sísola bytes leídos;evita bloquear red ypuede
  evitar repetir trabajo tras varias pausas. Beneficio total depende desolape y
  contención realCPU/USB;no vender traslado deesperaalfinal como ahorro.
- Fallo de prefijo:mantener noactivados los extents nuevos verificados,detener
  publicación yreparar SÓLObloques afectados. ESTA reparaciónlocal de stagedCASC
  NOexiste hoy;resetCascAfterRollback/resetResumableCheckpoint descartanstage,
  no son reparaciónlocal. Diseñarla es requisito, no capacidad supuesta.
- Requiere aprobar cambio de contrato:de «verificar todoantesdecontinuar» a
  «progreso sobreobjetosnuevos independiente;contenidoanterioryconjuntocompleto
  válidosantesdepublicar/usar». No saltarhashes niconfiar enfecha/tamaño.
- Potencial de comienzo temprano sostenido yreducción total porsolape,pero es
  cambio de arquitectura mayor queotraAPKincremental;riesgo recuperación alto.

### Descartado / no solución suficiente

- Prefetch temprano acotado sin commit:seguro después deconsentimiento,pero
 34claves se llenan enseguida yvuelve aestancarse. No cumple esteobjetivo.
- Hash/CRC/HMAC decheckpoint,fecha/tamaño obitmap«yaverificado» para omitir datos:
 no prueban integridad actual deUSB. No se ofrecen como reanudaciónsegura rápida.
- Mover toda lainstalación aalmacenamiento privado requiere37–85GB adicionales
 yotrodestino/coste/consentimiento:no solución por defecto aesteUSB.
- Másworkers,HTTPpool,batchfsync ycleanups dehex no se mezclan enesterediseño.

## Implementation / Action Items

- [x] 1. Investigar alternativas read-only ycontrastar afirmaciones sin tocarC110.
- [x] 2. Elegir alcance de diseño: C+A para frío;Bpausaviva queda fuera de esta
  implementación para no sumar otra máquina deestados/FGS antesde resolver frío.
  con métricas inicial/total por separado. Usuarioaprueba cambio decontrato,
  no una supuesta mejora ya garantizada.
  Aprobación:«si,hazlo» tras propuesta de descargar yverificar enparalelo con
  activaciónbloqueada hasta validartodo. Se inicia diseño concreto/revisión,
  sin cambiarAPK/dispositivo actual ni permitir confianza enCRC/mtime/tamaño.
- [x] 3. Diseñar barreras,extents/CP/índices/leases yrecuperaciónlocal mínima;
  revisión independiente antes de autorizar código/migración.
  ReviewAPTOcondicional:contratos1init/freeze,propiedadPreadonly/nuevosIDs,
  sealedbindingexact,barreraconjunta,tablaúnicowriter,joinanteslease,costes y
  deuda34 quedan requisitos deimplementación verificables,no permisospara
  debilitarlos. Primera implementación offline:C+A sinreparacióninplaceautomática;
  ante corrupción abortapreservandotodostage+taildurable ybloqueaactivación.
  Noreset/fulldownload ni gastoextra;repair explícita futura pendiente decisión.
  Porgarantías existentes SYS-NFR002/003 yUS008AC02/03/04 +FR002/003/004:
  contenido viejo no se considera reutilizado hasta proof,consentimientoantes
  detail,checkpointdurable sinpublicaciónparcial. No se edita spec silenciosamente
  nirebaja integridad de instalaciónlista. Pausa viva excluida.
- [x] 4. Implementación offline confixturespequeñas reales/equivalencia hashes,
  fallos enamboscursors,cancel/cierres/consentimiento,espacio yconservación parcial.
  Iniciada enunbuilder conownershipCasc/Updater/adapter/tests para evitar writers
  paralelosencontratosacoplados. Primera ruta source-null+CPno-finalizing+reader
  habilitado;otrosmodosconservados. No implementación de reparacióninplace.
  Child debepararanteformatosnuevos/costesnoprobados,envezdeforzarmigración.
  Nuevologdistingue checkpointPending/verifiedreal:primerfetchNOdebeafirmartodoP
  verificado. MétricasV/Wconcurrentes no se suman comowalltime.
  Checkpointdeimplementación solicitado porusuario:7archivos modificados;
  coordinador/freeze,verificadorsegmentobuf128KiB,barrera/newsegments,sealedbinding,
  rawretireproofyIDcapacity implementados. 6nuevosCasc+4Updater+1Preflight escritos,
  aúnNOejecutados:primercompile tuvo2erroresKotlin(castlambda yreferenciaa clase
  top-level) corregidos,segunda compilación interrumpida. NoclaimGREEN/readiness.
  Desviaciónadiscutir:producer todavía pasa bares viejos porcola. Exigir traza
  deque sólo seanmetadata/sincrypto/esperaV porbare ytests deprogreso sostenido
  >34newcommits conVbloqueado;si retrasa primerfetch,no aceptarcomoobjetivocumplido.
  Costesespacioseal/header/index deben probarse además de capacidadIDs;pendiente.
  Verificaciónfinalbuildercerrada:32testsdirigidos/0fallos/0errors/0skips enuna
  invocaciónGradleoffline testLegacyDebugUnitTest:BlteRange8,Preflight4,Casc13,
  Updater7. XML14:08:22..24 ygrouped10.logretenidoswork/c111-overlap-tests/logs/.
  Padre leyó4XMLysumó32,comparó7fuentesrepo/stage byteidénticas. NoAPKtodavía.
  Criteriodecisivoleídoentest2130..2229:34rawsEXACTAMENTEdurableprefix llenancupo;
  Vbloqueadoenprimerbind porsuhilo,35commitsnuevos=69marcosCPdurables antesde
  abrirgate;≥1proofindividualCASCreaderdrain,nogroupack,despuéspublicaciónnormal.
  Grupo1000objetosstreaming,claimrace/lateRegistration,CPswapfailclosed,
  coste480+16registros eIDs yregresionesfact/null/sourcedreportadosverdes.
  FinalSHA:Cascddc619890f7fdccb415561a2b182e07fd2274afb46d436dd86e43d0b64c8a722;
  Test2596440ddce9fff2cf623538ad876e6441f4fde045ca5016a19c1e17b4c51f7c;
  Updater94ce1ad03f3e1f51a73e79628ae46b017814255db565ffe39a2b6360da6b7579;
  UpdaterTest55a1e1de45276a7305ea1613d12128322d5027e2c1fc13a56abe7d7a199a7374.
  Reviewfinalsobrefuentesactuales ybuild/auditGameNativecompleto enparalelo.
  ReviewfinalAPTO,sinbloqueos:C111actualyfuentesstaged(hashddc619corey94ce1Updater)
  verificados.PKporobjeto/listasgroup/Doubleretire/selloCP/huecodeadlock34
  corregidosytestsverdes. Source-nullCPpartialnewIDs,barrierV+W/formatCP/durability
  preservados. Instalarpruebaautorizadocadena esperaAUDITPASS,nopauseantes;
  observadorinotifyinstalled-preservedlanzado. Ningunaestimación80%validadaaún.
- [ ] 5. TrasC110medida,diseñar prueba física mínima que mida suelolectura/crypto
  sinotraAPKdiagnósticacompleta ni interrumpir descarga sin justificar ahorro.
- [ ] 6. Unaentrega integrada revisada/auditada,medir inicio sostenido,instalación
  lista yrecuperación. No aprobar sólo uncontador moviéndose antes.
  C111ensayo10+2cerrado sinerror:EARLY_DOWNLOAD_AND_SUSTAINED_COMMITS_CONFIRMED.
  Primer source16:38:41.085appPID16961:overlap=true,verified13.736,pending548.773,
  writer164,816s(2min45). Hostprepare→observedsource999,535s(16min40) vsC110
  4.512,169s(~−77,85%);confirm→source514,903s(8min35) vs4.024,653s(~−87,21%).
  Medidasdifferentprefix/run,observationlagspossible,nocausalA/Bni≤5mincoldmet.
  Foreground594,737s:378.842.376bytes/5.161newobjs=0,60748MiB/s;
  fondo120,718s:73.439.538bytes/1.137objs=0,58017MiB/s. Maxsampledkeys32/31,
  objetoEretenido,FGScontinuo. Mucho>34commits,NOsóloprefetchlleno.
  VelocidadinferioraC1101,16950/1,46412:arranquemuchoantes,NOmejorthroughput
  acreditadonitiempototalresuelto. VerificadorPpendiente cuando terminó muestra;
  medirtransicióndespuésdeVdone antesotraAPK. CompitenCPU/USB yotra descargaPC.
  Artefacto work/c111-overlapped-resume-20261019/c111-comparison.json.
  PCreferenciaactual localizadapadrepor/proc(lectura):PID300333manualboundedrunner,
  /opt/wowserver/updates/1.60.1.70450/progress/download.json,stageWindowsx86_64
  enUS70450,phase downloading,reportamax_in_flight1. NOusar registrohistórico
  /opt/wowserver/state/client-download-progress.jsondel04octcomoactual. Media4MB/s
  userreport,no tasaindependientededucidadelpuntoperiódico. SinpararPCniAndroid.
  Pverificador terminóC11117:06:27.537dispositivo:42segmentos/562.509objetos,
  elapsedV1.757,830s(29min18s),sinerror. PrefijodistintomayorqueC110468.177,
  noA/Bcontrolado. Ventanaposterior10min599,998s=0,73111MiB/s,sinerror,
  c111-after-prefix-result.json. Mejorque0,60748durantesolape,pero NOdemuestra
  regresión/causalidadfrenteC1101,16950. Usuario confirmaotrosdispositivos
  descargandox86_64enmisma red:condiciónCOMPARTIDA,separarstartupdebandwidth.
  EsperarfinaldeotrasdescargasparanuevaventanaenMISMAejecución,sinreinstalar/
  revalidarprefijo. Instalaciónlista/integridadfinal/activación aúnpendientes.
  Usuario autoriza avanzar hasta despliegue/prueba(«avanza ysigue conlosrequisitos
  para desplegar yprobar lanuevaversión»). No pedir otra autorización rutinaria
  si cumplecontratos/costes/aprobaciones existentes;NOinstalarhasta pruebas+
  review+buildauditPASS. Candidata111/0.2.87-overlapped-resume preparada.
  Preparados drivers externosC111 deinstalación/preservación/UI/confirmación y
  observadornuevo:exigeoverlap=true,cuentasverified+pending=prefix,preflightPASS,
  crecimiento deCP>34nuevoscommits y10+2minFGS/fondo. NOesperaprefix completo
  paraempezarmedición nicuentaCPmetadata comocrypto. Guardasbuild70450/USB/coste
  ≤85GiB/espacioaprobado igualantes. ScriptsASTválidos,NOejecutados enmóvil.
  Version/auditexactdescriptorscoordinator+freeze preparados(2checksPythonverdes),
  sinwildcard nuevos. PinsfuturoCasc/Testpendientesdespuésfuentesfinales/review.

## Open Issues

- Aceptación pendiente al aparcar: instalación completa/activación,velocidadsin
  competencia de red yreparaciónlocal de stagedCASC. Paso5(sueloUSBaislado) no se
  ejecutó yno justifica detener transferencia. No reparar conreset ni sobrescribir
  durables viejos:la primera entrega aborta preservando progresoantecorrupción.
- Build C111 completo6min44s PASS,3testsPythonfocalesverdes. Primer audit falló
  sólo por backport D8 delcoordinador revisado;seadmite descriptor EXACTO
  CascOverlapCoordinator$$ExternalSyntheticBackportWithForwarding0 (sinprefix
  abierto),testnegativoUnexpectedsigueverde. Audit repetido sinrebuild innecesario:
  PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT,2184sources/178payloads. APK SHA256
  5df95f91dbd5bce1eb2ae8e7856c81b1e736386dd33814843fcf3b5c0a148725.
  Instalación ya encadenadaaarchivoauditPASS;esperar evidenciabytepreservada,
  no lanzar otrodriver ni confirmar descarga duplicada.
  Incidencia instalación:timeoutADB20s alcatCPtrasPausar,ANTESdeAPKinstall.
  Driverfallóseguro;padre comprobóversion110/FGSparado/0fetchworkers yretomó
  desdepausa(NOotraPausa/nocleardata) conlecturasCP180s+CRC/orderoffline.
  C111instalada111:562.509objetos/44.594.046.548bytes encoded conservados,
  CP/receiptbyteidénticos,tail0,objetoEpresente. Before/aftercapturas yJSON
  c111-installed-preserved.json enwork/c111-overlapped-resume-20261019/.
  Preparación/confirmación/mediciónrelanzadasUNAvez trasverificar no oldobserver;
  no repetirinstalldriver. SamplerC111ajusta timeoutCP180s,preservamismocontador
  CRC/order/snapshot;no cambioapp ni de intervalosdefsync. Mediciónpendiente.
- Usuario ratifica criterio de despliegue: «perfecto vamos a ello?». Se exige
  prueba de >34 commits nuevos con V bloqueado y deuda heredada checkpointed
  llena; no se acepta un primer fetch aislado. Drivers de C111 comprobados
  sintácticamente, sin ejecutar; host con424GiB libres para empaquetado.
- Para avanzar en paralelo: snapshot estable de 7 archivos en
  `work/c111-overlap-review-snapshot/manifest.json`; revisión independiente WIP
  iniciada mientras el builder completa los checks, sin editar sus archivos.
  Los hallazgos se cerrarán sobre el hash final antes de aprobar el despliegue.
- ReviewWIP detectócontabilidadretire doble(evict+ack),fallbackfileKey→path y
  falta deCPidentityporgrupo;padre exigeclaimatómico único/late-registrationsegura,
  identidadCPfailclosed yrevalidaciónporack. Recomendación deSQLiteglobal-lock
  retirada:AndroidSQLiteDatabaseusaThreadLocalSession/poolseguroycursorsporhilo;
  lock sobrewithObjectsintroduciríadeadlock productorbudget↔Vcatalogquery.
  Recomendaciónabandono dehilos/lease tras timeout tambiénretirada porinsegura;
  joinscompletos anteslease,stuckOSIOesriesgoresidual,NOliberar mientrastrabajan.
- Padre detectóO(n)listaspersegmento(groupKeys/groupFrames/acked)potencialmillones
  aunque segmento1GiB:exige CPstream+1lookahead/groupcount/lastKey+≤34rawkeys,
  nosoluciónconmilloneslista/bitmap. PKSQLiteporcadaCPobjeto tambiénevitablecon
  cursorcatálogoseparadoVstreaming,NOprecargar1Mspecs. Reparación enbuilderúnico.
- Proofindividual de retiroprioragrupo en snapshot eraNIOcopy+hashyregular/size,
  NOdev/ino pathFDprepostC110:debeusarmismofactoryOsCascReplayReader+fullrange,
  preservarrawante mismatchedpath. Helperporcada≤34rawrescanCPcompleto infringía
  coste diseñado:colectar mapaCP≤34keys eninitialloadúnico. Hallazgospendientes
  dechecksfinales,noclaim seguridad/rendimiento porcompileo testsescritos.
- Diseño C+A aprobado;producción/dispositivo sin cambios mientras se cierran
  protocolo de recuperaciónlocal,presupuesto ybarreras. No código/migración
  antesderevisión deldiseño concreto;si formatos/costes cambian,presentarlo.
- Cuánto cuesta metadata vsheaderproof dentrobinding ysuelo secuencial/crypto
  aúnsinmedir. No hay evidencia quegarantice≥80% o≤5min enUSBactual.
- Contratos de granularidad porgrupo,confianza durante pausa yvalidaciónantesde
  activación deben aprobarse explícitamente;no relajar garantías silenciosamente.
- Localrepair stagedCASC yretenciónsuffix requieren diseño/compatibilidad,
  presupuesto demedio ytratamientodedatosdesconocidos;reset actual NOsirve.

## Diseño concreto en elaboración (C+A, sin código)

- Primera ruta:instalación sinfuente/sourceDataFiles=null;updatescon fuente
  siguen seriales actuales. Mantener CPFWCASCR1/40B+marcos72B/ordenEKey/offsets
  ysegmentIDscontiguos,si se demuestra compatibilidad. No migración implícita.
- Bajo lease ejecutar load/recoverytorn-tail ANTESdecongelar prefijoP. Ese paso
  puede truncar lacola anterior alúltimo marco durable,igualque hoy. Después
  freeze(count,lastKey,CPbyteEnd,segmentEnds,identity) yNINGUNAescritura normal
  aarchivos deP. Writer/verificador se inicializanunavez,no dosloadsimultáneos.
- UnverificadorCPreadonly acotado aP.byteEnd,unwriterCPappendúnico. Elwriter
  ingiere metadata validada contra catálogo peroNOacepta sucontenido físico:
  empieza fetch/commitmissingtail sinsacar468kbares deunacola antes. SóloV
  verificaprefijo;no bucleprincipal llamandoVdenuevo. Sinbitmap/listaRAMmillones.
- Nuevosobjetos van anewDataId>prefixMax,CPappendnormalordenado;leer/verificar
  Pnocompiteconescrituraenelmismoarchivo,síenelmismoUSB. Barrera conjunta
  antesdeFINISH/índicespublicados/stagingtransaction/activación.
- sourceRevision inspeccionaData/dataINSTALADO,NOstage. Sourcelessusa
  EMPTY_SOURCE_REVISION;no atribuirle prueba decontenido deP. Pse liga por
  checkpointIdentity/receipt/namespace ysegheader/freshbinding/crypto propios.
- Lectorgruporequiereexactsizepostcomo guarddehigienedeprefijo inmutable.
  checkBinding(end)actual sólo≥end:NOprometertamañoexactoconfirmaqueNOloprueba.
  Propuesta interfaz interna checkSealedBinding(exactSize),freshpath+FD stat
  dev/ino/type ysize==exact;checkBinding actual se conserva para modoC110.
- Buffersecuencial128KiB compartido porsegmento;header30desdebuffer,ventanaN
  porobjetoalmismoparserBLTE. Ack degrupo trascryptoTODOS+bindingpost. Raws
  viejosnoretiradosporbareyield:ack oproofindependiente igualomásfuerte necesaria.
- Para deuda privada≤34checkpointedkeys bloqueantenuevosfetches:se puede
  autenticarINDIVIDUALMENTESUCOPIACASCreal conguardasC110/cabecera/EKey/BLTE/
  CKey/tamaño ybindingprepost,ANTESderetirarrawredundante duraderamente. NO
  basta verificar elraw+mírarsólocabeceraCASC. Elposteriorgruporepite≤34objetos;
  noafirmaqueungrupocompleto estéaceptado. Boundedmappara34keys/catálogoPK/CP
  offsets,noadelantar/verificartodoelmanifiesto. Revisar equivalenciaconcontrato
  actualderetirada antesdeimplementarlo;keys tailaheadconservan evicciónactual.
- Presupuesto1GiB/34intacto;segmentonewporresume añade480B+indexrecords,NO1GiB
  porque archivosnoprealocados. SíconsumeID0..1023 ycapacidadíndice:coste/probe
  deadmisióndebencontarlo,yrechazar antesdeescribir siúltimoIDnoalcanza.
- Reparaciónlocalpropuesta(precisa decisión):sólorango CORRUPTOdemismo largo
  ensegmentostagingpropio,conrawreplacementoficial íntegro,verificador drenado
  yFDcerrado,leaseexclusivo,destinofresco,NOFOLLOW,write+force yreadback completo.
  MappingCPorden/tamaños/noformat unchanged;noinstalledsource/CASCantiguo
  ajeno nireset-stage. Corte durantewrite deja rangoverificable/reparable al
  próximoarranque,NOpublicable. Necesita revisión decrashsafe yprueba focal.
- Networkrepairnoestápresupuestadoencostemissingtail:si nohayrawlocalútil,
  exigir recálculo/consentimientoANTESdebytesextra. Mantener extentsnuevosno
  activados yprogreso tailautorizado;noaumentar85GiBocotas pordefecto.
- Laautorización C+A no implica aceptar ya escriturasinplaceaData durable
  niotra política dered. Es unpunto dedecisión pendiente explícito antesdecódigo.

### Decisiones mínimas para review de arquitectura

- Group = segmentoCPsellado completo(rango480..segEnd,sinleerfuera). Unaackpor
  segmento;buf128KiB. Segundos deventana check/uso sonfunciónderendimientoreal,
  no inventarlímitedetiempo ni quepoststat detecteABA/inplacebitrot universal.
  checkSealedBinding internalexactsize enlstat+fstat levanta ack despuéscrypto;
  perobjecthash/header/framingintactos. No mantener FDsenmásdeunsegmentoV.
- CPappend:unúnicohiloW;conservar appendResumeFrame existente que abre/checkea/
  escribe/force/cierraPORmarco+dirforce. Noretener unnuevoCPfdwriter porahorro,
  porque cambiaría pathbinding/cadencia sin necesidad. VtienelectorREADONLY
  acotado aP.byteEnd+headeridentity+CRCytuplecatálogo;crecimiento esperado deltamaño
  CPnoinvalidaP,perosustitución/headers/framesantiguosdistintos sísonerror.
- Cierre de ambosworkers antes deoutputfinalization;errorV→cancel W/fetches,
  drenarjoin ypreservar marcosnuevos durables/tailrecuperable,NOresetCASC.
  ErrorW→cancel V ymismafinalizaciónsegura. No seguirpublicando ni dejarhilos
  tocandoCP/USBdespuésdeliberarlease. SinFINISH enfallo/no índicespublicados.
- La descarga tail puedeprogresar hasta falloV detectado;después nose mantiene
  descargandodescontroladamente mientrasunoperaciónfallidaseha liberado. Preservar
  avance yaautorizado,no prometer descargasconcurrentestrasERRORterminal.
- Reparacióninplace descrita arriba es CANDIDATA,separadadepermitirsolape:
  sepuede detenerantecorrupciónconservandoTODOSsegmentos/marcospropios yofrecer
  reparaciónexplícita posterior,sinvolveradescargarloválido. Revisar si eseprimer
  alcance es suficiente o si userdebeautorizar también escrituraslocalesantes
  de implementar reparacióntotal.No afirmar que reparaciónyaestá implementada.
  Decisiónprimeralcance:detenererrorpreservador,SINreparacióninplace ni descarga
  extra automática. No modificar durables viejos salvo torn-tail recovery vigente
  ANTESfreeze. No requerir nuevo consentimiento porquesta no escritura de reparación.

### Contabilidad de inicio C110 (evidencia retenida,sin nueva prueba)

`work/c110-streaming-replay-20261019/c110-startup-phase-accounting.json`:
preparación/navegación hasta intención deconfirmación487,516s(~8min08s);
confirmación→inicio writer329,713s(~5min30s,aprox);
writer→primersource3.694,940s(61min35s). Totaldesdepreparar4.512,169s(~75min12s).
Timestampshost+logdispositivoUTC+02;confirmaciónesintención justoantesdelclick,
no tiempo exactodeservicio. No tratar lasfasescomo microbenchmark aislado.

Consecuencia:quitar elbloqueo decryptoantesdepedir datos todavía deja unos
13min38s depreparación/re-plan actuales,másinitmetadata nueva. ≥80%reducción
de75min hasta~15min podría ser objetivo,NOestádemostrado. Aspiración≤5min
requiere también revisar preparación/admisión,NOvender sóloel solape como
solución instantánea. No omitir consentimiento ni validar menosfilas porintuición.
