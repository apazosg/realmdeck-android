# Parche ARM64: investigación dirigida en el PC, 2026-10-13

Continuación autorizada: «venga pues adelante. vamos a buscarlo e intentar
resolverlo». Investigación host-only de los archivos existentes. No se ejecutó
WoW/Wine, no hubo ADB, Login, lectura nueva de memoria del teléfono, escritura de
cliente ni cambios del APK. La prioridad sigue siendo ARM64 nativo; x64 es una
referencia de análisis, no una alternativa de ejecución.

**Resultado: todavía no hay una función verificadora ni un punto de parche
demostrado.** Esta ronda corrigió dos interpretaciones que podían desviar la
investigación y obtuvo identificadores concretos de símbolos de depuración.

## Entradas y herramientas

- ARM64: `WowB-ARM64.exe`, 94286544 bytes, SHA-256
  `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`.
- x64 de referencia: `WowB.exe`, 102916816 bytes, SHA-256
  `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92`.
- Ghidra oficial12.1.4/Java21; proyectos externos nuevos, `-noanalysis`, 4GiB,
  dos CPUs y límite de180s por ejecución. Sin repetir el autoanálisis global.
- Hashes antes/después estables. RVAs de arquitecturas distintas no implican
  equivalencia de funciones.

## 1. Las dos tablas estáticas no contienen doce claves crudas

Las coincidencias previas de IDs1..12/stride40 en RVAs80082408 y80525912 habían
sido examinadas como si el campo `+4..+35` fuese una clave y `+36` un flag.
La inspección de la entrada **completa**, con relocaciones DIR64, cambia esa
interpretación:

| Tabla | Slots con puntero relocado en las12 entradas |
|---|---|
| RVA80082408 | `+8`, `+16`, `+32` |
| RVA80525912 | `+8`, `+24`, `+32` |

Los targets comprobados pertenecen a `.rdata`. En las24 entradas, `+36` es la
parte alta del puntero de64 bits que ocupa `+32..+39`, **no un flag independiente**.
No se puede tratar estas entradas como el bloque de doce claves crudas del
contrato, ni interpretar su baja entropía como una codificación criptográfica.
Esto no identifica su función ni excluye que un objeto con punteros participe en
otro formato de confianza.

También se corrigió el clasificador para distinguir destinos virtuales BSS de
destinos respaldados por bytes del archivo. No aparecieron targets BSS en estas
dos tablas. El primer análisis omitía vistas alineadas `+8/+16/+32`: su conclusión
de «cero punteros» no debe reutilizarse como ausencia de indirecciones.

La referencia `.rdata` RVA`0x4c5feb8` apunta a `0x4c5f5e8` mediante DIR64; todavía
no se conoce su consumidor. Se siguieron como máximo64KiB de targets de filas;
no se obtuvo un descriptor textual ordinario que identifique las tablas.

Informes externos, preservados sin sobrescritura:

- `work/static-candidate-references-20261013T072300Z.json`: interpretación inicial,
  supersedida para punteros y flags.
- `work/static-candidate-references-v2-bss-20261013T074400Z.json`.
- `work/static-candidate-references-v3-whole-entry-20261013T082000Z.json`.

## 2. Error corregido en nuestro decodificador ADR/ADRP

`TrustReferenceFlow.java` componía incorrectamente los bits del inmediato ARM64.
La composición correcta es:

```text
imm21 = (IMMHI << 2) | IMMLO
ADR  = PC + sign_extend(imm21, 21)
ADRP = page(PC) + (sign_extend(imm21, 21) << 12)
```

Ejemplo observado: instrucción `0x9007ac94` en VA`0x140e7b558`. El destino correcto
es **`0x15040b000`**, igual al operando de Ghidra. El cálculo anterior producía
RVA`0x4bdf000`, una página que contiene `VerifyWithCertStore`, y fabricaba una
pista falsa. **No era un error de Ghidra ni una referencia verificadora demostrada.**

Se volvió a ejecutar una sola vez el prefiltrado dirigido con el cálculo
corregido: ocho términos de confianza, sus aliases de datos y las tablas
anteriores, dentro de253154 rangos `.pdata`. Resultado: cero rangos seleccionados,
sin consumidor exacto ni función que justifique decompilación. Es un negativo de
este método de referencias, no ausencia de verificación ni imposibilidad de
parche estático. Las llamadas indirectas/tablas indexadas siguen pendientes.

Evidencia externa:

- `work/trust-flow-20261013/arm64-report-final4.txt` y
  `focused-adpr-report-final.txt`: históricos anteriores al arreglo; la supuesta
  página de confianza y la discrepancia raw/Ghidra quedan retiradas.
- `work/trust-flow-20261013/arm64-directed-fixed-report.txt`: resultado corregido.
- `work/trust-flow-20261013/x64-report.txt`: sin consumidor dirigido identificado;
  no se infiere equivalencia ni cobertura completa.

Verificación del parent:
`uv run --offline pytest -q tests/test_arm64_trust_address.py` → **5 passed**.
Incluye compilación/ejecución del método Java real extraído del script, con
vectores ADR/ADRP positivos, negativos, bits bajos y el ejemplo de Ghidra; no
ejecuta código del cliente. Los scripts Ghidra y Python se ejecutaron correctamente.

## 3. Símbolos de depuración e imports

Las cabeceras contienen identificadores RSDS concretos:

| Cliente | PDB | GUID | Age |
|---|---|---|---|
| ARM64 | `WowB-arm64.pdb` | `4CE8202B-CD2B-4509-B118-9E9C58B16134` | 1 |
| x64 | `WowB.pdb` | `8640B6AC-FCCB-4E84-B96B-85D3C40A9E97` | 1 |

Consulta limitada de metadata pública: `symbols.blizzard.com` no resolvió DNS;
las dos peticiones HEAD a `msdl.microsoft.com` con basename/GUID/age devolvieron
404. No se descargó un PDB, no se enviaron credenciales ni se demuestra ausencia
de símbolos en cualquier otra fuente.

El análisis de imports **es incompleto**: el directorio ARM64 declara un tamaño
anómalo, el lector limitó su ventana y el presupuesto a16MiB. No encontró imports
criptográficos nominativos en lo observado; no permite decir que no existan APIs
de verificación o resolución dinámica. GuardFlags indica CF_INSTRUMENTED, sin
tabla GuardCF ni metadata CHPE observada que aporte funciones a este diagnóstico.

Informes:
`work/pe-metadata-trust-research-20261013T133000Z.json` y
`work/pdb-public-head-lookup-20261013T140000Z.json`.

## Estado y siguiente borde útil

No se ha modificado ningún ejecutable, clave, flag del cliente ni contrato de
escritura. Continúan sin identificar constructor, materialización de claves y
verificador nativo del build70009. No repetir Login sin un parche efectivo.

Seguir el mecanismo de referencias indirectas/resolución de funciones o obtener
un mapa de símbolos con esos identificadores sería discriminante. Otra búsqueda
general de claves o el uso de las tablas anteriores como destino de parche no lo
sería. Cualquier parche en disco deberá prepararse sobre una copia y comprobarse
contra un punto específico demostrado; **esta ronda no produce todavía tal copia**.

## Continuación: primitivas y destinos indirectos

La siguiente ronda PC encontró tablas completas estándar SHA y cinco ubicaciones
de constantes matemáticas Ed25519 por arquitectura; no son claves del servidor ni
prueba del verificador. El grafo de datos expuso slots DIR64 hacia cuatro cuerpos
ARM64 seleccionados, pero Ghidra no recuperó funciones interpretables desde sus
entradas. No se atribuye significado a opcodes no alcanzados ni se infiere texto
empaquetado por `halt_baddata`. Referencias raw/RVA y la clasificación de palabras
sin listing se corrigieron; reportes históricos preservados. Detalles:
`docs/arm64-certstore-primitive-consumers.md`. Sin teléfono/Login/escritura ni APK
nuevo; el punto de parche sigue pendiente.
