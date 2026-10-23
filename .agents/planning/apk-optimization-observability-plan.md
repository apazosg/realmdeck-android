---
id: apk-optimization-observability-2026-10-19
state: draft
date: 2026-10-19
updated: 2026-10-23
---

## Summary

Petición actual (2026-10-22): «necesitamos idear formas para reducir el tamaño del
apk inicial sin perder posibilidad de enviar diagnósticos a nuestro back».

Decisiones confirmadas por el usuario (2026-10-22): «la distribución será directa
desde vercel y un dominio que he comprado [...] está bien que haya una descarga
de componentes al abrirlo». Quedan elegidos **APK directo y componentes externos**;
Google Play/AAB no es el canal de este alcance. Después propone: «igual es buena
idea generar nosotros los componentes así si tenemos que actualizar o parchear
algo podemos. y quizás en vez de vercel podemos usar otra cosa». Tras valorar
alternativas, aprueba **GitHub Pages para la web + GitHub Releases para APK/packs,
manteniendo el backend privado de diagnóstico en Vercel**: «si hagamos eso. de
momento podemos mantener igual el código cerrado no?». Una vez comprobada la base
GPLv3 de GameNative, resuelve la cuestión: **«ok, da igual, está bien publicalo como
monorepo, creo que será más fácil de mantener y ya es como estamos trabajando»**.
Queda elegido un único monorepo público, no un repo privado de trabajo más otro
de distribución. El dominio está comprado en Porkbun; su nombre todavía no se ha
proporcionado y no hace falta para medir localmente ni abrir el código.

La propuesta concreta es **medir release/R8 y separar los recursos pesados del
APK base**, manteniendo en este la lógica del launcher, diagnóstico local,
saneado, consentimiento y transporte al backend existente. R8 es una primera
medición, no una alternativa suficiente a separar assets. No se requiere
Sentry/Crashlytics ni un segundo backend de observabilidad.

**Dirección de alojamiento aprobada:** GitHub Pages + Releases, sin servidor/túnel
propios ni migración del diagnóstico, desde el mismo monorepo público. El código
del servicio puede ser público sin publicar sus credenciales ni informes privados.
Sigue propuesto empezar las publicaciones propias empaquetando las versiones
actuales, no mantener un fork o compilar todo el runtime. R2 queda como alternativa
no elegida.

Este borrador registra esas decisiones, pero el flujo detallado de primer uso,
la reconciliación de specs y la optimización siguen pendientes. El usuario autoriza
sanear el identificador físico de archivos e historial y proteger datos locales
con `.gitignore` (2026-10-22): «si, y deberíamos meter eso en gitignore para evitar
hacer subida de esa información». El 2026-10-23 confirma ejecutar la publicación.
**Historial saneado, revisado y subido a `main` mientras el repo sigue privado**;
GitHub todavía devuelve un objeto antiguo con el identificador, por lo que no se
ha abierto. Se necesita purga del servidor o aprobar una ruta de repositorio nuevo.
El APK C140 tampoco se ha distribuido: incluye tres componentes declarados
propietarios y no tiene verificadas todas sus condiciones/fuentes de redistribución.
No se ha cambiado la app, construido otro APK, usado el dispositivo, enviado
informes reales, contratado servicios ni publicado binarios.
El MVP de diagnóstico sigue regido por su
[plan independiente](device-compatibility-catalog-plan.md) y
[contrato actual](../../docs/diagnostics-api.md).

## Content

### Medición actual: C132, no estimación desde C115

- Paquete `com.wowforever.arm64probe`, versión `0.2.108-settings-preparation`.
  APK **484.531.791 bytes = 484,53 MB decimales**. SHA-256 registrado en auditoría:
  `58aa1fec5d59342aafb5af269b91383d24b1a83bc13eb7e5f843c2ff508a6a49`.
- Artefactos y fuente reconstruida:
  `/home/adrian/wowforever-artifacts/arm64-prototype/client132-settings-preparation-20261022/`.
  `apk-audit.json` identifica el APK en
  `source/GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa/app/build/outputs/apk/modern/debug/app-modern-debug.apk`.
- Inspección host con Python `zipfile`, sin extraer ni modificar el APK: 1.945
  entradas, 484.200.900 bytes de contenido comprimido y 330.891 de estructura ZIP,
  alineación y firma. El tamaño coincide con la auditoría; no se repitió su hash.

| Bloque | Bytes dentro del ZIP | MB decimales |
|---|---:|---:|
| DEX: 24 archivos, sin compresión ZIP | 130.297.136 | 130,30 |
| Runtime Forever: Proton + DXVK seleccionado | 127.738.011 | 127,74 |
| Catálogo updater + metadatos | 63.286.281 | 63,29 |
| Prefijo Wine + metadatos | 47.022.124 | 47,02 |
| Variantes Box64 heredadas | 33.054.214 | 33,05 |
| Wrappers DX alternativos | 30.118.000 | 30,12 |
| Variantes FEX heredadas | 19.637.099 | 19,64 |
| Otros assets, incluido PanVK | 16.117.388 | 16,12 |
| Bibliotecas nativas Android | 6.783.802 | 6,78 |
| Variantes WowBox64 heredadas | 5.343.054 | 5,34 |
| Recursos Android | 4.545.103 | 4,55 |
| Otras entradas ZIP | 258.688 | 0,26 |

Los assets individuales principales son `proton.wcp` (125.541.265 bytes dentro
del ZIP), `catalog.sqlite3.gzip` (63.221.178) y `prefix-image.zip` (47.003.091).
Ya están comprimidos: recomprimirlos no es la palanca principal. Solo hay ABI
`arm64-v8a`; un split por ABI no elimina los cientos de MB de assets compartidos.
El APK no contiene la instalación completa de WoW ni sus objetos CASC.

### Opciones, ahorro y contrapartidas

| Opción | Efecto esperado | Coste / límite |
|---|---|---|
| **1. Release/R8 primero** | Reducir DEX y recursos; ahorro todavía no medido. `release` ya activa minify/shrink y conserva firma debug. | Menor cambio funcional, pero exige probar JNI/reflexión y retención de logs. R8 no elimina los grandes assets: runtime, catálogo, prefijo y variantes suman 326,20 MB. |
| **2. APK base + recursos externos — dirección elegida** | Sacar runtime seleccionado + prefijo + catálogo desplazaría 238,05 MB; quedarían aproximadamente 246,49 MB antes de R8 y otros recortes. | Cálculo sobre entradas actuales, NO tamaño de candidata construida. Descarga inicial al abrir aceptada; catálogo sujeto a conservar su cadena de confianza/fallback. Desplaza transferencia, no elimina su coste ni el espacio instalado. La app debe abrir y permitir diagnóstico aunque falten o fallen estos recursos. |
| **3. Recortar o diferir variantes heredadas** | Box64/FEX/WowBox64/wrappers alternativos ocupan en conjunto 88,15 MB. | No todo es prescindible: el flujo actual configura FEXCore. Inventariar alcanzabilidad y conservar lo requerido; no prometer esos 88,15 MB como ahorro íntegro. No reducir compatibilidad por inferencia desde un solo dispositivo. |

No hace falta una segunda app de diagnósticos, descargar DEX/Kotlin ni reescribir
el launcher. Mantener la lógica funcional compilada en el APK; R8 solo elimina
código no utilizado. Mantener inicialmente todas las bibliotecas Android de
`lib/arm64-v8a/` (6,78 MB comprimidos): poco ahorro frente al riesgo de romper JNI.

### Componentes propios: controlar las publicaciones, no rehacer todo

- **Propuesta:** generar y publicar nuestros packs versionados con procedencia
  verificable. Primero conservar exactamente las versiones/configuración actuales
  de Proton/DXVK y prefijo; no mezclar adelgazar el APK con cambiar el runtime.
  ImageFS ya tiene descarga separada; no rehacerla solo por uniformidad.
- **Empaquetar no es compilar.** Registrar origen/commit o artefacto exacto, hash,
  receta, parches y licencias. Compilar un componente desde su fuente cuando un
  parche lo requiera y la fuente/toolchain estén disponibles; no asumir que ya
  podemos reconstruir todos los binarios ni mantener forks sin necesidad. Las
  recetas y parches pueden ir en Git; los artefactos, fuera. No afirmar builds
  reproducibles byte a byte sin comprobarlo.
- Conservar las separaciones útiles existentes: actualizar un pack no debe
  obligar a redescargar todos los demás si sus hashes no cambian. Cada cambio
  publica versión/URL inmutable nueva; mantener las versiones referenciadas por
  APKs soportados. Reutilizar la caché verificada, no sobrescribir una URL publicada.
- **Límite de actualización del primer alcance:** las versiones/hashes siguen
  fijados en el APK. Un parche de componente requiere otro APK base que lo
  seleccione; no volver a descargar packs que no cambian. Actualizar componentes
  sin publicar otro APK exigiría un contrato adicional de metadatos firmados,
  compatibilidad y consentimiento; queda pendiente de decisión, no se introduce
  un actualizador remoto genérico ni se acepta `latest` por conveniencia.
- Solo recursos guest aprobados se externalizan; Kotlin/DEX y bibliotecas Android
  necesarias para abrir/reportar permanecen en el APK. El catálogo conserva la
  autoridad oficial de US-008. Revisar especialmente el prefijo y las obligaciones
  de código fuente/avisos de cada licencia antes de redistribuir; generar un pack
  propio no concede derechos sobre su contenido ni permite incluir cliente/CASC.

### Alojamiento elegido: GitHub Pages + Releases, diagnóstico separado

- **Elegido por el usuario el 2026-10-22:** web estática en **GitHub Pages**, con
  enlaces a **GitHub Releases** para APK y packs redistribuibles. Sin servidor,
  túnel, backend de descargas a medida ni contratación de R2 en este alcance.
- **Monorepo público elegido por el usuario:** conservar la organización actual
  para código, recetas/parches, documentación, web y releases, sin duplicar un repo
  de distribución. Incluye el código del backend que ya vive aquí, no sus datos ni
  configuración secreta. En GitHub Free, Pages requiere un repositorio público.
  No incluir tokens de acceso a releases privados en el APK. Abrir el código no
  sustituye comprobar la fuente correspondiente de cada binario distribuido.
- Publicar binarios como **assets de release**, no en Git, LFS ni en el sitio Pages.
  GitHub documenta hasta **1.000 assets por release**, cada uno **menor de 2 GiB**;
  el APK C132 y los recursos medidos caben individualmente. No fija un límite de
  tamaño total de release ni de ancho de banda en esa documentación: no convertir
  eso en promesa de CDN ilimitada, disponibilidad garantizada o exención de términos.
  Pages tiene límite de sitio de 1 GB y 100 GB/mes de ancho de banda como límite
  blando; se usará para la web ligera del proyecto, no para transportar los packs.
- Enlazar versiones concretas de assets por HTTPS, conservar las referenciadas
  por APKs soportados y no sobrescribirlas; mantener tamaños/hashes fijados en el
  APK, nunca `latest`. El ZIP/tar automático del tag del monorepo no garantiza por
  sí solo la fuente completa: la base GameNative y otros inputs hoy son externos.
- El dominio puede seguir registrado y con DNS en **Porkbun**. Configurar los
  registros apropiados para Pages después de verificar el dominio en GitHub;
  los enlaces de descarga pueden seguir usando URLs de GitHub. No es necesario
  transferir el dominio ni cambiar nameservers a Cloudflare. El remoto actual es
  `https://github.com/apazosg/wowforever.git`; el PAT autorizado permite consultar
  la API y las refs por HTTPS y confirma que sigue privado. La escritura de `main`
  con lease ya está comprobada; la visibilidad no se ha cambiado. Falta resolver
  los objetos antiguos retenidos y conocer el dominio para Pages. El dominio no
  bloquea abrir primero el código; la privacidad sí. No se ha cambiado DNS.
- **Diagnóstico permanece en Vercel, con Blob privado y su contrato actual.**
  No subir informes, credenciales ni claves de firma/administración a GitHub.
  Separar la distribución evita consumir cuota de Blob con APK/packs; no elimina
  las cuotas, límites ni fallos propios del diagnóstico. Pages no ejecuta la API.
- Como comparación, los recursos actuales suman ~239 MB fuera del ZIP: cien
  descargas son ~23,9 GB antes del APK y otros usos, frente a 10 GB/mes de Blob
  Hobby. Es transferencia de los assets actuales, no medida de futuros packs.
  R2 sigue siendo una alternativa si cambia la necesidad de distribución, no una
  compra ni una migración aprobadas. Ningún binario se sirve por `/api/reports`.
- Reducir bytes iniciales mejora instalación y acceso a diagnóstico, pero **no
  reduce por sí solo la transferencia total de una instalación nueva**. Medir
  aparte R8/variantes. Elegir GitHub no resuelve licencias ni constituye todavía
  publicación: primero resolver fuente correspondiente y los demás bloqueos.

### Monorepo público y fuente correspondiente de cada artefacto

- **Comprobado el 2026-10-22:** `LICENSE` y `README.md` de la fuente reconstruida
  C132 identifican **GPLv3** para GameNative. Coincide con el `LICENSE` upstream
  del commit `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`, base fijada en la receta.
  El launcher funcional añade sus overlays al mismo proyecto/aplicación; no es
  simplemente un cliente independiente de un servicio GPL.
- El [LICENSE MIT del monorepo](../../LICENSE) y su README no anulan los derechos
  ni las condiciones de la base GPL. No cambiar licencias ni declarar una excepción
  comercial sin autorización verificable de los titulares correspondientes.
- **Desarrollo/uso interno sin entregar copias a terceros:** GPLv3 §2 permite
  mantener las modificaciones privadas. Eso no convierte en uso interno una beta
  entregada a testers externos; sus destinatarios también tienen derechos.
- **Distribuir el APK derivado bajo la licencia actual:** GPLv3 §§5–6 exige la
  fuente correspondiente, incluidas modificaciones y scripts necesarios para
  generar/instalar/modificar la obra cubierta, junto con sus avisos/licencias.
  Para una descarga pública, proponer un archivo de fuente exacta por versión,
  accesible sin coste adicional y enlazado junto al binario (§6d). No basta con
  enlazar GameNative sin nuestros cambios ni ofrecer solo el APK.
- GPL no exige por sí sola abrir todo el monorepo o un backend independiente;
  **el usuario lo elige para mantener una única fuente de trabajo pública**. No
  cambiar automáticamente la licencia MIT de código propio ni atribuirla a la obra
  GPL combinada. Preservar licencias/avisos de cada componente y la autoría.
- **Bloqueo antes de distribuir binarios:** inventariar las obligaciones de cada
  runtime/prefijo, comprobar disponibilidad de sus fuentes y preparar avisos y
  material de compilación por artefacto. Un repo público con overlays y rutas a
  inputs locales no demuestra por sí solo cumplimiento. Esta comprobación de
  GameNative no es una auditoría completa de dependencias ni valida ya los packs.

### Preflight de apertura: hallazgos y bloqueo de publicación

- Inspección local del 2026-10-22 sobre `af8569c`: 71 commits y 1.040 blobs
  alcanzables desde `main` y el `origin/main` local. Sin fetch ni cambios remotos.
  El mayor blob mide 831.917 bytes; no se encontraron APK, DLL, EXE, SO, MPQ,
  PCAP o HAR por extensión en esos objetos. Las cuatro SO generadas presentes en
  `android/app/src/main/jniLibs/` no están versionadas. El wrapper JAR de Gradle
  no es un payload de juego. Estas comprobaciones no prueban todas las licencias.
- **Dato privado confirmado:** el serial USB real del teléfono aparece en **81
  archivos del HEAD y 82 versiones de blobs históricos**, también en el
  `origin/main` local. Ejemplos: `docs/device-poco-xaga.md`,
  `docs/wine-runtime-ownership-capacity-results.md` y
  `docs/wine-aa64-start-readiness-results.md`. No reproducir su valor en informes.
  Redactar solo los archivos actuales no lo retira del historial publicado.
- Búsqueda acotada de formatos de tokens de proveedor, claves privadas, URI de
  base de datos con credenciales y asignaciones de entorno del diagnóstico: sin
  credenciales confirmadas. El único candidato fue `.env.example`, con valores
  vacíos. Las dos únicas imágenes alcanzables son prototipos de UI revisados sin
  datos de cuenta visibles. No equivale a una auditoría exhaustiva de secretos.
- **Saneado autorizado el 2026-10-22:** retirar el serial tanto del estado actual
  como de las revisiones históricas; el usuario acepta el cambio de hashes y pide
  exclusiones para los datos privados locales. Conservar autoría y licencias; la
  identidad/email de los commits será visible al abrirlo. Esta autorización no
  permite borrar trabajo ajeno, evidencias privadas ni publicar binarios.
- `gh` no está disponible en PATH ni en `~/.local/bin`; la consulta pública sin
  autenticar al repo devuelve 404, lo cual no demuestra su visibilidad. Verificar
  acceso administrativo por un canal seguro, sin pegar tokens en el chat ni Git.
  Falta inspeccionar estado remoto, otras refs y contenido de Actions/releases/
  adjuntos antes de cambiar visibilidad. La revisión local no cubre esos recursos.

### Saneado de privacidad autorizado: ejecución acotada

- Sustituir el identificador en documentación/contratos por `DEVICE_SERIAL`, sin
  alterar resultados ni modelos de hardware. Los runners no deben tener un serial
  privado ni seleccionar implícitamente otro teléfono: recibir la selección por
  `WOWFOREVER_ADB_SERIAL` y rechazar su ausencia antes de tocar ADB/USB. Conservar
  las demás restricciones y autorizaciones de cada prueba física.
- Ampliar `.gitignore` para entorno local, credenciales/claves y evidencia privada,
  manteniendo ejemplos sin valores y documentación saneada versionables. Explicar
  que ignorar rutas no filtra texto dentro de archivos ya versionados ni historial;
  no introducir un framework genérico de escaneo ni prometer protección exhaustiva.
- Antes de reescribir, guardar una copia privada fuera del repositorio y registrar
  el estado de los cambios ajenos. Sanear en una copia aislada; verificar ausencia
  del identificador en blobs y metadatos alcanzables, preservación de autoría y
  equivalencia del árbol final. Adoptar el historial local sin checkout/reset ni
  sobrescribir los cambios en curso. No publicar refs o copias de respaldo privadas.
- Revisión independiente antes de adoptar/publicar el resultado. Actualizar remoto
  solo con acceso y estado remoto comprobados, sin pisar commits nuevos. Si falta
  acceso administrativo, dejar explícitamente pendiente la apertura pública; no
  solicitar tokens por chat ni cambiar DNS, backend o releases en este saneado.

Estado de ejecución local (2026-10-22):

- Retirado el valor de 66 Markdown (67 apariciones), 14 runners y el contrato JSON.
  Los Markdown solo cambian el identificador; se conservan resultados/hardware.
  Runners: selección explícita por entorno y fallo antes de ADB/USB si no existe;
  pruebas host con dobles, sin teléfono. README enlaza la
  [guía de privacidad](../../docs/repository-privacy.md).
- `.gitignore`: diez casos representativos de entorno, evidencia y claves excluidos;
  cuatro rutas de ejemplos/código siguen versionables. No es un filtro de contenido.
- El JSON saneado cambia su hash: se actualizan sus cuatro pins de código exactos,
  sin modificar sellos, evidencia externa ni identidades de APK. Las referencias
  históricas al hash anterior describen esos artefactos anteriores, no una nueva
  ejecución física.
- Pruebas focales: 11 de selección/streams; lotes de runners con 178 y 156 PASS;
  contrato y Wine, tras actualizar pins, 9 y 77 PASS. No sumar los lotes como casos
  únicos: hay solapamientos. Un chequeo de etiquetas UI encontró deriva **previa a
  este saneado**, confirmada en HEAD; no se corrige como parte de privacidad.
- Preparada/verificada una copia aislada saneada de los 77 commits hasta `9579682`,
  con árbol final idéntico y sin el serial conocido en los objetos alcanzables.
  No se ha adoptado ni extendido a los commits posteriores. Copias y datos privados
  en `/tmp/opencode/wowforever-privacy-20261022/`, fuera de Git. Sin push ni cambio
  de visibilidad; revisión independiente y adopción siguen pendientes.
- El revisor independiente no pudo ejecutarse por límite de uso. No se considera
  una revisión aprobada: preparar/verificar una copia aislada, pero no adoptar el
  historial reescrito ni publicar hasta resolver esa revisión. Hay además otra
  operación activa generando commits/cambios no relacionados; no sobrescribirlos.
- Acceso remoto desbloqueado el 2026-10-22 por petición expresa: «tenemos un pat en
  /dev/proyectos. copialo y úsalo aquí y documentalo». Localizado en
  `/home/adrian/dev/proyectos/.secrets/github-token`, copiado sin mostrar su valor a
  `private/github-token` (`0600`, directorio `0700`, ignorado y no versionado).
  API autenticada como `apazosg`: repo privado; metadatos con permisos admin/push/pull,
  sin probar mutaciones ni asumir todos los permisos efectivos del PAT. Git HTTPS
  con caché aislada temporal: `ls-remote` PASS, solo `main` en `8ca749d`, sin tags.
  Sin cambios de configuración persistente ni del remoto. Por petición posterior
  del usuario, la referencia de acceso queda local, ignorada y fuera del seguimiento
  de Git; se retiran sus enlaces públicos. Las versiones anteriores permanecen en
  el historial. Inspección del contenido remoto y revisión independiente aún
  pendientes. No se ha repetido el intento SSH.

### Publicación autorizada —2026-10-23

Tras comprobar de nuevo que el remoto sigue privado y sin releases, el usuario
confirma ejecutar la apertura y publicación: «venga hazlo adelante!». Esta aprobación
no incluye cambios de producto ni sustituye las comprobaciones de privacidad y
licencias. Se conserva este plan global como borrador para la optimización pendiente.

1. Preparar desde el HEAD vigente una nueva copia privada del historial: sustituir
   el serial conocido y retirar de todas sus revisiones la guía de acceso que el
   usuario decidió mantener local (`.agents/references/github-access.md`). No borrar
   su copia local ni la credencial ignorada. Conservar autores, topología y contenido
   restante; verificar que el árbol final coincide con el estado publicado previsto.
2. Auditar refs y superficies remotas accesibles (releases, issues/PR y Actions),
   sin mostrar datos privados. No publicar refs de respaldo ni suponer que los
   permisos administrativos anunciados equivalen a todas las capacidades del PAT.
3. Completar revisión independiente del saneado y procedimiento de publicación.
   Adoptar mediante actualización condicional de la ref, sin checkout/reset ni
   pérdida de cambios o commits concurrentes. Si el HEAD o remoto cambian, detenerse
   y volver a verificar; no sobreescribirlos con una copia obsoleta.
4. Actualizar únicamente `main`, con lease del SHA remoto comprobado, mientras el
   repo aún es privado; verificar el resultado y los objetos antiguos retenidos
   antes de cambiar su visibilidad. Una respuesta que todavía contenga el dato
   privado bloquea la apertura aunque ninguna ref actual apunte a ese objeto.
   No publicar otras refs ni hacer push mirror. No configurar DNS ni desplegar el
   backend como efecto lateral de esta operación.
5. Publicar en Releases solo artefactos cuya fuente correspondiente, procedencia,
   contenido y licencias se hayan verificado. Si un APK/pack no cumple, no subirlo:
   documentar el bloqueo concreto sin impedir la apertura del código saneado. No
   sustituir un APK por una release de fuentes presentada como aplicación instalable.

La comprobación inicial encuentra trabajo concurrente en el parcheador; queda fuera
de esta tarea y debe conservarse. El checkpoint saneado antiguo no se puede adoptar
sobre los commits nuevos. Las copias, credenciales y auditorías privadas permanecen
fuera del contenido público. Las versiones de binarios y sus limitaciones se deciden
por evidencia existente, sin construir, instalar, ejecutar el juego ni enviar informes
reales por esta autorización.

#### Resultado de ejecución — 2026-10-23

- Nueva copia privada verificada de **140 commits**. Se conservan autoría, metadatos,
  topología, rutas/modos y contenido, salvo la sustitución del serial conocido y
  la retirada histórica de la guía local de acceso (cinco árboles). El árbol final
  es idéntico al del checkpoint original. Cero apariciones del serial y del PAT real en
  los 2.159 objetos alcanzables saneados. `fsck --strict --no-reflogs` correcto.
- La única alerta de credenciales del inventario era un falso positivo del regex:
  las cinco asignaciones de `.env.example` están vacías. Las dos imágenes coinciden
  con los prototipos de UI ya revisados. Es una comprobación acotada, no garantía
  de detección exhaustiva de secretos.
- Revisión independiente completada: adopción local aprobada; el helper de push
  se corrige para comprobar HEAD inmediatamente antes y después del envío. La
  revisión posterior aprueba **solo el push privado**. El SHA literal evita subir
  commits concurrentes sin revisar; el lease evita pisar cambios remotos.
- Adopción local mediante CAS correcta, sin checkout/reset: índice, archivos
  versionados/no versionados/ignorados y cambios concurrentes preservados. Push de
  solo `main` correcto, sin tags ni mirror. `main` remoto verificado en el checkpoint
  saneado `0e0432a492e2179376692d4d8dfe215fc224d28e`; sin cambiar visibilidad.
- Auditoría remota: una rama sin protección, sin tags, releases, PR, comentarios,
  Actions/artifacts, deployments o webhooks; wiki y discussions desactivadas. La
  única issue (`Teclado`) es una petición de usabilidad sin datos privados ni adjuntos.
  Pages no está configurado. No se ha desplegado el backend.
- **Bloqueo confirmado en GitHub:** después del push, `GET` de un blob antiguo
  responde HTTP 200 y el contenido aún incluye el identificador. No se reproduce
  su valor ni su OID. Cambiar la visibilidad ahora no cumple el saneado solicitado.
  La documentación de GitHub indica que el force-push no purga automáticamente
  estas copias; GitHub Support puede gestionar GC/cachés en los casos admitidos.
  El repo permanece privado hasta purga comprobada o una ruta limpia aprobada.
- **C140 no apto todavía para publicación binaria:** hash y tamaño verificados
  (`7e3f9f347934c109146d4134124b9fd1bec69ec2800db313e02eec2ab40e5ba1`,
  484.653.575 bytes), auditoría de fuente/payload positiva. El APK contiene
  `assets/redirect.tzst`, `lib/arm64-v8a/libsteambootstrap.so` y
  `lib/arm64-v8a/libkgslshim.so`; `THIRD_PARTY_NOTICES` de su fuente reconstruida
  los declara propietarios, con derechos reservados. No se ha acreditado permiso
  de redistribución downstream. Tampoco está preparado el conjunto exacto de fuentes,
  recetas/avisos para runtimes/native ni revisado completamente el prefijo/archivos
  anidados. La autenticidad del build no resuelve estas obligaciones. No se afirma
  que todo componente sea ilegal; se documenta la evidencia que falta.

Copias, scripts y auditorías privadas de esta ejecución:
`/tmp/opencode/wowforever-github-publication-20261023/`. No son assets publicables.
La optimización/R8, la web, la apertura y los binarios siguen sin darse por terminados.

### Primer uso propuesto, pendiente de aprobación detallada

1. Abrir muestra inmediatamente bienvenida y acceso al diagnóstico, sin depender
   de tener runtime/prefijo/catálogo ni de alcanzar su alojamiento.
2. Si faltan componentes fijados para esta build, mostrar tamaño y descarga inicial
   con progreso. **[PROPUESTA]** Pedir confirmación inicial antes de transferirlos,
   para no consumir cientos de MB móviles por una mera apertura; la aceptación
   del usuario de «descarga al abrirlo» no especifica aún ese detalle de interfaz.
   Poder cancelar y reintentar sin impedir el uso del diagnóstico.
3. Descargar/verificar/promover componentes sin ejecutar Wine ni lanzar el juego
   automáticamente al terminar. Mantener exclusión con operaciones incompatibles.
   Una interrupción o recurso corrupto no equivale a instalación válida.
4. Reutilizar los componentes verificados en aperturas posteriores sin contactar
   su host. No consultar versiones nuevas, actualizar el cliente ni descargar
   PE/CASC del juego por abrir la app; esos flujos conservan su acción/consentimiento.
5. El envío de diagnóstico sigue siendo una acción explícita separada, incluso
   si falla la preparación inicial. No se añaden informes automáticos al abrir.

### Por qué puede conservarse el diagnóstico

- [El transporte existente](../../scripts/arm64-prototype/app/overlay/ForeverRemoteDiagnosticsUpload.kt)
  usa `HttpURLConnection`, JSON y APIs Android; no requiere Wine, Proton, DXVK,
  FEX ni otro SDK. Destino integrado: `https://wowforever-diagnostics.vercel.app`.
- La UI, el almacén/borrador, cronología, snapshot, saneado, autorización limitada
  y transporte `ForeverRemoteDiagnostics*` se quedan en el APK base. El envío
  conserva el contrato schema1, lectura privada separada, cotas y reintento manual
  idempotente; no se altera el backend para adelgazar el APK.
- La preparación del informe registra fuentes ausentes y valores desconocidos;
  no necesita ejecutar Wine ni una sonda GPU al enviar. La inspección de fuente
  permite proponer esta separación, **no demuestra aún un arranque/envío de una
  build adelgazada**. Mantener estado local y error visible si no hay red/cupo.
- Registrar la fase y el error también cuando falla descarga/verificación/
  instalación del pack, antes de Wine. No inventar logs del juego si nunca empezó.
  Reutilizar los campos de error/fase existentes; no ampliar a telemetría continua.
- El incremento histórico C117→C118 fue 211.736 bytes en debug, no una medición
  aislada de todas las dependencias de diagnóstico ni un coste proyectable a R8.
- La cobertura actual es parcial: GPU/driver pueden ser desconocidos y el crash
  externo de `CrashHandler` no se adjunta. Preservar el canal no equivale a prometer
  captura de todos los crashes ni envío tras un proceso muerto sin reapertura.

### Condiciones para separar recursos sin romper el producto

1. **Arranque independiente.** Revisar también `Application` y proveedores, no
   solo la Activity. En C132, `PluviaApp.kt:124-144` inicializa PostHog heredado y
   un hilo GPU antes de la UI. `SessionReport.kt:37-43,91-97` llega a
   `GPUInformation`/`GPUHelper`, que cargan `extras`/`winlator_11`; un `Error` no
   capturado no puede considerarse inocuo por ocurrir en un hilo daemon Android.
   No retirar estas bibliotecas. Verificar o diferir trabajo nativo temprano si
   impide abrir/reportar sin recursos guest; no prometer ausencia global de
   telemetría solo porque el canal de diagnóstico exige pulsar Enviar.
2. **Reusar bootstrap, no otra plataforma de paquetes.** ImageFS ya se descarga
   aparte (~175 MiB). Extender el flujo existente solo para los recursos aprobados,
   con progreso, coste, consentimiento y reutilización local. Se acepta descargar
   componentes faltantes al abrir; esto no autoriza consultar ni actualizar
   versiones remotas. Con caché válida, no exigir acceso al alojamiento de packs;
   la conexión al servidor del juego es otra necesidad.
3. **Integridad antes de uso.** Para el primer alcance bastan versiones, tamaños
   y SHA-256 fijados en el APK, orígenes HTTPS aprobados, staging privado, extracción
   sin traversal/symlinks inseguros y promoción recuperable. No aceptar ejecutables
   arbitrarios ni un manifiesto remoto que se dé autoridad con su propio hash.
   Mantener exclusión con Wine y cancelación sin callbacks tardíos que lancen.
4. **Catálogo no es una nueva autoridad.** Antes de quitar sus 63,29 MB, comprobar
   la admisión/generación desde metadatos oficiales ya prevista en US-008. Acordar
   qué fallback offline se conserva. No sustituirla por un SQLite de terceros sin
   verificar ni romper instalaciones existentes que usan un catálogo local.
5. **Distribución separada del diagnóstico.** Los endpoints/Blob de diagnóstico
   admiten informes de texto privados, no son un servicio de distribución de
   runtimes. El canal directo y GitHub Pages + Releases están elegidos. Resolver
   el cumplimiento GPL del launcher y revisar licencias/procedencia y límites
   antes de publicar. En particular, revisar el contenido del prefijo, no asumir
   que todo lo hoy empaquetado puede redistribuirse públicamente. No subir assets
   privados o propietarios.
6. **Release conserva evidencia.** Preservar inicialmente `-dontobfuscate` y keeps
   JNI/reflexión, guardar mapping/símbolos disponibles fuera del repo y comprobar
   que journal, intención/error y salida Wine siguen presentes sin `DEBUG`.
   No eliminar logs para lograr ahorro; no afirmar simbolización de binarios ajenos.

### Aceptación y pruebas propuestas, no ejecutadas

- Comparación por bloque frente a C132: APK inicial, descarga posterior, espacio
  instalado/pico, primera preparación y reapertura. No prometer «20/50 MB» sin build.
- Sin runtime instalado, y con descarga interrumpida/corrupta: home y diagnóstico
  siguen accesibles; informe con causa/fase real, sin archivos guest ficticios.
- Un caso representativo de descarga inicial al abrir, sin arranque automático de
  Wine/juego ni envío de informes, y otro de reutilización con el alojamiento
  de packs inaccesible conservan preparación, ajustes, cliente existente,
  lanzamiento y evidencia de Wine/juego; no se promete jugar a WoW offline.
- Mantener saneado, Cancelar sin envío, consentimiento por informe, cuotas y
  reintento idempotente mediante tests focales existentes; recepción controlada
  solo con autorización. Sin ampliar cobertura a matrices o harnesses innecesarios.
- Comprobar que el dimensionamiento/aislamiento de distribución no consume una
  cuota compartida necesaria para recibir diagnósticos; no dar esa garantía solo
  porque los objetos públicos y privados estén en stores distintos.
- Adaptar explícitamente receta, receipt y auditoría: hoy exigen igualdad exacta
  de payloads, firma y controles DEX debug. Ningún PASS eludiendo esas garantías.
- Mismo paquete/firma para la candidata local, sin desinstalar ni perder datos.
  Firma pública de producción, autoupdate, nuevos drivers/perfiles, cambios al
  protocolo backend y optimización CASC quedan fuera de alcance.
- Reconstrucción/build y pruebas de release en entorno aislado; prueba ARM64/GPU
  real coordinada por separado. No instalar, detener procesos o acceder al teléfono
  durante esta planificación; el login fallido de C131 sigue sin causa confirmada.

Referencias de comportamiento a reconciliar para la dirección elegida:
[US-003, preparación/evidencia](../specs/US-003.md),
[US-004, apertura/Jugar local](../specs/US-004.md),
[US-005, GPU informativa](../specs/US-005.md) y
[US-008, admisión oficial](../specs/US-008.md). Actualizar UC/AC y obtener la
aprobación requerida antes de derivar FR/TC de un cambio de primer uso/offline.

### Impacto en especificaciones, sin modificarlas todavía

- **US-004:** UC-01 / AC-01 y AC-07, FR-001 y FR-007 y sus TC: distinguir la
  descarga inicial de componentes al abrir de la preparación/admisión al pulsar
  Jugar; preservar bienvenida accesible, no Wine automático y exclusión de IO.
- **US-003:** UC-01/02/03, AC-02 y AC-05, FR-002/005 y sus TC: aplicar verificación,
  recuperación y evidencia también al fallo de componentes antes de tener guest.
- **US-008:** UC-01/02, AC-01/02 y FR-001/002: conservar consulta manual de versiones,
  admisión oficial y consentimiento de descarga del juego. Si se externaliza el
  catálogo, resolver su fallback sin introducir un SQLite autoautorizado como fuente
  de confianza. Si este acuerdo retrasa el recorte, conservarlo embebido primero:
  solo runtime + prefijo desplazarían 174,76 MB y dejarían ~309,77 MB antes de R8.
- **US-005 y contrato de diagnóstico:** preservar su comportamiento, sin nuevos
  drivers/sondas ni cambios de API. No marcar specs `dirty` ni alterar FR/TC en
  este paso: presentar el impacto y obtener aprobación antes de reconciliarlas.

## Implementation / Action Items

- [x] 1a. Elegir canal y aceptar descarga inicial de componentes: confirmado por el
  usuario el 2026-10-22, APK directo + dominio comprado en Porkbun.
- [x] 1b. Elegir alojamiento: GitHub Pages + Releases, manteniendo el diagnóstico
  privado en Vercel; dirección aprobada el 2026-10-22. Sin publicación todavía.
- [x] 1c. Resolver la petición de código cerrado: el usuario solicita publicar el
  monorepo completo, sin un segundo repo de distribución. Decisión confirmada el
  2026-10-22; no implica que la fuente de cada binario ya esté completa/verificada.
- [ ] 1d. Aprobar este detalle de primer uso/impacto en specs y cerrar el alcance
  de la siguiente fase, incluidas publicaciones propias. No asumir actualización
  de componentes independiente del APK. Verificar permisos sobre el remoto actual
  y confirmar dominio antes de configurar Pages; ninguna contratación implícita.
- [ ] 1e. Resolver los hallazgos del preflight antes de hacer público el monorepo:
  - [x] Autorizar saneado del serial en archivos e historial y protección de datos
    privados con `.gitignore`: confirmado por el usuario el 2026-10-22.
  - [x] Sanear archivos/runners, comprobar exclusiones y pruebas focales.
  - [x] Copiar el PAT autorizado a un archivo local ignorado, comprobar acceso API/Git
    de lectura y documentar su uso sin exponer el secreto (2026-10-22).
  - [x] Confirmar la ejecución de apertura y releases revisadas: autorizado el
    2026-10-23; no implica redistribuir artefactos con licencias/fuentes pendientes.
  - [x] Respaldar, sanear/verificar historial y preservar trabajo en curso.
  - [x] Completar revisión independiente y comprobar contenido/permisos remotos.
  - [x] Adoptar y subir únicamente `main` saneado, con lease y manteniendo repo privado.
  - [ ] Resolver los objetos antiguos retenidos por GitHub y abrir solo el estado
    revisado; no confundir el push privado con publicación pública.
- [ ] 2. Medir candidata `modernRelease` con la configuración existente y auditoría
  adaptada; comprobar diagnóstico/logs, JNI/reflexión y registrar ahorro real.
- [ ] 3. Inventariar variantes alcanzables, procedencia, recetas y redistribución;
  acordar recortes concretos sin degradar perfiles/compatibilidad por suposición.
- [ ] 4. Para APK base + recursos, actualizar/aprobar primero UC/AC y después FR/TC;
  confirmar alojamiento/licencias y adaptar bootstrap/importadores/catálogo.
- [ ] 5. Probar ausencia/fallo de recursos, integridad/recuperación, caché offline y
  envío preservado; revisión independiente de descarga ejecutable y release.
- [ ] 6. Documentar comparación y límites; pedir autorización aparte para recepción
  real o prueba física. La publicación de artefactos revisados queda autorizada el
  2026-10-23; no convierte automáticamente el APK actual en distribuible ni autoriza
  dispositivo, cambios de producto o redistribución sin licencia/fuente comprobada.

## Open Issues

- **Primer uso:** descarga al abrir aceptada. Falta aprobar el detalle propuesto:
  una confirmación inicial con coste visible, cancelación/reintento y diagnóstico
  accesible sin componentes. No reabrir la decisión de permitir descargas.
- **Privacidad antes de publicación:** saneado de archivos e historial autorizado,
  incluidos cambios de hashes; completados y revisados los 140 commits del checkpoint.
  Adopción y push privado correctos. **Bloqueo nuevo confirmado:** GitHub conserva
  un blob antiguo recuperable con el identificador. No abrir por un simple cambio
  de visibilidad ni tratar el force-push como purga. Elegir entre solicitar una purga
  a GitHub Support o **conservar el repo actual privado con otro nombre y crear un
  `wowforever` independiente con solo el historial saneado**. La segunda vía requiere
  aprobación antes de renombrar/crear repositorios; conservar la issue y los respaldos,
  no borrar el repo ni publicar la copia privada. No equivale a mantener dos repos
  activos de desarrollo. Verificar también que el nuevo destino no devuelve objetos
  antiguos antes de hacerlo público. Mientras tanto, apertura detenida.
- **Configuración de alojamiento:** GitHub Pages + Releases ya está elegido;
  `origin` identifica `apazosg/wowforever`. API/Git HTTPS de lectura y push de `main`
  verificados con el PAT autorizado; repo privado. Los metadatos anuncian permisos
  administrativos, pero cambiar visibilidad/crear repos no se ha probado. Falta el
  dominio para Pages, no para abrir primero el código. No cambiar visibilidad hasta
  resolver la retención confirmada de objetos privados antiguos.
  Mantener el servicio y los datos privados de diagnóstico en Vercel, sin migración.
  La elección no autoriza compras; los límites actuales del backend siguen existiendo.
  La autenticación ya no bloquea la lectura remota; la copia del PAT es privada y
  nunca debe incorporarse al historial, a URLs o a logs.
- **Publicaciones propias:** confirmar el alcance recomendado: empaquetar primero
  los componentes exactos actuales y compilar solo donde haga falta un parche.
  No equivale a reconstruir toda la pila. ¿Se necesita actualizar componentes sin
  otro APK? No se presupone: el primer alcance conserva pins dentro del APK;
  independizar esas actualizaciones abriría una decisión de seguridad/contrato.
- **Catálogo/licencias:** concretar fallback offline si se retira el catálogo,
  y qué recursos/prefijo tienen redistribución pública permitida. No reutilizar
  credenciales privadas ni ampliar el backend de diagnósticos. La fuente
  correspondiente de cada APK/pack debe estar disponible, no solo sus overlays.
  C140 bloqueado por los componentes propietarios confirmados y el conjunto de
  fuentes/recetas/avisos todavía no verificado. No subirlo ni recortarlo/recompilarlo
  como efecto lateral de esta operación de publicación. Abrir código saneado no
  depende de resolver estos binarios, pero sí de la privacidad del destino.
  Los binarios revisados pueden publicarse por la autorización del 2026-10-23;
  recibir informes reales y probar en teléfono siguen requiriendo autorización aparte.

### Referencias

- [Estado actual y límites de evidencia](../../docs/launcher-app-status.md).
- [Contrato de diagnóstico, límites y privacidad](../../docs/diagnostics-api.md).
- [Receta canónica](../../scripts/arm64-prototype/app/reconstruct_functional_source.py)
  y [auditoría](../../scripts/arm64-prototype/app/audit_functional_ui_apk.py).
- Android, reducción del tamaño (consultado2026-10-22):
  https://developer.android.com/topic/performance/reduce-apk-size
- Android, riesgos de carga dinámica y políticas de tienda:
  https://developer.android.com/privacy-and-security/risks/dynamic-code-loading
- Vercel, límites del despliegue por CLI (consultado2026-10-22):
  https://vercel.com/docs/limits#static-file-uploads
- Vercel Blob, precios/cuotas y tamaño de caché (consultado2026-10-22):
  https://vercel.com/docs/vercel-blob/usage-and-pricing
- Vercel, entrega directa de Blob público:
  https://vercel.com/docs/vercel-blob/public-storage
- Vercel Functions, cuerpos de petición/respuesta:
  https://vercel.com/docs/functions/limitations#request-body-size
- GitHub, límites de assets de release (consultado2026-10-22):
  https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases
- GitHub Pages, límites y uso:
  https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits
- GitHub Pages, repositorio público en Free y visibilidad del sitio:
  https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-github-pages-site
- GitHub Pages, dominio propio y verificación:
  https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/about-custom-domains-and-github-pages
- GameNative, licencia de la base exacta; GPLv3 §§1–2 y 5–6:
  https://github.com/utkarshdalal/GameNative/blob/205cbf8ddc06f855a1b85ba57c78c4be9a6930aa/LICENSE
