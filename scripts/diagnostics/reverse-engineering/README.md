# CertStore: investigación host-only con Ghidra

Scripts de investigación, no patchers. No ejecutan clientes ni acceden a ADB.
Resultado: `docs/arm64-certstore-ghidra-results.md`.

- `CertstoreFlow.java`: strings/xrefs reconocidos y decompilación de consumidores;
  análisis inicial con cobertura limitada. Elegir siempre salida nueva: este
  exportador exploratorio puede sobrescribir un informe existente.
- `X64TargetedFlow.java`: subconjunto RIP-relative y entrada x64, no todas las
  referencias indirectas. También exige elegir una salida nueva.
- `PdataTargetFlow.java`: límites candidatos desde exception metadata, muestra y
  métricas. Pasada exploratoria; no CFG restringido completo ni funciones
  equivalentes identificadas entre arquitecturas.
- `BootstrapFlow.java`: catálogo PE Thread Local Storage, instrucciones/CFG dentro
  de rangos seleccionados y decompilación acotada. Indirecciones y alias parciales.
- `StaticCandidateReferences.py`: interpreta las dos entradas estáticas previamente
  seleccionadas, separando targets virtuales/BSS/raw y punteros DIR64 de flags.
  No es un scanner de claves ni identifica el uso de la tabla por su forma.
- `TrustReferenceFlow.java`: prefiltrado dirigido ADR/ADRP/literales y aliases de
  datos, seguido de desensamblado dentro de límites `.pdata` cuando hay evidencia.
  Su composición de inmediatos se corrigió en la ronda PC: conservar los informes
  iniciales, pero no reutilizar su falsa referencia a la página de confianza.
  Regresión: `uv run --offline pytest -q tests/test_arm64_trust_address.py`.
- `IndirectTrustData.py`: grafo acotado de datos y slots DIR64 hacia código;
  mantiene separados aliases inversos, rutas directas y proximidad lexical.
- `CryptoPrimitiveAnchors.py`: constantes estándar SHA/Ed25519 derivadas y
  referencias entre secciones en RVAs; un anclaje no identifica el verificador.
- `DescriptorCodeFlow.java`: cuatro destinos indirectos fijos, con slots y límites
  `.pdata` comprobados. La cobertura es la del flujo desde la entrada; palabras
  ausentes del listing no equivalen a pruebas independientes de opcodes inválidos.
- `StaticEdSlotConsumers.py`: referencias dirigidas al único slot32 seleccionado
  por `wow-patcher` en copias ARM64/x64 autenticadas. Formaciones de dirección y
  lecturas raw son hipótesis de localización, no función criptográfica demostrada;
  cobertura de metadata limitada. Tests: `tests/test_static_ed_slot_consumers.py`.
- `EdSlotFlow.java`: seguimiento Ghidra del slot y su vecindario en las dos
  arquitecturas, selección de aliases y confirmación de instrucciones/PCode.
  Informes autoritativos v3 después de corregir ModRM x64 y páginas de aliases
  ARM64; sin consumidor de código demostrado. Tests: `tests/test_ed_slot_flow.py`.
- `EdSlotContext.py`: contexto y comparación de las mitades públicas de 16 bytes;
  no supone que un prefijo sea una clave completa ni hace desensamblado.
  Tests: `tests/test_ed_slot_context.py`.

Investigación dirigida posterior: `docs/arm64-certstore-pc-investigation.md`.
Continuación con constantes/consumidores: `docs/arm64-certstore-primitive-consumers.md`.
Investigación del patcher Rust estático: `docs/wow-patcher-arm64-investigation.md`.
Recorrido del slot compartido: `docs/ed25519-static-slot-dataflow.md`.

`PdataTargetFlow` y `BootstrapFlow` exportan con `CREATE_NEW`, permisos privados y fsync. Mantener
proyectos/reportes/binarios fuera del repo; nunca sobrescribir evidencia previa.
`halt_baddata()`/warnings y exit0 no acreditan un lugar de parche.

## Reproducir bootstrap acotado

Ghidra oficial12.1.4/Java21. Verificar entradas y padre externo antes de crear un
proyecto e informe nuevos. Ejemplo equivalente a la pasada ejecutada, no una razón
para repetir checks ya exitosos:

```bash
JAVA_TOOL_OPTIONS='-Xmx4G -XX:ActiveProcessorCount=2' timeout 180s \
  /home/adrian/wowforever-artifacts/tooling/ghidra-12.1.4/install/ghidra_12.1.4_PUBLIC/support/analyzeHeadless \
  /home/adrian/wowforever-artifacts/work/ghidra-flow-20261012 <proyecto-nuevo> \
  -import /home/adrian/wowforever-artifacts/references/official-arm64-beta/WowB-ARM64.exe \
  -noanalysis -max-cpu 2 \
  -scriptPath /home/adrian/dev/wowforever/scripts/diagnostics/reverse-engineering \
  -postScript BootstrapFlow.java <informe-externo-nuevo>
```

En x64 usar su PE, no la entrada ARM64. Entrada/callbacks provienen del PE;
`-noanalysis` evita repetir autoanálisis completo. Revisar resultados y advertencias,
no solo exit0. No modificar clientes ni ejecutar Ghidra desde Wine.
