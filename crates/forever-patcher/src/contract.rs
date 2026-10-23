//! Carga del contrato Forever (`forever/contract.json`, clave `key_store`).
//!
//! Por qué este módulo: las 12 claves y el resto de constantes no pueden vivir
//! como literales en el código porque derivarían en silencio si el upstream
//! publica otro build u otra clave; la única fuente de verdad es el JSON
//! (verificado por `scripts/contract-drift-check`).

use serde::Deserialize;
use std::{fmt, fs, path::Path};

/// Longitud en bytes de cada clave del almacén.
pub const KEY_LEN: usize = 32;

/// Longitud en bytes del relleno tras el flag de cada entrada.
pub const PADDING_LEN: usize = 3;

/// Fallo al cargar o validar el contrato.
#[derive(Debug)]
pub enum ContractError {
    /// No se pudo leer el fichero del contrato.
    Io(std::io::Error),
    /// El fichero no es JSON válido o no tiene la forma esperada.
    Parse(serde_json::Error),
    /// JSON válido pero con valores incoherentes (p. ej. 11 claves en vez de 12).
    Invalid(String),
}

impl fmt::Display for ContractError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "no se pudo leer el contrato: {e}"),
            Self::Parse(e) => write!(f, "contrato malformado: {e}"),
            Self::Invalid(msg) => write!(f, "contrato inválido: {msg}"),
        }
    }
}

impl std::error::Error for ContractError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            Self::Parse(e) => Some(e),
            Self::Invalid(_) => None,
        }
    }
}

impl From<std::io::Error> for ContractError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}

impl From<serde_json::Error> for ContractError {
    fn from(e: serde_json::Error) -> Self {
        Self::Parse(e)
    }
}

// --- Forma cruda del JSON (solo deserialización) ---

#[derive(Deserialize)]
struct RawContract {
    key_store: RawKeyStore,
}

#[derive(Deserialize)]
struct RawKeyStore {
    entry_size: usize,
    entry_count: usize,
    target_group: usize,
    anchor_group: usize,
    expected_flags: Vec<u8>,
    padding_after_flag: RawPadding,
    scan_alignment: u64,
    known_keys: Vec<String>,
    new_key: String,
}

#[derive(Deserialize)]
struct RawPadding {
    accepts: Vec<String>,
}

// --- Forma validada (la que usa el patcher) ---

/// Constantes del almacén de claves, cargadas del contrato y ya validadas.
///
/// Los grupos (`target_group`, `anchor_group`) son 1-based, como los ids que
/// guarda el cliente al inicio de cada entrada.
#[derive(Debug, Clone)]
pub struct KeyStoreContract {
    /// Tamaño en bytes de cada entrada (contrato: 40).
    pub entry_size: usize,
    /// Número de entradas contiguas del bloque (contrato: 12).
    pub entry_count: usize,
    /// Grupo cuya clave se sustituye por `new_key` (contrato: 8).
    pub target_group: usize,
    /// Grupo cuya clave sirve de ancla de búsqueda y nunca se modifica (contrato: 1).
    pub anchor_group: usize,
    /// Flag esperado por entrada, en orden de grupo 1..N.
    pub expected_flags: Vec<u8>,
    /// Muestras históricas de relleno tras el flag (Windows `7f0000`,
    /// Wine `000000`, ...). Se conservan como compatibilidad/documentación del
    /// contrato: NO son la regla de admisión. La admisión real es
    /// [`KeyStoreContract::accepts_padding`]: el primer byte de la cola varía
    /// entre builds (p. ej. `070000` observado con login OK), solo los dos
    /// últimos deben ser cero.
    pub accepted_paddings: Vec<[u8; PADDING_LEN]>,
    /// Las apariciones del ancla solo se consideran en direcciones múltiplo de esto.
    pub scan_alignment: u64,
    /// Las 12 claves conocidas, en orden de grupo 1..N.
    pub known_keys: Vec<[u8; KEY_LEN]>,
    /// Clave de reemplazo para el grupo objetivo.
    pub new_key: [u8; KEY_LEN],
}

impl KeyStoreContract {
    /// Regla única de admisión del relleno de 3 bytes tras el flag.
    ///
    /// Por qué no es la lista del JSON: el login x86_64 70450 OK demostró que
    /// el primer byte (offset 37 de la entrada) varía (`070000` en la
    /// observación x86_64 de wowserver) mientras los bytes 38/39 son cero. Exigir la
    /// tripleta exacta rechaza almacenes válidos. Solo se admite longitud
    /// exacta 3, primer byte libre y los dos últimos cero.
    pub fn accepts_padding(&self, padding: &[u8]) -> bool {
        padding.len() == PADDING_LEN && padding[1] == 0 && padding[2] == 0
    }

    /// Tamaño total en bytes del bloque contiguo (`entry_size * entry_count`).
    pub fn block_size(&self) -> usize {
        self.entry_size * self.entry_count
    }

    /// Clave del grupo ancla (la que se busca en memoria).
    pub fn anchor_key(&self) -> &[u8; KEY_LEN] {
        &self.known_keys[self.anchor_group - 1]
    }

    /// Dirección absoluta donde empieza la clave del grupo objetivo,
    /// dado el inicio del bloque.
    pub fn target_key_addr(&self, block_addr: u64) -> u64 {
        block_addr + ((self.target_group - 1) * self.entry_size) as u64 + 4
    }
}

/// Decodifica hex sin dependencias externas: solo se usa para 3 y 32 bytes,
/// así que no compensa añadir la crate `hex`.
fn decode_hex(s: &str) -> Result<Vec<u8>, String> {
    if !s.len().is_multiple_of(2) {
        return Err(format!("longitud impar: {s:?}"));
    }
    s.as_bytes()
        .chunks(2)
        .map(|pair| {
            let hi = hex_val(pair[0]).ok_or_else(|| format!("dígito inválido en {s:?}"))?;
            let lo = hex_val(pair[1]).ok_or_else(|| format!("dígito inválido en {s:?}"))?;
            Ok(hi << 4 | lo)
        })
        .collect()
}

fn hex_val(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

fn decode_key(s: &str) -> Result<[u8; KEY_LEN], String> {
    let bytes = decode_hex(s)?;
    bytes
        .try_into()
        .map_err(|_| format!("una clave debe tener 32 bytes: {s:?}"))
}

fn decode_padding(s: &str) -> Result<[u8; PADDING_LEN], String> {
    let bytes = decode_hex(s)?;
    bytes
        .try_into()
        .map_err(|_| format!("el relleno debe tener 3 bytes: {s:?}"))
}

/// Lee y valida el contrato desde `path`.
///
/// Falla si falta alguna clave, si alguna no tiene 32 bytes, si los grupos
/// quedan fuera de rango o si `expected_flags` no tiene una entrada por grupo:
/// mejor no arrancar que parchear la memoria equivocada.
pub fn load_contract(path: impl AsRef<Path>) -> Result<KeyStoreContract, ContractError> {
    let text = fs::read_to_string(path.as_ref())?;
    let raw: RawContract = serde_json::from_str(&text)?;
    let k = raw.key_store;

    if k.entry_count == 0 || k.entry_size < 4 + KEY_LEN + 1 + PADDING_LEN {
        return Err(ContractError::Invalid(format!(
            "entry_size/entry_count incoherentes: {} x {}",
            k.entry_size, k.entry_count
        )));
    }
    if k.target_group == 0 || k.target_group > k.entry_count {
        return Err(ContractError::Invalid(format!(
            "target_group {} fuera de 1..={}",
            k.target_group, k.entry_count
        )));
    }
    if k.anchor_group == 0 || k.anchor_group > k.entry_count {
        return Err(ContractError::Invalid(format!(
            "anchor_group {} fuera de 1..={}",
            k.anchor_group, k.entry_count
        )));
    }
    if k.expected_flags.len() != k.entry_count {
        return Err(ContractError::Invalid(format!(
            "expected_flags tiene {} valores para {} entradas",
            k.expected_flags.len(),
            k.entry_count
        )));
    }
    if k.known_keys.len() != k.entry_count {
        return Err(ContractError::Invalid(format!(
            "known_keys tiene {} claves para {} entradas",
            k.known_keys.len(),
            k.entry_count
        )));
    }
    if k.scan_alignment == 0 {
        return Err(ContractError::Invalid(
            "scan_alignment debe ser >= 1".to_string(),
        ));
    }
    if k.padding_after_flag.accepts.is_empty() {
        return Err(ContractError::Invalid(
            "padding_after_flag.accepts está vacío".to_string(),
        ));
    }

    let mut known_keys = Vec::with_capacity(k.entry_count);
    for hex in &k.known_keys {
        known_keys.push(decode_key(hex).map_err(ContractError::Invalid)?);
    }
    let mut accepted_paddings = Vec::with_capacity(k.padding_after_flag.accepts.len());
    for hex in &k.padding_after_flag.accepts {
        accepted_paddings.push(decode_padding(hex).map_err(ContractError::Invalid)?);
    }
    let new_key = decode_key(&k.new_key).map_err(ContractError::Invalid)?;

    Ok(KeyStoreContract {
        entry_size: k.entry_size,
        entry_count: k.entry_count,
        target_group: k.target_group,
        anchor_group: k.anchor_group,
        expected_flags: k.expected_flags,
        accepted_paddings,
        scan_alignment: k.scan_alignment,
        known_keys,
        new_key,
    })
}
