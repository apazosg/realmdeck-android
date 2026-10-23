---
id: android-testbench-2026-09-28
state: done
date: 2026-09-28
---

## Summary

Ampliar el APK diagnóstico existente, sin empaquetar Winlator ni integrar ningún
runtime, para convertirlo en un banco de pruebas reproducible en el emulador
`emulator-5554`. La batería ejecutará secuencialmente las pruebas reales ya
disponibles (JNI/contrato, `ExecProbe` y `crossProcessSelfTest`) fuera del hilo de
UI, conservará resultados por `run_id` y permitirá exportarlos con SAF. Se mantiene
`targetSdk 28`, el autotest por `Intent` usado por el smoke y la separación entre
evidencia del repo y artefactos grandes.

La aprobación explícita del usuario ("me parece bien. adelanta") autoriza pasar
directamente a ejecución con este plan en estado `approved`; el parent cerrará el
plan al finalizar.

## Content

### Límites y decisiones

- Cambios solo en `android/`, `scripts/android-launcher-smoke.py`, tests asociados
  cuando sean necesarios, `docs/android-emulator.md` y `docs/fase-5-launcher.md`.
- UI con Views existentes y sin dependencias nuevas. No se añade API de adaptadores,
  perfiles ejecutables, Wine, Winlator ni FrontMage; cualquier perfil externo, si se
  menciona, será informativo.
- Catálogo mínimo real: validación JNI/contrato, `ExecProbe` y
  `crossProcessSelfTest`. La cola será secuencial y no arrancará otra sonda mientras
  una anterior siga viva.
- Estados normativos: `PASS`, `FAIL`, `TIMEOUT`, `UNSUPPORTED`. Un timeout no se
  presenta como cancelación nativa: se conserva la incertidumbre y se bloquea la
  siguiente prueba hasta conocer que el trabajo terminó.
- Cada corrida persistirá `run_id`, estado, tiempos, versión de app, ABI, SDK y
  resultado detallado/logs. Runtime será explícitamente `none`; no se inventan
  versiones de GPU. Exportación JSON/texto mediante `ACTION_CREATE_DOCUMENT`.
- Se conserva el contrato de arranque existente (`run_selftest=true`, `run_id`) y
  la salida PASS/FAIL del smoke; se amplía el runner solo para consumir la nueva
  evidencia sin debilitar el filtrado por corrida.
- Verificación en el AVD existente, sin detener procesos ajenos; los artefactos se
  guardan en `/home/adrian/wowforever-artifacts` y no se versionan APKs/binarios.

## Implementation / Action Items

- [x] 1. Releer este plan y auditar el estado actual de los archivos de ownership;
  conservar cambios previos sin sobrescribirlos.
- [x] 2. Implementar modelos puros de resultado/corrida y persistencia local en
  `android/`, con serialización estable, tiempos monotónicos/de pared y estados sin
  false-pass.
  - [x] 2.1 Añadir pruebas unitarias puras para estados, persistencia, cola
    secuencial y recreación/lifecycle donde sea factible sin dependencias Android.
- [x] 3. Sustituir la ejecución ad-hoc de la Activity por un coordinador secuencial
  fuera de UI que envuelva JNI/contrato, `ExecProbe` y `crossProcessSelfTest`,
  respete timeout sin fingir cancelación y evite duplicados tras recreación.
  - [x] 3.1 Mantener compatibilidad con los extras de `Intent` del smoke (`run_id`,
    `run_selftest`) y producir líneas PASS/FAIL existentes.
- [x] 4. Ampliar la UI Views con catálogo, ejecución de batería, estado por prueba,
  corrida actual/historial y botones de exportación JSON/texto mediante SAF.
  - [x] 4.1 Mostrar `UNSUPPORTED` solo cuando una capacidad no esté disponible;
    GPU se deja como no medida si no hay API adecuada y fácil.
- [x] 5. Actualizar `scripts/android-launcher-smoke.py` y tests asociados únicamente
  para el contrato ampliado, manteniendo `run_id` único, códigos PASS/FAIL/TIMEOUT,
  contrato generado y rechazo de resultados antiguos.
- [x] 6. Documentar diseño, catálogo, persistencia/exportación y limitaciones en
  `docs/android-emulator.md` y `docs/fase-5-launcher.md`, incluyendo evidencia
  reproducible y que no se probó runtime/Wine/GPU real.
- [x] 7. Ejecutar verificación dirigida: tests puros/Python, build Gradle offline
  con `--max-workers=1`, instalación/arranque y controles UI explícitos por `adb` en
  `emulator-5554`, exportación verificable si resulta viable. Comprobar primero
  espacio libre y no detener procesos existentes.
- [x] 8. Registrar comandos exactos, resultados y ruta de evidencia; dejar checklist
  completada salvo el cierre/movimiento del plan, que corresponde al parent.

## Open Issues

- Ninguno abierto al aprobar el plan. Si el timeout de una sonda no permite saber
  que terminó, la batería quedará bloqueada en ese punto y lo reportará como riesgo,
  en vez de iniciar otra sonda concurrente o atribuir PASS.

## Cierre y evidencia

Implementación revisada, incluidos identificación segura de corridas, exportación
de la corrida seleccionada, sincronización y parsers sin falsos PASS conocidos.

- `./gradlew --offline --max-workers=1 :app:runDiagnosticLogicSelfTest`: PASS.
- `./gradlew --offline --max-workers=1 :app:assembleDebug`: BUILD SUCCESSFUL.
- Batería final `run-4d2f6f603cc146a0a4cafedab746936f`: contrato PASS,
  ExecProbe UNSUPPORTED (control C2 SKIP, sin .so en nativeLibraryDir),
  cross-process PASS. No se presenta la batería completa como PASS.
- Smoke compatible final `run-3f22d5b280c24156aa35124436b46765`: PASS.
- Evidencia externa bajo `/home/adrian/wowforever-artifacts/android-testbench/`:
  carpeta de batería con JSON/logs/UI y `smoke/` con la corrida del smoke.
- SAF JSON validado previamente para la corrida exacta
  `run-e8f1bc3fa0094e7abbe6c1a844efa0f8`; no se repitió tras ajustar solo parsers.
- `git diff --check` correcto. No commits. Sin prueba específica de onNewIntent
  final, GPU real, ARM64 ni runtimes Windows; onCreate/smoke sí verificados.
