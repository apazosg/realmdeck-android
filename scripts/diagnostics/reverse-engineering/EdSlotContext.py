#!/usr/bin/env python3
"""Read-only structural context for the two authenticated static Ed slots.

This is deliberately complementary to Ghidra: it does not disassemble, find
code references, or scan the image for keys.  It reads PE headers and exactly
one +/-512-byte raw window per supplied slot, with small bounded reads of the
corresponding relocation/resource metadata.  Reports contain offsets, masks,
counts, and classified numbers only; slot bytes and key material are never
written to the report.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
ARTIFACT_ROOT = Path(
    "/home/adrian/wowforever-artifacts/work/"
    "wow-patcher-arm64-20261013T182540Z"
)
ARM_SOURCE = ARTIFACT_ROOT / "arm64/WowB-ARM64.exe"
X64_SOURCE = ARTIFACT_ROOT / "x64/WowB.exe"
CONTRACT_SOURCE = ROOT / "forever/contract.json"
UPSTREAM_SOURCE = ARTIFACT_ROOT / "source/src/patterns/mod.rs"
FUNCTIONAL_PATCHER_CANDIDATES = (
    Path("/home/adrian/wowserver/vendor/forever-patcher/src/patch.rs"),
    Path("/home/adrian/dev/wowserver/vendor/forever-patcher/src/patch.rs"),
)

EXPECTED = {
    "arm64": {
        "size": 94286544,
        "sha256": "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59",
        "machine": 0xAA64,
        "slot_raw": 0x4A5F100,
        "slot_rva": 0x4A5FF00,
    },
    "x64": {
        "size": 102916816,
        "sha256": "5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92",
        "machine": 0x8664,
        "slot_raw": 0x48F5A70,
        "slot_rva": 0x48F6870,
    },
}

SLOT_BYTES = 32
CONTEXT_RADIUS = 512
MAX_CONTEXT_BYTES = CONTEXT_RADIUS * 2 + SLOT_BYTES
MAX_METADATA_BYTES = 64 * 1024
MAX_REPORT_BYTES = 2 * 1024 * 1024
PE_HEADER_BYTES = 64 * 1024
MAX_DIRECTED_BYTES = 32 * 1024 * 1024
MAX_MATCH_POSITIONS = 64
MAX_PAIR_OBSERVATIONS = 256


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def u64(data: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


def stat_record(stat: os.stat_result) -> dict:
    return {
        "dev": stat.st_dev,
        "inode": stat.st_ino,
        "mode": stat.st_mode,
        "nlink": stat.st_nlink,
        "uid": stat.st_uid,
        "size": stat.st_size,
        "mtime_ns": stat.st_mtime_ns,
        "ctime_ns": stat.st_ctime_ns,
    }


def sha256_fd(fd: int, size: int) -> str:
    digest = hashlib.sha256()
    offset = 0
    while offset < size:
        chunk = os.pread(fd, min(1024 * 1024, size - offset), offset)
        if not chunk:
            raise OSError("short read while hashing authenticated source")
        digest.update(chunk)
        offset += len(chunk)
    return digest.hexdigest()


def read_exact(fd: int, offset: int, length: int) -> bytes:
    if length < 0 or offset < 0:
        raise ValueError("negative bounded read")
    result = bytearray()
    while len(result) < length:
        chunk = os.pread(fd, length - len(result), offset + len(result))
        if not chunk:
            raise OSError("short bounded read")
        result.extend(chunk)
    return bytes(result)


def open_authenticated(path: Path, expected: dict) -> tuple[int, dict, bytes]:
    flags = os.O_RDONLY | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        before_stat = os.fstat(fd)
        if not stat.S_ISREG(before_stat.st_mode):
            raise ValueError(f"{path} is not a regular file")
        before_hash = sha256_fd(fd, before_stat.st_size)
        if before_stat.st_size != expected["size"] or before_hash != expected["sha256"]:
            raise ValueError(f"authenticated {path} identity mismatch")
        header = read_exact(fd, 0, min(PE_HEADER_BYTES, before_stat.st_size))
        after_hash = sha256_fd(fd, before_stat.st_size)
        after_stat = os.fstat(fd)
        identity = {
            "path": str(path),
            "size": before_stat.st_size,
            "expected_size": expected["size"],
            "sha256_before": before_hash,
            "sha256_after": after_hash,
            "expected_sha256": expected["sha256"],
            "stat_before": stat_record(before_stat),
            "stat_after": stat_record(after_stat),
            "stable_full_hash": before_hash == after_hash
            and stat_record(before_stat) == stat_record(after_stat),
            "held_fd": True,
            "nofollow_open": True,
        }
        if not identity["stable_full_hash"]:
            raise ValueError(f"source changed during held-fd authentication: {path}")
        return fd, identity, header
    except Exception:
        os.close(fd)
        raise


def parse_pe(header: bytes) -> tuple[dict, list[dict]]:
    if header[:2] != b"MZ":
        raise ValueError("source is not a DOS PE")
    nt = u32(header, 0x3C)
    if header[nt : nt + 4] != b"PE\0\0":
        raise ValueError("invalid PE signature")
    file_header = nt + 4
    machine = u16(header, file_header)
    count = u16(header, file_header + 2)
    optional_size = u16(header, file_header + 16)
    optional = file_header + 20
    if u16(header, optional) != 0x20B:
        raise ValueError("expected PE32+")
    directory_count = min(u32(header, optional + 108), 16)
    directories = []
    for index in range(directory_count):
        entry = optional + 112 + index * 8
        directories.append({"rva": u32(header, entry), "size": u32(header, entry + 4)})
    sections = []
    table = optional + optional_size
    for index in range(count):
        offset = table + index * 40
        name = header[offset : offset + 8].split(b"\0", 1)[0].decode("ascii", "replace")
        sections.append(
            {
                "name": name,
                "virtual_size": u32(header, offset + 8),
                "rva": u32(header, offset + 12),
                "raw_size": u32(header, offset + 16),
                "raw_offset": u32(header, offset + 20),
                "characteristics": u32(header, offset + 36),
            }
        )
    return {
        "machine": machine,
        "image_base": u64(header, optional + 24),
        "size_of_image": u32(header, optional + 56),
        "size_of_headers": u32(header, optional + 60),
        "directories": directories,
    }, sections


def section_for(sections: list[dict], rva: int) -> dict | None:
    for section in sections:
        if section["rva"] <= rva < section["rva"] + max(
            section["virtual_size"], section["raw_size"]
        ):
            return section
    return None


def rva_to_raw(sections: list[dict], rva: int, length: int = 1) -> int | None:
    section = section_for(sections, rva)
    if section is None:
        return None
    relative = rva - section["rva"]
    if relative < 0 or relative + length > section["raw_size"]:
        return None
    return section["raw_offset"] + relative


def directory_entry(pe: dict, index: int) -> dict:
    return pe["directories"][index] if index < len(pe["directories"]) else {"rva": 0, "size": 0}


def relative_runs(values: list[bool], first: int, slot_start: int, cap: int = 128) -> list[list[int]]:
    runs = []
    index = 0
    while index < len(values):
        value = values[index]
        end = index + 1
        while end < len(values) and values[end] == value:
            end += 1
        if value and len(runs) < cap:
            runs.append([first + index - slot_start, end - index])
        index = end
    return runs


def byte_profile(data: bytes) -> dict:
    return {
        "length": len(data),
        "zero_count": data.count(0),
        "printable_ascii_count": sum(32 <= value < 127 for value in data),
        "high_bit_count": sum(value >= 128 for value in data),
        "distinct_byte_count": len(set(data)),
        "adjacent_transition_count": sum(a != b for a, b in zip(data, data[1:])),
    }


def runs_matching(data: bytes, predicate) -> tuple[int, int]:
    left = right = 0
    while left < len(data) and predicate(data[len(data) - 1 - left]):
        left += 1
    while right < len(data) and predicate(data[right]):
        right += 1
    return left, right


def slot_shape(window: bytes, slot_index: int, window_raw_start: int = 0) -> dict:
    slot_end = slot_index + SLOT_BYTES
    before_zero, after_zero = runs_matching(
        window[:slot_index], lambda value: value == 0
    )[0], runs_matching(window[slot_end:], lambda value: value == 0)[1]
    before_nonzero, after_nonzero = runs_matching(
        window[:slot_index], lambda value: value != 0
    )[0], runs_matching(window[slot_end:], lambda value: value != 0)[1]
    before_printable, after_printable = runs_matching(
        window[:slot_index], lambda value: 32 <= value < 127
    )[0], runs_matching(window[slot_end:], lambda value: 32 <= value < 127)[1]
    return {
        "slot_width": SLOT_BYTES,
        "slot_all_zero": all(value == 0 for value in window[slot_index:slot_end]),
        "slot_all_printable_ascii": all(32 <= value < 127 for value in window[slot_index:slot_end]),
        "immediate_zero_run_before": before_zero,
        "immediate_zero_run_after": after_zero,
        "immediate_nonzero_run_before": before_nonzero,
        "immediate_nonzero_run_after": after_nonzero,
        "immediate_printable_run_before": before_printable,
        "immediate_printable_run_after": after_printable,
        "zero_delimiter_on_both_sides": bool(before_zero and after_zero),
        "nonzero_containing_span_relative": [
            -before_nonzero,
            SLOT_BYTES + after_nonzero,
        ],
        "nonzero_span_truncated_by_window": before_nonzero == slot_index
        or after_nonzero == len(window) - slot_end,
        "natural_alignment": {
            str(width): (window_raw_start + slot_index) % width == 0 for width in (4, 8, 16, 32)
        },
        "vector_boundaries": {
            str(width): {
                "slot_start_aligned": (window_raw_start + slot_index) % width == 0,
                "slot_width_multiple": SLOT_BYTES % width == 0,
            }
            for width in (8, 16, 32)
        },
    }


def positional_mask(left: bytes, right: bytes) -> dict:
    mask = [left[index] == right[index] for index in range(min(len(left), len(right)))]
    runs = []
    index = 0
    while index < len(mask):
        end = index + 1
        while end < len(mask) and mask[end] == mask[index]:
            end += 1
        if mask[index]:
            runs.append([index, end - index])
        index = end
    return {
        "length_compared": len(mask),
        "equal_count": sum(mask),
        "mismatch_count": len(mask) - sum(mask),
        "equal_mask": "".join("1" if item else "0" for item in mask),
        "equal_runs": runs,
    }


def classify_scalar(value: int, width: int, pe: dict) -> str | None:
    if value == 0:
        return None
    image_base = pe["image_base"]
    image_end = image_base + pe["size_of_image"]
    if width == 8 and image_base <= value < image_end:
        return "absolute_image_pointer"
    if width == 4 and value < pe["size_of_image"]:
        return "image_rva_candidate"
    if value <= 0x1000000:
        return "small_scalar_candidate"
    return None


def scalar_candidates(window: bytes, slot_index: int, pe: dict, window_raw_start: int) -> list[dict]:
    result = []
    slot_end = slot_index + SLOT_BYTES
    # Only the 64-byte neighborhoods immediately outside the candidate are
    # interpreted.  This intentionally cannot become a data/key sweep.
    for width in (4, 8):
        for index in range(0, len(window) - width + 1):
            if (window_raw_start + index) % width:
                continue
            relative = index - slot_index
            if relative < -64 or relative >= SLOT_BYTES + 64:
                continue
            if index < slot_end and index + width > slot_index:
                continue
            value = int.from_bytes(window[index : index + width], "little")
            role = classify_scalar(value, width, pe)
            if role is None:
                continue
            result.append({"relative_offset": relative, "width": width, "role": role, "value": value})
    return result[:128]


def descriptor_candidates(window: bytes, slot_index: int, pe: dict) -> list[dict]:
    result = []
    slot_end = slot_index + SLOT_BYTES
    for index in range(0, len(window) - 16 + 1, 8):
        relative = index - slot_index
        if relative < -64 or relative >= SLOT_BYTES + 64:
            continue
        if index < slot_end and index + 16 > slot_index:
            continue
        pointer = u64(window, index)
        pointer_role = classify_scalar(pointer, 8, pe)
        length = u32(window, index + 8)
        if pointer_role not in {"absolute_image_pointer", "small_scalar_candidate"}:
            continue
        if length > 0x1000000:
            continue
        result.append(
            {
                "record_relative_offset": relative,
                "record_width": 16,
                "pointer_relative_offset": relative,
                "pointer_role": pointer_role,
                "pointer_value": pointer,
                "length_relative_offset": relative + 8,
                "length_value": length,
                "crosses_slot": False,
            }
        )
    return result[:32]


def parse_relocation_context(
    fd: int, pe: dict, sections: list[dict], slot_rva: int, context_rva_start: int, context_rva_end: int
) -> dict:
    directory = directory_entry(pe, 5)
    raw = rva_to_raw(sections, directory["rva"], 1) if directory["rva"] else None
    if raw is None or not directory["size"]:
        return {"directory_present": False, "entries_in_context": [], "bytes_examined": 0}
    examined = min(directory["size"], MAX_METADATA_BYTES)
    blob = read_exact(fd, raw, examined)
    cursor = 0
    entries = []
    block_count = 0
    target_page = slot_rva & ~0xFFF
    while cursor + 8 <= len(blob):
        page = u32(blob, cursor)
        block_size = u32(blob, cursor + 4)
        if block_size < 8 or cursor + block_size > len(blob):
            break
        block_count += 1
        if page == target_page:
            for position in range(cursor + 8, cursor + block_size, 2):
                item = u16(blob, position)
                kind = item >> 12
                if kind == 0:
                    continue
                target = page + (item & 0xFFF)
                if context_rva_start <= target < context_rva_end:
                    entries.append({"relative_rva": target - slot_rva, "type": kind})
        cursor += block_size
        if page > target_page:
            break
    return {
        "directory_present": True,
        "directory_rva": f"0x{directory['rva']:x}",
        "directory_declared_size": directory["size"],
        "bytes_examined": examined,
        "scan_limit_hit": directory["size"] > examined,
        "blocks_examined": block_count,
        "entries_in_context": entries,
    }


def version_context(
    fd: int,
    pe: dict,
    sections: list[dict],
    slot_raw: int,
    slot_rva: int,
    contract: dict,
) -> dict:
    directory = directory_entry(pe, 2)
    resource = section_for(sections, directory["rva"]) if directory["rva"] else None
    context_raw_start = slot_raw - CONTEXT_RADIUS
    context_raw_end = slot_raw + SLOT_BYTES + CONTEXT_RADIUS
    context_rva_start = slot_rva - CONTEXT_RADIUS
    context_rva_end = slot_rva + SLOT_BYTES + CONTEXT_RADIUS
    result = {
        "resource_directory_rva": f"0x{directory['rva']:x}",
        "resource_directory_size": directory["size"],
        "resource_section": None if resource is None else resource["name"],
        "context_overlaps_resource_rva": bool(
            directory["rva"]
            and directory["rva"] < context_rva_end
            and directory["rva"] + directory["size"] > context_rva_start
        ),
        "context_overlaps_resource_raw": bool(
            resource
            and resource["raw_offset"] < context_raw_end
            and resource["raw_offset"] + resource["raw_size"] > context_raw_start
        ),
        "version_values": [],
        "version_read_limit": MAX_METADATA_BYTES,
        "contract_validated_build_id": contract["product"]["validated_build_id"],
        "contract_validated_version": contract["product"]["validated_versions_name"],
    }
    if resource is None or resource["raw_size"] == 0:
        return result
    amount = min(resource["raw_size"], MAX_METADATA_BYTES)
    blob = read_exact(fd, resource["raw_offset"], amount)
    marker = "FileVersion".encode("utf-16le")
    cursor = 0
    while len(result["version_values"]) < 4:
        position = blob.find(marker, cursor)
        if position < 0:
            break
        value_start = position + len(marker) + 2
        value_end = value_start
        while value_end + 1 < len(blob) and blob[value_end : value_end + 2] != b"\0\0":
            value_end += 2
        value = blob[value_start:value_end].decode("utf-16le", "replace").strip()
        if value:
            result["version_values"].append(value[:64])
        cursor = position + 2
    result["resource_bytes_examined"] = amount
    result["resource_read_limit_hit"] = resource["raw_size"] > amount
    return result


def extract_upstream_anchors(source: Path) -> dict[str, bytes]:
    """Read only named <=16-byte constants from the upstream pattern source."""
    text = source.read_text(encoding="utf-8")
    result = {}
    names = (
        "connect_to_modulus_pattern",
        "signature_modulus_pattern",
        "crypto_rsa_modulus_pattern",
        "crypto_ed_public_key_pattern",
    )
    for name in names:
        match = re.search(
            rf"pub fn {name}\(\).*?get_or_init\(\|\|\s*vec!\[([^\]]+)\]",
            text,
            re.DOTALL,
        )
        if not match:
            continue
        numbers = re.findall(r"0x([0-9A-Fa-f]+)", match.group(1))
        values = bytes(int(number, 16) for number in numbers)
        if 0 < len(values) <= 16:
            result[name] = values
    return result


def anchor_context_checks(window: bytes, slot_index: int, source: Path) -> list[dict]:
    anchors = extract_upstream_anchors(source)
    checks = []
    for name, pattern in anchors.items():
        positions = []
        start = 0
        while True:
            found = window.find(pattern, start)
            if found < 0:
                break
            positions.append(found - slot_index)
            start = found + 1
        checks.append(
            {
                "name": name,
                "width": len(pattern),
                "match_count_in_context": len(positions),
                "match_relative_offsets": positions,
                "raw_pattern_emitted": False,
            }
        )
    return checks


def find_occurrences(blob: bytes, pattern: bytes, limit: int = MAX_MATCH_POSITIONS) -> tuple[list[int], bool]:
    """Return at most ``limit`` exact occurrences and whether the cap was hit."""
    positions = []
    start = 0
    limit_hit = False
    while True:
        found = blob.find(pattern, start)
        if found < 0:
            break
        if len(positions) < limit:
            positions.append(found)
        else:
            limit_hit = True
            break
        start = found + 1
    return positions, limit_hit


def context_half_matches(window: bytes, slot_index: int, halves: list[dict]) -> list[dict]:
    result = []
    for item in halves:
        positions, limit_hit = find_occurrences(window, item["bytes"])
        result.append(
            {
                "group": item["group"],
                "half": item["half"],
                "width": 16,
                "match_count": len(positions),
                "count_limit_hit": limit_hit,
                "match_relative_offsets": [position - slot_index for position in positions],
                "raw_pattern_emitted": False,
            }
        )
    return result


def vector_alignment(raw_offset: int, rva: int) -> dict:
    return {
        "raw_mod_16": raw_offset % 16,
        "rva_mod_16": rva % 16,
        "raw_aligned_16": raw_offset % 16 == 0,
        "rva_aligned_16": rva % 16 == 0,
        "raw_aligned_32": raw_offset % 32 == 0,
        "rva_aligned_32": rva % 32 == 0,
    }


def directed_section_half_scan(
    fd: int,
    sections: list[dict],
    slot_raw: int,
    slot_rva: int,
    halves: list[dict],
) -> dict:
    selected = [section for section in sections if section["name"] in {".rdata", ".data"}]
    total_bytes = sum(section["raw_size"] for section in selected)
    if total_bytes > MAX_DIRECTED_BYTES:
        raise ValueError("directed .rdata/.data scan exceeds 32 MiB bound")
    by_half = {
        (item["group"], item["half"]): {
            "group": item["group"],
            "half": item["half"],
            "width": 16,
            "matches": [],
            "count_limit_hit": False,
            "raw_pattern_emitted": False,
        }
        for item in halves
    }
    for section in selected:
        blob = read_exact(fd, section["raw_offset"], section["raw_size"])
        for item in halves:
            key = (item["group"], item["half"])
            local_positions, local_limit_hit = find_occurrences(blob, item["bytes"])
            bucket = by_half[key]
            bucket["count_limit_hit"] = bucket["count_limit_hit"] or local_limit_hit
            for local in local_positions:
                raw_offset = section["raw_offset"] + local
                rva = section["rva"] + local
                observation = {
                    "section": section["name"],
                    "raw_offset": f"0x{raw_offset:x}",
                    "rva": f"0x{rva:x}",
                    "relative_to_slot_raw": raw_offset - slot_raw,
                    "relative_to_slot_rva": rva - slot_rva,
                    "slot_prefix_low128": item["half"] == 0 and raw_offset == slot_raw,
                    "vector_alignment": vector_alignment(raw_offset, rva),
                    "raw_pattern_emitted": False,
                }
                bucket["matches"].append(observation)
    observations = []
    for bucket in by_half.values():
        if len(bucket["matches"]) > MAX_MATCH_POSITIONS:
            bucket["matches"] = bucket["matches"][:MAX_MATCH_POSITIONS]
            bucket["count_limit_hit"] = True
        bucket["match_count"] = len(bucket["matches"])
        observations.extend(bucket["matches"])
    return {
        "sections_only": [section["name"] for section in selected],
        "section_count": len(selected),
        "bytes_examined": total_bytes,
        "combined_scan_limit": MAX_DIRECTED_BYTES,
        "combined_scan_within_limit": total_bytes <= MAX_DIRECTED_BYTES,
        "coverage": "complete raw .rdata/.data sections for this image",
        "position_limit_per_half": MAX_MATCH_POSITIONS,
        "half_observations": [by_half[key] for key in sorted(by_half)],
        "observation_count": len(observations),
        "raw_bytes_emitted": False,
    }


def half_pair_distances(first: dict, second: dict) -> dict:
    pairs = []
    for left in first["matches"]:
        for right in second["matches"]:
            if len(pairs) >= MAX_PAIR_OBSERVATIONS:
                return {
                    "pair_count": len(pairs),
                    "pair_limit_hit": True,
                    "distances_only": True,
                    "pairs": pairs,
                }
            pairs.append(
                {
                    "first_raw_offset": left["raw_offset"],
                    "second_raw_offset": right["raw_offset"],
                    "distance_raw": int(right["raw_offset"], 16) - int(left["raw_offset"], 16),
                    "same_section": left["section"] == right["section"],
                    "ordered_first_then_second": int(right["raw_offset"], 16) > int(left["raw_offset"], 16),
                }
            )
    return {
        "pair_count": len(pairs),
        "pair_limit_hit": False,
        "distances_only": True,
        "pairs": pairs,
    }


def summarize_half_materialization(scan: dict) -> list[dict]:
    observations = {(item["group"], item["half"]): item for item in scan["half_observations"]}
    result = []
    for group in range(1, 13):
        first = observations[(group, 0)]
        second = observations[(group, 1)]
        result.append(
            {
                "group": group,
                "first16_match_count": first["match_count"],
                "second16_match_count": second["match_count"],
                "first16_count_limit_hit": first["count_limit_hit"],
                "second16_count_limit_hit": second["count_limit_hit"],
                "both_halves_present": bool(first["matches"] and second["matches"]),
                "pair_distances": half_pair_distances(first, second),
                "no_assembled32_equivalence_inferred": True,
            }
        )
    return result


def directed_findings(arm: dict, x64: dict) -> dict:
    def slot_half_identities(image: dict, relative: int) -> list[list[int]]:
        identities = []
        for item in image["directed_public_half_scan"]["half_observations"]:
            if any(match["relative_to_slot_raw"] == relative for match in item["matches"]):
                identities.append([item["group"], item["half"]])
        return identities

    arm_low = slot_half_identities(arm, 0)
    x64_low = slot_half_identities(x64, 0)
    arm_high = slot_half_identities(arm, 16)
    x64_high = slot_half_identities(x64, 16)
    def label(identities: list[list[int]]) -> str:
        return ",".join(f"g{group}h{half}" for group, half in identities) or "none"

    return {
        "slot_low128_contract_half_identities_arm64": arm_low,
        "slot_low128_contract_half_identities_x64": x64_low,
        "slot_next16_contract_half_identities_arm64": arm_high,
        "slot_next16_contract_half_identities_x64": x64_high,
        "same_half_identity_in_both_architectures": arm_low == x64_low and arm_high == x64_high,
        "observed_contiguous_pair_distance_raw": 16 if arm_low and arm_high and x64_low and x64_high else None,
        "known9_second16_in_context_arm64": arm["known9_second16_in_context"]["match_count"] > 0,
        "known9_second16_in_context_x64": x64["known9_second16_in_context"]["match_count"] > 0,
        "interpretation": "The independently matched 16-byte ranges are reported as contract-half identities and a distance only; no 32-byte key assembly or compiler emission is inferred.",
        "concise_result": (
            f"arm64 slot halves: low128={label(arm_low)}, next16={label(arm_high)}; "
            f"x64 slot halves: low128={label(x64_low)}, next16={label(x64_high)}; "
            f"known9 second16 context: arm64={arm['known9_second16_in_context']['match_count']}, "
            f"x64={x64['known9_second16_in_context']['match_count']}"
        ),
    }


def analyze_image(path: Path, architecture: str, contract: dict, upstream_source: Path) -> dict:
    expected = EXPECTED[architecture]
    fd, identity, header = open_authenticated(path, expected)
    try:
        pe, sections = parse_pe(header)
        if pe["machine"] != expected["machine"]:
            raise ValueError(f"{architecture} PE machine mismatch")
        slot_raw = expected["slot_raw"]
        slot_rva = expected["slot_rva"]
        mapped = rva_to_raw(sections, slot_rva, SLOT_BYTES)
        slot_section = section_for(sections, slot_rva)
        if mapped != slot_raw or slot_section is None or slot_section["name"] != ".rdata":
            raise ValueError(f"{architecture} exact slot locator mismatch")
        window_start = max(0, slot_raw - CONTEXT_RADIUS)
        window_end = min(identity["size"], slot_raw + SLOT_BYTES + CONTEXT_RADIUS)
        window = read_exact(fd, window_start, window_end - window_start)
        slot_index = slot_raw - window_start
        slot = window[slot_index : slot_index + SLOT_BYTES]
        slot_context_start_rva = slot_rva - slot_index
        section_raw_start = slot_section["raw_offset"]
        section_raw_end = section_raw_start + slot_section["raw_size"]
        section_rva_start = slot_section["rva"]
        section_rva_end = section_rva_start + max(
            slot_section["virtual_size"], slot_section["raw_size"]
        )
        relocation = parse_relocation_context(
            fd,
            pe,
            sections,
            slot_rva,
            slot_context_start_rva,
            slot_context_start_rva + len(window),
        )
        version = version_context(fd, pe, sections, slot_raw, slot_rva, contract)
        context_halves = context_half_matches(window, slot_index, contract["_halves"])
        directed_halves = directed_section_half_scan(
            fd, sections, slot_raw, slot_rva, contract["_halves"]
        )
        identity_after = os.fstat(fd)
        final_hash = sha256_fd(fd, identity["size"])
        identity["sha256_final_held_fd"] = final_hash
        identity["stat_final"] = stat_record(identity_after)
        identity["stable_full_hash"] = (
            identity["stable_full_hash"]
            and final_hash == identity["sha256_before"]
            and stat_record(identity_after) == identity["stat_before"]
        )
        return {
            "architecture": architecture,
            "source": identity,
            "pe": {
                "machine": f"0x{pe['machine']:x}",
                "image_base": f"0x{pe['image_base']:x}",
                "size_of_image": pe["size_of_image"],
                "section_count": len(sections),
                "sections": [
                    {
                        "name": item["name"],
                        "rva": f"0x{item['rva']:x}",
                        "raw_offset": f"0x{item['raw_offset']:x}",
                        "raw_size": item["raw_size"],
                        "virtual_size": item["virtual_size"],
                    }
                    for item in sections
                ],
            },
            "slot": {
                "raw_offset": f"0x{slot_raw:x}",
                "rva": f"0x{slot_rva:x}",
                "section": slot_section["name"],
                "width": SLOT_BYTES,
                "window_raw": [f"0x{window_start:x}", f"0x{window_end:x}"],
                "window_relative_slot": [slot_index, slot_index + SLOT_BYTES],
                "section_relative_raw": [slot_raw - section_raw_start, slot_raw + SLOT_BYTES - section_raw_start],
                "section_bounds_raw": [f"0x{section_raw_start:x}", f"0x{section_raw_end:x}"],
                "section_bounds_rva": [f"0x{section_rva_start:x}", f"0x{section_rva_end:x}"],
                "context_bytes_examined": len(window),
                "raw_bytes_emitted": False,
                "slot_bytes_emitted": False,
            },
            "shape": slot_shape(window, slot_index, window_start),
            "slot_profile": byte_profile(slot),
            "neighbor_profile": {
                "before_64": byte_profile(window[max(0, slot_index - 64) : slot_index]),
                "after_64": byte_profile(window[slot_index + SLOT_BYTES : slot_index + SLOT_BYTES + 64]),
            },
            "neighbor_scalars": scalar_candidates(window, slot_index, pe, window_start),
            "neighbor_descriptor_candidates": descriptor_candidates(window, slot_index, pe),
            "relocation_context": relocation,
            "resource_version_context": version,
            "upstream_named_anchor_context_checks": anchor_context_checks(window, slot_index, upstream_source),
            "context_public_half_matches": context_halves,
            "known9_second16_in_context": next(
                item
                for item in context_halves
                if item["group"] == 9 and item["half"] == 1
            ),
            "directed_public_half_scan": directed_halves,
            "half_materialization_summary": summarize_half_materialization(directed_halves),
            "_slot_private": slot,
            "_window_private": window,
        }
    finally:
        os.close(fd)


def load_contract(path: Path) -> dict:
    contract = json.loads(path.read_text(encoding="utf-8"))
    keys = contract["key_store"]["known_keys"]
    if len(keys) < 9:
        raise ValueError("contract lacks known group 9")
    contract["_known9"] = bytes.fromhex(keys[8])
    contract["_halves"] = [
        {"group": group, "half": half, "bytes": bytes.fromhex(key)[half * 16 : (half + 1) * 16]}
        for group, key in enumerate(keys, 1)
        for half in (0, 1)
    ]
    return contract


def finalize_image(image: dict, known9: bytes) -> None:
    slot = image.pop("_slot_private")
    window = image.pop("_window_private")
    slot_index = image["slot"]["window_relative_slot"][0]
    image["slot_vs_contract_known9"] = positional_mask(slot, known9)
    image["slot_vs_contract_known9"]["prefix8_equal"] = slot[:8] == known9[:8]
    image["slot_vs_contract_known9"]["full32_equal"] = slot == known9
    image["context_profile"] = byte_profile(window)


def cross_architecture(arm: dict, x64: dict) -> dict:
    arm_slot = arm.get("_slot_private")
    x64_slot = x64.get("_slot_private")
    arm_window = arm.get("_window_private")
    x64_window = x64.get("_window_private")
    if arm_slot is None or x64_slot is None or arm_window is None or x64_window is None:
        raise ValueError("private context unexpectedly finalized")
    compared = min(len(arm_window), len(x64_window))
    slot_start = arm["slot"]["window_relative_slot"][0]
    mask = []
    for index in range(compared):
        in_slot = slot_start <= index < slot_start + SLOT_BYTES
        mask.append(False if in_slot else arm_window[index] == x64_window[index])
    arm_scalars = {
        (item["relative_offset"], item["width"], item["role"], item["value"])
        for item in arm["neighbor_scalars"]
    }
    x64_scalars = {
        (item["relative_offset"], item["width"], item["role"], item["value"])
        for item in x64["neighbor_scalars"]
    }
    arm_descriptors = {
        (item["record_relative_offset"], item["record_width"], item["pointer_role"])
        for item in arm["neighbor_descriptor_candidates"]
    }
    x64_descriptors = {
        (item["record_relative_offset"], item["record_width"], item["pointer_role"])
        for item in x64["neighbor_descriptor_candidates"]
    }
    arm_anchors = {
        item["name"]: (item["width"], tuple(item["match_relative_offsets"]))
        for item in arm["upstream_named_anchor_context_checks"]
    }
    x64_anchors = {
        item["name"]: (item["width"], tuple(item["match_relative_offsets"]))
        for item in x64["upstream_named_anchor_context_checks"]
    }
    arm_half_counts = [
        (item["group"], item["half"], item["match_count"], item["count_limit_hit"])
        for item in arm["directed_public_half_scan"]["half_observations"]
    ]
    x64_half_counts = [
        (item["group"], item["half"], item["match_count"], item["count_limit_hit"])
        for item in x64["directed_public_half_scan"]["half_observations"]
    ]
    return {
        "slot_full32_equal": arm_slot == x64_slot,
        "slot_length_equal": len(arm_slot) == len(x64_slot) == SLOT_BYTES,
        "neighborhood": {
            "relative_window_bytes_compared": compared,
            "slot_excluded": True,
            "equal_count_outside_slot": sum(mask),
            "mismatch_count_outside_slot": compared - SLOT_BYTES - sum(mask),
            "equal_runs_relative": relative_runs(mask, 0, slot_start),
            "equality_mask_outside_slot": "".join("1" if item else "0" for item in mask),
        },
        "structural_same_fields": [
            "slot width and section",
            "known9 positional match mask",
            "bounded context byte profiles",
            "neighbor scalar/descriptor classifications",
        ],
        "neighbor_metadata_comparison": {
            "exact_scalar_candidate_intersection_count": len(arm_scalars & x64_scalars),
            "descriptor_shape_equal": arm_descriptors == x64_descriptors,
            "descriptor_shape_arm64": sorted(arm_descriptors),
            "descriptor_shape_x64": sorted(x64_descriptors),
            "named_algorithm_anchor_context_equal": arm_anchors == x64_anchors,
            "named_algorithm_anchor_context_arm64": arm_anchors,
            "named_algorithm_anchor_context_x64": x64_anchors,
            "relocation_context_entries_equal": arm["relocation_context"]["entries_in_context"]
            == x64["relocation_context"]["entries_in_context"],
            "directed_half_count_shape_equal": arm_half_counts == x64_half_counts,
            "interpretation_boundary": "matching scalar/anchor shape is a locator discriminator only; it does not prove a shared object or crypto consumer",
        },
    }


def source_references(contract_path: Path, upstream_source: Path, functional_source: Path | None) -> dict:
    return {
        "functional_memory_patcher": {
            "path_requested": "/home/adrian/wowserver/vendor/forever-patcher/src/patch.rs",
            "path_used": None if functional_source is None else str(functional_source),
            "facts": [
                {"function": "scan_stores", "lines": "93-98", "proven": "delegates to bounded inner memory scan"},
                {"function": "scan_stores_inner", "lines": "124-264", "proven": "finds anchor key in runtime memory and validates candidate stores"},
                {"function": "validate_block", "lines": "279-314", "proven": "requires 12 ordered ids, all 12 full 32-byte keys, flags, and padding"},
                {"function": "patch_stores", "lines": "322-336", "proven": "writes only target_group key through target_key_addr; anchor remains untouched"},
            ],
            "layout_from_source": {"entry_count": 12, "entry_size": 40, "key_offset_in_entry": 4, "key_width": 32, "target_group": 8, "target_key_offset_from_block": 284},
        },
        "contract": {
            "path": str(contract_path),
            "lines": "36-62",
            "facts": "entry_size=40, entry_count=12, target_group=8, anchor_group=1, known_keys=12, new_key=32 bytes",
        },
        "upstream_static_patterns": {
            "path": str(upstream_source),
            "lines": "42-57",
            "facts": "named Ed25519 pattern is an 8-byte prefix; this probe checks it only inside each +/-512-byte context",
        },
        "upstream_binary_primitive": {
            "path": str(upstream_source.parent.parent / "binary/mod.rs"),
            "lines": "64-88",
            "facts": "generic file patch primitive permits replacement longer than the locating pattern; this is not evidence that the static slot is a 32-byte object",
        },
        "upstream_key_config": {
            "path": str(upstream_source.parent.parent / "keys/mod.rs"),
            "lines": "90-118",
            "facts": "Ed25519 replacement input is validated as 32 bytes",
        },
    }


def find_functional_source() -> Path | None:
    return next((path for path in FUNCTIONAL_PATCHER_CANDIDATES if path.is_file()), None)


def analyze(arm_source: Path = ARM_SOURCE, x64_source: Path = X64_SOURCE, contract_path: Path = CONTRACT_SOURCE, upstream_source: Path = UPSTREAM_SOURCE) -> dict:
    contract = load_contract(contract_path)
    if not upstream_source.is_file():
        raise ValueError(f"upstream source missing: {upstream_source}")
    arm = analyze_image(arm_source, "arm64", contract, upstream_source)
    x64 = analyze_image(x64_source, "x64", contract, upstream_source)
    comparison = cross_architecture(arm, x64)
    finalize_image(arm, contract["_known9"])
    finalize_image(x64, contract["_known9"])
    half_findings = directed_findings(arm, x64)
    functional = find_functional_source()
    return {
        "schema": "wowforever.ed-slot-context.v1",
        "scope": {
            "host_only_read_only": True,
            "no_client_wine_adb_login_or_memory_access": True,
            "no_disassembly_or_code_reference_scan": True,
            "no_full_image_key_or_encoding_sweep": True,
            "only_slot_context": "+/-512 raw bytes per image plus one exact 16-byte contract-half scan of raw .rdata/.data",
            "max_context_bytes_per_image": MAX_CONTEXT_BYTES,
            "max_metadata_bytes_per_directory": MAX_METADATA_BYTES,
            "max_directed_data_bytes_per_image": MAX_DIRECTED_BYTES,
            "max_positions_per_half": MAX_MATCH_POSITIONS,
            "contract_halves_scanned": 24,
            "raw_slot_or_potential_key_bytes_emitted": False,
        },
        "inputs": {"arm64": arm_source.as_posix(), "x64": x64_source.as_posix(), "contract": contract_path.as_posix(), "upstream_patterns": upstream_source.as_posix()},
        "images": [arm, x64],
        "directed_half_findings": half_findings,
        "cross_architecture": comparison,
        "source_references": source_references(contract_path, upstream_source, functional),
        "assessment": {
            "x64_functional_memory_consumption_proven": "runtime 12-entry store, not static PE slot: anchor group 1 is used to locate and all 12 full keys/metadata validate the block; only group 8 key bytes are written at block+284",
            "static32_slot_as_functional_store_proven": False,
            "static32_slot_boundary_proven": False,
            "static32_slot_prefix_plus_adjacent_constant": "structural hypothesis only; bounded shape/equality evidence is reported without assigning a crypto role",
            "boundary_interpretation": "both copies have a one-byte zero immediately before the slot and a nonzero continuation after it; this argues against a zero-delimited isolated 32-byte object, but does not prove that the continuation is part of the constant rather than adjacent data",
            "directed_half_scan_interpretation": "exact public 16-byte half locations and pair distances only; no 32-byte assembly equivalence or compiler emission claim is inferred",
            "directed_half_result": half_findings["concise_result"],
            "group8_link": "not_proven",
            "crypto_role": "unknown; neither the known9 prefix nor invalid compressed-point status is treated as role proof",
            "what_this_adds_for_ghidra": "the exact .rdata window, boundary shape, known9 positional mask, and cross-architecture relative equality mask are a discriminator for locating the containing object/base; no consumer is inferred",
            "unknowns_and_boundaries": [
                "No runtime memory store was inspected.",
                "No static code reference or disassembly was performed.",
                "A nonzero run, vector alignment, descriptor-like scalar, or 8-byte anchor does not establish object ownership or cryptographic role.",
                "Relocation/resource metadata is bounded and may report a limit when the relevant directory exceeds 64 KiB.",
            ],
        },
        "new_discriminator": {
            "name": "ed-slot-context-v1",
            "components": [
                "slot RVA/raw offset and .rdata section bounds",
                "known9 32-position equality mask and contiguous equal runs",
                "zero/nonzero/printable boundary shape within +/-512",
                "neighbor scalar and 16-byte pointer/RVA/length descriptor metadata",
                "relocation/resource/version context",
                "cross-architecture neighborhood equality mask with slot excluded",
                "exact 16-byte public contract-half locations and distance-only pair observations",
            ],
            "not_a_key_or_encoding_identifier": True,
        },
    }


def scrub_private(value):
    if isinstance(value, dict):
        return {key: scrub_private(item) for key, item in value.items() if not key.startswith("_")}
    if isinstance(value, list):
        return [scrub_private(item) for item in value]
    return value


def create_report(path: Path, report: dict) -> None:
    payload = (json.dumps(scrub_private(report), indent=2, sort_keys=True) + "\n").encode("utf-8")
    if len(payload) > MAX_REPORT_BYTES:
        raise ValueError("report exceeds 2 MiB")
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            fd = -1
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        if fd != -1:
            os.close(fd)


def synthetic_checks() -> None:
    left = bytes(range(32))
    right = left[:8] + bytes((value + 1) % 256 for value in left[8:])
    result = positional_mask(left, right)
    assert result["equal_count"] == 8
    assert result["equal_runs"] == [[0, 8]]
    window = bytes([0] * 16 + [7] * 32 + [0] * 16)
    shape = slot_shape(window, 16)
    assert shape["zero_delimiter_on_both_sides"]
    assert shape["nonzero_containing_span_relative"] == [0, 32]
    report_path = Path("/dev/null")
    assert report_path.name == "null"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arm64-source", type=Path, default=ARM_SOURCE)
    parser.add_argument("--x64-source", type=Path, default=X64_SOURCE)
    parser.add_argument("--contract", type=Path, default=CONTRACT_SOURCE)
    parser.add_argument("--upstream-patterns", type=Path, default=UPSTREAM_SOURCE)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        synthetic_checks()
        print(json.dumps({"self_test": "passed"}))
        return 0
    if args.output is None:
        parser.error("--output is required unless --self-test is used")
    try:
        report = analyze(args.arm64_source, args.x64_source, args.contract, args.upstream_patterns)
        create_report(args.output, report)
    except (OSError, ValueError, json.JSONDecodeError, struct.error) as exc:
        print(f"EdSlotContext.py: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"output": str(args.output), "stable_full_hash": all(item["source"]["stable_full_hash"] for item in report["images"]), "slot_equal": report["cross_architecture"]["slot_full32_equal"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
