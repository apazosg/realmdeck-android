---
id: wine-aa64-observation-2026-10-09
state: done
date: 2026-10-09
---

## Summary

Code55 verifica clear/Draw offscreen y backbuffer y30Present S_OK, pero el control
global es CONTROL_INVALID:24maps sin el PE+PanVK propio en un PID/startTicks/RUN
y cadena al root. Logcat apunta a18206/GraphicsProbeRe; el collector no conservó
su enumeración/rechazos, y no hay screenshot aceptado. La causa concreta del miss
no es demostrable con estos artefactos. Ver `docs/wine-aa64-readback-results.md`.

Se propone **instrumentar observabilidad host**, conservando APK/PE/pila/pins y
gates gráficos/de identidad, y solo después otro control físico único autorizado.
Aprobado por el usuario el 2026-10-09: «si, continuemos. continua de forma más
autonoma a partir de ahora. ten el objetivo que perseguimos presente». El control
anterior ya se consumió; esta operación autoriza exactamente un nuevo control.

## Content

### Baseline y límites protegidos

- Solo POCO xaga22041216G/Xiaomi, serialDEVICE_SERIAL, UID10330, package propio.
- Reutilizar APK55/SHA4818835a... y PE80c1c260.../96768bytes, sin rebuild ni cambios
  de pausa, geometría/shaders/culling/orden gráfico/Presents/30s. Legacy preservado.
- Mismos Proton/DXVK/wrapper/PanVKbeta17/Adrenotools/noGL y compiler47 AA64 c374...
  con backup54. Sin otro DLL/driver/flags/descargas/cliente activo, memoria o login.
- Conservar evidence55/audit/recibos. Reboot Android y nuevo bootId/UID muestreado
  vacío antes de otro Wine; sin tratar force-stop como contención.
- Host<=120s más cleanup20s,48maps máximos y6intentos de candidato por muestra;
  ninguna nueva señal, ptrace, dump de memoria ni proceso ajeno.
- Mantener PE+PanVK RX/samePID/UID/startTicks/RUN/root ancestry. Nombre, timestamps,
  logcat, API, file identity o UID compartido jamás sustituyen ese proof.

### Cambios propuestos de captura (solo después de aprobación)

1. En `scripts/diagnostics/usb_forever_trial.py`, conservar por muestra header y
   filas **exactas solo del UID10330** antes de parse/prioridad/corte. No persistir
   nombres/metadata de otros UID. Guardar hora inicio/fin, parámetros y truncación;
   máximo64muestras/64KiBpor muestra/4MiBtotal, respetando deadline/reserva actuales.
2. Registrar por candidato propio, dentro de límites, selección/prioridad/quota y
   motivo de cada skip/fallo: seen>=3, límite global, stat/UID antes/después, maps
   retornado/truncado, cmdline/identity change y final-reserve. Sin contenido de
   memoria ni ampliar candidatos por coincidencias no probadas.
3. Corregir el corte de elegibilidad confirmado en fuente: excluir cuotas seen
   agotadas **antes** de escoger los6intentos, manteniendo las prioridades/budgets.
   Esto evita una inanición posible, no presume que fuera la causa concreta de55.
   Tests deterministas para6antiguos agotados+un nuevo, NAMEtruncado/variante, UID
   extranjero, PID reuse, fallos de maps, truncación y ausencia de retry.
4. Registrar el motivo exacto de screenshot=false en los guards existentes. No
   capturar otras apps/keyguard/notificaciones ni introducir un PASS visual/ROI
   automático. La captura final puede seguir sin observar la ventana; su ausencia
   queda explícita, no se cambia el PE para prolongar presentación.
5. No cambiar el analizador para aceptar una identidad menos estricta. Validar los
   logs añadidos offline y conservar los negativos de sampler/evidencia existente;
   revisar la frontera de ownership antes de la prueba física.

### Control e interpretación

- Un único USB AOA con55 retenido después de reboot, captura externa previa a UI,
  consent/focus propios y cleanup existente. Sin repetir Wine por un fallo de captura.
- Explicar para el candidato realmente enumerado cada descarte, o conservar
  UNKNOWN si no se observa. Correlacionar relojes con su origen; elapsed del PE no
  se ancla al spawn del wrapper ni sirve como ownership.
- Si hay maps válidos pero PE anónimo, ancestry reparentada o ambigua, **STOP**:
  no sustituir proof por base0x140000000/logcat/UID. Otra attestation es una decisión
  contractual y necesitaría otro plan, no parte de este control.
- Aun con API/ownership cualificados, visual y WoW permanecen separados; sin
  evaluación visual independiente no hay PASS global ni APK jugable.

## Implementation / Action Items

- [x] 1. Aprobar instrumentación host y un único control posterior descritos.
- [x] 2. Implementar logs/eligibilidad/rechazos bounded y tests, sin cambiar el APK.
- [x] 3. Verificar tests dirigidos de sampling/streams/evidencia y revisión ownership;
  audit retenido/pins estables, sin duplicar builds o checks positivos anteriores.
- [x] 4. Reiniciar el POCO, verificar boot nuevo y ejecutar exactamente una vez.
  Control USB intentado una vez; abortado por guard de foco antes de consentimiento/
  prepare/Enter. Cero Wine, ningún nuevo RUN. No se afirma que se midiera el PE.
- [x] 5. Clasificar el miss/proof/visual sin relajar gates y cerrar el resultado.
  PRELAUNCH_ABORT/UNKNOWN, gráficos NOT_MEASURED. Resultado y evidencia sellada en
  `docs/wine-aa64-observation-results.md`; recuperación necesita nuevo alcance.

## Open Issues

- Control cerrado: `trial-20261009T080253Z-196d6511be`, boot10aa9149... nuevo; mismo
  APK instalado SHA4818835a.../compiler/backup verificados sin install/rebuild.
  Foco no propio en primera comprobación: abort pre-Enter. Después solo leyendo
  se observó UI propia y consentimiento unchecked, sin nuevo RUN ni Wine. La causa
  concreta del foco inicial no está guardada; no se presume una carrera demostrada.
  Sigue pendiente observar el PE+PanVK: no hay una nueva medición que atribuir.
  No retry automático bajo el control único. Propuesta de recuperación limitada
  prelaunch (entonces sin aprobación; posteriormente ejecutado) en
  `.agents/planning/done/wine-aa64-start-readiness-plan.md`. Resultado posterior en
  `docs/wine-aa64-start-readiness-results.md`.

- Implementación validada:81 tests dirigidos de sampling/observación/streams/logs/
  analizador PASS y revisión independiente GO. Corrección localizada posterior:
  guard exclusivo de jerarquía solo para screenshot (no cambia consentimiento/HID),
  con19 tests de observación PASS. Sin rebuild/edición del APK ni del analizador.
  Se registran escalares stat solo cuando el UID de esa lectura es propio; valores
  de UID ajenos no se conservan. No se suprimen futuras muestras por PID reciclado.
  Los guards screenshot son muestreados, no una garantía atómica ni proof visual.

- Falta el `ps` real de55; NAME/prioridad/elegibilidad exactos no son reconstruibles.
  Logcat evidencia un candidato, no su startTicks/PPID/root ni mapping PE.
- El PE podría mapearse anónimamente o quedar reparentado; este alcance observa,
  no redefine ownership. No se promete que el control resulte cualificado.
- No se modifica el lifetime para capturar el triángulo; puede faltar visual otra
  vez. Una captura durante presentación o hold adicional sería propuesta separada.
- Aprobado el alcance descrito, con más autonomía para decisiones técnicas
  reversibles dentro de él. El objetivo sigue siendo el APK jugable de WoW Forever;
  esta observación despeja la atribución gráfica, no sustituye juego/login/visual.
  Se mantienen los STOP de contrato/ownership y el control único. El plan
  completado anterior está en `.agents/planning/done/`.
