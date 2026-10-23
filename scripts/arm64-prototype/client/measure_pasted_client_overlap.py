#!/usr/bin/env python3
"""Measure CASC EKey overlap between the local x86-64 client and ARM64 builds.

Only official build/config/download/install metadata is acquired. The live
``client/`` tree is read-only: the downloaded-object set comes from its
InstallState, and local ``.idx`` files are parsed to corroborate those full
EKeys by their 9-byte local-index prefixes. No archives or game payloads are
enumerated or read.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import importlib.util
import json
import re
import sqlite3
import sys
import tempfile
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import BinaryIO, Iterable, Iterator
from urllib.parse import urlsplit

from cascette_tools.core.cache import DiskCache
from cascette_tools.core.local_storage import parse_local_idx_file
from cascette_tools.formats.build_info import BuildInfoParser
from cascette_tools.formats.config import BuildConfigParser, CDNConfigParser
from cascette_tools.formats.install import InstallParser

from forever_client.casc import filter_install_entries


PRODUCT = "wow_classic_beta"
REGION = "us"
PLATFORM = "Windows"
ARCH = "arm64"
LOCALE = "enUS"
CDN_PATH = "tpr/wow"
CDN_HOST = "us.cdn.blizzard.com"
VERSION_HOST = "us.version.battle.net"
VERSION_URL = f"https://{VERSION_HOST}/v2/products/{PRODUCT}/versions"
SELECTION_QUERY = "Windows,arm64,enUS"
LOCAL_KEY_WIDTH = 9
MAX_CONFIG_BYTES = 4 << 20
MAX_METADATA_ENCODED_BYTES = 1 << 30
MAX_METADATA_DECODED_BYTES = 1 << 30
IO_BYTES = 1 << 20
DEFAULT_CLIENT_ROOT = Path(__file__).resolve().parents[3] / "client"
DEFAULT_CACHE_ROOT = Path.home() / ".cache" / "cascette"
DEFAULT_OUTPUT_DIR = Path(
    "/home/adrian/wowforever-artifacts/measure/pasted-client-overlap-2026-10-20"
)

# This is the independently pinned ARM64 catalog build already used by the
# repository's catalog and updater-manifest gates.
SOURCE_BUILD_ID = 70009
SOURCE_VERSION = "1.60.1.70009"
SOURCE_BUILD_CONFIG = "05215079e3905ef5922ae0b03ffefb73"
SOURCE_CDN_CONFIG = "9b3c456dbb837d133a026d380c7c13e9"

_VERSION_RE = re.compile(r"(?P<build>\d+)patch(?P<version>[\d.]+)")
_MD5_RE = re.compile(r"[0-9a-fA-F]{32}\Z")


class MeasurementError(RuntimeError):
    """Metadata or local evidence was absent, inconsistent, or unsafe."""


@dataclass(frozen=True)
class OverlapResult:
    target_object_count: int
    target_encoded_bytes: int
    reused_object_count: int
    reused_encoded_bytes: int
    remaining_object_count: int
    remaining_encoded_bytes: int


def _load_manifest_engine():
    path = Path(__file__).with_name("update_manifest.py")
    spec = importlib.util.spec_from_file_location("_overlap_update_manifest", path)
    if spec is None or spec.loader is None:
        raise MeasurementError(f"cannot load the repository manifest parser: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


_MANIFEST_ENGINE = _load_manifest_engine()


def _normalise_ekey(value: str) -> str:
    if not isinstance(value, str) or _MD5_RE.fullmatch(value) is None:
        raise ValueError(f"invalid full EKey: {value!r}")
    return value.lower()


def calculate_overlap(
    target_objects: Iterable[tuple[str, int]], local_ekeys: Iterable[str]
) -> OverlapResult:
    """Calculate exact bytes for sorted, unique target EKeys.

    The target iterable must be sorted by EKey and deduplicated. The production
    caller streams a GROUP BY query from SQLite, avoiding a million-object
    in-memory target map; this public helper keeps the arithmetic unit-testable.
    """
    local = {_normalise_ekey(key) for key in local_ekeys}
    previous: str | None = None
    total_count = total_bytes = reused_count = reused_bytes = 0
    for raw_ekey, encoded_size in target_objects:
        ekey = _normalise_ekey(raw_ekey)
        if previous is not None and ekey <= previous:
            raise ValueError("target EKeys must be sorted and unique")
        if type(encoded_size) is not int or encoded_size <= 0:
            raise ValueError(f"invalid encoded size for {ekey}: {encoded_size!r}")
        previous = ekey
        total_count += 1
        total_bytes += encoded_size
        if ekey in local:
            reused_count += 1
            reused_bytes += encoded_size
    return OverlapResult(
        target_object_count=total_count,
        target_encoded_bytes=total_bytes,
        reused_object_count=reused_count,
        reused_encoded_bytes=reused_bytes,
        remaining_object_count=total_count - reused_count,
        remaining_encoded_bytes=total_bytes - reused_bytes,
    )


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(IO_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _iso_mtime(path: Path) -> str:
    return datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()


def _read_cache(cache_root: Path) -> DiskCache:
    """Create a path-only DiskCache resolver without its constructor writes."""
    cache = object.__new__(DiskCache)
    cache.base_dir = cache_root
    cache.cdn_dir = cache_root / "cdn"
    cache.api_dir = cache_root / "api"
    cache.metadata_file = cache_root / "metadata.json"
    cache.metadata = {}
    return cache


def _object_url(kind: str, digest: str) -> str:
    digest = _normalise_ekey(digest)
    content = "config" if kind == "config" else "data"
    return f"https://{CDN_HOST}/{CDN_PATH}/{content}/{digest[:2]}/{digest[2:4]}/{digest}"


def _fetch_https(
    url: str,
    output: Path,
    *,
    body_limit: int,
    expected_size: int | None,
    timeout: float,
) -> dict[str, object]:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise MeasurementError(f"unsafe metadata URL: {url}")
    if parsed.hostname == VERSION_HOST and parsed.path == f"/v2/products/{PRODUCT}/versions":
        host = VERSION_HOST
    elif parsed.hostname == CDN_HOST and parsed.path.startswith(f"/{CDN_PATH}/"):
        host = CDN_HOST
    else:
        raise MeasurementError(f"metadata URL is outside the official hosts/path: {url}")

    connection = http.client.HTTPSConnection(host, timeout=timeout)
    response = None
    received = 0
    try:
        connection.request(
            "GET", parsed.path,
            headers={"Accept-Encoding": "identity", "User-Agent": "wowforever-overlap-measure/1"},
        )
        response = connection.getresponse()
        if response.status != 200:
            raise MeasurementError(f"HTTP {response.status} fetching {url}; redirects are not followed")
        content_encoding = response.getheader("Content-Encoding")
        if content_encoding and content_encoding.lower() != "identity":
            raise MeasurementError(f"unexpected Content-Encoding {content_encoding!r} for {url}")
        content_length = response.getheader("Content-Length")
        if content_length is None or not content_length.isdigit():
            raise MeasurementError(f"missing/invalid Content-Length for {url}")
        declared = int(content_length)
        if declared <= 0 or declared > body_limit:
            raise MeasurementError(f"response length {declared} is outside bound {body_limit}: {url}")
        if expected_size is not None and declared != expected_size:
            raise MeasurementError(f"Content-Length {declared} != expected {expected_size}: {url}")
        with output.open("xb") as destination:
            while received < declared:
                chunk = response.read(min(IO_BYTES, declared - received))
                if not chunk:
                    raise MeasurementError(f"truncated response: got {received} of {declared} bytes: {url}")
                destination.write(chunk)
                received += len(chunk)
            if response.read(1):
                raise MeasurementError(f"response exceeds declared Content-Length: {url}")
        return {
            "kind": "official-https",
            "url": url,
            "filename": output.name,
            "http_status": 200,
            "received_bytes": received,
            "modified_at_utc": _iso_mtime(output),
        }
    finally:
        if response is not None:
            response.close()
        connection.close()


def _acquire_object(
    cache: DiskCache,
    *,
    kind: str,
    digest: str,
    expected_size: int | None,
    scratch: Path,
    timeout: float,
) -> tuple[Path, dict[str, object]]:
    digest = _normalise_ekey(digest)
    cache_path = cache._get_cdn_cache_path(digest, kind, CDN_PATH)
    if cache_path.is_symlink():
        raise MeasurementError(f"metadata cache object is a symlink: {cache_path}")
    if cache_path.exists():
        if not cache_path.is_file():
            raise MeasurementError(f"metadata cache object is not a regular file: {cache_path}")
        actual_size = cache_path.stat().st_size
        if expected_size is not None and actual_size != expected_size:
            raise MeasurementError(f"cached metadata size {actual_size} != expected {expected_size}: {cache_path}")
        return cache_path, {
            "kind": "read-only-cascette-cache",
            "path": str(cache_path),
            "size_bytes": actual_size,
            "modified_at_utc": _iso_mtime(cache_path),
        }
    url = _object_url(kind, digest)
    limit = MAX_CONFIG_BYTES if kind == "config" else MAX_METADATA_ENCODED_BYTES
    network = _fetch_https(
        url, scratch, body_limit=limit, expected_size=expected_size, timeout=timeout
    )
    return scratch, network


def _versions_row(raw: bytes, expected_build_id: int) -> dict[str, object]:
    if not raw or len(raw) > 64 * 1024:
        raise MeasurementError(f"versions table size outside bounds: {len(raw)} bytes")
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise MeasurementError(f"versions table is not UTF-8: {exc}") from exc
    header: list[str] | None = None
    sequence: str | None = None
    rows: list[dict[str, str]] = []
    for line in lines:
        if line.startswith("## seqn = "):
            sequence = line.partition("=")[2].strip()
            continue
        if "|" not in line:
            continue
        fields = line.split("|")
        if header is None and any(item.startswith("Region!") for item in fields):
            header = [item.partition("!")[0] for item in fields]
            continue
        if header is None:
            continue
        if len(fields) != len(header):
            raise MeasurementError("versions table contains a row with the wrong number of fields")
        row = dict(zip(header, fields, strict=True))
        if row.get("Region") == REGION:
            rows.append(row)
    if len(rows) != 1:
        raise MeasurementError(f"versions table has {len(rows)} us rows; expected exactly one")
    row = rows[0]
    try:
        build_id = int(row["BuildId"])
    except (KeyError, ValueError) as exc:
        raise MeasurementError("versions table has an invalid BuildId") from exc
    build_config = row.get("BuildConfig", "").lower()
    cdn_config = row.get("CDNConfig", "").lower()
    if _MD5_RE.fullmatch(build_config) is None or _MD5_RE.fullmatch(cdn_config) is None:
        raise MeasurementError("versions table contains invalid build/CDN config hashes")
    if build_id != expected_build_id:
        raise MeasurementError(f"published us build is {build_id}, expected {expected_build_id}")
    return {
        "region": REGION,
        "build_id": build_id,
        "version": row.get("VersionsName", "").strip(),
        "build_config_hash": build_config,
        "cdn_config_hash": cdn_config,
        "product_config_hash": row.get("ProductConfig", "").strip() or None,
        "sequence": sequence,
        "raw_sha256": hashlib.sha256(raw).hexdigest(),
        "raw_size_bytes": len(raw),
        "url": VERSION_URL,
    }


def _validate_build(build_raw: bytes, cdn_raw: bytes, *, build_hash: str, cdn_hash: str,
                    expected_build_id: int, expected_version: str | None = None) -> tuple[object, object, str]:
    actual_build_hash = hashlib.md5(build_raw).hexdigest()
    actual_cdn_hash = hashlib.md5(cdn_raw).hexdigest()
    if actual_build_hash != build_hash or actual_cdn_hash != cdn_hash:
        raise MeasurementError(
            f"config hash mismatch: build={actual_build_hash} (expected {build_hash}), "
            f"cdn={actual_cdn_hash} (expected {cdn_hash})"
        )
    try:
        build = BuildConfigParser().parse(build_raw)
        cdn = CDNConfigParser().parse(cdn_raw)
    except Exception as exc:
        raise MeasurementError(f"could not parse hash-bound configs: {exc}") from exc
    if build.build_uid != PRODUCT:
        raise MeasurementError(f"unexpected build product {build.build_uid!r}")
    if build.build_num and str(build.build_num) != str(expected_build_id):
        raise MeasurementError(f"build config number {build.build_num!r} != {expected_build_id}")
    if cdn.builds and build_hash not in {item.lower() for item in cdn.builds}:
        raise MeasurementError("CDN config does not reference the selected build config")
    name = build.build_name or ""
    match = _VERSION_RE.search(name)
    if match is None or int(match.group("build")) != expected_build_id:
        raise MeasurementError(f"build config name does not identify build {expected_build_id}: {name!r}")
    version = f"{match.group('version')}.{expected_build_id}"
    if expected_version is not None and version != expected_version:
        raise MeasurementError(f"build config version {version} != expected {expected_version}")
    return build, cdn, name


def _require_info(build: object, role: str) -> object:
    info = getattr(build, f"get_{role}_info")()
    if (
        info is None
        or not info.content_key
        or not info.encoding_key
        or type(info.size) is not int
        or info.size <= 0
        or info.size > MAX_METADATA_ENCODED_BYTES
    ):
        raise MeasurementError(f"build config lacks a bounded, complete {role} reference")
    _normalise_ekey(info.content_key)
    _normalise_ekey(info.encoding_key)
    return info


class _BoundedWriter:
    def __init__(self, output: BinaryIO, limit: int):
        self.output = output
        self.limit = limit
        self.written = 0

    def write(self, data: bytes) -> int:
        if self.written + len(data) > self.limit:
            raise MeasurementError(f"decoded metadata exceeds {self.limit} byte bound")
        written = self.output.write(data)
        self.written += written
        return written


def _decode_metadata(
    cache: DiskCache,
    info: object,
    *,
    role: str,
    scratch: Path,
    timeout: float,
) -> tuple[Path, dict[str, object]]:
    raw_path, source = _acquire_object(
        cache,
        kind="data",
        digest=info.encoding_key,
        expected_size=info.size,
        scratch=scratch.with_suffix(".raw"),
        timeout=timeout,
    )
    decoded_path = scratch.with_suffix(".decoded")
    try:
        with raw_path.open("rb") as raw, decoded_path.open("xb") as decoded:
            evidence = _MANIFEST_ENGINE.stream_decode_blte(
                raw,
                _BoundedWriter(decoded, MAX_METADATA_DECODED_BYTES),
                expected_ekey=info.encoding_key.lower(),
                encoded_size=info.size,
            )
    except Exception as exc:
        raise MeasurementError(f"{role} metadata BLTE/EKey validation failed: {exc}") from exc
    if evidence["decoded_md5"] != info.content_key.lower():
        raise MeasurementError(
            f"{role} metadata CKey mismatch: {evidence['decoded_md5']} != {info.content_key.lower()}"
        )
    return decoded_path, {
        "role": role,
        "content_key": info.content_key.lower(),
        "encoding_key": info.encoding_key.lower(),
        "declared_encoded_size": info.size,
        "decoded_size": evidence["decoded_size"],
        "decoded_md5": evidence["decoded_md5"],
        "raw_sha256": _sha256_file(raw_path),
        "decoded_sha256": _sha256_file(decoded_path),
        "blte_modes": evidence["blte_modes"],
        "source": source,
    }


def _selected_install_evidence(path: Path) -> dict[str, object]:
    try:
        with path.open("rb") as stream:
            manifest = InstallParser().parse(stream)
            if stream.read(1):
                raise MeasurementError("install manifest has trailing bytes")
    except MeasurementError:
        raise
    except Exception as exc:
        raise MeasurementError(f"could not parse install manifest: {exc}") from exc
    selected = filter_install_entries(manifest.entries, manifest.tags, PLATFORM, ARCH, LOCALE)
    executable = [
        entry for entry in selected
        if entry.filename.replace("\\", "/").casefold() == "wowb-arm64.exe"
    ]
    if len(executable) != 1:
        raise MeasurementError(
            f"{SELECTION_QUERY} install selection has {len(executable)} WowB-ARM64.exe entries"
        )
    return {
        "all_entry_count": len(manifest.entries),
        "selected_entry_count": len(selected),
        "selection_query": SELECTION_QUERY,
        "tag_types": {tag.name: tag.tag_type for tag in manifest.tags},
        "arm64_executable_content_key": executable[0].md5_hash.hex(),
        "arm64_executable_decoded_size": executable[0].size,
        "architecture_evidence": "verified install-manifest selection tags and WowB-ARM64.exe path; PE bytes not acquired",
    }


def _create_download_db(path: Path) -> sqlite3.Connection:
    db = sqlite3.connect(path)
    db.execute("PRAGMA cache_size=-8192")
    db.execute("PRAGMA temp_store=FILE")
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    _MANIFEST_ENGINE._create_spool_tables(db)
    return db


def _parse_download_plan(
    path: Path, db: sqlite3.Connection, *, query: str = SELECTION_QUERY
) -> tuple[dict[str, object], Iterator[tuple[str, int]]]:
    try:
        with path.open("rb") as stream:
            stats, tags, _ = _MANIFEST_ENGINE.parse_download_manifest(
                stream, db, query=query
            )
    except Exception as exc:
        gate_error = getattr(_MANIFEST_ENGINE, "GateError", ValueError)
        if isinstance(exc, gate_error):
            raise MeasurementError(f"download manifest parse failed: {exc}") from exc
        raise
    conflicts = db.execute(
        "SELECT COUNT(*) FROM (SELECT ekey FROM download_entries WHERE selected=1 "
        "GROUP BY ekey HAVING MIN(encoded_size) != MAX(encoded_size))"
    ).fetchone()[0]
    if conflicts:
        raise MeasurementError(f"{conflicts} selected EKeys have conflicting encoded sizes")
    unique_count, unique_bytes = db.execute(
        "SELECT COUNT(*), COALESCE(SUM(encoded_size),0) FROM ("
        "SELECT ekey, MIN(encoded_size) AS encoded_size FROM download_entries "
        "WHERE selected=1 GROUP BY ekey)"
    ).fetchone()
    if unique_count != stats.selected_ekey_count:
        raise MeasurementError("selected unique EKey count disagrees with manifest parser")
    plan = {
        "download_manifest_entry_count": stats.entry_count,
        "selected_manifest_entry_count": stats.selected_count,
        "selected_unique_object_count": unique_count,
        "selected_unique_encoded_bytes": unique_bytes,
        "selected_manifest_entry_encoded_bytes_including_duplicates": stats.selected_bytes,
        "selection_query": query,
        "selection_semantics": "OR within each tag_type group and AND between groups, same project filter_install_entries logic",
        "tag_types": {tag.name: tag.tag_type for tag in tags},
        "encoded_size_source": "encoded_size field in hash-bound download manifest; deduplicated by full EKey",
        "duplicate_size_conflicts": 0,
    }

    def rows() -> Iterator[tuple[str, int]]:
        for ekey, encoded_size in db.execute(
            "SELECT ekey, MIN(encoded_size) FROM download_entries WHERE selected=1 "
            "GROUP BY ekey ORDER BY ekey"
        ):
            yield bytes(ekey).hex(), int(encoded_size)

    return plan, rows()


def _stable_read(path: Path) -> tuple[bytes, dict[str, object]]:
    before = path.stat()
    if path.is_symlink() or not path.is_file():
        raise MeasurementError(f"expected regular read-only evidence file: {path}")
    raw = path.read_bytes()
    after = path.stat()
    if (before.st_ino, before.st_size, before.st_mtime_ns) != (
        after.st_ino, after.st_size, after.st_mtime_ns
    ):
        raise MeasurementError(f"local evidence changed while being read: {path}")
    return raw, {
        "path": str(path),
        "size_bytes": len(raw),
        "modified_at_utc": datetime.fromtimestamp(after.st_mtime, timezone.utc).isoformat(),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "inode": after.st_ino,
        "mtime_ns": after.st_mtime_ns,
    }


def _read_local_evidence(
    client_root: Path,
) -> tuple[set[str], set[str], dict[str, object], dict[str, object]]:
    state_path = client_root / "Data" / ".install_state.json"
    state_raw, state_source = _stable_read(state_path)
    try:
        state = json.loads(state_raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise MeasurementError(f"InstallState is not valid JSON: {exc}") from exc
    if not isinstance(state, dict) or not isinstance(state.get("downloaded"), list):
        raise MeasurementError("InstallState does not contain the expected downloaded EKey list")
    state_keys = [_normalise_ekey(key) for key in state["downloaded"]]
    if len(set(state_keys)) != len(state_keys):
        raise MeasurementError("InstallState downloaded EKey list contains duplicates")
    state_build = state.get("build_config_hash")
    state_build = _normalise_ekey(state_build)
    if state_build != SOURCE_BUILD_CONFIG:
        raise MeasurementError(
            f"InstallState build config {state_build} != pinned {SOURCE_BUILD_CONFIG}"
        )

    idx_dir = client_root / "Data" / "data"
    idx_paths = sorted(idx_dir.glob("*.idx"))
    if not idx_paths:
        raise MeasurementError(f"no local .idx files found in {idx_dir}")
    index_prefixes: set[str] = set()
    total_index_entries = 0
    index_sources: list[dict[str, object]] = []
    initial_paths = [path.name for path in idx_paths]
    for path in idx_paths:
        raw, source = _stable_read(path)
        try:
            parsed = parse_local_idx_file(raw)
        except Exception as exc:
            raise MeasurementError(f"could not parse local index {path}: {exc}") from exc
        if parsed.ekey_length != LOCAL_KEY_WIDTH:
            raise MeasurementError(
                f"local index {path} has {parsed.ekey_length}-byte keys, expected {LOCAL_KEY_WIDTH}"
            )
        total_index_entries += len(parsed.entries) + len(parsed.update_entries)
        index_prefixes.update(entry.key.hex() for entry in parsed.entries)
        index_prefixes.update(entry.key.hex() for entry in parsed.update_entries)
        source.update({
            "bucket": parsed.bucket,
            "version": parsed.version,
            "sorted_entry_count": len(parsed.entries),
            "update_entry_count": len(parsed.update_entries),
        })
        index_sources.append(source)
    if [path.name for path in sorted(idx_dir.glob("*.idx"))] != initial_paths:
        raise MeasurementError("local .idx file set changed while being read")

    state_prefix_counts = Counter(key[: LOCAL_KEY_WIDTH * 2] for key in state_keys)
    ambiguous_prefixes = {prefix for prefix, count in state_prefix_counts.items() if count > 1}
    confirmed_keys = {
        key for key in state_keys
        if key[: LOCAL_KEY_WIDTH * 2] in index_prefixes
        and key[: LOCAL_KEY_WIDTH * 2] not in ambiguous_prefixes
    }

    build_info_path = client_root / ".build.info"
    build_info_raw, build_info_source = _stable_read(build_info_path)
    try:
        build_info = BuildInfoParser().parse(build_info_raw)
    except Exception as exc:
        raise MeasurementError(f"could not parse local .build.info: {exc}") from exc
    if build_info.product != PRODUCT or build_info.version != SOURCE_VERSION:
        raise MeasurementError(
            f"local .build.info is not the pinned {PRODUCT}/{SOURCE_VERSION} build"
        )
    if (
        build_info.build_key != SOURCE_BUILD_CONFIG
        or build_info.cdn_key != SOURCE_CDN_CONFIG
        or build_info.architecture != "x86_64"
        or not any(item.code == LOCALE for item in build_info.locale_configs)
    ):
        raise MeasurementError(
            "local .build.info does not match the expected x86_64/enUS 70009 config"
        )

    evidence = {
        "build_info": {
            **build_info_source,
            "product": build_info.product,
            "version": build_info.version,
            "build_config_hash": build_info.build_key,
            "cdn_config_hash": build_info.cdn_key,
            "platform": build_info.platform,
            "architecture": build_info.architecture,
            "locale": [item.code for item in build_info.locale_configs],
            "tags": build_info.tags,
        },
        "install_state": {
            **state_source,
            "build_config_hash": state_build,
            "downloaded_full_ekey_count": len(state_keys),
            "total_bytes_written": state.get("total_bytes_written"),
            "failed_count": len(state.get("failed", [])) if isinstance(state.get("failed"), list) else None,
        },
        "local_indices": {
            "directory": str(idx_dir),
            "file_count": len(index_sources),
            "files": index_sources,
            "sorted_and_update_entry_count": total_index_entries,
            "unique_9_byte_prefix_count": len(index_prefixes),
            "full_state_ekeys_with_index_prefix": len(confirmed_keys),
            "state_ekeys_without_index_prefix": len(state_keys) - sum(
                key[: LOCAL_KEY_WIDTH * 2] in index_prefixes for key in state_keys
            ),
            "state_ekeys_with_ambiguous_9_byte_prefix": sum(
                state_prefix_counts[key[: LOCAL_KEY_WIDTH * 2]] > 1 for key in state_keys
            ),
            "index_only_prefix_count": len(index_prefixes - set(state_prefix_counts)),
            "key_width_bytes": LOCAL_KEY_WIDTH,
            "key_note": "local indexes retain only the first 9 EKey bytes; the measured full-key local set is InstallState keys corroborated by a unique index prefix",
        },
    }
    return confirmed_keys, set(state_keys), evidence, {
        "install_state_full_ekey_count": len(state_keys),
        "index_confirmed_full_ekey_count": len(confirmed_keys),
        "state_ekeys_without_index_prefix_count": evidence["local_indices"]["state_ekeys_without_index_prefix"],
        "ambiguous_prefix_ekey_count": evidence["local_indices"]["state_ekeys_with_ambiguous_9_byte_prefix"],
    }


def _measure_build(
    *,
    build_id: int,
    build_hash: str,
    cdn_hash: str,
    expected_version: str | None,
    cache: DiskCache,
    scratch: Path,
    timeout: float,
    manifest_db_path: Path,
    local_state_ekeys: set[str],
    index_confirmed_ekeys: set[str],
) -> tuple[dict[str, object], Iterator[tuple[str, int]], sqlite3.Connection, dict[str, object] | None]:
    build_path, build_source = _acquire_object(
        cache,
        kind="config",
        digest=build_hash,
        expected_size=None,
        scratch=scratch / f"build-{build_id}.config",
        timeout=timeout,
    )
    cdn_path, cdn_source = _acquire_object(
        cache,
        kind="config",
        digest=cdn_hash,
        expected_size=None,
        scratch=scratch / f"cdn-{build_id}.config",
        timeout=timeout,
    )
    build_raw, cdn_raw = build_path.read_bytes(), cdn_path.read_bytes()
    build, _cdn, build_name = _validate_build(
        build_raw,
        cdn_raw,
        build_hash=build_hash,
        cdn_hash=cdn_hash,
        expected_build_id=build_id,
        expected_version=expected_version,
    )
    install_info = _require_info(build, "install")
    download_info = _require_info(build, "download")
    install_path, install_evidence = _decode_metadata(
        cache,
        install_info,
        role=f"{build_id} install manifest",
        scratch=scratch / f"install-{build_id}",
        timeout=timeout,
    )
    install_selection = _selected_install_evidence(install_path)
    download_path, download_evidence = _decode_metadata(
        cache,
        download_info,
        role=f"{build_id} download manifest",
        scratch=scratch / f"download-{build_id}",
        timeout=timeout,
    )
    db = _create_download_db(manifest_db_path)
    download_plan, rows = _parse_download_plan(download_path, db)
    host_architecture_plan: dict[str, object] | None = None
    if build_id == SOURCE_BUILD_ID:
        # The local InstallState is an x86_64 70009 inventory. Parse the same
        # authenticated download manifest with its x86_64 tags to give an exact
        # completeness denominator (and to avoid calling it an ARM64 subset).
        x86_db = _create_download_db(manifest_db_path.with_name("download-70009-x86_64.sqlite3"))
        x86_plan, x86_rows = _parse_download_plan(
            download_path, x86_db, query="Windows,x86_64,enUS"
        )
        state_coverage = calculate_overlap(x86_rows, local_state_ekeys)
        def index_confirmed_rows() -> Iterator[tuple[str, int]]:
            for key, size in x86_db.execute(
                "SELECT ekey, MIN(encoded_size) FROM download_entries WHERE selected=1 "
                "GROUP BY ekey ORDER BY ekey"
            ):
                yield bytes(key).hex(), int(size)

        index_confirmed_coverage = calculate_overlap(
            index_confirmed_rows(), index_confirmed_ekeys
        )
        host_architecture_plan = {
            "architecture_tag": "x86_64",
            "download_manifest": x86_plan,
            "install_state_intersection": asdict(state_coverage),
            "index_confirmed_intersection": asdict(index_confirmed_coverage),
            "install_state_objects_not_in_selected_x86_plan": (
                len(local_state_ekeys) - state_coverage.reused_object_count
            ),
            "index_confirmed_objects_not_in_selected_x86_plan": (
                len(index_confirmed_ekeys) - index_confirmed_coverage.reused_object_count
            ),
        }
        x86_db.close()
    build_evidence: dict[str, object] = {
        "build_id": build_id,
        "version": expected_version or _VERSION_RE.search(build_name).group("version") + f".{build_id}",
        "build_name": build_name,
        "product": PRODUCT,
        "region": REGION,
        "platform_tag": PLATFORM,
        "architecture_tag": ARCH,
        "locale_tag": LOCALE,
        "build_config_hash": build_hash,
        "build_config_sha256": _sha256_file(build_path),
        "build_config_source": build_source,
        "cdn_config_hash": cdn_hash,
        "cdn_config_sha256": _sha256_file(cdn_path),
        "cdn_config_source": cdn_source,
        "install_manifest": install_evidence,
        "install_selection": install_selection,
        "download_manifest": {**download_evidence, **download_plan},
    }
    if host_architecture_plan is not None:
        build_evidence["host_architecture_plan"] = host_architecture_plan
    return build_evidence, rows, db, host_architecture_plan


def _round_trip_metrics(local_ekeys: set[str], rows: Iterator[tuple[str, int]]) -> OverlapResult:
    return calculate_overlap(rows, local_ekeys)


def _compare_download_plans(
    target_db: sqlite3.Connection,
    source_db_path: Path,
    target_plan: dict[str, object],
) -> OverlapResult:
    """Compare a target plan to a complete older ARM64 plan without key lists."""
    target_db.execute("ATTACH DATABASE ? AS source_plan", (str(source_db_path),))
    try:
        size_conflicts = target_db.execute(
            "SELECT COUNT(*) FROM ("
            "SELECT t.ekey, t.encoded_size AS target_size, s.encoded_size AS source_size "
            "FROM (SELECT ekey, MIN(encoded_size) AS encoded_size FROM download_entries "
            "WHERE selected=1 GROUP BY ekey) AS t "
            "JOIN (SELECT ekey, MIN(encoded_size) AS encoded_size FROM source_plan.download_entries "
            "WHERE selected=1 GROUP BY ekey) AS s ON s.ekey=t.ekey "
            "WHERE t.encoded_size != s.encoded_size)"
        ).fetchone()[0]
        if size_conflicts:
            raise MeasurementError(
                f"{size_conflicts} shared ARM64 EKeys have different encoded sizes across builds"
            )
        reused_count, reused_bytes = target_db.execute(
            "SELECT COUNT(*), COALESCE(SUM(t.encoded_size),0) FROM ("
            "SELECT ekey, MIN(encoded_size) AS encoded_size FROM download_entries "
            "WHERE selected=1 GROUP BY ekey) AS t "
            "JOIN (SELECT ekey FROM source_plan.download_entries WHERE selected=1 GROUP BY ekey) "
            "AS s ON s.ekey=t.ekey"
        ).fetchone()
    finally:
        target_db.execute("DETACH DATABASE source_plan")
    target_count = int(target_plan["selected_unique_object_count"])
    target_bytes = int(target_plan["selected_unique_encoded_bytes"])
    return OverlapResult(
        target_object_count=target_count,
        target_encoded_bytes=target_bytes,
        reused_object_count=int(reused_count),
        reused_encoded_bytes=int(reused_bytes),
        remaining_object_count=target_count - int(reused_count),
        remaining_encoded_bytes=target_bytes - int(reused_bytes),
    )


def _human_bytes(value: int) -> str:
    return f"{value:,} bytes ({value / 1_000_000_000:.3f} GB; {value / (1 << 30):.3f} GiB)"


def _write_reports(output_dir: Path, report: dict[str, object]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "overlap-report.json"
    md_path = output_dir / "overlap-summary.md"
    json_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    overlap_a = report["overlap_70009_arm64"]
    overlap_b = report["overlap_70450_arm64"]
    local = report["local_set"]
    markdown = [
        "# Pasted ARM64 client EKey overlap",
        "",
        f"Measurement time: `{report['measured_at_utc']}`.",
        "",
        "## Measured results",
        "",
        "| Comparison | Reusable objects | Reusable encoded bytes | Target total objects / bytes | Remaining objects / bytes |",
        "|---|---:|---:|---:|---:|",
        f"| ARM64 70009 target vs local 70009 x86-64 | {overlap_a['reused_object_count']:,} | {_human_bytes(overlap_a['reused_encoded_bytes'])} | {overlap_a['target_object_count']:,} / {_human_bytes(overlap_a['target_encoded_bytes'])} | {overlap_a['remaining_object_count']:,} / {_human_bytes(overlap_a['remaining_encoded_bytes'])} |",
        f"| ARM64 70450 target vs local 70009 x86-64 | {overlap_b['reused_object_count']:,} | {_human_bytes(overlap_b['reused_encoded_bytes'])} | {overlap_b['target_object_count']:,} / {_human_bytes(overlap_b['target_encoded_bytes'])} | {overlap_b['remaining_object_count']:,} / {_human_bytes(overlap_b['remaining_encoded_bytes'])} |",
        f"| ARM64 70450 target vs complete ARM64 70009 plan | {report['overlap_70450_vs_full_70009_arm64_plan']['reused_object_count']:,} | {_human_bytes(report['overlap_70450_vs_full_70009_arm64_plan']['reused_encoded_bytes'])} | {report['overlap_70450_vs_full_70009_arm64_plan']['target_object_count']:,} / {_human_bytes(report['overlap_70450_vs_full_70009_arm64_plan']['target_encoded_bytes'])} | {report['overlap_70450_vs_full_70009_arm64_plan']['remaining_object_count']:,} / {_human_bytes(report['overlap_70450_vs_full_70009_arm64_plan']['remaining_encoded_bytes'])} |",
        "",
        "## Local-set limits",
        "",
        f"- InstallState records {local['install_state_full_ekey_count']:,} full EKeys; {local['index_confirmed_full_ekey_count']:,} are corroborated by a unique 9-byte prefix in the local `.idx` files and were counted as locally reusable.",
        f"- {local['state_ekeys_without_index_prefix_count']:,} state EKeys have no matching local index prefix; {local['ambiguous_prefix_ekey_count']:,} have ambiguous truncated prefixes. Neither group was counted.",
        f"- The selected 70009 x86_64/enUS plan has {report['local_x86_70009_plan']['download_manifest']['selected_unique_object_count']:,} unique objects; only {report['local_x86_70009_plan']['install_state_intersection']['reused_object_count']:,} InstallState EKeys are in that plan. The other {report['local_x86_70009_plan']['install_state_objects_not_in_selected_x86_plan']:,} state EKeys are not counted as x86-plan completion.",
        "- A prefix match plus InstallState is inventory evidence, not a fresh read/hash of the corresponding archive bytes. The updater must still validate EKeys before reuse.",
        "",
        "## Method and provenance",
        "",
        f"- 70009 ARM64 set: pinned build/CDN config and `Windows,arm64,enUS` selection of its official download manifest. Build config: `{report['builds']['70009']['build_config_hash']}`.",
        f"- 70450 ARM64 set: official US versions feed (sequence `{report['published_target']['sequence']}`) → hash-bound build/CDN configs → selected download manifest. Build config: `{report['builds']['70450']['build_config_hash']}`.",
        "- Local set: full EKeys from `client/Data/.install_state.json`, constrained by the 9-byte prefixes parsed from `client/Data/data/*.idx`; no archive files were read.",
        "- Encoded object bytes come from each selected download manifest's encoded-size field, deduplicated by full EKey. Intersection byte counts are weighted by the relevant target build's encoded size.",
        "- The third comparison is the theoretical plan-to-plan overlap if a complete ARM64 70009 manifest selection is pasted; it is separate from measured EKeys currently in the partial x86 host tree.",
        "- Catalog metadata and manifest objects were checked against their config references (config MD5, metadata EKey and decoded CKey). No game payloads were downloaded.",
        "",
        "## Not measured / residual limits",
        "",
        "- Payload bytes were not reread from the local archives, so physical integrity/reusability remains subject to the launcher's per-EKey verification.",
        "- Selected download-manifest encoded sizes were not independently cross-checked against the separate encoding manifest. The download manifest itself was hash-bound through the build config and selected using the project tag-filter implementation.",
        "- Loose install files, indices, metadata bootstrap, and files outside the selected CASC download-object set are not included in these overlap/delta figures.",
        "",
        f"Full provenance and source file mtimes/hashes: [`overlap-report.json`](./{json_path.name}).",
        "",
    ]
    md_path.write_text("\n".join(markdown), encoding="utf-8")


def run_measurement(
    *,
    client_root: Path = DEFAULT_CLIENT_ROOT,
    output_dir: Path = DEFAULT_OUTPUT_DIR,
    cache_root: Path = DEFAULT_CACHE_ROOT,
    expected_target_build_id: int = 70450,
    timeout: float = 20.0,
) -> dict[str, object]:
    if not 1 <= expected_target_build_id <= 2**31 - 1:
        raise MeasurementError("target build id must be a positive integer")
    if timeout <= 0 or timeout > 120:
        raise MeasurementError("timeout must be positive and at most 120 seconds")

    local_ekeys, local_state_ekeys, local_evidence, local_counts = _read_local_evidence(
        client_root
    )
    cache = _read_cache(cache_root)

    output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".overlap-work-", dir=output_dir) as temp_name:
        scratch = Path(temp_name)
        versions_path = scratch / "versions.tsv"
        versions_source = _fetch_https(
            VERSION_URL,
            versions_path,
            body_limit=64 * 1024,
            expected_size=None,
            timeout=timeout,
        )
        versions_raw = versions_path.read_bytes()
        published = _versions_row(versions_raw, expected_target_build_id)
        published["source"] = versions_source
        published["source"]["sha256"] = published["raw_sha256"]

        source_report, source_rows, source_db, local_x86_plan = _measure_build(
            build_id=SOURCE_BUILD_ID,
            build_hash=SOURCE_BUILD_CONFIG,
            cdn_hash=SOURCE_CDN_CONFIG,
            expected_version=SOURCE_VERSION,
            cache=cache,
            scratch=scratch,
            timeout=timeout,
            manifest_db_path=scratch / "download-70009.sqlite3",
            local_state_ekeys=local_state_ekeys,
            index_confirmed_ekeys=local_ekeys,
        )
        overlap_a = _round_trip_metrics(local_ekeys, source_rows)

        target_report, target_rows, target_db, _ = _measure_build(
            build_id=expected_target_build_id,
            build_hash=str(published["build_config_hash"]),
            cdn_hash=str(published["cdn_config_hash"]),
            expected_version=str(published["version"]),
            cache=cache,
            scratch=scratch,
            timeout=timeout,
            manifest_db_path=scratch / f"download-{expected_target_build_id}.sqlite3",
            local_state_ekeys=local_state_ekeys,
            index_confirmed_ekeys=local_ekeys,
        )
        overlap_b = _round_trip_metrics(local_ekeys, target_rows)
        full_arm64_paste_overlap = _compare_download_plans(
            target_db,
            scratch / "download-70009.sqlite3",
            target_report["download_manifest"],
        )
        target_db.close()
        source_db.close()

    if overlap_b.target_encoded_bytes < overlap_b.reused_encoded_bytes:
        raise MeasurementError("reused byte sum exceeds target total")
    measured_at = datetime.now(timezone.utc).isoformat()
    report: dict[str, object] = {
        "schema": "wowforever.pasted-client-overlap-measurement",
        "schema_version": 1,
        "status": "measured",
        "measured_at_utc": measured_at,
        "measurement_date": measured_at[:10],
        "builds": {
            str(SOURCE_BUILD_ID): source_report,
            str(expected_target_build_id): target_report,
        },
        "published_target": published,
        "local_set": local_counts,
        "local_x86_70009_plan": local_x86_plan,
        "local_evidence": local_evidence,
        "overlap_70009_arm64": asdict(overlap_a),
        "overlap_70450_arm64": asdict(overlap_b),
        "overlap_70450_vs_full_70009_arm64_plan": asdict(full_arm64_paste_overlap),
        "no_medido": [
            "Los bytes de los objetos de los archives locales no se releen ni se recalculan por hash; el conjunto local se funda en InstallState y su corroboración por prefijo .idx.",
            "Los tamaños codificados del download manifest no se cruzaron independientemente con el encoding manifest.",
            "No se incluyen loose files, índices CDN, metadata bootstrap ni archivos fuera del conjunto seleccionado del download manifest.",
        ],
        "verification_scope": {
            "client_tree_modified": False,
            "client_archive_files_read": False,
            "device_accessed": False,
            "payloads_downloaded": False,
            "manifest_metadata_downloaded": True,
            "selection_query": SELECTION_QUERY,
            "local_full_ekey_source": "client/Data/.install_state.json downloaded list",
            "local_index_confirmation": "cascette-tools parse_local_idx_file; unique 9-byte key prefix",
            "target_size_source": "selected, unique full-EKey entries of the hash-bound download manifest",
        },
    }
    _write_reports(output_dir, report)
    print(json.dumps({
        "status": "measured",
        "report": str(output_dir / "overlap-report.json"),
        "summary": str(output_dir / "overlap-summary.md"),
        "local_full_ekeys": local_counts["install_state_full_ekey_count"],
        "index_confirmed_local_ekeys": local_counts["index_confirmed_full_ekey_count"],
        "overlap_70009_arm64": asdict(overlap_a),
        "overlap_70450_arm64": asdict(overlap_b),
        "overlap_70450_vs_full_70009_arm64_plan": asdict(full_arm64_paste_overlap),
    }, sort_keys=True))
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-root", type=Path, default=DEFAULT_CLIENT_ROOT)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--cache-root", type=Path, default=DEFAULT_CACHE_ROOT)
    parser.add_argument("--target-build-id", type=int, default=70450)
    parser.add_argument("--timeout", type=float, default=20.0)
    args = parser.parse_args(argv)
    try:
        run_measurement(
            client_root=args.client_root,
            output_dir=args.output_dir,
            cache_root=args.cache_root,
            expected_target_build_id=args.target_build_id,
            timeout=args.timeout,
        )
    except (MeasurementError, OSError, sqlite3.Error, ValueError) as exc:
        print(json.dumps({"status": "blocked", "reason": str(exc)}, sort_keys=True))
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
