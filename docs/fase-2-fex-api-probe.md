# Fase 2: control de APIs en FrontMage/FEX

## Resultado observado

El usuario ejecutó manualmente `C:\wow\diag\ApiProbe.exe` en el contenedor
FrontMage. Se leyó su resultado por ADB USB (`MZAYW4SCFEJBTCLV`):

```sh
adb -s MZAYW4SCFEJBTCLV shell "run-as com.winlator.llm cat files/imagefs/home/xuser-1/.wine/drive_c/wow/diag/api-probe.txt"
```

La sonda terminó con `PROBE-END result=0`:

- `LoadLibraryExW(L"api-ms-win-core-synch-l1-2-0", NULL, 0x800)`:
  handle válido, error 0, módulo resuelto a `kernelbase.dll`.
- La misma carga con flags 0 también funciona.
- `GetProcAddress(kernel32, "LoadLibraryExW")`: `0x4fff802880`,
  memoria de imagen comprometida con permiso de lectura/ejecución (`0x20`).
- `GetProcAddress(kernel32, "InitializeCriticalSectionEx")`:
  `0x4fff802560`, igualmente en memoria ejecutable.
- Llamada a `InitializeCriticalSectionEx` con spin count y flags 0:
  resultado 1, error 0; `DeleteCriticalSection` retorna.

Las direcciones son propias de esta ejecución, no constantes de configuración.
La sonda llama a la función de sincronización resuelta desde kernel32; no prueba
todos los posibles caminos de resolución ni el manejo de memoria del loader.

## Interpretación limitada

Este control no reproduce el fallo de WowB y descarta la hipótesis simple de que
estas APIs o este API-set estén ausentes o fallen siempre en el runtime.
No demuestra que el loader del juego las resuelva de la misma manera.

El fallo anterior de WowB sigue siendo una violación de ejecución hacia una
dirección baja sin mapeo observado (`0x1A23D3F` en la tercera corrida), con retorno
en `WowB_loader.dll+0x203E6AB`. El análisis estático de la rutina apunta a carga
del API-set y búsqueda de `InitializeCriticalSectionEx`; la identificación del
destino del slot indirecto todavía requiere evidencia dinámica.

Próximo diagnóstico: comparar el valor real del slot del loader, su evolución
y los destinos de resolución de Wine en la misma ejecución, sin sustituir ese
puntero ni parchear el cliente a ciegas. La causa raíz sigue abierta.

Sin evidencia de login, renderizado de WoW ni gameplay; Fase 2 no superada.

## Control offline host — 2026-09-28

Se corrigieron y validaron los controles en Linux x86-64 con MinGW-w64 y Wine
x86-64, sin móvil y sin lanzar `WowB.exe`. El prefijo fue nuevo y aislado:

```text
/home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix
```

Comandos ejecutados (cada sonda terminó con código 0):

```sh
WINEPREFIX=/home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix \
WINEDEBUG=-all wine64 /home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix/drive_c/wow/diag/ApiProbe.exe
WINEPREFIX=/home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix \
WINEDEBUG=-all wine64 /home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix/drive_c/wow/diag/DynCodeProbe.exe
WINEPREFIX=/home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix \
WINEDEBUG=-all wine64 /home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix/drive_c/wow/diag/SlotProbe.exe --synthetic
```

La evidencia durable está en:

```text
/home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix/drive_c/wow/diag/api-probe.txt
/home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix/drive_c/wow/diag/dyncode-probe.txt
/home/adrian/wowforever-artifacts/probes-offline-20260928/wineprefix/drive_c/wow/diag/slot-probe.txt
/home/adrian/wowforever-artifacts/probes-offline-20260928/sha256sums.txt
```

Resultados relevantes de `DynCodeProbe`:

- Caso RCX+RDX: `100 + 23 = 123`.
- Caso de llamada API: el thunk ejecutó `GetTickCount64` con shadow space y
  alineación Windows x64; el valor retornado quedó entre las lecturas
  `GetTickCount64` host anterior y posterior.
- Caso alias: la vista RW escribió 17, la vista RX ejecutó 17; después de una
  nueva escritura RW, `FlushInstructionCache` y una segunda llamada RX ejecutó
  29. Las protecciones observadas fueron `RW` (`0x4`) y `RX` (`0x20`); el log
  marca `updated=1`, no un remapeo.
- Final: `DYNCODE-END result=0 failures=0`.

`SlotProbe --synthetic` verificó la lectura de un slot y de su destino con
`ReadProcessMemory`/`VirtualQueryEx`, registró el slot RW y su destino de código RX, y terminó
con `SYNTHETIC PASS read-only observation` y `PROBE-END result=0`. El proceso
sintético escribe únicamente sus propias páginas; el modo por defecto sigue
siendo el control del cliente y no se ejecutó en esta validación.

La prueba opt-in compilada de regresión negativa también fue ejecutada con la
raíz explícita `WOWFOREVER_PROBE_ARTIFACTS=/home/adrian/wowforever-artifacts`:

```text
1 passed in 26.00s
```

Validó con binarios compilados que API ausente, `FlushInstructionCache` fallido,
una constante emitida incorrecta y READY ausente producen código distinto de 0.
El fixture READY ausente registró `SYNTHETIC READY-TIMEOUT ... action=not-killed`
y no registró `SYNTHETIC PASS`; su subdirectorio temporal propio fue eliminado.

Además, la repetición durable de esos fixtures quedó en
`/home/adrian/wowforever-artifacts/probes-offline-20260928/negative/`:

```text
api-missing:    exit=1, DYNCODE-END result=1 failures=1
flush-failure:  exit=1, DYNCODE-END result=1 failures=6
wrong-constant: exit=1, case1 RESULT=43 (esperado 42)
no-ready:       exit=4, SYNTHETIC READY-TIMEOUT timeout_ms=2000 action=not-killed
```

Los ejecutables positivos y negativos usados están enumerados en el
`sha256sums.txt` actualizado, incluidos sus hashes SHA-256.

Hashes SHA-256 de los ejecutables usados están en `sha256sums.txt` (rutas
durables bajo `/home/adrian/wowforever-artifacts`). Esta evidencia valida la ABI
y semántica Windows x86-64 en Wine nativo. No valida FEX/Box64, no ejecuta un
runtime ARM64 y no reproduce ni demuestra un bug o crash del runtime móvil.
