#!/usr/bin/env python3
"""HOST-only replay of the fixed guest EGL context with negotiation logging."""
from __future__ import annotations

import argparse
import hashlib
import json
import stat
import sys
import uuid
from pathlib import Path
from typing import Any

DIRECTORY = Path(__file__).parent
if str(DIRECTORY) not in sys.path:
    sys.path.insert(0, str(DIRECTORY))

import run_guest_context_usb as context_runner


source_runner = context_runner.source_runner
guest_loader = context_runner.guest_loader
loader = guest_loader.loader
PACKAGE = context_runner.PACKAGE
NATIVE_ARTIFACT_MODE = context_runner.NATIVE_ARTIFACT_MODE
NEGOTIATION_TRACE_PREFIX = b"WFVTEST_NEGOTIATION "
IDENTITY_PREFIXES = (
    b"WFVTEST_ANDROID_DEFAULT gl.vendor=",
    b"WFVTEST_ANDROID_DEFAULT gl.renderer=",
    b"WFVTEST_ANDROID_DEFAULT gl.version=",
)
TRACE_LIMIT = 2048
TRACE_ROWS_LIMIT = 64
U32_MAX = 2**32 - 1
# Verified sizeof(virgl_caps_v2) from the pinned 1.3.0 header, including an
# Android/aarch64 static assertion. The previous 1284 assumption was wrong.
CAP_BYTES_MAX = 1408
TRACE_EVENTS = {
    "command": {"schema", "event", "sequence", "length", "id"},
    "protocol": {"schema", "event", "sequence", "requested", "selected"},
    "cap_request": {"schema", "event", "sequence", "id", "version", "maxVersion", "maxBytes", "valid"},
    "cap_reply": {
        "schema", "event", "sequence", "id", "version", "bytes", "valid", "glsl_level",
        "bset", "rawbitmask", "primitiveMask", "max_texture_array_layers", "max_streamout_buffers",
        "max_dual_source_render_targets", "max_render_targets", "max_samples", "max_tbo_size",
        "max_uniform_blocks", "max_viewports", "max_texture_gather_components", "capability_bits",
    },
    "trace_truncated": {"schema", "event"},
}
U32_FIELDS = {
    "length", "id", "requested", "selected", "version", "maxVersion", "maxBytes", "bytes",
    "glsl_level", "bset", "rawbitmask", "primitiveMask", "max_texture_array_layers",
    "max_streamout_buffers", "max_dual_source_render_targets", "max_render_targets", "max_samples",
    "max_tbo_size", "max_uniform_blocks", "max_viewports", "max_texture_gather_components",
}


def _u32(value: Any) -> bool:
    return type(value) is int and 0 <= value <= U32_MAX


def validate_negotiation_server_stderr(raw: bytes) -> dict[str, Any]:
    """Parse only the fixed identity lines and source logging contract."""
    identity: dict[bytes, bytes] = {}
    rows: list[dict[str, Any]] = []
    truncated = False
    valid = bool(raw) and raw.endswith(b"\n")
    try:
        lines = raw.splitlines()
        for line in lines:
            if len(line) > TRACE_LIMIT:
                valid = False
                continue
            identity_prefix = next((prefix for prefix in IDENTITY_PREFIXES if line.startswith(prefix)), None)
            if identity_prefix is not None:
                value = line[len(identity_prefix):]
                if not 0 < len(value) <= 128 or any(byte < 32 or byte >= 127 for byte in value):
                    valid = False
                if identity_prefix in identity:
                    valid = False
                identity[identity_prefix] = value
                continue
            if not line.startswith(NEGOTIATION_TRACE_PREFIX):
                valid = False
                continue
            document = json.loads(
                line[len(NEGOTIATION_TRACE_PREFIX):].decode("utf-8", "strict"),
                object_pairs_hook=context_runner.unique_json_pairs,
            )
            if not isinstance(document, dict):
                valid = False
                continue
            event = document.get("event")
            if event not in TRACE_EVENTS or set(document) != TRACE_EVENTS[event] or type(document.get("schema")) is not int or document.get("schema") != 1:
                valid = False
                continue
            if event == "trace_truncated":
                if truncated:
                    valid = False
                truncated = True
                continue
            if len(rows) >= TRACE_ROWS_LIMIT:
                valid = False
                continue
            if type(document.get("sequence")) is not int or document["sequence"] != len(rows) + 1:
                valid = False
            for key in U32_FIELDS:
                if key in document and not _u32(document[key]):
                    valid = False
            if event in {"cap_request", "cap_reply"} and type(document["valid"]) is not int:
                valid = False
            if event in {"cap_request", "cap_reply"} and document["valid"] not in {0, 1}:
                valid = False
            if event == "cap_reply" and (document["valid"] != 1 or not 0 < document["bytes"] <= CAP_BYTES_MAX):
                valid = False
            capability_bits = document.get("capability_bits") if event == "cap_reply" else None
            if event == "cap_reply" and capability_bits is not None and not _u32(capability_bits):
                valid = False
            rows.append(document)
        identity_ok = (
            len(identity) == len(IDENTITY_PREFIXES)
            and all(prefix in identity for prefix in IDENTITY_PREFIXES)
        )
        if not any(row["event"] == "command" for row in rows):
            valid = False
        valid = valid and identity_ok
    except (UnicodeError, ValueError, json.JSONDecodeError, KeyError, TypeError):
        valid = False
        identity_ok = False

    protocol = next((row for row in rows if row.get("event") == "protocol"), None)
    protocol_metadata = None if protocol is None else {
        "requested": protocol["requested"], "selected": protocol["selected"],
    }
    request_count = sum(row.get("event") == "command" and row.get("id") in {1, 9, 16} for row in rows)
    return {
        "valid": valid and not truncated,
        "identityVerified": identity_ok,
        "parsedRows": rows,
        "hasCapRequests": request_count > 0,
        "capRequestCount": request_count,
        "modernCapsetRequestMetadataCount": sum(row.get("event") == "cap_request" for row in rows),
        "protocolMetadata": protocol_metadata,
        "traceTruncated": truncated,
    }


def validate_negotiation_output(
    raw: bytes,
    run: str,
    exit_code: int | None,
    complete: bool,
    *,
    server_identity: dict[str, Any] | None = None,
) -> tuple[str, list[dict[str, Any]], str]:
    """Retain the native result while classifying only the logging control."""
    actual, rows = context_runner.validate_output(
        raw, run, exit_code, complete, server_identity=server_identity,
    )
    if actual in {"GUEST_CONTEXT_PASS", "CONTEXT_FAILED"}:
        return "NEGOTIATION_OBSERVED", rows, actual
    return "UNKNOWN", rows, actual


def _validate_guest_inputs(files: list[dict[str, Any]], probe: dict[str, Any]) -> None:
    guest_loader._validate_copy_inputs(
        files, {"destination": "guest/bin/GuestEglLoader", "mode": guest_loader.NATIVE_ARTIFACT_MODE},
    )
    if probe.get("destination") != "guest/bin/GuestEglContext" or probe.get("mode") != NATIVE_ARTIFACT_MODE:
        raise ValueError("guest negotiation copier received an unapproved native artifact")
    path = probe.get("path")
    if not isinstance(path, Path):
        raise ValueError("guest negotiation copier received malformed native artifact")
    if (
        path.absolute() != context_runner.NATIVE_ARTIFACT.absolute()
        or probe.get("bytes") != context_runner.NATIVE_ARTIFACT_BYTES
        or probe.get("sha256") != context_runner.NATIVE_ARTIFACT_SHA256
    ):
        raise ValueError("guest negotiation native artifact pin changed")
    info = path.stat()
    if stat.S_IMODE(info.st_mode) != int(context_runner.NATIVE_ARTIFACT_HOST_MODE, 8):
        raise ValueError("guest negotiation native artifact mode changed")
    if info.st_size != probe.get("bytes") or hashlib.sha256(path.read_bytes()).hexdigest() != probe.get("sha256"):
        raise ValueError("guest negotiation native artifact changed")


def copy_payload_to_existing_run(
    files: list[dict[str, Any]], probe: dict[str, Any], run: str, aliases: list[dict[str, str]], *,
    app_root: str, server_files: list[dict[str, Any]], server_manifest_sha256: str,
    probe_input_validator: Any = None,
) -> tuple[str, dict[str, Any]]:
    """Append the unchanged guest closure, checking the negotiation server pins."""
    relative = guest_loader._private_relative(run)
    app_root = guest_loader._canonical_app_root(app_root)
    validator = _validate_guest_inputs if probe_input_validator is None else probe_input_validator
    if not callable(validator):
        raise ValueError("fixed native payload validator must be callable")
    validator(files, probe)
    if server_manifest_sha256 != source_runner.NEGOTIATION.manifest_sha256:
        raise ValueError("guest negotiation append requires the fixed negotiation server manifest")
    source_runner._validate_copy_inputs(server_files, candidate=source_runner.NEGOTIATION)
    vendor = guest_loader._vendor_item()
    if len(server_files) != 10:
        raise ValueError("guest negotiation copier received an unexpected server closure")
    stage = guest_loader.STAGE_PREFIX + uuid.uuid4().hex
    items = [*files, vendor, probe]
    cleanup_failures: list[str] = []
    operation_error: Exception | None = None
    stage_created = False
    server_checks: list[str] = []
    for item in server_files:
        destination = f"{relative}/{item['destination']}"
        server_checks.extend([
            f"test -f {guest_loader._q(destination)} && test ! -L {guest_loader._q(destination)}",
            f"test \"$(sha256sum {guest_loader._q(destination)} | cut -d' ' -f1)\" = {guest_loader._q(item['sha256'])}",
            f"test \"$(stat -c '%a' {guest_loader._q(destination)})\" = {guest_loader._q(item['mode'].lstrip('0'))}",
        ])
    for destination, target in {
        f"{relative}/lib/libEGL.so.1": "/system/lib64/libEGL.so",
        f"{relative}/lib/libGLESv2.so.2": "/system/lib64/libGLESv2.so",
        f"{relative}/lib/libvirglrenderer.so.1": "libvirglrenderer.so",
    }.items():
        server_checks.append(
            f"test -L {guest_loader._q(destination)} && test \"$(readlink {guest_loader._q(destination)})\" = {guest_loader._q(target)}"
        )
    try:
        loader.require_adb(loader.adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        loader.require_adb(loader.adb("shell", "mkdir", "-m", "755", stage), "exclusive public staging directory")
        stage_created = True
        for index, item in enumerate(items):
            staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
            loader.require_adb(loader.adb("push", str(item["path"]), staged, timeout=90), "normal ADB sync")
            loader.require_adb(loader.adb("shell", "chmod", "0644", staged), "staging file mode")
        guest = f"{relative}/guest"
        commands = [
            "umask 077", "set -e", "set -C", *server_checks,
            f"test -d {guest_loader._q(relative)} && test ! -L {guest_loader._q(relative)}",
            f"test ! -e {guest_loader._q(guest)} && test ! -L {guest_loader._q(guest)}",
            f"test ! -e {guest_loader._q(relative + '/server.identity')} && test ! -L {guest_loader._q(relative + '/server.identity')}",
        ]
        for directory in (guest, f"{guest}/bin", f"{guest}/lib", f"{guest}/share", f"{guest}/share/glvnd", f"{guest}/share/glvnd/egl_vendor.d"):
            commands.append(f"test ! -L {guest_loader._q(directory)} && mkdir -m 700 {guest_loader._q(directory)}")
        for index, item in enumerate(items):
            destination = f"{relative}/{item['destination']}"
            staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
            commands.extend([
                f"test ! -e {guest_loader._q(destination)} && test ! -L {guest_loader._q(destination)}",
                f"cat {guest_loader._q(staged)} > {guest_loader._q(destination)}",
                f"chmod {item['mode']} {guest_loader._q(destination)}",
            ])
        for alias in aliases:
            destination = f"{guest}/{alias['path']}"
            commands.extend([
                f"test ! -e {guest_loader._q(destination)} && test ! -L {guest_loader._q(destination)}",
                f"ln -s {guest_loader._q(alias['target'])} {guest_loader._q(destination)}",
            ])
        commands.extend(server_checks)
        commands.append("printf VTEST_GUEST_NEGOTIATION_UPLOAD_OK")
        result = loader.adb("exec-out", "run-as", PACKAGE, "sh", "-c", "; ".join(commands), timeout=90)
        if result.returncode or result.stdout != b"VTEST_GUEST_NEGOTIATION_UPLOAD_OK" or result.stderr:
            raise RuntimeError("private guest negotiation upload sentinel was not exact")
        for item in items:
            guest_loader._device_check(f"{relative}/{item['destination']}", item["sha256"], item["mode"])
        for alias in aliases:
            alias_path = f"{relative}/guest/{alias['path']}"
            actual = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "readlink", alias_path), "guest negotiation alias", 4096)
            if actual != (alias["target"] + "\n").encode():
                raise RuntimeError(f"guest negotiation alias target mismatch: {alias_path}")
            canonical = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "readlink", "-f", alias_path), "guest negotiation alias canonical target", 4096)
            expected = guest_loader._alias_canonical_target(app_root, relative, alias)
            if canonical != (expected + "\n").encode():
                raise RuntimeError(f"guest negotiation alias escaped the verified guest subtree: {alias_path}")
    except Exception as exc:
        operation_error = exc
    finally:
        if stage_created:
            for index, item in enumerate(items):
                staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
                try:
                    result = loader.adb("shell", "rm", "-f", staged)
                    if result.returncode or result.stdout or result.stderr:
                        cleanup_failures.append(staged)
                except Exception:
                    cleanup_failures.append(staged)
            try:
                result = loader.adb("shell", "rmdir", stage)
                if result.returncode or result.stdout or result.stderr:
                    cleanup_failures.append(stage)
            except Exception:
                cleanup_failures.append(stage)
    if operation_error is not None:
        if cleanup_failures:
            raise RuntimeError(f"guest negotiation upload failed ({operation_error}); owned staging cleanup failed: {cleanup_failures}") from operation_error
        raise operation_error
    if cleanup_failures:
        raise RuntimeError(f"owned staging cleanup failed; no negotiation executed: {cleanup_failures}")
    return relative, {
        "path": stage, "stagingClean": True, "failures": [],
        "fileCount": guest_loader.PAYLOAD_COUNT, "aliasCount": guest_loader.ALIAS_COUNT,
        "serverManifestSha256": server_manifest_sha256, "serverFilesVerified": True,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    return context_runner.main(
        argv=["--output", str(args.output)],
        probe_loader=context_runner.probe_file,
        payload_copier=copy_payload_to_existing_run,
        native_command_builder=context_runner.native_command,
        classifier=validate_negotiation_output,
        native_name=context_runner.NATIVE_NAME,
        output_prefix="native-NEGOTIATION-",
        pass_status="NEGOTIATION_OBSERVED",
        boundary="fixed guest EGL context replay with bounded vtest negotiation logging only; not game, presentation, D3D, WoW, or Mali support",
        source_candidate=source_runner.NEGOTIATION,
        server_trace_validator=validate_negotiation_server_stderr,
    )


if __name__ == "__main__":
    raise SystemExit(main())
