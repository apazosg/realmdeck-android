# PoC funcional: perfiles y lectura del cliente ARM64

Estado 2026-10-12: **pantalla de login limpia sobre Mali-G610 y perfiles
implementados; prueba visual posterior muestra desconexión WOW51900317,
autenticación no confirmada y parche de memoria todavía no aplicado**.

El perfil PoC aplicó el portal/configuración de Classic Forever sin modificar
`Config.wtf`. Este documento distingue la PoC gráfica de la variante automatizada
code33 y del baseline x64; el estado coordinado está en
[`arm64-forever-status.md`](arm64-forever-status.md).

La configuración actual de la PoC es `BetaFunctionalPoc-classic_forever.wtf`: la
PoC lee `BetaSuspendedTest.wtf` como entrada y después usa la configuración nueva.
El ensayo P0 no atribuye escrituras a `BetaSuspendedTest.wtf`.

## Disponible

- Perfil Original y Classic Forever (`auth.gpon.com.co`), hostname configurable.
- Configuración WTF derivada; el original y `Config.wtf` no se modifican.
- Opción de parche apagada por defecto. En esta etapa inicia únicamente lectura;
  JNI funcional expone `nativeBegin`, `nativePoll`, `nativeEnd`, no escritura.
- Runtime gráfico conservado: Proton11 ARM64EC, DXVK2.4.1 custom, PanVK, wrapper
  BC `none`, tipo `software`, recursos `ahb`, cache0 y volumen Android1/15.

La pantalla real de WoW ARM64 build70009 se mantiene limpia. Esto no acredita
autenticación, entrada al mundo, instalación completa ni estabilidad prolongada.

## Lectura física y bloqueo actual

APK retenido:
`/home/adrian/wowforever-artifacts/arm64-prototype/delivery/retained-functional-poc64-35f9b725.apk`

SHA256 `35f9b72508146391510c5661b993ecb3c52329bb1ba4175e71399222045359fe`.

Prueba:
`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/functional-poc-20261012T124623761464Z-26fd4d2948`.

Se identifica el WoW real PID15251/startTicks7683/UID4=10330/PPID7532, ligado al
nonce y ascendencia de la app. `/proc/<pid>/mem` se abre solo para lectura.
El kernel omite la vista `task/*/children`; el fallback filtra metadatos por UID
y reconstruye la ascendencia, sin autorizar objetivos por UID/nombre solamente.

Una pasada temprana completa leyó **58.791.936 bytes** en 115 regiones privadas RW:
**cero apariciones de la clave ancla, cero almacenes**. Pasadas posteriores llegaron
al límite de 2GiB y tuvieron errores de lectura mientras cambiaba la memoria:
son `INCOMPLETE`, no prueba de ausencia. Nunca se escribieron bytes.

Los contadores distinguen memoria del juego, lecturas procfs, anclas y rechazos del
bloque480. No registran direcciones, claves, heap ni datos de cuenta. El núcleo
original sigue siendo quien valida el contrato; un diagnóstico no autoriza parchear.

Análisis estático del ARM64 de referencia build70009 (SHA256
`d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`)
no encontró las claves del contrato ni las representaciones alternativas probadas.
Esto sugiere materialización dinámica, pero no prueba su momento de creación.
Los datos del dispositivo son un bootstrap ~1.2GiB, no un cliente completo.

### Investigación de inicialización, sin login ni nueva ejecución

El PE ARM64 de referencia contiene `NetClient::CertStore::PublicKeyEntry`, el
mensaje `Cert store init failed` y una descripción de `allowDevPublicKey` que
contrasta una clave de desarrollo con la fuente estática. Esto confirma que la
abstracción existe, no cuándo se construye ni cómo se materializan sus claves.

También hay mensajes de `WowAuthCryptoTACT.cpp` sobre certificados PEM y un
archivo de certificados del servidor en TACT. Es una ruta de certificados distinta
o relacionada aún por determinar: **no se identifica con el bloque12×40 del
patcher únicamente por compartir la palabra «certificados»**.

Se decodificó desde la caché existente el `vfs-root` de 52.937 bytes, verificando
su hash: TVFSv3, 827 rutas y 1.157 entradas CFT. Las rutas son de instalación y no
permitieron identificar un recurso de autenticación/certificados ni un FDID.
No se descargaron datos, ejecutó el cliente, amplió el escaneo de memoria ni
repitió la búsqueda de claves. Tampoco se obtuvo un constructor o disparador
demostrado del almacén. Por tanto, siguen sin probarse tanto la necesidad de una
conexión como la hipótesis de datos CASC incompletos.

Informes host-only fuera del repo:

- `work/static-resource-init-certstore-20261012T131412Z-1310563.json`
- `work/static-vfs-root-readback-20261012T132548Z-1358793.json`

Ambos bajo `/home/adrian/wowforever-artifacts/`; el PE analizado es una referencia,
no una nueva comprobación del hash del ejecutable del teléfono.

## Verificación y siguiente paso

- Siete tests Rust actuales del módulo funcional: PASS.
- Cross-build Android/16KiB, build APK offline y metadata/certificado: PASS.
- Runtime/recursos previos idénticos; solo JNI y marcador PoC cambian.
- Seis tests del collector de logs: PASS. El historial acumulado no impide
  capturar los cuatro logs propios más recientes, sin elevar el presupuesto.
- Logs de esta prueba recuperados de forma aditiva y read-only después del ensayo;
  los resultados originales se conservan. No se anuncia suite completa del repo.

La escritura de grupo8 queda pendiente de un almacén real único y compatible.
No ampliar búsquedas ni inventar offsets/claves por un `NOT_FOUND`.

## Prueba de Login autorizada, 2026-10-12

El usuario proporcionó una cuenta temporal y autorizó una prueba con Classic
Forever. Sus valores no se incluyen aquí. El runner lee y elimina el fichero host
privado, redacta los logs y no guarda screenshots después de introducir secretos.
La opción «Remember Account» se exige desmarcada antes de cada fase de entrada.

Se corrigió un bloqueo real de entrada: la Activity PoC no reenviaba las teclas
físicas al bus de eventos de XServer, a diferencia de MainActivity. Ahora lo hace
solo mientras está activa la GUI Wine; fuera de ella conserva el comportamiento
Android normal. Tab pasó a mover el foco al campo de contraseña. Los controles
anteriores detenidos antes de enviar Login no se reinterpretan como intentos de
autenticación.

APK de esta prueba, retenido fuera del repo:

`delivery/retained-functional-poc64-a0d6e1ec.apk`

420.476.267 bytes, SHA256
`a0d6e1ecfa776c892183c439b60e6e86deeddcd08e28f3074958cf291f4cc3f0`.
JNI sin cambios respecto a la lectura previa: SHA256
`d3cabad2fe53551d5ff21604ac8ff79148065d156c84808cf05166d13aa0df76`.
Solo cambió el marcador PoC entre sus assets/libs y el candidato anterior; los
binarios gráficos permanecieron idénticos. La sesión Java de lectura se amplió
de 90 a 180 segundos para observar el intervalo posterior a la conexión; los
límites de cada poll nativo y el acceso de solo lectura no cambiaron.

Evidencia:

`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/functional-poc-20261012T183141223033Z-f6496fc097`

- `actions.jsonl:46-47`: intención de Login a las 18:33:52.665 UTC y **un solo
  Enter** a las 18:33:52.897, tras comprobaciones del formulario y foco.
- `logcat-final.txt:4585-4598`: Enter seguido de actividad Wine TLS/certificados.
  No identifica un handshake remoto completado, respuesta de autenticación ni
  transición a selección de reino/personaje. Los `fixme` TLS no bastan para
  concluir un fallo de red, certificado o contraseña.
- `gx.log` acredita el arranque D3D11 anterior al Login. `General.log`, `Tact.log`
  y `WowDownload.log` recogidos están vacíos. Los errores históricos incluidos
  en la captura no se atribuyen a este envío.
- `poc-logs/final-1791829927920.jsonl:24-26`: después del Enter continúa la lectura
  del objetivo PID13849/startTicks9393/UID4=10330/PPID10216. Las dos últimas
  pasadas completas leyeron cada una **2.021.979.136 bytes de memoria**, sobre
  598 regiones privadas anónimas RW seleccionadas: cero anclas y cero almacenes.
  Pasadas anteriores incompletas conservan esa clasificación. «Completa» se
  refiere a ese conjunto seleccionado, no a toda la memoria ni a una ausencia
  global del almacén.
- `bytesWritten=0` durante toda la prueba; no existe API funcional de aplicación
  del parche. El resultado del harness `OBSERVED` significa captura completada,
  **no autenticación correcta**.

Verificación del cambio de entrada/runner: 27 tests Python dirigidos positivos,
build APK offline y metadata/certificado positivos. No se anuncia suite completa
del repo. Cleanup del paquete solicitado después de recoger la evidencia; no es
prueba de quiescencia del árbol Wine.

Resultado: **AUTH_UNCONFIRMED / STORE_NOT_VALIDATED**. El único envío autorizado
de esa prueba ya se utilizó; no hubo reintento automático. El siguiente diagnóstico debe
distinguir inicialización del almacén y compatibilidad TLS con evidencia concreta,
sin repetir credenciales ni habilitar escrituras por heurísticas.

### Captura posterior autorizada por el usuario

El usuario permitió mostrar la cuenta de test en los pantallazos. Se añadió
`--capture-login-result`, apagado por defecto y permitido solo en un control de
Login explícito. Conserva juego y diálogos visibles, oculta las barras del sistema,
mantiene los logs redactados y no pulsa botones ni repite Login durante la captura.
25 tests host dirigidos positivos; revisión read-only del cambio sin defectos
concretos. No hubo nuevo build ni cambio de APK/JNI/runtime para esta captura.

Prueba visual separada, un solo Enter en esa ejecución:

`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/functional-poc-20261012T184959886152Z-872fb78bc2`

- Login enviado a las 18:52:12.916 UTC (`actions.jsonl:46-47`).
- `screenshots/postauth-001.png`, `postauth-008.png`, `postauth-020.png` y
  `postauth-035.png`: capturas programadas tras el envío; los sufijos son tiempos
  nominales, la captura y los checks own-UI añaden latencia registrada en actions.
- Las imágenes inspeccionadas al principio y al final muestran
  **`You have been disconnected. (WOW51900317)`**. Sigue visible el login limpio
  y su fondo; no aparece selección de reino/personaje ni mundo.
- El código de desconexión no determina por sí mismo una contraseña incorrecta,
  certificado rechazado, respuesta concreta del servidor o causa de red/TLS.
  No se presenta como autenticación correcta ni como fallo demostrado del parche.

Se conservan los resultados originales de la prueba anterior sin screenshots
posteriores. La cuenta no se copia a este documento ni a la configuración del APK.

## Exploración del parche posterior

La afirmación histórica de que la conexión con Classic Forever «requiere el
parche» se conserva como **hipótesis heredada del método PC**, no como hecho
demostrado para ARM64. Estos controles sin parche no validaron las credenciales ni
la compatibilidad del servidor, pero tampoco aislaron la causa de
`WOW51900317`: no hubo escritura (`bytesWritten=0`) y el contrato 12×40 solo está
validado en x86_64. No se decide todavía si ARM64 necesita parche en disco,
memoria, configuración u otro mecanismo. No repetir Login en este alcance host-only
ni habilitar escrituras por coincidencia heurística. La PoC gráfica y la
autenticación son puertas distintas.

La comparación con el patcher PC confirmó que el bloque12×40 está validado en x64,
no probado todavía en ARM64. El lector canónico solo selecciona anónimos
`rw-p`; las pruebas iniciales no cubrían otras clases. Se reprodujo además un defecto
sintético de deduplicación antes de validación, que no explica por sí solo los
cero hallazgos del ancla. Se confirmó por SHA que el PE de referencia analizado
coincide con el del teléfono. Resultados, límites y siguiente diagnóstico concreto
en `docs/arm64-certstore-patch-exploration.md`.

### Continuación sin Login: seis clases privadas

El defecto de deduplicación fue corregido con cinco regresiones sintéticas y
ocho tests core positivos. Un diagnóstico separado rota las seis clases privadas
legibles, sin habilitar escritura ni alterar el contrato. Dieciséis tests Rust
funcionales positivos, revisión, cross-build y retención verificadas.

APK64 `d4bae6cdf35f60e1392e4d712a2f7b36c31e1931f044c8e120799335b0dac4e5`,
prueba `functional-poc-20261012T203602111851Z-39f3a01712`: WoW PID14423/start8744/
UID10330, login limpio conservado, sin Login ni credenciales, bytesWritten=0.
Cinco clases tuvieron al menos una pasada completa; anon-r-- quedó incompleta con
un error. Todas observaron cero claves conocidas/sustituta y cero anclas, incluidas
las no alineadas. No hay almacén compatible validado ni resultado de parche.
Tabla y límites temporales/cobertura en el documento de exploración; no se afirma
ausencia global ni se convierte un diagnóstico en permiso para modificar memoria.

### Continuación estructural: no asumir las claves x64

APK64 `b54bc0e6667ed8b925c120c5dc10ac6b6ad4b159c3841af55ebde6543f814ba3`,
prueba sin Login `functional-poc-20261012T210439363752Z-bb4e380bee`: render limpio
conservado, objetivo14622/start9322/UID4=10330, escrituras cero. El lector añadió
una hipótesis12×40 con IDs1..12 sin exigir el ancla. Encontró secuencias de IDs en
tres clases, pero ninguna con todos los flags/padding del contrato y ninguna clave
conocida. El contraste estático halló secuencias similares en ARM64 y x64 sin
identificar ninguna como CertStore; campos diferentes no equivalen a claves públicas
diferentes. Cuatro clases completas en esta prueba; anon-r--/anon-rw incompletas.

22 tests funcionales Rust positivos, revisión read-only, build/certificado y
retención positivos; solo JNI y marker cambiaron frente a d4bae6cd. No hay nuevo
export de escritura ni autenticación. Tabla, candidatos, informes y límites en
`docs/arm64-certstore-patch-exploration.md`. El siguiente bloqueo es identificar
la estructura real, no dar permiso de escritura a una coincidencia de IDs.

### Navegación de continuidad

- [Estado central ARM64 + Forever](arm64-forever-status.md) para variantes,
  ensayos y el resultado autenticado del alias.
- [Exploración técnica del CertStore/parche](arm64-certstore-patch-exploration.md)
  para límites del lector y del contrato.
