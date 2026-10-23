---
id: catalog-preparation-ordered-scan-2026-10-19
state: done
date: 2026-10-19
---

## Summary

**Cierre 2026-10-19:** implementada, revisada, auditada e instalada C112. Ambos
scans reales medidos:94,181/100,709s frente a224,584/225,166s deC111. Preparar→
primer source12min42s. Se cierra este experimento de preparación; no equivale a
cliente completo ni a throughput optimizado. Usuario aparca mejoras. Ver
[`docs/downloader-handoff.md`](../../../docs/downloader-handoff.md) para retomar.
La ventana10+2C112 terminó sinerror al cierre:foreground0,29939MiB/s en593,387s
yfondo0,36717MiB/s en118,197s,redcompartida;el verificadorP seguíapendiente en
elinforme. No se repiten descarga ni APK ni se declara cliente completo.

Usuario pide revisar la preparación/catálogo mientras C111 descarga. Investigación
offline identifica un candidato acotado: evitar la tabla hash temporal de todos
los objetos al planificar una instalación sin fuente, aprovechando el cursor
oficial ordenado y comprobando su orden, sin cachear confianza ni quitar scans
frescos. No se ha cambiado código de producción por este plan.

## Content

### Evidencia de C111 retenida, sin nueva carga en el móvil

- Primera `CASC target inspection source=false`:224.584ms;segunda225.166ms.
  Total~7min30 de dos fases deplanificación. Fuente:c111-live-phases.log bajo
  work/c111-overlapped-resume-20261019/.
- COUNT/MIN/MAX sólo168/194ms:no es el cuello de esta preparación.
- Ambasfases recorren1.144.978objetos. `inspectTarget` sigue creando
  `DiskIndexTable(target-records.bin)`,find+put porobjeto ycrecimiento delhash,
  incluso cuando sourceDataFiles=null yningún candidato anterior puede reutilizarse.
- `catalog.withObjects` produce ORDERBYekey yvalida cada ObjectRecord. Constructor
  ya hace otra validación completa;NOse elimina porque otros consumidores de
  catálogo dependen de ella. No confundir validaciónfila con agregadoSQL barato.
- Los225s incluyenSQL,validaciones,cálculos ytabla:no sonahorrogarantizado al
  quitar sólohashdisk. No hay medidaseparada niestimación inventada del beneficio.

### Cambio mínimo propuesto

1. Ruta interna explícita para `sourceDataFiles==null` ycursor admitido ORDERBYekey.
   En esa ruta validar EKey estrictamente creciente enCADAfila,además de todas
   lasvalidaciones actuales de spec/source enelcursor. Si elordenfalla,rechazar;
   no confiarenflagdeorden que permita saltarse validación.
2. Detectar duplicadoEKey ycolisión de prefijo de9bytes comparando conla fila
   anterior:enordenlexicográfico estricto,losEKeys con igualprefijo soncontiguos.
   Memoria O(1),sinHashSet/lista de1M niDiskIndexTable sólo para targetSeen.
3. Conservar EXACTOS packingvirtualde segmentos/headers480,headerlocal30,
   bucketCounts16,totalRecords,cotas2M/IDs0..1023,tamaños/costes,onObject porfila
   yTargetScanResult. Devolver plan/coste/snapshot iguales alalgoritmoactual.
4. Fuente existente,reuso candidatos,inspectTarget genérico conSequence arbitraria,
   writer/índicescanónicos yalgoritmoC111 mantienenrutaprevia. Ningún cambio a
   hashesCrypto,checkpoint,solape,storageguards,prefetch,durabilidad niConsent.
5. Mantener dosmomentos defreshplan antes/después deconfirmar yrequireSamePlan.
   No reutilizar elplan presentado como una garantía deestado sin revalidación.
6. No plegar ahora validaciónconstructor ni segundo cross-check CP dentro de
   buildPlan:son rediseños adicionales no necesarios para eliminar estetrabajo.

### Por qué puede ser equivalente

Sin source no hay entriesfísicos reutilizables ni reconstructionsource enla tabla;
se usa para detectar targets ya vistos/colisiones ysimular la disposición de los
nuevos objetos. El orden validado sustituye las búsquedasglobales,NOelhistograma,
las cotas ni loscálculos. La equivalencia se demuestra con fixtures diferenciales,
no por igualdad delCOUNT ni por confiarenmetadata privada.

## Implementation / Action Items

- [x] 1. Revisar logs/callgraph sin tocar C111;acotar candidato.
- [x] 2. Aprobar implementación de la ruta ordenada sin fuente,con review de
  equivalencia/consentimiento antes de empaquetar.
- [x] 3. Implementar mínimo ytests focales:resultado/coste igual algenérico para
  vacíos/segmentcrossing;duplicados/prefijo9colisionado/desordenrechazados;
  source-existing conserva reuso/rechazos yrequireSamePlan detecta cambios.
  Reusar fixtures,ninguna tabla/cliente sintético gigante niframeworknuevo.
  Builderoffline iniciadosóloCasc/Test+Updater/Test;sin cambioscatálogoconstructor,
  storage,reader,checkpoint,solape odurabilidad. Versión112/0.2.88-ordered-planning
  reservadaparaevitarconfundir fuentesfuturasconAPK C111actual,no build/instalación.
  Código4archivosimplementado:privateTargetPlanPacking compartearitmética exacta
  genérico/ordenado;entryinspectTargetOrderedSourceNull validaascendencia/prefijo
  porfila,sin target-records.bin. UpdatereligeconsourceDataFiles==null sólo.
  4gruposCasc+1Updaternuevosescritos,3regresionesanterioresseleccionadas;
  Gradleoffline encloneC111retenciónstaged en curso,NOresultadoverde todavía.
  ReviewWIP paralelosobrework/c112-ordered-plan-review/manifest.json estable
  paraadelantar análisis deequivalencia sin interferir compilación delbuilder.
  ReviewAPTOcódigo,sinbloqueos,condicionadoaverificación:packingcopiadoexacto,
  source-nullsinflags1 físico,detecciónprevkey/prefijo equivalenteenordenSQL,
  callbackycostessnapshot/replan frescos conservados. Casosfuera decontrato
  ordenadopuedenrechazarpordesordenantesdeduplicado,colisiónsinaceptarmásinputs.
  Resultados8selectoresGradlependientes:noAPKinstalada/beneficiomedidotodavía.
  Resultadofinal comprobado porpadre15:57UTC:8testsverdesdesde15:34:29,
  Casc6/Updater2 fallos0/errors0/skips0,command:app:testLegacyDebugUnitTest,
  loggradle-ordered-plan-group-final.log BUILD_SUCCESSFUL23s. Estadoanterior
  'compilando' quedóobsoleto~23min:corregido alusuario,nopresentar espera como
  trabajo activo. PrimerfallofueassertCASC48vsplanCASC+loose72;fixturecorregido
  conservandoambascifras,noproducción. Intentorepairunscopedtambiénlanzóubuntufs
  sin testcoincidente;finalscope:apppasótodos,nosuitegeneral.
  Padre4repo/stagematch,producciónIDÉNTICAasnapshotreviewAPTO;únicodeltaTest
  sóloaserciónCASCvsloose inspeccionada.Pinsfinales:
  Cascb33de1b60f9621f5a4381333483eef1ee78a7751e16524ac10c4051a3c153350;
  CascTestafdc52e562cceb6b6985736539b9c543a807abb9132658df7a9d427fb50b4570;
  Updaterb8ab1bde54b6282cb09a1b155318e6bab74e187d12995b38a58297e59468d79b;
  UpdaterTest6a9d188b89e1f600149d745084224385cb01c86cbc63a210879e67776ca96fda.
  Build/auditAPK completo lanzado,sininstalaciónC112/C111sigueintacta.
- [x] 4. Reviewindependiente ypinsfinales;build/auditúnicamente despuésdeverde.
  ReviewproducciónAPTOypins finales listos;build/auditcompleto lanzado tras8
  verdes. ResultadoAPKpendiente,no confundir estecheckbox conAUDITPASS.
- [x] 5. Decidir entrega conjunta/instalaciónsegúnbeneficioneto;no interrumpir
  C111 para APKs de diagnóstico. Medir las DOSfases targetinspection ystartup
  total sobre el catálogo real,sin afirmar ahorro porun microbenchmark deRAM.

## Open Issues

- Usuarioaprueba implementación ytests:«si,hagamoslo». SinpausarC111;
  no autorización deinstalación/otraAPKdiagnóstica enestepaso.
- La fracción exacta de225s atribuible atabla no está medida. Objetivoes
  eliminaroperaciones demostrablementeredundantes,nogarantizar7min30deahorro.
- Auditar equivalencia de colisión de prefijo vs tabla yguardas de packing;
  cualquierpredicado no representable pororden escalar debe conservarse.
- Instalaciónfísica futura,no autorizada por esta investigación;C111sigue activa.
  Usuario pide despliegue:«podemos desplegarla entonces?». Se encadena aPASS
  audit/review/tests ya verdes yproteccionesbuild70450/USB/costes existentes;
  nada de pausa antesdeauditoría. Driver112retieneCP180sdeadline(parano repetir
  timeout20s de111),update-rpreservandoestado,observadorcomprueba2fasesordered
  ysolape/progreso>34commits. ScriptsASTválidos,lanzadaconesperainotifyaudit.
  NOinstalada aún. Error/noauditoría→noinstalación/reintentoautomático.
  BuildcompletoC1123min42sPASS,3testsPythonverdes yaudit
  PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT(2184sources/178payloads). APKsha256
  3c325d3f422d3b7e631df5d2b038a00ad9fbe1adb493eaca47dc9ef33770f1ae.
  Driverinstalaciónesperabaaudit ycontinúa;observerinotifyinstalaciónlanzado.
  No segundo driver,nilanzarnuevapausa/confirmación enparalelo. Gatebeneficio
  Androidpendiente,noreduccióndepreparación acreditada todavía.
  C112instalada112confirmada:609.363objetos/48.294.811.059bytes encodedconservados,
  CP/receiptbyteidénticosbefore/after,tail0,objetoEpresente,0workers/FGSparado
  antesdeupdate. c112-installed-preserved.json. Cadena depreparación/confirmación
  ymedicióncontinúa,sin repetirdrivers. Falta medirambosscansorderedytiempohasta
  nuevoscommits;no asumirbeneficio sólo por instalaciónpreservada.
  Revisiónusuario20min:ADBconectado,logactual,nocapturaestancada. Primer source
  18:17:14.717dispositivo:overlap=true,verified27.051/pending582.312. Targetscans
  ordered94.181ms/100.709ms vsC111224.584/225.166(~57%menos enesasfases).
  Prepare→confirm351,628s(5min52);confirm→source410,746s(6min51),total762,374s
  (12min42)vsC111999,535s(16min40). Diferentesprefijos/condiciones,noA/Bcontrolado.
  ~6min despuésdesource,parcial337,872s0,30637MiB/s/1.460nuevoscommits,sinerror.
  Ahorropreparaciónobservado,noprogresovelocidad;redcompetida,noatribuircausal
  regresiónC112poruntramo. Ventana10+2todavíaencurso,c112-startup-partial.json.
