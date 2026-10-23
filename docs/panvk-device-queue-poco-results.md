# POCO: PanVK device/cola falla en `vkCreateDevice` (-2)

2026-10-07. Ensayo A aprobado en `.agents/planning/panvk-device-transfer-plan.md`.
Solo POCO xaga `DEVICE_SERIAL`, API34/debug41, UID10330; mismo ICD beta16
privado SHA `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
No se instaló un HAL ni se modificaron Wine, DXVK, runtime, cliente o APK.

## Inputs y gates previos

- Probe propio AA64 PIE 36.248 bytes SHA
  `2057f400d7a239ce7daad12f4b873e42b2e8111891ff8effa9b484c65e3bdf90`,
  manifest SHA `94ac8346e747509b82cc474c9515c356d64d98e6702c6bd0ded8e62debaa0d29`.
  V1/V2 no se ejecutaron en el teléfono; V3 corrige la familia cero, retención de
  counts y limpieza de handles válidos incluso ante error de reloj/owner.
- 11 casos HOST del probe nativo y 17 del runner pasan en rondas dirigidas;
  fuente/build/payload/pins comprobados, review final sin P0/P1. No ARM en host.
- Entrada única: instance API1.3, sin layers/extensions; físico único medido
  vendor0x13b5/device0xa8670000/Mali-G610 MC6. Un `VkDeviceQueueCreateInfo`,
  familia0/count1/priority1.0; features `NULL`, layers0/ext0/allocatorNULL.
  No buffer/memoria/cmdpool/submit/fence/espera o shader en el código del control.

## Único ensayo real

Evidencia externa conservada:

```text
/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/
  native-PVK-39337e01c1bc453d82b61371e0ba667b/
```

RUN igual al sufijo, nativePID12162/UID10330/PGID+SID12160. Namespace propio
`run-as`, host ADB1686065. Exportaciones/PFNs con owner exacto del ICD,
interfaz7→7 result0; `vkCreateInstance` result0, enumeración física completa1/1/1,
GPU-ID correcto.

Familias query/fill completas: **1** familia index0, flags7
(graphics+compute+transfer), queueCount2. Selección determinista index0,
prioridad de etiqueta `transfer`; se solicita solo una cola.

`vkCreateDevice` devuelve **-2 = `VK_ERROR_OUT_OF_DEVICE_MEMORY`** (definición
Vulkan) y ningún handle válido. Por eso `vkGetDeviceQueue` no se invoca.
Instancia destruida, `dlclose` correcto, identidad/hash ICD estables antes/después.
Native/HOST exit1, `VK_ERROR`/failureStage `vulkan`, duración ~332 ms, stdout
5.369 bytes, stderr vacío, pipes completos, sin timeout/force-stop ni procesos
propios restantes. Todos los flags de workload, triángulo, presentación, D3D y
WoW son **false**. `stopBeforeTransferRequired=true`: **control B no se ejecuta**.

## Causalidad y siguiente decisión

El código público fijado del port contiene asignaciones GPU/VA internas durante
`vkCreateDevice` incluso con features `NULL`. Por ejemplo, `kbase_init_seqnos`
prepara un BO de eventos; `kbase_create_group` prepara ring BOs y ambos tienen
rutas que devuelven `VK_ERROR_OUT_OF_DEVICE_MEMORY` si allocation/mapping falla.
El kernel del POCO es CSF1.18; la implementación pública también documenta
incertidumbre de layouts intermedios. **No se registró cuál de esas rutas produjo
el -2 en esta compilación concreta**, y la patch-series declarada por beta16
`5dabdb19eeae804944a72a743cb80c82235cff057fd0ada5f570b962c013bbf7`
no coincide con la referencia `sources.lock` del head consultado; la equivalencia
fuente/binario no está demostrada. El error no prueba memoria física agotada.

No se cambian flags/caps, se inventa una cola o se reintenta otro layout.
La metadata PanVK anterior sigue válida; esta combinación **no pasa Gate A**.
Para seguir haría falta fuente/binario G610/CSF1.18 cualificado o un diagnóstico
de la asignación concreta con contrato, revisión y presupuesto separados.
No generar APK ni pasar a Wine/DXVK/WoW a partir de metadata o de este -2.

## Diagnóstico real del `-2` (captura de logcat)

Se añadió un runner de solo diagnóstico
`scripts/diagnostics/android-panvk/run_device_logging_usb.py` que repite el mismo
control A y volca `logcat` acotado desde la hora de inicio del hijo. RUN
`PVK-8dde8d6f1c334510969947835e643783`. El ICD emite sus trazas con etiqueta
`MESA` por `__android_log_write`; las variables `vendor.mesa.*` están **denegadas**
para el UID de la app, así que no se puede subir el nivel de log desde la app.

Líneas decisivas:

```text
W MESA: kbase: KBASE_IOCTL_MEM_EXEC_INIT failed: Operation not permitted
W MESA: kbase: KBASE_IOCTL_MEM_EXEC_INIT failed: Operation not permitted
E MESA: kbase: KBASE_IOCTL_MEM_ALLOC_EX failed: Inappropriate ioctl for device
```

Lectura: el port pide la asignación moderna `KBASE_IOCTL_MEM_ALLOC_EX` y este
kernel (CSF 1.18) **no la implementa** (`ENOTTY`). `KBASE_IOCTL_MEM_EXEC_INIT`
también devuelve `EPERM`. Al fallar la asignación interna del buffer, `vkCreateDevice`
convierte el fallo en `-2`. **Es un problema de compatibilidad de UAPI de kernel,
no de memoria física ni de la GPU.** Coincide con la hipótesis del layout/UAPI
intermedio CSF 1.18 que el port no recorría en orden.

Siguiente paso justificado: obtener una variante que **haga fallback al ioctl de
asignación antiguo** (`KBASE_IOCTL_MEM_ALLOC`) cuando `MEM_ALLOC_EX` devuelva
`ENOTTY`. Beta.17 documenta «layouts CSF probados en orden de versión», que es
exactamente esta clase de corrección; conviene auditar beta.17 antes de parchear
nosotros. No falsificar features, no renombrar metadata ni tocar HAL/root.

Evidencia completa en
`physical-trials/xaga-20261002/native-logging-PVK-8dde8d6f1c334510969947835e643783/`
(`logcat-filtered.txt`, `logcat.txt`, `summary.json`, `stdout.txt`).

## Beta.17 resuelve el bloqueo: dispositivo y cola creados en G610

Tras la aprobación del usuario se auditó y adquirió el paquete oficial beta.17
(`g615-v11-csf-v0.1.0-beta.17`, commit `b4ee2e2490ffad0e6625ecbd78ef016bdf6cf5ce`,
publicado 2026-10-07T00:31Z) y se repitió el mismo Gate A sin cambios de HAL,
root, permisos ni APK. Su changelog «Universal changes (built, NOT tested on that
hardware)» incluye justamente las tres hipótesis: BC emulado salvo máscara nativa
completa, «kbase CSF uAPI layouts tried in version order, 16 KiB page support» y
`EXEC_INIT` antes de JIT. El release pide explícitamente probadores para v10
(G610/G710), que es nuestra clase.

- ZIP Android 7.466.046 bytes SHA
  `5ade77270e5c51daa26979c98e31abcc30be9c70cdc79589576b5fcedc28d632`; ICD Android
  31.142.504 bytes SHA
  `5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`. Ambos fijados
  y auditados solo en host (`controls/panvk-audit-beta17/`).
- El helper compartido `PanvkPrivateLoader.c` fijaba el tamaño/hash del ICD beta16;
  se avanzó su pin a beta.17 (nuevo SHA
  `cc8ea0c352ff582de80ef493d6060504a5bd1b11057604a1d55dd59875b3a4b7`) y se reconstruyó
  el probe como v5 (ELF 36.248 bytes SHA
  `9544260ebe3cc3fdb987812e3fc3502f0be76efa392669574f42dcff95443e10`, manifest SHA
  `9fc297f8faee14715199ec2b3ea9d9f195db22d9e65956164fc5c455ff2f549f`). Los
  artefactos v1–v4 quedan como historial, no reejecutados.
- RUN `PVK-65a65bc814064b4aa280cf04db131805`, evidencia
  `physical-trials/xaga-20261002/native-PVK-65a65bc814064b4aa280cf04db131805/`.
  Interfaz 7→7, API 1.4.363, instancia creada, un dispositivo físico
  **Mali-G610 MC6** (vendor `0x13b5`, device `0xa8670000`, `targetExact=true`),
  una familia de colas índice0 flags7 queueCount2, selección `transfer`,
  **`vkCreateDevice` result0 con dispositivo creado**, **`vkGetDeviceQueue`
  nonNull/observed=true**, y limpieza completa (deviceDestroyOk/instanceDestroyOk/
  dlcloseOk). `status=DEVICE_QUEUE_OBSERVED`, exits0, `stopBeforeTransferRequired=false`.

Conclusión: la incompatibilidad CSF 1.18 que rompía `vkCreateDevice` **estaba en el
driver beta16, no en el kernel ni en la GPU**; beta.17 la resuelve. Esto **no** es
aceptación de render, WSI, presentación, DXVK ni WoW: solo demuestra que PanVK crea
dispositivo y cola en este G610 concreto. Gate B (transfer de 4 KiB) queda ahora
habilitado y separado, pendiente de implementar/revisar/ejecutar.
