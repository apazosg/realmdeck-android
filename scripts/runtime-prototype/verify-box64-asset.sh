#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ARCHIVE="${1:?usage: verify-box64-asset.sh BOX64_TZST}"
command -v tar >/dev/null || { printf 'BOX64-ASSET-FAIL: tar is required\n' >&2; exit 2; }
command -v zstd >/dev/null || { printf 'BOX64-ASSET-FAIL: zstd is required\n' >&2; exit 2; }
command -v file >/dev/null || { printf 'BOX64-ASSET-FAIL: file is required\n' >&2; exit 2; }
command -v readelf >/dev/null || { printf 'BOX64-ASSET-FAIL: readelf is required\n' >&2; exit 2; }
[[ -f "$ARCHIVE" ]] || { printf 'BOX64-ASSET-FAIL: archive missing: %s\n' "$ARCHIVE" >&2; exit 1; }

entries="$(tar --zstd -tf "$ARCHIVE")"
[[ "$entries" == $'./\n./usr/\n./usr/local/\n./usr/local/bin/\n./usr/local/bin/box64' ]] \
    || { printf 'BOX64-ASSET-FAIL: unexpected archive listing\n%s\n' "$entries" >&2; exit 1; }
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
tar --zstd -xOf "$ARCHIVE" ./usr/local/bin/box64 > "$tmp"
file_output="$(file "$tmp")"
readelf_header="$(readelf -h "$tmp")"
readelf_programs="$(readelf -l "$tmp")"
printf 'archive=%s\n' "$ARCHIVE"
printf 'archive_sha256=%s\n' "$(sha256sum "$ARCHIVE" | cut -d' ' -f1)"
printf 'archive_entry=./usr/local/bin/box64\n'
printf 'archive_entry_size=%s\n' "$(stat --format='%s' "$tmp")"
printf 'archive_entry_mode=644\n'
printf 'file=%s\n' "$file_output"
printf '%s\n' "$readelf_header" | while IFS= read -r line; do
    case "$line" in
        *"Machine:"*) printf 'elf_%s\n' "$line";;
        *"Class:"*) printf 'elf_%s\n' "$line";;
    esac
done
printf '%s\n' "$readelf_programs" | while IFS= read -r line; do
    case "$line" in
        *INTERP*) printf 'elf_%s\n' "$line";;
    esac
done
case "$file_output" in *"ELF 64-bit"*"ARM aarch64"*) ;; *) printf 'BOX64-ASSET-FAIL: unexpected file identity\n' >&2; exit 1;; esac
case "$readelf_header" in *AArch64*) ;; *) printf 'BOX64-ASSET-FAIL: unexpected ELF machine\n' >&2; exit 1;; esac
case "$readelf_programs" in *INTERP*) ;; *) printf 'BOX64-ASSET-FAIL: PT_INTERP is absent\n' >&2; exit 1;; esac
