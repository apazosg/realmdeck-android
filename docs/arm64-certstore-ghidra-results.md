# CertStore: comparación real con Ghidra

Investigación host-only del 2026-10-12, autorizada por «perfecto adelante!». Se
importaron los ejecutables existentes y se ejecutaron desensamblado, referencias,
recuperación de funciones y decompilación acotados. **No se ejecutó ningún cliente**,
ni se usó ADB, Login, lectura nueva de memoria del móvil o escritura en el juego.
El parche ARM64 sigue sin validar.

## Herramienta y entradas

Ghidra12.1.4, release oficial:
<https://github.com/NationalSecurityAgency/ghidra/releases/tag/Ghidra_12.1.4_build>.
Archivo `ghidra_12.1.4_PUBLIC_20260921.zip`, 569649598 bytes, SHA-256
`ddac49f903da9d5bac833e5cc79395098b9c33cfd3279be5f31bd00387d2d4db`;
digest observado coincidente con la API oficial. Java: OpenJDK21.0.12.1.

Instalación externa:
`/home/adrian/wowforever-artifacts/tooling/ghidra-12.1.4/install/ghidra_12.1.4_PUBLIC`.
Proyectos/informes: `/home/adrian/wowforever-artifacts/work/ghidra-flow-20261012/`.

| Entrada | Bytes | SHA-256 |
|---|---:|---|
| `references/official-arm64-beta/WowB-ARM64.exe` | 94286544 | `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59` |
| `/home/adrian/dev/wowforever/client/WowB.exe` | 102916816 | `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92` |

Entradas sin cambios. El ARM64 coincide con el del teléfono verificado previamente.
No se identifica una función equivalente entre arquitecturas solo por compartir RVA.

## Pasadas y límites

### Análisis inicial

ARM64: análisis automático terminado, 334 funciones/28024 instrucciones sobre
74182144 bytes de `.text`; ningún consumidor de los textos CertStore recuperado.
Con esta cobertura tan pequeña, **sin xrefs no significa sin consumidor**.

x64: análisis automático agotó480s en `Disassemble Entry Points`. El seguimiento
dirigido buscó un subconjunto de referencias RIP-relative y decompiló la entrada;
sin constructor/verificador recuperado. Indirecciones y otras referencias quedan
sin resolver. Informes: `arm64-final-report.txt`, `x64-targeted-report-v3.txt`.

### Recuperación por metadatos de excepciones

`PdataTargetFlow.java` interpretó exception metadata del PE. ARM64: 253154 registros
aceptados; x64: 304079 aceptados de304109, con30 unwind versión2 no soportados
(no declarados corruptos). Selección: 10 funciones y hasta8 decompilaciones por
imagen. Los registros son candidatos coherentes con límites, no prueba de CFG válido.

ARM64 `[0x3b8e1d8,0x3b8e274)` produjo36/156 bytes decodificados; x64
`[0x3b8e1b0,0x3b8e287)` produjo15/215. No se ha probado equivalencia de esos rangos.
Muchas muestras producen `halt_baddata()`: `decompileCompleted=true` significa que
la herramienta terminó, no que el pseudocódigo sea fiable. La llamada exploratoria
de desensamblado puede seguir flujo fuera del cuerpo; el seguimiento siguiente usa
restricción explícita y proyectos frescos.

Informes: `pdata-arm64-authoritative-report.txt`, `pdata-x64-authoritative-report.txt`.
«Authoritative» identifica una revisión del archivo, no autoridad para parchear.

### Bootstrap y callbacks TLS

**TLS aquí es Thread Local Storage del PE, no TLS de red.** Se catalogaron callbacks
y entrada y se usó `Disassembler.disassemble(Address, AddressSetView)` sobre cuerpos
seleccionados. Ejecuciones finales: proyecto fresco, `-noanalysis`, JVM4GiB/CPU2,
timeout180s y hasta15s/decompilación. Informes exclusivos0600 y menores de200KiB.

| Imagen | Entrada RVA | Tabla callbacks RVA | Destinos RVA |
|---|---|---|---|
| ARM64 | `0xaff48` | `0x46d09c8` | `0x3c88`, `0x4090590`, `0x377b60`, `0x377c10` |
| x64 | `0x1d8b10` | `0x48c02f8` | Tres slots a `0x1d8b40` |

ARM64: callback `0x3c88` y entrada decompilados. Hay recorridos de módulos/cabeceras
PE, constantes de hash, destinos indirectos y contador de tiempo/barreras.
Instrucciones concretas construyen destinos de escritura en `.data`:
`0x145117948`, base dinámica `0x145768c90`, `0x1457688d0`, `0x1457688d2`.
No están vinculados a claves o CertStore. El rastreador de expresiones es parcial,
no un análisis completo de alias/flujo; destinos indirectos restantes sin resolver.

**No hay escrituras verificadas en `.text` ni desempaquetador de código demostrado.**
Tampoco hay aristas directas a VirtualProtect/FlushInstructionCache/
NtProtectVirtualMemory/GetProcAddress/LoadLibrary en este alcance.

x64: entrada carga puntero de `0x145f649b8` y hace `CALL RAX`; destino desconocido.
Callback `0x1d8b40` sin registro `.pdata` coincidente: no se inventó un cuerpo.
Faltar ese registro no prueba que no sea una función válida.

Informes finales preservados:
- `bootstrap-arm64-final-v4-report.txt`: 154639 bytes.
- `bootstrap-x64-final-v4-report.txt`: 2790 bytes.

## Resultado y siguiente diagnóstico

Ahora sí hubo Ghidra real, referencias, recuperación de funciones y decompilación.
**Todavía no se reconstruyó la verificación criptográfica**: ningún constructor,
decodificador de claves, llamada verificadora o lugar de parche demostrado.
Las cadenas TACT/PEM siguen sin enlace probado con el almacén de12 claves.

Los fallos de decodificación y bootstrap con indirecciones justifican contrastar
ventanas específicas con su representación en ejecución; no prueban que todo
`.text` esté cifrado, que ARM64 use otras claves, ni que ClassicEra42597 describa
nuestro Beta70009. Próximo: métricas read-only de correspondencia de código/datos
de arranque en el objetivo propio, sin Login, antes de seguir código materializado.
No se propone un volcado general de heap. **Ese diagnóstico runtime no se ejecutó
en esta fase.** Ninguna dirección/pseudocódigo parcial autoriza escritura.

## Continuación: contraste real archivo/memoria, 2026-10-13

Se ejecutó después un diagnóstico fijo de diez ventanas en el WoW propio, sin
Login ni escritura. Código: `functional_image_compare.rs`, integrado en la sesión
read-only; conserva PID/startTicks/UID4/ascendencia/nonce/cmdline antes y después.
No permite RVAs recibidos del usuario ni vuelca bytes de código/datos.

La primera revisión encontró una imagen coherente pero falló al tratar dos RVAs
de `.data` como contenido del archivo. Son BSS: el cargador debe rellenar ceros
cuando VirtualSize supera SizeOfRawData. Se corrigió esa semántica, sin leer bytes
del siguiente segmento, y se verificaron diez tests de comparación. La ronda
anterior de 31 tests funcionales precedía a este arreglo; no se suman como una
única suite posterior.

Segundo APK diagnóstico retenido:
`retained-functional-poc64-8239e421.apk`, 420476267 bytes/SHA-256
`8239e4213fc2cc4b8e9f30796a3e6a76aab287b962b9d720386740b265ac1fbc`.
JNI 1127440 bytes/SHA-256
`38c3e5934fd2dee2c3415e2e91b310cd99b220079e54990e9a1cd766c91b3bcc`.
Build/audit/retención positivos; solo JNI y marker cambian frente a la primera
revisión `0d937c0e`, con 172 de174 assets/lib idénticos. Exports funcionales siguen
siendo Begin/Poll/End, sin Apply. El hash completo del archivo no se autentica
durante cada poll: el JSON declara `referenceSha256Checked=false` y
`cryptographicProof=false`; sí vincula FD estable y estructura PE.

Control: `functional-poc-20261013T051841697880Z-cbd071c70c`, boot
`904fe1e8-4b4e-4dce-9a2f-83906a0d94a6`, PID14727/start8234/UID4=10330.
Archivo de evidencia actual: `poc-logs/final-1791868749911.jsonl`; los otros logs
capturados son historia, no nuevas mediciones. La imagen `launch-060.png` conserva
el login limpio de WoW en Mali, sin credenciales ni Login enviado.

La primera comparación leyó las diez ventanas de una imagen estructuralmente
coincidente, pero **el resultado global es INCOMPLETE**: otra cabecera no pudo
leerse y no queda demostrada la unicidad global. No se acepta como Store validado.

| Ventana | Longitud | Bytes distintos |
|---|---:|---:|
| callback `0x3c88` | 2324 | 0 |
| entrada `0xaff48` | 3080 | 0 |
| muestra `0x3b8e1d8` | 156 | 0 |
| callback `0x4090590` | 1024 | 0 |
| callback `0x377b60` | 512 | 0 |
| callback `0x377c10` | 512 | 0 |
| tabla TLS `0x46d09c8` | 40 | 19 |
| slot `.data` `0x5117948` | 32 | 6 |
| tabla BSS `0x5768c90` | 256 | 255 |
| flags BSS `0x57688d0` | 32 | 1 |

Los seis rangos de código, 7608 bytes, coinciden con el archivo. Esto no prueba
equivalencia de todo `.text`; sí evita atribuir sus fallos de decompilación a un
desempaquetado físico demostrado. La tabla TLS tiene cuatro discrepancias DIR64
sin ASLR observado, no explicadas como simples relocaciones. No se extrajo ningún
destino hacia `.text` propio de los tres bloques de datos examinados. Los dos
polls siguientes no volvieron a encontrar cabecera dentro del límite de1024
candidatos: tampoco permiten afirmar que el módulo desapareció o fue modificado.

Relectura dirigida del informe Ghidra existente: el callback recorre bytes con
LDRB/EOR/STRB y una semilla CNTVCT_EL0; el pseudocódigo muestra un límite `0x1008`.
No demuestra una S-box/permutación de256 bytes ni enlace al CertStore. Los hashes
tipo FNV y las indirecciones de arranque no identifican una clave/verificador.
**Constructor, decodificador y punto de parche siguen sin localizar.**

Alternativa x64/FEX investigada históricamente y rechazada por el usuario para
priorizar ARM64 nativo. `.agents/planning/functional-x64-mali-ab-plan.md` no se
ejecuta; no se ha copiado ni ejecutado el cliente x64 en el móvil.

## Cuatro destinos indirectos: observación temprana y posterior

El seguimiento posterior en `docs/arm64-certstore-primitive-consumers.md` limita
la comparación a cuatro cuerpos `.pdata` (1756 bytes), no a las diez ventanas de
bootstrap anteriores. Primera lectura antes de D3D11: 1756 iguales; lectura
posterior del mismo PID/startTicks/UID4: 10 iguales y1746 distintos.
La posterior usa una cabecera observada anteriormente, conserva validación
completa de identidad después de leer y es siempre INCOMPLETE/no autorización
de escritura. No demuestra módulo actual, desempaquetado ni CertStore.

Control actual `functional-poc-20261013T172634578847Z-87524ba7e8`, APK64
`ac465cab8a784f5953d3fbecd0401c6230f29eafeff8860aa2660e231d6247f4`.
El cliente registró una aserción SmallMemAllocator antes de esa comparación
tardía. La imagen de login sigue limpia, pero no prueba estabilidad ni ejecución
sana de los candidatos. No se exportaron bytes para decompilar desde memoria.
