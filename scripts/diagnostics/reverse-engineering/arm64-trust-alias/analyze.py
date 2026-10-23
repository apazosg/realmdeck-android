#!/usr/bin/env python3
"""Bounded, read-only characterization of the ARM64 post-slot alias.

This probe deliberately follows the already authenticated data edge
``0x4cb63b8 -> 0x4a5ff20``.  It is not a key scanner and never emits data
bytes.  Code-consumer evidence is reused from the prior exact-reference
analysis rather than repeating that 74 MiB executable scan.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import struct
import sys
from collections import defaultdict, deque
from pathlib import Path


SOURCE = Path(
    "/home/adrian/wowforever-artifacts/work/"
    "wow-patcher-arm64-20261013T182540Z/arm64/WowB-ARM64.exe"
)
EXPECTED_SIZE = 94_286_544
EXPECTED_SHA256 = "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59"
EXPECTED_MACHINE = 0xAA64
SLOT_RVA = 0x4A5FF00
POST_RVA = 0x4A5FF20
KNOWN_ALIAS_RVA = 0x4CB63B8

MAX_LEVELS = 3
MAX_NODES = 128
MAX_ALIAS_SCAN_BYTES = 32 * 1024 * 1024
MAX_WINDOW_BYTES = 2 * 1024 * 1024
SCAN_CHUNK = 1024 * 1024
MAX_REPORT_BYTES = 2 * 1024 * 1024
MAX_METADATA_BYTES = 2 * 1024 * 1024
MAX_PRIOR_REPORT_BYTES = 2 * 1024 * 1024
DIR64 = 10

PRIOR_CODE_REPORT = Path(
    "/home/adrian/wowforever-artifacts/work/ed-slot-flow-20261013/"
    "reports-v3/arm64.txt"
)
PRIOR_CODE_REPORT_SHA256 = "6e74fdd84dd06f18b24ad1c5ec198e7496a861f443b2c7c2eea7bb7a83b7eaa9"
MAX_EDGES = 128


def u16(blob: bytes, offset: int) -> int:
    return struct.unpack_from("<H", blob, offset)[0]


def u32(blob: bytes, offset: int) -> int:
    return struct.unpack_from("<I", blob, offset)[0]


def u64(blob: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", blob, offset)[0]


class Reader:
    """A held read-only FD with explicit hash and analysis I/O accounting."""

    def __init__(self, path: Path):
        self.path = path
        self.fd = os.open(path, os.O_RDONLY | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0))
        self.analysis_bytes = 0
        self.hash_bytes = 0
        self._closed = False

    def close(self) -> None:
        if not self._closed:
            os.close(self.fd)
            self._closed = True

    def __enter__(self) -> "Reader":
        return self

    def __exit__(self, *_args) -> None:
        self.close()

    def stat(self) -> os.stat_result:
        return os.fstat(self.fd)

    def read(self, offset: int, length: int, *, category: str = "analysis") -> bytes:
        if offset < 0 or length < 0:
            raise ValueError("negative bounded read")
        result = bytearray()
        while len(result) < length:
            chunk = os.pread(self.fd, length - len(result), offset + len(result))
            if not chunk:
                raise OSError("short bounded read")
            result.extend(chunk)
        if category == "hash":
            self.hash_bytes += length
        else:
            self.analysis_bytes += length
        return bytes(result)

    def sha256(self, size: int) -> str:
        digest = hashlib.sha256()
        offset = 0
        while offset < size:
            length = min(SCAN_CHUNK, size - offset)
            digest.update(self.read(offset, length, category="hash"))
            offset += length
        return digest.hexdigest()


def identity_record(value: os.stat_result) -> dict:
    """Return mutation-relevant identity, deliberately excluding atime."""
    return {
        "dev": value.st_dev,
        "inode": value.st_ino,
        "mode": value.st_mode,
        "size": value.st_size,
        "mtime_ns": value.st_mtime_ns,
        "ctime_ns": value.st_ctime_ns,
    }


def same_identity(left: os.stat_result, right: os.stat_result) -> bool:
    return identity_record(left) == identity_record(right)


def authenticate_prior_report(path: Path) -> dict:
    """Authenticate and parse only the bounded prior EdSlotFlow report."""
    result = {
        "status": "INCOMPLETE",
        "path": str(path),
        "validated": False,
        "reused": False,
        "reason": None,
        "report_sha256": None,
        "bytes_read": 0,
        "parsed": {},
    }
    flags = os.O_RDONLY | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        result["reason"] = f"open_failed:{exc.__class__.__name__}"
        return result
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            result["reason"] = "not_regular_file"
            return result
        if before.st_size > MAX_PRIOR_REPORT_BYTES:
            result["reason"] = "report_size_limit_exceeded"
            return result
        digest = hashlib.sha256()
        payload = bytearray()
        offset = 0
        while offset < before.st_size:
            length = min(SCAN_CHUNK, before.st_size - offset)
            chunk = os.pread(fd, length, offset)
            if not chunk:
                result["reason"] = "short_read"
                return result
            digest.update(chunk)
            payload.extend(chunk)
            offset += len(chunk)
        result["bytes_read"] = offset
        after = os.fstat(fd)
        computed_sha256 = digest.hexdigest()
        if not same_identity(before, after):
            result["reason"] = "report_changed_during_read"
            return result
        if computed_sha256 != PRIOR_CODE_REPORT_SHA256:
            result["reason"] = "report_digest_mismatch"
            return result
        text = payload.decode("utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        result["reason"] = f"read_failed:{exc.__class__.__name__}"
        return result
    finally:
        os.close(fd)

    import re

    def one(pattern: str) -> str | None:
        match = re.search(pattern, text, re.MULTILINE)
        return None if match is None else match.group(1)

    source_match = re.search(
        r"^SOURCE path=.* size=(\d+) sha256_before=([0-9a-f]{64}) .*machine=(0x[0-9a-f]+)",
        text,
        re.MULTILINE,
    )
    source_after = re.search(
        r"^SOURCE sha256_after=([0-9a-f]{64}) size_after=(\d+) stable=(true|false)",
        text,
        re.MULTILINE,
    )
    slot_match = re.search(r"^SLOT rva=(0x[0-9a-f]+) raw_offset=(0x[0-9a-f]+).*exact_locator=true", text, re.MULTILINE)
    alias_match = re.search(
        r"^DATA_ALIAS source_rva=(0x[0-9a-f]+).*target_neighborhood_offset=(-?\d+).*exact_slot_target=false",
        text,
        re.MULTILINE,
    )
    seeds_match = re.search(r"^RAW_SEEDS architecture=ARM64 executable_bytes_examined=(\d+) candidates=(\d+)", text, re.MULTILINE)
    confirmation_match = re.search(r"^GHIDRA_CONFIRMATION attempted_seeds=(\d+) confirmed_references=(\d+)", text, re.MULTILINE)
    bodies_match = re.search(r"^BODY_SELECTION candidate_functions=(\d+) selected=(\d+)", text, re.MULTILINE)
    if source_match is None or source_after is None or slot_match is None or alias_match is None:
        result["reason"] = "required_fields_missing"
        return result
    parsed = {
        "source_size": int(source_match.group(1)),
        "source_sha256_before": source_match.group(2),
        "source_machine": source_match.group(3),
        "source_sha256_after": source_after.group(1),
        "source_size_after": int(source_after.group(2)),
        "source_stable": source_after.group(3) == "true",
        "slot_rva": int(slot_match.group(1), 16),
        "slot_raw": int(slot_match.group(2), 16),
        "alias_source_rva": int(alias_match.group(1), 16),
        "alias_target_rva": int(slot_match.group(1), 16) + int(alias_match.group(2)),
        "executable_bytes_examined": None if seeds_match is None else int(seeds_match.group(1)),
        "raw_seed_candidates": None if seeds_match is None else int(seeds_match.group(2)),
        "ghidra_attempted_seeds": None if confirmation_match is None else int(confirmation_match.group(1)),
        "ghidra_confirmed_references": None if confirmation_match is None else int(confirmation_match.group(2)),
        "candidate_functions": None if bodies_match is None else int(bodies_match.group(1)),
        "selected_functions": None if bodies_match is None else int(bodies_match.group(2)),
    }
    result["parsed"] = parsed
    valid = (
        parsed["source_size"] == EXPECTED_SIZE
        and parsed["source_size_after"] == EXPECTED_SIZE
        and parsed["source_sha256_before"] == EXPECTED_SHA256
        and parsed["source_sha256_after"] == EXPECTED_SHA256
        and parsed["source_stable"]
        and parsed["source_machine"] == "0xaa64"
        and parsed["slot_rva"] + 32 == POST_RVA
        and parsed["alias_source_rva"] == KNOWN_ALIAS_RVA
        and parsed["alias_target_rva"] == POST_RVA
        and parsed["raw_seed_candidates"] == 0
        and parsed["ghidra_confirmed_references"] == 0
        and parsed["selected_functions"] == 0
    )
    if not valid:
        result["reason"] = "parsed_values_not_expected"
        return result
    result["status"] = "VALIDATED"
    result["validated"] = True
    result["reused"] = True
    result["report_sha256"] = computed_sha256
    result["reason"] = None
    return result


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
    directories = [
        (u32(header, optional + 112 + i * 8), u32(header, optional + 116 + i * 8))
        for i in range(directory_count)
    ]
    table = optional + optional_size
    sections = []
    for i in range(count):
        offset = table + i * 40
        sections.append(
            {
                "name": header[offset : offset + 8].split(b"\0", 1)[0].decode("ascii", "replace"),
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
        if section["rva"] <= rva < section["rva"] + max(section["virtual_size"], section["raw_size"]):
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


def storage_for(sections: list[dict], rva: int, length: int = 1) -> dict:
    section = section_for(sections, rva)
    if section is None:
        return {"kind": "unmapped"}
    relative = rva - section["rva"]
    raw_backed = relative >= 0 and relative + length <= section["raw_size"]
    virtual_backed = relative >= 0 and relative + length <= max(section["virtual_size"], section["raw_size"])
    if not virtual_backed:
        kind = "outside_section"
    elif raw_backed:
        kind = "initialized_file_backed"
    else:
        kind = "BSS_zero_fill"
    return {
        "kind": kind,
        "section": section["name"],
        "rva": f"0x{rva:x}",
        "raw_offset": None if not raw_backed else f"0x{section['raw_offset'] + relative:x}",
        "length": length,
    }


def directory(pe: dict, index: int) -> tuple[int, int]:
    return pe["directories"][index] if index < len(pe["directories"]) else (0, 0)


def parse_relocations(reader: Reader, pe: dict, sections: list[dict]) -> tuple[dict[int, int], dict]:
    rva, declared = directory(pe, 5)
    raw = rva_to_raw(sections, rva, 1) if rva else None
    if raw is None or not declared:
        return {}, {"present": False, "declared_bytes": declared, "bytes_examined": 0, "limit_hit": False}
    examined = min(declared, MAX_METADATA_BYTES)
    blob = reader.read(raw, examined)
    entries: dict[int, int] = {}
    cursor = 0
    blocks = 0
    while cursor + 8 <= len(blob):
        page = u32(blob, cursor)
        size = u32(blob, cursor + 4)
        if size < 8 or cursor + size > len(blob):
            break
        blocks += 1
        for position in range(cursor + 8, cursor + size, 2):
            item = u16(blob, position)
            kind = item >> 12
            if kind:
                entries[page + (item & 0xFFF)] = kind
        cursor += size
    return entries, {
        "present": True,
        "directory_rva": f"0x{rva:x}",
        "declared_bytes": declared,
        "bytes_examined": examined,
        "limit_hit": declared > examined,
        "blocks_examined": blocks,
        "entry_count": len(entries),
    }


def pointer_encoding(value: int, target: int, image_base: int, relocation_type: int | None) -> str | None:
    if relocation_type == DIR64 and value == image_base + target:
        return "DIR64"
    if relocation_type != DIR64 and value == target:
        return "candidate_relative_rva"
    return None


def scan_aliases(
    reader: Reader,
    pe: dict,
    sections: list[dict],
    relocations: dict[int, int],
    target_rvas: set[int],
) -> tuple[dict[int, list[dict]], dict]:
    """Scan initialized data only, returning exact pointer edges to target nodes."""
    selected = [item for item in sections if item["name"] in {".rdata", ".data"} and item["raw_size"]]
    total = sum(item["raw_size"] for item in selected)
    if total > MAX_ALIAS_SCAN_BYTES:
        raise ValueError(f"initialized alias scan requires {total} bytes > {MAX_ALIAS_SCAN_BYTES} bound")
    edges: dict[int, list[dict]] = defaultdict(list)
    near_target_counts = defaultdict(int)
    examined = 0
    passes = 0
    edges_considered = 0
    node_limit_hit = False
    edge_limit_hit = False
    level_limit_hit = False
    frontier = set(target_rvas)
    discovered = set(target_rvas)
    while frontier and passes < MAX_LEVELS and len(discovered) <= MAX_NODES:
        next_frontier = set()
        for section in selected:
            blob = reader.read(section["raw_offset"], section["raw_size"])
            examined += len(blob)
            candidates = []
            for local in range(0, max(0, len(blob) - 7), 8):
                source = section["rva"] + local
                value = u64(blob, local)
                relocation = relocations.get(source)
                if relocation == DIR64:
                    candidate = value - pe["image_base"] if pe["image_base"] <= value < pe["image_base"] + pe["size_of_image"] else None
                    if candidate is not None:
                        candidates.append((source, candidate, relocation, "DIR64", True))
            # RVA32 aliases are valid at four-byte alignment and must not be
            # hidden by the eight-byte DIR64 pass above.
            for local in range(0, max(0, len(blob) - 3), 4):
                source = section["rva"] + local
                relocation = relocations.get(source)
                if relocation == DIR64:
                    continue
                candidate = u32(blob, local)
                if candidate < pe["size_of_image"]:
                    candidates.append((source, candidate, relocation, "candidate_relative_rva", False))
            for source, candidate, relocation, encoding, confirmed in candidates:
                if candidate in frontier:
                    if sum(len(items) for items in edges.values()) >= MAX_EDGES:
                        edge_limit_hit = True
                        continue
                    edges[candidate].append({
                        "source_rva": f"0x{source:x}",
                        "target_rva": f"0x{candidate:x}",
                        "source_storage": storage_for(sections, source, 8 if confirmed else 4),
                        "target_storage": storage_for(sections, candidate, 1),
                        "encoding": encoding,
                        "confirmed_pointer": confirmed,
                        "relocation_type": relocation,
                    })
                    edges_considered += 1
                    if confirmed and source not in discovered and len(discovered) < MAX_NODES:
                        discovered.add(source)
                        next_frontier.add(source)
                    elif confirmed and source not in discovered:
                        node_limit_hit = True
                elif any(abs(candidate - target) <= 0x400 for target in frontier):
                    near_target_counts[candidate] += 1
        if passes + 1 >= MAX_LEVELS and next_frontier:
            level_limit_hit = True
        frontier = next_frontier
        passes += 1
    return edges, {
        "sections": [item["name"] for item in selected],
        "bytes_examined": examined,
        "unique_coverage_bytes": total,
        "passes": passes,
        "coverage_limit": MAX_ALIAS_SCAN_BYTES,
        "coverage_complete_for_selected_initialized_sections": True,
        "graph_limits": {
            "max_levels": MAX_LEVELS,
            "max_nodes": MAX_NODES,
            "max_edges": MAX_EDGES,
            "edges_considered": edges_considered,
            "edges_reported": sum(len(items) for items in edges.values()),
            "node_limit_hit": node_limit_hit,
            "edge_limit_hit": edge_limit_hit,
            "level_limit_hit": level_limit_hit,
            "graph_truncated": node_limit_hit or edge_limit_hit or level_limit_hit,
        },
        "near_target_nonexact_pointer_count": sum(near_target_counts.values()),
        "raw_bytes_emitted": False,
    }


def bounded_windows(reader: Reader, sections: list[dict], nodes: list[int]) -> dict:
    windows = []
    remaining = MAX_WINDOW_BYTES
    for rva in nodes:
        raw = rva_to_raw(sections, rva, 1)
        section = section_for(sections, rva)
        if raw is None or section is None or remaining == 0:
            windows.append({"rva": f"0x{rva:x}", "status": "not_file_backed"})
            continue
        amount = min(2048, remaining, section["raw_offset"] + section["raw_size"] - raw)
        if amount <= 0:
            windows.append({"rva": f"0x{rva:x}", "status": "empty"})
            continue
        blob = reader.read(raw, amount)
        remaining -= amount
        windows.append({
            "rva": f"0x{rva:x}",
            "storage": storage_for(sections, rva, 1),
            "bytes_examined": amount,
            "zero_count": blob.count(0),
            "distinct_byte_count": len(set(blob)),
            "all_zero": not any(blob),
            "raw_bytes_emitted": False,
        })
    return {
        "max_bytes": MAX_WINDOW_BYTES,
        "bytes_examined": MAX_WINDOW_BYTES - remaining,
        "nodes_with_windows": len([item for item in windows if "bytes_examined" in item]),
        "windows": windows,
        "raw_bytes_emitted": False,
    }


def analyze(path: Path = SOURCE) -> dict:
    prior = authenticate_prior_report(PRIOR_CODE_REPORT)
    with Reader(path) as reader:
        before = reader.stat()
        if not stat.S_ISREG(before.st_mode):
            raise ValueError("source is not a regular file")
        if before.st_size != EXPECTED_SIZE:
            raise ValueError("authenticated ARM64 source size mismatch")
        digest_before = reader.sha256(before.st_size)
        if digest_before != EXPECTED_SHA256:
            raise ValueError("authenticated ARM64 source identity mismatch")
        header = reader.read(0, min(64 * 1024, before.st_size))
        pe, sections = parse_pe(header)
        if pe["machine"] != EXPECTED_MACHINE:
            raise ValueError("source is not AA64")
        if rva_to_raw(sections, SLOT_RVA, 32) is None:
            raise ValueError("slot is not fully file-backed")
        if rva_to_raw(sections, POST_RVA, 1) is None:
            raise ValueError("post-slot target is not file-backed")
        relocations, relocation_report = parse_relocations(reader, pe, sections)
        target_rvas = {POST_RVA}
        edges, alias_scan = scan_aliases(reader, pe, sections, relocations, target_rvas)
        discovered = {POST_RVA}
        frontier = deque([(POST_RVA, 0)])
        chain_edges = []
        while frontier:
            target, level = frontier.popleft()
            if level >= MAX_LEVELS:
                continue
            for edge in edges.get(target, []):
                source = int(edge["source_rva"], 16)
                if len(discovered) >= MAX_NODES and source not in discovered:
                    break
                chain_edges.append({**edge, "level": level + 1})
                if edge.get("confirmed_pointer") and source not in discovered:
                    discovered.add(source)
                    frontier.append((source, level + 1))
                    target_rvas.add(source)
        windows = bounded_windows(reader, sections, sorted(discovered))
        after_digest = reader.sha256(before.st_size)
        after = reader.stat()
        stable = digest_before == after_digest and same_identity(before, after)
        if not stable:
            raise ValueError("source changed during held-fd analysis")
        return {
            "schema": "wowforever.arm64-trust-alias.v1",
            "scope": {
                "host_only_read_only": True,
                "source_never_written": True,
                "no_client_execution_or_runtime_memory": True,
                "no_key_or_secret_bytes_emitted": True,
                "max_reverse_levels": MAX_LEVELS,
                "max_nodes": MAX_NODES,
                "max_window_bytes": MAX_WINDOW_BYTES,
                "max_alias_scan_bytes": MAX_ALIAS_SCAN_BYTES,
                "ghidra_imports_performed": 0,
                "full_auto_scan_repeated": False,
            },
            "source": {
                "path": str(path),
                "size": before.st_size,
                "sha256_before": digest_before,
                "sha256_after": after_digest,
                "expected_size": EXPECTED_SIZE,
                "expected_sha256": EXPECTED_SHA256,
                "stable_held_fd": stable,
                "stat_before": identity_record(before),
                "stat_after": identity_record(after),
            },
            "pe": {
                "machine": f"0x{pe['machine']:x}",
                "image_base": f"0x{pe['image_base']:x}",
                "size_of_image": pe["size_of_image"],
                "sections": [{"name": item["name"], "rva": f"0x{item['rva']:x}", "raw_size": item["raw_size"], "virtual_size": item["virtual_size"]} for item in sections],
            },
            "post_slot": {
                "slot_rva": f"0x{SLOT_RVA:x}",
                "post_slot_rva": f"0x{POST_RVA:x}",
                "slot_storage": storage_for(sections, SLOT_RVA, 32),
                "post_slot_storage": storage_for(sections, POST_RVA, 1),
                "post_slot_offset": POST_RVA - SLOT_RVA,
                "relocation_overlap_slot": sum(SLOT_RVA <= item < SLOT_RVA + 32 for item in relocations),
                "relocation_overlap_post_slot": sum(POST_RVA <= item < POST_RVA + 1 for item in relocations),
            },
            "relocations": relocation_report,
            "alias_scan": alias_scan,
            "alias_graph": {
                "seed": f"0x{POST_RVA:x}",
                "known_prior_alias": f"0x{KNOWN_ALIAS_RVA:x}",
                "discovered_node_count": len(discovered),
                "edges": chain_edges,
                "max_levels_observed": max((edge["level"] for edge in chain_edges), default=0),
                "node_limit_hit": alias_scan["graph_limits"]["node_limit_hit"],
                "graph_truncation": alias_scan["graph_limits"],
                "graph_exhaustive_within_bounded_scan": not alias_scan["graph_limits"]["graph_truncated"],
                "raw_bytes_emitted": False,
            },
            "windows": windows,
            "code_consumer": {
                "status": "reused_prior_bounded_evidence" if prior["validated"] else "INCOMPLETE",
                "report_path": str(PRIOR_CODE_REPORT),
                "report_sha256": prior["report_sha256"] if prior["validated"] else None,
                "prior_report_authentication": prior,
                "evidence": None if not prior["validated"] else {
                    "alias_target_rva": f"0x{prior['parsed']['alias_target_rva']:x}",
                    "executable_bytes_examined": prior["parsed"]["executable_bytes_examined"],
                    "raw_seed_candidates": prior["parsed"]["raw_seed_candidates"],
                    "ghidra_attempted_seeds": prior["parsed"]["ghidra_attempted_seeds"],
                    "ghidra_confirmed_references": prior["parsed"]["ghidra_confirmed_references"],
                    "selected_functions": prior["parsed"]["selected_functions"],
                },
                "consumer_proven": False if prior["validated"] else None,
                "page_or_proximity_only": False,
                "no_new_code_scan": prior["validated"],
            },
            "io": {
                "analysis_bytes_read": reader.analysis_bytes,
                "full_hash_bytes_read": reader.hash_bytes,
                "full_hash_passes": 2,
                "total_reader_bytes_read": reader.analysis_bytes + reader.hash_bytes,
                "prior_report_bytes_read": prior["bytes_read"],
                "prior_report_hash_included_in_prior_report_bytes": True,
                "alias_scan_unique_coverage_bytes": alias_scan["unique_coverage_bytes"],
                "alias_scan_data_read_bytes_all_passes": alias_scan["bytes_examined"],
                "alias_scan_passes": alias_scan["passes"],
                "alias_scan_unique_coverage_limit": MAX_ALIAS_SCAN_BYTES,
                "hashes_reported_separately": True,
                "expected_full_hash_bytes": EXPECTED_SIZE * 2,
            },
            "assessment": {
                "alias_target_type": "initialized .rdata file-backed byte at post-slot RVA; exact pointer edge is DIR64 if present",
                "known_alias_found": any(
                    int(edge["source_rva"], 16) == KNOWN_ALIAS_RVA and edge.get("confirmed_pointer")
                    for edge in chain_edges
                ),
                "consumer_code_proven": False if prior["validated"] else None,
                "forever_group8_link": "not_proven",
                "negative_scope": "exact initialized .rdata/.data pointer coverage plus reused exact-code-reference evidence; no claim about runtime indirection or authentication",
            },
        }


def create_report(path: Path, report: dict) -> None:
    payload = (json.dumps(report, indent=2, sort_keys=True) + "\n").encode()
    if len(payload) > MAX_REPORT_BYTES:
        raise ValueError("report exceeds 2 MiB")
    parent = path.parent
    parent_stat = os.lstat(parent)
    if not stat.S_ISDIR(parent_stat.st_mode) or stat.S_ISLNK(parent_stat.st_mode):
        raise ValueError("report parent must be a non-symlink directory")
    if stat.S_IMODE(parent_stat.st_mode) & 0o077:
        raise PermissionError("report parent must already be private (0700 or stricter)")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as stream:
            fd = -1
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        if fd != -1:
            os.close(fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = analyze(args.source)
        create_report(args.output, report)
    except (OSError, ValueError, struct.error) as exc:
        print(f"arm64-trust-alias: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"output": str(args.output), "stable": report["source"]["stable_held_fd"], "alias": report["alias_graph"]["known_prior_alias"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
