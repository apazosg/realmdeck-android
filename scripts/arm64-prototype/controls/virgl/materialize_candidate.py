#!/usr/bin/env python3
"""Acquire a fixed Termux ledger and materialize only its audited ELF closure.

This is a bounded host-side artifact operation.  It has no package manager,
APT index refresh, root/device interface, ELF loader, compiler, or APK logic.
Downloaded packages remain outside the repository; only selected libraries
and safe aliases are copied to a newly-created private candidate directory.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import posixpath
import re
import shutil
import subprocess
import tarfile
import tempfile
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import lzma
from dataclasses import dataclass
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any, BinaryIO, Iterable

import audit_payloads as audit


ORIGIN = "https://packages-cf.termux.dev/apt/termux-main/"
PINNED_INDEX_SHA256 = "22ab9c8c17f404d3347b0f429a2efa1e315c607cf35bb52237e983aa4550ba6d"
MAX_METADATA_BYTES = 16 * 1024 * 1024
MAX_LEDGER_BYTES = 50 * 1024 * 1024
MAX_DOWNLOAD_TIMEOUT = 90
DOWNLOAD_CHUNK_BYTES = 64 * 1024
MAX_CANDIDATE_AGGREGATE_BYTES = 512 * 1024 * 1024
SHA256_RE = re.compile(r"\A[0-9a-fA-F]{64}\Z")


class MaterializerError(ValueError):
    """A fixed-ledger, network, archive, or output-safety failure."""


@dataclass(frozen=True)
class LedgerPackage:
    package: str
    version: str
    architecture: str
    filename: str
    sha256: str
    bytes: int
    depends: str | None

    @property
    def basename(self) -> str:
        return Path(self.filename).name

    @property
    def url(self) -> str:
        return ORIGIN + self.filename

    def as_manifest(self, *, source: str) -> dict[str, Any]:
        return {
            "package": self.package,
            "version": self.version,
            "architecture": self.architecture,
            "filename": self.filename,
            "url": self.url,
            "sha256": self.sha256,
            "bytes": self.bytes,
            "depends": self.depends,
            "source": source,
        }


def _read_existing(path: Path, limit: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise MaterializerError(f"not a regular non-symlink file: {path}")
    size = path.stat().st_size
    if size > limit:
        raise MaterializerError(f"file exceeds bound: {path}")
    return path.read_bytes()


def _reject_symlink_components(path: Path) -> None:
    if not path.is_absolute():
        raise MaterializerError(f"path must be absolute: {path}")
    cursor = Path(path.anchor)
    for part in path.parts[1:]:
        cursor /= part
        if cursor.is_symlink():
            raise MaterializerError(f"path contains symlink component: {path}")


def _outside_repo(path: Path) -> None:
    repo = Path(__file__).resolve().parents[4]
    candidate = path.resolve(strict=False)
    try:
        candidate.relative_to(repo)
    except ValueError:
        return
    raise MaterializerError(f"artifact path must be outside repository: {path}")


def _safe_relative_filename(value: str) -> str:
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as exc:
        raise MaterializerError("metadata filename is not UTF-8") from exc
    if (
        not value.startswith("pool/")
        or "\\" in value
        or "\x00" in value
        or "?" in value
        or "#" in value
        or unicodedata.normalize("NFC", value) != value
    ):
        raise MaterializerError(f"unsafe metadata filename: {value!r}")
    parts = value.split("/")
    if any(not part or part in {".", ".."} for part in parts):
        raise MaterializerError(f"unsafe metadata filename: {value!r}")
    return value


def _parse_dep_choices(depends: str | None) -> list[list[str]]:
    if not depends:
        return []
    result: list[list[str]] = []
    for clause in depends.split(","):
        choices = []
        for choice in clause.split("|"):
            match = re.match(r"\s*([a-z0-9][a-z0-9+.-]*)\s*(?:\(|$)", choice, re.IGNORECASE)
            if match:
                choices.append(match.group(1))
        if choices:
            result.append(choices)
    return result


def load_fixed_metadata(path: Path) -> tuple[dict[str, Any], list[LedgerPackage]]:
    raw = _read_existing(path, MAX_METADATA_BYTES)
    try:
        metadata = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise MaterializerError(f"invalid metadata JSON: {path}") from exc
    if not isinstance(metadata, dict) or not isinstance(metadata.get("packages"), dict):
        raise MaterializerError("metadata packages body is missing")
    if metadata.get("repository") != ORIGIN:
        raise MaterializerError("metadata origin is not the fixed official HTTPS origin")
    if metadata.get("compressedIndexSha256") != PINNED_INDEX_SHA256:
        raise MaterializerError("metadata compressed index pin mismatch")
    declared_total = metadata.get("compressedPackageBytes")
    try:
        declared_total = int(declared_total)
    except (TypeError, ValueError) as exc:
        raise MaterializerError("metadata compressedPackageBytes is invalid") from exc

    records: list[LedgerPackage] = []
    seen_names: set[str] = set()
    seen_files: set[str] = set()
    for key, item in sorted(metadata["packages"].items()):
        if not isinstance(item, dict):
            raise MaterializerError(f"invalid package stanza: {key}")
        package = item.get("Package", key)
        if package != key or not isinstance(package, str) or package in seen_names:
            raise MaterializerError(f"invalid/duplicate package name: {key}")
        architecture = item.get("Architecture")
        if architecture not in {"aarch64", "all"}:
            raise MaterializerError(f"unsupported package architecture: {package}")
        filename = item.get("Filename")
        if not isinstance(filename, str):
            raise MaterializerError(f"missing filename: {package}")
        filename = _safe_relative_filename(filename)
        if filename in seen_files:
            raise MaterializerError(f"duplicate metadata filename: {filename}")
        version = item.get("Version")
        digest = item.get("SHA256")
        try:
            size = int(item.get("Size"))
        except (TypeError, ValueError) as exc:
            raise MaterializerError(f"invalid package size: {package}") from exc
        if not isinstance(version, str) or size < 0 or not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            raise MaterializerError(f"invalid package pin: {package}")
        url = ORIGIN + filename
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.netloc != "packages-cf.termux.dev" or parsed.query or parsed.fragment:
            raise MaterializerError(f"unsafe package URL: {url}")
        records.append(
            LedgerPackage(package, version, architecture, filename, digest.lower(), size, item.get("Depends"))
        )
        seen_names.add(package)
        seen_files.add(filename)
    actual_total = sum(record.bytes for record in records)
    if actual_total != declared_total:
        raise MaterializerError(f"ledger byte total mismatch: {actual_total} != {declared_total}")
    if actual_total > MAX_LEDGER_BYTES:
        raise MaterializerError(f"ledger exceeds aggregate bound: {actual_total} > {MAX_LEDGER_BYTES}")
    names = {record.package for record in records}
    unresolved: list[list[str]] = []
    for record in records:
        for choices in _parse_dep_choices(record.depends):
            if not any(choice in names for choice in choices):
                unresolved.append(choices)
    if unresolved:
        raise MaterializerError(f"declared dependency choices absent from fixed metadata: {unresolved}")
    return metadata, records


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request: urllib.request.Request, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        raise MaterializerError(f"redirect refused for fixed URL: {newurl}")


def _validate_package_file(path: Path, record: LedgerPackage) -> None:
    audit.validate_pin(path, audit.PackagePin(record.bytes, record.sha256))


def _download_one(record: LedgerPackage, target: Path, opener: urllib.request.OpenerDirector) -> None:
    if target.exists() or target.is_symlink():
        raise MaterializerError(f"refusing to overwrite package: {target}")
    created = False
    try:
        request = urllib.request.Request(record.url, headers={"Accept": "application/octet-stream"})
        with opener.open(request, timeout=MAX_DOWNLOAD_TIMEOUT) as response:
            if getattr(response, "status", response.getcode()) != 200 or response.geturl() != record.url:
                raise MaterializerError(f"fixed package URL did not return ordinary 200: {record.url}")
            content_length = response.headers.get("Content-Length")
            if content_length is not None and int(content_length) > record.bytes:
                raise MaterializerError(f"response exceeds pinned size: {record.package}")
            with target.open("xb") as output:
                created = True
                digest = hashlib.sha256()
                total = 0
                while True:
                    chunk = response.read(DOWNLOAD_CHUNK_BYTES)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > record.bytes:
                        raise MaterializerError(f"response exceeds pinned size: {record.package}")
                    output.write(chunk)
                    digest.update(chunk)
                output.flush()
                os.fsync(output.fileno())
            if total != record.bytes or digest.hexdigest() != record.sha256:
                raise MaterializerError(f"download pin mismatch: {record.package}")
    except urllib.error.HTTPError as exc:
        if exc.code == 403:
            if created:
                target.unlink(missing_ok=True)
            _download_with_curl(record, target)
            return
        if created:
            target.unlink(missing_ok=True)
        raise MaterializerError(f"fixed package URL returned HTTP {exc.code}: {record.url}") from exc
    except (OSError, urllib.error.URLError, ValueError) as exc:
        if isinstance(exc, MaterializerError):
            error = exc
        else:
            error = MaterializerError(f"download failed for {record.package}: {exc}")
        if created:
            target.unlink(missing_ok=True)
        raise error from exc
    except Exception:
        if created:
            target.unlink(missing_ok=True)
        raise


def _download_with_curl(record: LedgerPackage, target: Path) -> None:
    """Use curl only for the same pinned URL when urllib gets the known 403."""
    curl = shutil.which("curl")
    if curl is None:
        raise MaterializerError("urllib received HTTP 403 and curl is unavailable")
    fd, temporary_name = tempfile.mkstemp(prefix=".candidate-download-", dir=target.parent)
    os.close(fd)
    temporary = Path(temporary_name)
    try:
        command = [
            curl,
            "--fail",
            "--silent",
            "--show-error",
            "--max-time",
            str(MAX_DOWNLOAD_TIMEOUT),
            "--max-redirs",
            "0",
            "--proto",
            "=https",
            "--proto-redir",
            "=https",
            "--max-filesize",
            str(record.bytes + 1),
            "--header",
            "Accept: application/octet-stream",
            "--output",
            str(temporary),
            "--write-out",
            "%{http_code}",
            record.url,
        ]
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=MAX_DOWNLOAD_TIMEOUT + 5, check=False)
        status = completed.stdout.decode("ascii", "replace").strip()
        if completed.returncode != 0 or status != "200":
            raise MaterializerError(f"curl did not return ordinary 200 for fixed URL: HTTP {status or 'unknown'}")
        _validate_package_file(temporary, record)
        if target.exists() or target.is_symlink():
            raise MaterializerError(f"refusing to overwrite package: {target}")
        os.link(temporary, target)
    except (OSError, subprocess.SubprocessError) as exc:
        raise MaterializerError(f"curl download failed for {record.package}: {exc}") from exc
    finally:
        temporary.unlink(missing_ok=True)


def acquire_packages(
    records: list[LedgerPackage], package_dir: Path, *, offline_only: bool = False
) -> list[dict[str, Any]]:
    """Verify existing packages or fetch exact ledger objects, never replacing."""
    _outside_repo(package_dir)
    _reject_symlink_components(package_dir)
    if not package_dir.is_dir() or package_dir.is_symlink():
        raise MaterializerError(f"package directory must already exist: {package_dir}")
    if offline_only:
        missing = [str(package_dir / record.basename) for record in records if not (package_dir / record.basename).is_file()]
        if missing:
            raise MaterializerError(f"offline cache is missing pinned packages: {missing}")
    opener = None if offline_only else urllib.request.build_opener(_NoRedirect)
    result = []
    for record in records:
        target = package_dir / record.basename
        if target.exists() or target.is_symlink():
            _validate_package_file(target, record)
            source = "existing_verified"
        else:
            if opener is None:
                raise MaterializerError(f"offline cache is missing pinned package: {target}")
            _download_one(record, target, opener)
            _validate_package_file(target, record)
            source = "downloaded_fixed_url"
        result.append(record.as_manifest(source=source) | {"path": str(target)})
    return result


def _audit_records(records: list[LedgerPackage], package_dir: Path) -> list[dict[str, Any]]:
    components = []
    for record in records:
        path = package_dir / record.basename
        components.append(
            audit.audit_package(
                path,
                audit.PackagePin(record.bytes, record.sha256),
                max_expanded_bytes=audit.MAX_CANDIDATE_EXPANDED_BYTES,
                max_file_bytes=audit.MAX_CANDIDATE_FILE_BYTES,
                ignore_unneeded_et_exec=True,
                ignore_broken_nonlibrary_symlinks=True,
            )
        )
    return components


def _symlink_final_path(component: dict[str, Any], path: str, seen: set[str] | None = None) -> str:
    regular = {item["packagePath"] for item in component["libraries"]}
    links = {item["path"]: item["resolvedPath"] for item in component["symlinks"]}
    if path in regular:
        return path
    seen = seen or set()
    if path in seen or path not in links:
        raise MaterializerError(f"selected symlink does not resolve internally: {path}")
    return _symlink_final_path(component, links[path], seen | {path})


def select_closure_files(
    components: list[dict[str, Any]],
    closure: dict[str, Any],
    *,
    include_optional_driver: bool = True,
) -> dict[str, set[tuple[str, str]]]:
    selected_sonames = {item["needed"] for item in closure["resolved"] if item["provider"] == "owned"}
    if include_optional_driver:
        selected_sonames.add("libdril_dri.so")
    regular: set[tuple[str, str]] = set()
    aliases: set[tuple[str, str]] = set()
    for component in components:
        for library in component["libraries"]:
            library_names = {library.get("soname"), PurePosixPath(library["packagePath"]).name}
            if library_names & selected_sonames:
                regular.add((component["package"], library["packagePath"]))
        for link in component["symlinks"]:
            try:
                final = _symlink_final_path(component, link["path"])
            except MaterializerError:
                continue
            if (component["package"], final) in regular:
                aliases.add((component["package"], link["path"]))
    if not any(path.endswith("/libdril_dri.so") for _package, path in regular):
        # The load module is optional in the ledger; absence is reported by the
        # manifest rather than invented or fetched from another package.
        pass
    return {"regular": regular, "aliases": aliases}


def _candidate_relative(path: str) -> str:
    prefix = "data/data/com.termux/files/usr/"
    if not path.startswith(prefix):
        raise MaterializerError(f"payload path is outside Termux usr prefix: {path}")
    relative = path[len(prefix) :]
    if not relative.startswith("lib/"):
        raise MaterializerError(f"selected payload path is not a library: {path}")
    audit._validate_utf_path(relative, field="candidate path")
    return relative


def _candidate_asset_relative(path: str) -> str:
    prefix = "data/data/com.termux/files/usr/"
    if not path.startswith(prefix):
        raise MaterializerError(f"payload path is outside Termux usr prefix: {path}")
    relative = path[len(prefix) :]
    try:
        audit._validate_utf_path(relative, field="candidate asset path")
    except audit.AuditError as exc:
        raise MaterializerError(str(exc)) from exc
    return relative


def _ensure_output_dir(path: Path) -> None:
    _outside_repo(path)
    _reject_symlink_components(path)
    if path.exists() or path.is_symlink():
        raise MaterializerError(f"candidate output must be new and non-symlink: {path}")
    if not path.parent.is_dir() or path.parent.is_symlink():
        raise MaterializerError(f"candidate parent must be existing non-symlink directory: {path.parent}")
    path.mkdir(mode=0o700)


def _copy_member(stream: BinaryIO, target: Path, expected_size: int) -> tuple[int, str]:
    if target.exists() or target.is_symlink():
        raise MaterializerError(f"refusing to overwrite candidate file: {target}")
    digest = hashlib.sha256()
    total = 0
    with target.open("xb") as output:
        os.chmod(target, 0o600)
        while True:
            chunk = stream.read(DOWNLOAD_CHUNK_BYTES)
            if not chunk:
                break
            total += len(chunk)
            if total > expected_size:
                raise MaterializerError(f"selected tar member grew beyond header: {target}")
            output.write(chunk)
            digest.update(chunk)
        output.flush()
        os.fsync(output.fileno())
    if total != expected_size:
        raise MaterializerError(f"selected tar member truncated: {target}")
    return total, digest.hexdigest()


def _extract_selected(
    records: list[LedgerPackage],
    package_dir: Path,
    components: list[dict[str, Any]],
    selected: dict[str, set[tuple[str, str]]],
    output_dir: Path,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    files: list[dict[str, Any]] = []
    aliases: list[dict[str, Any]] = []
    by_package = {component["package"]: component for component in components}
    for record in records:
        selected_paths = {path for package, path in selected["regular"] if package == record.basename}
        selected_aliases = {path for package, path in selected["aliases"] if package == record.basename}
        if not selected_paths and not selected_aliases:
            continue
        raw = audit.validate_pin(package_dir / record.basename, audit.PackagePin(record.bytes, record.sha256))
        members = audit.parse_ar(raw)
        data = members.get("data.tar.xz")
        if data is None:
            raise MaterializerError(f"missing data.tar.xz in {record.package}")
        component = by_package[record.basename]
        library_by_path = {item["packagePath"]: item for item in component["libraries"]}
        with lzma.LZMAFile(io.BytesIO(data)) as compressed:
            counted = audit.CountingReader(compressed, audit.MAX_CANDIDATE_EXPANDED_BYTES)
            with tarfile.open(fileobj=counted, mode="r|") as archive:
                for member in archive:
                    path = audit._validate_utf_path(member.name, field="tar path")
                    if path in selected_paths:
                        if not member.isfile():
                            raise MaterializerError(f"selected regular path is not a regular member: {path}")
                        if member.size > audit.MAX_CANDIDATE_FILE_BYTES:
                            raise MaterializerError(f"selected member exceeds candidate file bound: {path}")
                        source = archive.extractfile(member)
                        if source is None:
                            raise MaterializerError(f"selected member cannot be read: {path}")
                        relative = _candidate_relative(path)
                        target = output_dir / relative
                        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                        size, digest = _copy_member(source, target, member.size)
                        elf = library_by_path[path]
                        files.append(
                            {
                                "path": relative,
                                "package": record.package,
                                "packagePath": path,
                                "size": size,
                                "sha256": digest,
                                "soname": elf.get("soname"),
                                "needed": elf.get("needed", []),
                                "runpath": elf.get("runpath", []),
                                "rpath": elf.get("rpath", []),
                            }
                        )
            while counted.read(1024 * 1024):
                pass
        for link in component["symlinks"]:
            path = link["path"]
            if path not in selected_aliases:
                continue
            relative = _candidate_relative(path)
            target = output_dir / relative
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                raise MaterializerError(f"refusing to overwrite candidate alias: {target}")
            safe_target = link["target"]
            if safe_target.startswith("/") or "\x00" in safe_target or "\\" in safe_target:
                raise MaterializerError(f"unsafe selected alias target: {path}")
            target.symlink_to(safe_target)
            aliases.append(
                {
                    "path": relative,
                    "target": safe_target,
                    "package": record.package,
                    "packagePath": path,
                    "resolvedPath": _candidate_relative(_symlink_final_path(component, path)),
                }
            )
    return sorted(files, key=lambda item: item["path"]), sorted(aliases, key=lambda item: item["path"])


def _extract_vendor_json(
    records: list[LedgerPackage], package_dir: Path, output_dir: Path
) -> dict[str, Any]:
    """Copy the audited Mesa GLVND vendor file, if the pinned package has one."""
    expected_source = "data/data/com.termux/files/usr/share/glvnd/egl_vendor.d/50_mesa.json"
    record = next((item for item in records if item.package == "mesa"), None)
    if record is None:
        return {
            "status": "UNVERIFIED",
            "reason": "pinned Mesa package is not present in the fixed ledger",
            "libraryPath": None,
        }
    raw = audit.validate_pin(package_dir / record.basename, audit.PackagePin(record.bytes, record.sha256))
    data = audit.parse_ar(raw).get("data.tar.xz")
    if data is None:
        raise MaterializerError("Mesa package has no data.tar.xz")
    found: bytes | None = None
    with lzma.LZMAFile(io.BytesIO(data)) as compressed:
        counted = audit.CountingReader(compressed, audit.MAX_CANDIDATE_EXPANDED_BYTES)
        with tarfile.open(fileobj=counted, mode="r|") as archive:
            for member in archive:
                path = audit._validate_utf_path(member.name, field="tar path")
                if path != expected_source:
                    continue
                if not member.isfile() or member.size > 64 * 1024:
                    raise MaterializerError("Mesa vendor JSON is not a bounded regular file")
                stream = archive.extractfile(member)
                if stream is None:
                    raise MaterializerError("Mesa vendor JSON cannot be read")
                found = stream.read(member.size + 1)
                if len(found) != member.size:
                    raise MaterializerError("Mesa vendor JSON is truncated")
                break
        while counted.read(1024 * 1024):
            pass
    if found is None:
        return {
            "status": "UNVERIFIED",
            "reason": "pinned Mesa package has no audited GLVND vendor JSON",
            "libraryPath": None,
        }
    try:
        document = json.loads(found)
        library_path = document["ICD"]["library_path"]
    except (KeyError, TypeError, json.JSONDecodeError) as exc:
        raise MaterializerError("Mesa vendor JSON has no valid ICD.library_path") from exc
    if not isinstance(library_path, str):
        raise MaterializerError("Mesa vendor JSON library_path is not a string")
    try:
        audit._validate_utf_path(library_path, field="vendor library_path")
    except audit.AuditError as exc:
        raise MaterializerError(str(exc)) from exc
    relative = _candidate_asset_relative(expected_source)
    target = output_dir / relative
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    size, digest = _copy_member(io.BytesIO(found), target, len(found))
    return {
        "status": "PRESENT",
        "package": record.package,
        "packagePath": expected_source,
        "path": relative,
        "size": size,
        "sha256": digest,
        "fileFormatVersion": document.get("file_format_version"),
        "libraryPath": library_path,
        "note": "copied verbatim from the pinned cached Mesa package; no Android private path inferred",
    }


def _license_evidence(record: LedgerPackage, package_path: Path) -> dict[str, Any]:
    raw = audit.validate_pin(package_path, audit.PackagePin(record.bytes, record.sha256))
    members = audit.parse_ar(raw)
    control_name = next((name for name in members if name.startswith("control.tar.")), None)
    evidence: dict[str, Any] = {
        "package": record.package,
        "controlMember": control_name,
        "noticeOrLicensePaths": [],
        "status": "names_only_no_license_text_copied",
    }
    if control_name is None or not control_name.endswith(".xz"):
        evidence["status"] = "control_archive_not_inspected"
        return evidence
    with lzma.LZMAFile(io.BytesIO(members[control_name])) as compressed:
        counted = audit.CountingReader(compressed, 16 * 1024 * 1024)
        with tarfile.open(fileobj=counted, mode="r|") as archive:
            for member in archive:
                path = audit._validate_utf_path(member.name, field="control path")
                lower = path.lower()
                if any(token in lower for token in ("license", "licence", "copyright", "notice")):
                    evidence["noticeOrLicensePaths"].append(path)
        while counted.read(1024 * 1024):
            pass
    evidence["noticeOrLicensePaths"].sort()
    return evidence


def _write_manifest_exclusive(output_dir: Path, manifest: dict[str, Any]) -> Path:
    final = output_dir / "candidate-manifest.json"
    temporary = output_dir / ".candidate-manifest.tmp"
    if final.exists() or final.is_symlink() or temporary.exists() or temporary.is_symlink():
        raise MaterializerError("manifest output already exists")
    with temporary.open("x", encoding="utf-8") as stream:
        os.chmod(temporary, 0o600)
        json.dump(manifest, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.link(temporary, final)
    except OSError as exc:
        temporary.unlink(missing_ok=True)
        raise MaterializerError(f"exclusive manifest link failed: {exc}") from exc
    temporary.unlink()
    return final


def materialize_candidate(
    metadata_path: Path,
    package_dir: Path,
    output_dir: Path,
    *,
    roots: Iterable[str] | None = None,
    offline_only: bool = False,
) -> dict[str, Any]:
    requested_roots = None if roots is None else list(roots)
    metadata_raw = _read_existing(metadata_path, MAX_METADATA_BYTES)
    metadata, records = load_fixed_metadata(metadata_path)
    acquired = acquire_packages(records, package_dir, offline_only=offline_only)
    components = _audit_records(records, package_dir)
    closure = audit.build_closure(components, roots=requested_roots)
    if requested_roots is not None and closure["missingSonames"]:
        raise MaterializerError(f"requested closure has missing SONAMEs: {closure['missingSonames']}")
    selected = select_closure_files(components, closure, include_optional_driver=requested_roots is None)
    _ensure_output_dir(output_dir)
    try:
        files, aliases = _extract_selected(records, package_dir, components, selected, output_dir)
        expected_regular = len(selected["regular"])
        if len(files) != expected_regular:
            raise MaterializerError(f"materialized regular file count mismatch: {len(files)} != {expected_regular}")
        selected_bytes = sum(item["size"] for item in files)
        if selected_bytes > MAX_CANDIDATE_AGGREGATE_BYTES:
            raise MaterializerError(
                f"selected candidate exceeds 512 MiB aggregate bound: {selected_bytes}"
            )
        vendor_json = _extract_vendor_json(records, package_dir, output_dir) if requested_roots is not None else None
        licenses = [_license_evidence(record, package_dir / record.basename) for record in records]
        manifest = {
            "schema": "wowforever.virgl-private-candidate",
            "schemaVersion": 1,
            "offlineInstall": offline_only,
            "executedPayloads": False,
            "source": {
                "metadataPath": str(metadata_path),
                "metadataSha256": hashlib.sha256(metadata_raw).hexdigest(),
                "origin": ORIGIN,
                "compressedIndexSha256": metadata["compressedIndexSha256"],
                "recipeCommit": metadata.get("sourceRecipeCommit"),
                "ledgerDeclaredBytes": metadata["compressedPackageBytes"],
                "ledgerActualBytes": sum(record.bytes for record in records),
                "trustLimits": {
                    "aggregateLedgerBytes": MAX_LEDGER_BYTES,
                    "perDownloadTimeoutSeconds": MAX_DOWNLOAD_TIMEOUT,
                    "candidateExpandedBytes": audit.MAX_CANDIDATE_EXPANDED_BYTES,
                    "candidateRegularFileBytes": audit.MAX_CANDIDATE_FILE_BYTES,
                    "candidateAggregateBytes": MAX_CANDIDATE_AGGREGATE_BYTES,
                    "candidateTarMembers": audit.MAX_TAR_MEMBERS,
                },
            },
            "ledger": acquired,
            "dependencyComparison": {
                "status": "package-name-presence-only",
                "versionConstraintsEvaluated": False,
                "declaredDependsAreNotProofOfRuntimeResolution": True,
            },
            "components": components,
            "closure": closure,
            "materializedFiles": files,
            "approvedSymlinkAliases": aliases,
            "licenseEvidence": licenses,
            "privateLoaderResolution": {
                "status": "UNVERIFIED",
                "method": "preserve original DT_RUNPATH; no patchelf or loader execution",
            },
            "protocol": {"status": "UNVERIFIED"},
            "ready": False,
        }
        if requested_roots is not None:
            manifest.update(
                {
                    "rootSelection": requested_roots,
                    "selectedRegularFileCount": len(files),
                    "selectedRegularFileBytes": selected_bytes,
                    "selectedAliasCount": len(aliases),
                    "vendorJson": vendor_json,
                    "present": False,
                    "d3d": False,
                    "wow": False,
                }
            )
        manifest_path = _write_manifest_exclusive(output_dir, manifest)
        manifest["manifestPath"] = str(manifest_path)
        # The manifest path is intentionally printed by the CLI; embedding it
        # inside itself would make its hash self-referential.
        return manifest
    except Exception:
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--package-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    manifest = materialize_candidate(args.metadata, args.package_dir, args.output_dir)
    print(
        json.dumps(
            {
                "outputDir": str(args.output_dir),
                "manifest": str(args.output_dir / "candidate-manifest.json"),
                "downloadedBytes": sum(item["bytes"] for item in manifest["ledger"] if item["source"] == "downloaded_fixed_url"),
                "missingSonames": manifest["closure"]["missingSonames"],
                "ready": manifest["ready"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
