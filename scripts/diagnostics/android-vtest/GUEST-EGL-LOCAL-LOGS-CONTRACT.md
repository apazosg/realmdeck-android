# Rechazo EGL/DRI local: un replay solo de logs

Autorizado por el usuario tras la ronda de negociación. No cambia Mesa, Wine,
driver, features, contexto solicitado ni servidor. POCO xaga únicamente.

## Inputs fijos

- Fuente del control existente: `GuestEglContext.c`, SHA-256
  `32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f`;
  `GuestEglLoader.c`, SHA-256
  `4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6`.
- Servidor `android-negotiation`, manifest `e1aa871dc22f53e1a9bc68fd86a4d9838c4138d2c9ecf9a7da2d83b2cee37ba3`.
- Guest EGL manifest `30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9`,
  árbol `1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216`.
- Serial `DEVICE_SERIAL`, paquete `com.wowforever.arm64probe`, UID 10330.

## Única diferencia

El control nuevo es una compilación separada del mismo `GuestEglContext`, a
partir de copias verificadas de las dos fuentes. Un patch canónico fuzz 0 cambia
**solo la allowlist y comparación de entorno** del loader para exigir tres claves
adicionales exactas: `EGL_LOG_LEVEL=debug`, `MESA_LOG=file`, `MESA_LOG_LEVEL=debug`.
No `MESA_DEBUG`, overrides de versión/features, rutas de log arbitrarias ni
variantes de los atributos OpenGL 3.3 core. Los ocho valores previos se conservan.

Son controles de logging comprobados en el texto Mesa tag 26.2.4 (`egllog.c`,
`util/log.c`) y presentes como literales en las bibliotecas reales fijadas.
`MESA_LOG=file` dirige los logs a stderr por defecto y evita depender de logcat;
`MESA_LOG_LEVEL=debug` evita que el logger general descarte el nivel EGL debug.
El tag sirve de referencia, no demuestra equivalencia completa con el paquete.

## Artefacto y ejecución

- ELF nuevo `GuestEglLocalLogs` en directorio externo exclusivo, NDK24/API29,
  AArch64 PIE, solo libc/libdl, máximo 64 KiB; fuentes originales inmutables.
- Manifest liga inputs, patch, fuentes resultantes, recipe, compiler y ELF.
  No Mesa rebuild/download, no modificación de APK/runtime/client data.
- HOST registra ambiente efectivo antes de ejecutar; stdin cerrado, captura
  acotada y cleanup por handles/paquete propio iguales al control existente.
- Mismo servidor/guest separados, RUN privado, PID/startTicks/UID/argv y mapas.
- Preservar `CONTEXT_FAILED` si sucede. Los logs no habilitan PASS de contexto,
  gráficos, D3D, presentación, WoW o backend.
- Se acepta únicamente salida diagnóstica completa/limitada; truncación o mensajes
  desconocidos permanecen UNKNOWN, y los bytes se conservan para inspección.

## Gate de decisión

Si los logs ofrecen causa exacta y corregible, decidir por esa evidencia. Si solo
muestran el mapeo genérico `dri2_create_context` → `EGL_BAD_MATCH`, no fingir haber
identificado API/version/flag; cerrar este replay y fijar coste/alcance antes de
cualquier instrumentación sustancial de Mesa.
