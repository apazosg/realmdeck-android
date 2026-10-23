#!/usr/bin/env python3
"""Materialize the fixed, offline GLVND/Mesa EGL loader candidate.

This is intentionally narrower than the general virgl materializer: its
metadata digest, package pins, roots, and export ABI are fixed in this file.
It never downloads, loads, or executes a guest ELF.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from typing import Any, Iterable

import materialize_candidate as materializer
import audit_payloads as audit


METADATA_SHA256 = "5be59bf0e7b76cb90e95599509354e37e1fea36d2ac5cb7cff161508025d0968"
ROOTS = ("libEGL.so.1", "libEGL_mesa.so")
READelf = Path(
    "/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
)
REQUIRED_LOADER_EXPORTS = (
    "eglGetPlatformDisplay",
    "eglInitialize",
    "eglCreateContext",
    "eglMakeCurrent",
    "eglGetProcAddress",
    "eglGetError",
    "eglChooseConfig",
    "eglBindAPI",
    "eglTerminate",
)
REQUIRED_VENDOR_EXPORTS = ("__egl_Main",)
EXPECTED_PINS = {
    "mesa": ("mesa_26.2.4_aarch64.deb", 3_146_408, "d072b0144a9a6f4f80bd08797893aa37d4b75209aae810f2e84843a8fa23342e"),
    "libglvnd": ("libglvnd_1.7.0-1_aarch64.deb", 178_340, "67f3fd247e4279a7fceddbd2929f8b53e87c255edfc9c07d0dee7df5fe243d2c"),
}
_SYMBOL_RE = re.compile(r"^\s*([0-9]+):\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)(?:\s+(\S+))?\s*$")


class EglCandidateError(ValueError):
    """A fixed-ledger, export-ABI, or output-safety failure."""


def _validate_fixed_ledger(metadata_path: Path) -> tuple[dict[str, Any], list[materializer.LedgerPackage]]:
    raw = materializer._read_existing(metadata_path, materializer.MAX_METADATA_BYTES)
    digest = hashlib.sha256(raw).hexdigest()
    if digest != METADATA_SHA256:
        raise EglCandidateError(f"metadata SHA-256 mismatch: {digest} != {METADATA_SHA256}")
    metadata, records = materializer.load_fixed_metadata(metadata_path)
    by_name = {record.package: record for record in records}
    for package, (basename, size, sha256) in EXPECTED_PINS.items():
        record = by_name.get(package)
        if record is None or (record.basename, record.bytes, record.sha256) != (basename, size, sha256):
            raise EglCandidateError(f"fixed pin mismatch for required package: {package}")
    if len(records) != 29:
        raise EglCandidateError(f"fixed metadata package count changed: {len(records)}")
    return metadata, records


def match_required_exports(
    readelf_text: str, required: Iterable[str]
) -> tuple[list[dict[str, str]], list[str]]:
    """Return defined FUNCTION records matching *required*, not undefined imports."""
    wanted = set(required)
    found: dict[str, dict[str, str]] = {}
    for line in readelf_text.splitlines():
        match = _SYMBOL_RE.match(line)
        if not match:
            continue
        _number, value, size, symbol_type, bind, visibility, index, name = match.groups()
        if (not name or symbol_type != "FUNC" or not index.isdigit()
                or bind not in {"GLOBAL", "WEAK"} or visibility not in {"DEFAULT", "PROTECTED"}
                or int(value, 16) == 0):
            continue
        plain_name = name.split("@", 1)[0]
        if plain_name in wanted:
            found.setdefault(
                plain_name,
                {
                    "name": plain_name,
                    "type": symbol_type,
                    "bind": bind,
                    "visibility": visibility,
                    "index": index,
                    "value": value,
                    "size": size,
                },
            )
    records = [found[name] for name in sorted(found)]
    return records, sorted(wanted - set(found))


def audit_elf_exports(path: Path, required: Iterable[str]) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise EglCandidateError(f"selected ELF is not a regular file: {path}")
    if not READelf.is_file():
        raise EglCandidateError(f"pinned host llvm-readelf is unavailable: {READelf}")
    if path.stat().st_size > 4 * 1024 * 1024:
        raise EglCandidateError(f"export-audited entrypoint exceeds 4 MiB: {path}")
    with path.open("rb") as stream:
        raw = stream.read(4 * 1024 * 1024 + 1)
    if len(raw) > 4 * 1024 * 1024:
        raise EglCandidateError(f"export-audited entrypoint grew beyond its bound: {path}")
    try:
        elf = audit.parse_elf(raw)
    except audit.AuditError as exc:
        raise EglCandidateError(f"selected ELF audit failed: {path}: {exc}") from exc
    try:
        result = subprocess.run(
            [str(READelf), "--dyn-syms", "--dynamic", str(path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
            timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise EglCandidateError(f"cannot run pinned host llvm-readelf: {exc}") from exc
    if result.returncode != 0:
        raise EglCandidateError(f"llvm-readelf failed for {path}: {result.stderr.strip()}")
    exports, missing = match_required_exports(result.stdout, required)
    if missing:
        raise EglCandidateError(f"required FUNCTION exports absent from {path.name}: {missing}")
    return {
        "path": str(path),
        "bytes": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "elf": {
            "class": elf["elfClass"],
            "data": elf["elfData"],
            "machine": elf["elfMachine"],
            "type": elf["elfType"],
            "soname": elf["soname"],
            "needed": elf["needed"],
        },
        "exports": exports,
        "requiredExports": sorted(required),
        "exportTool": str(READelf),
        "executionPerformed": False,
    }


def _rewrite_manifest(output_dir: Path, manifest: dict[str, Any], export_audit: list[dict[str, Any]]) -> Path:
    path = output_dir / "candidate-manifest.json"
    if path.is_symlink() or not path.is_file():
        raise EglCandidateError("materializer did not create its manifest")
    manifest["exportAudit"] = {
        "status": "PRESENT",
        "method": "host llvm-readelf --dyn-syms --dynamic; guest ELF never loaded",
        "records": export_audit,
    }
    manifest["privateLoaderResolution"] = {
        "status": "UNVERIFIED",
        "method": "preserve original DT_RUNPATH; no patchelf or loader execution",
    }
    manifest["protocol"] = {"status": "UNVERIFIED"}
    manifest["ready"] = False
    manifest["present"] = False
    manifest["d3d"] = False
    manifest["wow"] = False
    temporary = output_dir / ".candidate-manifest.egl.tmp"
    if temporary.exists() or temporary.is_symlink():
        raise EglCandidateError("manifest temporary output already exists")
    with temporary.open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    return path


def _tree_sha256(output_dir: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(output_dir.rglob("*")):
        relative = path.relative_to(output_dir).as_posix()
        digest.update(relative.encode("utf-8") + b"\0")
        if path.is_symlink():
            digest.update(b"L\0" + os.readlink(path).encode("utf-8") + b"\0")
        elif path.is_file():
            digest.update(b"F\0")
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(64 * 1024), b""):
                    digest.update(chunk)
            digest.update(b"\0")
        elif path.is_dir():
            digest.update(b"D\0")
    return digest.hexdigest()


def materialize_egl_candidate(metadata_path: Path, package_dir: Path, output_dir: Path) -> dict[str, Any]:
    metadata, records = _validate_fixed_ledger(metadata_path)
    manifest = materializer.materialize_candidate(
        metadata_path,
        package_dir,
        output_dir,
        roots=ROOTS,
        offline_only=True,
    )
    loader = audit_elf_exports(output_dir / "lib/libEGL.so.1.1.0", REQUIRED_LOADER_EXPORTS)
    vendor = audit_elf_exports(output_dir / "lib/libEGL_mesa.so", REQUIRED_VENDOR_EXPORTS)
    for record in (loader, vendor):
        relative = Path(record["path"]).relative_to(output_dir).as_posix()
        selected = next((item for item in manifest["materializedFiles"] if item["path"] == relative), None)
        if selected is None or selected["sha256"] != record["sha256"] or selected["size"] != record["bytes"]:
            raise EglCandidateError(f"export-audited ELF is not the selected materialized file: {relative}")
        record["package"] = selected["package"]
        record["packagePath"] = selected["packagePath"]
    manifest["fixedCandidate"] = {
        "id": "egl-loader-v1",
        "roots": list(ROOTS),
        "metadataSha256": hashlib.sha256(materializer._read_existing(metadata_path, materializer.MAX_METADATA_BYTES)).hexdigest(),
        "sourceRecipeCommit": metadata.get("sourceRecipeCommit"),
        "requiredPackagePins": {
            package: {"basename": values[0], "bytes": values[1], "sha256": values[2]}
            for package, values in EXPECTED_PINS.items()
        },
        "ledgerPackageCount": len(records),
        "selectedRegularFileCount": manifest["selectedRegularFileCount"],
        "selectedRegularFileBytes": manifest["selectedRegularFileBytes"],
        "selectedAliasCount": manifest["selectedAliasCount"],
    }
    _rewrite_manifest(output_dir, manifest, [loader, vendor])
    manifest_path = output_dir / "candidate-manifest.json"
    manifest["manifestSha256"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    manifest["candidateTreeSha256"] = _tree_sha256(output_dir)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--package-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    manifest = materialize_egl_candidate(args.metadata, args.package_dir, args.output_dir)
    print(
        json.dumps(
            {
                "outputDir": str(args.output_dir),
                "manifest": str(args.output_dir / "candidate-manifest.json"),
                "manifestSha256": manifest["manifestSha256"],
                "candidateTreeSha256": manifest["candidateTreeSha256"],
                "selectedRegularFileCount": manifest["selectedRegularFileCount"],
                "selectedRegularFileBytes": manifest["selectedRegularFileBytes"],
                "selectedAliasCount": manifest["selectedAliasCount"],
                "requiredExports": {item["path"]: item["requiredExports"] for item in manifest["exportAudit"]["records"]},
                "vendorJson": manifest["vendorJson"],
                "ready": manifest["ready"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
