#!/usr/bin/env python3
"""Reproducibly build and statically qualify the diagnostic vtest candidate.

This is deliberately a host-only operation.  It downloads no source or
runtime package, never invokes an ELF, and never talks to Android.  The only
network access is the explicitly bounded Meson wheel acquisition performed by
the isolated uv environment.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import lzma
import os
import posixpath
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile
from collections import deque
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


REPO = Path(__file__).resolve().parents[4]
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls")
WORKSPACE = ARTIFACT_ROOT / "vtest-android-build-v6-final"
ANDROID_DEFAULT_V1_WORKSPACE = ARTIFACT_ROOT / "vtest-android-default-v1"
ANDROID_DEFAULT_WORKSPACE = ARTIFACT_ROOT / "vtest-android-default-v2"
ANDROID_NEGOTIATION_WORKSPACE = ARTIFACT_ROOT / "vtest-android-negotiation-v3"
REUSE_WORKSPACE = ARTIFACT_ROOT / "vtest-android-build-v4"
SOURCE_TAR = ARTIFACT_ROOT / "virgl-source-audit-1.3.0/official-source.tar.gz"
SOURCE_SHA256 = "56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce"
SOURCE_BYTES = 1_255_383
SOURCE_ROOT_NAME = "virglrenderer-virglrenderer-1.3.0"
PATCH = REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-diag-v1.patch"
INSTALL_PATCH = REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-install-rpath-v1.patch"
SONAME_PATCH = REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-soname-v1.patch"
SONAME_LINK_PATCH = REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-soname-link-v1.patch"
NDK_API_PATCHES = (
    (REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-ndk-api-root-v1.patch", "ndk-public-api-root"),
    (REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-ndk-api-mesa-v1.patch", "ndk-public-api-mesa-dependency"),
    (REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-ndk-api-log-v1.patch", "ndk-public-api-log-header"),
    (REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-ndk-api-log-call-v1.patch", "ndk-public-api-log-call"),
    (REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-ndk-api-property-limits-v1.patch", "ndk-public-api-property-limits"),
    (REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-ndk-api-property-call-v1.patch", "ndk-public-api-property-call"),
    (REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-ndk-api-dmabuf-v1.patch", "ndk-public-api-dmabuf-header"),
    (REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-ndk-api-dmabuf-call-v1.patch", "ndk-public-api-dmabuf-call"),
)
PATCHES = (
    (PATCH, "diagnostic"),
    (INSTALL_PATCH, "install-rpath"),
    (SONAME_PATCH, "public-soname"),
    (SONAME_LINK_PATCH, "public-soname-link"),
    *NDK_API_PATCHES,
)
RECIPE_ID = "virglrenderer-1.3.0-aarch64-android29-diagnostic-ndk-api-v1"
ANDROID_DEFAULT_PATCH = REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-android-default-v2.patch"
ANDROID_DEFAULT_RECIPE_ID = "virglrenderer-1.3.0-aarch64-android29-android-default-v2"
ANDROID_DEFAULT_PATCHES = (*PATCHES, (ANDROID_DEFAULT_PATCH, "android-default"))
ANDROID_NEGOTIATION_PATCH = REPO / "scripts/arm64-prototype/controls/virgl-build/patches/virglrenderer-1.3.0-wfvtest-negotiation-v1.patch"
ANDROID_NEGOTIATION_RECIPE_ID = "virglrenderer-1.3.0-aarch64-android29-android-negotiation-v1"
ANDROID_NEGOTIATION_PATCHES = (*ANDROID_DEFAULT_PATCHES, (ANDROID_NEGOTIATION_PATCH, "negotiation-trace"))
MESON_VERSION = "1.5.2"
MESON_WHEEL_FILENAME = "meson-1.5.2-py3-none-any.whl"
MESON_WHEEL_URL = "https://files.pythonhosted.org/packages/55/a6/47b9353c331318a13eb050887eacfd61eb075746285f9baf7ef7de6ae235/meson-1.5.2-py3-none-any.whl"
MESON_WHEEL_BYTES = 968_744
MESON_WHEEL_SHA256 = "77706e2368a00d789c097632ccf4fc39251fba56d03e1e1b262559a3c7a08f5b"
MESON_WHEEL_CACHE = ARTIFACT_ROOT / "vtest-android-build-v3/tools/download" / MESON_WHEEL_FILENAME
MAX_SOURCE_EXPANDED = 64 * 1024 * 1024
MAX_SOURCE_MEMBERS = 4096
MAX_WORKSPACE_BYTES = 512 * 1024 * 1024
MAX_TOOL_DOWNLOAD_BYTES = 10 * 1024 * 1024
NDK = Path("/home/adrian/android-sdk/ndk/24.0.8215888")
LLVM_BIN = NDK / "toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG = LLVM_BIN / "aarch64-linux-android29-clang"
CLANGXX = LLVM_BIN / "aarch64-linux-android29-clang++"
LLVM_AR = LLVM_BIN / "llvm-ar"
LLVM_STRIP = LLVM_BIN / "llvm-strip"
NINJA = Path("/home/adrian/android-sdk/cmake/3.22.1/bin/ninja")
UV = Path("/home/adrian/.local/bin/uv")
PATCH_TOOL = Path("/usr/bin/patch")
PATCH_TOOL_BYTES = 166_712
PATCH_TOOL_SHA256 = "44ecd416d1958be9c2b60488fa893e76aae8d0f81d2fedb9e83eb661da0ee18c"
OLD_CANDIDATE = ARTIFACT_ROOT / "virgl-audit/server_candidate-v2"
OLD_CANDIDATE_MANIFEST_SHA256 = "5f40682f034050d5e72d98199b41a5da9444aa2770ee5732bb4a077de7e17174"
MESA_DEB = ARTIFACT_ROOT / "virgl-audit/mesa_26.2.4_aarch64.deb"
EPOXY_DEB = ARTIFACT_ROOT / "virgl-audit/vtest-server-audit/libepoxy_1.5.10-3_aarch64.deb"
LIBDRM_DEB = ARTIFACT_ROOT / "virgl-audit/libdrm_2.4.134_aarch64.deb"
LIBX11_DEB = ARTIFACT_ROOT / "virgl-audit/libx11_1.8.13-1_aarch64.deb"
XORGPROTO_DEB = ARTIFACT_ROOT / "virgl-audit/xorgproto_2025.1_all.deb"
DEB_PINS = {
    EPOXY_DEB: (300_476, "89cec877ca34febc20dd62eaa07e95871c3c005e006fcc9fc155e09b4f61449b"),
    LIBDRM_DEB: (180_604, "0cbe82140cf325c15473fbf53a3711fc7a2fb88e36a3eea58a3267c461ab4a17"),
    MESA_DEB: (3_146_408, "d072b0144a9a6f4f80bd08797893aa37d4b75209aae810f2e84843a8fa23342e"),
    LIBX11_DEB: (1_909_956, "a18c5cf3cfe2670bf1cb4243e3140a0b7639350a4175b5229c969402fdb12e9f"),
    XORGPROTO_DEB: (249_676, "cd2a6405a79540f6cc0dbc991fffdbe68058f3223ed456ac66c07207048ef62f"),
}


@dataclass(frozen=True)
class BuildProfile:
    name: str
    workspace: Path
    patches: tuple[tuple[Path, str], ...]
    recipe_id: str
    candidate_name: str
    functional_egl_fix: bool
    compatibility_defines: tuple[str, ...]


PROFILES = {
    "diagnostic": BuildProfile(
        name="diagnostic",
        workspace=WORKSPACE,
        patches=PATCHES,
        recipe_id=RECIPE_ID,
        candidate_name="candidate_diagnostic_verified",
        functional_egl_fix=False,
        compatibility_defines=("WFVTEST_DIAG=1",),
    ),
    "android-default": BuildProfile(
        name="android-default",
        workspace=ANDROID_DEFAULT_WORKSPACE,
        patches=ANDROID_DEFAULT_PATCHES,
        recipe_id=ANDROID_DEFAULT_RECIPE_ID,
        candidate_name="candidate_android_default_v2_verified",
        functional_egl_fix=True,
        compatibility_defines=("WFVTEST_ANDROID_DEFAULT=1",),
    ),
    "android-negotiation": BuildProfile(
        name="android-negotiation",
        workspace=ANDROID_NEGOTIATION_WORKSPACE,
        patches=ANDROID_NEGOTIATION_PATCHES,
        recipe_id=ANDROID_NEGOTIATION_RECIPE_ID,
        candidate_name="candidate_android_negotiation_verified",
        functional_egl_fix=True,
        compatibility_defines=("WFVTEST_ANDROID_DEFAULT=1", "WFVTEST_NEGOTIATION_TRACE=1"),
    ),
}
ANDROID_ALLOWLIST = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libEGL.so", "libGLESv2.so"}
DEVICE_ALIASES = [
    {"name": "libEGL.so.1", "target": "/system/lib64/libEGL.so"},
    {"name": "libGLESv2.so.2", "target": "/system/lib64/libGLESv2.so"},
]
EXPECTED_CANDIDATE_DESTINATIONS = {
    "bin/virgl_test_server",
    "lib/libX11.so",
    "lib/libXau.so",
    "lib/libXdmcp.so",
    "lib/libandroid-support.so",
    "lib/libdrm.so",
    "lib/libepoxy.so",
    "lib/libgbm.so",
    "lib/libvirglrenderer.so",
    "lib/libxcb.so",
}
EXPECTED_NEEDED = {
    "bin/virgl_test_server": ["libvirglrenderer.so.1", "libc.so"],
    "lib/libvirglrenderer.so": ["libm.so", "liblog.so", "libepoxy.so", "libdrm.so", "libgbm.so", "libc.so"],
}
EXPECTED_RUNPATHS = {
    "bin/virgl_test_server": ["$ORIGIN/../lib"],
    "lib/libvirglrenderer.so": ["$ORIGIN"],
}
COMPILER_ENV_KEYS = {
    "CC", "CXX", "AR", "LD", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS",
    "LIBRARY_PATH", "CPATH", "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH", "PYTHONPATH",
}

GBM_HEADER_URL = "https://gitlab.freedesktop.org/mesa/mesa/-/raw/mesa-26.2.4/src/gbm/main/gbm.h?inline=false"
GBM_HEADER_BYTES = 19_165
GBM_HEADER_SHA256 = "95f3b4a6ee5175c7cc5d47368d4efb100063fe49e5a6f5b19030ac2ceed73b81"
X11_HEADERS = {
    "libx11": {"data/data/com.termux/files/usr/include/X11/" + name for name in (
        "ImUtil.h", "XKBlib.h", "Xcms.h", "Xlib-xcb.h", "Xlib.h", "XlibConf.h",
        "Xlibint.h", "Xlocale.h", "Xregion.h", "Xresource.h", "Xutil.h", "cursorfont.h",
    )},
    "xorgproto": {"data/data/com.termux/files/usr/include/X11/" + name for name in (
        "DECkeysym.h", "HPkeysym.h", "Sunkeysym.h", "X.h", "XF86keysym.h", "XWDFile.h",
        "Xalloca.h", "Xarch.h", "Xatom.h", "Xdefs.h", "Xfuncproto.h", "Xfuncs.h", "Xmd.h",
        "Xos.h", "Xos_r.h", "Xosdefs.h", "Xpoll.h", "Xproto.h", "Xprotostr.h", "Xthreads.h",
        "Xw32defs.h", "Xwindows.h", "Xwinsock.h", "ap_keysym.h", "keysym.h", "keysymdef.h",
    )},
}


class BuildError(RuntimeError):
    pass


def _profile(name: str = "diagnostic") -> BuildProfile:
    try:
        return PROFILES[name]
    except KeyError as exc:
        raise BuildError(f"unknown build profile: {name}") from exc


def _compile_args(profile: BuildProfile, deps: Path) -> list[str]:
    return [f"-I{deps / 'include'}", *[f"-D{define}" for define in profile.compatibility_defines]]


def _sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _patch_records(profile: str = "diagnostic") -> list[dict[str, str]]:
    selected = _profile(profile)
    return [
        {"id": name, "path": str(path), "sha256": _sha(path)}
        for path, name in selected.patches
    ]


def _regular(path: Path, limit: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise BuildError(f"not a regular file: {path}")
    if path.stat().st_size > limit:
        raise BuildError(f"file exceeds bound: {path}")
    return path.read_bytes()


def _download_pinned_header(workspace: Path) -> dict[str, Any]:
    target = workspace / "deps/include/gbm.h"
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if target.is_file() and not target.is_symlink():
        data = target.read_bytes()
        if len(data) == GBM_HEADER_BYTES and hashlib.sha256(data).hexdigest() == GBM_HEADER_SHA256:
            return {"path": "include/gbm.h", "url": GBM_HEADER_URL, "version": "mesa-26.2.4", "bytes": len(data), "sha256": GBM_HEADER_SHA256, "downloadBytes": 0, "reused": True}
        raise BuildError(f"existing gbm.h is not the pinned Mesa 26.2.4 header: {target}")
    cached = REUSE_WORKSPACE / "deps/include/gbm.h"
    if cached.is_file() and not cached.is_symlink():
        data = cached.read_bytes()
        if len(data) == GBM_HEADER_BYTES and hashlib.sha256(data).hexdigest() == GBM_HEADER_SHA256:
            target.write_bytes(data)
            return {"path": "include/gbm.h", "url": GBM_HEADER_URL, "version": "mesa-26.2.4", "bytes": len(data), "sha256": GBM_HEADER_SHA256, "downloadBytes": 0, "reused": True, "cachePath": str(cached)}
    raise BuildError(f"pinned Mesa gbm.h cache missing or mismatched: {cached}")


def _safe_rel(name: str) -> str:
    while name.startswith("./"):
        name = name[2:]
    if not name or "\\" in name or "\x00" in name or name.startswith("/"):
        raise BuildError(f"unsafe archive path: {name!r}")
    parts = name.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise BuildError(f"unsafe archive path: {name!r}")
    return "/".join(parts)


def _deb_data(path: Path) -> tuple[bytes, dict[str, bytes]]:
    expected = DEB_PINS[path]
    raw = _regular(path, 64 * 1024 * 1024)
    if (len(raw), hashlib.sha256(raw).hexdigest()) != expected:
        raise BuildError(f"pinned package mismatch: {path}")
    if raw[:8] != b"!<arch>\n":
        raise BuildError(f"not an ar package: {path}")
    members: dict[str, bytes] = {}
    offset = 8
    while offset < len(raw):
        if offset + 60 > len(raw) or raw[offset + 58 : offset + 60] != b"`\n":
            raise BuildError("invalid ar member")
        name = raw[offset : offset + 16].decode("ascii").strip().rstrip("/")
        size = int(raw[offset + 48 : offset + 58].decode("ascii").strip())
        end = offset + 60 + size
        if not name or end > len(raw) or name in members:
            raise BuildError("invalid or duplicate ar member")
        members[name] = raw[offset + 60 : end]
        offset = end + (size & 1)
    data = members.get("data.tar.xz")
    if data is None:
        raise BuildError(f"package has no data.tar.xz: {path}")
    return raw, members


def _walk_data(path: Path) -> Iterable[tuple[str, tarfile.TarInfo, bytes | None]]:
    _raw, members = _deb_data(path)
    expanded = 0
    with lzma.LZMAFile(io.BytesIO(members["data.tar.xz"])) as compressed:
        with tarfile.open(fileobj=compressed, mode="r|") as archive:
            for index, member in enumerate(archive):
                if index >= 4096:
                    raise BuildError(f"package member bound exceeded: {path}")
                if member.isdir() and member.name in {".", "./"}:
                    continue
                name = _safe_rel(member.name)
                if member.isdir():
                    yield name, member, None
                elif member.isfile():
                    if member.size > 64 * 1024 * 1024:
                        raise BuildError(f"package member bound exceeded: {name}")
                    stream = archive.extractfile(member)
                    if stream is None:
                        raise BuildError(f"cannot read package member: {name}")
                    value = stream.read(member.size + 1)
                    if len(value) != member.size:
                        raise BuildError(f"truncated package member: {name}")
                    expanded += len(value)
                    if expanded > 256 * 1024 * 1024:
                        raise BuildError(f"package expanded bound exceeded: {path}")
                    yield name, member, value
                elif member.issym():
                    if not member.linkname or member.linkname.startswith("/") or "\\" in member.linkname:
                        raise BuildError(f"unsafe package symlink: {name}")
                    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname))
                    if resolved == "." or resolved.startswith("../") or "/../" in resolved:
                        raise BuildError(f"escaping package symlink: {name}")
                    yield name, member, None
                else:
                    raise BuildError(f"unsupported package member: {name}")


def _extract_selected_package(path: Path, selected: set[str], destination: Path, *, allow_existing: bool = False) -> list[dict[str, Any]]:
    files: list[dict[str, Any]] = []
    prefix = "data/data/com.termux/files/usr/"
    for name, member, value in _walk_data(path):
        if value is None or name not in selected:
            continue
        if not name.startswith(prefix):
            raise BuildError(f"selected package path outside usr prefix: {name}")
        relative = name[len(prefix) :]
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() or target.is_symlink():
            if not allow_existing or target.is_symlink() or not target.is_file() or target.read_bytes() != value:
                raise BuildError(f"existing extracted dependency differs: {target}")
        else:
            target.write_bytes(value)
        files.append({"path": relative, "bytes": len(value), "sha256": hashlib.sha256(value).hexdigest(), "packagePath": name})
    missing = selected - {item["packagePath"] for item in files}
    if missing:
        raise BuildError(f"missing selected package members: {sorted(missing)}")
    return files


def _extract_source(workspace: Path) -> Path:
    source = workspace / "source"
    if source.exists() or source.is_symlink():
        raise BuildError(f"fresh build refuses to reuse source cache: {source}")
    raw = _regular(SOURCE_TAR, 8 * 1024 * 1024)
    if len(raw) != SOURCE_BYTES or hashlib.sha256(raw).hexdigest() != SOURCE_SHA256:
        raise BuildError("official source archive pin mismatch")
    source.mkdir(mode=0o700, exist_ok=True)
    expanded = 0
    seen: set[str] = set()
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
        for index, member in enumerate(archive):
            if index >= MAX_SOURCE_MEMBERS:
                raise BuildError("source member bound exceeded")
            name = _safe_rel(member.name)
            if name in seen:
                raise BuildError(f"duplicate source path: {name}")
            seen.add(name)
            if not name.startswith(SOURCE_ROOT_NAME + "/") and name != SOURCE_ROOT_NAME:
                raise BuildError(f"source has unexpected root: {name}")
            target = source / name
            if member.isdir():
                target.mkdir(mode=0o700, parents=True, exist_ok=True)
            elif member.isfile():
                expanded += member.size
                if expanded > MAX_SOURCE_EXPANDED:
                    raise BuildError("source expanded bound exceeded")
                stream = archive.extractfile(member)
                if stream is None:
                    raise BuildError(f"cannot extract source member: {name}")
                value = stream.read(member.size)
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                if target.exists() or target.is_symlink():
                    raise BuildError(f"unexpected pre-existing source member: {name}")
                target.write_bytes(value)
            elif member.issym():
                normalized = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname))
                if normalized == "." or normalized.startswith("../") or "/../" in normalized or not normalized.startswith(SOURCE_ROOT_NAME + "/"):
                    raise BuildError(f"unsafe source symlink: {name} -> {member.linkname}")
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                if target.exists() or target.is_symlink():
                    raise BuildError(f"unexpected pre-existing source link: {name}")
                target.symlink_to(member.linkname)
            else:
                raise BuildError(f"unsupported source member: {name}")
    return source / SOURCE_ROOT_NAME


def _run(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None, log: Path) -> dict[str, Any]:
    started = time.monotonic()
    completed = subprocess.run(command, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("a", encoding="utf-8") as stream:
        stream.write("$ " + " ".join(command) + "\n")
        stream.write(completed.stdout)
        stream.write(f"[exit={completed.returncode}]\n")
    result = {"command": command, "cwd": str(cwd) if cwd else None, "exit": completed.returncode, "seconds": round(time.monotonic() - started, 3)}
    if completed.returncode:
        raise BuildError(f"command failed ({completed.returncode}): {' '.join(command)}; see {log}")
    return result


def _tree_manifest(root: Path) -> dict[str, Any]:
    entries: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if relative.startswith(".diagnostic-patch") or relative in {".source-marker.json", "source-tree-manifest.json"}:
            continue
        if path.is_symlink():
            entries.append({"path": relative, "kind": "symlink", "target": os.readlink(path)})
        elif path.is_file():
            data = path.read_bytes()
            entries.append({"path": relative, "kind": "file", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        elif not path.is_dir():
            raise BuildError(f"unsupported source tree entry: {path}")
    encoded = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {"entries": entries, "count": len(entries), "sha256": hashlib.sha256(encoded).hexdigest()}


def _ensure_tools(workspace: Path, log: Path) -> tuple[Path, dict[str, Any], list[dict[str, Any]]]:
    for path in (UV, CLANG, CLANGXX, LLVM_AR, LLVM_STRIP, NINJA):
        if not path.is_file() or path.is_symlink() and not path.exists():
            raise BuildError(f"required pinned tool missing: {path}")
    tools = workspace / "tools"
    if tools.exists() or tools.is_symlink():
        raise BuildError(f"fresh build refuses to reuse tool cache: {tools}")
    # Reuse only immutable wheel bytes verified against the source-level pin,
    # never an installed executable or metadata from a previous workspace.
    wheel_bytes = _regular(MESON_WHEEL_CACHE, MESON_WHEEL_BYTES)
    if len(wheel_bytes) != MESON_WHEEL_BYTES or hashlib.sha256(wheel_bytes).hexdigest() != MESON_WHEEL_SHA256:
        raise BuildError("cached Meson wheel does not match the independent pin")
    download = tools / "download"
    download.mkdir(mode=0o700, parents=True)
    wheel = download / MESON_WHEEL_FILENAME
    with wheel.open("xb") as stream:
        stream.write(wheel_bytes)
    venv = tools / "venv"
    meson = venv / "bin/meson"
    env = _build_environment(workspace, workspace / "deps", meson)
    commands = [
        _run([str(UV), "venv", "--python", "/usr/bin/python3", str(venv)], env=env, log=log),
        _run([str(UV), "pip", "install", "--offline", "--no-deps", "--no-cache", "--python", str(venv / "bin/python"), str(wheel)], env=env, log=log),
    ]
    site_roots = list((venv / "lib").glob("python*/site-packages"))
    if len(site_roots) != 1 or not meson.is_file():
        raise BuildError("fresh isolated Meson installation is missing or ambiguous")
    module_count = 0
    with zipfile.ZipFile(wheel) as archive:
        for name in archive.namelist():
            if not name.startswith("mesonbuild/") or name.endswith("/"):
                continue
            relative = _safe_rel(name)
            actual = _regular(site_roots[0] / relative, 2 * 1024 * 1024)
            if actual != archive.read(name):
                raise BuildError(f"installed Meson module differs from the pinned wheel: {name}")
            module_count += 1
    if not module_count:
        raise BuildError("pinned Meson wheel has no verified modules")
    tool = {"name": "meson", "version": MESON_VERSION, "origin": MESON_WHEEL_URL, "wheel": str(wheel), "wheelBytes": MESON_WHEEL_BYTES, "wheelSha256": MESON_WHEEL_SHA256, "downloadBytes": 0, "cacheInput": str(MESON_WHEEL_CACHE), "installedInFreshWorkspace": True, "verifiedWheelModuleCount": module_count, "executableSha256": _sha(meson)}
    return meson, tool, commands


def _prepare_dependencies(workspace: Path) -> tuple[Path, dict[str, Any]]:
    deps = workspace / "deps"
    marker = deps / ".deps-marker.json"
    if deps.exists() or deps.is_symlink():
        raise BuildError(f"fresh build refuses to reuse dependency cache: {deps}")
    include = deps / "include"
    lib = deps / "lib"
    pcdir = lib / "pkgconfig"
    include.mkdir(mode=0o700, parents=True, exist_ok=True)
    lib.mkdir(mode=0o700, exist_ok=True)
    pcdir.mkdir(mode=0o700, exist_ok=True)
    selected = {
        EPOXY_DEB: {"data/data/com.termux/files/usr/include/epoxy/egl.h", "data/data/com.termux/files/usr/include/epoxy/egl_generated.h", "data/data/com.termux/files/usr/include/epoxy/common.h", "data/data/com.termux/files/usr/include/epoxy/gl.h", "data/data/com.termux/files/usr/include/epoxy/gl_generated.h", "data/data/com.termux/files/usr/include/epoxy/glx.h", "data/data/com.termux/files/usr/include/epoxy/glx_generated.h", "data/data/com.termux/files/usr/lib/libepoxy.so"},
        LIBDRM_DEB: {"data/data/com.termux/files/usr/include/xf86drm.h", "data/data/com.termux/files/usr/include/xf86drmMode.h", "data/data/com.termux/files/usr/include/libsync.h", *{"data/data/com.termux/files/usr/include/libdrm/" + name for name in ("drm.h", "drm_fourcc.h", "drm_mode.h", "drm_sarea.h", "virtgpu_drm.h")}, "data/data/com.termux/files/usr/lib/libdrm.so"},
        MESA_DEB: {"data/data/com.termux/files/usr/include/gbm_backend_abi.h", "data/data/com.termux/files/usr/lib/libgbm.so"},
        LIBX11_DEB: X11_HEADERS["libx11"],
        XORGPROTO_DEB: X11_HEADERS["xorgproto"],
    }
    extracted: list[dict[str, Any]] = []
    for package, paths in selected.items():
        extracted.extend(_extract_selected_package(package, paths, deps))
    gbm_record = _download_pinned_header(workspace)
    normalized_pc = {
        "epoxy.pc": """prefix=${pcfiledir}/../..\nincludedir=${prefix}/include\nlibdir=${prefix}/lib\nepoxy_has_glx=1\nepoxy_has_egl=1\nepoxy_has_wgl=0\nName: epoxy\nDescription: audited libepoxy dispatch library\nVersion: 1.5.10\nLibs: -L${libdir} -lepoxy\nCflags: -I${includedir}\n""",
        "libdrm.pc": """prefix=${pcfiledir}/../..\nincludedir=${prefix}/include\nlibdir=${prefix}/lib\nName: libdrm\nDescription: audited libdrm headers and library\nVersion: 2.4.134\nLibs: -L${libdir} -ldrm\nCflags: -I${includedir} -I${includedir}/libdrm\n""",
        "gbm.pc": """prefix=${pcfiledir}/../..\nincludedir=${prefix}/include\nlibdir=${prefix}/lib\nName: gbm\nDescription: audited Mesa GBM runtime with generated compile API header\nVersion: 26.2.4\nLibs: -L${libdir} -lgbm\nCflags: -I${includedir}\n""",
    }
    for name, text in normalized_pc.items():
        pc = pcdir / name
        if pc.exists() or pc.is_symlink():
            if pc.is_symlink() or pc.read_text(encoding="utf-8") != text:
                raise BuildError(f"existing generated pc differs: {pc}")
        else:
            pc.write_text(text, encoding="utf-8")
    record = {"packages": [{"path": str(path), "bytes": size, "sha256": digest} for path, (size, digest) in DEB_PINS.items()], "extracted": extracted, "gbmHeader": gbm_record, "x11Headers": [{"path": str(path.relative_to(include)), "sha256": _sha(path), "bytes": path.stat().st_size} for package_paths in X11_HEADERS.values() for path in (include / Path(package_path).relative_to("data/data/com.termux/files/usr/include") for package_path in package_paths)], "pcFiles": [{"path": f"lib/pkgconfig/{name}", "sha256": _sha(pcdir / name), "bytes": (pcdir / name).stat().st_size} for name in sorted(normalized_pc)], "tree": _tree_manifest(deps)}
    marker.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return deps, record


def _apply_patch(source: Path, log: Path, profile: str = "diagnostic") -> dict[str, Any]:
    marker = source / ".diagnostic-patch.json"
    selected = _profile(profile)
    patches = selected.patches
    patch_records = _patch_records(selected.name)
    tool = _regular(PATCH_TOOL, PATCH_TOOL_BYTES + 1)
    if len(tool) != PATCH_TOOL_BYTES or hashlib.sha256(tool).hexdigest() != PATCH_TOOL_SHA256:
        raise BuildError("patch executable does not match the fixed host-tool pin")
    patch_env = {"PATH": "/usr/bin:/bin", "HOME": str(source.parent),
                 "TMPDIR": str(source.parent), "LC_ALL": "C", "LANG": "C", "TZ": "UTC"}
    results = []
    for path, _name in patches:
        command = [str(PATCH_TOOL), "--batch", "--forward"]
        if selected.functional_egl_fix:
            command.append("--no-backup-if-mismatch")
        command.extend(["--fuzz=0", "-p1", "-i", str(path)])
        results.append(_run(command, cwd=source, env=patch_env, log=log))
    unexpected = [path for path in source.rglob("*") if path.is_file() and path.suffix in {".rej", ".orig"}]
    if unexpected:
        raise BuildError(f"patch left reject/original artifacts: {unexpected}")
    tree = _tree_manifest(source)
    (source / "source-tree-manifest.json").write_text(json.dumps(tree, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    record = {"recipeId": selected.recipe_id, "profile": selected.name, "patches": patch_records, "declaredPatchIds": [item["id"] for item in patch_records], "commands": [result["command"] for result in results], "applied": True, "fuzz": 0, "sourceTree": tree,
              "patchTool": {"path": str(PATCH_TOOL), "bytes": PATCH_TOOL_BYTES,
                            "sha256": PATCH_TOOL_SHA256, "environment": patch_env}}
    marker.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return record


def _cross_file(workspace: Path, deps: Path, profile: str = "diagnostic") -> Path:
    selected = _profile(profile)
    path = workspace / "cross-file.ini"
    pc = REPO / "scripts/arm64-prototype/controls/virgl-build/pkg_config.py"
    contents = (
        """[binaries]\n"""
        f"c = '{CLANG}'\ncpp = '{CLANGXX}'\nar = '{LLVM_AR}'\nstrip = '{LLVM_STRIP}'\n"
        f"pkgconfig = '{pc}'\n\n[properties]\nsys_root = '{NDK / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot'}'\n\n[built-in options]\nc_args = [{','.join(repr(arg) for arg in _compile_args(selected, deps))}]\n\n[host_machine]\nsystem = 'android'\ncpu_family = 'aarch64'\ncpu = 'aarch64'\nendian = 'little'\n"""
    )
    if path.exists():
        existing = path.read_text(encoding="utf-8")
        if "aarch64-linux-android29-clang" not in existing:
            raise BuildError(f"refusing to overwrite unknown cross file: {path}")
        if existing != contents:
            path.write_text(contents, encoding="utf-8")
    else:
        path.write_text(contents, encoding="utf-8")
    return path


def _build_environment(workspace: Path, deps: Path, meson: Path) -> dict[str, str]:
    home = workspace / "home"
    tmp = workspace / "tmp"
    home.mkdir(mode=0o700, exist_ok=True)
    tmp.mkdir(mode=0o700, exist_ok=True)
    env = {
        "PATH": os.pathsep.join((str(NINJA.parent), "/usr/bin", str(meson.parent))),
        "HOME": str(home),
        "TMPDIR": str(tmp),
        "LC_ALL": "C",
        "LANG": "C",
        "TZ": "UTC",
        "SOURCE_DATE_EPOCH": "0",
        "PKG_CONFIG_PATH": str(deps / "lib/pkgconfig"),
        "PKG_CONFIG_LIBDIR": str(deps / "lib/pkgconfig"),
        "PKG_CONFIG_SYSROOT_DIR": "",
        "MESON_PACKAGE_CACHE_DIR": str(workspace / "empty-wrap-cache"),
    }
    return env


def _compiler_macro_record(workspace: Path, source: Path, deps: Path, env: dict[str, str], profile: str = "diagnostic") -> dict[str, Any]:
    selected = _profile(profile)
    c_args = _compile_args(selected, deps)
    predefine = subprocess.run([str(CLANG), *c_args, "-dM", "-E", "-x", "c", "/dev/null"], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    if predefine.returncode:
        raise BuildError(f"NDK compiler predefine probe failed: {predefine.stdout}")
    values = _validate_compiler_predefines(predefine.stdout)
    predefine_path = workspace / "logs/compiler-predefines.txt"
    predefine_path.parent.mkdir(mode=0o700, exist_ok=True)
    predefine_path.write_text(predefine.stdout, encoding="utf-8")
    probe = workspace / "detect-os-probe.c"
    probe.write_text('#include "detect_os.h"\n', encoding="utf-8")
    detect = subprocess.run([str(CLANG), *c_args, f"-I{source / 'src/mesa/util'}", "-dM", "-E", "-x", "c", str(probe)], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    if detect.returncode:
        raise BuildError(f"Mesa detect_os.h probe failed: {detect.stdout}")
    detect_values = _validate_detect_os_predefines(detect.stdout)
    detect_path = workspace / "logs/detect-os-predefines.txt"
    detect_path.write_text(detect.stdout, encoding="utf-8")
    return {
        "compiler": {"command": [str(CLANG), *c_args, "-dM", "-E", "-x", "c", "/dev/null"], "defines": values, "path": str(predefine_path), "bytes": predefine_path.stat().st_size, "sha256": _sha(predefine_path)},
        "mesaDetectOs": {"command": [str(CLANG), *c_args, f"-I{source / 'src/mesa/util'}", "-dM", "-E", "-x", "c", str(probe)], "defines": detect_values, "path": str(detect_path), "bytes": detect_path.stat().st_size, "sha256": _sha(detect_path)},
    }


def _validate_compiler_predefines(text: str) -> dict[str, str]:
    expected = {"__ANDROID__": "1", "__ANDROID_API__": "29", "__aarch64__": "1", "__LP64__": "1", "__SIZEOF_POINTER__": "8"}
    values = dict(re.findall(r"^#define (__(?:ANDROID__|ANDROID_API__|ANDROID_MIN_SDK_VERSION__|aarch64__|LP64__|SIZEOF_POINTER__))\s+(\S+)$", text, re.MULTILINE))
    raw_api = values.get("__ANDROID_API__")
    if raw_api == "__ANDROID_MIN_SDK_VERSION__":
        values["__ANDROID_API__"] = values.get("__ANDROID_MIN_SDK_VERSION__", "")
    if any(values.get(name) != value for name, value in expected.items()):
        raise BuildError(f"NDK compiler predefined macro mismatch: expected {expected}, got {values}")
    return values


def _validate_detect_os_predefines(text: str) -> dict[str, str]:
    values = dict(re.findall(r"^#define (DETECT_OS_(?:ANDROID|LINUX|UNIX))\s+(\S+)$", text, re.MULTILINE))
    if any(values.get(name) != "1" for name in ("DETECT_OS_ANDROID", "DETECT_OS_LINUX", "DETECT_OS_UNIX")):
        raise BuildError(f"Mesa Android detection mismatch: {values}")
    return values


def _meson_options() -> dict[str, Any]:
    return {
        "platforms": ["egl"], "minigbm_allocation": False, "venus": False, "vulkan-dload": True, "vulkan-preload": False,
        "check-gl-errors": True, "drm-renderers": [], "render-server-worker": "process", "video": False, "tests": False,
        "fuzzer": False, "valgrind": False, "tracing": "none", "unstable-apis": False,
    }


def _recipe_fingerprint(deps: Path, profile: str = "diagnostic") -> str:
    selected = _profile(profile)
    payload = {
        "recipeId": selected.recipe_id,
        "sourceSha256": SOURCE_SHA256,
        "patches": [{"path": item["path"], "sha256": item["sha256"]} for item in _patch_records(selected.name)],
        "mesonOptions": _meson_options(),
        "cArgs": _compile_args(selected, deps),
        "installRpaths": {"library": "$ORIGIN", "executable": "$ORIGIN/../lib"},
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()


def _configure_and_build(workspace: Path, source: Path, deps: Path, meson: Path, log: Path, profile: str = "diagnostic") -> list[dict[str, Any]]:
    selected = _profile(profile)
    build = workspace / f"build-{selected.name}"
    install_root = workspace / "install-destdir"
    recipe_marker = build / ".recipe-marker.json"
    fingerprint = _recipe_fingerprint(deps, selected.name)
    recipe_changed = False
    if build.exists() and recipe_marker.is_file():
        record = json.loads(recipe_marker.read_text(encoding="utf-8"))
        if record.get("recipeFingerprint") != fingerprint:
            if not (build / "build.ninja").is_file():
                raise BuildError(f"build directory inputs changed: {build}")
            recipe_changed = True
    elif build.exists() and any(build.iterdir()):
        if not ((build / "meson-private/coredata.dat").is_file() or (build / "meson-private/meson.lock").is_file() and (build / "config.h").is_file()):
            raise BuildError(f"refusing to reuse build directory without recipe marker: {build}")
    if build.exists() and not (build / "build.ninja").is_file() and not (build / "meson-private/coredata.dat").is_file() and not (build / "config.h").is_file():
        raise BuildError(f"refusing unknown build directory: {build}")
    cross = _cross_file(workspace, deps, selected.name)
    pcdir = deps / "lib/pkgconfig"
    env = _build_environment(workspace, deps, meson)
    if COMPILER_ENV_KEYS.intersection(env):
        raise BuildError("build environment unexpectedly contains ambient compiler or wrapper variables")
    (workspace / "empty-wrap-cache").mkdir(mode=0o700, exist_ok=True)
    options = ["-Dplatforms=egl", "-Dminigbm_allocation=false", "-Dvenus=false", "-Dvulkan-dload=true", "-Dvulkan-preload=false", "-Dcheck-gl-errors=true", "-Ddrm-renderers=", "-Drender-server-worker=process", "-Dvideo=false", "-Dtests=false", "-Dfuzzer=false", "-Dvalgrind=false", "-Dtracing=none", "-Dunstable-apis=false"]
    commands: list[dict[str, Any]] = []
    c_args_option = "-Dc_args=[" + ",".join(repr(arg) for arg in _compile_args(selected, deps)) + "]"
    setup_command = [str(meson), "setup", str(build), str(source), "--cross-file", str(cross), "--prefix=/wfvtest-private", "--libdir=lib", "--bindir=bin", "--buildtype=release", "--wrap-mode=nodownload", c_args_option, *options]
    if (build / "build.ninja").is_file():
        setup_command = [str(meson), "setup", str(build), str(source), "--reconfigure", c_args_option] if recipe_changed else [str(meson), "configure", str(build), c_args_option]
    commands.append(_run(setup_command, env=env, log=log))
    recipe_marker.write_text(json.dumps({"recipeId": selected.recipe_id, "profile": selected.name, "recipeFingerprint": fingerprint, "compileArgs": _compile_args(selected, deps), "configHeader": str(build / "config.h")}, sort_keys=True) + "\n", encoding="utf-8")
    commands.append(_run([str(meson), "compile", "-C", str(build), "-j", "2"], env=env, log=log))
    install_root.mkdir(mode=0o700, exist_ok=True)
    commands.append(_run([str(meson), "install", "-C", str(build), "--destdir", str(install_root), "--no-rebuild"], env=env, log=log))
    return commands


def _find_built(build: Path, name: str) -> Path:
    candidates = [path for path in build.rglob(name) if path.is_file() and not path.is_symlink()]
    if len(candidates) != 1:
        raise BuildError(f"expected exactly one built {name}, got {candidates}")
    return candidates[0]


def _find_installed(workspace: Path, name: str, *, versioned: bool = False) -> Path:
    root = workspace / "install-destdir/wfvtest-private"
    pattern = f"{name}.*" if versioned else name
    candidates = [path for path in root.rglob(pattern) if path.is_file() and not path.is_symlink()]
    if versioned and not candidates:
        unversioned = root / "lib" / name
        if unversioned.is_file() and not unversioned.is_symlink():
            candidates = [unversioned]
    if len(candidates) != 1:
        raise BuildError(f"expected exactly one installed {name}, got {candidates}")
    return candidates[0]


def _elf_details(path: Path) -> dict[str, Any]:
    sys.path.insert(0, str(REPO / "scripts/arm64-prototype/controls/virgl"))
    import audit_server  # type: ignore

    return audit_server._elf_details(_regular(path, 192 * 1024 * 1024))


def _copy_candidate_files(workspace: Path, server: Path, library: Path, profile: str = "diagnostic") -> tuple[Path, list[dict[str, Any]]]:
    selected = _profile(profile)
    candidate = workspace / selected.candidate_name
    if candidate.exists():
        raise BuildError(f"refusing to overwrite candidate output: {candidate}")
    candidate.mkdir(mode=0o700)
    (candidate / "bin").mkdir(mode=0o700)
    (candidate / "lib").mkdir(mode=0o700)
    old_manifest_path = OLD_CANDIDATE / "candidate-manifest.json"
    if _sha(old_manifest_path) != OLD_CANDIDATE_MANIFEST_SHA256:
        raise BuildError("old provider manifest SHA-256 does not match the pinned authority")
    old_manifest = json.loads(_regular(old_manifest_path, 16 * 1024 * 1024))
    old_items = old_manifest.get("materializedFiles")
    if not isinstance(old_items, list):
        raise BuildError("old candidate has no materializedFiles")
    replace = {"bin/virgl_test_server", "lib/libvirglrenderer.so"}
    items: list[dict[str, Any]] = []
    for old in old_items:
        destination = old.get("destination")
        if destination in replace:
            continue
        if not isinstance(destination, str) or destination.startswith("/") or ".." in PurePosixPath(destination).parts:
            raise BuildError(f"unsafe old candidate destination: {destination}")
        source = OLD_CANDIDATE / destination
        data = _regular(source, 192 * 1024 * 1024)
        expected = old.get("sha256")
        if len(data) != old.get("bytes") or _sha(source) != expected:
            raise BuildError(f"old provider changed: {destination}")
        target = candidate / destination
        target.write_bytes(data)
        os.chmod(target, 0o600)
        items.append({"destination": destination, "bytes": len(data), "sha256": _sha(target), "mode": "0600", "sourceIdentity": {"kind": "existing_server_candidate_v2", "manifest": str(OLD_CANDIDATE / "candidate-manifest.json"), "sourcePath": str(source), "sourceSha256": expected, "sourceBytes": len(data)}})
    for destination, source, mode in (("bin/virgl_test_server", server, 0o700), ("lib/libvirglrenderer.so", library, 0o600)):
        data = _regular(source, 192 * 1024 * 1024)
        target = candidate / destination
        target.write_bytes(data)
        os.chmod(target, mode)
        audit = _elf_details(target)
        items.append({"destination": destination, "bytes": len(data), "sha256": _sha(target), "mode": format(mode, "04o"), "sourceIdentity": {"kind": "official_source_build", "recipeId": selected.recipe_id, "profile": selected.name, "sourcePath": str(source), "sourceSha256": SOURCE_SHA256, "patchIds": [item["id"] for item in _patch_records(selected.name)], "soname": audit.get("soname"), "needed": audit.get("needed", [])}})
    destinations = {item["destination"] for item in items}
    if destinations != EXPECTED_CANDIDATE_DESTINATIONS:
        raise BuildError(f"candidate destinations differ from the fixed ten-file contract: {sorted(destinations)}")
    return candidate, sorted(items, key=lambda item: item["destination"])


def _assert_final_elf_requirements(candidate: Path, new_destinations: set[str]) -> dict[str, Any]:
    audits: dict[str, Any] = {}
    for destination in sorted(new_destinations):
        details = _elf_details(candidate / destination)
        if details.get("elfClass") != 64 or details.get("elfData") != "LE" or details.get("elfMachine") != 183 or details.get("elfType") != "ET_DYN":
            raise BuildError(f"unexpected new ELF identity for {destination}: {details.get('elfClass')} {details.get('elfData')} {details.get('elfMachine')} {details.get('elfType')}")
        paths = list(details.get("rpath", [])) + list(details.get("runpath", []))
        if any(str(REPO) in path or str(ARTIFACT_ROOT) in path or "/home/" in path for path in paths):
            raise BuildError(f"new candidate ELF contains a host RPATH/RUNPATH: {destination}: {paths}")
        if destination.startswith("bin/"):
            if details.get("ptInterp") != "/system/bin/linker64":
                raise BuildError(f"unexpected executable interpreter for {destination}: {details.get('ptInterp')}")
        elif destination.startswith("lib/"):
            if details.get("ptInterp") is not None:
                raise BuildError(f"shared library unexpectedly has an interpreter for {destination}: {details.get('ptInterp')}")
        if destination == "lib/libvirglrenderer.so" and details.get("soname") != "libvirglrenderer.so.1":
            raise BuildError(f"unexpected libvirglrenderer SONAME: {details.get('soname')}")
        if details.get("needed", []) != EXPECTED_NEEDED[destination]:
            raise BuildError(f"unexpected DT_NEEDED for {destination}: {details.get('needed')}")
        if paths != EXPECTED_RUNPATHS[destination]:
            raise BuildError(f"unexpected runtime paths for {destination}: {paths}")
        audits[destination] = {"rpath": details.get("rpath", []), "runpath": details.get("runpath", []), "ptInterp": details.get("ptInterp"), "soname": details.get("soname")}
    return audits


def _closure(candidate: Path, items: list[dict[str, Any]]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    audits: list[dict[str, Any]] = []
    owned: dict[str, tuple[str, dict[str, Any]]] = {}
    for item in items:
        path = candidate / item["destination"]
        details = _elf_details(path)
        audit = {"destination": item["destination"], "bytes": details["bytes"], "sha256": details["sha256"], "elf": details}
        audits.append(audit)
        names = {PurePosixPath(item["destination"]).name}
        if item["destination"].startswith("lib/"):
            names.add(details.get("soname"))
        for name in (value for value in names if isinstance(value, str) and value):
            previous = owned.get(name)
            if previous and previous[0] != item["destination"]:
                raise BuildError(f"conflicting candidate provider name: {name}: {previous[0]} vs {item['destination']}")
            owned[name] = (item["destination"], details)
    root = "bin/virgl_test_server"
    root_details = next(item["elf"] for item in audits if item["destination"] == root)
    queue: deque[tuple[str, str | None]] = deque((name, root) for name in root_details.get("needed", []))
    resolved: list[dict[str, Any]] = [{"needed": "virgl_test_server", "provider": "candidate", "destination": root}]
    missing: list[dict[str, Any]] = []
    visited: set[str] = set()
    while queue:
        name, parent = queue.popleft()
        if name in visited:
            continue
        visited.add(name)
        provider = owned.get(name)
        if provider:
            destination, details = provider
            resolved.append({"needed": name, "provider": "candidate", "destination": destination})
            queue.extend((needed, name) for needed in details.get("needed", []))
        elif name in ANDROID_ALLOWLIST:
            resolved.append({"needed": name, "provider": "allowlisted_android_system"})
        else:
            missing.append({"needed": name, "from": parent, "reason": "not_owned_or_allowlisted"})
    closure = {"root": root, "resolved": resolved, "missing": missing, "missingSonames": sorted({item["needed"] for item in missing}), "allowlistedAndroidSystemSonames": sorted(ANDROID_ALLOWLIST), "candidateFileCount": len(items), "status": "SATISFIED" if not missing else "INCOMPLETE", "linkerExecution": "NOT_PERFORMED", "candidateAliasResolution": {"libvirglrenderer.so.1": "lib/libvirglrenderer.so", "runtimeResolutionProven": False}}
    return closure, audits


def _config_header_record(config: Path) -> dict[str, Any]:
    if not config.is_file():
        return {"path": str(config), "status": "MISSING"}
    text = config.read_text(encoding="utf-8")
    names = ("ENABLE_GBM", "ENABLE_GBM_ALLOCATION", "HAVE_EPOXY_EGL_H", "HAVE_EPOXY_GLX_H")
    defines: dict[str, str | None] = {}
    for name in names:
        match = re.search(rf"^#define {name}(?:[ \t]+(.*))?$", text, re.MULTILINE)
        undef = re.search(rf"^/\* #undef {name} \*/$", text, re.MULTILINE)
        defines[name] = match.group(1).strip() if match and match.group(1) else ("defined" if match else (None if undef else "UNSET"))
    return {
        "path": str(config),
        "bytes": config.stat().st_size,
        "sha256": _sha(config),
        "defines": defines,
        "androidDetection": {
            "compilerPredefined": "__ANDROID__",
            "mesaMacro": "DETECT_OS_ANDROID",
            "configHeader": "not emitted by Meson; supplied by src/mesa/util/detect_os.h",
        },
    }


def _manifest(workspace: Path, candidate: Path, items: list[dict[str, Any]], closure: dict[str, Any], audits: list[dict[str, Any]], runtime_audit: dict[str, Any], tools: dict[str, Any], deps: dict[str, Any], patch: dict[str, Any], commands: list[dict[str, Any]], macro_evidence: dict[str, Any], profile: str = "diagnostic") -> dict[str, Any]:
    selected = _profile(profile)
    config = workspace / f"build-{selected.name}/config.h"
    config_record = _config_header_record(config)
    config_defines = config_record.get("defines", {})
    removed_features = ["venus", "video", "tests", "fuzzer", "tracing", "drm-renderers"]
    if config_defines.get("ENABLE_GBM_ALLOCATION") not in {"defined", "1"}:
        removed_features.append("minigbm_allocation")
    return {
        "schema": "wowforever.virgl-server-candidate", "schemaVersion": 1, "executedPayloads": False, "ready": False,
        "deviceAliases": DEVICE_ALIASES,
        "buildProfile": {"name": selected.name, "profileId": selected.recipe_id, "workspace": str(workspace), "functionalEglFix": selected.functional_egl_fix, "officialSourceBuild": True, "options": _meson_options(), "compileArgs": _compile_args(selected, workspace / "deps"), "compatibilityDefines": list(selected.compatibility_defines), "androidMacroEnabled": macro_evidence["compiler"]["defines"].get("__ANDROID__") == "1" and macro_evidence["mesaDetectOs"]["defines"].get("DETECT_OS_ANDROID") == "1", "compiledMacros": {name: config_defines.get(name) for name in ("ENABLE_GBM", "ENABLE_GBM_ALLOCATION")}, "removedPlatforms": ["glx"], "removedFeatures": removed_features, "removedFeaturesAreNotBinaryEquivalenceClaim": True, "semanticIdentity": "Android-default EGL display opt-in; not a byte-identical upstream claim" if selected.functional_egl_fix else "diagnostic side effects only; not a byte-identical upstream claim"},
        "source": {"archive": str(SOURCE_TAR), "bytes": SOURCE_BYTES, "sha256": SOURCE_SHA256, "root": SOURCE_ROOT_NAME, "patch": patch, "patchScopes": {"diagnostic": "WFVTEST_DIAG logging around the unchanged EGL/winsys control flow", "android-default": "__ANDROID__ and WFVTEST_ANDROID_DEFAULT-only EGL_DEFAULT_DISPLAY path for GLES with NULL GBM and surfaceless=false, outside ENABLE_GBM_ALLOCATION; normal branches unchanged", "negotiation-trace": "WFVTEST_NEGOTIATION_TRACE-only bounded stderr metadata for unchanged vtest request/reply framing and caps", "install-rpath": "private $ORIGIN install lookup only", "public-soname": "explicit public libvirglrenderer.so.1 SONAME for the Android shared-library layout", "public-soname-link": "explicit Android linker SONAME override matching the public .so.1 ABI", "ndk-public-api": "Android NDK public logging/property and dma-buf APIs only; no EGL/GBM behavior change"}, "configHeader": config_record},
        "toolchain": {"ndk": str(NDK), "clang": str(CLANG), "clangSha256": _sha(CLANG), "clangTarget": "aarch64-linux-android29", "ninja": str(NINJA), "ninjaSha256": _sha(NINJA), "meson": tools, "macroEvidence": macro_evidence},
        "dependencies": deps,
        "materializedFiles": items,
        "binaryAudit": audits, "runtimePathAudit": runtime_audit,
        "closure": closure,
        "deviceExecution": {"executed": False, "adb": False, "phoneBinariesExecuted": False},
        "protocol": {"status": "UNVERIFIED"},
        "privateLoaderResolution": {"status": "UNVERIFIED", "interpreter": "/system/bin/linker64", "method": "static ELF metadata only; no loader execution", "aliases": [{"name": "libvirglrenderer.so.1", "target": "lib/libvirglrenderer.so", "runtimeResolutionProven": False}]},
        "commands": commands,
        "logs": {"build": str(workspace / "logs/build.log"), "buildRecord": {"path": str(workspace / "logs/build.log"), "bytes": (workspace / "logs/build.log").stat().st_size, "sha256": _sha(workspace / "logs/build.log")}},
        "budget": {"workspaceLimitBytes": MAX_WORKSPACE_BYTES, "toolDownloadLimitBytes": MAX_TOOL_DOWNLOAD_BYTES, "networkDownloadBytes": tools.get("downloadBytes", 0) + deps.get("gbmHeader", {}).get("downloadBytes", 0), "jobs": 2, "workspaceMeasuredBytes": _workspace_bytes(workspace)},
    }


def _workspace_bytes(root: Path) -> int:
    total = 0
    for path in root.rglob("*"):
        if path.is_file() and not path.is_symlink():
            total += path.stat().st_size
    return total


def build(profile: str = "diagnostic") -> Path:
    selected = _profile(profile)
    workspace = selected.workspace
    if not workspace.is_absolute() or workspace.resolve(strict=False).is_relative_to(REPO):
        raise BuildError("workspace must remain outside repository")
    if workspace.is_symlink() or workspace.exists() and not workspace.is_dir():
        raise BuildError(f"workspace must be a non-symlink directory: {workspace}")
    if workspace.exists() and any(workspace.iterdir()):
        raise BuildError(f"fresh workspace is not empty: {workspace}")
    workspace.mkdir(mode=0o700, parents=False, exist_ok=True)
    log = workspace / "logs/build.log"
    source = _extract_source(workspace)
    patch = _apply_patch(source, log, selected.name)
    deps, dep_record = _prepare_dependencies(workspace)
    meson, tool_record, tool_commands = _ensure_tools(workspace, log)
    macro_evidence = _compiler_macro_record(workspace, source, deps, _build_environment(workspace, deps, meson), selected.name)
    build_commands = _configure_and_build(workspace, source, deps, meson, log, selected.name)
    server = _find_installed(workspace, "virgl_test_server")
    library = _find_installed(workspace, "libvirglrenderer.so", versioned=True)
    candidate, items = _copy_candidate_files(workspace, server, library, selected.name)
    runtime_audit = _assert_final_elf_requirements(candidate, {"bin/virgl_test_server", "lib/libvirglrenderer.so"})
    closure, audits = _closure(candidate, items)
    if closure["missingSonames"]:
        raise BuildError(f"candidate closure is incomplete: {closure['missingSonames']}")
    manifest = _manifest(workspace, candidate, items, closure, audits, runtime_audit, tool_record, dep_record, patch, tool_commands + build_commands, macro_evidence, selected.name)
    manifest_path = candidate / "candidate-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if _workspace_bytes(workspace) > MAX_WORKSPACE_BYTES:
        raise BuildError("workspace exceeds 512 MiB after build")
    print(json.dumps({"profile": selected.name, "candidate": str(candidate), "manifest": str(manifest_path), "serverSha256": next(item["sha256"] for item in items if item["destination"] == "bin/virgl_test_server"), "librarySha256": next(item["sha256"] for item in items if item["destination"] == "lib/libvirglrenderer.so"), "workspaceBytes": _workspace_bytes(workspace)}, sort_keys=True))
    return manifest_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", action="store_true", help="build one new source candidate")
    parser.add_argument("--profile", choices=sorted(PROFILES), default="diagnostic")
    args = parser.parse_args(argv)
    if not args.build:
        parser.error("--build is required")
    try:
        build(args.profile)
    except (BuildError, OSError, subprocess.SubprocessError) as exc:
        print(f"build_diagnostic: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
