---
id: usb-cross-phone-2026-10-27
state: in-progress
date: 2026-10-27
---

## Summary

La primera fase autorizada fue pausa, inspección sin cambios y devolución:
«es mi móvil. activo el adb wifi también y probamos. avisame y quito el USB de uno
y lo pongo en el nuevo». Después confirma «Ya está enchufado en el mío» y facilita
un endpoint privado. Después aclara: «pero tenemos que resolverlo en este móvil no?
el objetivo es poder moverlo de un móvil a otro». En ese corte el USB permanece en
el segundo móvil: devolverlo al original no constituye una solución de portabilidad.
La inspección y C160 ya están verificadas. El usuario eligió **«Ambos»**:
reutilizar el cliente terminado y continuar el parcial en el segundo móvil.
La selección está resuelta y después aprobó **«Aprobar criterios»** para
US-009-UC-05 / AC-06–09. Después aprobó FR-009–013/NFR-004 y el protocolo para
preparar las pruebas. El 28-10 aprobó también TC-007–013 para implementación local;
no se declara todavía adopción implementada ni ejecutada. No se ha reiniciado el goal
de rendimiento.

El 28-10 el usuario pide «haz el goal o continua el que habíamos hecho».
Se crea el goal de portabilidad `c51e88cc-70df-4253-9ab3-147edad83c37`, con margen
máximo de seis horas y planificación persistida para ambos casos. El antiguo goal
de rendimiento conserva su cierre no logrado; no se sustituye su aceptación por
esta tarea. Se continúa desde la fixture original sin historial privado verificada
el 28-10, sin repetir emparejamiento ni inspecciones. Al crearlo faltaba la aprobación
de TC-007–013, recibida después, seguida de la autorización de crear, instalar y
probar APKs auditadas en el dispositivo conectado. La adopción conserva su plan real.

El 29-10, tras agotarse el límite, el usuario pide «por qué no se ha probado ni
instalado nada? continua o crea un nuevo goal». Se sustituye el anterior por
`ee5a2c15-f52b-48a0-b625-3ce59725a357`, conservando los seis criterios, aprobaciones,
commits y evidencias. La prioridad es cerrar la integración del flujo completo,
probarlo, auditar e instalar la candidata y validar el USB; no acumular más
prerrequisitos aislados. Las pruebas locales anteriores sí se ejecutaron, pero
todavía no demuestran adopción real. No se vuelve a pedir el permiso ya concedido
para crear, instalar y probar APKs; sigue siendo necesario el consentimiento del
coste y la adopción del plan real antes de mutar propiedad o iniciar payload.

El 29-10 autoriza «si ampliamos 12 horas el goal. pero debemos ser efectivos».
El sucesor `c07e6f7f-a0e6-4b71-bc73-b34caab2eee4` dispone de un máximo de 12 horas;
conserva los seis criterios y las tareas/evidencias anteriores, sin declarar
logrado el goal agotado. Punto de continuación: integración `f3f3fc3` probada
localmente y postimages de reconstrucción `1eeaeb7`; no hay APK nueva ni adopción
física. Prioridad: revisión del código integrado, candidata exacta auditada e
instalación/prueba real, sin repetir pruebas cerradas ni permisos ya concedidos.
La revisión solicitada en este corte resultó inconclusa por falta de acceso del
revisor a los archivos; no se considera una aprobación del código.

## Content

- Original: C160 cerró 19:14:11 UTC con **822.658 objetos durables**, prefix/CRC y
  retención raw válidos, callbacks/HTTP/fases inactivos. Lectura fresca 19:16:32:
  pausa, cero servicios updater y cero hilos fetch/prefix. Evidencia externa en
  `usb-cross-phone-20261027/source-preflight/summary.json`.
- Datos CASC parciales en staging USB, checkpoint y recibo en almacenamiento
  privado del original. `readStageReceipt`/`resumeGuardState` rechazan staging sin
  recibo propio: un rechazo del segundo móvil no demuestra pérdida del contenido.
- No copiar recibos/checkpoints, falsear `.build.info`, publicar staging, saltarse
  guardas ni anunciar cliente completo. Aplican [US-007](../specs/US-007.md),
  [US-008](../specs/US-008.md) y [US-009](../specs/US-009.md); se conservan las
  guardas normales; la nueva entrada tiene requisitos y diseño aprobados, con
  casos de prueba aprobados pendientes de implementación y ejecución.
- El usuario realiza el traslado físico y la expulsión de Android. La ausencia
  de escritores de la app no garantiza ausencia de escrituras del sistema.
- En el teléfono personal, conectar solo al endpoint facilitado. No heredar la
  autorización amplia del dispositivo dedicado: no inspeccionar otras apps,
  cuentas, mensajes, fotos o credenciales, ni cambiar permisos/seguridad/ajustes.
- Revisión de la app y del volumen sin Descargar/Reanudar/Reparar/Preparar carpeta,
  borrado, formato, copia, adopción o instalación/reemplazo de APK no acordado.
  Si no existe una ruta segura de lectura, detenerse y comunicarlo.
- Identificadores y evidencias fuera de Git, conforme a la
  [referencia de control](../references/device-control-scrcpy.md). Mantener ambos
  endpoints separados; no reutilizar el selector automático del original.
- Baseline privado válido. Los listados USB por `exec-out` contienen errores
  `ls` pese a returncode0: evidencia rechazada en `source-usb-inventory-v2/verification.json`,
  no prueba de datos desaparecidos ni inventario válido. No existe hash de todo
  el volumen; no prometer igualdad binaria o integridad completa de sus bytes.
- El nuevo endpoint facilitado por el usuario conecta: Android SDK36. La lectura
  inicial encontró C146 `0.2.122-performance-preview`; el reemplazo autorizado
  posterior por C160 se registra abajo.
  El volumen esperado está montado. El contexto `run-as` recibe Permission denied
  incluso en la raíz del volumen; shell UID2000 puede leer las rutas exactas.
  Esto no demuestra qué acceso tiene la app ejecutada normalmente.
- Inspección de metadatos 19:39:44 UTC: staging esperado, marcador `.owner` cuyo
  digest coincide con el recibo privado original y 81 archivos `data.000`–`data.080`,
  51.625.635.891 bytes (48,08 GiB). Sin `.build.info` publicada en la raíz.
  Cero servicios updater antes/después, cero acciones UI/escrituras/permisos.
  No se han leído ni autenticado todos los cuerpos CASC; no es una prueba de
  integridad completa o de reanudación en el segundo móvil.
- Resultado y límites en [el informe USB](../../docs/usb-cross-phone-results.md).
- Ampliación explícitamente aprobada: el usuario seleccionó «Actualizar C160».
  Se permite reemplazar únicamente la app personal por la APK C160 exacta ya
  auditada, mediante `adb install -r --no-streaming`, sin desinstalar/borrar datos.
  Antes: comprobar hash/tamaño/auditoría y ausencia de updater/guest de esa app.
  Después: comprobar versión, UID conservado y metadatos del staging sin iniciar
  descarga. No se fuerza una firma incompatible ni se reintenta una instalación
  de resultado incierto; no se cambia seguridad ni se migra el checkpoint.
- Reemplazo ejecutado una vez, final 19:52:23 UTC: Android confirmó `Success`,
  versión C160 `0.2.136-bounded-blte-buffers-test` y mismo UID. Cero servicios updater
  antes/después; `.owner` y diccionario completo de nombres/tamaños de los 81 CASC
  sin cambios. No lanzamiento de app, descarga ni juego. No se cotejaron todos los
  datos privados ni los cuerpos CASC. Evidencia externa:
  `personal-c160-install/summary.json` y `adb-install.txt`.
- El usuario pidió abrir y observar la app: «hazlo tú mejor así ves qué ocurre».
  Lanzamiento único de su actividad, 20:06:37 UTC; sin iniciar juego ni operaciones
  del cliente. Veredicto observado: **«Almacenamiento seleccionado no disponible»**.
  El único botón pulsado fue `Detalles`, que solo despliega la ruta: **«No conectado»**.
  Consulta de la operación `MANAGE_EXTERNAL_STORAGE` de esta app: `default` y
  rechazo reciente; no se ha concedido ni cambiado permiso alguno. Esto no prueba
  una causa única ni que conceder ese permiso permita reanudar el parcial.
- Metadatos del USB antes/después de abrir la app iguales: marcador y nombres/tamaños
  de los 81 CASC, 51.625.635.891 bytes. Cero servicios updater; se verificó que el
  proceso adicional de su UID es `/system/bin/logcat`, no un guest. El primer
  controlador se detuvo por rechazar conservadoramente ese logger; se conservó el
  fallo y se completaron solo lecturas faltantes, sin repetir el lanzamiento.
- No se guardaron XML de interfaz sin filtrar ni etiquetas de otras apps. El
  controlador no envió comandos de ajustes; el código C160 preexistente solicita
  volumen multimedia 1 al abrir. No se midió ni restauró el nivel anterior.
- Lectura dirigida 20:24:37 UTC: el identificador `volume` guardado por la app
  **no coincide** con el USB montado, tampoco normalizando mayúsculas. El marcador
  de la raíz sí coincide con la identidad del volumen montado. No es el caso del
  antiguo identificador basado en hash de ruta. Solo se leyó el fichero privado
  específico de selección de almacenamiento; no se modificaron preferencias.
  Evidencia externa: `personal-storage-binding/summary.json` e `identity-shape.json`.

### Continuación aprobada en alcance: cliente terminado y parcial

1. Corregir primero el reconocimiento/selección del USB real mediante el flujo
   normal de la app: enumeración Android, identidad, ruta, marcador y ausencia de
   I/O/guest comprobados. No editar preferencias a mano, elegir por nombre ambiguo,
   crear/preparar carpetas ni borrar el staging. Si el destino no aparece o no pasa
   sus validaciones, diagnosticar ese fallo en lugar de forzar la selección.
   Una selección confirmada puede ejecutar su preflight normal; debe conservar los
   datos existentes conforme a US-007-AC-04/FR-004. Corrección ejecutada una vez
   20:45:46 UTC, con las comprobaciones posteriores indicadas abajo.
2. Mantener **sin permiso de acceso a todos los archivos**: US-007-NFR-002,
   US-009-NFR-002 y US-008-AC-05 lo requieren. El estado `default` observado no
   demuestra por sí solo un defecto ni justifica conceder ese permiso. Sin root,
   excepciones de seguridad ni presentar una URI SAF como ruta ejecutable de Wine.
3. Para un cliente terminado, comprobar reconocimiento/revisión y reutilización
   con las verificaciones de US-009, sin descargar o duplicar lo ya válido. El
   runtime del segundo teléfono conserva sus propias precondiciones; reconocer
   archivos no demuestra que el juego arranque en ese hardware.
4. **Decisión del usuario: «Ambos»**, incluyendo continuar una descarga parcial.
   El USB actual no contiene una instalación publicada y el checkpoint/
   recibo siguen en el original. Ese traspaso no existe en el updater actual.
   Los UC/AC, FR/NFR y el diseño de US-009 están aprobados; revisar los nuevos
   TC-007–013 aprobados el 28-10 para implementar la adopción verificada.
   No copiar estado privado, falsear recibos/checkpoints o publicar un parcial
   como solución provisional; no empezar otra descarga completa automáticamente.
5. Antes de cualquier cambio de cliente: plan/coste/consentimiento explícitos,
   integridad, límites, quiescencia y recuperación preservados. Si hace falta otra
   APK, identificar y auditar esa candidata antes de su instalación ya autorizada.
   El desarrollo local no modifica la C160 instalada ni adopta por sí solo el USB.

La respuesta «Ambos» confirmó el alcance y «Aprobar criterios» confirmó los UC/AC.
La aprobación posterior de FR/NFR y del protocolo autoriza derivar sus casos de
prueba. Después autorizó implementar esos casos y crear, instalar y probar APKs.
Nada de ello es evidencia de reutilización: se conservan auditoría, pruebas y el
consentimiento del plan real antes de adoptar o descargar.

### Selección corregida y puerta de adopción pendiente

- Se abrieron Ajustes/Almacenamiento sin confirmar selección: aparecieron una
  ubicación antigua desconectada y una única «Unidad USB» removible habilitada.
  Las preferencias no cambiaron en esa observación. Un primer intento se detuvo
  sin acciones al no estar la app en primer plano; se preservó ese informe y se
  trajo únicamente la app propia al frente antes de completar las lecturas.
- Selección confirmada una vez mediante la app: identidad persistida y ruta
  coinciden con el USB/raíz original. Su preflight normal crea/renombra/lee/elimina
  una prueba temporal de 16 bytes y guarda la selección/registro privados;
  **no** es ausencia absoluta de escrituras. Cero servicios updater antes/después.
- Cierre 20:46:03 UTC: misma raíz y `.owner`, mismos 81 nombres/tamaños CASC,
  51.625.635.891 bytes; mismas dos entradas raíz, sin restos de la prueba.
  Consulta posterior de la app confirma que acceso a todos los archivos **no está
  permitido**. Sin Descargar/Reparar/Preparar/adopción, juego o cambio de seguridad.
- El cliente sigue «no instalado» porque los datos observados son staging, no una
  publicación. La selección resuelta no demuestra autenticación de todos sus
  cuerpos ni reanudación. No se recuperaron ni copiaron recibos o checkpoints originales.
- Inspección de código: las APIs actuales de CASC/updater exigen checkpoint/recibo
  privado ligado a identidad; no hay entrada de adopción de ese parcial en otro
  teléfono. Los segmentos pueden investigarse para recuperar objetos, pero su
  presencia o `.owner` no demuestra integridad ni qué se reconoció durable.
- El usuario aprobó [US-009-UC-05 y AC-06–09](../specs/US-009.md):
  distinguir completo/parcial, reutilizar solo objetos autenticados, establecer
  estado nuevo sin copiar el privado del original y recuperar fallos sin pérdida
  ni sobrescritura por estado antiguo. FR-009–013, NFR-004 y el diseño han sido
  aprobados, y TC-007–013 aprobados el 28-10; no se omite la guarda actual. El
  [diseño aprobado](../../docs/usb-portability-recovery-design.md) separa revisión,
  verificación, plan y adopción con generación nueva de propiedad sin mover CASC.
- Evidencia externa: `personal-storage-options*/summary.json`,
  `personal-current-usb-selection/summary.json`, sus metadatos antes/después y
  `permission-verification.json`. No repetir esas acciones ya confirmadas.

## Implementation / Action Items

- [x] 1. Cerrar el controlador original y verificar pausa/checkpoint/ausencia de trabajo.
- [x] 2. Usuario comunica traslado al segundo teléfono; no se presupone reutilización.
- [x] 3. Conectar ADB al endpoint autorizado; comprobar versión Android y app instalada.
- [x] 3a. Actualización C160 expresamente aprobada: reemplazo preservador y verificación,
  sin operaciones sobre el cliente ni lanzamiento automático del juego.
- [x] 4. Inspeccionar reconocimiento y veredicto sin operaciones del cliente,
  registrando límites: almacenamiento no disponible, ruta «No conectado»;
  conservación de metadatos comprobada, reutilización/reanudación no demostrada.
- [x] 5. Acordar la portabilidad requerida: usuario confirma «Ambos», manteniendo el
  USB en el segundo móvil. Identificado el impacto US-007/008/009; UC/AC del nuevo
  tratamiento parcial aprobados, implementación pendiente. La devolución queda
  retirada como próximo paso, pues no resolvería el objetivo aclarado.
- [x] 5a. Selección del volumen real resuelta por preflight normal: identidad/ruta
  correctas, sin permiso amplio, 81 metadatos CASC y marcadores preservados.
- [x] 5a.1. Usuario aprueba los criterios de portabilidad UC-05 / AC-06–09.
- [x] 5a.2. Usuario aprueba FR-009–013/NFR-004 y el diseño para preparar las pruebas.
- [x] 5a.3. Usuario aprueba TC-007–013 el 28-10 para implementación y pruebas locales;
  no se declaran todavía cobertura ejecutada.
- [ ] 5a.4. Implementar y verificar el protocolo aprobado tras la puerta de tests,
  con revisión sensible y APK exacta auditada antes de la instalación autorizada.
- [ ] 5b. Validar reutilización del cliente terminado y continuidad del parcial
  mediante el protocolo verificado y consentimiento del plan real; no asumirla.
- [ ] 6. Documentar conservación, reutilización observada o bloqueo seguro y pendientes.

## Open Issues

- 2026-10-30: la primera reparación del scratch fue rechazada porque podía borrar
  archivos arbitrarios con nombres reconocidos, y faltaba validar ancestros antes
  de la limpieza y del fence. La corrección local `c739aa9` conserva y bloquea
  scratch anterior desconocido; para intents durables nuevos vuelve a autenticar
  sin crearlo y exige el digest de inventario de la verificación previa. Revisión
  independiente final sin hallazgos. Seis tests focales sin fallos/errores/skips
  y paridad SHA-256 de 41 fuentes: `usb-portability-local-20261028/
  recovery-scan-scratch-retest-20261030T023132Z-3884381/summary.json`. Esto no
  constituye validación física ni establece que un scratch antiguo sea limpiable.
- C168 construida y auditada, todavía no instalada. El primer build se detuvo
  durante reconstrucción al detectar el postimage CASC anterior; no llegó a Gradle.
  `5bd0159` fija el SHA-256 revisado y dos tests de reconstrucción pasan. El
  reintento independiente con `fc4e32c` terminó Gradle offline en 8m02s y pasó
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2231 fuentes, 178 payloads y 42 fuentes de
  portabilidad coincidentes; certificado y permisos iguales a C167. APK de
  485401519 bytes, SHA-256 `dd08feaaa74d94934dba80060de4db7c05dc58f4488113ef5631ff3d79d31549`,
  comprobado de nuevo por separado. Evidencia `usb-portability-c168-retry-20261030/`;
  el fallo inicial se conserva. La última instalada confirmada es C167 y no hubo
  otra revisión física, adopción ni payload después de su ensayo de 900 s.
- Tras una única sustitución C168 preservadora y verificaciones completas, se
  prevé una revisión exclusivamente funcional de la fixture en el móvil original,
  con tope global de 7200 s que reserva tiempo de cancelación e idle. La creación
  incompleta del catálogo anterior se descartó al cancelar, por lo que repetir
  mecánicamente el límite de 900 s puede detenerse antes de la autenticación; este
  ensayo no vuelve a abrir ni acredita el objetivo de rendimiento. Detenerse en
  el plan real o fallo, sin pulsar adopción ni payload. No acredita por sí solo la
  portabilidad entre teléfonos distintos; esos casos siguen pendientes.

- C167: revisión física única 2026-10-30 00:47:12–01:02:52 UTC sin
  resumen antes de 900 s; a los 890,6 s preparaba 1077248/1144978 objetos
  del catálogo, sin llegar a autenticación del USB. El controlador confirma
  cancelación cooperativa e idle; `clientOperations=[]`, sin adopción/payload.
  Evidencia `usb-portability-c167-20261029/partial-review/summary.json`.
  El objetivo de 900 s continúa UNMET y no se reabre como optimización. Para
  los dos casos funcionales sigue faltando una revisión que llegue al plan
  real; no convertir esta observación ni los 822658 antiguos en progreso
  recuperado. Primero resolver el hallazgo sensible y validar la candidata.

- Revisión sensible independiente C167: detectó que fallos/cancelación después
  de completar el checkpoint preparado y antes del intent pueden dejar estado
  privado propio sin limpiar; reintentos acumularían espacio. `c739aa9` limpia
  solo artefactos propios verificados cuando no hay intent, reautentica el
  checkpoint vivo y bloquea huérfanos reconocidos sin eliminarlos; se conserva
  todo estado necesario para recuperación tras intent. Una muerte abrupta antes
  del intent o un recibo posterior a su retiro pueden dejar huérfanos bloqueantes:
  la política segura es conservarlos, no forzar un reintento o borrado.
  El otro hallazgo propuesto, enumeración no acotada, no se confirma:
  la búsqueda de stages retorna al encontrar la segunda entrada o una inválida;
  en la raíz vacía solo se permiten dos nombres distintos y se corta en el primero
  distinto. La revisión no es prueba física ni aprobación del flujo completo.

- C167 instalada el 2026-10-30 00:45:33 UTC tras reconexión: Android confirmó
  `install -r`, versión 167 y mismo UID; cero servicios updater antes/después y
  marcador más 81 archivos CASC/51625635891 bytes sin cambios de metadatos.
  No lanzamiento, adopción ni payload; no se cotejaron todos los cuerpos. El ensayo
  único posterior de 900 s y su cancelación se registran arriba; no repetirlo como
  prueba de rendimiento. Propiedad/payload siguen sujetos al consentimiento del
  plan real, que aún no se ha mostrado.

- 2026-10-30 00:43 UTC: el primer preflight C167 se detuvo al no estar el
  endpoint ADB registrado; no hubo intención de instalación ni acción UI. Se
  reconectó el endpoint autorizado y se confirmó `device` y modelo original.
  Se preparó un controlador nuevo, con salida exclusiva y verificación del
  intento anterior sin instalación, que conserva todos los controles originales;
  no se repite el controlador cerrado ni se borra la app o el USB.

- C167 (`c5720f9`): build canónica terminada 2026-10-29 23:00 UTC;
  auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2231 fuentes y 178 payloads,
  paridad de diez fuentes y certificado/lista de permisos iguales a C166.
  APK 485385195 bytes, SHA256
  `1d89a5928d4a0e21fb63515ccb01712fce08af6c7ecb300beb09ffa241af0d00`,
  rehash comprobado. Próximo paso autorizado: un único reemplazo preservador
  C166→C167, condicionado a dispositivo, versión, updater/Wine inactivos,
  revisión idle y metadatos del USB; sin borrar, lanzar app ni iniciar cliente.
  La auditoría no demuestra portabilidad ni cumplimiento del límite de 900 s.

- Reutilización de índices privados implementada: solo claves solicitadas por
  el CDN actual, cachés hermanas acotadas del mismo build/config, propietario,
  recibo, tamaño y SHA comprobados antes de entregar el archivo al parser normal.
  No recuperación ni borrado en la caché anterior; no reutilización de catálogo
  terminado de otro CDN. `metadata-reuse-host-retry-tests/summary.json`: 2 tests,
  cero fallos/errores/skips y paridad de fuentes; Gradle offline 5m34s. El primer
  intento falló por SIGSEGV C1 del JVM host antes de JUnit: no resultado de producto.
  Reintento desde árbol limpio con C1 desactivado solo en el host/Kotlin in-process.
  Revisión independiente leyó diff y fuentes reales por snapshot HTTP; sin hallazgos,
  pero test de transporte no prueba descubrimiento/parser en producción ni 900 s.
  Servidor de revisión cerrado. Pendiente candidata auditada y prueba física;
  no adopción, payload, modificación de USB ni nueva instalación en este paso.

- 2026-10-29 22:36 UTC: lectura acotada de nombres de índices en las dos
  cachés privadas existentes: 706 y 1412 archivos, 705 nombres coincidentes.
  Esto no autentica contenido ni admite ubicaciones antiguas. La corrección
  siguiente reutilizará únicamente un índice solicitado por el CDN ACTUAL,
  con recibo de caché propietaria, tamaño y SHA verificados, y conservando el
  parser normal del resolver. No reutilizar un catálogo terminado de otro CDN.
  La delegación de implementación no tuvo acceso al repositorio ni hizo cambios;
  el padre retoma directamente esos archivos. No hay trabajo de worker pendiente.

- C166: revisión única 21:43:53→21:59:13 UTC, sin resumen antes de 900 s;
  cancelación cooperativa e idle confirmados y cero updater posterior. No adopción
  ni payload. Las muestras muestran preparación de metadatos (download/size/root
  y catálogo de archivos), no el contador de autenticación USB. Diagnóstico de
  solo lectura en `usb-portability-c166-20261029/metadata-postmortem`: catálogo
  terminado con fingerprint anterior; caché nueva para otra configuración CDN,
  manteniendo build 70532 y build-config. El resolver solo reutiliza el directorio
  final de la fingerprint exacta (incluye CDN). No atribuir este ensayo al coste
  de las búsquedas por lotes ni saltarse esa frontera de confianza. Próximo análisis:
  reutilización de metadatos con identidad criptográfica compartida, sin asumir
  que los índices/ubicaciones de otra CDN son válidos. Mantener las dos evidencias
  y no repetir esta revisión ni ninguna instalación/prueba ya cerrada.

- C166 instalada: final 2026-10-29 21:41:42 UTC, `install -r` confirmado,
  UID conservado, cero updater antes/después, marcador y 81 archivos/
  51625635891 bytes sin cambios de metadatos; no lanzamiento automático ni
  adopción/payload. Revisión real siguiente: una acción Revisar, 900 s desde
  intención hasta resumen confirmable incluyendo metadatos/auth/plan; sin
  perfilador ni GET auxiliar. Observador solo navega Ajustes si Management no
  está ya abierto; nunca repite una acción enviada. Al límite cancela y confirma
  idle. `review-calibration.json` valida sintaxis/paridad de helpers sin repetir
  sus pruebas. No se acepta velocidad ni portabilidad antes del resultado real.

- C166 canónica: `1de8a65`, Modern offline 5m31s, auditor sin cambios
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2231 fuentes coincidentes con recibo,
  178 payloads. APK 485379823 bytes, SHA256
  `cc1f9b2bb02ca4bdb3654227905ee8d7b65c3ff2dd2f822990e6bed9179791f3`.
  La revisión independiente pendiente se completó tras nueva comprobación del
  catálogo del proveedor: el revisor leyó manifiesto/diff y fuentes completas
  inmutables por HTTP local; sin hallazgos accionables, sin repetir tests ni
  validar por ello velocidad/USB físico. Snapshot cerrado. Reemplazo preservador
  C165→C166 bajo autorización vigente: preflight nuevo exige review idle,
  versión/UID/guest/updater y marcador+81 archivos; un solo `install -r`, sin
  borrar datos ni lanzar operaciones. Después deberá medirse la revisión real;
  no se declara conseguido el objetivo de 900 s ni la portabilidad completa.

- Consultas por lotes implementadas en la revisión explícita: máximo 64
  descriptores/cabeceras y cuatro autenticaciones pendientes; respuesta oficial
  ligada a clave completa, ordinal y orden. Mantiene límites, auth/EOF, callbacks
  físicos, cancelación/join y defaults de preparación/recuperación. Prueba local
  única `batched-foreign-membership-tests`: 4 tests, cero fallos/errores/skips y
  paridad de fuentes; incluye respuesta desordenada rechazada y fuente preservada.
  No repetir esos checks. Revisión independiente solicitada con snapshot HTTP
  real, pero ambos intentos fallaron por variante de proveedor no disponible;
  no se obtuvo aprobación. Gate abierto antes de despliegue. Sin nueva mejora
  física ni 900 s aceptados; todavía debe medirse y completarse la portabilidad.

- Diagnóstico corto C165 cerrado: única revisión 21:10:19→21:13:50 UTC,
  perfil propio de 15 s capturado y cancelación/idle confirmados; ninguna adopción
  ni payload. El primer intento había fallado antes de cualquier click porque
  Management ya estaba abierto; se conservó y solo se continuó desde la pantalla
  real. Traza `diagnostic-current-profile` (3281750 bytes, SHA256
  `92a277524fd9138b6440290e7ea2a27dbdaffcf8e5248271fc503972bf27e7a5`):
  siete consultas host nuevas, sin recaptura, sin errores/pérdidas reportados;
  cuatro errores parciales de unwind en main/GC. Productor: 99 muestras,
  4,879902096 CPU-s, 54 muestras inclusivas en membership (27 objectByKey,
  25 ordinalOf). Workers de autenticación: 11,49454332 CPU-s en conjunto,
  con BLTE/inflate efectivo. Frames inclusivos no son partición de tiempo.
  Corrección siguiente: consultas existentes por lotes de claves/ordinales,
  descriptores acotados a 64 o al máximo admitido menor; conservar cuatro
  futuros, orden físico, autenticación completa, cancelación/join y fallback
  secuencial de preparación/recuperación. Pruebas focales y revisión antes
  de nueva candidata; no se atribuye aún mejora física ni cumplimiento de 900 s.

- Diagnóstico siguiente de C165, distinto de la medición cerrada: una revisión
  nueva con máximo 300 s para observar autenticación, un único perfil de CPU/
  scheduling de 15 s con la configuración ya validada y cancelación cooperativa
  inmediatamente después, también si falla la captura. No es otra prueba de
  900 s, ni adopción o payload. `diagnostic-calibration.json` comprueba sintaxis
  y paridad de las guardas de cancelación; los resultados anteriores se conservan.
  El perfil debe distinguir trabajo del productor/catálogo y workers BLTE/lectura,
  sin atribuir tiempo exclusivo a frames inclusivos ni aumentar concurrencia a ciegas.

- C165: la única revisión real terminó el 2026-10-29 a las 20:52:13 UTC;
  no produjo el resumen en 900 s. Última muestra a 902,065 s: 212295 objetos
  y 13,5 GiB autenticados provisionalmente. Cancelación cooperativa e idle
  confirmados, cero updater en la lectura posterior; sin adopción ni payload.
  Evidencia: `usb-portability-c165-20261029/partial-review/verdict.json`.
  El servicio systemd está inactivo; los shells/observadores perdidos están
  cerrados y sus esperas liberadas. No repetir instalación, auditoría, tests
  ni la prueba de 900 s. Los cuatro workers no demuestran por sí solos una
  mejora física. Siguiente discriminante: perfil corto NUEVO de C165 durante
  autenticación, distinguiendo productor/membership, lectura y workers BLTE;
  después cancelar esa observación. No elegir más concurrencia ni cambiar
  canales/guardas basándose únicamente en el resultado agregado.

- C165 instalada una sola vez: fin 20:33:18 UTC, UID conservado, cero updater,
  marcador y diccionario completo de 81 archivos/51625635891 bytes sin cambios;
  no lanzamiento automático ni adopción/payload. Protocolo de prueba real:
  una revisión nueva, 900 s desde la intención de pulsar Revisar hasta resumen
  confirmable (metadatos+autenticación+planificación), sin perfilador ni GET auxiliar;
  si no termina, cancelar cooperativamente y comprobar idle. Nunca pulsar Reutilizar
  o Descargar desde el observador. Comparación secuencial conserva límites de caché,
  carga y temperatura; no prometer aceleración x4 ni éxito por contar workers.

- C165 canónica (363fa76), reconstrucción/Modern offline 5m26s y auditoría sin
  modificar `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2231 fuentes coincidentes con
  recibo, 178 payloads; APK 485372567 bytes, SHA256
  `2ff5a8f1c65d6014b0a6294f90b37495890753b788ae2a9ad009189aa374a421`.
  Reemplazo preservador C164→C165 bajo autorización vigente: preflight fresco
  deberá confirmar revisión idle, versión/UID, ausencia updater/guest y marcador+81
  nombres/tamaños; una sola instalación `-r`, sin clear/uninstall ni autoarranque.
  No confirmar adopción/payload. Rendimiento de 900 s y portabilidad física
  siguen pendientes; no deducirlos de las cinco pruebas locales ni de la auditoría.

- Autenticación paralela explícita implementada: hasta cuatro rangos streaming,
  publicación de resultados en orden físico, drenaje por segmento y cancelación/
  unión de todos los workers antes de cerrar canales o limpiar. Preparación de
  checkpoints y recuperación conservan el modo secuencial por defecto. Sin cuerpos
  completos en RAM ni cambios de escritura, cotas o validaciones criptográficas.
  Pruebas focales `parallel-foreign-auth-tests`: 5/0 fallos/errores/omitidos,
  paridad de fuentes, Gradle offline 7m57s; concurrencia demostrada con rendezvous,
  orden, corrupción/cancelación sin workers vivos, checkpoint y caller Updater.
  Revisión independiente leyó el diff y fuentes/callers reales mediante snapshot
  HTTP inmutable; sin hallazgos, sin repetir tests. Aún no prueba los 900 s ni la
  portabilidad física. Postimages CASC fijados a esas fuentes; auditor sin cambios.

- C164 no cumple el objetivo de 900 s: revisión iniciada una sola vez a las
  19:51:52; última muestra a los 886.285 s, 208134 objetos/13.2 GiB autenticados
  provisionalmente, sin resumen completo. Se canceló localmente al límite;
  terminó 20:06:57 sin error, idle confirmado y cero updater comprobado después.
  No adopción ni payload. Evidencia `usb-portability-c164-20261029/partial-review/`
  (`summary.json`, `verdict.json`, `services-after.txt`). La ventana incluyó un
  perfil de 15 s, no se presenta como ensayo sin instrumentación. Los contadores
  no son progreso adoptado ni sustituyen la autenticación completa pendiente.

- Perfil real C164 de 15 s (29-10, 19:58:45–19:59:01): el coste dominante ya no
  son las comprobaciones FUSE repetidas. El worker tiene 205 muestras CPU, 152 en
  verificación BLTE y 116 en inflate; tiempos inclusivos, no sumables. Consume
  10.6365 s CPU/15 s; GC 3.6043 s. El contador observado a 349 s era 47148 objetos/
  3.1 GiB, insuficiente para aceptar 900 s. Evidencia fuera de Git en
  `usb-portability-c164-20261029/active-review-profile/analysis`.
  Próxima corrección acotada propuesta dentro de la revisión explícita: hasta
  cuatro autenticaciones independientes en paralelo, streaming sin cuerpos
  completos en RAM, con admisión/callbacks en orden físico y bajo la misma
  exclusión; drenar antes de cerrar cada segmento y cancelar/unir los workers
  antes de devolver o limpiar. Sin paralelizar mutaciones ni cambiar checks,
  cotas de descarga o Home/Resume. Exige pruebas focales, revisión sensible y
  nueva candidata exacta antes de cualquier instalación. La medición C164
  original no se reinicia y conserva su cancelación a los 900 s.

- Sustitución C163→C164 terminada el 29-10 a las 19:48:43, instalación confirmada,
  mismo UID, cero updater antes/después y marcador+81 nombres/tamaños CASC
  idénticos (51625635891 bytes). No autoarranque ni operaciones del cliente.
  Nueva medición prefijada `usb-portability-c164-20261029/review_partial.py`:
  una apertura/navegación y una revisión explícita; tiempo conservador desde
  intención de click hasta resumen observado, incluyendo metadatos/autenticación/
  planificación, muestras cada 15 s. A los 900 s sin resumen se solicita cancelación
  local y se comprueba idle; no aceptar una revisión parcial ni pulsar adopción o
  descarga. Los fallos transitorios de XML se registran y no reinician la revisión.

- C164 canónica terminada el 29-10 a las 19:46:29: build offline 5m22s,
  auditoría exacta `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2231 fuentes
  coincidentes con el recibo y 178 payloads. APK 485364419 bytes, SHA-256
  `e4da3f0970c7937c6dcff2676c271d99c1f85981f022947ab2eb4164e122dd54`.
  Se prepara sustitución preservadora autorizada C163→C164, exigiendo UI idle
  real además de cero foreground updater, mismo UID y marcador/diccionario
  completo de nombres/tamaños CASC antes/después. No autoarranque, adopción ni
  payload desde el instalador. Solo la medición posterior podrá aceptar <=900 s.

- Corrección guiada por perfil físico C163, 29-10: captura única de 15 s,
  `usb-portability-c163-20261029/active-review-profile/analysis/`: 51 muestras
  del hilo verificador, 45 inclusivas en revalidación por objeto, 34 en el
  clasificador de namespace/marcador y 6 en BLTE; no sumar contadores inclusivos
  ni interpretarlos como partición del tiempo bloqueado. Sin pérdidas reportadas.
  Corrección implementada: autenticación/cancelación por objeto y validación completa
  al entrar, cambiar de segmento y finalizar. Solo con ambas capacidades dirigidas,
  comprobar selección/identidad raíz-base y dueño exacto en grupos de hasta 16
  registros o al vencer 250 ms entre registros; sin capacidades, conservar full
  por objeto. La revisión es de solo lectura y todo resultado sigue provisional:
  cualquier cambio detectado impide devolver el resumen/adoptar. No aplicar esta
  optimización a preparación durable, recuperación ni escritura ordinaria.
  Prueba focal `foreign-binding-group-tests`: 1/0 fallos, paridad de fuentes,
  Gradle offline 4m9s; comprueba agrupación, fallback, cambio de dueño rechazado,
  cuerpos preservados y cancelación. Revisión independiente de fuentes reales
  por snapshot HTTP, sin hallazgos accionables ni repetición de pruebas.
  C163 quedó pausada a las 19:36:33 sin adopción/payload; dos intentos de obtener
  XML no enviaron clicks. Cancelación local mediante navegación UHID y el
  BackHandler de la propia pantalla, sin matar procesos ni cambiar seguridad.
  Medir de nuevo en USB: ni el perfil ni el test prueban todavía <=900 s.

- Requisito de rendimiento solicitado explícitamente por el usuario el 29-10:
  «nuestro objetivo ahí debería ser similar al tiempo de preparación unos
  15 minutos máximo». Criterio para la revisión previa de adopción: como máximo
  900 s desde la acción explícita hasta el resumen confirmable de reutilización,
  delta y espacio; no incluye instalar la APK ni descargar el payload restante.
  Medir por separado metadatos, autenticación y planificación, sin esconder esas
  fases fuera del tiempo total. La fixture física actual conserva 51625635891
  bytes CASC; no extrapolar su resultado a cualquier capacidad o velocidad USB.
  C163 no cumple: a las 19:19:59 mostraba solo 5881 objetos/411.5 MiB
  autenticados desde una revisión iniciada antes de las 18:50:49. No adoptar
  recuentos parciales ni eliminar EKey/BLTE/CKey o guardas para alcanzar la cota.
  Registrar esta exigencia en la especificación y medir el cuello dominante
  antes de aceptar otra candidata; no reiniciar la revisión ya enviada.

- C163 instalada el 29-10 a las 18:45:28: sustitución confirmada, mismo UID,
  cero updater antes/después y marcador+81 nombres/tamaños CASC idénticos
  (51625635891 bytes), sin autoarranque del instalador. Después se abrió la app
  una sola vez; la primera navegación no envió clicks por el espacio inicial
  del texto « Ajustes». Una lectura confirmó ese texto y la navegación restante
  se completó sin relanzar. Se inicia una nueva revisión explícita, nunca adopción
  ni payload; el observador conserva heartbeat atómico y registra XML temporalmente
  no disponible en vez de morir silenciosamente al primer fallo. El contador
  identifica objetos/bytes autenticados, no velocidad de red ni progreso adoptado.

- C163 canónica cerrada, 29-10 18:42:43: build offline 5m20s y auditoría exacta
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2231 fuentes coincidentes con el recibo,
  178 payloads. APK 485363483 bytes, SHA-256
  `af56d423c6cf9c06ae823dc0987a988f7477199be99e16202de8202688dcadf6`.
  Fuente congelada `7f14bc1`; el commit posterior `9f0fb67` solo aclara comentario
  y documentación, sin cambiar código ejecutable. Se prepara sustitución normal
  `-r --no-streaming`, ya autorizada, exigiendo C162 previa/UID/idle real sin
  Pausar, cero updater/guest, hash APK y marcador+81 tamaños USB antes/después.
  Sin reinstalación destructiva, autoarranque, adopción ni payload. Una vez
  verificada la sustitución se retomará explícitamente la revisión con contador.

- Corrección de la prueba real, 29-10 17:19: el observador se había detenido
  tras un fallo al extraer XML a las 15:42, pero la app seguía autenticando.
  No debía presentarse el observador como activo sin verificarlo. Se pulsó Pausar
  una sola vez y se observó el mensaje de cancelación, sin adopción/payload.
  Inspección del código revela validación completa de destino en cada registro
  del escáner extranjero; es un coste repetido candidato, no una atribución
  medida de toda la demora. Corrección focal aplicada: distinguir fronteras full
  de comprobaciones light por registro únicamente bajo las capacidades vigentes,
  manteniendo fallback full, autenticación y binding por objeto. Se añade regresión
  de cadencia antes de desplegar y contador de objetos/bytes
  autenticados con actualización acotada (máximo dos por segundo), no bytes de red.
  RED focal reproducido (1 fallo esperado). La primera corrección pasó el caso de
  corrupción/cancelación, pero falló el límite de llamadas full: el helper de
  clasificación de metadatos aún invocaba full al terminar cada registro. Se
  corrigió para recibir la misma capacidad light admitida del llamador, con
  default full sin cambios; se repite solo el test afectado, no el ya pasado.
  El siguiente fallo era una aserción incorrecta: comparaba coste full fijo de
  entrada/salida/lease/review con el tamaño de un prefijo forzado por presión.
  Se corrigió solo el test a una cota por segmentos (16 + 4 por segmento), sin
  cambiar más producción. GREEN real: `foreign-cadence-bound-tests/summary.json`,
  1 test/0 fallos, paridad de seis fuentes, Gradle 4m7s; conserva pruebas de
  autenticación, bytes inmutables, fallback full y ausencia de estado heredado.
  La UI compiló pero no está instalada. C163 está etiquetada (3 Python PASS);
  La revisión independiente accedió al diff y fuentes exactas por snapshot HTTP
  local: señaló un comentario inexacto, corregido sin cambio ejecutable. El check
  light valida selección/root/base; la identidad de montaje se refresca en los
  límites full, no por objeto. Bajo ese contrato no quedaron bloqueos runtime
  identificados; no es prueba física ni exclusión de escritores maliciosos.
  La compilación C163 iniciada una sola vez usa `7f14bc1`, con el comentario
  anterior y código ejecutable idéntico al corregido. Pendiente auditoría exacta
  antes de instalación preservadora y repetir explícitamente la revisión real.

- Prueba real C162, 29-10: sustitución preservadora completada a las 14:28:44,
  mismo UID, cero servicios updater y `.owner`/diccionario de nombres-tamaños de
  los 81 CASC (51625635891 bytes) idénticos antes/después. No es un hash completo
  de cuerpos ni prueba de adopción. Tras abrir la app una sola vez, se corrigió
  únicamente la captura UI del controlador y su comprobación de foreground;
  los intentos previos sin acciones se conservan, sin reinstalar.
  La selección normal de Unidad USB se confirmó una vez, con metadatos fuente
  conservados y sin adopción. Una desconexión ADB interrumpió la navegación
  posterior antes de cualquier acción; mDNS permitió reconectar el original.
  Después se abrió Cliente y actualizaciones una sola vez. La inspección local
  terminó mostrando Destino: Unidad USB y el botón explícito de portabilidad.
  Se ha enviado una sola acción «Revisar descarga de otro móvil»: observador
  independiente del servidor registra el avance, se detiene en el resumen o
  error y nunca pulsa adopción/payload. Última observación incorporada:
  «Consultando versión para revisar la descarga existente…»; resultado todavía
  no incorporado. Evidencias en `usb-portability-c162-20261029/`, especialmente
  `original-install/`, `usb-selection/`, `client-management-after-reconnect/`
  y `partial-review/`. No repetir esos controladores por reinicios.

- 29-10, candidata C162: compilación offline JDK17 Modern completada en 5m15s;
  auditoría sin relajar `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2231 fuentes
  coincidentes con el recibo y 178 payloads. APK de 485359339 bytes, SHA-256
  `6c7edcef8e082a6958f578816cdc6f5741648ec244fee50e3086781f220d49b3`.
  Revisor independiente leyó el diff integrado completo y código relevante vía
  snapshot local de fuentes reales, sin hallazgos accionables; no ejecutó tests
  ni validó dispositivo/cobertura completa. Servidores de revisión ya cerrados.
  Preflight fresco reconectó el original por anuncio mDNS único: modelo correcto,
  cero servicios updater; APK instalada **162/0.2.138-wowup-addons-test**, no la
  C160 histórica. Se prepara una única sustitución `install -r --no-streaming`
  por C162 de portabilidad, sin desinstalar/borrar/abrir la app; se exige mismo
  UID y metadatos del staging USB idénticos antes/después. No autoriza adopción
  ni payload sin presentar y confirmar el plan real. Evidencia externa en
  `usb-portability-c162-20261029/` y `c162-device-preflight/`; build no repetido.

- Integración en validación (2026-10-29): la interfaz ofrece revisión explícita,
  reutilización/delta/espacio, confirmación de adopción y recuperación interrumpida;
  el payload conserva su confirmación ordinaria separada. Backend mantiene una
  única exclusión real desde la autenticación fresca del checkpoint nuevo hasta
  la transición C/P/S y la publicación oficial; no restaura el checkpoint antiguo.
  Prueba integrada sintética de adopción, corte tras rename P→C, recuperación y
  ejecución ordinaria hasta `.build.info`: PASS, 1 test/0 fallos/errores/skips,
  `integrated-adoption-clean-service-tests/summary.json`, 10:32:54 UTC. La prueba
  de 12 cortes del primitivo pasó en la ejecución aislada anterior; no se repitió.
  La primera aserción integrada confundía missingObjects del origen publicado
  con crédito del checkpoint: se corrigió solo el test, manteniendo el planner.
  Interrupciones y fallos HOST Gradle/KSP anteriores no cuentan como JUnit;
  se conservaron sus logs y se ejecutó con outputs limpios bajo servicio de usuario.
  Fuentes de producción/UI compiladas; NO prueba real, APK nueva ni adopción USB.
  Revisión independiente del diff solicitada, pero la herramienta falló por
  variante no disponible y NO emitió revisión. Sigue pendiente, igual que
  auditoría de candidata y validación física de ambos casos.

- 2026-10-29: la verificación explícita devuelve además un resumen provisional
  de descarga y espacio privado/destino/pico, ligado por digest al catálogo,
  volumen/ruta, marcador, inventario autenticado y snapshot loose/config.
  Incluye segmentos retenidos en el límite transaccional, checkpoint nuevo completo
  y manifest privado; no acredita como libre el staging existente ni copia el
  checkpoint del móvil anterior. Son cotas conservadoras, no un plan ejecutable
  ni consentimiento. Service usa los configs de la candidata sin activarla.
  La prueba sintética existente de verificación/reutilización pasa con las nuevas
  aserciones: `portability-review-tests/summary.json`, 1 test/0 fallos, paridad
  de fuentes, Gradle offline 4m8s. Se recuperó el resultado original tras reinicio,
  sin repetir Gradle. Revisión limitada al snapshot suministrado, sin acceso del
  revisor al repositorio; no satisface la revisión independiente integral.
  No nuevos escaneos de cuerpos ni llamadas desde Home/Resume ordinario; no USB,
  APK ni adopción real. Pendientes consentimiento/UI, preparación/publicación
  integrada y recuperación, auditoría de candidata y validación física completa.

- Planificación integrada tras autenticación: `inspectForeignTarget` y el
  verificador explícito de Updater devuelven reutilizados, faltantes, bytes de
  delta y cotas CASC de segmentos/índices. Los cuerpos se verifican una sola vez;
  la segunda pasada es sobre el catálogo, con bitset acotado y tabla privada en
  disco. Reutiliza el packing existente, comprueba ordinales y no llama al loader
  mutante ni crea checkpoint/propiedad. No se añade a Home o Resume ordinario.
  La estimación conservadora empieza un segmento nuevo después de los retenidos.
  Dos tests focales pasan (JUnit 29-10, ejecución finalizada 06:42:57 UTC): delta
  exacto y rechazo de ordinal cambiado, más integración con el catálogo completo
  sintético sin estado privado anterior. Evidencia externa:
  `usb-portability-local-20261028/foreign-target-plan-retry-tests/summary.json`.
  El primer intento falló al compilar dos líneas de tests, antes de JUnit; se
  corrigieron solo import y llamada `summary()`, sin cambiar producción.
  Revisión limitada al resumen suministrado, sin acceso independiente al código;
  no constituye revisión completa. Faltan espacio privado/loose/config, plan y
  consentimiento UI, orquestación de adopción y prueba física; no hay nuevo APK
  ni escrituras en el USB real ni aceptación de la portabilidad completa.
- Integración local del catálogo candidato con verificación explícita: Service
  `verifyPortabilityCandidate` abre el catálogo oficial todavía no activado,
  comprueba destino y recibo activo anterior y llama al verificador bajo el lease
  real. No importa el catálogo APK como fallback ni cambia el puntero activo.
  El resultado/callbacks siguen siendo provisionales, no autoridad para adoptar.
  Una prueba focal pasó el 29-10 a las 06:24:44 (1/0 fallos/errores/omitidos,
  paridad de fuentes): cancelación antes del almacenamiento no resuelve metadatos,
  escanea ni activa el candidato. Resultado original recuperado tras reinicio,
  sin relanzar Gradle; evidencia externa `candidate-verification-tests/summary.json`.
  Revisión del snapshot suministrado sin hallazgos accionables, sin acceso al
  repositorio ni comandos. Verificación exitosa integrada, plan/consentimiento,
  adopción/UI y prueba física siguen pendientes; no hay APK nueva ni cambios USB.

- Reset obsoleto tras publicación: reproducido que un recibo privado de fuente
  vacía con `resetCasc` podía recrear su staging después de aparecer `Data/data`.
  Se rechaza ahora antes de crear staging o resetear checkpoint, mediante checks
  de ruta no-follow/existencia, solo en esa ruta de rollback; no se añade un
  escaneo a Home o Resume ordinario. RED real: 1 fallo esperado; GREEN: 2 casos
  sin fallos/errores/skips (2026-10-28T22:25:29), incluida recuperación propia antes
  de publicación que todavía completa el cliente sintético. Evidencia externa:
  `usb-portability-local-20261028/stale-reset-{red-retry,green}-tests/`.
  Un intento previo sufrió SIGSEGV del compilador C1 de la JVM antes de JUnit:
  no cuenta como prueba del producto; la repetición aislada excluye únicamente
  ese método del JIT de Gradle en el host, sin cambiar el runtime Android.
  Revisión limitada al snapshot suministrado, no a repositorio/USB/binarios antiguos;
  no se declara resuelto todo TC-013 ni el caso de fuente previa no vacía.

- Rendimiento preservado por petición explícita: no añadir escaneos completos al abrir,
  seleccionar almacenamiento o a cada Resume ordinario. La autenticación adicional se
  limita a la adopción explícita de datos ajenos. La integración debe evitar recorridos
  redundantes y reutilizar pruebas solo bajo exclusión y bindings todavía válidos;
  conservar las optimizaciones anteriores, la cadencia full/light y las barreras.
  Medir preparación/verificación/primer payload antes de aceptar el inicio real.
- Publicador oficial ligado al intent implementado localmente:
  `publishPortabilityCatalogReceipt` valida el SHA nuevo y el recibo anterior/nuevo
  esperado, aplica el parser ordinario y repite publicación/barreras incluso si el
  nuevo recibo ya es visible. Un caso focal pasó; la revisión de snapshot detectó
  un buffer mutable y se corrigió copiándolo antes de validar/publicar. Solo ese
  caso se repitió sobre el código cambiado: 1 test, 0 fallos/errores/skips, JUnit
  2026-10-28T22:04:14, paridad de fuentes. Evidencia externa:
  `usb-portability-local-20261028/portability-catalog-publication-snapshot-tests/`.
  La revisión confirmó la reparación solo sobre el snapshot suministrado, sin
  acceso independiente al repositorio ni comandos. Sigue pendiente conectar el
  publicador al caller con lease/intent/consentimiento y refrescar el catálogo en
  memoria; no demuestra adopción física ni ejecución de todo TC-012/013.

- Primitiva local de transición/recuperación C/P/S añadida bajo la lease real:
  valida el candidato privado, publica intent antes de cambiar propiedad, reserva
  solo el workspace previo ligado a su manifiesto, cambia la generación sin mover
  los cuerpos CASC y publica el candidato canónico. La entrada ordinaria sigue
  bloqueada por intent; esta primitiva no es una entrada de consentimiento/adopción.
  Recuperación vuelve a forzar los padres de renames ya visibles; P y su padre se
  fuerzan antes del intent. El publicador oficial deberá ser idempotente y restablecer
  su durabilidad incluso cuando el digest nuevo ya sea visible: integración pendiente.
  Antes de borrar cualquier reserva privada verifica también cada SHA, no solo
  tamaño/mtime/filekey; máximo 4096 entradas, manifiesto 2 MiB y reserva 2 GiB.
  Revisión de flujo suministrado detectó y corrigió esa omisión; revisión posterior
  sin hallazgo adicional, condicional al modelo privado/single-writer, sin acceso
  independiente al repositorio. Primer intento falló compilando el fixture, sin
  JUnit ejecutado. Después: dos checks PASS (JUnit 28-10 21:39:46); tras añadir la
  barrera del padre de P se ejecutó solo el check afectado, PASS (21:46:48), archivo
  de tests idéntico. Cubre doce escenarios sintéticos, cortes antes/después de
  renames, reserva parcialmente retirada y corrupción de igual tamaño/mtime que
  debe conservar todos los archivos restantes. Fuentes finales updater
  `a2ea854b…a6df6` / test `986e55a5…24dc8`; evidencia externa
  `usb-portability-local-20261028/portability-transition-parent-barrier-tests/`.
  Sin USB, APK ni dispositivo. Publicador real, preparación/plan ligado,
  consentimiento/UI, cobertura global obsoleta y prueba física siguen pendientes.
- Preparación explícita de catálogo candidato añadida al servicio: conserva la
  guarda ordinaria de catálogo activo ante staging extranjero, resuelve únicamente
  metadatos oficiales consentidos y devuelve sus bindings sin publicar la admisión.
  Dos checks focales de consentimiento pasan (JUnit 28-10 20:53:39, Gradle 4 min 4 s),
  con fuentes idénticas a las probadas. Esto no verifica todavía la resolución
  completa ni su publicación ligada al intent; revisión sensible e integración
  con la adopción siguen pendientes. Sin dispositivo, USB ni nueva APK.
- Preparación de checkpoint extranjero implementada localmente: escaneo completo
  autenticado produce frames nuevos en un candidato privado fresco, sin copiar el
  checkpoint anterior. Fuerza los segmentos antes del checkpoint/owner; no cambia
  ruta, cuerpos CASC ni propiedad USB y no publica el workspace canónico. El caller
  todavía debe mantener la lease real, plan consentido y transición C/P/S.
  Tres checks focales PASS (JUnit 28-10 20:40:23): preparación seguida de Resume
  sintético sin fetch ni cambio de segmentos, cancelación que retira solo el
  candidato privado, y barrera durable de 16 objetos. Fuentes `6a7b27ed…b3be1e` /
  test `8b232008…14e32` coinciden con las copias probadas; no se repitió Gradle tras
  perder sus notificaciones. Evidencia externa: `usb-portability-local-20261028/
  foreign-checkpoint-tests/`. Revisión del código suministrado sin hallazgo accionable,
  condicional al contrato; sin inspección independiente del repositorio ni prueba
  de adopción física. Integración global, cortes de transición y auditoría pendientes.
- Integración local de verificación parcial: lease real, catálogo admitido y orden
  físico enlazan el scanner con el destino/marcador revalidados. Resultado y callbacks
  siguen provisionales; no se crea checkpoint/recibo ni se adopta el USB. El primer
  test encontró que la inspección de bootstrap ordinaria rechazaba el parcial;
  se reutiliza solo la preparación acotada del índice, conservando esa guarda normal.
  GREEN posterior: dos checks, cero fallos/errores/skips, JUnit 28-10 20:28:26,
  fuentes updater `ea57c3d8…d1591` / test `de7752ce…c538`, paridad comprobada.
  Evidencia externa `usb-portability-local-20261028/partial-integration-green-tests`.
  Revisión independiente solicitada pero inconclusa por falta de acceso del revisor;
  sigue pendiente antes de desplegar. C/P/S, consentimiento/UI y prueba física no
  quedan implementados por esta integración. No se repiten los tests cerrados.
- Codec local de intent privado implementado: metadatos acotados a 16 KiB,
  checksum y representación canónica ligados a target/destino/storage, catálogo,
  plan, inventario, checkpoint y generaciones anterior/nueva. Solo produce/valida
  bytes: no publica intent, sustituye workspaces ni adopta el USB.
  Un check focal pasó (JUnit 28-10 20:13:06, cero fallos/errores/skips), fuentes
  `de770353…11a00` / `ee007331…1c60f`, copias de test iguales al repositorio.
  Notificaciones perdidas recuperadas del proceso original, sin repetir Gradle.
  Revisión sensible inconclusa por falta de acceso del revisor al diff, no aprobada;
  faltan publicación/recuperación C/P/S, checkpoint candidato, integración de
  catálogo, UI y pruebas físicas. No desplegar por este check aislado.

- Kernel local `ForeverCasc.inspectForeignStage` implementado: autenticación por
  objeto/EOF, catálogo suministrado, identidad de segmentos y digest de inventario;
  callbacks y resumen siempre provisionales, nunca autoridad para adoptar. Exige
  lease real y revalidación del llamador; no usa el loader que limpia/trunca colas.
  Resultado original recuperado sin rerun: tres checks, cero fallos. Ampliación
  posterior con N encoded >1 MiB: solo los dos targets afectados, cero fallos;
  fuente `e0630ccc…d2743`, tests `f38f996f…6b29`. Evidencia externa en
  `usb-portability-local-20261028/large-encoded-scanner-20261028/`.
  Revisión independiente de código todavía pendiente: el revisor no tuvo acceso
  a archivos. No es cobertura completa de TC-009/010 ni adopción sobre el USB.
- Descubrimiento ligero local añadido, aún sin UI ni autenticación: distingue vacío,
  publicación candidata, parcial candidato y disposición ambigua con lecturas de
  metadatos acotadas, bajo lease real. Un candidato no declara objetos verificados
  ni autoriza adopción. Mantiene la barrera privada de intención pendiente.
  El primer resultado ejecutado detectó que la guarda ordinaria de staging pendiente
  impedía también inspeccionarlo; se permite esa inspección solo en esta entrada
  de lectura, sin cambiar las entradas mutantes. GREEN posterior: un test focal,
  cero fallos/errores/skips, JUnit 28-10 19:32:14, Gradle 3 min 27 s, fuentes idénticas.
  Evidencia externa `usb-portability-local-20261028/discovery-green-tests/summary.json`;
  fallo previo preservado, sin repetir checks verdes ni tocar el USB real.
  Revisión sensible solicitada pero no obtenida por variante no disponible; no
  se considera suplida por estas pruebas ni por inspección del padre. Integración,
  verificación de cuerpos, recuperación durable y auditoría APK siguen pendientes.
- Guarda privada de intención pendiente implementada localmente: su presencia,
  incluso con bytes malformados, bloquea plan, execute, recover y Resume ordinarios;
  la lease conserva la operación pendiente. Un test focal pasa (JUnit 19:10:01 UTC),
  sin modificar datos del destino ni retirar la intención. Es solo la barrera de
  entrada: parser, publicación durable C/P/S y recuperación explícita siguen pendientes,
  al igual que la revisión sensible de esta integración. Sin acción en el USB real.
- Integración local del recibo generacional: tres controles del updater reutilizan
  el lector canónico acotado de Transaction y su hash privado, sin admitir un stage
  extranjero ni un checkpoint nuevo. Tres tests focales pasan (JUnit 18:21:51 UTC),
  incluido rechazo del hash antiguo y del workspace extranjero; fuentes exactas
  verificadas. Notificación perdida recuperada sin repetir Gradle. Revisión sensible
  solicitada pero no obtenida por fallo de disponibilidad del modelo; sigue pendiente
  para esta integración, sin atribuirle la revisión anterior del marcador.
- Prerrequisito local de generaciones: creación v1 preservada, marcador v2 ligado
  a stage/identidad/UUID y hash privado exacto, lectura acotada y longitud autenticada
  en publicación. Cinco tests focales/legacy pasan; revisión sensible de snapshot
  sin hallazgos, sin acceso del revisor al filesystem ni comandos. No acredita
  todavía intención C/P/S, cortes v2 específicos ni bloqueo obsoleto tras publicación.
  Sin nueva APK, adopción o escritura en el USB real. Verificador CASC en desarrollo
  separado; ningún recuento del USB ha sido autenticado por esta implementación.
- TC-007–013 aprobados el 28-10. El usuario también autoriza «crear apks, instalarias
  y probarlas en el dispositivo conectado por el adb wifi». No repetir preguntas de
  permiso por esa clase de acción; conservar auditoría de candidata exacta, preflight
  y datos. Esta autorización no elimina el plan y consentimiento reales antes de
  adoptar el parcial o descargar su delta, ni autoriza borrados o cambios de seguridad.
- Nuevo escenario solicitado el 28-10: el usuario borró los datos de la app y devolvió
  el USB al móvil original. Lectura 17:22:09 UTC: la app informa C160, sin workspace
  privado previo, admisión dinámica ni preferencias de almacenamiento en las rutas
  comprobadas. USB esperado montado, `.owner` igual al histórico y mismos 81 nombres
  y tamaños CASC, 51.625.635.891 bytes. Sin lanzar app, UI, descarga o adopción;
  cero servicios updater antes/después. No hay nuevo recuento de objetos autenticados.
  Esta fixture prueba ausencia de estado local; no sustituye la validación entre
  teléfonos distintos ni aprueba los TC pendientes. No restaurar el checkpoint antiguo.
- Actualización C160 completada y verificada en versión/UID/metadatos. La app se
  abrió después por petición expresa, sin descargar/reparar/adoptar el parcial ni
  migrar el checkpoint. La inspección de archivos del cliente sigue siendo de
  solo lectura.
- El usuario confirma ambos casos. Resolver la selección primero no autoriza
  todavía a mutar/adoptar el staging con un protocolo sin especificar/aprobar.
- El desajuste de selección está resuelto. Falta adopción/reutilización del parcial;
  el estado privado del original no es transferible por copiarlo o saltarse guardas.
- UC/AC, FR-009–013/NFR-004, diseño y TC-007–013 aprobados explícitamente:
  cliente completo, revisión sin efectos, verificación acotada,
  preservación ante errores/interrupción, consentimiento, cortes durables y rechazo
  obsoleto antes/después de publicación. Su implementación y cobertura ejecutada
  siguen pendientes; la aprobación no acredita código ni recuperación física.
- Mantener la ruta es relevante: las cabeceras de segmento CASC incorporan su ruta
  absoluta. El diseño propone una nueva generación del marcador, enlazada por el
  `markerHash` del recibo, para invalidar estado obsoleto sin renombrar los segmentos.
  No se ejecuta esa transición ni se confía en el marcador como prueba de objetos.
- El diseño aprobado concreta exclusión en host único, intent antes de cambiar
  propiedad, reserva del workspace antiguo y recuperación por bindings exactos.
  La representabilidad sin truncar datos extranjeros y el bloqueo de estado obsoleto
  en todas las rutas mutantes requieren pruebas; no se dan por demostrados por la
  revisión preliminar de un snapshot, sin acceso a archivos ni ejecución.
- No conceder permiso amplio como atajo: contradice los requisitos vigentes. Si un
  cambio de contrato resultase imprescindible, requiere otra decisión explícita.
- US-007 cubre selección sin migración y US-009 revisión/reutilización de una fuente
  CASC legible con metadatos de instalación. US-008 protege el staging y checkpoint;
  no se ampliarán silenciosamente para confiar en estado privado de otro teléfono.
- Primer intento de conexión 19:24 UTC no conectó; no se ejecutaron consultas
  sobre la app, acciones UI, instalaciones o escrituras de cliente en el personal.
- El traslado fue confirmado; no hay confirmación expresa de expulsión del sistema.
- No se acredita migración del parcial, instalación completa ni arranque del juego.
