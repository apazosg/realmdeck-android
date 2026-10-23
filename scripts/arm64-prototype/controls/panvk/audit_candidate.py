#!/usr/bin/env python3
"""Acquire and statically audit the one approved PanVK Android candidate.

This is deliberately a host-only operation.  It resolves one asset from the
fixed official GitHub release, verifies the official asset metadata and the
independent ICD pin before parsing the ZIP, and invokes only the existing NDK
``llvm-readelf`` for ELF metadata.  It never loads, executes, installs, or
probes the candidate and it never touches an APK, HAL, or device.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO, Iterable


REPOSITORY = "zenithblue-oss/panvk-kbase-android"
SOURCE_COMMIT = "b4ee2e2490ffad0e6625ecbd78ef016bdf6cf5ce"
RELEASE_TAG = "g615-v11-csf-v0.1.0-beta.17"
RELEASE_API_URL = f"https://api.github.com/repos/{REPOSITORY}/releases/tags/{RELEASE_TAG}"
REPOSITORY_URL = f"https://github.com/{REPOSITORY}"
SOURCE_COMMIT_URL = f"{REPOSITORY_URL}/commit/{SOURCE_COMMIT}"
ARTIFACT_DIR = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-audit-beta17")
READELF = Path(
    "/home/adrian/android-sdk/ndk/24.0.8215888/"
    "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
)

EXPECTED_ZIP_BYTES = 7_466_046
EXPECTED_ZIP_SHA256 = "5ade77270e5c51daa26979c98e31abcc30be9c70cdc79589576b5fcedc28d632"
# The ZIP's Android ICD basename is resolved by the exact approved archive;
# it is intentionally not confused with the separately published glibc ELF.
ICD_BASENAME = "libvulkan_panfrost.so"
EXPECTED_ICD_BYTES = 31_142_504
EXPECTED_ICD_SHA256 = "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"

MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
MAX_API_BYTES = 2 * 1024 * 1024
MAX_ARCHIVE_EXPANDED_BYTES = 128 * 1024 * 1024
MAX_ICD_BYTES = 32 * 1024 * 1024
MAX_ARCHIVE_FILES = 128
MAX_METADATA_FILE_BYTES = 256 * 1024
MAX_METADATA_TOTAL_BYTES = 1024 * 1024
DOWNLOAD_TIMEOUT_SECONDS = 90
READELF_TIMEOUT_SECONDS = 30
DOWNLOAD_CHUNK_BYTES = 64 * 1024

ASSET_REDIRECT_HOSTS = frozenset(
    {
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
    }
)
ANDROID_SYSTEM_SONAMES = frozenset(
    {
        "libandroid.so",
        "libc.so",
        "libdl.so",
        "libEGL.so",
        "libGLESv2.so",
        "liblog.so",
        "libm.so",
        "libnativewindow.so",
        "libsync.so",
        "libvulkan.so",
        "libz.so",
    }
)
TEXT_SUFFIXES = frozenset({".json", ".md", ".rst", ".txt", ".license", ".licence"})
TEXT_MARKERS = ("license", "licence", "copyright", "notice", "readme")


class AuditError(ValueError):
    """A fixed-provenance, malformed, unsafe, or overlarge input."""


@dataclass(frozen=True)
class Asset:
    name: str
    size: int
    sha256: str
    api_url: str
    browser_download_url: str
    asset_id: int
    content_type: str


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _read_bounded(stream: BinaryIO, limit: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = stream.read(min(DOWNLOAD_CHUNK_BYTES, limit - total + 1))
        if not chunk:
            break
        total += len(chunk)
        if total > limit:
            raise AuditError(f"response exceeds bound: {total} > {limit}")
        chunks.append(chunk)
    return b"".join(chunks)


def _validate_https_url(url: str, *, hosts: Iterable[str], expected_path: str | None = None, allow_query: bool = False) -> None:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.username or parsed.password or (parsed.query and not allow_query) or parsed.fragment:
        raise AuditError(f"unsafe HTTPS URL: {url}")
    if parsed.hostname not in set(hosts) or parsed.port not in (None, 443):
        raise AuditError(f"unapproved HTTPS authority: {url}")
    if expected_path is not None and parsed.path != expected_path:
        raise AuditError(f"unexpected URL path: {url}")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        raise AuditError(f"redirect refused for fixed metadata URL: {newurl}")


class _ApprovedAssetRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self) -> None:
        super().__init__()
        self.redirects: list[str] = []

    def redirect_request(self, request: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> Any:
        _validate_https_url(newurl, hosts=ASSET_REDIRECT_HOSTS, allow_query=True)
        self.redirects.append(newurl)
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def _response_status(response: Any) -> int:
    return int(getattr(response, "status", response.getcode()))


def fetch_release_metadata(opener: Any | None = None) -> tuple[bytes, dict[str, Any]]:
    """Fetch only the fixed official release API URL, with a small cap."""
    _validate_https_url(RELEASE_API_URL, hosts={"api.github.com"}, expected_path=urllib.parse.urlsplit(RELEASE_API_URL).path)
    if opener is None:
        opener = urllib.request.build_opener(_NoRedirect)
    request = urllib.request.Request(RELEASE_API_URL, headers={"Accept": "application/vnd.github+json", "User-Agent": "wowforever-panvk-audit/1"})
    try:
        with opener.open(request, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response:
            if _response_status(response) != 200 or response.geturl() != RELEASE_API_URL:
                raise AuditError("fixed release API did not return an ordinary 200")
            raw = _read_bounded(response, MAX_API_BYTES)
    except (OSError, urllib.error.URLError, ValueError) as exc:
        if isinstance(exc, AuditError):
            raise
        raise AuditError(f"release metadata request failed: {exc}") from exc
    try:
        metadata = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise AuditError("official release metadata is not JSON") from exc
    if not isinstance(metadata, dict):
        raise AuditError("official release metadata is not an object")
    return raw, metadata


def _asset_url_path(name: str) -> str:
    return f"/{REPOSITORY}/releases/download/{RELEASE_TAG}/{urllib.parse.quote(name, safe='.-_')}"


def resolve_approved_asset(metadata: dict[str, Any]) -> Asset:
    """Select the exact approved Android ZIP, never by a caller-supplied URL."""
    if metadata.get("tag_name") != RELEASE_TAG:
        raise AuditError("release tag mismatch")
    if metadata.get("html_url") != f"{REPOSITORY_URL}/releases/tag/{RELEASE_TAG}":
        raise AuditError("release HTML provenance mismatch")
    assets = metadata.get("assets")
    if not isinstance(assets, list):
        raise AuditError("release assets are missing")
    matches: list[Asset] = []
    for item in assets:
        if not isinstance(item, dict):
            raise AuditError("malformed release asset stanza")
        digest = item.get("digest")
        if not isinstance(digest, str) or not digest.startswith("sha256:"):
            continue
        try:
            size = int(item.get("size"))
            asset_id = int(item.get("id"))
        except (TypeError, ValueError) as exc:
            raise AuditError("malformed release asset size/id") from exc
        name = item.get("name")
        api_url = item.get("url")
        browser_url = item.get("browser_download_url")
        if not all(isinstance(value, str) for value in (name, api_url, browser_url)):
            raise AuditError("malformed release asset URL/name")
        if size != EXPECTED_ZIP_BYTES or digest[7:].lower() != EXPECTED_ZIP_SHA256 or not name.endswith(".adpkg.zip"):
            continue
        if item.get("content_type") != "application/zip" or item.get("state") != "uploaded":
            raise AuditError("approved ZIP asset is not an uploaded application/zip")
        _validate_https_url(api_url, hosts={"api.github.com"}, expected_path=f"/repos/{REPOSITORY}/releases/assets/{asset_id}")
        expected_path = _asset_url_path(name)
        _validate_https_url(browser_url, hosts={"github.com"}, expected_path=expected_path)
        if urllib.parse.unquote(PurePosixPath(urllib.parse.urlsplit(browser_url).path).name) != name:
            raise AuditError("release asset URL basename mismatch")
        matches.append(Asset(name, size, EXPECTED_ZIP_SHA256, api_url, browser_url, asset_id, item["content_type"]))
    if len(matches) != 1:
        raise AuditError(f"expected one exact approved Android ZIP, found {len(matches)}")
    return matches[0]


def _validate_regular_external_file(path: Path, *, limit: int) -> None:
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise AuditError(f"not a regular non-symlink file: {path}")
    if path.stat().st_size > limit:
        raise AuditError(f"file exceeds bound: {path}")


def _validate_download(path: Path, asset: Asset) -> dict[str, Any]:
    _validate_regular_external_file(path, limit=MAX_DOWNLOAD_BYTES)
    size = path.stat().st_size
    digest = _sha256(path.read_bytes())
    if size != asset.size or digest != asset.sha256:
        raise AuditError(f"download pin mismatch: size={size}, sha256={digest}")
    return {"path": str(path), "name": asset.name, "bytes": size, "sha256": digest}


def download_approved_asset(asset: Asset, target: Path, *, opener: Any | None = None) -> dict[str, Any]:
    """Stream the one resolved GitHub asset, enforcing length/hash before ZIP parsing."""
    if target.exists() or target.is_symlink():
        raise AuditError(f"refusing to overwrite download: {target}")
    if target.name != asset.name:
        raise AuditError("download basename is not the resolved official asset basename")
    _validate_https_url(asset.browser_download_url, hosts={"github.com"}, expected_path=_asset_url_path(asset.name))
    redirect_handler = _ApprovedAssetRedirect()
    if opener is None:
        opener = urllib.request.build_opener(redirect_handler)
    request = urllib.request.Request(asset.browser_download_url, headers={"Accept": "application/octet-stream", "User-Agent": "wowforever-panvk-audit/1"})
    created = False
    try:
        with opener.open(request, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response:
            final_url = response.geturl()
            _validate_https_url(final_url, hosts=ASSET_REDIRECT_HOSTS, allow_query=True)
            if _response_status(response) != 200:
                raise AuditError(f"asset returned HTTP {_response_status(response)}")
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) != asset.size:
                raise AuditError(f"asset Content-Length mismatch: {declared} != {asset.size}")
            with target.open("xb") as output:
                created = True
                digest = hashlib.sha256()
                total = 0
                while True:
                    chunk = response.read(DOWNLOAD_CHUNK_BYTES)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > asset.size or total > MAX_DOWNLOAD_BYTES:
                        raise AuditError("asset stream exceeds fixed length bound")
                    output.write(chunk)
                    digest.update(chunk)
                output.flush()
                os.fsync(output.fileno())
            if total != asset.size or digest.hexdigest() != asset.sha256:
                raise AuditError(f"asset download pin mismatch: {total} bytes, {digest.hexdigest()}")
            return {
                "source": "downloaded_official_github_asset",
                "requestedUrl": asset.browser_download_url,
                "finalUrl": final_url,
                "redirects": list(getattr(redirect_handler, "redirects", [])),
                "transport": {"scheme": "https", "initialAuthority": "github.com", "finalAuthority": urllib.parse.urlsplit(final_url).hostname, "authorityAllowlist": sorted(ASSET_REDIRECT_HOSTS)},
                **_validate_download(target, asset),
            }
    except Exception:
        if created:
            target.unlink(missing_ok=True)
        raise


def _validate_zip_path(value: str, *, directory: bool = False) -> str:
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as exc:
        raise AuditError("ZIP path is not valid UTF-8") from exc
    if "\x00" in value or "\\" in value or unicodedata.normalize("NFC", value) != value:
        raise AuditError(f"unsafe/non-normalized ZIP path: {value!r}")
    if directory:
        if not value.endswith("/"):
            raise AuditError(f"directory ZIP path lacks slash: {value!r}")
        value = value[:-1]
    if not value or value.startswith("/"):
        raise AuditError(f"unsafe ZIP path: {value!r}")
    parts = value.split("/")
    if any(not part or part in {".", ".."} for part in parts):
        raise AuditError(f"unsafe ZIP path: {value!r}")
    return value


def _zip_kind(info: zipfile.ZipInfo) -> tuple[str, int]:
    mode = (info.external_attr >> 16) & 0xFFFF
    file_type = stat.S_IFMT(mode)
    if info.is_dir():
        if file_type not in (0, stat.S_IFDIR):
            raise AuditError(f"directory has special ZIP mode: {info.filename}")
        return "directory", mode
    if info.flag_bits & 0x1:
        raise AuditError(f"encrypted ZIP member is not accepted: {info.filename}")
    if file_type == stat.S_IFLNK or file_type not in (0, stat.S_IFREG):
        raise AuditError(f"special/symlink ZIP member is not accepted: {info.filename}")
    return "regular", mode


def _member_bytes(archive: zipfile.ZipFile, info: zipfile.ZipInfo, limit: int) -> bytes:
    if info.file_size > limit:
        raise AuditError(f"ZIP member exceeds bound: {info.filename}")
    data = bytearray()
    with archive.open(info, "r") as stream:
        while True:
            chunk = stream.read(min(DOWNLOAD_CHUNK_BYTES, limit - len(data) + 1))
            if not chunk:
                break
            data.extend(chunk)
            if len(data) > limit:
                raise AuditError(f"ZIP member expanded beyond bound: {info.filename}")
    if len(data) != info.file_size:
        raise AuditError(f"ZIP member length mismatch: {info.filename}")
    return bytes(data)


def _drain_member(archive: zipfile.ZipFile, info: zipfile.ZipInfo, limit: int) -> None:
    if info.file_size > limit:
        raise AuditError(f"ZIP member exceeds bound: {info.filename}")
    total = 0
    with archive.open(info, "r") as stream:
        while True:
            chunk = stream.read(DOWNLOAD_CHUNK_BYTES)
            if not chunk:
                break
            total += len(chunk)
            if total > limit:
                raise AuditError(f"ZIP member expanded beyond bound: {info.filename}")
    if total != info.file_size:
        raise AuditError(f"ZIP member length mismatch: {info.filename}")


def _is_metadata_path(path: str) -> bool:
    lower = path.lower()
    name = PurePosixPath(path).name.lower()
    return name.endswith(tuple(TEXT_SUFFIXES)) or any(marker in name for marker in TEXT_MARKERS)


def _parse_symbol_table(text: str) -> tuple[list[str], list[str]]:
    imports: set[str] = set()
    exports: set[str] = set()
    for line in text.splitlines():
        match = re.match(r"^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+\S+\s+\S+\s+\S+\s+(\S+)\s+(.+?)\s*$", line)
        if not match:
            continue
        ndx, name = match.groups()
        if not name:
            continue
        base = name.split("@", 1)[0]
        (imports if ndx == "UND" else exports).add(base)
    return sorted(imports), sorted(exports)


def parse_readelf_output(text: str, *, byte_count: int, sha256: str) -> dict[str, Any]:
    """Parse readelf text only; no ELF bytes are mapped or loaded here."""
    def field(pattern: str) -> str | None:
        match = re.search(pattern, text, re.MULTILINE)
        return match.group(1).strip() if match else None

    elf_class = field(r"^\s*Class:\s*(.+)$")
    elf_data = field(r"^\s*Data:\s*(.+)$")
    elf_type = field(r"^\s*Type:\s*(.+)$")
    machine = field(r"^\s*Machine:\s*(.+)$")
    if elf_class is None or "ELF64" not in elf_class or elf_data is None or "little endian" not in elf_data.lower():
        raise AuditError("readelf did not prove ELF64 little-endian")
    if elf_type is None or not re.search(r"\bDYN\b", elf_type):
        raise AuditError(f"readelf did not prove ET_DYN: {elf_type!r}")
    if machine is None or "AArch64" not in machine:
        raise AuditError(f"readelf did not prove AArch64: {machine!r}")

    needed = sorted(set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", text)))
    soname_match = re.search(r"\(SONAME\).*?\[([^\]]+)\]", text)
    runpaths = sorted(set(re.findall(r"\(RUNPATH\).*?\[([^\]]*)\]", text)))
    rpaths = sorted(set(re.findall(r"\(RPATH\).*?\[([^\]]*)\]", text)))
    interp = sorted(set(re.findall(r"(?:Requesting program interpreter|INTERP[^\n]*?Interpreter):\s*([^\s]+)", text)))
    # GNU-style llvm-readelf prints the PT_INTERP row and, on another line,
    # "[Requesting program interpreter: ...]".  The row itself is sufficient
    # to report presence when the bracketed value is absent.
    has_interp = bool(re.search(r"^\s*INTERP\s", text, re.MULTILINE)) or bool(interp)
    alignments: list[int] = []
    for line in text.splitlines():
        if re.match(r"^\s*LOAD\s", line):
            tokens = line.split()
            if tokens and re.fullmatch(r"0x[0-9a-fA-F]+", tokens[-1]):
                alignments.append(int(tokens[-1], 16))
    imports, exports = _parse_symbol_table(text)
    api_matches = sorted(set(re.findall(r"Android API:\s*(\d+)", text)))
    if not api_matches:
        api_matches = sorted(set(re.findall(r"Android\s+\([^)]*\)\s+(\d+)\b", text)))
    return {
        "bytes": byte_count,
        "sha256": sha256,
        "elfClass": "ELF64",
        "elfData": "little-endian",
        "elfType": "ET_DYN",
        "elfMachine": "AArch64",
        "archMetadata": {"machine": machine, "flags": field(r"^\s*Flags:\s*(.+)$")},
        "soname": soname_match.group(1) if soname_match else None,
        "ptInterp": {"present": has_interp, "paths": interp},
        "needed": needed,
        "runpath": runpaths,
        "rpath": rpaths,
        "imports": imports,
        "exports": exports,
        "requiredExports": {
            "vkGetInstanceProcAddr": "vkGetInstanceProcAddr" in exports,
            "vk_icdGetInstanceProcAddr": "vk_icdGetInstanceProcAddr" in exports,
        },
        "minAPI": {"values": [int(value) for value in api_matches], "status": "PRESENT" if api_matches else "UNVERIFIED"},
        "pageAlignment": {"loadAlignments": alignments, "status": "PRESENT" if alignments else "UNVERIFIED"},
    }


def inspect_elf(path: Path) -> dict[str, Any]:
    _validate_regular_external_file(path, limit=MAX_ICD_BYTES)
    raw = path.read_bytes()
    if len(raw) != EXPECTED_ICD_BYTES or _sha256(raw) != EXPECTED_ICD_SHA256:
        raise AuditError("extracted ICD does not match its independent size/SHA pin")
    resolved_readelf = READELF.resolve(strict=False)
    if not READELF.is_file() or not resolved_readelf.is_file() or resolved_readelf.parent != READELF.parent:
        raise AuditError(f"required existing llvm-readelf is unavailable: {READELF}")
    command = [str(READELF), "--file-header", "--program-headers", "--dynamic", "--dyn-syms", "--notes", "--wide", str(path)]
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=READELF_TIMEOUT_SECONDS, check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        raise AuditError(f"llvm-readelf failed to start: {exc}") from exc
    output = result.stdout.decode("utf-8", "replace")
    if result.returncode != 0:
        raise AuditError(f"llvm-readelf returned {result.returncode}: {result.stderr.decode('utf-8', 'replace')[:500]}")
    parsed = parse_readelf_output(output, byte_count=len(raw), sha256=_sha256(raw))
    parsed["tool"] = str(READELF)
    parsed["command"] = command
    return parsed


def _dependency_report(elf: dict[str, Any]) -> dict[str, Any]:
    candidate_sonames = [elf["soname"]] if elf.get("soname") else []
    resolved: list[dict[str, str]] = []
    missing: list[str] = []
    for needed in elf.get("needed", []):
        if needed in ANDROID_SYSTEM_SONAMES:
            resolved.append({"needed": needed, "provider": "explicit_android_system_allowlist"})
        elif needed in candidate_sonames:
            resolved.append({"needed": needed, "provider": "audited_candidate_only"})
        else:
            missing.append(needed)
    return {
        "allowlistedAndroidSystemSonames": sorted(ANDROID_SYSTEM_SONAMES),
        "candidateSonames": candidate_sonames,
        "resolved": resolved,
        "missing": sorted(set(missing)),
        "status": "UNVERIFIED",
        "note": "No linker or loader was run; the allowlist and this ICD are not runtime-resolution proof.",
    }


def audit_zip(path: Path, asset: Asset) -> tuple[dict[str, Any], bytes, list[tuple[str, bytes]]]:
    pin = _validate_download(path, asset)
    members: list[dict[str, Any]] = []
    selected_icd: tuple[str, bytes] | None = None
    metadata_files: list[tuple[str, bytes]] = []
    seen: set[str] = set()
    expanded = 0
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            if len(infos) > MAX_ARCHIVE_FILES:
                raise AuditError(f"ZIP file count exceeds bound: {len(infos)}")
            for info in infos:
                directory = info.is_dir()
                canonical = _validate_zip_path(info.filename, directory=directory)
                if canonical in seen:
                    raise AuditError(f"duplicate ZIP path: {canonical}")
                seen.add(canonical)
                kind, mode = _zip_kind(info)
                if kind == "directory":
                    if info.file_size != 0:
                        raise AuditError(f"directory has nonzero size: {canonical}")
                else:
                    if info.file_size > MAX_ARCHIVE_EXPANDED_BYTES:
                        raise AuditError(f"ZIP member exceeds aggregate bound: {canonical}")
                    expanded += info.file_size
                    if expanded > MAX_ARCHIVE_EXPANDED_BYTES:
                        raise AuditError("ZIP aggregate expanded size exceeds 128 MiB")
                    _drain_member(archive, info, MAX_ARCHIVE_EXPANDED_BYTES)
                member = {"path": canonical, "kind": kind, "bytes": info.file_size, "compressedBytes": info.compress_size, "mode": mode, "extracted": False}
                members.append(member)
                if kind != "regular":
                    continue
                if PurePosixPath(canonical).name == ICD_BASENAME:
                    if selected_icd is not None:
                        raise AuditError(f"multiple ICD basename matches: {ICD_BASENAME}")
                    if info.file_size != EXPECTED_ICD_BYTES or info.file_size > MAX_ICD_BYTES:
                        raise AuditError("ICD member size does not match independent pin")
                    data = _member_bytes(archive, info, MAX_ICD_BYTES)
                    if _sha256(data) != EXPECTED_ICD_SHA256:
                        raise AuditError("ICD member SHA-256 does not match independent pin")
                    selected_icd = (canonical, data)
                elif info.file_size <= MAX_METADATA_FILE_BYTES and _is_metadata_path(canonical):
                    data = _member_bytes(archive, info, MAX_METADATA_FILE_BYTES)
                    if not data or b"\x00" in data:
                        continue
                    try:
                        text = data.decode("utf-8", "strict")
                    except UnicodeDecodeError:
                        continue
                    if not text.strip() or stat.S_IXUSR & mode:
                        continue
                    if sum(len(item[1]) for item in metadata_files) + len(data) <= MAX_METADATA_TOTAL_BYTES:
                        metadata_files.append((canonical, data))
            if selected_icd is None:
                raise AuditError(f"ZIP has no independently pinned {ICD_BASENAME}")
    except (zipfile.BadZipFile, RuntimeError, OSError) as exc:
        if isinstance(exc, AuditError):
            raise
        raise AuditError(f"invalid ZIP: {exc}") from exc
    metadata_paths = [path_name for path_name, _data in metadata_files]
    report = {
        "asset": pin,
        "memberCount": len(members),
        "expandedBytes": expanded,
        "members": sorted(members, key=lambda item: item["path"]),
        "icd": {"packagePath": selected_icd[0], "bytes": len(selected_icd[1]), "sha256": _sha256(selected_icd[1]), "independentlyPinned": True},
        "metadataPaths": sorted(metadata_paths),
        "bounds": {"archiveBytes": MAX_DOWNLOAD_BYTES, "expandedBytes": MAX_ARCHIVE_EXPANDED_BYTES, "icdBytes": MAX_ICD_BYTES, "files": MAX_ARCHIVE_FILES, "metadataFileBytes": MAX_METADATA_FILE_BYTES, "metadataTotalBytes": MAX_METADATA_TOTAL_BYTES},
    }
    return report, selected_icd[1], metadata_files


def _outside_repo(path: Path) -> None:
    repo = Path(__file__).resolve().parents[4]
    try:
        path.resolve(strict=False).relative_to(repo)
    except ValueError:
        return
    raise AuditError("artifact output must be outside the repository")


def prepare_artifact_dir(path: Path = ARTIFACT_DIR) -> None:
    _outside_repo(path)
    if path.is_symlink() or (path.exists() and not path.is_dir()):
        raise AuditError(f"artifact directory is not a directory: {path}")
    if path.exists():
        allowed_partial = {"release-metadata.json", "icd", "metadata"}
        unexpected = sorted(item.name for item in path.iterdir() if item.name not in allowed_partial and not item.name.endswith(".adpkg.zip"))
        if unexpected:
            raise AuditError(f"refusing non-empty existing artifact directory: {unexpected}")
        return
    if not path.parent.is_dir() or path.parent.is_symlink():
        raise AuditError(f"artifact parent must be an existing non-symlink directory: {path.parent}")
    path.mkdir(mode=0o700)


def _write_exclusive(path: Path, data: bytes) -> None:
    if path.exists() or path.is_symlink():
        raise AuditError(f"refusing to overwrite artifact: {path}")
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(path, 0o600)


def acquire_and_audit(output_dir: Path = ARTIFACT_DIR) -> dict[str, Any]:
    prepare_artifact_dir(output_dir)
    metadata_raw, metadata = fetch_release_metadata()
    asset = resolve_approved_asset(metadata)
    metadata_path = output_dir / "release-metadata.json"
    if metadata_path.exists() or metadata_path.is_symlink():
        _validate_regular_external_file(metadata_path, limit=MAX_API_BYTES)
        if metadata_path.read_bytes() != metadata_raw:
            raise AuditError("existing partial release metadata differs; refusing overwrite")
    else:
        _write_exclusive(metadata_path, metadata_raw)
    zip_path = output_dir / asset.name
    existing_zip_files = [item for item in output_dir.iterdir() if item.name.endswith(".adpkg.zip")]
    if any(item.name != asset.name for item in existing_zip_files) or len(existing_zip_files) > 1:
        raise AuditError("existing artifact directory contains an unexpected ZIP")
    if zip_path.exists() or zip_path.is_symlink():
        transport = {
            "source": "existing_verified_download_from_fixed_official_github_asset",
            "requestedUrl": asset.browser_download_url,
            "finalUrl": None,
            "redirects": [],
            "transport": {"scheme": "https", "initialAuthority": "github.com", "finalAuthority": "release-assets.githubusercontent.com", "authorityAllowlist": sorted(ASSET_REDIRECT_HOSTS), "note": "The fixed downloader created this ZIP before the first static audit stopped; redirect URL was not persisted."},
            **_validate_download(zip_path, asset),
        }
    else:
        transport = download_approved_asset(asset, zip_path)
    archive_report, icd_bytes, metadata_files = audit_zip(zip_path, asset)
    icd_path = output_dir / "icd" / ICD_BASENAME
    if icd_path.parent.exists() and (icd_path.parent.is_symlink() or not icd_path.parent.is_dir()):
        raise AuditError("existing ICD output directory is unsafe")
    icd_path.parent.mkdir(mode=0o700, exist_ok=True)
    if icd_path.exists() or icd_path.is_symlink():
        _validate_regular_external_file(icd_path, limit=MAX_ICD_BYTES)
        if icd_path.read_bytes() != icd_bytes:
            raise AuditError("existing ICD output differs; refusing overwrite")
    else:
        _write_exclusive(icd_path, icd_bytes)
    elf = inspect_elf(icd_path)
    extracted_metadata: list[dict[str, Any]] = []
    metadata_dir = output_dir / "metadata"
    if metadata_files:
        metadata_dir.mkdir(mode=0o700)
    for index, (package_path, data) in enumerate(sorted(metadata_files)):
        target = metadata_dir / f"{index:03d}-{PurePosixPath(package_path).name}"
        if target.exists() or target.is_symlink():
            _validate_regular_external_file(target, limit=MAX_METADATA_FILE_BYTES)
            if target.read_bytes() != data:
                raise AuditError("existing metadata output differs; refusing overwrite")
        else:
            _write_exclusive(target, data)
        extracted_metadata.append({"packagePath": package_path, "path": str(target), "bytes": len(data), "sha256": _sha256(data)})
    dependencies = _dependency_report(elf)
    manifest: dict[str, Any] = {
        "schema": "wowforever.panvk-candidate-static-audit",
        "schemaVersion": 1,
        "hostOnly": True,
        "driverExecution": False,
        "candidateExecution": False,
        "source": {
            "repository": REPOSITORY_URL,
            "commit": SOURCE_COMMIT,
            "commitUrl": SOURCE_COMMIT_URL,
            "releaseTag": RELEASE_TAG,
            "releaseApiUrl": RELEASE_API_URL,
            "releaseHtmlUrl": metadata.get("html_url"),
            "releaseId": metadata.get("id"),
            "releasePublishedAt": metadata.get("published_at"),
            "releaseTargetCommitish": metadata.get("target_commitish"),
            "asset": {"id": asset.asset_id, "name": asset.name, "apiUrl": asset.api_url, "browserDownloadUrl": asset.browser_download_url, "contentType": asset.content_type, "officialBytes": asset.size, "officialSha256": asset.sha256},
            "releaseMetadata": {"path": str(metadata_path), "bytes": len(metadata_raw), "sha256": _sha256(metadata_raw), "authority": "official GitHub HTTPS release API"},
            "transport": transport,
            "trustBoundary": "official API asset size/digest plus streamed local digest; source commit is pinned but not fetched in this bounded audit",
        },
        "archive": archive_report,
        "icd": {"path": str(icd_path), "packagePath": archive_report["icd"]["packagePath"], "bytes": len(icd_bytes), "sha256": _sha256(icd_bytes), "extractedOnly": True},
        "extractedMetadata": extracted_metadata,
        "elf": elf,
        "dependencies": dependencies,
        "publishedScope": {"binarySupportMetadata": ["v10", "v11", "v12", "v13", "v14"], "testedGpu": "Mali-G615 (published release evidence)", "targetGpu": "Mali-G610", "g610Validation": "UNVERIFIED; published binary support does not validate G610"},
        "licenseAndProvenance": {"status": "PACKAGE_TEXT_ONLY" if extracted_metadata else "NOT_FOUND_IN_SELECTED_TEXT", "archiveTextPaths": archive_report["metadataPaths"], "issues": ["license text, if present, is package evidence rather than independent legal provenance", "release validation is reported for G615; G610, kernel ABI, and CSF compatibility remain unverified", "source commit pin is recorded but source was not acquired under the one-ZIP budget"]},
        "readiness": {"ready": False, "loader": "UNVERIFIED", "kernel": "UNVERIFIED", "gpuWorkload": "UNVERIFIED", "wsi": "UNVERIFIED", "reason": "static host audit only; no linker, driver, GPU, workload, WSI, APK, or vendor HAL operation"},
        "baselineSafety": {"apkTouched": False, "vendorHalTouched": False, "deviceTouched": False, "helpersExecuted": False},
        "risks": ["v10-v14 binary support metadata is not a G610 qualification", "DT_NEEDED entries outside the explicit Android allowlist are missing and no dependencies were fetched", "PT_INTERP, minAPI, page alignment, imports, and exports are metadata observations, not loader proof"],
    }
    manifest_path = output_dir / "candidate-manifest.json"
    _write_exclusive(manifest_path, (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode())
    manifest["manifestPath"] = str(manifest_path)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)
    report = acquire_and_audit()
    print(json.dumps({"outputDir": str(ARTIFACT_DIR), "manifest": str(ARTIFACT_DIR / "candidate-manifest.json"), "asset": report["source"]["asset"]["name"], "icdSha256": report["icd"]["sha256"], "missingDependencies": report["dependencies"]["missing"], "ready": report["readiness"]["ready"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
