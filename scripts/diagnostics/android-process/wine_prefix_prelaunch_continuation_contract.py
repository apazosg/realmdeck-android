"""Authenticated authority for the single known Wine61 prelaunch abort."""

from __future__ import annotations

import hashlib
import json
import os
import stat
from pathlib import Path
from typing import Any


CONTRACT_SOURCE = Path(__file__).with_name("wine-prefix-prelaunch-continuation-contract.json")
EVIDENCE_ROOT = Path("/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/wine61-server-isolation-a34b5a295f944449b61617e70eef0619")
CONTRACT_SHA256 = "0008db0586756da7b8e20a88725788ce64dc4c290f2d04d360068ff65a64da27"
ORIGINAL_SEAL_SHA256 = "2831fd327578a6c6d5cd95972fdf7f3c6857113850d2214cbccc7916cc8df10c"
SUPPLEMENTAL_SEAL_SHA256 = "8bfc88773d7b7be622255cb92d3c54b94692220cafc6439bb9bf24ba768c7d3d"
SCHEMA = "wowforever.wine-prefix-prelaunch-continuation/v1"
MAX_JSON_BYTES = 4 * 1024 * 1024
SHA_RE = r"[0-9a-f]{64}"


class ContractError(ValueError):
    pass


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError("contract contains duplicate JSON keys")
        result[key] = value
    return result


def _json(raw: bytes) -> Any:
    try:
        return json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=_unique_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError, ContractError) as exc:
        raise ContractError("authenticated JSON is malformed") from exc


def _identity(info: os.stat_result) -> tuple[int, ...]:
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size,
            info.st_mtime_ns, info.st_ctime_ns)


def _check_ancestors(path: Path, root: Path) -> None:
    try:
        relative = path.relative_to(root)
    except ValueError as exc:
        raise ContractError("authenticated path escaped evidence root") from exc
    cursor = root
    root_info = os.lstat(root)
    if stat.S_ISLNK(root_info.st_mode) or not stat.S_ISDIR(root_info.st_mode):
        raise ContractError("evidence root is not a real directory")
    for part in relative.parts[:-1]:
        cursor /= part
        try:
            info = os.lstat(cursor)
        except FileNotFoundError:
            return
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
            raise ContractError("authenticated path contains a symlink or non-directory ancestor")


def _read_stable(path: Path, root: Path, maximum: int = MAX_JSON_BYTES) -> bytes:
    _check_ancestors(path, root)
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError as exc:
        raise ContractError("authenticated file is unreadable") from exc
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise ContractError("authenticated path is not a regular file")
        chunks: list[bytes] = []
        total = 0
        while True:
            block = os.read(fd, min(1024 * 1024, maximum - total + 1))
            if not block:
                break
            total += len(block)
            if total > maximum:
                raise ContractError("authenticated file exceeds bound")
            chunks.append(block)
        after = os.fstat(fd)
        if _identity(before) != _identity(after):
            raise ContractError("authenticated file changed while hashing")
        return b"".join(chunks)
    except OSError as exc:
        raise ContractError("authenticated file read failed") from exc
    finally:
        os.close(fd)


def _members(base: Path, entries: dict[str, Any]) -> None:
    if not isinstance(entries, dict):
        raise ContractError("seal member map is malformed")
    for relative, item in entries.items():
        if not isinstance(relative, str) or not isinstance(item, dict):
            raise ContractError("seal member metadata is malformed")
        raw = _read_stable(base / relative, base)
        if len(raw) != item.get("bytes") or hashlib.sha256(raw).hexdigest() != item.get("sha256"):
            raise ContractError(f"sealed member changed: {relative}")


def load_contract(path: Path = CONTRACT_SOURCE) -> tuple[dict[str, Any], bytes]:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ContractError("continuation contract is not a regular file")
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != CONTRACT_SHA256:
        raise ContractError("continuation contract hash mismatch")
    value = _json(raw)
    if not isinstance(value, dict) or value.get("schema") != SCHEMA or value.get("kind") != "PRELAUNCH_CONTINUATION":
        raise ContractError("continuation contract identity mismatch")
    if value.get("authority") != "SERVER_ISOLATION":
        raise ContractError("continuation contract authority mismatch")
    fixed = value.get("fixedPaths")
    if not isinstance(fixed, list) or len(fixed) != 58:
        raise ContractError("continuation authority must contain exactly 58 paths")
    paths = [item.get("path") for item in fixed if isinstance(item, dict)]
    if len(paths) != 58 or len(set(paths)) != 58:
        raise ContractError("continuation authority paths are not unique")
    if value.get("evidence", {}).get("fixedStatusCount") != 58:
        raise ContractError("continuation status count is not exact")
    return value, raw


def _verify_known_failure(value: dict[str, Any], root: Path) -> None:
    failure = value["knownFailure"]
    current = _read_stable(root / "generic-current-failure.json", root)
    original_result = _read_stable(root / "result.json", root)
    result = _read_stable(root / "capture-failure/wine-result.json", root)
    current_value = _json(current)
    original_result_value = _json(original_result)
    result_value = _json(result)
    if (current_value.get("runId"), current_value.get("stage"), current_value.get("status"),
            current_value.get("terminationUnknown"), current_value.get("quiescenceConfirmed"),
            current_value.get("message")) != (
                failure["runId"], "EXITED", "FAILED", True, False,
                "filesDir is not the canonical private app root"):
        raise ContractError("known failure status is not exact")
    if (original_result_value.get("outcome"), original_result_value.get("error"), original_result_value.get("serverIsolationQualification")) != (
            "UNKNOWN", "prepared prefix receipt is missing before terminal RUN", False):
        raise ContractError("known Wine result is not exact")
    if hashlib.sha256(current).hexdigest() != failure["current"]["sha256"] or len(current) != failure["current"]["bytes"]:
        raise ContractError("known failure current seal mismatch")
    if hashlib.sha256(result).hexdigest() != failure["wineResult"]["sha256"] or len(result) != failure["wineResult"]["bytes"]:
        raise ContractError("known failure result seal mismatch")
    if (result_value.get("runId"), result_value.get("wsRunId"), result_value.get("nonce"),
            result_value.get("terminalStatus"), result_value.get("nativeStatus"),
            result_value.get("supervisorExit"), result_value.get("closureObserved"),
            result_value.get("qualification"), result_value.get("echild"), result_value.get("error")) != (
                failure["runId"], failure["wsRunId"], failure["nonce"], "UNKNOWN", "UNKNOWN",
                None, False, False, False, "filesDir is not the canonical private app root"):
        raise ContractError("captured Wine result status is not exact")
    original_pin = value["evidence"]["originalResult"]
    if len(original_result) != original_pin["bytes"] or hashlib.sha256(original_result).hexdigest() != original_pin["sha256"]:
        raise ContractError("original result seal mismatch")


def verify_external_evidence(value: dict[str, Any], root: Path = EVIDENCE_ROOT) -> None:
    if root != EVIDENCE_ROOT:
        raise ContractError("evidence root is not the fixed authority")
    evidence = value.get("evidence")
    if not isinstance(evidence, dict):
        raise ContractError("evidence metadata is missing")
    original_path = root / evidence["originalSeal"]["path"]
    supplemental_path = root / evidence["supplementalSeal"]["path"]
    original_raw = _read_stable(original_path, root)
    supplemental_raw = _read_stable(supplemental_path, root)
    if hashlib.sha256(original_raw).hexdigest() != ORIGINAL_SEAL_SHA256 or hashlib.sha256(supplemental_raw).hexdigest() != SUPPLEMENTAL_SEAL_SHA256:
        raise ContractError("external seal hash mismatch")
    original = _json(original_raw)
    supplemental = _json(supplemental_raw)
    _members(root, original.get("files"))
    closeout = supplemental_path.parent
    _members(closeout, supplemental.get("artifacts"))
    if supplemental.get("allOriginalFilesUnchangedBeforeAfter") is not True:
        raise ContractError("supplemental closeout is not read-only")
    provenance = _read_stable(root / "source-provenance.json", root)
    if len(provenance) != 4333 or hashlib.sha256(provenance).hexdigest() != "f88fe6878bf9771425e2cb9c5f13cac90c83cb9c3392f5536fe6bfefc3eb6965":
        raise ContractError("source provenance seal mismatch")
    status = _json(_read_stable(closeout / "status-captures.json", closeout))
    before = status.get("before", {}).get("entries")
    after = status.get("after", {}).get("entries")
    if not isinstance(before, dict) or not isinstance(after, dict) or len(after) != 58 or set(before) != set(after):
        raise ContractError("status capture path set is not exactly 58 entries")
    if {key: item.get("status") for key, item in before.items()} != {key: item.get("status") for key, item in after.items()}:
        raise ContractError("status capture statuses changed")
    expected = {item["path"]: item for item in value["fixedPaths"]}
    if set(expected) != set(after):
        raise ContractError("contract does not cover the captured status authority")
    for path, item in expected.items():
        observed = after[path]
        if observed.get("status") != item.get("capturedStatus"):
            raise ContractError(f"captured status mismatch: {path}")
        if item["status"] == "PRESENT":
            if observed.get("statAfter", {}).get("bytes") != item.get("bytes") or observed.get("sha256") != item.get("sha256"):
                raise ContractError(f"captured bytes/hash mismatch: {path}")
            if item.get("kind") == "protected-compiler" and item.get("binaryCopied") is not False:
                raise ContractError(f"protected compiler was copied: {path}")
            capture = observed.get("capturePath")
            if capture:
                artifact = supplemental.get("artifacts", {}).get(capture)
                if not artifact or artifact.get("bytes") != observed.get("bytesRead") or artifact.get("sha256") != observed.get("captureSha256"):
                    raise ContractError(f"captured artifact mismatch: {path}")
        elif item["status"] != "MISSING":
            raise ContractError(f"unsupported fixed status: {path}")
    _verify_known_failure(value, root)


def verify_contract_source() -> dict[str, Any]:
    value, _ = load_contract()
    verify_external_evidence(value)
    return value


def candidate_authority(value: dict[str, Any] | None = None) -> dict[str, Any]:
    if value is None:
        value, _ = load_contract()
    return dict(value["candidate"])
