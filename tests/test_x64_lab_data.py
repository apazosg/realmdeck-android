"""Safety gates for the offline x64 lab-data preparation helper."""

from __future__ import annotations

import importlib.util
import hashlib
from types import SimpleNamespace
from pathlib import Path

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts/diagnostics/reverse-engineering/x64-certstore-trace/prepare_lab_data.py"
SPEC = importlib.util.spec_from_file_location("prepare_lab_data", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
LAB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAB)


def _source_tree(root: Path) -> Path:
    source = root / "source"
    (source / "Data" / "data").mkdir(parents=True)
    (source / "WTF").mkdir()
    (source / "WowB.exe").write_bytes(b"synthetic executable")
    (source / "WTF" / "personal.txt").write_text("not copied", encoding="utf-8")
    (source / "Data" / "data" / "data.000").write_bytes(b"archive")
    (source / "Data" / "data" / "0000000001.idx").write_bytes(b"index")
    return source


def test_private_paths_are_excluded_and_admitted_copy_is_verified(tmp_path: Path) -> None:
    source = _source_tree(tmp_path)
    destination = tmp_path / "destination"
    result = LAB._copy_and_verify(source, destination)

    assert (destination / "WowB.exe").read_bytes() == b"synthetic executable"
    assert not (destination / "WTF").exists()
    assert not (Path(result["sanitization"]["source_view"]) / "WTF").exists()
    assert result["copy_equality_verified"] is True
    assert result["sanitization"]["excluded_path_count"] == 0


def test_source_symlink_is_refused_before_copy(tmp_path: Path) -> None:
    source = _source_tree(tmp_path)
    (source / "WTF-link").symlink_to(source / "WTF", target_is_directory=True)

    with pytest.raises(LAB.PreparationError, match="symlink"):
        LAB._copy_and_verify(source, tmp_path / "destination")


def test_existing_destination_is_never_overwritten(tmp_path: Path) -> None:
    source = _source_tree(tmp_path)
    destination = tmp_path / "destination"
    destination.mkdir()

    with pytest.raises(FileExistsError):
        LAB._copy_and_verify(source, destination)


def test_source_metadata_mutation_is_fail_closed() -> None:
    before = {"entry_count": 1, "sha256": "before"}
    after = {"entry_count": 1, "sha256": "after"}

    with pytest.raises(LAB.PreparationError, match="metadata inventory"):
        LAB.assert_inventory_stable(before, after)


def test_unexpected_missing_header_set_is_refused() -> None:
    audit = {
        "status": "needs-review",
        "payload_bytes_read": 0,
        "payload_integrity_verified": False,
        "index_parse_errors": {},
        "summary": {
            "duplicate_reconstruction_keys": 0,
            "truncated_prefix_collisions": 0,
            "uncovered_slots": [
                {"archive_id": 18, "slot": 0, "status": "missing_index_reference"},
            ],
        },
    }

    with pytest.raises(LAB.PreparationError, match="unexpected missing"):
        LAB._require_source_audit(audit)


def _manifest_item(path: str, data: bytes, *, md5: bytes | None = None, size: int | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        path=path,
        md5=hashlib.md5(data).digest() if md5 is None else md5,
        size=len(data) if size is None else size,
    )


def test_missing_manifest_utils_dependency_is_copied_and_verified(tmp_path: Path) -> None:
    source = tmp_path / "source"
    destination = tmp_path / "destination"
    source.mkdir()
    destination.mkdir()
    data = b"technical dependency"
    item = _manifest_item("Utils/required.dll", data)
    (source / "Utils").mkdir()
    (source / item.path).write_bytes(data)

    result = LAB._copy_verified_manifest_files(source, destination, [item])

    assert result["copied_count"] == 1
    assert (destination / item.path).read_bytes() == data
    assert result["files"][0]["md5"] == hashlib.md5(data).hexdigest()


@pytest.mark.parametrize(
    ("bad_md5", "bad_size"),
    [(b"\x00" * 16, None), (None, 999)],
)
def test_manifest_source_wrong_md5_or_size_fails_closed(tmp_path: Path, bad_md5: bytes | None, bad_size: int | None) -> None:
    source = tmp_path / "source"
    destination = tmp_path / "destination"
    source.mkdir()
    destination.mkdir()
    data = b"dependency"
    (source / "Utils").mkdir()
    (source / "Utils" / "required.dll").write_bytes(data)
    item = _manifest_item("Utils/required.dll", data, md5=bad_md5, size=bad_size)

    with pytest.raises(LAB.PreparationError, match="public manifest (MD5|size) mismatch"):
        LAB._copy_verified_manifest_files(source, destination, [item])
    assert not (destination / "Utils" / "required.dll").exists()


@pytest.mark.parametrize("path", ["../secret.bin", "WTF/secret.bin", "/absolute.bin"])
def test_manifest_escape_or_private_path_is_refused(tmp_path: Path, path: str) -> None:
    with pytest.raises(LAB.PreparationError, match="(escapes|private|canonical)"):
        LAB._manifest_relative(path)


def test_existing_mismatched_manifest_dependency_is_not_overwritten(tmp_path: Path) -> None:
    source = tmp_path / "source"
    destination = tmp_path / "destination"
    source.mkdir()
    destination.mkdir()
    expected_data = b"approved dependency"
    old_data = b"unexpected dependency"
    (source / "Utils").mkdir()
    (destination / "Utils").mkdir()
    (source / "Utils" / "required.dll").write_bytes(expected_data)
    (destination / "Utils" / "required.dll").write_bytes(old_data)
    item = _manifest_item("Utils/required.dll", expected_data)

    with pytest.raises(LAB.PreparationError, match="existing manifest dependency mismatch"):
        LAB._copy_verified_manifest_files(source, destination, [item])
    assert (destination / "Utils" / "required.dll").read_bytes() == old_data
