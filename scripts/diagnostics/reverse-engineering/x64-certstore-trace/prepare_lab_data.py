#!/usr/bin/env python3
"""Prepare one private, sanitized x86-64 build 70009 debugger lab copy.

This command is intentionally offline and one-shot.  It reads the verified
``/opt/wowserver/client-base`` installation, copies only the technical CASC
inputs, applies the existing archive-18 header-reference repair to a fresh
fixture root, and writes a durable report outside the repository.  It never
launches WoW, Wine, a downloader, or a production repair operation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import time
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[4]
SERVER_TOOL = Path("/home/adrian/dev/wowserver/tool")
SOURCE = Path("/opt/wowserver/client-base")
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/work")
CURRENT_LAB_ROOT = Path(
    "/home/adrian/wowforever-artifacts/work/"
    "x64-certstore-data-20261013T213448Z-211565/fixture-root"
)
REPORT_NAME = "lab-data-report.json"
MANIFEST_COMPLETION_REPORT_NAME = "manifest-completion-report.json"
ARCHIVE_RE = re.compile(r"data\.(\d+)")
INDEX_RE = re.compile(r"[0-9a-fA-F]{10}\.idx")
CONFIG_RE = re.compile(r"[0-9a-fA-F]{2}")
HASH_RE = re.compile(r"[0-9a-fA-F]{32}")
CDN_INDEX_RE = re.compile(r"[0-9a-fA-F]{32}\.index")
TECHNICAL_ROOT_FILES = {
    ".build.info",
    "wowb.exe",
    "wow.exe",
    "buildconfig",
    "versions",
    "installmanifest",
}
PRIVATE_PARTS = {
    "wtf",
    "home",
    "accounts",
    "account",
    "private",
    "cache",
    "logs",
    "errors",
    "screenshots",
    "shmem",
    "repairmarker.psv",
    ".install_state.json",
}


class PreparationError(RuntimeError):
    """A safety or validation gate refused the lab preparation."""


def _json_hash(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _stat_signature(value: os.stat_result) -> tuple[int, ...]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
        value.st_mode,
        value.st_uid,
        value.st_gid,
        value.st_nlink,
    )


def _check_safe_components(root: Path, path: Path) -> None:
    root = Path(os.path.abspath(root))
    path = Path(os.path.abspath(path))
    try:
        relative = path.relative_to(root)
    except ValueError as error:
        raise PreparationError(f"path escapes bounded root: {path}") from error
    if root.is_symlink():
        raise PreparationError(f"symlinked root refused: {root}")
    current = root
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise PreparationError(f"symlinked path component refused: {current}")


def _regular_open(path: Path, label: str) -> tuple[int, os.stat_result]:
    if path.is_symlink():
        raise PreparationError(f"symlinked {label} refused: {path}")
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError as error:
        raise PreparationError(f"cannot open {label}: {path}: {error}") from error
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode):
        os.close(fd)
        raise PreparationError(f"{label} is not regular: {path}")
    return fd, info


def _sha_file_stable(path: Path, label: str) -> tuple[str, int]:
    """Hash one file through a descriptor and reject an in-flight mutation."""
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or path.is_symlink():
        raise PreparationError(f"{label} is not a regular non-symlink file: {path}")
    fd, opened = _regular_open(path, label)
    expected = _stat_signature(before)
    try:
        if _stat_signature(opened) != expected:
            raise PreparationError(f"{label} changed before hashing: {path}")
        digest = hashlib.sha256()
        total = 0
        while True:
            chunk = os.read(fd, 1 << 20)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
        after_fd = os.fstat(fd)
        after_path = path.lstat()
        if _stat_signature(after_fd) != expected or _stat_signature(after_path) != expected:
            raise PreparationError(f"{label} changed during hashing: {path}")
        return digest.hexdigest(), total
    finally:
        os.close(fd)


def _digests_file_stable(path: Path, label: str) -> dict[str, Any]:
    """Read one stable regular file and return both report digests."""
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or path.is_symlink():
        raise PreparationError(f"{label} is not a regular non-symlink file: {path}")
    fd, opened = _regular_open(path, label)
    expected = _stat_signature(before)
    md5 = hashlib.md5()
    sha256 = hashlib.sha256()
    total = 0
    try:
        if _stat_signature(opened) != expected:
            raise PreparationError(f"{label} changed before hashing: {path}")
        while True:
            chunk = os.read(fd, 1 << 20)
            if not chunk:
                break
            md5.update(chunk)
            sha256.update(chunk)
            total += len(chunk)
        if _stat_signature(os.fstat(fd)) != expected or _stat_signature(path.lstat()) != expected:
            raise PreparationError(f"{label} changed during hashing: {path}")
    finally:
        os.close(fd)
    return {
        "md5": md5.hexdigest(),
        "sha256": sha256.hexdigest(),
        "bytes": total,
        "signature": expected,
    }


def source_inventory(source: Path) -> dict[str, Any]:
    """Return metadata only; no source file contents are read here."""
    if source.is_symlink() or not source.is_dir():
        raise PreparationError(f"source is not a real directory: {source}")
    entries: list[dict[str, Any]] = []
    for current, dirs, files in os.walk(source, topdown=True, followlinks=False):
        dirs.sort()
        files.sort()
        for name in dirs + files:
            child = Path(current) / name
            info = child.lstat()
            if child.is_symlink() or not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
                raise PreparationError(f"symlink or special source inode: {child}")
            entries.append(
                {
                    "path": child.relative_to(source).as_posix(),
                    "kind": "dir" if stat.S_ISDIR(info.st_mode) else "file",
                    "dev": info.st_dev,
                    "ino": info.st_ino,
                    "size": info.st_size,
                    "mode": stat.S_IMODE(info.st_mode),
                    "uid": info.st_uid,
                    "gid": info.st_gid,
                    "mtime_ns": info.st_mtime_ns,
                    "ctime_ns": info.st_ctime_ns,
                    "nlink": info.st_nlink,
                }
            )
    return {
        "entry_count": len(entries),
        "file_count": sum(item["kind"] == "file" for item in entries),
        "directory_count": sum(item["kind"] == "dir" for item in entries),
        "sha256": _json_hash(entries),
        "_entries": entries,
    }


def assert_inventory_stable(before: dict[str, Any], after: dict[str, Any]) -> None:
    if before.get("sha256") != after.get("sha256") or before.get("entry_count") != after.get("entry_count"):
        raise PreparationError("admitted source metadata inventory changed during copy")


def _is_private(relative: Path) -> bool:
    return any(part.casefold() in PRIVATE_PARTS for part in relative.parts)


def _is_admitted_file(relative: Path) -> bool:
    folded = [part.casefold() for part in relative.parts]
    if _is_private(relative):
        return False
    if len(folded) == 1:
        return folded[0] in TECHNICAL_ROOT_FILES
    if len(folded) == 3 and folded[:2] == ["data", "data"]:
        return bool(ARCHIVE_RE.fullmatch(folded[2]) or INDEX_RE.fullmatch(folded[2]))
    if len(folded) == 5 and folded[:2] == ["data", "config"]:
        return bool(CONFIG_RE.fullmatch(folded[2]) and CONFIG_RE.fullmatch(folded[3]) and HASH_RE.fullmatch(folded[4]))
    if len(folded) == 3 and folded[:2] == ["data", "indices"]:
        return bool(CDN_INDEX_RE.fullmatch(folded[2]))
    return False


def _source_file_hashes(source: Path) -> dict[str, tuple[str, int]]:
    inventory = source_inventory(source)
    result: dict[str, tuple[str, int]] = {}
    for item in inventory["_entries"]:
        relative = Path(item["path"])
        if item["kind"] == "file" and _is_admitted_file(relative):
            result[relative.as_posix()] = _sha_file_stable(source / relative, "admitted source file")
    return result


def _hash_map_summary(values: dict[str, tuple[str, int]]) -> str:
    return _json_hash({path: {"sha256": digest, "bytes": size} for path, (digest, size) in sorted(values.items())})


def _archive_hashes(values: dict[str, tuple[str, int]]) -> dict[str, dict[str, Any]]:
    return {
        path: {"sha256": digest, "bytes": size}
        for path, (digest, size) in sorted(values.items())
        if len(Path(path).parts) == 3 and Path(path).parts[:2] == ("Data", "data") and ARCHIVE_RE.fullmatch(Path(path).name)
    }


def _index_hashes(values: dict[str, tuple[str, int]]) -> dict[str, dict[str, Any]]:
    return {
        path: {"sha256": digest, "bytes": size}
        for path, (digest, size) in sorted(values.items())
        if len(Path(path).parts) == 3 and Path(path).parts[:2] == ("Data", "data") and INDEX_RE.fullmatch(Path(path).name)
    }


def _write_exclusive(path: Path, data: bytes, mode: int = 0o600) -> None:
    if path.exists() or path.is_symlink():
        raise PreparationError(f"refusing to overwrite artifact: {path}")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), mode)
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            view = view[written:]
        os.fsync(fd)
    finally:
        os.close(fd)


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _new_output(requested: str | None) -> Path:
    root = ARTIFACT_ROOT
    if requested is None:
        name = f"x64-certstore-data-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-{os.getpid()}"
        output = root / name
    else:
        output = Path(requested).absolute()
        try:
            output.relative_to(root)
        except ValueError as error:
            raise PreparationError(f"artifact output must be below {root}") from error
    _check_safe_components(root, output)
    if output.exists() or output.is_symlink():
        raise PreparationError(f"artifact output already exists: {output}")
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    _fsync_dir(output.parent)
    return output


def _mkdir(path: Path, mode: int = 0o700) -> None:
    if path.exists() or path.is_symlink():
        if path.is_symlink() or not path.is_dir():
            raise PreparationError(f"unsafe lab directory: {path}")
        return
    path.mkdir(mode=mode)
    os.chmod(path, mode, follow_symlinks=False)
    _fsync_dir(path.parent)


def _prepare_fixture_root(output: Path) -> tuple[Path, Path]:
    root = output / "fixture-root"
    client = root / "players" / "player1" / "client"
    for path in (root, root / "state", root / "release-gate", root / "players", root / "players" / "player1"):
        _mkdir(path)
    control = client.parent / "control"
    _mkdir(control)
    _write_exclusive(control / "session.lock", b"")
    _write_exclusive(control / "ready.json", b'{"schema": 1, "slot": "player1"}\n')
    return root, client


def _copy_and_verify(source: Path, destination: Path, *, source_view: Path | None = None) -> dict[str, Any]:
    from forever_client.shared_storage import _copy_sanitized

    before_inventory = source_inventory(source)
    before_hashes = _source_file_hashes(source)
    source_view = source_view or destination.parent / "source-view"
    if source_view.exists() or source_view.is_symlink():
        raise PreparationError(f"refusing to overwrite source view: {source_view}")
    source_view.mkdir(mode=0o700)
    copied_view: dict[str, tuple[str, int]] = {}
    for relative, expected in sorted(before_hashes.items()):
        target = source_view / relative
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        copied_view[relative] = _copy_streaming(source / relative, target)
    if copied_view != before_hashes:
        raise PreparationError("technical source view differs from the stable source")
    _fsync_dir(source_view)
    sanitization = _copy_sanitized(source_view, destination)
    _fsync_dir(destination)
    after_inventory = source_inventory(source)
    assert_inventory_stable(before_inventory, after_inventory)
    after_hashes = _source_file_hashes(source)
    if before_hashes != after_hashes:
        raise PreparationError("admitted source content changed during copy")
    destination_hashes: dict[str, tuple[str, int]] = {}
    for relative in sorted(before_hashes):
        destination_hashes[relative] = _sha_file_stable(destination / relative, "sanitized destination file")
    if destination_hashes != before_hashes:
        raise PreparationError("sanitized destination content differs from source")
    return {
        "sanitization": {
            "copied_file_count": len(sanitization["copied"]),
            "excluded_path_count": len(sanitization["excluded"]),
            "unknown_paths_excluded": sanitization["unknown_paths_excluded"],
            "source_view": str(source_view),
            "source_view_is_ordinary_copy": True,
        },
        "source_inventory_before": {key: value for key, value in before_inventory.items() if key != "_entries"},
        "source_inventory_after": {key: value for key, value in after_inventory.items() if key != "_entries"},
        "source_inventory_stable": True,
        "source_admitted_hash_summary_before": _hash_map_summary(before_hashes),
        "source_admitted_hash_summary_after": _hash_map_summary(after_hashes),
        "destination_admitted_hash_summary": _hash_map_summary(destination_hashes),
        "copy_equality_verified": True,
        "source_archive_sha256_before": _archive_hashes(before_hashes),
        "source_archive_sha256_after": _archive_hashes(after_hashes),
        "destination_archive_sha256_before_repair": _archive_hashes(destination_hashes),
        "source_index_sha256_before": _index_hashes(before_hashes),
        "source_index_sha256_after": _index_hashes(after_hashes),
        "destination_index_sha256_before_repair": _index_hashes(destination_hashes),
        "source_archive_bytes_read_for_raw_hash": sum(item["bytes"] for item in _archive_hashes(before_hashes).values()),
    }


def _copy_streaming(source: Path, destination: Path) -> tuple[str, int]:
    """Copy one admitted regular file while hashing its raw bytes."""
    before = source.lstat()
    if source.is_symlink() or not stat.S_ISREG(before.st_mode):
        raise PreparationError(f"source-view input is not a regular file: {source}")
    source_fd, opened = _regular_open(source, "source-view input")
    expected = _stat_signature(before)
    destination_fd = os.open(
        destination,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    digest = hashlib.sha256()
    total = 0
    try:
        if _stat_signature(opened) != expected:
            raise PreparationError(f"source-view input changed before copy: {source}")
        while True:
            chunk = os.read(source_fd, 1 << 20)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
            view = memoryview(chunk)
            while view:
                written = os.write(destination_fd, view)
                view = view[written:]
        os.fsync(destination_fd)
        if _stat_signature(os.fstat(source_fd)) != expected or _stat_signature(source.lstat()) != expected:
            raise PreparationError(f"source-view input changed during copy: {source}")
    finally:
        os.close(destination_fd)
        os.close(source_fd)
    return digest.hexdigest(), total


def _manifest_relative(value: Any) -> Path:
    if not isinstance(value, str) or not value or "\\" in value:
        raise PreparationError("manifest path is not a canonical public path")
    relative = Path(value)
    if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
        raise PreparationError(f"manifest path escapes the client: {value!r}")
    if _is_private(relative):
        raise PreparationError(f"private manifest path refused: {value}")
    return relative


def _manifest_source_snapshot(source: Path, expected: Any) -> dict[str, dict[str, Any]]:
    records: dict[str, dict[str, Any]] = {}
    for item in expected:
        relative = _manifest_relative(getattr(item, "path", None))
        key = relative.as_posix()
        if key in records:
            raise PreparationError(f"duplicate public manifest path: {key}")
        actual = _digests_file_stable(source / relative, "public manifest source file")
        expected_size = getattr(item, "size", None)
        expected_md5 = getattr(item, "md5", b"")
        if not isinstance(expected_size, int) or actual["bytes"] != expected_size:
            raise PreparationError(f"public manifest size mismatch: {key}")
        if not isinstance(expected_md5, bytes) or actual["md5"] != expected_md5.hex():
            raise PreparationError(f"public manifest MD5 mismatch: {key}")
        records[key] = actual
    return records


def _ensure_manifest_parent(root: Path, target: Path) -> None:
    _check_safe_components(root, target)
    relative_parent = target.parent.relative_to(root)
    current = root
    for part in relative_parent.parts:
        current /= part
        if current.exists() or current.is_symlink():
            if current.is_symlink() or not current.is_dir():
                raise PreparationError(f"unsafe manifest parent: {current}")
            continue
        current.mkdir(mode=0o700)
        os.chmod(current, 0o700, follow_symlinks=False)
        _fsync_dir(current.parent)


def _copy_verified_manifest_files(source: Path, destination: Path, expected: Any) -> dict[str, Any]:
    """Complete only the closed public manifest set, with CREATE_NEW files."""
    if destination.is_symlink() or not destination.is_dir():
        raise PreparationError(f"manifest destination is not a real directory: {destination}")
    source_before = _manifest_source_snapshot(source, expected)
    actions: dict[str, str] = {}
    for item in expected:
        relative = _manifest_relative(getattr(item, "path", None))
        target = destination / relative
        _check_safe_components(destination, target)
        if target.is_symlink():
            raise PreparationError(f"symlinked existing manifest dependency refused: {target}")
        if target.exists():
            actual = _digests_file_stable(target, "existing manifest dependency")
            source_record = source_before[relative.as_posix()]
            if actual["bytes"] != source_record["bytes"] or actual["md5"] != source_record["md5"]:
                raise PreparationError(f"existing manifest dependency mismatch: {relative.as_posix()}")
            actions[relative.as_posix()] = "existing-valid"
        else:
            actions[relative.as_posix()] = "copied"

    for item in expected:
        relative = _manifest_relative(getattr(item, "path", None))
        key = relative.as_posix()
        if actions[key] != "copied":
            continue
        target = destination / relative
        _ensure_manifest_parent(destination, target)
        _copy_streaming(source / relative, target)
        _fsync_dir(target.parent)

    source_after = _manifest_source_snapshot(source, expected)
    if source_after != source_before:
        raise PreparationError("public manifest source changed during dependency completion")

    files: list[dict[str, Any]] = []
    for item in expected:
        relative = _manifest_relative(getattr(item, "path", None))
        key = relative.as_posix()
        actual = _digests_file_stable(destination / relative, "completed manifest dependency")
        source_record = source_before[key]
        if actual["bytes"] != source_record["bytes"] or actual["md5"] != source_record["md5"]:
            raise PreparationError(f"completed manifest dependency verification failed: {key}")
        files.append(
            {
                "path": key,
                "action": actions[key],
                "sha256": actual["sha256"],
                "md5": actual["md5"],
                "bytes": actual["bytes"],
            }
        )
    return {
        "files": files,
        "missing_before": [key for key, action in actions.items() if action == "copied"],
        "copied_count": sum(action == "copied" for action in actions.values()),
        "existing_valid_count": sum(action == "existing-valid" for action in actions.values()),
        "source_identity_stable": True,
    }


def _archive_metadata(client: Path) -> dict[str, tuple[int, ...]]:
    data_dir = client / "Data" / "data"
    if data_dir.is_symlink() or not data_dir.is_dir():
        raise PreparationError(f"archive metadata root is unsafe: {data_dir}")
    result: dict[str, tuple[int, ...]] = {}
    for path in sorted(data_dir.glob("data.*")):
        match = ARCHIVE_RE.fullmatch(path.name)
        if match is None:
            continue
        if path.is_symlink():
            raise PreparationError(f"symlinked archive refused: {path}")
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode):
            raise PreparationError(f"non-regular archive refused: {path}")
        result[path.name] = _stat_signature(info)
    if len(result) != 93 or sorted(int(name[5:]) for name in result) != list(range(93)):
        raise PreparationError("lab archive metadata set is not exactly data.000..data.092")
    return result


def _index_only_hashes(client: Path) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for path in sorted((client / "Data" / "data").glob("*.idx")):
        if not INDEX_RE.fullmatch(path.name):
            continue
        digest, size = _sha_file_stable(path, "KMT index")
        result[path.name] = {"sha256": digest, "bytes": size}
    if set(result) != {f"{bucket:02x}00000001.idx" for bucket in range(16)}:
        raise PreparationError("lab KMT index set is not exactly 16 generation-1 files")
    return result


def _read_json_stable(path: Path, label: str) -> tuple[bytes, dict[str, Any]]:
    fd, info = _regular_open(path, label)
    try:
        data = b"".join(iter(lambda: os.read(fd, 1 << 20), b""))
        if _stat_signature(os.fstat(fd)) != _stat_signature(info) or _stat_signature(path.lstat()) != _stat_signature(info):
            raise PreparationError(f"{label} changed while reading: {path}")
    finally:
        os.close(fd)
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PreparationError(f"invalid {label}: {path}") from error
    if not isinstance(value, dict):
        raise PreparationError(f"{label} must be an object: {path}")
    return data, value


def _require_source_audit(audit: dict[str, Any]) -> None:
    summary = audit.get("summary", {})
    if audit.get("status") != "needs-review":
        raise PreparationError("expected the known source archive-18 reference gap before lab repair")
    if audit.get("payload_bytes_read") != 0 or audit.get("payload_integrity_verified") is not False:
        raise PreparationError("source audit unexpectedly read or verified archive payloads")
    if audit.get("index_parse_errors") or summary.get("duplicate_reconstruction_keys") or summary.get("truncated_prefix_collisions"):
        raise PreparationError("source audit contains malformed indices or duplicate reconstruction keys")
    uncovered = summary.get("uncovered_slots", [])
    if sorted(uncovered, key=lambda item: (item.get("archive_id"), item.get("slot"))) != [
        {"archive_id": 18, "slot": slot, "status": "missing_index_reference"} for slot in range(16)
    ]:
        raise PreparationError("source audit has an unexpected missing-header/reference set")


def _expected_gap_audit(source: Path, raw_audit: dict[str, Any]) -> dict[str, Any]:
    """Reduce the known pre-repair audit without turning it into a success."""
    from forever_client.kmt_layout import analyze_idx

    data_dir = source / "Data" / "data"
    indices = []
    for path in sorted(data_dir.glob("*.idx")):
        analysis = analyze_idx(path.read_bytes())
        if not analysis.valid:
            raise PreparationError(f"source KMT index is invalid: {path.name}")
        indices.append(
            {
                "name": path.name,
                "entries": analysis.sorted_entry_count,
                "size": analysis.file_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        )
    summary = raw_audit.get("summary", {})
    return {
        "status": raw_audit.get("status"),
        "kmt_tables": len(indices),
        "kmt": indices,
        "segment_slots": summary.get("covered_slots", 0),
        "archives": raw_audit.get("archive_ids", []),
        "payload_bytes_read": raw_audit.get("payload_bytes_read", 0),
        "payload_integrity_verified": raw_audit.get("payload_integrity_verified", False),
        "expected_pre_repair_gap": True,
    }


def _index_semantics(path: Path) -> dict[str, Any]:
    from forever_client.kmt_layout import analyze_idx

    raw = path.read_bytes()
    analysis = analyze_idx(raw)
    return {
        "sha256": hashlib.sha256(raw).hexdigest(),
        "bytes": len(raw),
        "valid": analysis.valid,
        "sorted_entry_count": analysis.sorted_entry_count,
        "empty_update_area": analysis.empty_update_area,
    }


def _destination_index_diff(before: dict[str, dict[str, Any]], after_client: Path, additions: list[dict[str, Any]]) -> dict[str, Any]:
    expected_by_bucket: dict[int, int] = {bucket: 0 for bucket in range(16)}
    for item in additions:
        expected_by_bucket[int(item["index_bucket"])] += 1
    diff: dict[str, dict[str, Any]] = {}
    changed: list[str] = []
    for name, previous in sorted(before.items()):
        current = _index_semantics(after_client / "Data" / "data" / name)
        bucket = int(name[:2], 16)
        item = {
            "before_sha256": previous["sha256"],
            "after_sha256": current["sha256"],
            "changed": previous["sha256"] != current["sha256"],
            "before_sorted_entry_count": previous["sorted_entry_count"],
            "after_sorted_entry_count": current["sorted_entry_count"],
            "added_sorted_entry_count": current["sorted_entry_count"] - previous["sorted_entry_count"],
            "expected_added_entry_count": expected_by_bucket[bucket],
            "before_valid": previous["valid"],
            "after_valid": current["valid"],
            "after_empty_update_area": current["empty_update_area"],
        }
        diff[name] = item
        if item["changed"]:
            changed.append(name)
    return {"changed_indices": changed, "indices": diff}


def complete_current_lab_manifest() -> dict[str, Any]:
    """Complete the existing lab only; never recopy CASC or rerun repair."""
    if SOURCE != Path("/opt/wowserver/client-base"):
        raise PreparationError("source constant changed from the approved read-only client-base path")
    lab_root = CURRENT_LAB_ROOT
    destination = lab_root / "players" / "player1" / "client"
    if not lab_root.is_dir() or destination.is_symlink() or not destination.is_dir():
        raise PreparationError(f"approved existing lab destination is unavailable: {destination}")
    old_report_path = lab_root.parent / REPORT_NAME
    old_report_before, old_report = _read_json_stable(old_report_path, "original lab report")
    old_report_sha_before = hashlib.sha256(old_report_before).hexdigest()
    if old_report.get("status") != "prepared-lab-copy" or old_report.get("destination") != str(destination):
        raise PreparationError("original lab report is not the approved successful preparation")

    from forever_client.contract import load_contract
    from forever_client.initial_snapshot import _parse_public_context, _validate_expected
    from forever_client.segment_audit import audit_client

    contract_path = REPO_ROOT / "forever" / "contract.json"
    contract = load_contract(contract_path)
    if contract.validated_build_id != 70009 or contract.validated_versions_name != "1.60.1.70009" or contract.arch != "x86_64":
        raise PreparationError("repository contract is not the approved x64 build 70009")
    public_context = _parse_public_context(SOURCE, contract, fixture=False, metadata_only=True)
    if len(public_context.expected) != 42:
        raise PreparationError("pinned public manifest does not select exactly 42 files")
    _validate_expected(SOURCE, public_context.expected)

    source_before = _manifest_source_snapshot(SOURCE, public_context.expected)
    archive_metadata_before = _archive_metadata(destination)
    source_indices_before = _index_only_hashes(SOURCE)
    destination_indices_before = _index_only_hashes(destination)
    expected_source_indices = {
        Path(name).name: value
        for name, value in old_report.get("copy", {}).get("source_index_sha256_before", {}).items()
    }
    expected_destination_indices = old_report.get("destination_index_sha256_after_repair")
    if source_indices_before != expected_source_indices:
        raise PreparationError("source KMT hashes differ from the original successful report")
    if {name: item["sha256"] for name, item in destination_indices_before.items()} != expected_destination_indices:
        raise PreparationError("destination KMT hashes differ from the original successful report")

    completion = _copy_verified_manifest_files(SOURCE, destination, public_context.expected)
    source_after = _manifest_source_snapshot(SOURCE, public_context.expected)
    if source_after != source_before:
        raise PreparationError("source public manifest identity changed during completion")
    _validate_expected(destination, public_context.expected)

    destination_audit = audit_client(destination)
    summary = destination_audit.get("summary", {})
    if (
        destination_audit.get("status") != "ok"
        or summary.get("valid_headers") != 1488
        or summary.get("covered_slots") != 1488
        or destination_audit.get("index_parse_errors")
        or summary.get("uncovered_slots")
    ):
        raise PreparationError("destination CASC audit changed or is incomplete after manifest completion")
    from forever_client.kmt_layout import analyze_idx

    kmt_after: dict[str, dict[str, Any]] = {}
    for path in sorted((destination / "Data" / "data").glob("*.idx")):
        if not INDEX_RE.fullmatch(path.name):
            continue
        analysis = analyze_idx(path.read_bytes())
        kmt_after[path.name] = {
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "valid": analysis.valid,
            "sorted_entry_count": analysis.sorted_entry_count,
        }
    if len(kmt_after) != 16 or not all(item["valid"] for item in kmt_after.values()):
        raise PreparationError("destination KMT validation after manifest completion failed")
    if {name: item["sha256"] for name, item in kmt_after.items()} != expected_destination_indices:
        raise PreparationError("destination KMT hashes changed during manifest completion")
    if _index_only_hashes(SOURCE) != source_indices_before:
        raise PreparationError("source KMT hashes changed during manifest completion")
    if _archive_metadata(destination) != archive_metadata_before:
        raise PreparationError("destination archive metadata changed during manifest completion")

    old_report_after, _ = _read_json_stable(old_report_path, "original lab report")
    old_report_sha_after = hashlib.sha256(old_report_after).hexdigest()
    if old_report_after != old_report_before:
        raise PreparationError("original lab report changed during manifest completion")
    supplemental = {
        "report_version": 1,
        "status": "manifest-complete",
        "launch_ready_claim": False,
        "runtime_readiness_claim": False,
        "payload_integrity_verified": False,
        "payload_bytes_read": 0,
        "lab_root": str(lab_root),
        "destination": str(destination),
        "source": str(SOURCE),
        "source_phase_clarification": {
            "old_source_42_validation": "passed",
            "old_destination_42_validation": "not-performed",
            "current_destination_42_validation": "passed",
        },
        "old_report_sha256_before": old_report_sha_before,
        "old_report_sha256_after": old_report_sha_after,
        "manifest_completion": completion,
        "source_manifest_identity_stable": True,
        "source_kmt_hashes_stable": True,
        "destination_kmt_hashes_stable_since_original_repair": True,
        "destination_archive_metadata_stable": True,
        "destination_audit": {
            "status": destination_audit["status"],
            "archives": len(destination_audit.get("archive_ids", [])),
            "valid_headers": summary.get("valid_headers"),
            "covered_slots": summary.get("covered_slots"),
            "uncovered_slots": summary.get("uncovered_slots", []),
            "index_parse_errors": destination_audit.get("index_parse_errors", {}),
            "payload_bytes_read": destination_audit.get("payload_bytes_read", 0),
            "payload_integrity_verified": destination_audit.get("payload_integrity_verified", False),
        },
        "kmt_after": kmt_after,
        "limitations": [
            "Only the public 42-file manifest dependency set was completed; no CASC archive or IDX bytes were rewritten.",
            "Payload integrity remains unverified and no runtime readiness is claimed.",
            "The legacy path_suffix_mismatches metric was not used to alter headers.",
        ],
    }
    supplemental_path = lab_root.parent / MANIFEST_COMPLETION_REPORT_NAME
    _write_exclusive(supplemental_path, (json.dumps(supplemental, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    _fsync_dir(supplemental_path.parent)
    return supplemental


def prepare(output: Path) -> dict[str, Any]:
    if SOURCE != Path("/opt/wowserver/client-base"):
        raise PreparationError("source constant changed from the approved read-only client-base path")
    _check_safe_components(Path("/opt/wowserver"), SOURCE)
    if not SERVER_TOOL.is_dir():
        raise PreparationError(f"server tool path is unavailable: {SERVER_TOOL}")

    from forever_client.contract import load_contract
    from forever_client.initial_snapshot import _parse_public_context, _validate_expected
    from forever_client.segment_audit import audit_client
    from forever_client.shared_storage import SharedStorageError, _audit_source, _source_report
    from forever_client.kmt_repair import apply_repair

    contract_path = REPO_ROOT / "forever" / "contract.json"
    contract = load_contract(contract_path)
    if contract.validated_build_id != 70009 or contract.validated_versions_name != "1.60.1.70009" or contract.arch != "x86_64":
        raise PreparationError("repository contract is not the approved x64 build 70009")

    source_report = _source_report(SOURCE, contract_path, fixture=False)
    raw_source_audit = audit_client(SOURCE)
    _require_source_audit(raw_source_audit)
    try:
        # The established non-fixture helper is intentionally strict and
        # refuses the source's known pre-repair gap.  Invoke it first so that
        # a different structural failure cannot be silently downgraded.
        source_audit = _audit_source(SOURCE, fixture=False, require_initial_93=True)
    except SharedStorageError as error:
        if str(error) != "source does not prove complete CASC archive segment coverage":
            raise
        source_audit = _expected_gap_audit(SOURCE, raw_source_audit)
    public_context = _parse_public_context(SOURCE, contract, fixture=False, metadata_only=True)
    _validate_expected(SOURCE, public_context.expected)
    public_manifest = {
        "build_name": public_context.build_name,
        "build_info_sha256": public_context.build_info_sha256,
        "manifest_sha256": public_context.manifest_sha256,
        "selected_file_count": len(public_context.expected),
        "selected_file_paths": [item.path for item in public_context.expected],
        "config_path": public_context.config_path,
        "install_encoded_size": public_context.install_encoded_size,
        "install_decompressed_size": public_context.install_decompressed_size,
        "validation": "offline public installManifest + selected loose-file MD5",
    }

    root, destination = _prepare_fixture_root(output)
    copy_report = _copy_and_verify(SOURCE, destination, source_view=output / "source-view")
    destination_audit_before = audit_client(destination)
    destination_index_before = {
        path.name: _index_semantics(path)
        for path in sorted((destination / "Data" / "data").glob("*.idx"))
    }
    repair = apply_repair(root, "player1", fixture=True, segment_headers=True)
    additions = repair.get("indices", {}).get("segment_provenance", [])
    if len(additions) != 16 or sorted(item["slot"] for item in additions) != list(range(16)):
        raise PreparationError("repair did not report exactly archive-18 slots 0..15")

    destination_audit_after = audit_client(destination)
    after_summary = destination_audit_after.get("summary", {})
    if (
        destination_audit_after.get("status") != "ok"
        or after_summary.get("valid_headers") != 1488
        or after_summary.get("covered_slots") != 1488
        or destination_audit_after.get("index_parse_errors")
        or after_summary.get("duplicate_reconstruction_keys")
        or after_summary.get("truncated_prefix_collisions")
        or after_summary.get("uncovered_slots")
    ):
        raise PreparationError("post-repair destination audit is not complete")
    from forever_client.kmt_layout import analyze_idx

    destination_index_after = {
        path.name: _index_semantics(path)
        for path in sorted((destination / "Data" / "data").glob("*.idx"))
    }
    if len(destination_index_after) != 16 or not all(item["valid"] for item in destination_index_after.values()):
        raise PreparationError("post-repair destination does not have 16 valid KMT indices")
    index_diff = _destination_index_diff(destination_index_before, destination, additions)
    if any(item["added_sorted_entry_count"] != item["expected_added_entry_count"] for item in index_diff["indices"].values()):
        raise PreparationError("destination index semantic counts do not match header additions")
    destination_archives_after = {
        path: {
            "sha256": digest,
            "bytes": size,
        }
        for path, (digest, size) in _source_file_hashes(destination).items()
        if len(Path(path).parts) == 3 and Path(path).parts[:2] == ("Data", "data") and ARCHIVE_RE.fullmatch(Path(path).name)
    }
    if destination_archives_after != copy_report["destination_archive_sha256_before_repair"]:
        raise PreparationError("destination archive raw bytes changed during repair")
    source_hashes_after = _source_file_hashes(SOURCE)
    if _index_hashes(source_hashes_after) != copy_report["source_index_sha256_before"]:
        raise PreparationError("source KMT index hashes changed during lab preparation")
    if _archive_hashes(source_hashes_after) != copy_report["source_archive_sha256_before"]:
        raise PreparationError("source archive hashes changed during lab preparation")
    exe_path = destination / "WowB.exe"
    exe_sha, exe_bytes = _sha_file_stable(exe_path, "sanitized WowB.exe")
    if exe_bytes != 102916816 or exe_sha != "5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92":
        raise PreparationError("sanitized WowB.exe identity differs from the approved source")

    report = {
        "report_version": 1,
        "status": "prepared-lab-copy",
        "launch_ready_claim": False,
        "runtime_readiness_claim": False,
        "payload_integrity_verified": False,
        "payload_bytes_read": 0,
        "source": str(SOURCE),
        "destination": str(destination),
        "fixture_root": str(root),
        "build": {
            "product": source_report.get("product"),
            "build_id": source_report.get("build_id"),
            "versions_name": source_report.get("versions_name"),
            "arch": "x86_64",
            "wowb_exe_bytes": exe_bytes,
            "wowb_exe_sha256": exe_sha,
        },
        "source_report": source_report,
        "source_audit": source_audit,
        "offline_public_manifest": public_manifest,
        "copy": copy_report,
        "destination_audit_before_repair": destination_audit_before,
        "repair": {
            "mechanism": "forever_client.kmt_repair.apply_repair",
            "fixture": True,
            "operation": "segment-headers-v1",
            "report": repair,
            "actual_added_archive_18_slots": [item["slot"] for item in additions],
        },
        "destination_audit_after_repair": destination_audit_after,
        "index_diff": index_diff,
        "destination_index_sha256_after_repair": {
            name: item["sha256"] for name, item in sorted(destination_index_after.items())
        },
        "destination_archive_sha256_after_repair": destination_archives_after,
        "source_unchanged_proof": {
            "metadata_inventory_stable": True,
            "admitted_content_stable": True,
            "source_index_sha256_stable": True,
            "source_archive_sha256_stable": True,
            "destination_archive_sha256_stable_through_repair": True,
        },
        "limitations": [
            "Raw CASC archive bytes were copied and compared; Blizzard payload integrity was not validated.",
            "The structural audit reads only KMT metadata and 16 reconstruction headers per archive.",
            "The fixture-root repair is not a production readiness or runtime claim.",
            "No accounts, WTF/HOME state, credentials, logs, caches, or runtime artifacts were copied.",
        ],
    }
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--complete-manifest",
        action="store_true",
        help="complete only the approved existing lab's 42 public manifest dependencies",
    )
    parser.add_argument(
        "--lab-root",
        type=Path,
        default=CURRENT_LAB_ROOT,
        help="approved existing lab root (only CURRENT_LAB_ROOT is accepted)",
    )
    parser.add_argument("--output-dir", help="new private directory below /home/adrian/wowforever-artifacts/work")
    args = parser.parse_args(argv)
    if args.complete_manifest:
        try:
            requested = Path(args.lab_root).absolute()
            if requested != CURRENT_LAB_ROOT:
                raise PreparationError("manifest completion is bounded to the approved existing lab root")
            report = complete_current_lab_manifest()
            print(json.dumps({
                "status": report["status"],
                "destination": report["destination"],
                "report": str(CURRENT_LAB_ROOT.parent / MANIFEST_COMPLETION_REPORT_NAME),
                "copied_count": report["manifest_completion"]["copied_count"],
            }))
            return 0
        except Exception as error:
            print(json.dumps({"status": "failed", "error_type": type(error).__name__, "error": str(error)}))
            return 1
    output: Path | None = None
    try:
        output = _new_output(args.output_dir)
        report = prepare(output)
        _write_exclusive(output / REPORT_NAME, (json.dumps(report, indent=2, sort_keys=True) + "\n").encode("utf-8"))
        _fsync_dir(output)
        print(json.dumps({"status": report["status"], "output_dir": str(output), "report": str(output / REPORT_NAME)}))
        return 0
    except Exception as error:
        if output is not None:
            failure = {
                "report_version": 1,
                "status": "failed",
                "launch_ready_claim": False,
                "runtime_readiness_claim": False,
                "error_type": type(error).__name__,
                "error": str(error),
                "output_dir": str(output),
            }
            try:
                _write_exclusive(output / REPORT_NAME, (json.dumps(failure, indent=2, sort_keys=True) + "\n").encode("utf-8"))
                _fsync_dir(output)
            except Exception:
                pass
        print(json.dumps({"status": "failed", "error_type": type(error).__name__, "error": str(error), "output_dir": str(output) if output else None}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
