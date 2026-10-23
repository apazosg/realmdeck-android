"""Offline tests for the fixed GLVND/Mesa EGL candidate seam."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
VIRGL = ROOT / "scripts/arm64-prototype/controls/virgl"
sys.path.insert(0, str(VIRGL))
import audit_payloads as audit  # noqa: E402
import materialize_candidate as materializer  # noqa: E402
import materialize_egl_candidate as egl  # noqa: E402


def _library(soname: str, needed: list[str] | None = None) -> dict[str, object]:
    return {
        "packagePath": f"data/data/com.termux/files/usr/lib/{soname}",
        "soname": soname,
        "needed": needed or [],
    }


def test_custom_egl_roots_are_exact_and_default_roots_are_preserved() -> None:
    components = [{"package": "fixture", "libraries": [_library("libGL.so.1"), _library("libEGL.so.1"), _library("libEGL_mesa.so"), _library("libgallium-test.so")]}]
    default = audit.build_closure(components)
    custom = audit.build_closure(components, roots=egl.ROOTS)
    assert default["roots"] == ["libGL.so.1", "libGLX_mesa.so", "libgallium-test.so"]
    assert custom["roots"] == list(egl.ROOTS)
    assert custom["entrypoint"] == "libEGL.so.1"


@pytest.mark.parametrize("root", ["", ".", "..", "../escape", "/absolute", "a/b", "a\\b", "a\x00b"])
def test_bad_root_names_are_rejected(root: str) -> None:
    with pytest.raises(audit.AuditError, match="root"):
        audit.build_closure([], roots=[root])


def test_vendor_asset_path_rejects_escape() -> None:
    with pytest.raises(materializer.MaterializerError, match="outside|unsafe"):
        materializer._candidate_asset_relative("data/data/com.termux/files/usr/share/../escape.json")


def test_offline_acquisition_refuses_missing_cache_without_network(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    record = materializer.LedgerPackage("fixture", "1", "aarch64", "pool/main/f/fixture.deb", "0" * 64, 1, None)
    monkeypatch.setattr(materializer, "_download_one", lambda *_args: pytest.fail("network/download attempted"))
    with pytest.raises(materializer.MaterializerError, match="missing pinned packages"):
        materializer.acquire_packages([record], tmp_path, offline_only=True)


def test_existing_output_is_refused(tmp_path: Path) -> None:
    output = tmp_path / "egl-loader"
    output.mkdir()
    with pytest.raises(materializer.MaterializerError, match="new"):
        materializer._ensure_output_dir(output)


def test_missing_custom_closure_is_not_materialized(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(materializer, "_read_existing", lambda *_args: b"{}")
    monkeypatch.setattr(materializer, "load_fixed_metadata", lambda _path: ({}, []))
    monkeypatch.setattr(materializer, "acquire_packages", lambda *_args, **_kwargs: [])
    monkeypatch.setattr(materializer, "_audit_records", lambda *_args: [])
    monkeypatch.setattr(
        audit,
        "build_closure",
        lambda *_args, **_kwargs: {"missingSonames": ["libEGL_mesa.so"], "resolved": []},
    )
    with pytest.raises(materializer.MaterializerError, match="missing SONAMEs"):
        materializer.materialize_candidate(tmp_path / "metadata", tmp_path, tmp_path / "out", roots=egl.ROOTS, offline_only=True)
    assert not (tmp_path / "out").exists()


def test_required_export_matcher_requires_defined_functions() -> None:
    text = "\n".join(
        [
            "     1: 0000000000000000     0 FUNC GLOBAL DEFAULT UND eglInitialize",
            "     2: 0000000000000100    32 FUNC GLOBAL DEFAULT   12 eglInitialize@@EGL_1.5",
            "     3: 0000000000000200     8 OBJECT GLOBAL DEFAULT   12 eglGetError",
        ]
    )
    records, missing = egl.match_required_exports(text, ["eglInitialize", "eglGetError"])
    assert [item["name"] for item in records] == ["eglInitialize"]
    assert missing == ["eglGetError"]


def test_hidden_local_and_zero_exports_do_not_establish_loader_abi() -> None:
    for bind, visibility, address in (("LOCAL", "DEFAULT", "100"), ("GLOBAL", "HIDDEN", "100"), ("GLOBAL", "DEFAULT", "0")):
        text = f"1: {address} 32 FUNC {bind} {visibility} 12 eglInitialize"
        records, missing = egl.match_required_exports(text, ["eglInitialize"])
        assert records == [] and missing == ["eglInitialize"]


def test_tree_digest_streams_without_changing_its_byte_contract(tmp_path: Path) -> None:
    import hashlib
    (tmp_path / "lib").mkdir()
    data = b"x" * 200_000
    (tmp_path / "lib/library").write_bytes(data)
    (tmp_path / "lib/alias").symlink_to("library")
    expected = hashlib.sha256()
    for path in sorted(tmp_path.rglob("*")):
        expected.update(path.relative_to(tmp_path).as_posix().encode() + b"\0")
        if path.is_symlink():
            expected.update(b"L\0library\0")
        elif path.is_file():
            expected.update(b"F\0" + data + b"\0")
        else:
            expected.update(b"D\0")
    assert egl._tree_sha256(tmp_path) == expected.hexdigest()


def test_manifest_keeps_loader_protocol_and_acceptance_unverified(tmp_path: Path) -> None:
    output = tmp_path / "candidate"
    output.mkdir()
    (output / "candidate-manifest.json").write_text("{}\n", encoding="utf-8")
    manifest = {"ready": True}
    egl._rewrite_manifest(output, manifest, [])
    written = __import__("json").loads((output / "candidate-manifest.json").read_text(encoding="utf-8"))
    assert written["privateLoaderResolution"]["status"] == "UNVERIFIED"
    assert written["protocol"]["status"] == "UNVERIFIED"
    assert written["ready"] is False
    assert written["present"] is False and written["d3d"] is False and written["wow"] is False
