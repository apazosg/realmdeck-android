---
id: existing-install-import-2026-10-20
state: in-progress
date: 2026-10-20
---

## Summary

El usuario pregunta si con un USB que ya contiene la instalación del cliente se
puede indicar a la app dónde está para no descargarla de cero. La respuesta corta:
**el motor ya sabe reutilizar una instalación existente**, pero **no había forma
de decir "usa lo que hay en este USB"**: faltaban la acción de preparar la
carpeta, la revisión con veredicto y la interfaz.

> **Corrección de premisa (2026-10-20, verificada en `HEAD`):** al escribir este
> plan se creyó que el motor rechazaba toda instalación pegada. No era así: el
> `updater-gate` ya traía `PastedClientVerdict`, `PastedClientReport`,
> `inspectPastedClient()` y las ramas de admisión por build
> (`ForeverClientUpdateService.kt`, commit `2649bd4`). Lo que faltaba de verdad
> era (a) la capa de UX (preparar carpeta + veredicto), (b) el hueco del
> **marcador de almacenamiento copiado de otro volumen** (un `.forever-client-storage`
> ajeno hace que la app rechace la raíz) y (c) la documentación. El alcance real
> del plan es, por tanto, menor que el inicialmente estimado, pero la función
> es la misma.

Decisión del usuario (2026-10-20): la forma no será "indicar una ruta", sino
**«preparar carpeta»**: la app prepara en el USB la carpeta donde debe quedar el
cliente, muestra de forma inequívoca **qué tiene que pegar, desde qué PC y en
qué ruta**, y cuando el usuario vuelve a conectar el USB la app **verifica** lo
pegado y **adopta** esa instalación como fuente: reutiliza sus segmentos CASC in
situ y descarga solo el delta que falte.

Segunda decisión del usuario (2026-10-20): **se pega solo cliente ARM64**, y
*"no sé si es viable cualquier versión, revisarlo y aplicar la actualización si
corresponde"*. Por tanto la app no da por bueno lo pegado: lo **revisa**
(producto, región, idioma, tags de plataforma, build) y emite un **veredicto**
(§"Reglas de veredicto"); si corresponde, aplica la actualización (delta), y si
no, explica por qué en lenguaje llano. Un cliente x86-64 se rechaza.

Hallazgo central que define el tamaño del cambio: como el usuario pega **dentro
de la raíz del cliente** (la ruta que la app ya usa en ese volumen), el
"update over source" existente —que reutiliza los segmentos de la fuente sin
copiarlos— sirve tal cual. **No hay que tocar el writer ni el motor CASC**:
el trabajo real está en completar la capa de *admisión* ya existente, en la
capa de *almacenamiento* (estado nuevo de inspección) y en la
*UX* (preparar carpeta + verificar + veredicto claro).

Efecto neto esperado: de los ~85 GiB del build 70450 ARM64, se reutiliza todo
lo que coincida por EKey con lo pegado, y se descarga solo el resto. **Medido el
2026-10-20** (ver "Resultado de la medición"): pegar un cliente ARM64 70009
completo tiene **~1,9 GiB de objetos nuevos** en vez de ~85 GiB. Es una
comparación de planes: no acredita una instalación física ni incluye archivos
sueltos o metadatos. Detectar el build objetivo no garantiza descarga cero.

### Resultado de la medición (2026-10-20)

Herramienta: `scripts/arm64-prototype/client/measure_pasted_client_overlap.py`
(test: `tests/test_measure_pasted_client_overlap.py`, 2 casos en verde).
Informe: `/home/adrian/wowforever-artifacts/measure/pasted-client-overlap-2026-10-20/`
(`overlap-report.json`, `overlap-summary.md`).

| Escenario | Objetos reutilizables | Bytes reutilizables | Queda por descargar |
|---|---:|---:|---:|
| Cliente ARM64 **70009** pegado, destino **70450 ARM64** | 1.115.126 | 88.676.255.175 | 29.266 obj / **2.076.580.585 B (~1,9 GiB)** |
| Conjunto local del host (70009 x86-64) frente al plan 70450 ARM64 | 432.021 | 57.633.040.688 | 712.371 obj / 33.119.795.072 B |
| Conjunto local del host (70009 x86-64) frente al plan 70009 ARM64 | 507.753 | 64.519.635.223 | — |

Límites que la propia medición declara: el plan ARM64 70009 tiene 1.319.619
objetos; el manifiesto x86-64 70009 selecciona 1.319.626; de los 692.400 EKeys
del `InstallState` local, 690.000 tienen corroboración por prefijo único en los
índices y 2.400 se excluyeron; **no se releen ni hashean los bytes de los
archives** (la reutilización física queda sujeta a la verificación por EKey del
updater, que es justo lo que implementa este plan); no se incluyen archivos
sueltos. El escenario de la primera fila es una **comparación de planes**, no
la lectura de una instalación ARM64 física.

Conclusión: la función está justificada. El caso de pegar el **build objetivo**
(delta ~0) es el de mayor valor y el que el propio USB que C117 está llenando
ya va a producir.

## Content

### Estado actual (evidencia)

| Hecho | Evidencia |
|---|---|
| El motor ya tiene dos caminos: sin fuente (descarga todo, `writeCascDeltaParallel`, `ForeverClientUpdater.kt:1177`) y con fuente (`writeCascDeltaSerial`, `:958`, *"fetches only misses"*) | `updater-gate/ForeverClientUpdater.kt:940-956` |
| La fuente es **siempre** `<destino>/Data/data`; no existe fuente externa | `inspectTargetState`, `ForeverClientUpdater.kt:1808-1832` |
| Con fuente, el plan ya calcula el delta exacto (`scan.downloadBytes`, `scan.totalObjects`) | `ForeverCasc.inspectTarget(...)`, `ForeverClientUpdater.kt:611-618` |
| Los segmentos de la fuente se **reutilizan in situ** (el índice nuevo referencia los archives viejos; no se copian bytes) | `findCanonicalCandidate` + `outputSegmentHeadersAdded[archiveId]`, `ForeverCasc.kt:1520-1545` |
| El motor admite fuente pegada, pero rechaza destinos no vacíos sin fuente completa y colisiones de archivos sueltos sin propiedad acreditada | `inspectBuildInfo`, `inspectTargetState`, `inspectLooseFiles` de `ForeverClientUpdater.kt` |
| El servicio distingue build objetivo, previo conocido, fuente ARM64 desconocida y admisión gestionada | `inspectInstalled` / `inspectManagedPrior` / `PastedClientVerdict` de `ForeverClientUpdateService.kt` |
| La UI solo deja elegir **volumen**, nunca una ruta; la raíz del cliente la fija la app | `inspectCandidate`: raíz extraíble = `<externalFilesDir>/CustomGames/WoW Forever/` |
| El build objetivo en el dispositivo es **70450 ARM64/enUS**; el host tiene instalado el **70009 x86-64** (mismo producto `wow_classic_beta`, mismo CDN `tpr/wow`) | `docs/launcher-app-status.md` (C117), `client/.build.info` (70009), `forever/contract.json` |
| La app **no puede** leer la raíz de un volumen extraíble: la auditoría del APK prohíbe `MANAGE_EXTERNAL_STORAGE` | `scripts/arm64-prototype/app/audit_functional_ui_apk.py:251` |
| US-007 prohíbe presentar SAF como ruta ejecutable y prohíbe copia/migración automáticas | `.agents/specs/US-007.md` (FR-001, FR-004) |

### Decisiones

| # | Decisión | Motivo |
|---|---|---|
| D1 | El destino de lo pegado es la **raíz del cliente en el volumen extraíble** = `<externalFilesDir del USB>/CustomGames/WoW Forever/` (en el dispositivo: `/storage/6AD6-EF0E/Android/data/com.wowforever.arm64probe/files/CustomGames/WoW Forever/`; desde el PC: `Android/data/com.wowforever.arm64probe/files/CustomGames/WoW Forever/`) | Es la única ruta que la app puede leer sin permisos y sin copiar, y es la que Wine mapea a `A:`. El PC ve esa misma ruta dentro del USB, así que el "desconectar → pegar en el PC → reconectar" funciona. **Verificado en el dispositivo el 2026-10-20** (el código: `inspectCandidate`, `ForeverFunctionalClientStorage.kt:808-812`; en el volumen principal la raíz es `legacyRoot`, que el PC no ve, así que el pegado solo aplica a volumen extraíble). |
| D2 | Se pega **un cliente ARM64**: `Data/` (CASC: `data.000…` + índice local `.idx`) y `.build.info` son **obligatorios**; `WowB-ARM64.exe` y sus dependencias son **opcionales** (solo se aprovechan si el build pegado es el objetivo). **Un cliente x86-64 se rechaza con mensaje explícito.** | El usuario decide pegar solo ARM64. `.build.info` da build, producto y tags de plataforma (el del host dice `Windows x86_64 …`), así que la app puede revisar la versión pegada y decidir. El ejecutable solo se reutiliza si el build coincide con el objetivo: los archivos sueltos son específicos del build, los objetos CASC no. |
| D3 | La reutilización es **por EKey**, con verificación de bytes; la app nunca "asume" que lo pegado sirve | Regla ya vigente: nada se da por bueno sin hash (`ForeverCasc` revalida cada objeto reutilizado) |
| D4 | La adopción es de **fuente**, no de cliente instalado: lo pegado no se declara "cliente instalado" hasta que la app termine su propio build | Evita que un `WowB.exe` x86-64 o un `.build.info` ajeno pasen por cliente válido |
| D5 | La UX es «preparar carpeta» + «verificar» + informe; **no** hay selector de carpetas en esta fase | El usuario lo pidió así; SAF implicaría copia masiva de decenas de GB |
| D6 | Fase B (herramienta de host para preparar la instalación ARM64) queda **fuera de este plan**, salvo que el paso 0 la justifique | Es alcance nuevo grande; el caso primario (otra device/otra máquina ya tiene la instalación) no lo requiere |
| D7 | La app **revisa** lo pegado y emite un veredicto; nunca da por bueno un cliente pegado | Decisión del usuario (2026-10-20): *"no sé si es viable cualquier versión, revisarlo y aplicar la actualización si corresponde"* |
| D8 | Reglas de veredicto (se aplican en el orden indicado) | Derivadas de D7 y de lo que el código ya sabe admitir |
| D9 | El pegado son **`Data/` + `.build.info`** (y opcionalmente el ejecutable ARM64 exacto del destino). **Nunca** se copian `.forever-client-storage`, `.forever-client-update/` ni `.forever-update-staging/` | Son estado interno de la app, no del cliente. Un marcador ajeno bloquea; un marcador válido del volumen no es una entrada ajena |

### Reglas de veredicto (D8)

| Situación de lo pegado | Veredicto | Consecuencia |
|---|---|---|
| No hay `Data/data` o el índice local no se lee | **No corresponde** | No se escribe nada; el mensaje dice qué falta |
| `.build.info` con producto/región/idioma distintos de `wow_classic_beta`/us/enUS | **No corresponde** | Explicar: es otro cliente (p. ej. retail) |
| Tags de plataforma `x86_64` | **No corresponde** | Explicar: esta app ejecuta el cliente ARM64; el solapamiento de datos no compensa |
| Build = **objetivo** (70450) | **Corresponde como candidato** | Calcular el plan y verificar archivos sueltos + CASC; no prometer descarga cero desde metadatos |
| Build = **previo conocido** (70009 ARM64) | **Corresponde** | Ruta de build previo ya existente en la app; descarga solo el delta |
| Build **desconocido** del mismo producto | **Corresponde como fuente CASC** | Los objetos se revalidan uno a uno por EKey contra el catálogo objetivo; los archivos sueltos los baja la app. Se informa: *"build 70xxx no reconocido; se reutilizará su contenido CASC verificado"* |
| Índice CASC ilegible | **No corresponde** | Explicar qué falta; no dar por íntegro el contenido desde metadatos |
| Objeto CASC corrupto detectado durante el plan | **No reutilizable** | No se confía en sus bytes; solo se reutilizan los objetos que pasan la verificación |

### Alcance

**Dentro:**

1. Revisión de un cliente **ARM64** pegado y emisión de veredicto (D8), con admisión como **fuente CASC** cuando el build pegado no es el objetivo ni un previo conocido.
2. Estado de inspección nuevo ("instalación detectada; sin verificar") en la UI de almacenamiento.
3. Acción «Preparar carpeta»: crea la carpeta en el volumen elegido, escribe fuera de la raíz un `LEEME-INSTALACION.txt` con la ruta literal, **que se pegue el cliente ARM64** y el checklist, y muestra en pantalla los pasos exactos.
4. Acción «Revisar cliente pegado»: lectura local, sin escribir en el cliente, que devuelve el **veredicto** (D8) y el build detectado. El plan posterior calcula reutilización y **bytes que quedarían por descargar**; la revisión barata no acredita integridad.
5. Plan/UI: cuando hay cliente pegado utilizable, el CTA pasa a "Completar la instalación" y el coste mostrado es el delta, no los 85 GiB.
6. Spec nueva (US-009) + notas de spec y docs.

**Fuera:**

- Selector de carpetas / SAF.
- `MANAGE_EXTERNAL_STORAGE` o cualquier acceso a la raíz del volumen.
- Tool de host para producir la instalación ARM64 (fase B).
- Cambios en el writer CASC, en el checkpoint, en la transacción de publicación o en la app de parche.
- Publicar APK en el dispositivo mientras C117 esté en marcha (ver "Restricción operativa").

### Restricción operativa (no negociable)

C117 está en marcha en el dispositivo (108/144 operaciones en phase2, USB
`6AD6-EF0E`, journal ACTIVE). `docs/downloader-handoff.md` prohíbe sustituir APK,
resetear o cambiar destino en mitad de la operación. Por tanto: **todo el trabajo
de este plan es offline (Kotlin/JVM + host) hasta que C117 termine o se pause de
forma explícita.** La instalación del APK y la prueba física van al final, y solo
con autorización del usuario.

## Implementation / Action Items

- [x] 0. **Medir el solapamiento real** (offline, host). Resolver el catálogo ARM64 del build publicado (70450) y calcular la intersección de EKeys con el conjunto instalable de un build ARM64 anterior (70009 ARM64). Entregable: nº de objetos y bytes reutilizables, y por tanto **cuánto se ahorra** frente a los ~85 GiB completos. Fuentes: `scripts/arm64-prototype/client/update_manifest.py`, `catalog.py`, `client/Data/.install_state.json` (solo lectura; **no tabular el `client/` vivo**, ver AGENTS.md). Criterio de salida: número con fuente y fecha. El caso x86-64 se documenta aparte (para justificar por qué se rechaza) pero **no es el objetivo**.
  - **Hecho (2026-10-20)**: medir con `scripts/arm64-prototype/client/measure_pasted_client_overlap.py`. Cliente ARM64 70009 pegado → quedan **2.076.580.585 B (~1,9 GiB)** por descargar; el build objetivo pegado → delta ~0. Números y límites en "Resultado de la medición".
- [x] 1. **Decidir la semántica exacta de "cliente pegado"** en el motor, leyendo el código antes de escribir:
  - [x] 1.1 La transacción publica una lista explícita y conserva otros archivos; la admisión del build desconocido puede rechazar entradas inesperadas.
  - [x] 1.2 `inspectLooseFiles` mira solo el catálogo destino: coincidencias se conservan, colisiones de archivos ajenos diferentes se rechazan. No se puede prometer actualizar un cliente anterior entero.
  - [x] 1.3 `Data/data` e índice legible y `.build.info` ARM64 del mismo producto/región/idioma son obligatorios. La copia recomendada es solo `Data/` + `.build.info`.
- [x] 2. **Motor: reutilizar la admisión de fuente existente** (`updater-gate/ForeverClientUpdater.kt`)
  - [x] 2.1 El origen `PASTED_SOURCE` ya existe; fuente `<destino>/Data/data`, sin cambiar writer.
  - [x] 2.2 El `.build.info` pegado informa del origen y se sustituye al publicar; no acredita propiedad de otros archivos.
  - [x] 2.3 Se preserva el rechazo de destinos no vacíos sin fuente CASC completa.
  - [x] 2.4 El plan sigue calculando el delta existente. Se corrige la propagación de `destination.sourceOrigin`.
- [x] 3. **Servicio: revisar el cliente pegado y emitir veredicto** (`updater-gate/ForeverClientUpdateService.kt`)
  - [x] 3.1 Se reutiliza el lector existente de `.build.info`, producto, región, idioma, plataforma y build.
  - [x] 3.2 Se reutilizan los veredictos existentes; no se confunde la revisión de metadatos con verificación completa. Se añade informe del marcador ajeno y salida del bucle de detección hacia el plan.
  - [x] 3.3 Se reutiliza la rama de fuente pegada existente sin convertir su metadato en prueba de instalación.
  - [x] 3.4 Se conserva la prueba de propiedad de archivos; no se amplía por el CTA.
  - [x] 3.5 Se conserva la admisión gestionada existente tras publicación, sin modificar journal ni transacción.
- [x] 4. **Almacenamiento: estado de inspección nuevo** (`overlay/ForeverFunctionalClientStorage.kt`)
  - [x] 4.1 `ClientInspection.State` gana `PASTED_SOURCE` ("Cliente pegado detectado; sin verificar") cuando hay `Data/data` y no hay ejecutable.
  - [x] 4.2 Se preservan los validadores existentes y el rechazo de symlinks; pruebas focales de almacenamiento pasan.
  - [x] 4.3 Preparar en `<externalFilesDir>/CustomGames/WoW Forever/` y mostrar `Android/data/<context.packageName>/files/CustomGames/WoW Forever/` (paquete funcional `com.wowforever.arm64probe`).
- [x] 5. **UX «preparar carpeta» + «revisar»** (`overlay/ForeverFunctionalPocActivity.kt`, `ForeverFunctionalLauncherUx.kt`, `ForeverFunctionalUiText.kt`)
  - [x] 5.1 «Preparar carpeta»: crea el destino y deja fuera de su raíz el LEEME con rutas móvil/PC, objetivo dinámico, `Data/` + `.build.info`, qué no copiar y pasos para desconectar/conectar el USB. Sin sobrescribir archivos y con lease de E/S.
  - [x] 5.2 «Revisar cliente pegado»: solo lectura local; devuelve **veredicto** (D8) y build. El plan posterior calcula objetos/bytes restantes y no se confunde revisión con integridad.
  - [x] 5.3 CTA de gestión «Completar la instalación» para fuente admisible sin versión instalada, hacia qualify/plan existentes; el coste real aparece antes del consentimiento.
  - [x] 5.4 Textos de carpeta vacía, CASC ilegible, producto/plataforma incorrectos y versión no reconocida; no se promete admitir archivos sueltos ajenos.
- [ ] 6. **Pruebas focales** (solo lo tocado; reutilizar infraestructura existente)
  - [ ] 6.1 Updater: fuente pegada → el plan no lanza, `downloadBytes` < total, y los objetos reutilizados se verifican por EKey (negativo: objeto corrupto en la fuente → no se reutiliza).
  - [ ] 6.2 Servicio: una prueba por regla de veredicto (D8): ARM64 objetivo, ARM64 previo, ARM64 desconocido → fuente CASC, x86-64 → rechazo, producto distinto → rechazo, basura → rechazo.
  - [ ] 6.3 Storage: `PASTED_SOURCE` se detecta; el árbol pegado no dispara `storage_*` inválido.
  - [ ] 6.4 UX: textos y mapping de estado→CTA.
  - [ ] 6.5 Host: script de solapamiento (paso 0) con test del cálculo de intersección.
- [x] 7. **Spec y documentación** (US-009 queda borrador para revisión humana)
  - [x] 7.1 Nueva spec `.agents/specs/US-009.md` (importar/adoptar una instalación existente) con ACs, y referencias cruzadas con US-007 (almacenamiento) y US-008 (descarga/actualización). Estado `draft`: pendiente de revisión del usuario.
  - [x] 7.2 `docs/launcher-design.md`: decisión **D-L8** con límites de la medición y colisiones; `docs/launcher-app-status.md`: flujo offline y validación física pendiente, sin atribuirlo al APK instalado.
  - [x] 7.3 No cambia invariantes globales: se reutilizan restricciones de US-007/US-008, sin añadir duplicados a SYSTEM.
- [ ] 8. **Fase física (al final, con C117 resuelto y autorización explícita)**
  - [x] 8.1 Compilar + auditar APK (`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`) y revisar el diff.
  - [ ] 8.2 Prueba física: preparar carpeta → pegado simulado (copia de un cliente ARM64: el `Data/data` que C117 está dejando en el USB, o el de otro dispositivo) → verificar → medir que la descarga pendiente es el delta y no 85 GiB.
  - [ ] 8.3 Prueba negativa: cliente x86-64 o contenido no recognized → la app no escribe nada y lo dice.

## Open Issues

### Corte de validación offline (2026-10-20)

- Snapshot inicial code118: cinco clases JVM, **109 tests, 0 fallos, 0 omitidos**.
  Precede a la reparación final de UX/lease y al CTA; no acredita el árbol final.
- Host focal tras reparación: **16 pasan, 1 excluido** (la aserción preexistente
  del texto de cuenta en `test_account_unavailable_state…`, no relacionada).
- Revisión independiente: detectó adquisición del lease antes de entrar en una
  coroutine cancelable. Corregido: adquisición dentro del bloque IO con cierre
  en `finally`; revisor confirma cierre del bloqueo. Tripwire de código fuente
  añadido; no hay prueba de cancelación Android instrumentada.
- Compilación del primer árbol final detectó un `else` omitido en el texto del
  LEEME. Corregido; la validación canónica final usa una reconstrucción nueva:
  `/home/adrian/wowforever-artifacts/arm64-prototype/client-manager-pasted-client-reviewed-20261020/`.
  Resultado final: **BUILD SUCCESSFUL**, 111 tests JVM sin fallos ni omitidos
  (Updater36, Service14, Storage47, LauncherUx10, Finalization4).
- Auditoría final: **PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT**, 2215 fuentes,
  178 payloads. SHA256 APK:
  `c39aaaad108d670fb7fb5401109aea3f9a63fae0c4c37c7c47d80d3c5cf655d9`.
  Hubo que admitir cuatro descriptores DEX exactos tras revisión independiente:
  `ForeverClientSourceOrigin`, `PastedClientReport`, `PastedClientVerdict` y
  `FreshStagedDescriptor` (este último preexistente). Sin prefijos nuevos amplios;
  5 tests del auditor pasan, incluyendo rechazo de nombres vecinos.
- Revisor confirma cierre del bloqueo de cancelación y no detecta otros
  bloqueantes. No se añadieron harnesses de servicio/volumen ni prueba Android de
  cancelación: la cobertura del CTA y del marcador es focal/pura, no end-to-end.
- Sin despliegue, clics ni escritura en el cliente del dispositivo. Prueba física
  pendiente de C117 resuelto y autorización. El plan permanece activo.

- **O1 — Visibilidad de la ruta de pegado.** La ruta `Android/data/<pkg>/files/CustomGames/WoW Forever/` está oculta en MTP en Android 11+; desde un PC que monte el USB directamente (Linux/Windows) sí se puede crear y pegar. Hay que confirmar en el dispositivo real (MIUI) que (a) la carpeta sobrevive al montaje y (b) el explorador de archivos del móvil no la borra. Alternativa si falla: `adb push` (el usuario ya trabaja con adb) — se indicaría en el `LEEME`. **No** se propone `MANAGE_EXTERNAL_STORAGE` (prohibido por la auditoría del APK) ni SAF (implicaría copia masiva).
- **O2 — Qué se pega exactamente.** D2: cliente **ARM64**, con `Data/` y `.build.info` obligatorios y el ejecutable ARM64 opcional. Si el usuario pega el cliente entero aparecen ficheros ajenos en la raíz (`Launcher.db`, `Logs/`, `WTF/`). Depende de 1.1: si la transacción no tolera la raíz sucia, el `LEEME` exigirá "`Data/` + `.build.info`" y la app avisará de los ficheros inesperados.
  **Resuelto por inspección:** se recomienda solo `Data/` + `.build.info`; un ejecutable ajeno de otra versión puede colisionar y bloquear. La transacción conserva entradas no publicadas, pero la admisión desconocida puede rechazarlas. No se promete admitir cualquier cliente entero.
- **O3 — ~~¿Sirve un cliente x86-64 pegado?~~ RESUELTO (2026-10-20).** Solo ARM64: la app rechaza x86-64 con mensaje explícito (D8). El solapamiento x86-64→ARM64 se documenta en el paso 0 solo para justificar la decisión.
- **O4 — De dónde saca el usuario el cliente ARM64 para pegar.** Hoy el host no tiene downloader ARM64 (`forever-client install` es x86-64; el catálogo ARM64 está fijado, pero no se descargan sus datos). Vías reales: (a) **el propio USB que C117 está llenando ahora**: cuando termine, contendrá el cliente ARM64 70450 completo — ese es el caso "build = objetivo" (delta ~0) y sirve tal cual; (b) una fase B con comando de host para producir un ARM64 70009. Mientras (b) no exista, la instrucción al usuario debe decir de dónde sacar el cliente: "el USB que ya descargaste" u "otro dispositivo que ya lo tenga".
- **O5 — C117 en marcha.** Mientras la operación siga viva, no se publica APK ni se cambia el destino. Si el usuario quiere probar antes, hay que decidir si pausar la descarga (con lo que eso cuesta) o esperar a que termine.
