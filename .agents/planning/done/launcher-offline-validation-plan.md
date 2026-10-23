---
id: launcher-offline-validation-2026-09-28
state: done
date: 2026-09-28
---

## Summary

Revisión solicitada para aprovechar unas horas sin dispositivo físico. Propuesta:
consolidar la validación reproducible del launcher existente, sin anticipar la
integración del runtime antes del go de Fase 2. Aprobado por el usuario el
2026-09-28; implementado y validado en emulador x86_64.

## Content

### Estado contrastado

- Árbol Git limpio al comenzar; último commit `c38f760` (roadmap).
- Fase 1: instalador por lotes activo en el host al revisar. El arreglo de
  reanudación está fijado al fork; `verify.py` solo verifica loose, no CASC.
  No se ha medido de nuevo el porcentaje descargado ni la integridad del cliente.
- Fase 2: sigue sin go. Box64 termina sin logs del cliente y FEX falla en el
  loader. ApiProbe/TestD3D no demuestran arranque de WoW. Emulador x86_64 no
  sustituye móvil ARM64/PowerVR.
- Fase 3: núcleo y pruebas cross-process existentes; falta CertStore real.
- Fase 5: APK de diagnóstico y JNI, no launcher funcional del juego.
  MainActivity ya ejecuta ambas sondas fuera del hilo UI; no debe proponerse
  ese trabajo como si aún faltara. Falta automatizar el autotest, conservar
  evidencia y eliminar la copia manual del contrato.
- `targetSdk = 28` en código, pero la tabla de `docs/android-emulator.md` y
  el plan de Fase 5 conservan 35. Mantener compileSdk 35; no cambiar targetSdk.
- Memoria al revisar: 7,1 GiB disponibles y swap de 4 GiB prácticamente llena.
  No lanzar simultáneamente instalador, compilaciones grandes y emulador sin
  revisar recursos. No detener ni modificar la descarga sin autorización.
- Tamaños documentados no equivalen a tamaño final en disco: la bitácora de
  Fase 1 corrige la estimación antigua de 100,24 GiB a 84,7 GB del size manifest.
  No dar por demostrado que la instalación completa cabe en el móvil ni que
  el streaming ya resuelve sus necesidades de datos.

### Alcance recomendado para este bloque

1. Generar el asset Android del contrato desde `forever/contract.json` durante
   el build, con dependencias Gradle declaradas y sin dos fuentes mantenidas.
2. Permitir autotest sin interacción manual y añadir un runner de smoke test
   con serial explícito, timeout, exit code y logs fuera del repositorio.
3. Ajustar únicamente la documentación de esta validación y las referencias
   desactualizadas de targetSdk vinculadas a ella.

No incluye API JNI productiva de escritura por PID, supervisor del juego,
provisioning de Wine, descargador Android ni selección automática de drivers.
Son trabajos posibles, pero con más dependencias y riesgo que este bloque.
`verify` CASC merece una tarea propia con alcance y fixtures definidos.

## Implementation / Action Items

- [x] 1. Revisar instrucciones locales y recursos disponibles antes de ejecutar.
  - Al iniciar: 8,7 GiB disponibles; emulador ya activo junto al instalador.
    No se detienen procesos; limitar concurrencia de compilación.
- [x] 2. Generar `contract.json` como asset de build desde el contrato canónico.
  - [x] Verificar igualdad del asset empaquetado con la fuente.
  - [x] Probar que una modificación temporal de una fuente de prueba invalida
        la tarea, sin alterar el contrato real ni depender de la red.
- [x] 3. Automatizar el autotest existente y su recogida de evidencia.
  - [x] El runner distingue PASS, FAIL y timeout mediante código de salida.
  - [x] No confunde logs antiguos con el resultado de esta ejecución.
  - [x] El autotest permanece fuera del hilo UI y el serial es explícito.
- [x] 4. Validar el bloque secuencialmente cuando la memoria lo permita.
  - [x] Bibliotecas nativas existentes reutilizadas; no cambió código Rust.
  - [x] `./gradlew --offline :app:assembleDebug --max-workers=1` desde `android/`.
  - [x] Instalar y ejecutar el smoke test en emulador; guardar logs externos.
  - [x] Pruebas dirigidas del runner para fallo y timeout, sin repetir suites
        no afectadas. No atribuir al smoke cobertura ARM64 ni del cliente real.
- [x] 5. Actualizar evidencia y corregir referencias targetSdk obsoletas.

### Evidencia de cierre

- Pruebas dirigidas finales: `uv run pytest tests/test_android_launcher_smoke.py
  tests/test_android_contract_asset.py -q`: 6 passed, 1 skipped.
- Integración aislada opt-in: `WOWFOREVER_RUN_GRADLE_INTEGRATION=1 uv run pytest
  tests/test_android_contract_asset.py -q`: 2 passed. Cambia fixture sin tocar
  el contrato ni los outputs del build real.
- Build offline correcto; asset empaquetado idéntico al canónico (2976 bytes).
- Smoke final con el runner corregido: PASS, exit 0, run
  `run-d21eaae956e148309cbf9bf9d8d737ff`. Evidencia en
  `/home/adrian/wowforever-artifacts/android-launcher/smoke/` bajo ese ID.
  `child_confirm=PATCHED`, `rescan_already_patched=true`, `scan_ms=17612`,
  `legibles=383/383`, `first_err=none`.
- Revisión final: `git diff --check` sin incidencias. No commits.
- La revisión eliminó el override público de run_id y sustituyó coincidencias
  por prefijo por tokens exactos; UUID nuevo por ejecución. Se rechazan salidas
  dentro del repo y timeouts no finitos. No se borra logcat global.

## Open Issues

- Bloque aprobado por el usuario el 2026-09-28.
- Si no hay margen de memoria, diferir la ejecución del emulador; preguntar
  antes de pausar el instalador. La revisión actual no ha iniciado cargas.
- Al volver el móvil, retomar el plan existente `android-runtime-ab-plan.md`:
  priorizar DynCodeProbe, experimentos E1 con una variable por corrida y
  SlotProbe; documentar instrumentación residual antes de comparar baselines.
  Un resultado positivo de la sonda no demuestra compatibilidad de WoW.
- CertStore real depende de disponer de datos suficientes y de que el cliente
  alcance esa fase; no se atribuye el crash ARM64 solo a los datos ausentes.
