---
id: x64-certstore-initialization-trace-2026-10-13
state: paused
date: 2026-10-13
---

## Summary

**Suspendido el 2026-10-14:** el usuario aclara que el objetivo es hacer funcionar
el cliente ARM64 con Classic Forever; parchear memoria o archivo son métodos
posibles, no requisitos del resultado. Btrfs es otro proyecto. No continuar controles dinámicos
x64 ni acceder a la base protegida como siguiente paso. El análisis x64 existente
queda como referencia auxiliar; sus resultados no identifican un parche ARM64.
La propuesta `x64-casc-reference-export-plan.md` queda descartada sin ejecución.
La aclaración posterior permite consultar código/configuración del proyecto
Btrfs en solo lectura como referencia, pero prohíbe modificar sus archivos,
permisos, snapshots, contenedores, servicios o datos. No ejecutar sus scripts o
entrypoints con efectos sobre ese proyecto.
El resto de este documento conserva el alcance y los resultados históricos.

El usuario pide investigar dinámicamente el **cliente x64 funcional del PC**:
cuándo aparece el almacén real de doce claves, qué instrucciones lo escriben,
de dónde proceden los datos y qué características permiten buscar después la
rutina equivalente ARM64. No se pretende implementar ahora el parche ARM64.

Esta fase cambia de análisis estático a ejecución y depuración de una sesión de
laboratorio. Se conserva el cliente original, no se conecta a sesiones de juego
existentes y no se utiliza el supervisor productivo que aplica el grupo 8.
El resultado será evidencia de construcción o una limitación reproducible, no
una afirmación de autenticación basada en que el debugger arranque.

## Content

### Autoridad y entorno observados

- Cliente de referencia x64 existente: `client/WowB.exe`, 102916816 bytes,
  SHA-256 `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92`.
  Se confirmarán nuevamente PE32+, Machine `0x8664` y VERSIONINFO del archivo
  copiado antes de ejecutar. No se prueba x64 en Android.
- El contrato validado reconoce doce entradas de 40 bytes y el grupo 8 en offset
  284 del bloque de 480 bytes. La validación completa, no un prefijo de ocho
  bytes, identifica el almacén real.
- `forever-patch scan` es read-only. Se puede reutilizar el core de validación,
  pero hay que añadir identidad estable del hijo y marcas temporales al detector
  de laboratorio; no usar `ensure`, `patch_stores` ni escritores de memoria.
- El supervisor de `wowserver` puede escribir aunque se seleccione
  `--diagnose-readable-keys`. **No ejecutarlo como si fuese un modo read-only.**
- Imagen local documentada disponible:
  `wowserver/forever:auth-candidate`, digest
  `sha256:9d553802d94756f06968f12ebfe31a4a71060a8ee73267353a4879984260ba06`.
  Su disponibilidad no prueba que incluya herramientas de depuración ni que
  exista una instalación completa apropiada para esta nueva sesión.
- Host: Wine 10.0, `winedbg`, Docker y Xvfb disponibles; `gdb`, `lldb`, `rr` y
  `gdbserver` no encontrados en PATH. Yama `ptrace_scope=1`: debugger/detector
  deben operar sobre su propio árbol de laboratorio, sin cambiarlo ni usar root.

### Sesión de laboratorio, no producción

1. Elegir el runtime Wine 11 de referencia aislado, preferentemente la imagen
   local fijada, después de inspeccionar su contrato de arranque sin ejecutarlo.
   Usar contenedor/nombre/prefijo/display propios; no invocar entrypoints que
   lancen patcher, servicios de autologin, Wolf/Moonlight o sesiones player1/2.
2. Usar una copia verificada del ejecutable y datos **consistentes existentes**:
   snapshot/base inmutable de la instalación funcional, montados solo lectura,
   con capa de trabajo propia para escrituras normales del cliente. No tar en
   vivo, descargar CASC, modificar Btrfs productivo ni copiar HOME, registros,
   cuentas o WTF personales. Si falta una fuente utilizable sin privilegios,
   detenerse y registrar el bloqueo en vez de usar un cliente parcial como si
   reprodujese la instalación funcional.
   Ampliación aprobada el 2026-10-13 («adelante»): generar una copia técnica
   sanitizada de `/opt/wowserver/client-base` en el laboratorio y reconstruir
   únicamente las 16 referencias KMT del archive 18 demostradas por la auditoría
   actual. Usar headers existentes, no modificar payloads, no descargar CASC ni
   tocar el origen, producción, permisos o cuentas. Verificar build/manifiesto,
   índices y headers antes de decidir si permite el ensayo; no confundir esos
   checks con una validación completa de todos los payloads.
3. Generar configuración y prefijo desechables sin identificadores de cuenta,
   Remember Account, patch automático ni entrada de Login. No leer secretos de
   `wowserver`. No entrar en cuentas ni atribuir un fallo pre-login al servidor.
4. Mantener todos los ejecutables, proyectos, trazas y logs grandes fuera del
   repo, bajo `wowforever-artifacts/work/x64-certstore-trace-<id>/`; informes
   exclusivos `0600`, carpeta privada y fuente/hash antes/después. El repo
   contiene únicamente herramientas, tests sintéticos y el informe técnico.

### Depuración e instrumentación

- Priorizar WineDbg o GDB con interfaz de depuración Wine. Instalar herramientas
  necesarias solo desde una fuente oficial/autenticada en el entorno aislado,
  sin sudo, cambio global de seguridad ni modificación de la imagen productiva.
  Verificar primero con una pequeña fixture sintética que una watchpoint
  identifica la instrucción y la pila de una escritura conocida.
- El detector read-only reutiliza `load_contract`/`scan_stores` y exige un único
  bloque compatible, PID/start-time/UID/ejecutable/prefijo del proceso hijo
  verificados antes y después de leer. Registrar cero/uno/múltiples candidatos,
  cobertura y errores; no interpretar un scan incompleto como ausencia.
- Comenzar el detector y el debugger **antes de ejecutar el cliente**, con reloj
  monotónico común. Registrar eventos de módulos y hitos disponibles del cliente
  junto con el intervalo entre la última observación completa sin almacén y la
  primera validación. Esa observación acota aparición: no es necesariamente el
  instante exacto de creación.
- Una watchpoint colocada cuando el bloque ya está completo **no demuestra su
  construcción**. Etiquetar sus hits como uso/escritura posterior. A partir de
  ese resultado, preparar un reinicio diagnóstico con el punto de asignación o
  la rutina de copia realmente observados, sin reutilizar direcciones de heap de
  otro proceso ni desactivar ASLR globalmente.
- Preferir watchpoints/hardware breakpoints sobre un subconjunto definido del
  bloque. Si hacen falta breakpoints software o single-step, delimitarlos como
  trampas transitorias del debugger en ese hijo: no son un parche de confianza,
  no cambian resultados criptográficos y nunca se guardan en el PE original.
- Para cada escritura pertinente: hilo, instrucción que realmente escribió
  (distinguir IP posterior al trap), módulo/RVA, pila acotada, ancho y posición
  dentro del bloque, cambio de IDs/flags y coincidencia con claves públicas
  conocidas. Seguir origen de copia únicamente cuando el flujo observado lo
  justifique: dirección de módulo como RVA, fragmentos por igualdad, o componente
  que suministra el buffer. No volcar heaps, pilas completas, cuentas o claves
  ajenas al contrato.
- Registrar la región contenedora: permisos, privada/compartida, respaldo de
  archivo o anónima, tamaño, relación con la asignación y cambios observados.
  Entre ejecuciones comparar estas propiedades y RVAs de escritores/lectores,
  no reutilizar direcciones virtuales del heap.
- Si la construcción queda identificada, observar de forma acotada también las
  consultas posteriores con watchpoints de lectura/acceso o instrumentación de
  un consumidor ya demostrado. Distinguir lector, escritor y copia genérica;
  no ampliar a una traza general de memoria ni asumir que lectura implica
  autenticación o selección del grupo 8.
- El slot estático x64 RVA `0x48f6870` contiene `g9h0 || g10h1`, no grupo 8.
  Solo un acceso o flujo de datos observado puede vincularlo a la construcción;
  no convertir la proximidad o igualdad de mitades en prueba de consumidor.
- No alterar claves, flags, padding, firma, verificador, funciones de retorno ni
  ejecutables. La instrumentación observa escrituras **del cliente**. No usar
  `ptrace`/debugger contra procesos existentes o ajenos; si el contenedor/política
  bloquea al debugger hijo, registrar el rechazo, no conceder CAP_SYS_PTRACE ni
  relajar seccomp/Yama/SELinux por defecto.

### Presupuestos y evidencia mínima

- Hasta tres arranques separados y razonados: observación inicial, origen del
  escritor y repetición del hallazgo. Cada control: máximo 300 s + 30 s cleanup;
  ventana detallada de trazado máximo 30 s/64 eventos y pila máximo 16 frames.
  No repetir a ciegas si no se dispone de un almacén completo o fuente consistente.
- Cleanup solo de handles/hijos y contenedor/prefijo de este laboratorio; nunca
  kill por UID/nombre ni `wineserver -k` sin prefijo explícito. No detener, adjuntar,
  reiniciar ni modificar servicios/clientes que estén en uso.
- Informe `docs/x64-certstore-initialization.md`: responder las cinco preguntas
  con observaciones, no supuestos; scripts/comandos y hashes, cronología, escritor
  confirmado/call stack, módulo/RVA, fuente de datos probada y límites.
- El criterio para declarar rutina de construcción identificada es un evento
  dinámico de escritura/materialización ligado al bloque que posteriormente
  valida las doce entradas, preferentemente reproducido en otro arranque. Un
  `memcpy` observado debe distinguirse del constructor que suministra su origen.
- A continuación buscar ARM64 por semántica: llamadas, algoritmo, recorrido de
  entradas, tamaños y flujo de datos. No trasladar RVAs ni asumir la tabla x64.
  Recomendar parche estático, dinámico o interceptación solo según la evidencia;
  no implementar ninguno en esta fase.

## Implementation / Action Items

- [x] 1a. Usuario aprueba ejecución aislada sin Login, parche automático ni
  modificación criptográfica; añade metadatos de región, estabilidad entre
  ejecuciones y lectores posteriores cuando sea posible (2026-10-13).
- [ ] 1b. Fijar copia x64/runtime/fuente de datos
  consistente sin acceder a producción, credenciales ni sesiones existentes.
- [x] 2a. Fixture sintética Linux x86-64: watchpoint hardware antes de inicializar,
  escritor y lector con instrucción/pila/región, contrato12×40 validado y exit0.
  Test positivo con dos hijos independientes; reporte manual externo conservado.
- [x] 2b. Mecánica sintética Wine11 validada: escritor, lector, pila, región y
  validación completa. No equivale a validar el contexto del cliente WoW.
- [ ] 3. Observar el arranque x64 desde su inicio, validar el almacén y acotar su
  aparición sin aplicar el parche ni pulsar Login.
- [ ] 4. Obtener evidencia del escritor/origen mediante trazado dirigido y una
  repetición diagnosticada; detenerse si no se alcanza el estado necesario.
- [ ] 5. Relacionar los datos estáticos con el flujo observado y plantear la
  comparación ARM64 por comportamiento, sin introducir un parche.
- [ ] 6. Publicar informe/evidencia y decidir la técnica siguiente por resultados
  reales; conservar originales y límites de los controles inconclusos.

## Open Issues

- **Estado actualizado 2026-10-14:** copia sanitizada/16 KMT y 42 dependencias ya
  preparados; dos fixtures Linux/Wine y tracer Win32 propios validados. Los
  controles reales no superan CASC. Tras corregir locale/build-info/alias/locks y
  disponer de copias ordinarias RW de archives e índices, el último control
  `x64-certstore-trace-20261014T112637Z-1881800` sigue rechazando el grupo patch
  `93483a247c83d099458c1a92a7d4742f`, sin Error5. 125 scans privados RW completos,
  cero bloques, timeout124; sin Login/watchpoints/constructor demostrado.
  Rebuild offline con todos los 76 inputs produjo bytes idénticos: no repetir el
  control con el mismo grupo ni manipular expected hashes para forzar aceptación.
  Se mantienen pendientes datos funcionales y todas las preguntas del cliente.
  Ver `docs/x64-certstore-initialization.md` para límites de auditoría y controles.
  Propuesta de datos posteriormente descartada sin ejecución:
  `.agents/planning/x64-casc-reference-export-plan.md`. La base protegida Btrfs
  pertenece a otro proyecto; no se reactivará esta exportación. La consulta de
  código/configuración como referencia sí está permitida, exclusivamente en lectura.

- **Archivos CASC abiertos por el cliente:** la capa privada de índices resolvió
  `shmem.lock`, pero `x64-certstore-trace-20261014T092108Z-1238936` falló abriendo
  `data.000` con Win32 Error 5. Esos archivos siguen apuntando al mount RO.
  El siguiente control podrá usar una copia de trabajo ordinaria nueva de los
  93 archivos, nunca hardlinks ni cambios de permisos en la fuente. El coste
  estimado adicional es 93 GB; comprobar espacio y copiar con hash/identidad
  antes/después. Config/indices de origen y los 42 dependencias permanecen RO;
  los payloads originales no se alteran. Los writes normales del cliente quedan
  confinados a esta copia desechable; no se falsifican certificados ni índices.
  Si aún falla, conservar la causa real y no inferir ausencia del CertStore.

- **Coordinación CASC privada:** una vez corregido `..\\Data`, el control
  `x64-certstore-trace-20261014T075236Z-765809` abrió el config y falló al crear
  `z:/lab/Data/data/shmem.lock` (`CascLocked`). La fuente seguirá montada
  read-only. La vista propia añadirá `Data/config`/`Data/indices` como links RO,
  los 93 `data.NNN` como links al mount RO y copias CREATE_NEW verificadas de los
  16 `.idx` en un directorio privado writable para coordinación/caché local.
  Ambos aliases `game/Data` y el padre Data resolverán a esa misma vista. No se
  reparan bytes de payload ni se reutiliza esta capa en otra ejecución; no red,
  Login, edición de claves o modificación de producción/fuente.

- **Data en el padre confirmado por traza:** el control pasivo
  `x64-certstore-trace-20261014T073354Z-661971` conserva operaciones de archivo
  para el config fijado clasificadas como `parent-data` (`..\\Data`), no la
  carpeta `game\\Data`. Se añade el alias propio `/lab/Data -> /source/Data`
  junto al ya existente `game/Data`, ambos hacia el mismo mount read-only.
  El config público existe (83820 bytes, MD5 coincide con
  `05215079e3905ef5922ae0b03ffefb73`). No se cambia el dato, no se abre red ni
  se infiere que el `CertStore` vaya a existir después de esta corrección.

- **Metadato CASC de la vista propia:** después de fijar enUS, el cliente informó
  `No active install info entries`. La vista de ejecución había omitido
  `.build.info`, aunque existe y está validado en la copia técnica. Se añade
  únicamente un link al mount read-only `/source/.build.info`, con SHA fijo
  `5f99a2d48e442a51d2b9c2ea4dae860240476499b3726640e783f7cef5b30626`
  y comprobación antes/después. No se cambian payloads ni índices: los 42 archivos
  del manifiesto son dependencias sueltas, no todos los metadatos de instalación.
  La repetición es discriminante del error actual, sin red ni Login.

- **Bloqueo visual resuelto en la configuración propia:** el control pasivo con
  display capturado quedó en el selector de idioma (tres imágenes idénticas),
  no en la pantalla de Login. Se fija `textLocale/audioLocale=enUS`, coincidente
  con la instalación técnica seleccionada, solo en el WTF nuevo del laboratorio.
  Se usa el CVar documentado de narración `accessibilityScreenNarrationEnabled=0`.
  Una repetición diagnosticada mantendrá red bloqueada y ninguna entrada de
  Login, sin reutilizar ni editar configuraciones personales o del origen.

- **Continuación diagnóstica solicitada («sigue porfavor», 2026-10-14):** los
  tres controles reales anteriores no identificaron el almacén. El control pasivo
  mantuvo vivo el proceso hasta 300 s pero tampoco produjo `gx.log`; no demuestra
  fallo exclusivamente causado por el debugger. Se añade un nuevo control
  pasivo, con capturas acotadas del display Xvfb creado exclusivamente para ese
  laboratorio a 20/60/180 s, sin Login, entradas, cuentas, red o modificaciones
  criptográficas. No captura el desktop del host ni sesiones existentes. El
  resultado sirve para identificar un bloqueo visible de arranque, no para
  declarar autenticación, construcción o ausencia global de claves.

- **Control discriminante sin puerto de depuración (2026-10-13):** el segundo
  arranque real aislado terminó en `0xC00000E5` tras 2101 excepciones observadas,
  sin `gx.log` ni tabla validada. Se pasó cada excepción al cliente como
  `DBG_EXCEPTION_NOT_HANDLED`; no se ocultó debugger ni se alteró el verificador.
  El tercer arranque permitido comparará exactamente el mismo cliente/datos y
  configuración con un detector read-only que no usa `DEBUG_ONLY_THIS_PROCESS`,
  breakpoints, contextos ni watchpoints. Conserva el handle/creation-time del hijo,
  la validación de 480 bytes y los límites 300+30 s. Este control permite separar
  un problema de la ejecución bajo debugger de un fallo de base/runtime; no
  demuestra por sí solo causalidad, inicializador o autenticación. No habrá Login
  ni una cuarta repetición automática.

- **Vía local para generar los datos del laboratorio (2026-10-13):** ante la
  pregunta «no puedes generarlo tu?», se auditó de nuevo, sin escrituras,
  `/opt/wowserver/client-base`. El build coincide con los hashes del contrato
  70009; hay 93 archives consecutivos (0–92), 16 KMT válidos y 1488/1488 headers
  válidos. Solo faltan las 16 referencias KMT del archive 18: 1472/1488 cubiertas,
  sin headers malformados ni archives ausentes en ese rango. Esto confirma el
  defecto de índices, pero **no valida todos los payloads CASC**.
  Existe una reparación determinista que reconstruye esas referencias desde
  headers existentes, sin descargar datos ni modificar los archives. La vía
  propuesta es una copia técnica sanitizada fuera de producción, reparar solo
  sus índices y verificarla antes de ejecutar WoW; no usar hardlinks ni copiar
  WTF/HOME/cuentas. Hay aproximadamente 1,3 TB libres, suficiente para una copia
  de unos 93,7 GB. **Ampliación aprobada («adelante»); copia y reparación aún
  pendientes de ejecución.** No se tocará
  `client-base`, el Btrfs productivo ni sus permisos. Si aparecen otros defectos
  o falta contenido, no se inventará: se distinguirá una descarga nueva del
  trabajo local ya aprobado.
- **Bloqueo actual de datos:** las fuentes accesibles `client/` (~17,1 GB) y
  `/opt/wowserver/client-base` (~93,7 GB) no son una instalación funcional
  consistente verificada; la segunda tiene defectos KMT documentados. El lower
  de 120 archives documentado está bajo `shared-runtime` root0700 y no es
  accesible sin privilegios. No se creó ningún contenedor ni se ejecutó WoW.
  Hace falta una exportación/vista de datos consistente, de solo lectura y
  separada de producción; no el HOME ni los WTF/cuentas de un jugador.
- Resultado parcial en `docs/x64-certstore-initialization.md`: se han probado
  mecanismos de observación en una fixture, no el momento de construcción,
  fuente de claves, consumidores ni correspondencia ARM64 del cliente real.

- Aprobación explícita recibida: «Puedes proceder con la ejecución». Se prioriza
  evidencia reproducible aun si no se identifica la rutina. Solo se estudia el
  x64 del PC; no se implementan modificaciones ARM64 ni mecanismos de parcheo.

- Las pruebas reales x64 documentadas localizaron el almacén después de envíos
  autorizados de Login; no prueban que se construya necesariamente entonces.
  Esta fase no introduce credenciales ni pulsa Login. Si no aparece antes, hay
  que comunicar ese límite y delimitar otra interacción antes de ejecutarla.
- La imagen disponible y el host tienen versiones Wine diferentes. Una sesión
  Wine 10 alternativa no se presentará como reproducción exacta de Wine 11.
- Una dirección descubierta al terminar la construcción no permite vigilar
  retroactivamente sus primeras escrituras. Hay que demostrar la procedencia o
  usar otra ejecución con instrumentación armada a tiempo.
- `.rdata` puede contener constantes SIMD, descriptores o material indirecto:
  identificar la mitad de una clave no prueba su uso ni un punto modificable.
- **Incidente de inspección:** consultar `winedbg --help` ejecutó inicialización
  Wine 10 y creó `/home/adrian/.wine`, con errores por falta de display. No se
  ejecutó WoW, no se inspeccionaron cuentas y no se usará ni borrará ese prefijo.
  No volver a ejecutar herramientas Wine ni ayuda que inicialice runtime fuera
  del entorno de laboratorio aprobado. Las demás comprobaciones fueron de
  herramientas, docs, política y digest local de imagen, sin arranque del juego.
