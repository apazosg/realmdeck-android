from __future__ import annotations

import hashlib
import io
import importlib.util
import os
import sys
import tarfile
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).parents[1] / "scripts/diagnostics/android-process/wine_prefix_template.py"
SPEC = importlib.util.spec_from_file_location("wine_prefix_template", MODULE_PATH)
assert SPEC and SPEC.loader
wine = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = wine
SPEC.loader.exec_module(wine)


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _forge_archive(path: Path, kind: str) -> None:
    normal = [
        (".wine", "directory", 0, 0o700, b"", ""),
        (".wine/dosdevices", "directory", 0, 0o700, b"", ""),
        (".wine/drive_c", "directory", 0, 0o700, b"", ""),
        (".wine/drive_c/windows", "directory", 0, 0o700, b"", ""),
        (".wine/drive_c/windows/system32", "directory", 0, 0o700, b"", ""),
        (".wine/system.reg", "regular", 5, 0o600, b"reg-a", ""),
        (".wine/dosdevices/c:", "symlink", 0, 0o777, b"", "../drive_c"),
        (".wine/dosdevices/d:", "symlink", 0, 0o777, b"", "/forbidden/d"),
        (".wine/dosdevices/e:", "symlink", 0, 0o777, b"", "/forbidden/e"),
        (".wine/dosdevices/z:", "symlink", 0, 0o777, b"", "/forbidden/z"),
    ]
    if kind == "unexpected-symlink":
        normal[6] = (normal[6][0], normal[6][1], normal[6][2], normal[6][3], normal[6][4], "../wrong")
    if kind == "hardlink":
        normal[5] = (normal[5][0], "hardlink", 0, normal[5][3], b"", ".wine/other")
    if kind == "fifo":
        normal[5] = (normal[5][0], "fifo", 0, normal[5][3], b"", "")
    if kind == "device":
        normal[5] = (normal[5][0], "device", 0, normal[5][3], b"", "")
    with tarfile.open(path, "w:xz") as archive:
        for name, member_type, size, mode, data, target in normal:
            info = tarfile.TarInfo(name)
            info.mode = mode
            info.size = size
            if member_type == "directory":
                info.type = tarfile.DIRTYPE
            elif member_type == "symlink":
                info.type = tarfile.SYMTYPE
                info.linkname = target
            elif member_type == "hardlink":
                info.type = tarfile.LNKTYPE
                info.linkname = target
            elif member_type == "fifo":
                info.type = tarfile.FIFOTYPE
            elif member_type == "device":
                info.type = tarfile.CHRTYPE
                info.devmajor = 1
                info.devminor = 3
            else:
                archive.addfile(info, io.BytesIO(data))
                continue
            archive.addfile(info)
        if kind == "duplicate":
            info = tarfile.TarInfo(".wine/system.reg")
            info.mode = 0o600
            info.size = 5
            archive.addfile(info, io.BytesIO(b"reg-a"))
        elif kind == "unlisted":
            info = tarfile.TarInfo(".wine/unlisted")
            info.mode = 0o600
            info.size = 4
            archive.addfile(info, io.BytesIO(b"nope"))
        elif kind == "traversal":
            info = tarfile.TarInfo("../escape")
            info.mode = 0o600
            info.size = 4
            archive.addfile(info, io.BytesIO(b"nope"))


def _fixture(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, *, bad_member: tuple[str, str] | None = None):
    payloads = {"compiler": b"compiler-aa64", "dxgi": b"dxgi-aa64", "d3d11": b"d3d11-aa64"}
    sources = {}
    for name, data in payloads.items():
        path = tmp_path / name
        path.write_bytes(data)
        sources[name] = path
    imagefs = tmp_path / "imagefs"
    probe = tmp_path / "probe"
    imagefs.mkdir(exist_ok=True)
    probe.mkdir(exist_ok=True)
    imagefs.chmod(0o700)
    probe.chmod(0o700)
    archive_path = tmp_path / "prefixPack.txz"
    members = [
        {"path": ".wine", "type": "directory", "bytes": 0, "mode": 0o700},
        {"path": ".wine/dosdevices", "type": "directory", "bytes": 0, "mode": 0o700},
        {"path": ".wine/drive_c", "type": "directory", "bytes": 0, "mode": 0o700},
        {"path": ".wine/drive_c/windows", "type": "directory", "bytes": 0, "mode": 0o700},
        {"path": ".wine/drive_c/windows/system32", "type": "directory", "bytes": 0, "mode": 0o700},
        {"path": ".wine/system.reg", "type": "regular", "bytes": 5, "mode": 0o600, "sha256": _digest(b"reg-a")},
        {"path": ".wine/dosdevices/c:", "type": "symlink", "bytes": 0, "mode": 0o777, "target": "../drive_c"},
        {"path": ".wine/dosdevices/d:", "type": "symlink", "bytes": 0, "mode": 0o777, "target": "/forbidden/d"},
        {"path": ".wine/dosdevices/e:", "type": "symlink", "bytes": 0, "mode": 0o777, "target": "/forbidden/e"},
        {"path": ".wine/dosdevices/z:", "type": "symlink", "bytes": 0, "mode": 0o777, "target": "/forbidden/z"},
    ]
    if bad_member:
        members[-1][bad_member[0]] = bad_member[1]
    archive_members = members
    with tarfile.open(archive_path, "w:xz") as archive:
        for item in archive_members:
            info = tarfile.TarInfo(item["path"])
            info.mode = item["mode"]
            info.size = item["bytes"]
            if item["type"] == "directory":
                info.type = tarfile.DIRTYPE
            elif item["type"] == "symlink":
                info.type = tarfile.SYMTYPE
                info.linkname = item["target"]
            else:
                data = b"reg-a"
                archive.addfile(info, __import__("io").BytesIO(data))
                continue
            archive.addfile(info)
    manifest = {
        "schema": "wowforever.wine-prefix-template/v1",
        "archive": {
            "name": "prefixPack.txz",
            "size_bytes": archive_path.stat().st_size,
            "sha256": _digest(archive_path.read_bytes()),
            "member_count": len(members),
            "typed_counts": {"directory": 5, "regular": 1, "symlink": 4},
            "regular_payload_bytes": 5,
            "members": members,
        },
        "reconstruction": {
            "links": [
                {"path": ".wine/dosdevices/a:", "target": "<probe_root>", "target_kind": "explicit_root"},
                {"path": ".wine/dosdevices/c:", "target": "../drive_c", "target_kind": "relative"},
                {"path": ".wine/dosdevices/z:", "target": "<imagefs_root>", "target_kind": "explicit_root"},
            ]
        },
        "preserved_registries": [{"path": ".wine/system.reg", "bytes": 5, "mode": 0o600, "sha256": _digest(b"reg-a")}],
        "replacements": [
            {"path": ".wine/drive_c/windows/system32/d3dcompiler_47.dll", "source": "compiler", "bytes": len(payloads["compiler"]), "mode": 0o644, "sha256": _digest(payloads["compiler"])},
            {"path": ".wine/drive_c/windows/system32/dxgi.dll", "source": "dxgi", "bytes": len(payloads["dxgi"]), "mode": 0o755, "sha256": _digest(payloads["dxgi"])},
            {"path": ".wine/drive_c/windows/system32/d3d11.dll", "source": "d3d11", "bytes": len(payloads["d3d11"]), "mode": 0o755, "sha256": _digest(payloads["d3d11"])},
        ],
    }
    monkeypatch.setattr(wine, "_ALLOW_TEST_MANIFEST", True)
    monkeypatch.setattr(wine, "ARCHIVE_SIZE", archive_path.stat().st_size)
    monkeypatch.setattr(wine, "ARCHIVE_SHA256", manifest["archive"]["sha256"])
    return archive_path, sources, imagefs, probe, manifest


def _materialize(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, destination_name: str = "prefix"):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    destination = tmp_path / destination_name
    result = wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], destination, imagefs, probe, manifest)
    return result, destination, imagefs, probe


def _forged_case(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, kind: str):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    _forge_archive(archive, kind)
    manifest["archive"]["size_bytes"] = archive.stat().st_size
    manifest["archive"]["sha256"] = _digest(archive.read_bytes())
    monkeypatch.setattr(wine, "ARCHIVE_SIZE", manifest["archive"]["size_bytes"])
    monkeypatch.setattr(wine, "ARCHIVE_SHA256", manifest["archive"]["sha256"])
    return archive, sources, imagefs, probe, manifest


def test_deterministic_two_fresh_materializations_and_modes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    first, destination, imagefs, probe = _materialize(tmp_path, monkeypatch, "prefix-a")
    second, destination2, _, _ = _materialize(tmp_path, monkeypatch, "prefix-b")
    assert first == destination and second == destination2
    assert (destination / ".wine/system.reg").read_bytes() == b"reg-a"
    assert (destination / ".wine/drive_c/windows/system32/dxgi.dll").read_bytes() == b"dxgi-aa64"
    assert (destination / ".wine/drive_c/windows/system32/dxgi.dll").stat().st_mode & 0o777 == 0o755
    assert os.readlink(destination / ".wine/dosdevices/c:") == "../drive_c"
    assert os.readlink(destination / ".wine/dosdevices/a:") == str(probe.resolve())


def test_mutated_registry_and_source_pin_are_rejected_without_publish(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    manifest["archive"]["members"][5]["sha256"] = _digest(b"wrong")
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, probe, manifest)
    assert not (tmp_path / "prefix").exists()
    sources["compiler"].write_bytes(b"changed")
    fresh_dir = tmp_path / "fresh"
    fresh_dir.mkdir()
    fresh_archive, fresh_sources, fresh_imagefs, fresh_probe, fresh = _fixture(fresh_dir, monkeypatch)
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(fresh_archive, sources["compiler"], fresh_sources["dxgi"], fresh_sources["d3d11"], tmp_path / "prefix-2", fresh_imagefs, fresh_probe, fresh)


@pytest.mark.parametrize("bad", [("path", "../escape"), ("type", "hardlink"), ("type", "special")])
def test_malformed_member_classes_and_path_traversal_are_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, bad):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch, bad_member=bad)
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, probe, manifest)
    assert not (tmp_path / "escape").exists()


def test_existing_destination_is_never_replaced_or_cleaned(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    destination = tmp_path / "prefix"
    destination.mkdir()
    sentinel = destination / "sentinel"
    sentinel.write_text("foreign")
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], destination, imagefs, probe, manifest)
    assert sentinel.read_text() == "foreign"


def test_missing_or_overlapping_roots_stop_before_stage(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, imagefs, manifest)
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", tmp_path / "missing", probe, manifest)


@pytest.mark.parametrize("kind", ["duplicate", "unlisted", "traversal", "unexpected-symlink", "hardlink", "fifo", "device"])
def test_forged_tar_members_are_rejected_after_manifest_seam(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, kind: str):
    archive, sources, imagefs, probe, manifest = _forged_case(tmp_path, monkeypatch, kind)
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, probe, manifest)
    assert not (tmp_path / "escape").exists()


@pytest.mark.parametrize("raw", ["a/./b", "a//b", "../b", "/absolute", "a/../b"])
def test_safe_relative_rejects_noncanonical_components(raw: str):
    with pytest.raises(wine.PrefixTemplateError):
        wine._safe_relative(raw)


def test_source_ancestor_symlink_is_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    real = tmp_path / "real"
    real.mkdir()
    moved = real / "compiler"
    moved.write_bytes(sources["compiler"].read_bytes())
    sources["compiler"].unlink()
    os.symlink(real, tmp_path / "linked")
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, tmp_path / "linked/compiler", sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, probe, manifest)


def test_source_change_during_hash_is_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    original = wine._hash_file

    def mutate_after_hash(path: Path, expected_size: int | None = None):
        value = original(path, expected_size)
        if path == sources["compiler"]:
            path.write_bytes(b"mutated")
        return value

    monkeypatch.setattr(wine, "_hash_file", mutate_after_hash)
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, probe, manifest)
    assert not (tmp_path / "prefix").exists()


def test_fsync_failure_cleans_only_owned_stage(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    foreign = tmp_path / "foreign"
    foreign.write_text("keep")

    def fail_fsync(_fd: int):
        raise OSError("injected fsync failure")

    monkeypatch.setattr(wine.os, "fsync", fail_fsync)
    with pytest.raises(OSError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, probe, manifest)
    assert foreign.read_text() == "keep"
    assert not (tmp_path / "prefix").exists()


def test_destination_appearing_during_noreplace_is_not_cleaned(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)

    def race(state, destination):
        destination.mkdir()
        (destination / "foreign").write_text("keep")
        raise wine.PrefixTemplateError("destination appeared during publish")

    monkeypatch.setattr(wine, "_rename_noreplace", race)
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, probe, manifest)
    assert (tmp_path / "prefix/foreign").read_text() == "keep"


def test_parent_replaced_by_symlink_is_never_used_for_publish(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    parent = tmp_path / "publish-parent"
    parent.mkdir()
    parent.chmod(0o700)
    foreign = tmp_path / "foreign-parent"
    foreign.mkdir()
    foreign.chmod(0o700)
    destination = parent / "prefix"
    original_rename = wine._rename_noreplace

    def replace_parent(state, target):
        moved = tmp_path / "moved-parent"
        os.rename(parent, moved)
        os.symlink(foreign, parent)
        return original_rename(state, target)

    monkeypatch.setattr(wine, "_rename_noreplace", replace_parent)
    with pytest.raises(wine.PrefixTemplateError):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], destination, imagefs, probe, manifest)
    assert not (foreign / "prefix").exists()
    assert not (foreign / "prefix/foreign").exists()
    assert (tmp_path / "moved-parent").is_dir()


def test_stage_identity_swap_refuses_cleanup(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, manifest = _fixture(tmp_path, monkeypatch)
    swapped = tmp_path / "swapped-stage"
    swapped.mkdir()
    original_sync = wine._sync_tree

    def swap(state_path: Path):
        original_sync(state_path)
        stage_fd = int(state_path.name)
        foreign_fd = os.open(swapped, os.O_RDONLY | os.O_DIRECTORY)
        os.dup2(foreign_fd, stage_fd)
        os.close(foreign_fd)

    monkeypatch.setattr(wine, "_sync_tree", swap)
    with pytest.raises(wine.PrefixTemplateError, match="identity changed"):
        wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], tmp_path / "prefix", imagefs, probe, manifest)
    assert swapped.is_dir()


def test_read_only_verifier_detects_registry_tamper_and_extra_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, fixture_manifest = _fixture(tmp_path, monkeypatch)
    destination = tmp_path / "prefix"
    wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], destination, imagefs, probe, fixture_manifest)
    # The production verifier remains canonical-only; this replaces its read-only
    # authority with the already-tested small fixture without opening a CLI seam.
    monkeypatch.setattr(wine, "_load_manifest", lambda: fixture_manifest)
    wine.verify_materialized(destination, imagefs, probe)
    (destination / ".wine/system.reg").write_bytes(b"tampered")
    with pytest.raises(wine.PrefixTemplateError):
        wine.verify_materialized(destination, imagefs, probe)
    (destination / ".wine/system.reg").write_bytes(b"reg-a")
    (destination / ".wine/extra").write_text("extra")
    with pytest.raises(wine.PrefixTemplateError):
        wine.verify_materialized(destination, imagefs, probe)


def test_verifier_requires_private_root_and_stable_identity(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, fixture_manifest = _fixture(tmp_path, monkeypatch)
    destination = tmp_path / "prefix"
    wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], destination, imagefs, probe, fixture_manifest)
    monkeypatch.setattr(wine, "_load_manifest", lambda: fixture_manifest)
    destination.chmod(0o755)
    with pytest.raises(wine.PrefixTemplateError):
        wine.verify_materialized(destination, imagefs, probe)
    destination.chmod(0o700)
    real_open = wine._open_private_directory

    def foreign_uid(path, label, *, exact_mode=None):
        fd, identity = real_open(path, label, exact_mode=exact_mode)
        if label == "materialized destination":
            identity = wine._PathIdentity(identity.dev, identity.ino, os.getuid() + 1, identity.gid, identity.mode)
        return fd, identity

    monkeypatch.setattr(wine, "_open_private_directory", foreign_uid)
    with pytest.raises(wine.PrefixTemplateError):
        wine.verify_materialized(destination, imagefs, probe)


def test_verifier_fails_closed_on_destination_identity_change(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, sources, imagefs, probe, fixture_manifest = _fixture(tmp_path, monkeypatch)
    destination = tmp_path / "prefix"
    wine._materialize_for_test(archive, sources["compiler"], sources["dxgi"], sources["d3d11"], destination, imagefs, probe, fixture_manifest)
    monkeypatch.setattr(wine, "_load_manifest", lambda: fixture_manifest)
    original = wine._assert_fd_identity
    calls = {"destination": 0}

    def swap(fd, identity, label, *, directory=True):
        if label == "materialized destination":
            calls["destination"] += 1
            if calls["destination"] > 1:
                raise wine.PrefixTemplateError("materialized destination fd identity changed")
        return original(fd, identity, label, directory=directory)

    monkeypatch.setattr(wine, "_assert_fd_identity", swap)
    with pytest.raises(wine.PrefixTemplateError, match="identity changed"):
        wine.verify_materialized(destination, imagefs, probe)


def test_tar_descriptor_replacement_rejected_even_with_same_bytes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, _, _, _, _ = _fixture(tmp_path, monkeypatch)
    pinned_identity = wine._verify_archive_pin(archive)
    replacement = tmp_path / "replacement.txz"
    replacement.write_bytes(archive.read_bytes())
    os.replace(replacement, archive)
    with pytest.raises(wine.PrefixTemplateError, match="descriptor"):
        with wine._open_tar_stream(archive, pinned_identity):
            pytest.fail("replacement descriptor was admitted")


def test_tar_descriptor_content_authenticated_without_path_reopen(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    archive, _, _, _, _ = _fixture(tmp_path, monkeypatch)
    pinned_identity = wine._verify_archive_pin(archive)
    monkeypatch.setattr(wine, "ARCHIVE_SHA256", "0" * 64)
    with pytest.raises(wine.PrefixTemplateError, match="content authentication"):
        with wine._open_tar_stream(archive, pinned_identity):
            pytest.fail("unverified compressed bytes were admitted")


def test_canonical_manifest_symlink_rejected_before_content_check(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    target = tmp_path / "actual-manifest.json"
    target.write_bytes(wine._manifest_path().read_bytes())
    link = tmp_path / "manifest.json"
    link.symlink_to(target)
    monkeypatch.setattr(wine, "_manifest_path", lambda: link)
    with pytest.raises(wine.PrefixTemplateError):
        wine._load_manifest()


def test_cli_help_and_manifest_override_are_constrained(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]):
    monkeypatch.setattr(sys, "argv", ["wine_prefix_template.py"])
    assert wine._cli() == 0
    assert "usage:" in capsys.readouterr().out
    monkeypatch.setattr(sys, "argv", ["wine_prefix_template.py", "materialize", "--manifest", "x"])
    with pytest.raises(SystemExit):
        wine._cli()
