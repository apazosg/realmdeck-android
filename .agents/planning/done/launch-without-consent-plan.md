---
id: launch-without-consent-2026-11-02
state: done
date: 2026-11-02
---

## Summary

El usuario pidió eliminar el consentimiento por lanzamiento: «podemos eliminar ese
consentimiento, no tiene sentido y no aporta nada salvo frustración al usuario».
Play debe preparar y lanzar en la misma acción, sin casilla ni segunda confirmación,
manteniendo visibles el aviso de inicio de sesión manual y todas las comprobaciones.

## Decisiones

- Play es la única confirmación. Preparar y lanzar comparten acción tanto desde Home
  como desde el gestor de cliente («Jugar»).
- El aviso «Se abrirá WoW. El inicio de sesión será manual.» permanece visible sin
  requerir aceptación.
- No cambian preparación, validación de cliente, admisión, recuperación de reserva
  previa, exclusión de IO/Wine ni las comprobaciones finales antes de lanzar.
- Un fallo o una cancelación de preparación no lanza y no deja un estado sin salida:
  si el coordinador no puede iniciar el intento, se descarta la preparación inactiva
  para que Play vuelva a ofrecerse.
- Cambiar resolución o restablecer gráficos sigue descartando una preparación
  inactiva y no lanzada; el siguiente Play prepara y lanza.

## Implementación

- `ForeverFunctionalPocActivity.kt`: se elimina el estado `consent` y el panel
  «Listo para iniciar»; `requestLaunch(snapshot)` centraliza el lanzamiento y
  `prepareEnvironment` lo invoca al terminar.
- `US-004` actualizada (AC-07, FR-007, FR-017, TC-012, TC-022, TC-030 y supuestos).
- Pruebas estáticas de `tests/test_functional_launcher_ux.py`,
  `tests/test_functional_display_fit.py` y `tests/test_functional_poc_overlay.py`
  ajustadas al flujo de una sola acción.

## Evidencia y límites

- El usuario informó que el candidato funciona en el dispositivo; no hay sesión
  medida ni aceptación formal de rendimiento.
- El smoke de host `scripts/diagnostics/android-process/run_functional_launcher_ux.py`
  todavía busca la etiqueta de preparado, la casilla y el botón «Lanzar WoW». Queda
  desalineado con esta UX y debe actualizarse antes de usarlo en el dispositivo; no
  se presenta como herramienta válida para este flujo.
