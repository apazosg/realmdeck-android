---
id: adb-reliability-2026-10-30
state: draft
date: 2026-10-30
---

## Summary

El usuario necesita control fiable del móvil de desarrollo y una forma de recuperar
ADB Wi-Fi cuando se desactive, sin depender de facilitar reiteradamente un puerto.
Esta investigación complementa el trabajo USB de `usb-cross-phone-plan.md`: no
reanuda, cancela ni reinicia por sí misma la revisión en curso.

## Evidencia y límites

- El teléfono documentado usa Android 16/HyperOS, sin root, y ADB TLS por mDNS con
  puertos efímeros. El host usa platform-tools 37.0.1; `adb server-status` informó
  `mdns_enabled: true` y `mdns_backend: LIBADBMDNS`.
- A las 07:55 UTC el servidor ADB no tenía dispositivos registrados. La dirección
  anterior respondía a ICMP, pero el puerto ADB anterior rechazaba TCP. Poco después,
  mDNS mostraba un servicio TLS en esa dirección con un puerto distinto; ese
  puerto tampoco aceptaba TCP. El anuncio podría estar desactualizado o no ser
  alcanzable: no demuestra que el servicio esté activo ni identifica por sí solo
  al teléfono. La causa y el estado del interruptor se desconocen.
- El controlador de la revisión C168 carga una sola vez el endpoint en
  `review_after_network_recovery.py:66`. Tras ver progreso, conserva las
  observaciones no disponibles hasta su plazo global. No puede adaptarse al
  cambio de endpoint ni avisó a tiempo de que había perdido visibilidad.
- scrcpy usa ADB para iniciar su servidor y canal de control: no es un canal
  independiente para reactivar ADB. El host no mostraba adaptadores Bluetooth ni
  dispositivos de vídeo en `/sys/class` durante la inspección.
- La [documentación oficial de ADB](https://developer.android.com/tools/adb)
  describe el descubrimiento y la reconexión de dispositivos emparejados por
  mDNS. Eso puede recuperar una conexión cuando el servicio vuelve a anunciarse;
  no reactiva por sí mismo un interruptor de depuración apagado.
- La solución externa `adb-auto-enable` declara usar `WRITE_SECURE_SETTINGS`, un
  servicio web y ADB TCP persistente; esas concesiones contradicen los límites
  actuales. No instalarla ni asumir que funciona en este teléfono. No usar root,
  permisos amplios, accesibilidad privilegiada, escuchas remotas nuevas ni cambios
  de seguridad sin una decisión expresa que modifique esos límites.

## Implementation / Action Items

- [ ] 1. Recuperar acceso a la revisión existente sin relanzarla. Si el usuario
  debe intervenir, pedir que compruebe y reactive una sola vez la depuración
  inalámbrica; no desconectar el USB. Usar mDNS solo para localizar el dispositivo
  ya autorizado y comprobar su identidad antes de controlar UI. El controlador
  actual sigue fijado al endpoint anterior incluso si aparece otro.
- [ ] 2. Registrar de forma acotada el estado real del servicio ADB, conectividad,
  identidad, versión y mensajes relevantes del sistema tras recuperar acceso;
  redactar identificadores y contenido de otras apps. Distinguir desconexión,
  cambio de puerto, interruptor apagado y pérdida de red; no atribuir la causa a
  una hipótesis sin evidencia.
- [ ] 3. Diseñar y, tras resolver las decisiones necesarias, incorporar al flujo
  real de control reconexión limitada por mDNS al dispositivo emparejado y validado,
  selección por identidad inequívoca, detección rápida de pérdida y notificación
  explícita de estado incierto. No crear un helper aislado ni reintentos de UI
  ciegos; no afirmar que una operación del teléfono se detuvo al perder ADB.
- [ ] 4. Investigar un canal de recuperación realmente independiente para un ADB
  desactivado, empezando por opciones sin nuevos privilegios ni exposición de red y
  verificando la compatibilidad con el USB conectado. Documentar lo que requiera
  intervención manual, hardware adicional o modificación de seguridad; no asumir
  que un hub USB convencional permite a la vez los roles host y dispositivo.
- [ ] 5. Con el teléfono en reposo y sin comprometer la fixture, comprobar en el
  dispositivo la reconexión tras interrupción/cambio de puerto y el aviso cuando
  no sea posible recuperar control. Si se aprueba un canal independiente, probar
  también la reactivación real. No declarar resuelto el requisito de reactivación
  automática sin esa prueba física.
- [ ] 6. Volver a las pruebas USB aprobadas, conservando el consentimiento fresco,
  exclusión y demás criterios del plan original; mantener cualquier limitación
  pendiente en la evidencia.

## Open Issues

- ¿Por qué se perdió ADB después de días de funcionamiento? No hay logs suficientes
  para distinguir los casos; tampoco se sabe si el interruptor está apagado ahora.
- No se ha identificado un canal independiente existente que pueda encenderlo sin
  interacción. La reactivación automática no está demostrada y puede necesitar
  hardware o una excepción de seguridad que el usuario debe decidir expresamente.
