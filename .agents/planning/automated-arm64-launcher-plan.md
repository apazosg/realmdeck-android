---
id: automated-arm64-launcher-2026-09-30
state: in-progress
date: 2026-09-30
---

## Summary

El usuario fija la prioridad: «no perdamos de vista el objetivo principal, que es
ser capaces de lanzar wow forever en android. el siguiente objetivo es crear el
apk "automatizado"».

El siguiente entregable debe ser **un launcher experimental de WoW Forever**, no
otro APK cuya interfaz principal sea ejecutar tres sondas manualmente. Propuesta:
un botón **Preparar y lanzar** que orqueste runtime, cliente, configuración
Forever y arranque supervisado. La instrumentación y los controles quedan dentro
de ese flujo y sirven para localizar bloqueos, no como producto separado.

El usuario aprobó este alcance con «hazlo» el 2026-09-30, incluida la provisión
Android, supervisión del proceso real e integración del parche. No declara
superadas las puertas de ejecución, render o login Forever.

## Content

### Estado y evidencia

- Base compilada: GameNative `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`, Wine
  Bionic comunitario `proton-11.0-90624-arm64ec`, DXVK AA64
  `2.4.1-wow-aarch64-test`; fuentes, hashes y recetas en
  `scripts/arm64-prototype/` y `docs/windows-arm64-runtime.md`.
- APK privado entregado: `com.wowforever.arm64probe`, SHA-256
  `35e9911b0713bc6f6ca750aef16216e453cffdb50cfeef50f49c4382faaa256f`.
  Se conserva su applicationId y firma para actualizar sin borrar datos.
  La nueva versión debe identificarse inequívocamente con versionCode superior.
- El usuario **sí ejecutó** Hello, API y DynCode, en ese orden. La barra de carga
  cambió de etapa varias veces y cada intento acabó en el escritorio Android.
  El informe `RUN-1790746460061-673305530202438` tiene los tres logs `MISSING`.
  No prueba un fallo de ARM64/PowerVR ni distingue salida del guest de crash de la
  app. No se vuelve a pedir la misma secuencia manual sin nueva observabilidad.
- `WowB-ARM64.exe` oficial de `wow_classic_beta`, build `1.60.1.70009`, verificado
  estáticamente: PE `0xaa64`, 94.286.544 bytes, SHA-256
  `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`.
  Eso no demuestra que baste con ese EXE ni que funcione la autenticación privada.
- El provisionador Linux y el contrato existente seleccionan x86_64. La API JNI
  existente expone diagnóstico sintético, no una operación productiva sobre el
  PID del juego. El núcleo del patcher sí existe y valida el almacén completo.
- La memoria sintética de un hijo propio pudo parchearse sin root en Android;
  falta probar el descendiente real de Wine y el almacén ARM64 real.

### Flujo propuesto

```text
Abrir Forever → Preparar y lanzar
  → recuperar estado / comprobar espacio y conectividad
  → instalar o reutilizar runtime y prefijo dedicados
  → obtener y verificar cliente ARM64 + metadatos + datos de arranque
  → generar WTF/BetaSuspendedTest.wtf desde el contrato
  → lanzar WowB-ARM64.exe con Wine y registrar el proceso real
  → localizar y validar el almacén / aplicar y mantener Forever
  → juego, o error de etapa visible + informe exportable
```

En lanzamientos posteriores se reutiliza lo válido. No hay biblioteca de juegos,
selección manual de Wine/DXVK, tres Play separados, Steam, Battle.net, ADB ni root.
Permisos Android, autorización de descarga y las credenciales de juego siguen
siendo acciones del usuario; el launcher no las almacena ni automatiza el login.

### Alcance del primer APK automatizado

| Área | Decisión |
|---|---|
| Base | Reutilizar el runtime GameNative ya compilado; no crear runtime propio ni integrar otro fork. |
| UI | Pantalla de Forever, acción principal única, progreso por etapa, cancelar/reintentar y exportar. Ocultar el catálogo genérico del recorrido principal. |
| Runtime | Componentes fijados y prefijo dedicado; comprobación real de instalación, ejecutables, perfiles y rutas, no solo marcadores. |
| Cliente | Provisión Android desde la CDN oficial de un conjunto de arranque ARM64 versionado. No depender de preparar un cliente en PC para el flujo principal. |
| Catálogo | Generarlo en desarrollo con el motor TACT existente y empaquetar **metadatos**, nunca el cliente. El dispositivo descarga de la CDN, con memoria y concurrencia acotadas. No portar todo cascette/Python al APK. |
| Datos CASC | Determinar el conjunto y la materialización necesarios antes de implementar el descargador. Loose y prioridad 0 son candidatos, no prueba de suficiencia ni instalación completa. |
| Forever | Portal y flags del contrato; JNI al núcleo existente con PID propio y validación completa del almacén antes de cualquier escritura. |
| GPU inicial | Vulkan del sistema y DXVK AA64 en el Poco PowerVR; registrar GPU/driver efectivo. No instalar Turnip ni prometer soporte universal. |
| Diagnóstico | Automático, persistido desde antes del primer spawn y unido a la corrida. Un control interno puede detectar un bloqueo, pero no pide operar tres custom games. |
| Distribución | Ensayo privado por SFTP con APK, hash, fuentes/receta e instrucciones. No Play Store ni release público. |

### Invariantes y límites

- No cambiar silenciosamente `forever/contract.json` de x86_64 a ARM64. Definir
  un perfil de ejecución ARM64 explícito, manteniendo portal/claves compartidos y
  los defaults Linux existentes. Especificar y probar cualquier opción nueva.
- Build inicialmente fijado a `1.60.1.70009`; otro build necesita catálogo válido.
  Validar tamaño/hash/arquitectura antes de promover ficheros descargados.
- El cliente queda fuera del APK y del control de versiones. Provisión reanudable
  y reparación selectiva; no cargar manifiestos millonarios en memoria Android.
- No tocar `Config.wtf`; la configuración Forever solo modifica
  `WTF/BetaSuspendedTest.wtf` de la instalación gestionada, preservando lo ajeno.
- No reutilizar offsets x64 por nombre. Primero escanear sin escribir el almacén
  ARM64. Solo habilitar escritura al cumplir íntegramente US-002; solo grupo 8,
  nunca ancla/grupo 1. Si no es compatible, mostrar el bloqueo y parar, no relajar
  firmas ni presentar la app como capaz de iniciar sesión en Forever.
- El host Wine puede mantener FEX/HODLL para auxiliares x86. PE AA64 principal no
  significa que no exista traducción en ningún componente del entorno.
- Supervisar exclusivamente el proceso y descendientes propios verificables.
  Cancelación, timeout y muerte de Activity no deben permitir lanzamientos tardíos
  ni una segunda corrida sobre procesos con terminación desconocida.
- No destruir sesiones, contenedores ni datos de otras apps; no tar del cliente
  vivo, no detener instaladores activos, no commits sin petición.

### Criterios de aceptación

1. **Automatización:** una acción inicia las etapas en orden; no se pide crear
   contenedores ni ejecutar accesos de prueba. Doble pulsación no duplica trabajos.
2. **Provisión:** una instalación nueva recibe de la CDN todos los recursos del
   catálogo de arranque; corte de red, disco lleno o hash incorrecto no dejan una
   instalación marcada válida. Reintentar reutiliza lo ya verificado.
3. **Juego real:** se invoca `WowB-ARM64.exe` verificado con
   `-config BetaSuspendedTest.wtf -d3d11`, registrando argv, cwd, Wine, perfil,
   mapeos de unidades y PID efectivo. Registrar invocación no equivale a render.
4. **Forever:** configuración generada del contrato y parche solo tras validación
   completa. Se distingue `pendiente/no encontrado/incompatible/aplicado/error`.
   Login real sigue siendo una puerta de validación en móvil, no un PASS sintético.
5. **Errores útiles:** un spawn fallido, salida del guest, excepción del launcher o
   interrupción deja su etapa y evidencia disponibles en la app al volver/abrirla.
   Un informe que solo diga `MISSING` ya no cumple el objetivo del diagnóstico.
6. **Evidencia:** exportar una corrida concreta por SAF incluye journal de etapas,
   versiones/hashes, stdout/stderr acotados, salida del guest y logs del juego.
   Recuperar `ApplicationExitInfo` cuando esté disponible, con procedencia y
   timestamps; no atribuir una señal sin evidencia ni registrar credenciales.
7. **Entrega honesta:** versión/certificado/ABI verificados, sin Blizzard/CASC en
   el APK. Separar runtime iniciado, cliente iniciado, render y login Forever;
   no anunciar jugabilidad hasta prueba real.

## Implementation / Action Items

- [x] 1. Fijar especificación y contratos internos del flujo automatizado.
  - [x] Crear/reconciliar la historia del launcher y perfil ARM64 con US-001,
    US-002 y SYSTEM, conservando las puertas de validación y defaults existentes.
  - [x] Definir estados persistentes, resultado del launch, catálogo de cliente y
    interfaz JNI antes de asignar componentes independientes.
- [x] 2. Preparar catálogo ARM64 de arranque con las herramientas existentes.
  - [x] Inventariar entradas/dependencias compartidas y ARM64 del build, metadatos
    y datos CASC necesarios; medir bytes/espacio y validar en un snapshot externo.
  - [x] Documentar formatos BLTE/CASC que necesita Android y comprobar que la
    materialización produce una instalación compatible, no solo un EXE suelto.
    Validación estructural con cascette: 35 loose, 832 objetos runtime, 1.402
    índices CDN; 748.157.712 bytes de transferencia inicial. Todavía no prueba
    aceptación por el cliente real ni suficiencia para streaming/render.
  - [x] Acotado a metadatos/bootstrap finito, sin motor TACT completo ni descarga
    del juego completo. La suficiencia de esos datos sigue pendiente del móvil.
- [x] 3. Implementar la pantalla y el coordinador automático sobre GameNative.
  - [x] Integrar journal y captura de spawn/salida/crash desde el primer intento;
    los controles y el exportador son partes del launcher, no otro entregable.
  - [x] Automatizar runtime/ ImageFS/ prefijo y sustituir la selección de custom
    games por el arranque controlado del cliente gestionado.
  - [x] Añadir descarga/verificación/reanudación acotadas conforme al catálogo.
  - [x] Persistencia atómica, recuperación tras interrupción, exportación fijada a
    una corrida y cancelación acotada de trabajos/procesos propios.
- [ ] 4. Integrar configuración y patcher Forever con el proceso real (código
  integrado; validación real pendiente).
  - [x] Añadir API JNI productiva y supervisión off-UI; conservar autotests.
  - [x] Exigir identidad/UID/vida del proceso propio; empezar por scan sin escritura
    y conservar las validaciones y escrituras limitadas del núcleo.
  - [ ] Comprobar en móvil el almacén ARM64 y el login antes de declarar go.
- [ ] 5. Verificar y entregar un candidato integrado, no APKs intermedios por sonda.
  - [x] Tests JVM: orden, transiciones, errores, cancelación, doble inicio,
    recuperación y exportación sin mezclar corridas.
  - [ ] Tests de catálogo/descargador con fixtures HTTP, corrupción, interrupción,
    rutas inseguras, espacio insuficiente y reparación; preservar tests Linux.
    Fixtures HTTP/hash/reanudación/rutas y cálculo de espacio probados en host;
    `StatFs`, disco lleno real y provisión Android nueva aún necesitan ensayo.
  - [x] Tests Rust/JNI dirigidos al nuevo puente y al límite de escritura; pruebas
    sintéticas no equivalen a CertStore real. Emulador solo donde el ABI lo permita.
  - [x] Build JDK 17/Gradle fijados, `--max-workers=1`; inspección del APK, firma,
    assets, provenance y revisión independiente de descarga/memoria/concurrencia.
  - [x] Candidato auditado colocado en `delivery/`, con instrucciones, audit JSON,
    SHA256SUMS y fuentes/recetas propias en un ZIP verificado. Puerto SSH/SFTP 22
    escuchando en hermes (`192.168.1.5`); firma compatible con code 23.
  - [ ] En móvil: una acción, informe automático si falla; si arranca, registrar
    render y posteriormente autenticar en Forever. No ejecutar otra vez checks
    exitosos sin un cambio relevante ni pedir pasos manuales de runtime al usuario.

## Open Issues

- **Laboratorio USB xaga (2026-10-02):** AOA2 como teclado físico permitió consentir
  y pulsar la UI propia sin SIM/root/INJECT_EVENTS, y recoger logs privados y
  externos sin exportación manual. Code 30 fallaba en una instalación limpia por
  ImageFS omitido; codes 31–33 reutilizan su instalador original, validan variante,
  roots y bibliotecas, conservan perfiles y distinguen placeholders vacíos de datos
  no vacíos. Code 33 pasa el linker y permanece vivo, pero Explorer NT `00cc`
  presenta `winex11.drv` init failed / c0000005; sin PID WoW ni frame. Ver
  `docs/usb-lab-xaga.md`. Trial recogido y detenido por paquete propio; no señal
  SIGINT espontánea demostrada aquí ni solución retroactiva al PowerVR.
  APK 362.042.153 bytes, SHA
  `ccab6f5241a73a0f45dec15307abca7381c04041cc3e7696146842116ba27b2f`.
  61 inputs frescos/idempotentes, build/test dirigido y audit PASS. Plan sigue
  abierto: X11/GLX/Explorer, luego WoW/D3D/CASC/Auth son puertas distintas.

- **Ensayo code 24 / validación de runtime (2026-10-01):** el usuario informa de
  `runtime profile has no installed file manifest: Proton-proton-11.0-90624-arm64ec-1`
  inmediatamente en `runtime: resolving pinned Wine profile`. Reproducido con el
  parser real y el `profile.json` del WCP fijado: `files: []` es una lista válida
  de copias a ImageFS, no un manifiesto del árbol Wine. Corrección acotada en
  code 25: Wine se valida por binarios/bibliotecas/prefixPack; DXVK conserva su
  lista y archivos obligatorios. Sin borrar datos, cambiar runtime ni relajar el
  patcher. Reproducción previa confirmada; 6 regresiones + 2 tests Wine, build,
  receta fresca de 44 inputs y audit final pasan. El ensayo de code 25 superó esta
  etapa y llegó a la descarga del cliente; todavía no demuestra ejecución Wine.
- **Ensayo code 25 / tamaño BLTE (2026-10-01):** `BLTE decoded size mismatch:
  154 != 181` en `client: downloading`, objeto `bc0fe26fca9629c46c33c478077ecd4d`.
  El generador confundía el tamaño VFS codificado con el decodificado. Corregidas
  828 longitudes sin cambiar bytes/descarga ni relajar MD5. Los 832 objetos completos
  pasan cascette y el lector streaming del APK en host JVM; fixture ligado al
  catálogo exacto. Pasan 13 tests Python, 9 BLTE/provisionador y los 2 tests reales
  sin skips, build, receta fresca de 45 inputs y audit. Sin borrar runtime/caché,
  sin nueva descarga de cliente; este fallo anterior al spawn permite reintentar.
- **Ensayo code 26 / HTTP 404 (2026-10-01):** fallo al descargar
  `e092b002f52c0827ce4b65d534d7316d` (3.932.016 bytes). Reproducido: el generador
  usaba `6589b9d0516fd85ed44ff57302a4cefe`, el file-index de offset cero, como
  payload de archivo. Code 27 distingue roles/anchos y usa el objeto independiente
  con SHA/BLTE/MD5 verificados. Las 35 rutas devuelven rangos 206 de 64 bytes y el
  objeto completo devuelve 200; investigación de 3.934.256 bytes, sin nuevos
  runtimes ni descarga completa. Pasan 22 tests Python, 9 JVM sin skips, receta
  fresca de 46 inputs, build y audit. Se conserva runtime/caché; no demuestra Wine.
- **Ensayo code 27 / negro y recuperación (2026-10-01):** ZIP
  `forever-RUN-1790883604709-786341652235022.zip` conservado. Pasó gates de
  preparación y creó Wine PID 18319; el usuario vio controles/negro y volvió a
  Forever home tras minimizar. No hay identidad WoW validada ni frame. El preload
  redirigía bibliotecas al package original: inspección del ELF confirmó aliases
  `WINEMU_HOST_PKG`/`HOST_PKG` ausentes. Code 28 los fija en el merge final sin
  sustituir el binario ni quitar SHM/evshim/W^X/HODLL. `WINEDEBUG` acotado no vuelve
  a quedar sobrescrito por `-all`. Se corrige estado perdido como `INTERRUPTED`,
  veto/spawn único, callbacks tardíos y atribución de salidas con identidad/run;
  preflight readonly de helpers y lifecycle exportables. No demuestra la única
  causa del negro ni reattach físico al minimizar. Pasan 40 tests JVM sin skips,
  receta fresca de 50 inputs, build y audit; JNI/CDN/Wine/DXVK/driver no cambian.
- **Ensayo code 28 / SIGINT (2026-10-02):** ZIP
  `forever-RUN-1790893361326-187779452318.zip`, host PID 6424 identificado por
  PID/package/resumen único/tiempo; terminó por SIGINT 26,690 s después del spawn
  Wine. Rutas del preload ya correctas; emisor desconocido, sin identidad WoW ni
  frame. El stderr solo conservó sus primeros 512 KiB y perdió el final. Se
  conserva la evidencia y no se atribuye el cierre al helper o a PowerVR.
- **Incidente code 29 / catálogo:** rechazo de lectura al seleccionar el comando,
  antes del spawn supervisado. Asset 2.501.689 bytes frente a cap local 2 MiB;
  reproducido con el asset real. Code 30 unifica lector cap 8 MiB, mantiene pins y
  checks del ejecutable y añade límite de asset al audit. Cinco regresiones nuevas,
  siete de ruta y cinco de provisión pasan sin skips; receta fresca 59 inputs,
  build y audit PASS. Publicado `0.2.6-automated` / code 30, mismo package/firma,
  362.010.209 bytes, SHA
  `e7e0d4b746f5b1c3af38d284215aa711ec2290fe1d4149df9d3b12a20eba30f5`.
  Delivery conserva code 29, datos y reportes; cuatro hashes y ZIP de 87 entradas
  (86 fuentes + audit) verificados. Ensayo de ejecución real sigue pendiente.
- **Historial del candidato host:** `0.2.5-automated` / code 29, mismo package/firma,
  SHA `f2f79a524875ff640793b628801c500786af0c0687ce8904b2022bc65b99c901`.
  Ruta AA64 directa verificada inspirada en el fork funcional de jaredgei, sin
  wrapper Windows únicamente para el juego gestionado. Se conservan input/IPC,
  Wine/DXVK/System Vulkan, flags y patcher; startup/layout del fork se contrastan
  pero no se mezclan en este ensayo. Head/tail atómicos, strict boot/metadata,
  ownership incluso ante error post-exec y estados terminales probados: 58 casos
  JVM en rondas dirigidas, build offline, receta fresca de 57 inputs y audit PASS.
  JNI AA64 con siete exports, SHA
  `2edcd3cf9cc80df58a80d103709e33400080d22f9b0229e90a79e7f4fcdf5bf4`;
  en Android, el observer SIGINT queda UNSUPPORTED sin tocar disposiciones.
  Evidencia y restricciones en `docs/automated-arm64-launcher.md`. La entrega no
  cierra el plan: primer provisioning, Wine/PowerVR, CASC suficiente, render y
  autenticación requieren la prueba física.
- **Un solo intento tras spawn:** hasta disponer de containment real, el muestreo
  de hijos no prueba quiescencia. Reiniciar Android antes de repetir; no borrar
  datos ni aceptar un segundo proceso sobre terminación desconocida. La UI y
  recuperación lo hacen explícito y fallan cerradas.
- **CASC/packaging:** la prueba independiente de cascette detectó un error de
  rotación de checksum-B; corregido, coinciden los 17 archivos. El audit también
  detectó provenance obsoleto en merge incremental; se rehízo solo el merge de
  assets y se verificaron los hashes dentro del APK final, no solo las fuentes.

- **Aprobación:** concedida el 2026-09-30. Se conservan los límites de descarga y
  las puertas de validación del almacén ARM64 y del login real.
- **Bloqueo de arranque actual:** Wine bootstrap sí dejó stdout/stderr en codes
  27/28; falta la identidad del hijo WoW y no hay frame. Code 28 tiene host SIGINT
  identificado, pero no emisor. El preload ya usa el package correcto. Resolverlo dentro del launcher
  automatizado; no atribuir una señal antigua sin PID/package/run/tiempo ni
  declarar render porque aparecen controles del runtime.
  Referencia adicional revisada: `docs/thor-forever-findings.md` (AYN Thor,
  ARM64/Wine 11/DXVK 2.4.1). Aporta una firma GLX concreta y prácticas de
  supervisión, pero no prueba que nuestro cierre tenga esa causa ni exige cambiar
  runtime/driver. Su portal es `test`, sin parche de grupo 8; no valida login privado.
- **Datos mínimos:** no está demostrado cuánto CASC basta ni que el cliente pueda
  completar todo por streaming. No prometer los ~0,44 GiB históricos como solución
  ni iniciar una descarga completa de decenas de GB sin autorización informada.
- **ARM64 Forever:** layout del almacén y acceso al PID real aún no validados.
  La integración puede entregarse con un bloqueo explícito, pero no como login
  Forever funcional si esa puerta no se supera.
- **Recursos/licencias:** reutilizar toolchains/componentes disponibles; cualquier
  descarga adicional grande o reconstrucción nativa que requiera nuevo NDK debe
  presupuestarse antes. Inventario de terceros pendiente para publicación.

Relación con planes anteriores: este plan coordina el entregable automatizado y
da prioridad al flujo real. `windows-arm64-runtime-plan.md` conserva la evidencia
del experimento; los prototipos Box64/FEX y sus datos no se eliminan ni reactivan.
