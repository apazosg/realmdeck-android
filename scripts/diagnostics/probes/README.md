# Sondas de diagnóstico (Fase 2)

Ejecutables Windows **x86-64** mínimos para separar problemas del cliente de
problemas del runtime Android (Wine + traducción). Se ejecutan **dentro del
contenedor** del móvil; no tocan el juego, no modifican otros procesos y no
escriben más que su propio fichero de log.

Pensadas para desplegarse en `C:\wow\diag\` junto al cliente.

## Sondas

| Sonda | Qué comprueba | Salida |
|---|---|---|
| `ApiProbe.c` | Que las APIs que usa el loader funcionan fuera del juego: carga del API-set `api-ms-win-core-synch-l1-2-0` con y sin `LOAD_LIBRARY_SEARCH_SYSTEM32`, `GetProcAddress` de `LoadLibraryExW` e `InitializeCriticalSectionEx`, `VirtualQuery` de las direcciones resueltas y llamada real a la sección crítica | `C:\wow\diag\api-probe.txt` |
| `SlotProbe.c` | Lanza `WowB.exe` suspendido, lo reanuda y **lee** (nunca escribe) el slot `WowB_loader.dll+0x20B0738` y la memoria destino con `VirtualQueryEx`/`ReadProcessMemory`, registrando solo cambios | `C:\wow\diag\slot-probe.txt` |
| `DynCodeProbe.c` | Si el runtime **ejecuta** código máquina generado en memoria: constante, dos argumentos Windows x64, salto/bucle, llamada a una API real con shadow space/alineación, ejecución tras liberar y reasignar y dos vistas alias RW/RX con modificación posterior y `FlushInstructionCache` | `C:\wow\diag\dyncode-probe.txt` |

`DynCodeProbe` es el control más informativo: si falla ahí, tenemos un caso
reproducible pequeño, sin depender del juego. Un `DYNCODE-END result=0
failures=0` significa que todos los casos llegaron a sus comprobaciones; cualquier
fallo devuelve código de proceso 1 y deja el motivo en el log. Un fault durante
una llamada puede impedir la línea final, pero las líneas `CALLING` se fuerzan a
disco antes de entrar en código generado. El caso API exige que
`GetTickCount64` devuelva un valor entre las lecturas host anterior y posterior.

`SlotProbe.exe --synthetic` es un control host opt-in: crea un proceso hijo de la
propia sonda, lee un slot y su destino con `ReadProcessMemory`/`VirtualQueryEx`,
comprueba que la lectura de bytes sea completa y solo registra `PASS` después de
que el hijo termina con código 0. Nunca escribe en el hijo. La espera de READY
está acotada con `PeekNamedPipe`; ante timeout el proceso no se mata. El modo
sin argumentos conserva el objetivo real `WowB.exe`; no debe usarse como control
offline si el cliente no está disponible o no es seguro lanzarlo.

## Compilar (host Linux, MinGW-w64)

```bash
cd scripts/diagnostics/probes
MINW=x86_64-w64-mingw32-g++
FLAGS="-O2 -static -static-libgcc -static-libstdc++ -mwindows"

$MINW $FLAGS -o ApiProbe.exe    ApiProbe.c
$MINW $FLAGS -o DynCodeProbe.exe DynCodeProbe.c
$MINW $FLAGS -o SlotProbe.exe   SlotProbe.c -lpsapi   # EnumProcessModulesEx/GetModuleBaseNameW
```

`SlotProbe` **requiere `-lpsapi`**; sin ella el enlace falla con símbolos
indefinidos.

## Desplegar en el dispositivo

`run-as` permite escribir en el almacenamiento privado de la app
(es *debuggable*), pero **no** sirve para ejecutar Wine (SELinux). Se envía por
stdin, que es binario-seguro:

```bash
ADB=~/.local/opt/platform-tools/adb
D="$ADB -s <serial>"
R="run-as com.winlator.llm"          # o com.winlator, según el entorno
P="files/imagefs/home/xuser-1/.wine/drive_c/wow/diag"   # ruta del prefijo

$D shell "$R sh -c 'cat > $P/DynCodeProbe.exe'" < /tmp/DynCodeProbe.exe
```

Si el prefijo es de otro runtime (`com.winlator`, Box64), la ruta cambia
(`files/rootfs/home/xuser-0/.wine/drive_c/...`).

## Ejecutar

La app **no** lanza ejecutables desde `run-as`: hay que pedirlo desde la UI del
contenedor (o un acceso directo `*.desktop`). Ejecutar la sonda y esperar:

- `DynCodeProbe` y `ApiProbe` terminan solos en segundos.
- `SlotProbe` lanza `WowB.exe` y observa hasta 60 s.
- `SlotProbe --synthetic` termina solo en aproximadamente un segundo y no lanza
  el cliente.

Para una validación host aislada (Linux x86-64 + Wine x86-64), usar un prefijo
dedicado y conservar los artefactos fuera del repositorio:

```bash
ART=/home/adrian/wowforever-artifacts/probes-offline-YYYYMMDD
export WINEPREFIX="$ART/wineprefix"
export WINEDEBUG=-all
wine64 "$ART/DynCodeProbe.exe"
wine64 "$ART/ApiProbe.exe"
wine64 "$ART/SlotProbe.exe" --synthetic
```

Esta prueba host valida la ABI y la semántica de las APIs Windows x86-64. No
ejecuta un runtime ARM64, no prueba FEX/Box64 y no permite concluir que exista
un bug del runtime móvil.

La prueba de integración negativa es opt-in y compila fixtures que fuerzan API
ausente, fallo de flush, una constante errónea y ausencia de READY. Usa la raíz
de artefactos indicada por `WOWFOREVER_PROBE_ARTIFACTS` y elimina únicamente su
subdirectorio temporal:

```bash
WOWFOREVER_RUN_PROBE_INTEGRATION=1 \
WOWFOREVER_PROBE_ARTIFACTS=/home/adrian/wowforever-artifacts \
~/.venv/bin/python -m pytest \
  tests/test_fase2_probes.py::test_compiled_probes_pass_and_fail_under_wine -q
```

## Recoger el resultado

```bash
$D shell "$R cat $P/dyncode-probe.txt"
```

## Reglas de uso

- **Solo lectura** sobre procesos ajenos: nada de escribir memoria, cambiar
  protecciones ni inyectar en el juego.
- No sustituir DLLs del cliente ni parchear el loader para estas pruebas.
- Conservar los logs anteriores: comparar siempre con el timestamp.
