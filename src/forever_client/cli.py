"""CLI `forever-client`: resolve / install / verify / repair (US-001)."""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import structlog

from . import __version__
from .casc import fetch_build_name, query_versions
from .contract import Contract, is_supported_version, load_contract
from .installer import run_install
from .repair import run_repair
from .verify import run_verify

# cascette solo configura structlog en su propio CLI; como la usamos como
# biblioteca, sin esto sus trazas de `debug` (cache_hit, lookup de CKey...)
# inundan la salida del usuario.
_LOG_LEVEL = logging.WARNING


def _configure_logging() -> None:
    """Aplica el nivel de log una vez importada cascette (gana a su config)."""
    logging.basicConfig(level=_LOG_LEVEL, format="%(message)s")
    structlog.configure(wrapper_class=structlog.make_filtering_bound_logger(_LOG_LEVEL))


def build_parser() -> argparse.ArgumentParser:
    """Parser con ayuda explícita por subcomando."""
    parser = argparse.ArgumentParser(
        prog="forever-client",
        description=(
            "Descarga, verifica y repara el cliente WoW Classic Beta "
            "desde la CDN pública de Blizzard, sin Battle.net."
        ),
    )
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    parser.add_argument(
        "--contract",
        default=None,
        help="ruta alternativa a forever/contract.json",
    )
    parser.add_argument("--region", default=None, help="región NGDP (por defecto: us)")

    sub = parser.add_subparsers(dest="command", required=True)

    p_resolve = sub.add_parser(
        "resolve",
        help="muestra el build activo (BuildId, hashes, nombre).",
        description=(
            "Consulta el endpoint de versiones del producto wow_classic_beta "
            "(Ribbit, sin autenticación) y muestra BuildId, VersionsName, "
            "hashes de build/CDN config y nombre del build."
        ),
    )
    p_resolve.add_argument(
        "--build-config",
        default=None,
        help="en vez de Ribbit, describe este hash de build config",
    )
    p_resolve.add_argument(
        "--cdn-config",
        default=None,
        help="hash de CDN config (solo informativo con --build-config)",
    )

    p_install = sub.add_parser(
        "install",
        help="descarga e instala el cliente en <dir>.",
        description=(
            "Materializa WowB.exe + Data/ + metadatos en <dir>. Reanudable e "
            "idempotente: re-ejecutar no re-descarga lo válido. Nunca toca "
            "Config.wtf ni WTF/BetaSuspendedTest.wtf."
        ),
    )
    p_install.add_argument("dir", help="directorio destino de la instalación")
    p_install.add_argument(
        "--loose-only",
        action="store_true",
        help="instalación mínima: solo loose del install manifest (~1 GB)",
    )
    p_install.add_argument(
        "--max-files", type=int, default=0, help="límite de ficheros CASC (0 = todos)"
    )
    p_install.add_argument(
        "--max-archives", type=int, default=0, help="límite de archives (0 = todos)"
    )
    p_install.add_argument("--build-config", default=None, help="pinnea el build")
    p_install.add_argument("--cdn-config", default=None, help="pinnea la CDN config")
    p_install.add_argument(
        "--strict-version",
        action="store_true",
        help="abortar si el build no lo soporta el launcher (en vez de avisar)",
    )

    p_verify = sub.add_parser(
        "verify",
        help="comprueba la integridad de <dir> (exit 0 si válido).",
        description=(
            "Verifica los ficheros loose contra el install manifest (MD5): "
            "distingue ausentes de corruptos. Exit 0 si todo válido, "
            "1 si hay discrepancias, 2 si no hay instalación legible."
        ),
    )
    p_verify.add_argument("dir", help="directorio instalado a verificar")

    p_repair = sub.add_parser(
        "repair",
        help="re-descarga solo lo ausente o corrupto de <dir>.",
        description=(
            "Re-descarga únicamente los ficheros que verify marca como "
            "ausentes o corruptos y deja <dir> en estado que verify acepta."
        ),
    )
    p_repair.add_argument("dir", help="directorio instalado a reparar")

    return parser


def cmd_resolve(contract: Contract, region: str, args: argparse.Namespace) -> int:
    """Implementa `resolve` (US-001-FR-001)."""
    active_region = (args.region or region).lower()
    try:
        if args.build_config:
            bc = args.build_config
            cc = args.cdn_config or "?"
            name = fetch_build_name(bc, contract.product, active_region)
            print(f"producto:      {contract.product} (pinneado, sin Ribbit)")
            print(f"build config:  {bc}")
            print(f"CDN config:    {cc}")
            print(f"build:         {name or '?'}")
            return 0
        live = query_versions(contract.product, active_region)
        name = fetch_build_name(live.build_config_hash, contract.product, active_region)
    except Exception as e:
        print(f"error: no se pudo resolver el build: {e}")
        return 2
    print(f"producto:      {contract.product} (región {active_region}, sin autenticación)")
    print(f"BuildId:       {live.build_id}")
    print(f"VersionsName:  {live.versions_name}")
    print(f"build config:  {live.build_config_hash}")
    print(f"CDN config:    {live.cdn_config_hash}")
    print(f"build:         {name or '?'}")
    supported = "sí" if is_supported_version(live.versions_name, contract) else "NO"
    print(f"soportado por el launcher ({', '.join(contract.supported_versions)}): {supported}")
    return 0


def main(argv: list[str] | None = None) -> int:
    """Punto de entrada (`forever-client`). Devuelve el exit code."""
    _configure_logging()
    # Sin buffer: nuestros avisos salen antes que la salida del subproceso.
    sys.stdout.reconfigure(line_buffering=True)
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        contract = load_contract(args.contract)
    except Exception as e:
        print(f"error: no se pudo cargar el contrato: {e}")
        return 2
    region = args.region or contract.region
    if args.command == "resolve":
        return cmd_resolve(contract, region, args)
    if args.command == "install":
        return run_install(
            Path(args.dir),
            contract,
            region=args.region,
            loose_only=args.loose_only,
            max_files=args.max_files,
            max_archives=args.max_archives,
            build_config=args.build_config,
            cdn_config=args.cdn_config,
            strict_version=args.strict_version,
        )
    if args.command == "verify":
        return run_verify(Path(args.dir), contract, region=args.region)
    if args.command == "repair":
        return run_repair(Path(args.dir), contract, region=args.region)
    parser.error(f"subcomando desconocido: {args.command}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
