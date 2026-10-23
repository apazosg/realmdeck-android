"""Synthetic boundary tests for the bounded ARM64 trust-alias analyzer."""

from __future__ import annotations

import importlib.util
import hashlib
import json
import os
import stat
import struct
from types import SimpleNamespace
from pathlib import Path


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/diagnostics/reverse-engineering/arm64-trust-alias/analyze.py"
SPEC = importlib.util.spec_from_file_location("arm64_trust_alias", MODULE_PATH)
assert SPEC and SPEC.loader
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


def section(name: str, *, rva: int, raw_offset: int, raw_size: int, virtual_size: int) -> dict:
    return {
        "name": name,
        "rva": rva,
        "raw_offset": raw_offset,
        "raw_size": raw_size,
        "virtual_size": virtual_size,
        "characteristics": 0,
    }


def test_raw_rva_and_bss_boundaries_are_classified() -> None:
    sections = [section(".rdata", rva=0x1000, raw_offset=0x200, raw_size=0x20, virtual_size=0x40)]
    assert analysis.rva_to_raw(sections, 0x1010, 8) == 0x210
    assert analysis.storage_for(sections, 0x1010, 8)["kind"] == "initialized_file_backed"
    assert analysis.rva_to_raw(sections, 0x1030, 1) is None
    assert analysis.storage_for(sections, 0x1030, 1)["kind"] == "BSS_zero_fill"


def test_dir64_alias_requires_relocation_and_exact_target() -> None:
    image_base = 0x140000000
    assert analysis.pointer_encoding(image_base + 0x5020, 0x5020, image_base, analysis.DIR64) == "DIR64"
    assert analysis.pointer_encoding(image_base + 0x5021, 0x5021, image_base, None) is None
    assert analysis.pointer_encoding(0x5020, 0x5020, image_base, None) == "candidate_relative_rva"
    assert analysis.pointer_encoding(image_base + 0x5020, 0x5020, image_base, 3) is None


def test_synthetic_pe_data_finds_dir64_and_rva_edges_but_not_adjacent_or_bss() -> None:
    class BlobReader:
        def __init__(self, blob: bytes):
            self.blob = blob
            self.analysis_bytes = 0

        def read(self, offset: int, length: int, **_kwargs) -> bytes:
            self.analysis_bytes += length
            return self.blob[offset : offset + length]

    image_base = 0x140000000
    blob = bytearray(0x180)
    # .rdata RVA 0x1000; target 0x1020 is raw-backed, 0x1040 is BSS.
    struct.pack_into("<Q", blob, 0x08, image_base + 0x1020)
    struct.pack_into("<Q", blob, 0x10, image_base + 0x1021)  # adjacent false positive
    struct.pack_into("<Q", blob, 0x18, 0x1020)  # unrelocated RVA32 form
    struct.pack_into("<Q", blob, 0x20, image_base + 0x1040)  # BSS target is still classifiable
    sections = [section(".rdata", rva=0x1000, raw_offset=0, raw_size=0x30, virtual_size=0x50)]
    reader = BlobReader(bytes(blob))
    edges, report = analysis.scan_aliases(
        reader,
        {"image_base": image_base, "size_of_image": 0x2000},
        sections,
        {0x1008: analysis.DIR64, 0x1010: analysis.DIR64, 0x1020: analysis.DIR64},
        {0x1020, 0x1040},
    )
    assert {item["encoding"] for item in edges[0x1020]} == {"DIR64", "candidate_relative_rva"}
    assert all(item["target_rva"] != "0x1021" for values in edges.values() for item in values)
    assert edges[0x1040][0]["target_storage"]["kind"] == "BSS_zero_fill"
    assert report["unique_coverage_bytes"] == 0x30


def test_alias_scan_budget_rejects_unbounded_initialized_data() -> None:
    class Stub:
        pass

    reader = Stub()
    reader.read = lambda *_args, **_kwargs: b""
    sections = [section(".rdata", rva=0x1000, raw_offset=0, raw_size=analysis.MAX_ALIAS_SCAN_BYTES + 1, virtual_size=analysis.MAX_ALIAS_SCAN_BYTES + 1)]
    try:
        analysis.scan_aliases(reader, {"image_base": 0x140000000, "size_of_image": 0x100000}, sections, {}, {0x2000})
    except ValueError as exc:
        assert "bound" in str(exc)
    else:
        raise AssertionError("alias coverage limit was not enforced")


def test_real_scanner_reports_graph_truncation(monkeypatch) -> None:
    class BlobReader:
        def __init__(self, blob: bytes):
            self.blob = blob

        def read(self, offset: int, length: int, **_kwargs) -> bytes:
            return self.blob[offset : offset + length]

    image_base = 0x140000000
    blob = bytearray(0x40)
    struct.pack_into("<Q", blob, 0x08, image_base + 0x1020)
    struct.pack_into("<Q", blob, 0x10, image_base + 0x1020)
    sections = [section(".rdata", rva=0x1000, raw_offset=0, raw_size=0x20, virtual_size=0x40)]
    monkeypatch.setattr(analysis, "MAX_NODES", 2)
    edges, report = analysis.scan_aliases(
        BlobReader(bytes(blob)),
        {"image_base": image_base, "size_of_image": 0x2000},
        sections,
        {0x1008: analysis.DIR64, 0x1010: analysis.DIR64},
        {0x1020},
    )
    assert edges[0x1020]
    assert report["graph_limits"]["node_limit_hit"]
    assert report["graph_limits"]["graph_truncated"]


def test_wrong_size_rejects_before_reader_payload(monkeypatch, tmp_path: Path) -> None:
    source = tmp_path / "wrong-size.exe"
    source.write_bytes(b"short")
    read_called = False

    def forbidden_read(*_args, **_kwargs):
        nonlocal read_called
        read_called = True
        raise AssertionError("payload read occurred before size rejection")

    monkeypatch.setattr(analysis.Reader, "read", forbidden_read)
    monkeypatch.setattr(analysis.Reader, "sha256", forbidden_read)
    try:
        analysis.analyze(source)
    except ValueError as exc:
        assert "size mismatch" in str(exc)
    else:
        raise AssertionError("wrong-size input was accepted")
    assert not read_called


def test_held_reader_identity_ignores_atime_but_detects_real_change(tmp_path: Path) -> None:
    source = tmp_path / "held.bin"
    source.write_bytes(b"stable")
    with analysis.Reader(source) as reader:
        before = reader.stat()
        after_atime = SimpleNamespace(
            st_dev=before.st_dev,
            st_ino=before.st_ino,
            st_mode=before.st_mode,
            st_size=before.st_size,
            st_mtime_ns=before.st_mtime_ns,
            st_ctime_ns=before.st_ctime_ns,
            st_atime_ns=before.st_atime_ns + 1_000_000,
        )
        assert analysis.same_identity(before, after_atime)
        os.utime(source, ns=(after_atime.st_atime_ns, after_atime.st_mtime_ns + 1_000_000))
        after_mtime = reader.stat()
        assert not analysis.same_identity(before, after_mtime)


def test_prior_report_is_read_hashed_and_values_are_authenticated(tmp_path: Path, monkeypatch) -> None:
    report = tmp_path / "prior.txt"
    report.write_text(
        "SOURCE path=arm64.exe size=94286544 sha256_before=" + analysis.EXPECTED_SHA256
        + " expected_sha256=" + analysis.EXPECTED_SHA256 + " machine=0xaa64\n"
        + "SLOT rva=0x4a5ff00 raw_offset=0x4a5f100 length=32 section=.rdata exact_locator=true\n"
        + "DATA_ALIAS source_rva=0x4cb63b8 source_section=.rdata target_neighborhood_offset=32 width=8 exact_slot_target=false\n"
        + "RAW_SEEDS architecture=ARM64 executable_bytes_examined=74182144 candidates=0 independent_selector=true\n"
        + "GHIDRA_CONFIRMATION attempted_seeds=0 confirmed_references=0 direct_reference_manager=true\n"
        + "BODY_SELECTION candidate_functions=0 selected=0 max_bodies=4 max_body_bytes=65536\n"
        + "SOURCE sha256_after=" + analysis.EXPECTED_SHA256 + " size_after=94286544 stable=true\n"
    )
    monkeypatch.setattr(analysis, "PRIOR_CODE_REPORT_SHA256", hashlib.sha256(report.read_bytes()).hexdigest())
    result = analysis.authenticate_prior_report(report)
    assert result["validated"]
    assert result["reused"]
    assert result["report_sha256"] == hashlib.sha256(report.read_bytes()).hexdigest()
    report.write_text(report.read_text().replace("candidates=0", "candidates=1"))
    tampered = analysis.authenticate_prior_report(report)
    assert tampered["status"] == "INCOMPLETE"
    assert not tampered["reused"]
    assert tampered["reason"] == "report_digest_mismatch"
    # Even an explicitly pinned report must have the expected parsed values.
    monkeypatch.setattr(analysis, "PRIOR_CODE_REPORT_SHA256", hashlib.sha256(report.read_bytes()).hexdigest())
    assert analysis.authenticate_prior_report(report)["reason"] == "parsed_values_not_expected"
    missing = analysis.authenticate_prior_report(tmp_path / "missing.txt")
    assert missing["status"] == "INCOMPLETE"
    assert missing["report_sha256"] is None


def test_prior_report_checks_pinned_digest_before_parsing_fields(tmp_path: Path) -> None:
    report = tmp_path / "prior.txt"
    report.write_text("untrusted report; no authentication by self-reported source hash\n")
    result = analysis.authenticate_prior_report(report)
    assert result["reason"] == "report_digest_mismatch"
    assert not result["validated"] and not result["reused"]


def test_report_identity_hash_and_writer_are_private_exclusive(tmp_path: Path) -> None:
    output_parent = tmp_path / "private"
    output_parent.mkdir()
    output_parent.chmod(0o700)
    output = output_parent / "report.json"
    report = {
        "source": {"sha256_before": analysis.EXPECTED_SHA256, "sha256_after": analysis.EXPECTED_SHA256, "stable_held_fd": True},
        "io": {"full_hash_bytes_read": analysis.EXPECTED_SIZE * 2, "hashes_reported_separately": True},
        "raw_bytes_emitted": False,
    }
    analysis.create_report(output, report)
    assert stat.S_IMODE(output.stat().st_mode) == 0o600
    assert json.loads(output.read_text())["io"]["full_hash_bytes_read"] == 188_573_088
    try:
        analysis.create_report(output, report)
    except FileExistsError:
        pass
    else:
        raise AssertionError("report writer overwrote an existing artifact")


def test_report_rejects_public_parent_without_changing_it(tmp_path: Path) -> None:
    parent = tmp_path / "public"
    parent.mkdir()
    parent.chmod(0o755)
    try:
        analysis.create_report(parent / "report.json", {})
    except PermissionError:
        pass
    else:
        raise AssertionError("public report parent was accepted")
    assert stat.S_IMODE(parent.stat().st_mode) == 0o755


def test_report_rejects_symlink_parent(tmp_path: Path) -> None:
    private = tmp_path / "private"
    private.mkdir()
    private.chmod(0o700)
    link = tmp_path / "link"
    link.symlink_to(private, target_is_directory=True)
    try:
        analysis.create_report(link / "report.json", {})
    except ValueError:
        pass
    else:
        raise AssertionError("symlink report parent was accepted")
