#!/usr/bin/env python3
"""Bounded, host-only consumer investigation for the supplied static Ed slot.

This probe is intentionally narrower than the earlier string, primitive, and
12-by-40 investigations.  It examines only the exact 32-byte slot supplied for
the ARM64 and x64 source copies, reverse references to that slot (and one
possible data-pointer indirection), and at most a 2 KiB context window around a
concrete code reference.  It never emits slot bytes, key material, or process
data, and it never modifies an input PE.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import os
import struct
import sys
from pathlib import Path


ARM_SOURCE = Path(
    "/home/adrian/wowforever-artifacts/work/"
    "wow-patcher-arm64-20261013T182540Z/arm64/WowB-ARM64.exe"
)
X64_SOURCE = Path(
    "/home/adrian/wowforever-artifacts/work/"
    "wow-patcher-arm64-20261013T182540Z/x64/WowB.exe"
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
MAX_REVERSE_LEVELS = 2
MAX_SLOTS = 64
MAX_NODES = 128
MAX_FOLLOWED_BYTES = 1024 * 1024
MAX_METADATA_BYTES = 16 * 1024 * 1024
MAX_CONTEXT_BYTES = 2048


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def u64(data: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


def digest_and_stat(path: Path) -> tuple[dict, str]:
    stat = path.stat()
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {
        "size": stat.st_size,
        "mtime_ns": stat.st_mtime_ns,
        "inode": stat.st_ino,
        "mode": stat.st_mode,
    }, digest.hexdigest()


def parse_pe(data: bytes) -> tuple[dict, list[dict]]:
    if data[:2] != b"MZ":
        raise ValueError("source is not a DOS PE")
    nt = u32(data, 0x3C)
    if data[nt : nt + 4] != b"PE\0\0":
        raise ValueError("invalid PE signature")
    file_header = nt + 4
    machine = u16(data, file_header)
    count = u16(data, file_header + 2)
    optional_size = u16(data, file_header + 16)
    optional = file_header + 20
    if u16(data, optional) != 0x20B:
        raise ValueError("expected PE32+")
    directories = []
    for index in range(min(u32(data, optional + 108), 16)):
        entry = optional + 112 + index * 8
        directories.append((u32(data, entry), u32(data, entry + 4)))
    sections = []
    table = optional + optional_size
    for index in range(count):
        offset = table + index * 40
        sections.append(
            {
                "name": data[offset : offset + 8].split(b"\0", 1)[0].decode("ascii", "replace"),
                "rva": u32(data, offset + 12),
                "virtual_size": u32(data, offset + 8),
                "raw_size": u32(data, offset + 16),
                "raw_offset": u32(data, offset + 20),
                "characteristics": u32(data, offset + 36),
            }
        )
    return {
        "machine": machine,
        "image_base": u64(data, optional + 24),
        "size_of_image": u32(data, optional + 56),
        "size_of_headers": u32(data, optional + 60),
        "directories": directories,
    }, sections


def section_for(sections: list[dict], rva: int) -> dict | None:
    for section in sections:
        span = max(section["virtual_size"], section["raw_size"])
        if section["rva"] <= rva < section["rva"] + span:
            return section
    return None


def rva_to_file(sections: list[dict], rva: int, length: int = 1) -> int | None:
    section = section_for(sections, rva)
    if section is None or rva < section["rva"]:
        return None
    relative = rva - section["rva"]
    if relative < 0 or relative + length > section["raw_size"]:
        return None
    offset = section["raw_offset"] + relative
    return offset if offset + length <= section["raw_offset"] + section["raw_size"] else None


def section_name(sections: list[dict], rva: int) -> str | None:
    section = section_for(sections, rva)
    return None if section is None else section["name"]


def executable(sections: list[dict], rva: int) -> bool:
    section = section_for(sections, rva)
    return bool(section and section["characteristics"] & 0x20000000)


def parse_relocations(data: bytes, pe: dict, sections: list[dict]) -> dict[int, int]:
    directories = pe["directories"]
    rva, size = directories[5] if len(directories) > 5 else (0, 0)
    start = rva_to_file(sections, rva) if rva else None
    if start is None:
        return {}
    end = min(len(data), start + min(size, MAX_METADATA_BYTES))
    result = {}
    cursor = start
    while cursor + 8 <= end:
        page = u32(data, cursor)
        block_size = u32(data, cursor + 4)
        if block_size < 8 or cursor + block_size > end:
            break
        for position in range(cursor + 8, cursor + block_size, 2):
            raw = u16(data, position)
            if raw >> 12:
                result[page + (raw & 0xFFF)] = raw >> 12
        cursor += block_size
    return result


def parse_functions(data: bytes, pe: dict, sections: list[dict]) -> tuple[list[dict], dict]:
    directories = pe["directories"]
    rva, size = directories[3] if len(directories) > 3 else (0, 0)
    start = rva_to_file(sections, rva) if rva else None
    metadata = {"rva": rva, "declared_size": size, "bytes_examined": 0, "truncated": False}
    if start is None:
        return [], metadata
    bounded = min(size, MAX_METADATA_BYTES, len(data) - start)
    metadata.update({"bytes_examined": bounded, "truncated": size > bounded})
    machine = pe["machine"]
    record_size = 8 if machine == 0xAA64 else 12 if machine == 0x8664 else 0
    if record_size == 0:
        return [], metadata
    functions = []
    for cursor in range(start, start + bounded - record_size + 1, record_size):
        begin = u32(data, cursor)
        if machine == 0x8664:
            end = u32(data, cursor + 4)
            kind = "x64-runtime-function"
        else:
            unwind = u32(data, cursor + 4)
            flag = unwind & 3
            if flag in (1, 2):
                end = begin + ((unwind >> 2) & 0x7FF) * 4
                kind = "arm64-packed" if flag == 1 else "arm64-packed-fragment"
            elif flag == 0:
                xdata = unwind & ~3
                xoffset = rva_to_file(sections, xdata, 4)
                if xoffset is None:
                    continue
                end = begin + (u32(data, xoffset) & 0x3FFFF) * 4
                kind = "arm64-xdata"
            else:
                continue
        if end <= begin or end - begin > MAX_METADATA_BYTES:
            continue
        if not executable(sections, begin) or not executable(sections, end - 1):
            continue
        functions.append({"begin": begin, "end": end, "kind": kind})
    functions.sort(key=lambda item: (item["begin"], item["end"]))
    return functions, metadata


def function_for(functions: list[dict], rva: int) -> dict | None:
    begins = [item["begin"] for item in functions]
    index = bisect.bisect_right(begins, rva) - 1
    if index < 0:
        return None
    candidates = []
    while index >= 0 and functions[index]["begin"] <= rva:
        item = functions[index]
        if rva < item["end"]:
            candidates.append(item)
        index -= 1
    return min(candidates, key=lambda item: item["end"] - item["begin"]) if candidates else None


def arm_address_target(pc_rva: int, word: int) -> tuple[int, str] | None:
    imm21 = (((word >> 5) & 0x7FFFF) << 2) | ((word >> 29) & 3)
    signed = (imm21 ^ (1 << 20)) - (1 << 20)
    if word & 0x9F000000 == 0x10000000:
        return pc_rva + signed, "ADR"
    if word & 0x9F000000 == 0x90000000:
        return (pc_rva & ~0xFFF) + signed * 0x1000, "ADRP"
    if word & 0x3B000000 == 0x18000000:
        imm19 = (word >> 5) & 0x7FFFF
        signed19 = (imm19 ^ (1 << 18)) - (1 << 18)
        return pc_rva + signed19 * 4, "LDR-literal"
    return None


def arm_memory_access(word: int) -> tuple[int, int, int, str] | None:
    size_code = (word >> 30) & 3
    width = 1 << size_code
    rn = (word >> 5) & 31
    if word & 0x3B000000 == 0x39000000:
        immediate = ((word >> 10) & 0xFFF) * width
        return rn, immediate, width, "load" if word & 0x00400000 else "store"
    if word & 0x3B200000 == 0x38000000:
        immediate = (word >> 12) & 0x1FF
        immediate = (immediate ^ 0x100) - 0x100
        return rn, immediate, width, "load" if word & 0x00400000 else "store"
    return None


def arm_add_immediate(word: int) -> tuple[int, int, int] | None:
    if word & 0x7F000000 != 0x11000000:
        return None
    rd = word & 31
    rn = (word >> 5) & 31
    immediate = (word >> 10) & 0xFFF
    if word & 0x00400000:
        immediate <<= 12
    if word & 0x40000000:
        immediate = -immediate
    return rd, rn, immediate


def target_match(targets: dict[int, dict], address: int, width: int) -> dict | None:
    for item in targets.values():
        if address < item["start"] + item["span"] and address + width > item["start"]:
            return item
    return None


def page_target_match(targets: dict[int, dict], page: int) -> dict | None:
    for item in targets.values():
        if item["start"] & ~0xFFF == page:
            return item
    return None


def context(function: dict, instruction_rva: int) -> dict:
    half = MAX_CONTEXT_BYTES // 2
    start = max(function["begin"], instruction_rva - half)
    end = min(function["end"], start + MAX_CONTEXT_BYTES)
    start = max(function["begin"], end - MAX_CONTEXT_BYTES)
    return {"start_rva": f"0x{start:x}", "end_rva": f"0x{end:x}", "bytes": end - start}


def make_hit(function: dict, consumer_rva: int, formation_rva: int | None, target: dict, width: int, form: str, kind: str) -> dict:
    return {
        "function": {
            "begin_rva": f"0x{function['begin']:x}",
            "end_rva": f"0x{function['end']:x}",
            "kind": function["kind"],
            "pdata_exact_body": True,
        },
        "formation_rva": None if formation_rva is None else f"0x{formation_rva:x}",
        "consumer_rva": f"0x{consumer_rva:x}",
        "target_kind": target["kind"],
        "target_source_rva": f"0x{target['source_rva']:x}",
        "target_offset": target["start"] - target["source_rva"],
        "access_width": width,
        "access_kind": kind,
        "instruction_form": form,
        "context": context(function, consumer_rva),
        "reverse_level": 1 if target["kind"] == "new_static_ed_slot" else 2,
    }


def scan_arm(data: bytes, sections: list[dict], functions: list[dict], targets: dict[int, dict]) -> dict:
    consumers, formations = [], []
    seen_consumers, seen_formations = set(), set()
    scanned = 0
    for function in functions:
        registers: dict[int, tuple[int, int]] = {}
        for pc in range(function["begin"], function["end"], 4):
            offset = rva_to_file(sections, pc, 4)
            if offset is None:
                continue
            scanned += 4
            word = u32(data, offset)
            address_target = arm_address_target(pc, word)
            if address_target is not None:
                target, form = address_target
                matching = target_match(targets, target, 1)
                page_matching = page_target_match(targets, target & ~0xFFF)
                if matching is not None and form != "ADRP":
                    key = (function["begin"], pc, matching["source_rva"], "formation")
                    if key not in seen_formations and len(formations) < MAX_SLOTS:
                        seen_formations.add(key)
                        formations.append({
                            "function_begin_rva": f"0x{function['begin']:x}",
                            "instruction_rva": f"0x{pc:x}",
                            "target_source_rva": f"0x{matching['source_rva']:x}",
                            "instruction_form": form,
                            "role": "address_formation_only",
                        })
                if form == "LDR-literal" and matching is not None:
                    width = 8 if word & 0x40000000 else 4
                    key = (function["begin"], pc, matching["source_rva"], "literal-load")
                    if key not in seen_consumers and len(consumers) < MAX_SLOTS:
                        seen_consumers.add(key)
                        consumers.append(make_hit(
                            function, pc, None, matching, width, form, "load",
                        ))
                if page_matching is not None and form == "ADRP":
                    registers[word & 31] = (target, pc)
                elif form == "ADR" and matching is not None:
                    registers[word & 31] = (target, pc)
            add = arm_add_immediate(word)
            if add is not None:
                rd, rn, immediate = add
                previous = registers.get(rn)
                if previous is None:
                    registers.pop(rd, None)
                else:
                    registers[rd] = (previous[0] + immediate, previous[1])
                    matching = target_match(targets, registers[rd][0], 1)
                    if matching is not None and len(formations) < MAX_SLOTS:
                        key = (function["begin"], pc, matching["source_rva"], "add")
                        if key not in seen_formations:
                            seen_formations.add(key)
                            formations.append({
                                "function_begin_rva": f"0x{function['begin']:x}",
                                "instruction_rva": f"0x{pc:x}",
                                "target_source_rva": f"0x{matching['source_rva']:x}",
                                "instruction_form": "ADRP/ADR+ADD",
                                "role": "address_formation_only",
                            })
            memory = arm_memory_access(word)
            if memory is not None:
                rn, immediate, width, access_kind = memory
                previous = registers.get(rn)
                if previous is not None:
                    effective, formation = previous[0] + immediate, previous[1]
                    matching = target_match(targets, effective, width)
                    if matching is not None and pc - previous[1] <= MAX_CONTEXT_BYTES:
                        key = (function["begin"], pc, formation, matching["source_rva"], access_kind)
                        if key not in seen_consumers and len(consumers) < MAX_SLOTS:
                            seen_consumers.add(key)
                            consumers.append(make_hit(
                                function, pc, formation, matching, width,
                                "ADRP/ADR+LDR-or-STR", access_kind,
                            ))
                registers.pop(word & 31, None)
            if word & 0x7C000000 in (0x14000000, 0x94000000) or word & 0xFF000010 == 0x54000000:
                registers.clear()
    return {
        "architecture": "ARM64",
        "functions_examined": len(functions),
        "instruction_bytes_scanned": scanned,
        "consumer_references": consumers,
        "address_formation_only": formations,
        "reference_limit_hit": len(consumers) >= MAX_SLOTS or len(formations) >= MAX_SLOTS,
    }


def decode_x64_rip(data: bytes, sections: list[dict], rva: int) -> dict | None:
    offset = rva_to_file(sections, rva, 16)
    if offset is None:
        return None
    prefix = 0
    rex = 0
    if 0x40 <= data[offset] <= 0x4F:
        rex = data[offset]
        prefix = 1
    opcode = data[offset + prefix]
    if opcode in (0x8A, 0x88, 0x8B, 0x89, 0x8D):
        modrm = data[offset + prefix + 1]
        if modrm & 0xC7 != 0x05:
            return None
        displacement = struct.unpack_from("<i", data, offset + prefix + 2)[0]
        width = 1 if opcode in (0x8A, 0x88) else 8 if rex & 8 else 4
        return {
            "target": rva + prefix + 6 + displacement,
            "width": width,
            "kind": "formation" if opcode == 0x8D else "load" if opcode in (0x8A, 0x8B) else "store",
            "form": "RIP-relative",
        }
    # Common 128-bit loads/stores used when a 32-byte constant is consumed in
    # two vector operations.  This remains an exact RIP target check, not a
    # SIMD/crypto signature scan.
    if data[offset] in (0xF2, 0xF3, 0x66) and data[offset + 1] == 0x0F:
        opcode = data[offset + 2]
        modrm = data[offset + 3]
        if opcode in (0x6F, 0x7F) and modrm & 0xC7 == 0x05:
            displacement = struct.unpack_from("<i", data, offset + 4)[0]
            return {
                "target": rva + 8 + displacement,
                "width": 16,
                "kind": "load" if opcode == 0x6F else "store",
                "form": "RIP-relative-SIMD",
            }
    if data[offset] == 0x0F and data[offset + 1] in (0x10, 0x11):
        modrm = data[offset + 2]
        if modrm & 0xC7 == 0x05:
            displacement = struct.unpack_from("<i", data, offset + 3)[0]
            return {
                "target": rva + 7 + displacement,
                "width": 16,
                "kind": "load" if data[offset + 1] == 0x10 else "store",
                "form": "RIP-relative-SIMD",
            }
    return None


def scan_x64(data: bytes, sections: list[dict], functions: list[dict], targets: dict[int, dict]) -> dict:
    consumers, formations = [], []
    seen_consumers, seen_formations = set(), set()
    scanned = 0
    for function in functions:
        for pc in range(function["begin"], function["end"]):
            decoded = decode_x64_rip(data, sections, pc)
            scanned += 1
            if decoded is None:
                continue
            matching = target_match(targets, decoded["target"], decoded["width"])
            if matching is None:
                continue
            if decoded["kind"] == "formation":
                key = (function["begin"], pc, matching["source_rva"])
                if key not in seen_formations and len(formations) < MAX_SLOTS:
                    seen_formations.add(key)
                    formations.append({
                        "function_begin_rva": f"0x{function['begin']:x}",
                        "instruction_rva": f"0x{pc:x}",
                        "target_source_rva": f"0x{matching['source_rva']:x}",
                        "instruction_form": decoded["form"],
                        "role": "address_formation_only",
                    })
                continue
            key = (function["begin"], pc, matching["source_rva"], decoded["kind"])
            if key in seen_consumers or len(consumers) >= MAX_SLOTS:
                continue
            seen_consumers.add(key)
            consumers.append(make_hit(
                function, pc, None, matching, decoded["width"], decoded["form"], decoded["kind"],
            ))
    return {
        "architecture": "x64",
        "functions_examined": len(functions),
        "instruction_bytes_scanned": scanned,
        "consumer_references": consumers,
        "address_formation_only": formations,
        "reference_limit_hit": len(consumers) >= MAX_SLOTS or len(formations) >= MAX_SLOTS,
    }


def data_pointer_refs(
    data: bytes,
    pe: dict,
    sections: list[dict],
    relocations: dict[int, int],
    slot_rva: int,
    byte_budget: int,
) -> tuple[list[dict], int, bool]:
    """Find only data locations that point into the exact 32-byte slot."""
    result = []
    seen = set()
    slot_targets = {slot_rva + offset for offset in range(0, SLOT_BYTES, 4)}
    for source_rva, kind in sorted(relocations.items()):
        source_section = section_for(sections, source_rva)
        offset = rva_to_file(sections, source_rva, 8)
        if kind != 10 or offset is None or source_section is None or source_section["name"] not in {".data", ".rdata"}:
            continue
        target = u64(data, offset)
        target_rva = target - pe["image_base"] if target >= pe["image_base"] else -1
        if target_rva not in slot_targets:
            continue
        key = (source_rva, target_rva, "DIR64")
        if key not in seen and len(result) < MAX_SLOTS:
            seen.add(key)
            result.append({
                "source_rva": source_rva,
                "target_offset": target_rva - slot_rva,
                "encoding": "DIR64",
                "source_section": source_section["name"],
            })
    examined = 0
    limit_hit = False
    for section in sections:
        if section["name"] not in {".data", ".rdata"}:
            continue
        start, end = section["raw_offset"], section["raw_offset"] + section["raw_size"]
        scan_end = min(end, len(data), start + max(0, byte_budget - examined))
        if scan_end < end:
            limit_hit = True
        examined += max(0, scan_end - start)
        for offset in range(start, max(start, scan_end - 3), 4):
            source_rva = section["rva"] + offset - start
            if relocations.get(source_rva) == 10:
                continue
            target_rva = u32(data, offset)
            if target_rva not in slot_targets:
                continue
            key = (source_rva, target_rva, "RVA32")
            if key not in seen and len(result) < MAX_SLOTS:
                seen.add(key)
                result.append({
                    "source_rva": source_rva,
                    "target_offset": target_rva - slot_rva,
                    "encoding": "RVA32",
                    "source_section": section["name"],
                })
    return result, examined, limit_hit


def analyze_image(path: Path, architecture: str) -> dict:
    expected = EXPECTED[architecture]
    before, digest_before = digest_and_stat(path)
    data = path.read_bytes()
    pe, sections = parse_pe(data)
    if before["size"] != expected["size"] or digest_before != expected["sha256"]:
        raise ValueError(f"{architecture} source identity mismatch")
    if pe["machine"] != expected["machine"]:
        raise ValueError(f"{architecture} PE machine mismatch")
    slot_offset = rva_to_file(sections, expected["slot_rva"], SLOT_BYTES)
    if slot_offset != expected["slot_raw"] or section_name(sections, expected["slot_rva"]) != ".rdata":
        raise ValueError(f"{architecture} exact slot locator mismatch")
    functions, pdata = parse_functions(data, pe, sections)
    relocations = parse_relocations(data, pe, sections)
    relocation_size = pe["directories"][5][1] if len(pe["directories"]) > 5 else 0
    pointer_budget = max(0, MAX_METADATA_BYTES - pdata["bytes_examined"] - min(relocation_size, MAX_METADATA_BYTES))
    pointers, pointer_bytes, pointer_limit_hit = data_pointer_refs(
        data, pe, sections, relocations, expected["slot_rva"], pointer_budget
    )
    targets = {
        expected["slot_rva"]: {
            "start": expected["slot_rva"],
            "source_rva": expected["slot_rva"],
            "kind": "new_static_ed_slot",
            "span": SLOT_BYTES,
        }
    }
    for pointer in pointers:
        targets[pointer["source_rva"]] = {
            "start": pointer["source_rva"],
            "source_rva": pointer["source_rva"],
            "kind": "slot_pointer_indirection",
            "span": 8,
        }
    scanner = scan_arm if architecture == "arm64" else scan_x64
    scan = scanner(data, sections, functions, targets)
    after, digest_after = digest_and_stat(path)
    scan["data_pointer_references"] = [
        {
            **pointer,
            "source_rva": f"0x{pointer['source_rva']:x}",
        }
        for pointer in pointers
    ]
    scan["indirect_code_reference_count"] = sum(
        hit["target_kind"] == "slot_pointer_indirection" for hit in scan["consumer_references"]
    )
    scan["direct_slot_consumer_count"] = sum(
        hit["target_kind"] == "new_static_ed_slot" for hit in scan["consumer_references"]
    )
    return {
        "architecture": architecture,
        "source": {
            "path": str(path),
            "size": before["size"],
            "sha256": digest_before,
            "expected_size": expected["size"],
            "expected_sha256": expected["sha256"],
            "stat_before": before,
            "stat_after": after,
            "sha256_after": digest_after,
            "stable": before == after and digest_before == digest_after,
        },
        "pe": {
            "machine": f"0x{pe['machine']:x}",
            "image_base": f"0x{pe['image_base']:x}",
            "size_of_image": pe["size_of_image"],
            "sections": [{"name": item["name"], "rva": f"0x{item['rva']:x}", "raw_size": item["raw_size"]} for item in sections],
            "relocation_slot_count": len(relocations),
            "metadata_bytes_examined": pdata["bytes_examined"] + min(relocation_size, MAX_METADATA_BYTES) + pointer_bytes,
            "metadata_limit_hit": pointer_limit_hit,
            "pdata": pdata,
        },
        "slot": {
            "raw_offset": f"0x{expected['slot_raw']:x}",
            "rva": f"0x{expected['slot_rva']:x}",
            "section": ".rdata",
            "width": SLOT_BYTES,
            "present": True,
            "raw_bytes_emitted": False,
        },
        "scan": scan,
    }


def analyze(arm_source: Path = ARM_SOURCE, x64_source: Path = X64_SOURCE) -> dict:
    arm = analyze_image(arm_source, "arm64")
    x64 = analyze_image(x64_source, "x64")
    arm_offset = rva_to_file(parse_pe(arm_source.read_bytes())[1], EXPECTED["arm64"]["slot_rva"], SLOT_BYTES)
    x64_offset = rva_to_file(parse_pe(x64_source.read_bytes())[1], EXPECTED["x64"]["slot_rva"], SLOT_BYTES)
    arm_bytes = arm_source.read_bytes()[arm_offset : arm_offset + SLOT_BYTES]
    x64_bytes = x64_source.read_bytes()[x64_offset : x64_offset + SLOT_BYTES]
    direct = arm["scan"]["direct_slot_consumer_count"] + x64["scan"]["direct_slot_consumer_count"]
    indirect = arm["scan"]["indirect_code_reference_count"] + x64["scan"]["indirect_code_reference_count"]
    return {
        "schema": "wowforever.static-ed-slot-consumers.v1",
        "scope": {
            "host_only_read_only": True,
            "native_priority": "ARM64; x64 analyzed only as comparison, no x64 execution",
            "exact_slot_only": True,
            "prior_generic_sweeps_repeated": False,
            "crypto_or_key_scan": False,
            "client_wine_adb_login_download": False,
            "process_heap_accounts_credentials": False,
            "memory_or_pe_write": False,
            "max_reverse_levels": MAX_REVERSE_LEVELS,
            "max_slots": MAX_SLOTS,
            "max_nodes": MAX_NODES,
            "max_followed_bytes": MAX_FOLLOWED_BYTES,
            "max_metadata_bytes": MAX_METADATA_BYTES,
            "max_context_bytes": MAX_CONTEXT_BYTES,
        },
        "slot_identity": {
            "arm64_and_x64_full_32_bytes_equal": arm_bytes == x64_bytes,
            "comparison_only_no_slot_bytes_or_slot_hash_emitted": True,
            "raw_locator_status": "hypothesis_only",
        },
        "images": [arm, x64],
        "assessment": {
            "direct_static_slot_consumer_proven": direct > 0,
            "indirect_slot_consumer_candidate": indirect > 0,
            "slot_semantics": "concrete code consumer proven by exact slot access" if direct else (
                "indirection candidate only; no direct slot load/store proof" if indirect else
                "unknown after bounded exact-slot reverse-reference coverage"
            ),
            "forever_group8_link": "not_proven",
            "negative_result_scope": (
                "bounded coverage: no exact or one-indirection consumer reference was found; this is not a missing-crypto conclusion"
                if not direct and not indirect else
                "consumer evidence is static-storage evidence only; key role and Forever group are not inferred"
            ),
        },
    }


def create_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.fchmod(fd, 0o600)
        payload = (json.dumps(report, indent=2, sort_keys=True) + "\n").encode("utf-8")
        if len(payload) > 2 * 1024 * 1024:
            raise ValueError("report exceeds 2 MiB")
        with os.fdopen(fd, "wb") as stream:
            fd = -1
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        if fd != -1:
            os.close(fd)


def synthetic_checks() -> None:
    assert arm_address_target(0x1003, 0x10000020) == (0x1007, "ADR")
    assert arm_address_target(0x1000, 0x90000020) == (0x1000, "ADRP")
    spec = {0x2000: {"start": 0x2000, "source_rva": 0x2000, "kind": "new_static_ed_slot", "span": SLOT_BYTES}}
    assert target_match(spec, 0x2000, 16) is spec[0x2000]
    assert target_match(spec, 0x2010, 16) is spec[0x2000]
    assert target_match(spec, 0x2020, 1) is None
    assert decode_x64_rip(b"\x48\x8b\x05\xfa\x0f\x00\x00" + b"\0" * 10,
                         [{"name": ".text", "rva": 0, "virtual_size": 32, "raw_size": 32, "raw_offset": 0, "characteristics": 0x60000020}], 0)["target"] == 0x1000


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arm64-source", type=Path, default=ARM_SOURCE)
    parser.add_argument("--x64-source", type=Path, default=X64_SOURCE)
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
        report = analyze(args.arm64_source, args.x64_source)
        create_report(args.output, report)
    except (OSError, ValueError, struct.error) as exc:
        print(f"StaticEdSlotConsumers.py: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"output": str(args.output), "stable": all(item["source"]["stable"] for item in report["images"]), "direct": report["assessment"]["direct_static_slot_consumer_proven"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
