---
id: wow-tcp-connect-observation-2026-10-14
state: done
date: 2026-10-14
---

## Summary

**D2 terminado, resultado inconcluyente.** La herramienta instalada pudo observar
el proceso propio de WoW, pero el attach tardío y el cap2MiB alcanzado en6s
impidieron identificar el flujo. Sin Login, nuevos APK, bypass, root o Btrfs.
Resultados/timestamps completos en `docs/arm64-wine-tcp-observation.md`.
Se detuvo la observación a5min25s del comienzo; el cierre/restauración quedó
registrado a05:09:25UTC del día siguiente, por lo que **no se acredita el
presupuesto10min para el cierre completo**. No inferir observación activa en
todo ese intervalo. No retaggear datos ni dar por resuelta autenticación.

Propuesta revisada a petición del usuario: localizar el primer fallo verificable
de conexión de WowB-ARM64.exe con Forever, mediante una observación estándar de
red atribuida al proceso, no Android nc, aserciones genéricas o Login visible.
**Aprobada por el usuario: «si, hazlo»; comienza el preflight.** Hipótesis «solo escaneamos antes del
Login» cerrada. No reabrir búsquedas de claves, capturador o análisis estático.

## Content

### Capacidad existente y límite conocido

- Los logs de errores/advertencias Wine pueden habilitarse reversiblemente en
  el contenedor propio, sin APK nuevo: D1 verificó su valor efectivo en WowB.
- Actualmente no demuestran endpoint de Schannel ni finalización de handshake.
  Los FIXME de algoritmos no equivalen a fallo de certificado.
- Proc/net y netlink fueron denegados; no repetirlos esperando otro resultado.
- No se ha comprobado disponibilidad ni permiso de attach de `strace` en el
  dispositivo. Este es el requisito del método, no una capacidad que se dé por hecha.

### Método único: traza estándar de sockets del propio WoW + logs existentes

1. Identificar PID Unix, nombre/cmdline no sensible, UID y startTicks del WoW
   real. Verificar contexto Wine/prefix/portal y ambos instrumentos de memoriaOFF.
   No confundir15715/17480 del collector de D1 con el emisor Wine00e8/00ec.
2. Comprobar solo disponibilidad/versionado/permisos de la herramienta existente.
   Si `strace -yy` no está disponible o el kernel rechaza observar el proceso
   propio, parar: no instalar, compilar, root, cambiar SELinux/ptrace_scope,
   crear APK, VPN, proxy o un capturador alternativo.
3. Una observación, máximo120s, del proceso e hilos propios. Registrar timestamp,
   PID/TID, descriptor y tipo de socket, IP/puerto, resultados y errno de llamadas
   `socket`, `connect`, `getsockopt`, `getpeername`, `shutdown`, `close`.
   Los eventos de otras instancias/servicios Wine no acreditan conexión del WoW.
4. Para detectar intercambio sin capturar contenido, permitir solo metadata y
   valores de retorno de operaciones de envío/recepción, incluidas read/write si
   las usa el backend, **con argumentos sin decodificar** (`strace -e raw=...`).
   No registrar buffers, iovecs, payloads, datos de cuenta, certificados privados
   o material TLS. Si la versión disponible no permite garantizarlo, no habilitar
   esas operaciones; declarar que el intercambio/cierre remoto no es observable.
   Correlacionar solo con descriptores TCP identificados y vigentes; no confundir
   IPC/archivo/UDP o un descriptor reutilizado con ese flujo.
5. Correlacionar con errores/advertencias existentes de Wine y del cliente. No
   habilitar trace global de Schannel/GnuTLS que pueda volcar buffers o claves.
   Restaurar ajustes de logging al cerrar. No depurar aserciones en paralelo.

### Qué constituye evidencia

| Pregunta | Evidencia positiva o error concreto | Límite |
|---|---|---|
| TCP de WoW con Forever | Mismo PID/socket TCP, IP resuelta del portal y1119, connect completado; o errno terminal de esa conexión | EINPROGRESS aislado/SYN/Android nc no basta. SO_ERROR=0 solo no prueba un socket conectado. |
| TLS | Resultado explícito del handshake/error de validación del cliente/runtime asociado al flujo | Tráfico TCP, cipher consultado o silencio no prueban TLS completado. Si falta correlación, marcar desconocido. |
| Intercambio | Retornos positivos de envío y recepción en ese socket TCP vigente, sin contenido | No identifica mensajes WoW/BGS ni aceptación de credenciales. |
| Cierre | Orden de shutdown/close local frente a EOF/reset observado en recepción | EOF puede ser provocado por shutdown local: respetar el orden. Reset/remoto no prueba que lo originase el servidor y no un intermediario. Sin evidencia suficiente, no atribuir autor. |
| Primera etapa fallida | Primera llamada/estado con error terminal y contexto atribuido | Una etapa no observable no es automáticamente una etapa fallida. |

Si el attach ocurre después del connect, registrar ese límite, no inventar la
llamada perdida. Si Wine delega la operación o no se identifica inequívocamente
el descriptor, no ampliar la prueba a otros procesos para fabricar atribución.

### Tiempo y parada

- Máximo **10min totales**: hasta2min de disponibilidad/permisos, hasta3min de
  preparación/identidad, hasta2min de observación y3min de recogida/cierre.
- Cap2MiB de metadata de traza y2MiB de logs propios. Truncamiento explícito;
  parar si se alcanza el presupuesto. Nada de iteraciones para reparar herramientas.
- Un solo arranque/observación si es necesario y está preparado el observador;
  no repetir un arranque sin observabilidad. **No nuevo Login** en este alcance.
  Si no aparece intento de conexión sin Login, informar «no observado», no fallo
  TCP. No usar las credenciales por la autorización histórica de otros ensayos.
- Sin modificaciones de ejecutable, runtime, drivers, APK, memoria o validadores.
  Btrfs/wowserver permanece intacto; sin prueba x64 en paralelo.
- Evidencia privada fuera del repo, metadata mínima. Entregar aunque no se
  resuelva, identificando último éxito, primer fallo y campos no observables.

## Implementation / Action Items

- [x] 1. Aprobar el alcance revisado de una observación de sockets sin Login.
- [x] 2. Verificar herramienta/permisos y proceso propio: strace4.25, UID10330,
  WoWPID26754/start465456, attach aceptado. Identificación inicial por basename
  falló; método existente de lista Wine+comm identificó el proceso sin otro arranque.
- [x] 3. Una observación de6s, parada por cap2MiB; no buffers ni ampliación.
- [x] 4. Restaurar logging y entregar tabla TCP/TLS/intercambio/cierre con un primer
  error verificable o límites explícitos; no proponer corrección sin evidencia.

## Open Issues

- D2:27715 líneas,41 TID observados; E/S genérica sin socket TCP identificado,
  cero líneas de endpoint/conexión atribuible en la muestra. Esto no es fallo TCP.
- Attach aprox113s después del lanzamiento pudo perder la conexión inicial.
  Eliminar E/S genérica de un posible siguiente método antes de otro control;
  no repetir ni aumentar límites sin aprobación. TLS/cierre siguen desconocidos.
- WINEDEBUGrestaurado, flags de memoriaOFF y force-stop solicitado. Timestamps
  documentados honestamente; límite global de cierre no cumplido/acreditado.

- No se ha comprobado que strace exista o esté permitido en el dispositivo.
  D1 confirmó denegaciones de proc/net y netlink; no repetirlas como alternativa.
- Puede no haber conexión auth hasta un Login; esta prueba no lo autoriza.
- Wine puede delegar operaciones de socket: una traza sin el evento no demuestra
  ausencia de conexión ni permite atribuir al WoW la conexión de otro proceso.
- El método no garantiza que los logs existentes puedan acreditar la finalización
  de TLS. No compensar esa limitación con MITM, bypass o instrumentación nueva.
