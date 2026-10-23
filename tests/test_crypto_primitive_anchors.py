from __future__ import annotations

import importlib.util
import struct
import sys
from pathlib import Path

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts/diagnostics/reverse-engineering/CryptoPrimitiveAnchors.py"
SPEC = importlib.util.spec_from_file_location("crypto_primitive_anchors", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def _synthetic_pe(machine: int, payload: bytes, *, section_rva: int = 0x1000) -> bytes:
    pe_offset = 0x80
    section_table = pe_offset + 4 + 20 + 0xF0
    raw_offset = 0x400
    image = bytearray(raw_offset + len(payload))
    image[:2] = b"MZ"
    struct.pack_into("<I", image, 0x3C, pe_offset)
    image[pe_offset : pe_offset + 4] = b"PE\0\0"
    struct.pack_into("<H", image, pe_offset + 4, machine)
    struct.pack_into("<H", image, pe_offset + 6, 1)
    struct.pack_into("<H", image, pe_offset + 20, 0xF0)
    optional = pe_offset + 24
    struct.pack_into("<H", image, optional, 0x20B)
    struct.pack_into("<Q", image, optional + 24, 0x140000000)
    struct.pack_into("<I", image, optional + 56, 0x3000)
    struct.pack_into("<I", image, optional + 60, 0x400)
    image[section_table : section_table + 8] = b".rdata\0\0"
    struct.pack_into("<I", image, section_table + 8, len(payload))
    struct.pack_into("<I", image, section_table + 12, section_rva)
    struct.pack_into("<I", image, section_table + 16, len(payload))
    struct.pack_into("<I", image, section_table + 20, raw_offset)
    struct.pack_into("<I", image, section_table + 36, 0x40000040)
    image[raw_offset:] = payload
    return bytes(image)


def _write(tmp_path: Path, data: bytes) -> Path:
    path = tmp_path / "synthetic.exe"
    path.write_bytes(data)
    return path


def _directed_fixture() -> tuple[bytearray, dict, list[dict], dict]:
    image_base = 0x140000000
    data = bytearray(0x500)
    sections = [
        {"name": ".text", "rva": 0x1000, "raw_offset": 0x100, "raw_size": 0x100},
        {"name": ".rdata", "rva": 0x9000, "raw_offset": 0x300, "raw_size": 0x100},
        {"name": ".data", "rva": 0xA000, "raw_offset": 0x400, "raw_size": 0x80},
    ]
    pe = {"machine": MODULE.ARM64_MACHINE, "image_base": image_base}
    anchor = {
        "family": "ed25519",
        "standard_name": "synthetic Ed25519 d",
        "file_offset": 0x340,
        "rva": 0x9040,
        "section": ".rdata",
        "validated_fingerprint_length": 32,
        "sequence_words": None,
    }
    return data, pe, sections, anchor


def _encode_adrp(pc_rva: int, target_rva: int, rd: int) -> int:
    delta_pages = ((target_rva & ~0xFFF) - (pc_rva & ~0xFFF)) // 0x1000
    imm21 = delta_pages & ((1 << 21) - 1)
    return 0x90000000 | ((imm21 & 3) << 29) | (((imm21 >> 2) & 0x7FFFF) << 5) | rd


def test_standard_constants_are_derived_and_discriminating() -> None:
    assert MODULE.sha512_round_constants()[:2] == (0x428A2F98D728AE22, 0x7137449123EF65CD)
    assert MODULE.sha256_round_constants()[:2] == (0x428A2F98, 0x71374491)
    ed = MODULE._ed25519_constants()
    assert ed["d"] == 37095705934669439343138083508754565189542113879843219016388785533085940283555
    assert ed["order"] == 7237005577332262213973186563042994240857116359379907606001950938285454250989
    assert (ed["basepoint_y"] | (ed["basepoint_x"] & 1) << 255).to_bytes(32, "little") == b"\x58" + b"\x66" * 31


def test_positive_sequence_and_pe_rva_translation(tmp_path: Path) -> None:
    constants = MODULE.sha512_round_constants()[:4]
    pattern = b"".join(struct.pack("<Q", value) for value in constants)
    file_offset = 0x19
    path = _write(tmp_path, _synthetic_pe(MODULE.ARM64_MACHINE, b"N" * file_offset + pattern))
    result = MODULE.analyze(path, MODULE.ARM64_MACHINE)
    hits = [item for item in result["candidates"] if item["family"] == "sha512"]
    assert hits
    assert hits[0]["encoding"] == "64-bit-LE"
    assert hits[0]["validated_fingerprint_length"] == 32
    assert hits[0]["rva"] == 0x1000 + file_offset
    assert hits[0]["section"] == ".rdata"


def test_negative_does_not_match_wrong_sequence(tmp_path: Path) -> None:
    constants = list(MODULE.sha512_round_constants()[:4])
    constants[-1] ^= 1
    payload = b"".join(struct.pack("<Q", value) for value in constants)
    result = MODULE.analyze(_write(tmp_path, _synthetic_pe(MODULE.ARM64_MACHINE, payload)), MODULE.ARM64_MACHINE)
    assert result["candidate_counts"] == {"sha512": 0, "sha256": 0, "ed25519": 0}


def test_explicit_big_endian_positive(tmp_path: Path) -> None:
    payload = b"".join(struct.pack(">Q", value) for value in MODULE.sha512_round_constants()[:4])
    result = MODULE.analyze(_write(tmp_path, _synthetic_pe(MODULE.X64_MACHINE, payload)), MODULE.X64_MACHINE)
    assert any(item["encoding"] == "64-bit-BE" for item in result["candidates"] if item["family"] == "sha512")


def test_wrong_architecture_is_rejected(tmp_path: Path) -> None:
    path = _write(tmp_path, _synthetic_pe(MODULE.ARM64_MACHINE, b"none"))
    with pytest.raises(ValueError, match="wrong architecture"):
        MODULE.analyze(path, MODULE.X64_MACHINE)


def test_ed25519_positive_and_linkage_is_not_claimed(tmp_path: Path) -> None:
    d = MODULE._ed25519_constants()["d"].to_bytes(32, "little")
    result = MODULE.analyze(_write(tmp_path, _synthetic_pe(MODULE.ARM64_MACHINE, d)), MODULE.ARM64_MACHINE)
    assert result["candidate_counts"]["ed25519"] >= 1
    assert "not trust proof" in result["interpretation"]["ed25519"]


def test_arm64_cross_section_adrp_add_ldr_uses_rva_and_fences_clobber() -> None:
    data, pe, sections, anchor = _directed_fixture()
    struct.pack_into("<I", data, 0x100, _encode_adrp(0x1000, 0x9040, 1))
    struct.pack_into("<I", data, 0x104, 0x91000000 | (0x40 << 10) | (1 << 5) | 1)
    struct.pack_into("<I", data, 0x108, 0xF9400000 | (1 << 5) | 2)
    # A second chain clobbers X3 before the ADD; it must not be associated.
    struct.pack_into("<I", data, 0x110, _encode_adrp(0x1010, 0x9040, 3))
    struct.pack_into("<I", data, 0x114, 0xAA0003E0 | (4 << 16) | (31 << 5) | 3)
    struct.pack_into("<I", data, 0x118, 0x91000000 | (0x40 << 10) | (3 << 5) | 3)
    refs = MODULE._arm64_directed_refs(data, pe, sections, [anchor])
    assert any(ref["instruction"] == "ADRP+ADD+LDR-unsigned" and ref["resolved_target_rva"] == 0x9040 for ref in refs)
    assert not any(ref["instruction"] == "ADRP+ADD+LDR-unsigned" and ref["instruction_rva"] == 0x1018 for ref in refs)
    assert all("function boundary" in ref["flow_caveat"] for ref in refs)


def test_x64_cross_section_rip_reference_uses_rva_and_data_classification() -> None:
    data, pe, sections, anchor = _directed_fixture()
    pe["machine"] = MODULE.X64_MACHINE
    target_rva = anchor["rva"]
    displacement = target_rva - (sections[0]["rva"] + 7)
    data[0x100:0x107] = b"\x48\x8b\x05" + struct.pack("<i", displacement)
    struct.pack_into("<Q", data, 0x400, pe["image_base"] + target_rva)
    struct.pack_into("<Q", data, 0x408, target_rva)
    refs = MODULE._x64_rip_relative_refs(data, pe, sections, [anchor])
    assert refs[0]["resolved_target_rva"] == target_rva
    assert refs[0]["status"] == "raw-pattern-hypothesis"
    reverse = MODULE._data_reverse_refs(data, pe, sections, [anchor])
    encodings = {item["encoding"] for item in reverse["references"]}
    assert encodings == {"raw-absolute-VA", "relative-RVA"}
