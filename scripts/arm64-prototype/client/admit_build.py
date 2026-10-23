#!/usr/bin/env python3
"""Admit the current, official WoW Classic Beta 70425 install metadata.

This is a bounded host-side metadata probe, not an installer. It verifies the
official versions response, hash-binds the build/CDN configs and install
manifest, and emits the exact Windows/arm64/enUS manifest selection outside
the repository. It never fetches game payloads or inspects/executes a PE.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import importlib.util
import json
import math
import os
import re
import shutil
import sqlite3
import struct
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Callable
from urllib.parse import urlsplit

from cascette_tools.core.cache import DiskCache
from cascette_tools.core.utils import PathNormalizer
from cascette_tools.formats.cdn_archive import CdnArchiveParser
from cascette_tools.formats.config import BuildConfigParser, CDNConfigParser
from cascette_tools.formats.install import InstallParser
from forever_client.casc import filter_install_entries


PRODUCT = "wow_classic_beta"
REGION = "us"
EXPECTED_BUILD_ID = 70425
EXPECTED_VERSION = "1.60.1.70425"
PLATFORM = "Windows"
ARCHITECTURE = "arm64"
LOCALE = "enUS"
SELECTION_QUERY = "Windows,arm64,enUS"
EXPECTED_EXECUTABLE = "WowB-ARM64.exe"
CDN_PATH = "tpr/wow"
VERSION_URL = "https://us.version.battle.net/v2/products/wow_classic_beta/versions"
VERSION_HOST = "us.version.battle.net"
CDN_HOST = "us.cdn.blizzard.com"
ALLOWED_CDN_HOSTS = {"us.cdn.blizzard.com", "level3.blizzard.com"}

DEFAULT_OUTPUT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/client-build70425-admission"
)
DEFAULT_CACHE_ROOT = Path.home() / ".cache" / "cascette"

MAX_VERSION_BYTES = 64 * 1024
MAX_CONFIG_BYTES = 4 * 1024 * 1024
MAX_METADATA_OBJECT_BYTES = 256 * 1024 * 1024
MAX_TOTAL_RESPONSE_BYTES = 1 << 30
MAX_TOTAL_ARTIFACT_BYTES = 512 * 1024 * 1024
MAX_TOTAL_CLOSURE_ARTIFACT_BYTES = 1536 * 1024 * 1024
MAX_DECODED_CLOSURE_BYTES = 768 * 1024 * 1024
MAX_CONSUMER_CATALOG_BYTES = 512 * 1024 * 1024
MAX_PERSISTED_CDN_INDEX_BYTES = 64 * 1024 * 1024
MAX_CDN_INDEX_BYTES = 16 * 1024 * 1024
MAX_CONSUMER_CATALOG_OBJECTS = 2_000_000
MAX_INSTALL_ENTRIES = 100_000
MAX_INSTALL_TAGS = 4096
MAX_STREAM_TAGS = 4096
MAX_STREAM_TAG_BITMAP_BYTES = 128 * 1024 * 1024
MAX_ENCODING_ESPEC_BYTES = 1024 * 1024
MAX_CLOSURE_REPORT_BYTES = 64 * 1024 * 1024
IO_BYTES = 64 * 1024
MD5_RE = re.compile(r"[0-9a-fA-F]{32}\Z")


class AdmissionError(ValueError):
    """Official metadata is missing, inconsistent, unsupported, or over budget."""


@dataclass(frozen=True)
class OfficialBuild:
    region: str
    build_config_hash: str
    cdn_config_hash: str
    build_id: int
    version: str
    product_config_hash: str | None
    feed_sha256: str
    feed_bytes: int
    sequence: str | None


@dataclass
class NetworkBudget:
    response_bytes: int = 0
    limit_bytes: int = MAX_TOTAL_RESPONSE_BYTES
    fetches: list[dict[str, Any]] | None = None

    def __post_init__(self) -> None:
        if self.fetches is None:
            self.fetches = []

    def reserve(self, count: int, url: str) -> None:
        if count < 0 or self.response_bytes + count > self.limit_bytes:
            raise AdmissionError(
                f"network response budget exceeded at {url}: "
                f"{self.response_bytes}+{count}>{self.limit_bytes}"
            )

    def record(self, url: str, status: int, received: int) -> None:
        self.response_bytes += received
        assert self.fetches is not None
        self.fetches.append(
            {"url": url, "http_status": status, "response_body_bytes": received}
        )


def _load_update_manifest_engine() -> Any:
    """Reuse the pinned updater's streaming parsers and TACT-aware BLTE decoder."""
    path = Path(__file__).with_name("update_manifest.py")
    name = "_arm64_admission_update_manifest"
    module = sys.modules.get(name)
    if module is None:
        spec = importlib.util.spec_from_file_location(name, path)
        if spec is None or spec.loader is None:
            raise AdmissionError(f"cannot load bounded BLTE decoder: {path}")
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        spec.loader.exec_module(module)
    return module


_UPDATE_MANIFEST = _load_update_manifest_engine()
stream_decode_blte = _UPDATE_MANIFEST.stream_decode_blte


def _valid_hash(value: str, label: str) -> str:
    if not isinstance(value, str) or MD5_RE.fullmatch(value) is None:
        raise AdmissionError(f"invalid {label} MD5: {value!r}")
    return value.lower()


def parse_official_versions(raw: bytes) -> OfficialBuild:
    """Parse the official product versions table and require its us 70425 row."""
    if not raw or len(raw) > MAX_VERSION_BYTES:
        raise AdmissionError(
            f"versions response size outside bound: {len(raw)} bytes "
            f"(max {MAX_VERSION_BYTES})"
        )
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise AdmissionError(f"versions response is not UTF-8: {exc}") from exc

    sequence: str | None = None
    header: list[str] | None = None
    row: dict[str, str] | None = None
    for line in text.splitlines():
        line = line.strip("\r")
        if line.startswith("## seqn = "):
            sequence = line.partition("=")[2].strip()
            continue
        if "|" not in line:
            continue
        fields = line.split("|")
        if header is None and any(field.startswith("Region!") for field in fields):
            header = [field.partition("!")[0] for field in fields]
            required = {
                "Region",
                "BuildConfig",
                "CDNConfig",
                "BuildId",
                "VersionsName",
                "ProductConfig",
            }
            if not required.issubset(header):
                raise AdmissionError(f"versions table lacks columns: {sorted(required - set(header))}")
            continue
        if header is None:
            continue
        if len(fields) != len(header):
            raise AdmissionError(
                f"versions table row has {len(fields)} fields, expected {len(header)}"
            )
        values = dict(zip(header, fields, strict=True))
        if values.get("Region") == REGION:
            if row is not None:
                raise AdmissionError("official versions response has multiple us rows")
            row = values

    if header is None or row is None:
        raise AdmissionError("official versions response has no parseable us build row")
    try:
        build_id = int(row["BuildId"])
    except (TypeError, ValueError) as exc:
        raise AdmissionError(f"invalid official BuildId: {row.get('BuildId')!r}") from exc
    version = row["VersionsName"].strip()
    build_config = _valid_hash(row["BuildConfig"], "build config")
    cdn_config = _valid_hash(row["CDNConfig"], "CDN config")
    product_config = row.get("ProductConfig", "").strip()
    if product_config:
        product_config = _valid_hash(product_config, "product config")
    if build_id != EXPECTED_BUILD_ID or version != EXPECTED_VERSION:
        raise AdmissionError(
            f"official us build is {build_id}/{version}, expected "
            f"{EXPECTED_BUILD_ID}/{EXPECTED_VERSION}"
        )
    return OfficialBuild(
        region=REGION,
        build_config_hash=build_config,
        cdn_config_hash=cdn_config,
        build_id=build_id,
        version=version,
        product_config_hash=product_config or None,
        feed_sha256=hashlib.sha256(raw).hexdigest(),
        feed_bytes=len(raw),
        sequence=sequence,
    )


def _parse_hash_bound_configs(
    official: OfficialBuild, build_raw: bytes, cdn_raw: bytes
) -> tuple[Any, Any, Any]:
    """Verify raw config MD5s before parsing their product/reference metadata."""
    actual_build_hash = hashlib.md5(build_raw).hexdigest()
    if actual_build_hash != official.build_config_hash:
        raise AdmissionError(
            f"build config MD5 mismatch: got={actual_build_hash} "
            f"expected={official.build_config_hash}"
        )
    actual_cdn_hash = hashlib.md5(cdn_raw).hexdigest()
    if actual_cdn_hash != official.cdn_config_hash:
        raise AdmissionError(
            f"CDN config MD5 mismatch: got={actual_cdn_hash} "
            f"expected={official.cdn_config_hash}"
        )
    try:
        build = BuildConfigParser().parse(build_raw)
        cdn = CDNConfigParser().parse(cdn_raw)
    except Exception as exc:
        raise AdmissionError(f"could not parse hash-bound build/CDN config: {exc}") from exc
    if build.build_uid != PRODUCT:
        raise AdmissionError(f"build config product mismatch: {build.build_uid!r}")
    if build.build_num and str(build.build_num) != str(official.build_id):
        raise AdmissionError(
            f"build config build-num mismatch: {build.build_num!r} != {official.build_id}"
        )
    if cdn.builds:
        named_builds = [_valid_hash(value, "CDN config builds reference") for value in cdn.builds]
        if official.build_config_hash not in named_builds:
            raise AdmissionError(
                "CDN config builds list does not include the official build-config hash"
            )
    install = build.get_install_info()
    if (
        install is None
        or not install.content_key
        or not install.encoding_key
        or not isinstance(install.size, int)
        or install.size <= 0
    ):
        raise AdmissionError("build config has no complete install CKey/EKey/encoded-size reference")
    _valid_hash(install.content_key, "install content key")
    _valid_hash(install.encoding_key, "install encoding key")
    if install.size > MAX_METADATA_OBJECT_BYTES:
        raise AdmissionError(
            f"install metadata encoded size exceeds {MAX_METADATA_OBJECT_BYTES} byte limit: "
            f"{install.size}"
        )
    for digest in [*cdn.archives, *cdn.patch_archives]:
        _valid_hash(digest, "CDN archive reference")
    for digest in (cdn.file_index, cdn.patch_file_index):
        if digest:
            _valid_hash(digest, "CDN file-index reference")
    return build, cdn, install


def _closure_metadata_references(build: Any) -> dict[str, Any]:
    """Return only the three additional bounded closure metadata references."""
    references: dict[str, Any] = {}
    for role in ("encoding", "download", "size"):
        info = getattr(build, f"get_{role}_info")()
        if (
            info is None
            or not info.content_key
            or not info.encoding_key
            or not isinstance(info.size, int)
            or info.size <= 0
        ):
            raise AdmissionError(f"build config lacks complete {role} metadata reference")
        _valid_hash(info.content_key, f"{role} content key")
        _valid_hash(info.encoding_key, f"{role} encoding key")
        if info.size > MAX_METADATA_OBJECT_BYTES:
            raise AdmissionError(
                f"{role} metadata encoded size exceeds {MAX_METADATA_OBJECT_BYTES} byte limit: "
                f"{info.size}"
            )
        references[role] = info
    return references


def _all_closure_metadata_references(install_info: Any, additional: dict[str, Any]) -> dict[str, Any]:
    if set(additional) != {"encoding", "download", "size"}:
        raise AdmissionError(f"unexpected closure metadata roles: {sorted(additional)}")
    return {"install": install_info, **additional}


def _parse_closure_manifests(
    download_path: Path,
    size_path: Path,
    encoding_path: Path,
    db: sqlite3.Connection,
) -> dict[str, Any]:
    """Run the exact bounded preflight plus existing streaming SQLite parsers."""
    stream_bounds = _preflight_stream_manifests(download_path, size_path, encoding_path)
    try:
        with download_path.open("rb") as stream:
            download_stats, download_tags, download_batch = _UPDATE_MANIFEST.parse_download_manifest(
                stream, db, query=_UPDATE_MANIFEST.TAG_QUERY
            )
        with size_path.open("rb") as stream:
            size_stats, size_tags, size_batch = _UPDATE_MANIFEST.parse_size_manifest(
                stream, db, query=_UPDATE_MANIFEST.TAG_QUERY
            )
        with encoding_path.open("rb") as stream:
            encoding_stats = _UPDATE_MANIFEST.parse_encoding_pages(
                stream, encoding_path.stat().st_size, db
            )
    except AdmissionError:
        raise
    except Exception as exc:
        gate_error = getattr(_UPDATE_MANIFEST, "GateError", ValueError)
        if isinstance(exc, gate_error):
            raise AdmissionError(f"bounded 70425 manifest parsing failed: {exc}") from exc
        raise
    if (
        download_stats.entry_count != stream_bounds["download_entry_count"]
        or download_stats.tag_count != stream_bounds["download_tag_count"]
        or size_stats.entry_count != stream_bounds["size_entry_count"]
        or size_stats.tag_count != stream_bounds["size_tag_count"]
    ):
        raise AdmissionError("stream parser counts disagree with bounded manifest preflight")
    return {
        "preflight": stream_bounds,
        "download_stats": download_stats,
        "download_tags": download_tags,
        "download_batch": download_batch,
        "size_stats": size_stats,
        "size_tags": size_tags,
        "size_batch": size_batch,
        "encoding_stats": encoding_stats,
    }


def _readonly_cache(cache_root: Path) -> DiskCache:
    """Create only the path resolver; do not run DiskCache's writing constructor."""
    cache = object.__new__(DiskCache)
    cache.base_dir = cache_root
    cache.cdn_dir = cache_root / "cdn"
    cache.api_dir = cache_root / "api"
    cache.metadata_file = cache_root / "metadata.json"
    cache.metadata = {}
    return cache


def _config_url(digest: str) -> str:
    digest = _valid_hash(digest, "config URL")
    return f"https://{CDN_HOST}/{CDN_PATH}/config/{digest[:2]}/{digest[2:4]}/{digest}"


def _data_url(digest: str) -> str:
    digest = _valid_hash(digest, "data URL EKey")
    return f"https://{CDN_HOST}/{CDN_PATH}/data/{digest[:2]}/{digest[2:4]}/{digest}"


def _index_url(digest: str) -> str:
    digest = _valid_hash(digest, "CDN index URL hash")
    return f"https://{CDN_HOST}/{CDN_PATH}/data/{digest[:2]}/{digest[2:4]}/{digest}.index"


def _validate_https_url(url: str, *, allowed_hosts: set[str], expected_path: str) -> str:
    parsed = urlsplit(url)
    if (
        parsed.scheme != "https"
        or parsed.hostname is None
        or parsed.hostname.lower() not in allowed_hosts
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port is not None
        or parsed.query
        or parsed.fragment
        or parsed.path != expected_path
    ):
        raise AdmissionError(f"unsafe/non-official HTTPS metadata URL: {url}")
    return parsed.hostname.lower()


def _https_get_to_file(
    url: str,
    output: Path,
    *,
    body_limit: int,
    expected_size: int | None,
    timeout: float,
    budget: NetworkBudget,
    response_callback: Callable[[str, int, int], None] | None = None,
) -> tuple[int, int]:
    parsed = urlsplit(url)
    path = parsed.path
    if url == VERSION_URL:
        host = _validate_https_url(
            url, allowed_hosts={VERSION_HOST}, expected_path="/v2/products/wow_classic_beta/versions"
        )
    else:
        expected_path = path
        host = _validate_https_url(url, allowed_hosts=ALLOWED_CDN_HOSTS, expected_path=expected_path)
        if not path.startswith(f"/{CDN_PATH}/"):
            raise AdmissionError(f"CDN URL is outside {CDN_PATH}: {url}")
    connection = http.client.HTTPSConnection(host, timeout=timeout)
    response: Any = None
    received = 0
    try:
        connection.request(
            "GET", path, headers={"Accept-Encoding": "identity", "User-Agent": "wowforever-metadata-admission/1"}
        )
        response = connection.getresponse()
        if response.status != 200:
            raise AdmissionError(
                f"metadata request returned HTTP {response.status}; redirects are not followed: {url}"
            )
        content_encoding = response.getheader("Content-Encoding")
        if content_encoding and content_encoding.lower() != "identity":
            raise AdmissionError(f"compressed HTTP response is not accepted: {content_encoding!r}")
        transfer_encoding = response.getheader("Transfer-Encoding")
        if transfer_encoding and transfer_encoding.lower() != "identity":
            raise AdmissionError(f"transfer-encoded metadata response is not accepted: {transfer_encoding!r}")
        content_length = response.getheader("Content-Length")
        if content_length is None or not content_length.isdigit():
            raise AdmissionError(f"metadata response lacks a valid Content-Length: {url}")
        declared = int(content_length)
        if declared <= 0 or declared > body_limit:
            raise AdmissionError(
                f"metadata Content-Length outside object bound: {declared} (limit {body_limit})"
            )
        if expected_size is not None and declared != expected_size:
            raise AdmissionError(
                f"metadata Content-Length mismatch: {declared} != build-config size {expected_size}"
            )
        budget.reserve(declared, url)
        with output.open("xb") as destination:
            while received < declared:
                block = response.read(min(IO_BYTES, declared - received))
                if not block:
                    raise AdmissionError(
                        f"truncated metadata response: received {received} of {declared} bytes"
                    )
                destination.write(block)
                received += len(block)
            if response.read(1):
                raise AdmissionError("metadata response body exceeds its declared Content-Length")
        if received != declared:
            raise AdmissionError(f"metadata response length mismatch: {received} != {declared}")
        budget.record(url, response.status, received)
        if response_callback is not None:
            response_callback(url, response.status, received)
        return response.status, received
    finally:
        if response is not None:
            response.close()
        connection.close()


def _copy_cached_object(
    source: Path,
    destination: Path,
    *,
    max_size: int,
    expected_size: int | None = None,
) -> int:
    if source.is_symlink() or not source.is_file():
        raise AdmissionError(f"cached metadata path is not a regular file: {source}")
    size = source.stat().st_size
    if size <= 0 or size > max_size:
        raise AdmissionError(f"cached metadata size outside bound: {size} bytes at {source}")
    if expected_size is not None and size != expected_size:
        raise AdmissionError(f"cached metadata size mismatch: {size} != {expected_size}: {source}")
    with source.open("rb") as input_stream, destination.open("xb") as output_stream:
        shutil.copyfileobj(input_stream, output_stream, length=IO_BYTES)
    return size


def _hash_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(IO_BYTES), b""):
            digest.update(block)
    return digest.hexdigest()


def _artifact(
    role: str,
    path: Path,
    *,
    source_kind: str,
    source: str,
    url: str,
) -> dict[str, Any]:
    return {
        "role": role,
        "path": str(path.relative_to(path.parents[1])),
        "source_kind": source_kind,
        "source": source,
        "url": url,
        "size_bytes": path.stat().st_size,
        "md5": _hash_file(path, "md5"),
        "sha256": _hash_file(path, "sha256"),
    }


def _acquire_metadata(
    *,
    cache: DiskCache,
    output_path: Path,
    digest: str,
    kind: str,
    url: str,
    role: str,
    body_limit: int,
    expected_size: int | None,
    timeout: float,
    budget: NetworkBudget,
) -> dict[str, Any]:
    cache_path = cache._get_cdn_cache_path(digest, kind, CDN_PATH)
    if cache_path.exists():
        copied = _copy_cached_object(
            cache_path, output_path, max_size=body_limit, expected_size=expected_size
        )
        source_kind = "read-only-cascette-cache"
        source = str(cache_path)
    else:
        status, copied = _https_get_to_file(
            url,
            output_path,
            body_limit=body_limit,
            expected_size=expected_size,
            timeout=timeout,
            budget=budget,
        )
        source_kind = "official-https"
        source = f"HTTP {status}; {copied} response bytes"
    if expected_size is not None and copied != expected_size:
        raise AdmissionError(f"{role} size mismatch: {copied} != {expected_size}")
    if output_path.stat().st_size > body_limit:
        raise AdmissionError(f"{role} artifact exceeds {body_limit} byte bound")
    return _artifact(role, output_path, source_kind=source_kind, source=source, url=url)


def verify_encoded_object_ekey(path: Path, expected_ekey: str, expected_size: int) -> dict[str, Any]:
    """Verify the wire object identity before it enters the persistent raw cache."""
    expected_ekey = _valid_hash(expected_ekey, "metadata EKey")
    actual_size = path.stat().st_size
    if actual_size != expected_size:
        raise AdmissionError(f"raw metadata length mismatch: {actual_size}!={expected_size}")
    with path.open("rb") as source:
        prefix = source.read(8)
        if prefix[:4] == b"BLTE":
            if len(prefix) != 8:
                raise AdmissionError("truncated BLTE header before EKey verification")
            header_size = struct.unpack(">I", prefix[4:8])[0]
            if header_size:
                if (
                    header_size < 12
                    or header_size > _UPDATE_MANIFEST.MAX_BLTE_HEADER_BYTES
                    or header_size > actual_size
                ):
                    raise AdmissionError(f"invalid BLTE header size before EKey verification: {header_size}")
                source.seek(0)
                remaining = header_size
                digest = hashlib.md5()
                while remaining:
                    chunk = source.read(min(IO_BYTES, remaining))
                    if not chunk:
                        raise AdmissionError("truncated chunked BLTE header during EKey verification")
                    digest.update(chunk)
                    remaining -= len(chunk)
                rule = "md5-blte-header"
            else:
                source.seek(0)
                digest = hashlib.md5()
                for chunk in iter(lambda: source.read(IO_BYTES), b""):
                    digest.update(chunk)
                rule = "md5-whole-single-chunk-object"
        else:
            source.seek(0)
            digest = hashlib.md5()
            for chunk in iter(lambda: source.read(IO_BYTES), b""):
                digest.update(chunk)
            rule = "md5-whole-nonblte-object"
    actual_ekey = digest.hexdigest()
    if actual_ekey != expected_ekey:
        raise AdmissionError(f"raw metadata EKey mismatch: {actual_ekey}!={expected_ekey}")
    return {
        "encoding_key": actual_ekey,
        "encoded_size": actual_size,
        "ekey_rule": rule,
        "raw_sha256": _hash_file(path, "sha256"),
    }


def _verified_raw_path(raw_cache_dir: Path, role: str, ekey: str) -> Path:
    return raw_cache_dir / f"{role}-{_valid_hash(ekey, role + ' EKey')}.blte"


def _verified_raw_is_available(
    cache: DiskCache, raw_cache_dir: Path, role: str, info: Any
) -> bool:
    persistent = _verified_raw_path(raw_cache_dir, role, info.encoding_key)
    if persistent.is_symlink():
        raise AdmissionError(f"verified raw cache object is a symlink: {persistent}")
    if persistent.exists():
        verify_encoded_object_ekey(persistent, info.encoding_key, info.size)
        return True
    cached = cache._get_cdn_cache_path(info.encoding_key, "data", CDN_PATH)
    if cached.is_symlink():
        raise AdmissionError(f"read-only cascette object is a symlink: {cached}")
    if cached.exists():
        verify_encoded_object_ekey(cached, info.encoding_key, info.size)
        return True
    return False


def _directory_file_bytes(path: Path) -> int:
    if not path.exists():
        return 0
    if path.is_symlink() or not path.is_dir():
        raise AdmissionError(f"expected a regular directory: {path}")
    total = 0
    for item in path.iterdir():
        if item.is_symlink():
            raise AdmissionError(f"raw cache contains a symlink: {item}")
        if item.is_file():
            total += item.stat().st_size
        elif item.is_dir():
            total += _directory_file_bytes(item)
    return total


def _read_transfer_ledger(raw_cache_dir: Path) -> list[dict[str, Any]]:
    ledger_path = raw_cache_dir / "transfers.jsonl"
    if ledger_path.is_symlink():
        raise AdmissionError(f"raw-cache transfer ledger is a symlink: {ledger_path}")
    if not ledger_path.exists():
        return []
    events: list[dict[str, Any]] = []
    try:
        with ledger_path.open("r", encoding="utf-8") as stream:
            for line_number, line in enumerate(stream, 1):
                event = json.loads(line)
                if (
                    event.get("record_type") != "metadata-transfer-response"
                    or type(event.get("sequence")) is not int
                    or event.get("sequence") != line_number
                    or type(event.get("response_body_bytes")) is not int
                    or event["response_body_bytes"] <= 0
                    or event["response_body_bytes"] != event.get("expected_encoded_size")
                    or event.get("http_status") != 200
                    or event.get("role") not in {"encoding", "download", "size", "cdn-index"}
                ):
                    raise AdmissionError(f"invalid transfer ledger event at line {line_number}")
                role = event["role"]
                if role == "cdn-index":
                    index_hash = _valid_hash(
                        event.get("expected_index_hash"), "transfer ledger index hash"
                    )
                    expected_url = _index_url(index_hash)
                else:
                    ekey = _valid_hash(event.get("expected_ekey"), "transfer ledger EKey")
                    expected_url = _data_url(ekey)
                if event.get("url") != expected_url or re.fullmatch(
                    r"[0-9a-f]{64}", event.get("response_raw_sha256", "")
                ) is None:
                    raise AdmissionError(f"invalid transfer ledger provenance at line {line_number}")
                events.append(event)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AdmissionError(f"raw-cache transfer ledger is unreadable: {exc}") from exc
    return events


def _transfer_ledger_delta(
    events: list[dict[str, Any]], summarized_sequence: int
) -> tuple[list[dict[str, Any]], int]:
    if summarized_sequence < 0 or summarized_sequence > len(events):
        raise AdmissionError("admission summary transfer-ledger sequence is invalid")
    delta = events[summarized_sequence:]
    return delta, sum(event["response_body_bytes"] for event in delta)


def _append_transfer_event(
    raw_cache_dir: Path,
    events: list[dict[str, Any]],
    *,
    role: str,
    ekey: str,
    url: str,
    status: int,
    received: int,
    expected_size: int,
    response_path: Path,
) -> None:
    """Durably account a completed response before any decoder/parser can fail."""
    event = {
        "record_type": "metadata-transfer-response",
        "sequence": len(events) + 1,
        "role": role,
        "expected_ekey": ekey.lower(),
        "url": url,
        "http_status": status,
        "response_body_bytes": received,
        "expected_encoded_size": expected_size,
        "response_raw_sha256": _hash_file(response_path, "sha256"),
        "transport_complete": True,
        "ekey_verification": "performed immediately after the durable transfer record",
    }
    path = raw_cache_dir / "transfers.jsonl"
    line = (json.dumps(event, sort_keys=True, separators=(",", ":")) + "\n").encode()
    fd = os.open(path, os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, "ab", closefd=False) as stream:
            stream.write(line)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(fd)
    events.append(event)


def _append_index_transfer_event(
    raw_cache_dir: Path,
    events: list[dict[str, Any]],
    *,
    index_hash: str,
    url: str,
    status: int,
    received: int,
    response_path: Path,
) -> None:
    """Durably account an official CDN index response before parsing it."""
    index_hash = _valid_hash(index_hash, "CDN index hash")
    event = {
        "record_type": "metadata-transfer-response",
        "sequence": len(events) + 1,
        "role": "cdn-index",
        "expected_index_hash": index_hash,
        "url": url,
        "http_status": status,
        "response_body_bytes": received,
        "expected_encoded_size": received,
        "response_raw_sha256": _hash_file(response_path, "sha256"),
        "transport_complete": True,
        "index_structure_validation": "performed after the durable transfer record",
    }
    path = raw_cache_dir / "transfers.jsonl"
    line = (json.dumps(event, sort_keys=True, separators=(",", ":")) + "\n").encode()
    fd = os.open(path, os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, "ab", closefd=False) as stream:
            stream.write(line)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(fd)
    events.append(event)


def _acquire_verified_closure_object(
    *,
    cache: DiskCache,
    raw_cache_dir: Path,
    scratch_path: Path,
    digest: str,
    url: str,
    role: str,
    expected_size: int,
    timeout: float,
    budget: NetworkBudget,
    on_response: Callable[[str, int, int, Path], None] | None = None,
    fetch: Callable[..., tuple[int, int]] | None = None,
) -> dict[str, Any]:
    """Fetch or reuse one EKey-verified object, publishing it outside temp staging."""
    raw_cache_dir.mkdir(exist_ok=True)
    if raw_cache_dir.is_symlink() or not raw_cache_dir.is_dir():
        raise AdmissionError(f"verified raw cache is not a safe directory: {raw_cache_dir}")
    persistent_path = _verified_raw_path(raw_cache_dir, role, digest)
    if persistent_path.is_symlink():
        raise AdmissionError(f"verified raw cache object is a symlink: {persistent_path}")
    if persistent_path.exists():
        evidence = verify_encoded_object_ekey(persistent_path, digest, expected_size)
        return _artifact(
            f"{role}-manifest-encoded-object",
            persistent_path,
            source_kind="verified-external-raw-cache",
            source="reused and revalidated persistent raw metadata",
            url=url,
        ) | {"ekey_validation": evidence}

    cache_path = cache._get_cdn_cache_path(digest, "data", CDN_PATH)
    if cache_path.is_symlink():
        raise AdmissionError(f"read-only cascette raw metadata path is a symlink: {cache_path}")
    if cache_path.exists():
        copied = _copy_cached_object(
            cache_path,
            scratch_path,
            max_size=MAX_METADATA_OBJECT_BYTES,
            expected_size=expected_size,
        )
        source_kind = "read-only-cascette-cache"
        source = str(cache_path)
    else:
        fetch = fetch or _https_get_to_file
        status, copied = fetch(
            url,
            scratch_path,
            body_limit=MAX_METADATA_OBJECT_BYTES,
            expected_size=expected_size,
            timeout=timeout,
            budget=budget,
            response_callback=(
                (lambda got_url, got_status, got_bytes: on_response(
                    got_url, got_status, got_bytes, scratch_path
                ))
                if on_response is not None
                else None
            ),
        )
        source_kind = "official-https"
        source = f"HTTP {status}; {copied} response bytes"
    if copied != expected_size:
        raise AdmissionError(f"{role} encoded length mismatch: {copied}!={expected_size}")
    evidence = verify_encoded_object_ekey(scratch_path, digest, expected_size)

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=f".{role}-incoming-", dir=raw_cache_dir, delete=False
        ) as destination, scratch_path.open("rb") as source_stream:
            temporary_path = Path(destination.name)
            shutil.copyfileobj(source_stream, destination, length=IO_BYTES)
            destination.flush()
            os.fsync(destination.fileno())
        os.chmod(temporary_path, 0o444)
        staged_evidence = verify_encoded_object_ekey(temporary_path, digest, expected_size)
        if staged_evidence["raw_sha256"] != evidence["raw_sha256"]:
            raise AdmissionError("persistent raw-cache copy changed metadata bytes")
        try:
            os.link(temporary_path, persistent_path)
        except FileExistsError:
            existing = verify_encoded_object_ekey(persistent_path, digest, expected_size)
            if existing["raw_sha256"] != evidence["raw_sha256"]:
                raise AdmissionError("concurrent persistent raw-cache object differs")
        return _artifact(
            f"{role}-manifest-encoded-object",
            persistent_path,
            source_kind=source_kind,
            source=source,
            url=url,
        ) | {"ekey_validation": evidence}
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        scratch_path.unlink(missing_ok=True)


class _LimitedWriter:
    def __init__(
        self,
        output: BinaryIO,
        limit: int,
        *,
        aggregate: list[int] | None = None,
        aggregate_limit: int | None = None,
    ):
        self.output = output
        self.limit = limit
        self.bytes_written = 0
        self.aggregate = aggregate
        self.aggregate_limit = aggregate_limit

    def write(self, data: bytes) -> int:
        if self.bytes_written + len(data) > self.limit:
            raise AdmissionError(
                f"decoded metadata object exceeds {self.limit} byte bound"
            )
        if (
            self.aggregate is not None
            and self.aggregate_limit is not None
            and self.aggregate[0] + len(data) > self.aggregate_limit
        ):
            raise AdmissionError(
                f"aggregate decoded metadata exceeds {self.aggregate_limit} byte bound"
            )
        written = self.output.write(data)
        self.bytes_written += written
        if self.aggregate is not None:
            self.aggregate[0] += written
        return written


class _BoundedTextWriter:
    """Count UTF-8 report bytes while streaming JSONL rows to disk."""

    def __init__(self, output: Any, aggregate: list[int], limit: int):
        self.output = output
        self.aggregate = aggregate
        self.limit = limit

    def write(self, value: str) -> int:
        size = len(value.encode("utf-8"))
        if self.aggregate[0] + size > self.limit:
            raise AdmissionError(f"closure reports exceed {self.limit} byte aggregate bound")
        written = self.output.write(value)
        self.aggregate[0] += size
        return written


def _emit_bounded_download_closure(
    db: sqlite3.Connection,
    tags: list[Any],
    path: Path,
    aggregate: list[int],
) -> int:
    rows = db.execute(
        "SELECT d.entry_index,d.ekey,d.encoded_size,d.priority,d.checksum,d.flags,"
        "c.ckey,c.decoded_size "
        "FROM download_entries AS d LEFT JOIN encoding_ckeys AS c ON c.ekey=d.ekey "
        "WHERE d.selected=1 ORDER BY d.ekey,d.entry_index,c.ckey"
    )
    count = 0
    with path.open("w", encoding="utf-8", buffering=IO_BYTES) as stream:
        output = _BoundedTextWriter(stream, aggregate, MAX_CLOSURE_REPORT_BYTES)
        for row in rows:
            index, ekey, encoded_size, priority, checksum, flags, ckey, decoded_size = row
            record = {
                "record_type": "download-object-selection",
                "download_manifest_entry_index": index,
                "encoding_key": bytes(ekey).hex(),
                "encoded_size": encoded_size,
                "content_key": bytes(ckey).hex() if ckey is not None else None,
                "decoded_size": decoded_size,
                "priority": priority,
                "checksum": bytes(checksum).hex() if checksum is not None else None,
                "entry_flags": bytes(flags).hex() if flags is not None else None,
                "manifest_tags": _UPDATE_MANIFEST._tag_names_at(tags, index),
                "matched_query_tags": _UPDATE_MANIFEST._matched_query_tags(
                    tags, index, _UPDATE_MANIFEST.TAG_QUERY
                ),
                "selection_query": _UPDATE_MANIFEST.TAG_QUERY,
            }
            output.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
            count += 1
    return count


def _emit_bounded_size_selection(
    db: sqlite3.Connection,
    tags: list[Any],
    path: Path,
    aggregate: list[int],
) -> int:
    count = 0
    with path.open("w", encoding="utf-8", buffering=IO_BYTES) as stream:
        output = _BoundedTextWriter(stream, aggregate, MAX_CLOSURE_REPORT_BYTES)
        for index, prefix, estimate in db.execute(
            "SELECT entry_index,ekey_prefix,estimated_size FROM size_entries ORDER BY entry_index"
        ):
            record = {
                "record_type": "size-manifest-estimate",
                "size_manifest_entry_index": index,
                "ekey_prefix_9": bytes(prefix).hex(),
                "estimated_size": estimate,
                "manifest_tags": _UPDATE_MANIFEST._tag_names_at(tags, index),
                "matched_query_tags": _UPDATE_MANIFEST._matched_query_tags(
                    tags, index, _UPDATE_MANIFEST.TAG_QUERY
                ),
                "selection_query": _UPDATE_MANIFEST.TAG_QUERY,
                "identity_note": "partial EKey prefix; not used as object identity",
            }
            output.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
            count += 1
    return count


def _preflight_install(path: Path) -> tuple[int, int]:
    size = path.stat().st_size
    if size <= 0 or size > MAX_METADATA_OBJECT_BYTES:
        raise AdmissionError(
            f"decoded install manifest size outside bound: {size} bytes "
            f"(max {MAX_METADATA_OBJECT_BYTES})"
        )
    with path.open("rb") as stream:
        header = stream.read(10)
    if len(header) != 10 or header[:2] != b"IN":
        raise AdmissionError("decoded install manifest has a truncated or invalid IN header")
    version, hash_size = header[2], header[3]
    tag_count = struct.unpack(">H", header[4:6])[0]
    entry_count = struct.unpack(">I", header[6:10])[0]
    if version not in (1, 2) or hash_size != 16:
        raise AdmissionError(
            f"unsupported install header: version={version} hash_size={hash_size}"
        )
    if tag_count > MAX_INSTALL_TAGS:
        raise AdmissionError(f"install tag count exceeds limit: {tag_count}>{MAX_INSTALL_TAGS}")
    if entry_count > MAX_INSTALL_ENTRIES:
        raise AdmissionError(
            f"install entry count exceeds limit: {entry_count}>{MAX_INSTALL_ENTRIES}"
        )
    return tag_count, entry_count


def _preflight_stream_manifests(
    download_path: Path, size_path: Path, encoding_path: Path
) -> dict[str, int]:
    """Cap attacker-controlled tag/ESpec allocations before reusing stream parsers."""
    download_size = download_path.stat().st_size
    with download_path.open("rb") as stream:
        header = stream.read(11)
    if len(header) != 11 or header[:2] != b"DL":
        raise AdmissionError("decoded download manifest has a truncated or invalid DL header")
    if header[2] not in (1, 2, 3) or header[3] != 16 or header[4] not in (0, 1):
        raise AdmissionError("unsupported download manifest header before bounded parse")
    download_entries = struct.unpack(">I", header[5:9])[0]
    download_tags = struct.unpack(">H", header[9:11])[0]
    if header[2] >= 2:
        if download_size < 12:
            raise AdmissionError("truncated download V2 header")
        with download_path.open("rb") as stream:
            stream.seek(11)
            flag_size_raw = stream.read(1)
        if len(flag_size_raw) != 1:
            raise AdmissionError("truncated download V2 flag-size header")
        if flag_size_raw[0] > 64:
            raise AdmissionError(f"download entry flag width exceeds bound: {flag_size_raw[0]}")
    if header[2] >= 3 and download_size < 16:
        raise AdmissionError("truncated download V3 header")
    if download_tags > MAX_STREAM_TAGS:
        raise AdmissionError(f"download manifest tag count exceeds limit: {download_tags}")
    download_bitmap_bytes = download_tags * ((download_entries + 7) // 8)

    with size_path.open("rb") as stream:
        size_header = stream.read(15)
    if len(size_header) != 15 or size_header[:2] != b"DS":
        raise AdmissionError("decoded size manifest has a truncated or invalid DS header")
    if size_header[2] not in (1, 2) or size_header[3] != 9:
        raise AdmissionError("unsupported size manifest header before bounded parse")
    size_entries = struct.unpack(">I", size_header[4:8])[0]
    size_tags = struct.unpack(">H", size_header[8:10])[0]
    if size_tags > MAX_STREAM_TAGS:
        raise AdmissionError(f"size manifest tag count exceeds limit: {size_tags}")
    size_bitmap_bytes = size_tags * ((size_entries + 7) // 8)
    total_bitmap_bytes = download_bitmap_bytes + size_bitmap_bytes
    if total_bitmap_bytes > MAX_STREAM_TAG_BITMAP_BYTES:
        raise AdmissionError(
            f"download/size tag bitmaps exceed memory bound: {total_bitmap_bytes} "
            f"> {MAX_STREAM_TAG_BITMAP_BYTES}"
        )

    with encoding_path.open("rb") as stream:
        encoding_header = stream.read(22)
    if len(encoding_header) != 22 or encoding_header[:2] != b"EN":
        raise AdmissionError("decoded encoding metadata has a truncated or invalid EN header")
    espec_bytes = struct.unpack(">I", encoding_header[18:22])[0]
    if espec_bytes > MAX_ENCODING_ESPEC_BYTES:
        raise AdmissionError(
            f"encoding ESpec table exceeds memory bound: {espec_bytes}>{MAX_ENCODING_ESPEC_BYTES}"
        )
    return {
        "download_entry_count": download_entries,
        "download_tag_count": download_tags,
        "download_tag_bitmap_bytes": download_bitmap_bytes,
        "size_entry_count": size_entries,
        "size_tag_count": size_tags,
        "size_tag_bitmap_bytes": size_bitmap_bytes,
        "tag_bitmap_bytes_upper_bound": total_bitmap_bytes,
        "encoding_espec_bytes": espec_bytes,
    }


def select_install_records(install_file: Any) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Apply the canonical host selector and require the exact ARM64 EXE identity."""
    entries = install_file.entries
    tags = install_file.tags
    if len(entries) > MAX_INSTALL_ENTRIES or len(tags) > MAX_INSTALL_TAGS:
        raise AdmissionError("parsed install manifest exceeded the preflight entry/tag bounds")
    tag_by_name: dict[str, list[Any]] = {}
    for tag in tags:
        tag_by_name.setdefault(tag.name, []).append(tag)
        expected_mask_size = (len(entries) + 7) // 8
        if len(tag.bit_mask) != expected_mask_size:
            raise AdmissionError(
                f"install tag {tag.name!r} bitmap size mismatch: "
                f"{len(tag.bit_mask)} != {expected_mask_size}"
            )
    requested = (PLATFORM, ARCHITECTURE, LOCALE)
    for name in requested:
        if len(tag_by_name.get(name, [])) != 1:
            raise AdmissionError(f"install manifest must contain exactly one {name!r} tag")
    tag_types = [tag_by_name[name][0].tag_type for name in requested]
    if len(set(tag_types)) != len(tag_types):
        raise AdmissionError(
            "Windows, arm64, and enUS must be independent install tag groups"
        )
    try:
        selected = filter_install_entries(entries, tags, PLATFORM, ARCHITECTURE, LOCALE)
    except Exception as exc:
        raise AdmissionError(f"could not apply exact install tag selection: {exc}") from exc
    normalizer = PathNormalizer()
    records: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, entry in enumerate(selected):
        path = normalizer.normalize(entry.filename).replace("\\", "/")
        parts = path.split("/")
        if (
            not path
            or path.startswith("/")
            or re.match(r"^[A-Za-z]:($|/)", path)
            or any(part in {"", ".", ".."} for part in parts)
        ):
            raise AdmissionError(f"unsafe selected install path: {entry.filename!r}")
        folded = path.casefold()
        if folded in seen:
            raise AdmissionError(f"duplicate selected install path: {path!r}")
        seen.add(folded)
        if len(entry.md5_hash) != 16 or entry.size < 0:
            raise AdmissionError(f"invalid selected manifest identity for {path!r}")
        records.append(
            {
                "record_type": "selected-install-file",
                "selection_index": index,
                "relative_path": path,
                "content_key": entry.md5_hash.hex(),
                "decoded_size": entry.size,
                "manifest_tags": list(entry.tags),
                "manifest_file_type": entry.file_type,
            }
        )
    executable = [
        item for item in records
        if item["relative_path"].casefold() == EXPECTED_EXECUTABLE.casefold()
    ]
    if len(executable) != 1:
        raise AdmissionError(
            f"selected Windows/arm64/enUS manifest has {len(executable)} exact "
            f"{EXPECTED_EXECUTABLE} entries; expected one"
        )
    return records, {
        "relative_path": executable[0]["relative_path"],
        "content_key": executable[0]["content_key"],
        "decoded_size": executable[0]["decoded_size"],
        "manifest_tags": executable[0]["manifest_tags"],
        "manifest_file_type": executable[0]["manifest_file_type"],
        "identity_source": "hash-bound install manifest selection; executable payload not acquired",
        "architecture_evidence": "declared arm64 selection tag; PE Machine field not inspected",
        "pe_machine_observed": False,
    }


def _write_selection(
    output_path: Path,
    *,
    official: OfficialBuild,
    install_info: Any,
    raw_artifact: dict[str, Any],
    decoded_evidence: dict[str, Any],
    tags: list[Any],
    all_entry_count: int,
    records: list[dict[str, Any]],
) -> dict[str, Any]:
    header = {
        "record_type": "install-selection-header",
        "schema": "wowforever.arm64-build-admission.selection",
        "schema_version": 1,
        "product": PRODUCT,
        "region": REGION,
        "build_id": official.build_id,
        "version": official.version,
        "build_config_hash": official.build_config_hash,
        "cdn_config_hash": official.cdn_config_hash,
        "selection_query": SELECTION_QUERY,
        "selection_semantics": "tag types are ANDed; requested tags have distinct type IDs",
        "install_content_key": install_info.content_key.lower(),
        "install_encoding_key": install_info.encoding_key.lower(),
        "install_encoded_size": install_info.size,
        "install_raw_sha256": raw_artifact["sha256"],
        "install_decoded_sha256": decoded_evidence["decoded_sha256"],
        "install_manifest_decoded_md5": decoded_evidence["decoded_md5"],
        "manifest_entry_count": all_entry_count,
        "selected_entry_count": len(records),
        "tags": [{"name": tag.name, "tag_type": tag.tag_type} for tag in tags],
        "android_trust_contract": (
            "Pin build_config_hash and cdn_config_hash from trusted official-version evidence; "
            "verify config MD5s, install EKey/CKey and encoded size, then reparse the install "
            "manifest and repeat the Windows/arm64/enUS tag selection. Do not trust this JSONL alone."
        ),
    }
    with output_path.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(header, sort_keys=True, separators=(",", ":")) + "\n")
        for record in records:
            output.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
    return {
        "path": output_path.name,
        "size_bytes": output_path.stat().st_size,
        "sha256": _hash_file(output_path, "sha256"),
        "record_count": len(records),
        "manifest_entry_count": all_entry_count,
        "selection_query": SELECTION_QUERY,
    }


def _ensure_output_dir(path: Path) -> Path:
    expected = DEFAULT_OUTPUT.resolve(strict=False)
    absolute = path.absolute()
    resolved = path.resolve(strict=False)
    repo_root = Path(__file__).resolve().parents[3]
    if resolved != expected:
        raise AdmissionError(f"output directory must be exactly {DEFAULT_OUTPUT}")
    if resolved == repo_root or repo_root in resolved.parents:
        raise AdmissionError("metadata output directory must be outside the repository")
    current = absolute
    while current != current.parent:
        if current.is_symlink():
            raise AdmissionError(f"output path contains a symlink: {current}")
        current = current.parent
    if not path.parent.is_dir():
        raise AdmissionError(f"output parent directory does not exist: {path.parent}")
    if path.exists():
        if not path.is_dir() or any(path.iterdir()):
            raise AdmissionError(f"output directory must not exist or must be empty: {path}")
    else:
        path.mkdir()
    return path


def _promote_stage(stage: Path, output_dir: Path) -> None:
    for name in ("metadata", "install-selection.jsonl", "metadata-manifest.json", "admission.json"):
        source = stage / name
        if source.exists():
            os.replace(source, output_dir / name)


def run_admission(
    *,
    output_dir: Path = DEFAULT_OUTPUT,
    cache_root: Path = DEFAULT_CACHE_ROOT,
    timeout: float = 15.0,
) -> dict[str, Any]:
    if not math.isfinite(timeout) or timeout <= 0 or timeout > 120:
        raise AdmissionError("timeout must be finite, positive, and at most 120 seconds")
    output_dir = _ensure_output_dir(output_dir)
    cache = _readonly_cache(cache_root)
    budget = NetworkBudget()

    with tempfile.TemporaryDirectory(prefix=".stage-", dir=output_dir) as temporary:
        stage = Path(temporary)
        metadata_dir = stage / "metadata"
        metadata_dir.mkdir()

        versions_path = metadata_dir / "official-versions.tsv"
        _https_get_to_file(
            VERSION_URL,
            versions_path,
            body_limit=MAX_VERSION_BYTES,
            expected_size=None,
            timeout=timeout,
            budget=budget,
        )
        versions_raw = versions_path.read_bytes()
        official = parse_official_versions(versions_raw)
        versions_artifact = _artifact(
            "official-versions-table",
            versions_path,
            source_kind="official-https",
            source=f"HTTP 200; {len(versions_raw)} response bytes",
            url=VERSION_URL,
        )

        build_path = metadata_dir / f"build-config-{official.build_config_hash}.txt"
        cdn_path = metadata_dir / f"cdn-config-{official.cdn_config_hash}.txt"
        build_artifact = _acquire_metadata(
            cache=cache,
            output_path=build_path,
            digest=official.build_config_hash,
            kind="config",
            url=_config_url(official.build_config_hash),
            role="build-config",
            body_limit=MAX_CONFIG_BYTES,
            expected_size=None,
            timeout=timeout,
            budget=budget,
        )
        cdn_artifact = _acquire_metadata(
            cache=cache,
            output_path=cdn_path,
            digest=official.cdn_config_hash,
            kind="config",
            url=_config_url(official.cdn_config_hash),
            role="cdn-config",
            body_limit=MAX_CONFIG_BYTES,
            expected_size=None,
            timeout=timeout,
            budget=budget,
        )
        build_raw = build_path.read_bytes()
        cdn_raw = cdn_path.read_bytes()
        build, cdn, install = _parse_hash_bound_configs(official, build_raw, cdn_raw)

        install_path = metadata_dir / f"install-{install.encoding_key.lower()}.blte"
        install_artifact = _acquire_metadata(
            cache=cache,
            output_path=install_path,
            digest=install.encoding_key,
            kind="data",
            url=_data_url(install.encoding_key),
            role="install-manifest-encoded-object",
            body_limit=MAX_METADATA_OBJECT_BYTES,
            expected_size=install.size,
            timeout=timeout,
            budget=budget,
        )
        decoded_path = metadata_dir / f"install-{install.content_key.lower()}.manifest"
        with install_path.open("rb") as source, decoded_path.open("xb") as decoded_output:
            limited_output = _LimitedWriter(decoded_output, MAX_METADATA_OBJECT_BYTES)
            try:
                decoded_evidence = stream_decode_blte(
                    source,
                    limited_output,
                    expected_ekey=install.encoding_key.lower(),
                    encoded_size=install.size,
                )
            except Exception as exc:
                raise AdmissionError(f"install metadata BLTE/EKey verification failed: {exc}") from exc
        if decoded_evidence["decoded_md5"] != install.content_key.lower():
            raise AdmissionError(
                f"install decoded CKey mismatch: {decoded_evidence['decoded_md5']} "
                f"!= {install.content_key.lower()}"
            )
        declared_tag_count, declared_entry_count = _preflight_install(decoded_path)
        decoded_evidence["decoded_sha256"] = _hash_file(decoded_path, "sha256")
        decoded_artifact = _artifact(
            "install-manifest-decoded",
            decoded_path,
            source_kind="verified-decode",
            source=install_artifact["path"],
            url=install_artifact["url"],
        )
        try:
            with decoded_path.open("rb") as stream:
                install_file = InstallParser().parse(stream)
                if stream.read(1):
                    raise AdmissionError("install manifest parser left trailing bytes")
        except AdmissionError:
            raise
        except Exception as exc:
            raise AdmissionError(f"could not parse verified install manifest: {exc}") from exc
        if len(install_file.entries) != declared_entry_count or len(install_file.tags) != declared_tag_count:
            raise AdmissionError("install manifest parser counts disagree with the preflight header")
        records, executable = select_install_records(install_file)

        selection_path = stage / "install-selection.jsonl"
        selection = _write_selection(
            selection_path,
            official=official,
            install_info=install,
            raw_artifact=install_artifact,
            decoded_evidence=decoded_evidence,
            tags=install_file.tags,
            all_entry_count=len(install_file.entries),
            records=records,
        )
        artifacts = [
            versions_artifact,
            build_artifact,
            cdn_artifact,
            install_artifact,
            decoded_artifact,
            {
                "role": "install-selection",
                "path": selection["path"],
                "source_kind": "derived-from-verified-install-manifest",
                "source": decoded_artifact["path"],
                "url": install_artifact["url"],
                "size_bytes": selection["size_bytes"],
                "sha256": selection["sha256"],
            },
        ]
        artifact_total_bytes = sum(item["size_bytes"] for item in artifacts)
        if artifact_total_bytes > MAX_TOTAL_ARTIFACT_BYTES:
            raise AdmissionError(
                f"metadata artifact total exceeds {MAX_TOTAL_ARTIFACT_BYTES}: {artifact_total_bytes}"
            )
        metadata_manifest = {
            "schema": "wowforever.arm64-build-admission.metadata-manifest",
            "schema_version": 1,
            "product": PRODUCT,
            "build_id": official.build_id,
            "version": official.version,
            "build_config_hash": official.build_config_hash,
            "cdn_config_hash": official.cdn_config_hash,
            "artifacts": artifacts,
            "selection": selection,
        }
        (stage / "metadata-manifest.json").write_text(
            json.dumps(metadata_manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        summary: dict[str, Any] = {
            "schema": "wowforever.arm64-build-admission",
            "schema_version": 1,
            "status": "metadata-admitted",
            "provenance": {
                "product": PRODUCT,
                "region": REGION,
                "build_id": official.build_id,
                "version": official.version,
                "official_versions_url": VERSION_URL,
                "official_versions_sequence": official.sequence,
                "official_versions_sha256": official.feed_sha256,
                "official_versions_bytes": official.feed_bytes,
                "build_config_hash": official.build_config_hash,
                "cdn_config_hash": official.cdn_config_hash,
                "product_config_hash": official.product_config_hash,
                "cdn_config_hosts_used": [CDN_HOST],
                "cdn_config_build_references": list(cdn.builds),
                "cdn_archive_count": len(cdn.archives),
                "cdn_file_index": cdn.file_index,
                "build_name": build.build_name,
                "build_uid": build.build_uid,
                "cache_root": str(cache_root),
                "cache_access": "DiskCache path resolver only; constructor bypassed; files copied read-only",
            },
            "install_metadata_reference": {
                "content_key": install.content_key.lower(),
                "encoding_key": install.encoding_key.lower(),
                "encoded_size": install.size,
                "tact_ekey_rule": "header MD5 when BLTE headerSize > 0; whole-object MD5 otherwise",
                "decoded_content_key_rule": "MD5 of decoded install manifest",
                "decoded_size": decoded_evidence["decoded_size"],
                "decoded_md5": decoded_evidence["decoded_md5"],
                "decoded_sha256": decoded_evidence["decoded_sha256"],
                "blte_flags": decoded_evidence["blte_flags"],
                "blte_modes": decoded_evidence["blte_modes"],
            },
            "manifest_selection": {
                "query": SELECTION_QUERY,
                "semantics": "OR within a tag_type group, AND between groups; required tags have distinct tag_type values",
                "all_install_entry_count": len(install_file.entries),
                "selected_entry_count": len(records),
                "tag_types": {tag.name: tag.tag_type for tag in install_file.tags},
                "selection_artifact": selection,
                "executable": executable,
            },
            "architecture_evidence": {
                "manifest_declares_arm64_selection": True,
                "executable_path": executable["relative_path"],
                "executable_content_key": executable["content_key"],
                "pe_machine_observed": False,
                "claim_boundary": "Install tags/path identify the ARM64 executable selection; no PE payload was downloaded, so PE Machine is unproven.",
            },
            "server_compatibility": {
                "server": "ClassicForever",
                "build_id": EXPECTED_BUILD_ID,
                "status": "user-confirmed build compatibility; not independently login-tested",
                "login_or_gameplay_verified": False,
            },
            "update_claim": {
                "assessment": "not-assessed",
                "update_available_claimed": False,
                "reason": "No exact upstream ARK tag/source proof was gathered by this metadata admission.",
            },
            "network_and_bounds": {
                "network_bytes_received": budget.response_bytes,
                "network_response_budget_bytes": MAX_TOTAL_RESPONSE_BYTES,
                "metadata_artifact_budget_bytes": MAX_TOTAL_ARTIFACT_BYTES,
                "network_ledger_sequence": 0,
                "network_fetches": budget.fetches,
                "metadata_artifact_bytes_including_decoded_and_selection": artifact_total_bytes,
                "max_per_config_bytes": MAX_CONFIG_BYTES,
                "max_install_metadata_encoded_and_decoded_bytes": MAX_METADATA_OBJECT_BYTES,
                "max_install_entries": MAX_INSTALL_ENTRIES,
                "max_install_tags": MAX_INSTALL_TAGS,
                "large_game_payloads_or_archives_downloaded": False,
                "executable_payload_downloaded_or_executed": False,
                "cache_modified": False,
                "credentials_used": False,
            },
            "artifacts": artifacts,
            "android_consumer_pin_protocol": {
                "do_not_trust_summary_or_selection_jsonl_as_authentication": True,
                "pin": ["provenance.build_config_hash", "provenance.cdn_config_hash"],
                "verify": [
                    "fetch the official version row over validated HTTPS or use these independently pinned values",
                    "MD5 the raw build/CDN configs and compare to the pinned hashes",
                    "parse build config product and its second install hash as the CDN EKey",
                    "verify install encoded size and TACT EKey (header digest rule), decode CKey, then reparse the full install manifest",
                    "repeat Windows/arm64/enUS tag selection and require the executable path recorded here",
                ],
                "architecture_limit": "The authenticated install manifest establishes ARM64 selection tags and executable path, not PE COFF Machine identity.",
            },
        }
        (stage / "admission.json").write_text(
            json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        _promote_stage(stage, output_dir)
        summary["summary_path"] = str(output_dir / "admission.json")
        return summary


def _safe_output_file(root: Path, relative: str) -> Path:
    candidate = Path(relative)
    if candidate.is_absolute() or any(part in {"", ".", ".."} for part in candidate.parts):
        raise AdmissionError(f"unsafe output artifact path: {relative!r}")
    path = root / candidate
    if path.is_symlink() or not path.is_file() or root.resolve() not in path.resolve().parents:
        raise AdmissionError(f"missing/unsafe output artifact: {path}")
    return path


def _output_tree_bytes(root: Path) -> int:
    total = 0
    for path in root.rglob("*"):
        if path.is_symlink():
            raise AdmissionError(f"output tree contains a symlink: {path}")
        if path.is_file():
            total += path.stat().st_size
    return total


def _metadata_reference_record(
    role: str,
    info: Any,
    raw: dict[str, Any],
    decoded: dict[str, Any],
) -> dict[str, Any]:
    return {
        "role": role,
        "content_key": info.content_key.lower(),
        "encoding_key": info.encoding_key.lower(),
        "declared_encoded_size": info.size,
        "decoded_size": decoded["decoded_size"],
        "decoded_md5": decoded["decoded_md5"],
        "decoded_sha256": decoded["decoded_sha256"],
        "blte_flags": decoded["blte_flags"],
        "blte_modes": decoded["blte_modes"],
        "raw_artifact": raw["path"],
        "raw_sha256": raw["sha256"],
    }


def _closure_metadata_records(
    references: dict[str, Any],
    raw_artifacts: dict[str, dict[str, Any]],
    decoded_evidence: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    if set(references) != {"install", "encoding", "download", "size"}:
        raise AdmissionError(f"closure reference set is incomplete: {sorted(references)}")
    if set(raw_artifacts) != set(references) or set(decoded_evidence) != set(references):
        raise AdmissionError("closure metadata evidence roles do not match config references")
    return {
        role: _metadata_reference_record(
            role, info, raw_artifacts[role], decoded_evidence[role]
        )
        for role, info in references.items()
    }


def _write_install_encoding_provenance(
    db: sqlite3.Connection,
    records: list[dict[str, Any]],
    path: Path,
    aggregate: list[int],
) -> int:
    count = 0
    with path.open("w", encoding="utf-8", buffering=IO_BYTES) as stream:
        output = _BoundedTextWriter(stream, aggregate, MAX_CLOSURE_REPORT_BYTES)
        for item in records:
            ckey = bytes.fromhex(item["content_key"])
            mappings = list(
                db.execute(
                    "SELECT c.ekey,c.decoded_size,e.encoded_size "
                    "FROM encoding_ckeys AS c LEFT JOIN encoding_ekeys AS e ON e.ekey=c.ekey "
                    "WHERE c.ckey=? ORDER BY c.ekey",
                    (ckey,),
                )
            )
            if not mappings:
                raise AdmissionError(
                    f"selected install CKey is absent from encoding pages: {item['relative_path']}"
                )
            keys: list[dict[str, Any]] = []
            for ekey, decoded_size, encoded_size in mappings:
                if encoded_size is None or decoded_size != item["decoded_size"]:
                    raise AdmissionError(
                        f"selected install encoding size mismatch: {item['relative_path']}"
                    )
                keys.append(
                    {
                        "encoding_key": bytes(ekey).hex(),
                        "encoded_size": encoded_size,
                        "decoded_size": decoded_size,
                    }
                )
            output.write(
                json.dumps(
                    {
                        "record_type": "selected-install-encoding-provenance",
                        "relative_path": item["relative_path"],
                        "content_key": item["content_key"],
                        "decoded_size": item["decoded_size"],
                        "manifest_tags": item["manifest_tags"],
                        "encoding_keys": keys,
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
            )
            count += 1
    return count


def _validate_selection_tags(tags: list[Any], role: str) -> None:
    by_name: dict[str, list[Any]] = {}
    for tag in tags:
        by_name.setdefault(tag.name, []).append(tag)
    requested = (PLATFORM, ARCHITECTURE, LOCALE)
    for name in requested:
        if len(by_name.get(name, [])) != 1:
            raise AdmissionError(
                f"{role} manifest must contain exactly one {name!r} selection tag"
            )
    tag_types = [by_name[name][0].tag_type for name in requested]
    if len(set(tag_types)) != len(tag_types):
        raise AdmissionError(f"{role} Windows/arm64/enUS tags are not independent groups")


def _create_consumer_work_tables(db: sqlite3.Connection) -> None:
    db.executescript(
        """
        CREATE TABLE consumer_work (
            ekey BLOB PRIMARY KEY,
            ckey BLOB NOT NULL,
            encoded_size INTEGER NOT NULL,
            decoded_size INTEGER NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE consumer_candidates (
            role TEXT NOT NULL,
            owner TEXT NOT NULL,
            ekey BLOB NOT NULL,
            ckey BLOB NOT NULL,
            encoded_size INTEGER NOT NULL,
            decoded_size INTEGER NOT NULL,
            PRIMARY KEY (role, owner, ekey)
        ) WITHOUT ROWID;
        CREATE TABLE consumer_loose (
            path TEXT NOT NULL,
            ekey BLOB NOT NULL,
            ckey BLOB NOT NULL,
            encoded_size INTEGER NOT NULL,
            decoded_size INTEGER NOT NULL,
            PRIMARY KEY (path, ekey)
        ) WITHOUT ROWID;
        CREATE TABLE consumer_chosen_loose (
            path TEXT PRIMARY KEY,
            ekey BLOB NOT NULL,
            ckey BLOB NOT NULL,
            encoded_size INTEGER NOT NULL,
            decoded_size INTEGER NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE consumer_needed (
            ekey BLOB PRIMARY KEY,
            encoded_size INTEGER NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE consumer_sources (
            ekey BLOB PRIMARY KEY,
            encoded_size INTEGER NOT NULL,
            source_kind TEXT NOT NULL,
            source_hash BLOB NOT NULL,
            source_offset INTEGER NOT NULL
        ) WITHOUT ROWID;
        """
    )


def _add_consumer_candidates(
    db: sqlite3.Connection,
    *,
    role: str,
    owner: str,
    content_key: str,
    expected_ekey: str | None = None,
    expected_encoded_size: int | None = None,
) -> int:
    """Bind one required CKey to encoding-page candidates without losing full EKeys."""
    ckey = bytes.fromhex(_valid_hash(content_key, f"{role} content key"))
    rows = list(
        db.execute(
            "SELECT c.ekey,c.decoded_size,e.encoded_size "
            "FROM encoding_ckeys AS c JOIN encoding_ekeys AS e ON e.ekey=c.ekey "
            "WHERE c.ckey=? ORDER BY c.ekey",
            (ckey,),
        )
    )
    if expected_ekey is not None:
        expected = bytes.fromhex(_valid_hash(expected_ekey, f"{role} EKey"))
        rows = [row for row in rows if bytes(row[0]) == expected]
    if not rows:
        raise AdmissionError(f"{role} CKey has no matching full EKey in encoding pages")
    decoded_sizes = {row[1] for row in rows}
    if len(decoded_sizes) != 1:
        raise AdmissionError(f"{role} CKey has conflicting decoded sizes across EKeys")
    if expected_encoded_size is not None and any(
        row[2] != expected_encoded_size for row in rows
    ):
        raise AdmissionError(f"{role} EKey encoded size disagrees with build-config reference")
    if len(rows) > 64:
        raise AdmissionError(f"{role} CKey has too many encoding alternatives: {len(rows)}")
    db.executemany(
        "INSERT INTO consumer_candidates(role,owner,ekey,ckey,encoded_size,decoded_size) "
        "VALUES(?,?,?,?,?,?)",
        [
            (role, owner, bytes(ekey), ckey, encoded_size, decoded_size)
            for ekey, decoded_size, encoded_size in rows
        ],
    )
    return len(rows)


def _add_explicit_metadata_candidate(
    db: sqlite3.Connection,
    *,
    role: str,
    info: Any,
    decoded_size: int,
) -> None:
    """Use the independently verified build-config reference for metadata objects.

    A build's encoding manifest need not contain an entry for itself (and can
    omit other host metadata objects). These exact CKey/EKey/encoded-size
    bindings come from the hash-bound build config and the completed BLTE
    verification, so self-mapping is not a prerequisite.
    """
    ckey = bytes.fromhex(_valid_hash(info.content_key, f"{role} content key"))
    ekey = bytes.fromhex(_valid_hash(info.encoding_key, f"{role} EKey"))
    if not isinstance(info.size, int) or info.size <= 0 or decoded_size < 0:
        raise AdmissionError(f"{role} metadata has invalid verified sizes")
    mapping_rows = list(
        db.execute(
            "SELECT c.ckey,c.decoded_size,e.encoded_size "
            "FROM encoding_ckeys AS c LEFT JOIN encoding_ekeys AS e ON e.ekey=c.ekey "
            "WHERE c.ekey=?",
            (ekey,),
        )
    )
    if mapping_rows and (
        any(bytes(row[0]) != ckey for row in mapping_rows)
        or any(row[1] != decoded_size for row in mapping_rows)
        or any(row[2] is not None and row[2] != info.size for row in mapping_rows)
    ):
        raise AdmissionError(f"{role} config/encoding-page bindings disagree")
    encoded_row = db.execute(
        "SELECT encoded_size FROM encoding_ekeys WHERE ekey=?", (ekey,)
    ).fetchone()
    if encoded_row is not None and encoded_row[0] != info.size:
        raise AdmissionError(f"{role} encoded size disagrees with encoding pages")
    db.execute(
        "INSERT INTO consumer_candidates(role,owner,ekey,ckey,encoded_size,decoded_size) "
        "VALUES(?, '', ?, ?, ?, ?)",
        (role, ekey, ckey, info.size, decoded_size),
    )


def _populate_consumer_work(
    db: sqlite3.Connection,
    *,
    build: Any,
    references: dict[str, Any],
    metadata_decoded_sizes: dict[str, int],
    install_records: list[dict[str, Any]],
    download_stats: Any,
) -> dict[str, Any]:
    """Cross-bind download objects, all required runtime roles, and loose files."""
    collision_count = db.execute(
        "SELECT COUNT(*) FROM ("
        "SELECT d.ekey FROM download_entries AS d "
        "JOIN encoding_ckeys AS c ON c.ekey=d.ekey "
        "WHERE d.selected=1 GROUP BY d.ekey HAVING COUNT(DISTINCT c.ckey)>1)"
    ).fetchone()[0]
    if collision_count:
        raise AdmissionError(
            f"{collision_count} selected download EKeys map to multiple CKeys; payload identity is ambiguous"
        )
    db.execute(
        "INSERT INTO consumer_work(ekey,ckey,encoded_size,decoded_size) "
        "SELECT d.ekey,MIN(c.ckey),MIN(d.encoded_size),MIN(c.decoded_size) "
        "FROM download_entries AS d JOIN encoding_ckeys AS c ON c.ekey=d.ekey "
        "WHERE d.selected=1 GROUP BY d.ekey"
    )
    selected_download_count, selected_download_bytes = db.execute(
        "SELECT COUNT(*),COALESCE(SUM(encoded_size),0) FROM consumer_work"
    ).fetchone()
    if selected_download_count != download_stats.selected_ekey_count:
        raise AdmissionError(
            "unique selected download EKey count disagrees with the streaming manifest parser"
        )

    runtime_role_count = 0
    if not getattr(build, "root", None):
        raise AdmissionError("build config has no root CKey for the required runtime")
    runtime_role_count += _add_consumer_candidates(
        db, role="root", owner="", content_key=build.root
    )

    vfs_root = build.get_vfs_root_info()
    if vfs_root is None:
        raise AdmissionError("build config has no vfs-root reference for the required runtime")
    runtime_role_count += _add_consumer_candidates(
        db,
        role="vfs-root",
        owner="",
        content_key=vfs_root.content_key,
        expected_ekey=vfs_root.encoding_key,
        expected_encoded_size=vfs_root.size,
    )
    vfs_entries = build.get_vfs_entries()
    for index, info in vfs_entries:
        runtime_role_count += _add_consumer_candidates(
            db,
            role=f"vfs-{index}",
            owner="",
            content_key=info.content_key,
            expected_ekey=info.encoding_key,
            expected_encoded_size=info.size,
        )

    required_runtime = {"root", "vfs-root", "encoding", "install", "download", "size"}
    if not required_runtime.issubset({"root", "vfs-root", *(f"vfs-{i}" for i, _ in vfs_entries), *references}):
        raise AdmissionError("required runtime role set is incomplete")
    for role in ("encoding", "install", "download", "size"):
        info = references.get(role)
        if info is None:
            raise AdmissionError(f"build config lacks required runtime {role} reference")
        if role not in metadata_decoded_sizes:
            raise AdmissionError(f"verified decoded size is missing for {role} metadata")
        _add_explicit_metadata_candidate(
            db, role=role, info=info, decoded_size=metadata_decoded_sizes[role]
        )
        runtime_role_count += 1

    for item in install_records:
        content_key = item["content_key"]
        expected_decoded_size = item["decoded_size"]
        ckey = bytes.fromhex(_valid_hash(content_key, "selected loose-file CKey"))
        rows = list(
            db.execute(
                "SELECT c.ekey,c.decoded_size,e.encoded_size "
                "FROM encoding_ckeys AS c JOIN encoding_ekeys AS e ON e.ekey=c.ekey "
                "WHERE c.ckey=? ORDER BY c.ekey",
                (ckey,),
            )
        )
        if not rows:
            raise AdmissionError(
                f"selected install file has no encoding EKey: {item['relative_path']}"
            )
        if any(decoded_size != expected_decoded_size for _, decoded_size, _ in rows):
            raise AdmissionError(
                f"selected install file decoded size disagrees with encoding pages: {item['relative_path']}"
            )
        db.executemany(
            "INSERT INTO consumer_loose(path,ekey,ckey,encoded_size,decoded_size) "
            "VALUES(?,?,?,?,?)",
            [
                (item["relative_path"], bytes(ekey), ckey, encoded_size, decoded_size)
                for ekey, decoded_size, encoded_size in rows
            ],
        )
        db.executemany(
            "INSERT INTO consumer_candidates(role,owner,ekey,ckey,encoded_size,decoded_size) "
            "VALUES('loose',?,?,?,?,?)",
            [
                (item["relative_path"], bytes(ekey), ckey, encoded_size, decoded_size)
                for ekey, decoded_size, encoded_size in rows
            ],
        )

    db.execute(
        "INSERT INTO consumer_needed(ekey,encoded_size) "
        "SELECT ekey,MIN(encoded_size) FROM consumer_work GROUP BY ekey"
    )
    conflicting_candidate_sizes = db.execute(
        "SELECT COUNT(*) FROM (SELECT ekey FROM consumer_candidates "
        "GROUP BY ekey HAVING MIN(encoded_size)!=MAX(encoded_size))"
    ).fetchone()[0]
    if conflicting_candidate_sizes:
        raise AdmissionError(
            f"{conflicting_candidate_sizes} required runtime/loose EKeys have conflicting encoded sizes"
        )
    db.execute(
        "INSERT INTO consumer_needed(ekey,encoded_size) "
        "SELECT ekey,MIN(encoded_size) FROM consumer_candidates GROUP BY ekey "
        "ON CONFLICT(ekey) DO NOTHING"
    )
    size_disagreements = db.execute(
        "SELECT COUNT(*) FROM consumer_needed AS n JOIN consumer_candidates AS c USING(ekey) "
        "WHERE n.encoded_size!=c.encoded_size"
    ).fetchone()[0]
    if size_disagreements:
        raise AdmissionError("same full EKey has inconsistent encoded sizes across required records")
    db.commit()
    return {
        "selected_download_unique_ekeys": selected_download_count,
        "selected_download_unique_encoded_bytes": selected_download_bytes,
        "required_runtime_candidate_count": runtime_role_count,
        "required_runtime_roles": sorted(required_runtime | {f"vfs-{i}" for i, _ in vfs_entries}),
        "selected_loose_path_count": len(install_records),
        "needed_full_ekey_count": db.execute("SELECT COUNT(*) FROM consumer_needed").fetchone()[0],
        "bloom_filter_bytes": (
            db.execute("SELECT COUNT(*) FROM consumer_needed").fetchone()[0] * 20 + 7
        ) // 8,
    }


class _EKeyBloom:
    """Small membership prefilter; SQLite remains the full-identity authority."""

    def __init__(self, bit_count: int):
        self.bit_count = max(64, bit_count)
        self.bits = bytearray((self.bit_count + 7) // 8)

    def _positions(self, ekey: bytes) -> Any:
        first = int.from_bytes(ekey[:8], "big")
        step = int.from_bytes(ekey[8:], "big") | 1
        for number in range(14):
            yield (first + number * step) % self.bit_count

    def add(self, ekey: bytes) -> None:
        for position in self._positions(ekey):
            self.bits[position >> 3] |= 1 << (position & 7)

    def may_contain(self, ekey: bytes) -> bool:
        return all(
            self.bits[position >> 3] & (1 << (position & 7))
            for position in self._positions(ekey)
        )


def _build_consumer_bloom(db: sqlite3.Connection) -> _EKeyBloom:
    count = db.execute("SELECT COUNT(*) FROM consumer_needed").fetchone()[0]
    if count <= 0 or count > MAX_CONSUMER_CATALOG_OBJECTS:
        raise AdmissionError(f"consumer catalog EKey count outside bound: {count}")
    bloom = _EKeyBloom(count * 20)
    for (ekey,) in db.execute("SELECT ekey FROM consumer_needed"):
        bloom.add(bytes(ekey))
    return bloom


def _cdn_index_layout(raw: bytes, digest: str, file_index_hash: str | None) -> dict[str, Any]:
    digest = _valid_hash(digest, "CDN index hash")
    if not raw or len(raw) > MAX_CDN_INDEX_BYTES:
        raise AdmissionError(
            f"CDN index size outside bound: {len(raw)} bytes (max {MAX_CDN_INDEX_BYTES})"
        )
    parser = CdnArchiveParser()
    try:
        footer = parser._parse_footer(raw)
    except Exception as exc:
        raise AdmissionError(f"invalid CDN index footer {digest}: {exc}") from exc
    is_file_index = digest == file_index_hash
    expected_offset_bytes = 0 if is_file_index else 4
    if (
        footer.key_bytes != 16
        or footer.size_bytes != 4
        or footer.offset_bytes != expected_offset_bytes
        or footer.page_size_kb <= 0
        or footer.footer_hash_bytes <= 0
        or footer.footer_hash_bytes > 16
        or footer.entry_count <= 0
    ):
        raise AdmissionError(
            f"unsupported CDN index layout {digest}: offset={footer.offset_bytes} "
            f"size={footer.size_bytes} key={footer.key_bytes} entries={footer.entry_count}"
        )
    page_size = footer.page_size_kb * 1024
    entry_size = footer.key_bytes + footer.size_bytes + footer.offset_bytes
    entries_per_page = page_size // entry_size
    if entries_per_page <= 0:
        raise AdmissionError(f"invalid CDN index page geometry: {digest}")
    page_count = (footer.entry_count + entries_per_page - 1) // entries_per_page
    expected_length = (
        page_count * page_size
        + page_count * footer.key_bytes
        + page_count * footer.footer_hash_bytes
        + parser.FOOTER_SIZE
    )
    if len(raw) != expected_length:
        raise AdmissionError(
            f"CDN index length disagrees with footer {digest}: {len(raw)} != {expected_length}"
        )
    return {
        "footer": footer,
        "file_index": is_file_index,
        "page_size": page_size,
        "entry_size": entry_size,
        "entries_per_page": entries_per_page,
        "page_count": page_count,
        "pages_bytes": page_count * page_size,
    }


def _iter_cdn_index_entries(
    raw: bytes, digest: str, file_index_hash: str | None
) -> Any:
    layout = _cdn_index_layout(raw, digest, file_index_hash)
    footer = layout["footer"]
    page_size = layout["page_size"]
    entries_per_page = layout["entries_per_page"]
    pages_bytes = layout["pages_bytes"]
    page_hash_bytes = footer.footer_hash_bytes
    for page_index in range(layout["page_count"]):
        page_start = page_index * page_size
        page = raw[page_start : page_start + page_size]
        entries_this_page = min(
            entries_per_page, footer.entry_count - page_index * entries_per_page
        )
        toc_keys_start = pages_bytes
        toc_hashes_start = pages_bytes + layout["page_count"] * footer.key_bytes
        last_key = page[(entries_this_page - 1) * layout["entry_size"] :
                        (entries_this_page - 1) * layout["entry_size"] + footer.key_bytes]
        advertised_last_key = raw[
            toc_keys_start + page_index * footer.key_bytes :
            toc_keys_start + (page_index + 1) * footer.key_bytes
        ]
        advertised_page_hash = raw[
            toc_hashes_start + page_index * page_hash_bytes :
            toc_hashes_start + (page_index + 1) * page_hash_bytes
        ]
        actual_page_hash = hashlib.md5(page).digest()[:page_hash_bytes]
        if advertised_last_key != last_key or advertised_page_hash != actual_page_hash:
            raise AdmissionError(f"CDN index page TOC/checksum mismatch: {digest} page {page_index}")
        for entry_index in range(entries_this_page):
            position = page_start + entry_index * layout["entry_size"]
            ekey = raw[position : position + footer.key_bytes]
            if ekey == bytes(footer.key_bytes):
                continue
            size_start = position + footer.key_bytes
            encoded_size = int.from_bytes(raw[size_start : size_start + footer.size_bytes], "big")
            if encoded_size <= 0:
                raise AdmissionError(f"CDN index has a zero-sized object: {digest}")
            offset = None
            if not layout["file_index"]:
                offset_start = size_start + footer.size_bytes
                offset = int.from_bytes(raw[offset_start : offset_start + footer.offset_bytes], "big")
            yield ekey, encoded_size, offset


def _record_consumer_source(
    db: sqlite3.Connection,
    *,
    ekey: bytes,
    encoded_size: int,
    source_kind: str,
    source_hash: bytes,
    source_offset: int,
) -> None:
    expected = db.execute(
        "SELECT encoded_size FROM consumer_needed WHERE ekey=?", (ekey,)
    ).fetchone()
    if expected is None:
        return
    if expected[0] != encoded_size:
        raise AdmissionError(
            f"CDN source encoded size mismatch for full EKey {ekey.hex()}: "
            f"index={encoded_size} encoding={expected[0]}"
        )
    existing = db.execute(
        "SELECT encoded_size,source_kind,source_hash,source_offset "
        "FROM consumer_sources WHERE ekey=?",
        (ekey,),
    ).fetchone()
    new_record = (encoded_size, source_kind, source_hash, source_offset)
    if existing is None:
        db.execute(
            "INSERT INTO consumer_sources(ekey,encoded_size,source_kind,source_hash,source_offset) "
            "VALUES(?,?,?,?,?)",
            (ekey, *new_record),
        )
        return
    if existing[0] != encoded_size:
        raise AdmissionError(f"duplicate CDN source sizes disagree for full EKey {ekey.hex()}")
    if existing[1] == "direct":
        if source_kind == "direct" and tuple(existing) != new_record:
            raise AdmissionError(f"conflicting direct CDN source for full EKey {ekey.hex()}")
        return
    if source_kind == "direct":
        db.execute(
            "UPDATE consumer_sources SET source_kind=?,source_hash=?,source_offset=? WHERE ekey=?",
            (source_kind, source_hash, source_offset, ekey),
        )


def _persist_index_response(
    raw_cache_dir: Path, digest: str, scratch_path: Path, raw: bytes
) -> Path:
    current_index_bytes = sum(
        item.stat().st_size
        for item in raw_cache_dir.glob("cdn-index-*.index")
        if item.is_file() and not item.is_symlink()
    )
    if current_index_bytes + len(raw) > MAX_PERSISTED_CDN_INDEX_BYTES:
        raise AdmissionError(
            f"persisted CDN index cache exceeds {MAX_PERSISTED_CDN_INDEX_BYTES} byte bound"
        )
    destination = raw_cache_dir / f"cdn-index-{digest}.index"
    if destination.is_symlink():
        raise AdmissionError(f"persistent CDN index is a symlink: {destination}")
    if destination.exists():
        if _hash_file(destination, "sha256") != hashlib.sha256(raw).hexdigest():
            raise AdmissionError(f"persistent CDN index changed during acquisition: {digest}")
        scratch_path.unlink(missing_ok=True)
        return destination
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=".cdn-index-incoming-", dir=raw_cache_dir, delete=False
        ) as output:
            temporary = Path(output.name)
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o444)
        if _hash_file(temporary, "sha256") != hashlib.sha256(raw).hexdigest():
            raise AdmissionError("persistent CDN index copy changed bytes")
        try:
            os.link(temporary, destination)
        except FileExistsError:
            if _hash_file(destination, "sha256") != hashlib.sha256(raw).hexdigest():
                raise AdmissionError(f"concurrent persistent CDN index differs: {digest}")
        return destination
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        scratch_path.unlink(missing_ok=True)


def _acquire_cdn_index(
    *,
    cache: DiskCache,
    raw_cache_dir: Path,
    stage_path: Path,
    digest: str,
    file_index_hash: str | None,
    timeout: float,
    budget: NetworkBudget,
    ledger_events: list[dict[str, Any]],
) -> tuple[bytes, str, str]:
    digest = _valid_hash(digest, "CDN index hash")
    persistent = raw_cache_dir / f"cdn-index-{digest}.index"
    if persistent.is_symlink():
        raise AdmissionError(f"persistent CDN index is a symlink: {persistent}")
    if persistent.is_file():
        raw_sha = _hash_file(persistent, "sha256")
        if not any(
            event.get("role") == "cdn-index"
            and event.get("expected_index_hash") == digest
            and event.get("response_raw_sha256") == raw_sha
            for event in ledger_events
        ):
            raise AdmissionError(f"persistent CDN index lacks matching transfer evidence: {digest}")
        raw = persistent.read_bytes()
        _cdn_index_layout(raw, digest, file_index_hash)
        return raw, "verified-external-index-cache", raw_sha

    cache_path = cache._get_cdn_cache_path(digest, "index", CDN_PATH)
    if cache_path.is_symlink():
        raise AdmissionError(f"read-only cascette CDN index is a symlink: {cache_path}")
    if cache_path.is_file():
        size = cache_path.stat().st_size
        if size <= 0 or size > MAX_CDN_INDEX_BYTES:
            raise AdmissionError(f"cached CDN index size outside bound: {size} at {cache_path}")
        raw = cache_path.read_bytes()
        _cdn_index_layout(raw, digest, file_index_hash)
        return raw, "read-only-cascette-cache", hashlib.sha256(raw).hexdigest()

    index_url = _index_url(digest)

    def record_response(url: str, status: int, received: int) -> None:
        _append_index_transfer_event(
            raw_cache_dir,
            ledger_events,
            index_hash=digest,
            url=url,
            status=status,
            received=received,
            response_path=stage_path,
        )

    status, received = _https_get_to_file(
        index_url,
        stage_path,
        body_limit=MAX_CDN_INDEX_BYTES,
        expected_size=None,
        timeout=timeout,
        budget=budget,
        response_callback=record_response,
    )
    if status != 200 or received <= 0:
        raise AdmissionError(f"CDN index transfer did not complete: {digest}")
    raw = stage_path.read_bytes()
    _cdn_index_layout(raw, digest, file_index_hash)
    _persist_index_response(raw_cache_dir, digest, stage_path, raw)
    return raw, "official-https", hashlib.sha256(raw).hexdigest()


def _scan_cdn_sources(
    *,
    db: sqlite3.Connection,
    cdn: Any,
    cache: DiskCache,
    raw_cache_dir: Path,
    stage_metadata: Path,
    timeout: float,
    budget: NetworkBudget,
    ledger_events: list[dict[str, Any]],
) -> dict[str, Any]:
    """Resolve only required full EKeys against official CDN indexes, never archive payloads."""
    needed_count = db.execute("SELECT COUNT(*) FROM consumer_needed").fetchone()[0]
    bloom = _build_consumer_bloom(db)
    file_index_hash = _valid_hash(cdn.file_index, "configured CDN file index") if cdn.file_index else None
    ordered_hashes: list[tuple[str, bool]] = []
    seen: set[str] = set()
    for raw_hash in cdn.archives:
        digest = _valid_hash(raw_hash, "configured CDN archive index")
        if digest not in seen:
            seen.add(digest)
            ordered_hashes.append((digest, digest == file_index_hash))
    if file_index_hash is not None and file_index_hash not in seen:
        ordered_hashes.append((file_index_hash, True))
    if not ordered_hashes:
        raise AdmissionError("CDN config contains no archive or file indexes")

    index_records: list[dict[str, Any]] = []
    matched_entry_count = 0
    for index_number, (digest, is_file_index) in enumerate(ordered_hashes):
        path = stage_metadata / f"cdn-index-{digest}.index.part"
        raw, source_kind, raw_sha = _acquire_cdn_index(
            cache=cache,
            raw_cache_dir=raw_cache_dir,
            stage_path=path,
            digest=digest,
            file_index_hash=file_index_hash,
            timeout=timeout,
            budget=budget,
            ledger_events=ledger_events,
        )
        layout = _cdn_index_layout(raw, digest, file_index_hash)
        if bool(layout["file_index"]) != is_file_index:
            raise AdmissionError(f"CDN index role disagrees with config reference: {digest}")
        matches = 0
        for ekey, encoded_size, offset in _iter_cdn_index_entries(
            raw, digest, file_index_hash
        ):
            if not bloom.may_contain(ekey):
                continue
            expected = db.execute(
                "SELECT encoded_size FROM consumer_needed WHERE ekey=?", (ekey,)
            ).fetchone()
            if expected is None:
                continue
            if is_file_index:
                _record_consumer_source(
                    db,
                    ekey=ekey,
                    encoded_size=encoded_size,
                    source_kind="direct",
                    source_hash=ekey,
                    source_offset=0,
                )
            else:
                assert offset is not None
                _record_consumer_source(
                    db,
                    ekey=ekey,
                    encoded_size=encoded_size,
                    source_kind="range",
                    source_hash=bytes.fromhex(digest),
                    source_offset=offset,
                )
            matches += 1
        matched_entry_count += matches
        footer = layout["footer"]
        index_records.append(
            {
                "hash": digest,
                "role": "file-index" if is_file_index else "archive-index",
                "source_kind": source_kind,
                "size_bytes": len(raw),
                "sha256": raw_sha,
                "entry_count": footer.entry_count,
                "offset_bytes": footer.offset_bytes,
                "required_ekey_matches": matches,
            }
        )
        path.unlink(missing_ok=True)
        if index_number % 64 == 63:
            db.commit()
    db.commit()

    missing_download_sources = db.execute(
        "SELECT COUNT(*) FROM consumer_work AS w "
        "LEFT JOIN consumer_sources AS s USING(ekey) WHERE s.ekey IS NULL"
    ).fetchone()[0]
    if missing_download_sources:
        raise AdmissionError(
            f"{missing_download_sources} selected download EKeys have no CDN index source"
        )
    source_fingerprint = hashlib.sha256()
    for record in sorted(index_records, key=lambda item: item["hash"]):
        source_fingerprint.update(
            f"{record['hash']}\0{record['role']}\0{record['size_bytes']}\0{record['sha256']}\n".encode()
        )
    return {
        "index_count": len(index_records),
        "archive_index_count": sum(record["role"] == "archive-index" for record in index_records),
        "file_index_count": sum(record["role"] == "file-index" for record in index_records),
        "index_encoded_bytes": sum(record["size_bytes"] for record in index_records),
        "index_sha256_manifest": source_fingerprint.hexdigest(),
        "needed_full_ekey_count": needed_count,
        "index_match_count_including_alternatives": matched_entry_count,
        "index_sources": index_records,
    }


def _upsert_consumer_work_object(
    db: sqlite3.Connection,
    *,
    ekey: bytes,
    ckey: bytes,
    encoded_size: int,
    decoded_size: int,
) -> None:
    existing = db.execute(
        "SELECT ckey,encoded_size,decoded_size FROM consumer_work WHERE ekey=?", (ekey,)
    ).fetchone()
    value = (ckey, encoded_size, decoded_size)
    if existing is not None:
        if tuple(existing) != value:
            raise AdmissionError(f"full EKey {ekey.hex()} has conflicting CKey/size bindings")
        return
    db.execute(
        "INSERT INTO consumer_work(ekey,ckey,encoded_size,decoded_size) VALUES(?,?,?,?)",
        (ekey, *value),
    )


def _choose_consumer_candidates(db: sqlite3.Connection) -> dict[str, Any]:
    runtime_fingerprints: dict[str, Any] = {}
    runtime_rows = list(
        db.execute(
            "SELECT DISTINCT role,owner FROM consumer_candidates "
            "WHERE role!='loose' ORDER BY role,owner"
        )
    )
    for role, owner in runtime_rows:
        selected = db.execute(
            "SELECT c.ekey,c.ckey,c.encoded_size,c.decoded_size,s.source_kind,s.source_hash,s.source_offset "
            "FROM consumer_candidates AS c JOIN consumer_sources AS s USING(ekey) "
            "WHERE c.role=? AND c.owner=? AND c.encoded_size=s.encoded_size "
            "ORDER BY c.ekey LIMIT 1",
            (role, owner),
        ).fetchone()
        if selected is None:
            label = role if not owner else f"{role}:{owner}"
            raise AdmissionError(f"required runtime role has no resolved CDN source: {label}")
        ekey, ckey, encoded_size, decoded_size, source_kind, _source_hash, _source_offset = selected
        collision = db.execute(
            "SELECT COUNT(DISTINCT ckey) FROM encoding_ckeys WHERE ekey=?", (ekey,)
        ).fetchone()[0]
        if collision != 1 and not (collision == 0 and role in {"encoding", "install", "download", "size"}):
            raise AdmissionError(
                f"required runtime full EKey {bytes(ekey).hex()} maps to {collision} CKeys"
            )
        _upsert_consumer_work_object(
            db,
            ekey=bytes(ekey),
            ckey=bytes(ckey),
            encoded_size=encoded_size,
            decoded_size=decoded_size,
        )
        runtime_fingerprints[role] = {
            "owner": owner,
            "ekey": bytes(ekey).hex(),
            "ckey": bytes(ckey).hex(),
            "encoded_size": encoded_size,
            "decoded_size": decoded_size,
            "source_kind": source_kind,
        }

    loose_count = 0
    for (path,) in db.execute("SELECT DISTINCT path FROM consumer_loose ORDER BY path"):
        selected = db.execute(
            "SELECT l.ekey,l.ckey,l.encoded_size,l.decoded_size "
            "FROM consumer_loose AS l JOIN consumer_sources AS s USING(ekey) "
            "WHERE l.path=? AND l.encoded_size=s.encoded_size ORDER BY l.ekey LIMIT 1",
            (path,),
        ).fetchone()
        if selected is None:
            raise AdmissionError(f"selected loose file has no resolved CDN source: {path}")
        ekey, ckey, encoded_size, decoded_size = selected
        collision = db.execute(
            "SELECT COUNT(DISTINCT ckey) FROM encoding_ckeys WHERE ekey=?", (ekey,)
        ).fetchone()[0]
        if collision != 1:
            raise AdmissionError(
                f"loose-file full EKey {bytes(ekey).hex()} maps to {collision} CKeys"
            )
        _upsert_consumer_work_object(
            db,
            ekey=bytes(ekey),
            ckey=bytes(ckey),
            encoded_size=encoded_size,
            decoded_size=decoded_size,
        )
        db.execute(
            "INSERT INTO consumer_chosen_loose(path,ekey,ckey,encoded_size,decoded_size) "
            "VALUES(?,?,?,?,?)",
            (path, bytes(ekey), bytes(ckey), encoded_size, decoded_size),
        )
        loose_count += 1
    db.commit()
    return {"runtime_role_fingerprints": runtime_fingerprints, "loose_count": loose_count}


def _create_consumer_catalog(path: Path) -> sqlite3.Connection:
    if path.exists() or path.is_symlink():
        raise AdmissionError(f"refusing to overwrite consumer catalog database: {path}")
    db = sqlite3.connect(path)
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("PRAGMA cache_size=-8192")
    db.executescript(
        """
        CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE objects (
            ekey BLOB PRIMARY KEY CHECK(length(ekey)=16),
            ckey BLOB NOT NULL CHECK(length(ckey)=16),
            encoded_size INTEGER NOT NULL CHECK(encoded_size>0),
            decoded_size INTEGER NOT NULL CHECK(decoded_size>=0),
            source_kind TEXT NOT NULL CHECK(source_kind IN ('direct','range')),
            source_hash BLOB NOT NULL CHECK(length(source_hash)=16),
            source_offset INTEGER NOT NULL CHECK(source_offset>=0),
            source_length INTEGER NOT NULL CHECK(source_length=encoded_size),
            CHECK((source_kind='direct' AND source_hash=ekey AND source_offset=0) OR source_kind='range')
        ) WITHOUT ROWID;
        CREATE TABLE loose (
            path TEXT PRIMARY KEY,
            ekey BLOB NOT NULL CHECK(length(ekey)=16),
            ckey BLOB NOT NULL CHECK(length(ckey)=16),
            encoded_size INTEGER NOT NULL CHECK(encoded_size>0),
            decoded_size INTEGER NOT NULL CHECK(decoded_size>=0),
            FOREIGN KEY(ekey) REFERENCES objects(ekey)
        ) WITHOUT ROWID;
        """
    )
    return db


def _write_consumer_catalog(
    *,
    spool: sqlite3.Connection,
    path: Path,
    metadata: dict[str, Any],
) -> dict[str, Any]:
    database = _create_consumer_catalog(path)
    try:
        database.executemany(
            "INSERT INTO metadata(key,value) VALUES(?,?)",
            [
                (key, json.dumps(value, sort_keys=True, separators=(",", ":")))
                for key, value in sorted(metadata.items())
            ],
        )
        expected_object_count = spool.execute(
            "SELECT COUNT(*) FROM consumer_work"
        ).fetchone()[0]
        if expected_object_count > MAX_CONSUMER_CATALOG_OBJECTS:
            raise AdmissionError(f"consumer catalog object count outside bound: {expected_object_count}")
        cursor = spool.execute(
            "SELECT w.ekey,w.ckey,w.encoded_size,w.decoded_size,s.source_kind,s.source_hash,s.source_offset "
            "FROM consumer_work AS w JOIN consumer_sources AS s USING(ekey) "
            "WHERE w.encoded_size=s.encoded_size ORDER BY w.ekey"
        )
        batch: list[tuple[Any, ...]] = []
        for ekey, ckey, encoded_size, decoded_size, source_kind, source_hash, source_offset in cursor:
            batch.append(
                (
                    bytes(ekey),
                    bytes(ckey),
                    encoded_size,
                    decoded_size,
                    source_kind,
                    bytes(source_hash),
                    source_offset,
                    encoded_size,
                )
            )
            if len(batch) == _UPDATE_MANIFEST.SQL_BATCH_ROWS:
                database.executemany(
                    "INSERT INTO objects(ekey,ckey,encoded_size,decoded_size,source_kind,source_hash,source_offset,source_length) "
                    "VALUES(?,?,?,?,?,?,?,?)",
                    batch,
                )
                batch.clear()
        if batch:
            database.executemany(
                "INSERT INTO objects(ekey,ckey,encoded_size,decoded_size,source_kind,source_hash,source_offset,source_length) "
                "VALUES(?,?,?,?,?,?,?,?)",
                batch,
            )
        loose_rows = spool.execute(
            "SELECT path,ekey,ckey,encoded_size,decoded_size "
            "FROM consumer_chosen_loose ORDER BY path"
        )
        database.executemany(
            "INSERT INTO loose(path,ekey,ckey,encoded_size,decoded_size) VALUES(?,?,?,?,?)",
            [tuple(bytes(value) if isinstance(value, (bytes, bytearray, memoryview)) else value for value in row)
             for row in loose_rows],
        )
        database.commit()
        object_count = database.execute("SELECT COUNT(*) FROM objects").fetchone()[0]
        loose_count = database.execute("SELECT COUNT(*) FROM loose").fetchone()[0]
        if object_count != expected_object_count:
            raise AdmissionError(
                f"consumer catalog export lost objects: {object_count}!={expected_object_count}"
            )
        if loose_count != spool.execute(
            "SELECT COUNT(DISTINCT path) FROM consumer_loose"
        ).fetchone()[0]:
            raise AdmissionError("consumer catalog export lost selected loose files")
        database.close()
        size = path.stat().st_size
        if size <= 0 or size > MAX_CONSUMER_CATALOG_BYTES:
            raise AdmissionError(
                f"consumer SQLite catalog size outside bound: {size} bytes "
                f"(max {MAX_CONSUMER_CATALOG_BYTES})"
            )
        return {
            "path": path.name,
            "size_bytes": size,
            "sha256": _hash_file(path, "sha256"),
            "schema": "metadata(key TEXT PRIMARY KEY,value TEXT); objects(ekey BLOB16 PRIMARY KEY,ckey BLOB16,encoded_size,decoded_size,source_kind,source_hash BLOB16,source_offset,source_length); loose(path TEXT PRIMARY KEY,ekey BLOB16,ckey BLOB16,encoded_size,decoded_size)",
            "object_count": object_count,
            "loose_count": loose_count,
        }
    except Exception:
        database.close()
        raise


def extend_manifest_closure(
    *,
    output_dir: Path = DEFAULT_OUTPUT,
    cache_root: Path = DEFAULT_CACHE_ROOT,
    timeout: float = 15.0,
) -> dict[str, Any]:
    """Fetch/decode bounded encoding/download/size metadata and stream the closure to SQLite."""
    if not math.isfinite(timeout) or timeout <= 0 or timeout > 120:
        raise AdmissionError("timeout must be finite, positive, and at most 120 seconds")
    requested_output = output_dir.absolute()
    current = requested_output
    while current != current.parent:
        if current.is_symlink():
            raise AdmissionError(f"closure output path contains a symlink: {current}")
        current = current.parent
    output_dir = requested_output.resolve(strict=True)
    if output_dir != DEFAULT_OUTPUT.resolve(strict=True):
        raise AdmissionError(f"closure output must be the admitted external directory: {DEFAULT_OUTPUT}")
    required_children = {
        "admission.json",
        "install-selection.jsonl",
        "metadata-manifest.json",
        "metadata",
    }
    allowed_children = required_children | {"verified-raw"}
    child_names = {item.name for item in output_dir.iterdir()}
    if not required_children.issubset(child_names) or not child_names.issubset(allowed_children):
        raise AdmissionError("closure mode requires the admitted artifact set and optional verified-raw cache")
    for path in output_dir.iterdir():
        if path.is_symlink():
            raise AdmissionError(f"closure output contains a symlink: {path}")
    admission_path = output_dir / "admission.json"
    manifest_path = output_dir / "metadata-manifest.json"
    try:
        summary = json.loads(admission_path.read_text(encoding="utf-8"))
        metadata_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AdmissionError(f"prior admission report is unreadable: {exc}") from exc
    if (
        summary.get("schema") != "wowforever.arm64-build-admission"
        or summary.get("provenance", {}).get("product") != PRODUCT
        or summary.get("provenance", {}).get("build_id") != EXPECTED_BUILD_ID
        or summary.get("provenance", {}).get("version") != EXPECTED_VERSION
        or metadata_manifest.get("schema")
        != "wowforever.arm64-build-admission.metadata-manifest"
    ):
        raise AdmissionError("closure input is not an admitted 70425 metadata generation")

    prior_files = list(output_dir.rglob("*"))
    if any(path.is_symlink() for path in prior_files):
        raise AdmissionError("closure input tree contains symlinks")
    raw_cache_dir = output_dir / "verified-raw"
    transfer_events = _read_transfer_ledger(raw_cache_dir)
    prior_tree_bytes = _output_tree_bytes(output_dir)
    prior_network_bytes = int(summary["network_and_bounds"]["network_bytes_received"])
    summarized_ledger_sequence = int(summary["network_and_bounds"].get("network_ledger_sequence", 0))
    unaccounted_events, unaccounted_transfer_bytes = _transfer_ledger_delta(
        transfer_events, summarized_ledger_sequence
    )
    effective_prior_network_bytes = prior_network_bytes + unaccounted_transfer_bytes
    prior_version_path = _safe_output_file(output_dir, "metadata/official-versions.tsv")
    prior_official = parse_official_versions(prior_version_path.read_bytes())
    if (
        prior_official.build_config_hash != summary["provenance"].get("build_config_hash")
        or prior_official.cdn_config_hash != summary["provenance"].get("cdn_config_hash")
    ):
        raise AdmissionError("saved official versions table disagrees with admission provenance")
    prior_build_path = _safe_output_file(
        output_dir,
        f"metadata/build-config-{prior_official.build_config_hash}.txt",
    )
    prior_cdn_path = _safe_output_file(
        output_dir,
        f"metadata/cdn-config-{prior_official.cdn_config_hash}.txt",
    )
    prior_build_raw = prior_build_path.read_bytes()
    prior_cdn_raw = prior_cdn_path.read_bytes()
    prior_build, _prior_cdn, _prior_install = _parse_hash_bound_configs(
        prior_official, prior_build_raw, prior_cdn_raw
    )
    preliminary_references = _closure_metadata_references(prior_build)
    cache = _readonly_cache(cache_root)
    preliminary_network = sum(
        info.size
        for role, info in preliminary_references.items()
        if not _verified_raw_is_available(cache, raw_cache_dir, role, info)
    )
    if (
        effective_prior_network_bytes > MAX_TOTAL_RESPONSE_BYTES
        or preliminary_network > MAX_TOTAL_RESPONSE_BYTES - effective_prior_network_bytes
    ):
        raise AdmissionError(
            f"cumulative network response budget would exceed {MAX_TOTAL_RESPONSE_BYTES} bytes; "
            "refusing before any metadata request"
        )
    budget = NetworkBudget(limit_bytes=MAX_TOTAL_RESPONSE_BYTES - effective_prior_network_bytes)

    with tempfile.TemporaryDirectory(prefix=".closure-stage-", dir=output_dir) as temporary:
        stage = Path(temporary)
        stage_metadata = stage / "metadata"
        stage_metadata.mkdir()
        official = prior_official
        old_provenance = summary["provenance"]
        if (
            official.build_config_hash != old_provenance.get("build_config_hash")
            or official.cdn_config_hash != old_provenance.get("cdn_config_hash")
        ):
            raise AdmissionError("official 70425 config hashes changed since the admission artifact")
        versions_artifact = next(
            (item for item in summary["artifacts"] if item.get("role") == "official-versions-table"),
            None,
        )
        if versions_artifact is None:
            raise AdmissionError("admission summary lacks official versions table artifact provenance")

        build_path = _safe_output_file(
            output_dir,
            f"metadata/build-config-{official.build_config_hash}.txt",
        )
        cdn_path = _safe_output_file(
            output_dir,
            f"metadata/cdn-config-{official.cdn_config_hash}.txt",
        )
        build_raw = build_path.read_bytes()
        cdn_raw = cdn_path.read_bytes()
        build, cdn, install_info = _parse_hash_bound_configs(official, build_raw, cdn_raw)
        references = _closure_metadata_references(build)
        all_references = _all_closure_metadata_references(install_info, references)

        install_raw_path = _safe_output_file(
            output_dir,
            f"metadata/install-{install_info.encoding_key.lower()}.blte",
        )
        install_decoded_path = stage / "install-verified.manifest"
        install_source = install_raw_path.open("rb")
        with install_source, install_decoded_path.open("xb") as decoded_file:
            try:
                install_decode = stream_decode_blte(
                    install_source,
                    decoded_file,
                    expected_ekey=install_info.encoding_key.lower(),
                    encoded_size=install_info.size,
                )
            except Exception as exc:
                raise AdmissionError(f"revalidated install BLTE/EKey verification failed: {exc}") from exc
        if install_decode["decoded_md5"] != install_info.content_key.lower():
            raise AdmissionError("revalidated install metadata CKey mismatch")
        install_decode["decoded_sha256"] = _hash_file(install_decoded_path, "sha256")
        existing_decoded_path = _safe_output_file(
            output_dir,
            f"metadata/install-{install_info.content_key.lower()}.manifest",
        )
        if (
            _hash_file(existing_decoded_path, "sha256") != install_decode["decoded_sha256"]
            or existing_decoded_path.stat().st_size != install_decode["decoded_size"]
        ):
            raise AdmissionError("saved decoded install manifest differs from verified BLTE decode")
        _, install_entry_count = _preflight_install(install_decoded_path)
        try:
            with install_decoded_path.open("rb") as stream:
                install_file = InstallParser().parse(stream)
                if stream.read(1):
                    raise AdmissionError("revalidated install manifest has trailing bytes")
        except AdmissionError:
            raise
        except Exception as exc:
            raise AdmissionError(f"could not reparse install manifest before closure: {exc}") from exc
        if len(install_file.entries) != install_entry_count:
            raise AdmissionError("revalidated install parser count disagrees with header")
        install_records, _executable = select_install_records(install_file)
        raw_install_record = _artifact(
            "install-manifest-encoded-object",
            install_raw_path,
            source_kind="official-https-or-verified-cache",
            source="revalidated external metadata artifact",
            url=_data_url(install_info.encoding_key),
        )
        fresh_selection_path = stage / "install-selection.jsonl"
        fresh_selection = _write_selection(
            fresh_selection_path,
            official=official,
            install_info=install_info,
            raw_artifact=raw_install_record,
            decoded_evidence=install_decode,
            tags=install_file.tags,
            all_entry_count=len(install_file.entries),
            records=install_records,
        )
        old_selection = summary["manifest_selection"]["selection_artifact"]
        if fresh_selection["sha256"] != old_selection.get("sha256"):
            raise AdmissionError("reparsed install selection differs from the original admission")
        fresh_selection_path.unlink()

        planned_raw_storage_bytes = sum(
            info.size
            for role, info in references.items()
            if not _verified_raw_path(raw_cache_dir, role, info.encoding_key).is_file()
        )
        decoded_budget_limit = min(
            MAX_DECODED_CLOSURE_BYTES,
            MAX_TOTAL_CLOSURE_ARTIFACT_BYTES
            - prior_tree_bytes
            - planned_raw_storage_bytes
            - MAX_PERSISTED_CDN_INDEX_BYTES
            - MAX_CONSUMER_CATALOG_BYTES
            - MAX_CLOSURE_REPORT_BYTES
        )
        if decoded_budget_limit <= 0:
            raise AdmissionError(
                "no space remains within the 1.5 GiB closure artifact bound for decoded metadata"
            )

        raw_artifacts: dict[str, dict[str, Any]] = {}
        raw_cache_before_bytes = _directory_file_bytes(raw_cache_dir)
        for role, info in references.items():
            scratch_path = stage_metadata / f"{role}-{info.encoding_key.lower()}.blte.part"

            def record_transfer(
                got_url: str,
                status: int,
                received: int,
                response_path: Path,
                *,
                transfer_role: str = role,
                transfer_ekey: str = info.encoding_key,
                transfer_size: int = info.size,
            ) -> None:
                _append_transfer_event(
                    raw_cache_dir,
                    transfer_events,
                    role=transfer_role,
                    ekey=transfer_ekey,
                    url=got_url,
                    status=status,
                    received=received,
                    expected_size=transfer_size,
                    response_path=response_path,
                )

            raw_artifacts[role] = _acquire_verified_closure_object(
                cache=cache,
                raw_cache_dir=raw_cache_dir,
                scratch_path=scratch_path,
                digest=info.encoding_key,
                url=_data_url(info.encoding_key),
                role=role,
                expected_size=info.size,
                timeout=timeout,
                budget=budget,
                on_response=record_transfer,
            )
        if effective_prior_network_bytes + budget.response_bytes > MAX_TOTAL_RESPONSE_BYTES:
            raise AdmissionError(
                f"cumulative network response budget exceeded {MAX_TOTAL_RESPONSE_BYTES} bytes"
            )

        decoded_artifacts: dict[str, dict[str, Any]] = {}
        decoded_metadata: dict[str, dict[str, Any]] = {}
        decoded_paths: dict[str, Path] = {}
        aggregate_decoded = [0]
        for role, info in references.items():
            decoded_path = stage_metadata / f"{role}-{info.content_key.lower()}.manifest"
            decoded_paths[role] = decoded_path
            raw_path = _verified_raw_path(raw_cache_dir, role, info.encoding_key)
            with raw_path.open("rb") as source, decoded_path.open("xb") as output:
                limited_output = _LimitedWriter(
                    output,
                    MAX_METADATA_OBJECT_BYTES,
                    aggregate=aggregate_decoded,
                    aggregate_limit=decoded_budget_limit,
                )
                try:
                    evidence = stream_decode_blte(
                        source,
                        limited_output,
                        expected_ekey=info.encoding_key.lower(),
                        encoded_size=info.size,
                    )
                except Exception as exc:
                    raise AdmissionError(
                        f"{role} metadata BLTE/EKey verification failed: {exc}"
                    ) from exc
            if evidence["decoded_md5"] != info.content_key.lower():
                raise AdmissionError(
                    f"{role} decoded CKey mismatch: {evidence['decoded_md5']} "
                    f"!= {info.content_key.lower()}"
                )
            evidence["decoded_sha256"] = _hash_file(decoded_path, "sha256")
            decoded_metadata[role] = evidence
            decoded_artifacts[role] = _artifact(
                f"{role}-manifest-decoded",
                decoded_path,
                source_kind="verified-decode",
                source=raw_artifacts[role]["path"],
                url=raw_artifacts[role]["url"],
            )

        database_path = stage / "spool.sqlite3"
        db = sqlite3.connect(database_path)
        db.execute(f"PRAGMA cache_size=-{_UPDATE_MANIFEST.SQLITE_CACHE_BYTES // 1024}")
        db.execute("PRAGMA temp_store=FILE")
        db.execute("PRAGMA journal_mode=OFF")
        db.execute("PRAGMA synchronous=OFF")
        _UPDATE_MANIFEST._create_spool_tables(db)
        try:
            parsed = _parse_closure_manifests(
                decoded_paths["download"], decoded_paths["size"], decoded_paths["encoding"], db
            )
            download_stats = parsed["download_stats"]
            download_tags = parsed["download_tags"]
            download_batch = parsed["download_batch"]
            size_stats = parsed["size_stats"]
            size_tags = parsed["size_tags"]
            size_batch = parsed["size_batch"]
            encoding_stats = parsed["encoding_stats"]
            _validate_selection_tags(download_tags, "download")
            _validate_selection_tags(size_tags, "size")
            duplicate_sizes = db.execute(
                "SELECT COUNT(*) FROM (SELECT ekey FROM download_entries WHERE selected=1 "
                "GROUP BY ekey HAVING MIN(encoded_size) != MAX(encoded_size))"
            ).fetchone()[0]
            if duplicate_sizes:
                raise AdmissionError(f"selected download EKeys have conflicting sizes: {duplicate_sizes}")
            missing_ekeys, size_mismatches = db.execute(
                "SELECT "
                "SUM(CASE WHEN m.ekey IS NULL THEN 1 ELSE 0 END), "
                "SUM(CASE WHEN m.ekey IS NOT NULL AND m.encoded_size != d.encoded_size THEN 1 ELSE 0 END) "
                "FROM (SELECT ekey,MIN(encoded_size) AS encoded_size FROM download_entries "
                "WHERE selected=1 GROUP BY ekey) AS d LEFT JOIN encoding_ekeys AS m ON m.ekey=d.ekey"
            ).fetchone()
            if missing_ekeys:
                raise AdmissionError(
                    f"{missing_ekeys} selected download EKeys are absent from encoding pages"
                )
            if size_mismatches:
                raise AdmissionError(
                    f"{size_mismatches} selected download EKeys disagree with encoding sizes"
                )
            unmapped_ekeys = db.execute(
                "SELECT COUNT(*) FROM (SELECT DISTINCT d.ekey FROM download_entries d "
                "LEFT JOIN encoding_ckeys c ON c.ekey=d.ekey "
                "WHERE d.selected=1 AND c.ekey IS NULL)"
            ).fetchone()[0]
            if unmapped_ekeys:
                raise AdmissionError(
                    f"{unmapped_ekeys} selected download EKeys lack CKey mappings"
                )
            _create_consumer_work_tables(db)
            consumer_selection = _populate_consumer_work(
                db,
                build=build,
                references=all_references,
                metadata_decoded_sizes={
                    "install": install_decode["decoded_size"],
                    **{
                        role: int(evidence["decoded_size"])
                        for role, evidence in decoded_metadata.items()
                    },
                },
                install_records=install_records,
                download_stats=download_stats,
            )
            cdn_sources = _scan_cdn_sources(
                db=db,
                cdn=cdn,
                cache=cache,
                raw_cache_dir=raw_cache_dir,
                stage_metadata=stage_metadata,
                timeout=timeout,
                budget=budget,
                ledger_events=transfer_events,
            )
            chosen = _choose_consumer_candidates(db)
            source_unresolved = db.execute(
                "SELECT COUNT(*) FROM consumer_work AS w "
                "LEFT JOIN consumer_sources AS s USING(ekey) "
                "WHERE s.ekey IS NULL OR w.encoded_size!=s.encoded_size"
            ).fetchone()[0]
            if source_unresolved:
                raise AdmissionError(
                    f"{source_unresolved} required consumer objects have unresolved CDN sources"
                )
            install_provenance_count = chosen["loose_count"]
            if install_provenance_count != len(install_records):
                raise AdmissionError("selected install CKey/EKey/source count mismatch")
            consumer_object_count = db.execute(
                "SELECT COUNT(*) FROM consumer_work"
            ).fetchone()[0]
            if consumer_object_count != consumer_selection["selected_download_unique_ekeys"] + db.execute(
                "SELECT COUNT(*) FROM consumer_work AS w "
                "WHERE NOT EXISTS (SELECT 1 FROM download_entries AS d "
                "WHERE d.selected=1 AND d.ekey=w.ekey)"
            ).fetchone()[0]:
                raise AdmissionError("consumer object union count is inconsistent")
        except AdmissionError:
            raise
        except Exception as exc:
            gate_error = getattr(_UPDATE_MANIFEST, "GateError", ValueError)
            if isinstance(exc, gate_error):
                raise AdmissionError(f"bounded 70425 manifest closure failed: {exc}") from exc
            raise
        finally:
            db.close()

        raw_closure_artifacts = list(raw_artifacts.values())
        decoded_closure_artifacts = list(decoded_artifacts.values())
        closure_raw_artifacts = {"install": raw_install_record, **raw_artifacts}
        closure_decoded_evidence = {"install": install_decode, **decoded_metadata}
        closure_metadata_objects = _closure_metadata_records(
            all_references, closure_raw_artifacts, closure_decoded_evidence
        )
        catalog_path = stage / "client-catalog.sqlite3"
        export_spool = sqlite3.connect(database_path)
        try:
            consumer_catalog = _write_consumer_catalog(
                spool=export_spool,
                path=catalog_path,
                metadata={
                    "schema": "wowforever.arm64-client-catalog.sqlite",
                    "schema_version": 1,
                    "product": PRODUCT,
                    "region": REGION,
                    "build_id": official.build_id,
                    "version": official.version,
                    "platform": PLATFORM,
                    "architecture": ARCHITECTURE,
                    "locale": LOCALE,
                    "build_config_hash": official.build_config_hash,
                    "cdn_config_hash": official.cdn_config_hash,
                    "official_versions_sha256": official.feed_sha256,
                    "selection_query": SELECTION_QUERY,
                    "install_manifest_sha256": install_decode["decoded_sha256"],
                    "download_manifest_sha256": decoded_metadata["download"]["decoded_sha256"],
                    "size_manifest_sha256": decoded_metadata["size"]["decoded_sha256"],
                    "encoding_manifest_sha256": decoded_metadata["encoding"]["decoded_sha256"],
                    "metadata_objects": closure_metadata_objects,
                    "required_runtime_roles": consumer_selection["required_runtime_roles"],
                    "runtime_role_fingerprints": chosen["runtime_role_fingerprints"],
                    "cdn_index_count": cdn_sources["index_count"],
                    "cdn_index_sha256_manifest": cdn_sources["index_sha256_manifest"],
                    "cdn_file_index_hash": cdn.file_index,
                    "download_selected_entry_count": download_stats.selected_count,
                    "download_selected_unique_ekey_count": consumer_selection[
                        "selected_download_unique_ekeys"
                    ],
                    "download_selected_unique_encoded_bytes": consumer_selection[
                        "selected_download_unique_encoded_bytes"
                    ],
                    "size_selected_entry_count": size_stats.selected_count,
                    "size_selected_estimated_bytes": size_stats.selected_bytes,
                    "selected_loose_file_count": chosen["loose_count"],
                    "source_unresolved_count": 0,
                    "payloads_acquired_or_verified": False,
                    "source_boundary": (
                        "All catalog EKeys have a configured CDN file-index direct source or "
                        "archive-index range binding; payload bytes were not fetched or probed."
                    ),
                    "server_compatibility": summary["server_compatibility"],
                },
            )
        finally:
            export_spool.close()
        catalog_artifact = {
            "role": "android-consumer-client-catalog",
            "path": consumer_catalog["path"],
            "source_kind": "verified-manifest-and-index-derived-sqlite",
            "source": "full EKey/CKey closure, CDN source indexes, and selected ARM64 loose manifest",
            "url": None,
            "size_bytes": consumer_catalog["size_bytes"],
            "sha256": consumer_catalog["sha256"],
        }
        closure_status = "consumer-catalog-ready"
        closure: dict[str, Any] = {
            "schema": "wowforever.arm64-build-admission.manifest-closure",
            "schema_version": 1,
            "status": closure_status,
            "provenance": {
                "product": PRODUCT,
                "region": REGION,
                "build_id": official.build_id,
                "version": official.version,
                "build_config_hash": official.build_config_hash,
                "cdn_config_hash": official.cdn_config_hash,
                "official_versions_sha256": official.feed_sha256,
                "official_versions_feed_reused_from_verified_admission": True,
            },
            "metadata_objects": closure_metadata_objects,
            "install_manifest_revalidation": {
                "content_key": install_info.content_key.lower(),
                "encoding_key": install_info.encoding_key.lower(),
                "selected_entry_count": len(install_records),
                "selection_sha256_reproduced": fresh_selection["sha256"],
                "selected_entries_cross_checked_against_encoding": install_provenance_count,
            },
            "consumer_catalog": {
                **consumer_catalog,
                "schema": "metadata(key TEXT PRIMARY KEY,value TEXT); objects(ekey BLOB16 PRIMARY KEY,ckey BLOB16,encoded_size,decoded_size,source_kind,source_hash BLOB16,source_offset,source_length); loose(path TEXT PRIMARY KEY,ekey BLOB16,ckey BLOB16,encoded_size,decoded_size)",
                "required_runtime_roles": consumer_selection["required_runtime_roles"],
                "required_runtime_role_fingerprints": chosen["runtime_role_fingerprints"],
                "source_unresolved_count": 0,
                "payloads_acquired_or_probed": False,
                "source_index_fingerprint": cdn_sources["index_sha256_manifest"],
            },
            "download_manifest": {
                "entry_count": download_stats.entry_count,
                "selected_entry_count": download_stats.selected_count,
                "selected_unique_full_ekey_count": download_stats.selected_ekey_count,
                "selected_entry_encoded_bytes_including_repeated_entries": download_stats.selected_bytes,
                "selected_unique_ekey_encoded_bytes": consumer_selection[
                    "selected_download_unique_encoded_bytes"
                ],
                "all_entry_encoded_bytes": download_stats.total_bytes,
                "tag_count": download_stats.tag_count,
                "selected_ekeys_missing_from_encoding": missing_ekeys,
                "selected_ekey_size_mismatches": size_mismatches,
                "selected_ekeys_without_content_mapping": unmapped_ekeys,
                "closure_record_count": consumer_selection[
                    "selected_download_unique_ekeys"
                ],
                "content_mapping_complete": unmapped_ekeys == 0,
                "source_unresolved_count": 0,
            },
            "size_manifest": {
                "entry_count": size_stats.entry_count,
                "selected_entry_count": size_stats.selected_count,
                "selected_estimated_bytes": size_stats.selected_bytes,
                "declared_all_entry_bytes": size_stats.total_bytes,
                "tag_count": size_stats.tag_count,
                "partial_ekey_prefix_bytes": 9,
                "prefix_used_as_object_identity": False,
                "selected_estimate_records": size_stats.selected_count,
            },
            "cdn_source_resolution": {
                "index_count": cdn_sources["index_count"],
                "archive_index_count": cdn_sources["archive_index_count"],
                "file_index_count": cdn_sources["file_index_count"],
                "total_index_bytes_read": cdn_sources["index_encoded_bytes"],
                "source_index_manifest_sha256": cdn_sources["index_sha256_manifest"],
                "needed_full_ekey_count": cdn_sources["needed_full_ekey_count"],
                "matched_index_records_including_alternatives": cdn_sources[
                    "index_match_count_including_alternatives"
                ],
                "unresolved_catalog_sources": 0,
                "payloads_or_archives_downloaded": False,
                "index_sources": cdn_sources["index_sources"],
            },
            "encoding": {
                "ckey_record_count": encoding_stats.ckey_count,
                "ekey_record_count": encoding_stats.ekey_count,
                "ckey_ekey_mapping_count": encoding_stats.ckey_mapping_count,
                "ckey_page_count": encoding_stats.ckey_page_count,
                "ekey_page_count": encoding_stats.ekey_page_count,
                "max_page_bytes": encoding_stats.max_page_bytes,
                "trailing_bytes_after_declared_pages": encoding_stats.trailing_bytes,
                "trailing_bytes_sha256": encoding_stats.trailing_sha256,
            },
            "selected_install_encoding_records_cross_checked": install_provenance_count,
            "verification_boundary": {
                "download_payloads_acquired": False,
                "full_game_payloads_or_archives_acquired": False,
                "selected_download_metadata_bound_to_encoding_pages": True,
                "unmapped_download_content_key_count": unmapped_ekeys,
                "all_selected_install_records_mapped_to_encoding_pages": True,
                "encoding_install_download_size_objects": "Each metadata object is independently BLTE EKey/CKey/size verified against its build-config reference; the encoding metadata object cannot self-authenticate against its own pages.",
                "unsupported_metadata_blte_modes": "fail-closed; only N/Z supported by the reused decoder",
            },
            "network_and_bounds": {
                "new_response_bytes": budget.response_bytes,
                "prior_unaccounted_transfer_ledger_bytes": unaccounted_transfer_bytes,
                "cumulative_response_bytes_including_admission": (
                    effective_prior_network_bytes + budget.response_bytes
                ),
                "response_limit_bytes": MAX_TOTAL_RESPONSE_BYTES,
                "artifact_limit_bytes": MAX_TOTAL_CLOSURE_ARTIFACT_BYTES,
                "fetches": budget.fetches,
                "transfer_ledger_sequence": len(transfer_events),
                "each_encoded_object_limit_bytes": MAX_METADATA_OBJECT_BYTES,
                "decoded_object_limit_bytes": MAX_METADATA_OBJECT_BYTES,
                "report_output_limit_bytes": MAX_CLOSURE_REPORT_BYTES,
            },
            "memory_shape": {
                "download_and_size_entry_lists_retained": False,
                "encoding_record_lists_retained": False,
                "sqlite_cache_limit_bytes": _UPDATE_MANIFEST.SQLITE_CACHE_BYTES,
                "max_sql_batch_rows": max(
                    _UPDATE_MANIFEST.SQL_BATCH_ROWS, download_batch, size_batch
                ),
                "max_encoding_page_bytes": encoding_stats.max_page_bytes,
                "tag_masks_retained_concurrently_bytes": (
                    download_stats.tag_mask_bytes + size_stats.tag_mask_bytes
                ),
                "tag_bitmap_storage_bytes_peak": _UPDATE_MANIFEST.peak_tag_bitmap_storage_bytes(
                    download_stats, size_stats
                ),
                "consumer_ekey_bloom_bytes": consumer_selection["bloom_filter_bytes"],
                "temporary_sqlite_spool_removed": True,
            },
            "artifacts": [
                versions_artifact,
                *raw_closure_artifacts,
                *decoded_closure_artifacts,
                catalog_artifact,
            ],
        }
        closure_path = stage / "manifest-closure.json"
        closure_path.write_text(json.dumps(closure, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        closure_artifact = {
            "role": "manifest-closure-report",
            "path": closure_path.name,
            "source_kind": "verified-host-report",
            "source": "authenticated manifests, encoding pages, CDN indexes, and consumer SQLite catalog",
            "url": None,
            "size_bytes": closure_path.stat().st_size,
            "sha256": _hash_file(closure_path, "sha256"),
        }

        current_artifacts = list(summary["artifacts"])
        current_artifacts.extend(
            [*raw_closure_artifacts, *decoded_closure_artifacts, catalog_artifact, closure_artifact]
        )
        summary["artifacts"] = current_artifacts
        summary["manifest_closure"] = {
            "status": closure_status,
            "report": closure_path.name,
            "report_sha256": closure_artifact["sha256"],
            "consumer_catalog": catalog_artifact,
            "download_selected_entry_count": download_stats.selected_count,
            "size_selected_entry_count": size_stats.selected_count,
            "selected_install_record_count": len(install_records),
            "content_mapping_complete": unmapped_ekeys == 0,
            "source_unresolved_count": 0,
        }
        summary["client_catalog"] = {
            "status": "ready-for-downloader-source-planning",
            "artifact": catalog_artifact,
            "object_count": consumer_catalog["object_count"],
            "loose_file_count": consumer_catalog["loose_count"],
            "source_unresolved_count": 0,
            "payloads_acquired_or_probed": False,
            "pe_machine_observed": False,
            "server_login_or_gameplay_verified": False,
        }
        summary["network_and_bounds"]["network_bytes_received"] = (
            effective_prior_network_bytes + budget.response_bytes
        )
        summary["network_and_bounds"]["network_response_budget_bytes"] = MAX_TOTAL_RESPONSE_BYTES
        summary["network_and_bounds"]["metadata_artifact_budget_bytes"] = (
            MAX_TOTAL_CLOSURE_ARTIFACT_BYTES
        )
        summary["network_and_bounds"]["decoded_closure_budget_bytes"] = decoded_budget_limit
        summary["network_and_bounds"]["consumer_catalog_budget_bytes"] = (
            MAX_CONSUMER_CATALOG_BYTES
        )
        summary["network_and_bounds"]["cumulative_network_remaining_bytes"] = (
            MAX_TOTAL_RESPONSE_BYTES
            - effective_prior_network_bytes
            - budget.response_bytes
        )
        ledger_delta_events, ledger_delta_bytes = _transfer_ledger_delta(
            transfer_events, summarized_ledger_sequence
        )
        if ledger_delta_bytes != unaccounted_transfer_bytes + budget.response_bytes:
            raise AdmissionError("durable transfer ledger and in-memory network budget disagree")
        summary["network_and_bounds"]["network_ledger_sequence"] = len(transfer_events)
        summary["network_and_bounds"]["network_fetches"] = (
            summary["network_and_bounds"].get("network_fetches", [])
            + [
                {
                    "url": event["url"],
                    "http_status": event["http_status"],
                    "response_body_bytes": event["response_body_bytes"],
                    "role": event["role"],
                    "encoding_key": event.get("expected_ekey"),
                    "index_hash": event.get("expected_index_hash"),
                }
                for event in ledger_delta_events
            ]
        )
        summary["network_and_bounds"]["metadata_artifact_bytes_including_decoded_and_selection"] = sum(
            item["size_bytes"] for item in current_artifacts
        )
        summary["network_and_bounds"]["cdn_index_encoded_bytes_read"] = (
            cdn_sources["index_encoded_bytes"]
        )
        summary["network_and_bounds"]["consumer_catalog_bytes"] = consumer_catalog["size_bytes"]
        metadata_manifest["artifacts"] = [
            *metadata_manifest.get("artifacts", []),
            *raw_closure_artifacts,
            *decoded_closure_artifacts,
            catalog_artifact,
            closure_artifact,
        ]
        metadata_manifest["manifest_closure"] = summary["manifest_closure"]
        new_admission_path = stage / "admission.json"
        new_manifest_path = stage / "metadata-manifest.json"
        new_admission_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        new_manifest_path.write_text(
            json.dumps(metadata_manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

        staged_metadata_bytes = sum(path.stat().st_size for path in stage_metadata.iterdir() if path.is_file())
        raw_cache_delta_bytes = _directory_file_bytes(raw_cache_dir) - raw_cache_before_bytes
        staged_reports = [catalog_path, closure_path]
        staged_report_bytes = sum(path.stat().st_size for path in staged_reports)
        staged_summary_bytes = new_admission_path.stat().st_size + new_manifest_path.stat().st_size
        replaced_bytes = (
            admission_path.stat().st_size
            + manifest_path.stat().st_size
        )
        projected_total = (
            prior_tree_bytes
            - replaced_bytes
            + raw_cache_delta_bytes
            + staged_metadata_bytes
            + staged_report_bytes
            + staged_summary_bytes
        )
        if staged_report_bytes + staged_summary_bytes > MAX_CLOSURE_REPORT_BYTES + MAX_CONSUMER_CATALOG_BYTES:
            raise AdmissionError("consumer catalog, closure report, and summaries exceed their reserved bounds")
        if projected_total > MAX_TOTAL_CLOSURE_ARTIFACT_BYTES:
            raise AdmissionError(
                f"final metadata artifact tree would exceed {MAX_TOTAL_CLOSURE_ARTIFACT_BYTES} bytes: {projected_total}"
            )

        for staged in stage_metadata.iterdir():
            destination = output_dir / "metadata" / staged.name
            if staged.name != "official-versions.tsv" and destination.exists():
                raise AdmissionError(f"refusing to overwrite existing closure metadata: {destination}")
        for staged in stage_metadata.iterdir():
            os.replace(staged, output_dir / "metadata" / staged.name)
        for path in staged_reports:
            os.replace(path, output_dir / path.name)
        os.replace(new_manifest_path, manifest_path)
        os.replace(new_admission_path, admission_path)

    closure["report_path"] = str(output_dir / "manifest-closure.json")
    closure["consumer_catalog_path"] = str(output_dir / "client-catalog.sqlite3")
    closure["artifact_tree_bytes"] = _output_tree_bytes(output_dir)
    return closure


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--cache-root", type=Path, default=DEFAULT_CACHE_ROOT)
    parser.add_argument(
        "--extend-closure",
        action="store_true",
        help="complete bounded 70425 manifest closure and emit a source-resolved SQLite client catalog",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.extend_closure:
            summary = extend_manifest_closure(
                timeout=args.timeout,
                cache_root=args.cache_root,
            )
        else:
            summary = run_admission(timeout=args.timeout, cache_root=args.cache_root)
    except (AdmissionError, OSError, TimeoutError) as exc:
        print(json.dumps({"status": "blocked", "error": str(exc)}, sort_keys=True))
        return 2
    if args.extend_closure:
        result = {
            "status": summary["status"],
            "report": summary["report_path"],
            "build_id": summary["provenance"]["build_id"],
            "version": summary["provenance"]["version"],
            "download_selected_entries": summary["download_manifest"]["selected_entry_count"],
            "size_selected_entries": summary["size_manifest"]["selected_entry_count"],
            "catalog": summary["consumer_catalog_path"],
            "catalog_objects": summary["consumer_catalog"]["object_count"],
            "catalog_loose_files": summary["consumer_catalog"]["loose_count"],
            "source_unresolved": summary["consumer_catalog"]["source_unresolved_count"],
            "network_bytes": summary["network_and_bounds"]["cumulative_response_bytes_including_admission"],
        }
    else:
        result = {
            "status": summary["status"],
            "summary": summary["summary_path"],
            "build_id": summary["provenance"]["build_id"],
            "version": summary["provenance"]["version"],
            "build_config_hash": summary["provenance"]["build_config_hash"],
            "cdn_config_hash": summary["provenance"]["cdn_config_hash"],
            "selected_install_entries": summary["manifest_selection"]["selected_entry_count"],
            "executable": summary["manifest_selection"]["executable"]["relative_path"],
            "network_bytes": summary["network_and_bounds"]["network_bytes_received"],
        }
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
