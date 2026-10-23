//! Tests contra el contrato REAL (`forever/contract.json`): sin red, sin
//! dispositivo. Todos construyen un almacén sintético válido con las 12 claves
//! reales sobre [`SliceMemory`] y comprueban escaneo, parcheo y rechazos.

use forever_patcher::{
    KeyStoreContract, MemoryAccess, SliceMemory, ensure_once, load_contract, patch_stores,
    scan_stores,
};

/// Base alineada a 4 (el contrato exige `scan_alignment = 4`).
const BASE: u64 = 0x1_0000;
/// Relleno previo al bloque; múltiplo de 4 para no romper el alineamiento.
const PREFIX: usize = 0x40;

fn real_contract() -> KeyStoreContract {
    let dir = std::env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR");
    let path = std::path::Path::new(&dir).join("../../forever/contract.json");
    load_contract(&path).expect("el contrato real debe parsearse")
}

/// Construye un bloque válido. `target_new` elige la clave del grupo objetivo:
/// conocida (pendiente) o `new_key` (ya parcheado).
fn build_store(c: &KeyStoreContract, padding: [u8; 3], target_new: bool) -> Vec<u8> {
    let mut buf = Vec::with_capacity(c.block_size());
    for i in 0..c.entry_count {
        buf.extend_from_slice(&((i + 1) as u32).to_le_bytes());
        let key = if i + 1 == c.target_group && target_new {
            &c.new_key
        } else {
            &c.known_keys[i]
        };
        buf.extend_from_slice(key);
        buf.push(c.expected_flags[i]);
        buf.extend_from_slice(&padding);
    }
    buf
}

fn mem_with_store(c: &KeyStoreContract, padding: [u8; 3], target_new: bool) -> (SliceMemory, u64) {
    let mut bytes = vec![0xAAu8; PREFIX];
    let addr = BASE + PREFIX as u64;
    bytes.extend_from_slice(&build_store(c, padding, target_new));
    bytes.extend_from_slice(&[0x55u8; 64]);
    (SliceMemory::new(BASE, bytes), addr)
}

fn mem_with_store_mixed(
    c: &KeyStoreContract,
    paddings: &[[u8; 3]],
    target_new: bool,
) -> (SliceMemory, u64) {
    assert_eq!(paddings.len(), c.entry_count);
    let (mem, addr) = mem_with_store(c, [0; 3], target_new);
    let mut bytes = mem.snapshot();
    for (index, padding) in paddings.iter().enumerate() {
        let offset = PREFIX + index * c.entry_size + 37;
        bytes[offset..offset + 3].copy_from_slice(padding);
    }
    (SliceMemory::new(BASE, bytes), addr)
}

#[test]
fn contrato_real_tiene_12_claves_de_32_bytes() {
    let c = real_contract();
    assert_eq!(c.entry_size, 40);
    assert_eq!(c.entry_count, 12);
    assert_eq!(c.target_group, 8);
    assert_eq!(c.anchor_group, 1);
    assert_eq!(c.known_keys.len(), 12);
    assert_eq!(c.expected_flags.len(), 12);
    // `known_keys` y `new_key` son `[u8; 32]` por tipo; aquí se comprueba que
    // la clave nueva difiere de la conocida del grupo 8 (si no, el parche
    // sería un no-op indistinguible).
    assert_ne!(c.new_key, c.known_keys[c.target_group - 1]);
    assert!(!c.accepted_paddings.is_empty());
}

#[test]
fn scan_encuentra_almacen_valido_y_no_escribe() {
    let c = real_contract();
    let (mem, addr) = mem_with_store(&c, c.accepted_paddings[0], false);
    let before = mem.snapshot();
    let found = scan_stores(&mem, &c).expect("scan");
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].addr, addr);
    assert!(!found[0].already_patched);
    // `scan` no debe escribir: el buffer queda byte-idéntico.
    assert_eq!(mem.snapshot(), before);
}

#[test]
fn acepta_relleno_windows_y_wine() {
    let c = real_contract();
    assert!(
        c.accepted_paddings.len() >= 2,
        "el contrato debe aceptar al menos dos variantes de relleno"
    );
    for padding in &c.accepted_paddings {
        let (mem, addr) = mem_with_store(&c, *padding, false);
        let found = scan_stores(&mem, &c).expect("scan");
        assert_eq!(found.len(), 1, "relleno {padding:02X?} debe validar");
        assert_eq!(found[0].addr, addr);
    }
}

#[test]
fn patch_solo_modifica_los_32_bytes_del_grupo_8() {
    let c = real_contract();
    let (mem, addr) = mem_with_store(&c, c.accepted_paddings[0], false);
    let before = mem.snapshot();

    let out = patch_stores(&mem, &c).expect("patch");
    assert_eq!(out.len(), 1);
    assert_eq!(out[0].addr, addr);
    assert!(!out[0].already_patched);

    let after = mem.snapshot();
    let key_off = (addr - BASE) as usize + (c.target_group - 1) * c.entry_size + 4;
    // Exactamente 32 bytes difieren, y son la clave nueva en su sitio.
    let diffs: Vec<usize> = before
        .iter()
        .zip(after.iter())
        .enumerate()
        .filter(|(_, (a, b))| a != b)
        .map(|(i, _)| i)
        .collect();
    assert_eq!(diffs.len(), 32);
    assert_eq!(diffs, (key_off..key_off + 32).collect::<Vec<_>>());
    assert_eq!(&after[key_off..key_off + 32], &c.new_key);
    // Flag y relleno de la entrada objetivo, intactos.
    assert_eq!(after[key_off + 32], c.expected_flags[c.target_group - 1]);
    assert_eq!(
        &after[key_off + 33..key_off + 36],
        &c.accepted_paddings[0]
    );
}

#[test]
fn idempotencia_segunda_pasada_sin_cambios() {
    let c = real_contract();
    let (mem, addr) = mem_with_store(&c, c.accepted_paddings[1], false);

    let first = patch_stores(&mem, &c).expect("primer patch");
    assert_eq!(first.len(), 1);
    assert!(!first[0].already_patched);

    let snapshot = mem.snapshot();
    let second = patch_stores(&mem, &c).expect("segundo patch");
    assert_eq!(second.len(), 1);
    assert_eq!(second[0].addr, addr);
    assert!(second[0].already_patched, "debe detectarse ya parcheado");
    assert_eq!(mem.snapshot(), snapshot, "la segunda pasada no escribe nada");

    // Un bloque construido ya parcheado también se reconoce como tal.
    let (mem2, addr2) = mem_with_store(&c, c.accepted_paddings[1], true);
    let found = scan_stores(&mem2, &c).expect("scan");
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].addr, addr2);
    assert!(found[0].already_patched);
}

#[test]
fn rechazo_flag_incorrecto_id_desordenado_y_clave_manipulada() {
    let c = real_contract();

    // Flag incorrecto en la entrada 1.
    let (mem, _) = mem_with_store(&c, c.accepted_paddings[0], false);
    let flag_addr = BASE + PREFIX as u64 + 36;
    let mut bad = mem.snapshot();
    bad[(flag_addr - BASE) as usize] ^= 0x01;
    let mem = SliceMemory::new(BASE, bad);
    assert!(scan_stores(&mem, &c).expect("scan").is_empty());

    // Id fuera de orden (entrada 2 dice 3).
    let (mem, _) = mem_with_store(&c, c.accepted_paddings[0], false);
    let mut bad = mem.snapshot();
    bad[PREFIX + c.entry_size] = 3; // byte bajo del u32 LE
    let mem = SliceMemory::new(BASE, bad);
    assert!(scan_stores(&mem, &c).expect("scan").is_empty());

    // Clave conocida manipulada (grupo 5, primer byte).
    let (mem, _) = mem_with_store(&c, c.accepted_paddings[0], false);
    let mut bad = mem.snapshot();
    bad[PREFIX + 4 * c.entry_size + 4] ^= 0xFF;
    let mem = SliceMemory::new(BASE, bad);
    assert!(scan_stores(&mem, &c).expect("scan").is_empty());

    // Relleno no aceptado (grupo 1, segundo byte de la cola no nulo).
    let (mem, _) = mem_with_store(&c, c.accepted_paddings[0], false);
    let mut bad = mem.snapshot();
    bad[PREFIX + 38] = 0x42;
    let mem = SliceMemory::new(BASE, bad);
    assert!(scan_stores(&mem, &c).expect("scan").is_empty());
}

#[test]
fn ancla_del_grupo_1_nunca_se_modifica() {
    let c = real_contract();
    let (mem, addr) = mem_with_store(&c, c.accepted_paddings[0], false);
    patch_stores(&mem, &c).expect("patch");
    let after = mem.snapshot();
    let anchor_off = (addr - BASE) as usize + (c.anchor_group - 1) * c.entry_size + 4;
    assert_eq!(
        &after[anchor_off..anchor_off + 32],
        &c.known_keys[c.anchor_group - 1]
    );
}

#[test]
fn ensure_reaplica_si_el_cliente_recrea_el_almacen() {
    let c = real_contract();
    let (mem, addr) = mem_with_store(&c, c.accepted_paddings[0], false);
    patch_stores(&mem, &c).expect("patch inicial");

    // El cliente recrea el almacén con la clave original.
    let key_addr = c.target_key_addr(addr);
    mem.write(key_addr, &c.known_keys[c.target_group - 1])
        .expect("simular recreación");

    let out = ensure_once(&mem, &c).expect("ensure");
    assert_eq!(out.len(), 1);
    assert!(!out[0].already_patched, "debió detectar el almacén recreado");

    let mut key = [0u8; 32];
    mem.read(key_addr, &mut key).expect("releer clave");
    assert_eq!(key, c.new_key, "la clave vuelve a ser la nueva");
}

#[test]
fn acepta_primer_byte_de_cola_libre_en_entradas_mezcladas() {
    // Evidencia x86_64 (login 70450 OK): el byte 37 de cada entrada varía
    // (`070000` observado en wowserver) mientras los bytes 38/39 son cero. El
    // escáner real debe aceptar esa mezcla, incluidas variantes nunca vistas
    // como `ff0000` o `420000`, junto a las históricas del contrato.
    let c = real_contract();
    let mut paddings = [[0u8; 3]; 12];
    let observed: [[u8; 3]; 6] = [
        [0x07, 0x00, 0x00],
        [0xff, 0x00, 0x00],
        [0x42, 0x00, 0x00],
        [0x00, 0x00, 0x00],
        [0x7f, 0x00, 0x00],
        [0x07, 0x00, 0x00],
    ];
    for (i, slot) in paddings.iter_mut().enumerate() {
        *slot = observed[i % observed.len()];
    }
    let (mem, addr) = mem_with_store_mixed(&c, &paddings, false);
    let found = scan_stores(&mem, &c).expect("scan");
    assert_eq!(found.len(), 1, "la mezcla con primer byte libre debe validar");
    assert_eq!(found[0].addr, addr);
    assert!(!found[0].already_patched);

    // El parche sigue tocando solo los 32 bytes del grupo 8 y es idempotente.
    let before = mem.snapshot();
    let out = patch_stores(&mem, &c).expect("patch");
    assert_eq!(out.len(), 1);
    assert!(!out[0].already_patched);
    let after = mem.snapshot();
    let key_offset = (c.target_key_addr(addr) - BASE) as usize;
    assert_eq!(&after[..key_offset], &before[..key_offset]);
    assert_eq!(&after[key_offset..key_offset + 32], &c.new_key);
    assert_eq!(&after[key_offset + 32..], &before[key_offset + 32..]);
    let second = patch_stores(&mem, &c).expect("segundo patch");
    assert_eq!(second.len(), 1);
    assert!(second[0].already_patched, "la segunda pasada no reescribe");
    assert_eq!(mem.snapshot(), after);
}

#[test]
fn rechaza_cola_con_segundo_o_tercer_byte_no_nulo() {
    // Solo los bytes 38/39 invalidan el relleno; el primero es libre.
    let c = real_contract();
    let bad_tails: [[u8; 3]; 4] = [
        [0x07, 0x01, 0x00],
        [0x07, 0x00, 0x01],
        [0x00, 0x01, 0x00],
        [0x00, 0x00, 0x01],
    ];
    for tail in bad_tails {
        let mut paddings = [[0x07u8, 0x00, 0x00]; 12];
        paddings[5] = tail;
        let (mem, _) = mem_with_store_mixed(&c, &paddings, false);
        assert!(
            scan_stores(&mem, &c).expect("scan").is_empty(),
            "cola {tail:02X?} con byte 38/39 no nulo debe rechazar el bloque"
        );
    }
}
