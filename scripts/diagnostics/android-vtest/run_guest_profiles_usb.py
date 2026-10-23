#!/usr/bin/env python3
"""HOST-only runner for the fixed guest EGL profile matrix.

This is deliberately a thin profile-specific adapter around the reviewed
GuestEglContext two-child orchestration.  It has no device/network/build
fallbacks and exposes only ``--output`` on its CLI.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import shlex
import stat
import uuid
from pathlib import Path
from typing import Any


DIRECTORY = Path(__file__).parent


def _load(name: str, path: Path) -> Any:
    existing = __import__("sys").modules.get(name)
    if existing is not None and Path(getattr(existing, "__file__", "")).resolve() == path.resolve():
        return existing
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    __import__("sys").modules[name] = module
    spec.loader.exec_module(module)
    return module


context_runner = _load("vtest_profiles_context_runner", DIRECTORY / "run_guest_context_usb.py")
source_runner = context_runner.source_runner
guest_loader = context_runner.guest_loader

ADB = context_runner.ADB
SERIAL = context_runner.SERIAL
PACKAGE = context_runner.PACKAGE
UID = context_runner.UID
VERSION_CODE = context_runner.VERSION_CODE
RUN_PREFIX = context_runner.RUN_PREFIX
PRIVATE_PREFIX = context_runner.PRIVATE_PREFIX

NATIVE_NAME = "GuestEglProfiles"
NATIVE_ARTIFACT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-profiles/GuestEglProfiles"
)
NATIVE_ARTIFACT_BYTES = 39_008
NATIVE_ARTIFACT_SHA256 = "d912d2a4e872ab7b1981e6f1f8b5f0156b02e79719e31e7f5d6af19f0fbc3726"
NATIVE_ARTIFACT_HOST_MODE = "0755"
NATIVE_ARTIFACT_MODE = "0700"
NATIVE_SOURCE_PINS = {
    "GuestEglProfiles.c": "8d02dfab61d36ad3f4d3a6626169681532d0633f221e29b6eddcf617295e436f",
    "GuestEglContext.c": "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f",
    "GuestEglLoader.c": "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6",
}

SCHEMA = "wowforever.android-guest-egl-profiles/version1"
STDOUT_LIMIT = 16 * 1024
STDERR_LIMIT = context_runner.STDERR_LIMIT
DEADLINE_SECONDS = context_runner.DEADLINE_SECONDS
MAX_MAP_BYTES = 2 * 1024 * 1024
MAX_STRING = 512
HEX64 = re.compile(r"^[0-9a-f]{64}$")
RUN_RE = re.compile(r"^VTEST-[0-9a-f]{32}$")
PROFILE_NAMES = ("OpenGL3.3CORE", "OpenGL3.3COMPAT", "OpenGL3.2CORE", "OpenGL3.0DEFAULT")
GUEST_MANIFEST_SHA256 = "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9"
GUEST_TREE_SHA256 = "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216"
SERVER_MANIFEST_SHA256 = "b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28"
OPTIONAL_CACHE_DISABLED_WARNING = context_runner.OPTIONAL_CACHE_DISABLED_WARNING
BROADER_FLAGS = (
    "hardwareRenderingVerified", "triangleVerified", "presentationVerified",
    "d3dVerified", "wowVerified",
)


def probe_file() -> dict[str, Any]:
    """Validate the parent-owned profile ELF without executing it."""
    if NATIVE_ARTIFACT_BYTES <= 0 or not HEX64.fullmatch(NATIVE_ARTIFACT_SHA256):
        raise ValueError("native GuestEglProfiles artifact pin is invalid")
    for name, digest in NATIVE_SOURCE_PINS.items():
        source = DIRECTORY / name
        context_runner._regular_nosymlink(source)
        if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise ValueError(f"native profile source hash mismatch: {name}")
    info = context_runner._regular_nosymlink(NATIVE_ARTIFACT)
    if info.st_size != NATIVE_ARTIFACT_BYTES or stat.S_IMODE(info.st_mode) != int(NATIVE_ARTIFACT_HOST_MODE, 8):
        raise ValueError("native GuestEglProfiles artifact metadata mismatch")
    data = NATIVE_ARTIFACT.read_bytes()
    if len(data) != NATIVE_ARTIFACT_BYTES or hashlib.sha256(data).hexdigest() != NATIVE_ARTIFACT_SHA256:
        raise ValueError("native GuestEglProfiles artifact hash mismatch")
    guest_loader.loader._elf64_aarch64_dyn(data, NATIVE_NAME, require_linker_interp=True)
    guest_loader._validate_native_dynamic(data)
    return {
        "destination": "guest/bin/GuestEglProfiles", "path": NATIVE_ARTIFACT,
        "bytes": NATIVE_ARTIFACT_BYTES, "sha256": NATIVE_ARTIFACT_SHA256,
        "mode": NATIVE_ARTIFACT_MODE,
    }


def native_command(private_root: str, run: str) -> list[str]:
    if not RUN_RE.fullmatch(run):
        raise ValueError("invalid fixed RUN")
    roots = (
        f"/data/user/0/{PACKAGE}/{PRIVATE_PREFIX}/{run}",
        f"/data/data/{PACKAGE}/{PRIVATE_PREFIX}/{run}",
    )
    if private_root not in roots:
        raise ValueError("unapproved private guest profiles root")
    guest = f"{private_root}/guest"
    inner = (
        f"cd {shlex.quote(private_root)} && exec /system/bin/env -i "
        "PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data "
        f"{shlex.quote('LD_LIBRARY_PATH=' + guest + '/lib:/system/lib64')} "
        f"{shlex.quote('TMPDIR=' + private_root)} "
        f"{shlex.quote('__EGL_VENDOR_LIBRARY_FILENAMES=' + guest + '/share/glvnd/egl_vendor.d/50_mesa.json')} "
        "GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=vtest.sock "
        f"/system/bin/linker64 {shlex.quote(guest + '/bin/' + NATIVE_NAME)} --run-id {shlex.quote(run)}"
    )
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "sh", "-c", shlex.quote(inner)]


def unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _is_int(value: Any) -> bool:
    return type(value) is int


def _is_bool(value: Any) -> bool:
    return type(value) is bool


def _string(value: Any, *, nullable: bool = False, nonempty: bool = False) -> bool:
    if value is None:
        return nullable
    return isinstance(value, str) and len(value) <= MAX_STRING and (not nonempty or bool(value))


def _exact_keys(value: Any, keys: set[str]) -> bool:
    return isinstance(value, dict) and set(value) == keys


TOP_KEYS = {
    "schema", "runId", "pid", "uid", "pgid", "sid", "times", "status", "contextQualified",
    *BROADER_FLAGS, "scope", "server", "library", "exports", "display", "config", "attempts",
    "maps", "cleanup", "nativeExit",
}
TIME_KEYS = {"startedNs", "finishedNs", "durationNs"}
SCOPE_KEYS = {
    "valid", "environmentValid", "selectionValid", "cwd", "libraryPath", "ldLibraryPath",
    "vendorJson", "guestManifestSha256", "guestTreeSha256", "serverManifestSha256",
}
SERVER_KEYS = {"expectedPid", "expectedStartTicks", "identityLoaded", "live", "liveBeforeTerminate"}
LIBRARY_KEYS = {"loaded", "privatePath", "canonicalPath", "loadError"}
EXPORT_KEYS = {"expectedCount", "exportCount", "allExports", "ownerExact", "ownerPath", "missingSymbol", "wrongOwnerSymbol"}
DISPLAY_KEYS = {
    "clientExtensions", "clientExtensionBytes", "displayExtensions", "displayExtensionBytes", "vendor", "version",
    "clientApis", "displayCreated", "displayInitialized", "eglError",
}
CONFIG_KEYS = {
    "boundApi", "chosen", "count", "attributesValid", "bindError", "chooseError", "EGL_RENDERABLE_TYPE", "EGL_CONFORMANT", "EGL_SURFACE_TYPE",
    "EGL_CONFIG_ID", "attributeErrors",
}
CONFIG_ERROR_KEYS = {"EGL_RENDERABLE_TYPE", "EGL_CONFORMANT", "EGL_SURFACE_TYPE", "EGL_CONFIG_ID"}
REQUEST_KEYS = {"major", "minor", "profile", "core"}
ACTUAL_KEYS = {
    "valid", "major", "minor", "profileKnown", "profileMask", "core", "vendor", "renderer", "version",
    "glslVersion", "glError", "rendererVirgl", "softwareFallback",
}
CLEANUP_ATTEMPT_KEYS = {"unbindAttempted", "unbindOk", "unbindError", "destroyAttempted", "destroyOk", "destroyError", "valid"}
ATTEMPT_KEYS = {
    "name", "requested", "serverLiveBefore", "createCalled", "createError", "created", "makeCurrentCalled",
    "makeCurrentError", "madeCurrent", "actual", "cleanup", "serverLiveAfter", "deadlineValid",
}
MAP_KEYS = {
    "complete", "truncated", "bytes", "lines", "privateLoader", "privateVendor", "privateGallium", "privateLlvm",
    "androidEgl", "androidGles", "loaderPath", "vendorPath", "error",
}
CLEANUP_KEYS = {"terminateAttempted", "terminateOk", "terminateError", "handleClosed", "failures", "serverLiveBeforeTerminate"}


def _validate_attempt(attempt: Any, index: int) -> bool:
    if not _exact_keys(attempt, ATTEMPT_KEYS) or attempt["name"] != PROFILE_NAMES[index]:
        return False
    request = attempt["requested"]
    expected_request = (
        {"major": 3, "minor": 3, "profile": "CORE", "core": True},
        {"major": 3, "minor": 3, "profile": "COMPAT", "core": False},
        {"major": 3, "minor": 2, "profile": "CORE", "core": True},
        {"major": 3, "minor": 0, "profile": None, "core": None},
    )[index]
    if not _exact_keys(request, REQUEST_KEYS) or request != expected_request:
        return False
    if not _is_int(request["major"]) or not _is_int(request["minor"]) or (request["core"] is not None and not _is_bool(request["core"])):
        return False
    for key in ("serverLiveBefore", "createCalled", "created", "makeCurrentCalled", "madeCurrent", "serverLiveAfter", "deadlineValid"):
        if not _is_bool(attempt[key]):
            return False
    if not all(_is_int(attempt[key]) and 0 <= attempt[key] <= 65535 for key in ("createError",)):
        return False
    if attempt["makeCurrentError"] is not None and (not _is_int(attempt["makeCurrentError"]) or not 0 <= attempt["makeCurrentError"] <= 65535):
        return False
    if not attempt["serverLiveBefore"] or not attempt["serverLiveAfter"] or not attempt["deadlineValid"] or not attempt["createCalled"]:
        return False
    actual = attempt["actual"]
    cleanup = attempt["cleanup"]
    if not _exact_keys(actual, ACTUAL_KEYS) or not _exact_keys(cleanup, CLEANUP_ATTEMPT_KEYS):
        return False
    if not _is_bool(actual["valid"]) or not _is_bool(actual["profileKnown"]) or not _is_bool(actual["rendererVirgl"]) or not _is_bool(actual["softwareFallback"]):
        return False
    if not _is_int(actual["glError"]) or not 0 <= actual["glError"] <= 65535:
        return False
    for key in ("unbindAttempted", "unbindOk", "destroyAttempted", "destroyOk", "valid"):
        if not _is_bool(cleanup[key]):
            return False
    for key in ("unbindError", "destroyError"):
        if cleanup[key] is not None and (not _is_int(cleanup[key]) or not 0 <= cleanup[key] <= 65535):
            return False

    if not attempt["created"]:
        if attempt["createError"] == 0x3000 or attempt["makeCurrentCalled"] or attempt["makeCurrentError"] is not None or attempt["madeCurrent"]:
            return False
        if any(actual[key] is not value for key, value in (("valid", False), ("profileKnown", False), ("rendererVirgl", False), ("softwareFallback", False))):
            return False
        if any(actual[key] is not None for key in ("major", "minor", "profileMask", "core", "vendor", "renderer", "version", "glslVersion")):
            return False
        if actual["glError"] != 0:
            return False
        if cleanup != {"unbindAttempted": False, "unbindOk": False, "unbindError": None,
                        "destroyAttempted": False, "destroyOk": False, "destroyError": None, "valid": True}:
            return False
        return True

    if attempt["createError"] != 0x3000 or not attempt["makeCurrentCalled"]:
        return False
    if not attempt["madeCurrent"]:
        # A created-but-not-current context is still a truthful rejected
        # attempt.  Native cleanup must destroy it, but must not claim an
        # unbind or any GL observation.
        if attempt["makeCurrentError"] in (None, 0x3000) or actual["valid"]:
            return False
        if any(actual[key] is not None for key in ("major", "minor", "profileMask", "core", "vendor", "renderer", "version", "glslVersion")):
            return False
        if actual["profileKnown"] or actual["rendererVirgl"] or actual["softwareFallback"] or actual["glError"] != 0:
            return False
        return cleanup == {"unbindAttempted": False, "unbindOk": False, "unbindError": None,
                           "destroyAttempted": True, "destroyOk": True, "destroyError": 0x3000, "valid": True}

    if attempt["makeCurrentError"] != 0x3000 or not actual["valid"]:
        return False
    if not _is_int(actual["major"]) or not _is_int(actual["minor"]) or actual["major"] < 1 or actual["minor"] < 0:
        return False
    if not all(_string(actual[key], nonempty=True) for key in ("vendor", "renderer", "version", "glslVersion")):
        return False
    if actual["glError"] != 0:
        return False
    if actual["major"] > 3 or (actual["major"] == 3 and actual["minor"] >= 2):
        if actual["profileKnown"] is not True or not _is_int(actual["profileMask"]) or actual["profileMask"] < 0 or not _is_bool(actual["core"]) or actual["core"] != bool(actual["profileMask"] & 1):
            return False
    elif actual["profileKnown"] or actual["profileMask"] is not None or actual["core"] is not None:
        return False
    lowered_renderer = actual["renderer"].lower()
    if actual["rendererVirgl"] != ("virgl" in lowered_renderer) or actual["softwareFallback"] != any(name in lowered_renderer for name in ("llvmpipe", "softpipe", "swrast", "software")):
        return False
    return cleanup == {"unbindAttempted": True, "unbindOk": True, "unbindError": 0x3000,
                       "destroyAttempted": True, "destroyOk": True, "destroyError": 0x3000, "valid": True}


def validate_output(
    raw: bytes,
    run: str,
    exit_code: int | None,
    complete: bool,
    truncated: bool = False,
    *,
    server_identity: dict[str, Any] | None = None,
) -> tuple[str, list[dict[str, Any]]]:
    """Classify only complete, exact native profile evidence."""
    if not RUN_RE.fullmatch(run) or not complete or truncated or not raw or len(raw) > STDOUT_LIMIT or not raw.endswith(b"\n"):
        return "UNKNOWN", []
    try:
        if raw.count(b"\n") != 1:
            return "UNKNOWN", []
        row = json.loads(raw[:-1].decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs)
        if not _exact_keys(row, TOP_KEYS):
            return "UNKNOWN", [row] if isinstance(row, dict) else []
        if row["schema"] != SCHEMA or row["runId"] != run:
            return "UNKNOWN", [row]
        if not all(_is_int(row[key]) and row[key] > 0 for key in ("pid", "pgid", "sid")) or row["uid"] != UID or not _is_int(row["uid"]):
            return "UNKNOWN", [row]
        times = row["times"]
        if not _exact_keys(times, TIME_KEYS) or not all(_is_int(times[key]) and times[key] > 0 for key in TIME_KEYS):
            return "UNKNOWN", [row]
        if times["finishedNs"] < times["startedNs"] or times["durationNs"] != times["finishedNs"] - times["startedNs"]:
            return "UNKNOWN", [row]
        if row["status"] not in {"PROFILE_MATRIX_MEASURED", "UNKNOWN"} or not isinstance(row["status"], str):
            return "UNKNOWN", [row]
        if not _is_bool(row["contextQualified"]) or any(row[key] is not False or not _is_bool(row[key]) for key in BROADER_FLAGS):
            return "UNKNOWN", [row]
        if not isinstance(row["attempts"], list) or len(row["attempts"]) != 4 or not all(_validate_attempt(item, index) for index, item in enumerate(row["attempts"])):
            return "UNKNOWN", [row]
        # This matrix classifies observations; it never accepts a graphics backend.
        if row["contextQualified"] is not False:
            return "UNKNOWN", [row]
        scope, server, library, exports = row["scope"], row["server"], row["library"], row["exports"]
        display, config, maps, cleanup = row["display"], row["config"], row["maps"], row["cleanup"]
        if not _exact_keys(scope, SCOPE_KEYS) or not _exact_keys(server, SERVER_KEYS) or not _exact_keys(library, LIBRARY_KEYS) or not _exact_keys(exports, EXPORT_KEYS):
            return "UNKNOWN", [row]
        if not _exact_keys(display, DISPLAY_KEYS) or not _exact_keys(config, CONFIG_KEYS) or not _exact_keys(maps, MAP_KEYS) or not _exact_keys(cleanup, CLEANUP_KEYS):
            return "UNKNOWN", [row]
        if not _exact_keys(config["attributeErrors"], CONFIG_ERROR_KEYS):
            return "UNKNOWN", [row]
        private_roots = (
            f"/data/user/0/{PACKAGE}/{PRIVATE_PREFIX}/{run}",
            f"/data/data/{PACKAGE}/{PRIVATE_PREFIX}/{run}",
        )
        if scope["cwd"] not in private_roots:
            return "UNKNOWN", [row]
        private = scope["cwd"]
        guest_library = "guest/lib/libEGL.so.1.1.0"
        guest_vendor = "guest/lib/libEGL_mesa.so"
        library_path = f"{private}/{guest_library}"
        checks = (
            _is_bool(scope["valid"]) and _is_bool(scope["environmentValid"]) and _is_bool(scope["selectionValid"])
            and scope["valid"] and scope["environmentValid"] and scope["selectionValid"]
            and scope["libraryPath"] == library_path and scope["ldLibraryPath"] == f"{private}/guest/lib:/system/lib64"
            and scope["vendorJson"] == f"{private}/guest/share/glvnd/egl_vendor.d/50_mesa.json"
            and scope["guestManifestSha256"] == GUEST_MANIFEST_SHA256 and scope["guestTreeSha256"] == GUEST_TREE_SHA256
            and scope["serverManifestSha256"] == SERVER_MANIFEST_SHA256
            and all(_is_bool(server[key]) for key in ("identityLoaded", "live", "liveBeforeTerminate"))
            and _is_int(server["expectedPid"]) and server["expectedPid"] > 0
            and _is_int(server["expectedStartTicks"]) and server["expectedStartTicks"] > 0
            and all(_is_bool(server[key]) and server[key] for key in ("identityLoaded", "live", "liveBeforeTerminate"))
            and isinstance(server_identity, dict)
            and _is_int(server_identity.get("pid")) and server_identity.get("pid") > 0
            and _is_int(server_identity.get("startTicks")) and server_identity.get("startTicks") > 0
            and server["expectedPid"] == server_identity.get("pid")
            and server["expectedStartTicks"] == server_identity.get("startTicks")
            and _is_bool(library["loaded"]) and _string(library["privatePath"]) and _string(library["canonicalPath"])
            and _string(library["loadError"], nullable=True)
            and library == {"loaded": True, "privatePath": library_path, "canonicalPath": library_path, "loadError": None}
            and all(_is_int(exports[key]) for key in ("expectedCount", "exportCount"))
            and all(_is_bool(exports[key]) for key in ("allExports", "ownerExact"))
            and all(_string(exports[key], nullable=True) for key in ("ownerPath", "missingSymbol", "wrongOwnerSymbol"))
            and exports == {"expectedCount": 11, "exportCount": 11, "allExports": True, "ownerExact": True,
                            "ownerPath": library_path, "missingSymbol": None, "wrongOwnerSymbol": None}
        )
        checks = checks and (
            all(_string(display[key], nonempty=True) for key in ("clientExtensions", "displayExtensions", "vendor", "version", "clientApis"))
            and all(_is_int(display[key]) and 0 <= display[key] <= 16 * 1024 for key in ("clientExtensionBytes", "displayExtensionBytes"))
            and _is_bool(display["displayCreated"]) and _is_bool(display["displayInitialized"]) and _is_int(display["eglError"])
            and display["displayCreated"] and display["displayInitialized"] and display["eglError"] == 0x3000
            and "EGL_KHR_create_context" in display["displayExtensions"] and "EGL_KHR_surfaceless_context" in display["displayExtensions"]
            and all(_is_bool(config[key]) for key in ("boundApi", "chosen", "attributesValid"))
            and all(_is_int(config[key]) for key in ("count", "bindError", "chooseError", "EGL_RENDERABLE_TYPE", "EGL_CONFORMANT", "EGL_SURFACE_TYPE", "EGL_CONFIG_ID"))
            and all(_is_int(config["attributeErrors"][key]) for key in CONFIG_ERROR_KEYS)
            and config["boundApi"] and config["chosen"] and config["attributesValid"] and config["count"] == 1
            and config["bindError"] == 0x3000 and config["chooseError"] == 0x3000
            and config["EGL_RENDERABLE_TYPE"] >= 0 and (config["EGL_RENDERABLE_TYPE"] & 8) != 0
            and config["EGL_SURFACE_TYPE"] >= 0 and config["EGL_CONFIG_ID"] > 0
            and all(config["attributeErrors"][key] == 0x3000 for key in CONFIG_ERROR_KEYS)
        )
        checks = checks and (
            _is_bool(maps["complete"]) and _is_bool(maps["truncated"]) and maps["complete"] and not maps["truncated"]
            and all(_is_int(maps[key]) and 0 <= maps[key] <= MAX_MAP_BYTES for key in ("bytes", "lines"))
            and all(maps[key] is True for key in ("privateLoader", "privateVendor", "privateGallium", "privateLlvm"))
            and maps["androidEgl"] is False and maps["androidGles"] is False
            and maps["loaderPath"] == library_path and maps["vendorPath"] == f"{private}/{guest_vendor}" and maps["error"] is None
        )
        checks = checks and (
            all(_is_bool(cleanup[key]) for key in ("terminateAttempted", "terminateOk", "handleClosed", "serverLiveBeforeTerminate"))
            and all(_is_int(cleanup[key]) for key in ("terminateError", "failures"))
            and cleanup["terminateAttempted"] and cleanup["terminateOk"] and cleanup["terminateError"] == 0x3000
            and cleanup["handleClosed"] is True and cleanup["failures"] == 0
            and _is_int(row["nativeExit"]) and row["nativeExit"] == exit_code == 0
        )
        if not checks:
            return "UNKNOWN", [row]
        return ("PROFILE_MATRIX_MEASURED" if row["status"] == "PROFILE_MATRIX_MEASURED" else "UNKNOWN"), [row]
    except (UnicodeError, ValueError, json.JSONDecodeError, KeyError, TypeError):
        return "UNKNOWN", []


def _validate_profile_copy_inputs(files: list[dict[str, Any]], probe: dict[str, Any]) -> None:
    expected = {f"guest/{item['sourcePath']}" for item in files}
    if len(files) != guest_loader.PAYLOAD_COUNT or {item.get("destination") for item in files} != expected:
        raise ValueError("guest profiles copier received an unexpected payload closure")
    for item in files:
        path = item.get("path")
        if not isinstance(path, Path) or item.get("mode") != guest_loader.DEVICE_FILE_MODE:
            raise ValueError("guest profiles copier received malformed payload metadata")
        relative = guest_loader.PurePosixPath(item["sourcePath"])
        expected_path = guest_loader._canonical_contained(guest_loader.CANDIDATE_ROOT, relative)
        if path.absolute() != expected_path.absolute():
            raise ValueError("guest profiles source escaped candidate containment")
        guest_loader._stream_hash(path, item["bytes"], item["sha256"], exact=guest_loader.EXPECTED_VENDOR_JSON if item["sourcePath"] == guest_loader.EXPECTED_VENDOR_PATH else None)
    if probe.get("destination") != "guest/bin/GuestEglProfiles" or probe.get("mode") != NATIVE_ARTIFACT_MODE:
        raise ValueError("guest profiles copier received an unapproved native artifact")
    path = probe.get("path")
    if not isinstance(path, Path) or path.stat().st_size != NATIVE_ARTIFACT_BYTES or hashlib.sha256(path.read_bytes()).hexdigest() != NATIVE_ARTIFACT_SHA256:
        raise ValueError("guest profiles native artifact changed")


def copy_payload_to_existing_run(
    files: list[dict[str, Any]], probe: dict[str, Any], run: str, aliases: list[dict[str, str]], *,
    app_root: str, server_files: list[dict[str, Any]], server_manifest_sha256: str,
) -> tuple[str, dict[str, Any]]:
    """Append only the fixed profile executable and guest closure to a bound run."""
    relative = guest_loader._private_relative(run)
    app_root = guest_loader._canonical_app_root(app_root)
    _validate_profile_copy_inputs(files, probe)
    if server_manifest_sha256 != source_runner.ANDROID_DEFAULT.manifest_sha256:
        raise ValueError("guest profiles append requires the fixed Android-default server manifest")
    source_runner._validate_copy_inputs(server_files, candidate=source_runner.ANDROID_DEFAULT)
    if len(server_files) != 10:
        raise ValueError("guest profiles copier received an unexpected server closure")
    vendor = guest_loader._vendor_item()
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
        server_checks.append(f"test -L {guest_loader._q(destination)} && test \"$(readlink {guest_loader._q(destination)})\" = {guest_loader._q(target)}")
    try:
        loader = guest_loader.loader
        loader.require_adb(loader.adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        loader.require_adb(loader.adb("shell", "mkdir", "-m", "755", stage), "exclusive public staging directory")
        stage_created = True
        for index, item in enumerate(items):
            staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
            loader.require_adb(loader.adb("push", str(item["path"]), staged, timeout=90), "normal ADB sync")
            loader.require_adb(loader.adb("shell", "chmod", "0644", staged), "staging file mode")
        guest = f"{relative}/guest"
        commands = ["umask 077", "set -e", "set -C", *server_checks,
                    f"test -d {guest_loader._q(relative)} && test ! -L {guest_loader._q(relative)}",
                    f"test ! -e {guest_loader._q(guest)} && test ! -L {guest_loader._q(guest)}",
                    f"test ! -e {guest_loader._q(relative + '/server.identity')} && test ! -L {guest_loader._q(relative + '/server.identity')}"]
        for directory in (guest, f"{guest}/bin", f"{guest}/lib", f"{guest}/share", f"{guest}/share/glvnd", f"{guest}/share/glvnd/egl_vendor.d"):
            commands.append(f"test ! -L {guest_loader._q(directory)} && mkdir -m 700 {guest_loader._q(directory)}")
        for index, item in enumerate(items):
            destination = f"{relative}/{item['destination']}"
            staged = f"{stage}/{index:02d}-{Path(item['destination']).name}"
            commands.extend([f"test ! -e {guest_loader._q(destination)} && test ! -L {guest_loader._q(destination)}",
                             f"cat {guest_loader._q(staged)} > {guest_loader._q(destination)}", f"chmod {item['mode']} {guest_loader._q(destination)}"])
        for alias in aliases:
            destination = f"{guest}/{alias['path']}"
            commands.extend([f"test ! -e {guest_loader._q(destination)} && test ! -L {guest_loader._q(destination)}",
                             f"ln -s {guest_loader._q(alias['target'])} {guest_loader._q(destination)}"])
        commands.extend(server_checks)
        commands.append("printf VTEST_GUEST_PROFILES_UPLOAD_OK")
        result = loader.adb("exec-out", "run-as", PACKAGE, "sh", "-c", "; ".join(commands), timeout=90)
        if result.returncode or result.stdout != b"VTEST_GUEST_PROFILES_UPLOAD_OK" or result.stderr:
            raise RuntimeError("private guest profiles upload sentinel was not exact")
        for item in items:
            guest_loader._device_check(f"{relative}/{item['destination']}", item["sha256"], item["mode"])
        for alias in aliases:
            alias_path = f"{relative}/guest/{alias['path']}"
            actual = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "readlink", alias_path), "guest profiles alias", 4096)
            if actual != (alias["target"] + "\n").encode():
                raise RuntimeError(f"guest profiles alias target mismatch: {alias_path}")
            canonical = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "readlink", "-f", alias_path), "guest profiles alias canonical target", 4096)
            if canonical != (guest_loader._alias_canonical_target(app_root, relative, alias) + "\n").encode():
                raise RuntimeError(f"guest profiles alias escaped the verified guest subtree: {alias_path}")
    except Exception as exc:
        operation_error = exc
    finally:
        if stage_created:
            loader = guest_loader.loader
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
            raise RuntimeError(f"guest profiles upload failed ({operation_error}); owned staging cleanup failed: {cleanup_failures}") from operation_error
        raise operation_error
    if cleanup_failures:
        raise RuntimeError(f"owned staging cleanup failed; no profiles executed: {cleanup_failures}")
    return relative, {"path": stage, "stagingClean": True, "failures": [], "fileCount": guest_loader.PAYLOAD_COUNT,
                      "aliasCount": guest_loader.ALIAS_COUNT, "serverManifestSha256": server_manifest_sha256,
                      "serverFilesVerified": True}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    # All provisioning and process ownership remains in the context runner;
    # these fixed seams do not alter its public/default CLI behavior.
    return context_runner.main(
        argv=["--output", str(args.output)],
        probe_loader=probe_file,
        payload_copier=copy_payload_to_existing_run,
        native_command_builder=native_command,
        classifier=validate_output,
        native_name=NATIVE_NAME,
        output_prefix="native-PROFILES-",
        pass_status="PROFILE_MATRIX_MEASURED",
        boundary="fixed guest EGL profile matrix measurement only; not context qualification, hardware rendering, triangle, presentation, D3D, WoW, or Wine",
    )


if __name__ == "__main__":
    raise SystemExit(main())
