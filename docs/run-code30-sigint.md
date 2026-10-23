# Corrida code 30: ruta AA64 aplicada, cierre del host por SIGINT

Fecha de revisión: 2026-10-02. Solo inspección; no nueva APK ni cambios de runtime.

## Informe y procedencia

```text
/home/adrian/wowforever-artifacts/delivery/forever-RUN-1790948680875-8382457650114.zip
SHA-256 d30ddf214207f0f7a9974853409452046419d25d0e14608b0461d7d76a14947a
```

Once entradas, 88.648 bytes sin comprimir: estado, provenance, JSONL, evidencia
nativa no capturada, head/tail/manifests de ambos streams e índice de logs vacío.
Provenance identifica `0.2.6-automated` / code 30 y los mismos inputs de catálogo,
Proton/DXVK, WX y JNI que el candidato entregado. Dispositivo Xiaomi 2409FPCC4G,
hardware mt6855, Android API 36 / arm64.

## Hechos confirmados

- El asset del catálogo dejó de bloquear: `launch_route_selected` registra
  `verified-aa64-direct`, PE AA64/PE32+, MD5
  `f8d91fd1b3751e77973035dccbde40e7` y tamaño 94.286.544.
- Comando dirigido a Wine/explorer y `A:\WowB-ARM64.exe`, con
  `-config BetaSuspendedTest.wtf -d3d11`, sin wrapper Windows `winhandler.exe`.
  Cwd y unidad A corresponden a la instalación gestionada.
- Wine raíz PID 32018 / startTicks 1258685, spawn `1790948697574`.
  Host Android original PID 31865. Al volver a abrir, nuevo host PID 32390.
- `ApplicationExitInfo` identifica **SIGINT**: reason 2 / status 2,
  PID 31865, package y resumen único de RUN coincidentes, timestamp
  `1790948709404`. Son **11,830 segundos después del spawn**. No se atribuyen
  las entradas históricas LockScreenClean ni la señal de la corrida code 28.
- La recuperación dejó `INTERRUPTED / supervision_lost_reboot_required` y
  terminación desconocida; no reanudó ni creó automáticamente otro Wine.
- Native signal observer `UNSUPPORTED/ANDROID_SIGNAL_OWNERSHIP_UNVERIFIED`;
  no se instaló un handler ni se conservó `siginfo` del emisor.
- 23 descubrimientos sin candidato validado; gamePid null y ninguna escritura
  del patcher. Esto no prueba ausencia absoluta del juego ni culpabilidad del JNI.
- Los aliases de package y la cadena SHM/evshim/WX están correctos; las
  redirecciones efectivas apuntan a nuestra ImageFS. Preflight de componentes
  nativos AA64 válido, helper opcional para esta ruta.

## Lo nuevo del final de stderr

El snapshot contiene **55.168 bytes / 426 líneas**, sin truncación ni error de
persistencia declarado. Última captura `1790948708727`, último flush
`1790948709014`: quedan 677 ms y 390 ms respectivamente hasta la señal. No es el
agujero de ~15 segundos provocado por el límite inicial de code 28. Todavía puede
faltar la última fracción de segundo; el exportador mantiene `UNKNOWN` sobre el
estado final del stream.

En el texto retenido:

- Línea 26: creación de `wineboot.exe --init` (Windows PID 0028).
- Líneas 355–358: wineboot termina con exit code 0, Wine uptime 12594.102.
- Líneas 389–401: creación de `explorer.exe`, Windows PID 00d4, Wine uptime
  12597.900, con el escritorio y el argumento del cliente.
- Últimas líneas: inicialización de Explorer y redirecciones de bibliotecas.
  No hay una creación `NtCreateUserProcess` de WoW registrada ni un error gráfico
  fatal/SEH/GLX/DXVK/Vulkan que explique el SIGINT.
- `machine-id` ausente, `nsi` bind errno 13, warning sharedgpures y fallo del driver
  winebth son mensajes observados, **no causas fatales demostradas**.

Wine/explorer creado no equivale a WoW creado, frame renderizado o login.
El índice de logs del juego vacío está sujeto al filtro de exportación.

## Contraste de código y límites

No se encontró un emisor Java ni C/C++ fuente de SIGINT en las rutas revisadas de
XServer/renderer/evshim/WinHandler. SIGTERM/SIGKILL del cleanup no explican status
2. PRoot no participa en este argv Bionic directo. La cercanía a Explorer no
demuestra un fallo gráfico ni identifica quién envió la señal.

Hay bibliotecas precompiladas: no se pueden excluir llamadas nativas no visibles
en sus fuentes o syscalls directos. Inspección de imports dinámicos de
`libwinlator_11`, `libvulkan_renderer`, `libxconnectorpatch`, `libmain_hook` y
`libhook_impl` no encontró imports kill/killpg/raise/signal; algunos importan
syscall o abort. **Eso no excluye un emisor por syscall ni código externo.**

Dos límites propios del diagnóstico detectados, sin corregirlos a ciegas:

1. El tokenizer upstream conserva comillas en sus tokens; nuestro journal usa
   otro parser que las elimina al presentarlas. El stderr muestra las comillas
   escapadas en el cmdline de Explorer. El fork funcional fijado a
   `2619b9f7...` usa **el mismo tokenizer y patrón de launch**; no es una diferencia
   que pueda proclamarse causa, y no se cambia globalmente el parser por intuición.
2. PGID/SID se leen para el binding privado, pero no están en este ZIP: snapshot
   de procesos omite esos campos y la ausencia de trace nativa no exporta binding.
   No hay evidencia para afirmar si host y Wine compartían un grupo. Añadir el
   registro por sí solo tampoco demostraría quién envía SIGINT.

## Resultado y siguiente decisión

El ensayo demuestra que la corrección del catálogo y la ruta AA64 directa están
activas. **El bypass no bastó para eliminar el cierre del host.** El emisor sigue
desconocido; no hay base para cambiar driver, suprimir señales o afirmar que WoW
ha fallado por insuficiencia de CASC.

No se genera otra APK por este informe. El próximo experimento debe discriminar
el arranque del runtime de la integración propia: misma ruta/componentes/datos,
pero un control explícito sin descubrimiento/parcheo ni arming JNI durante el
arranque. Ese control no validaría login Forever y requiere definir su alcance
antes de implementarlo. Alternativamente, una captura fiable del emisor resolvería
la atribución, pero el handler inseguro no se reactiva para conseguirla.

El informe y code 30 se conservan. No se pide repetir esta misma tentativa ni
borrar datos. Si se hace otra prueba después del Wine iniciado, la política
actual exige reiniciar Android; no se modifica la autorización por este análisis.
