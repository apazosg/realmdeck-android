---
id: wine-aa64-start-readiness-2026-10-09
state: done
date: 2026-10-09
---

## Summary

El collector de atribución está implementado/verificado, pero el control único
se abortó antes del consentimiento/Enter/Wine porque el primer dump no tenía top
propio. Una lectura posterior confirmó UI propia disponible, checkbox unchecked
y ningún nuevo RUN. No se conoce la causa precisa del foco inicial. Ver
`docs/wine-aa64-observation-results.md`.

Para trabajar con más autonomía sin relajar guards ni repetir Wine, se propone
esperar de forma acotada la UI propia al arrancar y permitir recuperación solo
antes de cualquier consentimiento/prepare. Objetivo: medir PE/PanVK con la carga
positiva55, después resolver visual y avanzar hacia WoW; no acumular sondas sin
 gate al juego. Aprobado el 2026-10-09; ejecución autorizada.

## Content

### Decisiones y límites

- Solo host `usb_forever_trial.py`/tests, mismo APK55/PE80c1/runtime/compiler/backup.
  Sin install/rebuild/downloads, cambios de pausa/driver/shaders/culling/API ni
  analizador, memoria/auth/login/WoW. Dispositivo/package/UID/serial idénticos.
- Tras propia activity start, esperar UI top propia con consultas sin input hasta
  8s/máximo3dumps. Cada llamada queda limitada por el menor deadline local/global,
  sin consumir reserva final. Revalidar la UI inmediatamente antes de cada tecla.
  No se acepta un dump fallido/ajeno como consentimiento ni se guarda contenido
  de otra UI. No dismiss adicional, secure lock bypass, Back/Enter genérico ni click.
- El usuario levantó los topes de número de controles/lanzamientos. Cada control
  conserva 120s+cleanup20s, un solo prepare/Enter, evidencia separada y nuevo
  reboot/boot/UID vacío previo. No se repite a ciegas: diagnosticar el resultado
  antes de decidir otro control y preservar cualquier estado ambiguo.
- No hay retry inline ni activación doble dentro de un RUN. La autorización para
  repetir controles no permite relajar ownership, el aislamiento ni convertir
  muestras parciales en PASS. Cambios contractuales requieren evaluación aparte.
- Mantener6intentos/48maps/64PS y proof exacto PE+PanVK RX/samePID/startTicks/UID/RUN/
  ancestry. Si se demuestra PE anónimo o reparenting, STOP y otro plan contractual.
- Capturas solo con guards propios, visual=false hasta evaluación independiente.
  No prometer cierre visual ni atribuir por logcat/nombre/UID/tiempo.

## Implementation / Action Items

- [x] 1. Aprobación y autonomía: «hazlo. no importan los límites, ese móvil es tuyo
  para hacer todas las pruebas que quieras». Sustituye topes de número de pruebas,
  no deadlines técnicos, aislamiento, evidencia ni verificación de identidad.
- [x] 2. Implementar espera sin input y logs no sensibles; tests con reloj falso:
  disponible tarde, foco ajeno permanente, dumpfallo, deadline/reserva, ninguna
  tecla antes de ownready, imposibilidad de repetir después de intención/RUN.
- [x] 3. Tests dirigidos/revisión de frontera; 57 tests PASS, revisión GO. Los pins
  retenidos/instalados se comprueban en el preflight previo sin build.
- [x] 4. Dos reboots/controles, sin retry inline, ambos readback4/Presents30 PASS;
  segundo control con arreglo fresh-first diagnosticado y autorizado.
- [x] 5. CONTROL_INVALID conservado; preservar estado/backup/evidencia,
  documentar gate y siguiente paso hacia visual/WoW sin modificar contratos.
  Resultado: `docs/wine-aa64-start-readiness-results.md`; pendiente ownership/PE.

## Open Issues

- Endurecido el ciclo de dumps: ruta UUID por dump, clear previo y cleanup exacto
  en finally; fallo de cleanup impide entrada. Timeout cooperativo, no garantía de
  preemption del SO. Resultados tardíos no autorizan consentimiento.
- Primer control de esta operación: UI propia a 5734ms/2dumps, RUN nuevo y cuatro
  readbacks/30Present positivos. PS sample20 sí enumeró PID13939 NAME=wine, pero
  selection-cap lo descartó detrás de seis runtime ya vistos 1–2 veces. Se corrige
  scheduling host (misma prioridad: menos muestras primero), conservando6/48 y
  todas las verificaciones. No prueba ownership ni resuelve PPID1/PE anónimo.
  Repetición diagnosticada autorizada por el usuario, mismo APK/PE/runtime.
- Segundo control capturó candidato14064 UID10330/startTicks6831/PPID1 y PanVK RX
  privado dev65079/inode2089381, pero PE anónimo. No se modifica el contrato para
  aceptar nombre/time/base/UID. La operación de readiness se cierra; no equivale a
  cualificación gráfica ni WoW. Próximo diseño separado: supervisor por RUN.

- La UI posterior propia no demuestra causa/duración del foco inicial. Una espera
  puede no resolverlo; no se promete éxito. Si persiste, se necesita preparación
  legítima de la UI por el usuario, no automatización fuera de ownership.
- El collector mejorado todavía no ha observado el PE real en un nuevo RUN. La
  atribución podría revelar reparenting/PE anónimo y bloquear el contrato actual.
- La captura final puede ocurrir después de Present; no se cambia lifetime/ROI.
- La autonomía solicitada se aplica a decisiones reversibles dentro de límites;
  autorización explícita recibida para controles repetidos en el mismo POCO. Se
  mantienen guards y presupuestos por ejecución; no se requieren aprobaciones
  rutinarias por reintento diagnosticado, pero sí evaluar nuevos contratos/riesgos.
