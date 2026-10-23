#!/usr/bin/env python3
"""Bounded host-only audit/acquisition of the standard Termux virgl vtest server.

The two package objects in this module are a closed, published candidate.  The
command may fetch those objects to a new directory outside the repository, but
it never installs, loads, or executes an ELF.  Optional candidate materialization
copies only selected, hash-verified regular files to a newly-created external
directory.  Archive contents are parsed in memory and the existing payload ELF
parser is reused for the common ELF invariants.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import lzma
import os
import re
import struct
import subprocess
import tarfile
import tempfile
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO, Iterable

import audit_payloads as payloads


ORIGIN = "https://packages-cf.termux.dev/apt/termux-main/"
RECIPE_COMMIT = "27da60be397a6e9eb898bba8d03bcd648782ccd5"
RECIPE_PATH = "packages/virglrenderer/build.sh"
RECIPE_VERSION = "1.3.0"
RECIPE_TAR_SHA256 = "56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce"
PLATFORM_OPTIONS = ["-Dplatforms=egl,glx"]

MAX_PACKAGE_BYTES = 1024 * 1024
MAX_AGGREGATE_DOWNLOAD_BYTES = 1024 * 1024
MAX_DECODED_PACKAGE_BYTES = 16 * 1024 * 1024
MAX_TAR_MEMBERS = 1024
MAX_TAR_FILE_BYTES = 8 * 1024 * 1024
DOWNLOAD_TIMEOUT = 40
MAX_METADATA_BYTES = 16 * 1024 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
SHA256_RE = re.compile(r"\A[0-9a-f]{64}\Z")
EXPECTED_SERVER = "virgl_test_server"
EXPECTED_VIRGL_SONAME = "libvirglrenderer.so"
EXPECTED_EPOXY_SONAME_PREFIX = "libepoxy.so"
DLOPEN_NAMES = ("libEGL.so.1", "libGLESv2.so.2")
SYSTEM_EGL_ALIASES = ("libEGL.so", "libGLESv2.so")

DEFAULT_ARTIFACT_DIR = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/vtest-server-audit")
DEFAULT_MANIFEST = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/private_candidate/candidate-manifest.json"
)


class ServerAuditError(ValueError):
    """A fixed-source, network, archive, or static-audit failure."""


@dataclass(frozen=True)
class ServerPackage:
    package: str
    version: str
    architecture: str
    filename: str
    bytes: int
    sha256: str

    @property
    def url(self) -> str:
        return ORIGIN + self.filename

    @property
    def basename(self) -> str:
        return PurePosixPath(self.filename).name


DEFAULT_PACKAGE_DIR = DEFAULT_ARTIFACT_DIR
DEFAULT_MESA_PACKAGE = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/mesa_26.2.4_aarch64.deb"
)
DEFAULT_SERVER_CANDIDATE = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/server_candidate"
)
DEFAULT_SERVER_REPORT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/server-v6-report.json"
)
MESA_PACKAGE = ServerPackage(
    "mesa",
    "26.2.4",
    "aarch64",
    "pool/main/m/mesa/mesa_26.2.4_aarch64.deb",
    3_146_408,
    "d072b0144a9a6f4f80bd08797893aa37d4b75209aae810f2e84843a8fa23342e",
)
MESA_GBM_PATH = "data/data/com.termux/files/usr/lib/libgbm.so"
MAX_MESA_EXPANDED_BYTES = payloads.MAX_CANDIDATE_EXPANDED_BYTES
MAX_MESA_FILE_BYTES = payloads.MAX_CANDIDATE_FILE_BYTES
MAX_MATERIALIZED_FILE_BYTES = 192 * 1024 * 1024
MAX_MATERIALIZED_AGGREGATE_BYTES = 256 * 1024 * 1024
SERVER_INTERPRETER = "/system/bin/linker64"
DEVICE_ALIASES = (
    {"name": "libEGL.so.1", "target": "/system/lib64/libEGL.so"},
    {"name": "libGLESv2.so.2", "target": "/system/lib64/libGLESv2.so"},
)


@dataclass(frozen=True)
class MaterializationItem:
    destination: str
    data: bytes
    source: dict[str, Any]
    mode: int

    @property
    def bytes(self) -> int:
        return len(self.data)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


PACKAGES = (
    ServerPackage(
        "virglrenderer",
        "1.3.0",
        "aarch64",
        "pool/main/v/virglrenderer/virglrenderer_1.3.0_aarch64.deb",
        408648,
        "dbecfdddba75293ad1ea8b6f8105c1898a196cdddace18ece650205a85ba3efc",
    ),
    ServerPackage(
        "libepoxy",
        "1.5.10-3",
        "aarch64",
        "pool/main/libe/libepoxy/libepoxy_1.5.10-3_aarch64.deb",
        300476,
        "89cec877ca34febc20dd62eaa07e95871c3c005e006fcc9fc155e09b4f61449b",
    ),
)


def _validate_fixed_source() -> None:
    if not ORIGIN.endswith("/") or urllib.parse.urlsplit(ORIGIN).scheme != "https":
        raise ServerAuditError("fixed origin is not HTTPS")
    total = 0
    names: set[str] = set()
    for record in PACKAGES:
        if record.package in names or record.basename in names:
            raise ServerAuditError("duplicate fixed package identity")
        names.update((record.package, record.basename))
        if record.bytes <= 0 or record.bytes > MAX_PACKAGE_BYTES:
            raise ServerAuditError(f"fixed package exceeds per-download bound: {record.package}")
        if not SHA256_RE.fullmatch(record.sha256):
            raise ServerAuditError(f"fixed package has invalid SHA-256: {record.package}")
        parsed = urllib.parse.urlsplit(record.url)
        if (
            parsed.scheme != "https"
            or parsed.netloc != "packages-cf.termux.dev"
            or parsed.query
            or parsed.fragment
            or not record.filename.startswith("pool/")
            or "\\" in record.filename
            or any(part in {"", ".", ".."} for part in record.filename.split("/"))
        ):
            raise ServerAuditError(f"unsafe fixed package URL: {record.url}")
        total += record.bytes
    if total > MAX_AGGREGATE_DOWNLOAD_BYTES:
        raise ServerAuditError("fixed package aggregate exceeds 1 MiB")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request: urllib.request.Request, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        raise ServerAuditError(f"redirect refused for fixed URL: {newurl}")


def _read_regular(path: Path, limit: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ServerAuditError(f"not a regular non-symlink file: {path}")
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise ServerAuditError(f"cannot stat file: {path}") from exc
    if size > limit:
        raise ServerAuditError(f"file exceeds bound: {path}")
    return path.read_bytes()


def _verify_published_metadata(path: Path) -> dict[str, Any]:
    raw = _read_regular(path, MAX_METADATA_BYTES)
    try:
        metadata = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ServerAuditError(f"invalid published package metadata: {path}") from exc
    if not isinstance(metadata, dict) or metadata.get("repository") != ORIGIN:
        raise ServerAuditError("published metadata origin is not the fixed official origin")
    packages = metadata.get("packages")
    if not isinstance(packages, dict):
        raise ServerAuditError("published metadata has no packages object")
    for record in PACKAGES:
        item = packages.get(record.package)
        if not isinstance(item, dict):
            raise ServerAuditError(f"published metadata omits {record.package}")
        expected = {
            "Package": record.package,
            "Version": record.version,
            "Architecture": record.architecture,
            "Filename": record.filename,
            "Size": record.bytes,
            "SHA256": record.sha256,
        }
        for key, value in expected.items():
            actual = item.get(key)
            if key == "Size":
                try:
                    actual = int(actual)
                except (TypeError, ValueError) as exc:
                    raise ServerAuditError(f"published metadata size is invalid: {record.package}") from exc
            if actual != value:
                raise ServerAuditError(f"published metadata pin mismatch for {record.package}: {key}")
        if not isinstance(item["SHA256"], str) or not SHA256_RE.fullmatch(item["SHA256"].lower()):
            raise ServerAuditError(f"published metadata SHA-256 is invalid: {record.package}")
    return {
        "path": str(path),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "repository": ORIGIN,
        "packagesVerified": [record.package for record in PACKAGES],
    }


def _curl_probe(url: str, expected: int) -> None:
    command = [
        "curl",
        "--fail",
        "--silent",
        "--show-error",
        "--head",
        "--max-time",
        str(DOWNLOAD_TIMEOUT),
        "--max-redirs",
        "0",
        "--proto",
        "=https",
        "--proto-redir",
        "=https",
        "--dump-header",
        "-",
        "--write-out",
        "\n%{http_code}\n%{url_effective}\n",
        url,
    ]
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=DOWNLOAD_TIMEOUT + 5, check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        raise ServerAuditError(f"URL preflight failed: {url}: {exc}") from exc
    if result.returncode != 0:
        raise ServerAuditError(f"URL preflight was not ordinary 200: {url}")
    text = result.stdout.decode("ascii", "replace")
    lines = text.splitlines()
    if len(lines) < 3 or lines[-2] != "200" or lines[-1] != url:
        raise ServerAuditError(f"URL preflight returned a redirect or non-200: {url}")
    lengths = re.findall(r"(?im)^content-length:\s*(\d+)\s*$", text)
    if lengths and int(lengths[-1]) != expected:
        raise ServerAuditError(f"URL preflight size mismatch: {url}")


def _probe_url(record: ServerPackage, opener: urllib.request.OpenerDirector) -> None:
    request = urllib.request.Request(record.url, method="HEAD", headers={"Accept": "application/octet-stream"})
    try:
        with opener.open(request, timeout=DOWNLOAD_TIMEOUT) as response:
            status = getattr(response, "status", None)
            if status is None:
                status = response.getcode()
            if status != 200 or response.geturl() != record.url:
                raise ServerAuditError(f"fixed package URL did not return ordinary 200: {record.url}")
            length = response.headers.get("Content-Length")
            if length is None or int(length) != record.bytes:
                raise ServerAuditError(f"fixed package URL size was not the published size: {record.package}")
    except urllib.error.HTTPError as exc:
        if exc.code != 403:
            raise ServerAuditError(f"fixed package URL returned HTTP {exc.code}: {record.url}") from exc
        _curl_probe(record.url, record.bytes)
    except (urllib.error.URLError, OSError, ValueError) as exc:
        raise ServerAuditError(f"fixed package URL preflight failed: {record.url}") from exc


def _curl_download(record: ServerPackage, target: Path) -> int:
    fd, temporary_name = tempfile.mkstemp(prefix=".vtest-download-", dir=target.parent)
    os.close(fd)
    temporary = Path(temporary_name)
    try:
        command = [
            "curl",
            "--fail",
            "--silent",
            "--show-error",
            "--max-time",
            str(DOWNLOAD_TIMEOUT),
            "--max-redirs",
            "0",
            "--proto",
            "=https",
            "--proto-redir",
            "=https",
            "--max-filesize",
            str(record.bytes + 1),
            "--output",
            str(temporary),
            "--write-out",
            "%{http_code}",
            record.url,
        ]
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=DOWNLOAD_TIMEOUT + 5, check=False)
        except (OSError, subprocess.SubprocessError) as exc:
            raise ServerAuditError(f"curl download failed: {record.package}") from exc
        if result.returncode != 0 or result.stdout.decode("ascii", "replace").strip() != "200":
            raise ServerAuditError(f"curl did not return ordinary 200: {record.url}")
        raw = _read_regular(temporary, record.bytes)
        if len(raw) != record.bytes or hashlib.sha256(raw).hexdigest() != record.sha256:
            raise ServerAuditError(f"download pin mismatch: {record.package}")
        if target.exists() or target.is_symlink():
            raise ServerAuditError(f"refusing to overwrite package: {target}")
        os.link(temporary, target)
        return len(raw)
    finally:
        temporary.unlink(missing_ok=True)


def _download_one(record: ServerPackage, target: Path, opener: urllib.request.OpenerDirector) -> int:
    if target.exists() or target.is_symlink():
        raise ServerAuditError(f"refusing to overwrite package: {target}")
    request = urllib.request.Request(record.url, headers={"Accept": "application/octet-stream"})
    try:
        with opener.open(request, timeout=DOWNLOAD_TIMEOUT) as response:
            status = getattr(response, "status", None)
            if status is None:
                status = response.getcode()
            if status != 200 or response.geturl() != record.url:
                raise ServerAuditError(f"fixed package URL did not return ordinary 200: {record.url}")
            length = response.headers.get("Content-Length")
            if length is not None and int(length) > record.bytes:
                raise ServerAuditError(f"response exceeds published size: {record.package}")
            digest = hashlib.sha256()
            total = 0
            with target.open("xb") as output:
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > record.bytes:
                        raise ServerAuditError(f"response exceeds published size: {record.package}")
                    output.write(chunk)
                    digest.update(chunk)
            if total != record.bytes or digest.hexdigest() != record.sha256:
                raise ServerAuditError(f"download pin mismatch: {record.package}")
            return total
    except urllib.error.HTTPError as exc:
        target.unlink(missing_ok=True)
        if exc.code == 403:
            return _curl_download(record, target)
        raise ServerAuditError(f"fixed package URL returned HTTP {exc.code}: {record.url}") from exc
    except (OSError, urllib.error.URLError, ValueError) as exc:
        target.unlink(missing_ok=True)
        if isinstance(exc, ServerAuditError):
            raise
        raise ServerAuditError(f"download failed: {record.package}") from exc
    except Exception:
        target.unlink(missing_ok=True)
        raise


def _ensure_new_output(path: Path) -> None:
    repo = Path(__file__).resolve().parents[4]
    if not path.is_absolute() or path.resolve(strict=False).is_relative_to(repo):
        raise ServerAuditError("artifact output must be an absolute path outside the repository")
    cursor = Path(path.anchor)
    for part in path.parts[1:]:
        cursor /= part
        if cursor.is_symlink():
            raise ServerAuditError("artifact output contains a symlink component")
    if not path.parent.is_dir() or path.parent.is_symlink():
        raise ServerAuditError("artifact output parent must be an existing non-symlink directory")
    if path.exists() or path.is_symlink():
        raise ServerAuditError("artifact output must be new; refusing overwrite")
    path.mkdir(mode=0o700)


def acquire_packages(output_dir: Path, *, metadata: Path | None = None) -> tuple[list[Path], dict[str, Any], int]:
    """Preflight and fetch both exact package objects into a new external dir."""
    _validate_fixed_source()
    metadata_report = _verify_published_metadata(metadata) if metadata is not None else None
    _ensure_new_output(output_dir)
    opener = urllib.request.build_opener(_NoRedirect)
    paths: list[Path] = []
    downloaded = 0
    try:
        for record in PACKAGES:
            _probe_url(record, opener)
            target = output_dir / record.basename
            downloaded += _download_one(record, target, opener)
            payloads.validate_pin(target, payloads.PackagePin(record.bytes, record.sha256))
            paths.append(target)
    except Exception:
        # A failed acquisition is not a reusable candidate.  Do not remove a
        # directory supplied by a caller after creation; its partial files are
        # visible for diagnosis and are never mistaken for a valid report.
        raise
    return paths, {"metadata": metadata_report, "downloadedBytes": downloaded}, downloaded


def _vaddr_offset(loads: list[tuple[int, int, int, int]], address: int, length: int, size: int) -> int:
    for vaddr, memsz, offset, filesz in loads:
        if vaddr <= address and address - vaddr <= memsz and length <= filesz - (address - vaddr):
            result = offset + address - vaddr
            if result >= 0 and result + length <= size:
                return result
    raise ServerAuditError(f"ELF virtual address is not file-backed: 0x{address:x}")


def _elf_details(raw: bytes, *, exports: bool = True) -> dict[str, Any]:
    try:
        base = payloads.parse_elf(raw)
    except payloads.AuditError as exc:
        raise ServerAuditError(str(exc)) from exc
    if len(raw) < 64:
        raise ServerAuditError("truncated ELF")
    phoff = struct.unpack_from("<Q", raw, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", raw, 54)
    loads: list[tuple[int, int, int, int]] = []
    dynamic: tuple[int, int] | None = None
    interpreter: str | None = None
    for index in range(phnum):
        at = phoff + index * phentsize
        p_type, _flags = struct.unpack_from("<II", raw, at)
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, _align = struct.unpack_from("<QQQQQQ", raw, at + 8)
        if p_type == 1:
            loads.append((p_vaddr, p_memsz, p_offset, p_filesz))
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
        elif p_type == 3:
            if p_filesz == 0 or p_filesz > 4096 or p_offset + p_filesz > len(raw):
                raise ServerAuditError("invalid ELF PT_INTERP")
            value = raw[p_offset : p_offset + p_filesz]
            if not value.endswith(b"\0"):
                raise ServerAuditError("unterminated ELF PT_INTERP")
            try:
                interpreter = value[:-1].decode("utf-8", "strict")
            except UnicodeDecodeError as exc:
                raise ServerAuditError("ELF PT_INTERP is not UTF-8") from exc
    result = dict(base)
    result["ptInterp"] = interpreter
    result["staticExports"] = {"names": [], "count": 0, "truncated": False}
    if not exports or dynamic is None:
        return result

    dynamic_offset, dynamic_size = dynamic
    tags: dict[int, list[int]] = {}
    for at in range(dynamic_offset, dynamic_offset + dynamic_size, 16):
        tag, value = struct.unpack_from("<qQ", raw, at)
        tags.setdefault(tag, []).append(value)
        if tag == 0:
            break
    symtab = tags.get(6, [None])[0]
    syment = tags.get(11, [24])[0]
    string_address = tags.get(5, [None])[0]
    string_size = tags.get(10, [None])[0]
    hash_address = tags.get(4, [None])[0]
    gnu_hash_address = tags.get(0x6FFFFEF5, [None])[0]
    if symtab is None or string_address is None or string_size is None or syment < 24 or syment > 256:
        return result
    count: int | None = None
    if hash_address is not None:
        hash_offset = _vaddr_offset(loads, hash_address, 8, len(raw))
        _bucket_count, count = struct.unpack_from("<II", raw, hash_offset)
    elif gnu_hash_address is not None:
        hash_offset = _vaddr_offset(loads, gnu_hash_address, 16, len(raw))
        bucket_count, symbol_offset, bloom_count, _bloom_shift = struct.unpack_from("<IIII", raw, hash_offset)
        if bucket_count > 1_000_000 or bloom_count > 1_000_000:
            raise ServerAuditError("ELF GNU hash table exceeds bound")
        buckets_offset = hash_offset + 16 + bloom_count * 8
        chain_offset = buckets_offset + bucket_count * 4
        if chain_offset > len(raw):
            raise ServerAuditError("truncated ELF GNU hash table")
        count = symbol_offset
        for index in range(bucket_count):
            bucket_at = buckets_offset + index * 4
            bucket = struct.unpack_from("<I", raw, bucket_at)[0]
            if bucket < symbol_offset:
                continue
            symbol_index = bucket
            while True:
                chain_at = chain_offset + (symbol_index - symbol_offset) * 4
                if chain_at + 4 > len(raw):
                    raise ServerAuditError("truncated ELF GNU hash chain")
                chain_value = struct.unpack_from("<I", raw, chain_at)[0]
                symbol_index += 1
                count = max(count, symbol_index)
                if chain_value & 1:
                    break
                if symbol_index - symbol_offset > 1_000_000:
                    raise ServerAuditError("ELF GNU hash chain exceeds bound")
    if count is None or count > 1_000_000:
        return result
    string_offset = _vaddr_offset(loads, string_address, string_size, len(raw))
    strings = raw[string_offset : string_offset + string_size]
    sym_offset = _vaddr_offset(loads, symtab, syment, len(raw))
    names: list[str] = []
    for index in range(count):
        at = sym_offset + index * syment
        if at + 24 > len(raw):
            raise ServerAuditError("truncated ELF dynamic symbol table")
        name_offset, info, _other, section = struct.unpack_from("<IBBH", raw, at)
        if section == 0 or name_offset == 0 or name_offset >= len(strings):
            continue
        end = strings.find(b"\0", name_offset)
        if end < 0:
            raise ServerAuditError("unterminated ELF dynamic symbol")
        if info >> 4 not in {1, 2}:
            continue
        try:
            name = strings[name_offset:end].decode("utf-8", "strict")
        except UnicodeDecodeError as exc:
            raise ServerAuditError("ELF export name is not UTF-8") from exc
        if name and name not in names:
            names.append(name)
    names.sort()
    # Preserve a useful bounded sample without turning the audit into a symbol
    # dump (libepoxy alone has thousands of dynamic exports).
    result["staticExports"] = {"names": names[:256], "count": len(names), "truncated": len(names) > 256}
    return result


def _read_tar_file(archive: tarfile.TarFile, member: tarfile.TarInfo) -> bytes:
    if member.size < 0 or member.size > MAX_TAR_FILE_BYTES:
        raise ServerAuditError(f"tar member exceeds 8 MiB bound: {member.name}")
    stream = archive.extractfile(member)
    if stream is None:
        raise ServerAuditError(f"cannot read regular tar member: {member.name}")
    value = stream.read(member.size + 1)
    if len(value) != member.size:
        raise ServerAuditError(f"truncated tar member: {member.name}")
    return value


def _parse_package(path: Path, record: ServerPackage) -> dict[str, Any]:
    raw = payloads.validate_pin(path, payloads.PackagePin(record.bytes, record.sha256))
    members = payloads.parse_ar(raw)
    data = members.get("data.tar.xz")
    if data is None or list(name for name in members if name == "data.tar.xz") != ["data.tar.xz"]:
        raise ServerAuditError(f"package has no unique data.tar.xz: {record.package}")
    entries: dict[str, payloads.ArchiveEntry] = {}
    elf_raw: dict[str, bytes] = {}
    expanded = payloads.CountingReader(lzma.LZMAFile(io.BytesIO(data)), MAX_DECODED_PACKAGE_BYTES)
    try:
        with tarfile.open(fileobj=expanded, mode="r|") as archive:
            for index, member in enumerate(archive):
                if index >= MAX_TAR_MEMBERS:
                    raise ServerAuditError("tar member count exceeds 1024")
                path_name = payloads._validate_utf_path(member.name, field="tar path")
                if path_name == "." and member.isdir():
                    continue
                if path_name in entries:
                    raise ServerAuditError(f"duplicate tar path: {path_name}")
                if member.isdir():
                    entries[path_name] = payloads.ArchiveEntry(path_name, "directory")
                elif member.issym():
                    payloads._safe_link_target(path_name, member.linkname)
                    entries[path_name] = payloads.ArchiveEntry(path_name, "symlink", target=member.linkname)
                elif member.isfile():
                    value = _read_tar_file(archive, member)
                    details = None
                    if value.startswith(b"\x7fELF"):
                        details = _elf_details(value)
                        elf_raw[path_name] = value
                    entries[path_name] = payloads.ArchiveEntry(path_name, "file", member.size, elf=details)
                else:
                    raise ServerAuditError(f"unsupported/special tar member: {path_name}")
            while expanded.read(1024 * 1024):
                pass
    except payloads.AuditError as exc:
        raise ServerAuditError(str(exc)) from exc
    except (lzma.LZMAError, tarfile.TarError) as exc:
        raise ServerAuditError(f"invalid data.tar.xz: {record.package}") from exc
    finally:
        expanded.close()
    try:
        ignored_links = payloads._resolve_archive_links(entries, ignore_broken_nonlibrary=True)
    except payloads.AuditError as exc:
        raise ServerAuditError(str(exc)) from exc
    libraries = []
    for path_name, entry in entries.items():
        if entry.elf is not None:
            libraries.append({**entry.elf, "packagePath": path_name})
    return {
        "package": record.package,
        "version": record.version,
        "filename": record.filename,
        "bytes": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "memberCount": len(entries),
        "expandedBytes": expanded.total,
        "libraries": sorted(libraries, key=lambda item: item["packagePath"]),
        "symlinks": sorted(
            [
                {"path": item.path, "target": item.target, "resolvedPath": payloads._safe_link_target(item.path, item.target or "")}
                for item in entries.values()
                if item.kind == "symlink"
            ],
            key=lambda item: item["path"],
        ),
        "ignoredSymlinks": ignored_links,
        "_elfRaw": elf_raw,
    }


def _assert_external_path(path: Path) -> None:
    repo = Path(__file__).resolve().parents[4]
    if not path.is_absolute() or path.resolve(strict=False).is_relative_to(repo):
        raise ServerAuditError(f"source/output must be absolute and outside repository: {path}")
    cursor = Path(path.anchor)
    for part in path.parts[1:]:
        cursor /= part
        if cursor.is_symlink():
            raise ServerAuditError(f"path contains a symlink component: {path}")


def _extract_archive_members(
    path: Path,
    pin: payloads.PackagePin,
    selected: set[str],
    *,
    max_expanded_bytes: int,
    max_file_bytes: int,
) -> dict[str, bytes]:
    """Extract only selected regular members after a bounded safe tar walk."""
    raw = payloads.validate_pin(path, pin)
    members = payloads.parse_ar(raw)
    data = members.get("data.tar.xz")
    if data is None:
        raise ServerAuditError(f"package has no data.tar.xz: {path}")
    found: dict[str, bytes] = {}
    seen: set[str] = set()
    expanded = payloads.CountingReader(lzma.LZMAFile(io.BytesIO(data)), max_expanded_bytes)
    try:
        with tarfile.open(fileobj=expanded, mode="r|") as archive:
            for index, member in enumerate(archive):
                if index >= MAX_TAR_MEMBERS:
                    raise ServerAuditError("tar member count exceeds bound")
                member_path = payloads._validate_utf_path(member.name, field="tar path")
                if member_path == "." and member.isdir():
                    continue
                if member_path in seen:
                    raise ServerAuditError(f"duplicate tar path: {member_path}")
                seen.add(member_path)
                if member.isdir():
                    continue
                if member.issym():
                    payloads._safe_link_target(member_path, member.linkname)
                    continue
                if not member.isfile():
                    raise ServerAuditError(f"unsupported/special tar member: {member_path}")
                if member.size < 0 or member.size > max_file_bytes:
                    raise ServerAuditError(f"tar member exceeds bound: {member_path}")
                if member_path not in selected:
                    continue
                stream = archive.extractfile(member)
                if stream is None:
                    raise ServerAuditError(f"cannot read selected tar member: {member_path}")
                value = stream.read(member.size + 1)
                if len(value) != member.size:
                    raise ServerAuditError(f"truncated selected tar member: {member_path}")
                found[member_path] = value
            while expanded.read(1024 * 1024):
                pass
    except payloads.AuditError as exc:
        raise ServerAuditError(str(exc)) from exc
    except (lzma.LZMAError, tarfile.TarError) as exc:
        raise ServerAuditError(f"invalid data.tar.xz: {path}") from exc
    finally:
        expanded.close()
    missing = selected - found.keys()
    if missing:
        raise ServerAuditError(f"selected archive members are missing: {sorted(missing)}")
    return found


def _audit_cached_mesa(path: Path) -> tuple[dict[str, Any], dict[str, bytes]]:
    """Validate and inspect the already-cached Mesa archive; never download it."""
    _assert_external_path(path)
    if path.name != MESA_PACKAGE.basename:
        raise ServerAuditError(f"unexpected cached Mesa basename: {path.name}")
    raw = payloads.validate_pin(path, payloads.PackagePin(MESA_PACKAGE.bytes, MESA_PACKAGE.sha256))
    component = payloads.audit_package(
        path,
        payloads.PackagePin(MESA_PACKAGE.bytes, MESA_PACKAGE.sha256),
        max_expanded_bytes=MAX_MESA_EXPANDED_BYTES,
        max_file_bytes=MAX_MESA_FILE_BYTES,
        ignore_unneeded_et_exec=True,
        ignore_broken_nonlibrary_symlinks=True,
    )
    component["package"] = MESA_PACKAGE.package
    component["version"] = MESA_PACKAGE.version
    component["_provider"] = "cached_audited_mesa"
    gbm_record = _require_gbm_record(component)
    selected = _extract_archive_members(
        path,
        payloads.PackagePin(MESA_PACKAGE.bytes, MESA_PACKAGE.sha256),
        {MESA_GBM_PATH},
        max_expanded_bytes=MAX_MESA_EXPANDED_BYTES,
        max_file_bytes=MAX_MESA_FILE_BYTES,
    )
    gbm_raw = selected[MESA_GBM_PATH]
    if len(gbm_raw) != gbm_record["bytes"] or hashlib.sha256(gbm_raw).hexdigest() != gbm_record["sha256"]:
        raise ServerAuditError("cached Mesa libgbm source identity mismatch")
    if len(raw) != MESA_PACKAGE.bytes or hashlib.sha256(raw).hexdigest() != MESA_PACKAGE.sha256:
        raise ServerAuditError("cached Mesa package identity changed during audit")
    return component, selected


def _require_gbm_record(component: dict[str, Any]) -> dict[str, Any]:
    gbm = [item for item in component.get("libraries", []) if item.get("packagePath") == MESA_GBM_PATH]
    if len(gbm) != 1:
        raise ServerAuditError(f"cached Mesa has no unique regular {MESA_GBM_PATH}")
    if gbm[0].get("soname") != "libgbm.so":
        raise ServerAuditError(
            f"cached Mesa libgbm ABI gap: SONAME is {gbm[0].get('soname')!r}, expected 'libgbm.so'"
        )
    return gbm[0]


def _flat_library_name(package_path: str) -> str:
    name = PurePosixPath(package_path).name
    if not name or name in {".", ".."} or "/" in name or "\\" in name:
        raise ServerAuditError(f"unsafe flat library basename: {package_path}")
    payloads._validate_utf_path(f"lib/{name}", field="materialized path")
    return name


def _validate_materialization_items(items: list[MaterializationItem]) -> None:
    total = 0
    destinations: dict[str, str] = {}
    for item in items:
        if item.destination != "bin/virgl_test_server" and not item.destination.startswith("lib/"):
            raise ServerAuditError(f"materialized path is outside flat bin/lib layout: {item.destination}")
        payloads._validate_utf_path(item.destination, field="materialized path")
        if item.destination.startswith("lib/") and "/" in item.destination[4:]:
            raise ServerAuditError(f"materialized library path is not flat: {item.destination}")
        if item.destination in destinations:
            if destinations[item.destination] != item.sha256:
                raise ServerAuditError(f"materialized identity collision: {item.destination}")
            raise ServerAuditError(f"duplicate materialized destination: {item.destination}")
        destinations[item.destination] = item.sha256
        if item.bytes > MAX_MATERIALIZED_FILE_BYTES:
            raise ServerAuditError(f"materialized file exceeds 192 MiB: {item.destination}")
        if not SHA256_RE.fullmatch(item.sha256):
            raise ServerAuditError(f"materialized file has invalid SHA-256: {item.destination}")
        if item.source.get("bytes") != item.bytes or item.source.get("sha256") != item.sha256:
            raise ServerAuditError(f"materialized source identity mismatch: {item.destination}")
        if item.destination == "bin/virgl_test_server":
            if item.mode != 0o700 or item.source.get("elfType") != "ET_DYN":
                raise ServerAuditError("server materialization is not a 0700 PIE")
        elif item.mode != 0o600:
            raise ServerAuditError(f"library mode is not 0600: {item.destination}")
        total += item.bytes
        if total > MAX_MATERIALIZED_AGGREGATE_BYTES:
            raise ServerAuditError("materialized regular files exceed 256 MiB aggregate")


def _source_from_elf(component: dict[str, Any], item: dict[str, Any], *, kind: str, archive: Path) -> dict[str, Any]:
    return {
        "kind": kind,
        "package": component["package"],
        "archive": str(archive),
        "archiveBytes": component["bytes"],
        "archiveSha256": component["sha256"],
        "packagePath": item["packagePath"],
        "bytes": item["bytes"],
        "sha256": item["sha256"],
        "soname": item.get("soname"),
        "needed": item.get("needed", []),
        "elfType": item.get("elfType"),
        "elfMachine": item.get("elfMachine"),
        "elfClass": item.get("elfClass"),
        "elfData": item.get("elfData"),
        "runpath": item.get("runpath", []),
        "rpath": item.get("rpath", []),
    }


def _load_candidate_manifest(path: Path) -> dict[str, Any]:
    raw = _read_regular(path, MAX_MANIFEST_BYTES)
    try:
        manifest = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ServerAuditError(f"invalid candidate manifest: {path}") from exc
    if not isinstance(manifest, dict):
        raise ServerAuditError("candidate manifest is not an object")
    materialized = manifest.get("materializedFiles")
    if materialized is None:
        libraries = []
        for component in manifest.get("components", []):
            if not isinstance(component, dict) or not isinstance(component.get("libraries", []), list):
                raise ServerAuditError("candidate manifest has invalid component libraries")
            libraries.extend(component["libraries"])
    elif isinstance(materialized, list):
        libraries = materialized
    else:
        raise ServerAuditError("candidate manifest materializedFiles is not a list")
    for item in libraries:
        if not isinstance(item, dict) or not isinstance(item.get("packagePath"), str):
            raise ServerAuditError("candidate manifest has invalid library record")
    aliases = manifest.get("approvedSymlinkAliases", [])
    if not isinstance(aliases, list):
        raise ServerAuditError("candidate manifest aliases are not a list")
    return {
        "path": str(path),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "regularLibraries": libraries,
        "aliases": aliases,
        "ready": manifest.get("ready"),
    }


def _names_for_library(item: dict[str, Any]) -> set[str]:
    names = {item.get("soname")}
    package_path = item.get("packagePath")
    if isinstance(package_path, str):
        names.add(PurePosixPath(package_path).name)
    return {name for name in names if isinstance(name, str) and name}


def _closure(components: list[dict[str, Any]], server: dict[str, Any], manifest: dict[str, Any]) -> dict[str, Any]:
    new_owned: dict[str, dict[str, Any]] = {}
    for component in components:
        for library in component["libraries"]:
            provider = {
                **library,
                "provider": component.get("_provider", "new_pinned_package"),
                "package": component["package"],
            }
            for name in _names_for_library(library):
                if name in new_owned and new_owned[name]["packagePath"] != provider["packagePath"]:
                    raise ServerAuditError(f"conflicting new library name: {name}")
                new_owned[name] = provider
    server_provider = {**server, "provider": "new_pinned_package", "package": "virglrenderer"}
    new_owned[EXPECTED_SERVER] = server_provider

    candidate_owned: dict[str, dict[str, Any]] = {}
    for item in manifest["regularLibraries"]:
        provider = {**item, "provider": "existing_private_candidate"}
        for name in _names_for_library(item):
            candidate_owned.setdefault(name, provider)
    for alias in manifest["aliases"]:
        if not isinstance(alias, dict) or not isinstance(alias.get("path"), str):
            raise ServerAuditError("candidate manifest has invalid alias")
        alias_name = PurePosixPath(alias["path"]).name
        resolved = alias.get("resolvedPath")
        target_name = PurePosixPath(resolved).name if isinstance(resolved, str) else None
        if alias_name and target_name in candidate_owned:
            candidate_owned[alias_name] = candidate_owned[target_name]

    android = set(payloads.ALLOWED_ANDROID_SYSTEM_LIBS)
    queue: deque[tuple[str, str | None]] = deque([(EXPECTED_SERVER, None)])
    visited: set[str] = set()
    resolved: list[dict[str, Any]] = []
    missing: list[dict[str, Any]] = []
    static_needed: set[str] = set()
    while queue:
        name, parent = queue.popleft()
        if name in visited:
            continue
        visited.add(name)
        if name in new_owned:
            provider = new_owned[name]
        elif name in candidate_owned:
            provider = candidate_owned[name]
        elif name in android:
            resolved.append({"needed": name, "provider": "allowlisted_android_system"})
            continue
        else:
            missing.append({"needed": name, "from": parent, "reason": "not_owned_or_allowlisted"})
            continue
        resolved.append(
            {
                "needed": name,
                "provider": provider["provider"],
                "package": provider.get("package"),
                "packagePath": provider.get("packagePath"),
            }
        )
        for needed in provider.get("needed", []):
            if not isinstance(needed, str):
                raise ServerAuditError(f"invalid DT_NEEDED record for {name}")
            static_needed.add(needed)
            queue.append((needed, name))
    return {
        "root": EXPECTED_SERVER,
        "resolved": resolved,
        "missing": missing,
        "missingSonames": sorted({item["needed"] for item in missing}),
        "staticNeeded": sorted(static_needed),
        "allowlistedAndroidSystemSonames": sorted(android),
        "newOwnedNames": sorted(new_owned),
        "existingCandidateRegularLibraries": len(manifest["regularLibraries"]),
        "existingCandidateAliases": len(manifest["aliases"]),
        "candidateComparison": "static manifest presence only; no borrowed GLVND resolution claim",
    }


def _build_materialization_items(
    components: list[dict[str, Any]],
    closure: dict[str, Any],
    package_paths: dict[str, Path],
    manifest_path: Path,
) -> list[MaterializationItem]:
    by_package = {component["package"]: component for component in components}
    selected: dict[str, set[str]] = {}
    for resolved in closure["resolved"]:
        provider = resolved["provider"]
        package = resolved.get("package")
        package_path = resolved.get("packagePath")
        if provider == "allowlisted_android_system":
            continue
        if not isinstance(package, str) or not isinstance(package_path, str):
            raise ServerAuditError(f"closure provider has no source identity: {resolved}")
        selected.setdefault(package, set()).add(package_path)

    raw_by_package: dict[str, dict[str, bytes]] = {}
    for package, paths in selected.items():
        component = by_package.get(package)
        if component is None:
            continue
        if package == MESA_PACKAGE.package:
            raw_by_package[package] = {MESA_GBM_PATH: component["_selectedRaw"][MESA_GBM_PATH]}
            continue
        record = next((record for record in PACKAGES if record.package == package), None)
        if record is None:
            continue
        archive = package_paths.get(package)
        if archive is None:
            raise ServerAuditError(f"no cached package path for {package}")
        raw_by_package[package] = _extract_archive_members(
            archive,
            payloads.PackagePin(record.bytes, record.sha256),
            paths,
            max_expanded_bytes=MAX_DECODED_PACKAGE_BYTES,
            max_file_bytes=MAX_TAR_FILE_BYTES,
        )

    candidate_manifest = _load_candidate_manifest(manifest_path)
    candidate_records = {item["packagePath"]: item for item in candidate_manifest["regularLibraries"]}
    items: list[MaterializationItem] = []
    for resolved in closure["resolved"]:
        provider = resolved["provider"]
        if provider == "allowlisted_android_system":
            continue
        package = resolved["package"]
        package_path = resolved["packagePath"]
        if provider == "existing_private_candidate":
            candidate_item = candidate_records.get(package_path)
            if candidate_item is None or not isinstance(candidate_item.get("path"), str):
                raise ServerAuditError(f"candidate source path is missing: {package}:{package_path}")
            candidate_relative = candidate_item["path"]
            if not candidate_relative.startswith("lib/"):
                raise ServerAuditError(f"candidate source path is not under lib/: {candidate_relative}")
            payloads._validate_utf_path(candidate_relative, field="candidate source path")
            candidate_path = manifest_path.parent / candidate_relative
            _assert_external_path(candidate_path)
            expected_bytes = candidate_item.get("size")
            expected_sha = candidate_item.get("sha256")
            if not isinstance(expected_bytes, int) or not isinstance(expected_sha, str) or not SHA256_RE.fullmatch(expected_sha):
                raise ServerAuditError(f"candidate source identity is invalid: {package}:{package_path}")
            data = _read_regular(candidate_path, MAX_MATERIALIZED_FILE_BYTES)
            if len(data) != expected_bytes or hashlib.sha256(data).hexdigest() != expected_sha:
                raise ServerAuditError(f"candidate source identity mismatch: {candidate_path}")
            try:
                candidate_elf = payloads.parse_elf(data)
            except payloads.AuditError as exc:
                raise ServerAuditError(f"candidate provider is not ELF64LE AArch64 ET_DYN: {candidate_path}") from exc
            for field in ("soname", "needed", "runpath", "rpath"):
                if field in candidate_item and candidate_item[field] != candidate_elf.get(field):
                    raise ServerAuditError(f"candidate ELF metadata mismatch: {candidate_path}:{field}")
            source = {
                "kind": "existing_private_candidate",
                "manifest": str(manifest_path),
                "package": package,
                "packagePath": package_path,
                "sourcePath": str(candidate_path),
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "soname": candidate_elf.get("soname"),
                "needed": candidate_elf.get("needed", []),
                "elfType": candidate_elf.get("elfType"),
                "elfMachine": candidate_elf.get("elfMachine"),
                "elfClass": candidate_elf.get("elfClass"),
                "elfData": candidate_elf.get("elfData"),
                "runpath": candidate_elf.get("runpath", []),
                "rpath": candidate_elf.get("rpath", []),
            }
            items.append(MaterializationItem(f"lib/{_flat_library_name(package_path)}", data, source, 0o600))
            continue

        component = by_package.get(package)
        if component is None:
            raise ServerAuditError(f"closure package component missing: {package}")
        record = next((item for item in component["libraries"] if item["packagePath"] == package_path), None)
        if record is None:
            raise ServerAuditError(f"closure package member missing: {package}:{package_path}")
        if record.get("elfType") != "ET_DYN" or record.get("elfMachine") != 183 or record.get("elfClass") != 64 or record.get("elfData") != "LE":
            raise ServerAuditError(f"closure member is not an ELF64LE AArch64 ET_DYN: {package}:{package_path}")
        if package in raw_by_package:
            data = raw_by_package[package].get(package_path)
            if data is None:
                raise ServerAuditError(f"closure member was not extracted: {package}:{package_path}")
            source = _source_from_elf(
                component,
                record,
                kind="cached_audited_mesa" if package == MESA_PACKAGE.package else "fixed_pinned_deb",
                archive=package_paths.get(package, DEFAULT_MESA_PACKAGE),
            )
            destination = "bin/virgl_test_server" if PurePosixPath(package_path).name == EXPECTED_SERVER else f"lib/{_flat_library_name(package_path)}"
            mode = 0o700 if destination == "bin/virgl_test_server" else 0o600
            source["bytes"] = len(data)
            source["sha256"] = hashlib.sha256(data).hexdigest()
            if source["bytes"] != record["bytes"] or source["sha256"] != record["sha256"]:
                raise ServerAuditError(f"selected ELF source identity mismatch: {package}:{package_path}")
            items.append(MaterializationItem(destination, data, source, mode))
            continue

    # A root executable and each selected library must have one destination;
    # duplicate SONAMEs are harmless only when their exact bytes also match.
    deduped: dict[str, MaterializationItem] = {}
    for item in items:
        previous = deduped.get(item.destination)
        if previous is None:
            deduped[item.destination] = item
        elif previous.sha256 != item.sha256:
            raise ServerAuditError(f"materialized destination has different bytes: {item.destination}")
    result = list(deduped.values())
    _validate_materialization_items(result)
    if not any(item.destination == "bin/virgl_test_server" for item in result):
        raise ServerAuditError("materialization has no virgl_test_server root")
    return sorted(result, key=lambda item: item.destination)


def _dynamic_names(components: Iterable[dict[str, Any]]) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {name: [] for name in DLOPEN_NAMES}
    for component in components:
        for path_name, raw in component["_elfRaw"].items():
            for name in DLOPEN_NAMES:
                if name.encode() + b"\0" in raw:
                    found[name].append(f"{component['package']}:{path_name}")
    return found


def _write_materialized_file(root: Path, item: MaterializationItem) -> None:
    target = root / item.destination
    if target.exists() or target.is_symlink() or not target.parent.is_dir() or target.parent.is_symlink():
        raise ServerAuditError(f"refusing to overwrite materialized file: {target}")
    with target.open("xb") as stream:
        view = memoryview(item.data)
        for offset in range(0, len(view), 1024 * 1024):
            stream.write(view[offset : offset + 1024 * 1024])
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(target, item.mode)


def materialize_server_candidate(
    package_paths: list[Path],
    mesa_path: Path,
    manifest_path: Path,
    output_dir: Path,
    report_path: Path,
) -> dict[str, Any]:
    """Build a new private host candidate from already verified archives only."""
    _validate_fixed_source()
    _assert_external_path(mesa_path)
    _assert_external_path(output_dir)
    _assert_external_path(report_path)
    if output_dir.exists() or output_dir.is_symlink():
        raise ServerAuditError(f"server candidate output must be new: {output_dir}")
    if report_path.exists() or report_path.is_symlink():
        raise ServerAuditError(f"refusing to overwrite report: {report_path}")
    if not output_dir.parent.is_dir() or output_dir.parent.is_symlink():
        raise ServerAuditError("server candidate parent must be an existing non-symlink directory")
    if not report_path.parent.is_dir() or report_path.parent.is_symlink():
        raise ServerAuditError("server report parent must be an existing non-symlink directory")

    expected_paths = {record.package: path for record, path in zip(PACKAGES, package_paths)}
    base = audit_server(package_paths, manifest_path, acquisition={"downloadedBytes": 0, "metadata": None})
    parsed_components = []
    for record in PACKAGES:
        path = expected_paths.get(record.package)
        if path is None:
            raise ServerAuditError(f"missing cached package path: {record.package}")
        parsed_components.append(_parse_package(path, record))
    server_entries = [
        item
        for component in parsed_components
        for item in component["libraries"]
        if PurePosixPath(item["packagePath"]).name == EXPECTED_SERVER
    ]
    if len(server_entries) != 1:
        raise ServerAuditError(f"expected exactly one {EXPECTED_SERVER} root")
    server_entry = server_entries[0]
    if server_entry["elfType"] != "ET_DYN" or server_entry["ptInterp"] != SERVER_INTERPRETER:
        raise ServerAuditError(
            f"server PIE ABI gap: expected ET_DYN PT_INTERP {SERVER_INTERPRETER}, got {server_entry['elfType']} {server_entry['ptInterp']!r}"
        )
    for component in parsed_components:
        for item in component["libraries"]:
            if item["elfType"] != "ET_DYN" or item["elfMachine"] != 183 or item["elfClass"] != 64 or item["elfData"] != "LE":
                raise ServerAuditError(f"unexpected non-AArch64 PIE ELF in selected package: {item['packagePath']}")

    mesa_component, mesa_selected = _audit_cached_mesa(mesa_path)
    mesa_component["_selectedRaw"] = mesa_selected
    augmented_components = parsed_components + [mesa_component]
    candidate_manifest = _load_candidate_manifest(manifest_path)
    closure = _closure(augmented_components, server_entry, candidate_manifest)
    if closure["missingSonames"]:
        raise ServerAuditError(f"static closure remains incomplete after cached Mesa: {closure['missingSonames']}")
    item_paths = dict(expected_paths)
    item_paths[MESA_PACKAGE.package] = mesa_path
    items = _build_materialization_items(augmented_components, closure, item_paths, manifest_path)
    _validate_materialization_items(items)

    _ensure_new_output(output_dir)
    (output_dir / "bin").mkdir(mode=0o700)
    (output_dir / "lib").mkdir(mode=0o700)
    os.chmod(output_dir, 0o700)
    for item in items:
        _write_materialized_file(output_dir, item)

    materialized_files = [
        {
            "destination": item.destination,
            "mode": format(item.mode, "04o"),
            "bytes": item.bytes,
            "sha256": item.sha256,
            "sourceIdentity": item.source,
        }
        for item in items
    ]
    candidate_doc = {
        "schema": "wowforever.virgl-server-candidate",
        "schemaVersion": 1,
        "ready": False,
        "executedPayloads": False,
        "materializedFiles": materialized_files,
        "deviceAliases": list(DEVICE_ALIASES),
        "closure": closure,
        "privateLoaderResolution": {
            "status": "UNVERIFIED",
            "runpathPolicy": "preserve original Termux DT_RUNPATH",
            "futureProcessPrefix": "private LD_LIBRARY_PATH:/system/lib64",
        },
        "protocol": {"status": "UNVERIFIED"},
        "source": {
            "serverPackages": base["sourceAuthority"]["packages"],
            "mesa": {
                "package": MESA_PACKAGE.package,
                "version": MESA_PACKAGE.version,
                "architecture": MESA_PACKAGE.architecture,
                "filename": MESA_PACKAGE.filename,
                "path": str(mesa_path),
                "bytes": MESA_PACKAGE.bytes,
                "sha256": MESA_PACKAGE.sha256,
                "audit": "existing pinned archive; no network acquisition",
            },
            "candidateManifest": candidate_manifest["path"],
            "licenseCompliance": "not assessed",
        },
    }
    _write_exclusive(output_dir / "candidate-manifest.json", candidate_doc)

    report = json.loads(json.dumps(base))
    report["components"] = json.loads(json.dumps(base["components"])) + [
        {key: value for key, value in mesa_component.items() if key not in {"_selectedRaw", "_provider"}}
    ]
    report["closure"] = closure
    report["acquisition"]["newNetworkBytes"] = 0
    report["sourceAuthority"]["cachedMesa"] = candidate_doc["source"]["mesa"]
    report["sourceAuthority"]["licenseCompliance"] = "not assessed"
    report["materialization"] = {
        "status": "HOST_ONLY_MATERIALIZED",
        "outputDirectory": str(output_dir),
        "manifest": str(output_dir / "candidate-manifest.json"),
        "aggregateRegularBytes": sum(item.bytes for item in items),
        "fileCount": len(items),
        "deviceAliases": list(DEVICE_ALIASES),
        "hostSymlinksCreated": False,
        "executionPerformed": False,
    }
    report["dlopen"]["missingDlopen"] = []
    report["dlopen"]["dynamicResolution"] = "UNVERIFIED"
    report["dlopen"]["androidSystemAliasesPlan"] = list(DEVICE_ALIASES)
    report["privateLoaderResolution"]["futureProcessPrefix"] = "private LD_LIBRARY_PATH:/system/lib64"
    report["privateDependencyPlan"]["libgbm"] = {
        "expectedSoname": "libgbm.so",
        "provider": "cached_audited_mesa",
        "status": "PRESENT",
    }
    report["tests"]["staticClosure"] = "PASS"
    report["tests"]["materialization"] = "PASS"
    report["risks"].extend(
        [
            "dynamic Android EGL aliases remain UNVERIFIED until a future private loader run",
            "libgbm.so DT_NEEDED does not imply DRI usage or a driver selection",
            "device aliases are a JSON plan only; no host/global symlinks were created",
            "source package license compliance was not assessed",
        ]
    )
    report["blockers"] = [
        "private loader resolution remains UNVERIFIED",
        "vtest handshake/protocol remains UNVERIFIED",
        "PRESENT/D3D/WoW remain false until independent probes qualify them",
    ]
    report["reportPath"] = str(report_path)
    _write_exclusive(report_path, report)
    return report


def audit_server(package_paths: list[Path], manifest_path: Path, *, acquisition: dict[str, Any] | None = None) -> dict[str, Any]:
    _validate_fixed_source()
    if len(package_paths) != len(PACKAGES):
        raise ServerAuditError("exactly the two fixed server packages are required")
    by_basename = {record.basename: record for record in PACKAGES}
    components = []
    for path in package_paths:
        record = by_basename.get(path.name)
        if record is None:
            raise ServerAuditError(f"unexpected package basename: {path.name}")
        components.append(_parse_package(path, record))
    if {item["package"] for item in components} != {record.package for record in PACKAGES}:
        raise ServerAuditError("both fixed package identities are required")
    servers = [
        (component, path_name, details)
        for component in components
        for path_name, details in ((item["packagePath"], item) for item in component["libraries"])
        if PurePosixPath(path_name).name == EXPECTED_SERVER
    ]
    if len(servers) != 1:
        raise ServerAuditError(f"expected exactly one {EXPECTED_SERVER} ELF")
    server_component, server_path, server = servers[0]
    manifest = _load_candidate_manifest(manifest_path)
    closure = _closure(components, server, manifest)
    dynamic = _dynamic_names(components)
    available_names = set(closure["newOwnedNames"])
    available_names.update(payload["needed"] for payload in closure["resolved"])
    missing_dlopen = sorted(name for name in DLOPEN_NAMES if name not in available_names)
    virgl_sonames = sorted(
        item["soname"]
        for component in components
        for item in component["libraries"]
        if isinstance(item.get("soname"), str) and item["soname"].startswith("libvirglrenderer")
    )
    epoxy_sonames = sorted(
        item["soname"]
        for component in components
        for item in component["libraries"]
        if isinstance(item.get("soname"), str) and item["soname"].startswith(EXPECTED_EPOXY_SONAME_PREFIX)
    )
    for component in components:
        component.pop("_elfRaw", None)
    report = {
        "schema": "wowforever.virgl-vtest-server-static-audit",
        "schemaVersion": 1,
        "hostOnly": True,
        "executionPerformed": False,
        "serverKind": "standard_mesa_vtest_server_not_angle",
        "sourceAuthority": {
            "origin": ORIGIN,
            "packages": [
                {
                    "package": record.package,
                    "version": record.version,
                    "architecture": record.architecture,
                    "filename": record.filename,
                    "url": record.url,
                    "bytes": record.bytes,
                    "sha256": record.sha256,
                }
                for record in PACKAGES
            ],
            "recipe": {
                "commit": RECIPE_COMMIT,
                "path": RECIPE_PATH,
                "version": RECIPE_VERSION,
                "tarSha256": RECIPE_TAR_SHA256,
                "options": PLATFORM_OPTIONS,
                "libepoxyUnixDlopenPatch": True,
            },
            "runtimeBionic": "UNVERIFIED",
            "aptGpgVerified": False,
            "publishedMetadataPinsValidated": True,
            "trustBasis": "HTTPS/published metadata pins only; no full apt-GPG/source equivalence claim",
            "metadata": acquisition.get("metadata") if acquisition else None,
        },
        "acquisition": {
            "newNetworkBytes": acquisition.get("downloadedBytes", 0) if acquisition else 0,
            "aggregateLimitBytes": MAX_AGGREGATE_DOWNLOAD_BYTES,
            "perDownloadLimitBytes": MAX_PACKAGE_BYTES,
            "timeoutSeconds": DOWNLOAD_TIMEOUT,
            "packageFiles": [
                {
                    "package": component["package"],
                    "path": str(package_paths[index]),
                    "bytes": component["bytes"],
                    "sha256": component["sha256"],
                }
                for index, component in enumerate(components)
            ],
        },
        "server": {
            "expectedName": EXPECTED_SERVER,
            "actualPackage": server_component["package"],
            "actualPackagePath": server_path,
            "bytes": server["bytes"],
            "sha256": server["sha256"],
            "ptInterp": server["ptInterp"],
            "needed": server["needed"],
            "soname": server["soname"],
            "runpath": server["runpath"],
            "rpath": server["rpath"],
            "staticExports": server["staticExports"],
            "elfClass": server["elfClass"],
            "elfData": server["elfData"],
            "elfMachine": server["elfMachine"],
            "elfType": server["elfType"],
        },
        "components": components,
        "privateDependencyPlan": {
            "virglrenderer": {
                "expectedSoname": EXPECTED_VIRGL_SONAME,
                "observedSonames": virgl_sonames,
                "status": "PRESENT" if EXPECTED_VIRGL_SONAME in virgl_sonames else "MISSING",
            },
            "libepoxy": {
                "expectedSonamePrefix": EXPECTED_EPOXY_SONAME_PREFIX,
                "observedSonames": epoxy_sonames,
                "status": "PRESENT" if epoxy_sonames else "MISSING",
                "resolution": "private pinned package only; no system/GLVND borrowing",
            },
        },
        "closure": closure,
        "dlopen": {
            "names": DLOPEN_NAMES,
            "observedStringCandidates": dynamic,
            "staticNeeded": closure["staticNeeded"],
            "missingDlopen": missing_dlopen,
            "status": "UNVERIFIED_string_candidates_only",
            "systemNativeEglAliases": {
                name: {"staticNeeded": name in closure["staticNeeded"], "status": "UNVERIFIED"} for name in SYSTEM_EGL_ALIASES
            },
            "note": "libEGL.so.1/libGLESv2.so.2 are not DT_NEEDED proof; no borrowed GLVND resolution claim is made.",
        },
        "protocol": {
            "standardServer": "Mesa vtest/virgl standard server",
            "status": "UNVERIFIED",
            "socketScope": "private_own_socket_only",
            "eglOwner": "native_EGL_required",
            "gameNativeCreateRendererSocket": "INCOMPATIBLE_SCOPE",
            "note": "Current GameNative CREATE_RENDERER1/Mesa8 socket is not this standard server protocol.",
        },
        "tests": {
            "fixedPinsAndArchiveParsing": "PASS",
            "elf64LeAarch64EtDynAndMetadata": "PASS",
            "serverIdentity": "PASS",
            "staticClosure": "BLOCKED_MISSING_SONAMES" if closure["missingSonames"] else "PASS",
            "dlopenResolution": "UNVERIFIED",
            "deviceExecution": "NOT_RUN",
        },
        "privateLoaderResolution": {
            "status": "UNVERIFIED",
            "method": "static DT_NEEDED/name comparison only; no loader or patchelf",
        },
        "present": False,
        "d3d": False,
        "wow": False,
        "ready": False,
        "readinessReasons": [
            "private_loader_resolution_unverified",
            "protocol_unverified",
            "present_not_tested",
            "d3d_not_tested",
            "wow_not_tested",
        ],
        "risks": [
            "IS_BIONIC runtime compatibility is unverified",
            "static strings do not prove dlopen behavior or EGL alias resolution",
            "no hardware/vendor inference is made from names or hashes",
            "native pbuffer success does not prove EGL_NO_SURFACE support",
        ],
        "blockers": [
            *[f"missing static SONAME: {name}" for name in closure["missingSonames"]],
            "private loader resolution remains UNVERIFIED",
            "vtest handshake/protocol remains UNVERIFIED",
            "PRESENT/D3D/WoW remain false until independent probes qualify them",
        ],
    }
    return report


def _write_exclusive(path: Path, report: dict[str, Any]) -> None:
    _assert_external_path(path)
    if path.exists() or path.is_symlink() or not path.parent.is_dir() or path.parent.is_symlink():
        raise ServerAuditError(f"refusing to overwrite report: {path}")
    with path.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")


def main(argv: list[str] | None = None) -> int:
    _validate_fixed_source()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", type=Path, default=DEFAULT_ARTIFACT_DIR)
    parser.add_argument("--candidate-manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--mesa-package", type=Path, help="existing pinned Mesa .deb; never fetched")
    parser.add_argument("--server-candidate", type=Path, help="new external host candidate directory")
    parser.add_argument("--server-report", type=Path, help="new external static/materialization report")
    parser.add_argument("--materialize-server", action="store_true", help="audit cached Mesa and materialize a new private host candidate")
    parser.add_argument("--metadata", type=Path, help="existing official package metadata JSON; never fetched")
    parser.add_argument("--output", type=Path, help="external report path; defaults to artifact-dir/report.json")
    args = parser.parse_args(argv)
    if args.materialize_server:
        package_paths = [args.artifact_dir / record.basename for record in PACKAGES]
        report = materialize_server_candidate(
            package_paths,
            args.mesa_package or DEFAULT_MESA_PACKAGE,
            args.candidate_manifest,
            args.server_candidate or DEFAULT_SERVER_CANDIDATE,
            args.server_report or DEFAULT_SERVER_REPORT,
        )
        print(json.dumps({"ready": report["ready"], "missingSonames": report["closure"]["missingSonames"], "newNetworkBytes": report["acquisition"]["newNetworkBytes"], "candidate": report["materialization"]["outputDirectory"]}, sort_keys=True))
        return 0
    paths, acquisition, _downloaded = acquire_packages(args.artifact_dir, metadata=args.metadata)
    report = audit_server(paths, args.candidate_manifest, acquisition=acquisition)
    output = args.output or args.artifact_dir / "report.json"
    _write_exclusive(output, report)
    print(json.dumps({"ready": report["ready"], "missingSonames": report["closure"]["missingSonames"], "newNetworkBytes": report["acquisition"]["newNetworkBytes"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
