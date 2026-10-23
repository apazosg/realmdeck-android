# ARM64: constantes criptográficas y consumidores indirectos, 2026-10-13

Continuación autorizada: «pues vamos allá. adelante». Trabajo exclusivamente en
el PC con los ejecutables existentes. No hubo ADB, ejecución de WoW/Wine, Login,
lectura nueva de memoria del móvil, escritura del cliente ni cambios del APK.
x64 sigue siendo referencia de análisis, no alternativa de ejecución.

**Resultado: hay nuevos anclajes criptográficos y punteros a código comprobados,
pero no un verificador de servidor ni un punto de parche demostrado.**

## Entradas

- ARM64: 94286544 bytes, SHA-256
  `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`.
- x64: 102916816 bytes, SHA-256
  `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92`.
- Identidades/hashes antes y después estables. No se modificaron los originales.
- Informes y proyectos nuevos bajo `/home/adrian/wowforever-artifacts/work/`;
  salidas exclusivas `0600`, con fsync. Los informes anteriores no se sobrescriben.

## 1. Anclajes independientes de las claves del servidor

`CryptoPrimitiveAnchors.py` deriva las constantes estándar SHA-256/SHA-512 y
valores matemáticos Ed25519 de RFC8032. No busca las doce claves del contrato.

| Anclaje | ARM64 | x64 |
|---|---|---|
| Tablas completas SHA-512, 80 palabras | `0x4cf6260`, `0x4e53940` | `0x4cbf880` |
| Tablas completas SHA-256, 64 palabras | `0x4cf6160`, `0x4e53d10` | `0x4925120` |
| Parámetro Ed25519 `d` | `0x4e53bc0` | `0x4cbfb40` |
| Coordenadas base `B.x`, `B.y` | `0x4e53c00`, `0x4e53c20` | `0x4cbfbe8`, `0x4cbfc08` |
| Primo de campo y orden de subgrupo | `0x4e53ca8`, `0x4e53cc8` | `0x5215d60`, `0x5215d80` |

Son cinco ubicaciones Ed25519 distintas por imagen, no seis: la representación
comprimida de la base coincide con `B.y`. Se informan también fragmentos de cuatro
palabras SHA por separado; no equivalen a tablas completas adicionales.

Estos datos no demuestran que una rutina Ed25519 se ejecute en la autenticación,
que las tablas se usen ni que exista enlace a `NetClient::CertStore`. SHA puede
servir a muchas otras funciones; no se confunde con TLS ni confianza de servidor.

La primera pasada de referencias mezclaba offsets de archivo y RVAs entre
secciones. Se corrigieron ADR/LDR ARM64 y RIP-relative x64, con regresiones cuya
sección de datos tiene una traducción raw/RVA distinta. Se añadió seguimiento
acotado de los cinco anclajes Ed25519 más la tabla SHA-512 adyacente de cada
arquitectura, sin repetir el barrido de claves. Resultado dirigido: cero referencias
de texto o datos reconocidas por esos métodos. No excluye indirecciones,
constantes inmediatas, representaciones por limbs ni otras implementaciones.
Los patrones raw x64 no se califican como instrucciones sin confirmación Ghidra.

Informes preservados:

- `crypto-primitive-anchors-20261013T151000Z.json`: positivos de constantes;
  las referencias dirigidas iniciales tenían el defecto descrito.
- `crypto-primitive-anchors-20261013T160000Z-targeted.json`: referencias corregidas,
  candidatos Ed25519 incluidos y límites explícitos; 84596 bytes.

Verificación ejecutada por el builder: **8 tests host positivos** en
`tests/test_crypto_primitive_anchors.py`, con constantes derivadas, PE, endian,
arquitectura y referencias entre secciones. No es una suite completa del repo.

## 2. Datos indirectos: separar ventanas, aristas y función del objeto

`IndirectTrustData.py` sigue únicamente las cadenas de datos de las referencias
ya conocidas, hasta tres niveles/256 nodos/2MiB. Distingue:

- Puntero absoluto respaldado por DIR64 frente a campo RVA relativo.
- Arista hacia delante frente a alias que apunta al objeto desde otro sitio.
- Slot exacto frente a ventana vecina de datos no tipada.
- RTTI/COL/vftable MSVC comprobado frente a descriptor de función desconocida.

La segunda pasada alcanzó los límites de 256 nodos y 24 destinos de código;
no es cobertura global. No obtuvo prueba RTTI de CertStore. Una ruta concreta es:

```text
root 0x4c5f5e8
  -- DIR64 en slot 0x4c5f710 --> .rdata 0x4c60b08
  -- slots 0x4c60b20 / 0x4c60b28 --> .text 0x37b1998 / 0x37b1af8
```

Los punteros y sus destinos son verificables; el nombre `root` solo identifica
la tabla explorada anteriormente, **no un almacén de certificados probado**.
Otros slots `0x498b558/0x498b568` apuntan a `0x223c820/0x223cc08` cerca del texto
`allowDevPublicKey`: su proximidad no constituye una referencia a ese texto ni
permite atribuirles autenticación.

Informe: `indirect-trust-data-codeptr-20261013T163000Z.json`. Verificación del
builder: **6 tests host positivos** en `tests/test_indirect_trust_data.py`;
incluye aristas, límites, destino ejecutable/no compartido y alias inversos.
El informe anterior `indirect-trust-data-20261013T154000Z.json` queda histórico:
su grafo de datos no exponía estas aristas hacia código.

## 3. Seguimiento real de cuatro destinos en Ghidra

`DescriptorCodeFlow.java` revalidó el SHA del PE, cinco slots DIR64 y cuatro
límites `.pdata`, y desensambló exclusivamente dentro de esos cuerpos. Ghidra
oficial12.1.4/Java21, `-noanalysis`, 4GiB/dos CPUs, 180s totales; como máximo cuatro
decompilaciones de 15s y 400 líneas cada una. No hubo análisis global nuevo.

| Entrada RVA | Cuerpo según metadata | Bytes alcanzados desde la entrada |
|---|---:|---:|
| `0x37b1998` | 60 | 0 |
| `0x37b1af8` | 216 | 4, una instrucción `bics` |
| `0x223c820` | 1000 | 0 |
| `0x223cc08` | 480 | 0 |

No se reconstruyó una función utilizable: los cuatro resultados de decompilación
solo contienen `halt_baddata`. Exit0/decompileCompleted no acredita código válido,
un consumidor criptográfico ni un objetivo de parche.

**Corrección de interpretación:** la primera salida llamaba `INVALID_STOP` a
cada palabra sin instrucción en el listing. El análisis seguía flujo desde la
entrada, no intentaba decodificar independientemente cada palabra posterior.
Por tanto no se puede decir que todo el cuerpo tenga opcodes inválidos; por
ejemplo `0x17346f3d` tiene forma de branch aunque no quedó alcanzado/decodificado.
La salida v2 usa `UNLISTED_WORD`/`independent_word_decode_attempted=false` y
explicita que no demuestra empaquetado global ni transformación del ejecutable.

- `descriptor-code-flow-20261013/descriptor-code-flow-report.txt`: histórico,
  15976 bytes; conservar pero aplicar la corrección anterior.
- `descriptor-code-flow-20261013/descriptor-code-flow-report-v2.txt`: 21456 bytes,
  script corregido compilado/ejecutado correctamente, mismo resultado acotado.

## 4. Contraste en ejecución de estos cuatro rangos

La primera revisión (`07fb7afe`) observó los 1756 bytes iguales al archivo, pero
la comparación ocurrió antes de LogOpen/D3D11. Los siguientes polls ya no
reconocieron una cabecera dentro del límite de búsqueda. No acredita que esos
rangos permaneciesen iguales después de inicializar el juego.

Se añadió una hipótesis privada por lease: conservar el candidato de una cabecera
estructuralmente coincidente y reutilizarlo solo para los mismos cuatro rangos si
el poll posterior encuentra cero candidatos. PID/startTicks/UID4/PPID/cmdline,
nonce/ancestry y FD/metadata/layout del archivo deben seguir vinculados; un
cambio de identidad/origen, cancelación o cabeceras ambiguas bloquea la lectura
reutilizada. Una validación completa posterior precede a la emisión de métricas;
si falla se descartan las observaciones y el cache. No se persisten bytes/base.

La lectura reutilizada es siempre `INCOMPLETE`, `comparisonBasis` =
`earlier-structural-header`, `headerStructureMatches=false`,
`cryptographicProof=false`, `referenceSha256Checked=false`,
`writeAuthorized=false`. No convierte una cabecera histórica en identidad actual
del módulo. Cuarenta tests Rust funcionales positivos en la ronda final y revisión
read-only posterior; no suite completa del repositorio.

APK retenido `retained-functional-poc64-ac465cab.apk`, 418611744 bytes/SHA
`ac465cab8a784f5953d3fbecd0401c6230f29eafeff8860aa2660e231d6247f4`;
JNI 1143736 bytes/SHA
`b19c2ed0e3a1afed9a115763b71d919ddeb52a2d8c0ff9eff301d6f3b09b3618`.
Cross-build, empaquetado/auditoría y retención positivos; frente a `07fb7afe`,
172 de174 assets/lib idénticos, solo JNI y marker cambiados. Begin/Poll/End
siguen siendo los únicos exports funcionales; no Apply.

Control `functional-poc-20261013T172634578847Z-87524ba7e8`, boot
`3629d2fa-1909-41ec-861d-1a2ba98ac299`, PID14333/start9312/UID4=10330/PPID10015.
El log actual es `poc-logs/final-1791912422809.jsonl`; los otros tres son historia.
No hubo Login ni credenciales; `bytesWritten=0`. La captura `launch-060.png`
mantiene el login visualmente limpio de WoW en Mali, no acredita estabilidad.

| RVA / longitud | Igual / distinto antes de D3D11 | Igual / distinto en lectura posterior |
|---|---:|---:|
| `0x37b1998` / 60 | 60 / 0 | 0 / 60 |
| `0x37b1af8` / 216 | 216 / 0 | 2 / 214 |
| `0x223c820` / 1000 | 1000 / 0 | 7 / 993 |
| `0x223cc08` / 480 | 480 / 0 | 1 / 479 |
| Total | 1756 / 0 | 10 / 1746 |

La primera comparación fue a17:27:30.566 UTC; LogOpen a17:27:38.495 y D3D11 S_OK
a17:27:39.901 (gx.log UTC+02). La posterior, a17:27:58.964, conserva identidad
completa del proceso después de leer (`identityValidatedAfterRead=true`), pero
usa una hipótesis de cabecera anterior: cero candidatos actuales y búsqueda
incompleta por su límite. Demuestra cambios en esos rangos del espacio de memoria
observado; no demuestra que sigan perteneciendo al mismo módulo ni código
desempaquetado, ejecutado, criptográfico o parcheable.

Además, el cliente registró `SmallMemAllocator.cpp:920`/`head != tailNext` en
`ThreadPool Low 1` a17:27:43.225, antes de la lectura tardía. El error actual
`2026-10-13_19.27.42_Error_216.txt` muestra frame1 y BGDL desactivado. No atribuir
la aserción a este diagnóstico ni considerar las observaciones posteriores como
fotogramas sanos; tampoco inferir que el ajuste BGDL había resuelto el allocator.

Contraste host independiente anterior:
`four-arm64-body-independent-decode-20261013T143000Z-v5.json`, SHA
`0ef7244ad478e93c7637df28d60cd0ee8fe40154791cfced29165ec5453c4ebd`.
LLVM decodificó linealmente 138/439 palabras con el perfil por defecto; esto no
equivale a instrucciones alcanzadas desde la entrada, ni resuelve su función.

## Características de los buffers: control del instrumento

El control posterior `functional-poc-20261013T180007649970Z-f7f30b574c`
usa el APK `retained-functional-poc64-31bd6bdd.apk`, 418613792 bytes/SHA
`31bd6bdda2c6f6b4265f5dd4f2896c9f272d4da0f87b5721426f06eee02615ea`.
JNI 1148472 bytes/SHA
`50c5faf22c08a849aac82bf178f15ed898f0b4bd596e7bc475ab5588ff8f99d5`;
20 tests dirigidos del comparador positivos, empaquetado/auditoría/retención
positivos. Solo JNI y marker cambian frente a ac465cab, 172 assets/lib intactos.
El primer build conservó un JNI obsoleto; se detectó por SHA/strings y no se
retuvo. Un target Cargo externo nuevo produjo y verificó los nuevos contadores.
No se hizo clean ni se alteró el toolchain.

Boot `cfa107d9-e38d-4711-81cf-1bc67d76f2c3`, PID14624/start9039/UID4=10330.
Log actual `poc-logs/final-1791914437162.jsonl`. Sin Login ni escritura.
La primera lectura a18:01:04.063 UTC es idéntica al archivo. LogOpen a18:01:13.096,
D3D11 S_OK a18:01:14.288. Las lecturas posteriores a18:01:27.737 y18:02:04.198
repiten estos mismos **conteos**, no una igualdad de bytes entre lecturas:

| RVA/longitud | Ceros archivo | Ceros runtime tardío | No ceros runtime | Valores distintos | Coincidencias archivo | Bytes RX / escribibles según maps |
|---|---:|---:|---:|---:|---:|---:|
| `0x37b1998`/60 | 1 | 6 | 54 | 43 | 0 | 60 / 0 |
| `0x37b1af8`/216 | 1 | 26 | 190 | 86 | 2 | 216 / 0 |
| `0x223c820`/1000 | 5 | 107 | 893 | 165 | 7 | 1000 / 0 |
| `0x223cc08`/480 | 1 | 43 | 437 | 112 | 1 | 480 / 0 |

El modelo de relleno constante cero no reproduce las coincidencias: daría8,
no10. Los datos observados tampoco son vacíos o un relleno constante: tienen
182 ceros y1574 no ceros, con diversidad por ventana. `ReadOnlyMemory::read`
exige completar la lectura y devuelve error en EOF/lectura parcial;
`read_covered` propaga ese error, no acepta el buffer parcialmente rellenado.
Los permisos son la observación de maps de cada poll, no una garantía atómica
de pertenencia al módulo o ejecución de esas instrucciones.

El resultado sigue `INCOMPLETE`, base de cabecera anterior y validación completa
del proceso posterior, cero cabeceras actuales y sin identidad criptográfica del
módulo. Este control no registró una aserción nueva en gx.log; los tres Errors
recogidos estaban presentes antes del control. No implica estabilidad general.
`launch-060.png` conserva el login limpio de WoW en Mali.

La evidencia favorece investigar una representación transformada frente a un
vaciado simple, pero no identifica el verificador. No se exportaron bytes de
código, heap ni claves. El siguiente cambio de alcance de datos está propuesto
en `.agents/planning/arm64-runtime-code-fragments-plan.md`, todavía borrador.

## Estado y siguiente borde

Constructor, materialización de claves y verificador del servidor siguen sin
identificar. No se preparó una copia parcheada ni se cambió un flag, clave o
instrucción del juego. No repetir Login sin parche aplicado/verificado.

Tenemos anclajes estándar, destinos indirectos precisos y métricas tempranas y
tardías diferentes. Aún no se ha exportado código en ejecución para Ghidra, ni
identificado el uso de confianza. El siguiente análisis debe distinguir la
representación de esos rangos antes del fallo y su pertenencia al módulo, y solo
entonces interpretar su código; no otro barrido general de claves. No se admite
un parche por la magnitud del cambio. El criterio de escritura permanece intacto.
