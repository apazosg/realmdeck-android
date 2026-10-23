# Supervisor APK59: admisión exacta, GO app-domain y cancelación

## Resultado actual

**GO para el perfil de fixtures nativas normal y cancelaciones en el APK real**
del POCO xaga, Android14/API34, sin root. Ya no es solo una prueba `run-as`.
Java posee un supervisor persistente; este adopta y recoge sus hijos, y el
testigo Java independiente permanece estable hasta Q y sale con código0.

Esto **no** verifica Wine, wineserver, PE anónimo, presentación visible o WoW.
La prueba deliberada de EOF deja una cuarentena UNKNOWN que NO se recupera.

## Candidato y conservación

- Package `com.wowforever.arm64probe`, UID10330, code59 / `0.2.35-automated`.
- APK retenido e instalado: 370965684 bytes,
  SHA256 `b8cace99ded43adb1841a2047f3802976b4b2d113aa3c5a67091bdd7270d2435`.
- Supervisor30344 bytes / `fd8727d3d4b3b76e00cbf17c74bcee75684d7945aa8736f644ecbbaf55f7385c`;
  fixture12784 / `7f1b6d256939c9a7310475c3d3bc7dbdfeb22ad46e3de4270413db05a663e67f`;
  manifest3662 / `a246a11bc142fecbe156c40ac8913baba02a172288aff8b1f2787c21eb1134b6`.
- Disposición congelada2646 bytes / `423ed396ac54c9caa5edf43c06ec645dc5b68275928003c064729b85ecb44c62`.
- APK58 fue retenido pero nunca instalado. Comparación58→59: 165 miembros,
  163 idénticos, solo disposición y provenance cambiadas. Nativos, Wine/WCP,
  DXVK, PanVK, noGL, JNI, PE, cliente y catálogo permanecen byte-idénticos.
- Entrega normal33, APKs55/56/57/58 y evidencia histórica conservados.

Artefactos en `/home/adrian/wowforever-artifacts/arm64-prototype/delivery/`;
auditoría validada `automated-0.2.35-code59-validated-apk-audit.json`.

## Admisión histórica: no reset de UNKNOWN

La única excepción autorizada era el prelaunch57
`NS-b0a3fe48f925d7d656f597f740b4b413`. Su run/status UNKNOWN no cambia.
Su current original fue archivado byte-exacto con SHA73428e2c…57244d.
Los cuatro streams estaban **ausentes en el dispositivo**, no vacíos: los
placeholders del collector host nunca se hicieron pasar por ficheros originales.

APK59 creó dentro de aquel RUN tres ficheros append-only: current archivado,
`prelaunch-disposition.json` y `prelaunch-consumption.json`. La reserva de consumo
es durable y liga un único nuevo RUN antes de avanzar current. La disposición
no afirma ECHILD, quiescencia ni cierre del RUN57. V1/UNKNOWN56 sigue intacto.

Primer RUN admitido: `NS-0059ae0d466c23c6383d48332a04d73f`.
Disposition SHA `d289189baa2d1c55a365234434c8694265db6600cb40563a9bf686600b7146ba`;
consumption SHA `8e8dd9267f6a558c219b53474577e801f8779a4ec988c0c7f37c8006107e0e3f`.
Los RUNs posteriores preservan esos receipts exactos y no consumen otra excepción.

## Controles físicos

Base externa: `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`.
Cada intento efectivo tuvo nuevo preflight/boot/RUN y una única intención START.

| Directorio `native-supervisor-trial-…` | Escenario | App/nativo | Resultado original global |
|---|---|---|---|
| `a238d7bc81f54a9bbf2ad8740040b302` | normal / primera admisión | PASS | UNKNOWN: bug de receipts del collector |
| `7e84cca3b7ce4410a753c59cbbf80d13` | normal | PASS | **GO** |
| `00236a6b06e344bfb5c0cc9346e3d9bc` | cancel_before_start | CANCELLED | **CANCELLED** |
| `957b94227e50441a865174c90c8f1ba3` | cancel_fork | CANCELLED | UNKNOWN: orden cleanup del collector |
| `829e275881a04ba3878a2986ea23fa90` | cancel_fork | CANCELLED | **CANCELLED** |
| `f39b9ae49cf346019aaadba2721be04b` | EOF deliberado | UNKNOWN / exit2 | **INVALID**, sin aceptación positiva |

No se reescribió ninguno de los resultados originales UNKNOWN tras corregir
el host. Sus hechos nativos positivos se distinguen del veredicto global.

### Normal GO

RUN `NS-f4076be673312b5a4814c3bf938a6c36`, supervisor14083/startTicks6646,
PPID10234, UID4=10330. READY registra SET/GET subreaper0/0/1.
Dos familias double-fork: adopciones14112 y14133 al supervisor14083, con
PID/startTicks/UID4 y PPID de nacimiento/adopción coherentes. Ambos hijos de
cada fase salen0; PHASE_DONE y RUN_DONE verifican childCount0/ECHILD.
Supervisor exit0 y testigo estable/Q/reaped/exit0. IPC, waits, lectores y
persistencia terminal se cierran antes de aceptar PASS.

### Cancelaciones

Cancel-before-start acredita READY→CLOSE→RUN_DONE CANCELLED sin START de fase,
supervisor exit0/ECHILD y testigo positivo.

Cancel-fork aceptado: RUN `NS-0737ae7eca2372bd1f5874205ee0b7be`,
supervisor14177/startTicks6307/PPID10290. STOP después del SPAWN del nieto
verificado y antes de su ACK. Cleanup registra EXIT del intermedio,
ADOPT del nieto14207 al supervisor14177 y EXIT del nieto, seguido de
RUN_DONE CANCELLED/childCount0/ECHILD. El ADOPT de cleanup se liga a la identidad
de nacimiento y al parent nativo propietario; no inventa un `/proc` fresco
después del reap. El testigo conserva su prueba independiente positiva.

### EOF: frontera y límite

RUN `NS-1951490a6a0bc710f0ea3e219f582e46`: EOF mientras native esperaba ACK
del intermedio. Stdout registra EXIT125 del intermedio, ERROR y RUN_DONE ERROR
con ECHILD; el journal aceptado por Java termina antes de ese cierre.
Java conserva UNKNOWN, nativeExit2, echild:false; testigo PASS/Q/exit0.
No hay identidad completa de nacimiento/adopción/reaping del nieto en ese
journal: su ausencia no demuestra que no existiera. No se toma el stdout
aislado como autorización de cleanup ni se recupera el RUN.

`invalid_protocol` físico no se ejecutó después de ese UNKNOWN. Sigue probado
solo en host/JVM; supervisor_failure permanece exclusivamente host/JVM.

## Correcciones y validación

Cambios APK: permiso EACCES esperado, admisión exacta/consumo único, marcadores
durables PROCESS_CREATE_INTENT antes de submit y rechazo de streams añadidos.
No cambios de C/IPC/señales para estos seis controles.

Correcciones host diagnosticadas, sin otra versión de APK:

1. Validar receipts creados por la primera admisión antes de exigir estabilidad.
2. Separar current vivo del archive histórico y rechazar reutilización no exacta
   del antiguo RUN. Para admisiones siguientes, comparar receipts byte-exactos
   contra el baseline original; nunca rebasing antes de comparar.
3. Cancel cleanup canónico EXIT→ADOPT→EXIT, no ADOPT→EXIT→EXIT. Prueba negativa
   del orden anterior y rechazo si falta EXIT; mismas identidades/ECHILD/testigo.

JVMDisposition actual7/7, Python packaging+collector94/94 antes de las últimas
correcciones host; nuevo no-follow cubrió tres ramas en un caso dirigido.
Después: collector91 positivos por ronda, cuatro tests live/archive, nueve de
receipt byte-binding y seis de cancel/fault. Son rondas solapadas, no una suma
de tests distintos ni full-suite actual. Revisión independiente de admisión y
receipt binding, fresh/idempotent overlay99, build offline y audit positivos.

## Cierre de evidencia y estado

Cada uno de los seis directorios conserva result/seal originales y añade
`artifact-summary.json`, `latest-post-trial-state.json` y
`supplemental-closure-seal.json`, exclusivos0600/fsync/directorio fsync.
El cierre final fue **solo lectura** del dispositivo: sin otro force-stop,
keys, UI, reboot, instalación o RUN. Helper
`/tmp/opencode/close_native59_controls.py`,
SHA `b333a773392d9f2bbb999583fa64d592434bdc3b1224acc41af6480436ed4bb9`.

Boot final `9aaa217e-168e-468e-b5cd-970345b84370`; current v2 UNKNOWN/EOF SHA
`f067e510b36e37e4c7e19c6c0163d36f125c5faa1f7335538271de474cd388d9`.
Current v1 c9c899f5…64685c y archive57/run73428e2c…57244d permanecen intactos;
compiler AA64 c374b107…ed3add y originalbackup06dc23aa…b58e52 estables.
Snapshot de procesos UID no es contención ni quiescencia global. Nuevo boot
obligatorio antes de cualquier futura prueba Wine; no más admisión fixture v2.

Plan terminado: `.agents/planning/done/native-prelaunch-disposition-plan.md`.
Próximo contrato propuesto: `.agents/planning/wine-supervisor-handoff-plan.md`.
