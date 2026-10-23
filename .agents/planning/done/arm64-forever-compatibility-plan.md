---
id: arm64-forever-compatibility-2026-10-14
state: done
date: 2026-10-14
---

## Summary

**Fase host-only terminada, no compatibilidad resuelta.** Se organizó la
documentación y se ejecutó la prueba del alias con un negativo acotado. ARM64
todavía no autentica ni entra al mundo de Forever de forma demostrada. No se
eligió/aplicó un parche y no se tocaron Btrfs, APK, runtime o ejecutables.

Objetivo del usuario: hacer funcionar el cliente ARM64 nativo con Classic
Forever, incluyendo autenticación y entrada al mundo; no imponer parche en
memoria o archivo como requisito. Mantener la documentación organizada para
retomar sin repetir pruebas ni confundir hipótesis con hechos.

Primera revisión de esta ronda: solo lectura de documentación/código existente.
No se ejecutó WoW, Wine, Docker o ADB; no hubo Login, descarga, modificación de
binarios/APK, exportación Btrfs ni acceso a credenciales. Este plan no es una
prueba nueva de funcionamiento. Fase aprobada el 2026-10-14: «adelante. sigue de
forma autónoma hasta conseguir desbloquearlo. ve actualizando la documentación».
La autonomía permite continuar dentro de las fronteras host-only de este plan;
no autoriza por sí sola las ampliaciones sensibles enumeradas más abajo.

### Hallazgos contrastados de la revisión

| Hecho | Evidencia y límite |
|---|---|
| Existe una PoC funcional ARM64 con login gráfico sobre Mali | `docs/functional-forever-profiles-results.md:3-17`; no demuestra autenticación, mundo ni estabilidad prolongada. |
| Esa PoC dispone de perfil Classic Forever y configuración derivada | Mismo documento y `scripts/arm64-prototype/app/overlay/ForeverFunctionalPoc.kt`; no confundirla con la variante automatizada code33 bloqueada en Explorer. |
| Hubo dos envíos históricos de Login, no cero | `docs/functional-forever-profiles-results.md:90-173`, pruebas `f6496fc097` y `872fb78bc2`; un Enter por ejecución. Las pruebas posteriores sin Login no borran ese historial. |
| La segunda prueba mostró WOW51900317 | No identifica contraseña, fallo TLS, rechazo de clave, respuesta del servidor ni causa de desconexión. |
| No hubo parche aplicado en esos controles | `bytesWritten=0`; bridge funcional Begin/Poll/End, no Apply. `memoryPatchEnabled` no demuestra escritura. |
| El contrato del patcher está validado x86_64 | `forever/contract.json`; no prueba formato 12x40 ni necesidad exacta del grupo8 en ARM64. |
| Las tablas estáticas stride40 no son doce claves crudas identificadas | `docs/arm64-certstore-pc-investigation.md:24-49`; campos interpretados como flags eran partes de punteros DIR64. |
| El slot estático seleccionado por prefijo no es la clave del grupo8 | `docs/ed25519-static-slot-dataflow.md:8-23`; mezcla de mitades de grupos9/10. No sustituir esos32 bytes por semejanza. |
| Hay una arista estática ARM64 concreta pendiente de caracterización | Alias `0x4cb63b8 -> 0x4a5ff20`, dato posterior al slot, sin consumidor confirmado. Mismo documento:59-77,167-171. |
| La vía dinámica x64 no produjo procedencia del almacén | `docs/x64-certstore-initialization.md`; controles bloqueados CASC, vía suspendida. No reactivar reparación/exportación de Btrfs. |

No está demostrado que el bloqueo ARM64 sea exclusivamente criptográfico. Tampoco
están demostradas suficiencia de datos (~1,2GiB de bootstrap), compatibilidad de
protocolo o ausencia global del almacén. Los negativos están limitados por sus
clases/ventanas/budgets. Las constantes criptográficas por sí solas no identifican
autenticación. La frase histórica «requiere el parche» se tratará como hipótesis
heredada del método PC, no como conclusión obtenida de WOW51900317.

## Content

### 1. Documentación para retomar

- Crear `docs/arm64-forever-status.md` como punto de entrada de esta investigación:
  objetivo, fronteras, estado actual, variantes/APKs diferenciados, hipótesis,
  pruebas realizadas/descartadas y una única próxima prueba justificada.
- Crear `docs/README.md` como índice temático corto del proyecto: arquitectura,
  provisión, runtimes/GPU, compatibilidad Forever, launcher y pruebas/toolchain.
  Enlazar bitácoras existentes; no mover ni duplicar todos los informes.
- Enlazar los puntos de entrada desde `README.md` y `docs/roadmap.md`, corrigiendo
  únicamente las afirmaciones globales obsoletas: ARM64 nativo ahora tiene una
  PoC gráfica, pero no es una APK final ni hay gameplay Forever demostrado.
  Conservar la diferencia entre baseline x64, automatizada code33 y PoC funcional.
- Actualizar la conclusión de compatibilidad en
  `docs/functional-forever-profiles-results.md` y enlazar el estado actual desde
  las bitácoras ARM64 relevantes. No reescribir resultados originales ni eliminar
  correcciones/negativos. No editar specs, contrato, AGENTS o skills en esta ronda.
- Separar observación, interpretación y siguiente acción. Por prueba registrar:
  ID/fecha, pregunta, entrada/build/SHA, procedimiento y límites, resultado,
  inferencia permitida, hipótesis descartada o pendiente, evidencia externa,
  comandos/checks efectivamente ejecutados y motivo para no repetirla.
- No copiar credenciales, cuentas, heap, capturas sensibles ni ejecutables al repo.
  Artefactos técnicos externos privados, nuevos, sin sobrescribir anteriores.
- Actualizar estado y bitácora al concluir cada experimento, incluidos bloqueos y
  fallos de instrumento; no sumar tests de rondas diferentes como una suite nueva.

### 2. Próxima prueba propuesta: dato indirecto ARM64, host-only

Una prueba acotada del alias `0x4cb63b8 -> 0x4a5ff20` puede discriminar qué es el
dato posterior al slot estático sin repetir un barrido general de claves ni un
arranque x64 que falla CASC. No se le atribuye CertStore por proximidad.

- Fuente: copia ARM64 existente, PE AA64, versión1.60.1.70009, 94286544 bytes,
  SHA `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`.
  Verificar identidad/hash antes y después; original y copias previas sin writes.
- Partir de ese alias y del destino exacto, no de un supuesto objeto Ed25519 de32
  bytes. Clasificar raw/RVA/BSS/relocaciones, límites y referencias indirectas.
  Distinguir puntero confirmado, ventana vecina, referencia a página y consumidor.
- Inspección cerrada de datos: hasta tres niveles,128 nodos,2MiB de ventanas;
  búsquedas de aliases nuevas, si necesarias, máximo32MiB de cobertura registrada.
  Reutilizar informes autenticados cuando cubran la misma pregunta.
- Solo desensamblar/decompilar con una entrada/referencia de código confirmada.
  Ghidra existente, proyecto externo nuevo, `-noanalysis`,4GiB/2CPU/180s, hasta
  cuatro cuerpos de64KiB y15s por decompilación. No ejecutar el código del cliente.
- Instrumentar el presupuesto del lector: hashes completos se contabilizan
  aparte de búsquedas/ventanas/importación Ghidra; no presentar chunks pequeños
  como cap de I/O total. Documentar límites efectivos y cobertura no examinada.
- Si se modifica un analizador, tests sintéticos de raw/RVA, DIR64, aliases,
  referencias falsas, límites y clasificación; revisión propia del diff. Solo
  revisar independientemente si se introduce una frontera de seguridad.
- Éxito: tipo/consumidor verificable o un negativo nuevo con cobertura exacta.
  Si no hay una arista de código válida, parar; no repetir la búsqueda, parchear
  por conjetura, inventar función o preparar Login.

### 3. Fronteras y fases futuras

- Proyecto Btrfs/wowserver: únicamente lectura de código/configuración técnica
  útil. Ningún cambio de archivos, permisos, snapshots, datos, contenedores,
  servicios o producción; no ejecutar sus scripts, builds, imports con efectos,
  entrypoints, cliente o debugger. No sortear rutas protegidas cambiando permisos.
- Nada de ADB, Wine, Docker, descarga adicional, Login o escritura de memoria en
  esta fase; no modificar APK, runtime, drivers, contrato o ejecutables del juego.
- El plan de captura `arm64-runtime-code-fragments-plan.md` sigue borrador y NO
  se autoriza por este plan. Nuevas lecturas del móvil, retención de código runtime,
  credenciales, cambios del cliente/runtime o pruebas de autenticación requieren
  un alcance y aprobación separados antes de ejecutarse.
- Solo considerar configuración, memoria, archivo, integración con launcher u
  otra adaptación si hay evidencia de su efecto sobre la compatibilidad. No
  desactivar firmas o validadores a ciegas ni trasladar patrones de otros builds.
- Fin último: autenticar y entrar al mundo con Forever, después validar gameplay.
  Un alias, test sintético, proceso vivo o pantalla de Login no satisface ese fin.

## Implementation / Action Items

- [x] 1. Aprobar esta fase de documentación y análisis host-only; ningún método
  definitivo de adaptación queda elegido.
- [x] 2. Crear índice y estado central, reconciliar conclusiones históricas y
  enlazar fuentes sin perder evidencias ni modificar documentación ajena.
- [x] 3. Autenticar la fuente y comprobar qué parte de la prueba del alias ya
  está cubierta; cancelar repeticiones idénticas antes de ejecutar herramientas.
- [x] 4. Ejecutar solo el análisis estático discriminante restante y sus checks
  dirigidos; preservar inputs y registrar presupuestos/resultados/bloqueos.
- [x] 5. Actualizar estado/bitácora con prueba y conclusión, verificar enlaces y
  diff; proponer una sola continuación basada en resultados.

## Open Issues

- **Resultado A1:** DIR64 confirmado `0x4cb63b8 -> 0x4a5ff20`, destino file-backed
  posterior al slot. Dos nodos/una arista reversa y ningún alias adicional en la
  cobertura declarada, sin truncación. No se tipó el objeto ni demostró consumidor
  o grupo8. No repetir esta prueba ni usar el alias como destino de parche.
- Reporte final:
  `/home/adrian/wowforever-artifacts/work/arm64-trust-alias-20261014T163813-d0df8d24bf9044a7bdaf1e9aa2ba8a1e/report.json`,
  SHA `53e453bca51dd6b7848319f95cb2d036cbae3e6643eb8dbbfb6eb08137791b91`.
  Fuente SHA/tamaño estables antes/después; evidencia de código previa autenticada
  y revalidada por el padre contra su digest fijado, sin otro barrido de `.text`.
- Verificación final: `/home/adrian/.local/bin/uv run --offline pytest -q
  tests/test_arm64_trust_alias.py` → 12 passed; validación del digest previo y
  reporte PASS, enlaces Markdown locales/anchors y whitespace PASS. No suite
  completa, autenticación, lectura runtime o nuevo Ghidra.
- Documentación de continuidad: `docs/README.md` y `docs/arm64-forever-status.md`;
  bitácoras conservan fallos del instrumento, presupuesto y límites del negativo.
- El siguiente borde propuesto es el borrador
  `.agents/planning/arm64-runtime-code-fragments-plan.md`, no ejecutado. La captura
  de código runtime cambia los datos retenidos y sale del alcance aprobado de
  esta fase. No conceder autorización de escritura a partir de métricas o un
  módulo cuya pertenencia actual no esté demostrada.

- No hay causa aislada de WOW51900317 ni evidencia suficiente para dar por elegido
  un parche. No interpretar actividad TLS o ausencia de claves como diagnóstico.
- El alias puede ser metadata ajena a autenticación. Si la revisión previa ya
  responde la misma pregunta, registrar el límite sin repetir herramientas.
- Los cuatro cuerpos runtime cambiaron en métricas, pero su pertenencia actual al
  módulo no quedó demostrada; la lectura histórica no autoriza parche o captura.
- Falta verificar autenticación/mundo y suficiencia de datos ARM64; esta fase
  host-only reduce incertidumbre, no garantiza resolver la conexión completa.
- Alcance aprobado para esta fase. La revisión inicial de fuentes corresponde
  a evaluación del plan, no a un nuevo ensayo de autenticación. Las ampliaciones
  sensibles permanecen separadas y requieren su propia aprobación.
