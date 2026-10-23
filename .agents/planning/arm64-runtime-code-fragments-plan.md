---
id: arm64-runtime-code-fragments-2026-10-13
state: paused
date: 2026-10-13
---

## Summary

**Suspendido por instrucción explícita del usuario:** no continuar capturador,
claves en memoria o análisis estático hasta identificar el fallo real de conexión.
Nuevo resultado D1: `docs/arm64-wine-auth-diagnosis.md`; instrumentos verificados
OFF. El contenido siguiente conserva historial, no autoriza otro build/captura.

### Decisión de alcance del 2026-10-14

El usuario autorizó la captura original con «adelante» y preguntó si conviene
ampliarla. **Autorizado:** cuatro fragmentos/1756 bytes por snapshot, máximo dos.
**Ampliación aprobada:** sustituirlos por dos ventanas fijas de
16KiB que incluyen esos cuatro cuerpos; 32KiB por snapshot, 64KiB total.
Aprobación del usuario el 2026-10-14: «venga hagámoslo a ver si solucionamos ya».
No se ha implementado ni ejecutado aún ninguna captura de bytes runtime.

La evaluación detectó una discrepancia importante: el código del repositorio
actual compara solo el slot estático32B, no los cuatro cuerpos documentados en
el APK retenido31bd6bdd. Antes de construir o lanzar una variante hay que fijar
su procedencia y probar sus rangos reales. La autorización no permite atribuir
al código actual las mediciones del APK anterior.

### Ampliación propuesta: contexto ejecutable fijo, no volcado de proceso

1. Rangos fijos: RVA `0x223c000`/`0x4000` y `0x37b0000`/`0x4000`. Ambos
   alineados a16KiB, incluyen los cuatro cuerpos anteriores sin solaparse.
   Estos límites son del diagnóstico, no fronteras de funciones demostradas.
   Confirmar desde el PE autenticado que los rangos completos pertenecen a
   `.text`, están respaldados por archivo y tienen atributo ejecutable.
2. Conservar como máximo dos snapshots por lease:32768 bytes cada uno,
   65536 bytes binarios totales. Presupuesto separado para cabeceras/maps,
   metadata y hashing; contabilizar relecturas. No seguir llamadas ni ampliar
   automáticamente si salen de esas ventanas.
3. Opt-in distinto del lector normal, apagado por defecto. Lista interna fija,
   sin parámetros genéricos de PID, direcciones, RVAs, tamaños o rutas de salida.
   Identidad del proceso propio y origen PE antes/después, validaciones actuales
   de maps antes/después y permisos exclusivamente privados `r-xp`; rechazar
   RW/RWX, shared, dispositivos, gaps, lectura parcial, cancelación y ambigüedad.
4. Eliminar buffers pendientes si falla una guarda antes de persistir. Salida
   privada0700/0600 CREATE_NEW por nonce, sin bytes en logs; artefactos fuera del
   repo. Control sin Login/cuenta introducida; no capturar heap, stack, `.data`,
   `.rdata` ni memoria completa. RX y `.text` no prueban que todos los bytes sean
   instrucciones: señalar literal pools/datos embebidos sin presentar un volcado
   general como «solo código» ni prometer ausencia absoluta de datos sensibles.
5. Distinguir identidad de proceso de pertenencia al módulo. Con cabecera previa,
   maps RX estables y proceso estable puede haber una observación diagnóstica,
   pero sigue `INCOMPLETE`, base histórica y módulo actual no demostrado. No
   ejecutar esos bytes ni usarlos como autoridad para escribir/parchear. Si no
   hay base retenida fiable o las coberturas actuales cambian, no guardar bytes.
6. Análisis Ghidra/LLVM en proyectos externos nuevos, sin ejecutar el cliente;
   importar fragmentos solo como representación diagnóstica. Comparar ambos
   snapshots y archivo; limitar CFG a las ventanas/cuerpos confirmados y reportar
   destinos fuera de cobertura sin obtener memoria adicional.

### Discrepancias de procedencia y guardas: evaluación C0

- `crates/forever-patcher-jni/src/functional_image_compare.rs:26-28` define solo
  `upstream-ed25519-prefix-slot`/RVA0x4a5ff00/32B. El fallback
  `scan_cached_windows` conserva la hipótesis de cabecera histórica, no un mapa
  actual del módulo; el lector existente no exige RX-only ni dos lecturas de maps.
  No reutilizarlo sin nuevas guardas para retener las ventanas ampliadas.
- `scripts/arm64-prototype/app/apply_functional_poc.py::marker_bytes/apply` fija
  el commit GameNative, hashes de overlays y hash del ELF suministrado, pero no
  manifiesto fuente/tree del JNI.31bd6bdd es prefijo de SHA de APK, no commit.
  Congelar manifiesto de fuentes JNI/Cargo.lock, receta, toolchain y ELF nuevo;
  cotejar el SHA del ELF dentro del APK para evitar outputs Cargo obsoletos.
- Receta genérica nativa usa linker API26; pruebas anteriores documentan API29.
  Esta variante usará NDK24/API29 y verificará realmente alineación ELF16KiB,
  sin editar el runtime ni cambiar la receta genérica por suposición.
- Disponibilidad del móvil desconocida; C0 no invocó ADB ni inspeccionó cuentas.
  No habrá prueba física si no se identifica el dispositivo propio/candidato
  esperado o no se conserva la PoC limpia restaurable.

### Orden de ejecución tras aprobar la ampliación

- Fijar procedencia y contrato binario/opt-in, implementar guardas/exportador y
  tests dirigidos, incluyendo la lista ampliada exacta y presupuesto65536B.
- Revisión independiente por retención de memoria y fronteras de identidad.
- Cross-build/auditoría de APK candidata, solo cambios JNI y UI/marker propios
  necesarios; no sustituir datos/runtime/drivers/PE del juego ni tocar Btrfs.
- Un control120s+cleanup20 sin Login; conservar hasta dos snapshots y analizar
  exclusivamente esos bytes. Reboot solo si el dispositivo dedicado está libre,
  sin interrumpir otras sesiones; si no se puede verificar, registrar el bloqueo.
- Documentar resultado/negativo y única continuación discriminante. No reintento
  ni ampliación automática, ni anuncio de autenticación a partir de desensamblado.

El alcance original que sigue se conserva como historial. La ampliación aprobada
reemplaza su lista/límites, no se suma como un tercer snapshot
ni autorizará retener otras regiones.

El control f7f30b574c distingue transformación de vaciado simple: los cuatro
rangos tardíos contienen datos diversos en maps privados RX y repiten conteos
en dos lecturas sin un informe de aserción nuevo. No basta para saber qué código
son: hasta ahora solo conservamos métricas. Proponer una captura **solo de estos
1756 bytes ejecutables** para analizarlos con Ghidra en el PC, sin volcado de heap,
Login ni escritura en el proceso. Es una ampliación explícita de los datos retenidos,
no una autorización para parchear. No implementado ni ejecutado; alcance original
autorizado el 2026-10-14; ampliación definida arriba aprobada en la misma fecha.

## Content

### Alcance cerrado

- Los cuatro rangos públicos actuales: RVA `0x37b1998`/60,
  `0x37b1af8`/216, `0x223c820`/1000, `0x223cc08`/480.
- Solo el WoW ARM64 propio de la lease vigente: PID/startTicks/UID4/PPID,
  nonce/cmdline/ancestry y fuente PE retenida antes y después. No caller PID,
  dirección, RVA, ruta de salida o longitud arbitrarios.
- Captura opt-in diferenciada del lector normal; por defecto siguen métricas sin
  bytes. Máximo dos snapshots por lease, 3512 bytes binarios totales y metadata
  acotada. Solo cobertura **privada legible y ejecutable** en los cuatro rangos,
  verificada antes y después; rechazar regiones compartidas, escribibles,
  dispositivos, gaps, lecturas parciales, cambios de identidad/cancelación.
- Fuente fija del cliente70009 y los mismos cuatro rangos del archivo; mantener
  explícita la limitación de la cabecera histórica si no hay cabecera actual.
  Un snapshot con esa base sigue INCOMPLETE/no prueba del módulo, no Store válido.
- Guardar únicamente artefactos privados0700/0600 por nonce, sin secretos en logs,
  fuera del repo y sin publicar los fragmentos. Sin stack, heap, cuenta,
  certificados privados ni otras regiones de datos. No capturar credenciales;
  control nuevo sin Login, campos vacíos y volumen bajo.
- JNI sigue sin apply/write; no cambiar selector de proceso, flags/claves/padding,
  autoridad de escritura, runtime, drivers, cliente en disco o imágenes originales.
- PoC gráfica limpia retenida intacta, candidata diagnóstica/versionada por SHA,
  restaurable. El escaneo normal conserva sus resultados/cobertura.

### Análisis en el PC

Autenticar metadata, nonce/lease, tamaños, SHA y comparación fuente/runtime.
Importar el PE de referencia en un proyecto Ghidra **nuevo externo** y superponer
solo esos cuatro fragmentos en la representación del proyecto, no escribir un
WowB modificado. Desensamblado restringido por cuerpo y análisis de llamadas,
accesos y convenciones; registrar destinos fuera de los rangos como no resueltos,
sin obtener más memoria automáticamente. Comparar con LLVM y señalar dónde
la reconstrucción es válida o falla. Si parecen instrucciones coherentes,
seguir únicamente consumidores/callees demostrados mediante otro alcance concreto.
No preparar ni aplicar un parche a partir de la diferencia de bytes sola.

### Verificación prevista

Tests de lista/RVA/longitud fija, límite dos snapshots, opt-in/default-off,
gaps/RW/shared/partialread, estado tardío/identidad/cancelación y ausencia de
campos genéricos de dirección/heap. Revisión independiente de la captura.
Build NDK24/API29/16KiB, JDK17/offline/CPU2/Xmx4G/1worker y auditoría SHA nativa
empaquetada, evitando los outputs Cargo obsoletos ya observados. Comparación
del APK contra31bd6bdd: solo JNI, UI/marker propios si necesarios, sin cambios
a los componentes gráficos. Un control120s+cleanup20 con reboot previo,
colección privada y sin Login; no repetir sin diagnóstico.

## Implementation / Action Items

- [x] 1. Aprobar la retención específica de los cuatro fragmentos runtime.
- [x] 1b. Aprobar la ampliación propuesta a dos ventanas fijas de16KiB cada una,
  máximo dos snapshots/64KiB total, sin otras regiones ni seguimiento automático.
- [x] 2. Congelar formato/opt-in/límites e implementar lector/exportador no genérico.
- [ ] 3. Tests dirigidos, revisión, build/auditoría/retención de la variante diagnóstica.
- [ ] 4. Capturar las dos ventanas ampliadas bajo identidad y permisos actuales; preservar límites.
- [ ] 5. Analizar exclusivamente esa representación en Ghidra/LLVM desde el PC.
- [x] 6. Documentar función demostrada o siguiente borde; no afirmar parche aplicado.

## Open Issues

### C5/C6: separar metadata de carga y payload ejecutable

- C4real `9b88a31dfb` con APK7f11 corrigió el fallo transitorio/carrera pero siguió
  sin histórico. Se añadió clasificación escalar, sin bytes nuevos de payload.
- C5real `1ff95048cc` con APKce111 mostró inicialmente header válido y ambas
  ventanasRX/histórico disponible; después cambió metadata de maps y se invalidó.
  Un test reprodujo un rechazo excesivo por comparar el VMA completo, no la
  intersección con la ventana: cobertura canónica recortada/offsets/coalescing
  corregidos,21tests dirigidos/65filtered y revisión aceptados, sin subir caps.
- C6real `667fef660f` con APKc8c01 mostró explícitamente
  `header-matched-payload-non-rx`: ambas ventanasRW al reconocer el header;
  luego `preferred-header-read-failed` y ningún histórico. No snapshots,8
  intentos tardíos bloqueados. Esto identifica una condición de bootstrap del
  instrumento, no un fallo TLS, ausencia de código o evidencia de desempaquetado.
- Clarificación de implementación dentro del mismo alcance: la fase temprana
  retiene **solo metadata** de cabecera/identidad/cobertura de las dos ventanas;
  no necesita que las ventanas ya sean ejecutables ni lee sus bytes. Se permite
  observar en maps su transición de privada legibleRW a privadaRX, conservando
  base estructural validada, backing/inode/device/offset/rangos equivalentes y
  mismo proceso/lease/fuente. No aceptar backing distinto ni atribuir módulo
  actual a una mera base histórica. Cambios de identidad/ambigüedad cancelan.
- El **payload** sigue siendo exclusivamente las dos ventanas actuales privadas
  `r-xp`, idénticas en metadata antes/después de leer, máximo32KiB por snapshot y
  dos snapshots/64KiB total. Ningún byte de payload se retiene duranteRW, ningún
  permiso de memoria se cambia y nunca se autoriza escribir/parchear. Al usar
  metadata temprana sin header actual, conservarINCOMPLETE/moduleProof=false.
- Revisar independientemente esta separación metadata/payload y sus pruebas de
  transición, rechazo deRW actual y cambio de backing antes de otra candidata.

### C4: retoma aprobada

- Usuario pide continuar autónomamente y acepta considerar cualquier solución,
  incluso desactivar validación TLS. Se registra como método posible, no como
  causa demostrada ni permiso para tocar Btrfs o desactivar TLS globalmente.
  Evaluación en `docs/arm64-forever-tls-diagnostics.md`: dos handshakes estrictos
  del host al endpoint fijo aceptan TLS1.3 yTLS1.2 sin credenciales,432/311ms.
  No prueban Wine ni el CertStore propietario; ningún bypass implementado.
- Corrección temprana revisada: ausencia transitoria no envenena estado; errores
  reales invalidan histórico. Revisión detectó una carrera entre nativePoll:
  guard de operación ahora serializa fase temprana/commit completa sin bloquear
  nativeEnd.18tests dirigidos/65filtered y revisión final aceptados.
- Candidata nueva7f11c6c827c2b8b4/SHA
  `7f11c6c827c2b8b454b82b71c101d16c5d26c4c946fd0d56eab942275f42eb3d`,
  JNIc7b904768a8cdff828cf65996944ad927d84d521953fe827d64d4b5fda0519f9;
  build/audit nuevas sin retagging y soloJNI+marker diferentes frente a2db.
  Fuente corregida por fin empaquetada; siguiente un control120s sin Login
  después de verificar dispositivo propio disponible y reboot fresco.

### C2/C3: controles físicos y bloqueo vigente

- La fase permanece **in-progress/bloqueada**, no done: se instalaron candidatas
  auditadas preservando datos y se hicieron controles sin Login, pero no se
  retuvo ningún snapshot completo. No hay fragmentos para Ghidra/LLVM, verificador
  identificado, parche aplicado ni autenticación demostrada.
- Historial en `docs/arm64-forever-status.md` C2/C3. Controles bajo
  `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`:
  `58705645ed` UI fuera del viewport/pre-WoW;
  `d3799ce65e` guarda de ruta falló antes de poll, aunque un fallo Kotlin permitió
  lanzar WoW sin captura; `f1e1c2f3f8` prepare-only identificó componente3
  `/data/user/0` como S_IFLNK40960/UID0 en la vista JNI;
  `8cc530c63c` prepare-only pasó con preflightENDED tras usar el ancla fija
  `/data/data` de la misma app sin relajar no-follow;
  `417f9d0ef0` captura bloqueada por límite1024,0 éxitos;
  `074791bf59` fallback sin histórico retenido,8 intentos/0 éxitos;
  `1a634b7038` observabilidad explícita: histórico ausente,11 intentos/0 éxitos.
  Los reensayos respondieron a fallos concretos distintos del instrumento; no
  reinterpretar OBSERVED del harness como captura válida ni login correcto.
- Correcciones implementadas: Tab limitado revela la casilla fuera de viewport,
  Space solo en control exacto/enfocado de la app; captura preflightBegin/End sin
  Poll en preparación; errorBegin bloquea lanzamiento; contexto de cabecera PE
  puede ser privado legible RO, mientras los dos payloads siguen exclusivamente
  privadosRX; dirección ImageBase solo semilla validada, nunca prueba de carga;
  fallback por límite exige histórico anterior válido y cobertura actual estable,
  resultado INCOMPLETE/moduleProof=false/cryptographicModuleProof=false.
- Último APK instalado:
  `arm64-prototype/functional-poc-candidate-2db5dfcf434b8bcc.apk`,419082021B/SHA
  `2db5dfcf434b8bcc502ddc6cca10863c34c6448cf667ab58fd51781def101494`.
  JNI932296B/SHA `61ff4ce8da43c2986c677cc386db23e39c6bec2bea43cfb62c08506c15cece7f`;
  sidecar `5cb3ead37e0a0ebd8bfd7f2ec0046968550fc7e90878a71310af8d093d34cbbb`.
  NativeNDK24/API29/16KiB y APK offline/audit/firma compatibles; assets/lib
  cambian únicamente JNI+marker. La candidata intermedia5de9589f no se instaló:
  el padre detectó y corrigió su semilla indebidamente limitada a cabecera RX.
- Último control: `functional-poc-20261014T191708698869Z-1a634b7038`, log actual
  `final-1792005458147.jsonl`; PID14113/start7805/UID4=10330/PPID2235,
  nonce `413b0b0623d44d879eba9821e627d841`. Collector real del nonce fijo:
  `CollectorError: capture lease contains no complete fixed snapshots`.
  Sin bytes binarios retenidos. No ejecutar un análisis runtime sobre nada ni
  usar logs históricos de la misma recogida como nuevas capturas.
- **Defecto adicional corregido solo en fuente tras ese APK:** ausencia inicial
  de candidato se trataba como fallo permanente y detenía observación temprana.
  `EARLY_NOT_READY` ahora permite seguir sondeando metadata dentro del límite
  temprano20s; fallos reales de identidad/origen/ambigüedad invalidan el histórico.
  Tests finales dirigidos17passed/65filtered, no suite completa. Esa corrección
  **no está empaquetada ni desplegada**; no retaggear sidecars/APKs anteriores.
- Siguiente gate host-only antes de otra prueba física: revisar esta corrección
  y distinguir con tests por qué no se obtuvo cabecera/coberturaRX tempranas;
  después nueva provenance/build/audit si procede. No repetir el control con el
  APK instalado ni incrementar límites por conjetura. Steps3/4/5 no completados:
  step3 debe verificarse otra vez para la fuente final;4/5 requieren bytes válidos.
- No se modificó Btrfs, ejecutable del cliente, runtime ni drivers. No hubo
  credenciales/Login o escrituras de memoria del juego en esta ronda. Cleanup
  fue force-stop solicitado; no es prueba independiente de quiescencia.

- **C1, preparación2026-10-14:** variante implementada, revisada y compilada.
  Ventanas fijas32768B, máximo dos snapshots a partir de20s/60s monotónicos,
  opt-in separado de parche/Login. SHA del PE una vez por lease y origen estable;
  maps actuales RX antes/después, identidad completa del proceso, cabecera
  histórica siempreINCOMPLETE/no prueba criptográfica de módulo.
- La revisión encontró bloqueos reales: combinación Login/captura, asignación
  PE sin límites previos, candidatos truncados, hash hastaEOF, falsa provenance
  before/after, ELF p_align mal leído, nonce/índice/hash y commit/collector.
  Se corrigieron antes de instalar; no se usaron los builds iniciales en el móvil.
  Controles normales32B preservados. Los tests pasan por rondas independientes,
  no se suman: Rust73 antes de reparación; verificación final de cambios nativos
  dirigida10passed/65filtered; provenance/overlay10passed; collector18passed.
- Build nativo real NDK24.0.8215888/API29, cuatroPT_LOAD16KiB:
  `arm64-prototype/functional-capture-build.VQgaTP/libforever_patcher_jni.so`,
  920472B/SHA `4c64838796cbe99beeadb12f6e6ee3704ac585bff73aa430c0966a5f2c018711`.
  Sidecar independiente before/afterSHA
  `f584c568e461f7996c313ebff0a7c9496266309767f69649485db20c1ca07295`.
- APK candidata en `/home/adrian/wowforever-artifacts/arm64-prototype/`:
  `functional-poc-candidate-05b23e474a7b546e.apk`,420551919B/SHA
  `05b23e474a7b546e1dcee0d14e54bad4157dd88774bce4a0c86f38052c54a8fd`,
  code64, firma compatible, auditPASS. Gradle offlineJDK17/2CPU/1worker PASS;
  assets/lib frente a31bd6bdd: soloJNI+marker diferentes, UI compiladaDEX.
- Límite de provenance: el helper valida consistencia del toolchain suministrado,
  no es un servicio público con allowlist genérica. Para este control el padre
  comprobó la instalación concretaNDK24 aprobada y paths canónicos de Rust/LLVM
  en el sidecar; únicamente se acepta el artefacto/SHA indicado, no otros inputs.
- Móvil xagaDEVICE_SERIAL disponible/API34; preflight no mostró proceso del
  paquete ni wine/wineserver/WoW mediantepidof. Prueba física aún pendiente al
  registrar C1; no se afirma captura, autenticación ni resolución del bloqueo.

- Antes de ejecutar: resolver discrepancia código actual/variante retenida y
  provenance reproducible del JNI. No asumir que el hash de un APK identifica
  la fuente de sus cuatro ventanas históricas.
- Ampliación aprobada explícitamente; límite vigente32768B por snapshot,
  máximo65536B y dos snapshots por lease. No ampliar automáticamente.

- La cabecera desaparece en los polls tardíos. La base histórica y RX no prueban
  que la misma imagen siga ocupando el rango; por eso la captura no autoriza escribir.
- La coincidencia de conteos no prueba que las dos lecturas tengan bytes iguales;
  los dos snapshots permitirán comprobarlo sin ampliar la lectura.
- Dos de los destinos son proximidad léxica, no consumidores CertStore probados.
  Su código puede corresponder a otra funcionalidad, incluso si se desensambla.
- Sin una rutina/flujo de confianza demostrado no hay parche ni nuevo Login.
