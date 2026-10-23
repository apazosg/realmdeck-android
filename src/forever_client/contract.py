"""Constantes del proyecto cargadas desde `forever/contract.json`.

Nada de literales dispersos: producto NGDP, builds soportadas, hashes
validados y locale viven en el contrato versionado (SYSTEM.md SYS-NFR-007).
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONTRACT_PATH = REPO_ROOT / "forever" / "contract.json"
CONTRACT_ENV_VAR = "WOWFOREVER_CONTRACT"


@dataclass(frozen=True)
class Contract:
    """Subconjunto del contrato relevante para la provisión (US-001)."""

    path: Path
    product: str = "wow_classic_beta"
    region: str = "us"
    platform: str = "Windows"
    arch: str = "x86_64"
    locale: str = "enUS"
    supported_versions: tuple[str, ...] = ()
    validated_build_id: int = 0
    validated_versions_name: str = ""
    validated_build_config_hash: str = ""
    validated_cdn_config_hash: str = ""
    exe: str = "WowB.exe"
    portal: str = ""


def contract_path(explicit: str | Path | None = None) -> Path:
    """Ruta del contrato: argumento > env > `forever/contract.json` del repo."""
    if explicit is not None:
        return Path(explicit)
    env = os.environ.get(CONTRACT_ENV_VAR)
    if env:
        return Path(env)
    return DEFAULT_CONTRACT_PATH


def load_contract(path: str | Path | None = None) -> Contract:
    """Carga y valida mínimamente el contrato Forever."""
    resolved = contract_path(path)
    data = json.loads(resolved.read_text(encoding="utf-8"))
    product = data["product"]
    launcher = data["launcher"]
    tags = data["tags"]
    return Contract(
        path=resolved,
        product=product["ngdp"],
        region=product["region"],
        platform=tags["platform"],
        arch=tags["arch"],
        locale=tags["locale"],
        supported_versions=tuple(launcher["supported_versions"]),
        validated_build_id=int(product["validated_build_id"]),
        validated_versions_name=product["validated_versions_name"],
        validated_build_config_hash=product["validated_build_config_hash"],
        validated_cdn_config_hash=product["validated_cdn_config_hash"],
        exe=launcher["exe"],
        portal=data["server"]["portal"],
    )


def is_supported_version(versions_name: str, contract: Contract) -> bool:
    """True si el launcher acepta este build (US-001-FR-003)."""
    return versions_name in contract.supported_versions
