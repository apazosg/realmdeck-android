//! `store_holder`: proceso auxiliar SOLO para el test de integración
//! (`tests/process_integration.rs`). No es parte del producto.
//!
//! Reserva memoria anónima `PROT_READ|PROT_WRITE` (no ejecutable) con `mmap`,
//! construye en ella un almacén de claves válido con las 12 claves del
//! contrato (relleno `000000`) e informa de su estado por stdout:
//!
//! - primera línea: su PID (para que el padre abra `/proc/<pid>/mem`);
//! - después, cada 500 ms: `PATCHED` o `PENDING` según si la clave del grupo
//!   objetivo ya es `new_key` (lectura volátil: el parcheo lo hace OTRO
//!   proceso y el compilador no puede cachear el valor).
//!
//! Termina cuando el padre cierra su stdin (tubería rota/EOF) o al recibir
//! SIGTERM (acción por defecto: terminar, sin manejador propio).

use forever_patcher::load_contract;
use std::io::Write as _;

fn contract_path_from_args() -> String {
    let argv: Vec<String> = std::env::args().collect();
    if let Some(i) = argv.iter().position(|a| a == "--contract") {
        if let Some(p) = argv.get(i + 1) {
            return p.clone();
        }
        eprintln!("error: --contract necesita una ruta");
        std::process::exit(2);
    }
    // Se acepta también como primer argumento posicional.
    if let Some(p) = argv.get(1) {
        if !p.starts_with('-') {
            return p.clone();
        }
    }
    if let Ok(env) = std::env::var("WOWFOREVER_CONTRACT") {
        if !env.is_empty() {
            return env;
        }
    }
    "forever/contract.json".to_string()
}

fn main() {
    let contract_path = contract_path_from_args();
    let contract = load_contract(&contract_path).unwrap_or_else(|e| {
        eprintln!("store_holder: error: {e}");
        std::process::exit(2);
    });

    let block_size = contract.block_size();
    let alloc_size = block_size.max(4096);

    // `mmap` anónimo, solo lectura/escritura: el equivalente del SO a
    // `VirtualAlloc(MEM_COMMIT, PAGE_READWRITE)`. Sin `PROT_EXEC`.
    let ptr = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            alloc_size,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
            -1,
            0,
        )
    };
    if ptr == libc::MAP_FAILED {
        eprintln!(
            "store_holder: mmap falló: {}",
            std::io::Error::last_os_error()
        );
        std::process::exit(1);
    }
    let base = ptr as *mut u8;

    // Construye el almacén válido con relleno `000000` (una de las variantes
    // que acepta el contrato; la de Wine/Linux).
    let padding = [0x00u8, 0x00, 0x00];
    debug_assert!(
        contract.accepts_padding(&padding),
        "el contrato debería aceptar el relleno 000000"
    );
    for i in 0..contract.entry_count {
        let entry_off = i * contract.entry_size;
        unsafe {
            // id u32 LE (grupos 1..12 en orden).
            let id = (i as u32) + 1;
            std::ptr::write_volatile(base.add(entry_off), (id & 0xFF) as u8);
            std::ptr::write_volatile(base.add(entry_off + 1), ((id >> 8) & 0xFF) as u8);
            std::ptr::write_volatile(base.add(entry_off + 2), ((id >> 16) & 0xFF) as u8);
            std::ptr::write_volatile(base.add(entry_off + 3), ((id >> 24) & 0xFF) as u8);
            for (j, b) in contract.known_keys[i].iter().enumerate() {
                std::ptr::write_volatile(base.add(entry_off + 4 + j), *b);
            }
            std::ptr::write_volatile(
                base.add(entry_off + 4 + 32),
                contract.expected_flags[i],
            );
            for (j, b) in padding.iter().enumerate() {
                std::ptr::write_volatile(base.add(entry_off + 4 + 32 + 1 + j), *b);
            }
        }
    }

    // Pone el stdin en no bloqueante para detectar el EOF sin quedarse
    // colgado en `read` cuando el padre cierra la tubería.
    unsafe {
        let flags = libc::fcntl(0, libc::F_GETFL);
        if flags >= 0 {
            libc::fcntl(0, libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
    }

    println!("{}", std::process::id());
    std::io::stdout().flush().expect("flush del PID");

    let target_off = (contract.target_group - 1) * contract.entry_size + 4;
    let mut stdin_buf = [0u8; 64];
    loop {
        // Espera hasta 500 ms vigilando el stdin: si hay datos o cuelgue,
        // se comprueba si es EOF (padre cerró la tubería) para salir.
        let mut pfd = libc::pollfd {
            fd: 0,
            events: libc::POLLIN | libc::POLLHUP,
            revents: 0,
        };
        unsafe {
            libc::poll(&mut pfd, 1, 500);
        }
        if pfd.revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR | libc::POLLNVAL) != 0
        {
            let n = unsafe {
                libc::read(
                    0,
                    stdin_buf.as_mut_ptr().cast::<libc::c_void>(),
                    stdin_buf.len(),
                )
            };
            if n == 0 {
                // EOF o HUP sin datos: el padre cerró stdin → salida limpia.
                break;
            }
            // n > 0: se ignora el contenido (solo importa seguir vivo).
            // n < 0 (EAGAIN): no había nada que leer, se sigue.
        }

        // Lectura volátil: otro proceso puede haber reescrito la clave con
        // `/proc/<pid>/mem` y el compilador no debe reutilizar un valor
        // cacheado en registro.
        let mut current = [0u8; 32];
        for (j, slot) in current.iter_mut().enumerate() {
            *slot = unsafe { std::ptr::read_volatile(base.add(target_off + j)) };
        }
        if current == contract.new_key {
            println!("PATCHED");
        } else {
            println!("PENDING");
        }
        if std::io::stdout().flush().is_err() {
            // Tubería de stdout rota (el padre ya no lee): salir.
            break;
        }
    }

    unsafe {
        libc::munmap(ptr, alloc_size);
    }
}
