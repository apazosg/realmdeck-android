"""Reparación: re-descarga solo lo ausente o corrupto (US-001-FR-006).

Reutiliza el diagnóstico de `verify` y descarga únicamente los ficheros
afectados, dejándolos en estado que `verify` acepta. No re-descarga contenido
válido y no toca `Config.wtf` ni `WTF/BetaSuspendedTest.wtf`: solo escribe
rutas que pertenecen al install manifest.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from cascette_tools.core.cdn import CDNClient
from cascette_tools.core.types import Product

from .casc import ensure_fetcher, fetch_blob, load_expected_loose_files, md5_of_file
from .contract import Contract
from .verify import classify, read_build_info


def run_repair(
    install_dir: Path,
    contract: Contract,
    region: str | None = None,
) -> int:
    """Repara `<dir>`; 0 si queda válido, 1 si algo sigue fallando, 2 si no hay
    instalación legible."""
    root = install_dir
    info = read_build_info(root)
    if info is None:
        print(f"error: {root} no contiene .build.info (¿sin instalar?)")
        return 2
    if not info.build_key or not info.cdn_key:
        print("error: .build.info sin hashes de build/CDN; reinstala el cliente")
        return 2
    active_region = (region or info.region or contract.region).lower()
    try:
        build_name, expected = load_expected_loose_files(
            info.build_key, info.cdn_key, contract, active_region, root
        )
    except Exception as e:
        print(f"error: no se pudo resolver el manifiesto esperado: {e}")
        return 2
    mismatches = classify(root, expected)
    if not mismatches:
        print(f"OK: nada que reparar ({len(expected)} ficheros válidos)")
        return 0
    print(f"build: {build_name or info.build_key}")
    print(f"a reparar: {len(mismatches)} de {len(expected)} ficheros")

    cdn = CDNClient(Product(contract.product), region=active_region)
    try:
        # Los índices ya están en Data/indices tras cualquier install, así que
        # esto no descarga nada en el caso normal.
        fetcher = ensure_fetcher(cdn, info.cdn_key, root)
        failures = 0
        for m in mismatches:
            item = next(e for e in expected if e.relpath == m.relpath)
            data: bytes | None = None
            for ekey in item.ekeys:
                data = fetch_blob(ekey, cdn, fetcher)
                if data is not None:
                    break
            if data is None:
                print(f"fallo:  {m.relpath} (ilocalizable en la CDN)")
                failures += 1
                continue
            if len(data) != item.size or hashlib.md5(data).digest() != item.md5:
                print(f"fallo:  {m.relpath} (la CDN devolvió contenido inválido)")
                failures += 1
                continue
            target = root / item.relpath
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            print(f"reparado: {m.relpath} ({m.kind})")
        remaining = classify(root, expected)
        if not remaining and failures == 0:
            print(f"OK: {len(mismatches)} ficheros reparados; verify acepta el estado")
            return 0
        for m in remaining:
            print(f"pendiente: {m.relpath} ({m.kind}: {m.detail})")
        return 1
    finally:
        cdn.close()
