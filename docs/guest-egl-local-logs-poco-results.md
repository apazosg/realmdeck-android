# POCO: logs EGL locales observados; error DRI interno aún oculto

2026-10-04. Una repetición autorizada, únicamente de logging en el hijo nativo.
No modifica APK41, Mesa, Wine, DXVK, driver, datos, atributos de contexto ni
capacidades. No se recompila Mesa ni se activa parcheo de memoria.

## Inputs y guardas

- Contexto original: SHA `32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f`.
- Loader original: `4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6`.
- Patch `guest-egl-local-logs.patch`, SHA
  `46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d`:
  solo amplía el entorno permitido para exigir `EGL_LOG_LEVEL=debug`,
  `MESA_LOG=file`, `MESA_LOG_LEVEL=debug`; todos los valores anteriores intactos.
- Copias de fuentes verificadas, patch fuzz 0 y toolchain NDK24 existente.
  `GuestEglLocalLogs`: 36.544 bytes, AA64 PIE, libc/libdl únicamente, SHA
  `ba0db20f632c23cd250b7c3dd4e9b31b2a74c99f2ea221480da415cfff59a3b6`.
- Servidor logging `android-negotiation`, manifest `e1aa871...`, guest `30c438...`
  / tree `1cc803...`, idénticos a la ronda anterior.
- Seis pruebas nativas/build pasan; quince HOST tests afectados pasan sin skips;
  review independiente sin P0/P1 para este replay acotado. Captura antes de exec,
  stdin cerrado, pipes limitados, identidad real del servidor y cleanup retenidos.

Las referencias tag Mesa 26.2.4 (`egllog.c`, `util/log.c`) describen esos controles;
los literales existen también en los ELF fijados. El tag no prueba reproducibilidad
del paquete Termux ni equivalencia total del binario.

## Control real y resultado

`physical-trials/xaga-20261002/native-LOCAL-EGL-VTEST-a4d6c75bc4c74b6fa59941915053d832`.
Servidor PID21729/startTicks11308614/UID10330; hijo21755. Ambos identificados en
RUN `VTEST-a4d6c75bc4c74b6fa59941915053d832`, namespace privado runas_app.
Servidor exit0, hijo exit1, pipes completos, sin truncación/timeout/force-stop ni
procesos restantes. Stdout hijo6003, stderr12830 bytes; servidor90/1655 bytes.

Se observan 115 filas diagnósticas de Mesa y el warning de caché opcional:

```text
MESA-EGL: debug: EGL user error 0x3001 (EGL_NOT_INITIALIZED) in eglInitialize: DRI2: failed to load driver
MESA-EGL: debug: Falling back to surfaceless swrast without DRM.
...
MESA-EGL: debug: EGL user error 0x3009 (EGL_BAD_MATCH) in eglCreateContext: dri2_create_context
```

El primer error es anterior al fallback: el display finalmente inicializa EGL1.5,
elige config1 y enlaza OpenGL. El nombre swrast es la ruta de carga EGL; no prueba
por sí solo llvmpipe ni ausencia de virpipe, y el intercambio vtest sigue observado.
El rechazo final continúa siendo CreateContext3.3 core/EGL_BAD_MATCH, no un fallo
de init sin fallback. No contexto current, strings GL ni triángulo guest.

Protocolo solicitado/aceptado4; GET_CAPS2 devuelve1408 y GET_CAPS1 devuelve308,
GLSL430/HOST_IS_GLES. Logs efectivos pero no distinguen el enum DRI de retorno.
Resumen **LOCAL_EGL_LOGS_OBSERVED / actualContextStatus=CONTEXT_FAILED**:
`exactDriErrorIdentified=false`, contexto/PRESENT/D3D/WoW no aceptados.

## Una posibilidad más pequeña que rebuild Mesa

Inspección estática de los ELF reales:

- `libEGL_mesa.so` importa `driCreateContextAttribs` como FUNC GLOBAL DEFAULT UND.
- `libgallium-26.2.4.so` exporta la función FUNC GLOBAL DEFAULT, VA `0xaa856c`,
  size896. No se ha interceptado, parcheado ni invocado directamente.
- Referencias Mesa26.2.4 `dri_util.h/c` declaran la función de nueve parámetros,
  incluido bool `thread_safe`; `mesa_interface.h` define errores DRI. Algunas
  typedefs de interfaces antiguas tienen ocho parámetros: **no usarlas** como ABI
  de esta función. Validación del contrato contra el binario sigue siendo un gate.

Un observador privado de esa única llamada podría conservar argumentos y resultado
y registrar el error antes de que EGL lo agrupe. Es una propuesta separada que
requiere aprobación/review y tests de ABI/forwarding; no una corrección ya aplicada.
No fingir éxito si el símbolo no se intercepta ni extrapolar al driver físico.

## Resultado posterior del observer aprobado

La propuesta anterior se ejecutó después bajo un contrato separado y revisión.
El retorno original es **DRI3/BAD_VERSION** para API core/major3/minor3, sin
alterar llamada, retorno o errno. Es un diagnóstico cumplido, no contexto gráfico
aceptado. Ver `docs/dri-return-observer-poco-results.md` y
`.agents/planning/done/dri-return-observer-plan.md`. No cambia retrospectivamente
la clasificación `exactDriErrorIdentified=false` del control de logs de esta nota.
