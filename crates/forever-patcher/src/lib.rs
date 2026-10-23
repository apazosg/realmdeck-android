//! `forever-patcher`: localiza y sustituye en memoria el almacén de claves
//! del cliente WoW Classic Beta (parche Forever, Fase 3).
//!
//! El launcher lanzará el juego, así que lector y objetivo comparten uid y no
//! hace falta root ni en Linux ni en Android.

pub mod contract;
pub mod memory;
pub mod patch;

pub use contract::{KEY_LEN, KeyStoreContract, load_contract};
pub use memory::{LinuxProcessMemory, MemoryAccess, Region, SliceMemory};
pub use patch::{FoundStore, PatchOutcome, ensure_loop, ensure_once, patch_stores, scan_stores};
