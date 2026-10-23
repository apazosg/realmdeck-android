# Wine AA64: compiler47 reparado; historial hasta code54

2026-10-08. Solo POCO USB `DEVICE_SERIAL`/UID10330, Mali-G610/API34.
Planes aprobados y cerrados:
`.agents/planning/done/wine-aa64-shader-compiler-plan.md` y
`.agents/planning/done/wine-aa64-compiler-prefix-plan.md`. Code49–52 conservaban el
DLL del prefijo; code54 repara únicamente compiler47 con el builtin AA64 del mismo
Proton y backup. No otro override, driver/runtime/backend modificado, memoria o
login. No render/present aceptado ni nueva release normal; candidatos de laboratorio.

**Actualización2026-10-09:** este documento conserva el historial hasta54. La
variante55 verifica readbacks offscreen/backbuffer y30Present S_OK, pero sigue
CONTROL_INVALID por ownership/mapping y sin visual aceptada. Ver
`docs/wine-aa64-readback-results.md`; no se reinterpretan los resultados anteriores.

## Selección y controles

- Code49/0.2.25 introduce únicamente selección `d3dcompiler_47=b` en el último merge
  gestionado; preserva entradas previas de WINEDLLOVERRIDES, las tres variables
  PanVK, DXVK/noGL/Proton/input y flags. Asset builtin AA641114112 bytes SHA
  `c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add` se verifica
  sin instalarlo en el prefijo. El DLL x64 del prefijo no se cambia.
- 5 casos JVM builtin + 11 PanVK scope/commit pasan; fuente/receta82inputs/audit49
  pasan. APK49 370601729 bytes SHA
  `bade4a195adf5fb8f945d5370b3cd5dbf593b64555dcda97240e9ba2bd1c13ac`.
  Resultado `COMPILER_UNAVAILABLE`, sin error loader conservado todavía.
- Code50/0.2.26 solo añade `err+module` y WINEDLLOVERRIDES a metadata allowlist.
  15 JVM dirigidos pasan; misma selección funcional. RUN
  `RUN-1791474597008-47524231079`, carpeta `trial-20261008T154935Z-7c1d71570e`.
  El spawn registra exactamente `d3dcompiler_47=b`. D3D11CreateDevice S_OK11_1
  pero no carga compiler, no error `err:module` retenido. APK50
  370603093 bytes SHA `9b591349c82f81831d75f063f818df1c0c840be1ae1c1429fadd19408aad80d1`.

## Error Win32 medido, no inferido

Code51/0.2.27 usa una versión de fuente propia que registra GetLastError
**inmediatamente** después de LoadLibrary/GetProcAddress. No cambia el flujo D3D,
shaders, dispositivo, driver ni overrides. Fichero fijo versionado
`GraphicsProbeLog-ARM64.exe` para conservar el anterior sin overwrite.

- PE AA64 nuevo90112 bytes SHA
  `795acd6d878d6def7139566da6040b16bf2ee6a2a9edfb90d16d4af32a67ab9a`.
  El original89600/a19e672 permanece en host/device y empaquetado con pin auditado.
- 8 checks fuente/encoder/build nativos, 8 casos JVM ruta/provisión, 7 de hints/
  whitelist/guards y 24 de analizador HOST pasan en rondas dirigidas. Review
  cerrado: mezcla de ambos PE/descriptor extranjero o segundo descriptor
  contradictorio no puede calificar; las rutas/pins/ancestría no se relajan.
- Receta fresca/idempotente83inputs, build offline, APK final auditado
  **371940904 bytes**, SHA
  `cf2b84887456f607e9b742a356a874e938ea697ec6b9fabd0fa1f507b860ca18`.
  APK49/50 retenidos, auditoría51 previa al último hint también preservada.

Prueba automática: `trial-20261008T162845Z-c21cdc2bea`, RUN
`RUN-1791476944332-45857482464`. El archivo durable de la sonda registra:

```json
{"event":"compiler_load","moduleName":"d3dcompiler_47.dll","loaded":false,"getProcFound":false,"loadError":193,"procError":null}
```

**193 = ERROR_BAD_EXE_FORMAT**, no ausencia del archivo demostrada. No se llamó
GetProcAddress sobre un módulo NULL. D3D11 sigue S_OK/feature11_1/removedReason0,
shader `COMPILER_UNAVAILABLE`, finalFAILED/successfulPresents0. El código real
captura el error antes de cualquier otra API que pudiera sustituirlo.

## Límite y siguiente decisión del code51

No se ha demostrado aún qué archivo o import provoca el error193. El builtin
verificado tiene marker `Wine builtin DLL` y machineAA64. Read-only posterior
confirma `wined3d.dll` AA64 en prefijo y Proton, mismo tamaño8126464 y SHA
`e5e6084cb10a407301fddae7f699b3de34912667529ed832fa31dd35c60929a9`.
No hay evidencia que autorice otro override para esa dependencia.

Conservar error real y verificar la resolución Wine del compiler47 antes de
proponer un reemplazo de archivo, override adicional o DLL externa. El builtin
seleccionado no se considera cargado por la simple presencia del journal.
El analizador sigue requiriendo PE+PanVK en un PID/RUN/UID/startTicks y vínculo
propio; observación incompleta no significa fallback System ni acepta presentación.

Agent detuvo solo el paquete propio después de cada captura y cerró sus clientes
ADB/logcat; sin procesos de UID10330 al comprobar. Nativos WSI/datos/Wine/DXVK/
ICD/driver del sistema intactos. No APK jugable, shader, primer frame ni login.

## Code52: el archivo rechazado sí queda identificado

Logging-only cambia WINEDEBUG a timestamp/pid + err/warn/trace module + trace
loaddll, retirando el ruido X11. No cambia DLL/override, sonda o backend.
9 RuntimeSetup + 7 ProcessPolicy tests pasan, 28 HOST dirigidos pasan, receta
83 inputs fresca/idempotente y APK52 auditado. 370.639.693 bytes SHA
`d1e218ac996d8421d7a613ef0a43f80e4aeea851607408af3a5e8f62d186e733`.
APK51 `cf2b8488...` retenido, no nueva release normal publicada.

Una prueba automática: `trial-20261008T172634Z-7e46f046ad`, RUN
`RUN-1791480413367-45929808617`. En stderr tail aparecen en NT00e8:00ec:

```text
trace:module:load_dll looking for L"d3dcompiler_47.dll" in L"A:\\;C:\\windows\\system32;..."
trace:module:open_dll_file L"\\??\\C:\\windows\\system32\\d3dcompiler_47.dll" is for arch 8664, continuing search
warn:module:load_dll Failed to load module L"d3dcompiler_47.dll"; status=c000007b
```

El rechazo de arquitectura del candidato x64 se observa directamente, seguido de
STATUS_INVALID_IMAGE_FORMAT193. No aparece selección/carga builtin para ese DLL
en este intento. Los imports directos source+prefix wined3d/ucrtbase/kernel32/ntdll
tienen headersAA64 verificados de forma independiente después de detener el paquete.
Esto no prueba que todos los imports se cargarían, pero evita culpar a wined3d por
su arquitectura sin evidencia. Nuevas sustituciones no realizadas.

StdErr observado1.223.521 bytes; snapshot conserva head131072 + tail393216 con
truncación explícita. El texto relevante sí quedó en tail; el primer inspector
host buscó claves `head`/`tail` inexistentes y fue corregido a Base64 real, sin
cambiar snapshot/resultado ni repetir teléfono. Se conservan contexto y números
de línea del original; no interpretar vacío del primer inspector como falta de log.

La reparación limitada se propuso después de code52 y se autorizó por separado:
`.agents/planning/done/wine-aa64-compiler-prefix-plan.md`. No alterar headers para
aparentar AA64 ni falsear features; el resultado posterior se registra abajo.

## Code53/54: reparación aplicada y compilador cargado

La transacción valida source/target/RUN/prefix, conserva backup exclusivo y usa
rename atómico obligatorio con fsync, posthash y verificación inmediatamente antes
del spawn. Rechaza cambios desconocidos, symlinks, hardlinks, owner incorrecto,
cancelación y RUN obsoleto; no reemplaza otras DLLs.

- Tests dirigidos cubren swap/reuse, backup/rollback, IO, metadata, carreras,
  recuperación de commit/rollback/reuse interrumpidos y cancelación sincronizada.
  Revisión sin P0/P1 pendientes antes del control físico. Las correcciones se
  verificaron por rondas afectadas; no se afirma una última suite completa única.
- Code53 falló en la validación del root ImageFS antes de escribir o lanzar Wine:
  `container root is not the approved ImageFS home alias`. Evidencia
  `trial-20261008T215646Z-7b6035a927`; DLL original intacto. Code54 permite solo los
  spellings appfiles `/data/data` y `/data/user/0` del mismo package/user0; no otros
  usuarios, packages o escapes. Regresión de esos aliases verificada.
- Code54/0.2.30-automated: receta fresca85inputs/idempotente, build offline y audit
  PASS. APK **370710845 bytes**, SHA
  `cee6ffaf9f4a6b7f28b402139dcbd4c7e6d1022e77e6358136697a94370005d9`.
  APK52/53/54 retenidos, incluida `retained-0.2.30-code54.apk`; no release normal.

Trial actual: `trial-20261008T220723Z-00219df91e`, RUN
`RUN-1791497262380-683075998117`; boot
`969d1d0f-946a-42b2-9ea6-bd5960d98cc5`. Code53 no requirió otro reboot al haber
fallado antes del spawn. RootWine18120/startTicks69287, host16224; Wine0/8675ms
no es resultado PASS de la sonda.

### Transacción verificada en el POCO

`files/forever/controls/compiler-prefix/<RUN>/transaction.json` declara
`COMMITTED`, reusedfalse. Backup:
`backup-d3dcompiler_47.dll-7239e3f8-b5fc-42d3-9650-15481cd22164.bak`.

| Archivo | Tamaño / SHA / metadata posterior |
|---|---|
| Backup original PE8664 | 4453888 / `06dc23aaa86607e083ed1725f2e99cc40501caf93408569eb9f287b272b58e52` / UID10330, modo771, nlink1 |
| Target system32/compiler47 | 1114112 / `c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add` / UID10330, modo771, nlink1 |
| Source Proton builtin AA64 | 1114112 / mismo SHA instalado / UID10330, modo771, nlink1 |

Destino y backup conservan mtime original; se comprobó estabilidad del backup y
del recibo durante la colección readonly. Se conserva la reparación correcta y el
backup original, no se realiza rollback físico ni prueba de corte de energía.

La misma carpeta externa del trial ahora contiene:

- `compiler-prefix-transaction.json`: copia exacta del recibo.
- `compiler-prefix-original-x64.dll`: backup original adicional en host, hash/PE
  verificados, fuera del repo.
- `compiler-prefix-verification.json`: stat/SHA/source/target/boot y decisión de
  conservación; no declara quiescencia de kernel.

### Resultado real de la sonda

`graphics-probe.jsonl` tiene11filas del RUN correcto:

1. `compiler_load`: loadedtrue/GetProcAddresstrue, loadError/procErrornull.
2. `modules`: compiler cargado en `C:\windows\system32\d3dcompiler_47.dll`.
   Tail del loader confirma `find_builtin_dll` y `map_image_into_view` para él;
   ya no termina esa carga en el rechazo de arquitectura observado en52.
3. D3D11CreateDevice S_OK, feature11_1, DeviceRemovedReason S_OK.
4. Swapchain320x240/dos buffers creado S_OK; shaders vs_5_0/ps_5_0 `COMPILED`,
   incluyendo creación de shaders/input layout S_OK.
5. **triangle_readback FAILED**, Map S_OK pero coloredPixels0 y
   rgbaFnv1a3375453637; terminalFAILED/successfulPresents0. La sonda para antes
   de llamar Present al no verificar el primer dibujo.

FNV-1a del frame uniforme320x240 con RGBA8 **(10,10,20,255)**, cuantización del
clear(.04,.04,.08,1), da exactamente3375453637. Es consistente con readback
solo-clear, no con triángulo verificado; un hash32 no excluye colisiones. No
demuestra por sí solo cuál de draw/pipeline/backbuffer/copia falla. La inspección
de shaders/stride/viewport/geometría no establece una causa única ni justifica
culpar al driver, cambiar culling o añadir otra DLL.

StdErr total1358158 bytes; head131072+tail393216, intervalo omitido explícito.
`stderr-head-code54.txt`/`stderr-tail-code54.txt` conservan esos segmentos decodificados,
no una traza completa. Tail muestra DXVKv2.4.1 y graphics pipeline libraries;
los mensajes `err:vulkan:win32u_vkDestroyDevice` son de teardown, no un fallo
de Draw demostrado. No inferir ausencia de error en todo stderr a partir de los
segmentos retenidos.

### Calificación, cleanup y siguiente gate

`wine-qualification-code54.json`, generado una vez con el analizador existente:
**CONTROL_INVALID**, reportedStatusFAILED. Ninguno de los19maps guardados aporta
PE+PanVK en un mismo PID/startTicks y vínculo a la raíz propia. D3DDeviceObservedtrue;
driverMappingObserved/offscreenReadbackObserved/apiPresentObserved/visualPresentationObservedfalse.
No se relaja el gate ni se infiere fallback System por falta de captura.

Screenshot final muestra la UI propia con aviso de terminación desconocida, no
un triángulo Wine. Agent-stop propio consta completado; una lectura posterior
del UID10330 solo observa el `ps` del recolector. `termination_unknown` y
quiescenceConfirmedfalse se mantienen: **reiniciar Android antes de otro Wine**,
sin borrar datos ni tratar force-stop como contención demostrada.

La reparación del compilador se cierra con resultado positivo para carga/compilación,
no para render/present/WoW/login. El siguiente diagnóstico debe distinguir dibujo
offscreen del backbuffer y capturar píxeles/metadata suficientes; modificar la
sonda congelada requiere un nuevo alcance aprobado, sin swaps de DLLs/drivers.
