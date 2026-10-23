---
id: wow-patcher-arm64-investigation-2026-10-13
state: done
date: 2026-10-13
---

## Summary

Investigación cerrada; no se ha resuelto todavía la autenticación ARM64.

El usuario solicita investigar `wowemulation-dev/wow-patcher` en cinco fases:
analizarlo, compararlo con Forever, estudiar copias ARM64/x86, producir una copia
parcheada únicamente si se identifica inequívocamente la clave de 32 bytes y
evaluar alternativas si no es viable. Entregable: informe técnico con compatibilidad
real, componentes y ubicaciones, búsquedas, causa si puede determinarse,
recomendación y prototipo cuando exista evidencia. Una prueba estática/dry-run
no resuelve la conexión; cualquier resultado funcional requiere el cliente ARM64.

Evaluación inicial de fuentes públicas completada, sin clon ni prototipo:
revisión `acc364c0b2c93ada01308ae0b1bd24614fe98ba9`, licencia MIT OR Apache-2.0.
RSA y Ed25519 son mecanismos separados. El motor estático genérico no comprueba
Machine ARM64 y su Ed25519 usa el primer prefijo de8 bytes; el runtime retail
sí exige Windows x64. No hay soporte validado para nuestro Beta ARM6470009.

Operación aprobada por el usuario: «avanza por donde consideres que podemos
encontrar una solución viable. aunque no sea la más elegante debemos priorizar
conseguir que funcione el login». Priorizar análisis que pueda resolver la
confianza del cliente, sin imponer la tabla x64 a ARM64. El borrador de exportación
de código runtime no forma parte de esta tarea y no se ejecuta.

## Content

### Autoridad y ubicaciones

- Repositorio: `https://github.com/wowemulation-dev/wow-patcher`.
  Clon HTTPS fuera del workspace, revisión anterior fijada/detached; registrar
  HEAD remoto, commit, árbol, licencias y Cargo.lock. No trasplantar código sin
  conservar avisos de licencia. No commits/push/configuración Git ni limpieza.
- Trabajo, clon, copias, target Cargo, logs e informes grandes:
  `/home/adrian/wowforever-artifacts/work/wow-patcher-arm64-<id>/`.
  Crear ubicación nueva; no sobrescribir resultados anteriores.
- ARM64 original de referencia: `references/official-arm64-beta/WowB-ARM64.exe`,
  94286544 bytes/SHA
  `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`.
  La lectura independiente del mismo ejecutable del móvil había confirmado ese SHA.
- x64 original: `/home/adrian/dev/wowforever/client/WowB.exe`, 102916816 bytes/SHA
  `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92`.
  Solo referencia, no A/B de ejecución x64/FEX, rechazado por el usuario.
- Copias exclusivas con identidad/hash antes/después y verificación post-copia;
  no cambiar originales, cliente vivo/CASC, configs, APK gráfico o perfiles.
- Informe versionado en `docs/wow-patcher-arm64-investigation.md`, herramientas
  específicas pequeñas si necesarias en `scripts/diagnostics/reverse-engineering/`
  y tests sintéticos bajo `tests/`; artefactos/binarios nunca en el repo.

### Fase 1 — wow-patcher

Revisar README, docs, ejemplos, licencias, Cargo y tests del clon autenticado.
Localizar y citar funciones/commit/lineas: `binary::{find_pattern,patch}`,
`binary/section::{check_offset_section,validate_patch_offsets}`, `keys::KeyConfig`,
`patterns::{connect_to_modulus_pattern,crypto_ed_public_key_pattern}`,
`cmd/execute::{execute_patch,dry_run_preview}`, `platform::extract_version`,
`patcher::Patcher` y el cálculo RVA del runtime retail, sin ejecutar ese runtime.
Verificar formatos/arquitecturas, selección de versión por nombre frente a
VERSIONINFO, reemplazos, límites de sección y dry-run/salida/backup/overwrite.
Build/test con Cargo.lock en target externo; offline primero y sin instalación
global. Si faltan dependencias, registrar el bloqueo o la adquisición pública
fijada, no ocultar un check no ejecutado.

Hallazgos preliminares que deben confirmarse en el clon:

- Parse PE mediante goblin no equivale a soporte/validación ARM64.
- RSA256 bytes ConnectToModulus no equivale a clave Ed2551932 ni al grupo8.
- El prefijo Ed25519 del proyecto coincide con el inicio de la clave del grupo9
  de nuestro contrato, **no con el grupo8**; demostrar la comparación, no copiar
  ese selector como contrato Forever.
- `patch` acepta la primera coincidencia y escribe32 bytes aunque el patrón
  mida8; no valida la clave original completa ni la unicidad.
- La sección se verifica por offset inicial, no necesariamente todo el intervalo
  de32 bytes. La vía estática no proporciona por sí sola un traductor general de
  RVA para ARM64; la vía retail x64 contiene cálculos específicos que no trasladar.
- `fs::write` puede sobrescribir la salida, incluso el input si se elige mal.
  Dry-run con Ed25519 ausente puede retornar normalmente; código de salida0,
  portal cambiado o mensaje de éxito genérico no prueban la clave aplicada.

### Fase 2 — comparación con Forever

Fuente versionada `forever/contract.json`: doce claves públicas conocidas,
entrada40 bytes/idLE/clave32/flag/padding, ancla1, objetivo8, nuevo valor único;
`crates/forever-patcher/src/patch.rs::{scan_stores,validate_block,patch_stores}`
y las validaciones de identidad del JNI funcional. Explicar dedup ya corregida,
validación completa, idempotencia y diferencias de alcance de memoria/archivo.
No asumir la disposición12×40 en ARM64 ni que los datos/punteros candidatos
previos sean claves. Separar núcleo x64 validado de diagnóstico ARM64 sin writes.

### Fase 3 — copias y análisis estático

1. Verificar PE Machine/PE32+/VERSIONINFO70009, secciones y bounds de raw/virtual,
   recursos/imports/relocaciones y limitaciones conocidas de protección/decodificación.
2. Reutilizar los resultados previos de búsquedas individuales completas cuando
   estén ligados al mismo SHA: las doce claves y nuevo valor dieron0 en ARM64.
   Ejecutar una pasada reproducible nueva solo para los patrones/representaciones
   específicas de wow-patcher o una pregunta que los informes anteriores no cubran.
3. Comparar cada clave individual y prefijos/roles con x64, reportando todas las
   coincidencias, sección/offset/RVA, no solo la primera. No volcar material sensible.
4. Ejecutar exclusivamente dry-run/grupo Ed25519 y análisis en copias cuando el
   CLI se haya comprobado; no defaults TrinityCore, RSA, portal, URLs, cert-bundle
   o launch. Comprobar por hashes que ningún dry-run modifica input/output.
5. Si no hay clave inequívoca, investigar estáticamente consumidores de confianza
   dirigidos con la evidencia nueva del proyecto. No repetir barridos generales
   de llaves/CFG ya descartados sin una razón nueva ni inferir inexistencia global.

### Fase 4 — prototipo condicionado

Solo si existe **una clave objetivo inequívoca** con identidad semántica Forever
grupo8 demostrada, 32 bytes originales completos, offset único/bounds raw de PE
ARM64 y origen/version/hash fijados:

- Implementar un plan dry-run verificable y sustitución de datos de exactamente
  ese intervalo; ningún NOP/branch/integrity/cert-verifier/security routine patch.
- Generar archivo **nuevo** mediante creación exclusiva, no `fs::write` sobre el
  original. Rechazar input==output, output existente, cambios de fuente y
  candidato ambiguo/solo prefijo. Validar todo el intervalo en la misma sección.
- Comprobar tamaño, SHA antes/después y diff: el conjunto de bytes distintos debe
  ser un subconjunto de los32 esperados y la clave completa debe ser la nueva;
  todo lo demás idéntico, incluida máquina/versión/secciones.
- Tests sintéticos de cero/múltiples coincidencias, cruce sección/BSS/no raw,
  clave parcial, output existente y source mutation; revisión independiente.
- Solo entonces control ARM64 Wine/Mali con copia separada, original restaurable,
  logs y captura de arranque. No Login en esta tarea: parche que arranca no prueba
  autenticación; ésta sigue explícitamente pendiente de la verificación de parche.
  Sin clave demostrada: **no fabricar prototipo ni ejecutar una copia inválida**.

### Fase 5 — alternativas y conclusión

Evaluar localización posterior a inicialización, representaciones/almacenamiento
ARM64, bibliotecas/reutilización Rust y una instrumentación limitada que resuelva
un borde preciso. Mantener separados certificados TLS, RSA bundles y Ed25519
Forever. Recomendar la opción más simple que tenga evidencia, incluso si el
resultado honesto es no viabilidad estática con las claves/patrones actuales.
No exportar nuevos fragmentos runtime ni habilitar escritura genérica/ptrace/root,
ni alterar rutinas de seguridad como alternativa; esas decisiones no pertenecen
a la solicitud actual.

## Implementation / Action Items

- [x] 1. Aprobación recibida para ejecutar las cinco fases y priorizar una solución
  viable de login; no repetir credenciales antes de verificar la modificación.
- [x] 2. Clon detached `acc364c0b2c93ada01308ae0b1bd24614fe98ba9` externo;
  build locked, tests y funciones documentados en el informe externo.
- [x] 3. Copias ARM64/x64 autenticadas y originales intactos; PE32+/VERSIONINFO
  70009 verificados. El prefijo Ed25519 aparece una vez en cada copia, pero los
  32 bytes completos no coinciden con ninguna clave del contrato Forever.
- [x] 4. Análisis y dry-runs Ed25519 ARM64/x64 completados sobre copias intactas;
  build locked y 40 tests filtrados del clon positivos. Lector de un slot: 25
  tests y regresión FD posterior separada; control ARM64 sin Login confirma
  32 bytes iguales antes/después de D3D, con identidad de imagen incompleta.
- [x] 5. Puerta inequívoca negativa: no se generó copia parcheada ni se ejecutó
  un prototipo ficticio. Prefijo único no equivale a clave completa grupo8;
  consumidor/función criptográfica no demostrados. Arranque del lector no
  acredita arranque de cliente parcheado ni autenticación.
- [x] 6. Informe completo `docs/wow-patcher-arm64-investigation.md`: compatibilidad
  de parser/dry-run, ubicaciones reutilizables, búsquedas, causa aún no determinada,
  alternativas y ausencia explícita de prototipo funcional.

## Open Issues

- Resultado final de investigación: parser ARM64 y dry-run comprobados; no solución
  de autenticación ni parche. En el control `functional-poc-20261013T190628372779Z-53f5c4529b`,
  el único slot32 de `.rdata` mantiene los 32 bytes del archivo antes y después de
  D3D11; las observaciones tardías usan base anterior y permanecen INCOMPLETE.
  UID/PID-ticks/nonce/ancestry se conservan, pero no es identidad criptográfica
  ni relación con grupo8. Sin Login, bytesWritten0 y render Mali limpio.
- `logs/ed-slot-representation-check.json`: las doce claves Forever son puntos
  comprimidos válidos; el slot idéntico ARM/x64 falla normal e inversión total.
  Esto no excluye representación opaca. El análisis nuevo dirigido de consumidores
  tuvo cobertura limitada y no demostró ninguno; cuatro tests del analizador
  positivos. No se modificaron los originales ni las rutinas de seguridad.

- Ejecución PC en `work/wow-patcher-arm64-20261013T182540Z/report.txt`:
  ARM64 Ed8-prefix raw0x4a5f100/RVA0x4a5ff00, x64 raw0x48f5a70/RVA0x48f6870,
  ambos en .rdata con el intervalo completo de32 bytes dentro de sección.
  Dry-run Ed25519 retorna0 y anuncia sustitución, pero no escribe ni demuestra
  Forever: las doce claves completas y el nuevo valor aparecen0 veces.
- `slot-analysis-v2.json`: los dos slots de32 bytes son idénticos entre CPUs,
  no solapan relocaciones ni parecen punteros; solamente el prefijo coincide
  con grupo9. La interpretación como punto Ed25519 canónico no se valida y
  no acredita la función del slot. El reemplazo TrinityCore sí coincide con
  nuestro nuevo valor, pero ese hecho tampoco convierte grupo9 en grupo8.
- Refinamiento de Fase5 autorizado dentro del diagnóstico read-only existente:
  contrastar únicamente esos32 bytes del RVA público ARM64 contra archivo y
  las claves conocidas durante inicialización, sin Login, dump, writes ni
  nuevo input libre. Reutilizar lector/lease/PID-ticks/UID4/nonce/ancestry y
  source-FD; base recordada solo hipótesis INCOMPLETE, ninguna coincidencia
  habilita escritura/VALIDATED. No hace falta exportar los fragmentos de código
  del otro borrador. El mecanismo CA/JSON/NGIS del repositorio es separado y
  no tiene relación probada con CertStore Beta70009.

- README/documentación cubre Classic1.13/1.14/2.5/3.4/4.4 y runtime retail12.x x64;
  no valida Beta1.60.1.70009 ARM64. Soporte genérico goblin es un componente,
  no compatibilidad funcional.
- La búsqueda conocida actualARM64dio0 claves; un motor de sustitución no resuelve
  por sí solo dónde se materializan ni cuál es la entrada correcta.
- El cliente transforma algunos rangos ejecutables durante el arranque. No sabemos
  si ocurre lo mismo con datos de confianza ni si anularía un parche en disco.
  Los conteos no prueban que esos rangos sean el verificador.
- La licencia permite investigar/reutilizar con sus avisos, pero hay que auditar
  dependencias antes de una integración Android real; no empaquetar el CLI entero
  con sus estrategias runtime x64/security-code patches por comodidad.
