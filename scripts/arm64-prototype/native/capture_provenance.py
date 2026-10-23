#!/usr/bin/env python3
"""Capture the independent source/toolchain/native build provenance sidecar."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from elf16k_audit import audit_elf


SCRIPT_ROOT = Path("scripts/arm64-prototype/native")
REQUIRED_SOURCE_FILES = (
    Path("Cargo.toml"),
    Path("Cargo.lock"),
    Path("forever/contract.json"),
    Path("scripts/arm64-prototype/native/build_functional_capture.sh"),
    Path("scripts/arm64-prototype/native/elf16k_audit.py"),
    Path("scripts/arm64-prototype/native/capture_provenance.py"),
)


def _regular_file(path: Path, label: str) -> Path:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"{label} is not a regular non-symlink file: {path}")
    return path


def sha256(path: Path) -> str:
    return hashlib.sha256(_regular_file(path, "provenance input").read_bytes()).hexdigest()


def _source_paths(root: Path) -> list[Path]:
    paths = {path for path in REQUIRED_SOURCE_FILES}
    for path in (root / "crates").rglob("*"):
        if path.is_symlink():
            raise ValueError(f"source tree contains a symlink: {path}")
        relative = path.relative_to(root)
        if path.is_file() and (
            (path.suffix == ".rs" and "target" not in relative.parts)
            or path.name == "build.rs"
            or (path.name in {"Cargo.toml", "Cargo.lock"})
        ):
            paths.add(relative)
    # Only Cargo's applicable ancestor configuration, never recurse through
    # client/CASC data or unrelated external projects looking for config files.
    for path in (root / ".cargo", root / "crates/.cargo", root / "crates/forever-patcher/.cargo", root / "crates/forever-patcher-jni/.cargo"):
        if path.is_symlink():
            raise ValueError(f"source tree contains a symlink: {path}")
        if path.is_dir():
            for config in path.glob("config*"):
                if config.is_symlink():
                    raise ValueError(f"Cargo config is a symlink: {config}")
                if config.is_file():
                    paths.add(config.relative_to(root))
    return sorted(paths)


def source_manifest(root: Path) -> dict[str, str]:
    paths = _source_paths(root)
    missing = [str(path) for path in paths if not (root / path).is_file() or (root / path).is_symlink()]
    if missing:
        raise ValueError("missing provenance inputs: " + ", ".join(missing))
    return {str(path): sha256(root / path) for path in paths}


def command_text(command: list[str]) -> str:
    result = subprocess.run(command, check=False, capture_output=True, text=True, timeout=10)
    if result.returncode != 0:
        raise ValueError(f"tool identity command failed: {command[0]}")
    return (result.stdout + result.stderr).strip()[:4096]


def _tool(path: Path, name: str) -> dict[str, str]:
    # NDK's llvm-strip/readelf are official multi-call symlinks. Keep their
    # invocation name, but pin the resolved binary rather than hashing the link.
    resolved = _regular_file(path.resolve(strict=True), name)
    return {"path": str(path), "resolvedPath": str(resolved), "sha256": sha256(resolved), "version": command_text([str(path), "--version"])}


def toolchain_manifest(
    ndk: Path,
    rustc: Path,
    cargo: Path,
    strip: Path,
    linker: Path,
    readelf: Path,
) -> dict[str, object]:
    if ndk.is_symlink() or not ndk.is_dir():
        raise ValueError(f"NDK is not a real directory: {ndk}")
    source_properties = _regular_file(ndk / "source.properties", "NDK source.properties")
    properties = source_properties.read_text(encoding="utf-8")
    match = re.search(r"^Pkg\.Revision\s*=\s*(.+)$", properties, re.MULTILINE)
    if not match:
        raise ValueError("NDK source.properties has no Pkg.Revision")
    return {
        "api": 29,
        "elfPageSize": 16384,
        "ndk": {
            "path": str(ndk),
            "version": match.group(1).strip(),
            "sourcePropertiesSha256": sha256(source_properties),
        },
        "rustc": _tool(rustc, "rustc"),
        "cargo": _tool(cargo, "cargo"),
        "llvmStrip": _tool(strip, "llvm-strip"),
        "linker": _tool(linker, "linker"),
        "readelf": _tool(readelf, "llvm-readelf"),
    }


def _git(root: Path, *args: str) -> str:
    return command_text(["git", "-C", str(root), *args])


def collect(
    root: Path,
    ndk: Path,
    rustc: Path,
    cargo: Path,
    strip: Path,
    linker: Path,
    readelf: Path,
    so: Path | None,
    phase: str,
) -> dict[str, object]:
    if phase not in {"before", "after"}:
        raise ValueError("phase must be before or after")
    value: dict[str, object] = {
        "schema": 1,
        "phase": phase,
        "revision": _git(root, "rev-parse", "HEAD"),
        "dirtyTree": bool(_git(root, "status", "--short", "--untracked-files=all", "--", "Cargo.toml", "Cargo.lock", "crates", "forever/contract.json", str(SCRIPT_ROOT))),
        "sourceSha256": source_manifest(root),
        "toolchain": toolchain_manifest(ndk, rustc, cargo, strip, linker, readelf),
    }
    if so is not None:
        so = _regular_file(so, "native ELF")
        elf = so.read_bytes()
        loads = audit_elf(elf)
        value["nativeElf"] = {
            "path": str(so),
            "bytes": len(elf),
            "sha256": hashlib.sha256(elf).hexdigest(),
            "elfClass": 64,
            "machine": "AArch64",
            "ptLoad": loads,
        }
    elif phase == "after":
        raise ValueError("after provenance requires the compiled native ELF")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--rustc", type=Path, required=True)
    parser.add_argument("--cargo", type=Path, required=True)
    parser.add_argument("--strip", type=Path, required=True)
    parser.add_argument("--linker", type=Path, required=True)
    parser.add_argument("--readelf", type=Path, required=True)
    parser.add_argument("--so", type=Path)
    parser.add_argument("--phase", choices=("before", "after"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    value = collect(
        args.root, args.ndk, args.rustc, args.cargo, args.strip, args.linker, args.readelf,
        args.so if args.so else None, args.phase,
    )
    args.output.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
