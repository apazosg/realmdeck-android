#!/usr/bin/env python3
"""Offline, read-only static audit for pinned Termux virgl payloads.

This tool deliberately does not have a downloader, package installer, ELF
loader, or device interface.  It reads a Debian ``.deb`` as an ar container,
streams its ``data.tar.xz`` member, and inspects ELF metadata in memory.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import lzma
import posixpath
import struct
import tarfile
import unicodedata
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any, BinaryIO, Iterable


MAX_PACKAGE_BYTES = 64 * 1024 * 1024
MAX_DATA_COMPRESSED_BYTES = 64 * 1024 * 1024
MAX_DATA_EXPANDED_BYTES = 256 * 1024 * 1024
MAX_TAR_MEMBERS = 4096
MAX_TAR_FILE_BYTES = 64 * 1024 * 1024
# Phase-B candidate bounds are explicit and intentionally separate from the
# smaller default audit bounds.  libLLVM is large, but no unbounded extraction
# is permitted.
MAX_CANDIDATE_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_CANDIDATE_FILE_BYTES = 192 * 1024 * 1024
MAX_AR_MEMBERS = 32
ALLOWED_ANDROID_SYSTEM_LIBS = frozenset(
    {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libEGL.so", "libGLESv2.so"}
)

# These are the two already downloaded candidate payloads.  A path is only
# accepted by the command line after its basename, byte count, and digest have
# all matched this table.  Tests can supply their own pins to the Python API.
PINNED_PACKAGES: dict[str, dict[str, Any]] = {
    "mesa_26.2.4_aarch64.deb": {
        "bytes": 3_146_408,
        "sha256": "d072b0144a9a6f4f80bd08797893aa37d4b75209aae810f2e84843a8fa23342e",
    },
    "libglvnd_1.7.0-1_aarch64.deb": {
        "bytes": 178_340,
        "sha256": "67f3fd247e4279a7fceddbd2929f8b53e87c255edfc9c07d0dee7df5fe243d2c",
    },
}


class AuditError(ValueError):
    """A malformed, unsafe, unpinned, or out-of-bounds input."""


@dataclass(frozen=True)
class PackagePin:
    bytes: int
    sha256: str


@dataclass
class ArchiveEntry:
    path: str
    kind: str
    size: int = 0
    target: str | None = None
    elf: dict[str, Any] | None = None


class CountingReader:
    """Bound an expanded xz stream while keeping tarfile in streaming mode."""

    def __init__(self, stream: BinaryIO, limit: int) -> None:
        self.stream = stream
        self.limit = limit
        self.total = 0

    def read(self, size: int = -1) -> bytes:
        data = self.stream.read(size)
        self.total += len(data)
        if self.total > self.limit:
            raise AuditError(f"expanded data exceeds bound ({self.limit} bytes)")
        return data

    def close(self) -> None:
        self.stream.close()


def _bounded_read(path: Path, limit: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise AuditError(f"package is not a regular file: {path}")
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise AuditError(f"cannot stat package: {path}") from exc
    if size > limit:
        raise AuditError(f"package exceeds bound ({size} > {limit})")
    return path.read_bytes()


def validate_pin(path: Path, pin: PackagePin) -> bytes:
    """Read and hash a pinned package before parsing any archive bytes."""
    raw = _bounded_read(path, MAX_PACKAGE_BYTES)
    digest = hashlib.sha256(raw).hexdigest()
    if len(raw) != pin.bytes:
        raise AuditError(f"pin size mismatch for {path.name}: {len(raw)} != {pin.bytes}")
    if digest != pin.sha256:
        raise AuditError(f"pin SHA-256 mismatch for {path.name}: {digest} != {pin.sha256}")
    return raw


def parse_ar(raw: bytes) -> dict[str, bytes]:
    """Parse a bounded ar container, rejecting malformed/duplicate members."""
    if raw[:8] != b"!<arch>\n":
        raise AuditError("not an ar archive")
    members: dict[str, bytes] = {}
    offset = 8
    count = 0
    while offset < len(raw):
        count += 1
        if count > MAX_AR_MEMBERS or offset + 60 > len(raw):
            raise AuditError("invalid or overlarge ar member table")
        header = raw[offset : offset + 60]
        if header[58:60] != b"`\n":
            raise AuditError("invalid ar member trailer")
        try:
            name = header[:16].decode("ascii").strip().rstrip("/")
            size = int(header[48:58].decode("ascii").strip())
        except (UnicodeDecodeError, ValueError) as exc:
            raise AuditError("invalid ar member header") from exc
        if not name or size < 0:
            raise AuditError("invalid ar member name or size")
        start = offset + 60
        end = start + size
        if end > len(raw) or name in members:
            raise AuditError("truncated or duplicate ar member")
        members[name] = raw[start:end]
        offset = end + (size & 1)
    if offset != len(raw):
        raise AuditError("invalid ar alignment")
    return members


def _validate_utf_path(value: str, *, field: str) -> str:
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as exc:
        raise AuditError(f"{field} is not valid UTF-8") from exc
    if "\x00" in value or "\\" in value or unicodedata.normalize("NFC", value) != value:
        raise AuditError(f"unsafe or non-normalized UTF-8 {field}: {value!r}")
    if value.startswith("/") or value == "":
        raise AuditError(f"unsafe archive path: {value!r}")
    parts = value.split("/")
    while parts and parts[0] == ".":
        parts.pop(0)
    if any(part in {"", ".", ".."} for part in parts):
        raise AuditError(f"unsafe archive path: {value!r}")
    return "/".join(parts) if parts else "."


def _safe_link_target(path: str, target: str) -> str:
    try:
        target.encode("utf-8", "strict")
    except UnicodeEncodeError as exc:
        raise AuditError("symlink target is not valid UTF-8") from exc
    if "\x00" in target or "\\" in target or unicodedata.normalize("NFC", target) != target:
        raise AuditError(f"unsafe symlink target: {target!r}")
    if target.startswith("/") or target == "" or any(part == "" for part in target.split("/")):
        raise AuditError(f"absolute symlink target: {path} -> {target}")
    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
    if resolved == "." or resolved.startswith("../") or "/../" in resolved:
        raise AuditError(f"escaping symlink target: {path} -> {target}")
    return resolved


def _resolve_archive_links(
    entries: dict[str, ArchiveEntry], *, ignore_broken_nonlibrary: bool = False
) -> list[dict[str, str]]:
    """Ensure every symlink is internal and resolves to an archive entry."""
    ignored: list[dict[str, str]] = []
    def resolve(path: str, seen: set[str]) -> str:
        if path in seen:
            raise AuditError(f"symlink cycle at {path}")
        entry = entries.get(path)
        if entry is None:
            raise AuditError(f"broken symlink target: {path}")
        if entry.kind != "symlink":
            return path
        assert entry.target is not None
        return resolve(_safe_link_target(path, entry.target), seen | {path})

    for entry in entries.values():
        if entry.kind == "symlink":
            try:
                resolve(entry.path, set())
            except AuditError as exc:
                is_library_path = entry.path.startswith("data/data/com.termux/files/usr/lib/")
                if not (ignore_broken_nonlibrary and not is_library_path and "broken symlink target" in str(exc)):
                    raise
                ignored.append({"path": entry.path, "target": entry.target or "", "reason": "broken_unneeded_nonlibrary_symlink"})
    return ignored


def _vaddr_to_offset(loads: list[tuple[int, int, int, int]], address: int, length: int, file_size: int) -> int:
    for vaddr, memsz, offset, filesz in loads:
        if vaddr <= address and address - vaddr <= memsz and length <= filesz - (address - vaddr):
            result = offset + (address - vaddr)
            if result < 0 or result + length > file_size:
                break
            return result
    raise AuditError(f"ELF virtual address is not file-backed: 0x{address:x}")


def parse_elf(raw: bytes) -> dict[str, Any]:
    """Parse only ELF headers/program metadata; never map or execute an ELF."""
    if len(raw) < 64 or raw[:4] != b"\x7fELF":
        raise AuditError("not an ELF64 file")
    if raw[4] != 2 or raw[5] != 1 or raw[6] != 1:
        raise AuditError("ELF must be 64-bit little-endian")
    e_type, machine = struct.unpack_from("<HH", raw, 16)
    if e_type != 3:
        raise AuditError(f"ELF type is not ET_DYN: {e_type}")
    if machine != 183:
        raise AuditError(f"ELF machine is not AArch64: {machine}")
    phoff, shoff = struct.unpack_from("<QQ", raw, 32)
    phentsize, phnum, shentsize, shnum = struct.unpack_from("<HHHH", raw, 54)
    if phentsize < 56 or phnum > 4096 or phoff + phentsize * phnum > len(raw):
        raise AuditError("invalid ELF program header table")
    if shnum and (shentsize < 64 or shnum > 65535 or shoff + shentsize * shnum > len(raw)):
        raise AuditError("invalid ELF section header table")

    loads: list[tuple[int, int, int, int]] = []
    dynamic: tuple[int, int] | None = None
    for index in range(phnum):
        at = phoff + index * phentsize
        p_type, p_flags = struct.unpack_from("<II", raw, at)
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, _p_align = struct.unpack_from("<QQQQQQ", raw, at + 8)
        if p_offset + p_filesz > len(raw) or p_memsz < p_filesz:
            raise AuditError("invalid ELF program segment")
        if p_type == 1:
            loads.append((p_vaddr, p_memsz, p_offset, p_filesz))
        elif p_type == 2:
            if dynamic is not None:
                raise AuditError("multiple ELF PT_DYNAMIC segments")
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        raise AuditError("ELF has no dynamic section")
    dynamic_offset, dynamic_size = dynamic
    if dynamic_size == 0 or dynamic_size > 16 * 1024 * 1024 or dynamic_offset + dynamic_size > len(raw):
        raise AuditError("invalid ELF dynamic section")

    tags: list[tuple[int, int]] = []
    for at in range(dynamic_offset, dynamic_offset + dynamic_size, 16):
        if at + 16 > len(raw):
            raise AuditError("truncated ELF dynamic section")
        tag, value = struct.unpack_from("<qQ", raw, at)
        tags.append((tag, value))
        if tag == 0:
            break
    else:
        raise AuditError("ELF dynamic section has no DT_NULL")

    values: dict[int, list[int]] = {}
    for tag, value in tags:
        values.setdefault(tag, []).append(value)
    strings_address = values.get(5, [None])[0]
    string_size = values.get(10, [None])[0]
    if strings_address is None or string_size is None or string_size > 64 * 1024 * 1024:
        raise AuditError("ELF dynamic string table is missing or too large")
    strings_offset = _vaddr_to_offset(loads, strings_address, string_size, len(raw))
    strings = raw[strings_offset : strings_offset + string_size]

    def string_at(offset: int) -> str:
        if offset >= len(strings):
            raise AuditError("ELF dynamic string offset is out of range")
        end = strings.find(b"\0", offset)
        if end < 0:
            raise AuditError("unterminated ELF dynamic string")
        try:
            return strings[offset:end].decode("utf-8", "strict")
        except UnicodeDecodeError as exc:
            raise AuditError("ELF dynamic string is not UTF-8") from exc

    needed = [string_at(value) for value in values.get(1, [])]
    soname = string_at(values[14][0]) if 14 in values else None
    runpath = [string_at(value) for value in values.get(29, [])]
    rpath = [string_at(value) for value in values.get(15, [])]
    hash_descriptors: list[dict[str, Any]] = []
    for tag, name in ((4, "sysv"), (0x6FFFFEF5, "gnu")):
        for value in values.get(tag, []):
            offset = _vaddr_to_offset(loads, value, 4, len(raw))
            hash_descriptors.append({"kind": name, "virtual_address": value, "file_offset": offset})
    return {
        "bytes": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "elfClass": 64,
        "elfData": "LE",
        "elfMachine": 183,
        "elfType": "ET_DYN",
        "needed": needed,
        "soname": soname,
        "runpath": runpath,
        "rpath": rpath,
        "hashes": hash_descriptors,
    }


def _read_tar_member(
    archive: tarfile.TarFile, member: tarfile.TarInfo, max_file_bytes: int
) -> bytes:
    if member.size < 0 or member.size > max_file_bytes:
        raise AuditError(f"tar member exceeds bound: {member.name}")
    stream = archive.extractfile(member)
    if stream is None:
        raise AuditError(f"cannot read regular tar member: {member.name}")
    value = stream.read(member.size + 1)
    if len(value) != member.size:
        raise AuditError(f"truncated tar member: {member.name}")
    return value


def parse_data_tar(
    data: bytes,
    package_name: str,
    *,
    max_expanded_bytes: int | None = None,
    max_file_bytes: int | None = None,
    ignore_unneeded_et_exec: bool = False,
    ignore_broken_nonlibrary_symlinks: bool = False,
) -> dict[str, Any]:
    max_expanded_bytes = MAX_DATA_EXPANDED_BYTES if max_expanded_bytes is None else max_expanded_bytes
    max_file_bytes = MAX_TAR_FILE_BYTES if max_file_bytes is None else max_file_bytes
    if len(data) > MAX_DATA_COMPRESSED_BYTES:
        raise AuditError(f"data.tar.xz exceeds compressed bound: {package_name}")
    entries: dict[str, ArchiveEntry] = {}
    with lzma.LZMAFile(io.BytesIO(data)) as compressed:
        counted = CountingReader(compressed, max_expanded_bytes)
        ignored_elfs: list[dict[str, Any]] = []
        try:
            with tarfile.open(fileobj=counted, mode="r|") as archive:
                for index, member in enumerate(archive):
                    if index >= MAX_TAR_MEMBERS:
                        raise AuditError("tar member count exceeds bound")
                    path = _validate_utf_path(member.name, field="tar path")
                    if path == "." and member.isdir():
                        continue
                    if path in entries:
                        raise AuditError(f"duplicate tar path: {path}")
                    if member.isdir():
                        entries[path] = ArchiveEntry(path, "directory")
                    elif member.issym():
                        target = member.linkname
                        _safe_link_target(path, target)
                        entries[path] = ArchiveEntry(path, "symlink", target=target)
                    elif member.isfile():
                        raw = _read_tar_member(archive, member, max_file_bytes)
                        elf = None
                        if raw.startswith(b"\x7fELF"):
                            try:
                                elf = parse_elf(raw)
                            except AuditError:
                                # Candidate packages may carry an unneeded
                                # ET_EXEC helper.  It is recorded, never
                                # materialized; malformed/foreign ET_DYN
                                # libraries still fail the audit.
                                is_et_exec = len(raw) >= 20 and struct.unpack_from("<H", raw, 16)[0] == 2
                                if not (ignore_unneeded_et_exec and is_et_exec):
                                    raise
                                ignored_elfs.append(
                                    {
                                        "packagePath": path,
                                        "bytes": len(raw),
                                        "sha256": hashlib.sha256(raw).hexdigest(),
                                        "reason": "unneeded_ET_EXEC",
                                    }
                                )
                        entries[path] = ArchiveEntry(path, "file", member.size, elf=elf)
                    elif member.islnk() or member.isdev() or member.isfifo() or member.isreg() is False:
                        raise AuditError(f"unsupported/special tar member: {path}")
                # Force xz EOF so trailing expanded data is included in the bound.
                while counted.read(1024 * 1024):
                    pass
        finally:
            counted.close()
    ignored_symlinks = _resolve_archive_links(
        entries, ignore_broken_nonlibrary=ignore_broken_nonlibrary_symlinks
    )

    libraries: list[dict[str, Any]] = []
    symlinks: list[dict[str, Any]] = []
    for entry in entries.values():
        if entry.elf is not None:
            item = dict(entry.elf)
            item["packagePath"] = entry.path
            libraries.append(item)
        elif entry.kind == "symlink":
            assert entry.target is not None
            symlinks.append({"path": entry.path, "target": entry.target, "resolvedPath": _safe_link_target(entry.path, entry.target)})
    return {
        "package": package_name,
        "libraries": sorted(libraries, key=lambda item: item["packagePath"]),
        "symlinks": sorted(symlinks, key=lambda item: item["path"]),
        "memberCount": len(entries),
        "expandedBytes": counted.total,
        "ignoredElfs": ignored_elfs,
        "ignoredSymlinks": ignored_symlinks,
    }


def audit_package(
    path: Path,
    pin: PackagePin,
    *,
    max_expanded_bytes: int | None = None,
    max_file_bytes: int | None = None,
    ignore_unneeded_et_exec: bool = False,
    ignore_broken_nonlibrary_symlinks: bool = False,
) -> dict[str, Any]:
    raw = validate_pin(path, pin)
    members = parse_ar(raw)
    data_names = [name for name in members if name == "data.tar.xz"]
    if len(data_names) != 1:
        raise AuditError("package must contain exactly one data.tar.xz member")
    data = members["data.tar.xz"]
    component = parse_data_tar(
        data,
        path.name,
        max_expanded_bytes=max_expanded_bytes,
        max_file_bytes=max_file_bytes,
        ignore_unneeded_et_exec=ignore_unneeded_et_exec,
        ignore_broken_nonlibrary_symlinks=ignore_broken_nonlibrary_symlinks,
    )
    component.update({"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
    return component


def _owned_libraries(components: Iterable[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    owned: dict[str, dict[str, Any]] = {}
    for component in components:
        for library in component["libraries"]:
            provider = {**library, "package": component["package"]}
            names = {library.get("soname"), PurePosixPath(library["packagePath"]).name}
            for name in (item for item in names if item):
                if name in owned:
                    previous = owned[name]
                    if previous["packagePath"] != provider["packagePath"] or previous["package"] != provider["package"]:
                        raise AuditError(f"conflicting owned library name: {name}")
                    continue
                owned[name] = provider
    return owned


def _validate_requested_roots(roots: Iterable[str]) -> list[str]:
    requested = list(roots)
    if not requested:
        raise AuditError("at least one closure root is required")
    if len(set(requested)) != len(requested):
        raise AuditError("closure roots must be unique")
    for name in requested:
        if (
            not isinstance(name, str)
            or not name
            or "/" in name
            or "\\" in name
            or "\x00" in name
            or name in {".", ".."}
            or unicodedata.normalize("NFC", name) != name
        ):
            raise AuditError(f"unsafe closure root name: {name!r}")
    return requested


def build_closure(
    components: list[dict[str, Any]], *, roots: Iterable[str] | None = None
) -> dict[str, Any]:
    owned = _owned_libraries(components)
    if roots is None:
        gallium_roots = sorted(name for name in owned if name.startswith("libgallium-") and name.endswith(".so"))
        requested_roots = ["libGL.so.1", "libGLX_mesa.so", *gallium_roots]
    else:
        requested_roots = _validate_requested_roots(roots)
    queue: deque[tuple[str, str | None, str]] = deque((name, None, name) for name in requested_roots)
    visited: set[str] = set()
    edges: list[dict[str, Any]] = []
    missing: list[dict[str, Any]] = []
    resolved: list[dict[str, Any]] = []
    while queue:
        name, parent, root = queue.popleft()
        if name in visited:
            continue
        visited.add(name)
        provider = owned.get(name)
        if provider is None:
            if name in ALLOWED_ANDROID_SYSTEM_LIBS:
                resolved.append({"needed": name, "provider": "allowlisted_android_system"})
            else:
                missing.append({"needed": name, "from": parent, "root": root, "reason": "not_owned_or_allowlisted"})
            continue
        resolved.append({"needed": name, "provider": "owned", "package": provider["package"], "packagePath": provider["packagePath"]})
        for needed in provider["needed"]:
            edges.append({"from": name, "needed": needed, "root": root})
            queue.append((needed, name, root))
    unique_missing = sorted({item["needed"] for item in missing})
    dynamic_paths = {
        "runpath": sorted({path for lib in owned.values() for path in lib.get("runpath", [])}),
        "rpath": sorted({path for lib in owned.values() for path in lib.get("rpath", [])}),
        "status": "UNVERIFIED",
        "note": "No linker was run; paths are reported metadata, not proof of app-prefix usability.",
    }
    return {
        "entrypoint": "libGL.so.1" if roots is None else requested_roots[0],
        "roots": requested_roots,
        "ownedSonames": sorted(owned),
        "allowlistedAndroidSystemSonames": sorted(ALLOWED_ANDROID_SYSTEM_LIBS),
        "edges": edges,
        "resolved": resolved,
        "missing": missing,
        "missingSonames": unique_missing,
        "linkerPath": dynamic_paths,
        "protocol": {"status": "UNVERIFIED", "note": "ELF strings and names do not prove virgl/vtest protocol compatibility."},
        "ready": False,
        "readiness": "UNVERIFIED",
        "readinessReasons": ["linker_path_unverified", "protocol_unverified"],
    }


def attach_metadata(report: dict[str, Any], metadata_path: Path | None) -> None:
    if metadata_path is None:
        return
    raw = _bounded_read(metadata_path, 16 * 1024 * 1024)
    try:
        metadata = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise AuditError(f"invalid metadata JSON: {metadata_path}") from exc
    if not isinstance(metadata, dict) or not isinstance(metadata.get("packages"), dict):
        raise AuditError("metadata must contain a packages object")
    candidates = []
    audited = {component["sha256"] for component in report["components"]}
    for package_name, item in sorted(metadata["packages"].items()):
        if not isinstance(item, dict):
            raise AuditError(f"invalid metadata package stanza: {package_name}")
        candidates.append(
            {
                "package": item.get("Package", package_name),
                "version": item.get("Version"),
                "architecture": item.get("Architecture"),
                "filename": item.get("Filename"),
                "sha256": item.get("SHA256"),
                "bytes": item.get("Size"),
                "installedSize": item.get("Installed-Size"),
                "depends": item.get("Depends"),
                "auditedPayload": item.get("SHA256") in audited,
            }
        )
    report["metadata"] = {
        "source": str(metadata_path),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "schema": metadata.get("schema"),
        "sourceRecipeCommit": metadata.get("sourceRecipeCommit"),
        "repository": metadata.get("repository"),
        "selection": metadata.get("selection"),
        "compressedPackageBytes": metadata.get("compressedPackageBytes"),
        "installedPackageBytes": metadata.get("installedPackageBytes"),
        "candidateProviderPackages": candidates,
        "providerResolution": "not inferred from package names; ELF SONAME ownership comes only from audited payloads",
    }


def _safe_exclusive_output(path: Path) -> Any:
    repo = Path(__file__).resolve().parents[4]
    if not path.is_absolute():
        raise AuditError("output must be an absolute external path")
    candidate = path.resolve(strict=False)
    try:
        candidate.relative_to(repo)
    except ValueError:
        pass
    else:
        raise AuditError("output must be outside the repository")
    cursor = Path(path.anchor)
    for part in path.parts[1:-1]:
        cursor /= part
        if cursor.is_symlink():
            raise AuditError("output path contains a symlink component")
    if not path.parent.is_dir() or path.parent.is_symlink():
        raise AuditError("output parent must be an existing non-symlink directory")
    if path.exists() or path.is_symlink():
        raise AuditError("refusing to overwrite output")
    return path.open("x", encoding="utf-8")


def audit(paths: list[Path], pins: dict[str, PackagePin], metadata: Path | None = None) -> dict[str, Any]:
    if not paths:
        raise AuditError("at least one package is required")
    components: list[dict[str, Any]] = []
    for path in paths:
        pin = pins.get(path.name)
        if pin is None:
            raise AuditError(f"no exact pin for package basename: {path.name}")
        components.append(audit_package(path, pin))
    report: dict[str, Any] = {
        "schema": "wowforever.virgl-payload-static-audit",
        "schemaVersion": 1,
        "offline": True,
        "executionPerformed": False,
        "components": components,
        "closure": build_closure(components),
        "verificationBoundary": "Pinned archive, safe tar paths, ELF64LE AArch64 ET_DYN metadata, exact owned/allowlisted SONAME closure only; linker and protocol remain UNVERIFIED.",
    }
    attach_metadata(report, metadata)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, action="append", required=True, help="existing pinned .deb; repeat for each payload")
    parser.add_argument("--metadata", type=Path, help="optional existing package metadata JSON; never fetched")
    parser.add_argument("--output", type=Path, required=True, help="absolute, external, non-existing JSON path")
    args = parser.parse_args(argv)
    pins = {name: PackagePin(item["bytes"], item["sha256"]) for name, item in PINNED_PACKAGES.items()}
    report = audit(args.package, pins, args.metadata)
    with _safe_exclusive_output(args.output) as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")
    print(json.dumps({"missingSonames": report["closure"]["missingSonames"], "roots": report["closure"]["roots"], "ready": report["closure"]["ready"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
