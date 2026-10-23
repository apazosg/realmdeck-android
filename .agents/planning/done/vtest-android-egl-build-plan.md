---
id: vtest-android-egl-build-2026-10-03
state: done
date: 2026-10-03
---

## Summary

Continuación de Phase B de `gpu-platform-support-plan.md`: ejecutar WoW en el
**POCO xaga/Mali-G610 conectado**, no Pixel, sin memoria/auth todavía. Los
controles EGL propios, incluso por la cadena libepoxy privada del servidor,
alcanzan Android EGL y GLES. El servidor precompilado falla antes de caps.

La fuente oficial 1.3.0 ya está obtenida y verificada contra el pin Termux. Su
ruta genérica EGL exige GBM o una extensión de plataforma que no anunció el
cliente EGL medido. Se propone un **build acotado de virglrenderer/vtest**, primero
con diagnóstico source-accurate; una ruta Android-default opcional solo si la
traza respalda el cambio. No es un rebuild de Mesa, LLVM, Wine o del driver.

La fase anterior autorizaba auditoría y controles, pero reservaba ampliación de
presupuesto para builds sustanciales. Este documento pide ese límite explícito;
aprobado por el usuario: «si, me parece bien. todo lo que consideres para seguir
avanzando». Se conserva la secuencia diagnóstico primero, corrección condicionada.

## Content

### Evidencia y autoridad

- Resultados exactos en `docs/native-vtest-epoxy-poco-results.md`: libepoxy/contexto
  PASS, `EGL_EXT_platform_base` ausente, default display/GLES2/EGL_NO_SURFACE válidos.
- Dos handshakes del prebuilt, normal y surfaceless, fallaron `vrend winsys`;
  no timeout ni caps. El cliente se vinculó al servidor PID/startTicks/UID real.
- Tar oficial virglrenderer 1.3.0: 1.255.383 bytes, SHA
  `56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce`;
  receta Termux fijada `27da60be397a6e9eb898bba8d03bcd648782ccd5`.
- Source oficial, no el mirror Android: winsys EGL condicionada a ENABLE_GBM;
  rechazo `!gbm && (!surfaceless || !has_egl_base)` antes del display. La condición
  corresponde al ensamblador; falta observar valores/subpasos reales en vtest.
- El éxito del programa libepoxy separado no identifica la rama del servidor ni
  acepta VirGL, triángulo, presentación, D3D o WoW.

### Alcance mínimo y secuencia

1. **Build reproducible diagnóstico:** reutilizar NDK 24 y dependencias Bionic
   ya auditadas. Sources y patches fijados, generación Meson/config.h manifiesta,
   ISA/SONAME/NEEDED/modos/SHA registrados. Logs acotados a configuración compilada,
   presencia de GBM, extensiones, selección de display, init/config/context/current
   y errores; no credenciales/punteros de juego. No falsear retornos ni features.
2. **Control privado separado:** nuevo directorio y pin, nunca sobrescribir el
   servidor anterior, ImageFS o HAL. Mismo cliente vtest/UID/startTicks/flags/env y
   límites de captura; comparar primero ese build antes de cambiar la selección.
   Cambiar toolchain/build puede cambiar comportamiento: registrar esas diferencias,
   no convertir una mejora en atribución exclusiva a una línea del prebuilt.
3. **Corrección Android opt-in condicionada:** si la traza confirma el rechazo
   genérico antes del EGL válido, habilitar únicamente la ruta Bionic/GLES hacia
   `eglGetDisplay(EGL_DEFAULT_DISPLAY)` sin depender de un rendernode GBM. Mantener
   comprobaciones de versión, extensiones, config, contexto y cleanup; comprobar
   `eglMakeCurrent`, no aceptar su fallo. No editar el comportamiento normal de
   Linux/GLX ni usar extensiones/caps inventadas. Deshabilitar Venus/video/fuzzers
   solo si no participan en este vtest GLES; documentar el perfil de build.
4. **Gate**, no integración prematura: loader → servidor/contexto → caps de una
   instancia verificada. Después recurso/transfer/triángulo/present deberán pasar
   como puertas independientes antes de un perfil WineD3D o WoW. Si falla, parar
   en el primer subpaso demostrado; no sumar flags ni otro runtime sin evidencia.

### Presupuesto y no-go

| Recurso | Límite propuesto |
|---|---|
| Fuente principal | Ya verificada, 1.255.383 bytes; no nueva fuente Mesa/LLVM/Wine |
| Herramientas host ausentes | Hasta 10 MiB adicionales, origen/versión/hash fijados; sin sudo/pip3 |
| Toolchain | NDK 24 existente; Ninja SDK 3.22.1 disponible, Meson no instalado |
| Workspace externo | Hasta 512 MiB, binarios/sources extraídos fuera del repo |
| Compilación | Uno o dos jobs, objetivo ≤1 GiB RAM; estimación 10–20 min, no garantía |
| Gastos | Ninguno; no cuentas/servicios/cloud ni compras |

Si faltan dependencias o el build requiere otro NDK, Mesa, LLVM, Wine, un runtime
distinto o supera estos límites, **STOP y comunicar el bloqueo/coste**. No instalar
paquetes globales ni ocultar descargas de Meson/subprojects: vendoring/downloads
automáticos deshabilitados; se fijan antes los inputs necesarios.

Mantener APK41, Wine/DXVK, driver Vulkan, guest Mesa host-only, datos/caché, flags
Forever y firma intactos. Memoria desactivada, Android SIGINT observer UNSUPPORTED,
sin root/HAL/SIM/permisos UI/grupos/servicios nuevos. No señalizar PIDs ajenos ni
relajar ownership; no reclamar containment por los procesos observados.

### Verificación y coordinación

- Contrato/build options fijados antes de delegar archivos. Un escritor por área;
  no editar fuentes extraídas a mano sin parche reproducible en repo.
- Tests afectados del build/gating y rechazo de inputs/hash/fallbacks; baseline
  fuente y perfil Android distinguidos. No rerun de suites exitosas sin motivo.
- Revisión independiente de ABI, ámbito Android/EGL, errores/cleanup y del runner
  antes de ejecutar la nueva biblioteca. Caps opacas nunca son GPU/frame PASS.
- Trial USB automático solo serial `DEVICE_SERIAL`, carpeta propia/UID 10330,
  manteniendo APK 41 intacta;
  comandos fijos y salidas/deadlines acotadas, stdin cerrado, captura durable
  antes de exec y cleanup del hijo retenido/paquete propio según política vigente.
- No APK nueva para aprobar este gate nativo; no pedir taps/export al usuario.
  Documentar resultado y límites antes de proponer integración WineD3D.

## Implementation / Action Items

- [x] 1. Calificar libepoxy privada y conservar evidencia del contexto EGL Android.
- [x] 2. Obtener/verificar tar oficial e inspeccionar la selección de winsys/EGL.
- [x] 3. Aprobar el build acotado y su límite de herramientas/workspace.
- [x] 4. Fijar dependencias/opciones, añadir diagnóstico y construir/auditar.
- [x] 5. Revisar y ejecutar el control fuente con cliente vinculado al servidor.
- [x] 6. Solo si se demuestra: probar la ruta Android-default opt-in y documentar caps.

## Open Issues

- **Alcance aprobado:** build del servidor, herramientas host ≤10 MiB y workspace
  ≤512 MiB. No ampliación automática a Mesa/Wine/driver ni cambios de capacidades.
- El tar exacto difiere de las ramas Android descritas por el mirror. No asumir
  IS_BIONIC, una línea de default-display o un Meson option inexistente.
- Los headers/`.pc` y herramientas host deben fijarse sin nuevas descargas grandes;
  el LLVM de Mesa guest no es una dependencia autorizada de este build.
- Caps/contexto no prueban aceleración útil, transfer, presentación, D3D11 o WoW;
  EGL/Activity y `runas_app` son ámbitos distintos. Login/memoria siguen después.
- **Gate acotado completado 2026-10-04:** fuente oficial, NDK24/tool wheel/header
  independientes, fresco sin red, 13 patches fuzz 0 y workspace 29.759.644 bytes.
  Diagnóstico v6 RUN `VTEST-6498e7005c0a47c08bdf295326837f59` confirma rechazo
  GBM/plataforma antes de EGL. Variante Android-default v2, manifest
  `b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28`, solo Android
  opt-in/GLES/no surfaceless/GBM null. RUN `VTEST-036cf04d51f44ad2a9e2d226302e67a3`
  recibe caps 308 bytes y EGL identity Mali-G610, mismo cliente/ownership, exits 0,
  sin timeout/force-stop o procesos restantes. La secuencia stdout del source se
  valida exactamente; summary original se conserva junto a interpretación con
  hashes. Checks afectados 18 + 10 + 7 pasan y review no detecta P0/P1 pendientes
  para el gate privado. `docs/native-vtest-poco-results.md` conserva resultados.
- Se cierra este plan de build/contexto/caps, **no el soporte gráfico ni el
  objetivo WoW**. Recurso/transfer, rendering/present, D3D y gameplay siguen en
  `gpu-platform-support-plan.md`. Ninguna APK ni driver se sustituyeron.
