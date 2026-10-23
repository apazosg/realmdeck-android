# PanVK: informe preparado para el autor (G610 / CSF 1.18)

2026-10-07. Documento preparado, **no enviado**. Recoge la tupla exacta de nuestro
POCO y tres preguntas concretas. No contiene credenciales ni datos personales.

## Tupla medida (control privado, sin root ni HAL)

- Dispositivo: POCO `22041216G` (xaga), SoC `MT6896`/`mt6895`, Android 14 / API34,
  kernel `5.10.209-android12-9-00019-g4ea09a298bb4-ab12292661`, páginas 4096.
- GPU: **Mali-G610 MC6**, `vendor 0x13b5`, `deviceID 0xa8670000` (leído por dos vías
  propias: kbase GET_GPUPROPS y `vkGetPhysicalDeviceProperties`).
- kbase CSF UAPI observada: **1.18** (VERSION_CSF), SET_FLAGS0 correcto.
- ICD: `libvulkan_panfrost.so` del paquete beta16, 30.972.472 bytes, SHA-256
  `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
  El paquete declara `minApi=35`; el dispositivo es API34.
- Carga privada: `dlopen` OK, los tres exports ICD con owner exacto, `dlclose` 0.
- Instancia: API anunciada **1.4.363**, solicitada 1.3.0; sin layers ni extensiones.
- Físico único: 185 extensiones completas; features reportadas incluyen
  `shaderDemoteToHelperInvocation`/`synchronization2`/`dynamicRendering` true,
  `textureCompressionBC` false y `robustBufferAccess2` false.
- Colas: **una** familia, `flags=7` (graphics+compute+transfer), `queueCount=2`.

## Fallo reproducible

`vkCreateDevice` con **una** `VkDeviceQueueCreateInfo` (familia0, count1,
priority1.0), `pEnabledFeatures=NULL`, layers0/extensions0/allocatorNULL:

```text
resultado = -2  (VK_ERROR_OUT_OF_DEVICE_MEMORY), sin handle
```

No se llegó a `vkGetDeviceQueue`. Instancia destruida y `dlclose` correctos, sin
procesos propios restantes ni timeout. Repetible con el mismo binario.

No está probado que sea agotamiento de memoria física del teléfono.

## Causa exacta capturada por logcat

Una captura acotada de `logcat` (etiqueta `MESA`, `__android_log_write`) durante
el mismo control A registra:

```text
W MESA: kbase: KBASE_IOCTL_MEM_EXEC_INIT failed: Operation not permitted
W MESA: kbase: KBASE_IOCTL_MEM_EXEC_INIT failed: Operation not permitted
E MESA: kbase: KBASE_IOCTL_MEM_ALLOC_EX failed: Inappropriate ioctl for device
```

Interpretación: el port usa `KBASE_IOCTL_MEM_ALLOC_EX`, que este kernel CSF 1.18
**no implementa** (`ENOTTY`), y `KBASE_IOCTL_MEM_EXEC_INIT` devuelve `EPERM`. La
asignación interna del buffer falla y `vkCreateDevice` lo traduce en `-2`. Es un
problema de UAPI de kernel, no de memoria física ni de la GPU.

Las variables `vendor.mesa.*` (incluida `vendor.mesa.log.level` y
`vendor.mesa.panvk.debug`) están **denegadas** para el UID de la app, así que no
podemos subir el nivel de log desde la app.

## Preguntas

1. **Procedencia:** el paquete beta16 declara `patchSeriesId`
   `sha256:5dabdb19eeae804944a72a743cb80c82235cff057fd0ada5f570b962c013bbf7`,
   pero `sources.lock` del tag beta16 y de `main` declaran `374b7b83…`. ¿Qué serie
   exacta produjo el ICD `c0e54909…`?
2. **CSF 1.18:** en el código consultado, `kbase_kmod_csf_group_create` prueba el
   layout de 112 B (≥1.25) y cae directamente al de 1.6; el intermedio de **40 B
   (1.18)** no se intenta. ¿Es ese el caso de nuestro `-2`? ¿Existe una build
   cualificada para G610/CSF 1.18, o la serie beta.17 («layouts probados en orden
   de versión») lo resuelve?
3. **Diagnóstico:** ¿qué variable o ruta de log (por ejemplo `PANVK_DEBUG` /
   `mesa_loge`) registra el `ioctl`/asignación concreta que devuelve ENOMEM, para
   poder localizarlo sin reconstruir Mesa?

## Datos ya capturados que podemos aportar si se piden

- `getprop` relevantes y `gpuinfo` (si el acceso lo permite) y `logcat -d`
  filtrado por `kbase:`/`panvk`, si el autor lo solicita.
- Evidencia completa del control en
  `physical-trials/xaga-20261002/native-PVK-39337e01c1bc453d82b61371e0ba667b/`.

## Límites

No se falsifican features, no se renombra metadata G615→G610 y no se sustituye el
HAL ni se usa root. El objetivo es una build o guía cualificada para este kernel.
