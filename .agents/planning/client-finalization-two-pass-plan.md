---
id: client-finalization-two-pass-2026-10-20
state: in-progress
date: 2026-10-20
---

## Summary

Petición: «haz un plan para simplificar esto. no tiene sentido hacer tantísimas
lecturas no? sobretodo cuando comprobamos también las descargas».

Objetivo: reducir la finalización normal de una instalación nueva de **ocho a
como máximo dos lecturas completas SHA-256 por segmento CASC**, sin retirar la
verificación oficial existente ni reducir los fsync o las garantías de recovery.
No es una orden de cancelar/modificar la operación C117 que está aplicando.

**Solo plan.** No código, despliegue, pausa, reset, repair ni recuperación nueva
hasta aprobación. Una sustitución de APK ahora mataría el proceso ypodría obligar
a recuperar/repetir trabajo; queda expresamente fuera de este plan mientras siga
la operación actual.

## Content

### 1. Causa y evidencia

Código inspeccionado: `scripts/arm64-prototype/app/updater-gate/`.

| Pasada | Punto actual | Qué relee |
|---|---|---|
| 1 | `ForeverClientUpdater.inputFile()` | SHA-256 completo del fichero staged |
| 2 | `ForeverClientUpdateTransaction.plan()` | Mismo staged, contra hash de InputFile |
| 3 | `commit()` → `validatePlan()` | Mismo staged antes de publicar |
| 4 | Bucle pre-journal de `commit()` | Mismo staged + forceFile/forceDirectory |
| 5 | `rollForward()` | Staged otra vez antes de rename |
| 6 | `commit()` → `verifyPublished()` | Destino ya publicado |
| 7 | Entrada de `cleanupCommitted()` → `verifyPublished()` | Destino otra vez |
| 8 | Antes de limpiar journal → `verifyPublished()` | Destino otra vez |

Se trata del camino normal de instalación sin fuente, con segmentos write-once
(`old==null`). Las lecturas de originales/backups en updates son otro caso.
`APPLYING` se emite **después de plan()**, antes decommit:las pasadas1/2 noestán
bajoese estado, pero también retrasan la finalización ysu comienzo visible.

Evidencia solo lectura C117/PID11675/TID5454,20oct:
- Último commitCASC:14:03:29.341 del móvil,206.159objetos nuevos y16.535.464.484B;
  prefijo938.819objetos/74.581.621.578B ya validado. Payload total90.117.086.062B,
  más cabeceras eíndices. Ocho pasadas equivalen a~721GB de lectura lógica,
  NOa ochopasadas observadas comoterminadas.
- Muestra12,24s:~35,6MiB/s de lecturas delwriter,FDstagingdata.005→data.006,
  contadores deescritura sin cambio. Journal privado todavía solo `.lock`.
  Compatible concommitpre-journal;validatePlanvsbuclepre-journal no desambiguados.
- Aesa tasa puntual, leer90GB ronda40min. Es aritmética orientativa,NOETA ni
  benchmark de capacidad secuencial USB, ni medida de cada pasada.
- `/proc` acumula actividad de todoelrun: susbytes totales nopermiten asignar
  número depasadas deestafase. `run-as` no puede listarUSBcomoapp;PermissionDenied
  NOsignifica stagingausente. No se añade lecturaCASC para diagnosticarlo.

Artefactos externos: `work/c117-finalization-readonly-20261020/`
(`findings.json`, `short-read-rate.json`, `current-logcat.txt`, `current-proc.txt`,
`app-namespace-status.txt`), bajo `/home/adrian/wowforever-artifacts/`.

### 2. Qué comprobación aporta cada capa

- EKey/BLTE/frameMD5/CKey/tamaños existentes autentican los objetos oficiales.
  **Se mantienen.** SHA-256 delsegmento CASC es una huella del layout materializado,
  no sustituye esa autenticación ni demuestra por sísola correspondencia conCDN.
- Hash de raw descargado no garantiza bytes finalmente guardados enUSB: conservar
  una prueba de contenido delsegmento materializado yuna comprobación deldestino.
- `forceFile`, `forceDirectory` yjournal durable garantizan orden/persistencia,
  no son hashes. Mantenerlos NOrequiere repetir seisveces un hash decontenido.
- Más relecturas no dan snapshot atómico global;unfichero puede cambiar después
  delúltimohash en ambos diseños. Elcontrato debe ser explícito sobrela ventana
  check→uso, lease, identidad delmedio ybloqueo dearranque/publicación.

### 3. Alcance inicial: camino normal fresco, no todos los casos

Optimizar primero finalización deinstalación sin cliente previo, segmentos
write-once yuna misma ejecución bajo lease. Conservarelcamino estricto anterior
para actualizaciones con originales/backups ypara recovery deuna transacción
interrumpida, hasta que su equivalencia se demuestre aparte.

No opciones deusuario, cambiosHTTP, pausa viva, otrocachemanager ni inventario
persistente dehashes. No cambiar formatoCASC/checkpoint/journal por defecto.
Si una decisión exige otroformato omigración, parar ypresentarlo antesdecódigo.

### 4. Flujo simplificado propuesto

**A. Una autoridad única para la admisión staged (pasada1).**

Unificar `inputFile()` y`transaction.plan()` paraque el mismo byte no se lea en
ambos. Tras lasbarreras cryptoCASC actuales, realizar UNA lecturaSHA-256 completa
porsegmento acabado;validartipo/no-follow,tamaño/identidad ynamespace antes/
después delread. Generar plan depublicación inmutable ligado aownerToken,
stageUUID/marker,destino yhuellas esperadas.

La API concreta se cierra enreview:elhash debe tener procedencia delvalidador,
no unboolean `verified=true` puesto porelcaller. No aceptarplanesmutados,copias
conotrosficheros nivalores aportados como prueba dehaberleído. Si se mantiene
`InputFile` público para otros callers,su ruta estricta permanece;elcamino nuevo
interno entrega prueba efímera no falsificable dentro delprotocolo deoperación.
No conservar4096FDsabiertos:memoriaO(MAX_OPERATIONS≤4096) dehuellas/metadatos,
yFDs acotados porfichero. Metadatos de identidad NOse presentan como prueba de
contenido;la detección decontenido cambiado la asegura la pasada2.

**B. Commit sin relecturas globales intermedias.**

- `validatePlan` conserva propiedad,unicidad,paths,no-follow,allowlist,espacio,
  fingerprint esperado,clasificación y`.build.info` alfinal;no vuelve a hashear
  todoelstaged cuando existe la admisión anterior válida deesa mismaoperación.
- Mantener EXACTO orden de forces pre-journal yjournalACTIVE durable ANTESde
  rename. Seeliminan relecturasSHA duplicadas,no las barreras deescritura.
- Antesdecadamove,revalidardestino ybinding dearchivo/namespace;si elpathcambió,
  rechazar. No asumir que `FileChannel` proporciona por sísolo vínculo path↔FD:
  cerrarsu diseño concreto,usando APIs públicas cuando corresponda, sin comparar
  `fileKey` opaco condev/ino ni usarFdviejo para autorizar pathnuevo.

**C. Rename + UNA comprobación deldestino (pasada2).**

- Renames atómicos enmismo filesystem,no copias de90GB.
- Porfichero renombrado:force directorios,abrir CURRENTTARGET no-follow,validar
  binding/tamaño yreleerSHA-256 completo contra la huella staged admitida.
- Registrar avance durable poroperación solo conlas barreras apropiadas;no
  contabilizar unposthash fallido como completado. Conservarsemántica compatible
  deljournal,especialmente lostests decrashantes/despuésderenames.
- **Publicar `.build.info` alfinal, DESPUÉSdeverificartodoslosdemásarchivos
  publicados.** Lease/journal/runtimegate impiden usarunclienteparcial mientras
  losdatos están eneldestino perotodavíapendientes de comprobación.
- La comprobación delmarker propio también debe pasar antesdeCOMMITTED. Hasta
  entonces no estadoDone/clienteinstalado ni permiso delanzamiento.

**D. Limpieza sin tres barridos de90GB.**

Tras recibirprueba detodaslasoperaciones enESAejecución,cleanupvuelve a probar
identidad/namespace/journal/marker yownership deentradas quevayaaretirar;no
repiteSHA deTODOSlossegmentos. No borrarbackups/originales porqueunflag diga
verificado;loschecks de borrado propios deupdates/recovery conservanrutaanterior.
No borrarreemplazosdesconocidos ni ejecutarreset para encubrir discrepancias.

### 5. Fallos yrecovery (no confiar en prueba guardada tras reinicio)

| Corte/estado | Conducta requerida |
|---|---|
| AntesdejournalACTIVE | Sinpublicación;stagingconservado;no reconstruir prueba apartirdefechas/tamaños. |
| DespuésdeACTIVE,antesdemove | Recoveryvalida journal/staged/target conhashesreales;no pruebaefímerareutilizable. |
| Despuésdemove,antes/duranteposthash | Progreso journal suficienteparareconocerestado;activaciónbloqueada,bytes/journalconservados. |
| Posthashmismatch | Fallarcerrado,preservar evidencia;no `.build.info`/COMMITTED,norepair/reset ciegos. |
| Crashtrasposthash/opphase | Nuevo proceso revalida bytes actuales:phase2no demuestra ausencia debitrot. |
| COMMITTED alreanudar | Mantenervalidaciónestricta recovery/cleanup anterior,nocleanup rápido porelestado persistido solo. |

No afirmar que todoslosmismatchespermiten rollback automático:instalaciónfresca
write-once sinbackup puede quedar bloqueada. Elcaminoactual ya tiene límites;
elcambio no autoriza borrados ni redescarga total. Reparaciónlocal/topuprequieren
su propiocoste/consentimiento. Preservar bugno-op/aplicación debytesidénticos como
problema separado;no solucionarlo indirectamente retirando guardas deborrado.

### 6. Observabilidad para no volver a esperar horas sin fase

Separarprogreso ylogagregado:admisiónSHAstaged,persistencia/journal,publicación+
verificacióndestino ycleanup. Informararchivos/bytes comprobados frentealtotal,
no objetosdedescarga yaal100% ni porcentajefalso. Mantener protección contra
pausa/cierre enventanas atómicas,sin convertir elaviso enpermiso deinterrupción.
Timers/counters dehashbytes porfase deben permitir auditar presupuestodelecturas.
No logs porbloque,ningúnsecretopathcredential,nuevoframework de telemetría ni
APKsolodiagnóstica. No publicarjournal interno `durableBoundary` como si hoy
fueseautomáticamenteunlogcat deproducción:actualmenteesuncallback defaultpoints.

### 7. Criterios de aceptación yverificación proporcional

- FRESHnormal:≤2lecturascompletasSHA debytesCASClocales duranteFINALIZACIÓN
  (admisión+readback). No contarfulloficialcrypto de descarga/replay comoeliminado.
- Igualdad decontenido ycostesfinales,mismaidentidaddemedio/ownership ymisma
  exclusiónWine;ningúnclientelisto antesdetodoelposthash ymarker final.
- Mismasbarreras force/journal yrecovery conhashes tras muerte,reboot oerror.
- Memoria/FDacotados,ningunacopiadeCASC ni nueva lecturaoculta enhelperocleanup.
- Objetivo experimental≥70%menosbytesreleídos enfinalizaciónnormal(8→2=75%
  teórico). NOgarantiza75%menostiempototal;medirtiempodefase yhastaReady.
- Tests focales sobrefixtures Transaction existentes:presupuestobytes2pasadas;
  planmutado/archivo sustituido;stagedcorruptotrasACTIVE;targetcorruptotrasmove;
  corte encadabarreraactualdurableBoundary;recovery ignora pruebaefímera y
  revalida. Añadir≤6grupossignificativos,no matrizexhaustiva ni nuevosharnesses.
- Revisiónindependiente de data/security/migración,build/audit único yprueba
  física consensuada solo cuando la operaciónactualestéenestadoseguro.

### Protocolo concreto para revisión previa a implementación

- Elegibilidad inicial conservadora: operación fresca sin fuente instalada;
  TODOS los payloads de la transacción tienen `expectedOld == null`. Si algún
  loose/índice/config/marker requiere reemplazo, usar ruta estricta ANTES de
  admitir el plan; no mezclar un fast-path parcial con backups sin probarlos.
- Una entrada interna de Transaction admite los staged y calcula los SHA una
  sola vez. Updater no ejecuta antes `inputFile().sha256File` sobre esos mismos
  segmentos. Los callers de `plan(InputFile...)` previos mantienen ruta estricta.
  La forma exacta debe evitar nuevas opciones públicas/frameworks: archivos
  staged+paths admitidos, no unhash fingido ounboolean de confianza.
- El plan rápido pertenece a ESA instancia y se liga a una copia privada de la
  lista de operaciones/allowlist/stage/owner/identidad. `TransactionPlan` actual
  no es data-class; aun así, sus colecciones internas no deben poder alterarse
  por alias/cast. Rechazar otro plan/copia/metadatos cambiados; no rehabilitarlo
  con un fallback automático. Solo una admisión/plan vivo por operación.
- No FDs retenidos de todos los segmentos. La huella esperada proviene de la
  primera lectura staged; cada destino se hashea tras rename yantes delmarker
  final. Guardas de binding/path son adicionales,NOprueba de contenido inmutable.
- Recovery usa SIEMPRE elcamino estrictoactual, aunque eljournal venga deeste
  fast-path; fases,hashes yformatose conservan. Una prueba enmemoria no sobrevive
  muerte/reinicio ni se reconstruye dephase2/COMMITTED.
- La instalación actual C117 NOseusa como ensayo de esta modificación mientras
  esté aplicando. No cambiar APK/estado/recuperación para acelerar estaejecución.
- La observabilidad no puede desproteger el commit: mantener la semántica
  atómica de `Phase.APPLYING` (UI/notificación/Pausar/Volver) durante journal/
  moves/readback/cleanup. Añadir detalle/progreso real, no fases nuevas que hagan
  que `isAtomicPhase()` habilite interacción insegura en mitad de publicación.

### Precisiones tras revisión independiente

- ReviewAPTOcondicional:2lecturasbulk son viables,nose requiere tercera lectura
  si elprotocolo se implementa como se describe. No se admite `ownerToken`solo
  ni supuesto `old==null` deunaúnicaoperación como elegibilidadglobal.
- GateUpdatersinfuente/rootvacío lógico,buildmarkerprevioausente,ningúnconfig
  reutilizadofueradeinputs yloose sinreemplazos. EntryFreshdeTransactionexige
  journal/tmpausentes,todoslostargets ausentes,oldnullentodosyexactamente1marker
  último. Cualquierpayloadpreexistenteantesdeelegir fast→rutagenéricaestricta;
  discrepancia después deadmisión→rechazo,no fallback queblanquee elcambio.
- Nueva admisión interna de staged calcula pasada1. Colecciones defensivas y
  fingerprintcanónico privado delplan(ops/allowlist/stage/identity) más identidad
  deinstanciadelobjeto emitido;unplanvivo,unsolouso. Copias/mutacionesrechazadas.
  `InputFile.sha256` decaller no sirve comocapacidaddehaberpasadolalectura.
- `phase=2` deljournal continúa significando RENAMEREALIZADO,no «hashverificado».
  Mantener escritura dephase yforces porbarrera como hoy;éxitodeposthash queda
  solocomopruebaefímera poroperación.No COMMITTEDsi hayfallo.Crashentre rename,
  phase2yposthash se recupera conelalgoritmoestrictoactual,quelee bytes reales.
- La validaciónfinal depublicaciónes la conjunción deTODOSlosposthashesporarchivo
  más markerúltimo;NOotra llamada al `verifyPublished()`globalqueañadatercera
  pasada. Si se reutiliza sunombre,debeaceptar pruebaemitida/no releerpayloads en
  fastnormal;la función/rutaoriginalde recovery mantienehashesestrictos.
- Cleanupfresh revalidaidentidad/namespace/journal/stagingmarker/targetbindings,
  no backups inesperados,ningúnunknownborrado yhashpequeñode`.build.info` antes
  de borrarlojournal. Repetirhash deese control pequeño no cuenta comobulkCASC.
  Pruebaenmemoria nunca habilitacleanup rápido enunarecuperaciónposterior.

## Implementation / Action Items

- [x] 1. Aprobar objetivo2pasadas,alcancefresco yconservación de forces/recovery.
- [x] 2. Cerrar protocolo de pruebaefímera/admisiónúnica/pathFD ymarkerlast;
  reviewindependiente ANTESdecódigo. Si2pasadasnopuedendemostrarse seguras,
  presentaralternativa3yquébarreralaobliga;noimplementarlo porintuición.
  Revisión APTOcondicional: B1–B4 incorporados como requisitos verificables del
  código (gatefresco completo,planemitido privado,phase2=rename/recoverestricto,
  cleanupbound+marker pequeño sinbulk). No tercera lectura global escondida.
- [x] 3. Implementaciónoffline mínima enUpdater/Transaction/progreso ytests,
  conservandorutasgenéricas/updates/recovery estrictas. Pins/allowlists históricos
  autenticados;noeditarbaselinecongelado nidrivers dedispositivo previos.
  Builder único iniciado sobreTransaction/Updater/tests/UiText para evitar
  interfaces de confianza editadas enparalelo. C117 enmóvil no modificada.
  Prueba de bytes debe abarcar Updater+Transaction,no sólo contadorTransaction.
  Primera implementación pasó37checksdirigidos(16Tx+4UiText+2Updater+6FGS+9UX),
  peroNOaceptada:parentinspeccionócommitFast→fastRollForward(allmovesinclmarker)
  →fastVerifyPublished(allsweep) ydetectóviolaciónmarkerlastAFTERpayloadposthash.
  Elbloqueo runtime/COMMITTEDnobasta paracumpliresecontrato. Se pidepublicar+
  verificarporarchivoantesdelsiguiente,yBInfosólotrasTODOSpayloadscomprobados.
  Tests negativos debenexigirBInfoAUSENTEendestino/marcadorpresenteenstage ante
  hashfallidodepayload. Sin nuevaslecturasbulk/forcesdifferent;recoverystrictigual.
  ReviewWIPhabíapasadoincorrectamenteB3conCOMMITTED-only;padrecorrigeinforme.
  Laexcepcióncross-instance genéricaIAE escomportamientoestrictoprevio:no cambiar
  códigolegacy sóloporuniformaruntestnuevo. First37GREENnoevidenciafinaldeaceptación.
  Reparación marker implementada:publish+posthash porarchivo yBInfosóloposttodos
  lospayloads,verified:<target>comofaultseam,no fasepersistidanueva. Corridafinal
  afectada18checks(16Transaction+2Updater),fallos0/errors0/skips0,XML15:11:03/05,
  repair-run.log59s. Padre comparó6repo/stagebyteidénticos;hashTx
  4648a7b9938867d3d6c19a09b8ac9f91b36f194c8383a8173016a246a55da72e,
  TxTest978e5104a214c7f3443689691fc680d24c00728fe35b70a60d540b5415e640c4.
  RestoUiText/FGS/UX verdeenprecedentecorrida37,no repetirnisumarcomosuiteúnica;
  desglose de37 iniciales reportado(16+4+2+6+9)suma37,las clases fuera de18
  suman19,nolos15delrelato delbuilder. Cierre review reparaciónpendiente.
  Coordenaciónpackaging:reconstructoractual yareservó118/0.2.94-remote-diagnostics
  portrabajoconcurrente ajeno. NOsobrescribirversión/reservani bundlear supuestos
  diagnósticos comopartedeesta tarea. Candidatafinalizaciónsinnúmerofijado aquí,
  despliegueademásbloqueadomientrasoperaciónC117 sigaaplicando.
  ReviewfinalsobrecódigoreparadoAPTO,sinbloqueos:markerfísicoúltimotrasposthash
  deTODOSpayloads ypruebasnegativasloexigen;cross-instanceconservaIAEestricta,
  sinrelajartest/cambiarAPIlegacy.Proofprivada/eligibilidad/progresoatómicoy
  recoveryestrictopreservados. Implementaciónofflinecerrada;build/auditAPK y
  pruebafísica futura aúnpendientes.No atribuir75%tiempomejoradoporesteverde.
- [ ] 4. Verificaciónfocal/review,conteo debytesleídos ybarrras de crash;unbuild/
  audit. Sin repetir suitegeneral nireinstalar para resolverpreguntasdecódigo.
- [ ] 5. Tras autorización yestadoseguro:instalar,medirunafinalizaciónreal,
  registrarcontenido/bytes/tiempo/recovery yactualizardocumentación.

## Open Issues

- **Decisión de despliegue2026-10-20:** usuario confirmaentender ysolicita dejarlo
  registrado. EninspecciónsololecturaC117/PID11675 seguíaforeground; journalACTIVE,
  108/144opsphase2,36phase0,buildmarkerphase0. Sonrenames,no75%tiempo.
  NOsustituirAPKduranteesta publicación:install-r terminaelproceso ylaotraAPK
  debe recuperar estrictamente, sin pruebaefímeraválida para saltarselecturas.
  No ahorro retroactivo ni hotpatch. Preparar/buildofflineesposible,perodespliegue
  depende deestadoseguroautorizado. No nueva instalaciónfresca/borradodelcliente
  sólo para probarvelocidad sin destino/coste/consentimiento explícitos.
- Usuario aprueba «hazlo»: cerrar diseño/review eimplementar offline. C117en
  aplicación no se modifica;esto noautoriza pausa/reinstalación ni cambiar la
  transacción en curso. Noformato nuevo ni atajos de recovery.
- Hayqueligar la pasada1aUNAautoridad:dejarinputFilehash+planhash=3pasadasal
  añadirreadback,no cumpleobjetivo2. La API actualno basta porrenombrar métodos.
- Seconocetopologíade8calls,NOcuántaspasadasya completóhoy eldispositivo. No
  parar laoperación porunaestimación.LarutaobservadaestáantesdejournalACTIVE.
- Recoverydeunhashmismatch trasrename puedequedarbloqueado;semánticafailclosed,
  conservaciónyconsentimientodeeventualrepair deben conservarse explícitas.
- La pruebaefímera solo existeenmismaoperación;ni flags,valoressize/mtime,CRC,
  journalphaseorecibos sustituyenlecturadecontenido trascierremuerte/proceso.
