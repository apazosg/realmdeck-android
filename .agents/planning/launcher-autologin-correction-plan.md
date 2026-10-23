---
id: launcher-autologin-correction-2026-10-22
state: in-progress
date: 2026-10-22
---

## Summary

Petición: «he guardado los credenciales en el apk pero nunca llega. a introducirse
puedes revisar cual puede ser el motivo y planificar una corrección».

La revisión distingue guardar una cuenta de introducirla en el juego. En el código
actual hay tres cortes confirmados: disponibilidad fijada a `false`, ningún intento
conectado al arranque y ningún detector/adaptador de envío productivo. El guardado
existe; estos cortes bastan para explicar el síntoma sin atribuirlo a una contraseña
incorrecta ni borrar la cuenta para intentar arreglarlo.
El usuario aprueba este plan el 2026-10-22: «si, hagamoslo». Se autoriza la
implementación en el orden previsto, incluida la convivencia con el escritor sin
captura. El uso de la cuenta actual no está autorizado; instalación y ensayos
físicos se coordinan según el paso 7.

Es el detalle de reanudación del ítem 3 del
[plan de entrega funcional](launcher-functional-completion-plan.md), no otro
proyecto de autologin. El [plan inicial](launcher-autologin-plan.md) conserva la
investigación histórica; sus autorizaciones de una cuenta temporal no se trasladan
automáticamente a la cuenta actual.

## Content

### Diagnóstico y límites de la evidencia

- La entrega C90 separó Guardar de activar autologin, mantuvo memoria por defecto
  y Recordar cifrado opt-in. La comprobación física allí registrada validó guardar
  y reabrir, **no** escribir en WoW. Véase
  [estado del launcher](../../docs/launcher-app-status.md), sección «Entrega
  actual90: guardado real de cuenta, independiente del autologin».
- Ese mismo registro dice explícitamente que detector, input y sender reales no
  estaban integrados. El estado consolidado del 2026-10-22 sigue identificando el
  autologin de la APK como pendiente.
- Inspección de fuentes bajo `scripts/arm64-prototype/app/overlay/`:

  | Corte | Evidencia actual |
  |---|---|
  | Disponibilidad desactivada de forma constante | `ForeverFunctionalPocActivity.kt:2525–2533` pasa `runtimeReady = false`. `ForeverFunctionalAutologinSettings.kt:466` fuerza `requestedEnabled = runtimeReady && desiredEnabled`; `:687–705` oculta la casilla de activar cuando no hay disponibilidad. |
  | Preferencia sin consumidor de lanzamiento | La carga productiva de `ForeverFunctionalAutologinPreferences` solo aparece en Ajustes (`ForeverFunctionalAutologinSettings.kt:334`), no en arranque/runtime. |
  | Intento y evidencia sin integración | Los constructores de `ForeverFunctionalAutologinAttempt`, `Policy` y `Evidence` solo tienen usos en tests. `ForeverFunctionalAutologinAttempt.kt:79–83` declara explícitamente que no obtiene credenciales ni envía input. |
  | Sin transporte de autologin | `ForeverFunctionalAutologinSettings.kt:375–379` solo configura la cuenta; no hay detector ni sender conectado. `Permit.claimSubmit` (`ForeverFunctionalAutologinAttempt.kt:234–236`) reserva permiso, no pulsa Enter. |

  Por tanto, los tests del almacén y del controlador puro no acreditan el flujo
  Ajustes → Jugar → formulario → escritura → envío. Cambiar solo `false` por `true`
  no arreglaría ninguno de los otros cortes y eliminaría una guarda deliberada.
- El gate histórico no confirmó de forma fiable el formulario/foco. No es seguro
  sustituirlo por una espera fija, una ventana mapeada o coordenadas de otro layout.
- Esta revisión no consulta el dispositivo ni descifra credenciales: no confirma
  la versión instalada, el opt-in actual ni la recuperabilidad de esa cuenta.
- La incidencia de aplicación del parche/conexión C133 es distinta: resolver la
  entrada automática no demuestra autenticación ni corrige el parcheador.
- Hay una incompatibilidad adicional latente: `automaticAllowed()` en
  `ForeverFunctionalAutologinAttempt.kt:151–157` prohíbe autologin tanto con captura
  como con el escritor de memoria habilitado. Aunque se integrara el sender,
  seguiría bloqueado con el escritor Forever actual; requiere decisión explícita.
- Verificación de esta ronda: lecturas y búsquedas de símbolos/call-sites, sin
  modificar fuentes ni ejecutar suites, ADB, juego, capturas o entrada de datos.

### Resultado propuesto y criterios de aceptación

Fuente de comportamiento: [US-006](../specs/US-006.md), UC-01/02/03,
AC-01…06 y FR-001…006; límites globales en [SYSTEM](../specs/SYSTEM.md).
Se conserva el aislamiento y la información veraz de
[US-003](../specs/US-003.md), FR-005/006/007. No se altera la autoridad de escritura
del parcheador definida en [US-002](../specs/US-002.md).

1. Guardar y habilitar autologin son acciones distintas. Desactivarlo conserva la
   cuenta; Olvidar la borra. No habilitarlo silenciosamente por haber guardado.
2. Con opt-in y credenciales disponibles, un único controlador ligado al arranque
   real comprueba sesión propia, ventana, foco, formulario y campo antes de escribir.
3. Geometría del guest/viewport actual y evidencia positiva, también de ausencia
   de modal; revalidación antes de cada fase de entrada y antes de Submit.
4. Como máximo un envío por arranque. Interacción manual, cambio de foco/sesión/
   geometría, modal, error o 90 s sin readiness cancelan sin rearmar. Recreación y
   callbacks tardíos no generan otro intento.
5. Entrada por las APIs internas del XServer, sin root, ADB o scripts de PC como
   requisito del APK; no clipboard, argumentos, intents ni archivos con secretos.
6. Mostrar cuenta guardada, autologin desactivado/no disponible, esperando,
   cancelado o enviado sin revelar valores. «Enviado» nunca significa autenticado.
   Mantener el teclado y la entrada manual.
7. No declarar resuelto el autologin solo por mejorar un mensaje o compilar: exige
   comprobar escritura y envío desde la APK instalada en el dispositivo acordado.

### Diseño mínimo y alcance

- Reutilizar almacén, coordinador de Ajustes y política de intento único existentes;
  añadir solo la conexión de ciclo de vida, detector y adaptador de entrada que falten.
- Sustituir la disponibilidad constante por la capacidad real de esa integración,
  no por otro `true` fijo. Separar capacidad de readiness: se configura en Ajustes
  con el juego cerrado y el formulario/campo se verifica en cada lanzamiento.
- La ruta de teclado ya usada por los controles de la APK es
  `PluviaApp.xServerView?.getxServer()?.keyboard.onKeyEvent(...)`
  (`ForeverFunctionalPocActivity.kt:1376–1383`). Acredita teclas, no un transporte de
  contraseñas arbitrarias. Confirmar las APIs de ventana/foco/captura/puntero en el
  árbol canónico reconstruido: no están implementadas en el overlay versionado.
- **Enfoque revisado y aprobado:** el usuario elige «Sin imágenes» el 2026-10-22.
  Buscar una señal directa del estado del login y del foco, no reconocimiento de
  capturas. Si las APIs actuales no ofrecen esa semántica, presentar el cambio de
  alcance antes de implementar una nueva integración. Sin una discriminación fiable
  se mantiene entrada manual; no reemplazar la prueba por temporizadores o Tab.
- **Investigación ampliada aprobada:** tras proponer investigar una integración
  directa con WoW/Wine sin modificar aún el cliente, el usuario responde «si,
  hazlo» y añade «puedes investigar sino como lo hacen otras soluciones de
  autologin en WoW». Comparar soluciones existentes, APIs de login/Glue y posibles
  señales semánticas de Wine, distinguiendo builds antiguas de 1.60.1.70450 ARM64.
  Es investigación de viabilidad, no autorización de inyección, lectores nuevos,
  cambios de cliente/runtime ni uso de credenciales.
- Adquirir una copia temporal de credenciales solo tras el gate positivo, mantener
  su vida acotada y cerrarla al terminar o cancelar. No cambiar el cifrado ni migrar
  o recrear el almacén para resolver una integración de entrada.
- Validar la codificación completa antes de empezar: no transformar, truncar ni
  introducir parcialmente una cuenta que el transporte no pueda representar.
  No copiar al almacén la allowlist limitada del helper host; conservar manual si
  una secuencia no se puede enviar fielmente.
- **Decisión propuesta para aprobación:** permitir autologin con el escritor
  Forever opt-in, manteniendo captura/diagnósticos que puedan persistir memoria
  desactivados y sus controles de secretos. Revisar esa frontera antes de cambiar
  la política; no habilitar el escritor automáticamente ni exigir que ya figure
  «parche verificado» antes de enviar, porque ese orden no está demostrado.
- No cambiar runtime, driver, cliente, TLS, servidor, contrato de claves ni permisos
  del sistema; no reabrir la investigación de conexión como trabajo adyacente.
- No frameworks nuevos, OCR genérico, matrices de resoluciones ni suites globales.
  Limitar pruebas nuevas al comportamiento y frontera de seguridad modificados.

## Implementation / Action Items

### Cambio de enfoque solicitado: entrada asistida desde el overlay

**Actualización de entrega aprobada (2026-10-23):** el usuario exige instalar y
habilitar la función solicitada: «y dale con protección. súbela de una vez»,
«por qué está hmdeshabilitsdo?» y «si es precisamente lo que te pedí que hicieras».
La instalación inicial confirmó C138, pero su botón estaba cerrado por el gate de
compilación; no satisface la entrega. Preparar una C138 corregida con identidad de
versión/hash distinta, conservando el artefacto anterior y el JNI de C137. Mantener
las guardas implementadas y revisadas; la prueba física de captura queda pendiente
y deja de bloquear esta entrega experimental. No presentar esa protección como
verificada físicamente. No ejecutar el juego ni usar la cuenta sin autorización.

**Contrato asistido aprobado (2026-10-22): «si, hazlo».** El usuario
propone un icono como el del teclado que envíe «usuario tab contraseña enter» y
después indica: «por defecto ya aparece en el usuario al principio. probemos de
esta forma creo que es la más sencilla». Se acepta esa observación como evidencia
aportada por el usuario para su arranque, no como garantía universal de foco.

Esta propuesta sustituye, para la próxima prueba, el envío automático por una
acción explícita de **entrada asistida**. Se pausa la sonda y no se la ejecuta como
prerrequisito de este experimento; sus resultados pendientes no se declaran aprobados.
Los pasos históricos de integración automática que siguen debajo no se ejecutan
rebajando su gate ni se marcan completos por implementar este botón.

#### Contrato propuesto e impacto sobre US-006

- Añadir un icono accesible «Introducir cuenta» junto al teclado del overlay.
  Un toque inicia usuario → Tab → contraseña → Enter, sin disparo al arrancar,
  temporizador de readiness ni reintento. No pedir confirmaciones repetidas: el
  botón constituye la acción explícita. Explicar junto al control/ayuda que requiere
  **campo Usuario vacío y enfocado, sin diálogos**. Si el juego recuerda un nombre,
  el usuario lo vacía manualmente antes; no añadir borrado automático de campos.
- El usuario asume la comprobación de formulario/campo/modal. La APK valida la
  sesión/ventana propia disponible, pero **no sabe comprobar el campo ni los
  modales internos**. Un foco distinto puede mostrar secretos en otro campo;
  no presentar el botón como detector ni autenticación garantizada.
- Mantener guardado y Keystore existentes; adquirir copia temporal solo al toque,
  liberar al terminar/cancelar. El botón no exige ni habilita la preferencia de
  autologin automático que hoy no tiene integración; sin cuenta utilizable,
  informar sin enviar teclas ni abrir Ajustes con secretos en el overlay.
- Validar la representabilidad completa antes de la primera tecla; no alterar
  símbolos/mayúsculas, truncar ni empezar a introducir una secuencia no soportada.
  Usar el XServer interno, nunca clipboard, argumentos, intents o archivos.
- Un envío en curso por lanzamiento; consumir la acción antes de empezar input
  para que doble toque/recreación/callback tardío no dupliquen ni rearmen un envío
  parcial. Mantener como máximo un intento asistido por lanzamiento en esta prueba.
  Cancelar ante cierre, cambio de sesión/ventana o pérdida de foco detectable e
  intervención real durante el envío; el toque inicial del icono no es cancelación.
  No prometer detectar cambios de campo/modal internos mientras se envía.
- Mantener captura de memoria incompatible y escritor Forever independiente;
  ninguna opción se activa por el botón. Verificar privacidad de diagnósticos
  locales/remotos y capturas antes de ensayo con secretos; persistir solo códigos.
  Conservar entrada manual. «Enviado» no significa autenticado.

**Impacto a aprobar:** UC-02 pasa de intento automático con gate semántico a envío
asistido; AC-03/FR-003 deben separar confirmación humana de comprobación de sesión
por APK; AC-04/FR-004 y NFR-001 deben describir cancelación observable durante
el envío, no fingir detección de modal ni espera automática90s. Ajustar TC-003/004/
006/007 y añadir únicamente regresión del toque explícito y transporte fiel.
UC-01, AC-01/02/05/06 y protecciones FR-001/002/005/006 no se relajan. Los FR/TC
actuales permanecen intactos hasta aprobar el cambio; no se cambia el parcheador,
runtime, cliente ni contrato de servidor. La independencia de guardar frente a
habilitar se mantiene. Revisar US-003 solo si se toca su frontera diagnóstica.

- [x] A1. Contrato e impacto aprobados por «si, hazlo». UC/AC de US-006 actualizados
  primero; FR/NFR aprobados el2026-10-23 por «si hagamoslo. estoy haciendo la c137
  podemos añadir estas funcionalidades también aquí». TC afectados reconciliados
  y aprobados por «vale. hagamos la c138 que contenga por supuesto todo lo de la
  137»; US-006 vuelve a `ready`, sin declarar implementados sus requisitos.
  La C137 ya consta compilada/auditada: conservar ese artefacto y su evidencia sin
  alterarlos. La integración asistida partirá de sus cambios y tendrá una nueva
  identidad de compilación/auditoría; no instalar ni sustituir el ensayo en curso.
  La entrega solicitada se identifica como **C138 sobre C137**, conservando su JNI
  alineado `be86859b38c03daeeac37032578e5ae0fe77782c98fc1337190e103d71bce472`.
- [x] A2. Implementar el sender acotado y el icono reutilizando almacén y APIs
  existentes; no forzar el controlador automático a aceptar evidencia inventada.
  - [x] Sender, transporte real y control implementados y revisados; guardas de
    privacidad revisadas. El gate inicialmente cerrado se abre en la corrección
    `0.2.114-assisted-entry`, compilada, auditada e instalada por petición expresa.
    No es evidencia de entrada en WoW; la comprobación física de captura sigue pendiente.
- [x] A3. Pruebas focales sintéticas: sin toque no hay input; secuencia fiel y un
  Enter; doble toque/recreación sin duplicado; pérdida de sesión/cierre cancela;
  credenciales no representables se rechazan antes de enviar; secretos no exportados.
  Doce JVM del sender, once JVM de privacidad y ocho Python de guardas host pasan.
  Solo acreditan los escenarios comprobados, no protección contra captura externa
  privilegiada ni control de los logs generados por el propio juego.
- [x] A4. Revisar seguridad/input y construir/auditar APK fuera del repo. Si sigue
  indisponible la revisión independiente, informar y no presentar la entrega como
  revisada ni ejecutar ensayos con secretos bajo una aprobación ficticia.
  - Bloqueo histórico confirmado el2026-10-23: tanto builder como revisor permitido
    fallan antes de ejecutar con `Variant unavailable for openai2/gpt-6-luna: xhigh`.
    No hay resultado de revisión. La inspección directa confirma que siguen los
    sinks locales/exportación ya registrados, sin guardas de entrada asistida.
    No habilitar ni entregar como utilizable el sender antes de resolver esta
    frontera y obtener la revisión exigida. No se ha generado ni instalado C138;
    C137 permanece intacta. No requiere otra aprobación funcional del usuario,
    sino restablecer el revisor configurado y completar implementación/verificación.
  - El reintento posterior con el grupo disponible `openai-luna` sí arranca:
    implementación preliminar y seis pruebas JVM sintéticas terminadas; revisión
    independiente iniciada. La indisponibilidad anterior ya no es el bloqueo activo.
    El control permanece inaccesible (`ASSISTED_ENTRY_ENABLED=false`) hasta resolver
    privacidad y revisión. La primera reconstrucción C138 falla en paridad de dos
    assets antes de compilar APK; se investiga sin relajar el auditor ni cambiar
    el C137 autenticado. No marcar A2/A3/A4 completos por estos avances parciales.
  - Revisión independiente terminada: no habilitar todavía. P1: exportación y
    persistencia diagnóstica cruda sin guardas para sesiones asistidas. P2: dimensiones
    del root Compose en vez de la vista XServer real; pruebas usan encoder/input
    falsos sin verificar transporte de producción. Correcciones acotadas en curso;
    no se exige detector semántico ni se reabre el contrato asistido aprobado.
  - Sender y privacidad corregidos, verificados y aprobados para auditoría de APK.
    C138 compilada con gate cerrado, JNI de C137 preservado. La primera auditoría
    independiente rechaza los dos assets anonimizados aprobados: se actualiza solo
    esa admisión exacta y sus pruebas, sin reconstruir la APK ni alterar C137.
    Resultado y hashes en [la evidencia C138](../../docs/launcher-assisted-entry-c138.md).
  - Auditoría del mismo artefacto terminada con PASS: 2219 fuentes y178 payloads.
    El integrador inspeccionó diff completo, informe y hashes. Revisión independiente
    del delta del auditor sin hallazgos, limitada al código proporcionado porque su
    entorno no permite inspeccionar archivos. No equivale a revisión independiente
    del artefacto físico ni aprobación para habilitar. A2 sigue parcial y A5 pendiente.
- [ ] A5. Coordinar instalación sin borrar la cuenta, guest inactivo y ensayo de
  cuenta sintética/temporal expresamente autorizada; usuario comprueba campo vacío.
  No usar la cuenta guardada por inferencia de esta petición ni tomar capturas.
  Documentar envío y respuesta separadamente; la funcionalidad no está resuelta
  hasta la prueba de la APK. Comprometer solo archivos propios.
  - Coordinación iniciada: usuario confirma ADB Wi-Fi activo y juego cerrado;
    conexión establecida por mDNS, consulta de solo lectura observa C136 y ningún
    proceso WoW/Wine/Box86/Box64. Sin instalación ni cuenta. Se prepara la prueba
    aislada de privacidad con ventana sintética antes de cualquier ensayo con secretos.
  - C138 corregida instalada mediante `adb install -r`; versión posterior confirmada
    `138 / 0.2.114-assisted-entry`. No se borran datos, abre el juego ni usa la cuenta.
    A5 permanece pendiente de observación de entrada y respuesta real, no de entrega.
  - Ensayo comunicado por el usuario: todo aparece en Usuario, incluida la contraseña.
    A5 no pasa. Corregir el transporte de Tab conforme al contrato ya aprobado;
    no recoger secretos/capturas/logs para diagnosticar y no considerar las pruebas
    sintéticas anteriores evidencia suficiente de separación real de campos.
    La corrección dirige Tab/Enter a la ruta física y conserva Unicode para texto;
   13 pruebas del sender y2 de identidad/marcador pasan, con revisión del delta sin
    hallazgos. Artefacto corregido auditado e instalado tras confirmar el usuario
    el cierre; versión `0.2.114-assisted-entry-tabfix` comprobada. Falta observar
    la separación de campos y el resultado en WoW.

### Secuencia histórica de autologin automático (no habilitada)

- [x] 1. Aprobar este plan, incluida la convivencia propuesta con el escritor sin
  captura, antes de modificar esa política. Explicitarlo en US-006 UC/AC primero;
  derivar solo los FR/TC afectados y revisar dependencias con US-002/003. Conservar
  separadas las decisiones de guardar, autologin y habilitar el parche.
- [ ] 2. Reproducir el bloqueo en la integración actual con datos sintéticos y
  comprobar los hooks reales de sesión, foco, geometría, interacción y cierre.
  Comprobar un gate semántico sin imágenes sobre el formulario, sin enviar
  credenciales. Si no hay evidencia fiable de campo/modal, registrar el resultado
  concreto y detener la habilitación; no rebajar umbrales a ciegas. Debe distinguir
  foco de usuario y contraseña, no solo que la Activity o ventana tengan foco.
  - [x] Hooks del árbol canónico inspeccionados: foco de ventana X, geometría,
    puntero y teclas; no ofrecen una señal confirmada del campo interno de WoW.
  - [ ] Gate de campo/modal: bloqueado sin otra integración. Véase el
    [resultado y verificación focal](../../docs/launcher-autologin-investigation-2026-10-22.md).
  - [x] Comparar soluciones públicas de autologin: dos senders de teclado con
    espera/foco de ventana y una integración DLL específica de3.3.5a. Se verificó
    código fijado a revisión; ninguno acredita el gate de70450 ARM64 ni permite
    copiar su transporte de secretos bajo US-006. No se ejecutaron herramientas.
  - [x] Evaluar fuentes internas y accesibilidad: se localizó fuente UI etiquetada
    1.60.1.70450 con `C_Login` y un puente MSAA/UIA en fuente Wine. No acreditan
    acceso desde terceros ni el árbol efectivo del cliente/runtime instalado.
  - [ ] **Sonda aprobada:** el usuario responde «vale. hagamoslo» tras la propuesta
    de implementar y después coordinar su ensayo en el móvil. Implementar una sonda guest de
    accesibilidad de solo lectura, sin cambiar ejecutable/DLLs ni enviar input,
    para comprobar en un ensayo acordado si aparecen campos/foco/modales. Solo
    tipos/roles, foco, `IsPassword`, geometría e identidad; sin `Name`, `Value`,
    texto, credenciales, capturas ni dumps. Árbol vacío o genérico no habilita el
    autologin. No implementar un puente Glue/lector de memoria como fallback sin
    otra decisión. Detalle de límites en el informe de investigación enlazado.
    - [x] Helper ARM64 MSAA de metadatos implementado y compilado fuera del repo;
      límites de nodos/profundidad/ventanas y watchdog10s. No consulta UIA directa
      ni declara readiness. Receta/alcance en
      [README de la sonda](../../scripts/diagnostics/wine-accessibility/README.md).
    - [ ] Revisión independiente: bloqueada por indisponibilidad del worker
      (`Variant unavailable`), no por un resultado favorable. Completar antes
      del ensayo; no instalar ni ejecutar aún contra el cliente.
    - [ ] Coordinar sesión con formulario vacío y PID Windows del cliente propio;
      no usar credenciales ni capturar pantalla/logs generales. Ensayo pendiente.
- [ ] 3. Conectar intento único, detector, adquisición tardía e input interno al
  lanzamiento y cierre. Revalidar por fases y cancelar ante intervención manual,
  pérdida de evidencia o timeout; distinguir eventos propios de input del usuario.
- [ ] 4. Habilitar la opción real en Ajustes solo con esa integración disponible;
  conservar guardado independiente y fallback manual con causa comprensible.
- [ ] 5. Añadir y ejecutar regresiones focales: opt-in apagado y guardado sin envío;
  evidencia válida → un único envío; foco/modal/geometría/manual/timeout → sin
  continuación; recreación/callback tardío → sin duplicado; cierre de secretos y
  ausencia de valores en diagnósticos; entrada fiel con mayúsculas/símbolos y rechazo
  previo de secuencias no representables. Cubrir la convivencia aprobada de escritor
  y captura. Reutilizar las pruebas existentes del núcleo,
  sin repetir suites aprobadas si sus componentes no cambian.
  - [x] Delta aislado escritor ON/captura OFF y rechazo con captura ON:
    11 JVM y 1 prueba host aprobadas; no equivalen a integración de entrada.
- [ ] 6. Revisión independiente de la frontera de input, concurrencia y secretos
  antes de habilitarla en una entrega; reparar y verificar solo lo afectado.
- [ ] 7. Reconstrucción canónica, compilación y auditoría de procedencia/payloads;
  APK y recibos fuera del repo. Coordinar instalación sin borrar datos y con guest
  inactivo. Ensayo sintético aislado, sin sobrescribir la cuenta ni el alias/vault
  de producción; después, solo con autorización expresa para una cuenta de prueba,
  verificar introducción y un envío sin capturas de campos.
  Registrar por separado envío, respuesta del servidor y autenticación observada.
- [ ] 8. Actualizar FR/TC y estado operativo/checklist de entrega con evidencia real,
  revisar y comprometer exclusivamente cambios propios; cerrar y mover este plan
  a `done/` solo al cumplir su alcance o documentar explícitamente el cierre parcial.

## Open Issues

- **Ajuste de linaje aprobado —2026-10-23:** «pues corrígelo para que podamos
  avanzar». Se levanta el STOP solo para autenticar el delta anonimizado y continuar
  las correcciones ya aprobadas; no autoriza restaurar el serial ni relajar auditoría.
  La comparación focal encuentra que el
  contrato de disposición empaquetado en C137 contiene un identificador de dispositivo
  que la receta actual ya anonimiza. El contrato histórico `b65e2a…` y el actual
  `c198e2…` tienen hashes distintos; la procedencia generada también cambia.
  Conservar bytes históricos reintroduciría ese dato; admitir cualquier diferencia
  rebajaría la auditoría. Se propone aprobar un **baseline anonimizado autenticado**:
  verificar exactamente el contrato actual y su procedencia consistente, registrar
  ese delta de privacidad y mantener paridad estricta para los demás payloads,
  incluido JNI C137. No cambia el comportamiento del parcheador ni de recuperación.
  No copiar el identificador a documentos/logs ni modificar la APK histórica.
  Mantener el sender deshabilitado hasta resolver privacidad, revisión y pruebas.
  Los workers ya se han detenido: guardas de privacidad sin integración/pruebas y
  correcciones de renderer/transporte con verificación interrumpida (SIGSEGV de
  JDK21 y timeout posterior). Las seis JVM iniciales no validan el código corregido.
  Detalle y evidencia parcial en [estado C138](../../docs/launcher-assisted-entry-c138.md).

- **Aprobado:** «si, hagamoslo»; ejecución iniciada sin ampliar el uso de secretos.
- **Decisión de detección reabierta (2026-10-22):** al solicitar un formulario
  vacío para calibración, el usuario pregunta: «no hay imágenes de ese login que
  podamos usar? cada móvil es distinto no es muy fiable usar reconocimiento».
  Tras explicar las alternativas, el usuario elige explícitamente «Sin imágenes».
  Se descarta la vía visual y se pausa la habilitación mientras se comprueba si
  existe una señal semántica real de formulario/campo; no se autorizan capturas.
  Una referencia histórica no prueba fiabilidad entre geometrías/builds. Si la
  alternativa exige cambios de cliente/runtime o un lector nuevo de memoria,
  requiere presentar primero el cambio de alcance, no implementarlo implícitamente.
- **Revisión de convivencia nativa:** sin bloqueo observado para escritor ON y
  captura OFF: los chunks son transitorios, las salidas son estados/contadores y
  el escritor sigue limitado a los 32 bytes validados. No se modifica nativo ni se
  promete borrado absoluto de sus buffers. Esto no completa la revisión de input.
- **Privacidad pendiente antes de habilitar:** captura OFF no desactiva todos los
  diagnósticos. `ForeverRunStore.export` incluye JSONL, streams y logs del juego
  sin el saneamiento que sí aplica el informe remoto; el runner host
  `scripts/diagnostics/usb_forever_trial.py` también puede persistir capturas y
  salida. No se ha observado al escritor emitir credenciales, pero hay que evitar
  esos sinks durante un ensayo con secretos y proteger el flujo local de producción
  antes de declarar US-006-AC-02 satisfecha. No se ensayan con la cuenta guardada.
- **Gate seguro:** falta demostrar identificación actual de formulario/campo,
  foco y modal con la geometría efectiva de la build/dispositivo de prueba.
- **Modos de memoria/captura:** revisar la compatibilidad de la política histórica
  de autologin con el escritor Forever actual: revisión completada sin bloqueo
  nativo y delta mínimo de política revisado/aprobado. No habilita la función;
  siguen pendientes la señal semántica y los sinks diagnósticos generales.
- **Ensayo físico:** acordar dispositivo/build y autorización de la cuenta de prueba
  cuando haya candidato. No pedir contraseñas por el chat ni recuperar secretos
  temporales antiguos. Sin ensayo, la aceptación de extremo a extremo queda pendiente.
- **Trabajo ajeno:** al iniciar se observaron ediciones en US-004,
  `docs/launcher-app-status.md` y el plan de memoria; aparecieron además cambios
  concurrentes en otros planes/documentos. Este diagnóstico solo añade su plan;
  no incorpora ni compromete esas ediciones ni entradas ajenas del índice.
