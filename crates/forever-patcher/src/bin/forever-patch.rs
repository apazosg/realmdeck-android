//! `forever-patch`: binario mínimo sobre la librería.
//!
//! - `scan` solo informa (nunca escribe).
//! - `ensure` aplica el parche y lo mantiene hasta Ctrl-C.

use forever_patcher::{LinuxProcessMemory, load_contract};
use std::time::Duration;

const DEFAULT_INTERVAL_MS: u64 = 2000;

fn usage() -> ! {
    eprintln!(
        "uso:\n\
         \tforever-patch scan --pid <N> [--contract <ruta>]\n\
         \tforever-patch ensure --pid <N> [--interval-ms <MS>] [--contract <ruta>]\n\
         \n\
         \t--contract por defecto: $WOWFOREVER_CONTRACT o forever/contract.json"
    );
    std::process::exit(2);
}

struct Args {
    pid: u32,
    contract: String,
    interval: Duration,
}

fn find_contract_from(start: &std::path::Path) -> Option<std::path::PathBuf> {
    let mut dir: Option<&std::path::Path> = Some(start);
    while let Some(d) = dir {
        let cand = d.join("forever/contract.json");
        if cand.is_file() {
            return Some(cand);
        }
        dir = d.parent();
    }
    None
}

fn resolve_default_contract() -> String {
    let cwd = std::env::current_dir().unwrap_or_else(|_| std::path::PathBuf::from("."));
    if cwd.join("forever/contract.json").is_file() {
        return "forever/contract.json".to_string();
    }
    // El binario puede invocarse desde un subdirectorio del repo (p. ej.
    // `crates/forever-patcher/`): se busca hacia arriba hasta la raíz que
    // contenga `forever/contract.json`.
    if let Some(found) = find_contract_from(&cwd) {
        return found.to_string_lossy().into_owned();
    }
    // No encontrado: se devuelve la ruta relativa para que `load_contract`
    // produzca el error claro habitual ("no se pudo leer el contrato: ...").
    "forever/contract.json".to_string()
}

fn contract_arg(values: &[String]) -> String {
    if let Some(i) = values.iter().position(|a| a == "--contract") {
        return values.get(i + 1).unwrap_or_else(|| usage()).clone();
    }
    if let Ok(env) = std::env::var("WOWFOREVER_CONTRACT") {
        if !env.is_empty() {
            return env;
        }
    }
    resolve_default_contract()
}

fn pid_arg(values: &[String]) -> u32 {
    let i = values.iter().position(|a| a == "--pid").unwrap_or_else(|| usage());
    values
        .get(i + 1)
        .unwrap_or_else(|| usage())
        .parse()
        .unwrap_or_else(|_| {
            eprintln!("error: --pid debe ser un número de proceso");
            std::process::exit(2);
        })
}

fn main() {
    let argv: Vec<String> = std::env::args().collect();
    let sub = argv.get(1).map(String::as_str).unwrap_or_else(|| usage());
    let rest = &argv[2..];

    let args = Args {
        pid: pid_arg(rest),
        contract: contract_arg(rest),
        interval: rest
            .iter()
            .position(|a| a == "--interval-ms")
            .map(|i| {
                rest.get(i + 1).unwrap_or_else(|| usage()).parse().unwrap_or_else(|_| {
                    eprintln!("error: --interval-ms debe ser un número de milisegundos");
                    std::process::exit(2);
                })
            })
            .map(Duration::from_millis)
            .unwrap_or_else(|| Duration::from_millis(DEFAULT_INTERVAL_MS)),
    };
    if rest.iter().any(|a| a == "-h" || a == "--help") {
        usage();
    }

    let contract = load_contract(&args.contract).unwrap_or_else(|e| {
        eprintln!("error: {e}");
        std::process::exit(2);
    });
    let mem = LinuxProcessMemory::new(args.pid);

    match sub {
        "scan" => {
            // Solo informa: ninguna llamada a `write` en este camino.
            match forever_patcher::scan_stores(&mem, &contract) {
                Ok(stores) if stores.is_empty() => {
                    println!("sin almacén válido en el proceso {}", args.pid);
                }
                Ok(stores) => {
                    for s in &stores {
                        println!(
                            "almacén válido en 0x{:X} ({})",
                            s.addr,
                            if s.already_patched {
                                "ya parcheado"
                            } else {
                                "pendiente de parche"
                            }
                        );
                    }
                    println!("{} almacén(es) en el proceso {}", stores.len(), args.pid);
                }
                Err(e) => {
                    eprintln!("error al escanear el proceso {}: {e}", args.pid);
                    std::process::exit(1);
                }
            }
        }
        "ensure" => {
            println!(
                "asegurando parche en el proceso {} cada {} ms (Ctrl-C para detener)…",
                args.pid,
                args.interval.as_millis()
            );
            loop {
                match forever_patcher::patch_stores(&mem, &contract) {
                    Ok(out) if out.is_empty() => {
                        println!("almacén aún no visible, reintentando…");
                    }
                    Ok(out) => {
                        for o in &out {
                            println!(
                                "bloque 0x{:X}: {}",
                                o.addr,
                                if o.already_patched {
                                    "ya estaba parcheado"
                                } else {
                                    "parche aplicado"
                                }
                            );
                        }
                    }
                    Err(e) => {
                        eprintln!("error con el proceso {}: {e}", args.pid);
                        std::process::exit(1);
                    }
                }
                std::thread::sleep(args.interval);
            }
        }
        _ => usage(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn unique_tmp_root(tag: &str) -> std::path::PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("reloj")
            .as_nanos();
        std::env::temp_dir().join(format!(
            "forever-patch-test-{}-{}-{nanos}",
            std::process::id(),
            tag
        ))
    }

    #[test]
    fn resuelve_contrato_buscando_hacia_arriba() {
        let root = unique_tmp_root("up");
        let nested = root.join("a").join("b");
        let contract = root.join("forever").join("contract.json");
        std::fs::create_dir_all(contract.parent().unwrap()).unwrap();
        std::fs::create_dir_all(&nested).unwrap();
        std::fs::write(&contract, "{}").unwrap();

        // Desde el subdirectorio se encuentra el contrato de un ancestro.
        assert_eq!(find_contract_from(&nested), Some(contract.clone()));
        // Desde la propia raíz también.
        assert_eq!(find_contract_from(&root), Some(contract));

        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn sin_contrato_en_ancestros_no_encuentra_nada() {
        let root = unique_tmp_root("none");
        let nested = root.join("a");
        std::fs::create_dir_all(&nested).unwrap();

        assert_eq!(find_contract_from(&nested), None);

        std::fs::remove_dir_all(&root).ok();
    }
}
