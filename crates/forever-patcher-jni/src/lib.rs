//! Puente JNI entre la app Android (`com.wowforever.launcher.NativeBridge`) y el
//! núcleo `forever-patcher`.
//!
//! Todo el `unsafe` necesario para el FFI vive en este crate; el núcleo
//! (`contract`, `memory`, `patch`) no contiene `unsafe` y no se toca desde aquí,
//! solo se usa su API pública.
//!
//! Funciones expuestas (fase 5, puntos 1-3: solo comprobación de carga):
//! - `nativeVersion() -> String`: demuestra que la biblioteca carga y enlaza.
//! - `contractSummaryJson(json: String) -> String`: valida un contrato con el
//!   cargador real del núcleo y devuelve un resumen; demuestra que el núcleo es
//!   funcional desde JNI.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong};
// Para `LinuxProcessMemory::read/regions` en el diagnóstico (trait del núcleo).
use forever_patcher::MemoryAccess as _;
use serde_json::{Map, Value, json};
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use std::os::unix::fs::MetadataExt;

mod signal_trace;
mod functional_session;

/// Versión del crate JNI (la del núcleo va en su propio `Cargo.toml`; aquí solo
/// se demuestra el enlace llamando a su API y a sus constantes).
const CORE_WITNESS: usize = forever_patcher::KEY_LEN;

/// `com.wowforever.launcher.NativeBridge.nativeVersion`
#[no_mangle]
pub unsafe extern "system" fn Java_com_wowforever_launcher_NativeBridge_nativeVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let text = format!(
        "forever-patcher-jni {} (core KEY_LEN={})",
        env!("CARGO_PKG_VERSION"),
        CORE_WITNESS,
    );
    env.new_string(text)
        .expect("no se pudo crear el String JNI de versión")
}

/// Resume un contrato Forever a partir de su JSON (el mismo que valida
/// `scripts/contract-drift-check`).
///
/// El núcleo solo sabe cargar desde fichero (`load_contract`), así que el JSON
/// recibido se vuelca a un temporal y se valida con el cargador real: cualquier
/// deriva del formato falla aquí igual que en el host.
pub fn summarize_contract_json(json: &str) -> Result<String, String> {
    // Único por llamada: los tests corren en hilos del mismo proceso y antes
    // compartían un único temporal por PID (carrera lectura/borrado).
    static NEXT_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let id = NEXT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let mut path = std::env::temp_dir();
    path.push(format!(
        "forever-contract-{}-{}.json",
        std::process::id(),
        id,
    ));
    std::fs::write(&path, json).map_err(|e| format!("no se pudo escribir temporal: {e}"))?;
    let result = (|| {
        let c = forever_patcher::load_contract(&path).map_err(|e| e.to_string())?;
        Ok(format!(
            "entries={} entry_size={} target_group={} anchor_group={} block_size={}",
            c.entry_count,
            c.entry_size,
            c.target_group,
            c.anchor_group,
            c.block_size(),
        ))
    })();
    let _ = std::fs::remove_file(&path);
    result
}

/// `com.wowforever.launcher.NativeBridge.contractSummaryJson`
#[no_mangle]
pub unsafe extern "system" fn Java_com_wowforever_launcher_NativeBridge_contractSummaryJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
) -> JString<'local> {
    let text: String = env
        .get_string(&json)
        .map(|s| s.into())
        .unwrap_or_else(|_| String::from("{\"error\":\"json JNI ilegible\"}"));
    let summary = summarize_contract_json(&text)
        .unwrap_or_else(|e| format!("contrato inválido: {e}"));
    env.new_string(summary)
        .expect("no se pudo crear el String JNI de resumen")
}

// --- Punto 4 (Fase 5, milestone D-L3): autotest cross-proceso real --------
//
// El padre crea un hijo con `fork()`; el hijo publica un almacén sintético
// válido en una página `mmap` anónima RW y el padre lo localiza y parchea con
// el núcleo (`scan_stores` + `patch_stores` sobre `LinuxProcessMemory`, es
// decir, vía `/proc/<pid>/mem`). La prueba solo pasa si el HIJO observa
// `new_key` en su propia memoria (`PATCHED`).
//
// Todo el `unsafe` y lo dependiente de plataforma vive aquí, no en el núcleo.
// En el hijo (tras `fork()`) solo se usan llamadas async-signal-safe
// (`mmap`, `write`, `nanosleep`, `_exit`, accesos volátiles) y buffers en
// pila: nada de asignación, nada de runtime, nunca se vuelve de la función
// (termina con `_exit`). Un `PermissionDenied` de `/proc/<pid>/mem` es un
// resultado válido y se informa con `errno` exacto.

/// Tiempo que el padre espera el "listo" del hijo (ms).
const READY_TIMEOUT_MS: i32 = 5_000;
/// Tiempo que el hijo vigila su propia memoria antes de rendirse (ms).
/// 120 s (no 10 s): en el emulador el escaneo de cientos de regiones es lento
/// y el hijo debe seguir vivo hasta que el padre termine; sigue siendo un
/// timeout acotado con salida limpia y el padre lo recoge antes vía `done`.
const CHILD_WATCH_MS: u64 = 120_000;
/// Intervalo entre comprobaciones del hijo (ms).
const CHILD_POLL_MS: u64 = 50;
/// Tiempo que el padre espera la confirmación del hijo tras parchear (ms).
const RESULT_TIMEOUT_MS: i32 = 15_000;

/// Datos planos que el hijo lee tras `fork()` (todo `Copy`: sin asignación,
///
/// solo lecturas de memoria ya duplicada por `fork`).
#[derive(Clone, Copy)]
struct ChildData {
    keys: *const u8,
    flags: *const u8,
    new_key: [u8; 32],
    entry_size: usize,
    entry_count: usize,
    target_group: usize,
}

// SAFETY: solo se transporta el puntero del padre al hijo tras `fork()`; el
// hijo solo lee memoria ya existente y nunca lo comparte entre hilos.
unsafe impl Send for ChildData {}

/// Texto de error con clase y `errno` exactos (lo esencial del experimento).
fn errno_text(contexto: &str, e: &std::io::Error) -> String {
    match e.raw_os_error() {
        Some(n) => format!("{contexto}: {e} (kind={:?}, errno={n})", e.kind()),
        None => format!("{contexto}: {e} (kind={:?})", e.kind()),
    }
}

/// Carga el contrato desde su JSON usando el cargador real del núcleo (vuelco
/// a temporal, igual que `summarize_contract_json`).
fn load_contract_from_json(
    json: &str,
) -> Result<forever_patcher::KeyStoreContract, String> {
    static NEXT_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let id = NEXT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let mut path = std::env::temp_dir();
    path.push(format!(
        "forever-selftest-{}-{}.json",
        std::process::id(),
        id,
    ));
    std::fs::write(&path, json).map_err(|e| errno_text("no se pudo escribir el contrato temporal", &e))?;
    let result = forever_patcher::load_contract(&path).map_err(|e| e.to_string());
    let _ = std::fs::remove_file(&path);
    result
}

/// Lee una línea (hasta `\n` o EOF) del fd con timeout, usando solo `poll` +
/// `read` para poder expirar sin hilos.
fn read_line_timeout(fd: i32, timeout_ms: i32) -> std::io::Result<String> {
    use std::time::Instant;
    let deadline = Instant::now() + std::time::Duration::from_millis(timeout_ms.max(0) as u64);
    let mut out = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() && !out.is_empty() {
            break;
        }
        let ms = remaining.as_millis().min(i32::MAX as u128) as i32;
        let mut pfd = libc::pollfd {
            fd,
            events: libc::POLLIN | libc::POLLHUP | libc::POLLERR,
            revents: 0,
        };
        // SAFETY: `poll` sobre un fd propio y un `pollfd` en pila.
        let r = unsafe { libc::poll(&mut pfd, 1, ms) };
        if r < 0 {
            let e = std::io::Error::last_os_error();
            if e.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            return Err(e);
        }
        if r == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!("timeout de {timeout_ms} ms esperando al hijo"),
            ));
        }
        // SAFETY: `read` de 1 byte a un buffer en pila.
        let n = unsafe { libc::read(fd, byte.as_mut_ptr().cast::<libc::c_void>(), 1) };
        if n < 0 {
            let e = std::io::Error::last_os_error();
            if e.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            return Err(e);
        }
        if n == 0 {
            break; // EOF
        }
        if byte[0] == b'\n' {
            break;
        }
        out.push(byte[0]);
        if out.len() > 4096 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "línea del hijo demasiado larga",
            ));
        }
    }
    String::from_utf8(out).map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))
}

/// Espera al hijo hasta `timeout_ms` sin bloquearse; si sigue vivo lo mata
/// (`SIGKILL`) y lo recoge. Nunca deja zombis en el camino de éxito.
fn reap_child(pid: i32, timeout_ms: u64) {
    use std::time::Instant;
    let deadline = Instant::now() + std::time::Duration::from_millis(timeout_ms);
    loop {
        // SAFETY: `waitpid` WNOHANG sobre el hijo propio.
        let r = unsafe { libc::waitpid(pid, std::ptr::null_mut(), libc::WNOHANG) };
        if r == pid || r < 0 {
            return;
        }
        if Instant::now() >= deadline {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    // SAFETY: el hijo tiene timeout propio (10 s); si sigue aquí, se fuerza.
    unsafe {
        libc::kill(pid, libc::SIGKILL);
    }
    // SAFETY: recogida bloqueante final del hijo propio.
    unsafe {
        libc::waitpid(pid, std::ptr::null_mut(), 0);
    }
}

/// Escribe todo el buffer (reintentando en `EINTR`). Solo para el hijo.
unsafe fn child_write_all(fd: i32, mut p: *const u8, mut left: usize) {
    while left > 0 {
        let n = libc::write(fd, p.cast::<libc::c_void>(), left);
        if n <= 0 {
            // `last_os_error` solo lee el errno del hilo (sin asignar ni
            // bloquear): apto para el hijo tras `fork()`. En bionic no existe
            // `__errno_location` de glibc, así que no se usa.
            if std::io::Error::last_os_error().kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            break;
        }
        p = p.add(n as usize);
        left -= n as usize;
    }
}

/// Añade el decimal de `v` al buffer; devuelve la nueva longitud. Hijo.
fn child_push_dec(buf: &mut [u8; 128], mut len: usize, v: u64) -> usize {
    let mut digs = [0u8; 20];
    let mut n = 0usize;
    let mut x = v;
    if x == 0 {
        digs[0] = b'0';
        n = 1;
    } else {
        while x > 0 && n < digs.len() {
            digs[n] = b'0' + (x % 10) as u8;
            x /= 10;
            n += 1;
        }
    }
    while n > 0 && len < buf.len() {
        n -= 1;
        buf[len] = digs[n];
        len += 1;
    }
    len
}

/// Añade `v` en hex (`0x...`) al buffer. Hijo.
fn child_push_hex(buf: &mut [u8; 128], mut len: usize, v: u64) -> usize {
    const H: &[u8; 16] = b"0123456789abcdef";
    if len + 3 < buf.len() {
        buf[len] = b'0';
        buf[len + 1] = b'x';
        len += 2;
    }
    let mut started = false;
    for shift in (0..16).rev() {
        let d = ((v >> (shift * 4)) & 0xF) as usize;
        if d != 0 || started || shift == 0 {
            started = true;
            if len < buf.len() {
                buf[len] = H[d];
                len += 1;
            }
        }
    }
    len
}

/// Cuerpo del hijo tras `fork()`: NUNCA vuelve (termina con `_exit`).
///
/// Reserva la página, construye el almacén válido (mismo formato que valida el
/// núcleo: id u32 LE + 32 B de clave + flag + relleno `000000`), avisa por
/// `ready_w` y vigila con lecturas volátiles si la clave objetivo pasa a ser
/// `new_key`, publicando el resultado por `res_w`. Tras `PATCHED` sigue vivo
/// (página mapeada) hasta que el padre cierre `done_r` (EOF) o 15 s más: así
/// el reescaneo del padre siempre ve un proceso vivo, no un zombi.
unsafe fn child_main(ready_w: i32, res_w: i32, done_r: i32, d: ChildData) -> ! {
    // Cierra los extremos de lectura heredados (los del padre).
    // (Los cierra el padre en su lado; aquí solo se usan los de escritura.
    // No se hace nada más con fds para no cerrar por error un fd reused.)
    let block_size = d.entry_size * d.entry_count;
    let alloc_size = block_size.max(4096);
    let ptr = libc::mmap(
        std::ptr::null_mut(),
        alloc_size,
        libc::PROT_READ | libc::PROT_WRITE,
        libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
        -1,
        0,
    );
    if ptr == libc::MAP_FAILED {
        let e = std::io::Error::last_os_error().raw_os_error().unwrap_or(-1);
        let mut msg = [0u8; 128];
        let mut len = 0usize;
        for b in b"ERR mmap errno=" {
            if len < msg.len() {
                msg[len] = *b;
                len += 1;
            }
        }
        len = child_push_dec(&mut msg, len, e as u64);
        if len < msg.len() {
            msg[len] = b'\n';
            len += 1;
        }
        child_write_all(ready_w, msg.as_ptr(), len);
        libc::_exit(1);
    }
    let base = ptr as *mut u8;
    let padding = [0x00u8, 0x00, 0x00];
    let mut i = 0usize;
    while i < d.entry_count {
        let off = i * d.entry_size;
        let id = (i as u32) + 1;
        core::ptr::write_volatile(base.add(off), (id & 0xFF) as u8);
        core::ptr::write_volatile(base.add(off + 1), ((id >> 8) & 0xFF) as u8);
        core::ptr::write_volatile(base.add(off + 2), ((id >> 16) & 0xFF) as u8);
        core::ptr::write_volatile(base.add(off + 3), ((id >> 24) & 0xFF) as u8);
        let mut j = 0usize;
        while j < 32 {
            let kb = core::ptr::read(d.keys.add(i * 32 + j));
            core::ptr::write_volatile(base.add(off + 4 + j), kb);
            j += 1;
        }
        let fb = core::ptr::read(d.flags.add(i));
        core::ptr::write_volatile(base.add(off + 4 + 32), fb);
        let mut j = 0usize;
        while j < 3 {
            core::ptr::write_volatile(base.add(off + 4 + 32 + 1 + j), padding[j]);
            j += 1;
        }
        i += 1;
    }
    // Aviso de listo: "PID <pid> ADDR 0x<base>\n".
    let pid = libc::getpid() as u64;
    let mut msg = [0u8; 128];
    let mut len = 0usize;
    for b in b"PID " {
        msg[len] = *b;
        len += 1;
    }
    len = child_push_dec(&mut msg, len, pid);
    for b in b" ADDR " {
        if len < msg.len() {
            msg[len] = *b;
            len += 1;
        }
    }
    len = child_push_hex(&mut msg, len, base as u64);
    if len < msg.len() {
        msg[len] = b'\n';
        len += 1;
    }
    child_write_all(ready_w, msg.as_ptr(), len);

    // Vigilancia: lecturas volátiles (otro proceso puede reescribir la clave).
    let target_off = (d.target_group - 1) * d.entry_size + 4;
    let mut elapsed = 0u64;
    while elapsed < CHILD_WATCH_MS {
        let mut same = true;
        let mut j = 0usize;
        while j < 32 {
            if core::ptr::read_volatile(base.add(target_off + j)) != d.new_key[j] {
                same = false;
                break;
            }
            j += 1;
        }
        if same {
            let ok = b"PATCHED\n";
            child_write_all(res_w, ok.as_ptr(), ok.len());
            // Quedarse vivo con la página mapeada hasta que el padre cierre
            // `done_r` (EOF = permiso para salir) o 120 s más: los
            // (re)escaneos en el emulador son lentos y el hijo debe seguir
            // vivo durante todos ellos, no solo durante el primero.
            let mut waited = 0u64;
            let mut tmp = [0u8; 16];
            while waited < 120_000 {
                let mut pfd = libc::pollfd {
                    fd: done_r,
                    events: libc::POLLIN | libc::POLLHUP,
                    revents: 0,
                };
                let r = libc::poll(&mut pfd, 1, 500);
                if r > 0 && (pfd.revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR | libc::POLLNVAL) != 0) {
                    let n = libc::read(done_r, tmp.as_mut_ptr().cast::<libc::c_void>(), tmp.len());
                    if n <= 0 {
                        break; // EOF/HUP (padre cerró) o error: salir limpio.
                    }
                    // n > 0: contenido ignorado, pero el padre ya habló: salir.
                    break;
                }
                waited += 500;
            }
            libc::munmap(ptr, alloc_size);
            libc::_exit(0);
        }
        let req = libc::timespec {
            tv_sec: (CHILD_POLL_MS / 1000) as libc::time_t,
            tv_nsec: ((CHILD_POLL_MS % 1000) * 1_000_000) as libc::c_long,
        };
        libc::nanosleep(&req, std::ptr::null_mut());
        elapsed += CHILD_POLL_MS;
    }
    let to = b"TIMEOUT\n";
    child_write_all(res_w, to.as_ptr(), to.len());
    libc::munmap(ptr, alloc_size);
    libc::_exit(3);
}

/// Hex minúsculo de unos bytes (para el diagnóstico: sin dependencias).
fn hex_bytes(buf: &[u8]) -> String {
    const H: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(buf.len() * 2);
    for b in buf {
        s.push(H[(b >> 4) as usize] as char);
        s.push(H[(b & 0xF) as usize] as char);
    }
    s
}

/// Diagnóstico D-L3 sobre el hijo `pid` cuya página del almacén empieza en
/// `base`: ¿se puede leer `/proc/<pid>/mem` de verdad?
///
/// Devuelve `maps=<n> rwp_anon=<n> legibles=<ok>/<n>
/// base_read=Ok(<hex16> match=<bool>)|Err(<errno>) first_err=<...>`:
/// - `maps`: líneas de `/proc/<pid>/maps` (o el error al leerlo).
/// - `rwp_anon`: regiones que pasan el filtro REAL del núcleo
///   (`LinuxProcessMemory::regions`, `rw-p` sin ruta de fichero).
/// - `legibles`: de esas, cuántas devuelven 16 bytes al leerlas (el escaneo
///   salta en silencio las que fallan; aquí se cuentan y se guarda el primer
///   error con su `errno`).
/// - `base_read`: lectura directa de la página que el hijo dice haber
///   escrito, con los primeros 16 bytes en hex y si coinciden con lo
///   esperado (id `01000000` + 12 primeros bytes de la clave del grupo 1).
fn diagnose_child(
    pid: u32,
    base: u64,
    contract: &forever_patcher::KeyStoreContract,
) -> String {
    let mem = forever_patcher::LinuxProcessMemory::new(pid);
    let maps_text = std::fs::read_to_string(format!("/proc/{pid}/maps"));
    let maps_n = match &maps_text {
        Ok(t) => format!("{}", t.lines().count()),
        Err(e) => format!("ERR({})", errno_text("maps ilegible", e)),
    };
    let (rwp_n, legibles, first_err) = match mem.regions() {
        Ok(regions) => {
            let mut ok = 0usize;
            let mut first: Option<String> = None;
            for r in &regions {
                let mut probe = [0u8; 16];
                match mem.read(r.start, &mut probe) {
                    Ok(()) => ok += 1,
                    Err(e) => {
                        if first.is_none() {
                            first = Some(errno_text(
                                &format!("lectura de {:x} falló", r.start),
                                &e,
                            ));
                        }
                    }
                }
            }
            (
                format!("{}", regions.len()),
                format!("{ok}/{}", regions.len()),
                first.unwrap_or_else(|| "none".to_string()),
            )
        }
        Err(e) => (
            format!("ERR({})", errno_text("regions() falló", &e)),
            "0/?".to_string(),
            "none".to_string(),
        ),
    };
    let mut expected = [0u8; 16];
    expected[0] = 1; // id del grupo 1, u32 LE
    expected[4..16].copy_from_slice(&contract.known_keys[0][..12]);
    let base_read = match (|| {
        let mut buf = [0u8; 16];
        mem.read(base, &mut buf)?;
        Ok::<[u8; 16], std::io::Error>(buf)
    })() {
        Ok(buf) => format!(
            "Ok({} match={})",
            hex_bytes(&buf),
            buf == expected,
        ),
        Err(e) => format!("Err({})", errno_text("página del hijo ilegible", &e)),
    };
    format!("maps={maps_n} rwp_anon={rwp_n} legibles={legibles} base_read={base_read} first_err={first_err}")
}

/// Segundo nivel del diagnóstico D-L3: valida a mano el bloque publicado en
/// `base` (sin usar `scan_stores`) y reproduce la lectura por trozos que hace
/// el escaneo sobre la región contenedora.
///
/// Devuelve `block=<...>` + `container=<rango> chunk=<...>` +
/// `anchor_in_page=<n>`:
/// - `block`: lectura de los `block_size` bytes y chequeo entrada por
///   entrada (id, clave, flag, relleno) con el primer desajuste detallado.
/// - `container`/`chunk`: región que contiene a `base` y resultado de leer
///   el primer trozo de 256 KB como hace `scan_stores` (si el trozo grande
///   falla con `EIO` pero las lecturas cortas van, el escaneo salta la
///   región en silencio y ese es el culpable).
/// - `anchor_in_page`: nº de apariciones de la clave ancla (alineadas según
///   el contrato) dentro de la propia página del hijo (se espera 1, en el
///   desplazamiento 4).
fn diagnose_block(
    mem: &forever_patcher::LinuxProcessMemory,
    base: u64,
    contract: &forever_patcher::KeyStoreContract,
) -> String {
    let bs = contract.block_size();
    let mut buf = vec![0u8; bs];
    let block = match mem.read(base, &mut buf) {
        Err(e) => format!("block=read_Err({})", errno_text("bloque ilegible", &e)),
        Ok(()) => {
            let mut msg: Option<String> = None;
            for i in 0..contract.entry_count {
                let off = i * contract.entry_size;
                let id = u32::from_le_bytes(buf[off..off + 4].try_into().unwrap_or([0; 4]));
                if id != i as u32 + 1 {
                    msg = Some(format!("valid=false entry{} id exp={} got={id}", i + 1, i + 1));
                    break;
                }
                let key = &buf[off + 4..off + 4 + 32];
                let key_ok = if i + 1 == contract.target_group {
                    key == contract.known_keys[i] || key == contract.new_key
                } else {
                    key == contract.known_keys[i]
                };
                if !key_ok {
                    msg = Some(format!("valid=false entry{} key got={}", i + 1, hex_bytes(key)));
                    break;
                }
                if buf[off + 4 + 32] != contract.expected_flags[i] {
                    msg = Some(format!(
                        "valid=false entry{} flag exp={} got={}",
                        i + 1,
                        contract.expected_flags[i],
                        buf[off + 4 + 32]
                    ));
                    break;
                }
                let pad: [u8; 3] = buf[off + 4 + 32 + 1..off + contract.entry_size]
                    .try_into()
                    .unwrap_or([0xFF; 3]);
                if !contract.accepts_padding(&pad) {
                    msg = Some(format!("valid=false entry{} pad got={}", i + 1, hex_bytes(&pad)));
                    break;
                }
            }
            match msg {
                None => "block=read_Ok valid=true".to_string(),
                Some(m) => format!("block=read_Ok {m}"),
            }
        }
    };
    // Región contenedora y lectura del primer trozo como hace el escaneo.
    let (container, chunk) = match mem.regions() {
        Err(e) => (
            "container=ERR".to_string(),
            format!("chunk=regions_Err({})", errno_text("regions falló", &e)),
        ),
        Ok(regions) => match regions.iter().find(|r| r.start <= base && base < r.end) {
            None => (
                "container=none_for_base".to_string(),
                "chunk=skipped".to_string(),
            ),
            Some(r) => {
                let len = (r.end - r.start).min(256 * 1024) as usize;
                let mut c = vec![0u8; len];
                let cr = match mem.read(r.start, &mut c) {
                    Ok(()) => format!("chunk=Ok({len})"),
                    Err(e) => format!("chunk=Err({})", errno_text("trozo ilegible", &e)),
                };
                (format!("container=0x{:x}-0x{:x}", r.start, r.end), cr)
            }
        },
    };
    // Búsqueda del ancla dentro de la página del hijo (lo que el escaneo
    // debería ver): clave ancla en posiciones múltiplo de `scan_alignment`.
    let anchor = contract.anchor_key();
    let align = contract.scan_alignment.max(1);
    let mut page = vec![0u8; bs.max(4096)];
    let anchor_hits = match mem.read(base, &mut page) {
        Err(_) => "anchor_in_page=read_Err".to_string(),
        Ok(()) => {
            let mut hits = 0u32;
            let mut first_off: Option<usize> = None;
            // Solo hasta `bs`: más allá es memoria ajena al almacén.
            if page.len() >= bs && bs >= 32 {
                let mut pos = 0usize;
                while pos + 32 <= bs {
                    if ((base + pos as u64) % align == 0) && page[pos..pos + 32] == anchor[..] {
                        hits += 1;
                        if first_off.is_none() {
                            first_off = Some(pos);
                        }
                    }
                    pos += 1;
                }
            }
            format!("anchor_in_page={hits}@{:?}", first_off)
        }
    };
    format!("{block} {container} {chunk} {anchor_hits}")
}

/// Autotest cross-proceso real (padre): crea el hijo, lo parchea con el núcleo
/// y espera su confirmación. Devuelve una línea `result=OK ...` o
/// `result=FAIL ...` con la causa exacta (incluido `errno`).
pub fn cross_process_self_test(contract_json: &str) -> String {
    // OJO: en Rust, Android NO es `target_os = "linux"`, es `"android"`.
    if !cfg!(any(target_os = "linux", target_os = "android")) {
        return "result=FAIL stage=fork cause=el autotest necesita Linux/Android (/proc/<pid>/mem)".to_string();
    }
    let contract = match load_contract_from_json(contract_json) {
        Ok(c) => c,
        Err(e) => return format!("result=FAIL stage=load_contract cause={e}"),
    };
    // Copias planas para el hijo (el `Vec` original sigue vivo en el padre
    // durante todo el test, así que los punteros son válidos tras `fork()`).
    let mut keys_flat = Vec::with_capacity(contract.entry_count * 32);
    for k in &contract.known_keys {
        keys_flat.extend_from_slice(k);
    }
    let flags = contract.expected_flags.clone();
    let data = ChildData {
        keys: keys_flat.as_ptr(),
        flags: flags.as_ptr(),
        new_key: contract.new_key,
        entry_size: contract.entry_size,
        entry_count: contract.entry_count,
        target_group: contract.target_group,
    };
    // SAFETY: `pipe`/`fork`/`close`/`read`/`waitpid` sobre fds propios.
    unsafe {
        let mut ready = [0i32; 2];
        if libc::pipe(ready.as_mut_ptr()) != 0 {
            let e = std::io::Error::last_os_error();
            return format!("result=FAIL stage=pipe_ready cause={}", errno_text("pipe listo falló", &e));
        }
        let mut res = [0i32; 2];
        if libc::pipe(res.as_mut_ptr()) != 0 {
            let e = std::io::Error::last_os_error();
            libc::close(ready[0]);
            libc::close(ready[1]);
            return format!("result=FAIL stage=pipe_result cause={}", errno_text("pipe resultado falló", &e));
        }
        // `done`: el padre la mantiene abierta y la cierra para decirle al
        // hijo que ya puede salir (el hijo ve EOF).
        let mut done = [0i32; 2];
        if libc::pipe(done.as_mut_ptr()) != 0 {
            let e = std::io::Error::last_os_error();
            libc::close(ready[0]);
            libc::close(ready[1]);
            libc::close(res[0]);
            libc::close(res[1]);
            return format!("result=FAIL stage=pipe_done cause={}", errno_text("pipe control falló", &e));
        }
        let pid = libc::fork();
        if pid < 0 {
            let e = std::io::Error::last_os_error();
            libc::close(ready[0]);
            libc::close(ready[1]);
            libc::close(res[0]);
            libc::close(res[1]);
            libc::close(done[0]);
            libc::close(done[1]);
            return format!("result=FAIL stage=fork cause={}", errno_text("fork falló", &e));
        }
        if pid == 0 {
            // Hijo: cierra lectura, ejecuta y nunca vuelve.
            libc::close(ready[0]);
            libc::close(res[0]);
            libc::close(done[1]);
            child_main(ready[1], res[1], done[0], data);
        }
        // Padre: cierra escritura de listo/resultado y lectura de control.
        libc::close(ready[1]);
        libc::close(res[1]);
        libc::close(done[0]);
        let finish = |s: String| {
            libc::close(ready[0]);
            libc::close(res[0]);
            libc::close(done[1]);
            s
        };
        let fail = |stage: &str, extra: String| {
            reap_child(pid, 5_000);
            finish(format!("result=FAIL stage={stage} child_pid={pid} cause={extra}"))
        };

        let ready_line = match read_line_timeout(ready[0], READY_TIMEOUT_MS) {
            Ok(l) => l,
            Err(e) => return fail("ready", errno_text("el hijo no avisó de listo", &e)),
        };
        if let Some(rest) = ready_line.strip_prefix("ERR ") {
            return fail("child", format!("el hijo falló antes de publicar: {rest}"));
        }
        // "PID <pid> ADDR 0x<base>".
        let cols: Vec<&str> = ready_line.split_whitespace().collect();
        let (pub_pid, store_addr): (u32, u64) = match cols.as_slice() {
            ["PID", p, "ADDR", a] => {
                let pp = p.parse::<u32>();
                let aa = a.strip_prefix("0x").and_then(|h| u64::from_str_radix(h, 16).ok());
                match (pp, aa) {
                    (Ok(pp), Some(aa)) => (pp, aa),
                    _ => return fail("ready", format!("línea de listo ilegible: {ready_line:?}")),
                }
            }
            _ => return fail("ready", format!("línea de listo ilegible: {ready_line:?}")),
        };
        if pub_pid != pid as u32 {
            return fail("ready", format!("el PID publicado ({pub_pid}) no coincide con el hijo ({pid})"));
        }

        let mem = forever_patcher::LinuxProcessMemory::new(pid as u32);
        // Diagnóstico D-L3: `scan_stores` salta en silencio las regiones no
        // legibles, así que "0 almacenes" es ambiguo. Se distingue aquí si
        // `/proc/<pid>/mem` se puede leer (contenido real de la página del
        // hijo + prueba de lectura por región candidata) o si SELinux lo
        // niega (errno exacto).
        let diag = diagnose_child(pid as u32, store_addr, &contract);
        let diag2 = diagnose_block(&mem, store_addr, &contract);
        let diag = format!("{diag} {diag2}");
        // Tiempo del escaneo + si el hijo sigue vivo al terminar: si el
        // escaneo tarda más que la vida del hijo, "0 almacenes" es una
        // carrera temporal, no una denegación.
        let t0 = std::time::Instant::now();
        let found = match forever_patcher::scan_stores(&mem, &contract) {
            Ok(f) => f,
            Err(e) => return fail("scan", format!("{diag} {}", errno_text(&format!("/proc/{pid}/mem no legible (scan)"), &e))),
        };
        let scan_ms = t0.elapsed().as_millis();
        // `kill(pid, 0)` no envía señal: ESRCH = muerto, Ok/EPERM = vivo.
        let child_alive = unsafe {
            if libc::kill(pid, 0) == 0 {
                true
            } else {
                std::io::Error::last_os_error().raw_os_error() != Some(libc::ESRCH)
            }
        };
        let timing = format!("scan_ms={scan_ms} child_alive_after_scan={child_alive}");
        if found.len() != 1 {
            return fail("scan", format!("{diag} {timing} se esperaban 1 almacén, hallados {}", found.len()));
        }
        let outcomes = match forever_patcher::patch_stores(&mem, &contract) {
            Ok(o) => o,
            Err(e) => return fail("patch", errno_text(&format!("/proc/{pid}/mem no escribible (patch)"), &e)),
        };
        if outcomes.len() != 1 {
            return fail("patch", format!("se esperaba 1 resultado, hallados {}", outcomes.len()));
        }
        let patched = !outcomes[0].already_patched;
        if !patched {
            return fail("patch", "el almacén ya estaba parcheado antes de escribir (inesperado)".to_string());
        }
        let confirm = match read_line_timeout(res[0], RESULT_TIMEOUT_MS) {
            Ok(l) => l,
            Err(e) => return fail("confirm", errno_text("el hijo no confirmó el parche", &e)),
        };
        // Reescaneo: debe seguir reconociendo el bloque como ya parcheado.
        // (El hijo sigue vivo esperando el EOF de `done`: no hay carrera con
        // su salida.)
        let rescan = match forever_patcher::scan_stores(&mem, &contract) {
            Ok(f) => f,
            Err(e) => return fail("rescan", errno_text("reescaneo falló", &e)),
        };
        let rescan_ok = rescan.len() == 1 && rescan[0].already_patched && rescan[0].addr == found[0].addr;
        // Permiso de salida al hijo (EOF) y recogida limpia (sin SIGKILL en el
        // camino de éxito: el hijo sale solo al ver el EOF).
        libc::close(done[1]);
        reap_child(pid, 5_000);
        libc::close(ready[0]);
        libc::close(res[0]);
        if confirm != "PATCHED" {
            return format!(
                "result=FAIL stage=confirm child_pid={pid} stores=1 store_addr=0x{:x} patched=true child_confirm={confirm:?} cause=el hijo no observó new_key en su memoria",
                found[0].addr,
            );
        }
        if !rescan_ok {
            let mut addrs = String::from("[");
            for (k, s) in rescan.iter().enumerate() {
                if k > 0 {
                    addrs.push(',');
                }
                addrs.push_str(&format!("0x{:x}:{}", s.addr, s.already_patched));
            }
            addrs.push(']');
            return format!(
                "result=FAIL stage=rescan child_pid={pid} stores=1 store_addr=0x{:x} patched=true child_confirm=PATCHED rescan_len={} rescan={addrs} cause=el reescaneo no ve el bloque como ya parcheado",
                found[0].addr,
                rescan.len(),
            );
        }
        format!(
            "result=OK child_pid={pid} stores=1 store_addr=0x{:x} child_addr=0x{store_addr:x} patched=true child_confirm=PATCHED rescan_already_patched=true {timing} {diag}",
            found[0].addr,
        )
    }
}

/// `com.wowforever.launcher.NativeBridge.crossProcessSelfTest`
#[no_mangle]
pub unsafe extern "system" fn Java_com_wowforever_launcher_NativeBridge_crossProcessSelfTest<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
) -> JString<'local> {
    let text: String = env
        .get_string(&json)
        .map(|s| s.into())
        .unwrap_or_else(|_| String::from("{\"error\":\"json JNI ilegible\"}"));
    let out = cross_process_self_test(&text);
    env.new_string(out)
        .expect("no se pudo crear el String JNI del autotest")
}

// --- API productiva del launcher automatizado --------------------------------
//
// Esta sección es deliberadamente distinta del autotest anterior. El autotest
// crea un hijo suyo; la API de producción solo acepta un descendiente que el
// launcher pueda demostrar, conserva el fd de /proc/<pid>/mem abierto durante
// toda la operación y nunca envía señales al proceso del juego.

const RESULT_VERSION: u64 = 1;
const MAX_FIND_PROCESSES: usize = 4096;
const MAX_CANDIDATES: usize = 64;
const MAX_ANCESTRY_DEPTH: usize = 64;
const MAX_PROC_STAT_BYTES: usize = 64 * 1024;
const MAX_PROC_STATUS_BYTES: usize = 64 * 1024;
const MAX_PROC_MAPS_BYTES: usize = 1024 * 1024;
const MAX_CONTRACT_BYTES: usize = 64 * 1024;
const CANONICAL_CONTRACT: &str = include_str!("../../../forever/contract.json");
const SCAN_TIMEOUT: Duration = Duration::from_secs(15);
const SCAN_MAX_BYTES: u64 = 128 * 1024 * 1024;
const SCAN_MAX_OPS: u64 = 8192;
static APPLY_LOCK: Mutex<()> = Mutex::new(());

#[derive(Clone, Debug, PartialEq, Eq)]
struct ProcStat {
    ppid: u32,
    start_ticks: u64,
}

#[derive(Clone, Debug)]
struct ProcFs {
    root: PathBuf,
}

impl ProcFs {
    fn host() -> Self {
        Self { root: PathBuf::from("/proc") }
    }

    fn path(&self, pid: u32, name: &str) -> PathBuf {
        self.root.join(pid.to_string()).join(name)
    }

    fn stat(&self, pid: u32, budget: &WorkBudget) -> io::Result<ProcStat> {
        let bytes = self.read_bounded(pid, "stat", MAX_PROC_STAT_BYTES, budget)?;
        parse_proc_stat_bytes(&bytes)
    }

    fn uid(&self, pid: u32, budget: &WorkBudget) -> io::Result<u32> {
        let bytes = self.read_bounded(pid, "status", MAX_PROC_STATUS_BYTES, budget)?;
        let text = std::str::from_utf8(&bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        text.lines()
            .find_map(|line| line.strip_prefix("Uid:").map(str::trim))
            .and_then(|line| line.split_whitespace().next())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "status sin Uid"))
            .and_then(|uid| uid.parse::<u32>().map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e)))
    }

    fn exe_matches(&self, pid: u32, expected: &Path, budget: &WorkBudget) -> io::Result<bool> {
        budget.begin_op()?;
        let link = std::fs::read_link(self.path(pid, "exe")).map_err(|error| {
            budget.record_read_error(&error);
            error
        })?;
        budget.charge(link.as_os_str().len())?;
        budget.check()?;
        Ok(normalize_proc_path(&link) == expected)
    }

    fn maps_matches(&self, pid: u32, expected: &Path, budget: &WorkBudget) -> io::Result<bool> {
        let bytes = self.read_bounded(pid, "maps", MAX_PROC_MAPS_BYTES, budget)?;
        let text = std::str::from_utf8(&bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        let metadata = std::fs::metadata(expected)?;
        let expected_inode = metadata.ino();
        Ok(text.lines().any(|line| {
            let mut cols = line.split_whitespace();
            let _range = cols.next();
            let perms = cols.next().unwrap_or("");
            let offset = cols.next().and_then(|value| u64::from_str_radix(value, 16).ok()).unwrap_or(0);
            let _dev = cols.next();
            let inode = cols.next().and_then(|value| value.parse::<u64>().ok());
            let path = cols.next().map(Path::new).map(normalize_proc_path);
            // A Wine process normally has /proc/exe pointing at wine, so the
            // fallback must be a real executable image mapping, not an r-- data
            // mapping. Exact path + inode + nonzero file offset binds it to the
            // verified PE file rather than a same-name or copied data mapping.
            perms.as_bytes().get(0) == Some(&b'r')
                && perms.as_bytes().get(2) == Some(&b'x')
                && offset > 0
                && inode == Some(expected_inode)
                && path.as_ref().is_some_and(|path| path == expected)
        }))
    }

    fn read_bounded(&self, pid: u32, name: &str, limit: usize, budget: &WorkBudget) -> io::Result<Vec<u8>> {
        budget.begin_op()?;
        let path = self.path(pid, name);
        let metadata = match std::fs::metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) => {
                budget.record_read_error(&error);
                return Err(error);
            }
        };
        if metadata.len() > limit as u64 {
            budget.mark_incomplete();
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, format!("/proc/{pid}/{name} exceeds bounded read")));
        }
        let mut file = match File::open(&path) {
            Ok(file) => file,
            Err(error) => {
                budget.record_read_error(&error);
                return Err(error);
            }
        };
        let mut bytes = Vec::with_capacity(metadata.len().min(limit as u64) as usize);
        std::io::Read::by_ref(&mut file).take(limit as u64 + 1).read_to_end(&mut bytes).map_err(|error| {
            budget.record_read_error(&error);
            error
        })?;
        if bytes.len() > limit {
            budget.mark_incomplete();
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, format!("/proc/{pid}/{name} grew beyond bounded read")));
        }
        budget.charge(bytes.len())?;
        budget.check()?;
        Ok(bytes)
    }
}

fn normalize_proc_path(path: &Path) -> PathBuf {
    if path.is_absolute() { path.to_path_buf() } else { path.to_path_buf() }
}

/// `/proc/<pid>/stat` has a comm field which may contain spaces and `)`.
/// Parsing from the final `)` avoids treating those characters as columns.
fn parse_proc_stat(text: &str) -> io::Result<ProcStat> {
    let close = text.rfind(')').ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "stat sin comm"))?;
    let fields: Vec<&str> = text[close + 1..].split_whitespace().collect();
    // fields[0] is field 3 (state), fields[1] is field 4 (ppid), and
    // fields[19] is field 22 (starttime), per proc_pid_stat(5).
    if fields.len() <= 19 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "stat incompleto"));
    }
    let ppid = fields[1].parse::<u32>().map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let start_ticks = fields[19].parse::<u64>().map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    Ok(ProcStat { ppid, start_ticks })
}

fn parse_proc_stat_bytes(bytes: &[u8]) -> io::Result<ProcStat> {
    let text = std::str::from_utf8(bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    parse_proc_stat(text)
}

#[derive(Clone, Debug)]
struct OwnedIdentity {
    pid: u32,
    start_ticks: u64,
    current_pid: u32,
    current_uid: u32,
    executable: PathBuf,
}

#[derive(Debug)]
enum IdentityError {
    NotFound(String),
    Rejected(String),
    Permission(String),
}

impl IdentityError {
    fn kind(&self) -> &'static str {
        match self {
            Self::NotFound(_) => "not_found",
            Self::Rejected(_) => "identity",
            Self::Permission(_) => "permission_denied",
        }
    }

    fn detail(&self) -> &str {
        match self {
            Self::NotFound(s) | Self::Rejected(s) | Self::Permission(s) => s,
        }
    }
}

fn canonical_executable(path: &str) -> Result<PathBuf, String> {
    let input = Path::new(path);
    if !input.is_absolute() {
        return Err("executablePath must be absolute".to_string());
    }
    std::fs::canonicalize(input).map_err(|e| format!("executablePath is not readable: {e}"))
}

fn verify_pe_aa64(path: &Path) -> Result<(), String> {
    let mut file = File::open(path).map_err(|e| format!("executable cannot be opened: {e}"))?;
    let size = file.metadata().map_err(|e| format!("executable metadata failed: {e}"))?.len();
    if size < 0x40 {
        return Err("executable is shorter than a DOS header".to_string());
    }
    let mut dos = [0u8; 0x40];
    file.read_exact(&mut dos).map_err(|e| format!("executable DOS header read failed: {e}"))?;
    if &dos[0..2] != b"MZ" {
        return Err("executable is not a PE image (MZ missing)".to_string());
    }
    let pe_offset = u32::from_le_bytes(dos[0x3c..0x40].try_into().unwrap()) as u64;
    if pe_offset.checked_add(26).filter(|end| *end <= size).is_none() {
        return Err("PE header is outside executable".to_string());
    }
    file.seek(SeekFrom::Start(pe_offset)).map_err(|e| format!("PE header seek failed: {e}"))?;
    let mut header = [0u8; 26];
    file.read_exact(&mut header).map_err(|e| format!("PE header read failed: {e}"))?;
    if &header[0..4] != b"PE\0\0" {
        return Err("PE signature missing".to_string());
    }
    if u16::from_le_bytes([header[4], header[5]]) != 0xaa64 {
        return Err("executable is not PE AA64".to_string());
    }
    if u16::from_le_bytes([header[24], header[25]]) != 0x20b {
        return Err("PE optional header is not PE32+".to_string());
    }
    Ok(())
}

fn validate_owned_identity(
    procfs: &ProcFs,
    current_pid: u32,
    current_uid: u32,
    pid: u32,
    expected_start_ticks: u64,
    executable: &Path,
    budget: &WorkBudget,
) -> Result<OwnedIdentity, IdentityError> {
    if pid == 0 || pid == current_pid {
        return Err(IdentityError::Rejected("target is not a game descendant".to_string()));
    }
    let stat = procfs.stat(pid, budget).map_err(|e| classify_proc_error("target stat", e))?;
    if stat.start_ticks != expected_start_ticks {
        return Err(IdentityError::Rejected("PID starttime does not match; possible PID reuse".to_string()));
    }
    let uid = procfs.uid(pid, budget).map_err(|e| classify_proc_error("target uid", e))?;
    if uid != current_uid {
        return Err(IdentityError::Rejected("target UID is not the launcher UID".to_string()));
    }
    let exe_ok = procfs.exe_matches(pid, executable, budget).map_err(|e| classify_proc_error("target exe", e))?;
    let map_ok = procfs.maps_matches(pid, executable, budget).map_err(|e| classify_proc_error("target maps", e))?;
    if !exe_ok && !map_ok {
        return Err(IdentityError::Rejected("target executable is not an exact executable mapping".to_string()));
    }

    let mut child = pid;
    let mut seen = std::collections::BTreeSet::new();
    let mut descended = false;
    for _ in 0..MAX_ANCESTRY_DEPTH {
        if !seen.insert(child) {
            break;
        }
        let parent = procfs.stat(child, budget).map_err(|e| classify_proc_error("ancestry stat", e))?.ppid;
        if parent == current_pid {
            descended = true;
            break;
        }
        if parent == 0 || parent == 1 || parent == child {
            break;
        }
        let parent_uid = procfs.uid(parent, budget).map_err(|e| classify_proc_error("ancestry uid", e))?;
        if parent_uid != current_uid {
            return Err(IdentityError::Rejected("process ancestry crosses a different UID".to_string()));
        }
        child = parent;
    }
    if !descended {
        return Err(IdentityError::Rejected("target is not a verifiable descendant of the launcher".to_string()));
    }
    Ok(OwnedIdentity { pid, start_ticks: expected_start_ticks, current_pid, current_uid, executable: executable.to_path_buf() })
}

fn classify_proc_error(context: &str, error: io::Error) -> IdentityError {
    match error.raw_os_error() {
        Some(libc::ENOENT | libc::ESRCH) => IdentityError::NotFound(format!("{context}: process disappeared")),
        Some(libc::EACCES | libc::EPERM) => IdentityError::Permission(format!("{context}: permission denied")),
        _ => IdentityError::Rejected(format!("{context}: {error}")),
    }
}

fn current_uid() -> u32 {
    // Android and Linux both provide getuid through libc; this is the UID of
    // the app process, not a caller-supplied value.
    unsafe { libc::getuid() }
}

fn find_owned(executable_path: &str) -> String {
    let executable = match canonical_executable(executable_path) {
        Ok(p) => p,
        Err(e) => return find_json(Vec::new(), 0, 0, Some(("invalid_executable", e))),
    };
    if let Err(e) = verify_pe_aa64(&executable) {
        return find_json(Vec::new(), 0, 0, Some(("wrong_executable", e)));
    }
    let procfs = ProcFs::host();
    let budget = WorkBudget::new();
    let current_pid = std::process::id();
    let uid = current_uid();
    let mut scanned = 0usize;
    let mut rejected = 0usize;
    if budget.begin_op().is_err() {
        return find_json(Vec::new(), 0, 0, Some(("incomplete", "process discovery budget exhausted".to_string())));
    }
    let mut candidates = Vec::new();
    let entries = match std::fs::read_dir(&procfs.root) {
        Ok(e) => e,
        Err(e) => {
            budget.record_read_error(&e);
            return find_json(Vec::new(), 0, 0, Some(("incomplete", format!("process discovery unreadable: {e}"))));
        }
    };
    let mut entries = entries;
    loop {
        if budget.check().is_err() {
            break;
        }
        if scanned >= MAX_FIND_PROCESSES {
            if entries.next().is_some() {
                budget.mark_incomplete();
            }
            break;
        }
        let entry = match entries.next() {
            Some(Ok(entry)) => entry,
            Some(Err(error)) => {
                budget.record_read_error(&error);
                continue;
            }
            None => break,
        };
        let Ok(pid) = entry.file_name().to_string_lossy().parse::<u32>() else { continue };
        scanned += 1;
        let stat = match procfs.stat(pid, &budget) {
            Ok(stat) => stat,
            Err(_) => continue,
        };
        match validate_owned_identity(&procfs, current_pid, uid, pid, stat.start_ticks, &executable, &budget) {
            Ok(_) => {
                candidates.push(json!({"pid": pid, "startTicks": stat.start_ticks}));
                if candidates.len() >= MAX_CANDIDATES {
                    budget.mark_incomplete();
                    break;
                }
            }
            Err(_) => rejected += 1,
        }
    }
    if budget.exhausted() || !budget.coverage_complete() {
        return find_json(candidates, scanned, rejected, Some(("incomplete", "process discovery coverage was incomplete".to_string())));
    }
    find_json(candidates, scanned, rejected, None)
}

fn find_json(candidates: Vec<Value>, scanned: usize, rejected: usize, error: Option<(&str, String)>) -> String {
    let mut out = Map::new();
    out.insert("version".to_string(), json!(RESULT_VERSION));
    let status = if error.as_ref().is_some_and(|(kind, _)| *kind == "incomplete") {
        "INCOMPLETE"
    } else if candidates.is_empty() {
        "NOT_FOUND"
    } else {
        "VALIDATED"
    };
    out.insert("status".to_string(), json!(status));
    out.insert("candidates".to_string(), Value::Array(candidates));
    out.insert("scanned".to_string(), json!(scanned));
    out.insert("rejected".to_string(), json!(rejected));
    if let Some((kind, detail)) = error {
        out.insert("errorKind".to_string(), json!(kind));
        out.insert("error".to_string(), json!(detail));
    }
    Value::Object(out).to_string()
}

#[derive(Clone, Debug)]
struct WorkBudget {
    deadline: Instant,
    max_bytes: u64,
    max_ops: u64,
    bytes: std::sync::Arc<std::sync::atomic::AtomicU64>,
    ops: std::sync::Arc<std::sync::atomic::AtomicU64>,
    exhausted: std::sync::Arc<std::sync::atomic::AtomicBool>,
    incomplete: std::sync::Arc<std::sync::atomic::AtomicBool>,
    read_errors: std::sync::Arc<std::sync::atomic::AtomicU64>,
    permission_errors: std::sync::Arc<std::sync::atomic::AtomicU64>,
    write_attempts: std::sync::Arc<std::sync::atomic::AtomicU64>,
    committed_bytes: std::sync::Arc<std::sync::atomic::AtomicU64>,
    possible_partial_writes: std::sync::Arc<std::sync::atomic::AtomicU64>,
    mutation_unknown: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

impl WorkBudget {
    fn new() -> Self {
        Self {
            deadline: Instant::now() + SCAN_TIMEOUT,
            max_bytes: SCAN_MAX_BYTES,
            max_ops: SCAN_MAX_OPS,
            bytes: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            ops: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            exhausted: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            incomplete: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            read_errors: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            permission_errors: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            write_attempts: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            committed_bytes: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            possible_partial_writes: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            mutation_unknown: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }

    fn check(&self) -> io::Result<()> {
        use std::sync::atomic::Ordering;
        if Instant::now() >= self.deadline {
            self.exhausted.store(true, Ordering::Relaxed);
            return Err(io::Error::new(io::ErrorKind::TimedOut, "patch scan timeout"));
        }
        Ok(())
    }

    fn begin_op(&self) -> io::Result<()> {
        use std::sync::atomic::Ordering;
        self.check()?;
        let ops = self.ops.fetch_add(1, Ordering::Relaxed).saturating_add(1);
        if ops > self.max_ops {
            self.exhausted.store(true, Ordering::Relaxed);
            return Err(io::Error::new(io::ErrorKind::TimedOut, "patch read budget exceeded"));
        }
        Ok(())
    }

    fn charge(&self, bytes: usize) -> io::Result<()> {
        use std::sync::atomic::Ordering;
        let bytes = bytes as u64;
        let total = self.bytes.fetch_add(bytes, Ordering::Relaxed).saturating_add(bytes);
        if total > self.max_bytes {
            self.exhausted.store(true, Ordering::Relaxed);
            return Err(io::Error::new(io::ErrorKind::TimedOut, "patch read budget exceeded"));
        }
        self.check()
    }

    fn reserve(&self, bytes: usize) -> io::Result<()> {
        self.begin_op()?;
        self.charge(bytes)
    }

    fn exhausted(&self) -> bool {
        self.exhausted.load(std::sync::atomic::Ordering::Relaxed)
    }
    fn mark_incomplete(&self) {
        self.incomplete.store(true, std::sync::atomic::Ordering::Relaxed);
    }
    fn coverage_complete(&self) -> bool {
        !self.exhausted()
            && !self.incomplete.load(std::sync::atomic::Ordering::Relaxed)
            && self.read_errors() == 0
    }
    fn bytes(&self) -> u64 { self.bytes.load(std::sync::atomic::Ordering::Relaxed) }
    fn ops(&self) -> u64 { self.ops.load(std::sync::atomic::Ordering::Relaxed) }
    fn record_read_error(&self, error: &io::Error) {
        use std::sync::atomic::Ordering;
        self.read_errors.fetch_add(1, Ordering::Relaxed);
        self.incomplete.store(true, Ordering::Relaxed);
        if matches!(error.raw_os_error(), Some(libc::EACCES | libc::EPERM)) {
            self.permission_errors.fetch_add(1, Ordering::Relaxed);
        }
    }
    fn read_errors(&self) -> u64 { self.read_errors.load(std::sync::atomic::Ordering::Relaxed) }
    fn permission_errors(&self) -> u64 { self.permission_errors.load(std::sync::atomic::Ordering::Relaxed) }
    fn record_write_attempt(&self) {
        self.write_attempts.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
    fn record_committed(&self, bytes: usize) {
        self.committed_bytes.fetch_add(bytes as u64, std::sync::atomic::Ordering::Relaxed);
    }
    fn record_mutation_unknown(&self) {
        self.possible_partial_writes.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.mutation_unknown.store(true, std::sync::atomic::Ordering::Relaxed);
    }
    fn write_attempts(&self) -> u64 { self.write_attempts.load(std::sync::atomic::Ordering::Relaxed) }
    fn committed_bytes(&self) -> u64 { self.committed_bytes.load(std::sync::atomic::Ordering::Relaxed) }
    fn possible_partial_writes(&self) -> u64 { self.possible_partial_writes.load(std::sync::atomic::Ordering::Relaxed) }
    fn mutation_unknown(&self) -> bool { self.mutation_unknown.load(std::sync::atomic::Ordering::Relaxed) }
}

/// A process-memory fd opened only after identity validation. Keeping this fd
/// open prevents a later PID reuse from redirecting a read/write to a new
/// process (the fd still refers to the original proc inode).
struct PinnedProcessMemory {
    identity: OwnedIdentity,
    procfs: ProcFs,
    budget: WorkBudget,
    file: Mutex<File>,
}

impl PinnedProcessMemory {
    fn open(identity: OwnedIdentity, writable: bool, budget: WorkBudget) -> io::Result<Self> {
        budget.begin_op()?;
        let path = ProcFs::host().path(identity.pid, "mem");
        let file = OpenOptions::new().read(true).write(writable).open(path)?;
        let mem = Self { identity, procfs: ProcFs::host(), budget, file: Mutex::new(file) };
        mem.revalidate().map_err(|e| io::Error::new(io::ErrorKind::PermissionDenied, e.detail().to_string()))?;
        Ok(mem)
    }

    fn revalidate(&self) -> Result<(), IdentityError> {
        validate_owned_identity(&self.procfs, self.identity.current_pid, self.identity.current_uid, self.identity.pid, self.identity.start_ticks, &self.identity.executable, &self.budget).map(|_| ())
    }
}

impl forever_patcher::MemoryAccess for PinnedProcessMemory {
    fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> {
        let bytes = self.procfs.read_bounded(self.identity.pid, "maps", MAX_PROC_MAPS_BYTES, &self.budget)?;
        let text = std::str::from_utf8(&bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        let mut regions = Vec::new();
        for line in text.lines() {
            let mut cols = line.split_whitespace();
            let (Some(range), Some(perms)) = (cols.next(), cols.next()) else { continue };
            let mut rest = cols.skip(3);
            if let Some(path) = rest.next() {
                if path.starts_with('/') { continue; }
            }
            let p: Vec<char> = perms.chars().collect();
            let Some((start, end)) = range.split_once('-').and_then(|(a, b)| Some((u64::from_str_radix(a, 16).ok()?, u64::from_str_radix(b, 16).ok()?))) else { continue };
            if p.len() >= 4 && p[0] == 'r' && p[1] == 'w' && p[2] == '-' && p[3] == 'p' && end > start {
                regions.push(forever_patcher::Region { start, end });
            }
        }
        Ok(regions)
    }

    fn read(&self, addr: u64, buf: &mut [u8]) -> io::Result<()> {
        self.budget.reserve(buf.len())?;
        let mut file = self.file.lock().map_err(|_| io::Error::other("memory fd mutex poisoned"))?;
        file.seek(SeekFrom::Start(addr))?;
        match file.read_exact(buf) {
            Ok(()) => self.budget.check(),
            Err(error) => {
                self.budget.record_read_error(&error);
                Err(error)
            }
        }
    }

    fn write(&self, addr: u64, data: &[u8]) -> io::Result<()> {
        self.budget.check()?;
        self.revalidate().map_err(|e| io::Error::new(io::ErrorKind::PermissionDenied, e.detail().to_string()))?;
        self.budget.reserve(data.len())?;
        let mut file = self.file.lock().map_err(|_| io::Error::other("memory fd mutex poisoned"))?;
        file.seek(SeekFrom::Start(addr))?;
        file.write_all(data)?;
        self.budget.check()
    }
}

/// Adds the production-only guard around the core API. `patch_stores` still
/// decides which stores are valid and which address is the target; this guard
/// makes its final write conditional on a fresh complete-block validation and
/// verifies the exact 32-byte read-back.
struct GuardedMemory<'a, M: forever_patcher::MemoryAccess> {
    inner: &'a M,
    inner_budgets_reads: bool,
    contract: &'a forever_patcher::KeyStoreContract,
    budget: WorkBudget,
    approved: Arc<Mutex<std::collections::BTreeSet<u64>>>,
    scan_complete: Arc<std::sync::atomic::AtomicBool>,
}

impl<'a, M: forever_patcher::MemoryAccess> GuardedMemory<'a, M> {
    fn new(inner: &'a M, contract: &'a forever_patcher::KeyStoreContract, budget: WorkBudget) -> Self {
        Self {
            inner,
            inner_budgets_reads: false,
            contract,
            budget,
            approved: Arc::new(Mutex::new(std::collections::BTreeSet::new())),
            scan_complete: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }

    fn with_budgeted_inner(inner: &'a M, contract: &'a forever_patcher::KeyStoreContract, budget: WorkBudget) -> Self {
        let mut guard = Self::new(inner, contract, budget);
        guard.inner_budgets_reads = true;
        guard
    }

    fn approve_complete(&self, stores: &[forever_patcher::FoundStore]) -> bool {
        if !self.budget.coverage_complete() {
            return false;
        }
        let mut approved = self.approved.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        approved.clear();
        approved.extend(stores.iter().map(|store| store.addr));
        self.scan_complete.store(true, std::sync::atomic::Ordering::Release);
        true
    }
}

fn validate_full_block<M: forever_patcher::MemoryAccess>(mem: &M, block: u64, c: &forever_patcher::KeyStoreContract) -> io::Result<Option<bool>> {
        let mut buf = vec![0u8; c.block_size()];
    mem.read(block, &mut buf)?;
    let mut already = false;
    for i in 0..c.entry_count {
        let base = i * c.entry_size;
        let id = u32::from_le_bytes(buf[base..base + 4].try_into().map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "store id out of range"))?);
        if id != i as u32 + 1 || buf[base + 4 + forever_patcher::KEY_LEN] != c.expected_flags[i] {
            return Ok(None);
        }
        let pad: [u8; 3] = buf[base + 4 + forever_patcher::KEY_LEN + 1..base + c.entry_size].try_into().map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "store padding out of range"))?;
        if !c.accepts_padding(&pad) { return Ok(None); }
        let key = &buf[base + 4..base + 4 + forever_patcher::KEY_LEN];
        if i + 1 == c.target_group {
            if key == c.new_key { already = true; }
            else if key != c.known_keys[i] { return Ok(None); }
        } else if key != c.known_keys[i] { return Ok(None); }
    }
    Ok(Some(already))
}

impl<M: forever_patcher::MemoryAccess> forever_patcher::MemoryAccess for GuardedMemory<'_, M> {
    fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { self.inner.regions() }

    fn read(&self, addr: u64, buf: &mut [u8]) -> io::Result<()> {
        if !self.inner_budgets_reads {
            self.budget.reserve(buf.len())?;
        }
        match self.inner.read(addr, buf) {
            Ok(()) => Ok(()),
            Err(error) => {
                if !self.inner_budgets_reads {
                    self.budget.record_read_error(&error);
                }
                Err(error)
            }
        }
    }

    fn write(&self, addr: u64, data: &[u8]) -> io::Result<()> {
        if data.len() != forever_patcher::KEY_LEN || data != self.contract.new_key {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "guard rejected non-target write"));
        }
        if !self.scan_complete.load(std::sync::atomic::Ordering::Acquire) || !self.budget.coverage_complete() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "guard rejected write without a complete scan in this pass"));
        }
        let offset = ((self.contract.target_group - 1) * self.contract.entry_size + 4) as u64;
        let block = addr.checked_sub(offset).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "guard rejected target address"))?;
        if !self.approved.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).contains(&block) {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "guard rejected unapproved store target"));
        }
        match validate_full_block(self, block, self.contract)? {
            Some(true) => return Err(io::Error::new(io::ErrorKind::InvalidData, "guard rejected already-patched block")),
            Some(false) => {}
            None => return Err(io::Error::new(io::ErrorKind::InvalidData, "guard rejected changed store block")),
        }
        self.budget.record_write_attempt();
        if let Err(error) = self.inner.write(addr, data) {
            self.budget.record_mutation_unknown();
            return Err(error);
        }
        let mut readback = [0u8; forever_patcher::KEY_LEN];
        if let Err(error) = self.read(addr, &mut readback) {
            self.budget.record_mutation_unknown();
            return Err(error);
        }
        if readback != data {
            self.budget.record_mutation_unknown();
            return Err(io::Error::new(io::ErrorKind::InvalidData, "target key read-back mismatch"));
        }
        self.budget.record_committed(data.len());
        Ok(())
    }
}

fn validate_production_contract(c: &forever_patcher::KeyStoreContract) -> Result<(), String> {
    if c.entry_size != 40 || c.entry_count != 12 || c.target_group != 8 || c.anchor_group != 1 {
        return Err("contract is not the canonical Forever group-8 contract".to_string());
    }
    Ok(())
}

fn load_canonical_contract(path: &str) -> Result<forever_patcher::KeyStoreContract, String> {
    let path = Path::new(path);
    let metadata = std::fs::metadata(path).map_err(|e| format!("contract metadata failed: {e}"))?;
    if metadata.len() > MAX_CONTRACT_BYTES as u64 {
        return Err("contract exceeds the 64 KiB bound".to_string());
    }
    let mut file = File::open(path).map_err(|e| format!("contract cannot be opened: {e}"))?;
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.take(MAX_CONTRACT_BYTES as u64 + 1).read_to_end(&mut bytes).map_err(|e| format!("contract read failed: {e}"))?;
    if bytes.len() > MAX_CONTRACT_BYTES {
        return Err("contract exceeds the 64 KiB bound".to_string());
    }
    if bytes != CANONICAL_CONTRACT.as_bytes() {
        return Err("contract does not exactly match the embedded canonical contract".to_string());
    }
    load_contract_from_json(CANONICAL_CONTRACT).map_err(|e| e.to_string())
}

fn result_json(status: &str, pid: u32, start_ticks: u64, stores: usize, bytes_written: u64, budget: Option<&WorkBudget>, error: Option<(&str, String)>) -> String {
    let mut out = Map::new();
    out.insert("version".to_string(), json!(RESULT_VERSION));
    out.insert("status".to_string(), json!(status));
    out.insert("pid".to_string(), json!(pid));
    out.insert("startTicks".to_string(), json!(start_ticks));
    out.insert("stores".to_string(), json!(stores));
    out.insert("bytesWritten".to_string(), json!(budget.map_or(bytes_written, WorkBudget::committed_bytes)));
    if let Some(b) = budget {
        out.insert("coverage".to_string(), json!({"readBytes": b.bytes(), "readOps": b.ops(), "readErrors": b.read_errors(), "permissionDeniedReads": b.permission_errors(), "timeoutMs": SCAN_TIMEOUT.as_millis(), "maxReadBytes": SCAN_MAX_BYTES, "maxReadOps": SCAN_MAX_OPS, "complete": b.coverage_complete()}));
        out.insert("writeAttempts".to_string(), json!(b.write_attempts()));
        out.insert("possiblePartialWrites".to_string(), json!(b.possible_partial_writes()));
        out.insert("mutationUnknown".to_string(), json!(b.mutation_unknown()));
    }
    if let Some((kind, detail)) = error {
        out.insert("errorKind".to_string(), json!(kind));
        out.insert("error".to_string(), json!(detail));
    }
    Value::Object(out).to_string()
}

fn io_kind(error: &io::Error) -> &'static str {
    if error.kind() == io::ErrorKind::TimedOut || error.raw_os_error() == Some(libc::ETIMEDOUT) {
        "timeout"
    } else {
        match error.raw_os_error() {
            Some(libc::EACCES | libc::EPERM) => "permission_denied",
            Some(libc::ENOENT | libc::ESRCH) => "not_found",
            _ => "memory_access",
        }
    }
}

fn inspect_owned(pid: i32, start_ticks: i64, executable_path: &str, contract_path: &str, apply: bool) -> String {
    let pid_u = match u32::try_from(pid) { Ok(p) if p > 0 => p, _ => return result_json("REJECTED", 0, 0, 0, 0, None, Some(("identity", "invalid pid".to_string()))) };
    let ticks = match u64::try_from(start_ticks) { Ok(t) => t, _ => return result_json("REJECTED", pid_u, 0, 0, 0, None, Some(("identity", "invalid startTicks".to_string()))) };
    let executable = match canonical_executable(executable_path) {
        Ok(p) => p,
        Err(e) => return result_json("REJECTED", pid_u, ticks, 0, 0, None, Some(("wrong_executable", e))),
    };
    if let Err(e) = verify_pe_aa64(&executable) {
        return result_json("REJECTED", pid_u, ticks, 0, 0, None, Some(("wrong_executable", e)));
    }
    let contract = match load_canonical_contract(contract_path) {
        Ok(c) => c,
        Err(e) => return result_json("REJECTED", pid_u, ticks, 0, 0, None, Some(("contract", e))),
    };
    if let Err(e) = validate_production_contract(&contract) {
        return result_json("REJECTED", pid_u, ticks, 0, 0, None, Some(("contract", e)));
    }
    let procfs = ProcFs::host();
    let current_pid = std::process::id();
    let uid = current_uid();
    let budget = WorkBudget::new();
    let _apply_guard = apply.then(|| APPLY_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner()));
    let identity = match validate_owned_identity(&procfs, current_pid, uid, pid_u, ticks, &executable, &budget) {
        Ok(i) => i,
        Err(e) => {
            if budget.read_errors() > 0 || budget.exhausted() {
                return result_json("INCOMPLETE", pid_u, ticks, 0, 0, Some(&budget), Some(("read_coverage", e.detail().to_string())));
            }
            return result_json("REJECTED", pid_u, ticks, 0, 0, None, Some((e.kind(), e.detail().to_string())));
        }
    };
    if !budget.coverage_complete() {
        return result_json("INCOMPLETE", pid_u, ticks, 0, 0, Some(&budget), Some(("read_coverage", "identity coverage was incomplete".to_string())));
    }
    let memory = match PinnedProcessMemory::open(identity, apply, budget.clone()) {
        Ok(m) => m,
        Err(e) => return result_json("INCOMPLETE", pid_u, ticks, 0, 0, Some(&budget), Some((io_kind(&e), e.to_string()))),
    };
    let guarded = GuardedMemory::with_budgeted_inner(&memory, &contract, budget.clone());
    if let Err(e) = memory.revalidate() {
        return result_json("REJECTED", pid_u, ticks, 0, 0, Some(&budget), Some((e.kind(), e.detail().to_string())));
    }
    let found = match forever_patcher::scan_stores(&guarded, &contract) {
        Ok(found) => found,
        Err(e) => {
            let status = if !budget.coverage_complete() { "INCOMPLETE" } else { "ERROR" };
            return result_json(status, pid_u, ticks, 0, 0, Some(&budget), Some((io_kind(&e), e.to_string())));
        }
    };
    if !budget.coverage_complete() {
        let kind = if budget.exhausted() { "read_budget" } else if budget.permission_errors() > 0 { "permission_denied" } else { "read_coverage" };
        return result_json("INCOMPLETE", pid_u, ticks, found.len(), 0, Some(&budget), Some((kind, "complete read-only coverage was not established".to_string())));
    }
    if let Err(e) = memory.revalidate() {
        return result_json("REJECTED", pid_u, ticks, found.len(), 0, Some(&budget), Some((e.kind(), e.detail().to_string())));
    }
    if !budget.coverage_complete() {
        return result_json("INCOMPLETE", pid_u, ticks, found.len(), 0, Some(&budget), Some(("read_coverage", "identity changed during capture".to_string())));
    }
    if found.is_empty() {
        return result_json("NOT_FOUND", pid_u, ticks, 0, 0, Some(&budget), Some(("store_not_found", "no complete compatible key store was found".to_string())));
    }
    if !apply {
        let all_patched = found.iter().all(|store| store.already_patched);
        return result_json(if all_patched { "ALREADY_PATCHED" } else { "VALIDATED" }, pid_u, ticks, found.len(), 0, Some(&budget), None);
    }
    if !guarded.approve_complete(&found) {
        return result_json("INCOMPLETE", pid_u, ticks, found.len(), 0, Some(&budget), Some(("read_coverage", "approved target set lacks a complete scan".to_string())));
    }
    for store in found.iter().filter(|store| !store.already_patched) {
        if let Err(error) = guarded.write(contract.target_key_addr(store.addr), &contract.new_key) {
            if budget.mutation_unknown() {
                return result_json("INCOMPLETE", pid_u, ticks, found.len(), 0, Some(&budget), Some(("mutation_unknown", error.to_string())));
            }
            let kind = if error.kind() == io::ErrorKind::InvalidData { "identity" } else { io_kind(&error) };
            return result_json(if budget.committed_bytes() > 0 { "REJECTED" } else { "REJECTED" }, pid_u, ticks, found.len(), 0, Some(&budget), Some((kind, error.to_string())));
        }
    }
    if !budget.coverage_complete() {
        return result_json("INCOMPLETE", pid_u, ticks, found.len(), 0, Some(&budget), Some(("read_coverage", "coverage became incomplete during apply".to_string())));
    }
    if budget.mutation_unknown() {
        return result_json("INCOMPLETE", pid_u, ticks, found.len(), 0, Some(&budget), Some(("mutation_unknown", "a write or read-back may have partially mutated memory".to_string())));
    }
    let all_patched = found.iter().all(|store| store.already_patched);
    result_json(if all_patched { "ALREADY_PATCHED" } else { "PATCHED" }, pid_u, ticks, found.len(), 0, Some(&budget), None)
}

fn jni_text<'local>(env: &mut JNIEnv<'local>, value: String) -> JString<'local> {
    match env.new_string(value) {
        Ok(value) => value,
        Err(_) => unsafe { JString::from_raw(std::ptr::null_mut()) },
    }
}

fn jni_input(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, String> {
    env.get_string(value).map(|s| s.into()).map_err(|_| "JNI String argument is unreadable".to_string())
}

/// `com.wowforever.launcher.ForeverPatchBridge.findOwnedGameProcesses`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_wowforever_launcher_ForeverPatchBridge_findOwnedGameProcesses<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, executable_path: JString<'local>,
) -> JString<'local> {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        jni_input(&mut env, &executable_path).map(|path| find_owned(&path)).unwrap_or_else(|e| find_json(Vec::new(), 0, 0, Some(("jni", e))))
    })).unwrap_or_else(|_| find_json(Vec::new(), 0, 0, Some(("panic", "native bridge aborted safely".to_string()))));
    jni_text(&mut env, out)
}

/// `com.wowforever.launcher.ForeverPatchBridge.inspectOwnedGame`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_wowforever_launcher_ForeverPatchBridge_inspectOwnedGame<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, pid: jint, start_ticks: jlong,
    executable_path: JString<'local>, contract_path: JString<'local>, apply: jboolean,
) -> JString<'local> {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let executable = jni_input(&mut env, &executable_path);
        let contract = jni_input(&mut env, &contract_path);
        match (executable, contract) {
            (Ok(executable), Ok(contract)) => inspect_owned(pid, start_ticks, &executable, &contract, apply != 0),
            (Err(e), _) | (_, Err(e)) => result_json("ERROR", pid.max(0) as u32, start_ticks.max(0) as u64, 0, 0, None, Some(("jni", e))),
        }
    })).unwrap_or_else(|_| result_json("ERROR", pid.max(0) as u32, start_ticks.max(0) as u64, 0, 0, None, Some(("panic", "native bridge aborted safely".to_string()))));
    jni_text(&mut env, out)
}

/// `com.wowforever.launcher.ForeverPatchBridge.armSignalTrace`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_wowforever_launcher_ForeverPatchBridge_armSignalTrace<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, output_path: JString<'local>,
    run_id: JString<'local>, boot_id: JString<'local>, expected_pid: jint,
    expected_start_ticks: jlong,
) -> JString<'local> {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let output = jni_input(&mut env, &output_path);
        let run = jni_input(&mut env, &run_id);
        let boot = jni_input(&mut env, &boot_id);
        match (output, run, boot) {
            (Ok(output), Ok(run), Ok(boot)) => signal_trace::arm_signal_trace(&output, &run, &boot, expected_pid, expected_start_ticks),
            _ => signal_trace::arm_signal_trace("", "", "", -1, -1),
        }
    })).unwrap_or_else(|_| signal_trace::arm_signal_trace("", "", "", -1, -1));
    jni_text(&mut env, out)
}

/// `com.wowforever.launcher.ForeverPatchBridge.signalTraceStatus`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_wowforever_launcher_ForeverPatchBridge_signalTraceStatus<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>,
) -> JString<'local> {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(signal_trace::signal_trace_status))
        .unwrap_or_else(|_| String::from("{\"version\":1,\"status\":\"ERROR\",\"reason\":\"panic\"}"));
    jni_text(&mut env, out)
}

/// Read-only functional PoC session.  This is deliberately a separate JNI
/// surface from `ForeverPatchBridge`: it has no PID/address input and no
/// apply/write operation.
#[no_mangle]
pub unsafe extern "system" fn Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativeBegin<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, executable_path: JString<'local>,
    nonce: JString<'local>, contract_path: JString<'local>, code_capture_enabled: jboolean,
) -> JString<'local> {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let executable = jni_input(&mut env, &executable_path);
        let nonce = jni_input(&mut env, &nonce);
        let contract = jni_input(&mut env, &contract_path);
        match (executable, nonce, contract) {
            (Ok(executable), Ok(nonce), Ok(contract)) => functional_session::begin(&executable, &nonce, &contract, code_capture_enabled != 0),
            _ => json!({"version": 1, "status": "ERROR", "errorKind": "jni", "error": "JNI String argument is unreadable"}).to_string(),
        }
    })).unwrap_or_else(|_| json!({"version": 1, "status": "ERROR", "errorKind": "panic", "error": "functional begin aborted safely"}).to_string());
    jni_text(&mut env, out)
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativePoll<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, token: JString<'local>,
) -> JString<'local> {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        jni_input(&mut env, &token).map(|token| functional_session::poll(&token)).unwrap_or_else(|error| {
            json!({"version": 1, "status": "ERROR", "errorKind": "jni", "error": error}).to_string()
        })
    })).unwrap_or_else(|_| json!({"version": 1, "status": "ERROR", "errorKind": "panic", "error": "functional poll aborted safely"}).to_string());
    jni_text(&mut env, out)
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativeEnd<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, token: JString<'local>,
) -> JString<'local> {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        jni_input(&mut env, &token).map(|token| functional_session::end(&token)).unwrap_or_else(|error| {
            json!({"version": 1, "status": "ERROR", "errorKind": "jni", "error": error}).to_string()
        })
    })).unwrap_or_else(|_| json!({"version": 1, "status": "ERROR", "errorKind": "panic", "error": "functional end aborted safely"}).to_string());
    jni_text(&mut env, out)
}

/// Guarded group-8 key apply for the functional PoC session. Takes only the
/// lease token: no PID, address, or key bytes cross the bridge. Returns
/// stable JSON with `status` one of `APPLIED` (32), `ALREADY_PATCHED` (0),
/// `DENIED` (0), or `ERROR` (real bytes), plus honest `readOnly`/`writeAuthorized`.
#[no_mangle]
pub unsafe extern "system" fn Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativeApply<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, token: JString<'local>,
) -> JString<'local> {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        jni_input(&mut env, &token).map(|token| functional_session::apply(&token)).unwrap_or_else(|error| {
            json!({"version": 1, "status": "ERROR", "errorKind": "jni", "error": error}).to_string()
        })
    })).unwrap_or_else(|_| json!({"version": 1, "status": "ERROR", "errorKind": "panic", "error": "functional apply aborted safely"}).to_string());
    jni_text(&mut env, out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::os::unix::fs::symlink;
    static PROCESS_MEMORY_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    #[test]
    fn resume_el_contrato_real_del_repo() {
        let json = include_str!("../../../forever/contract.json");
        let summary = summarize_contract_json(json).expect("el contrato del repo debe validar");
        assert!(summary.contains("entries=12"), "resumen: {summary}");
        assert!(summary.contains("target_group=8"), "resumen: {summary}");
        assert!(summary.contains("anchor_group=1"), "resumen: {summary}");
    }

    #[test]
    fn json_malformado_da_error_controlado() {
        let err = summarize_contract_json("{no es json").unwrap_err();
        assert!(!err.is_empty());
    }

    #[test]
    fn autotest_cross_proceso_ok_en_linux() {
        let _lock = PROCESS_MEMORY_TEST_LOCK.lock().unwrap();
        if !cfg!(any(target_os = "linux", target_os = "android")) {
            eprintln!("SKIP: el autotest necesita Linux/Android");
            return;
        }
        let json = include_str!("../../../forever/contract.json");
        let out = super::cross_process_self_test(json);
        assert!(out.starts_with("result=OK"), "autotest: {out}");
        assert!(out.contains("child_confirm=PATCHED"), "autotest: {out}");
    }

    #[test]
    fn parsea_stat_con_comm_que_contiene_parentesis() {
        let mut text = "42 (Wine) Wow) S 100".to_string();
        for n in 0..17 { text.push_str(&format!(" {}", n + 1)); }
        // fields[19] is field 22 (starttime).
        text.push_str(" 987654\n");
        let stat = parse_proc_stat(&text).expect("stat");
        assert_eq!(stat.ppid, 100);
        assert_eq!(stat.start_ticks, 987654);
    }

    fn proc_fixture() -> (PathBuf, PathBuf) {
        let root = std::env::temp_dir().join(format!("forever-jni-proc-{}-{}", std::process::id(), Instant::now().elapsed().as_nanos()));
        let exe = root.join("game/WowB-ARM64.exe");
        fs::create_dir_all(exe.parent().unwrap()).unwrap();
        fs::write(&exe, b"fixture").unwrap();
        (root, exe)
    }

    fn add_proc(root: &Path, pid: u32, ppid: u32, ticks: u64, uid: u32, exe: &Path, map: &Path) {
        let dir = root.join(pid.to_string());
        fs::create_dir_all(&dir).unwrap();
        let mut stat = format!("{pid} (game) S {ppid}");
        for n in 0..17 { stat.push_str(&format!(" {}", n + 1)); }
        stat.push_str(&format!(" {ticks}\n"));
        fs::write(dir.join("stat"), stat).unwrap();
        fs::write(dir.join("status"), format!("Name:\tgame\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n")).unwrap();
        symlink(exe, dir.join("exe")).unwrap();
        let inode = fs::metadata(map).map(|metadata| metadata.ino()).unwrap_or(0);
        fs::write(dir.join("maps"), format!("1000-2000 r-xp 00001000 00:00 {inode} {}\n", map.display())).unwrap();
    }

    #[test]
    fn identity_rechaza_self_unrelated_stale_reused_y_exe_incorrecto() {
        let (root, exe) = proc_fixture();
        let good_map = exe.clone();
        fs::write(root.join("other.exe"), b"other").unwrap();
        add_proc(&root, 200, 100, 55, 42, &exe, &good_map);
        add_proc(&root, 300, 1, 66, 42, &exe, &good_map);
        add_proc(&root, 400, 100, 77, 42, &exe, &good_map);
        add_proc(&root, 500, 100, 88, 42, &root.join("other.exe"), &root.join("other.exe"));
        add_proc(&root, 600, 100, 99, 42, &root.join("other.exe"), &exe);
        add_proc(&root, 700, 100, 111, 42, &root.join("other.exe"), &exe);
        let exe_inode = fs::metadata(&exe).unwrap().ino();
        fs::write(root.join("700/maps"), format!("1000-2000 r--p 00001000 00:00 {exe_inode} {}\n", exe.display())).unwrap();
        let procfs = ProcFs { root: root.clone() };
        let budget = WorkBudget::new();
        assert!(validate_owned_identity(&procfs, 100, 42, 200, 55, &exe, &budget).is_ok());
        assert!(matches!(validate_owned_identity(&procfs, 100, 42, 100, 55, &exe, &budget), Err(IdentityError::Rejected(_))));
        assert!(matches!(validate_owned_identity(&procfs, 100, 42, 300, 66, &exe, &budget), Err(IdentityError::Rejected(_))));
        assert!(matches!(validate_owned_identity(&procfs, 100, 42, 400, 999, &exe, &budget), Err(IdentityError::Rejected(_))));
        assert!(matches!(validate_owned_identity(&procfs, 100, 42, 500, 88, &exe, &budget), Err(IdentityError::Rejected(_))));
        assert!(validate_owned_identity(&procfs, 100, 42, 600, 99, &exe, &budget).is_ok(), "exact PE mapping is an accepted identity proof");
        assert!(matches!(validate_owned_identity(&procfs, 100, 42, 700, 111, &exe, &budget), Err(IdentityError::Rejected(_))), "r-- data mapping is not an executable identity proof");
        fs::remove_dir_all(root).unwrap();
    }

    fn store_memory() -> (forever_patcher::KeyStoreContract, forever_patcher::SliceMemory) {
        let c = load_contract_from_json(include_str!("../../../forever/contract.json")).unwrap();
        let mut bytes = vec![0u8; c.block_size()];
        for i in 0..c.entry_count {
            let off = i * c.entry_size;
            bytes[off..off + 4].copy_from_slice(&((i + 1) as u32).to_le_bytes());
            bytes[off + 4..off + 4 + 32].copy_from_slice(&c.known_keys[i]);
            bytes[off + 36] = c.expected_flags[i];
            bytes[off + 37..off + 40].copy_from_slice(&c.accepted_paddings[0]);
        }
        (c, forever_patcher::SliceMemory::new(0x1000, bytes))
    }

    #[test]
    fn read_only_pass_no_escribe_y_guard_rechaza_bloque_cambiado() {
        let _lock = PROCESS_MEMORY_TEST_LOCK.lock().unwrap();
        let (c, mem) = store_memory();
        let before = mem.snapshot();
        let budget = WorkBudget::new();
        let guard = GuardedMemory::new(&mem, &c, budget);
        let found = forever_patcher::scan_stores(&guard, &c).unwrap();
        assert_eq!(found.len(), 1);
        assert!(!found[0].already_patched);
        assert_eq!(before, mem.snapshot(), "scan-only must not write");

        let target = c.target_key_addr(0x1000);
        guard.approve_complete(&found);
        guard.write(target, &c.new_key).unwrap();
        let after = mem.snapshot();
        let target_off = (c.target_group - 1) * c.entry_size + 4;
        assert_eq!(&after[target_off..target_off + 32], &c.new_key);
        assert_eq!(&after[4..36], &before[4..36], "anchor remains untouched");

        let (c2, mem2) = store_memory();
        let guard2 = GuardedMemory::new(&mem2, &c2, WorkBudget::new());
        let found2 = forever_patcher::scan_stores(&guard2, &c2).unwrap();
        guard2.approve_complete(&found2);
        mem2.write(0x1000 + 40 + 4, &[0u8; 32]).unwrap();
        let err = guard2.write(c2.target_key_addr(0x1000), &c2.new_key).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }

    struct ReadErrorMemory {
        inner: forever_patcher::SliceMemory,
    }

    impl forever_patcher::MemoryAccess for ReadErrorMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> {
            let mut regions = self.inner.regions()?;
            regions.push(forever_patcher::Region { start: 0x5000, end: 0x5010 });
            Ok(regions)
        }

        fn read(&self, addr: u64, buf: &mut [u8]) -> io::Result<()> {
            if addr >= 0x5000 {
                return Err(io::Error::new(io::ErrorKind::PermissionDenied, "synthetic read failure"));
            }
            self.inner.read(addr, buf)
        }

        fn write(&self, addr: u64, data: &[u8]) -> io::Result<()> { self.inner.write(addr, data) }
    }

    struct PartialWriteMemory {
        inner: forever_patcher::SliceMemory,
    }

    impl forever_patcher::MemoryAccess for PartialWriteMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { self.inner.regions() }
        fn read(&self, addr: u64, buf: &mut [u8]) -> io::Result<()> { self.inner.read(addr, buf) }
        fn write(&self, addr: u64, data: &[u8]) -> io::Result<()> {
            self.inner.write(addr, &data[..8])?;
            Err(io::Error::new(io::ErrorKind::Other, "synthetic partial write"))
        }
    }

    #[test]
    fn read_error_after_valid_store_forces_incomplete_and_forbids_write() {
        let _lock = PROCESS_MEMORY_TEST_LOCK.lock().unwrap();
        let (contract, base_memory) = store_memory();
        let memory = ReadErrorMemory { inner: base_memory };
        let guard = GuardedMemory::new(&memory, &contract, WorkBudget::new());
        let found = forever_patcher::scan_stores(&guard, &contract).unwrap();
        assert_eq!(found.len(), 1);
        assert!(!guard.approve_complete(&found));
        let error = guard.write(contract.target_key_addr(0x1000), &contract.new_key).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert_eq!(guard.budget.write_attempts(), 0);
    }

    #[test]
    fn budget_failure_before_write_is_sticky() {
        let (contract, memory) = store_memory();
        let budget = WorkBudget::new();
        budget.exhausted.store(true, std::sync::atomic::Ordering::Relaxed);
        let guard = GuardedMemory::new(&memory, &contract, budget);
        assert!(!guard.approve_complete(&[]));
        let error = guard.write(contract.target_key_addr(0x1000), &contract.new_key).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert_eq!(guard.budget.write_attempts(), 0);
    }

    #[test]
    fn legacy_block_validator_shares_free_first_padding_byte_rule() {
        // El validador heredado de `GuardedMemory` usa la misma regla que el
        // núcleo: primer byte de la cola libre (`070000`/`ff0000`/`420000` más
        // históricas), bytes 38/39 a cero.
        let (contract, _) = store_memory();
        let mut bytes = vec![0u8; contract.block_size()];
        let observed: [[u8; 3]; 6] = [
            [0x07, 0x00, 0x00],
            [0xff, 0x00, 0x00],
            [0x42, 0x00, 0x00],
            [0x00, 0x00, 0x00],
            [0x7f, 0x00, 0x00],
            [0x07, 0x00, 0x00],
        ];
        for i in 0..contract.entry_count {
            let off = i * contract.entry_size;
            bytes[off..off + 4].copy_from_slice(&((i + 1) as u32).to_le_bytes());
            bytes[off + 4..off + 4 + 32].copy_from_slice(&contract.known_keys[i]);
            bytes[off + 36] = contract.expected_flags[i];
            bytes[off + 37..off + 40].copy_from_slice(&observed[i % observed.len()]);
        }
        let memory = forever_patcher::SliceMemory::new(0x1000, bytes);
        assert_eq!(validate_full_block(&memory, 0x1000, &contract).unwrap(), Some(false));
        let mut bad = memory.snapshot();
        bad[3 * contract.entry_size + 38] = 0x01;
        let bad_memory = forever_patcher::SliceMemory::new(0x1000, bad);
        assert_eq!(validate_full_block(&bad_memory, 0x1000, &contract).unwrap(), None);
    }

    #[test]
    fn pinned_read_is_accounted_once_through_guard() {
        let (contract, _) = store_memory();
        let path = std::env::temp_dir().join(format!("forever-pinned-budget-{}", std::process::id()));
        fs::write(&path, b"fixture").unwrap();
        let budget = WorkBudget::new();
        let memory = PinnedProcessMemory {
            identity: OwnedIdentity {
                pid: 200, start_ticks: 55, current_pid: 100, current_uid: 42,
                executable: PathBuf::from("/fixture/WowB-ARM64.exe"),
            },
            procfs: ProcFs::host(), budget: budget.clone(),
            file: Mutex::new(File::open(&path).unwrap()),
        };
        let guard = GuardedMemory::with_budgeted_inner(&memory, &contract, budget.clone());
        let mut bytes = [0u8; 7];
        guard.read(0, &mut bytes).unwrap();
        assert_eq!(&bytes, b"fixture");
        assert_eq!(budget.bytes(), 7);
        assert_eq!(budget.ops(), 1);
        guard.read(7, &mut bytes).unwrap_err();
        assert_eq!(budget.read_errors(), 1);
        assert!(!budget.coverage_complete());
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn missing_exe_link_marks_discovery_coverage_incomplete() {
        let root = std::env::temp_dir().join(format!("forever-exe-race-{}", std::process::id()));
        fs::create_dir_all(root.join("200")).unwrap();
        let procfs = ProcFs { root: root.clone() };
        let budget = WorkBudget::new();
        assert!(procfs.exe_matches(200, Path::new("/fixture/WowB-ARM64.exe"), &budget).is_err());
        assert_eq!(budget.read_errors(), 1);
        assert!(!budget.coverage_complete());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn tampered_contract_is_rejected_before_core_load() {
        let path = std::env::temp_dir().join(format!("forever-contract-tampered-{}", std::process::id()));
        let mut tampered = CANONICAL_CONTRACT.as_bytes().to_vec();
        tampered.push(b' ');
        fs::write(&path, tampered).unwrap();
        let result = load_canonical_contract(path.to_str().unwrap());
        assert!(result.is_err());
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn partial_write_reports_unknown_mutation_and_committed_count() {
        let _lock = PROCESS_MEMORY_TEST_LOCK.lock().unwrap();
        let (contract, base_memory) = store_memory();
        let memory = PartialWriteMemory { inner: base_memory };
        let guard = GuardedMemory::new(&memory, &contract, WorkBudget::new());
        let found = forever_patcher::scan_stores(&guard, &contract).unwrap();
        assert!(guard.approve_complete(&found));
        let error = guard.write(contract.target_key_addr(0x1000), &contract.new_key).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert_eq!(guard.budget.write_attempts(), 1);
        assert_eq!(guard.budget.committed_bytes(), 0);
        assert_eq!(guard.budget.possible_partial_writes(), 1);
        assert!(guard.budget.mutation_unknown());
        let result = result_json("INCOMPLETE", 1, 1, 1, 0, Some(&guard.budget), Some(("mutation_unknown", "partial".to_string())));
        assert!(result.contains("\"mutationUnknown\":true"));
        assert!(result.contains("\"possiblePartialWrites\":1"));
    }
}
