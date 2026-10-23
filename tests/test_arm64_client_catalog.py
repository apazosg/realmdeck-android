"""Bounded, offline invariants for the ARM64 client catalog generator."""

from __future__ import annotations

import hashlib
import importlib.util
import struct
import sys
from copy import deepcopy
from pathlib import Path

import pytest
from cascette_tools.core.local_storage import LocalStorage, parse_local_idx_file
from cascette_tools.formats.blte import BLTEBuilder, CompressionMode, decompress_blte
from cascette_tools.formats.install import InstallEntry


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/arm64-prototype/client/catalog.py"
SPEC = importlib.util.spec_from_file_location("arm64_client_catalog", MODULE_PATH)
assert SPEC and SPEC.loader
catalog = importlib.util.module_from_spec(SPEC)
sys.modules["arm64_client_catalog"] = catalog
SPEC.loader.exec_module(catalog)
sys.modules["catalog"] = catalog


def _load_runtime_audit() -> object:
    path = ROOT / "scripts/arm64-prototype/client/runtime_audit.py"
    spec = importlib.util.spec_from_file_location("arm64_runtime_audit", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_source_audit() -> object:
    path = ROOT / "scripts/arm64-prototype/client/cdn_source_audit.py"
    spec = importlib.util.spec_from_file_location("arm64_cdn_source_audit", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _minimal_catalog() -> dict[str, object]:
    result: dict[str, object] = {
        "provenance": {
            "product": "wow_classic_beta",
            "platform": "Windows",
            "arch": "arm64",
            "locale": "enUS",
            "version": "1.60.1.70009",
            "build_config_hash": "0123456789abcdef0123456789abcdef",
            "cdn_config_hash": "fedcba9876543210fedcba9876543210",
            "official_cdn_host": catalog.CDN_HOST,
        },
        "loose_files": [
            {
                "relative_path": "WowB-ARM64.exe",
                "decoded_size": 4,
                "content_key": "0123456789abcdef0123456789abcdef",
            }
        ],
        "executable": {
            "relative_path": "WowB-ARM64.exe",
            "architecture": {"expected_machine": "0xaa64"},
        },
        "required_configs": [
            {"kind": "build", "hash": "0123456789abcdef0123456789abcdef", "relative_path": "Data/config/01/23/0123456789abcdef0123456789abcdef", "url": catalog.official_url(catalog.CDN_HOST, "config", "0123456789abcdef0123456789abcdef"), "status": "cached-and-parser-validated", "observed_encoded_size": 1, "sha256": "0" * 64},
            {"kind": "cdn", "hash": "fedcba9876543210fedcba9876543210", "relative_path": "Data/config/fe/dc/fedcba9876543210fedcba9876543210", "url": catalog.official_url(catalog.CDN_HOST, "config", "fedcba9876543210fedcba9876543210"), "status": "cached-and-parser-validated", "observed_encoded_size": 1, "sha256": "1" * 64},
        ],
        "casc_bootstrap": {
            "objects": [
                {"content_key": "0123456789abcdef0123456789abcdef", "roles": ["root"], "decoded_size": 1, "encoded_size": 1, "decoded_md5": "0123456789abcdef0123456789abcdef", "verification": "fully-verified", "sources": [{"kind": "direct", "ekey": "0123456789abcdef0123456789abcdef", "sha256": "0" * 64, "decoded_size": 1, "decoded_md5": "0123456789abcdef0123456789abcdef"}]},
                {"content_key": "1123456789abcdef0123456789abcdef", "roles": ["encoding"], "decoded_size": 1, "encoded_size": 1, "decoded_md5": "1123456789abcdef0123456789abcdef", "verification": "fully-verified", "sources": [{"kind": "direct", "ekey": "1123456789abcdef0123456789abcdef", "sha256": "0" * 64, "decoded_size": 1, "decoded_md5": "1123456789abcdef0123456789abcdef"}]},
                {"content_key": "2123456789abcdef0123456789abcdef", "roles": ["install"], "decoded_size": 1, "encoded_size": 1, "decoded_md5": "2123456789abcdef0123456789abcdef", "verification": "fully-verified", "sources": [{"kind": "direct", "ekey": "2123456789abcdef0123456789abcdef", "sha256": "0" * 64, "decoded_size": 1, "decoded_md5": "2123456789abcdef0123456789abcdef"}]},
                {"content_key": "3123456789abcdef0123456789abcdef", "roles": ["vfs-root"], "decoded_size": 1, "encoded_size": 1, "decoded_md5": "3123456789abcdef0123456789abcdef", "verification": "fully-verified", "sources": [{"kind": "direct", "ekey": "3123456789abcdef0123456789abcdef", "sha256": "0" * 64, "decoded_size": 1, "decoded_md5": "3123456789abcdef0123456789abcdef"}]},
                {"content_key": "4123456789abcdef0123456789abcdef", "roles": ["download"], "decoded_size": 1, "encoded_size": 1, "decoded_md5": "4123456789abcdef0123456789abcdef", "verification": "fully-verified", "sources": [{"kind": "direct", "ekey": "4123456789abcdef0123456789abcdef", "sha256": "0" * 64, "decoded_size": 1, "decoded_md5": "4123456789abcdef0123456789abcdef"}]},
                {"content_key": "5123456789abcdef0123456789abcdef", "roles": ["size"], "decoded_size": 1, "encoded_size": 1, "decoded_md5": "5123456789abcdef0123456789abcdef", "verification": "fully-verified", "sources": [{"kind": "direct", "ekey": "5123456789abcdef0123456789abcdef", "sha256": "0" * 64, "decoded_size": 1, "decoded_md5": "5123456789abcdef0123456789abcdef"}]},
            ],
            "cdn_indices": [],
        },
        "build_metadata": {"unresolved_references": []},
        "build_info": {
            "fields": {
                "branch": "us",
                "build_key": "0123456789abcdef0123456789abcdef",
                "cdn_key": "fedcba9876543210fedcba9876543210",
                "cdn_path": "tpr/wow",
                "cdn_hosts": ["level3.blizzard.com", "us.cdn.blizzard.com"],
                "version": "1.60.1.70009",
                "product": "wow_classic_beta",
                "platform": "Windows",
                "architecture": "arm64",
                "locale": "enUS",
                "region": "us",
                "install_key": "0123456789abcdef0123456789abcdef",
                "im_size": None,
            },
        },
        "runtime_metadata": {"resolved_and_verified": True},
        "bounds": {"android_initial_transfer_bytes": 1},
        "completeness": {"status": "finite-bootstrap", "launch_ready": True},
    }
    loose = result["loose_files"][0]  # type: ignore[index]
    loose["encoded_size"] = 1
    loose["decoded_md5"] = loose["content_key"]
    loose["encoding_keys"] = [loose["content_key"]]
    loose["status"] = "direct-object"
    loose["sources"] = [{
        "kind": "direct",
        "ekey": loose["content_key"],
        "url": catalog.official_url(catalog.CDN_HOST, "data", loose["content_key"]),
        "encoded_size": 1,
        "sha256": "0" * 64,
        "decoded_size": loose["decoded_size"],
        "decoded_md5": loose["content_key"],
    }]
    result["casc_bootstrap"]["cdn_indices"].append({  # type: ignore[index]
        "hash": "abcdef0123456789abcdef0123456789",
        "relative_path": "Data/indices/abcdef0123456789abcdef0123456789.index",
        "url": catalog.official_url(catalog.CDN_HOST, "index", "abcdef0123456789abcdef0123456789", ".index"),
        "status": "cached-and-parser-validated",
        "observed_encoded_size": 1,
        "sha256": "0" * 64,
        "present_sha256": "0" * 64,
    })
    for item in result["casc_bootstrap"]["objects"]:  # type: ignore[index]
        item["encoding_keys"] = [item["sources"][0]["ekey"]]
        item["blte_flags"] = None
        item["blte_modes"] = ["N"]
        item["sources"][0]["encoded_size"] = item["encoded_size"]
        item["sources"][0]["blte_flags"] = None
        item["sources"][0]["blte_modes"] = ["N"]
        item["sources"][0]["url"] = catalog.official_url(catalog.CDN_HOST, "data", item["sources"][0]["ekey"])
        item["sources"][0]["raw_sha256"] = item["sources"][0]["sha256"]
    fields = result["build_info"]["fields"]  # type: ignore[index]
    raw = catalog.render_build_info(fields)  # type: ignore[arg-type]
    result["build_info"]["serialized_size"] = len(raw)  # type: ignore[index]
    result["build_info"]["serialized_sha256"] = hashlib.sha256(raw).hexdigest()  # type: ignore[index]
    return result


def test_negative_traversal_and_absolute_paths() -> None:
    for value in ("../WowB.exe", r"Utils\..\WowB.exe", "/tmp/WowB.exe", "C:/WowB.exe", "Utils//x.dll"):
        with pytest.raises(catalog.CatalogError):
            catalog.ensure_safe_relative_path(value)


def test_duplicate_normalized_paths_are_rejected() -> None:
    entries = [
        InstallEntry(filename=r"UTILS\x.dll", md5_hash=b"0" * 16, size=1),
        InstallEntry(filename=r"UTILS/x.dll", md5_hash=b"1" * 16, size=1),
    ]
    with pytest.raises(catalog.CatalogError, match="duplicate"):
        catalog._canonical_install_paths(entries)


def test_wrong_architecture_is_rejected_without_execution(tmp_path: Path) -> None:
    path = tmp_path / "wrong.exe"
    dos = bytearray(64)
    dos[:2] = b"MZ"
    struct.pack_into("<I", dos, 0x3C, 64)
    path.write_bytes(bytes(dos) + b"PE\0\0" + struct.pack("<H", 0x8664) + b"\0" * 18)
    identity = catalog.inspect_pe(path)
    assert identity["machine_name"] == "x86-64"
    assert not identity["is_arm64"]
    with pytest.raises(catalog.CatalogError, match="ARM64"):
        real = _minimal_catalog()
        real["executable"]["architecture"] = {"expected_machine": "0x8664"}  # type: ignore[index]
        catalog.validate_catalog(real)  # type: ignore[arg-type]


def test_build_architecture_and_manifest_identity_are_required() -> None:
    good = _minimal_catalog()
    catalog.validate_catalog(good)
    bad = _minimal_catalog()
    bad["provenance"]["arch"] = "x86_64"  # type: ignore[index]
    with pytest.raises(catalog.CatalogError, match="ARM64"):
        catalog.validate_catalog(bad)  # type: ignore[arg-type]


def test_catalog_rejects_non_hex_loose_key_and_unapproved_cdn_host() -> None:
    bad_key = _minimal_catalog()
    bad_key["loose_files"][0]["content_key"] = "g" * 32  # type: ignore[index]
    with pytest.raises(catalog.CatalogError, match="invalid loose-file record"):
        catalog.validate_catalog(bad_key)  # type: ignore[arg-type]
    for host in ("https://cdn.example.invalid", "http://us.cdn.blizzard.com", "https://us.cdn.blizzard.com.evil.invalid"):
        bad_host = _minimal_catalog()
        bad_host["provenance"]["official_cdn_host"] = host  # type: ignore[index]
        with pytest.raises(catalog.CatalogError, match="official HTTPS allowlist"):
            catalog.validate_catalog(bad_host)  # type: ignore[arg-type]


def test_runtime_record_uses_decoded_size_not_declared_vfs_encoded_size() -> None:
    content = b"decoded-vfs-content"
    raw = BLTEBuilder().build(BLTEBuilder.create_single_chunk(content, CompressionMode.ZLIB))
    ekey = bytes.fromhex("abcdef0123456789abcdef0123456789")

    class Source:
        def get(self, digest: str, kind: str = "data") -> bytes | None:
            return raw if digest == ekey.hex() else None

    record = catalog._ref_record(
        role="vfs-782",
        content_key=hashlib.md5(content).hexdigest(),
        decoded_size=len(content),
        declared_encoded_size=len(raw),
        ekeys=[ekey],
        source=Source(),  # type: ignore[arg-type]
        locations={},
        host=catalog.CDN_HOST,
    )
    assert record["decoded_size"] == len(content)
    assert record["encoded_size"] == len(raw)


def test_runtime_decoded_md5_mismatch_is_rejected() -> None:
    bad = _minimal_catalog()
    bad["casc_bootstrap"]["objects"][0]["decoded_md5"] = "f" * 32  # type: ignore[index]
    with pytest.raises(catalog.CatalogError, match="decoded MD5"):
        catalog.validate_catalog(bad)  # type: ignore[arg-type]


def test_runtime_source_and_declared_encoding_sizes_must_agree() -> None:
    content = b"size-check"
    raw = BLTEBuilder().build(BLTEBuilder.create_single_chunk(content, CompressionMode.ZLIB))
    ekey = bytes.fromhex("fedcba9876543210fedcba9876543210")

    class Source:
        def get(self, digest: str, kind: str = "data") -> bytes | None:
            return raw if digest == ekey.hex() else None

    with pytest.raises(catalog.CatalogError, match="encoded size disagreement"):
        catalog._ref_record(
            role="vfs-1",
            content_key=hashlib.md5(content).hexdigest(),
            decoded_size=len(content),
            declared_encoded_size=len(raw) + 1,
            ekeys=[ekey],
            source=Source(),  # type: ignore[arg-type]
            locations={},
            host=catalog.CDN_HOST,
        )


def test_catalog_rejects_bad_source_binding_and_multikey_policy() -> None:
    bad_url = _minimal_catalog()
    bad_url["casc_bootstrap"]["objects"][0]["sources"][0]["url"] = catalog.official_url(  # type: ignore[index]
        catalog.CDN_HOST, "data", "f" * 32
    )
    with pytest.raises(catalog.CatalogError, match="URL/EKey"):
        catalog.validate_catalog(bad_url)  # type: ignore[arg-type]

    bad_keys = _minimal_catalog()
    bad_keys["casc_bootstrap"]["objects"][0]["encoding_keys"].append("f" * 32)  # type: ignore[index]
    with pytest.raises(catalog.CatalogError, match="exactly one encoding key"):
        catalog.validate_catalog(bad_keys)  # type: ignore[arg-type]


def _cdn_index_fixture(*, offset_bytes: int, key: bytes, size: int, offset: int = 0) -> bytes:
    """Build one real paged CDN-index entry with a controllable footer role."""
    page_size = 4096
    entry = key + struct.pack(">I", size)
    if offset_bytes == 4:
        entry += struct.pack(">I", offset)
    elif offset_bytes == 6:
        entry += b"\x00\x00" + struct.pack(">I", offset)
    page = entry + b"\x00" * (page_size - len(entry))
    footer = (
        b"\x00" * 8  # TOC hash
        + bytes((1, 0, 0, 4, offset_bytes, 4, 16, 8))
        + struct.pack("<I", 1)
        + b"\x00" * 8
    )
    return page + key + b"\x00" * 8 + footer


def test_file_index_offset_zero_is_standalone_object_not_archive_range() -> None:
    ekey = bytes.fromhex("e092b002f52c0827ce4b65d534d7316d")
    file_index = "6589b9d0516fd85ed44ff57302a4cefe"

    class Source:
        def get(self, digest: str, kind: str = "data") -> bytes | None:
            if kind == "index" and digest == file_index:
                return _cdn_index_fixture(offset_bytes=0, key=ekey, size=3932016)
            return None

    locations, records = catalog._scan_locations(  # type: ignore[attr-defined]
        Source(), ["0017a402f556fbece46c38dc431a2c9b"], file_index_hash=file_index
    )
    location = locations[ekey]
    assert location.kind == "direct-object"
    assert location.index_hash == file_index
    assert location.offset is None
    assert location.length == 3932016
    assert records[-1]["index_role"] == "file-index"
    assert records[-1]["offset_bytes"] == 0

    record = catalog._ref_record(  # type: ignore[attr-defined]
        role="loose",
        content_key="d6c3f8e62bc38d8db9cb0cf1f3dee719",
        decoded_size=8654776,
        ekeys=[ekey],
        source=Source(),  # type: ignore[arg-type]
        locations=locations,
        host=catalog.CDN_HOST,
        install_relpath="Utils/discord_partner_sdk.dll",
    )
    assert record["sources"] == [{
        "kind": "direct-object",
        "ekey": ekey.hex(),
        "index": file_index,
        "url": catalog.official_url(catalog.CDN_HOST, "data", ekey.hex()),
        "encoded_size": 3932016,
    }]
    assert "sha256" not in record["sources"][0]


def test_file_index_role_and_archive_payload_binding_are_strict() -> None:
    ekey = bytes.fromhex("e092b002f52c0827ce4b65d534d7316d")
    file_index = "6589b9d0516fd85ed44ff57302a4cefe"

    class WrongRoleSource:
        def get(self, digest: str, kind: str = "data") -> bytes | None:
            if kind == "index" and digest == file_index:
                return _cdn_index_fixture(offset_bytes=4, key=ekey, size=3932016, offset=12)
            return None

    with pytest.raises(catalog.CatalogError, match="configured file index"):
        catalog._scan_locations(  # type: ignore[attr-defined]
            WrongRoleSource(), ["0017a402f556fbece46c38dc431a2c9b"], file_index_hash=file_index
        )

    bad = _minimal_catalog()
    index_hash = bad["casc_bootstrap"]["cdn_indices"][0]["hash"]  # type: ignore[index]
    bad["casc_bootstrap"]["cdn_indices"][0]["index_role"] = "file-index"  # type: ignore[index]
    bad["casc_bootstrap"]["cdn_indices"][0]["offset_bytes"] = 0  # type: ignore[index]
    source = bad["loose_files"][0]["sources"][0]  # type: ignore[index]
    source["kind"] = "archive-range"
    source["archive"] = index_hash
    source["offset"] = 0
    source["length"] = source["encoded_size"]
    source["range"] = "bytes=0-0"
    source["url"] = catalog.official_url(catalog.CDN_HOST, "data", index_hash)
    with pytest.raises(catalog.CatalogError, match="index hash.*archive payload"):
        catalog.validate_catalog(bad)  # type: ignore[arg-type]

    group_source = type("GroupSource", (), {"get": lambda self, digest, kind="data": _cdn_index_fixture(offset_bytes=6, key=ekey, size=3932016) if kind == "index" and digest == "group" else None})()
    with pytest.raises(catalog.CatalogError, match="archive-group CDN index is unsupported"):
        catalog._scan_locations(  # type: ignore[attr-defined]
            group_source, ["group"], file_index_hash=None
        )


def test_unverified_file_index_object_cannot_claim_launch_ready() -> None:
    bad = _minimal_catalog()
    index = bad["casc_bootstrap"]["cdn_indices"][0]  # type: ignore[index]
    index["index_role"] = "file-index"
    index["offset_bytes"] = 0
    source = bad["loose_files"][0]["sources"][0]  # type: ignore[index]
    source.clear()
    source.update({
        "kind": "direct-object",
        "ekey": "0123456789abcdef0123456789abcdef",
        "index": index["hash"],
        "url": catalog.official_url(catalog.CDN_HOST, "data", "0123456789abcdef0123456789abcdef"),
        "encoded_size": 1,
    })
    with pytest.raises(catalog.CatalogError, match="must keep the catalog blocked"):
        catalog.validate_catalog(bad)  # type: ignore[arg-type]
    bad["completeness"] = {"status": "blocked", "launch_ready": False}
    catalog.validate_catalog(bad)  # type: ignore[arg-type]


def test_verified_overlay_is_read_without_touching_the_cdn_cache(tmp_path: Path) -> None:
    ekey = "0123456789abcdef0123456789abcdef"
    overlay = tmp_path / ekey
    overlay.write_bytes(b"verified-overlay")
    source = catalog.CacheSource(  # type: ignore[call-arg]
        object(), verified_overlay={ekey: overlay}
    )
    assert source.get(ekey) == b"verified-overlay"


def test_source_audit_marks_bad_range_evidence_and_binds_expected_digest(monkeypatch: pytest.MonkeyPatch) -> None:
    audit = _load_source_audit()
    digest = "0123456789abcdef0123456789abcdef"
    url = catalog.official_url(catalog.CDN_HOST, "data", digest)

    class Response:
        status = 200

        def getheader(self, name: str) -> str | None:
            return "64" if name == "Content-Length" else None

        def read(self, amount: int = -1) -> bytes:
            return b"x" * min(amount, 64)

        def close(self) -> None:
            pass

    class Connection:
        def __init__(self, host: str, timeout: float) -> None:
            pass

        def request(self, method: str, path: str, headers: dict[str, str]) -> None:
            assert headers["Range"] == "bytes=0-63"

        def getresponse(self) -> Response:
            return Response()

        def close(self) -> None:
            pass

    monkeypatch.setattr(audit.http.client, "HTTPSConnection", Connection)  # type: ignore[attr-defined]
    result = audit._request(  # type: ignore[attr-defined]
        url,
        expected_digest=digest,
        start=0,
        end=63,
        expected_total=64,
        exact_total=True,
        timeout=1.0,
        body_limit=64,
        body_budget=[0],
    )
    assert not result["valid"]
    with pytest.raises(audit.AuditError):  # type: ignore[attr-defined]
        audit._request(  # type: ignore[attr-defined]
            url,
            expected_digest="fedcba9876543210fedcba9876543210",
            start=0,
            end=63,
            expected_total=64,
            exact_total=True,
            timeout=1.0,
            body_limit=64,
            body_budget=[0],
        )


def test_source_audit_rejects_bad_full_response_and_unsafe_output(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    audit = _load_source_audit()
    digest = "0123456789abcdef0123456789abcdef"
    url = catalog.official_url(catalog.CDN_HOST, "data", digest)

    class Response:
        status = 200

        def getheader(self, name: str) -> str | None:
            return "2" if name == "Content-Length" else None

        def read(self, amount: int = -1) -> bytes:
            return b"x"

        def close(self) -> None:
            pass

    class Connection:
        def __init__(self, host: str, timeout: float) -> None:
            pass

        def request(self, method: str, path: str, headers: dict[str, str]) -> None:
            pass

        def getresponse(self) -> Response:
            return Response()

        def close(self) -> None:
            pass

    monkeypatch.setattr(audit.http.client, "HTTPSConnection", Connection)  # type: ignore[attr-defined]
    result = audit._full_download(  # type: ignore[attr-defined]
        url,
        ekey=digest,
        expected_size=1,
        expected_content_key=hashlib.md5(b"x").hexdigest(),
        expected_decoded_size=1,
        timeout=1.0,
        output=tmp_path / digest,
        body_budget=[0],
    )
    assert not result["valid"]
    with pytest.raises(audit.AuditError):  # type: ignore[attr-defined]
        audit.ensure_external_path(ROOT / "inside-repo")  # type: ignore[attr-defined]


def test_source_probe_total_must_cover_the_entire_catalog_range(monkeypatch: pytest.MonkeyPatch) -> None:
    audit = _load_source_audit()
    digest = "0123456789abcdef0123456789abcdef"

    class Response:
        status = 206

        def getheader(self, name: str) -> str | None:
            return "64" if name == "Content-Length" else "bytes 0-63/64"

        def read(self, amount: int = -1) -> bytes:
            return b"x" * min(amount, 64)

        def close(self) -> None:
            pass

    class Connection:
        def __init__(self, host: str, timeout: float) -> None:
            pass

        def request(self, method: str, path: str, headers: dict[str, str]) -> None:
            pass

        def getresponse(self) -> Response:
            return Response()

        def close(self) -> None:
            pass

    monkeypatch.setattr(audit.http.client, "HTTPSConnection", Connection)  # type: ignore[attr-defined]
    result = audit._request(  # type: ignore[attr-defined]
        catalog.official_url(catalog.CDN_HOST, "data", digest), expected_digest=digest,
        start=0, end=63, expected_total=128, exact_total=False,
        timeout=1.0, body_limit=64, body_budget=[0],
    )
    assert not result["valid"]


def test_offline_source_report_rejects_direct_total_and_raw_sha_mismatches() -> None:
    audit = _load_source_audit()
    current = _minimal_catalog()
    item = current["loose_files"][0]  # type: ignore[index]
    source = item["sources"][0]
    source["encoded_size"] = item["encoded_size"] = 64
    report = {
        "status": "ok", "catalog_sha256": audit.canonical_catalog_sha256(current),  # type: ignore[attr-defined]
        "catalog_loose_file_count": 1, "checked_route_count": 1,
        "unsupported_count": 0, "failure_count": 0,
        "aggregate_response_body_bytes": 128,
        "routes": [{
            "relative_path": item["relative_path"], "url": source["url"],
            "expected_digest": source["ekey"], "range_requested": "bytes=0-63",
            "content_range": "bytes 0-63/64", "status": 206,
            "content_length": 64, "body_bytes_read": 64, "valid": True,
        }],
        "full_download": {
            "valid": True, "status": 200, "url": source["url"],
            "expected_digest": source["ekey"], "content_length": 64,
            "body_bytes_read": 64, "raw_sha256": source["sha256"],
            "decoded_md5": item["content_key"], "decoded_size": item["decoded_size"],
        },
    }
    assert audit.validate_audit_report(report, current)["status"] == "ok"  # type: ignore[attr-defined]
    report["aggregate_response_body_bytes"] = 129
    with pytest.raises(audit.AuditError, match="aggregate byte count"):  # type: ignore[attr-defined]
        audit.validate_audit_report(report, current)  # type: ignore[attr-defined]
    rebound = audit.bind_existing_report(report, current)  # type: ignore[attr-defined]
    assert rebound["aggregate_response_body_bytes"] == 128
    assert audit.validate_audit_report(rebound, current)["status"] == "ok"  # type: ignore[attr-defined]
    report["aggregate_response_body_bytes"] = 128
    duplicate = deepcopy(current)
    duplicate["loose_files"].append(deepcopy(item))  # type: ignore[index]
    with pytest.raises(audit.AuditError, match="duplicate catalog path"):  # type: ignore[attr-defined]
        audit.bind_existing_report(report, duplicate)  # type: ignore[attr-defined]
    report["routes"][0]["content_range"] = "bytes 0-63/128"  # type: ignore[index]
    with pytest.raises(audit.AuditError, match="Content-Range mismatch"):  # type: ignore[attr-defined]
        audit.validate_audit_report(report, current)  # type: ignore[attr-defined]
    report["routes"][0]["content_range"] = "bytes 0-63/64"  # type: ignore[index]
    report["full_download"]["raw_sha256"] = "f" * 64  # type: ignore[index]
    with pytest.raises(audit.AuditError, match="raw SHA differs"):  # type: ignore[attr-defined]
        audit.validate_audit_report(report, current)  # type: ignore[attr-defined]
    assert audit.bind_existing_report(report, current)["status"] == "blocked"  # type: ignore[attr-defined]


def test_missing_required_index_blocks_finite_catalog() -> None:
    bad = _minimal_catalog()
    bad["casc_bootstrap"]["cdn_indices"][0]["status"] = "missing-from-host-cache"  # type: ignore[index]
    with pytest.raises(catalog.CatalogError, match="incomplete catalog"):
        catalog.validate_catalog(bad)  # type: ignore[arg-type]


def test_runtime_audit_rejects_wrong_source_sha(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    audit = _load_runtime_audit()

    class FakeCache:
        def get_cdn(self, digest: str, kind: str, cdn_path: str) -> bytes:
            return b"cached"

        def _get_cdn_cache_path(self, digest: str, kind: str, cdn_path: str) -> Path:
            return tmp_path / digest

    monkeypatch.setattr(audit, "DiskCache", lambda _path=None: FakeCache())  # type: ignore[attr-defined]
    monkeypatch.setattr(  # type: ignore[attr-defined]
        audit,
        "_audit_direct_object",
        lambda raw, **kwargs: {
            "encoded_size": 1,
            "decoded_size": 1,
            "decoded_md5": kwargs["content_key"],
            "raw_sha256": "a" * 64,
            "blte_flags": None,
            "blte_modes": ["N"],
        },
    )
    with pytest.raises(catalog.CatalogError, match="source SHA256 mismatch"):
        audit.audit_catalog(_minimal_catalog())  # type: ignore[attr-defined]


def test_blte_and_local_casc_index_fixture_are_compatible(tmp_path: Path) -> None:
    content = b"catalog-fixture"
    blte = BLTEBuilder().build(BLTEBuilder.create_single_chunk(content, CompressionMode.ZLIB))
    assert decompress_blte(blte) == content

    storage = LocalStorage(tmp_path)
    storage.initialize()
    ekey = bytes.fromhex("0123456789abcdef0123456789abcdef")
    storage.write_content(ekey, blte, expected_ckey=hashlib.md5(blte).digest())
    storage.flush_indices()
    parsed_files = [parse_local_idx_file(path.read_bytes()) for path in (tmp_path / "Data" / "data").glob("*.idx")]
    assert all(parsed.version == 7 for parsed in parsed_files)
    assert any(entry.key == ekey[:9] for parsed in parsed_files for entry in parsed.entries)


def test_official_urls_keep_hash_and_range_shape() -> None:
    assert catalog.official_url(catalog.CDN_HOST, "data", "abcdef0123456789abcdef0123456789").endswith(
        "/data/ab/cd/abcdef0123456789abcdef0123456789"
    )
    assert catalog.official_url(catalog.CDN_HOST, "index", "abcdef0123456789abcdef0123456789", ".index").endswith(
        "/data/ab/cd/abcdef0123456789abcdef0123456789.index"
    )


def test_bounded_source_audit_rejects_non_official_or_unbound_urls() -> None:
    audit = _load_source_audit()
    digest = "abcdef0123456789abcdef0123456789"
    assert audit.validate_official_url(  # type: ignore[attr-defined]
        catalog.official_url(catalog.CDN_HOST, "data", digest), digest=digest
    )[0] == "us.cdn.blizzard.com"
    for url in (
        "http://us.cdn.blizzard.com/tpr/wow/data/ab/cd/" + digest,
        "https://cdn.example.invalid/tpr/wow/data/ab/cd/" + digest,
        catalog.official_url(catalog.CDN_HOST, "data", "fedcba9876543210fedcba9876543210"),
    ):
        with pytest.raises(audit.AuditError):  # type: ignore[attr-defined]
            audit.validate_official_url(url, digest=digest)  # type: ignore[attr-defined]
