# POCO xaga: carga del servidor y handshake vtest

2026-10-03. Solo `DEVICE_SERIAL`, package propio/UID 10330. No modificación
de APK, ImageFS, Wine, DXVK, driver, perfil activo, permisos ni datos del cliente.
Los controles son hijos nativos `runas_app`, no aceptación del dominio Activity.

## Artefactos y aislamiento

- Candidato servidor: 10 archivos Bionic AA64, 3.917.232 bytes, manifest SHA
  `5f40682f034050d5e72d98199b41a5da9444aa2770ee5732bb4a077de7e17174`.
- `virgl_test_server`: 139.464 bytes, SHA
  `f8f01e91be6e8edeffcc7197dc0f064a25cc190682c3ef7415b697ee6f61961f`.
- Cliente propio `VtestHandshake`: 16.360 bytes, SHA
  `c8f7223f368e5441f00e580cd725d98e55391c9810886a0531dcb90900d46b22`,
  PIE AA64 con `libc.so` únicamente. NDK 24 existente; no Wine ni GPU en sus tests.
- Directorios RUN privados exclusivos; todos los bytes/modos/hashes verificados
  antes de ejecutar. Solo aliases privados `libEGL.so.1` y `libGLESv2.so.2` hacia
  las bibliotecas Android existentes. No GLVND/Mesa-EGL ni reemplazo global.
- HOST supervisa dos handles ADB con stdin cerrado, salidas/deadlines acotados,
  captura escrita y fsynced antes de exec. Vincula servidor PID/startTicks/UID y
  argv privado; el cliente compara `SO_PEERCRED`, identity y stat antes/después
  de recibir datos. Half-close y EOF obligatorio, sin paquetes adicionales.
- Requests únicos: `[6,8] + "vtest\0"` y `[0,1]`, little-endian. Caps opacas
  `N+1/command 1` + N bytes, `1 <= N <= 256 KiB`. Sin recursos, submit ni draw.

## Carga del ELF comprobada, no renderer

Carpeta `native-VTEST-7c7415725bb14662b57221cda0106eac` bajo
`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`.
El servidor respondió a `--help` con su propia Usage, exit 1 y
`unrecognized option '--help'`. Se preservó la clasificación original y se añadió
una interpretación `LOADER_USAGE_OBSERVED`: llegó al parser, no a EGL/vtest.
No considerar un exit distinto de cero como help PASS.

## Primer handshake físico

RUN `VTEST-282e28e6110a4a3c82e61569bda34226`, misma raíz externa con prefijo
`native-`. Servidor `--no-loop-or-fork --use-gles --socket-path vtest.sock`;
PID 30395/startTicks 2326884 verificados. Cliente PID 30419 confirmó socket y
peer exactos, envió el request, pero recibió cierre sin caps.

Servidor stderr completo (64 bytes):

```text
failed to initialize vrend winsysfailed to initialise renderer.
```

Exit servidor 0, cliente 1/`UNKNOWN`, `response_rejected`. Es fallo de inicialización
previo al dispatch del contexto, no un protocol PASS ni hardware no soportado.
La primera clasificación genérica `FAILED` queda intacta en el informe.

## Control de una sola flag

RUN `VTEST-97d41055af0f4c009457ff2b85b23ce6`; PID servidor 30785/startTicks
2360350. Se añade únicamente `--use-egl-surfaceless`; ELF, librerías y protocolo
idénticos. Mismo stderr completo de 64 bytes, exit servidor 0/cliente 1,
`FAILED: server renderer initialization or context failed`. Sin caps.

Ambos controles finalizaron sin timeout ni force-stop; los procesos propios
restantes fueron `[]`. No se han cambiado señales/grupos ni inferido containment.
El control EGL nativo anterior sí dibujó 1.300 píxeles usando Android default EGL
con `EGL_NO_SURFACE`; este fallo no invalida aquella evidencia.

## Contraste estático del binario real

La biblioteca privada `libvirglrenderer.so` está stripped. Su export
`virgl_renderer_init` llama al bloque `0xa69ac`; la ruta EGL (flag bit 0) pasa por
un helper `0xa6e28` y luego `0xa78f4`. El último contiene una query de extensiones
EGL de cliente y comprueba `EGL_EXT_platform_base` (cadena VA `0x32fe8`). Con GBM
null, la rama default abandona antes de seleccionar un display; el control
surfaceless depende además de la ruta de plataforma. No coincide automáticamente
con el control propio default-display/EGL_NO_SURFACE.

Es asociación de ensamblador/source, no registro de la rama ejecutada ni prueba
de los defines completos `IS_BIONIC`/GBM de esta build. No se parcheó el ELF ni se
han fingido extensiones. Las fuentes mirror no se equiparan al tar oficial por
nominalmente decir 1.3.0. Las causas específicas de fallo (selección/loader/config)
siguen pendientes de resolver antes de otra integración.

## Siguiente puerta

Puerta pendiente al terminar los primeros controles: no conectar todavía WineD3D
a ese candidato. Primero calificar una build/path de
servidor que use el EGL Android comprobado, o diagnosticar ese subpaso de winsys
con fuente/build identity explícitos. Un cambio de flags no arregló el problema.
Solo después: caps válidas, recurso/transfer/draw, presentación, D3D y WoW.
Los controles fuente posteriores descritos abajo superan caps; recurso, transfer,
draw, presentación, D3D y WoW continúan **no demostrados**, incluido hardware VirGL.

## Control posterior de la cadena EGL y source oficial

`docs/native-vtest-epoxy-poco-results.md` registra un contexto Android EGL válido
a través del libepoxy y aliases privados exactos. La query de cliente no anuncia
EGL_EXT_platform_base. Se obtuvo el tar oficial 1.3.0 con SHA de la receta Termux;
su winsys condiciona EGL a GBM y contiene el rechazo temprano ya asociado en el
ensamblador. No incluye la rama IS_BIONIC que se describía en el mirror.
Ese era el límite de la investigación inicial. El build acotado se aprobó y los
controles siguientes identifican el rechazo real antes de probar una corrección.

## Build fuente diagnóstico: rechazo demostrado antes de EGL

Fuente oficial 1.3.0 fijada a
`56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce`,
NDK24/API29, EGL sin GLX, source patches con fuzz 0, entorno de build aislado y
headers reales. Candidato diagnóstico v6, manifest
`3947b472b1aeeba6a9e16e882c2a503e9b05d831006da3ab60c47927bd21e031`.
No se sustituye el servidor Termux, la APK41 ni el ImageFS.

RUN `VTEST-6498e7005c0a47c08bdf295326837f59`, servidor PID 8353/startTicks 3999035,
UID 10330. La traza completa confirma:

```text
compile.gbm=1
input.surfaceless=0
input.gles=1
client.has_platform_base=0
init.reject_missing_gbm_or_platform=1
```

Después falla winsys, sin seleccionar display ni inicializar EGL. Exit servidor
0/cliente 1, sin caps, timeout, force-stop o procesos restantes. Esto confirma una
guarda de la build genérica; no un fallo del hardware Mali ni del loader libepoxy.

## Android-default: contexto y caps recibidas — 2026-10-04

La variante opt-in conserva todos los caminos normales y selecciona
`eglGetDisplay(EGL_DEFAULT_DISPLAY)` solo cuando `__ANDROID__` y
`WFVTEST_ANDROID_DEFAULT` están habilitados, GLES=true, surfaceless=false y GBM=null.
Mantiene checks de extensiones/config/contexto, comprueba `eglMakeCurrent` y limpia
recursos adquiridos ante fallo. La selección no depende de
`ENABLE_GBM_ALLOCATION`: ese macro efectivo vale 1 aunque la opción Meson
`minigbm_allocation` sea false. No se inventan capacidades.

Candidato privado independiente:

- `controls/vtest-android-default-v2/candidate_android_default_v2_verified/`
- Manifest SHA `b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28`.
- Servidor 152.672 bytes, SHA
  `4aad98a173414b625d6af7e5181fe202c450e36d29cc632fb9bab49d11310261`.
- Biblioteca 654.752 bytes, SHA
  `468adc11695bab0173e0f2b1d461f87e2e4ad153c079011b92af20166ee0584e`.
- Los ocho proveedores anteriores son idénticos; sin descargas nuevas, workspace
  29.759.644 bytes, 13 patches fuzz 0. Solo define `WFVTEST_ANDROID_DEFAULT=1`:
  no equiparar el logging a la variante diagnóstica ni reclamar equivalencia binaria.

RUN `VTEST-036cf04d51f44ad2a9e2d226302e67a3`, carpeta `native-<RUN>` en la raíz
externa indicada arriba. Servidor PID 23137/startTicks 6935370, cliente PID 23159,
UID 10330. Servidor informa después de make-current:

```text
gl.vendor=ARM
gl.renderer=Mali-G610 MC6
gl.version=OpenGL ES 3.2 v1.r32p1-01eac0.54329dee8f160f288c574caaf67bbe3f
```

El cliente recibió **308 bytes de caps opacas**, 67 no-cero, FNV
`388f8b4b8a652997`. Revalidó peer/identity/PID/startTicks antes y después, framing
exacto, half-close y EOF; ambos procesos exit 0, sin timeout/force-stop/errores de
ownership, captures completos y procesos restantes `[]`.

El primer `summary.json` queda **preservado** con FAILED por una comprobación HOST
incorrecta: esperaba stdout vacío y el marker en stderr. El source oficial
`vtest_server.c:538,759` imprime creación y desconexión normal en stdout. Se acepta
solo esa secuencia exacta, con todas las guardas nativas intactas; se rechazan texto
extra, errores del renderer en ambos streams, peer distinto, caps inválidas,
truncación y salidas no-cero. `interpretation.json` añade
`VTEST_CAPS_RECEIVED`, hashes de cada evidencia y del summary original, sin
reescribir el resultado anterior ni repetir el teléfono para cambiar su etiqueta.

Checks afectados: 18 tests de source/build, 10 del runner fuente y 7 del handshake
HOST pasan; review independiente no encuentra P0/P1 para este gate privado. No se
repiten APK/JNI ni los controles EGL anteriores.

**Límite:** caps opacas y contexto EGL del servidor no prueban dibujado por VirGL,
transferencias, aceleración, GLX/X11, presentación, WineD3D, D3D11 ni WoW. El dominio
es `runas_app`, no la Activity. Los manifests estáticos originales permanecen
`ready=false`; la evidencia física independiente no convierte esto en un perfil.
Los controles posteriores de recursos y triángulo se ejecutaron con el mismo
servidor y se registran a continuación; no convierten el gate de caps en un perfil
de presentación o WineD3D.

## Recurso real por vtest — 2026-10-04

RUN `VTEST-210be544d9e34f798e49619b070a8c3e`, carpeta externa `native-<RUN>`.
Servidor PID 24388/startTicks 7306704, cliente 24412, UID 10330. El cliente fijo
`VtestResource` (21.848 bytes, SHA
`a31356a847bd342a2b5469d0837f88e2389c234f3b03d1beddfe3be88198efac`)
recibió caps, creó un buffer de cuatro bytes, escribió `a1b2c3d4` y leyó exactamente
los mismos bytes. UNREF, peer revalidado, EOF, ambos exits 0, ningún timeout,
force-stop ni proceso restante. Estado HOST `VTEST_RESOURCE_ROUNDTRIP`: prueba
recurso/map/transfer, no dibujado ni presentación. Los formatos protocolo v0,
identidad PID/startTicks/UID y límites están fijados en `README-resource.md`.

## Triángulo real por vtest — 2026-10-04

Sin cambiar servidor, APK, Wine, driver, DLLs, datos ni perfil activo, se ejecutó
`run_triangle_usb.py` con un cliente nativo propio libc-only que emite los paquetes
del test oficial de virglrenderer 1.3.0. Reutiliza el vínculo de peer conocido y
protocolo v0; no se conecta al servidor custom de GameNative.

- RUN `VTEST-c932e502b56342b783ac9c24cf8a1791`, carpeta externa `native-<RUN>`.
- Servidor PID 25458/startTicks 7569460, cliente 25485, UID 10330.
- `VtestTriangle`: 30.944 bytes, ELF AA64 PIE, solo libc; SHA
  `52c6edbfa00213b86390b1178edddea125916945baf7a8b94562e76a7ee94325`.
- Caps 308 bytes, 67 no-cero, FNV `388f8b4b8a652997`.
- Render target BGRA/X8 **64×64**, VBO de 96 bytes, vertex/fragment TGSI literales
  oficiales, framebuffer/viewport/estados y una llamada de tres vértices.
- Antes de dibujar, los **4.096 píxeles** de lectura eran el clear verde esperado.
  Después, lectura exacta **16.384 bytes**: **1.682 píxeles cambiados** y **2.414
  píxeles de fondo verde**, RGB no uniforme y borde verde. El byte X no se usa para
  fingir cambios de color. FNV del resultado `2a782f042044a277`.
- BUSY_WAIT, destrucción de objetos/UNREF de ambos recursos, identidad revalidada,
  half-close y EOF completos. Ambos exits 0, 288 ms de cliente, sin truncación,
  timeout, force-stop, errores de ownership ni procesos restantes.
- El servidor informa ARM / Mali-G610 MC6 / OpenGL ES 3.2. El estado es
  **`VTEST_TRIANGLE_OFFSCREEN`**, no un PASS genérico de GPU.

La revisión detectó y corrigió antes del teléfono un DWORD de stream-output
ausente en el header TGSI; se añadió una prueba de parsing independiente contra
el encoder oficial. 14 tests nativos HOST/cross-build pasan; 28 checks HOST del
runner nuevo y handshake pasan, revisión privada sin P0/P1. No se repiten suites
APK/JNI ni pruebas gráficas anteriores.

**Límite:** esto acepta shader/draw/readback fuera de pantalla a través del servidor
vtest independiente, en `runas_app`. No ejecuta todavía el cliente Mesa/GLVND
descargado, GLX/X11, presentación, WineD3D, D3D11 ni WoW. No equivale a verificar
el dominio de la Activity o soporte general Mali/Adreno/PowerVR. El siguiente gate
es el loader y el camino `virpipe` del guest real, aislado del EGL Android del
servidor; después vendrán presentación y D3D.
