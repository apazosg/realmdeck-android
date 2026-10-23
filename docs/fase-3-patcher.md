# Fase 3 — Patcher Forever: estado y hallazgos

> Bitácora del componente que aplica Forever (portal + clave del grupo 8) en la
> memoria del cliente. Comportamiento normativo: `.agents/specs/US-002.md`.
> Contrato de constantes: `forever/contract.json`
> (verificar deriva con `scripts/contract-drift-check`).

## Corte2026-10-21 — WoW ARM64 real validado

C127 aplica la clave pública del grupo8 a un hijo WoW70450 propio en Android.
El usuario confirma Login y entrada al juego sin glitches gráficos observados;
no hay medidas de FPS/audio/controles ni prueba30–60min. C128 adelanta el inicio
del parche: compilada/auditada con32 tests JVM, todavía sin instalar ni ensayo físico.

- Núcleo y validadores comparten el relleno: byte37 variable, bytes38–39 cero.
  La referencia inicial `070000` procede de wowserver/x86_64; no se exportó el
  valor concreto de ese byte ARM64. Claves/IDs/flags no se relajaron.
- Lector funcional progresivo sobre todas las regiones legibles; cobertura con
  huecos/cambios es INCOMPLETE, nunca prueba de ausencia ni unicidad global.
- Nuevo puente explícito `nativeApply(token)`, sin dirección/bytes del llamador:
  solo un candidato observado con bloque480B/identidad/mapeo frescos. Escritura
  privada RW/RWX, anónima, nunca fichero/compartida/sololectura ni mprotect.
- Escritura única32B del grupo8, relectura480B, ancla1/otras entradas intactas,
  ALREADY_PATCHED sin escritura. Error parcial/postverificación bloquea nuevas
  aplicaciones del lease, sin reintento/rollback ciego. End invalida el token.
- El lector sigue sin escribir; la captura no tiene permiso de aplicación. El
  núcleo histórico conserva su filtro privado RW no ejecutable, separado del JNI.
- Prueba física: APPLIED32 y mantenimiento ALREADY_PATCHED, luego Login positivo
  en el intento con USB montado. Otra prueba terminó al desconectarse el USB;
  no se atribuye al parche/temporizador ese cierre.

Procedencia, tests y artefactos: [estado consolidado](launcher-app-status.md),
[registro causal](arm64-forever-connection-progress.md),
[plan de compatibilidad cerrado](../.agents/planning/done/arm64-server-identity-plan.md).

## Historial del núcleo y pruebas x86_64

## Qué está hecho

Crate Rust `crates/forever-patcher/` (workspace en la raíz del repo):

| Fichero | Contenido |
|---|---|
| `src/contract.rs` | `load_contract()` + validación del contrato de claves (sin claves hardcodeadas) |
| `src/memory.rs` | `trait MemoryAccess`, `SliceMemory` (tests), `LinuxProcessMemory` (`/proc/<pid>/maps` + `/proc/<pid>/mem`) |
| `src/patch.rs` | `scan_stores`, `patch_stores`, `ensure_once`, `ensure_loop` |
| `src/bin/forever-patch.rs` | CLI: `scan --pid` (solo informa) / `ensure --pid` (aplica y mantiene) |
| `src/bin/store_holder.rs` | **Solo test**: proceso que se autoconstruye un almacén válido y avisa cuando lo ven parcheado |

El **núcleo no usa `unsafe`** (requisito de US-002); `store_holder` lo usa para `mmap`
al ser un auxiliar de prueba.

Reglas implementadas: se localiza el almacén por la **clave ancla** (grupo 1, que
**nunca** se modifica), se valida el bloque completo (`entry_size`, ids en orden,
flags esperados, relleno con primer byte variable y dos últimos bytes cero) y se escriben **solo**
los 32 bytes de la clave del grupo objetivo. Idempotente.

## Verificación histórica

- `cargo test` → **12/12** (incluye: solo cambian los 32 bytes del grupo 8, el resto
  byte-idéntico incluido el ancla; ambos rellenos; rechazo de bloques manipulados;
  reaplicación tras recrearse el almacén; parseo del contrato real).
- `cargo clippy --all-targets` limpio; `cargo build --release` OK.
- **Prueba cross-proceso real** (`tests/process_integration.rs`): lanza `store_holder`
  como hijo, lo encuentra con `LinuxProcessMemory`, parchea, y el **hijo confirma**
  que ve la clave nueva (`PATCHED`); segunda pasada → idempotente. Corre **sin
  privilegios** (hijo = descendiente).

## Límite de la exploración histórica del cliente loose

En aquel ensayo host se recorrieron631MB con cero apariciones del ancla. Ese
resultado acotado no demuestra ausencia global del objeto ni permite atribuirla
solo a la instalación loose. No es el bloqueo vigente: C126/C127 posteriores
validan el bloque real ARM64 y la aplicación guardada con Login positivo.

## Hallazgos de entorno (importantes para Android)

- `kernel.yama.ptrace_scope=1` (por defecto): solo se pueden leer procesos
  **descendientes** propios.
- Procesos lanzados vía `sg`/`setgid` quedan **no dumpables**: ilegibles incluso para
  su dueño sin `CAP_SYS_PTRACE`.
- ⇒ Esto **valida la decisión D-L3** del launcher: si la app **lanza ella misma** el
  juego, el proceso es hijo y del mismo uid, así que puede parchearlo **sin root**.
  No hay que "buscar el proceso": lo tenemos.

## Cómo ejecutarlo

```bash
# solo informar (recomendado primero)
~/.cargo/bin/cargo run --release --manifest-path crates/forever-patcher/Cargo.toml \
  --bin forever-patch -- scan --pid <PID>

# aplicar y mantener
... --bin forever-patch -- ensure --pid <PID> [--interval-ms 2000]
```

Contrato: `--contract <ruta>` > `$WOWFOREVER_CONTRACT` > búsqueda de
`forever/contract.json` **hacia arriba** desde el cwd.

## Pendiente

1. Validar contra el `CertStore` real (bloqueado por la instalación completa).
2. Decidir el empaquetado para Android (nativo dentro del proceso de la app; la CLI
   es solo una herramienta de escritorio).
