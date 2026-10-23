#!/usr/bin/env python3
"""Tiny isolated pkg-config implementation for the fixed cross build.

It intentionally reads only the build workspace's PKG_CONFIG_PATH and emits
only the flags declared by the generated relocatable pc files.  It does not
consult the host package database or invoke a compiler/linker.
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path


class PkgConfigError(RuntimeError):
    pass


ALLOWED_FIELDS = {"Name", "Description", "Version", "Libs", "Cflags", "prefix", "includedir", "libdir", "epoxy_has_glx", "epoxy_has_egl", "epoxy_has_wgl", "pcfiledir"}
VERSION_REQUIREMENT = re.compile(r"^(>=|<=|=|>|<)?\s*(\d+(?:\.\d+)*)$")


def _files() -> dict[str, Path]:
    result: dict[str, Path] = {}
    for directory in os.environ.get("PKG_CONFIG_PATH", "").split(os.pathsep):
        if not directory:
            continue
        root = Path(directory)
        if not root.is_dir() or root.is_symlink():
            continue
        for path in root.glob("*.pc"):
            if path.is_file() and not path.is_symlink():
                result.setdefault(path.stem, path)
    return result


def _parse(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    values["pcfiledir"] = str(path.parent)
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if ":" in line and "=" not in line.split(":", 1)[0]:
            key, value = line.split(":", 1)
            key = key.strip()
            if key not in ALLOWED_FIELDS:
                raise PkgConfigError(f"unsupported fixed pc field {key!r} in {path}")
            values[key] = value.strip()
        elif "=" in line:
            key, value = line.split("=", 1)
            key = key.strip()
            if key not in ALLOWED_FIELDS:
                raise PkgConfigError(f"unsupported fixed pc variable {key!r} in {path}")
            values[key] = value.strip()
    return values


def _expand(values: dict[str, str], value: str) -> str:
    seen: set[str] = set()
    for _ in range(32):
        if value in seen:
            raise PkgConfigError("pc variable expansion cycle")
        seen.add(value)
        matches = list(re.finditer(r"\$\{([^}]+)\}", value))
        if not matches:
            return value
        unresolved = [match.group(1) for match in matches if match.group(1) not in values]
        if unresolved:
            raise PkgConfigError(f"unknown pc variable: {unresolved[0]}")
        expanded = re.sub(r"\$\{([^}]+)\}", lambda m: values[m.group(1)], value)
        if expanded == value:
            raise PkgConfigError("pc variable expansion cycle")
        value = expanded
    raise PkgConfigError("pc variable expansion exceeded 32 passes")


def _version(value: str) -> tuple[int, ...]:
    match = VERSION_REQUIREMENT.fullmatch(value.strip())
    if not match:
        raise PkgConfigError(f"unsupported version requirement: {value}")
    return tuple(int(part) for part in match.group(2).split("."))


def _satisfies(actual: str, operator: str | None, required: str) -> bool:
    if operator is None:
        return True
    left = _version(actual)
    right = _version(required)
    width = max(len(left), len(right))
    left += (0,) * (width - len(left))
    right += (0,) * (width - len(right))
    return {">=": left >= right, "<=": left <= right, "=": left == right, ">": left > right, "<": left < right}[operator]
    return value


def main(argv: list[str]) -> int:
    files = _files()
    if "--version" in argv:
        print("wfvtest-pkg-config 1")
        return 0
    names = [item for item in argv if not item.startswith("-") and item not in {">=", "<=", "=", ">", "<"} and not VERSION_REQUIREMENT.fullmatch(item)]
    if not names:
        return 0
    missing = [name for name in names if name not in files]
    if missing:
        return 1
    parsed = {name: _parse(files[name]) for name in names}
    requirements: list[tuple[str | None, str]] = []
    for index, arg in enumerate(argv):
        if arg.startswith(("--atleast-version=", "--exact-version=", "--max-version=")):
            prefix, required = arg.split("=", 1)
            requirements.append({"--atleast-version": ">=", "--exact-version": "=", "--max-version": "<="}[prefix], required)
        elif arg in {">=", "<=", "=", ">", "<"} and index + 1 < len(argv):
            requirements.append((arg, argv[index + 1]))
    if requirements:
        version = parsed[names[0]].get("Version", "")
        if any(not _satisfies(version, operator, required) for operator, required in requirements):
            return 1
    if any(arg == "--exists" for arg in argv):
        return 0
    if any(arg.startswith("--variable=") for arg in argv):
        variable = next(arg.split("=", 1)[1] for arg in argv if arg.startswith("--variable="))
        values = parsed[names[0]]
        print(_expand(values, values.get(variable, "")))
        return 0
    if "--modversion" in argv:
        print(parsed[names[0]].get("Version", ""))
        return 0
    output: list[str] = []
    for field in ("Cflags", "Libs"):
        if field == "Libs" and "--libs" not in argv:
            continue
        if field == "Cflags" and "--cflags" not in argv:
            continue
        for values in parsed.values():
            output.extend(_expand(values, values.get(field, "")).split())
    print(" ".join(output))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except PkgConfigError as exc:
        print(f"pkg-config: {exc}", file=sys.stderr)
        raise SystemExit(1)
