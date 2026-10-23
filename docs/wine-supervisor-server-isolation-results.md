# Wine SERVER_ISOLATION: APK60 validado, intento interrumpido sin GO

Estado a 2026-10-11: la puerta Java/native, el prefijo privado, el APK y el
collector están implementados. **No hay todavía una ejecución acreditada de
wineserver/cliente bajo este supervisor.** El segundo control quedó interrumpido
tras publicar su binding; el RUN autoritativo permanece `ACTIVE/PREFLIGHT`.

El plan general [wine-supervisor-handoff-plan.md](../.agents/planning/wine-supervisor-handoff-plan.md)
sigue en curso. La conexión gráfica, XServer/Pulse/SysV y el cliente WoW no se
han probado bajo esta nueva frontera. No se recupera el UNKNOWN de fixtures59.

## Candidato retenido e instalado

- Package `com.wowforever.arm64probe`, code60 / `0.2.36-automated`.
- APK418266965 bytes, SHA-256
  `76db60beacfb6d62a0f55b16c4d78b06e9b4351fd9a28de5695849b340800c24`.
- AA64, min29/target36, certificado debug fijado
  `2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b`.
- Entrada única habilitada del laboratorio: alias/Activity Wine con permiso
  `android.permission.DUMP`, proceso `:wine_supervisor`, perfil `server_only`.
- Supervisor Wine45056/SHA
  `6f641c3683b239eb58a3986e947afe2099555ef138d495f8490a000b5b52f0da`;
  manifest4745/SHA
  `8c9bcde26c1ae3a7ebcb620db47b5a5d4f9dc450f021ae4b4d97b28785b3b894`.
- Publisher de prefix123560/SHA
  `f479ea8def9dc3cffd42affa489ae1554f9214273b13af7d79053a2773ad6914`;
  manifest2368/SHA
  `63d5b1bf80e9039030839c7fd37139f9d9ea9c8f78cd2d3f3eee7de5ba99922d`.
- Imagen ZIP47680132/SHA
  `42f9e9119682f5e3cbd9071efe3f079d0cac76896fe3ce0fa9b2ef0d41048853`;
  image manifest124991/SHA
  `2318de72bb020da9263300511a2139abbd06c228f46fac1708d0fb759e625ba9`.

Artefactos y auditorías están en
`/home/adrian/wowforever-artifacts/arm64-prototype/delivery/`:
`retained-0.2.36-code60.apk`, auditoría source/validated y receipt de retención.
El campo `physicalTrialExecutedAtRetention=false` describe el momento de la
retención, no el estado posterior de estas pruebas.

Comparación ZIP real contra59:165→171 miembros assets/libs,164 existentes
byte-idénticos, solo provenance modificada y seis adiciones deliberadas:
dos ELF y cuatro recursos del supervisor/publisher/imagen. Proton/WCP, DXVK,
PanVK/noGL, PE, JNI, catálogo y cliente no cambiaron. Normal delivery33 y los
APK históricos permanecen retenidos; no hubo publicación ni commit.

## Implementación y verificación antes del dispositivo

- `ForeverRunStore` es la única autoridad RUN. El binding RUN↔WS/nonce se escribe
  antes de trabajos asíncronos; marcadores durables preceden a cada creación.
  Admisión Wine positiva requiere estado, resultado tipado, commit, gate raíz,
  ECHILD/exit0 y prueba independiente de testigo coherentes. No reset de UNKNOWN.
- Supervisor persistente schema2: server fijado `-f -p10`, después cliente
  `cmd.exe /c exit 0`, env/cwd cerrados, prefijo nuevo y namespace por dev/inode.
  No Bionic genérico, observer global, UID/name sweeps ni `wineserver -k`.
- Prefijo pristine verificado,411 regulares/163 directorios/3 links permitidos;
  tres DLL AA64 fijados. Publisher FD/hash y `RENAME_NOREPLACE`, sin clonar el
  prefijo compartido. Native y fuentes WCP tienen clases de autoridad distintas.
- Cancelación/terminalización, jobs/lectores/escritores reales, errores de
  durabilidad y cierre fallido permanecen fail-closed. Un handle o un EOF no
  sustituye reaping ni prueba de todos los descendientes.
- Host prefix32 tests; supervisor fake20 tests y tres dirigidos posteriores de
  fuente/metadata, por rondas. Publisher/imagen seis tests host.
- Cuatro clases JVM Wine:33 casos validados por rondas (30 y tres correcciones
  de fixtures/tests). Regresión adicional state/recovery:18 casos PASS.
  **No se afirma una suite completa del repo ni un único round combinado.**
- Fresh overlay reproducible/idempotente y equivalente al source real:
  `FRESH-WINE-OVERLAY-PASS 64`. Build JDK17 offline,1 worker, CPU2/Xmx4GiB y
  auditoría del APK real:PASS. No upgrade de AGP/SDK ni clean global.
- La receta59 original no era versionada y se perdió durante una sustitución.
  La nueva receta declara una migración: receipt de fuentes efectivas59
  `code59-source-baseline.json`, SHA
  `0a3d039a1d018230a9a99ab7c4de45d7e5753b75626868c8f06cad3678fdd0d8`,
  12 postimages y63 bindings autenticados; no falsa recuperación de sus bytes.

## Controles físicos

Solo POCO xaga, Xiaomi22041216G/API34, serial `DEVICE_SERIAL`, UID10330.
ADB siempre seleccionado explícitamente. No root, login, memoria PE, cuentas,
capturas de otras apps o modificaciones del cliente.

Base externa:
`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`.

| Directorio | Arranque acreditado | Resultado conservado |
|---|---|---|
| `wine60-server-isolation-6438cb4330924dc7891a416e3281bf7c` | Solo Activity; no Start/RUN nuevo registrado | `UNKNOWN`: readiness de UI falló |
| `wine60-server-isolation-b5c368d6845f414cbbbe56db5745a744` | Un consentimiento/Start y binding RUN↔WS | `UNKNOWN`: allowlist de ruta del collector falló |

Antes hubo preparación abortada y una instalación own-package60 que devolvió
success pero stderr no aceptado; una preparación posterior comprobó el SHA
instalado y completó reboot. Los STOP originales no se reescribieron. El helper
ya es versionado en `prepare_wine_supervisor_apk.py`, no depende de `/tmp`.

Primer control: el collector dedicaba8s a toda la secuencia y omitía polls
por `hasattr` sobre el módulo equivocado. Corrección host: ventana UI60s dentro
del deadline global240s/reserva20s; readiness sigue8s/3 dumps; polls awake y
keyguard obligatorios, un HID context, wake/settle y dismiss no-secure público.
No relajación del foco/jerarquía/consentimiento ni repetición de Start.

Segundo control, después de nuevo boot:

- Boot `06254205-1db4-4aaa-8f4e-7123db10f851`.
- RUN `RUN-1791718684192-65783495695`.
- WS `WS-024cc10c5e18649fad0c6567191aa18b`.
- Nonce `44fa3305f5c3db9fa0d8cd6541978ba8`.
- Binding `BOUND`,207 bytes/SHA
  `1c7d568d4ec506bb7a0a2233fa12ec1b453bf64143d5835f9e2bdf083b28bd22`.
- Estado genérico `ACTIVE/PREFLIGHT`,575 bytes/SHA
  `745eff1e97de89de1de4c5665ce064564022686cf8409b613cce71aebc022cf2`.
  `rootPid=null`, `launchAttempted=false`, `quiescenceConfirmed=false`.

El defecto host era determinista: `_private_path()` rechazaba lecturas fijadas
por WS cuando no recibían RUN; además leía el receipt una sola vez, aunque la
preparación asíncrona admite120s. El collector hizo own-package force-stop después
del fallo/captura incompleta. **Puede haber interrumpido preparación o persistencia;
las acciones originales no tienen timestamps y no prueban ese orden causal.**

Se corrigieron únicamente collector/tests: autoridad RUN/WS exacta, espera del
receipt y terminal same-RUN con deadlines, snapshots antes/después, binding
byte-estable, cuatro logs reales obligatorios para GO y captura parcial con
estados explícitos. Guarda terminal antes de Activity y no retry del RUN activo.
La suite actual del collector pasó58 casos; revisión fuente sin P1/P2 pendiente
en esa frontera. No se recompiló APK60 para estos cambios host.

## Preservación y frontera de lo observado

Closeout read-only aditivo:
`read-only-closeout-e4e8730ff3264482ab07db3cce79dbc3/`, dentro del segundo trial.
Dos snapshots de33 paths: estado/binding byte-estables; sin receipt preparado,
marcadores, frames, resultado/commit/admisión, logs o WS/prefix roots observados.
Eran `missing`, no streams vacíos fabricados. **Estas ausencias y el ps muestreado
no prueban que nunca se crearan hijos ni que se obtuviera ECHILD.**

Se verificaron los históricos56/57/EOF59, compiler AA64 y backup original antes
y después, sin writes/control en el closeout. No se repitió force-stop allí.
Los intentos incompletos del helper quedaron aditivos; el closeout completo no
sustituyó resultados ni seals originales. Verificación host posterior autenticó
todos los miembros de los dos seals y los33 estados de ambos snapshots.

Pins de evidencia original y suplemento:

- `result.json`:232/SHA
  `681b5154d73d292032249b25ad1e3235e4f34341c886771aba1ec6310d4a9bf9`.
- `seal.json`:SHA
  `ca8b128255f98c99639615c5e0faef65ddbed613c8f577750785149553a42003`.
- `supplemental-closure-seal.json`:SHA
  `4e6b7bcc0bf586f7f7fd36e7eb5efd4c338ba531562aafc141855ec5be9f9af2`.
- `post-trial-state.json`:9724/SHA
  `fd3b50ed666843f23040aadc3b4b87e4c792494c7d0690e9912b471a867ebc05`.

**NO-GO de SERVER_ISOLATION por falta de evidencia**, no veredicto negativo de
Proton/subreaper/driver. No READY/prctl/SPAWN/CLIENT/EXIT/cierre acreditados bajo60,
ni mapas PE/PanVK, API/visual o WoW. El RUN activo bloquea admisión; no se ha
rearmado, renombrado el store ni llamado recuperación genérica.

La siguiente decisión material está en
[wine-interrupted-run-disposition-plan.md](../.agents/planning/done/wine-interrupted-run-disposition-plan.md):
abandonar exclusivamente este intento tras un nuevo boot, preservando su estado
incierto sin convertirlo en PASS/ECHILD, antes de permitir un RUN nuevo.
