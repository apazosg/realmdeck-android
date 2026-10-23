# POCO xaga: contexto del cliente Mesa todavía rechazado

2026-10-04. Gate privado aprobado de GPU Phase B: no cambia APK41, Wine, DXVK,
driver, ImageFS ni cliente. El servidor Android-default conserva manifest
`b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28`;
guest manifest `30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9`,
tree `1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216`.

## Implementación y límites

`GuestEglContext` carga GLVND/vendor privados, consulta display
`EGL_PLATFORM_SURFACELESS_MESA` y pide OpenGL 3.3 core. Solo si obtiene un contexto,
mide GL major/minor/profile mediante `glGetIntegerv`. Revalida servidor por
PID/startTicks/UID/exe/argv, y mappings privados sin EGL/GLES Android en el guest.
El servidor conserva su EGL Android en otro proceso. No draw, presentación, D3D,
Wine ni escritura de memoria; todas esas aceptaciones permanecen falsas.

NDK24/Android29, libc/libdl. Pasaron pruebas dirigidas native/HOST y regresiones
del loader anterior. Un harness omitía constantes; se corrigió el fixture, no el
gate. La revisión llevó a medir realmente el profile, rechazar zombies/exe/argv
incorrectos y testigos contradictorios antes de ejecutar. Los controles son
`runas_app`, no aceptación del dominio Activity ni de un backend completo.

## Primer control: requisito implícito de ventana

Carpeta `physical-trials/xaga-20261002/native-CONTEXT-VTEST-b6ed08f81c1a4de4b8387b5cef45cab3`.
Guest PID31269, servidor31243/startTicks8866668, UID10330.

- GLVND/vendor/Gallium/LLVM privados presentes; no fallback EGL/GLES Android.
- Display Mesa EGL1.5 inicializado y conexión al servidor demostrada.
- `eglChooseConfig` devuelve cero configs: la sonda omitía `EGL_SURFACE_TYPE`,
  solicitando por defecto `EGL_WINDOW_BIT` en un control sin superficie.
- Native CONTEXT_FAILED/exit1, resumen HOST UNKNOWN por warning de caché no
  previsto. Sin timeout, force-stop o procesos residuales. Resultado conservado.

Se corrigió solo la petición: `EGL_SURFACE_TYPE=0`, sin exigir superficie.
El warning exacto de caché Termux opcional inaccesible se registra como caché
desactivada, no fallo gráfico ni permiso para escribir allí. Otro stderr sigue
impidiendo PASS. No se modifica ninguna capacidad ni backend.

## Segundo control: config válida, creación de contexto rechazada

Carpeta `physical-trials/xaga-20261002/native-CONTEXT-VTEST-e911e5fe50b84108a771c2a8df973d87`.
Guest PID32111, servidor32090/startTicks8892956, UID10330.

- Misma cadena privada y display EGL1.5; una config RGBA8/OpenGL encontrada.
- `eglBindAPI(EGL_OPENGL_API)` tiene éxito.
- `eglCreateContext` **OpenGL3.3 core** falla con EGL_BAD_MATCH (0x3009/12297).
- Sin contexto current ni strings GL: no se puede deducir el máximo OpenGL,
  ni fingir un profile/feature level para continuar.
- Native/HOST CONTEXT_FAILED, clienteexit1/servidorexit0. Los mensajes de
  contexto creado y vendor ARM/Mali-G610/GLES3.2 son del servidor, no del guest.
- Diez eventos válidos, stdout5992/stderr95 bytes (warning de caché), pipes
  completos, sin truncación, timeout, force-stop o error de ownership. UID vacío.

Artifact final `controls/guest-egl-context/GuestEglContext`: 36528 bytes,
SHA `8db0e0565017743c29ad4814541b2c9607b42a3a48000bab538e3f16a5775975`.
Primer control: `9fac615efcd25484f3491ab8891fe64e3673d9f4431775ae6f45cb0477665e79`.
No se ejecutó ARM en host ni se cambió el servidor entre intentos.

## Próximo gate

Clasificar EGL_BAD_MATCH de esta pareja/version/profile/config, no declarar
incapacidad de Mali en general. Display guest/conexión ya probados; contexto
desktop3.3 core, draw guest, GLX/X11/present, D3D y WoW siguen pendientes. Si se
prueba otro profile/version, medir el resultado real sin Mesa overrides o fake
features y conservar este fallo. No hace falta otra APK para esta investigación.

## Matriz de clasificación: no es solo el profile core

2026-10-04. Mismos manifests y servidor; la nueva sonda fija mide atributos de
config y cuatro peticiones, sin aceptar un contexto inferior como backend.
Artifact `GuestEglProfiles`: 39.008 bytes, SHA
`d912d2a4e872ab7b1981e6f1f8b5f0156b02e79719e31e7f5d6af19f0fbc3726`.
Incluye los helpers anteriores sin modificar y delega la identidad en su chequeo
completo de UID/exe/argv/PID/startTicks/estado. No draw, Wine o memoria.

El primer intento `native-PROFILES-VTEST-c03eead22a244db2a580012b43473423`
conserva `UNKNOWN`: el encoder de la sonda puso una coma después del inicio del
array. Es fallo de nuestro diagnóstico, no evidencia de incompatibilidad GPU.
Se corrigió el encoder y se añadió un harness que compila y analiza **la salida
completa del C real** antes de repetir. No se reescribió el informe inválido.

Resultado válido:
`physical-trials/xaga-20261002/native-PROFILES-VTEST-6e0448a840f24c7fa14579bfd64916e3`.
Guest PID5671/UID10330, servidor5638/startTicks9442095.

- Display privado Mesa EGL1.5 y todos sus módulos/export owners comprobados.
- Config 31: `EGL_RENDERABLE_TYPE=77`, `EGL_CONFORMANT=77`,
  `EGL_SURFACE_TYPE=1`. Son bitmasks: contiene OpenGL (8), y pedir surface type 0
  no obliga a que la config devuelva cero. Bind/choose/los cuatro atributos:
  `EGL_SUCCESS`. No se modifica el provider para que anuncie capacidades.
- OpenGL3.3 core, 3.3 compatibility, 3.2 core y 3.0 sin máscara de profile:
  **las cuatro creaciones devuelven `EGL_BAD_MATCH` (0x3009)**.
- Ningún contexto current ni versión/renderer GL medidos. Esto no establece
  máximo GL2.1 ni incapacidad general Mali; solo rechaza esas cuatro peticiones
  en esta pareja/config. El capset1 anterior de 308 bytes no codifica una versión
  GL/profile exacta; `max_version` es la versión del capset, no máximo OpenGL.
- `PROFILE_MATRIX_MEASURED` es clasificación completa, **no PASS gráfico**.
  `contextQualified`, hardware/triangle/present/D3D/WoW permanecen false.
  Native y servidor exit0, pipes completos, cleanup limpio, ningún proceso propio
  residual, sin timeout ni force-stop. Warning exacto de caché opcional conservado.

Los tests dirigidos y la revisión corrigieron error de selección ignorado,
consistencia make-current/errores, perfil/renderer real y pins antes del trial.
El siguiente control debe observar negociación/capset y la decisión Mesa/EGL,
no seguir cambiando versiones a ciegas. La fuente upstream y el binario Termux
no se consideran equivalentes solo por su nombre. La APK41, Wine/DXVK, el driver,
ImageFS y los datos siguen intactos, sin parcheo de memoria.

## Ronda acotada de negociación

El control posterior ya observa protocolo4 y replies de ambos capsets (2:1408
bytes, 1:308; GLSL430, host GLES), con el mismo rechazo de contexto3.3 core.
Ver `docs/guest-vtest-negotiation-poco-results.md`. No se demuestra un fallo de
negociación wire, ni una corrección pequeña del rechazo local. Esta pareja queda
no aceptada; no se integrará con WineD3D ni se publicará como soporte Mali.
