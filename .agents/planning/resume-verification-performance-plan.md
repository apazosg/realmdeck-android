---
id: resume-verification-performance-2026-10-19
state: in-progress
date: 2026-10-19
---

## Summary

**Corte 2026-10-19:** experimentoC110 terminado ysuperado porC111/C112;el usuario
aparca mejoras. GatefísicoPasóenapp;replayC110quedó~33%menorwall a prefijoigual,
noel50%buscado. ReanudaciónsolapadaposteriorC111yplanificadorC112yaestán
instalados. Referenciaactual: [docs/downloader-handoff.md](../../docs/downloader-handoff.md).
Las restricciones/estadosanteriores deesteplan sonhistóricos,no órdenes para
reinstalarC110otomarotraAPKdiagnóstica. Transferenciaactualnomodificada.

Usuario pide estudiar cómo reducir la verificación tras cierre/pausa mientras
C109 avanza yse mide. Este plan NOinterrumpe esa prueba ni autoriza otraAPK.
Objetivo experimental:reducir≥50%el coste del replay a igualdad de prefijo/bytes,
sin perder hashes,recuperación ni reutilización de actualizaciones parciales.
Una pausa viva yuna recuperación tras muerte del proceso son casos distintos.

## Content

### Evidencia

- C108:324.662objetos/25.614.228.707bytes encoded,writer hasta nuevo source
  3.631,725s(~60min32s). C109retiene401.259objetos/31.585.607.500bytes;su replay
  aún pendiente. Comparar por objeto Ybytes,no tiempos de prefijos distintos.
- C107primer4.096:totalVerify38.735ms;openHeader24.057ms(~62%),copy7.215ms,
  BLTE5.925ms,EKey754ms,cleanup465ms,index342ms. OpenHeader agrupa preparación
  del temporal,format/guards/open/size/cabecera/validación,NOes latencia puraUSB.
- verifyExistingIncrementalObject copia cadaobjetoUSB→temporal privado ydespués
  verifica EKey yBLTE desde ese temporal. El índice no es el coste prioritario.
- Pausar hoy lanza cancelación cooperativa,desmonta operación/cierra recursos y
  libera leases/FGS(ForegroundService:76,140-165). Reanudar reconstruye writer;
  conservarlo vivo no es un simple cambio de texto ni sustituye recovery frío.

### Candidato prioritario: replay frío acotado por segmento

1. Mantener orden actual del manifiesto/checkpoint. NOagrupar/reordenar por
   archiveId/offset. Un único lector activo,reabierto al cambiar de segmento;
   posicionar por offset cuando no sean contiguos. Sin lista global/FDcache.
2. Evaluar apertura Android pública Os.open(O_RDONLY|O_NOFOLLOW),Os.fstat(fd) y
   Os.lstat(currentPath) para probar vínculo path↔FD antes ydespués de CADAobjeto.
   Comparar st_dev/st_ino,tipo regular/tamaño yguards de padres aplicables;un FD
   antiguo válido NUNCAautoriza un path sustituido,borrado osymlink. No equiparar
   Files.fileKey opaco con st_ino. Sin root,JNI,hiddenAPI,Unsafe nireflexiónFD.
3. Verificar el rango exacto [offset+30,encodedSize) mediante stream acotado,
   enun pase para alimentar EKey yparserBLTE,sin materializar/copiartodoelobjeto.
   Mantener cabeceraCASC30bytes/checksums/prefijo/fullEKey,flags/tamaños,regla
   EKey(headerBLTEchunked vscontenidoenteroheaderless),frameMD5,CKey/decodedSize,
   trailingbytes,cotas/cancelación yframesE autenticados igualque hoy.
4. Stream no puede consumir el siguiente objeto ni ocultar truncados/errores
   comoEOFnormal;cancelación/earlyreturn/error/fin siempre cierran el lector.
   Sin RAM deobjeto/segmento entero,buffer acotado reutilizable.
5. Cambiar sólo replayflags2 reanudable al principio;legacy/source-existing y
   rutas que reutilizan candidatos conservan implementación actual. Nada de
   hashes persistidos confiados porCRC/mtime/tamaño,ninuevosformatos checkpoint.

Eliminar temporal elimina opens/copy/write/cleanup privados;un lector puede
reducir aperturasUSB,pero sus guardas nuevas también cuestan. NOhay porcentaje
de ahorro demostrado. No presentar este candidato como única vía posible a50%.

### Pausa viva (opción secundaria,no implementación incluida)

Estudiar conservar continuación del writer tras pausa sin descargar/verificar en
background,con memoria/FD acotados yliberando locks de energía. Requiere definir
leases,selecciónUSB,datos modificados durante pausa,FGS/límitesAndroid yqué ocurre
si muere el proceso. Nada de conservar confianza porque el proceso siga vivo.
No sustituir la optimización fría ni cambiar semántica de pausa sin aprobación.

Hallazgo de arquitectura:la pausa actual cancela el servicio ytermina execute,
cierra catálogo/writer ylibera lease/locks. Una pausa viva necesitaría retener
iterador/índice,detener realmente productor+prefetch sin reservasfantasma,
definir si mantiene lease(bloquea cambioUSB) yFGS/notificación,yliberar energía.
Android puede matar ese proceso de todosmodos:si ocurre,se vuelve a recoveryfrío.
No se elige como primera implementación:atraviesa concurrencia/UXymantendría sin
resolver cierre/reinicio. El diseño prioritario es lector+verificación fría.

### Límites y decisiones

- No tocar C109 ni ejercer cargaUSB durante su ventana de medición. Primero
  diseño/revisión offline;prueba pequeña deidentidadFD después deesa ventana,
  sólo si seautoriza,sobre scratchpropio fuera del cliente/checkpoint.
- Os.*públicodesdeAPI21 resuelve acceso a fd sinreflexión,pero su identidad bajo
  FUSE/vfat DEBEprobarse enAndroidreal. FUSErw no prueba rw físico;no quitar
  validación de destino ni interpretar un lstat como garantía global atómica.
- No ampliar ventanas relevantes respecto avalidación actual. Si no se puede
  probar binding oFDmetadataesinestable,rechazar modo,NOdegradar silenciosamente
  un fallo activo aFDcache/permisivo. La ruta vieja sigue disponible como modo
  elegido antes de usar optimización,no como bypass tras detectar sustitución.
- Coste de portabilidad explícito:Casc/BLTE hoysonJavaNIO. Decidir frontera mínima
  deAndroidOs sin propagar dependencias innecesarias al parser/hash genérico.
- Ningún cambio deHTTP,prefetch34/1GiB,fsync/checkpoint/dirforce,journal,WTF,
  consentimiento,nifallbacksilencioso de almacenamiento. No borrar datosdurables.
- CPUformat/hex es alternativa menor,no mezclar refactor general ni prometer50%
  con ella. No nuevaAPKdiagnóstica antes de tener candidato justificado.

## Implementation / Action Items

- [x] 1. Aprobar alcance de estudio/diseño;documentar algoritmo de stream,EKey,
  límites ybindingFD/path,portabilidad ycasoswarm/cold separados.
- [x] 2. Revisión independiente de integridad/seguridad;resolver gaps antes de
  proponer código. Presentar diseño concreto para aprobación de implementación.
- [x] 3. Tras autorización,C109medida ynosininterferir:comprobar identidadOs/FUSE
  con prueba física pequeña/propia ylimitada.No modificar datosdelcliente.
  Precondición mediciónC109cerrada:1,22263MiB/sforeground y1,74422MiB/sfondo,
  sinerror. Replay401.259objetos4.728,656s(~78min49s). Gatefísico aúnNOrealizado,
  falta método ennamespace demostrado/autorización sininterrumpirdescarga.
  Método acordado yautorizado porusuario:«si,hazlo si es necesario» traspropuesta
  depreflightintegradoC110. ADBrun-as namespace mnt:[4026533766] distinto deapp
  PID13009 `mnt:[4026535982]` (artefactoexterno `c110-probe-namespace-check.txt`),
  no prueba equivalente.
  Incluir sonda acotada enMISMAC110,enexecute dentrolease antes denuevowriter:
  scratchUUIDnuevo app-specific fuera deCustomGames/Data/checkpoint,revalidate
  antes/después,misma implementaciónAndroidreader. Identidadestable,preadposicional,
  rename-swap/unlinkrecreate conFDviejo deben pasar/rechazarse como corresponde.
  No estadoPASSdurable niTTL:gateporinvocación ymedio admitido. Sólo trasPASS
  asignar factory aengine;cancelación/fallo detienen operaciónsinmodificardatos
  cliente,nifallbackpermisivo. Targetsnoelegibles siguenoldmodefijadoantesdewrite.
  SymlinksonUSBvfat puedennoestarsoportados:NOexigircapacidaddecrearlosenFAT.
  Diseñar pruebaNOFOLLOW aislada enfilesystemprivado app + rechazo de symlink
  USBsólosiesrepresentable,sin inferir inexistenciaUSB de un errorgenérico.
  Cleanup sóloentradasexactascreadas deeseUUID,sinrecursive delete sobreárbol
  ajeno/nilogs secretos. Revisar implementación ytests antesdebuild/audit/update.
  No instalar versión intermedia sólodiagnóstico;C109 sigue hastaentregavalidada.
  Gate integradoimplementado:preflighttiny4KiB/scratchUUID appfiles+privado,
  mismoOsreader paraidentity/pread/swap/unlink,NOFOLLOWprivado conELOOP.
  Activaciónfactory sóloenService.execute trasPASS+revalidate,fallospropagan.
  4tests protocolo hostverdes(no físicos),K2API36 compila gate;compilaciónslice
  Service no cierraupstream(285símbolosajenosfaltantes),NOequivaleabuildintegrado.
  Revisión independiente en curso,antesdeAPK. Padre identifica riesgos deownership
  porregistrarpathantesdeCREATE_NEW ydeclosefinallymaskingprimary;exigir cierre
  antesdeinstalar. Evidencia arm64-prototype/c110-preflight-check/manifest.json.
  ReviewgateBLOQUEAprimeraimplementación:paths premarcadosantesdeCREATE_NEW
  podían borrarforeigntrasEEXIST;closeReaderfinallypodíamaskear cancel/primary;
  readAllBytes trasstat no estabarealmenteacotado. Reparaciónfocalenbuilder:
  tracking sólotrascreaciónexitosa,partialwriteownercleanup,renameB→Aambospropios
  elimina backupunknown(ATOMIC_MOVE no garantiza noreplace),cierreconprimary/
  suppressed ylectura4096+1máximoNOFOLLOW. Añadir pruebas negativas significativas,
  no frameworks. NingunaAPKnihardwareprobe realizada antesdelevantarestosbloqueos.
  Reparaciónfocalimplementada;10tests gatehost verdes/0fallos/0errors/0skips.
  ProbeSHA8b6da0ae4422a6a2924a55b3987478f55250e0e3882a265cbfdc5244177ef6a2;
  Test505c4940aba7f89e68d30095de9a07b482f9b1343095abd778f8d561ed3e66d0;
  adapter9ec7d5df136d04a4b5bdb50bb3f8069a0b8550945ddfd57c87dd995226d9f556;
  Service569d519657d66777c2bdf6f809f382b5225ade9de1a2a6b4b2e24555352e5bc4.
  Revisiónfinalreparación ybuildGameNativecompleto/auditoría en paralelo;
  NOinstalarhastaambosAPTO. Compilaciónslice285erroresNOseaceptacomoappbuild.
  ReviewfinalgateAPTO:ownershiptrackaftercreate,EEXISTforeignpreservado,
  close/cancelprimarysuppressed yreadacotado verificados;sinbloqueoscódigo.
  Sepermiteinstalar MISMAC110 sólodespués build/auditPASS;su preflight físicose
  ejecutaDENTROdeesaAPK antesdeactivarfactory(no pedirPASSdispositivoantesde
  instalarlo,sería circular). SiFAIL/CANCELLED,no replaynuevo nireintento ciego.
- [x] 4. Tras diseño aprobado,implementar cambio mínimo ytests focales:equivalencia
  N/Z/E+headerless/chunked/corrupción/truncados/bounds;swap/unlink/recreate/symlink
  con FDviejo;cancel/close;regresión source-existing/checkpoint,tamaño de memoria.
  Reusarfixtures/harness existente,sin matricesexhaustivas ni suitetotal.
  Implementaciónoffline iniciada en2áreas conownership excluyente:Blte+RangeTest
  (parserúnico/EKey) yCasc/Test+Updater+adapterAndroid(lector/bounds/lifecycle).
  Interfaz verifyEncodedRange(source,N,expectedEkey,expectedSize,CKey,cancel)
  fijadaantes deparalelizar. Una property factory interna por instanciaUpdater
  evita exponer interfaz interna enconstructor público;defaultnullmodoanterior.
  AndroidService NOactivala factory hasta gatefísico,nuevaAPKno se instala.
  Parser/rango pasó primera corrida6nuevos+3existentes,pero inspecciónpadre halló
  bug de digest conlecturasfragmentadas:plain decidehashNalos4bytes ybytes5..8
  podían quedarretenidos sinhash. Se exige regresión deunderlyingreadbulk que
  devuelve1byte yreparaciónantes de aceptar;losverdesiniciales NOcierranparser.
  También restituir guardexpectedSize antesdeemisiónprefijo4bytesplain yrechazar
  lecturapositivasinprogreso,sinbucle. Reparación focaloffline,noC109afectada.
  Reparación cerrada:8testsRange/0fallos/0errors/0skips,XML09:26:33;3testsBLTE
  existentes verdes enversión previa (no afirmar suite única final de11).
  MainBLTESHA4a19ccb993fe4e9545abd57d08bd9a32e524d8c556c4483e1972111a7209c8c6;
  RangeTest1e3962d3fb6fef666577215b3fb7daa9e080186d77aeefbcdc79ab697783b209.
  Revisión independiente parserAPTO,sin bloqueos. Pin depostimagenactualizado,
  preimágen/baselinecongelados. Integraciónlector ypruebasCasc todavía pendientes.
  Ojo:laextraccióncore afecta también wrappersFile enunafuturaAPK,aunque lector
  nosecablee;las pruebasdiferenciales/Fileyrevisión cubren esecambio. LaAPK C109
  actualmenteinstalada sigue byteintacta,no recibe cambios deárbol automáticamente.
  IntegraciónCasc/Updater+adapterAndroid implementada,sinwiringService;factorynull
  mantiene ruta anterior. Builder informa4nuevosCasc+4regresiones verdes,K2core/
  adapterandroid.jar36/closureUpdater compilan contraBltefinal. Padre pide rutas
  exactas/logs/hashstage porque clonretenido parece copiarepo,noGameNative como
  seinformó:verificaciónevidenciapendiente,no asumir buildAndroidintegrado/Gradle.
  Revisión independiente reader/lifecycle en curso;padre detectaposible skip de
  cleanup original si nuevoclose lanza,pendiente diagnóstico/fix antesdeaceptar.
  No hay APK nueva ni evidenciaFUSE física ni ahorro de rendimiento acreditado.
  Evidenciaclarificada:elclon es copiarepo completa,no retenciónGameNative. K2
  compila fuentescore/test enunmódulo yadaptercontraandroid.jar36;NOGradle/APK.
  Padre comparó5fuentesstaged byteidénticasyclassesall09:30:03/adapter09:30:30/
  updater09:31:29 coherentes. BuilderNOretuvo stdoutJUnit/logs/XML de8runs:hay
  relato deéxito yclases,NOlogsretenidos. Se exige guardar comprobacionesfocales
  restantes tras corrección decleanup,nopresentar eseevidenciacomoXML niAndroid
  integrado. Paths .replay-test-out/{all,adapter,updater,runner,cp.env,kccp.env}.
  ReaderreviewAPTOcondicionalcon1reparaciónobligatoria:unfallo closeReplayReader
  podía saltararchive.close/tempcleanup;se corrigecon acumulación/suppressed y
  ejecuciónincondicional de limpiezas. También partial-openclose secundario se
  retiene suppressed ycomentario deFDseajusta(no promesaABA). Reparaciónenbuilder
  original;ahora guardarcomandos/logs/hashmanifestcore+adapter+Updater ycorridas
  focalesafectadas. No repetir suitesgenerales ni implementar otroharness.
  Reparaciónfinalverificada ylogsretenidos:K2core BUILD_CORE_OK,7checksJUnit
  runCount1/failures0/ignored0cadauno(4reader+closefail+resumeviejo+sourceexisting),
  ADAPTER_BUILD_OKandroid.jar36 yUPDATER_CLOSURE_OK. Padreleyómanifest/log yhashes
  coinciden:Casccore4e84d6343f77ef68bdd842ff17ccc22eae2924477dd6b53e1ea54390c5e8e7c7,
  Testafc2ae62a63863f49f80de1253467d4f13f7f3bee6f361e47d7ec1dd48965e76,
  adapter5634d3aa88e10f4c79a320424e91def41d654d4ce140df22c948dbd40d7d5c6d,
  Updater277518fa7847156172d9db49e5d275c9a2f5fbe15badd0680afd0a71effb2f58.
  Evidencia arm64-prototype/replay-reader-tests/{verification-final.sh,
  verification-final.log,manifest.json};no GradleXML niAPK enesaprueba.
  Pinsfinales ylistas explícitasconadapter/RangeTest preparadas para candidata
  110/0.2.86-streaming-replay-gated,sinbuildAPK/activación.2tests focalesPython
  reconstructorverdes. Revisiónfinal de reparaciónAPTO,sinbloqueos offline;
  cleanupcompleto,primario/suppressed,cancelación yFDdelta verificados.
  GateFUSEintacto:protocolo host yAPIcompile NOprueban bindingOs enUSBAndroid.
- [x] 5. Revisar diff ypruebas,build/audit;decidir instalación sólo con ahorro neto
  previsto frente apreparación/replay adicional ydatosconservados.
  Usuario autoriza «instalalo yvemos». BuildGameNativeC110completo4m43sPASS;
  primer auditfallóSÓLOpor2descriptoresnuevos ya revisados(interfaceCascReplayReader
  yCascReplayBindingMismatchException). Se añadieron EXACTOS alallowlistDEX,
  testfocalacepta2/rechazaReaderUnexpectedPASS,sin wildcardrelajado. SinrebuildAPK
  innecesario:auditoría repetidaPASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT,2184fuentes
  retenidasbyteintactas/178payloads. APK SHA256
  3b372f8d8a7e45c5f86d767471612472103bec314783f93f3052967052786f9b.
  Instalación+preservación+preparación+confirmación+medición encadenadaslanzadas;
  no afirmarinstalada aún. Driverintentoúnico/no reintento automático,sierror
  preservarestadoydiagnosticar. Observadorinotify deinstalación tambiénlanzado.
  Instalaciónconfirmada110:468.177objetos/37.044.580.827bytes encoded conservados,
  checkpoint/recibo byte-idénticos,0workers/FGSparado antesdeupdate,tailparcial0,
  objetoEretenido. Evidencia c110-installed-preserved.json ycapturasbefore/after.
  Cadena prepara/confirmación/reanudación sigueactiva;observereventopreflight
  lanzado. Gatefísico NOconfirmado aún;no afirmarlectorrápido activo ni50%ahorro.
  GatefísicoPASSenappPID15171,13:11:21.895dispositivo:elapsedMs366,probeBytes4096.
  EjecutósondaOs ennamespace real sobre scratchpropio,antesdeusarreader.
  Selevanta gatecapacidad paraesainvocación;NOdemuestraperformance/reducción50%
  niatomicidadABA. Activación efectiva se confirmará con mode=replay-reader.
  Activaciónconfirmada enlogs mode=replay-reader. Muestra4.096totalVerify41.606ms
  NOesmejorqueC10738.735ms(no elegir sólo primertramo). Comparaciónparcial más
  ampliaC109/C11094.208objetos:framescheckpointprefijo byteidénticos,
  totalVerify1.020.422→678.759ms(~33,48%menos dentroverificador),no walltotalni
  resultadode468.177objetos.NocontrolA/Bdecondiciones/cache. Evidenciaexterna
  c110-partial-replay-comparison.json;seguirensayo sinpausas ni declarar50%.
- [x] 6. Medir replayfísico normalizado ydescarga posterior;informar beneficio,
  ausencia de él,límites ypendientes. No declarar reanudación instantánea fría.
  C110completa:REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED,error=null;
  preflightPASS,mode=replay-reader. Primer source14:17:19.411dispositivo,
  468.177objetos verificados,elapsedwriter3.694,940s(61min34,94s).
  Prefijo COMÚN400.384objetos(framesCPbyteidénticos):C109verify4.454.223ms→
  C1102.891.235ms(~35,09%menos);writer4.718.889→3.161.595ms(~33,00%menos).
  Caché/condicionesdeejecucióndistintas,noA/Bcontrolado. Objetivo≥50%NOcumplido.
  Descarganueva:733.113.088bytes/9.575objetosen597,821s=1,16950MiB/s;
  fondo185.197.889bytes/2.205objetosen120,631s=1,46412MiB/s. C1091,22263/
  1,74422MiB/s:−4,35%/−16,06%,tramosdistintos,noatribuir regresióncausal allector
  nianunciarmejoradescarga. RSSforeground177.604–188.180KiB,fondo188.872–191.772,
  objetoEconservado,sinerror. Evidencia c110-comparison.json yphysical-result.
  Descargacompleta/integridadfinal/activación pendientes. No nuevapausaAPK;
  investigación degranreducciónen fast-resume-redesign-plan.md pendienteaprobación.

## Open Issues

- Usuario aprueba avanzar con implementación/pruebas offline tras el diseño
  revisado yel cambio de flujo de trabajo:«venga adelante entonces». C109sigue
  intacta. Se adelanta paso4(offline) respecto al3(físico);el nuevo lector queda
  SINACTIVAR enproducción hasta gateFUSE ydecisión deinstalación. Ninguna sonda
  niAPK adicional autorizada por este paso. Sin más rondas diagnósticas completas.
- Os.fstat/lstat disponibles públicamente no prueban por sísolos equivalencia
  deFUSE ni seguridad de apertura;hay que cerrar el protocolo exacto devalidación.
- Integrar EKey+BLTE enun pase requiere conservar todaslas reglas deEKey yEOF;
  APIstream/longitud ytestdiferencial aún pendientes,no confiar eninput.length.
- Cold recovery debe seguir leyendo contenido autenticado:latencia mínima ligada
  al volumen debytes,no prometersegundospara31,6GB. Pausa viva podría evitar
  reconstrucción,no la validación necesaria tras muerte delproceso.

## Decisiones de arquitectura en revisión (sin implementación)

- NOduplicar el parser BLTE:estudiar extracción mínima de un núcleoInputStream
  compartido,con wrappersFile existentes/guardas conservados ytests diferenciales.
  PlainnoBLTE se admite hoy;el prefijo de4bytes leído debe contarse enCKey/tamaño,
  no omitirse. No confundirlo conzero-headerBLTE.
- La regla de longitudEKey se decide conlos8primerosbytes(magic+headerSize);
  un wrapperdigest puede retener≤8bytes yhashearN(headerless/plain) osóloheader.
  Flags/count/descriptor se validan UNAvez enparsercompartido;cerrar equivalencia
  deconstantes/cotas conencodedEkey viejo antes de aceptarlo. Sinpeek12obligatorio.
- Hallazgo AndroidAOSP15:FileInputStream(FileDescriptor) llama alconstructor
  isFdOwner=false;suclose NOgarantiza cerrar ese FD. Se descarta el diseño que
  asumía ownershipautomático. Fuente libcore/ojluni/.../java/io/FileInputStream.java
  ramaandroid15-release,constructor yclose inspeccionados por padre.
- Alternativa propuesta:adapterAndroid usa EXCLUSIVAMENTE Os.open/fstat/lstat/
  pread/close sobre unFD propio. Os.pread(fd,byte[],off,len,position) es público;
  sourceAndroid15 libcore/luni/src/main/java/android/system/Os.java verificado.
  Parser/Casc no reciben AndroidOs. Una interfaz interna mínima readAt/binding/
  ensureSegment/close evita exponer ExistingIndexRecord privado;factory se inyecta
  desdeAndroidService haciaUpdater/Casc,defaultold para JVM,NOconfigdeusuario.
- MinSdk real delprototipo retenidoC109=26,compileSdk36;NOusar los valores de
  android/app independiente como evidencia de esa APK. API21cubierta,no pruebaFUSE.
- ABAno exige forjarhashes:FD deA puedeleerA válido mientraspathalternóA/B entre
  observaciones. No afirmar protección atómica global nique sólo restan ataques
  criptográficos. Pre/poststat detecta sustituciónpersistente;review debe comparar
  honestamente su ventana conla ruta vieja,no prometer eliminarTOCTOU.
- Método de prueba física ennamespaceapp aún por cerrar. Un run-as cualquiera
  puede verotro mountnamespace;no declararlo equivalente. La app instalada no
  ejecuta código nuevo mágicamente:instrumentación/APKpuedeparar proceso ycostar
  otroreplay. No prometer sonda sininstalación ni interrumpirC109 para resolverlo.

### Contrato concreto para revisión independiente

**Lector:**interfaz interna CascReplayReader conensureSegment(File),
checkBinding(endExclusive:Long),readAt(byte[],off,len,position:Long),close().
No expone tipos privadosExistingIndexRecord ni funciones dehash/cabecera.
AdapterAndroid únicoownerOs.open(O_RDONLY|O_NOFOLLOW|O_CLOEXEC)/pread/close.
ensureSegment:comprobar pathregular-no-symlink,abrirfd,comprobar fstatylstat-post
dev/ino/tipo/tamaño;si falla trasopen,cerrarFDparcial antes depropagar.
checkBinding antes ydespués deCADAobjeto repite lstat(path) yfstat(fd),requiere
dev/ino coincidentes,tipo regular ysize≥finobjeto. Sin metadata heredada como
prueba decontenido. Records/offsets/tamaños se validan conlas reglasCasc actuales
ylas sumas se comprueban sin overflow. Un cambio dearchiveId cierraFDprevio;
se abandonaFDal salir del prefijoflags2 antes deescritura nueva ysiempre al
terminar/error/cancelación. Un únicolector/FDactivo,nunca sort/reorder.

Os.pread devuelve0 alEOF:con bytes pendientes el streamlanza truncado,nunca
falsoEOFválido. read(byte[],off,0)devuelve0 siempre;conlongitudespositivas no
puede girar sinprogreso. Cancellation se consultaantesdelecturas/reintentos;
EINTR sólo reintenta con cancel comprobado;otrosEIO/ENOENT/errorespropagan.
La factory interna recibe la misma cancel predicate deese writecall para que
adapter/core no necesiten estado global. Os.close seintentauna vez porFD;
no retrycloseEINTR,closeidempotente yfallo declose noocultaexcepciónprincipal.

**Cotas yownership:**streamdeobjeto posee offsetyremaining,NOelFD. No puede leer
fuera de[start,start+N),soloEOFnormalalconsumirN. Closeobjetono cierraFD. EKey/
BLTE usan read()/read(buf)idénticos;skipprohibido,mark/resetno usados,available
nunca decide longitud. Buffer64KiB+tablaexistente≤8192,ningún objeto enteroRAM.
El writer crealectortrasrecuperartail;no retenciónporrollback/reanudación nueva.

**Wiring mínimo:**factorynullable interna desdeAndroidService→Updater→Casc,
defaultnull=algoritmoviejo para callerscore/JVM. No Android imports enCasc/Blte,
sin opcionesusuario,niregistroglobal. Se usa sólo flags2reanudable;source-existing/
legacy/candidatos mantienen ruta yhashes anteriores. No añadir capas de extensiones.

**Parser único:**extraer decodeStream(InputStream,N,sink?,expectedSize,CKey,
cancelled,allowEncrypted) del parserFile actual. Mantener wrappersFile conguards
yownership/sinkactuales;N sustituye length enaritméticaLong. Cambionecesario plain:
los4bytes deprobe seentreganaMD5/sink yseleeelresto deMISMAfuente,envezde cerrar
yreabrirFile. Noafirmarsnapshotatómico delcontenido:uninode abierto puedemutarse.
Conservar decodedSize/CKey/checksums paraautenticar bytes leídos.

**EKeyenun pase:**wrapperdigestalrededordelstreamacotado,buffermáximo8bytes.
Alos4primerosbytes,siNOsonBLTE decidirhashN yvolcarprefijo;siBLTEesperar8para
decidirhashN(headerSize0) ohashheaderSize. EstocubreplainNde4..7,queNOdebeperder
digestpornoalcanzar8. HeaderBLTEincompleto/malformado lorechaza elparsercompartido.
No duplicar flags/count/tabla:límites16MiB/8192 coinciden enCascEKeyyBLTE actuales;
elcoreverificaheader=12+count*24/40 yrestodereglasantesdecualquieréxito.
Nuevo wrapperverifyEncodedRange tomaexpectedEKey ycomparaMD5trasdecodeStream;
éxitosólosiconsumeNexactosysuperaEKey+BLTE/frameMD5+CKey/tamaños correspondientes.
El pase único cambia elorden de trabajo sobreinput noadmitido:EKeyheaderless
sólo se conocealfinal,mientrasBLTEya pudo procesar bytes. Sinknull enreplay,
cotasdecoded/tabla/zlibactuales intactas yningún resultado/rawretirado/checkpoint
aceptado hastaAMBASverificaciones+bindingpost. Diferencialexige mismoveredicto
deaceptación/rechazo,nolosmismosmensajesdeprimererrorantecorrupción múltiple.

**Fallback:**factorynulleligeoldmodeantesdelcall. Si un modo nuevo encuentra
EIO/ENOENT/symlink/binding-mismatch/no-progress,aborta;jamáseligeoldporobjeto.
Sólo capacidades explícitamente no soportadas detectadas ANTESdelprimerobjeto
podrían elegiroldcon diagnóstico;NOtratarcualquierEINVAL/Errnoinesperado como
capacidad ausente(requiereproof/errorclassificationenimplementación). No prueba
física completada aún:no activar modo nuevo enUSBactual sin cerrarla.

Pruebas focales de diseño:1)diferencialFile/rango plain4..7,N/Z/E,zeroheader,
chunked0x0f/0x10consemántica actual(0x10encryptedverifyrechazado);2)header/count/
checksum/trailing/truncados yconsumonooverread;3)read()vsread(buf)EKey/zerolen;
4)unlink/recreate/swap/symlink conFDviejo;5)cancel/segmentchange/close/no fallback.
Reusar fixtures. Códigoportabletestea lógica;Os yFUSEreales necesitan evidencia
separada. No claimingatomicidadtotal/ABAforgeryni50%ahorroantesdemedición.

### Precisiones tras primera revisión independiente

ReviewAPTOcondicional,conprecisiones necesariasantesdecódigo:

- Padres:conservar literalmente rejectSymlinkParents(sourceDataFiles yotros
  paths actuales) enadmisión/writer;no afirmar queanteshabía esecheckporobjeto.
  ensureSegment añade lstatdedirectorio alabrirsegmento. Pre/postporobjeto son
  lstatcurrentpath+fstatFD,mismo dev/ino,ambosregulares yambostamaños suficientes.
  No reabrir FDporobjeto:eso contradice elcandidato1lector/segmento yrequiere
  elección distinta,NOes una corrección semántica implícita. No comparar st_mode
  completo como identidad:permisos pueden cambiar sin sustituir inode;require
  tiporegular ylecturarealexitosa,erroresfailclosed. Persistentswap se detecta,
  ABAyotrascarrerasnoatómicas no quedan eliminadas.
- Aritmética:reutilizar validateIncrementalSpec yguards deentry actuales;
  start=addExact(archiveOffset,30),end=addExact(start,N). Rechazarvaloresnegativos,
  finfuera deARCHIVE_LIMIT ysegmento mayorqueARCHIVE_LIMIT. EnreadAt validarbounds
  delbytearray conoff≤size ylen≤size-off,yposition≥0/conremaining,sin sumar sin
  comprobar. Cada readlimitado alremaining evitaoverflow ysalida deventana.
- decodeStream eselÚNICOlector de magic:wrapperekey observa bytes queloatraviesan,
  NOprefetcha ni consume para sídejando parserposicionado despuésdelprefijo.
  Colocar boundedread→digestobservador→parser;bufferingeventual nunca bajo el
  boundedsource. Primeros4noBLTEdecidenhashN,incluyenplain4..7;BLTE<8rechazo.
- ExactitudEOF:verifyEncodedRange compara contadorencodedconsumed==N trascore.
  El trailingcheck existente read()!=-1 puede mantenerse:alconsumirN elbounded
  devuelveEOF SINpread;siquedanbytesinternos devuelve1yprovocarechazo. Nunca
  usarEOFdelarchivo segmentocomolímite delobjeto. read(b,off,0)=0,skipno evade
  digest yavailableNOlongitud. Esto no requiere re-leer el byte del siguiente.
- 0x10+allowEncrypted debe seguir rechazándose EXACTAMENTE como hoy,incluso si
  eseobjeto particular no contieneE. decodeFile normal no cambia suallowEncrypted.
  Diferencialevalúa aceptación/rechazo,notexto delprimererror.
- Cierre:fallo primario preservado;closefallido se añade suppressed,no se usa
  para activar modo viejo. No retrycloseEINTR. Abrirsegmentonuevo cierraanterior
  primero;siopen parcialfalla,se cierraeseFDantesdepropagar. readAttrasclosefalla.
- Activaciónreal sigue BLOQUEADA porpruebaFUSE/Os.pread ennamespaceapp ypor
  aprobación específica. APIs disponibles ytestsJVM no satisfacen esa condición.

Cierre review:APTOCONDICIONAL,sin bloqueos restantes de diseño. Reviewer retira
afirmación deque pre/postno detecta sustituciónpersistente ynoexigeopenporobjeto;
acepta1lector/segmento,guardasinitiales actuales,dev/ino/tipo/tamaño pre/post y
equivalencia deaceptación conlas ventanasnoatómicas declaradas. No se concluye
estabilidadFUSEpor elargumento deinode:pendiente gatefísico. Diseño/estudio
offline cerrado;NOcódigo nuevo/sonda/APK ni porcentaje deahorro acreditado.

## Extras evaluados para C110 (sin ampliar implementación)

Usuario pide máximo ahorro enla siguiente entrega. Investigación acotada de
preparación/admisión identifica3opciones aplazadas,no3mejoras listas:
1)Segundo cursor completo de buildPlan para inspectResumableCheckpoint después
del merge/inspectTarget:podría evitar cursor/relectura,pero hoy es cross-check
independiente contra specs;no eliminar defensa porigualdad decontadores.
2)Reusar buildPlan entre pantalla de coste yexecute:re-plan/requireSamePlan son
guardas frescas deconsentimiento/cambios,no caché prescindible.Sinrediseñoaparte.
3)MD5+SHA256 enuna pasada porloose envezdedos:posible fusiónmanteniendoambos,
pero pocosloose ysin tiempo por fase acreditado,no prioridadfrente areplayCASC.
El perfil índice<1%delverificador NOmide elcoste delcursorcatálogo completo:
no usarlo para descartar costes de preparación distintos.
Parentobserva ekeyHexnuevoBlte aún usa16FormatterporMD5;podría sustituirse por
hexlookup yaestablecidopero no hay ahorro medido. No empaquetar refactors CPU/
formato generalsólo por aumentar cantidaddecambios. C110concentradaeneliminar
TEMP/relecturas/open porobjeto;faltagatemóvil,ningún extra eliminaesapuerta.
