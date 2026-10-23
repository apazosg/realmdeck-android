---
id: probes-offline-validation-2026-09-28
state: done
date: 2026-09-28
---

## Summary

La propuesta aprobada prepara controles reproducibles de Fase 2 sin usar el
móvil: corregir la ABI x86-64 de `DynCodeProbe`, hacer que sus resultados sean
fiables y añadir una prueba de dos vistas RW/RX con modificación y
`FlushInstructionCache`. También se revisará `SlotProbe` como control de host
solo lectura mediante un proceso sintético, sin lanzar el cliente real ni
alterar su instalación.

## Content

- Ámbito exclusivo: `scripts/diagnostics/probes/`, tests nuevos relacionados y
  `docs/fase-2-fex-api-probe.md` si la evidencia host es útil.
- Los controles son de proceso propio salvo `SlotProbe`, que solo inspecciona
  un proceso hijo cuando se ejecuta explícitamente; no escriben memoria ajena,
  no cambian protecciones del juego y no diagnostican FEX por sí solos.
- La validación prevista usa MinGW-w64 + Wine x86-64 en un prefijo aislado y
  deja ejecutables/logs fuera del repositorio en
  `/home/adrian/wowforever-artifacts`; no se detienen descargas ni procesos
  existentes.
- El emulador Android x86-64 no se usará para fingir validación de runtimes
  ARM64. Solo se documentará el límite salvo que exista un componente adecuado.

## Implementation / Action Items

- [x] 1. Corregir `DynCodeProbe` y endurecer su contrato de resultados.
  - [x] 1.1 Pasar dos argumentos al caso RCX+RDX y validar el valor esperado.
  - [x] 1.2 Reservar shadow space y mantener alineación Windows x64 en el thunk
    que llama `GetTickCount64`.
  - [x] 1.3 Añadir caso de alias RW/RX, escritura inicial y actualización tras
    la primera ejecución, con flush y comprobación de ambos valores.
  - [x] 1.4 Registrar cada fase antes de llamar, acumular fallos y emitir
    `DYNCODE-END result=0` solo cuando todos los casos obligatorios pasan.
- [x] 2. Revisar `SlotProbe` como control host seguro.
  - [x] 2.1 Añadir una ruta de prueba sintética opt-in que ejercite lectura,
    `VirtualQueryEx` y registro de destino sin depender de `WowB.exe`.
  - [x] 2.2 Mantener el modo cliente existente (sin argumentos), solo lectura y sin matar hijos;
    devolver códigos distinguibles para error, timeout y éxito.
- [x] 3. Añadir tests offline de fuente/contrato y fixtures de salida; no hacer
  que pytest dependa de SDK MinGW ni de Wine.
- [x] 4. Compilar y ejecutar las sondas en Wine con prefijo aislado, conservar
  logs y hashes en artefactos, y probar casos de fallo/código de salida.
- [x] 5. Documentar instrucciones, límites, rutas y evidencia en README y
  `docs/fase-2-fex-api-probe.md` sin afirmar bug FEX ni crash reproducido.
- [x] 6. Marcar este plan como `done` y moverlo a `.agents/planning/done/`.

### Evidencia de cierre

- MinGW con `-Wall -Wextra -Werror`: compilación correcta.
- Pruebas finales: `WOWFOREVER_RUN_PROBE_INTEGRATION=1
  WOWFOREVER_PROBE_ARTIFACTS=/home/adrian/wowforever-artifacts uv run pytest
  tests/test_fase2_probes.py -q`: 4 passed (toolchain Wine/MinGW en PATH).
- ApiProbe, DynCodeProbe y SlotProbe sintético bajo Wine: exit 0.
- Negativos compilados: API ausente, flush fallido y constante incorrecta:
  exit 1; READY ausente: exit 4, sin PASS falso.
- Logs, ejecutables y SHA-256 en
  `/home/adrian/wowforever-artifacts/probes-offline-20260928/`.
- Revisión parent de resultados, rutas de error y diff; `git diff --check`
  sin incidencias. No commits, no ejecución de WowB ni del móvil.

## Open Issues

- La prueba host valida semántica de Windows x86-64 y el contrato de la sonda,
  no la traducción x86-64→ARM64 de FEX/Box64.
- El control sintético de `SlotProbe` no puede probar el offset real del loader;
  solo la mecánica de observación segura.
