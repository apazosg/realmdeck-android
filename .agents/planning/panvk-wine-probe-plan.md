---
id: panvk-wine-probe-2026-10-08
state: in-progress
date: 2026-10-08
---

## Summary

Usuario pide continuar hacia WoW después del triángulo PanVK visible en Android.
El gate pendiente no es repetir native Vulkan: es **cargar PanVK bajo Wine y
medir D3D11 con la sonda existente**. La investigación de la biblioteca real y
su fuente publicada encuentra una ruta custom ya implementada en el wrapper
Vulkan/Xlib. Ver `docs/panvk-wine-loader-findings.md`.

Se propone un único control cambiando solo el driver subyacente seleccionado
para el RUN de la sonda. La dirección general está aprobada; confirmar este
profile escrito aprobado («si hagámoslo»); se altera únicamente la selección
con los límites siguientes, antes de interpretar el resultado D3D.

## Content

### Inputs y baseline protegido

- Solo POCO xaga `DEVICE_SERIAL`, UID10330/API34/G610; no Pixel ni otros GPUs.
- APK47/0.2.23 y WSI aceptado se conservan, sin modificar reportes/candidatos.
  PanVK beta17 existente31.142.504 bytes SHA
  `5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9`,
  minApi35 publicado intacto, cualificaciones propias parciales documentadas.
- Actual wrapper SO19.021.832 bytes SHA
  `b598a54166790a6fc071f08495f141f92dc192f1eb339e029bfc7a303c7486ce` y JSON
  `wrapper_icd.aarch64.json` se conservan; es la ruta Xlib ya existente.
- Wine/Proton11.0-90624-arm64ec, DXVK2.4.1AA64, graphics probe89.600 bytes SHA
  `a19e6721647fae0f31052133c585c91194823883d6be9bb81c6d0d342abfee9f`,
  command/cwd/A-drive/flags/source/shaders/no-GL/memoria off igual al baseline41.

### Cambio único de selección

1. Policy debug congelada `PANVK_WINE_PROBE`, solo para la sonda gráfica gestionada
   y RUN actual verificable. Profile congelado al comenzar, no cambiarlo durante
   una corrida. Normal routes/WSI debug fuera del profile no activan la selección.
2. Provisionar por asset ICD **ya existente en APK** a nuevo directorio privado
   por RUN, archivo exclusivo0600/fsync/NOFOLLOW/canonical-owner/hash/size/ELF
   verificados; no registry/ZIP installer custom de GameNative ni root/HAL.
   Preservar original de ImageFS, baseline y cliente/cache compartidos.
3. En el último merge Bionic antes de exec, fijar únicamente:

   ```text
   ADRENOTOOLS_DRIVER_PATH=<directorio privado verificado del RUN>/
   ADRENOTOOLS_DRIVER_NAME=libvulkan_panfrost.so
   ADRENOTOOLS_HOOKS_PATH=<ImageFS propio>/usr/lib
   ```

   Exigir que la carpeta/hooks/librerías requeridas por la ruta estén preparados,
   existentes y dentro del runtime propio. No REDIRECT_DIR o flags nuevos.
4. Mantener VK_ICD_FILENAMES del wrapper, LD_PRELOAD completo SHM/evshim/WX,
   HODLL, display, renderer, Wine prefix, startup, DXVK DLLs, logs info y no-GL.
   No cambios WRAPPER_VK_VERSION/features/GL overrides para aparentar soporte.
   El nombre Adrenotools no prueba restricción ni compatibilidad con Mali.
5. Capturar en el proceso real de la sonda Wine el mapping privado PanVK y su
   byte hash, identidad PID/startTicks/UID/RUN/root propio, driver-info/feature
   report/modules del PE. No tratar la carga previa en `:panvk_wsi` como evidencia
   de esta selección. No PanVK bajo Wine o fallback System => CONTROL_INVALID.

### Ejecución y decisiones

- Un único intento USB AOA/ADB autorizado, después del reboot si el último RUN
  Wine lo requiere. El WSI nativo no altera esa política. Capture externo antes
  de UI, consent por ruta normal, journals/sonda/logs/mapas/screenshot propios.
- Mantener native ownership/one-spawn/cancel/generation/pins y límites de lectura.
  Host deadline máximo120s, sonda30s cooperativos; detener paquete propio tras
  preservar evidencia si falla/loop, no PIDs/grupos ajenos ni retry automático.
  Driver waits/kernel pueden no preemptarse: conservar UNKNOWN, no falso cleanup.
- D3D dispositivo con featurelevel no nulo, shader/readback/30 presentsS_OK son
  los gates reales de la sonda. Module compiler47 ausente u otra dependencia no
  cualificada se registra y para: no descargar otro DLL o bajar requisitos.
- PresentS_OK no es visual proof en este port; exigir además captura concordante
  del triángulo en la ventana/foreground propio antes de aceptar Wine/Xlib.
- Classificaciones separadas: control cargó driver; D3D creado; offscreen/readback;
  present API; pantalla observada. Failure siempre conservado, nunca cambiar
  `device rejected` a hardware universalmente no soportado o `VK_SUCCESS` a WoW.
- Solo si driver+D3D/present verdadero pasan se plantea después ejecutar el mismo
  WoW sin otra variable. No login/parcheo ni cambio de BC/R2 por este control.

### Verificación y presupuesto

Tests de flags/RUN/perfil privado/source pin/exclusividad/symlinks/aliases/copy
cancelled; entorno final preserva allruntimevars+wrapper mientras el profile off
es byte-exacto; mapas System-only no califican, nativeWSI-run ajeno no sirve.
Review independiente de provisión/contexto/scope+loader. Build offline singleworker,
receta fresca/idempotente, audit del APK real/cert/hash/assets/native, versión
nueva mismafirma/package; preservar47 y baseline40/41. No scripts públicos de
ejecución arbitraria, nuevo servicio, cuenta, root, SIM/permiso UI o driver del sistema.

No herramientas/descargas/sourcebuild driver/Mesa/Wine/NDK/paidcloud. ICD/probe/
runtime ya presentes, workspace nuevo external<=128MiB aparte de outputs Gradle
incrementales existentes. Si falta dependencia o ruta es incompatible, informar
el requisito y parar antes de escribir otro renderer/puente o ampliar presupuesto.

## Implementation / Action Items

- [x] 1. Localizar ruta custom/Xlib en fuente y wrapper real sin ejecución.
- [x] 2. Aprobar profile Wine/PanVK privado y el intento de sonda descrito.
  Usuario: «si hagámoslo».
- [x] 3. Fijar provisioning/contrato de entorno final y pruebas de selección.
- [x] 4. Revisar, compilar, auditar y probar una vez por USB con maps/PE evidence.
  Code48: datos parciales, no aceptación del control; no same-process PE/PanVK proof.
- [ ] 5. Decidir Wine/D3D/present y siguiente gate WoW sin promesas automáticas.

## Open Issues

- Profile escrito aprobado. No se amplía a otros drivers, DLLs, features o WoW
  por esta autorización; primer control es la sonda existente.
- Loader Android/HMI/namespace/custom routing y Wine/Xlib aún no cualificados;
  fuente publicada y binario difieren en opción REDIRECT_DIR. No afirmar igualdad.
- Las features BC/R2 y compiler47 no aceptadas bajo Wine; no copiar flags de
  Adreno, cambiar driverInfo o anunciar soporte solo por familia/metadata.
- NativeAndroidPresentation no implica Xlib ni D3D/WoW. MinApi35/G610 experimental
  siguen teniendo límites, aunque los controles nativos API34 hayan pasado.
- Intento code48: D3D11 11_1 creado; shader COMPILER_UNAVAILABLE, sin presents ni
  readback. PanVK privado mapeado en Wine PID15681, no con PE/ancestría suficientes:
  analizador CONTROL_INVALID, no fallback System demostrado. Prefijo compiler47
  x64 frente a builtin AA64 verificado disponible. Ver
  `docs/panvk-wine-probe-poco-results.md`. No DLL o override cambiado en esta ronda.
