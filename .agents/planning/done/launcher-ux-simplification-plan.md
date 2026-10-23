---
id: launcher-ux-simplification-2026-10-19
state: done
date: 2026-10-19
---

## Summary

Auditoría de UI/UX del launcher ARM64 (`ForeverFunctionalPocActivity`) con el
objetivo de simplificar: **una sola acción principal, siempre válida**, **el
estado real de la app reflejado en la pantalla inicial**, y **sin información
técnica innecesaria**.

La auditoría se verificó **en el dispositivo en vivo** (192.168.1.133:34313,
`com.wowforever.arm64probe`, 2026-10-19) con una descarga de cliente **en curso**
(el servicio en primer plano `ForeverClientUpdateForegroundService` estaba
activo, PID 24930). El volcado de la pantalla inicial durante esa descarga es
la evidencia central del hallazgo H1.

Resultado: **3 hallazgos críticos, 3 medios, 2 bajos** y una observación positiva. El peor de ellos es que
la pantalla inicial es **ciega al subsistema de descarga** y ofrece una acción
principal que **no puede funcionar**.

Propuesta: un bloque único de **estado real** + un **CTA único dependiente del
estado** + la tarjeta de GPU fuera del inicio + errores con texto humano +
descarga visible a un toque + **perfil de servidor fijado en tiempo de
compilación**. Sin tocar runtime, parche, hashes, permisos ni el motor de
descarga.

**Aprobado por el usuario el 2026-10-19:** «ok, hazlo», con revisión visual
iterativa de capturas hasta conseguir un resultado satisfactorio.

### Correcciones de la auditoría al iniciar

- `DisposableEffect(session)` ejecuta la lectura EGL al entrar en composición o
  cambiar la sesión, no en cada recomposición. Mover la tarjeta evita ejecutarla
  al abrir Inicio, pero no se atribuye un coste por recomposición inexistente.
- Instalar con `install -r` conserva datos **pero reinicia el proceso y corta la
  transferencia**. No instalar sobre la descarga activa sin autorización de una
  pausa controlada. Primero validar offline y capturas en entorno aislado si es
  viable; consultar antes de afectar la operación existente.
- No inferir cliente listo, versión instalada ni pausa recuperable solo por
  archivos presentes o por ausencia del servicio. No inventar porcentaje/ETA.
- H9 es una observación positiva, no un noveno defecto. H8 se corrige únicamente
  como parte del texto compartido necesario para Inicio.

**Autorización operativa posterior:** el usuario eligió «Sí, pausa y reanuda»
para validación visual en el móvil. Cuando la candidata esté compilada/revisada,
se permite pausa cooperativa, comprobar cero workers, preservar checkpoint y
recibo, instalar sin clear-data y reanudar **la misma build/destino**, dentro del
coste ya autorizado. No reinstalar mientras sigue la operación activa.

**Criterios de aceptación visual:** revisar capturas renderizadas, no solo XML;
estado y acción visibles sin scroll en el móvil; jerarquía clara, texto breve,
sin versiones inventadas, barra/porcentaje solo con datos válidos; Ajustes y
subpantallas sin recortes ni controles amontonados; áreas táctiles ≥48dp y
contraste legible. Iterar si la primera captura muestra defectos. Aprobación
estética final del usuario sigue siendo necesaria, no se presume.

## Decisiones del usuario (2026-10-19)

| # | Decisión |
|---|---|
| D1 | **O1**: cliente descargado pero no verificado (`PRESENT_NOT_VERIFIED`) → CTA **«Completa la instalación»** |
| D2 | **O2**: el perfil de servidor se **decide en tiempo de compilación**; la APK no lleva las dos opciones de perfil |
| D3 | **O3**: **una sola ruta de lanzamiento**, con consentimiento explícito en ambos caminos (se elimina el atajo de «Jugar») |
| D4 | **O4**: la tarjeta **«Compatibilidad gráfica» sale del inicio** a `Ajustes → Compatibilidad gráfica` |

## Content

### 1. Estado actual verificado en el dispositivo

Volcado de `uiautomator` de la pantalla inicial **mientras la descarga corría**:

```
WoW Forever
Cliente
Servidor: Original
Falta WowB-ARM64.exe · versión 1.60.1.70009
Almacenamiento: Unidad USB
Detalles
Preparar WoW                      <-- acción offered; imposible (no hay cliente)
Compatibilidad gráfica
Mali-G610 MC6 · arranque/render probado (histórico)
Configuración prevista
PanVK · D3D11 / Vulkan · DXVK 2.4.1 · Wine 11
Recomprobar
Detalles
 Ajustes
```

En el mismo instante, `dumpsys activity services` mostraba:

```
ServiceRecord{... com.wowforever.arm64probe/app.gamenative.ForeverClientUpdateForegroundService}
  isForeground=true  types=00000001 (dataSync)  foregroundNoti=... category=progress actions=1
  app=ProcessRecord{... 24930:com.wowforever.arm64probe/u0a330}
```

Es decir: **había una descarga en marcha y la pantalla inicial no mostraba ni
una línea sobre ella.** La muestra histórica anterior retenía48.536.870.644bytes
encoded, no48,5GiB; ni esa muestra ni el tamaño de otro cliente determinan el
porcentaje real de esta ejecución.

### 2. Hallazgos

| # | Sev | Hallazgo | Evidencia | Consecuencia |
|---|---|---|---|---|
| H1 | **Crítico** | La pantalla inicial es **ciega al subsistema de descarga** | `ForeverFunctionalPocActivity.kt` contiene **0 referencias** a `ForeverClientUpdateExecution` (grep verificado). El hub expone `busy/applying/progress` en `MutableStateFlow` (`ForeverClientUpdateForegroundService.kt:28-59`) y el servicio los publica en `:199-212` | El usuario no ve la descarga desde la app. Solo la notificación y un diálogo a 2 toques. Un usuario que abre la app durante 40 h de descarga ve «Preparar WoW» y no tiene forma de saber qué pasa |
| H2 | **Crítico** | La acción principal se ofrece cuando **no puede funcionar** | `Activity:1052-1098` renderiza `Text("Preparar WoW")` sin ninguna comprobación del cliente. `resolveForPrepare()` lanza `StorageException("client_not_installed")` (`ForeverFunctionalClientStorage.kt:898`) | El botón es un callejón sin salida: produce un error tras el toque en vez de no existir. La pantalla se contradice («Falta WowB-ARM64.exe» + «Preparar WoW») |
| H3 | **Crítico** | Los errores técnicos crudos llegan al usuario | El diálogo de error de inicio muestra `failure.message` literal (`Activity:1082`, `1540-1556`) | El usuario lee literalmente `client_not_installed`, `storage_busy`, `guest_reservation_lost`. El diálogo de cliente **sí tiene** un mapper (`ForeverClientManagement.kt:68-79`); el inicio no lo usa |
| H4 | Medio | **Información técnica excesiva** en la pantalla inicial | `ForeverFunctionalGpuCompatibilityCard` se renderiza **siempre** (`Activity:1197-1202`): 5 líneas + 2 botones («Recomprobar», «Detalles»). Su diálogo «Detalles» muestra ~20 filas (matriz, SHA-256 del PanVK, kernel, versiones EGL/Vulkan, args de configuración, marcas de tiempo) | Ocupa espacio con información que no cambia la acción principal. `DisposableEffect(session)` inicia una lectura EGL al entrar en composición o cambiar la sesión, no en cada recomposición |
| H5 | Medio | **Profundidad y rutas de acceso incoherentes** | La descarga está a **2 toques** (Ajustes → Cliente y actualizaciones) siendo la tarea principal. Hay **dos rutas de lanzamiento** con semántica de consentimiento distinta: «Lanzar WoW» exige casilla (`Activity:1143-1193`) mientras «Jugar» en el diálogo de cliente fija `consent = true` y arranca (`Activity:1414-1416`, `Management.kt:268`) | La tarea principal está enterrada; el consentimiento se puede saltar por un camino y no por otro |
| H6 | Medio | La etiqueta de estado listo **suena contradictoria** | `homeReadinessLabel` (`Activity:546-550`) muestra «Cliente y Data presentes · **integridad no verificada**» cuando `readiness.ready == true` | En el único estado bueno, la app dice que algo no está verificado. Genera desconfianza sin acción posible |
| H7 | Bajo | El perfil de servidor es una **opción de usuario** dentro de la APK | `ForeverFunctionalServerProfiles.kt:31` (`profile = ORIGINAL`); el volcado muestra «Servidor: Original» y el grupo de radios «Perfil de servidor» vive en Ajustes → Opciones avanzadas (`Activity:1288-1316`) | La APK lleva las dos opciones y el usuario puede estar en el perfil equivocado. Además «Parche de memoria» y «Captura ARM64» solo existen para `CLASSIC_FOREVER` (`Activity:1356-1375`), así que quedan inaccesibles con el perfil por defecto |
| H8 | Bajo | `clientBytes()` **duplicado** | `ForeverClientUpdateForegroundService.kt:284` y `ForeverClientManagement.kt:284`, copias idénticas | La pantalla inicial necesita el mismo formateo; una tercera copia divergiría. La notificación, el diálogo y el inicio deben decir **lo mismo** («Mirrors the dialog wording so the notification and the dialog never disagree», `ForegroundService.kt:198`) |
| H9 | — | *(positivo)* La ruta del almacenamiento **ya** está tras «Detalles» | `Activity:998` muestra la etiqueta amigable; `:1008-1012` solo revela `Ruta:` tras el toggle | Se conserva este comportamiento; no es un problema |

### 3. Contratos que un rediseño no debe romper en silencio

| Contrato | Dónde | Efecto de cambiarlo |
|---|---|---|
| `activity.count('Text("Preparar WoW")') == 1` y la rama `} else if (prepared == null) {` | `tests/test_functional_launcher_ux.py:45-47` | Falla el test. **Hay que actualizarlo a propósito** dentro de la misma entrega, con el motivo escrito en el propio test |
| `PREPARE_LABEL`, `LAUNCH_LABEL`, `KEYBOARD_LABEL` | `scripts/diagnostics/android-process/run_functional_launcher_ux.py:53-56` | El smoke en dispositivo busca esos textos |
| `READY_LABEL` y `CONSENT_LABEL` de ese mismo script | `run_functional_launcher_ux.py:54-55` | **Ya están desfasados**: el código emite «Entorno preparado. Revisa y pulsa Lanzar WoW.» (`Activity:1072`) y «Se abrirá WoW. El inicio de sesión será manual.» (`Activity:1145`). Deriva preexistente que hay que cerrar |
| `defaultsAreOriginalAndPatchOff` | `overlay/ForeverFunctionalServerProfilesTest.kt:47-52` | Fija el perfil por defecto a `ORIGINAL`; con D2 pasa a ser el perfil de compilación |
| `onKeyboardRequested` / `contentDescription = "Abrir teclado"` | `tests/test_functional_launcher_ux.py` | Botón flotante sobre el juego; no se toca |
| `heightIn(min = 48.dp)`, `windowInsetsPadding`, `imePadding`, tema | `tests/test_functional_launcher_ux.py:38-43` | Accesibilidad y ergonomía; se conservan |

### 4. Diseño objetivo: un estado, una acción

La pantalla inicial se modela como una **máquina de estados con una sola acción
principal**. La descarga/operación activa tiene prioridad; sin ella, la
comprobación y disponibilidad del almacenamiento preceden a las decisiones
*no instalado* / *incompleto o no verificado* / *listo para preparar*.

```
        [crest]
        WoW Forever

  ┌─ ESTADO ─────────────────────────────────────┐
  │  (a) Descargando el cliente…                 │
   │      Progreso real de esta ejecución          │
  │      [Pausar]            Ver descarga         │
  │                                               │
  │  (b) Cliente no instalado                    │
  │      Unidad USB                              │
  │                                               │
  │  (c) Cliente incompleto                      │
  │      Unidad USB · falta WowB-ARM64.exe        │
  │                                               │
  │  (d) Cliente listo                            │
   │      Unidad USB · versión solo si acreditada  │
  └───────────────────────────────────────────────┘

        [ ACCIÓN PRINCIPAL ÚNICA ]
        (a) —              (b) Descargar cliente
        (c) Completa la instalación              (d) Preparar WoW

                  [ Ajustes ]
```

Reglas:

1. **Nunca** una acción que no pueda ejecutarse. (b) y (c) abren directamente el
   gestor de cliente; (d) mantiene el flujo actual preparar → consent → lanzar.
2. La descarga se ve **en el inicio**, no solo en la notificación. En (a) el
   bloque de estado **es** la acción (progreso + Pausar); no hay CTA adicional.
3. El estado *(a)* tiene prioridad sobre *(c)*: durante la descarga el cliente
   está a medias por definición y «Falta WowB-ARM64.exe» es ruido.
4. La tarjeta de GPU sale del inicio; vive en `Ajustes → Compatibilidad gráfica`
   (un toque desde Ajustes), con su diálogo de «Detalles» intacto.
5. Todo error visible pasa por un mapper de texto humano.
6. El perfil de servidor **no se elige**: lo fija la compilación. En el inicio
   se muestra como dato («Servidor: Classic Forever»), sin control.
7. Volver del gestor a Inicio durante una descarga en primer plano no solicita
   pausa implícita: «Pausar» es una acción explícita. Se conserva el bloqueo de
   navegación durante aplicación/recuperación atómica y la cancelación del
   trabajo local de detección/preparación al salir antes de confirmar.

### 5. Decisiones técnicas previstas

- **Fuente del estado de descarga**: `ForeverClientUpdateExecution.busy /
  applying / progress` (`MutableStateFlow`, mismo proceso que la Activity: el
  servicio corre en `com.wowforever.arm64probe`, igual que la Activity), y un
  `detail: MutableStateFlow<ForeverClientUpdateProgress?>` **nuevo** para
  obtener los números de la barra (`transferredBytes / totalTransferBytes`).
  *El texto ya existe; el numerador, no.*
- **Derivación del estado**: función pura en
  `ForeverFunctionalLauncherUx.kt` (el «seam» de UX que ya existe) que combina
  descarga activa + `ClientReadiness` → `HomeState`. **Testeable sin
  dispositivo.**
- **Perfil en tiempo de compilación (D2)**: `apply_functional_poc.py` inyecta
  `buildConfigField("String", "FOREVER_SERVER_PROFILE", …)` en `defaultConfig`
  de `app/build.gradle.kts`, usando el mecanismo auditado que ya edita ese
  fichero (`transform_build`, `apply_functional_poc.py:131-148`), con
  idempotencia. En Kotlin, `ForeverFunctionalServerProfiles` lo lee de
  `BuildConfig` (mismo paquete `app.gamenative`) y `load()` **fuerza** ese
  perfil; se elimina el grupo de radios «Perfil de servidor» de Opciones
  avanzadas. El `buildConfig` ya está activo (`buildFeatures { buildConfig =
  true }`) y `buildConfigField` ya se usa en ese fichero.
- **Persistencia**: el progreso en vivo es solo memoria. Tras la muerte del
  proceso no se infiere una descarga activa ni una pausa acreditada desde el
  hub vacío. **No se implementa un registro de progreso en disco**. El staging
  y los checkpoints existentes se conservan; reanudar sigue requiriendo la
  detección/preparación y el consentimiento actuales, no un reinicio automático.
- **Textos compartidos**: `clientBytes()` y el mapper de errores se extraen a
  un archivo compartido (`overlay/ForeverFunctionalUiText.kt`) para que
  notificación, diálogo de cliente y pantalla inicial formateen igual. Requiere
  añadir el nombre a `OVERLAY_NAMES` en `apply_functional_poc.py`.
- **Ruta única de lanzamiento (D3)**: el «Jugar» del diálogo de cliente deja de
  fijar `consent = true`; prepara y vuelve a la pantalla de consentimiento, igual
  que el CTA de inicio.

### 6. Alcance

**Entra**

- Pantalla inicial: bloque de estado real, CTA único, descarga visible con
  progreso y pausa, tarjeta GPU fuera.
- Visibilidad de la descarga en el inicio (progreso + pausa).
- Texto humano para los errores de inicio.
- Perfil de servidor fijado en tiempo de compilación (sin opción en la APK).
- Una sola ruta de lanzamiento, con consentimiento.
- Duplicado `clientBytes()` eliminado.
- Tests de host actualizados a propósito + test nuevo de la derivación de estado.

**No entra (fuera de alcance)**

- Tocar el motor de descarga, hashes, fsync, checkpoint, journal, permisos,
  runtime, parche de memoria o ciclo de vida del servicio en primer plano. Solo
  se publica detalle UI adicional; pausa/reinstalación/reanudación únicamente
  bajo la autorización operativa posterior registrada arriba.
- Rediseñar los diálogos de cuenta, almacenamiento o resolución (ya están
  jerarquizados y son secundarios).
- Persistir el progreso de descarga en disco.
- Cambiar los textos de los diálogos de cliente que ya funcionan.

### 7. Riesgos

| Riesgo | Mitigación |
|---|---|
| Ruptura de la descarga en curso | Primero compilar/auditar; pausa cooperativa autorizada y cero workers antes de instalar sin borrar datos; checkpoint/recibo byte-idénticos antes/después; reanudar la misma build/destino y comprobar progreso nuevo |
| Los tests de host fijan literals de la UI | Se actualizan **en la misma entrega** y con el motivo escrito en el propio test |
| Deriva entre notificación, diálogo e inicio al formatear | Un único formateador compartido; los tres caminos lo usan |
| Progreso en memoria perdido al morir el proceso | No mostrar progreso obsoleto ni afirmar pausa recuperable sin evidencia; usar la inspección real y el flujo existente para continuar |
| D2 cambia el perfil efectivo de las APK ya instaladas | Es el objetivo del proyecto (Classic Forever). Las preferencias de perfil guardadas dejan de mandar; se documenta en la bitácora |
| `transform_build` hoy retorna pronto si la versión ya es la objetivo | Se reestructura para que la inyección del campo de perfil sea **independiente** de la versión y **idempotente** |

## Implementation / Action Items

- [x] 1. Tests: actualizar `tests/test_functional_launcher_ux.py` a la
      nueva estructura (con el motivo escrito en el test) y añadir el test de
      la derivación de estado.
- [x] 2. Extraer el texto compartido: `overlay/ForeverFunctionalUiText.kt` con
      `clientBytes()` y `failureMessage()` (+ `client_not_installed` y familia);
      borrar las dos copias; registrar el archivo en `OVERLAY_NAMES`.
- [x] 3. Derivar `HomeState` en `ForeverFunctionalLauncherUx.kt` (función pura,
      sin Compose) y su test JVM en `overlay/ForeverFunctionalLauncherUxTest.kt`
      (registrar en `TEST_OVERLAY_NAMES`).
- [x] 4. Añadir `detail` al hub `ForeverClientUpdateExecution` y publicarlo en
      `publish()` del servicio.
- [x] 5. Inyectar `FOREVER_SERVER_PROFILE` en `build.gradle.kts` desde
      `apply_functional_poc.py` (idempotente, independiente de la versión) y
      forzar ese perfil en `ForeverFunctionalServerProfiles.load()`; eliminar el
      grupo de radios «Perfil de servidor».
- [x] 6. Rediseñar la pantalla inicial en `ForeverFunctionalPocActivity.kt`:
      bloque de estado, CTA único, descarga visible con progreso y pausa,
      tarjeta GPU movida a Ajustes, error con texto humano.
- [x] 7. Unificar la ruta de lanzamiento: el «Jugar» del diálogo de cliente
      vuelve a la pantalla de consentimiento.
- [x] 8. Corregir los labels desfasados de `run_functional_launcher_ux.py`
      (`READY_LABEL`, `CONSENT_LABEL`).
- [x] 9. Verificación: tests de host (`uv run pytest tests/ -q` y los focales),
      `py_compile` de los scripts tocados, reconstrucción canónica con recibo,
      tests JVM y auditoría de APK, y **verificación física en el dispositivo**
      (descarga visible en el inicio, CTA correcto, tarjeta GPU fuera).
- [x] 10. Documentación en `docs/launcher-app-status.md` y
      `docs/downloader-handoff.md` actualizada; visto bueno visual recibido.
      Reanudación C115 probada con nuevos commits; plan cerrado en `done/`.

## Open Issues

D1–D4 resueltos. Compilación/auditoría, actualización conservando datos,
reanudación y revisión física C113 completadas. El usuario aprueba la línea de
iconos originales («me gustan») y autoriza actualizar/reanudar C114, ya entregada.
La valoración C114 detectó desalineación y espaciado irregular. El usuario autoriza
«Sí, también otras iteraciones» y pide «itera hasta
conseguir una interfaz con la que estemos contentos». Permite las pausas
controladas necesarias dentro de esta revisión visual, preservando progreso y
reanudando al terminar; no amplía el alcance al motor ni a otro cliente/destino.
Tras ver la captura corregida C115, el usuario confirma: **«si, mucho mejor»**.
Aceptación estética recibida y reanudación final C115 probada. No quedan bloqueos
de esta revisión. Siguen fuera del alcance la optimización del descargador,
instalación/integridad completas y arranque/login. La suite global no está verde;
sus resultados y limitaciones se conservan abajo, sin ocultarlos al cerrar.

### Corrección visual solicitada tras C114 (C115)

El usuario señala: «los iconos de ajustes están descentrados [...] cada botón
tiene una anchura distinta y una separación [...] diferente». La revisión C114
fue demasiado permisiva: centraba grupos icono/texto de anchuras distintas y no
contrastó la geometría. El código confirma márgenes superiores diferentes,
botones de altura variable y ayuda intercalada entre las acciones.
Esta corrección está dentro de la iteración visual ya aprobada; no cambia rutas,
etiquetas, perfil ni lógica de descarga/lanzamiento.

- [x] Usar una única geometría para las cinco acciones de Ajustes: icono20dp en
  columna fija, texto a la izquierda con espacio12dp, mismo ancho y altura mínima
  64dp con padding16dp horizontal/12dp vertical. Texto puede crecer por accesibilidad.
- [x] Agrupar acciones con separación12dp, sin padding particular por botón ni
  ayuda intercalada. Ayuda normal de resolución ya existe en su diálogo; solo
  conservar el aviso de bloqueo, explícito y fuera del grupo cuando corresponda.
- [x] Test focal de la estructura común, compilación y auditoría C115/
  `0.2.91-settings-grid`. Sin tocar motor, almacenamiento ni inventar estados.
- [x] Permiso de pausa recibido para esta revisión e iteraciones. Capturas reales y
  medidas XML de los cinco botones: ancho, altura y separaciones iguales en el
  dispositivo observado; iconos/textos alineados. Progreso conservado y misma
  descarga reanudada; valoración del usuario recibida.

C115 instalada: componente privado `ForeverSettingsAction`
compartido por cinco destinos, mismos callbacks/etiquetas/guardas. No se modifican
iconos ni motor; solo geometría y se retira la ayuda redundante intercalada.
Seis tests focales pasan y scripts tocados compilan. Reconstrucción+APK:
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,2187fuentes/178payloads,483.989.085bytes,
SHA256`2a9eddedc57d57bbefe75b98409a3c6f5539fa5b9744812460c46903b3b2317c`,
en`/home/adrian/wowforever-artifacts/arm64-prototype/client-manager-code115-settings-grid/`.
Instalación y medidas físicas confirmadas; la captura C114 no se toma como
validación de C115. Pausa cooperativa y `install -r` preservan byte a byte los tres
ficheros críticos (719.879objetos/57.334.811.304bytes encoded). XML: cinco botones
de748×176px y cuatro huecos de33px; a440dpi son272×64dp y12dp. Capturas reales
`c115-settings.png` y `c115-home-reviewed.png` inspeccionadas por el padre: iconos y
texto en columnas, cinco etiquetas completas a una línea, sin recortes, acciones
secundarias distinguibles. Reanudación probada con nuevos commits C115.
Captura presentada y aceptada expresamente por el usuario:
«si, mucho mejor». No se requieren más cambios visuales para esta revisión.

Reanudación C115:70450 ARM64/enUS/USB6AD6-EF0E,31,6GiB descarga/33,5GiB adicionales
(sin aumento), una confirmación. Prueba22:01:12UTC del19oct:720.047objetos/
57.348.415.110bytes encoded,168objetos nuevos,foreground/PID20462/16workers,
cola parcial0 y objeto cifrado retenido. `Ver descarga`→gestor→`Volver` mantiene
foreground; captura final de Inicio inspeccionada, fase y acciones válidas,
sin recortes. App dejada descargando, no se ejecutó juego. Informes externos:
`c115-cost-screen.json`, `c115-resume-confirmation.json`,
`c115-resumed-first-new-commit.json`, `c115-home-returned-without-pausing.png`.
Documentación sincronizada: estado de producto, handoff, índice, README del
prototipo y navegación de AGENTS.md. No commits/pushes ni artefactos grandes en repo.

### Continuación aprobada: iconos originales (C114)

- [x] Añadir seis vectores locales reutilizables, sin dependencias ni imágenes
  descargadas, fieles a la lámina aprobada: emblema, descarga, almacenamiento,
  cuenta, pantalla y gráficos. Tamaño pequeño y etiquetas conservadas; iconos
  decorativos sin anuncio accesible duplicado. Mantener fondo, anillos y líneas
  del emblema actual; no añadir tarjetas, filas ni información.
- [x] Integrar en Inicio y botones existentes de Ajustes. Registrar únicamente el
  nuevo helper en staging/receta/allowlist exacta. C114/`0.2.90-original-icons`;
  baseline original, payloads y lógica de descarga/lanzamiento intactos.
- [x] Pruebas focales de lo tocado, compilación y auditoría canónicas. No repetir
  la suite completa ni los checks de lógica ya aprobados si no cambian.
- [x] Pedir nueva autorización antes de pausar/instalar; hasta entonces C113 y su
  descarga permanecen intactas. Después de actualizar, revisar capturas reales
  (incluido ajuste del texto largo de resolución) y conservar progreso.

Evidencia C114: compilación `assembleModernDebug` y auditoría
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`,2187fuentes/178payloads. APK483.991.209bytes,
SHA256`f474d4968977e51bca5473fe0ed488120ca416a360a23e00e04909399248979b`,
artefactos en
`/home/adrian/wowforever-artifacts/arm64-prototype/client-manager-code114-original-icons/`.
Verificación focal del constructor:57pasados/1fallo conocido de Cuenta; los5
fixtures legacy de overlay fallan igual con el recipe anterior. Se retiraron
después tres tests redundantes de geometría/literales, sustituyéndolos por una
aserción de copia byte-idéntica del helper en el test existente de staging;
ese test modificado pasa. Scripts Python compilan. La revisión del diff confirma
que solo se añaden iconos y registros/versiones, sin tocar lógica del cliente.
**C114 instalada y revisada físicamente; descarga reanudada y con commits nuevos.**

El usuario autoriza expresamente C114: «Sí, instala y reanuda». La primera
comprobación operativa encuentra ADB sin dispositivos; reconectar al endpoint
conocido `192.168.1.133:34313` devuelve `Connection refused` y mDNS no descubre
otro endpoint. Bloqueo resuelto tras indicar el usuario nueva IP130: mDNS descubre
`192.168.1.130:35279`. C113 ya presentaba «No se pudo completar la descarga»,
sin FGS/0workers; no se determinó la causa ni se atribuye al cambio de red.
No hizo falta pausar. Se preservaron708.312objetos/56.408.614.061bytes encoded;
checkpoint/recibo/preferencias byte-idénticos al instalar C114.

Revisadas imágenes reales `c114-home-reviewed.png`, `c114-settings.png` y
`c114-home-returned-without-pausing.png`: emblema y seis recursos coherentes con
la propuesta; etiquetas conservadas, resolución a dos líneas sin recortes,
acciones de descarga en una línea y barras del sistema respetadas. También se
recorrieron Almacenamiento/Resolución/GPU sin cambios de configuración.
Consentimiento70450/USB32,5GiB/34,4GiB, sin ampliar los costes C113. Se confirmó
una vez. El primer observador se anticipó al arranque FGS: aviso de cancelación
del scope del diálogo al volver durante el handoff, pero el servicio arrancó y
continuó. Se ajustó solo la espera del observador y se repitió la observación;
**no se reinició ni volvió a confirmar la descarga ni se cambió lógica C114**.
Prueba21:13:48UTC:708.404objetos/56.415.189.328bytes encoded (92objetos nuevos),
FGS/PID20461/16workers, cola parcial0, objeto cifrado retenido.
`c114-installed-preserved.json` y `c114-resumed-first-new-commit.json` son evidencia
histórica C114. Ver descarga→Volver conservó foreground. La aprobación estética
posterior corresponde a la corrección C115, no a esta captura C114. No son pruebas
de juego/login ni de instalación/integridad completas. Optimización del descargador
sigue aparcada.

### Avance y evidencia

- Implementación C113 (`0.2.89-contextual-home`) revisada independientemente:
  perfil/provenance/servicio/gestor sin defectos nuevos; revisión de Inicio detectó
  consentimiento anterior tras fallo de una segunda preparación. Corregido
  invalidando `prepared` y `consent` en ese fallo. La validación del coordinador
  detectó además que `prepared` es el handle de liberación del guest activo:
  el refresh de Inicio no puede invalidarlo durante preparación/lanzamiento/juego
  (guardas al entrar y al retornar del IO). Último error crudo de prelaunch
  pasado al mapper humano.
- Tests focales:59pasados y6fallos preexistentes reproducidos con copias anteriores
  (fixtures legacy de manifiesto/toolchain y literal desfasado de Cuenta). No se
  alteran piezas ajenas para ocultarlos. Suite completa:1305pasados,42fallos,
  4omitidos. Uno era un pin anterior «no download en Activity», incompatible con
  H1; actualizado deliberadamente y su test pasa. Quedan41fallos sin resolver en
  fixtures/literals y subsistemas como supervisores nativos, empaquetado y
  laboratorio x64. No se han contrastado todos individualmente contra baseline;
  no se afirma que los41 sean preexistentes ni suite global verde. No se amplía
  esta tarea a corregirlos. Dos tests de las guardas finales de Inicio pasan.
- Primera ejecución JVM:21/22pasados. El fallo era una expectativa del test
  nuevo que ponía almacenamiento desconocido por delante de «Comprobando»;
  corregida conforme al orden aprobado (descarga > comprobación > disponibilidad).
   Los dos casos modificados pasan en la siguiente ejecución (2/2,0errores).
   En conjunto22casos pertinentes aprobados; no se repiten perfil/servicio.
- La receta operativa C112→C113 es `reconstruct_functional_source.py`, no el
  `apply_functional_poc.py` histórico. Se valida reconstrucción+recibo+auditoría
  reales en vez de declarar un dry-run legacy como prueba de la APK entregada.
  Se mantienen intactas las protecciones de baseline/provenance.
- Artefactos/capturas y revisión visual:
  `/home/adrian/wowforever-artifacts/work/launcher-ux-visual-20261019/`.
- Fuente final reconstruida y APK compilada/auditada:2186fuentes/178payloads,
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, SHA256
  `a0d0609f8e523de03a29355dc24df7e6608d0b3ad2c1a63a8d396f508898aa9c`.
  `py_compile` de los cuatro scripts tocados pasa. Activity/GPU empaquetados
  byte-idénticos al repo, sin debilitar el baseline ni la allowlist.
- Revisión física iterativa: Inicio sin cliente ofrece Descargar cliente, sin
  Preparar/Lanzar ni GPU. Capturas de Ajustes/Almacenamiento/Resolución/GPU y
  detalles; se corrigieron título GPU duplicado y ayuda de resolución mal situada,
  se reconstruyó/auditó/instaló la segunda candidata y se repitieron capturas.
- Pausa cooperativa con0fetch workers;689.223objetos/54.792.793.222bytes encoded,
  checkpoint/recibo/preferencias byte-idénticos en ambas instalaciones C113.
  Mismo70450 ARM64/enUS/USB6AD6-EF0E; consentimiento real34,0GiB/35,9GiB,
  dentro del límite autorizado. Confirmado una sola vez tras planificar.
- Reanudación acreditada19:56:03UTC:689.244objetos/54.793.343.700bytes encoded,
  FGS/16workers,cola parcial0,objeto cifrado retenido. Inicio enseña progreso y
  Pausar/Ver descarga, nunca Preparar/Lanzar. Abrir gestor y Volver conserva FGS.
  Durante recuperación atómica se observó Aplicando y pausa bloqueada, como debía.
- La descarga queda activa. No se ejecutó el juego ni se fingió cliente listo;
  ramas de cliente listo/consentimiento verificadas por código/tests, no físicamente.
  No se acredita instalación completa, integridad final, login ni gameplay.
- El usuario sugiere recursos gráficos si aportan estilo. Se creó una propuesta
  original de seis iconos vectoriales en `icon-style-proposal.svg` y su lámina PNG,
  dentro del directorio externo de revisión. La lámina inicial no modificó la APK
  ni requirió pausar. Aprobada e incorporada después en C114, sin añadir pantallas
  ni tarjetas; véase la continuación y su evidencia más arriba.
