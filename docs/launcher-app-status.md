# Launcher ARM64 — estado de producto

## Estado consolidado —2026-10-23

### C141 — primer Login positivo comunicado; tiempos pendientes

El usuario aprueba «si, hagamos eso» tras la
[reverificación oficial/x86](patch-search-x86-comparison.md); ejecución en el
[plan](../.agents/planning/patch-discovery-parity-plan.md). Código `8885eae`.
El full scan funcional no-capture deja de recorrer el fallback de memoria donde
el escritor nunca permite aplicar: reutiliza su predicado privado anónimo/pseudo
RW/RWX, ordena por dirección y conserva cursor, solape, presupuestos e identidad.
Ambos snapshots se filtran igual; cobertura identifica `APPLY_ELIGIBLE_REGIONS`,
sin afirmar cobertura all-readable ni unicidad global. El parser compartido de
diagnóstico/captura, CLI y toda la autoridad del escritor siguen iguales.

Cada intento de parche tiene un resumen acotado en `files/patch-summary.json`:
alcance,14 campos fijos de contadores/estados y tiempos relativos, primer candidato,
primera aplicación nativa verificada y envío asistido cuando realmente termina.
Ese último hito significa envío, no autenticación; Enter manual no se interpreta
como Login. Las observaciones no autorizan writes y sus errores no bloquean el
parche ni el botón. No hay IO de disco bajo el lock de eventos; un único escritor
coalescido serializa snapshots y descarta callbacks de intentos anteriores.
El resumen no copia JSON nativo, claves, direcciones, memoria, credenciales,
argumentos, mensajes de error ni RUNs. No se añaden permisos, diálogos u opciones.

Se conservan C140, Tab/botón de cuenta, su recuperación de sesión y cierre normal
de audio (comprobado también en la fuente canónica generada). Sin cambios de
runtime, driver, contrato, opt-ins, arranque observado, pausa1s, límite180s/900s,
mantenimiento de candidato ni validación480B/escritura32B/relectura/enclavamiento.

**Validación:**107 nativas focales,62 JVM (45 UX/sesión,4 resumen,13 entrada
asistida) y44 host (33 reconstrucción/tripwires y11 auditor) pasan. No es una suite
global. Revisión independiente sin bloqueos concretos, incluidos los diez
descriptores nuevos exactos; vecinos no aprobados siguen rechazándose. La carrera
de cambio de propietario durante IO no tiene un test determinista específico;
su orden de publicación se inspeccionó. JNI nuevo con procedencia auténtica,
NDK24/API29 y ELF16KiB, SHA256
`79af82848e15de5395ddfb52f916c95ff3212fdb0e579cc69b96e2d32e4c1bd3`.

Build7m7s; `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2221 fuentes sin cambios,
178 payloads. APK484663455bytes SHA256
`fa6908c1d1a193975f7451a08b3f36a07effa37a289ea866261c57af5676d5b2`.
Recibo, logs, XML y auditoría externos en
`/home/adrian/wowforever-artifacts/arm64-prototype/client141-eligible-scan-20261023/`.

La instalación se pospone cuando el usuario indica que WoW sigue abierto. Tras
«sigue», dos observaciones frescas prueban solo app/logcat y52 grupos terminales
propios con identidad estable y Threads:1, sin juego vivo ni servicios. Se actualiza
mediante install-r: versión141/`0.2.117-eligible-scan`, UID10330 conservado y hash
de base.apk instalado idéntico al auditado. `deployment-receipt.json` y
`deployment-script-proof.json` registran la verificación externa; sin matar procesos,
borrar datos, abrir juego ni enviar credenciales desde el agente. Pendiente un único
ensayo manual con el resumen.

El usuario comunica después: «he hecho una prueba sin login y agota el tiempo.
la prueba con login funciona directamente e inicia a la primera!!». Esto confirma
un **primer Login positivo según el usuario en C141**, frente a la demora y segundo
envío de los ensayos anteriores. Es consistente con el objetivo del cambio, pero
no cuantifica su aceleración ni demuestra que ocurra en todos los intentos.
El intento distinto sin Login vuelve a agotar la búsqueda; no se exige un fallo
de Login previo ni se reanudan rondas A/B. No se atribuye aún la causa de B2.

Se intenta leer exclusivamente `files/patch-summary.json`, sin RUNs crudos, pero
ADB no tiene dispositivos conectados; mDNS conserva el anuncio del puerto previo.
No se recuperan contadores ni tiempos del intento: están pendientes de reconexión.
No se abre/cierra juego ni se envía input. Antes de otro lanzamiento, recuperar
ese resumen del último intento, que de otro modo se sustituiría por el siguiente.

### C140 — botón operativo y resultados mixtos de Login; cierre por confirmar

El usuario reproduce en C139 tanto el bloqueo Quit→Jugar como «Protección de
privacidad no disponible» al usar el botón de cuenta. Se pausa B1; su Login manual
fallido mostró un mensaje de desconexión distinto, sin causa atribuida al parche.

La fuente y el recibo auténticos de C139 demuestran una omisión de empaquetado:
la receta aislada tenía el stop de audio corregido, pero la reconstrucción canónica
solo ejecutaba las transformaciones build/manifest/XServer. El audio empaquetado
seguía usando la búsqueda por nombre que no identifica al daemon bajo linker64.
Los metadatos del dispositivo confirman que ese daemon sigue vivo después de Quit.
La auditoría validó la correspondencia con esa fuente, no que la corrección prevista
estuviera incluida. C140 incorpora la transformación a la ruta canónica y prueba
el resultado y sus hashes de recibo.

El marcador global de sesión sensible presente en el dispositivo es anterior a la
actualización C139. Tras morir el host se pierde su Session en memoria y no existía
recuperación del marcador: todos los nuevos intentos quedaban bloqueados. Se retira
solo ese marcador al admitir el próximo guest, bajo el mismo bloqueo exclusivo,
después de quiescencia y recuperación del propietario anterior, con entorno ausente.
No se retira desde el botón ni durante un guest vivo; tampoco mientras quede un
guest local cuya limpieza anterior pueda borrar el marcador del nuevo intento.
Los marcadores históricos de
RUN/attempt permanecen. No se añaden opciones, confirmaciones ni nuevas barreras de
entrada. Parcheador, cuenta, datos y límites permanecen iguales.

Código `7bdacef`. Revisión independiente completada tras corregir la carrera de
limpieza local tardía. 34 JVM pasan (8 lease,13 entrada asistida,13 host safety),
29 host de reconstrucción y1 de autoridad de fuentes. Tras congelar los hashes se
repiten los dos casos de pins afectados, ambos positivos; no es una suite global.
La procedencia cambia solo en el hash de receta y las seis vinculaciones de las
dos fuentes revisadas: RunStore/HostSafetyTest. Contrato anonimizado, JNI C137,
payloads de runtime y auditor sin cambios; no se ensanchan excepciones.

Reconstrucción final con parada de audio comprobada en Java y en su recibo,
build6m47s y `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2219 fuentes sin cambios,
178 payloads. APK484653575bytes SHA256
`7e3f9f347934c109146d4134124b9fd1bec69ec2800db313e02eec2ab40e5ba1`.
Evidencia externa `client140-session-recovery-20261023-final/`: recibo de
reconstrucción, build.log, XML, apk-audit.json y deployment-receipt.json.
Actualizada mediante install-r, sin borrar cuenta/datos: versión140/
`0.2.116-session-recovery`, UID10330 conservado y SHA instalado igual al auditado.
Preflight fresco sin juego vivo (app/logcat,12 grupos terminales demostrados y el
audio residual propio). El agente no abre juego, envía credenciales, borra el
marcador del dispositivo ni solicita salida de audio desde el host.

Ensayo manual posterior: el usuario confirma que envía el Login con el botón de
cuenta mientras el parche sigue buscando; después comunica «aplicado» y, tras
indicarle que vuelva a probar Login, «si funcionó el login». El bloqueo de privacidad
del botón no reaparece en este intento. No se especifica el método del segundo
Login ni se mide el tiempo; no demuestra aceleración o causalidad del primero.
No se exportan RUNs sensibles ni se automatiza input. **Pendiente confirmar dos
ciclos Quit→Jugar**; no afirmar todavía que el cierre esté resuelto. B1 controlado
sigue pausado: este ensayo C140 no es comparable directamente con A1 C138.

Secuencia posterior comunicada por el usuario, sin exportar RUNs ni automatizar
input: A2 en C140 termina sin aplicación indicada y el Login posterior vuelve al
formulario sin otro mensaje. Se hace Quit para B2; tras el primer Login, B2 muestra
«parche aplicado», pero el siguiente envío devuelve «You have been disconnected».
El usuario confirma que el indicador continúa mostrando aplicado durante ese error.
Por tanto B2 registra aplicación indicada y autenticación fallida, no otro éxito de
Login ni prueba de que la escritura aplicada corresponda al store usado por esa
autenticación. No hay timestamps de los envíos ni lectura nativa de este intento;
la secuencia no demuestra que el Login cree el store. Los relanzamientos A2/B2
ocurren tras anunciar Quit, pero aún falta confirmación explícita de ambos ciclos
de cierre sin el error previo. No se cambia la versión ni el parcheador.

### Quit→Jugar — C139 instalada, fallo físico reproducido

Durante A1 sin Login el usuario agota la vigilancia sin parche; al salir con Quit
y preparar B1 se reproduce «no se pudo comprobar que WoW estuviera cerrado».
B1 queda bloqueado y no cuenta como ensayo. Antes de actualizar, paquete138,
`0.2.114-assisted-entry-tabfix` (no139). Inspección solo de metadatos de procesos
propios:12 zombis con UID/PID/startTicks/Z estables, exe ilegible, y PulseAudio
vivo bajo linker64; además app/logcat. No se leen RUNs sensibles ni credenciales.

El observador lee exe antes de reconocer estado terminal y ni siquiera retiene
estadoZ: puede devolver Unknown por un cadáver. El daemon de audio es un segundo
bloqueo: stop busca libpulseaudio.so en ps NAME, pero NAME es linker64 aquí.
El usuario aprueba el [plan revisado](../.agents/planning/launcher-quit-replay-plan.md)
el2026-10-23. C139 conserva botón de cuenta/Tab-fix, protecciones de sesión sensible
y JNI C137. Solo reconoce un grupo terminado si Z/UID/PID/starttime permanecen
estables y ambos status prueban Threads:1. El revisor independiente detectó que
un líder Z aislado no basta: puede conservar hilos vivos. Se incorpora esa prueba
adicional sin eximir procesos vivos ni desconocidos.

La receta aislada cambia únicamente el stop encolado de audio para solicitar `exit` por
el socket del propio componente, reutilizando su cliente y timeout. La ayuda local
del pactl empaquetado anuncia esa orden con sus bibliotecas configuradas; no se
ha emitido una orden de control en el dispositivo. Esa transformación no llegó a
la fuente canónica C139; se corrige arriba en C140. El cierre espera hasta30s,
fuera de UI, entorno retirado y prueba fresca de quiescencia antes de recuperar
el ticket/almacenamiento exactos. Jugar espera al cierre acotado del mismo intento;
no se borran locks/journals ni se matan procesos. Comparación A/B pausada.
Revisión independiente completada tras corregir la prueba de hilos. **33 JVM
finales pasan** (13 observador,7 lease/exclusión,13 entrada asistida), además de
40 casos host correctos tras ajustar dos tripwires; no es una suite global.
Build6m32s y auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2219 fuentes sin
cambios y178 payloads. Auditor admite solo el descriptor nuevo exacto, sin ampliar
prefijos. Código `3fc752b`; evidencia externa
`/home/adrian/wowforever-artifacts/arm64-prototype/client139-quit-replay-20261023-final/`.
APK484651163bytes, SHA256
`7a08285a49753f70cd88d0ac0c02fb09c5726afbb1606ba96b93d053fd17997f`.
Instalación autorizada con «Sí, instala» y completada con `adb install -r`, sin
borrar datos: versión139/`0.2.115-quit-replay`, UID10330 conservado y SHA del
base.apk instalado igual al auditado. Preflight sin juego vivo y sin servicios;
los12 zombis también prueban Threads:1. Recibo externo verificado. No se abre
la app desde el agente. **El usuario reproduce el mismo bloqueo Quit→Jugar en C139**;
no se considera corregido. El cierre real y Login siguen siendo pruebas distintas.
Resumen privado externo `quit-replay-20261023/observation-summary.json`.

### Búsqueda alineada — C137 compilada y auditada, sin instalar

El usuario aprueba portar la optimización observada en WoWserver («vale,
hagamoslo»). Se cambia únicamente el bucle del full scan: calcula la primera
posición absolutamente alineada de cada chunk, avanza según el alineamiento del
contrato y descarta por1/4bytes antes de comparar el ancla32B. Conserva C136:
regiones, cobertura, solape, cursor, budgets, pausas, disparador, validación480B,
aplicación32B y límites. No cambia Login, captura ni autoridad de escritura.
Se aplica el comportamiento ya aprobado US-002-FR-003/004/011; TC-003 explicita
la comprobación de alineamiento absoluto con chunks desalineados.

**106 pruebas nativas focales pasan**, incluida una regresión con las cuatro
posibles alineaciones de inicio y un almacén señuelo no alineado; se reutilizan
las pruebas de frontera, reanudación, cancelación y guardas. Reconstrucción:23
pruebas correctas y un pin C136 obsoleto corregido; ese único test repetido pasa
(24 casos correctos tras corrección, no suite global ejecutada). UI sin cambios;
no se repiten las48 JVM positivas de C135. Revisor independiente no disponible
por error de variante, por tanto revisión independiente pendiente.

Microbenchmark sintético del comparador aislado en host x86-64, fixtures256MiB:
medianas antiguo/nuevo1,613s/0,080s (ceros) y1,611s/0,082s (pseudoaleatorio), con
igual resultado. No mide el escáner completo, ARM64 ni Login: **no promete20× de
mejora en el dispositivo**. Los temporales de benchmark/tests desaparecieron
antes de archivarlos; resultados del stdout observado retenidos como resumen,
no como logs íntegros, en `verification-summary.json` externo.

Nativo nuevo con procedencia y ELF16KiB,1013336bytes, SHA-256
`be86859b38c03daeeac37032578e5ae0fe77782c98fc1337190e103d71bce472`.
Primera compilación APK interrumpida, sin proceso vivo ni APK; registro conservado
y reanudación posterior correcta en5min8s. Auditoría sin cambios:
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,2217 fuentes sin cambios y178 payloads.
APK484564983bytes, SHA-256
`6ad513e4999861bb48a2cde11d53cc3b1e663f42fd241011c0807d25a4dd4a8e`.
Evidencia externa `client137-aligned-search-20261023/`; snapshot de la receta
auditada C136 más tres overrides propios, sin incorporar cambios ajenos.
**En el cierre de C137 no se instaló ni probó en el móvil; seguía C136.**
Instalación y ensayo manual requieren coordinar cierre normal y mantener datos/UID.
La hipótesis de creación del bloque tras Login y el bloqueo Quit→Jugar siguen
sin resolución causal.

Actualización tras la entrega C138 por el otro flujo de trabajo: el usuario dice
que vuelve a aplicar unos segundos después de fallar el primer Login. Consulta
de paquete confirma138/`0.2.114-assisted-entry`, no137. La receta C138 conserva
el hash JNI C137; este diagnóstico no logró comprobar el hash del SO instalado
y no lo presenta como identidad binaria verificada.

Se consulta el RUN `1792762163466`: SEARCH_STARTED111845ms, APPLIED32 a179420ms
(67575ms tras búsqueda),42 INCOMPLETE y106 ALREADY_PATCHED en el snapshot.
El sondeo41 termina la pasada previa; el42 empieza una nueva y comunica un
candidato. Primer sondeo4129ms y sondeo del hallazgo3078ms. Son menores que en
los ejemplos C136, pero el ensayo no está controlado y C138 incluye entrada
asistida: no atribuir toda la diferencia a la optimización. El momento de Login
no está registrado; sigue sin probarse creación del bloque por Login.

Al detectar C138 y sus nuevas guardas de sesión sensible se elimina únicamente
la copia diagnóstica cruda recién creada por esta tarea; se retienen solo
contadores/tiempos públicos seleccionados en `reported-trial/summary.json`, bajo
el directorio externo C137. No seguir exportando RUNs crudos sin respetar esas
guardas. No se instala, reinicia, envía Login ni modifica configuración aquí.

### Prioridad de búsqueda — C136 instalada, resultados manuales mixtos

Experimento aprobado `.agents/planning/patch-search-priority-plan.md`, código
`93ecae3`. Visita primero regiones privadas, anónimas y escribibles según el
predicado existente del apply; después todo el resto de memoria legible. Orden
por dirección dentro de cada grupo, reanudación por grupo/dirección y fallback
sin reinicios de la fase preferente por cambios de mapas. No cambia la autoridad
de escritura, validación, pausa1000ms, presupuestos, límites ni disparador C135.

**105 pruebas nativas focales y24 host de reconstrucción pasan**. La ejecución
paralela inicial de toda la biblioteca tuvo121 correctas y1 fallo del autotest
histórico multiproceso (múltiples almacenes sintéticos); aislado pasa. No se afirma
suite global verde. UI sin cambios; no se repiten las48 JVM que pasaron en C135.

Nativo **nuevo**, no retag C127: procedencia before/after y ELF16KiB verificados,
SHA-256 `dc6ccae1f35ea4421ad4b9adf54c2d67f55011547273906a218d24926cc1f19c`.
Build APK5min54s, auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2217 fuentes sin
cambios y178 payloads, sin modificar el auditor. APK SHA-256
`e296f3e3214e3120a92f116ecf2062fce6c5dfb04463d1005110e5a7bc1701f5`.
Inputs aislados de C135 más cambios propios C136, evidencia externa en
`/home/adrian/wowforever-artifacts/arm64-prototype/client136-priority-scan-20261022/`.

**Revisión independiente bloqueada por límite de uso; resultados C136 mixtos.**
La mejora de tiempo sigue siendo una hipótesis. C135 conserva
la última prueba positiva de Login; la construcción no interactuó con el móvil.

Intento posterior de despliegue autorizado por el usuario: preflight C135 sin
guest vivo; `install -r` falla sin detalle y el dispositivo pasa a offline. La
reconexión Wi-Fi devuelve Connection refused. **La versión instalada al terminar
el intento es desconocida**; falta recuperar ADB y verificar antes de repetir.
`deployment-attempt.json` externo; sin borrar datos ni abrir el juego. C135 sigue
siendo la última referencia positiva, no una afirmación de la versión actual.

Conexión recuperada: se confirma que el intento interrumpido dejó C135. Tras
repetir preflight sin guest vivo, `install -r` C136 termina correctamente: versión136
/ `0.2.112-priority-scan`, UID10330 conservado y hash del `base.apk` idéntico al
auditado. `deployment-receipt.json` externo. Sin borrar datos, abrir el juego ni
enviar Login desde el agente; prueba manual pendiente.

Resultado posterior comunicado por el usuario: no llegó a encontrar/aplicar el
parche, terminó en «parche no aplicado» y Login fallido, con mensaje aproximado
«you have been disconnected». El intento no conserva el resultado positivo C135;
no se afirma causa ni resultado nativo concreto sin el JSONL. Lectura de evidencia
bloqueada por ADB desconectado y endpoint mDNS que rechaza conexión. Se detiene el
experimento sin nuevos cambios; volver a C135 se propone, pero no se ha ejecutado
sin autorización/cierre normal. No se declara aceleración ni Login C136 correcto.

Actualización posterior del usuario: «acabo de conseguir hacer funcionar la c136.
creo que encuentra la sección unos segundos después de hacer el primer login sin
el parche y que falle». Se conserva el intento fallido anterior y se añade este
resultado positivo comunicado, sin declarar regresión universal ni superioridad
temporal. La relación causal Login→aparición/accesibilidad del bloque es una
**hipótesis del usuario**, no prueba de cuándo se creó: también podría coincidir
con el progreso de lectura. No se exige ni automatiza un Login fallido, ni se
modifican lector/guardas a partir de esta observación. Falta correlacionar el JSONL
del intento positivo con los hitos manuales; no hay telemetría automática de Login.

Investigación posterior de solo lectura con ADB recuperado, versión136 comprobada:
se copian los JSONL existentes sin iniciar juego, Login ni lecturas de memoria.
Intento fallido `1792703844638`:101 INCOMPLETE y1 CANCELLED, ningún candidato
validado comunicado ni evento apply; termina180012ms después de iniciar búsqueda.
No es un fallo de escritura observado: nunca se intentó apply en ese registro.
Intento positivo `1792704498274`:41 INCOMPLETE, APPLIED32 a84215ms de búsqueda y90
ALREADY_PATCHED. La señal de arranque precede búsqueda5ms; el hallazgo sucede en
el primer sondeo de una pasada nueva, sin cambio de mapas observado en esa pasada.
C135 registró143802ms búsqueda→apply: esta ejecución C136 tardó59587ms menos,
pero el momento de Login no está registrado y no demuestra causalidad del orden.

Ambos intentos positivos C134/C135 también hallaron el candidato al comenzar una
pasada nueva. La hipótesis Login→creación/accesibilidad sigue siendo compatible,
igual que una aparición tardía seguida de revisita del escáner. Los contadores
no prueban ausencia previa ni identidad de la región entre pasadas; no usar
`anchorHits` del diagnóstico como prueba de ausencia en el full scan. Evidencia
externa privada/saneada en `client136-priority-scan-20261022/login-investigation/`.
Para distinguir causas se requiere un ensayo manual con instante de Login marcado
y condiciones comparables; no se ejecutó ni se automatizó ninguno aquí.

Ensayo manual posterior guiado por avisos del usuario: pulsa Jugar, comunica
«ahora está buscando», se propone esperar30s y el usuario avisa «le doy s login»;
después comunica fallo del primer Login, hallazgo del parche, confirma estado
aplicado/verificado y segundo Login correcto: «si y acabo de poder entrar».
No se automatizaron entradas. Los30s son el protocolo propuesto, no una duración
medida; los avisos de conversación no dan precisión de reloj del dispositivo.
Esto repite la secuencia comunicada Login fallido→aplicación→Login correcto,
pero no prueba que Login cause creación/accesibilidad del bloque. No hay control
sin Login ni telemetría del instante de Login. ADB volvió a desconectarse antes
de copiar el JSONL de este intento; no atribuirle los contadores/timings de los
intentos anteriores. No hace falta salir del juego ni repetir para conservarlo.

Con ADB recuperado se obtiene el registro propio del ensayo guiado, versión136:
`1792705391624`. Búsqueda a116109ms, candidato en el sondeo42 a199611ms y
APPLIED32 a199638ms: **83529ms desde búsqueda**, frente a84215ms del positivo
anterior. El sondeo41 termina la pasada previa a189856ms con mapsChanged; el42
inicia una nueva sin cambio observado y comunica un candidato. Este patrón de
revisita es consistente, pero no demuestra que el bloque existiera antes del
Login ni cuánto tardó desde ese Login (no hay marcador preciso).

El snapshot registra44 INCOMPLETE, un APPLIED,86 ALREADY_PATCHED y un DENIED
posterior a291129ms por comprobación de mapas, con0 bytes escritos y dos scans
posteriores; vigilancia ENDED a296112ms. El rechazo tardío conserva las guardas,
no deshace la clave anterior ni prueba desconexión: el usuario confirmó entrada.
No se afirma mantenimiento indefinido. Evidencia privada y resúmenes saneados
`guided-*` bajo el directorio externo de investigación C136. Sin interacción con
el juego, credenciales, configuración ni memoria desde el agente.

### Arranque observado de WoW — C135 instalada, parche y Login confirmados

Plan aprobado `.agents/planning/patch-wow-readiness-plan.md`, código `881e501`.
C135 / `0.2.111-wow-startup` sustituye el Enter por una señal automática: ventana
WoW identificada por clase, mapeada, del tamaño del plan congelado y con actividad
de contenido. Se documenta como **arranque observado de WoW**, no como Login listo,
píxeles presentados, identidad nativa demostrada ni autorización para escribir.
La interfaz permanece pendiente hasta la señal; verificado sigue requiriendo
APPLIED/ALREADY_PATCHED de apply. El reenvío de teclas se conserva.

Mismo JNI C127, opt-in, captura independiente, intervalo y límites temporales.
Tiempos relativos/monotónicos de observación, búsqueda, polls y aplicación permiten
medir sin repetir el rastreo de claves. No se promete todavía una mejora en segundos.

**48 JVM y34 host focales pasan**, build correcto6min52s. Auditoría
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2217 fuentes sin cambios,178 payloads.
APK SHA-256 `02ff390df68fa98f30447f8b56790fbb27dd739216b46067b8ff89e360f5c8e1`.
Reconstrucción aislada del mismo origen usado para C134 más archivos propios,
sin incorporar trabajo concurrente ajeno; evidencia externa en
`/home/adrian/wowforever-artifacts/arm64-prototype/client135-wow-startup-20261022/`.
Inspección local realizada; **revisión independiente bloqueada por configuración
del revisor**. El usuario autoriza después instalar y probar pese a esa revisión
pendiente. Instalación `install -r` correcta, sin borrar datos: versión135,
UID10330 conservado y hash del `base.apk` igual al auditado; sin guest vivo en el
preflight (solo app, logcat, audio y zombies). `deployment-receipt.json` externo.
El agente no abrió el juego ni envió Login. No se declara suite global verde.

**Ensayo manual C135:** el usuario observa pendiente durante la pantalla negra y
buscando al aparecer WoW. El primer Login, aproximadamente dos minutos después,
falla; unos treinta segundos más tarde aparece aplicado y el siguiente Login
funciona. Consulta posterior de solo lectura del JSONL del mismo intento
`1792701487652`: señal de arranque a110161ms, búsqueda a110171ms, primer resultado
INCOMPLETE a119579ms (poll9408ms), APPLIED32 a253973ms, seguido de34
ALREADY_PATCHED. Los81 scans registrados son INCOMPLETE; esto no impide que el
nativo valide y aplique un candidato observado. Resumen externo saneado
`successful-login-summary.json`, JSONL privado fuera de Git.

La búsqueda empieza10ms después de la señal observada y la aplicación llega
143802ms después del inicio de búsqueda (unos2min24s). El disparador automático
y Login tienen evidencia positiva en este dispositivo; **no se demuestra una
aceleración frente a C134**, ni se explica todavía la espera del lector. No se
amplían plazos ni se cambia la lectura a raíz de este ensayo. La revisión
independiente sigue pendiente; una prueba positiva no la sustituye.

### Comparación temporal C127 — C134 instalada, parche y Login confirmados

Comparación aprobada en `.agents/planning/done/patch-c127-regression-plan.md`: conservar
C133 y cambiar solo el inicio de las lecturas, que vuelven a esperar el primer
Enter del juego como en C127. `nativeBegin` sigue precediendo a Wine; captura,
límites, opt-in, validación y biblioteca nativa permanecen sin cambios. El estado
es pendiente mientras espera Enter y buscando solo cuando comienzan las lecturas.
No se resetean datos ni gráficos, ni se reinstala la APK C127 completa.

La hipótesis aún no es una causa probada. C134 / `0.2.110-c127-enter-gate` está
instalada y recupera una prueba real de parche aplicado y Login. No se encadenan
cambios del lector ni se repite el rastreo de claves.

Código `b92e193`. Revisión de concurrencia sin bloqueo de producción; se ajustaron
dos expectativas de pruebas para la nueva secuencia pendiente → buscando → final.
**46 JVM y 24 host de reconstrucción pasan**; los fixtures/UX focales conservan
27 correctas y 6 fallos previos conocidos, sin afirmar suite global verde.
Build final correcto en 6min54s. Auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:
2215 fuentes sin cambios y 178 payloads, incluido el mismo nativo C127.
APK SHA-256 `0c2eca5a7f573311a976e13f75c330a5bde76fbd25e20743da1b747d7f52a8d4`.

La reconstrucción inicial rechazó correctamente dos payloads alterados por trabajo
ajeno de privacidad. Se preservó ese trabajo y se reconstruyó sin relajar paridad
desde un snapshot de `9e168db` más los cinco archivos propios C134. Manifiesto de
hashes, receipt, build, XML, auditoría y `deployment-receipt.json` externos en
`/home/adrian/wowforever-artifacts/arm64-prototype/client134-c127-enter-gate-20261022-isolated/`.
Tras confirmar el cierre normal y comprobar ausencia de guest activo/servicios,
se instaló con `install -r`, sin borrar datos: versión134, UID10330 conservado y
hash del `base.apk` igual al auditado. El agente no abrió el juego ni envió datos.

**Resultado físico:** primero se comunica fallo de Login y búsqueda durante la
pantalla negra. El registro del mismo intento `1792698913622` muestra después
41 scans INCOMPLETE, APPLIED32 y mantenimiento; sin modificar la APK ni reiniciar
desde el agente, el usuario pide esperar y confirma: «tardo un poco más tras el
segundo enter ponía parche aplicado y si funcionó el login». El snapshot posterior
registra un APPLIED de 32 bytes y 66 ALREADY_PATCHED. Evidencia saneada externa:
`successful-login-summary.json`; JSONL completos privados, fuera de Git.

La comparación queda cerrada con resultado positivo en este dispositivo. No prueba
causalidad única del adelanto C128 ni estabilidad general. La demora y el estado
buscando durante la pantalla negra no se declaran corregidos; no se inicia otra
investigación ni se alteran límites o validaciones dentro de este ensayo.

### Robustez de sesión de parche — C133 instalada; Login fallido sin aplicación

Lote A aprobado tras la auditoría de memoria: cancelar la sesión del parcheador
cuando falla el arranque, aunque Wine no llegue a emitir salida normal, y mostrar
el estado real del intento actual. Un hallazgo de escaneo no equivale a aplicación;
la confirmación depende del resultado de `nativeApply`. El fin de la vigilancia
debe distinguir aplicación verificada previa de ausencia de aplicación y no
significa que se deshaga una clave ya aplicada. Los fallos de cierre conservan
el error y la exclusión nativa. Callbacks antiguos no pueden reemplazar el intento
actual. Plan: `.agents/planning/done/memory-patch-hardening-plan.md`.

Sin cambios del opt-in (desactivado por defecto), límites temporales, captura de
solo lectura, nativo C127, contrato, runtime ni autoridad de escritura. Los riesgos
del CLI histórico y las políticas de mantenimiento se difieren a otro lote. Se
ha reconstruido, auditado e instalado una candidata nueva. El ensayo manual C133
falla sin aplicación efectiva del parche; no se atribuye todavía una causa exacta
a ese resultado ni al fallo C131.

Revisión independiente sin bloqueos finales: se corrige el orden de publicación
inicial, DENIED vuelve a búsqueda con historial, y las respuestas tardías del mismo
intento refinan historial/error terminal sin reactivar vigilancia. Un error al
cerrar permanece FAILED aunque después llegue prueba de aplicación. El estado usa
datos tipados y textos fijos, sin direcciones ni errores nativos en pantalla.

**57 JVM pasan** (46 de UX/sesión y 11 de perfiles), más las 24 pruebas host de
reconstrucción y las 8 del auditor. El conjunto focal de fixtures/UX conserva
27 correctas y 6 fallos previos conocidos (5 fixtures del permiso ancla y 1 texto
antiguo de autologin): no se afirma suite global verde. La primera ejecución JVM
detectó una carrera de espera en una prueba nueva; se corrigió la espera de la
notificación terminal y una reconstrucción fresca completó build y tests en 4min33s.

Auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2215 fuentes sin cambios y 178
payloads. Se admiten exactamente los dos tipos observacionales nuevos y sus dos
lambdas compiladas, manteniendo rechazo de clases vecinas y sin ampliar prefijos.
APK SHA-256 `c380419e2c65319fbd0757ae112a3cf38c2038835d91d269681b168e931034b7`.
Receipt, build, XML y auditoría finales externos en
`/home/adrian/wowforever-artifacts/arm64-prototype/client133-patch-status-20261022-final/`;
el primer intento fallido se conserva en el directorio sin sufijo `-final`.
Instalación por ADB Wi-Fi mediante `install -r`, tras verificar quiescencia y sin
borrar datos: UID10330 conservado, versión133 y hash del `base.apk` coincidentes.
`deployment-receipt.json` externo conserva la verificación. El agente no abrió el
juego ni introdujo credenciales; el usuario realizó el ensayo manual.

**Resultado físico:** escritor habilitado, captura desactivada, estado «buscando»
sin «verificado» y Login comunicado como fallido. El snapshot posterior del mismo
intento tiene 238 `patch_scan`: 2 NOT_FOUND, 6 REJECTED de identidad al arrancar y
230 INCOMPLETE/read_coverage. Cero anclas y candidatos observados, ningún
`patch_apply`: el parche no llegó a aplicarse. El lector registra huecos y cambios
de mapas; en el último poll también agota presupuesto. Esto no prueba ausencia
de claves, cambio del contrato ni fallo de los validadores; la causa exacta sigue
abierta. `manual-login-summary.json` externo contiene estados/contadores saneados;
los JSONL completos permanecen privados, fuera de Git.

Buscar durante la pantalla negra es intencionado: Begin precede a Wine. No exigir
«verificado» antes del Login: aún no está demostrado que las claves existan antes
de autenticar. La indicación inicial de esperar se corrigió y se hizo un Login
manual sin ese requisito. El lote A queda completado, no la corrección de conexión.

**Comparación con el éxito ya obtenido, no una nueva búsqueda de claves:** se ha
recuperado `run-1792615126263.jsonl` de C127: 39 scans INCOMPLETE, después
APPLIED32 y 98 ALREADY_PATCHED. Ese éxito tampoco necesitó cobertura global
completa. El nativo auditado es idéntico; los 16 campos comparados del lanzamiento
(perfil, opciones, Wine/DXVK, ejecutable nominal y argumentos, entre otros)
coinciden con C133. El USB conocido figura montado en la comprobación actual.
Eso no prueba igualdad de los contenidos de la configuración o del PE; el intento
de obtener su hash por la ruta del sandbox no tuvo acceso y no se toma como prueba
de que falte el ejecutable.

La última versión con Login positivo documentado sigue siendo C127. C128 no se
instaló, C129 se instaló sin ensayo positivo y C130 no se instaló. El cambio de
sondear antes de Enter, introducido en C128 y heredado por C131/C133, no tiene un
control positivo intermedio. Es una diferencia a aislar, no una causa demostrada.
Comparación saneada externa: `known-good-comparison.json`.

### Ajustes sin bloqueo por preparación interna — C132 lista, sin instalar

El usuario aprueba descartar automáticamente la preparación sin lanzar al entrar
en resolución o confirmar un reset gráfico. Una preparación terminada es un
snapshot sin recursos guest; no debe mostrarse como «preparación activa» ni exigir
que el usuario la gestione. Los ajustes siguen excluidos durante trabajo real.
El reset intentará recuperar una reserva guest residual únicamente con las mismas
pruebas existentes de destino, propiedad y quiescencia, sin pedir pulsar Jugar antes,
sin borrar locks ni forzar procesos. Plan:
`.agents/planning/done/launcher-preparation-settings-plan.md`.

Implementado sin cancelación compleja: la entrada en Ajustes ya excluye preparación
real y su IO. Solo se descarta el snapshot sin ticket/reserva guest y se invalida
el consentimiento. El reset conserva la doble prueba de runtime inactivo y su
lease de IO. Pruebas host: 20 pasan/1 fallo previo de texto en autologin; perfil y
fixtures históricos: 9 pasan/5 fallos previos del permiso ancla. Las 32 pruebas de
reconstrucción/auditoría pasan. No se afirma suite global verde ni prueba física.

Código y pruebas en `dd41aab`; revisión independiente sin bloqueos. Reconstrucción
canónica y compilación correctas (6min34s), **28 JVM pasan**: 7 de exclusión de
operaciones, 10 del runtime gate y 11 de display-fit. Auditoría
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2215 fuentes sin cambios y 178 payloads.
APK SHA-256 `58aa1fec5d59342aafb5af269b91383d24b1a83bc13eb7e5f843c2ff508a6a49`.
Receipt, build, XML y auditoría externos bajo
`/home/adrian/wowforever-artifacts/arm64-prototype/client132-settings-preparation-20261022/`.
No se instala ni abre la APK: ADB desconectado; la prueba física sigue pendiente.

**Login C131 comunicado como fallido por el usuario:** posible regresión, sin causa
confirmada. La comparación C130→C131 no muestra cambios en el hook, sesión JNI,
contrato o binario del parche; sí cambia la reutilización del perfil derivado. Eso
no demuestra que el parche se ejecutara en el intento fallido ni identifica su
causa. En esa comprobación ADB no tenía dispositivo conectado y el antiguo puerto
Wi-Fi rechazaba la conexión; falta capturar el error exacto y los eventos del intento. Esta corrección
de Ajustes no se presenta como arreglo del login. No se resetean datos, credenciales
ni configuración para diagnosticarlo.

### Corrección de resolución y gráficos — C131 instalada; prueba del juego pendiente

Alcance aprobado: selector de la APK exclusivamente de resolución, defaults
gráficos iniciales mínimos/sin sombras/30 FPS, conservación de los cambios que el
juego guarde en el perfil derivado y restauración explícita solo de gráficos con
la sesión cerrada. No debe reconstruirse un perfil existente desde la base de
solo lectura durante preparación ni al aplicar una resolución.

**Franjas sin causa aislada:** la evidencia histórica de code74 ya igualaba
desktop XServer, ventana y CVars en 1564×716 sobre superficie 2360×1080; Nativa
2360×1080 tampoco las eliminó. El renderer ascendente usa ajuste proporcional
`SCALE_FIT`, por lo que esa diferencia de aspecto de ~0,04 % no acredita la causa
de franjas grandes. Faltan posiciones de ventana, drawable/backbuffer y
transformación efectiva del renderer en el cliente actual, además de captura
completa. No se cambia runtime/driver ni se disimula el problema estirando o
recortando. La ruta `-config BetaFunctionalPoc-classic_forever.wtf` está comprobada
en código; falta probar físicamente qué guarda/carga el cliente entre sesiones,
el nivel visible 1, sombras realmente desactivadas y desaparición de franjas.
**C131 / `0.2.107-graphics-settings` instalada** por ADB Wi-Fi en xaga/22041216G,
Android14, mediante `install -r`, sin borrar datos (UID10330 conservado). Se esperó
al cierre normal confirmado por el usuario y se comprobó justo antes de instalar
que no había procesos guest vivos ni servicios de la app activos. No se abrió la
APK ni el juego. La versión instalada y el SHA-256 del `base.apk` coinciden con
el candidato auditado:
`41f4ba0c249e31b1abb08d676362d17ca92e983aa7a43e81ef03642e04eb2f53`.

Reconstrucción canónica y compilación correctas; **22 pruebas JVM pasan** (11 de
display-fit y 11 de perfiles). Auditoría
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2215 archivos de fuente sin cambios y 178
payloads verificados. La primera ejecución de Gradle quedó interrumpida sin APK;
la ejecución posterior en primer plano terminó en 5min57s. Para la auditoría se
restauraron tres entradas externas ausentes desde la reconstrucción, comprobando
sus tamaños y hashes fijados antes de copiarlas; no se debilitó ningún control.
Evidencia externa en
`/home/adrian/wowforever-artifacts/arm64-prototype/client131-graphics-settings-20261022/`:
`reconstruction-receipt.json`, `build.log`, `apk-audit.json` y
`deployment-receipt.json`. Código y pruebas en commit `9d62f68`.

Revisión independiente final sin bloqueos: el reset exige runtime inactivo
antes y después de adquirir el lease de IO, no recupera reservas antiguas y
bloquea la preparación, lanzamiento y cambios de almacenamiento mientras escribe.
Pruebas host del cambio: 21 correctas y 1 fallo ajeno de autologin; incluyendo
la receta histórica, 28 correctas y 6 fallos (autologin y cinco fixtures de receta
sin el permiso ancla `FOREGROUND_SERVICE_DATA_SYNC`). El worker reprodujo esos
fallos en HEAD limpio; se inspeccionaron los archivos ajenos sin cambios y se
guardó una reproducción representativa de cada causa en
`client131-graphics-settings-20261022/known-host-failures.log`. Las 32 pruebas de
reconstrucción/auditoría pasan. No se afirma suite global verde.

**C130 — recuperación de reservas desde la APK:** Jugar comprueba la exclusión
antes de preparar y recupera una reserva antigua de guest únicamente mediante la
API existente, con destino/propiedad coincidentes y quiescencia fresca. Wine
activo o desconocido bloquea con mensajes distintos; no se recuperan estados del
updater. El cierre mantiene su liberación asíncrona con prueba, sin el rollback
síncrono duplicado. Revisión independiente aprobada sin bloqueos de seguridad ni
concurrencia. Es implementación y validación local, **no prueba física de que la
incidencia C129 esté resuelta**: el usuario aplaza instalación y ensayo hasta
reconectar el móvil.

Candidata `130 / 0.2.106-guest-recovery`:32 pruebas host focales correctas,
131 JVM sin fallos/errores/omisiones, build6m28s y
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`. APK SHA256
`36267225b90853d9fe95b266e3ce594a154365fe5b52a167c3f441cc592e823d`.
Receipt, build, XML y auditoría externos bajo
`arm64-prototype/client130-guest-recovery-20261022/` en
`/home/adrian/wowforever-artifacts/`. No se amplió la admisión DEX ni se cambió JNI.
La suite global no está verde: una ejecución previa al arreglo del test del pin
de versión dio43 fallos/1355 correctos/4 omitidos y otra44 cabeceras de fallo sin
resumen. No hay comparación con el commit base ni suite global posterior al
arreglo: no se afirma que todos esos fallos sean preexistentes. Quedan pendientes
prueba directa de la orquestación y los ensayos físicos; una negativa de la guarda
local aún puede mostrar el mensaje genérico de cierre.

**Incidencia posterior a C129 — bloqueo de Jugar:** dos intentos alcanzan
`display_plan_applied` y fallan con `launch_blocked`: «recover the unfinished
client operation before launching the guest». La operación durable seguía
`GUEST_STARTED/UNKNOWN/proc_exe_unreadable` de la sesión anterior; no es prueba de
WoW vivo. ADB solo veía el launcher y su lector logcat bajo UID10330. Recuperación
autorizada por el usuario: observador de la propia app `Quiescent`, llamada a
`recoverPreviousGuestOperation` mediante depurador y estado posterior `IDLE`
confirmado, sin editar/borrar registros ni reiniciar. Las16 pruebas focales de
OperationLease/RuntimeGate pasan sin fallos/errores/omitidos. El informe remitido
tiene recibo local, pero `latest-intent` quedó `cancelled/display/error=null` pese
a que el log PoC sí conserva la causa: diagnóstico UX pendiente de mejora.
ADB volvió a quedar offline antes del clic de prueba en Jugar: **no se confirmó
todavía otro arranque del juego ni una solución permanente al cierre/recuperación**.
Evidencia saneada externa en `work/client129-play-blocker-20261021/`, bajo
`/home/adrian/wowforever-artifacts/`.

**Despliegue C129 (21oct, 22:41 UTC):** recompilada tras corregir el import
`layout.ime` y registrar únicamente la clase de controles de teclado existente en
la auditoría DEX. Instalada por Wi-Fi ADB con `install -r` (sin borrar datos) en
xaga/22041216G, Android14, tras comprobar ausencia de procesos del UID de la app.
Android confirma `129 / 0.2.105-reuse-preparation`; SHA256 del APK instalado y del
auditado idénticos:
`95e2b4fef825598ee119b7bd59dda6837a7841606b49287205c8c7860e6fd76b`.
Build6m22s;241 tests JVM,0 fallos/errores y1 omitido;8 tests host del auditor
correctos y `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`. Se mantienen seis fallas del
lote host exploratorio registradas y aceptadas por el usuario: no es suite global
verde. No se abrió la app ni se probó teclado/Wine/WoW. Receipt y logs externos en
`arm64-prototype/client129-wifi-adb-ime-fixed-20261021/` bajo
`/home/adrian/wowforever-artifacts/`. Las referencias a C129 «no instalada»
inferiores describen el corte anterior.

**Resultado principal:** cliente Windows ARM64 `1.60.1.70450` ejecutado en Android,
autenticación Classic Forever y entrada al juego confirmadas por el usuario en
C127. No observó glitches gráficos dentro del juego. No equivale a una sesión
medida de30–60min, una cifra de FPS ni validación de audio/mando/táctil.

### Avances incorporados

| Área / versión | Avance y límite de lo acreditado |
|---|---|
| Provisión del cliente | NGDP/TACT sin Battle.net, producto `wow_classic_beta`; C117 terminó la publicación de70450 ARM64/enUS en USB. No está descargando todavía. |
| UX y controles del launcher | Launcher vertical/juego horizontal, ajustes simplificados, selector de almacenamiento y resolución adaptativa, bienvenida/diagnóstico GPU y presets. Los presets no son pruebas de FPS/audio/mando y el autologin de la APK sigue pendiente. |
| Reutilización por USB | «Preparar carpeta», instrucciones para PC y revisión de `Data/` + `.build.info`; reutilización en el sitio y cálculo del delta. Implementado/testeado; falta completar el ensayo físico de pegar otra instalación. |
| C118 — reconocer70450 | Se abandona el veto del ejecutable70009 para instalaciones gestionadas: admisión privada vinculada a build y contenido, comprobación ARM64/MD5 y `.build.info`; creación de WTF solo después de probar el cliente. |
| C119–C120 — primer uso | Implementado en C119 y ejecutado en C120: Jugar crea/recupera contenedor y prepara Wine. ImageFS se descarga desde la app (~175MiB), sin inflar la APK con ese archivo; reutilización de caché comprobada en C121. |
| C120 — identidad del USB | UUID/ruta como identidad persistente; dev/inode/mount congelados solo por operación. Migración acotada del registro antiguo con prueba de36 archivos sueltos y metadatos, no rehash completo de CASC. |
| C121 — lanzamiento | El panel «Listo para iniciar» acepta la preparación fuerte aunque la inspección superficial siga diciendo integridad no verificada; conserva consentimiento y revalidación. |
| C122 — CASC/KMT | Corrección permanente de escritor, estimación y reanudación: reserva de48KiB después del bloque ordenado alineado; desaparece el rechazo nativo de sección truncada. Ocho regresiones focales, incluida metadata de1.319.619 objetos. |
| Primer arranque nativo | Se reúnen los1.504 nombres de índices CDN referenciados, luego se observan lecturas de archivos CASC locales, creación D3D11 y cinemática. La presencia de esos índices no equivale a una verificación completa por hashes; no fue otra descarga completa del juego. |
| C123 — salida | Presentación/orientación despachadas al hilo principal; desaparece el crash Java observado al cerrar desde el hilo de espera Wine. La limpieza de todos los hijos aún tiene pendientes. |
| C124–C125 — observación | Ventanas/cancelación acotadas, lector progresivo de todas las regiones legibles y procedencia verificable del JNI; conserva cursor entre tandas y declara huecos/cambios, no ausencia global ficticia. |
| C126 — almacén real | Regla compartida: byte37 variable, bytes38–39 cero; IDs/claves/flags intactos. Referencia inicial x86_64; después se reconoce el bloque completo ARM64 en3 polls. No se exportó el valor del37 ARM64. |
| C127 — compatibilidad Forever | `nativeApply(token)` escribe solo32 bytes públicos del grupo8 y comprueba los480 bytes del bloque. Mantenimiento/idempotencia, prueba fresca de proceso/mapeo, fallo cerrado y revisión nativa/Kotlin. Login/mundo confirmados tras APPLIED y ALREADY_PATCHED. |
| C128 — adelantar el parche | Implementado en Kotlin, revisión cerrada,32 tests JVM correctos y APK compilada/auditada. El escritor empieza antes de Enter; observador/captura conservan su comportamiento. Todavía no instalada ni probada físicamente. |
| C129 — reutilizar preparación | Jugar integra la preparación local, sin paso previo «Preparar WoW»; conserva recursos existentes y evita recorridos completos intermedios, manteniendo resolución fuerte y puerta final. Versiones remotas solo mediante «Buscar actualizaciones»; Jugar no obliga a adoptar la última.127 tests JVM correctos y APK compilada/auditada. No instalada; rapidez física aún no medida. |
| Control/diagnóstico host | Navegación con scrcpy oficial/UHID, pruebas de foco/parpadeo para70450 y un envío autónomo autorizado comprobado antes de resolver grupo8. Herramientas host revisadas, entrada efímera sin guardar valores; no constituyen autologin incorporado a la APK. Los Logins positivos finales fueron manuales. |

La comparación host de planes ARM64 completos70009→70450 estimó ~1,934GiB de
objetos faltantes frente a ~84,52GiB de objetos codificados del objetivo. **No**
es el tamaño medido del USB, el delta garantizado de cualquier copia ni una
verificación física; excluye costes sueltos/metadata. Una copia completa vieja
puede bloquearse si hay colisiones de archivos sueltos sin propiedad acreditada.

### Ruta validada y ubicaciones

- App funcional `com.wowforever.arm64probe`, no la app diagnóstica
  `com.wowforever.launcher`. Fuente real en `scripts/arm64-prototype/app/overlay/`
  y `updater-gate/`, puente en `crates/forever-patcher-jni/`.
- Dispositivo del ensayo: xaga/22041216G, Android14, GPU Mali-G610MC6. Cliente
  Windows ARM64, Proton11.0-90624 ARM64EC, DXVK2.4.1-wow-aarch64-test/PanVK/noGL,
  D3D11. No es la ruta histórica x86_64/Box64/PowerVR.
- Cliente: `/storage/6AD6-EF0E/Android/data/com.wowforever.arm64probe/files/CustomGames/WoW Forever/`.
  Ruta PC: `Android/data/com.wowforever.arm64probe/files/CustomGames/WoW Forever/`.
  No copiar marcadores/transacciones privados de otra instalación ni el WTF ajeno.
- Perfil Classic Forever, portal `auth.gpon.com.co`. Configuración derivada
  `WTF/BetaFunctionalPoc-classic_forever.wtf`; no parche de `Config.wtf` ni del EXE.
- Referencia pública contrastada con el contrato; no se instaló una DLL externa,
  no se desactivó TLS ni se cambió runtime/driver para resolver el grupo8.

### Verificación y artefactos del corte

- C127:29 pruebas JVM de UI/sesión,32 host de reconstrucción/auditoría, revisiones
  independientes y `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`;2215 fuentes base y178
  payloads controlados. No es una afirmación de suite completa verde.
- JNI C127: API29/NDK24,23 hashes fuente estables, alineación16KiB y8 exports
  comprobados. SHA256 `2f7014d4133ba17d668e193b5aea0e7290d5a093168c58ace22b83647c69bd67`.
- APK C127: `arm64-prototype/client127-guarded-group8-reviewed-20261021/`
  externo, con receipt, tests, log Gradle y auditoría. Los nombres de los subdirectorios
  están bajo `/home/adrian/wowforever-artifacts/`, nunca dentro del repositorio.
- Prueba positiva: `work/client127-usb-retry-20261021/first-application-observation.json`
  y `run-1792615126263.jsonl`; APPLIED seguido de ALREADY_PATCHED, mismo hijo vivo y
  USB montado antes del Login positivo comunicado por el usuario. No se exportaron
  credenciales ni imágenes de sus campos para documentar el resultado.
- Siete hashes privados preservados al instalar; recuperación de la reserva solo
  mediante la API existente tras quiescencia del UID. No se editaron/borraron leases.
- Artefactos C128 en `arm64-prototype/client128-early-group8-reviewed-20261021/`;
  compilación4m44s,32 tests JVM sin fallos/errores/omitidos, dos comprobaciones host
  focales (versión/pins y tripwire) y auditoría correctos, sin nuevas excepciones.
  Su nativo sigue siendo el de C127. APK484443715B, SHA256
  `6a4e842f84f174d4df293a72c4ed0a4935ac9a1da29d646efc15a6357fde8d59`.
  No sustituir la APK mientras el usuario juega.

- C129: `arm64-prototype/client129-reuse-preparation-reviewed-20261021/verified/`
  externo, con receipt, log Gradle, resultados XML y auditoría
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`; compilación5m33s,127 tests JVM
  (almacenamiento76, resolución10, UX33, perfiles8), sin fallos/errores/omitidos.
  APK484473675B, SHA256
  `c3c4b6edfca4b19879fc016cd22a06a3087f0bde634b0ea1c1492b2d6ec6606b`.
  Mantiene JNI C127,2215 fuentes base y178 payloads controlados; revisión
  independiente sin bloqueantes. Políticas del gestor:5 pruebas host correctas.
  Las suites globales de los builders no están verdes; estos resultados focales
  y de auditoría no equivalen a una suite global correcta. No se tocó el dispositivo.
  Duraciones futuras en el evento `prepare_stages` del log existente; faltan dos
  aperturas en frío coordinadas para medir preparación/lanzamiento y probar sin red.

### Pendientes reales

1. Cuando el usuario cierre WoW, instalar C128 y comprobar
   APPLIED **antes** del primer Enter y resultado del primer Login. No está probado
   todavía que el almacén exista antes de autenticar.
2. Sesión30–60min: FPS, memoria/temperatura, audio, controles y estabilidad del mundo.
3. USB: hubo desconexión/remontaje acreditados que interrumpieron un ensayo;
   cable/hub/alimentación/controlador no aislados. No atribuir ese cierre al parche.
4. Cierre Wine/PulseAudio/zombis y recuperación normal de guest sin depender de
   depuración. No confundir ausencia de ventana con quiescencia ni borrar reservas.
5. Optimización del descargador de una sola pasada: **no implementada**. No repetir
   verify/repair/descarga completa para perseguir un bloqueo ya resuelto.
6. Prueba física de importar otra instalación, comportamiento ante otros dispositivos
   y acabado one-click/UX (incluida pantalla de carga, aún propuesta separada).

Planes: [compatibilidad cerrada](../.agents/planning/done/arm64-server-identity-plan.md),
[CASC cerrado](../.agents/planning/done/casc-native-index-repair-plan.md),
[adelanto aprobado en curso](../.agents/planning/early-group8-apply-plan.md).

## Detalle de versiones e historial

**C127 instalada (`0.2.103-guarded-group8-apply`):** aplicación guardada de la
clave pública del grupo8 implementada/revisada, separada del lector.29 tests JVM,
32 host de procedencia/auditor y auditoría APK correctos. SHA256
`bcc67f3f05c581267f83ff3a1588eed7821be3c2f617fea75d5c7d59bcf18ff7`.
Siete hashes privados preservados, mismo boot y reserva recuperada por la API
existente sin editar registros. **Login confirmado por el usuario («funcionó!!!»)**
en el reintento con USB conectado y después de verificar aplicación/mantenimiento
de la clave. Primera prueba: APPLIED32 bytes y54 ALREADY_PATCHED, interrumpida
por desconexión USB antes del reintento. **El usuario confirma después acceso al
juego y ausencia de glitches gráficos dentro del juego.** No hay duración/FPS
medidos ni validación de estabilidad prolongada. Pendiente buscar/aplicar antes del primer Enter: C127 aún exige esa
activación y puede necesitar un reintento manual. Detalle y evidencia en
[avance Forever](arm64-forever-connection-progress.md).

**C128 lista y auditada, no instalada:** el usuario aprueba adelantar la búsqueda/
aplicación al arranque, sin esperar Enter, solo para el escritor autorizado.
Conserva el nativo y validaciones de C127, máximo total15min y corte3min tras
el primer Enter. El observador sin escritura y la captura mantienen sus tiempos.
Falta verificar aplicación antes del primer Login en el dispositivo; no se
interrumpe la partida actual. [Plan](../.agents/planning/early-group8-apply-plan.md).

**21oct — C126 instalada (`0.2.102-variable-padding`):** corrige la validación del
relleno: byte37 variable,38–39 cero, sin relajar claves/IDs/flags. Procede de la
reparación70450 x86_64 de wowserver; no habilita escrituras en el observador.
La prueba posterior reconoce un bloque estricto de12 entradas en3 polls,
pero con recorrido global incompleto, no unicidad ni el valor concreto del37.
Login manual vuelve al formulario; la aplicación guardada fue aprobada después
y se incorpora a C127, ya instalada y con Login positivo comunicado por el usuario.
Tests/revisión, compilación y auditoría APK
correctos; SHA256 `7ad48a455bd0ecc95dc5a1db0a0c406caa4c8236da080cedd4c266bbdf599725`.
Instalada conservando7 hashes privados, mismo boot y reserva recuperada por la
API existente. Un nuevo lanzamiento y Login manual realizados,87 polls sin escribir.
La prueba manual C125 volvió al formulario, con fallo de identidad grupo8 en su
log cerrado;92 polls incompletos sin almacén validado, no ausencia demostrada.

**Actualizado: 2026-10-21.** Punto de entrada para el trabajo de UX y gestión del
cliente. [Plan aprobado](../.agents/planning/arm64-launcher-app-polish-plan.md),
[estado técnico](arm64-forever-status.md) e [índice](README.md).

## Historial C123: identidad ARM64 pendiente

**Prueba autónoma21oct:** se envió una sola vez la cuenta autorizada por entrada
UHID guardada, sin valores en archivos/capturas persistentes. Los eventos públicos
extraídos al cerrar confirman login completado18:11:30.105, lista de reinos y
conexión al reino; **18:11:37.410 rechazo de identidad del servidor, grupo8**.
No se llegó a selección de personaje. CASC y autenticación de cuenta superados;
la adaptación de clave ARM64 continúa sin implementar. Observador agotado antes
del Login: no hay evidencia de memoria de esa ventana.

Cierre normal comprobado sin nuevo reinicio: C123 sigue viva y no aparece el crash
de UI anterior en su PID. Limpieza aún incompleta: quedan procesos `Z` bajo la app
y PulseAudio propio activo; reserva `GUEST_STARTED/proc_exe_unreadable` conservada. No
anunciar quiescencia ni eliminar guardas. Evidencia actual y límites en
[registro de conexión](arm64-forever-connection-progress.md).

### Historia de instalación y recuperación anterior a esta prueba

El usuario aprobó el plan de adaptación. La clave pública actual del script HTTPS
de Classic Forever coincide con el contrato; falta demostrar su objetivo ARM64.
C123 `0.2.99-main-thread-exit` conserva la corrección CASC de C122 y corrige el
despacho de presentación al hilo principal. Regresión focal aprobada (1 test),
compilación y auditoría correctas. APK484388299B, SHA256
`a00c2590b476cc9df1b1a1908e2f78f0b32b56b626df1f0c318afcc6e89c3263`.
Informe y receipt contrastados con los archivos reales. Instalada el21oct por
192.168.1.130:37601: versión123 y7 hashes privados conservados. No hay clave
modificada; falta comprobar físicamente el cierre.
El guest durable tras el crash sigue `GUEST_STARTED/UNKNOWN`; antes de otro arranque
se necesita recuperación por la API existente y un boot nuevo, no borrar guardas.

ADB recuperado por mDNS en37601. La recuperación operativa aún no ha llegado a
ejecutarse: primero objeto Kotlin no inicializado en el depurador y después
onResume no alcanzado con pantalla Asleep/keyguard. No se borró la reserva.
Reinicio enviado por ADB para el siguiente intento limpio; pendiente confirmar
nuevo boot y endpoint tras desbloqueo. Sin cambios de seguridad, nuevos logins ni
credenciales en archivos de trabajo. Evidencia: `work/client123-server-identity-20261021T054157Z/`.

**23:08–23:10 CEST, registros tras Exit Game:** se confirma la causa del retorno:
`Connection.log` registra login completado y lista de reinos a22:54:48, conexión
al reino a22:54:49 y **«Protocol error: server identity check failed regionGroup="8"»**
a22:54:55.382. Se superó la autenticación de cuenta; el bloqueo siguiente es la
identidad del servidor. No demuestra todavía layout ni método de parche ARM64.
[Plan aprobado](../.agents/planning/done/arm64-server-identity-plan.md), todavía sin
cambios de claves. Solo se conservaron extractos filtrados de los logs.

La salida además produjo un **crash Java independiente** a23:05:18:
`CalledFromWrongThreadException`, hilo `gamenative-process-wait` →
`setWineGuiRunning` → `syncGamePresentation` → `AppUtils.hideSystemUI`.
`ApplicationExitInfo` confirma APP CRASH(EXCEPTION). A23:08 no quedan app/Wine;
bootId intacto, sin reinicio del teléfono. ADB recuperado en192.168.1.130:36351.
No se ha vuelto a lanzar ni instalado otro APK; comprobar guest durable antes de
otro arranque y corregir la actualización de vistas fuera del hilo principal.

### Observaciones anteriores al volcado de logs

**Resultado comunicado por el usuario a22:57:** alcanza el formulario, envía
Login y ve «login to server»; vuelve a pedir contraseña sin error visible ni
selección de personaje. Entonces no se acreditaba autenticación completada; los
logs volcados posteriormente sí la confirman antes del fallo de grupo8. El proceso24130
sigue vivo; Connection/Aurora/WowConnection siguen0B. Solo se verificó el campo
portal del WTF derivado: `auth.gpon.com.co`. Las líneas estándar `fixme:secur32`
no identifican rechazo TLS ni causa del retorno. No se repitió el Login ni se leyeron
credenciales ni se capturó el formulario. La compatibilidad de70450 con ese servidor
no está confirmada; el feed público del contrato responde404, no un rechazo de build.
El usuario confirma que usó una cuenta de **Classic Forever**, no Battle.net;
esto aclara el tipo de cuenta, no valida la contraseña ni identifica el rechazo.

**22:47 CEST:** ya se ve la cinemática de WoW; dos capturas a22:47:25/33 muestran
fotogramas distintos, la segunda con subtítulos. `gx.log` confirma70450 ARM64,
D3D11 forzado y «Dx11 Device Create Successful» a22:43:37 sobre
`Wrapper(Mali-G610 MC6)`. Se supera el rechazo KMT inicial y hay presentación
gráfica real; la película no acredita todavía escena3D interactiva, login ni mundo.
Capturas y logs en `work/native-kmt-reserve-20261020/` externo. No reiniciar ni
instalar C122 mientras el intento siga activo.

C122 `0.2.98-native-index-reserve` compilada y auditada;8 regresiones focales JVM
aprobadas (incluida escala de1.319.619 registros) y5 tests host del auditor.
Revisión independiente sin bloqueantes. Ambos escritores y el cálculo de espacio
reservan al menos48KiB tras el bloque ordenado alineado; reanudación regenerará
índices de una finalización interrumpida. APK SHA256
`dd2b530cd07f8e04f0968c9d95f5eed7d9f1bc114159e18d12dd43acce32e560`, evidencia externa
`arm64-prototype/client122-native-index-reserve-reviewed-v2-20261020/`.
No instalar mientras siga vivo el intento actual en C121.

**22:42–22:43 CEST:** los1504 nombres de índices CDN referenciados están presentes
(1424 archives+78 patch+2 file-index; presencia, no verificación de hashes).
Después se observan lecturas nativas de data.014/data.019/data.002; no está parado
en la descarga de índices anterior. Todavía pantalla negra, sin login/render ni
log Tact volcado. No asumir completitud de todo CASC a partir de estas observaciones.

**20oct22:32 CEST:** tras el reinicio aprobado y copias exactas, añadidos48KiB
vacíos a cada uno de los16 índices (786432B totales), con guardas y publicación
verificada. No se tocaron data.NNN, ejecutable, admisión ni auxiliares del juego.
El nuevo intento mapea los16 índices y abre los89 archives; la traza confirma que
ahora prepara los índices CDN en `Data/indices` (942/1424, ~95,5MiB). El rechazo
KMT inmediato ya no es la frontera observada; pantalla aún negra y login pendiente.
No afirmar que todos los objetos CASC o el login se hayan validado. C122 incorporará
la reserva al escritor y al cálculo de espacio, con regresión y revisión.

El primer clic tras reiniciar fue bloqueado antes de Wine por la antigua reserva
GUEST_RESERVED. Se recuperó usando la API existente, con observación Quiescent y
destino coincidente, mediante depurador; no se borró el registro a mano. Falta una
ruta visible de esa recuperación en la UI normal; se registra como pendiente separado.

### Historial C121: rechazo inicial

**Resultado físico posterior:** el usuario avisa de un error y la captura confirma
«Your World of Warcraft installation is damaged». Hay proceso11603
`wine A:\WowB-ARM64.exe … -d3d11`, ventana Wine de clase `wowb-arm64.exe` y nuevos
Logs del cliente: el ejecutable sí arrancó, no se alcanzó login ni render3D.
`Tact.log` a21:45:43 informa «Truncated updated section in KMT file detected»,
«Failed to bind mapping table `0`» y rechazo del índice `Data/data` con NeedsRepair.
Se conservan captura y log en `work/client121-prepared-launch-20261020/` externo.
El propio juego creó `RepairMarker.psv` y `shmem`; no se ejecutó reparación,
no se modificaron índices manualmente ni se volvió a lanzar. La causa exacta
(formato del índice frente a corrupción física) sigue en investigación de lectura.

Comprobación local de copias de `0000000001.idx` y `0100000001.idx`: hashes del
encabezado y bloque ordenado correctos;71636/71469 entradas estrictamente ordenadas,
sin ubicaciones fuera de límites. Cada archivo termina exactamente al inicio
alineado de la sección de actualizaciones (reserva0). Esto descarta determinados
daños de escritura en esas dos copias, **no** demuestra compatibilidad nativa ni
que el cliente necesite una reserva concreta. No se han reescrito índices ni
verificado de nuevo los archivos CASC completos.

**20oct21:43 CEST:** C121 `0.2.97-prepared-launch` instalada; APK SHA256
`f7a17046e4d43983235c48e97e934af32d3edbb39e983374958ce3f16d9a3242`.
Compilación y auditoría correctas;20 pruebas host focales aprobadas (autologin
histórico excluido). Siete hashes privados idénticos tras instalar, incluida la
admisión v2. El primer Jugar reutiliza ImageFS y muestra el panel y consentimiento.
Un clic en Lanzar WoW a21:43:09 abre presentación negra; logcat confirma copia de
DLLs del Proton fijado al primer prefijo. Aún no es prueba de proceso WowB/render
ni login. No repetir el lanzamiento mientras inicializa el prefijo.

### C120: preparación e identidad durable

**20oct21:29 CEST:** instalada C120 `0.2.96-durable-storage-identity`, SHA256
`96edd094c09ef201b35d71ca3d343898a5efb07dcd84005205f7ee22b0ff3381`.
Separa UUID/ruta persistentes de identidades físicas congeladas por operación;
migra la admisión antigua solo tras comprobar metadatos y archivos sueltos,
sin volver a recorrer CASC. Guarda de journal real y revisión independiente
correctas.74 tests Storage finales aprobados;34 tests Service/UX/OperationLease
de la revisión anterior sin cambios y19 host aprobados (autologin histórico excluido).
Compilación y auditoría APK correctas. Seis hashes privados idénticos tras instalar.
Un Jugar reconoce70450 actualizado y ya supera el bloqueo de admisión: crea el
registrov2 conservando el anterior. A21:31 termina la preparación automática de
ImageFS y aparece «Entorno preparado». El panel de lanzamiento queda oculto por
una puerta de UI que exige READY a la inspección superficial, mientras esta sigue
correctamente en PRESENT_NOT_VERIFIED. Corrección focal C121 en curso: usar la
preparación fuerte sin eliminar consentimiento ni revalidación antes de lanzar.
C121 corrige esa puerta; panel y consentimiento confirmados en el dispositivo.
Todavía no hay prueba de Wine, render ni login. No se ha descargado el cliente.

### Historial C118–C119 y diagnóstico

**20oct19:59 CEST:** C118 instalada con admisión/preferencias byte-idénticas.
82 tests JVM focales y auditoría de fuentes/payloads correctos; APK SHA256
`851bd8778602b1454b39a551570134629f00788b9ecb7c4a917ddc4c6d73ecd0`.
Control Wi-Fi recuperado mediante el servidor scrcpy5.0.1 ya fijado y teclado UHID,
sin cambiar permisos, vídeo, audio ni portapapeles. El gestor reconoce
**Instalado:1.60.1.70450 / El cliente está actualizado / Jugar**; no se confirmó
ninguna descarga. Jugar provisionó `WTF/BetaSuspendedTest.wtf` pero la preparación
terminó antes de lanzar Wine: `stage=prepare`, `error=Exception`, sin log PoC.
Formulario/login/render aún no alcanzados. Causa confirmada solo lectura:
**ImageFS y contenedor Wine no inicializados** (`home/` y `.img_version` ausentes);
`getContainer()` estricto lanza esa excepción antes de la derivada y de Wine.
Inicialización automática en **Jugar** autorizada: contenedor, perfiles e ImageFS,
con progreso normal, sin preparación manual ni nueva descarga del cliente. ImageFS
se descargará desde la app, no se incluirá en la APK:183506500B (~175MiB), tamaño
comunicado y flujo confirmado por el usuario. C119 (`0.2.95-first-run-runtime`):
110 tests JVM focales y19 tests host correctos; revisión sin bloqueantes;
auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`. APK SHA256
`5ab4abe84abdf0d4f774815a40d2b52105b50a0e28de2bea2d46cae565c8d6a9`.
C119 instalada y actividad principal abierta;6 ficheros de preferencias/admisión
con hashes idénticos antes/después de `install -r`. Sin servicio foreground;
no se pulsó Jugar durante la instalación. Después el usuario autoriza («adelante»)
la primera prueba C119 de preparación/arranque, actualmente en curso.
**Primer intento C119 (20oct20:28 CEST):** el gestor confirma70450 actualizado,
pero Jugar termina en `prepare` con `StorageException`/`client_executable_invalid`,
antes de ImageFS. Ejecutable, PE ARM64 y `.build.info` siguen coincidiendo con la
admisión; el registro privado no se perdió. La identidad inode visible desde ADB
difiere de la guardada; confirmado también desde Java en el proceso de la app:
raíz `(dev=1000a6,ino=1095827)`, base `(dev=1000a6,ino=1095825)`. Eso deriva otro
storage_id sin admisión actual; la anterior vinculaba raíz inode1053434. No se
puede ignorar la guarda. Investigación posterior: USB VFAT expuesto por FUSE;
el inode no es una identidad durable. La app lo incorpora erróneamente tanto
al storage_id persistente como a la admisión. La propuesta de solo readmitir se
retiró: el fallo reaparecería. Usuario aprueba separar identidad durable
(volumen/ruta) de identidad congelada durante una operación, y migración local
acotada de la admisión existente. [Plan aprobado](../.agents/planning/managed-admission-rebind-plan.md).
Corrección entregada en C120 (estado actual arriba), sin recorrido completo
CASC ni nueva descarga/reparación. El disparador histórico exacto de la
reasignación no está trazado; no atribuirlo a actualizar la APK ni afirmar que
hashes iguales prueban por sí solos ausencia de sustitución física.
Evidencia C119: `arm64-prototype/client119-first-run-runtime-reviewed-20261020/`
y `work/client119-first-run-runtime-20261020/` (fuera del repo).
No relajar guardas. Evidencia en
`work/client70450-first-launch-20261020/`.

## Reutilizar un cliente ARM64 pegado en USB (antecedentes offline)

**Actualización operativa20oct19:29 CEST:** C117 terminó por sí sola a19:22:28
(`cancelled=false`); sin servicio foreground ni journal pendiente. Existe admisión
privada de70450 ARM64/enUS ligada al USB y a la identidad de su raíz; `.build.info`
y tamaño del ejecutable coinciden con esa admisión. No se repitió un escaneo CASC.
Inicio todavía muestra «Cliente incompleto»: reconocimiento/arranque bajo diagnóstico,
no prueba de que falten datos. El usuario autoriza corregirlo, actualizar conservando
datos, arrancar el cliente descargado e intentar login. La optimización a una pasada
queda aplazada y no se anuncia como implementada. Los cortes inferiores son historia.

Flujo aprobado: Almacenamiento → **Preparar carpeta** → desconectar el USB y
conectarlo directamente al PC → pegar **`Data/` + `.build.info`** → reconectar
al móvil → **Revisar cliente pegado** → **Completar la instalación** y consentir
el coste calculado. No es un selector de rutas ni una copia automática.

Destino: `Android/data/com.wowforever.arm64probe/files/CustomGames/WoW Forever/`.
El LEEME queda al lado, fuera de la raíz. No copiar el marcador
`.forever-client-storage` ni los directorios internos `.forever-client-update`
y `.forever-update-staging`. No pegar WTF ni ejecutables de otras versiones.

La revisión local distingue producto/plataforma/build, pero **no acredita
integridad ni descarga cero**. El plan posterior valida los objetos reutilizables
y calcula lo que falta; las colisiones con archivos sueltos ajenos pueden bloquear.
La comparación de planes ARM64 70009→70450 dio ~1,9 GiB de objetos nuevos, no
una garantía para una copia real. No demuestra compatibilidad con Forever.

**Compilado y auditado; no desplegado ni probado físicamente.** Cinco clases JVM:
111 tests, sin fallos ni omitidos; host focal: 16 pasan y una aserción preexistente
de cuenta excluida; auditoría: 5 tests pasan. Revisión independiente sin bloqueantes
tras corregir la liberación del lease al cancelar. APK:
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, 2215 fuentes y 178 payloads; SHA256
`c39aaaad108d670fb7fb5401109aea3f9a63fae0c4c37c7c47d80d3c5cf655d9`.
Evidencia externa en
`/home/adrian/wowforever-artifacts/arm64-prototype/client-manager-pasted-client-reviewed-20261020/`
(`gradle.log`, `reconstruction-receipt.json`, `apk-audit.json`).
No se sustituyó C117 durante su descarga/publicación. La nueva prueba de arranque
se realiza solo tras la finalización observada y la validación de la candidata.
[Plan](../.agents/planning/existing-install-import-plan.md),
[spec borrador](../.agents/specs/US-009.md).

## Corte actual: C117 en publicación; no sustituir APK en mitad de la operación

Inspección solo lectura20oct:C117/PID11675,servicioforeground activo yjournal
ACTIVE.108de144operaciones enphase2(renombradas),36enphase0;`.build.info`enphase0.
No significa75%deltiempo ni cliente completo. CASC terminó14:03:29delmóvil;
finalización hacelecturasSHArepetidas,conavanceobservado enstaging. Ver
[handoff operativo](downloader-handoff.md).

Usuario entiende dejar terminar esta publicación. Laoptimización de8→2pasadas
estáimplementada/revisadaoffline,no desplegada. Su admisión esefímera deuna
operaciónnueva;instalarAPK ahora mataC117yobliga recoveryestricto,que no usaese
atajo ypuede repetirtrabajo. No pausa/update/reset/repair/drivers a ciegas.
[Plan y decisión](../.agents/planning/client-finalization-two-pass-plan.md).

### Corte anterior: C116 bloqueada antes de costes (histórico)

**Instalada C116 / `0.2.92-app-branding`**, sin cambios en la interfaz aprobada
C115. Inicio muestra la descarga real, progreso, Pausar y Ver
descarga, o Descargar cliente si no hay instalación ni ejecución activa. GPU en
Ajustes, perfil Classic Forever fijado por compilación y lanzamiento con una sola
ruta de consentimiento. [Plan UX completado](../.agents/planning/done/launcher-ux-simplification-plan.md).

Continuación autorizada: [icono Android y arranque C116](../.agents/planning/launcher-branding-plan.md).
Recursos propios implementados y pruebas focales pasan. C116 compilada y auditada
(2196fuentes/178payloads), SHA256
`3267df6195e5a412a34a968d78c4da2c9a05779b45fe2960149cbc33330842b3`;
vinculación de recursos compilados comprobada con `aapt`. Pausa cooperativa e
instalación confirmadas:938819objetos/74581621578bytes encoded conservados,
checkpoint/recibo/preferencias byte-idénticos. Arranque nativo dorado sobre azul
oscuro y Ajustes inspeccionados en capturas reales; geometría C115 conservada.
**Pendiente:** la ficha de Android aún muestra el icono upstream. No se localizó
Forever en la página visible del escritorio; no atribuirlo a caché MIUI sin
diagnóstico ni dar por validado ese icono. No limpiar datos/caché ni cambiar tema.
**Reanudación bloqueada en el último corte físico C116:** no llegó a costes ni
se confirmó. Reparación C117 autorizada, compilada y auditada; despliegue y
readmisión en curso, pendientes de comprobar los informes de dispositivo.
El contexto cacheado original pasa el parser estándar de C116; los controles
durables siguen idénticos (`work/client-resume-pin-20261020/`). `plan()` detecta
`recover the unfinished client transaction before planning or updating`; la UI
dice «Falló la recuperación…», pero este flujo no llamó a `recover()`. FGS0,
PID4811; los tres hashes durables siguen idénticos. Diagnóstico solo lectura de
journal/staging; no reintentar, limpiar ni ejecutar recuperación a ciegas: algunas
rutas de `recover()` pueden resetear CASC. Evidencia en `c116-resume-blocker.json`
y `c116-recovery-logcat.txt`. No declarar descarga en marcha ni marca completada.

**Causa identificada:** el CDN-config publicado cambió sin cambiar70450/build-config.
Preparar metadatos sustituyó el catálogo activo; su nuevo hash deriva otro
workspace sin recibo. El staging original y su owner son correctos, no hay journal
pendiente ni backup. No borrar ese staging ni llamar a reset/repair. Catálogo
anterior aún cacheado y vinculable al workspace original. [Plan de reparación
aprobado y en ejecución](../.agents/planning/client-resume-catalog-pin-plan.md).

C117/`0.2.93-pinned-resume`: seis tests JVM focales pasan, ninguno omitido;
revisión independiente sin bloqueantes. Auditoría canónica PASS2196fuentes/
178payloads, SHA256`085bf2633124068501720846aa022e020a95f2acd25356e4d105c72787efc1c6`.
Guard mantiene el lease hasta publicar y conserva el catálogo original cuando
hay descarga propia pendiente; contextos desconocidos bloquean sin resetear.
No se añadió harness Service/volumen simulado ni se declara integración JVM
completa: falta la prueba física de readmisión y reanudación.

Se revisaron capturas reales de Inicio, Ajustes, Almacenamiento, Resolución y GPU;
una segunda candidata eliminó el título gráfico duplicado y recolocó la ayuda
de resolución. El usuario aprobó la familia de iconos originales propuesta.
**C114 compilada, auditada, instalada y revisada en capturas reales**:
seis vectores pequeños en Inicio y Ajustes, etiquetas y lógica conservadas.
SHA256`f474d4968977e51bca5473fe0ed488120ca416a360a23e00e04909399248979b`
(2187fuentes/178payloads). Conexión recuperada en`192.168.1.130:35279`. Sin
recortes; resolución envuelve a dos líneas. **El usuario rechaza la alineación y
el espaciado C114**: capturas sin recortes no bastaban para aceptar la UI.
C115 compilada, auditada e instalada; corrección mediante una misma geometría
de acción y separación uniforme. XML real: cinco botones de748×176px y cuatro
separaciones de33px (272×64dp y12dp). Captura inspeccionada: iconos/textos en
columnas, etiquetas completas a una línea, secundarios diferenciados y sin
recortes. APK SHA256`2a9eddedc57d57bbefe75b98409a3c6f5539fa5b9744812460c46903b3b2317c`,
2187fuentes/178payloads; seis tests focales pasan. Permiso expreso para iteraciones
visuales, conservando datos y reanudando al terminar. El usuario acepta la captura
corregida C115: «si, mucho mejor». Esa interfaz queda aprobada; el icono del
escritorio y el arranque se abordan después, en la continuación C116.

### Corte anterior C115 (histórico)

Pausa cooperativa C114→C115 confirmada:719.879objetos/57.334.811.304bytes encoded;
checkpoint/recibo/preferencias byte-idénticos al instalar. Reanudación de la misma
descarga confirmada:70450 ARM64/enUS en USB6AD6-EF0E, costes31,6GiB/33,5GiB,
sin ampliar el consentimiento anterior ni cambiar destino/build. Muestra C115
22:01:12UTC del19oct:720.047objetos/57.348.415.110bytes encoded (168objetos nuevos),
FGS/PID20462/16workers, cola parcial0 y objeto cifrado retenido. Ver descarga→Volver
mantiene foreground; app dejada en Inicio, descargando. Evidencia:
`c115-resumed-first-new-commit.json` y `c115-home-returned-without-pausing.png`.
Es una fotografía, no un contador permanente ni instalación completa.

Las muestras siguientes son históricas C114, no un PID/contador actual C115.

Transferencia reanudada de70450 ARM64/enUS en USB6AD6-EF0E, sin cambiar destino
ni ampliar costes. C113 ya mostraba error y cero workers al reconectar; no se
atribuye causa sin diagnóstico.708.312objetos/56.408.614.061bytes encoded
conservados; checkpoint/recibo/preferencias byte-idénticos al instalar C114.
Consentimiento32,5GiB/34,4GiB, confirmado una vez. Muestra21:13:48UTC:
708.404objetos/56.415.189.328bytes,FGS/PID20461/16workers;92commits nuevos,
no solo prefetch. Ver descarga→Volver mantiene la transferencia.
El cliente **no está declarado completo, verificado al final ni activado**.
No reabre la optimización del descargador ni autoriza reset o cambio de destino.

APK histórica C113 audit PASS (2186fuentes/178payloads),SHA256
`a0d0609f8e523de03a29355dc24df7e6608d0b3ad2c1a63a8d396f508898aa9c`.
22casos JVM pertinentes pasan tras corregir una expectativa; scripts compilan.
Suite host completa:1305pasados/42fallos/4omitidos; un pin incompatible con la
nueva UX corregido y vuelto a pasar.41otros fallos sin resolver, no todos
contrastados contra baseline; no se declara suite global verde. Evidencia y
límites en el plan UX.

Para retomar: **[punto de reanudación del descargador](downloader-handoff.md)**.
Contiene rutas de estado/artefactos, garantías, límites, métricas y cómo observar
la ejecución existente sin reinstalar, duplicar consentimiento ni repetir replay.

### C112: preparación ordenada instalada y medida

- Planificación sin fuente con orden EKey comprobado, duplicados/colisiones de
  prefijo9bytes por adyacencia, sin tabla temporal `target-records.bin`.
  Costes/packing exactos, ruta con fuente, validación del catálogo, replan fresco
  y solape de C111 conservados.
- 8 tests focales (Casc6/Updater2),0 fallos/errores/skips, revisión APTO.
  Build3min42s y `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT` (2184sources/178payloads).
  APK SHA256 `3c325d3f422d3b7e631df5d2b038a00ad9fbe1adb493eaca47dc9ef33770f1ae`.
- Instalación sin pérdida:609.363objetos/48.294.811.059bytes encoded conservados,
  checkpoint/recibo byte-idénticos,cola parcial0,objetoE presente.
- Dos scans de planificación: **94,181+100,709s**, frente a224,584+225,166s de
  C111 (~57%menos en esas fases). Preparar→primera solicitud nueva **12min42s**,
  frente a16min40s; tras confirmar **6min51s**, frente a8min35s.
- Ventana10+2 terminada sinerror:foreground593,387s/2.463newobjs a0,29939MiB/s;
  fondo118,197s/675newobjs a0,36717MiB/s. Solape ycommits sostenidos confirmados,
  prefijo aún no figuraba completo. Última muestra16:29:32UTC:
  612.513objetos/48.536.870.644bytes,PID24930,FGS/16workers,objetoEretenido,
  checkpoint sincola parcial. No confundir con instalación lista ni con fallo.
- Red compartida con otras descargas x86_64. No mejora de throughput ni tiempo
  total acreditada; retomar medición sin competencia en la misma ejecución.

### C111: solape acreditado, no instalación terminada

Verificador secuencial por segmentos + writer de nuevos objetos en otros
segmentos; barrera conjunta antes de FINISH/índices/publicación. Corrupción aborta
preservando staging viejo+nuevo, **sin reparación local automática ni reset**.
Formato checkpoint y orden durable conservados.32tests dirigidos,0fallos/skips,
review APTO y audit PASS. APK SHA256
`5df95f91dbd5bce1eb2ae8e7856c81b1e736386dd33814843fcf3b5c0a148725`.

Inicio nuevo16min40s desde preparar y8min35s tras confirmar, con548.773objetos
anteriores todavía pendientes; miles de commits nuevos, no solo34prefetches.
Verificador:562.509objetos/42segmentos en29min18s. Foreground/fondo durante
solape0,60748/0,58017MiB/s; después del prefijo0,73111MiB/s durante10min.
Distintos tramos/condiciones y red compartida: no causalidad de velocidad.

### Pendientes de producto

Completar y validar/activar70450, arranque/login y aceptación de actualizaciones
entre dos instalaciones completas. Reparación local de stagedCASC, pausa viva,
corte eléctrico físico y hot-unplug real no implementados/probados en este cierre.
Bug previo Transaction al reaplicar bytes idénticos sigue abierto. No modificar
70009/WTF ni borrar Data/checkpoints/archivos ajenos para intentar resolverlo.

## Bitácora histórica: todo lo anterior a C111 (C106–C110 y entregas previas 9x/8x/7x…)

Las entradas siguientes conservan decisiones y estados intermedios de esos
ensayos. Sus frases «pendiente», «en curso», «no instalada», «actual» o «USB no
implementado» son históricas;
**no sustituyen el corte C113 anterior**.

### Entrega c106:ventana32medida,sin mejora consistente demostrada

C106 (`0.2.82-lookahead-32-recovery`) instalada y auditada;SHA256APK
`9e304b6373d061034c8984950e01ccb61f30e0122ea5e0d55f42588f31cb2724`.
122.954objetos/9.637.262.946bytes encoded conservados;checkpoint/recibo
byte-idénticos antes/después,pauseconfirmada y0workers antes de instalar.
SoloUpdater/Test cambian:16workers,cola30(lookahead32),recuperación34old+new,
budget1GiBtotal con restos sembrados,caché heredada unused evictable de forma
owned/durable ycoste conservador en rama sin fuente. Source-con-fuente sigue
serial,reutilizaData yacredita cacheloose que se conserva. Hashes,Data/checkpoint/
journal yrevalidación por objeto intactos. Borrado de raw exige dir-force privado
antes de liberar cupo;es el coste adicional de seguridad aprobado.

Revisión final ypruebas por fases en verde:2últimas(seed/liveness/serialcrédito)
y3concurrentes(head32,futurecache64,doscrashes) sobre hashfinal;regresiones
previas documentadas en [plan completado](../.agents/planning/done/prefetch-lookahead-32-plan.md).
No se afirma crash-power físico probado ni activación de update completa.
Preparar→confirmar→replay→10minnuevos commits→2minfondo completados,error=null.
Writer hasta primer source nuevo:22min27s para122.954objetos,fuera de throughput.
Foreground:0,60301MiB/s en600,580s;fondo:0,60593MiB/s en122,063s.
C105:0,61991/0,46100MiB/s;rangos distintos,no A/B controlado ni prueba de
mejora consistente. Persisten8–15workers en futex;muestras27–32claves,
caché máximo4.215.998bytes,RSS181.848–190.772KiB,objetoE conservado.
Snapshots no atómicos,no prueba de cotas ni corte eléctrico físico.
Artefactos `work/c106-lookahead-20261018/`.

Usuario pide además reducir el replay(~20min para122.954objetos):perfil80
syscalls no demuestra un único culpable(14metadatos/open,4DataUSB,4tempprivado,
muchosrunning/noatribuidos,0índice clasificado). C107soloobservabilidad compilada
(3m34s)yauditada,separa sus fases,sin omitir EKey/BLTE/CKey/size,ni mover archivos ni
cachear validación. No se instalará hasta terminar mediciónC106. No se usaroot:
debuggerd fue denegado yno se cambiaron permisos/config de seguridad.
Revisión independiente deC107aprobada;4pruebasCasc focales verdes/0skips,
testbyte-idéntico,mainSHA256bb3e2c15dc5d122b3c6f9d7f3e127e9579d23a7528c041601cf4e9e8f7539e05.
El diagnóstico recogerá los primeros4.096objetos de replay,no necesita esperar
a su final para identificar la fase dominante. Log por1.024,mideíndice,
openHeader(metadatos/apertura/cabecera),copy(tempprivado+lectura),EKey,BLTE,
limpieza,totalVerify inclusivo(no sumar con subfases). No atribuir CPU/I/O puro.
APK C107 SHA256 `240e13f9a8a1aade31dd29460e060d49823232065748fb8c870154e9dfaab093`,
auditPASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT. **Instalada107**,tras pausa confirmada,
cero workers ycheckpoint sin tail parcial:324.662objetos/25.614.228.707bytes
encoded conservados,objetoE presente. Checkpoint yrecibo byte-idénticos tras
update. Baseline/capturas en `work/c107-replay-phases-20261018/`.
Preparación/confirmación yobservador de4.096objetos lanzados;sin mejora aún medida.

### Objetivo vigente:tiempo total hasta instalación lista

[Plan aprobado](../.agents/planning/install-time-performance-plan.md):objetivo
experimental≥25%throughput(desde~0,60a≥0,75MiB/s),sin trasladar coste a replay/
finalización. LogsC106ya permiten acotar:delta256→28.928objetos,0,58997MiB/s;
revalidate34,10%,futureWait40,94%,privateVerify1,91%,appendForce10,39%,
checkpoint6,75%,resto5,91%(aproximado por truncado de timers por objeto).
Por eso no se añade más telemetría de commit antes de atacar costes dominantes.
Optimización mínima de consultasUI dentro de revalidación updater en preparación;
sin caché de identidad ni menos enumeraciones/checks frescos. Beneficio pendiente.
C108(`0.2.84-update-storage-fast-path`)implementada:el snapshot público sigue
completo;solo resolve/revalidateForUpdate omiten metadata cliente yStatFs deUI.
5tests focales verdes/0skips;revisión independienteAPTO sin bloqueos,
build4m41s yauditPASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT,instalada108.
APK SHA256 `efa14d068219c3002e22be355547e362718df1f990e56c07fa08fc5874ea25e7`.
Dos tests focales del reconstructor también verdes. C107confirmada70450enUSB,
61,2GiBpendientes/63,1GiBadicionales;perfil4096completo sin error.
Perfil:totalVerify38.735ms;openHeader24.057ms(~62%),copy7.215ms,
BLTE5.925ms,EKey754ms,cleanup465ms,index342ms. No sumar total con subfases;
elapsedwriter90.660ms incluye inicio/carga. No priorizar mover índice por ahora.
C108instalada tras pausa/0workers,324.662objetos/25.614.228.707bytes yobjetoE
conservados;checkpoint/recibo byte-idénticos tras update. Logs/capturas ydrivers
en `work/c108-update-storage-20261019/`. Preparación→confirmación→replay→
10minnuevos commits→2minfondo lanzados;beneficio todavía pendiente.
ADB perdió observabilidad a08:11:12del dispositivo;la app siguió. Tras reconectar,
PID5301/servicio/16workers activos,329.648objetos/26.006.915.743bytes conservados.
Logcat recuperado:primer source08:49:41.504 tras324.662verificados y3.631,725s
dewriter(~60min32s). No se repitió preparación/instalación/replay:se relanzó
solo medición fresca10+2min ycaptura;no contar el evento histórico como inicio
de esa ventana.

ResultadoC108sin errores:1,06908MiB/s en600,923s foreground y1,33882MiB/s en
120,172s fondo(+77,29%/+120,95%vsC106). ≥0,75MiB/s observado,no A/B causal.
Revalidate42,268→36,436ms/obj(~−13,8%),pero future50,749→3,141ms/obj:
la mayor parte del salto coincide con menor espera,no atribuir todo al código.
Writercapturado10.240objetos/720,656s:revalidate51,77%wall,appendForce19,38%,
checkpoint11,16%,future4,46%,privateVerify3,28%(ventana no idéntica a throughput).
RSSforeground172.800–191.616KiB,objetoE conservado,progreso en fondo probado.
Replay60min32s no mejorado;instalación lista/integridad final/activación pendientes.
Se mantiene descarga sin nuevas interrupciones/APKs hasta justificar ahorro neto.

C109(`0.2.85-directed-usb-revalidation`)implementada para USBnativo admitido:
1querypública frescaUUID/path/state por objeto y0snapshots completos,reutiliza
trusted/resolve físicos completos. Resto derutas sigue completo;fallos dirigidos
no usan fallback. Prueba efímera de4campos,no disco ni cambios de checkpoints.
10tests focales verdes/0skips(5anteriores+5gruposdirigidos),revisión independiente
de código APTO,sin bloqueos. Build4m56s yauditPASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT;
APK SHA256 `7eb30b0b9f5426bba589c0663cfd5f5b3cc548b89b7b26e9658bef9250497c5f`.
Se cierran3gaps de tests sin
cambiarproducción(mountinfoprodnull,UUIDlessfallback,canceladmisión):3focales
adicionales verdes/0skips(1solapa conlos10anteriores,XMLprevio retenido).
C109instalada tras autorización:401.259objetos/31.585.607.500bytes encoded
conservados,checkpoint/recibo byte-idénticos,objetoE presente,pausa confirmada y
0workers antes de update. Preparación→confirmación→replay→10+2min lanzados.
No mejora física aún medida. Artefactos work/c109-directed-storage-20261019/.
Reanudar entonces (C109, pre-solape) revalidaba todo el prefijo(~75min orientativos para401kobjetos);
es un problemaUXyde tiempo total,no solucionado porC109. Background continuo
conFGS no implica por sísolo otrarevalidacióncompleta. No maquillar ni omitir hashes.

Trabajo offline de replay:[plan](../.agents/planning/resume-verification-performance-plan.md).
Parser BLTE único compartido con verificación de rango+EKeyenunpase,8tests focales
finales verdes yreviewAPTO. LectorCASCacotado/adapterAndroidOs implementados sin
wiringService(factorynull),7checks focalesfinales verdes;compilecore/adapterAPI36/
closureUpdaterverificados conK2,no APK. Errorclosequeomitíacleanup corregido y
probado(primario/cancelación/suppressed/FDdelta);revisiónfinalAPTO,sinbloqueosoffline.
Candidata110reservada,NOinstalada/activada. Falta identidadFD/Os.pread enFUSEreal
yensayoreplay normalizado. No afirmar50%deahorro ni saltar verificacióncontenido.
Usuarioautoriza gatefísicointegrado enmismaC110 paraevitarAPKdiagnósticaextra:
scratchpropiofuera delcliente,1preflightpor invocación,sinproofpersistida;PASS
ymediofrescamente revalidado habilitanreader,sifallaaborta. Trasreviewse corrigió
ownershipCREATE_NEW/foreign,cierresuppressed yfreshread4096+1acotado;10tests
hostgate verdes,no hardwareproof. RevisiónfinalAPTO;build/auditGameNative en curso.
Actualización:C110compilada4m43s yauditPASS(2184fuentes/178payloads). Elprimer
rechazoDEXfuesólointerfaz+excepciónnuevasrevisadas;allowlistexacto ytestnegativo
validan admisión sin abrirprefijos. APK SHA256
`3b372f8d8a7e45c5f86d767471612472103bec314783f93f3052967052786f9b`.
Usuarioautoriza instalar;cadena conpausa/preservación/reanudación enmarcha,
aún sinconfirmación deinstalación nidePASSfísico. No prometerreducciónreplay.
Instalación110confirmada:468.177objetos/37.044.580.827bytes encoded conservados,
checkpoint/recibo byte-idénticos,tail0,objetoEpresente,pausay0workersprevios.
Preparación/reanudaciónautomáticaencurso;PASSfísico yreplayrápido aúnpendientes.
GatefísicoPASS:appPID15171,13:11:21.895dispositivo,366ms/4096bytes de sonda.
Readersehabilitasóloparaesainvocacióntrasrevalidate;mode=replay-reader ybeneficio
dereplay aúnporconfirmar. Sonda es capacidad,no throughput ni pruebaeléctrica.
ResultadoC110cerrado,sinerror:replay468.177objetos en3.694,940s(61min35s).
Prefijocomún400.384(framesCPbyteidénticos):verificador~35,09%menos,writer~33%menos
vsC109;noA/Bdecaché niobjetivo50%cumplido. Descarganueva1,16950MiB/s en597,821s,
fondo1,46412MiB/s en120,631s;C1091,22263/1,74422,distintosobjetos/condiciones,
sin mejora de velocidad nueva demostrada. ObjEretenido,progresoFGS/fondo confirmado.
ProblemaUXdereanudaciónsigueabierto:[rediseño propuesto](../.agents/planning/fast-resume-redesign-plan.md)
busca≥80%menosbloqueoinicial,nootraoptimizaciónincremental. No implementados
niaprobadossolape/pauseviva/granularidadporsegmento;descargaactualcontinúa.

MediciónC109completa,sin errores:1,22263MiB/sforeground600,323s(+14,36%vsC108),
1,74422MiB/sfondo121,619s(+30,28%);rangosdistintos,noA/Bcontrolado ni2xalcanzado.
Revalidate36,436→29,798ms/obj(~−18,2%),44,93%delwallcapturado;appendForce21,92%,
checkpoint15,11%,future2,07%,privateVerify3,63%(ventana noidénticaathroughput).
Replay401.259objetos4.728,656s(~78min49s),fuera deesasvelocidades;no optimizado
porC109. ObjEconservado,muestras≤32rawkeys,sinerror;nointegridadfinal/activación
completa aún. Descarga continúa;C110offlineAPTOaúnsinactivación/gateFUSEreal.
No altera replay ni su verificación;no atribuirle reducción de esos22min.

### Entrega c105:consulta numérica ydiagnóstico del fetch medidos

Cambios aprobados implementados yrevisados:consulta fresca de tamaños para evitar
construir1,14Mregistros extra antes del scan obligatorio;sin eliminar validación
de apertura,scan completo ni re-plan. Catalog13+Updater7tests en verde,2tests
adicionales fijan igualdad de costes/rechazo post-admisión antes de escribir.
Transfer9regresiones+1test muestreado en verde,sin skips. El diagnóstico disperso
separa headers/cuerpo/escritura/force/verify/progress/publicación sin cambiar el
cliente HTTP ysin exponer URLs/rutas/credenciales. Log best-effort no aborta fetch.

Revisiones independientes cerradas;C105 compilada3m21s,auditada einstalada como
105/0.2.81-catalog-query-fetch-timings. SHA256APK
`4b9cc52410ce7de6b59ad9fbe5070dd7729a49e462b7fa7ff9bc13a2ddfa16c1`.
C104 pausada por UI con0workers;checkpoint/recibo byte-idénticos tras update,
71.749objetos/5.668.212.326bytes encoded conservados. Preparación,replay ymedición
completados. Query166/208ms en Android para1.144.978objetos. No hay timer
equivalente del viejo barrido en app:no extrapolar la mejora host. Full replay
71.749objetos en772,924s,nuevos commits yobjetoE original conservados.
[Plan completado](../.agents/planning/done/catalog-size-pass-optimization-plan.md).

Muestreo fetch:solo EKeys terminadas00(~1/256),solo éxitos;headers incluyeDNS/TCP/
TLS/HTTP,bodyRead incluyeespera/TLS/decode,progress incluyecallback/locks/notificar.
Bytes es tamañoencoded entero,no bytes nuevos de red si hubo resume/cachehit;
los tiempos por objetos concurrentes no suman walltime. Errores/agotamiento de
reintentos no emiten esa muestra;se conservan los errores reales de ejecución.
Artefactos `work/c105-catalog-fetch-20261018/`.

Resultado físico `REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED`:foreground10min
0,61991MiB/s(389.836.395bytes/5.301objetos),background2min0,46100MiB/s
(58.325.721bytes/743objetos),FGS/16workers presentes yRAM174–188,8MiB.
Tramos distintos del catálogo:no son benchmarks A/Bidénticos ni una mejora HTTP
atribuible al agregado de tamaños. Proyección entonces~36–49h,no ETA garantizada.

20fetches muestreados exitosos:media289,25ms,headers~78,5% del tiempo sumado de
esas muestras (media227,05ms/mediana77ms/pico1664ms),body34,25ms de media,
force11,55ms,verify3,05ms,progress1,15ms,publish5,4ms. La espera de cabeceras
incluye DNS/TCP/TLS/HTTP yno identifica por sí sola un fallo del pool o delCDN.
No se cambian hashes/durabilidad para reducir ese coste.

Observación posterior breve:coexisten15.blte listos,14–15workers en futex,1enpoll
ywriter en futex. Compatible con bloqueo de cabeza en la ventana de prefetch
ordenada,pendiente de cuantificar/concluir antes de otra modificación. Aumentar
lookahead requiere revisar recuperación/coste de restos,no solo cambiar un número.
No se modifica el cliente mientras continúan investigaciones solo lectura.

### Entrega c104:captura única medida,42% menos coste de revalidación

C104 (`0.2.80-storage-snapshot-once`) instalada y auditada;SHA256APK
`c89155b49667466cb3c18031895985cd0ff3340c5179a460790e17b1129141bc`.
52.789objetos/4.219.808.339bytes encoded conservados;checkpoint yrecibo
byte-idénticos al actualizar después de pausa controlada(sin workers vivos).
8tests de identidad/guest específicos en verde sin skips,revisión independiente
de producción ytests sin bloqueos. No se ejecutó la clase completa ni hot-unplug
físico:son límites de evidencia,no se presentan como comprobados.

Se elimina únicamente la segunda enumeración dentro de revalidateForUpdate:
una captura fresca por objeto,helper privado con el cuerpo original. Selección,
revisión,marker,root/base,montaje,no-symlinks,hashes yfsync intactos;sin caché
entre objetos. Reuso de bloques/checkpoint/formato no cambian,ni16/14/18/1GiB.
[Plan completado](../.agents/planning/done/storage-revalidation-single-snapshot-plan.md).
Primeros1.024objetos:revalidate43.455ms(42,44ms/obj),frente74.516ms(72,77ms)
c103: **41,7% menos en esa fase**,sin omitir checks. Full replay52.789objetos
en572,446s,seguido de nuevos commits. Foreground600,801s:302.594.755bytes /
4.121objetos,**0,48032MiB/s**;background120,295s:66.895.446bytes/767objetos,
**0,53033MiB/s**. RAM192,4–199,5MiB,16workers/FGS yobjetoE conservados.
Resultado `REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED`,sin error del driver.
Objetos/latencia cambian entre muestras:no se atribuye todo el throughput al helper.
Artefactos `work/c104-single-snapshot-20261018/`.

Siguiente trabajo **aprobado** por el usuario tras aclarar que se harán ambos:
[agregado fresco de tamaños](../.agents/planning/done/catalog-size-pass-optimization-plan.md)
para acelerar preparación,sin sustituir el scan completo. En paralelo,diagnóstico
disperso de fases del fetch(red/cuerpo+disco/force/verify/progreso)sin cambiar
HTTP,hashes o recuperación. C105 en preparación;ningún ajuste de pool a ciegas.

### Entrega c103:solo diagnóstico separado de la espera de fuente

C103 (`0.2.79-source-timings`) instalada y auditada;SHA256APK
`dcd40c1fc8dbf6cc00fa2094edd33b636630608c9a9dad6d89f529b6488a2af2`.
Dos pruebas puntuales de retorno de fuente/solape16 ycancelación-resume16 pasan
sin skips;build4m58s,auditoría de fuente/payload correcta. Checkpoint yrecibo
byte-idénticos al actualizar:44.140objetos/3.536.891.435bytes encoded conservados,
0workers tras la pausa previa. No se cambiaron condiciones de validación ni orden
de llamadas:16/14/18,budget1GiB,hashes,fsync,temporales yupdates seriales intactos.

Solo se añaden contadores locales,cada256fuentes resueltas: `revalidateMs`
(llamada completa de comprobación del destino) y`verifiedWaitMs`(espera del
future de un objeto verificado). Miden el camino crítico del consumidor,no CPU
agregada ni red pura. El segundo incluye el trabajo pendiente del fetch/verify.
Preparación/replay yrecogida de1.024fuentes nuevas completados,sin error,FGSactivo.
Artefactos `work/c103-source-timings-20261018/`. No se repite un benchmark10min:
esta compilación aísla el cuello que c101/c102 agrupaban como sourceResolve.

Resultado c103:1.024fuentes/72.788.970bytes en162,062s de recorrido nuevo;
**revalidate74,516s(~46%)**,espera de future verificado63,544s(~39%),verificación
privada2,230s,append+force13,032s,checkpoint6,533s(sumastiempos aproximadas).
Comprobar el destino consume **72,77ms/objeto** de forma bastante constante.
Código:revalidateForUpdate hace snapshot yresolveForUpdate vuelve a hacer otro;
ambos enumeran/inspeccionan todos los volúmenes. No se desactiva ni se espacian
los checks. Cambio **aprobado,en validación**:
[una captura fresca por revalidación](../.agents/planning/done/storage-revalidation-single-snapshot-plan.md),
manteniendo selección,revisión,marker,montaje,root/base yno-symlinks por objeto.
Evidencia `work/c103-source-timings-20261018/c103-source-{result,summary}.json`.

C104 en preparación,**aprobada por el usuario** con énfasis en cierres inesperados
yactualizaciones parciales:helper privado de6líneas para usar una sola captura
fresca por revalidación. Revisión de producción sin bloqueos;pruebas directas
UpdateTarget en curso. No se cambia el formato de checkpoints ni la frecuencia
de validación,hashes o durabilidad. C103 sigue instalada ydescargando.

Otros duplicados auditados sin editar:el barrido de `largestEncoded` podría
reemplazarse por agregado fresco con las mismas cotas/count,pero su coste aún
no está medido yse difiere. No se elimina el re-plan antes de ejecutar(TOCTOU),
ni el segundo hash al consumir el File(defensa contra alteración entre etapas).
No se espaciarán controles de identidad ni se tomarán todos los pases del
checkpoint como barridos completos idénticos.

### Entrega c102:16workers medidos,sin mejora consistente frente a8

C102 (`0.2.78-sixteen-fetch-workers`) instalada y auditada;SHA256APK
`88bc3701fac792c6f99ad818bb3931a95aee2c8c6dd5f5091bff15b42d66737a`.
Checkpoint y recibo byte-idénticos tras actualizar,36.437objetos /
2.895.532.777bytes encoded conservados,0workers tras la pausa previa de c101.
Revisión independiente sin bloqueos y7tests puntuales en verde sin skips
(Updater4+Casc3):16solapados,cancel/resume16,admisión18/rechazo19,reuso serial.

Solo3constantes+comentario en producción:pool16,cola14,recuperación18. Cota1GiB,
hashes,fsync/checkpoint,orden ytemporales privados intactos;updates con fuente
siguen seriales y reutilizan bloques. Si se vuelve a8debe conservarse admisión18;
no reinstalar c101 sobre estados de16a ciegas. No se ha cambiado el siguiente
cuello al mismo tiempo. [Plan16completado](../.agents/planning/done/sixteen-fetch-workers-plan.md).

Replay36.437objetos en382,879s;medición completada,sin error del driver:

| Estado | Duración | Nuevos bytes comprometidos | Media | Frente a c101 |
|---|---:|---:|---:|---:|
| Foreground |599,862s|257.586.600|**0,40952MiB/s**|0,637x|
| Background |119,869s|60.416.314|**0,48067MiB/s**|1,242x|

16workers,FGS,checkpoint yobjetoE conservados;RSS181,8–196,7MiB. Tramos distintos
del catálogo:no es un A/Bidéntico,no se demuestra causalidad ni mejora consistente.
No se escalan más conexiones ni se adopta16como ajuste óptimo. Se mantienen de
forma temporal para evaluar el siguiente cuello,en el mismo contexto.
Proyección a esas tasas~48–57h entonces,no ETA garantizada. Resultado yresumen:
`work/c102-sixteen-workers-20261018/c102-{physical-result,measurement-summary}.json`.

De3.840objetos nuevos,sourceResolve619.599ms (~86% de720.829ms),privateVerify
10.045ms,appendAndForce49.284ms,checkpoint30.428ms. No es red pura:incluye
`destination.revalidate()` y`future.get()`. C103 en preparación añade únicamente
dos tiempos separados para resolver esa ambigüedad(sin cambiar16/14/18,hashes,
fsync ni comportamiento del updater). No se optimiza el chequeo del USB a ciegas.

### Entrega c101:8workers medidos,~2,18x en foreground frente a c100

C101 (`0.2.77-eight-fetch-workers`) instalada y auditada;SHA256APK
`d234b4e9b9344da53f3513c7b76a92093c68e872ff3d89e2fbe396a75e43536f`.
Checkpoint y recibo previos byte-idénticos tras instalar:27.816objetos /
2.194.565.341bytes encoded conservados. C100 se pausó por UI antes de actualizar,
0workersfetch tras pausa. Revisión independiente y6tests puntuales en verde sin
skips (solape8,cancel-resume,reuso serial,3Casc). El fallo intermedio9vs8 era
expectativa de claves distintas en el test:8CASC+1loose son9llamadas/8claves.

Cambio acotado:8workers solo al instalar sin fuente;actualizaciones con fuente
siguen seriales y reutilizan objetos hash-verificados, sin descargar por adelanto.
Cola6,presupuesto1GiB y recuperación10claves sin cambios. Hashes,orden yfsync por
objeto intactos;tiemposCasc agregados cada256nuevos objetos+final del resto.
`sourceResolveMs` incluye revalidate y espera del future,**no mide solo red**.
Bug previo Transaction no-op intacto;el test de reuso no acredita un update
completo en móvil. Plan [de rendimiento completado](../.agents/planning/done/download-throughput-optimization-plan.md).

Replay completo27.816objetos en291,249s desde inicio del writer;primer nuevo
payload18:19:04.794. Resultado físico `REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED`,
sin error del driver. Ventanas de medida:

| Estado | Duración | Bytes nuevos comprometidos | Media | RAM RSS |
|---|---:|---:|---:|---:|
| App en foreground |600,068s|404.391.891|**0,64269MiB/s**|187,7–205,4MiB|
| App en background |120,207s|48.784.826|**0,38704MiB/s**|191,7–194,4MiB|

8workers yFGS conservados en todas las muestras;checkpoint válido y referencia
al objeto `E` original presente. Frente a0,29461MiB/s de c100,~2,18x en el tramo
foreground,con objetos distintos (no benchmark idéntico). Proyección entonces
~36,5h foreground/~60,6h background,no ETA garantizada. No se llega a7h.
Artefactos `work/c101-throughput-20261018/c101-{physical-result,measurement-summary}.json`.

Primeros4.864objetos nuevos:sourceResolve476.800ms (~79% de602.692ms de recorrido
nuevo),privateVerify11.415ms,appendAndForce64.689ms,checkpoint36.703ms.
La espera de fuente incluye revalidación del destino y espera del future;separar
ambas antes de decidir otro cambio. No se omite fsync ni se escala a16como parte
de este plan ya cerrado. Descarga completa y update físico entre versiones siguen
pendientes;las mejoras medidas no equivalen a gameplay ni a compatibilidad70450.

El usuario aprobó probar16conexiones y después evaluar el siguiente cuello.
[Plan16workers](../.agents/planning/done/sixteen-fetch-workers-plan.md):C102 en
preparación,pool16/cola14/admisión18,misma cota1GiB,mismos hashes yfsync por
objeto,updates con fuente existenteseriales. No cambiar otros costes a la vez ni
dar por hecho un nuevo2x. C101 sigue instalada y descargando mientras se prepara.

### Entrega c100: revalidación acelerada y descarga en fondo comprobadas

c100 (`0.2.76-private-casc-verification`) instalada sin borrar datos; auditoría
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`. APK SHA256
`58f1096cf0d7a99fec772b3fe449f50ebd79978f2c1070986c04ebd5061ec6c2`.
Checkpoint y recibo del stage byte-idénticos antes/después (18.362objetos /
1.430.491.739bytes encoded). Plan70450 confirmado:83,7GiB restantes/85,6GiB
adicionales, dentro del coste autorizado. **Revalidación completa en179,659s**
y continuación con nuevos commits; el mismo tramo2.048objetos tardó21,461s frente
a264,489s en c99(~12,3x). Temporal observado en privado. No se omiten hashes.

El writer resumable usa el workspace privado existente para su único temporal;
el legacy sin checkpoint conserva el destino anterior. Hashes, fsync, checkpoint,
orden y revalidate intactos. Presupuesto:mayor encoded141.273.036bytes(134,7MiB)
trasladado del destino al privado, además del prefetch. Rechazo de huérfanos privado
y USB antes de crear marcadores; reset borra/fuerza scratch antes de marcadores.
Pruebas enfocadas iniciales19/0skips y reparación6Casc/0skips, fuentes sincronizadas
con el clon y revisión independiente final sin bloqueos. Plan activo:
[temporal privado completado](../.agents/planning/done/casc-private-verification-temp-plan.md).

**Descarga nueva:** foreground60s a0,580MiB/s; background120s a0,316MiB/s,
servicio foreground activo en todas las muestras, launcher de MIUI arriba durante
la prueba de fondo, referencia al objeto cifrado conservada. Resultado
`REPLAY_AND_BACKGROUND_PROGRESS_CONFIRMED` en
`work/c100-private-temp-20261018/c100-physical-result.json`.
El cambio resuelve el temporal FUSE de revalidación, **no satura la red**:
media conjunta~0,403MiB/s en180,7s (proyección~59h en ese instante, no ETA
garantizada). Descarga completa, índices finales y arranque todavía pendientes.
Está en curso una muestra más larga y comparación de peticiones/instalador PC.

**Diagnóstico del coste restante (sin cambios):**185/320muestras de los4workers
están en poll y116/320en futex; el writer también espera a menudo al fetch.
No atribuirlo todo al commit. Mismas8peticiones HTTPS Range de437.402bytes,
Java con cuerpo drenado/reutilización:PC1,560s,Android3,186s alhostactual
`us.cdn.blizzard.com`. `level3.blizzard.com` tardó~20s en ambos, pese a buen ping;
no se cambia el host por esa hipótesis. Muestra pequeña, sin atribuir TLS como
causa única. Comparación con el instalador de `wowserver` solicitada por usuario.

**Comparación PC completada, solo lectura:** `wowserver` ejecutaba hasta16fetches,
una petición Range por objeto ycheckpoint cada5000objetos/300s, sinfsync de payload
por objeto. Su tramo final acreditado procesó16,48GB en~68min(~4MiB/s). Hubo
tandas/reinicios por memoria;el recuerdo de~7h no corresponde a una sola pasada
registrada, pero es compatible con el orden de magnitud activo total. No importar
su uso de10–15GiB de índices ni sus reinicios OOM al móvil. Primer paso
**completado**: [8workers con tiempos y mismas garantías](../.agents/planning/done/download-throughput-optimization-plan.md).

El usuario aprobó ese primer paso, destacando que deben respetarse las
actualizaciones. C101 está en preparación:8workers para instalaciones sin fuente,
fallback serial con reutilización para fuente existente, misma cota1GiB, mismos
hashes y sincronización por objeto. Se medirán tiempos agregados de resolución de
fuente, verificación y commit, sin prometer un multiplicador. C100 sigue instalada
y descargando mientras se prepara la versión nueva.

Ventana completa c100 de10min:185.490.279bytes/2.287objetos añadidos en600,445s,
**0,29461MiB/s /3,809objetos/s**. FGS y objeto cifrado conservados en todas las
muestras;proyección80,4h entonces, no ETA garantizada. Memoria observada17:36:
RSS192.996KiB (~188MiB),swap25.016KiB. Referencia para la comparación c101.

### Entrega c99: instalada; reanudación pausada tras medir el cuello FUSE

c99 (`0.2.75-download-recovery`) está instalada y auditada; las pruebas específicas
de Kotlin y la revisión independiente están en verde. Conserva18.362objetos /
1.430.491.739bytes encoded. Está **pausada**: el replay real es demasiado lento;
no se llegó a medir descarga sostenida ni supervivencia en fondo de c99.
La revisión del prefetch anterior de c98 encontró
errores de propagación del productor, presupuesto privado, recuperación de varios
objetos en vuelo, cancelación/cierre de workers y progreso concurrente. c99 los
corrige sin quitar hashes, fsyncs ni comprobaciones de identidad
USB por objeto. El servicio de primer plano y la pausa de c99 se comprobaron;
el recorrido completo de reanudación hasta descargar payloads sigue pendiente.

Evidencia nueva del móvil (artefactos externos en
`work/c98-resume-review-20261018/`):

- Prueba HTTPS desde Android con `HttpsURLConnection`, sin escribir el cuerpo:
  OVH `10Mb.dat`, 10.485.760 bytes en 0,733 s, **114,51 Mbps / 13,65 MiB/s**.
  Es una medida corta de capacidad de red, no una tasa sostenida de Blizzard.
  Cloudflare devolvió HTTP403; no se evitó TLS ni se alteró su validación.
- La captura de las15:06 muestra **517,6 MiB de84,4 GiB descargados**; ya salió
  del escaneo. Cuatro workers vivos, pero sin servicio del paquete en `dumpsys
  activity services`. Hay un defecto de código: un start duplicado con `busy=true`
  hacía `stopSelf()` aunque su coroutine siguiera viva. Se corrige para c99; no
  se afirma que fuera la causa física sin logs de ciclo de vida correlacionados.
- El checkpoint leído contiene17.911 objetos, CRC válidos y claves ordenadas,
  incluido `0006690c41014581816e37b9b5f6846e`. Se extrajeron sus21.177 bytes desde
  `data.000` del stage USB: **EKey y checksum de su único frame `E` coinciden**.
  Queda acreditado el paso real de ese objeto cifrado por la reconstrucción;
  no equivale a verificar toda la instalación ni el descifrado del cliente.

Correcciones a conclusiones anteriores: `disconnect()` no quedó demostrado como
coste dominante; los temporales de transferencia son privados del teléfono, no
USB. CPU global activa puede ser renderizado y no demuestra avance del scan.
El contador SCANNING no cubre los pases posteriores de planificación; los
rehashes de la tabla de índices en disco son otra hipótesis de las pausas.

**Perfil real de «Comprobando archivos»** (sonda aislada, catálogo70450 READONLY,
sin fuente CASC ni escrituras en el cliente; artefactos
`work/c98-scan-profile-20261018/`):1.144.978 filas leídas en7,34s; invocación del
`inspectTarget` de producción en90,97s. Los chunks que cruzan los crecimientos
78.644/157.287/314.573/629.146 tardan2,78/4,84/8,90/16,73s. Esto acredita las
pausas intermedias de rehash, pero no prueba que todo ese tiempo sea conversión
hexadecimal. c99 sustituye el `Formatter` por byte de `ForeverCasc.hex()` por la
misma conversión de nibbles ya usada en el catálogo, sin cambiar el resultado;
añade tiempos por fase, no logging por objeto, y actualiza el postimage autenticado.
La mejora de tiempo después del cambio todavía debe medirse.

El chunk final1.130.496 tardó1,07s en esa sonda: **no reproduce la espera larga
al reanudar**. Queda por localizar en APK con los tiempos de carga del checkpoint,
verificación staged y primera petición al downloader. El intento inicial de medir
verificación staged bajo `run-as` aislado no accedió al USB: sus500 resultados
`false` rápidos son errores de acceso, no un benchmark válido. No se alteraron
permisos, checkpoint ni payloads para evitar ese límite.

**Verificación staged medida después:** la misma sonda, ejecutada bajo UID shell
(sin root, scratch propio en `/data/local/tmp`), sí pudo leer el stage.500 objetos
reales /35,1MiB encoded: **500/500 correctos**,2,945s y9,219s en dos corridas;
mediana17ms y p95=29ms en la segunda. Sondas y temporales eliminados sin tocar
Data/checkpoint. Para unos18.000 objetos ya staged, el rango6–18ms/obj apunta
a~2–5,5min solo de revalidación, además del resto del recorrido del writer.
Esto es una extrapolación compatible con la pausa final, **no una medida completa
de esa fase en el proceso app**: UID/mounts distintos y variación de caché/contención.
Los logs c99 deben confirmar dónde se acumula el tiempo real antes de darlo por
diagnosticado completamente. No se eliminan las comprobaciones de hashes.

**Optimización medida antes de instalar c99:** con las clases CASC nuevas delante
del APK en el proceso diagnóstico, el mismo escaneo real completo tarda **70,38s
frente a90,97s** (una corrida por versión,~23% menos, no benchmark del flujo app).
El chunk con el último rehash pasa de16,73s a4,53s. Los mensajes nuevos aparecen
en logcat con tag `ForeverClientUpdate`, confirmando la carga de las clases nuevas;
el crecimiento629.145→2.097.152slots tarda3.662ms según ese log. No se ha cambiado
la capacidad inicial de la tabla ni se han eliminado verificaciones.

**Validación de la reparación:**24 tests Kotlin (Updater10, Transfer9, servicio5)
pasaron sin skips;3 tests CASC de fuente ausente, recuperación de cola y colisión
de prefijo también pasaron sin skips. Revisión independiente sin bloqueos. Se
eliminó además un deadline de5s que podía perder el marcador final del productor
si el último commit era lento. c99-r2 compilada,2tests completion/cancel pasaron
sin skips y auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT` correcta. **Instalada
como99/0.2.75-download-recovery**, APK SHA256
`378564418dae38a82ac4a35b509d83f941befd2ce5dc31c877abac3e791db697`.
Checkpoint y recibo del stage permanecen byte-idénticos tras instalar
(18.362objetos,1.430.491.739bytes encoded). Prueba física de reanudación en curso;
no se declara el flujo ni la descarga completa terminados.
El árbol c98 usado para tests ya NO coincide con su receipt: originales
preservados en `work/c99-transfer-repair/originals-c98/`; no usarlo para entrega.

**Límite anterior no resuelto:** reaplicar una instalación ya idéntica puede
atascar el journal de `ForeverClientUpdateTransaction` al confundir bytes viejos
y nuevos iguales con una publicación que requiere backup. El código es idéntico
a c97. No afecta al root actual (solo marker y stage, sin archivos de instalación
publicados); no se declara resuelto repair/reapply de una instalación completa.

**Cierre físico c99 (16:13):** confirmada la reanudación del plan70450 de83,7GiB
restantes y85,6GiB adicionales (dentro del coste autorizado). FGS activo desde
16:02:30; scan repetido al ejecutar200,291s, carga del checkpoint1,906s. El replay
real verifica1.024objetos a los134,954s y2.048 a los264,489s desde el inicio del
writer:~130s por1.024 (~0,13s/obj). **Sustituye la estimación de2–5,5min anterior**:
proyecta~40min para18.362objetos, sin haber esperado/verificado el conjunto entero.
En40muestras del replay,34esperas FUSE, principalmente fstatat/openat/unlinkat:
la velocidad secuencial de la USB NO descarta su latencia de metadatos.

Se pausó por la UI antes de nuevos commits: el servicio recibió Pausar16:13:05.432
y terminó16:13:05.892; UI «Descarga pausada», sin workers `forever-fetch` visibles.
Checkpoint **byte-idéntico** al anterior a instalar c99; sigue íntegra la referencia
al objeto `E` original. La medición de tasa/fondo se canceló porque no se alcanzó
la primera petición de nuevo payload, no por una prueba satisfactoria de descarga.
Evidencia: `c99-complete-run.log`, `c99-replay-syscalls.txt`, `c99-pause-check.json`,
`after-c99-pause-baseline.json` en el directorio externo de revisión.

Siguiente propuesta, **sin implementar y pendiente de aprobación**:
[temporal privado de verificación](../.agents/planning/done/casc-private-verification-temp-plan.md).
Mover el temporal, no el cliente; mantener hashes/durabilidad y costear scratch.

### Entrega actual97: frames cifrados 'E' aceptados con passthrough autenticado (descarga en marcha)

Diagnóstico confirmado con oráculo (archivo real del CDN, 268 MB + índice oficial
`29608f9.index`): el objeto que abrupta el fallo, `0006690c41014581816e37b9b5f6846e`,
es BLTE modo **'E'** (cifrado TACT, clave `a9b26fb30d67a40d`, **no publicada** en
TACTKeys). ~17,5% de los frames y ~5,4% de los bytes del conjunto 70450 son 'E':
descifrar no es viable sin la clave y *saltar* no es viable por volumen. El lazo
anterior de la verificación es válida: el ekey (MD5 de la cabecera BLTE) ya
autenticó los bytes descargados contra `*h1` publicado, y el checksum de frame
(certificado por oráculo) = `MD5(mode+payload)` autentica el stream cifrado.

c97 (`0.2.73-functional-client-manager`): **passthrough cifrado autenticado** —
`ForeverBlte.decode(..., allowEncrypted=true)`: para frames 'E' verifica el stream
cifrado con el checksum del frame sin descifrar, y no evalúa el ckey global
(imposible sin clave; el cliente descifra en runtime con su clave embebida).
Estricto (default) para metadatos y extracción de loose files. Cambios en
`ForeverBlte.kt` (updater-gate, mecánica preimage/postimage como `ForeverCasc`;
overlay quedś bit-idéntico al código59) + call sites de reconstrucción
(`ForeverClientUpdateTransfer`, `ForeverCasc` ×2, `ForeverClientUpdater`).
Pins/tests actualizados (25/25) y auditoría APK
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT` (delta DEX winlator `$1`→lambda aprobado
por experimento: recompilado hoy, el árbol c96 reproduce el mismo drift).

**Resultado físico c97:** instalada por Wi-Fi sin borrar datos; navegación
Ajustes → Cliente y actualizaciones; el plan 85,0 GiB a USB confirmado y la
descarga arrancó («Descargando 13.5 MiB de 85.0 GiB» → USB usado 17,7→41 MiB en
~10 min; sin aborto de verificación en los primeros minutos, a diferencia de c96).
Monitor de largo plazo corriendo en el host (`c97-download-monitor.json`). Pausar/
reanudar y cierre en fondo aún por verificar físicamente.

### Entrega actual93: descarga con servicio en primer plano (en verificación)

User respondió a la pregunta de pausa/fondo: compilar c93 con tres cosas aprobadas
(«c93 completo (Recomendado)») — servicio en primer plano con notificación y Pausar
desde la notificación, registro real de errores en logcat, y reintento instrumentado
de la descarga70450 de85,0GiB al USB. Código: nuevo
`ForeverClientUpdateForegroundService.kt` (servicio `dataSync`, WakeLock/WifiLock,
canal «Descarga de WoW», acción Pausar vía intent) + puente
`ForeverClientUpdateExecution` (proceso) y cambios acotados en
`ForeverClientManagement.kt` (Pausar enlaza al servicio, Volver/cierre del diálogo
no cancelan una descarga activa, errores mapeados por el registro). Manifiesto:
servicio + `WAKE_LOCK` añadidos por `apply_functional_poc.py` (idempotente); el base
ya traía `FOREGROUND_SERVICE_DATA_SYNC` y `POST_NOTIFICATIONS`. c93 =
`0.2.69-functional-client-manager`. Estado: instalada (Wi-Fi, sin borrar datos).

**Resultado físico c93:** la confirmación arrancó la descarga real en
`ForeverClientUpdateForegroundService` (ServiceRecord visible) y el USB creció
(3,7MiB→~15,9MiB usados) mientras la UI mostraba «Descargando X MiB de 85.0 GiB».
El logcat registró el fallo real, antes perdido tras el mensaje genérico:
`IllegalStateException: downloaded encoded object failed EKey, BLTE, CKey, or size
verification` en `ForeverClientUpdateTransfer.fetch`. La reanudación quedó activa
y el journal conservado para reintentos, pero la ejecución abortó completa (según
diseño) sin llegar a la fase de pausa/notificación informada por el fondo.

**Respuesta en c94 (compilación en curso):** reintento por objeto con conexión
limpia (`VERIFY_ATTEMPTS=2`) y el ekey/tamaño/URL en el mensaje del fallo, para
identificar el objeto exacto si vuelve a fallar (¿borde de caché CDN o error del
mapeo de rangos del catálogo en dispositivo?). El diálogo además mapea ese caso
(«Un archivo llegó dañado desde el servidor…»). Manifiesto, WakeLock y diseño del
servicio siguen como en c93.

**Resultado físico c95 (instalada):** el auto-recovery concreto funcionó como
diseño (`pending recovery detected; recovering before retry` en logcat), la
transacción se recuperó, PERO el reintento volvió a bloquearse: el registro
congelado de la operation lease seguía en `Status.UPDATING` porque
`ForeverClientOperationLease.recoverUpdate` no tenía ningún llamador de producción
— el journal quedaba limpio y el gate idéntico. c96 (compilación en curso):
`service.recover` cierra el ciclo llamando `runtimeGate.operations.recoverUpdate`
solo cuando el motivo era exactamente `update_recovery_required` y el journal
está resuelto; sin afirmar funcionamiento hasta probarlo en el móvil.

**Resultado físico c94 (instalada):** el reintento NO llegó a transferencias: el
arranque de ejecución se bloqueó con `Hay una actualización anterior pendiente de
recuperación.` — la transacción abortada de c93 dejó el journal en estado
pending-recovery y ningún flujo del UI encadenaba `service.recover` (existía pero
estaba huérfano). c95 (compilación en curso): `executeWithAutoRecovery` en el
servicio en primer plano detecta ese bloqueo exacto, corre
`service.recover(…)` («Recuperando la descarga anterior…») y reintenta la misma
descarga con el mismo consentimiento una vez; mapeo del diálogo también ajustado
para el caso «Falló la recuperación…». Sin afirmar funcionamiento hasta probarla.

### Entrega actual92 y preparación real de70450 en Android

**Actualización de ejecución:** catálogo70450 generado en el propio móvil y
publicado en caché privada; cualificación USB completa aprobada (`private_fs_pass;
client_volume_fs_pass;cdn_probe_pass;runtime_idle`). El planner finalizó con pantalla
real de coste: **85,0GiB de descarga / 86,1GiB de espacio adicional**, botón Confirmar
descarga habilitado, USB114,5GiB libre. No se pulsó esa confirmación y el USB seguía
en3,7MiB usados. Procesó1.144.978 objetos; observación prolongada mostró actividad
real de SQLite/CDN, no se reinició ni duplicó la descarga para acelerar la UI.

Evidencia externa `single-action-code92-plan-watch.json`,
`client-manager-code92-cost.{png,json}` bajo `work/wifi-uhid-control-20261017/`.
Consulta de `/proc`/espacio/caché usada solo como diagnóstico; no se ejecutaron tests.

**Discrepancia a aclarar:** en la comprobación final la ruta interna legacy
`.../CustomGames/WoW Forever` ya no existía (tampoco la alternativa `files/CustomGames`).
No afirmar originales intactos a esta fecha: no se pudieron repetir sus hashes.
En esta operación no se solicitó mover/borrar el cliente interno ni aplicar una
actualización; no se conoce todavía cuándo o por qué dejó de existir esa ruta.
La cualificación y la planificación se hicieron sobre el USB vacío seleccionado.

Code92 `0.2.68-functional-client-manager` instalado por Wi-Fi. Build6m34s sin
ejecutar tests por petición del usuario; auditoría2178 inputs/178 payloads y firma,
JNI/runtime conservados. APK483713353 bytes, SHA-256
`a0a5b35d883334eb5267968a6c6b4ea77b1459930ff64377f0a9da8b6cf7efeb`.

Interfaz de91 conservada: detección de instalación/novedades y una acción principal.
Corregido uso de FileStore Java para Android: espacio vía File.usableSpace,
identidad de filesystem vía Os.stat.st_dev, capacidad compartida /data-emulado
tenida en cuenta. Permisos POSIX de caché de metadatos vía Os.chmod en Android;
no cambios de permisos globales/USB/cliente. Sigue la verificación de hashes.

Se inició «Descargar e instalar» en el USB vacío, autorizando solo preparar metadatos
y calcular el coste. En el móvil se adquirieron configs/raw/decoded70450 en caché
privada (~436MiB), con base de trabajo local que crece durante la generación del
catálogo. Aún en procesamiento en la última observación: no se presenta la generación
ni el cálculo como completados. El USB no recibió payloads del cliente (3,7MiB usados
observados), no se pulsó Confirmar descarga ni se tocó el cliente interno.
Evidencia `work/wifi-uhid-control-20261017/single-action-code92-result.json`;
`costConfirmationVisible=false` al terminar la observación acotada, no un éxito.
Continuar la operación ya iniciada, no repetir descargas o reinstalar mientras corre.

### Entrega actual91: detección automática y una acción principal

Code91 `0.2.67-functional-client-manager` instalado por Wi-Fi sin borrar datos.
Build6m29s sin suites de tests; auditoría2178 inputs/178 payloads, firma/JNI/runtime
conservados. APK483712449 bytes, SHA-256
`6b0b5c0ebc3df2fc39bbb474745021336f17da7356fcc2c68ada99e2eb678e55`.

El gestor consulta instalación y publicación al abrir. Presenta ubicación, versión
y una acción: Descargar e instalar/Actualizar/Jugar según detección. El flujo prepara
metadatos, cualifica unidad y calcula el plan internamente; después muestra coste y
Confirmar descarga. Pausar durante trabajo cancelable; no se interrumpe publicación
atómica. Jugar se conecta a preparación/lanzamiento existentes por acción explícita.
Instalaciones con archivos no identificados no se tratan como un cliente vacío.

Observación física91: USB seleccionado, «Cliente no instalado»,70450 disponible,
solo «Descargar e instalar» y Volver como acciones en reposo. Captura/informe externos
`work/wifi-uhid-control-20261017/client-manager-code91-single-action.{png,json}`.
No se inició descarga del juego. El intento de preparación real con90 había fallado
antes de completar su catálogo y falta diagnosticar ese error; la nueva interfaz
no demuestra resuelto el motor. Se conservan los informes `metadata-code90-*`.
Tampoco se acreditan todavía actualización completa, arranque nuevo o autologin.

### Entrega actual90: guardado real de cuenta, independiente del autologin

Code90 `0.2.66-functional-client-manager` instalado por Wi-Fi sin borrar datos.
Compilación6m39s sin suites de tests; auditoría2178 inputs/178 payloads con firma,
JNI y runtime conservados. APK483782573 bytes, SHA-256
`f0fc3f362146a723dd6ce327616319cc2b166239939113cf5aa030b5b4d85222`.

Cuenta vuelve a Ajustes con Usuario/Contraseña y Guardar habilitados. Guardar con
autologin apagado conserva la cuenta: ya no ejecuta borrado. Recordar sigue opt-in
cifrado Keystore; sin marcar, memoria de proceso. Al reabrir se adquiere el almacén
fuera del hilo UI y se restaura solo usuario, nunca contraseña visible. Contraseña
vacía al guardar conserva la anterior solo si el usuario coincide; cambiar usuario
sin nueva contraseña se rechaza. Olvidar es la operación de borrado explícita.

Prueba física con cuenta sintética, después de confirmar que no había cuenta previa:
guardar con Recordar → cerrar/reabrir → usuario restaurado/semántica password
protegida → olvidar. Todas esas acciones completadas; editor SecureOn, sin captura
ni persistencia/log de campos. Evidencia externa
`work/wifi-uhid-control-20261017/account-code90-result.json` (solo booleanos).
No equivale a autologin: detector/input/sender real aún no integrados.
La consulta/preparación dinámica sin casilla de compatibilidad de89 se conserva.

### Entrega actual89: consulta dinámica sin veto de compatibilidad

Instalado code89 `0.2.65-functional-client-manager` por Wi-Fi sin borrar datos.
Compilación6m32s, sin ejecutar tests por petición del usuario, auditoría2178 inputs/
178 payloads; JNI/runtime/firma preservados. APK483778169 bytes, SHA-256
`d53e0adf1832821a8cefa3183ffe755605d637fd7f83bb26fbf3192340189938`.

Consulta oficial comprobada en móvil:70450 disponible. La preparación de metadatos
no exige afirmar compatibilidad Forever: casilla retirada y admisión dinámica
marcada `not-confirmed`, sin fingir confirmación del usuario. Se conserva el coste/
consentimiento para transferir metadatos y descargar/aplicar después. No hubo descarga
del juego ni preparación completa de metadatos en esta comprobación. USB seleccionado,
sin cliente.70425 es el catálogo preparado inicial, no una recomendación fija de la
consulta. Sigue pendiente cerrar el recorrido real de preparación/descarga.

Mensajes del gestor ya sin «build fija del catálogo» ni «servicio no ha autorizado»;
motivo concreto para comprobar unidad y refresco del estado tras hacerlo. Cuenta
quedó retirada del menú por el cambio anterior, sin borrar credenciales existentes;
**no equivale a terminar el guardado/autologin**, que debe volver utilizable.
Evidencia externa `client-manager-code89-dynamic-query/` y
`work/wifi-uhid-control-20261017/client-manager-code89-query.json`.

### Entrega anterior88: aceptación nativa informativa

Por petición explícita del usuario se elimina el veto booleano por aceptación nativa
pendiente en ejecución, recuperación y recibos de instalaciones gestionadas. No se
falsea la evidencia como validada. Se conservan consentimiento/coste, hashes,
identidad del destino, recuperación y exclusión con Wine.

Code88 `0.2.64-functional-client-manager` compilado sin ejecutar tests (6m32s),
auditado e instalado por Wi-Fi conservando datos. SHA-256 APK
`7808223ae1f6adec2ddffaa2813d24dd4b9ffc42336c727ce186787f930cb4fa`;
483783973 bytes,2178 inputs y178 payloads auditados; JNI/runtime/firma conservados.
En dispositivo se comprobó ausencia del veto de arranque nativo. La selección
encontrada era Unidad USB,114,5GiB libres; se preservó sin cambiarla. Ese volumen
aún requiere su comprobación de almacenamiento reciente antes de ejecutar una
descarga. No se inició descarga del juego, actualización, Login o credenciales.
Evidencia externa `client-manager-code88-native-advisory/` y
`work/wifi-uhid-control-20261017/client-manager-code88-advisory.json`.

### Validación física del gestor (2026-10-17, entrega actual87)

Instalado `com.wowforever.arm64probe` code87,
`0.2.63-functional-client-manager`, por ADB Wi-Fi sin borrar datos. APK483784689 bytes,
SHA-256 `076b145ef19abc934151523a7778720ed60c17f092be8b4d2abc5a4c3cef40af`.
Build8m38s y auditoría correctos:2178 inputs,178 payloads (4 assets de metadatos
admitidos, marker cambiado; JNI/runtime/firma conservados). Historial79 y siguientes
se conserva debajo; no confundirlo con la versión instalada actual.

El gestor abre en el teléfono, importa/verifica el catálogo completo y reconoce
70009 instalada y70425 destino. El escaneo del catálogo usa conversión hexadecimal
directa, evitando millones de llamadas costosas a Formatter; comprobación inicial
observada de unos44s en85, no benchmark universal.

Corregidas diferencias Android reales: alias del ancla privada obtenida de Context
(solo se canonicaliza esa ancla del SO; hijos siguen no-follow); publicación de
caché privada mediante rename atómico serializado por lock de proceso/archivo,
sin requerir hardlinks del FS Android; clasificación exacta de `/system/bin/logcat`
de la app; nombre `comm` de procesos Java usa últimos15 caracteres en Android.
Se siguen exigiendo argv0 del paquete, ejecutable Java, UID estable y starttime;
no se ignoran procesos Wine ni desconocidos por nombre parecido.

**Prueba física87 aprobada:** `private_fs_pass;client_volume_fs_pass;cdn_probe_pass;runtime_idle`.
Se escribieron probes propios pequeños y se hizo una transferencia CDN pequeña
ligada al catálogo/verificada; no descarga del cliente ni modificación del juego.
Hashes PE70009 y WTF original intactos tras la prueba. La reserva de guest activo,
su cierre normal y recuperación tras muerte del host aún requieren ejercicio físico.

Consulta oficial desde la misma APK detectó70450, fingerprint build config
`84bea4233331e390fda8724c6f91ffa5`, CDN config
`f065eade5276a5ac05606e60a4c5a1f7`. No confirmada para Forever; no se marcó el
consentimiento de compatibilidad ni se preparó/descargó esa build.70425 sigue siendo
el destino confirmado por el usuario. Hace falta permitir preparar explícitamente
esa admisión conocida sin confundirla con la última publicación genérica.

Pendiente: generación completa del catálogo en el dispositivo (el port ya coincide
con el host en pruebas), recorrido de descarga/reparación real y arranque nativo
aislado antes de habilitar activación. El gate nativo sigue pendiente; no presentar
esta entrega como actualizador terminado.

Evidencia externa `arm64-prototype/client-manager-code87-host-identity/` y
`work/wifi-uhid-control-20261017/client-manager-code87-qualification.json`,
`client-manager-code85-version-check.json`. Versiones80–86 conservan logs/tests y
diagnósticos: fallos de integración y pruebas físicas no se retaggean como éxitos.

### Prueba física USB y control Wi-Fi (2026-10-17, APK79)

ADB inalámbrico conectado al mismo xaga. Los toques SDK siguen denegados por
`INJECT_EVENTS`; no se modificaron ajustes de seguridad ni permisos. El servidor
oficial scrcpy5.0.1, SHA-256
`764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`,
permitió teclado UHID por Wi-Fi: dispositivo observado y Tab/foco comprobados.
Sin vídeo, audio, portapapeles ni escritura de texto. Sesiones y forwards temporales
cerrados y jar temporal propio eliminado tras cada control.

USB público **6AD6-EF0E**, vfat,114,5GiB libres. La app lo enumera como «Unidad USB».
Se confirmó mediante la UI su selección real, pasando el preflight de escritura/
rename/lectura/limpieza de archivos pequeños del servicio. El selector mostró USB
actual y cliente/Data no instalados; después se restauró explícitamente el interno.
Preferencia final `primary:emulated`; hashes del PE y WTF original intactos. La
denegación de `run-as` al listar USB no equivalía a denegación del proceso Android.
No se copiaron archivos del cliente, no hubo descarga ni arranque desde USB; esas
pruebas siguen pendientes. No se ha ejercitado todavía desconexión física/reinicio.

Evidencia externa:
`/home/adrian/wowforever-artifacts/work/wifi-uhid-control-20261017/`:
`probe-result.json`, `usb-storage-ui.json`, `usb-storage-code79-wifi.png`,
`usb-selection-result.json` y logs acotados del servidor. Teléfono continúa en79.

**Actualizador en desarrollo, no instalado:** plan aprobado en
[`launcher-client-updater-plan.md`](../.agents/planning/launcher-client-updater-plan.md).
Comprobador Kotlin de versiones y manifiesto completo70009 en host probados; consulta
puntual oficial detecta70425 y el usuario confirma que Classic Forever usa esa versión;
es el destino objetivo, pendientes evidencia ARM64 y ejecución propia. Prototipo CASC incremental
valida reutilización/reparación en fixtures con lector independiente. El límite fijo
se ha eliminado en el prototipo: índices variables y trabajo en disco manejan
1.319.619 registros sintéticos de metadatos, verificados independientemente; no son
1,3M payloads ni una actualización real. La variante de índices64KiB con cola no nula
observada en el móvil sigue sin admitirse; no se ignora ni declara corrupta.
Activación/journal/UI bloqueados hasta compatibilidad de formato y aceptación nativa;
no hay aún botón Actualizar, descargas grandes,
reparación real ni APK nuevo. La entrega instalada continúa siendo79.

**Entrega actual instalada:** `com.wowforever.arm64probe` code79,
`0.2.55-functional-ui-clarity`. Compilada desde árbol aislado con linaje revisado,
auditada e instalada con `adb install -r`, sin borrar datos. Bienvenida confirmada
en vertical y contraste revisado en el dispositivo. La bienvenida se ha compactado:
título corto, acción principal antes de la tarjeta GPU y detalle técnico secundario.
La prueba posterior solicitó un lanzamiento y observó Wine/wineserver propios y
rotación90°. Una observación posterior con el centro redactado confirmó parte
del fondo gráfico de acceso en code69; **no confirma el formulario/campo, la
autenticación o gameplay, ni una prueba de render en code72**.

**El envío automático NO está habilitado:** entrada manual hasta verificar
formulario/campo real. El almacén memoria/Recordar cifrado de code72 se conserva;
code79 bloquea edición y Guardar mientras el envío no está disponible, en vez de
ofrecer una configuración engañosa. Olvidar sigue accesible. Datos, driver, runtime
y perfil Forever intactos.

### Claridad de interfaz y navegación (code79)

- Ajustes abre **Almacenamiento**, **Cuenta** y **Pantalla / resolución**. Perfil,
  dirección personalizada y diagnósticos quedan bajo **Opciones avanzadas**, plegado
  inicialmente. Se mantiene la política de diagnósticos apagados y guards de IO.
- Acciones/selección doradas sobre fondo oscuro; títulos de diálogo más pequeños,
  textos cortos y menos jerga. Se conservan estilo propio y controles de48dp mínimo.
- Subapartados sin Ajustes apilado detrás; **Volver** retorna a Ajustes. En el móvil
  se comprobó también Escape desde almacenamiento y navegación antes/después de preparar.
- Almacenamiento muestra ubicación/capacidad/estado; ruta y metadatos bajo detalles
  opcionales. **Buscar unidades**, **Usar esta ubicación**, ubicación actual marcada
  y ausencia de USB explícita. Esto no añade descarga/copia ni acredita USB físico.
- Cuenta explica **«Inicio automático no disponible todavía. Inicia sesión dentro
  de WoW.»** Campos/Recordar/Guardar deshabilitados mientras `runtimeReady=false`;
  no solicita datos sin utilidad ni ofrece Guardar que borre con opt-in apagado.
  No fuerza preferencias, no borra automáticamente ni cambia cifrado/servicios.
  Diálogo SecureOn/password enmascarada conservados; Olvidar disponible pero no
  ejercitado con secretos. No se capturó el editor ni se escribieron credenciales.
- Bienvenida conserva Preparar y Lanzar explícitos con confirmación abreviada.
  Resolución bloqueada al estar preparado con motivo visible, no desbloqueo implícito.

Verificación:25 tests host de UX/almacenamiento/receta aprobados; build offline
SUCCESSFUL6m50s, auditoría2142 inputs/174 payloads baseline64 salvo marker, firma
conservada. No se repiten suites JVM de servicios sin cambios. APK419209804 bytes,
SHA-256 `088931c5e2a3c548b6f082096d22ab85b3b99f79f7776c33fe543fb439a0418a`.
Instalado `adb install -r`, sin borrar datos. Inspección física de bienvenida,
Ajustes, almacenamiento/detalles, resolución, avanzado y Cuenta; preparación
confirmada, preset8 claves y hashes PE/WTF originales intactos, sin runtime vivo.
El arnés inicial conservó el mensaje READY antiguo y falló al reconocer un estado
ya preparado; corregido solo el matcher y completada la continuación sobre la misma
preparación, sin repetirla ni lanzar juego. Fallo original preservado en su informe.

Evidencia externa: `/home/adrian/wowforever-artifacts/arm64-prototype/ui-clarity-code79-reviewed/`:
recibo/auditoría/build, `ui-clarity-device.json`, `ui-clarity-prepared-continuation.json`
y capturas públicas de bienvenida/Ajustes/almacenamiento/resolución/avanzado/preparado.
No se acredita lector de pantalla, texto ampliado, IME en esta entrega ni gameplay.

### Almacenamiento y cuenta visibles (code75/76; verificación code78)

**Entrega anterior78, instalada sin borrar datos.** Prueba de ida y vuelta interno→extraíble→interno
con fixtures: selección persistida al recrear el servicio, rechazo de preparaciones
anteriores, `prepareContainer()` real con guardado/recarga de A:, demás drives
conservados y WTF derivado en ambos destinos. Destino vacío/desconectado rechaza
preparación sin usar el cliente interno como fallback. No equivale a USB físico.

Corregidos el enriquecimiento de metadatos de un USB informado por dos APIs y
la validación de enlaces/archivos especiales anidados. Recorrido NOFOLLOW de
metadatos limitado a profundidad64/100000 entradas durante selección/preparación/
revalidación; snapshots siguen sin recorrer Data. Sin inmunidad a mutaciones
externas concurrentes ni benchmark de rendimiento en medios lentos.

Validación78:16 tests host aprobados. JVM:30 casos aprobados en la ejecución de31;
el restante tenía una expectativa incorrecta de código de error, corregida y
aprobada en ejecución dirigida separada. No se presenta como suite31/31 en una
sola ejecución. Build final offline SUCCESSFUL3m22s tras interrupción anterior;
review independiente sin P1/P2 pendientes. APK419200680 bytes, SHA-256
`5b61113fd734f1053fcce5b9d630cee7c62a4ec959430de860dc1938dd403f84`;
2142 inputs auditados y174 payloads conservados salvo marker, sin cambios de runtime.

En móvil78: selector y ausencia de USB confirmados, preparación del root actual,
A: y resto de drives correctos, preset8 claves y hashes originales PE/WTF intactos;
cuenta protegida accesible preparado, sin credenciales/Login/arranque de guest.
El arnés de preparación pidió un segundo Cerrar inexistente después de volver a
bienvenida; su error se conserva. Comprobaciones finales de integridad y runtime
ausente completadas por control read-only separado. Solo hay un destino: no se
ejercitó cambio/invalidación entre volúmenes ni selección persistida nueva en el
móvil. Evidencia externa: `client-storage-code78-release/` bajo
`/home/adrian/wowforever-artifacts/arm64-prototype/`, incluidos auditoría,
`settings-device-ui.json`, `selected-root-device-preparation.json`,
`final-device-integrity.json` y capturas públicas78. XML JVM de30 aprobados y1
fallo de expectativa preservado en `client-storage-code78-final/`.

Arriba en Ajustes aparecen **Almacenamiento del cliente** y **Usuario y contraseña**
como botones de alto contraste. Ambos se comprobaron en el móvil USB actual;
cuenta accesible incluso estando preparado pero sin juego/IO activo. El editor
conserva flagSECURE, password oculto y fallback manual; no se escribieron secretos.

El destino es **un root conjunto de WoW, `Data` y `WTF`**, resuelto por identidad
Android y usado realmente en detección, preparación y A: de Wine. Default: la
ubicación existente, sin mover/copiar/borrar ni descargar automáticamente.
Se muestran ruta, libre, cliente/datos presentes e integridad aún sin verificar.
Destino elegido vacío: cliente no instalado. USB ausente: no disponible, sin
fallback silencioso. Runtime/prefix/logs/Keystore siguen internos.

API30+ también enumera `StorageManager.storageVolumes.directory` para unidades
USB temporales omitidas por Context; solo namespace app-specific derivado del
directorio público, con preflight explícito. Si Android rechaza acceso directo,
la app lo explica y no confirma otro destino ni solicita permisos para eludirlo.
No es un picker SAF convertido a ruta. USB/SD montado en solo lectura permanece
visible pero no seleccionable.

**No hay una memoria USB pública montada en el teléfono.** La GUI lo refleja;
el cable de ADB al PC no es almacenamiento. La ejecución real desde USB todavía
requiere una unidad montada con acceso permitido. Fixtures cubren vacío,
desconexión/remount/denegación, pero no sustituyen esa prueba física.

Validación anterior76:24 tests JVM de almacenamiento,0 fallos/errores, build offline8m3s;
review de integridad/rutas cerrada. APK SHA-256
`08546c9e79be6529ef1e8077773b7fa4a6c80cf1f3a3bab005172c1ce46e995d`,
419197192 bytes;2142 inputs auditados y174 payloads conservados salvo marker.
En dispositivo: root anterior presente, preparación validada, A: correcto y
otros drives conservados; preset8 claves correcto y originales intactos.
Un lanzamiento desde esa preparación mostró el cliente horizontal; sesión propia
cerrada, sin Login o credenciales. No se validaron normal-exit, IME o gameplay.

Evidencia externa: `/home/adrian/wowforever-artifacts/arm64-prototype/client-storage-code76-usb/`,
incluidos `general-settings-code76.png`, `client-storage-code76.png`, informe
de preparación y captura completa del cliente. [Plan completado](../.agents/planning/done/launcher-client-storage-plan.md)
y [US-007](../.agents/specs/US-007.md). **Instalador completo y updater siguen
pendientes:** seleccionar un destino no instala el cliente en un móvil nuevo.

### Resolución adaptativa y selector (code73/74)

Implementado selector **Automática / Rendimiento / Calidad / Nativa** en Ajustes.
Default Automática, sin stretch. Se mide el área útil después de rotar y se usa
un plan por arranque para Wine y el WTF derivado; cambiar preferencias no modifica
un guest vivo. Se preservan gamepad, calidad visible1, límite30FPS y originales.

En este móvil:2460×1080 total,100px de seguridad por cámara a la izquierda y área
útil2360×1080. Auto aplicó1564×716; se comprobaron las tres CVars y, en code74,
**desktop XServer y ventana de WoW realmente1564×716**, view2360×1080.

**Las franjas siguen presentes: fullscreen todavía no está resuelto.** Nativa
2360×1080 cambió tamaño/encuadre pero tampoco llenó el área útil. Se restauró Auto.
Falta medir la frontera de imagen/drawable/compositor frente a letterboxing del
cliente; no se han cambiado drivers ni forzado estiramiento para ocultarlo.

JVM:10 tests DisplayFit y7 perfiles aprobados; review de cancelación/lifecycle/
insets cerrada. APK74 auditado, firma/JNI/runtime conservados, SHA-256
`00fe59c1f33aeadd6ed9ad9ba83664d1b115f28ae534cf3c832d3e48a6d77e50`.
Evidencia externa: `display-fit-code73-final/` y `display-observe-code74-reviewed/`
bajo `/home/adrian/wowforever-artifacts/arm64-prototype/`. Selector y capturas
completas de Auto/Nativa disponibles, sin Login y con limpieza de sesiones propias.
Detalle: [plan de resolución](../.agents/planning/launcher-display-fit-plan.md).

**Capturas posteriores autorizadas:** ya se capturaron configuración general y
pantalla completa del cliente72, sin redactar y con campos vacíos, por petición
explícita del usuario. Evidencia de render/formulario actual y márgenes laterales
reales; todavía no detección/foco automático validados. Archivos externos
`general-settings-code72.png` y `client-unredacted-code72.png` en el workspace
`autologin-settings-code72-framework`. No se capturó el editor protegido ni se
introdujeron credenciales ni se envió Login. Sesión propia cerrada tras la captura.

La bienvenida ya detecta `Mali-G610 MC6` y muestra **arranque/render probado
(histórico)** para la combinación xaga/API34/kernel5.10.209 y el perfil previsto.
Otros modelos/perfiles se clasifican como **Compatibilidad no comprobada**;
no se cambia driver ni se bloquea Lanzar automáticamente. Configuración prevista
visible: Proton11 ARM64EC + DXVK2.4.1 custom + PanVK, D3D11/Vulkan. «Prevista»
no significa ya cargada ni gameplay validado.

[Plan GPU completado](../.agents/planning/done/launcher-gpu-compatibility-plan.md)
y [US-005](../.agents/specs/US-005.md).13 tests JVM de política/concurrencia sin
fallos; APK auditado y tarjeta confirmada visualmente en el dispositivo.
La entrega inicial de GPU fue code68; la actual conserva esa política y sus
tests, cambiando solo la presentación. Evidencia inicial:
`/home/adrian/wowforever-artifacts/arm64-prototype/launcher-gpu-code68-reviewed/`.

### Última entrega y ensayo code69

- APK418778300 bytes, SHA-256
  `8adb3758782e9b8e9a2aa5beeac71129cb220276130a6f8bd8737375a2267356`.
  Build offline exitoso en6m21s; firma conservada,2130 inputs de fuente intactos
  y174 payloads iguales al baseline code64 salvo marker. No cambio de runtime/JNI.
- Workspace y auditoría:
  `/home/adrian/wowforever-artifacts/arm64-prototype/launcher-compact-code69-reviewed/`.
- Comprobación física del WTF derivado: las ocho claves esperadas aparecen una
  sola vez, con gamepad1, interfaz gamepad1, calidad0 (visible1), FPS30/30 y límites
  habilitados. **Esto acredita configuración preparada, no su aplicación visual
  dentro del juego ni30FPS sostenidos.**
- Un envío de lanzamiento a partir de preparación ya verificada: Wine y
  wineserver propios, cuatro procesos nuevos y Activity horizontal90°. Sin
  captura de imagen del juego, escritura de texto o Login.
- IME no solicitado: el botón pequeño no estaba ya enfocado y no se envió Enter
  a un control no demostrado. Teclado/escritura, mando operativo, aspecto y frame
  siguen pendientes de prueba manual.
- Cleanup del paquete propio con exit0, runtime ya no observado; se reabrió
  bienvenida en vertical0°. **Esto prueba reinicio en vertical, no salida normal
  del juego al launcher sin force-stop.** Cliente/archivo WTF original intactos,
  captura/parcheOFF y perfil Forever conservado.
- Reporte del control acotado:
  `/home/adrian/wowforever-artifacts/work/functional-launcher-ux-smoke-20261016T/functional-launcher-ux-20261016T062741624290Z-34e0fab550/result.json`.
- Incidencias previas conservadas: dos controles code68 fallaron en lectura UI
  (el segundo sí preparó); code69 preparó pero el matcher del checkbox no aceptaba
  la jerarquía Compose aplanada. Se aisló con consulta read-only y fixture exacto,
  y la continuación sobre esa preparación envió un único lanzamiento. No se
  repitió Login ni se convirtió un fallo del arnés en fallo de autenticación.
- Tests del arnés actual:15 passed; tests host de UX/receta previos:29 passed,
  1 deselected. No equivalen a suite global ni a mando/IME físicamente validados.

Avanzar con el pulido del launcher sobre la **PoC funcional code64**, sin seguir
diagnosticando autenticación por defecto. El usuario acepta el Login
provisionalmente para poder avanzar: **no se han acreditado selección de
personaje, entrada al mundo ni gameplay estable**.

El plan fue aprobado con «adelante empecemos». Las peticiones añadidas son elegir
almacenamiento del cliente —por ejemplo USB— y usar **launcher vertical / WoW
horizontal / regreso a vertical al salir**.

## Base que se conserva

- App funcional `com.wowforever.arm64probe`, code64, versión
  `0.2.40-functional-forever-poc`; cliente ARM64 `1.60.1.70009`.
- Proton11 ARM64EC, DXVK2.4.1 custom y PanVK sobre Mali-G610, sin cambio de
  runtime/driver, ejecutable o política criptográfica para implementar la UX.
- Portal/perfil Forever aplicado; captura y parche de memoria apagados.
- El automatizado code33 bloqueado en Explorer es **otra variante**, no la base
  del arranque funcional. Se pueden reutilizar componentes puros de provisión,
  no asumir que su arranque esté resuelto.
- Instalación actual preservada. No se mueve ni se adopta otro root por defecto.

## Trabajo iniciado y entregas

| Bloque | Estado | Puerta de salida |
|---|---|---|
| Documentación y requisitos UX | Registrados | Estado actualizado, US-004 y trazabilidad al plan aprobado. |
| Bienvenida y UX del juego | Code69 compacto instalado; bienvenida vertical y arranque de runtime horizontal confirmados | Immersive/IME y ocultación del overlay implementados; faltan frame/aspecto, entrada y mando reales, y retorno normal sin force-stop. |
| Preset | Tests JVM7/7 y fichero derivado del dispositivo verificado | Gamepad, límite30FPS, calidad visible1 (`graphicsQuality=0`); no validado todavía en UI del juego. |
| GPU y configuración | Code68 instalado y detección G610 confirmada | Matriz histórica inicial, desconocidos explícitos, previsto/preparado separado de carga efectiva; sin selección automática de drivers. |
| Provisión/builds | Pendiente | Descarga/verificación/reparación/reanudación y versiones con procedencia real; distinguir bootstrap de cliente completo. |
| Almacenamiento elegible | Code78 instalado, ida/vuelta A→B→A con contenedor persistido probada en fixtures; root actual/preparación/A: comprobados en móvil | Root conjunto cliente/Data; USB requiere volumen montado y preflight autorizado. Cambio físico/ejecución USB real aún no probados, sin migración ni descarga automática. |
| Actualización incremental | Pendiente | Reutilización de objetos CASC, pico de espacio acotado, journal/recuperación y exclusión con WoW activo. |
| Autologin | Cuenta code79 muestra indisponibilidad; edición/Guardar bloqueados y Olvidar accesible | Almacén/controlador conservados; detector/campo, proveedor Keystore físico y sender pendientes. |

#### Avance de autologin (2026-10-16, todavía sin APK nuevo)

Implementados y compilados el almacén de credenciales memoria/Keystore y el
controlador puro de intento único/90s/cancelación.12 tests JVM de almacén y10
del controlador aprobados; revisión de seguridad del núcleo sin P1/P2 pendientes.
Los tests de cifrado usan JCE, no sustituyen la prueba real de Android Keystore.

**Autologin no está habilitado.** Ajustes ya está instalado en code72, con16 tests
JVM de servicio aprobados, incluidas recreación, propiedad y borrado. Diálogo físico
con dos campos, password enmascarado y flagSECURE confirmado sin escribir ni
capturar el diálogo. Faltan detector
positivo de formulario/campo, adaptador de entrada y prueba física de proveedor
Keystore/gestos UI. `runtimeReady=false` mantiene el flujo manual explícito.
El APK auxiliar sintético de Keystore se construyó y auditó, pero Android rechazó
su instalación (`INSTALL_FAILED_USER_RESTRICTED`). No se eludió la restricción ni
se cambiaron permisos. La prueba del proveedor real sigue **sin ejecutarse**.

APK principal SHA-256 `d4e05e205ea3f9614bed71b507b0e70880bdcd2c275c0a0604b244611462dfe2`;
evidencia externa en `/home/adrian/wowforever-artifacts/arm64-prototype/autologin-settings-code72-framework/`.

El usuario volvió a proporcionar la cuenta temporal. Se preparó en fichero0600
dentro de directorio0700 fuera del repo y se eliminó al cerrar la ronda, sin
introducirla en cliente, APK, logs o argumentos. **No se ha enviado ningún Login.**
La calibración actual rechazó9 frames2460×1080 contra la referencia histórica;
no se guardaron imágenes o campos ni se generó un perfil de detector. No se debe
rebajar el guard para forzar un envío. Hace falta una referencia visual actual
sin campos rellenados o calibración positiva fiable, no más datos de cuenta.
Detalle y evidencia en el [plan de autologin](../.agents/planning/launcher-autologin-plan.md)
y [US-006](../.agents/specs/US-006.md).

**Recordatorio del usuario:** autologin configurable desde Ajustes del APK,
credenciales en memoria de sesión; persistencia solo opt-in cifrada con Keystore,
nunca en WTF/argumentos/logs ni estado UI persistido en texto. Mantener por defecto
gamepad y calidad visible1/30FPS y el pequeño icono overlay de teclado real.
Preset/IME están en el APK; preset confirmado en archivo preparado, IME y aplicación
visual dentro de WoW todavía pendientes.

### Primera entrega

Bienvenida con estado/progreso/errores y diagnósticos separados; inicio explícito,
launcher vertical hasta abrir WoW horizontal; immersive sin stretch, ocultar
gamepad táctil conservando mando físico, botón pequeño de teclado real y preset.
La especificación de este bloque es [US-004](../.agents/specs/US-004.md).

La implementación preserva el baseline de audio muy bajo y los overrides legacy
de recursos/narración/advertencias, separados del nuevo preset. No modifica su
política ni introduce un acuse global de fotosensibilidad. Se corrigió además
la detección de claves duplicadas y portal en archivos Windows CRLF.

El diseño añade serif del sistema, paleta oscura/oro/burdeos y adornos vectoriales
originales (escudo abstracto, estrellas y montañas). Se permite integrar imágenes
originales generadas en una entrega posterior, sin redistribuir arte/fuentes de
Blizzard ni fingir endorsement. La captura de code65 reveló texto demasiado oscuro;
se corrigió en el candidato posterior y code67 confirma el contraste visual.

### Entrega code67 y evidencia

- APK418690852 bytes, SHA-256
  `154a73e1105674d007e48b64eb3722a0713b08155ecd562023d770d51265e34f`.
- Evidencia externa:
  `/home/adrian/wowforever-artifacts/arm64-prototype/launcher-ux-code67-reviewed/`:
  recibo de reconstrucción, log Gradle, auditoría final y captura de bienvenida.
- Build offline `assembleModernDebug`:SUCCESS. Tests JVM del perfil:7 sin fallos
  sobre code65; perfil/test idénticos en code67, no se repitieron para estilos.
- Firma conservada;174 payloads iguales a code64 salvo marker. JNI/runtime/driver
  sin cambios.2128 archivos de fuente/inputs auditados. DEX recompilado desde
  fuentes registradas; inventario de descriptores no equivale a métodos idénticos.
- Actualización con datos preservados, perfil Forever y captura/parcheOFF.
  PE original y `BetaSuspendedTest.wtf` mantienen los mismos SHA-256 antes/después.
- Sin Login ni prueba de WoW en esta entrega; launcher no lo abre al arrancar.

#### Historial del gate de compilación (ya resuelto para el nuevo linaje)

- Pruebas dirigidas de estructura/receta:13 passed; Python `py_compile`:PASS.
  Después, nueva regresión estática de CRLF:1 passed,1 deselected. Comandos
  exactos en el [plan](../.agents/planning/arm64-launcher-app-polish-plan.md#evidencia-de-esta-entrega-en-fuentes).
- Dry-run de la receta sobre el árbol externo:PASS, sin modificarlo. **No equivale
  a compilar Kotlin, ejecutar tests JVM o demostrar procedencia DEX.**
- Existe un recibo autenticado code59 en
  `scripts/arm64-prototype/app/code59-source-baseline.json`: archivo upstream
  fijado, inventario completo,12 postimágenes y63 bindings. Es posible reconstruir
  hasta code62 en un árbol aislado, sin copiar clases actuales desconocidas.
- Falta identificar/revisar un delta completo code62→63; la receta funcional
  acepta code63/64. No se debe rellenar ese hueco copiando clases core/XServer
  del árbol externo mutable ni entregar su APK antiguo.
- El campo `nativeBuildProvenance.*.dirtyTree` del marker se refiere al repo Rust;
  no demuestra por sí solo que las fuentes Kotlin sean ajenas o incorrectas.
- Resolución: el controller actual del repo se revisó y fijó explícitamente como
  entrada nueva, sin equipararlo al histórico code59. Se añadió el puente de
  producción omitido en el primer intento, se verificaron sus siete exports JNI
  y se conservaron los payloads de code64. Baseline histórico/tests no alterados.
  Por eso es un linaje nuevo documentado, no recuperación ficticia de code63.

Pruebas físicas pendientes: orientación de entrada/salida y errores, IME con
escritura real y recuperación de immersive, ausencia de marcos/distorsión,
correspondencia táctil y mando físico con desconexión/reconexión. El renderer
existente ya configura `forceFullscreenWMClass` desde el ejecutable; no se atribuye
la eliminación de todos los márgenes a un cambio guest no probado.

### Descarga, disco y versiones

El catálogo existente acredita solo ARM64 `1.60.1.70009`; otras builds admitidas
en x86_64 no implican disponibilidad ARM64. NGDP versions no es un historial
completo. Si solo hay una build acreditada, la UI debe mostrarlo honestamente.

El [bootstrap actual](arm64-client-bootstrap.md) no equivale a cliente completo.
Falta validar demanda de datos/streaming y la política ante recursos ausentes.
No lanzar una descarga completa de decenas de GB por inercia.

**No redescargar ni duplicar el cliente completo como estrategia normal de
actualización.** El escritor CASC actual genera `Data.new` desde cero; un updater
incremental con nuevos segmentos, índices y recuperación todavía debe
implementarse y probarse. Estimar espacio adicional antes de mutar; no actualizar
mientras WoW esté vivo o su salida no se haya confirmado. No prometer un delta
pequeño para cualquier salto.

### Almacenamiento y USB: confirmado frente a pendiente

- Confirmado por código: cliente/provisioner usan `File`/`Files` y rutas POSIX;
  la preferencia genérica de almacenamiento no cambia el root de la PoC.
- Un URI SAF `content://` no es una ruta nativa para Wine. No ofrecer cualquier
  carpeta del picker como destino ejecutable ni pedir all-files access por defecto.
- Propuesta pendiente de decisión: destinos app-specific montados con preflight
  de acceso, escritura, capacidad, identidad y semántica del volumen; runtime,
  Wine prefix, ImageFS y estado de control siguen internos.
- USB OTG requiere prueba en el teléfono/unidad reales. Distinguir ubicaciones
  donde se ejecuta el cliente de fuentes SAF desde donde se importa/copia.
- No mover la instalación funcional ni hacer fallback silencioso al desconectar
  un volumen. El soporte USB todavía **no está implementado ni validado**.

## Último checkpoint técnico

- D4 acreditó TCP/TLS del WoW real hacia Forever1119/8081. REST del host aceptó
  la cuenta temporal; no equivale a Login del juego.
- Logging temporal restaurado el 2026-10-15 a16:49:17UTC; otros campos
  preservados, captura/parcheOFF. El antiguo checkpoint USB ya no está pendiente.
- Un envío visual posterior a17:02:22UTC: el usuario observó «Logging in to game
  server» o equivalente, seguido de retorno aparente al formulario sin error
  visible. No se acreditó mundo; fichero de credenciales consumido/eliminado.
- Evidencia externa en `wow-net-d4-20261015T054200Z` y
  `wow-login-visual-20261015T164917Z` bajo
  `/home/adrian/wowforever-artifacts/work/`. No se copian secretos ni binarios al repo.

## Límites de ejecución

No reabrir capturador, escaneos de claves, análisis estático o bypass TLS para
implementar la UX. `wowserver`/Btrfs sigue siendo solo referencia técnica de
lectura. Conservar cambios ajenos y fuentes originales. Distribución, cambios
destructivos, sistema/particiones, seguridad permanente y cuentas reales requieren
decisión separada; el plan no los autoriza implícitamente.
