#!/usr/bin/env python3
"""Run the bounded own-process x64 cert-store tracer.

The normal tracer is a Windows DEBUG_ONLY_THIS_PROCESS program compiled from
``wow_trace.c``.  This host wrapper builds the disposable synthetic target and
the tracer, verifies their PE identity, and runs exactly one isolated Wine
container.  ``--real`` is intentionally available only as an explicit parent
review gate; implementation verification does not invoke it.

The report contains contract validation and bounded instruction/region
metadata, never table bytes, key values, heap dumps, credentials, or an
authentication result.  Real mode remains an explicit parent-review gate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import select
import selectors
import shutil
import stat
import struct
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[4]
SOURCE = Path(__file__).with_name("wow_trace.c")
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/work")
IMAGE = (
    "wowserver/forever:auth-candidate@"
    "sha256:9d553802d94756f06968f12ebfe31a4a71060a8ee73267353a4879984260ba06"
)
DEFAULT_CLIENT = Path(
    "/home/adrian/wowforever-artifacts/work/"
    "x64-certstore-data-20261013T213448Z-211565/fixture-root/players/player1/client"
)
LAB_DATA_REPORT = Path(
    "/home/adrian/wowforever-artifacts/work/"
    "x64-certstore-data-20261013T213448Z-211565/lab-data-report.json"
)
IMMUTABLE_LAB_DATA_REPORT = LAB_DATA_REPORT
COMPLETION_REPORT = Path(
    "/home/adrian/wowforever-artifacts/work/"
    "x64-certstore-data-20261013T213448Z-211565/manifest-completion-report.json"
)
EXPECTED_WOWB_SHA256 = "5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92"
EXPECTED_BUILD_INFO_SHA256 = "5f99a2d48e442a51d2b9c2ea4dae860240476499b3726640e783f7cef5b30626"
EXPECTED_LAB_DATA_REPORT_SHA256 = "61e98c21e5e4c35899881d2dd4812a348295cbeb3473ab2bd29a850c30293dea"
EXPECTED_COMPLETION_REPORT_SHA256 = "810cb5bda4921e0709a3d5ea037c90a1bd5b8044396c9921c9b4fe6a2c2d2ba6"
PUBLIC_MANIFEST_COUNT = 42
SOURCE_FILE_MAX_BYTES = 256 * 1024 * 1024
REAL_CONFIG_NAME = "BetaTrace.wtf"
REAL_PORTAL = "auth.gpon.com.co"
OUTPUT_CAP = 256 * 1024
XVFB_STDERR_CAP = 64 * 1024
XVFB_START_SECONDS = 10
XVFB_STOP_SECONDS = 10
RUN_SECONDS = {"synthetic": 180, "real": 300}
RUN_SECONDS["passive-real"] = 330
REAL_MODES = {"real", "passive-real"}
DISPLAY_CAPTURE_DELAYS = (20, 60, 180)
DISPLAY_CAPTURE_TIMEOUT = 5
DISPLAY_CAPTURE_OUTPUT_CAP = 32 * 1024
DISPLAY_CAPTURE_PNG_CAP = 4 * 1024 * 1024
DISPLAY_CAPTURE_SIZE = (1280, 720)
TRACE_EVENT_CAP = 64
TRACE_EXCEPTION_SAMPLE_CAP = 16
TRACE_EXCEPTION_CODE_CAP = 8
TRACE_EXCEPTION_DISPATCH_CAP = 4096
TABLE_ROWS = 12
TABLE_WIDTH = 40
TABLE_BYTES = TABLE_ROWS * TABLE_WIDTH


class TraceError(RuntimeError):
    """A bounded lab precondition or observation failure."""


class HostXvfb:
    """One host-owned Xvfb instance and its private display resources."""

    def __init__(self, process: subprocess.Popen[bytes], display: int, socket_path: Path,
                 auth_path: Path, stderr: bytes, stderr_truncated: bool,
                 socket_preexisting: bool) -> None:
        self.process = process
        self.display = display
        self.socket_path = socket_path
        self.auth_path = auth_path
        self.stderr = stderr
        self.stderr_truncated = stderr_truncated
        self.socket_preexisting = socket_preexisting
        self._stderr_thread: threading.Thread | None = None
        self._stderr_buffer = bytearray()
        self._stderr_stream = process.stderr

    @classmethod
    def start(cls, output: Path) -> "HostXvfb":
        xvfb = shutil.which("Xvfb")
        if xvfb is None:
            raise TraceError("host Xvfb is unavailable; real mode is blocked")
        if os.getuid() != 1000:
            raise TraceError("host Xvfb must run as UID 1000 for the container display user")
        _reject_symlink_components(Path("/tmp/.X11-unix"))
        if not Path("/tmp/.X11-unix").is_dir():
            raise TraceError("host X11 Unix socket directory is unavailable")
        existing_sockets = {item.name for item in Path("/tmp/.X11-unix").iterdir()}
        auth_path = output / ".xauthority"
        cookie = secrets.token_bytes(16)
        display_number = None
        display_read, display_write = os.pipe()
        os.set_blocking(display_read, False)
        command = [
            xvfb, "-displayfd", str(display_write), "-screen", "0", "1280x720x24",
            "-nolisten", "tcp", "-auth", str(auth_path),
        ]
        _write_exclusive(auth_path, struct.pack("!HH", 0xFFFF, 0) + struct.pack("!H", 0) + struct.pack("!H", 18) + b"MIT-MAGIC-COOKIE-1" + struct.pack("!H", len(cookie)) + cookie)
        process: subprocess.Popen[bytes] | None = None
        stderr_buffer = bytearray()
        stderr_truncated = False

        def drain_stderr(stream: Any) -> None:
            nonlocal stderr_truncated
            while True:
                chunk = stream.read(4096)
                if not chunk:
                    return
                room = XVFB_STDERR_CAP - len(stderr_buffer)
                if room > 0:
                    stderr_buffer.extend(chunk[:room])
                if len(chunk) > room:
                    stderr_truncated = True

        try:
            process = subprocess.Popen(
                command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                pass_fds=(display_write,), close_fds=True,
            )
            os.close(display_write)
            display_write = -1
            assert process.stderr is not None
            stderr_thread = threading.Thread(target=drain_stderr, args=(process.stderr,), daemon=True)
            stderr_thread.start()
            deadline = time.monotonic() + XVFB_START_SECONDS
            display_bytes = bytearray()
            while display_number is None and time.monotonic() < deadline:
                if display_read in select.select([display_read], [], [], 0.1)[0]:
                    chunk = os.read(display_read, 64)
                    display_bytes.extend(chunk)
                    if b"\n" in display_bytes:
                        line = bytes(display_bytes).split(b"\n", 1)[0].strip()
                        if not re.fullmatch(rb"[0-9]+", line):
                            raise TraceError("host Xvfb returned an invalid display number")
                        display_number = int(line)
                if process.poll() is not None and display_number is None:
                    break
            if display_number is None:
                raise TraceError("host Xvfb did not allocate a display within 10 seconds")
            socket_path = Path("/tmp/.X11-unix") / f"X{display_number}"
            # -displayfd is sent after the server creates its socket. Compare
            # against the pre-spawn inventory, not its expected current presence.
            socket_preexisting = socket_path.name in existing_sockets
            if socket_preexisting:
                raise TraceError("host Xvfb display socket unexpectedly pre-existed")
            number = str(display_number).encode("ascii")
            auth_record = (struct.pack("!HH", 0xFFFF, 0) + struct.pack("!H", len(number))
                           + number + struct.pack("!H", 18) + b"MIT-MAGIC-COOKIE-1"
                           + struct.pack("!H", len(cookie)) + cookie)
            auth_fd = os.open(auth_path, os.O_WRONLY | os.O_NOFOLLOW)
            try:
                os.ftruncate(auth_fd, 0)
                written = 0
                while written < len(auth_record):
                    written += os.write(auth_fd, auth_record[written:])
                os.fsync(auth_fd)
            finally:
                os.close(auth_fd)
            while time.monotonic() < deadline:
                try:
                    info = os.lstat(socket_path)
                    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
                        raise TraceError("host Xvfb socket is not a private owned Unix socket")
                    if process.poll() is not None:
                        raise TraceError("host Xvfb exited before socket readiness")
                    instance = cls(process, display_number, socket_path, auth_path, b"", False, False)
                    instance._stderr_thread = stderr_thread
                    instance._stderr_buffer = stderr_buffer
                    instance._stderr_stream = process.stderr
                    instance.stderr_truncated = stderr_truncated
                    return instance
                except FileNotFoundError:
                    time.sleep(0.05)
            raise TraceError("host Xvfb socket was not ready within 10 seconds")
        except BaseException:
            if process is not None:
                cls._stop_process(process, stderr_thread if 'stderr_thread' in locals() else None)
            raise
        finally:
            os.close(display_read)
            if display_write != -1:
                os.close(display_write)

    @staticmethod
    def _stop_process(process: subprocess.Popen[bytes], stderr_thread: threading.Thread | None) -> None:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=XVFB_STOP_SECONDS)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=XVFB_STOP_SECONDS)
        if stderr_thread is not None:
            stderr_thread.join(timeout=XVFB_STOP_SECONDS)

    def stop(self) -> dict[str, Any]:
        self._stop_process(self.process, self._stderr_thread)
        if self._stderr_stream is not None:
            self._stderr_stream.close()
        self.stderr = bytes(self._stderr_buffer[:XVFB_STDERR_CAP])
        self.stderr_truncated = self.stderr_truncated or len(self._stderr_buffer) > XVFB_STDERR_CAP
        return {
            "implementation": "host-Xvfb",
            "display": f":{self.display}",
            "displayfd": True,
            "pid": self.process.pid,
            "socket": str(self.socket_path),
            "socket_preexisting": self.socket_preexisting,
            "socket_reuse": False,
            "socket_created_by_owned_process": True,
            "socket_private_owned": True,
            "socket_uid": os.getuid(),
            "tcp_listen": False,
            "auth_mounted_readonly": True,
            "cookie_reported": False,
            "returncode": self.process.returncode,
            "stderr": self.stderr.decode(errors="replace"),
            "stderr_bytes": len(self.stderr),
            "stderr_truncated": self.stderr_truncated,
        }


def _write_exclusive(path: Path, data: bytes, mode: int = 0o600) -> None:
    if path.exists() or path.is_symlink():
        raise TraceError(f"refusing to overwrite existing artifact: {path}")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            if written <= 0:
                raise TraceError("private index write made no progress")
            view = view[written:]
        os.fsync(fd)
    finally:
        os.close(fd)


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _read_bounded_file(path: Path, limit: int) -> bytes:
    _reject_symlink_components(path)
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError as error:
        raise TraceError(f"cannot stat metadata file {path}: {error}") from error
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise TraceError(f"metadata file is not a bounded regular file: {path}")
        data = bytearray()
        while len(data) < before.st_size:
            chunk = os.read(fd, min(1 << 20, before.st_size - len(data)))
            if not chunk:
                raise TraceError(f"metadata file truncated while reading: {path}")
            data.extend(chunk)
        after = os.fstat(fd)
    finally:
        os.close(fd)
    if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
        raise TraceError(f"metadata file changed while reading: {path}")
    return bytes(data)


def _source_file_metadata(path: Path, expected: dict[str, Any]) -> dict[str, Any]:
    """Hash one approved manifest file without following a mutable final link."""
    _reject_symlink_components(path)
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError as error:
        raise TraceError(f"cannot open approved source file {path}: {error}") from error
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_size > SOURCE_FILE_MAX_BYTES:
            raise TraceError(f"approved source file is not bounded regular data: {path}")
        digest = hashlib.sha256()
        md5 = hashlib.md5()
        remaining = before.st_size
        while remaining:
            chunk = os.read(fd, min(1 << 20, remaining))
            if not chunk:
                raise TraceError(f"approved source file truncated while reading: {path}")
            digest.update(chunk)
            md5.update(chunk)
            remaining -= len(chunk)
        after = os.fstat(fd)
    finally:
        os.close(fd)
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
        after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns
    ):
        raise TraceError(f"approved source file changed while hashing: {path}")
    actual = {
        "path": expected["path"],
        "bytes": before.st_size,
        "md5": md5.hexdigest(),
        "sha256": digest.hexdigest(),
    }
    if actual["bytes"] != expected["bytes"] or actual["md5"] != expected["md5"] or actual["sha256"] != expected["sha256"]:
        raise TraceError(f"approved source metadata mismatch: {path}")
    return actual


def _manifest_files(client: Path) -> tuple[Path, list[dict[str, Any]]]:
    """Load the fixed, successful 42-file manifest completion authority."""
    original_bytes = _read_bounded_file(LAB_DATA_REPORT, 4 * 1024 * 1024)
    original_sha = hashlib.sha256(original_bytes).hexdigest()
    if original_sha != EXPECTED_LAB_DATA_REPORT_SHA256:
        raise TraceError("original lab-data report SHA-256 does not match")
    try:
        original = json.loads(original_bytes)
    except (OSError, json.JSONDecodeError) as error:
        raise TraceError(f"cannot read original lab-data report: {error}") from error
    if (
        original.get("destination") != str(client)
        or original.get("build", {}).get("wowb_exe_sha256") != EXPECTED_WOWB_SHA256
        or original.get("copy", {}).get("copy_equality_verified") is not True
        or original.get("copy", {}).get("source_inventory_stable") is not True
    ):
        raise TraceError("original lab-data report does not prove the fixed client source")
    completion_bytes = _read_bounded_file(COMPLETION_REPORT, 1 * 1024 * 1024)
    completion_sha = hashlib.sha256(completion_bytes).hexdigest()
    if completion_sha != EXPECTED_COMPLETION_REPORT_SHA256:
        raise TraceError("manifest-completion report SHA-256 does not match")
    try:
        report = json.loads(completion_bytes)
    except (OSError, json.JSONDecodeError) as error:
        raise TraceError(f"cannot read immutable manifest-completion report: {error}") from error
    completion = report.get("manifest_completion")
    files = completion.get("files") if isinstance(completion, dict) else None
    if (
        report.get("status") != "manifest-complete"
        or report.get("destination") != str(client)
        or report.get("destination_archive_metadata_stable") is not True
        or report.get("source_manifest_identity_stable") is not True
        or completion.get("source_identity_stable") is not True
        or report.get("source_phase_clarification", {}).get("current_destination_42_validation") != "passed"
        or not isinstance(files, list)
        or len(files) != PUBLIC_MANIFEST_COUNT
        or completion.get("copied_count") != PUBLIC_MANIFEST_COUNT - 1
        or completion.get("existing_valid_count") != 1
        or report.get("old_report_sha256_before") != original_sha
        or report.get("old_report_sha256_after") != original_sha
    ):
        raise TraceError("immutable manifest-completion report does not prove the exact 42-file pass")
    selected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in files:
        if not isinstance(item, dict) or item.get("action") not in {"copied", "existing-valid"}:
            raise TraceError("manifest completion contains an unsuccessful file entry")
        relative = item.get("path")
        if not isinstance(relative, str) or not relative or relative in seen:
            raise TraceError("manifest completion contains an invalid or duplicate path")
        relative_path = Path(relative)
        if relative_path.is_absolute() or ".." in relative_path.parts or "\\" in relative:
            raise TraceError("manifest completion contains an unsafe path")
        if not isinstance(item.get("bytes"), int) or item["bytes"] < 0 or item["bytes"] > SOURCE_FILE_MAX_BYTES:
            raise TraceError("manifest completion contains an unbounded file size")
        if not re.fullmatch(r"[0-9a-f]{32}", str(item.get("md5"))) or not re.fullmatch(r"[0-9a-f]{64}", str(item.get("sha256"))):
            raise TraceError("manifest completion contains malformed file metadata")
        seen.add(relative)
        selected.append({"path": relative, "bytes": item["bytes"], "md5": item["md5"], "sha256": item["sha256"]})
    _reject_symlink_components(client)
    root = client.resolve(strict=True)
    if root != client or not root.is_dir():
        raise TraceError("fixed lab client must be a canonical directory, not a symlink")
    _reject_symlink_components(client / "Data")
    if not (client / "Data").is_dir():
        raise TraceError("fixed lab client Data directory is unavailable")
    return COMPLETION_REPORT, selected


def _reject_symlink_components(path: Path) -> None:
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        if os.path.lexists(current) and current.is_symlink():
            raise TraceError(f"refusing symlink output component: {current}")


def new_output(requested: str | None) -> Path:
    if requested:
        output = Path(requested).absolute()
        _reject_symlink_components(output)
        if ROOT == output or ROOT in output.parents:
            raise TraceError("artifacts must be outside the repository")
    else:
        ARTIFACT_ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
        output = ARTIFACT_ROOT / (
            f"x64-certstore-trace-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-"
            f"{os.getpid()}"
        )
        _reject_symlink_components(output)
    if output.exists() or output.is_symlink():
        raise TraceError(f"refusing to reuse output directory: {output}")
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    os.chmod(output, 0o700)
    return output


def _tool(command: list[str], *, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise TraceError(f"tool failed: {' '.join(command)}: {error}") from error
    if result.returncode:
        raise TraceError(f"tool exit {result.returncode}: {' '.join(command)}: {result.stderr[-2000:]}")
    return result


def _pe_identity(path: Path) -> dict[str, Any]:
    data = path.read_bytes() if path.stat().st_size <= 1 << 20 else path.open("rb").read(4096)
    if data[:2] != b"MZ" or len(data) < 0x40:
        raise TraceError(f"{path} is not a PE image")
    pe_offset = int.from_bytes(data[0x3C:0x40], "little")
    if pe_offset + 0x98 > len(data) or data[pe_offset:pe_offset + 4] != b"PE\0\0":
        raise TraceError(f"{path} has no valid PE header")
    machine = int.from_bytes(data[pe_offset + 4:pe_offset + 6], "little")
    optional_magic = int.from_bytes(data[pe_offset + 24:pe_offset + 26], "little")
    image_size = int.from_bytes(data[pe_offset + 0x50:pe_offset + 0x54], "little")
    if machine != 0x8664 or optional_magic != 0x20B or not image_size:
        raise TraceError(f"{path} is not PE32+ x86-64")
    return {
        "machine": "0x8664",
        "optional_magic": "0x20b",
        "image_size": image_size,
        "sha256": sha256(path),
        "bytes": path.stat().st_size,
    }


def compile_images(output: Path) -> dict[str, Any]:
    compiler = "x86_64-w64-mingw32-gcc"
    tracer = output / "wow_trace.exe"
    fixture = output / "synthetic_certstore_fixture.exe"
    common = [
        compiler, "-std=c11", "-O0", "-g", "-fno-omit-frame-pointer",
        "-Wall", "-Wextra", "-Werror", "-D_WIN32_WINNT=0x0602",
    ]
    tracer_command = [*common, str(SOURCE), "-o", str(tracer), "-ldbghelp", "-lpsapi"]
    fixture_command = [*common, "-DTRACE_SYNTHETIC_FIXTURE", str(SOURCE), "-o", str(fixture)]
    _tool(tracer_command)
    _tool(fixture_command)
    os.chmod(tracer, 0o700)
    os.chmod(fixture, 0o700)
    return {
        "compiler": compiler,
        "compiler_version": _tool([compiler, "--version"], timeout=10).stdout.splitlines()[0],
        "source_sha256": sha256(SOURCE),
        "source": SOURCE.name,
        "tracer": {"name": tracer.name, **_pe_identity(tracer)},
        "synthetic_fixture": {"name": fixture.name, **_pe_identity(fixture)},
        "commands": {"tracer": tracer_command, "synthetic_fixture": fixture_command},
    }


def prepare_real_links(output: Path, client: Path) -> dict[str, Any]:
    """Expose only the selected read-only client view inside the own lab dir."""
    if client != DEFAULT_CLIENT:
        raise TraceError("the real mode has one fixed approved lab client path")
    report_path, selected = _manifest_files(client)
    source_root = client.resolve(strict=True)
    selected_metadata = [_source_file_metadata(source_root / item["path"], item) for item in selected]
    executable = source_root / "WowB.exe"
    identity = _pe_identity(executable)
    if identity["sha256"] != EXPECTED_WOWB_SHA256:
        raise TraceError("fixed lab WowB.exe SHA-256 does not match the approved x64 image")
    build_info = _read_bounded_file(source_root / ".build.info", 8192)
    if hashlib.sha256(build_info).hexdigest() != EXPECTED_BUILD_INFO_SHA256:
        raise TraceError("fixed public .build.info does not match the approved active build")
    build_info_metadata = _source_file_metadata(source_root / ".build.info", {
        "path": ".build.info", "bytes": len(build_info),
        "md5": hashlib.md5(build_info).hexdigest(), "sha256": EXPECTED_BUILD_INFO_SHA256,
    })
    game = output / "game"
    game.mkdir(mode=0o700, parents=False, exist_ok=False)
    # Link each selected public dependency independently.  The source mount is
    # /source in the container and is read-only; source WTF/HOME/account state
    # is deliberately not exposed in this private writable game view.
    linked_paths: list[str] = []
    for item in selected:
        relative = Path(item["path"])
        target = game / relative
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.symlink(f"/source/{item['path']}", target)
        linked_paths.append(item["path"])
    os.symlink("/lab/Data", game / "Data")
    os.symlink("/source/.build.info", game / ".build.info")
    writable_dirs = ("WTF", "Cache", "Logs", "Errors", "Screenshots")
    for name in writable_dirs:
        (game / name).mkdir(mode=0o700, exist_ok=False)
    wtf = game / "WTF" / REAL_CONFIG_NAME
    _write_exclusive(
        wtf,
        (
            f"SET portal {REAL_PORTAL}\n"
            "SET textLocale enUS\nSET audioLocale enUS\n"
            "SET gxApi d3d11\nSET gxWindow 1\nSET gxMaximize 0\n"
            "SET windowWidth 1280\nSET windowHeight 720\n"
            "SET enableBGDL 0\nSET accessibilityScreenNarrationEnabled 0\n"
            "SET showPhotosensitivityWarning 0\nSET Sound_MasterVolume 0\n"
            "SET rememberAccount 0\nSET autoComplete 0\n"
        ).encode(),
    )
    _fsync_dir(game / "WTF")
    return {
        "source": str(client),
        "manifest_report": {
            "path": str(report_path),
            "sha256": EXPECTED_COMPLETION_REPORT_SHA256,
            "original_lab_data_report": {
                "path": str(LAB_DATA_REPORT),
                "sha256": EXPECTED_LAB_DATA_REPORT_SHA256,
            },
            "selected_public_manifest_count": len(selected_metadata),
            "selected_public_manifest_42": selected_metadata,
        },
        "executable": identity,
        "source_wowb_sha256_before": identity["sha256"],
        "source_selected_files_before": selected_metadata,
        "linked_public_manifest_paths": linked_paths,
        "data_link": "/lab/Data",
        "build_info_before": build_info_metadata,
        "build_info_link": "/source/.build.info",
        "writable_game_directories": list(writable_dirs),
        "writable_config": wtf.name,
        "portal": REAL_PORTAL,
    }


def prepare_runtime(output: Path) -> Path:
    """Create the only writable container root, separate from evidence files."""
    runtime = output / "runtime"
    runtime.mkdir(mode=0o700, parents=False, exist_ok=False)
    home = runtime / "home"
    home.mkdir(mode=0o700, exist_ok=False)
    (home / ".cache").mkdir(mode=0o700, exist_ok=False)
    (runtime / "wineprefix").mkdir(mode=0o700, exist_ok=False)
    return runtime


DATA_ARCHIVES = tuple(f"data.{index:03d}" for index in range(93))
DATA_INDEX_RE = re.compile(r"^[0-9a-f]{10}\.idx$")
DATA_INDEX_MAX_BYTES = 16 * 1024 * 1024
DATA_INDEX_TOTAL_MAX_BYTES = 128 * 1024 * 1024
WORKING_ARCHIVE_EXPECTED_BYTES = 93_021_230_203
WORKING_ARCHIVE_RESERVE_BYTES = 256 * 1024 * 1024
WORKING_ARCHIVE_CHUNK_BYTES = 4 * 1024 * 1024
WORKING_ARCHIVE_COPY_DEADLINE_SECONDS = 900
WORKING_ARCHIVE_MAX_BYTES = WORKING_ARCHIVE_EXPECTED_BYTES
CDN_INDEX_NAME_RE = re.compile(r"^[0-9a-f]{32}\.index$")
CDN_INDEX_ENTRY_COUNT_MAX = 2048
CDN_INDEX_MAX_BYTES = 128 * 1024 * 1024
CDN_INDEX_TOTAL_MAX_BYTES = 2 * 1024 * 1024 * 1024


def _data_stat_record(info: os.stat_result, relative: str) -> dict[str, int | str]:
    return {
        "path": relative,
        "regular": True,
        "bytes": info.st_size,
        "uid": info.st_uid,
        "gid": info.st_gid,
        "mode": info.st_mode & 0o7777,
        "dev": info.st_dev,
        "ino": info.st_ino,
        "nlink": info.st_nlink,
        "mtime_ns": info.st_mtime_ns,
        "ctime_ns": info.st_ctime_ns,
    }


def _source_stat_metadata(path: Path, relative: str) -> dict[str, int | str]:
    info = os.lstat(path)
    if not stat.S_ISREG(info.st_mode) or path.is_symlink():
        raise TraceError(f"source data entry is not a regular file: {relative}")
    return _data_stat_record(info, relative)


def _read_stable_index(path: Path, relative: str) -> tuple[bytes, dict[str, Any]]:
    _reject_symlink_components(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_size > DATA_INDEX_MAX_BYTES:
            raise TraceError(f"source index is not bounded regular data: {relative}")
        data = bytearray()
        while len(data) < before.st_size:
            chunk = os.read(fd, min(64 * 1024, before.st_size - len(data)))
            if not chunk:
                raise TraceError(f"source index truncated while reading: {relative}")
            data.extend(chunk)
        after = os.fstat(fd)
    finally:
        os.close(fd)
    fields = ("st_dev", "st_ino", "st_size", "st_mode", "st_uid", "st_gid", "st_mtime_ns")
    if any(getattr(before, field) != getattr(after, field) for field in fields):
        raise TraceError(f"source index changed while reading: {relative}")
    digest = hashlib.sha256(data).hexdigest()
    metadata = {
        "path": relative,
        "bytes": before.st_size,
        "uid": before.st_uid,
        "gid": before.st_gid,
        "mode": before.st_mode & 0o7777,
        "dev": before.st_dev,
        "ino": before.st_ino,
        "mtime_ns": before.st_mtime_ns,
        "sha256": digest,
    }
    return bytes(data), metadata


def _validate_source_data_root(source: Path) -> tuple[Path, list[Path], list[Path]]:
    _reject_symlink_components(source)
    source_root = source.resolve(strict=True)
    if source_root != source or not source_root.is_dir():
        raise TraceError("source client must be a canonical directory")
    data_root = source / "Data"
    _reject_symlink_components(data_root)
    if not data_root.is_dir() or data_root.resolve(strict=True) != data_root:
        raise TraceError("source Data must be a canonical directory")
    root_entries = sorted(data_root.iterdir(), key=lambda item: item.name)
    if [entry.name for entry in root_entries] != ["config", "data", "indices"]:
        raise TraceError("source Data must contain exactly config, data, and indices")
    for entry in root_entries:
        if entry.is_symlink() or not entry.is_dir():
            raise TraceError("source Data entries must be real directories")
        _reject_symlink_components(entry)
    data_dir = data_root / "data"
    entries = sorted(data_dir.iterdir(), key=lambda item: item.name)
    expected_archives = set(DATA_ARCHIVES)
    archive_entries = [entry for entry in entries if entry.name in expected_archives]
    index_entries = [entry for entry in entries if DATA_INDEX_RE.fullmatch(entry.name)]
    if {entry.name for entry in archive_entries} != expected_archives or len(index_entries) != 16:
        raise TraceError("source Data/data must contain the fixed 93 archives and 16 indexes")
    if len(archive_entries) + len(index_entries) != len(entries):
        raise TraceError("source Data/data contains an unexpected entry")
    for entry in archive_entries + index_entries:
        if entry.is_symlink() or not entry.is_file():
            raise TraceError("source Data/data entries must be regular files")
    return data_root, sorted(archive_entries, key=lambda item: item.name), sorted(index_entries, key=lambda item: item.name)


def _validate_cdn_index_entries(data_root: Path) -> tuple[list[Path], list[dict[str, Any]], int]:
    indices_root = data_root / "indices"
    entries = sorted(indices_root.iterdir(), key=lambda item: item.name)
    if len(entries) > CDN_INDEX_ENTRY_COUNT_MAX:
        raise TraceError("source Data/indices has too many entries")
    if any(not CDN_INDEX_NAME_RE.fullmatch(entry.name) for entry in entries):
        raise TraceError("source Data/indices contains an unexpected entry")
    metadata: list[dict[str, Any]] = []
    total_bytes = 0
    for entry in entries:
        if entry.is_symlink() or not entry.is_file():
            raise TraceError("source Data/indices entries must be regular files")
        info = os.lstat(entry)
        if not stat.S_ISREG(info.st_mode) or info.st_size > CDN_INDEX_MAX_BYTES:
            raise TraceError("source CDN index exceeds the bounded size")
        total_bytes += info.st_size
        if total_bytes > CDN_INDEX_TOTAL_MAX_BYTES:
            raise TraceError("source CDN indexes exceed the bounded total size")
        metadata.append(_data_stat_record(info, f"Data/indices/{entry.name}"))
    return entries, metadata, total_bytes


def _copy_private_index(destination: Path, data: bytes, expected_sha256: str) -> dict[str, Any]:
    if destination.exists() or destination.is_symlink():
        raise TraceError("refusing to overwrite a data index")
    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            if written <= 0:
                raise TraceError("private index copy made no write progress")
            view = view[written:]
        os.fsync(fd)
        info = os.fstat(fd)
    finally:
        os.close(fd)
    os.chmod(destination, 0o600)
    post = _read_bounded_file(destination, DATA_INDEX_MAX_BYTES)
    post_sha256 = hashlib.sha256(post).hexdigest()
    if post_sha256 != expected_sha256 or info.st_size != len(data):
        raise TraceError("private data index changed after copy")
    return {"path": destination.name, "bytes": len(post), "mode": 0o600, "sha256": post_sha256}


def _metadata_stable(before: dict[str, Any], after: dict[str, Any]) -> bool:
    return all(before.get(field) == after.get(field) for field in (
        "bytes", "uid", "gid", "mode", "dev", "ino", "nlink", "mtime_ns", "ctime_ns",
    ))


def _hash_destination_file(path: Path, expected_size: int, max_size: int, deadline: float | None = None) -> tuple[str, dict[str, Any]]:
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_size != expected_size or before.st_size > max_size:
            raise TraceError("working file destination size/type changed")
        digest = hashlib.sha256()
        remaining = before.st_size
        while remaining:
            if deadline is not None and time.monotonic() >= deadline:
                raise TraceError("working archive copy deadline")
            chunk = os.read(fd, min(WORKING_ARCHIVE_CHUNK_BYTES, remaining))
            if not chunk:
                raise TraceError("working archive destination truncated")
            digest.update(chunk)
            remaining -= len(chunk)
        after = os.fstat(fd)
    finally:
        os.close(fd)
    before_meta = _data_stat_record(before, path.name)
    after_meta = _data_stat_record(after, path.name)
    if not _metadata_stable(before_meta, after_meta):
        raise TraceError("working archive destination changed while hashing")
    return digest.hexdigest(), after_meta


def _copy_working_file(source: Path, destination: Path, relative: str, deadline: float, max_size: int) -> dict[str, Any]:
    if destination.exists() or destination.is_symlink():
        raise TraceError("refusing to overwrite a working archive")
    source_fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW)
    destination_fd: int | None = None
    digest = hashlib.sha256()
    try:
        source_before_stat = os.fstat(source_fd)
        if not stat.S_ISREG(source_before_stat.st_mode) or source_before_stat.st_size > max_size:
            raise TraceError(f"source working file is not bounded regular data: {relative}")
        source_before = _data_stat_record(source_before_stat, relative)
        destination_fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        remaining = source_before_stat.st_size
        while remaining:
            if time.monotonic() >= deadline:
                raise TraceError("working archive copy deadline")
            chunk = os.read(source_fd, min(WORKING_ARCHIVE_CHUNK_BYTES, remaining))
            if not chunk:
                raise TraceError(f"source archive truncated while copying: {relative}")
            digest.update(chunk)
            view = memoryview(chunk)
            while view:
                written = os.write(destination_fd, view)
                if written <= 0:
                    raise TraceError("working archive write made no progress")
                view = view[written:]
            remaining -= len(chunk)
        source_after_stat = os.fstat(source_fd)
        source_after = _data_stat_record(source_after_stat, relative)
        source_metadata_stable = _metadata_stable(source_before, source_after)
        if not source_metadata_stable:
            raise TraceError(f"source archive changed while copying: {relative}")
        os.fsync(destination_fd)
    finally:
        os.close(source_fd)
        if destination_fd is not None:
            os.close(destination_fd)
    os.chmod(destination, 0o600)
    destination_sha256, destination_after = _hash_destination_file(destination, source_before_stat.st_size, max_size, deadline)
    source_sha256 = digest.hexdigest()
    if destination_sha256 != source_sha256:
        raise TraceError(f"working file hash mismatch: {relative}")
    if (destination_after["dev"], destination_after["ino"]) == (source_before["dev"], source_before["ino"]):
        raise TraceError(f"working file is not an independent file: {relative}")
    return {
        "path": relative,
        "bytes": source_before["bytes"],
        "source_sha256": source_sha256,
        "destination_sha256": destination_sha256,
        "source_metadata_before": source_before,
        "source_metadata_after": source_after,
        "source_metadata_stable": source_metadata_stable,
        "destination_metadata_after_copy": destination_after,
        "destination_identity_distinct": True,
        "destination_mode": 0o600,
    }


def _copy_working_archive(source: Path, destination: Path, relative: str, deadline: float) -> dict[str, Any]:
    return _copy_working_file(source, destination, relative, deadline, WORKING_ARCHIVE_MAX_BYTES)


def prepare_real_data_view(output: Path, source: Path, copy_working_archives: bool = False) -> dict[str, Any]:
    """Build disposable Data; original archives stay immutable in either mode."""
    data_root, archives, indexes = _validate_source_data_root(source)
    total_index_bytes = 0
    source_archives_before = [_source_stat_metadata(entry, f"Data/data/{entry.name}") for entry in archives]
    source_indexes_before: list[dict[str, Any]] = []
    index_payloads: list[tuple[Path, bytes, dict[str, Any]]] = []
    for entry in indexes:
        payload, metadata = _read_stable_index(entry, f"Data/data/{entry.name}")
        total_index_bytes += len(payload)
        if total_index_bytes > DATA_INDEX_TOTAL_MAX_BYTES:
            raise TraceError("source indexes exceed the bounded coordination budget")
        source_indexes_before.append(metadata)
        index_payloads.append((entry, payload, metadata))
    source_cdn_entries: list[Path] = []
    source_cdn_indices_before: list[dict[str, Any]] = []
    cdn_index_total_bytes = 0
    if copy_working_archives:
        source_cdn_entries, source_cdn_indices_before, cdn_index_total_bytes = _validate_cdn_index_entries(data_root)
    archive_total_bytes = sum(int(item["bytes"]) for item in source_archives_before)
    copy_deadline = time.monotonic() + WORKING_ARCHIVE_COPY_DEADLINE_SECONDS
    disk_free_before: int | None = None
    if copy_working_archives:
        if source == DEFAULT_CLIENT and archive_total_bytes != WORKING_ARCHIVE_EXPECTED_BYTES:
            raise TraceError("fixed source archive total does not match the approved ordinary-copy dataset")
        disk_free_before = shutil.disk_usage(output / "runtime").free
        required = archive_total_bytes + cdn_index_total_bytes + WORKING_ARCHIVE_RESERVE_BYTES
        if disk_free_before < required:
            raise TraceError("insufficient disk space for the bounded working archive copy")
    runtime_data = output / "runtime" / "Data"
    runtime_data.mkdir(mode=0o700, exist_ok=False)
    os.symlink("/source/Data/config", runtime_data / "config")
    runtime_indices = runtime_data / "indices"
    if copy_working_archives:
        runtime_indices.mkdir(mode=0o700, exist_ok=False)
    else:
        os.symlink("/source/Data/indices", runtime_indices)
    scratch_data = runtime_data / "data"
    scratch_data.mkdir(mode=0o700, exist_ok=False)
    archive_aliases: list[str] = []
    working_archive_copies: list[dict[str, Any]] = []
    if copy_working_archives:
        for entry in archives:
            remaining_bytes = sum(int(item["bytes"]) for item in source_archives_before[len(working_archive_copies):])
            if shutil.disk_usage(scratch_data).free < remaining_bytes + WORKING_ARCHIVE_RESERVE_BYTES:
                raise TraceError("working archive copy exhausted the reserved disk space")
            working_archive_copies.append(_copy_working_archive(
                entry, scratch_data / entry.name, f"Data/data/{entry.name}", copy_deadline,
            ))
    else:
        for entry in archives:
            destination = scratch_data / entry.name
            os.symlink(f"/source/Data/data/{entry.name}", destination)
            archive_aliases.append(entry.name)
    copied_indexes = []
    for entry, payload, metadata in index_payloads:
        copied_indexes.append(_copy_private_index(scratch_data / entry.name, payload, metadata["sha256"]))
    cdn_index_copies = []
    if copy_working_archives:
        for entry in source_cdn_entries:
            copied_count = len(cdn_index_copies)
            remaining_cdn = sum(int(item["bytes"]) for item in source_cdn_indices_before[copied_count:])
            if shutil.disk_usage(runtime_indices).free < remaining_cdn + WORKING_ARCHIVE_RESERVE_BYTES:
                raise TraceError("working index copy exhausted the reserved disk space")
            cdn_index_copies.append(_copy_working_file(
                entry, runtime_indices / entry.name, f"Data/indices/{entry.name}", copy_deadline, CDN_INDEX_MAX_BYTES,
            ))
    _fsync_dir(scratch_data)
    if copy_working_archives:
        _fsync_dir(runtime_indices)
    _fsync_dir(runtime_data)
    _fsync_dir(output / "runtime")
    return {
        "source_data_root": "Data",
        "source_archive_count": len(archives),
        "source_index_count": len(indexes),
        "copy_working_archives": copy_working_archives,
        "working_archive_copies": working_archive_copies,
        "working_archive_mode": "copy" if copy_working_archives else "readonly-alias",
        "working_archive_total_bytes": archive_total_bytes,
        "source_cdn_indices_before": source_cdn_indices_before,
        "cdn_index_total_bytes": cdn_index_total_bytes,
        "cdn_index_copies": cdn_index_copies,
        "disk_free_before_archive_copy": disk_free_before,
        "disk_reserve_bytes": WORKING_ARCHIVE_RESERVE_BYTES if copy_working_archives else 0,
        "archive_aliases": archive_aliases,
        "source_archives_before": source_archives_before,
        "source_indexes_before": source_indexes_before,
        "copied_indexes": copied_indexes,
        "index_total_bytes": total_index_bytes,
        "runtime_data": "runtime/Data",
        "coordination_data": "runtime/Data/data",
        "config_link": "/source/Data/config",
        "indices_link": None if copy_working_archives else "/source/Data/indices",
    }


def finalize_real_data_view(view: dict[str, Any], source: Path, output: Path | None = None) -> dict[str, Any]:
    """Recheck immutable source metadata without reading archive payloads."""
    try:
        data_root, archives, indexes = _validate_source_data_root(source)
        source_archives_after = [_source_stat_metadata(entry, f"Data/data/{entry.name}") for entry in archives]
        source_indexes_after = []
        for entry in indexes:
            _, metadata = _read_stable_index(entry, f"Data/data/{entry.name}")
            source_indexes_after.append(metadata)
        view["source_archives_after"] = source_archives_after
        view["source_indexes_after"] = source_indexes_after
        view["source_archives_unchanged"] = source_archives_after == view["source_archives_before"]
        view["source_indexes_unchanged"] = (
            len(source_indexes_after) == len(view["source_indexes_before"])
            and all(after["sha256"] == before["sha256"] for before, after in zip(view["source_indexes_before"], source_indexes_after))
        )
        source_cdn_unchanged = True
        if view.get("copy_working_archives"):
            _, source_cdn_after, _ = _validate_cdn_index_entries(data_root)
            view["source_cdn_indices_after"] = source_cdn_after
            source_cdn_unchanged = (
                len(source_cdn_after) == len(view["source_cdn_indices_before"])
                and all(after == before for before, after in zip(view["source_cdn_indices_before"], source_cdn_after))
            )
            view["source_cdn_indices_unchanged"] = source_cdn_unchanged
            if output is not None:
                destination_root = output / "runtime" / "Data" / "indices"
                destination_after = []
                for copied in view["cdn_index_copies"]:
                    destination = destination_root / Path(copied["path"]).name
                    try:
                        metadata = _source_stat_metadata(destination, copied["path"])
                        destination_after.append({
                            "path": copied["path"],
                            "metadata": metadata,
                            "changed_after_client": metadata != copied["destination_metadata_after_copy"],
                        })
                    except Exception as error:
                        destination_after.append({
                            "path": copied["path"],
                            "status": "unavailable",
                            "error_type": type(error).__name__,
                        })
                view["cdn_index_destination_after"] = destination_after
                view["cdn_index_destination_hashes_postrun"] = "not_collected"
        view["source_unchanged"] = view["source_archives_unchanged"] and view["source_indexes_unchanged"] and source_cdn_unchanged
        view["source_payload_integrity"] = "metadata-only-no-full-archive-hash"
        if view.get("copy_working_archives") and output is not None:
            destination_root = output / "runtime" / "Data" / "data"
            destination_after = []
            for copied in view["working_archive_copies"]:
                destination = destination_root / Path(copied["path"]).name
                try:
                    metadata = _source_stat_metadata(destination, copied["path"])
                    destination_after.append({
                        "path": copied["path"],
                        "metadata": metadata,
                        "changed_after_client": metadata != copied["destination_metadata_after_copy"],
                    })
                except Exception as error:
                    destination_after.append({
                        "path": copied["path"],
                        "status": "unavailable",
                        "error_type": type(error).__name__,
                    })
            view["working_archive_destination_after"] = destination_after
            view["working_archive_destination_hashes_postrun"] = "not_collected"
    except Exception as error:
        view["source_archives_after"] = []
        view["source_indexes_after"] = []
        view["source_archives_unchanged"] = False
        view["source_indexes_unchanged"] = False
        view["source_unchanged"] = False
        view["source_payload_integrity"] = "metadata-only-no-full-archive-hash"
        view["postcheck_error_type"] = type(error).__name__
    return view


def docker_command(output: Path, container: str, mode: str, source: Path | None,
                   cidfile: Path, label: str, *, display: int | None = None,
                   socket_path: Path | None = None, xauthority: Path | None = None,
                   trace_build_config: bool = False) -> list[str]:
    if trace_build_config and mode != "passive-real":
        raise TraceError("--trace-build-config requires --passive-real")
    command = [
        "docker", "run", "--rm", "--name", container, "--cidfile", str(cidfile),
        "--label", f"com.wowforever.trace-run={label}",
        "--user", "1000:1000", "--network", "none", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges", "--read-only", "--memory", "6g",
        "--cpus", "2", "--pids-limit", "128", "--tmpfs", "/tmp:rw,size=256m,mode=1777",
        "--workdir", "/lab",
        "--mount", f"type=bind,src={output / 'runtime'},dst=/lab",
        "--mount", f"type=bind,src={output / 'wow_trace.exe'},dst=/lab/wow_trace.exe,readonly",
    ]
    if mode == "synthetic":
        command.extend(["--mount", f"type=bind,src={output / 'synthetic_certstore_fixture.exe'},dst=/lab/synthetic_certstore_fixture.exe,readonly"])
    else:
        command.extend(["--mount", f"type=bind,src={output / 'game'},dst=/lab/game"])
        if display is None or socket_path is None or xauthority is None:
            raise TraceError("real Docker command requires the host-owned Xvfb display resources")
        command.extend([
            "--mount", f"type=bind,src={socket_path},dst=/tmp/.X11-unix/X{display},readonly",
            "--mount", f"type=bind,src={xauthority},dst=/lab/.xauthority,readonly",
        ])
    if source is not None:
        command.extend(["--mount", f"type=bind,src={source},dst=/source,readonly"])
    gui = ""
    environment = "export HOME=/lab/home XDG_CACHE_HOME=/lab/home/.cache WINEPREFIX=/lab/wineprefix DISPLAY=; "
    if mode in REAL_MODES:
        gui = (
            f"printf 'xserver_status=host-ready\\n'; export DISPLAY=:{display}; "
        )
        environment = "export HOME=/lab/home XDG_CACHE_HOME=/lab/home/.cache WINEPREFIX=/lab/wineprefix XAUTHORITY=/lab/.xauthority DISPLAY=; "
    tracer_command = f"wine /lab/wow_trace.exe --{mode}"
    if trace_build_config:
        tracer_command = f"WINEDEBUG=-all,+file {tracer_command}"
    command.extend([
        "--entrypoint", "/bin/sh", IMAGE, "-c",
        "set -u; cd /lab; "
        + environment
        + gui +
        "wine_version=$(wine --version 2>&1); "
        "timeout 60s wineboot --init; bootstrap_rc=$?; "
        "drive_c=false; system32=false; dos_c=false; dos_z=false; "
        "[ -d /lab/wineprefix/drive_c ] && drive_c=true; "
        "[ -d /lab/wineprefix/drive_c/windows/system32 ] && system32=true; "
        "[ -e /lab/wineprefix/dosdevices/c: ] && dos_c=true; "
        "[ -e /lab/wineprefix/dosdevices/z: ] && dos_z=true; "
        "printf 'bootstrap_wine_version=%s\\nbootstrap_wineboot_rc=%s\\nbootstrap_drive_c=%s\\nbootstrap_system32=%s\\nbootstrap_dos_c=%s\\nbootstrap_dos_z=%s\\n' "
        "\"$wine_version\" \"$bootstrap_rc\" \"$drive_c\" \"$system32\" \"$dos_c\" \"$dos_z\"; "
        "if [ \"$bootstrap_rc\" -ne 0 ] || [ \"$drive_c\" != true ] || [ \"$system32\" != true ] || [ \"$dos_c\" != true ] || [ \"$dos_z\" != true ]; then exit 125; fi; "
        f"{tracer_command}; status=$?; "
        "printf 'tracer_exit=%s\\n' \"$status\"; exit \"$status\"",
    ])
    return command


def remove_owned_container(cidfile: Path, expected_label: str) -> str | None:
    if not cidfile.is_file() or cidfile.is_symlink():
        return None
    cid = cidfile.read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", cid):
        return "invalid cidfile"
    try:
        inspected = subprocess.run(
            ["docker", "inspect", "--format", "{{.Id}}|{{.Name}}|{{index .Config.Labels \"com.wowforever.trace-run\"}}", cid],
            capture_output=True, text=True, timeout=30, check=False,
        )
        if inspected.returncode == 0:
            fields = inspected.stdout.strip().split("|", 2)
            if len(fields) != 3 or fields[0] != cid or fields[1] != f"/{expected_label}" or fields[2] != expected_label:
                return "owned-container identity/label mismatch"
            removed = subprocess.run(["docker", "rm", "-f", cid], capture_output=True,
                                     text=True, timeout=30, check=False)
            if removed.returncode:
                return f"docker rm failed: {removed.stderr[-500:]}"
    except (OSError, subprocess.TimeoutExpired) as error:
        return f"container cleanup failed: {error}"
    finally:
        try:
            cidfile.unlink()
        except FileNotFoundError:
            pass
    return None


def validate_capture_request(mode: str, capture_display: bool) -> None:
    if not capture_display:
        return
    if mode != "passive-real":
        raise TraceError("--capture-display requires --passive-real")
    if shutil.which("ffmpeg") is None:
        raise TraceError("--capture-display requires the host ffmpeg executable")


def _fixed_png(data: bytes) -> bool:
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n":
        return False
    if int.from_bytes(data[8:12], "big") != 13 or data[12:16] != b"IHDR":
        return False
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")
    return (width, height) == DISPLAY_CAPTURE_SIZE


def _capture_display_frame(output: Path, host_xvfb: HostXvfb, started: float, delay: int) -> dict[str, Any]:
    elapsed_ms = int((time.monotonic() - started) * 1000)
    result: dict[str, Any] = {
        "delay_seconds": delay,
        "elapsed_ms": elapsed_ms,
        "artifact": f"screenshots/progress-{delay}.png",
    }
    temp_dir: Path | None = None
    temp_file: Path | None = None
    try:
        if host_xvfb.process.poll() is not None:
            raise TraceError("xvfb_not_alive")
        screenshots = output / "screenshots"
        _reject_symlink_components(screenshots)
        if not screenshots.exists():
            screenshots.mkdir(mode=0o700, parents=False, exist_ok=False)
        os.chmod(screenshots, 0o700)
        final_path = screenshots / f"progress-{delay}.png"
        if os.path.lexists(final_path):
            raise TraceError("destination_exists")
        temp_dir = screenshots / f".capture-{delay}-{uuid.uuid4().hex}"
        temp_dir.mkdir(mode=0o700, parents=False, exist_ok=False)
        temp_file = temp_dir / "frame.png"
        ffmpeg = shutil.which("ffmpeg")
        if ffmpeg is None:
            raise TraceError("ffmpeg_unavailable")
        command = [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-n",
            "-f", "x11grab", "-video_size", "1280x720", "-i", f":{host_xvfb.display}",
            "-frames:v", "1", str(temp_file),
        ]
        environment = {
            "PATH": os.environ.get("PATH", ""),
            "DISPLAY": f":{host_xvfb.display}",
            "XAUTHORITY": str(host_xvfb.auth_path),
        }
        try:
            completed = subprocess.run(
                command, env=environment, stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=DISPLAY_CAPTURE_TIMEOUT, check=False,
            )
        except subprocess.TimeoutExpired as error:
            raise TraceError(type(error).__name__) from error
        if len(completed.stdout or b"") > DISPLAY_CAPTURE_OUTPUT_CAP or len(completed.stderr or b"") > DISPLAY_CAPTURE_OUTPUT_CAP:
            raise TraceError("ffmpeg_output_excess")
        if completed.returncode != 0:
            raise TraceError("ffmpeg_nonzero_exit")
        os.chmod(temp_file, 0o600)
        fd = os.open(temp_file, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            before = os.fstat(fd)
            if not stat.S_ISREG(before.st_mode) or before.st_size > DISPLAY_CAPTURE_PNG_CAP:
                raise TraceError("png_size_invalid")
            data = bytearray()
            while len(data) < before.st_size:
                chunk = os.read(fd, min(64 * 1024, before.st_size - len(data)))
                if not chunk:
                    raise TraceError("png_truncated")
                data.extend(chunk)
            after = os.fstat(fd)
            if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns
            ):
                raise TraceError("png_changed")
            if not _fixed_png(bytes(data)):
                raise TraceError("png_invalid")
            os.fsync(fd)
        finally:
            os.close(fd)
        try:
            os.link(temp_file, final_path, follow_symlinks=False)
        except FileExistsError as error:
            raise TraceError("destination_exists") from error
        os.chmod(final_path, 0o600)
        final_fd = os.open(final_path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            final_size = os.fstat(final_fd).st_size
            os.fsync(final_fd)
        finally:
            os.close(final_fd)
        _fsync_dir(screenshots)
        result.update({"status": "captured", "bytes": final_size})
        return result
    except Exception as error:
        result.update({"status": "failed", "error_type": str(error) if isinstance(error, TraceError) else type(error).__name__})
        return result
    finally:
        if temp_file is not None:
            try:
                temp_file.unlink()
            except FileNotFoundError:
                pass
        if temp_dir is not None:
            try:
                temp_dir.rmdir()
            except OSError:
                pass


BUILD_CONFIG_KEY = "05215079e3905ef5922ae0b03ffefb73"
TRACE_LINE_CAP = 8192
BUILD_CONFIG_RECORD_CAP = 64
BUILD_CONFIG_RECORD_LINE_CAP = 2048
BUILD_CONFIG_ARTIFACT_CAP = 128 * 1024


def _new_build_config_filter(started: float) -> dict[str, Any]:
    return {"started": started, "pending": bytearray(), "records": [], "dropped_count": 0}


def _sanitize_build_config_line(line: str) -> tuple[str, str, str]:
    matched = "build-info" if re.search(r"\.build\.info", line, re.IGNORECASE) else "config-key"
    operation_match = re.search(
        r"\b(CreateFile(?:W)?|ReadFile|WriteFile|CloseHandle|GetFileAttributes(?:W)?|NtOpenFile)\b",
        line, re.IGNORECASE,
    )
    operation = operation_match.group(1) if operation_match else "unknown"
    metadata = []
    for name in ("flags", "access", "desired_access", "share", "disposition", "status", "result"):
        match = re.search(rf"\b{name}\s*[=: ]\s*([A-Za-z0-9_|+.-]+)", line, re.IGNORECASE)
        if match:
            metadata.append(f"{name}={match.group(1)[:128]}")
    sanitized = f"operation={operation};file=<{matched}>"
    # Preserve only known technical path classes, not arbitrary quoted content.
    normalized = line.replace("\\\\", "\\").replace("/", "\\").lower()
    if "\\lab\\game\\..\\data\\" in normalized or "\\lab\\data\\" in normalized:
        path_class = "parent-data"
    elif "\\lab\\game\\data\\" in normalized:
        path_class = "game-data"
    elif "\\source\\data\\" in normalized:
        path_class = "source-data"
    else:
        path_class = "unresolved"
    sanitized += f";path_class={path_class}"
    if metadata:
        sanitized += ";" + ";".join(metadata)
    encoded = sanitized.encode("ascii", errors="replace")[:BUILD_CONFIG_RECORD_LINE_CAP]
    return encoded.decode("utf-8", errors="replace"), matched, operation


def _trace_line_record(state: dict[str, Any], line: bytes) -> bytes | None:
    if len(line) > TRACE_LINE_CAP:
        state["dropped_count"] += 1
        return None
    text = line.rstrip(b"\r\n").decode("utf-8", errors="replace")
    if text.startswith("{"):
        try:
            item = json.loads(text)
        except json.JSONDecodeError:
            item = None
        if isinstance(item, dict) and item.get("type") in {"launch", "scan", "event", "exception", "symbol_load", "final"}:
            return line
    if text.startswith(("bootstrap_", "xserver_status=", "tracer_exit=")):
        return line
    if not (re.search(r"\.build\.info", text, re.IGNORECASE) or BUILD_CONFIG_KEY.lower() in text.lower()):
        state["dropped_count"] += 1
        return None
    sanitized, matched, operation = _sanitize_build_config_line(text)
    records: list[dict[str, Any]] = state["records"]
    if len(records) < BUILD_CONFIG_RECORD_CAP:
        records.append({
            "timestamp_ms": int((time.monotonic() - state["started"]) * 1000),
            "bytes": len(line),
            "match": matched,
            "operation": operation,
            "line": sanitized,
        })
    else:
        state["dropped_count"] += 1
        return None
    return (sanitized + "\n").encode("utf-8")


def _consume_trace_chunk(state: dict[str, Any], chunk: bytes) -> list[bytes]:
    state["pending"].extend(chunk)
    selected: list[bytes] = []
    while b"\n" in state["pending"]:
        line, remainder = bytes(state["pending"]).split(b"\n", 1)
        state["pending"] = bytearray(remainder)
        filtered = _trace_line_record(state, line + b"\n")
        if filtered is not None:
            selected.append(filtered)
    if len(state["pending"]) > TRACE_LINE_CAP:
        state["pending"].clear()
        state["dropped_count"] += 1
    return selected


def _flush_trace_filter(state: dict[str, Any]) -> list[bytes]:
    if not state["pending"]:
        return []
    pending = bytes(state["pending"])
    state["pending"].clear()
    filtered = _trace_line_record(state, pending)
    return [filtered] if filtered is not None else []


def _write_build_config_artifact(output: Path, state: dict[str, Any]) -> dict[str, Any]:
    records = state["records"]
    artifact = {
        "version": 1,
        "source": "wine-file-debug",
        "known_suffix": ".build.info",
        "known_config_key": BUILD_CONFIG_KEY,
        "dropped_count": state["dropped_count"],
        "records": records,
    }
    encoded = json.dumps(artifact, separators=(",", ":"), sort_keys=True).encode() + b"\n"
    if len(encoded) > BUILD_CONFIG_ARTIFACT_CAP:
        for record in records:
            record["line"] = record["line"][:1024]
        encoded = json.dumps(artifact, separators=(",", ":"), sort_keys=True).encode() + b"\n"
    if len(encoded) > BUILD_CONFIG_ARTIFACT_CAP:
        artifact["records"] = records[:32]
        encoded = json.dumps(artifact, separators=(",", ":"), sort_keys=True).encode() + b"\n"
    if len(encoded) > BUILD_CONFIG_ARTIFACT_CAP:
        raise TraceError("build_config_artifact_cap")
    path = output / "build-config-file-ops.json"
    _write_exclusive(path, encoded)
    _fsync_dir(output)
    return {"artifact": path.name, "bytes": len(encoded), "records": len(artifact["records"]), "dropped_count": state["dropped_count"]}
def _collect_client_logs(output: Path) -> dict[str, Any]:
    """Copy only fixed, bounded diagnostics from the private game view."""
    game_root = output / "game"
    destination = output / "collected-real-logs"
    destination.mkdir(mode=0o700, exist_ok=False)
    collected: list[dict[str, Any]] = []

    def read_owned_bounded(path: Path) -> tuple[bytes, dict[str, int]] | None:
        try:
            relative = path.relative_to(game_root)
        except ValueError as error:
            raise TraceError(f"diagnostic path escaped private game root: {path}") from error
        ancestors = [game_root]
        current = game_root
        for part in relative.parts[:-1]:
            current /= part
            ancestors.append(current)
        snapshots: list[tuple[int, int, int, int]] = []
        try:
            for ancestor in ancestors:
                info = os.lstat(ancestor)
                if not stat.S_ISDIR(info.st_mode):
                    raise TraceError(f"diagnostic ancestor is not a directory: {ancestor}")
                snapshots.append((info.st_dev, info.st_ino, info.st_mode, info.st_mtime_ns))
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        except FileNotFoundError:
            return None
        except OSError as error:
            raise TraceError(f"cannot open diagnostic file {path}: {error}") from error
        try:
            before = os.fstat(fd)
            if not stat.S_ISREG(before.st_mode) or before.st_size > 128 * 1024:
                raise TraceError(f"diagnostic is not a bounded regular file: {path}")
            data = bytearray()
            while len(data) < before.st_size:
                chunk = os.read(fd, min(16 * 1024, before.st_size - len(data)))
                if not chunk:
                    raise TraceError(f"diagnostic truncated while reading: {path}")
                data.extend(chunk)
            after = os.fstat(fd)
        finally:
            os.close(fd)
        current_snapshots: list[tuple[int, int, int, int]] = []
        for ancestor in ancestors:
            info = os.lstat(ancestor)
            current_snapshots.append((info.st_dev, info.st_ino, info.st_mode, info.st_mtime_ns))
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
            after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns
        ) or snapshots != current_snapshots:
            raise TraceError(f"diagnostic changed while being collected: {path}")
        return bytes(data), {"bytes": before.st_size}

    for relative in ("Logs/gx.log", "Logs/General.log", "Logs/Tact.log"):
        source = game_root / relative
        try:
            result = read_owned_bounded(source)
        except TraceError:
            collected.append({"path": relative, "status": "rejected", "reason": "identity-or-bound"})
            continue
        if result is None:
            collected.append({"path": relative, "status": "missing"})
            continue
        data, metadata = result
        target = destination / Path(relative).name
        _write_exclusive(target, data)
        collected.append({"path": relative, "status": "collected", **metadata, "artifact": target.name, "sha256": hashlib.sha256(data).hexdigest()})
    error_dir = game_root / "Errors"
    error_candidates = []
    try:
        error_info = os.lstat(error_dir)
        if stat.S_ISDIR(error_info.st_mode):
            error_candidates = sorted(
                path for path in error_dir.iterdir()
                if not path.is_symlink()
                and re.fullmatch(r"\d{4}[-_]?\d{2}[-_]?\d{2}.*\.txt", path.name, re.IGNORECASE)
            )
    except FileNotFoundError:
        pass
    if len(error_candidates) > 1:
        collected.append({"path": "Errors/date.txt", "status": "rejected", "reason": "more-than-one-date-report"})
    elif error_candidates:
        source = error_candidates[0]
        result = read_owned_bounded(source)
        if result is None:
            collected.append({"path": f"Errors/{source.name}", "status": "missing"})
        else:
            data, metadata = result
            target = destination / source.name
            _write_exclusive(target, data)
            collected.append({"path": f"Errors/{source.name}", "status": "collected", **metadata, "artifact": target.name, "sha256": hashlib.sha256(data).hexdigest()})
    else:
        collected.append({"path": "Errors/date.txt", "status": "missing"})
    return {"directory": destination.name, "files": collected, "max_bytes": 128 * 1024}


def run_container(
    output: Path, mode: str, source: Path | None, *, capture_display: bool = False,
    trace_build_config: bool = False, copy_working_archives: bool = False,
) -> dict[str, Any]:
    validate_capture_request(mode, capture_display)
    if trace_build_config and mode != "passive-real":
        raise TraceError("--trace-build-config requires --passive-real")
    if copy_working_archives and mode != "passive-real":
        raise TraceError("--copy-working-archives requires --passive-real")
    prepare_runtime(output)
    data_view: dict[str, Any] | None = None
    if mode in REAL_MODES:
        if source is None:
            raise TraceError("real mode requires the fixed source client")
        data_view = prepare_real_data_view(output, source, copy_working_archives=copy_working_archives)
    label = f"x64-certstore-trace-{uuid.uuid4().hex}"
    container = label
    cidfile = output / "container.cid"
    if cidfile.exists() or cidfile.is_symlink():
        raise TraceError("refusing to reuse container cidfile")
    if mode in REAL_MODES:
        image_probe = subprocess.run(["docker", "image", "inspect", IMAGE], capture_output=True,
                                      text=True, timeout=30, check=False)
        if image_probe.returncode:
            raise TraceError("approved isolated Docker image is unavailable; no real client was launched")
    collision = subprocess.run(["docker", "inspect", container], capture_output=True,
                               text=True, timeout=30, check=False)
    if collision.returncode == 0:
        raise TraceError("generated container name unexpectedly already exists")
    host_xvfb: HostXvfb | None = None
    host_xvfb_report: dict[str, Any] | None = None
    if mode in REAL_MODES:
        host_xvfb = HostXvfb.start(output)
        try:
            command = docker_command(
                output, container, mode, source, cidfile, label,
                display=host_xvfb.display, socket_path=host_xvfb.socket_path,
                xauthority=host_xvfb.auth_path,
                trace_build_config=trace_build_config,
            )
        except BaseException:
            host_xvfb_report = host_xvfb.stop()
            raise
    else:
        command = docker_command(output, container, mode, source, cidfile, label)
    process: subprocess.Popen[bytes] | None = None
    captured = bytearray()
    truncated = False
    started = time.monotonic()
    deadline = started + RUN_SECONDS[mode]
    cleanup_error: str | None = None
    display_capture: list[dict[str, Any]] = []
    build_config_filter = _new_build_config_filter(started) if trace_build_config else None
    capture_index = 0
    poller = selectors.DefaultSelector()
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        assert process.stdout is not None
        poller.register(process.stdout, selectors.EVENT_READ)
        while process.poll() is None or poller.get_map():
            now = time.monotonic()
            while capture_display and capture_index < len(DISPLAY_CAPTURE_DELAYS) and now - started >= DISPLAY_CAPTURE_DELAYS[capture_index]:
                delay = DISPLAY_CAPTURE_DELAYS[capture_index]
                if process.poll() is not None:
                    display_capture.append({
                        "delay_seconds": delay, "elapsed_ms": int((now - started) * 1000),
                        "artifact": f"screenshots/progress-{delay}.png",
                        "status": "skipped", "error_type": "tracer_not_running",
                    })
                elif deadline - now < 8:
                    display_capture.append({
                        "delay_seconds": delay, "elapsed_ms": int((now - started) * 1000),
                        "artifact": f"screenshots/progress-{delay}.png",
                        "status": "skipped", "error_type": "capture_window_expired",
                    })
                elif host_xvfb is None or host_xvfb.process.poll() is not None:
                    display_capture.append({
                        "delay_seconds": delay, "elapsed_ms": int((now - started) * 1000),
                        "artifact": f"screenshots/progress-{delay}.png",
                        "status": "failed", "error_type": "xvfb_not_alive",
                    })
                else:
                    display_capture.append(_capture_display_frame(output, host_xvfb, started, delay))
                capture_index += 1
                now = time.monotonic()
            if process.poll() is None and time.monotonic() >= deadline:
                process.kill()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired as error:
                    cleanup_error = remove_owned_container(cidfile, label)
                    raise TraceError("container did not stop during cleanup") from error
            for key, _ in poller.select(timeout=0.2):
                chunk = key.fileobj.read1(16 * 1024)
                if not chunk:
                    if build_config_filter is not None:
                        for selected in _flush_trace_filter(build_config_filter):
                            room = OUTPUT_CAP - len(captured)
                            if room > 0:
                                captured.extend(selected[:room])
                            if len(selected) > room or len(captured) >= OUTPUT_CAP:
                                truncated = True
                    poller.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                selected_chunks = [chunk] if build_config_filter is None else _consume_trace_chunk(build_config_filter, chunk)
                for selected in selected_chunks:
                    room = OUTPUT_CAP - len(captured)
                    if room > 0:
                        captured.extend(selected[:room])
                    if len(selected) > room or len(captured) >= OUTPUT_CAP:
                        truncated = True
            if time.monotonic() >= deadline + 30:
                raise TraceError("bounded container cleanup deadline exceeded")
        assert process is not None
        process.wait(timeout=30)
    except BaseException:
        if process is not None and process.poll() is None:
            process.kill()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                pass
        cleanup_error = remove_owned_container(cidfile, label)
        raise
    finally:
        poller.close()
        cleanup_error = remove_owned_container(cidfile, label) or cleanup_error
        if host_xvfb is not None:
            host_xvfb_report = host_xvfb.stop()
    assert process is not None
    log = bytes(captured).decode(errors="replace")
    log_path = output / "tracer-container.log"
    _write_exclusive(log_path, bytes(captured))
    build_config_report = _write_build_config_artifact(output, build_config_filter) if build_config_filter is not None else None
    real_logs = _collect_client_logs(output) if mode in REAL_MODES else None
    if data_view is not None:
        data_view = finalize_real_data_view(data_view, source, output)
    result = {
        "container": container,
        "cidfile_cleanup_error": cleanup_error,
        "command": command,
        "exit_status": process.returncode,
        "output_truncated": truncated,
        "output_cap": OUTPUT_CAP,
        "log": log_path.name,
        "log_sha256": sha256(log_path),
        "duration_ms": int((time.monotonic() - started) * 1000),
        "protocol": log,
        "real_logs": real_logs,
        "host_xvfb": host_xvfb_report,
    }
    if data_view is not None:
        result["data_view"] = data_view
    if capture_display:
        while capture_index < len(DISPLAY_CAPTURE_DELAYS):
            delay = DISPLAY_CAPTURE_DELAYS[capture_index]
            display_capture.append({
                "delay_seconds": delay, "elapsed_ms": int((time.monotonic() - started) * 1000),
                "artifact": f"screenshots/progress-{delay}.png",
                "status": "skipped", "error_type": "tracer_not_running",
            })
            capture_index += 1
        result["display_capture"] = display_capture
    if build_config_report is not None:
        result["trace_build_config"] = build_config_report
    return result


def parse_protocol(text: str) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    messages: list[dict[str, Any]] = []
    final: dict[str, Any] | None = None
    for line in text.splitlines():
        if not line.startswith("{"):
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if item.get("type") in {"launch", "scan", "event", "exception", "symbol_load"}:
            messages.append(item)
        elif item.get("type") == "final":
            if final is not None:
                raise TraceError("duplicate final protocol message")
            final = item
    return messages, final


def parse_bootstrap(text: str) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for key, value in re.findall(r"^bootstrap_([a-z0-9_]+)=(.*)$", text, re.MULTILINE):
        values[key] = value
    for key in ("drive_c", "system32", "dos_c", "dos_z"):
        if key in values:
            values[key] = values[key] == "true"
    if "wineboot_rc" in values:
        try:
            values["wineboot_rc"] = int(values["wineboot_rc"])
        except ValueError:
            pass
    return values


def make_report(build: dict[str, Any], run: dict[str, Any], mode: str) -> dict[str, Any]:
    passive_mode = mode == "passive-real"
    watchpoint = {
        "armed_only_after_unique_complete_store": True,
        "requested_width": 8,
        "condition": "read-write",
        "event_cap": TRACE_EVENT_CAP,
        "detail_seconds": 30,
        "software_patch": False,
        "write_process_memory": False,
    }
    if passive_mode:
        watchpoint["enabled"] = False
    messages, final = parse_protocol(str(run["protocol"]))
    bootstrap = parse_bootstrap(str(run["protocol"]))
    scans = [item for item in messages if item.get("type") == "scan"]
    events = [item for item in messages if item.get("type") == "event"][:TRACE_EVENT_CAP]
    exceptions = [item for item in messages if item.get("type") == "exception"]
    if final and isinstance(final.get("exception_samples"), list):
        exceptions = final["exception_samples"][:TRACE_EXCEPTION_SAMPLE_CAP]
    symbol_loads = [item for item in messages if item.get("type") == "symbol_load"]
    launch_confirmed = any(
        item.get("type") == "launch"
        and item.get("fixed_child") is True
        and item.get("target") == "WowB.exe"
        and item.get("arguments") == "-d3d11 -config BetaTrace.wtf"
        for item in messages
    )
    xserver_status = next(
        (match.group(1) for match in re.finditer(r"^xserver_status=([^\n]+)$", str(run["protocol"]), re.MULTILINE)),
        "not-required" if mode == "synthetic" else "unknown",
    )
    host_gui = run.get("host_xvfb")
    valid_scan = bool(final and final.get("complete_unique_store") is True and any(
        scan.get("complete") is True and scan.get("unique") is True for scan in scans
    ))
    bounded_stacks = all(isinstance(event.get("stack"), list) and 0 < len(event["stack"]) <= 16 for event in events if event.get("access") in {"write", "read"})
    synthetic_observed = (
        mode == "synthetic"
        and final is not None
        and final.get("target_exit_seen") is True
        and final.get("target_exit_code") == 0
        and valid_scan
        and any(event.get("access") == "write" for event in events)
        and any(event.get("access") == "read" for event in events)
        and final.get("executable_pe_x64_verified") is True
        and final.get("identity", {}).get("owned_process_handle") is True
        and final.get("identity", {}).get("start_time_double_checked") is True
        and final.get("arm_errors") == 0
        and final.get("debugger_error") is False
        and final.get("unexpected_exceptions") == 0
        and final.get("unclassified_events") == 0
        and final.get("deadline_expired") is False
        and final.get("event_cap_exceeded") is False
        and isinstance(final.get("events"), int) and final["events"] <= TRACE_EVENT_CAP
        and isinstance(final.get("watch_traps"), int) and final["watch_traps"] <= TRACE_EVENT_CAP
        and final.get("synthetic_window_complete") is True
        and bounded_stacks
        and all(event.get("region") is not None for event in events if event.get("access") in {"write", "read"})
        and all(event.get("validation_counts_after_access") == {"ids": 12, "keys": 12, "flags": 12, "padding": 12}
                for event in events if event.get("access") in {"write", "read"})
        and not run["output_truncated"]
        and run["exit_status"] == 0
    )
    status = "observed" if synthetic_observed else "partial"
    report: dict[str, Any] = {
        "report_version": 1,
        "kind": "X64 WOW OWNPROCESS LAB TRACE",
        "status": status,
        "mode": mode,
        "image": IMAGE,
        "build": build,
        "run": {key: value for key, value in run.items() if key != "protocol"},
        "bootstrap": bootstrap,
        "gui": {
            "xserver": "host-Xvfb" if mode in REAL_MODES else None,
            "display": host_gui.get("display") if isinstance(host_gui, dict) else None,
            "status": xserver_status,
            "screen": "1280x720x24" if mode in REAL_MODES else None,
            "tcp_listen": False if mode in REAL_MODES else None,
            "provenance": host_gui if mode in REAL_MODES else None,
        },
        "contract": {
            "rows": TABLE_ROWS,
            "width": TABLE_WIDTH,
            "bytes": TABLE_BYTES,
            "complete_validation_required": True,
            "unique_valid_blocks": final.get("complete_unique_store") if final else None,
            "target_group_old_or_new_accepted": True,
            "padding_accepted": ["7f0000", "000000"],
            "values_reported": False,
        },
        "process": final or {"identity": "no final debugger protocol"},
        "scans": scans,
        "events": events,
        "exceptions": exceptions,
        "symbol_loads": symbol_loads,
        "protocol_messages": messages[:TRACE_EVENT_CAP + 2],
        "watchpoint": watchpoint,
        "exception_protocol": {
            "dispatch_cap": TRACE_EXCEPTION_DISPATCH_CAP,
            "sample_cap": TRACE_EXCEPTION_SAMPLE_CAP,
            "code_cap": TRACE_EXCEPTION_CODE_CAP,
            "status_for_unexpected": "DBG_EXCEPTION_NOT_HANDLED",
            "thread_name_notify_code": "0x406d1388",
        },
        "real_wow_launched": mode in REAL_MODES and launch_confirmed,
        "real_launch_confirmation": launch_confirmed if mode in REAL_MODES else False,
        "production_service_launched": False,
        "authentication_claim": False,
        "initializer_construction_proven": False,
        "limitations": [
            "A watch hit after discovery is POST_DISCOVERY and is not constructor proof.",
            "A generic copy is not classified as a constructor without a decoded access to the validated block.",
            "An incomplete scan or debugger protocol never establishes absence.",
            "--real is an explicit parent-review gate; this report does not claim login, rendering, or authentication.",
        ],
    }
    if passive_mode:
        report["detector_only"] = True
        report["store_observed"] = final.get("store_observed") if final and isinstance(final.get("store_observed"), bool) else None
        report["crypto_writes"] = final.get("crypto_writes") if final and isinstance(final.get("crypto_writes"), bool) else None
        report["limitations"].append(
            "--passive-real is detector-only: a store observation is not constructor, crypto-write, or authentication proof."
        )
    if mode == "passive-real" and "trace_build_config" in run:
        report["trace_build_config"] = {
            "requested": True,
            "source": "wine-file-debug",
            "known_suffix": ".build.info",
            "known_config_key": BUILD_CONFIG_KEY,
            **run["trace_build_config"],
        }
    if mode in REAL_MODES and "data_view" in run:
        report["data_view"] = run["data_view"]
    return report


def report_failure(output: Path, error: Exception, mode: str | None = None) -> Path:
    report = {
        "report_version": 1,
        "kind": "X64 WOW OWNPROCESS LAB TRACE",
        "status": "blocked",
        "mode": mode,
        "error_type": type(error).__name__,
        "error": str(error),
        "real_wow_launched": False,
        "production_service_launched": False,
        "authentication_claim": False,
    }
    path = output / "trace-report.json"
    _write_exclusive(path, (json.dumps(report, indent=2, sort_keys=True) + "\n").encode())
    _fsync_dir(output)
    return path


def run(
    mode: str, output: Path, client: Path | None, *, capture_display: bool = False,
    trace_build_config: bool = False, copy_working_archives: bool = False,
) -> dict[str, Any]:
    validate_capture_request(mode, capture_display)
    if trace_build_config and mode != "passive-real":
        raise TraceError("--trace-build-config requires --passive-real")
    if copy_working_archives and mode != "passive-real":
        raise TraceError("--copy-working-archives requires --passive-real")
    build = compile_images(output)
    source_metadata = None
    if mode in REAL_MODES:
        assert client is not None
        source_metadata = prepare_real_links(output, client)
    run_result = run_container(
        output, mode, client if mode in REAL_MODES else None, capture_display=capture_display,
        trace_build_config=trace_build_config,
        copy_working_archives=copy_working_archives,
    )
    report = make_report(build, run_result, mode)
    if source_metadata is not None:
        _, selected = _manifest_files(client)
        source_root = client.resolve(strict=True)
        selected_after = [_source_file_metadata(source_root / item["path"], item) for item in selected]
        after = _pe_identity(client / "WowB.exe")
        source_metadata["source_wowb_sha256_after"] = after["sha256"]
        source_metadata["source_selected_files_after"] = selected_after
        source_metadata["source_selected_files_unchanged"] = source_metadata["source_selected_files_before"] == selected_after
        build_info_after = _source_file_metadata(source_root / ".build.info", source_metadata["build_info_before"])
        source_metadata["build_info_after"] = build_info_after
        source_metadata["build_info_unchanged"] = build_info_after == source_metadata["build_info_before"]
        source_metadata["source_unchanged"] = (
            source_metadata["source_wowb_sha256_before"] == after["sha256"]
            and source_metadata["executable"]["bytes"] == after["bytes"]
            and source_metadata["source_selected_files_unchanged"]
            and source_metadata["build_info_unchanged"]
        )
        if not source_metadata["source_unchanged"]:
            report["status"] = "partial"
        report["source"] = source_metadata
    report_path = output / "trace-report.json"
    _write_exclusive(report_path, (json.dumps(report, indent=2, sort_keys=True) + "\n").encode())
    _fsync_dir(output)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--synthetic", action="store_true")
    modes.add_argument("--real", action="store_true")
    modes.add_argument("--passive-real", action="store_true")
    parser.add_argument("--capture-display", action="store_true")
    parser.add_argument("--trace-build-config", action="store_true")
    parser.add_argument("--copy-working-archives", action="store_true")
    parser.add_argument("--output-dir")
    args = parser.parse_args(argv)
    mode = "passive-real" if args.passive_real else ("real" if args.real else "synthetic")
    if args.capture_display and mode != "passive-real":
        parser.error("--capture-display requires --passive-real")
    if args.trace_build_config and mode != "passive-real":
        parser.error("--trace-build-config requires --passive-real")
    if args.copy_working_archives and mode != "passive-real":
        parser.error("--copy-working-archives requires --passive-real")
    if args.capture_display and shutil.which("ffmpeg") is None:
        parser.error("--capture-display requires the host ffmpeg executable")
    output: Path | None = None
    try:
        output = new_output(args.output_dir)
        report = run(
            mode, output, DEFAULT_CLIENT if mode in REAL_MODES else None,
            capture_display=args.capture_display,
            trace_build_config=args.trace_build_config,
            copy_working_archives=args.copy_working_archives,
        )
        print(json.dumps({"status": report["status"], "report": str(output / "trace-report.json")}))
        return 0 if report["status"] == "observed" else 1
    except Exception as error:
        if output is None:
            raise
        report_path = report_failure(output, error, mode)
        print(json.dumps({"status": "blocked", "report": str(report_path), "error": str(error)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
