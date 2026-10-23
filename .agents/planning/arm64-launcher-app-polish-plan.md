---
id: arm64-launcher-app-polish-2026-10-15
state: in-progress
date: 2026-10-15
---

## Summary

Consolidación del estado y de las mejoras solicitadas para convertir la PoC
funcional ARM64/Mali en un launcher de uso normal. **Prioridad: producto y UX,
no continuar por defecto el diagnóstico de autenticación.**

El usuario acepta tratar el Login como provisionalmente válido para avanzar.
Es una decisión de priorización, **no evidencia de autenticación completa,
selección de personaje, entrada al mundo o gameplay estable**.

Plan aprobado por el usuario el 2026-10-15 («adelante empecemos»), incluido el
orden de entregas y preservar aspecto sin deformación por defecto. Se inicia la
primera entrega UX y su documentación; esto no convierte las capacidades futuras
del catálogo, updater o autologin en funciones ya implementadas.

## Content

### Claridad visual y navegación (aprobada: «venga solucionalo»)

Corrección reversible de presentación sobre code78, sin cambiar servicios de
credenciales/almacenamiento, permisos, runtime ni confirmación de lanzamiento.

- [x] Reducir texto técnico, jerarquizar Ajustes y plegar opciones avanzadas;
  colores de acción legibles y navegación de subapartados de vuelta a Ajustes.
- [x] Simplificar almacenamiento y mover rutas/metadatos a detalles opcionales.
- [x] Cuenta: indicar inicio manual y bloquear edición/guardar mientras el inicio
  automático no está disponible. No ofrecer «Guardar cuenta» que borre datos por
  tener el permiso de autologin apagado. Olvidar sigue disponible y explícito;
  no se cambian preferencias ni se borra nada automáticamente.
- [x] Compilar/auditar/instalar candidato79 y comprobar navegación/estados en el móvil,
  sin introducir credenciales ni capturar el editor protegido.
- [x] Registrar evidencia y límites: sin prueba de lector de pantalla/texto ampliado
  o gameplay por estas mejoras de presentación.

Entrega79 `0.2.55-functional-ui-clarity`, APK SHA-256
`088931c5e2a3c548b6f082096d22ab85b3b99f79f7776c33fe543fb439a0418a`.
25 tests host, build6m50s y auditoría2142 inputs/174 payloads salvo marker. Evidencia
externa `ui-clarity-code79-reviewed/`: capturas públicas e informes de recorrido/
continuación preparada; error inicial del arnés por READY_LABEL antiguo conservado,
continuación correcta sin repetir preparación ni lanzar guest. Cuenta protegida
no capturada, sin introducir secretos; preset y originales PE/WTF intactos.

### DATA3 entregado (code76)

Selector visible y root real coordinado con preparación/Wine; cuenta arriba en
Ajustes como «Usuario y contraseña». Default legado preservado, sin copia,
migración, borrado o descarga automática.24 tests JVM y comprobación física de
root/A:/cuenta/preparación/arranque; sin USB público montado, la ejecución desde
USB real sigue pendiente. Ver [plan completado](done/launcher-client-storage-plan.md).
Esto no completa DATA1/2, instalador, runtime nuevo ni updater incremental.

#### Verificación adicional solicitada (2026-10-16)

El usuario pide revisar y probar el cambio efectivo, no solo la visibilidad del
selector. La revisión confirma el enlace selección → preparación → A: e identifica
validación incompleta de enlaces/special files anidados (NFR-002 ya aprobado).
Corrección acotada del contrato existente, sin ampliar permisos o migrar datos:

- [x] Revisar selección, persistencia, invalidación, mapeo y ausencia de fallback.
- [x] Rechazar nodos anidados no regulares sin seguirlos; recorrido de metadatos
  acotado durante selección/preparación/revalidación, nunca en snapshot/UI.
- [x] Probar ida y vuelta A→B→A, persistencia, preparación real del contenedor,
  WTF derivado, originales intactos y destino vacío/desconectado sin fallback.
- [x] Compilar/auditar/instalar code78 y validar UI y nueva preparación del root
  actual. Persistencia de selección e invalidación entre destinos probadas con
  fixtures; no ejercitadas en móvil porque solo hay un destino disponible.

No hay USB público montado: no acreditar el cambio físico entre volúmenes Android
ni ejecución desde USB con fixtures o reselección del único destino disponible.

### Continuación UX compacta y ensayo sin credenciales (2026-10-16)

- Usuario: «tiene demasiado texto ahora mismo pero está bien. sigamos».
  Code69 `0.2.45-functional-launcher-compact` instalado: título/crest compactos,
  CTA antes de GPU, limitaciones y configuraciones extensas en detalles/ajustes.
- Fuentes y APK auditados en workspace externo `launcher-compact-code69-reviewed`;
  APK SHA-256 `8adb3758782e9b8e9a2aa5beeac71129cb220276130a6f8bd8737375a2267356`.
- Preparación física con claves gamepad/calidad/FPS comprobadas sin duplicados;
  PE y WTF original conservados. No equivale a comprobar opciones dentro de WoW.
- Tras dos fallos del arnés code68 y uno de foco en code69, una consulta read-only
  demostró el checkbox separado del texto como sibling del botón de lanzamiento
  en la jerarquía Compose. Fixture nuevo, matcher corregido y15 tests dirigidos
  aprobados; no se amplió la autorización de entrada ni se habilitaron taps/root.
- Una continuación de la preparación ya existente envió un único lanzamiento,
  observó Wine/wineserver propios y rotación90°. Sin Login, texto, captura de
  imagen del juego, parche o captura de memoria. Se cerró el paquete de la sesión
  propia y se reabrió bienvenida vertical; no presentar ese reinicio como retorno
  normal desde el juego. Frame, aspecto, IME/escritura y mando requieren aún prueba.
- Evidencia externa en
  `/home/adrian/wowforever-artifacts/work/functional-launcher-ux-smoke-20261016T/functional-launcher-ux-20261016T062741624290Z-34e0fab550/`.
- Autologin seguro desde Ajustes sigue pendiente, con opt-in y persistencia
  cifrada; esta ronda no añade credenciales ni afirma la función implementada.

### Ampliación GPU entregada y prioridades conservadas

Code68 `0.2.44-functional-launcher-gpu` instalado y auditado, con tarjeta de
identidad/compatibilidad histórica y perfil previsto. Ver
[plan GPU completado](done/launcher-gpu-compatibility-plan.md) y US-005.
No amplía soporte a todo Mali ni valida gameplay. Se conservan los requisitos
de autologin opcional desde Ajustes, memoria de sesión y persistencia cifrada
opt-in, preset gamepad/calidad visible1/30FPS e icono overlay pequeño de teclado.
Autologin pendiente; preset/IME implementados pero pendientes de prueba en WoW.

### Ampliación visual y continuación de la entrega

El usuario pide continuar hasta obtener el APK y mejorar estilos/UX con referencias
de WoW. Se adopta una estética propia inspirada: fondo oscuro, bronce/dorado,
botones burdeos y títulos serif, cuerpo sans legible; sin copiar fuentes, logos
o arte oficiales de licencia no confirmada. No cambia la orientación ni autoriza
assets propietarios o distribución pública. El diseño ya está en fuentes; falta
validación visual real. Se autoriza continuar la reconstrucción aislada con un
linaje nuevo code59→62→candidato65, **sin fingir recuperar code63 histórico**.

La primera reconstrucción produjo recibo externo
`/home/adrian/wowforever-artifacts/arm64-prototype/reconstruction.EYweDF/reconstruction-receipt.json`.
Review independiente detectó admisión débil de una fuente overlay, JNI distinto
con ABI incompleta y claims de paridad no demostrados. Esta reconstrucción sirve
para diagnóstico de compilación, **no para instalar**. Reparar admisión/pins y
paridad con code64 antes de generar una entrega admitida; no ocultar el fallo
existente de hash en `test_code59_source_baseline.py`.

**Gate resuelto para el nuevo linaje:** revisión independiente del controller
actual del repo, pin explícito `8e89d2b0…`, sin afirmar equivalencia al histórico
`45f80f52…`; puente de producción añadido desde fuente del repo con pin
`a5071436…`. Se preserva JNI code64 `ec8573de…` y se auditan sus siete exports.
Recibos nuevos independientes, sin cambiar el baseline histórico ni sus tests.
Code65 se compiló/auditó/instaló; prueba visual detectó bajo contraste y motivó
code66. El usuario pidió más adornos e imágenes originales generadas: se incorporan
vectores originales en code67, sin assets oficiales. **Code67 ya instalado**;
la transición a juego, IME y mando físico todavía requieren prueba funcional.

### Estado consolidado al 2026-10-15

| Área | Hecho acreditado / límite |
|---|---|
| Base funcional | `com.wowforever.arm64probe`, code64, versión `0.2.40-functional-forever-poc`: WoW ARM64 nativo con Wine, login gráfico sobre Mali-G610 y perfil/portal Forever. Preservar Proton11 ARM64EC, DXVK2.4.1 custom y PanVK probados. |
| Otra variante | El automatizado code33 se bloquea en Explorer/`winex11.drv`. No usar su arranque como base del producto ni confundir sus resultados con code64. |
| Transporte D4 | El cliente real completó TCP/TLS hacia los servicios Forever de puertos1119/8081 e intercambió datos. El control REST independiente aceptó la cuenta temporal; no demuestra autenticación del juego. |
| Restauración | Informe `restored-after-reconnect-20261015T164917Z.json`: logging temporal D4 restaurado a16:49:17UTC, otros campos preservados; captura y parche de memoria apagados. Resuelve el checkpoint USB/restauración que aún figura pendiente en las bitácoras. No es una nueva comprobación del dispositivo. |
| Último Login visual | Un envío a17:02:22UTC; fichero efímero de credenciales eliminado. El usuario observó un estado equivalente a «Logging in to game server» y posterior retorno aparente al formulario, sin error visible. No se acreditó reino/personaje/mundo. |
| Cliente disponible | Catálogo fijo ARM64 `1.60.1.70009`, producto `wow_classic_beta`, tags Windows/arm64/enUS. El bootstrap verificado es finito, no un cliente completo ni prueba de suficiencia de recursos. |
| Provisión existente | Descarga por rangos, reanudación, verificación, BLTE y materialización CASC reutilizables. No hay todavía catálogo multi-build ni updater incremental. |
| Espacio de actualización | `ForeverCasc.materialize()` construye `Data.new` completo; no sirve como estrategia normal de actualización de un cliente grande. Falta escritor incremental con recuperación. |
| Ramas suspendidas | Capturador, búsquedas de claves y análisis estático no se reabren. No hay bypass TLS implementado ni rechazo criptográfico demostrado. |
| Procedencia de APK | Workspace externo GameNative con cambios ajenos. Las mejoras de UI requieren compilar y auditar fuentes/DEX; no basta el procedimiento anterior de sustitución solo JNI sobre APK baseline. |

Evidencia y referencias:

- [Estado técnico histórico](../../docs/arm64-forever-status.md) y
  [D3/D4](../../docs/arm64-forever-connection-progress.md).
- [Catálogo/bootstrap y sus límites](../../docs/arm64-client-bootstrap.md).
- [Autonomía diagnóstica y límites originales](arm64-forever-autonomous-connection-plan.md).
- Artefactos externos: `/home/adrian/wowforever-artifacts/work/wow-net-d4-20261015T054200Z/`
  y `/home/adrian/wowforever-artifacts/work/wow-login-visual-20261015T164917Z/`.
  No incorporar APKs, cliente, credenciales ni capturas al repositorio.

### Alcance solicitado y aceptación

| ID | Mejora | Criterio de aceptación |
|---|---|---|
| UX1 | Pantalla completa y orientación | Launcher vertical; transición a horizontal al abrir WoW y retorno a vertical al salir. Sin barras Android ni marcos de escritorio en juego; recuperación tras IME, pausa/reanudación y cambio de foco; imagen sin deformación y coordenadas de entrada correctas. Validar por separado immersive Android, ventana guest y relación de aspecto. |
| UX2 | Preset gamepad/30FPS/calidad1 | Perfil por defecto verificable en configuración y UI del juego; límite30FPS, no promesa de rendimiento sostenido. Conservar funcionamiento de mando físico; habilitar gamepad no equivale a tener bindings completos. |
| UX3 | Retirar overlay táctil | No aparecen controles de gamepad superpuestos; mando físico sigue conectado y operativo. No desactivar `ControllerManager`. |
| UX4 | Botón de teclado | Icono pequeño y accesible, sin capturar el resto de la pantalla; abre/cierra IME real y permite escribir en WoW; respeta insets, foco e immersive. |
| UX5 | Autologin opcional | Opt-in explícito, configuración y borrado desde APK, fallback manual; un único envío como máximo por arranque, solo con formulario/foco/sesión propios confirmados. Nada de contraseñas en APK, argumentos, WTF, logs o capturas retenidas. |
| UX6 | Bienvenida | Pantalla cuidada antes de iniciar Wine: estado instalado, versión, progreso/errores, ajustes y CTA apropiada. Diagnósticos separados del flujo normal; lanzar WoW solo por acción explícita. |
| DATA1 | Cliente gestionado por APK | Descargar, verificar, cancelar/reanudar y reparar con memoria/espacio acotados, progreso real y errores recuperables. Distinguir bootstrap de instalación completa; no mostrar «completo/jugable» por tener ejecutable y metadatos. |
| DATA2 | Builds y actualizaciones | Selector de builds ARM64 con procedencia/disponibilidad verificadas y compatibilidad Forever diferenciada; instalada y recomendada claramente marcadas. Actualización incremental sin redescarga o duplicación completa como estrategia normal. |
| DATA3 | Elegir almacenamiento | Implementado code76: identidad/espacio/ruta/estado, root real cliente/Data/Wine. Sin mover datos ni fingir SAF como POSIX; USB real requiere montaje/acceso directo y prueba en hardware. |

### Ampliaciones solicitadas durante el inicio

- El usuario pide launcher **vertical hasta abrir WoW en horizontal**, y vuelta a
  vertical al salir. Se integra en la primera UX; la declaración manifest fija en
  landscape no cumple este requisito y debe cambiar junto al manejo de sesión.
- El usuario pide poder elegir almacenamiento, incluido USB externo. La lectura
  del código confirma que WoW/provisioner usan rutas POSIX (`File`/`Files`) y que
  el root actual no consume la preferencia genérica de almacenamiento GameNative.
  Un grant SAF no convierte `content://` en una ruta utilizable por Wine.
- Propuesta inicial DATA3 (ya concretada en code76 con aprobación posterior):
  enumerar ubicaciones app-specific montadas y validar lectura/escritura, capacidad,
  identidad del volumen y semántica de archivos antes de ofrecerlas como destino.
  USB depende de soporte real del dispositivo/volumen. Wine, prefix, ImageFS y
  estado de control permanecen internos; solo cliente/datos serían externos.
- SAF arbitrario podría servir como fuente de importación, no como root runnable
  por defecto. No copiar el picker existente que traduce URI a rutas supuestas o
  solicita `MANAGE_EXTERNAL_STORAGE`. No ampliar permisos de forma implícita.
- Gate pendiente: validar una unidad USB real en este teléfono y decidir root,
  persistencia y recuperación ante desconexión. No migrar datos, descargar todo
  ni cambiar rutas de la PoC para probar esta hipótesis. La ausencia del volumen
  debe bloquear operaciones, nunca crear otro cliente o fallback silencioso.

### Límites y decisiones propuestas

- Conservar arranque/runtime funcional, portal, ejecutable original y datos
  existentes. No introducir refactor de runtime ni parche de autenticación.
- `wowserver`/Btrfs solo como referencia técnica de lectura: no ejecutar scripts,
  importar módulos, modificar archivos/permisos/snapshots, inspeccionar cuentas
  personales ni operar su infraestructura.
- No sobrescribir trabajo ajeno. Antes del primer build de UI, fijar procedencia
  de fuentes, assets y cambios admitidos; revisar el delta real de APK/DEX/SO.
  Si no puede aislarse de forma verificable, detener la entrega.
- No tocar `Config.wtf`. Integrar el preset en la configuración derivada usada
  por la PoC y propiedad del launcher, preservando la entrada original y los
  campos ajenos. El patcher no adquiere responsabilidades de configuración.
- No sustituir la instalación funcional original por una adopción implícita.
  Identificar ownership del root antes de modificarlo; gestionar un solo cliente
  normal en `CustomGames/WoW Forever`, sin duplicarlo completo para cada build.
- Mantener diagnóstico/captura/parche apagados por defecto. Requerir aprobación
  separada para distribución, cambios destructivos, seguridad permanente,
  cuentas reales o cambios de sistema/Btrfs.

### Preset y presentación

Referencias consultadas solo en lectura:
`/home/adrian/dev/wowserver/config/wow-client-graphics.wtf` y
`/home/adrian/dev/wowserver/image/setup-startup-preferences.py`.

- `graphicsQuality=0` corresponde al nivel1 mostrado por UI; no copiar el1 de
  notas históricas. Sombras/distancia/entorno/suelo/texturas y efectos al mínimo;
  partículas y densidad de hechizos a1, conforme a la plantilla técnica.
- `maxFPS=30`, `maxFPSBk=30`, `useMaxFPS=1`, `useMaxFPSBk=1`,
  `useTargetFPS=0`; `gxApi=D3D11`, `vsync=0`, `hwDetect=0`.
- `GamePadEnable=1`, `InputDeviceInterfaceStyle=1`. No inventar bindings de
  habilidades, autoloot o gamepadcursor no acreditados por las referencias.
- La referencia usa1280×720 y `gxMaximize=1`; no asumir que elimina márgenes en
  pantalla2460×1080. Propuesta: preservar aspecto por defecto, ofrecer resolución
  guest adaptada si funciona y no deformar para llenar el panel.
- Reutilizar immersive existente y `renderer.forceFullscreenWMClass` cuando
  corresponda. Ocultar overlay con `setShowTouchscreenControls(false)` y abrir
  IME mediante `InputControlsView.triggerShowKeyboard()`.
- La PoC fuerza actualmente `enableBGDL=0`, volumen0.03 y
  `showPhotosensitivityWarning=0`. Revisar esos overrides conscientemente;
  `enableBGDL=0` afecta la política de recursos, no es causa probada del Login.
  No copiar automáticamente el acuse global de fotosensibilidad de wowserver
  ni suprimir advertencias iniciales sin una decisión explícita.

### Contratos entre componentes (propuestos, aún no implementados)

1. **Catálogo de builds:** ID estable, arquitectura, idioma/tags, hashes de
   build/CDN config, fuentes oficiales y estado de disponibilidad con fecha.
   Compatibilidad Forever separada: validada, candidata o desconocida. No
   equiparar versión x86_64 admitida en el contrato con build ARM64 disponible.
   NGDP versions no proporciona por sí solo un historial completo. Si solo se
   conoce70009, el selector muestra solo esa opción, sin inventar alternativas.
2. **Plan de instalación/actualización:** build origen/destino, completitud,
   objetos reutilizables/nuevos, bytes de red y pico de espacio adicional,
   respaldo/caché y límites, errores bloqueantes. El `Plan(downloadBytes,
   workspaceBytes)` existente es insuficiente y debe evolucionar explícitamente.
3. **Coordinador:** estados idle, checking, planning, downloading, verifying,
   activating, ready, launching, running, recovering y error; cancelación y
   progreso estructurados. Exclusión mutua entre mutación de cliente y sesión
   WoW, incluso tras recreación de Activity o muerte del proceso launcher.
4. **Sesión de juego:** identidad propia, readiness visual, foco, salida real y
   cierre confirmado. Proceso vivo o ventana mapeada no significan Login listo.
5. **Autologin:** consumidor opt-in de la sesión, no dueño de arranque ni de
   provisión. Secretos cifrados con clave Android Keystore, excluirlos de backup,
   borrado accesible y fallo cerrado ante clave inválida; sin fallback en texto.

### Diseño de actualización incremental y espacio

- Reutilizar objetos CASC existentes solo tras validar EKey, tamaño codificado e
  identidad decodificada. Un CKey coincidente por sí solo no demuestra que la
  representación codificada requerida ya esté disponible.
- Conservar segmentos antiguos e incorporar objetos nuevos en segmentos nuevos;
  construir índices con registros antiguos+nuevos verificados. Investigar y
  probar antes límites de formato, numeración, offsets y aceptación del cliente.
  No asumir soporte append/merge en el escritor actual.
- Descargar solo faltantes o corruptos. Caché compartida limitada, temporales
  eliminables y staging proporcional al delta; no hardlinks sobre CASC mutable
  ni una instalación side-by-side completa como solución normal.
- Antes de mutar, estimar pico adicional: nuevos objetos/loose, índices, journal,
  respaldo acotado y margen. Rechazar si no cabe, explicando cuánto falta.
- Activar metadata/loose solo después de verificar el destino. La operación
  multiarquivo necesita journal durable y recuperación; un `ATOMIC_MOVE` aislado
  o su fallback no garantiza transacción completa. Durante activación/recuperación
  no lanzar WoW; recuperar una versión coherente antes de permitir el arranque.
- Mantener respaldo acotado de metadata/loose sustituidos y datos todavía
  referenciados. GC solo tras validación/commit, sin borrar archivos ajenos.
  Diferir compactación que necesite mucho espacio; comunicar espacio retenido.
- Bloquear actualización/reparación/GC mientras WoW siga vivo o no pueda
  confirmarse su terminación. Lock/journal sobreviven al cierre del launcher.
- Si el salto no admite delta correcto, explicar el bloqueo o coste y pedir una
  decisión explícita; nunca reinstalar todo silenciosamente. No prometer que
  todo salto será pequeño: el ahorro depende de los objetos compartidos.

### Autologin: guardas y privacidad

- Diseñar sobre eventos de ventana, sesión y foco; usar metadata `WM_CLASS`,
  `WM_NAME`, PID guest cuando esté disponible y confirmación del formulario.
- Propuesta inicial: matcher determinista pequeño sobre ROI adaptable, usando
  PixelCopy transitorio del SurfaceView; sin OCR/red/modelo nuevo. Validar su
  fiabilidad antes de autorizar entrada; no trasladar ROIs fijas del helper host.
- Espera de readiness acotada a90s; como máximo un envío por arranque. Cancelar
  ante interacción manual, cambio de foco/ventana/sesión o retorno tras error;
  no reintentar credenciales ni formar bucles de Login.
- Entrada por APIs XServer propias, no escalada de permisos Android. No persistir
  screenshots del formulario con credenciales ni logs con campos/tickets.
- Pruebas primero con formulario sintético; validación física con cuenta temporal
  solo dentro de la autorización vigente. No declarar autenticación exitosa por
  desaparición del formulario o por estado transitorio de conexión.

## Implementation / Action Items

- [x] 1. **Documentación y procedencia, tras aprobar el plan.** Crear
  `docs/launcher-app-status.md`, enlazar índice/roadmap/estado técnico y corregir
  los checkpoints D4 ya resueltos sin borrar historia. Separar hechos,
  decisiones y pendientes. Alinear specs afectadas siguiendo spec-as-source
  antes de cambiar contratos. Auditar baseline de build y cambios ajenos.
  - [x] Estado de producto, enlaces, checkpoint D4 y alcance UX en US-004.
  - [x] Auditar el límite de procedencia; recibo code59 disponible, falta delta
    completo y revisado code62→63 para una reconstrucción funcional aislada.
  - [x] Resolver ese gate antes de generar/instalar APK de UI con nuevo linaje
    revisado; no equivale a recuperar code63 histórico.
- [ ] 2. **Entrega UX mínima sobre la PoC funcional.** Bienvenida, immersive,
  launcher vertical/juego horizontal, overlay oculto, botón IME y preset. Mantener arranque manual estable y controles
  físicos. Tests de merge/idempotencia del perfil; prueba física de foco,
  teclado, retorno a fullscreen y mando. No cambiar runtime ni autenticación.
  - [x] Implementación en fuentes: bienvenida, orientación, immersive/IME,
    ocultación de overlay y preset; mantener controles legacy de audio/recursos.
  - [x] Regresiones host de estructura/receta y corrección de líneas CRLF.
  - [x] Compilación Kotlin y ejecución de tests JVM sobre base auditable.
  - [x] APK code67 auditado, instalado sin borrar datos y bienvenida visual vertical.
  - [x] Code69 compacto auditado e instalado; preset del fichero derivado y
    transición del runtime a horizontal comprobados sin credenciales.
  - [ ] APK auditado y validación física de orientación, teclado, aspecto y mando.
- [ ] 3. **Cliente gestionado y catálogo.** Reutilizar BLTE/CDN/provisión fuera
  de la ruta Explorer; definir ownership, completitud y estados. Verificar
  disponibilidad real de builds, instalar/verificar/reparar/reanudar y presentar
  costes antes de descargar. Resolver política de recursos/bootstrap antes de
  anunciar instalación completa; no lanzar descargas grandes por defecto.
  - [x] 3.1 Acordar y validar DATA3 e implementar selector de almacenamiento:
    destinos POSIX admitidos, USB extraíble, permisos y recuperación. Sin migración
    automática de la instalación funcional; no prometer soporte de USB no probado.
- [ ] 4. **Actualizador incremental.** Implementar planner por objetos, escritor
  CASC incremental, journal, exclusión con sesión, recuperación y GC acotado.
  No habilitar actualización en UI hasta pasar pruebas de integridad/espacio.
- [ ] 5. **Autologin opcional.** Almacenamiento protegido, readiness/foco, envío
  único y cancelación; añadir ajustes/borrado. Revisión de seguridad independiente
  antes de habilitarlo. No bloquear el resto del launcher si el matcher no resulta
  fiable: mantener entrada manual y declarar esa función pendiente.
- [ ] 6. **Validación de conjunto y entrega privada.** Auditar APK/DEX/SO frente a
  fuentes, probar en dispositivo y documentar resultados. No distribuir como
  producto compatible completo hasta validar por separado mundo/gameplay,
  suficiencia de datos y licencias de componentes.

Pruebas mínimas por bloque (los comandos exactos se fijarán con el cambio):

- Perfil: valores, duplicados/case, idempotencia y preservación de campos ajenos.
  UX: fullscreen/IME/ciclo de vida y mando físico real.
- Catálogo/provisión: arquitectura/tags/config, rutas seguras, hosts/redirects,
  hashes, Range, BLTE no soportado, cancelación, red caída y reanudación.
- Delta sintético con90% de objetos compartidos: descargar solo el10% nuevo y
  no copiar segmentos grandes antiguos; corruptos se descargan de nuevo.
- Disco insuficiente antes de mutar; interrupciones en descarga, verificación,
  cada paso de activación y GC; recuperación sin permitir una build mezclada.
- Concurrencia: dos operaciones y WoW activo bloquean mutación, incluso después
  de reinicio del launcher. Review independiente del updater/journal por riesgo
  de integridad de datos; no basta probar el camino feliz.
- Autologin: foco incorrecto, modal distinto, timeout, interacción manual,
  rotación/recreación, fallo de clave, error/retorno de Login y logs sin secretos.
  Un único envío máximo; revisión de seguridad y prueba manual de fallback.
- Ejecutar pruebas dirigidas de cada bloque una vez por candidato; suite global
  solo cuando el alcance o riesgo lo justifique. Conservar comandos/resultados
  y limitaciones, sin sumar rondas distintas como si fueran una validación única.

## Open Issues

1. **Aprobado 2026-10-15:** comenzar por documentación/procedencia y primera UX.
   El orden permite obtener mejoras visibles sin esperar al updater.
2. **Política de imagen aprobada:** preservar aspecto sin deformación como default.
   Si se exige llenar todo el panel, validar resolución guest adaptada; no asumir
   que fullscreen Android elimina barras debidas a una superficie16:9.
3. **Cliente completo/streaming:** demanda real de recursos y política ante misses
   todavía no demostradas. Determinan volumen, espacio y significado de «listo».
   Mantener señalización honesta hasta resolverlo, sin descargar todo por inercia.
4. **Compatibilidad/versiones:** únicamente ARM64 70009 acreditada como catálogo.
   Disponibilidad CDN y compatibilidad Forever deben validarse separadamente.
5. **CASC incremental:** formato y aceptación por el cliente son un gate técnico;
   si no se demuestra viabilidad, comunicar el bloqueo y revisar diseño antes de
   ofrecer actualización, no sustituirlo por duplicación completa silenciosa.
6. **Seguridad y entrega:** Keystore/autologin y journal/updater necesitan review;
   fuentes externas sucias y licencias deben resolverse antes de entrega pública.
7. **Login/gameplay:** permanecen sin validar; no se reabre diagnóstico por defecto
   ni se marca ninguna fase técnica completada por la decisión de priorizar UX.
8. **Almacenamiento añadido:** selector/root real entregado code76. Prueba de
   ejecución desde USB real pendiente de hardware; sin SAF falso, privilegios
   nuevos, copia/migración ni instalación completa implícita.
9. **Gate histórico de entrega de la primera UX, resuelto por nuevo linaje:** existe recibo autenticado de fuentes
   efectivas code59 (`scripts/arm64-prototype/app/code59-source-baseline.json`)
   y es viable reconstruir hasta code62 en aislamiento. La receta funcional solo
   admite code63/64 y no se ha localizado un recibo completo del delta code62→63.
   No importar fuentes core/XServer desconocidas del árbol mutable para saltarlo.
   El `dirtyTree` del marker nativo describe Rust, no prueba suciedad de Kotlin.
   Se revisó y fijó la fuente actual como entrada nueva, sin fingir historia;
   reconstrucción aislada y APK nuevo auditados. No se usó el APK externo antiguo
   como si incluyera la nueva UX.

### Evidencia de esta entrega en fuentes

- `/home/adrian/.local/bin/uv run pytest -q tests/test_functional_launcher_ux.py tests/test_functional_launcher_profile.py tests/test_functional_poc_overlay.py`
  →13 passed tras reparar preservación de audio/overrides y el flujo de insets.
- `python3 -m py_compile scripts/arm64-prototype/app/apply_functional_poc.py` →PASS.
- `/home/adrian/.local/bin/uv run python scripts/arm64-prototype/app/apply_functional_poc.py --source /home/adrian/wowforever-artifacts/arm64-prototype/toolchain/GameNative --dry-run --update-owned`
  →FUNCTIONAL-POC-CHECK PASS. Es un dry-run, no compilación ni auditoría DEX.
- Reproducción posterior en JShell: Java Regex `^SET.*$` no acepta una línea
  terminada enCR. Corregida detección de claves/portal con `removeSuffix("\r")`,
  conservando bytes de líneas ajenas; test JVM añadido, todavía no ejecutado.
- `/home/adrian/.local/bin/uv run --offline pytest -q tests/test_functional_launcher_profile.py -k crlf`
  →1 passed,1 deselected después de esa corrección. Es un test de estructura,
  no ejecución del merge Kotlin; no sumar rondas como una suite completa.
- La ausencia de build/instalación anterior era un checkpoint histórico; queda
  sustituida por la entrega code67 indicada debajo. Paso2 permanece abierto por
  validación física de juego/IME/mando, no por el antiguo bloqueo de compilación.

### Entrega privada code67 (2026-10-15)

- App instalada: `com.wowforever.arm64probe`, code67,
  `0.2.43-functional-launcher-ux`. APK418690852 bytes, SHA-256
  `154a73e1105674d007e48b64eb3722a0713b08155ecd562023d770d51265e34f`.
- Workspace/recibos fuera de Git:
  `/home/adrian/wowforever-artifacts/arm64-prototype/launcher-ux-code67-reviewed/`.
  `reconstruction-receipt.json`, `gradle-assemble.log`, `apk-final-audit.json`,
  `welcome-awake.xml` y `welcome-awake.png` acreditan fuentes, build y bienvenida.
- Construcción offline: JDK17.0.20.1+1 y SDK existente. `:app:assembleModernDebug`
  →BUILD SUCCESSFUL,6m32s; no cambios de toolchain/driver, no descargas.
- Tests JVM del perfil ejecutados en code65 con los mismos bytes de perfil/test:
 7 tests,0 failures,0 errors. No se repitieron en cambios solo visuales posteriores.
- Tests de reconstrucción tras admisión nueva:10 passed. Tests de auditoría:2
  passed. Última regresión de adornos/versionado:2 passed,15 deselected.
  Son checks distintos, no una suite global ni validación de gameplay.
- Auditoría:2128 archivos registrados sin cambios durante el build, firma debug
  conservada,174 payloads y solo marker de procedencia distinto respecto a code64.
  DEX recompilado: altas/bajas de descriptores limitadas a clases UX admitidas;
  no se afirma equivalencia semántica de todos los métodos del APK anterior.
- `adb install -r` →Success, sin borrar datos. El APK previo instalado se autenticó
  con el SHA-256 code64 fijado antes de actualizar. UID10330 y perfil Forever
  conservados; captura y parcheOFF. Hash del PE y configuración original iguales
  antes/después. No movimiento del cliente, Login ni lanzamiento del juego.
- Bienvenida visible en vertical, contraste corregido y adornos confirmados en
  captura del dispositivo. Primer screenshot negro correspondía a pantalla dormida,
  no a un fallo de app: se confirmó keyguard no seguro y se usó el wake existente.
- Pendientes: transición horizontal/retorno, foco/IME/escritura real, fullscreen
  guest/aspecto/entrada, mando físico y opciones gráficas visibles dentro de WoW.
