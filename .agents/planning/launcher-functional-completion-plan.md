---
id: launcher-functional-completion-2026-10-17
state: in-progress
date: 2026-10-17
---

## Summary

**Corte del descargador (2026-10-19):** el usuario aparca esta línea de mejoras,
sin ordenar detener la transferencia. C112 está instalada/auditada,70450ARM64enUS
enUSB6AD6-EF0E,609.363objetos/48.294.811.059bytes preservados al update. El punto
de continuación es [docs/downloader-handoff.md](../../docs/downloader-handoff.md).
No cerrar el ítem5:faltan descarga completa,validación/publicación,instalación
gestionada yarranque;no relanzar drivers al retomar sin observar primero.

Petición: «haz un plan con todo lo que acabamos de hablar que falta. y no pares
hasta implementarlo». Objetivo: entregar funciones utilizables en el móvil, no
pantallas provisionales ni otro ciclo de explicaciones sobre bloqueos artificiales.
Este plan consolida la ejecución pendiente de UX, cuenta/autologin, descargador,
actualizador y almacenamiento. Los planes anteriores quedan como evidencia
técnica; este será el checklist de cierre de esta entrega.

Plan aprobado con la orden posterior de implementar, corregido para que el flujo
normal consulte publicaciones dinámicas y no se centre en70425. Continuar
sin pedir aprobación por cada paso técnico reversible. No declarar completado un
bloque hasta que el APK instalado lo haga. No prometer tiempos o ejecución
ininterrumpida fuera de las capacidades de la sesión.

## Content

### Estado de partida, sin equiparar piezas con producto terminado

- Instalado: code88, `0.2.64-functional-client-manager`. Compilado sin ejecutar
  tests por petición del usuario, auditado e instalado por Wi-Fi sin borrar datos.
- El veto booleano de aceptación nativa pendiente ya se eliminó; no reintroducirlo
  ni sustituirlo por otro indicador manual que impida progresar indefinidamente.
- USB6AD6-EF0E vfat,114,5GiB libres, selección/preflight interno→USB→interno
  comprobados. La última inspección encontró USB seleccionado: preservar la elección
  actual del usuario, no restaurarla al interno automáticamente.
- Host inactivo, filesystem interno y transferencia CDN pequeña acreditados en87.
  La cualificación específica del USB y una instalación/arranque allí faltan.
- Parsers TACT Kotlin y catálogo dinámico coinciden con el oráculo host70425 en
  pruebas. El recorrido completo de preparación/descarga Android aún no está cerrado.
- Cuenta: almacén/cifrado existen, pero la pantalla instalada mantiene Guardar
  deshabilitado. El servicio borra al guardar con autologin apagado. No es un
  guardado de cuenta utilizable. La retirada de Cuenta fue solo un cambio en fuentes;
  no es la solución final solicitada. No hay detector/sender de autologin operativo.
- Mensajes simplificados/refresco tras comprobar unidad están en fuentes y no
  instalados. El usuario sigue viendo textos internos y estados poco accionables.
- Cliente original70009 y WTF original intactos.70425 confirmada por el usuario
  para Forever; Blizzard publica70450, todavía no confirmada para el servidor.

### Entrega y criterios de aceptación

| Bloque | Resultado obligatorio visible/usable |
|---|---|
| Inicio y navegación | Versión instalada, ubicación y una acción principal según estado: Descargar, Actualizar, Reanudar o Jugar. Pantallas secundarias breves, Volver consistente y sin diálogos apilados. |
| Mensajes | Eliminar «build fija del catálogo», «el servicio no ha autorizado» y equivalentes. Mostrar el motivo y una acción directa; nada de apartados de funciones no utilizables. Detalles técnicos fuera del recorrido normal. |
| Cuenta | Guardar usuario/contraseña funciona con autologin apagado. Memoria por defecto y Recordar cifrado opt-in. Confirmación inequívoca de guardado; al reabrir se ve el usuario y el estado, no una contraseña revelada. Olvidar borra por acción explícita. |
| Autologin | Opción real que introduce la cuenta guardada y envía una sola vez en el formulario propio confirmado. Interacción manual/foco equivocado/error cancelan, sin reintentos ciegos. Guardar y activar autologin son decisiones distintas. |
| Descargador | Desde la APK, sin PC, preparar metadatos, mostrar tamaño/espacio/destino y descargar en la ubicación elegida. Progreso útil y pausa/reanudación real tras cerrar/reabrir. |
| Actualizador | La misma compilación admite otra build soportada sin generar APK/assets por versión. Reutiliza objetos existentes verificados; no reconstruye ni copia todo Data como estrategia normal. |
| Versiones | Consultar la publicación oficial y compararla con la instalada, sin recomendar permanentemente una build incrustada. Las versiones conocidas anteriores son secundarias y no sustituyen la búsqueda de novedades. |
| Consulta normal de actualizaciones | La acción consulta las versiones publicadas de forma dinámica desde la APK. No toma70425 ni ninguna build incrustada como recomendación permanente. Comprueba arquitectura, prepara metadatos y compara con la instalada; compatibilidad Forever se muestra aparte, sin vetar la consulta/preparación. |
| Integridad y recuperación | Descarga incompleta o activación interrumpida se recupera. Mantener hashes, identidad del medio y exclusión con Wine abierto; no convertirlos en botones/avisos técnicos omnipresentes. |
| USB | Capacidad/acceso desde la app, destino vacío, persistencia de selección, pausa/desconexión/remontaje y arranque comprobados físicamente cuando haya espacio y archivos suficientes. |
| Lanzamiento | La build instalada/gestionada se reconoce, configura y arranca; no sigue figurando70009 por una constante tras instalar70425. Launcher vertical/juego horizontal sin alterar runtime o drivers. |

### Cambios concretos y secuencia

1. **Congelar alcance y pantalla mínima.** Quitar del flujo habitual informes,
   casillas de ingeniería y botones de recuperación/verificación sin necesidad
   actual. Esas operaciones se ejecutan en el flujo o en un apartado secundario
   solo cuando aportan una acción útil. Diferenciar aviso, error y trabajo en curso.
2. **Cuenta antes de más retoques visuales.** Separar guardar credenciales del
   Boolean de autologin en `ForeverFunctionalAutologinSettings.kt`; desmarcar
   autologin no debe borrar una cuenta guardada. Mantener memoria/cifrado existentes,
   edición segura, usuario recuperable y contraseña solo de escritura/estado
   enmascarado. No inventar asteriscos como contraseña ni sobrescribir un secreto
   guardado por un campo vacío. Eliminar el acoplamiento a `runtimeReady` de Guardar.
3. **Autologin conectado, no solo controlador puro.** Integrar detección actual de
   formulario/campo, foco e input en XServer con el controlador existente. Ajustar
   geometría al viewport real, no a coordenadas históricas. No root, ADB o scripts
   externos como requisito del usuario. Completar el envío y comprobarlo con una
   cuenta de pruebas autorizada; no afirmar autenticación exitosa por desaparecer
   el formulario. Si el servidor rechaza el login, distinguir envío correcto de
   fallo remoto; no reabrir búsquedas de claves o bypass TLS como trabajo adyacente.
4. **Consulta de actualizaciones y metadatos.** El flujo predeterminado consulta
   NGDP desde la APK y usa la versión publicada con sus hashes reales; no selecciona
   una build fija como recomendación permanente. Comprobar ARM64 y generar catálogo
   local sin exigir una casilla que afirme compatibilidad del servidor para poder
   consultar/preparar metadatos. Mostrar compatibilidad Forever como dato separado,
   y pedir consentimiento de actualización cuando corresponda, sin inventarla.
   Builds anteriores conocidas pueden ser una elección secundaria, nunca el centro
   del actualizador. Generar/admitir catálogo local y recibo duradero en Android;
   preservar caché de metadatos verificados tras errores. Scripts host solo oráculo
   y diagnóstico, no paso obligatorio de futuras actualizaciones.
5. **Descarga y aplicación.** Integrar plan, confirmación de coste, descarga,
   checkpoints, publicación y recuperación con la acción principal. El chequeo de
   almacenamiento se hace como preflight del flujo, con su motivo concreto si falla,
   no como permiso manual opaco. Registrar una instalación gestionada para que futuras
   versiones reconozcan su origen sin listas Kotlin por build. Habilitar preparación
   y arranque por fingerprint admitido, sin relajar a «cualquier PE ARM64».
6. **Prueba física interna/USB y arranque.** Antes de transferencias grandes obtener
   el coste real y conservar el consentimiento. Usar destino aislado para no pisar
   la instalación funcional. Validar lectura/escritura real, reanudación y cierre;
   si el conjunto no cabe, informar bytes/espacio y opciones, nunca formatear o borrar
   archivos ajenos. Probar arranque del resultado como verificación, no como otro veto
   manual de «pendiente» que se perpetúe en la UI.
7. **Entrega conjunta.** Una compilación/auditoría tras estabilizar cambios coherentes,
   instalación sin borrar datos y recorrido de las acciones reales. Capturas públicas
   de interfaz final, nunca del editor protegido o campos con secretos. Actualizar
   estado y checklist solo con evidencia del APK realmente instalado.

### Disciplina para no repetir los retrasos anteriores

- No una compilación completa por cada texto o cada hipótesis sin reproducción.
  Diagnosticar primero; agrupar correcciones y compilar una entrega coherente.
- El usuario ha pedido no seguir con rondas de tests: no ejecutar suites rutinarias
  ni matrices de cobertura. Priorizar compilación imprescindible, auditoría existente
  y comprobaciones manuales directas del comportamiento; si se requiere una prueba
  específica por un defecto de integridad, justificarla antes de repetir nada.
- Reutilizar componentes existentes; no nuevos frameworks, capas de configuración,
  proveedor remoto de catálogos ni cambios de runtime para resolver UX.
- Registro por bloque: fuentes cambiadas, APK instalado, acción comprobada y límite
  real pendiente. «Compila», «existe el almacén» y «hay interfaz» no equivalen a
  «guardar», «autologin» o «actualizar funciona».
- No retirar Cuenta como sustituto de terminarla; no mensajes de función futura
  donde se ofrece una acción actual. No volver a afirmar una entrega que no existe.

## Implementation / Action Items

- [x] 1. Aprobación única del plan escrito con consulta dinámica como flujo normal.
- [ ] 2. Guardado de cuenta desacoplado, restauración de usuario/estado y borrado
  explícito; diálogo seguro reabierto desde Ajustes y comprobado en el APK.
  - [x] Code90 instalado: guardado independiente del Boolean de autologin,
    campos/Guardar habilitados, usuario restaurado y contraseña no revelada.
  - [x] Prueba física de cuenta sintética Recordar cifrado→guardar→cerrar/reabrir→
    olvidar, todas correctas, sin captura/secretos en artefactos ni cuenta ajena.
  - [ ] Persistencia tras muerte real del proceso/reinicio físico pendiente;
    no se presenta reabrir un diálogo como haber probado esos ciclos adicionales.
- [ ] 3. Detector/foco/input/autologin integrados, intento único y fallback manual
  comprobados; no solo configuración/prefs.
- [ ] 4. Consulta dinámica de novedades, comprobación ARM64 y preparación de dos
  builds soportadas con la misma compilación, sin PC, versión fijada en el flujo
  normal ni casillas de compatibilidad como requisito para consultar metadatos.
  - [x] Preparación real70450 en el móvil code92 desde metadatos oficiales; catálogo
    generado y activo, sin producir otro APK/catalogo en el host para esa versión.
- [ ] 5. Descarga/reanudación/actualización/recuperación end-to-end, coste real y
  reconocimiento/arranque de la instalación gestionada.
  - [x] C112 instalada concheckpoint/receiptbyteidénticos;solape heredadoC111
    permite nuevoscommits antesdecompletarverificación,conbarrera depublicación.
    Preparación sinfuente ordenada reduce2scans225s→94/101s;inicio nuevo12min42s.
  - [ ] Completar/validar/activar70450yprobarreconocimiento/arranque. Throughput
    sostenido sigueabierto,redcompartida;C11210+2terminó0,29939/0,36717MiB/s,
    sinerror,conprefijo aúnpendienteenelinforme. No declarar instalaciónlista.
  - [x] Planner físico USB:85,0GiB de descarga y86,1GiB adicionales, confirmación
    habilitada,114,5GiB libres. No se pulsó Confirmar descarga; payloads pendientes.
  - [x] Flujo confirmado con aprobación («si hagamoslo»/«si, hazlo») y c93 en
    compilación: confirmación enviada real («Confirmar descarga»), fallo temprano
    documentado antes de payload (USB intacto) y reintento instrumentado con
    servicio en primer plano, notificación y Pausar (c93). Pausa screenshot y
    verificación física del fondo (app cerrada) quedan pendientes con c93
    instalado; no cerrar el ítem sin evidencia en APK instalada.
- [ ] 6. USB físico, persistencia de selección, desconexión y retorno controlado,
  sin copiar/borrar automáticamente el cliente interno.
- [ ] 7. Simplificación final de inicio/Ajustes/progreso/mensajes, sin botones muertos.
- [ ] 8. Build/auditoría/instalación conjunta y comprobación física, documentación
  reconciliada y capturas públicas de la entrega efectiva.

## Open Issues

## Continuación c98: corrección y validación (2026-10-18)

El usuario aprobó continuar las mejoras de descarga y, tras el resumen de límites,
respondió «adelante». Se ejecuta dentro del alcance ya aprobado; no se cambian
formato CASC/checkpoint, garantías de integridad, identidad del medio ni datos del
cliente original. No se difieren comprobaciones ni fsyncs como atajo de rendimiento.

- [x] Reconstrucción, compilación y auditoría de procedencia c98; instalada.
  Esto NO acredita corrección funcional de su prefetch.
- [x] Reconexión de diagnóstico: c98 muestra 517,6 MiB de 84,4 GiB descargados;
  USB 1.201.728 KiB usados. No aparece servicio activo en dumpsys de servicios.
  Evidencia: artefactos externos `work/c98-resume-review-20261018/`.
- [x] Medida corta de red desde Android: OVH10MiB/0,733s =114,51Mbps,
  con validación TLS normal y sin escribir el cuerpo. No es benchmark de Blizzard.
- [x] Objeto cifrado que bloqueaba c96: checkpoint real ordenado/CRC válido y
  extracción del objeto `0006690c41014581816e37b9b5f6846e` desde el stage USB;
  EKey y checksum de su frame `E` verificados independientemente.
- [x] Revisión independiente de concurrencia, cancelación, coste/espacio,
  propagación de errores, progreso y recuperación; corregir solo defectos acreditados.
- [x] Diagnosticar pausas de escaneo con evidencia; CPU global activa no demuestra
  avance del trabajo (puede ser render/UI). No afirmar que TLS domina sin medirlo.
  - [x] Lectura del algoritmo: carga máxima60% y crecimiento de tabla en disco
    a157.287/314.573/629.146 registros; el callback anterior queda justamente
    en147.456/311.296/622.592. Pista concreta, todavía no medida como coste dominante.
  - [x] Perfil aislado con catálogo real y código instalado: cursor7,34s,
    inspectTarget90,97s, pausas de rehash2,78/4,84/8,90/16,73s. Scratch eliminado.
    Chunk final1.130.496 normal(1,07s): no explica la pausa final de producción.
  - [x] Separar con marcas de fase en APK el coste de cargar checkpoint y verificar
    staged al reanudar. El primer intento aislado no accedió al USB y no acredita
    rendimiento de esa fase. No se relajarán comprobaciones para medirla.
    - [x] Vía shell sin root y con scratch propio:500objetos/35,1MiB reales del
      stage,500/500 válidos,2,945–9,219s; mediana17ms,p95=29ms segunda corrida.
      Extrapolación a~18kobjetos:2–5,5min; confirmar todavía el coste en proceso app.
  - [x] Medir después del cambio localizado `ForeverCasc.hex()` (Formatter por byte
    sustituido por conversión de nibbles equivalente; revisión independiente sin
    bloqueos y pin actualizado). Mantener los rehashes/validaciones actuales.
    Resultado aislado91→70,4s; no equiparar con app (scans198,827/200,291s).
  - Referencia del experimento: separar cursor,
    crecimiento de tabla y revalidación de objetos staged al reanudar. Solo scratch
    nuevo acotado; no modificar cliente, catálogo, checkpoint ni selección USB.
    El contador final1.130.496 NO coincide con un umbral de crecimiento y debe
    explicarse separadamente. El chequeo de checkpoint no consume necesariamente
    todo el catálogo: se detiene tras el último ekey guardado.
- [x] Verificación Kotlin puntual de concurrencia/orden, cancelación y reanudación,
  reintento HTTP y respeto de límites; evitar suites rutinarias. Estas pruebas se
  justifican por el riesgo de corrupción o workers que sobrevivan a la transacción.
  Resultado:24tests(Updater10+Transfer9+FGS5),0fallos/0skips; CASC3tests,0fallos/0skips.
  Cambio final EOS: eliminado deadline5s que podía perder el marcador ante un
  último commit lento. Revisión específica aprobada;2tests completion/cancel y
  compilación en reconstrucción nueva c99-r2 correctos(2tests/0fallos/0skips).
  - [x] c99-r2 auditada e instalada como99/0.2.75; candidato previo no instalado.
    Checkpoint/recibo de stage byte-idénticos antes/después. Reanudación física
    iniciada con FGS comprobado y pausa posterior correcta. No se alcanzó descarga
    sostenida: replay real~130s/1.024objetos;2.048verificados a264,489s. Se pausó
    antes de nuevos commits, checkpoint byte-idéntico y18.362objetos conservados.
    La proyección~40min no es tiempo total medido; sustituye estimación shell.
- [ ] Auditar y entregar la corrección con versión nueva si cambia lo instalado;
  comprobar FGS real, progreso, pausa y recuperación sin borrar datos.
- [ ] Medir tasa sostenida y separar red, almacenamiento y procesamiento; comparar
  con c97 sin prometer multiplicadores. Speedtest desde móvil aún no realizado.
  La prueba HTTPS corta OVH sí está realizada (evidencia arriba). c98 se pausó
  desde la UI a706,0MiB de sesión, conservando1.401.760KiB usados del USB; no hay
  workers `forever-fetch` tras esa pausa, pero persiste un `Thread-17` antiguo.
  Baseline validado antes de instalar:18.362objetos/1.430.491.739bytes encoded
  comprometidos;frameE original conservado. Artefacto `before-c99-baseline.json`.

### Checkpoint c100 (2026-10-18)

- C100 instalada y auditada; plan del temporal privado completado en
  `done/casc-private-verification-temp-plan.md`. Datos previos intactos tras update.
- Revalidación completa18.362objetos en179,659s;2.048 en21,461s frente264,489s
  c99. Descarga de nuevos objetos y continuidad en background120s con FGS probados.
- Nuevos commits aún lentos:0,580MiB/s foreground/0,316MiB/s background en ventanas
  cortas. No cerrar instalación/actualización end-to-end ni prometer2–3h.
- Usuario pide comparar el instalador `wowserver` (recuerda~7h); investigación
  solo lectura, sin tocar su cliente/producción ni relajar garantías del móvil.

### Checkpoint c101 (2026-10-18)

- Plan `done/download-throughput-optimization-plan.md` completado:8fetchworkers
  solo para primera instalación sin fuente,cola6/cota1GiB/recuperación10claves
  intactas,hashes yfsync por objeto intactos. Updates con fuente siguen seriales
  y reutilizan bloques verificados;6tests específicos sin skips yrevisión en verde.
- C101 instalada/auditada sin perder27.816objetos/2.194.565.341bytes previos.
  Replay completo291,249s,seguido de nuevos commits;objeto E original conservado.
- Foreground10min:0,64269MiB/s (~2,18x c100),4.841objetos;background2min:
  0,38704MiB/s,629objetos.8workers,FGS yRAM estable comprobados. No ETA de7h:
  a esas tasas proyección~36,5–60,6h restantes,no garantía. Descarga aún en curso.
- Resto de coste dominante medido como `sourceResolve` (~79% al inicio),pero
  mezcla revalidate+future:separarlos antes de decidir otra optimización.
  No cambiar checkpoints,host oconcurrency de updates sin otra decisión explícita.

### Checkpoint c102 ydiagnóstico posterior (2026-10-18)

- C102instalada/auditada:16workers,cola14,recuperación18,budget1GiB ygarantías
  intactos;7tests puntuales sin skips,revisión sin bloqueos. Plan completado en
  `done/sixteen-fetch-workers-plan.md`. Datos previos conservados tras instalación.
- Diezmin foreground0,40952MiB/s frente0,64269c101;dosmin fondo0,48067frente
  0,38704c101. RAMestable,FGSvivo,16workers,checkpoint/objetoE conservados.
  No mejora consistente(no A/Bidéntico);no más escalado por ahora.
- Evaluar siguiente cuello,como pidió el usuario:c103diagnóstica separa dos
  contadores locales(revalidate vsespera future) cada256fuentes;sin cambiar
  comportamiento,fsync ni seguridad del USB. Hasta entonces sourceResolve~86%
  no puede atribuirse solo aHTTP. No tests rutinarios:sólo compilación ydos casos
  de retorno de fuente/cancelación relevantes al callback instrumentado.
  C103 compilada,auditada einstalada;2tests/0fallos/0skips. Estado previo
  44.140objetos/3.536.891.435bytes conservado ycheckpoint/recibo byte-idénticos.
  Sin cambios de comportamiento,solo tiempos locales acumulados con resolución
  nanosegundo ylog cada256fuentes. Recogida de1.024resoluciones reales en curso.

### Checkpoint c104 ycontinuación aprobada (2026-10-18)

- Plan `done/storage-revalidation-single-snapshot-plan.md` completado:una captura
  fresca por objeto,mismas guardas.8tests específicos sin skips,revisión yAPK
  auditados. Replay52.789objetos ydescarga/fondo comprobados,sin pérdida de datos.
- Revalidate72,77→42,44ms/obj(~42%menos en esa fase). Descarga medida0,480MiB/s
  foreground10min/0,530MiB/s background2min,RAM estable,16workers/FGS.
- Usuario aprobó ambos siguientes pasos:quitar barrido numérico redundante para
  preparar el plan ymedir espera del objeto sin cambiar transporte ni integridad.
  C105 en curso con propiedad independiente Catalog/Updater yTransferdiagnóstico;
  no retirar re-planTOCTOU,hashes,fsync ni reutilización de bloques en updates.

### Checkpoint c105 (2026-10-18)

- Plan `done/catalog-size-pass-optimization-plan.md` completado,APK105auditada,
  pruebas/reviews focales sin bloqueos. Checkpoint/recibo preservados al actualizar.
- Query freshcount/min/max166/208ms;scan completo/apertura/re-plan mantienen
  validación de integridad ycostes. Replay71.749objetos completo772,924s;descarga
  nueva ybackground2min probados. 0,61991MiB/sforeground/0,46100background,
  FGS/16workers yRAM174–188,8MiB. No confundirahorro de preparación conHTTP.
- Fetch20muestras:~78,5% del tiempo muestreado espera cabeceras(DNS/TCP/TLS/HTTP),
  mediana77ms/media227ms ypico1664ms. No atribuirlo todo aTLS/pool sin evidencia.
  Observación de15outputs listos+workers inactivos+head esperando compatible con
  bloqueo de cabeza,solo lectura por ahora. No cambiardurabilidad ni cortes de
  actualización por una prueba de rendimiento;ninguna descarga nueva del100%
  por las optimizaciones presentes.

Límite fuera de esta reparación: Transaction preexistente falla al reaplicar bytes
idénticos y deja journal atascado. Primera instalación/resume parcial actual no
afectados (root sin `.build.info`,loose/config/Data publicados; marker no es input
de commit). No declarar updates sin cambios/repair completos ni confirmar una
reaplicación sin delta hasta resolver su semántica de backup.

## Checkpoint c97 (2026-10-18)

- c97/0.2.73 instalada y auditada (`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`): frames
  'E' verificados sin descifrar (`ForeverBlte.verifyEncodedObject`), overlay del
  código59 intacto, mecanismo preimage/postimage como CASC.
- Físico confirmado: plan 85,0 GiB a USB 6AD6-EF0E confirmado; descarga corriendo
  y SIN aborto de verificación (c96 abortaba en minutos); 73,6 MiB de 85,0 GiB en
  ~40 min con interrupciones de prueba; USB en crecimiento monotónico
  (17,7→126 MB usados aunque con reciclaje de fases de prep).
- Pausar (botón) ≙ estado persistido «Descarga pausada» confirmado. Reanudar =
  flujo 'Descargar e instalar' → «Recuperando la descarga anterior…» → continúa:
  USB no perdió datos. PENDIENTE UX: reanudación directa desde notificación
  (hoy el resume repite prep ~4 min).
- Fondo: FGS `isForeground` con notificación (action Pausar); app en fondo
  (launcher arriba) durante +2 min ⇒ descarga continuó a ~270 KB/s.
- PENDIENTE: descarga sostenida hasta el primer lote del objeto
  `0006690c41014581816e37b9b5f6846e` (frames 'E' reales) y a largo plazo hasta
  los ~85 GiB; verificación de integridad con catálogo del cliente, extracción
  manifest/install y arranque del cliente aún no probados físicamente.

## Open Issues

- Discrepancia observada al finalizar el cálculo70450: la instalación interna
  legacy ya no aparece en su ruta conocida. No se ejecutó borrado/migración en esta
  operación y no se ha establecido la causa. No afirmar integridad presente por
  hashes históricos; el USB sigue vacío de payloads hasta confirmación de85GiB.

- El código88 está instalado; los cambios recientes de Cuenta/UI en fuentes no.
  El plan no debe presentar esa discrepancia como corregida hasta entregar otro APK.
- Cuenta de pruebas: autorización previa existe, pero el secreto efímero se eliminó.
  No buscarlo en logs ni reconstruirlo. Su introducción privada en la app/fichero
  efímero puede requerir una intervención del usuario al llegar al login real;
  mientras tanto continuar la implementación y usar datos sintéticos no personales.
- La publicación70450 no confirma compatibilidad Forever;70425 está confirmada.
  No bloquear el destino conocido por haber cambiado la última fila de Blizzard.
- Una descarga completa puede implicar decenas de GB; el USB tiene capacidad libre
  observada, pero el delta real, disponibilidad y modos de los payloads aún deben
  medirse. La autorización del plan no autoriza formateo, borrado ajeno o transferencias
  grandes silenciosas. Se conserva confirmación de coste/destino en la acción real.
- Los avisos de formato/proceso/almacenamiento no admitidos deben corresponder a
  evidencia reproducible. Si un bloqueo externo impide realmente continuar, registrar
  el hecho y la intervención mínima necesaria; no declarar terminado lo que no lo esté.
- Pantalla completa sin franjas, mando/IME y gameplay sostenido siguen pendientes
  del plan de producto previo. Esta entrega conserva orientación, preset y entrada;
  no declara esas otras pruebas completas por terminar descargador/cuenta.
