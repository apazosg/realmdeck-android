---
id: usb-resume-ux-release-2026-10-30
state: in-progress
date: 2026-10-30
---

## Summary

El usuario quiere recuperar y continuar en el Xaga los datos parciales que venían
de otro móvil, mejorar la claridad y sencillez del flujo, reducir el tiempo y,
después, publicar la versión en GitHub. El USB ya está conectado al Xaga y no debe
trasladarse. Este plan complementa el plan de portabilidad ya aprobado; no sustituye
sus consentimientos específicos ni su objetivo de revisión de hasta 900 s.

### Objetivo actualizado por el usuario

- El usuario ha confirmado como objetivo reescribir el código de recuperación de
  datos descargados: tratar el parcial existente **como una actualización**,
  reutilizando lo existente y descargando solo lo que falte o esté dañado sobre la
  marcha. Rechaza explícitamente esperar una verificación completa antes de iniciar
  la descarga, las dos pasadas actuales y que restaurar tarde tanto que no compense.
  No basta con mejorar el texto ni aplicar parches de ritmo.
- Evitar trabajo duplicado y capas innecesarias; conservar las comprobaciones que
  realmente acreditan los objetos reutilizados, los límites de consentimiento,
  espacio y red, y la protección de los datos existentes. La recuperación debe ser
  interrumpible y reanudable. No presentar como medido un rendimiento aún no probado.
- El conflicto con el antiguo delta **real** previo al payload quedó resuelto con
  la autorización del máximo informado indicada abajo. No llamar «real» a una
  estimación ni superar el coste consentido; la reconciliación de specs conserva
  por separado el delta real del flujo de cliente completo pegado.
- La C168 anterior verificaba los objetos en la revisión y repetía una autenticación
  completa durante la adopción. El ritmo de ~2,35 registros/s se midió solo en el
  checkpoint, no identifica el cuello de botella. El checkpoint parcial preparado
  no está acreditado como reanudable. La última observación física fiable fue a las
  20:50 UTC con ~4,46 %; es progreso de adopción, no descarga. No pausar, reinstalar
  ni modificar el USB sin analizar el riesgo de perder
  el progreso y conservar las autorizaciones aplicables.
- Los reinicios interrumpieron las dos investigaciones de integración y de impacto
  en especificaciones, sin informe final. No se ha completado la reescritura para
  este objetivo. El árbol contiene cambios anteriores sin validar y un plan ajeno
  `voice-chat-plan.md`; conservarlos, no sobrescribirlos ni incluirlos por accidente.
  No instalar ni publicar la candidata anterior como solución del nuevo objetivo.
- El usuario aprobó el 30-10 US-009 UC-05 y AC-08 para el flujo incremental y
  seleccionó «Máximo informado»: sustituir el delta exacto previo por un máximo
  conservador de descarga y espacio mostrado antes del consentimiento, sin exceder
  ese límite y mostrando como desconocido lo que todavía no se sabe. La autorización
  anterior del plan físico no autoriza sobrepasar sus costes. US-009 está `dirty`
  mientras se reconcilian FR-011/012 y sus TC; US-008 ya estaba `dirty`. Mantener
  también las restricciones y la presentación honesta del progreso de US-008.
- El usuario insistió en continuar e iterar hasta lograr una recuperación funcional
  y rápida, sin pedirle decisiones, e indicó expresamente instalar y validar una vez
  haya una candidata. Esta autorización no convierte una candidata no validada en
  segura: valorar la interrupción del proceso actual y preservar sus datos, e instalar
  solo tras las comprobaciones locales necesarias; después medir e iterar en Xaga.
- El 31-10 el usuario aprobó una excepción acotada a los objetos con frames TACT `E`
  de la continuación incremental: comprobar EKey, estructura, checksums y tamaños
  codificado y declarado, sin afirmar CKey ni tamaño descifrado real. Los objetos sin
  `E` mantienen ambas comprobaciones independientes; los flujos ordinarios no cambian.
  AC-08/FR-011/TC-009 y el nuevo TC-014 recogen la decisión y US-008 remite a esa
  excepción únicamente para el mismo flujo. La implementación y pruebas siguen
  pendientes. Sin tabla BLTE autenticada que declare la longitud decodificada no se
  puede cumplir la comprobación aprobada y la candidata no se acredita como reusable.
- Inspección para el rediseño: el updater ordinario ya tiene una ruta serial que
  autentica candidatos del CASC y solicita de la red únicamente los que faltan
  (`ForeverClientUpdater.writeCascDeltaSerial`, `ForeverCasc.writeTargetIncrementalResumable`).
  Reutiliza segmentos existentes sin copiar su contenido y guarda checkpoint, pero
  espera índices de una fuente CASC, no los segmentos parciales ajenos tal cual. La
  recuperación nueva debe resolver esa diferencia sin acreditar cabeceras como
  objetos íntegros, sin hacer una copia completa y sin descargar objetos que estén
  más adelante en los segmentos. Una exploración acotada de cabeceras o un índice
  candidato podrían ser necesarios; su coste, la integración con la propiedad y la
  reanudación todavía no están demostrados. Una cabecera corrupta puede impedir
  localizar objetos posteriores y debe fallar preservando el origen, no habilitar
  una descarga destructiva. No hay aún diseño ni medición de extremo a extremo.
- Se reconciliaron US-009 FR-011/012 y TC-009–013 con el flujo aprobado, y US-008
  AC-09/FR-010/TC-015 con un total todavía desconocido; ambos specs siguen `dirty`
  y no se afirma implementación ni cobertura. La reconciliación se comprometió
  por separado como `976e21a` en `perf/download-bottlenecks`; se verificaron
  frontmatter, IDs/referencias, enlaces relativos y `git diff --check`. Una primera implementación delegada
  no modificó archivos porque no pudo completar de manera segura el puente de
  segmentos ajenos al escritor existente. Se acotó el primer paso al escaneo local
  de cabeceras que devuelve únicamente candidatos **no verificados**, sin tocar la
  interfaz ni el dispositivo, con pruebas focalizadas; sesión completada
  `ses_e7176eae6ffes0lPtYa4gFXQrD`. Este paso por sí solo no
  implementa descarga incremental ni permite instalar o publicar la candidata.
  En paralelo, la sesión de solo lectura `ses_e71740843ffeY0DcYsHT4ra8qS` identifica
  el enlace entre consentimiento, updater, límites de red/espacio e interfaz para
  orientar la integración; no hace cambios ni accede al móvil. Resultado: el flujo
  actual es `reviewPartial → beginPartialPortabilityReview →
  verifyPartialPortabilitySource` (todas las verificaciones de cuerpo), seguido de
  confirmación, `confirmPartialPortabilityAdoption → plan → handoff` y el servicio
  en primer plano. El límite de metadatos de 1 GiB no es consentimiento de payload.
  El coste validado al inicio y los contadores de la UI no garantizan por sí solos
  un límite duro de bytes: debe cubrir rangos reanudados, reintentos y cualquier
  prefetch en el punto común de transferencia, también tras una caída. Los máximos
  numéricos deben calcularse del catálogo admitido y el plan, no inventarse.
- Integración pendiente de diseño y prueba: `prepareResumableOutput` y
  `loadResumableCheckpoint` tienen reglas estrictas para estado poseído y pueden
  truncar colas; además la ruta ordinaria exige source/output disjuntos. No sembrar
  su checkpoint con cabeceras no autenticadas ni reutilizar sin más su lógica de
  limpieza sobre el parcial ajeno. Una posible dirección es reservar los segmentos
  ajenos inmutables bajo una transición durable específica, acreditar cada candidato
  individualmente antes de su uso y escribir nuevos segmentos poseídos aparte, pero
  aún debe demostrarse recuperación, exclusión, protección ante reversión, límites
  de red y espacio y publicación. Una reserva conservadora durable de bytes antes de
  cada petición puede evitar superar el máximo tras reintentos o caídas, pero no es
  diseño aprobado ni implementado y hay que validar cómo manejar respuestas y rangos.
- La primera pieza es ahora un escáner de candidatas por cabecera en
  `ForeverCasc.kt`, sin lectura de cuerpos ni adopción y sin llamadas de producción.
  Una revisión independiente (`ses_e717096fdffe5kVmU1lsABelkD`) detectó que sus
  hashes de inventario compartían dominio con los verificados; se separó el dominio
  provisional conservando exactamente el verificado y se añadió una prueba. No
  confundir estas candidatas con objetos íntegros; falta toda la integración.
- La presentación de un total todavía desconocido muestra los bytes medidos sin
  porcentaje ni total supuesto; estima velocidad solo con muestras reales y dice
  explícitamente que no hay estimación de tiempo. Los cuatro archivos modificados
  son `overlay/ForeverFunctionalUiText.kt`, `overlay/ForeverFunctionalLauncherUxTest.kt`,
  `updater-gate/ForeverClientDownloadRate.kt` y `updater-gate/ForeverClientDownloadRateTest.kt`.
- La primera ejecución Gradle en reconstrucción aislada falló al compilar: el
  callback de tres argumentos del escáner de candidatas era `{}` de cero argumentos.
  Se corrigió a `{ _, _, _ -> }`; todavía no había tests ejecutados. El log y
  resultado original son `/tmp/opencode/foreign-candidate-and-unknown-total-20261030.{log,result}`.
- La repetición Gradle en la misma reconstrucción aislada
  `/tmp/opencode/foreign-stage-candidates-20261030-reconstruction`: los cinco métodos
  de inspección/candidatas, estimador y texto UX, y las pruebas reales del ledger y
  transferencia. Incorpora las fuentes nuevas, usa un worker y limita memoria.
  Identificador `sh_18ea06af5001tM2SDo2iSA3hc1`, timeout 900 s; log y resultado en
  `/tmp/opencode/foreign-candidate-budget-20261030.{log,result}`. Tras el reinicio
  del servidor el wrapper terminó: **32 pruebas pasaron y 1 falló**. El fallo es la
  fixture de `foreignStageCandidateScanPublishesOnlyProvisionalLocationsWithoutBodyAuthentication`:
  lee el último byte del cuerpo y después escribe en el siguiente encabezado porque
  avanzó el puntero. La sesión delegada alcanzó su límite y se corrigió directamente,
  volviendo a buscar el byte antes de escribirlo.
  Esta ejecución confirmó que el error de compilación anterior quedó resuelto.
- Se lanzó una única repetición de la prueba reparada y toda la clase de transferencia,
  incluyendo la regresión de lecturas anticipadas con fixtures separadas. Shell
  `sh_18ea5bfee001fEZfKJxs5CsZ2A`, timeout 850 s; log y resultado
  `/tmp/opencode/foreign-candidate-transfer-followup-20261030.{log,result}`. El
  servidor reinició y notificó cancelación, pero el wrapper podría seguir vivo;
  no repetirla mientras no se conozca el resultado o transcurra el timeout.
- `uv run pytest tests/test_functional_launcher_ux.py -q` (con uv en
  `$HOME/.local/bin/uv`) tuvo 20 aprobados y 1 fallo ajeno previo: la prueba exige
  `Inicio automático no disponible todavía. Inicia sesión dentro de WoW.` pero la
  fuente de autologin en HEAD no contiene ese texto. No se ha modificado autologin.
- La investigación `ses_e716da9eaffee03ECC6uyRscZW` confirmó que el escritor ya
  autentica cada candidata antes de usarla y descarga lo ausente, pero necesita un
  modo para un prefijo ajeno inmutable: fuente sin índices y salida coinciden, y
  `loadResumableCheckpoint` solo conoce segmentos poseídos y puede truncar colas.
  Las candidatas deben volver a autenticarse tras reanudar; solo los nuevos appends
  poseídos entran al checkpoint. Falta diseñar una representación durable que fije
  el límite del prefijo ajeno, su identidad y la recuperación sin tocarlo. Sin ella
  no hay integración segura ni implementación final acreditada.
- El intento de implementar ese modo con `ses_e7176eae6ffes0lPtYa4gFXQrD` terminó
  bloqueado sin modificar archivos: hace falta definir un registro versionado del
  prefijo ajeno, forzado antes de escribir, y cómo validarlo y limitar el recovery
  al sufijo poseído. El intento previo de integración también se detuvo sin editar;
  no repetir delegaciones ciegas hasta resolver explícitamente este contrato.
- La investigación `ses_e716da9e0ffeiWLm08IY9CEh6v` identificó los dos puntos de
  petición de payload: `streamObject` y el batch en `ForeverClientUpdateTransfer`.
  El presupuesto debe compartirse y reservarse duraderamente antes de cada intento,
  incluyendo rango reanudado, retry y batch, y vincularse a operación, catálogo,
  destino y revisión. Un fallo incierto no se reembolsa automáticamente; se puede
  agotar la autorización conservadoramente. Contaría cuerpos HTTP consumidos por
  la app, no cabeceras, reintentos TCP ni bytes físicos en búfer: no prometer un
  límite exacto de tráfico de red. Falta definir fórmula de máximo que incorpore los
  reintentos y comprobar el espacio por separado; no usar el límite de metadatos.
- En curso `ses_e7169c746ffel35D7npugkMqLh`, delimitado exclusivamente al ledger
  durable privado y los dos hooks de transferencia, sin integración productiva ni
  cambio de la app/dispositivo. La reserva se hace antes de abrir la conexión,
  incluyendo cada intento/rango/batch más el byte extra del chequeo de longitud; no
  se reembolsa un resultado incierto. Las pruebas Gradle las coordina el padre.
- El ledger y los hooks están implementados pero no conectados; la revisión
  independiente `ses_e71675f80ffeY0yuwSs2mkWtVI` no encontró defectos accionables y
  señaló como límites los fallos de barrera y carreras de ruta sin test. Se detectó
  después que faltaba constructor interno con conexión oficial y que los errores de
  persistencia no deben pasar por el retry de red; el mismo autor corrigió ambos y
  añadió una prueba. `reconstruct_functional_source.py` incluye las dos nuevas fuentes.
- Las tres pruebas de `ForeverClientPayloadBudgetTest` pasan con el compilador Kotlin
  2.1.21 y JUnit aislados fuera del árbol. El primer intento aislado de
  `ForeverClientUpdateTransferTest` carecía de las dependencias OkHttp; el segundo,
  con esas dependencias, fue cancelado al reiniciarse el servidor antes de producir
  resultado. Ninguno sustituye la prueba Gradle ni la integración de la app.
- El ledger actual sincroniza a disco antes de *cada* petición; puede perjudicar el
  rendimiento al escalar a cientos de miles de objetos. No se ha medido en Android
  ni debe presentarse como solución de rendimiento. Cualquier amortización posterior
  debe conservar el límite durable y no contabilizar reservas inciertas como libres.
- Su límite se define actualmente para bytes de cuerpos de respuesta entregados a la
  aplicación, reservados antes de cada petición. No se ha demostrado un límite de
  tráfico físico total (cabeceras, TLS y posibles lecturas anticipadas de la pila);
  no presentar ambos conceptos como idénticos sin verificación y encaje con el coste
  autorizado. Tampoco hay pruebas de fallos de barrera de persistencia en Android.
- Se detectó además una posible lectura anticipada *dentro de la propia aplicación*:
  `BufferedInputStream` sobre la conexión podría consumir hasta 64 KiB aunque la
  reserva sea menor. Se retiró ese buffer en ambos caminos; la prueba contra
  respuestas excesivas usa fixtures independientes para comprobar las rutas individual
  y batch. Gradle aislado validó la clase completa de transferencia y la prueba de
  cuerpos candidatos corruptos, con resultado `BUILD SUCCESSFUL` en 4 min 1 s.
- `uv run pytest tests/test_reconstruct_functional_source.py -q`: 39 pasan, 1 falla
  por el pin SHA-256 del postimagen de `ForeverCasc.kt`; la prueba detiene ahí antes
  de comprobar el pin del test. El pin coincide con HEAD, pero ambos archivos CASC
  contienen modificaciones anteriores y las candidatas nuevas. No actualizar los
  pins hasta revisar los postimágenes completos; es una barrera real de reconstrucción
  además de las pruebas Gradle pendientes.
- `git diff --check` del árbol y de los dos archivos nuevos del presupuesto: sin
  problemas de espacios. No se ha instalado, reiniciado ni publicado ninguna app,
  y los cambios aún no están confirmados: los archivos CASC se solapan con trabajo
  anterior y la integración y verificaciones exigidas siguen incompletas.
- Avance local posterior: `inspectForeignStageCandidates` puede recibir el inventario
  previo del prefijo y volver a leer solo esos segmentos originales aunque ya haya
  entradas adicionales en el mismo directorio. Comprueba ruta, catálogo, identidad,
  tamaño, fecha, cabecera y digest; sus callbacks siguen siendo pistas provisionales
  que no acreditan integridad ni propiedad del sufijo. La admisión ordinaria sin
  prefijo sigue rechazando entradas adicionales. Añadidas dos pruebas focalizadas de
  coexistencia del sufijo y rechazo de alteración; la ejecución Gradle focalizada
  (`foreign-prefix-candidate-rescan-20261030`) terminó con código 0 y
  `BUILD SUCCESSFUL` en 3 min 55 s. Falta
  persistir y ligar ese inventario a la transición durable y a la propiedad real,
  integrar escritor/recuperación/presupuesto/interfaz, y validar antes de instalar.
- Añadidos codificación/decodificación acotadas del inventario de prefijo, con
  checksum y hash de la identidad de operación, y prueba focalizada aún sin ejecutar.
  El checksum no es una credencial: la integración debe ligar el archivo a la
  transición y comprobar la propiedad del workspace y del marcador. No se ha
  conectado ni escrito el registro en ningún dispositivo.
- Añadido el chequeo rápido de límite `revalidateForeignCandidatePrefix`: comprueba
  ruta, identidad, tamaño, fecha y hash de cabecera de cada segmento, sin leer
  cuerpos ni autorizar escrituras; se añadió una prueba, aún sin ejecutar, que
  altera la cabecera y restaura la fecha. Debe invocarse bajo exclusión antes de
  cualquier recuperación del sufijo, no sustituye al escaneo de pistas ni prueba
  la propiedad o la integridad de objetos.
- Orden de integración seguro: (1) publicar el inventario privado ligado a la
  identidad de etapa y a un intent durable/versionado sin romper la recuperación
  de intents antiguos; (2) antes de limpiar/truncar el CASC, revalidar todos los
  segmentos originales y excluir explícitamente sus IDs de cualquier recuperación
  de colas o segmentos incompletos, rechazando entradas no acreditadas; (3) construir
  las pistas provisionales desde ese prefijo, sin tratar el checksum como prueba de
  cuerpos; (4) recorrer primero los objetos que no tienen pista, con descarga
  agrupada y presupuesto compartido, y después verificar los candidatos al usarlos,
  descargando los corruptos. Así no se fuerza una pasada completa de autenticación
  antes de iniciar las descargas, ni se vuelve a copiar el prefijo. El orden debe
  preservarse también al reanudar y no producir registros duplicados; no se debe
  afirmar que el acceso de red ha empezado hasta medirlo. La tabla de pistas, los
  marcadores de checkpoint y la contabilidad del pico deben seguir acotados.
- El almacén existente `CandidateHintStore` puede representar las ubicaciones
  provisionales; `findCanonicalCandidate` autentica cada objeto antes de admitirlo.
  Esa ruta usa hoy una copia temporal por objeto, que añade E/S. Existe
  `verifyStagedReplayObject`, que verifica BLTE/EKey/CKey directamente sobre un
  `CascReplayReader` acotado y comprueba la relación ruta/descriptor antes y después.
  Podría reutilizarse para candidatas del prefijo, pero solo si se prueban también
  sus límites inmutables y el fallo seguro; no está conectado ni se ha medido.
- El productor prefetch ordinario no puede alimentarse ciegamente de todo el catálogo:
  haría peticiones para pistas que después resulten reutilizables. En esta ruta solo
  puede anticipar claves sin pista; una candidata corrupta debe verificar primero y
  recibir su solicitud de reemplazo después. El nuevo orden necesita pertenencia al
  catálogo con ordinales estables tanto en el checkpoint como en el recorrido, y debe
  conservar la asociación del registro con la petición; la ruta serial actual usa
  un único registro de catálogo en vuelo y no hace esa reordenación por sí sola.
- Cuestión de integridad a resolver antes de acreditar la implementación frente a
  US-009 AC-08: `ForeverBlte.verifyEncodedRange` y `verifyEncodedObject` admiten
  frames TACT `E` sin descifrarlos y, como documenta el código, no calculan su CKey
  de contenido; sí validan el EKey/estructura y los checksums cifrados admitidos.
  El 30-10 se comprobó en una muestra real guardada durante la revisión c98 que
  hay al menos un objeto cifrado en el catálogo activo: coinciden EKey, checksum
  del frame, longitud codificada y longitud decodificada **declarada**. No se
  descifró el contenido, no se calculó su CKey y no se midió su longitud
  decodificada real. La evidencia histórica c97 indica una incidencia no marginal
  y que la clave necesaria no estaba publicada; ver
  [estado del launcher](../../docs/launcher-app-status.md). El 31-10 se aprobó la
  excepción descrita arriba; no acreditar CKey ni tamaño decodificado real para `E`.
- La revisión independiente del último cambio de prefijo se intentó, pero la cuenta
  de la sesión alcanzó su cuota y no devolvió informe; no considerarla aprobada.

## Content

- C168 mostró en Xaga una revisión confirmable de 822.658 objetos reutilizables,
  322.320 objetos CASC pendientes, hasta 36,9 GiB de descarga y hasta 39,4 GiB de
  espacio adicional (hasta 2,0 GiB en la app y 37,4 GiB en el USB). Se desconoce la
  duración total de la revisión. La adopción se ha solicitado una vez; su finalización
  y el inicio de la descarga aún no están acreditados.
- Priorizar aprovechar ese plan, no perderlo innecesariamente instalando otra APK
  o repitiendo la revisión. La adopción crea estado propio y puede actualizar el
  marcador. En la C168 ya instalada, la descarga posterior requiere su confirmación
  separada; se ha autorizado enviarla una sola vez si el plan conserva identidad y
  costes. Ante cambios o fallos, no inventar el resultado ni relajar las guardas.
- Revisar el recorrido de almacenamiento y de gestión del cliente para que el
  usuario distinga una descarga parcial, la verificación, el resultado, la
  recuperación y la descarga pendiente. Simplificar textos, navegación y acciones
  confusas sin ocultar costes ni eliminar los consentimientos vigentes. No presentar
  un cliente parcial como instalado o listo para jugar.
  Actualmente el destino se selecciona en Almacenamiento y la revisión se inicia
  aparte en Cliente y actualizaciones; las acciones de revisión y de recuperación
  interrumpida aparecen juntas. Por petición posterior del usuario, integrar la
  detección y recuperación en el flujo normal con el mínimo de clics: un solo
  consentimiento explícito sobre costes puede autorizar adopción y descarga, con
  revalidación antes de transferir. Seleccionar o abrir sigue sin iniciar red ni
  mutación; ante cambios se requiere revisar el plan nuevo.
- Mejorar el tiempo de revisión hasta el resumen confirmable; el objetivo solicitado
  es 900 s en la fixture física documentada, no una garantía universal. En C167 el
  catálogo seguía preparándose a los 890,6 s; en C168 no se observó el tiempo total.
  Elegir una optimización acotada basada en evidencia y medir fases y tiempo total.
  Si ya se recuperó la fixture real, no dañar ni revertir su progreso para repetir
  la prueba: usar una fixture segura y registrar la diferencia de condiciones.
- Mantener las verificaciones EKey, BLTE, CKey y tamaños, los límites de recursos,
  identidad, exclusión, Wi-Fi, cancelación y durabilidad. No copiar estado privado
  del móvil anterior ni reinterpretar el marcador como prueba de integridad.
  Respetar US-007, US-008 y US-009; si se propone modificar criterios aprobados,
  documentar y aprobar primero el cambio de comportamiento y su impacto.
- Publicar después como **prerelease experimental**, siguiendo la práctica de
  `docs/releasing.md`, sin afirmar un rendimiento o compatibilidad no demostrados.
  Derivar la integración pública del `main` público, nunca publicar sin auditoría
  la historia local o privada. Comprobar identidad, versión,
  firma, checksum, privacidad, notices y licencias de los componentes y adjuntos;
  mantener cliente, datos CASC, claves y evidencias privadas fuera de la publicación.
  Publicar solo el APK y materiales cuya redistribución esté autorizada y auditada.

## Implementation / Action Items

- [x] 1. Confirmar el resumen C168 en Xaga y solicitar la adopción una sola vez.
- [ ] 2. Implementar la continuación incremental aprobada: fijar un prefijo ajeno
  inmutable y seguro al reanudar; autenticar cada candidata al uso; conservar los
  segmentos originales y escribir solo los objetos faltantes o dañados en un sufijo
  poseído. No esperar la finalización de C168 como sustituto de este objetivo.
- [ ] 3. Integrar el máximo consentido con reservas durables de payload para cada
  petición, comprobar el espacio por separado y conectar revisión, transición,
  reanudación e interfaz; realizar las pruebas focalizadas de seguridad y mensajes.
- [ ] 4. Compilar y auditar la candidata exacta, pedir revisión independiente de
  las partes sensibles y probar de manera proporcionada. No interrumpir una descarga
  activa para instalar una APK; actualizar sin desinstalar ni borrar datos.
- [ ] 5. Medir en condiciones documentadas y reflejar si el objetivo de 900 s queda
  demostrado, no demostrado o incumplido; no alterar el parcial para la medición.
- [ ] 6. Preparar la integración pública y la prerelease GitHub, con notas en inglés,
  assets auditados y sumas; publicar cuando pasen las barreras de privacidad,
  redistribución y verificación y comprobar metadatos y hashes publicados.

## Open Issues

- Integridad de `E`: la decisión de producto quedó resuelta el 31-10 y las specs
  `dirty` se reconciliaron para el flujo incremental, sin acreditar cobertura. La
  implementación debe distinguir el veredicto `encrypted`, comprobar que existe una
  tabla BLTE autenticada con los tamaños declarados coincidentes y no afirmar CKey o
  tamaño real del contenido cifrado. Se añadió el verificador separado
  `verifyForeignPartialEncodedRange`, que rechaza `E` sin tabla y mantiene la
  verificación completa para objetos sin `E`; el verificador anterior conserva su
  conducta. Las 10 pruebas de `ForeverBlteRangeTest` pasan con Kotlin 2.1.21 y
  JUnit aislados, incluidas tabla válida, `E` sin tabla, corrupción, tamaño, CKey y
  objeto mixto `N`/`E`. **No está conectado al flujo incremental**, y los llamadores
  existentes siguen descartando el veredicto: esto aún no demuestra conformidad ni
  autoriza instalar una candidata. Mantener CKey y tamaño real para los objetos sin
  `E`, y el requisito íntegro de los flujos ordinarios.
- La primera compilación Gradle focalizada agotó el heap del compilador Kotlin antes
  de ejecutar los tests. El reintento con daemon Kotlin de 4 GiB sobrevivió al
  reinicio del servidor y terminó con código 0: `BUILD SUCCESSFUL`, 2 pruebas
  `ForeverCascTest` ejecutadas, ninguna omitida ni fallida
  (`foreignCandidatePrefixRecordIsBoundedAndOperationIdentityBound` y
  `foreignCandidatePrefixBoundaryCheckDetectsHeaderChangeWithRestoredMetadata`).
  El resultado y log están en
  `/tmp/opencode/foreign-prefix-record-memory-retry-20261031.{result,log}`. Es una
  prueba acotada del registro y la frontera; no acredita la transición, la autoridad
  del sufijo ni el flujo completo. La nueva verificación BLTE se probó por separado.
- La revisión independiente de la excepción TACT y de la autoridad durable del
  prefijo se intentó, pero los workers respondieron límite de uso alcanzado. Es una
  barrera de auditoría pendiente para esta lógica sensible; la inspección propia y
  las pruebas aisladas no la sustituyen antes de instalar o publicar.
- Un nuevo intento de continuar la implementación del prefijo con el trabajador
  `ses_e6ec4cd51ffehOAurRUqET0KX3` terminó por límite de uso, sin resultado de
  implementación acreditado. No se ha instalado ni probado otra versión en Xaga.
- Bloqueante de implementación: aún no está definido ni probado el contrato
  completo de la autoridad durable del prefijo ajeno en todas las fronteras de
  transición, cancelación, caída y finalización de índices. La reanudación debe
  excluir los sufijos poseídos del nuevo escaneo de cabeceras y nunca confundirlos
  con origen; los índices/scratch de una finalización interrumpida requieren el
  recovery validado, no un borrado genérico. El registro privado debe vincular la
  operación y catálogo a la ruta y el inventario exacto de segmentos, y solo permitir
  recuperar/truncar el sufijo probado como propio. Un inventario no acredita cuerpos.
- Bloqueante de integración: el máximo de payload debe calcularse de forma
  conservadora del catálogo admitido y mostrarse antes de consentir; el requisito de
  espacio se calcula aparte. Debe incluir la sobrecarga de reservas por intento y el
  byte extra del chequeo de longitud, y ningún camino de payload puede eludir el mismo
  presupuesto durable. Sigue pendiente probar la UX y la reanudación completa.
- El 30-10 a las 17:19–17:21 UTC se encontró el Xaga mediante el transporte
  ADB inalámbrico descubierto (el nombre puede contener espacios). La app funcional
  estaba viva y la UI seguía en adopción, sin servicio de descarga ni payload
  acreditados. El checkpoint privado **preparado** pasó de 524.992 bytes
  (~7.291 registros) a 545.080 bytes (~7.570 registros) entre aproximadamente
  las 17:19:41 y las 17:21:34 UTC; el formato observado usa cabecera de 40 bytes
  y registros de 72. Esto es progreso de adopción, no progreso de descarga. A ese
  ritmo de muestra (~2,5 objetos/s), extrapolar los 822.658 objetos revisados da
  varios días; la mezcla de objetos y el ritmo pueden variar. La UI ya mostraba
  adopción a las 17:02 UTC; posteriormente el mtime del directorio privado
  preparado indicó creación de sus entradas hacia las 16:28:01 UTC (aproximación
  del comienzo del escaneo, no hora exacta del clic). A las 17:24:38 UTC el mismo
  checkpoint contenía ~8.010 registros y se había actualizado un segundo antes.
  No se ha pausado, reinstalado ni repetido el proceso. Se investiga por separado
  el coste repetido y los riesgos de reducir comprobaciones antes de proponer
  cambios. La consulta por lotes del catálogo en la adopción es un candidato
  de bajo riesgo, aún sin perfil ni ganancia física demostrada. La fuente
  reconstruida de la C168 confirma que usa búsqueda individual y revalidación
  completa por objeto; eso no demuestra cuál domina el tiempo. Una muestra
  pasiva de CPU del proceso, ~5 s, dio ~59 % de un núcleo y no separa costes.
  Otra lectura pasiva a las 17:33:24 UTC mostró ~9.260 registros preparados,
  aún actualizándose; entre las 17:24:38 y las 17:33:24 la media fue ~2,38/s.
  A las 17:38:48 UTC se observaron ~10.021 registros, con actualización un
  segundo antes y el proceso vivo. A las 17:48:41 UTC había ~11.427 registros;
  la media entre estas dos muestras fue ~2,37 objetos/s. Son registros de
  adopción, no descarga de payload; una extrapolación al resto de los 822.658
  objetos revisados sería de unos cuatro días si el ritmo no cambiase.
  Tras el reinicio del servidor, una lectura pasiva a las 19:02:16 UTC encontró
  el proceso de la app vivo, en primer plano, y el checkpoint preparado actualizado
  un segundo antes, con 1.568.920 bytes: ~21.790 registros (~2,65 % de los objetos
  revisados). La media desde las 17:48:41 fue ~2,35 registros/s, equivalente a
  unas 95 horas restantes si se mantuviese el ritmo. Es una extrapolación, no una
  previsión garantizada; no acredita descarga. No se ha pausado ni reinstalado.
  Otra consulta pasiva de metadatos a las 19:20:31 UTC encontró la app viva y un
  checkpoint preparado con 1.754.320 bytes, 24.365 registros completos (~2,96 %),
  actualizado un segundo antes. La media desde las 19:02 fue ~2,35 registros/s;
  sigue siendo adopción, no payload. Dos transportes ADB correspondían al mismo
  teléfono físico; no se envió ninguna interacción ni se leyó el contenido.
  A las 19:41:12 UTC, una nueva consulta pasiva del paquete funcional
  `com.wowforever.arm64probe` lo encontró vivo, con un único checkpoint preparado
  de 1.962.904 bytes: 27.262 registros completos (~3,31 %), actualizado un segundo
  antes. La media desde las 19:20 fue ~2,33 registros/s. Sigue siendo progreso de
  adopción, no evidencia de descarga. No se ha interrumpido ni modificado el móvil.
  A las 19:51:29 UTC, otra lectura solo de metadatos encontró el proceso vivo y el
  checkpoint preparado con 2.067.592 bytes: 28.716 registros (~3,49 %), con mtime
  de esa misma observación. La media desde las 19:41 fue ~2,36 registros/s. No se
  ha enviado ninguna interacción, ni se ha observado el contenido del checkpoint.
  A las 20:50:32 UTC, la app seguía viva y el único checkpoint preparado observado
  tenía 2.639.272 bytes: 36.656 registros (~4,46 %), con mtime 20:50:31 UTC. Media
  desde las 19:51: ~2,24 registros/s. Se consultaron solo metadatos; no hubo
  interacción, no se leyó el contenido y no acredita descarga.
  A las 21:10:48 UTC, otra lectura pasiva encontró la app viva y un checkpoint de
  2.828.992 bytes: 39.291 registros (~4,78 %), con mtime de la observación. La media
  desde las 20:50 fue ~2,16 registros/s. No se ha observado inicio de payload.
- La inspección de código sugiere que «Pausar» durante la adopción cancela de
  forma cooperativa, no reanuda desde el checkpoint preparado parcial. Antes del
  intent durable, la limpieza normal intenta retirar los artefactos preparados;
  una salida abrupta puede dejar huérfanos que bloquean el siguiente intento. La
  revisión también reside en memoria. No se ha probado en el dispositivo activo
  una pausa, limpieza o reanudación de esta adopción. No interrumpirla basándose
  solo en la mejora todavía no medida de la candidata. A las 19:06 UTC el teléfono
  estaba alimentado, con batería al 100 % y despierto; es una lectura puntual, no
  una garantía de ejecución durante los días estimados.
- Tras el reinicio, el 30-10 hacia las 17:02 UTC, se verificaron de nuevo la
  identidad del Xaga y la app C168 en primer plano. La UI mostraba «Verificando y
  recuperando la descarga existente…» y «Pausar»; no aparecía un servicio updater
  en la consulta. No se envió ninguna acción, se reinstaló ni se repitió la revisión.
  La ausencia de ese servicio no demuestra que la operación local haya terminado:
  la finalización de la adopción y el inicio de payload continúan sin acreditar.
- La implementación local de continuación unificada aún no ha pasado todas las
  pruebas Android; una revisión independiente identificó que el resumen de consentimiento
  debe mostrar también la versión. Se añadió la versión con una comprobación local
  (6 tests de `test_client_management_manual_check.py` aprobados). Se confirmó que
  comparar solo identidad superficial y costes agregados no liga la revisión al
  plan posterior: hay que resolverlo conforme a US-008-TC-010 antes de entregar el
  plan automáticamente. No instalar ni publicar esa implementación hasta resolverlo
  y ejecutar las pruebas pertinentes.
- La ejecución de ambos archivos Python de UI obtuvo 26 aprobados y un fallo previo,
  ajeno a esta tarea: la aserción de texto de autologin no coincide con la fuente ya
  presente en HEAD. Una ejecución posterior de `test_functional_launcher_ux.py`
  obtuvo 20 aprobados y el mismo fallo preexistente; la fuente de autologin no tiene
  diff respecto a HEAD. Se comprobó además que tanto la aserción obsoleta como su
  fuente sin esa cadena también existen en el `origin/main` público; no se cambió
  autologin fuera de alcance. La prueba nueva de ruta de almacenamiento se ejecutó
  contra el overlay selectivo del borrador público y pasó (1 test). Las pruebas
  Gradle de clasificación iniciadas antes del reinicio quedaron sin resultado
  confirmado.
- La compilación Kotlin independiente de la candidata local falló por exponer un
  tipo interno en tres API públicas, antes de ejecutar tests. Se detectó además
  que comparar directamente los contadores de la revisión y el plan reanudado
  rechaza una adopción normal: el segundo cuenta los objetos frente a la fuente
  publicada y acredita el checkpoint en bytes. Se aplicaron ambas correcciones;
  el test del vínculo pasó, pero hay otros fallos sin clasificar. No instalar ni
  publicar hasta resolver la validación.
- Para explorar una aceleración segura sin tocar el Xaga se añadió localmente el
  paso de consultas de catálogo por lotes y se preparó la posibilidad de reutilizar
  las comprobaciones de identidad agrupadas y la autenticación paralela de la
  revisión. La integración candidata ya usa esas comprobaciones en adopción con
  ambas pruebas dirigidas; amplía la ventana de detección de un cambio transitorio
  del USB hasta 16 registros o 250 ms entre registros. Se
  conservan las comprobaciones completas en los límites de segmento, alrededor del
  `force` y antes de la transición, y la autenticación individual de cada objeto.
  La revisión independiente no encontró defectos accionables, pero señaló que falta
  una prueba integrada de la transferencia de autorización entre interfaz y servicio.
  No se ha medido ninguna mejora física ni se ha instalado la candidata.
- La compilación Kotlin posterior sí llegó a ejecutarse: las dos clases focalizadas
  sumaron 144 tests, con 2 fallos y 1 omitido. Pasaron los tests añadidos de
  adopción, cadencia y vínculo del checkpoint. Los dos fallos afectan escenarios de
  reanudación y caché solapada. La prueba de caché pasó al ejecutarse aislada;
  la de reanudación volvió a fallar y una ejecución diagnóstica mostró una
  lectura concurrente de checkpoint truncado. El mismo test de reanudación
  también falló en una copia limpia del HEAD `8addebf`, pero sin exponer la
  excepción del hilo; la causa exacta en esa copia sigue bajo investigación.
  La lectura del productor omite los frames incompletos, de modo que truncar solo
  una cola parcial no explica ese EOF. El writer también puede eliminar un frame
  completo de finalización al recuperar; eso sí podría competir con un lector que
  capturó el tamaño anterior. Los 2.920 bytes congelados son compatibles con 40
  objetos sin ese frame, pero no prueban que antes hubiera 2.992 bytes ni identifican
  el truncado responsable. No se atribuye aún el fallo observado a esa carrera.
  Se inició un experimento focal instrumentado en una copia aislada, sin tocar el
  dispositivo ni las fuentes candidatas. Tras el reinicio se encontró su resultado:
  falló la compilación del test instrumentado por la referencia fuera de alcance
  `resumeFailure` (`/tmp/opencode/overlap-34-instrumented-20261030.run1.log`); no
  llegó a ejecutar el test ni aporta nueva evidencia causal. Se encargó corregir
  solo la instrumentación de esa copia y ejecutar una vez el test focal, con log y
  resultado externos. Un trabajo anterior había continuado tras el reinicio y su
  segunda ejecución sí compiló y reprodujo el fallo; el trabajo posterior comunicó
  que no había ejecutado Gradle y modificó solo la copia desechable. En el XML de
  la ejecución anterior, el productor capturó 2.992 bytes, la recuperación eliminó
  el frame FINISH y truncó a 2.920, y el productor obtuvo EOF al intentar leer ese
  frame. La excepción `truncated CASC file` del hilo quedó enmascarada por el test.
  Confirma la carrera en ese ensayo, no su frecuencia ni que la misma carrera sea
  la causa del fallo de la copia base. Log, estado y XML se preservaron en
  `usb-public-port-checkpoint-20261030-bYmYQW/overlap34-confirmed-race/`; SHA-256
  del XML `666028034d68097fda4161d40fa24260769d86bf7569b3dfb3c12af28c9c24bb`.
  La segunda tarea dijo no encontrar JDK 17, pero una comprobación local posterior
  confirmó el JDK 17 de la ruta documentada. No repetir la reproducción ya lograda.
  Se encargó una corrección acotada de esta carrera, con test determinista y
  repetición del test de integración solo después del cambio, al trabajador
  `ses_e71a35648ffeLwhyxo9VlAKinz`. Tiene propiedad exclusiva de `ForeverCasc.kt`,
  `ForeverCascTest.kt` y, solo si hace falta, `ForeverClientUpdaterTest.kt` en el
  árbol local; no lanzar otro escritor ni compilación equivalente mientras siga
  trabajando. No debe debilitar el rechazo de frames OBJECT dañados, identidad,
  cancelación ni límites de memoria. La solución aún requiere revisión independiente
  por afectar concurrencia y durabilidad, antes de portarla al árbol público.
  El primer diff capturaba FINISH en `size - 72`, posición incorrecta si hay bytes
  finales incompletos; se devolvió al mismo trabajador para alinear el último frame
  completo y añadir ese caso al test. El primer informe no ejecutó tests por un
  supuesto Gradle activo; el log muestra que ese daemon terminó su último build a
  las 19:03:41 UTC y no había otro build activo, por lo que se aclaró que podía
  ejecutar una sola vez la validación focal acordada. La reparación sigue en curso.
  Una revisión independiente identificó una posible carrera si la recuperación
  trunca el checkpoint durante la lectura, pero no probó que explique este test,
  cuyo checkpoint inicial estaba alineado. Las 11 pruebas focalizadas del
  servicio pasaron, incluida la entrega y limpieza de la autorización pendiente;
  queda por probar que la misma autorización llega al ejecutor. No dar la
  candidata por validada mientras no se resuelvan esas comprobaciones.
- Revisión independiente posterior del vínculo de autorización UI → servicio →
  ejecutor: sin defectos concretos y sin cambios concurrentes durante la lectura.
  SHA-256 revisados, respectivamente `ForeverClientManagement.kt`:
  `ddeab26be27ad9a1125637dc40e913c22e22259e89e3b0b0be75f57b33c86c08`,
  `ForeverClientUpdateForegroundService.kt`:
  `dc692888b4356cbd06b837225c66e176521a75908b926fbd2cef0bd21ffeb62d`,
  `ForeverClientUpdateService.kt`:
  `a98bc9bed47812d10039bebc2f6303b97d88ab8310a122c0732886545d8fadd8`, y
  `ForeverClientUpdater.kt`:
  `29b7f97390b62288f8bc599660de6c4760bb6d9972a237697c25543a275ab957`.
  La revisión no sustituye la prueba conductual pendiente del servicio al ejecutor.
- Durante esa prueba pendiente se observó que cinco tests preexistentes del candidato
  desaparecieron de `tests/test_client_management_manual_check.py` al retirar un
  test nuevo. Se restauraron y el archivo coincide byte a byte con la copia anterior
  del árbol público; los nueve tests del archivo pasan. En el entorno reiniciado `uv`
  no estaba en el PATH, por lo que se usó `~/.local/bin/uv run pytest
  tests/test_client_management_manual_check.py -q`. Su borrado no fue una decisión
  de alcance. La prueba conductual de traspaso al ejecutor sigue pendiente.
- El perfil existente señala la preparación del catálogo como parte significativa
  del coste, pero no separa sus fases ni justifica todavía otra optimización segura.
  C168 no demuestra el objetivo de 900 s; los tests sintéticos de optimizaciones ya
  implementadas no son una medición física de ese objetivo. Las muestras anteriores
  son bytes autenticados, no una medición de lectura física USB ni un límite inferior
  de tiempo. No repetir la prueba cerrada: si otra revisión es necesaria, medir sus
  fases y tiempo total sin añadir lecturas USB o perfilado a ese ensayo.
  Una inspección posterior distingue la revisión de otras rutas: C167 seguía en
  catálogo a los 890,6 s y no había empezado la autenticación USB; los 39,750 s
  atribuidos a consultas individuales en `docs/download-resume-baseline-review.md`
  corresponden a reanudación, no a esta revisión. El microbenchmark Python/SQLite
  allí citado tampoco mide Kotlin ni el dispositivo. Para una futura fixture segura
  y separada, medir tiempo total monotónico y por fases de catálogo, autenticación
  de la etapa y preparación del resumen; no debilitar verificaciones ni presentar
  mediciones locales como prueba de la meta física. No hay aún datos para elegir
  otra optimización de revisión con fundamento.
- La auditoría inicial de publicación encontró que C168 es una APK de prueba con
  firma debug, no una candidata de publicación. C146 ya se publicó como prerelease
  debug-signed y el usuario confirmó el 25-10 la redistribución de los componentes
  propietarios identificados en los avisos heredados; esa confirmación no equivale
  a una auditoría legal ni cubre automáticamente nuevos componentes. Hay que ligar
  el inventario, derechos, avisos y alcance de fuentes a la candidata exacta, sin
  llamar completas a fuentes parciales. Como orientación, la comparación SHA-256
  de los 178 archivos `lib/` y `assets/` de C146 y C168 encontró los mismos nombres
  y 177 contenidos idénticos; solo cambió `provenance.json`. Los certificados de
  firma auditados coinciden. Eso no sustituye la auditoría de la candidata final.
  Una comparación posterior del borrador público respecto a `342ee766` encontró
  entonces 19 archivos cambiados y ningún archivo de librería, asset, licencia o Gradle
  directamente modificado. El diff del reconstruidor solo cambia los tres hashes
  postimage y su comentario; los imports Kotlin añadidos son de Java y, en tests,
  JUnit/Mockito. Esto no verifica los inputs externos ni el contenido de una APK.
  Los 13 archivos Kotlin modificados de `updater-gate` y los dos tests Python del
  borrador público coinciden byte a byte con los del árbol local; el overlay se
  portó selectivamente y no se pretende que coincida con el local completo.
  Faltan asimismo la revisión final de privacidad
  y la integración pública de la candidata. El 30-10 la consulta remota de solo
  lectura confirmó que `main` público coincide con `origin/main` local en
  `342ee76605e03091de2131f1b5dad477536a9973`; no se publicó ningún cambio.
  No se ha establecido una secuencia atómica de commits segura para trasladar toda
  la funcionalidad: el árbol local incluye 31 cambios de archivos bajo el prototipo
  funcional desde `origin/main`, incluidos componentes ajenos al flujo USB. Se ha
  iniciado una integración aislada y selectiva desde ese árbol público. La lectura
  a las 19:02 UTC encontró 18 archivos modificados sin preparar en ese árbol;
  todavía hay que cerrar dependencias, incorporar la candidata final validada,
  revisar, reconstruir y auditar sin trasladar automáticamente los commits locales.
  Los tres postimages fijados de CASC/BLTE coinciden con los archivos de ese árbol
  provisional; hay que volver a fijarlos cuando se congele la candidata. La prueba
  local de traspaso por el servicio foreground se portó allí byte a byte (SHA-256
  `913875fe926a55a3613f4d967c4b7082664a1e68d6ddb0a0b0c1038d35c4e467`). Las 11
  pruebas del informe anterior no incluyen las dos pruebas de despacho añadidas
  después; aún deben ejecutarse antes de dar validez a ese traspaso. También queda
  pendiente la prueba del servicio al ejecutor.
  El overlay de actividad del árbol local también contiene código de addons ajeno a
  esta tarea que no está en el `main` público; su diferencia completa es intencional.
  El cambio de esta tarea (`initialMissingStorageRoute`) ya está aplicado por separado
  en el árbol público. No sobrescribir el overlay público entero con el archivo local.
  Una auditoría independiente preliminar de privacidad de los 18 archivos no
  encontró credenciales, identificadores de dispositivo ni binarios añadidos, pero
  señaló historial operativo innecesario en US-009. En el árbol público provisional
  se sustituyó por estado neutral sin alterar requisitos y se eliminaron dos enlaces
  a documentos privados no portados; los enlaces de US-008 y US-009 ahora resuelven
  localmente. Quedan sin auditar los cambios posteriores, el APK final, las licencias,
  avisos y firma; repetir solo la revisión incremental sobre la candidata congelada.
  Para preservar el trabajo provisional ante la pérdida de `/tmp`, se guardó fuera
  del repositorio el diff público y copia de los tests en
  `/home/adrian/wowforever-artifacts/work/usb-public-port-checkpoint-20261030-bYmYQW/`.
  SHA-256 de `public-draft.patch`:
  `0919453af04c9d5af5f1b07bfe7d7eb9c84df7d0ce91ba7f87c88ddbbe2a6c50`;
  de `manual-tests.py`:
  `50280faff952da8dd90e35b72756788f01b1d5f52516a1b0dac1ce4a57430a90`.
  Tras portar el test foreground se guardó también `public-draft-with-foreground.patch`,
  SHA-256 `c3e64a033fa96b3bd17d68a30f5d278012ac10da4d1d4edfe49c4bf315a45108`.
  Tras corregir la contradicción de US-009 se guardó `public-draft-with-spec-status.patch`,
  SHA-256 `9e2a938579e5170e6e5762092356ae97f32bd47be1dd1ae7b743d5f85e2e03d8`.
  Tras sustituir los dos tests foreground fallidos se guardó
  `public-draft-private-dispatch-tests.patch`, SHA-256
  `b22dcdd53cad870e01e812b6d74a2666d06c5edabe70a4413fabc64072c012fe`.
  En el mismo directorio hay un `release-notes-draft.md` en inglés con marcadores
  sin resolver: es un borrador no publicable que debe ajustarse a la candidata,
  a su firma, a la auditoría y a la evidencia física efectiva. El borrador identifica
  ahora las fuentes previstas como parciales y remite al manifiesto de cobertura;
  esa advertencia no acredita todavía ningún archivo fuente de C169.
  Tras ajustar provisionalmente versión y dos tests, el diff público de 20 archivos
  se guardó como `public-draft-c169-provisional.patch`, SHA-256
  `666621cc92834220aa109869d32615b77c7e58a7c19f9694e4c7847f6f36fe0c`.
  Es un checkpoint anterior a la validación final, no un artefacto de publicación;
  verificar su base y los cambios posteriores antes de recuperarlo.
  Los reinicios dejaron dos invocaciones Gradle idénticas compilando el mismo test
  en el mismo directorio temporal y agotando la memoria; se detuvo únicamente la
  duplicada. No usar sus resultados concurrentes como evidencia ni iniciar otra
  compilación en ese directorio mientras quede activa la primera.
  El borrador público se ajustó provisionalmente a `CANDIDATE_VERSION = (169,
  "0.2.145-usb-resume-preview")`; los dos tests focalizados de versión y metadatos
  pasaron. Una consulta remota de solo lectura no encontró etiquetas `c169-*` ni
  `c170-*`; esto no reserva versiones y hay que comprobarlo al publicar. Siguen
  pendientes firma compatible y alineación de etiqueta, APK y adjuntos; todavía no
  se ha compilado ni publicado esta candidata.
  La revisión de la receta encontró disponibles los inputs externos principales,
  pero no una receta de entorno de compilación completa en el reconstruidor. Una
  comparación de solo lectura comprobó que el certificado de la entrada
  `PrivateKeyEntry` debug local coincide con el firmante de la C146 publicada, que
  ya se había cotejado con C168. No se ha probado aún la firma de una candidata con
  esa clave ni auditado sus bytes; esto no autoriza por sí solo su redistribución.
  Se encargó en paralelo una inspección de solo lectura de los adjuntos de C146 y
  del procedimiento de auditoría de componentes/fuentes/avisos para la futura
  candidata (`ses_e71a07d8dffeym6S5UjHA80tZM`), sin acceder al dispositivo ni
  compilar. Concluyó que hay que inventariar y comparar **todos** los miembros del
  APK definitivo contra C146, explicar cambios, altas y bajas, y revisar por separado
  firma, manifiesto/permisos, privacidad y derechos. `audit_functional_ui_apk.py` es
  una referencia, pero sus identificadores/pins históricos no constituyen una
  auditoría C169. Los adjuntos C146 incluyen `SOURCE-COVERAGE.json` dentro de su
  archivo de fuentes; el README reconoce fuentes parciales y los notices no son
  exhaustivos. No afirmar fuentes completas ni autorización para nuevos componentes.
  Conservar APK, commit y recibo exactos, auditar los adjuntos y verificar metadatos
  y hashes de cada descarga pública. Nada de esto sustituye la auditoría del APK
  definitivo, aún inexistente.
  Como referencia operativa, el script histórico externo
  `public-performance-port-20261025/build-commit-group-145.sh` reconstruye un árbol
  aislado, usa JDK 17/SDK 36 y `:app:assembleModernDebug` offline con un worker,
  luego verifica firma y ejecuta `audit_functional_ui_apk.py` contra el receipt.
  Para C169 habría que adaptar las rutas al árbol público **ya congelado**, registrar
  su commit y generar un receipt nuevo; no ejecutar ni reutilizar el artefacto C145.
- Una inspección independiente del borrador público confirmó que la reconstrucción
  incluye la fuente y el test foreground y no encontró fuentes públicas ausentes;
  no se ha compilado ese árbol. Su reconstrucción depende de herramientas y artefactos
  externos al repositorio. La prueba que captura el traspaso del servicio al motor
  no se añadió: montar el servicio real exige lease, cualificación, runtime y
  verificación posterior y no existe un seam de test; añadir uno sería una
  modificación de producción solo para la prueba. La llamada se inspeccionó de forma
  independiente, pero esa inspección no es cobertura conductual.
- La siguiente validación, si está en curso, ejecuta **solo** los dos métodos
  `onStartCommandDispatches*` en la reconstrucción ya compilada
  `/home/adrian/wowforever-artifacts/work/forever-kotlin-verification-20261030-172733`,
  con fuentes de producción idénticas y test actualizado byte a byte. El classpath
  usa Mockito 5.14.2. Está limitada a 900 s y guarda su log y resultado fuera del
  repositorio: no iniciar otra instancia al reiniciarse el servidor. No toca Xaga.
  El primer intento no ejecutó Gradle porque `gradlew` no tenía permiso de ejecución
  (estado 126); se corrigió invocándolo con `sh`. La ejecución corregida se lanzó en
  segundo plano con identificador `sh_18e46cbfb001tv8s9epeSo6KRw`; deja el resultado
  atómico y el log en el directorio externo
  `usb-public-port-checkpoint-20261030-bYmYQW/foreground-dispatch-run-20261030-sh/`.
  El servidor comunicó «cancelled» al reiniciar, pero la comprobación puntual mostró
  que el proceso wrapper, Gradle y el worker de test seguían activos; la ejecución
  ya había empezado un método. No lanzar duplicados; el wrapper tiene timeout de
  900 s y escribirá `result.txt` al terminar. Se conservó el XML anterior de 11 tests.
- Esa ejecución terminó con 2 fallos de los 2 nuevos tests: el `mockConstruction`
  usado era local al hilo, pero el constructor real se ejecuta en `Dispatchers.IO`.
  Se conservó su XML junto a `foreground-dispatch-run-20261030-sh/result.txt` y se
  sustituyeron los dos tests por pruebas de los dos caminos del despacho síncrono
  privado con un mock normal. Verifican plan, consentimiento y la identidad de la
  autorización, pero no cubren toda la corrutina `onStartCommand` → `run`. El archivo
  de test local y el público coinciden, SHA-256
  `4e48c37c94b66cdd0199ddd2b214f06c8633561d0f41666ccb057f13ddbad1ba`.
  La validación exclusiva de esas dos pruebas terminó correctamente: 2 tests,
  0 fallos, 0 errores y 0 omitidos. El resultado y XML se conservaron en
  `usb-public-port-checkpoint-20261030-bYmYQW/foreground-private-dispatch-run-20261030/`;
  SHA-256 del XML `382e8d2e754b922504a470ec6639b9039f5c4d32347ebc4a5c644978c3da0ba5`.
  El reinicio del servidor informó «cancelled», pero el wrapper sobrevivió y generó
  el resultado. No repetir esa ejecución ni inferir cobertura end-to-end.
- Se corrigió una contradicción documental: los TC de portabilidad ya estaban
  aprobados; lo pendiente es la implementación verificada. Una comprobación focal
  del borrador público pasó frontmatter, unicidad de IDs, referencias FR/TC y enlaces
  relativos de US-008 y US-009. Esto no ejecuta pruebas funcionales.
  La CLI `gh` no está disponible en el PATH. No se encontró credencial
  HTTPS no interactiva, helper Git configurado ni identidad SSH habitual; aún no
  se ha demostrado acceso de escritura a GitHub. No solicitar ni incluir tokens
  en el repositorio.
- La optimización concreta y su ganancia se decidirán con el perfil y las pruebas;
  no se promete que el límite de 900 s se logre antes de medirlo.
- La revisión actual está en memoria: instalar otra APK antes de adoptar puede
  perder el resumen y obligar a repetir trabajo. La adopción con su consentimiento
  específico evita descartarlo innecesariamente; no demuestra por sí sola que la
  posterior descarga o publicación del cliente haya terminado.
- La publicación depende de que el acceso GitHub, el árbol público y los derechos
  de redistribución de la candidata pasen las comprobaciones. No usar un proyecto
  Android de diagnóstico como si fuera la app funcional.

### Corte 2026-10-31 (tarde): autoridad de prefijo y presupuesto durable

- Implementada, sin commit, la autoridad durable del prefijo ajeno dentro del
  checkpoint privado: trama tipo 3 (`FWFCP001`) con longitud, CRC por trama,
  relleno cero y binding de identidad. `prepareForeignPrefixCheckpoint` escribe
  cabecera + registro + owner sin leer cuerpos; `readForeignPrefixRecord` la
  valida. `loadResumableCheckpoint` y `inspectResumableCheckpoint` omiten la
  trama, permiten los IDs de segmento originales y nunca los truncan ni borran;
  el sufijo propio empieza en `prefixMaxId + 1`. `writeTargetIncrementalResumable`
  exige fuente nula, siembra `CandidateHintStore` desde el prefijo y verifica cada
  candidata con `ForeignCandidateVerifier` al usarla, sin copia temporal; solo
  `DECODED`/`ENCRYPTED` se reutilizan, por lo que `ENCRYPTED_SIZE_UNAVAILABLE`
  cae a descarga según la excepción `E`.
- El presupuesto durable `ForeverClientPayloadBudget` queda conectado a
  `executeWithPortabilityAuthorization(..., payloadCeiling)` y al servicio; el
  ledger vive en `<workspace>/payload-budget/payload-budget.v1`, nunca devuelve
  reservas y falla cerrado ante binding/techo distintos. Los fetchers inyectados
  en pruebas siguen siendo autoritativos. `validatePrivateWorkspaceContents`
  admite ese directorio y solo ese registro.
- Evidencia: `:app:compileModernDebugUnitTestKotlin` terminó `BUILD SUCCESSFUL`
  en 1 m 37 s. La suite focalizada de ocho clases dio 224 tests, 3 fallos y
  1 omitido. El reintento de `ForeverCascTest` + `ForeverClientUpdaterTest`
  (`/tmp/opencode/retest-two-20261031.log`) dio 154 tests, 1 fallo y 1 omitido:
  solo `overlapThirtyFourCheckpointRawsResumeWithoutDeadlock` (esperaba 41
  reutilizados, obtuvo 40). El mismo defecto ya aparece en la base reconstruida
  anterior `/tmp/opencode/usb-portability-head-baseline`; no se ha corregido aquí.
  El test de adopción parcial confirmada y el de cierre del replay pasaron.
- Sigue pendiente la integración de producto: nada en producción llama aún a
  `inspectForeignStageCandidates` ni a `prepareForeignPrefixCheckpoint`; la
  revisión/adopción continúa usando el escaneo completo de cuerpos. Faltan la
  prioridad de descarga sin pista, la interfaz del máximo consentido, el reparto
  de espacio y la prueba end-to-end. Los pins SHA-256 de `ForeverCasc.kt`,
  `ForeverCascTest.kt` y `ForeverBlte.kt` en `reconstruct_functional_source.py`
  siguen obsoletos y no se actualizan hasta revisar los postimages completos.
  No se ha instalado, reiniciado ni modificado el dispositivo, y no se ha
  publicado nada. US-008 y US-009 siguen `dirty`.

### Corte 2026-10-31 (noche): contrato de integración de la revisión incremental

Resuelto por inspección del código actual; sustituye los bloqueantes anteriores de
«contrato de autoridad del prefijo» y «coste de la revisión». Ningún intento previo
queda aprobado por esto.

1. La ruta de portabilidad deja de autenticar cuerpos en revisión y en adopción.
   Revisión: `inspectForeignStageCandidates` (solo cabeceras) gobernada por el lease
   real y el marcador, igual que hoy. Adopción: re-enumerar con `expectedPrefix`
   bajo el mismo lease y publicar `prepareForeignPrefixCheckpoint`. Se elimina la
   segunda pasada `prepareForeignStageCheckpoint` **solo** de esta ruta; esa función
   y sus llamadas ordinarias no cambian.
2. Pertenencia catálogo↔candidata: `ForeignStageCandidateLocation` expone el ordinal
   estable resuelto por `targetMembership` dentro del escaneo. Fuera del escaneo no
   se re-resuelve por EKey ni se inventan ordinales.
3. Índice durable de ordinales candidatos: bitset de `ceil(objectCount/8)` bytes
   (máximo 250 KB para 2.000.000 ordinales), archivo propio en el directorio privado
   del checkpoint, con cabecera mágica, identidad, digest de catálogo, `objectCount`
   y CRC. Lo escribe solo `prepareForeignPrefixCheckpoint` tras su barrera
   `completed`; es estado poseído, se valida al leer y no acredita cuerpos. Su nombre
   entra en la lista de entradas permitidas del workspace privado.
4. `ForeignStageCandidateScan` no gana el bitset: `ByteArray` rompería la igualdad del
   data class y del registro. El bitset viaja como parámetro/resultado separado.
5. Semántica de costes: `downloadBytes` = objetos del catálogo **sin** candidata +
   loose (mínimo estimado). `payloadCeilingBytes` = encoded de **todos** los objetos
   no poseídos (con y sin candidata) + loose: máximo si toda candidata resulta
   inválida. Espacio (`private/destination/peak`) y `hasEnoughSpace` se calculan con
   el techo. `totalsAreUpperBounds = true`; la UI rotula «hasta» y no presenta el
   techo como medición. El techo es límite duro de bytes de cuerpo nuevos: el
   presupuesto falla cerrado al alcanzarlo; no promete cubrir reintentos ilimitados.
6. `PortabilityReview` añade `payloadCeilingBytes` y `totalsAreUpperBounds`; el
   `digest` liga `sourceInventoryBinding`, `candidateCount` y el techo (US-008
   TC-010) además de lo actual. `PartialPortabilityVerification` sustituye
   `objects: ForeignStageVerification` por `prefix: ForeignStageCandidateScan` y el
   plan de catálogo por ordinales; comprobar antes si otra ruta lo consume.
7. `buildPlan` lee el bitset solo cuando el receipt/identidad del checkpoint adoptado
   coincide y cuenta esos ordinales como reutilizables **no verificados**: mínimo y
   techo según (5). Ninguna otra ruta lee el archivo. El plan expone ambos valores.
8. `executeInternal`, `checkConsent`, `requireSamePlan`, la autorización de
   continuación y el presupuesto durable usan el techo para espacio y límite; el
   mínimo solo se muestra. `payloadCeiling` = `review.payloadCeilingBytes`.
9. Prefetch: solo claves sin candidata (ordinal no marcado). Si este corte no lo
   implementa, queda como pendiente explícito y no se afirma el ahorro.
10. Prohibido en este corte: cambiar el comportamiento de rutas ordinarias, tocar
    los pins SHA-256, `voice-chat-plan.md`, instalar, reiniciar el móvil o publicar.

Aceptación: `:app:compileModernDebugUnitTestKotlin` correcto; `ForeverCascTest` y
`ForeverClientUpdaterTest` sin fallos nuevos (el fallo preexistente
`overlapThirtyFourCheckpointRawsResumeWithoutDeadlock` se conserva como tal); pruebas
nuevas del bitset (round-trip, límite, corrupción, identidad), de revisión sin
verificación de cuerpos, de adopción con registro de prefijo, de plan mínimo/techo y
de rechazo de bitset alterado; ninguna llamada a `prepareForeignStageCheckpoint` en
la ruta de portabilidad.

### Corte 2026-10-31 (noche, cont.): integración implementada sin verificar

El árbol de trabajo ya contiene la integración del contrato anterior (sesión previa
interrumpida por reinicios, sin informe final). Estado inspeccionado:

- `ForeverCasc.kt`: `ForeignStageCandidateLocation` lleva ordinal; bitset durable
  `.forever-casc-candidate-ordinals` (magia `FWFCORD1`, identidad, digest de catálogo,
  `objectCount`, CRC, tope 250 KB); `prepareForeignPrefixCheckpoint(..., objectCount,
  ordinals)` exige cardinalidad = `candidateCount`; `planCandidateTarget` empaqueta el
  catálogo con candidatas como reutilizadas-no-verificadas; `readAdoptedForeignCandidates`
  falla cerrado si el archivo existe y no valida.
- `ForeverClientUpdater.kt`: `verifyPartialPortabilitySource` es ahora enumeración de
  cabeceras + `planCandidateTarget` + `portabilityReview` (sin cuerpos);
  `PartialPortabilityVerification(stageId, markerDigest, prefix, candidateOrdinals,
  targetPlan, review)`; `confirmAndAdoptPartialPortability` reenumera con
  `expectedPrefix`, exige prefijo y bitset idénticos, recalcula el plan y el digest, y
  publica `prepareForeignPrefixCheckpoint` en lugar de la pasada de cuerpos. El plan
  gana `payloadCeilingBytes` y `candidateObjects`; `buildPlan` lee el bitset solo con
  receipt de portabilidad (`EMPTY_SOURCE_REVISION`) y calcula mínimo y techo.
- `ForeverClientUpdateService`/`Management`: la autorización y el consentimiento
  efectivo usan el techo; la pantalla de revisión dice «hasta».
- Correcciones propias de esta sesión para completar el refactor detenido:
  declaraciones `candidateObjects`/`candidateBytes` izadas al ámbito de `buildPlan`
  (estaban dentro de la rama `archiveOrder`), `reviewed.objects` → `reviewed.prefix`,
  lambda `prefixProof` con tipo explícito, consentimiento del servicio en primer plano
  y de `resumeDownload` con `payloadCeilingBytes`, y texto «hasta» en el plan cuando
  el techo supera el mínimo.
- Pendiente inmediato: compilar `:app:compileModernDebugUnitTestKotlin` y ejecutar
  `ForeverCascTest`/`ForeverClientUpdaterTest`. Tres clientes Gradle quedaron
  contendiendo en el árbol aislado por los reinicios; hay que dejar uno solo y
  confirmar el resultado antes de acreditar nada.
- Riesgo abierto de contabilidad: `portabilityReview` calcula el espacio con el plan
  mínimo (segmentos solo para objetos sin candidata) mientras el techo puede requerir
  segmentos nuevos para candidatas inválidas. Si se aplica el techo al espacio, el
  caso C168 probablemente no cabe; si no, una candidata inválida puede agotar espacio
  a mitad. Decidir y documentar antes de instalar; no presentar el espacio como
  garantía del techo.
- Sigue sin implementarse el prefetch solo sin candidata (contrato punto 9). Los pins
  SHA-256 de `reconstruct_functional_source.py` siguen obsoletos. No se ha instalado,
  reiniciado ni publicado nada.

### Corte 2026-10-31 (noche, cierre de verificación local)

Verificado en el árbol aislado `:app:compileModernDebugUnitTestKotlin` (verde) y
`:app:testModernDebugUnitTest --tests ForeverCascTest --tests ForeverClientUpdaterTest`:
157 pruebas, 1 fallo (`overlapThirtyFourCheckpointRawsResumeWithoutDeadlock`, ya
fallaba antes de este corte), 1 omitida. Fallos intermedios corregidos en esta sesión:

- `buildPlan` usaba `candidateObjects`/`candidateBytes` fuera de su ámbito: izados.
- `verifyPartialPortabilitySource`/adopción: `reviewed.objects` → `reviewed.prefix`.
- Lambda `prefixProof` en `planCandidateTarget`: tipo explícito para evitar que el
  compilador la leyera como lambda de `requireNotNull`.
- La recuperación durable de una adopción con checkpoint de solo prefijo ya no
  reescribe cuerpos: lee el prefijo del checkpoint y reenumera cabeceras con
  `inspectForeignStageCandidates`; conserva la rama antigua de escaneo de cuerpos
  para checkpoints legados. El hook de prueba pasó a `recovery-candidate-located`.
- El pipeline paralelo ya no prefetchea candidatas: el productor emite la espec
  sin fuente y el escritor autentica el cuerpo existente antes de reutilizarlo.
  Si la prueba falla, `sourceFor` no tiene prefetch y la operación falla cerrada
  (no hay re-descarga silenciosa). El techo de payload sigue reservado en el
  presupuesto durable y el consentimiento.
- `prepareForeignPrefixCheckpoint`/bitset cubiertos por tres pruebas nuevas:
  códec con identidad/límite/CRC/bit fuera de rango, checkpoint que publica el
  bitset y rechaza cardinalidad, y plan que cuenta reutilizadas-no-verificadas
  frente a pendientes.
- Consentimiento del servicio en primer plano y de `resumeDownload` usan
  `payloadCeilingBytes`; la pantalla de revisión dice «hasta» para la descarga y
  «previsto» para el espacio, y ya no afirma que los candidatos estén verificados.

Riesgos/gaps que permanecen y no deben presentarse como resueltos: el espacio se
calcula con el plan mínimo (una candidata inválida puede agotar espacio a mitad y
fallar cerrado); el prefetch solo para claves sin candidata sigue diferido; los pins
SHA-256 del reconstructor no se actualizaron; no se instaló, reinició ni publicó nada.

## Checkpoint 2026-11-02

- La verificación de candidatas del prefijo extranjero revalida ruta/atributos y el
  vínculo del segmento por cambio de segmento y cada 256 objetos, no por objeto; el
  cuerpo conserva EKey/BLTE/CKey y el descriptor abierto sigue rigiendo cada
  lectura. La detección de una sustitución dentro del lote puede retrasarse hasta
  256 objetos.
- `trustForeignHeadersWithoutBody` se añadió solo como diagnóstico para medir la
  descarga pendiente tras horas de re-verificación de cuerpos en USB. Está fijado en
  `ForeverClientUpdater`, marcado «remove before release», se registra y contradice
  US-008-FR-003. No es comportamiento aprobado ni puede publicarse.
- El usuario informa que ese candidato funciona en el dispositivo; no hay tasa de
  bytes de red ni instalación completa medidas.
- Los pins del reconstructor y del nativo candidato se re-anclan a las fuentes
  actuales y al build `functional-capture-build.wIYy6T` en este mismo cambio.
