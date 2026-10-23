"""Focused host-only tests for the bounded indirect trust-data graph."""

from __future__ import annotations

import importlib.util
import struct
import stat
from pathlib import Path


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/diagnostics/reverse-engineering/IndirectTrustData.py"
SPEC = importlib.util.spec_from_file_location("indirect_trust_data", MODULE_PATH)
assert SPEC and SPEC.loader
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


def _encode_adr(immediate: int, *, page: bool) -> int:
    encoded = immediate & ((1 << 21) - 1)
    return (0x90000000 if page else 0x10000000) | ((encoded & 3) << 29) | ((encoded >> 2) << 5)


def test_arm64_imm21_uses_immhi_shift_then_immlo() -> None:
    pc = 0x140100103
    for page in (False, True):
        for immediate in (1, 2, 3, -1, -2, -3, 0x12345, -0x12345):
            decoded = analysis.arm64_adr_target(pc, _encode_adr(immediate, page=page))
            assert decoded is not None
            expected = (pc & ~0xFFF) + immediate * 0x1000 if page else pc + immediate
            assert decoded[0] == expected


def test_known_bad_false_page_is_not_revived() -> None:
    decoded = analysis.arm64_adr_target(0x140E7B558, 0x9007AC94)
    assert decoded == (0x15040B000, "ADRP")
    assert decoded[0] != 0x144BDF000


def test_seed_scope_and_bounds_are_not_broad_key_scan() -> None:
    assert set(analysis.ROOTS) == {0x4C5F5E8, 0x4CCBA58}
    assert analysis.KNOWN_ROOT_REFERENCE == 0x4C5FEB8
    assert len(analysis.KNOWN_STRINGS) == 11
    assert analysis.MAX_LEVELS == 3
    assert analysis.MAX_NODES == 256
    assert analysis.MAX_FOLLOWED_DATA == 2 * 1024 * 1024
    assert analysis.MAX_METADATA == 16 * 1024 * 1024
    assert analysis.MAX_CODE_METADATA == 64 * 1024
    assert analysis.MAX_NEW_CODE_TARGETS == 24


def test_rtti_col_requires_self_pointer_and_rva_targets() -> None:
    sections = [{"name": ".rdata", "rva": 0x1000, "virtual_size": 0x1000, "raw_size": 0x1000, "raw_offset": 0}]
    pe = {"size_of_image": 0x3000, "size_of_headers": 0x200}
    data = bytearray(0x1000)
    # COL at 0x1100: signature, offsets, type RVA, class RVA, self RVA.
    data[0x100 : 0x118] = (
        (0).to_bytes(4, "little")
        + (4).to_bytes(4, "little")
        + (0).to_bytes(4, "little")
        + (0x1200).to_bytes(4, "little")
        + (0x1300).to_bytes(4, "little")
        + (0x1100).to_bytes(4, "little")
    )
    # The synthetic section does not cover 0x1200/0x1300 yet; add a second
    # virtual section so the proof checks target classification rather than IDs.
    sections.append({"name": ".rdata", "rva": 0x1200, "virtual_size": 0x200, "raw_size": 0x200, "raw_offset": 0x1000})
    sections.append({"name": ".rdata", "rva": 0x1300, "virtual_size": 0x100, "raw_size": 0x100, "raw_offset": 0x1200})
    data.extend(b"\0" * 0x400)
    assert analysis.parse_col(bytes(data), pe, sections, 0x1100) is not None
    data[0x114 : 0x118] = (0x1104).to_bytes(4, "little")
    assert analysis.parse_col(bytes(data), pe, sections, 0x1100) is None


def test_create_report_is_exclusive_and_private(tmp_path: Path) -> None:
    destination = tmp_path / "report.json"
    analysis.create_report(destination, {"ok": True})
    assert stat.S_IMODE(destination.stat().st_mode) == 0o600
    try:
        analysis.create_report(destination, {"overwrite": True})
    except FileExistsError:
        pass
    else:
        raise AssertionError("report writer overwrote an existing artifact")


def test_root_forward_descriptor_window_observes_code_pointer_without_alias_traversal() -> None:
    data = bytearray(0x400)
    sections = [
        {"name": ".rdata", "rva": 0x1000, "virtual_size": 0x200, "raw_size": 0x200, "raw_offset": 0, "characteristics": 0x40000040},
        {"name": ".text", "rva": 0x2000, "virtual_size": 0x100, "raw_size": 0x100, "raw_offset": 0x200, "characteristics": 0x60000020},
        {"name": ".shared", "rva": 0x2200, "virtual_size": 0x100, "raw_size": 0x100, "raw_offset": 0x300, "characteristics": 0x70000020},
    ]
    pe = {"image_base": 0x100000000, "size_of_image": 0x3000, "size_of_headers": 0x200}
    struct.pack_into("<Q", data, 0x48, pe["image_base"] + 0x2000)  # slot 0x1048
    struct.pack_into("<Q", data, 0x50, pe["image_base"] + 0x2200)  # shared/foreign
    struct.pack_into("<Q", data, 0x58, pe["image_base"] + 0x9000)  # invalid
    graph = analysis.BoundedGraph(data, pe, sections, {0x1048: 10, 0x1050: 10, 0x1058: 10})
    graph.add_node(0x1000, 0, "synthetic-root", seed=True)
    graph.add_node(0x1040, 1, "synthetic-descriptor")
    graph.edge(0x1000, 0x1040, "absolute_DIR64", "slot_rva=0x1000", "forward_data")
    graph.add_node(0x1080, 1, "synthetic-reverse-alias")
    graph.edge(0x1080, 0x1000, "absolute_DIR64", "slot_rva=0x1080", "reverse_alias_consumer")
    graph.follow_window(0x1040)
    paths = graph.forward_paths({0x1000: "synthetic-root"})
    assert 0x1040 in paths and 0x1080 not in paths
    result = graph.code_pointer_observations(
        paths,
        [{"begin_rva": 0x2000, "end_rva": 0x2040, "body_bytes": 0x40, "kind": "arm64-packed"}],
    )
    assert result["new_target_count"] == 1
    observation = result["observations"][0]
    assert observation["pointer_slot_rva"] == "0x1048"
    assert observation["target_rva"] == "0x2000"
    assert observation["target_section"] == ".text"
    assert observation["target_executable"] is True
    assert observation["containing_node"]["rva"] == "0x1040"
    assert observation["containing_node"]["role"] == "untyped_graph_data_node"
    assert observation["root_provenance"]["graph_path"] == ["0x1000", "0x1040"]
    assert result["excluded_slots"]["shared_target"] == 1
    assert result["excluded_slots"]["invalid_target"] == 1
    assert result["metadata_bytes_followed"] <= analysis.MAX_CODE_METADATA
