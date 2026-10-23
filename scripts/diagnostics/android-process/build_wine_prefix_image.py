#!/usr/bin/env python3
"""Build the byte-stable, link-free Wine prefix image.

The input archive is evidence, not an extraction source for the APK.  Only the
typed members in the audited template are copied, and the three approved
graphics files replace their archive counterparts (which are deliberately not
present in the pristine prefix).  The resulting ZIP and manifest are suitable
for an APK asset; no Wine process is started here.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import tarfile
import zipfile
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parent
TEMPLATE = ROOT / "wine-prefix-template.json"
TEMPLATE_SHA256 = "306bed4ade9aac21809919f1b1272cdbc9c389e77704a13374033dcee7b0bcfa"
ARCHIVE_SHA256 = "ade99dc80b17086a269c038c9bc50167902103a9a61df763cba4d613543ff12a"
ARCHIVE_SIZE = 22_067_952
MAX_MEMBER = 512 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024


class ImageError(RuntimeError):
    pass


def digest(path: Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()


def safe_path(value: str) -> str:
    if not isinstance(value, str) or not value or "\x00" in value or "\\" in value:
        raise ImageError(f"unsafe path: {value!r}")
    parts = value.split("/")
    if value.startswith("/") or "//" in value or any(p in {"", ".", ".."} for p in parts):
        raise ImageError(f"unsafe path: {value!r}")
    if PurePosixPath(value).parts != tuple(parts):
        raise ImageError(f"non-canonical path: {value!r}")
    return value


def load_template() -> dict[str, object]:
    raw = TEMPLATE.read_bytes()
    if hashlib.sha256(raw).hexdigest() != TEMPLATE_SHA256:
        raise ImageError("template manifest SHA-256 mismatch")
    value = json.loads(raw)
    if value.get("schema") != "wowforever.wine-prefix-template/v1":
        raise ImageError("unsupported template manifest")
    return value


def verify_file(path: Path, size: int, expected: str, label: str) -> None:
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size != size:
        raise ImageError(f"{label} metadata mismatch")
    actual = digest(path)
    if actual != expected:
        raise ImageError(f"{label} SHA-256 mismatch: {actual}")


def archive_members(archive: Path, expected: dict[str, object]) -> dict[str, bytes | None]:
    info = archive.stat()
    if info.st_size != ARCHIVE_SIZE or digest(archive) != ARCHIVE_SHA256:
        raise ImageError("prefixPack.txz pin mismatch")
    expected_by_path = {item["path"]: item for item in expected["archive"]["members"]}
    found: dict[str, bytes | None] = {}
    total = 0
    with tarfile.open(archive, mode="r:xz") as stream:
        for member in stream:
            name = safe_path(member.name)
            if name in found:
                raise ImageError(f"duplicate archive member: {name}")
            item = expected_by_path.get(name)
            if item is None:
                raise ImageError(f"unlisted archive member: {name}")
            kind = "directory" if member.isdir() else "regular" if member.isfile() else "symlink" if member.issym() else "special"
            if kind != item["type"] or (member.mode & 0o7777) != item["mode"] or member.size != item["bytes"]:
                raise ImageError(f"archive metadata mismatch: {name}")
            if kind == "directory":
                found[name] = None
            elif kind == "regular":
                if member.size > MAX_MEMBER:
                    raise ImageError(f"member too large: {name}")
                payload = stream.extractfile(member)
                if payload is None:
                    raise ImageError(f"missing stream: {name}")
                data = payload.read(member.size + 1)
                if len(data) != member.size or hashlib.sha256(data).hexdigest() != item["sha256"]:
                    raise ImageError(f"archive member digest mismatch: {name}")
                total += len(data)
                if total > MAX_TOTAL:
                    raise ImageError("archive payload exceeds bound")
                found[name] = data
            else:
                # Links are checked as evidence only.  None is copied.
                if member.linkname != item.get("target"):
                    raise ImageError(f"archive link target mismatch: {name}")
                found[name] = None
    if set(found) != set(expected_by_path):
        raise ImageError("archive member set mismatch")
    return found


def zip_info(name: str, mode: int, directory: bool) -> zipfile.ZipInfo:
    stored = name + "/" if directory else name
    info = zipfile.ZipInfo(stored, date_time=(1980, 1, 1, 0, 0, 0))
    info.create_system = 3
    info.external_attr = (mode & 0o7777) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    return info


def write_header(path: Path, manifest: dict[str, object], image_manifest_sha: str) -> None:
    members = manifest["entries"]
    links = manifest["links"]
    lines = [
        "/* Generated by build_wine_prefix_image.py; do not edit. */",
        "#ifndef WINE_PREFIX_PUBLISH_MANIFEST_H",
        "#define WINE_PREFIX_PUBLISH_MANIFEST_H",
        "#include <stddef.h>",
        "#include <stdint.h>",
        f'#define WPP_IMAGE_MANIFEST_SHA256 "{image_manifest_sha}"',
        "enum wpp_member_kind { WPP_DIRECTORY = 1, WPP_REGULAR = 2 };",
        "struct wpp_member { const char *path; uint64_t bytes; uint32_t mode; const char *sha256; unsigned kind; };",
        "struct wpp_link { const char *path; const char *target; unsigned target_kind; };",
        "static const struct wpp_member wpp_members[] = {",
    ]
    for item in members:
        if item["type"] == "symlink":
            continue
        kind = "WPP_DIRECTORY" if item["type"] == "directory" else "WPP_REGULAR"
        sha = item.get("sha256", "")
        lines.append(f'  {{"{item["path"]}", {item["bytes"]}ULL, {item["mode"]}U, "{sha}", {kind}}},')
    lines += [
        "};",
        "static const size_t wpp_member_count = sizeof(wpp_members) / sizeof(wpp_members[0]);",
        "static const struct wpp_link wpp_links[] = {",
    ]
    for link in links:
        target = link["target"]
        kind = 1 if link["target_kind"] == "relative" else 2 if target == "<imagefs_root>" else 3
        lines.append(f'  {{"{link["path"]}", "{target}", {kind}U}},')
    lines += [
        "};",
        "static const size_t wpp_link_count = sizeof(wpp_links) / sizeof(wpp_links[0]);",
        "#endif",
        "",
    ]
    path.write_text("\n".join(lines), encoding="ascii")


def build(archive: Path, compiler: Path, dxgi: Path, d3d11: Path, out: Path, header: Path) -> None:
    template = load_template()
    replacements = {item["source"]: item for item in template["replacements"]}
    verify_file(compiler, replacements["compiler"]["bytes"], replacements["compiler"]["sha256"], "compiler")
    verify_file(dxgi, replacements["dxgi"]["bytes"], replacements["dxgi"]["sha256"], "dxgi")
    verify_file(d3d11, replacements["d3d11"]["bytes"], replacements["d3d11"]["sha256"], "d3d11")
    payloads = archive_members(archive, template)
    replacement_payloads = {"compiler": compiler.read_bytes(), "dxgi": dxgi.read_bytes(), "d3d11": d3d11.read_bytes()}

    entries: list[dict[str, object]] = []
    data: dict[str, bytes] = {}
    for item in template["archive"]["members"]:
        if item["type"] == "symlink":
            continue
        record = {"path": item["path"], "type": item["type"], "bytes": item["bytes"], "mode": item["mode"]}
        if item["type"] == "regular":
            data[item["path"]] = payloads[item["path"]]  # type: ignore[index]
            record["sha256"] = item["sha256"]
        entries.append(record)
    for source, item in replacements.items():
        path = item["path"]
        if path in data:
            raise ImageError(f"replacement unexpectedly exists in archive: {path}")
        data[path] = replacement_payloads[source]
        entries.append({"path": path, "type": "regular", "bytes": item["bytes"], "mode": item["mode"], "sha256": item["sha256"]})
    entries.sort(key=lambda item: item["path"])
    if len(entries) != 574 or sum(item["type"] == "regular" for item in entries) != 411 or sum(item["type"] == "directory" for item in entries) != 163:
        raise ImageError("final image accounting mismatch")

    links = [dict(item) for item in template["reconstruction"]["links"]]
    image_manifest: dict[str, object] = {
        "schema": "wowforever.wine-prefix-image/v1",
        "source": {
            "template": TEMPLATE.name,
            "templateSha256": TEMPLATE_SHA256,
            "archive": {"name": archive.name, "bytes": ARCHIVE_SIZE, "sha256": ARCHIVE_SHA256},
            "replacements": {source: {"bytes": item["bytes"], "sha256": item["sha256"]} for source, item in replacements.items()},
        },
        "counts": {"entries": len(entries), "regular": 411, "directories": 163, "links": len(links)},
        "entries": entries,
        "links": links,
        "zip": {"name": "prefix-image.zip", "compression": "deflate", "timestamp": "1980-01-01T00:00:00Z"},
    }
    out.mkdir(parents=True, exist_ok=True)
    zip_path = out / "prefix-image.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, strict_timestamps=True) as stream:
        for item in entries:
            name = item["path"]
            info = zip_info(name, item["mode"], item["type"] == "directory")
            stream.writestr(info, b"" if item["type"] == "directory" else data[name])
    image_manifest["zip"]["bytes"] = zip_path.stat().st_size
    image_manifest["zip"]["sha256"] = digest(zip_path)
    manifest_bytes = (json.dumps(image_manifest, sort_keys=True, indent=2, separators=(",", ": ")) + "\n").encode("ascii")
    manifest_path = out / "prefix-image-manifest.json"
    manifest_path.write_bytes(manifest_bytes)
    write_header(header, image_manifest, hashlib.sha256(manifest_bytes).hexdigest())
    print(json.dumps({"zip": str(zip_path), "zipBytes": zip_path.stat().st_size, "zipSha256": digest(zip_path), "manifest": str(manifest_path), "manifestSha256": hashlib.sha256(manifest_bytes).hexdigest(), "header": str(header)}, sort_keys=True))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--compiler", type=Path, required=True)
    parser.add_argument("--dxgi", type=Path, required=True)
    parser.add_argument("--d3d11", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--header-output", type=Path, required=True)
    args = parser.parse_args()
    build(args.archive, args.compiler, args.dxgi, args.d3d11, args.output_dir, args.header_output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
