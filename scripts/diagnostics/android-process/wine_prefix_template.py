#!/usr/bin/env python3
"""Materialize the audited, private Wine prefix handoff.

This is a host-side file materializer only.  It never starts Wine, reads a
shared prefix, invokes a process, or talks to a device.  The JSON beside this
module is the authority for the archive tree; the digest below is deliberately
hard-coded so the authority cannot silently attest to itself.
"""

from __future__ import annotations

import argparse
import ctypes
import errno
import hashlib
import json
import lzma
import os
import stat
import tarfile
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import BinaryIO, Iterator


ARCHIVE_SHA256 = "ade99dc80b17086a269c038c9bc50167902103a9a61df763cba4d613543ff12a"
ARCHIVE_SIZE = 22_067_952
MANIFEST_SHA256 = "306bed4ade9aac21809919f1b1272cdbc9c389e77704a13374033dcee7b0bcfa"
MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
MAX_MEMBER_BYTES = 512 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
_ALLOW_TEST_MANIFEST = False


class PrefixTemplateError(RuntimeError):
    """A source, archive, destination, or final-tree contract violation."""


@dataclass(frozen=True)
class _PathIdentity:
    dev: int
    ino: int
    uid: int
    gid: int
    mode: int


@dataclass(frozen=True)
class _StageState:
    path: Path
    identity: _PathIdentity
    parent: Path
    parent_identity: _PathIdentity
    parent_fd: int
    stage_fd: int
    stage_name: str


def _identity(info: os.stat_result) -> _PathIdentity:
    return _PathIdentity(info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_mode & 0o7777)


def _raw_absolute(path: Path, label: str) -> None:
    raw = os.fspath(path)
    if not os.path.isabs(raw) or os.path.normpath(raw) != raw:
        raise PrefixTemplateError(f"{label} is not canonically spelled: {path}")


def _proc_fd_number(path: Path) -> int | None:
    parts = path.parts
    if len(parts) == 5 and parts[1:4] == ("proc", "self", "fd") and parts[4].isdigit():
        return int(parts[4])
    return None


def _assert_no_symlink_ancestors(path: Path, label: str, *, allow_missing_leaf: bool = False) -> None:
    _raw_absolute(path, label)
    fd_number = _proc_fd_number(path)
    if fd_number is not None:
        try:
            os.fstat(fd_number)
        except OSError as exc:
            raise PrefixTemplateError(f"{label} fd anchor is unavailable: {path}") from exc
        return
    parts = path.parts
    proc_anchor = len(parts) >= 5 and parts[1:4] == ("proc", "self", "fd") and parts[4].isdigit()
    current = Path(path.anchor)
    raw_parts = parts[5:] if proc_anchor else parts[1:]
    if proc_anchor:
        current = Path("/proc/self/fd") / parts[4]
    for index, part in enumerate(raw_parts):
        current /= part
        try:
            info = current.lstat()
        except FileNotFoundError:
            if allow_missing_leaf and index == len(parts) - 1:
                return
            raise PrefixTemplateError(f"{label} has a missing ancestor: {current}")
        if stat.S_ISLNK(info.st_mode):
            raise PrefixTemplateError(f"{label} follows a symlink ancestor: {current}")
        if index != len(raw_parts) - 1 and not stat.S_ISDIR(info.st_mode):
            raise PrefixTemplateError(f"{label} has a non-directory ancestor: {current}")


def _assert_identity(path: Path, expected: _PathIdentity, label: str, *, directory: bool = True) -> os.stat_result:
    _assert_no_symlink_ancestors(path, label)
    try:
        info = path.lstat()
    except OSError as exc:
        raise PrefixTemplateError(f"{label} disappeared: {path}") from exc
    if (directory and not stat.S_ISDIR(info.st_mode)) or (not directory and not stat.S_ISREG(info.st_mode)):
        raise PrefixTemplateError(f"{label} changed type: {path}")
    if stat.S_ISLNK(info.st_mode) or _identity(info) != expected:
        raise PrefixTemplateError(f"{label} identity changed: {path}")
    return info


def _assert_fd_identity(fd: int, expected: _PathIdentity, label: str, *, directory: bool = True) -> os.stat_result:
    try:
        info = os.fstat(fd)
    except OSError as exc:
        raise PrefixTemplateError(f"{label} fd disappeared") from exc
    if (directory and not stat.S_ISDIR(info.st_mode)) or (not directory and not stat.S_ISREG(info.st_mode)):
        raise PrefixTemplateError(f"{label} fd changed type")
    if stat.S_ISLNK(info.st_mode) or _identity(info) != expected:
        raise PrefixTemplateError(f"{label} fd identity changed")
    return info


def _open_private_directory(path: Path, label: str, *, exact_mode: int | None = None) -> tuple[int, _PathIdentity]:
    _assert_no_symlink_ancestors(path, label)
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    info = os.fstat(fd)
    if info.st_uid != os.getuid() or (exact_mode is not None and (info.st_mode & 0o7777) != exact_mode):
        os.close(fd)
        raise PrefixTemplateError(f"{label} is not a private directory")
    return fd, _identity(info)


def _manifest_path() -> Path:
    return Path(__file__).with_name("wine-prefix-template.json")


def _load_manifest() -> dict[str, object]:
    path = _manifest_path()
    before = _snapshot(path, "canonical manifest")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        opened = os.fstat(stream.fileno())
        if not _same_identity(before, opened):
            raise PrefixTemplateError("canonical manifest changed before open")
        raw = stream.read(256 * 1024 + 1)
        if len(raw) > 256 * 1024 or not _same_identity(opened, os.fstat(stream.fileno())):
            raise PrefixTemplateError("canonical manifest is oversized or changed")
        if not _same_identity(opened, _snapshot(path, "canonical manifest")):
            raise PrefixTemplateError("canonical manifest path changed")
    actual = hashlib.sha256(raw).hexdigest()
    if actual != MANIFEST_SHA256:
        raise PrefixTemplateError(f"canonical manifest SHA-256 mismatch: {actual}")
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise PrefixTemplateError("canonical manifest is not JSON") from exc
    if not isinstance(value, dict) or value.get("schema") != "wowforever.wine-prefix-template/v1":
        raise PrefixTemplateError("unsupported canonical manifest")
    _validate_manifest(value)
    return value


def _test_manifest(manifest: dict[str, object]) -> dict[str, object]:
    """Private fixture seam; production CLI and public API never use it."""
    if not _ALLOW_TEST_MANIFEST:
        raise PrefixTemplateError("test manifest seam is disabled")
    _validate_manifest(manifest)
    return manifest


def _safe_relative(name: str) -> PurePosixPath:
    if not isinstance(name, str) or not name or "\x00" in name or "\\" in name:
        raise PrefixTemplateError(f"unsafe archive path: {name!r}")
    raw_parts = name.split("/")
    if name.startswith("/") or name.endswith("/") or "//" in name or any(part in ("", ".", "..") for part in raw_parts):
        raise PrefixTemplateError(f"unsafe archive path: {name!r}")
    path = PurePosixPath(name)
    if path.is_absolute() or tuple(path.parts) != tuple(raw_parts):
        raise PrefixTemplateError(f"unsafe archive path: {name!r}")
    return path


def _validate_manifest(manifest: dict[str, object]) -> None:
    archive = manifest.get("archive")
    if not isinstance(archive, dict):
        raise PrefixTemplateError("manifest archive section missing")
    members = archive.get("members")
    if not isinstance(members, list):
        raise PrefixTemplateError("manifest members missing")
    expected_count = archive.get("member_count")
    if expected_count != len(members):
        raise PrefixTemplateError("manifest member count mismatch")
    seen: set[str] = set()
    counts: dict[str, int] = {}
    total = 0
    for item in members:
        if not isinstance(item, dict):
            raise PrefixTemplateError("manifest member is not an object")
        name = item.get("path")
        _safe_relative(name)
        if name in seen:
            raise PrefixTemplateError(f"duplicate manifest member: {name}")
        seen.add(name)
        kind = item.get("type")
        if kind not in {"directory", "regular", "symlink"}:
            raise PrefixTemplateError(f"unsupported manifest member type: {kind!r}")
        if not isinstance(item.get("bytes"), int) or item["bytes"] < 0:
            raise PrefixTemplateError(f"invalid member size: {name}")
        mode = item.get("mode")
        if not isinstance(mode, int) or mode < 0 or mode > 0o7777:
            raise PrefixTemplateError(f"invalid member mode: {name}")
        counts[kind] = counts.get(kind, 0) + 1
        if kind == "regular":
            digest = item.get("sha256")
            if not isinstance(digest, str) or len(digest) != 64:
                raise PrefixTemplateError(f"regular member has no digest: {name}")
            total += item["bytes"]
        elif kind == "symlink" and not isinstance(item.get("target"), str):
            raise PrefixTemplateError(f"symlink has no target: {name}")
    if archive.get("typed_counts") != counts or archive.get("regular_payload_bytes") != total:
        raise PrefixTemplateError("manifest member accounting mismatch")
    if total > MAX_TOTAL_BYTES:
        raise PrefixTemplateError("manifest exceeds total payload bound")
    for section in ("preserved_registries", "replacements"):
        values = manifest.get(section)
        if not isinstance(values, list) or not values:
            raise PrefixTemplateError(f"manifest {section} missing")
        for item in values:
            if not isinstance(item, dict):
                raise PrefixTemplateError(f"invalid {section} entry")
            _safe_relative(item.get("path"))
            if not isinstance(item.get("bytes"), int) or not isinstance(item.get("mode"), int):
                raise PrefixTemplateError(f"invalid {section} size/mode")
            digest = item.get("sha256")
            if not isinstance(digest, str) or len(digest) != 64:
                raise PrefixTemplateError(f"invalid {section} digest")
    replacements = manifest["replacements"]
    expected_replacement_paths = {
        "compiler": ".wine/drive_c/windows/system32/d3dcompiler_47.dll",
        "dxgi": ".wine/drive_c/windows/system32/dxgi.dll",
        "d3d11": ".wine/drive_c/windows/system32/d3d11.dll",
    }
    if {item.get("source") for item in replacements} != set(expected_replacement_paths):
        raise PrefixTemplateError("replacement source set is not the approved three-file set")
    if {item.get("path") for item in replacements} != set(expected_replacement_paths.values()):
        raise PrefixTemplateError("replacement target set is not the approved three-file set")
    reconstruction = manifest.get("reconstruction")
    if not isinstance(reconstruction, dict) or not isinstance(reconstruction.get("links"), list):
        raise PrefixTemplateError("manifest reconstruction section missing")


def _snapshot(path: Path, label: str, *, directory: bool = False) -> os.stat_result:
    _assert_no_symlink_ancestors(path, label)
    try:
        info = path.lstat()
    except OSError as exc:
        raise PrefixTemplateError(f"{label} is unavailable: {path}") from exc
    wanted = stat.S_ISDIR if directory else stat.S_ISREG
    if not wanted(info.st_mode) or stat.S_ISLNK(info.st_mode) or (not directory and info.st_nlink != 1):
        raise PrefixTemplateError(f"{label} is not a private stable object: {path}")
    if info.st_uid != os.getuid():
        raise PrefixTemplateError(f"{label} is not owned by the app host: {path}")
    return info


def _same_identity(before: os.stat_result, after: os.stat_result) -> bool:
    return (
        before.st_dev,
        before.st_ino,
        before.st_uid,
        before.st_gid,
        before.st_mode,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
        before.st_nlink,
    ) == (
        after.st_dev,
        after.st_ino,
        after.st_uid,
        after.st_gid,
        after.st_mode,
        after.st_size,
        after.st_mtime_ns,
        after.st_ctime_ns,
        after.st_nlink,
    )


def _hash_file(path: Path, expected_size: int | None = None) -> str:
    digest = hashlib.sha256()
    total = 0
    _assert_no_symlink_ancestors(path, "hashed source")
    flags = os.O_RDONLY | os.O_NOFOLLOW
    fd = os.open(path, flags)
    before = os.fstat(fd)
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_uid != os.getuid():
        os.close(fd)
        raise PrefixTemplateError(f"hashed source is not a stable regular file: {path}")
    with os.fdopen(fd, "rb", closefd=True) as stream:
        while block := stream.read(1024 * 1024):
            total += len(block)
            if total > MAX_ARCHIVE_BYTES and expected_size is None:
                raise PrefixTemplateError(f"unbounded source: {path}")
            if expected_size is not None and total > max(expected_size, MAX_MEMBER_BYTES):
                raise PrefixTemplateError(f"source exceeds pin: {path}")
            digest.update(block)
        after = os.fstat(stream.fileno())
    if not _same_identity(before, after):
        raise PrefixTemplateError(f"hashed source changed while being read: {path}")
    if expected_size is not None and total != expected_size:
        raise PrefixTemplateError(f"source size mismatch: {path}")
    return digest.hexdigest()


def _verify_source(path: Path, expected_size: int, expected_sha256: str, label: str) -> None:
    before = _snapshot(path, label)
    if before.st_size != expected_size:
        raise PrefixTemplateError(f"{label} size mismatch")
    actual = _hash_file(path, expected_size)
    after = _snapshot(path, label)
    if not _same_identity(before, after):
        raise PrefixTemplateError(f"{label} changed while being read")
    if actual != expected_sha256:
        raise PrefixTemplateError(f"{label} SHA-256 mismatch: {actual}")


def _trusted_root(path: Path, label: str) -> Path:
    _snapshot(path, label, directory=True)
    info = path.lstat()
    if info.st_mode & 0o022:
        raise PrefixTemplateError(f"{label} is group/world writable: {path}")
    return path


def _overlap(first: Path, second: Path) -> bool:
    try:
        first.relative_to(second)
        return True
    except ValueError:
        pass
    try:
        second.relative_to(first)
        return True
    except ValueError:
        return False


def _validate_destination(destination: Path, imagefs_root: Path, probe_root: Path) -> Path:
    _raw_absolute(destination, "destination")
    parent = destination.parent
    _snapshot(parent, "destination parent", directory=True)
    if parent.lstat().st_mode & 0o022:
        raise PrefixTemplateError("destination parent is group/world writable")
    if destination.exists() or destination.is_symlink():
        raise PrefixTemplateError(f"destination already exists: {destination}")
    resolved_destination = destination
    roots = [_trusted_root(imagefs_root, "imagefs root"), _trusted_root(probe_root, "probe root")]
    if _overlap(roots[0], roots[1]) or any(_overlap(resolved_destination, root) for root in roots):
        raise PrefixTemplateError("destination/root overlap is not private")
    return resolved_destination


def _archive_identity(path: Path) -> os.stat_result:
    info = _snapshot(path, "prefix archive")
    if info.st_size != ARCHIVE_SIZE:
        raise PrefixTemplateError(f"prefix archive size mismatch: {info.st_size}")
    return info


def _verify_archive_pin(path: Path, before: os.stat_result | None = None) -> os.stat_result:
    before = before or _archive_identity(path)
    actual = _hash_file(path)
    after = _archive_identity(path)
    if not _same_identity(before, after):
        raise PrefixTemplateError("prefix archive changed while being read")
    if actual != ARCHIVE_SHA256:
        raise PrefixTemplateError(f"prefix archive SHA-256 mismatch: {actual}")
    return after


def _authenticate_archive_stream(raw: BinaryIO, expected: os.stat_result) -> None:
    """Authenticate the very descriptor used by XZ/TAR, not a pathname reopen."""
    if not _same_identity(expected, os.fstat(raw.fileno())):
        raise PrefixTemplateError("archive descriptor does not match pinned identity")
    raw.seek(0)
    digest = hashlib.sha256()
    total = 0
    while block := raw.read(1024 * 1024):
        total += len(block)
        if total > ARCHIVE_SIZE:
            raise PrefixTemplateError("archive descriptor exceeds pinned size")
        digest.update(block)
    if (total != ARCHIVE_SIZE or digest.hexdigest() != ARCHIVE_SHA256 or
            not _same_identity(expected, os.fstat(raw.fileno()))):
        raise PrefixTemplateError("archive descriptor failed pinned content authentication")
    raw.seek(0)


@contextmanager
def _open_tar_stream(path: Path, expected: os.stat_result) -> Iterator[tuple[tarfile.TarFile, os.stat_result]]:
    """Open the pinned archive through an O_NOFOLLOW descriptor only."""
    _assert_no_symlink_ancestors(path, "prefix archive")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    before = os.fstat(fd)
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_uid != os.getuid():
        os.close(fd)
        raise PrefixTemplateError(f"prefix archive is not a stable regular file: {path}")
    raw = os.fdopen(fd, "rb", closefd=False)
    compressed = None
    failed = False
    try:
        _authenticate_archive_stream(raw, expected)
        compressed = lzma.LZMAFile(raw, "rb")
        with tarfile.open(fileobj=compressed, mode="r|") as tar:
            yield tar, before
        compressed.close()
        compressed = None
        _authenticate_archive_stream(raw, expected)
    except BaseException:
        failed = True
        raise
    finally:
        try:
            if compressed is not None:
                compressed.close()
            if not failed:
                after_path = _archive_identity(path)
                if (not _same_identity(expected, os.fstat(fd)) or
                        not _same_identity(expected, after_path)):
                    raise PrefixTemplateError("prefix archive changed while being streamed")
        finally:
            raw.close()
            os.close(fd)


def _new_directory(path: Path, mode: int) -> None:
    _assert_no_symlink_ancestors(path.parent, "stage directory parent")
    try:
        os.mkdir(path, 0o700)
    except FileExistsError as exc:
        raise PrefixTemplateError(f"stage collision: {path}") from exc
    os.chmod(path, mode)
    _fsync_directory(path)


def _fsync_file(fd: int) -> None:
    os.fsync(fd)


def _fsync_directory(path: Path) -> None:
    anchored_fd = _proc_fd_number(path)
    if anchored_fd is not None:
        fd = os.dup(anchored_fd)
    else:
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _write_all(fd: int, block: bytes) -> None:
    view = memoryview(block)
    while view:
        written = os.write(fd, view)
        if written <= 0:
            raise PrefixTemplateError("short write while materializing")
        view = view[written:]


def _existing_parent(path: Path) -> Path:
    parent = path.parent
    _assert_no_symlink_ancestors(parent, "stage member parent")
    info = parent.lstat()
    if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode):
        raise PrefixTemplateError(f"stage parent is not a directory: {parent}")
    return parent


def _write_member(stage: Path, item: dict[str, object], source: BinaryIO) -> None:
    relative = _safe_relative(item["path"])
    target = stage.joinpath(*relative.parts)
    _existing_parent(target)
    expected_size = int(item["bytes"])
    if expected_size > MAX_MEMBER_BYTES:
        raise PrefixTemplateError(f"member exceeds bound: {item['path']}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    fd = os.open(target, flags, 0o600)
    digest = hashlib.sha256()
    total = 0
    try:
        while total < expected_size:
            block = source.read(min(1024 * 1024, expected_size - total))
            if not block:
                raise PrefixTemplateError(f"short archive member: {item['path']}")
            total += len(block)
            digest.update(block)
            _write_all(fd, block)
        if source.read(1):
            raise PrefixTemplateError(f"archive member exceeds pin: {item['path']}")
        if digest.hexdigest() != item["sha256"]:
            raise PrefixTemplateError(f"archive member SHA-256 mismatch: {item['path']}")
        os.fchmod(fd, int(item["mode"]))
        _fsync_file(fd)
    finally:
        os.close(fd)


def _copy_replacement(stage: Path, source_path: Path, item: dict[str, object]) -> None:
    before = _snapshot(source_path, f"replacement source {item['source']}")
    if before.st_size != item["bytes"]:
        raise PrefixTemplateError(f"replacement source size mismatch: {item['source']}")
    target = stage.joinpath(*_safe_relative(item["path"]).parts)
    _existing_parent(target)
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    digest = hashlib.sha256()
    total = 0
    try:
        source_fd = os.open(source_path, os.O_RDONLY | os.O_NOFOLLOW)
        if not _same_identity(before, os.fstat(source_fd)):
            os.close(source_fd)
            raise PrefixTemplateError(f"replacement source changed before copy: {item['source']}")
        with os.fdopen(source_fd, "rb", closefd=True) as source:
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > int(item["bytes"]):
                    raise PrefixTemplateError(f"replacement source exceeds pin: {item['source']}")
                digest.update(block)
                _write_all(fd, block)
        after = _snapshot(source_path, f"replacement source {item['source']}")
        if not _same_identity(before, after) or total != item["bytes"]:
            raise PrefixTemplateError(f"replacement source changed while being read: {item['source']}")
        if digest.hexdigest() != item["sha256"]:
            raise PrefixTemplateError(f"replacement SHA-256 mismatch: {item['source']}")
        os.fchmod(fd, int(item["mode"]))
        _fsync_file(fd)
    finally:
        os.close(fd)


def _replace_declared_stage_file(
    stage: Path, archive_item: dict[str, object], source_path: Path, replacement: dict[str, object]
) -> None:
    """Replace only a declared regular archive target after pinning its old bytes."""
    if archive_item["type"] != "regular" or archive_item["path"] != replacement["path"]:
        raise PrefixTemplateError(f"replacement target is not a declared regular file: {replacement['path']}")
    target = stage.joinpath(*_safe_relative(replacement["path"]).parts)
    _existing_parent(target)
    info = target.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):
        raise PrefixTemplateError(f"replacement target changed type: {replacement['path']}")
    if info.st_size != archive_item["bytes"] or (info.st_mode & 0o7777) != archive_item["mode"]:
        raise PrefixTemplateError(f"replacement target old metadata mismatch: {replacement['path']}")
    if _hash_file(target, archive_item["bytes"]) != archive_item["sha256"]:
        raise PrefixTemplateError(f"replacement target old SHA-256 mismatch: {replacement['path']}")
    before = _snapshot(source_path, f"replacement source {replacement['source']}")
    if before.st_size != replacement["bytes"]:
        raise PrefixTemplateError(f"replacement source size mismatch: {replacement['source']}")
    fd = os.open(target, os.O_WRONLY | os.O_TRUNC | os.O_NOFOLLOW)
    digest = hashlib.sha256()
    total = 0
    source_fd = -1
    try:
        source_fd = os.open(source_path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            source_info = os.fstat(source_fd)
            if not _same_identity(before, source_info):
                raise PrefixTemplateError(f"replacement source changed before copy: {replacement['source']}")
            with os.fdopen(source_fd, "rb", closefd=True) as source:
                source_fd = -1
                while block := source.read(1024 * 1024):
                    total += len(block)
                    if total > replacement["bytes"]:
                        raise PrefixTemplateError(f"replacement source exceeds pin: {replacement['source']}")
                    digest.update(block)
                    _write_all(fd, block)
        finally:
            if source_fd >= 0:
                os.close(source_fd)
        after = _snapshot(source_path, f"replacement source {replacement['source']}")
        if not _same_identity(before, after) or total != replacement["bytes"]:
            raise PrefixTemplateError(f"replacement source changed while being read: {replacement['source']}")
        if digest.hexdigest() != replacement["sha256"]:
            raise PrefixTemplateError(f"replacement SHA-256 mismatch: {replacement['source']}")
        os.fchmod(fd, int(replacement["mode"]))
        _fsync_file(fd)
    finally:
        os.close(fd)


def _rename_noreplace(state: _StageState, destination: Path) -> None:
    _assert_stage(state)
    _assert_identity(state.parent, state.parent_identity, "destination parent")
    if destination.parent != state.parent or "/" in destination.name or destination.name in ("", ".", ".."):
        raise PrefixTemplateError("destination is not a child of the anchored parent")
    libc = ctypes.CDLL(None, use_errno=True)
    function = getattr(libc, "renameat2", None)
    if function is None:
        raise PrefixTemplateError("Linux renameat2(RENAME_NOREPLACE) is required")
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    result = function(state.parent_fd, os.fsencode(state.stage_name), state.parent_fd, os.fsencode(destination.name), 1)
    if result != 0:
        error = ctypes.get_errno()
        if error == errno.EEXIST:
            raise PrefixTemplateError(f"destination appeared during publish: {destination}")
        raise OSError(error, os.strerror(error), destination)
    _assert_fd_identity(state.stage_fd, state.identity, "published destination")
    published_fd = os.open(destination.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=state.parent_fd)
    try:
        if _identity(os.fstat(published_fd)) != state.identity:
            raise PrefixTemplateError("published destination identity mismatch")
    finally:
        os.close(published_fd)
    try:
        _assert_identity(state.parent, state.parent_identity, "destination parent after publish")
    except PrefixTemplateError as exc:
        raise PrefixTemplateError("parent path changed after anchored publish; publication status unknown") from exc


def _sync_tree(root: Path) -> None:
    for current, directories, files in os.walk(root, topdown=False, followlinks=False):
        current_path = Path(current)
        for name in files:
            item = current_path / name
            info = item.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                continue
            fd = os.open(item, os.O_RDONLY | os.O_NOFOLLOW)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        _fsync_directory(current_path)


def _create_stage(parent: Path) -> _StageState:
    parent_fd, parent_identity = _open_private_directory(parent, "destination parent")
    stage_name: str | None = None
    try:
        names = tempfile._get_candidate_names()
        for _ in range(100):
            candidate = ".wine-prefix-stage-" + next(names)
            try:
                os.mkdir(candidate, 0o700, dir_fd=parent_fd)
                stage_name = candidate
                break
            except FileExistsError:
                continue
        else:
            raise PrefixTemplateError("could not allocate a unique materialization stage")
        assert stage_name is not None
        stage_fd = os.open(stage_name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
        stage_identity = _identity(os.fstat(stage_fd))
        stage_path = Path(f"/proc/self/fd/{stage_fd}")
        return _StageState(stage_path, stage_identity, parent, parent_identity, parent_fd, stage_fd, stage_name)
    except BaseException:
        if stage_name is not None:
            try:
                os.rmdir(stage_name, dir_fd=parent_fd)
            except OSError:
                pass
        os.close(parent_fd)
        raise


def _assert_stage(state: _StageState) -> None:
    _assert_fd_identity(state.stage_fd, state.identity, "materialization stage")
    _assert_fd_identity(state.parent_fd, state.parent_identity, "destination parent")
    _assert_identity(state.parent, state.parent_identity, "destination parent path")


def _cleanup_stage(state: _StageState) -> None:
    _assert_fd_identity(state.stage_fd, state.identity, "materialization stage")
    _assert_fd_identity(state.parent_fd, state.parent_identity, "destination parent")
    try:
        named = os.stat(state.stage_name, dir_fd=state.parent_fd, follow_symlinks=False)
    except FileNotFoundError as exc:
        raise PrefixTemplateError(f"materialization stage name disappeared; quarantined: {state.stage_name}") from exc
    if _identity(named) != state.identity:
        raise PrefixTemplateError(f"materialization stage name changed; refusing cleanup: {state.stage_name}")
    _remove_tree_fd(state.stage_fd)
    try:
        os.rmdir(state.stage_name, dir_fd=state.parent_fd)
    except OSError as exc:
        raise PrefixTemplateError(f"materialization stage cleanup could not unlink its own name: {state.stage_name}") from exc


def _remove_tree_fd(directory_fd: int) -> None:
    root = Path(f"/proc/self/fd/{directory_fd}")
    with os.scandir(root) as entries:
        for entry in entries:
            if entry.is_symlink():
                os.unlink(entry.name, dir_fd=directory_fd)
            elif entry.is_dir(follow_symlinks=False):
                child_fd = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)
                try:
                    _remove_tree_fd(child_fd)
                finally:
                    os.close(child_fd)
                os.rmdir(entry.name, dir_fd=directory_fd)
            else:
                os.unlink(entry.name, dir_fd=directory_fd)


def _close_stage(state: _StageState) -> None:
    errors: list[BaseException] = []
    for fd in (state.stage_fd, state.parent_fd):
        try:
            os.close(fd)
        except OSError as exc:
            errors.append(exc)
    if errors:
        raise PrefixTemplateError("materialization stage fd close failed") from errors[0]


def _materialize_with_manifest(
    sourcearchive: Path,
    compiler: Path,
    dxgi: Path,
    d3d11: Path,
    destination: Path,
    imagefs_root: Path,
    probe_root: Path,
    manifest: dict[str, object],
) -> Path:
    archive = manifest["archive"]
    _raw_absolute(sourcearchive, "source archive")
    if sourcearchive.is_symlink():
        raise PrefixTemplateError("source archive must be an absolute regular file")
    _validate_destination(destination, imagefs_root, probe_root)
    imagefs_root = _trusted_root(imagefs_root, "imagefs root")
    probe_root = _trusted_root(probe_root, "probe root")
    imagefs_identity = _identity(imagefs_root.lstat())
    probe_identity = _identity(probe_root.lstat())
    archive_info = _archive_identity(sourcearchive)
    if archive["size_bytes"] != ARCHIVE_SIZE or archive["sha256"] != ARCHIVE_SHA256:
        raise PrefixTemplateError("manifest archive pin is not the approved source")
    _verify_archive_pin(sourcearchive, archive_info)
    replacements = {item["source"]: item for item in manifest["replacements"]}
    for source_name, item in replacements.items():
        source_path = {"compiler": compiler, "dxgi": dxgi, "d3d11": d3d11}.get(source_name)
        if source_path is None:
            raise PrefixTemplateError(f"unknown replacement source: {source_name}")
        _verify_source(source_path, item["bytes"], item["sha256"], f"replacement source {source_name}")

    parent = destination.parent
    state = _create_stage(parent)
    published = False
    try:
        members_by_path = {item["path"]: item for item in archive["members"]}
        seen: set[str] = set()
        total = 0
        with _open_tar_stream(sourcearchive, archive_info) as (tar, _archive_stream_identity):
            for member in tar:
                _assert_stage(state)
                _safe_relative(member.name)
                if member.name in seen:
                    raise PrefixTemplateError(f"duplicate archive member: {member.name}")
                seen.add(member.name)
                expected = members_by_path.get(member.name)
                if expected is None:
                    raise PrefixTemplateError(f"unlisted archive member: {member.name}")
                actual_type = "directory" if member.isdir() else "regular" if member.isfile() else "symlink" if member.issym() else "hardlink" if member.islnk() else "special"
                if actual_type != expected["type"] or member.size != expected["bytes"] or (member.mode & 0o7777) != expected["mode"]:
                    raise PrefixTemplateError(f"archive metadata mismatch: {member.name}")
                if actual_type == "directory":
                    _new_directory(state.path.joinpath(*PurePosixPath(member.name).parts), expected["mode"])
                elif actual_type == "regular":
                    total += member.size
                    if total > MAX_TOTAL_BYTES:
                        raise PrefixTemplateError("archive payload exceeds bound")
                    stream = tar.extractfile(member)
                    if stream is None:
                        raise PrefixTemplateError(f"regular archive member has no stream: {member.name}")
                    _write_member(state.path, expected, stream)
                elif actual_type == "symlink":
                    if member.linkname != expected["target"]:
                        raise PrefixTemplateError(f"archive link target mismatch: {member.name}")
                    # Archive links are authority evidence only.  None is extracted.
                else:
                    raise PrefixTemplateError(f"disallowed archive member type: {member.name}")
        if seen != set(members_by_path) or len(seen) != archive["member_count"]:
            raise PrefixTemplateError("archive member set/count mismatch")
        _verify_archive_pin(sourcearchive, archive_info)
        _assert_stage(state)
        _assert_identity(imagefs_root, imagefs_identity, "imagefs root")
        _assert_identity(probe_root, probe_identity, "probe root")
        for link in manifest["reconstruction"]["links"]:
            relative = _safe_relative(link["path"])
            target = state.path.joinpath(*relative.parts)
            _existing_parent(target)
            if target.exists() or target.is_symlink():
                raise PrefixTemplateError(f"reconstructed link collision: {link['path']}")
            kind = link["target_kind"]
            if kind == "relative":
                link_target = link["target"]
            elif kind == "explicit_root" and link["target"] == "<imagefs_root>":
                link_target = str(imagefs_root)
            elif kind == "explicit_root" and link["target"] == "<probe_root>":
                link_target = str(probe_root)
            else:
                raise PrefixTemplateError(f"unapproved reconstructed link: {link['path']}")
            os.symlink(link_target, target)
        for source_name, item in replacements.items():
            source_path = {"compiler": compiler, "dxgi": dxgi, "d3d11": d3d11}[source_name]
            if item["path"] in members_by_path:
                _replace_declared_stage_file(state.path, members_by_path[item["path"]], source_path, item)
            else:
                _copy_replacement(state.path, source_path, item)
        _assert_stage(state)
        _assert_identity(imagefs_root, imagefs_identity, "imagefs root")
        _assert_identity(probe_root, probe_identity, "probe root")
        _sync_tree(state.path)
        _assert_stage(state)
        os.fsync(state.parent_fd)
        _rename_noreplace(state, destination)
        published = True
        os.fsync(state.parent_fd)
        return destination
    finally:
        try:
            if not published:
                _cleanup_stage(state)
        finally:
            _close_stage(state)


def materialize(
    sourcearchive: Path,
    compiler: Path,
    dxgi: Path,
    d3d11: Path,
    destination: Path,
    imagefs_root: Path,
    probe_root: Path,
) -> Path:
    """Materialize one fresh private prefix using only the approved sources."""
    return _materialize_with_manifest(
        Path(sourcearchive), Path(compiler), Path(dxgi), Path(d3d11), Path(destination),
        Path(imagefs_root), Path(probe_root), _load_manifest()
    )


def _materialize_for_test(
    sourcearchive: Path,
    compiler: Path,
    dxgi: Path,
    d3d11: Path,
    destination: Path,
    imagefs_root: Path,
    probe_root: Path,
    manifest: dict[str, object],
) -> Path:
    """Test-only seam; it cannot be selected through the production CLI."""
    return _materialize_with_manifest(
        Path(sourcearchive), Path(compiler), Path(dxgi), Path(d3d11), Path(destination),
        Path(imagefs_root), Path(probe_root), _test_manifest(manifest)
    )


def verify_materialized(destination: Path, imagefs_root: Path, probe_root: Path) -> None:
    """Read-only final-tree verifier; it never repairs or deletes anything."""
    manifest = _load_manifest()
    destination = Path(destination)
    _raw_absolute(destination, "materialized destination")
    imagefs = _trusted_root(Path(imagefs_root), "imagefs root")
    probe = _trusted_root(Path(probe_root), "probe root")
    imagefs_identity = _identity(imagefs.lstat())
    probe_identity = _identity(probe.lstat())
    destination_fd, destination_identity = _open_private_directory(destination, "materialized destination", exact_mode=0o700)
    if destination_identity.uid != os.getuid() or destination_identity.mode != 0o700:
        os.close(destination_fd)
        raise PrefixTemplateError("materialized destination is not a private 0700 directory")
    anchored_destination = Path(f"/proc/self/fd/{destination_fd}")
    try:
        expected: dict[str, tuple[str, object]] = {}
        for item in manifest["archive"]["members"]:
            if item["type"] == "symlink":
                continue
            expected[item["path"]] = (item["type"], item)
        for link in manifest["reconstruction"]["links"]:
            expected[link["path"]] = ("symlink", link)
        for item in manifest["replacements"]:
            expected[item["path"]] = ("regular", item)
        for relative, (kind, item) in expected.items():
            _assert_fd_identity(destination_fd, destination_identity, "materialized destination")
            path = anchored_destination.joinpath(*_safe_relative(relative).parts)
            _assert_no_symlink_ancestors(path.parent, f"materialized member {relative} parent")
            try:
                info = path.lstat()
            except FileNotFoundError as exc:
                raise PrefixTemplateError(f"materialized member missing: {relative}") from exc
            if kind == "directory":
                if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode) or (info.st_mode & 0o7777) != item["mode"]:
                    raise PrefixTemplateError(f"materialized directory mismatch: {relative}")
                continue
            if kind == "regular":
                if not stat.S_ISREG(info.st_mode) or info.st_size != item["bytes"] or (info.st_mode & 0o7777) != item["mode"]:
                    raise PrefixTemplateError(f"materialized file metadata mismatch: {relative}")
                if _hash_file(path, item["bytes"]) != item["sha256"]:
                    raise PrefixTemplateError(f"materialized file digest mismatch: {relative}")
            elif kind == "symlink":
                if not stat.S_ISLNK(info.st_mode):
                    raise PrefixTemplateError(f"materialized link mismatch: {relative}")
                target = item["target"]
                if target == "<imagefs_root>":
                    target = str(imagefs)
                elif target == "<probe_root>":
                    target = str(probe)
                if os.readlink(path) != target:
                    raise PrefixTemplateError(f"materialized link target mismatch: {relative}")
        actual: set[str] = set()
        for current, directories, files in os.walk(anchored_destination, topdown=True, followlinks=False):
            current_path = Path(current)
            for name in directories + files:
                path = current_path / name
                relative = path.relative_to(anchored_destination).as_posix()
                actual.add(relative)
                if path.is_symlink():
                    directories[:] = [value for value in directories if value != name]
        if actual != set(expected):
            raise PrefixTemplateError("materialized tree contains extra or disallowed members")
        _assert_fd_identity(destination_fd, destination_identity, "materialized destination")
        _assert_identity(imagefs, imagefs_identity, "imagefs root")
        _assert_identity(probe, probe_identity, "probe root")
    finally:
        os.close(destination_fd)


def inspect_archive(sourcearchive: Path) -> dict[str, object]:
    """Read-only inventory check against the canonical archive contract."""
    manifest = _load_manifest()
    sourcearchive = Path(sourcearchive)
    archive_info = _verify_archive_pin(sourcearchive)
    seen: set[str] = set()
    counts: dict[str, int] = {}
    with _open_tar_stream(sourcearchive, archive_info) as (tar, _archive_stream_identity):
        for member in tar:
            _safe_relative(member.name)
            if member.name in seen:
                raise PrefixTemplateError(f"duplicate archive member: {member.name}")
            seen.add(member.name)
            kind = "directory" if member.isdir() else "regular" if member.isfile() else "symlink" if member.issym() else "hardlink" if member.islnk() else "special"
            counts[kind] = counts.get(kind, 0) + 1
    if seen != {item["path"] for item in manifest["archive"]["members"]}:
        raise PrefixTemplateError("archive inventory differs from canonical manifest")
    return {"archive": str(sourcearchive), "sha256": ARCHIVE_SHA256, "member_count": len(seen), "typed_counts": counts}


def _cli() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action")
    inventory = subparsers.add_parser("inventory", help="verify the pinned archive inventory")
    inventory.add_argument("archive", type=Path)
    build = subparsers.add_parser("materialize", help="materialize a new private prefix")
    for name in ("archive", "compiler", "dxgi", "d3d11", "destination", "imagefs_root", "probe_root"):
        build.add_argument(name, type=Path)
    verify = subparsers.add_parser("verify", help="verify an existing prefix read-only")
    verify.add_argument("destination", type=Path)
    verify.add_argument("imagefs_root", type=Path)
    verify.add_argument("probe_root", type=Path)
    args = parser.parse_args()
    if args.action is None:
        parser.print_help()
        return 0
    if args.action == "inventory":
        print(json.dumps(inspect_archive(args.archive), sort_keys=True))
    elif args.action == "materialize":
        materialize(args.archive, args.compiler, args.dxgi, args.d3d11, args.destination, args.imagefs_root, args.probe_root)
        print(json.dumps({"destination": str(args.destination), "status": "published"}, sort_keys=True))
    else:
        verify_materialized(args.destination, args.imagefs_root, args.probe_root)
        print(json.dumps({"destination": str(args.destination), "status": "verified"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(_cli())
