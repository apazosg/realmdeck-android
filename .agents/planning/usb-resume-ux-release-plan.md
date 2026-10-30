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
- [ ] 2. Verificar la finalización de la adopción. En C168, confirmar una vez el
  plan actualizado únicamente si coincide con el destino y los costes autorizados;
  comprobar después si la descarga realmente ha comenzado.
- [ ] 3. Analizar el recorrido real y el perfil de costes; realizar los cambios
  mínimos de interfaz y rendimiento dentro de los criterios aprobados, con pruebas
  focalizadas de mensajes, navegación y seguridad de la recuperación.
- [ ] 4. Compilar y auditar la candidata exacta, pedir revisión independiente de
  las partes sensibles y probar de manera proporcionada. No interrumpir una descarga
  activa para instalar una APK; actualizar sin desinstalar ni borrar datos.
- [ ] 5. Medir en condiciones documentadas y reflejar si el objetivo de 900 s queda
  demostrado, no demostrado o incumplido; no alterar el parcial para la medición.
- [ ] 6. Preparar la integración pública y la prerelease GitHub, con notas en inglés,
  assets auditados y sumas; publicar cuando pasen las barreras de privacidad,
  redistribución y verificación y comprobar metadatos y hashes publicados.

## Open Issues

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
