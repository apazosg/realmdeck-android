"""Offline admission invariants for the official Classic Beta 70425 metadata gate."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import sqlite3
import struct
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
from cascette_tools.formats.install import InstallEntry, InstallFile, InstallTag


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/arm64-prototype/client/admit_build.py"
SPEC = importlib.util.spec_from_file_location("arm64_build_admission", MODULE_PATH)
assert SPEC and SPEC.loader
admission = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = admission
SPEC.loader.exec_module(admission)


def _versions(
    *,
    build_id: int = 70425,
    version: str = "1.60.1.70425",
    build_config: str = "ea9c525eef74f1d682c00a8ef39a5d2b",
    cdn_config: str = "fcab7fec2ccc6b8158b56b6938ec4551",
) -> bytes:
    return (
        "## seqn = 1234\n"
        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|"
        "BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16\n"
        f"us|{build_config}|{cdn_config}||{build_id}|{version}|0123456789abcdef0123456789abcdef\n"
        f"eu|{build_config}|{cdn_config}||{build_id}|{version}|0123456789abcdef0123456789abcdef\n"
    ).encode()


def _selected_install(*, executable: str = "WowB-ARM64.exe") -> InstallFile:
    entries = [
        InstallEntry(
            filename=executable,
            md5_hash=bytes.fromhex("11" * 16),
            size=4096,
            file_type=1,
            tags=["Windows", "arm64", "enUS"],
        ),
        InstallEntry(
            filename="Data/small.dat",
            md5_hash=bytes.fromhex("22" * 16),
            size=17,
            file_type=2,
            tags=["Windows", "arm64", "enUS"],
        ),
        InstallEntry(
            filename="WowB.exe",
            md5_hash=bytes.fromhex("33" * 16),
            size=2048,
            file_type=1,
            tags=["Windows", "x86", "enUS"],
        ),
    ]
    tags = [
        InstallTag(name="Windows", tag_type=1, bit_mask=bytes((0xE0,))),
        InstallTag(name="arm64", tag_type=2, bit_mask=bytes((0xC0,))),
        InstallTag(name="x86", tag_type=2, bit_mask=bytes((0x20,))),
        InstallTag(name="enUS", tag_type=3, bit_mask=bytes((0xE0,))),
    ]
    return InstallFile(version=2, hash_size=16, entries=entries, tags=tags)


def _chunked_n_blte(payload: bytes) -> tuple[bytes, str]:
    mode_chunk = b"N" + payload
    header_size = 12 + 24
    header = (
        b"BLTE"
        + struct.pack(">I", header_size)
        + b"\x0f\x00\x00\x01"
        + struct.pack(">II", len(mode_chunk), len(payload))
        + hashlib.md5(mode_chunk).digest()
    )
    assert len(header) == header_size
    raw = header + mode_chunk
    return raw, hashlib.md5(header).hexdigest()


def _encoding_with_large_espec() -> tuple[bytes, bytes, bytes]:
    ckey = bytes.fromhex("11" * 16)
    ekey = bytes.fromhex("22" * 16)
    ckey_page = bytearray(1024)
    ckey_page[:22] = b"\x01" + (123).to_bytes(5, "big") + ckey
    ckey_page[22:38] = ekey
    ekey_page = bytearray(1024)
    ekey_page[:25] = ekey + struct.pack(">I", 0) + (456).to_bytes(5, "big")
    espec = b"x\x00" * (393_134 // 2)
    assert len(espec) == 393_134
    header = (
        b"EN"
        + bytes((1, 16, 16))
        + struct.pack(">HHIIBI", 1, 1, 1, 1, 0, len(espec))
    )
    encoded = (
        header
        + espec
        + ckey
        + hashlib.md5(ckey_page).digest()
        + bytes(ckey_page)
        + ekey
        + hashlib.md5(ekey_page).digest()
        + bytes(ekey_page)
    )
    return encoded, ckey, ekey


def _cdn_index_fixture(
    entries: list[tuple[bytes, int, int | None]], *, offset_bytes: int
) -> bytes:
    page_size = 1024
    entry_size = 16 + 4 + offset_bytes
    per_page = page_size // entry_size
    pages = bytearray()
    toc_keys = bytearray()
    toc_hashes = bytearray()
    for start in range(0, len(entries), per_page):
        page_entries = entries[start : start + per_page]
        page = bytearray()
        for ekey, size, offset in page_entries:
            assert len(ekey) == 16
            page.extend(ekey)
            page.extend(size.to_bytes(4, "big"))
            if offset_bytes:
                assert offset is not None
                page.extend(offset.to_bytes(4, "big"))
        page.extend(bytes(page_size - len(page)))
        pages.extend(page)
        toc_keys.extend(page_entries[-1][0])
        toc_hashes.extend(hashlib.md5(page).digest()[:8])
    footer = (
        bytes(8)
        + bytes((1,))
        + bytes(2)
        + bytes((1, offset_bytes, 4, 16, 8))
        + len(entries).to_bytes(4, "little")
        + bytes(8)
    )
    return bytes(pages + toc_keys + toc_hashes) + footer


def test_official_versions_table_pins_the_us_70425_identity() -> None:
    official = admission.parse_official_versions(_versions())
    assert (official.region, official.build_id, official.version) == (
        "us",
        70425,
        "1.60.1.70425",
    )
    assert official.build_config_hash == "ea9c525eef74f1d682c00a8ef39a5d2b"
    assert official.cdn_config_hash == "fcab7fec2ccc6b8158b56b6938ec4551"
    assert official.sequence == "1234"
    assert official.feed_sha256 == hashlib.sha256(_versions()).hexdigest()

    with pytest.raises(admission.AdmissionError, match="official us build is"):
        admission.parse_official_versions(_versions(build_id=70009, version="1.60.1.70009"))


def test_official_versions_table_rejects_truncation_and_invalid_hash_provenance() -> None:
    truncated = b"Region!STRING:0|BuildConfig!HEX:16\nus|ea9c525eef74f1d6822ae0b03ffefb73\n"
    with pytest.raises(admission.AdmissionError, match="lacks columns|no parseable"):
        admission.parse_official_versions(truncated)

    with pytest.raises(admission.AdmissionError, match="invalid build config MD5"):
        admission.parse_official_versions(
            _versions(build_config="not-a-config-hash")
        )

    header_only = (
        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|"
        "BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16\n"
    ).encode()
    with pytest.raises(admission.AdmissionError, match="no parseable us build row"):
        admission.parse_official_versions(header_only)


def test_configs_are_hash_bound_before_parsing_and_require_the_install_ekey(monkeypatch: pytest.MonkeyPatch) -> None:
    official = admission.parse_official_versions(_versions())
    build_raw = b"synthetic build config"
    cdn_raw = b"synthetic cdn config"
    bound_build_hash = hashlib.md5(build_raw).hexdigest()
    install = SimpleNamespace(
        content_key="1234567890abcdef1234567890abcdef",
        encoding_key="abcdef1234567890abcdef1234567890",
        size=4096,
    )

    class FakeBuildParser:
        def parse(self, raw: bytes) -> object:
            assert raw == build_raw
            return SimpleNamespace(
                build_uid="wow_classic_beta",
                build_num="70425",
                build_name="Classic Beta 1.60.1.70425",
                get_install_info=lambda: install,
            )

    class FakeCDNParser:
        def parse(self, raw: bytes) -> object:
            assert raw == cdn_raw
            return SimpleNamespace(
                builds=[bound_build_hash],
                archives=[],
                patch_archives=[],
                file_index="0123456789abcdef0123456789abcdef",
                patch_file_index=None,
            )

    monkeypatch.setattr(admission, "BuildConfigParser", FakeBuildParser)
    monkeypatch.setattr(admission, "CDNConfigParser", FakeCDNParser)
    bound_build = admission.OfficialBuild(
        **{
            **official.__dict__,
            "build_config_hash": hashlib.md5(build_raw).hexdigest(),
            "cdn_config_hash": hashlib.md5(cdn_raw).hexdigest(),
        }
    )
    _, cdn, parsed_install = admission._parse_hash_bound_configs(bound_build, build_raw, cdn_raw)
    assert cdn.file_index == "0123456789abcdef0123456789abcdef"
    assert parsed_install.encoding_key == install.encoding_key

    with pytest.raises(admission.AdmissionError, match="build config MD5 mismatch"):
        admission._parse_hash_bound_configs(bound_build, b"different build", cdn_raw)


def test_config_product_and_cdn_build_binding_mismatches_are_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    official = admission.parse_official_versions(_versions())
    build_raw, cdn_raw = b"build bytes", b"cdn bytes"
    bound_build_hash = hashlib.md5(build_raw).hexdigest()
    install = SimpleNamespace(
        content_key="1234567890abcdef1234567890abcdef",
        encoding_key="abcdef1234567890abcdef1234567890",
        size=100,
    )
    monkeypatch.setattr(
        admission,
        "BuildConfigParser",
        lambda: SimpleNamespace(
            parse=lambda _raw: SimpleNamespace(
                build_uid="wow_classic_beta",
                build_num=None,
                build_name="wrong product",
                get_install_info=lambda: install,
            )
        ),
    )
    monkeypatch.setattr(
        admission,
        "CDNConfigParser",
        lambda: SimpleNamespace(
            parse=lambda _raw: SimpleNamespace(
                builds=["ff" * 16],
                archives=[],
                patch_archives=[],
                file_index=None,
                patch_file_index=None,
            )
        ),
    )
    bound = admission.OfficialBuild(
        **{
            **official.__dict__,
            "build_config_hash": hashlib.md5(build_raw).hexdigest(),
            "cdn_config_hash": hashlib.md5(cdn_raw).hexdigest(),
        }
    )
    with pytest.raises(admission.AdmissionError, match="does not include"):
        admission._parse_hash_bound_configs(bound, build_raw, cdn_raw)

    monkeypatch.setattr(
        admission,
        "BuildConfigParser",
        lambda: SimpleNamespace(
            parse=lambda _raw: SimpleNamespace(
                build_uid="another_product",
                build_num=None,
                build_name="wrong product",
                get_install_info=lambda: install,
            )
        ),
    )
    monkeypatch.setattr(
        admission,
        "CDNConfigParser",
        lambda: SimpleNamespace(
            parse=lambda _raw: SimpleNamespace(
                builds=[bound_build_hash],
                archives=[],
                patch_archives=[],
                file_index=None,
                patch_file_index=None,
            )
        ),
    )
    with pytest.raises(admission.AdmissionError, match="product mismatch"):
        admission._parse_hash_bound_configs(bound, build_raw, cdn_raw)


def test_selection_is_exactly_windows_arm64_enus_and_names_the_executable() -> None:
    records, executable = admission.select_install_records(_selected_install())
    assert [item["relative_path"] for item in records] == [
        "WowB-ARM64.exe",
        "Data/small.dat",
    ]
    assert executable["relative_path"] == "WowB-ARM64.exe"
    assert executable["content_key"] == "11" * 16
    assert executable["architecture_evidence"].startswith("declared arm64")
    assert executable["pe_machine_observed"] is False


def test_selection_fails_closed_for_missing_arch_tag_or_wrong_executable_path() -> None:
    missing_arm64 = _selected_install()
    missing_arm64.tags = [tag for tag in missing_arm64.tags if tag.name != "arm64"]
    with pytest.raises(admission.AdmissionError, match="exactly one 'arm64' tag"):
        admission.select_install_records(missing_arm64)

    with pytest.raises(admission.AdmissionError, match="0 exact WowB-ARM64.exe"):
        admission.select_install_records(_selected_install(executable="WowB.exe"))


def test_install_preflight_rejects_truncated_and_oversized_manifests(tmp_path: Path) -> None:
    truncated = tmp_path / "truncated.install"
    truncated.write_bytes(b"IN\x02\x10\x00\x00\x00\x00\x00")
    with pytest.raises(admission.AdmissionError, match="truncated or invalid IN header"):
        admission._preflight_install(truncated)

    too_many = tmp_path / "too-many.install"
    too_many.write_bytes(
        b"IN" + bytes((2, 16)) + struct.pack(">H", 0) + struct.pack(">I", admission.MAX_INSTALL_ENTRIES + 1)
    )
    with pytest.raises(admission.AdmissionError, match="entry count exceeds limit"):
        admission._preflight_install(too_many)


def test_closure_metadata_references_keep_all_three_object_fetches_bounded() -> None:
    accepted = SimpleNamespace(
        get_encoding_info=lambda: SimpleNamespace(
            content_key="11" * 16, encoding_key="12" * 16, size=100
        ),
        get_download_info=lambda: SimpleNamespace(
            content_key="21" * 16, encoding_key="22" * 16, size=200
        ),
        get_size_info=lambda: SimpleNamespace(
            content_key="31" * 16, encoding_key="32" * 16, size=300
        ),
    )
    references = admission._closure_metadata_references(accepted)
    assert list(references) == ["encoding", "download", "size"]
    assert references["download"].encoding_key == "22" * 16

    oversized = SimpleNamespace(
        get_encoding_info=lambda: SimpleNamespace(
            content_key="11" * 16,
            encoding_key="12" * 16,
            size=admission.MAX_METADATA_OBJECT_BYTES + 1,
        ),
        get_download_info=accepted.get_download_info,
        get_size_info=accepted.get_size_info,
    )
    with pytest.raises(admission.AdmissionError, match="encoding metadata encoded size exceeds"):
        admission._closure_metadata_references(oversized)


def test_stream_preflight_rejects_manifest_tag_memory_bombs_and_oversized_espec(
    tmp_path: Path,
) -> None:
    download = tmp_path / "download.manifest"
    size = tmp_path / "size.manifest"
    encoding = tmp_path / "encoding.manifest"
    download.write_bytes(
        b"DL" + bytes((1, 16, 0)) + struct.pack(">I", 2_000_000) + struct.pack(">H", 4096)
    )
    size.write_bytes(
        b"DS" + bytes((1, 9)) + struct.pack(">I", 0) + struct.pack(">H", 0) + bytes(5)
    )
    encoding.write_bytes(b"EN" + bytes((1, 16, 16)) + bytes(13) + struct.pack(">I", 0))
    with pytest.raises(admission.AdmissionError, match="tag bitmaps exceed memory bound"):
        admission._preflight_stream_manifests(download, size, encoding)

    download.write_bytes(b"DL" + bytes((1, 16, 0)) + struct.pack(">I", 0) + struct.pack(">H", 0))
    encoding.write_bytes(
        b"EN" + bytes((1, 16, 16)) + bytes(13) + struct.pack(">I", admission.MAX_ENCODING_ESPEC_BYTES + 1)
    )
    with pytest.raises(admission.AdmissionError, match="ESpec table exceeds memory bound"):
        admission._preflight_stream_manifests(download, size, encoding)


def test_extend_closure_parser_accepts_actual_393134_byte_espec_and_sqlite_fixture(
    tmp_path: Path,
) -> None:
    download = tmp_path / "download.manifest"
    size = tmp_path / "size.manifest"
    encoding = tmp_path / "encoding.manifest"
    download.write_bytes(
        b"DL" + bytes((1, 16, 0)) + struct.pack(">I", 0) + struct.pack(">H", 0)
    )
    size.write_bytes(
        b"DS" + bytes((1, 9)) + struct.pack(">I", 0) + struct.pack(">H", 0) + bytes(5)
    )
    encoding_raw, _ckey, _ekey = _encoding_with_large_espec()
    encoding.write_bytes(encoding_raw)

    db = sqlite3.connect(":memory:")
    admission._UPDATE_MANIFEST._create_spool_tables(db)
    parsed = admission._parse_closure_manifests(download, size, encoding, db)
    assert parsed["preflight"]["encoding_espec_bytes"] == 393_134
    assert parsed["encoding_stats"].espects == 196_567
    assert parsed["encoding_stats"].ckey_count == 1
    assert parsed["encoding_stats"].ekey_count == 1
    assert parsed["encoding_stats"].ckey_mapping_count == 1
    assert parsed["download_stats"].entry_count == 0
    assert parsed["size_stats"].entry_count == 0
    db.close()


def test_closure_role_bundle_includes_install_and_prevents_prior_keyerror() -> None:
    install = SimpleNamespace(
        content_key="01" * 16, encoding_key="02" * 16, size=10
    )
    refs = {
        role: SimpleNamespace(
            content_key=f"{index:02x}" * 16,
            encoding_key=f"{index + 1:02x}" * 16,
            size=20 + index,
        )
        for index, role in enumerate(("encoding", "download", "size"), 3)
    }
    complete = admission._all_closure_metadata_references(install, refs)
    raw = {
        role: {"path": f"verified-raw/{role}.blte", "sha256": "a" * 64, "url": "https://cdn.invalid/x"}
        for role in complete
    }
    decoded = {
        role: {"decoded_size": item.size, "decoded_md5": item.content_key, "decoded_sha256": "b" * 64, "blte_flags": 15, "blte_modes": ["N"]}
        for role, item in complete.items()
    }
    records = admission._closure_metadata_records(complete, raw, decoded)
    assert list(records) == ["install", "encoding", "download", "size"]
    assert records["install"]["encoding_key"] == install.encoding_key


def test_verified_raw_survives_real_parser_failure_and_retry_is_network_free(
    tmp_path: Path,
) -> None:
    payload = b"DL"  # validly authenticated BLTE object, deliberately truncated manifest
    raw, ekey = _chunked_n_blte(payload)
    ckey = hashlib.md5(payload).hexdigest()
    raw_cache = tmp_path / "verified-raw"
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    cache = admission._readonly_cache(tmp_path / "empty-cascette-cache")
    budget = admission.NetworkBudget()
    events: list[dict[str, object]] = []
    calls = 0
    url = f"https://us.cdn.blizzard.com/tpr/wow/data/{ekey[:2]}/{ekey[2:4]}/{ekey}"

    def fake_fetch(
        request_url: str,
        destination: Path,
        *,
        body_limit: int,
        expected_size: int,
        timeout: float,
        budget: admission.NetworkBudget,
        response_callback: object,
    ) -> tuple[int, int]:
        nonlocal calls
        calls += 1
        assert request_url == url
        assert len(raw) <= body_limit and expected_size == len(raw)
        budget.reserve(len(raw), request_url)
        destination.write_bytes(raw)
        budget.record(request_url, 200, len(raw))
        assert callable(response_callback)
        response_callback(request_url, 200, len(raw))  # type: ignore[operator]
        return 200, len(raw)

    def record_response(request_url: str, status: int, received: int, response_path: Path) -> None:
        admission._append_transfer_event(
            raw_cache,
            events,
            role="download",
            ekey=ekey,
            url=request_url,
            status=status,
            received=received,
            expected_size=len(raw),
            response_path=response_path,
        )

    first = admission._acquire_verified_closure_object(
        cache=cache,
        raw_cache_dir=raw_cache,
        scratch_path=scratch / "attempt-1.blte.part",
        digest=ekey,
        url=url,
        role="download",
        expected_size=len(raw),
        timeout=1.0,
        budget=budget,
        on_response=record_response,
        fetch=fake_fetch,
    )
    assert first["ekey_validation"]["ekey_rule"] == "md5-blte-header"
    assert first["ekey_validation"]["raw_sha256"] == hashlib.sha256(raw).hexdigest()

    decoded = io.BytesIO()
    with (raw_cache / f"download-{ekey}.blte").open("rb") as stream:
        result = admission.stream_decode_blte(
            stream, decoded, expected_ekey=ekey, encoded_size=len(raw)
        )
    assert result["decoded_md5"] == ckey
    db = sqlite3.connect(":memory:")
    admission._UPDATE_MANIFEST._create_spool_tables(db)
    with pytest.raises(admission._UPDATE_MANIFEST.GateError, match="truncated download header"):
        admission._UPDATE_MANIFEST.parse_download_manifest(io.BytesIO(decoded.getvalue()), db)
    db.close()

    persisted_path = raw_cache / f"download-{ekey}.blte"
    assert persisted_path.read_bytes() == raw
    assert persisted_path.stat().st_mode & 0o222 == 0

    def must_not_fetch(*_args: object, **_kwargs: object) -> tuple[int, int]:
        pytest.fail("retry attempted an HTTP request instead of reusing verified raw metadata")

    second = admission._acquire_verified_closure_object(
        cache=cache,
        raw_cache_dir=raw_cache,
        scratch_path=scratch / "attempt-2.blte.part",
        digest=ekey,
        url=url,
        role="download",
        expected_size=len(raw),
        timeout=1.0,
        budget=budget,
        on_response=record_response,
        fetch=must_not_fetch,
    )
    assert second["sha256"] == first["sha256"]
    assert persisted_path.read_bytes() == raw
    assert calls == 1
    assert budget.response_bytes == len(raw)
    ledger = admission._read_transfer_ledger(raw_cache)
    assert len(ledger) == 1
    assert sum(event["response_body_bytes"] for event in ledger) == len(raw)
    retry_ledger_delta, retry_ledger_bytes = admission._transfer_ledger_delta(ledger, 0)
    assert len(retry_ledger_delta) == 1 and retry_ledger_bytes == len(raw)
    committed_delta, committed_bytes = admission._transfer_ledger_delta(ledger, len(ledger))
    assert committed_delta == [] and committed_bytes == 0
    decoded_retry = io.BytesIO()
    with persisted_path.open("rb") as stream:
        admission.stream_decode_blte(stream, decoded_retry, expected_ekey=ekey, encoded_size=len(raw))
    retry_db = sqlite3.connect(":memory:")
    admission._UPDATE_MANIFEST._create_spool_tables(retry_db)
    with pytest.raises(admission._UPDATE_MANIFEST.GateError, match="truncated download header"):
        admission._UPDATE_MANIFEST.parse_download_manifest(io.BytesIO(decoded_retry.getvalue()), retry_db)
    retry_db.close()
    assert calls == 1 and budget.response_bytes == len(raw)


def test_consumer_network_budget_uses_the_cumulative_remaining_allowance() -> None:
    budget = admission.NetworkBudget(limit_bytes=17)
    budget.reserve(17, "https://us.cdn.blizzard.com/tpr/wow/data/aa/bb/object")
    budget.record("https://us.cdn.blizzard.com/tpr/wow/data/aa/bb/object", 200, 17)
    with pytest.raises(admission.AdmissionError, match="17\\+1>17"):
        budget.reserve(1, "https://us.cdn.blizzard.com/tpr/wow/data/aa/bb/next")


def test_cdn_archive_and_file_index_sources_keep_full_ekeys_and_validate_pages() -> None:
    archive_hash = "ab" * 16
    file_index_hash = "cd" * 16
    full_ekey = bytes.fromhex("1234567890abcdef12" + "a1b2c3d4e5f607")
    same_prefix_other_ekey = bytes.fromhex("1234567890abcdef12" + "08070605040302")
    archive = _cdn_index_fixture([(full_ekey, 1234, 65536)], offset_bytes=4)
    direct = _cdn_index_fixture(
        [(same_prefix_other_ekey, 5678, None)], offset_bytes=0
    )

    assert admission._cdn_index_layout(archive, archive_hash, file_index_hash)["file_index"] is False
    assert list(admission._iter_cdn_index_entries(archive, archive_hash, file_index_hash)) == [
        (full_ekey, 1234, 65536)
    ]
    assert admission._cdn_index_layout(direct, file_index_hash, file_index_hash)["file_index"] is True
    assert list(admission._iter_cdn_index_entries(direct, file_index_hash, file_index_hash)) == [
        (same_prefix_other_ekey, 5678, None)
    ]

    corrupted = bytearray(archive)
    corrupted[0] ^= 1
    with pytest.raises(admission.AdmissionError, match="TOC/checksum mismatch"):
        list(admission._iter_cdn_index_entries(bytes(corrupted), archive_hash, file_index_hash))


def test_consumer_source_resolution_prefers_direct_and_never_uses_short_ekey_identity() -> None:
    db = sqlite3.connect(":memory:")
    admission._create_consumer_work_tables(db)
    first = bytes.fromhex("1234567890abcdef12" + "a1b2c3d4e5f607")
    second = bytes.fromhex("1234567890abcdef12" + "08070605040302")
    db.executemany(
        "INSERT INTO consumer_needed(ekey,encoded_size) VALUES(?,?)",
        [(first, 100), (second, 200)],
    )
    archive = bytes.fromhex("ab" * 16)
    admission._record_consumer_source(
        db,
        ekey=first,
        encoded_size=100,
        source_kind="range",
        source_hash=archive,
        source_offset=4096,
    )
    admission._record_consumer_source(
        db,
        ekey=second,
        encoded_size=200,
        source_kind="range",
        source_hash=archive,
        source_offset=8192,
    )
    admission._record_consumer_source(
        db,
        ekey=first,
        encoded_size=100,
        source_kind="direct",
        source_hash=first,
        source_offset=0,
    )
    assert db.execute(
        "SELECT ekey,source_kind,source_hash,source_offset FROM consumer_sources ORDER BY ekey"
    ).fetchall() == [
        (second, "range", archive, 8192),
        (first, "direct", first, 0),
    ]
    with pytest.raises(admission.AdmissionError, match="encoded size mismatch"):
        admission._record_consumer_source(
            db,
            ekey=first,
            encoded_size=101,
            source_kind="range",
            source_hash=archive,
            source_offset=4096,
        )
    db.close()


def test_consumer_sqlite_export_has_portable_exact_schema_and_source_constraints(
    tmp_path: Path,
) -> None:
    path = tmp_path / "client-catalog.sqlite3"
    spool = sqlite3.connect(":memory:")
    admission._create_consumer_work_tables(spool)
    direct_ekey = bytes.fromhex("11" * 16)
    direct_ckey = bytes.fromhex("22" * 16)
    range_ekey = bytes.fromhex("33" * 16)
    range_ckey = bytes.fromhex("44" * 16)
    archive_hash = bytes.fromhex("55" * 16)
    spool.executemany(
        "INSERT INTO consumer_work(ekey,ckey,encoded_size,decoded_size) VALUES(?,?,?,?)",
        [
            (direct_ekey, direct_ckey, 100, 200),
            (range_ekey, range_ckey, 300, 400),
        ],
    )
    spool.executemany(
        "INSERT INTO consumer_sources(ekey,encoded_size,source_kind,source_hash,source_offset) "
        "VALUES(?,?,?,?,?)",
        [
            (direct_ekey, 100, "direct", direct_ekey, 0),
            (range_ekey, 300, "range", archive_hash, 65536),
        ],
    )
    spool.execute(
        "INSERT INTO consumer_loose(path,ekey,ckey,encoded_size,decoded_size) VALUES(?,?,?,?,?)",
        ("WowB-ARM64.exe", direct_ekey, direct_ckey, 100, 200),
    )
    spool.execute(
        "INSERT INTO consumer_chosen_loose(path,ekey,ckey,encoded_size,decoded_size) VALUES(?,?,?,?,?)",
        ("WowB-ARM64.exe", direct_ekey, direct_ckey, 100, 200),
    )
    spool.commit()
    artifact = admission._write_consumer_catalog(
        spool=spool, path=path, metadata={"build_id": 70425}
    )
    assert artifact["object_count"] == 2 and artifact["loose_count"] == 1
    db = sqlite3.connect(path)
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO objects(ekey,ckey,encoded_size,decoded_size,source_kind,source_hash,source_offset,source_length) "
            "VALUES(?,?,?,?,?,?,?,?)",
            (bytes.fromhex("66" * 16), direct_ckey, 100, 200, "direct", archive_hash, 0, 100),
        )
    db.commit()
    assert {
        name: [row[1] for row in db.execute(f"PRAGMA table_info({name})")]
        for name in ("metadata", "objects", "loose")
    } == {
        "metadata": ["key", "value"],
        "objects": [
            "ekey", "ckey", "encoded_size", "decoded_size", "source_kind",
            "source_hash", "source_offset", "source_length",
        ],
        "loose": ["path", "ekey", "ckey", "encoded_size", "decoded_size"],
    }
    assert db.execute("SELECT COUNT(*) FROM objects").fetchone()[0] == 2
    assert db.execute("SELECT COUNT(*) FROM loose").fetchone()[0] == 1
    assert db.execute(
        "SELECT source_kind,source_hash,source_offset,source_length FROM objects WHERE ekey=?",
        (range_ekey,),
    ).fetchone() == ("range", archive_hash, 65536, 300)
    db.close()
    spool.close()
    assert path.stat().st_size > 0


def test_runtime_and_loose_records_form_one_source_resolved_consumer_catalog(
    tmp_path: Path,
) -> None:
    spool = sqlite3.connect(":memory:")
    admission._UPDATE_MANIFEST._create_spool_tables(spool)
    admission._create_consumer_work_tables(spool)
    records: dict[str, SimpleNamespace] = {}
    for number, role in enumerate(
        ("root", "vfs-root", "vfs-1", "encoding", "install", "download", "size", "loose"),
        1,
    ):
        ekey = bytes((number,)) * 16
        ckey = bytes((0x80 + number,)) * 16
        encoded_size = 100 + number
        decoded_size = 4096 if role == "loose" else 200 + number
        if role != "encoding":
            spool.execute(
                "INSERT INTO encoding_ekeys(ekey,encoded_size) VALUES(?,?)",
                (ekey, encoded_size),
            )
            spool.execute(
                "INSERT INTO encoding_ckeys(ekey,ckey,decoded_size) VALUES(?,?,?)",
                (ekey, ckey, decoded_size),
            )
        records[role] = SimpleNamespace(
            content_key=ckey.hex(),
            encoding_key=ekey.hex(),
            size=encoded_size,
            decoded_size=decoded_size,
        )
    download_ekey = bytes.fromhex(records["download"].encoding_key)
    spool.execute(
        "INSERT INTO download_entries(entry_index,ekey,encoded_size,priority,checksum,flags,selected) "
        "VALUES(0,?,?,0,NULL,NULL,1)",
        (download_ekey, records["download"].size),
    )
    build = SimpleNamespace(
        root=records["root"].content_key,
        get_vfs_root_info=lambda: records["vfs-root"],
        get_vfs_entries=lambda: [(1, records["vfs-1"])],
    )
    references = {role: records[role] for role in ("encoding", "install", "download", "size")}
    loose = {
        "relative_path": "Data/small.dat",
        "content_key": records["loose"].content_key,
        "decoded_size": records["loose"].decoded_size,
    }
    selected_stats = SimpleNamespace(selected_ekey_count=1)
    selection = admission._populate_consumer_work(
        spool,
        build=build,
        references=references,
        metadata_decoded_sizes={
            role: records[role].decoded_size
            for role in ("encoding", "install", "download", "size")
        },
        install_records=[loose],
        download_stats=selected_stats,
    )
    for (ekey, encoded_size) in spool.execute(
        "SELECT ekey,encoded_size FROM consumer_needed"
    ):
        admission._record_consumer_source(
            spool,
            ekey=bytes(ekey),
            encoded_size=encoded_size,
            source_kind="direct",
            source_hash=bytes(ekey),
            source_offset=0,
        )
    chosen = admission._choose_consumer_candidates(spool)
    assert chosen["loose_count"] == 1
    assert set(chosen["runtime_role_fingerprints"]) == set(selection["required_runtime_roles"])
    assert spool.execute("SELECT COUNT(*) FROM consumer_work").fetchone()[0] == 8

    path = tmp_path / "complete-catalog.sqlite3"
    artifact = admission._write_consumer_catalog(
        spool=spool,
        path=path,
        metadata={
            "build_id": 70425,
            "runtime_role_fingerprints": chosen["runtime_role_fingerprints"],
        },
    )
    assert artifact["object_count"] == 8
    assert artifact["loose_count"] == 1
    consumer = sqlite3.connect(path)
    assert consumer.execute("SELECT COUNT(*) FROM objects WHERE source_kind='direct'").fetchone()[0] == 8
    assert consumer.execute("SELECT path,decoded_size FROM loose").fetchone() == (
        "Data/small.dat", 4096
    )
    consumer.close()
    spool.close()


def test_selection_jsonl_is_a_reproducible_pin_protocol_not_a_pe_claim(tmp_path: Path) -> None:
    official = admission.parse_official_versions(_versions())
    install = SimpleNamespace(
        content_key="12" * 16,
        encoding_key="34" * 16,
        size=111,
    )
    records, _ = admission.select_install_records(_selected_install())
    path = tmp_path / "install-selection.jsonl"
    evidence = admission._write_selection(
        path,
        official=official,
        install_info=install,
        raw_artifact={"sha256": "a" * 64, "path": "metadata/install.blte", "url": "https://us.cdn.blizzard.com/data"},
        decoded_evidence={
            "decoded_sha256": "b" * 64,
            "decoded_md5": install.content_key,
        },
        tags=_selected_install().tags,
        all_entry_count=3,
        records=records,
    )
    lines = [json.loads(line) for line in path.read_text().splitlines()]
    assert evidence["record_count"] == 2
    assert lines[0]["schema"] == "wowforever.arm64-build-admission.selection"
    assert lines[0]["build_config_hash"] == official.build_config_hash
    assert lines[0]["install_encoding_key"] == install.encoding_key
    assert lines[0]["install_raw_sha256"] == "a" * 64
    assert "Do not trust this JSONL alone" in lines[0]["android_trust_contract"]
    assert lines[1]["relative_path"] == "WowB-ARM64.exe"
