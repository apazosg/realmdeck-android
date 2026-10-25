---
id: download-resume-simplification-2026-10-25
state: draft
date: 2026-10-25
---

## Summary

**Límite vigente de esta sesión — observación pasiva:** el usuario reanuda el
goal únicamente para pasos 3/4 con evidencia existente y logs accesibles en lectura.
No pausar/reanudar descarga, operar app, instalar/reiniciar/borrar ni cambiar código,
contratos o garantías. Si falta línea base de inicio por requerir interacción,
documentar el bloqueo y detenerse sin pedir más permiso. Mantener `draft` y no
cerrar goal sin aceptación medida. Las aprobaciones locales y resultados descritos
abajo son antecedentes de trabajo intervenido mientras esta sesión estaba pausada,
no permiso para continuar esa implementación en este turno. Cambios locales previos
en Management y su test se conservan sin tocar.

Resultado de lectura pasiva:
[observación del log retenido](../../docs/download-passive-log-observation.md).
Se mide la transferencia en curso, no el tiempo desde intención a primer byte;
paso 3 sigue incompleto sin esas marcas. No se inicia otra prueba para obtenerlas.

Objetivo solicitado: simplificar descarga y reanudación, reducir interacciones
repetidas y tiempo hasta transferencia real, revisar comprobaciones previas sin
debilitar las garantías acordadas y resolver cuellos con medidas antes/después.
Este plan continúa la investigación existente; no reabre la publicación C146 ni
autoriza otra entrega pública. **Aprobación vigente (2026-10-25):** el usuario
eligió «Aprobar trabajo local» y confirmó «adelante». Se autorizan diagnóstico,
cambios mínimos de código/UI y pruebas locales (pasos 2–6 y documentación del 8),
sin operar el teléfono ni publicar una APK. Los cambios de garantías/contrato
siguen requiriendo propuesta y aprobación explícitas.

Ampliación autorizada: el usuario permite pruebas locales y con Android mediante
ADB Wi-Fi y, al detectarse servicio foreground activo en C146, eligió «Observar y
pausar normalmente»: leer progreso, pausa cooperativa por UI y medir reanudación
de la versión instalada. No autoriza todavía sustituir la app con una candidata.

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

### Ventana física propuesta — pendiente de autorización separada

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

### Medición y aceptación

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
  Aprobado el alcance local; no se autoriza modificar garantías ni operar el móvil.
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
  Review de algoritmo y concurrencia realizada; no se ha preparado una APK.
- [ ] 7. Con autorización y estado fresco del dispositivo, medir antes/después sin
  perder progreso ni interferir con un juego/descarga activos; registrar limitaciones.
- [ ] 8. Reconciliar notas vigentes, revisar staged diff, commits atómicos solo de la
  tarea y estado final limpio; cerrar plan en `done/` solo con aceptación acreditada.

## Open Issues

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
- Actualización física autorizada: ADB Wi-Fi disponible, C146 con descarga activa.
  UI 17:26–17:27 UTC: 8.0 GiB/71.4 GiB y 0.3 MB/s redondeados. Un único intento de
  pulsar «Pausar» fue rechazado por Android (`INJECT_EVENTS`); no se ha pausado ni
  instalado nada. Se requiere pulsación manual para continuar esa medición.
- Se acredita reducción de trabajo del inspector host (commit `c1bc7a1`, 4/4
  regresiones focales); no reducción global ni física. La compilación integrada
  Modern y los diez tests del servicio pasaron con la fuente final de UI y updater
  (coincidencia comprobada por `cmp`). La copia parcial Legacy seguía incompleta;
  los focales de capacidad y costes del árbol Modern también pasaron (un test por
  clase, cero fallos/errores/omitidos, XML 2026-10-25T16:52:04), sin atribuir los
  problemas de staging al producto. La UI está versionada en `633329e`.
- Consentimiento persistente tras muerte del proceso y pausa viva son cambios de
  producto fuera del primer alcance. Si resultan necesarios, presentar decisión;
  conservar checkpoint no autoriza a descargar automáticamente.
