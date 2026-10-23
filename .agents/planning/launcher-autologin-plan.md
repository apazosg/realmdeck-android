---
id: launcher-autologin-2026-10-16
state: in-progress
date: 2026-10-16
---

## Summary

Ejecución de UX5/paso5 del plan de producto ya aprobado y reafirmado por el
usuario: «sigue con el autologin. podemos probarlo con las credenciales que
usábamos en pruebas». No reabre diagnóstico de autenticación ni autoriza bypass,
cambios de servidor, runtime o cuentas reales. Code69 es la base instalada.

## Content

### Alcance y criterios ya aprobados

- Autologin opcional desde Ajustes del APK, desactivado inicialmente.
- Credenciales en memoria de sesión; «Recordar» explícito con cifrado Android
  Keystore y archivo en `noBackupFilesDir`. Nunca texto plano persistido,
  `rememberSaveable`, WTF, argumentos, intents, clipboard, screenshots o logs.
- Borrado accesible; desactivar/olvidar no puede conservar persistencia oculta.
  Clave inválida, corrupción o restore incompleto requieren reentrada manual,
  no fallback inseguro. Minimizar vida y copias de secretos sin prometer borrado
  absoluto de Strings inevitables en Android/Compose/JVM.
- No basta una ventana mapeada: sesión propia, ventana/clase/nombre, foco y
  confirmación positiva del formulario. Hasta90s de readiness, como máximo un
  envío por arranque; cancelar ante interacción manual, foco/sesión distintos,
  timeout o error. Sin reintento automático ni declarar auth por salir del form.
- Entrada por APIs propias XServer, nunca root/ADB como requisito del APK.
- Primero formularios/datos sintéticos; revisión de seguridad antes de habilitar
  la función en una entrega. Mantener entrada manual si el gate no es fiable.

### Integración y guardas

1. Almacén de credenciales separado de detección visual y transporte de entrada.
   API de adquisición devuelve copia temporal con liberación explícita; no data
   class/toString de usuario/contraseña, ni excepciones que incluyan valores.
2. Ajustes separados de diagnósticos, campos ocultos/protegidos y sin sugerencias
   o autofill no solicitado; pantalla protegida mientras se editan credenciales.
   El usuario puede usar solo memoria o persistir cifrado por opt-in.
3. Controlador por arranque, no por recreación de Compose, con token/sesión
   estable y estado terminal después del único envío/cancelación. Diagnóstico
   limitado a códigos/etapas, sin cuentas, tickets, frames o pulsaciones.
4. Detector/input requieren contrastar las APIs reales del árbol reconstruido.
   No trasladar a ciegas las ROIs host2460×1080 de `functional_login.py`; adaptar
   coordenadas al guest y comprobar modales/overlays/foco antes de cada fase.
   PixelCopy solo transitorio, sin archivos de imágenes del formulario.
5. Si falta una prueba positiva suficientemente discriminante de formulario,
   no habilitar envío automático por temporizador. Registrar ese bloqueo técnico
   y conservar UI/entrada manual, sin vender una función que no se ha validado.

### Prueba física autorizada y credenciales

La cuenta de pruebas anterior está autorizada. Sus ficheros efímeros se consumieron
y eliminaron; no se buscan ni reconstruyen credenciales en logs/configuración.
Se ha solicitado al usuario recrear un fichero privado fuera del repo,0600, y
facilitar solo su ruta cuando se necesite la prueba. No bloquea implementación ni
tests sintéticos; sí bloquea prueba con esa cuenta hasta volver a disponer de ella.
No incorporar el secreto a fixtures, APK, argumentos de procesos o entregas.

**Actualización:** el usuario volvió a proporcionar la cuenta temporal y autorizó
crear el fichero. Se creó fuera del repo dentro de directorio0700/archivo0600;
no se pasó a workers, APK, argumentos ni logs de aplicación. No se usó para Login
porque falló el gate de formulario. El fichero se eliminó al cerrar esta ronda;
no registrar aquí valores ni pedir al usuario que los repita innecesariamente.

## Implementation / Action Items

- [x] 1. Registrar alcance aprobado y límites de la prueba de cuenta temporal.
- [ ] 2. Confirmar hooks reales de ventana/foco/captura/entrada y decidir el gate
  positivo sin ampliar a autenticación, procesos ajenos o modificación de Wine.
  - [x] APIs reales investigadas: WindowManager/Window, XServer pointer/key,
    SurfaceView/PixelCopy, transformación guest y cancelación de toque manual.
  - [ ] Validar formulario/campo actuales; metadata sola no autoriza entrada.
- [ ] 3. Implementar almacén memoria/Keystore y tests de ciclo de vida, corrupción,
  olvido y ausencia de fallback; integrar ajustes sin añadir texto a bienvenida.
  - [x] Almacén y12 tests JVM aprobados con datos sintéticos/backend JCE.
  - [x] Integración en fuentes de Ajustes: diálogo seguro, memoria/Recordar,
    borrado, prefs solo booleanas, compensación de errores/cancelación y11 tests.
  - [x] APK de Ajustes code72 instalado; diálogo protegido, dos campos y
    password enmascarado comprobados sin introducir credenciales.
  - [ ] Android Keystore real: APK auxiliar construido; instalación rechazada
    por Android. No cambiar permisos de seguridad para eludirlo.
- [ ] 4. Implementar controlador de envío único y readiness/input con tests
  sintéticos de foco erróneo, modales, timeout, cancelación y recreación.
  - [x] Controlador puro de token/permiso revocable,90s y Submit único;10 tests
    JVM aprobados tras corregir el arranque de threads del fixture concurrente.
  - [ ] Gate positivo del formulario/foco y adaptador de entrada real.
- [ ] 5. Review independiente de secretos y frontera de inyección; reparar y
  verificar antes de compilar/instalar un candidato.
- [ ] 6. Build aislado, auditoría de APK y prueba en móvil con cuenta temporal
  cuando vuelva a proporcionarse. Resultado de Login se registra sin inventar éxito.

## Open Issues

- Resolución adaptativa posterior: code74 y selector Auto/Rendimiento/Calidad/
  Nativa, plan en `launcher-display-fit-plan.md`. La geometría de ventana cambió;
  no activar el detector antiguo ni enviar credenciales por referencias code69.
  Fullscreen todavía tiene márgenes pese a desktop/ventana/CVars coherentes.
- Cuenta de prueba recibida de nuevo; no falta autorización ni datos de cuenta.
  Temporal eliminado al cerrar esta ronda sin enviar Login.
- Fiabilidad de identificación del formulario real y coordenadas después de
  fullscreen; es gate de envío, no permiso para inyectar en un form desconocido.
- La investigación confirma que callbacks map/unmap no ofrecen semántica de
  campos ni detectan un modal dibujado dentro de la misma ventana de WoW. La
  referencia host histórica2460×1080 no está validada para code69. Sin referencia
  actual/calibración y negativos de modal/foco, resultado `FORM_UNCONFIRMED` y
  entrada manual: no enviar credenciales por un temporizador.
- **Gate real bloqueado:** calibración code69,9 frames2460×1080 en memoria, todos
  rechazados por el matcher antiguo y sus anclas; negativo sintético de modal
  rechazado. No se exportó perfil ni se guardaron imágenes/formulario/campos.
  Resultado útil `FORM_UNCONFIRMED`; terminación literal del arnés fue
  `live-frame-capture-invalid` al límite de captura, no fallo de autenticación.
  Hace falta referencia visual actual sin campos rellenados o nueva calibración
  suficientemente discriminante. No reducir umbrales a ciegas ni inyectar por Tab.
- Verificar propiedad de operaciones/compensación entre recreaciones de Activity,
  gestos reales de selección/clipboard y proveedor Keystore antes de producción.
  Los tests de servicio no sustituyen estos gates.
- IME/mando/frame de code69 todavía tienen límites de validación física; no
  convertir la transición a Wine horizontal en autenticación/render acreditados.

### Núcleo implementado y verificado, no entrega de autologin

- Fuentes nuevas: `ForeverFunctionalAutologinCredentials.kt` y
  `ForeverFunctionalAutologinAttempt.kt`, con sus tests. Recetas cableadas para
  candidato70 de compilación del núcleo, sin modificar el APK code69 instalado.
- Revisión independiente corrigió IV de Android Keystore (lo genera el proveedor,
  no el caller), limpieza independiente de archivo/clave, copias/buffers propios,
  temporales y mismatch de ciphertext/clave en summary. Sin P1/P2 restantes en
  este núcleo; siguen pendientes los gates de integración y proveedor real.
- El controlador no contiene credenciales ni ejecuta entradas. Evidencia de form
  y campo debe venir de un futuro adaptador confiable. Instrumentación/parche de
  memoria deben estar OFF; habilitarlos no permite autologin.
- Workspace externo:
  `/home/adrian/wowforever-artifacts/arm64-prototype/autologin-core-code70-reviewed/`.
  `gradle-core-tests.log` registra12 tests de almacén aprobados y un fallo del
  fixture concurrente (threads creados sin start). Corregido solo ese fixture;
  `gradle-attempt-tests-corrected.log` registra10/10 de intentos, BUILD SUCCESSFUL.
  No se repitieron los12 tests del almacén no afectados. No declarar suite global.
- `core-test-verification.json` conserva el delta de test posterior al recibo de
  reconstrucción, sin reetiquetar el primer fallo como éxito.
- No se ha creado/instalado un APK de autologin, integrado Ajustes ni ejecutado
  Login real en aquella fase de núcleo. Ahora Ajustes está integrado en fuentes
  del candidato71, compilado y con tests, **sin APK generado/instalado**; sigue
  instalada la versión69. No hay detector o sender activo.

### Ajustes en fuentes y calibración (continuación de la ronda)

- `ForeverFunctionalAutologinSettings.kt` y su test: habilitación deseada como
  único booleano, diálogo `SecureOn`, password oculto, toolbar sin copy/cut,
  IO fuera de UI y compensación no cancelable. No habilita por sí mismo entrada.
- `runtimeReady=false`: mensaje explícito de entrada manual y consentimiento sin
  prometer que se escribirán credenciales. El fallback no se presenta como éxito.
- Se cerró además la resurrección de sesión tras fallo de olvidar: mientras el
  almacén esté `REENTRY_REQUIRED`, no adquiere ni anuncia secretos aunque una
  avería deje ciphertext/clave válidos. Solo nuevo save explícito permite salir.
- Workspace externo `autologin-settings-code71-reviewed`; primer build de tests
  detectó colisión de setter de un fake Kotlin/JVM. Corregido solo el fake
  (`enabledValue` interno con getter público); segundo log
  `gradle-settings-tests-corrected.log` →BUILD SUCCESSFUL,11 tests de Ajustes y
  1 regresión de olvidar aprobados. No repetir los anteriores12/10 ni sumar
  rondas como suite global. Solo compilación/tests, no assemble/install.
- El recibo original de ese árbol precede al arreglo del fake; registrar el delta
  de test, no afirmar equivalencia exacta de aquel recibo después de la corrección.
- Calibración inicial rechazó un prepared nuevo por comparar hora Android con
  host (~subsegundo de desfase); se diagnosticó read-only que había uno, no varios.
  Continuación de esa preparación exacta: ID1792138223731, flagsOFF, hash/preset
  intactos, un consentimiento y lanzamiento, rotación90°,9 frames rechazados.
  Cerrado paquete propio con exit0, runtime ausente, bienvenida vertical restaurada.
- Evidencia de calibración:
  `/home/adrian/wowforever-artifacts/work/functional-launcher-ux-smoke-20261016T/functional-launcher-ux-20261016T081920153373Z-f6ce6295af/result.json`.
- No se introdujeron credenciales, no se produjo intento de autenticación y no
  se abrió la rama de SRP/TLS/patcher. El bloqueo es de readiness visual seguro.

### Continuación: entrega de Ajustes code72 (2026-10-16)

- Instalado `com.wowforever.arm64probe`, code72,
  `0.2.48-functional-autologin-settings`, sin borrar datos. **No es autologin
  funcional:** no hay sender habilitado ni detector aprobado, `runtimeReady=false`.
  Ajustes permite configurar el estado deseado, con aviso explícito de entrada
  manual. No se guardaron las credenciales reales proporcionadas en la aplicación.
- APK SHA-256 `d4e05e205ea3f9614bed71b507b0e70880bdcd2c275c0a0604b244611462dfe2`,
  418942780 bytes. Firma conservada,2138 inputs registrados sin cambios durante
  build y174 payloads iguales al baseline64 salvo marker; runtime/JNI intactos.
- Workspace externo:
  `/home/adrian/wowforever-artifacts/arm64-prototype/autologin-settings-code72-framework/`.
  `reconstruction-receipt.json`, `gradle-main-build.log`, `apk-final-audit.json`
  y `settings-device-ui.json` registran procedencia, build y comprobación UI.
- Build principal offline `testModernDebugUnitTest --tests app.gamenative.ForeverFunctionalAutologinSettingsTest assembleModernDebug`
  →BUILD SUCCESSFUL,3m36s,16 tests de Ajustes sin fallos. No se repitieron tests
  del núcleo no afectado. Review independiente final: sin P1/P2 en Ajustes.
- Se cerraron las carreras entre Activities: coordinador de proceso con
  generaciones y lock de transacción completa; compensación antigua no borra un
  save posterior. Olvidar admitido bajo lock termina prefs/almacén aunque se
  cierre la UI.16 tests cubren estas guardas, no solo el camino feliz.
- UI física: «Acceso automático» accesible, aviso manual visible, dos campos,
  password con semántica enmascarada y flag `SECURE` en el diálogo. Sin escritura
  de campos, screenshot del diálogo, Login, clipboard o modificación de flags.
- Gradle androidTest solicitó dependencias no cacheadas incluso al aislar el
  harness stock; no se descargaron. La prueba usa framework, por lo que se
  construyó por separado con Kotlin/D8/AAPT2/zipalign/apksigner existentes.
  Recibo de herramientas, comandos e inputs en `provider-check-build/`.
- Harness sintético exclusivo: alias Keystore y `noBackupFilesDir` de validación
  separados de producción; fases write/restore-and-forget/cleanup, sin entradas
  secretas por Intent ni Activity de test añadida al APK principal.
- Test APK SHA-256 `58b4bae8f08716118ca84e4908145169d1cfabe69a8059752491898b96196fcc`.
  Instalación rechazada: `INSTALL_FAILED_USER_RESTRICTED` / cancelación de
  instalación. No se ejecutó instrumentación ni se cambió seguridad del móvil.
  Requiere autorización normal de instalación del usuario si se continúa.
- Vista redactada del cliente69: fondo de acceso del juego con márgenes laterales.
  Solo tiras exteriores persistidas, centro y barras negros antes de escribir;
  no username/password ni frame crudo. No es template para el APK. Evidencia en
  `.../functional-launcher-ux-20261016T092157636620Z-3ef65aa960/`.
- Registro geométrico con escala/traslación y umbrales originales:19 tests
  sintéticos aprobados, pero dos frames reales no aceptados (`FORM_UNCONFIRMED`)
  en `.../functional-launcher-ux-20261016T093229710172Z-fa862cb5c3/`. No se debilitó
  el gate ni se autorizó entrada. Ambas sesiones propias cerradas y bienvenida
  restaurada; otros Mali/aspectos/field focus no validados.
- Perfil Forever/memoriaOFF/capturaOFF y hashes de PE/WTF original conservados.
  Directorio temporal de credenciales vacío al cerrar la ronda. No hace falta
  volver a pedir la cuenta autorizada; falta resolver el formulario seguro.

### Capturas completas autorizadas por el usuario

El usuario pidió la captura de configuración y después la del cliente «sin
redactar», aclarando que la cuenta es de prueba. Se autoriza esta observación
local, no desactivar `FLAG_SECURE`, introducir credenciales ni publicar secretos.

- `general-settings-code72.png`: configuración general de servidor, entrada a
  acceso automático y diagnósticosOFF. No editor de credenciales.
- `client-unredacted-code72.png`: cliente72 en horizontal con formulario vacío
  completo, sin máscaras; se observan los márgenes laterales reales y el botón
  overlay de teclado. **Ahora sí hay evidencia visual del formulario actual y
  render en code72**, sin Login ni validación todavía del campo o sender.
- Ambas fuera del repo, en el workspace `autologin-settings-code72-framework`,
  permisos0600. SHA-256: ajustes `2e2295a5c2fc0364e243ef9ce190203f7e36bebce896dd5b600a0072b928bb40`;
  cliente `c0513bc0ca7171c92e4a417c5de5bbaa25c96dbcf0028c617e5802a30c37c86b`.
- Captura de cliente con preparación/nuevo log/perfil/flags/hashes verificados,
  consentimiento y un lanzamiento propios. No escritura en campos ni Login.
  Cleanup del paquete propio exit0, runtime ausente y bienvenida reabierta.
- La imagen completa actual permite preparar una referencia nueva sin depender
  del layout histórico. No convertir automáticamente verla en permiso de envío;
  falta validar detección, ausencia de modal y foco de cada campo en el APK.
