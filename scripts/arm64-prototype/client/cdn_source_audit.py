#!/usr/bin/env python3
"""Perform bounded, read-only checks of catalog CDN source routes.

Every loose-file route is probed with at most a 64-byte ranged GET.  At most
one explicitly selected direct object may be downloaded in full, and the
aggregate response-body budget is 16 MiB.  The client never follows redirects
and only accepts the official Blizzard HTTPS hosts.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import math
import re
import sys
from copy import deepcopy
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from catalog import CDN_HOSTS, CDN_PATH, CatalogError, _audit_direct_object, validate_catalog


PROBE_BYTES = 64
FULL_DOWNLOAD_LIMIT = 4 * 1024 * 1024
RESPONSE_BODIES_MAX = 16 * 1024 * 1024
TARGET_EKEY = "e092b002f52c0827ce4b65d534d7316d"


class AuditError(ValueError):
    """A source audit request cannot be safely performed."""


def _expected_path(kind: str, digest: str, suffix: str = "") -> str:
    content = "data" if kind in {"data", "index"} else "patch"
    return f"/{CDN_PATH}/{content}/{digest[:2]}/{digest[2:4]}/{digest}{suffix}"


def validate_official_url(url: str, *, digest: str, kind: str = "data", suffix: str = "") -> tuple[str, str]:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname is None or parsed.hostname.lower() not in CDN_HOSTS:
        raise AuditError(f"non-official HTTPS URL: {url}")
    if parsed.username or parsed.password or parsed.port is not None or parsed.query or parsed.fragment:
        raise AuditError(f"URL contains unsafe authority/query components: {url}")
    if parsed.path != _expected_path(kind, digest.lower(), suffix):
        raise AuditError(f"URL does not bind to {kind}/{digest}: {url}")
    return parsed.hostname.lower(), parsed.path


def validate_timeout(timeout: float) -> float:
    if not math.isfinite(timeout) or timeout <= 0:
        raise AuditError("timeout must be finite and positive")
    return timeout


def ensure_external_path(path: Path) -> Path:
    """Reject output paths which resolve into this repository or via symlinks."""
    repo_root = Path(__file__).resolve().parents[3]
    absolute = path.absolute()
    resolved = path.resolve(strict=False)
    if resolved == repo_root or repo_root in resolved.parents:
        raise AuditError(f"audit output must be outside the repository: {path}")
    current = absolute
    while current != current.parent:
        if current.is_symlink():
            raise AuditError(f"audit output path contains a symlink: {path}")
        current = current.parent
    return resolved


def _reserve_body_budget(body_budget: list[int], amount: int) -> None:
    if amount < 0 or body_budget[0] + amount > RESPONSE_BODIES_MAX:
        raise AuditError("aggregate response-body budget exceeded")


def _read_limited(response: Any, limit: int) -> bytes:
    chunks: list[bytes] = []
    remaining = limit
    while remaining:
        chunk = response.read(min(remaining, 16 * 1024))
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def _request(
    url: str,
    *,
    expected_digest: str,
    start: int,
    end: int,
    expected_total: int,
    exact_total: bool,
    timeout: float,
    body_limit: int,
    body_budget: list[int],
) -> dict[str, Any]:
    if not re.fullmatch(r"[0-9a-f]{32}", expected_digest):
        raise AuditError(f"invalid expected CDN digest: {expected_digest}")
    if start < 0 or end < start or end - start + 1 != PROBE_BYTES:
        raise AuditError(f"probe range must be exactly {PROBE_BYTES} bytes")
    if expected_total <= 0 or end >= expected_total:
        raise AuditError("probe range is outside the expected object")
    validate_timeout(timeout)
    _reserve_body_budget(body_budget, body_limit)
    host, path = validate_official_url(url, digest=expected_digest)
    connection = http.client.HTTPSConnection(host, timeout=timeout)
    response: Any = None
    try:
        connection.request("GET", path, headers={"Range": f"bytes={start}-{end}", "Accept-Encoding": "identity"})
        response = connection.getresponse()
        content_length = response.getheader("Content-Length")
        content_range = response.getheader("Content-Range")
        body = _read_limited(response, body_limit)
        declared = int(content_length) if content_length and content_length.isdigit() else None
        body_budget[0] += len(body)
        range_match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+|\*)", content_range or "")
        actual_start = int(range_match.group(1)) if range_match else None
        actual_end = int(range_match.group(2)) if range_match else None
        actual_total = int(range_match.group(3)) if range_match and range_match.group(3) != "*" else None
        valid = (
            response.status == 206
            and declared == PROBE_BYTES
            and len(body) == PROBE_BYTES
            and actual_start == start
            and actual_end == end
            and actual_total is not None
            and actual_total >= expected_total
            and (actual_total == expected_total if exact_total else True)
        )
        return {
            "url": url,
            "status": response.status,
            "content_range": content_range,
            "content_length": declared if declared is not None else content_length,
            "body_bytes_read": len(body),
            "body_md5": hashlib.md5(body).hexdigest() if body else None,
            "range_requested": f"bytes={start}-{end}",
            "expected_digest": expected_digest,
            "expected_total": expected_total,
            "exact_total": exact_total,
            "valid": valid,
            "failure": None if valid else "response did not provide exact HTTP 206 range evidence",
        }
    finally:
        if response is not None:
            response.close()
        connection.close()


def path_digest(url: str) -> str:
    path = urlsplit(url).path
    match = re.search(r"/([0-9a-f]{32})(?:\.index)?$", path, re.IGNORECASE)
    if not match:
        raise AuditError(f"CDN URL has no hash-shaped final path component: {url}")
    return match.group(1).lower()


def _full_download(
    url: str,
    *,
    ekey: str,
    expected_size: int,
    expected_content_key: str,
    expected_decoded_size: int,
    timeout: float,
    output: Path,
    body_budget: list[int],
) -> dict[str, Any]:
    validate_timeout(timeout)
    if not re.fullmatch(r"[0-9a-f]{32}", ekey) or expected_size <= 0:
        raise AuditError("invalid full-download EKey or size")
    if expected_size > FULL_DOWNLOAD_LIMIT:
        raise AuditError(f"full-download target exceeds 4 MiB bound: {expected_size}")
    _reserve_body_budget(body_budget, expected_size)
    host, path = validate_official_url(url, digest=ekey)
    connection = http.client.HTTPSConnection(host, timeout=timeout)
    response: Any = None
    chunks: list[bytes] = []
    try:
        connection.request("GET", path, headers={"Accept-Encoding": "identity"})
        response = connection.getresponse()
        content_length = response.getheader("Content-Length")
        declared = int(content_length) if content_length and content_length.isdigit() else None
        if response.status != 200:
            return {"url": url, "expected_digest": ekey, "status": response.status, "content_range": response.getheader("Content-Range"), "content_length": declared, "body_bytes_read": 0, "body_md5": None, "valid": False, "failure": "full object did not return HTTP 200", "expected_size": expected_size}
        if declared != expected_size:
            return {"url": url, "expected_digest": ekey, "status": response.status, "content_range": response.getheader("Content-Range"), "content_length": declared, "body_bytes_read": 0, "body_md5": None, "valid": False, "failure": "full object Content-Length mismatch", "expected_size": expected_size}
        while True:
            chunk = response.read(min(64 * 1024, FULL_DOWNLOAD_LIMIT - sum(map(len, chunks)) + 1))
            if not chunk:
                break
            chunks.append(chunk)
            total = sum(map(len, chunks))
            body_budget[0] += len(chunk)
            if total > FULL_DOWNLOAD_LIMIT or body_budget[0] > RESPONSE_BODIES_MAX:
                raise AuditError("full response exceeded bounded body budget")
        body = b"".join(chunks)
        if len(body) != expected_size:
            return {"url": url, "expected_digest": ekey, "status": response.status, "content_range": response.getheader("Content-Range"), "content_length": declared, "body_bytes_read": len(body), "body_md5": hashlib.md5(body).hexdigest() if body else None, "valid": False, "failure": "full object body size mismatch", "expected_size": expected_size}
        try:
            audit = _audit_direct_object(
                body,
                content_key=expected_content_key,
                role="full-download",
                expected_encoded_size=expected_size,
                expected_decoded_size=expected_decoded_size,
            )
        except CatalogError as exc:
            return {"url": url, "expected_digest": ekey, "status": response.status, "content_range": response.getheader("Content-Range"), "content_length": declared, "body_bytes_read": len(body), "body_md5": hashlib.md5(body).hexdigest(), "valid": False, "failure": str(exc), "expected_size": expected_size}
        output = ensure_external_path(output)
        if output.exists() and output.is_symlink():
            raise AuditError(f"full-download output is a symlink: {output}")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(body)
        return {
            "url": url,
            "expected_digest": ekey,
            "status": response.status,
            "content_range": response.getheader("Content-Range"),
            "content_length": declared,
            "body_bytes_read": len(body),
            "body_md5": hashlib.md5(body).hexdigest(),
            "raw_sha256": audit["raw_sha256"],
            "decoded_md5": audit["decoded_md5"],
            "decoded_size": audit["decoded_size"],
            "expected_size": expected_size,
            "expected_content_key": expected_content_key,
            "valid": audit["decoded_md5"] == expected_content_key and audit["decoded_size"] == expected_decoded_size,
            "failure": None if audit["decoded_md5"] == expected_content_key and audit["decoded_size"] == expected_decoded_size else "full object decoded identity mismatch",
            "saved_to": str(output),
        }
    finally:
        if response is not None:
            response.close()
        connection.close()


def canonical_catalog_sha256(catalog: dict[str, Any]) -> str:
    serialized = (json.dumps(catalog, indent=2, sort_keys=True) + "\n").encode("utf-8")
    return hashlib.sha256(serialized).hexdigest()


def _validate_catalog_for_audit(catalog: dict[str, Any]) -> None:
    try:
        validate_catalog(catalog)
    except (CatalogError, KeyError, TypeError, ValueError) as exc:
        raise AuditError(f"audit catalog is malformed: {exc}") from exc


def audit_catalog(
    catalog: dict[str, Any],
    *,
    full_ekey: str | None,
    output_dir: Path,
    timeout: float,
    catalog_sha256: str | None = None,
) -> dict[str, Any]:
    validate_timeout(timeout)
    output_dir = ensure_external_path(output_dir)
    body_budget = [0]
    routes: list[dict[str, Any]] = []
    unsupported: list[dict[str, Any]] = []
    for item in catalog.get("loose_files", []):
        sources = item.get("sources", [])
        if len(sources) != 1:
            unsupported.append({"relative_path": item.get("relative_path"), "status": "unsupported-source-cardinality"})
            continue
        source = sources[0]
        kind = source.get("kind")
        if kind not in {"direct", "direct-object", "archive-range"}:
            unsupported.append({"relative_path": item.get("relative_path"), "status": f"unsupported-source-kind:{kind}"})
            continue
        if kind == "archive-range":
            digest = source.get("archive", "")
            start = source.get("offset", -1)
            end = start + source.get("length", 0) - 1
            expected_total = end + 1
            exact_total = False
        else:
            digest = source.get("ekey", "")
            start, end = 0, PROBE_BYTES - 1
            expected_total = source.get("encoded_size", 0)
            exact_total = True
        if not re.fullmatch(r"[0-9a-f]{32}", digest) or start < 0 or end < start:
            unsupported.append({"relative_path": item.get("relative_path"), "status": "unsupported-source-fields"})
            continue
        try:
            probe_end = min(end, start + PROBE_BYTES - 1)
            if probe_end - start + 1 != PROBE_BYTES:
                raise AuditError("source range is shorter than the 64-byte audit probe")
            result = _request(
                source["url"],
                expected_digest=digest,
                start=start,
                end=probe_end,
                expected_total=expected_total,
                exact_total=exact_total,
                timeout=timeout,
                body_limit=PROBE_BYTES,
                body_budget=body_budget,
            )
            result.update({"relative_path": item["relative_path"], "source_kind": kind, "ekey": source.get("ekey")})
            routes.append(result)
        except (AuditError, OSError, TimeoutError, KeyError, TypeError, ValueError) as exc:
            routes.append({"relative_path": item.get("relative_path"), "source_kind": kind, "status": "request-error", "valid": False, "failure": str(exc), "expected_digest": digest, "expected_range": f"bytes={start}-{min(end, start + PROBE_BYTES - 1)}"})

    full_download = None
    if full_ekey is not None:
        matches = [(item, item["sources"][0]) for item in catalog.get("loose_files", []) if item.get("sources") and item["sources"][0].get("ekey", "").lower() == full_ekey.lower()]
        if len(matches) != 1:
            raise AuditError(f"full-download EKey must identify exactly one loose record: {full_ekey}")
        item, source = matches[0]
        if source.get("kind") not in {"direct", "direct-object"}:
            raise AuditError("full-download target must be a direct CDN object")
        full_download = _full_download(
            source["url"],
            ekey=full_ekey.lower(),
            expected_size=source["encoded_size"],
            expected_content_key=item["content_key"],
            expected_decoded_size=item["decoded_size"],
            timeout=timeout,
            output=output_dir / full_ekey.lower(),
            body_budget=body_budget,
        )
    failures = [route for route in routes if not route.get("valid", False)]
    if full_download is not None and not full_download.get("valid", False):
        failures.append(full_download)
    catalog_sha256 = catalog_sha256 or canonical_catalog_sha256(catalog)
    return {
        "status": "ok" if not failures and not unsupported and body_budget[0] <= RESPONSE_BODIES_MAX else "blocked",
        "catalog_sha256": catalog_sha256,
        "catalog_loose_file_count": len(catalog.get("loose_files", [])),
        "checked_route_count": len(routes),
        "unsupported_count": len(unsupported),
        "failure_count": len(failures) + len(unsupported),
        "routes": routes,
        "unsupported": unsupported,
        "full_download": full_download,
        "aggregate_response_body_bytes": body_budget[0],
        "aggregate_response_body_limit": RESPONSE_BODIES_MAX,
    }


def validate_audit_report(report: dict[str, Any], catalog: dict[str, Any]) -> dict[str, Any]:
    """Validate an existing report without issuing any network request."""
    _validate_catalog_for_audit(catalog)
    expected_sha = canonical_catalog_sha256(catalog)
    if report.get("catalog_sha256") != expected_sha:
        raise AuditError("audit report is not bound to the supplied catalog serialization")
    loose = {item.get("relative_path"): item for item in catalog.get("loose_files", [])}
    routes = report.get("routes", [])
    if report.get("catalog_loose_file_count") != len(loose) or report.get("checked_route_count") != len(loose):
        raise AuditError("audit report route count does not cover every loose file")
    if report.get("status") != "ok" or report.get("unsupported_count") != 0 or report.get("failure_count") != 0:
        raise AuditError("audit report records failed or unsupported checks")
    seen: set[str] = set()
    for route in routes:
        relative = route.get("relative_path")
        if relative in seen or relative not in loose:
            raise AuditError(f"audit report has an unexpected or duplicate route: {relative}")
        seen.add(relative)
        source = loose[relative]["sources"][0]
        kind = source["kind"]
        digest = source["archive"] if kind == "archive-range" else source["ekey"]
        start = source.get("offset", 0) if kind == "archive-range" else 0
        expected_end = start + PROBE_BYTES - 1
        expected_total = source.get("encoded_size") if kind != "archive-range" else start + source["length"]
        if route.get("url") != source.get("url") or route.get("expected_digest") != digest:
            raise AuditError(f"audit report source binding mismatch: {relative}")
        if route.get("range_requested") != f"bytes={start}-{expected_end}":
            raise AuditError(f"audit report range binding mismatch: {relative}")
        validate_official_url(route["url"], digest=digest)
        content_match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", route.get("content_range", ""))
        if not route.get("valid") or route.get("status") != 206 or route.get("body_bytes_read") != PROBE_BYTES or route.get("content_length") != PROBE_BYTES or not content_match:
            raise AuditError(f"audit report lacks exact 206 evidence: {relative}")
        actual_total = int(content_match.group(3))
        if (int(content_match.group(1)), int(content_match.group(2))) != (start, expected_end) or actual_total < expected_total or (kind != "archive-range" and actual_total != expected_total):
            raise AuditError(f"audit report Content-Range mismatch: {relative}")
    if seen != set(loose):
        raise AuditError("audit report omitted a loose-file route")
    full = report.get("full_download")
    if full is not None:
        matches = [item for item in catalog.get("loose_files", []) if item.get("sources") and item["sources"][0].get("ekey") == full.get("expected_digest", full.get("ekey"))]
        if len(matches) != 1:
            raise AuditError("audit report full-download EKey is not uniquely bound")
        source = matches[0]["sources"][0]
        if not full.get("valid") or full.get("status") != 200 or full.get("url") != source.get("url") or full.get("content_length") != source.get("encoded_size") or full.get("body_bytes_read") != source.get("encoded_size") or full.get("decoded_md5") != matches[0].get("content_key") or full.get("decoded_size") != matches[0].get("decoded_size"):
            raise AuditError("audit report full-download evidence is incomplete")
        if source.get("sha256") is not None and full.get("raw_sha256") != source["sha256"]:
            raise AuditError("audit report full-download raw SHA differs from the catalog")
    actual_body_bytes = sum(route["body_bytes_read"] for route in routes) + (full["body_bytes_read"] if full is not None else 0)
    if report.get("aggregate_response_body_bytes") != actual_body_bytes:
        raise AuditError("audit report aggregate byte count does not match its response bodies")
    if actual_body_bytes > RESPONSE_BODIES_MAX:
        raise AuditError("audit report exceeds aggregate response-body budget")
    return {"status": "ok", "catalog_sha256": expected_sha, "loose_routes": len(routes), "full_download": full is not None}


def bind_existing_report(report: dict[str, Any], catalog: dict[str, Any]) -> dict[str, Any]:
    """Add deterministic binding/validity fields to an already collected report.

    This performs no I/O beyond the caller's JSON reads.  Existing response
    evidence must already satisfy the same checks as a live audit; failures
    remain failures rather than being converted into success.
    """
    _validate_catalog_for_audit(catalog)
    bound = deepcopy(report)
    loose = {item.get("relative_path"): item for item in catalog.get("loose_files", [])}
    failures = 0
    for route in bound.get("routes", []):
        item = loose.get(route.get("relative_path"))
        if item is None or len(item.get("sources", [])) != 1:
            route.update(valid=False, failure="route is not present in supplied catalog")
            failures += 1
            continue
        source = item["sources"][0]
        kind = source.get("kind")
        digest = source.get("archive") if kind == "archive-range" else source.get("ekey")
        start = source.get("offset", 0) if kind == "archive-range" else 0
        expected_end = start + PROBE_BYTES - 1
        expected_total = source.get("encoded_size") if kind != "archive-range" else start + source.get("length", 0)
        content_match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", route.get("content_range", ""))
        valid = (
            route.get("url") == source.get("url")
            and route.get("status") == 206
            and route.get("body_bytes_read") == PROBE_BYTES
            and route.get("content_length") == PROBE_BYTES
            and content_match is not None
            and int(content_match.group(1)) == start
            and int(content_match.group(2)) == expected_end
            and int(content_match.group(3)) >= expected_total
            and (int(content_match.group(3)) == expected_total if kind != "archive-range" else True)
        )
        route.update(
            expected_digest=digest,
            expected_total=expected_total,
            exact_total=kind != "archive-range",
            range_requested=f"bytes={start}-{expected_end}",
            valid=valid,
            failure=None if valid else "existing response evidence failed exact route binding",
        )
        failures += int(not valid)
    full = bound.get("full_download")
    if full is not None:
        ekey = full.get("expected_digest", full.get("ekey")) or path_digest(full.get("url", ""))
        matches = [item for item in catalog.get("loose_files", []) if item.get("sources") and item["sources"][0].get("ekey") == ekey]
        source = matches[0]["sources"][0] if len(matches) == 1 else {}
        valid = (
            len(matches) == 1
            and full.get("url") == source.get("url")
            and full.get("status") == 200
            and full.get("content_length") == source.get("encoded_size")
            and full.get("body_bytes_read") == source.get("encoded_size")
            and full.get("decoded_md5") == matches[0].get("content_key")
            and full.get("decoded_size") == matches[0].get("decoded_size")
            and (source.get("sha256") is None or full.get("raw_sha256") == source["sha256"])
        )
        full.update(expected_digest=ekey, expected_size=source.get("encoded_size"), valid=valid, failure=None if valid else "existing full-download evidence failed exact binding")
        failures += int(not valid)
    unsupported = bound.get("unsupported", [])
    body_counts = [route.get("body_bytes_read", 0) for route in bound.get("routes", [])]
    if full is not None:
        body_counts.append(full.get("body_bytes_read", 0))
    if any(not isinstance(count, int) or isinstance(count, bool) or count < 0 for count in body_counts):
        raise AuditError("existing report has invalid response-body byte counts")
    aggregate = sum(body_counts)
    bound.update(
        status="ok" if failures == 0 and not unsupported and aggregate <= RESPONSE_BODIES_MAX else "blocked",
        aggregate_response_body_bytes=aggregate,
        catalog_sha256=canonical_catalog_sha256(catalog),
        catalog_loose_file_count=len(loose),
        checked_route_count=len(bound.get("routes", [])),
        unsupported_count=len(unsupported),
        failure_count=failures + len(unsupported),
    )
    return bound


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="external JSON report")
    parser.add_argument("--output-dir", type=Path, default=None, help="external directory for one verified full object")
    parser.add_argument("--full-ekey", default=None, help="at most one direct EKey to download fully")
    parser.add_argument("--existing-report", type=Path, default=None, help="bind and validate an existing report without network access")
    parser.add_argument("--timeout", type=float, default=10.0)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    output = ensure_external_path(args.output)
    if output.exists() and output.is_symlink():
        raise SystemExit("audit report output is a symlink")
    if args.existing_report is not None:
        existing = json.loads(args.existing_report.read_text(encoding="utf-8"))
        bound = bind_existing_report(existing, catalog)
        try:
            result = validate_audit_report(bound, catalog)
        except AuditError as exc:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(bound, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            print(json.dumps({"status": "blocked", "report": str(output), "error": str(exc)}, sort_keys=True))
            return 2
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(bound, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(result | {"report": str(output)}, sort_keys=True))
        return 0
    try:
        if args.output_dir is None:
            raise AuditError("--output-dir is required for a live audit")
        report = audit_catalog(catalog, full_ekey=args.full_ekey, output_dir=args.output_dir, timeout=args.timeout)
    except (AuditError, CatalogError, OSError, TimeoutError) as exc:
        print(json.dumps({"status": "blocked", "error": str(exc)}, sort_keys=True))
        return 2
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "report": str(output), "checked": report["checked_route_count"], "unsupported": report["unsupported_count"], "failures": report["failure_count"], "body_bytes": report["aggregate_response_body_bytes"]}, sort_keys=True))
    return 0 if report["status"] == "ok" else 2


if __name__ == "__main__":
    sys.exit(main())
