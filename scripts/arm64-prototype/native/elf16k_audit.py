#!/usr/bin/env python3
"""Bounded ELF64/AArch64 PT_LOAD 16 KiB alignment audit."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


PAGE = 0x4000
PT_LOAD = 1
ELF_HEADER = 64
PROGRAM_HEADER = 56


class ElfAuditError(ValueError):
    pass


def audit_elf(data: bytes) -> list[dict[str, int]]:
    if len(data) < ELF_HEADER or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ElfAuditError("not a bounded ELF64 little-endian image")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ElfAuditError("ELF machine is not AArch64")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize = struct.unpack_from("<H", data, 54)[0]
    phnum = struct.unpack_from("<H", data, 56)[0]
    if phentsize < PROGRAM_HEADER or phnum == 0 or phnum > 256:
        raise ElfAuditError("program header table is malformed")
    table_end = phoff + phentsize * phnum
    if phoff < ELF_HEADER or table_end < phoff or table_end > len(data):
        raise ElfAuditError("program header table is outside the ELF")
    loads: list[dict[str, int]] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, _flags, p_offset, p_vaddr, _paddr, filesz, memsz, p_align = struct.unpack_from("<IIQQQQQQ", data, offset)
        if p_type != PT_LOAD:
            continue
        if p_align < PAGE or p_align % PAGE != 0:
            raise ElfAuditError(f"PT_LOAD {index} alignment is below 16 KiB")
        if p_offset % PAGE != p_vaddr % PAGE:
            raise ElfAuditError(f"PT_LOAD {index} offset/vaddr are not 16 KiB congruent")
        if p_offset + filesz < p_offset or p_offset + filesz > len(data):
            raise ElfAuditError(f"PT_LOAD {index} file range is outside the ELF")
        if p_vaddr + memsz < p_vaddr:
            raise ElfAuditError(f"PT_LOAD {index} virtual range overflows")
        loads.append({"index": index, "offset": p_offset, "vaddr": p_vaddr, "filesz": filesz, "memsz": memsz, "align": p_align})
    if not loads:
        raise ElfAuditError("ELF has no PT_LOAD entries")
    return loads


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("elf", type=Path)
    args = parser.parse_args()
    loads = audit_elf(args.elf.read_bytes())
    print(f"PT_LOAD_ALIGNMENT_16K PASS loads={len(loads)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
