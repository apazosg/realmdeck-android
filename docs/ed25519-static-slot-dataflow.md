# Recorrido de los 32 bytes estáticos comunes a x64 y ARM64

Fecha: 2026-10-13. Investigación estática sobre copias autenticadas; sin ejecutar
clientes, usar ADB, hacer Login, modificar ejecutables ni desactivar verificaciones.

## Resultado ejecutivo

**Estado coordinado 2026-10-14:** consultar el [estado ARM64 + Forever](arm64-forever-status.md)
para la decisión vigente. La inspección cerrada del alias ARM64
`0x4cb63b8 -> 0x4a5ff20` está completada. No tipó el objeto ni demostró
consumidor, relación con grupo 8 u objetivo directo de parche; no se repite dentro
del alcance. Las propuestas históricas de este documento no se consideran
ejecutadas por anticipación.

**No se ha identificado un consumidor de código ni una clave del grupo 8.**
Sí se ha aclarado qué contiene el intervalo que `wow-patcher` selecciona a partir
de su prefijo de ocho bytes:

- Sus primeros **16 bytes** coinciden exactamente con la mitad inicial de la
  clave conocida del **grupo 9**.
- Sus siguientes **16 bytes** coinciden exactamente con la mitad final de la
  clave conocida del **grupo 10**.
- Esta concatenación es idéntica en los dos archivos. No equivale a una clave
  completa de ninguno de esos grupos ni del grupo 8.

Es evidencia de relación con fragmentos del material conocido, no evidencia de
que el intervalo sea un objeto Ed25519 de 32 bytes, una tabla o un objetivo de
sustitución. Puede existir una representación o construcción que todavía no
hemos reconstruido. No se identifica una técnica de compilador u ofuscación por
el parecido de los bytes.

## 1. Entradas, arquitectura y ubicación

Las copias están en
`/home/adrian/wowforever-artifacts/work/wow-patcher-arm64-20261013T182540Z/`.
Los hashes se comprobaron antes y después del análisis.

| Propiedad | ARM64 | x64, referencia únicamente |
|---|---|---|
| Archivo | `arm64/WowB-ARM64.exe` | `x64/WowB.exe` |
| Tamaño | 94.286.544 bytes | 102.916.816 bytes |
| SHA-256 | `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59` | `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92` |
| Machine / formato | `0xAA64`, PE32+ | `0x8664`, PE32+ |
| Versión | `1.60.1.70009` | `1.60.1.70009` |
| Sección | `.rdata` | `.rdata` |
| Offset raw | `0x4a5f100` | `0x48f5a70` |
| RVA | `0x4a5ff00` | `0x48f6870` |
| Intervalo examinado | 32 bytes | 32 bytes |

Todo el intervalo está respaldado por archivo; no es BSS ni parte del directorio
de recursos. El análisis Ghidra del directorio completo de relocaciones no
encontró relocaciones que solapen estos 32 bytes. Eso no establece su tipo.

## 2. Ghidra: referencias y funciones

`scripts/diagnostics/reverse-engineering/EdSlotFlow.java` se ejecutó realmente en
Ghidra oficial 12.1.4/Java 21, con proyectos separados y `-noanalysis`. Límites:
180 segundos por imagen, 4 GiB, dos CPUs, vecindario de 256 bytes a cada lado,
32 MiB de búsqueda de aliases, hasta ocho cuerpos candidatos de 64 KiB y 15
segundos por decompilación. No se decompilaron funciones sin evidencia de entrada.

El selector inspecciona formación de direcciones ARM64 y RIP-relative x64,
incluidos accesos SIMD. Un candidato debe confirmarse como instrucción/referencia
Ghidra o recorrido PCode; la coincidencia de una página no cuenta como consumidor.

### Resultados autoritativos v3

| Resultado | ARM64 | x64 |
|---|---:|---:|
| Registros `.pdata` aceptados como límites candidatos | 253.154 | 304.109 |
| Semillas de instrucción al slot, vecindario o aliases | 0 | 0 |
| Referencias de código confirmadas | 0 | 0 |
| Funciones seleccionadas / decompiladas | 0 / 0 | 0 / 0 |
| Aliases de datos en la cobertura acotada | 1 | 0 |

El alias ARM64 está en RVA **`0x4cb63b8`** y apunta a **`slot + 32`**, RVA
`0x4a5ff20`: los datos inmediatamente posteriores, **no los 32 bytes del slot**.
No se presenta como referencia directa a la clave. No se encontró un consumidor
demostrado de ese alias. Se alcanzó el límite de búsqueda de aliases: el resultado
negativo no cubre todas las referencias indirectas posibles.

Esta pasada importa los PEs y selecciona referencias antes de decompilar; no es
un autoanálisis completo del ejecutable. Cero funciones seleccionadas no demuestra
ausencia de autenticación, código empaquetado ni datos sin uso.

### Correcciones del analizador

El primer informe x64 se conserva, pero su resultado negativo no es autoritativo:
el índice del ModRM tenía un desplazamiento de un byte. Se corrigió el decoder
RIP-relative y sus longitudes, REX.W, prefijo `66` y SIMD. Los tests compilan y
ejecutan el decoder Java que usa el script, no solo un modelo Python.

También se corrigió la selección ARM64 de páginas ADRP para incluir los aliases
fuera de la página principal. La pasada corregida v3 sigue sin semillas válidas.
Los seis tests del módulo `tests/test_ed_slot_flow.py` pasaron. No se infiere
equivalencia de funciones por igualdad de RVA o de datos.

## 3. Contexto y límites del objeto

`EdSlotContext.py` examinó un vecindario de 512 bytes a cada lado en cada copia,
sin exportar los bytes del slot. Encontró un byte cero anterior y continuación
no nula posterior; no se acreditó un límite de objeto de exactamente 32 bytes ni
un descriptor con puntero/longitud. La alineación se calcula desde el offset de
archivo, no desde el índice centrado del buffer: ambos slots son múltiplos de 16;
solo el ARM64 es múltiplo de 32.

La comparación posicional con el grupo 9 aporta **16 coincidencias iniciales**,
no solo las ocho del patrón upstream. Para discriminar una posible construcción
por fragmentos se hizo una nueva búsqueda dirigida de las **24 mitades exactas
de 16 bytes** de las doce claves públicas del contrato, solo en `.rdata` y `.data`
raw, sin repetir los antiguos barridos de claves completas o sus codificaciones.

| Fragmento | ARM64: raw / RVA | x64: raw / RVA |
|---|---|---|
| Grupo 9, bytes 0–15 | `0x4a5f100` / `0x4a5ff00` | `0x48f5a70` / `0x48f6870` |
| Grupo 10, bytes 16–31 | `0x4a5f110` / `0x4a5ff10` | `0x48f5a80` / `0x48f6880` |

Las otras 22 mitades tuvieron cero coincidencias en esa cobertura: 17.446.912
bytes ARM64 y 22.288.384 bytes x64, sin alcanzar el cap de posiciones. La segunda
mitad del grupo 9 tampoco apareció en el vecindario. No hay evidencia de que esas
dos mitades observadas se ensamblen para reconstruir una clave completa.

Los siete tests actuales de `tests/test_ed_slot_context.py` pasaron, incluida la
regresión de alineación absoluta. Los informes previos no se sobrescribieron;
sus campos de alineación del buffer no deben usarse como alineación de archivo.

## 4. Contraste con el parcheador x64 funcional

El parcheador de PC no consume este offset estático. En
`/home/adrian/dev/wowserver/vendor/forever-patcher/src/patch.rs`:

- `validate_block`, líneas 279–314: comprueba el bloque runtime de 12 entradas,
  sus IDs, las claves completas, flags y padding.
- `patch_stores`, líneas 322–336: utiliza el intervalo del grupo 8,
  **offset 284 dentro del bloque** (`(8 - 1) * 40 + 4`), escribe solo sus 32 bytes
  tras validar el almacén. Esta función core no hace por sí sola read-back;
  no se atribuye aquí la verificación posterior de otras capas a ese fragmento.

Es evidencia funcional de un almacén de memoria x64, no de un lector de este slot
de `.rdata`. Los dos mecanismos no se hacen equivalentes porque `wow-patcher`
use el nombre Ed25519 o el mismo valor de reemplazo. El último dry-run upstream
demuestra selección/reemplazo de bytes; no demuestra el recorrido del cliente.

## 5. Respuestas a las preguntas de la investigación

| Pregunta | Evidencia / estado |
|---|---|
| ¿Sección y referencias? | `.rdata`, offsets/RVAs exactos; alias ARM al dato posterior, no referencia de código confirmada. |
| ¿Funciones Ed25519/autenticación? | Ninguna función usuaria identificada. La relación con fragmentos de claves es exacta; su consumo criptográfico no está probado. |
| ¿Funciones equivalentes x64/ARM64? | No localizadas. Igualdad de los 32 bytes no demuestra correspondencia de funciones. |
| ¿Construcción/selección del grupo 8? | No identificada; sus mitades tampoco aparecen en las secciones raw examinadas. |
| ¿Generación dinámica y momento? | No determinados. La presencia estática de los fragmentos no prueba cuándo se materializa el almacén runtime. No se confunde cargar una clave con generar una nueva clave criptográfica. |

No se desactivó ninguna verificación ni se produjo un cliente parcheado.

## 6. Experimento siguiente más prometedor

**Usar el almacén x64 realmente validado como punto de partida de la procedencia
de datos, no seguir suponiendo que el slot de ocho bytes es el origen de grupo 8.**

Un siguiente experimento controlado en el PC sería observar, en una sesión x64 de
referencia, el primer momento en que el parcheador obtiene el bloque completo y
registrar únicamente clase de mapping, IDs/metadatos y su relación con la imagen.
Una traza de lectura/creación que identifique la instrucción y pila responsable
de poblar la entrada permitiría localizar una rutina concreta y buscar su
equivalente ARM64 por comportamiento, no por RVA ni por firma de claves.

Esta propuesta **no se ejecutó aquí** y sale del análisis estático de este pedido:
requiere delimitar explícitamente una traza dinámica del cliente PC, sin Login,
credenciales, cambios de código, desactivar firmas ni volcar heap. No implica
ejecutar x64 en el móvil. Si no aparece un almacén válido en ese estado, se
registra ese límite sin inventar el momento de inicialización.

Antes de una traza dinámica, la única arista estática nueva concreta es el alias
ARM64 `0x4cb63b8 → 0x4a5ff20`; el salto adicional debe limitarse a demostrar el
tipo/consumidor del dato posterior, sin convertirlo por proximidad en grupo 8.
Otra búsqueda general de prefijos o aplicar el dry-run a ciegas no discriminaría
estos mecanismos.

## 7. Evidencia reproducible

- `scripts/diagnostics/reverse-engineering/EdSlotFlow.java`.
- `scripts/diagnostics/reverse-engineering/EdSlotContext.py`.
- `/home/adrian/wowforever-artifacts/work/ed-slot-flow-20261013/reports-v3/arm64.txt`
  (SHA `6e74fdd84dd06f18b24ad1c5ec198e7496a861f443b2c7c2eea7bb7a83b7eaa9`).
- En el mismo directorio, `x64.txt`
  (SHA `a7c9f4f61861dadaba4b4e1b8c16a22e09ab61c8b923d8d8ca4300ac6f3def58`).
- `work/wow-patcher-arm64-20261013T182540Z/logs/ed-slot-context-20261013T212000Z.json`
  bajo `/home/adrian/wowforever-artifacts/`, 66.535 bytes, modo `0600`, contiene las
  24 consultas de mitades, máscaras y límites; no bytes de claves ni memoria.
- Los informes v2 del flujo y el contexto anterior se conservan como históricos.

No se repitieron suites completas, builds de APK ni controles del móvil para esta
investigación host-only. Los tests de análisis no prueban autenticación.

## Navegación de continuidad

- [Estado central](arm64-forever-status.md) para preguntas, límites, el resultado
  A1 y el próximo borde condicionado.
- [Exploración del parche](arm64-certstore-patch-exploration.md) para el contrato
  x86_64 y los diagnósticos de memoria read-only.

## Estado posterior: A1 completado

El reporte autoridad de A1 es
`/home/adrian/wowforever-artifacts/work/arm64-trust-alias-20261014T163813-d0df8d24bf9044a7bdaf1e9aa2ba8a1e/report.json`,
SHA-256 `53e453bca51dd6b7848319f95cb2d036cbae3e6643eb8dbbfb6eb08137791b91`.
Confirmó el alias DIR64 tipo10 desde RVA `0x4cb63b8` / raw `0x4cb55b8` hacia
RVA `0x4a5ff20` / raw `0x4a5f120`, file-backed y no BSS: `slot + 32`, no los
32 bytes del slot. Hubo dos nodos, una arista reversa, sin truncación ni alias
adicional en la cobertura; no hubo nuevo código ni Ghidra.

Se reutilizó el reporte v3 negativo acotado (seeds/refs/functions `0/0/0`), SHA
`6e74fdd84dd06f18b24ad1c5ec198e7496a861f443b2c7c2eea7bb7a83b7eaa9`. El padre
autenticó ambos reportes y comparó el hash previo fijado. El alias se descarta
como objetivo directo demostrado de parche, sin descartar por ello toda relación
con autenticación, dato no usado u otros mecanismos del host.

La verificación final del padre fue
`/home/adrian/.local/bin/uv run --offline pytest -q tests/test_arm64_trust_alias.py`
→ **12 passed**. No se agregan rondas de tests ni se anuncia suite completa. La
captura limitada de fragmentos runtime queda como propuesta separada y pendiente
de alcance en [`arm64-runtime-code-fragments-plan.md`](../.agents/planning/arm64-runtime-code-fragments-plan.md).
