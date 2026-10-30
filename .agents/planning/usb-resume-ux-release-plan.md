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
- La inspección de código sugiere que «Pausar» durante la adopción cancela de
  forma cooperativa, no reanuda desde el checkpoint preparado parcial. Antes del
  intent durable, la limpieza normal intenta retirar los artefactos preparados;
  una salida abrupta puede dejar huérfanos que bloquean el siguiente intento. La
  revisión también reside en memoria. No se ha probado en el dispositivo activo
  una pausa, limpieza o reanudación de esta adopción. No interrumpirla basándose
  solo en la mejora todavía no medida de la candidata.
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
  diff respecto a HEAD. Las pruebas Gradle de clasificación iniciadas antes del
  reinicio quedaron sin resultado confirmado.
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
  Una revisión independiente identificó una posible carrera si la recuperación
  trunca el checkpoint durante la lectura, pero no probó que explique este test,
  cuyo checkpoint inicial estaba alineado. Las 11 pruebas focalizadas del
  servicio pasaron, incluida la entrega y limpieza de la autorización pendiente;
  queda por probar que la misma autorización llega al ejecutor. No dar la
  candidata por validada mientras no se resuelvan esas comprobaciones.
- El perfil existente señala la preparación del catálogo como parte significativa
  del coste, pero no separa sus fases ni justifica todavía otra optimización segura.
  C168 no demuestra el objetivo de 900 s; los tests sintéticos de optimizaciones ya
  implementadas no son una medición física de ese objetivo.
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
  Faltan asimismo la revisión de privacidad
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
  local nueva de traspaso por el servicio foreground todavía no está portada allí.
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
