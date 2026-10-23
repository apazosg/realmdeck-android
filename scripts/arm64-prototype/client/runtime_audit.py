#!/usr/bin/env python3
"""Audit the finite direct CASC runtime set and emit a small cache manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

from cascette_tools.core.cache import DiskCache

from catalog import CacheSource, CatalogError, _audit_direct_object, _is_required_runtime_object
from catalog import validate_catalog


def audit_catalog(catalog: dict[str, Any], cache_dir: Path | None = None, catalog_sha256: str | None = None) -> dict[str, Any]:
    """Verify every required direct object without copying or modifying payloads."""
    validate_catalog(catalog)
    if catalog_sha256 is None:
        catalog_sha256 = hashlib.sha256((json.dumps(catalog, indent=2, sort_keys=True) + "\n").encode("utf-8")).hexdigest()
    cache = DiskCache(cache_dir)
    source = CacheSource(cache)
    objects = [item for item in catalog["casc_bootstrap"]["objects"] if _is_required_runtime_object(item)]
    verified: list[dict[str, Any]] = []
    flags: Counter[str] = Counter()
    modes: Counter[str] = Counter()
    for item in objects:
        for source_item in item["sources"]:
            if source_item.get("kind") != "direct":
                raise CatalogError(f"required object is not directly cached: {item['content_key']}")
            ekey = source_item["ekey"]
            raw = source.get(ekey)
            if raw is None:
                raise CatalogError(f"required object is absent from cache: {ekey}")
            evidence = _audit_direct_object(
                raw,
                content_key=item["content_key"],
                role=item["roles"][0],
                expected_encoded_size=item["encoded_size"],
                expected_decoded_size=item["decoded_size"],
            )
            if evidence["encoded_size"] != item["encoded_size"] or evidence["encoded_size"] != source_item.get("encoded_size"):
                raise CatalogError(f"runtime source encoded size mismatch: {ekey}")
            if evidence["raw_sha256"] != source_item.get("sha256"):
                raise CatalogError(f"runtime source SHA256 mismatch: {ekey}")
            if source_item.get("raw_sha256") is not None and evidence["raw_sha256"] != source_item["raw_sha256"]:
                raise CatalogError(f"runtime source raw SHA256 mismatch: {ekey}")
            if item.get("decoded_md5") != item["content_key"] or evidence["decoded_md5"] != item["content_key"]:
                raise CatalogError(f"runtime decoded MD5 binding mismatch: {ekey}")
            cache_path = cache._get_cdn_cache_path(ekey, "data", source.cdn_path).resolve()
            verified.append(
                {
                    "source_path": str(cache_path),
                    "role": item["roles"][0],
                    "roles": item["roles"],
                    "ckey": item["content_key"],
                    "ekey": ekey,
                    "encoded_size": evidence["encoded_size"],
                    "decoded_size": evidence["decoded_size"],
                    "decoded_md5": evidence["decoded_md5"],
                    "raw_sha256": evidence["raw_sha256"],
                    "blte_flags": evidence["blte_flags"],
                    "blte_modes": evidence["blte_modes"],
                }
            )
            flags[str(evidence["blte_flags"])] += 1
            modes.update(evidence["blte_modes"])
    if len(verified) != len(objects):
        raise CatalogError(f"expected one direct source per required object: {len(objects)} != {len(verified)}")
    return {
        "schema": "wowforever.arm64-client-runtime-audit",
        "schema_version": 1,
        "catalog_schema": catalog["schema"],
        "catalog_sha256": catalog_sha256,
        "provenance": catalog["provenance"],
        "required_object_count": len(objects),
        "verified_source_count": len(verified),
        "blte_flags": dict(sorted(flags.items())),
        "blte_modes": dict(sorted(modes.items())),
        "objects": verified,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, default=None)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    catalog_raw = args.catalog.read_bytes()
    catalog = json.loads(catalog_raw)
    manifest = audit_catalog(catalog, args.cache_dir, hashlib.sha256(catalog_raw).hexdigest())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ("required_object_count", "verified_source_count", "blte_flags", "blte_modes")}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
