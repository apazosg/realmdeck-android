# wow-patcher y WoW Forever ARM64 — informe técnico

Fecha: 2026-10-13. Revisión investigada:
[`acc364c0b2c93ada01308ae0b1bd24614fe98ba9`](https://github.com/wowemulation-dev/wow-patcher/tree/acc364c0b2c93ada01308ae0b1bd24614fe98ba9).

## Resultado ejecutivo

**Compatibilidad de análisis estático: comprobada. Compatibilidad funcional con
Forever ARM64: no comprobada. No se ha generado ni aplicado un cliente parcheado.**

El proyecto procesa nuestro PE ARM64 y detecta `1.60.1.70009`. Su dry-run Ed25519
encuentra una coincidencia única de su prefijo de ocho bytes en `.rdata`, como
también ocurre en el ejecutable x64 de referencia. Esta es una pista nueva y
concreta, pero no identifica inequívocamente la clave objetivo de Forever:

- Ninguna de las doce claves públicas completas del contrato aparece literalmente
  en las dos copias; tampoco aparece el reemplazo completo.
- El prefijo del proyecto corresponde al inicio de la clave **del grupo 9**, no
  al grupo 8 que sustituye el parche Forever de memoria.
- Los 32 bytes seleccionados son idénticos entre las dos arquitecturas, pero no
  coinciden con ninguna clave completa del contrato. Difieren en 16 bytes de la
  clave del grupo 9.
- No hemos demostrado qué función consume ese slot ni su relación con la
  autenticación de Forever. El análisis dirigido de referencias tampoco la
  reconstruyó dentro de su cobertura acotada.

El criterio solicitado para la fase 4 —clave objetivo inequívoca— **no se cumple**.
El dry-run no se presenta como un prototipo funcional ni como autenticación.

## 1. Autoridad, copias y alcance

Se clonó por HTTPS fuera del repositorio y se fijó HEAD detached:

| Autoridad | Valor |
|---|---|
| Commit | `acc364c0b2c93ada01308ae0b1bd24614fe98ba9` |
| Árbol | `17448e28d628f697d34ee597e060e848978edc22` |
| Cargo.lock SHA-256 | `c61429cca666766a74e61dc43a7cf1a568b8b6f84c22a93f29fb27cdfec3cdfb` |
| Licencia | `MIT OR Apache-2.0`; conservar avisos al reutilizar código |
| Trabajo externo | `/home/adrian/wowforever-artifacts/work/wow-patcher-arm64-20261013T182540Z/` |

Las copias se crearon exclusivamente, en modo `0600`, con verificación de
identidad y SHA antes/después y fsync. Se verificó que originales y copias
conservaron sus contenidos. El clon continúa sin cambios.

| Cliente | Tamaño | SHA-256 |
|---|---:|---|
| ARM64 | 94.286.544 | `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59` |
| x64, solo referencia | 102.916.816 | `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92` |

Ambos contienen VERSIONINFO `1.60.1.70009`, PE32+ e ImageBase preferida
`0x140000000`; Machine es respectivamente `0xAA64` y `0x8664`. El ARM64 tiene seis
secciones y el x64 siete. La cola virtual de `.data` es zero-fill/BSS: no debe
traducirse a bytes posteriores del archivo.

Los originales, las instrucciones del cliente, el runtime gráfico y los datos
CASC no se modificaron. No se ejecutó el cliente x64. El único control nuevo del
móvil fue **ARM64 de lectura, sin Login**, descrito en la sección 5.

## 2. Compatibilidad real y componentes reutilizables

Las ubicaciones siguientes corresponden exclusivamente al commit fijado.

| Componente / ubicación | Uso posible | Límite que debe corregirse o respetarse |
|---|---|---|
| `src/binary/section.rs:15–48`, `check_offset_section` / `check_pe_offset` | Parse PE mediante Goblin y distinguir `.rdata`/`.data` | El camino estático no valida Machine; comprueba el offset inicial, no todo el intervalo de sustitución. |
| `src/platform/mod.rs:125–159`, `extract_version` / `extract_pe_version` | Leer VERSIONINFO del PE | No convertir versión detectada en prueba de compatibilidad ARM64/Forever. |
| `src/platform/mod.rs:35–78`, `ClientType` / `detect_client_type` | Clasificación y selección de mecanismos | Nuestro dry-run devuelve `Client type: Unknown`; esa clasificación no es la arquitectura PE. |
| `src/keys/mod.rs:21–27,90–118,151–189`, `KeyConfig` | Claves Ed25519 de 32 bytes y RSA de 256, separadas | Validación de tamaño/configuración, no prueba del significado de la clave original. |
| `src/patch_group.rs:10–35,47–65`, `PatchGroup::ED25519` | Limitar un análisis/reemplazo exclusivamente a Ed25519 | No usar los defaults que habilitan también otros grupos. |
| `src/patterns/mod.rs:42–57` | Separar patrones RSA y Ed25519 | El patrón Ed es solo un prefijo de ocho bytes; no es nuestro contrato de grupo 8. |
| `src/binary/mod.rs:88–124`, `patch`; `find_pattern` | Sustitución de bytes y wildcard `-1` | Primera coincidencia; no comprueba unicidad ni los 32 bytes originales completos. Permite reemplazo mayor que el patrón. |
| `src/cmd/execute.rs:219–267,590…`, dry-run | Simular cambios sin escribir | Un exit code 0, patrón opcional ausente o preview positivo no prueban el parche ni el login. |
| `src/patcher.rs`, `Patcher::patches` / `dry_run` | API Rust para organizar grupos | Preferible extraer componentes pequeños, no integrar todo el CLI y sus caminos runtime. |

### RVA y offset de archivo

La búsqueda estática devuelve un **offset dentro del vector del archivo**, no una
dirección virtual. Para bytes respaldados por archivo en una sección PE:

```text
offset = PointerToRawData + (RVA - VirtualAddress)
```

Se debe validar **todo** el intervalo contra `SizeOfRawData`, el tamaño del
archivo y la sección. El BSS virtual no ofrece bytes modificables en disco.
`src/cmd/retail/mod.rs:155–181` aplica una traducción de este tipo y conserva los
bytes originales; es una referencia reutilizable del cálculo, no una receta
ARM64: `prepare():121–124` exige expresamente PE x64 y sus recetas son de otros
builds retail.

El camino de salida estático (`src/cmd/execute.rs:516–542`) usa `fs::write`.
Una integración nuestra deberá crear una salida nueva exclusivamente, rechazar
input=output y verificar el diff; no heredar su posibilidad de sobrescritura.

## 3. Comparación con nuestro parcheador de memoria

| Aspecto | Forever actual | wow-patcher estático |
|---|---|---|
| Selección | Ancla grupo 1 y bloque completo | Prefijo de ocho bytes, primera coincidencia |
| Formato | 12 entradas de 40: ID LE, clave 32, flag y padding | No exige esa tabla; sustituye un intervalo de archivo |
| Objetivo | Grupo **8**, clave completa conocida | Slot llamado Ed25519 por el proyecto, prefijo coincidente con grupo **9** |
| Validación | IDs, doce claves, flags/padding; solo original o reemplazo admitidos | Sección del offset y límites del vector; sin identidad semántica de grupo |
| Modificación | 32 bytes del grupo 8 y verificación/idempotencia | 32 bytes desde la primera coincidencia del prefijo |
| ARM64 probado | Diagnóstico funcional de lectura; ningún almacén compatible validado | Parse PE, versión y dry-run probados; no arranque parcheado ni autenticación |

Fuentes propias: `forever/contract.json`,
`crates/forever-patcher/src/patch.rs::{scan_stores,validate_block,patch_stores}` y
`crates/forever-patcher-jni/src/functional_session.rs`. La deduplicación del
scanner ya ocurre después de validar el candidato. La arquitectura x86-64
validada del contrato **no demuestra** una tabla ARM64 idéntica.

RSA ConnectTo, RSA para firma de bundles, certificados TLS/CA y claves de
aplicación Ed25519 son roles distintos; no se intercambian por compartir el
nombre «clave».

## 4. Búsquedas individuales y dry-runs en copias

La auditoría contó **todas** las coincidencias, no solo la primera:

| Material | ARM64 | x64 |
|---|---|---|
| Cada una de las doce claves completas Forever | 0 por clave | 0 por clave |
| Nuevo valor Forever completo | 0 | 0 |
| Prefijo Ed25519 del proyecto | 1: raw `0x4a5f100`, RVA `0x4a5ff00`, `.rdata` | 1: raw `0x48f5a70`, RVA `0x48f6870`, `.rdata` |
| Tres prefijos RSA ConnectTo/Signature/Crypto | 0/0/0 | 0/0/0 |
| Envelope `{"Created":` y URL `bgs-key-fingerprint` del proyecto | 0/0 | 0/0 |
| Fragmento portal `.actual.battle.net` | 1 | 1 |

Los intervalos completos de los dos slots de 32 bytes caben en la zona raw de
`.rdata` y no solapan relocaciones DIR64. Los slots son byte-idénticos entre
arquitecturas, sin igualdad completa con grupo 8, grupo 9, otra clave conocida
o reemplazo.

Solo se ejecutó este grupo sobre cada copia:

```text
wow-patcher --dry-run --verbose --warcraft-exe <copia>
            --output-file <salida nueva inexistente> --patches ed25519
```

Ambos dry-runs detectaron la versión, validaron la sección y anunciaron que
aplicarían Ed25519. Retornaron 0; **ninguna salida se creó** y los hashes y
metadatos de las entradas siguieron iguales. RSA, portal, URLs, bundle y launch
no se ejecutaron.

### Representación y referencias del slot

Con el mismo predicado matemático de punto Edwards25519 comprimido canónico:
las doce claves del contrato y su reemplazo son válidos en el orden listado.
El slot original no pasa en ese orden ni invirtiendo sus 32 bytes. Esto debilita
su interpretación como clave canónica directa, pero **no excluye** datos opacos,
otra representación o un mecanismo distinto. No demuestra que el cliente sea
inválido ni que el prefijo sea accidental.

El proyecto manipula vectores raw de 32 bytes, sin conversión endian Ed25519
identificada. Sus pruebas sintéticas demuestran el reemplazo, no el consumo de
ese slot por nuestro cliente. Un análisis nuevo de referencias directas y una
indirección, limitado a 16 MiB de metadata y formas ADR/ADRP/LDR y RIP-relative,
no encontró un consumidor demostrado. Se alcanzó el límite de metadata:
**resultado negativo acotado**, no ausencia del verificador.

La continuación solicitada con Ghidra y contexto dirigido está en
[`ed25519-static-slot-dataflow.md`](ed25519-static-slot-dataflow.md). Aclara que
los 32 bytes se descomponen en la mitad inicial del grupo 9 y la mitad final del
grupo 10, idénticas en los dos archivos. No se demuestra una clave completa ni
un consumidor; no se presenta esa concatenación como grupo 8.

## 5. Comprobación ARM64 real, sin parche ni Login

Se reutilizó la PoC de lectura para comparar exclusivamente los 32 bytes de
RVA `0x4a5ff00`, sin exportar su contenido ni habilitar escritura.

- APK diagnóstico: `retained-functional-poc64-7b717e75.apk`, 418.610.992 bytes,
  SHA `7b717e752df9deb12dd553ece2a6ff3b91b8e5f5fc8625bac6410a87628e507b`.
- JNI: 1.142.696 bytes, SHA
  `2ea23d9768718eb5b20063651302a986a46df7c4f4aa07d0c03aa61d71359557`;
  funcionales Begin/Poll/End solamente, sin Apply.
- Control: `functional-poc-20261013T190628372779Z-53f5c4529b`.
- WoW real PID 14744, start ticks 9937, UID4 `10330×4`; identidad completa
  revalidada después de las lecturas.

| Observación UTC | Base de comparación | Igualdad con archivo |
|---|---|---|
| 19:07:25.363, antes de crear D3D11 | Una cabecera PE estructural observada; unicidad global incompleta | 32/32; prefijo de 8 idéntico; claves conocidas 0/12; reemplazo no |
| 19:07:49.673, después de D3D11 | Hipótesis de la cabecera anterior; búsqueda actual limitada sin coincidencia | Mismas métricas 32/32 |
| 19:08:27.483, después de D3D11 | Hipótesis de la cabecera anterior | Mismas métricas 32/32 |

D3D11 se creó a las 19:07:34.510 UTC y el pantallazo de 60 segundos muestra
el login limpio de WoW ARM64 sobre Mali-G610. **Ninguna de las tres observaciones
constituye prueba criptográfica de la imagen actual ni de la función del slot**:
las tardías están etiquetadas como cached/INCOMPLETE y no se verificó SHA del
archivo dentro del lector nativo. La fuente se vinculó a FD y metadata estable.

El control informa `loginRequested=false`, `loginSubmitted=false`,
`readOnlyMemory=true`, `bytesWritten=0`. No se probó autenticación. El cleanup del
paquete tras capturar no equivale a cierre kernel demostrado.

## 6. Por qué falla el enfoque actual

La causa raíz completa **no está determinada**. Sí se han separado estos hechos:

1. El lector ya identifica el proceso ARM64 real y puede leer memoria sin root;
   no es simplemente incapacidad general de acceso.
2. Las búsquedas completas dentro de clases seleccionadas no localizaron el
   ancla/almacén x64. Cobertura parcial y snapshots no simultáneos impiden afirmar
   ausencia global.
3. Las claves completas tampoco están literalmente en el PE x64 de referencia.
   Por tanto, su ausencia estática ARM64 no demuestra una diferencia causada por
   la CPU: la materialización dinámica, datos transformados o fuente distinta
   siguen abiertos.
4. El nuevo prefijo estático no es una validación del almacén ni del grupo 8.
   El hecho de que TrinityCore use el mismo reemplazo que Forever no une ambos
   mecanismos de autenticación.

No se ha demostrado que la falta de CASC completo sea la causa, ni que el
almacén necesite Login para existir. No repetimos credenciales para probar esas
hipótesis.

## 7. Alternativa recomendada y prototipo

**Reutilizar los componentes PE/versionado/búsqueda de Rust, no portar el CLI
completo ni aplicar su reemplazo genérico como solución Forever.**

La pista más específica es ahora este slot único. El siguiente borde es probar
su consumidor y el formato que utiliza —incluidos accesos indirectos o
materialización— y relacionarlo con la decisión de confianza del cliente. Si se
demuestra una clave sustituible en datos, un parche mínimo podrá limitarse al
intervalo completo verificado y producir una copia exclusiva con diff exacto;
no necesita supervisor de producto ni modificar rutinas de seguridad.

Otras vías siguen condicionadas a evidencia: detectar el almacén después de su
inicialización, identificar una representación indirecta ARM64 o resolver la
fuente de certificados TACT. Esta última es un mecanismo diferente y no debe
confundirse con el bloque de claves Forever.

**Prototipo estático funcional: no viable todavía con la identidad conocida.**
No hay ejecutable modificado, arranque de copia parcheada ni login verificado.
La fase condicionada de sustitución se cerró sin ejecutarla, conforme a la
petición de no parchear a ciegas. El problema no se da por resuelto.

## 8. Evidencia y verificaciones

Directorio externo principal:
`/home/adrian/wowforever-artifacts/work/wow-patcher-arm64-20261013T182540Z/`.

- `report.txt`, `logs/copy-metadata.json`, `logs/final-integrity.json`.
- `logs/pe-pattern-audit.json`, `logs/dryrun-arm64.log`,
  `logs/dryrun-x64.log`, `logs/dryrun-stability.json`.
- `slot-analysis-v2.json` es la revisión autoritativa del predicado de curva;
  el reporte v1 se conserva como histórico, no como conclusión actual.
- `logs/ed-slot-representation-check.json`.
- `logs/static-ed-slot-consumers-20261013T191700Z.json`.
- Control físico bajo
  `/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`,
  `functional-poc-20261013T190628372779Z-53f5c4529b/`, log actual
  `poc-logs/final-1791918415883.jsonl` y `screenshots/launch-060.png`.

Build del clon locked exitoso; offline inicial bloqueado por dependencias no
cacheadas y adquisición pública registrada en Cargo.lock. Tests host filtrados
del clon: binary 25, section 3, keys 8, platform 4, todos positivos; no suite total.
Analizador dirigido nuevo: cuatro tests positivos. Lector de slot: 25 tests en
una ronda y un test de estabilidad FD posterior al ajuste final, separados.
Cross-build, APK/signature/ZIP y retención verificados; se conservaron los APKs
previos y el renderer. No se repitieron suites completas para documentación.
