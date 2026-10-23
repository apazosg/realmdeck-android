"""Offline bounds and static-qualification tests for the vtest server audit."""

from __future__ import annotations

import hashlib
import io
import json
import lzma
import struct
import sys
import tarfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts/arm64-prototype/controls/virgl"))
import audit_payloads as payloads  # noqa: E402
import audit_server as server  # noqa: E402


def _elf(*, machine: int = 183, name: str = "virgl_test_server", needed: list[str] | None = None) -> bytes:
    needed = needed or ["libvirglrenderer.so.1", "libc.so"]
    strings = b"\0" + b"\0".join(item.encode() for item in [*needed, name]) + b"\0"
    offsets: dict[str, int] = {}
    cursor = 1
    for value in [*needed, name]:
        offsets[value] = cursor
        cursor += len(value) + 1
    phoff, dynamic_off, string_off = 64, 0x100, 0x180
    dynamic = [(5, 0x400000 + string_off), (10, len(strings)), (14, offsets[name]), *[(1, offsets[item]) for item in needed], (0, 0)]
    raw = bytearray(0x180 + len(strings))
    raw[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHIQQQIHHHHHH", raw, 16, 3, machine, 1, 0, phoff, 0, 0, 64, 56, 2, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", raw, phoff, 1, 5, 0, 0x400000, 0, len(raw), len(raw), 0x1000)
    struct.pack_into("<IIQQQQQQ", raw, phoff + 56, 2, 4, dynamic_off, 0x400000 + dynamic_off, 0, len(dynamic) * 16, len(dynamic) * 16, 8)
    for index, pair in enumerate(dynamic):
        struct.pack_into("<qQ", raw, dynamic_off + index * 16, *pair)
    raw[string_off : string_off + len(strings)] = strings
    return bytes(raw)


def _deb(tmp_path: Path, payload: bytes, name: str) -> Path:
    def member(member_name: str, value: bytes) -> bytes:
        header = f"{member_name:<16}{0:<12}{0:<6}{0:<6}{0:<8}{len(value):<10}`\n".encode("ascii")
        return header + value + (b"\n" if len(value) & 1 else b"")

    path = tmp_path / name
    path.write_bytes(b"!<arch>\n" + member("debian-binary", b"2.0\n") + member("data.tar.xz", payload))
    return path


def _package(tmp_path: Path, name: str, entries: list[tuple[str, bytes]]) -> Path:
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        for path, value in entries:
            item = tarfile.TarInfo(path)
            item.size = len(value)
            archive.addfile(item, io.BytesIO(value))
    return _deb(tmp_path, lzma.compress(stream.getvalue()), name)


def _pin(path: Path) -> payloads.PackagePin:
    raw = path.read_bytes()
    return payloads.PackagePin(len(raw), hashlib.sha256(raw).hexdigest())


def _fixture_record(path: Path) -> server.ServerPackage:
    raw = path.read_bytes()
    return server.ServerPackage("virglrenderer", "1.3.0", "aarch64", server.PACKAGES[0].filename, len(raw), hashlib.sha256(raw).hexdigest())


def _manifest(path: Path) -> None:
    path.write_text(
        json.dumps(
            {
                "components": [{"libraries": [{"packagePath": "lib/libextra.so", "soname": "libextra.so", "needed": ["libc.so"]}]}],
                "approvedSymlinkAliases": [{"path": "lib/libextra-alias.so", "resolvedPath": "lib/libextra.so"}],
                "ready": False,
            }
        ),
        encoding="utf-8",
    )


def test_fixed_source_is_exact_and_below_one_mib() -> None:
    server._validate_fixed_source()
    assert sum(item.bytes for item in server.PACKAGES) == 709124
    assert all(len(item.sha256) == 64 for item in server.PACKAGES)
    assert server.PACKAGES[0].url == server.ORIGIN + "pool/main/v/virglrenderer/virglrenderer_1.3.0_aarch64.deb"
    assert server.PACKAGES[1].url == server.ORIGIN + "pool/main/libe/libepoxy/libepoxy_1.5.10-3_aarch64.deb"


def test_published_metadata_must_match_before_acquisition(tmp_path: Path) -> None:
    path = tmp_path / "metadata.json"
    path.write_text(
        json.dumps(
            {
                "repository": server.ORIGIN,
                "packages": {
                    item.package: {
                        "Package": item.package,
                        "Version": item.version,
                        "Architecture": item.architecture,
                        "Filename": item.filename,
                        "Size": item.bytes,
                        "SHA256": item.sha256,
                    }
                    for item in server.PACKAGES
                },
            }
        ),
        encoding="utf-8",
    )
    assert server._verify_published_metadata(path)["packagesVerified"] == ["virglrenderer", "libepoxy"]
    body = json.loads(path.read_text(encoding="utf-8"))
    body["packages"]["libepoxy"]["SHA256"] = "0" * 64
    path.write_text(json.dumps(body), encoding="utf-8")
    with pytest.raises(server.ServerAuditError, match="pin mismatch"):
        server._verify_published_metadata(path)


def test_wrong_architecture_is_rejected(tmp_path: Path) -> None:
    path = _package(tmp_path, "virglrenderer_1.3.0_aarch64.deb", [("usr/bin/virgl_test_server", _elf(machine=62))])
    with pytest.raises(server.ServerAuditError, match="AArch64"):
        server._parse_package(path, _fixture_record(path))


def test_decoded_and_member_bounds_are_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    path = _package(tmp_path, "virglrenderer_1.3.0_aarch64.deb", [("usr/share/blob", b"x" * 4096)])
    monkeypatch.setattr(server, "MAX_DECODED_PACKAGE_BYTES", 128)
    with pytest.raises(server.ServerAuditError, match="(expanded|bound)"):
        server._parse_package(path, _fixture_record(path))


def test_server_report_is_static_and_not_ready(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    virgl = _package(
        tmp_path,
        server.PACKAGES[0].basename,
        [("usr/bin/virgl_test_server", _elf()), ("usr/lib/libvirglrenderer.so", _elf(name="libvirglrenderer.so", needed=["libepoxy.so", "libc.so"]))],
    )
    epoxy = _package(tmp_path, server.PACKAGES[1].basename, [("usr/lib/libepoxy.so.0", _elf(name="libepoxy.so.0", needed=["libdl.so", "libc.so"]))])
    # The production pins are deliberately immutable; substitute fixture pins
    # only at the API seam so this test remains offline.
    monkeypatch.setattr(server, "PACKAGES", (server.ServerPackage("virglrenderer", "1.3.0", "aarch64", server.PACKAGES[0].filename, virgl.stat().st_size, hashlib.sha256(virgl.read_bytes()).hexdigest()), server.ServerPackage("libepoxy", "1.5.10-3", "aarch64", server.PACKAGES[1].filename, epoxy.stat().st_size, hashlib.sha256(epoxy.read_bytes()).hexdigest())))
    manifest = tmp_path / "candidate-manifest.json"
    _manifest(manifest)
    report = server.audit_server([virgl, epoxy], manifest)
    assert report["server"]["expectedName"] == "virgl_test_server"
    assert report["server"]["actualPackagePath"] == "usr/bin/virgl_test_server"
    assert report["server"]["needed"] == ["libvirglrenderer.so.1", "libc.so"]
    assert report["privateDependencyPlan"]["libepoxy"]["status"] == "PRESENT"
    assert report["privateLoaderResolution"]["status"] == "UNVERIFIED"
    assert report["protocol"]["status"] == "UNVERIFIED"
    assert report["present"] is False and report["d3d"] is False and report["wow"] is False
    assert report["executionPerformed"] is False


def _closure_library(package_path: str, soname: str, needed: list[str]) -> dict[str, object]:
    return {"packagePath": package_path, "soname": soname, "needed": needed}


def _materialization_item(destination: str, value: bytes = b"fixture") -> server.MaterializationItem:
    digest = hashlib.sha256(value).hexdigest()
    return server.MaterializationItem(destination, value, {"bytes": len(value), "sha256": digest, "elfType": "ET_DYN"}, 0o600)


def test_new_cached_mesa_pin_is_checked_before_parse(tmp_path: Path) -> None:
    path = tmp_path / server.MESA_PACKAGE.basename
    path.write_bytes(b"not-the-pinned-mesa-archive")
    with pytest.raises(payloads.AuditError, match="(size|SHA-256)"):
        server._audit_cached_mesa(path)


def test_new_missing_gbm_is_reused_from_cached_mesa_provider() -> None:
    server_entry = {"packagePath": "usr/bin/virgl_test_server", "needed": ["libvirglrenderer.so", "libc.so"]}
    components = [
        {"package": "virglrenderer", "libraries": [_closure_library("usr/lib/libvirglrenderer.so", "libvirglrenderer.so", ["libepoxy.so", "libgbm.so", "libdrm.so"])]},
        {"package": "libepoxy", "libraries": [_closure_library("usr/lib/libepoxy.so", "libepoxy.so", ["libdl.so", "libc.so"])]},
        {"package": "mesa", "_provider": "cached_audited_mesa", "libraries": [_closure_library(server.MESA_GBM_PATH, "libgbm.so", ["libdrm.so", "libdl.so", "libc.so"])]},
    ]
    manifest = {"regularLibraries": [_closure_library("usr/lib/libdrm.so", "libdrm.so", ["libc.so"])], "aliases": []}
    closure = server._closure(components, server_entry, manifest)
    assert closure["missingSonames"] == []
    gbm = next(item for item in closure["resolved"] if item["needed"] == "libgbm.so")
    assert gbm["provider"] == "cached_audited_mesa"


def test_new_wrong_gbm_soname_is_an_abi_gap() -> None:
    with pytest.raises(server.ServerAuditError, match="ABI gap"):
        server._require_gbm_record({"libraries": [{"packagePath": server.MESA_GBM_PATH, "soname": "libgbm.so.99"}]})


def test_new_materialization_source_alias_collision_rejected() -> None:
    first = _materialization_item("lib/libsame.so", b"first")
    second = _materialization_item("lib/libsame.so", b"second")
    with pytest.raises(server.ServerAuditError, match="identity collision"):
        server._validate_materialization_items([first, second])


def test_new_unknown_candidate_output_is_not_replaced(tmp_path: Path) -> None:
    output = tmp_path / "server_candidate"
    output.mkdir()
    with pytest.raises(server.ServerAuditError, match="new"):
        server._ensure_new_output(output)


def test_new_materialization_bounds_are_enforced(monkeypatch: pytest.MonkeyPatch) -> None:
    item = _materialization_item("lib/libone.so", b"123")
    monkeypatch.setattr(server, "MAX_MATERIALIZED_FILE_BYTES", 2)
    with pytest.raises(server.ServerAuditError, match="192 MiB"):
        server._validate_materialization_items([item])
    monkeypatch.setattr(server, "MAX_MATERIALIZED_FILE_BYTES", 192 * 1024 * 1024)
    monkeypatch.setattr(server, "MAX_MATERIALIZED_AGGREGATE_BYTES", 5)
    with pytest.raises(server.ServerAuditError, match="aggregate"):
        server._validate_materialization_items([item, _materialization_item("lib/libtwo.so", b"456")])
