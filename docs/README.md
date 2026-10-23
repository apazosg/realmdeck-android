# Índice de documentación

Índice temático para retomar la investigación sin recorrer los más de cien
informes y logs individuales. No es un inventario exhaustivo: cada bitácora
conserva su evidencia, límites y contexto original.

## Punto de entrada

- [Launcher: estado de producto](launcher-app-status.md): resumen consolidado de
  todos los hitos; C127 instalada con Login/mundo confirmados y C128 lista/auditada,
  pendiente de instalar y probar físicamente.
- [Descargador: punto de reanudación](downloader-handoff.md): cliente70450 en USB,
  publicación completada, corrección CASC y precauciones para conservar la sesión.
- [Estado ARM64 + Classic Forever](arm64-forever-status.md): estado central,
  variantes que no deben mezclarse, decisiones y próximos bordes condicionados.
- [Roadmap](roadmap.md): fases, puertas y trabajo pendiente, con los pasos
  históricos etiquetados como tales.
- [README del proyecto](../README.md): propósito, estructura y comandos de
  provisión.

## Arquitectura y provisión del cliente

- [Arquitectura](architecture.md): baseline y decisiones de diseño.
- [Instalación Fase 1](fase-1-instalacion-cliente.md): bitácora de TACT/CASC,
  instalación reanudable y límites conocidos.
- [Bootstrap del cliente ARM64](arm64-client-bootstrap.md): catálogo host-only,
  objetos y límites de la instalación; no equivale a cliente jugable.

## Runtime ARM64, Wine y GPU

- [Matriz de runtimes Android](android-runtime-test-results.md): evidencia de
  candidatos y puertas de Fase 2.
- [Launcher ARM64 automatizado](automated-arm64-launcher.md): variante privada
  code33 y sus límites; no confundirla con la PoC funcional de Mali.
- [Ensayo USB xaga](usb-lab-xaga.md): Wine/Explorer y controles gráficos.
- [Resultados gráficos Mali](mali-graphics-probe-results.md) y
  [hallazgos de backend GPU](gpu-backend-findings.md).
- [Runtime ARM64 Windows](windows-arm64-runtime.md) y
  [candidatos runtime](android-runtime-candidates.md).
- [Provisión Winlator](winlator-provisioning.md), [emulador Android](android-emulator.md)
  y [diseño del launcher](launcher-design.md).

## Compatibilidad con Classic Forever

- [Registro de avance](arm64-forever-connection-progress.md): rechazo de identidad
  grupo8 aislado, bloque ARM64 validado, parche guardado y Login/mundo positivos
  en C127; conserva D3/D4 como historia, no como bloqueo vigente.

- [Observación de sockets del WoW real](arm64-wine-tcp-observation.md): D2,
  capacidad de attach comprobada, traza truncada y límites de atribución.

- [Diagnóstico directo del Wine ARM64](arm64-wine-auth-diagnosis.md): D1 acotado,
  etapas de conexión, Login autorizado y primer error anterior a autenticación.

- [TLS y confianza interna](arm64-forever-tls-diagnostics.md): handshakes estrictos
  sin credenciales, alternativas de bypass y límites de lo demostrado.

- [Estado de perfiles y PoC funcional](functional-forever-profiles-results.md):
  login visual, los dos envíos históricos y diagnósticos read-only.
- [Exploración de CertStore/parche ARM64](arm64-certstore-patch-exploration.md):
  comparación del contrato x86_64 con las observaciones ARM64.
- [Investigación estática PC](arm64-certstore-pc-investigation.md),
  [consumidores de primitivas](arm64-certstore-primitive-consumers.md) y
  [resultados Ghidra](arm64-certstore-ghidra-results.md).
- [Flujo del slot estático Ed25519](ed25519-static-slot-dataflow.md):
  fragmentos observados, alias y límites del análisis.
- [Investigación de `wow-patcher`](wow-patcher-arm64-investigation.md):
  dry-run estático, no prueba funcional ARM64.
- [Construcción x64 del CertStore](x64-certstore-initialization.md): referencia
  suspendida por el bloqueo CASC.
- [Patcher de Fase 3](fase-3-patcher.md): núcleo histórico x86_64 y validación
  posterior del aplicador Android contra el WoW ARM64 real.

## Launcher, pruebas y referencias

- [Fase 5 del launcher](fase-5-launcher.md): JNI, supervisión y validación sin
  root en el dominio de la app.
- [Evidencia pública de clientes ARM64](arm64-private-server-public-references.md)
  y [hallazgos de la comunidad](community-arm64-findings.md).
- Los informes de dispositivo, Wine, PanVK, EGL, supervisión y controles
  permanecen en este directorio; el [estado central](arm64-forever-status.md)
  enlaza solo los que cambian la decisión actual.

## Convención de lectura

Separar siempre **observación**, **interpretación** y **siguiente acción**. La
PoC ARM64 con login visible, la variante automatizada code33 bloqueada en
Explorer y el baseline x64 son experimentos distintos. La autenticación, la
entrada al mundo, el render y un parche aplicado son puertas independientes.
