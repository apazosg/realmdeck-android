#!/usr/bin/env python3
"""Bounded ARM64 data-graph analysis for the known trust-data anchors.

This is a host-only, read-only PE inspection.  It does not search for public-key
bytes, execute the image, use Ghidra, inspect a process, or write a PE.  The graph
starts only at the previously reported trust strings and the two corrected
12-by-40 candidates.  It follows relocation-backed data edges and explicitly
recognised MSVC RTTI shapes; it does not infer ``CertStore`` from row IDs.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import os
import struct
import sys
from collections import defaultdict, deque
from pathlib import Path
from typing import Iterable


DEFAULT_SOURCE = Path(
    "/home/adrian/wowforever-artifacts/references/official-arm64-beta/WowB-ARM64.exe"
)
DEFAULT_OUTPUT_DIR = Path("/home/adrian/wowforever-artifacts/work")
EXPECTED_SHA256 = "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59"
EXPECTED_SIZE = 94286544

MAX_LEVELS = 3
MAX_NODES = 256
MAX_FOLLOWED_DATA = 2 * 1024 * 1024
MAX_METADATA = 16 * 1024 * 1024
# A row table is 480 bytes.  Keeping the transitive context to one nearby
# 512-byte window avoids turning this directed graph into a .rdata sweep.
NODE_WINDOW = 0x200
ROOT_TABLE_BYTES = 12 * 40
MAX_REVERSE_MATCHES = 64
MAX_NEW_CODE_TARGETS = 24
MAX_CODE_METADATA = 64 * 1024
MAX_PDATA_BODIES = 4
MAX_PDATA_BODY_BYTES = 64 * 1024

KNOWN_STRINGS = {
    0x498B860: "NetClient.cpp",
    0x498BB50: "Cert store init failed",
    0x498CC10: "PublicKeyEntry",
    0x498B440: "allowDevPublicKey",
    0x4BDFBE8: "VerifyWithCertStore",
    0x4BDF960: "AddServerCertificatesToCertStore",
    0x4BDF9A0: "AddTrustCertificatesToCertStore",
    0x4BDFAC8: "VerifyWithGivenTrustCertificate[0]",
    0x4BDFB10: "VerifyWithGivenTrustCertificate[1]",
    0x4BDFB60: "VerifyWithGivenTrustCertificate[2]",
    0x4BDFBA8: "VerifyWithGivenTrustCertificate[3]",
}
ROOTS = {0x4C5F5E8: "candidate_1_12x40", 0x4CCBA58: "candidate_2_12x40"}
KNOWN_ROOT_REFERENCE = 0x4C5FEB8


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def u64(data: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


def sign_extend(value: int, bits: int) -> int:
    sign = 1 << (bits - 1)
    return (value ^ sign) - sign


def arm64_adr_target(pc_rva: int, word: int) -> tuple[int, str] | None:
    """Decode ADR/ADRP with the architectural imm21 layout."""
    imm21 = (((word >> 5) & 0x7FFFF) << 2) | ((word >> 29) & 3)
    if (word & 0x9F000000) == 0x10000000:
        return pc_rva + sign_extend(imm21, 21), "ADR"
    if (word & 0x9F000000) == 0x90000000:
        return (pc_rva & ~0xFFF) + sign_extend(imm21, 21) * 0x1000, "ADRP"
    if (word & 0x3B000000) == 0x18000000:
        return pc_rva + sign_extend((word >> 5) & 0x7FFFF, 19) * 4, "LDR-literal"
    return None


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
    pe_offset = u32(data, 0x3C)
    if data[pe_offset : pe_offset + 4] != b"PE\0\0":
        raise ValueError("invalid PE signature")
    file_header = pe_offset + 4
    machine = u16(data, file_header)
    count = u16(data, file_header + 2)
    optional_size = u16(data, file_header + 16)
    optional = file_header + 20
    if u16(data, optional) != 0x20B:
        raise ValueError("expected PE32+")
    directory_count = min(u32(data, optional + 108), 16)
    directories = []
    for index in range(directory_count):
        entry = optional + 112 + index * 8
        directories.append((u32(data, entry), u32(data, entry + 4)))
    section_table = optional + optional_size
    sections = []
    for index in range(count):
        offset = section_table + index * 40
        name = data[offset : offset + 8].split(b"\0", 1)[0].decode("ascii", "replace")
        sections.append(
            {
                "name": name,
                "rva": u32(data, offset + 12),
                "virtual_size": u32(data, offset + 8),
                "raw_size": u32(data, offset + 16),
                "raw_offset": u32(data, offset + 20),
                "characteristics": u32(data, offset + 36),
            }
        )
    return {
        "machine": machine,
        "machine_name": "ARM64" if machine == 0xAA64 else f"0x{machine:04x}",
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
    return offset


def classify(pe: dict, sections: list[dict], data_len: int, rva: int) -> dict:
    if rva < 0 or rva >= pe["size_of_image"]:
        return {"region": "invalid", "section": None}
    if rva < pe["size_of_headers"]:
        return {"region": "header", "section": "<headers>"}
    section = section_for(sections, rva)
    if section is None:
        return {"region": "invalid", "section": None}
    relative = rva - section["rva"]
    if relative >= section["virtual_size"]:
        return {"region": "raw-tail", "section": section["name"]}
    raw = relative < section["raw_size"] and section["raw_offset"] + relative < data_len
    return {"region": "raw-backed" if raw else "bss", "section": section["name"]}


def parse_relocations(
    data: bytes, pe: dict, sections: list[dict]
) -> tuple[dict[int, int], dict]:
    directories = pe["directories"]
    directory = directories[5] if len(directories) > 5 else (0, 0)
    rva, size = directory
    start = rva_to_file(sections, rva) if rva else None
    if start is None:
        return {}, {"rva": rva, "declared_size": size, "bytes_examined": 0, "truncated": False}
    bounded = min(size, MAX_METADATA, len(data) - start)
    end = start + bounded
    result: dict[int, int] = {}
    cursor = start
    while cursor + 8 <= end:
        page = u32(data, cursor)
        block_size = u32(data, cursor + 4)
        if block_size < 8 or cursor + block_size > end:
            break
        for position in range(cursor + 8, cursor + block_size, 2):
            raw = u16(data, position)
            kind = raw >> 12
            if kind:
                result[page + (raw & 0xFFF)] = kind
        cursor += block_size
    return result, {
        "rva": rva,
        "declared_size": size,
        "bytes_examined": bounded,
        "truncated": size > bounded,
    }


def parse_pdata_ranges(data: bytes, pe: dict, sections: list[dict], budget: int) -> tuple[list[dict], dict]:
    """Read only bounded ARM64 exception records for later code-target mapping."""
    directories = pe["directories"]
    rva, size = directories[3] if len(directories) > 3 else (0, 0)
    start = rva_to_file(sections, rva) if rva else None
    if start is None or budget <= 0:
        return [], {"rva": rva, "declared_size": size, "bytes_examined": 0, "truncated": bool(size)}
    bounded = min(size, budget, len(data) - start)
    end = start + bounded
    ranges: list[dict] = []
    for cursor in range(start, end - 7, 8):
        begin = u32(data, cursor)
        unwind = u32(data, cursor + 4)
        kind = "arm64-packed" if unwind & 3 in (1, 2) else "arm64-xdata"
        if unwind & 3 in (1, 2):
            body_size = ((unwind >> 2) & 0x7FF) * 4
        elif unwind & 3 == 0:
            xdata = unwind & ~3
            xdata_offset = rva_to_file(sections, xdata, 4)
            if xdata_offset is None:
                continue
            words = u32(data, xdata_offset) & 0x3FFFF
            body_size = words * 4
        else:
            continue
        end_rva = begin + body_size
        if body_size <= 0 or body_size > MAX_PDATA_BODY_BYTES:
            continue
        info = classify(pe, sections, len(data), begin)
        if info["section"] != ".text" or not (section_for(sections, begin)["characteristics"] & 0x20000000):  # type: ignore[index]
            continue
        ranges.append({"begin_rva": begin, "end_rva": end_rva, "body_bytes": body_size, "kind": kind})
    return ranges, {
        "rva": rva,
        "declared_size": size,
        "bytes_examined": bounded,
        "truncated": size > bounded,
    }


def read_ascii(data: bytes, sections: list[dict], rva: int, limit: int = 128) -> str | None:
    offset = rva_to_file(sections, rva)
    if offset is None:
        return None
    end = min(len(data), offset + limit)
    nul = data.find(b"\0", offset, end)
    if nul < 0:
        return None
    raw = data[offset:nul]
    if not raw or any(byte < 0x20 or byte > 0x7E for byte in raw):
        return None
    return raw.decode("ascii")


def valid_data_target(pe: dict, sections: list[dict], data: bytes, rva: int) -> bool:
    info = classify(pe, sections, len(data), rva)
    return info["region"] == "raw-backed" and info["section"] in {".rdata", ".data"}


def parse_col(data: bytes, pe: dict, sections: list[dict], rva: int) -> dict | None:
    offset = rva_to_file(sections, rva, 24)
    if offset is None or not valid_data_target(pe, sections, data, rva):
        return None
    signature, object_offset, cd_offset = struct.unpack_from("<III", data, offset)
    type_rva, class_rva, self_rva = struct.unpack_from("<III", data, offset + 12)
    if signature not in (0, 1) or self_rva != rva:
        return None
    if not valid_data_target(pe, sections, data, type_rva):
        return None
    if not valid_data_target(pe, sections, data, class_rva):
        return None
    return {
        "kind": "msvc_complete_object_locator",
        "confidence": "strong",
        "signature": signature,
        "object_offset": object_offset,
        "cd_offset": cd_offset,
        "type_descriptor_rva": type_rva,
        "class_hierarchy_rva": class_rva,
        "self_rva": self_rva,
    }


def parse_type_descriptor(
    data: bytes, pe: dict, sections: list[dict], relocations: dict[int, int], rva: int
) -> dict | None:
    offset = rva_to_file(sections, rva, 24)
    if offset is None or not valid_data_target(pe, sections, data, rva):
        return None
    vf_target = u64(data, offset)
    spare = u64(data, offset + 8)
    if relocations.get(rva) != 10 or vf_target < pe["image_base"]:
        return None
    vf_rva = vf_target - pe["image_base"]
    if not valid_data_target(pe, sections, data, vf_rva):
        return None
    name = read_ascii(data, sections, rva + 16, 128)
    if name is None or not name.startswith(".?A"):
        return None
    return {
        "kind": "msvc_type_descriptor",
        "confidence": "strong",
        "vftable_rva": vf_rva,
        "spare_is_null": spare == 0,
        "decorated_name": name,
    }


def parse_class_hierarchy(data: bytes, pe: dict, sections: list[dict], rva: int) -> dict | None:
    offset = rva_to_file(sections, rva, 16)
    if offset is None or not valid_data_target(pe, sections, data, rva):
        return None
    signature, attributes, count, array_rva = struct.unpack_from("<IIII", data, offset)
    if signature not in (0, 1) or not 1 <= count <= 64:
        return None
    array_offset = rva_to_file(sections, array_rva, count * 4)
    if array_offset is None or not valid_data_target(pe, sections, data, array_rva):
        return None
    bases = [u32(data, array_offset + index * 4) for index in range(count)]
    if not all(valid_data_target(pe, sections, data, base) for base in bases):
        return None
    return {
        "kind": "msvc_class_hierarchy_descriptor",
        "confidence": "strong",
        "signature": signature,
        "attributes": attributes,
        "base_count": count,
        "base_array_rva": array_rva,
        "base_type_descriptor_rvas": bases,
    }


def parse_vftable(
    data: bytes, pe: dict, sections: list[dict], relocations: dict[int, int], rva: int
) -> dict | None:
    if rva < 8:
        return None
    col_target = None
    previous_offset = rva_to_file(sections, rva - 8, 8)
    if previous_offset is not None and relocations.get(rva - 8) == 10:
        value = u64(data, previous_offset)
        if value >= pe["image_base"]:
            col_target = value - pe["image_base"]
    col = parse_col(data, pe, sections, col_target) if col_target is not None else None
    if col is None:
        return None
    offset = rva_to_file(sections, rva, 8)
    if offset is None:
        return None
    function_count = 0
    while function_count < 16:
        slot_rva = rva + function_count * 8
        slot = rva_to_file(sections, slot_rva, 8)
        if slot is None or relocations.get(slot_rva) != 10:
            break
        value = u64(data, slot)
        target = value - pe["image_base"] if value >= pe["image_base"] else -1
        if classify(pe, sections, len(data), target)["section"] != ".text":
            break
        function_count += 1
    if function_count == 0:
        return None
    return {
        "kind": "msvc_vftable",
        "confidence": "strong",
        "complete_object_locator_rva": col_target,
        "function_pointer_count_observed": function_count,
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


class BoundedGraph:
    def __init__(self, data: bytes, pe: dict, sections: list[dict], relocations: dict[int, int]):
        self.data = data
        self.pe = pe
        self.sections = sections
        self.relocations = relocations
        self.relocation_targets: dict[int, list[int]] = defaultdict(list)
        self.relocation_slots: list[int] = []
        for slot, kind in relocations.items():
            if kind != 10 or not rva_to_file(sections, slot, 8):
                continue
            section = section_for(sections, slot)
            if section is None or section["name"] not in {".rdata", ".data"}:
                continue
            offset = rva_to_file(sections, slot, 8)
            assert offset is not None
            value = u64(data, offset)
            if value < pe["image_base"]:
                continue
            target = value - pe["image_base"]
            if classify(pe, sections, len(data), target)["region"] == "raw-backed":
                self.relocation_targets[target].append(slot)
            # Generic graph edges are data edges only.  Code relocations are
            # not data nodes and are handled separately by the bounded pointer pass.
            self.relocation_slots.append(slot)
        self.relocation_slots.sort()
        self.nodes: dict[int, dict] = {}
        self.edges: dict[tuple[int, int, str, str], dict] = {}
        self.queue: deque[int] = deque()
        self.followed_intervals: list[tuple[int, int]] = []
        self.followed_bytes = 0
        self.follow_limit_hit = False
        self.reverse_match_limit_hit = False
        self.windows: dict[int, tuple[int, int]] = {}
        self.seeds = set(KNOWN_STRINGS) | set(ROOTS)

    def add_node(self, rva: int, level: int, reason: str, seed: bool = False) -> bool:
        if rva < 0 or rva >= self.pe["size_of_image"]:
            return False
        info = classify(self.pe, self.sections, len(self.data), rva)
        if info["region"] in {"invalid", "header", "bss"}:
            return False
        old = self.nodes.get(rva)
        if old is not None:
            old["reasons"] = sorted(set(old["reasons"]) | {reason})
            old["seed"] = old["seed"] or seed
            if level < old["level"]:
                old["level"] = level
                self.queue.append(rva)
            return True
        if len(self.nodes) >= MAX_NODES:
            return False
        self.nodes[rva] = {
            "rva": rva,
            "level": level,
            "seed": seed,
            "section": info["section"],
            "region": info["region"],
            "reasons": [reason],
        }
        self.queue.append(rva)
        return True

    def edge(
        self,
        source: int,
        target: int,
        encoding: str,
        detail: str = "",
        orientation: str = "unspecified",
    ) -> None:
        key = (source, target, encoding, detail, orientation)
        self.edges.setdefault(
            key,
            {
                "source_rva": source,
                "target_rva": target,
                "encoding": encoding,
                "detail": detail,
                "orientation": orientation,
            },
        )

    def add_edge_node(
        self,
        source: int,
        target: int,
        level: int,
        encoding: str,
        reason: str,
        detail: str = "",
        orientation: str = "unspecified",
    ) -> None:
        self.edge(source, target, encoding, detail, orientation)
        self.add_node(source, level, reason)
        self.add_node(target, level, reason)

    def reverse_rva_slots(self, target: int) -> list[int]:
        packed = struct.pack("<I", target)
        result = []
        for section in self.sections:
            if section["name"] not in {".rdata", ".data"}:
                continue
            start = section["raw_offset"]
            end = min(len(self.data), start + section["raw_size"])
            cursor = start
            while len(result) < MAX_REVERSE_MATCHES:
                found = self.data.find(packed, cursor, end)
                if found < 0:
                    break
                slot = section["rva"] + found - start
                if found % 4 == start % 4 and self.relocations.get(slot) != 10:
                    result.append(slot)
                cursor = found + 1
            if len(result) >= MAX_REVERSE_MATCHES:
                self.reverse_match_limit_hit = True
                break
        return result

    def follow_window(self, rva: int) -> tuple[int, int] | None:
        section = section_for(self.sections, rva)
        if section is None or section["name"] not in {".rdata", ".data"}:
            return None
        relative = rva - section["rva"]
        if rva in ROOTS:
            start_relative = relative
            end_relative = min(section["raw_size"], relative + ROOT_TABLE_BYTES)
        elif rva == KNOWN_ROOT_REFERENCE:
            # This is a supplied pointer slot, not an invitation to scan the
            # surrounding array as an untyped object.
            start_relative = relative
            end_relative = min(section["raw_size"], relative + 8)
        else:
            start_relative = max(0, relative - 8)
            end_relative = min(section["raw_size"], relative + NODE_WINDOW)
        start = section["raw_offset"] + start_relative
        end = section["raw_offset"] + end_relative
        if end <= start:
            return None
        # Charge only the union of followed raw intervals.
        added = end - start
        for old_start, old_end in self.followed_intervals:
            added -= max(0, min(end, old_end) - max(start, old_start))
        if self.followed_bytes + max(0, added) > MAX_FOLLOWED_DATA:
            self.follow_limit_hit = True
            return None
        self.followed_intervals.append((start, end))
        self.followed_bytes += max(0, added)
        self.windows[rva] = (start, end)
        return start, end

    def add_rtti_edges(self, node: int, level: int) -> None:
        if level >= MAX_LEVELS:
            return
        col = parse_col(self.data, self.pe, self.sections, node)
        if col is not None:
            self.add_edge_node(node, col["type_descriptor_rva"], level + 1, "relative_rva32", "COL.type_descriptor", orientation="descriptor_forward")
            self.add_edge_node(node, col["class_hierarchy_rva"], level + 1, "relative_rva32", "COL.class_hierarchy", orientation="descriptor_forward")
        typ = parse_type_descriptor(self.data, self.pe, self.sections, self.relocations, node)
        if typ is not None:
            self.add_edge_node(node, typ["vftable_rva"], level + 1, "absolute_DIR64", "type_descriptor.vftable", orientation="descriptor_forward")
        chd = parse_class_hierarchy(self.data, self.pe, self.sections, node)
        if chd is not None:
            self.add_edge_node(node, chd["base_array_rva"], level + 1, "relative_rva32", "CHD.base_array", orientation="descriptor_forward")
            for base in chd["base_type_descriptor_rvas"]:
                self.add_edge_node(node, base, level + 1, "relative_rva32", "CHD.base_type_descriptor", orientation="descriptor_forward")
        vftable = parse_vftable(self.data, self.pe, self.sections, self.relocations, node)
        if vftable is not None:
            self.add_edge_node(node, vftable["complete_object_locator_rva"], level + 1, "absolute_DIR64", "vftable[-1].COL", orientation="descriptor_forward")

    def process(self) -> None:
        while self.queue:
            node = self.queue.popleft()
            item = self.nodes[node]
            level = item["level"]
            if level >= MAX_LEVELS:
                continue
            # Process forward data first so fixed roots retain graph capacity;
            # reverse aliases are a separate consumer component and never make
            # a source slot reachable from the root.
            window = self.follow_window(node)
            if window is not None:
                start, end = window
                first = bisect.bisect_left(self.relocation_slots, section_for(self.sections, node)["rva"])  # type: ignore[index]
                # A small linear walk is bounded by the relocation count and the
                # charged window; bisect is only used to avoid image-wide scans.
                for slot in self.relocation_slots[first:]:
                    slot_offset = rva_to_file(self.sections, slot, 8)
                    if slot_offset is None or slot_offset < start:
                        continue
                    if slot_offset + 8 > end:
                        if slot_offset >= end:
                            break
                        continue
                    value = u64(self.data, slot_offset)
                    if value < self.pe["image_base"]:
                        continue
                    target = value - self.pe["image_base"]
                    target_info = classify(self.pe, self.sections, len(self.data), target)
                    if target_info["region"] == "raw-backed" and target_info["section"] in {".rdata", ".data"}:
                        self.add_edge_node(node, target, level + 1, "absolute_DIR64", "window_relocation_slot", f"slot_rva=0x{slot:x}", "forward_data")
                known = set(self.nodes)
                for position in range(start, max(start, end - 3), 4):
                    value = u32(self.data, position)
                    target = value if value in known else None
                    if target is None or self.relocations.get(section_for(self.sections, node)["rva"] + position - section_for(self.sections, node)["raw_offset"]) == 10:  # type: ignore[index]
                        continue
                    source = section_for(self.sections, node)["rva"] + position - section_for(self.sections, node)["raw_offset"]  # type: ignore[index]
                    self.add_edge_node(source, target, level + 1, "relative_RVA32", "window_relative_rva_field", orientation="forward_data")
            self.add_rtti_edges(node, level)
            for source in self.relocation_targets.get(node, [])[:MAX_REVERSE_MATCHES]:
                self.add_edge_node(source, node, level + 1, "absolute_DIR64", "relocation-backed_reverse_reference", orientation="reverse_alias_consumer")
            for source in self.reverse_rva_slots(node):
                self.add_edge_node(source, node, level + 1, "relative_RVA32", "relative_rva_reverse_reference", orientation="reverse_alias_consumer")

    def forward_paths(self, seeds: dict[int, str]) -> dict[int, dict]:
        """Return only forward data paths; reverse aliases are deliberately excluded."""
        adjacency: dict[int, list[dict]] = defaultdict(list)
        for edge in self.edges.values():
            if edge["orientation"] in {"forward_data", "descriptor_forward"}:
                adjacency[edge["source_rva"]].append(edge)
        paths: dict[int, dict] = {}
        queue: deque[int] = deque()
        for seed, label in seeds.items():
            if seed in self.nodes:
                paths[seed] = {
                    "seed_rva": seed,
                    "seed_label": label,
                    "seed_kind": "root" if seed in ROOTS else "trust_string",
                    "nodes": [seed],
                    "edges": [],
                }
                queue.append(seed)
        while queue:
            source = queue.popleft()
            path = paths[source]
            if len(path["edges"]) >= MAX_LEVELS:
                continue
            for edge in adjacency.get(source, []):
                target = edge["target_rva"]
                if target in paths:
                    continue
                if target not in self.nodes or self.nodes[target]["section"] not in {".rdata", ".data"}:
                    continue
                paths[target] = {
                    "seed_rva": path["seed_rva"],
                    "seed_label": path["seed_label"],
                    "seed_kind": path["seed_kind"],
                    "nodes": path["nodes"] + [target],
                    "edges": path["edges"] + [edge],
                }
                queue.append(target)
        return paths

    def code_pointer_observations(self, paths: dict[int, dict], pdata_ranges: list[dict]) -> dict:
        """Inspect DIR64 slots only in root/trust-string forward windows."""
        observations: list[dict] = []
        seen_targets: set[int] = set()
        excluded = defaultdict(int)
        intervals: list[tuple[int, int]] = []
        followed = 0
        limit_hit = False
        ordered_paths = sorted(
            paths.items(),
            key=lambda item: (0 if item[1]["seed_kind"] == "root" else 1, len(item[1]["edges"]), item[0]),
        )
        for node_rva, path in ordered_paths:
            window = self.windows.get(node_rva)
            if window is None:
                continue
            start, end = window
            added = end - start
            for old_start, old_end in intervals:
                added -= max(0, min(end, old_end) - max(start, old_start))
            if followed + max(0, added) > MAX_CODE_METADATA:
                limit_hit = True
                break
            intervals.append(window)
            followed += max(0, added)
            section = section_for(self.sections, node_rva)
            if section is None:
                continue
            low_rva = section["rva"] + start - section["raw_offset"]
            high_rva = section["rva"] + end - section["raw_offset"]
            first = bisect.bisect_left(self.relocation_slots, low_rva)
            for slot in self.relocation_slots[first:]:
                if slot >= high_rva:
                    break
                slot_offset = rva_to_file(self.sections, slot, 8)
                if slot_offset is None or slot_offset < start or slot_offset + 8 > end:
                    continue
                value = u64(self.data, slot_offset)
                if value < self.pe["image_base"]:
                    excluded["invalid_target"] += 1
                    continue
                target = value - self.pe["image_base"]
                info = classify(self.pe, self.sections, len(self.data), target)
                if info["region"] == "invalid":
                    excluded["invalid_target"] += 1
                    continue
                target_section = section_for(self.sections, target)
                shared = bool(target_section and target_section["characteristics"] & 0x10000000)
                if shared:
                    excluded["shared_target"] += 1
                    continue
                if info["section"] != ".text":
                    excluded["non_text_target"] += 1
                    continue
                executable = bool(target_section and target_section["characteristics"] & 0x20000000)
                if not executable:
                    excluded["non_executable_text_target"] += 1
                    continue
                if target in seen_targets:
                    continue
                seen_targets.add(target)
                containing = {
                    "rva": f"0x{node_rva:x}",
                    "role": "untyped_graph_data_node",
                    "relationship": "pointer_slot_is_not_assumed_to_be_object_start",
                }
                pdata = next(
                    (item for item in pdata_ranges if item["begin_rva"] <= target < item["end_rva"]),
                    None,
                )
                observations.append(
                    {
                        "pointer_slot_rva": f"0x{slot:x}",
                        "containing_node": containing,
                        "root_provenance": {
                            "seed_kind": path["seed_kind"],
                            "seed_rva": f"0x{path['seed_rva']:x}",
                            "seed_label": path["seed_label"],
                            "graph_path": [f"0x{value:x}" for value in path["nodes"]],
                            "graph_path_edges": [
                                {
                                    "source_rva": f"0x{edge['source_rva']:x}",
                                    "target_rva": f"0x{edge['target_rva']:x}",
                                    "encoding": edge["encoding"],
                                    "orientation": edge["orientation"],
                                    "detail": edge["detail"],
                                }
                                for edge in path["edges"]
                            ],
                        },
                        "target_rva": f"0x{target:x}",
                        "target_section": info["section"],
                        "target_executable": executable,
                        "target_shared": shared,
                        "pdata_range": (
                            {
                                "begin_rva": f"0x{pdata['begin_rva']:x}",
                                "end_rva": f"0x{pdata['end_rva']:x}",
                                "body_bytes": pdata["body_bytes"],
                                "kind": pdata["kind"],
                            }
                            if pdata is not None
                            else None
                        ),
                    }
                )
                if len(observations) >= MAX_NEW_CODE_TARGETS:
                    limit_hit = True
                    break
            if len(observations) >= MAX_NEW_CODE_TARGETS:
                break
        bodies: list[dict] = []
        seen_bodies: set[tuple[int, int]] = set()
        for item in observations:
            pdata = item["pdata_range"]
            if pdata is None:
                continue
            key = (int(pdata["begin_rva"], 16), int(pdata["end_rva"], 16))
            if key in seen_bodies:
                continue
            seen_bodies.add(key)
            if len(bodies) < MAX_PDATA_BODIES:
                bodies.append(pdata)
        return {
            "observations": observations,
            "root_forward_observation_count": sum(item["root_provenance"]["seed_kind"] == "root" for item in observations),
            "trust_string_context_observation_count": sum(item["root_provenance"]["seed_kind"] == "trust_string" for item in observations),
            "new_target_count": len(observations),
            "target_limit_hit": limit_hit,
            "metadata_bytes_followed": followed,
            "metadata_limit_bytes": MAX_CODE_METADATA,
            "metadata_limit_hit": limit_hit and followed >= MAX_CODE_METADATA,
            "excluded_slots": dict(sorted(excluded.items())),
            "pdata_bodies": bodies,
            "pdata_body_limit": MAX_PDATA_BODIES,
            "pdata_body_bytes_limit": MAX_PDATA_BODY_BYTES,
            "ghidra_followup": {
                "required_if_new_credible_targets": bool(observations),
                "max_bodies": MAX_PDATA_BODIES,
                "max_body_bytes": MAX_PDATA_BODY_BYTES,
                "max_decompilations": MAX_PDATA_BODIES,
                "seconds_per_decompilation": 15,
                "overall_timeout_seconds": 180,
                "noanalysis": True,
                "max_cpu": 2,
                "jvm_heap": "4G",
                "not_run_by_this_python_analyzer": True,
                "reason_not_run": "bounded report records candidates and pdata only; no broad Ghidra pass was invoked",
            },
        }


def analyze(source: Path) -> dict:
    before, digest_before = digest_and_stat(source)
    data = source.read_bytes()
    pe, sections = parse_pe(data)
    relocations, metadata = parse_relocations(data, pe, sections)
    pdata_budget = max(0, MAX_METADATA - metadata["bytes_examined"])
    pdata_ranges, pdata_metadata = parse_pdata_ranges(data, pe, sections, pdata_budget)
    metadata_bytes_examined = metadata["bytes_examined"] + pdata_metadata["bytes_examined"]
    graph = BoundedGraph(data, pe, sections, relocations)
    for rva, label in ROOTS.items():
        graph.add_node(rva, 0, label, seed=True)
    for rva, label in KNOWN_STRINGS.items():
        graph.add_node(rva, 0, f"known_trust_string:{label}", seed=True)
    graph.process()
    after, digest_after = digest_and_stat(source)
    proofs = []
    for rva, node in sorted(graph.nodes.items()):
        col = parse_col(data, pe, sections, rva)
        typ = parse_type_descriptor(data, pe, sections, relocations, rva)
        chd = parse_class_hierarchy(data, pe, sections, rva)
        vftable = parse_vftable(data, pe, sections, relocations, rva)
        if any(value is not None for value in (col, typ, chd, vftable)):
            proof = {"rva": rva}
            if col is not None:
                proof["complete_object_locator"] = col
            if typ is not None:
                proof["type_descriptor"] = typ
            if chd is not None:
                proof["class_hierarchy"] = chd
            if vftable is not None:
                proof["vftable"] = vftable
            proofs.append(proof)
    edges = sorted(
        graph.edges.values(),
        key=lambda edge: (edge["source_rva"], edge["target_rva"], edge["encoding"], edge["orientation"], edge["detail"]),
    )
    ordered_seeds = {**{rva: label for rva, label in ROOTS.items()}, **KNOWN_STRINGS}
    forward_paths = graph.forward_paths(ordered_seeds)
    root_paths = {rva: path for rva, path in forward_paths.items() if path["seed_kind"] == "root"}
    trust_string_paths = {rva: path for rva, path in forward_paths.items() if path["seed_kind"] == "trust_string"}
    code_pointers = graph.code_pointer_observations(forward_paths, pdata_ranges)
    reverse_alias_edges = [edge for edge in edges if edge["orientation"] == "reverse_alias_consumer"]
    encoding_counts = defaultdict(int)
    for edge in edges:
        encoding_counts[edge["encoding"]] += 1
    return {
        "schema": "wowforever.indirect-trust-data.v1",
        "scope": {
            "host_only_read_only": True,
            "starts_only_at_known_trust_strings_and_two_corrected_roots": True,
            "direct_pdata_prefilter_repeated": False,
            "x64_execution_or_translation": False,
            "client_or_process_access": False,
            "max_graph_levels": MAX_LEVELS,
            "max_nodes": MAX_NODES,
            "max_followed_data_bytes": MAX_FOLLOWED_DATA,
            "followed_data_bytes": graph.followed_bytes,
            "metadata_limit_bytes": MAX_METADATA,
            "metadata_bytes_examined": metadata_bytes_examined,
            "code_pointer_pass": "DIR64 slots only in root-forward/trust-string forward windows; no global .text scan",
            "reverse_aliases_used_as_code_sources": False,
            "max_code_pointer_metadata_bytes": MAX_CODE_METADATA,
            "raw_key_or_heap_bytes_emitted": False,
        },
        "source": {
            "path": str(source),
            "size": before["size"],
            "sha256": digest_before,
            "expected_size": EXPECTED_SIZE,
            "expected_sha256": EXPECTED_SHA256,
            "stat_before": before,
            "stat_after": after,
            "sha256_after": digest_after,
            "stable": before == after and digest_before == digest_after,
        },
        "pe": {
            "machine": pe["machine_name"],
            "image_base": pe["image_base"],
            "size_of_image": pe["size_of_image"],
            "relocation_directory": metadata,
            "pdata_directory": pdata_metadata,
            "relocation_slots_in_data": len(graph.relocation_slots),
        },
        "seeds": {
            "strings": {f"0x{rva:x}": label for rva, label in KNOWN_STRINGS.items()},
            "roots": {f"0x{rva:x}": label for rva, label in ROOTS.items()},
            "known_first_root_reference_rva": f"0x{KNOWN_ROOT_REFERENCE:x}",
        },
        "graph": {
            "node_count": len(graph.nodes),
            "edge_count": len(edges),
            "nodes": [
                {**node, "rva": f"0x{node['rva']:x}"}
                for node in sorted(graph.nodes.values(), key=lambda item: (item["level"], item["rva"]))
            ],
            "edges": [
                {**edge, "source_rva": f"0x{edge['source_rva']:x}", "target_rva": f"0x{edge['target_rva']:x}"}
                for edge in edges
            ],
            "edge_encoding_counts": dict(sorted(encoding_counts.items())),
            "node_limit_hit": len(graph.nodes) >= MAX_NODES,
            "reverse_match_limit_hit": graph.reverse_match_limit_hit,
            "followed_data_limit_hit": graph.follow_limit_hit,
            "forward_root_node_count": len(root_paths),
            "forward_trust_string_node_count": len(trust_string_paths),
            "reverse_alias_edge_count": len(reverse_alias_edges),
        },
        "forward_components": {
            "root_forward_node_count": len(root_paths),
            "trust_string_forward_node_count": len(trust_string_paths),
            "orientation": "forward only; reverse aliases are not traversed",
        },
        "reverse_alias_consumer_components": {
            "edge_count": len(reverse_alias_edges),
            "orientation": "data slot/source points TO a known root or trust string; not root-forward reachability",
        },
        "abi_proofs": proofs,
        "code_pointer_observations": code_pointers,
        "coverage_assessment": {
            "msvc_col_type_descriptor_vftable_proofs": len(proofs),
            "root_forward_code_target_count": code_pointers["new_target_count"],
            "root_forward_nodes_observed": len(root_paths),
            "reverse_aliases_not_forward_traversed": True,
            "negative_is_bounded_not_global": True,
        },
        "assessment": (
            "DIR64 .text observations are code-pointer candidates only; no CertStore consumer is asserted without a trust-root path plus code proof. "
            "row IDs alone are intentionally not treated as trust metadata."
        ),
    }


def synthetic_checks() -> None:
    pc = 0x1003
    for immediate in (1, 2, 3, -1, -2, -3, 0x12345, -0x12345):
        encoded = immediate & ((1 << 21) - 1)
        word = 0x10000000 | ((encoded & 3) << 29) | ((encoded >> 2) << 5)
        decoded = arm64_adr_target(pc, word)
        assert decoded == (pc + immediate, "ADR")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
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
        report = analyze(args.source)
        create_report(args.output, report)
    except (OSError, ValueError, struct.error) as exc:
        print(f"IndirectTrustData.py: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"output": str(args.output), "stable": report["source"]["stable"], "nodes": report["graph"]["node_count"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
