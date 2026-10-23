#!/usr/bin/env python3
"""Tiny independent cascette CASC golden, external only, for Kotlin writer tests."""
import argparse
import hashlib
import json
import tempfile
from pathlib import Path
from cascette_tools.core.local_storage import LocalStorage


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.output.parent.is_dir()
    raw = b'BLTE\0\0\0\0Ncasc-golden'
    with tempfile.TemporaryDirectory(prefix='casc-golden-', dir=args.output.parent) as directory:
        local = LocalStorage(Path(directory))
        # Reconstruction keys include MD5(data_path). Kotlin writes in Data.new
        # then atomically promotes it. Use the SAME staged absolute path for the
        # golden, not an unrelated temporary root with different valid keys.
        local.data_path = Path(directory) / 'Data.new' / 'data'
        local.initialize()
        local.write_content(bytes.fromhex('0123456789abcdef0123456789abcdef'), raw)
        local.flush_indices()
        result = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(local.data_path.iterdir()) if p.is_file()}
        args.output.write_text(json.dumps({'staging_root': directory, 'files': result}, indent=2, sort_keys=True) + '\n')
        print(f'CASCETTE-GOLDEN {len(result)} files -> {args.output}')


if __name__ == '__main__':
    main()
