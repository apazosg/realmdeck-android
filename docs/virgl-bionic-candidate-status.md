# Candidato VirGL Bionic: servidor con triángulo; integración guest pendiente

Actualizado 2026-10-04. Continúa Phase B de `gpu-platform-support-plan.md`. El
candidato GL/GLX de 30 bibliotecas permanece solo en host. Un cierre EGL/GLVND de
28 bibliotecas sí se cargó privadamente: display Mesa EGL1.5 y conexión al servidor
demostrados, pero OpenGL3.3 core rechazado con EGL_BAD_MATCH. El servidor de 10
archivos ya pasó su triángulo nativo. Son gates distintos, sin modificar APK,
Wine, ImageFS, driver, DLLs o perfil activo. Ver
`docs/guest-egl-context-poco-results.md`; todavía no hay contexto/draw guest ni
presentación, D3D o frame de WoW aceptados.

## Paquetes y bibliotecas

El ledger fijado de Termux contenía 29 paquetes, 40.461.140 bytes. Se adquirieron
37.136.392 bytes nuevos; los dos paquetes anteriores se reutilizaron tras validar
SHA y tamaño. Sin APT, refresh de índice ni versiones alternativas.

- `audit_payloads.py` y `materialize_candidate.py` realizan auditoría acotada,
  checks de pin/rutas/symlinks/ABI y materialización exclusiva fuera del repo.
- **13 tests** afectados pasan; sin pruebas repetidas de APK o de GPU.
- Candidato host: `arm64-prototype/controls/virgl-audit/private_candidate/`.
  30 bibliotecas regulares y 56 aliases relativos auditados.
- Cierre DT_NEEDED desde libGL/GLX_mesa/Gallium: **ningún SONAME ausente** respecto
  a paquetes obtenidos + allowlist Android. Eso no prueba símbolos/versiones,
  resolución de RUNPATH ni que un loader Bionic pueda cargarlos.
- `candidate-manifest.json`: pins/ABI/NEEDED/paths; `ready=false` y
  `privateLoaderResolution=UNVERIFIED`. El RUNPATH Termux original se conserva;
  no patchelf ni escritura en ImageFS. Trust limitado al ledger/HTTPS y sus hashes,
  no validación GPG independiente del índice apt.

## El servidor actual no es Mesa vtest

En GameNative fijado a `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`:

- `virgl_server_protocol.h` define CREATE_RENDERER=1, GET_CAPS=2,
  RESOURCE_CREATE=3, FLUSH_FRONTBUFFER=9.
- `virgl_server.c:75-79` cierra la conexión si el primer command no es 1.
- Mesa vtest usa CREATE_RENDERER=8, GET_CAPS=1, RESOURCE_CREATE=2,
  GET_CAPS2=9 y negociación/versiones diferentes. El formato de recursos/FDs y
  frontbuffer también difiere; no se arregla renombrando un socket o un comando.
- El servidor JNI actual requiere GLRenderer/EGL y su callback Android de
  frontbuffer. El cliente Termux usa `VTEST_SOCKET_NAME`; la rama custom previa
  usa `VIRGL_SERVER_PATH` y `/tmp/.virgl/V0`.

Por tanto, **no conectar el cliente obtenido al servidor existente como si fuese
un perfil listo**. No se escribió ni ejecutó un adaptador de protocolo.
El header/source actual permite verificar la incompatibilidad antes de otro APK.

La alternativa investigada posteriormente es el servidor vtest upstream Bionic correspondiente,
con integración EGL/presentación demostrada; su receta Termux 1.3.0 usa
`-Dplatforms=egl,glx`. Esa receta no demuestra todavía un binario compatible con
nuestro XServer ni recepción de un frame. Adaptar/recompilar el cliente o servidor
sería otro cambio de alcance, no un preset sin coste.

## Decisión

Conservar los componentes en el host y el baseline del teléfono intacto. No nuevo
APK ni trial WineD3D por este hallazgo. Las pruebas posteriores de carga y handshake
están en `docs/native-vtest-poco-results.md`: primero falló winsys; el diagnóstico
fuente identificó la guarda GBM y la variante Android-default posterior recibió
caps de una instancia verificada. No backend ni presentación PASS.

El usuario ha confirmado después que **no usaremos el Pixel**. El siguiente
componente compatible se investiga para el POCO xaga/Mali-G610 conectado, sin
esperar otro dispositivo ni transferir a él resultados gráficos ajenos.
GPU, D3D11/present, WoW/frame y login siguen siendo gates separados.

## Primer gate físico EGL/GLES — 2026-10-03

La sonda nativa propia, usando solo bibliotecas Android del sistema, consiguió
**GLES 3.2 / Mali-G610**, shaders y lectura de 1.300 píxeles de un triángulo pbuffer,
con limpieza correcta. No se instaló el candidato Mesa ni se cambió Wine/APK.
Dominio `runas_app`, no prueba todavía del dominio de la Activity ni de vtest.
Resultado y límites en `docs/native-egl-poco-results.md`.

La ruta estándar Mesa vtest de presentación pasa por su callback interno
`flush_frontbuffer` y XShm/XPutImage; no confundirlo con el opcode 9 GameNative.
Esto no valida aún la conexión, GLX ni presentation. Sigue pendiente un servidor
vtest correspondiente y su resolución de EGL/GLES: libepoxy Termux usa nombres
Unix y el control pbuffer no prueba su ruta de contexto sin superficie.

## Segundo gate físico: servidor y protocolo

El control propio EGL sin superficie también dibujó 1.300 píxeles con GLES en el
Mali, sin pbuffer. El servidor Termux separado supera DT_NEEDED/entrada a main,
pero dos conexiones privadas (default EGL y una flag `--use-egl-surfaceless`)
devuelven `failed to initialize vrend winsysfailed to initialise renderer.` antes
del contexto/caps. Cliente y servidor están ligados por PID/startTicks/UID y
argv; no timeout ni procesos restantes. Esto califica una **limitación del
candidato servidor**, no invalida el EGL nativo ni demuestra GPU incompatible.
El próximo gate es su selección/configuración de EGL/build, no otro preset Wine.

El control libepoxy posterior también pasó el contexto usando EGL Android, no
solo las imports directas de las sondas anteriores. La fuente oficial del tar
Termux ya está verificada y muestra la guarda GBM/plataforma genérica; la rama
Android del mirror no es autoridad sobre ese tar. Ver
`docs/native-vtest-epoxy-poco-results.md`. El build acotado aprobado y sus controles
se completaron en `.agents/planning/done/vtest-android-egl-build-plan.md`.

## Estado después del control fuente Android

Solo el servidor independiente usa el EGL Android default ya medido. El RUN
`VTEST-036cf04d51f44ad2a9e2d226302e67a3` recibió 308 bytes de caps de un peer
PID/startTicks/UID verificado, con exits 0 y sin procesos pendientes. Se conserva
la clasificación HOST original equivocada y su interpretación corregida, ligada
a los bytes y al source; no se relajan los checks ni se infiere gameplay.

El cliente Mesa/GL de 30 bibliotecas **sigue sin cargarse** en el teléfono. La ruta
GameNative custom sigue siendo incompatible con Mesa vtest: el nuevo servidor es
otro candidato aislado, no un traductor del servidor JNI ni un nuevo perfil activo.
Los controles nativos posteriores del mismo servidor ya verificaron un buffer
de cuatro bytes y un triángulo de 64×64: 1.682 píxeles cambiados y 2.414 de fondo,
con shader/draw/readback, cleanup/EOF y exits 0. Ver los RUN y límites exactos en
`docs/native-vtest-poco-results.md`. Esto no cargó el guest Mesa de 30 bibliotecas.

Quedan loader/GLVND/virpipe del guest real, GLX/X11/presentación, WineD3D, D3D y
WoW. El siguiente candidato de carga necesita los roots EGL/GLVND y EGL Mesa
presentes en paquetes ya auditados, pero omitidos por la selección anterior
GL/GLX. Sus exports `egl*`/`__egl_Main`, vendor JSON y dependencias se califican
antes de cualquier ejecución. Los espacios de bibliotecas guest y servidor deben
seguir separados: no anteponer Mesa EGL al servidor que usa EGL Android.

## Selección EGL guest auditada, aún sin ejecutar

Un candidato **separado** se materializó exclusivamente en host a partir de los
29 paquetes ya fijados/cacheados, sin nuevas descargas ni rebuild de Mesa:

- `controls/virgl-audit/private_egl_candidate-v1/`.
- Roots exactos `libEGL.so.1` y `libEGL_mesa.so`; cierre DT_NEEDED sin SONAMEs
  ausentes respecto a esos paquetes y la allowlist Android.
- 28 archivos regulares, 159.453.576 bytes, 13 aliases internos seguros. El
  candidato anterior GL/GLX de 30 bibliotecas no se modifica.
- Manifest SHA
  `30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9`;
  tree SHA `1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216`.
- `llvm-readelf` confirma exports dinámicos públicos FUNCTION del loader GLVND:
  `eglGetPlatformDisplay`, `eglInitialize`, `eglCreateContext`, `eglMakeCurrent`,
  `eglGetProcAddress`, `eglGetError`, `eglChooseConfig`, `eglBindAPI`, `eglTerminate`.
  Mesa expone `__egl_Main`: no llamar directamente `egl*` sobre el vendor.
- El paquete contiene el vendor JSON auditado de 105 bytes,
  `share/glvnd/egl_vendor.d/50_mesa.json`, con `library_path=libEGL_mesa.so.0`.
  Esto prueba el contenido, no que GLVND lo encuentre o lo cargue en Android.

Los cambios de raíces/offline conservan por defecto el materializador anterior;
28 tests nuevos/afectados pasan y dos regresiones adicionales verifican rechazo
de exports local/hidden/zero y hash de árbol por streaming. La revalidación de
exports más estricta y el hash coinciden con los archivos ya materializados.
Solo inspección ELF en host: **no se ha ejecutado o copiado este guest al teléfono**.
RUNPATH Termux permanece original, sin patchelf; `ready=false`, loader/protocolo
UNVERIFIED, presentación/D3D/WoW false. La carga real es el siguiente control,
con namespaces de bibliotecas guest y servidor separados y sin modificar el
ImageFS, el driver o el perfil de referencia.
