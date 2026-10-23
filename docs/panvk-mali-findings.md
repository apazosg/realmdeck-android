# PanVK y el Mali-G610 del laboratorio

Investigación de texto/código del 2026-10-03; no se instaló ni ejecutó PanVK.
El dispositivo conectado sigue siendo `DEVICE_SERIAL`, xaga/Android 14,
Mali-G610 MC6. No confundirlo con el Adreno de las referencias ni el Poco PowerVR.

## El hardware sí tiene un camino upstream

La [documentación oficial Mesa](https://docs.mesa3d.org/drivers/panfrost.html)
declara PanVK conforme en Mali-G610 y enumera Vulkan 1.4 para G610/v10. Por tanto,
el Vulkan 1.1.177 del driver Android actual no prueba un límite absoluto del GPU.

Esto no valida el port para el kernel Android existente. PanVK upstream y los
ports que usan el interfaz propietario `mali_kbase` son combinaciones distintas;
compilar para ARM64 no demuestra compatibilidad de kernel, buffers o presentación.

## Dos ports Android concretos

### zenithblue-oss/panvk-kbase-android

Ref inspeccionada: `b38f05f1aaf6975d5ccb16a2ad5263c13518116c`.

- [README fijado](https://github.com/zenithblue-oss/panvk-kbase-android/blob/b38f05f1aaf6975d5ccb16a2ad5263c13518116c/README.md)
  informa cargas DXVK Native/D3D11 FL11_0 en G615/Poco X6 Pro. Es evidencia del
  autor, no una prueba nuestra ni de WoW AA64/G610.
- [Perfil G610](https://github.com/zenithblue-oss/panvk-kbase-android/blob/b38f05f1aaf6975d5ccb16a2ad5263c13518116c/profiles/g610-v10-csf.json):
  `planned P24 (not yet validated)`, arch 10, CSF, kbase; sin identidad GPU/UAPI
  cualificada. No hay binario release G610 probado.
- Arquitectura Bionic: ICD cargable dentro del proceso, kbase ioctls y
  `/dev/mali0`. **No requiere conceptualmente reemplazar el HAL global**. Que esta
  arquitectura pueda ser privada no prueba que funcione en nuestro teléfono.
- La serie de patches cualificada es `csf-v11`; `apply-patches.sh` elegiría
  `csf-v10` para G610, pero esa serie no existe en el ref. El packaging conserva
  defaults G615/UAPI 1.21; no basta cambiar el nombre del perfil.
- Beta.9 ofrece un driver Android aarch64 de 30.681.408 bytes, SHA
  `5196385512663b7e91cfb3e75b9667b20f766e00fb09c587bb38d3ec1647dadd`.
  **No descargado**: está cualificado para G615, no es una prueba G610.

### BkaNeko/panvk-mtk-driver

Ref inspeccionada: `594efbf6d2038df81ce0a75603cce72f4c866879`.

- Su [README](https://github.com/BkaNeko/panvk-mtk-driver/blob/594efbf6d2038df81ce0a75603cce72f4c866879/README.md)
  sí apunta a xaga/G610, Android 15 y kernel 5.10.237.
- El despliegue publicado usa `su`, remount de `/vendor` y reemplazo de
  `vulkan.mali.so`, no un control privado de nuestro launcher.
- [Patch inspeccionado](https://github.com/BkaNeko/panvk-mtk-driver/blob/594efbf6d2038df81ce0a75603cce72f4c866879/patches/panvk_mtk.patch):
  declara geometry/BC/sparse/float64, entre otras capacidades, aun cuando algunas
  no están implementadas, porque HWUI no las ejercita. Eso no sirve para demostrar
  D3D11/WoW y contradice nuestro gate de capacidades reales. No copiar ese patch
  completo ni instalar su HAL para obtener una enumeración aparentemente mejor.

## Lecturas propias, sin ejecutar un driver

- Kernel actual: `5.10.209-android12-9-00019-g4ea09a298bb4-ab12292661`.
- `/dev/mali0`: dispositivo char `10,113`, modo `0666`, owner system/graphics.
- `run-as com.wowforever.arm64probe`: comprobaciones `test -r` y `test -w` pasan.
  Solo prueban permisos de acceso; **no se abrió ni envió ioctl al GPU**, no se
  midió el handshake kbase/CSF ni se aceptó compatibilidad SELinux del nuevo ICD.

Un candidato privado G610 necesita primero identidad GPU/UAPI, cualificación
CSF-v10, dependencia/ABI y loader Bionic, y después instance/device/workload real.
WSI/X11 y la sonda D3D/present son puertas posteriores. No activar fake features,
no root ni tocar `/vendor`. Un rebuild sustancial de Mesa/NDK y la adaptación del
port requieren definir inputs/coste/alcance antes de iniciarlos.

## Decisión actual

PanVK es una alternativa **relevante**, no descartada por la familia G610. Ningún
port inspeccionado es todavía un reemplazo privado verificado para nuestro tuple.
Se mantiene la investigación en paralelo al candidato WineD3D/VirGL Bionic:
`closure-audit.json` comprueba los dos paquetes ya obtenidos, pero informa 18
SONAMEs ausentes y `ready=false`; no se instaló ese backend tampoco.

No hay otro APK, driver ni ensayo de render causado por esta revisión. La última
medición AA64 sigue siendo `DEVICE_REJECTED`, sin dispositivo D3D o presentación.

## Actualización 2026-10-06: PanVK sigue siendo una ruta prioritaria

El usuario solicita expresamente no descartar PanVK y continuar en el POCO
conectado. La revisión anterior queda como registro histórico, no como afirmación
de que G610 carezca de soporte upstream o de que el port siga limitado a su beta.9.

Ref nueva inspeccionada: `de577411e8f27da9de50aec26a0d8cc43890745e`
(2026-10-05). El [informe v10 fijado](https://github.com/zenithblue-oss/panvk-kbase-android/blob/de577411e8f27da9de50aec26a0d8cc43890745e/docs/universal/v10/README.md)
añade evidencia publicada de carga/enumeración de **G610 MC6**, GPU ID
`0xa8670000`, en otro teléfono `23054RA19C`, Android 15/kernel 5.10 Android-12.
No es nuestro `22041216G`/Android 14/kernel 5.10.209.

- Los intentos publicados de D3D10/11 usan beta.14 y DXVK 3.1.1; DXVK descarta el
  adaptador por `textureCompressionBC=false` antes de `vkCreateDevice`.
- No hay un PanProbe v10 publicado, ni ejecución de queues, heap, shaders o
  workload offscreen en esos registros. El UAPI exacto no está medido allí.
- El documento identifica como hipótesis una decisión de emulación BC incorrecta
  ante soporte nativo parcial; no valida su arreglo. Emulación implementada sería
  admisible; anunciar BC sin implementación no lo sería.
- Se describen riesgos de CSF antiguo: layout de queue-group intermedio 1.18,
  tiler heap y orden EXEC_INIT/JIT_INIT. La advertencia EXEC_INIT EPERM no demuestra
  todavía un fallo de shader en v10 porque no llegó a ejecutarse ese trabajo.
- El perfil `g610-v10-csf` todavía dice planned/not validated. Una build multi-arch
  no equivale a aceptar todas sus arquitecturas.

La [release beta.16](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/g615-v11-csf-v0.1.0-beta.16)
publica binarios v10–v14, pero su aceptación completa sigue siendo G615. Metadata
del ICD Android: 30.972.472 bytes, SHA
`c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`;
paquete `.adpkg.zip` 7.409.026 bytes, SHA
`f89824ff3b81c610eb6f7254ac8e16f7764c39d112c1bda8fa1427d13cbe1fc5`.
**Solo metadata inspeccionada; no descargado ni instalado.** Los resultados de
NFS/PanProbe anunciados en G615 no son una prueba propia de WoW o G610.

Lectura propia adicional, solo metadatos: nuestro POCO sigue en Android 14,
kernel `5.10.209-android12-9-00019-g4ea09a298bb4-ab12292661`, páginas de 4096 bytes.
La lectura de `/sys/class/misc/mali0/device/gpuinfo` como shell fue denegada; no se
elevan permisos ni se infiere GPU-ID/UAPI del teléfono de la publicación.

Decisión: **PanVK permanece como alternativa Vulkan prioritaria**, no descartada.
Su primer gate deberá identificar GPU-ID/UAPI y probar un ICD privado con
instance/device/trabajo fuera de pantalla antes de WSI/DXVK/WoW. Ni root/HAL global
ni el patch de BkaNeko con capacidades fingidas forman parte del alcance.
La investigación de fuentes continúa; adquisición/ejecución de ICD y cualquier
build sustancial requieren fijar payload, presupuesto y protocolo kernel.

El camino Mesa/VirGL ya superó carga, caps y triángulo nativo del servidor; el
guest real aún devuelve DRI BAD_VERSION al crear contexto. Ver
`docs/dri-return-observer-poco-results.md`. Ese rechazo no acepta ni descarta PanVK.

La medida final posterior, aprobada y registrada en
`docs/dri-version-limit-poco-results.md`, observó máximo core calculado0 antes y
después de la llamada. Se cierra el intento guest Mesa/VirGL actual sin cambiar
features. PanVK pasó a siguiente vía prioritaria; el gate de adquisición/kernel
estaba entonces draft. Fue aprobado posteriormente y se ejecuta por etapas según
`.agents/planning/panvk-poco-qualification-plan.md`, sin aceptar un driver por nombre.

## Gate propio aprobado: paquete host y metadata CSF

El usuario aprobó después la cualificación. ZIP e ICD beta16 se descargaron y
auditaron solo en host, con hashes/tamaños fijados. El paquete declara minApi35;
el POCO es API34, así que no se ha ejecutado el driver.

El nuevo control propio abrió el nodo char10:113 y obtuvo **CSF UAPI1.18**.
GET_GPUPROPS devuelve EPERM sin inicializar flags. Se detuvo ahí, con exit1 y
cierre limpio; no GPU-ID, allocator, queues, shader ni trabajo Vulkan probado.
Datos/resultados en `docs/panvk-poco-kernel-results.md`. La inicialización
SET_FLAGS=0 se propone en un draft separado, no se realizó automáticamente.

## Segundo gate propio: contexto metadata aprobado y GPU-ID real

Tras aprobación específica se ejecutó una copia nueva VERSION1.18 → SET_FLAGS0
una vez → GET_GPUPROPS723 bytes → close0. GPU-ID propio **0xa8670000**, texture
fields raw0xc1fe039e/0/0/0; sin GPU alloc/tracking/EXEC/queues/workload ni ICD.
El contexto se cerró limpiamente, sin procesos propios restantes. Ver resultado
`PVK-939499c38ec84523b7cd09ee91c87dfb` en `docs/panvk-poco-kernel-results.md`.

La restricción minApi35 es una política publicada, no prueba de calls API35-only:
las 269 importaciones del binario encuentran proveedores de nombre/tipo/versión
en los siete ELF capturados de este API34 (IFUNC incluidos estáticamente, no
ejecutados). No se ha probado el namespace o loader y no se cambió la metadata.
La carga privada fue aprobada y completada después: handle/exports correctos,
dlclose0, sin invocar Vulkan ni cambiar minApi35. Ver
`.agents/planning/done/panvk-api34-loader-plan.md` y
`docs/panvk-private-loader-poco-results.md`. GPU-ID/UAPI/loader no prueban colas,
BC, shaders o WoW. El posterior gate de instancia/physical metadata fue aprobado
y completado: G610/Vulkan1.4.363/185 extensiones, demote/sync2/dynamicRendering
true, BC y robustBufferAccess2 false. No device/colas/workload. Ver
`docs/panvk-instance-poco-results.md` y
`.agents/planning/done/panvk-instance-enumeration-plan.md`. El posterior control
device/cola fue aprobado y obtuvo `VK_ERROR_OUT_OF_DEVICE_MEMORY` (-2) antes de
crear un handle; transfer sin ejecutar. Ver `docs/panvk-device-queue-poco-results.md`
y el plan activo `.agents/planning/panvk-device-transfer-plan.md`.

beta.17 (ZIP `5ade7727…`, ICD `5d9fdb88…`) resolvió el bloqueo: el defecto
`KBASE_IOCTL_MEM_ALLOC_EX → ENOTTY` / `MEM_EXEC_INIT → EPERM` estaba en el driver
beta16, no en el kernel. Con beta.17 el mismo control obtuvo
`DEVICE_QUEUE_OBSERVED` (device result0, cola no nula) y después, con Gate B, un
**transfer real de 4 KiB verificado** (1024/1024 DWORD `0xa1b2c3d4`, fence
señalada, limpieza completa). Ver `docs/panvk-device-transfer-poco-results.md`.
Sigue sin shader/triángulo/presentación/DXVK/WoW; BC y robustBufferAccess2 false.

## Gate C: triángulo fuera de pantalla dibujado

El mismo ICD beta.17, sin cambios de APK/driver/HAL, completó un pipeline gráfico
fuera de pantalla: shaders SPIR-V (compilados con el `glslc` del NDK y validados
con `spirv-val`), render pass, framebuffer, graphics pipeline y `vkCmdDraw(3)`.
`TRIANGLE_OFFSCREEN_OBSERVED` con 512 píxeles de triángulo y 3584 de fondo,
fence señalada y limpieza completa. Un error de **nuestro** parser HOST (probaba el
bit de memoria `0x4` en vez de `DEVICE_LOCAL 0x1`) rechazó primero el mismo stdout;
corregido sin recompilar. `docs/panvk-triangle-poco-results.md`. Sigue sin WSI,
presentación, DXVK, D3D, WoW ni login; BC/R2 false.
