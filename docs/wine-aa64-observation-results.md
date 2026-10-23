# Wine AA64: collector preparado; control abortado antes de Wine

2026-10-09. Alcance aprobado en
`.agents/planning/done/wine-aa64-observation-plan.md`. El objetivo sigue siendo un
APK jugable de WoW Classic Forever: esta operación mejora la observación de la
atribución PE/PanVK, no prueba WoW ni sustituye presentación visual.

## Implementación y validación

Cambios solo host en `scripts/diagnostics/usb_forever_trial.py` y tests nuevos
`tests/test_usb_forever_observation.py`:

- Conserva header válido exacto y filas propias UID10330 de `ps` antes de parsear,
  priorizar o cortar. Máximo64muestras/64KiBpor muestra/4MiBtotal; sin metadata de
  otros UID. Header ausente/inválido/excesivo queda explícito y no se persiste como
  contenido arbitrario. Ficheros privados600/fsync.
- Filtra cuotas agotadas antes de seleccionar6intentos. Registra prioridad,
  selección y rechazos: cuota/cap/reserva, stat/UID, mapas, cmdline e identidad.
  Los escalares stat se conservan solo si la lectura UID correspondiente es propia;
  no se guardan args cmdline, texto proc bruto ni valores UID ajenos.
- Mantiene48maps totales,3por PID,6intentos por muestra y la reserva final. Sin
  retry dentro de una lectura; una muestra posterior puede observar de nuevo un
  PID, evitando una supresión permanente incorrecta tras reutilización del PID.
- Cada snapshot guardado incluye nombre del fichero maps, longitud y SHA. Test
  offline comprueba el vínculo de escritura/ordinal/hash. Analizador sin cambios:
  PE+PanVK RX, PID/startTicks/UID/RUN y ascendencia siguen siendo obligatorios.
- Screenshot registra motivos fijos, sin contenido ajeno. Solo en esa ruta se exige
  jerarquía enteramente propia antes/después; el consentimiento/HID no cambia.
  Dos dumps no son una garantía atómica frente a overlays ni prueba visual.

Verificación dirigida del builder:

```bash
/home/adrian/.local/bin/uv run --offline pytest -q \
  tests/test_usb_forever_probe_sampling.py \
  tests/test_usb_forever_observation.py \
  tests/test_usb_forever_trial_streams.py \
  tests/test_usb_forever_graphics_log.py \
  tests/test_panvk_wine_probe_evidence.py
```

81PASS; revisión independiente de ownership GO. Parent acotó posteriormente el
guard exclusivo al screenshot para no alterar el consentimiento:19tests de
observación PASS. Los conteos se solapan, no son100casos distintos ni suite completa.
Las correcciones incluyeron negativos de PID reuse, starvation con6agotados de
prioridad mayor, header excesivo, cuota total, maps/cmdline truncados y UI mixta.

## Artefactos y reinicio

No se compiló, instaló ni modificó otro APK/PE/runtime. Se verificaron los cuatro
artefactos del recibo retenido y el SHA del APK **ya instalado** en el dispositivo:
code55/0.2.31-automated,371186229bytes,
`4818835af27aad8c9196af15976d9debeef933193a03f8cb360ceb35bd60d514`.
PE readback80c1c260.../96768bytes, misma pila y compiler47AA64c374b107...; backup
original54/06dc23... intacto. La entrega normal code33 permanece sin publicar.

Preflight:
`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`
`observation-preflight-20261009T080203Z-aba2e2a321`.
Reboot único, boot nuevo `10aa9149-86d6-41b4-9ebf-d25aed11152f`, distinto del boot55
anterior6147a0fe...; UID10330 muestreado solo con `ps`, no prueba de contención.

## Único control: PRELAUNCH_ABORT

Comando ejecutado exactamente una vez:

```bash
/home/adrian/.local/bin/uv run --offline python scripts/diagnostics/usb_forever_trial.py \
  --output /home/adrian/wowforever-artifacts/physical-trials/xaga-20261002 \
  --version-code 55 --version-name 0.2.31-automated --seconds 120
```

Evidencia en `trial-20261009T080253Z-196d6511be` del mismo parent externo:

- 08:02:53.363Z: preflight/host log previo a UI;08:02:54.221Z: propia activity iniciada.
- 08:02:57.523Z: guard `UI top node is not the own package`; `prepare_pressed=false`.
- No Tab/consent/prepare-intent/Enter, nuevo RUN, Wine, reparación ni mapas gráficos.
  `trial-result.json`:UNKNOWN/runIdnull. No se reutiliza el RUN antiguo ni sus
  readbacks como una nueva medición. No hubo retry ni force-stop de un RUN ajeno.

Logcat propio muestra inicialización lenta y cambios de resume/focus, pero no
demuestra qué ventana provocó el rechazo. No se guardó UI/screenshot ajeno ni se
descartó el guard. La causa exacta queda UNKNOWN, no se culpa a PanVK/DXVK.

Inspección posterior solo lectura, sin teclas/start/screenshot/Wine:
08:05:31.867Z: top y jerarquía propios, checkbox de consentimiento presente,
unchecked/unfocused. Sigue el RUN histórico `RUN-1791530356088-76612335773`;
**ningún nuevo RUN**. Muestra propia: app7712 y logcat8058, sin Wine observado.
Esto muestra que después la UI estaba disponible, no cuándo cambió ni por qué.

Otra lectura compara `ps` shell para esos PID independientemente verificados UID/
stat antes-después: UID numérico10330 y nombres propios. No hay evidencia de una
incompatibilidad del formato UID en este dispositivo; no se midió el PE objetivo.

`post-abort-own-state.json`, `shell-ps-own-diagnostic.json`,
`observation-result.json` y `collector-provenance.json` conservan estos límites.
`evidence-sha256-observation.json` sella10ficheros estables SHA/tamaño,600/fsync.

## Gate siguiente hacia WoW

La atribución PE/PanVK sigue pendiente. Los resultados positivos anteriores de55
en `docs/wine-aa64-readback-results.md` no cambian. No hay nuevo resultado gráfico,
proof visual, login ni WoW.

El control USB de esta operación se intentó una vez y terminó antes del lanzamiento.
Después el usuario aprobó readiness y controles repetidos. Plan ya cerrado:
`.agents/planning/done/wine-aa64-start-readiness-plan.md`. Resultado posterior:
`docs/wine-aa64-start-readiness-results.md` (dos controles con gráficos positivos;
candidato capturado con PanVK RX, pero PE anónimo/PPID1). No se rebajó foco/keyguard
ni contrato de identidad. Próximo gate: ownership real por RUN, no otro driver.
