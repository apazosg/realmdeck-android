"""Authenticated fixed contract for the one approved Wine60 abandonment."""

from __future__ import annotations

import hashlib
import json
import os
import stat
from pathlib import Path
from typing import Any


CONTRACT_SOURCE = Path(__file__).with_name("wine-interrupted-run-disposition-contract.json")
EVIDENCE_ROOT = Path("/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/wine60-server-isolation-b5c368d6845f414cbbbe56db5745a744")
CONTRACT_SHA256 = "c198e20f582b90e5bc691e3e4712e8b2b1ecf33434bf1f86232c30be7fab2ac6"
ORIGINAL_SEAL_SHA256 = "ca8b128255f98c99639615c5e0faef65ddbed613c8f577750785149553a42003"
SUPPLEMENTAL_SEAL_SHA256 = "4e6b7bcc0bf586f7f7fd36e7eb5efd4c338ba531562aafc141855ec5be9f9af2"


class ContractError(ValueError):
    pass


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError("contract contains duplicate JSON keys")
        result[key] = value
    return result


def load_contract(path: Path = CONTRACT_SOURCE) -> tuple[dict[str, Any], bytes]:
    try:
        info = path.lstat()
        if not os.path.isfile(path) or path.is_symlink():
            raise ContractError("disposition contract is not a regular file")
        raw = path.read_bytes()
    except OSError as exc:
        raise ContractError("disposition contract is unreadable") from exc
    if hashlib.sha256(raw).hexdigest() != CONTRACT_SHA256:
        raise ContractError("disposition contract hash mismatch")
    try:
        value = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=_unique_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError, ContractError) as exc:
        raise ContractError("disposition contract is malformed") from exc
    if not isinstance(value, dict) or value.get("schema") != "wowforever.wine-interrupted-run-disposition/v1":
        raise ContractError("disposition contract identity mismatch")
    if value.get("kind") != "ABANDONED_ON_NEW_BOOT" or value.get("authority") != "SERVER_ISOLATION":
        raise ContractError("disposition contract authority mismatch")
    fixed_paths = value.get("fixedPaths")
    if not isinstance(fixed_paths, list) or len(fixed_paths) != 33:
        raise ContractError("disposition contract must contain exactly 33 fixed paths")
    seen: set[str] = set()
    for item in fixed_paths:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str) or item["path"] in seen:
            raise ContractError("disposition contract fixed paths are not unique")
        seen.add(item["path"])
        if item.get("status") == "PRESENT":
            if not isinstance(item.get("bytes"), int) or not isinstance(item.get("sha256"), str):
                raise ContractError("present fixed path lacks an exact seal")
            if item.get("capturedStatus") != "ok":
                raise ContractError("present fixed path lacks captured ok status")
        elif item.get("status") != "MISSING":
            raise ContractError("fixed path has an unsupported status")
        elif item.get("capturedStatus") != "missing":
            raise ContractError("missing fixed path lacks captured missing status")
    prospective = value.get("transactionPreconditions")
    if not isinstance(prospective, list) or len(prospective) != 4:
        raise ContractError("transaction preconditions are not exact")
    if any(item.get("status") != "MISSING" or item.get("prospective") is not True for item in prospective):
        raise ContractError("transaction preconditions are not prospective missing paths")
    if seen.intersection(item.get("path") for item in prospective):
        raise ContractError("prospective transaction paths are sealed fixed paths")
    return value, raw


def _check_ancestors(path: Path, root: Path) -> None:
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise ContractError("sealed path escaped evidence root") from exc
    cursor = root
    try:
        root_info = os.lstat(root)
    except OSError as exc:
        raise ContractError("evidence root is unreadable") from exc
    if stat.S_ISLNK(root_info.st_mode) or not stat.S_ISDIR(root_info.st_mode):
        raise ContractError("evidence root is not a real directory")
    try:
        relative_parts = path.relative_to(root).parts
    except ValueError as exc:
        raise ContractError("sealed path escaped evidence root") from exc
    if any(part == ".." for part in relative_parts):
        raise ContractError("sealed path contains parent traversal")
    for part in relative_parts[:-1]:
        cursor = cursor / part
        try:
            info = os.lstat(cursor)
        except FileNotFoundError:
            return
        except OSError as exc:
            raise ContractError("sealed path ancestor is unreadable") from exc
        if os.path.islink(cursor) or not stat.S_ISDIR(info.st_mode):
            raise ContractError("sealed path contains a symlink or non-directory ancestor")


def _identity(info: os.stat_result) -> tuple[int, ...]:
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size,
            info.st_mtime_ns, info.st_ctime_ns)


def _read_stable(path: Path, root: Path, maximum: int) -> bytes:
    _check_ancestors(path, root)
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError as exc:
        raise ContractError("sealed file is unreadable") from exc
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise ContractError("sealed path is not a regular file")
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(fd, min(1024 * 1024, maximum - total + 1))
            if not chunk:
                break
            total += len(chunk)
            if total > maximum:
                raise ContractError("sealed file exceeds bound")
            chunks.append(chunk)
        after = os.fstat(fd)
        if _identity(before) != _identity(after):
            raise ContractError("sealed file changed while hashing")
        return b"".join(chunks)
    except OSError as exc:
        raise ContractError("sealed file read failed") from exc
    finally:
        os.close(fd)


def _pin(path: Path, expected: dict[str, Any], root: Path) -> None:
    _check_ancestors(path, root)
    if expected.get("status") == "MISSING":
        try:
            info = os.lstat(path)
        except FileNotFoundError:
            return
        except OSError as exc:
            raise ContractError("sealed missing path is unreadable") from exc
        if os.path.islink(path) or info is not None:
            raise ContractError(f"sealed missing path became present: {expected.get('path')}")
    if expected.get("status") != "PRESENT":
        raise ContractError(f"sealed path status mismatch: {expected.get('path')}")
    raw = _read_stable(path, root, int(expected.get("bytes", 0)))
    if len(raw) != expected.get("bytes") or hashlib.sha256(raw).hexdigest() != expected.get("sha256"):
        raise ContractError(f"sealed path hash mismatch: {expected.get('path')}")


def _verify_manifest_members(base: Path, members: dict[str, Any]) -> None:
    if not isinstance(members, dict):
        raise ContractError("seal member map is malformed")
    for relative, expected in members.items():
        if not isinstance(expected, dict) or not isinstance(expected.get("bytes"), int) or not isinstance(expected.get("sha256"), str):
            raise ContractError("seal member metadata is malformed")
        raw = _read_stable(base / relative, base, expected["bytes"])
        if len(raw) != expected["bytes"] or hashlib.sha256(raw).hexdigest() != expected["sha256"]:
            raise ContractError(f"sealed member changed: {relative}")


def verify_external_evidence(contract: dict[str, Any], root: Path = EVIDENCE_ROOT) -> None:
    evidence = contract.get("evidence")
    if not isinstance(evidence, dict) or root != EVIDENCE_ROOT:
        raise ContractError("evidence root is not the fixed authority")
    original = root / str(evidence["originalSeal"]["path"])
    supplemental = root / str(evidence["supplementalSeal"]["path"])
    original_raw = _read_stable(original, root, 2 * 1024 * 1024)
    supplemental_raw = _read_stable(supplemental, root, 2 * 1024 * 1024)
    if hashlib.sha256(original_raw).hexdigest() != ORIGINAL_SEAL_SHA256:
        raise ContractError("original seal changed")
    if hashlib.sha256(supplemental_raw).hexdigest() != SUPPLEMENTAL_SEAL_SHA256:
        raise ContractError("supplemental seal changed")
    source = root / str(evidence["sourceProvenance"]["path"])
    source_raw = _read_stable(source, root, int(evidence["sourceProvenance"]["bytes"]))
    if len(source_raw) != evidence["sourceProvenance"]["bytes"] or hashlib.sha256(source_raw).hexdigest() != evidence["sourceProvenance"]["sha256"]:
        raise ContractError("source provenance changed")
    seals = json.loads(original_raw.decode("utf-8", "strict"))
    supplemental_value = json.loads(supplemental_raw.decode("utf-8", "strict"))
    if seals.get("schema") != 1 or not supplemental_value.get("allOriginalFilesUnchangedBeforeAfter"):
        raise ContractError("seal metadata is not closed")
    _verify_manifest_members(root, seals.get("files"))
    closeout = root / "read-only-closeout-e4e8730ff3264482ab07db3cce79dbc3"
    _verify_manifest_members(closeout, supplemental_value.get("artifacts"))
    status_values = {}
    for name in ("snapshot-01-status.json", "snapshot-02-status.json"):
        status_raw = _read_stable(closeout / name, closeout, 2 * 1024 * 1024)
        status_values[name] = json.loads(status_raw.decode("utf-8", "strict"))
    before_entries = status_values["snapshot-01-status.json"].get("entries")
    after_entries = status_values["snapshot-02-status.json"].get("entries")
    if not isinstance(before_entries, dict) or not isinstance(after_entries, dict) or set(before_entries) != set(after_entries):
        raise ContractError("snapshot status entry keys changed")
    if {key: value.get("status") for key, value in before_entries.items()} != {key: value.get("status") for key, value in after_entries.items()}:
        raise ContractError("snapshot status entry statuses changed")
    fixed = {item["path"]: item for item in contract.get("fixedPaths", [])}
    if set(fixed) != set(after_entries) or len(fixed) != 33:
        raise ContractError("contract is not the exact captured 33-path authority")
    for device_path, item in fixed.items():
        status_entry = after_entries[device_path]
        if status_entry.get("status") != item.get("capturedStatus"):
            raise ContractError(f"captured status mismatch: {device_path}")
        if item.get("capturedStatus") == "ok":
            metadata = status_entry.get("metadata") or {}
            expected_bytes = status_entry.get("expectedBytes", metadata.get("bytes"))
            expected_sha = status_entry.get("expectedSha256", metadata.get("sha256"))
            if item.get("bytes") != expected_bytes or item.get("sha256") != expected_sha:
                raise ContractError(f"captured status seal mismatch: {device_path}")
            if item.get("kind") == "protected-compiler":
                if item.get("binaryCopied") is not False or item.get("expectedBytes") != status_entry.get("expectedBytes") or item.get("expectedSha256") != status_entry.get("expectedSha256"):
                    raise ContractError(f"protected compiler metadata mismatch: {device_path}")
            else:
                capture = item.get("capturePath")
                if not isinstance(capture, str) or capture != status_entry.get("capturePath"):
                    raise ContractError(f"captured path mismatch: {device_path}")
                _pin(closeout / capture, item, closeout)
    prior = contract["prior"]
    if prior["current"]["sha256"] != "745eff1e97de89de1de4c5665ce064564022686cf8409b613cce71aebc022cf2" or prior["binding"]["sha256"] != "1c7d568d4ec506bb7a0a2233fa12ec1b453bf64143d5835f9e2bdf083b28bd22":
        raise ContractError("prior authority pins are not exact")


def verify_contract_source() -> dict[str, Any]:
    value, _ = load_contract()
    verify_external_evidence(value)
    return value
