from pathlib import Path
import importlib.util
import struct

import pytest


ROOT = Path(__file__).parents[1]
RECIPE = ROOT / "scripts/arm64-prototype/native/build_functional_capture.sh"
AUDIT = ROOT / "scripts/arm64-prototype/native/elf16k_audit.py"
SPEC = importlib.util.spec_from_file_location("elf16k_audit", AUDIT)
assert SPEC and SPEC.loader
elf16k_audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(elf16k_audit)


def test_capture_build_recipe_is_offline_api29_external_and_verifies_16k_loads() -> None:
    text = RECIPE.read_text(encoding="utf-8")
    assert "CARGO_NET_OFFLINE=true" in text
    assert "aarch64-linux-android29-clang" in text
    assert "max-page-size=16384" in text
    assert "elf16k_audit.py" in text
    assert "capture_provenance.py" in text
    assert "--phase before" in text
    assert "--phase after" in text
    assert "--rustc" in text and "--cargo" in text and "--strip" in text
    assert "/home/adrian/wowforever-artifacts/arm64-prototype" in text
    assert "CARGO_TARGET_DIR" in text
    assert "WowB" not in text
    assert "adb" not in text


def test_recipe_elf_audit_checks_class_machine_and_every_load_alignment() -> None:
    text = RECIPE.read_text(encoding="utf-8")
    assert "elf16k_audit.py" in text
    assert 'before_core = {key: value for key, value in before.items() if key not in {"phase", "nativeElf"}}' in text


def _elf(load_alignment: int) -> bytes:
    data = bytearray(64 + 56)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<H", data, 54, 56)
    struct.pack_into("<H", data, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 0, 0, load_alignment)
    return bytes(data)


def test_elf_audit_uses_exact_elf64_program_header_offsets() -> None:
    assert elf16k_audit.audit_elf(_elf(0x4000))[0]["align"] == 0x4000
    with pytest.raises(elf16k_audit.ElfAuditError):
        elf16k_audit.audit_elf(_elf(0x1000))
