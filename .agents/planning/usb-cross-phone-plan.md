---
id: usb-cross-phone-2026-10-27
state: in-progress
date: 2026-10-27
---

## Summary

La primera fase autorizada fue pausa, inspección sin cambios y devolución:
«es mi móvil. activo el adb wifi también y probamos. avisame y quito el USB de uno
y lo pongo en el nuevo». Después confirma «Ya está enchufado en el mío» y facilita
un endpoint privado. Después aclara: «pero tenemos que resolverlo en este móvil no?
el objetivo es poder moverlo de un móvil a otro». En ese corte el USB permanece en
el segundo móvil: devolverlo al original no constituye una solución de portabilidad.
La inspección y C160 ya están verificadas. El usuario eligió **«Ambos»**:
reutilizar el cliente terminado y continuar el parcial en el segundo móvil.
La selección está resuelta y después aprobó **«Aprobar criterios»** para
US-009-UC-05 / AC-06–09. Después aprobó FR-009–013/NFR-004 y el protocolo para
preparar las pruebas. El 28-10 aprobó también TC-007–013 para implementación local;
no se declara todavía adopción implementada ni ejecutada. No se ha reiniciado el goal
de rendimiento.

El 28-10 el usuario pide «haz el goal o continua el que habíamos hecho».
Se crea el goal de portabilidad `c51e88cc-70df-4253-9ab3-147edad83c37`, con margen
máximo de seis horas y planificación persistida para ambos casos. El antiguo goal
de rendimiento conserva su cierre no logrado; no se sustituye su aceptación por
esta tarea. Se continúa desde la fixture original sin historial privado verificada
el 28-10, sin repetir emparejamiento ni inspecciones. Al crearlo faltaba la aprobación
de TC-007–013, recibida después, seguida de la autorización de crear, instalar y
probar APKs auditadas en el dispositivo conectado. La adopción conserva su plan real.

## Content

- Original: C160 cerró 19:14:11 UTC con **822.658 objetos durables**, prefix/CRC y
  retención raw válidos, callbacks/HTTP/fases inactivos. Lectura fresca 19:16:32:
  pausa, cero servicios updater y cero hilos fetch/prefix. Evidencia externa en
  `usb-cross-phone-20261027/source-preflight/summary.json`.
- Datos CASC parciales en staging USB, checkpoint y recibo en almacenamiento
  privado del original. `readStageReceipt`/`resumeGuardState` rechazan staging sin
  recibo propio: un rechazo del segundo móvil no demuestra pérdida del contenido.
- No copiar recibos/checkpoints, falsear `.build.info`, publicar staging, saltarse
  guardas ni anunciar cliente completo. Aplican [US-007](../specs/US-007.md),
  [US-008](../specs/US-008.md) y [US-009](../specs/US-009.md); se conservan las
  guardas normales; la nueva entrada tiene requisitos y diseño aprobados, con
  casos de prueba aprobados pendientes de implementación y ejecución.
- El usuario realiza el traslado físico y la expulsión de Android. La ausencia
  de escritores de la app no garantiza ausencia de escrituras del sistema.
- En el teléfono personal, conectar solo al endpoint facilitado. No heredar la
  autorización amplia del dispositivo dedicado: no inspeccionar otras apps,
  cuentas, mensajes, fotos o credenciales, ni cambiar permisos/seguridad/ajustes.
- Revisión de la app y del volumen sin Descargar/Reanudar/Reparar/Preparar carpeta,
  borrado, formato, copia, adopción o instalación/reemplazo de APK no acordado.
  Si no existe una ruta segura de lectura, detenerse y comunicarlo.
- Identificadores y evidencias fuera de Git, conforme a la
  [referencia de control](../references/device-control-scrcpy.md). Mantener ambos
  endpoints separados; no reutilizar el selector automático del original.
- Baseline privado válido. Los listados USB por `exec-out` contienen errores
  `ls` pese a returncode0: evidencia rechazada en `source-usb-inventory-v2/verification.json`,
  no prueba de datos desaparecidos ni inventario válido. No existe hash de todo
  el volumen; no prometer igualdad binaria o integridad completa de sus bytes.
- El nuevo endpoint facilitado por el usuario conecta: Android SDK36. La lectura
  inicial encontró C146 `0.2.122-performance-preview`; el reemplazo autorizado
  posterior por C160 se registra abajo.
  El volumen esperado está montado. El contexto `run-as` recibe Permission denied
  incluso en la raíz del volumen; shell UID2000 puede leer las rutas exactas.
  Esto no demuestra qué acceso tiene la app ejecutada normalmente.
- Inspección de metadatos 19:39:44 UTC: staging esperado, marcador `.owner` cuyo
  digest coincide con el recibo privado original y 81 archivos `data.000`–`data.080`,
  51.625.635.891 bytes (48,08 GiB). Sin `.build.info` publicada en la raíz.
  Cero servicios updater antes/después, cero acciones UI/escrituras/permisos.
  No se han leído ni autenticado todos los cuerpos CASC; no es una prueba de
  integridad completa o de reanudación en el segundo móvil.
- Resultado y límites en [el informe USB](../../docs/usb-cross-phone-results.md).
- Ampliación explícitamente aprobada: el usuario seleccionó «Actualizar C160».
  Se permite reemplazar únicamente la app personal por la APK C160 exacta ya
  auditada, mediante `adb install -r --no-streaming`, sin desinstalar/borrar datos.
  Antes: comprobar hash/tamaño/auditoría y ausencia de updater/guest de esa app.
  Después: comprobar versión, UID conservado y metadatos del staging sin iniciar
  descarga. No se fuerza una firma incompatible ni se reintenta una instalación
  de resultado incierto; no se cambia seguridad ni se migra el checkpoint.
- Reemplazo ejecutado una vez, final 19:52:23 UTC: Android confirmó `Success`,
  versión C160 `0.2.136-bounded-blte-buffers-test` y mismo UID. Cero servicios updater
  antes/después; `.owner` y diccionario completo de nombres/tamaños de los 81 CASC
  sin cambios. No lanzamiento de app, descarga ni juego. No se cotejaron todos los
  datos privados ni los cuerpos CASC. Evidencia externa:
  `personal-c160-install/summary.json` y `adb-install.txt`.
- El usuario pidió abrir y observar la app: «hazlo tú mejor así ves qué ocurre».
  Lanzamiento único de su actividad, 20:06:37 UTC; sin iniciar juego ni operaciones
  del cliente. Veredicto observado: **«Almacenamiento seleccionado no disponible»**.
  El único botón pulsado fue `Detalles`, que solo despliega la ruta: **«No conectado»**.
  Consulta de la operación `MANAGE_EXTERNAL_STORAGE` de esta app: `default` y
  rechazo reciente; no se ha concedido ni cambiado permiso alguno. Esto no prueba
  una causa única ni que conceder ese permiso permita reanudar el parcial.
- Metadatos del USB antes/después de abrir la app iguales: marcador y nombres/tamaños
  de los 81 CASC, 51.625.635.891 bytes. Cero servicios updater; se verificó que el
  proceso adicional de su UID es `/system/bin/logcat`, no un guest. El primer
  controlador se detuvo por rechazar conservadoramente ese logger; se conservó el
  fallo y se completaron solo lecturas faltantes, sin repetir el lanzamiento.
- No se guardaron XML de interfaz sin filtrar ni etiquetas de otras apps. El
  controlador no envió comandos de ajustes; el código C160 preexistente solicita
  volumen multimedia 1 al abrir. No se midió ni restauró el nivel anterior.
- Lectura dirigida 20:24:37 UTC: el identificador `volume` guardado por la app
  **no coincide** con el USB montado, tampoco normalizando mayúsculas. El marcador
  de la raíz sí coincide con la identidad del volumen montado. No es el caso del
  antiguo identificador basado en hash de ruta. Solo se leyó el fichero privado
  específico de selección de almacenamiento; no se modificaron preferencias.
  Evidencia externa: `personal-storage-binding/summary.json` e `identity-shape.json`.

### Continuación aprobada en alcance: cliente terminado y parcial

1. Corregir primero el reconocimiento/selección del USB real mediante el flujo
   normal de la app: enumeración Android, identidad, ruta, marcador y ausencia de
   I/O/guest comprobados. No editar preferencias a mano, elegir por nombre ambiguo,
   crear/preparar carpetas ni borrar el staging. Si el destino no aparece o no pasa
   sus validaciones, diagnosticar ese fallo en lugar de forzar la selección.
   Una selección confirmada puede ejecutar su preflight normal; debe conservar los
   datos existentes conforme a US-007-AC-04/FR-004. Corrección ejecutada una vez
   20:45:46 UTC, con las comprobaciones posteriores indicadas abajo.
2. Mantener **sin permiso de acceso a todos los archivos**: US-007-NFR-002,
   US-009-NFR-002 y US-008-AC-05 lo requieren. El estado `default` observado no
   demuestra por sí solo un defecto ni justifica conceder ese permiso. Sin root,
   excepciones de seguridad ni presentar una URI SAF como ruta ejecutable de Wine.
3. Para un cliente terminado, comprobar reconocimiento/revisión y reutilización
   con las verificaciones de US-009, sin descargar o duplicar lo ya válido. El
   runtime del segundo teléfono conserva sus propias precondiciones; reconocer
   archivos no demuestra que el juego arranque en ese hardware.
4. **Decisión del usuario: «Ambos»**, incluyendo continuar una descarga parcial.
   El USB actual no contiene una instalación publicada y el checkpoint/
   recibo siguen en el original. Ese traspaso no existe en el updater actual.
   Los UC/AC, FR/NFR y el diseño de US-009 están aprobados; revisar los nuevos
   TC-007–013 aprobados el 28-10 para implementar la adopción verificada.
   No copiar estado privado, falsear recibos/checkpoints o publicar un parcial
   como solución provisional; no empezar otra descarga completa automáticamente.
5. Antes de cualquier cambio de cliente: plan/coste/consentimiento explícitos,
   integridad, límites, quiescencia y recuperación preservados. Si hace falta otra
   APK, identificar y auditar esa candidata antes de su instalación ya autorizada.
   El desarrollo local no modifica la C160 instalada ni adopta por sí solo el USB.

La respuesta «Ambos» confirmó el alcance y «Aprobar criterios» confirmó los UC/AC.
La aprobación posterior de FR/NFR y del protocolo autoriza derivar sus casos de
prueba. Después autorizó implementar esos casos y crear, instalar y probar APKs.
Nada de ello es evidencia de reutilización: se conservan auditoría, pruebas y el
consentimiento del plan real antes de adoptar o descargar.

### Selección corregida y puerta de adopción pendiente

- Se abrieron Ajustes/Almacenamiento sin confirmar selección: aparecieron una
  ubicación antigua desconectada y una única «Unidad USB» removible habilitada.
  Las preferencias no cambiaron en esa observación. Un primer intento se detuvo
  sin acciones al no estar la app en primer plano; se preservó ese informe y se
  trajo únicamente la app propia al frente antes de completar las lecturas.
- Selección confirmada una vez mediante la app: identidad persistida y ruta
  coinciden con el USB/raíz original. Su preflight normal crea/renombra/lee/elimina
  una prueba temporal de 16 bytes y guarda la selección/registro privados;
  **no** es ausencia absoluta de escrituras. Cero servicios updater antes/después.
- Cierre 20:46:03 UTC: misma raíz y `.owner`, mismos 81 nombres/tamaños CASC,
  51.625.635.891 bytes; mismas dos entradas raíz, sin restos de la prueba.
  Consulta posterior de la app confirma que acceso a todos los archivos **no está
  permitido**. Sin Descargar/Reparar/Preparar/adopción, juego o cambio de seguridad.
- El cliente sigue «no instalado» porque los datos observados son staging, no una
  publicación. La selección resuelta no demuestra autenticación de todos sus
  cuerpos ni reanudación. No se recuperaron ni copiaron recibos o checkpoints originales.
- Inspección de código: las APIs actuales de CASC/updater exigen checkpoint/recibo
  privado ligado a identidad; no hay entrada de adopción de ese parcial en otro
  teléfono. Los segmentos pueden investigarse para recuperar objetos, pero su
  presencia o `.owner` no demuestra integridad ni qué se reconoció durable.
- El usuario aprobó [US-009-UC-05 y AC-06–09](../specs/US-009.md):
  distinguir completo/parcial, reutilizar solo objetos autenticados, establecer
  estado nuevo sin copiar el privado del original y recuperar fallos sin pérdida
  ni sobrescritura por estado antiguo. FR-009–013, NFR-004 y el diseño han sido
  aprobados, y TC-007–013 aprobados el 28-10; no se omite la guarda actual. El
  [diseño aprobado](../../docs/usb-portability-recovery-design.md) separa revisión,
  verificación, plan y adopción con generación nueva de propiedad sin mover CASC.
- Evidencia externa: `personal-storage-options*/summary.json`,
  `personal-current-usb-selection/summary.json`, sus metadatos antes/después y
  `permission-verification.json`. No repetir esas acciones ya confirmadas.

## Implementation / Action Items

- [x] 1. Cerrar el controlador original y verificar pausa/checkpoint/ausencia de trabajo.
- [x] 2. Usuario comunica traslado al segundo teléfono; no se presupone reutilización.
- [x] 3. Conectar ADB al endpoint autorizado; comprobar versión Android y app instalada.
- [x] 3a. Actualización C160 expresamente aprobada: reemplazo preservador y verificación,
  sin operaciones sobre el cliente ni lanzamiento automático del juego.
- [x] 4. Inspeccionar reconocimiento y veredicto sin operaciones del cliente,
  registrando límites: almacenamiento no disponible, ruta «No conectado»;
  conservación de metadatos comprobada, reutilización/reanudación no demostrada.
- [x] 5. Acordar la portabilidad requerida: usuario confirma «Ambos», manteniendo el
  USB en el segundo móvil. Identificado el impacto US-007/008/009; UC/AC del nuevo
  tratamiento parcial aprobados, implementación pendiente. La devolución queda
  retirada como próximo paso, pues no resolvería el objetivo aclarado.
- [x] 5a. Selección del volumen real resuelta por preflight normal: identidad/ruta
  correctas, sin permiso amplio, 81 metadatos CASC y marcadores preservados.
- [x] 5a.1. Usuario aprueba los criterios de portabilidad UC-05 / AC-06–09.
- [x] 5a.2. Usuario aprueba FR-009–013/NFR-004 y el diseño para preparar las pruebas.
- [x] 5a.3. Usuario aprueba TC-007–013 el 28-10 para implementación y pruebas locales;
  no se declaran todavía cobertura ejecutada.
- [ ] 5a.4. Implementar y verificar el protocolo aprobado tras la puerta de tests,
  con revisión sensible y APK exacta auditada antes de la instalación autorizada.
- [ ] 5b. Validar reutilización del cliente terminado y continuidad del parcial
  mediante el protocolo verificado y consentimiento del plan real; no asumirla.
- [ ] 6. Documentar conservación, reutilización observada o bloqueo seguro y pendientes.

## Open Issues

- Kernel local `ForeverCasc.inspectForeignStage` implementado: autenticación por
  objeto/EOF, catálogo suministrado, identidad de segmentos y digest de inventario;
  callbacks y resumen siempre provisionales, nunca autoridad para adoptar. Exige
  lease real y revalidación del llamador; no usa el loader que limpia/trunca colas.
  Resultado original recuperado sin rerun: tres checks, cero fallos. Ampliación
  posterior con N encoded >1 MiB: solo los dos targets afectados, cero fallos;
  fuente `e0630ccc…d2743`, tests `f38f996f…6b29`. Evidencia externa en
  `usb-portability-local-20261028/large-encoded-scanner-20261028/`.
  Revisión independiente de código todavía pendiente: el revisor no tuvo acceso
  a archivos. No es cobertura completa de TC-009/010 ni adopción sobre el USB.
- Descubrimiento ligero local añadido, aún sin UI ni autenticación: distingue vacío,
  publicación candidata, parcial candidato y disposición ambigua con lecturas de
  metadatos acotadas, bajo lease real. Un candidato no declara objetos verificados
  ni autoriza adopción. Mantiene la barrera privada de intención pendiente.
  El primer resultado ejecutado detectó que la guarda ordinaria de staging pendiente
  impedía también inspeccionarlo; se permite esa inspección solo en esta entrada
  de lectura, sin cambiar las entradas mutantes. GREEN posterior: un test focal,
  cero fallos/errores/skips, JUnit 28-10 19:32:14, Gradle 3 min 27 s, fuentes idénticas.
  Evidencia externa `usb-portability-local-20261028/discovery-green-tests/summary.json`;
  fallo previo preservado, sin repetir checks verdes ni tocar el USB real.
  Revisión sensible solicitada pero no obtenida por variante no disponible; no
  se considera suplida por estas pruebas ni por inspección del padre. Integración,
  verificación de cuerpos, recuperación durable y auditoría APK siguen pendientes.
- Guarda privada de intención pendiente implementada localmente: su presencia,
  incluso con bytes malformados, bloquea plan, execute, recover y Resume ordinarios;
  la lease conserva la operación pendiente. Un test focal pasa (JUnit 19:10:01 UTC),
  sin modificar datos del destino ni retirar la intención. Es solo la barrera de
  entrada: parser, publicación durable C/P/S y recuperación explícita siguen pendientes,
  al igual que la revisión sensible de esta integración. Sin acción en el USB real.
- Integración local del recibo generacional: tres controles del updater reutilizan
  el lector canónico acotado de Transaction y su hash privado, sin admitir un stage
  extranjero ni un checkpoint nuevo. Tres tests focales pasan (JUnit 18:21:51 UTC),
  incluido rechazo del hash antiguo y del workspace extranjero; fuentes exactas
  verificadas. Notificación perdida recuperada sin repetir Gradle. Revisión sensible
  solicitada pero no obtenida por fallo de disponibilidad del modelo; sigue pendiente
  para esta integración, sin atribuirle la revisión anterior del marcador.
- Prerrequisito local de generaciones: creación v1 preservada, marcador v2 ligado
  a stage/identidad/UUID y hash privado exacto, lectura acotada y longitud autenticada
  en publicación. Cinco tests focales/legacy pasan; revisión sensible de snapshot
  sin hallazgos, sin acceso del revisor al filesystem ni comandos. No acredita
  todavía intención C/P/S, cortes v2 específicos ni bloqueo obsoleto tras publicación.
  Sin nueva APK, adopción o escritura en el USB real. Verificador CASC en desarrollo
  separado; ningún recuento del USB ha sido autenticado por esta implementación.
- TC-007–013 aprobados el 28-10. El usuario también autoriza «crear apks, instalarias
  y probarlas en el dispositivo conectado por el adb wifi». No repetir preguntas de
  permiso por esa clase de acción; conservar auditoría de candidata exacta, preflight
  y datos. Esta autorización no elimina el plan y consentimiento reales antes de
  adoptar el parcial o descargar su delta, ni autoriza borrados o cambios de seguridad.
- Nuevo escenario solicitado el 28-10: el usuario borró los datos de la app y devolvió
  el USB al móvil original. Lectura 17:22:09 UTC: la app informa C160, sin workspace
  privado previo, admisión dinámica ni preferencias de almacenamiento en las rutas
  comprobadas. USB esperado montado, `.owner` igual al histórico y mismos 81 nombres
  y tamaños CASC, 51.625.635.891 bytes. Sin lanzar app, UI, descarga o adopción;
  cero servicios updater antes/después. No hay nuevo recuento de objetos autenticados.
  Esta fixture prueba ausencia de estado local; no sustituye la validación entre
  teléfonos distintos ni aprueba los TC pendientes. No restaurar el checkpoint antiguo.
- Actualización C160 completada y verificada en versión/UID/metadatos. La app se
  abrió después por petición expresa, sin descargar/reparar/adoptar el parcial ni
  migrar el checkpoint. La inspección de archivos del cliente sigue siendo de
  solo lectura.
- El usuario confirma ambos casos. Resolver la selección primero no autoriza
  todavía a mutar/adoptar el staging con un protocolo sin especificar/aprobar.
- El desajuste de selección está resuelto. Falta adopción/reutilización del parcial;
  el estado privado del original no es transferible por copiarlo o saltarse guardas.
- UC/AC, FR-009–013/NFR-004, diseño y TC-007–013 aprobados explícitamente:
  cliente completo, revisión sin efectos, verificación acotada,
  preservación ante errores/interrupción, consentimiento, cortes durables y rechazo
  obsoleto antes/después de publicación. Su implementación y cobertura ejecutada
  siguen pendientes; la aprobación no acredita código ni recuperación física.
- Mantener la ruta es relevante: las cabeceras de segmento CASC incorporan su ruta
  absoluta. El diseño propone una nueva generación del marcador, enlazada por el
  `markerHash` del recibo, para invalidar estado obsoleto sin renombrar los segmentos.
  No se ejecuta esa transición ni se confía en el marcador como prueba de objetos.
- El diseño aprobado concreta exclusión en host único, intent antes de cambiar
  propiedad, reserva del workspace antiguo y recuperación por bindings exactos.
  La representabilidad sin truncar datos extranjeros y el bloqueo de estado obsoleto
  en todas las rutas mutantes requieren pruebas; no se dan por demostrados por la
  revisión preliminar de un snapshot, sin acceso a archivos ni ejecución.
- No conceder permiso amplio como atajo: contradice los requisitos vigentes. Si un
  cambio de contrato resultase imprescindible, requiere otra decisión explícita.
- US-007 cubre selección sin migración y US-009 revisión/reutilización de una fuente
  CASC legible con metadatos de instalación. US-008 protege el staging y checkpoint;
  no se ampliarán silenciosamente para confiar en estado privado de otro teléfono.
- Primer intento de conexión 19:24 UTC no conectó; no se ejecutaron consultas
  sobre la app, acciones UI, instalaciones o escrituras de cliente en el personal.
- El traslado fue confirmado; no hay confirmación expresa de expulsión del sistema.
- No se acredita migración del parcial, instalación completa ni arranque del juego.
