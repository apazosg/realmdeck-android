# APK57: guard loader corregido; fallo de política Java antes del launch

2026-10-09. Continuación autorizada mediante la delegación de autonomía del usuario.
**NO-GO app-domain**: el único control real terminó UNKNOWN antes de crear un
Process. La corrección del loader está implementada y probada en host, pero todavía
no se ha medido READY/prctl/adopción/cierre desde el APK. Sin Wine ni WoW.

## Candidato retenido e instalado

| Artefacto | Bytes | SHA256 |
|---|---:|---|
| APK57 / 0.2.33-automated | 370931339 | `8d0c0a86b1ca1571736e399d08f9ae7e36b9b7ec2f6b73075867c0a5fb747572` |
| Supervisor v2 | 30344 | `fd8727d3d4b3b76e00cbf17c74bcee75684d7945aa8736f644ecbbaf55f7385c` |
| Fixture | 12784 | `7f1b6d256939c9a7310475c3d3bc7dbdfeb22ad46e3de4270413db05a663e67f` |
| Manifest v2 | 3662 | `a246a11bc142fecbe156c40ac8913baba02a172288aff8b1f2787c21eb1134b6` |

Package/certificado/AA64/min29/target36 permanecen iguales. Copias y auditoría
retenidas en `/home/adrian/wowforever-artifacts/arm64-prototype/delivery/`:
`retained-0.2.33-code57.apk`, `automated-0.2.33-code57-validated-apk-audit.json`
y `retained-0.2.33-code57-receipt.json`. Receipt describe la retención previa al trial.
Comparación independiente contra56:164 miembros assets/lib,161 idénticos, solo
supervisor/manifest/provenance cambian; fixture idéntico, ninguna alta/baja.
Wine/WCP/PanVK/noGL/JNI/PE/client/catalog intactos. Entrega normal33 sin cambios.

## Guard y validaciones

- Autoridad de imagen: dirección propia en `/proc/self/maps`, RX no writable,
  path canónico, dev/inode y supervisor exacto; ya no `/proc/self/exe` del loader.
- Fixture canónico hermano inmutable: owner root/system, modo sin escritura
  group/other, app W_OK denegado; revalidación dev/inode antes de fork y exec.
- Tests nativos por rondas: direct/loader normal, maps writable, sustitución,
  nombres/argv/symlink/exterior y permisos Android. No sumar rondas solapadas.
- Python integración83PASS antes de cierre; corrección posterior del preflight
  verificada con6 casos dirigidos. No suite global declarada.
- JVM dirigido actual del candidato: **24PASS**, una ronda completa.
- Receta fresh/idempotent: **96 inputs equivalentes**. Build y auditoría PASS;
  offline/JDK17/CPU2/Xmx4096m/1worker, rerun únicamente assets. Warnings AGP/SDK
  existentes no ocultados. No actualización de toolchain.
- Revisión independiente del guard/persistencia: GO condicionado al packaging y
  evidencia física. No equivale a GO app-domain.

## Control físico y fallo

Preflight `native57-preflight-20261009T184357Z-1e229c4b17` verificó APK56 exacto,
instaló57 mediante `install -r`, preservó v1 UNKNOWN56 y reboot nuevo:
`66f3530d-a8e6-4a94-9b62-44393cdc75c0` distinto del boot56
`3370c8dc-85b4-4975-91a6-352d14271fbd`. Solo POCO serialDEVICE_SERIAL,
xaga/22041216G/Xiaomi/API34/UID10330. No input durante preflight.

El primer collector se rechazó antes de ADB/Activity por una condición host
incorrecta: prohibía que el boot **anterior** fuera el de56. Se corrigió para
exigir solo que el **nuevo** difiera de ambos. Se conservó el recibo original;
su hash de collector previo describe el preflight, no el código ejecutado después.
El trial guarda su source-runner/provenance real. Tests preflight6PASS.

Trial `native-supervisor-trial-30eac3ee6fd645cf91172db96ce86a72`:

- RUN `NS-b0a3fe48f925d7d656f597f740b4b413`, nonce
  `83ebf2726bda3c43258cd9523c465f59`.
- UI propia/consentimiento y un solo Enter acreditados. No retry de START.
- `run.json`: UNKNOWN, nativeExit:null, identities:[], echild:false,
  witnessProof:UNPROVEN, error `access failed: EACCES (Permission denied)`.
- Events solo RUN intent; stdout/stderr/witness streams vacíos. No READY ni
  identidad supervisor, ningún testigo ni prueba de adopción/cancelación.
- Collector conserva evidencia antes del force-stop de su paquete. Ese acto no
  demuestra contención ni convierte UNKNOWN en terminal reutilizable.

La causa localizada es Java `Os.access(..., W_OK)`: Android lanza ErrnoException
EACCES para la denegación que la política pretendía exigir. `artifactResolver`
se ejecuta antes de `startProcess` y falla ahí. La falta de PID/streams por sí
sola no prueba no-launch; la conclusión se basa también en el source/APK exacto.
No se ha medido el guard C corregido en esta ejecución: no llegó a ese código.

## Corrección fuente posterior, no nuevo APK

Policy ahora acepta exclusivamente EACCES como denegación de escritura; true
continúa rechazado, false conserva su semántica, cualquier otro errno propaga
fallo. Stat/owner/group/mode/canonical/hash/dev/inode no cambian.
Dos tests JVM dirigidos PASS: true/false/EACCES y ENOENT/EPERM/EIO.
La receta se reaplicó para compilar esos tests; **no se ensambló ni instaló un APK
nuevo**. Retained/installed57 sigue con el código previo. Sources/provenance del
checkout ahora son posteriores al APK auditado; no usar su APK viejo como build
de esta corrección ni sobrescribir la auditoría histórica.

## Preservación y siguiente gate

`post-trial-state.json` y `closure-seal.json` suplementales preservan original
seal/result, v1 y v2 estables. Recolección final solo lectura: no stop adicional,
input, reset, nuevo RUN ni namespace. Current v2 SHA
`73428e2c77bdeafc5687a5860821b67b7f1489966f1cae62d04a12318c57244d`;
v1 SHA `c9c899f579314d193a91a7bd935be5df16d6dcc1ece39d42863a9ab15064685c`.
Compiler AA64 c374b107…ed3add y backup original06dc23…b58e52 permanecen intactos.
PS solo propio no prueba quiescence; nuevo boot antes de cualquier futuro control.

Plan cerrado: `.agents/planning/done/native-supervisor-loader-plan.md`.
Contrato ejecutado: `.agents/planning/done/native-prelaunch-disposition-plan.md`.
Resultado posterior: `docs/native-prelaunch-disposition-results.md`.
No otra variante del store para esquivar UNKNOWN. La recuperación requiere
disposición explícita, exacta, auditable y consumida una vez, manteniendo el RUN
UNKNOWN original. Hasta aprobar ese contrato, v2 sigue bloqueado. Wine/PE/visual/
login son gates posteriores, no autorizados por esta operación.
