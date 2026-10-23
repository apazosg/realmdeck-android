"""Offline regression tests for the bounded virgl payload audit."""

from __future__ import annotations

import io
import lzma
import struct
import sys
import tarfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts/arm64-prototype/controls/virgl"))
import audit_payloads as audit  # noqa: E402


def _deb(tmp_path: Path, data: bytes, *, name: str = "fixture.deb") -> Path:
    def member(member_name: str, payload: bytes) -> bytes:
        header = f"{member_name:<16}{0:<12}{0:<6}{0:<6}{0:<8}{len(payload):<10}`\n".encode("ascii")
        return header + payload + (b"\n" if len(payload) & 1 else b"")

    raw = b"!<arch>\n" + member("debian-binary", b"2.0\n") + member("data.tar.xz", data)
    path = tmp_path / name
    path.write_bytes(raw)
    return path


def _tar(tmp_path: Path, entries: list[tuple[str, bytes | None, str]]) -> bytes:
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        for name, payload, kind in entries:
            item = tarfile.TarInfo(name)
            if kind == "file":
                assert payload is not None
                item.size = len(payload)
                archive.addfile(item, io.BytesIO(payload))
            elif kind == "symlink":
                assert payload is not None
                item.type = tarfile.SYMTYPE
                item.linkname = payload.decode()
                archive.addfile(item)
            else:
                item.type = tarfile.DIRTYPE
                archive.addfile(item)
    return lzma.compress(stream.getvalue())


def _pin(path: Path) -> audit.PackagePin:
    raw = path.read_bytes()
    return audit.PackagePin(len(raw), __import__("hashlib").sha256(raw).hexdigest())


def _elf(*, machine: int = 183, etype: int = 3, soname: str = "libGL.so.1", needed: list[str] | None = None) -> bytes:
    """Small valid ET_DYN fixture with PT_LOAD and PT_DYNAMIC string data."""
    needed = needed or ["libc.so"]
    names = [soname, *needed]
    strings = b"\0" + b"\0".join(item.encode() for item in names) + b"\0"
    offsets = {}
    cursor = 1
    for item in names:
        offsets[item] = cursor
        cursor += len(item) + 1
    phoff, dynamic_off, string_off = 64, 0x100, 0x180
    dynamic = [(5, 0x400000 + string_off), (10, len(strings)), (14, offsets[soname]), *[(1, offsets[item]) for item in needed], (0, 0)]
    raw = bytearray(0x180 + len(strings))
    raw[:4] = b"\x7fELF"
    raw[4:7] = b"\x02\x01\x01"
    struct.pack_into("<HHIQQQIHHHHHH", raw, 16, etype, machine, 1, 0, phoff, 0, 0, 64, 56, 2, 0, 0, 0)
    # PT_LOAD maps the whole fixture; PT_DYNAMIC points at the dynamic table.
    struct.pack_into("<IIQQQQQQ", raw, phoff, 1, 5, 0, 0x400000, 0, len(raw), len(raw), 0x1000)
    struct.pack_into("<IIQQQQQQ", raw, phoff + 56, 2, 4, dynamic_off, 0x400000 + dynamic_off, 0, len(dynamic) * 16, len(dynamic) * 16, 8)
    for index, pair in enumerate(dynamic):
        struct.pack_into("<qQ", raw, dynamic_off + index * 16, *pair)
    raw[string_off : string_off + len(strings)] = strings
    return bytes(raw)


def test_negative_ar_rejected() -> None:
    with pytest.raises(audit.AuditError, match="ar"):
        audit.parse_ar(b"!<arch>\n" + b"x" * 60)


@pytest.mark.parametrize("target", ["/etc/passwd", "../../../outside"])
def test_traversal_and_absolute_symlinks_rejected(tmp_path: Path, target: str) -> None:
    with pytest.raises(audit.AuditError, match="(absolute|escaping|unsafe)"):
        audit.parse_data_tar(_tar(tmp_path, [("usr/lib/x", target.encode(), "symlink")],), "fixture.deb")


def test_broken_symlink_rejected(tmp_path: Path) -> None:
    with pytest.raises(audit.AuditError, match="broken"):
        audit.parse_data_tar(_tar(tmp_path, [("usr/lib/x", b"missing", "symlink")],), "fixture.deb")


def test_duplicate_normalized_paths_rejected(tmp_path: Path) -> None:
    data = _tar(tmp_path, [("./usr/lib/x", b"one", "file"), ("usr/lib/x", b"two", "file")],)
    with pytest.raises(audit.AuditError, match="duplicate"):
        audit.parse_data_tar(data, "fixture.deb")


def test_wrong_elf_architecture_rejected(tmp_path: Path) -> None:
    with pytest.raises(audit.AuditError, match="AArch64"):
        audit.parse_data_tar(_tar(tmp_path, [("usr/lib/libbad.so", _elf(machine=62), "file")],), "fixture.deb")


def test_expanded_bound_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(audit, "MAX_DATA_EXPANDED_BYTES", 128)
    with pytest.raises(audit.AuditError, match="expanded"):
        audit.parse_data_tar(_tar(tmp_path, [("usr/share/blob", b"x" * 4096, "file")],), "fixture.deb")


def test_conflicting_sonames_rejected(tmp_path: Path) -> None:
    data = _tar(tmp_path, [("usr/lib/a.so", _elf(soname="libsame.so"), "file"), ("usr/lib/b.so", _elf(soname="libsame.so"), "file")],)
    with pytest.raises(audit.AuditError, match="(SONAME|owned)"):
        path = _deb(tmp_path, data)
        audit.build_closure([audit.audit_package(path, _pin(path))])


def test_closure_does_not_resolve_unknown_imagefs_names(tmp_path: Path) -> None:
    data = _tar(tmp_path, [("usr/lib/libGL.so.1", _elf(needed=["libc.so", "libimagefs.so.1"]), "file")],)
    path = _deb(tmp_path, data)
    component = audit.audit_package(path, _pin(path))
    closure = audit.build_closure([component])
    assert "libimagefs.so.1" in closure["missingSonames"]
    assert closure["ready"] is False
    assert closure["protocol"]["status"] == "UNVERIFIED"
