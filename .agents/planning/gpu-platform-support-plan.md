---
id: gpu-platform-support-2026-10-03
state: in-progress
date: 2026-10-03
---

## Summary

El usuario pide soporte gráfico para Adreno, Mali y PowerVR, empezando por el
teléfono conectado. Prioridad: **crear un dispositivo D3D y conseguir el primer
frame de WoW en Mali**, no aún autenticación/parcheo. Plan basado en
`docs/gpu-backend-findings.md` y el control code 40: ventana conseguida, DXVK
enumera Mali pero devuelve feature level 0. Hace falta decidir un backend
compatible por capacidades y componentes reales, no añadir presets nominales.

**Prioridad vigente:** el usuario aclara «no vamos a usar el pixel. vas a seguir
con el poco con el que estás de momento». El único objetivo de pruebas actual es
el POCO xaga conectado, Mali-G610 MC6. El Pixel queda fuera del alcance de estas
iteraciones; su nota de especificaciones se conserva únicamente como histórico.

Este plan cambia el ámbito de backends/configuración del renderer. Aprobado por
el usuario («vale hagamoslo»): se empieza por medición/sonda del perfil actual,
sin sustituir DLLs, drivers, SO Android, datos o contratos de memoria.

## Content

### Decisiones propuestas

- Un APK con **perfiles de componentes verificables**, no tres forks ni runtime
  propio. Reutilizar Wine/DXVK/WineD3D y glue GameNative donde sean compatibles.
- Empezar únicamente por `DEVICE_SERIAL`, Mali-G610 MC6/Android 14. PowerVR
  anterior y Adreno requieren aceptación en dispositivos propios posteriormente.
- Separar identidad física (vendor/device/driver/API/extensiones/features) de
  capacidades **efectivas** expuestas por wrapper/winevulkan y de éxito D3D.
- Estados por tuple: candidato / sonda válida / D3D creado / triángulo presentado /
  WoW renderizado / gameplay medido. La mera familia, API o ventana no es soporte.
- Mantener code 40 y su perfil DXVK/System Vulkan/no-OpenGL como baseline protegido.
  No fingir features ni subir el número Vulkan para aparentar compatibilidad.
- Mantener memoria desactivada, configuración/flags Forever y APK/firma, datos
  comunes verificados, captura USB AOA, ownership y política de reinicio actuales.
  No cuentas, root, SIM, permisos UI nuevos, grupos/servicios o cambios de sesiones.

### Fase A — diagnóstico Mali y sonda reutilizable

1. Registrar un snapshot mínimo de capacidades físicas y del contexto efectivo
   Wine/ICD/DLL. Mapas/hashes demuestran qué componentes se cargaron. No repetir
   archivos/CASC ni ensayos ya exitosos como sustituto de medir las features.
2. Medir las features que DXVK exige realmente, especialmente fillModeNonSolid,
   shaderClipDistance y BC, ausentes en el Vulkan físico actual. Si es necesario,
   una instrumentación **solo de logs** del chequeo requiere primero fijar el
   source/ABI equivalente al DLL AA64, no reemplazarlo con un build arbitrario.
3. Crear una sonda Windows **PE AA64**, fuente propia y toolchains existentes, para
   `D3D11CreateDevice` con argumentos válidos/levels explícitos y, si tiene éxito,
   un triángulo/present. Mismo backend/configuración efectivos que WoW, sesión
   propia acotada; no autoriza parcheo, no comando arbitrario de terceros.
4. Capturar HRESULT, adapter, features/level, módulos y evidencia de present.
   Si el tuple no pasa, clasificarlo y detenerlo sin fake overrides ni fallback
   silencioso. Un control anterior fallido se conserva, no se convierte en PASS.

### Fase B — alternativa Mali solo con dependencias demostradas

Si DXVK no es viable con el tuple actual, el siguiente candidato sería
**WineD3D AA64 + VirGL/EGL**, reutilizando componentes existentes, sujeto a:

- WineD3D/d3d11/dxgi AA64 ya observados en el Proton; conservar sus hashes/lineage.
- Auditar/obtener un **guest Mesa/GL Bionic AA64** compatible. El VirGL local de
  legacy es GLIBC, y el modern Bionic no tiene el instalador necesario: no usarlo
  directamente, no migrar todo Wine a GLIBC ni escribir un renderer nuevo.
- Conservar el GL renderer/EGL y el servidor JNI correspondientes. No mezclar
  este perfil con el shim que declara OpenGL no disponible.
- Contenedor/prefijo de gráficos separado o cambio reversible con manifiesto y
  validación; compartir cliente/cache sin duplicar 84 GB ni sobrescribir baseline.
- Primero sonda D3D/triángulo, después WoW sin otros cambios. Perfil experimental
  hasta que el frame real y estabilidad se demuestren. Si no hay componente
  compatible dentro del presupuesto, documentar el bloqueo, no inventar soporte.

Una opción DXVK anterior solo procede si existe un payload AA64 verificable y sus
features satisfacen la medida. Lavapipe sería diagnóstico, no solución jugable.
No instalar Turnip en Mali ni transformar sourceOptions en pruebas de compatibilidad.

### Fase C — Adreno y PowerVR sin promesas automáticas

- Adreno: iniciar con AA64 DXVK + driver Turnip compatible/fijado, aprovechando las
  referencias funcionales y verificando modelo/generación, no copiar preset de 740
  a todas las GPUs Adreno.
- PowerVR: usar Vulkan del fabricante y medir el contexto efectivo. Su inventario
  histórico es Vulkan 1.4, distinto del Mali; no necesita ni admite Turnip por nombre.
- Selección por capacidades + tuple validado, override de diagnóstico explícito,
  sin cambiar perfil en mitad de una corrida. Misma sonda y gates para los tres.
- Fuera de Mali, estado candidato/no probado hasta disponer del dispositivo.
  Una prueba Mali no acepta render ni rendimiento de PowerVR/Adreno.

### Verificación, coordinación y presupuesto

- Tests de selección/manifest/ABI, perfil congelado/RUN/cancelación/ownership,
  rutas/hashes y separación no-OpenGL vs WineD3D. Sin cambios a US-002/core JNI.
- Revisión independiente para sustitución de DLLs/provisión de componentes y
  aislamiento de perfiles. Fijar el contrato antes de delegar escritores.
- Build offline/concurrencia uno, receta fresca/idempotente, audit del APK real y
  snapshots del candidato previo. No repetir suites no afectadas ni modelos ficticios.
- Toda ejecución USB automática con captura externa y datos privados del RUN; parar
  el propio paquete en bucles, no PIDs ajenos. Reinicio automatizado cuando la
  política vigente lo exija; sin declaración falsa de containment completo.
- Primera fase usa toolchains/payloads existentes y consultas de texto/metadatos.
  No nueva descarga grande o rebuild Wine/Mesa/NDK. Antes de adquirir un componente
  ausente, fijar fuente, hash, licencia, ABI/dependencias y coste/tamaño y pedir
  ampliación si implica otro runtime o un build sustancial. No gastos cloud.
- Tras aprobación, reconciliar la especificación del perfil opcional WineD3D si
  procede; no modificar silently el contrato x86_64 ni declarar login privado.

## Implementation / Action Items

- [x] 1. Revisar fallo D3D actual, baseline física y disponibilidad real de backends.
- [x] 2. Aprobar el alcance Mali primero y los gates de perfiles experimentales.
- [x] 3. Medir capacidades efectivas y probar la sonda AA64 D3D/present en baseline.
  Code 41: medición válida, DEVICE_REJECTED; no D3D/triángulo presentado.
- [ ] 4. Si no pasa, auditar dependencias y ensayar un único backend alternativo.
- [ ] 5. Verificar WoW/frame y publicar candidato con límites reales, sin memoria.
- [ ] 6. Aplicar los mismos gates a Adreno/PowerVR cuando haya acceso al dispositivo.

## Open Issues

- **Puente Wine siguiente:** Android WSI ya aceptado, no Wine/D3D/WoW. Auditoría
  del wrapper real confirma ruta custom con path/name/hooks y código WSI Xlib;
  compatibilidad PanVK/HMI/namespace y forwarding no probada. Nuevo profile mínimo
  Wine+sonda todavía draft en `.agents/planning/panvk-wine-probe-plan.md`. Mantener
  wrapper ICD y renderer existentes, no suponer que hay que escribir otro bridge.
  Ver `docs/panvk-wine-loader-findings.md`.

- **PanVK instancia y metadata en POCO:** control privado v4 y RUN
  `PVK-81cc697088b7492395d471c73399bbcc` reportan Mali-G610MC6/Vulkan1.4.363,
  185 extensiones completas y features demote/sync2/dynamicRendering true. BC y
  robustBufferAccess2 false, sin device/workload/present/D3D/WoW. Old baseline
  intacto; no activar perfil por metadata. Ver `docs/panvk-instance-poco-results.md`.
- **PanVK device/cola NO aceptado:** ensayo A con el mismo ICD/POCO enumera familia
  flags7/count2 pero `vkCreateDevice` retorna -2/sin handle. Sin trabajo, cola,
  presentación ni WoW. Gate B transfer detenido, no atribuir a agotamiento físico
  de memoria sin observar fallo interno. `docs/panvk-device-queue-poco-results.md`.

- **Prioridad reafirmada 2026-10-06:** el usuario pide no descartar PanVK.
  Ref nueva zenithblue `de577411e8f27da9de50aec26a0d8cc43890745e` documenta
  carga/enumeración en G610/kernel5.10, sin device/workload por rechazo BC de DXVK.
  No extrapolar la aceptación G615 ni el fallo del vendor driver actual. PanVK
  queda como alternativa Vulkan prioritaria; ver `docs/panvk-mali-findings.md`.
- **Última medida completada (2026-10-06):**
  `done/dri-version-limit-plan.md`, ampliación explícita aprobada. RUN
  `VTEST-9c2fe8e63b974ecdb126313c8a0d7b6c` lee cuatro bytes antes/después del campo
  core del ELF/API fijados: máximo0 estable, petición33, original null/BAD_VERSION.
  El check binario rechazaría; no PC observado ni requisito limitante identificado.
  Sin corrección pequeña demostrada, se cierra esta pareja guest Mesa/VirGL actual.
  Se conservan native EGL y triángulo del servidor aceptados por separado. PanVK
  prioritario, todavía sin descargar/ejecutar ICD ni modificar el driver. Ver
  `docs/dri-version-limit-poco-results.md` y el draft `panvk-poco-qualification-plan.md`.

- Logs locales posteriores: replay autorizado con EGL_LOG_LEVEL/debug y logger
  Mesa a stderr alcanzó `dri2_create_context`/EGL_BAD_MATCH, pero no revela el enum
  DRI. Mismos componentes, no Mesa rebuild ni contexto aceptado; ver
  `docs/guest-egl-local-logs-poco-results.md`. Una posible observación del retorno
  en el símbolo dinámico real requiere decisión separada:
  `.agents/planning/done/dri-return-observer-plan.md` (observer aprobado y cerrado;
  solo hijo de diagnóstico, no un cambio del backend).

- **Enum DRI real (2026-10-05):** observer de nueve argumentos cualificado,
  RUN `VTEST-9cc0884dc09d4f699cfcb8dc0527fabd`, forwarding único al Gallium privado
  con API3/major3/minor3 y errno conservado: retorno null, error3/BAD_VERSION.
  Display/config válidos; no contexto ni D3D/PRESENT/WoW. En este control anterior
  no se leyó el máximo; la ampliación posterior sí midió cero, no su feature
  limitante. No forzar bits/versiones ni ampliar automáticamente structs. Ver
  `docs/dri-return-observer-poco-results.md`. La pareja actual permanece no aceptada.

- **Ronda acotada final de negociación:** perfil solo-logs Android-default,
  RUN `VTEST-f704d8e8a71244d79bb1b4c2f6ab0677`. Mesa negocia protocolo4 y el servidor
  devuelve capset2 1408/capset1 308 bytes, GLSL430/host GLES, antes del mismo
  EGL_BAD_MATCH3.3 core. No corrección pequeña demostrada; actual pareja guest/
  servidor no aceptada. No más perfiles/versiones a ciegas ni nueva APK equivalente.
  Ver `docs/guest-vtest-negotiation-poco-results.md`; nuevo diagnóstico local Mesa
  o pareja cualificada requiere explicitar alcance/coste antes de ampliar esta vía.

- **Guest perfiles (2026-10-04):** config privada 31/OpenGL-bit real medido;
  requests GL3.3 core/compatibility, GL3.2 core y GL3.0 sin máscara devuelven
  `EGL_BAD_MATCH`. Matriz nativa válida, no soporte ni máximo GL establecido.
  Ver `docs/guest-egl-context-poco-results.md`: próximo gate es negociación/caps y
  rechazo local Mesa/EGL; no otra variante APK/driver ni features ficticias.

- **Gate guest de contexto (2026-10-04):** GLVND/Mesa/Gallium/LLVM privados y
  display EGL1.5 conectados al servidor cualificado. Tras corregir la petición
  implícita de ventana de la sonda, una config existe pero OpenGL3.3 core se
  rechaza con EGL_BAD_MATCH. Sin draw/present/D3D guest ni backend aceptado.
  Se conservan controles sin cambiar APK/runtime/driver; ver
  `docs/guest-egl-context-poco-results.md`.

- **EGL por libepoxy y source real:** el control privado posterior alcanza Android
  EGL/GLES en el POCO, exit 0 sin errores/procesos pendientes; la query de cliente
  no anuncia EGL_EXT_platform_base. Tar oficial 1.3.0 obtenido/verificado, 1.255.383
  bytes, mismo SHA Termux; su winsys genérica requiere GBM, no la rama IS_BIONIC del
  mirror. El build diagnóstico aprobado confirmó la guarda previa al display;
  la corrección privada Android-default posterior recibió caps. Ver
  `docs/native-vtest-epoxy-poco-results.md`, `docs/native-vtest-poco-results.md` y
  `.agents/planning/done/vtest-android-egl-build-plan.md`.

- **Servidor vtest posterior:** el candidato privado de 10 ELF entra a su parser;
  dos conexiones de caps con PID/startTicks/UID/argv vinculados fallan en
  `vrend_winsys` antes del contexto/caps. Default EGL y una única flag surfaceless
  producen el mismo fallo; no hubo timeout, force-stop ni procesos restantes.
  Native EGL Android sí pasó ambos controles de triángulo. Resolver build/path de
  inicialización EGL del servidor antes de WineD3D; ver
  `docs/native-vtest-poco-results.md`. No se modificó driver/SDK/seguridad/runtime.
- **Gate posterior aceptado, no backend:** RUN
  `VTEST-036cf04d51f44ad2a9e2d226302e67a3`, servidor Android-default source/pin
  independiente, GL identity ARM/Mali-G610/ES3.2, 308 bytes de caps opacas, native
  exits 0, peer/kernel identity y EOF válidos, sin procesos pendientes. Corrección
  HOST del canal stdout contrastada con source, summary anterior preservado.
  18 source/build + 10 runner fuente + 7 handshake tests pasan; revisión de ámbito
  y ABI aceptada. Los controles siguientes verifican recurso y draw nativos,
  pero no se ha cargado Mesa guest ni ejecutado presentación/WineD3D/D3D/WoW con
  este servidor. Phase B sigue pendiente.
- **Servidor estándar: recurso y triángulo aceptados:** RUN
  `VTEST-210be544d9e34f798e49619b070a8c3e` transfirió cuatro bytes exactos; RUN
  `VTEST-c932e502b56342b783ac9c24cf8a1791` dibujó TGSI/3 vértices/64×64 con lectura
  16.384 bytes, 1.682 píxeles distintos del clear y 2.414 verdes. Identidad/EOF,
  ambos exits 0 y cleanup sin procesos restantes; no presentación ni D3D/WoW.
  14 checks cliente nativo y 28 runner/handshake pasan, review de gate sin P0/P1.
  Próximo gate: ampliar la selección HOST a EGL Mesa/GLVND de los mismos paquetes
  cacheados, verificar exports y loader privado/virpipe sin mezclar EGL del servidor.

- Alcance aprobado. No GPU tiene todavía aceptación D3D/WoW de este launcher;
  la presentación nativa Android del control PanVK se acepta por separado.
- **WSI Android PanVK observado:** code47/0.2.23, RUN
  PWSI-94e1a0a08761465f912127847b743ce8, same beta17 y UID10330 en Activity propia;
  tres frames, screenshot negro→triángulo rojo12,5%/verde87,5%, cleanup/journal
  válidos y sin procesos restantes. Gate cerrado en
  `.agents/planning/done/panvk-presentation-plan.md`; ver
  `docs/panvk-presentation-poco-results.md`. No es Wine/Xlib, DXVK, D3D ni WoW.
  El siguiente gate requiere fijar loader/bridge del ICD que Wine usa realmente
  antes de declarar un perfil de juego aceptado o escribir otro renderer.
- **PanVK metadata propia completada:** control aprobado de contexto da UAPI1.18,
  SET_FLAGS0 ret0, propiedades723 bytes, GPU-ID0xa8670000 y texture0xc1fe039e/0/0/0;
  close0/sin procesos restantes. Plan metadata DONE, no ICD ejecutado. Auditoría
  estática encuentra proveedores API34 para 269 imports, sin demostrar namespace
  o funcionamiento. El paquete conserva minApi35. El posterior loader-only
  aprobado se completó: handle+3 exports owners exactos/dlclose0 en API34, sin
  invocar Vulkan. Plan cerrado en `.agents/planning/done/panvk-api34-loader-plan.md`.
  Ver `docs/panvk-private-loader-poco-results.md`; instance/enumeración se completó
  posteriormente en `.agents/planning/done/panvk-instance-enumeration-plan.md`.
  Device/transfer propuesto todavía draft en
  `.agents/planning/panvk-device-transfer-plan.md`, no D3D/WoW aceptado.
- **Phase A completada:** code 41/0.2.17, sonda AA64 89.600 bytes, 23 tests JVM,
  receta fresca 70 inputs, audit PASS. RUN `RUN-1791026632504-55097624541`: archivo
  durable completo del mismo RUN, demote/sync2/dynamic rendering false efectivos;
  D3D11CreateDevice/retry 0x80070057, sin dispositivo/presents. Ver
  `docs/mali-graphics-probe-results.md`. El exit 0 de Explorer no es probe PASS.
- Source lineage del DXVK experimental y asociación exacta Vulkan/DXGI no están
  plenamente comprobados; interpretar la rama upstream condicionalmente.
- **Phase B candidato:** Termux Mesa 26.2.4/libglvnd aarch64, 3.324.748 bytes
  descargados únicamente para auditoría estática con hashes fijados; no se instaló
  nada. ELF Bionic y ruta vtest plausibles; cierre ELF privado/protocolo con servidor
  actual aún pendientes. Metadata del cierre completo: 40.461.140 bytes de descarga
  y 238.690.304 expandidos (no adquiridos). No hace falta suponer rebuild propio,
  pero sigue sin haber un perfil WineD3D/VirGL probado ni triángulo.
- Disponibilidad de Adreno/PowerVR para pruebas posteriores y presupuesto de
  componentes nuevos: pendiente. No se paga ni descarga sin decisión explícita.
- Primer frame no implica cliente CASC completo, estabilidad/rendimiento o login;
  esas puertas y el parche de memoria quedan después, como pidió el usuario.
- **Investigación PanVK:** upstream declara G610 conforme/Vulkan 1.4, distinto del
  driver Android actual. Port zenithblue G615 validado, G610 todavía scaffold;
  BkaNeko xaga exige HAL/root y anuncia capacidades no implementadas, descartado
  como tuple válido. Ver `docs/panvk-mali-findings.md`. No se instala PanVK ni se
  cambia el backend sin un build G610/kernel/ICD/present cualificado.
- **Cierre ELF reproducible:** auditor offline de los dos paquetes Bionic fijados
  más nueve tests pasan; `controls/virgl-audit/closure-audit.json` fuera del repo
  enumera 18 SONAMEs todavía ausentes, loader/protocolo UNVERIFIED y `ready=false`.
  Es auditoría, no integración ni autorización de una sustitución global.
- **Candidato completo posterior:** 29 paquetes fijados, 40.461.140 bytes totales
  (37.136.392 nuevos), 13 tests afectados pasan. Cierre ELF sin SONAMEs ausentes;
  materializado solo en host, carga aún UNVERIFIED. La comparación de fuentes
  revela que el servidor JNI GameNative no habla Mesa vtest: cliente y servidor
  no pueden conectarse mediante un simple cambio de socket. No se instaló ni
  ejecutó esa combinación; ver `docs/virgl-bionic-candidate-status.md`.
- **Cambio de prioridad:** no usar Pixel 9a ni esperar su acceso para avanzar.
  Continuar en `DEVICE_SERIAL` / POCO xaga. Adreno y PowerVR quedan como perfiles
  candidatos futuros, no sustitutos del ensayo Mali actual.
- **Gate físico EGL/GLES:** control propio de 22.592 bytes, imports Android
  libEGL/GLESv3/libc, PASS pbuffer64×64 + shaders + 1.300 píxeles leídos en
  Mali-G610/GLES3.2, sin errores/cleanup fallido ni procesos propios pendientes.
  Se ejecutó bajo `run-as` UID10330, no la Activity; no acepta todavía vtest,
  WineD3D, D3D/present o WoW. Ver `docs/native-egl-poco-results.md`.
   Los controles posteriores de surfaceless/epoxy y servidor fuente ya superaron
   esos gates. No conectar Mesa al servidor custom; el guest real y presentación
   siguen pendientes.
- **PanVK device/queue/transfer (beta.17):** el bloqueo CSF1.18 de beta16 estaba en
  el driver (`MEM_ALLOC_EX` ENOTTY / `MEM_EXEC_INIT` EPERM), no en el kernel ni la
  GPU. Con beta.17 el control propio crea instancia, un G610 exacto, device result0
  y una cola, y un **transfer de 4 KiB verificado** (1024/1024 DWORD, fence
  señalada, limpieza completa). Sigue sin shader/triángulo/presentación/WSI/DXVK/
  D3D/WoW y sin memoria; BC/robustBufferAccess2 false. Ver
  `docs/panvk-device-transfer-poco-results.md` y
  `.agents/planning/panvk-device-transfer-plan.md` (checkbox 6 hecho, 7 pendiente).
- **Gate C (triángulo fuera de pantalla) completado:** el mismo ICD beta.17 dibuja
  64×64 con shaders SPIR-V, render pass y pipeline, y lee 512 píxeles de triángulo
  con 3584 de fondo (`TRIANGLE_OFFSCREEN_OBSERVED`, fence señalada, limpieza
  completa). Un bug de nuestro parser HOST (bit `DEVICE_LOCAL` mal probado) rechazó
  primero el mismo stdout; corregido sin recompilar el binario. No es presentación,
  WSI, D3D, DXVK, WoW ni frame en pantalla. Ver `docs/panvk-triangle-poco-results.md`.
