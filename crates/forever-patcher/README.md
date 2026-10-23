# forever-patcher

Localiza y sustituye en memoria el almacén de claves del cliente WoW Classic
Beta (parche Forever, Fase 3). Base del futuro APK: el launcher lanzará el
juego, así que lector y objetivo comparten uid y no hace falta root.

## Diseño

- `src/contract.rs`: carga `forever/contract.json` (`key_store`) y lo valida.
  Las 12 claves **no** están en el código; solo en el JSON.
- `src/memory.rs`: `trait MemoryAccess { regions, read, write }`,
  `SliceMemory` (doble de test sobre `Vec<u8>` + base) y `LinuxProcessMemory`
  (`/proc/<pid>/maps` + `/proc/<pid>/mem`, mismo uid).
- `src/patch.rs`: `scan_stores` (no escribe), `patch_stores`,
  `ensure_once` y `ensure_loop` (cancelable por clausura, intervalo
  configurable). Todo puro sobre el trait, sin `unsafe`.
- `src/bin/forever-patch.rs`: `scan --pid <N>` (informa) y
  `ensure --pid <N> [--interval-ms]` (aplica y mantiene hasta Ctrl-C).

Formato del bloque: 12 entradas contiguas de 40 bytes =
id u32 LE (1..12) + 32 bytes de clave + 1 byte de flag + 3 de relleno.

## Decisiones

- Regiones: solo `rw-p` privadas **sin** ruta de fichero (anónimas, `[heap]`,
  `[stack]`); nunca código ni `.so`/ejecutables mapeados.
- Alineamiento: el ancla solo cuenta en direcciones múltiplo de
  `scan_alignment` (absolutas, no relativas a la región).
- Relleno: se acepta cualquiera de `accepts` (`7f0000` Windows, `000000` Wine).
- En el grupo 8 se admite clave conocida o `new_key` (reaplicación idempotente).
- Lectura por trozos de 256 KiB con solape (bloque + 32) para heaps grandes.
- Una región que se vuelve ilegible a mitad del escaneo se salta, no aborta.
- `scan` nunca escribe (hay test que lo comprueba byte a byte).

## Uso

```bash
cargo run -p forever-patcher --bin forever-patch -- scan --pid <N>
cargo run -p forever-patcher --bin forever-patch -- ensure --pid <N> [--interval-ms 2000]
# --contract <ruta> o $WOWFOREVER_CONTRACT; por defecto: forever/contract.json
# (si no existe en el cwd, se busca hacia arriba hasta la raíz del repo)
```

## Prueba de integración cross-proceso

`tests/process_integration.rs` valida la ruta real `/proc/<pid>/mem` sin
juego ni `sudo`: lanza `src/bin/store_holder.rs` como hijo, que reserva con
`mmap` anónimo `PROT_READ|PROT_WRITE` un almacén válido (12 claves del
contrato, relleno `000000`), publica su PID por stdout y cada 500 ms imprime
`PENDING`/`PATCHED` según vea o no `new_key` en su propia memoria. El test
hace `scan_stores` (exactamente un almacén), `patch_stores` (primera vez
`already_patched = false`), espera el `PATCHED` del hijo (timeout 10 s) y
repite `patch_stores` (`already_patched = true`); al final cierra el stdin
del hijo (o `kill` si sigue vivo) para no dejar huérfanos.

```bash
cargo test -p forever-patcher
# o solo la integración: cargo test -p forever-patcher --test process_integration
```

Si el entorno no permite el acceso (p. ej. `/proc` ausente), el test se omite
con `SKIP: ...` en stderr en vez de fallar.

## Nota de entorno (ptrace_scope, dumpable)

Con `kernel.yama.ptrace_scope=1` (valor por defecto) solo se puede
leer/escribir la memoria de procesos **descendientes** (hijos del lector).
Además, los procesos lanzados vía `setgid`/`sg` quedan **no dumpables** y son
ilegibles incluso para su propio uid. Por eso el test usa un hijo directo del
propio `cargo test` (descendiente, mismo uid, dumpable) y funciona sin
privilegios. El diseño del launcher Android evita el problema de la misma
forma: es él quien lanza el juego, así que lector y objetivo comparten uid y
relación de descendencia, sin root.
