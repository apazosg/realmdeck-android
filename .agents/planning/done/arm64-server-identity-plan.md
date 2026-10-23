---
id: arm64-server-identity-2026-10-20
state: done
date: 2026-10-20
---

## Summary

**Validación posterior al cierre:** el usuario confirma acceso y funcionamiento
dentro del juego, sin glitches gráficos observados. Amplía el hito de Login a
entrada al juego; no demuestra duración30–60min, FPS, audio ni controles.

**Cierre21oct2026:** C127 instalada conserva las correcciones anteriores y aplica
la clave pública del grupo8 con relectura/identidad/mapeo guardados. Tras confirmar
un `APPLIED` y mantenimiento `ALREADY_PATCHED` en el reintento con USB conectado,
se solicita un único Login y el usuario comunica **«funcionó!!!»**. Hito de Login
resuelto por confirmación del usuario; no se afirma mundo/gameplay ni se exportan
credenciales. Evidencia vigente: `work/client127-usb-retry-20261021/first-application-observation.json`,
log `run-1792615126263.jsonl`; mismo WoW23794/start5351664 vivo y USB montado en
la comprobación inmediatamente previa. No se cierra ni modifica esa sesión.

Los Open Issues y propuestas de abajo conservan la cronología del diagnóstico.
Fuera del cierre de este fix quedan UX previa al primer Enter, estabilidad USB,
limpieza completa de Wine y gameplay. El usuario pidió adelantar la búsqueda;
esa temporización aún no se ha implementado y requiere su decisión propia.

El intento real de70450 ARM64 supera CASC, muestra la cinemática y permite enviar
Login. Los registros volcados al salir aíslan ahora el bloqueo de acceso:
`Protocol error: server identity check failed regionGroup="8"`.
La sesión de autenticación y la lista de reinos se completan antes de ese error.
No se justifica volver a descargar el cliente, cambiar la contraseña ni desactivar TLS.

Se propone adaptar la verificación del servidor Classic Forever para ARM64 con
evidencia propia de esta arquitectura, sin imponer el layout histórico x86_64.
**Aprobado por el usuario («si, hagamoslo»).** Todavía no se ha cambiado ninguna
clave ni activado instrumentos nuevos. La corrección mecánica del cierre se trabaja
por separado, sin alterar la verificación de identidad ni las guardas de guest.

## Content

### Evidencia y límites

- C121, cliente1.60.1.70450 ARM64, portal real `auth.gpon.com.co:1119`.
- Connection.log:22:54:47 respuesta de formulario `ERROR_OK (0)`;
  22:54:48 login completado y lista de reinos disponible;
  22:54:49 conexión al reino establecida;
  22:54:55.382 fallo de identidad del servidor, grupo8, seguido de desconexión.
- El código1016 posterior es el cierre del canal tras ese fallo, no evidencia de
  contraseña incorrecta ni causa anterior al rechazo de identidad.
- Evidencia externa filtrada en
  `work/native-kmt-reserve-20261020/Connection.log.closed-redacted.txt`.
  No se exportaron credenciales, tickets ni registros completos de autenticación.
- El contrato existente fija una clave pública de Classic Forever para el grupo8;
  su aplicación validada en x86_64 no demuestra estructura, ubicación ni método ARM64.
  El nuevo log acredita el grupo que falla, no todavía el objeto que modificar.
- Estado/feed upstream404: no confundirlo con un rechazo de build. El cliente
  sí recibe la lista de reinos; compatibilidad completa y mundo siguen sin validar.

### Alcance propuesto

1. Reutilizar investigación existente y comprobar la procedencia de la clave pública
   del servidor; identificar la ruta de validación y un mecanismo mínimo ARM64.
   Priorizar configuración soportada si existe; no copiar el parche x86_64 a ciegas.
2. Si hace falta observación del proceso, acotarla al cliente hijo propio y a la
   evidencia necesaria de claves **públicas**/estructura. Sin dumps generales de
   heap, credenciales, sesión o código, ni reactivar el capturador histórico por defecto.
   Una búsqueda fallida no autoriza ampliar el escaneo ni escribir por heurística.
3. Con objetivo y formato demostrados, aplicar únicamente la clave pública de
   Classic Forever al grupo8 mediante el mecanismo más pequeño que se justifique.
   Conservar los demás grupos y el ancla1; validar bytes originales y resultado,
   identidad del proceso y cancelación al terminar. Fallar cerrado si hay ambigüedad.
   Si el método requiere cambiar contratos/specs o nuevas clases de escritura,
   actualizar el plan y solicitar esa decisión antes de ejecutar.
4. Incorporar la solución al launcher, con pruebas focales, revisión independiente
   de confianza/concurrencia, reconstrucción reproducible y auditoría APK.
5. Pruebas físicas controladas, respetando la política de un arranque WoW por boot.
   El usuario autoriza después el uso autónomo de la cuenta de prueba facilitada en
   la conversación: entrada efímera solo en Classic Forever, sin copiar sus valores
   al plan, archivos, registros o argumentos de procesos. No leer credenciales del dispositivo.
   No repetir intentos idénticos sin evidencia nueva ni relajar guardas. Registrar
   si desaparece el fallo de identidad y si llega a selección de personaje/mundo.

### Fuera de alcance

- Desactivar TLS, hostname, certificados o comprobaciones globales de identidad.
- Cambiar credenciales, aceptar cualquier servidor, usar cuentas oficiales o leer
  preferencias que contengan secretos.
- Parchear el ejecutable instalado o invalidar sus hashes/admisión; reset, repair,
  descarga completa, cambio de runtime/driver, root o modificaciones del servidor.
- Alterar CASC: la corrección de reserva ya está compilada y auditada en C122 y debe
  conservarse en el próximo APK.
- El crash al salir es un defecto independiente de UI, no el motivo de autenticación.

## Implementation / Action Items

- [x] 1. Obtener evidencia semántica del intento existente, sin repetir Login.
- [x] 2. Aprobar este alcance antes de instrumentos nuevos o cambios de confianza.
- [x] 3. Identificar método ARM64 y validar procedencia/objetivo de la clave pública.
  - [x] **Aprobado posteriormente («si, hazlo»), implementado en C124:** ajustar la temporización Kotlin del
    observador existente: `nativeBegin`
    sigue siendo anterior a Wine y conserva el baseline; en modo sin captura, primer
    Enter del juego inicia los mismos180s de polling. Cancelar/cerrar antes del Enter
    debe terminar el token sin leer. Misma ABI, patrones, límites y ausencia de
    escrituras; captura de código conserva su comportamiento previo. Propuesta:
    límite total de15min desde Begin, incluido el tiempo de espera, y polling hasta
    el menor de ese límite y180s tras Enter. No implica cobertura completa de los
    siete segundos auth→reino ni autoriza por sí solo otro lanzamiento/reinicio.
  - [x] Procedencia pública corroborada en el script HTTPS de Classic Forever,
    SHA256 `7b773fb16928ac998532271bdeee7c835d452e095caad813e8c994612a5e07c5`:
    `NEW_KEY` coincide exactamente con el contrato; grupo8, ancla1 y12 entradas.
    No se ha ejecutado el script y esta referencia continúa siendo x86_64.
  - [x] C126 valida el bloque real ARM64; C127 aplica32 bytes y relee el bloque
    completo correctamente. No demuestra unicidad global ni acceso al reino.
- [x] 4. Implementar el mínimo demostrado; pruebas focales y revisión independiente.
- [x] 5. Compilar/auditar/instalar conservando datos y corrección CASC.
- [x] 6. Intento controlado con la cuenta autorizada; registrar resultado y límites.
  - [x] C127: reintento manual tras aplicación y mantenimiento verificados, USB
    montado y mismo proceso vivo. El usuario confirma «funcionó!!!». Sin prueba
    adicional de mundo/gameplay; no se repite el Login ni se interrumpe su sesión.
  - [x] Prueba diagnóstica autónoma en C123, sin cambio de clave: un único Enter;
    cuenta aceptada, lista de reinos y conexión al reino, seguidas de rechazo grupo8
    a21oct18:11:37.410. Sin selección de personaje. No confundir con validación de
    una adaptación ARM64 todavía no implementada.

### Resultado físico posterior: cuenta enviada, bloqueo confirmado

La prueba pública exacta y el vaciado pasaron; el envío real terminó con
`credentialsReceived=true`, `loginEnterAttempted=true`, `loginSubmitted=true`
(493,82s de entrada guardada). El formulario reapareció con contraseña vacía.
Un único Alt+F4 normal, separado del adaptador de credenciales y precedido de
prueba fresca de proceso/formulario/foco, cerró WoW y volcó los logs. Se extraen
solo etiquetas públicas y horas en el dispositivo; nunca líneas completas de auth.
Login completado18:11:30.105, reinos18:11:30.592, conexión18:11:31.073;
rechazo de identidad grupo8 a18:11:37.410. No más Login ni reinicio.

El canal local anterior aceptó un cuerpo en RAM (HTTP202), pero su comprobación
final denegó la entrega al controlador: `credentialsReceived=false`, sin teclas.
No era una prueba de autenticación. Se añadió únicamente hasta3 lecturas frescas
ante `LoginError` de foco a esa guarda; ningún reenvío de teclas ni aceptación de
campo no vacío. Cuatro mocks focales aprueban fallo transitorio, límite de3,
campo no vacío y portal incorrecto. El envío final pasó sin denegaciones de foco.

C123 no reproduce el crash Java de salida y conserva app14420. Sin embargo, los
estados de kernel confirman zombis del guest y un helper activo; la reserva sigue
`GUEST_STARTED/proc_exe_unreadable`. El primer muestreo usó por error UID textual
`u0_a330` donde `ps` devuelve10330: sus listas vacías **no** prueban cierre. Los
estados `/proc` posteriores son la evidencia válida. No se declara quiescencia ni
se borra/edita la reserva; limpieza y temporización del observador se investigan
sin nuevas escrituras o lanzamiento. El observador original expiró antes del Login.

## Open Issues

### Referencia Android aportada posteriormente por el usuario

Revisada sin ejecutar ni instalar:
`legustoss/Classic-Forever-Android`, `version.c` en commit
`4012b693792f1824fc3bd97a5ae110d55c29f451`.
Carga una DLL Windows dentro del cliente mediante `WINEDLLOVERRIDES=version=n,b`;
su hilo consulta memoria privada RW/XRW, busca el ancla1, valida las12 entradas
de40B y sustituye solo los32B del grupo8. Espera3s al inicio y repite cada3s.
Comparación independiente: las12 claves, clave nueva y flags coinciden con nuestro
contrato; el flag0 pertenece al grupo9, **no** al grupo8. La novedad es cargar el
parche dentro del proceso mediante Wine, no un patrón de claves distinto.

README declara70425 y prueba en AYN Thor; no demuestra70450 ni nuestro Mali/PanVK.
La lógica de claves no depende de GPU, pero la compatibilidad del conjunto no está
probada. No aceptar la declaración como evidencia física de nuestro objetivo.
El código no acota el bloque480B completo al final de la región antes de leerlo,
no exige un único candidato, ignora el resultado de restaurar protección y deja
un hilo infinito. No copiar ni ejecutar la DLL precompilada: falta procedencia de
compilación contrastada y aclaración de licencia de sus adiciones.
Lectura independiente del PE precompilado, solo en RAM:19968B, máquina`0xaa64`
(ARM64; no evidencia para llamarlo ARM64EC), SHA256
`449bf014f6b4ad822fc2982d786762d3edfd7916103349a8d91d13af686ace93`.
Esta identificación no demuestra que el DLL corresponda al código publicado.

Esta referencia justifica evaluar una ruta dentro del proceso, no autoriza cambiar
ahora el contrato, añadir clases de escritura o instalar código ajeno. Si se elige
esa vía, concretar antes compilación propia, validación/solo lectura inicial y
condiciones de aplicación y someter el cambio de método a la decisión prevista.

### Adaptar el algoritmo al lector propio, ampliación aprobada

La primera implementación no se aceptó ni distribuyó: la revisión independiente
detectó cursores que podían saltar bytes al agotarse una
tanda, fallos de relectura de candidatos no persistidos, identidad incompleta de
mapas, candidatos retenidos tras remap y un límite de candidatos no efectivo.
Se exige corregir esas fronteras y probar el bucle real de lectura/reanudación;
las pruebas iniciales de helpers no bastan. No hay objetivo ARM64 validado ni
autorización nueva de escritura derivada de esta implementación.

Fronteras corregidas y revisión independiente cerrada: el bucle de producción se
prueba con memoria sintética, conserva cursor ante presupuesto/cancelación,
persiste huecos reales, compara identidad del backing y descarta candidatos tras
remap; límite efectivo64. Once pruebas focales y92 `functional_` aprobadas. El
rebase comparte ahora el helper probado (regresión focal posterior:1 aprobada).
Mapas iguales antes/después solo acreditan consistencia observada: no excluyen
ABA ni cambios de contenido entre lecturas. La lectura sigue sin autorizar escritura.

Biblioteca final recompilada con el helper compartido: API29/NDK24, ELF AArch64
con cuatro segmentos PT_LOAD compatibles16KiB.972264B, SHA256
`4ee401cb67aecbd56fdc330e87517c63ee4bb5fe1054d3c8a4ab080479eceafe`;
sidecar SHA256 `655592001cb81ff9910452ff4d931b38729458831881fa406cb741a4df9e0e34`,
23 fuentes iguales antes/después. Artefactos externos en
`arm64-prototype/functional-capture-build.dN9Pff/`. La primera compilación
`XqAXMS` es anterior a esa edición y no se distribuye. C125 debe autenticar esta
nueva biblioteca como entrada revisada independiente, conservando intactos los
pins históricos CODE64. Integración de receta/auditoría revisada; instalada en la
prueba autorizada posterior, como se detalla a continuación.

C125 `0.2.101-full-readable-observation`: la receta conserva la autenticación
histórica y exige exactamente el nuevo SO/sidecar y sus23 fuentes/toolchain.
El auditor admite solo ese miembro JNI/hash adicional, con
`readOnlyNativeChanged=true` y sin cambio de runtime/driver. Se corrigió antes
de compilar una discrepancia entre `inputs.candidateNative` del receipt y el
lector del auditor; la comparación del receipt real reconstruido con el marker
ya pasa. Pruebas host:31 aprobadas inicialmente; tras esa reparación,8 de auditoría
y8 focales de candidato aprobadas (incluida la versión reconciliada, sin excluirla).
Reconstrucción canónica en
`arm64-prototype/client125-full-readable-reviewed-20261021/`;
`assembleModernDebug` correcto en4m15s. Auditoría final
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2215 fuentes base sin cambios,178 payloads;
única nueva excepción de payload, el SO exacto revisado. APK484425231B, SHA256
`0f2829af8ca4deb9f082d25dfe20d4a86b3ef0f0c587eacdd3e49dd2f805843a`.
No se repiten las pruebas Kotlin/nativas ya aprobadas sin cambios. Falta resolver
la sesión anterior antes de instalar/probar. No se autoriza un nuevo reinicio Android.

**Decisión operativa aprobada («hazlo. a ver si funciona»):** el boot actual ya tuvo un
spawn y la regla privada histórica exige reiniciar antes del siguiente. No se
saltará silenciosamente esa regla ni se reiniciará el teléfono. El usuario autoriza
sustituirla para esta prueba por detener únicamente el
paquete propio (sin borrar datos), verificar que no queda ningún proceso de su
UID exclusivo y recuperar la reserva mediante la API existente, que debe probar
quiescencia. Si esa comprobación o la recuperación fallan, no habrá nuevo spawn.
No se eliminarán registros ni se forzará IDLE. La excepción solo permite lanzar
después de esas pruebas de quiescencia y recuperación; no elimina sus guardas.

Ejecución de esa aprobación: `am force-stop` del paquete propio eliminó todos los
procesos UID10330 (incluidos PulseAudio y los zombies anteriores), manteniendo el
boot `5e1775b1-801b-4464-8e9b-ae5d4518d250`. C125 instalada con `install -r`;
siete hashes privados idénticos antes/después. La API completa existente
`recoverPreviousGuestOperation` terminó y el registro pasó de GUEST_STARTED a
IDLE, sin editarlo ni borrarlo. USB6AD6-EF0E montado; perfil Classic Forever,
observación activada y captura de código desactivada. Evidencia externa en
`work/client125-full-readable-20261021/`. Preparación/lanzamiento controlados en
curso; aún no se afirma cobertura de memoria ni resultado de Login.

C125: un solo Lanzar WoW autorizado a18:12:26Z21oct. El log confirma BEGIN de
observación de solo lectura; objetivo real nuevo PID26564/startTicks4471265,
UID10330/PPid18609, argumentos completos y portal corroborados. El formulario
70450 acaba coincidiendo en cuatro frames, con foco usuario y ambos campos vacíos.
Se reutiliza el controlador revisado con esas identidades congeladas; no se
modifican umbrales ni transporte. El canal efímero aceptó el cuerpo solo en RAM;
el controlador de envío único y la observación nativa están en curso. HTTP202
no acredita por sí solo que se haya enviado Enter ni autenticado la cuenta.

Resultado C125:90 polls, ningún almacén validado; todos incompletos y finalmente
cancelados al acabar la ventana. Hubo dos recorridos drenados con cambios de mapas
y huecos de lectura, no búsquedas completas sin resultado. La observación acabó
antes del Login; se interrumpió el controlador host con SIGINT mientras todavía
estaba en el bucle de usuario (línea845), antes de Tab/contraseña/Enter. No hubo
nuevo Login. El origen exacto del Enter que activó la ventana no está demostrado.

**Nueva prueba manual aprobada («vale, hazlo»):** reutilizar C125 sin cambiar APK,
ventanas ni límites; detener solo el paquete, comprobar UID vacío y recuperar
estrictamente la reserva como antes. Arrancar una vez y dejar al usuario hacer
Login rápidamente dentro de la ventana vigente. No entrada autónoma de cuenta,
no Enter del agente dentro de WoW, no reinicio Android ni edición de registros.
La propuesta de una APK con observación continua no se ejecuta en esta prueba.

### Nueva evidencia de wowserver: relleno variable (ampliación aprobada)

El usuario remite a `/home/adrian/dev/wowserver/docs/INCIDENTE-70450.md`,
sección5: en70450 x86_64 se observó `070000` en las12 entradas; se corrigió el
validador para ignorar únicamente el byte37 y exigir cero en38–39. El documento
registra login confirmado posteriormente en ambos jugadores y paridad con el
launcher1.3.3. La lectura del código actual
`wowserver/vendor/forever-patcher/src/patch.rs:279–317` confirma esa regla.

En este repo persiste la comparación exacta contra `accepted_paddings` en el
núcleo, el lector completo y diagnósticos auxiliares de `functional_session.rs`.
El contrato solo enumera `7f0000` y `000000`: `070000` sería rechazado aquí.
Esto demuestra una discrepancia de validación; **no** demuestra todavía el
relleno real de ARM64 ni explica por sí solo sus huecos de lectura.

La prueba manual C125 produjo92 polls, sin almacén validado, y terminó `ENDED`;
los recorridos fueron incompletos por huecos/cambios de mapas. El usuario confirma
que Login volvió al formulario. Connection.log seguía0B durante la lectura;
no se atribuye un nuevo error concreto de autenticación sin su registro.

**Aprobada por el usuario («si hazlo»), en ejecución:**
1. Reconciliar US-002/FR-005 y la interpretación de la lista histórica de rellenos
   con la regla de1.3.3: byte37 variable,38–39 cero. Conservar claves, IDs, flags,
   alineación, identidad, presupuestos y todas las guardas de escritura.
2. Aplicar una única regla compartida a los validadores/diagnósticos afectados;
   regresión focal con `070000`/`ff0000`/`420000` y rechazo de38/39 no cero,
   claves/IDs/flags incorrectos. No limitarse a añadir `07` a una lista.
3. Revisión independiente, nueva biblioteca/provenance y APK autenticada para
   comprobar el almacén ARM64 en modo de solo lectura. No habilitar escritura
   JNI ni copiar el parcheador x86_64: el puente Android aún es solo observador.
4. Solo con evidencia ARM64 válida, decidir el mecanismo de sustitución de la
   clave pública del grupo8 conforme al alcance autorizado y revisión requerida.

Esta ampliación cambia una regla de validación normativa: su aprobación incluye
la reconciliación focal de US-002 y los validadores/diagnósticos. No cambia datos
del cliente ni requiere otra descarga o reinicio Android. La implementación es
de validación; no habilita escrituras del observador ni garantiza login ARM64.

**Implementación y verificación C126:** predicado único
`KeyStoreContract::accepts_padding` usado por núcleo, lector completo y todos
los validadores/contadores JNI afectados; contrato JSON sin cambios (muestras
históricas). La regresión nueva rechazó el bloque mixto antes de la corrección.
Después:10 tests de patch,5 de solape,94 funcionales JNI y11 dirigidos correctos;
`cargo check --all-targets` del núcleo correcto. El padre simplificó el fixture
duplicado y añadió comparación real de todos los bytes salvo los32 de grupo8,
incluida idempotencia: solo las2 pruebas afectadas se repitieron, correctas.
Revisión independiente sin bloqueantes. Una atribución inicial del constructor
a «070000 observado en ARM64/C125» era incorrecta: se corrigió en código/tests;
la observación real procede exclusivamente de x86_64 en wowserver.

Biblioteca final: `functional-capture-build.akTYsD/libforever_patcher_jni.so`,
972120B, SHA256 `93630349b109549e28a02bfd7ef1af3e5a73c60b7c1c8b5bfa47b9b4bf54848e`.
Sidecar SHA256 `fc533434572628631c435f7f8a28c800bcdfd260ca7d6bf985eed7ffff58e60e`;
API29/NDK24/alineación16KiB correctos,23 hashes fuente antes/después idénticos.
No cambios Rust posteriores. Se actualiza exclusivamente el pin candidato,
conservando la autenticación histórica CODE64 y las guardas del auditor.
32 pruebas host de receta/auditoría aprobadas y reconstrucción autenticada en
`client126-variable-padding-reviewed-20261021/`. Ensamblado C126
`0.2.102-variable-padding` en curso; auditoría APK e instalación aún pendientes.

**C126 instalada después:** ensamblado correcto4m26s; auditoría
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,2215 fuentes base sin cambios y178 payloads.
APK484424867B SHA256
`7ad48a455bd0ecc95dc5a1db0a0c406caa4c8236da080cedd4c266bbdf599725`.
La única diferencia nativa admitida sigue siendo el JNI candidato exacto, además
de los5 metadatos/provenance anteriores; no se amplió la lista del auditor.
El juego previo ya estaba terminado (zombies); se detuvo únicamente el paquete,
se verificó UID10330 vacío y se instaló con `-r`. Siete hashes privados idénticos,
mismo boot, sin limpiar datos. Se recuperó la reserva mediante la API completa
existente: IDLE, app19329. Preparación en curso sin lanzar ni armar aún otro guest;
se espera coordinación del usuario para no consumir otra ventana antes del Login.

El log ya vaciado del intento manual C125 contiene una línea nueva de fallo de
identidad grupo8 (Connection.log7867B, mtime1792607946); se extrajo solo el
contador público, no las líneas de autenticación. Evidencia de instalación,
recuperación y ese contador: `work/client126-variable-padding-20261021/`.
Todavía no se ha observado un almacén ARM64 ni sustituido una clave en Android.

### Resultado físico C126: candidato estricto ARM64 encontrado

El usuario autoriza iniciar («estoy listo, vamos»); un único lanzamiento a
19:01:56Z, app19329, mismo boot y sin entrada del agente. Log
`run-1792609187653.jsonl`. El usuario hace Login y vuelve al formulario.
La observación termina correctamente con87 polls y cero bytes escritos.
**Tres polls** (19:06:54.049Z,19:08:15.227Z,19:08:16.811Z) informan
`validatedStoreCount=1`: un bloque candidato supera todas las12 entradas,
claves, IDs, flags y la nueva regla de relleno. El recorrido global sigue
`INCOMPLETE` por huecos/cambios de mapas: no demuestra unicidad ni cobertura
completa. El byte37 concreto no se exportó; **no** afirmar que sea07 en ARM64.
Evidencia: `work/client126-variable-padding-20261021/native-observation-summary.json`.

Una traza breve de syscalls, agregada **en el dispositivo** sin buffers ni
direcciones exportados, contó238 errores EIO (cero EACCES/EPERM/EFAULT/EINVAL),
8165 syscalls totales. Tras separar la traza,78 hilos con TracerPid0. El contador
`TRACE_ATTACH_ERRORS` usa una expresión demasiado amplia (incluye mensajes de
attach/detach): no interpretarlo como78 fallos de attach. Esta traza no prueba
qué mapeos fallan ni que el EIO explique el bloqueo de identidad.

### Aplicación guardada (aprobada, en implementación)

El puente funcional continúa solo observador: cambiar el relleno no aplicó aún
la clave pública. La API actual no permite convertir la sesión terminada en una
escritura. No se utilizarán JDB, un proceso host ni un DLL externo para saltarla.

El usuario autoriza «aplica el fix para que funcione el login». Se añadirá una
operación JNI explícita y mínima, ligada al mismo token
y proceso hijo comprobado, e integrarla en el opt-in existente del parche:
- Releer y validar las12 entradas completas, identidad de proceso, límites y
  mapeo actual antes de actuar. Cero escrituras si cambia el proceso/mapeo,
  candidato incoherente, cancelación, memoria no autorizada o fallo de prueba.
- Admitir escritura solo en memoria privada y escribible del hijo (RW/RWX),
  no en archivos/compartida/sololectura; sin `mprotect` ni cambios del ejecutable.
- Sustituir exclusivamente los32 bytes del grupo8 original por la clave pública
  Forever ya corroborada; si ya es nueva, informar sin escribir. Conservar
  ancla1, demás grupos, flags y rellenos, y releer/comprobar el resultado.
- Distinguir validación completa **del bloque** de cobertura global incompleta:
  la búsqueda con huecos no será presentada como única ni NOT_FOUND completo.
  Varios bloques completamente válidos no autorizan escribir otros candidatos
  ni obviar sus comprobaciones individuales; cualquier conflicto falla cerrado.
- Mantener el observador de sololectura intacto, sin dumps, nuevas clases de
  escaneo ni desactivar TLS. Cancelación, límite temporal y procedencia deberán
  cubrir la operación nueva; pruebas de solo32 bytes/idempotencia/identidad/
  cancelación y revisión independiente antes de compilar/auditar otra APK.
- Para la prueba física, repetir únicamente la parada del paquete, UID vacío y
  recuperación estricta ya aprobadas, sin reiniciar Android, reset ni descarga.
  Login manual coordinado; no prometer selección de personaje antes de probarla.

Esto añade una capacidad de escritura/ABI que no estaba en C126 y se ejecuta
tras la aprobación expresa anterior. La observación positiva identifica un
candidato, no una prueba de Login resuelto. Contrato mínimo entre componentes:
`nativeApply(token): String`, sin PID/dirección/bytes aportados por el llamador;
solo candidatos internos del mismo lease/target tras el último poll. Primera
versión exige un único candidato observado elegible y falla con varios; no
presentará esto como unicidad global cuando el recorrido sea incompleto.
El modo de captura y `nativePoll` siguen sin escribir. `start` mantiene por defecto
`applyEnabled=false`; el hook de lanzamiento lo habilita explícitamente solo con
el opt-in de parche del perfil Classic Forever y sin captura. La primera llamada
de aplicación sigue a un poll con candidato, dentro de las ventanas actuales y
sin nuevos Enters automáticos. Tras `APPLIED`/`ALREADY_PATCHED`, cada iteración de
un segundo revalida/aplica ese mismo candidato en lugar de repetir un barrido que
puede consumir20s. `DENIED` regresa al escaneo; `ERROR` deshabilita aplicaciones.
Esto mantiene el parche si reaparece la clave original en el bloque, sin ampliar
los180s activos/900s totales ni aceptar direcciones externas. El primer Login
podría fallar antes de hallar el bloque; solo se pedirá un reintento manual con
evidencia nueva de aplicación, no se automatizan Enters.
Un error tras escritura parcial/verificación fallida se informa con bytes reales
y deshabilita nuevas aplicaciones de esa sesión: no reintentar a ciegas ni
restaurar bytes sobre memoria que pudo cambiar. Sin nuevo framework/temporizador.

Implementación C127 (`0.2.103-guarded-group8-apply`) instalada; aplicación física
confirmada, resultado del reintento de Login pendiente.
La integración Kotlin fue revisada sin bloqueantes: permiso explícito por defecto
desactivado, captura excluida, aplicación tras candidato estricto y mantenimiento
acotado. Compilación canónica correcta y29 regresiones JVM aprobadas, incluidas
las7 nuevas de aplicación/mantenimiento/denegación/cancelación.
La primera implementación nativa pasó100 tests funcionales y12 del escáner;
la revisión detectó un éxito idempotente fuera de la puerta de lease, orden del
latch posterior a liberar el lock y contabilidad de un eventual panic tras
escritura. Reparación implementada: éxitos idempotentes y escrituras comparten
prueba posterior, latch antes de liberar el lock y progreso real por intento
conservado ante panic; la puerta no relee el ejecutable externo bajo el mutex.
`cargo check --all-targets` y2 regresiones focales de admisión/progreso aprobados;
revisión independiente de cierre correcta, sin bloqueantes. No confundir tests
del bloque de480B con cobertura de la orquestación `End`/cancelación. Ninguna
escritura física hasta ahora.
La reconstrucción conserva los7 exports históricos para autenticar CODE64 y
exige8 en C127, incluido `nativeApply`. Receipt/marcador/auditor declaran
`guardedPatchNativeChanged=true`, `readOnlyNativeChanged=false` y capacidad
explícita `guarded-group8-public-key-apply`, sin ampliar otras excepciones de
payload ni atribuir al nuevo binario paridad histórica.
Las8 pruebas host del auditor y24 de reconstrucción están aprobadas. Native build
revisado correcto (API29/NDK24/16KiB,23 hashes fuente estables):
`functional-capture-build.CLrzsZ/libforever_patcher_jni.so`,997800B, SHA256
`2f7014d4133ba17d668e193b5aea0e7290d5a093168c58ace22b83647c69bd67`;
sidecar SHA256 `fa52cd48a2080028aae345d0c384a02ecfa5eb7b38bbbad331422df615cbcd4d`.
Pins actualizados solo para el candidato. Revisión de procedencia detectó dos
referencias antiguas a7 exports en la auditoría final/informe: corregidas a8,
sin cambiar la autenticación histórica de CODE64. Reconstrucción canónica en
`client127-guarded-group8-reviewed-20261021/` correcta; build4m42s,29 tests sin
fallos/errores/skips (7,686s). Auditoría APK `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:
2215 fuentes base sin cambios,178 payloads, solo el JNI fijado y los metadatos
previos. Se admiten3 descriptores sintéticos exactos del cableado nuevo, revisados
independientemente y con regresión focal correcta; no se abre un prefijo.
Un error de indentación en la auditoría tras ajustar8 exports se corrigió antes
de verificar el artefacto; no afecta al APK compilado.

APK484443363B, SHA256
`bcc67f3f05c581267f83ff3a1588eed7821be3c2f617fea75d5c7d59bcf18ff7`.
Instalación127 confirmada,7 hashes privados idénticos, mismo boot
`5e1775b1-801b-4464-8e9b-ae5d4518d250`. Solo se detuvo el paquete propio y se
comprobó UID10330 vacío; no reboot ni reset. App31624; recuperación por la API
existente correcta (`GUEST_STARTED`→`IDLE`), sin editar reservas. Evidencia en
`work/client127-guarded-apply-20261021/`. Preparación de un nuevo intento manual
finalizada antes del intento manual descrito a continuación. Acceso al reino
todavía no demostrado.

Preparación C127 confirmada (161,79s, un Jugar, cero Lanzar). Al intentar enfocar
Lanzar mediante el helper UHID, su guarda detectó un nodo de otra interfaz y
abortó antes de enviar Enter al botón. Prueba posterior: solo app31624/logcat,
ningún Wine; el log actual contiene únicamente `prepared`. Una nueva lectura
del HOME vuelve a pasar la guarda y conserva consentimiento marcado. No se
relaja la guarda ni se reintenta navegación a ciegas: el usuario, ya listo,
realizará Lanzar/login manualmente y se observan solo eventos estructurados de
parche del log `run-1792612215356.jsonl`. Este aborto de navegación no es un
fallo del escritor ni un intento de Login; precede a la aplicación física posterior.

**Resultado físico C127:** el usuario pulsa Lanzar y hace un primer Login, que
vuelve al formulario antes de que se encuentre el bloque.40 polls aún no lo
validan; el siguiente registra un candidato y `APPLIED` a1792612886776,32 bytes.
La API solo devuelve APPLIED tras lectura íntegra480B y comprobaciones finales.
Después54 `ALREADY_PATCHED` con0 bytes; total32. Al final hay un DENIED/identity
sin escritura y ENDED a1792612946801; no se inventa su causa exacta. End no restaura
la clave original. Se solicita UN reintento manual sin cerrar WoW, pendiente de
resultado. Evidencia `manual-first-apply-observation.json` y
`applied-and-maintained-snapshot.json` en el work C127. La aplicación de la clave
está demostrada; aún no se declara resuelto Login/selección de personajes.

Después se confirma desconexión USB anterior al cierre: vold destruye
`public:8,49`22:02:24.674 CEST, eject22:02:24.904, WoW/wrapper terminan0 y
`guest_terminated`22:02:25.979, `wine_exit`22:02:26.797. Mismo UUID remonta como
`public:8,65`22:02:29. No hubo segundo Login confirmado tras APPLIED: el aviso al
agente llegó después del cierre. Usuario confirma sin Salir/Atrás y solicita
reintentar. Seis muestras de volumen/root/ejecutable estables en
`work/client127-usb-retry-20261021/usb-preflight.json`; aún no es garantía contra
otro corte. No se reinicia Android ni se cambia el APK para este reintento.

**Mejora de coordinación solicitada, diseño pendiente:** el usuario indica que
la búsqueda/parche deben comenzar antes del primer Enter. Es correcto iniciar la
búsqueda al aparecer el hijo WoW; no se cambia silenciosamente C127 mientras se
repite la prueba. Hay que acordar ventana activa/total (actual180s/900s) y no
bloquear un Login indefinidamente si el cliente solo inicializa la tabla durante
autenticación. No se asume que la tabla exista antes de Enter. Cualquier cambio
de timing se validará y auditará antes de otra APK; el reintento actual conserva
el flujo conocido de C127 y requiere confirmación APPLIED antes de repetir Login.

**Dirección posterior del usuario:** «no limitemos inicialmente la búsqueda … la
prioridad ahora es hacerlo funcionar». Sustituye la propuesta de añadir solo una
clase `rwxp` por una búsqueda diagnóstica completa en el proceso WoW demostrado.
Detalle operativo aprobado posteriormente por el usuario («si, hazlo»), antes
de cambiar Rust:

1. Enumerar todas las regiones legibles del cliente verificado, sin descartar de
   entrada por RW/RX/RWX, anónimo o respaldo de fichero. Buscar anclas/claves
   públicas y validar estructuras; no exportar páginas, código, credenciales ni
   datos ajenos al objetivo. No inspeccionar otros procesos por semejanza de nombre.
2. Evitar que los límites por tanda vuelvan a empezar siempre desde cero y dejen
   sin cubrir regiones posteriores: recorrido progresivo con cobertura explícita,
   revalidando identidad y cambios de mapas antes de continuar. Los topes de
   tiempo/bytes por tanda protegen capacidad de cancelar, no son una conclusión
   de ausencia ni un recorte silencioso de la búsqueda completa.
3. Un único recorrido exhaustivo de mapas legibles no demuestra ausencia global
   durante toda la vida del proceso. Documentar fase y cobertura efectiva; errores
   de lectura o cambios de mapas quedan incompletos, nunca NOT_FOUND completo.
4. Mantener intacta la prohibición de escritura de este lector y los filtros de
   escritura del núcleo. Encontrar un candidato no autoriza parchearlo por una
   coincidencia parcial; comparar el mecanismo del DLL y validar el bloque antes
   de decidir la aplicación. No desactivar TLS ni aceptar cualquier servidor.
5. Pruebas focales de inclusión de regiones, progreso/cobertura, cambios de
   identidad y cancelación; revisión independiente, procedencia nativa y APK
   reproducible. Ningún reinicio Android ni nuevo Wine ignorando el guest previo.

Implementación y pruebas de esta ampliación pendientes. Las restricciones
de clases y rotación descritas abajo son la propuesta anterior, ahora sustituida,
no la implementación deseada. La cuenta autorizada no forma parte de los artefactos.

El usuario pregunta si se puede estudiar el DLL para aplicar su método al nuestro.
Comparación directa: ambas implementaciones usan el mismo ancla1,12×40B, claves,
flags y padding admitido. No hace falta introducir el DLL para reutilizar esas
reglas. Diferencia concreta: el DLL admite `PAGE_EXECUTE_READWRITE` además de RW;
nuestro `memory.rs::parse_maps` solo admite `rw-p`, y las clases diagnósticas de
`functional_session.rs` tampoco incluyen `rwxp`. La vista `VirtualQuery` de Wine
no equivale automáticamente a `/proc/maps`: es una hipótesis verificable, no la
causa confirmada del fallo ni una prueba de que exista allí el almacén.

Propuesta anterior sustituida: mantener intacto el filtro de escritura del núcleo y añadir
solo una comprobación diagnóstica de lectura para regiones privadas anónimas
`rwxp`, con los mismos límites existentes, identidad congelada, cancelación y
salidas sin bytes/código/secretos. No ampliar a otras clases ni volcar páginas;
resultado negativo no autoriza otra expansión. Determinar explícitamente cómo
se integra en la rotación sin aumentar el presupuesto total ni afirmar cobertura
temporal de Login. Pruebas focales de clasificación/exclusiones, límites y
cancelación; revisión independiente; biblioteca recompilada con procedencia y
APK auditado antes de cualquier ensayo. No sustituir la clave ni habilitar una
clase de escritura sobre páginas ejecutables. Cualquier aplicación posterior
requiere objetivo demostrado y decisión separada. El ensayo físico sigue
condicionado a resolver la sesión/reserva anterior sin saltar guardas ni reiniciar
Android sin confirmación. Esta propuesta todavía no modifica Rust ni el contrato.

- **Decisión posterior aprobada, implementación pendiente:** `nativeBegin` activa `PR_SET_CHILD_SUBREAPER`
  en el proceso host y End intenta restaurar el valor anterior. El token no expira
  nativamente: posponer el polling también prolongaría ese efecto sobre la adopción
  de huérfanos, no solo movería lecturas. La primera recomendación de que bastaba con
  diferir el contador queda corregida. Antes de cambiar código se pide aprobación
  explícita al límite total propuesto de15min, recibida después («si, hazlo»);
  cancelar/salir debe cerrar sin lecturas
  pendientes, y un fallo de restauración mantiene el bloqueo nativo. No se conoce el
  valor anterior del bit ni se atribuyen los zombis actuales únicamente al observador.
  Implementar ese tope desde Begin y una ventana de lectura máxima de180s desde
  el primer Enter del juego, terminada también por el tope global. El cierre del
  token no puede depender de que regrese un poll bloqueado. Captura de código
  conserva la ruta previa. No se autoriza un reinicio Android sin confirmación y
  reconexión disponible ni otro arranque ignorando reservas/descendientes activos.

  Primer Enter es una heurística aprobada, no una señal semántica de Login: un
  Enter previo en un diálogo del juego consumiría la única ventana y no se permite
  rearmarla ni ampliarla. La prueba controlada evita Enter durante la cinemática;
  cualquier cobertura se correlacionará con los eventos reales de autenticación.
  `nativeEnd` no limpia hijos ni reservas. Si falla la restauración, se conserva
  el error y el rechazo nativo de un nuevo Begin; no hay reinicio automático.

  Cambio Kotlin implementado en el árbol y todavía en revisión independiente:
  espera al primer Enter, tope global y watchdog separado del poll. La inspección
  inicial detectó y devolvió al implementador errores de cadencia, publicación del
  instante de armado, notificación perdida y liberación tras fallo de arranque;
  corregidos según su informe, pendientes de revisión final y pruebas JVM.
  Tres tripwires host focales aprobados; no son pruebas de concurrencia ejecutadas
  en Android. C124 preparada como versión del candidato, todavía sin APK validado
  ni instalación. No modifica la clave ni resuelve por sí sola el fallo del reino.

  Revisión independiente final del código reparado: sin bloqueantes; confirma
  cadencia, armado bajo monitor sin notificación perdida, cierre independiente y
  ausencia de cambios de ABI/lectura/escritura nativa. Reconstrucción autenticada
  C124 creada en `arm64-prototype/client124-login-observation-reviewed-20261021/`
  externo; pruebas LauncherUx y ensamblado iniciados, resultado aún pendiente.
  Compilar no autoriza instalar sobre el guest pendiente ni otro lanzamiento.

  Resultado C124:22 tests LauncherUx,0 fallos/errores/omitidos (4,655s), ensamblado
  correcto (6min32s). Auditoría APK `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,2215
  fuentes base sin cambios y178 payloads; SHA256
  `3dd0c794d695ebec6cb4459706b4dadf2883396081eb2ec19e4995978109a931`,484404031B.
  Se admitieron únicamente los12 descriptores exactos del GatedPollSession y sus
  lambdas tras revisión independiente;6 tests del auditor aprobados. No instalado:
  todavía contiene el nativo anterior, no la ampliación de búsqueda aprobada.

- La estructura real de validación ARM64 y el mecanismo de adaptación siguen sin
  estar demostrados. El fallo del grupo8 no prueba que exista el bloque12×40.
- La clave pública coincide con el script público actual del CDN del proyecto;
  falta demostrar dónde/cómo se utiliza la clave de grupo8 en ARM64. No aceptar
  datos del servidor rechazado como nueva raíz de confianza.
- Antes del próximo APK se debe corregir el defecto mecánico de salida:
  `CalledFromWrongThreadException` en `setWineGuiRunning`/`syncGamePresentation`,
  llamado desde `gamenative-process-wait`. Es independiente de este cambio de confianza.
- El estado durable de guest debe inspeccionarse antes de otro lanzamiento: el
  crash puede haber interrumpido su liberación. No borrar registros ni saltar guardas.
  Comprobación de solo metadatos tras Exit: `GUEST_STARTED`, observación `UNKNOWN`,
  sin identidades registradas. Exige recuperación mediante la API existente y
  prueba de quiescencia antes de otro lanzamiento; no es una sesión ya liberada.

### Corrección independiente del cierre (C123, prueba y auditoría aprobadas)

`setWineGuiRunning` despacha **todas** sus mutaciones de presentación mediante
`runOnUiThread`; los callbacks de guest y sus guardas no cambian. Se añade una
regresión Robolectric con callback desde un hilo de espera y looper pausado: la
orientación solo cambia al ejecutar el hilo principal. C123 conserva la corrección
CASC de C122; todavía no adapta ninguna clave ni afirma acceso al reino.

La regresión focal terminó correctamente (XML:1 test,0 fallos/errores,2,819s).
El empaquetado posterior quedó interrumpido: APK0B y log con final sin vaciar;
no existe un Gradle activo. El host arrancó a21:30:59, después de los últimos
archivos de compilación a21:30:31–32. Se retoma solo `assembleModernDebug`, sin
repetir la prueba ya aprobada. No se ha instalado ese artefacto incompleto.

APK válido verificado posteriormente:484388299B, SHA256
`a00c2590b476cc9df1b1a1908e2f78f0b32b56b626df1f0c318afcc6e89c3263`.
Auditoría existente `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,2215 fuentes base sin
cambios y178 payloads. SHA del APK y del receipt contrastados con ese informe;
ensamblado incremental retomado correcto. Los artefactos también conservan la
cuarentena de un transform Gradle de metadatos0B del intervalo del reinicio;
no se eliminaron en bloque otras cachés. Falta la confirmación física del cierre.

**21oct, conexión recuperada:** mDNS anuncia192.168.1.130:37601; boot todavía
`2b014ccb-6c94-4593-850f-f10c5dfb9fc1`, sin Wine ni foreground updater. C123 instalada:
versión123 confirmada,7 hashes privados idénticos antes/después. Evidencia externa
`work/client123-server-identity-20261021T054157Z/`. El registro de guest antiguo
sigue reservado. La primera invocación de recuperación no llegó al método (objeto
Kotlin aún sin inicializar); la segunda no alcanzó el breakpoint de onResume.
Se confirmó pantalla Asleep/keyguard visible. No es un fallo demostrado de la API
de recuperación y no se editó su registro. Depuradores desconectados y forwards
propios retirados; se solicita un nuevo boot mediante ADB antes de otra prueba.
Pendiente desbloquear/reconectar y recuperar por la API existente, bajo sus guardas.

**Bloqueo confirmado por el usuario:** está fuera de casa y no puede reactivar la
conexión del dispositivo. El reinicio enviado interrumpió el acceso remoto; no se
aseguró previamente una vía de reconexión. Pruebas físicas pausadas, sin nuevos
logins ni observaciones nativas. No volver a reiniciar sin confirmación del usuario
y posibilidad real de reconectar. No escanear puertos ni cambiar seguridad para
intentar suplir la falta de acceso. C123 quedó instalada y los7 hashes privados
coincidían antes del reinicio; nuevo boot, montaje USB y recuperación siguen sin
comprobación. El objetivo de acceso al reino continúa pendiente.

**21oct, acceso restablecido por el usuario:** mDNS192.168.1.130:42299; boot nuevo
`5e1775b1-801b-4464-8e9b-ae5d4518d250`, C123 y USB6AD6-EF0E montado. La API
`recoverPreviousGuestOperation` se ejecutó tras inicializar el objeto Kotlin y
confirmó `IDLE/QUIESCENT`, sin edición del registro. Evidencia externa:
`work/client123-resumed-20261021T125840Z/operation-after-recovery.json` y transcript.
No hubo Wine ni Login. La configuración del opt-in mediante JDB falló primero
por un argumento null en el evaluador host y después por falta de breakpoint;
la app acabó mostrando ANR. No se afirma que el observador esté habilitado ni
que el ANR sea un defecto de C123 sin aislarlo. Se detiene ese método de control y
se recupera únicamente la app en reposo, con una tarea operativa acotada, sin
reinicio Android, captura de código, entrada de credenciales ni arranque guest.

### Entrada autónoma reiterada por el usuario (21oct)

El usuario vuelve a pedir que se envíe la cuenta facilitada. No se reproducen sus
valores aquí. El control por UI recuperó la interfaz sin matar el proceso y guardó
`memory_patch_enabled=true`, `code_capture_enabled=false`, sin override de portal.
La ventana ANR observada desapareció al retirar la depuración; el stack posterior
se tomó con todos los hilos suspendidos por JDB y no permite atribuir una causa.

Un único arranque a13:29:22Z abrió70450; todavía no se escribió texto ni se envió
Login. Se saltó una cinemática con un Escape. El perfil de entrada fijo de70009 no
valida la geometría actual: no se sustituye por una guarda permisiva. Se requiere
adaptación mínima del perfil host a70450, conservando el anterior, con prueba real
de campos/foco, regresiones focales y revisión antes de teclear. Sin nuevas vías de
entrada al dispositivo, lectura de secretos guardados, escritura de memoria ni
reinicios; las credenciales solo podrán pasar efímeramente por memoria host, no por
archivos, argumentos, URLs o logs. Una sonda local sin datos comprobó un canal
loopback de un solo uso y lo cerró; no recibió credenciales.

El observador existente produjo lecturas preautenticación sin almacén validado,
con algunas coberturas incompletas y comparación de imagen aún ligada al tamaño
70009. No demuestra ausencia global ni autoriza cambiar ninguna clave. No ampliar
el escaneo ni repetir Login sin resolver primero la guarda de entrada.

El diagnóstico de entrada se concretó sin escribir credenciales: el cursor real
está en `x1072..1074`, fuera de las bandas antiguas. Tab sí cambia de campo; la
interpretación anterior basada en esas bandas era incorrecta. Se aprueba el nuevo
perfil explícito70450, conservando70009 como default, a partir de la imagen pública
revisada con **ambos campos completamente opacos**, SHA256
`5c17e08c0be1a0372394c31de9e37d7f163add9be7dc1c91a1d2e7f2330f8ca7`.
Prueba física sin texto: formulario confirmado, parpadeo del cursor en contraseña
y después en usuario mediante Tab/Home.16 tests host y13 de geometría/transporte
aprobados; la reparación posterior de prueba fresca por pulsación y anulación de
fase fallida añade3 casos focales aprobados (incluidos en los16).

**Todavía no se ha introducido la cuenta ni enviado Login.** El caller operativo
queda separado del APK: debe probar primero el mapa HID con texto ficticio, sin
Enter, y someter su integración a revisión. Solo entonces usará el receptor local
de un solo uso, revisado con seis casos ficticios y un caso de cabecera incompleta.
El receptor aún no ha recibido credenciales reales. No se amplía el observador ni
se ha resuelto o modificado la clave ARM64 del grupo8.

Las primeras pruebas públicas se bloquearon antes de escribir: tres capturas PNG
secuenciales mostraban siempre el mismo estado del cursor. Evidencia de solo
lectura: formulario válido en3/3, cursor usuario `[1072,1073,1074]` en3/3 y ningún
cursor en contraseña. La guarda rechazó correctamente la falta de parpadeo.
Cada PNG tarda~9,7s por Wi-Fi; ampliar su timeout no resuelve ese alias temporal.
Se cambia **solo el capturador host** por dos segundos de `screenrecord` nativo
en stdout H264, sobre el mismo ADB, decodificados exclusivamente en RAM mediante
ffprobe/ffmpeg ya instalados. Sin archivos de imagen/vídeo, audio, nuevo transporte
ni cambios del APK. Cuatro muestras reales repartidas por el flujo mantienen la
resolución2460×1080 y las mismas guardas, coordenadas y umbrales. Prueba de lectura:
4/4 formularios válidos; cursor ausente/presente/presente/ausente; foco y campos
vacíos confirmados. El deadline vuelve a600s. Revisión focal aprobada para la
prueba pública; tres casos mock del callback verifican forma/propiedad, rechazo de
dimensiones incorrectas y rechazo de cambio de proceso después de capturar.
El callback final pasa también en el dispositivo (4/4 formularios, foco y campos
vacíos,10,73s). Prueba física de teclado en curso; ninguna credencial enviada todavía.

La prueba posterior escribe solo el prefijo ficticio `a@` y se detiene por una
guarda antes de completarlo. Recorte exclusivo del campo público revisado por el
padre confirma el mapa `@`; no se exportó ni introdujo la cuenta real. Los callbacks
de permiso permiten ahora hasta3 comprobaciones **nuevas de solo lectura**, nunca
reenvían una tecla tras un error. Revisión focal aprobada: todos los intentos fallidos
siguen denegando, sin caché ni umbrales relajados. Se autoriza únicamente completar
la prueba pública sustituyendo ese prefijo ficticio conocido mediante Ctrl+A y
nueva prueba de foco. Sigue pendiente el recorte completo y la prueba de selección
antes de habilitar la cuenta; no es una admisión genérica de campos no vacíos.

**Reevaluación del resultado público:** el runner devolvió `pass`, pero la lectura
del recorte mostró `a@a@.2`, no `a@.2`: Ctrl+A no había reemplazado el prefijo.
El padre rechaza ese `selectedProof`; demostrar foco no demuestra selección.
Una sola prueba pública con la pausa80ms del helper original tampoco muestra
selección visible. No se reciben credenciales basándose en esa suposición.
La alternativa mínima exige **campos vacíos demostrados** antes de abrir el canal,
en cada permiso del receptor y justo antes de escribir usuario/contraseña.
Ctrl+A sobre un campo ya vacío deja de ser una dependencia. Se añade únicamente
Backspace al mapa host, con las mismas guardas, para eliminar los caracteres
ficticios conocidos y volver a comprobar vacío; no se borran datos del cliente.
El transporte conserva80ms por informe como el helper Wi-Fi original.
Ocho pruebas focales del adaptador aprobadas (2,38s); pendiente revisión de esta
reformulación y prueba pública desde vacío con recorte exacto y vaciado posterior.
La cuenta real sigue sin recibirse ni introducirse.

Prueba física pública posterior aprobada: seis Backspace guardados eliminan el
texto ficticio conocido; ambas entradas vacías verificadas. Desde vacío se escribe
`a@.2`, el padre lee ese contenido exacto en `probe-empty-start-dummy-crop.png`, y
cuatro Backspace dejan otra vez ambos campos vacíos con prueba fresca de foco/formulario.
No hubo Tab, Enter, contraseña ni cuenta real durante esa comprobación. La revisión
independiente acepta esta puerta de vacío, no la antigua suposición de selección.
Comprobación focal del nuevo predicado de contraseña: acepta vacío/caret intermitente
y rechaza incluso un píxel blanco persistente; no se relajan geometría ni umbrales.

El primer canal de cuenta todavía no envía datos: el lector del padre esperaba
erróneamente claves `port/nonce`; la salida real revisada del receptor es
`credentialChannelPort/once`. Se consumió solo esa metadata, sin POST ni teclas.
Se deja expirar ese canal de un uso y se corrige el lector antes de abrir otro;
esto no es un intento de autenticación ni justifica repetir Enter.

**Límite de la observación actual:** la sesión de180s iniciada con el lanzamiento
ya terminó antes de esta automatización. Revisión de código confirma que un nuevo
`nativeBegin(false)` incluye al hijo actual en su baseline y lo excluye; no permite
adjuntarse a24992 mediante ninguna API existente. No se intenta ese reinicio inútil
del observador ni se altera el contrato/baseline. El siguiente Login puede comprobar
la entrada y el resultado funcional, pero no se anunciará como nueva cobertura de
memoria durante autenticación. El objetivo ARM64 del grupo8 sigue sin demostrarse.

Para la entrada autónoma autorizada, el helper Wi-Fi existente solo admite
navegación; el mapa HID y guardas visuales de login ya existen en el helper USB.
Se permite un adaptador mínimo del informe HID sobre el socket scrcpy existente,
sin duplicar transporte ni desactivar guardas: la entrada nativa requiere una
guarda explícita de objetivo/foco; no se acepta una guarda permisiva. Sin cuentas
en archivos, registro de teclas, portapapeles ni nuevo framework de automatización.

Adaptador host `functional_login_uhid.py` implementado y revisado:7 pruebas
focales aprobadas; informes HID idénticos al transporte existente, permiso solo
si la guarda retorna literalmente `True`, excepciones sin texto del llamador y
liberación de tecla en `finally`. Importación del mapa desde el módulo conocido;
sin nuevo transporte ni CLI. La documentación exige prueba fresca de la fase
actual, nunca reutilizar una prueba del campo usuario para contraseña/Enter.
Es **inerte y no está conectado al runner**: falta integrar y verificar en el
dispositivo la identidad/foco antes de introducir datos reales. No demuestra
login autónomo operativo ni resuelve la clave del grupo8. No se usaron los
valores de la cuenta en las pruebas ni se modificó el APK por este helper.

### Siguiente observación permitida, sin escritura

Revisión independiente del modo existente `nativeBegin(..., false)` completada:
no escribe memoria del hijo ni crea archivos de captura; sus salidas contienen
metadatos/contadores, no bytes arbitrarios, claves de sesión ni código capturado.
El opt-in histórico de «parche» solo habilita este observador: no existe un export
de aplicación en ese puente. `codeCaptureEnabled` debe permanecer **false**.
Se contrastaron también los22 hashes fuente del sidecar nativo fijado con el árbol
revisado: todos coinciden. La biblioteca empaquetada fijada es
`ec8573de09cddc6f28606a6b361e675a74432d0c3349a4daef0eb4cfe72189b1`;
no se está extrapolando la revisión a una biblioteca de procedencia desconocida.

El mecanismo existente rota ocho tipos de lectura, con límites de20s/2GiB por poll
y180s de sesión; **no** es un instrumento dirigido a los siete segundos de auth→reino.
Si se utiliza en el siguiente boot autorizado, se correlacionarán únicamente sus
resultados con la fase real del login manual. Un poll incompleto o fuera de fase
no demuestra ausencia; tampoco un NOT_FOUND aislado demuestra que no exista otra
representación. Un VALIDATED aporta un objetivo candidato, no habilita por sí solo
escrituras sin el diseño/revisión requeridos. No ampliar el escaneo si no resuelve
la incertidumbre.

Las copias offline de70009 y70450 tampoco contienen ninguna de las12 claves
completas del contrato como32 bytes contiguos (ni invertidos o hex ASCII). Son
comparaciones acotadas de constantes públicas, no una prueba sobre su forma en
memoria. Evidencia externa: `work/arm64-server-identity-20261020/`.
