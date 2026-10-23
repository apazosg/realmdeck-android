#!/usr/bin/env python3
"""Materialize and statically audit the community Windows ARM64 runtime.

This tool deliberately produces artifacts outside the repository.  It does not
execute Wine, a PE, an ELF, or an Android binary.  A WCP is a zstd-compressed
POSIX tar archive; extraction is streamed through the system ``zstd`` command
and every tar member is checked before it is written.

The output is an isolated runtime directory, not a FrontMage/FEX prefix.  The
caller must provide the client and an Android-side launcher later.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import BinaryIO, Iterable


BUNDLE_SHA256 = "70f1f9924d3649fed811634024eb5b40544485aa11b43d0f28959e95211fa3e2"
PROTON_WCP = "components/proton-11.0-90624-arm64ec.wcp"
DXVK_WCP = "components/dxvk-2.4.1-wow-aarch64-test.wcp"
MAX_MEMBER_BYTES = 512 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
EXPECTED_PROTON = "proton-11.0-90624-arm64ec"
EXPECTED_DXVK = "2.4.1-wow-aarch64-test"


class RecipeError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_member_name(name: str) -> PurePosixPath:
    """Return a safe relative POSIX path or reject traversal/absolute paths."""
    if not name or "\\" in name:
        raise RecipeError(f"unsafe tar member path: {name!r}")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise RecipeError(f"unsafe tar member path: {name!r}")
    return path


def validate_zip(bundle: Path) -> dict[str, object]:
    if not bundle.is_file():
        raise RecipeError(f"bundle is not a regular file: {bundle}")
    actual = sha256(bundle)
    if actual != BUNDLE_SHA256:
        raise RecipeError(f"bundle SHA-256 mismatch: {actual} != {BUNDLE_SHA256}")
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        for name in names:
            safe_member_name(name)
        for required in (PROTON_WCP, DXVK_WCP):
            if required not in names:
                raise RecipeError(f"required WCP missing: {required}")
        return {
            "sha256": actual,
            "size_bytes": bundle.stat().st_size,
            "entries": len(names),
            "required_entries": [PROTON_WCP, DXVK_WCP],
            "member_compressed_bytes": {
                required: archive.getinfo(required).compress_size for required in (PROTON_WCP, DXVK_WCP)
            },
        }


def stream_wcp_member(archive: zipfile.ZipFile, member: str, scratch_dir: Path) -> Iterable[bytes]:
    """Yield decompressed WCP bytes without making a second archive copy."""
    if shutil.which("zstd") is None:
        raise RecipeError("zstd is required to read WCP archives")
    scratch_dir.mkdir(parents=True, exist_ok=True)
    with archive.open(member) as source, tempfile.NamedTemporaryFile(
        prefix=".wcp-", suffix=".zst", dir=scratch_dir, delete=False
    ) as compressed:
        compressed_path = Path(compressed.name)
        shutil.copyfileobj(source, compressed, length=1024 * 1024)
    try:
        with compressed_path.open("rb") as compressed_input:
            process = subprocess.Popen(
                ["zstd", "-dc", "--"], stdin=compressed_input, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            assert process.stdout is not None
            try:
                while block := process.stdout.read(1024 * 1024):
                    yield block
                stderr = process.stderr.read().decode("utf-8", errors="replace").strip()
                code = process.wait()
                if code:
                    raise RecipeError(f"zstd failed for {member}: {stderr}")
            except BaseException:
                process.kill()
                process.wait()
                raise
    finally:
        compressed_path.unlink(missing_ok=True)


class ChunkReader:
    """A file-like reader over chunks, suitable for tarfile streaming mode."""

    def __init__(self, chunks: Iterable[bytes]):
        self._iterator = iter(chunks)
        self._buffer = bytearray()
        self._done = False

    def read(self, size: int = -1) -> bytes:
        if size == 0:
            return b""
        while not self._done and (size < 0 or len(self._buffer) < size):
            try:
                self._buffer.extend(next(self._iterator))
            except StopIteration:
                self._done = True
        if size < 0:
            result = bytes(self._buffer)
            self._buffer.clear()
            return result
        result = bytes(self._buffer[:size])
        del self._buffer[:size]
        return result


def write_member(root: Path, member: tarfile.TarInfo, stream: BinaryIO) -> int:
    relative = safe_member_name(member.name)
    destination = root.joinpath(*relative.parts)
    root_resolved = root.resolve()
    if not destination.resolve(strict=False).is_relative_to(root_resolved):
        raise RecipeError(f"tar member escapes output: {member.name!r}")
    if member.issym() or member.islnk():
        link = PurePosixPath(member.linkname)
        if not member.linkname or link.is_absolute() or "\\" in member.linkname:
            raise RecipeError(f"unsafe tar link target: {member.name!r} -> {member.linkname!r}")
        if member.issym():
            link_destination = destination.parent / member.linkname
        else:
            link_destination = root.joinpath(*link.parts)
        if not link_destination.resolve(strict=False).is_relative_to(root_resolved):
            raise RecipeError(f"tar link escapes output: {member.name!r} -> {member.linkname!r}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.symlink_to(member.linkname) if member.issym() else os.link(link_destination, destination)
        return 0
    if member.isdev() or member.isfifo():
        raise RecipeError(f"unsupported special tar member: {member.name!r}")
    if member.size > MAX_MEMBER_BYTES:
        raise RecipeError(f"tar member is too large: {member.name!r}")
    if member.isdir():
        destination.mkdir(parents=True, exist_ok=True)
        return 0
    if not member.isfile():
        raise RecipeError(f"unsupported tar member type: {member.name!r}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    with destination.open("xb") as output:
        while written < member.size:
            block = stream.read(min(1024 * 1024, member.size - written))
            if not block:
                raise RecipeError(f"truncated tar member: {member.name!r}")
            output.write(block)
            written += len(block)
    os.chmod(destination, stat.S_IMODE(member.mode) | stat.S_IRUSR | stat.S_IWUSR)
    return written


def extract_wcp(archive: zipfile.ZipFile, member: str, destination: Path) -> dict[str, object]:
    if destination.exists():
        raise RecipeError(f"refusing to overwrite existing runtime directory: {destination}")
    destination.mkdir(parents=True)
    total = 0
    names: list[str] = []
    reader = ChunkReader(stream_wcp_member(archive, member, destination.parent))
    try:
        with tarfile.open(fileobj=reader, mode="r|", format=tarfile.PAX_FORMAT) as tar:
            for item in tar:
                safe_member_name(item.name)
                total += item.size
                if total > MAX_TOTAL_BYTES:
                    raise RecipeError(f"expanded WCP exceeds safety limit: {member}")
                names.append(item.name)
                source = tar.extractfile(item) if item.isfile() else None
                write_member(destination, item, source) if source is not None else write_member(destination, item, _EmptyReader())
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise
    return {"member": member, "entries": len(names), "expanded_bytes": total, "top_level": sorted({n.split("/", 1)[0] for n in names})}


class _EmptyReader:
    def read(self, size: int = -1) -> bytes:
        return b""


def read_profile(path: Path) -> dict[str, object]:
    profile_path = path / "profile.json"
    try:
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RecipeError(f"invalid profile at {profile_path}: {exc}") from exc
    if not isinstance(profile, dict):
        raise RecipeError(f"profile is not an object: {profile_path}")
    return profile


def elf_info(path: Path) -> dict[str, object] | None:
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return None
    if data[4] != 2 or data[5] != 1:
        return {"path": str(path), "class": data[4], "data": data[5], "machine": "unsupported"}
    machine = int.from_bytes(data[18:20], "little")
    phoff = int.from_bytes(data[32:40], "little")
    phentsize = int.from_bytes(data[54:56], "little")
    phnum = int.from_bytes(data[56:58], "little")
    interpreter = None
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + phentsize > len(data) or phentsize < 56:
            raise RecipeError(f"invalid ELF program header table: {path}")
        if int.from_bytes(data[offset:offset + 4], "little") != 3:
            continue
        string_offset = int.from_bytes(data[offset + 8:offset + 16], "little")
        string_size = int.from_bytes(data[offset + 32:offset + 40], "little")
        interpreter = data[string_offset:string_offset + string_size].split(b"\0", 1)[0].decode(errors="replace")
    needed: list[str] = []
    if shutil.which("readelf") is not None:
        # readelf localizes the dynamic-tag labels unless the locale is pinned;
        # the audit must not silently lose DT_NEEDED on a non-English host.
        env = os.environ.copy()
        env.update({"LC_ALL": "C", "LANG": "C"})
        dynamic = subprocess.run(
            ["readelf", "-d", str(path)],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        ).stdout
        marker = "Shared library: ["
        needed = sorted({line.split(marker, 1)[1].split("]", 1)[0] for line in dynamic.splitlines() if marker in line})
    return {"path": str(path), "class": "ELF64", "machine": f"0x{machine:04x}", "machine_name": "AArch64" if machine == 183 else "other", "interpreter": interpreter, "needed": needed}


def pe_info(path: Path) -> dict[str, object] | None:
    data = path.read_bytes()
    if len(data) < 64 or data[:2] != b"MZ":
        return None
    offset = int.from_bytes(data[0x3C:0x40], "little")
    if offset + 6 > len(data) or data[offset:offset + 4] != b"PE\0\0":
        return None
    machine = int.from_bytes(data[offset + 4:offset + 6], "little")
    names = {0xAA64: "ARM64", 0xA641: "ARM64EC", 0xA64E: "ARM64X", 0x8664: "x86-64", 0x014C: "x86"}
    return {"path": str(path), "machine": f"0x{machine:04x}", "machine_name": names.get(machine, "other")}


def audit_tree(path: Path) -> dict[str, object]:
    elfs: list[dict[str, object]] = []
    pes: list[dict[str, object]] = []
    for candidate in sorted(p for p in path.rglob("*") if p.is_file()):
        info = elf_info(candidate)
        if info is not None:
            elfs.append(info)
        info = pe_info(candidate)
        if info is not None:
            pes.append(info)
    return {"elf_count": len(elfs), "elf": elfs, "pe_count": len(pes), "pe": pes}


def inspect_prefix_pack(proton_dir: Path) -> dict[str, object]:
    prefix = proton_dir / "prefixPack.txz"
    if not prefix.is_file():
        raise RecipeError(f"Wine prefix pack missing: {prefix}")
    names = subprocess.run(["tar", "-tJf", str(prefix)], check=True, capture_output=True, text=True).stdout.splitlines()
    for name in names:
        safe_member_name(name.rstrip("/"))
    return {"path": str(prefix), "size_bytes": prefix.stat().st_size, "sha256": sha256(prefix), "entries": len(names), "top_level": sorted({n.split("/", 1)[0] for n in names if n})}


def materialize(args: argparse.Namespace) -> int:
    bundle = args.bundle.resolve()
    output = args.output.resolve()
    source = validate_zip(bundle)
    output.mkdir(parents=True, exist_ok=True)
    existing = output / "runtime-manifest.json"
    with zipfile.ZipFile(bundle) as archive:
        proton_dir = output / "proton-11.0-90624-arm64ec"
        dxvk_dir = output / "dxvk-2.4.1-wow-aarch64-test"
        if existing.exists():
            if not proton_dir.is_dir() or not dxvk_dir.is_dir():
                raise RecipeError(f"existing manifest has incomplete runtime output: {output}")
            previous = json.loads(existing.read_text(encoding="utf-8"))
            proton_extract = previous["components"]["proton"]["extract"]
            dxvk_extract = previous["components"]["dxvk"]["extract"]
        else:
            unexpected = [item.name for item in output.iterdir()]
            if unexpected:
                raise RecipeError(f"output must be absent or empty: {output} ({unexpected})")
            proton_extract = extract_wcp(archive, PROTON_WCP, proton_dir)
            dxvk_extract = extract_wcp(archive, DXVK_WCP, dxvk_dir)
    proton_profile = read_profile(proton_dir)
    dxvk_profile = read_profile(dxvk_dir)
    if proton_profile.get("versionName") != EXPECTED_PROTON or dxvk_profile.get("versionName") != EXPECTED_DXVK:
        raise RecipeError("WCP profile version does not match the pinned recipe")
    proton_audit = audit_tree(proton_dir)
    dxvk_audit = audit_tree(dxvk_dir)
    prefix_audit = inspect_prefix_pack(proton_dir)
    bad_elfs = [item for item in proton_audit["elf"] if item.get("machine_name") != "AArch64"]
    if bad_elfs:
        raise RecipeError(f"non-AArch64 host ELF found: {bad_elfs[:3]}")
    manifest = {
        "schema": "wowforever.arm64-runtime.v1",
        "static_only": True,
        "executed": False,
        "target": {"abi": "arm64-v8a", "gpu": "PowerVR", "renderer": "system-vulkan", "turnip": False},
        "source": {"bundle": str(bundle), **source},
        "components": {
            "proton": {"wcp": PROTON_WCP, "profile": proton_profile, "extract": proton_extract, "audit": proton_audit, "prefix_pack": prefix_audit},
            "dxvk": {"wcp": DXVK_WCP, "profile": dxvk_profile, "extract": dxvk_extract, "audit": dxvk_audit},
        },
        "launch_contract": {
            "runtime_dir": "<app-private-files>/runtimes/arm64/<version>",
            "prefix_dir": "<app-private-files>/prefixes/arm64/<version>",
            "client_pe": "<mapped-drive>/WowB-ARM64.exe",
            "wine_command": "<runtime_dir>/proton-11.0-90624-arm64ec/bin/wine <client_pe> -d3d11",
            "working_dir": "parent directory of client_pe",
            "required_env": ["WINEPREFIX", "HOME", "PATH", "LD_LIBRARY_PATH", "DISPLAY", "XDG_DATA_DIRS", "WINEESYNC=0"],
            "vulkan_policy": "use Android system Vulkan through the selected renderer; do not install Turnip/Adrenotools",
            "architecture_policy": "native ARM64 PE and aarch64-unix Wine; do not set x86_64 DLL overrides or use a FrontMage FEX prefix",
        },
        "resource_estimate": {
            "compressed_wcp_bytes": {"proton": source["member_compressed_bytes"][PROTON_WCP], "dxvk": source["member_compressed_bytes"][DXVK_WCP]},
            "expanded_bytes": {"proton": proton_extract["expanded_bytes"], "dxvk": dxvk_extract["expanded_bytes"]},
            "prefix_pack_compressed_bytes": prefix_audit["size_bytes"],
            "note": "bundle-level compressed bytes are recorded separately in source; values above are not per-component sizes",
        },
    }
    (output / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "proton_elfs": proton_audit["elf_count"], "proton_pes": proton_audit["pe_count"], "dxvk_pes": dxvk_audit["pe_count"], "prefix_entries": prefix_audit["entries"]}, sort_keys=True))
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--bundle", type=Path, required=True, help="verified outer GameNative bundle ZIP")
    result.add_argument("--output", type=Path, required=True, help="empty directory outside the repository")
    return result


if __name__ == "__main__":
    try:
        raise SystemExit(materialize(parser().parse_args()))
    except (RecipeError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
