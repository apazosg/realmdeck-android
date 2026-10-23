# Observación de sockets del WoW ARM64 — D2

[Estado general](arm64-forever-status.md) · [D1](arm64-wine-auth-diagnosis.md) ·
[plan cerrado](../.agents/planning/done/wow-tcp-connect-observation-plan.md).

## Resultado

**Inconcluyente.** Se verificó que la herramienta existente puede observar el
proceso real de WoW sin root. Sin embargo, el attach fue tardío y el cap2MiB se
alcanzó en unos6s por E/S genérica; no se identificó un socket TCP con Forever.
No se demostró conexión fallida, TLS completado, intercambio WoW o autor del cierre.

No se hizo Login, ni se usaron credenciales, instalaron herramientas/APK o modificaron
ejecutable, runtime, validadores o Btrfs. Capturador/escáner permanecieronOFF.

## Procedimiento realizado

1. Preflight con UID10330: `/system/bin/strace`4.25 existente; aceptó `-yy` y
   argumentos raw. Prueba inocua sobre `/system/bin/true` exit0; attach a un
   `sleep` propio durante1s finalizó por timeout124 sin denegación. No son pruebas
   de conexión del juego, solo capacidades del observador.
2. Preparación de una sola PoC con portal Forever, ambos flagsfalse en preferencias
   y evento `prepared`. Logging err/warn de red activado reversiblemente en
   `envVars.WINEDEBUG` del contenedor propio, sin trazas de buffers Schannel.
3. Un solo arranque solicitado; no se pulsó Login. El intento inicial de localizar
   el proceso con `pidof WowB-ARM64.exe` no lo encontró durante75s. No se hizo
   segundo arranque: se identificó mediante la lista existente de procesos Wine
   y sus metadatos `comm`, UID y stat. Es un fallo de identificación por basename,
   no evidencia de que el juego no arrancase o no conectase.
4. Objetivo confirmado: UnixPID26754, `comm=WowB-ARM64.exe`,
   startTicks465456, UID4`[10330,10330,10330,10330]`. Después de la traza se
   releyeron los mismos datos. El PID Android20339 recoge salida; no es el WoW.
5. Attach solicitado22:27:45.354UTC, aproximadamente113s después de la petición
   de lanzamiento22:25:52.185UTC. **Se pudo perder la conexión inicial.**
6. `strace -f -ttt -T -yy` restringido a llamadas de socket/cierre/duplicación y
   E/S, con argumentos `raw` en read/write/send/recv y variantes. Se conservaron
   punteros, longitudes y retornos, no se decodificaron buffers ni payloads.
   Observación máximo120s y salida máxima2MiB. No lectura de claves/heap ni
   reactivación del capturador; la lectura del observador es metadata de syscalls.
7. Cap alcanzado:2097152B retenidos más indicador de exceso;27715 líneas, algunas
   de llamadas todavía sin terminar,41 TID presentes. Tiempo del comando6.013s;
   los timestamps de líneas completas abarcan aproximadamente5.9s. No se repitió
   la traza ni se aumentó el límite.

### Qué contiene y qué no demuestra

Entradas de llamadas contadas en el fragmento:8113read,5294write,994sendto,
1544recvmsg,1694writev,187close. Son **líneas de entrada**, no necesariamente
operaciones completadas. La E/S puede ser IPC, archivos u otros descriptores;
los retornos positivos no se atribuyen a TCP sin identificar ese descriptor.

No aparecen líneas `connect`, `getpeername`, `SO_ERROR`, `AF_INET`,
`SOCK_STREAM` o anotaciónTCP que acrediten el flujo buscado en ese intervalo.
Eso no excluye sockets ya conectados antes del attach ni operaciones delegadas.
Los187close no demuestran que el servidor o WoW cerrasen una conexión auth.

El extracto propio de Wine volvió a mostrar uso Schannel/GnuTLS y los FIXME
de metadatos de algoritmos ya conocidos, sin handshake/endpoints vinculados.
No se utilizan como error de certificado o primer fallo TCP.

## Tabla final

| Pregunta | Resultado |
|---|---|
| ¿Puede observarse el WoW real con la herramienta existente? | Sí: attach aceptado y llamadas del PID/hilos, identidad estable. |
| ¿TCP de WoW con Forever acreditado? | No observado en el fragmento; attach tardío y truncamiento impiden descartar. |
| ¿TLS completado o fallido contra Forever? | No establecido; logs sin correlación suficiente. |
| ¿Intercambio de datos con Forever? | No atribuible: E/S genérica sin socket TCP identificado. |
| ¿Quién cierra? | No determinado. Cierres de FD genéricos no sirven como evidencia. |
| ¿Primer error de conexión verificable? | No identificado; los límites del observador no son un error del servidor. |
| ¿Se superó una etapa antes fallida? | No demostrado. |

## Cierre y tiempo — no ocultar la diferencia

- Inicio preflight:2026-10-14T22:22:26UTC.
- Fin de traza por cap:2026-10-14T22:27:51.367UTC, a5min25s del inicio.
- Cierre/restauración registrados:2026-10-15T05:09:25.702UTC.
- La diferencia de reloj hasta el cierre es24420s. **El cierre completo no queda
  acreditado dentro del presupuesto10min.** No descontar ese intervalo como si
  la restauración hubiera ocurrido antes; tampoco inferir que hubo una traza
  activa durante esas horas. El comando de observación terminó por cap en6s.
- WINEDEBUGrestaurado con relectura; flags de memoria siguenOFF. Force-stop
  solicitado al paquete propio, sin afirmación de quiescencia de todo el árbol.

## Decisión

Cerrar D2 como inconcluyente y no repetir ahora. Se conoce una limitación concreta
del método: attach tardío y demasiado volumen de E/S no clasificada. Si se
autoriza otra observación, la única corrección propuesta es identificar el PID
por el mecanismo que funcionó, preparar el observador temprano y restringir la
primera traza a llamadas de creación/conexión/estado/cierre de sockets, sin E/S
genérica. Primero acreditar TCP; no intentar resolver todas las etapas en una
traza que se satura. No elevar caps, añadir herramientas, hacer Login, investigar
aserciones o reabrir escaneos por este resultado. No ejecutado ese seguimiento.

## Evidencia privada

`/home/adrian/wowforever-artifacts/work/wow-tcp-observation-20261014T222226Z/`,
directorio0700/archivos0600: `target.json`, `socket-syscalls.txt`,
`trace-result.json`, `analysis.json`, acciones de preparación/lanzamiento,
`configuration.json`, backup privado del contenedor, `wine-network-warnings.txt`
y `cleanup-and-result.json`. Sin credenciales o buffers de red.
