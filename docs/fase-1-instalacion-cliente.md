# Fase 1 — Instalación del cliente: estado y hallazgos

> Bitácora de los ensayos de instalación completa del cliente en el host. Complementa
> `.agents/specs/US-001.md` (comportamiento) y `docs/architecture.md` §7.

## Corte posterior —2026-10-21, cliente Android70450

C117 terminó la publicación de70450 ARM64/enUS en USB. La primera ejecución
rechazó los índices locales por sección de actualización truncada; se corrigieron
solo16 índices conservando el resto del cliente y la reserva48KiB quedó integrada
en escritor/estimador/reanudación desde C122. No se repitió una descarga completa
para validar ese escritor: se usaron regresiones de metadata completa y aceptación
posterior del cliente nativo. C127 ya logró Login y entrada al juego, confirmados
por el usuario, después de corregir además la clave de identidad del servidor.

Esto no certifica que el árbol `client/` del host esté completo ni actualiza sus
mediciones históricas x86_64 al objetivo ARM64 actual. La importación por pegar
`Data/` + `.build.info` está implementada, pero su ensayo físico con otra copia
sigue pendiente. Singlepass no implementado. C128 está lista/auditada para adelantar
el parche, sin instalar durante la partida actual.

[Estado consolidado](launcher-app-status.md), [handoff](downloader-handoff.md),
[evidencia Forever](arm64-forever-connection-progress.md).

## Tamaño real del baseline histórico x86_64

| Medida | Valor |
|---|---|
| Download manifest con **todos** los tags | 136,1 GB (2.505.571 entradas) |
| Filtrado `Windows` + `x86_64` + `enUS`, según el **size manifest** | **84,7 GB** |
| Filtrado según el *download manifest* (cálculo previo) | 100,24 GiB |
| Install manifest (loose), filtrado | ~0,37 GB (42 ficheros) |

En ese ensayo mandaba el **size manifest** (84,7 GB); el cálculo desde el download
manifest sobreestimaba. No extrapolar esa cifra a otra build/arquitectura.

En aquel ensayo móvil solo se empujaron1,1GB de archivos loose. Ya no describe la
instalación USB actual; la ausencia de anclas en búsquedas antiguas tampoco
demuestra por sí sola que el almacén no llegara a crearse.

## Por qué se queda sin memoria (OOM)

El paso `Installing N files to local CASC` retiene en memoria:

1. **Todas las tareas de descarga encoladas antes de consumirlas**: `commands/install.py`
   hace `await queue.submit(...)` para **cada** entrada y solo después consume con
   `async for result in queue.run(...)`.
2. Una `asyncio.Queue` **sin límite** en `core/download_queue.py` (`submit()` es
   `await self._queue.put(...)`), y cada elemento guarda un closure.
3. El manifiesto completo (2,5 M), los pendientes (1,3 M), una copia ordenada, el
   diccionario de prioridades (1,3 M claves hex) y los índices de archivos de la CDN.

Consecuencia: el pico de RSS depende del **catálogo** y del **trabajo en vuelo**, no
de lo ya escrito. Medido:

| Lote | Pico observado | Resultado |
|---|---|---|
| 50.000 ficheros | ~26 GB `anon-rss` | **OOM-killer** (`total-vm:27,6 GB`) |
| 20.000 ficheros | 14,8 GB | completó |
| 15.000 ficheros | ~10–18 GB | completó |

Bajar la concurrencia (`CASCETTE_MAX_CONCURRENCY`) **no basta**: la muerte se produjo
con 6/2 y aun así fueron 26,1 GB. Lo que sí acota el pico es **reducir el lote**.

> Nota: el mensaje `Concurrent connections: 12 global, 3 per host` de `install.py`
> es un **literal fijo**; no refleja las variables de entorno reales.

## Reanudación: funciona

`InstallState.load(...)` **sí se llama** (`install.py:2649`); el estado acumula entre
ejecuciones en `client/Data/.install_state.json` (descargados, fallidos, bytes
escritos, estadísticas por prioridad). Reanudar es barato: no se re-descarga.

Cuidado al leerlo: si se lee **mientras** el proceso escribe, puede dar una foto
intermedia (parecía "no acumular" cuando en realidad era un run en curso).

## Herramienta

`scripts/install-client-chunks.sh <dir> [lote] [max-runs]` — instala en lotes
acotados y para si no hay progreso; lee estado entre lotes. Variables:
`CASCETTE_MAX_CONCURRENCY` (def. 6), `CASCETTE_MAX_PER_HOST` (def. 2), `UV_BIN`.

Uso recomendado en este host (28 GB, ~15 GB libres con el escritorio):

```bash
nohup env CASCETTE_MAX_CONCURRENCY=4 CASCETTE_MAX_PER_HOST=2 \
  scripts/install-client-chunks.sh /home/adrian/dev/wowforever/client 15000 300 \
  >> /home/adrian/wowforever-artifacts/chunks-run-2.log 2>&1 &
```

### Trampa de shell (nos ha mordido dos veces)
`pkill -f` / `grep -f` con un patrón que aparece **literalmente en la propia línea de
comandos** mata la propia shell (el comando parece colgarse hasta el timeout). Filtrar
`$$`/`$PPID` o construir el patrón de forma no literal.

## Progreso (2026-09-28)

- Estado: **~204.000 de ~1.300.000** ficheros pendientes procesados (≈ 38 GB escritos
  acumulados según el estado).
- `client/Data/data`: ~20 GB en 37 ficheros `data.NNN`; `client/Data/indices`: 309 MB.
- El objetivo de completarlo es **validar la Fase 3** (que el cliente cree el
  `CertStore` y poder probar el patcher contra un almacén real), no el producto.

## Bug de reanudación entre lotes (encontrado y corregido)

La instalación por lotes **no avanzaba**: 18 runs seguidos procesaban siempre los
mismos 15.000 ficheros / 2,6 GB, sin una sola línea de reanudación.

Causa, en `cascette_tools/commands/install.py`:

```python
# Clean up state file on successful completion (no failures)
if failed == 0 and integrity_errors == 0:
    install_state.cleanup()
```

Con `--max-files N` el lote se trunca (`pending_entries = pending_entries[:N]`),
los N se instalan, `failed == 0` y **se borraba `Data/.install_state.json` aunque
quedara trabajo**. El run siguiente empezaba de cero. La reanudación *dentro* de un
run sí funcionaba; la de *entre* runs, no.

Corregido en nuestro fork (`apazosg/cascette-py`, commit
`c13c16c3b80008e8a9715f7dcbc9abea10130412`): se captura el número de pendientes
antes de truncar y **no se borra el estado si el lote se truncó**. Verificado en dos
runs consecutivos (`Resuming: 2.000 files already downloaded` + `Skipping …`, y el
estado creciendo) y con la suite del fork en verde.

## Relevancia para el APK

Nada de esto se empaqueta: es una herramienta de escritorio. El APK solo necesita un
**descargador acotado** del esqueleto (+ verificación y reanudación) con memoria
proporcional a una ventana, y dejar que el propio cliente **streamee** el resto
(`docs/launcher-design.md`, D-L2).
