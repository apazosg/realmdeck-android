#!/usr/bin/env python3
"""HOST-only qualification of the fixed private Mesa/GLVND EGL loader.

The guest payload is copied into a new, private ``guest/`` subtree and the only
device child is the separately pinned ``GuestEglLoader`` diagnostic.  This
runner intentionally does not start the vtest server, a renderer, Wine, or the
WoW client.  The native artifact pin is a parent-owned seam and remains
fail-closed until filled in.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import stat
import struct
import subprocess
import threading
import time
import uuid
from pathlib import Path, PurePosixPath
from typing import Any

import run_loader_usb as loader


ADB = loader.ADB
SERIAL = loader.SERIAL
PACKAGE = loader.PACKAGE
UID = loader.UID
VERSION_CODE = loader.VERSION_CODE

CANDIDATE_ROOT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/"
    "private_egl_candidate-v1"
)
MANIFEST = CANDIDATE_ROOT / "candidate-manifest.json"
MANIFEST_SHA256 = "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9"
TREE_SHA256 = "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216"

SCHEMA = "wowforever.virgl-private-candidate"
SCHEMA_VERSION = 1
NATIVE_SCHEMA = "wowforever.android-guest-egl-loader/version1"
RUN_PREFIX = "VTEST-"
PRIVATE_PREFIX = "files/forever/controls/vtest-loader"
STAGE_PREFIX = "/data/local/tmp/wowforever-guest-loader-"

MANIFEST_LIMIT = 2 * 1024 * 1024
PER_FILE_LIMIT = 192 * 1024 * 1024
PAYLOAD_TOTAL_LIMIT = 512 * 1024 * 1024
PAYLOAD_COUNT = 28
PAYLOAD_TOTAL = 159_453_576
ALIAS_COUNT = 13
DEVICE_FILE_MODE = "0600"
PRIVATE_DIR_MODE = "0700"
STDOUT_LIMIT = 64 * 1024
STDERR_LIMIT = 128 * 1024
DEADLINE_SECONDS = 30
MAPS_LIMIT = 2 * 1024 * 1024

EXPECTED_VENDOR_PATH = "share/glvnd/egl_vendor.d/50_mesa.json"
EXPECTED_VENDOR_SHA256 = "cd3881df4227c13ca7f9cfe6c78820f21d5f7a00a41b299214f6f827e63be369"
EXPECTED_VENDOR_BYTES = 105
EXPECTED_VENDOR_JSON = (
    b'{\n    "file_format_version" : "1.0.0",\n    "ICD" : {\n'
    b'        "library_path" : "libEGL_mesa.so.0"\n    }\n}\n'
)
EXPECTED_SYSTEM_SONAMES = (
    "libEGL.so", "libGLESv2.so", "libandroid.so", "libc.so", "libdl.so", "liblog.so", "libm.so"
)
HEX64 = re.compile(r"^[0-9a-f]{64}$")
SAFE_COMPONENT = re.compile(r"^[A-Za-z0-9_.+-]+$")

# Fixed parent-verified native artifact; no caller-facing override. The artifact is
# host-mode 0755; it is copied into the private run as device-mode 0700.
NATIVE_ARTIFACT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-loader/GuestEglLoader"
)
NATIVE_ARTIFACT_BYTES = 23_200
NATIVE_ARTIFACT_SHA256 = "805180ce805d49887ab3d18c1ff77f60b2d801ce54e052ca4369441350b5ebf5"
NATIVE_ARTIFACT_HOST_MODE = "0755"
NATIVE_ARTIFACT_MODE = "0700"
NATIVE_NAME = "GuestEglLoader"

EVENTS = ("begin", "scope", "library", "exports", "client_extensions", "mappings", "cleanup", "end")
BROADER_FLAGS = (
    "eglContextVerified", "virpipeVerified", "hardwareRenderingVerified", "triangleVerified",
    "presentationVerified", "d3dVerified", "wowVerified",
)
BASE_KEYS = {"schema", "runId", "event", "timeNs", "pid", "uid", "pgid", "sid"}
EXPORTS = (
    "eglBindAPI", "eglChooseConfig", "eglCreateContext", "eglGetError", "eglGetPlatformDisplay",
    "eglGetProcAddress", "eglInitialize", "eglMakeCurrent", "eglQueryString", "eglTerminate",
)
EVENT_FIELDS: dict[str, set[str]] = {
    "begin": {"status"},
    "scope": {"status", "scopeValid", "runIdValid", "uidValid", "cwdValid", "environmentValid", "selectionValid", "cwd", "libraryPath"},
    "library": {"status", "loaded", "privatePath", "canonicalPath", "loadError"},
    "exports": {"status", "exportCount", "expectedExportCount", "allExports", "ownerExact", "ownerPath", "missingSymbol", "wrongOwnerSymbol"},
    "client_extensions": {
        "status", "queryValid", "extensionScanValid", "extensions", "extensionScanBytes", "extensionsTruncated",
        "hasEGL_EXT_platform_base", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context", "eglError",
    },
    "mappings": {
        "status", "mapsRead", "mapsComplete", "mapsTruncated", "mapsBytes", "mapLines", "privateLoaderMapped",
        "privateVendorMapped", "privateGalliumMapped", "privateLlvmMapped", "androidEglMapped", "androidGlesMapped", "mappingError",
    },
    "cleanup": {"status", "cleanupFailures", "handleClosed", "cleanupStatus"},
    "end": {"status", "privateLoaderVerified", "exportsVerified", "clientExtensionsVerified", "mappingsVerified", "cleanupVerified", "nativeExit", *BROADER_FLAGS},
}
BOOL_FIELDS = {
    "scopeValid", "runIdValid", "uidValid", "cwdValid", "environmentValid", "selectionValid", "loaded", "allExports",
    "ownerExact", "queryValid", "extensionScanValid", "extensionsTruncated", "mapsRead", "mapsComplete", "mapsTruncated",
    "handleClosed", "privateLoaderVerified", "exportsVerified", "clientExtensionsVerified",
    "mappingsVerified", "cleanupVerified", *BROADER_FLAGS,
}
OPTIONAL_BOOL = {
    "hasEGL_EXT_platform_base", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context",
    "privateLoaderMapped", "privateVendorMapped", "privateGalliumMapped", "privateLlvmMapped", "androidEglMapped", "androidGlesMapped",
}
INT_RANGES = {
    "timeNs": (1, 2**63 - 1), "pid": (1, 2**31 - 1), "uid": (0, 2**31 - 1),
    "pgid": (1, 2**31 - 1), "sid": (1, 2**31 - 1), "exportCount": (0, 10), "expectedExportCount": (0, 10),
    "extensionScanBytes": (0, 16 * 1024), "eglError": (0, 65_535), "mapsBytes": (0, MAPS_LIMIT), "mapLines": (0, MAPS_LIMIT), "cleanupFailures": (0, 64),
    "nativeExit": (0, 255),
}
NULLABLE_INT = {"eglError"}
STRING_FIELDS = {
    "status", "cwd", "libraryPath", "privatePath", "canonicalPath", "loadError", "ownerPath", "missingSymbol",
    "wrongOwnerSymbol", "extensions", "mappingError", "cleanupStatus",
}
NONNULL_STRING = {"status", "cleanupStatus"}


def unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _regular_nosymlink(path: Path) -> os.stat_result:
    if path.is_symlink():
        raise ValueError(f"symlink is not an approved regular file: {path}")
    info = path.stat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(f"not a regular file: {path}")
    return info


def _safe_relative(value: str, *, prefix: str | None = None) -> PurePosixPath:
    if not isinstance(value, str) or "\\" in value:
        raise ValueError("unsafe relative path")
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError("unsafe relative path")
    if any(not SAFE_COMPONENT.fullmatch(part) for part in path.parts):
        raise ValueError("unsafe relative path component")
    if prefix is not None and (not path.parts or path.parts[0] != prefix):
        raise ValueError("unexpected relative path prefix")
    return path


def _canonical_contained(root: Path, relative: PurePosixPath) -> Path:
    root_info = root.lstat()
    if stat.S_ISLNK(root_info.st_mode) or not stat.S_ISDIR(root_info.st_mode):
        raise ValueError("candidate root is not a private directory")
    canonical_root = root.resolve(strict=True)
    current = root
    for index, component in enumerate(relative.parts):
        current = current / component
        info = current.lstat()
        if stat.S_ISLNK(info.st_mode):
            raise ValueError(f"source path contains a symlink: {relative}")
        if index < len(relative.parts) - 1 and not stat.S_ISDIR(info.st_mode):
            raise ValueError(f"source path parent is not a directory: {relative}")
    canonical = current.resolve(strict=True)
    if not canonical.is_relative_to(canonical_root):
        raise ValueError(f"source path escapes candidate: {relative}")
    return current


def _stream_hash(path: Path, expected_size: int, expected_sha: str, *, exact: bytes | None = None) -> dict[str, Any]:
    if expected_size <= 0 or expected_size > PER_FILE_LIMIT or not HEX64.fullmatch(expected_sha):
        raise ValueError("invalid fixed source pin")
    info_before = path.lstat()
    if stat.S_ISLNK(info_before.st_mode) or not stat.S_ISREG(info_before.st_mode):
        raise ValueError(f"source is not a regular file: {path}")
    if info_before.st_size != expected_size or stat.S_IMODE(info_before.st_mode) != 0o600:
        raise ValueError(f"source metadata mismatch: {path}")
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError as exc:
        raise ValueError(f"source open failed: {path}") from exc
    digest = hashlib.sha256()
    total = 0
    prefix = bytearray()
    try:
        opened = os.fstat(fd)
        if (opened.st_dev, opened.st_ino, opened.st_size, stat.S_IMODE(opened.st_mode)) != (
            info_before.st_dev, info_before.st_ino, info_before.st_size, stat.S_IMODE(info_before.st_mode)
        ):
            raise ValueError(f"source identity changed before read: {path}")
        with os.fdopen(fd, "rb") as stream:
            fd = -1
            while True:
                chunk = stream.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > PER_FILE_LIMIT or total > expected_size:
                    raise ValueError(f"source exceeds per-file bound: {path}")
                digest.update(chunk)
                if len(prefix) < 4096:
                    prefix.extend(chunk[:4096 - len(prefix)])
    finally:
        if fd >= 0:
            os.close(fd)
    info_after = path.lstat()
    if (info_after.st_dev, info_after.st_ino, info_after.st_size, stat.S_IMODE(info_after.st_mode)) != (
        info_before.st_dev, info_before.st_ino, info_before.st_size, stat.S_IMODE(info_before.st_mode)
    ):
        raise ValueError(f"source identity changed after read: {path}")
    if total != expected_size or digest.hexdigest() != expected_sha:
        raise ValueError(f"source hash mismatch: {path}")
    if exact is not None and bytes(prefix) != exact:
        raise ValueError(f"source content mismatch: {path}")
    return {"bytes": total, "sha256": expected_sha, "mode": DEVICE_FILE_MODE}


def _validate_elf_header(path: Path, label: str) -> None:
    with path.open("rb") as stream:
        header = stream.read(64)
    if len(header) < 20 or header[:4] != b"\x7fELF" or header[4:6] != b"\x02\x01":
        raise ValueError(f"{label} is not ELF64 little-endian")
    if int.from_bytes(header[16:18], "little") != 3:
        raise ValueError(f"{label} is not ET_DYN")
    if int.from_bytes(header[18:20], "little") != 183:
        raise ValueError(f"{label} is not AArch64")


def _validate_aliases(document: dict[str, Any], regular_paths: set[str]) -> list[dict[str, str]]:
    aliases = document.get("approvedSymlinkAliases")
    if not isinstance(aliases, list) or len(aliases) != ALIAS_COUNT:
        raise ValueError("unexpected approved alias count")
    by_path: dict[str, dict[str, str]] = {}
    for item in aliases:
        if not isinstance(item, dict):
            raise ValueError("malformed approved alias")
        path = item.get("path")
        target = item.get("target")
        resolved = item.get("resolvedPath")
        if not all(isinstance(value, str) for value in (path, target, resolved)):
            raise ValueError("malformed approved alias")
        _safe_relative(path, prefix="lib")
        _safe_relative(resolved, prefix="lib")
        if "/" in target or target in {"", ".", ".."} or not SAFE_COMPONENT.fullmatch(target):
            raise ValueError("alias target is not a pure relative name")
        if path in by_path or path in regular_paths:
            raise ValueError("duplicate alias path")
        by_path[path] = {"path": path, "target": target, "resolvedPath": resolved}
    for alias in by_path.values():
        current = PurePosixPath(alias["path"])
        visited: set[str] = set()
        for _ in range(ALIAS_COUNT):
            key = current.as_posix()
            if key in visited:
                raise ValueError("alias chain loops")
            visited.add(key)
            if key in regular_paths:
                break
            link = by_path.get(key)
            if link is None:
                raise ValueError("alias chain leaves the private guest root")
            current = current.parent / link["target"]
        else:
            raise ValueError("alias chain exceeds bound")
        if current.as_posix() != alias["resolvedPath"] or current.as_posix() not in regular_paths:
            raise ValueError("alias canonical target mismatch")
    return list(by_path.values())


def validate_manifest_document(document: dict[str, Any], candidate_root: Path) -> list[dict[str, Any]]:
    """Validate the closed 28-file guest closure without loading an ELF."""
    if document.get("schema") != SCHEMA or document.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("unexpected private EGL manifest schema")
    if document.get("ready") is not False or document.get("present") is not False:
        raise ValueError("candidate must remain static and not ready")
    if document.get("selectedRegularFileCount") != PAYLOAD_COUNT or document.get("selectedRegularFileBytes") != PAYLOAD_TOTAL:
        raise ValueError("unexpected fixed regular-file closure")
    if document.get("selectedAliasCount") != ALIAS_COUNT:
        raise ValueError("unexpected fixed alias closure")
    if document.get("rootSelection") != ["libEGL.so.1", "libEGL_mesa.so"]:
        raise ValueError("unexpected private EGL roots")
    closure = document.get("closure")
    if not isinstance(closure, dict) or tuple(closure.get("allowlistedAndroidSystemSonames", ())) != EXPECTED_SYSTEM_SONAMES:
        raise ValueError("Android system SONAME allowlist changed")
    if closure.get("roots") != ["libEGL.so.1", "libEGL_mesa.so"]:
        raise ValueError("closure roots changed")

    materialized = document.get("materializedFiles")
    if not isinstance(materialized, list) or len(materialized) != PAYLOAD_COUNT:
        raise ValueError("unexpected materialized file count")
    regular_paths: set[str] = set()
    regular_names: set[str] = set()
    regular_sonames: set[str] = set()
    files: list[dict[str, Any]] = []
    total = 0
    for entry in materialized:
        if not isinstance(entry, dict):
            raise ValueError("malformed materialized file")
        relative = entry.get("path")
        if not isinstance(relative, str) or relative in regular_paths:
            raise ValueError("duplicate or invalid materialized path")
        rel = _safe_relative(relative, prefix="lib")
        if len(rel.parts) != 2:
            raise ValueError("materialized path is not a flat private library")
        size = entry.get("size")
        digest = entry.get("sha256")
        needed = entry.get("needed")
        if type(size) is not int or size <= 0 or size > PER_FILE_LIMIT:
            raise ValueError("invalid materialized file size")
        if not isinstance(digest, str) or not HEX64.fullmatch(digest):
            raise ValueError("invalid materialized file hash")
        if not isinstance(needed, list) or any(
            not isinstance(name, str) or "/" in name or not SAFE_COMPONENT.fullmatch(name) for name in needed
        ):
            raise ValueError("invalid ELF needed closure")
        path = _canonical_contained(candidate_root, rel)
        metadata = _stream_hash(path, size, digest)
        _validate_elf_header(path, relative)
        regular_paths.add(relative)
        regular_names.add(rel.name)
        soname = entry.get("soname")
        if soname is not None:
            if not isinstance(soname, str) or "/" in soname or not SAFE_COMPONENT.fullmatch(soname):
                raise ValueError(f"invalid ELF SONAME: {relative}")
            regular_sonames.add(soname)
        total += size
        files.append({
            "destination": f"guest/{relative}", "sourcePath": relative, "path": path,
            "sha256": digest, "bytes": size, "mode": DEVICE_FILE_MODE, "needed": list(needed),
            "sourceMode": format(stat.S_IMODE(path.stat().st_mode), "04o"), "sourceMetadata": metadata,
        })
    if total != PAYLOAD_TOTAL or total > PAYLOAD_TOTAL_LIMIT:
        raise ValueError("candidate regular-file byte total changed")
    aliases = _validate_aliases(document, regular_paths)

    vendor = document.get("vendorJson")
    if not isinstance(vendor, dict) or vendor.get("path") != EXPECTED_VENDOR_PATH or vendor.get("size") != EXPECTED_VENDOR_BYTES or vendor.get("sha256") != EXPECTED_VENDOR_SHA256 or vendor.get("libraryPath") != "libEGL_mesa.so.0":
        raise ValueError("vendor JSON pin changed")
    vendor_path = _canonical_contained(candidate_root, PurePosixPath(EXPECTED_VENDOR_PATH))
    _stream_hash(vendor_path, EXPECTED_VENDOR_BYTES, EXPECTED_VENDOR_SHA256, exact=EXPECTED_VENDOR_JSON)
    if len(files) != PAYLOAD_COUNT or sum(item["bytes"] for item in files) != PAYLOAD_TOTAL:
        raise ValueError("materialized regular-file closure changed")
    # The manifest's provider table is informational only; every DT_NEEDED edge
    # must still resolve either to one of these regular SONAMEs or bare Bionic.
    for item in files:
        if any(name not in regular_names and name not in regular_sonames and name not in EXPECTED_SYSTEM_SONAMES for name in item["needed"]):
            raise ValueError(f"unapproved DT_NEEDED edge: {item['sourcePath']}")
    # The reviewed manifest's 28 materialized ELF records carry the fixed
    # selected-byte total.  The 105-byte vendor JSON is a separately recorded
    # regular file in the same tree and is added by the copier without
    # changing that manifest ledger.
    return files


def _tree_hash(root: Path, allowed_files: set[str], allowed_links: dict[str, str] | None = None) -> str:
    entries = sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix())
    expected_dirs = {"lib", "share", "share/glvnd", "share/glvnd/egl_vendor.d"}
    expected_files = allowed_files | {"candidate-manifest.json"}
    expected_links = allowed_links or {}
    digest = hashlib.sha256()
    total = 0
    for path in entries:
        relative = path.relative_to(root).as_posix()
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode):
            if relative not in expected_links or os.readlink(path) != expected_links[relative]:
                raise ValueError("candidate tree contains an unapproved symlink")
            digest.update(relative.encode() + b"\0L\0" + expected_links[relative].encode() + b"\0")
            continue
        if stat.S_ISDIR(info.st_mode):
            if relative not in expected_dirs:
                raise ValueError(f"candidate tree contains an unknown directory: {relative}")
            digest.update(relative.encode() + b"\0D\0")
        elif stat.S_ISREG(info.st_mode):
            if relative not in expected_files:
                raise ValueError(f"candidate tree contains an unknown file: {relative}")
            digest.update(relative.encode() + b"\0F\0")
            with path.open("rb") as stream:
                while True:
                    chunk = stream.read(1024 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > PAYLOAD_TOTAL_LIMIT:
                        raise ValueError("candidate tree hash budget exceeded")
                    digest.update(chunk)
            digest.update(b"\0")
        else:
            raise ValueError("candidate tree contains a non-regular object")
    return digest.hexdigest()


def load_candidate() -> tuple[dict[str, Any], list[dict[str, Any]], bytes]:
    """Authorize the fixed manifest hash before parsing any JSON."""
    _regular_nosymlink(MANIFEST)
    if MANIFEST.stat().st_size > MANIFEST_LIMIT:
        raise ValueError("candidate manifest exceeds 2 MiB")
    digest = hashlib.sha256()
    raw_parts: list[bytes] = []
    raw_size = 0
    with MANIFEST.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            raw_parts.append(chunk)
            raw_size += len(chunk)
            if raw_size > MANIFEST_LIMIT:
                raise ValueError("candidate manifest exceeds 2 MiB")
    raw = b"".join(raw_parts)
    if digest.hexdigest() != MANIFEST_SHA256:
        raise ValueError("candidate manifest hash mismatch")
    document = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_json_pairs)
    if not isinstance(document, dict):
        raise ValueError("candidate manifest is not an object")
    files = validate_manifest_document(document, CANDIDATE_ROOT)
    aliases_for_tree = {item["path"]: item["target"] for item in document["approvedSymlinkAliases"]}
    if _tree_hash(CANDIDATE_ROOT, {item["sourcePath"] for item in files} | {EXPECTED_VENDOR_PATH}, aliases_for_tree) != TREE_SHA256:
        raise ValueError("candidate tree hash mismatch")
    capture_document = dict(document)
    capture_document["authorizedManifestSha256"] = MANIFEST_SHA256
    capture_document["authorizedTreeSha256"] = TREE_SHA256
    return capture_document, files, raw


def probe_file() -> dict[str, Any]:
    """Validate the parent-supplied native artifact without executing it."""
    if not isinstance(NATIVE_ARTIFACT_BYTES, int) or NATIVE_ARTIFACT_BYTES <= 0 or not HEX64.fullmatch(NATIVE_ARTIFACT_SHA256):
        raise ValueError("native GuestEglLoader artifact pin is still a placeholder")
    if NATIVE_ARTIFACT_BYTES > 64 * 1024 or NATIVE_ARTIFACT_SHA256 == "0" * 64:
        raise ValueError("native GuestEglLoader artifact pin is invalid")
    info = _regular_nosymlink(NATIVE_ARTIFACT)
    if info.st_size != NATIVE_ARTIFACT_BYTES or stat.S_IMODE(info.st_mode) != int(NATIVE_ARTIFACT_HOST_MODE, 8):
        raise ValueError("native GuestEglLoader artifact metadata mismatch")
    raw = NATIVE_ARTIFACT.read_bytes()
    if len(raw) != NATIVE_ARTIFACT_BYTES or hashlib.sha256(raw).hexdigest() != NATIVE_ARTIFACT_SHA256:
        raise ValueError("native GuestEglLoader artifact hash mismatch")
    loader._elf64_aarch64_dyn(raw, NATIVE_NAME, require_linker_interp=True)
    _validate_native_dynamic(raw)
    return {"destination": "guest/bin/" + NATIVE_NAME, "path": NATIVE_ARTIFACT,
            "bytes": NATIVE_ARTIFACT_BYTES, "sha256": NATIVE_ARTIFACT_SHA256, "mode": NATIVE_ARTIFACT_MODE}


def _validate_native_dynamic(data: bytes) -> None:
    """Reject an artifact that can resolve anything beyond Android libc/libdl."""
    if len(data) < 64:
        raise ValueError("native GuestEglLoader ELF header is truncated")
    phoff = int.from_bytes(data[32:40], "little")
    phentsize = int.from_bytes(data[54:56], "little")
    phnum = int.from_bytes(data[56:58], "little")
    if phentsize < 56 or phnum > 64 or phoff + phentsize * phnum > len(data):
        raise ValueError("native GuestEglLoader program headers are invalid")
    loads: list[tuple[int, int, int, int]] = []
    dynamic: tuple[int, int] | None = None
    for index in range(phnum):
        header = data[phoff + index * phentsize:phoff + (index + 1) * phentsize]
        kind = int.from_bytes(header[:4], "little")
        offset = int.from_bytes(header[8:16], "little")
        address = int.from_bytes(header[16:24], "little")
        file_size = int.from_bytes(header[32:40], "little")
        memory_size = int.from_bytes(header[40:48], "little")
        if offset + file_size > len(data):
            raise ValueError("native GuestEglLoader segment is out of bounds")
        if kind == 1:
            loads.append((address, memory_size, offset, file_size))
        elif kind == 2:
            dynamic = (offset, file_size)
    if dynamic is None or dynamic[1] % 16:
        raise ValueError("native GuestEglLoader has no bounded dynamic table")

    def address_to_offset(address: int) -> int:
        for start, memory_size, offset, file_size in loads:
            if start <= address < start + memory_size and address - start < file_size:
                return offset + address - start
        raise ValueError("native GuestEglLoader dynamic address is outside PT_LOAD")

    tags: list[tuple[int, int]] = []
    for offset in range(dynamic[0], dynamic[0] + dynamic[1], 16):
        tag, value = struct.unpack_from("<qQ", data, offset)
        tags.append((tag, value))
        if tag == 0:
            break
    values = {tag: value for tag, value in tags}
    if 5 not in values or 10 not in values:
        raise ValueError("native GuestEglLoader string table is missing")
    strings_offset = address_to_offset(values[5])
    strings_size = values[10]
    if strings_size > len(data) - strings_offset:
        raise ValueError("native GuestEglLoader string table is out of bounds")
    strings = data[strings_offset:strings_offset + strings_size]

    def string_at(index: int) -> str:
        if index >= len(strings):
            raise ValueError("native GuestEglLoader string offset is invalid")
        end = strings.find(b"\0", index)
        if end < 0:
            raise ValueError("native GuestEglLoader string is unterminated")
        return strings[index:end].decode("ascii", "strict")

    needed = {string_at(value) for tag, value in tags if tag == 1}
    if needed != {"libc.so", "libdl.so"}:
        raise ValueError("native GuestEglLoader is not linked only to libc/libdl")
    if 15 in values or 29 in values:
        raise ValueError("native GuestEglLoader has an unapproved search path")


def _q(value: str) -> str:
    return shlex.quote(value)


def _validate_run(run: str) -> None:
    if not re.fullmatch(r"VTEST-[0-9a-f]{32}", run):
        raise ValueError("invalid fixed RUN")


def _private_relative(run: str) -> str:
    _validate_run(run)
    return f"{PRIVATE_PREFIX}/{run}"


def _validate_copy_inputs(files: list[dict[str, Any]], probe: dict[str, Any]) -> None:
    expected = {f"guest/{item['sourcePath']}" for item in files}
    if len(files) != PAYLOAD_COUNT or {item.get("destination") for item in files} != expected:
        raise ValueError("guest copier received an unexpected payload closure")
    for item in files:
        path = item.get("path")
        if not isinstance(path, Path) or item.get("mode") != DEVICE_FILE_MODE or not isinstance(item.get("sha256"), str):
            raise ValueError("guest copier received malformed payload metadata")
        expected_path = _canonical_contained(CANDIDATE_ROOT, PurePosixPath(item["sourcePath"]))
        if path.absolute() != expected_path.absolute():
            raise ValueError("guest copier source escaped candidate containment")
        _stream_hash(path, item["bytes"], item["sha256"], exact=EXPECTED_VENDOR_JSON if item["sourcePath"] == EXPECTED_VENDOR_PATH else None)
    if probe.get("destination") != "guest/bin/GuestEglLoader" or probe.get("mode") != NATIVE_ARTIFACT_MODE:
        raise ValueError("guest copier received an unapproved native artifact")


def _vendor_item() -> dict[str, Any]:
    path = _canonical_contained(CANDIDATE_ROOT, PurePosixPath(EXPECTED_VENDOR_PATH))
    _stream_hash(path, EXPECTED_VENDOR_BYTES, EXPECTED_VENDOR_SHA256, exact=EXPECTED_VENDOR_JSON)
    return {"destination": f"guest/{EXPECTED_VENDOR_PATH}", "sourcePath": EXPECTED_VENDOR_PATH,
            "path": path, "sha256": EXPECTED_VENDOR_SHA256, "bytes": EXPECTED_VENDOR_BYTES,
            "mode": DEVICE_FILE_MODE, "needed": [], "sourceMode": "0600"}


def _private_copy_script(stage: str, relative: str, files: list[dict[str, Any]], vendor: dict[str, Any], probe: dict[str, Any], aliases: list[dict[str, str]]) -> str:
    guest = f"{relative}/guest"
    directories = ["files", "files/forever", "files/forever/controls", PRIVATE_PREFIX, relative, guest,
                   f"{guest}/bin", f"{guest}/lib", f"{guest}/share", f"{guest}/share/glvnd", f"{guest}/share/glvnd/egl_vendor.d"]
    commands = ["umask 077", "set -e", "set -C"]
    for directory in directories[:4]:
        commands.append(f"test ! -L {_q(directory)} && (test -d {_q(directory)} || mkdir -m 700 {_q(directory)})")
    commands.append(f"test ! -e {_q(relative)} && test ! -L {_q(relative)}")
    commands.append(f"mkdir -m 700 {_q(relative)}")
    for directory in directories[5:]:
        commands.append(f"test ! -L {_q(directory)} && (test -d {_q(directory)} || mkdir -m 700 {_q(directory)})")
    all_items = [*files, vendor, probe]
    for index, item in enumerate(all_items):
        destination = f"{relative}/{item['destination']}"
        staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
        commands.append(f"test ! -e {_q(destination)} && test ! -L {_q(destination)}")
        commands.append(f"cat {_q(staged)} > {_q(destination)}")
        commands.append(f"chmod {item['mode']} {_q(destination)}")
    for alias in aliases:
        destination = f"{guest}/{alias['path']}"
        commands.append(f"test ! -e {_q(destination)} && test ! -L {_q(destination)}")
        commands.append(f"ln -s {_q(alias['target'])} {_q(destination)}")
    commands.append("printf VTEST_GUEST_UPLOAD_OK")
    return "; ".join(commands)


def _device_check(path: str, sha256: str, mode: str) -> None:
    hashed = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "sha256sum", path), "guest device artifact hash", 4096).decode(errors="replace").split()
    if not hashed or hashed[0] != sha256:
        raise RuntimeError(f"guest device artifact hash mismatch: {path}")
    actual_mode = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%a", path), "guest device artifact mode", 4096).strip()
    if actual_mode != mode.lstrip("0").encode():
        raise RuntimeError(f"guest device artifact mode mismatch: {path}")


def _alias_canonical_target(app_root: str, relative: str, alias: dict[str, str]) -> str:
    accepted = {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}
    if app_root not in accepted:
        raise ValueError("unapproved application root for alias verification")
    return f"{app_root}/{relative}/guest/{alias['resolvedPath']}"


def _canonical_app_root(app_root: str) -> str:
    accepted = {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}
    if app_root not in accepted:
        raise ValueError("unapproved application root")
    actual = loader.require_adb(
        loader.adb("exec-out", "run-as", PACKAGE, "readlink", "-f", app_root),
        "canonical own application root", 4096,
    ).decode("ascii", "strict").strip()
    if actual not in accepted:
        raise ValueError("canonical application root escaped approved scope")
    return actual


def copy_payload(
    files: list[dict[str, Any]], probe: dict[str, Any], run: str, aliases: list[dict[str, str]], *, app_root: str,
) -> tuple[str, dict[str, Any]]:
    """Upload only this run's known files, then verify every private target."""
    relative = _private_relative(run)
    app_root = _canonical_app_root(app_root)
    _validate_copy_inputs(files, probe)
    vendor = _vendor_item()
    stage = STAGE_PREFIX + uuid.uuid4().hex
    stage_created = False
    items = [*files, vendor, probe]
    cleanup_failures: list[str] = []
    operation_error: Exception | None = None
    try:
        loader.require_adb(loader.adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        loader.require_adb(loader.adb("shell", "mkdir", "-m", "755", stage), "exclusive public staging directory")
        stage_created = True
        for index, item in enumerate(items):
            staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
            loader.require_adb(loader.adb("push", str(item["path"]), staged, timeout=90), "normal ADB sync")
            loader.require_adb(loader.adb("shell", "chmod", "0644", staged), "staging file mode")
        result = loader.adb("exec-out", "run-as", PACKAGE, "sh", "-c",
                            _private_copy_script(stage, relative, files, vendor, probe, aliases), timeout=90)
        if result.returncode or result.stdout != b"VTEST_GUEST_UPLOAD_OK" or result.stderr:
            raise RuntimeError("private guest upload sentinel was not exact")
        for item in items:
            _device_check(f"{relative}/{item['destination']}", item["sha256"], item["mode"])
        for alias in aliases:
            alias_path = f"{relative}/guest/{alias['path']}"
            target = _alias_canonical_target(app_root, relative, alias)
            actual = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "readlink", alias_path), "guest alias target", 4096)
            if actual != (alias["target"] + "\n").encode():
                raise RuntimeError(f"guest alias target mismatch: {alias_path}")
            canonical = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "readlink", "-f", alias_path), "guest alias canonical target", 4096)
            if canonical != (f"{target}\n").encode():
                raise RuntimeError(f"guest alias canonical target mismatch: {alias_path}")
            if canonical.startswith(b"/system/") or canonical.startswith(b"/vendor/"):
                raise RuntimeError("guest alias resolved into a system path")
    except Exception as exc:
        operation_error = exc
    finally:
        if stage_created:
            for index, item in enumerate(items):
                staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
                try:
                    result = loader.adb("shell", "rm", "-f", staged)
                    if result.returncode or result.stdout or result.stderr:
                        cleanup_failures.append(staged)
                except Exception:
                    cleanup_failures.append(staged)
            try:
                result = loader.adb("shell", "rmdir", stage)
                if result.returncode or result.stdout or result.stderr:
                    cleanup_failures.append(stage)
            except Exception:
                cleanup_failures.append(stage)
    if operation_error is not None:
        if cleanup_failures:
            raise RuntimeError(f"guest upload failed ({operation_error}); owned staging cleanup failed: {cleanup_failures}") from operation_error
        raise operation_error
    if cleanup_failures:
        raise RuntimeError(f"owned staging cleanup failed; no payload executed: {cleanup_failures}")
    return relative, {"path": stage, "stagingClean": True, "failures": [], "fileCount": PAYLOAD_COUNT, "aliasCount": ALIAS_COUNT}


def copy_payload_to_existing_run(
    files: list[dict[str, Any]], probe: dict[str, Any], run: str, aliases: list[dict[str, str]], *,
    app_root: str, server_files: list[dict[str, Any]], server_manifest_sha256: str,
) -> tuple[str, dict[str, Any]]:
    """Append the fixed guest subtree to an already-created source-server run.

    This is intentionally separate from :func:`copy_payload`: the historical
    loader runner still creates a fresh run, while the context control must
    attach only after HOST has copied and bound the immutable Android-default
    server.  No server file, alias, or identity file is replaced here.
    """
    relative = _private_relative(run)
    app_root = _canonical_app_root(app_root)
    _validate_copy_inputs(files, {"destination": "guest/bin/GuestEglLoader", "mode": NATIVE_ARTIFACT_MODE})
    import run_source_handshake_usb as fixed_source
    if server_manifest_sha256 != fixed_source.ANDROID_DEFAULT.manifest_sha256:
        raise ValueError("guest append requires the fixed Android-default server manifest")
    fixed_source._validate_copy_inputs(server_files, candidate=fixed_source.ANDROID_DEFAULT)
    if probe.get("destination") != "guest/bin/GuestEglContext" or probe.get("mode") != "0700":
        raise ValueError("guest context copier received an unapproved native artifact")
    probe_path = probe.get("path")
    if not isinstance(probe_path, Path):
        raise ValueError("guest context copier received malformed native artifact")
    _regular_nosymlink(probe_path)
    if probe_path.stat().st_size != probe.get("bytes") or hashlib.sha256(probe_path.read_bytes()).hexdigest() != probe.get("sha256"):
        raise ValueError("guest context native artifact changed")
    _validate_elf_header(probe_path, "GuestEglContext")
    vendor = _vendor_item()
    if len(server_files) != 10 or not isinstance(server_manifest_sha256, str) or not HEX64.fullmatch(server_manifest_sha256):
        raise ValueError("guest context copier received an unexpected server closure")
    server_destinations = {
        "bin/virgl_test_server", "lib/libX11.so", "lib/libXau.so", "lib/libXdmcp.so",
        "lib/libandroid-support.so", "lib/libdrm.so", "lib/libepoxy.so", "lib/libgbm.so",
        "lib/libvirglrenderer.so", "lib/libxcb.so",
    }
    if {item.get("destination") for item in server_files} != server_destinations:
        raise ValueError("guest context server closure changed")
    for item in server_files:
        if item.get("mode") not in {"0600", "0700"} or not isinstance(item.get("sha256"), str) or not HEX64.fullmatch(item["sha256"]):
            raise ValueError("guest context server metadata is malformed")

    guest = f"{relative}/guest"
    stage = STAGE_PREFIX + uuid.uuid4().hex
    items = [*files, vendor, probe]
    cleanup_failures: list[str] = []
    operation_error: Exception | None = None
    stage_created = False
    server_checks = []
    for item in server_files:
        destination = f"{relative}/{item['destination']}"
        server_checks.extend([
            f"test -f {_q(destination)} && test ! -L {_q(destination)}",
            f"test \"$(sha256sum {_q(destination)} | cut -d' ' -f1)\" = {_q(item['sha256'])}",
            f"test \"$(stat -c '%a' {_q(destination)})\" = {_q(item['mode'].lstrip('0'))}",
        ])
    server_aliases = {
        f"{relative}/lib/libEGL.so.1": "/system/lib64/libEGL.so",
        f"{relative}/lib/libGLESv2.so.2": "/system/lib64/libGLESv2.so",
        f"{relative}/lib/libvirglrenderer.so.1": "libvirglrenderer.so",
    }
    for destination, target in server_aliases.items():
        server_checks.extend([
            f"test -L {_q(destination)} && test \"$(readlink {_q(destination)})\" = {_q(target)}",
        ])
    try:
        loader.require_adb(loader.adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        loader.require_adb(loader.adb("shell", "mkdir", "-m", "755", stage), "exclusive public staging directory")
        stage_created = True
        for index, item in enumerate(items):
            staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
            loader.require_adb(loader.adb("push", str(item["path"]), staged, timeout=90), "normal ADB sync")
            loader.require_adb(loader.adb("shell", "chmod", "0644", staged), "staging file mode")
        directories = [f"{guest}", f"{guest}/bin", f"{guest}/lib", f"{guest}/share",
                       f"{guest}/share/glvnd", f"{guest}/share/glvnd/egl_vendor.d"]
        commands = ["umask 077", "set -e", "set -C", *server_checks,
                    f"test -d {_q(relative)} && test ! -L {_q(relative)}",
                    f"test ! -e {_q(guest)} && test ! -L {_q(guest)}",
                    f"test ! -e {_q(relative + '/server.identity')} && test ! -L {_q(relative + '/server.identity')}"]
        for directory in directories:
            commands.append(f"test ! -L {_q(directory)} && mkdir -m 700 {_q(directory)}")
        for index, item in enumerate(items):
            destination = f"{relative}/{item['destination']}"
            staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
            commands.extend([
                f"test ! -e {_q(destination)} && test ! -L {_q(destination)}",
                f"cat {_q(staged)} > {_q(destination)}",
                f"chmod {item['mode']} {_q(destination)}",
            ])
        for alias in aliases:
            destination = f"{guest}/{alias['path']}"
            commands.extend([f"test ! -e {_q(destination)} && test ! -L {_q(destination)}",
                             f"ln -s {_q(alias['target'])} {_q(destination)}"])
        commands.extend(server_checks)
        commands.append("printf VTEST_GUEST_CONTEXT_UPLOAD_OK")
        result = loader.adb("exec-out", "run-as", PACKAGE, "sh", "-c", "; ".join(commands), timeout=90)
        if result.returncode or result.stdout != b"VTEST_GUEST_CONTEXT_UPLOAD_OK" or result.stderr:
            raise RuntimeError("private guest context upload sentinel was not exact")
        for item in items:
            _device_check(f"{relative}/{item['destination']}", item["sha256"], item["mode"])
        for alias in aliases:
            alias_path = f"{relative}/guest/{alias['path']}"
            actual = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "readlink", alias_path), "guest context alias", 4096)
            if actual != (alias["target"] + "\n").encode():
                raise RuntimeError(f"guest context alias target mismatch: {alias_path}")
            canonical = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "readlink", "-f", alias_path), "guest context alias canonical target", 4096)
            expected = _alias_canonical_target(app_root, relative, alias)
            if canonical != (expected + "\n").encode():
                raise RuntimeError(f"guest context alias escaped the verified guest subtree: {alias_path}")
    except Exception as exc:
        operation_error = exc
    finally:
        if stage_created:
            for index, item in enumerate(items):
                staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
                try:
                    result = loader.adb("shell", "rm", "-f", staged)
                    if result.returncode or result.stdout or result.stderr: cleanup_failures.append(staged)
                except Exception:
                    cleanup_failures.append(staged)
            try:
                result = loader.adb("shell", "rmdir", stage)
                if result.returncode or result.stdout or result.stderr: cleanup_failures.append(stage)
            except Exception:
                cleanup_failures.append(stage)
    if operation_error is not None:
        if cleanup_failures:
            raise RuntimeError(f"guest context upload failed ({operation_error}); owned staging cleanup failed: {cleanup_failures}") from operation_error
        raise operation_error
    if cleanup_failures:
        raise RuntimeError(f"owned staging cleanup failed; no context executed: {cleanup_failures}")
    return relative, {"path": stage, "stagingClean": True, "failures": [], "fileCount": PAYLOAD_COUNT, "aliasCount": ALIAS_COUNT,
                      "serverManifestSha256": server_manifest_sha256, "serverFilesVerified": True}


def native_command(private_root: str, run: str) -> list[str]:
    relative = _private_relative(run)
    roots = [f"/data/user/0/{PACKAGE}/{relative}", f"/data/data/{PACKAGE}/{relative}"]
    if private_root not in roots:
        raise ValueError("unapproved private guest root")
    guest = f"{private_root}/guest"
    inner = (
        f"cd {_q(private_root)} && exec /system/bin/env -i "
        "PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data "
        f"{_q('LD_LIBRARY_PATH=' + guest + '/lib:/system/lib64')} "
        f"{_q('TMPDIR=' + private_root)} "
        f"{_q('__EGL_VENDOR_LIBRARY_FILENAMES=' + guest + '/share/glvnd/egl_vendor.d/50_mesa.json')} "
        "GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=vtest.sock "
        f"/system/bin/linker64 {_q(guest + '/bin/' + NATIVE_NAME)} --run-id {run}"
    )
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "sh", "-c", shlex.quote(inner)]


def valid_event(row: dict[str, Any]) -> bool:
    event = row.get("event")
    if event not in EVENT_FIELDS or set(row) != BASE_KEYS | EVENT_FIELDS[event]:
        return False
    if not all(isinstance(row.get(key), str) for key in ("schema", "runId", "event")):
        return False
    for key, value in row.items():
        if key in BOOL_FIELDS and type(value) is not bool:
            return False
        if key in OPTIONAL_BOOL and value is not None and type(value) is not bool:
            return False
        if key in INT_RANGES:
            low, high = INT_RANGES[key]
            if value is None and key in NULLABLE_INT:
                continue
            if type(value) is not int or not low <= value <= high:
                return False
        if key in STRING_FIELDS:
            if key in NONNULL_STRING and (not isinstance(value, str) or not 0 < len(value) <= 64):
                return False
            if value is not None and (not isinstance(value, str) or len(value) > (4096 if key in {"cwd", "libraryPath", "privatePath", "canonicalPath", "ownerPath"} else 512)):
                return False
    return True


def validate_output(raw: bytes, run: str, exit_code: int | None, complete: bool, truncated: bool = False) -> tuple[str, list[dict[str, Any]]]:
    if not complete or truncated or not raw or len(raw) > STDOUT_LIMIT or not raw.endswith(b"\n"):
        return "UNKNOWN", []
    lines = raw.splitlines()
    if len(lines) != len(EVENTS) or any(len(line) >= 24_576 for line in lines):
        return "UNKNOWN", []
    try:
        rows = [json.loads(line.decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs) for line in lines]
        if any(not isinstance(row, dict) or not valid_event(row) for row in rows):
            return "UNKNOWN", rows
        if tuple(row["event"] for row in rows) != EVENTS:
            return "UNKNOWN", rows
        first = rows[0]
        if first["status"] != "STARTED":
            return "UNKNOWN", rows
        times = []
        for row in rows:
            if row["schema"] != NATIVE_SCHEMA or row["runId"] != run or row["uid"] != UID or row["pid"] != first["pid"]:
                return "UNKNOWN", rows
            if row["pgid"] != first["pgid"] or row["sid"] != first["sid"]:
                return "UNKNOWN", rows
            times.append(row["timeNs"])
        if times != sorted(times):
            return "UNKNOWN", rows
        end = rows[-1]
        if end["nativeExit"] != exit_code or any(end[key] is not False for key in BROADER_FLAGS):
            return "UNKNOWN", rows
        if end["status"] == "LOADER_FAILED":
            return ("LOADER_FAILED" if exit_code == 1 else "UNKNOWN"), rows
        if end["status"] != "GUEST_LOADER_PASS":
            return "UNKNOWN", rows
        scope, library, exports, query, mappings, cleanup = rows[1:7]
        allowed_roots = (
            f"/data/user/0/{PACKAGE}/files/forever/controls/vtest-loader/{run}",
            f"/data/data/{PACKAGE}/files/forever/controls/vtest-loader/{run}",
        )
        if scope["cwd"] not in allowed_roots:
            return "UNKNOWN", rows
        private_root = scope["cwd"]
        allowed_library = f"{private_root}/guest/lib/libEGL.so.1.1.0"
        good = (
            exit_code == 0
            and scope["status"] == "VALID" and all(scope[key] is True for key in ("scopeValid", "runIdValid", "uidValid", "cwdValid", "environmentValid", "selectionValid"))
            and scope["cwd"] == private_root and scope["libraryPath"] == allowed_library
            and library["status"] == "LOADED" and library["loaded"] is True
            and library["privatePath"] == allowed_library and library["canonicalPath"] == allowed_library
            and library["loadError"] is None
            and exports["status"] == "EXPORTED" and exports["exportCount"] == 10 and exports["expectedExportCount"] == 10
            and exports["allExports"] is True and exports["ownerExact"] is True and exports["ownerPath"] == allowed_library
            and exports["missingSymbol"] is None and exports["wrongOwnerSymbol"] is None
            and query["status"] == "MEASURED" and query["queryValid"] is True and query["extensionScanValid"] is True
            and query["eglError"] == 0x3000 and query["extensionScanBytes"] <= 16 * 1024
            and isinstance(query["extensions"], str)
            and all(type(query[key]) is bool for key in
                    ("hasEGL_EXT_platform_base", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context"))
            and mappings["status"] == "MAPPED" and mappings["mapsRead"] is True and mappings["mapsComplete"] is True and mappings["mapsTruncated"] is False
            and mappings["privateLoaderMapped"] is True and mappings["privateVendorMapped"] is True
            and mappings["privateGalliumMapped"] is True and mappings["privateLlvmMapped"] is True
            and mappings["androidEglMapped"] is False and mappings["androidGlesMapped"] is False and mappings["mappingError"] is None
            and cleanup["status"] == "CLEAN" and cleanup["cleanupFailures"] == 0
            and cleanup["handleClosed"] is True and cleanup["cleanupStatus"] == "CLEAN"
            and end["privateLoaderVerified"] is True and end["exportsVerified"] is True
            and end["clientExtensionsVerified"] is True and end["mappingsVerified"] is True and end["cleanupVerified"] is True
        )
        return ("GUEST_LOADER_PASS" if good else "UNKNOWN"), rows
    except (UnicodeError, ValueError, KeyError, TypeError):
        return "UNKNOWN", []


def force_stop(timeout: float) -> dict[str, Any]:
    result = loader.adb("shell", "am", "force-stop", PACKAGE, timeout=timeout)
    return {"action": "authorized fixed lab package force-stop after native timeout/error", "returnCode": result.returncode,
            "stdout": result.stdout[:2048].decode(errors="replace"), "stderr": result.stderr[:2048].decode(errors="replace")}


def owned_processes(timeout: float) -> list[dict[str, Any]]:
    raw = loader.require_adb(
        loader.adb("shell", "ps", "-A", "-o", "UID,PID,NAME", timeout=timeout),
        "bounded own-UID post-run check",
    )
    aliases = {str(UID), f"u0_a{UID - 10000}"}
    rows: list[dict[str, Any]] = []
    for line in raw.decode("utf-8", "replace").splitlines()[1:]:
        fields = line.split(None, 2)
        if len(fields) == 3 and fields[0] in aliases:
            rows.append({"pid": fields[1], "name": fields[2]})
    return rows


def cleanup_child(process: Any, threads: list[threading.Thread], must_stop: bool) -> dict[str, Any]:
    deadline = time.monotonic() + 10
    errors: list[str] = []
    stopped: dict[str, Any] | None = None
    remaining: list[dict[str, Any]] = [{"state": "UNKNOWN"}]

    def budget() -> float:
        left = deadline - time.monotonic()
        if left <= 0:
            raise TimeoutError("cleanup deadline expired")
        return min(left, 3)

    try:
        remaining = owned_processes(budget())
    except Exception as exc:
        errors.append("ownership check: " + str(exc)[:1024])
        must_stop = True
    try:
        must_stop = must_stop or bool(remaining) or process.poll() is None or any(thread.is_alive() for thread in threads)
        if must_stop:
            try:
                stopped = force_stop(budget())
                if stopped["returnCode"] or stopped["stdout"] or stopped["stderr"]:
                    errors.append("fixed-package force-stop did not complete cleanly")
            except Exception as exc:
                stopped = {"action": "fixed-package force-stop attempted", "status": "UNKNOWN"}
                errors.append("force-stop: " + str(exc)[:1024])
    finally:
        try:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=1)
        except Exception as exc:
            errors.append("held host ADB handle: " + str(exc)[:1024])
        for thread in threads:
            try:
                thread.join(timeout=max(0, min(1, deadline - time.monotonic())))
            except Exception as exc:
                errors.append("drain: " + str(exc)[:1024])
        if any(thread.is_alive() for thread in threads):
            errors.append("output drain did not finish")
        if must_stop:
            try:
                remaining = owned_processes(budget())
            except Exception as exc:
                remaining = [{"state": "UNKNOWN"}]
                errors.append("final ownership check: " + str(exc)[:1024])
    return {"forcedCleanup": stopped, "remainingOwnProcesses": remaining, "errors": errors}


def _write_evidence(out: Path, name: str, data: bytes) -> str | None:
    try:
        loader._write_exclusive(out / name, data)
        return None
    except Exception as exc:
        return f"{name}: {str(exc)[:1024]}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    parent = loader._external_parent(args.output)
    document, files, manifest_raw = load_candidate()
    probe = probe_file()
    shell_status = loader.remote_status_preflight()
    uid, app_root, selinux = loader._package_preflight()
    if owned_processes(15):
        raise RuntimeError("unexpected live application process before private guest upload")
    # load_candidate has already validated the fixed alias ledger.  Keeping the
    # fallback empty is useful only for offline tests that replace the loader;
    # the real fixed manifest can never reach this branch without aliases.
    aliases = document.get("approvedSymlinkAliases", [])
    run = RUN_PREFIX + uuid.uuid4().hex
    out = parent / ("native-GUEST-" + run)
    out.mkdir(mode=0o700)
    relative, staging = copy_payload(files, probe, run, aliases, app_root=app_root)
    private_root = app_root + "/" + relative
    command = native_command(private_root, run)
    capture = {
        "schema": 1, "runId": run, "serial": SERIAL, "package": PACKAGE, "uid": uid, "versionCode": VERSION_CODE,
        "selinuxContext": selinux, "manifestSha256": MANIFEST_SHA256, "treeSha256": TREE_SHA256,
        "payloadFiles": [{key: item[key] for key in ("destination", "bytes", "sha256", "mode")} for item in files],
        "approvedAliases": aliases, "vendorJson": {"path": EXPECTED_VENDOR_PATH, "sha256": EXPECTED_VENDOR_SHA256, "bytes": EXPECTED_VENDOR_BYTES},
        "systemSonameAllowlist": list(EXPECTED_SYSTEM_SONAMES),
        "nativeArtifact": {"destination": probe["destination"], "path": str(probe["path"]), "bytes": probe["bytes"],
                           "sha256": probe["sha256"], "mode": probe["mode"]}, "command": command,
        "workingDirectory": private_root, "stagingCleanup": staging, "shellStatusPreflight": shell_status,
        "executionStarted": False, "requestedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        **{key: False for key in BROADER_FLAGS},
    }
    # Durable ownership/pins must exist before any child is spawned.
    loader._write_exclusive(out / "capture.json", (json.dumps(capture, indent=2) + "\n").encode())

    process: subprocess.Popen[bytes] | None = None
    results: dict[str, Any] = {}
    threads: list[threading.Thread] = []
    timed_out = False
    error: str | None = None
    cleanup: dict[str, Any] = {"forcedCleanup": None, "remainingOwnProcesses": [], "errors": []}
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for name, pipe, limit in (("stdout", process.stdout, STDOUT_LIMIT), ("stderr", process.stderr, STDERR_LIMIT)):
            thread = threading.Thread(target=loader._drain, args=(name, pipe, limit, results), daemon=True)
            threads.append(thread)
            thread.start()
        process.wait(timeout=DEADLINE_SECONDS)
    except subprocess.TimeoutExpired:
        timed_out = True
    except Exception as exc:
        error = str(exc)[:2048]
    finally:
        if process is not None:
            cleanup = cleanup_child(process, threads, timed_out or error is not None)
    complete = (
        process is not None and not timed_out and error is None and cleanup["forcedCleanup"] is None
        and not cleanup["errors"] and not cleanup["remainingOwnProcesses"]
        and not any(thread.is_alive() for thread in threads) and set(results) == {"stdout", "stderr"}
    )
    stdout_result = results.get("stdout", {"data": b"", "total": 0, "truncated": False})
    stderr_result = results.get("stderr", {"data": b"", "total": 0, "truncated": False})
    status, rows = validate_output(
        stdout_result["data"], run, process.returncode if process is not None else None, complete,
        stdout_result.get("truncated", False) or stderr_result.get("truncated", False),
    )
    if stderr_result["data"] or cleanup["remainingOwnProcesses"] or cleanup["errors"]:
        status = "UNKNOWN"
    evidence_errors: list[str] = []
    for name, data in (("stdout.txt", stdout_result["data"]), ("stderr.txt", stderr_result["data"])):
        failure = _write_evidence(out, name, data)
        if failure:
            evidence_errors.append(failure)
            status = "UNKNOWN"
    summary = {
        "schema": 1, "runId": run, "status": status, "output": str(out), "events": rows,
        "nativeExitCode": process.returncode if process is not None else None, "hostAdbClientPid": process.pid if process is not None else None,
        "timedOut": timed_out, "error": error, "forcedCleanup": cleanup["forcedCleanup"], "cleanupErrors": cleanup["errors"],
        "evidenceErrors": evidence_errors, "remainingOwnProcesses": cleanup["remainingOwnProcesses"], "completePipes": complete,
        "stdoutBytes": stdout_result.get("total", 0), "stderrBytes": stderr_result.get("total", 0),
        "stdoutTruncated": stdout_result.get("truncated", False), "stderrTruncated": stderr_result.get("truncated", False),
        "eglContextVerified": False, "virpipeVerified": False, "hardwareRenderingVerified": False, "triangleVerified": False,
        "presentationVerified": False, "d3dVerified": False, "wowVerified": False,
        "boundary": "private guest GLVND/Mesa loader, exports, client query and maps only; no EGL context, server, backend or Wine",
    }
    failure = _write_evidence(out, "summary.json", (json.dumps(summary, indent=2) + "\n").encode())
    if failure:
        summary["status"] = "UNKNOWN"
        summary["evidenceErrors"].append(failure)
        status = "UNKNOWN"
    print(json.dumps(summary, indent=2))
    return 0 if status == "GUEST_LOADER_PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
