#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 --check|--write" >&2
}

if [[ $# -ne 1 ]]; then
  usage
  exit 2
fi

mode="$1"
case "$mode" in
  --check|--write) ;;
  *)
    usage
    exit 2
    ;;
esac

workspace_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source_dir="$workspace_root/.agents/skills"

mapfile -t source_files < <(find "$source_dir" -mindepth 2 -maxdepth 2 -name SKILL.md -type f -print | sort)
if [[ ${#source_files[@]} -eq 0 ]]; then
  echo "No shared skills found in $source_dir" >&2
  exit 1
fi

mapfile -t system_files < <(find "$workspace_root" -type f -path '*/.agents/specs/SYSTEM.md' -not -path '*/.venv/*' -print | sort)
if [[ ${#system_files[@]} -eq 0 ]]; then
  echo "No repositories with .agents/specs/SYSTEM.md found" >&2
  exit 1
fi

check_no_extra_skills() {
  local target_dir="$1"
  local mirror_file
  local relative_file
  local unexpected=0

  while IFS= read -r mirror_file; do
    relative_file=${mirror_file#"$target_dir"/}
    if [[ ! -f "$source_dir/$relative_file" || -L "$mirror_file" ]]; then
      printf 'Unexpected mirror skill: %s\n' "$mirror_file" >&2
      unexpected=1
    fi
  done < <(find "$target_dir" -mindepth 2 -maxdepth 2 -name SKILL.md \( -type f -o -type l \) -print | sort)

  return "$unexpected"
}

failed=0
for system_file in "${system_files[@]}"; do
  repo_root=${system_file%/.agents/specs/SYSTEM.md}
  target_dir="$repo_root/.agents/skills"

  if [[ "$mode" == "--check" && ! -d "$target_dir" ]]; then
    printf 'Missing mirror directory: %s\n' "$target_dir" >&2
    failed=1
    continue
  fi

  if [[ "$mode" == "--write" ]]; then
    mkdir -p "$target_dir"
  fi

  if ! check_no_extra_skills "$target_dir"; then
    failed=1
    continue
  fi

  for source_file in "${source_files[@]}"; do
    relative_file=${source_file#"$source_dir"/}
    mirror_file="$target_dir/$relative_file"

    if [[ -L "$mirror_file" ]]; then
      printf 'Mirror skill must not be a symlink: %s\n' "$mirror_file" >&2
      failed=1
      continue
    fi

    if [[ ! -f "$mirror_file" ]]; then
      if [[ "$mode" == "--write" ]]; then
        mkdir -p "$(dirname "$mirror_file")"
        cp "$source_file" "$mirror_file"
      else
        printf 'Missing mirror skill: %s\n' "$mirror_file" >&2
        failed=1
      fi
    elif ! cmp -s "$source_file" "$mirror_file"; then
      if [[ "$mode" == "--write" ]]; then
        cp "$source_file" "$mirror_file"
      else
        printf 'Outdated mirror skill: %s\n' "$mirror_file" >&2
        failed=1
      fi
    fi
  done
done

exit "$failed"
