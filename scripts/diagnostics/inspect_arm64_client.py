#!/usr/bin/env python3
"""Inspect and fetch the official Windows ARM64 beta executable.

This is deliberately a one-file, static-evidence tool.  It resolves the pinned
NGDP build with cascette-py, selects the manifest entry tagged
``Windows,arm64,enUS``, downloads only ``WowB-ARM64.exe`` (when it is not
already cached), and reads its PE headers/import table without executing it.
All downloaded material is written outside the repository.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from datetime import datetime, timezone
from pathlib import Path
from typing import BinaryIO

from cascette_tools.core.cache import DiskCache
from cascette_tools.core.cdn import CDNClient
from cascette_tools.core.types import Product
from cascette_tools.formats.blte import decompress_blte, is_blte
from cascette_tools.formats.config import BuildConfigParser, CDNConfigParser
from cascette_tools.formats.encoding import EncodingParser
from cascette_tools.formats.install import InstallEntry, InstallParser

from forever_client.casc import filter_install_entries


DEFAULT_BUILD_CONFIG = "05215079e3905ef5922ae0b03ffefb73"
DEFAULT_CDN_CONFIG = "9b3c456dbb837d133a026d380c7c13e9"
DEFAULT_PRODUCT = "wow_classic_beta"
DEFAULT_REGION = "us"
DEFAULT_CDN_HOST = "https://us.cdn.blizzard.com"
DEFAULT_OUTPUT = Path("/home/adrian/wowforever-artifacts/references/official-arm64-beta")
MAX_EXECUTABLE_BYTES = 100_000_000
VERSION_RE = re.compile(rb"\b\d+\.\d+\.\d+\.\d{5}\b")

MACHINE_NAMES = {
    0xAA64: "ARM64",
    0xA641: "ARM64EC",
    0xA64E: "ARM64X",
    0x8664: "x86-64",
    0x014C: "x86",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def hash_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _u64(data: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


def _read_at(stream: BinaryIO, offset: int, size: int) -> bytes:
    stream.seek(offset)
    data = stream.read(size)
    if len(data) != size:
        raise ValueError(f"truncated PE data at 0x{offset:x}")
    return data


def _rva_to_file_offset(
    rva: int, sections: list[tuple[int, int, int, int]], size_of_headers: int
) -> int | None:
    if rva < size_of_headers:
        return rva
    for virtual_address, virtual_size, raw_offset, raw_size in sections:
        span = max(virtual_size, raw_size)
        if virtual_address <= rva < virtual_address + span:
            relative = rva - virtual_address
            if relative >= raw_size:
                return None
            return raw_offset + relative
    return None


def _read_c_string(stream: BinaryIO, offset: int, limit: int = 512) -> str:
    stream.seek(offset)
    data = bytearray()
    for _ in range(limit):
        char = stream.read(1)
        if not char or char == b"\0":
            break
        data.extend(char)
    return data.decode("ascii", errors="replace")


def _scan_versions(path: Path) -> list[str]:
    found: set[str] = set()
    overlap = b""
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            data = overlap + block
            found.update(match.decode("ascii") for match in VERSION_RE.findall(data))
            overlap = data[-32:]
    return sorted(found)


def inspect_pe(path: Path) -> dict[str, object]:
    """Read PE headers and static import names; never maps or executes code."""
    with path.open("rb") as stream:
        dos = _read_at(stream, 0, 64)
        if dos[:2] != b"MZ":
            raise ValueError("not a PE file: missing MZ signature")
        pe_offset = _u32(dos, 0x3C)
        signature = _read_at(stream, pe_offset, 4)
        if signature != b"PE\0\0":
            raise ValueError("not a PE file: missing PE signature")

        coff = _read_at(stream, pe_offset + 4, 20)
        machine = _u16(coff, 0)
        section_count = _u16(coff, 2)
        timestamp = _u32(coff, 4)
        optional_size = _u16(coff, 16)
        optional_offset = pe_offset + 24
        optional = _read_at(stream, optional_offset, optional_size)
        magic = _u16(optional, 0)
        if magic == 0x20B:
            data_directory_offset = 112
            size_of_headers = _u32(optional, 60)
        elif magic == 0x10B:
            data_directory_offset = 96
            size_of_headers = _u32(optional, 60)
        else:
            raise ValueError(f"unsupported PE optional-header magic 0x{magic:04x}")

        dll_characteristics = _u16(optional, 70)
        sections: list[tuple[int, int, int, int]] = []
        section_offset = optional_offset + optional_size
        for index in range(section_count):
            raw = _read_at(stream, section_offset + 40 * index, 40)
            sections.append((_u32(raw, 12), _u32(raw, 8), _u32(raw, 20), _u32(raw, 16)))

        imports: list[str] = []
        if len(optional) >= data_directory_offset + 16:
            import_rva = _u32(optional, data_directory_offset + 8)
            import_size = _u32(optional, data_directory_offset + 12)
            import_offset = _rva_to_file_offset(import_rva, sections, size_of_headers)
            if import_offset is not None and import_size:
                for index in range(4096):
                    descriptor = _read_at(stream, import_offset + index * 20, 20)
                    original_thunk = _u32(descriptor, 0)
                    name_rva = _u32(descriptor, 12)
                    first_thunk = _u32(descriptor, 16)
                    if not any((original_thunk, name_rva, first_thunk)):
                        break
                    name_offset = _rva_to_file_offset(name_rva, sections, size_of_headers)
                    if name_offset is not None:
                        imports.append(_read_c_string(stream, name_offset))

        return {
            "signature": "MZ/PE\\0\\0",
            "pe_offset": pe_offset,
            "machine": f"0x{machine:04x}",
            "machine_name": MACHINE_NAMES.get(machine, "unknown"),
            "is_arm64_family": machine in (0xAA64, 0xA641, 0xA64E),
            "optional_magic": f"0x{magic:04x}",
            "timestamp_raw": timestamp,
            "dll_characteristics": f"0x{dll_characteristics:04x}",
            "nx_compat": bool(dll_characteristics & 0x0100),
            "imports": sorted(set(imports)),
            "versions_found": _scan_versions(path),
        }


def _entry_dict(entry: InstallEntry) -> dict[str, object]:
    return {
        "filename": entry.filename,
        "size": entry.size,
        "md5": entry.md5_hash.hex(),
        "tags": entry.tags,
    }


def _official_url(host: str, kind: str, digest: str) -> str:
    h = digest.lower()
    return f"{host.rstrip('/')}/tpr/wow/{kind}/{h[:2]}/{h[2:4]}/{h}"


def inspect(args: argparse.Namespace) -> int:
    output_dir: Path = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    cdn = CDNClient(Product(args.product), region=args.region)
    try:
        build_config_data = cdn.fetch_config(args.build_config, config_type="build")
        build_config = BuildConfigParser().parse(build_config_data)
        cdn_config_data = cdn.fetch_config(args.cdn_config, config_type="cdn")
        cdn_config = CDNConfigParser().parse(cdn_config_data)
        if not cdn_config.archives:
            raise RuntimeError("CDN config has no data archives")
        encoding_info = build_config.get_encoding_info()
        install_info = build_config.get_install_info()
        if not encoding_info or not encoding_info.encoding_key:
            raise RuntimeError("build config has no encoding ckey/ekey pair")
        if not install_info or not install_info.encoding_key:
            raise RuntimeError("build config has no install ckey/ekey pair")

        # The encoding blob is 160 MB in this build.  CDNClient first checks the
        # immutable cascette disk cache, so this does not redownload an existing
        # encoding file; cascette remains responsible for its binary format.
        encoding_raw = cdn.fetch_data(encoding_info.encoding_key, quiet=True)
        encoding_data = decompress_blte(encoding_raw) if is_blte(encoding_raw) else encoding_raw
        encoding_parser = EncodingParser()
        encoding_file = encoding_parser.parse(encoding_data)

        install_raw = cdn.fetch_data(install_info.encoding_key, quiet=True)
        install_data = decompress_blte(install_raw) if is_blte(install_raw) else install_raw
        manifest = InstallParser().parse(install_data)
        arm_entries = filter_install_entries(manifest.entries, manifest.tags, "Windows", "arm64", "enUS")
        x86_entries = filter_install_entries(manifest.entries, manifest.tags, "Windows", "x86_64", "enUS")
        target_candidates = [entry for entry in arm_entries if entry.filename.casefold() == "wowb-arm64.exe"]
        if len(target_candidates) != 1:
            raise RuntimeError(f"expected one ARM64 WowB entry, found {len(target_candidates)}")
        target = target_candidates[0]
        if target.size > MAX_EXECUTABLE_BYTES:
            raise RuntimeError(f"refusing executable larger than 100,000,000 bytes: {target.size}")

        target_ekeys = encoding_parser.find_content_key(encoding_data, encoding_file, target.md5_hash) or []
        if not target_ekeys:
            raise RuntimeError(f"no encoding key for {target.filename} ({target.md5_hash.hex()})")
        target_ekey = target_ekeys[0]
        raw_target = cdn.fetch_data(target_ekey.hex(), quiet=True)
        target_data = decompress_blte(raw_target) if is_blte(raw_target) else raw_target
        if len(target_data) != target.size:
            raise RuntimeError(f"downloaded size {len(target_data)} != manifest size {target.size}")
        actual_md5 = hashlib.md5(target_data).hexdigest()
        if actual_md5 != target.md5_hash.hex():
            raise RuntimeError(f"downloaded MD5 {actual_md5} != manifest MD5 {target.md5_hash.hex()}")

        executable = output_dir / target.filename.replace("\\", "_")
        part = executable.with_suffix(executable.suffix + ".part")
        part.write_bytes(target_data)
        part.replace(executable)
        pe = inspect_pe(executable)

        source = {
            "captured_at": utc_now(),
            "product": args.product,
            "region": args.region,
            "build_name": build_config.build_name,
            "build_config_hash": args.build_config,
            "cdn_config_hash": args.cdn_config,
            "build_config_url": _official_url(args.cdn_host, "config", args.build_config),
            "cdn_config_url": _official_url(args.cdn_host, "config", args.cdn_config),
            "cdn_data_archives": len(cdn_config.archives),
            "encoding_content_key": encoding_info.content_key,
            "encoding_encoding_key": encoding_info.encoding_key,
            "install_content_key": install_info.content_key,
            "install_encoding_key": install_info.encoding_key,
            "install_manifest": {"version": manifest.version, "entries": len(manifest.entries)},
            "manifest_tags": [{"name": tag.name, "type": tag.tag_type} for tag in manifest.tags],
            "arm_selected_executables_and_dlls": [
                _entry_dict(entry)
                for entry in arm_entries
                if entry.filename.casefold().endswith((".exe", ".dll"))
            ],
            "x86_wowb_entry": next(
                (_entry_dict(entry) for entry in x86_entries if entry.filename.casefold() == "wowb.exe"),
                None,
            ),
            "target": {
                **_entry_dict(target),
                "encoding_keys": [key.hex() for key in target_ekeys],
                "encoding_url": _official_url(args.cdn_host, "data", target_ekey.hex()),
                "artifact": str(executable),
                "sha256": hash_file(executable, "sha256"),
            },
            "pe": pe,
            "static_only": True,
            "executed": False,
        }
        (output_dir / "evidence.json").write_text(json.dumps(source, indent=2, sort_keys=True) + "\n", encoding="utf-8")

        print(f"build={build_config.build_name}")
        print(f"build_config={args.build_config} url={source['build_config_url']}")
        print(f"cdn_config={args.cdn_config} url={source['cdn_config_url']}")
        print(f"manifest_entries={len(manifest.entries)} arm_selected={len(arm_entries)} x86_selected={len(x86_entries)}")
        print(f"target={target.filename} size={target.size} md5={target.md5_hash.hex()} ekey={target_ekey.hex()}")
        print(f"artifact={executable} sha256={source['target']['sha256']}")
        print(f"pe_machine={pe['machine']} ({pe['machine_name']}) arm64_family={pe['is_arm64_family']}")
        print(f"pe_nx_compat={pe['nx_compat']} dll_characteristics={pe['dll_characteristics']}")
        print(f"pe_versions={','.join(pe['versions_found']) or '(none found)'}")
        print("static_imports=" + ",".join(pe["imports"]) if pe["imports"] else "static_imports=(none)")
        print("arm_dependencies=" + ",".join(item["filename"] for item in source["arm_selected_executables_and_dlls"]))
        return 0
    finally:
        cdn.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--build-config", default=DEFAULT_BUILD_CONFIG)
    parser.add_argument("--cdn-config", default=DEFAULT_CDN_CONFIG)
    parser.add_argument("--product", default=DEFAULT_PRODUCT)
    parser.add_argument("--region", default=DEFAULT_REGION)
    parser.add_argument("--cdn-host", default=DEFAULT_CDN_HOST)
    return parser


if __name__ == "__main__":
    raise SystemExit(inspect(build_parser().parse_args()))
