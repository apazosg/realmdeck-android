"""Offline safety tests for the fixed-ledger candidate materializer."""

from __future__ import annotations

import hashlib
import io
import json
import lzma
import sys
import tarfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts/arm64-prototype/controls/virgl"))
import audit_payloads as audit  # noqa: E402
import materialize_candidate as materializer  # noqa: E402


def _ar_data(path: Path, payload: bytes, name: str = "fixture_1_aarch64.deb") -> Path:
    def member(member_name: str, value: bytes) -> bytes:
        header = f"{member_name:<16}{0:<12}{0:<6}{0:<6}{0:<8}{len(value):<10}`\n".encode("ascii")
        return header + value + (b"\n" if len(value) & 1 else b"")

    raw = b"!<arch>\n" + member("debian-binary", b"2.0\n") + member("data.tar.xz", payload)
    result = path / name
    result.write_bytes(raw)
    return result


def _empty_data_tar() -> bytes:
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        archive.addfile(tarfile.TarInfo("."))
    return lzma.compress(stream.getvalue())


def _record(path: Path) -> materializer.LedgerPackage:
    raw = path.read_bytes()
    return materializer.LedgerPackage(
        "fixture",
        "1",
        "aarch64",
        "pool/main/f/fixture/fixture_1_aarch64.deb",
        hashlib.sha256(raw).hexdigest(),
        len(raw),
        None,
    )


def test_wrong_pin_is_rejected_before_candidate_use(tmp_path: Path) -> None:
    package = _ar_data(tmp_path, _empty_data_tar())
    record = _record(package)
    wrong = materializer.LedgerPackage(record.package, record.version, record.architecture, record.filename, "0" * 64, record.bytes, None)
    with pytest.raises(audit.AuditError, match="SHA-256"):
        materializer.acquire_packages([wrong], tmp_path)


def test_fixed_metadata_rejects_query_and_traversal_urls(tmp_path: Path) -> None:
    path = tmp_path / "metadata.json"
    package = {
        "Architecture": "aarch64",
        "Filename": "pool/main/f/fixture/../fixture_1_aarch64.deb?x=1",
        "Package": "fixture",
        "SHA256": "0" * 64,
        "Size": "1",
        "Version": "1",
    }
    path.write_text(
        json.dumps(
            {
                "repository": materializer.ORIGIN,
                "compressedIndexSha256": materializer.PINNED_INDEX_SHA256,
                "compressedPackageBytes": 1,
                "packages": {"fixture": package},
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(materializer.MaterializerError, match="unsafe metadata filename"):
        materializer.load_fixed_metadata(path)


def test_repeated_output_directory_is_rejected(tmp_path: Path) -> None:
    output = tmp_path / "private_candidate"
    materializer._ensure_output_dir(output)
    with pytest.raises(materializer.MaterializerError, match="new"):
        materializer._ensure_output_dir(output)


def test_unneeded_et_exec_is_recorded_not_materialized(tmp_path: Path) -> None:
    # Header-only ET_EXEC is sufficient to exercise the scoped ignore branch;
    # it is never passed to a loader and cannot become a selected SONAME.
    raw = bytearray(20)
    raw[:4] = b"\x7fELF"
    raw[4:7] = b"\x02\x01\x01"
    raw[16:18] = (2).to_bytes(2, "little")
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        item = tarfile.TarInfo("data/data/com.termux/files/usr/bin/helper")
        item.size = len(raw)
        archive.addfile(item, io.BytesIO(raw))
    component = audit.parse_data_tar(
        lzma.compress(stream.getvalue()),
        "fixture.deb",
        ignore_unneeded_et_exec=True,
    )
    assert component["ignoredElfs"][0]["reason"] == "unneeded_ET_EXEC"
    assert component["libraries"] == []
