---
id: launcher-quit-replay-2026-10-22
state: in-progress
date: 2026-10-22
---

## Summary

El usuario comunica que tras Quit y volver a Jugar aparece «No se pudo completar
la acción / no se pudo comprobar que WoW estuviera cerrado» y solicita corregirlo.
Esta operación es independiente del experimento de prioridad C136, que después
tuvo resultados positivos de parche/Login.
No se fuerza el cierre, no se borran reservas ni se convierte Unknown en cerrado.

Aprobado el2026-10-23 con «hazlo si»: conservar entrega actual/botón de cuenta,
reconocer únicamente Z propio estable y parada normal del audio por su canal.

## Content

### Evidencia y causa delimitada

- [US-004-AC-09/10](../specs/US-004.md) requiere recuperación automática segura y
  conserva exclusión ante runtime activo/desconocido. El observador es compartido
  con [US-008-AC-05](../specs/US-008.md): no debilitar la exclusión del updater.
- `ForeverFunctionalUiText.kt:166-167` traduce los fallos de observación
  `own_runtime_unknown` al mensaje comunicado; el texto no identifica su subcausa.
- `ForeverClientRuntimeGate.kt:150-169` lee stat, comm y exe antes de clasificar
  procesos. `parseStat` retiene PID/starttime, **descarta el estado**; la prueba
  `privateWineAndGuestExeChildrenAreActiveEvenWhenZombieOrOrphaned` exige que un
  zombi sintético sea Active. Esto es comportamiento existente explícito, no una
  regresión demostrada del orden de lectura C136.
- Preflights reales anteriores tras cierre muestran WowB y varios procesos Wine
  en estado Z, con app/logcat y un proceso de audio vivo. Un zombi no ejecuta
  instrucciones, pero puede conservar su entrada /proc y carecer de exe. La ruta
  actual puede producir Active o Unknown indefinidamente por esos restos.
- Inspección de solo lectura 2026-10-23: versión instalada138,
  `0.2.114-assisted-entry-tabfix`, UID10330. Hay12 zombis propios con UID,
  PID/startTicks y estado Z estables en dos lecturas, sin exe legible; también
  app/logcat y un linker64 vivo propio con argv1=libpulseaudio.so. La app cambia
  legítimamente R/S entre lecturas, sin afectar la estabilidad de los zombis.
  `proc_exe_unreadable` se deduce de la ruta del observador, no de invocar su API
  ni de exportar RUNs. No afirmar que solo corregir zombis cierre todo.
- En la fuente canónica C138: `PulseAudioComponent.stop()` encola una búsqueda
  `info.name.contains("libpulseaudio.so")`; `ProcessHelper.listSubProcesses()`
  obtiene ese nombre de ps NAME. En este dispositivo el nombre es linker64,
  por tanto esa selección no identifica el daemon observado. El inicio usa
  `--daemonize=true --exit-idle-time=-1`, sin retener un handle del daemon.
  `shutdownEnvironment()` puede poner xEnvironment=null antes de que termine
  el stop asíncrono. No hay prueba obtenida ejecutando comandos de control de audio.
- `releaseGuestAfterShutdown` espera hasta30s a que xEnvironment desaparezca, pero
  hace un solo intento posterior de recuperación. No prueba que todos los hijos
  ya hayan terminado; preservar exclusión ante una salida todavía en curso.

### Corrección propuesta y límites

1. Reproducir con el FakeProc existente el caso owned-Z con exe ilegible y la
   transición de identidad/estado. Retener el estado de stat y clasificar como
   terminado solo Z demostrado para el mismo PID/starttime/UID con lecturas frescas
   coherentes y Threads:1 en ambas lecturas de status del grupo; el líder Z por
   sí solo no prueba ausencia de hilos vivos. No exigir exe/cmdline de un cadáver. Mantener las dos observaciones,
   bootId y límites. Una carrera, PID reutilizado, metadatos ilegibles de un vivo,
   estado inválido o proceso desconocido vivo sigue bloqueando.
2. Recuperar solo el ticket/almacenamiento propios cuando no queda ningún proceso
   vivo que impida quiescencia y el entorno está retirado. Si se demuestra una
   carrera normal de teardown, reutilizar espera/reintento **acotados y fuera de
   UI**, no agregar polling permanente, botones de preparación ni limpiezas ciegas.
3. Corregir la parada normal del audio mediante el canal de control de su propio
   socket de sesión (reutilizar execPactlCommand con solicitud de salida si el
   pactl empaquetado lo admite), no mediante excepción al observador ni búsqueda
   global de nombres. Verificar disponibilidad/compatibilidad del cliente de
   control antes de implementar; si falta, detenerse para decidir una alternativa
   de propiedad explícita. Parada fuera de UI y confirmación acotada de salida;
   no declarar quiescencia mientras quede vivo. No kill por nombre/UID, no parar
   daemons ajenos, no ignorar linker64/PulseAudio globalmente.
4. Conservar cancelación del parche, marca de cierre del intento, exclusión de
   launches futuros, recuperación de storage/ticket exactos y bloqueos updater.
   No kill/force-stop/root, waitpid genérico que robe hijos, borrar locks/journals,
   resetear datos ni asumir cerrado por xEnvironment=null.
5. Candidata de cierre basada en la última entrega instalada autenticada,
   actualmente C138 Tab-fix, conservando el botón de cuenta y JNI C137 sin
   modificaciones. No volver a C135 ni perder cambios del usuario. Coordinar
   propiedad exclusiva de archivos con el otro flujo si prepara C139; identidad
   final acordada antes del build, sin mezclar cambios no congelados. La corrección
   de cierre no es otro experimento del parcheador ni de autenticación.

## Implementation / Action Items

- [x] 1. Aprobar esta definición de proceso terminado y su impacto compartido en
  US-004/US-008; ajustar AC/FR/TC con trazabilidad antes de cambiar el observador.
- [x] 2. Obtener reasonCode/estado actuales de solo lectura cuando vuelva ADB,
  sin abrir/cerrar el juego ni leer credenciales. No condicionar las regresiones
  sintéticas del caso Z a la disponibilidad del dispositivo.
- [x] 3. Reproducir y corregir el reconocimiento terminal con pocas pruebas del
  observador y exclusión/recovery existentes: Z estable, vivo residual, reutilización
  de PID, carrera de estado y exe ilegible. No matrices de estados hipotéticos.
- [x] 4. Revisar si hay un segundo bloqueo de audio/teardown con evidencia; si
  requiere ampliar política, detenerse y presentar la decisión, no ignorarlo.
- [x] 5. Revisión independiente de concurrencia y frontera updater/guest; ejecutar
  JVM focales y pruebas host de receta, reconstrucción auténtica y auditoría APK.
- [ ] 6. Coordinar instalación sin borrar datos y ensayo manual Quit→Jugar al menos
  dos veces, verificando nueva admisión/ticket y ausencia de error. Login y tiempos
  del parche son evidencia separada; no declararlos corregidos por el arreglo de cierre.

### Fallo físico C139 y corrección C140 en curso

El usuario reproduce ambos bloqueos en C139: Quit→Jugar y el botón de cuenta con
mensaje de privacidad. La transformación de audio existía en apply_functional_poc,
pero reconstruct_functional_source no la ejecutaba; el Java y el recibo canónicos
C139 conservan el stop antiguo. Se corrige la ruta real, con regresión que comprueba
el audio transformado y sus hashes. Se reabre el paso5 para validar la entrega real.

Un marcador global sensible anterior a la actualización queda huérfano tras perder
la Session en memoria. Se recupera únicamente antes de la nueva reserva de guest,
bajo el bloqueo exclusivo de admisión y sus pruebas existentes de quiescencia y
propietario anterior recuperado, con entorno ausente. No se borra durante el botón,
un guest vivo, un estado desconocido ni una reserva pendiente; no se borran taints
históricos RUN/attempt. La revisión detecta además una carrera tras liberar
ProcessGuard y antes de terminar la limpieza de privacidad: se exige que no quede
activeGuest local y se reproduce esa ventana determinísticamente en un test.
No se añaden barreras de usuario ni se cambia el parcheador.
Regresión de ciclo de vida en US-006-TC-009. Código7bdacef, revisión final sin
bloqueos concretos,34 JVM y30 casos host distintos correctos; dos casos de pins se
repiten tras congelar fuentes. Cambian solo las dos vinculaciones de fuente revisada
y su receta/procedencia exactas; no se cambia contrato, JNI ni auditor.

Reconstrucción final comprueba el stop de audio en fuente y recibo. Build6m47s,
auditoría PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT,2219 fuentes/178 payloads. APK
SHA256 `7e3f9f347934c109146d4134124b9fd1bec69ec2800db313e02eec2ab40e5ba1`.
Evidencia externa `client140-session-recovery-20261023-final/`. Actualización140/
0.2.116-session-recovery verificada por versión/UID/hash instalado, sin borrar datos
ni abrir juego. Después, el usuario confirma envío mediante botón de cuenta,
estado todavía buscando, posterior «aplicado» y «si funcionó el login» al repetir
Login (método del segundo envío no especificado). El bloqueo del botón no reaparece
en este intento. No se miden tiempos ni se exportan RUNs sensibles. Los dos ciclos
Quit→Jugar siguen pendientes; B1 controlado permanece pausado.

El usuario continúa en C140: anuncia Quit y completa A2 sin Login hasta agotarse
la vigilancia sin aplicación indicada; su Login posterior vuelve al formulario.
Anuncia otro Quit para B2, envía el primer Login, observa aplicado y después obtiene
«You have been disconnected», manteniéndose el indicador aplicado. Resultado de
autenticación mixto, separado de cierre y de estado del botón. Los relanzamientos
se deducen de esa secuencia; no se sustituye la confirmación explícita de los dos
ciclos Quit→Jugar. No se exportan RUNs, no se automatiza input ni se cambia código.

### Verificación local histórica C139 — no prueba cierre real

- Implementación `3fc752b`, candidata139/`0.2.115-quit-replay`, conserva C138
  Tab-fix/botón/protección sensible y JNI C137. No cambia el parcheador.
- Revisión independiente Sol completada: el bloqueo de líder Z con hilos vivos
  queda corregido y revisado. Sin otro bloqueo de seguridad concreto; se agrega
  el único descriptor exacto `ForeverClientProcStatus` al auditor, con rechazo de
  un vecino no autorizado. No se amplían prefijos.
- 33 JVM finales:13 observador,7 lease/exclusión,13 entrada asistida; cero fallos,
  errores o saltos.40 casos host correctos:28 iniciales,2 corregidos/repetidos
  (pin de versión y tripwire de nombre),10 auditor. No es una suite global.
- Reconstrucción final auténtica, build6m32s y auditoría
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2219 fuentes sin cambios,178 payloads.
  APK484651163bytes SHA256
  `7a08285a49753f70cd88d0ac0c02fb09c5726afbb1606ba96b93d053fd17997f`.
- Evidencia externa `client139-quit-replay-20261023-final/`: recibo de
  reconstrucción, build.log, XML de JVM y apk-audit.json. El primer directorio
  sin `-final` conserva el build previo a añadir Threads:1 y no se despliega.
- Instalación autorizada con «Sí, instala», sin juego vivo. Preflight demuestra
  los12 cadáveres con Threads:1, app/logcat y audio residual propio; no servicio
  activo. `adb install -r` correcto, versión139/nombre/UID10330 e identidad SHA
  del base.apk instalado iguales al auditado. `deployment-receipt.json` externo
  verificado, privado. No se abre la app ni se toca ningún formulario.
- Pendiente Quit→Jugar dos veces. La prueba A/B de Login sigue pausada y no se
  atribuye ningún resultado de autenticación ni se confirma aún el arreglo físico.

## Open Issues

- La revisión independiente detectó que un líder Z puede conservar hilos vivos.
  Se exige además Threads:1 en los status frescos que delimitan las lecturas de
  stat/identidad. Es una condición adicional para demostrar terminación, no una
  excepción nueva para procesos vivos. La regresión de líder Z con varios hilos
  y status incompleto forma parte de las13 pruebas positivas del observador.
- Aprobado el cambio explícito de clasificación de zombis y su impacto en las
  dos historias; la prueba existente que exigía Active se actualizará.
- Evidencia privada saneada: directorio externo
  `/home/adrian/wowforever-artifacts/arm64-prototype/quit-replay-20261023/`,
  `observation-summary.json`. No se leyeron RUNs, credenciales, memoria ni campos
  del formulario. No se iniciaron/cerraron juegos ni mataron procesos. Falta
  revisar el delta de audio. Se valida la disponibilidad de pactl empaquetado y
  su ayuda local anuncia `pactl [options] exit` con LD_LIBRARY_PATH equivalente
  al componente. Es una consulta `--help`: no se envía ninguna orden al servidor.
- El revisor por defecto falla por variante; se utilizó la alternativa Sol
  autorizada anteriormente y esta vez completó ambas revisiones de código.
- Esta revisión aprobada sustituye la propuesta antigua de base C135 por
  preservación de la entrega actual. No hay downgrade autorizado.
