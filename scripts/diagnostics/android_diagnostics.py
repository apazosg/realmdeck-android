#!/usr/bin/env python3
"""Small, read-only ADB capture runner for the FrontMage SlotProbe.

The runner deliberately does not install files, alter a container, click the UI,
or kill processes.  It only starts the selected Android activity (without
force-stopping it) and waits for a human to run the already-deployed probe.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Sequence


DEFAULT_ADB = "/home/adrian/.local/opt/platform-tools/adb"
DEFAULT_PROBE = "/home/adrian/wowforever-artifacts/frontmage-ab/slot-probe/SlotProbe.exe"
DEFAULT_SOURCE = "/home/adrian/wowforever-artifacts/frontmage-ab/slot-probe/SlotProbe.c"
DEFAULT_PREFIX = "/data/user/0/{package}/files/imagefs/home/xuser-1/.wine"
DEFAULT_PROBE_PATH = "{prefix}/drive_c/wow/diag/slot-probe.txt"
DEFAULT_TIMEOUT = 75
MAX_LAUNCH_ATTEMPTS = 2


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_run_id(value: str | None) -> str:
    if value is None:
        return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", value):
        raise ValueError("run-id must contain only letters, digits, '.', '_' or '-'")
    return value


@dataclass(frozen=True)
class CommandResult:
    argv: tuple[str, ...]
    returncode: int
    stdout: bytes
    stderr: bytes


class AdbClient:
    """ADB wrapper that makes serial selection explicit for every invocation."""

    def __init__(
        self,
        adb: str,
        serial: str,
        *,
        runner: Callable[..., subprocess.CompletedProcess[bytes]] = subprocess.run,
    ) -> None:
        self.adb = adb
        self.serial = serial
        self._runner = runner
        self.records: list[dict[str, object]] = []

    def run(
        self,
        args: Sequence[str],
        *,
        timeout: int = 30,
        stdin: bytes | None = None,
        keep_output: bool = True,
    ) -> CommandResult:
        argv = (self.adb, "-s", self.serial, *map(str, args))
        try:
            completed = self._runner(
                list(argv),
                input=stdin,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                check=False,
            )
            result = CommandResult(
                argv=argv,
                returncode=completed.returncode,
                stdout=completed.stdout or b"",
                stderr=completed.stderr or b"",
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            result = CommandResult(argv=argv, returncode=125, stdout=b"", stderr=str(error).encode())

        record: dict[str, object] = {
            "argv": list(argv),
            "returncode": result.returncode,
            "stdout_bytes": len(result.stdout),
            "stderr_bytes": len(result.stderr),
            "stdout_sha256": sha256_bytes(result.stdout),
            "stderr_sha256": sha256_bytes(result.stderr),
        }
        if keep_output:
            record["stdout"] = result.stdout.decode("utf-8", errors="replace")
            record["stderr"] = result.stderr.decode("utf-8", errors="replace")
        self.records.append(record)
        return result

    def shell(self, *args: str, **kwargs: object) -> CommandResult:
        return self.run(("shell", *args), **kwargs)


def shell_text(result: CommandResult) -> str:
    return result.stdout.decode("utf-8", errors="replace").strip()


def package_version(dumpsys: str) -> dict[str, str | None]:
    version_name = re.search(r"\bversionName=([^\s]+)", dumpsys)
    version_code = re.search(r"\bversionCode=([^\s]+)", dumpsys)
    return {
        "version_name": version_name.group(1) if version_name else None,
        "version_code": version_code.group(1) if version_code else None,
    }


def resolve_activity(output: str, package: str) -> str | None:
    for line in output.splitlines():
        candidate = line.strip()
        if "/" in candidate and candidate.startswith(package + "/"):
            return candidate
    return None


def stat_probe(client: AdbClient, package: str, remote_path: str) -> tuple[bool, dict[str, str | int], str]:
    result = client.shell(
        "run-as",
        package,
        "stat",
        "-c",
        "%Y:%s",
        remote_path,
    )
    text = shell_text(result)
    if result.returncode != 0:
        return False, {"error": text or result.stderr.decode(errors="replace").strip()}, text
    match = re.fullmatch(r"(\d+):(\d+)", text)
    if not match:
        return False, {"error": "unexpected stat output", "raw": text}, text
    return True, {"mtime_epoch": int(match.group(1)), "size": int(match.group(2))}, text


def read_probe(client: AdbClient, package: str, remote_path: str) -> tuple[bool, bytes, str]:
    result = client.shell("run-as", package, "cat", remote_path, timeout=90)
    if result.returncode != 0:
        return False, b"", result.stderr.decode("utf-8", errors="replace").strip()
    return True, result.stdout, ""


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def ensure_output_dir(path: Path, repo_root: Path) -> None:
    resolved = path.expanduser().resolve()
    root = repo_root.resolve()
    if resolved == root or root in resolved.parents:
        raise ValueError(f"output-dir must be outside repository: {resolved}")
    resolved.mkdir(parents=True, exist_ok=True)


def extract_slots(text: str) -> list[str]:
    return re.findall(r"SLOT-CHANGE .*?\bslot=(0x[0-9a-fA-F]+)", text)


def exact_human_instruction() -> str:
    return (
        "En com.winlator.llm abre Container-1 y Frontend, abre/selecciona "
        "C:\\wow\\diag\\SlotProbe.exe una sola vez y espera hasta 60 s; no cambies "
        "configuración ni cierres WowB. Después vuelve aquí y pulsa Enter."
    )


def capture(
    *,
    adb_path: str,
    serial: str,
    package: str,
    output_dir: Path,
    probe_path: str,
    probe_exe: Path,
    source: Path,
    wait_seconds: int = DEFAULT_TIMEOUT,
    run_id: str | None = None,
    runner: Callable[..., subprocess.CompletedProcess[bytes]] = subprocess.run,
    input_fn: Callable[[str], str] = input,
    output_fn: Callable[[str], None] = print,
    sleep_fn: Callable[[float], None] = time.sleep,
) -> dict[str, object]:
    """Run one bounded capture; all mutations are confined to output_dir."""
    if wait_seconds < 1:
        raise ValueError("wait-seconds must be positive")
    started = time.monotonic()
    started_at = utc_now()
    client = AdbClient(adb_path, serial, runner=runner)

    # Device selection is the first remote operation.  No package/UI action is
    # attempted until this exact serial reports the expected state.
    state = client.run(("get-state",), timeout=15)
    if state.returncode != 0 or shell_text(state) != "device":
        raise RuntimeError(f"ADB device {serial!r} is not ready: {shell_text(state)!r}")

    run_name = safe_run_id(run_id)
    run_dir = output_dir.expanduser().resolve() / run_name
    run_dir.mkdir(parents=True, exist_ok=False)
    client.records[0]["phase"] = "device-preflight"

    package_path = client.shell("pm", "path", package, keep_output=True)
    package_dump = client.shell("dumpsys", "package", package, keep_output=False)
    version = package_version(shell_text(package_dump))
    activity_result = client.shell("cmd", "package", "resolve-activity", "--brief", package)
    activity = resolve_activity(shell_text(activity_result), package)

    exists, stat_info, stat_raw = stat_probe(client, package, probe_path)
    initial_snapshot: dict[str, object] = {
        "exists": exists,
        "stat": stat_info,
        "stat_raw": stat_raw,
        "captured_at": utc_now(),
    }
    write_json(run_dir / "preflight.json", {
        "serial": serial,
        "package": package,
        "package_path": shell_text(package_path),
        "package_version": version,
        "resolved_activity": activity,
        "probe_path": probe_path,
        "initial_snapshot": initial_snapshot,
    })

    result_status = "stale-result" if exists else "missing-result"
    probe_bytes = b""
    final_snapshot: dict[str, object] = initial_snapshot
    launch_attempts = 0
    instruction = exact_human_instruction()

    if exists:
        # A pre-existing file is evidence only of the prior run.  It is copied
        # for inspection, but never treated as a fresh probe result.
        read_ok, probe_bytes, read_error = read_probe(client, package, probe_path)
        if not read_ok:
            final_snapshot = {**initial_snapshot, "read_error": read_error}
    elif activity is None:
        result_status = "blocked-no-activity"
    else:
        for attempt in range(1, MAX_LAUNCH_ATTEMPTS + 1):
            launch_attempts = attempt
            launch = client.shell("am", "start", "-W", "-n", activity, timeout=30)
            client.records[-1]["phase"] = f"ui-launch-{attempt}"
            output_fn(f"UI launch attempt {attempt}/{MAX_LAUNCH_ATTEMPTS}: {shell_text(launch)!r}")
            output_fn(instruction)
            try:
                input_fn("Pulsa Enter cuando termine la ejecución manual (o para continuar): ")
            except EOFError:
                result_status = "blocked-human-action-required"
                break

            deadline = time.monotonic() + wait_seconds
            while time.monotonic() < deadline:
                present, candidate_stat, candidate_raw = stat_probe(client, package, probe_path)
                if present:
                    read_ok, candidate_bytes, read_error = read_probe(client, package, probe_path)
                    if read_ok:
                        probe_bytes = candidate_bytes
                        final_snapshot = {
                            "exists": True,
                            "stat": candidate_stat,
                            "stat_raw": candidate_raw,
                            "captured_at": utc_now(),
                        }
                        result_status = "fresh-result"
                        break
                    final_snapshot = {
                        "exists": True,
                        "stat": candidate_stat,
                        "stat_raw": candidate_raw,
                        "read_error": read_error,
                    }
                sleep_fn(1.0)
            if result_status == "fresh-result":
                break
            result_status = "blocked-human-action-required"

    if probe_bytes:
        (run_dir / "slot-probe.txt").write_bytes(probe_bytes)
        (run_dir / "slot-probe.sha256").write_text(sha256_bytes(probe_bytes) + "\n", encoding="ascii")

    config_without_hash: dict[str, object] = {
        "run_id": run_name,
        "serial": serial,
        "package": package,
        "adb_path": str(Path(adb_path).expanduser()),
        "package_version": version,
        "resolved_activity": activity,
        "probe_path": probe_path,
        "probe_exe": {"path": str(probe_exe), "sha256": sha256_file(probe_exe)},
        "source": {"path": str(source), "sha256": sha256_file(source)},
        "launch_attempt_limit": MAX_LAUNCH_ATTEMPTS,
        "wait_seconds": wait_seconds,
        "read_only_probe": True,
    }
    config_bytes = json.dumps(config_without_hash, sort_keys=True, separators=(",", ":")).encode()
    config = {**config_without_hash, "config_sha256": sha256_bytes(config_bytes)}
    write_json(run_dir / "config.json", config)
    write_json(run_dir / "final-snapshot.json", final_snapshot)

    ended_at = utc_now()
    summary: dict[str, object] = {
        "run_id": run_name,
        "status": result_status,
        "serial": serial,
        "package": package,
        "package_version": version,
        "launch_attempts": launch_attempts,
        "probe_file": str(run_dir / "slot-probe.txt") if probe_bytes else None,
        "slot_values_read": extract_slots(probe_bytes.decode("utf-8", errors="replace")) if probe_bytes else [],
        "started_at": started_at,
        "ended_at": ended_at,
        "duration_ms": round((time.monotonic() - started) * 1000),
        "config_sha256": config["config_sha256"],
        "human_instruction": instruction if result_status.startswith("blocked") else None,
    }
    write_json(run_dir / "summary.json", summary)
    with (run_dir / "adb-commands.jsonl").open("w", encoding="utf-8") as log:
        for record in client.records:
            log.write(json.dumps(record, sort_keys=True) + "\n")
    output_fn(f"RESULT {json.dumps(summary, sort_keys=True)}")
    return summary


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--adb", default=DEFAULT_ADB)
    result.add_argument("--serial", required=True, help="explicit ADB device serial")
    result.add_argument("--package", required=True, help="explicit Android package")
    result.add_argument("--output-dir", required=True, type=Path, help="new run directory parent, outside this repository")
    result.add_argument("--remote-probe-path", dest="probe_path")
    result.add_argument("--probe-exe", type=Path, default=Path(DEFAULT_PROBE))
    result.add_argument("--source", type=Path, default=Path(DEFAULT_SOURCE))
    result.add_argument("--wait-seconds", type=int, default=DEFAULT_TIMEOUT)
    result.add_argument("--run-id")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    prefix = DEFAULT_PREFIX.format(package=args.package)
    probe_path = args.probe_path or DEFAULT_PROBE_PATH.format(prefix=prefix)
    try:
        ensure_output_dir(args.output_dir, Path(__file__).resolve().parents[2])
        capture(
            adb_path=args.adb,
            serial=args.serial,
            package=args.package,
            output_dir=args.output_dir,
            probe_path=probe_path,
            probe_exe=args.probe_exe,
            source=args.source,
            wait_seconds=args.wait_seconds,
            run_id=args.run_id,
        )
    except (OSError, RuntimeError, ValueError) as error:
        print(f"BLOCKED {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
