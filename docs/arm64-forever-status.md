# Estado ARM64 + Classic Forever

**Actualizado:2026-10-21.** C127 consigue Login y entrada al juego de Classic
Forever con el cliente Windows ARM64 nativo70450. El usuario confirma ausencia
de glitches en esa prueba; estabilidad prolongada, FPS, audio y controles no
están medidos. No confundir este resultado con la antigua PoC/code33 ni con x64.

## Resumen ejecutivo

- Cliente70450 completo en USB, descargado sin Battle.net. Publicación C117
  terminada; defecto de reserva KMT corregido en el escritor desde C122, sin
  volver a descargar el cliente para estos ensayos.
- Runtime funcional en xaga/Mali-G610: Proton11 ARM64EC + DXVK/PanVK/D3D11,
  paquete `com.wowforever.arm64probe`, sin root ni cambios de driver para Login.
- C126 reconoce un bloque completo ARM64 tras corregir el relleno (37 variable,
  38–39 cero). C127 añade aplicación explícita de32 bytes de clave pública del
  grupo8 con revalidación/readback; no parche del EXE, DLL externa ni bypass TLS.
- Una primera prueba aplicada se interrumpe por desconexión USB acreditada.
  Otro intento con el mismo APK y USB montado verifica APPLIED/ALREADY_PATCHED
  antes del reintento de Login; después el usuario confirma acceso dentro del juego.
- **Trabajo actual:** C128 adelanta búsqueda/aplicación antes de Enter, sin
  ampliar15min totales; revisión cerrada,32 tests JVM, compilación y auditoría
  correctos. No instalada ni comprobada físicamente; no interrumpir la partida actual.
- Siguientes puertas: aplicación pre-Login acreditada, sesión30–60min, rendimiento/
  audio/controles, USB y cierre completo Wine. No repetir TLS/captura estática
  agotada, descarga completa ni reiniciar Android por defecto.

El [estado de producto](launcher-app-status.md) consolida versiones y verificaciones;
el [registro de avance](arm64-forever-connection-progress.md) contiene la secuencia
causal y evidencias; el [handoff](downloader-handoff.md) fija precauciones operativas.
Planes: [compatibilidad cerrada](../.agents/planning/done/arm64-server-identity-plan.md)
y [adelanto aprobado en curso](../.agents/planning/early-group8-apply-plan.md).

## Historial de diagnóstico hasta2026-10-19 (no estado actual)

Los resultados «sin auth/mundo», capturador suspendido, source30 o code33 que
siguen describen esos ensayos, no C127. No constituyen próximos pasos vigentes.

**Modo diagnóstico autorizado2026-10-15:** iteración autónoma reversible. D3 corrigió
attach/ruido de D2 y acreditó TCP del propio WoW por inode compartido con wineserver,
pero hacia34.110.236.123:443, no todavía el portal1119. Un Login test, sin avance
al mundo. Siguiente: identificar servicio DNS/SNI/fallo con logging existente;
ver [registro breve](arm64-forever-connection-progress.md). No requiere aprobación
por cada ensayo reversible; Btrfs/seguridad permanente/cuentas reales siguen fuera.

**Último resultado: [D2](arm64-wine-tcp-observation.md), inconcluyente.** Strace4.25
existente pudo observar al WoW Unix26754/UID10330/start465456. El attach ocurrió
aprox113s después de solicitar lanzamiento y la salida alcanzó2MiB en6s de E/S
genérica: sin socket TCP de Forever acreditado, handshake atribuible, autor del
cierre o primer error de conexión. Sin Login, nuevos APK o bypass. Se paró sin
repetir ni ampliar límites; restauración posterior verificada y memoriaOFF.
El cierre completo no quedó dentro del plazo10min; D2 conserva timestamps sin
presentar esas horas de diferencia como observación activa. No reabrir capturador.

**D1-A, revisión cerrada de la aserción:** el log identifica al emisor SEH, no el
módulo que la origina ni la correspondencia del PID Wine00e8 con el WoW Unix17589.
1248 repeticiones antes/después del Enter, sin prueba de muerte inmediata o corte
de TCP. No hay control sano que permita llamarlas normales. No se seguirá esa
rama con los logs actuales. Única propuesta siguiente: observación acotada de
TCP atribuible al PID de WoW, sin otro Login y sin ejecutarla todavía; ver
[D1-A](arm64-wine-auth-diagnosis.md#d1-a--revisión-de-la-primera-aserción-solo-logs-existentes).

**Vigente entonces:** capturador, claves en memoria y análisis estático detenidos por el
usuario. [Diagnóstico directo D1](arm64-wine-auth-diagnosis.md),17min35s:
DNS/TCP auxiliares Android positivos; uso real de Schannel/GnuTLS, sin handshake
atribuido a Forever ni mensajes WoW demostrados. Un Login autorizado sin avance
acreditado. Primer error repetido: aserción21:27:55UTC, anterior al Enter21:35:57UTC;
causa/impacto pendientes. Ningún rechazo X509/Ed25519 demostrado ni bypass.
WINEDEBUGrestaurado; ambos instrumentos permanecenOFF. C1–C9 son historial y no
autorizan reabrir esa vía.

- **C4–C9 cerraron esta ronda sin resolver captura ni auth:** se corrigieron
  progresivamente espera temprana, cobertura de maps, separación cabecera/cuerpo,
  commit atómico y selección de base, siempre sin aumentar cap ni retener heap.
  Las rondas host fueron independientes y no se suman. T1/T2 aceptaron TLS1.3
  yTLS1.2 desde Linux con validación estricta del endpoint Forever, sin
  credenciales. [Detalles TLS](arm64-forever-tls-diagnostics.md). Eso no implica
  que Wine valide igual ni que CertStore sea TLS/X509. El usuario permite evaluar
  una variante aislada con validación TLS desactivada, pero no se implementó
  ningún bypass ni bypass de certificado.

- Hay una **PoC ARM64 gráfica funcional sobre Mali-G610**: muestra el login de
  WoW y aplica el perfil/portal de Classic Forever. Eso demuestra una ruta de
  arranque y presentación, no autenticación, entrada al mundo ni estabilidad
  de gameplay.
- En los controles físicos de esta ronda siguen siendo **cero los snapshots de
  código retenidos**. C3 terminó con el arnés de captura sin un snapshot fijo
  completo; el error validado del collector no equivale a ausencia de código.
  La cobertura diagnóstica normal sigue siendo read-only y no autoriza una
  afirmación de ausencia global.
- Hubo **dos envíos históricos de Login** en esa PoC: `f6496fc097` y
  `872fb78bc2`. El segundo dejó visible `WOW51900317`. En ambos el puente fue
  read-only y `bytesWritten=0`; no hubo parche aplicado.
- Los controles posteriores sin Login son diagnósticos diferentes. Por ello no
  se afirma ausencia global de Login ni se presenta el código de desconexión
  como causa criptográfica, TLS, protocolo, contraseña o parche.
- La variante automatizada **code33** alcanza Wine/Explorer pero queda bloqueada
  en `winex11.drv`; no tiene identidad WoW, frame, CertStore ni Login
  demostrados. No es la misma variante que la PoC gráfica funcional.
- El contrato y el parche de **x86_64** validan un bloque 12×40 y la sustitución
  del grupo 8 en esa arquitectura. No prueban que ARM64 tenga esa tabla, que la
  requiera o que deba escribirse.
- No hay causa criptográfica aislada de `WOW51900317`, ni suficiencia probada de
  protocolo, datos/bootstrap (~1,2 GiB) o cliente completo. La dinámica x64 está
  suspendida por CASC y la exportación Btrfs fue cancelada; Btrfs/wowserver es
  solo referencia read-only.
- El ensayo estático del alias ARM64 ya está **completado**: confirmó una arista
  DIR64 hacia datos file-backed posteriores al slot, pero no tipó el objeto ni
  encontró consumidor, relación con grupo 8 u objetivo directo demostrado de
  parche. No se repite dentro de este alcance.
- La variante de captura revisada conserva `--prepare-only` apagado por defecto
  y deniega la combinación Login/captura. La captura runtime sigue sin haber
  reunido un módulo actual demostrable: no hay exportación de bytes, análisis
  Ghidra ni prueba criptográfica de pertenencia al módulo.
- C4–C6 dejaron **cero snapshots retenidos**. C6 mostró que una cabecera PE
  válida puede aparecer antes de que su payload sea privado RX; eso justifica
  separar la fase metadata de la fase de cuerpo, pero no prueba desencriptado,
  CertStore, TLS ni autenticación. El siguiente gate debe analizar un artefacto
  solo si pasa esa separación y sus identidades before/after.
- C7 consiguió lecturas payload bajo todas las guardas, pero falló el commit
  atómico y retuvo cero binarios. C8 y C9 encontraron, respectivamente, un
  clasificador de paths anónimos y una posible re-selección de otra copia PE;
  ambos son correcciones de instrumento, no prueba de captura ni de causa única.
  `source30` es la última fuente aprobada; su nuevo build sigue en paralelo y
  pendiente, por lo que no se predice resultado de captura.

## Variantes que deben permanecer separadas

| Variante | Qué demuestra | Qué no demuestra | Bitácora |
|---|---|---|---|
| C127 funcional70450 ARM64 | Aplicación guardada real; Login y entrada al juego confirmados por el usuario, sin glitches observados | Estabilidad30–60min, FPS/audio/controles, universalidad de dispositivo o primer Login antes de aplicar | [avance](arm64-forever-connection-progress.md), [producto](launcher-app-status.md) |
| Baseline x64 / patcher PC | Contrato, bloque 12×40 y grupo 8 validados en x86_64 | Layout ARM64, necesidad del parche o autenticación ARM64 | [Fase 3](fase-3-patcher.md), [investigación del patcher](wow-patcher-arm64-investigation.md) |
| PoC funcional ARM64 Mali | Arranque gráfico, login visible y perfil/portal Forever aplicado | Auth, mundo, gameplay, estabilidad o parche | [perfiles funcionales](functional-forever-profiles-results.md) |
| PoC Login `f6496fc097` | Un envío de Login y actividad posterior; solo lectura | Handshake remoto, aceptación de credenciales o causa de error | [evidencia de la PoC](functional-forever-profiles-results.md) |
| PoC Login `872fb78bc2` | Un envío y pantalla posterior con `WOW51900317` | Causa del código, auth correcta o fallo demostrado del parche | [evidencia visual](functional-forever-profiles-results.md) |
| Diagnósticos read-only posteriores | Cobertura acotada de clases de mapping y candidatos estructurales | Ausencia global de almacén, formato ARM64 o autorización de escritura | [exploración del parche](arm64-certstore-patch-exploration.md) |
| Automatizada ARM64 code33 | Bootstrap/Wine y llegada a Explorer en una entrega privada | WoW, render, CertStore, Login o jugabilidad | [launcher automatizado](automated-arm64-launcher.md) |

## Fronteras del gate histórico de captura

Las restricciones del párrafo siguiente corresponden a aquel gate y no reemplazan
el plan de producto aprobado posteriormente. Para el trabajo vigente rigen los
límites del [estado de producto](launcher-app-status.md#límites-de-ejecución).

Para el siguiente gate no se ejecutan clientes, Wine, Docker o ADB; tampoco
Login, descargas adicionales, credenciales, escritura de memoria o parches del
ejecutable. C3 usó únicamente APKs diagnósticos auditados y no autoriza nuevos
cambios de APK fuera de una revisión fresca de SO/provenance. No se modifica el
proyecto Btrfs/wowserver: solo se pueden consultar referencias técnicas
read-only. No se cambia el contrato, specs, runtime ni drivers para convertir
una hipótesis en resultado.

La frase histórica «requiere el parche» se conserva como **hipótesis heredada
del método PC**, no como hecho de compatibilidad ARM64. Cualquier futura
adaptación deberá seguir a una evidencia discriminante, preservar el original y
separar configuración, parche en archivo, parche en memoria y autenticación.

## Bitácora de ensayos y decisiones

Cada entrada conserva ID, pregunta, entrada, procedimiento/límites, resultado,
interpretación, evidencia y siguiente acción. Los paths de artefactos son
externos al repositorio y no contienen aquí credenciales, heap ni ejecutables.

### P0 — PoC gráfica ARM64/Mali (`functional-poc-20261012`)

- **Pregunta:** ¿Puede el cliente ARM64 llegar a una pantalla gráfica de login
  con el perfil Classic Forever?
- **Entrada:** PoC ARM64 build `1.60.1.70009`, Mali-G610; configuración actual
  `BetaFunctionalPoc-classic_forever.wtf`. La PoC lee `BetaSuspendedTest.wtf`
  como entrada y después usa la configuración nueva; P0 no atribuye escrituras
  a `BetaSuspendedTest.wtf`. `Config.wtf` no se modifica; APKs retenidos fuera
  del repo.
- **Procedimiento/límites:** arranque gráfico y observación de la UI; puente
  read-only, sin escritura ni credenciales en el control inicial.
- **Resultado:** login visible y limpio; el perfil/portal Forever quedó aplicado.
- **Interpretación:** PoC de render/arranque y configuración, no prueba de
  autenticación, mundo, datos suficientes o estabilidad prolongada.
- **Evidencia / siguiente:** [functional-forever-profiles-results.md](functional-forever-profiles-results.md),
  [resultados gráficos Mali](mali-graphics-probe-results.md); mantener esta
  variante separada de code33 y de los Login históricos.

### L1 — Login histórico `f6496fc097` (2026-10-12)

- **Pregunta:** ¿El formulario de la PoC puede enviar Login al perfil Forever?
- **Entrada:** APK `a0d6e1ec...`, perfil Forever, cuenta temporal proporcionada
  fuera del repo; los valores no se conservan.
- **Procedimiento/límites:** un único Enter tras corregir el foco/teclado; ventana
  de observación de 180 s; logs redactados y lectura de memoria sin escritura.
- **Resultado:** hubo intención y un envío de Login; se observó actividad Wine
  TLS/certificados, sin transición a reino/personaje; `bytesWritten=0`.
- **Interpretación:** `AUTH_UNCONFIRMED / STORE_NOT_VALIDATED`. La actividad no
  identifica handshake completado, rechazo de clave, contraseña, TLS o red.
- **Evidencia / siguiente:** acciones/logs del ensayo en la bitácora funcional;
  no repetir Login en este alcance ni habilitar escritura por heurística.

### L2 — Captura posterior `872fb78bc2` (2026-10-12)

- **Pregunta:** ¿Qué estado visual queda después de un envío independiente?
- **Entrada:** misma familia de PoC funcional; captura explícita autorizada,
  sin guardar la cuenta en el repo.
- **Procedimiento/límites:** un único Enter; capturas programadas posteriores;
  no se pulsaron botones adicionales ni se habilitó parche.
- **Resultado:** permaneció el login y se mostró `You have been disconnected.
  (WOW51900317)`; no apareció mundo ni selección de reino/personaje.
- **Interpretación:** resultado de desconexión no causal: no demuestra contraseña
  incorrecta, certificado rechazado, protocolo, suficiencia de datos o fallo del
  parche. No contradice que existiera el envío L1.
- **Evidencia / siguiente:** [bitácora funcional](functional-forever-profiles-results.md); investigar
  una causa solo con evidencia nueva y alcance aprobado.

### D1 — Seis clases privadas, sin Login (`39f3a01712`)

- **Pregunta:** ¿Está el almacén compatible en una clase de mapping no cubierta
  por el lector canónico?
- **Entrada:** APK diagnóstico `d4bae6cd...`; objetivo WoW de la PoC en Mali;
  seis clases privadas legibles.
- **Procedimiento/límites:** polls por clase, lectura read-only, límites de
  aproximadamente 2 GiB/20 s por poll, sin direcciones ni bytes exportados.
- **Resultado:** cinco clases tuvieron pasadas completas y una quedó incompleta;
  cero claves conocidas/sustituta, `bytesWritten=0`.
- **Interpretación:** negativo limitado a esas clases/ventanas/instantes; no
  ausencia global, no formato ARM64 demostrado y no permiso de escritura.
- **Evidencia / siguiente:** [exploración del parche](arm64-certstore-patch-exploration.md); no repetir
  barrido general.

### D2 — Hipótesis estructural 12×40 (`bb4e380bee`)

- **Pregunta:** ¿Las secuencias de IDs 1..12 separadas 40 bytes identifican el
  almacén ARM64?
- **Entrada:** APK `b54bc0e6...`, misma PoC, sin Login y sin escritura.
- **Procedimiento/límites:** búsqueda de IDs/padding en clases privadas, solape
  para bloques de 480 bytes, límites por candidatos y lecturas incompletas.
- **Resultado:** aparecieron secuencias parciales en varias clases, pero ninguna
  reunió todos los metadatos del contrato ni claves conocidas; no hubo almacén
  validado y `bytesWritten=0`.
- **Interpretación:** 12×40 es una hipótesis importada de x64, no contrato ARM64
  probado. Las coincidencias no autorizan parchear.
- **Evidencia / siguiente:** [continuación estructural](functional-forever-profiles-results.md); buscar
  una arista tipada, no más coincidencias de IDs.

### A1 — Alias de confianza ARM64 (2026-10-14, completado)

- **ID:** `arm64-trust-alias-20261014T163813-d0df8d24bf9044a7bdaf1e9aa2ba8a1e`.
- **Pregunta:** ¿Qué tipo y consumidor tiene el dato posterior al slot, sin
  atribuirlo por proximidad a CertStore o grupo 8?
- **Entrada:** PE AA64 build `1.60.1.70009`, 94.286.544 bytes, SHA antes/después
  `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`.
  Reporte autoridad: `/home/adrian/wowforever-artifacts/work/arm64-trust-alias-20261014T163813-d0df8d24bf9044a7bdaf1e9aa2ba8a1e/report.json`,
  SHA-256 `53e453bca51dd6b7848319f95cb2d036cbae3e6643eb8dbbfb6eb08137791b91`.
- **Procedimiento/límites:** análisis host-only de raw/RVA/BSS/relocaciones y
  aliases inicializados en `.rdata`/`.data`; dos nodos y una arista reversa, sin
  truncación ni alias adicional. Cobertura única de 17.446.912 bytes, cap de
  33.554.432; dos pasadas, 34.893.824 bytes reales, ventanas de 4.096 y cap de
  2.097.152. Presupuesto `sha_full_bytes` 188.573.088 y `analysis_bytes`
  35.539.980, total lector 224.113.068. No hubo nuevo desensamblado ni Ghidra.
- **Resultado:** alias DIR64 tipo10 desde RVA `0x4cb63b8` / raw `0x4cb55b8`
  hacia RVA `0x4a5ff20` / raw `0x4a5f120`. El destino es file-backed, no BSS,
  y corresponde a `slot + 32`, es decir, datos posteriores a los 32 bytes del
  slot. No se tipó el objeto, no se demostró consumidor ni enlace al grupo 8.
- **Interpretación:** el alias queda **descartado como objetivo directo demostrado
  de parche**. Esto no descarta toda relación con autenticación, que sea dato no
  usado, ni que el host tenga otros mecanismos; solo agota esta arista dentro de
  la cobertura declarada.
- **Evidencia / siguiente:** se reutilizó el reporte `EdSlotFlow` v3 negativo
  acotado (seeds 0 / refs 0 / functions 0), SHA
  `6e74fdd84dd06f18b24ad1c5ec198e7496a861f443b2c7c2eea7bb7a83b7eaa9`. El padre
  autenticó ambos reportes y comparó el hash previo fijado. El método estático
  del alias está agotado; **no repetirlo**. La captura de fragmentos runtime es
  solo una propuesta separada y pendiente de alcance.

#### Guardas, incidencias y verificación de A1

- El analizador actual es `scripts/diagnostics/reverse-engineering/arm64-trust-alias/analyze.py`
  con `tests/test_arm64_trust_alias.py`; ambos se añadieron y corrigieron en A1.
- Guardas reparadas: hash previo real y pinned, size guard antes del hash PE,
  identidad sin `atime`, salida privada `0700` sin symlinks y `CREATE_NEW/0600`,
  truncación del grafo y candidatos RVA32.
- El reporte T2 `ce4796...` es histórico, anterior a la corrección, sin
  autenticación previa ni pin y **no autoritativo**. El reporte autoridad nuevo
  es el SHA `53e453...` indicado arriba; no se retaggea ni cambia.
- Incidencias de instrumento conservadas: dos fallos de tests sintéticos sin PE;
  `uv` ausente en PATH del padre en una tentativa; constante pin ausente (3
  fallos/9 pasados), corregida antes de la ronda final. Verificación final del
  padre: `/home/adrian/.local/bin/uv run --offline pytest -q tests/test_arm64_trust_alias.py`
  → **12 passed**. No se suman rondas 6+11+12 ni se anuncia suite completa.
- El reporte previo de código de 2.156 bytes se conserva aparte: el padre
  revalidó esos 2.156 y el artefacto tenía 8.345 bytes adicionales. No hubo
  writes del PE, Btrfs, cuentas ni logs raw.

### H2 — Investigación estática PC/ARM64 (2026-10-13)

- **Pregunta:** ¿Las tablas stride40 o constantes primitivas ofrecen un punto de
  parche verificable?
- **Entrada:** PEs autenticados ARM64/x64; Ghidra y parsers host-only; hashes
  estables antes/después.
- **Procedimiento/límites:** clasificación de relocaciones DIR64, ADR/ADRP,
  aliases y hasta cuatro cuerpos de código con presupuestos acotados; sin
  ejecución, APK nuevo, Login ni lectura nueva del móvil.
- **Resultado:** las supuestas tablas contienen punteros, no doce claves crudas;
  se hallaron constantes criptográficas y destinos indirectos, pero no
  constructor, consumidor CertStore ni verificador.
- **Interpretación:** no hay offset, rutina ni sustitución demostrados. La vía
  dinámica x64 queda suspendida por CASC; la exportación Btrfs no se reabre.
- **Evidencia / siguiente:** [investigación PC](arm64-certstore-pc-investigation.md),
  [primitivas](arm64-certstore-primitive-consumers.md) y [Ghidra](arm64-certstore-ghidra-results.md);
  A1 ya está completado; cualquier captura runtime queda separada y pendiente de
  alcance.

## Próximo borde propuesto: fragmentos runtime (pendiente de alcance)

La captura limitada de fragmentos runtime está descrita en el plan
[`arm64-runtime-code-fragments-plan.md`](../.agents/planning/arm64-runtime-code-fragments-plan.md).
El usuario autorizó el 2026-10-14 la ampliación a32KiB por snapshot, máximo
dos/64KiB total, exclusivamente dos ventanas fijas de `.text` con cobertura
privada RX actual. Está implementada y la candidata host está auditada; todavía
no se ha ejecutado la captura física al registrar C1. No es un volcado de
heap/proceso ni una autorización para parchear.

### C0 — Preparación de captura: discrepancia de fuente (2026-10-14)

- **Pregunta:** ¿El código disponible reproduce los cuatro cuerpos del diagnóstico
  retenido y permite ampliar contexto sin capturar memoria arbitraria?
- **Procedimiento:** revisión de código y recetas en solo lectura; sin ADB,
  builds, cliente, Btrfs ni nueva captura runtime.
- **Resultado:** `functional_image_compare.rs::WINDOWS` del repo define solo el
  slot estático32B, no los cuatro cuerpos históricos. El marker fija el SHA del
  JNI suministrado, pero no su fuente/tree.31bd6bdd es SHA de APK, no commit.
  El lector de métricas actual no exige RX-only ni coteja maps antes/después.
- **Interpretación:** falta reconstruir una candidata diagnóstica reproducible;
  no tratar el código actual como fuente demostrada del APK histórico. La base
  histórica y maps RX tampoco demuestran pertenencia actual al módulo.
- **Siguiente:** fijar procedencia y guardas en la variante de captura; lista
  ampliada propuesta `0x223c000/0x4000` y `0x37b0000/0x4000`, ambas alineadas16KiB.
  Sin ampliación automática ni exportación si falla identidad/cobertura actual.

### C1 — Candidata de captura reproducible (2026-10-14)

- **Pregunta:** ¿Podemos retener las ventanas aprobadas con identidad, límites y
  procedencia comprobados sin cambiar el runtime/gráficos/cliente?
- **Resultado host:** captura separada, opt-in OFF por defecto, mutuamente
  excluyente con Login/parche. Ventanas0x223c000/0x4000 y0x37b0000/0x4000;
  máximo dos snapshots32768B a partir de20s/60s monotónicos. Artifactbinario
  SHA enlazado a metadata/nonce/índice; salida privada y collector acotado.
- **Revisión:** detectó fallos del instrumento antes del despliegue y se
  corrigieron. Ninguna variante inicial se instaló. La base histórica conserva
  INCOMPLETE/no prueba criptográfica de módulo; el lector normal32B no cambia.
- **JNI:** NDK24/API29,920472B,SHA
  `4c64838796cbe99beeadb12f6e6ee3704ac585bff73aa430c0966a5f2c018711`,
  cuatroPT_LOAD16KiB. Sidecar independiente fuente/toolchain antes/después,
  SHA `f584c568e461f7996c313ebff0a7c9496266309767f69649485db20c1ca07295`.
- **APK:** `arm64-prototype/functional-poc-candidate-05b23e474a7b546e.apk`
  bajo `/home/adrian/wowforever-artifacts/`,420551919B,SHA
  `05b23e474a7b546e1dcee0d14e54bad4157dd88774bce4a0c86f38052c54a8fd`,
  code64/API29/arm64-v8a, firma compatible. AuditPASS: JNI empaquetado exacto,
  soloJNI+marker diferentes en assets/lib frente a31bd6bdd, runtime intacto.
  Informes `...-audit.json`, `...-build-provenance.json`, `...-manifest.json`.
- **Verificación por ronda, no sumada:** tests nativos dirigidos10passed y
  65filtered; provenance/overlay10passed; collector18passed. Build nativo y
  Gradle offlineJDK17/2CPU/1worker PASS. No suite completa ni prueba de auth.
- **Siguiente:** un control120s+cleanup20 sin Login en xaga propio, actualizado
  sin borrar datos; aceptar solo capturas que pasen las guardas. Si no hay
  artefactos válidos, registrar el bloqueo, no inferir ausencia de código.

### C2 — Controles físicos de preparación, UI y captura (2026-10-14)

Esta sección es la actualización operativa de C1. Se leyó únicamente el árbol
externo `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/` y
los controles enumerados aquí; no se reabrieron logs de cuentas ni ensayos
anteriores. Cada fila conserva la pregunta, parámetros, resultado, límite de
interpretación y descarte. Los identificadores son prefijos del control o del
artefacto externo.

| ID / hora UTC | Pregunta y parámetros | Resultado observado | Límite, inferencia y descarte |
|---|---|---|---|
| `58705645ed14T181447` | ¿La UI puede activar la captura antes de preparar WoW? Perfil funcional, checkbox de captura solicitado, sin Login. | Falló antes de preparar WoW con `code-capture-checkbox-not-exact`; la casilla quedó fuera del viewport y no hubo captura. | Era un fallo de automatización/UI, no de WoW, Wine, CASC ni del formato de código. La causa fue la combinación de `Compose` con `verticalScroll` y límites de altura de 1036 en landscape: no había etiqueta visible única. El runner se corrigió a una pestaña acotada a `MAX18`, que deja el viewport visible; después enfoca la etiqueta exacta y usa Space para activar el opt-in. |
| `d3799ce65e14T181831` | ¿El perfil Forever y el opt-in llegan al arranque sin habilitar memoria? `profile=classic_forever`, `codeCapture=true`, `memoryPatch=false`, campos vacíos. | `nativeBegin` devolvió `ERROR capture_root_contains_a_symlink`; memoria leída `0`, sin poll nativo. Kotlin absorbió el error como no-op y WoW se lanzó igualmente. `launch-030.png` muestra gráficos limpios, Beta `1.60.1.70009`, arm64, sin credenciales visibles; la imagen quedó externa y no se publica. | El error no prueba ausencia de código ni fallo del runtime: no hubo sondeo nativo. Sí demuestra un bug de manejo de error Kotlin que permitía continuar. La captura fue `read-only`; `bytesWritten=0`; no hubo Login, autenticación ni código capturado. Los campos permanecieron vacíos y `Remember Account` sin marcar. |
| Auditoría de preflight | ¿La app propia y el JNI desplegado corresponden a una candidata auditada? APK `e096a...420553727`, SHA-256 `e096a814fbfea927c353cb3a74c1abb4e975141f2441f2781ff1fe62a3189bf1`; nativo `920840B`, SHA-256 `d3f80bce2491f22d146a7c938dc5816f30dfc73db5bb81ed48c982d2603a8534`. | Preflight limitado a la cabecera de la app propia; no se presentó como prueba del módulo WoW. | Solo acredita la identidad del artefacto de la app/JNI en ese control. No acredita captura, identidad de `WowB.exe` ni autenticación. |
| `f1e1c2f3f814T183313` | ¿`--prepare-only` detecta y rechaza la raíz insegura antes de arrancar WoW? | Falló antes de WoW con `capture_root_symlink_component_3_mode_40960_uid_0`. El modo es `S_IFLNK`; el componente 3 corresponde a `fixed/data/user/0` en la vista APPVIEW. La inspección `run-as` observó todos los componentes como directorios, raíz incluida, sin enlace. | Es una discrepancia de namespace/vista entre la comprobación y la inspección, no evidencia contra Windows/CASC, protocolo o runtime. La guardia sí bloqueó el arranque; no hubo poll ni captura. La salida fija quedó en `data/data/<mismo-paquete>/files/functional-poc/captures`; los nombres relativos del collector no cambiaron. No se introdujeron credenciales ni se usó chmod/link bypass. |
| Auditoría candidata `30a03dc...` | ¿La corrección host produce un APK reproducible y compatible con el dispositivo? APK `420553727B`, SHA-256 `30a03dc39133397cfe0807b67fbb0c213fa7d98385cbc7fd59ed7701dafef9a5`; JNI `920840B`, SHA-256 `f2475e8969937833bf0fcb10d2917947d18ff13b053743889ce7d37851c55669`; sidecar SHA-256 `6930871d7c610a4ac9504638f5489cac6f162449874c66ea476f5ba4214c368b`. | Fresh build NDK24/API29, cuatro `PT_LOAD` alineados a 16 KiB, mismo certificado y APK `code64`. | Es una auditoría de empaquetado/procedencia, no una prueba de captura ni de auth. El candidato de host posterior seguía pendiente al cerrar esta bitácora. |
| `8cc530c63c14T183720` | ¿La nueva variante prepara sin arrancar WoW ni hacer poll? `--prepare-only`, captura opt-in separada. | Estado `PREPARED`; `nativeBegin=ENDED`; no hubo WoW ni poll. | Control negativo correcto: demuestra que la preparación no lanza el juego, no que el proceso pueda capturarse. |
| `417f9d0ef0T183900` | Tras reboot limpio, ¿las guardas y la captura tardía encuentran el proceso propio? Sin Login, cuenta vacía, `memoryPatch=false`; preflight primero y captura en ventanas monotónicas de 20 s/60 s. | Preflight `ENDED`. `nativeBegin=BEGIN_CODE_CAPTURE_OPT_IN`, nonce `580bb0686ba24ebc9cc28c14f018a4b1`. Antes de 20 s hubo una comparación con una cabecera; otra cabecera quedó ilegible: `INCOMPLETE`, 32 B iguales y `source unchanged`, no función demostrada. Pasado el umbral aparecieron >1024 candidatos; la inspección nativa se detuvo por cap. Hubo `attemptCount=1..7`, `successCount=0` y ningún artefacto. La pantalla quedó en Login gráfico limpio, Beta `1.60.1.70009` arm64, sin credenciales. `UID=0/PID=0` en polls iniciales fueron placeholders, no el WoW objetivo. | La raíz de captura dejó de ser el bloqueo del control anterior, pero el cap de inspección sigue siendo una limitación causal distinta. Las seis clases del diagnóstico normal continuaron y no observaron claves; por su cobertura acotada e incompleta eso **no** prueba ausencia global ni ausencia en el proceso. No hubo Login, `bytesWritten=0`, `moduleProof=false` ni `cryptographicModuleProof=false`. |

#### C2: guardas, implementación y evidencia que no se debe sobreinterpretar

- La revisión independiente de predespliegue cerró todas las guardas anteriores de
  C1. El preflight Kotlin ahora es fail-closed: un error de `begin`/`end` no
  permite arrancar el juego. `--prepare-only` está OFF por defecto y deniega la
  combinación Login/captura. La salida es constante y privada; no se cambió
  el collector relativo, no se escribieron `Config.wtf`/PE/cliente, no se modificó
  Btrfs ni el root del juego y no se concedió escritura de memoria.
- La política de fallback aprobada para el historial no eleva ningún cap: solo
  puede consultar una base PE temprana retenida válida si coinciden maps actuales
  RX, identidad de proceso y procedencia. El resultado sigue `INCOMPLETE`, con
  `moduleProof=false` y `cryptographicModuleProof=false`. Sin historial no hay
  artefactos; cualquier otro error se rechaza. La revisión aceptó este alcance,
  aunque falta una prueba de integración completa.
- Verificación por rondas independientes: antes del `prepare-only`, 15 pruebas
  negativas de viewport/Login pasaron en
  `tests/test_functional_poc_code_capture_runner.py` y el runner de perfiles.
  La ronda intermedia registró Rust `11 passed, 65 filtered` y overlay del runner
  `17 passed`; no se suman. La ronda más reciente registró `12` pruebas nativas
  pasadas y `65 filtered` después del parche del clasificador de fallback. No se
  anuncia una suite acumulada. El nuevo builder host/candidato seguía pendiente.
- No se recolectó Ghidra ni exportación runtime. No hay PNG actual que pruebe
  identidad de fuente, pertenencia criptográfica al módulo o un snapshot de
  código; las imágenes solo acreditan el estado visual de Login. Por tanto,
  `capture root unblocked` no equivale a `code captured`, y ningún control actual
  predice autenticación.

#### C2: decisión de continuidad

El paso siguiente es **un único control nuevo**, causalmente distinto, después
de auditar el APK host actual (trabajo de build/auditoría del padre). No se
predice éxito de captura ni de autenticación. Si el control vuelve a terminar
en `INCOMPLETE`/cap, se conserva el resultado como tal y se elige una sola
continuación discriminante; no se reabre el barrido general, no se aumenta el
cap y no se reutilizan los Login históricos como explicación.

### C3 — Estado final de la ronda: captura sin snapshot (2026-10-14)

C3 continúa cronológicamente a C2 y **no reemplaza sus resultados históricos**.
Se conservaron únicamente los artefactos de los controles actuales de xaga y no
se amplió el caller, el cap ni la exportación. El objetivo sigue siendo
discriminar la procedencia de una imagen PE válida, no predecir captura,
autenticación o parche.

| ID / hora UTC | Pregunta y parámetros | Resultado observado | Límite, inferencia y descarte |
|---|---|---|---|
| `074791bf5914T190139` | ¿El APK con fallback histórico permitido cambia el resultado de C2? APK `d27e...`, `source=custom`, fallback de cap habilitado solo para procedencia PE histórica prevalidada; `current-final-1792004532156.jsonl` es el único log actual de esa corrida. | Proceso propio `PID=14542`, `start=8013`, `UID4=[10330,10330,10330,10330]`, `PPID=7176`; lease `039beeeffbb84ebbe15d2248e514cf9b`. `codeCapture`: `attempts=1..8`, `success=0`. No había historial disponible para inferir desde el cap. | En ese control no hubo métricas explícitas por intento; no se debe reinterpretar como cero intentos ni atribuir todos los fallos al cap. El collector no se consultó. El comparador normal retuvo una cabecera anterior, pero la captura independiente solo tenía estado de comienzos de maps; el comparador además pudo usar `sourcePreferredImageBase` dentro de un map privado legible. Es una discrepancia metodológica concreta, no prueba de causa única. |
| Revisión de builder/procedencia | ¿La semántica de permisos distingue cabecera PE legible de cuerpo ejecutable? Se corrigió la semilla de cabecera: `private readable` (`r--`) permitido; el cuerpo sigue exigiendo RX privado estricto, sin RW. | La prueba sintética real con interior PE `RO` + `.text` RX pasó; un cuerpo RO falla como corresponde. Se añadió seed de `imageBase` y comienzos de maps con unicidad cap1024, conservando cabecera propia, ejecutable y SHA de fuente; fallback ASLR usa map start. Revisión aceptada. | Un MZ aislado o una mera rebase no son prueba de módulo. APK5 intermedio con semilla RX de cabecera incorrecta **no fue instalado**. La ronda host registró `16 passed, 65 filtered`; no se suma con rondas anteriores. |
| Auditoría de artefactos finales | ¿El APK desplegado conserva runtime/assets y solo cambia la instrumentación autorizada? APK final `2db5dfcf434b8bcc`, tamaño `419082021`, SHA-256 `2db5dfcf434b8bcc502ddc6cca10863c34c6448cf667ab58fd51781def101494`; JNI `932296B`, SHA-256 `61ff4ce8da43c2986c677cc386db23e39c6bec2bea43cfb62c08506c15cece7f`; sidecar `5cb3ead37e0a0ebd8bfd7f2ec0046968550fc7e90878a71310af8d093d34cbbb`. | NDK24/API29, cuatro `PT_LOAD` a 16 KiB, `code64`, certificado v2 igual. La comparación exacta del padre pasó: entre `d27e` y `30a03dc...` solo cambian JNI/marker, con `174` assets/lib y `661.957.208` bytes lógicos comparados; entre `2db` y `d27e`, solo JNI/marker. Variación de compresión APK/tamaño DEX no equivale a runtime cambiado. | La auditoría acredita empaquetado y delta acotado, no captura, identidad del `WowB.exe` ni auth. La candidata `d27e` tenía APK de `418605320B`, SHA completo `d27e9820af593de4ea8019e7845b20349ef298a271c35a3280ff42fd32e4cf83` y JNI `e5092b56a3952d67e0847db9c243b251d6e02216ea7927d1052595a3500eda46` (`924440B`); queda como procedencia revisada, no como fuente para reutilizar automáticamente. |
| `1a634b7038T191708` | Tras actualizar al APK final, conservar datos y reboot limpio: ¿el arnés produce una captura válida? Log actual único `final-1792005458147.jsonl`; `PID=14113`, `start=7805`, `UID=410330`, `PPID=2235`; nonce `413b0b0623d44d879eba9821e627d841`. | El resultado observado fue del arnés/UI, no una confirmación nativa de captura. `begin` preflight terminó `ENDED` y luego apareció `BEGIN_CAPTURE`. En la fase temprana no hubo cabecera PE validada; hubo diagnóstico de map privado RX frente a cabecera no encontrada, estado desconocido y sin historial. Tras 20 s hubo `11` intentos, cap1024, `success=0`, `historicalAvailable=false`. El comparador normal también encontró `0` cabeceras en este ensayo. | El primer flag `EARLY_NOT_READY` quedó latched por error; se corrigió para que solo sea terminal después del último APK: los `note_early_failure` transitorios no envenenan el periodo temprano de polls metadata-only (<20 s), y se limpian identidad, fuente y ambigüedad. Esto es una corrección del instrumento, no evidencia de ausencia de PE, código o constructor. |
| Collector C3 | ¿Existe algún snapshot fijo completo que exportar? Consulta real con el nonce vigente. | El comando con `collect_functional_code.py --serial DEVICE_SERIAL --output /home/adrian/wowforever-artifacts/physical-trials/xaga-20261002 --nonce 413b0b0623d44d879eba9821e627d841` devolvió `CollectorError: capture lease contains no complete fixed snapshots`. No se retuvo binario y no fue posible análisis Ghidra/LLVM runtime. | Es un fallo validado de contrato de retención, no una conclusión `code=0` ni ausencia de código. No hubo Login, auth, escritura de memoria, parche de `WowB-ARM64.exe` ni modificación Btrfs. Se solicitó force-stop, pero no se trata como prueba de quiescencia; el APK final permaneció instalado y no se borraron datos de usuario. |

#### C3: estado y siguiente gate

- La corrección de `EARLY_NOT_READY` pasó `17 passed, 65 filtered` en la ronda de
  fuente; no se acumula con cifras anteriores. El APK final desplegado todavía
  **no contiene esa última corrección de fuente**, por lo que no se debe reutilizar
  su sidecar ni anunciar que C3 la validó en dispositivo.
- No hay captura nativa retenida, archivo para Ghidra/LLVM, Login, auth, rechazo
  de clave del servidor, escritura en `WowB.exe`/`WowB-ARM64.exe`, modificación
  Btrfs ni cambio de los assets runtime originales. Forever permanece
  `AUTH_UNCONFIRMED`; no se aplicó parche en archivo ni en memoria.
- No se planifican más ensayos físicos en esta ronda mientras no se cierre el
  gate pendiente. El estado objetivo es **no resuelto**, con bloqueo explícito de
  detección/procedencia del instrumento, no una solución ni una ausencia global.
- Siguiente pregunta host-only, antes de otro juego físico: ¿el file descriptor
  nativo mantiene hash estable de la imagen, una cabecera PE válida al comienzo
  y una correspondencia inequívoca con el map privado, separando contadores
  escalares por razón de error? Solo después de esa revisión y de un nuevo APK
  con SO/provenance fresco se decidirá otro control; no habrá aumento de cap,
  caller genérico ni exportación adicional automática.

### C4 — Retoma con candidata corregida

- Nuevo guard `operation` serializa toda la observación/commit: una lectura
  concurrente no puede restaurar histórico invalidado por identidad/ambigüedad.
  `EARLY_NOT_READY` permite observar metadata mientras el loader inicia dentro
  del límite20s; las demás guardas conservan fail-closed y el máximo64KiB.
- Verificación inicial: `18 passed, 65 filtered`; revisión independiente
  aceptada, sin repetir tests previos. NDK24/API29,4PT_LOAD16KiB, Gradle offline
  y audit de JNI+marker frente a2dbPASS. Sin cambio de runtime o cliente.
- Candidata host: `arm64-prototype/functional-poc-candidate-7f11c6c827c2b8b4.apk`
  bajo `/home/adrian/wowforever-artifacts/`,419083061B/SHA
  `7f11c6c827c2b8b454b82b71c101d16c5d26c4c946fd0d56eab942275f42eb3d`.
  JNI933576B/SHA `c7b904768a8cdff828cf65996944ad927d84d521953fe827d64d4b5fda0519f9`;
  sidecar `c2aad74acf219c31e1767321de65c775a14cdba1bbd205edf2d4396ba20cafe6`.
- Control físico nuevo120s sin Login pendiente al registrar esta entrada; el
  resultado posterior y la ronda scalar de `19 passed, 65 filtered` se documentan
  abajo y no se suman a esta verificación inicial.
  No se retaggearon los APK/sidecars anteriores ni se anunció captura/auth.

#### C4 — Resultado físico y diagnóstico temprano

- **Control:** `9b88a31dfb14T194010`, sobre la variante `7f11` con operación
  serializada, serial del dispositivo correcto y `EARLY_NOT_READY` no terminal.
  El único log actual fue `final-1792006839165.jsonl`; las otras tres fuentes
  fueron históricas y no se mezclan.
- **Identidad:** `PID=14128`, `start=7911`, `UID4=[10330,10330,10330,10330]`,
  `PPID=2232`, nonce `c2ba22464ad5418fbd3fb56976923fe8`. El historial no estaba
  disponible; durante la fase temprana hubo ambigüedad entre cuerpo y cabecera.
  Más tarde se alcanzó el cap con `snapshot attempts=0`, `success=0`, sin
  snapshot retenido. El comparador normal sí encontró una cabecera temprana
  (`1`), a diferencia del ensayo C3 anterior con `0`; esto no convierte la
  diferencia en una explicación causal.
- La corrección de espera temprana se redujo a un diagnóstico **solo escalar**:
  no añadió bytes raw capturados. La nueva candidata `ce111` quedó auditada con
  APK `419086265B`, SHA-256
  `ce1113961d756bc571bc35d2a8a33a52339e9e94bf7ac2ea8df1b79138c336bb`, JNI
  `939832B` SHA-256
  `aea17b2a262c304c56592035e66f6b87eabde02c33bca017c0e38dc4e6caa794`, y
  sidecar SHA-256
  `22e175a398b5d82adc0f73697f07ea5274bfee527a6f54a3e868809462484b50`.
  La ronda dirigida fue `19 passed, 65 filtered`; no se combina con la ronda
  anterior de C4.

#### C4 — TLS host, aislado del resultado ARM64

El permiso de evaluar cualquier método incluye una variante aislada con
validación TLS desactivada, pero no se implementó ningún bypass ni se tocó la
confianza de otra aplicación. Los únicos controles TLS fueron dos handshakes
desde Linux host, sin credenciales, datos de aplicación, Wine ni cliente WoW:

| Control | Resultado | Qué descarta y qué no |
|---|---|---|
| T1, TLS1.3 estricto contra `auth.gpon.com.co:1119` | SAN válido, vence `2026-12-17`, `TLS_AES_256_GCM_SHA384`, 432 ms | Descarta endpoint inalcanzable o necesariamente inválido desde ese host. No prueba CA/trust store de Wine/Android, RPC, CertStore ni handshake del juego. |
| T2, TLS1.2 estricto forzado al mismo endpoint | `ECDHE-ECDSA-AES256-GCM-SHA384`, 311 ms, aceptado | Descarta dependencia exclusiva de TLS1.3 desde ese host. No prueba selección de cipher ni autenticación de cuenta. |

No se aumentaron las pruebas de Login ni se introdujo una cuenta. T1/T2 no
prueban una cadena CA inválida para Wine ni un handshake RPC/CertStore, y no
explican `WOW51900317`.

### C5 — Invalidación correcta del historial por cobertura de maps

- **Control:** `1ff95048cc14T194902`; log actual `final-1792007374834.jsonl`.
  Identidad `PID=14303`, `start=8082`, `UID4=[10330,10330,10330,10330]`,
  `PPID=6254`, nonce `fee0068c9a67469ea0e0e8dd3773aa89`. Primero `EARLY` quedó
  listo con ambos mapas RX e historial provisional `true`; después falló la
  lectura de cabecera y ese historial siguió provisionalmente `true`. Cuando
  cambió la cobertura de maps, la guardia invalidó correctamente la candidata
  histórica. El control terminó en cap tardío: `attempts=0`, `success=0`, sin
  archivo ni necesidad de consultar el collector.
- Esto es un discriminante nuevo —no la hipótesis «nunca hay cabecera»— y no
  demuestra que la invalidación de cobertura sea la causa física única. La
  revisión identificó un bug de extensión de mapa: se intentaba cubrir el map
  completo (`4096..67108864`) al intersectarlo con dos ventanas RVA. La
  corrección recorta inicio/fin **solo dentro de cada ventana**, comprueba
  offset de archivo y que las regiones anónimas sean cero/file-backed, y solo
  coalesce segmentos dentro de la misma ventana.
- El payload conserva `r-xp`; no aumentaron cap ni política. El resultado deja
  `historical=false`, `moduleProof=false` y no retiene bytes. La prueba sintética
  y revisión fueron aceptadas: `21 passed, 65 filtered`, ronda independiente.
- APK de esta variante: `c8c01...`, `419081968B`, SHA-256
  `c8c01faddcb8f863908390fa73800528a8b066a5959ad9bc3d3a484d2483c9e3`, JNI
  `929592B` SHA-256
  `941481727051615008eb65eb5f4395b501ac812a1f962ebd920251af2a928afb`,
  sidecar SHA-256
  `25a8decb8fa52afecdc86a207dd481683b803dc9fe92342f7a7083e15cdd502e`.

### C6 — Separación de cabecera metadata y cuerpo RX

- **Control:** `667fef660f14T195924`; log actual `final-1792007996083.jsonl`.
  Identidad `PID=14771`, `start=8007`, `UID4=[10330,10330,10330,10330]`,
  `PPID=5976`, nonce `a1e34f994da54eb8a9231b12cd414336`.
- La clasificación temprana fue `header-matched-payload-non-rx`: ambos maps
  eran `RW`, aunque la cabecera era válida y el candidato normal era `1`.
  Después falló la cabecera preferida; el historial nunca se retuvo. El control
  acabó en cap tardío con `attempts=0`, `success=0`. Esto demuestra que exigir
  RX demasiado pronto puede excluir una cabecera PE válida; no demuestra
  desencriptado, TLS, servidor, CertStore ni constructor de código.
- La solución aprobada separa fases **dentro del mismo presupuesto de captura**:
  en carga solo se lee metadata de cabecera; se observan mapas RW para identidad
  pero no se leen ni retienen bytes de esos cuerpos. Se conservan UID, nonce,
  cabecera before/after, backing, rangos, offset, inode y device estables,
  ignorando únicamente el cambio privado `RW`/`r--`→`RX`. La fase tardía solo
  acepta cuerpo privado RX unchanged before/after y todas las identidades; un
  cambio de backing rechaza sin capturar.
- La cápsula histórica sigue `INCOMPLETE`, `moduleProof=false` y
  `cryptographicModuleProof=false`; no hubo writes ni heap leído/retendido. La
  revisión de fuente pasó `23 passed, 65 filtered` y fue aceptada. El último
  build host de otro builder seguía en progreso; no se anticipa su APK ni una
  captura.

#### Estado C4–C6 y próximo paso

La ronda completa permanece en **cero snapshots de código** y sin Ghidra/LLVM,
Login, autenticación o parche. No se modificaron Btrfs, el PE ARM64, el cliente
ni los binarios runtime originales. No se repite el APK `c8c01` automáticamente.

El siguiente paso es un único control después de cerrar los gates de separación
de fases, procedencia y build; solo se analizaría el contenido si existe un
snapshot válido. No se aumenta el cap, no se amplía el caller y no se afirma
resultado de captura antes del próximo artefacto que entregue el padre.

### C7 — Lectura válida, commit atómico fallido (2026-10-14)

- **Control:** `7a52c580a814T201325`; log actual `final-1792008840326.jsonl`.
  Proceso propio `PID=14781`, `start=8290`, `UID4=[10330,10330,10330,10330]`,
  `PPID=7019`, nonce `c06628c353eb46b58bf7158fcf6fe95e`.
- APK `2d52ecc`, fuente `23`: los campos metadata identificaron el proceso y
  pasaron a historial válido; la lectura tardía del payload pasó fuente,
  identidad, RX y backing before/after. Hubo intentos `1..8`, pero el commit
  devolvió literalmente `capture binary commit failed`; se retuvieron `0`
  binarios nativos. Hubo bytes temporales en el dispositivo y falló la limpieza
  posterior al commit. El `errno` genérico no permite atribuir la causa a
  hardlink o SELinux.
- La corrección sustituye `hardlink + unlink` por
  `renameat2(RENAME_NOREPLACE)`. El preflight de almacenamiento usa un marcador
  privado pequeño por nonce y prueba escritura, colisión `EEXIST`, `fsync` y
  cleanup antes del juego, sin hacer poll. La revisión detectó y corrigió cleanup
  sobre rutas preexistentes ajenas. La fuente atómica registró `26 passed, 65
  filtered`; no se acumula con rondas anteriores.
- Preflight `c4324b9c35` (`20261014T202741`) con APK ensamblado `746d` quedó
  `PREPARED`, `nativeBegin=END` y sin juego/cabecera de memoria. La instalación
  incremental falló antes de instalar el APK; el fallback interno por stream sí
  funcionó. Después se usó explícitamente `--no-incremental` para evitar repetir
  la misma ruta ADB, que no era un fallo del APK. Native `938424B`, SHA-256
  `746d53d8b6e3c83160a94624182c52e99d1d502d3f4dcbb8c2e59c8ab1e6aa24`.
  `746d` es el hash SO, no el hash APK; el APK ensamblado se identificó por
  SHA-256 `b57a38035e22f28574827fe501f01f19941d7501be8649a0a50b3887b2bad475`
  y `416482663B`.

### C8 — Clasificación de paths anónimos y baseline de ensamblado

- **Control:** `926cd03c62T202906`; log actual `final-1792009777767.jsonl`.
  Proceso propio `PID=14514`, `start=7900`, `UID=10330`, `PPID=6997`, nonce
  `e3b4f51766fb485c9ac7c2d06aac03dc`. La fase temprana vio cabecera con cuerpo
  `RW`, sin historial; el control terminó en cap tardío con `attempts=0`,
  `success=0`.
- El normalizador trataba cualquier `[anon:...]` como pseudo. Se restringió
  `map_path_kind`: solo `[anon:*]` con `inode=0` y `dev=00` es anónimo; heap,
  stack, `anon_shmem` y `vdso` siguen siendo pseudo/rechazados. Un payload `RW`
  continúa rechazado. La ronda registró `27 passed, 65 filtered`; no se suma.
- Un APK GameNative fresco `695ca` mostró ocho cambios DEX inesperados y un
  marker nativo. La investigación encontró 19 clases core/UI no relacionadas
  con causa conocida; seis overlays PoC conservaron hashes y las 375 filas de
  prefijos del método seleccionado mantuvieron hashes normalizados idénticos
  entre base vieja y nueva. Manifest, `aapt` y certificado coincidieron. El
  padre **no desplegó** ese APK con cambios DEX no explicados.
- Decisión de ensamblado: baseline byte-pinned `746d` más JNI fresco
  `ff414e97291c9864523a35c7e480daee1d944337326adf698429b00cd31616dc`,
  `938816B`, y marker del `695ca`; sidecar
  `6c8ca3186ac9ae2863a1edec5ab5c55658c00fb59fe5115f850ffc14e1f4678e`.
  No se reclama provenance completa del GameNative nuevo ni causa conocida para
  el core desconocido; se conserva la baseline sin esos cambios.
- APK ensamblado `75d9510431ccd5a1`, `419082663B`, SHA-256
  `75d9510431ccd5a1dc7870eaf3db07aa0ca2c1d8f0afcfe13a4b6b0c8fdd6228`;
  audit `f1979f16bccf39783eb0d237f7d256ea6334dbe96937100de43f7840bb765733`;
  reporte DEX `3a9b3d98fad1442a0b24091b4d61258958b825519e284aa7c0578fedbf0e7630`.
  Las `1934` entradas ZIP comparadas dieron delta exacto solo JNI+marker; el
  V2 signing block no es una entrada ZIP. Runtime, XML, recursos y DEX quedan
  sin cambios frente a la baseline física. No se modificaron Btrfs, cliente,
  EXE, credenciales ni TLS.

### C9 — Re-selección de copia PE durante la observación

- **Control final:** `b176855b69T20261014T205045`; log actual
  `final-1792011078641.jsonl`. Proceso `PID=14695`, `start=8065`,
  `UID=10330`, `PPID=6343`, nonce `665131af39224d6cacfb6959e736e41f`.
- La fase temprana quedó metadata-ready, RX e historial `true`; después apareció
  `early candidate backing changed during observation` y se invalidó el
  historial. Hubo `10` intentos tardíos, cap y `success=0`. **No se afirma** que
  el backing físico hubiera cambiado: el early finder re-seleccionó un candidato
  PE global y pudo comparar otra base elegible, una copia PE raw file-backed
  legible, contra la base histórica. El bug concreto es que una copia PE ajena
  puede rebindear e invalidar falsamente la base; la causa física final no queda
  probada porque no se registró el escalar `baseChanged`.
- Corrección aprobada: fijar una sola base histórica y refrescar en early solo
  esa misma base. La cabecera puede faltar y conservar confianza histórica; un
  layout válido pero incorrecto se rechaza. La metadata current se normaliza
  contra la base antigua ignorando únicamente permisos privados durante la fase;
  el frame de proceso sigue exacto. La comparación before/after de metadata,
  incluidos permisos, debe ser exacta; la revisión descubrió y corrigió una
  relajación incorrecta ahí. El payload tardío conserva las dos ventanas/64 KiB,
  RX privado estable y sin relajar ambigüedad.
- La fuente `source30` pasó `30 passed, 65 filtered`; revisión independiente
  aceptada. El nuevo build del otro builder seguía pendiente y no se anticipa su
  APK ni una captura. El APK `75d` queda como baseline privada pendiente, no como
  evidencia de autenticación.

#### Estado operativo C7–C9

La ronda continúa con **cero snapshots nativos retenidos**: C7 leyó payload pero
falló el commit; C8 y C9 terminaron por guards/cap sin binario completo. No hay
Ghidra/LLVM, claves, Login, auth, escritura de memoria, parche del ARM64 EXE,
Btrfs modificado ni bypass TLS. La instrumentación tiene correcciones revisadas,
pero no está demostrado que se haya eliminado toda causa de bloqueo ni que
Forever funcione.

`source30` es el gate actual. Cuando exista el nuevo build aprobado, se podrá
considerar **un único control** con la baseline `75d` y solo cambios JNI/marker;
no se repetirá automáticamente `c8c01`, no se elevará el cap y no se relajarán
backing, frame, identidad o RX. Si el backing pinned cambia realmente, el loop
de captura raw se detiene sin convertirlo en una captura válida.

## Navegación de continuidad

- [Índice temático](README.md) → [roadmap](roadmap.md) → este estado.
- [Perfiles y resultados funcionales](functional-forever-profiles-results.md)
  contiene la evidencia primaria de Login y `bytesWritten=0`.
- [Exploración del parche](arm64-certstore-patch-exploration.md) y
  [flujo estático](ed25519-static-slot-dataflow.md) contienen los límites
  técnicos y el reporte autenticado de A1.
