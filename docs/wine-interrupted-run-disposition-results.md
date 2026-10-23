# APK61: abandono histórico consumido; preparación nueva fallida

La disposición aprobada del intento60 se consumió una sola vez después de un
nuevo boot. El RUN anterior y su binding siguen byte-exactos. **No hay GO de
Wine**: el nuevo intento61 terminó antes del receipt de prefijo con un fallo
en la comprobación Java de `filesDir`.

## Candidato y validación

- APK61 / `0.2.37-automated`,418308948 bytes,
  SHA `99a256bd8f5de5854c609490b83799cb0da6c38d184330283745d0bca5cf006d`.
- Contrato10550 bytes,
  SHA `b65e2a66668e7c137259b68e832f587edc41e9f03ed4d5ee8976d19f8d4e92ad`:
  los33 paths históricos reales y4 precondiciones prospectivas separadas.
- JVM final antes del APK:35 casos dirigidos pasan en una ronda. Python84
  casos dirigidos previos pasan; no se afirma suite completa del repo.
- Fuente fresh/idempotente64 inputs verificada; build y APK audit pasan.
- Comparación60→61:171→172 assets/libs,170 idénticos, solo provenance cambia
  y se añade el contrato. Native Wine/publisher, runtime, PE y cliente intactos.
- Retención en `/home/adrian/wowforever-artifacts/arm64-prototype/delivery/`:
  `retained-0.2.37-code61.apk`, audits original/validado y receipt. Los receipts
  de retención reflejan su instante anterior al control, no el estado actual.

## Preparación y control físico

El install propio dejó instalado61 aunque ADB emitió stderr y el helper cerró
con STOP. Se preservó ese STOP y se verificó después la identidad instalada;
la repetición diagnosticada no reinstaló ni lanzó procesos. Se corrigió el
helper para admitir únicamente ese candidato independiente exacto sobre la
misma autoridad histórica, manteniendo el nuevo reboot obligatorio. Los dos
tests host de install/reboot pasan.

Preflight aceptado: `wine61-preflight-48b765b0787e44f284c55307279b9de9`.
Boot nuevo: `bc331988-3879-4cd4-b4d2-c1b616c5a2a1`.
Serial `DEVICE_SERIAL`, xaga22041216G/Xiaomi/API34, package
`com.wowforever.arm64probe`, UID10330.

Una validación posterior del collector falló **antes de Activity/START**:
intentaba copiar los dos DLL protegidos por `head` y superaba el límite ADB.
La corrección host usa stat/SHA/restat bounded de los dos paths exactos, con
nofollow y metadata estable; no aumenta el límite ni copia binarios.
Los14 tests dirigidos de esa corrección pasan.

Un único control llegó después a consentimiento y START. Evidencia:
`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/wine61-server-isolation-a34b5a295f944449b61617e70eef0619/`.

| Campo | Resultado |
|---|---|
| RUN nuevo | `RUN-1791738410167-533692127338` |
| WS | `WS-51c1b011c946c45309229bd9d6fff681` |
| Nonce | `f9f4da016c99beb427b44274e9ab4ef6` |
| Estado genérico | `FAILED/EXITED`, terminationUnknown=true, quiescence=false |
| Resultado Wine | `UNKNOWN`, nativeStatus=UNKNOWN, supervisorExit=null |
| Error app | `filesDir is not the canonical private app root` |
| Error host | prepared prefix receipt missing before terminal RUN |
| READY/servidor/cliente | No acreditados |
| Closure/ECHILD/testigo | Sin prueba positiva |

El error proviene del guard Java al comienzo de `ForeverWinePrefix.prepare`,
antes de crear el árbol WS y antes del callback publisher. La evidencia no
registra la pareja Java supplied/canonical exacta; no se atribuye el fallo a
una dirección de alias concreta como hecho observado. Run-as/readlink propios
resuelven `/data/user/0/com.wowforever.arm64probe/files`. La fuente admite ahora
ambas direcciones entre `/data/data/<package>/files` y
`/data/user/0/<package>/files`, solo para ese package/user0; rechaza rutas
iguales fuera de esas dos raíces, otros usuarios/packages y escapes.

Los9 tests de `ForeverWinePrefixTest` pasan después de esta corrección.
**No se ha ensamblado ni instalado un nuevo APK con ella.** APK61 retenido sigue
siendo el binario fallido. La fuente externa/provenance posterior no debe
presentarse como la fuente auditada del APK61 anterior.

## Historial y preservación

El RUN60 original conserva SHA
`745eff1e97de89de1de4c5665ce064564022686cf8409b613cce71aebc022cf2`,
y su binding SHA
`1c7d568d4ec506bb7a0a2233fa12ec1b453bf64143d5835f9e2bdf083b28bd22`.
El archivo `*-current-before-abandonment.json` coincide con el current antiguo.
Los nuevos archivos append-only quedaron fijados:

- Reserva: `070fa21732d2d78309dd17048b33aebae0a5cead109a4384db06e05ea486e10e`.
- Abandono: `f6d85e173a3b39d15e2c5beecdbe56444dd46cf1669dadb162049944b06b2fed`.
- Consumo: `e5afe10eaa5e080ab2086a445be9ab6d86aa916fa27b13ce4396e34af2e06848`.

Closeout adicional read-only:
`read-only-closeout-14ac27ff454a4634977214dd990a0b29/`.
Las dos capturas de58 paths tienen mismos status/hash/metadata. Current nuevo
SHA `8625ecf47bf3347107e1475995e85fe3454dcf4f7fed7bad9d8f2b3bba9dd3d2`.
Los archivos originales/seal no se modificaron; los dos intentos parciales
anteriores del helper se conservan separados. La ejecución final del helper
fue read-only,29.416s,400 comandos acotados, sin force-stop adicional ni input.
Compiler/backup y los históricos de fixtures se mantuvieron estables. Una
lista propia de procesos o archivos ausentes **no prueba quiescencia**.

Pins externos originales:

- Seal: `2831fd327578a6c6d5cd95972fdf7f3c6857113850d2214cbccc7916cc8df10c`.
- Resultado: `d3aaa155fb91888ba36e674c396090e517abf33a8af2d5ee712159f1e605223c`.
- Wine result: `d25aec706b9cf564ae58ff78c43d8567f0a87e2d3f55736b3e01297bb7a04f04`.
- Seal suplemental: `8bfc88773d7b7be622255cb92d3c54b94692220cafc6439bb9bf24ba768c7d3d`.

## Gate siguiente

La excepción60 está consumida y no permite rearmar el UNKNOWN61. No se ha
repetido START. La continuación específica propuesta está en
[wine-prefix-prelaunch-continuation-plan.md](../.agents/planning/done/wine-prefix-prelaunch-continuation-plan.md).
La ejecución62 y el nuevo fallo de modos están en
[wine-prefix-prelaunch-continuation-results.md](wine-prefix-prelaunch-continuation-results.md).
El plan padre de Wine sigue abierto: servidor/cliente, gráficos managed,
identidad PE, visual y WoW aún no están cualificados.
