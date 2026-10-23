#!/usr/bin/env python3
"""Build a bounded, Android-consumable catalog for the pinned ARM64 build.

The catalog is intentionally *not* an installer manifest.  It contains the
finite ARM64 install-manifest subset, the CDN archive locations needed to fetch
those objects, and enough CASC metadata to create an initial local store.  It
does not claim that the resulting store is a playable client.

The normal command is cache-only.  The cascette disk cache is immutable CDN
evidence and is read directly; this tool never reads or writes ``/client`` and
never executes a downloaded PE file.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import re
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

import structlog
from cascette_tools.core.cache import DiskCache
from cascette_tools.core.local_storage import LocalStorage, compute_bucket, parse_local_idx_file
from cascette_tools.core.utils import PathNormalizer
from cascette_tools.formats.blte import BLTEParser, decompress_blte, is_blte
from cascette_tools.formats.cdn_archive import CdnArchiveParser
from cascette_tools.formats.config import BuildConfigParser, CDNConfigParser, ConfigFileInfo
from cascette_tools.formats.encoding import CKeyPageEntry, EKeyPageEntry, EncodingFile, EncodingParser
from cascette_tools.formats.install import InstallEntry, InstallFile, InstallParser
from cascette_tools.formats.build_info import BuildInfoParser, create_build_info

from forever_client.casc import filter_install_entries


PRODUCT = "wow_classic_beta"
REGION = "us"
PLATFORM = "Windows"
ARCH = "arm64"
LOCALE = "enUS"
VERSION = "1.60.1.70009"
BUILD_CONFIG = "05215079e3905ef5922ae0b03ffefb73"
CDN_CONFIG = "9b3c456dbb837d133a026d380c7c13e9"
CDN_PATH = "tpr/wow"
CDN_HOST = "https://us.cdn.blizzard.com"
TARGET_EXE = "WowB-ARM64.exe"
TARGET_MACHINE = 0xAA64
SCHEMA_VERSION = 1
CDN_HOSTS = ("level3.blizzard.com", "us.cdn.blizzard.com")
CATALOG_MAX_TRANSFER_BYTES = 1 << 30
REQUIRED_RUNTIME_ROLES = {"root", "vfs-root", "encoding", "install", "download", "size"}
OPTIONAL_DELTA_ROLES = {"patch", "patch-index"}
SUPPORTED_BLTE_FLAGS = {None, 0x0F, 0x10}
SUPPORTED_BLTE_MODES = {"N", "Z"}


class CatalogError(ValueError):
    """A catalog invariant is not satisfied."""


@dataclass(frozen=True)
class _CdnLocation:
    """A location advertised by one CDN index.

    CDN archive indices (footer ``offset_bytes == 4``) locate an object inside
    the archive named by the index.  The CDN file index uses
    ``offset_bytes == 0``: it is a size-only table for standalone EKey
    objects, not an archive index.  Keeping these forms distinct prevents a
    file-index hash from becoming an archive payload URL.
    """

    kind: str
    index_hash: str
    length: int
    offset: int | None = None
    archive_index: int | None = None


def official_url(host: str, kind: str, digest: str, suffix: str = "") -> str:
    """Return the official CDN URL for a config/data/index object."""
    value = digest.lower()
    content = "config" if kind == "config" else "patch" if kind.startswith("patch") else "data"
    filename = value + suffix
    return f"{host.rstrip('/')}/{CDN_PATH}/{content}/{value[:2]}/{value[2:4]}/{filename}"


def ensure_safe_relative_path(value: str) -> str:
    """Normalize a manifest path and reject paths unsafe for Android extraction."""
    if not value or "\x00" in value:
        raise CatalogError("empty or NUL-containing install path")
    path = value.replace("\\", "/")
    if path.startswith("/") or re.match(r"^[A-Za-z]:($|/)", path):
        raise CatalogError(f"absolute install path: {value!r}")
    parts = path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise CatalogError(f"unsafe install path: {value!r}")
    return path


def _canonical_install_paths(entries: Iterable[InstallEntry]) -> list[tuple[InstallEntry, str]]:
    normalizer = PathNormalizer()
    result: list[tuple[InstallEntry, str]] = []
    seen: dict[str, str] = {}
    for entry in entries:
        safe = ensure_safe_relative_path(normalizer.normalize(entry.filename))
        folded = safe.casefold()
        if folded in seen:
            raise CatalogError(f"duplicate install path: {entry.filename!r} and {seen[folded]!r}")
        seen[folded] = entry.filename
        result.append((entry, safe))
    return result


def _decode(raw: bytes) -> bytes:
    return decompress_blte(raw) if is_blte(raw) else raw


def _hash_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _audit_direct_object(
    raw: bytes,
    *,
    content_key: str,
    role: str,
    expected_encoded_size: int | None = None,
    expected_decoded_size: int | None = None,
) -> dict[str, Any]:
    """Decode one cached object and verify every content/size invariant.

    Build-config ``*-size`` values describe the encoded CDN object, whereas
    the CKey entry's ``file_size`` describes decoded content.  Keeping those
    checks separate is important: accepting either value as the other is the
    defect this generator is intended to prevent.
    """
    if expected_encoded_size is not None and len(raw) != expected_encoded_size:
        raise CatalogError(
            f"{role} encoded size disagreement: source={len(raw)} "
            f"metadata={expected_encoded_size}"
        )
    if is_blte(raw):
        parsed = BLTEParser().parse(raw)
        flags = parsed.header.flags
        modes = sorted({chunk.compression_mode.value for chunk in parsed.chunks})
        if flags not in SUPPORTED_BLTE_FLAGS:
            raise CatalogError(f"{role} uses unsupported BLTE flags: {flags!r}")
        unsupported = set(modes) - SUPPORTED_BLTE_MODES
        if unsupported:
            raise CatalogError(f"{role} uses unsupported BLTE modes: {sorted(unsupported)}")
        decoded = BLTEParser().decompress(parsed)
    else:
        flags = None
        modes = ["N"]
        decoded = raw
    decoded_md5 = hashlib.md5(decoded).hexdigest()
    if decoded_md5 != content_key.lower():
        raise CatalogError(
            f"{role} decoded MD5 mismatch: got={decoded_md5} expected={content_key.lower()}"
        )
    if expected_decoded_size is not None and len(decoded) != expected_decoded_size:
        raise CatalogError(
            f"{role} decoded size disagreement: source={len(decoded)} "
            f"encoding={expected_decoded_size}"
        )
    return {
        "encoded_size": len(raw),
        "decoded_size": len(decoded),
        "decoded_md5": decoded_md5,
        "raw_sha256": _sha256_bytes(raw),
        "blte_flags": flags,
        "blte_modes": modes,
    }


def render_build_info(fields: dict[str, Any]) -> bytes:
    """Serialize installer-compatible PSV ``.build.info`` metadata."""
    info = create_build_info(
        branch=str(fields["branch"]),
        build_config_hash=str(fields["build_key"]),
        cdn_config_hash=str(fields["cdn_key"]),
        cdn_path=str(fields["cdn_path"]),
        cdn_hosts=list(fields["cdn_hosts"]),
        version=str(fields["version"]),
        product=str(fields["product"]),
        platform=str(fields["platform"]),
        architecture=str(fields["architecture"]),
        locale=str(fields["locale"]),
        region=str(fields["region"]),
        has_speech=True,
        has_text=True,
        install_key=str(fields["install_key"]),
        im_size=fields.get("im_size"),
        keyring=str(fields.get("keyring", "")),
    )
    return BuildInfoParser().build(info)


def inspect_pe(path: Path) -> dict[str, Any]:
    """Read only the PE identity needed to reject a wrong-architecture EXE."""
    with path.open("rb") as stream:
        dos = stream.read(64)
        if len(dos) != 64 or dos[:2] != b"MZ":
            raise CatalogError("executable is not an MZ PE")
        pe_offset = struct.unpack_from("<I", dos, 0x3C)[0]
        stream.seek(pe_offset)
        if stream.read(4) != b"PE\0\0":
            raise CatalogError("executable is not a PE")
        coff = stream.read(20)
        if len(coff) != 20:
            raise CatalogError("truncated PE COFF header")
        machine = struct.unpack_from("<H", coff, 0)[0]
    names = {0xAA64: "ARM64", 0xA641: "ARM64EC", 0xA64E: "ARM64X", 0x8664: "x86-64"}
    return {"machine": f"0x{machine:04x}", "machine_name": names.get(machine, "unknown"), "is_arm64": machine == TARGET_MACHINE}


@dataclass(frozen=True)
class CacheSource:
    cache: DiskCache
    cdn_path: str = CDN_PATH
    verified_overlay: Mapping[str, Path] | None = None

    def get(self, digest: str, kind: str = "data") -> bytes | None:
        if kind == "data" and self.verified_overlay is not None:
            overlay_path = self.verified_overlay.get(digest.lower())
            if overlay_path is not None and overlay_path.is_file():
                return overlay_path.read_bytes()
        return self.cache.get_cdn(digest, kind, self.cdn_path)

    def has(self, digest: str, kind: str = "data") -> bool:
        if kind == "data" and self.verified_overlay is not None:
            overlay_path = self.verified_overlay.get(digest.lower())
            if overlay_path is not None and overlay_path.is_file():
                return True
        return self.cache.has_cdn(digest, kind, self.cdn_path)


def _fast_find_ckey(
    encoding_data: bytes, encoding_file: EncodingFile, content_key: bytes
) -> CKeyPageEntry | None:
    """Find a CKey without cascette's O(number-of-pages) index walk."""
    first_keys = [entry[0] for entry in encoding_file.ckey_index]
    page = bisect.bisect_right(first_keys, content_key) - 1
    if page < 0:
        return None
    parser = EncodingParser()
    parsed = parser.load_ckey_page(encoding_data, encoding_file, page, max_entries=10000)
    return next((entry for entry in parsed.entries if entry.content_key == content_key), None)


def _fast_find_ekey(
    encoding_data: bytes, encoding_file: EncodingFile, encoding_key: bytes
) -> EKeyPageEntry | None:
    """Find EKey size metadata without walking all encoding pages."""
    first_keys = [entry[0] for entry in encoding_file.ekey_index]
    page = bisect.bisect_right(first_keys, encoding_key) - 1
    if page < 0:
        return None
    parser = EncodingParser()
    parsed = parser.load_ekey_page(encoding_data, encoding_file, page, max_entries=10000)
    return next((entry for entry in parsed.entries if entry.encoding_key == encoding_key), None)


def _config_file_record(host: str, kind: str, digest: str, raw: bytes) -> dict[str, Any]:
    suffix = ""
    return {
        "kind": kind,
        "hash": digest.lower(),
        "relative_path": f"Data/config/{digest[:2].lower()}/{digest[2:4].lower()}/{digest.lower()}",
        "url": official_url(host, "config", digest, suffix),
        "observed_encoded_size": len(raw),
        "status": "cached-and-parser-validated" if raw else "missing-source",
        "sha256": _sha256_bytes(raw) if raw else None,
        "present_sha256": _sha256_bytes(raw) if raw else None,
        "validation": ["https_object_path", "config_hash_provenance", "parser_compatibility"],
    }


def _ref_record(
    *,
    role: str,
    content_key: str,
    decoded_size: int | None,
    ekeys: list[bytes],
    source: CacheSource,
    locations: dict[bytes, _CdnLocation],
    host: str,
    install_relpath: str | None = None,
    allow_missing: bool = False,
    declared_encoded_size: int | None = None,
    encoding_sizes: dict[bytes, int] | None = None,
) -> dict[str, Any]:
    if len(content_key) != 32 or not re.fullmatch(r"[0-9a-fA-F]{32}", content_key):
        raise CatalogError(f"invalid content key for {role}: {content_key!r}")
    if not ekeys:
        raise CatalogError(f"no encoding key for {role} ({content_key})")
    if len(ekeys) != 1:
        raise CatalogError(f"multiple encoding keys are unsupported for bounded role {role}")
    sources: list[dict[str, Any]] = []
    encoded_size = 0
    observed: dict[str, Any] | None = None
    for ekey in ekeys:
        ehex = ekey.hex()
        encoding_size = (encoding_sizes or {}).get(ekey)
        if declared_encoded_size is not None and encoding_size is not None and declared_encoded_size != encoding_size:
            raise CatalogError(
                f"{role} declared/encoding size disagreement: metadata={declared_encoded_size} "
                f"encoding={encoding_size}"
            )
        expected_encoded_size = encoding_size if encoding_size is not None else declared_encoded_size
        direct = source.get(ehex)
        if direct is not None:
            advertised = locations.get(ekey)
            if advertised is not None and len(direct) != advertised.length:
                raise CatalogError(
                    f"{role} direct/file-index size disagreement: {len(direct)} != {advertised.length}"
                )
            audit = _audit_direct_object(
                direct,
                content_key=content_key,
                role=role,
                expected_encoded_size=expected_encoded_size,
                expected_decoded_size=decoded_size,
            )
            if observed is not None and audit["decoded_size"] != observed["decoded_size"]:
                raise CatalogError(f"{role} encoding keys disagree on decoded size")
            observed = audit if observed is None else observed
            encoded_size = len(direct)
            sources.append({"kind": "direct", "ekey": ehex, "url": official_url(host, "data", ehex), "encoded_size": len(direct), "sha256": audit["raw_sha256"], **audit})
            continue
        location = locations.get(ekey)
        if location is None:
            if allow_missing:
                sources.append({"kind": "unresolved", "ekey": ehex})
                continue
            raise CatalogError(f"no direct object or archive range for {role} ({ehex})")
        if encoding_size is not None and location.length != encoding_size:
            raise CatalogError(
                f"{role} index/encoding size disagreement: {location.length} != {encoding_size}"
            )
        encoded_size = location.length
        if location.kind == "direct-object":
            # A file-index entry has no offset and does not prove that the
            # payload is in an archive.  Do not manufacture a SHA-256 from
            # the content key; a bounded consumer may verify it after fetch.
            sources.append({
                "kind": "direct-object",
                "ekey": ehex,
                "index": location.index_hash,
                "url": official_url(host, "data", ehex),
                "encoded_size": location.length,
            })
        elif location.kind == "archive-range":
            if location.offset is None:
                raise CatalogError(f"{role} archive index has no offset")
            sources.append({
                "kind": "archive-range",
                "ekey": ehex,
                "archive": location.index_hash,
                "offset": location.offset,
                "length": location.length,
                "url": official_url(host, "data", location.index_hash),
                "range": f"bytes={location.offset}-{location.offset + location.length - 1}",
                "encoded_size": location.length,
            })
        else:
            raise CatalogError(f"unknown CDN location kind: {location.kind!r}")
    result: dict[str, Any] = {
        "role": role,
        "content_key": content_key.lower(),
        "decoded_size": observed["decoded_size"] if observed is not None else (decoded_size if decoded_size is not None else 0),
        "encoding_keys": [key.hex() for key in ekeys],
        "sources": sources,
        "encoded_size": encoded_size,
        "validation": ["range_or_object_size", "blte_decode", "decoded_size", "decoded_md5_content_key"],
        "verification": "fully-verified" if observed is not None and len(sources) == len(ekeys) and all(item["kind"] == "direct" for item in sources) else "source-unverified",
    }
    if observed is not None:
        result.update({key: observed[key] for key in ("decoded_md5", "blte_flags", "blte_modes")})
    if install_relpath is not None:
        result["relative_path"] = install_relpath
    return result


def _scan_locations(
    source: CacheSource,
    archive_hashes: Iterable[str],
    *,
    file_index_hash: str | None = None,
) -> tuple[dict[bytes, _CdnLocation], list[dict[str, Any]]]:
    """Parse cached CDN index files and retain only locations of interest later.

    The returned index records are the Android skeleton's CDN-index inputs.  A
    caller may scan all entries once and then use the resulting map for the
    finite set of selected objects.
    """
    parser = CdnArchiveParser()
    archive_hashes = [digest.lower() for digest in archive_hashes]
    if file_index_hash is not None:
        file_index_hash = file_index_hash.lower()
    locations: dict[bytes, _CdnLocation] = {}
    records: list[dict[str, Any]] = []
    index_hashes = list(archive_hashes)
    if file_index_hash is not None and file_index_hash not in index_hashes:
        index_hashes.append(file_index_hash)
    for index_hash in index_hashes:
        raw = source.get(index_hash, "index")
        if raw is None:
            records.append({"hash": index_hash, "relative_path": f"Data/indices/{index_hash}.index", "url": official_url(CDN_HOST, "index", index_hash, ".index"), "status": "missing-from-host-cache", "sha256": None})
            continue
        try:
            footer = parser._parse_footer(raw)
            if footer.offset_bytes not in {0, 4, 6} or footer.size_bytes != 4 or footer.key_bytes != 16:
                raise CatalogError(
                    f"unsupported CDN index footer for {index_hash}: "
                    f"offset_bytes={footer.offset_bytes} size_bytes={footer.size_bytes} key_bytes={footer.key_bytes}"
                )
            if footer.offset_bytes == 0 and index_hash != file_index_hash:
                raise CatalogError(
                    f"standalone file index has the wrong CDN role: {index_hash}"
                )
            if footer.offset_bytes != 0 and index_hash == file_index_hash:
                raise CatalogError(
                    f"configured file index is not a standalone file index: {index_hash}"
                )
            if footer.offset_bytes == 0:
                # cascette-tools currently unpacks four bytes even when the
                # footer says there is no offset.  Parse the 20-byte
                # standalone file-index entry explicitly.
                entry_size = footer.key_bytes + footer.size_bytes
                page_size = footer.page_size_kb * 1024
                entries_per_page = page_size // entry_size if entry_size else 0
                if entries_per_page <= 0:
                    raise CatalogError(f"invalid file-index page geometry: {index_hash}")
                entries = []
                for number in range(footer.entry_count):
                    page = number // entries_per_page
                    slot = number % entries_per_page
                    position = page * page_size + slot * entry_size
                    end = position + entry_size
                    if end > len(raw) - CdnArchiveParser.FOOTER_SIZE:
                        raise CatalogError(f"file-index entry exceeds index body: {index_hash}")
                    key = raw[position : position + footer.key_bytes]
                    size = struct.unpack(">I", raw[position + footer.key_bytes : end])[0]
                    if key != b"\x00" * footer.key_bytes:
                        entries.append((key, size, None, None))
                index_role = "file-index"
            else:
                parsed = parser.parse(raw)
                entries = [(entry.encoding_key, entry.size, entry.offset, entry.archive_index) for entry in parsed.entries]
                index_role = "archive-group" if footer.offset_bytes == 6 else "archive-index"
            for key, size, offset, archive_index in entries:
                if size <= 0:
                    raise CatalogError(f"CDN index has non-positive object size: {index_hash}")
                if footer.offset_bytes == 0:
                    locations[key] = _CdnLocation("direct-object", index_hash, size)
                elif footer.offset_bytes == 4:
                    if offset is None:
                        raise CatalogError(f"archive index has no offset: {index_hash}")
                    locations[key] = _CdnLocation("archive-range", index_hash, size, offset=offset)
                else:
                    raise CatalogError(
                        f"archive-group CDN index is unsupported for bounded catalog: {index_hash}"
                    )
        except CatalogError:
            raise
        except (ValueError, struct.error) as exc:
            raise CatalogError(f"invalid CDN index {index_hash}: {exc}") from exc
        records.append({
            "hash": index_hash,
            "relative_path": f"Data/indices/{index_hash}.index",
            "url": official_url(CDN_HOST, "index", index_hash, ".index"),
            "observed_encoded_size": len(raw),
            "sha256": _sha256_bytes(raw),
            "present_sha256": _sha256_bytes(raw),
            "entry_count": footer.entry_count,
            "index_role": index_role,
            "offset_bytes": footer.offset_bytes,
            "status": "cached-and-parser-validated",
            "validation": ["cdn_archive_index_parser", "https_object_path"],
        })
    return locations, records


def _optional_patch_index_records(source: CacheSource, cdn: Any) -> list[dict[str, Any]]:
    """Describe delta indices without making them part of the clean bootstrap."""
    hashes = list(cdn.patch_archives or [])
    if cdn.patch_file_index:
        hashes.append(cdn.patch_file_index)
    records: list[dict[str, Any]] = []
    for digest in hashes:
        raw = source.get(digest, "patch_index")
        record: dict[str, Any] = {
            "hash": digest.lower(),
            "relative_path": f"Data/indices/{digest.lower()}.index",
            "url": official_url(CDN_HOST, "patch-index", digest, ".index"),
            "kind": "optional-delta-index",
            "required_for_clean_install": False,
            "status": "cached-and-parser-validated" if raw is not None else "missing-from-host-cache",
            "validation": ["https_object_path", "optional_delta_provenance"],
        }
        if raw is not None:
            parsed = CdnArchiveParser().parse(raw)
            record.update(observed_encoded_size=len(raw), sha256=_sha256_bytes(raw), entry_count=len(parsed.entries))
            record["validation"].append("cdn_archive_index_parser")
        else:
            record["sha256"] = None
        records.append(record)
    return records


def _is_required_runtime_object(item: dict[str, Any]) -> bool:
    return any(role in REQUIRED_RUNTIME_ROLES or role.startswith("vfs-") for role in item.get("roles", []))


def _validate_source_bindings(
    item: dict[str, Any],
    host: str,
    *,
    required_runtime: bool = False,
    index_hashes: set[str] | None = None,
    file_index_hashes: set[str] | None = None,
) -> None:
    """Validate the one canonical source selected for a bounded catalog record."""
    encoding_keys = item.get("encoding_keys")
    sources = item.get("sources")
    if not isinstance(encoding_keys, list) or not encoding_keys or len(encoding_keys) != 1:
        raise CatalogError(f"catalog record must have exactly one encoding key: {item.get('content_key')}")
    if not all(isinstance(key, str) and re.fullmatch(r"[0-9a-f]{32}", key) for key in encoding_keys):
        raise CatalogError(f"invalid encoding key list: {item.get('content_key')}")
    if not isinstance(sources, list) or len(sources) != len(encoding_keys):
        raise CatalogError(f"catalog record source/key cardinality mismatch: {item.get('content_key')}")
    if item.get("encoded_size", 0) <= 0:
        raise CatalogError(f"catalog record has no encoded size: {item.get('content_key')}")
    source = sources[0]
    if not isinstance(source, dict) or source.get("ekey") not in encoding_keys:
        raise CatalogError(f"catalog source EKey is not selected by record: {item.get('content_key')}")
    kind = source.get("kind")
    if kind == "direct":
        size = source.get("encoded_size")
        if not isinstance(size, int) or size <= 0 or size != item["encoded_size"]:
            raise CatalogError(f"direct source size binding mismatch: {item.get('content_key')}")
        if source.get("url") != official_url(host, "data", source["ekey"]):
            raise CatalogError(f"direct source URL/EKey mismatch: {source['ekey']}")
        if not re.fullmatch(r"[0-9a-f]{64}", source.get("sha256", "")):
            raise CatalogError(f"direct source lacks SHA256: {source['ekey']}")
        if "raw_sha256" in source and not re.fullmatch(r"[0-9a-f]{64}", source["raw_sha256"]):
            raise CatalogError(f"direct source has invalid raw SHA256: {source['ekey']}")
        if "raw_sha256" in source and source["raw_sha256"] != source.get("sha256"):
            raise CatalogError(f"direct source SHA256 fields disagree: {source['ekey']}")
        if source.get("decoded_size") != item.get("decoded_size") or source.get("decoded_md5") != item.get("content_key"):
            raise CatalogError(f"direct source decoded binding mismatch: {source['ekey']}")
        if file_index_hashes and source["ekey"] in file_index_hashes:
            raise CatalogError(f"CDN index hash is incorrectly advertised as a payload: {source['ekey']}")
    elif kind == "direct-object":
        size = source.get("encoded_size")
        if not isinstance(size, int) or size <= 0 or size != item["encoded_size"]:
            raise CatalogError(f"direct-object source size binding mismatch: {item.get('content_key')}")
        if source.get("url") != official_url(host, "data", source["ekey"]):
            raise CatalogError(f"direct-object source URL/EKey mismatch: {source['ekey']}")
        advertised_by = source.get("index", "")
        if not re.fullmatch(r"[0-9a-f]{32}", advertised_by):
            raise CatalogError(f"direct-object source lacks file-index provenance: {source['ekey']}")
        if file_index_hashes is None or advertised_by not in file_index_hashes:
            raise CatalogError(f"direct-object source references a non-file CDN index: {advertised_by}")
    elif kind == "archive-range":
        size = source.get("length")
        if not isinstance(size, int) or size <= 0 or size != item["encoded_size"] or source.get("encoded_size") != size:
            raise CatalogError(f"archive source size binding mismatch: {item.get('content_key')}")
        archive = source.get("archive", "")
        if not re.fullmatch(r"[0-9a-f]{32}", archive) or source.get("url") != official_url(host, "data", archive):
            raise CatalogError(f"archive source URL/hash mismatch: {item.get('content_key')}")
        if file_index_hashes and archive in file_index_hashes:
            raise CatalogError(f"CDN index hash is incorrectly used as an archive payload: {archive}")
        if source.get("offset", -1) < 0:
            raise CatalogError(f"archive source offset is invalid: {item.get('content_key')}")
    else:
        raise CatalogError(f"unknown catalog source kind: {kind!r}")
    if required_runtime and item.get("verification") == "fully-verified":
        if "blte_flags" not in item or item["blte_flags"] not in SUPPORTED_BLTE_FLAGS:
            raise CatalogError(f"fully verified runtime object has invalid BLTE flags: {item.get('content_key')}")
        modes = item.get("blte_modes")
        if not isinstance(modes, list) or not modes or set(modes) - SUPPORTED_BLTE_MODES:
            raise CatalogError(f"fully verified runtime object has invalid BLTE modes: {item.get('content_key')}")
        source_flags = source.get("blte_flags")
        source_modes = source.get("blte_modes")
        if source_flags not in SUPPORTED_BLTE_FLAGS or not isinstance(source_modes, list) or not source_modes or set(source_modes) - SUPPORTED_BLTE_MODES:
            raise CatalogError(f"fully verified runtime source has invalid BLTE metadata: {item.get('content_key')}")


def _required_loose_sources_valid(catalog: dict[str, Any]) -> bool:
    for item in catalog.get("loose_files", []):
        sources = item.get("sources", [])
        if not sources or item.get("status") not in {"direct-object", "archive-range"}:
            return False
        if any(source.get("kind") == "unresolved" for source in sources):
            return False
        if any(source.get("encoded_size", 0) <= 0 for source in sources):
            return False
    return True


def _required_indices_valid(catalog: dict[str, Any]) -> bool:
    indices = catalog.get("casc_bootstrap", {}).get("cdn_indices", [])
    return bool(indices) and all(
        item.get("status") == "cached-and-parser-validated"
        and item.get("observed_encoded_size", 0) > 0
        and re.fullmatch(r"[0-9a-f]{64}", item.get("present_sha256", item.get("sha256", "")))
        for item in indices
    )


def validate_catalog(catalog: dict[str, Any]) -> None:
    """Validate invariants shared by host generation and Android consumers."""
    provenance = catalog["provenance"]
    if provenance.get("official_cdn_host") not in {f"https://{host}" for host in CDN_HOSTS}:
        raise CatalogError("catalog CDN host is outside the official HTTPS allowlist")
    if provenance["product"] != PRODUCT or provenance["arch"] != ARCH or provenance["platform"] != PLATFORM or provenance["locale"] != LOCALE:
        raise CatalogError("catalog is not the approved Windows ARM64 enUS product")
    if provenance["version"] != VERSION:
        raise CatalogError(f"unexpected build version: {provenance['version']}")
    for field in ("build_config_hash", "cdn_config_hash"):
        if not re.fullmatch(r"[0-9a-f]{32}", provenance[field]):
            raise CatalogError(f"invalid pinned {field}")
    index_hashes = {
        item.get("hash", "")
        for item in catalog.get("casc_bootstrap", {}).get("cdn_indices", [])
        if isinstance(item, dict)
    }
    file_index_hashes: set[str] = set()
    for item in catalog.get("casc_bootstrap", {}).get("cdn_indices", []):
        if not isinstance(item, dict) or item.get("index_role") != "file-index":
            continue
        if item.get("offset_bytes") != 0:
            raise CatalogError("file-index record must have offset_bytes=0")
        file_index_hashes.add(item.get("hash", ""))
    paths: set[str] = set()
    for item in catalog["loose_files"]:
        path = ensure_safe_relative_path(item["relative_path"])
        folded = path.casefold()
        if folded in paths:
            raise CatalogError(f"duplicate catalog path: {path}")
        paths.add(folded)
        if item["decoded_size"] < 0 or not re.fullmatch(r"[0-9a-f]{32}", item["content_key"]):
            raise CatalogError(f"invalid loose-file record: {path}")
        if item.get("decoded_md5", item["content_key"]) != item["content_key"]:
            raise CatalogError(f"loose decoded MD5 does not match content key: {path}")
        _validate_source_bindings(
            item,
            provenance["official_cdn_host"],
            index_hashes=index_hashes,
            file_index_hashes=file_index_hashes,
        )
    executable = catalog["executable"]
    if executable["relative_path"].casefold() != TARGET_EXE.casefold():
        raise CatalogError("catalog executable is not WowB-ARM64.exe")
    architecture = executable.get("architecture", {})
    if architecture.get("expected_machine") != "0xaa64":
        raise CatalogError("catalog does not require PE ARM64")
    config_paths: set[str] = set()
    config_hashes: set[str] = set()
    for item in catalog.get("required_configs", []):
        digest = item.get("hash", "")
        if not re.fullmatch(r"[0-9a-f]{32}", digest):
            raise CatalogError("invalid config hash")
        path = ensure_safe_relative_path(item["relative_path"])
        if path.casefold() in config_paths or digest in config_hashes:
            raise CatalogError("duplicate config record")
        config_paths.add(path.casefold())
        config_hashes.add(digest)
        if item.get("url") != official_url(provenance["official_cdn_host"], "config", digest):
            raise CatalogError(f"config URL/hash mismatch: {digest}")
        if item.get("status") == "cached-and-parser-validated":
            if not re.fullmatch(r"[0-9a-f]{64}", item.get("present_sha256", item.get("sha256", ""))) or item.get("observed_encoded_size", 0) <= 0:
                raise CatalogError(f"cached config lacks raw integrity metadata: {digest}")
    index_paths: set[str] = set()
    index_hashes: set[str] = set()
    for item in catalog.get("casc_bootstrap", {}).get("cdn_indices", []):
        digest = item.get("hash", "")
        if not re.fullmatch(r"[0-9a-f]{32}", digest):
            raise CatalogError("invalid CDN index hash")
        path = ensure_safe_relative_path(item["relative_path"])
        if path.casefold() in index_paths or digest in index_hashes:
            raise CatalogError("duplicate CDN index record")
        index_paths.add(path.casefold())
        index_hashes.add(digest)
        if item.get("url") != official_url(provenance["official_cdn_host"], "index", digest, ".index"):
            raise CatalogError(f"CDN index URL/hash mismatch: {digest}")
        if item.get("status") == "cached-and-parser-validated":
            if not re.fullmatch(r"[0-9a-f]{64}", item.get("present_sha256", item.get("sha256", ""))) or item.get("observed_encoded_size", 0) <= 0:
                raise CatalogError(f"cached CDN index lacks raw integrity metadata: {digest}")
    for item in catalog.get("casc_bootstrap", {}).get("objects", []):
        if not re.fullmatch(r"[0-9a-f]{32}", item.get("content_key", "")):
            raise CatalogError("invalid runtime content key")
        if item.get("decoded_size", -1) < 0 or item.get("encoded_size", 0) <= 0:
            raise CatalogError(f"invalid runtime object sizes: {item.get('content_key')}")
        required_runtime = _is_required_runtime_object(item)
        if required_runtime:
            if item.get("verification") != "fully-verified":
                raise CatalogError(f"runtime object lacks full decode verification: {item['content_key']}")
            if item.get("decoded_md5") != item["content_key"]:
                raise CatalogError(f"runtime decoded MD5 does not match content key: {item['content_key']}")
        _validate_source_bindings(
            item,
            provenance["official_cdn_host"],
            required_runtime=required_runtime,
            index_hashes=index_hashes,
            file_index_hashes=file_index_hashes,
        )
    required_roles = REQUIRED_RUNTIME_ROLES
    present_roles = {role for item in catalog.get("casc_bootstrap", {}).get("objects", []) for role in item.get("roles", [])}
    missing_roles = required_roles - present_roles
    config_kinds = {item.get("kind") for item in catalog.get("required_configs", []) if item.get("status") != "missing-source"}
    missing_configs = {"build", "cdn"} - config_kinds
    unresolved = catalog.get("build_metadata", {}).get("unresolved_references", [])
    build_info = catalog.get("build_info")
    if not build_info:
        raise CatalogError("catalog lacks installer-compatible .build.info metadata")
    build_info_raw = render_build_info(build_info["fields"])
    parsed_build_info = BuildInfoParser().parse(build_info_raw)
    if parsed_build_info.build_key != provenance["build_config_hash"] or parsed_build_info.cdn_key != provenance["cdn_config_hash"] or parsed_build_info.architecture != ARCH or parsed_build_info.product != PRODUCT:
        raise CatalogError(".build.info metadata does not match catalog provenance")
    if build_info.get("serialized_sha256") != _sha256_bytes(build_info_raw) or build_info.get("serialized_size") != len(build_info_raw):
        raise CatalogError(".build.info serialized integrity metadata mismatch")
    runtime = catalog.get("runtime_metadata", {})
    transfer_over_budget = catalog.get("bounds", {}).get("android_initial_transfer_bytes", 0) > CATALOG_MAX_TRANSFER_BYTES
    loose_sources_valid = _required_loose_sources_valid(catalog)
    indices_valid = _required_indices_valid(catalog)
    has_unverified_direct_objects = any(
        source.get("kind") == "direct-object"
        for item in catalog.get("loose_files", [])
        for source in item.get("sources", [])
    )
    if has_unverified_direct_objects:
        completeness = catalog.get("completeness", {})
        if completeness.get("status") != "blocked" or completeness.get("launch_ready") is not False:
            raise CatalogError("unverified direct-object sources must keep the catalog blocked")
    if missing_roles or missing_configs or unresolved or not runtime.get("resolved_and_verified") or not loose_sources_valid or not indices_valid or transfer_over_budget:
        completeness = catalog.get("completeness", {})
        if completeness.get("status") != "blocked" or completeness.get("launch_ready") is not False:
            raise CatalogError("incomplete catalog must be explicitly blocked")


def _build_catalog_from_parts(
    *,
    build: Any,
    cdn: Any,
    manifest: InstallFile,
    encoding_data: bytes,
    encoding_file: EncodingFile,
    source: CacheSource,
    build_hash: str,
    cdn_hash: str,
    host: str,
    executable_artifact: Path | None = None,
) -> dict[str, Any]:
    selected = filter_install_entries(manifest.entries, manifest.tags, PLATFORM, ARCH, LOCALE)
    canonical = _canonical_install_paths(selected)
    target = [item for item in canonical if item[0].filename.casefold() == TARGET_EXE.casefold()]
    if len(target) != 1:
        raise CatalogError(f"expected one {TARGET_EXE}, found {len(target)}")
    if build.build_uid != PRODUCT:
        raise CatalogError(f"build product mismatch: {build.build_uid!r}")
    if not build.build_name or VERSION not in build.build_name.replace("70009patch1.60.1", VERSION):
        raise CatalogError(f"build name does not identify {VERSION}: {build.build_name!r}")

    # Build metadata identifies every reference, while only root/VFS objects
    # are required for the finite initial CASC skeleton. Encoding/install/etc.
    # remain explicit host provenance and are not silently treated as gameplay.
    refs: list[tuple[str, str, int | None, str | None]] = []
    root_entry = _fast_find_ckey(encoding_data, encoding_file, bytes.fromhex(build.root)) if build.root else None
    if not build.root or root_entry is None:
        raise CatalogError("build root is absent from the encoding file")
    refs.append(("root", build.root, None, root_entry.encoding_keys[0].hex()))
    for index, info in build.get_vfs_entries():
        refs.append((f"vfs-{index}", info.content_key, info.size, info.encoding_key))
    for role, info in (("vfs-root", build.get_vfs_root_info()), ("encoding", build.get_encoding_info()), ("install", build.get_install_info()), ("download", build.get_download_info()), ("size", build.get_size_info()), ("patch", build.get_patch_info()), ("patch-index", build.get_patch_index_info())):
        if info is not None:
            refs.append((role, info.content_key, info.size, info.encoding_key))

    unique_refs: dict[str, dict[str, Any]] = {}
    unresolved_build_refs: list[dict[str, Any]] = []
    optional_delta_refs: list[dict[str, Any]] = []
    encoding_sizes: dict[bytes, int] = {}
    for role, ckey, declared_encoded_size, ekey_hint in refs:
        if ckey in unique_refs:
            unique_refs[ckey]["roles"].append(role)
            continue
        entry = _fast_find_ckey(encoding_data, encoding_file, bytes.fromhex(ckey))
        if entry is None and ekey_hint is None:
            ref = {"role": role, "content_key": ckey.lower(), "declared_decoded_size": declared_encoded_size or 0, "status": "not-present-in-current-encoding"}
            if role in OPTIONAL_DELTA_ROLES:
                optional_delta_refs.append(ref)
            else:
                unresolved_build_refs.append(ref)
            continue
        if entry is not None and ekey_hint is not None and bytes.fromhex(ekey_hint) not in entry.encoding_keys:
            raise CatalogError(f"{role} content/encoding key disagreement: {ckey}")
        ekeys = [bytes.fromhex(ekey_hint)] if ekey_hint is not None else [entry.encoding_keys[0]]
        for ekey in ekeys:
            ekey_entry = _fast_find_ekey(encoding_data, encoding_file, ekey)
            if ekey_entry is not None:
                encoding_sizes[ekey] = ekey_entry.file_size
        record = _ref_record(role=role, content_key=ckey, decoded_size=entry.file_size if entry is not None else None, ekeys=ekeys, source=source, locations={}, host=host, allow_missing=True, declared_encoded_size=declared_encoded_size, encoding_sizes=encoding_sizes)
        record["roles"] = [role]
        unique_refs[ckey] = record

    # Loose files need archive ranges. Metadata is normally direct in the
    # immutable cache, so resolve it before scanning the large index set.
    unresolved: list[tuple[dict[str, Any], list[bytes]]] = []
    loose_records: list[dict[str, Any]] = []
    for entry, relative in canonical:
        ckey = entry.md5_hash.hex()
        ckey_entry = _fast_find_ckey(encoding_data, encoding_file, entry.md5_hash)
        if ckey_entry is None:
            raise CatalogError(f"install entry absent from encoding: {entry.filename}")
        record = _ref_record(role="loose", content_key=ckey, decoded_size=entry.size, ekeys=ckey_entry.encoding_keys, source=source, locations={}, host=host, install_relpath=relative, allow_missing=True)
        record["manifest_tags"] = list(entry.tags)
        record["manifest_file_type"] = entry.file_type
        record["decoded_md5"] = ckey
        record["status"] = "needs-archive-location" if record["sources"][0]["kind"] == "archive-range" else "direct-object"
        if record["status"] == "needs-archive-location" or record["sources"][0]["kind"] == "unresolved":
            unresolved.append((record, ckey_entry.encoding_keys))
        loose_records.append(record)

    if unresolved:
        locations, index_records = _scan_locations(
            source, cdn.archives, file_index_hash=cdn.file_index
        )
        for record, ekeys in unresolved:
            fixed = _ref_record(role="loose", content_key=record["content_key"], decoded_size=record["decoded_size"], ekeys=ekeys, source=source, locations=locations, host=host, install_relpath=record["relative_path"])
            fixed["manifest_tags"] = record["manifest_tags"]
            fixed["manifest_file_type"] = record["manifest_file_type"]
            fixed["decoded_md5"] = record["decoded_md5"]
            fixed["status"] = "archive-range" if fixed["sources"][0]["kind"] == "archive-range" else "direct-object"
            loose_records[loose_records.index(record)] = fixed
    else:
        index_records = []
    # The metadata records are also resolved against the same index map when
    # a cache has no standalone copy. This is rare but must not be hidden.
    missing_meta = [record for record in unique_refs.values() if record["sources"][0]["kind"] not in ("direct",)]
    if missing_meta:
        if not index_records:
            locations, index_records = _scan_locations(
                source, cdn.archives, file_index_hash=cdn.file_index
            )
        for record in missing_meta:
            ekeys = [bytes.fromhex(x) for x in record["encoding_keys"]]
            fixed = _ref_record(role=record["roles"][0], content_key=record["content_key"], decoded_size=record["decoded_size"], ekeys=ekeys, source=source, locations=locations, host=host, encoding_sizes=encoding_sizes)
            fixed["roles"] = record["roles"]
            unique_refs[record["content_key"]] = fixed

    artifact_identity: dict[str, Any] = {"expected_machine": "0xaa64", "expected_architecture": "ARM64", "executed": False}
    if executable_artifact is not None:
        identity = inspect_pe(executable_artifact)
        if not identity["is_arm64"]:
            raise CatalogError(f"wrong executable architecture: {identity['machine_name']}")
        target_entry = target[0][0]
        if executable_artifact.stat().st_size != target_entry.size or _hash_file(executable_artifact, "md5") != target_entry.md5_hash.hex():
            raise CatalogError("executable artifact does not match install manifest")
        artifact_identity.update(identity, sha256=_hash_file(executable_artifact, "sha256"), observed_size=executable_artifact.stat().st_size, observed_md5=_hash_file(executable_artifact, "md5"))
    for record in loose_records:
        if record["relative_path"].casefold() == TARGET_EXE.casefold():
            record["architecture"] = artifact_identity
            executable_record = record
            break
    else:
        raise CatalogError("executable record disappeared")

    config_records = [
        _config_file_record(host, "build", build_hash, source.get(build_hash, "config") or b""),
        _config_file_record(host, "cdn", cdn_hash, source.get(cdn_hash, "config") or b""),
    ]
    optional_delta_indices = _optional_patch_index_records(source, cdn)
    install_info = build.get_install_info()
    if install_info is None or not install_info.encoding_key:
        raise CatalogError("install metadata is required for local CASC")
    build_info_fields = {
        "branch": REGION,
        "build_key": build_hash.lower(),
        "cdn_key": cdn_hash.lower(),
        "cdn_path": CDN_PATH,
        "cdn_hosts": list(CDN_HOSTS),
        "version": VERSION,
        "product": PRODUCT,
        "platform": PLATFORM,
        "architecture": ARCH,
        "locale": LOCALE,
        "region": REGION,
        "install_key": install_info.encoding_key,
        "im_size": None,
    }
    build_info_raw = render_build_info(build_info_fields)
    build_info_record = {
        "relative_path": ".build.info",
        "format": "cascette_tools.formats.build_info.BuildInfoParser",
        "fields": build_info_fields,
        "tags": "Windows arm64 US? enUS speech?:Windows arm64 US? enUS text?",
        "serialized_size": len(build_info_raw),
        "serialized_sha256": _sha256_bytes(build_info_raw),
        "present_sha256": _sha256_bytes(build_info_raw),
        "validation": ["installer_create_build_info", "BuildInfoParser_round_trip"],
    }
    if not index_records:
        _, index_records = _scan_locations(
            source, cdn.archives, file_index_hash=cdn.file_index
        )
    # Re-read config/build fields as plain JSON so Android need not parse a
    # proprietary host object to decide whether the pin matches.
    field_pairs = {name: getattr(build, name) for name in ("root", "encoding", "install", "download", "size", "vfs_root", "patch", "patch_index") if getattr(build, name, None)}
    index_bytes = sum(item.get("observed_encoded_size", 0) for item in index_records)
    loose_encoded = sum(item["encoded_size"] for item in loose_records)
    runtime_objects = [item for item in unique_refs.values() if _is_required_runtime_object(item)]
    bootstrap_encoded = sum(item["encoded_size"] for item in runtime_objects)
    bootstrap_decoded = sum(item["decoded_size"] for item in runtime_objects)
    local_records = []
    for item in sorted(runtime_objects, key=lambda value: value["encoding_keys"][0]):
        ekey = bytes.fromhex(item["encoding_keys"][0])
        local_records.append({"content_key": item["content_key"], "encoding_key": item["encoding_keys"][0], "encoded_size": item["encoded_size"], "local_record_size": 30 + item["encoded_size"], "bucket": compute_bucket(ekey)})
    local_index_bytes = 16 * 0x30000
    required_resolved = not unresolved_build_refs and all(item.get("status") == "cached-and-parser-validated" for item in config_records) and all(_is_required_runtime_object(item) and item.get("verification") == "fully-verified" and item.get("sources") and item["sources"][0]["kind"] != "unresolved" for item in runtime_objects) and all(item.get("status") in {"direct-object", "archive-range"} and item.get("sources") and all(source.get("kind") in {"direct", "archive-range"} and source.get("encoded_size", 0) > 0 for source in item["sources"]) for item in loose_records) and bool(index_records) and all(item.get("status") == "cached-and-parser-validated" and item.get("observed_encoded_size", 0) > 0 and re.fullmatch(r"[0-9a-f]{64}", item.get("present_sha256", item.get("sha256", ""))) for item in index_records)
    transfer_bytes = loose_encoded + index_bytes + bootstrap_encoded + sum(item.get("observed_encoded_size", 0) for item in config_records) + len(build_info_raw)
    catalog: dict[str, Any] = {
        "schema": "wowforever.arm64-client-catalog",
        "schema_version": SCHEMA_VERSION,
        "provenance": {"product": PRODUCT, "region": REGION, "platform": PLATFORM, "arch": ARCH, "locale": LOCALE, "version": VERSION, "build_name": build.build_name, "build_config_hash": build_hash.lower(), "cdn_config_hash": cdn_hash.lower(), "cdn_path": CDN_PATH, "official_cdn_host": host},
        "executable": executable_record,
        "loose_files": sorted(loose_records, key=lambda item: item["relative_path"].casefold()),
        "required_configs": config_records,
        "build_info": build_info_record,
        "casc_bootstrap": {"objects": sorted(unique_refs.values(), key=lambda item: item["content_key"]), "cdn_indices": index_records, "materialization": {"config_paths": [item["relative_path"] for item in config_records], "index_paths": [item["relative_path"] for item in index_records], "local_storage_objects": [item["content_key"] for item in runtime_objects], "local_records": local_records, "local_storage_format": "cascette_tools.core.local_storage.LocalStorage V7", "recipe": {"segment_header_bytes": 480, "local_header_bytes": 30, "local_index_entry_bytes": 18, "local_index_file_bytes": local_index_bytes, "ekey_index_prefix_bytes": 9, "archive_limit_bytes": 1 << 30, "write_raw_blte_then_flush_sorted_indices": True}}},
        "build_metadata": {"fields": field_pairs, "unresolved_references": unresolved_build_refs, "optional_delta_references": optional_delta_refs, "optional_delta_indices": optional_delta_indices, "host_only_objects": sorted([item for item in unique_refs.values() if not _is_required_runtime_object(item)], key=lambda item: item["content_key"])},
        "runtime_metadata": {"required_roles": sorted(REQUIRED_RUNTIME_ROLES), "resolved_and_verified": required_resolved, "installer_evidence": ["install.py Step 1 saves build/CDN config", "Step 2 writes encoding", "Step 3 writes download manifest", "Step 5 loads archive indices", "Step 6 writes install manifest", "Step 7.4 writes root content", "Step 7.5 writes VFS files"], "size_manifest_is_materialized_but_install_continues_if_unavailable": True},
        "bounds": {"loose_file_count": len(loose_records), "loose_decoded_bytes": sum(item["decoded_size"] for item in loose_records), "loose_encoded_bytes": loose_encoded, "cdn_index_count": len(index_records), "cdn_index_bytes": index_bytes, "casc_bootstrap_object_count": len(runtime_objects), "casc_bootstrap_encoded_bytes": bootstrap_encoded, "casc_bootstrap_decoded_bytes": bootstrap_decoded, "required_runtime_encoded_bytes": bootstrap_encoded, "required_runtime_decoded_bytes": bootstrap_decoded, "local_index_bytes": local_index_bytes, "build_info_bytes": len(build_info_raw), "android_initial_transfer_bytes": transfer_bytes, "android_initial_transfer_budget_bytes": CATALOG_MAX_TRANSFER_BYTES, "workspace_encoded_bytes_without_full_game": sum(item["decoded_size"] for item in loose_records) + bootstrap_encoded + index_bytes + (len(runtime_objects) * 30) + local_index_bytes + sum(item.get("observed_encoded_size", 0) for item in config_records) + len(build_info_raw), "workspace_expanded_bytes_without_full_game": sum(item["decoded_size"] for item in loose_records) + bootstrap_decoded + index_bytes + (len(runtime_objects) * 30) + local_index_bytes + sum(item.get("observed_encoded_size", 0) for item in config_records) + len(build_info_raw)},
        "completeness": {"status": "blocked" if unresolved_build_refs or not required_resolved or transfer_bytes > CATALOG_MAX_TRANSFER_BYTES else "finite-bootstrap", "launch_ready": False if unresolved_build_refs or not required_resolved or transfer_bytes > CATALOG_MAX_TRANSFER_BYTES else True, "blocking_unresolved_references": unresolved_build_refs, "optional_delta_provenance": optional_delta_refs, "transfer_budget_status": "exceeds-1GiB" if transfer_bytes > CATALOG_MAX_TRANSFER_BYTES else "within-1GiB", "selected_install_manifest_subset": "complete", "selected_loose_sources": "complete", "build_cdn_metadata": "blocked" if unresolved_build_refs or not required_resolved else "complete", "casc_bootstrap_skeleton": "blocked" if unresolved_build_refs or not required_resolved else "describable-and-materializable", "full_game_data": "not-included", "playable_installation": "unproven", "priority_zero_is_not_used_as_proof": True, "executed_client": False},
    }
    validate_catalog(catalog)
    return catalog


def _verified_overlay_paths(directory: Path | None) -> dict[str, Path]:
    """Return externally verified EKey files without mutating the CDN cache."""
    if directory is None:
        return {}
    if not directory.is_dir():
        raise CatalogError(f"verified object overlay is not a directory: {directory}")
    result: dict[str, Path] = {}
    for path in directory.iterdir():
        if path.is_file() and re.fullmatch(r"[0-9a-fA-F]{32}", path.name):
            result[path.name.lower()] = path
    return result


def _make_cache_source(
    cache_dir: Path | None, verified_overlay: Path | None = None
) -> CacheSource:
    return CacheSource(
        DiskCache(cache_dir), verified_overlay=_verified_overlay_paths(verified_overlay)
    )


def load_real_catalog(
    *,
    cache_dir: Path | None = None,
    host: str = CDN_HOST,
    executable_artifact: Path | None = None,
    verified_overlay: Path | None = None,
) -> dict[str, Any]:
    """Resolve the pinned real build entirely from the immutable cascette cache."""
    # Archive parsing is intentionally quiet: a 1,402-index catalog otherwise
    # emits one parser log line per object.
    structlog.configure(wrapper_class=structlog.make_filtering_bound_logger(40))
    source = _make_cache_source(cache_dir, verified_overlay)
    build_raw = source.get(BUILD_CONFIG, "config")
    cdn_raw = source.get(CDN_CONFIG, "config")
    if build_raw is None or cdn_raw is None:
        raise CatalogError("pinned build/CDN config is absent from cascette cache")
    build = BuildConfigParser().parse(build_raw)
    cdn = CDNConfigParser().parse(cdn_raw)
    encoding_info = build.get_encoding_info()
    install_info = build.get_install_info()
    if encoding_info is None or install_info is None or encoding_info.encoding_key is None or install_info.encoding_key is None:
        raise CatalogError("pinned build lacks encoding/install references")
    encoding_raw = source.get(encoding_info.encoding_key, "data")
    install_raw = source.get(install_info.encoding_key, "data")
    if encoding_raw is None or install_raw is None:
        raise CatalogError("pinned encoding/install object is absent from cascette cache")
    encoding_data = _decode(encoding_raw)
    install_data = _decode(install_raw)
    encoding_file = EncodingParser().parse(encoding_data)
    manifest = InstallParser().parse(install_data)
    return _build_catalog_from_parts(build=build, cdn=cdn, manifest=manifest, encoding_data=encoding_data, encoding_file=encoding_file, source=source, build_hash=BUILD_CONFIG, cdn_hash=CDN_CONFIG, host=host, executable_artifact=executable_artifact)


def materialize_skeleton(catalog: dict[str, Any], output_dir: Path, source: CacheSource) -> dict[str, Any]:
    """Materialize configs, CDN indices, and required runtime CASC objects.

    This writes no install-manifest loose files and no full archive payloads.
    The result is a bounded CASC store that can be parsed and extended by an
    Android consumer; it is deliberately not represented as a playable client.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    data = output_dir / "Data"
    local = LocalStorage(output_dir)
    local.initialize()
    for config in catalog["required_configs"]:
        raw = source.get(config["hash"], "config")
        if raw is None:
            raise CatalogError(f"missing cached config {config['hash']}")
        local.save_config(config["hash"], raw)
    for index in catalog["casc_bootstrap"]["cdn_indices"]:
        raw = source.get(index["hash"], "index")
        if raw is None:
            raise CatalogError(f"missing cached CDN index {index['hash']}")
        local.save_cdn_index(index["hash"], raw)
    object_keys = set(catalog["casc_bootstrap"]["materialization"]["local_storage_objects"])
    written = 0
    for item in catalog["casc_bootstrap"]["objects"]:
        if item["content_key"] not in object_keys:
            continue
        source_item = item["sources"][0]
        if source_item["kind"] != "direct":
            raise CatalogError(f"skeleton object {item['content_key']} is not directly cached")
        raw = source.get(source_item["ekey"], "data")
        if raw is None:
            raise CatalogError(f"missing cached skeleton object {source_item['ekey']}")
        if _sha256_bytes(raw) != source_item.get("sha256"):
            raise CatalogError(f"cached runtime object SHA256 mismatch {source_item['ekey']}")
        local.write_content(bytes.fromhex(source_item["ekey"]), raw)
        written += 1
    local.flush_indices()
    build_info_raw = render_build_info(catalog["build_info"]["fields"])
    parsed_build_info = BuildInfoParser().parse(build_info_raw)
    if parsed_build_info.build_key != catalog["provenance"]["build_config_hash"] or parsed_build_info.architecture != ARCH:
        raise CatalogError("materialized .build.info failed parser round-trip")
    (output_dir / ".build.info").write_bytes(build_info_raw)
    expected = {item["encoding_key"]: item for item in catalog["casc_bootstrap"]["materialization"]["local_records"]}
    for ekey_hex, record in expected.items():
        entry = local.find_entry(bytes.fromhex(ekey_hex))
        if entry is None or entry.size != record["local_record_size"]:
            raise CatalogError(f"local CASC entry mismatch for {ekey_hex}")
    parsed_indices = [parse_local_idx_file(path.read_bytes()) for path in sorted(local.data_path.glob("*.idx"))]
    if len(parsed_indices) != 16 or any(parsed.version != 7 for parsed in parsed_indices):
        raise CatalogError("materialized local CASC index set is not sixteen V7 files")
    parsed_keys = {entry.key for parsed in parsed_indices for entry in parsed.entries}
    if not all(bytes.fromhex(ekey)[:9] in parsed_keys for ekey in expected):
        raise CatalogError("materialized local CASC indices omit a runtime ekey")
    return {"output": str(output_dir), "objects_written": written, "build_info_sha256": _sha256_bytes(build_info_raw), "local_index_files": len(parsed_indices), "validated_local_records": len(expected), "local_statistics": local.get_statistics(), "data_directory": str(data)}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=None)
    parser.add_argument("--output", type=Path, required=True, help="small JSON catalog outside the repository")
    parser.add_argument("--official-executable", type=Path, default=None, help="optional static PE validation; never executed")
    parser.add_argument(
        "--verified-overlay",
        type=Path,
        default=None,
        help="optional external directory of already-downloaded EKey payloads (named by EKey); never writes the CDN cache",
    )
    parser.add_argument("--materialize-skeleton", type=Path, default=None, help="optional external CASC bootstrap output")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    catalog = load_real_catalog(
        cache_dir=args.cache_dir,
        executable_artifact=args.official_executable,
        verified_overlay=args.verified_overlay,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(catalog, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"catalog": str(args.output), "bounds": catalog["bounds"], "completeness": catalog["completeness"]}, sort_keys=True))
    if args.materialize_skeleton is not None:
        summary = materialize_skeleton(
            catalog,
            args.materialize_skeleton,
            _make_cache_source(args.cache_dir, args.verified_overlay),
        )
        print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
