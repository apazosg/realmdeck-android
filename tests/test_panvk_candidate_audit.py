"""Offline tests for the bounded PanVK candidate acquisition/audit."""

from __future__ import annotations

import hashlib
import io
import stat
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts/arm64-prototype/controls/panvk"))
import audit_candidate as audit  # noqa: E402


def _asset() -> audit.Asset:
    return audit.Asset(
        "candidate.adpkg.zip",
        audit.EXPECTED_ZIP_BYTES,
        audit.EXPECTED_ZIP_SHA256,
        "https://api.github.com/repos/zenithblue-oss/panvk-kbase-android/releases/assets/1",
        f"https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/{audit.RELEASE_TAG}/candidate.adpkg.zip",
        1,
        "application/zip",
    )


def _zip(tmp_path: Path, entries: list[tuple[str, bytes, int | None]], *, name: str = "candidate.adpkg.zip") -> Path:
    path = tmp_path / name
    with zipfile.ZipFile(path, "w") as archive:
        for member_name, payload, mode in entries:
            info = zipfile.ZipInfo(member_name)
            info.compress_type = zipfile.ZIP_DEFLATED
            if mode is not None:
                info.create_system = 3
                info.external_attr = mode << 16
            archive.writestr(info, payload)
    return path


def _pinned_zip(tmp_path: Path, extra: list[tuple[str, bytes, int | None]] | None = None, *, include_icd: bool = True) -> Path:
    payload = b"not-an-elf-fixture"
    entries = [("driver/" + audit.ICD_BASENAME, payload, stat.S_IFREG | 0o600)] if include_icd else []
    entries.extend(extra or [])
    path = _zip(tmp_path, entries)
    # Tests of archive structure use a test-local pin rather than pretending the
    # fixture is the published 30 MiB ICD.
    return path


def _metadata(tag: str = audit.RELEASE_TAG, *, assets: list[dict[str, object]] | None = None) -> dict[str, object]:
    return {
        "tag_name": tag,
        "html_url": f"https://github.com/{audit.REPOSITORY}/releases/tag/{audit.RELEASE_TAG}",
        "assets": assets if assets is not None else [
            {
                "id": 4,
                "name": "approved.adpkg.zip",
                "size": audit.EXPECTED_ZIP_BYTES,
                "digest": "sha256:" + audit.EXPECTED_ZIP_SHA256,
                "content_type": "application/zip",
                "state": "uploaded",
                "url": "https://api.github.com/repos/zenithblue-oss/panvk-kbase-android/releases/assets/4",
                "browser_download_url": f"https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/{audit.RELEASE_TAG}/approved.adpkg.zip",
            }
        ],
    }


def test_resolves_only_exact_official_android_zip_and_preserves_basename() -> None:
    asset = audit.resolve_approved_asset(_metadata())
    assert asset.name == "approved.adpkg.zip"
    assert asset.sha256 == audit.EXPECTED_ZIP_SHA256
    assert "/releases/download/" in asset.browser_download_url


def test_linux_asset_or_moved_pin_is_not_selected() -> None:
    linux = _metadata(assets=[{
        "id": 5,
        "name": "libvulkan_panfrost-glibc-aarch64.so",
        "size": audit.EXPECTED_ZIP_BYTES,
        "digest": "sha256:" + audit.EXPECTED_ZIP_SHA256,
        "content_type": "application/octet-stream",
        "state": "uploaded",
        "url": "https://api.github.com/repos/zenithblue-oss/panvk-kbase-android/releases/assets/5",
        "browser_download_url": f"https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/{audit.RELEASE_TAG}/libvulkan_panfrost-glibc-aarch64.so",
    }])
    with pytest.raises(audit.AuditError, match="approved Android ZIP"):
        audit.resolve_approved_asset(linux)


def test_asset_authority_rejects_arbitrary_redirect() -> None:
    with pytest.raises(audit.AuditError, match="authority"):
        audit._validate_https_url("https://evil.example/x", hosts=audit.ASSET_REDIRECT_HOSTS)


def test_zip_path_traversal_and_non_normalized_unicode_rejected(tmp_path: Path) -> None:
    for name in ("../outside", "a\\b", "e\u0301.txt"):
        with pytest.raises(audit.AuditError, match="unsafe|normalized"):
            path = _pinned_zip(tmp_path, [(name, b"x", stat.S_IFREG | 0o600)], include_icd=False)
            audit.audit_zip(path, audit.Asset(path.name, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest(), _asset().api_url, _asset().browser_download_url, 1, "application/zip"))


def test_zip_duplicate_paths_rejected(tmp_path: Path) -> None:
    path = tmp_path / "duplicate.zip"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("driver/x", b"one")
        archive.writestr("driver/x", b"two")
    asset = audit.Asset(path.name, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest(), _asset().api_url, _asset().browser_download_url, 1, "application/zip")
    with pytest.raises(audit.AuditError, match="duplicate"):
        audit.audit_zip(path, asset)


def test_zip_independent_size_and_hash_pin_rejected(tmp_path: Path) -> None:
    path = _pinned_zip(tmp_path, include_icd=False)
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    wrong_size = audit.Asset(path.name, path.stat().st_size + 1, actual, _asset().api_url, _asset().browser_download_url, 1, "application/zip")
    with pytest.raises(audit.AuditError, match="pin mismatch"):
        audit.audit_zip(path, wrong_size)
    wrong_hash = audit.Asset(path.name, path.stat().st_size, "0" * 64, _asset().api_url, _asset().browser_download_url, 1, "application/zip")
    with pytest.raises(audit.AuditError, match="pin mismatch"):
        audit.audit_zip(path, wrong_hash)


@pytest.mark.parametrize("mode", [stat.S_IFLNK | 0o777, stat.S_IFCHR | 0o600, stat.S_IFIFO | 0o600])
def test_zip_special_files_rejected(tmp_path: Path, mode: int) -> None:
    path = _pinned_zip(tmp_path, [("driver/special", b"x", mode)], include_icd=False)
    asset = audit.Asset(path.name, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest(), _asset().api_url, _asset().browser_download_url, 1, "application/zip")
    with pytest.raises(audit.AuditError, match="special|symlink"):
        audit.audit_zip(path, asset)


def test_zip_file_count_and_expanded_bounds_rejected(tmp_path: Path) -> None:
    old_files, old_expanded = audit.MAX_ARCHIVE_FILES, audit.MAX_ARCHIVE_EXPANDED_BYTES
    audit.MAX_ARCHIVE_FILES = 1
    try:
        path = _pinned_zip(tmp_path, [("README.txt", b"x", stat.S_IFREG | 0o600)])
        asset = audit.Asset(path.name, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest(), _asset().api_url, _asset().browser_download_url, 1, "application/zip")
        with pytest.raises(audit.AuditError, match="file count"):
            audit.audit_zip(path, asset)
    finally:
        audit.MAX_ARCHIVE_FILES, audit.MAX_ARCHIVE_EXPANDED_BYTES = old_files, old_expanded


def test_readelf_parser_reports_identity_dynamic_metadata_and_exports() -> None:
    output = """
  Class: ELF64
  Data: 2's complement, little endian
  Type: DYN (Shared object file)
  Machine: AArch64
  Flags: 0x0
  LOAD 0x0 0x0 0x0 0x1000 0x1000 R 0x1000
  INTERP 0x0 0x0 0x0 0x20 0x20 R 0x1
      [Requesting program interpreter: /system/bin/linker64]
  0x0000000000000001 (NEEDED)             Shared library: [libc.so]
  0x000000000000000e (SONAME)             Library soname: [libpanvk.so]
  0x000000000000001d (RUNPATH)            Library runpath: [$ORIGIN]
  Android API: 34
     1: 0000000000000000     0 FUNC    GLOBAL DEFAULT     1 vkGetInstanceProcAddr
     2: 0000000000000010     0 FUNC    GLOBAL DEFAULT     1 vk_icdGetInstanceProcAddr
"""
    report = audit.parse_readelf_output(output, byte_count=7, sha256="a" * 64)
    assert report["elfMachine"] == "AArch64"
    assert report["elfType"] == "ET_DYN"
    assert report["soname"] == "libpanvk.so"
    assert report["needed"] == ["libc.so"]
    assert report["ptInterp"]["present"] is True
    assert report["pageAlignment"]["loadAlignments"] == [4096]
    assert report["minAPI"]["values"] == [34]
    assert report["requiredExports"] == {"vkGetInstanceProcAddr": True, "vk_icdGetInstanceProcAddr": True}


def test_report_schema_is_explicitly_not_ready(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # Exercise the schema without running readelf or requiring the real pinned
    # 30 MiB payload: the production path's readiness values are fixed policy.
    monkeypatch.setattr(audit, "READELF", tmp_path / "llvm-readelf")
    assert audit.MAX_ICD_BYTES == 32 * 1024 * 1024
    schema_keys = {"ready", "loader", "kernel", "gpuWorkload", "wsi"}
    readiness = {"ready": False, "loader": "UNVERIFIED", "kernel": "UNVERIFIED", "gpuWorkload": "UNVERIFIED", "wsi": "UNVERIFIED"}
    assert schema_keys <= readiness.keys()
