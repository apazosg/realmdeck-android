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
conexión». Esa petición no autorizaba permisos amplios ni cambios de seguridad.
Después, tras proponer MacroDroid y explicar el permiso amplio
`WRITE_SECURE_SETTINGS`, pidió expresamente: «la instalamos y activamos?». La
autorización se limita a instalar esa app, concederle ese permiso y configurar una
automatización de recuperación; no aprueba el resto del plan ni otros cambios de
seguridad.

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
- MacroDroid, publicado por ArloSoft, permite escribir el ajuste global
  `adb_wifi_enabled` con el permiso antes mencionado. Hay una macro comunitaria que
  lo revisa periódicamente, pero se prefiere configurar y revisar una sola macro
  propia en vez de importar acciones de terceros sin inspección. La autorización no
  incluye root, accesibilidad, puerto ADB adicional, servidor web, ni modificar el
  USB. Restringirla a una red conocida si puede hacerse sin permisos adicionales no
  aprobados; una coincidencia de SSID por sí sola no autentica la red. Si la revisión
  USB sigue sin estado conocido, no apagar ADB ni simular una caída para probarla.
- Instalación física del 30-10: Google Play proponía instalar también en otro
  dispositivo de la cuenta. Se desmarcó ese destino, se verificó que solo el teléfono
  de desarrollo estaba seleccionado y se envió una única acción de instalación.
  El gestor de paquetes confirma `com.arlosoft.macrodroid` 6.1.5 con Google Play
  como instalador. No se compró la versión de pago ni se importaron plantillas.
  La app se abrió; su interruptor general figura activado, pero no se ha creado
  ninguna macro de recuperación ni se ha concedido `WRITE_SECURE_SETTINGS`. La
  notificación de la app tampoco figura concedida. El USB no se manipuló y la
  revisión anterior sigue sin estado confirmado.
- La [documentación de la condición Wi-Fi de MacroDroid](https://wiki.macrodroid.com/wiki/index.php/Constraint:_Wifi_State)
  indica que precisa permiso de ubicación «permitir siempre» para consultar el estado
  o la red en segundo plano. En la comprobación actual la ubicación del dispositivo
  ya estaba activada, pero la app no tenía ubicación precisa ni de fondo. Concederla
  sería una decisión adicional de privacidad y no está comprendida en la autorización
  anterior. Todavía no se ha verificado la condición física ni si intervienen otros
  permisos o restricciones de HyperOS. Se mantiene sin conceder también el permiso
  amplio ya autorizado hasta poder configurar la guarda de red.
- Tras explicar que el permiso permitiría a la app acceder a la ubicación precisa
  también en segundo plano, el usuario eligió «Permitir ubicación». Esto autoriza
  los permisos de ubicación necesarios para la guarda; no autoriza automáticamente
  accesibilidad, nuevos puertos, excepciones de batería ni otros permisos distintos.
  La autorización no demuestra que el SSID identifique de forma segura una red.
- La concesión inicial por `pm grant` fue rechazada con `SecurityException` por el
  teléfono; no se modificó la seguridad de depuración. Usando los ajustes normales
  de Android se seleccionó una vez «Permitir siempre» para MacroDroid y el sistema
  mostró «Ubicación precisa» activada. `dumpsys package` confirmó ubicación gruesa,
  precisa y de fondo concedidas. `WRITE_SECURE_SETTINGS` seguía sin conceder y la
  macro todavía no se había configurado; reactivación y guarda siguen sin probar.
- En el editor se preparó, sin guardar todavía una macro, la conjunción de dos
  restricciones: Wi-Fi conectada al SSID exacto de la red actualmente conectada y
  `Global(adb_wifi_enabled) != 1` como entero. Se comprobó que ese SSID coincidía
  con el leído de la conexión actual; no se versiona y no autentica la red. El editor
  muestra dos restricciones, ningún disparador y ninguna acción. Esto no prueba que
  la guarda funcione en segundo plano ni que la app pueda reactivar ADB.
- El disparador «Intervalo regular» solicitó permiso para alarmas exactas antes de
  permitir configurarlo; se canceló el aviso sin concederlo. Al intentar añadir la
  acción «Configuración del sistema», la app solicitó además permitirle modificar
  configuraciones de sistema; también se canceló el aviso. Es un acceso adicional
  distinto de `WRITE_SECURE_SETTINGS`, que el usuario ya había autorizado. La consulta
  posterior mostró `WRITE_SETTINGS` sin conceder y su app-op en `ignore`,
  `SCHEDULE_EXACT_ALARM` sin conceder y su app-op en `default`; no se concedió
  tampoco `WRITE_SECURE_SETTINGS`. El interruptor de depuración inalámbrica seguía
  activado. Se dejó el borrador visible y se cerró el control UHID temporal.
- Al abrir el editor apareció un diálogo publicitario sobre compartir datos; se
  seleccionó «Manage options», no «Consent», y el diálogo desapareció después. Se
  desconoce qué estado de consentimiento publicitario quedó guardado; no se
  considera probado ni el rechazo ni la aceptación de ese tratamiento.
- Tras informar de los dos permisos adicionales, de sus efectos y de la incertidumbre
  publicitaria, el usuario eligió «Permitir ambos»: autoriza el acceso de MacroDroid
  a modificar ajustes del sistema y a alarmas exactas para la revisión periódica.
  Esta autorización no amplía los límites sobre root, accesibilidad, otros permisos,
  nuevos puertos, el USB ni la prueba de caída con la revisión todavía incierta.
- Se establecieron solo para MacroDroid en el usuario Android 0 los app-ops
  `WRITE_SETTINGS` y `SCHEDULE_EXACT_ALARM` en `allow` y se verificaron. En el editor,
  aún sin guardar, figura un intervalo fijo de cinco minutos con «Usar alarma», una
  única acción entera `[Global] adb_wifi_enabled = 1` sin aplicación auxiliar y la
  conjunción de las dos restricciones descritas arriba. Tras cerrar el control UHID,
  el editor y sus contenidos seguían visibles. No hay macro guardada ni activada.
- Se intentó conceder el `WRITE_SECURE_SETTINGS` previamente autorizado y Android lo
  rechazó. Una segunda ejecución, únicamente para conservar el diagnóstico exacto,
  mostró `SecurityException: grantRuntimePermission: Neither user 2000 nor current
  process has android.permission.GRANT_RUNTIME_PERMISSIONS`; el paquete seguía sin
  ese permiso. La propiedad leída `persist.security.adbinput` era `0`: referencias de
  terceros sobre MIUI/HyperOS la relacionan con «Depuración USB (ajustes de seguridad)»,
  pero no se ha confirmado el interruptor en la UI ni demostrado que cambiarlo lo resuelva.
  No se modificó esa opción ni se utilizó root; la acción aún muestra la advertencia
  de permiso. La depuración inalámbrica seguía activada. Evidencia del diagnóstico
  privada en `/tmp/opencode/macro-secure-grant-diagnostic.log`.
- Tras explicar que la opción de Xiaomi amplía temporalmente las facultades de los
  equipos ADB autorizados y que puede no resolver el bloqueo, el usuario eligió
  «Permitir temporalmente»: se autoriza intentar habilitar solo «Depuración USB
  (ajustes de seguridad)» para conceder el permiso a MacroDroid y restaurar y
  verificar inmediatamente el estado anterior. Si exige cuenta, SIM u otro cambio,
  detenerse; no crear cuentas, usar root ni cambiar opciones ajenas. Esta autorización
  no convierte la recuperación automática en probada ni autoriza probar una caída.
- Se comprobó en los ajustes que la opción estaba apagada y se hizo un único intento
  de activación, atravesando los avisos de Xiaomi. La respuesta registrada por su
  componente fue `Insert your SIM card`; la opción continuó apagada tanto en la UI
  como en `persist.security.adbinput=0`. Se detuvo el intento sin insertar ninguna
  SIM ni cambiar otra opción, y no se intentó de nuevo la concesión protegida. La app
  seguía sin `WRITE_SECURE_SETTINGS`, Wi-Fi ADB seguía encendido y los dos app-ops
  autorizados (`WRITE_SETTINGS`, `SCHEDULE_EXACT_ALARM`) seguían en `allow`. El control
  UHID temporal se cerró. El editor sin guardar seguía teniendo un registro de
  actividad en segundo plano, lo que no garantiza que su borrador sobreviva; no hay
  una macro guardada o activa. No se ha probado recuperación real.

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
- [ ] 7. Recuperación mediante MacroDroid autorizada expresamente, con la única app
  oficial y el permiso `WRITE_SECURE_SETTINGS` explicado al usuario.
  - [x] 7.1. Instalar y verificar la app de Google Play solo en el teléfono previsto.
  - [x] 7.2. El usuario autorizó la ubicación de fondo adicional para intentar
    restringir la automatización a la red conocida; no habilitarla en cualquier red.
  - [ ] 7.3. Resolver el rechazo del permiso protegido y verificar manualmente la
    condición de red y la única acción de recuperación antes de activar la macro;
    no añadir permisos, listeners ni puertos no aprobados.
  - [ ] 7.4. Demostrar recuperación real únicamente cuando esté confirmado que la
    revisión USB está en reposo y sin comprometer la fixture.

## Open Issues

- La pérdida coincidió con un cambio de red y desconexiones TLS; falta establecer
  por qué cambió la red y si hubo cambio de puerto o apagado de la depuración. Al
  recuperar acceso, el ajuste de depuración Wi-Fi se leyó activado.
- No se ha identificado un canal independiente existente que pueda encenderlo sin
  interacción. La reactivación automática no está demostrada y puede necesitar
  hardware o una excepción de seguridad que el usuario debe decidir expresamente.
- Para restringir MacroDroid a la red conocida, la ubicación de fondo parece
  necesaria según su documentación y el usuario la autorizó. Falta comprobar el
  comportamiento real del dispositivo. El SSID por sí solo no prueba que la red
  sea auténtica. Sin la guarda, no se activa la macro por defecto.
- Los accesos adicionales a modificar ajustes y a alarmas exactas fueron autorizados
  y concedidos, pero el sistema rechazó `WRITE_SECURE_SETTINGS`. El intento temporal
  de activar la opción de seguridad de Xiaomi respondió que se inserte una SIM y no
  activó la opción. Usar una SIM, desactivar la vigilancia de permisos o añadir cuentas
  no está autorizado por los permisos anteriores. Sin el permiso protegido no se
  activa una macro que aparentaría funcionar. Una alternativa sin este permiso no
  se ha demostrado. El usuario indicó «no vamos a tener una sim nunca»: descartar
  las vías que dependan de una SIM y detener esta vía si no existe una alternativa
  viable sin ella.
- MacroDroid es una candidata expresamente autorizada, no una recuperación probada.
  Se debe verificar en el teléfono si exige confirmación del sistema, permisos extra
  para limitar la red o alguna excepción adicional de HyperOS; no conceder permisos
  no aprobados ni considerar demostrada la recuperación sin prueba física segura.
