#!/usr/bin/env python3
"""Bounded, read-only search for standard cryptographic primitive anchors.

This scanner deliberately does not search for server keys, certificates, login
data, or the previously investigated 12x40 records.  It searches only exact
standard constants in the two supplied PE files and reports locations, not
constant bytes.  A hit is evidence that an implementation (or a general
purpose library) may be present; it is never evidence of trust-store linkage.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mmap
import os
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


MAX_FILE_BYTES = 200 * 1024 * 1024
MAX_DIRECTED_TABLES = 2
MAX_DIRECTED_REFS_PER_TABLE = 8
MAX_FUNCTION_BODY_BYTES = 64 * 1024
ARM64_MACHINE = 0xAA64
X64_MACHINE = 0x8664

DEFAULT_ARM64 = Path(
    "/home/adrian/wowforever-artifacts/references/official-arm64-beta/WowB-ARM64.exe"
)
DEFAULT_X64 = Path("/home/adrian/dev/wowforever/client/WowB.exe")


@dataclass(frozen=True)
class Signature:
    """A standard byte representation, with no embedded client-specific data."""

    family: str
    standard_name: str
    encoding: str
    pattern: bytes
    fingerprint_length: int
    sequence_words: int | None = None


def _integer_nth_root(value: int, degree: int) -> int:
    """Return floor(value ** (1 / degree)) using integer arithmetic only."""
    low, high = 0, 1
    while high**degree <= value:
        high <<= 1
    while high - low > 1:
        middle = (low + high) // 2
        if middle**degree <= value:
            low = middle
        else:
            high = middle
    return low


def _fractional_root_word(prime: int, degree: int, bits: int = 64) -> int:
    """Derive a FIPS round word from the fractional root of a prime.

    SHA-256 and SHA-512 K round constants use fractional cube roots of the
    first primes.  Scaling before taking the integer root avoids copying a
    third-party table.
    """
    scaled_floor = _integer_nth_root(prime << (bits * degree), degree)
    integer_part = _integer_nth_root(prime, degree)
    return scaled_floor - (integer_part << bits)


def _primes(count: int) -> list[int]:
    values: list[int] = []
    candidate = 2
    while len(values) < count:
        if all(candidate % divisor for divisor in range(2, int(candidate**0.5) + 1)):
            values.append(candidate)
        candidate += 1
    return values


def sha512_round_constants() -> tuple[int, ...]:
    return tuple(_fractional_root_word(p, 3) for p in _primes(80))


def sha256_round_constants() -> tuple[int, ...]:
    # SHA-256 K uses fractional cube roots; its initial H uses square roots.
    return tuple(_fractional_root_word(p, 3, 32) for p in _primes(64))


def _ed25519_constants() -> dict[str, int]:
    """Derive RFC 8032 field/order/basepoint values.

    RFC 8032 defines p=2^255-19, d=-121665/121666, the subgroup order l,
    and B=(x,y) with y=4/5 and the even x square root.  This is mathematical
    derivation, rather than a copied implementation table.
    """
    p = (1 << 255) - 19
    d = (-121665 * pow(121666, -1, p)) % p
    order = (1 << 252) + 27742317777372353535851937790883648493
    y = (4 * pow(5, -1, p)) % p
    numerator = (y * y - 1) % p
    denominator = (d * y * y + 1) % p
    u = numerator * pow(denominator, -1, p) % p
    x = pow(u, (p + 3) // 8, p)
    if x * x % p != u:
        x = x * pow(2, (p - 1) // 4, p) % p
    if x & 1:
        x = p - x
    assert x * x % p == u and not (x & 1)
    return {"p": p, "d": d, "order": order, "basepoint_x": x, "basepoint_y": y}


def _word_sequence_signatures(
    family: str, label: str, values: tuple[int, ...], word_size: int
) -> list[Signature]:
    """Build a full-table signature and every four-word complete subsequence."""
    signatures: list[Signature] = []
    # Keep one full table plus four-word windows.  Four standard consecutive
    # words are a deliberately high-discrimination positive, while tolerating
    # a compiler/linker that splits or trims a constant table.
    for count, start in [(len(values), 0)] + [(4, n) for n in range(len(values) - 3)]:
        selected = values[start : start + count]
        for endian, fmt in (("LE", "<"), ("BE", ">")):
            pattern = b"".join(struct.pack(f"{fmt}{'Q' if word_size == 8 else 'I'}", item) for item in selected)
            end = start + count - 1
            standard_name = f"{label} K[{start}..{end}]"
            signatures.append(
                Signature(
                    family=family,
                    standard_name=standard_name,
                    encoding=f"{word_size * 8}-bit-{endian}",
                    pattern=pattern,
                    fingerprint_length=len(pattern),
                    sequence_words=count,
                )
            )
    return signatures


def _ed_signature(name: str, value: int, encoding: str = "canonical-LE") -> Signature:
    pattern = value.to_bytes(32, "little" if encoding.endswith("LE") else "big")
    return Signature("ed25519", name, encoding, pattern, len(pattern))


def build_signatures() -> tuple[Signature, ...]:
    """Return only standard primitive fingerprints used by the scanner."""
    signatures = _word_sequence_signatures(
        "sha512", "SHA-512 round constants", sha512_round_constants(), 8
    )
    signatures.extend(
        _word_sequence_signatures(
            "sha256", "SHA-256 round constants", sha256_round_constants(), 4
        )
    )
    constants = _ed25519_constants()
    for key, name in (
        ("p", "Ed25519 field prime p (RFC8032)"),
        ("d", "Ed25519 Edwards parameter d (RFC8032)"),
        ("order", "Ed25519 subgroup order l (RFC8032)"),
        ("basepoint_x", "Ed25519 basepoint B.x (RFC8032)"),
        ("basepoint_y", "Ed25519 basepoint B.y (RFC8032)"),
    ):
        for encoding in ("canonical-LE", "canonical-BE"):
            signatures.append(_ed_signature(name, constants[key], encoding))
    # RFC8032's compressed basepoint is y with the sign bit of the even x.
    compressed = constants["basepoint_y"] | (constants["basepoint_x"] & 1) << 255
    for encoding in ("compressed-LE", "compressed-BE"):
        signatures.append(_ed_signature("Ed25519 compressed basepoint (RFC8032)", compressed, encoding))
    return tuple(signatures)


def _u16(data: bytes | mmap.mmap, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes | mmap.mmap, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _u64(data: bytes | mmap.mmap, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


def parse_pe(data: bytes | mmap.mmap) -> tuple[dict, list[dict]]:
    if len(data) < 0x40 or data[:2] != b"MZ":
        raise ValueError("source is not a DOS PE")
    pe_offset = _u32(data, 0x3C)
    if pe_offset + 24 > len(data) or data[pe_offset : pe_offset + 4] != b"PE\0\0":
        raise ValueError("invalid PE signature")
    file_header = pe_offset + 4
    machine = _u16(data, file_header)
    section_count = _u16(data, file_header + 2)
    optional_size = _u16(data, file_header + 16)
    optional = file_header + 20
    if optional + optional_size > len(data) or _u16(data, optional) != 0x20B:
        raise ValueError("expected PE32+ image")
    image_base = _u64(data, optional + 24)
    size_of_image = _u32(data, optional + 56)
    size_of_headers = _u32(data, optional + 60)
    directory_count = min(_u32(data, optional + 108), 16)
    directories = []
    for index in range(directory_count):
        entry = optional + 112 + index * 8
        if entry + 8 > optional + optional_size:
            break
        directories.append((_u32(data, entry), _u32(data, entry + 4)))
    section_table = optional + optional_size
    if section_table + section_count * 40 > len(data):
        raise ValueError("truncated PE section table")
    sections = []
    for index in range(section_count):
        offset = section_table + index * 40
        raw_name = data[offset : offset + 8].split(b"\0", 1)[0]
        sections.append(
            {
                "name": raw_name.decode("ascii", "replace"),
                "rva": _u32(data, offset + 12),
                "virtual_size": _u32(data, offset + 8),
                "raw_size": _u32(data, offset + 16),
                "raw_offset": _u32(data, offset + 20),
                "characteristics": _u32(data, offset + 36),
            }
        )
    return (
        {
            "machine": machine,
            "machine_name": {ARM64_MACHINE: "ARM64", X64_MACHINE: "x64"}.get(
                machine, f"0x{machine:04x}"
            ),
            "image_base": image_base,
            "size_of_image": size_of_image,
            "size_of_headers": size_of_headers,
            "directories": directories,
        },
        sections,
    )


def _section_for_file_offset(sections: list[dict], offset: int, size: int) -> dict | None:
    for section in sections:
        start = section["raw_offset"]
        end = start + section["raw_size"]
        if start <= offset and offset + size <= end:
            return section
    return None


def _rva_for_file_offset(sections: list[dict], offset: int, size: int) -> int | None:
    section = _section_for_file_offset(sections, offset, size)
    return None if section is None else section["rva"] + offset - section["raw_offset"]


def _find_all(data: bytes | mmap.mmap, pattern: bytes) -> Iterable[int]:
    start = 0
    while True:
        found = data.find(pattern, start)
        if found < 0:
            return
        yield found
        start = found + 1


def _deduplicate_contained(matches: list[dict]) -> list[dict]:
    """Prefer a full table over its contained four-word reports."""
    retained: list[dict] = []
    for item in sorted(matches, key=lambda value: (-value["validated_fingerprint_length"], value["file_offset"])):
        start = item["file_offset"]
        end = start + item["validated_fingerprint_length"]
        if any(
            prior["family"] == item["family"]
            and prior["encoding"] == item["encoding"]
            and prior["file_offset"] <= start
            and end <= prior["file_offset"] + prior["validated_fingerprint_length"]
            for prior in retained
        ):
            continue
        retained.append(item)
    return sorted(retained, key=lambda value: (value["file_offset"], value["family"], value["encoding"]))


def _sign_extend(value: int, bits: int) -> int:
    sign = 1 << (bits - 1)
    return value - (1 << bits) if value & sign else value


def _anchor_hit(target_rva: int, anchor: dict) -> bool:
    start = anchor.get("rva")
    if start is None:
        return False
    return start <= target_rva < start + anchor["validated_fingerprint_length"]


def _anchor_page_hit(page_rva: int, anchor: dict) -> bool:
    start = anchor.get("rva")
    if start is None:
        return False
    end = start + anchor["validated_fingerprint_length"]
    return page_rva < end and page_rva + 0x1000 > start


def _decode_add_immediate(word: int) -> tuple[int, int, int] | None:
    """Decode only 64-bit ADD (immediate): (rd, rn, byte displacement)."""
    if word & 0x7F000000 != 0x11000000 or (word >> 31) & 1 == 0 or (word >> 30) & 1:
        return None
    imm = (word >> 10) & 0xFFF
    if (word >> 22) & 1:
        imm <<= 12
    return word & 31, (word >> 5) & 31, imm


def _decode_ldr_unsigned(word: int) -> tuple[int, int, int] | None:
    """Decode scalar LDR unsigned immediate: (rt, rn, scaled displacement)."""
    if word & 0x3B000000 != 0x39000000 or not (word & 0x00400000):
        return None
    size = (word >> 30) & 3
    displacement = ((word >> 10) & 0xFFF) << size
    return word & 31, (word >> 5) & 31, displacement


def _writes_register(word: int, register: int) -> bool:
    """Conservative clobber fence for the small linear ARM64 follow window."""
    rd = word & 31
    # ADR/ADRP and data-processing immediate/logical instructions write Rd.
    if word & 0x1F000000 in (0x10000000, 0x11000000, 0x0A000000):
        return rd == register
    if word & 0x3B000000 == 0x39000000:
        return rd == register
    return False


def _arm64_reference(
    anchor: dict,
    instruction_rva: int,
    instruction: str,
    target_rva: int,
    related_instruction_rvas: list[int] | None = None,
) -> dict:
    return {
        "anchor_rva": anchor["rva"],
        "anchor_standard": anchor["standard_name"],
        "instruction_rva": instruction_rva,
        "related_instruction_rvas": related_instruction_rvas or [instruction_rva],
        "instruction": instruction,
        "resolved_target_rva": target_rva,
        "status": "address-reference-only",
        "flow_caveat": "linear bounded register follow; pdata/function boundary and register liveness not proven",
    }


def _arm64_directed_refs(
    data: bytes | mmap.mmap,
    pe: dict,
    sections: list[dict],
    candidates: list[dict],
) -> list[dict]:
    """Find bounded ADR/ADRP/LDR references to the selected anchor intervals."""
    if pe["machine"] != ARM64_MACHINE:
        return []
    text = next((section for section in sections if section["name"] == ".text"), None)
    if text is None:
        return []
    selected = candidates[:MAX_DIRECTED_TABLES]
    if not selected:
        return []
    start = text["raw_offset"]
    end = min(start + text["raw_size"], len(data))
    found: list[dict] = []
    for offset in range(start, max(start, end - 3), 4):
        word = _u32(data, offset)
        instruction_rva = text["rva"] + offset - start
        pc = pe["image_base"] + instruction_rva
        target: int | None = None
        kind = None
        register: int | None = None
        if word & 0x9F000000 == 0x90000000:  # ADRP
            immlo = (word >> 29) & 0x3
            immhi = (word >> 5) & 0x7FFFF
            target = (pc & ~0xFFF) + (_sign_extend((immhi << 2) | immlo, 21) << 12)
            kind = "ADRP-page"
            register = word & 31
        elif word & 0x9F000000 == 0x10000000:  # ADR
            immlo = (word >> 29) & 0x3
            immhi = (word >> 5) & 0x7FFFF
            target = pc + _sign_extend((immhi << 2) | immlo, 21)
            kind = "ADR"
        elif word & 0x3B000000 == 0x18000000:  # LDR literal
            target = pc + (_sign_extend((word >> 5) & 0x7FFFF, 19) << 2)
            kind = "LDR-literal"
        if target is None:
            continue
        target_rva = target - pe["image_base"]
        for candidate in selected:
            if (kind == "ADRP-page" and _anchor_page_hit(target_rva, candidate)) or (
                kind != "ADRP-page" and _anchor_hit(target_rva, candidate)
            ):
                found.append(_arm64_reference(candidate, instruction_rva, kind, target_rva))

        # Resolve only a short, linear ADRP register chain.  ADD and scaled
        # LDR are decoded explicitly; no claim is made about the containing
        # function or about a clobber outside this window.
        if kind == "ADRP-page" and register is not None:
            base_rva = target_rva
            related = [instruction_rva]
            for step in range(1, 4):
                next_offset = offset + step * 4
                if next_offset + 4 > end:
                    break
                next_word = _u32(data, next_offset)
                next_rva = instruction_rva + step * 4
                add = _decode_add_immediate(next_word)
                if add is not None and add[1] == register and add[0] == register:
                    base_rva += add[2]
                    related.append(next_rva)
                    continue
                ldr = _decode_ldr_unsigned(next_word)
                if ldr is not None and ldr[1] == register:
                    resolved = base_rva + ldr[2]
                    for candidate in selected:
                        if _anchor_hit(resolved, candidate):
                            found.append(
                                _arm64_reference(
                                    candidate,
                                    next_rva,
                                    "ADRP+ADD+LDR-unsigned",
                                    resolved,
                                    related + [next_rva],
                                )
                            )
                    break
                if _writes_register(next_word, register):
                    break
                related.append(next_rva)
            if len(found) >= MAX_DIRECTED_REFS_PER_TABLE * len(selected):
                return found
        if len(found) >= MAX_DIRECTED_REFS_PER_TABLE * len(selected):
            return found
    return found


def _x64_rip_relative_refs(
    data: bytes | mmap.mmap, pe: dict, sections: list[dict], candidates: list[dict]
) -> list[dict]:
    """Bounded x64 RIP-relative hypotheses using RVA, never file offsets."""
    if pe["machine"] != X64_MACHINE:
        return []
    text = next((section for section in sections if section["name"] == ".text"), None)
    if text is None:
        return []
    selected = candidates[:MAX_DIRECTED_TABLES]
    start = text["raw_offset"]
    end = min(start + text["raw_size"], len(data))
    found: list[dict] = []
    for offset in range(start, max(start, end - 7)):
        cursor = offset
        if 0x40 <= data[cursor] <= 0x4F:
            cursor += 1
        opcode = data[cursor]
        # Only one-byte MOV/LEA forms are accepted here.  SSE, C7, and other
        # opcode families remain raw-pattern hypotheses for later Ghidra
        # confirmation rather than being assigned a guessed instruction size.
        if opcode in (0x8B, 0x8D):
            modrm_offset = cursor + 1
            length = modrm_offset + 5 - offset
        else:
            continue
        if modrm_offset + 5 > end:
            continue
        modrm = data[modrm_offset]
        if modrm & 0xC7 != 0x05:
            continue
        disp_offset = modrm_offset + 1
        if disp_offset + 4 > end:
            continue
        instruction_rva = text["rva"] + offset - start
        target_rva = instruction_rva + length + struct.unpack_from("<i", data, disp_offset)[0]
        for candidate in selected:
            if _anchor_hit(target_rva, candidate):
                found.append(
                    {
                        "anchor_rva": candidate["rva"],
                        "anchor_standard": candidate["standard_name"],
                        "instruction_rva": instruction_rva,
                        "instruction": "RIP-relative-MOV/LEA-hypothesis",
                        "resolved_target_rva": target_rva,
                        "status": "raw-pattern-hypothesis",
                        "confirmation_required": "Ghidra instruction decode before consumer claim",
                    }
                )
                if len(found) >= MAX_DIRECTED_REFS_PER_TABLE * len(selected):
                    return found
    return found


def _select_anchor_set(matches: list[dict]) -> list[dict]:
    """Select five distinct Ed25519 locations plus one adjacent full SHA-512 table."""
    ed_locations: dict[int, list[dict]] = {}
    for item in matches:
        if item["family"] == "ed25519" and item["rva"] is not None:
            ed_locations.setdefault(item["file_offset"], []).append(item)
    selected: list[dict] = []
    for offset in sorted(ed_locations):
        representations = ed_locations[offset]
        representative = dict(sorted(representations, key=lambda item: item["standard_name"])[0])
        representative["representation_count"] = len(representations)
        selected.append(representative)
    full_sha512 = [
        item
        for item in matches
        if item["family"] == "sha512"
        and item["sequence_words"] == 80
        and item["rva"] is not None
    ]
    if full_sha512 and selected:
        nearest = min(
            full_sha512,
            key=lambda item: min(abs(item["file_offset"] - anchor["file_offset"]) for anchor in selected),
        )
        selected.append(dict(nearest))
    return selected


def _classify_pointer(value: int, pe: dict, intervals: list[tuple[int, int, str]]) -> tuple[str, int, str] | None:
    for start, end, label in intervals:
        if pe["image_base"] + start <= value < pe["image_base"] + end:
            return "raw-absolute-VA", value - pe["image_base"], label
        if start <= value < end:
            return "relative-RVA", value, label
    return None


def _data_reverse_refs(
    data: bytes | mmap.mmap,
    pe: dict,
    sections: list[dict],
    anchors: list[dict],
) -> dict:
    """Follow only bounded data-pointer reverse references to anchor intervals."""
    intervals = [
        (item["rva"], item["rva"] + item["validated_fingerprint_length"], item["standard_name"])
        for item in anchors
        if item.get("rva") is not None
    ]
    if not intervals:
        return {"references": [], "levels": 0, "pointer_slots_examined": 0, "followed_bytes": 0}

    def scan(wanted: list[tuple[int, int, str]], level: int, limit: int) -> list[dict]:
        results: list[dict] = []
        for section in sections:
            if section["name"] not in {".data", ".rdata", "_RDATA"}:
                continue
            start = section["raw_offset"]
            end = min(start + section["raw_size"], len(data))
            for offset in range(start, max(start, end - 7), 8):
                value = _u64(data, offset)
                match = _classify_pointer(value, pe, wanted)
                if match is None:
                    continue
                source_rva = section["rva"] + offset - start
                results.append(
                    {
                        "level": level,
                        "source_slot_rva": source_rva,
                        "source_section": section["name"],
                        "encoding": match[0],
                        "target_rva": match[1],
                        "target_label": match[2],
                        "status": "numeric-pointer-reference; table identity not inferred",
                    }
                )
                if len(results) >= limit:
                    return results
        return results

    references: list[dict] = []
    wanted = intervals
    pointer_slots_examined = 0
    nodes = 0
    for level in range(2):
        remaining_slots = 64 - pointer_slots_examined
        if remaining_slots <= 0:
            break
        level_refs = scan(
            wanted,
            level,
            min(MAX_DIRECTED_REFS_PER_TABLE * len(anchors), remaining_slots),
        )
        pointer_slots_examined += len(level_refs)
        if not level_refs:
            break
        references.extend(level_refs)
        nodes += len(level_refs)
        if nodes >= 128 or pointer_slots_examined >= 64:
            break
        # Reverse one more pointer level: look for pointers to the slots just
        # found, while retaining the same absolute-VA/RVA classification.
        wanted = [
            (item["source_slot_rva"], item["source_slot_rva"] + 8, f"slot@0x{item['source_slot_rva']:x}")
            for item in level_refs
        ]
    return {
        "references": references[:128],
        "levels": min(2, len({item["level"] for item in references})),
        "pointer_slots_examined": pointer_slots_examined,
        "node_limit": 128,
        "pointer_slot_limit": 64,
        "follow_budget_bytes": 1024 * 1024,
        "followed_bytes": min(len(references) * 256, 1024 * 1024),
    }


def _file_digest(path: Path) -> tuple[dict, str]:
    stat = path.stat()
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return (
        {"size": stat.st_size, "mtime_ns": stat.st_mtime_ns, "inode": stat.st_ino, "dev": stat.st_dev},
        digest.hexdigest(),
    )


def analyze(path: Path, expected_machine: int | None = None) -> dict:
    """Scan one PE independently, with no client-specific key material loaded."""
    before_stat, before_sha = _file_digest(path)
    if before_stat["size"] > MAX_FILE_BYTES:
        raise ValueError(f"input exceeds {MAX_FILE_BYTES} byte limit")
    with path.open("rb") as handle:
        with mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ) as data:
            pe, sections = parse_pe(data)
            if expected_machine is not None and pe["machine"] != expected_machine:
                raise ValueError(
                    f"wrong architecture: expected 0x{expected_machine:04x}, got 0x{pe['machine']:04x}"
                )
            matches = []
            for signature in build_signatures():
                for offset in _find_all(data, signature.pattern):
                    rva = _rva_for_file_offset(sections, offset, signature.fingerprint_length)
                    section = _section_for_file_offset(sections, offset, signature.fingerprint_length)
                    matches.append(
                        {
                            "family": signature.family,
                            "standard_name": signature.standard_name,
                            "encoding": signature.encoding,
                            "validated_fingerprint_length": signature.fingerprint_length,
                            "sequence_words": signature.sequence_words,
                            "file_offset": offset,
                            "rva": rva,
                            "section": section["name"] if section else "<header-or-overlay>",
                        }
                    )
            matches = _deduplicate_contained(matches)
            directed_candidates = _select_anchor_set(matches)
            directed = _arm64_directed_refs(data, pe, sections, directed_candidates)
            if pe["machine"] == X64_MACHINE:
                directed = _x64_rip_relative_refs(data, pe, sections, directed_candidates)
            data_refs = _data_reverse_refs(data, pe, sections, directed_candidates)
    after_stat, after_sha = _file_digest(path)
    counts = {family: sum(item["family"] == family for item in matches) for family in ("sha512", "sha256", "ed25519")}
    unique_counts = {
        family: len({item["file_offset"] for item in matches if item["family"] == family})
        for family in ("sha512", "sha256", "ed25519")
    }
    return {
        "source": {
            "path": str(path),
            "size": before_stat["size"],
            "sha256": before_sha,
            "stat_before": before_stat,
            "stat_after": after_stat,
            "sha256_after": after_sha,
            "stable": before_stat == after_stat and before_sha == after_sha,
        },
        "pe": {
            "machine": pe["machine_name"],
            "machine_numeric": pe["machine"],
            "image_base": pe["image_base"],
            "size_of_image": pe["size_of_image"],
            "sections": [
                {"name": s["name"], "rva": s["rva"], "raw_size": s["raw_size"], "characteristics": s["characteristics"]}
                for s in sections
            ],
        },
        "scope": {
            "max_input_bytes": MAX_FILE_BYTES,
            "workers": 1,
            "cpu_limit": 2,
            "only_file_backed_bytes": True,
            "server_key_scan": False,
            "known_12x40_scan": False,
        },
        "candidate_counts": counts,
        "unique_location_counts": unique_counts,
        "ed25519_representation_note": "six reported Ed25519 representations map to five distinct file/RVA locations; B.y and compressed-B are an intentional alias",
        "candidates": matches,
        "directed_anchor_set": [
            {
                "rva": item["rva"],
                "section": item["section"],
                "standard_name": item["standard_name"],
                "representation_count": item.get("representation_count", 1),
                "validated_fingerprint_length": item["validated_fingerprint_length"],
            }
            for item in directed_candidates
        ],
        "directed_references": directed,
        "data_reverse_references": data_refs,
        "ghidra_followup": {
            "executed": False,
            "reason": "No consumer claim made from these bounded address/pointer hypotheses; Ghidra requires a confirmed instruction/data seed first",
            "reserved_limits": "fresh external project, -noanalysis, 180s, 4GiB, 2 CPUs, at most four 15s decompilations and 64KiB per body",
        },
        "interpretation": {
            "sha512": "SHA-512 primitive anchor only; may be general-purpose hashing or TLS support, not trust proof",
            "sha256": "SHA-256 primitive anchor only; may be general-purpose hashing or TLS support, not trust proof",
            "ed25519": "RFC8032 mathematical anchor only; no verifier, key store, or trust linkage established; not trust proof",
            "absence": "No match does not show the algorithm is absent: limb/immediate/generated/obfuscated representations are out of coverage",
        },
        "coverage": {
            "representations": "exact complete SHA-256/SHA-512 word sequences in explicit LE and BE, and canonical 32-byte RFC8032 values in LE and BE",
            "sha_sequence_rule": "full table or any complete consecutive four-word sequence; four words are the minimum fingerprint",
            "directed_reference_scope": "one fixed .text pass plus bounded .data/.rdata pointer reverse-reference passes; six selected anchors, max 64 pointer slots, two levels, 128 nodes, 1 MiB follow budget",
            "x64_decoder_caveat": "only one-byte MOV/LEA RIP-relative ModRM hypotheses are emitted; SSE/C7 and other forms are not assigned guessed lengths and require Ghidra confirmation",
            "not_scanned": "server keys, certificates, login/account data, instructions interpreted as cryptographic code, or memory",
            "rva_caveat": "RVAs are file mappings only; ARM64 and x64 RVAs/functions are not compared",
        },
    }


def write_exclusive_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.fchmod(fd, 0o600)
        payload = (json.dumps(report, indent=2, sort_keys=True) + "\n").encode()
        if len(payload) > 200 * 1024:
            raise ValueError("report exceeds 200 KiB")
        with os.fdopen(fd, "wb") as handle:
            fd = -1
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
    finally:
        if fd != -1:
            os.close(fd)


def _machine_arg(value: str) -> int:
    values = {"arm64": ARM64_MACHINE, "x64": X64_MACHINE}
    try:
        return values[value.lower()]
    except KeyError as exc:
        raise argparse.ArgumentTypeError("architecture must be arm64 or x64") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arm64", type=Path, default=DEFAULT_ARM64)
    parser.add_argument("--x64", type=Path, default=DEFAULT_X64)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = {
            "schema": "wowforever.crypto-primitive-anchors.v1",
            "inputs": [
                analyze(args.arm64, ARM64_MACHINE),
                analyze(args.x64, X64_MACHINE),
            ],
            "cross_architecture": "independent scans only; no same-RVA or same-function inference",
        }
        write_exclusive_report(args.output, report)
    except (OSError, ValueError, struct.error) as exc:
        print(f"CryptoPrimitiveAnchors.py: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"output": str(args.output), "inputs_stable": [item["source"]["stable"] for item in report["inputs"]]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
