//! Regresiones del escaneo cuando un almacén cruza una frontera de chunk.
//!
//! La memoria es sintética y no permite escrituras: estos tests solo ejercitan
//! la detección/validación, nunca un proceso real.

use forever_patcher::{KeyStoreContract, MemoryAccess, Region, load_contract, scan_stores};
use std::io;
use std::sync::Mutex;

const BASE: u64 = 0x1000_0000;
const CHUNK_SIZE: u64 = 256 * 1024;
const BLOCK_OFFSET_FROM_BOUNDARY: u64 = 200;

#[derive(Clone, Copy)]
enum ValidationBehavior {
    Stable,
    IoErrorFirst,
    InvalidIdFirst,
    AlwaysInvalid,
}

struct SyntheticMemory {
    base: u64,
    bytes: Vec<u8>,
    block: u64,
    block_size: usize,
    behavior: ValidationBehavior,
    block_reads: Mutex<usize>,
}

impl SyntheticMemory {
    fn new(
        bytes: Vec<u8>,
        block: u64,
        block_size: usize,
        behavior: ValidationBehavior,
    ) -> Self {
        Self {
            base: BASE,
            bytes,
            block,
            block_size,
            behavior,
            block_reads: Mutex::new(0),
        }
    }

    fn block_reads(&self) -> usize {
        *self.block_reads.lock().unwrap()
    }
}

impl MemoryAccess for SyntheticMemory {
    fn regions(&self) -> io::Result<Vec<Region>> {
        Ok(vec![Region {
            start: self.base,
            end: self.base + self.bytes.len() as u64,
        }])
    }

    fn read(&self, addr: u64, buf: &mut [u8]) -> io::Result<()> {
        let offset = addr
            .checked_sub(self.base)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "address below base"))?
            as usize;
        let end = offset
            .checked_add(buf.len())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "read overflow"))?;
        if end > self.bytes.len() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "read outside synthetic region",
            ));
        }

        buf.copy_from_slice(&self.bytes[offset..end]);
        if addr == self.block && buf.len() == self.block_size {
            let mut reads = self.block_reads.lock().unwrap();
            *reads += 1;
            match (self.behavior, *reads) {
                (ValidationBehavior::IoErrorFirst, 1) => {
                    return Err(io::Error::new(io::ErrorKind::Interrupted, "transient read"));
                }
                (ValidationBehavior::InvalidIdFirst, 1)
                | (ValidationBehavior::AlwaysInvalid, _) => {
                    buf[0..4].copy_from_slice(&99u32.to_le_bytes());
                }
                _ => {}
            }
        }
        Ok(())
    }

    fn write(&self, _addr: u64, _data: &[u8]) -> io::Result<()> {
        Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "synthetic memory is read-only",
        ))
    }
}

fn real_contract() -> KeyStoreContract {
    let dir = std::env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR");
    let path = std::path::Path::new(&dir).join("../../forever/contract.json");
    load_contract(path).expect("the real contract must parse")
}

fn build_store(c: &KeyStoreContract, padding: [u8; 3]) -> Vec<u8> {
    let mut buf = Vec::with_capacity(c.block_size());
    for i in 0..c.entry_count {
        buf.extend_from_slice(&((i + 1) as u32).to_le_bytes());
        buf.extend_from_slice(&c.known_keys[i]);
        buf.push(c.expected_flags[i]);
        buf.extend_from_slice(&padding);
    }
    buf
}

fn memory_with_store(
    c: &KeyStoreContract,
    behavior: ValidationBehavior,
) -> (SyntheticMemory, u64) {
    assert_eq!(c.block_size(), 480);
    let block = BASE + CHUNK_SIZE - BLOCK_OFFSET_FROM_BOUNDARY;
    let mut bytes = vec![0xAA; (CHUNK_SIZE + 1024) as usize];
    let offset = (block - BASE) as usize;
    bytes[offset..offset + c.block_size()]
        .copy_from_slice(&build_store(c, c.accepted_paddings[0]));
    (
        SyntheticMemory::new(bytes, block, c.block_size(), behavior),
        block,
    )
}

#[test]
fn stable_overlap_is_deduplicated_after_validation() {
    let c = real_contract();
    let (mem, block) = memory_with_store(&c, ValidationBehavior::Stable);

    let found = scan_stores(&mem, &c).expect("scan");

    assert_eq!(found.len(), 1);
    assert_eq!(found[0].addr, block);
    assert!(!found[0].already_patched);
    assert_eq!(mem.block_reads(), 2, "both overlapping observations validate");
}

#[test]
fn first_validation_io_error_recovers_on_overlap() {
    let c = real_contract();
    let (mem, block) = memory_with_store(&c, ValidationBehavior::IoErrorFirst);

    let found = scan_stores(&mem, &c).expect("scan");

    assert_eq!(found.len(), 1);
    assert_eq!(found[0].addr, block);
    assert_eq!(mem.block_reads(), 2);
}

#[test]
fn transient_invalid_id_recovers_on_overlap() {
    let c = real_contract();
    let (mem, block) = memory_with_store(&c, ValidationBehavior::InvalidIdFirst);

    let found = scan_stores(&mem, &c).expect("scan");

    assert_eq!(found.len(), 1);
    assert_eq!(found[0].addr, block);
    assert_eq!(mem.block_reads(), 2);
}

#[test]
fn permanently_invalid_block_is_never_accepted() {
    let c = real_contract();
    let (mem, _) = memory_with_store(&c, ValidationBehavior::AlwaysInvalid);

    let found = scan_stores(&mem, &c).expect("scan");

    assert!(found.is_empty());
    assert_eq!(mem.block_reads(), 2, "failed observations remain retryable");
}

#[test]
fn keys_flags_and_padding_still_require_contract_values() {
    let c = real_contract();
    let block = BASE + CHUNK_SIZE - BLOCK_OFFSET_FROM_BOUNDARY;
    let padding = c.accepted_paddings[0];

    for (entry, field, mask) in [(4, 4, 0xFF), (1, 36, 0x01), (2, 39, 0x01)] {
        let mut bytes = vec![0xAA; (CHUNK_SIZE + 1024) as usize];
        let offset = (block - BASE) as usize;
        bytes[offset..offset + c.block_size()]
            .copy_from_slice(&build_store(&c, padding));
        let entry_offset = offset + entry * c.entry_size;
        bytes[entry_offset + field] ^= mask;
        let mem = SyntheticMemory::new(
            bytes,
            block,
            c.block_size(),
            ValidationBehavior::Stable,
        );

        assert!(scan_stores(&mem, &c).expect("scan").is_empty());
    }
}
