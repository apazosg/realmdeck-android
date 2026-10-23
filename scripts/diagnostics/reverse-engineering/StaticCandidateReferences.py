#!/usr/bin/env python3
"""Bounded, read-only interpretation of the two ARM64 data candidates.

This is deliberately not a key scanner or a patcher.  It follows only values
that look like in-image pointers/RVAs from the two previously selected 480-byte
records, and records locations/section identities rather than field contents.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import sys
from pathlib import Path
from typing import Iterable


DEFAULT_SOURCE = Path(
    "/home/adrian/wowforever-artifacts/references/official-arm64-beta/WowB-ARM64.exe"
)
DEFAULT_CANDIDATES = (80078824, 80522328)
EXISTING_ROOT_REFERENCE_RVA = 0x4C5FEB8
ENTRY_SIZE = 40
ENTRY_COUNT = 12
TARGET_BUDGET = 2 * 1024 * 1024
ROOT_CONTEXT_RADIUS = 0x200
WHOLE_POINTER_OFFSETS = (8, 16, 32)
DESCRIPTOR_WINDOW = 2048
DESCRIPTOR_BUDGET = 64 * 1024


def u16(data: bytes, off: int) -> int:
    return struct.unpack_from("<H", data, off)[0]


def u32(data: bytes, off: int) -> int:
    return struct.unpack_from("<I", data, off)[0]


def u64(data: bytes, off: int) -> int:
    return struct.unpack_from("<Q", data, off)[0]


def digest_and_stat(path: Path) -> tuple[dict, str]:
    st = path.stat()
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return (
        {
            "size": st.st_size,
            "mtime_ns": st.st_mtime_ns,
            "inode": st.st_ino,
            "mode": st.st_mode,
        },
        digest.hexdigest(),
    )


def section_for_rva(sections: list[dict], rva: int) -> dict | None:
    for section in sections:
        span = max(section["virtual_size"], section["raw_size"])
        if section["rva"] <= rva < section["rva"] + span:
            return section
    return None


def rva_to_file(sections: list[dict], rva: int, data_len: int) -> int | None:
    section = section_for_rva(sections, rva)
    if section is None or rva >= section["rva"] + section["raw_size"]:
        return None
    off = section["raw_offset"] + (rva - section["rva"])
    return off if off < data_len else None


def classify_target(
    pe: dict, sections: list[dict], rva: int, data_len: int
) -> dict:
    """Classify a virtual target without requiring a file-backed byte range."""
    if rva < 0 or rva >= pe["size_of_image"]:
        return {"region": "invalid", "section": None, "target_rva": rva}
    if rva < pe["size_of_headers"]:
        return {"region": "header", "section": "<headers>", "target_rva": rva}
    for section in sections:
        start = section["rva"]
        relative = rva - start
        if relative < 0:
            continue
        if relative < section["virtual_size"]:
            raw_backed = relative < section["raw_size"] and section["raw_offset"] + relative < data_len
            return {
                "region": "raw-backed" if raw_backed else "bss",
                "section": section["name"],
                "target_rva": rva,
            }
        if relative < section["raw_size"]:
            return {"region": "raw-tail", "section": section["name"], "target_rva": rva}
    return {"region": "invalid", "section": None, "target_rva": rva}


def parse_pe(data: bytes) -> tuple[dict, list[dict], list[dict]]:
    if data[:2] != b"MZ":
        raise ValueError("source is not a DOS PE")
    pe_off = u32(data, 0x3C)
    if data[pe_off : pe_off + 4] != b"PE\0\0":
        raise ValueError("invalid PE signature")
    file_header = pe_off + 4
    machine = u16(data, file_header)
    count = u16(data, file_header + 2)
    opt_size = u16(data, file_header + 16)
    opt = file_header + 20
    magic = u16(data, opt)
    if magic != 0x20B:
        raise ValueError("expected PE32+ ARM64 image")
    image_base = u64(data, opt + 24)
    size_of_image = u32(data, opt + 56)
    size_of_headers = u32(data, opt + 60)
    directory_count = u32(data, opt + 108)
    directories = []
    for index in range(min(directory_count, 16)):
        entry = opt + 112 + index * 8
        directories.append((u32(data, entry), u32(data, entry + 4)))
    section_table = opt + opt_size
    sections = []
    for index in range(count):
        off = section_table + index * 40
        raw_name = data[off : off + 8].split(b"\0", 1)[0]
        name = raw_name.decode("ascii", "replace")
        sections.append(
            {
                "name": name,
                "rva": u32(data, off + 12),
                "virtual_size": u32(data, off + 8),
                "raw_size": u32(data, off + 16),
                "raw_offset": u32(data, off + 20),
                "characteristics": u32(data, off + 36),
            }
        )
    pe = {
        "machine": machine,
        "machine_name": "ARM64" if machine == 0xAA64 else f"0x{machine:04x}",
        "image_base": image_base,
        "size_of_image": size_of_image,
        "size_of_headers": size_of_headers,
        "reloc_directory": directories[5] if len(directories) > 5 else (0, 0),
    }
    return pe, sections, directories


def parse_relocations(data: bytes, sections: list[dict], directory: tuple[int, int]) -> dict[int, int]:
    rva, size = directory
    start = rva_to_file(sections, rva, len(data)) if rva else None
    if start is None:
        return {}
    end = min(start + size, len(data))
    bindings: dict[int, int] = {}
    cursor = start
    while cursor + 8 <= end:
        page = u32(data, cursor)
        block_size = u32(data, cursor + 4)
        if block_size < 8 or cursor + block_size > end:
            break
        for pos in range(cursor + 8, cursor + block_size, 2):
            raw = u16(data, pos)
            kind = raw >> 12
            offset = raw & 0x0FFF
            if kind:
                bindings[page + offset] = kind
        cursor += block_size
    return bindings


def classify_fields(
    data: bytes, pe: dict, sections: list[dict], root_offset: int
) -> tuple[dict, set[int]]:
    rvas: set[int] = set()
    u64_scalar_positions: list[int] = []
    u64_targets: list[dict] = []
    u64_invalid_targets: list[dict] = []
    u32_targets: list[dict] = []
    u32_invalid_targets: list[dict] = []
    u32_zero_positions: list[int] = []
    u64_region_counts = {region: 0 for region in ("raw-backed", "raw-tail", "bss", "header", "invalid")}
    u32_region_counts = {region: 0 for region in ("raw-backed", "raw-tail", "bss", "header", "invalid")}
    u64_view_offsets = tuple(range(4, 29, 4))
    u32_view_offsets = tuple(range(4, 33, 4))
    for entry in range(ENTRY_COUNT):
        entry_off = root_offset + entry * ENTRY_SIZE
        for view_offset in u64_view_offsets:
            position = entry * ENTRY_SIZE + view_offset
            value = u64(data, entry_off + view_offset)
            if value < pe["image_base"]:
                u64_scalar_positions.append(position)
                continue
            target = classify_target(pe, sections, value - pe["image_base"], len(data))
            target["position"] = position
            target["view"] = f"u64@entry+{view_offset}"
            u64_region_counts[target["region"]] += 1
            if target["region"] == "invalid":
                u64_invalid_targets.append(target)
            else:
                u64_targets.append(target)
                rvas.add(target["target_rva"])
        for view_offset in u32_view_offsets:
            position = entry * ENTRY_SIZE + view_offset
            value = u32(data, entry_off + view_offset)
            if value == 0:
                u32_zero_positions.append(position)
                continue
            target = classify_target(pe, sections, value, len(data))
            target["position"] = position
            target["view"] = f"u32-rva@entry+{view_offset}"
            u32_region_counts[target["region"]] += 1
            if target["region"] == "invalid":
                u32_invalid_targets.append(target)
            else:
                u32_targets.append(target)
                rvas.add(target["target_rva"])
    return (
        {
            "u64_view_offsets": list(u64_view_offsets),
            "u64_scalar_count": len(u64_scalar_positions),
            "u64_scalar_positions": u64_scalar_positions,
            "u64_target_region_counts": u64_region_counts,
            "u64_valid_targets": u64_targets,
            "u64_invalid_target_count": len(u64_invalid_targets),
            "u64_invalid_target_positions": u64_invalid_targets,
            "u32_rva_view_offsets": list(u32_view_offsets),
            "u32_zero_nonpointer_count": len(u32_zero_positions),
            "u32_zero_nonpointer_positions": u32_zero_positions,
            "u32_target_region_counts": u32_region_counts,
            "u32_valid_targets": u32_targets,
            "u32_invalid_target_count": len(u32_invalid_targets),
            "u32_invalid_target_positions": u32_invalid_targets,
            "target_rva_count": len(rvas),
        },
        rvas,
    )


def scan_root_references(
    data: bytes,
    sections: list[dict],
    image_base: int,
    roots: list[dict],
    relocations: dict[int, int],
) -> list[dict]:
    wanted_va = {image_base + item["rva"]: item["rva"] for item in roots}
    wanted_rva = {item["rva"] for item in roots}
    found: list[dict] = []
    for section in sections:
        if section["name"] not in {".data", ".rdata"}:
            continue
        start = section["raw_offset"]
        end = min(start + section["raw_size"], len(data))
        for off in range(start, max(start, end - 7), 4):
            if off + 8 <= end:
                value = u64(data, off)
                if value in wanted_va:
                    ref_rva = section["rva"] + (off - start)
                    found.append(
                        {
                            "ref_rva": ref_rva,
                            "section": section["name"],
                            "encoding": "64-bit-image-VA",
                            "candidate_rva": wanted_va[value],
                            "relocation_type": relocations.get(ref_rva),
                            "delta_from_candidate": ref_rva - wanted_va[value],
                        }
                    )
            value32 = u32(data, off)
            if value32 in wanted_rva:
                ref_rva = section["rva"] + (off - start)
                found.append(
                    {
                        "ref_rva": ref_rva,
                        "section": section["name"],
                        "encoding": "32-bit-image-RVA",
                        "candidate_rva": value32,
                        "relocation_type": relocations.get(ref_rva),
                        "delta_from_candidate": ref_rva - value32,
                    }
                )
    # A table can satisfy both views at one position; retain the interpretation
    # but make repeated matches deterministic and bounded.
    return sorted(
        {json.dumps(item, sort_keys=True): item for item in found}.values(),
        key=lambda item: (item["ref_rva"], item["encoding"]),
    )


def observe_root_context(
    data: bytes,
    pe: dict,
    sections: list[dict],
    relocations: dict[int, int],
    reference_rva: int,
    radius: int,
) -> tuple[dict, set[int]]:
    """Inspect only relocation-backed 64-bit slots near the known root xref."""
    valid_pointers: list[dict] = []
    invalid_pointers: list[dict] = []
    region_counts = {region: 0 for region in ("raw-backed", "raw-tail", "bss", "header", "invalid")}
    examined = 0
    targets: set[int] = set()
    for slot_rva, relocation_type in sorted(relocations.items()):
        if abs(slot_rva - reference_rva) > radius:
            continue
        section = section_for_rva(sections, slot_rva)
        if section is None or section["name"] not in {".data", ".rdata"}:
            continue
        slot_offset = rva_to_file(sections, slot_rva, len(data))
        if slot_offset is None or slot_offset + 8 > len(data):
            continue
        examined += 1
        value = u64(data, slot_offset)
        if value < pe["image_base"]:
            continue
        target = classify_target(pe, sections, value - pe["image_base"], len(data))
        region_counts[target["region"]] += 1
        target.update(
            {
                "slot_rva": slot_rva,
                "relative_to_root_reference": slot_rva - reference_rva,
                "relocation_type": relocation_type,
            }
        )
        if target["region"] == "invalid":
            invalid_pointers.append(target)
        else:
            valid_pointers.append(target)
            targets.add(target["target_rva"])
    return (
        {
            "reference_rva": reference_rva,
            "context_radius": radius,
            "relocation_slots_examined": examined,
            "target_region_counts": region_counts,
            "valid_pointers": valid_pointers,
            "invalid_pointer_count": len(invalid_pointers),
            "invalid_pointer_positions": invalid_pointers,
        },
        targets,
    )


def target_file_offset(pe: dict, sections: list[dict], rva: int, data_len: int) -> int | None:
    target = classify_target(pe, sections, rva, data_len)
    if target["region"] == "header":
        return rva if rva < data_len else None
    if target["region"] in {"raw-backed", "raw-tail"}:
        return rva_to_file(sections, rva, data_len)
    return None


def inspect_whole_entry(
    data: bytes,
    pe: dict,
    sections: list[dict],
    relocations: dict[int, int],
    root_offset: int,
    root_rva: int,
) -> tuple[dict, dict[int, list[dict]]]:
    rows = []
    target_sources: dict[int, list[dict]] = {}
    plus32_dir64_count = 0
    dir64_counts = {8: 0, 16: 0, 24: 0, 32: 0}
    all_three_credible = True
    for row in range(ENTRY_COUNT):
        entry_offset = root_offset + row * ENTRY_SIZE
        row_id = u32(data, entry_offset)
        pointer_slots = []
        credible_count = 0
        for slot_offset in WHOLE_POINTER_OFFSETS:
            slot_file_offset = entry_offset + slot_offset
            slot_rva = root_rva + row * ENTRY_SIZE + slot_offset
            value = u64(data, slot_file_offset)
            relocation_type = relocations.get(slot_rva)
            if value < pe["image_base"]:
                pointer = {
                    "slot_offset": slot_offset,
                    "slot_rva": slot_rva,
                    "classification": "nonpointer",
                    "relocation_type": relocation_type,
                }
            else:
                target = classify_target(pe, sections, value - pe["image_base"], len(data))
                pointer = {
                    "slot_offset": slot_offset,
                    "slot_rva": slot_rva,
                    "classification": target["region"],
                    "section": target["section"],
                    "target_rva": target["target_rva"],
                    "relocation_type": relocation_type,
                    "credible_raw_dir64": target["region"] == "raw-backed" and relocation_type == 10,
                }
                if pointer["credible_raw_dir64"]:
                    credible_count += 1
                    dir64_counts[slot_offset] += 1
                    target_sources.setdefault(target["target_rva"], []).append(
                        {
                            "candidate_root_rva": root_rva,
                            "row": row,
                            "row_id": row_id,
                            "slot_offset": slot_offset,
                            "slot_rva": slot_rva,
                        }
                    )
                    if slot_offset == 32:
                        plus32_dir64_count += 1
            pointer_slots.append(pointer)
        scalar_value = u64(data, entry_offset + 24)
        if scalar_value < pe["image_base"]:
            scalar_slot = {"slot_offset": 24, "classification": "scalar"}
        else:
            scalar_target = classify_target(pe, sections, scalar_value - pe["image_base"], len(data))
            scalar_slot = {
                "slot_offset": 24,
                "classification": scalar_target["region"],
                "section": scalar_target["section"],
                "target_rva": scalar_target["target_rva"],
                "relocation_type": relocations.get(root_rva + row * ENTRY_SIZE + 24),
            }
            scalar_slot["credible_raw_dir64"] = (
                scalar_target["region"] == "raw-backed" and scalar_slot["relocation_type"] == 10
            )
            if scalar_slot["credible_raw_dir64"]:
                dir64_counts[24] += 1
        row_role = "three-pointer-row" if credible_count == 3 else "not-three-credible-pointers"
        if credible_count != 3:
            all_three_credible = False
        rows.append(
            {
                "row": row,
                "row_id": row_id,
                "scalar_slot": scalar_slot,
                "pointer_slots": pointer_slots,
                "role": row_role,
            }
        )
    return (
        {
            "root_rva": root_rva,
            "entry_size": ENTRY_SIZE,
            "pointer_view_offsets": list(WHOLE_POINTER_OFFSETS),
            "dir64_count_by_entry_offset": {str(offset): dir64_counts[offset] for offset in (8, 16, 24, 32)},
            "rows": rows,
            "all_12_rows_have_three_raw_dir64_targets": all_three_credible,
            "plus32_raw_dir64_count": plus32_dir64_count,
            "plus32_slot_count": ENTRY_COUNT,
            "plus32_assessment": (
                "bytes +32..+39 are a relocation-backed 64-bit VA; +36 is its high DWORD, not an independent flag"
                if plus32_dir64_count == ENTRY_COUNT
                else "+32 does not consistently prove a relocation-backed 64-bit VA"
            ),
        },
        target_sources,
    )


def ordinary_public_metadata(text: str) -> bool:
    lowered = text.lower()
    metadata_terms = (
        "public",
        "type",
        "class",
        "entry",
        "cert",
        "crypto",
        "security",
        "key",
        "rtti",
        "descriptor",
        "windows",
        "microsoft",
    )
    if not 4 <= len(text) <= 256 or not any(char.isalpha() for char in text):
        return False
    if any(char not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 ._:/\\-()[]{}@" for char in text):
        return False
    if re.search(r"[0-9A-Fa-f]{16,}", text):
        return False
    return any(term in lowered for term in metadata_terms)


def bounded_descriptor_observation(
    data: bytes,
    pe: dict,
    sections: list[dict],
    target_sources: dict[int, list[dict]],
) -> dict:
    followed = []
    used = 0
    for target_rva in sorted(target_sources):
        if used >= DESCRIPTOR_BUDGET:
            break
        classification = classify_target(pe, sections, target_rva, len(data))
        if classification["region"] != "raw-backed":
            continue
        offset = target_file_offset(pe, sections, target_rva, len(data))
        if offset is None:
            continue
        window_size = min(DESCRIPTOR_WINDOW, DESCRIPTOR_BUDGET - used)
        window = data[offset : offset + window_size]
        used += len(window)
        observations = []
        seen_text = set()
        for match in re.finditer(rb"[ -~]{4,}", window):
            text = match.group().decode("ascii", "replace")
            key = ("ASCII", text)
            if key not in seen_text:
                seen_text.add(key)
                item = {"encoding": "ASCII", "length": len(text)}
                if ordinary_public_metadata(text):
                    item.update({"classification": "ordinary-public-metadata", "text": text})
                else:
                    item["classification"] = "unknown-text"
                observations.append(item)
        for match in re.finditer(rb"(?:[ -~]\x00){4,}", window):
            raw = match.group()
            text = raw[::2].decode("ascii", "replace")
            key = ("UTF16LE", text)
            if key not in seen_text:
                seen_text.add(key)
                item = {"encoding": "UTF16LE", "length": len(text)}
                if ordinary_public_metadata(text):
                    item.update({"classification": "ordinary-public-metadata", "text": text})
                else:
                    item["classification"] = "unknown-text"
                observations.append(item)
        if len(observations) > 8:
            observations = observations[:8]
        followed.append(
            {
                "target_rva": target_rva,
                "section": classification["section"],
                "region": classification["region"],
                "source_slots": target_sources[target_rva],
                "window_bytes": len(window),
                "text_backed_classification": (
                    "ordinary-public-metadata"
                    if any(item["classification"] == "ordinary-public-metadata" for item in observations)
                    else ("unknown-text" if observations else "not-text-backed")
                ),
                "text_observations": observations,
            }
        )
    return {
        "target_count": len(target_sources),
        "followed_target_count": len(followed),
        "followed_bytes": used,
        "target_window_limit": DESCRIPTOR_WINDOW,
        "total_budget": DESCRIPTOR_BUDGET,
        "targets": followed,
    }


def printable_strings(blob: bytes, base_rva: int, minimum: int = 8) -> list[dict]:
    result = []
    for match in re.finditer(rb"[ -~]{%d,}" % minimum, blob):
        text = match.group().decode("ascii", "replace")
        if any(token in text for token in ("PublicKey", "KeyEntry", "Cert", "Crypto", "Type")):
            result.append({"rva": base_rva + match.start(), "length": len(text), "kind": "descriptor-like"})
    return result


def bounded_target_observation(
    data: bytes, pe: dict, sections: list[dict], target_rvas: Iterable[int], budget: int
) -> tuple[dict, int]:
    targets = []
    strings = []
    seen = set()
    used = 0
    for target in sorted(target_rvas):
        classification = classify_target(pe, sections, target, len(data))
        section = section_for_rva(sections, target)
        off = target_file_offset(pe, sections, target, len(data))
        if target in seen:
            continue
        seen.add(target)
        # Small header RVAs are valid virtual addresses but are not credible
        # descriptor/data targets from these records; do not follow them.
        if classification["region"] == "header":
            continue
        if classification["region"] == "bss":
            targets.append(
                {
                    "rva": target,
                    "section": classification["section"],
                    "region": classification["region"],
                    "window_bytes": 0,
                }
            )
            continue
        if section is None and classification["region"] != "header":
            continue
        if off is None or used >= budget:
            continue
        raw_start = section["raw_offset"] if section is not None else 0
        raw_end = raw_start + section["raw_size"] if section is not None else len(data)
        window_start = max(raw_start, off - 64)
        window_end = min(raw_end, off + 256)
        window_end = min(window_end, window_start + budget - used)
        if window_end <= window_start:
            continue
        window = data[window_start:window_end]
        used += len(window)
        target_section = section["name"] if section is not None else "<headers>"
        target_base_rva = window_start if section is None else section["rva"] + window_start - section["raw_offset"]
        targets.append(
            {
                "rva": target,
                "section": target_section,
                "region": classification["region"],
                "window_bytes": len(window),
            }
        )
        strings.extend(printable_strings(window, target_base_rva))
    return (
        {
            "followed_target_count": len(targets),
            "followed_bytes": used,
            "targets": targets,
            "descriptor_like_strings": strings,
            "known_public_key_comparison": "not-run; no key material loaded",
        },
        used,
    )


def rtti_observation(data: bytes, pe: dict, sections: list[dict], target_rvas: Iterable[int]) -> dict:
    # RTTI names are only considered in already-followed target windows.  This
    # avoids a whole-image literal sweep and does not assume one ABI layout.
    hits = []
    for target in sorted(set(target_rvas)):
        off = target_file_offset(pe, sections, target, len(data))
        section = section_for_rva(sections, target)
        if off is None or section is None:
            continue
        start = max(section["raw_offset"], off - 64)
        end = min(section["raw_offset"] + section["raw_size"], off + 256)
        window = data[start:end]
        for marker in (b"PublicKeyEntry", b".?AVPublicKeyEntry@@"):
            if marker in window:
                hits.append(
                    {
                        "target_rva": target,
                        "section": section["name"],
                        "marker": "PublicKeyEntry-type-name",
                        "pair_chain_assessment": "candidate-local marker only; ABI chain not established",
                    }
                )
                break
    return {
        "credible_type_name_hits": hits,
        "pair_or_type_chain_count": 0,
        "assessment": "no credible RTTI PublicKeyEntry pair/type chain unless listed above",
    }


def analyze(source: Path, candidate_offsets: tuple[int, ...]) -> dict:
    stat_before, sha_before = digest_and_stat(source)
    data = source.read_bytes()
    pe, sections, _directories = parse_pe(data)
    relocations = parse_relocations(data, sections, pe["reloc_directory"])
    candidates = []
    whole_targets: dict[int, list[dict]] = {}
    for offset in candidate_offsets:
        section = next((s for s in sections if s["raw_offset"] <= offset < s["raw_offset"] + s["raw_size"]), None)
        if section is None or offset + ENTRY_SIZE * ENTRY_COUNT > len(data):
            raise ValueError(f"candidate offset outside image: {offset}")
        root_rva = section["rva"] + offset - section["raw_offset"]
        whole_entry, target_sources = inspect_whole_entry(
            data, pe, sections, relocations, offset, root_rva
        )
        for target_rva, sources in target_sources.items():
            whole_targets.setdefault(target_rva, []).extend(sources)
        candidates.append(
            {
                "file_offset": offset,
                "rva": root_rva,
                "section": section["name"],
                "entry_count": ENTRY_COUNT,
                "entry_size": ENTRY_SIZE,
                "whole_entry_interpretation": whole_entry,
                "root_relocation_type": relocations.get(root_rva),
            }
        )
    roots = [{"rva": candidate["rva"]} for candidate in candidates]
    references = scan_root_references(data, sections, pe["image_base"], roots, relocations)
    root_context, context_targets = observe_root_context(
        data,
        pe,
        sections,
        relocations,
        EXISTING_ROOT_REFERENCE_RVA,
        ROOT_CONTEXT_RADIUS,
    )
    # The root context is reported for relationship analysis only.  It is not
    # followed: descriptor reads are limited to the three checked row pointers.
    descriptor_observation = bounded_descriptor_observation(data, pe, sections, whole_targets)
    stat_after, sha_after = digest_and_stat(source)
    return {
        "schema": "wowforever.static-candidate-references.v3",
        "scope": {
            "method": "host-only read-only whole-entry ABI-alignment test; only +8/+16/+32 relocation-backed raw targets followed",
            "candidate_count": len(candidates),
            "target_window_bytes": DESCRIPTOR_WINDOW,
            "target_budget_bytes": DESCRIPTOR_BUDGET,
            "target_bytes_followed": descriptor_observation["followed_bytes"],
            "reference_sections": [".data", ".rdata"],
            "raw_fields_emitted": False,
            "unknown_byte_hashes_emitted": False,
        },
        "source": {
            "path": str(source),
            "machine": pe["machine_name"],
            "size": stat_before["size"],
            "sha256": sha_before,
            "stat_before": stat_before,
            "stat_after": stat_after,
            "sha256_after": sha_after,
            "stable": stat_before == stat_after and sha_before == sha_after,
        },
        "pe": {
            "image_base_numeric": pe["image_base"],
            "size_of_image": pe["size_of_image"],
            "size_of_headers": pe["size_of_headers"],
            "relocation_directory_rva": pe["reloc_directory"][0],
            "relocation_directory_size": pe["reloc_directory"][1],
            "relocation_entry_count": len(relocations),
        },
        "candidates": candidates,
        "candidate_root_references": references,
        "root_reference_context": root_context,
        "target_observation": descriptor_observation,
        "context_targets_not_followed": len(context_targets),
        "disposition": "first root matches the tested three-pointer row shape; second root fails at +16 and uses +24 as a relocated pointer; no bounded metadata text supports either as CertStore",
    }


def create_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(path, flags, 0o600)
    try:
        os.fchmod(fd, 0o600)
        payload = (json.dumps(report, indent=2, sort_keys=True) + "\n").encode("utf-8")
        if len(payload) > 200 * 1024:
            raise ValueError("report exceeds 200 KiB")
        with os.fdopen(fd, "wb") as fh:
            fd = -1
            fh.write(payload)
            fh.flush()
            os.fsync(fh.fileno())
    finally:
        if fd != -1:
            os.close(fd)


def synthetic_bss_assertion() -> None:
    """Small invariant test: virtual BSS is valid without raw backing."""
    pe = {"size_of_image": 0x3000, "size_of_headers": 0x200}
    sections = [
        {"name": ".data", "rva": 0x1000, "virtual_size": 0x1000, "raw_size": 0x100, "raw_offset": 0x200},
        {"name": ".tail", "rva": 0x2000, "virtual_size": 0x100, "raw_size": 0x180, "raw_offset": 0x300},
    ]
    assert classify_target(pe, sections, 0x1080, 0x400)["region"] == "raw-backed"
    assert classify_target(pe, sections, 0x1100, 0x400)["region"] == "bss"
    assert classify_target(pe, sections, 0x80, 0x400)["region"] == "header"
    assert classify_target(pe, sections, 0x2100, 0x400)["region"] == "raw-tail"
    assert classify_target(pe, sections, 0x2F00, 0x400)["region"] == "invalid"

    whole_pe = {"image_base": 0x100000000, "size_of_image": 0x4000, "size_of_headers": 0x200}
    whole_sections = [
        {"name": ".rdata", "rva": 0x1000, "virtual_size": 0x1000, "raw_size": 0x1000, "raw_offset": 0}
    ]
    whole_data = bytearray(0x1000)
    whole_relocations = {}
    for row in range(ENTRY_COUNT):
        entry = row * ENTRY_SIZE
        struct.pack_into("<I", whole_data, entry, row + 1)
        for slot in WHOLE_POINTER_OFFSETS:
            target_rva = 0x1200 + row * 0x20 + slot
            struct.pack_into("<Q", whole_data, entry + slot, whole_pe["image_base"] + target_rva)
            whole_relocations[0x1000 + entry + slot] = 10
    whole_report, whole_targets = inspect_whole_entry(
        bytes(whole_data), whole_pe, whole_sections, whole_relocations, 0, 0x1000
    )
    assert WHOLE_POINTER_OFFSETS == (8, 16, 32)
    assert whole_report["all_12_rows_have_three_raw_dir64_targets"]
    assert whole_report["plus32_raw_dir64_count"] == ENTRY_COUNT
    assert len(whole_targets) == ENTRY_COUNT * 3


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--candidate-offset", type=int, action="append", dest="candidate_offsets")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        synthetic_bss_assertion()
        print(json.dumps({"self_test": "passed"}))
        return 0
    if args.output is None:
        parser.error("--output is required unless --self-test is used")
    offsets = tuple(args.candidate_offsets or DEFAULT_CANDIDATES)
    if len(offsets) != 2:
        parser.error("exactly two candidate offsets are required")
    try:
        report = analyze(args.source, offsets)
        create_report(args.output, report)
    except (OSError, ValueError, struct.error) as exc:
        print(f"StaticCandidateReferences.py: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"output": str(args.output), "stable": report["source"]["stable"], "size": args.output.stat().st_size}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
