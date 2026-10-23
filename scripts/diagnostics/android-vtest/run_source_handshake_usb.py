#!/usr/bin/env python3
"""Run the fixed source-built diagnostic vtest handshake.

This is a host-side qualification wrapper around the already reviewed
handshake runner.  It selects one pinned official-source candidate and adds
only the private SONAME alias required by that candidate's Android layout.
It has no build, download, install, or arbitrary device-command interface.
"""
from __future__ import annotations

import hashlib
import argparse
import importlib.util
import json
import re
import stat
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Any


_SHARED_PATH = Path(__file__).with_name("run_handshake_usb.py")
_SHARED_SPEC = importlib.util.spec_from_file_location("vtest_handshake_shared", _SHARED_PATH)
if _SHARED_SPEC is None or _SHARED_SPEC.loader is None:  # pragma: no cover - packaging error
    raise ImportError(f"cannot load {_SHARED_PATH}")
shared = importlib.util.module_from_spec(_SHARED_SPEC)
_SHARED_SPEC.loader.exec_module(shared)


SOURCE_MANIFEST = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
    "vtest-android-build-v6-final/candidate_diagnostic_verified/candidate-manifest.json"
)
SOURCE_MANIFEST_SHA256 = "3947b472b1aeeba6a9e16e882c2a503e9b05d831006da3ab60c47927bd21e031"
SOURCE_MANIFEST_LIMIT = 1024 * 1024
SOURCE_CANDIDATE_KIND = "official_source_diagnostic"
SOURCE_ARCHIVE = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
    "virgl-source-audit-1.3.0/official-source.tar.gz"
)
SOURCE_ARCHIVE_BYTES = 1_255_383
SOURCE_ARCHIVE_SHA256 = "56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce"
SOURCE_ROOT = "virglrenderer-virglrenderer-1.3.0"
OLD_MANIFEST_SHA256 = "5f40682f034050d5e72d98199b41a5da9444aa2770ee5732bb4a077de7e17174"

SOURCE_SERVER_SHA256 = "ce8a4f61c2042d7525ea73daf047d5b1847542fc7f61a7256830c44b3578f533"
SOURCE_LIBRARY_SHA256 = "c9d78c69c135c88242c781e38bfa651670bfc4d0cac95c56bf1409ab704b0107"
SOURCE_SERVER_BYTES = 152_672
SOURCE_LIBRARY_BYTES = 655_320

# These eight values are independently pinned to the old candidate manifest;
# the source candidate is not allowed to become its own authority for them.
OLD_PROVIDER_PINS: dict[str, tuple[int, str]] = {
    "lib/libX11.so": (1_187_752, "5b507549e15e6cc4567938510a391abbeb135118ed8fdda6f30fdb8eba156b4b"),
    "lib/libXau.so": (9_904, "e820c412bac544d28d4288f50f7b6dee046e2f32cc7cfc82e203c642fbaa42bf"),
    "lib/libXdmcp.so": (18_920, "fca2b4d37620a90b9e97ff5ef24b4b7f438fcce6a837db152705348c95379ef8"),
    "lib/libandroid-support.so": (20_736, "739cf829511d71dafd6c67fdbb70f3f0c6048642ea2e1967790ee961fde14430"),
    "lib/libdrm.so": (76_016, "0e4e8f0bbc6809589206a757759a6506e0aa7c88e0b55de2fe8d54f7ef6a3323"),
    "lib/libepoxy.so": (1_056_544, "465c17fea235d0f3007a2ab4b2036d0cb4e1af85faacddf989c5f51887cf8a8f"),
    "lib/libgbm.so": (13_824, "5a24cc0ce8878f05b97f4270e83962d48a09d7c318c3669748f1de2a9f0d2849"),
    "lib/libxcb.so": (136_584, "13d59f33b51765e4d1444cad3f7f949ccf60a9bd34e53bdbe10cfcd787fda14c"),
}
SOURCE_PAYLOAD_PINS = {
    **OLD_PROVIDER_PINS,
    "bin/virgl_test_server": (SOURCE_SERVER_BYTES, SOURCE_SERVER_SHA256),
    "lib/libvirglrenderer.so": (SOURCE_LIBRARY_BYTES, SOURCE_LIBRARY_SHA256),
}
SOURCE_PAYLOAD_DESTINATIONS = set(SOURCE_PAYLOAD_PINS)

SOURCE_ELF_PINS: dict[str, dict[str, Any]] = {
    "bin/virgl_test_server": {
        "bytes": SOURCE_SERVER_BYTES,
        "ptInterp": "/system/bin/linker64",
        "soname": None,
        "needed": ["libvirglrenderer.so.1", "libc.so"],
        "rpath": [],
        "runpath": ["$ORIGIN/../lib"],
    },
    "lib/libvirglrenderer.so": {
        "bytes": SOURCE_LIBRARY_BYTES,
        "ptInterp": None,
        "soname": "libvirglrenderer.so.1",
        "needed": ["libm.so", "liblog.so", "libepoxy.so", "libdrm.so", "libgbm.so", "libc.so"],
        "rpath": [],
        "runpath": ["$ORIGIN"],
    },
}


@dataclass(frozen=True)
class SourceCandidate:
    manifest: Path
    manifest_sha256: str
    profile: str
    functional_fix: bool
    compatibility_defines: tuple[str, ...]
    kind: str
    server_bytes: int
    server_sha256: str
    library_bytes: int
    library_sha256: str

    @property
    def payload_pins(self) -> dict[str, tuple[int, str]]:
        return {
            **OLD_PROVIDER_PINS,
            "bin/virgl_test_server": (self.server_bytes, self.server_sha256),
            "lib/libvirglrenderer.so": (self.library_bytes, self.library_sha256),
        }

    @property
    def elf_pins(self) -> dict[str, dict[str, Any]]:
        return {
            name: {**pin, "bytes": self.payload_pins[name][0]}
            for name, pin in SOURCE_ELF_PINS.items()
        }


DIAGNOSTIC = SourceCandidate(
    SOURCE_MANIFEST, SOURCE_MANIFEST_SHA256, "diagnostic", False,
    ("WFVTEST_DIAG=1",), SOURCE_CANDIDATE_KIND,
    SOURCE_SERVER_BYTES, SOURCE_SERVER_SHA256, SOURCE_LIBRARY_BYTES, SOURCE_LIBRARY_SHA256,
)
ANDROID_DEFAULT = SourceCandidate(
    Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
         "vtest-android-default-v2/candidate_android_default_v2_verified/candidate-manifest.json"),
    "b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28",
    "android-default", True, ("WFVTEST_ANDROID_DEFAULT=1",),
    "official_source_android_default",
    152_672, "4aad98a173414b625d6af7e5181fe202c450e36d29cc632fb9bab49d11310261",
    654_752, "468adc11695bab0173e0f2b1d461f87e2e4ad153c079011b92af20166ee0584e",
)
NEGOTIATION = SourceCandidate(
    Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
         "vtest-android-negotiation-v3/candidate_android_negotiation_verified/candidate-manifest.json"),
    "e1aa871dc22f53e1a9bc68fd86a4d9838c4138d2c9ecf9a7da2d83b2cee37ba3",
    "android-negotiation", True,
    ("WFVTEST_ANDROID_DEFAULT=1", "WFVTEST_NEGOTIATION_TRACE=1"),
    "official_source_android_negotiation",
    155_704, "6a73e38866ac4a4fe6eeae00e792d9ff1ce2ac21aae925afe5c322cf85e30476",
    654_752, "b6e4f9f91a7c1d97896e374e60d86756e194534a15a364350962ee9e0edfff4e",
)
SOURCE_CANDIDATES = {
    "diagnostic": DIAGNOSTIC,
    "android-default": ANDROID_DEFAULT,
    "android-negotiation": NEGOTIATION,
}


def _regular_nosymlink(path: Path) -> Any:
    return shared._regular_nosymlink(path)


def _source_archive_pin() -> None:
    info = _regular_nosymlink(SOURCE_ARCHIVE)
    if info.st_size != SOURCE_ARCHIVE_BYTES:
        raise ValueError("official source archive size mismatch")
    digest = hashlib.sha256(SOURCE_ARCHIVE.read_bytes()).hexdigest()
    if digest != SOURCE_ARCHIVE_SHA256:
        raise ValueError("official source archive hash mismatch")


def _old_provider_manifest_pins() -> dict[str, tuple[int, str]]:
    """Read the old manifest only as an independent authority for its providers."""
    old_manifest = shared.loader.MANIFEST
    info = _regular_nosymlink(old_manifest)
    if info.st_size > shared.loader.MANIFEST_LIMIT:
        raise ValueError("old provider manifest exceeds 128 KiB")
    with old_manifest.open("rb") as stream:
        raw = stream.read(shared.loader.MANIFEST_LIMIT + 1)
    if len(raw) > shared.loader.MANIFEST_LIMIT:
        raise ValueError("old provider manifest exceeds 128 KiB")
    if hashlib.sha256(raw).hexdigest() != OLD_MANIFEST_SHA256:
        raise ValueError("old provider manifest hash mismatch")
    document = json.loads(raw.decode("utf-8"), object_pairs_hook=shared.loader.unique_json_pairs)
    materialized = document.get("materializedFiles") if isinstance(document, dict) else None
    if not isinstance(materialized, list):
        raise ValueError("old provider manifest materialized files are missing")
    result: dict[str, tuple[int, str]] = {}
    for item in materialized:
        if isinstance(item, dict) and item.get("destination") in OLD_PROVIDER_PINS:
            result[item["destination"]] = (item.get("bytes"), item.get("sha256"))
    if result != OLD_PROVIDER_PINS:
        raise ValueError("old provider manifest pins do not match the reviewed closure")
    return result


def _elf_dynamic_metadata(data: bytes, label: str) -> dict[str, Any]:
    """Read the small DT_* closure needed for the two source-built objects."""
    shared.loader._elf64_aarch64_dyn(data, label, require_linker_interp=False)
    if len(data) < 64:
        raise ValueError(f"{label} has a truncated ELF header")
    phoff = int.from_bytes(data[32:40], "little")
    phentsize = int.from_bytes(data[54:56], "little")
    phnum = int.from_bytes(data[56:58], "little")
    if phentsize < 56 or phnum > 128 or phoff + phentsize * phnum > len(data):
        raise ValueError(f"{label} has invalid program headers")

    loads: list[tuple[int, int, int, int]] = []
    dynamic: tuple[int, int] | None = None
    interpreter: str | None = None
    for index in range(phnum):
        header = data[phoff + index * phentsize:phoff + (index + 1) * phentsize]
        p_type = int.from_bytes(header[0:4], "little")
        p_offset = int.from_bytes(header[8:16], "little")
        p_vaddr = int.from_bytes(header[16:24], "little")
        p_filesz = int.from_bytes(header[32:40], "little")
        p_memsz = int.from_bytes(header[40:48], "little")
        if p_offset + p_filesz > len(data):
            raise ValueError(f"{label} has an out-of-bounds program segment")
        if p_type == 1:
            loads.append((p_vaddr, p_memsz, p_offset, p_filesz))
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
        elif p_type == 3:
            raw = data[p_offset:p_offset + p_filesz]
            if not raw or not raw.endswith(b"\0"):
                raise ValueError(f"{label} has an invalid PT_INTERP")
            interpreter = raw[:-1].decode("ascii", "strict")

    def vaddr_to_offset(address: int) -> int:
        for vaddr, memsz, offset, filesz in loads:
            if vaddr <= address < vaddr + memsz and address - vaddr < filesz:
                return offset + address - vaddr
        raise ValueError(f"{label} dynamic address is outside PT_LOAD")

    if dynamic is None:
        raise ValueError(f"{label} has no PT_DYNAMIC")
    dynamic_offset, dynamic_size = dynamic
    if dynamic_size % 16:
        raise ValueError(f"{label} has a malformed dynamic table")
    tags: list[tuple[int, int]] = []
    for offset in range(dynamic_offset, dynamic_offset + dynamic_size, 16):
        tag, value = struct.unpack_from("<qQ", data, offset)
        tags.append((tag, value))
        if tag == 0:
            break
    values = {tag: value for tag, value in tags}
    if 5 not in values or 10 not in values:
        raise ValueError(f"{label} has no bounded dynamic string table")
    strings_offset = vaddr_to_offset(values[5])
    strings_size = values[10]
    if strings_size > len(data) - strings_offset:
        raise ValueError(f"{label} dynamic string table is out of bounds")
    strings = data[strings_offset:strings_offset + strings_size]

    def string_at(index: int) -> str:
        if index >= len(strings):
            raise ValueError(f"{label} has an invalid dynamic string offset")
        end = strings.find(b"\0", index)
        if end < 0:
            raise ValueError(f"{label} has an unterminated dynamic string")
        return strings[index:end].decode("utf-8", "strict")

    needed = [string_at(value) for tag, value in tags if tag == 1]
    return {
        "ptInterp": interpreter,
        "soname": string_at(values[14]) if 14 in values else None,
        "needed": needed,
        "rpath": [string_at(values[15])] if 15 in values else [],
        "runpath": [string_at(values[29])] if 29 in values else [],
    }


def _validate_source_elf(document: dict[str, Any], files: list[dict[str, Any]], *, candidate: SourceCandidate = DIAGNOSTIC) -> None:
    audits = {
        item.get("destination"): item
        for item in document.get("binaryAudit", [])
        if isinstance(item, dict)
    }
    runtime = document.get("runtimePathAudit")
    if not isinstance(runtime, dict):
        raise ValueError("source candidate runtime path audit is missing")
    by_destination = {item["destination"]: item for item in files}
    for destination, expected in candidate.elf_pins.items():
        entry = audits.get(destination)
        if not isinstance(entry, dict) or entry.get("sha256") != candidate.payload_pins[destination][1]:
            raise ValueError(f"source ELF audit pin mismatch: {destination}")
        elf = entry.get("elf")
        if not isinstance(elf, dict):
            raise ValueError(f"source ELF metadata is missing: {destination}")
        for key in ("bytes", "elfClass", "elfData", "elfMachine", "elfType", "ptInterp", "soname", "needed", "rpath", "runpath"):
            expected_value: Any = expected.get(key)
            if key == "elfClass":
                expected_value = 64
            elif key == "elfData":
                expected_value = "LE"
            elif key == "elfMachine":
                expected_value = 183
            elif key == "elfType":
                expected_value = "ET_DYN"
            if elf.get(key) != expected_value:
                raise ValueError(f"source ELF manifest field mismatch: {destination}:{key}")
        path_audit = runtime.get(destination)
        if not isinstance(path_audit, dict):
            raise ValueError(f"source runtime path audit missing: {destination}")
        for key in ("ptInterp", "soname", "rpath", "runpath"):
            if path_audit.get(key) != expected[key]:
                raise ValueError(f"source runtime path mismatch: {destination}:{key}")
        item = by_destination[destination]
        actual = _elf_dynamic_metadata(item["path"].read_bytes(), destination)
        if actual != {key: expected[key] for key in ("ptInterp", "soname", "needed", "rpath", "runpath")}:
            raise ValueError(f"source ELF header/dynamic metadata mismatch: {destination}")


def _validate_source_materialized(document: dict[str, Any], candidate_root: Path) -> list[dict[str, Any]]:
    """Validate the same closed layout as the old loader, with source size pins."""
    materialized = document.get("materializedFiles")
    if not isinstance(materialized, list) or len(materialized) != 10:
        raise ValueError("source candidate must contain exactly ten materialized files")
    files: list[dict[str, Any]] = []
    destinations: set[str] = set()
    total = 0
    for entry in materialized:
        if not isinstance(entry, dict):
            raise ValueError("malformed source materialized file")
        destination = entry.get("destination")
        if not isinstance(destination, str) or destination in destinations or destination not in SOURCE_PAYLOAD_DESTINATIONS:
            raise ValueError("unexpected source payload destination")
        parts = destination.split("/")
        if len(parts) != 2 or parts[0] not in {"bin", "lib"} or not re.fullmatch(r"[A-Za-z0-9_.-]+", parts[1]):
            raise ValueError("unsafe source payload destination")
        size = entry.get("bytes")
        digest = entry.get("sha256")
        mode = entry.get("mode")
        if type(size) is not int or size <= 0 or size > shared.loader.PAYLOAD_LIMIT:
            raise ValueError("source payload size is outside its bound")
        if not isinstance(digest, str) or not shared.loader.HEX64.fullmatch(digest):
            raise ValueError("invalid source payload hash")
        expected_mode = "0700" if destination == "bin/virgl_test_server" else "0600"
        if mode != expected_mode:
            raise ValueError("invalid source payload mode")
        path = candidate_root / destination
        info = _regular_nosymlink(path)
        if info.st_size != size or stat.S_IMODE(info.st_mode) != int(mode, 8):
            raise ValueError(f"source payload metadata mismatch: {destination}")
        data = path.read_bytes()
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"source payload hash mismatch: {destination}")
        shared.loader._elf64_aarch64_dyn(data, destination, destination == "bin/virgl_test_server")
        destinations.add(destination)
        total += size
        files.append({"destination": destination, "path": path, "sha256": digest, "bytes": size, "mode": mode})
    if destinations != SOURCE_PAYLOAD_DESTINATIONS or total > shared.loader.PAYLOAD_TOTAL_LIMIT:
        raise ValueError("source candidate closure is not the approved ten-file payload")
    return files


def validate_source_manifest(document: dict[str, Any], candidate_root: Path, *, candidate: SourceCandidate = DIAGNOSTIC) -> list[dict[str, Any]]:
    """Apply source-only gates after the old ten-file closure validator."""
    if document.get("schema") != shared.loader.SCHEMA or document.get("schemaVersion") != 1:
        raise ValueError("unexpected source candidate schema")
    if document.get("executedPayloads") is not False or document.get("ready") is not False:
        raise ValueError("source candidate must remain static and not ready")
    if document.get("protocol", {}).get("status") != "UNVERIFIED":
        raise ValueError("source candidate protocol is not unverified")
    aliases = document.get("deviceAliases")
    if aliases != shared.loader.EXPECTED_ALIASES:
        raise ValueError("unexpected source device aliases")

    execution = document.get("deviceExecution")
    if not isinstance(execution, dict) or any(execution.get(key) is not False for key in ("adb", "executed", "phoneBinariesExecuted")):
        raise ValueError("source candidate records device execution")
    profile = document.get("buildProfile")
    if not isinstance(profile, dict) or profile.get("name") != candidate.profile or profile.get("officialSourceBuild") is not True or profile.get("functionalEglFix") is not candidate.functional_fix or profile.get("androidMacroEnabled") is not True or profile.get("compatibilityDefines") != list(candidate.compatibility_defines) or profile.get("removedPlatforms") != ["glx"]:
        raise ValueError("source candidate build profile does not match the fixed selection")
    options = profile.get("options")
    if not isinstance(options, dict) or options.get("platforms") != ["egl"] or options.get("drm-renderers") != []:
        raise ValueError("source candidate build options are not EGL-only")
    config = document.get("source", {}).get("configHeader") if isinstance(document.get("source"), dict) else None
    defines = config.get("defines") if isinstance(config, dict) else None
    if not isinstance(defines, dict) or defines.get("ENABLE_GBM") != "defined" or defines.get("HAVE_EPOXY_EGL_H") != "defined" or defines.get("HAVE_EPOXY_GLX_H") is not None:
        raise ValueError("source candidate effective headers are not pinned")
    if candidate.functional_fix and defines.get("ENABLE_GBM_ALLOCATION") != "1":
        raise ValueError("source candidate compiled allocation metadata differs from the qualified build")
    toolchain = document.get("toolchain")
    if not isinstance(toolchain, dict) or toolchain.get("clangTarget") != "aarch64-linux-android29" or not str(toolchain.get("ndk", "")).endswith("/ndk/24.0.8215888"):
        raise ValueError("source candidate toolchain is not Android 29 NDK 24")
    source = document.get("source")
    if not isinstance(source, dict) or source.get("archive") != str(SOURCE_ARCHIVE) or source.get("bytes") != SOURCE_ARCHIVE_BYTES or source.get("sha256") != SOURCE_ARCHIVE_SHA256 or source.get("root") != SOURCE_ROOT:
        raise ValueError("official source provenance pin mismatch")
    _source_archive_pin()

    old_provider_pins = _old_provider_manifest_pins()
    files = _validate_source_materialized(document, candidate_root)
    by_destination = {item["destination"]: item for item in files}
    for destination, (size, digest) in candidate.payload_pins.items():
        item = by_destination.get(destination)
        if item is None or item["bytes"] != size or item["sha256"] != digest:
            raise ValueError(f"source payload pin mismatch: {destination}")
        identity = next(
            entry.get("sourceIdentity")
            for entry in document["materializedFiles"]
            if entry.get("destination") == destination
        )
        if not isinstance(identity, dict):
            raise ValueError(f"source identity is missing: {destination}")
        if destination in old_provider_pins:
            if identity.get("kind") != "existing_server_candidate_v2" or identity.get("manifest") != str(shared.loader.MANIFEST):
                raise ValueError(f"provider provenance mismatch: {destination}")
        else:
            if identity.get("kind") != "official_source_build" or identity.get("sourceSha256") != SOURCE_ARCHIVE_SHA256:
                raise ValueError(f"source build provenance mismatch: {destination}")
    _validate_source_elf(document, files, candidate=candidate)
    return files


def load_candidate(*, candidate: SourceCandidate = DIAGNOSTIC) -> tuple[dict[str, Any], list[dict[str, Any]], bytes]:
    """Read the exact source manifest before any transfer is attempted."""
    # Keep the reviewed diagnostic defaults visible to existing fixtures; the
    # functional selection is immutable and never accepts a caller-supplied path.
    manifest = SOURCE_MANIFEST if candidate is DIAGNOSTIC else candidate.manifest
    digest = SOURCE_MANIFEST_SHA256 if candidate is DIAGNOSTIC else candidate.manifest_sha256
    _regular_nosymlink(manifest)
    if manifest.stat().st_size > SOURCE_MANIFEST_LIMIT:
        raise ValueError("source candidate manifest exceeds 1 MiB")
    with manifest.open("rb") as stream:
        raw = stream.read(SOURCE_MANIFEST_LIMIT + 1)
    if len(raw) > SOURCE_MANIFEST_LIMIT or hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError("source candidate manifest hash mismatch")
    document = json.loads(raw.decode("utf-8"), object_pairs_hook=shared.loader.unique_json_pairs)
    if not isinstance(document, dict):
        raise ValueError("source candidate manifest is not an object")
    files = validate_source_manifest(document, manifest.parent, candidate=candidate)
    capture_document = dict(document)
    capture_document["candidateKind"] = candidate.kind
    capture_document["oldProviderManifestSha256"] = OLD_MANIFEST_SHA256
    return capture_document, files, raw


def _validate_copy_inputs(files: list[dict[str, Any]], *, candidate: SourceCandidate = DIAGNOSTIC) -> dict[str, dict[str, Any]]:
    if len(files) != len(SOURCE_PAYLOAD_PINS):
        raise ValueError("source copier received an unexpected payload count")
    by_destination: dict[str, dict[str, Any]] = {}
    for item in files:
        if not isinstance(item, dict) or item.get("destination") in by_destination:
            raise ValueError("source copier received duplicate payload output")
        destination = item.get("destination")
        if destination not in SOURCE_PAYLOAD_DESTINATIONS:
            raise ValueError("source copier received an unapproved payload output")
        expected_bytes, expected_sha = candidate.payload_pins[destination]
        path = item.get("path")
        if not isinstance(path, Path) or item.get("bytes") != expected_bytes or item.get("sha256") != expected_sha:
            raise ValueError(f"source copier payload pin mismatch: {destination}")
        expected_mode = "0700" if destination == "bin/virgl_test_server" else "0600"
        if item.get("mode") != expected_mode:
            raise ValueError(f"source copier payload mode mismatch: {destination}")
        info = _regular_nosymlink(path)
        if info.st_size != expected_bytes or stat.S_IMODE(info.st_mode) != int(expected_mode, 8):
            raise ValueError(f"source copier payload metadata mismatch: {destination}")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected_sha:
            raise ValueError(f"source copier payload hash mismatch: {destination}")
        by_destination[destination] = item
    if set(by_destination) != SOURCE_PAYLOAD_DESTINATIONS:
        raise ValueError("source copier payload closure mismatch")
    return by_destination


def copy_payload(files: list[dict[str, Any]], run: str, *, candidate: SourceCandidate = DIAGNOSTIC) -> tuple[str, dict[str, Any]]:
    """Use the old ten-file transfer, then add one exclusive private alias."""
    by_destination = _validate_copy_inputs(files, candidate=candidate)
    relative, staging = shared.copy_payload(files, run)
    if staging.get("stagingClean") is not True:
        raise RuntimeError("source payload staging was not cleaned before execution")
    if not re.fullmatch(r"files/forever/controls/vtest-loader/[A-Za-z0-9_-]{1,100}", relative):
        raise ValueError("source copier received an unapproved private run")

    target = f"{relative}/lib/libvirglrenderer.so"
    alias = f"{relative}/lib/libvirglrenderer.so.1"
    expected_sha = by_destination["lib/libvirglrenderer.so"]["sha256"]
    script = "; ".join(
        [
            "umask 077",
            "set -e",
            "set -C",
            f"test -f {shared._q(target)} && test ! -L {shared._q(target)}",
            f"test \"$(sha256sum {shared._q(target)} | cut -d' ' -f1)\" = {shared._q(expected_sha)}",
            f"test \"$(stat -c '%a' {shared._q(target)})\" = 600",
            f"test ! -e {shared._q(alias)} && test ! -L {shared._q(alias)}",
            f"ln -s libvirglrenderer.so {shared._q(alias)}",
            f"test -L {shared._q(alias)} && test \"$(readlink {shared._q(alias)})\" = libvirglrenderer.so",
            f"test -f {shared._q(target)} && test ! -L {shared._q(target)}",
            f"test \"$(sha256sum {shared._q(target)} | cut -d' ' -f1)\" = {shared._q(expected_sha)}",
            "printf VTEST_SOURCE_ALIAS_OK",
        ]
    )
    result = shared.adb("exec-out", "run-as", shared.PACKAGE, "sh", "-c", script, timeout=30)
    if result.returncode or result.stdout != b"VTEST_SOURCE_ALIAS_OK" or result.stderr:
        raise RuntimeError("private source SONAME alias verification failed")
    staging = dict(staging)
    staging["sourceAlias"] = {
        "destination": "lib/libvirglrenderer.so.1",
        "target": "libvirglrenderer.so",
        "targetSha256": expected_sha,
        "targetRegular": True,
        "readlink": "libvirglrenderer.so",
        "exclusive": True,
    }
    return relative, staging


def main(argv: list[str] | None = None) -> int:
    # Only two fixed artifact tuples. No dynamic path/hash/command option.
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--source-profile", choices=tuple(SOURCE_CANDIDATES), default="diagnostic")
    args, remaining = parser.parse_known_args(argv)
    selected = SOURCE_CANDIDATES[args.source_profile]
    if selected is DIAGNOSTIC:
        return shared.main(remaining, candidate_loader=load_candidate, payload_copier=copy_payload)
    return shared.main(
        remaining,
        candidate_loader=lambda: load_candidate(candidate=selected),
        payload_copier=lambda files, run: copy_payload(files, run, candidate=selected),
    )


if __name__ == "__main__":
    raise SystemExit(main())
