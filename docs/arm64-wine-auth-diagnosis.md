# Diagnóstico directo ARM64/Wine — D1

Fecha:2026-10-14. [Estado general](arm64-forever-status.md) ·
[plan terminado](../.agents/planning/done/arm64-wine-auth-diagnosis-plan.md).

## Resultado ejecutivo

**No se resolvió la autenticación ni se superó una etapa antes fallida.**
Se comprobó conectividad auxiliar Android y se observó el Wine real con ambos
instrumentos de memoria apagados. El primer error repetido del runtime apareció
antes de enviar Login: `err:seh:dispatch_exception assertion failure exception`.
Su causa y relación causal con la conexión todavía no están aisladas.

No se demostró rechazo de certificado, clave Ed25519 o contraseña. No se
implementó bypass ni se justifica desactivar verificaciones con esta evidencia.

## Alcance y duración

- Presupuesto20min. Operaciones21:21:22–21:38:57UTC: **1055s,17min35s**.
  Se cerró el ensayo sin prórroga para reparar herramientas. Este documento
  registra después su resultado; no se hicieron nuevas pruebas al redactarlo.
- xaga/API34, PoC code64 `com.wowforever.arm64probe`, Wine/Proton
  `proton-11.0-90624-arm64ec-1`, cliente ARM64 build70009.
- Se desactivaron y releyeron `code_capture_enabled` y `memory_patch_enabled`;
  el evento `prepared` confirmó ambosfalse. No hubo sesión del capturador,
  búsquedas de claves, lecturas de memoria, análisis del PE ni nuevo APK/build.
- Se cambió únicamente `envVars.WINEDEBUG` del contenedor propio después de
  preparar y antes de lanzar; XServerScreen recarga ese objeto desde disco.
  Se verificó el valor efectivo en el entorno del proceso Wine. Se restauró al
  terminar, conservando otros campos; ambos instrumentos permanecenOFF.
- Se solicitó force-stop del paquete. No se presenta esa solicitud como prueba
  independiente de quiescencia de todo el árbol Wine.
- No se modificó Btrfs, el ejecutable, runtime/driver, permisos del sistema ni
  verificaciones criptográficas. No hubo root, MITM o captura general de paquetes.

## Pruebas y resultados

### DNS/TCP auxiliar — Android con UID de la app, no Wine

- `ping -c1 -W3 auth.gpon.com.co` resolvió **132.145.132.10**. No respondió ICMP;
  eso no significa fallo DNS/TCP.
- `nc -w5 -q1 auth.gpon.com.co 1119`, stdin vacío y timeout externo7s, salió con
  código0. Acredita TCP desde Android/UID de la app, no WinSock o TLS del juego.
- `nc -q0` fue rechazado antes de conectar; se usó después la opción documentada
  `-q1`. El argumento inválido no se atribuye al servidor.
- Se encontraron utilidades Unix en ImageFS y utilidades Windows del prefix,
  pero no se verificó una invocación CLI soportada en el mismo contexto Bionic.
  No se construyó un probe ni se presentó OpenSSL de otro entorno como Wine.

### Logging efectivo de Wine

Se habilitaron errores/advertencias de `module`, `winsock`, `schannel`, `secur32`,
`crypt`, `winhttp`, con timestamp/PID. Sin `trace`, relay/snoop o buffers.
Proceso Android15715, cliente `WowB-ARM64.exe`17589, UID4
`[10330,10330,10330,10330]`; log propio `run-1792013170403.jsonl` preparado con
portal Forever y ambos instrumentosOFF, sin eventos patch_session.

Wine registró antes del envío de Login:

```text
21:27:52.829 UTC fixme:secur32:get_enabled_protocols handle TLS parameters
21:27:52.969 UTC fixme:secur32:get_cipher_algid unknown algorithm 23
21:27:52.969 UTC fixme:secur32:get_mac_algid unknown algorithm 200, cipher 23
```

En Wine11/GnuTLS,23 es ChaCha20-Poly1305 y200 es AEAD. La traducción de metadatos
puede emitir estos FIXME en `schan_get_connection_info()` y devolver `SEC_E_OK`;
**no prueban fallo de certificado ni ruptura de handshake**. El uso de
`GNUTLS_SYSTEM_PRIORITY_FILE=/dev/null` es un fallback deliberado de Wine.
El texto `tls` de libMEOW puede ser thread-local storage: no cuenta como red.

Se observó uso real de Schannel/GnuTLS, pero no se obtuvo el endpoint asociado.
Podría corresponder a otro servicio del arranque. `/proc/17589/net/tcp{,6}` y
`ss`/netlink devolvieron `Permission denied`; no se cambiaron permisos.

Referencias interpretativas, no prueba de equivalencia exacta del fork instalado:
[Wine11 conexión](https://github.com/wine-mirror/wine/blob/wine-11.0/dlls/secur32/schannel_gnutls.c#L724-L827),
[Schannel](https://github.com/wine-mirror/wine/blob/wine-11.0/dlls/secur32/schannel.c#L934-L970),
[GnuTLS](https://github.com/gnutls/gnutls/blob/3.8.10/lib/includes/gnutls/gnutls.h.in).

### Un envío autorizado de Login

El usuario aportó una cuenta temporal vigente y autorizó al asistente a usarla;
después permitió más intentos. Se hizo **un solo envío**. La contraseña y valores
de cuenta no se incorporan aquí ni al APK. Los ficheros privados temporales se
consumieron/eliminaron antes de entrada por HID o redacción de logs.

Primera guarda: terminó el límite local12s antes de escribir, pese a muestras
de formulario/foco coincidentes. Segunda guarda: helper existente con espera35s,
sin cambiar su código ni condiciones. Verificó formulario/foco y Remember Account
desmarcado. **Enter enviado21:35:57.823UTC**, fin de envío21:35:58.057UTC.
La primera guarda no cuenta como intento de autenticación.

Después se observó todavía el login, sin transición acreditada a reino/personaje
o mundo ni respuesta WoW/BGS nominativa. No se observó WOW51900317 en la imagen
actual; no importar el resultado histórico a esta prueba. Las imágenes retenidas
enmascaran campos y barras; los textos posteriores se redactaron.

### Primer error repetido observable, anterior al Login

```text
21:27:55.768 UTC err:seh:dispatch_exception assertion failure exception
21:27:55.995 UTC err:seh:dispatch_exception assertion failure exception
... repetido durante la ejecución ...
21:35:57.823 UTC Login: un Enter enviado
```

El extracto posterior contiene1269 líneas seleccionadas, mayoritariamente esa
aserción. Entrada logcat258401B, sin alcanzar cap2MiB, pero limitada a las
últimas2200 líneas: no es cobertura ilimitada. Incluye los mensajes Schannel y
la aserción indicados. No fueron accesibles metadatos de Errors para atribuirla
a una función concreta; no se reutiliza una aserción histórica del allocator.

La precedencia temporal excluye que este envío de credenciales iniciara ese
bucle. No demuestra por sí sola error fatal, congelación total, ausencia de
tráfico o que la aserción impida autenticar.

## Tabla de etapas

| Etapa | Estado | Evidencia / límite |
|---|---|---|
| DNS Android/UID app | OBSERVADA_OK | Resolución del portal; no sustituye resolver Wine. |
| TCP Android/UID app | OBSERVADA_OK | nc1119 código0; no prueba WinSock del cliente. |
| DNS/TCP Wine hacia Forever | NO_OBSERVABLE | Actividad WinSock/sockets, sin endpoint atribuible. |
| Uso de Schannel/GnuTLS en Wine real | OBSERVADA_OK | Consultas de protocolo/cipher/MAC; no error X509 demostrado. |
| TLS completado contra Forever por el cliente | NO_OBSERVABLE | Falta endpoint/resultado explícito de handshake y validación. |
| Mensajes WoW/BGS aceptados/respondidos | NO_PROBADA | Sin mensaje/respuesta nominativa. |
| Envío de Login | OBSERVADA_OK | Un Enter con guardas; no prueba aceptación. |
| Autenticación/reino/mundo | NO_PROBADA | Sin transición acreditada. |
| Error del runtime previo a Login | FALLO_CONCRETO observado | Aserciones repetidas; causa/impacto sin aislar. |

La primera etapa desconocida no equivale a la primera fallida. La última etapa
de autenticación aceptada **no pudo establecerse**. No se ejecutó un control
x64 emparejado: no reabrir CASC o infraestructura Btrfs para fabricarlo. Los
históricos de proceso vivo/MsQuic no equivalen a éxito auth comparable.

## Corrección mínima y siguiente observación

**Ninguna corrección funcional está justificada todavía.** No desactivar TLS ni
cambiar claves por estos FIXME. La revisión acotada D1-A siguiente agotó lo
atribuible a la aserción con los logs actuales. No continuar investigando
excepciones genéricas, capturador, nuevos APK u offsets por este mensaje.

## D1-A — Revisión de la primera aserción, solo logs existentes

Revisión2026-10-14,21:45:28–21:46:58UTC,90s de inspección. Sin operaciones de
dispositivo, procesos nuevos del cliente, Login, red, código o APK modificados.
Se examinaron los dos extractos D1 y sus líneas próximas a la primera aserción y
al Enter. No se consultaron otras corridas ni se investigó código de excepciones.

| Pregunta | Conclusión respaldada por los logs |
|---|---|
| ¿Qué componente la genera? | El **emisor del mensaje** es el despachador SEH de Wine (`seh:dispatch_exception`). No consta quién **origina** la aserción: faltan módulo/DLL, condición, ubicación y pila. No atribuirla a TLS, WoW, allocator o Wine como causa raíz. |
| ¿WoW principal u otro proceso Wine? | Las1248 repeticiones llevan el mismo par de identificadores Wine `00e8/00ec`. El prefijo Android `15715/17480 System.out` es el proceso/hilo que recoge la salida, no prueba del proceso emisor Wine. D1 identificó por separado el PID Unix17589 como WowB, pero el extracto no conserva una correlación entre17589 y00e8. **No puede decidirse con certeza cuál proceso Wine la origina ni si00ec es el hilo principal de WoW.** |
| ¿Es fatal o corta la conexión? | No hay evidencia explícita de terminación o cierre de socket causado por esa línea. El mismo par Wine sigue escribiendo durante8min47s; otro hilo del mismo proceso `00e8/01c0` consulta Schannel después del Enter. No parece una terminación inmediata del proceso, pero eso no prueba funcionamiento sano ni excluye bloqueo de otro hilo. La desconexión TCP no está observada. |
| ¿Operación concreta o comportamiento normal? | Primera21:27:55.768UTC, última21:36:42.991UTC;1248 líneas iguales,1137 anteriores al Enter y111 posteriores. No aparece solo al enviar Login. No tenemos un control sano comparable: **no podemos llamarlo comportamiento normal**. Tampoco vincular la primera aserción a Schannel por proximidad temporal. |

Evidencia primaria: `transport-errors-after-login-redacted.txt`, línea8
(primera), líneas1147–1154 (entorno del Enter y actividad Schannel posterior),
línea1269 (última). El recuento es sobre el extracto filtrado D1, no una cuenta
global de toda la ejecución. La ausencia de pila, terminación u otros mensajes
en ese extracto **no prueba que no existieran en la salida no retenida**.

**Decisión:** origen e impacto no identificables con la evidencia actual. Cerrar
esta rama aquí, sin nuevas trazas de SEH, volcados o pruebas de excepciones.

### Única prueba siguiente propuesta — TCP del propio WoW

Observar durante un máximo de60s las llamadas de socket del **PID Unix confirmado
como WowB**, usando una traza estándar ya disponible y permitida (`strace -yy`,
solo `socket`, `connect`, `getsockopt`, `getpeername`). No registrar `send`, `recv`,
`read`, `write`, buffers de protocolo, memoria o credenciales. Sin Login y sin
modificar el APK; los instrumentos anteriores permanecenOFF.

La evidencia positiva debe vincular el mismo proceso/hilo/socket TCP a una IP
resuelta del portal y puerto1119, con conexión completada: `connect=0` o
finalización asíncrona acreditada, no solo `EINPROGRESS`/un SYN. Una conexión de
Android `nc`, otro proceso Wine o un descriptor sin tipo TCP confirmado no basta.

Comprobar primero disponibilidad/permisos, sin instalar herramientas ni usar
root. Si falta la capacidad, no se realiza la traza. Si el cliente no intenta
conectar sin Login o la traza no ve la operación, resultado **inconcluyente**,
no «TCP falla»; no ampliar a otros procesos ni enviar Login automáticamente.
La propuesta se revisó después a10min y se ejecutó como
[D2](arm64-wine-tcp-observation.md), inconcluyente por attach tardío y cap de
metadata alcanzado. Esta descripción conserva el alcance inicial; no autoriza
repetirlo. Detalle en el
[plan cerrado](../.agents/planning/done/wow-tcp-connect-observation-plan.md).

## Evidencia privada

Directorio externo0700:
`/home/adrian/wowforever-artifacts/work/wine-auth-diag-20261014T212122Z/`.
Acciones de preparación/lanzamiento/Login sin valores, verificacionesOFF,
backup de configuración, extractos redactados, imágenes enmascaradas y
`cleanup-and-timing.json`. No contiene el fichero temporal de credenciales.
Los handshakes Linux anteriores siguen separados en
[TLS y confianza interna](arm64-forever-tls-diagnostics.md).
