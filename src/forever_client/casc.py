"""Lectura de manifiestos NGDP vía cascette (solo red de lectura + caché local).

Reutiliza los parsers y el cliente CDN de cascette para resolver el conjunto
esperado de ficheros loose del install manifest filtrado por tags
(`Windows`+`x86_64`+`enUS`), con la misma semántica de bitmaps que Agent.exe.
No escribe nada en el directorio de instalación.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path

from cascette_tools.core.cdn import CDNClient
from cascette_tools.core.cdn_archive_fetcher import CdnArchiveFetcher
from cascette_tools.core.tact import TACTClient
from cascette_tools.core.types import Product
from cascette_tools.core.utils import PathNormalizer
from cascette_tools.formats.blte import decompress_blte, is_blte
from cascette_tools.formats.config import BuildConfigParser, CDNConfigParser
from cascette_tools.formats.encoding import EncodingParser
from cascette_tools.formats.install import InstallEntry, InstallParser, InstallTag
from cascette_tools.formats.size import SizeTag, apply_tag_query, is_file_selected

from .contract import Contract


@dataclass(frozen=True)
class VersionsInfo:
    """Fila del endpoint `versions` para el producto y región (US-001-FR-001)."""

    build_id: str
    versions_name: str
    build_config_hash: str
    cdn_config_hash: str


@dataclass
class ExpectedFile:
    """Fichero loose esperado: ruta relativa, hash MD5, tamaño y claves de CDN."""

    relpath: str
    md5: bytes
    size: int
    ekeys: list[bytes] = field(default_factory=list)


def query_versions(product_code: str, region: str) -> VersionsInfo:
    """Consulta el endpoint de versiones (Ribbit, sin autenticación)."""
    client = TACTClient(region=region)
    row = client.get_latest_build(Product(product_code))
    if row is None:
        raise RuntimeError(f"sin build para {product_code} en la región {region}")
    return VersionsInfo(
        build_id=row.get("BuildId", ""),
        versions_name=row.get("VersionsName", ""),
        build_config_hash=row.get("BuildConfig", ""),
        cdn_config_hash=row.get("CDNConfig", ""),
    )


def md5_of_file(path: Path) -> bytes:
    """MD5 del contenido (en streaming; hay loose de cientos de MB)."""
    digest = hashlib.md5()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.digest()


def classify_kind(path: Path, item: ExpectedFile) -> str | None:
    """None si el fichero coincide; motivo de corrupción en caso contrario."""
    actual_size = path.stat().st_size
    if actual_size != item.size:
        return f"tamaño {actual_size} != {item.size} esperado"
    if md5_of_file(path) != item.md5:
        return "MD5 distinto del manifiesto"
    return None


def filter_install_entries(
    entries: list[InstallEntry],
    tags: list[InstallTag],
    platform: str | None,
    arch: str | None,
    locale: str | None,
) -> list[InstallEntry]:
    """Filtra por tags con bitmaps, igual que `install-to-casc` de cascette."""
    query = ",".join(p for p in (platform, arch, locale) if p)
    if not query:
        return list(entries)
    size_tags = [
        SizeTag(
            name=t.name,
            tag_id=t.tag_type,
            tag_type=t.tag_type,
            file_indices=[],
            bit_mask=t.bit_mask,
        )
        for t in tags
    ]
    bitmap = apply_tag_query(size_tags, query, len(entries))
    return [e for i, e in enumerate(entries) if is_file_selected(bitmap, i)]


def _fetch_loose(cdn: CDNClient, ekey_hex: str) -> bytes:
    """Descarga un blob suelto de la CDN y lo descomprime (BLTE) si toca."""
    raw = cdn.fetch_data(ekey_hex, quiet=True)
    return decompress_blte(raw) if is_blte(raw) else raw


def ensure_fetcher(
    cdn: CDNClient, cdn_config_hash: str, install_path: Path | None
) -> CdnArchiveFetcher:
    """Fetcher con los índices de archivo: primero caché local, luego CDN.

    Tras cualquier `install` (incluso `--loose-only`) `Data/indices/` ya trae
    los índices, así que el caso normal no descarga nada aquí.
    """
    config_data = cdn.fetch_config(cdn_config_hash, config_type="cdn")
    archives = CDNConfigParser().parse(config_data).archives
    fetcher = CdnArchiveFetcher(cdn_client=cdn)
    missing: list[str] = []
    for archive_hash in archives:
        local = (
            install_path / "Data" / "indices" / f"{archive_hash.lower()}.index"
            if install_path is not None
            else None
        )
        if local is not None and local.exists():
            if fetcher.load_index_from_file(archive_hash, local):
                continue
        missing.append(archive_hash)
    for archive_hash in missing:
        index_data = cdn.fetch_data(archive_hash, is_index=True)
        fetcher.load_index_from_bytes(archive_hash, index_data)
    return fetcher


def fetch_blob(
    ekey: bytes, cdn: CDNClient, fetcher: CdnArchiveFetcher | None = None
) -> bytes | None:
    """Contenido descomprimido de una encoding key (archivo > suelto en CDN)."""
    if fetcher is not None:
        data = fetcher.fetch_file_via_cdn(cdn, ekey, decompress=True)
        if data is not None:
            return data
    try:
        return _fetch_loose(cdn, ekey.hex())
    except Exception:
        return None


def load_expected_loose_files(
    build_config_hash: str,
    cdn_config_hash: str,
    contract: Contract,
    region: str,
    install_path: Path | None = None,
) -> tuple[str, list[ExpectedFile]]:
    """Manifiesto de instalación filtrado + nombre del build.

    Devuelve `(build_name, ficheros)`. Las rutas ya vienen normalizadas con la
    misma regla que usa `install-to-casc` al escribir (casing canónico).
    """
    cdn = CDNClient(Product(contract.product), region=region)
    try:
        build_config_data = cdn.fetch_config(build_config_hash, config_type="build")
        build_config = BuildConfigParser().parse(build_config_data)
        build_name = build_config.build_name or ""

        encoding_info = build_config.get_encoding_info()
        install_info = build_config.get_install_info()
        if not encoding_info or not encoding_info.encoding_key:
            raise RuntimeError("el build config no trae encoding key")
        if not install_info or not install_info.content_key:
            raise RuntimeError("el build config no trae install manifest")

        encoding_raw = _fetch_loose(cdn, encoding_info.encoding_key)
        encoding_parser = EncodingParser()
        encoding_file = encoding_parser.parse(encoding_raw)

        install_ckey = bytes.fromhex(install_info.content_key)
        install_ekeys = encoding_parser.find_content_key(
            encoding_raw, encoding_file, install_ckey
        ) or []
        fetcher: CdnArchiveFetcher | None = None
        install_data: bytes | None = None
        for candidate in install_ekeys:
            try:
                install_data = _fetch_loose(cdn, candidate.hex())
                break
            except Exception:
                continue
        if install_data is None:
            # El manifiesto no está suelto: tirar de índices de archivo.
            fetcher = ensure_fetcher(cdn, cdn_config_hash, install_path)
            for candidate in install_ekeys:
                install_data = fetcher.fetch_file_via_cdn(
                    cdn, candidate, decompress=True
                )
                if install_data is not None:
                    break
        if install_data is None:
            raise RuntimeError("install manifest ilocalizable en la CDN")

        manifest = InstallParser().parse(install_data)
        selected = filter_install_entries(
            manifest.entries, manifest.tags, contract.platform, contract.arch, contract.locale
        )
        normalizer = PathNormalizer()
        expected: list[ExpectedFile] = []
        for entry in selected:
            ekeys = encoding_parser.find_content_key(
                encoding_raw, encoding_file, entry.md5_hash
            ) or []
            expected.append(
                ExpectedFile(
                    relpath=normalizer.normalize(entry.filename),
                    md5=entry.md5_hash,
                    size=entry.size,
                    ekeys=ekeys,
                )
            )
        return build_name, expected
    finally:
        cdn.close()


def fetch_build_name(
    build_config_hash: str, product_code: str, region: str
) -> str:
    """Nombre del build (p. ej. `WOW-70009patch1.60.1_ForeverBeta`)."""
    cdn = CDNClient(Product(product_code), region=region)
    try:
        data = cdn.fetch_config(build_config_hash, config_type="build")
        return BuildConfigParser().parse(data).build_name or ""
    finally:
        cdn.close()
