# Autologin sin imágenes — investigación del 2026-10-22

## Resultado y límite

El usuario aprobó la corrección y después eligió explícitamente **Sin imágenes**.
La inspección no encontró una señal directa de formulario/campo en las APIs de
XServer examinadas. No se habilita el envío ni se declara corregido el autologin.
No hubo ADB, capturas, ejecución del juego, instalación ni uso de la cuenta guardada.

Árbol canónico consultado, fuera del repositorio:
`/home/adrian/wowforever-artifacts/arm64-prototype/client134-c127-enter-gate-20261022-isolated/source/GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`.

- `app/src/main/java/com/winlator/xserver/WindowManager.java:125–145` consulta y
  asigna foco a una ventana X, no a un campo interno del login de WoW.
- Los listeners de ventanas (`:23–46, 314–329`) exponen mapeo, geometría y
  propiedades, no el estado del formulario ni un modal dibujado dentro del juego.
- `Keyboard.java:92–132` ofrece eventos de tecla; la ruta virtual incluye un
  carácter Unicode por evento, pero no se ha validado un transporte completo y
  fiel de credenciales arbitrarias. No confundir inyección de teclas con detección.
- `Pointer.java:60–70` permite posición/botones, sin identificar controles.
- La ruta `PixelCopy` examinada en `ImmersiveXrActivity.kt:1292–1293` copia una
  superficie; no aporta semántica y no se utilizó.

Esto no prueba que toda integración sin imágenes sea imposible. Sí impide
construirla con garantías solo a partir de estos hooks. Hay que evaluar y aprobar
otra fuente de estado del cliente/Wine antes de añadir un puente, un lector de
memoria o cambios del cliente/runtime. No se implementó ninguna de esas opciones.

## Delta aceptado y verificación

En `ForeverFunctionalAutologinAttempt.kt` se elimina solo el veto al opt-in
independiente del escritor. Se conservan el veto de captura, la evidencia completa,
la revalidación, la cancelación y el permiso de envío único. El cambio no habilita
autologin ni el escritor. La revisión independiente no encontró defectos en este
delta. La revisión nativa no encontró persistencia de credenciales por el escritor
con captura OFF; no equivale a una garantía global de privacidad.

Verificación en copia aislada de la fuente canónica:
`/home/adrian/wowforever-artifacts/arm64-prototype/autologin-policy-verification-20261022/`.

```bash
# Desde source/GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa, JDK 17
bash ./gradlew :app:testModernDebugUnitTest \
  --tests app.gamenative.ForeverFunctionalAutologinAttemptTest \
  --offline --no-daemon --no-configuration-cache --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx4096m -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=2'

# Desde el repositorio
/home/adrian/.local/bin/uv run pytest tests/test_reconstruct_functional_source.py \
  -k candidate_transform_uses_only_known_functional_overlay_sources -q
```

Resultados: **11 JVM aprobadas**, XML en
`app/build/test-results/testModernDebugUnitTest/TEST-app.gamenative.ForeverFunctionalAutologinAttemptTest.xml`;
**1 host aprobada, 23 excluidas**. El wrapper sin permiso de ejecución se ejecutó
mediante `bash`; no se alteró el árbol canónico original. El padre inspeccionó XML
y diff; el revisor no repitió pruebas. No es compilación/auditoría de APK ni prueba
de escritura real: la evidencia de campo de los tests es sintética.

## Riesgos pendientes antes de habilitar

Captura de memoria OFF no desactiva los demás sinks. `ForeverRunStore.kt:1188–1240`
exporta diagnósticos, streams y logs del juego sin el saneamiento aplicado por el
informe remoto. El runner host `scripts/diagnostics/usb_forever_trial.py` puede
persistir pantalla y salida. No se observó al escritor emitir credenciales, pero
esas rutas deben protegerse antes de un ensayo con secretos y de habilitar input.
No se modificaron en esta fase.

El [plan activo](../.agents/planning/launcher-autologin-correction-plan.md) permanece
abierto: falta una fuente de estado directa, integración de input, revisión completa
y aceptación en dispositivo. Se preservan todos los cambios concurrentes ajenos.

## Investigación comparativa autorizada

El usuario autoriza ampliar la investigación a integración directa WoW/Wine y
pregunta cómo resuelven el autologin otras soluciones. No autoriza por ello cambios
de cliente/runtime, inyección ni acceso a la cuenta guardada.

El objetivo de comparación es el cliente **1.60.1.70450 Windows ARM64**, ejecutado
en Android14 mediante Proton11.0-90624 ARM64EC/DXVK, según el
[corte operativo](launcher-app-status.md), sección «Ruta validada y ubicaciones».
El contrato histórico de70009 x86_64 no demuestra compatibilidad con este cliente.

Se evalúan por separado:

1. **Estado:** qué identifica realmente el formulario, campo activo y modal; una
   ventana activa o una espera no lo acreditan.
2. **Entrada:** cómo se introducen los caracteres; poder escribir no confirma el
   destinatario ni garantiza que se representen todos los símbolos.
3. **Secretos:** dónde se guardan y por qué canales pasan. Que otra herramienta use
   texto plano, clipboard o argumentos no lo convierte en admisible aquí.
4. **Portabilidad:** versión de WoW, arquitectura, Wine y necesidad de modificar el
   cliente. Un ejemplo antiguo no acredita soporte en70450 ARM64.

Esta investigación no cambia US-006: NFR-004 sigue prohibiendo cambios del
ejecutable/runtime y exige revisión de seguridad. Una opción que necesite otra
autoridad debe presentarse para decisión, no incorporarse como un simple detector.

### Referencia propia: automatización host de70450

`scripts/diagnostics/android-process/functional_login.py:463–554` ya separa envío
de prueba de foco. `verify_70450_blink` exige entre2 y4 imágenes del formulario
estable, parpadeo del cursor en el campo esperado y ausencia de ese parpadeo en el
otro; `Tracker70450` refresca esa prueba por fase y pulsación. Es **reconocimiento
visual**, no una consulta al estado interno del cliente. La geometría fija se
rechaza si no coincide. El adaptador UHID host tampoco añade semántica por sí mismo.

Por tanto, el ensayo host referido en el estado operativo no demuestra que ya haya
un detector sin imágenes reutilizable en la APK. Se conserva como evidencia
histórica, pero no se propone trasladarlo bajo la decisión actual.

### Soluciones públicas: mecanismo observado, no validación de compatibilidad

Se consultó código público fijado a revisión; no se ejecutó ni instaló ninguna
herramienta. La revisión directa confirmó las rutas de entrada y secretos.

| Solución / revisión | Qué hace | Límite para esta APK |
|---|---|---|
| [EinBaum/AutoLogin, `71889a3`](https://github.com/EinBaum/AutoLogin/tree/71889a396999fdcef882952fd2386f3fbb5c3d6e) | El README limita la prueba a1.12.1. `AutoLogin.c`: encuentra una ventana visible del PID, espera `delay` y usa `SendInput` para cuenta → Tab → contraseña → Enter. | No consulta campo/modal; incluso el parámetro `hWnd` no dirige `SendInput` a ese control. Cuenta y contraseña llegan por argumentos. No cumple el gate ni el transporte aprobados. |
| [EinBaum/AutoLoginGUI, `e043115`](https://github.com/EinBaum/AutoLoginGUI/tree/e0431155b8d26406c8a6f02f2fe49512c4120f29) | `AutoLogin.c`: ventana visible, espera1s, `SetForegroundWindow`, secuencia de teclas; Enter es opcional. | El foco es de ventana, no del campo. `SaveSettings` escribe usuario/contraseña en `AutoLogin.ini` sin cifrado y conserva backup. No se acredita versión WoW objetivo. |
| [Zaifat/wow-autologin, `17a63d9`](https://github.com/Zaifat/wow-autologin/tree/17a63d9e29ddc5acb164d6812e2a27056129abe5) | Documenta3.3.5a/build12340. Parchea el ejecutable para cargar una DLL; el callback Glue postload llama a `NetClient::Login`. No necesita reconocer píxeles para esa llamada. | Cambia el cliente y usa funciones específicas de otro build. El almacenamiento cifrado ofrecido por el launcher no elimina el transporte por argumentos o `autologin.json`; `CommandLine.cpp` lee ese archivo y lo elimina después. Además registra el login en `autologin.log`. No cumple los límites de secretos ni acredita ARM64/70450. |

Fuentes de código verificadas:

- [AutoLogin.c](https://github.com/EinBaum/AutoLogin/blob/71889a396999fdcef882952fd2386f3fbb5c3d6e/AutoLogin.c): `AL_SendInfo`, `AL_Window_Callback`, `main`.
- [AutoLoginGUI/AutoLogin.c](https://github.com/EinBaum/AutoLoginGUI/blob/e0431155b8d26406c8a6f02f2fe49512c4120f29/AutoLogin.c): `SendInfo`, `StartGame_Process`, `SaveSettings`.
- [CommandLine.cpp](https://github.com/Zaifat/wow-autologin/blob/17a63d9e29ddc5acb164d6812e2a27056129abe5/awesome_wotlk_src/src/AwesomeWotlkLib/CommandLine.cpp): `loadJsonParams`, `getParam`, `gluexml_postload`.

**Conclusión limitada a estos ejemplos:** no usar imágenes es viable como técnica,
pero no significa que se compruebe el estado del formulario. Los dos ejemplos de
teclado asumen el estado; el ejemplo interno obtiene otra autoridad sobre otro
cliente. Ninguno demuestra una solución directamente trasladable a70450 ARM64.
Se puede reutilizar el patrón de eventos de teclado desde memoria, no sus gates
ni su tratamiento de secretos. Aceptar una secuencia ciega requeriría cambiar
explícitamente AC-03/FR-003, y no se propone hacerlo como arreglo silencioso.

### Fuente de UI etiquetada con el build objetivo

La investigación localizó una referencia mucho más próxima que los ejemplos de
Vanilla/WotLK: [Gethe/wow-ui-source, revisión `172a387`](https://github.com/Gethe/wow-ui-source/commit/172a387e3ab85b26e6640d3058d5ec1e6ccd4c63).
Su [version.txt](https://github.com/Gethe/wow-ui-source/blob/172a387e3ab85b26e6640d3058d5ec1e6ccd4c63/version.txt)
declara **1.60.1.70450**, confirmado por lectura directa del contenido público.

En esa revisión, [Mainline/AccountLogin.lua](https://github.com/Gethe/wow-ui-source/blob/172a387e3ab85b26e6640d3058d5ec1e6ccd4c63/Interface/AddOns/Blizzard_GlueXML/Mainline/AccountLogin.lua):

- Líneas31–33,69–80: registra `LOGIN_STATE_CHANGED` y consulta `C_Login.GetState()`.
- Líneas203–223: `AccountLogin_Login` llama a `C_Login.Login` con el usuario y el
  **objeto PasswordEditBox**, no una contraseña pasada como argumento de texto
  externo; después vacía el campo. No confundirlo con `DefaultServerLogin` antiguo.
- Líneas248–259: `C_Login.IsLoginReady()` condiciona Enter y existe una función que
  da foco a `PasswordEditBox`. Readiness de conexión no prueba por sí sola foco de
  campo ni ausencia de todos los modales.
- Líneas660–675: el autologin integrado usa `Kiosk.GetKioskLoginInfo()` o
  `C_Login.LauncherLogin()`. No se ha acreditado que Forever suministre ninguna de
  esas rutas; no se propone transportar credenciales como parámetros Kiosk.

El [TOC de Blizzard_GlueXML](https://github.com/Gethe/wow-ui-source/blob/172a387e3ab85b26e6640d3058d5ec1e6ccd4c63/Interface/AddOns/Blizzard_GlueXML/Blizzard_GlueXML.toc)
declara `AllowLoad: Glue` y selecciona archivos mediante `[Family]`/`[Game]`.
La etiqueta exacta de build no demuestra equivalencia con los archivos cargados por
el binario ARM64 instalado ni que se acepten addons de terceros antes de autenticar.
Estas son APIs usadas por la UI de Blizzard, **no una API externa acreditada para
el APK ni para addons normales**. Un addon que solo carga después del login no
resuelve el formulario previo. No se ha probado aceptación/rechazo de un addon
externo en Glue, verificación de firma ni acceso a esas funciones desde él.

La resolución esperada del TOC es familia Mainline/juego Camelot, pero el cargador
del binario no se ha inspeccionado. Que `DefaultServerLogin` no aparezca en el
archivo revisado no demuestra que esté ausente del cliente.
La documentación generada del mismo árbol enumera
[`HasFocus()`](https://github.com/Gethe/wow-ui-source/blob/172a387e3ab85b26e6640d3058d5ec1e6ccd4c63/Interface/AddOns/Blizzard_APIDocumentationGenerated/SimpleEditBoxAPIDocumentation.lua)
con `ChecksForbiddenAspects = QueryFocus` e
[`IsShown()`/`IsVisible()`](https://github.com/Gethe/wow-ui-source/blob/172a387e3ab85b26e6640d3058d5ec1e6ccd4c63/Interface/AddOns/Blizzard_APIDocumentationGenerated/SimpleFrameAPIDocumentation.lua).
Son candidatas a estado de campo/modal sin leer valores, no prueba de autorización
para ejecutarlas desde código externo durante Glue.

No se ha invocado ninguna API, instalado un addon ni inyectado código. La fuente
sirve para orientar una prueba de viabilidad futura, no para habilitar autologin.

### Accesibilidad Wine/Windows: opción de menor intrusión por comprobar

[`GetGUIThreadInfo`](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getguithreadinfo)
y [`EnumChildWindows`](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-enumchildwindows)
ofrecen foco de HWND y ventanas hijas. No convierten los controles dibujados por
WoW dentro de la ventana en controles Windows identificables. MSAA/UIA podría
ofrecer más semántica **si el cliente la expone** mediante un provider; véanse
[`WM_GETOBJECT`](https://learn.microsoft.com/en-us/windows/win32/winauto/wm-getobject)
y [proxies IAccessible](https://learn.microsoft.com/en-us/windows/win32/winauto/iaccessible-proxies).

La fuente Wine consultada tiene un puente MSAA→UIA:
[`uia_provider.c`, revisión `e5fa703`](https://github.com/The412Banner/proton-wine/blob/e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2/dlls/uiautomationcore/uia_provider.c#L674-L716)
traduce rol a tipo de control, `STATE_SYSTEM_FOCUSED` a foco e
`STATE_SYSTEM_PROTECTED` a `IsPassword`. La revisión directa confirmó esas rutas.
Sus [exports](https://github.com/The412Banner/proton-wine/blob/e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2/dlls/uiautomationcore/uiautomationcore.spec)
incluyen operaciones implementadas y otras stub; por ejemplo `UiaNodeFromFocus`
está exportada, mientras `UiaSetFocus` es stub en esa revisión.
**No se ha acreditado que esta revisión sea el binario Proton instalado**, ni que
WoW publique un árbol útil. No confundir implementación genérica con compatibilidad
observada del login.

### Recomendación y decisión pendiente

Antes de considerar modificaciones Glue o lectores de memoria específicos de build,
probar la opción menos intrusiva: una **sonda guest de accesibilidad de solo
lectura**, separada del autologin y todavía no autorizada para implementación ni
ejecución. No modifica `WowB.exe` ni DLLs del runtime, no inyecta código, no envía
teclas y no usa la cuenta guardada.

La sonda propuesta consultaría únicamente identidad de proceso/ventana, existencia
del árbol, rol/tipo, foco, `IsPassword` y geometría, con salida acotada de
metadatos. No solicitaría `Name`, `Value`, texto, clipboard, capturas, argumentos
del juego ni dumps de memoria. Requiere acordar ensayo con formulario sin introducir
credenciales y conservar apagadas las capturas/diagnósticos indiscriminados.

El resultado útil debe distinguir campos y sus cambios de foco, y aportar evidencia
de los modales que invalidan el formulario; un árbol genérico de ventana no basta.
Un fallo o árbol vacío solo significa **sin señal utilizable en ese ensayo**, no
demuestra ausencia universal de provider. Si no da esa evidencia, no conectar
credenciales: presentar la limitación antes de optar por otro puente o cambiar
el contrato a entrada asistida/ciega.

Un canario Glue sin credenciales podría investigar por separado la carga previa a
autenticación, pero añade archivos de addon y no prueba acceso seguro a las APIs;
no se propone como primer ensayo ni se ha instalado. La investigación comparativa
no ha corregido aún la introducción de credenciales de la APK.

## Sonda aprobada e implementada — ensayo pendiente

El usuario aprueba «vale. hagamoslo» tras proponer implementar la sonda y después
coordinar el ensayo móvil. Se añade
[`wine-accessibility/`](../scripts/diagnostics/wine-accessibility/README.md): helper
Windows ARM64 con **MSAA como primera vía**, sin UIA directa. La reducción de alcance
evita un segundo backend antes de observar si el primero ofrece datos útiles;
un resultado negativo no descarta UIA ni habilita un fallback de inyección.

Recibe solo PID Windows explícito; mantiene handle del proceso, filtra HWND/nodos
por pertenencia a ese PID vivo, no lee nombres/valores/texto y emite roles/flags/
geometría con claves fijas. Máximo16 ventanas,128 nodos, profundidad8 y watchdog10s
que termina solo la sonda ante bloqueo. Cada muestra final declara
`readiness_proven: false`. No es un detector de modales, ni un snapshot atómico.
Véase el README para interpretación de errores, límites y protocolo de ensayo.

Verificación host realizada:

```bash
bash scripts/diagnostics/wine-accessibility/build.sh \
  /home/adrian/wowforever-artifacts/arm64-prototype/controls/accessibility-probe-20261022
bash -n scripts/diagnostics/wine-accessibility/build.sh
```

Compilación con llvm-mingw existente y `-Wall -Wextra -Werror` correcta; inspección
de PE confirma `IMAGE_FILE_MACHINE_ARM64 (0xAA64)`. Recibo de hashes e imports en
ese directorio externo. Se inspeccionaron imports y fuente: consultas MSAA/USER32,
COM y bibliotecas de sistema/CRT; `TerminateProcess` se usa solo sobre la propia
sonda. Las llamadas a métodos COM no se demuestran mediante una lista de imports:
se revisaron además en la fuente. No se ejecutó el PE ni se probó un provider real.

La delegación de implementación y la revisión independiente fallaron antes de
ejecutarse por `Variant unavailable`. La implementación y comprobación de fuente
se realizaron directamente, **sin sustituir esa revisión por una aprobación
inventada**. La revisión independiente queda pendiente antes de probar contra WoW.
No hubo ADB, instalación, modificación de la APK/runtime, acceso a la cuenta ni
entrada de datos. El autologin sigue sin habilitarse.
