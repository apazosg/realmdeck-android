# C138 — entrada asistida desde el overlay

## Alcance aprobado —2026-10-23

C138 debe conservar todos los cambios de C137 y añadir la acción explícita
«Introducir cuenta» junto al teclado: usuario → Tab → contraseña → Enter, como
máximo un intento por lanzamiento. El usuario comprueba que Usuario está vacío y
enfocado y no hay diálogos. No es autologin automático, detector de campos ni prueba
de autenticación. No requiere otra aprobación funcional.

El [contrato US-006](../.agents/specs/US-006.md) y el
[plan activo](../.agents/planning/launcher-autologin-correction-plan.md) mantienen
las protecciones de credenciales, captura incompatible y escritor independiente.
No se autoriza usar la cuenta guardada ni capturar formularios poblados.

## Evidencia parcial y bloqueos

La implementación preliminar contiene sender, estado de un uso compartido entre
copias de sesión y cancelación observable. La interfaz está **deshabilitada por
código**, por lo que todavía no ofrece un botón utilizable.

El builder registró seis pruebas JVM sintéticas positivas y25 pruebas host de
reconstrucción positivas. Son pruebas del controlador con encoder/input falsos;
no validan todavía el transporte real ni el comportamiento en el móvil.

La revisión independiente sí pudo ejecutarse tras el fallo histórico de variante.
Detectó:

- **P1:** sinks locales de diagnósticos/output/logs del juego sin protección previa
  a persistencia/exportación para la entrada asistida.
- **P2:** geometría medida sobre el root Compose en vez de la vista XServer real.
- **P2:** falta de pruebas de encoder/dispatch de producción.

Correcciones acotadas pendientes; no habilitar por haber pasado pruebas sintéticas.

## Estado conservado al STOP de linaje

Los workers conservan cambios parciales, no una entrega aceptada:

- El sender ahora mide dimensiones y posición de la vista real del renderer,
  observa input manual antes de preparar credenciales e intenta liberar teclas ante
  excepciones. Las nuevas pruebas de encoder/dispatch de producción **no han
  terminado**: se corrigió un error de compilación, después hubo SIGSEGV de
  OpenJDK21 en C2 y el intento con compilación tier1 agotó180s. No extrapolar las
  seis JVM anteriores a esta revisión. Copia de pruebas externa:
  `/tmp/opencode/assisted-entry-correction-c138/GameNative/`; crash conservado como
  `hs_err_pid513932.log`. No hay resultado positivo del código corregido.
- Los sinks incorporan guardas preliminares con marcador privado
  `files/forever/assisted-credentials-sensitive`. Falta integrar el inicio antes de
  adquirir credenciales, mantener protección de ventana durante toda la sesión
  sensible y cerrarla únicamente al terminar esa sesión. La protección del runner
  host sigue incompleta; no se han añadido ni ejecutado pruebas de estas guardas.
  No impiden por sí solas escritura del cliente en logs/WTF ni captura de terceros.
- El gate sigue en `false`. Falta completar privacidad, verificar correcciones y
  repetir revisión del delta corregido, además del baseline de build pendiente.

La primera reconstrucción falló antes de compilar APK por paridad de
`assets/forever/provenance.json` y
`assets/forever/wine-interrupted-run-disposition-contract.json`. Directorio externo
incompleto:
`/home/adrian/wowforever-artifacts/arm64-prototype/client138-assisted-entry-review-pending-20261023/`.
No relajar la auditoría ni sustituir autoridades para superar esa diferencia.

La investigación posterior identifica una diferencia real de privacidad: C137/C64
conservan un identificador de dispositivo en el contrato histórico; la receta actual
lo anonimiza, cambiando contrato y procedencia. No es corrupción ni se debe restaurar
el dato. Se pausa la ejecución para aprobar un baseline anonimizado autenticado,
con delta explícito y paridad estricta para todos los demás payloads. La C138 debe
conservar el comportamiento de C137, no ese identificador privado. No se ejecutó
otra reconstrucción/build/auditoría en esta investigación.

El usuario aprueba corregir este bloqueo con «pues corrígelo para que podamos
avanzar». Se autoriza exclusivamente autenticar el contrato anonimizado y la
procedencia consistente como delta explícito; se conserva la paridad estricta de
todos los demás assets y JNI C137. La aprobación no habilita el sender incompleto.

No hay APK C138 auditada, instalación ni ensayo de credenciales. La C137 auditada
se conserva en `client137-aligned-search-20261023/` fuera del repositorio; JNI
SHA-256 `be86859b38c03daeeac37032578e5ae0fe77782c98fc1337190e103d71bce472`, APK
SHA-256 `6ad513e4999861bb48a2cde11d53cc3b1e663f42fd241011c0807d25a4dd4a8e`.

## Reanudación tras aprobar el delta de privacidad

El sender corregido pasa 10 pruebas JVM focales con JDK17, sin errores,
fallos ni casos omitidos. Se comprueba el XML en
`/tmp/opencode/assisted-entry-correction-c138/GameNative/app/build/test-results/testModernDebugUnitTest/TEST-app.gamenative.ForeverFunctionalAutologinAssistedEntryTest.xml`.
Incluyen transporte con la clase Keyboard real, mayúsculas/símbolos, rechazo
completo de texto no representable, cancelación durante preparación y protección
de ventana recreada. El gate continúa deshabilitado. Esta evidencia sustituye
la verificación interrumpida del sender; no valida las guardas diagnósticas
todavía en corrección ni prueba envío en el dispositivo. Revisión independiente
del sender solicitada tras esta comprobación; sin APK ni ensayo de cuenta.

La auditoría admite únicamente dos sustituciones con hashes exactos: contrato
anonimizado y procedencia generada. Conserva la autenticación Code64 existente y
la paridad del resto de payloads. Las 28 pruebas focales de reconstrucción pasan;
no se ha repetido la reconstrucción mientras cambian las fuentes. El hash de
procedencia continúa explícitamente sin congelar y bloquea la producción: se
fijará tras revisar y congelar las fuentes y su receta. No es una excepción
abierta ni una reconstrucción validada. Revisión independiente solicitada.

Revisión independiente del delta de linaje: sin defecto accionable en el código
facilitado al revisor (su entorno no permitió leer el repositorio). La aprobación
se limita al cierre seguro y a la excepción exacta de dos payloads; no sustituye
la congelación y revisión de los hashes finales ni una reconstrucción real.

### Verificación y riesgos pendientes de las guardas diagnósticas

Las seis pruebas Python actuales de
`tests/test_usb_forever_sensitive_session.py` pasan; `py_compile` también.
La primera comprobación JVM focal de `ForeverHostSafetyTest` compila y pasa en una copia
separada, `/tmp/opencode/assisted-privacy-verification-c138/GameNative`, con el
JDK17 existente. El fallo de acceso a herramientas de los workers no demuestra
que ese JDK falte. Se solicita de nuevo revisión independiente de sinks; todavía
no está aprobada.

La inspección del integrador encontró dos rutas adicionales: escritura tardía de
`appendRaw` tras cerrar el marcador activo y exportación de `Logs/` compartidos
desde un RUN posterior. Se corrigen consultando también el taint persistente del
RUN al escribir y omitiendo los logs compartidos tras cualquier RUN sensible.
Se amplía la regresión focal y se repite únicamente la clase afectada: diez pruebas
JVM pasan, sin fallos, errores ni casos omitidos. XML:
`/tmp/opencode/assisted-privacy-verification-c138/GameNative/app/build/test-results/testModernDebugUnitTest/TEST-app.gamenative.ForeverHostSafetyTest.xml`.
Las cinco fuentes de privacidad/pruebas coinciden byte a byte con esa copia.
No se afirma que la APK controle los logs generados por el propio juego.
Se mantiene `ASSISTED_ENTRY_ENABLED=false`.

### Revisión final del sender

La revisión encontró dos carreras adicionales: suscripción al input manual después
de preparar privacidad y supresión global de eventos durante la inyección. Se
corrigen instalando primero el observador y distinguiendo los eventos propios por
hilo. Las doce pruebas focales del sender pasan con JDK17; XML y cuatro fuentes
coinciden con la copia externa indicada arriba. La revisión independiente del
sender y sus llamadas de limpieza no deja hallazgos accionables. No exige un
harness de terminación completo adicional: se conserva el límite de prueba del
RuntimeGate existente. Esta aprobación no incluye sinks de privacidad ni habilita
la función; ambos siguen como prerrequisitos de la APK utilizable.

La revisión de privacidad encuentra dos P2 adicionales: el sondeo host puede
confundir una carpeta inaccesible con ausencia del marcador, y el upload remoto
mantiene el lock de privacidad durante red, bloqueando lectores de output e inicio
de sesión sensible. Se solicita cierre seguro ante permisos ambiguos y admisión
breve de uploads que rechace la entrada asistida mientras haya un envío en curso,
sin mantener el lock durante red. Ambas correcciones están aplicadas: ocho pruebas
Python y once JVM focales pasan, sin fallos ni errores. XML en la misma copia
aislada de privacidad; las fuentes reparadas coinciden byte a byte. Se solicita
revisión final del delta; no se habilita por haber pasado estas pruebas.

La revisión final de privacidad no deja hallazgos y aprueba el delta para auditoría
de APK. Mantiene pendiente la comprobación física de `FLAG_SECURE`/captura; no
autoriza habilitar el botón antes de ella y de la auditoría. La siguiente
compilación seguirá con el gate cerrado, sin uso de cuentas ni instalación implícita.

## Candidato compilado con gate cerrado

La reconstrucción posterior a `55c383c` y la compilación offline con JDK17 terminan
correctamente. Los cinco inputs revisados admitidos por la receta tienen hashes
exactos; los demás bindings conservan la autenticación histórica. Las pruebas
focales de esta congelación pasan: una de admisión de overlays y cuatro de
reconstrucción/privacidad. No se repiten suites globales.

Directorio externo:
`/home/adrian/wowforever-artifacts/arm64-prototype/client138-gated-candidate-main55c383c/`.
APK: `source/GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa/app/build/outputs/apk/modern/debug/app-modern-debug.apk`.

| Evidencia | SHA-256 |
| --- | --- |
| APK, 484641531 bytes | `a1b3d975e55c36f1ae5ba37d6db360fbf2f1ed9ee9798a506b3b74b6ef799181` |
| `reconstruction-receipt.json` | `6d6c701c4f769fa88edae0d22f3227beb487abbfcb35fb88f2db04c09bc369af` |
| Receta `apply_overlay.py` | `bf7b903a3c5f409755077444e262161bd51ea464330e419e91ecdec425734a87` |
| Provenance empaquetada | `a6104250dac1adf2c257fc0ac284767836dd0cbbe2cf9089768b860f352735e5` |
| Contrato anonimizado empaquetado | `c198e20f582b90e5bc691e3e4712e8b2b1ecf33434bf1f86232c30be7fab2ac6` |

El integrador comprobó directamente hashes de APK, recibo, receta, ambos assets y
los cinco overlays fijados. La primera auditoría independiente falla porque aún
solo admite marcador y JNI, no los dos assets anonimizados aprobados. Se corrige
esa regla exacta y se auditará el mismo artefacto; no se omite el fallo ni se
restaura el identificador privado. **Compilada no significa auditada ni utilizable.**
El icono sigue deshabilitado. No se ha instalado ni usado ninguna cuenta.

La revisión independiente de los cuatro archivos de congelación/admisión no deja
hallazgos: cinco fuentes admitidas únicamente por ruta y SHA-256 exactos, resto de
bindings históricos preservados y anonimización limitada a `device.serial`.
El revisor pudo inspeccionar el repositorio; no se confunde este resultado con el
intento anterior sin acceso a archivos. Su aprobación no incluye todavía el delta
del auditor ni habilitar la función. El integrador comprobó también que el JNI
empaquetado conserva el hash de C137 y que las cinco fuentes productivas de sender,
Activity, sesión, RunStore y diagnósticos remotos coinciden con la copia compilada.

### Auditoría del mismo artefacto

El auditor actualizado acepta exclusivamente los dos assets fijados y sus bindings
de recibo, preimagen autenticada, inventario fuente y provenance. También admite
siete descriptores DEX exactos del helper de privacidad y wrappers generados de los
call sites revisados; no se añade un prefijo general. Dos regresiones focales
positivas/negativas pasan, además de `py_compile` y comprobación de whitespace.

La auditoría posterior devuelve `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2219 archivos
fuente sin cambios y178 payloads. No se reconstruye ni modifica la APK, el árbol
compilado o el recibo. Los fallos previos no produjeron informe y no se sobrescriben
con un PASS atribuido al auditor anterior.

- Auditor actualizado SHA-256:
  `5d7d184f387d3964279b92f75db61ede223fcfb9428ce3c86031539b66c632ff`.
- Informe externo `apk-audit-privacy-v2.json`, SHA-256:
  `b4e5d7a7d745afdb32f99e6e789be552d2cb4fa7174427cbf503f0bd08dbf783`.
- El integrador inspeccionó el diff completo y el informe, y comprobó ambos hashes.
  Revisión independiente adicional del auditor solicitada; el primer intento sin
  acceso al repositorio no constituye aprobación.

La revisión independiente posterior del código completo proporcionado no encuentra
defectos accionables y aprueba la lógica de la excepción exacta y sus regresiones
descritas. Su alcance es **código suministrado**, no inspección del repositorio ni
verificación independiente del artefacto/hashes/resultados. El integrador conserva
la responsabilidad de esa inspección directa, ya realizada. No autoriza habilitación.

Comando de auditoría, con `WORK` igual al directorio externo anterior:

```bash
python3 scripts/arm64-prototype/app/audit_functional_ui_apk.py \
  --receipt "$WORK/reconstruction-receipt.json" \
  --apk "$WORK/source/GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa/app/build/outputs/apk/modern/debug/app-modern-debug.apk" \
  --output "$WORK/apk-audit-privacy-v2.json"
```

El PASS de procedencia/payloads no verifica `FLAG_SECURE` físico ni autentica una
cuenta. Sigue pendiente coordinar una comprobación sin secretos reales y el ensayo
asistido expresamente autorizado. El botón permanece inaccesible y A5 pendiente.

### Coordinación física por ADB Wi-Fi

El usuario confirma que ADB Wi-Fi está activo y que el juego está cerrado. La
primera consulta no encuentra endpoints; mDNS descubre un único servicio de
conexión y `adb connect` conecta correctamente. La consulta posterior observa un
único endpoint online, paquete C136 (`0.2.112-priority-scan`) y ningún nombre de
proceso WoW/Wine/Box86/Box64. Es evidencia puntual, no una garantía de quiescencia
para una instalación posterior. No se registran direcciones ni identificadores.

Solo se realizaron consultas de versión y nombres de procesos: no instalación,
lanzamiento, captura, acceso a la cuenta ni modificación de datos del dispositivo.
Se prepara una comprobación mínima con ventana sintética y contexto privado aislado
para ejercer el helper de privacidad real sin usar el almacén ni el RUN existentes.
No está implementada ni verificada por esta coordinación, y no se autoriza inferir
un ensayo con la cuenta guardada.

### Instalación inicial y corrección de entrega solicitada

El usuario pide instalar inmediatamente. Se verifica de nuevo un único endpoint
online y ausencia de nombres WoW/Wine/Box86/Box64; el SHA-256 coincide con el APK
auditado anterior. `adb install -r` termina correctamente y la consulta posterior
confirma C138 `0.2.114-assisted-entry-review-pending`. No se pide borrar datos ni
se lanza el juego o se consulta la cuenta. Este artefacto sigue con el botón cerrado
y, por tanto, **no satisface la función solicitada**.

El usuario reclama expresamente el botón utilizable. Se prepara una compilación
corregida `0.2.114-assisted-entry`, separada del artefacto inmutable anterior, con
las guardas revisadas intactas. La comprobación física de captura queda pendiente
sin bloquear esta entrega experimental, según la actualización aprobada del plan.
No se declara verificada físicamente ni se utiliza la cuenta para comprobarla.
La preparación de instrumentación sintética queda detenida: no produjo APK de
prueba ni se ejecutó en el dispositivo.

### C138 corregida con botón habilitado: instalada

Solo se cambia el gate de compilación, la identidad de versión y su marcador/prueba.
No se modifica el sender, las guardas de privacidad, el almacén o el JNI de C137.
Revisión independiente del delta proporcionado sin hallazgos; limitada al código
aportado, no a comprobación independiente del artefacto. El integrador inspeccionó
el diff completo, comparó Activity con la fuente compilada y verificó hashes,
marcador empaquetado habilitado y JNI preservado.

- Directorio externo: `client138-enabled-candidate-20261023` bajo el mismo directorio
  de artefactos ARM64 usado arriba; APK en la misma ruta relativa de compilación.
- Versión: `138 / 0.2.114-assisted-entry`.
- APK SHA-256: `f3b78ecfe59c71c332cdc1303a4bbafdd303d3dc5b247a0ee4f7e52edde0027b`.
- Recibo: `03f0f420d55d953fcf66453ab276d09aea9ec950b8e7d0ac5baed093e3bab3c2`.
- Informe `apk-audit-enabled.json`:
  `1383028570861c550fbfc9cd87ce218a6a39331f90a8d4e5474d3159ef219171`.
- Dos pruebas focales de versión/marcador pasan; compilación offline JDK17 y
  auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT` (2219 fuentes,178 payloads).
- `adb install -r` termina con éxito y la consulta posterior confirma la versión
  corregida. No se solicita borrado de datos ni lanzamiento o acceso a la cuenta.

La protección física de captura y la entrada real en WoW siguen sin verificarse.
El marcador declara `physicalCaptureVerification: PENDING`. El borrador de
instrumentación no compilado se conserva fuera del repo en
`controls/android-assisted-privacy-preparation-20261023`, sin incluirlo en esta entrega.

### Fallo real comunicado: Tab no separa los campos

El usuario comunica: «se puso todo en username incluido el password». Esta prueba
refuta la corrección funcional del envío: las pruebas sintéticas y la auditoría
no garantizaban que Tab cambiase de campo en el cliente real. No se solicita ni
recoge el contenido del campo, capturas, logs o credenciales. Se indica borrar el
campo, no repetir el intento y cambiar la contraseña si llegó a enviarse así.

La inspección encuentra que el adaptador envía también Tab por `onVirtualKeyEvent`,
que proporciona `event.getUnicodeChar()` como keysym, mientras la ruta normal
`onKeyEvent` inyecta el código físico sin ese override. Se investiga y reproduce
esta diferencia con datos sintéticos antes de corregirla; aún no se declara causa
confirmada ni solución comprobada en WoW. No añadir temporizadores a ciegas ni
volver a instalar mientras el juego esté activo.

La investigación identifica el mecanismo en código: el Tab Unicode9 puede
sobrescribir el keysym de navegación X11 (65289). La corrección usa `onKeyEvent`
para Tab/Enter y conserva `onVirtualKeyEvent` para caracteres, sin modificar tiempos,
privacidad o reintentos. La reproducción es sintética; aún no verifica el cliente
real. El primer pase focal da2/2; la clase completa descubre además una precondición
ausente en el fixture de privacidad (RUN activo), que se corrige sin relajar el gate.
El siguiente artefacto tendrá identidad `0.2.114-assisted-entry-tabfix`, separado
de la APK defectuosa, para distinguir su instalación y evidencia.

Corrección verificada en host:13 pruebas de la clase del sender pasan, sin errores,
fallos ni omisiones. El integrador leyó el XML conservado en
`/tmp/opencode/c138-tab-fix-20261023/ForeverFunctionalAutologinAssistedEntryTest-13of13.xml`
y comprobó los hashes de ambos archivos frente a los ensayados. La prueba reproduce
el override Unicode9 y exige la ruta física para Tab/Enter, manteniendo cobertura de
símbolos/mayúsculas y liberación de teclas. Se crea explícitamente un RUN sintético
activo en el fixture de privacidad y se finaliza como cancelado al limpiar.

La revisión independiente del delta de producción no encuentra defectos. Su aviso
de mantener el gate cerrado es anterior a la decisión de entrega ya aprobada: este
arreglo conserva el botón habilitado y todas las guardas. No constituye revisión de
APK ni evidencia de navegación dentro de WoW. Dos pruebas focales de identidad y
marcador pasan; se reconstruye un artefacto separado en
`client138-tabfix-candidate-20261023`. Una consulta puntual de nombres de procesos
todavía detecta el guest activo: no se instala ni se interrumpe ese proceso.

El usuario confirma después «cerrado» y la consulta de nombres ya no detecta el
guest. La reconstrucción nueva, compilación offline JDK17 y auditoría final pasan
(2219 fuentes,178 payloads), conservando el JNI de C137 y todos los pins de assets.
El integrador verifica hashes, informe PASS, marcador habilitado y fuentes compiladas
del sender/Activity y prueba frente al repo. `adb install -r` termina correctamente;
la consulta posterior confirma `138 / 0.2.114-assisted-entry-tabfix`. No se solicita
borrar datos ni lanzar el juego o acceder a la cuenta. La separación de campos en
WoW aún requiere confirmación del usuario; no declarar resuelto por instalar.

- Directorio: `client138-tabfix-candidate-20261023` bajo los artefactos ARM64.
- APK SHA-256: `191441089436326056f633c35c980fbc4088cf6766cf1c784afd58ceaf4aaa81`.
- Recibo SHA-256: `d795b4be8e242e1b4383d7fc7a74d998cd17e74e0ddfbcc2b7f805314681bd16`.
- Informe `apk-audit-tabfix.json` SHA-256:
  `11c4339fce7c36dcc9a181f1fe1a94680cbf6c73019e438ac000dc819c0001a2`.
