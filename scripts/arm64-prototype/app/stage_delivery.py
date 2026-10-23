#!/usr/bin/env python3
"""Stage the audited private APK and source recipe without overwriting artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tempfile
import zipfile
from pathlib import Path


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def publish(temporary: Path, destination: Path) -> None:
    if destination.is_symlink():
        raise ValueError(f"refusing a symlink artifact: {destination}")
    if destination.exists():
        if sha(temporary) != sha(destination):
            raise ValueError(f"refusing to overwrite another artifact: {destination}")
        return
    # Hard-linking an owned temporary file provides atomic, no-overwrite publish.
    os.link(temporary, destination)


def stage(directory: Path, name: str, writer) -> Path:
    with tempfile.NamedTemporaryFile(prefix=".forever-delivery-", dir=directory) as stream:
        writer(stream)
        stream.flush()
        os.fsync(stream.fileno())
        destination = directory / name
        publish(Path(stream.name), destination)
    return destination


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", type=Path, required=True)
    parser.add_argument("--delivery", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    directory = args.delivery.resolve()
    if not directory.is_dir() or directory.is_relative_to(repo):
        raise ValueError("delivery must be an existing external directory")
    audit = json.loads(args.audit.read_text())
    if audit["result"] != "PASS" or audit["package"] != "com.wowforever.arm64probe" or audit["versionCode"] != 35 or audit["versionName"] != "0.2.11-automated":
        raise ValueError("not an audited automated candidate")
    source = Path(audit["apk"])
    if sha(source) != audit["sha256"] or source.stat().st_size != audit["bytes"]:
        raise ValueError("APK changed after audit")

    def copy_apk(stream) -> None:
        with source.open("rb") as input_stream:
            shutil.copyfileobj(input_stream, stream, length=1024 * 1024)

    apk = stage(directory, "wowforever-automated-0.2.11-code35-debug.apk", copy_apk)
    if sha(apk) != audit["sha256"]:
        raise ValueError("staged APK differs")
    files = [apk]
    files.append(stage(directory, "wowforever-automated-0.2.11-apk-audit.json", lambda stream: stream.write(args.audit.read_bytes())))
    instructions = Path(__file__).with_name("INSTRUCCIONES-automated.txt").read_text()
    instructions = instructions.replace("{{APK_SHA256}}", audit["sha256"]).replace("{{APK_BYTES}}", str(audit["bytes"]))
    files.append(stage(directory, "INSTRUCCIONES-wowforever-automated-0.2.11.txt", lambda stream: stream.write(instructions.encode())))

    sources = set()
    for base in (repo / "scripts/arm64-prototype", repo / "scripts/diagnostics/arm64-probes"):
        sources.update(path for path in base.rglob("*") if path.is_file() and not path.is_symlink() and path.suffix in {".py", ".kt", ".json", ".md", ".txt", ".c", ".h", ".sh"})
    for crate in ("forever-patcher", "forever-patcher-jni"):
        root = repo / "crates" / crate
        for subtree in ("src", "tests"):
            sources.update((root / subtree).rglob("*.rs"))
        sources.update(path for path in (root / "Cargo.toml", root / "Cargo.lock", root / "README.md") if path.is_file())
    sources.update(repo / name for name in (
        "forever/contract.json", ".agents/specs/SYSTEM.md", ".agents/specs/US-002.md", ".agents/specs/US-003.md",
        "docs/arm64-client-bootstrap.md", "docs/windows-arm64-runtime.md", "docs/automated-arm64-launcher.md",
        "docs/community-arm64-findings.md", "docs/thor-forever-findings.md", "docs/jaredgei-wow-forever-android-findings.md",
        "docs/device-poco-xaga.md", "docs/usb-lab-xaga.md",
        "tests/test_arm64_client_catalog.py", "tests/test_arm64_probes.py",
    ))
    def source_bundle(stream) -> None:
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            def member(name: str, data: bytes) -> None:
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)
            for path in sorted(sources):
                if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(repo):
                    raise ValueError(f"invalid source input: {path}")
                member("wowforever/" + path.relative_to(repo).as_posix(), path.read_bytes())
            member("apk-audit.json", (json.dumps(audit, indent=2) + "\n").encode())
    files.append(stage(directory, "wowforever-automated-0.2.11-source-recipe.zip", source_bundle))
    sums = "".join(f"{sha(path)}  {path.name}\n" for path in files)
    stage(directory, "SHA256SUMS-wowforever-automated-0.2.11.txt", lambda stream: stream.write(sums.encode()))
    print(f"DELIVERY-PASS {apk} {apk.stat().st_size} bytes sha256={sha(apk)}; {len(sources)} source files")


if __name__ == "__main__":
    main()
