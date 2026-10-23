"""Instalación del cliente delegando la descarga en cascette (US-001-FR-002/003/004/008).

Solo orquesta: resuelve el build (Ribbit en vivo o hashes pinneados), avisa si
el build no está soportado por el launcher y ejecuta
`cascette install install-to-casc` con los tags `Windows`+`x86_64`+`enUS` y
`--subfolder ""` (loose en la raíz de `<dir>`, junto a `WowB.exe`).

Reanudación (FR-004): nunca se pasa `--force` ni se borra estado; re-ejecutar
retoma desde `.build.info`/`InstallState` sin re-descargar lo ya válido.
Solo escribe rutas del install manifest más `Data/` y `.build.info`: nunca
toca `Config.wtf` ni `WTF/BetaSuspendedTest.wtf` (FR-007).
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

from .casc import fetch_build_name, query_versions
from .contract import Contract, is_supported_version

_BUILD_RE = re.compile(r"(\d+)patch([\d.]+)")


def pinned_versions_name(build_config_hash: str, product: str, region: str) -> str:
    """`1.60.1.70009` a partir del nombre del build (`WOW-70009patch1.60.1_…`)."""
    name = fetch_build_name(build_config_hash, product, region)
    match = _BUILD_RE.search(name)
    if not match:
        raise RuntimeError(f"nombre de build inesperado: {name!r}")
    build_id, version = match.groups()
    return f"{version}.{build_id}"


def run_install(
    install_dir: Path,
    contract: Contract,
    region: str | None = None,
    loose_only: bool = False,
    max_files: int = 0,
    max_archives: int = 0,
    build_config: str | None = None,
    cdn_config: str | None = None,
    strict_version: bool = False,
) -> int:
    """Instala el cliente en `<dir>`; devuelve el exit code de cascette."""
    active_region = (region or contract.region).lower()
    if build_config and cdn_config:
        print("build pinneado por el usuario (no se consulta Ribbit)")
        bc, cc = build_config, cdn_config
        try:
            versions_name = pinned_versions_name(bc, contract.product, active_region)
        except Exception as e:
            print(f"error: no se pudo leer el build pinneado: {e}")
            return 2
    elif build_config or cdn_config:
        print("error: --build-config y --cdn-config deben darse juntos")
        return 2
    else:
        try:
            live = query_versions(contract.product, active_region)
        except Exception as e:
            print(f"error: no se pudo resolver el build en vivo: {e}")
            return 2
        bc, cc = live.build_config_hash, live.cdn_config_hash
        versions_name = live.versions_name
        print(f"build activo en la CDN: {versions_name} (BuildId {live.build_id})")

    if is_supported_version(versions_name, contract):
        print(f"versión {versions_name}: soportada por el launcher")
    else:
        print(
            "ADVERTENCIA: la versión "
            f"{versions_name} NO está entre las soportadas por el launcher "
            f"({', '.join(contract.supported_versions)}). "
            "El cliente puede no conectar con Classic Forever."
        )
        if strict_version:
            print("aborto por --strict-version")
            return 2

    # El tamaño filtrado exacto lo informa cascette antes de descargar
    # (paso de filtrado previo a la descarga); aquí el plan es cualitativo.
    mode = "mínima (solo loose del install manifest)" if loose_only else "completa"
    print(f"modo: {mode}; tags: {contract.platform}+{contract.arch}+{contract.locale}")
    print(
        "reanudable: re-ejecutar este mismo comando retoma lo pendiente "
        "sin re-descargar lo ya válido (sin --force)"
    )

    cmd = [
        sys.executable, "-m", "cascette_tools",
        "install", "install-to-casc",
        bc, cc, str(install_dir),
        "--product", contract.product,
        "--region", active_region,
        "--platform", contract.platform,
        "--arch", contract.arch,
        "--locale", contract.locale,
        "--subfolder", "",
    ]
    if loose_only:
        cmd.append("--loose-only")
    if max_files > 0:
        cmd += ["--max-files", str(max_files)]
    if max_archives > 0:
        cmd += ["--max-archives", str(max_archives)]
    completed = subprocess.run(cmd)
    return completed.returncode
