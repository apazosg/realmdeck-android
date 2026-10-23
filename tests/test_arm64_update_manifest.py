"""Synthetic and offline invariants for the host-only updater manifest gate."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import sqlite3
import struct
import sys
import zlib
from pathlib import Path

import pytest
from cascette_tools.formats.install import InstallEntry, InstallTag


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/arm64-prototype/client/update_manifest.py"
SPEC = importlib.util.spec_from_file_location("arm64_update_manifest", MODULE_PATH)
assert SPEC and SPEC.loader
gate = importlib.util.module_from_spec(SPEC)
sys.modules["arm64_update_manifest"] = gate
SPEC.loader.exec_module(gate)


def _mask(entry_count: int, indices: set[int]) -> bytes:
    result = bytearray((entry_count + 7) // 8)
    for index in indices:
        result[index >> 3] |= 0x80 >> (index & 7)
    return bytes(result)


def _db() -> sqlite3.Connection:
    db = sqlite3.connect(":memory:")
    gate._create_spool_tables(db)
    return db


def _download_fixture(
    *,
    ekeys: list[bytes],
    tags: list[tuple[str, int, set[int]]],
    version: int = 3,
    tag_masks: list[bytes] | None = None,
) -> bytes:
    checksum = version >= 2
    flag_size = 2 if version >= 2 else 0
    output = bytearray(
        b"DL"
        + bytes((version, 16, int(checksum)))
        + struct.pack(">I", len(ekeys))
        + struct.pack(">H", len(tags))
    )
    if version >= 2:
        output.append(flag_size)
    if version >= 3:
        output.extend(b"\x00\x00\x00\x00")
    for index, ekey in enumerate(ekeys):
        output.extend(ekey)
        output.extend((100 + index).to_bytes(5, "big"))
        output.extend(struct.pack("b", index - 2))
        if checksum:
            output.extend(bytes((index, 1, 2, 3)))
        if flag_size:
            output.extend(bytes((index, 0xA5)))
    for tag_index, (name, tag_type, selected) in enumerate(tags):
        output.extend(name.encode() + b"\x00" + struct.pack(">H", tag_type))
        output.extend(tag_masks[tag_index] if tag_masks is not None else _mask(len(ekeys), selected))
    return bytes(output)


def _size_fixture(
    *,
    prefixes: list[bytes],
    estimates: list[int],
    tags: list[tuple[str, int, set[int]]],
    tag_masks: list[bytes] | None = None,
    declared_total: int | None = None,
) -> bytes:
    assert len(prefixes) == len(estimates)
    output = bytearray(
        b"DS"
        + bytes((1, 9))
        + struct.pack(">I", len(prefixes))
        + struct.pack(">H", len(tags))
        + (sum(estimates) if declared_total is None else declared_total).to_bytes(5, "big")
    )
    for tag_index, (name, tag_type, selected) in enumerate(tags):
        output.extend(name.encode() + b"\x00" + struct.pack(">H", tag_type))
        output.extend(tag_masks[tag_index] if tag_masks is not None else _mask(len(prefixes), selected))
    for prefix, estimate in zip(prefixes, estimates, strict=True):
        assert len(prefix) == 9
        output.extend(prefix + struct.pack(">I", estimate))
    return bytes(output)


def _encoding_fixture(*, corrupt_ckey_count: bool = False, corrupt_checksum: bool = False) -> tuple[bytes, bytes, bytes]:
    ckey = bytes.fromhex("00112233445566778899aabbccddeeff")
    ekey = bytes.fromhex("ffeeddccbbaa99887766554433221100")
    ckey_page = bytearray(1024)
    ckey_page[:22] = b"\x01" + (123).to_bytes(5, "big") + ckey
    ckey_page[22:38] = ekey
    ekey_page = bytearray(1024)
    ekey_page[:25] = ekey + struct.pack(">I", 0) + (456).to_bytes(5, "big")
    ckey_digest = hashlib.md5(ckey_page).digest()
    if corrupt_checksum:
        ckey_digest = b"\x00" * 16
    ekey_digest = hashlib.md5(ekey_page).digest()
    ckey_pages = 2 if corrupt_ckey_count else 1
    header = (
        b"EN"
        + bytes((1, 16, 16))
        + struct.pack(">HHIIBI", 1, 1, ckey_pages, 1, 0, 5)
    )
    assert len(header) == 22
    espec = b"spec\x00"
    ckey_index = ckey + ckey_digest
    ekey_index = ekey + ekey_digest
    encoded = header + espec + ckey_index + bytes(ckey_page) + ekey_index + bytes(ekey_page)
    return encoded, ckey, ekey


def _multichunk_blte(payload: bytes, *, extended: bool = False) -> bytes:
    mode_chunk = b"Z" + zlib.compress(payload)
    header_size = 12 + (40 if extended else 24)
    result = bytearray(b"BLTE" + struct.pack(">I", header_size))
    result.extend(bytes((0x10 if extended else 0x0F,)))
    result.extend((1).to_bytes(3, "big"))
    result.extend(struct.pack(">II", len(mode_chunk), len(payload)))
    result.extend(hashlib.md5(mode_chunk).digest())
    if extended:
        result.extend(hashlib.md5(payload).digest())
    result.extend(mode_chunk)
    return bytes(result)


def test_tag_bitmap_selection_matches_filter_install_entries_exactly() -> None:
    count = 4
    masks = {
        "Windows": _mask(count, {0, 1}),
        "WindowsOther": _mask(count, {2}),
        "arm64": _mask(count, {0, 1, 2, 3}),
        "enUS": _mask(count, {0, 2, 3}),
        "beta": _mask(count, {2}),
    }
    install_tags = [
        InstallTag(name="Windows", tag_type=1, bit_mask=masks["Windows"]),
        InstallTag(name="WindowsOther", tag_type=1, bit_mask=masks["WindowsOther"]),
        InstallTag(name="arm64", tag_type=2, bit_mask=masks["arm64"]),
        InstallTag(name="enUS", tag_type=3, bit_mask=masks["enUS"]),
        InstallTag(name="beta", tag_type=4, bit_mask=masks["beta"]),
    ]
    entries = [
        InstallEntry(filename=f"file-{i}", md5_hash=bytes((i,)) * 16, size=i + 1)
        for i in range(count)
    ]
    chosen = gate.filter_install_entries(entries, install_tags, "Windows,WindowsOther", "arm64", "enUS")
    bitmap = gate.apply_tag_query(
        [gate.ManifestTag(tag.name, tag.tag_type, tag.bit_mask) for tag in install_tags],
        "Windows,WindowsOther,arm64,enUS",
        count,
    )
    selected_indices = [index for index in range(count) if gate._selected(bitmap, index)]
    assert [item.filename for item in chosen] == ["file-0", "file-2"]
    assert selected_indices == [0, 2]
    subtractive = gate.apply_tag_query(
        [gate.ManifestTag(tag.name, tag.tag_type, tag.bit_mask) for tag in install_tags],
        "Windows,WindowsOther,arm64,enUS,!beta",
        count,
    )
    assert [i for i in range(count) if gate._selected(subtractive, i)] == [0]


def test_download_manifest_streams_bounded_batches_and_emits_portable_record(tmp_path: Path) -> None:
    ekeys = [bytes((i,)) * 16 for i in range(4)]
    tags = [
        ("Windows", 1, {0, 1}),
        ("WindowsOther", 1, {2}),
        ("arm64", 2, {0, 1, 2, 3}),
        ("enUS", 3, {0, 2, 3}),
        ("beta", 4, {3}),
    ]
    db = _db()
    stats, parsed_tags, max_batch = gate.parse_download_manifest(
        io.BytesIO(_download_fixture(ekeys=ekeys, tags=tags)),
        db,
        query="Windows,WindowsOther,arm64,enUS,!beta",
        batch_rows=2,
    )
    assert stats.entry_count == 4
    assert stats.selected_count == 2
    assert stats.selected_ekey_count == 2
    assert stats.selected_bytes == 202
    assert max_batch <= 2
    db.execute("INSERT INTO encoding_ckeys VALUES(?,?,?)", (ekeys[0], bytes.fromhex("11" * 16), 77))
    output = tmp_path / "closure.jsonl"
    assert gate._emit_download_closure(
        db, parsed_tags, output, query="Windows,WindowsOther,arm64,enUS,!beta"
    ) == 2
    lines = [json.loads(line) for line in output.read_text().splitlines()]
    assert [line["download_manifest_entry_index"] for line in lines] == [0, 2]
    assert lines[0]["encoding_key"] == ekeys[0].hex()
    assert lines[0]["encoded_size"] == 100
    assert lines[0]["content_key"] == "11" * 16
    assert lines[0]["decoded_size"] == 77
    assert lines[0]["priority"] == -2
    assert lines[0]["checksum"] == "00010203"
    assert lines[0]["entry_flags"] == "00a5"
    assert lines[0]["manifest_tags"] == ["Windows", "arm64", "enUS"]
    assert lines[1]["matched_query_tags"] == ["WindowsOther", "arm64", "enUS"]


def test_download_parser_rejects_truncated_entries_and_tag_bitmaps() -> None:
    db = _db()
    # Header declares two records but only one is encoded.
    malformed_count = bytearray(_download_fixture(ekeys=[b"a" * 16], tags=[]))
    struct.pack_into(">I", malformed_count, 5, 2)
    with pytest.raises(gate.GateError, match="truncated download entry 1"):
        gate.parse_download_manifest(io.BytesIO(malformed_count), db)

    header = b"DL" + bytes((1, 16, 0)) + struct.pack(">I", 1) + struct.pack(">H", 1)
    body = b"k" * 16 + (5).to_bytes(5, "big") + b"\x00" + b"Windows\x00" + struct.pack(">H", 1)
    with pytest.raises(gate.GateError, match="download tag 'Windows' bitmap"):
        gate.parse_download_manifest(io.BytesIO(header + body), _db())


def test_size_manifest_selects_by_its_own_tags_and_checks_total() -> None:
    tags = [("enUS", 3, {0, 2}), ("arm64", 2, {0, 1, 2}), ("Windows", 1, {0, 2})]
    fixture = _size_fixture(
        prefixes=[bytes((i,)) * 9 for i in range(3)],
        estimates=[10, 20, 30],
        tags=tags,
    )
    db = _db()
    stats, parsed_tags, max_batch = gate.parse_size_manifest(io.BytesIO(fixture), db, batch_rows=1)
    assert stats.entry_count == 3
    assert stats.selected_count == 2
    assert stats.selected_bytes == 40
    assert stats.total_bytes == 60
    assert max_batch == 1
    assert parsed_tags[0].name == "enUS"  # Tag order is intentionally independent.
    with pytest.raises(gate.GateError, match="size total mismatch"):
        gate.parse_size_manifest(
            io.BytesIO(
                _size_fixture(
                    prefixes=[b"x" * 9],
                    estimates=[2],
                    tags=[],
                    declared_total=3,
                )
            ),
            _db(),
        )


def test_tag_mask_length_and_corrupt_size_count_are_rejected() -> None:
    with pytest.raises(gate.GateError, match="bitmap length"):
        gate.apply_tag_query([gate.ManifestTag("Windows", 1, b"")], "Windows", 2)
    bad_count = bytearray(_size_fixture(prefixes=[b"x" * 9], estimates=[2], tags=[]))
    struct.pack_into(">I", bad_count, 4, 2)
    with pytest.raises(gate.GateError, match="truncated size entry 1"):
        gate.parse_size_manifest(io.BytesIO(bad_count), _db())


def test_peak_bitmap_accounting_includes_both_live_manifests() -> None:
    download = gate.ManifestStats(
        entry_count=24,
        selected_count=8,
        selected_bytes=0,
        total_bytes=0,
        tag_bitmap_bytes=13,
        tag_mask_bytes=10,
        selection_bitmap_bytes=3,
    )
    size = gate.ManifestStats(
        entry_count=24,
        selected_count=8,
        selected_bytes=0,
        total_bytes=0,
        tag_bitmap_bytes=7,
        tag_mask_bytes=4,
        selection_bitmap_bytes=3,
    )
    assert gate.peak_tag_bitmap_storage_bytes(download, size) == 23


def test_encoding_pages_are_paged_indexed_and_checksum_validated() -> None:
    fixture, ckey, ekey = _encoding_fixture()
    db = _db()
    stats = gate.parse_encoding_pages(io.BytesIO(fixture), len(fixture), db, batch_rows=1)
    assert (stats.ckey_count, stats.ekey_count, stats.ckey_mapping_count) == (1, 1, 1)
    assert db.execute("SELECT encoded_size FROM encoding_ekeys WHERE ekey=?", (ekey,)).fetchone() == (456,)
    assert db.execute("SELECT decoded_size FROM encoding_ckeys WHERE ekey=? AND ckey=?", (ekey, ckey)).fetchone() == (123,)

    bad_checksum, _, _ = _encoding_fixture(corrupt_checksum=True)
    with pytest.raises(gate.GateError, match="CKey page 0 checksum mismatch"):
        gate.parse_encoding_pages(io.BytesIO(bad_checksum), len(bad_checksum), _db())

    bad_geometry, _, _ = _encoding_fixture(corrupt_ckey_count=True)
    with pytest.raises(gate.GateError, match="geometry/count mismatch"):
        gate.parse_encoding_pages(io.BytesIO(bad_geometry), len(bad_geometry), _db())


def test_streaming_blte_n_z_checks_sizes_checksums_and_unsupported_modes() -> None:
    payload = b"small synthetic manifest data" * 100
    for raw, modes in (
        (b"BLTE\x00\x00\x00\x00N" + payload, ["N"]),
        (_multichunk_blte(payload), ["Z"]),
        (_multichunk_blte(payload, extended=True), ["Z"]),
    ):
        output = io.BytesIO()
        result = gate.stream_decode_blte(io.BytesIO(raw), output)
        assert output.getvalue() == payload
        assert result["decoded_size"] == len(payload)
        assert result["decoded_md5"] == hashlib.md5(payload).hexdigest()
        assert result["blte_modes"] == modes

    unsupported = b"BLTE\x00\x00\x00\x00L" + b"ignored"
    with pytest.raises(gate.GateError, match="unsupported BLTE mode 'L'"):
        gate.stream_decode_blte(io.BytesIO(unsupported), io.BytesIO())

    truncated = _multichunk_blte(payload)[:-1]
    with pytest.raises(gate.GateError, match="truncated BLTE Z payload"):
        gate.stream_decode_blte(io.BytesIO(truncated), io.BytesIO())


def test_chunked_ekey_is_header_md5_not_whole_object_md5() -> None:
    payload = b"TACT chunked EKey evidence" * 11
    raw = _multichunk_blte(payload)
    header_size = struct.unpack(">I", raw[4:8])[0]
    expected_ekey = hashlib.md5(raw[:header_size]).hexdigest()
    whole_object_md5 = hashlib.md5(raw).hexdigest()
    assert expected_ekey != whole_object_md5

    decoded = io.BytesIO()
    evidence = gate.stream_decode_blte(
        io.BytesIO(raw), decoded, expected_ekey=expected_ekey, encoded_size=len(raw)
    )
    assert decoded.getvalue() == payload
    assert evidence["encoding_key"] == expected_ekey
    with pytest.raises(gate.GateError, match="metadata EKey mismatch"):
        gate.stream_decode_blte(
            io.BytesIO(raw), io.BytesIO(), expected_ekey=whole_object_md5, encoded_size=len(raw)
        )


def test_forged_huge_blte_chunk_count_is_rejected_before_descriptors() -> None:
    count = gate.MAX_BLTE_CHUNKS + 1
    header_size = 12 + count * 24
    raw = (
        b"BLTE"
        + struct.pack(">I", header_size)
        + b"\x0f"
        + count.to_bytes(3, "big")
    )
    with pytest.raises(gate.GateError, match="chunk count exceeds gate limit"):
        gate.stream_decode_blte(io.BytesIO(raw), io.BytesIO(), encoded_size=header_size)
