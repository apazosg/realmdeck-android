# POCO xaga: triángulo nativo EGL/GLES fuera de pantalla

2026-10-03. Continúa Phase B del plan gráfico aprobado, **solo en el POCO USB**
`DEVICE_SERIAL`. El Pixel no participa. No cambia la APK instalada code 41,
Wine, DXVK, las bibliotecas de ImageFS, preloads, driver, permisos o datos del juego.

## Control y evidencia

Sonda propia `scripts/diagnostics/android-egl/EglBaseline.c`, compilada con el
NDK 24 existente: ELF64 LE PIE / AArch64, 22.592 bytes, imports únicamente
`libEGL.so`, `libGLESv3.so`, `libc.so`. SHA-256:

```text
163ebc3f5705e322778bfd35f1fa2b82627dacce510885a3b172b9dd9c39e34b
```

Fuente C y build fijados en el manifest externo. Se corrigió antes del dispositivo
un error del encoder que duplicaba las comillas de `runId`; el test compila y
ejecuta las funciones C reales de formato en host, sin EGL. Cuatro tests de
fuente/formato/build y dos del validador de salida pasan. No se repiten pruebas de
APK, cliente ni native patcher no afectados.

Ejecutado por `scripts/diagnostics/android-egl/run_usb.py`, con ADB autorizado y
`run-as`, UID 10330. Contexto SELinux real:
`u:r:runas_app:s0:c74,c257,c512,c768`. Entorno del hijo limpio: solo bibliotecas
Android del sistema, sin el shim no-GL ni Mesa/Termux. No es una ejecución en el
dominio SELinux de la Activity ni un nuevo ensayo Wine.

```text
physical-trials/xaga-20261002/native-EGL-20261003T142223Z-33b05b7fec/
RUN: EGL-20261003T142223Z-33b05b7fec
PID: 22806
```

Captura `capture.json`, ocho registros `stdout.txt`, `stderr.txt` y `summary.json`
fuera del repo. El uploader usa ADB sync + copia exclusiva a un directorio privado
del control y confirma SHA antes de ejecutar; no `exec-out` stdin (no entregó el
archivo). Dos intentos de transporte anteriores no ejecutaron la sonda; no son
fallos del driver. Se conservaron sus directorios de evidencia. El archivo público
temporal de staging se retiró solo en su directorio único; los datos anteriores
de la aplicación nunca se borraron.

## Resultado medido

| Gate | Evidencia |
|---|---|
| EGL | 1.4, `Android META-EGL`, API `OpenGL_ES` |
| Contexto | GLES 3 actual, pbuffer RGBA8 de 64 × 64 |
| Vendor / renderer | `ARM` / **Mali-G610 MC6** |
| Versión | OpenGL ES 3.2 `v1.r32p1-01eac0.54329dee8f160f288c574caaf67bbe3f` |
| Shaders | GLSL ES 3.00 propios, vertex/fragment compilados y program enlazado |
| Draw / lectura | Un triángulo, 1.300 píxeles coloreados de 4.096; FNV-1a RGBA `3005607757` |
| Errores | `glError=0`; limpieza sin errores; exit del proceso 0 |
| Resultado | **OFFSCREEN_PASS** |
| Presentación / D3D / WoW | Todos **false**, explícitos en el informe |

La lectura posterior no encuentra procesos del UID propio vivos. No se envió
ninguna señal, no hubo timeout, ni se arrancó Wine. Este control no desbloquea una
corrida Wine previa ni sustituye la política de reinicio de ese supervisor.

El listado de extensiones GL está truncado explícitamente: no interpretar una
extensión no incluida en ese fragmento como ausente. El resultado demuestra este
camino EGL/GLES y el triángulo medido, no todos los features que requiere VirGL.

## Servidor vtest: gates que siguen pendientes

El servidor GameNative custom continúa siendo incompatible con Mesa vtest. No se
conectó el cliente al servidor ni se copió el candidato Mesa al teléfono.

- Mesa `flush_frontbuffer` es un callback interno del cliente. En el camino
  estándar puede solicitar `TRANSFER_GET/GET2`, copiar al display target y usar
  XShm/XPutImage. **No es el opcode 9 del servidor custom GameNative.** Que exista
  ese callback no demuestra que haga falta el protocolo custom para presentación.
  La ruta GLX/DRI efectiva y su integración X11 todavía no se han probado.
- La receta Termux de libepoxy elimina el branch Android de nombres de bibliotecas:
  el candidato busca `libEGL.so.1` y `libGLESv2.so.2`, no las bibliotecas Android
  que utilizó esta sonda. No asumir que GLVND carga el EGL nativo de Mali.
- El source mirror `Container-On-Android/virglrenderer/1.3.0-8148de0`, en
  `src/vrend/vrend_winsys_egl.c`, describe GLES con un contexto `EGL_NO_SURFACE` y
  requisitos de `EGL_KHR_create_context` / `EGL_KHR_surfaceless_context`. No es
  el pbuffer medido aquí ni una prueba de identidad con el tarball oficial Termux.
- Los flags vtest `--use-gles` / `--use-egl-surfaceless` no garantizan por sí solos
  esa inicialización en Android. Comprobar source/build, búsqueda privada y
  handshake/caps/transfer antes de integrar WineD3D o publicar un perfil.

Por tanto: **EGL/GLES nativo comprobado; loader y servidor vtest compatibles aún
UNKNOWN; ningún backend WineD3D, frame WoW ni login aceptado**.

## Referencias exactas

- Recetas Termux:
  `27da60be397a6e9eb898bba8d03bcd648782ccd5`,
  `packages/libepoxy/libepoxy-1.5.3_src_dispatch_common.c.patch`,
  `packages/virglrenderer/{build.sh,disable-android-detection.patch,vtest-vtest_protocol.h.patch}`.
- [Mirror del EGL vtest](https://raw.githubusercontent.com/Container-On-Android/virglrenderer/1.3.0-8148de0/src/vrend/vrend_winsys_egl.c): evidencia de diseño, no identidad del paquete.
- `docs/virgl-bionic-candidate-status.md`: hashes/cierre HOST y diferencias de protocolo.
