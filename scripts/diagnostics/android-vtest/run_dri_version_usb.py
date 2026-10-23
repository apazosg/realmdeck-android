#!/usr/bin/env python3
"""Bounded HOST runner for the fixed DRI core-version limit observation."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import stat
import uuid
from pathlib import Path
from typing import Any

import run_dri_observer_usb as old_dri


# The old runner remains the authority for the reviewed orchestration and the
# return-observer common grammar.  This module supplies only private DI seams;
# it never changes the old module's globals.
context = old_dri.context
guest = old_dri.guest
negotiation = old_dri.negotiation
DIRECTORY = Path(__file__).parent

BASE = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/dri-version-limit")
NAME = "GuestEglDriVersion"
OBSERVER = "libdri_version_observer.so"
PROVIDER = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
    "virgl-audit/private_egl_candidate-v1/lib/libgallium-26.2.4.so"
)
PROVIDER_BYTES = 17_656_040
PROVIDER_SHA256 = "910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5"
GUEST_MANIFEST_SHA256 = "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9"
GUEST_TREE_SHA256 = "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216"
NEGOTIATION_MANIFEST_SHA256 = "e1aa871dc22f53e1a9bc68fd86a4d9838c4138d2c9ecf9a7da2d83b2cee37ba3"

ARTIFACT_PINS = {
    NAME: (36_736, "f23e616bfc20fe63249c8ac47004587bf83bddc81ed0f257edb859e405ab8a37"),
    OBSERVER: (25_456, "46d7aaa9be73557bb9129b72a4b3b5ea38cafdd2feda428c999b114aafc222cb"),
}
SOURCE_PINS = {
    "GuestEglContext.c": "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f",
    "GuestEglLoader.c": "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6",
    "guest-egl-local-logs.patch": "46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d",
    "DriVersionObserver.c": "0cd2c64d30d577bce2853095c00f7f8f3fa096df417db2ae356ff152ff102330",
    "guest-egl-dri-version.patch": "a7f9b09745d58563d98a21bc3af0f4c06fdb2e70f5058d8440dd9195c0132da9",
    "build_dri_version_observer.sh": "d600bf75c9f984f7abfbc581a416f9bac553316d54f6ee3eeb572df758a56aaa",
}

PREFIX = b"WFDRI_VERSION "
SCHEMA = "wowforever.android-dri-version-observer/version1"
PASS_STATUS = "DRI_VERSION_CONTROL_OBSERVED"
MEASUREMENT_UNKNOWN = "MEASUREMENT_UNKNOWN"
TRACE_NOT_OBSERVED = "TRACE_NOT_OBSERVED"
INFERENCES = {
    "EARLY_CHECK_WOULD_REJECT",
    "NOT_EXPLAINED_BY_EARLY_CHECK",
    "NO_BAD_VERSION",
    "UNKNOWN",
}
OLD_FIELDS = set(old_dri.FIELDS)
FIELDS = OLD_FIELDS | {
    "layoutVerified", "scopeMatches", "requestedVersion", "maximumBefore", "maximumAfter",
    "beforeReadBytes", "afterReadBytes", "beforeReadErrno", "afterReadErrno",
    "measurementValid", "checkInference",
}
U32_MAX = 2**32 - 1


def _is_int(value: Any) -> bool:
    return type(value) is int


def artifact(name: str, destination: str, *, executable: bool) -> dict[str, Any]:
    if set(ARTIFACT_PINS) != {NAME, OBSERVER} or set(SOURCE_PINS) != {
        "GuestEglContext.c", "GuestEglLoader.c", "guest-egl-local-logs.patch",
        "DriVersionObserver.c", "guest-egl-dri-version.patch", "build_dri_version_observer.sh",
    }:
        raise ValueError("DRI version artifact authority is not installed")
    for source, digest in SOURCE_PINS.items():
        path = DIRECTORY / source
        info = context._regular_nosymlink(path)
        if info.st_size > 1024 * 1024 or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError("DRI version source authority changed: " + source)
    if name not in ARTIFACT_PINS:
        raise ValueError("unknown DRI version artifact")
    size, digest = ARTIFACT_PINS[name]
    path = BASE / name
    info = context._regular_nosymlink(path)
    if info.st_size != size or not 0 < size <= 64 * 1024 or stat.S_IMODE(info.st_mode) != 0o755:
        raise ValueError("DRI version artifact size/mode changed")
    raw = path.read_bytes()
    if len(raw) != size or hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError("DRI version artifact hash changed")
    guest.loader._elf64_aarch64_dyn(raw, name, require_linker_interp=executable)
    guest._validate_native_dynamic(raw)
    return {
        "destination": destination, "path": path, "bytes": size, "sha256": digest,
        "mode": "0700" if executable else "0600",
    }


def probe_file() -> dict[str, Any]:
    return artifact(NAME, "guest/bin/" + NAME, executable=True)


def observer_file() -> dict[str, Any]:
    return artifact(OBSERVER, "guest/observer/" + OBSERVER, executable=False)


def _provider_is_pinned(item: dict[str, Any]) -> bool:
    path = item.get("path")
    return (
        item.get("destination") == "guest/lib/libgallium-26.2.4.so"
        and item.get("bytes") == PROVIDER_BYTES
        and item.get("sha256") == PROVIDER_SHA256
        and isinstance(path, Path)
        and path.absolute() == PROVIDER.absolute()
    )


def validate_copy_inputs(files: list[dict[str, Any]], probe: dict[str, Any]) -> None:
    if guest.MANIFEST_SHA256 != GUEST_MANIFEST_SHA256 or guest.TREE_SHA256 != GUEST_TREE_SHA256:
        raise ValueError("DRI version copier requires the fixed guest manifest/tree")
    if negotiation.source_runner.NEGOTIATION.manifest_sha256 != NEGOTIATION_MANIFEST_SHA256:
        raise ValueError("DRI version copier requires the fixed negotiation server")
    guest._validate_copy_inputs(files, {"destination": "guest/bin/GuestEglLoader", "mode": "0700"})
    if not any(_provider_is_pinned(item) for item in files):
        raise ValueError("DRI version copier requires the fixed Gallium provider")
    if probe != probe_file():
        raise ValueError("DRI version copier requires the fixed native child")
    observer_file()


def copy_payload(*args: Any, **kwargs: Any) -> tuple[str, dict[str, Any]]:
    relative, info = negotiation.copy_payload_to_existing_run(
        *args, **kwargs, probe_input_validator=validate_copy_inputs,
    )
    item = observer_file()
    stage = guest.STAGE_PREFIX + uuid.uuid4().hex
    staged = stage + "/" + OBSERVER
    directory = relative + "/guest/observer"
    target = relative + "/" + item["destination"]
    created = False
    cleanup_errors: list[str] = []
    try:
        guest.loader.require_adb(guest.loader.adb("shell", "mkdir", "-m", "755", stage), "DRI version staging")
        created = True
        guest.loader.require_adb(guest.loader.adb("push", str(item["path"]), staged, timeout=30), "DRI version sync")
        guest.loader.require_adb(guest.loader.adb("shell", "chmod", "0644", staged), "DRI version staged mode")
        commands = [
            "umask 077", "set -e", "set -C",
            f"test -d {guest._q(relative + '/guest')} && test ! -L {guest._q(relative + '/guest')}",
            f"test ! -e {guest._q(directory)} && test ! -L {guest._q(directory)}",
            f"mkdir -m 700 {guest._q(directory)}",
            f"test ! -e {guest._q(target)} && test ! -L {guest._q(target)}",
            f"cat {guest._q(staged)} > {guest._q(target)}",
            f"chmod 0600 {guest._q(target)}", "printf DRI_VERSION_OBSERVER_UPLOAD_OK",
        ]
        uploaded = guest.loader.adb(
            "exec-out", "run-as", context.PACKAGE, "sh", "-c", "; ".join(commands), timeout=30,
        )
        if uploaded.returncode or uploaded.stdout != b"DRI_VERSION_OBSERVER_UPLOAD_OK" or uploaded.stderr:
            raise RuntimeError("DRI version exclusive upload sentinel was not exact")
        guest._device_check(target, item["sha256"], item["mode"])
    finally:
        if created:
            for operation in (("rm", "-f", staged), ("rmdir", stage)):
                try:
                    result = guest.loader.adb("shell", *operation)
                    if result.returncode or result.stdout or result.stderr:
                        cleanup_errors.append(operation[0])
                except Exception as exc:
                    cleanup_errors.append(type(exc).__name__)
            if cleanup_errors:
                raise RuntimeError("owned DRI version staging cleanup failed: " + ",".join(cleanup_errors))
    info["observer"] = {key: item[key] for key in ("destination", "bytes", "sha256", "mode")}
    info["observerStagingClean"] = True
    return relative, info


def native_command(private_root: str, run: str) -> list[str]:
    if not re.fullmatch(r"VTEST-[0-9a-f]{32}", run):
        raise ValueError("invalid DRI version RUN")
    if private_root not in (
        f"/data/user/0/{context.PACKAGE}/{context.PRIVATE_PREFIX}/{run}",
        f"/data/data/{context.PACKAGE}/{context.PRIVATE_PREFIX}/{run}",
    ):
        raise ValueError("invalid DRI version private root")
    root = private_root + "/guest"
    values = [
        "PATH=/system/bin:/system/xbin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LD_LIBRARY_PATH=" + root + "/lib:/system/lib64", "TMPDIR=" + private_root,
        "__EGL_VENDOR_LIBRARY_FILENAMES=" + root + "/share/glvnd/egl_vendor.d/50_mesa.json",
        "GALLIUM_DRIVER=virpipe", "VTEST_SOCKET_NAME=vtest.sock",
        "EGL_LOG_LEVEL=debug", "MESA_LOG=file", "MESA_LOG_LEVEL=debug",
        "LD_PRELOAD=" + root + "/observer/" + OBSERVER,
    ]
    inner = (
        f"cd {shlex.quote(private_root)} && exec /system/bin/env -i "
        + " ".join(map(shlex.quote, values))
        + " /system/bin/linker64 " + shlex.quote(root + "/bin/" + NAME)
        + " --run-id " + shlex.quote(run)
    )
    return [context.ADB, "-s", context.SERIAL, "shell", "-T", "run-as", context.PACKAGE,
            "sh", "-c", shlex.quote(inner)]


def _read_fields_valid(row: dict[str, Any], maximum: str, read_bytes: str, read_errno: str) -> bool:
    value = row[maximum]
    size = row[read_bytes]
    error = row[read_errno]
    if size is None:
        return error is None and value is None
    if not _is_int(size) or not -1 <= size <= 4 or error is None:
        return False
    if not _is_int(error) or not 0 <= error <= 4095:
        return False
    if size == 4 and error == 0:
        return _is_int(value) and 0 <= value <= U32_MAX
    return value is None


def _row_valid(row: dict[str, Any]) -> bool:
    if not isinstance(row, dict) or set(row) != FIELDS or row["schema"] != SCHEMA:
        return False
    if not isinstance(row["checkInference"], str) or row["checkInference"] not in INFERENCES:
        return False
    if not isinstance(row["runId"], str) or not re.fullmatch(r"VTEST-[0-9a-f]{32}", row["runId"]):
        return False
    for field in ("pid", "uid", "callSequence", "api", "numAttribs", "incomingErrno", "returnedErrno"):
        if not _is_int(row[field]):
            return False
    if row["pid"] <= 0 or row["uid"] != 10330 or not 1 <= row["callSequence"] <= 4:
        return False
    if not 0 <= row["numAttribs"] <= U32_MAX or not -(2**31) <= row["api"] < 2**31:
        return False
    if any(not -(2**31) <= row[field] < 2**31 for field in ("incomingErrno", "returnedErrno")):
        return False
    for field in (
        "threadSafe", "attributesObserved", "attributesOversize", "providerVerified", "forwarded",
        "resultPresent", "errorPresent", "layoutVerified", "scopeMatches", "measurementValid",
    ):
        if type(row[field]) is not bool:
            return False
    if row["providerVerified"] is not True or row["forwarded"] is not True:
        return False
    if row["errorPresent"]:
        if not _is_int(row["error"]) or not 0 <= row["error"] <= U32_MAX:
            return False
    elif row["error"] is not None:
        return False
    pairs = row["attributePairs"]
    if not isinstance(pairs, list) or len(pairs) > 16 or any(
        not isinstance(pair, list) or len(pair) != 2 or any(not _is_int(value) or not 0 <= value <= U32_MAX for value in pair)
        for pair in pairs
    ):
        return False
    if row["attributesObserved"]:
        if row["attributesOversize"] or len(pairs) != row["numAttribs"]:
            return False
    elif pairs or row["attributesOversize"] != (row["numAttribs"] > 16):
        return False
    exact_scope = (
        row["api"] == 3 and row["numAttribs"] == 2 and len(pairs) == 2
        and len({tuple(pair) for pair in pairs}) == 2
        and {tuple(pair) for pair in pairs} == {(0, 3), (1, 3)}
    )
    if row["scopeMatches"] != exact_scope:
        return False
    if exact_scope:
        if not _is_int(row["requestedVersion"]) or row["requestedVersion"] != 33:
            return False
    elif row["requestedVersion"] is not None:
        return False
    for field in ("maximumBefore", "maximumAfter"):
        value = row[field]
        if value is not None and (not _is_int(value) or not 0 <= value <= U32_MAX):
            return False
    if not all(_read_fields_valid(row, maximum, read_bytes, read_errno) for maximum, read_bytes, read_errno in (
        ("maximumBefore", "beforeReadBytes", "beforeReadErrno"),
        ("maximumAfter", "afterReadBytes", "afterReadErrno"),
    )):
        return False
    if not exact_scope:
        if any(row[field] is not None for field in (
            "maximumBefore", "maximumAfter", "beforeReadBytes", "afterReadBytes",
            "beforeReadErrno", "afterReadErrno",
        )) or row["measurementValid"] or row["checkInference"] != "UNKNOWN":
            return False
    if exact_scope and not row["layoutVerified"]:
        if any(row[field] is not None for field in (
            "maximumBefore", "maximumAfter", "beforeReadBytes", "afterReadBytes",
            "beforeReadErrno", "afterReadErrno",
        )) or row["measurementValid"] or row["checkInference"] != "UNKNOWN":
            return False
    reasonable = lambda value: value == 0 or 10 <= value <= 99
    measurement = (
        exact_scope and row["layoutVerified"]
        and row["beforeReadBytes"] == row["afterReadBytes"] == 4
        and row["beforeReadErrno"] == row["afterReadErrno"] == 0
        and row["maximumBefore"] == row["maximumAfter"]
        and reasonable(row["maximumBefore"])
    )
    if row["measurementValid"] != measurement:
        return False
    expected_inference = "UNKNOWN"
    if measurement:
        if row["resultPresent"] and row["errorPresent"] and row["error"] == 0:
            expected_inference = "NO_BAD_VERSION"
        elif not row["resultPresent"] and row["errorPresent"] and row["error"] == 3:
            expected_inference = (
                "EARLY_CHECK_WOULD_REJECT" if row["maximumBefore"] < 33
                else "NOT_EXPLAINED_BY_EARLY_CHECK"
            )
    return row["checkInference"] == expected_inference


def _old_projection(row: dict[str, Any]) -> dict[str, Any]:
    projected = {field: row[field] for field in old_dri.FIELDS}
    projected["schema"] = "wowforever.android-dri-return-observer/version1"
    return projected


def validate_guest_stderr(raw: bytes) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    mesa_lines: list[bytes] = []
    observer_bytes = 0
    valid = bool(raw) and len(raw) <= context.STDERR_LIMIT and raw.endswith(b"\n")
    for line in raw.splitlines():
        if not line.startswith(PREFIX):
            mesa_lines.append(line + b"\n")
            continue
        observer_bytes += len(line) + 1
        try:
            if len(line) >= 3072 or observer_bytes > 4096 or len(rows) >= 4:
                raise ValueError("DRI version observer bounds")
            row = json.loads(line[len(PREFIX):].decode("ascii"), object_pairs_hook=context.unique_json_pairs)
            if not _row_valid(row):
                raise ValueError("DRI version observer schema")
            rows.append(row)
        except (UnicodeError, ValueError, TypeError, KeyError, json.JSONDecodeError):
            valid = False
    projected = b"".join(
        old_dri.PREFIX + json.dumps(_old_projection(row), separators=(",", ":")).encode("ascii") + b"\n"
        for row in rows
    )
    old = old_dri.validate_guest_stderr(b"".join(mesa_lines) + projected)
    sequences = sorted(row["callSequence"] for row in rows)
    if sequences != list(range(1, len(rows) + 1)):
        valid = False
    valid = valid and old["valid"]
    return {
        **old,
        "valid": valid,
        "observerRecords": rows,
        "driVersionObserved": any(row["measurementValid"] for row in rows),
        "exactDriErrorIdentified": False,
    }


def validate_output(*args: Any, **kwargs: Any) -> tuple[str, list[dict[str, Any]], str]:
    actual, rows = context.validate_output(*args, **kwargs)
    return (
        PASS_STATUS if actual in {"CONTEXT_FAILED", "GUEST_CONTEXT_PASS"} else "UNKNOWN",
        rows,
        actual,
    )


def bind_observation(
    observation: dict[str, Any], *, run: str | None, pid: int | None,
    result_present: bool | None, native_status: str | None = None,
    scope_valid: bool | None = None,
) -> dict[str, Any]:
    """Bind the trace only after the context/native identity gate succeeds."""
    candidate_rows = observation.get("observerRecords", [])
    rows = (
        candidate_rows
        if isinstance(candidate_rows, list)
        and all(isinstance(row, dict) and set(row) == FIELDS and _row_valid(row) for row in candidate_rows)
        else []
    )
    last = max(rows, key=lambda row: row["callSequence"]) if rows else None
    valid_scope = scope_valid is True
    bound = (
        observation.get("valid") is True and native_status == PASS_STATUS and valid_scope
        and bool(rows) and isinstance(run, str) and re.fullmatch(r"VTEST-[0-9a-f]{32}", run)
        and _is_int(pid) and pid > 0 and type(result_present) is bool
        and all(row["runId"] == run and row["pid"] == pid and row["uid"] == 10330 for row in rows)
        and last["scopeMatches"] is True and last["requestedVersion"] == 33
        and last["resultPresent"] == result_present
    )
    measured = bound and last["measurementValid"] is True
    result = {
        **observation,
        "valid": measured,
        "status": PASS_STATUS if measured else (MEASUREMENT_UNKNOWN if bound else TRACE_NOT_OBSERVED),
        "exactDriErrorIdentified": bound,
        "observedDriError": last["error"] if bound and last["errorPresent"] else None,
        "observedCallSequence": last["callSequence"] if bound else None,
        "observedMaximum": last["maximumAfter"] if measured else None,
        "observedMaximumBefore": last["maximumBefore"] if measured else None,
        "derivedCheckInference": last["checkInference"] if measured else None,
    }
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    expected: dict[str, Any] = {
        "run": None, "pid": None, "result_present": None,
        "native_status": None, "scope_valid": None,
    }

    def command(root: str, run: str) -> list[str]:
        expected["run"] = run
        return native_command(root, run)

    def classified(*values: Any, **kwargs: Any) -> tuple[str, list[dict[str, Any]], str]:
        status, rows, actual = validate_output(*values, **kwargs)
        expected.update({"native_status": status, "pid": None, "result_present": None, "scope_valid": None})
        if status == PASS_STATUS and len(rows) == 10 and rows[1].get("scopeValid") is True:
            expected["pid"] = rows[0]["pid"]
            expected["result_present"] = rows[5]["contextCreated"]
            expected["scope_valid"] = True
        return status, rows, actual

    def stderr(raw: bytes) -> dict[str, Any]:
        return bind_observation(validate_guest_stderr(raw), run=expected["run"], pid=expected["pid"],
                                result_present=expected["result_present"], native_status=expected["native_status"],
                                scope_valid=expected["scope_valid"])

    return context.main(
        argv=["--output", str(args.output)], probe_loader=probe_file, payload_copier=copy_payload,
        native_command_builder=command, classifier=classified, native_name=NAME,
        output_prefix="native-DRI-VERSION-", pass_status=PASS_STATUS,
        boundary="child-only DRI core-version limit observation; derived static-check inference only; no GPU, backend, hardware GL, D3D, WoW or acceptance claim",
        source_candidate=negotiation.source_runner.NEGOTIATION,
        server_trace_validator=negotiation.validate_negotiation_server_stderr,
        guest_stderr_validator=stderr,
    )


if __name__ == "__main__":
    raise SystemExit(main())
