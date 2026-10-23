"""Offline contract checks for the Windows PE/AA64 diagnostic probes."""

from pathlib import Path


ROOT = Path(__file__).parents[1]
PROBES = ROOT / "scripts/diagnostics/arm64-probes"


def test_arm64_probe_set_is_standalone_and_declares_aa64_contract() -> None:
    hello = (PROBES / "HelloArm64.c").read_text(encoding="utf-8")
    api = (PROBES / "ApiArm64.c").read_text(encoding="utf-8")
    dyn = (PROBES / "DynCodeArm64.c").read_text(encoding="utf-8")
    readme = (PROBES / "README.md").read_text(encoding="utf-8")

    assert "pe_machine=0xAA64" in hello
    assert "result = 42" in hello
    assert "InitializeCriticalSectionEx" in api
    assert "LoadLibraryExW" in api
    assert "LOAD_LIBRARY_SEARCH_SYSTEM32" in api
    assert "0xA9BF7BFD" in dyn
    assert "0xD65F03C0" in dyn
    assert "CreateFileMappingW" in dyn
    assert "FILE_MAP_READ | FILE_MAP_WRITE" in dyn
    assert "FILE_MAP_READ | FILE_MAP_EXECUTE" in dyn
    assert "expected=17" in dyn
    assert "expected=29" in dyn
    assert "STAGE-NOT-INCLUDED name=syscall-copied" in dyn
    assert "SHA256=bb7bb7654b33d5aa8712acb837c963b2e0c56352560c76105270a3268c665c21" in readme
    assert "IMAGE_FILE_MACHINE_ARM64" in readme
    assert "x86-64 MinGW" in readme


def test_arm64_probe_argument_contract_is_strict() -> None:
    common = (PROBES / "probe_common.h").read_text(encoding="utf-8")

    assert "--log" in common
    assert "--run-id" in common
    assert "probe_is_absolute_windows_path" in common
    assert "_commit(_fileno(log->file))" in common
    assert "STAGE-BEGIN name=%s" in common
    assert "STAGE-END name=%s returncode=%d" in common
    assert "return 2;" in common
