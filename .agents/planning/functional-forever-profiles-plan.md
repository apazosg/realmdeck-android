---
id: functional-forever-profiles-2026-10-12
state: in-progress
date: 2026-10-12
---

## Summary

El usuario pide continuar con el parche de memoria para Classic Forever y hacerlo
opcional/configurable, pensando también en un servidor público futuro. La PoC63 ya
muestra el login real de WoW ARM64 1.60.1.70009 limpio sobre Mali-G610; su ruta
unmanaged no ejecuta el patcher. Este paso añade perfiles de conexión y parche
opcional sin convertir supervisor/admisiones de producto en prerrequisitos.

Referencia funcional de escritorio: `/home/adrian/dev/wowserver`, cuyo README
documenta detección del almacén real, modificación del grupo8 e idempotencia. Se
consulta solo código/documentación; no se operan servicios, sesiones ni secretos
del PC. No se presupone que sus permisos/topología Linux se reproduzcan en Android.

## Content

### Baseline que se conserva

- Cliente `WowB-ARM64.exe`, build70009, runtime Proton11 ARM64EC/DXVK/PanVK/noGL,
  prefijo GUI conocido y copia original `WTF/BetaSuspendedTest.wtf`.
- Perfil gráfico funcional: wrapper BC `none`, compute desactivado (`software`),
  recurso `ahb`, cache0; PanVK mantiene su emulación BC. Capturas limpias repetidas.
- APK funcional actual retenido `be7931f1…`, SHA completo
  `be7931f12f8954a573790f5f6ebbc21c048e62c45bf4d07261d4b74a812f0dc3`.
  Añade `showPhotosensitivityWarning=0`; la última prueba no registró una nueva
  aserción de voz en los logs recogidos. No acredita estabilidad prolongada.
- CVars diagnósticos BGDL0/narración0/volumen0.03 y volumen Android1/15.
- Los UNKNOWN/ACTIVE históricos de los Stores anteriores no se rehabilitan,
  modifican ni se utilizan para autorizar esta sesión funcional.

### Perfiles, no etiquetas «privado/público»

Separar los datos de conexión de la política de parche:

| Perfil inicial | Portal | Parche de memoria |
|---|---|---|
| Cliente sin parche | Portal original de la configuración base | Desactivado; cero lecturas/escrituras de memoria del juego |
| Classic Forever | `auth.gpon.com.co`, desde el contrato versionado | Opcional, activado explícitamente para este perfil y contrato Forever conocido |

Una etiqueta público/privado no determina si se necesita la clave. El servidor
público de una comunidad puede usar el mismo parche que su despliegue privado.
No presentar el perfil sin parche como soporte confirmado de Battle.net/Blizzard.

Modelo mínimo de configuración: `profileId`, `portalHost`, `memoryPatchEnabled`,
`patchContractId` y build compatible. Preferencia persistente pequeña sin guardar
credenciales. Selección visible en la UI antes de preparar/lanzar; parche apagado
por defecto hasta la elección explícita del perfil Forever. Portal propio solo
como hostname validado, nunca argv/shell/URL libre ni material de claves suministrado
por Intent. Una nueva clave/estructura/build requiere otro contrato revisado.

No editar `forever/contract.json` para cambiar únicamente el destino de red:
el JNI existente exige el contrato canónico completo. Generar el portal elegido
en una configuración derivada del juego, manteniendo el contrato de parche
versionado separado de la selección del endpoint.

### Configuración y cambio de modo

- Generar `WTF/BetaFunctionalPoc-<profileId>.wtf` desde el original y aplicar solo
  portal y overrides PoC conocidos; no tocar `Config.wtf` ni el original.
- No registrar ni persistir contraseñas/cuentas en repo, APK, preferencias o informes.
  Excepción autorizada 2026-10-12: el usuario solicita probar una cuenta temporal
  de test. Se permite un único Login a `auth.gpon.com.co`, con entrada HID en el
  cliente propio, después de comprobar visualmente el login y el perfil. Los
  secretos se leen únicamente desde un fichero host efímero privado fuera del
  repo, se borran al terminar y no se incluyen en argv, journals ni capturas.
- Un perfil sin parche no inicia siquiera el trabajador de memoria.
- No intentar restaurar claves en un proceso vivo cuando se apaga el parche.
  Detener el trabajador y relanzar el cliente antes de cambiar perfil: la memoria
  nueva parte del estado original. Deshabilitar el selector mientras juega.
- Reutilizar la ruta GUI funcional; no habilitar globalmente `MEMORY_PATCHING_ENABLED`
  ni revivir `ForeverLaunchController`/admisiones de producto como atajo.

### Integración mínima y prueba real antes de escribir

Sesión `ForeverFunctionalPocPatchSession` pequeña, cancelable y ligada al único
arranque propio de la PoC; nonce fresco y PID/UID/startTicks reales. Puede reutilizar
el núcleo Rust y el puente JNI, pero no una simple constante global: hoy la PoC
carece de los objetos managed que exige `patchOwned()`.

Primera puerta física: lectura únicamente sobre el cliente ARM64 lanzado por la
app. Registrar identidad del objetivo, permiso de lectura, clases de mappings,
cobertura y candidatos completos, sin volcar memoria genérica ni datos de cuenta.
Validar el almacén por la clave ancla y sus12 entradas de40 bytes. La arquitectura
del PE no confirma por sí sola la estructura del almacén.

Se conoce una limitación: JNI actual exige ascendencia al launcher y mapping PE
con ruta; Wine ha mostrado PPID1 e imágenes PE anónimas. Investigar el enlace
mínimo de la sesión real antes de añadir una API funcional separada. No modificar
la API managed para aceptar cualquier PID del UID, ni tratar nombre/nonce/timing
como prueba suficiente. No imponer una nueva campaña de seguridad de producto;
sí impedir que el patcher pueda escribir en otro proceso.

Si los permisos/topología impiden esa lectura, conservar un resultado específico
y resolver la estrategia de acceso sin root/ptrace/SELinux bypass. No prometer
un mecanismo aún no verificado ni habilitar escrituras por una heurística.

Segunda puerta: solo tras lectura real válida y objetivo propio, aplicar el
contrato Forever. No direcciones/bytes arbitrarios recibidos desde Kotlin/Intent:

- Bloque completo con IDs/flags/padding/claves del contrato US-002.
- Grupo1 ancla intacto; cambiar únicamente los32 bytes de la clave del grupo8.
- Releer el bloque completo antes/después, comprobar diff exacto y PID/startTicks.
- Reaplicación idempotente y mantenimiento acotado/cancelable si se recrea.
- Logs distintos para desactivado, buscando, no disponible, validado, aplicado,
  ya aplicado y error; un marcador UI no sustituye el read-back real.

La búsqueda puede no hallar todavía el almacén si solo se crea al iniciar una
conexión. `NOT_FOUND` no equivale a fallo ni permite inventar direcciones. Esa
incertidumbre se comunica antes de requerir una interacción de login.

### Alcance y verificación

Código esperado: dos clases PoC existentes, perfil/sesión de parche pequeños,
puente JNI/núcleo Rust si hacen falta cambios acotados, receta PoC, runner y tests.
Consultar el supervisor/patcher de wowserver sin portar su despliegue Btrfs,
locks de producción, autologin o credenciales. Nuevo candidato code64 si libre,
nombre distinto y APKs fuera del repo; conservar todos los APK funcionales previos.

Tests: defaults persistencia, toggle apagado sin llamadas de memoria, portal
separado del contrato, originales intactos, cambio de perfil solo con relanzamiento;
contrato válido/inválido, lectura sin escritura, diff32 exacto/ancla intacta,
idempotencia/cancelación/objetivo cambiado/lecturas fallidas. Revisión independiente
del límite de escritura e identidad; build local offline/caps habituales,
compilación y tests Rust/JVM dirigidos, comparación de runtime gráfico sin cambios.

Control inicial en el POCO sin introducir credenciales: demostrar que modo
apagado conserva login limpio; modo Forever encuentra/aplica y confirma la clave
si el almacén ya existe. La conexión real al servidor y gameplay se consideran
puertas distintas, no se anuncian por mostrar el logo/login o parchear bytes.

## Implementation / Action Items

- [x] 1. Aprobado por el usuario: «perfecto hagámoslo. Avisame cuando esté».
  Perfiles desacoplados aprobados; el endpoint/contrato de un futuro servidor
  público queda pendiente sin bloquear los dos perfiles iniciales.
- [x] 2. Selección Original/Classic Forever, hostname validado, configuración derivada
  y sesión opcional implementadas en APK64; apagado por defecto. Original conserva
  el login limpio sin trabajador de memoria. Runtime gráfico sin cambios.
- [ ] 3. Validar acceso/identificación y almacén real ARM64 en lectura únicamente.
- [ ] 4. Integrar escritura acotada/read-back/mantenimiento solo si la puerta de lectura es positiva.
- [ ] 5. Tests dirigidos/revisión/build/retención y controles físicos de modo apagado/Forever, sin credenciales.
- [ ] 6. Documentar parche realmente aplicado o bloqueo concreto; separar conexión/autenticación/gameplay pendientes.

## Open Issues

- Continuación autorizada: «venga adelante a ver si lo resolvemos». Comparar en
  lectura exclusivamente los cuatro cuerpos del seguimiento dirigido de punteros:
  `0x37b1998/60`, `0x37b1af8/216`, `0x223c820/1000`, `0x223cc08/480` (1756 bytes).
  Los dos últimos son proximidad léxica, no consumidores de confianza demostrados.
  Sustituyen las diez ventanas anteriores; misma identidad/lease/fd y límites,
  métricas sin bytes/heap, ningún Login ni autorización de parche por coincidencia.
  Si coinciden, investigar entradas/desensamblado desde el PC; si cambian, registrar
  transformación localizada sin extrapolar al cliente completo ni a CertStore.
  Primer control `514f1e8b64`: 1756 bytes idénticos antes del LogOpen/D3D11; después
  no hubo cabecera PE reconocida. Se permite conservar como hipótesis diagnóstica
  el base previamente observado SOLO en esta lease/PID/startTicks/source estable,
  para comparar los mismos cuatro rangos privados en una lectura posterior.
  Cabecera no reconocida nunca implica autenticación del módulo ni COMPLETE:
  resultado etiquetado INCOMPLETE, referencia histórica explícita, cero bytes
  persistidos/escritos, ninguna ampliación del criterio de parche.
  Refinamiento ejecutado en `functional-poc-20261013T172634578847Z-87524ba7e8`:
  APK64 ac465cab/JNI b19c2ed0, cuarenta tests Rust positivos y revisión read-only;
  PID14333/start9312/UID4=10330. Antes de D3D11 los 1756 bytes eran iguales;
  a17:27:58.964UTC la hipótesis histórica leyó10 iguales/1746 distintos con
  identidad completa post-read, cero cabeceras actuales y global INCOMPLETE.
  Hubo aserción SmallMemAllocator a17:27:43.225 antes de la lectura tardía.
  Sin Login, bytes guardados/escritos ni módulo/CertStore/parche autenticados.
  Métricas/timing/limitaciones en docs/arm64-certstore-primitive-consumers.md;
  no inferir estabilidad ni metamorfosis criptográfica solo por el cambio.
  Continuación «el entendido pues nada sigamos.Me parece raro no haber localizado
  todavía nada»: comprobar también el instrumento. El modelo de relleno constante
  cero no reproduce los conteos tardíos: el archivo tiene8 ceros frente a10
  coincidencias observadas. La lectura parcial devuelve error, no relleno aceptado.
  Añadir solo características agregadas de los mismos cuatro buffers (ceros,
  diversidad y cobertura de permisos de maps), sin bytes ni nuevas regiones,
  para distinguir memoria vaciada/no ejecutable de transformación de código.
  Mantener los resultados de base histórico como INCOMPLETE/no autoridad de parche.
- Continuación PC autorizada: «venga pues adelante. vamos a buscarlo e intentar
  resolverlo». Investigación host-only2026-10-13 documentada en
  docs/arm64-certstore-pc-investigation.md, sin APK/ADB/Login/escrituras.
  Las dos tablas estáticas contienen punteros DIR64, incluido `+32..+39`; el
  supuesto flag `+36` no valida una clave. Se corrigió el cálculo ADR/ADRP de
  TrustReferenceFlow: la pista `0xe7b558→0x4bdf000` era falsa; Ghidra daba el destino
  correcto `0x15040b000`. Cinco tests, incluyendo el Java real, positivos; pasada
  dirigida corregida sin consumidor exacto. RSDS de ambos clientes identificados,
  sin PDB obtenido en las dos fuentes públicas consultadas. Imports observados
  incompletos, no ausencia de verificador. Continúa pendiente localizar el uso de
  confianza nativo; no se cambia contrato ni se prepara parche de bytes a ciegas.
- El usuario rechaza el A/B x64/FEX por priorizar ejecución nativa y porque el
  parche PC ya está demostrado. No implementar el borrador x64. Solicita revisar
  otros clientes ARM64 y evaluar un parche en disco, conservando el original.
  Revisión pública2026-10-13 en docs/arm64-private-server-public-references.md:
  PlayBeta70009 sigue siendo memoria; Longee/Arctium tiene instrucciones ARM64
  de certificados pero no prueba Beta70009; St0ny/Smerdokryl muestran escritura
  de PE en otros clientes. Smerdokryl usa un patrón correspondiente a grupo9,
  no la clave de grupo8 de nuestro Store. No autoriza copiar offsets/parches ni
  escribir el cliente local. Prioridad: identificar fuente/consumo de confianza
  nativos y su idoneidad para un parche estático específico del build.
  Contraste host-only de los dos patrones de instrucciones ARM64 de Longee:
  longitudes12/42, cero coincidencias en `.text` de nuestro PE70009; entradaSHA
  d0dd...1f59 estable. No repetir barridos de claves ni aplicar un parche ajeno.
- Contraste runtime ejecutado: revisión `8239e421`, control
  `functional-poc-20261013T051841697880Z-cbd071c70c`, PID14727/start8234/UID4=10330,
  diez ventanas con una cabecera estructuralmente coincidente y global INCOMPLETE
  por otra cabecera ilegible. Seis ventanas `.text` (7608 bytes) idénticas al archivo;
  cambios en tabla TLS y tres bloques `.data`/BSS, sin destinos `.text` propios
  recuperados. No constructor/verificador/Store identificado, no Login ni escritura.
  Detalles/límites en docs/arm64-certstore-ghidra-results.md. El arreglo PE BSS pasó
  diez tests; no sumar la ronda anterior31 como una única suite posterior.
  Evaluación estática del runtime encontró una vía x64/FEX plausible, no ejecutada;
  el usuario ha descartado priorizarla. El borrador separado
  `.agents/planning/functional-x64-mali-ab-plan.md` queda sin autorización de ejecución.
- Continuación autorizada: «vamos a ello. necesitamos poder parchearlo o encontrar
  una alternativa que cumpla el mismo objetivo». Comparar en el objetivo propio
  ventanas fijas de entrada/callbacks/datos PE conocidas por Ghidra con el archivo
  autenticado, en lectura únicamente. Resolver la imagen mediante sus cabeceras
  y mappings privados; no asumir que una dirección 0x140… identifica el módulo.
  Informar RVA/tamaño, igualdad/diferencias y destinos RVA internos de punteros,
  sin bytes de heap, claves ni direcciones virtuales externas. No se convierte
  esta correspondencia diagnóstica en autoridad de escritura. La evaluación
  histórica de x64/FEX no se ejecuta: el usuario mantiene la prioridad ARM64.
- Continuación «perfecto adelante!» ejecutada con Ghidra oficial12.1.4/Java21,
  instalado fuera del repo: importación y análisis acotados x64/ARM64, recuperación
  de límites `.pdata`, CFG/referencias y decompilación de entrada/callbacks TLS del
  PE. Bootstrap ARM64 con destinos indirectos y escrituras identificadas en
  `.data`, sin enlace a CertStore ni modificación demostrada de `.text`. x64
  entrada indirecta; callbacks sin límite resuelto. No constructor/verificador ni
  offset de parche válido. Resultado en docs/arm64-certstore-ghidra-results.md.
  No ADB/Login/lectura nueva de memoria/escritura/APK en esta fase. Próximo: probar
  correspondencia de ventanas específicas archivo↔ejecución, no otro barrido de
  claves ni un volcado genérico; en aquella fase aún no se había ejecutado. La
  continuación runtime posterior queda registrada arriba con resultado parcial.
- El usuario solicita buscar otros proyectos y comparar código de verificación
  criptográfica x64/ARM64, no solo las claves Ed25519 conocidas. Investigación
  pública en `docs/arm64-private-server-public-references.md`: cliente publicado
  AA64 y lógica de parcheo ARM64 para Classic Era42597; el launcher Forever70009
  sigue usando el supuesto12×40 sin demostración de autenticación ARM64.
  No se ejecutan binarios ajenos ni se trasplanta código sin licencia.
  Herramientas usadas hasta ahora: parsers/scripts y NDK llvm-objdump acotado,
  no Ghidra/radare2/Rizin ni CFG/decompilación completa. Priorizar reconstrucción
  del flujo de carga/decodificación/uso de claves en ambos clientes, incluyendo
  texto empaquetado solo si se demuestra para nuestro build. No ampliar contrato
  de escritura ni repetir Login hasta verificar el parche.
- Continuación PC autorizada «pues vamos allá. adelante»: dos análisis host-only
  nuevos separan referencias de datos de constantes estándar criptográficas,
  sin repetir las claves conocidas. SHA-512/SHA-256 completas y cinco ubicaciones
  Ed25519 matemáticas por arquitectura; sin consumidor del verificador probado.
  Grafo limitado a256 nodos/24 destinos: slots DIR64 hacia cuatro cuerpos ARM64
  revalidados en Ghidra, cobertura desde entrada 0/60,4/216,0/1000,0/480 bytes.
  No se llama inválido a cada opcode no listado ni se infiere packing global.
  Tests host8+6 en sus dos módulos; dos salidas Ghidra con corrección de etiquetas,
  originales preservados. `docs/arm64-certstore-primitive-consumers.md` describe
  las aristas, anclajes, límites y siguiente contraste runtime preciso pendiente.
  Sin ADB/Login/nuevas lecturas del teléfono/memoria escrita/APK/cliente modificado.
- Continuación autorizada «adelante» para separar material de claves de formato:
  añadir a los seis polls de solo lectura una hipótesis estructural explícita
  `contiguous-12x40-le-id`, buscando IDs 1..12 sin usar la clave ancla como requisito.
  Informar conteos de coincidencias de IDs, flags/padding y claves conocidas por
  grupo, con muestras numéricas acotadas; nunca direcciones, claves, heap ni datos
  de cuenta. La hipótesis de 40 bytes procede del contrato x64: un candidato no
  demuestra el formato ARM64, no cambia `VALIDATED` y no autoriza escritura.
  Mantener identidad, regiones, límites, ausencia de Login y baseline gráfico.
  Las claves públicas no varían por la CPU; diferencias de build o representación
  de memoria siguen abiertas y se contrastan, no se presuponen.
- Continuación estructural ejecutada: APK64 b54bc0e6 (SHA completo en resultados),
  `functional-poc-20261012T210439363752Z-bb4e380bee`, PID14622/start9322/UID4=10330.
  Secuencias IDs1..12 observadas en file-r--/anon-r--/file-rx; todas con cero claves
  conocidas y cero coincidencias completas de metadatos. Cuatro clases completas,
  anon-r--/anon-rw incompletas. Render Mali limpio, sin Login, bytesWritten=0.
  22 tests Rust, revisión read-only, build/retención positivos; solo JNI/marker cambian.
  Informes estáticos de los dos candidatos ARM64 y uno x64 sin enlace a CertStore:
  diferencia de campos/flags no autoriza otro contrato ni demuestra diferentes
  claves de confianza. Próximo: vincular estructura/campos con el uso real del almacén.
- El usuario aclara que las credenciales no pueden funcionar con Forever sin el
  parche y solicita explorar el parcheo. No repetir Login hasta que la clave esté
  aplicada y verificada. La PoC gráfica es un resultado positivo independiente,
  no una validación de cuenta ni de autenticación privada.
- Exploración posterior: `docs/arm64-certstore-patch-exploration.md`. Contrato PC
  validado x64, representación ARM64 pendiente; filtro actual solo anónimos RW.
   Reproducción sintética confirmó deduplicación prematura del candidato, sin
   atribuirle los cero hallazgos físicos. PE del teléfono y referencia coinciden
   por SHA. Corrección y diagnóstico de otras clases privadas implementados en la
   continuación; no amplía el contrato de escritura.
- Continuación autorizada «seguimos entonces?» tras la exploración: corregir la
  deduplicación prematura reproduciendo errores/transitorios en tests; añadir al
  lector funcional un diagnóstico separado de seis clases privadas legibles
  (`anon-rw`, `anon-r--`, `file-rw`, `file-r--`, `file-rx`, `anon-rx`), excluyendo
  shared y `/dev`. Rotar seis polls diagnósticos y una pasada canónica por sesión,
  manteniendo el límite existente 2GiB/20s por poll y las comprobaciones completas
  de identidad. Informar únicamente contadores por grupo/clase, alineamiento y
  cobertura, sin direcciones, nombres de archivos, claves ni bytes de memoria.
  Un resultado diagnóstico no autoriza `VALIDATED`, escritura ni ampliación de
  las regiones canónicas. Nueva prueba sin Login ni credenciales; runtime y
  baseline gráfico intactos.
- Continuación física completada sin Login:
  `functional-poc-20261012T203602111851Z-39f3a01712`, APK64 d4bae6cd/SHA
  d4bae6cdf35f60e1392e4d712a2f7b36c31e1931f044c8e120799335b0dac4e5.
  PID14423/start8744/UID4=10330/PPID8561, render limpio conservado. Cinco clases
  con pasadas completas y cero claves conocidas; anon-r-- incompleta/un error,
  también cero claves en lo leído. Diagnóstico sin alineación también negativo.
  Escrituras cero; almacén ARM64 todavía sin validar. 16 tests funcionales Rust,
  5 regresiones de solape y 8 core positivos; build/audit/retención y revisión
  read-only positivos. Detalles en docs/arm64-certstore-patch-exploration.md.

- Prueba autorizada ejecutada: `functional-poc-20261012T183141223033Z-f6496fc097`,
  APK64 a0d6e1ecfa776c892183c439b60e6e86deeddcd08e28f3074958cf291f4cc3f0.
  Un único Enter de Login a las 18:33:52.897 UTC. Actividad TLS/certificados
  posterior observada, sin handshake remoto ni resultado de autenticación
  demostrado; no se concluye fallo de credenciales. No hubo reintento.
  Después del envío, dos pasadas completas sobre las regiones privadas anónimas
  RW seleccionadas leyeron 2021979136 bytes cada una, con cero anclas/almacenes.
  No es una prueba de ausencia en toda la memoria. Escrituras cero.
  Resultado y límites en `docs/functional-forever-profiles-results.md`.
- Corregido el puente de teclado físico de la Activity PoC al bus XServer,
  únicamente mientras la GUI Wine está activa. La sesión read-only Java dura
  180 segundos; límites de poll Rust y permisos no se ampliaron. Build/audit/
  retención verificados, JNI y runtime gráfico idénticos. 27 tests host dirigidos
  positivos. No se almacenan aquí valores de cuenta ni screenshots posteriores
  a la entrada de secretos.

- Estado físico 2026-10-12: lectura sin root del WoW ARM64 real demostrada, con
  UID4, PID/startTicks, ascendencia y nonce. El kernel de xaga no publica
  `/proc/<pid>/task/<tid>/children`; se usa enumeración de metadatos filtrada por
  UID y cadena PPID, manteniendo validación del objetivo. No acepta UID por sí solo.
- Última prueba read-only: `functional-poc-20261012T124623761464Z-26fd4d2948`,
  APK64 SHA35f9b72508146391510c5661b993ecb3c52329bb1ba4175e71399222045359fe.
  Objetivo15251/start7683/UID10330/PPID7532; login limpio conservado. Pasada completa
  temprana: 58791936 bytes de memoria, 115 regiones privadas RW, cero anclas y cero
  almacenes. Pasadas posteriores hasta 2GiB incompletas por cambios/límites,
  también sin anclas observadas; no prueba ausencia global ni disposición distinta.
  `bytesWritten=0`, exports funcionales solo begin/poll/end.
- Análisis estático offline del ARM64 de referencia build70009, SHA
  d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59:
  no contiene las claves exactas del contrato, tampoco en representaciones
  alternativas probadas. No prueba que el almacén exista antes de conectar.
  Bootstrap actual de datos ~1.2GiB, no instalación completa. Investigar
  inicialización/CASC antes de ampliar memoria o pedir credenciales.
- Diagnóstico actual añade contadores de anclas/rechazos, memoria/proc bytes y
  cobertura sin volcar keys/heap. Siete tests Rust actuales positivos, build
  Android/certificado/retención verificados; sin API de escritura funcional.
  Collector corregido: más de16 logs históricos ya no bloquean la recogida; valida
  todos los nombres y captura como máximo los cuatro más recientes con el mismo
  presupuesto. Seis tests host positivos. La recogida de esta prueba fue aditiva
  y read-only después del control; originales no reescritos.
- Investigación host-only posterior: el ARM64 de referencia contiene el tipo
  `NetClient::CertStore::PublicKeyEntry` y mensajes de inicialización, pero no se
  resolvió su constructor, fuente ni momento de creación. Los mensajes PEM/TACT de
  `WowAuthCryptoTACT.cpp` no se equiparan al almacén12×40 sin enlace demostrado.
  El `vfs-root` existente se decodificó y autenticó (52.937 bytes/827 rutas), pero
  solo expone rutas de instalación y no identificó el recurso/FDID. No hubo
  descarga, nuevo arranque, lectura ampliada de memoria ni login; el bloqueo de
  escritura sigue siendo cero almacenes reales compatibles observados.

- El usuario autorizó explícitamente una prueba de conexión con cuenta de test
  el 2026-10-12. No se copian aquí las credenciales recibidas. El intento es
  diagnóstico: el CertStore aún no se ha encontrado y la API funcional continúa
  siendo read-only (`bytesWritten=0`). No se promete autenticación correcta ni se
  modifica el contrato para escribir sin un almacén completo compatible. Se
   registra una única intención de Login; sin reintentos automáticos, capturas
   posteriores sin autorización, root ni secretos en APK/logs. Cualquier petición de
   segundo factor, cambio de cuenta o aceptación de términos detiene la prueba.

- El usuario autoriza después capturas posteriores con la cuenta de test: «no hay
  problema en mostrar los credenciales son precisamente para pruebas». Se permite
  una nueva prueba visual acotada, un Enter por ejecución y sin reintentos de
  autenticación automáticos. Flag explícito `--capture-login-result`, apagado por
  defecto; pantallazos del juego con campos/diálogos visibles y barras del sistema
  ocultas. Los ficheros de credenciales siguen efímeros y los logs redactados.
  No se relaja el límite de escritura del patcher ni se operan otras cuentas.

- Prueba visual posterior autorizada: `functional-poc-20261012T184959886152Z-872fb78bc2`,
  mismo APK64 a0d6e1ec/runtime/JNI, un Enter a las 18:52:12.916 UTC. Cuatro PNG
  posteriores del juego, con barras del sistema ocultas y logs redactados.
  El cliente muestra «You have been disconnected. (WOW51900317)» en el login
  limpio; no hay reino/personaje/mundo. Causa de desconexión indeterminada,
  no se atribuye a credenciales/certificado por ese código solamente.
  Opción de capturas apagada por defecto; 25 tests host dirigidos positivos.

- ¿«Servidor público de WoW Forever» significa el público de Classic Forever,
  otro servidor comunitario, o el servicio oficial de Blizzard? No se infiere una
  clave distinta o ausencia de parche por ser público. El diseño desacoplado sirve
  para perfiles futuros; su compatibilidad deberá validarse con endpoint/contrato.
- Permisos/topología de memoria del cliente actual bajo Android: las pruebas del
  patcher sobre hijos sintéticos y la producción x86_64 de wowserver no resuelven
  automáticamente PPID1/PE anónimo de la PoC ARM64.
- Momento de creación/recreación del CertStore del cliente real y número de copias
  válidas aún desconocidos; seguir el contrato, sin aceptar bloques parciales.
- Este plan autoriza parche de clave de autenticación al aprobarse, no modificación
  de código del juego, de otras memorias o del protocolo del servidor.
- La PoC visual funciona en este Mali-G610; no acredita otros Mali, instalación
  completa, sesiones prolongadas ni acceso a ninguna cuenta/servidor.

- Investigación `wowemulation-dev/wow-patcher` aprobada y completada en
  `docs/wow-patcher-arm64-investigation.md`; plan específico archivado en
  `.agents/planning/done/wow-patcher-arm64-investigation-plan.md`. Parse AA64,
  VERSIONINFO70009 y dry-run Ed comprobados sobre copias; sin PE modificado.
  Prefijo8 único en ARM/x64, pero full32 idéntico entre CPUs y distinto de todas
  las claves completas Forever: ninguna identidad semántica del grupo8 probada.
  Control ARM64 `functional-poc-20261013T190628372779Z-53f5c4529b`: 32/32 iguales
  antes/después de D3D11, comparación tardía cached/INCOMPLETE; sin Login ni writes,
  render limpio conservado. Lector25 tests más regresión FD posterior y analizador
   dirigido cuatro tests, por rondas; no nueva autoridad de escritura ni autenticación.
- El usuario solicita seguir el recorrido del slot32 idéntico con Ghidra,
  contrastando el parcheador x64 funcional, sin modificar el cliente ni desactivar
  verificaciones. Investigación host-only en `docs/ed25519-static-slot-dataflow.md`:
  slot `.rdata` ARM64 RVA4a5ff00/x64 RVA48f6870; compuesto de primeros16 de grupo9
  y últimos16 de grupo10, no clave completa ni grupo8. Ghidra v3 corrige un
  selector RIP-relative y páginas de aliases; ningún consumidor identificado,
  alias ARM4cb63b8 apunta al dato posterior (+32). Seis tests de flujo y siete
  de contexto actuales positivos; originales/hashes intactos, sin ADB/Login ni
  escrituras. Constructor, equivalencia funcional e inicialización pendientes.
  La traza dinámica PC desde un almacén x64 validado es propuesta, no ejecutada
  ni autorizada implícitamente como instrumentación genérica.
