# POCO: PanVK auditado; metadata propia CSF 1.18 y GPU-ID medidos

2026-10-06. Primeros gates del plan PanVK aprobado. Solo el dispositivo
`DEVICE_SERIAL`, POCO 22041216G/xaga, Android 14/API 34. No se modifica el
driver, APK41, Wine, DXVK, ImageFS, datos, permisos ni el HAL.

## Candidato, sin ejecución del ICD

- Release fijada `g615-v11-csf-v0.1.0-beta.16`, paquete Android 7.409.026 bytes,
  SHA `f89824ff3b81c610eb6f7254ac8e16f7764c39d112c1bda8fa1427d13cbe1fc5`.
- ICD extraído exclusivamente en host, 30.972.472 bytes, SHA
  `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
- Auditoría: ELF64 LE AArch64 ET_DYN, SONAME `libvulkan_panfrost.so`, sin
  PT_INTERP, segmentos PT_LOAD alineados a 16 KiB; RUNPATH
  `$ORIGIN/../../android_stub`. Exporta los entrypoints ICD estándar, no el
  `vkGetInstanceProcAddr` público: un futuro loader debe respetar ese ABI.
- DT_NEEDED: libc, libdl, liblog, libm, libnativewindow, libsync y libz. La lista
  Android allowlisted no prueba resolución/namespace/símbolos en el teléfono.
- El paquete declara **minApi 35**, con la nota «until API 29 rebuild is proven»,
  perfil G615/v11 y UAPI 1.21. El POCO es API34/G610: no se renombra la metadata ni
  se considera aceptado ese tuple. No ICD cargado, constructor ni trabajo GPU.
- No licencia dedicada en el ZIP; SOURCE/VALIDATION/metadatos son evidencia del
  paquete, no auditoría completa para redistribución pública.
- Manifiesto host independiente: `controls/panvk-audit-beta16/candidate-manifest.json`
  fuera del repo, `ready=false`. Doce tests offline de autoridad, ZIP y ELF pasan.

## Control propio, sin inicializar flags/contexto

`scripts/diagnostics/android-panvk/KbaseMetadata.c` permite únicamente:

1. Abrir `/dev/mali0` como UID10330 con CLOEXEC/NOFOLLOW, validar char10:113 e
   identidad por lstat/fstat.
2. Una llamada CSF `VERSION_CHECK` `0xC0048034`, estructura de 4 bytes `{0,0}`.
   No fallback JM ni números/layouts alternativos.
3. Si la versión CSF es 1.x, `GET_GPUPROPS` `0x40108003`, probe tamaño cero y,
   únicamente si devuelve tamaño válido, fill propio acotado a 16 KiB.

No SET_FLAGS, mmap de tracking/GPU, JIT/EXEC_INIT, memoria/colas/shaders/reset,
driver dlopen ni señales. La consulta de versión afecta solo a ese fd y sus
campos de handshake; no establece aceptación completa de UAPI/PanVK.

Artefacto 16.696 bytes, AA64 PIE/API29/libc-only, SHA
`0714a261d69b54793b7f8018cc8b445016dde7d6fad0e129e9cc7c70fe3cda06`;
manifest SHA `0c83c84fe5ed2db7ede6f30b65c8e00cd76012aeeaa675d600c498b58678ccec`.
Native 23 tests host/mock/encoder/build pasan; HOST runner final 17 tests pasan.
Review independiente no P0/P1 antes del control; no se repiten suites GPU previas.

## Resultado físico

Carpeta externa `physical-trials/xaga-20261002/native-PVK-81bf96c79b0f452cab3263ce5e7a7987`.
RUN `PVK-81bf96c79b0f452cab3263ce5e7a7987`, PID8931/UID10330, PGID/SID8929.

- Apertura e identidad del fd válidas antes y después.
- **CSF VERSION_CHECK: ret0/errno0, major1/minor18.** Es nuestra primera medición
  propia de UAPI1.18, no una inferencia del otro teléfono publicado.
- **GET_GPUPROPS tamaño: ret-1, errno EPERM (1).** No fill, GPU-ID/texture flags
  permanecen desconocidos. No se intentó desbloquear mediante SET_FLAGS.
- Status `PROPERTIES_BLOCKED`, native y runner exit1, stdout997 bytes/stderr0,
  pipes completos; duración nativa80.384 ns, cierre0/errno0.
- Sin timeout, force-stop, errores de ownership/evidencia ni procesos propios
  restantes. No Wine arrancado ni cambio del límite de reinicio tras Wine.

El port fijado describe VERSION_CHECK → SET_FLAGS como inicialización requerida
para las otras consultas. Eso concuerda con EPERM, pero **no prueba por sí solo
su causa exclusiva frente a políticas del kernel/SELinux**. No declarar la GPU
incompatible ni atribuir GPU-ID de otra máquina.

## Decisión

No continuar con el ICD: el gate de propiedades está incompleto y el paquete
declara un nivel Android posterior al dispositivo. Proponer por separado un
control de metadata que inicialice explícitamente solo el contexto propio de fd
con SET_FLAGS=0, sin tracking/alloc/queues/work. La compatibilidad API34 del ICD
requiere calificación explícita o una build compatible fijada; no resolverla
modificando un número de metadata.

Ningún dispositivo Vulkan, submit, triángulo, presentación, D3D ni WoW está
demostrado por esta consulta de kernel.

## Ampliación aprobada: un contexto propio con flags cero

El primer control anterior permanece intacto. Tras aprobación específica se añadió
una copia `KbaseContextMetadata`, sin tracking/mmap, memoria GPU, JIT, EXEC, colas,
shaders, prioridades, reset ni carga del ICD. Orden fijo en un solo fd:
VERSION_CSF; únicamente para 1.18, SET_FLAGS `{create_flags=0}` `0x40048001` una
vez; GET_GPUPROPS size/fill <=16 KiB; fstat/close. Versión distinta o cualquier
error impide continuar. Same serial/UID/RUN/char10:113 y deadlines5s/10s.

- Artefacto AA64 PIE/libc-only, 17.216 bytes, SHA
  `e739ce1ee26c46d5167af21de703d7d7264ebf313c544a36253ac01b4735ec2f`;
  manifest `3082bac1793efa10e2f77424434b13912bec927c8ac9bf2391af673cee162b94`.
- Build y 23 casos dirigidos iniciales pasan; después de review se ejecutan
  16 casos HOST de parser/path y un caso adicional de cleanup ante timeout mkdir.
  Las fuentes del control anterior mantienen sus tres hashes publicados.
- Carpeta: `physical-trials/xaga-20261002/native-PVK-939499c38ec84523b7cd09ee91c87dfb`.
  PID11044/UID10330, PGID/SID11042; contexto SELinux runas_app.
- VERSION1.18, SET_FLAGS0 ret0/errno0; GPUPROPS size723/fill723, ambos errno0.
  **GPU-ID `0xa8670000`**. TEXTURE_FEATURES keys52=`0xc1fe039e`,53=0,54=0,81=0.
  Valores raw medidos, no una declaración de formatos Vulkan implementados.
- `PROPERTIES_OBSERVED`, native/host exit0, duración1.376.539ns; close0/errno0,
  identidad fstat estable, stdout1173/stderr0, pipes completos, sin timeout,
  force-stop, errores ni procesos propios restantes.

La metadata ya no está bloqueada tras esta secuencia en nuestro fd; eso no demuestra
que cualquier EPERM tenga esa causa. El contexto propio se cerró y **no se cargó
el driver ni se enviaron comandos GPU**. No actualizar el manifest estático a
`ready=true`. El gate minApi/loader y los de colas/shaders siguen pendientes.

Plan acotado cerrado: `.agents/planning/done/panvk-kbase-context-metadata-plan.md`.
Auditoría siguiente solo de archivos: `docs/panvk-api34-symbol-audit.md`.
