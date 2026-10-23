#!/usr/bin/env python3
"""Run one bounded Wine 11 WineDbg watchpoint experiment.

The executable built here is a Windows-only synthetic fixture.  It contains no
client, account, authentication, or contract key material.  WineDbg is armed
before the fixture's initialization function and may report one known write and
one known read of the first eight bytes of a separately validated 12x40 table.
Only a complete, independently guarded trace is reported as observed; a
breakpoint or a zero-event run is partial.  This is not a WoW/authentication
test.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import selectors
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


IMAGE = (
    "wowserver/forever:auth-candidate@"
    "sha256:9d553802d94756f06968f12ebfe31a4a71060a8ee73267353a4879984260ba06"
)
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/work")
EVENT_CAP = 64
STACK_CAP = 16
RUN_SECONDS = 180
OUTPUT_CAP = 256 * 1024
INSTRUCTION_CAP = 16
TABLE_ROWS = 12
TABLE_WIDTH = 40
TABLE_SIZE = TABLE_ROWS * TABLE_WIDTH


FIXTURE_SOURCE = r'''
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <windows.h>

__declspec(dllexport) volatile unsigned char synthetic_table[480];
static const unsigned char synthetic_key[480] = {
KEY_BYTES
};

__declspec(dllexport) __declspec(noinline)
void initialize_table(void) {
    *(volatile uint64_t *)(void *)synthetic_table =
        *(const uint64_t *)(const void *)synthetic_key;
    for (size_t i = 8; i < sizeof(synthetic_table); ++i)
        synthetic_table[i] = synthetic_key[i];
}

__declspec(dllexport) __declspec(noinline)
uint64_t consume_table(void) {
    return *(volatile uint64_t *)(void *)synthetic_table ^ synthetic_table[479];
}

static int validate_table(void) {
    return memcmp((const void *)synthetic_table, synthetic_key,
                  sizeof(synthetic_table)) == 0;
}

static int validate_ids(void) {
    for (size_t row = 0; row < 12; ++row) {
        uint32_t id;
        memcpy(&id, (const void *)synthetic_table + row * 40, sizeof(id));
        if (id != row + 1)
            return 0;
    }
    return 1;
}

static int validate_keys(void) {
    for (size_t row = 0; row < 12; ++row)
        if (memcmp((const void *)synthetic_table + row * 40 + 4,
                   synthetic_key + row * 40 + 4, 32) != 0)
            return 0;
    return 1;
}

static int validate_flags(void) {
    for (size_t row = 0; row < 12; ++row)
        if (synthetic_table[row * 40 + 36] != synthetic_key[row * 40 + 36])
            return 0;
    return 1;
}

static int validate_padding(void) {
    for (size_t row = 0; row < 12; ++row)
        if (memcmp((const void *)synthetic_table + row * 40 + 37,
                   synthetic_key + row * 40 + 37, 3) != 0)
            return 0;
    return 1;
}

int main(void) {
    initialize_table();
    Sleep(50);
    (void)consume_table();
    MEMORY_BASIC_INFORMATION region;
    SIZE_T queried = VirtualQuery((const void *)synthetic_table, &region,
                                  sizeof(region));
    printf("table_valid=%s ids_valid=%s keys_valid=%s flags_valid=%s "
           "padding_valid=%s rows=%d width=%d\n",
           validate_table() ? "true" : "false",
           validate_ids() ? "true" : "false",
           validate_keys() ? "true" : "false",
           validate_flags() ? "true" : "false",
           validate_padding() ? "true" : "false", 12, 40);
    fflush(stdout);
    if (queried == sizeof(region))
         printf("region_query=ok base=%p size=%llu protect=%lu state=%lu type=%lu allocation_base=%p\n",
                (void *)region.BaseAddress, (unsigned long long)region.RegionSize,
                (unsigned long)region.Protect, (unsigned long)region.State,
                (unsigned long)region.Type, (void *)region.AllocationBase);
    printf("fixture_exit=0\n");
    fflush(stdout);
    return (validate_table() && validate_ids() && validate_keys() &&
            validate_flags() && validate_padding()) ? 0 : 17;
}
'''


class FixtureError(RuntimeError):
    """A bounded lab precondition or observation failed."""


def _write_exclusive(path: Path, data: bytes, mode: int = 0o600) -> None:
    if path.exists() or path.is_symlink():
        raise FixtureError(f"refusing to overwrite artifact: {path}")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            view = view[written:]
        os.fsync(fd)
    finally:
        os.close(fd)


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _tool_text(command: list[str], timeout: int = 10) -> str:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise FixtureError(f"tool failed: {' '.join(command)}: {error}") from error
    if result.returncode:
        raise FixtureError(f"tool exit {result.returncode}: {' '.join(command)}")
    return result.stdout


def _parse_nm_symbols(text: str) -> dict[str, int]:
    symbols: dict[str, int] = {}
    for line in text.splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]+)\s+\S\s+(\S+)", line.strip())
        if match:
            symbols[match.group(2)] = int(match.group(1), 16)
    return symbols


def _parse_pe_exports(text: str) -> dict[str, int]:
    addresses: dict[int, int] = {}
    names: dict[int, str] = {}
    in_addresses = False
    in_names = False
    for line in text.splitlines():
        if "Export Address Table --" in line:
            in_addresses = True
            in_names = False
            continue
        if "[Ordinal/Name Pointer] Table" in line:
            in_addresses = False
            in_names = True
            continue
        address = re.search(r"\[\s*\d+\s*\].*\+base\[\s*(\d+)\s*\]\s+([0-9a-fA-F]+)\s+Export RVA", line)
        if in_addresses and address:
            addresses[int(address.group(1))] = int(address.group(2), 16)
            continue
        name = re.search(r"\[\s*\d+\s*\].*\+base\[\s*(\d+)\s*\]\s+\S+\s+(\S+)", line)
        if in_names and name:
            names[int(name.group(1))] = name.group(2)
    return {name: addresses[ordinal] for ordinal, name in names.items() if ordinal in addresses}


def _pe_identity(binary: Path) -> dict[str, Any]:
    nm = os.environ.get("NM", "x86_64-w64-mingw32-nm")
    objdump = os.environ.get("OBJDUMP", "x86_64-w64-mingw32-objdump")
    nm_command = [nm, "-g", "--defined-only", str(binary)]
    objdump_command = [objdump, "-p", str(binary)]
    nm_symbols = _parse_nm_symbols(_tool_text(nm_command))
    pe_text = _tool_text(objdump_command)
    debug_command = [objdump, "-g", str(binary)]
    debug_text = _tool_text(debug_command)
    exports = _parse_pe_exports(pe_text)
    image_match = re.search(r"^ImageBase\s+([0-9a-fA-F]+)", pe_text, re.MULTILINE)
    if not image_match:
        raise FixtureError("fresh PE has no parseable ImageBase")
    image_base = int(image_match.group(1), 16)
    required = ("initialize_table", "synthetic_table", "consume_table")
    values: dict[str, dict[str, str]] = {}
    for name in required:
        if name not in nm_symbols or name not in exports:
            raise FixtureError(f"fresh PE did not export/define {name}")
        rva = nm_symbols[name] - image_base
        if rva != exports[name]:
            raise FixtureError(f"PE export RVA mismatch for {name}")
        values[name] = {
            "absolute": f"0x{nm_symbols[name]:x}",
            "rva": f"0x{rva:x}",
        }
    disassembly_command = [
        objdump,
        "-d",
        f"--start-address=0x{nm_symbols['initialize_table']:x}",
        f"--stop-address=0x{nm_symbols['consume_table'] + 0x20:x}",
        str(binary),
    ]
    disassembly_text = _tool_text(disassembly_command)
    instructions = []
    for line in disassembly_text.splitlines():
        match = re.match(r"\s*([0-9a-fA-F]+):\s+(?:[0-9a-fA-F]{2}\s+)+(.+)$", line)
        if match:
            instructions.append(
                {"absolute": f"0x{int(match.group(1), 16):x}", "text": match.group(2).strip()}
            )
    return {
        "image_base": f"0x{image_base:x}",
        "symbols": values,
        "pe_exports": {name: f"0x{rva:x}" for name, rva in sorted(exports.items())},
        "nm_command": nm_command,
        "objdump_command": objdump_command,
        "debug_symbols": {
            "command": debug_command,
            "source_present": "synthetic_certstore_fixture.c:" in debug_text,
            "debug_sections_present": ".debug_info" in _tool_text(
                [objdump, "-h", str(binary)]
            ),
        },
        "disassembly_command": disassembly_command,
        "disassembly": instructions,
    }


def _new_output(requested: str | None) -> Path:
    if requested:
        output = Path(requested).absolute()
        try:
            output.relative_to(ARTIFACT_ROOT)
        except ValueError as error:
            raise FixtureError(f"output must be below {ARTIFACT_ROOT}") from error
    else:
        output = ARTIFACT_ROOT / (
            f"x64-wine-debugger-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-"
            f"{os.getpid()}"
        )
    if output.exists() or output.is_symlink():
        raise FixtureError(f"refusing to overwrite output directory: {output}")
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    os.chmod(output, 0o700)
    return output


def _synthetic_rows() -> bytes:
    rows = []
    for row in range(1, TABLE_ROWS + 1):
        flag = 1 if row in {8, 9, 10} else 0
        key = hashlib.sha256(
            f"wine-debugger-synthetic-row:{row}:flag:{flag}".encode()
        ).digest()
        rows.append(row.to_bytes(4, "little") + key + bytes([flag, 0, 0, 0]))
    result = b"".join(rows)
    if len(result) != TABLE_SIZE:
        raise FixtureError("synthetic table did not produce 12x40 bytes")
    return result


def _compile(output: Path) -> dict[str, Any]:
    compiler = os.environ.get("CC", "x86_64-w64-mingw32-gcc")
    rows = ", ".join(f"0x{value:02x}" for value in _synthetic_rows())
    source = FIXTURE_SOURCE.replace("KEY_BYTES", rows)
    source_path = output / "synthetic_certstore_fixture.c"
    binary_path = output / "synthetic_certstore_fixture.exe"
    _write_exclusive(source_path, source.encode())
    command = [
        compiler,
        "-std=c11",
        "-O0",
        "-g",
        "-fno-omit-frame-pointer",
        "-Wall",
        "-Wextra",
        "-Werror",
        # This disables ASLR only for this disposable fixture, so WineDbg can
        # arm raw addresses before the PE module is loaded.  No host/global
        # ASLR setting is changed.
        "-Wl,--disable-dynamicbase",
        str(source_path),
        "-o",
        str(binary_path),
    ]
    started = time.monotonic_ns()
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise FixtureError(f"cross-compiler failed: {error}") from error
    if result.returncode:
        raise FixtureError(f"cross-compiler exit {result.returncode}: {result.stderr[-4000:]}")
    os.chmod(binary_path, 0o700)
    try:
        version = subprocess.run(
            [compiler, "--version"], capture_output=True, text=True, timeout=5, check=True
        ).stdout.splitlines()[0]
    except (OSError, IndexError, subprocess.SubprocessError):
        version = "unavailable"
    pe_identity = _pe_identity(binary_path)
    return {
        "command": command,
        "exit_status": result.returncode,
        "duration_ms": (time.monotonic_ns() - started) // 1_000_000,
        "compiler": compiler,
        "compiler_version": version,
        "source": source_path.name,
        "source_sha256": _sha256(source_path),
        "binary": binary_path.name,
        "binary_sha256": _sha256(binary_path),
        "pe_identity": pe_identity,
    }


def _commands(pe_identity: dict[str, Any]) -> list[str]:
    symbols = pe_identity["symbols"]
    required = ("initialize_table", "synthetic_table", "consume_table")
    if any(name not in symbols for name in required):
        raise FixtureError("debugger commands require verified fixture symbols")
    return [
        # Raw addresses are generated only from this run's verified PE export
        # table/nm agreement; they are not guessed RVAs or constants.
        f"break *{symbols['initialize_table']['absolute']}",
        "cont",
        "info share",
        "info map",
        f"watch *(unsigned long long *){symbols['synthetic_table']['absolute']}",
        f"rwatch *(unsigned long long *){symbols['synthetic_table']['absolute']}",
        "info break",
        "cont",
        "info thread",
        "disas",
        "bt",
        "info map",
        "disable 2",
        "cont",
        "info thread",
        "disas",
        "bt",
        "info map",
        "disable 3",
        "cont",
        "quit",
    ]


def _write_debugger_commands(output: Path, pe_identity: dict[str, Any]) -> Path:
    path = output / "winedbg.commands"
    _write_exclusive(path, ("\n".join(_commands(pe_identity)) + "\n").encode())
    return path


def _docker_command(output: Path, container: str, command_file: Path, binary: Path) -> list[str]:
    return [
        "docker",
        "run",
        "--rm",
        "--name",
        container,
        "--user",
        "1000:1000",
        "--network",
        "none",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges",
        "--pids-limit",
        "128",
        "--memory",
        "4g",
        "--cpus",
        "2",
        "--read-only",
        "--tmpfs",
        "/tmp:rw,size=256m,mode=1777",
        "--mount",
        f"type=bind,src={output},dst=/lab",
        "--entrypoint",
        "/bin/sh",
        IMAGE,
        "-c",
        "set -eu; mkdir -p /lab/home /lab/wineprefix; "
        "export HOME=/lab/home WINEPREFIX=/lab/wineprefix; "
        "printf 'wine='; command -v wine; printf 'winedbg='; command -v winedbg; "
        "printf 'wine-version='; wine --version; "
        "winedbg --file /lab/winedbg.commands /lab/synthetic_certstore_fixture.exe; "
        "winedbg_status=$?; printf 'winedbg_exit=%s\\n' \"$winedbg_status\"; "
        "wine /lab/synthetic_certstore_fixture.exe; fixture_status=$?; "
        "printf 'fixture_process_exit=%s\\n' \"$fixture_status\"; exit \"$fixture_status\"",
    ]


def _remove_owned_container(container: str) -> None:
    try:
        subprocess.run(
            ["docker", "rm", "-f", container],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        # The primary report retains the cleanup failure; this best-effort path
        # must not target anything except this run's pid-derived container.
        pass


def _run_winedbg(output: Path, command_file: Path, binary: Path) -> dict[str, Any]:
    container = f"x64-wine-debugger-{os.getpid()}"
    command = _docker_command(output, container, command_file, binary)
    started = time.monotonic_ns()
    process: subprocess.Popen[bytes] | None = None
    timed_out = False
    output_truncated = False
    captured = bytearray()
    deadline = time.monotonic() + RUN_SECONDS
    cleanup_deadline: float | None = None
    poller = selectors.DefaultSelector()
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        assert process.stdout is not None
        poller.register(process.stdout, selectors.EVENT_READ)
        while process.poll() is None or poller.get_map():
            if process.poll() is None and time.monotonic() >= deadline:
                timed_out = True
                cleanup_deadline = time.monotonic() + 30
                process.kill()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    _remove_owned_container(container)
                    raise FixtureError("WineDbg process did not terminate during cleanup")
            if timed_out and cleanup_deadline is not None and time.monotonic() >= cleanup_deadline:
                for key in list(poller.get_map().values()):
                    poller.unregister(key.fileobj)
                    key.fileobj.close()
                break
            remaining = max(0.0, deadline - time.monotonic()) if not timed_out else 1.0
            for key, _ in poller.select(timeout=min(0.25, remaining)):
                chunk = key.fileobj.read1(16 * 1024)
                if not chunk:
                    poller.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                if len(captured) < OUTPUT_CAP:
                    room = OUTPUT_CAP - len(captured)
                    captured.extend(chunk[:room])
                    if len(chunk) > room:
                        output_truncated = True
                else:
                    output_truncated = True
            if timed_out and process.poll() is not None and not poller.get_map():
                break
        if process.poll() is None:
            process.wait(timeout=30)
    except BaseException:
        if process is not None and process.poll() is None:
            process.kill()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                pass
        _remove_owned_container(container)
        raise
    finally:
        poller.close()
        _remove_owned_container(container)
    assert process is not None
    log_path = output / "winedbg.log"
    _write_exclusive(log_path, bytes(captured))
    return {
        "container": container,
        "command": command,
        "exit_status": process.returncode,
        "timed_out": timed_out,
        "output_truncated": output_truncated,
        "output_cap": OUTPUT_CAP,
        "duration_ms": (time.monotonic_ns() - started) // 1_000_000,
        "log": log_path.name,
        "log_sha256": _sha256(log_path),
    }


def _parse_region(log: str) -> dict[str, Any] | None:
    match = re.search(
        r"region_query=ok base=(0x)?([0-9a-fA-F]+) size=(\d+) protect=(\d+) "
        r"state=(\d+) type=(\d+) allocation_base=(0x)?([0-9a-fA-F]+)",
        log,
    )
    if not match:
        return None
    _, base_digits, size, protect, state, region_type, _, allocation_digits = match.groups()
    base = f"0x{int(base_digits, 16):x}"
    allocation_base = f"0x{int(allocation_digits, 16):x}"
    protect_value = int(protect)
    type_value = int(region_type)
    return {
        "base": base,
        "size": int(size),
        "protection": protect_value,
        "protection_name": {1: "PAGE_NOACCESS", 2: "PAGE_READONLY", 4: "PAGE_READWRITE"}.get(
            protect_value,
            {
                1: "PAGE_NOACCESS",
                2: "PAGE_READONLY",
                4: "PAGE_READWRITE",
                8: "PAGE_WRITECOPY",
                16: "PAGE_EXECUTE",
                32: "PAGE_EXECUTE_READ",
                64: "PAGE_EXECUTE_READWRITE",
                128: "PAGE_EXECUTE_WRITECOPY",
            }.get(protect_value, f"unknown({protect_value})")
        ),
        "state": int(state),
        "type": type_value,
        "backing": (
            "private-anonymous"
            if type_value == 131072
            else "PE image file-backed"
            if type_value == 16777216
            else "unknown"
        ),
        "private": type_value == 131072,
        "file_backed": type_value == 16777216,
        "allocation_base": allocation_base,
        "source": "fixture VirtualQuery(synthetic_table)",
    }


def _event_blocks(log: str) -> list[tuple[str, str]]:
    matches = list(
        re.finditer(
            r"Stopped on watchpoint\s+(\d+)\s+at\s+(0x[0-9a-fA-F]+).*?(?="
            r"Stopped on watchpoint|table_valid=|Process of pid=|\Z)",
            log,
            re.DOTALL,
        )
    )
    return [(match.group(0), match.group(2)) for match in matches[:EVENT_CAP]]


def _stack(block: str) -> list[str]:
    frames = []
    in_backtrace = False
    for line in block.splitlines():
        if line.startswith("Backtrace:"):
            in_backtrace = True
            continue
        if in_backtrace and re.match(r"\s*(?:=>\s*)?\d+\s+0x", line):
            frames.append(line.strip())
            if len(frames) >= STACK_CAP:
                break
    return frames


def _thread_id(block: str) -> str | None:
    match = re.search(r"^\s+([0-9a-fA-F]+)\s+\d+\s+<==\s*$", block, re.MULTILINE)
    return f"0x{match.group(1).lower()}" if match else None


def _static_instruction(
    stop_ip: str, pe_identity: dict[str, Any]
) -> tuple[str, str, str] | None:
    if "image_base" not in pe_identity or "disassembly" not in pe_identity:
        return None
    stop = int(stop_ip, 16)
    image_base = int(pe_identity["image_base"], 16)
    initialize = int(pe_identity["symbols"]["initialize_table"]["absolute"], 16)
    consume = int(pe_identity["symbols"]["consume_table"]["absolute"], 16)
    if initialize <= stop < consume:
        operation = "writer"
        symbol = "initialize_table"
    elif consume <= stop < consume + 0x20:
        operation = "reader"
        symbol = "consume_table"
    else:
        return None
    candidates = [
        item
        for item in pe_identity["disassembly"]
        if initialize <= int(item["absolute"], 16) < stop
    ]
    if symbol == "consume_table":
        candidates = [
            item
            for item in pe_identity["disassembly"]
            if consume <= int(item["absolute"], 16) < stop
        ]
    if not candidates:
        return None
    item = candidates[-1]
    return symbol, operation, json.dumps(
        {
            "absolute": item["absolute"],
            "rva": f"0x{int(item['absolute'], 16) - image_base:x}",
            "text": item["text"],
        },
        sort_keys=True,
    )


def _instruction(block: str, table_absolute: str) -> tuple[str, str] | None:
    lines = []
    for line in block.splitlines():
        match = re.match(r"\s*(0x[0-9a-fA-F]+).*?:\s+(.*)$", line)
        if match and ("mov" in match.group(2).lower() or "lea" in match.group(2).lower()):
            lines.append((match.group(1), match.group(2).strip()))
    target = table_absolute.lower()
    for address, text in lines:
        if target in text.lower() or "synthetic_table" in text:
            return address, text
    return lines[-1] if lines else None


def _classify_instruction(text: str) -> str | None:
    lowered = text.lower().strip()
    if not lowered.startswith(("mov ", "movq ", "movl ", "movabs ")):
        return None
    operands = lowered.split(None, 1)[1] if " " in lowered else ""
    if "," not in operands:
        return None
    source, destination = operands.rsplit(",", 1)
    if "synthetic_table" in destination:
        return "writer"
    if "synthetic_table" in source:
        return "reader"
    return None


def _extract_observations(output: Path, run: dict[str, Any], pe_identity: dict[str, Any]) -> dict[str, Any]:
    log = (output / str(run["log"])).read_text(errors="replace")
    watch_stops = re.findall(r"Stopped on watchpoint\s+(\d+)\s+at\s+(0x[0-9a-fA-F]+)", log)
    table_status = re.search(
        r"table_valid=(\w+) ids_valid=(\w+) keys_valid=(\w+) flags_valid=(\w+) "
        r"padding_valid=(\w+) rows=(\d+) width=(\d+)",
        log,
    )
    table_absolute = pe_identity["symbols"]["synthetic_table"]["absolute"]
    image_base = pe_identity.get("image_base", "0x140000000")
    watchpoint_widths = {
        int(number): int(width)
        for number, width in re.findall(r"^(\d+):.*?on (\d+) bytes \([WR]\)", log, re.MULTILINE)
    }
    blocks = _event_blocks(log)
    events = []
    for index, (block, stop_ip) in enumerate(blocks):
        instruction = _instruction(block, table_absolute)
        static = _static_instruction(stop_ip, pe_identity)
        if static:
            symbol, operation, static_json = static
            static_fact = json.loads(static_json)
        else:
            symbol = None
            operation = _classify_instruction(instruction[1]) if instruction else None
            static_fact = None
        events.append(
            {
                "event": index + 1,
                "watchpoint": int(re.search(r"watchpoint\s+(\d+)", block).group(1)),
                "post_trap_ip": stop_ip,
                "post_trap_rva": f"0x{int(stop_ip, 16) - int(image_base, 16):x}",
                "module": "synthetic_certstore_fixture",
                "module_base": image_base,
                "instruction_ip": static_fact["absolute"] if static_fact else (instruction[0] if instruction else None),
                "instruction_rva": static_fact["rva"] if static_fact else None,
                "instruction": static_fact["text"] if static_fact else (instruction[1] if instruction else None),
                "instruction_source": "verified PE disassembly" if static_fact else None,
                "function": symbol,
                "operation": operation,
                "width": 8,
                "watch_width": watchpoint_widths.get(
                    int(re.search(r"watchpoint\s+(\d+)", block).group(1))
                ),
                "position": 0,
                "stack": _stack(block),
                "thread": _thread_id(block),
                "region": _parse_region(block),
            }
        )
    trap_disassembly = [event["instruction"] for event in events if event["instruction"]]
    region = _parse_region(log)
    for event in events:
        if event["region"] is None:
            event["region"] = region
    writer = next((event for event in events if event["operation"] == "writer"), None)
    reader = next((event for event in events if event["operation"] == "reader"), None)
    fixture_exit = re.search(r"fixture_exit=(\d+)", log)
    fixture_process_exit = re.search(r"fixture_process_exit=(\d+)", log)
    process_exit = fixture_exit and int(fixture_exit.group(1)) == 0
    watchpoint_error = re.search(
        r"(No type or type mismatch|Unable to add watchpoint[^\n]*)", log
    )
    blocker = None
    if not writer or not reader:
        blocker = (
            "WineDbg did not produce both classified actual writer and reader events; "
            f"watchpoint diagnostic: {watchpoint_error.group(1) if watchpoint_error else 'none'}"
        )
    elif not writer["stack"] or not reader["stack"]:
        blocker = "WineDbg did not report a bounded call stack for both watchpoint events"
    elif not region:
        blocker = "synthetic fixture did not report VirtualQuery region metadata"
    elif not process_exit:
        blocker = "synthetic fixture did not report fixture_exit=0"
    elif run.get("output_truncated"):
        blocker = "bounded debugger output reached OUTPUT_CAP"
    return {
        "watchpoint_stops": [
            {"watchpoint": int(number), "stop_ip": address}
            for number, address in watch_stops[:EVENT_CAP]
        ],
        "watchpoint_stop_count": len(watch_stops),
        "watchpoint_supported": bool(writer and reader),
        "actual_writer": writer,
        "actual_reader": reader,
        "callstacks": [event["stack"] for event in events],
        "region_metadata": [region] if region else [],
        "blocker": blocker,
        "debugger_reported_disassembly_lines": trap_disassembly[:EVENT_CAP],
        "stack_cap": STACK_CAP,
        "event_cap": EVENT_CAP,
        "fixture_exit": int(fixture_exit.group(1)) if fixture_exit else None,
        "fixture_process_exit": int(fixture_process_exit.group(1)) if fixture_process_exit else None,
        "actual_event_count": len(events),
        "source_origin": {
            "fact": "synthetic_key is copied into synthetic_table by initialize_table",
            "not_authentication_material": True,
        },
        "table_validation": (
            {
                "table_valid": table_status.group(1) == "true",
                "ids_valid": table_status.group(2) == "true",
                "keys_valid": table_status.group(3) == "true",
                "flags_valid": table_status.group(4) == "true",
                "padding_valid": table_status.group(5) == "true",
                "rows": int(table_status.group(6)),
                "width": int(table_status.group(7)),
            }
            if table_status
            else None
        ),
        "values_reported": False,
        "raw_log_is_observation_only": True,
    }


def _report_failure(output: Path, error: Exception, started: int) -> Path:
    report_path = output / "trace-report.json"
    report = {
        "report_version": 1,
        "kind": "WINE SYNTHETIC DEBUGGER MECHANICS",
        "status": "blocked",
        "error_type": type(error).__name__,
        "error": str(error),
        "duration_ms": (time.monotonic_ns() - started) // 1_000_000,
        "real_wow_launched": False,
        "production_service_launched": False,
        "authentication_claim": False,
    }
    _write_exclusive(report_path, (json.dumps(report, indent=2, sort_keys=True) + "\n").encode())
    return report_path


def run(output: Path) -> dict[str, Any]:
    started = time.monotonic_ns()
    build = _compile(output)
    command_file = _write_debugger_commands(output, build["pe_identity"])
    run_report = _run_winedbg(output, command_file, output / str(build["binary"]))
    observations = _extract_observations(output, run_report, build["pe_identity"])
    status = (
        "observed"
        if observations["actual_writer"]
        and observations["actual_reader"]
        and observations["watchpoint_stop_count"] >= 2
        and observations["table_validation"]
        and all(observations["table_validation"].values())
        and observations["fixture_exit"] == 0
        and observations["actual_writer"]["stack"]
        and observations["actual_reader"]["stack"]
        and observations["region_metadata"]
        and run_report["exit_status"] == 0
        and not run_report["timed_out"]
        and not run_report["output_truncated"]
        else "partial"
    )
    report = {
        "report_version": 1,
        "kind": "WINE SYNTHETIC DEBUGGER MECHANICS",
        "status": status,
        "wine_version_expected": "wine-11.0",
        "image": IMAGE,
        "fixture": {
            "rows": TABLE_ROWS,
            "width": TABLE_WIDTH,
            "bytes": TABLE_SIZE,
            "synthetic_data_only": True,
            "complete_validation_required": True,
        },
        "watchpoint": {
            "requested_width": 8,
            "effective_width_reported_by_winedbg": (
                observations["actual_writer"].get("watch_width")
                if observations["actual_writer"]
                else None
            ),
            "instruction_width": 8,
        },
        "build": build,
        "debugger_commands": _commands(build["pe_identity"]),
        "run": run_report,
        "observations": observations,
        "real_wow_launched": False,
        "production_service_launched": False,
        "authentication_claim": False,
        "limitations": [
            "Synthetic Windows child only; no WoW executable or client data was mounted.",
            "The result tests WineDbg mechanics, not authentication or certstore construction.",
            "Watchpoint output is retained as bounded debugger text; no memory/key dump is made.",
            "Wine 11 reports the typed uint64 watchpoint as four hardware-watched bytes; the actual PE instructions are independently width-8 load/store operations.",
            "A partial result is not a GO: no writer, reader, call stack, or region is claimed without watchpoint stops.",
        ],
        "duration_ms": (time.monotonic_ns() - started) // 1_000_000,
        "host": {"python": sys.version.split()[0], "platform": platform.platform()},
    }
    report_path = output / "trace-report.json"
    _write_exclusive(report_path, (json.dumps(report, indent=2, sort_keys=True) + "\n").encode())
    _fsync_dir(output)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", help="new private directory below the artifact root")
    args = parser.parse_args(argv)
    output: Path | None = None
    started = time.monotonic_ns()
    try:
        output = _new_output(args.output_dir)
        report = run(output)
        print(json.dumps({"status": report["status"], "report": str(output / "trace-report.json")}))
        return 0 if report["status"] == "observed" else 1
    except Exception as error:
        if output is None:
            raise
        report_path = _report_failure(output, error, started)
        print(json.dumps({"status": "blocked", "report": str(report_path), "error": str(error)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
