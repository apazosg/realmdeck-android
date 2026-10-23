#!/usr/bin/env python3
"""Pull only the fixed, opt-in ARM64 code-capture artifacts.

This is a collector, not a runner: it never starts the client, selects a
historical run, reads game logs, or accepts a device-side path.  The nonce is
the lease identity supplied by the reviewed launcher control and the two
artifact names are fixed here.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import uuid


PACKAGE = "com.wowforever.arm64probe"
REMOTE_ROOT = "files/functional-poc/captures"
SOURCE_SIZE = 94_286_544
SOURCE_SHA256 = "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59"
WINDOWS = ({"name": "text-223c000", "rva": 0x223C000, "length": 0x4000, "offset": 0},
           {"name": "text-37b0000", "rva": 0x37B0000, "length": 0x4000, "offset": 0x4000})
SNAPSHOT_BYTES = 32_768
TOTAL_BYTES = 65_536
MAX_METADATA_BYTES = 64 * 1024
MAX_LIST_BYTES = 512
MAX_STDERR_BYTES = 4096
SAFE_NONCE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
DEVICE = re.compile(r"^[0-9a-fA-F]+:[0-9a-fA-F]+$")
FIXED_ARTIFACTS = frozenset(
    f"snapshot-{index}.{kind}"
    for index in range(2)
    for kind in ("json", "bin")
)


class CollectorError(RuntimeError):
    pass


def _safe_output(path: Path) -> Path:
    absolute = Path(os.path.abspath(path))
    current = absolute
    while True:
        try:
            if current.is_symlink():
                raise CollectorError("--output path contains a symlink")
            current.stat()
        except FileNotFoundError as error:
            raise CollectorError("--output must be an existing real directory") from error
        if current == current.parent:
            break
        current = current.parent
    if not absolute.is_dir():
        raise CollectorError("--output must be an existing real directory")
    resolved = absolute.resolve(strict=True)
    repo = Path(__file__).resolve().parents[3]
    if resolved == repo or repo in resolved.parents:
        raise CollectorError("--output must be outside the repository")
    return resolved


def _new_private_dir(parent: Path, nonce: str) -> Path:
    for _ in range(20):
        candidate = parent / f"functional-code-{nonce}-{uuid.uuid4().hex[:10]}"
        try:
            candidate.mkdir(mode=0o700)
            os.chmod(candidate, 0o700)
            return candidate
        except FileExistsError:
            continue
        except OSError as error:
            if candidate.is_dir() and not candidate.is_symlink():
                try:
                    candidate.rmdir()
                except OSError:
                    pass
            raise CollectorError("could not allocate a private collector directory") from error
    raise CollectorError("could not allocate a private collector directory")


def _private_write(path: Path, data: bytes) -> tuple[int, int]:
    if path.exists() or path.is_symlink():
        raise CollectorError("collector artifact destination already exists")
    fd = -1
    identity: tuple[int, int] | None = None
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        opened = os.fstat(fd)
        identity = (opened.st_dev, opened.st_ino)
        stream = os.fdopen(fd, "wb")
        fd = -1  # fdopen owns the descriptor from this point onward.
        with stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        assert identity is not None
        return identity
    except OSError:
        if fd >= 0:
            os.close(fd)
            fd = -1
        if identity is not None:
            _unlink_if_identity(path, identity)
        raise


def _unlink_if_identity(path: Path, identity: tuple[int, int]) -> bool:
    try:
        current = path.stat()
    except FileNotFoundError:
        return False
    except OSError:
        return False
    if (current.st_dev, current.st_ino) != identity:
        return False
    try:
        path.unlink()
    except FileNotFoundError:
        return False
    except OSError:
        return False
    return True


def _path_identity(path: Path) -> tuple[int, int] | None:
    try:
        value = path.stat()
    except (FileNotFoundError, OSError):
        return None
    return (value.st_dev, value.st_ino)


def _commit_private(directory: Path, name: str, data: bytes) -> Path:
    final = directory / name
    temporary = directory / f".{name}.{uuid.uuid4().hex}.part"
    temporary_identity = _private_write(temporary, data)
    final_was_preexisting = False
    try:
        final_was_preexisting = final.exists() or final.is_symlink()
        if final_was_preexisting:
            raise CollectorError("collector artifact destination already exists")
        os.link(temporary, final, follow_symlinks=False)
        if not _unlink_if_identity(temporary, temporary_identity):
            raise OSError("collector temporary artifact cleanup failed")
        directory_fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except (CollectorError, OSError) as error:
        if not final_was_preexisting and temporary_identity is not None:
            final_identity = _path_identity(final)
            if final_identity == temporary_identity:
                _unlink_if_identity(final, final_identity)
        if temporary_identity is not None:
            _unlink_if_identity(temporary, temporary_identity)
        if isinstance(error, CollectorError):
            raise
        raise CollectorError("collector artifact commit failed") from error
    return final


def _run(adb: str, serial: str, remote: str, limit: int) -> bytes:
    command = [adb]
    if serial:
        command += ["-s", serial]
    script = 'test ! -L "$2" && head -c "$1" "$2"'
    command += ["exec-out", " ".join(("run-as", shlex.quote(PACKAGE), "sh", "-c", shlex.quote(script), "collector", str(limit + 1), shlex.quote(remote)))]
    try:
        result = subprocess.run(command, check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10.0)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise CollectorError("fixed artifact read timed out or could not start") from error
    if result.returncode != 0:
        raise CollectorError("fixed artifact read failed")
    if len(result.stderr or b"") > MAX_STDERR_BYTES:
        raise CollectorError("fixed artifact read produced excessive stderr")
    if len(result.stdout) > limit:
        raise CollectorError("fixed artifact read exceeded its bound")
    return result.stdout


def _run_listing(adb: str, serial: str, remote: str) -> set[str]:
    command = [adb]
    if serial:
        command += ["-s", serial]
    script = (
        'test ! -L "$1" && test -d "$1" || exit 1; '
        'for name in snapshot-0.json snapshot-0.bin snapshot-1.json snapshot-1.bin; do '
        'if test ! -L "$1/$name" && test -f "$1/$name"; then printf "%s\\n" "$name"; fi; '
        "done"
    )
    command += ["exec-out", " ".join(("run-as", shlex.quote(PACKAGE), "sh", "-c", shlex.quote(script), "collector", shlex.quote(remote)))]
    try:
        result = subprocess.run(command, check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10.0)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise CollectorError("fixed capture listing timed out or could not start") from error
    if result.returncode != 0:
        raise CollectorError("fixed capture listing failed")
    if len(result.stderr or b"") > MAX_STDERR_BYTES:
        raise CollectorError("fixed capture listing produced excessive stderr")
    if len(result.stdout) > MAX_LIST_BYTES:
        raise CollectorError("fixed capture listing exceeded its bound")
    try:
        names = result.stdout.decode("ascii").splitlines()
    except UnicodeDecodeError as error:
        raise CollectorError("fixed capture listing is not valid ASCII") from error
    if any(name not in FIXED_ARTIFACTS for name in names) or len(names) != len(set(names)):
        raise CollectorError("fixed capture listing contains an unexpected artifact")
    return set(names)


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_u64(value: object) -> bool:
    return _is_int(value) and 0 <= value <= 0xFFFFFFFFFFFFFFFF


def _validate_map_entry(entry: object, name: str) -> None:
    if not isinstance(entry, dict):
        raise CollectorError(f"{name}: map coverage entry is malformed")
    allowed = {"start", "end", "permissions", "device", "inode", "offset", "pathKind"}
    if set(entry) != allowed:
        raise CollectorError(f"{name}: map coverage contains unvalidated path data")
    start, end = entry["start"], entry["end"]
    if not (_is_u64(start) and _is_u64(end) and 0 < start < end):
        raise CollectorError(f"{name}: map bounds are invalid")
    if entry["permissions"] != "r-xp":
        raise CollectorError(f"{name}: map coverage is not private RX")
    if not (_is_u64(entry["offset"]) and _is_u64(entry["inode"])):
        raise CollectorError(f"{name}: map offset or inode is invalid")
    if not isinstance(entry["device"], str) or not DEVICE.fullmatch(entry["device"]):
        raise CollectorError(f"{name}: map device is invalid")
    if entry["pathKind"] not in {"file", "anonymous", "pseudo"}:
        raise CollectorError(f"{name}: map path classification is invalid")


def _validate_metadata(value: object, name: str, expected_nonce: str, expected_index: int) -> dict[str, object]:
    if not isinstance(value, dict) or value.get("schema") != 1 or value.get("kind") != "arm64-runtime-code-window":
        raise CollectorError(f"{name}: unsupported capture metadata")
    if value.get("nonce") != expected_nonce:
        raise CollectorError(f"{name}: capture nonce does not match the requested lease")
    source = value.get("source")
    if (
        not isinstance(source, dict)
        or not _is_int(source.get("size"))
        or source.get("size") != SOURCE_SIZE
        or source.get("sha256") != SOURCE_SHA256
        or source.get("sha256CheckedOncePerLease") is not True
        or source.get("metadataStable") is not True
        or source.get("windowsValidatedAsRawBackedExecutableText") is not True
    ):
        raise CollectorError(f"{name}: source provenance is not pinned")
    snapshot = value.get("snapshot")
    if (
        not isinstance(snapshot, dict)
        or snapshot.get("index") != expected_index
        or not _is_int(snapshot.get("index"))
        or snapshot.get("bytes") != SNAPSHOT_BYTES
        or not isinstance(snapshot.get("sha256"), str)
        or not re.fullmatch(r"[0-9a-f]{64}", snapshot["sha256"])
    ):
        raise CollectorError(f"{name}: snapshot size is outside the fixed cap")
    windows = snapshot.get("windows")
    if not isinstance(windows, list) or len(windows) != 2:
        raise CollectorError(f"{name}: fixed window list is missing")
    for expected, actual in zip(WINDOWS, windows, strict=True):
        if (
            not isinstance(actual, dict)
            or actual.get("name") != expected["name"]
            or not _is_int(actual.get("rva"))
            or not _is_int(actual.get("length"))
            or not _is_int(actual.get("offset"))
            or any(actual.get(key) != expected[key] for key in ("name", "rva", "length", "offset"))
        ):
            raise CollectorError(f"{name}: fixed window contract mismatch")
    target = value.get("target")
    if (
        not isinstance(target, dict)
        or not _is_int(target.get("pid"))
        or not _is_int(target.get("startTicks"))
        or not _is_int(target.get("ppid"))
        or target["pid"] <= 0
        or target["startTicks"] <= 0
        or target["ppid"] <= 0
        or not isinstance(target.get("uid4"), list)
        or len(target["uid4"]) != 4
        or not all(_is_int(uid) and uid >= 0 for uid in target["uid4"])
        or target.get("cmdlineValidated") is not True
        or target.get("ancestryValidated") is not True
        or target.get("nonceValidated") is not True
    ):
        raise CollectorError(f"{name}: target identity proof is incomplete")
    if value.get("cryptographicModuleProof") is not False:
        raise CollectorError(f"{name}: cryptographic module proof must be false")
    if value.get("mapsStable") is not True or value.get("writeAuthorized") is not False:
        raise CollectorError(f"{name}: map or write guard is invalid")
    before = value.get("mapsBefore")
    after = value.get("mapsAfter")
    if not isinstance(before, list) or not isinstance(after, list) or before != after or not before or len(before) > 32:
        raise CollectorError(f"{name}: stable map coverage metadata is missing")
    for entry in before:
        _validate_map_entry(entry, name)
    basis = value.get("basis")
    if basis not in ("current-structural-header", "earlier-structural-header"):
        raise CollectorError(f"{name}: unsupported comparison basis")
    current = basis == "current-structural-header"
    if value.get("status") != ("CAPTURED" if current else "INCOMPLETE") or value.get("moduleProof") is not current:
        raise CollectorError(f"{name}: module proof status is inconsistent")
    return value


def collect(adb: str, serial: str, output: Path, nonce: str) -> Path:
    if not SAFE_NONCE.fullmatch(nonce):
        raise CollectorError("nonce is not a bounded lease token")
    destination = _new_private_dir(_safe_output(output), nonce)
    records: list[dict[str, object]] = []
    owned: dict[Path, tuple[int, int]] = {}
    total = 0

    def commit(name: str, data: bytes) -> None:
        path = _commit_private(destination, name, data)
        identity = _path_identity(path)
        if identity is None:
            raise CollectorError("collector artifact disappeared after commit")
        owned[path] = identity

    def cleanup() -> None:
        for path, identity in tuple(owned.items()):
            _unlink_if_identity(path, identity)
        try:
            destination.rmdir()
        except OSError:
            # Never remove an unexpected entry merely to make cleanup succeed.
            pass

    try:
        remote_dir = f"{REMOTE_ROOT}/{nonce}"
        names = _run_listing(adb, serial, remote_dir)
        indexes = []
        for index in range(2):
            pair = {f"snapshot-{index}.json", f"snapshot-{index}.bin"}
            present = pair & names
            if present and present != pair:
                raise CollectorError(f"snapshot-{index}: metadata and binary must be present together")
            if present:
                indexes.append(index)
        if not indexes:
            raise CollectorError("capture lease contains no complete fixed snapshots")
        if indexes != list(range(len(indexes))):
            raise CollectorError("capture snapshots are not a contiguous fixed sequence")
        for index in indexes:
            base = f"{REMOTE_ROOT}/{nonce}/snapshot-{index}"
            metadata_bytes = _run(adb, serial, base + ".json", MAX_METADATA_BYTES)
            if len(metadata_bytes) > MAX_METADATA_BYTES:
                raise CollectorError("capture metadata exceeded its bound")
            try:
                metadata_value = json.loads(metadata_bytes.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise CollectorError(f"snapshot-{index}: capture metadata is not valid JSON") from error
            metadata = _validate_metadata(metadata_value, f"snapshot-{index}", nonce, index)
            binary = _run(adb, serial, base + ".bin", SNAPSHOT_BYTES)
            if len(binary) != SNAPSHOT_BYTES or total + len(binary) > TOTAL_BYTES:
                raise CollectorError("capture binary budget exceeded")
            binary_sha256 = hashlib.sha256(binary).hexdigest()
            if metadata["snapshot"]["sha256"] != binary_sha256:
                raise CollectorError("capture binary hash does not match metadata")
            commit(f"snapshot-{index}.json", metadata_bytes)
            commit(f"snapshot-{index}.bin", binary)
            total += len(binary)
            records.append({"index": index, "metadata": f"snapshot-{index}.json", "binary": f"snapshot-{index}.bin", "bytes": len(binary), "sha256": binary_sha256, "basis": metadata["basis"]})
        manifest_status = "CAPTURED" if all(record["basis"] == "current-structural-header" for record in records) else "INCOMPLETE"
        manifest = (json.dumps({"schema": 1, "kind": "arm64-runtime-code-collection", "status": manifest_status, "nonce": nonce, "snapshots": records, "totalBytes": total, "sourceSha256": SOURCE_SHA256}, sort_keys=True, separators=(",", ":")) + "\n").encode()
        commit("manifest.json", manifest)
        return destination
    except (CollectorError, OSError):
        cleanup()
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="")
    parser.add_argument("--adb", default="/home/adrian/android-sdk/platform-tools/adb")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--nonce", required=True)
    args = parser.parse_args()
    print(collect(args.adb, args.serial, args.output, args.nonce))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
