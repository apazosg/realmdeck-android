# POCO: libepoxy alcanza EGL Android; fuente oficial del servidor verificada

2026-10-03. Solo POCO xaga `DEVICE_SERIAL`, UID 10330. No cambios a APK,
ImageFS, Wine, DXVK, driver, perfil activo o datos. El proceso es `runas_app`, no
aceptación del dominio Activity ni un renderer integrado con Wine.

## Control de la cadena privada libepoxy → EGL

Carpeta fuera del repo:

```text
/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/native-EPOXY-VTEST-e81f42a09ac8404498ea8e3e15fbcbf1
```

Probe propio `EglEpoxyProbe`: 21.864 bytes, PIE AA64, solo libdl/libc;
SHA `90e4c734cd8a862ee65a3fcb96753260ec631a8cafbf9a28062749333b2ed7fb`.
El runner valida el mismo manifest de 10 archivos del servidor, aliases privados,
hashes, UID, RUN/cwd y entorno limpio. No ejecuta el servidor ni toca sockets.
Entrada cerrada, captura fsynced antes de exec, deadlines y limpieza acotados.

- PID 1157, UID 10330, PGID/SID 1149; ocho eventos válidos y completos.
- Exit 0, stdout 4.497 bytes, stderr vacío, sin timeout/force-stop/errores de
  limpieza/evidencia; procesos propios restantes `[]`.
- Estado **EPOXY_CONTEXT_PASS**: carga privada y contexto comprobados, no draw,
  vtest, presentación, D3D ni WoW. Los campos de esas aceptaciones son false.
- `dlopen` del libepoxy fijado y slots de funciones con identidad privada exacta;
  después de la resolución, implementación EGL `/system/lib64/libEGL.so`.
- `eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS)`: 100 bytes completos, scan
  válido; **EGL_EXT_platform_base ausente**. No confundir extensiones de cliente
  con las del display: los false de cliente no niegan soporte de contexto.
- Android default EGL inicializa como Android META-EGL 1.4; extensiones del
  display 1.066 bytes completos, `EGL_KHR_create_context` y
  `EGL_KHR_surfaceless_context` presentes, `EGL_EXT_platform_base` ausente.
- GLES2 config WINDOW_BIT, contexto current con `EGL_NO_SURFACE`; GL vendor ARM,
  Mali-G610 MC6, OpenGL ES 3.2 r32p1. Errores EGL/GL limpios y cleanup correcto.

Verificación afectada: cuatro tests de probe/encoder/flow y build NDK 24;
siete tests del runner y recheck independiente sin P0/P1. No se repitieron las
pruebas de APK/triángulo nativo. Este programa diferente **no observa la rama
ejecutada por vtest** ni descarta por sí solo GBM, display o config en el servidor.

## Fuente oficial, no equivalencia por nombre de versión

Se obtuvo solo para inspección el tar oficial 1.3.0: **1.255.383 bytes**, SHA
`56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce`, exactamente
el pin de la receta Termux `27da60be397a6e9eb898bba8d03bcd648782ccd5`.
465 entradas, 9.169.006 bytes declarados; se leyeron diez textos seleccionados,
sin ejecutar fuentes ni construir. Archivo y hashes por texto en
`arm64-prototype/controls/virgl-source-audit-1.3.0/` fuera del repo.

La revisión del source oficial corrige una hipótesis de los mirrors:

- `meson.build:296–311` habilita el EGL no-Windows/no-Darwin mediante GBM;
  no contiene la rama Android `IS_BIONIC` que aparecía en el fork inspeccionado.
- `src/vrend/vrend_winsys.c:55–83` solo toma la ruta EGL propia con ENABLE_GBM;
  no existe allí una rama `IS_BIONIC` hacia `virgl_egl_init(NULL,...)`.
- `src/vrend/vrend_winsys_egl.c:338–340`, con ENABLE_GBM, rechaza
  `!gbm && (!surfaceless || !has_egl_base)` **antes de seleccionar un display**.
  El default display posterior no rescata un rechazo en esa guarda.
- Esa condición concuerda con el bloque de ensamblador del candidato; aún no
  registra el valor GBM en su ejecución ni demuestra la totalidad de sus patches
  o defines. Fuente del tar y receta no son reproducibilidad binaria comprobada.

Por tanto no aplicar un supuesto arreglo de un mirror a ciegas. El siguiente
paso propuesto es un build pequeño y trazable del servidor para registrar el
subpaso real y, si se confirma, habilitar explícitamente el EGL Android default
sin falsear capacidades. Ese build fue aprobado y completado; ver
`.agents/planning/done/vtest-android-egl-build-plan.md` y la secuencia fuente/fix/caps
en `docs/native-vtest-poco-results.md`. Esto no convierte el contexto libepoxy
separado en una prueba de recursos o render VirGL.
