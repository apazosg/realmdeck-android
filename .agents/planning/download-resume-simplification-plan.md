---
id: download-resume-simplification-2026-10-25
state: in-progress
date: 2026-10-25
---

## Summary

**Alcance vigente (2026-10-25):** tras la ventana anterior de lectura pasiva, el
usuario autorizó pruebas locales y Android por ADB Wi-Fi, pausa/reanudación normal,
control mediante scrcpy y «Autorizar C147 local». Se puede preparar/auditar C147 y
actualizar conservando datos tras confirmar pausa; no publicar, desinstalar,
reiniciar, borrar progreso ni cambiar seguridad/garantías. No aceptar costes nuevos
sin consultarlos. Estas autorizaciones posteriores sustituyen el límite pasivo de
aquel turno, no sus resultados ni sus limitaciones.

Continuación aprobada mediante «sí, adelante»: conservar todo el trabajo anterior
y completar su validación. Las reglas vigentes del dispositivo permiten también
lanzar actividades por ADB: ya se abrió C147 directamente, sin intervención manual,
reinstalación ni juego. Se mantienen privacidad, progreso y consulta de costes nuevos.

Antecedente de lectura pasiva:
[observación del log retenido](../../docs/download-passive-log-observation.md).
Se mide la transferencia en curso, no el tiempo desde intención a primer byte;
aquella observación no cubría el paso 3. La prueba activa posterior se registra abajo.

Objetivo solicitado: simplificar descarga y reanudación, reducir interacciones
repetidas y tiempo hasta transferencia real, revisar comprobaciones previas sin
debilitar las garantías acordadas y resolver cuellos con medidas antes/después.
Este plan continúa la investigación existente; no reabre la publicación C146 ni
autoriza otra entrega pública. **Aprobación local inicial (2026-10-25):** el usuario
eligió «Aprobar trabajo local» y confirmó «adelante». Se autorizan diagnóstico,
cambios mínimos de código/UI y pruebas locales (pasos 2–6 y documentación del 8),
sin operar el teléfono ni publicar una APK. Los cambios de garantías/contrato
siguen requiriendo propuesta y aprobación explícitas.

Ampliación autorizada: el usuario permite pruebas locales y con Android mediante
ADB Wi-Fi y, al detectarse servicio foreground activo en C146, eligió «Observar y
pausar normalmente»: leer progreso, pausa cooperativa por UI y medir reanudación
de la versión instalada. Posteriormente autorizó preparar, auditar e instalar C147
local conservando datos y progreso, solo con pausa confirmada.

Solicitud renovada mediante objetivo el 2026-10-25: «Reducir el tiempo de
preparación inicial al iniciar o restaurar una descarga. mejorar la ui UX para
que el usuario no tenga que pulsar varias veces hasta iniciar la descarga.»
Se reutiliza este plan para no abrir un trabajo paralelo equivalente. Establecer
el objetivo no se interpreta como aprobación de los pasos pendientes ni como
permiso para operar el dispositivo.

## Content

### Punto de partida comprobado

- Rama `perf/download-bottlenecks`, HEAD `342ee76`, árbol limpio al iniciar.
  El plan cerrado `done/public-performance-release-plan.md` acredita C146 publicada
  e instalada. Las afirmaciones anteriores de publicación pendiente en el plan de
  rendimiento y sus notas no son el estado vigente; reconciliarlas al documentar.
- US-008 AC-07/FR-008 ya limita instalación habitual a tres acciones desde Home;
  AC-10/FR-011 exige Reanudar en una acción si el consentimiento sigue válido.
  Ambas historias US-007/008 están `dirty`, pero sus enmiendas citadas tienen
  aprobación explícita registrada: no interpretar ese estado como permiso para
  cambiar sus contratos.
- `ForeverClientManagement.kt:228-291`: preparación llama `qualify()` y `plan()`;
  confirmar llama otra vez `qualify()`; Reanudar llama `qualify()` y `plan()` antes
  de entregar al servicio. La reanudación compara identidad/revisión y presupuestos
  residuales contra el plan previo. Son llamadas repetidas, no redundancia probada.
- `ForeverClientUpdater.kt:345-386`: `plan()` calcula bajo lease y `execute()`
  recalcula bajo otro lease, compara el plan completo y comprueba espacio fresco.
  Esa separación protege cambios entre mostrar costes y ejecutar: no quitar la
  segunda planificación sin demostrar una alternativa con las mismas garantías.
- `ForeverClientUpdateForegroundService.kt:27-74`: candidato de consentimiento y
  plan de reanudación son solo memoria del proceso. Recrear Activity no equivale a
  matar el proceso; los checkpoints persistentes no son consentimiento persistente.
- Evidencia anterior: inspecciones completas de 225,435 y 169,203 s; C144 registra
  aproximadamente quince minutos de preparación. No constituyen una línea base
  actual y comparable de inicio/reanudación C146.
- C145 redujo fuerza por objeto de 7,549 a 3,000 ms, pero su ventana de 610,573 s
  solo alcanzó 0,40085 MB/s útiles y acumuló 432,024 s esperando fuentes. C143 llegó
  a 3,878 MB/s en otra cohorte. No son A/B causal ni demuestran que grupo16 mejore
  el tiempo total. La preparación y la presión de disponibilidad son problemas
  separados; no aumentar cuotas ni cambiar el orden del escritor por intuición.

### Alcance y decisiones propuestas

Propuesta para esta solicitud: aprobar diagnóstico local reproducible y mejora
mínima de código/UI dentro de las garantías siguientes (pasos 2–6 y documentación
del 8). La prueba física del paso 7 requiere una autorización separada. Antes de
implementar, concretar el cuello con evidencia y registrar cualquier cambio de
comportamiento en UC/AC para aprobación; no eliminar controles solo porque se
invocan más de una vez. Dar respuesta visual inmediata a la primera pulsación y
evitar reentradas durante la preparación; conservar la confirmación final de
costes y la reanudación con una sola acción cuando el plan siga siendo válido.

1. Mantener el contrato UX aprobado: instalación ≤3 acciones habituales y Resume
   en una acción para el plan aún válido. Suprimir reentrada/pantallas/confirmaciones
   rutinarias solo si realmente aparecen; no inventar una reducción si ya se cumple.
   Conservar resumen visible de destino, versión, descarga y espacio antes de aprobar.
2. Priorizar latencia previa: medir calificación, inspección del destino, catálogo,
   planificación, revalidación del ejecutor y recuperación antes de optimizar.
   Separar trabajo inmutable repetido dentro de una operación de observaciones
   frescas necesarias después de una pausa o del tiempo de decisión del usuario.
3. Aplicar primero la menor eliminación de trabajo demostrablemente duplicado que
   conserve las guardas. No añadir caché temporal arbitraria, receipts de confianza,
   opciones, formatos nuevos ni una segunda máquina de estados de pausa viva.
4. Investigar después esperas de fuente/backpressure si siguen siendo dominantes.
   Una mejora local requiere medida y regresión; no mezclar a la vez varias políticas
   que impidan atribuir el resultado ni producir APKs solo diagnósticas en cadena.
5. Cualquier cambio de consentimiento entre procesos, frecuencia de comprobaciones,
   binding, durabilidad o recuperación exige propuesta explícita, análisis de riesgo,
   actualización UC/AC y aprobación antes de derivar FR/NFR/TC e implementar.

### Garantías que permanecen

- Wi-Fi-only; nada remoto por abrir Home ni descargas antes del consentimiento.
- Identidad/catálogo congelados, consentimiento y costes por volumen, espacio fresco,
  exclusión Wine/updater, no-follow y fallo cerrado ante destino cambiado/desconocido.
- Autenticación individual completa; verificación pendiente no cuenta como contenido
  válido y no permite publicación/activación hasta completar todas las barreras.
- Política full/light ya aprobada para sourceless dirigido; otros caminos intactos.
  Grupos de hasta 16 con flush parcial, 34 claves/1 GiB y 16 fetch workers intactos.
- Fuerza de datos → fuerza de checkpoint → acknowledgement/retiro de raw; sin reset,
  migración, borrado de staging o reparación/descarga extra implícita.

### Ventana física autorizada — solo prueba local

- Comprobar primero, de forma no destructiva, dispositivo, destino, espacio y si
  hay juego/transferencia activos; si los hay, detener la intervención y consultar,
  no interrumpirlos por cuenta propia.
- Registrar la línea base disponible en la versión instalada, sin borrar progreso.
  Preparar/auditar una candidata local e instalarla conservando datos únicamente
  si el usuario autoriza también esa instalación; no publicar ni subir una APK.
- Observar primera pulsación, preparación, pausa/reanudación y reconexión de UI;
  limitar la observación de payload a dos minutos tras arrancar. No presentar esa
  ventana como prueba de throughput sostenido de diez minutos. Confirmar destino y
  costes visibles antes de consentir, y consultar si cambian o resultan desconocidos.
- No resetear/borrar cliente o staging, matar un juego, reiniciar el dispositivo ni
  cambiar consentimiento entre procesos. Comparar fases/cohortes disponibles y
  declarar diferencias de caché/progreso; no prometer un A/B causal si no lo es.
- Candidata física autorizada mediante «Autorizar C147 local»: **C147 /
  `0.2.123-download-startup-test`**, solo local. Cambiar únicamente la identidad de
  candidata y sus expectativas de test; reconstruir con inputs autenticados y
  auditar el APK exacto. Instalar como actualización conservando datos solo tras
  pausa cooperativa confirmada. Sin desinstalar, publicar,
  subir, cambiar firmas ni forzar un downgrade si la actualización no es compatible.

### Medición y aceptación

Prioridad reafirmada por el usuario tras medir C148: **medir y reducir el tiempo
completo hasta empezar la transferencia**; los 16–24 minutos actuales no son una
aceptación. Antes de otra candidata:

- Correlacionar logs ya existentes de catálogo, orden físico, inspección,
  checkpoint y solicitud de fuente. Un agregado de tamaños de ~0,2 s no explica
  minutos; los relojes de consumidor/carga se anidan y no deben sumarse dos veces.
- Aislar el coste de `checkpointMembership`: actualmente realiza dos consultas SQL
  por objeto checkpointed (~356.000 en el dispositivo), antes de cada inspector.
  Medir el patrón actual frente a lotes con el límite existente. Solo aplicar la
  amortización si conserva metadatos autoritativos, ordinales únicos, rechazos,
  cancelación y todos los contrastes posteriores; sin cachés entre operaciones.
- Si los logs no bastan, realizar un perfil de muestreo diagnóstico acotado de la
  app instalada, sin otro APK, reset ni nueva confirmación de costes. Declarar el
  sobrecoste del perfil; no usar esa latencia como A/B de producción. Conservar
  artefactos filtrados fuera del repo y pausar cooperativamente al terminar.
- Tras regresiones y revisión, contrastar el recorrido Android completo y terminar
  la reapertura al gestor con el botón real «Ver descarga». No repetir toda una
  descarga únicamente para corregir una expectativa errónea del guion.

Diseño aprobado el 2026-10-26, **aún no implementado**: los lectores de
checkpoint posteriores conservan la resolución escalar de metadatos/ordinales
por frame. La prueba física C149 usa su APK congelada; el trabajo local siguiente
es independiente y no debe sustituir esa medición:

- Usar el mismo catálogo autoritativo y ordinales en lotes de hasta 256 también
  en inspección, carga y verificación del prefijo sellado. Mantener todas las
  comparaciones por frame y los controles globales; la ruta legacy sin membresía
  y la ruta con fuente no cambian de contrato.
- Validar CRC/tipo antes de acumular; resolver y validar el lote antes de aplicar
  sus registros, finalizar o truncar una cola incompleta. Un fallo no puede perder
  progreso que antes se conservaba. No cambiar formatos, ownership ni orden durable.
- Respetar `cpByteEnd` y comprobaciones frescas por grupo del verificador. Los lotes
  son locales a cada lector; nada de caché compartida ni confianza entre leases.
- **No** anticipar el stream de entrada del escritor: su callback escalar y sus
  límites de prefetch/raw permanecen intactos, evitando dependencias de backpressure.
- Exigir equivalencia/rechazos, cancelación, cola parcial y prefijo solapado en
  pruebas focales e inspección independiente. Si no se preservan esas propiedades
  con una modificación acotada, detener ese ajuste para decisión, no relajar una
  guarda. No generar otra candidata hasta acabar las pruebas/revisión; C149 continúa
  su ensayo independiente sin aceptar automáticamente costes nuevos.

La inspección inicial concluyó que agrupar carga/verificador exigiría separar sus
fases de validación y mutación/ACK. En la fase anterior no se amplió a ese rediseño;
se implementó y verificó primero una alternativa local:
reutilizar el registro autoritativo que ya acompaña al objeto exacto entregado por
el cursor del catálogo al writer, dentro de la misma operación. La comparación
existente sigue ejecutándose; si no hay una coincidencia inequívoca, usar el lookup
actual. Un único slot local, seguro entre threads, sin caché entre leases. El ordinal
debe venir del origen validado antes de encolar (nunca de la posición del consumidor)
o conservar su consulta actual; no debilitar detección de duplicados. No anticipar
objetos, tocar CRC/colas/ACK ni ampliar cotas. Exigir pruebas y revisión independiente
antes de otra candidata; si necesita un refactor amplio, detener también esta opción.

**Ampliación aprobada el 2026-10-26:** el usuario indicó «aplica el refactor si
crees que es la mejor solución». Se elige este refactor focal porque los lectores
siguen resolviendo metadatos uno a uno; no basta la mejora del objeto actual.
Separar explícitamente
validación y aplicación por lote en los tres lectores de checkpoint, en vez de
intentar insertar un buffer en sus bucles actuales. Alcance limitado a esos
lectores y su callback de metadatos; no rediseñar el input del writer ni cambiar
formatos, cuotas, autoridad, consentimiento o cadencia de almacenamiento. Resolver
y contrastar cada lote antes de mutar/ACK/truncar, respetar `cpByteEnd`, y conservar
el comportamiento escalar por defecto. Reutilizar fixtures para equivalencia,
metadata inválida con cola torn, cancelación y prefijo solapado; revisión
independiente obligatoria. Solo después reconstruir/auditar y probar conservando
progreso. La alternativa pequeña ya verificada se conserva; no se implementa ningún
rediseño del stream de entrada ni se debilitan garantías.

- Callback bulk opcional solo para lectores: claves únicas por lote, cardinalidad
  y correspondencia de resultados comprobadas, seguido del mismo contraste completo
  y detección global de ordinales repetidos. Ausencia del callback conserva el camino
  escalar, incluida la ruta con fuente/legacy.
- Separar admisión CRC/tipo y metadatos del lote de la aplicación de sus registros.
  Aplicar en el orden original; vaciar y validar pendientes antes de finalización,
  reparación/truncado o cambio de grupo. No ACK tras una excepción ni antes de las
  verificaciones criptográficas y bindings frescos originales. Respetar el límite
  congelado `cpByteEnd` incluso si el writer sigue añadiendo frames.
- Pruebas representativas de más de 256 frames, equivalencia escalar/bulk,
  metadatos inválidos con cola incompleta, cancelación y barreras de ACK del prefijo.
  Revisión independiente antes de reconstruir/auditar y volver a medir el recorrido.
- Se difiere compilar la instantánea pequeña C150 ya reconstruida (no instalada).
  La candidata del refactor tendrá una reconstrucción autenticada fresca y recibo
  propio; no se modifica ni reutiliza como si fuera actual el árbol congelado anterior.
- Implementación `fba5ac8` y revisión de captura concluidas: once focales únicos
  aprobados, incluidos 300 frames, cola incompleta/cancelación y barreras de ACK/
  `cpByteEnd`; fuentes reales cotejadas con las compiladas. No se repiten los checks
  ya verdes ni se presenta la revisión como acceso independiente al repositorio.
  C151 (`3ca861a`) identifica ambos cambios, con postimages exactas revisadas y
  cinco tests de reconstrucción/identidad aprobados. Reconstrucción/auditoría y
  medida física completa aún pendientes; no se declara mejora Android por estos tests.

- Registrar versión/hash, dispositivo/destino, build/catálogo, tamaño del prefijo,
  cachés frías/calientes, red compartida, objetos/tamaños, bytes útiles y reintentos.
  Artefactos/capturas fuera del repo; publicar solo resúmenes sin información privada.
- Casos representativos: instalación sin progreso previo, pausa/reanudación del mismo
  proceso, recreación de Activity con transferencia activa y cierre real/reinicio.
  Emplear fixtures para no borrar un cliente real; no equiparar sus tiempos con USB.
- Separar tiempo humano de cómputo: intención inicial → consentimiento, confirmación
  o Resume → primer byte nuevo y → progreso sostenido con commits; duraciones por
  fase, operaciones repetidas/omitidas y acciones del usuario. Un fetch aislado que
  llena la cola no acredita inicio sostenido.
- Medir preparación separada y ventanas de carga útil de diez minutos con subventanas
  de dos minutos cuando se pruebe throughput. Usar contadores exactos existentes;
  si solo hay UI redondeada, declarar cotas. No sumar relojes concurrentes/anidados.
- Comparar antes/después sobre fixture/entrada iguales para atribución; en teléfono
  documentar diferencias de cohorte/cache/red y repetir cuando impidan concluir.
  No reiniciar/resetear una descarga para fabricar un A/B. El objetivo histórico de
  3,2 MB/s es experimental, no requisito de velocidad universal.
- Aceptar solo con reducción demostrada del trabajo/latencia intervenido y sin
  regresión observada en consentimiento, recuperación o progreso sostenido. Informar
  magnitud e incertidumbre; no afirmar mejora total si solo mejora un subcomponente.
- Pruebas focales con infraestructura existente: pausa válida/coste reducido sin
  nueva confirmación, consentimiento obsoleto, destino/catálogo alterado, cancelación
  y fallos de recuperación preservadores. Ampliar solo el comportamiento tocado.
  Revisión independiente obligatoria para integridad/concurrencia/consentimiento.

## Implementation / Action Items

- [x] 1. Registrar objetivo e inspeccionar contratos, evidencia y puntos de entrada.
- [x] 2. Aprobar este plan y resolver decisiones que cambien garantías antes de código.
  Aprobados alcance local, ventana Android y candidata C147 local. No se autoriza
  modificar garantías ni aceptar costes desconocidos.
- [ ] 3. Reproducir recorrido y obtener línea base por fases/acciones con entradas
  controladas; usar instrumentación agregada existente antes de añadir otra.
- [x] 4. Elegir el cuello prioritario demostrado; documentar qué detecta cada chequeo,
  qué puede cambiar y qué guardas se mantienen. Si cambia contrato, parar y aprobarlo.
  Intervención local elegida: dimensionamiento inicial de la tabla temporal del
  inspector sin fuente con el conteo del catálogo ya admitido. El perfil histórico
  identifica rehashes; se exige comparación host actual antes de aceptar la mejora.
  Separadamente se corrige reentrada de pulsaciones y arranque foreground pendiente.
  No se elimina ninguna calificación, recorrido ni replanificación.
  Benchmark host actual de 300.000 objetos: cuatro rehashes con pista cero y
  ninguno con conteo exacto; resultados/callbacks idénticos. Véase el informe.
- [x] 5. Implementar mejora mínima y regresiones focales; revisar diff y evidencias.
  Optimización `c1bc7a1` y UI `633329e`; cuatro focales host y diez tests del servicio
  aprobados. La interacción física y el recorrido completo siguen pendientes.
- [x] 6. Revisar independientemente límites sensibles; reconstruir/auditar candidata
    solo si hace falta prueba física. No repetir checks exitosos sin cambio relevante.
  Review de algoritmo y concurrencia realizada. C147 reconstruida y auditada;
  instalada como actualización tras pausa confirmada, sin borrar datos. La revisión
  adicional del retag no pudo arrancar por variante de worker no disponible;
  identidad/pins y diff inspeccionados por el responsable, auditoría automática PASS.
- [ ] 7. Con autorización y estado fresco del dispositivo, medir antes/después sin
  perder progreso ni interferir con un juego/descarga activos; registrar limitaciones.
- [ ] 8. Reconciliar notas vigentes, revisar staged diff, commits atómicos solo de la
  tarea y estado final limpio; cerrar plan en `done/` solo con aceptación acreditada.

## Open Issues

- Prioridad reafirmada por el usuario: medir y reducir el intervalo completo entre
  la acción y la transferencia, no aceptar solo una función más rápida. Para el
  siguiente ajuste se conservarán las fases visibles y se atribuirá el coste con
  logs existentes/perfilado diagnóstico (marcado por su sobrecoste), antes de
  comparar de nuevo el recorrido sin perfilador. Sin APKs solo diagnósticas.
- Hipótesis concreta a reproducir: la membresía de cada frame de checkpoint hace
  consultas unitarias de catálogo y ordinal, repetidas en cada validación. Evaluar
  amortizarlas en bloques acotados del mismo catálogo/orden abierto e inmutable,
  sin omitir ninguna comprobación ni reutilizar confianza entre leases. Medir
  consultas/resultados y latencia antes de aceptar el ajuste; preservar cancelación,
  rechazo de claves/manifiestos ajenos y concurrencia del verificador/escritor.
- C148 validó físicamente Resume con una sola acción, sin otra confirmación, pero
  tardó 1.453,953 s hasta transferencia visible (Descargar: 948,398 s). La carga de
  checkpoint y otras esperas siguen dominando; no se acepta el objetivo global por
  eliminar rehashes del inspector. Diagnosticar el recorrido restante con las
  mediciones retenidas antes de elegir otra intervención.
- La reconexión UI quedó incompleta por una expectativa incorrecta del guion:
  Home activo muestra «Ver descarga», no «Descargar cliente». Se conservó el error
  original y se pausó cooperativamente a las 21:04:24 UTC; Home confirmó pausa y
  cero servicios updater activos. No repetir pruebas terminadas para corregir la
  evidencia ni declarar fallo del producto por esa aserción.

- «Restaurar» se trata inicialmente como reanudar una descarga interrumpida,
  distinguiendo pausa del mismo proceso y reapertura tras cierre; no incluye
  restaurar copias de seguridad ni persistir consentimiento entre procesos.
- Paso 3 parcialmente ejecutado; paso 4 respaldado ya por benchmark host actual:
  [informe de diagnóstico](../../docs/download-resume-baseline-review.md).
  Al iniciar esta fase no se habían modificado código/specs, ejecutado suites ni
  operado dispositivos. La reproducción local ya está autorizada;
  no se da por completo el recorrido del paso 3 con una inspección estática.
- Al aprobar el alcance local no se habían comprobado acceso/estado del teléfono.
  La aprobación del plan no implica
  permiso para detener una partida o transferencia, instalar, reiniciar ni confirmar
   costes desconocidos; coordinar la ventana física antes de esas operaciones.
- Control físico: el intento ADB input fue rechazado por `INJECT_EVENTS`; scrcpy
  UHID ya permitió pausa normal sin cambiar seguridad. Reanudación C146 única a
  17:35:31 UTC, observación acotada sin fase visible de transferencia y pausa final
  a 17:56:10 UTC; estado final «En pausa». Dos inspecciones actuales: 223,012 y
  166,107 s, con seis rehashes cada una (41,579 y 26,521 s dentro de esos totales).
  No sumar estos relojes anidados ni atribuir el intervalo entero a esas tablas.
  No hay aún primer byte medido. Primera preparación C147: una acción «Continuar»,
  respuesta visible antes de 2,722 s, resumen a 509,206 s; inspector 179,133 s y cero
  rehashes. Está entre ambas duraciones C146: confirma menos trabajo, no una mejora
  causal del tiempo total. Proceso fresco C147 y Resume vivo C146 no son A/B.
- C147 local instalada: `fff17ac`, 56 tests de reconstrucción/auditoría aprobados,
  build ModernDebug offline SUCCESS y auditoría fuente/payload PASS. Entre receipts
  C146/C147 solo cambian tres fuentes productivas, dos tests, versión y marcador de
  procedencia. No se cambian los inputs históricos ni sus pins. Antes de actualizar:
  «En pausa», sin servicio updater ni ejecutable guest observado; `adb install -r`
  devolvió Success, versión instalada 147. El bloqueo de apertura manual quedó
  obsoleto tras actualizar las reglas de control. En la continuación aprobada ya se
  abrió la actividad directamente mediante ADB: Home muestra «Cliente no instalado»
  y «Descargar cliente», sin ejecutar juego. Esto no prueba pérdida del checkpoint
  de una instalación aún no publicada; verificar el recorrido antes de transferir.
- Se acredita reducción de trabajo del inspector host (commit `c1bc7a1`, 4/4
  regresiones focales) y cero rehashes en el inspector C147 físico; no reducción
  global de latencia Android. La compilación integrada
  Modern y los diez tests del servicio pasaron con la fuente final de UI y updater
  (coincidencia comprobada por `cmp`). La copia parcial Legacy seguía incompleta;
  los focales de capacidad y costes del árbol Modern también pasaron (un test por
  clase, cero fallos/errores/omitidos, XML 2026-10-25T16:52:04), sin atribuir los
  problemas de staging al producto. La UI está versionada en `633329e`.
- El usuario autorizó «Confirmar y probar» el plan visible C147: Unidad USB,
  `1.60.1.70532`, 63,0 GiB pendientes y 65,2 GiB adicionales. Ejecución y una
  reanudación con observación acotada, pausa final y progreso conservado; no dejar
  descargando el cliente entero ni aceptar costes distintos por esa autorización.
- Ejecución física C147: una confirmación → transferencia visible a +984,696 s;
  pausa cooperativa, checkpoint recuperado y commit final de 128 objetos. Resume
  único → a +526,058 s volvió a pedir «Descargar» (62,9/65,1 GiB). No se aceptó otra
  confirmación. Diagnosticar la condición exacta de `canResume`: los presupuestos
  privados/destino se comparan separadamente y no basta un pico combinado menor
  para demostrar que el consentimiento sigue válido. No relajar la guarda.
- Diagnóstico confirmado por lectura de argumentos en C147 debug: espacio válido,
  misma revisión y mismo `planId`. El presupuesto privado pasa de 1.831.494.496 a
  1.835.889.466 bytes (+4.394.970); descarga, destino y pico bajan. La guarda hace
  bien en rechazarlo. La corrección local conserva esa guarda y el cálculo actual
  de espacio libre: separar del coste consentido los créditos de ocupación privada
  transitoria para que retirar raws/scratch propios no eleve su límite consentido.
  Usar una cota conservadora estable en el camino sin fuente, como ya se hace con
  la reserva de orden de archivo, dejando el camino con fuente intacto. Reproducir
  primero con un parcial sintético escaso, luego probar límites/espacio y mostrar
  las cotas por volumen exigidas por AC-02. Sin formato/recibo nuevo, sin persistir
  consentimiento y sin una segunda máquina de estados. Si exige cambiar una
  garantía o el comportamiento aprobado, parar para decisión en vez de ampliarlo.
- Reparación `5516cf6`: cinco focales PASS, fuentes compiladas cotejadas y revisión
  independiente GPT-6 Luna/OpenAI sin hallazgos (modelo autorizado por el usuario).
  C148 local / `0.2.124-download-resume-test` verificará esta corrección: identidad
  diferenciada, reconstrucción autenticada, auditoría, actualización conservando
  datos y prueba acotada. No repetir suites verdes por un retag ni publicar. La
  autorización del dispositivo cubre la actualización; confirmar los costes reales
  antes de iniciar payload si necesitan un consentimiento nuevo.
- C148 reconstruida/autenticada, compilación Modern y auditoría del APK exacto
  aprobadas; actualización `install -r` verificada, Home disponible 19:57:58 UTC.
  Comprobación previa sin updater/juego activo; no se borró progreso. Preparación
  de C148 medida: Resume válido de una acción, sin reconfirmar, pero hasta 24 minutos
  hasta transferencia visible. Reconexión completa aún pendiente: no cerrar por
  la compilación o por el ahorro del inspector. Artefactos fuera del repositorio.
- Perfil C148 actual: en una ventana diagnóstica de 60,155 s, `checkpointMembership`
  acumula 44,435 s muestreados y sus consultas individuales de catálogo/ordinal
  39,750 s incluidos (~89,5 %). Lecturas pequeñas: 1,708 s. Muestreo 10 ms sin
  overflow; no es un A/B ni el tiempo completo de fase. Priorizar lotes de hasta
  256, sin quitar validaciones individuales, duplicados globales, cancelación ni
  la comprobación independiente posterior. No optimizar por hipótesis las lecturas
  ni cambiar políticas de confianza. Regresión/revisión y medida física total
  siguen siendo necesarias; carga del escritor y otros preludios permanecen.
- Consentimiento persistente tras muerte del proceso y pausa viva son cambios de
  producto fuera del primer alcance. Si resultan necesarios, presentar decisión;
  conservar checkpoint no autoriza a descargar automáticamente.
- C149 contiene `a8f086d`: lotes <=256, regresiones focales y revisión aprobadas;
  reconstrucción/build final y auditoría fuente/payload PASS, actualización local
  verificada sin reset. Primera preparación única: respuesta +2,963 s, inspector
  178,687 s desde +113,067 s, resumen +440,830 s. Plan visible 70532/Unidad USB,
  descarga 62,8 GiB y adicional 65,0 GiB (app/cliente 1,7/63,2). El usuario respondió
  «Confirmar y medir». Ensayo terminado: Descargar 860,836 s y Resume 1.225,989 s,
  una acción cada uno; reapertura con mismo proceso/servicio y sin reconfirmar.
  Pausa final 23:06:25 UTC, después cero servicios activos. No repetir esa prueba.
  Se observan 88/228 s menos que C148, con caché/cohorte sin controlar; 14/20 minutos
  siguen excesivos y no son aceptación global. La alternativa pequeña ya se validó;
  el 2026-10-26 se aprobó además el refactor focal de lectores descrito arriba,
  conservando las correcciones y pruebas anteriores.
- Alternativa pequeña `c823708`: reutilización de la fila autoritativa y su ordinal
  previo a filtros, con fallback intacto. Se reprodujo y corrigió una clasificación
  desplazada; tres focales legacy/cancelación/solapamiento pasan con fuentes finales
  cotejadas. Revisión independiente de la captura final sin hallazgos, sin atribuirle
  acceso al repositorio ni ejecución de tests. La candidata combinada C151 medirá el
  efecto completo tras reconstrucción/auditoría. La instantánea pequeña C150 se
  conserva sin compilar; tras la nueva aprobación se integrará el refactor de
  lectores con un recibo fresco. Todavía no hay una mejora física de esos cambios.
- La extensión de lotes a otros lectores requiere preservar CRC/layout/ordinales,
  tail recuperable y límite congelado `cpByteEnd`. No adelantar el stream de entrada
  del escritor sin analizar backpressure de las cotas raw: no es un cambio aprobado
  por el mero resultado del perfil. Elegir el siguiente ajuste solo con evidencia
  del recorrido restante y sin eliminar guardas ni añadir confianza entre leases.
