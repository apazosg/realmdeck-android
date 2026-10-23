//! Integración cross-proceso real: el patcher contra un proceso vivo vía
//! `/proc/<pid>/mem`, sin juego ni `sudo`.
//!
//! Qué valida exactamente:
//! 1. El hijo (`store_holder`, descendiente del test) publica un almacén de
//!    claves válido en memoria anónima `rw-p` construida con el contrato real.
//! 2. `scan_stores` con [`LinuxProcessMemory`] encuentra EXACTAMENTE un
//!    almacén en el otro proceso.
//! 3. `patch_stores` parchea (`already_patched = false`) escribiendo solo por
//!    `/proc/<pid>/mem`.
//! 4. El HIJO observa `new_key` en su propia memoria e imprime `PATCHED`
//!    (prueba de que la escritura cross-proceso es visible en el objetivo).
//! 5. Una segunda pasada es idempotente (`already_patched = true`).
//!
//! Comunicación padre↔hijo: el padre lanza al hijo con stdin/stdout por
//! tubería; el hijo imprime su PID en la primera línea y luego una línea
//! `PENDING`/`PATCHED` cada 500 ms (todo con flush). El padre cierra el stdin
//! para pedirle al hijo una salida limpia (además vale SIGTERM); si el hijo
//! sigue vivo tras 5 s se le hace `kill` para no dejar huérfanos.
//!
//! Sin permisos (p. ej. `ptrace_scope` restrictivo o `/proc` no disponible) el
//! test se OMITE con mensaje en stderr en vez de fallar; con el hijo
//! descendiente debe funcionar sin privilegios.

use forever_patcher::{LinuxProcessMemory, load_contract, patch_stores, scan_stores};
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

fn real_contract_path() -> std::path::PathBuf {
    let dir = std::env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR");
    std::path::Path::new(&dir).join("../../forever/contract.json")
}

/// Cierra el stdin del hijo (señal de salida limpia) y espera hasta 5 s; si
/// sigue vivo lo mata. Siempre hace `wait` para no dejar zombis/huérfanos.
fn cleanup_child(child: &mut Child) {
    // Cerrar la tubería de stdin: el hijo lo interpreta como orden de salida.
    drop(child.stdin.take());
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) => {
                if Instant::now() >= deadline {
                    break;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(_) => break,
        }
    }
    if matches!(child.try_wait(), Ok(None)) {
        let _ = child.kill();
        let _ = child.wait();
    } else {
        let _ = child.wait();
    }
}

#[test]
fn cross_process_scan_patch_idempotente() {
    if !cfg!(target_os = "linux") {
        eprintln!("SKIP: la prueba cross-proceso necesita Linux (/proc/<pid>/mem)");
        return;
    }
    let contract_path = real_contract_path();
    if !contract_path.is_file() {
        eprintln!(
            "SKIP: no se encontró el contrato en {}",
            contract_path.display()
        );
        return;
    }
    let holder = env!("CARGO_BIN_EXE_store_holder");

    let mut child = match Command::new(holder)
        .arg("--contract")
        .arg(&contract_path)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
    {
        Ok(c) => c,
        Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => {
            eprintln!("SKIP: sin permiso para lanzar el proceso hijo: {e}");
            return;
        }
        Err(e) => panic!("no se pudo lanzar store_holder ({holder}): {e}"),
    };

    // Hilo lector: empuja cada línea del hijo a un canal para poder esperar
    // con timeout (el `read_line` bloqueante solo no permite expirar).
    let stdout = child.stdout.take().expect("stdout del hijo");
    let (tx, rx) = std::sync::mpsc::channel::<Option<String>>();
    std::thread::spawn(move || {
        let mut reader = BufReader::new(stdout);
        loop {
            let mut line = String::new();
            match reader.read_line(&mut line) {
                Ok(0) => {
                    let _ = tx.send(None);
                    break;
                }
                Ok(_) => {
                    if tx.send(Some(line.trim().to_string())).is_err() {
                        break;
                    }
                }
                Err(_) => {
                    let _ = tx.send(None);
                    break;
                }
            }
        }
    });

    // Primera línea: el PID que publica el hijo.
    let pid_line = match rx.recv_timeout(Duration::from_secs(5)) {
        Ok(Some(l)) => l,
        Ok(None) => {
            cleanup_child(&mut child);
            panic!("store_holder terminó sin publicar su PID");
        }
        Err(_) => {
            cleanup_child(&mut child);
            panic!("timeout esperando el PID de store_holder");
        }
    };
    let pid: u32 = match pid_line.parse() {
        Ok(p) => p,
        Err(_) => {
            cleanup_child(&mut child);
            panic!("primera línea del hijo no es un PID: {pid_line:?}");
        }
    };
    assert_eq!(
        pid,
        child.id(),
        "el PID publicado debe coincidir con el hijo lanzado"
    );

    let contract = load_contract(&contract_path).expect("el contrato real debe parsearse");
    let mem = LinuxProcessMemory::new(pid);

    // `scan` debe encontrar exactamente un almacén en el otro proceso.
    let found = match scan_stores(&mem, &contract) {
        Ok(f) => f,
        Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => {
            eprintln!("SKIP: sin permiso para leer /proc/{pid}/mem ({e}); ver README: ptrace_scope/procesos no dumpables");
            cleanup_child(&mut child);
            return;
        }
        Err(e) => {
            cleanup_child(&mut child);
            panic!("scan cross-proceso falló: {e}");
        }
    };
    assert_eq!(
        found.len(),
        1,
        "debe hallarse exactamente un almacén en el hijo"
    );
    assert!(
        !found[0].already_patched,
        "el almacén del hijo empieza pendiente"
    );

    // Primer parche: escribe vía /proc/<pid>/mem.
    let first = match patch_stores(&mem, &contract) {
        Ok(o) => o,
        Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => {
            eprintln!("SKIP: sin permiso para escribir /proc/{pid}/mem ({e})");
            cleanup_child(&mut child);
            return;
        }
        Err(e) => {
            cleanup_child(&mut child);
            panic!("patch cross-proceso falló: {e}");
        }
    };
    assert_eq!(first.len(), 1);
    assert_eq!(first[0].addr, found[0].addr);
    assert!(
        !first[0].already_patched,
        "la primera pasada debe parchear"
    );

    // El hijo debe observar new_key en SU memoria e imprimir PATCHED (< 10 s).
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut saw_patched = false;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match rx.recv_timeout(remaining) {
            Ok(Some(line)) => {
                if line == "PATCHED" {
                    saw_patched = true;
                    break;
                }
                // PENDING u otras líneas: se ignoran y se sigue esperando.
            }
            Ok(None) => break, // el hijo cerró stdout
            Err(_) => break,   // timeout
        }
    }
    if !saw_patched {
        cleanup_child(&mut child);
        panic!("el hijo no confirmó PATCHED en 10 s (la escritura cross-proceso no fue visible)");
    }

    // Segunda pasada: idempotente también cross-proceso.
    let second = patch_stores(&mem, &contract).expect("segundo patch cross-proceso");
    assert_eq!(second.len(), 1);
    assert_eq!(second[0].addr, found[0].addr);
    assert!(
        second[0].already_patched,
        "la segunda pasada debe detectar ya parcheado"
    );

    cleanup_child(&mut child);
    assert!(
        !matches!(child.try_wait(), Ok(None)),
        "el hijo debe haber terminado (sin huérfanos)"
    );
}
