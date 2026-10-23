# Exploración del parche Forever en ARM64

> Estado coordinado: [ARM64 + Classic Forever](arm64-forever-status.md). Esta
> bitácora conserva el historial técnico. La inspección estática del alias
> `0x4cb63b8 -> 0x4a5ff20` está completada; su resultado no tipa el objeto ni
> demuestra consumidor, grupo 8 u objetivo directo de parche.

## Alcance confirmado el 2026-10-14

El objetivo final aclarado por el usuario es **hacer funcionar el cliente ARM64
con el servidor privado Classic Forever**, no parchear por sí mismo. No se ha
decidido el método: configuración compatible, parche en memoria, cambio en una
copia del ejecutable u otro mecanismo demostrado son vías a evaluar. Un cambio
del archivo, si resulta necesario, se hará sobre una copia preservando el original.
La pantalla de login no demuestra autenticación ni entrada al mundo.
Btrfs pertenece a un proyecto separado.
Aclaración posterior del usuario: se permite consultar en solo lectura su código
de parcheo, configuración Wine y otras referencias útiles; no modificar ningún
archivo, permiso, snapshot, servicio, contenedor ni dato de ese proyecto. No
ejecutar scripts, clientes, builds o entrypoints con efectos sobre él. La propuesta de
exportación CASC fue descartada sin ejecución y la vía dinámica x64 se suspendió.

En el último estado verificado el ejecutable ARM64 estaba sin modificar; esta
actualización solo revisa documentación, no revalida el binario. No hay un offset,
rutina verificadora o sustitución de clave demostrados que permitan preparar una
copia parcheada fiable. Las tablas estáticas de stride40 examinadas contienen
punteros, no doce claves crudas identificadas. El análisis de esas estructuras y
del flujo ARM64 no autoriza un parche por conjetura. El contrato existente está
validado para x86_64: portal/configuración y sustitución del grupo8 son referencia,
no prueba de que ARM64 requiera o admita exactamente el mismo método. Comparar
alternativas según compatibilidad demostrada, alcance mínimo y reversibilidad;
no asumir que basta cambiar el portal ni desactivar verificaciones a ciegas.
Los diagnósticos de memoria descritos más abajo son evidencia histórica, no el
objetivo final. La compatibilidad ARM64 con Forever todavía no está demostrada.

Estado 2026-10-12: el login gráfico en Mali está demostrado, pero **el parche de
memoria sigue sin aplicarse**. La exploración inicial fue estática; su continuación
implementó un diagnóstico JNI de solo lectura y lo ejecutó en el móvil, sin Login,
credenciales ni escrituras. Runtime, gráficos y ejecutable del juego no cambiaron.

## Lo que funciona en PC

El patcher de `wowserver/vendor/forever-patcher` y este repo comparten claves,
flags y contrato de build70009. El procedimiento validado en x64 busca entradas
contiguas de ID u32 little-endian, clave32, flag1 y padding3: 12×40 bytes. Valida
el bloque completo y reemplaza solamente la clave del grupo8; el grupo1 es ancla.

Referencias: `crates/forever-patcher/src/patch.rs:39-147`,
`forever/contract.json:20-62` y
`/home/adrian/dev/wowserver/vendor/forever-patcher/src/bin/forever-supervisor.rs`.
El contrato conserva `arch=x86_64`: la igualdad de build **no demuestra** que el
cliente ARM64 use la misma representación, disposición o clase de mapping.

El patcher de escritorio no exige una fase concreta de Login para buscar el
almacén. Su diagnóstico adicional, documentado en `wowserver/docs/READABLE-KEYS.md`,
examina seis clases privadas legibles sin habilitarlas para escritura:
`anon-rw`, `anon-r--`, `file-rw`, `file-r--`, `file-rx` y `anon-rx`.

## Límite del lector canónico y de las pruebas anteriores

`functional_session.rs::ProcFs::maps` selecciona únicamente `rw-p` con nombre
ausente o entre corchetes. No considera memoria privada de solo lectura,
mappings respaldados por archivos ni nombres especiales no bracketados.
La política del core `memory.rs::parse_maps` admite nombres no iniciados por `/`;
ambas selecciones tampoco son exactamente iguales.

Por tanto, los cero hallazgos de las pasadas completas de ~2GiB son una observación
sobre el conjunto seleccionado, no sobre toda la memoria del proceso. Tampoco
demuestran una disposición ARM64 distinta o falta de inicialización.

### Defecto reproducido del scanner común

Antes de la corrección, `patch.rs::scan_stores` registraba `seen.insert(block)` antes de validar el bloque.
Si una validación transitoria falla o ve una tabla todavía incompleta, la segunda
aparición en el solape del siguiente chunk no se revalida.

Se ejecutó una reproducción host sintética contra el core entonces vigente, sin editarlo:

| Caso | Almacenes hallados | Intentos de validación |
|---|---:|---:|
| Tabla estable | 1 | 1 |
| Primera lectura de validación falla; siguientes serían válidas | 0 | 1 |
| Primera validación ve ID incompleto; siguientes serían válidas | 0 | 1 |

Informe exclusivo fuera del repo:
`work/patch-scan-overlap-reproduction-20261012.json`.
Comando: `cargo run --offline --manifest-path /tmp/opencode/patch-scan-repro/Cargo.toml`
con `CARGO_TARGET_DIR` externo. No hubo memoria real ni escrituras.
**Este defecto no explica por sí solo los cero hallazgos del ancla en Android.**
El solape de 512 bytes y la alineación4 son coherentes con el contrato actual.

La continuación corrigió la deduplicación: solo las observaciones que validan el
bloque completo se agregan a `seen`. Un error de lectura o ID transitorio puede
revalidarse en la siguiente observación del solape. Cinco regresiones sintéticas
en `crates/forever-patcher/tests/scan_overlap.rs` y los ocho tests `patch_tests`
pasaron. Contrato, alineación y escritura del grupo8 no se ampliaron.

## Ejecutable correcto, fuente de claves todavía desconocida

La lectura SHA256 del archivo del teléfono confirmó que el PE analizado es el
mismo: 94.286.544 bytes,
`d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`.
Tamaño y mtime fueron estables antes/después de esa lectura; no se modificó el PE.

El análisis estático dirigido recuperó `NetClient::CertStore::PublicKeyEntry`,
`Cert store init failed`, `allowDevPublicKey` y el tipo
`blz::pair<int, NetClient::CertStore::PublicKeyEntry>`. Este último **no establece**
stride, offsets, contigüidad ni implementación concreta de contenedor.

Una revisión correcta de RVA/image-base y límites `.pdata` descartó el único ADRP
aparente como referencia entre funciones. No se obtuvo un constructor ni un
disparador verificable. También aparecieron `wowstatic_camelot_1_60_1` y
`WOWSTATIC_CAMELOT_%d_%d_%d`, sin enlace demostrado con las claves. No se inventa
un archivo o FDID a partir de esos nombres; los certificados PEM/TACT siguen sin
estar ligados al almacén12×40.

Informes nuevos bajo `/home/adrian/wowforever-artifacts/work/`:

- `static-arm64-certstore-init-layout-20261012T195103Z-1108727.json`
- `static-db2-publickey-selector-20261012T195855Z-1140967.json`

El PE se conservó byte-exacto. Los resultados negativos de metadatos públicos
WoWDBDefs no demuestran ausencia de recursos en este build.

## Diagnóstico comparativo implementado y ejecutado

El JNI rota seis polls diagnósticos y una pasada canónica por lease. Mantiene
PID/startTicks/UID4/ascendencia/nonce/ejecutable, FD de memoria read-only y los
límites de 2GiB/20s por poll. Cada poll diagnóstico examina una clase privada
legible; excluye shared y `/dev`. Busca las doce claves y la sustituta en cualquier
alineación con solape31, sin duplicar coincidencias en fronteras. Reporta solo
contadores y cobertura: no direcciones, paths, claves, heap ni cuentas.

`READ_DIAGNOSTIC` no es `VALIDATED`: sus contadores nunca alimentan `stores`,
`validStoreCount` ni la política canónica de escritura. Los resultados incompletos
conservan `INCOMPLETE`; un mapa truncado o una identidad cambiada no puede producir
una pasada completa. Dieciséis tests funcionales Rust y revisión read-only positivos.

APK retenido: `delivery/retained-functional-poc64-d4bae6cd.apk`, 420476267 bytes,
SHA `d4bae6cdf35f60e1392e4d712a2f7b36c31e1931f044c8e120799335b0dac4e5`.
JNI 1048168 bytes/SHA
`5f0562965d6235cfb5f94045b55e5d3ebe3d7c3cfdbbdfa1d9c4a68b0631eaf4`.
Exports funcionales siguen siendo begin/poll/end; no apply/write.
Build NDK24/Android29/16KiB y APK offline/caps positivos. Comparación contra
`a0d6e1ec`: 174 assets/lib, 172 intactos; solo JNI y marker PoC cambiaron.

### Prueba física sin Login

Evidencia bajo `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`:
`functional-poc-20261012T203602111851Z-39f3a01712`.
Objetivo único PID14423/startTicks8744/UID4=10330/PPID8561.
`result.json`: OBSERVED, loginRequested=false, loginSubmitted=false,
readOnlyMemory=true y bytesWritten=0. `screenshots/launch-060.png` conserva el
login limpio en Mali y campos vacíos. No se introdujeron credenciales.

Resumen de las pasadas tardías del log `poc-logs/final-1791837392283.jsonl`:

| Clase | Regiones | Bytes de memoria leídos | Cobertura de esa pasada | Claves conocidas/sustituta |
|---|---:|---:|---|---|
| file-r-- | 302 | 56136978 | Completa | 0/0 |
| file-rw | 132 | 1232927 | Completa | 0/0 |
| anon-r-- | 263 | 40240995 | Incompleta: un error | 0/0 en bytes leídos |
| file-rx | 153 | 117830147 | Completa | 0/0 |
| anon-rx | 59 | 25733149 | Completa | 0/0 |
| anon-rw | 598 | 2102583959 | Completa en una pasada; posteriores incompletas | 0/0 |

Los bytes incluyen lecturas de solape; las clases se observaron en instantes
distintos, no como snapshot atómico. Tanto anclas alineadas como no alineadas
fueron cero. Las pasadas canónicas de esta prueba quedaron incompletas y no
validaron ningún almacén. Cero claves en cinco clases completas reduce la hipótesis
de que solo faltaba examinar mappings file-backed/ejecutables, pero **no demuestra**
ausencia global, formato ARM64 distinto ni falta de inicialización.

Sigue pendiente identificar la fuente/representación/inicialización real del
CertStore, incluyendo el límite de lectura en anon-r--. No repetir Login ni escribir
por conjeturas; el siguiente candidato debe aportar estructura completa compatible
o evidencia de un contrato ARM64 nuevo, no solo un contador de claves.

## Continuación: estructura sin requisito de clave ancla

Autorizada con «adelante»: además de las claves conocidas, el diagnóstico busca
IDs little-endian 1..12 separados por 40 bytes, en cualquier alineación. Es una
**hipótesis explícita del formato x64**, no una validación del formato ARM64.
`structuralStoreProbe` distingue secuencias de IDs, flags/padding compatibles y
claves conocidas por grupo. Muestras numéricas limitadas a ocho y clasificación
limitada a 64 candidatos; nunca direcciones, claves, digests de campos ni heap.
Una tabla con claves desconocidas no alimenta `VALIDATED` ni permite escritura.

El solape de lectura es ahora 479 bytes para poder observar un bloque480 que cruza
el límite del chunk. Floors separados para patrones de32 y bloques de480 evitan
doble conteo; los errores de lectura conservan cobertura incompleta. Las regiones
cortas completamente leídas tienen cero candidatos posibles, no un falso error.
Veintidós tests funcionales Rust positivos, revisión read-only y cross-build APK
positivos. No se ejecutó la suite completa del repo.

APK `delivery/retained-functional-poc64-b54bc0e6.apk`, 420476267 bytes, SHA
`b54bc0e6667ed8b925c120c5dc10ac6b6ad4b159c3841af55ebde6543f814ba3`;
JNI 1057744 bytes/SHA
`5502be84dc5f2d7b8a6b2e70dc88024a0b81185bbf1121dcb1004a185f5228cf`.
Comparación contra d4bae6cd: 174 assets/lib, 172 intactos; solo JNI y marker PoC.
Exports begin/poll/end, sin apply. Runtime, gráficos, PE y contratos sin cambios.

### Resultado físico

Prueba `functional-poc-20261012T210439363752Z-bb4e380bee`, boot
`167dfd5a-49ac-4e70-8a8d-e1eb3675192e`. Objetivo PID14622/startTicks9322/
UID4=10330/PPID8983. `result.json`: OBSERVED, launchRequested=true,
loginRequested=false, loginSubmitted=false, readOnlyMemory=true, bytesWritten=0.
La captura `screenshots/launch-060.png` conserva WoW limpio en Mali con campos vacíos.
Log actual: `poc-logs/final-1791839109873.jsonl`, no los tres históricos también
recogidos por el runner.

Última pasada disponible por clase:

| Clase | Bytes leídos | Cobertura | Secuencias IDs1..12 | Flags coincidentes por muestra |
|---|---:|---|---:|---|
| file-r-- | 56215409 | Completa | 1 | 0/12 |
| file-rw | 1233375 | Completa | 0 | — |
| anon-r-- | 40268323 | Incompleta: un error | 2 | 1/12, 1/12 |
| file-rx | 118015650 | Completa | 3 | 11/12, 11/12, 1/12 |
| anon-rx | 25763165 | Completa | 0 | — |
| anon-rw | 2114555681 | Incompleta: errores y presupuesto | 0 en bytes leídos | — |

Todas las muestras tienen doce campos no nulos y distintos y padding compatible,
pero **cero claves conocidas y cero candidatos con todos los metadatos compatibles**.
No hubo truncación por el cap de candidatos. Los conteos son por pasada/clase,
no un número de tablas únicas en toda la memoria ni un snapshot simultáneo.
Las pasadas canónicas tampoco validaron un almacén.

### Contraste estático de las coincidencias

Informes exclusivos en `poc-logs/` de esta prueba:

- `static-certstore-structure-20261012T211212396977Z-1446473.json`
- `arm64-certstore-slice-xref-20261012T211624011843Z-1462155.json`
- `x64-certstore-structural-20261012T211832183940Z-1472104.json`

El PE ARM64 exacto contiene dos secuencias estáticas de12×40 en `.rdata`, RVAs
80082408 y 80525912. La lectura inicial bajo la hipótesis de clave32/flag atribuía
el valor1 a todos los flags; **la revisión host posterior comprobó que `+36` es
parte de un puntero DIR64 `+32..+39`, no un flag independiente**. No son tablas de
claves crudas identificadas. Véase `docs/arm64-certstore-pc-investigation.md`.
Los 384 bytes interpretados inicialmente como campos de claves contienen
respectivamente 215 y 204 ceros y tienen entropía agregada baja (2,64 y 3,01 bits
por byte). No se encontró una referencia de código fiable a `NetClient::CertStore`.
El primer informe también examinó 64 fuentes runtime locales acotadas a ~29MiB;
las coincidencias adicionales no aportan identificación del almacén.

El PE x64 local de referencia (102916816 bytes, SHA
`5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92`)
contiene una secuencia similar, pero ninguno de sus campos de32 bytes es idéntico
por grupo a los de las dos secuencias ARM64. Eso **no demuestra claves distintas**:
no hay prueba de que dichas secuencias sean CertStores o de que sus campos sean
claves públicas. La comparación estática no correlaciona bytes/direcciones con los
candidatos observados en vivo. La interpretación inicial de «datos ajenos» se
descarta como conclusión no probada; podría ser metadata ordinaria, representación
indirecta o datos codificados. No se modifica el contrato por esta semejanza.

Una misma clave pública de servidor no depende de ARM64/x64. Su representación,
almacenamiento y el conjunto de confianza de diferentes clientes/builds sí pueden
variar. Esta continuación identifica coincidencias investigables, no un lugar
seguro de parcheo. Sigue pendiente vincular una estructura al uso real de
`NetClient::CertStore` y validar qué son sus campos antes de aplicar grupo8.

## Referencias públicas y cambio de enfoque

La búsqueda solicitada por el usuario encontró
`jhinzuo2/wow-classic-arm64-customservers`: cliente publicado con cabecera AA64 y
código específico de instrucciones ARM64 para Classic Era42597. Su launcher
Forever70009, en cambio, sigue suponiendo el mismo almacén12×40 sin demostrarlo
en ARM64. No se descargaron ejecutables completos ni se ejecutaron parches.
Fuentes fijadas y límites: `docs/arm64-private-server-public-references.md`.

Hasta ahora no se usaron Ghidra/radare2/Rizin: las búsquedas/parsers y el
desensamblado NDK fueron acotados, sin reconstrucción completa de CFG/xrefs ni
comparación de verificación criptográfica x64/ARM64. La petición del usuario es
investigar ese flujo y su construcción/decodificación de claves, no continuar
solo con patrones conocidos. El nuevo resultado externo no valida un offset ni
habilita escritura en nuestro Beta; el análisis de flujo sigue pendiente.

## Continuación con Ghidra real

La autorización posterior «perfecto adelante!» se ejecutó con Ghidra12.1.4
headless sobre ambos PE existentes: autoanálisis, funciones derivadas de `.pdata`
y bootstrap/callbacks TLS acotados. Instrucciones del arranque ARM64 muestran
escrituras de `.data`; destinos indirectos y consumidores CertStore no resueltos.
No se demuestra desempaquetado global de `.text` ni un lugar válido de parche.
Resultados, fuentes y límites: `docs/arm64-certstore-ghidra-results.md`.
No se ejecutó cliente, Login o nuevo lector de memoria del teléfono en esta fase.

## Navegación de continuidad

- [Estado central](arm64-forever-status.md) para la decisión actual y la tabla de
  variantes.
- [Flujo del slot estático](ed25519-static-slot-dataflow.md) para los fragmentos
  grupo9/grupo10 y el alias ya caracterizado.
- [Resultados funcionales](functional-forever-profiles-results.md) para la PoC,
  los dos Login históricos y `bytesWritten=0`.

## Continuación centrada en el PC

La solicitud «vamos a buscarlo e intentar resolverlo» se ejecutó sobre los PE
existentes, sin reconstruir APK ni probar nuevamente el móvil. Se corrigieron
la interpretación de entradas completas y la composición ADR/ADRP del script:
la supuesta página de `VerifyWithCertStore` de la primera pasada dirigida era
un falso resultado del decodificador, no un fallo de Ghidra. Cinco tests verifican
el método Java real; el prefiltrado corregido no identifica un consumidor.
Identificadores PDB encontrados y consultas públicas acotadas tampoco aportaron
símbolos descargables. Resultados/límites en
`docs/arm64-certstore-pc-investigation.md`; no hay punto de parche demostrado.

## Resultado A1: alias ARM64 completado (2026-10-14)

### Autoridad y entrada

El reporte autoridad es
`/home/adrian/wowforever-artifacts/work/arm64-trust-alias-20261014T163813-d0df8d24bf9044a7bdaf1e9aa2ba8a1e/report.json`,
SHA-256 `53e453bca51dd6b7848319f95cb2d036cbae3e6643eb8dbbfb6eb08137791b91`.
La fuente AA64 build `1.60.1.70009` tiene 94.286.544 bytes y SHA
`d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`, idéntico
antes y después. No hubo escritura del PE, Btrfs ni acceso a credenciales.

### Método y cobertura

Se inspeccionaron aliases inicializados de `.rdata`/`.data` con dos nodos y una
arista reversa, sin truncación. La cobertura única fue 17.446.912 bytes, con cap
33.554.432; hubo dos pasadas y 34.893.824 bytes reales, ventanas de 4.096 y cap
2.097.152. El presupuesto `sha_full_bytes` fue 188.573.088 y `analysis_bytes`
35.539.980, para 224.113.068 bytes del lector. No hubo nuevo código ni Ghidra. Se reutilizó
`EdSlotFlow` v3 como negativo acotado, seeds/refs/functions `0/0/0`, SHA
`6e74fdd84dd06f18b24ad1c5ec198e7496a861f443b2c7c2eea7bb7a83b7eaa9`.

### Resultado e interpretación

El alias es DIR64 tipo10, desde RVA `0x4cb63b8` / raw `0x4cb55b8` hacia RVA
`0x4a5ff20` / raw `0x4a5f120`. El destino es file-backed, no BSS, y es el dato
posterior al slot (`slot + 32`), no los 32 bytes del slot. No apareció ningún
alias adicional en la cobertura.

El resultado se **descarta como objetivo directo demostrado de parche**. No tipa
el objeto, no demuestra consumidor ni relación con grupo 8. No permite concluir
que toda relación con autenticación sea falsa, que el dato esté sin uso o que el
host no pueda tener otro mecanismo.

El T2 `ce4796...` queda histórico y no autoritativo por ser anterior a las
correcciones de autenticación/pinning. El reporte previo de código de 2.156 bytes
se conserva aparte; el padre revalidó esos 2.156 y el artefacto tenía 8.345 bytes
adicionales. Las incidencias de instrumento (tests sintéticos sin PE, `uv` fuera
de PATH en una tentativa y constante pin ausente, corregida antes del final) se
conservan en la bitácora. La ronda final del padre fue
`/home/adrian/.local/bin/uv run --offline pytest -q tests/test_arm64_trust_alias.py`
→ **12 passed**; no se suman rondas ni se presenta como suite completa.

### Cierre y siguiente borde

El método estático del alias está agotado dentro del alcance: **no repetir**. La
captura limitada de fragmentos runtime es solo una propuesta en
[`arm64-runtime-code-fragments-plan.md`](../.agents/planning/arm64-runtime-code-fragments-plan.md),
no ejecutada ni autorizada por este plan y dependiente de la pertenencia actual
al módulo. A1 no resuelve autenticación.
