//! Escaneo, validación, parcheo y mantenimiento del almacén de claves.
//!
//! Todo es puro sobre [`MemoryAccess`](crate::memory::MemoryAccess): sin
//! `unsafe`, sin syscalls directas y sin conocimiento del proceso real, de
//! modo que los tests ejercitan exactamente el mismo código que toca el juego.
//!
//! Formato del bloque (del contrato): `entry_count` entradas contiguas de
//! `entry_size` bytes; cada entrada es id u32 LE (1..12) + 32 bytes de clave
//! + 1 byte de flag + 3 bytes de relleno.

use crate::contract::{KEY_LEN, KeyStoreContract};
use crate::memory::MemoryAccess;
use std::collections::BTreeSet;
use std::io;
use std::time::Duration;

/// Tamaño de lectura por trozo al escanear regiones grandes (un heap puede
/// tener cientos de MB; leerlo de golpe agotaría la RAM del móvil).
const CHUNK_SIZE: u64 = 256 * 1024;

/// Almacén válido hallado en memoria.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FoundStore {
    /// Dirección absoluta del inicio del bloque (entrada del grupo 1).
    pub addr: u64,
    /// Cierto si la clave objetivo ya es `new_key` (no hay nada que escribir).
    pub already_patched: bool,
}

/// Resultado de aplicar el parche sobre un almacén hallado.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PatchOutcome {
    /// Dirección absoluta del inicio del bloque.
    pub addr: u64,
    /// Estado previo: cierto si ya estaba parcheado (segunda pasada idempotente).
    pub already_patched: bool,
}

/// Busca el almacén en las regiones candidatas. No escribe nada.
///
/// Cómo funciona: localiza la clave del grupo ancla con el alineamiento del
/// contrato, retrocede al inicio del bloque y lo valida entero (ids en orden,
/// flags, rellenos y las 11 claves restantes). En el grupo objetivo se admite
/// tanto la clave conocida como `new_key` para que un reescaneo tras parchear
/// siga reconociendo el bloque (idempotencia).
pub fn scan_stores(
    mem: &impl MemoryAccess,
    contract: &KeyStoreContract,
) -> io::Result<Vec<FoundStore>> {
    let regions = mem.regions()?;
    let anchor = contract.anchor_key();
    let align = contract.scan_alignment.max(1);
    let block_size = contract.block_size() as u64;
    let entry_size = contract.entry_size as u64;
    // Distancia desde el inicio del bloque hasta la clave ancla: cada entrada
    // previa ocupa `entry_size` y dentro de la propia entrada la clave va tras
    // el id de 4 bytes.
    let anchor_offset = 4 + (contract.anchor_group as u64 - 1) * entry_size;
    // Solape entre trozos: debe cubrir el bloque entero más la clave para no
    // partir ningún candidato por la frontera de lectura.
    let overlap = (block_size + KEY_LEN as u64).min(CHUNK_SIZE - 1);

    let mut seen = BTreeSet::new();
    let mut found = Vec::new();
    for region in regions.iter().filter(|r| !r.is_empty()) {
        let mut off = region.start;
        loop {
            let chunk_end = (off + CHUNK_SIZE).min(region.end);
            let mut chunk = vec![0u8; (chunk_end - off) as usize];
            // La región puede dejar de ser legible entre `regions()` y `read()`
            // (el cliente la libera o cambia sus permisos): se salta sin
            // abortar el resto del escaneo.
            if mem.read(off, &mut chunk).is_err() {
                break;
            }
            if chunk.len() >= KEY_LEN {
                for pos in 0..=(chunk.len() - KEY_LEN) {
                    let abs = off + pos as u64;
                    if !abs.is_multiple_of(align) {
                        continue;
                    }
                    if chunk[pos..pos + KEY_LEN] != anchor[..] {
                        continue;
                    }
                    let Some(block) = abs.checked_sub(anchor_offset) else {
                        continue;
                    };
                    if let Some(already) = validate_block(mem, block, contract) {
                        // El solape hace que un mismo bloque aparezca en dos trozos.
                        // Solo se deduplican observaciones validadas: una lectura
                        // transitoriamente fallida o un bloque aún inconsistente
                        // debe poder validarse de nuevo en el solape.
                        if seen.insert(block) {
                            found.push(FoundStore {
                                addr: block,
                                already_patched: already,
                            });
                        }
                    }
                }
            }
            if chunk_end == region.end {
                break;
            }
            off = chunk_end - overlap;
        }
    }
    Ok(found)
}

/// Valida el bloque que empieza en `block`: devuelve `Some(true)` si es válido
/// y ya está parcheado, `Some(false)` si es válido y pendiente, o `None` si no
/// es el almacén (incluido el caso de que la lectura falle por cruzar memoria
/// no mapeada: un candidato a caballo entre regiones no cuenta).
fn validate_block(
    mem: &impl MemoryAccess,
    block: u64,
    contract: &KeyStoreContract,
) -> Option<bool> {
    let mut buf = vec![0u8; contract.block_size()];
    mem.read(block, &mut buf).ok()?;
    let mut target_is_new = false;
    for i in 0..contract.entry_count {
        let base = i * contract.entry_size;
        let id = u32::from_le_bytes(buf[base..base + 4].try_into().ok()?);
        if id != (i as u32) + 1 {
            return None;
        }
        if buf[base + KEY_LEN + 4] != contract.expected_flags[i] {
            return None;
        }
        let pad: [u8; 3] = buf[base + KEY_LEN + 5..base + contract.entry_size]
            .try_into()
            .ok()?;
        if !contract.accepts_padding(&pad) {
            return None;
        }
        let key = &buf[base + 4..base + 4 + KEY_LEN];
        if i + 1 == contract.target_group {
            if key == contract.new_key {
                target_is_new = true;
            } else if key != contract.known_keys[i] {
                return None;
            }
        } else if key != contract.known_keys[i] {
            return None;
        }
    }
    Some(target_is_new)
}

/// Aplica `new_key` en cada almacén pendiente y devuelve uno por bloque.
///
/// Solo se escriben los 32 bytes de la clave del grupo objetivo: el flag, el
/// relleno y el resto de entradas (incluida el ancla) quedan intactos.
/// Es idempotente: si el bloque ya está parcheado no se escribe nada y se
/// informa `already_patched = true`.
pub fn patch_stores(
    mem: &impl MemoryAccess,
    contract: &KeyStoreContract,
) -> io::Result<Vec<PatchOutcome>> {
    let mut outcomes = Vec::new();
    for store in scan_stores(mem, contract)? {
        if !store.already_patched {
            mem.write(contract.target_key_addr(store.addr), &contract.new_key)?;
        }
        outcomes.push(PatchOutcome {
            addr: store.addr,
            already_patched: store.already_patched,
        });
    }
    Ok(outcomes)
}

/// Una pasada de "asegurar parche": escanea y reaplica si el cliente recreó el
/// almacén con la clave original (lo hace al reconectar o recargar).
pub fn ensure_once(
    mem: &impl MemoryAccess,
    contract: &KeyStoreContract,
) -> io::Result<Vec<PatchOutcome>> {
    patch_stores(mem, contract)
}

/// Bucle de "asegurar parche": repite pasadas cada `interval` hasta que
/// `should_stop` devuelva cierto.
///
/// Por qué una clausura y no señales: la librería no impone runtime ni manejo
/// de señales; el binario sale con Ctrl-C y el futuro launcher Android parará
/// el hilo con su propio flag (un `AtomicBool`). Se comprueba antes de la
/// primera pasada para que una cancelación inmediata no toque memoria.
pub fn ensure_loop(
    mem: &impl MemoryAccess,
    contract: &KeyStoreContract,
    interval: Duration,
    mut should_stop: impl FnMut() -> bool,
) -> io::Result<()> {
    loop {
        if should_stop() {
            return Ok(());
        }
        patch_stores(mem, contract)?;
        std::thread::sleep(interval);
    }
}
