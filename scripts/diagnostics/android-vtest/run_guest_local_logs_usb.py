#!/usr/bin/env python3
"""One fixed EGL-local logging replay; no graphics/context acceptance inferred."""
from __future__ import annotations

import argparse
import hashlib
import re
import shlex
import stat
from pathlib import Path
from typing import Any

import run_guest_negotiation_usb as negotiation

context = negotiation.context_runner
guest = negotiation.guest_loader
DIRECTORY = Path(__file__).parent
NAME = "GuestEglLocalLogs"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-local-logs/GuestEglLocalLogs")
SIZE = 36544
SHA256 = "ba0db20f632c23cd250b7c3dd4e9b31b2a74c99f2ea221480da415cfff59a3b6"
SOURCE_PINS = {
    "GuestEglContext.c": "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f",
    "GuestEglLoader.c": "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6",
    "guest-egl-local-logs.patch": "46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d",
    "build_guest_egl_log.sh": "170bcc38e2590b96c9147877fd2153c9b39cdcb28cd5a69dd3bc6d6b77cc13a1",
}
LOG_ENV = {"EGL_LOG_LEVEL": "debug", "MESA_LOG": "file", "MESA_LOG_LEVEL": "debug"}


def probe_file() -> dict[str, Any]:
    for name, digest in SOURCE_PINS.items():
        path = DIRECTORY / name
        info = context._regular_nosymlink(path)
        if info.st_size > 1024 * 1024 or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError(f"logging-control source pin mismatch: {name}")
    info = context._regular_nosymlink(ARTIFACT)
    if info.st_size != SIZE or stat.S_IMODE(info.st_mode) != 0o755:
        raise ValueError("logging artifact size/mode mismatch")
    raw = ARTIFACT.read_bytes()
    if len(raw) != SIZE or hashlib.sha256(raw).hexdigest() != SHA256:
        raise ValueError("logging artifact pin mismatch")
    guest.loader._elf64_aarch64_dyn(raw, NAME, require_linker_interp=True)
    guest._validate_native_dynamic(raw)
    return {"destination": "guest/bin/" + NAME, "path": ARTIFACT,
            "bytes": SIZE, "sha256": SHA256, "mode": "0700"}


def validate_copy_inputs(files: list[dict[str, Any]], probe: dict[str, Any]) -> None:
    guest._validate_copy_inputs(files, {"destination": "guest/bin/GuestEglLoader", "mode": "0700"})
    if probe != probe_file():
        raise ValueError("logging copier received a non-fixed native payload")


def copy_payload(*args: Any, **kwargs: Any) -> tuple[str, dict[str, Any]]:
    return negotiation.copy_payload_to_existing_run(
        *args, **kwargs, probe_input_validator=validate_copy_inputs,
    )


def native_command(private_root: str, run: str) -> list[str]:
    if not re.fullmatch(r"VTEST-[0-9a-f]{32}", run):
        raise ValueError("invalid fixed RUN")
    if private_root not in (
        f"/data/user/0/{context.PACKAGE}/{context.PRIVATE_PREFIX}/{run}",
        f"/data/data/{context.PACKAGE}/{context.PRIVATE_PREFIX}/{run}",
    ):
        raise ValueError("invalid private logging root")
    root = private_root + "/guest"
    inner = (
        f"cd {shlex.quote(private_root)} && exec /system/bin/env -i "
        "PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data "
        f"{shlex.quote('LD_LIBRARY_PATH=' + root + '/lib:/system/lib64')} "
        f"{shlex.quote('TMPDIR=' + private_root)} "
        f"{shlex.quote('__EGL_VENDOR_LIBRARY_FILENAMES=' + root + '/share/glvnd/egl_vendor.d/50_mesa.json')} "
        "GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=vtest.sock "
        "EGL_LOG_LEVEL=debug MESA_LOG=file MESA_LOG_LEVEL=debug "
        f"/system/bin/linker64 {shlex.quote(root + '/bin/' + NAME)} --run-id {shlex.quote(run)}"
    )
    return [context.ADB, "-s", context.SERIAL, "shell", "-T", "run-as", context.PACKAGE,
            "sh", "-c", shlex.quote(inner)]


def validate_guest_logs(raw: bytes) -> dict[str, Any]:
    """Accept logging grammar, not a graphics result; preserve raw messages."""
    count = 0
    optional_cache = False
    valid = bool(raw) and len(raw) <= context.STDERR_LIMIT and raw.endswith(b"\n")
    bad_match = False
    dri = False
    for line in raw.splitlines():
        if line + b"\n" == context.OPTIONAL_CACHE_DISABLED_WARNING:
            if optional_cache:
                valid = False
            optional_cache = True
            continue
        if len(line) > 4096 or any(byte < 32 or byte >= 127 for byte in line):
            valid = False
            continue
        if not re.fullmatch(rb"MESA(?:-EGL)?: (?:debug|info|warning|error): .+", line):
            valid = False
            continue
        count += 1
        bad_match |= b"EGL_BAD_MATCH" in line or b"0x3009" in line
        dri |= b"dri2_create_context" in line
    return {"valid": valid and count > 0, "loggingRows": count,
            "optionalShaderCacheDisabled": optional_cache,
            "mappedBadMatchObserved": bad_match, "driCreateContextObserved": dri,
            "exactDriErrorIdentified": False}


def validate_output(*args: Any, **kwargs: Any) -> tuple[str, list[dict[str, Any]], str]:
    actual, rows = context.validate_output(*args, **kwargs)
    return ("LOCAL_EGL_LOGS_OBSERVED" if actual in {"CONTEXT_FAILED", "GUEST_CONTEXT_PASS"} else "UNKNOWN", rows, actual)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    return context.main(
        argv=["--output", str(args.output)], probe_loader=probe_file,
        payload_copier=copy_payload, native_command_builder=native_command,
        classifier=validate_output, native_name=NAME, output_prefix="native-LOCAL-EGL-",
        pass_status="LOCAL_EGL_LOGS_OBSERVED",
        boundary="one logging-only guest EGL rejection replay; actual context status retained; not a backend or D3D/WoW acceptance",
        source_candidate=negotiation.source_runner.NEGOTIATION,
        server_trace_validator=negotiation.validate_negotiation_server_stderr,
        guest_stderr_validator=validate_guest_logs,
    )


if __name__ == "__main__":
    raise SystemExit(main())
