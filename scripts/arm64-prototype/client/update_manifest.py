#!/usr/bin/env python3
"""Host-only, disk-backed proof of the pinned ARM64 download manifest closure.

This is a metadata gate, not an updater or downloader. It reads only the
immutable cascette CDN cache, writes reports outside the repository, and never
opens the live client tree. The large download, size, and encoding manifests
are parsed a page/record at a time; indexes and selected records are spooled to
SQLite instead of being retained as Python object lists.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import struct
import tempfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

from cascette_tools.core.cache import DiskCache
from cascette_tools.formats.config import BuildConfigParser, CDNConfigParser
from cascette_tools.formats.install import InstallParser
from cascette_tools.core.utils import PathNormalizer

from forever_client.casc import filter_install_entries


PRODUCT = "wow_classic_beta"
REGION = "us"
PLATFORM = "Windows"
ARCH = "arm64"
LOCALE = "enUS"
VERSION = "1.60.1.70009"
BUILD_CONFIG = "05215079e3905ef5922ae0b03ffefb73"
CDN_CONFIG = "9b3c456dbb837d133a026d380c7c13e9"
CDN_PATH = "tpr/wow"
TAG_QUERY = "Windows,arm64,enUS"

DEFAULT_OUTPUT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/client-updater-manifest-gate"
)
DEFAULT_CATALOG = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/client-bootstrap/catalog.json"
)
CATALOG_SHA256 = "3cee581d681a5439c138955edd3c39ff2c799689b3889a47cd41c6762e6ff1ce"

READ_BYTES = 1 << 20
SQLITE_CACHE_BYTES = 8 << 20
SQL_BATCH_ROWS = 2048
MAX_ENCODING_PAGE_BYTES = 1 << 20
MAX_TAG_NAME_BYTES = 4096
MAX_BLTE_CHUNKS = 16_384
MAX_BLTE_CHUNK_BYTES = 128 << 20
MAX_BLTE_HEADER_BYTES = 12 + MAX_BLTE_CHUNKS * 40


class GateError(ValueError):
    """The pinned metadata is absent, malformed, unsupported, or inconsistent."""


@dataclass(frozen=True)
class ManifestTag:
    name: str
    tag_type: int
    mask: bytes


@dataclass(frozen=True)
class ManifestStats:
    entry_count: int
    selected_count: int
    selected_bytes: int
    total_bytes: int
    selected_ekey_count: int = 0
    tag_count: int = 0
    tag_bitmap_bytes: int = 0
    tag_mask_bytes: int = 0
    selection_bitmap_bytes: int = 0


def peak_tag_bitmap_storage_bytes(download: ManifestStats, size: ManifestStats) -> int:
    """Conservative peak while both manifests' tag masks remain live.

    Tag masks from download stay resident while size is parsed. During bitmap
    application, account for the result, one OR-group workspace, and the
    returned immutable bitmap copy (three selection-sized buffers).
    """
    download_phase = download.tag_mask_bytes + 3 * download.selection_bitmap_bytes
    size_phase = (
        download.tag_mask_bytes
        + size.tag_mask_bytes
        + 3 * size.selection_bitmap_bytes
    )
    return max(download_phase, size_phase)


@dataclass(frozen=True)
class EncodingStats:
    ckey_count: int
    ekey_count: int
    ckey_mapping_count: int
    ckey_page_count: int
    ekey_page_count: int
    max_page_bytes: int
    espects: int
    trailing_bytes: int
    trailing_sha256: str


def _read_exact(stream: BinaryIO, size: int, context: str) -> bytes:
    value = stream.read(size)
    if len(value) != size:
        raise GateError(f"truncated {context}: expected {size} bytes, got {len(value)}")
    return value


def _read_cstring(stream: BinaryIO, context: str) -> str:
    raw = bytearray()
    while len(raw) <= MAX_TAG_NAME_BYTES:
        byte = stream.read(1)
        if not byte:
            raise GateError(f"truncated NUL-terminated {context}")
        if byte == b"\x00":
            try:
                return raw.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise GateError(f"invalid UTF-8 in {context}: {exc}") from exc
        raw.extend(byte)
    raise GateError(f"{context} exceeds {MAX_TAG_NAME_BYTES} bytes")


def _assert_eof(stream: BinaryIO, context: str) -> None:
    if stream.read(1):
        raise GateError(f"unexpected trailing bytes in {context}")


def _parse_query(query: str) -> list[tuple[str, bool]]:
    parsed: list[tuple[str, bool]] = []
    for item in re.split(r"[,?:]", query):
        item = item.strip()
        if item:
            subtractive = item.startswith("!")
            name = item[1:] if subtractive else item
            if name:
                parsed.append((name, subtractive))
    return parsed


def apply_tag_query(tags: list[ManifestTag], query: str, entry_count: int) -> bytes:
    """Mirror cascette ``apply_tag_query`` / ``filter_install_entries`` bitmap logic.

    Requested tags are ORed inside each tag_type group and groups are ANDed;
    subtractive tags clear bits afterward. Unknown names are ignored, and an
    empty/unrecognized query keeps the legacy all-selected behavior.
    """
    if entry_count < 0:
        raise GateError("negative manifest entry count")
    mask_size = (entry_count + 7) // 8
    for tag in tags:
        if len(tag.mask) != mask_size:
            raise GateError(
                f"tag {tag.name!r} bitmap length {len(tag.mask)} != expected {mask_size}"
            )
    if not tags or not query:
        return b"\xff" * mask_size

    parsed = _parse_query(query)
    tag_map = {tag.name: tag for tag in tags}
    additive: list[ManifestTag] = []
    subtractive: list[ManifestTag] = []
    for name, is_subtractive in parsed:
        tag = tag_map.get(name)
        if tag is not None:
            (subtractive if is_subtractive else additive).append(tag)
    if not additive and not subtractive:
        return b"\xff" * mask_size

    result = bytearray(b"\xff" * mask_size)
    if additive:
        groups: dict[int, list[ManifestTag]] = {}
        for tag in additive:
            groups.setdefault(tag.tag_type, []).append(tag)
        for group in groups.values():
            group_mask = bytearray(mask_size)
            for tag in group:
                for offset, value in enumerate(tag.mask):
                    group_mask[offset] |= value
            for offset, value in enumerate(group_mask):
                result[offset] &= value
    for tag in subtractive:
        for offset, value in enumerate(tag.mask):
            result[offset] &= ~value
    return bytes(result)


def _selected(bitmap: bytes, index: int) -> bool:
    return bool(bitmap[index >> 3] & (0x80 >> (index & 7)))


def _tag_names_at(tags: list[ManifestTag], index: int) -> list[str]:
    return [tag.name for tag in tags if _selected(tag.mask, index)]


def _matched_query_tags(tags: list[ManifestTag], index: int, query: str) -> list[str]:
    requested = {name for name, subtractive in _parse_query(query) if not subtractive}
    return [tag.name for tag in tags if tag.name in requested and _selected(tag.mask, index)]


def _read_download_tags(stream: BinaryIO, tag_count: int, entry_count: int) -> list[ManifestTag]:
    mask_size = (entry_count + 7) // 8
    tags: list[ManifestTag] = []
    seen: set[str] = set()
    for index in range(tag_count):
        name = _read_cstring(stream, f"download tag {index} name")
        tag_type = struct.unpack(">H", _read_exact(stream, 2, f"download tag {name!r} type"))[0]
        mask = _read_exact(stream, mask_size, f"download tag {name!r} bitmap")
        if name in seen:
            raise GateError(f"duplicate download tag name: {name!r}")
        seen.add(name)
        tags.append(ManifestTag(name, tag_type, mask))
    return tags


def _create_spool_tables(db: sqlite3.Connection) -> None:
    db.executescript(
        """
        CREATE TABLE download_entries (
            entry_index INTEGER PRIMARY KEY,
            ekey BLOB NOT NULL,
            encoded_size INTEGER NOT NULL,
            priority INTEGER NOT NULL,
            checksum BLOB,
            flags BLOB,
            selected INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX download_selected_ekey_idx
            ON download_entries(selected, ekey, entry_index);
        CREATE TABLE size_entries (
            entry_index INTEGER PRIMARY KEY,
            ekey_prefix BLOB NOT NULL,
            estimated_size INTEGER NOT NULL
        );
        CREATE TABLE encoding_ekeys (
            ekey BLOB PRIMARY KEY,
            encoded_size INTEGER NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE encoding_ckeys (
            ekey BLOB NOT NULL,
            ckey BLOB NOT NULL,
            decoded_size INTEGER NOT NULL,
            PRIMARY KEY (ekey, ckey)
        ) WITHOUT ROWID;
        """
    )


def parse_download_manifest(
    stream: BinaryIO,
    db: sqlite3.Connection,
    *,
    query: str = TAG_QUERY,
    batch_rows: int = SQL_BATCH_ROWS,
) -> tuple[ManifestStats, list[ManifestTag], int]:
    """Stream a DL manifest into SQLite and apply the exact tag bitmap query."""
    if batch_rows <= 0:
        raise GateError("download spool batch size must be positive")
    header = _read_exact(stream, 11, "download header")
    if header[:2] != b"DL":
        raise GateError(f"download manifest magic mismatch: {header[:2].hex()}")
    version, ekey_size, has_checksum = header[2], header[3], header[4]
    entry_count = struct.unpack(">I", header[5:9])[0]
    tag_count = struct.unpack(">H", header[9:11])[0]
    if version not in (1, 2, 3):
        raise GateError(f"unsupported download manifest version: {version}")
    if ekey_size != 16:
        raise GateError(f"download EKey width is {ekey_size}; full 16-byte EKeys required")
    if has_checksum not in (0, 1):
        raise GateError(f"invalid download checksum flag: {has_checksum}")
    flag_size = _read_exact(stream, 1, "download V2 flag_size")[0] if version >= 2 else 0
    if version >= 3:
        _read_exact(stream, 4, "download V3 priority/reserved header")

    pending: list[tuple[object, ...]] = []
    max_batch = 0
    total_bytes = 0
    for index in range(entry_count):
        ekey = _read_exact(stream, ekey_size, f"download entry {index} EKey")
        encoded_size = int.from_bytes(_read_exact(stream, 5, f"download entry {index} size"), "big")
        priority = struct.unpack("b", _read_exact(stream, 1, f"download entry {index} priority"))[0]
        checksum = _read_exact(stream, 4, f"download entry {index} checksum") if has_checksum else None
        flags = _read_exact(stream, flag_size, f"download entry {index} flags") if flag_size else None
        if encoded_size <= 0:
            raise GateError(f"download entry {index} has non-positive encoded size")
        total_bytes += encoded_size
        pending.append((index, ekey, encoded_size, priority, checksum, flags))
        if len(pending) == batch_rows:
            db.executemany(
                "INSERT INTO download_entries(entry_index,ekey,encoded_size,priority,checksum,flags) "
                "VALUES(?,?,?,?,?,?)",
                pending,
            )
            max_batch = max(max_batch, len(pending))
            pending.clear()
    if pending:
        db.executemany(
            "INSERT INTO download_entries(entry_index,ekey,encoded_size,priority,checksum,flags) "
            "VALUES(?,?,?,?,?,?)",
            pending,
        )
        max_batch = max(max_batch, len(pending))
    db.commit()

    tags = _read_download_tags(stream, tag_count, entry_count)
    selection = apply_tag_query(tags, query, entry_count)
    _assert_eof(stream, "download manifest")

    for start in range(0, entry_count, batch_rows):
        updates = [
            (index,)
            for index in range(start, min(start + batch_rows, entry_count))
            if _selected(selection, index)
        ]
        if updates:
            db.executemany("UPDATE download_entries SET selected=1 WHERE entry_index=?", updates)
            max_batch = max(max_batch, len(updates))
    db.commit()
    selected_count, selected_bytes, unique_ekeys = db.execute(
        "SELECT COUNT(*),COALESCE(SUM(encoded_size),0),COUNT(DISTINCT ekey) "
        "FROM download_entries WHERE selected=1"
    ).fetchone()
    stats = ManifestStats(
        entry_count=entry_count,
        selected_count=selected_count,
        selected_bytes=selected_bytes,
        total_bytes=total_bytes,
        selected_ekey_count=unique_ekeys,
        tag_count=len(tags),
        tag_bitmap_bytes=sum(len(tag.mask) for tag in tags) + len(selection),
        tag_mask_bytes=sum(len(tag.mask) for tag in tags),
        selection_bitmap_bytes=len(selection),
    )
    return stats, tags, max_batch


def _read_size_tags(stream: BinaryIO, tag_count: int, entry_count: int) -> list[ManifestTag]:
    mask_size = (entry_count + 7) // 8
    tags: list[ManifestTag] = []
    seen: set[str] = set()
    for index in range(tag_count):
        name = _read_cstring(stream, f"size tag {index} name")
        tag_type = struct.unpack(">H", _read_exact(stream, 2, f"size tag {name!r} type"))[0]
        if tag_type in (0, 0xFFFF):
            break
        mask = _read_exact(stream, mask_size, f"size tag {name!r} bitmap")
        if name in seen:
            raise GateError(f"duplicate size tag name: {name!r}")
        seen.add(name)
        tags.append(ManifestTag(name, tag_type, mask))
    return tags


def parse_size_manifest(
    stream: BinaryIO,
    db: sqlite3.Connection,
    *,
    query: str = TAG_QUERY,
    batch_rows: int = SQL_BATCH_ROWS,
) -> tuple[ManifestStats, list[ManifestTag], int]:
    """Stream a DS manifest; selected partial keys are retained only on disk."""
    if batch_rows <= 0:
        raise GateError("size spool batch size must be positive")
    header = _read_exact(stream, 15, "size header")
    if header[:2] != b"DS":
        raise GateError(f"size manifest magic mismatch: {header[:2].hex()}")
    version, ekey_width = header[2], header[3]
    entry_count = struct.unpack(">I", header[4:8])[0]
    tag_count = struct.unpack(">H", header[8:10])[0]
    declared_total = int.from_bytes(header[10:15], "big")
    if version not in (1, 2):
        raise GateError(f"unsupported size manifest version: {version}")
    if ekey_width != 9:
        raise GateError(f"size EKey prefix width is {ekey_width}; pinned build requires 9 bytes")
    tags = _read_size_tags(stream, tag_count, entry_count)
    selection = apply_tag_query(tags, query, entry_count)

    total_bytes = 0
    selected_bytes = 0
    selected_count = 0
    pending: list[tuple[int, bytes, int]] = []
    max_batch = 0
    stride = ekey_width + 4
    for index in range(entry_count):
        prefix = _read_exact(stream, ekey_width, f"size entry {index} EKey prefix")
        estimate = struct.unpack(">I", _read_exact(stream, 4, f"size entry {index} estimate"))[0]
        total_bytes += estimate
        if _selected(selection, index):
            selected_count += 1
            selected_bytes += estimate
            pending.append((index, prefix, estimate))
            if len(pending) == batch_rows:
                db.executemany(
                    "INSERT INTO size_entries(entry_index,ekey_prefix,estimated_size) VALUES(?,?,?)",
                    pending,
                )
                max_batch = max(max_batch, len(pending))
                pending.clear()
    if pending:
        db.executemany(
            "INSERT INTO size_entries(entry_index,ekey_prefix,estimated_size) VALUES(?,?,?)",
            pending,
        )
        max_batch = max(max_batch, len(pending))
    db.commit()
    if total_bytes != declared_total:
        raise GateError(
            f"size total mismatch: entries sum={total_bytes} header total={declared_total}"
        )
    _assert_eof(stream, "size manifest")
    stats = ManifestStats(
        entry_count=entry_count,
        selected_count=selected_count,
        selected_bytes=selected_bytes,
        total_bytes=total_bytes,
        tag_count=len(tags),
        tag_bitmap_bytes=sum(len(tag.mask) for tag in tags) + len(selection),
        tag_mask_bytes=sum(len(tag.mask) for tag in tags),
        selection_bitmap_bytes=len(selection),
    )
    return stats, tags, max_batch


class _DecodedSink:
    def __init__(self, output: BinaryIO):
        self.output = output
        self.md5 = hashlib.md5()
        self.decoded_size = 0
        self.chunk_md5: object | None = None

    def write(self, data: bytes) -> None:
        if data:
            self.output.write(data)
            self.md5.update(data)
            if self.chunk_md5 is not None:
                self.chunk_md5.update(data)  # type: ignore[attr-defined]
            self.decoded_size += len(data)


class _DigestingReader:
    """Hash raw input bytes as the decoder consumes the same open stream."""

    def __init__(self, source: BinaryIO):
        self.source = source
        self.raw_sha256 = hashlib.sha256()
        self.bytes_read = 0

    def read(self, size: int = -1) -> bytes:
        data = self.source.read(size)
        if data:
            self.raw_sha256.update(data)
            self.bytes_read += len(data)
        return data


def _decompress_zlib_part(decoder: zlib.Decompress, data: bytes, sink: _DecodedSink) -> None:
    pending = data
    while True:
        try:
            output = decoder.decompress(pending, READ_BYTES)
        except zlib.error as exc:
            raise GateError(f"BLTE Z decompression failed: {exc}") from exc
        sink.write(output)
        if decoder.unused_data:
            raise GateError("BLTE Z chunk contains trailing compressed bytes")
        pending = decoder.unconsumed_tail
        if pending:
            continue
        if len(output) == READ_BYTES:
            pending = b""
            continue
        return


def stream_decode_blte(
    source: BinaryIO,
    output: BinaryIO,
    *,
    expected_ekey: str | None = None,
    encoded_size: int | None = None,
) -> dict[str, object]:
    """Decode plain or BLTE N/Z metadata to a file with bounded buffers.

    Encrypted, LZ4, frame, and unknown modes intentionally fail closed. BLTE
    encoded chunk checksums and extended decoded checksums are verified. EKeys
    follow TACT BLTE rules: MD5 of the complete object for headerSize=0, and
    MD5 of exactly the first headerSize bytes for chunked objects.
    """
    reader = _DigestingReader(source)
    sink = _DecodedSink(output)
    prefix = reader.read(4)

    def finish(ekey_digest: hashlib._Hash, flags: int | None, modes: list[str]) -> dict[str, object]:
        ekey = ekey_digest.hexdigest()
        if expected_ekey is not None and ekey != expected_ekey.lower():
            raise GateError(
                f"metadata EKey mismatch: computed={ekey} expected={expected_ekey.lower()}"
            )
        if encoded_size is not None and reader.bytes_read != encoded_size:
            raise GateError(
                f"metadata encoded length changed while reading: got={reader.bytes_read} expected={encoded_size}"
            )
        return {
            "decoded_size": sink.decoded_size,
            "decoded_md5": sink.md5.hexdigest(),
            "blte_flags": flags,
            "blte_modes": modes,
            "encoding_key": ekey,
            "raw_sha256": reader.raw_sha256.hexdigest(),
        }

    if prefix != b"BLTE":
        if len(prefix) != 4:
            raise GateError("metadata object is shorter than the BLTE signature")
        ekey_digest = hashlib.md5(prefix)
        sink.write(prefix)
        while True:
            part = reader.read(READ_BYTES)
            if not part:
                break
            ekey_digest.update(part)
            sink.write(part)
        return finish(ekey_digest, None, ["N"])

    header_size_raw = _read_exact(reader, 4, "BLTE header size")
    header_size = struct.unpack(">I", header_size_raw)[0]
    ekey_digest = hashlib.md5(prefix + header_size_raw)
    if header_size == 0:
        mode = _read_exact(reader, 1, "single-chunk BLTE mode")
        ekey_digest.update(mode)
        _decode_chunk(reader, None, mode, sink, encoded_hash=ekey_digest)
        _assert_eof(reader, "single-chunk BLTE")
        return finish(ekey_digest, None, [mode.decode("ascii")])

    if header_size < 12:
        raise GateError(f"invalid BLTE extended header size: {header_size}")
    if header_size > MAX_BLTE_HEADER_BYTES:
        raise GateError(
            f"BLTE header exceeds gate limit: {header_size} > {MAX_BLTE_HEADER_BYTES} bytes"
        )
    if encoded_size is not None and header_size > encoded_size:
        raise GateError(f"BLTE header size {header_size} exceeds encoded object size {encoded_size}")
    flags_raw = _read_exact(reader, 1, "BLTE flags")
    count_raw = _read_exact(reader, 3, "BLTE chunk count")
    ekey_digest.update(flags_raw + count_raw)
    flags = flags_raw[0]
    if flags not in (0x0F, 0x10):
        raise GateError(f"unsupported BLTE header flags: 0x{flags:02x}")
    chunk_count = int.from_bytes(count_raw, "big")
    if chunk_count == 0:
        raise GateError("chunked BLTE header declares zero chunks")
    if chunk_count > MAX_BLTE_CHUNKS:
        raise GateError(f"BLTE chunk count exceeds gate limit: {chunk_count} > {MAX_BLTE_CHUNKS}")
    extended = flags == 0x10
    record_size = 40 if extended else 24
    expected_header_size = 12 + chunk_count * record_size
    if header_size != expected_header_size:
        raise GateError(
            f"corrupt BLTE header length: header={header_size} chunks={chunk_count}"
        )
    if expected_header_size > MAX_BLTE_HEADER_BYTES:
        raise GateError(
            f"BLTE descriptor table exceeds gate limit: {expected_header_size} > {MAX_BLTE_HEADER_BYTES}"
        )

    # The descriptor list is allocated only after the chunk-count, header-size,
    # and header-geometry caps have all been checked.
    chunk_info: list[tuple[int, int, bytes, bytes | None]] = []
    encoded_payload_bytes = 0
    for index in range(chunk_count):
        descriptor = _read_exact(reader, record_size, f"BLTE chunk {index} descriptor")
        ekey_digest.update(descriptor)
        encoded_chunk_size = struct.unpack(">I", descriptor[:4])[0]
        decoded_chunk_size = struct.unpack(">I", descriptor[4:8])[0]
        checksum = descriptor[8:24]
        decoded_checksum = descriptor[24:40] if extended else None
        if encoded_chunk_size > MAX_BLTE_CHUNK_BYTES + 1:
            raise GateError(
                f"BLTE chunk {index} encoded size exceeds gate limit: "
                f"{encoded_chunk_size} > {MAX_BLTE_CHUNK_BYTES + 1}"
            )
        if decoded_chunk_size > MAX_BLTE_CHUNK_BYTES:
            raise GateError(
                f"BLTE chunk {index} decoded size exceeds gate limit: "
                f"{decoded_chunk_size} > {MAX_BLTE_CHUNK_BYTES}"
            )
        if encoded_chunk_size < 1:
            raise GateError(f"BLTE chunk {index} has zero encoded size")
        encoded_payload_bytes += encoded_chunk_size
        if encoded_size is not None and encoded_payload_bytes > encoded_size - header_size:
            raise GateError("BLTE chunk descriptors exceed remaining encoded object length")
        chunk_info.append((encoded_chunk_size, decoded_chunk_size, checksum, decoded_checksum))
    if reader.bytes_read != header_size:
        raise GateError(
            f"BLTE header read length mismatch: read={reader.bytes_read} header={header_size}"
        )
    if encoded_size is not None and encoded_payload_bytes != encoded_size - header_size:
        raise GateError(
            f"BLTE chunk payload lengths disagree with encoded object size: "
            f"chunks={encoded_payload_bytes} remaining={encoded_size - header_size}"
        )

    modes_seen: set[str] = set()
    for index, (encoded_chunk_size, decoded_chunk_size, checksum, decoded_checksum) in enumerate(chunk_info):
        mode = _read_exact(reader, 1, f"BLTE chunk {index} mode")
        modes_seen.add(mode.decode("ascii", errors="replace"))
        chunk_hash = hashlib.md5(mode)
        before_size = sink.decoded_size
        before_md5 = hashlib.md5()
        sink.chunk_md5 = before_md5
        _decode_chunk(
            reader,
            encoded_chunk_size - 1,
            mode,
            sink,
            encoded_hash=chunk_hash,
        )
        sink.chunk_md5 = None
        actual_decoded = sink.decoded_size - before_size
        if actual_decoded != decoded_chunk_size:
            raise GateError(
                f"BLTE chunk {index} decoded size mismatch: got={actual_decoded} expected={decoded_chunk_size}"
            )
        if chunk_hash.digest() != checksum:
            raise GateError(f"BLTE chunk {index} encoded checksum mismatch")
        if decoded_checksum is not None and before_md5.digest() != decoded_checksum:
            raise GateError(f"BLTE chunk {index} decoded checksum mismatch")
    _assert_eof(reader, "multi-chunk BLTE")
    return finish(ekey_digest, flags, sorted(modes_seen))


def _decode_chunk(
    source: BinaryIO,
    payload_length: int | None,
    mode: bytes,
    sink: _DecodedSink,
    *,
    encoded_hash: object | None = None,
) -> None:
    if mode == b"N":
        remaining = payload_length
        while remaining is None or remaining > 0:
            data = source.read(READ_BYTES if remaining is None else min(READ_BYTES, remaining))
            if not data:
                if remaining is None:
                    break
                raise GateError(f"truncated BLTE N payload: {remaining} bytes missing")
            if remaining is not None:
                remaining -= len(data)
            if encoded_hash is not None:
                encoded_hash.update(data)  # type: ignore[attr-defined]
            sink.write(data)
        return
    if mode != b"Z":
        label = mode.decode("ascii", errors="replace")
        raise GateError(f"unsupported BLTE mode {label!r}; gate supports only N/Z")

    decoder = zlib.decompressobj()
    remaining = payload_length
    while remaining is None or remaining > 0:
        data = source.read(READ_BYTES if remaining is None else min(READ_BYTES, remaining))
        if not data:
            if remaining is None:
                break
            raise GateError(f"truncated BLTE Z payload: {remaining} bytes missing")
        if remaining is not None:
            remaining -= len(data)
        if encoded_hash is not None:
            encoded_hash.update(data)  # type: ignore[attr-defined]
        _decompress_zlib_part(decoder, data, sink)
    try:
        tail = decoder.flush()
    except zlib.error as exc:
        raise GateError(f"BLTE Z finalization failed: {exc}") from exc
    sink.write(tail)
    if not decoder.eof or decoder.unused_data:
        raise GateError("BLTE Z stream is incomplete or has trailing compressed data")


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(READ_BYTES), b""):
            digest.update(block)
    return digest.hexdigest()


def _decode_cached_metadata(
    cache: DiskCache,
    digest: str,
    *,
    expected_content_key: str,
    expected_encoded_size: int,
    role: str,
    output_path: Path,
) -> dict[str, object]:
    source_path = cache._get_cdn_cache_path(digest, "data", CDN_PATH)
    if not source_path.is_file():
        raise GateError(f"missing cached {role} object EKey {digest} at {source_path}")
    actual_encoded_size = source_path.stat().st_size
    if actual_encoded_size != expected_encoded_size:
        raise GateError(
            f"cached {role} encoded size mismatch: {actual_encoded_size} != build metadata {expected_encoded_size}"
        )
    with source_path.open("rb") as source, output_path.open("wb") as output:
        evidence = stream_decode_blte(
            source,
            output,
            expected_ekey=digest,
            encoded_size=actual_encoded_size,
        )
    if evidence["decoded_md5"] != expected_content_key.lower():
        raise GateError(
            f"cached {role} decoded MD5 mismatch: got={evidence['decoded_md5']} expected={expected_content_key.lower()}"
        )
    evidence["encoded_size"] = actual_encoded_size
    evidence["content_key"] = expected_content_key.lower()
    return evidence


def _read_encoding_layout(stream: BinaryIO, file_size: int) -> tuple[dict[str, int], list[str]]:
    header = _read_exact(stream, 22, "encoding header")
    if header[:2] != b"EN":
        raise GateError(f"encoding magic mismatch: {header[:2].hex()}")
    version, ckey_width, ekey_width = header[2], header[3], header[4]
    ckey_page_kb = struct.unpack(">H", header[5:7])[0]
    ekey_page_kb = struct.unpack(">H", header[7:9])[0]
    ckey_page_count = struct.unpack(">I", header[9:13])[0]
    ekey_page_count = struct.unpack(">I", header[13:17])[0]
    unknown = header[17]
    espec_size = struct.unpack(">I", header[18:22])[0]
    if version != 1 or unknown != 0:
        raise GateError(f"unsupported encoding version/flags: version={version} flags={unknown}")
    if (ckey_width, ekey_width) != (16, 16):
        raise GateError(f"encoding key widths unsupported: CKey={ckey_width} EKey={ekey_width}")
    if not ckey_page_kb or not ekey_page_kb or not ckey_page_count or not ekey_page_count or not espec_size:
        raise GateError("encoding header contains a zero page size/count or empty ESpec table")
    ckey_page_bytes = ckey_page_kb * 1024
    ekey_page_bytes = ekey_page_kb * 1024
    if max(ckey_page_bytes, ekey_page_bytes) > MAX_ENCODING_PAGE_BYTES:
        raise GateError(
            f"encoding page exceeds gate memory bound: {max(ckey_page_bytes, ekey_page_bytes)} bytes"
        )
    ckey_index_offset = 22 + espec_size
    ckey_pages_offset = ckey_index_offset + ckey_page_count * (ckey_width + 16)
    ekey_index_offset = ckey_pages_offset + ckey_page_count * ckey_page_bytes
    ekey_pages_offset = ekey_index_offset + ekey_page_count * (ekey_width + 16)
    expected_file_size = ekey_pages_offset + ekey_page_count * ekey_page_bytes
    if file_size < expected_file_size:
        raise GateError(
            f"encoding geometry/count mismatch: header requires at least {expected_file_size} bytes, file has {file_size}"
        )

    stream.seek(22)
    espec_raw = _read_exact(stream, espec_size, "encoding ESpec table")
    if not espec_raw.endswith(b"\x00"):
        raise GateError("encoding ESpec table is not NUL-terminated")
    raw_espects = espec_raw[:-1].split(b"\x00")
    if not raw_espects or any(not value for value in raw_espects):
        raise GateError("encoding ESpec table contains empty strings")
    try:
        espects = [value.decode("ascii") for value in raw_espects]
    except UnicodeDecodeError as exc:
        raise GateError(f"encoding ESpec table is not ASCII: {exc}") from exc
    layout = {
        "ckey_width": ckey_width,
        "ekey_width": ekey_width,
        "ckey_page_bytes": ckey_page_bytes,
        "ekey_page_bytes": ekey_page_bytes,
        "ckey_page_count": ckey_page_count,
        "ekey_page_count": ekey_page_count,
        "ckey_index_offset": ckey_index_offset,
        "ckey_pages_offset": ckey_pages_offset,
        "ekey_index_offset": ekey_index_offset,
        "ekey_pages_offset": ekey_pages_offset,
        "expected_file_size": expected_file_size,
        "trailing_bytes": file_size - expected_file_size,
        "espec_count": len(espects),
    }
    return layout, espects


def parse_encoding_pages(
    stream: BinaryIO,
    file_size: int,
    db: sqlite3.Connection,
    *,
    batch_rows: int = SQL_BATCH_ROWS,
) -> EncodingStats:
    """Page through encoding metadata; no CKey/EKey record list is retained."""
    if batch_rows <= 0:
        raise GateError("encoding spool batch size must be positive")
    layout, espects = _read_encoding_layout(stream, file_size)
    ckey_count = 0
    ekey_count = 0
    mapping_count = 0
    max_page = max(layout["ckey_page_bytes"], layout["ekey_page_bytes"])
    ckey_batch: list[tuple[bytes, bytes, int]] = []
    last_ckey: bytes | None = None
    previous_ckey_index: bytes | None = None

    cindex_size = layout["ckey_width"] + 16
    for page_index in range(layout["ckey_page_count"]):
        stream.seek(layout["ckey_index_offset"] + page_index * cindex_size)
        first_key = _read_exact(stream, 16, f"encoding CKey page {page_index} index key")
        expected_hash = _read_exact(stream, 16, f"encoding CKey page {page_index} checksum")
        if page_index and first_key <= previous_ckey_index:
            raise GateError(f"encoding CKey page index is not strictly sorted at page {page_index}")
        previous_ckey_index = first_key
        stream.seek(layout["ckey_pages_offset"] + page_index * layout["ckey_page_bytes"])
        page = _read_exact(stream, layout["ckey_page_bytes"], f"encoding CKey page {page_index}")
        if hashlib.md5(page).digest() != expected_hash:
            raise GateError(f"encoding CKey page {page_index} checksum mismatch")
        offset = 0
        first_actual: bytes | None = None
        while offset < len(page):
            key_count = page[offset]
            if key_count == 0:
                break
            if offset + 22 > len(page):
                raise GateError(f"truncated CKey page {page_index} entry header")
            decoded_size = int.from_bytes(page[offset + 1 : offset + 6], "big")
            ckey = page[offset + 6 : offset + 22]
            end = offset + 22 + key_count * 16
            if end > len(page):
                raise GateError(f"CKey page {page_index} entry EKey list exceeds page")
            if first_actual is None:
                first_actual = ckey
                if first_actual != first_key:
                    raise GateError(f"encoding CKey page {page_index} first key/index mismatch")
            if last_ckey is not None and ckey <= last_ckey:
                raise GateError(f"encoding CKey records are not strictly sorted at page {page_index}")
            last_ckey = ckey
            for ekey_offset in range(offset + 22, end, 16):
                ekey = page[ekey_offset : ekey_offset + 16]
                ckey_batch.append((ekey, ckey, decoded_size))
                mapping_count += 1
                if len(ckey_batch) >= batch_rows:
                    try:
                        db.executemany(
                            "INSERT INTO encoding_ckeys(ekey,ckey,decoded_size) VALUES(?,?,?)",
                            ckey_batch,
                        )
                    except sqlite3.IntegrityError as exc:
                        raise GateError(f"duplicate encoding CKey/EKey mapping: {exc}") from exc
                    ckey_batch.clear()
            ckey_count += 1
            offset = end
    if ckey_batch:
        try:
            db.executemany(
                "INSERT INTO encoding_ckeys(ekey,ckey,decoded_size) VALUES(?,?,?)",
                ckey_batch,
            )
        except sqlite3.IntegrityError as exc:
            raise GateError(f"duplicate encoding CKey/EKey mapping: {exc}") from exc
    db.commit()

    eindex_size = layout["ekey_width"] + 16
    last_ekey: bytes | None = None
    previous_ekey_index: bytes | None = None
    ekey_batch: list[tuple[bytes, int]] = []
    stride = layout["ekey_width"] + 9
    for page_index in range(layout["ekey_page_count"]):
        stream.seek(layout["ekey_index_offset"] + page_index * eindex_size)
        first_key = _read_exact(stream, 16, f"encoding EKey page {page_index} index key")
        expected_hash = _read_exact(stream, 16, f"encoding EKey page {page_index} checksum")
        if previous_ekey_index is not None and first_key <= previous_ekey_index:
            raise GateError(f"encoding EKey page index is not strictly sorted at page {page_index}")
        previous_ekey_index = first_key
        stream.seek(layout["ekey_pages_offset"] + page_index * layout["ekey_page_bytes"])
        page = _read_exact(stream, layout["ekey_page_bytes"], f"encoding EKey page {page_index}")
        if hashlib.md5(page).digest() != expected_hash:
            raise GateError(f"encoding EKey page {page_index} checksum mismatch")
        offset = 0
        first_actual: bytes | None = None
        while offset + stride <= len(page):
            ekey = page[offset : offset + 16]
            espec_index = struct.unpack(">I", page[offset + 16 : offset + 20])[0]
            if espec_index == 0xFFFFFFFF or (espec_index == 0 and ekey == b"\x00" * 16):
                break
            encoded_size = int.from_bytes(page[offset + 20 : offset + 25], "big")
            if espec_index >= len(espects):
                raise GateError(f"encoding EKey page {page_index} has invalid ESpec index {espec_index}")
            if encoded_size <= 0:
                raise GateError(f"encoding EKey page {page_index} has non-positive object size")
            if first_actual is None:
                first_actual = ekey
                if first_actual != first_key:
                    raise GateError(f"encoding EKey page {page_index} first key/index mismatch")
            if last_ekey is not None and ekey <= last_ekey:
                raise GateError(f"encoding EKey records are not strictly sorted at page {page_index}")
            last_ekey = ekey
            ekey_batch.append((ekey, encoded_size))
            if len(ekey_batch) >= batch_rows:
                try:
                    db.executemany("INSERT INTO encoding_ekeys(ekey,encoded_size) VALUES(?,?)", ekey_batch)
                except sqlite3.IntegrityError as exc:
                    raise GateError(f"duplicate encoding EKey: {exc}") from exc
                ekey_batch.clear()
            ekey_count += 1
            offset += stride
    if ekey_batch:
        try:
            db.executemany("INSERT INTO encoding_ekeys(ekey,encoded_size) VALUES(?,?)", ekey_batch)
        except sqlite3.IntegrityError as exc:
            raise GateError(f"duplicate encoding EKey: {exc}") from exc
    db.commit()
    tail_hash = hashlib.sha256()
    tail_bytes = 0
    while True:
        block = stream.read(READ_BYTES)
        if not block:
            break
        tail_hash.update(block)
        tail_bytes += len(block)
    if tail_bytes != layout["trailing_bytes"]:
        raise GateError(
            f"encoding trailing-byte read mismatch: expected {layout['trailing_bytes']}, got {tail_bytes}"
        )
    return EncodingStats(
        ckey_count=ckey_count,
        ekey_count=ekey_count,
        ckey_mapping_count=mapping_count,
        ckey_page_count=layout["ckey_page_count"],
        ekey_page_count=layout["ekey_page_count"],
        max_page_bytes=max_page,
        espects=len(espects),
        trailing_bytes=tail_bytes,
        trailing_sha256=tail_hash.hexdigest(),
    )


def _readonly_cache(cache_root: Path) -> DiskCache:
    """Construct DiskCache without its constructor's metadata-file write."""
    cache = object.__new__(DiskCache)
    cache.base_dir = cache_root
    cache.cdn_dir = cache_root / "cdn"
    cache.api_dir = cache_root / "api"
    cache.metadata_file = cache_root / "metadata.json"
    cache.metadata = {}
    return cache


def _hash_md5_file(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(READ_BYTES), b""):
            digest.update(block)
    return digest.hexdigest()


def _build_input(cache: DiskCache) -> tuple[bytes, object, object, dict[str, object]]:
    build_path = cache._get_cdn_cache_path(BUILD_CONFIG, "config", CDN_PATH)
    cdn_path = cache._get_cdn_cache_path(CDN_CONFIG, "config", CDN_PATH)
    for label, path in (("build config", build_path), ("CDN config", cdn_path)):
        if not path.is_file():
            raise GateError(f"missing cached pinned {label}: {path}")
    build_raw = build_path.read_bytes()
    cdn_raw = cdn_path.read_bytes()
    if _hash_md5_file(build_path) != BUILD_CONFIG or _hash_md5_file(cdn_path) != CDN_CONFIG:
        raise GateError("cached build/CDN config bytes do not match pinned config hashes")
    build = BuildConfigParser().parse(build_raw)
    cdn = CDNConfigParser().parse(cdn_raw)
    if build.build_uid != PRODUCT:
        raise GateError(f"build product mismatch: {build.build_uid!r}")
    build_name = (build.build_name or "").replace("70009patch1.60.1", VERSION)
    if VERSION not in build_name:
        raise GateError(f"build name does not identify {VERSION}: {build.build_name!r}")
    info_by_role = {
        role: getattr(build, f"get_{role}_info")()
        for role in ("encoding", "install", "download", "size")
    }
    for role, info in info_by_role.items():
        if info is None or not info.content_key or not info.encoding_key or info.size <= 0:
            raise GateError(f"pinned build lacks a complete {role} reference")
    build_info = {
        "raw_size": len(build_raw),
        "raw_sha256": hashlib.sha256(build_raw).hexdigest(),
        "build_name": build.build_name,
        "product": build.build_uid,
        "cdn_raw_size": len(cdn_raw),
        "cdn_raw_sha256": hashlib.sha256(cdn_raw).hexdigest(),
        "cdn_archive_count": len(cdn.archives),
        "cdn_file_index": cdn.file_index,
    }
    return build_raw, build, cdn, {**info_by_role, "_evidence": build_info}


def _read_reused_catalog(path: Path) -> tuple[dict[str, object], str] | None:
    if not path.is_file():
        return None
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != CATALOG_SHA256:
        raise GateError(
            f"reused bootstrap catalog SHA-256 mismatch: got={digest} expected={CATALOG_SHA256}"
        )
    try:
        catalog = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError(f"invalid pinned bootstrap catalog JSON: {exc}") from exc
    provenance = catalog.get("provenance", {})
    expected = {
        "product": PRODUCT,
        "platform": PLATFORM,
        "arch": ARCH,
        "locale": LOCALE,
        "version": VERSION,
        "build_config_hash": BUILD_CONFIG,
        "cdn_config_hash": CDN_CONFIG,
    }
    if catalog.get("schema") != "wowforever.arm64-client-catalog" or any(
        provenance.get(key) != value for key, value in expected.items()
    ):
        raise GateError("pinned bootstrap catalog provenance does not match the update gate")
    if catalog.get("completeness", {}).get("status") != "finite-bootstrap":
        raise GateError("reused bootstrap catalog is not the validated finite-bootstrap generation")
    if not catalog.get("runtime_metadata", {}).get("resolved_and_verified"):
        raise GateError("reused bootstrap catalog does not mark required runtime metadata verified")
    loose = catalog.get("loose_files")
    objects = catalog.get("casc_bootstrap", {}).get("objects")
    if not isinstance(loose, list) or len(loose) != 35 or not isinstance(objects, list):
        raise GateError("pinned bootstrap catalog does not contain the expected 35 loose records")
    runtime = [item for item in objects if _is_runtime_object(item)]
    if len(runtime) != 832:
        raise GateError(f"pinned bootstrap catalog runtime object count is {len(runtime)}, expected 832")
    return catalog, digest


def _is_runtime_object(item: dict[str, object]) -> bool:
    roles = item.get("roles", [])
    return isinstance(roles, list) and any(
        role in {"root", "vfs-root", "encoding", "install", "download", "size"}
        or (isinstance(role, str) and role.startswith("vfs-"))
        for role in roles
    )


def _verify_encoding_binding(
    db: sqlite3.Connection,
    *,
    ckey_hex: str,
    ekey_hex: str,
    encoded_size: int,
    decoded_size: int,
    source: str,
) -> None:
    ekey = bytes.fromhex(ekey_hex)
    ckey = bytes.fromhex(ckey_hex)
    found_size = db.execute("SELECT encoded_size FROM encoding_ekeys WHERE ekey=?", (ekey,)).fetchone()
    if found_size is None:
        raise GateError(f"{source} EKey {ekey_hex} missing from pinned encoding EKey pages")
    if found_size[0] != encoded_size:
        raise GateError(
            f"{source} encoded size disagrees with encoding: {ekey_hex} {encoded_size} != {found_size[0]}"
        )
    found_content = db.execute(
        "SELECT decoded_size FROM encoding_ckeys WHERE ekey=? AND ckey=?", (ekey, ckey)
    ).fetchone()
    if found_content is None:
        raise GateError(f"{source} CKey/EKey mapping absent from encoding: {ckey_hex}/{ekey_hex}")
    if found_content[0] != decoded_size:
        raise GateError(
            f"{source} decoded size disagrees with encoding: {ckey_hex} {decoded_size} != {found_content[0]}"
        )


def _validate_and_write_catalog_provenance(
    db: sqlite3.Connection,
    catalog: dict[str, object],
    catalog_sha: str,
    encoding_reference: object,
    install_manifest_path: Path,
    loose_output: Path,
    runtime_output: Path,
) -> tuple[int, int, int, int]:
    try:
        with install_manifest_path.open("rb") as stream:
            install_manifest = InstallParser().parse(stream)
            if stream.read(1):
                raise GateError("unexpected trailing bytes in install manifest")
    except GateError:
        raise
    except Exception as exc:
        raise GateError(f"could not parse pinned install manifest: {exc}") from exc
    selected = filter_install_entries(install_manifest.entries, install_manifest.tags, PLATFORM, ARCH, LOCALE)
    normalizer = PathNormalizer()
    actual = {
        (normalizer.normalize(entry.filename).replace("\\", "/").casefold(), entry.md5_hash.hex(), entry.size):
        set(entry.tags)
        for entry in selected
    }
    loose = catalog["loose_files"]
    expected: dict[tuple[str, str, int], set[str]] = {}
    for item in loose:
        key = (str(item["relative_path"]).replace("\\", "/").casefold(), str(item["content_key"]), int(item["decoded_size"]))
        expected[key] = set(item.get("manifest_tags", []))
    if len(actual) != 35 or actual != expected:
        raise GateError(
            f"cached install manifest selection disagrees with pinned catalog: actual={len(actual)} catalog={len(expected)}"
        )

    loose_count = 0
    with loose_output.open("w", encoding="utf-8", buffering=READ_BYTES) as output:
        for item in loose:
            ekeys = item.get("encoding_keys")
            if not isinstance(ekeys, list) or len(ekeys) != 1:
                raise GateError(f"loose catalog record must have one full EKey: {item.get('relative_path')}")
            _verify_encoding_binding(
                db,
                ckey_hex=str(item["content_key"]),
                ekey_hex=str(ekeys[0]),
                encoded_size=int(item["encoded_size"]),
                decoded_size=int(item["decoded_size"]),
                source=f"loose file {item['relative_path']}",
            )
            record = {
                "record_type": "loose-file-provenance",
                "relative_path": item["relative_path"],
                "content_key": item["content_key"],
                "decoded_size": item["decoded_size"],
                "encoding_key": ekeys[0],
                "encoded_size": item["encoded_size"],
                "manifest_tags": item.get("manifest_tags", []),
                "manifest_file_type": item.get("manifest_file_type"),
                "catalog_verification": item.get("verification"),
                "source_catalog_sha256": catalog_sha,
            }
            output.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
            loose_count += 1

    runtime_objects = [item for item in catalog["casc_bootstrap"]["objects"] if _is_runtime_object(item)]
    runtime_count = 0
    runtime_cross_checked = 0
    self_reference_count = 0
    with runtime_output.open("w", encoding="utf-8", buffering=READ_BYTES) as output:
        for item in runtime_objects:
            ekeys = item.get("encoding_keys")
            if not isinstance(ekeys, list) or len(ekeys) != 1:
                raise GateError(f"runtime catalog record must have one full EKey: {item.get('content_key')}")
            roles = item.get("roles", [])
            is_encoding_self_reference = (
                isinstance(roles, list)
                and "encoding" in roles
                and str(ekeys[0]) == encoding_reference.encoding_key
            )
            if is_encoding_self_reference:
                if (
                    str(item["content_key"]) != encoding_reference.content_key
                    or int(item["encoded_size"]) != encoding_reference.size
                ):
                    raise GateError("encoding self-reference disagrees with pinned build-config metadata")
                binding_status = "self-reference-verified-by-build-config; absent-from-own-encoding-index"
                self_reference_count += 1
            else:
                _verify_encoding_binding(
                    db,
                    ckey_hex=str(item["content_key"]),
                    ekey_hex=str(ekeys[0]),
                    encoded_size=int(item["encoded_size"]),
                    decoded_size=int(item["decoded_size"]),
                    source=f"required runtime {item['content_key']}",
                )
                binding_status = "cross-checked-against-encoding-pages"
                runtime_cross_checked += 1
            record = {
                "record_type": "required-runtime-provenance",
                "content_key": item["content_key"],
                "decoded_size": item["decoded_size"],
                "encoding_key": ekeys[0],
                "encoded_size": item["encoded_size"],
                "roles": item.get("roles", []),
                "encoding_binding_status": binding_status,
                "catalog_verification": item.get("verification"),
                "decoded_md5_in_catalog": item.get("decoded_md5"),
                "source_catalog_sha256": catalog_sha,
            }
            output.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
            runtime_count += 1
    return loose_count, runtime_count, runtime_cross_checked, self_reference_count


def _emit_download_closure(
    db: sqlite3.Connection,
    tags: list[ManifestTag],
    output_path: Path,
    *,
    query: str = TAG_QUERY,
) -> int:
    rows = db.execute(
        "SELECT d.entry_index,d.ekey,d.encoded_size,d.priority,d.checksum,d.flags,"
        "c.ckey,c.decoded_size "
        "FROM download_entries AS d LEFT JOIN encoding_ckeys AS c ON c.ekey=d.ekey "
        "WHERE d.selected=1 ORDER BY d.ekey,d.entry_index,c.ckey"
    )
    count = 0
    with output_path.open("w", encoding="utf-8", buffering=READ_BYTES) as output:
        for row in rows:
            index, ekey, encoded_size, priority, checksum, flags, ckey, decoded_size = row
            record = {
                "record_type": "download-object-selection",
                "download_manifest_entry_index": index,
                "encoding_key": bytes(ekey).hex(),
                "encoded_size": encoded_size,
                "content_key": bytes(ckey).hex() if ckey is not None else None,
                "decoded_size": decoded_size,
                "priority": priority,
                "checksum": bytes(checksum).hex() if checksum is not None else None,
                "entry_flags": bytes(flags).hex() if flags is not None else None,
                "manifest_tags": _tag_names_at(tags, index),
                "matched_query_tags": _matched_query_tags(tags, index, query),
                "selection_query": query,
            }
            output.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
            count += 1
    return count


def _emit_size_selection(
    db: sqlite3.Connection,
    tags: list[ManifestTag],
    output_path: Path,
    *,
    query: str = TAG_QUERY,
) -> int:
    count = 0
    with output_path.open("w", encoding="utf-8", buffering=READ_BYTES) as output:
        for index, prefix, estimate in db.execute(
            "SELECT entry_index,ekey_prefix,estimated_size FROM size_entries ORDER BY entry_index"
        ):
            record = {
                "record_type": "size-manifest-estimate",
                "size_manifest_entry_index": index,
                "ekey_prefix_9": bytes(prefix).hex(),
                "estimated_size": estimate,
                "manifest_tags": _tag_names_at(tags, index),
                "matched_query_tags": _matched_query_tags(tags, index, query),
                "selection_query": query,
                "identity_note": "partial EKey prefix; not used as object identity",
            }
            output.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
            count += 1
    return count


def _jsonl_evidence(path: Path, records: int) -> dict[str, object]:
    return {"path": path.name, "record_count": records, "size_bytes": path.stat().st_size, "sha256": _file_sha256(path)}


def _prepare_output_dir(output_dir: Path) -> None:
    resolved = output_dir.resolve()
    repo = Path(__file__).resolve().parents[3]
    if resolved == repo or repo in resolved.parents:
        raise GateError("output directory must be outside the repository")
    allowed_root = DEFAULT_OUTPUT.resolve()
    if resolved != allowed_root and allowed_root not in resolved.parents:
        raise GateError(f"output directory must be under {allowed_root}")
    if not output_dir.parent.is_dir():
        raise GateError(f"output parent directory is missing: {output_dir.parent}")
    output_dir.mkdir(exist_ok=True)
    if any(output_dir.iterdir()):
        raise GateError(f"output directory is not empty: {output_dir}")


def run_gate(
    *,
    output_dir: Path = DEFAULT_OUTPUT,
    cache_root: Path | None = None,
    catalog_path: Path = DEFAULT_CATALOG,
) -> dict[str, object]:
    """Run the offline metadata closure gate and write compact external evidence."""
    _prepare_output_dir(output_dir)
    cache_root = cache_root or (Path.home() / ".cache" / "cascette")
    cache = _readonly_cache(cache_root)
    _build_raw, build, cdn, references = _build_input(cache)
    evidence = references.pop("_evidence")
    metadata_dir = output_dir

    try:
        with tempfile.TemporaryDirectory(prefix="manifest-gate-", dir=metadata_dir) as tmp_name:
            work = Path(tmp_name)
            db_path = work / "spool.sqlite3"
            db = sqlite3.connect(db_path)
            db.execute(f"PRAGMA cache_size=-{SQLITE_CACHE_BYTES // 1024}")
            db.execute("PRAGMA temp_store=FILE")
            db.execute("PRAGMA journal_mode=OFF")
            db.execute("PRAGMA synchronous=OFF")
            _create_spool_tables(db)
            decoded: dict[str, dict[str, object]] = {}
            decoded_paths: dict[str, Path] = {}
            for role in ("encoding", "install", "download", "size"):
                info = references[role]
                decoded_path = work / f"{role}.decoded"
                decoded[role] = _decode_cached_metadata(
                    cache,
                    info.encoding_key,
                    expected_content_key=info.content_key,
                    expected_encoded_size=info.size,
                    role=role,
                    output_path=decoded_path,
                )
                decoded_paths[role] = decoded_path

            with decoded_paths["download"].open("rb") as stream:
                download_stats, download_tags, download_batch = parse_download_manifest(stream, db)
            with decoded_paths["size"].open("rb") as stream:
                size_stats, size_tags, size_batch = parse_size_manifest(stream, db)
            with decoded_paths["encoding"].open("rb") as stream:
                encoding_stats = parse_encoding_pages(stream, decoded["encoding"]["decoded_size"], db)

            duplicate_sizes = db.execute(
                "SELECT COUNT(*) FROM (SELECT ekey FROM download_entries WHERE selected=1 "
                "GROUP BY ekey HAVING MIN(encoded_size) != MAX(encoded_size))"
            ).fetchone()[0]
            if duplicate_sizes:
                raise GateError(f"selected download EKeys have conflicting encoded sizes: {duplicate_sizes}")
            missing_ekeys, size_mismatches = db.execute(
                "SELECT "
                "SUM(CASE WHEN m.ekey IS NULL THEN 1 ELSE 0 END), "
                "SUM(CASE WHEN m.ekey IS NOT NULL AND m.encoded_size != d.encoded_size THEN 1 ELSE 0 END) "
                "FROM (SELECT ekey,MIN(encoded_size) AS encoded_size FROM download_entries "
                "WHERE selected=1 GROUP BY ekey) AS d LEFT JOIN encoding_ekeys AS m ON m.ekey=d.ekey"
            ).fetchone()
            if missing_ekeys:
                raise GateError(f"{missing_ekeys} selected download EKeys are absent from encoding metadata")
            if size_mismatches:
                raise GateError(f"{size_mismatches} selected download EKeys disagree with encoding sizes")
            unmapped_ekeys = db.execute(
                "SELECT COUNT(*) FROM (SELECT DISTINCT d.ekey FROM download_entries d "
                "LEFT JOIN encoding_ckeys c ON c.ekey=d.ekey WHERE d.selected=1 AND c.ekey IS NULL)"
            ).fetchone()[0]

            catalog_result = _read_reused_catalog(catalog_path)
            if catalog_result is None:
                raise GateError(f"validated bootstrap catalog missing: {catalog_path}")
            catalog, catalog_sha = catalog_result
            loose_path = work / "loose-file-provenance.jsonl"
            runtime_path = work / "required-runtime-provenance.jsonl"
            loose_count, runtime_count, runtime_cross_checked, self_reference_count = _validate_and_write_catalog_provenance(
                db,
                catalog,
                catalog_sha,
                references["encoding"],
                decoded_paths["install"],
                loose_path,
                runtime_path,
            )
            download_path = work / "download-object-closure.jsonl"
            size_path = work / "size-selected-estimates.jsonl"
            download_records = _emit_download_closure(db, download_tags, download_path)
            size_records = _emit_size_selection(db, size_tags, size_path)

            output_files = [
                _jsonl_evidence(download_path, download_records),
                _jsonl_evidence(size_path, size_records),
                _jsonl_evidence(loose_path, loose_count),
                _jsonl_evidence(runtime_path, runtime_count),
            ]
            # Promote only completed, parser-validated reports into the artifact root.
            for item in output_files:
                (work / item["path"]).replace(output_dir / item["path"])

            summary: dict[str, object] = {
                "schema": "wowforever.arm64-update-manifest-gate",
                "schema_version": 1,
                "status": "metadata-closure-proven",
                "provenance": {
                    "product": PRODUCT,
                    "region": REGION,
                    "platform": PLATFORM,
                    "architecture": ARCH,
                    "locale": LOCALE,
                    "version": VERSION,
                    "build_config_hash": BUILD_CONFIG,
                    "cdn_config_hash": CDN_CONFIG,
                    "cdn_path": CDN_PATH,
                    "build_name": evidence["build_name"],
                    "cache_root": str(cache_root),
                    "cache_access": "DiskCache path resolver + read-only file streams; constructor bypassed to avoid cache metadata writes",
                },
                "metadata_objects": {
                    role: {
                        "content_key": references[role].content_key,
                        "encoding_key": references[role].encoding_key,
                        "declared_encoded_size": references[role].size,
                        **decoded[role],
                    }
                    for role in ("encoding", "install", "download", "size")
                },
                "blte_support_limits": {
                    "supported_modes": ["N", "Z"],
                    "max_chunk_count": MAX_BLTE_CHUNKS,
                    "max_header_bytes": MAX_BLTE_HEADER_BYTES,
                    "max_encoded_chunk_bytes_including_mode": MAX_BLTE_CHUNK_BYTES + 1,
                    "max_decoded_chunk_bytes": MAX_BLTE_CHUNK_BYTES,
                    "scope": "bounded host metadata gate only; this is not a universal payload/updater BLTE support claim",
                },
                "cached_config_evidence": evidence,
                "selection": {
                    "query": TAG_QUERY,
                    "semantics": "OR within tag_type groups, AND between groups, then subtractive tags; same query implementation as filter_install_entries/apply_tag_query",
                    "download_tag_types": {tag.name: tag.tag_type for tag in download_tags},
                    "size_tag_types": {tag.name: tag.tag_type for tag in size_tags},
                    "size_partial_ekey_width_bytes": 9,
                    "size_partial_keys_are_not_used_as_object_identity": True,
                },
                "download_manifest": {
                    "entry_count": download_stats.entry_count,
                    "selected_entry_count": download_stats.selected_count,
                    "selected_unique_full_ekey_count": download_stats.selected_ekey_count,
                    "selected_encoded_bytes": download_stats.selected_bytes,
                    "all_entry_encoded_bytes": download_stats.total_bytes,
                    "tag_count": download_stats.tag_count,
                    "tag_bitmap_and_selection_bytes": download_stats.tag_bitmap_bytes,
                    "encoded_sizes_match_encoding_pages": True,
                    "selected_ekeys_missing_from_encoding": missing_ekeys,
                    "selected_ekey_size_mismatches": size_mismatches,
                    "selected_ekeys_without_content_mapping": unmapped_ekeys,
                    "closure_record_count": download_records,
                },
                "size_manifest": {
                    "entry_count": size_stats.entry_count,
                    "selected_entry_count": size_stats.selected_count,
                    "selected_estimated_bytes": size_stats.selected_bytes,
                    "declared_all_entry_bytes": size_stats.total_bytes,
                    "tag_count": size_stats.tag_count,
                    "tag_bitmap_and_selection_bytes": size_stats.tag_bitmap_bytes,
                    "note": "independently selected by tag names/types; no ordinal alignment or 9-byte prefix identity is assumed",
                    "selected_record_count": size_records,
                },
                "encoding": {
                    "ckey_record_count": encoding_stats.ckey_count,
                    "ekey_record_count": encoding_stats.ekey_count,
                    "ckey_ekey_mapping_count": encoding_stats.ckey_mapping_count,
                    "ckey_page_count": encoding_stats.ckey_page_count,
                    "ekey_page_count": encoding_stats.ekey_page_count,
                    "page_checksum_validation": "MD5 for every CKey/EKey page",
                    "max_page_bytes": encoding_stats.max_page_bytes,
                    "espec_count": encoding_stats.espects,
                    "trailing_bytes_after_declared_pages": encoding_stats.trailing_bytes,
                    "trailing_bytes_sha256": encoding_stats.trailing_sha256,
                    "trailing_bytes_semantics": "not interpreted; declared CKey/EKey indexes and pages are parsed and checksummed",
                },
                "reused_bootstrap_provenance": {
                    "catalog_path": str(catalog_path),
                    "catalog_sha256": catalog_sha,
                    "catalog_generation": "existing validated finite-bootstrap catalog; read-only and not regenerated",
                    "loose_file_count": loose_count,
                    "required_runtime_object_count": runtime_count,
                    "loose_files_cross_checked_against_encoding_pages": True,
                    "runtime_objects_cross_checked_against_encoding_pages": runtime_cross_checked,
                    "encoding_self_reference_count": self_reference_count,
                    "prior_object_content_verification_reperformed": False,
                },
                "verification_boundary": {
                    "metadata_closure_complete": True,
                    "download_payloads_downloaded": False,
                    "download_payload_contents_verified_by_this_gate": False,
                    "unverified_download_object_content_count": download_stats.selected_ekey_count,
                    "cached_payload_contents_inferred_from_metadata": False,
                    "client_tree_read_or_written": False,
                    "network_or_credentials_used": False,
                    "decoded_encodings_unverified": [
                        "All selected download-object payloads: not acquired or decoded; EKey/CKey/size fields are metadata bindings only."
                    ],
                    "metadata_manifest_blte_modes": {
                        role: {"flags": decoded[role]["blte_flags"], "modes": decoded[role]["blte_modes"]}
                        for role in ("encoding", "install", "download", "size")
                    },
                    "limitations": [
                        "Download-object content bytes were not acquired or decoded; EKey, encoded size, CKey and decoded size are metadata bindings only.",
                        "The existing bootstrap catalog's prior 832-object verification is retained as provenance, not repeated by this gate.",
                        "Size-manifest entries use 9-byte EKey prefixes and are reported as estimates, never as full object identities.",
                    ],
                },
                "memory_shape": {
                    "download_or_size_entry_lists_retained": False,
                    "encoding_record_lists_retained": False,
                    "work_queue_depth": 0,
                    "future_count": 0,
                    "sqlite_cache_limit_bytes": SQLITE_CACHE_BYTES,
                    "max_sql_batch_rows": max(SQL_BATCH_ROWS, download_batch, size_batch),
                    "maximum_io_buffer_bytes": READ_BYTES,
                    "max_encoding_page_bytes": encoding_stats.max_page_bytes,
                    "max_blte_chunk_descriptors": MAX_BLTE_CHUNKS,
                    "max_blte_descriptor_table_bytes": MAX_BLTE_HEADER_BYTES,
                    "tag_masks_retained_concurrently_bytes": download_stats.tag_mask_bytes + size_stats.tag_mask_bytes,
                    "tag_bitmap_storage_bytes_peak": peak_tag_bitmap_storage_bytes(download_stats, size_stats),
                    "selected_records_spooled_to_sqlite": True,
                    "spool_database_deleted_after_report": True,
                },
                "outputs": output_files,
            }
            db.close()
            summary_path = output_dir / "summary.json"
            summary_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            return summary
    except Exception:
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    try:
        summary = run_gate(output_dir=args.output_dir)
    except GateError as exc:
        print(json.dumps({"status": "blocked", "blocker": str(exc)}, sort_keys=True))
        return 2
    print(
        json.dumps(
            {
                "status": summary["status"],
                "summary": str(args.output_dir / "summary.json"),
                "download_manifest": summary["download_manifest"],
                "size_manifest": summary["size_manifest"],
                "encoding": summary["encoding"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
