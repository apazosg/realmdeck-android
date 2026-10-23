"""Verificación de integridad contra el install manifest (US-001-FR-005).

Cubre exactamente: los ficheros loose del install manifest filtrados por
`Windows`+`x86_64`+`enUS` (lo que instala `--loose-only`: `WowB.exe` y
compañía), comparando existencia, tamaño y MD5 contra el manifiesto de la CDN.

NO cubre: los archivos CASC del download manifest (`Data/data/`). En una
instalación completa esa parte la garantiza la reanudación de cascette
(`InstallState` + índices locales); aquí solo se informa de su presencia.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from cascette_tools.formats.build_info import BuildInfoParser, LocalBuildInfo

from .casc import ExpectedFile, classify_kind, load_expected_loose_files, md5_of_file
from .contract import Contract


@dataclass(frozen=True)
class Mismatch:
    """Discrepancia clasificada: ausente o corrupto (US-001-AC-06)."""

    relpath: str
    kind: str  # "missing" | "corrupt"
    detail: str


def classify(install_root: Path, expected: list[ExpectedFile]) -> list[Mismatch]:
    """Clasifica cada fichero esperado sin tocar la red (puro, testeable)."""
    found: list[Mismatch] = []
    for item in expected:
        path = install_root / item.relpath
        if not path.is_file():
            found.append(Mismatch(item.relpath, "missing", "no existe en disco"))
            continue
        reason = classify_kind(path, item)
        if reason is not None:
            found.append(Mismatch(item.relpath, "corrupt", reason))
    return found


def read_build_info(install_root: Path) -> LocalBuildInfo | None:
    """Lee `.build.info` si existe y parsea (None si no hay instalación)."""
    info_path = install_root / ".build.info"
    if not info_path.is_file():
        return None
    return BuildInfoParser().parse_file(str(info_path))


def run_verify(
    install_dir: Path,
    contract: Contract,
    region: str | None = None,
) -> int:
    """Verifica `<dir>`; 0 si todo válido, 1 si hay discrepancias, 2 si no hay
    instalación legible. Solo lee: nunca modifica `Config.wtf` ni el almacén."""
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
    total_bytes = sum(e.size for e in expected)
    print(f"build: {build_name or info.build_key}")
    print(f"ficheros loose esperados (Windows/x86_64/enUS): {len(expected)}")
    if not mismatches:
        print(
            f"OK: {len(expected)} ficheros válidos "
            f"({total_bytes / (1 << 30):.2f} GiB verificados por MD5)"
        )
        print("cobertura: solo ficheros loose del install manifest; "
              "archivos CASC de Data/ no incluidos")
        return 0
    missing = [m for m in mismatches if m.kind == "missing"]
    corrupt = [m for m in mismatches if m.kind == "corrupt"]
    for m in missing:
        print(f"ausente:  {m.relpath}")
    for m in corrupt:
        print(f"corrupto: {m.relpath} ({m.detail})")
    print(f"resumen: {len(missing)} ausentes, {len(corrupt)} corruptos, "
          f"{len(expected) - len(mismatches)} válidos de {len(expected)}")
    return 1
