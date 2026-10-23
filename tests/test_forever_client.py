"""Tests sin red: contrato, filtrado por tags y clasificación ausente/corrupto."""

from __future__ import annotations

import hashlib
from pathlib import Path

from cascette_tools.formats.install import InstallEntry, InstallTag

from forever_client.casc import ExpectedFile, filter_install_entries
from forever_client.contract import Contract, is_supported_version, load_contract
from forever_client.verify import classify


def test_load_contract_real() -> None:
    contract = load_contract()
    assert contract.product == "wow_classic_beta"
    assert contract.exe == "WowB.exe"
    assert contract.supported_versions == (
        "1.60.1.69913",
        "1.60.1.69977",
        "1.60.1.70009",
    )
    assert contract.validated_build_id == 70009
    assert contract.validated_build_config_hash == "05215079e3905ef5922ae0b03ffefb73"
    assert contract.validated_cdn_config_hash == "9b3c456dbb837d133a026d380c7c13e9"


def test_is_supported_version() -> None:
    contract = Contract(path=Path("x"), supported_versions=("1.60.1.70009",))
    assert is_supported_version("1.60.1.70009", contract)
    assert not is_supported_version("1.60.1.12345", contract)
    assert not is_supported_version("", contract)


def _synthetic_manifest() -> tuple[list[InstallEntry], list[InstallTag]]:
    # 8 ficheros; bits MSB-first: fichero i -> byte i//8, bit 0x80 >> (i % 8).
    entries = [
        InstallEntry(filename=f"f{i}.bin", md5_hash=hashlib.md5(f"{i}".encode()).digest(), size=i)
        for i in range(8)
    ]
    windows = InstallTag(name="Windows", tag_type=1, bit_mask=bytes([0xE0]))  # f0,f1,f2
    enus = InstallTag(name="enUS", tag_type=2, bit_mask=bytes([0x50]))  # f1,f3
    return entries, [windows, enus]


def test_filter_by_single_tag() -> None:
    entries, tags = _synthetic_manifest()
    windows = filter_install_entries(entries, tags, "Windows", None, None)
    assert [e.filename for e in windows] == ["f0.bin", "f1.bin", "f2.bin"]
    enus = filter_install_entries(entries, tags, None, None, "enUS")
    assert [e.filename for e in enus] == ["f1.bin", "f3.bin"]


def test_filter_without_query_returns_all() -> None:
    entries, tags = _synthetic_manifest()
    assert filter_install_entries(entries, tags, None, None, None) == entries


def _expected(relpath: str, content: bytes) -> ExpectedFile:
    return ExpectedFile(
        relpath=relpath, md5=hashlib.md5(content).digest(), size=len(content)
    )


def test_classify_ok_missing_corrupt(tmp_path: Path) -> None:
    good = b"0123456789"
    (tmp_path / "good.bin").write_bytes(good)
    (tmp_path / "bad.bin").write_bytes(b"012345678X")  # mismo tamaño, distinto MD5
    (tmp_path / "short.bin").write_bytes(b"0123")  # tamaño distinto
    expected = [
        _expected("good.bin", good),
        _expected("absent.bin", b"0123456789"),
        _expected("bad.bin", good),
        _expected("short.bin", good),
    ]
    mismatches = {m.relpath: m for m in classify(tmp_path, expected)}
    assert set(mismatches) == {"absent.bin", "bad.bin", "short.bin"}
    assert mismatches["absent.bin"].kind == "missing"
    assert mismatches["bad.bin"].kind == "corrupt"
    assert "MD5" in mismatches["bad.bin"].detail
    assert mismatches["short.bin"].kind == "corrupt"
    assert "tamaño" in mismatches["short.bin"].detail


def test_classify_all_ok(tmp_path: Path) -> None:
    content = b"hello"
    (tmp_path / "a.bin").write_bytes(content)
    assert classify(tmp_path, [_expected("a.bin", content)]) == []
