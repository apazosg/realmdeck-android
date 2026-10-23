---
id: panvk-poco-qualification-2026-10-06
state: in-progress
date: 2026-10-06
---

## Summary

El usuario pide priorizar PanVK como vía Mali. Se completa el último gate de la
pareja Mesa/VirGL actual: máximo core calculado0, sin corrección pequeña demostrada.
Proponer ahora una cualificación **privada de PanVK en el POCO conectado**, no una
sustitución del driver Android ni otra APK sin resultado. La adquisición de ICD
y los nuevos accesos kernel/GPU requieren esta aprobación específica.

Solo `DEVICE_SERIAL` / POCO22041216G / Android14 / Mali-G610 / kernel5.10.209 /
páginas4096. No Pixel ni extrapolación del G615. Mantener la APK41, baseline DXVK,
datos y permisos actuales; parcheo/login permanecen después del arranque gráfico.

## Content

### Fuente y candidato fijados

- Referencia textual zenithblue-oss/panvk-kbase-android
  `de577411e8f27da9de50aec26a0d8cc43890745e`; ver `docs/panvk-mali-findings.md`.
- Metadata beta.16 `g615-v11-csf-v0.1.0-beta.16`: paquete Android `.adpkg.zip`
  7.409.026 bytes, SHA
  `f89824ff3b81c610eb6f7254ac8e16f7764c39d112c1bda8fa1427d13cbe1fc5`;
  ICD Android 30.972.472 bytes, SHA
  `c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
  No se ha descargado ni ejecutado. Las pruebas completas publicadas son G615;
  G610 tiene carga/enumeración en otro teléfono y rechazo BC previo a device.
- Upstream soporta G610, pero eso no cualifica este port contra nuestro kbase/CSF.
  GPU-ID/UAPI propios aún no medidos; no inferirlos del teléfono de otra persona.

### Gates y límites

1. **Auditoría host antes de ejecutar:** adquirir únicamente el ZIP fijado por
   HTTPS; longitud/hash exactos, archive paths/tamaños/ELF/SONAME/DT_NEEDED/exports,
   fuente/licencias y metadata de arquitectura reales. Rechazar sustituciones,
   paquetes distintos, escapes o ampliaciones ocultas. Conservar un manifiesto
   independiente, sin tratar autorreferencias como autoridad. Extraer solo el
   ICD/dependencias cualificados fuera del repo. No ejecutar helpers del paquete.
2. **Gate kernel mínimo:** con un control propio pequeño del UID autorizado,
   cualificar apertura propia de `/dev/mali0` y consultas documentadas de versión
   UAPI/GPU properties. Fijar números/structs contra las fuentes del port y kernel;
   tamaños acotados y error real. Sin ioctls desconocidos, escritura de registros,
   reset, acceso a otros procesos, root, `/vendor` o cambios de permisos. Si el
   ABI/API/SELinux no se puede demostrar, parar; no probar layouts al azar.
3. **ICD privado:** solo si pasan ABI/kernel/dependencias, cargar el ICD fijado en
   un hijo propio nuevo y medir instance/adapter/features. No instalar HAL ni
   sustituir el Vulkan del sistema o entorno de otras apps. Crear device/queue
   únicamente tras identificar el tuple real; errores y features ausentes son
   resultados, no se cambian bits para superar el gate.
4. **Trabajo acotado fuera de pantalla:** si device/queue funcionan, un control
   propio mínimo de submit/fence/readback, con plazo y recuperación del paquete
   propio. Shader/triángulo/present se cualifican después por separado; no mezclar
   WSI, Wine ni DXVK en el primer fallo kernel. Posible GPU hang implica parar y
   conservar datos; no insistir en layouts/colas no cualificados.

Solo un candidato y un control por gate; revisión independiente antes de accesos
kernel/ejecución. stdin cerrado, capturas persistidas antes del exec, output y
deadlines acotados, UID/PID/startTicks/RUN reales y limpieza de handles propios.
No nuevo servicio, sesión/grupo, señal ignorada, ptrace ni contención inventada.
No cuenta Battle.net/Forever ni memory patch durante esta cualificación.

### Presupuesto y criterio de parada

- Descarga fijada <=8 MiB comprimidos (ZIP), ICD <=32 MiB, expansión/workspace
  externos <=128 MiB. No segundo runtime, Mesa/Wine/LLVM/NDK rebuild ni otros
  payloads sin nueva decisión. Código de probes propios con toolchain ya existente.
- Si falta una dependencia, el binario no corresponde a la arquitectura/kernel,
  falla el acceso o se precisa parche/build sustancial: documentar requisito,
  tamaño/coste y parar antes de ampliarlo. No renombrar metadata G615 para aceptar
  G610, no HAL/root ni capacidades fingidas como en el patch BkaNeko.
- Device/submit/readback no implican presentación, D3D/WoW ni estabilidad. Solo si
  los gates reales pasan se propone integrar el perfil en la APK y repetir la
  sonda D3D existente antes de WoW. No otra APK por el mero hecho de bajar un ICD.

## Implementation / Action Items

- [x] 1. Revisar evidencia PanVK reciente y cerrar el control numérico Mesa/VirGL.
- [x] 2. Aprobar adquisición del candidato y nuevos controles kernel/ICD privados.
  Usuario: «si adelante». Se ejecutan los gates en orden, no una instalación global.
- [x] 3. Auditar bytes/dependencias/provenance y definir contratos ABI de probes.
  ZIP/ICD fijados; metadata minApi35 contra Android34 sigue no cualificada.
- [x] 4. Revisar y ejecutar el gate UAPI/GPU-ID bajo UID propio.
  VERSION1.18 + SET_FLAGS0 aprobados, 723 bytes y GPU-ID0xa8670000. Solo metadata;
  no equivale a aceptar todos los layouts u operaciones kernel del ICD.
- [ ] 5. Si procede, calificar ICD/device/trabajo mínimo y conservar resultados.
- [ ] 6. Decidir integración DXVK/WoW solo a partir de aceptación real.

## Open Issues

- **Instancia/metadata propias completadas:** RUN
  `PVK-81cc697088b7492395d471c73399bbcc` crea/destroye instance sin layers/ext;
  un Mali-G610 MC6 directo con API1.4.363/185 extensiones/queries0. Demote,
  sync2/dynamicRendering true; BC y robustBufferAccess2 false. No device o GPU
  work. El gate cerrado está en `done/panvk-instance-enumeration-plan.md`;
  resultados `docs/panvk-instance-poco-results.md`. Device/transfer aún pendientes.

- **Primer control propio:** RUN `PVK-81bf96c79b0f452cab3263ce5e7a7987` mide CSF
  UAPI1.18; GET_GPUPROPS devuelve EPERM sin SET_FLAGS. Status PROPERTIES_BLOCKED,
  native/host exit1, ningún proceso propio restante. No ICD ejecutado ni flags
  inicializados. Ver `docs/panvk-poco-kernel-results.md`.
- **Segundo control aprobado:** RUN `PVK-939499c38ec84523b7cd09ee91c87dfb` completa
  SET_FLAGS0 y GET723 bytes; GPU-ID0xa8670000, texture0xc1fe039e/0/0/0,
  cierre0 y sin procesos propios restantes. Plan metadata cerrado en
  `.agents/planning/done/panvk-kbase-context-metadata-plan.md`.
- **Gate ICD anterior:** beta16 conserva minApi35 para POCO API34. Auditoría de
  siete proveedores estáticos/API34: 269 imports con nombres/tipos/versiones
  presentes (12 IFUNC no ejecutados en esa auditoría), entonces sin prueba dinámica.
  Ver `docs/panvk-api34-symbol-audit.md`.
- **Loader propio completado después:** RUN PVK-ec1a455021d0451aa5e30afcddf0d115,
  PRIVATE_ICD_LOADER_OBSERVED/native0, handle y tres exports del owner privado,
  hash/identity estables/dlclose0/sin timeout o procesos restantes. No Vulkan
  invocado ni metadata renombrada. Plan cerrado
  `.agents/planning/done/panvk-api34-loader-plan.md`; resultado en
  `docs/panvk-private-loader-poco-results.md`. Instancia/enumeración posterior
  completada en `.agents/planning/done/panvk-instance-enumeration-plan.md`;
  El alcance device/transfer fue aprobado; su gate A se ejecutó y falló en
  vkCreateDevice(-2), sin VkDevice ni cola. Gate B detenido en
  `.agents/planning/panvk-device-transfer-plan.md`; evidencia
  `docs/panvk-device-queue-poco-results.md`.

- Alcance aprobado; adquisición auditada y controles propios en preparación.
  No ICD instalado ni nuevo ioctl ejecutado al aprobar este plan.
- GPU-ID propio0xa8670000 medido; la consulta CSF1.18 observada no demuestra otros
  ioctls compatibles. El informe de otro G610 no es autoridad.
- Emulación BC del port y soporte de su queue/tiler/EXEC_INIT en v10 antiguos no
  aceptados. No falsificar features ni eludir error kernel para aparentar éxito.
- Native `run-as` y Activity Android tienen dominios distintos; antes del producto
  habrá que validar carga, WSI/present y estabilidad dentro de la APK.
