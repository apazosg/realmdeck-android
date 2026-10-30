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
El usuario pidió: «antes de continuar intentemos que sea lo más fiable posible la
conexión». Esto no autoriza permisos amplios ni cambios de seguridad.

## Evidencia y límites

- El teléfono de la prueba C168 respondió Android 14 (API 34), sin root en el
  procedimiento, y ADB TLS por mDNS con puertos efímeros. El documento del POCO
  M7 Pro con Android 16 describe otro dispositivo y no debe confundirse con éste.
  El host usa platform-tools 37.0.1; `adb server-status` informó
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
- Tras indicar el usuario que el teléfono volvía a estar disponible, ADB presentó
  dos alias mDNS para la misma identidad física; ésta coincidió con `ro.serialno`,
  el modelo esperado y la app C168. La identidad se guardó en un archivo gitignored
  privado con modo 0600; el destino operativo quedó apuntado a un alias emparejado.
  Una comprobación de aproximadamente diez segundos hizo seis lecturas con
  identidad verificada: 6/6 pasaron, sin acciones UI. Es una muestra corta, no una
  prueba de estabilidad durante horas ni de reactivación automática. El servicio
  updater no estaba activo, pero la app no estaba en primer plano y la revisión
  anterior sigue sin estado UI confirmado.
- Una observación pasiva posterior de 120,1 s realizó 25 lecturas ADB del serial
  fijado: 25/25 correctas, con dos alias conectados durante toda la muestra,
  mediana de 89 ms y máximo de 197 ms. No se enviaron acciones UI ni se cambiaron
  ajustes o desconectó ADB. El intervalo no prueba funcionamiento prolongado ni
  recuperación real tras una interrupción. Ambos alias se verificaron por separado
  contra el serial fijado; comparten teléfono, Wi-Fi y servidor ADB y no constituyen
  redundancia independiente.
- El log acotado de ADB del dispositivo contiene a las 06:57:39 UTC un evento de
  cambio de red Wi-Fi seguido de tres desconexiones TLS, siete segundos después de
  la última pantalla visible. No registra en esa ventana que la depuración se
  desactivara ni demuestra por qué cambió la red. La instantánea posterior mostró
  Wi-Fi de 5 GHz con RSSI −40 dBm, pantalla despierta y alimentación de CA; no
  demuestra cómo estaban esas condiciones en el momento de la caída.
- El servidor ADB activo usa el valor por defecto que permite autoconexión TLS.
  `adb track-devices -l` y `adb mdns track-services --proto-text` proporcionan
  flujos de eventos en esta instalación; ambos emitieron información de la identidad
  fijada durante una observación breve, aunque el segundo no aparece en la ayuda
  resumida. Se observan dos anuncios de la identidad fijada, uno en la dirección
  anterior y al menos uno con puerto diferente; un anuncio no demuestra por sí solo
  que acepte conexiones. Ninguna observación demuestra recuperación tras una caída.
- La lista actual de ajustes rápidos no muestra una baldosa con nombre de depuración
  inalámbrica; la consulta de servicios tampoco la identificó. Esto no demuestra
  que el fabricante no la ofrezca: puede estar deshabilitada o usar otro nombre.
  [Android documenta](https://developer.android.com/tools/adb) una baldosa de
  desarrollador en dispositivos compatibles, pero no se ha probado en éste ni se han
  modificado ajustes para buscarla o activarla.
- scrcpy usa ADB para iniciar su servidor y canal de control: no es un canal
  independiente para reactivar ADB. El host no mostraba adaptadores Bluetooth ni
  dispositivos de vídeo en `/sys/class` durante la inspección.
- La [documentación oficial de ADB](https://developer.android.com/tools/adb)
  describe el descubrimiento y la reconexión de dispositivos emparejados por
  mDNS. Eso puede recuperar una conexión cuando el servicio vuelve a anunciarse;
  no reactiva por sí mismo un interruptor de depuración apagado.
- Si la depuración está apagada, el camino disponible sin nuevos privilegios es
  que una persona use la pantalla del teléfono para reactivarla y después mDNS
  redescubra el servicio emparejado. Puede hacerse con el USB conectado, pero aquí
  aún no se ha demostrado. Un teclado Bluetooth previamente emparejado podría
  facilitar la interacción sin usar el puerto USB; el host no tiene adaptador
  detectado y no se ha validado ningún periférico. Cable ADB o scrcpy OTG requerirían
  cambiar el rol del puerto y posiblemente retirar el almacenamiento: no hacerlo
  mientras el estado de la revisión y del USB sea incierto. Un hub ordinario no
  prueba que puedan coexistir ADB por cable y el teléfono como host del USB.
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
  explícita de estado incierto. Revalidar identidad antes de cualquier control y
  cerrar el canal UHID obsoleto tras una pérdida; nunca repetir una pulsación cuyo
  resultado sea incierto. Tras reconectar, observar antes de decidir cualquier acción.
  No modificar ni ejecutar el controlador histórico ya cerrado; no crear un helper
  aislado ni afirmar que una operación del teléfono se detuvo al perder ADB.
- [ ] 4. Investigar un canal de recuperación realmente independiente para un ADB
  desactivado, empezando por opciones sin nuevos privilegios ni exposición de red y
  verificando la compatibilidad con el USB conectado. Documentar lo que requiera
  intervención manual, hardware adicional o modificación de seguridad; no asumir
  que un hub USB convencional permite a la vez los roles host y dispositivo.
- [ ] 5. Probar primero el flujo con casos simulados focalizados: cambio de puerto,
  alias duplicados, identidad inesperada, pérdida antes/durante una pulsación y
  ausencia de reintentos de una acción incierta. Con el teléfono en reposo y sin
  comprometer la fixture, comprobar físicamente la reconexión y el aviso de pérdida,
  sin cambiar ajustes de seguridad. Si se aprueba un canal independiente, probar
  también la reactivación real. No declarar resuelto el requisito de reactivación
  automática sin esa prueba física.
- [ ] 6. Volver a las pruebas USB aprobadas, conservando el consentimiento fresco,
  exclusión y demás criterios del plan original; mantener cualquier limitación
  pendiente en la evidencia.

## Open Issues

- La pérdida coincidió con un cambio de red y desconexiones TLS; falta establecer
  por qué cambió la red y si hubo cambio de puerto o apagado de la depuración. Al
  recuperar acceso, el ajuste de depuración Wi-Fi se leyó activado.
- No se ha identificado un canal independiente existente que pueda encenderlo sin
  interacción. La reactivación automática no está demostrada y puede necesitar
  hardware o una excepción de seguridad que el usuario debe decidir expresamente.
