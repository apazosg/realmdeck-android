---
id: diagnostics-usability-2026-10-21
state: draft
date: 2026-10-21
---

## Summary

El usuario solicita mejorar mucho los diagnósticos: demasiado texto, espera
excesiva y comentario obligatorio. Propone una experiencia realmente utilizable.
Este plan propone el alcance; no autoriza aún cambios de comportamiento ni envíos.

## Content

### Evidencia de la incidencia actual

- ADB Wi-Fi reconectado a xaga/22041216G en `192.168.1.130:36937`.
- Los dos logs PoC recientes registran `display_plan_applied` seguido de
  `launch_blocked`: `recover the unfinished client operation before launching the guest`.
- `beginGuest()` comprueba primero quiescencia y después rechaza el registro no IDLE:
  la causa acreditada es la operación durable pendiente, no un juego vivo demostrado.
- El registro conserva `GUEST_STARTED`, observación histórica `UNKNOWN` y motivo
  `proc_exe_unreadable`. No se imprimen ni copian su token ni datos privados.
- Actualmente solo aparecen el launcher y su lector logcat bajo UID10330; no Wine.
- El borrador enviado tiene `reportId` no vacío. Esto acredita un recibo local de
  envío, no una consulta independiente del backend. No se reenvía nada.
- `latest-intent.json` conserva `result=cancelled, stage=display, error=null`,
  mientras el PoC sí conserva la causa original. Investigar por qué el cierre del
  flujo pierde la causa y si la información útil llega al payload enviado.

### Propuesta de experiencia y aceptación

1. Diálogo corto: una explicación breve de qué se envía, comentario opcional,
   acciones Enviar/Cancelar y detalles técnicos plegados. Sin envío automático ni
   eliminación de consentimiento o saneado de secretos.
2. Preparación y envío fuera de UI; respuesta visual inmediata, etapas reales,
   cancelación y tiempo de espera acotado. Medir antes de atribuir la demora a red,
   recopilación o procesamiento. No prometer envío rápido si el servidor no responde.
3. Reutilizar datos ya capturados y recopilar solo datos acotados necesarios para
   el fallo: intento/versión, etapa, causa original, estado del runtime y operación
   pendiente. Sin escaneos del cliente, credenciales, volcado de memoria o capturas
   automáticas. No añadir plataforma de telemetría ni nuevas dependencias.
4. No reemplazar un fallo original por `cancelled` al cerrar un diálogo; distinguir
   cancelación del envío, del lanzamiento y error previo. Un informe debe permitir
   reconstruir lo sucedido sin pedir al usuario que interprete logs.
5. Mensaje de error accionable y fiel: distinguir juego activo, comprobación no
   concluyente y sesión anterior pendiente de recuperar. No pedir cerrar WoW si
   solo se ha acreditado una reserva vieja; no quitar guardas para mejorar la UX.
6. Confirmación corta de envío y referencia del informe; fallos con Reintentar
   reutilizando la idempotencia existente y sin duplicar informes.

### Impacto en especificaciones

US-004: presentación/errores del launcher. US-003: diagnóstico persistente si
corresponde a su ruta, sin mezclar contratos de apps distintas. SYSTEM: privacidad
intacta. Las especificaciones actuales no describen de forma completa esta UX de
envío remoto; proponer UC/AC autocontenidos para este comportamiento antes de
derivar FR/NFR/TC e implementar. US-005 solo cubre informe GPU mínimo, no sustituye
el contrato de envío de incidencias del launcher.

## Implementation / Action Items

- [ ] 1. Aprobar alcance/UC-AC propuestos; inspeccionar recopilación, comentario y
  tiempos reales sin enviar nuevos informes.
- [ ] 2. Concretar especificación afectada, trazabilidad y límites de tiempo con
  evidencia; obtener las aprobaciones de requisitos necesarias.
- [ ] 3. Implementar cambios mínimos en diálogo, captura/resultado y mensajes;
  conservar transporte, idempotencia, privacidad y exclusión existentes.
- [ ] 4. Regresiones focales para comentario vacío, causa preservada, cancelación y
  reintento; revisión independiente de privacidad, concurrencia y envío.
- [ ] 5. Compilar/auditar y coordinar instalación; comprobar UX física y un envío
  solo con consentimiento. Documentar tiempos medidos y límites.

## Open Issues

- Pendiente aprobación del usuario del alcance anterior.
- Recuperar la reserva que bloquea Jugar es una operación separada: no se modifica
  el registro, no se inicia el juego ni se invoca recuperación con este plan.
- Identificar el coste dominante antes de fijar un objetivo numérico verificable;
  nunca usar un temporizador para declarar éxito o saltarse saneado.
