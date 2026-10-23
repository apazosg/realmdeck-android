//! Acceso a memoria de procesos: el trait, el doble de test y el acceso real.
//!
//! Por qué un trait: el escaneo/validación/parcheo debe probarse sin
//! dispositivo ni proceso real, así que toda la lógica trabaja sobre
//! [`MemoryAccess`] y los tests inyectan [`SliceMemory`]. En producción
//! (Linux/Android, mismo uid porque el launcher lanza el juego) se usa
//! [`LinuxProcessMemory`], sin `unsafe` en ningún caso.

use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write as _};

/// Región contigua de memoria virtual: `[start, end)`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Region {
    /// Dirección inicial (inclusiva).
    pub start: u64,
    /// Dirección final (exclusiva).
    pub end: u64,
}

impl Region {
    /// Longitud en bytes (0 si el rango está vacío o invertido).
    pub fn len(&self) -> u64 {
        self.end.saturating_sub(self.start)
    }

    /// Cierto si no contiene ningún byte.
    pub fn is_empty(&self) -> bool {
        self.end <= self.start
    }
}

/// Operaciones mínimas que el patcher necesita sobre la memoria ajena.
///
/// `read` debe comportarse como lectura exacta: o rellena `buf` entero o
/// devuelve error (nunca éxitos parciales silenciosos).
pub trait MemoryAccess {
    /// Regiones candidatas al escaneo (solo memoria privada, escribible y no
    /// ejecutable; ver [`LinuxProcessMemory`]).
    fn regions(&self) -> io::Result<Vec<Region>>;
    /// Lee `buf.len()` bytes desde la dirección absoluta `addr`.
    fn read(&self, addr: u64, buf: &mut [u8]) -> io::Result<()>;
    /// Escribe `data` en la dirección absoluta `addr`.
    fn write(&self, addr: u64, data: &[u8]) -> io::Result<()>;
}

/// Memoria simulada sobre un `Vec<u8>` con una dirección base.
///
/// Es el doble de test: `addr` absoluta se traduce a `addr - base` dentro del
/// vector. Lleva `Mutex` (y no `RefCell`) para que los métodos reciban `&self`
/// como exige el trait sin renunciar a `Sync`.
#[derive(Debug)]
pub struct SliceMemory {
    base: u64,
    bytes: std::sync::Mutex<Vec<u8>>,
}

impl SliceMemory {
    /// Crea la memoria simulada: `bytes[i]` vive en la dirección `base + i`.
    pub fn new(base: u64, bytes: Vec<u8>) -> Self {
        Self {
            base,
            bytes: std::sync::Mutex::new(bytes),
        }
    }

    /// Dirección base (la absoluta de `bytes[0]`).
    pub fn base(&self) -> u64 {
        self.base
    }

    /// Copia del contenido actual (para aserciones byte a byte en tests).
    pub fn snapshot(&self) -> Vec<u8> {
        self.bytes.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    fn offset(&self, addr: u64, len: usize) -> io::Result<usize> {
        let off = addr
            .checked_sub(self.base)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "dirección bajo la base"))?;
        let end = off
            .checked_add(len as u64)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "rango desbordado"))?;
        let guard = self.bytes.lock().unwrap_or_else(|e| e.into_inner());
        if end > guard.len() as u64 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "lectura/escritura fuera del buffer",
            ));
        }
        Ok(off as usize)
    }
}

impl MemoryAccess for SliceMemory {
    fn regions(&self) -> io::Result<Vec<Region>> {
        let len = self.bytes.lock().unwrap_or_else(|e| e.into_inner()).len() as u64;
        Ok(vec![Region {
            start: self.base,
            end: self.base + len,
        }])
    }

    fn read(&self, addr: u64, buf: &mut [u8]) -> io::Result<()> {
        let off = self.offset(addr, buf.len())?;
        let guard = self.bytes.lock().unwrap_or_else(|e| e.into_inner());
        buf.copy_from_slice(&guard[off..off + buf.len()]);
        Ok(())
    }

    fn write(&self, addr: u64, data: &[u8]) -> io::Result<()> {
        let off = self.offset(addr, data.len())?;
        let mut guard = self.bytes.lock().unwrap_or_else(|e| e.into_inner());
        guard[off..off + data.len()].copy_from_slice(data);
        Ok(())
    }
}

/// Memoria de un proceso Linux/Android vía `/proc/<pid>/maps` y `/proc/<pid>/mem`.
///
/// Funciona sin root cuando lector y objetivo comparten uid, que es el caso
/// del futuro APK (el launcher lanza el juego). Los errores de permiso o de
/// proceso inexistente se propagan como `io::Error` para que el binario los
/// informe sin pánicos.
#[derive(Debug, Clone, Copy)]
pub struct LinuxProcessMemory {
    pid: u32,
}

impl LinuxProcessMemory {
    /// Acceso a la memoria del proceso `pid`.
    pub fn new(pid: u32) -> Self {
        Self { pid }
    }

    /// Pid objetivo.
    pub fn pid(&self) -> u32 {
        self.pid
    }

    fn maps_path(&self) -> String {
        format!("/proc/{}/maps", self.pid)
    }

    fn mem_path(&self) -> String {
        format!("/proc/{}/mem", self.pid)
    }
}

impl MemoryAccess for LinuxProcessMemory {
    fn regions(&self) -> io::Result<Vec<Region>> {
        let text = std::fs::read_to_string(self.maps_path())?;
        Ok(parse_maps(&text))
    }

    fn read(&self, addr: u64, buf: &mut [u8]) -> io::Result<()> {
        // `pread` no está en std sin libc, así que se emula con seek + read_exact
        // sobre un fd recién abierto (cada llamada abre el suyo: evita carreras
        // con el offset compartido si varios hilos leen a la vez).
        let mut f = File::open(self.mem_path())?;
        f.seek(SeekFrom::Start(addr))?;
        f.read_exact(buf)
    }

    fn write(&self, addr: u64, data: &[u8]) -> io::Result<()> {
        let mut f = OpenOptions::new().write(true).open(self.mem_path())?;
        f.seek(SeekFrom::Start(addr))?;
        f.write_all(data)
    }
}

/// Filtra `/proc/<pid>/maps` quedándose solo con memoria privada (`p`),
/// escribible (`w`) y no ejecutable: el equivalente a `rw-p`.
///
/// Por qué este filtro: el almacén de claves es un `malloc` del cliente, así
/// que vive en heap/anónimo; excluir código y páginas ejecutables evita falsos
/// positivos y, sobre todo, escribir donde no se debe. Además se descartan los
/// mapeos a fichero (ruta que empieza por `/`): nunca se parchea un `.so`,
/// el ejecutable ni datos respaldados por fichero. Se conservan las regiones
/// anónimas y pseudo-mapeos como `[heap]` o `[stack]`.
pub(crate) fn parse_maps(text: &str) -> Vec<Region> {
    let mut out = Vec::new();
    for line in text.lines() {
        let mut cols = line.split_whitespace();
        let (Some(range), Some(perms)) = (cols.next(), cols.next()) else {
            continue;
        };
        // Columnas restantes: offset, dev, inode y (opcional) ruta.
        let mut rest = cols.skip(3);
        if let Some(path) = rest.next() {
            // Los mapeos a fichero empiezan por '/'; `[heap]`, `[stack]`,
            // `[anon:*]` y la cadena vacía (anónimo) sí se aceptan.
            if path.starts_with('/') {
                continue;
            }
        }
        let p: Vec<char> = perms.chars().collect();
        if p.len() < 4 || p[0] != 'r' || p[1] != 'w' || p[2] != '-' || p[3] != 'p' {
            continue;
        }
        let Some((start_hex, end_hex)) = range.split_once('-') else {
            continue;
        };
        let (Ok(start), Ok(end)) = (
            u64::from_str_radix(start_hex, 16),
            u64::from_str_radix(end_hex, 16),
        ) else {
            continue;
        };
        if end > start {
            out.push(Region { start, end });
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_solo_rw_p_privado_y_sin_ficheros() {
        let text = "\
00400000-0040b000 r--p 00000000 08:01 1234                              /usr/bin/wow\n\
7f000000-7f010000 rw-p 00000000 00:00 0                                  [heap]\n\
7f010000-7f020000 rw-p 00000000 00:00 0 \n\
7f020000-7f030000 rwxp 00000000 00:00 0 \n\
7f030000-7f040000 r--p 00000000 00:00 0 \n\
7f040000-7f050000 rw-p 00000000 08:01 999  /tmp/datos.bin\n\
7f050000-7f060000 rw-p 00000000 00:00 0                                  [stack]\n\
7f060000-7f070000 r-xp 00000000 08:01 555  /lib/x.so\n";
        let regions = parse_maps(text);
        assert_eq!(
            regions,
            vec![
                Region {
                    start: 0x7f00_0000,
                    end: 0x7f01_0000
                },
                Region {
                    start: 0x7f01_0000,
                    end: 0x7f02_0000
                },
                Region {
                    start: 0x7f05_0000,
                    end: 0x7f06_0000
                },
            ]
        );
    }
}
