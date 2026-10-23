#!/usr/bin/env python3
"""Run the Android launcher self-test without UI interaction.

The result is accepted only from a logcat line carrying this invocation's
unique run id.  The runner deliberately does not clear the device log buffer.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import selectors
import shutil
import signal
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence


PASS = 0
FAIL = 1
TIMEOUT = 2
_REPO_ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Config:
    serial: str
    apk: Path
    output_dir: Path
    adb: str
    package: str
    activity: str
    timeout: float
    run_id: str
    skip_install: bool


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _command(adb: str, serial: str, *args: str) -> list[str]:
    return [adb, "-s", serial, *args]


def _write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _terminate(process: subprocess.Popen[bytes]) -> bytes:
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
        try:
            stdout, _ = process.communicate(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            stdout, _ = process.communicate()
    else:
        stdout, _ = process.communicate()
    return stdout or b""


def _logcat_lines(
    process: subprocess.Popen[bytes], deadline: float, run_id: str
) -> tuple[list[str], bool]:
    """Read logcat until a deadline, without accepting a pre-existing line."""
    selector = selectors.DefaultSelector()
    assert process.stdout is not None
    selector.register(process.stdout, selectors.EVENT_READ)
    lines: list[str] = []
    pending = b""
    timed_out = False

    def consume(data: bytes) -> None:
        nonlocal pending
        pending += data
        chunks = pending.split(b"\n")
        pending = chunks.pop()
        lines.extend(chunk.decode("utf-8", errors="replace").rstrip("\r") for chunk in chunks)

    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                timed_out = True
                break
            events = selector.select(remaining)
            if not events:
                timed_out = True
                break
            for key, _ in events:
                data = os.read(key.fd, 65536)
                if data:
                    consume(data)
                    if _result_for_run(lines, run_id)[0] is not None:
                        break
                else:
                    selector.unregister(key.fileobj)
            if _result_for_run(lines, run_id)[0] is not None:
                break
            if process.poll() is not None and not selector.get_map():
                break
    finally:
        selector.close()

    if timed_out:
        consume(_terminate(process))
    else:
        consume(_terminate(process) if process.poll() is None else process.communicate()[0] or b"")
    if pending:
        lines.append(pending.decode("utf-8", errors="replace").rstrip("\r"))
    return lines, timed_out


def _result_for_run(lines: Sequence[str], run_id: str) -> tuple[str | None, str | None]:
    """Return PASS/FAIL only for the exact run id; old logcat is ignored."""
    for line in lines:
        tokens = line.split()
        if f"run_id={run_id}" not in tokens or "phase=selftest" not in tokens:
            continue
        for result in ("PASS", "FAIL"):
            if f"result={result}" in tokens:
                return result, line
    return None, None


def run_smoke(config: Config) -> int:
    run_dir = config.output_dir / config.run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    logcat_path = run_dir / "logcat.txt"
    command_path = run_dir / "commands.txt"
    result_path = run_dir / "result.json"
    started = _now()
    metadata: dict[str, object] = {
        "run_id": config.run_id,
        "serial": config.serial,
        "package": config.package,
        "apk": str(config.apk.resolve()),
        "started_at": started,
        "status": "RUNNING",
    }
    _write_json(run_dir / "run.json", metadata)
    commands: list[str] = []
    logcat_lines: list[str] = []
    status = "FAIL"
    exit_code = FAIL
    matched_line: str | None = None
    error: str | None = None
    logcat: subprocess.Popen[bytes] | None = None

    def adb_call(args: Sequence[str], timeout: float = 60) -> subprocess.CompletedProcess[str]:
        command = _command(config.adb, config.serial, *args)
        commands.append(" ".join(command))
        return subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)

    try:
        logcat_command = _command(
            config.adb,
            config.serial,
            "logcat",
            "-T",
            "1",
            "-v",
            "threadtime",
            "-s",
            "ForeverJNI:I",
            "*:S",
        )
        commands.append(" ".join(logcat_command))
        logcat = subprocess.Popen(logcat_command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

        if not config.skip_install:
            if not config.apk.is_file():
                raise RuntimeError(f"APK no existe: {config.apk}")
            installed = adb_call(("install", "-r", str(config.apk)))
            (run_dir / "install.stdout").write_text(installed.stdout, encoding="utf-8")
            (run_dir / "install.stderr").write_text(installed.stderr, encoding="utf-8")
            if installed.returncode != 0:
                raise RuntimeError(f"adb install terminó con código {installed.returncode}")

        started_activity = adb_call(
            (
                "shell",
                "am",
                "force-stop",
                config.package,
            )
        )
        if started_activity.returncode != 0:
            raise RuntimeError(f"force-stop terminó con código {started_activity.returncode}")
        launched = adb_call(
            (
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                f"{config.package}/{config.activity}",
                "--ez",
                "run_selftest",
                "true",
                "--es",
                "run_id",
                config.run_id,
            )
        )
        (run_dir / "launch.stdout").write_text(launched.stdout, encoding="utf-8")
        (run_dir / "launch.stderr").write_text(launched.stderr, encoding="utf-8")
        if launched.returncode != 0:
            raise RuntimeError(f"am start terminó con código {launched.returncode}")

        logcat_lines, timed_out = _logcat_lines(
            logcat, time.monotonic() + config.timeout, config.run_id
        )
        logcat = None
        if timed_out:
            status = "TIMEOUT"
            exit_code = TIMEOUT
        else:
            result, matched_line = _result_for_run(logcat_lines, config.run_id)
            if result == "PASS":
                status = "PASS"
                exit_code = PASS
            elif result == "FAIL":
                status = "FAIL"
                exit_code = FAIL
            else:
                error = "no se encontró resultado selftest para este run_id"
    except subprocess.TimeoutExpired as exc:
        status = "TIMEOUT"
        exit_code = TIMEOUT
        error = f"timeout ejecutando adb: {exc}"
    except (OSError, RuntimeError) as exc:
        error = str(exc)
    finally:
        if logcat is not None:
            logcat_lines.extend(line for line in _terminate(logcat).decode("utf-8", errors="replace").splitlines())
        logcat_path.write_text("\n".join(logcat_lines) + ("\n" if logcat_lines else ""), encoding="utf-8")
        command_path.write_text("\n".join(commands) + ("\n" if commands else ""), encoding="utf-8")
        if error is not None:
            metadata["error"] = error
        metadata.update(
            {
                "finished_at": _now(),
                "status": status,
                "exit_code": exit_code,
                "matched_line": matched_line,
                "evidence_dir": str(run_dir.resolve()),
            }
        )
        _write_json(result_path, metadata)

    print(f"{status} run_id={config.run_id} evidence={run_dir}")
    return exit_code


def _default_adb() -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidate = Path(sdk) / "platform-tools/adb"
        if candidate.exists():
            return str(candidate)
    return (
        shutil.which("adb")
        or ("/home/adrian/.local/opt/android-sdk/platform-tools/adb"
            if Path("/home/adrian/.local/opt/android-sdk/platform-tools/adb").exists()
            else "adb")
    )


def parse_args(argv: Sequence[str]) -> Config:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="serie adb; nunca se elige por defecto")
    parser.add_argument(
        "--apk",
        type=Path,
        default=Path(__file__).parents[1] / "android/app/build/outputs/apk/debug/app-debug.apk",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("/home/adrian/wowforever-artifacts/android-launcher/smoke"),
    )
    parser.add_argument("--adb", default=_default_adb())
    parser.add_argument("--package", default="com.wowforever.launcher")
    parser.add_argument("--activity", default=".MainActivity")
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--skip-install", action="store_true")
    args = parser.parse_args(argv)
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout debe ser un número finito positivo")
    output_dir = args.output_dir.resolve()
    if output_dir == _REPO_ROOT or _REPO_ROOT in output_dir.parents:
        parser.error("--output-dir debe estar fuera del repositorio")
    run_id = f"run-{uuid.uuid4().hex}"
    return Config(
        serial=args.serial,
        apk=args.apk,
        output_dir=output_dir,
        adb=args.adb,
        package=args.package,
        activity=args.activity,
        timeout=args.timeout,
        run_id=run_id,
        skip_install=args.skip_install,
    )


def main(argv: Sequence[str] | None = None) -> int:
    return run_smoke(parse_args(argv if argv is not None else sys.argv[1:]))


if __name__ == "__main__":
    raise SystemExit(main())
