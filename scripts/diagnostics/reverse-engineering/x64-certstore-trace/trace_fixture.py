#!/usr/bin/env python3
"""Bounded native-host watchpoint fixture for the x64 certstore trace plan.

This is deliberately not a Wine/client launcher.  It builds a private child
process, arms an x86-64 debug-register watchpoint before the child initializes
its synthetic 12x40 table, and records metadata only (never table contents).
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import selectors
import signal
import struct
import subprocess
import sys
import time
from pathlib import Path


EVENT_CAP = 64
STACK_CAP = 16
TRACE_SECONDS = 30.0
TABLE_ROWS = 12
TABLE_WIDTH = 40
TABLE_SIZE = TABLE_ROWS * TABLE_WIDTH
PIPE_CAP = 64 * 1024
REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_PATH = REPO_ROOT / "forever" / "contract.json"

PTRACE_TRACEME = 0
PTRACE_PEEKDATA = 2
PTRACE_CONT = 7
PTRACE_GETREGS = 12
PTRACE_PEEKUSER = 3
PTRACE_SETOPTIONS = 0x4200
PTRACE_POKEUSER = 6
PTRACE_O_EXITKILL = 0x00100000
DEBUG_REGISTER_BASE = 848  # offsetof(struct user, u_debugreg[0]) on x86-64 Linux
DEBUG_STATUS_OFFSET = DEBUG_REGISTER_BASE + (6 * 8)
DEBUG_CONTROL_OFFSET = DEBUG_REGISTER_BASE + (7 * 8)


class UserRegs(ctypes.Structure):
    _fields_ = [
        ("r15", ctypes.c_ulonglong), ("r14", ctypes.c_ulonglong),
        ("r13", ctypes.c_ulonglong), ("r12", ctypes.c_ulonglong),
        ("rbp", ctypes.c_ulonglong), ("rbx", ctypes.c_ulonglong),
        ("r11", ctypes.c_ulonglong), ("r10", ctypes.c_ulonglong),
        ("r9", ctypes.c_ulonglong), ("r8", ctypes.c_ulonglong),
        ("rax", ctypes.c_ulonglong), ("rcx", ctypes.c_ulonglong),
        ("rdx", ctypes.c_ulonglong), ("rsi", ctypes.c_ulonglong),
        ("rdi", ctypes.c_ulonglong), ("orig_rax", ctypes.c_ulonglong),
        ("rip", ctypes.c_ulonglong), ("cs", ctypes.c_ulonglong),
        ("eflags", ctypes.c_ulonglong), ("rsp", ctypes.c_ulonglong),
        ("ss", ctypes.c_ulonglong), ("fs_base", ctypes.c_ulonglong),
        ("gs_base", ctypes.c_ulonglong), ("ds", ctypes.c_ulonglong),
        ("es", ctypes.c_ulonglong), ("fs", ctypes.c_ulonglong),
        ("gs", ctypes.c_ulonglong),
    ]


FIXTURE_SOURCE = r'''
#define _DEFAULT_SOURCE
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ptrace.h>
#include <unistd.h>

unsigned char synthetic_table[480] __attribute__((aligned(16)));

/* Values are synthetic public-contract material, not real credentials. */
static const unsigned char synthetic_key[480] = {
KEY_BYTES
};

__attribute__((noinline)) static void initialize_table(void) {
    /* Keep this first store a stable RIP-relative x86-64 instruction. */
    __asm__ volatile(
        "movq synthetic_key(%%rip), %%rax\n\t"
        "movq %%rax, synthetic_table(%%rip)\n\t"
        ::: "rax", "memory");
    for (size_t i = 8; i < sizeof(synthetic_table); ++i)
        synthetic_table[i] = synthetic_key[i];
}

__attribute__((noinline)) static unsigned long long consume_table(void) {
    unsigned long long first;
    __asm__ volatile("movq synthetic_table(%%rip), %%rax"
                     : "=a"(first) :: "memory");
    return first ^ synthetic_table[479];
}

static int validate_table(void) {
    return memcmp(synthetic_table, synthetic_key, sizeof(synthetic_table)) == 0;
}

static int validate_ids(void) {
    for (size_t row = 0; row < 12; ++row) {
        uint32_t id;
        memcpy(&id, synthetic_table + row * 40, sizeof(id));
        if (id != row + 1)
            return 0;
    }
    return 1;
}

static int validate_keys(void) {
    for (size_t row = 0; row < 12; ++row)
        if (memcmp(synthetic_table + row * 40 + 4,
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
        if (memcmp(synthetic_table + row * 40 + 37,
                   synthetic_key + row * 40 + 37, 3) != 0)
            return 0;
    return 1;
}

int main(void) {
    if (ptrace(PTRACE_TRACEME, 0, 0, 0) != 0)
        return 111;
    raise(SIGSTOP);
    initialize_table();
    usleep(50000);
    (void)consume_table();
    printf("table_valid=%s ids_valid=%s keys_valid=%s flags_valid=%s "
           "padding_valid=%s rows=%d width=%d\n",
           validate_table() ? "true" : "false",
           validate_ids() ? "true" : "false",
           validate_keys() ? "true" : "false",
           validate_flags() ? "true" : "false",
           validate_padding() ? "true" : "false", 12, 40);
    fflush(stdout);
    return 0;
}
'''


def _libc() -> ctypes.CDLL:
    libc = ctypes.CDLL(None, use_errno=True)
    libc.ptrace.restype = ctypes.c_long
    libc.ptrace.argtypes = [ctypes.c_ulong, ctypes.c_ulong,
                            ctypes.c_void_p, ctypes.c_void_p]
    return libc


LIBC = _libc()


def ptrace(request: int, pid: int, address: int = 0, data: int = 0) -> None:
    result = LIBC.ptrace(request, pid, ctypes.c_void_p(address),
                         ctypes.c_void_p(data))
    if result == -1:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))


def write_private(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            view = view[written:]
        os.fsync(fd)
    finally:
        os.close(fd)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_nm(binary: Path) -> dict[str, int]:
    output = subprocess.run(["nm", "-g", "--defined-only", str(binary)],
                            check=True, capture_output=True, text=True, timeout=10).stdout
    symbols: dict[str, int] = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) == 3:
            try:
                symbols[parts[2]] = int(parts[0], 16)
            except ValueError:
                pass
    return symbols


def proc_identity(pid: int) -> dict[str, object]:
    stat = Path(f"/proc/{pid}/stat").read_text()
    _, fields = stat.rsplit(") ", 1)
    stat_fields = fields.split()
    uid_line = next(line for line in Path(f"/proc/{pid}/status").read_text().splitlines()
                    if line.startswith("Uid:"))
    uid_values = [int(value) for value in uid_line.split()[1:]]
    if len(uid_values) != 4:
        raise RuntimeError("child UID does not have four fields")
    executable = os.path.realpath(os.readlink(f"/proc/{pid}/exe"))
    return {
        "pid": pid,
        "uid": {"real": uid_values[0], "effective": uid_values[1],
                "saved_set": uid_values[2], "filesystem": uid_values[3]},
        "start_time_ticks": stat_fields[19],  # field 22; fields starts at field 3
        "executable": executable,
        "ppid": int(stat_fields[1]),  # field 4
    }


def verify_identity(actual: dict[str, object], expected: dict[str, object]) -> None:
    if actual != expected:
        raise RuntimeError(f"child identity changed: expected {expected}, got {actual}")


def maps(pid: int) -> list[dict[str, object]]:
    result = []
    for line in Path(f"/proc/{pid}/maps").read_text().splitlines():
        fields = line.split(None, 5)
        start, end = (int(part, 16) for part in fields[0].split("-"))
        perms, offset = fields[1], int(fields[2], 16)
        path = fields[5] if len(fields) == 6 else ""
        result.append({"start": start, "end": end, "perms": perms,
                       "offset": offset, "path": path})
    return result


def containing_region(regions: list[dict[str, object]], address: int) -> dict[str, object] | None:
    for region in regions:
        if int(region["start"]) <= address < int(region["end"]):
            path = str(region["path"])
            perms = str(region["perms"])
            return {
                "start": f"0x{int(region['start']):x}",
                "end": f"0x{int(region['end']):x}",
                "size": int(region["end"]) - int(region["start"]),
                "permissions": perms,
                "private": perms.endswith("p"),
                "shared": perms.endswith("s"),
                "backing": os.path.basename(path) if path else "anonymous",
                "allocation": ("file-backed-private" if path and perms.endswith("p")
                                else "anonymous-private" if not path and perms.endswith("p")
                                else "shared-or-unknown"),
                "file_offset": f"0x{int(region['offset']):x}",
            }
    return None


def read_child(pid: int, address: int, size: int) -> bytes:
    with open(f"/proc/{pid}/mem", "rb", buffering=0) as stream:
        stream.seek(address)
        data = stream.read(size)
    if len(data) != size:
        raise RuntimeError(f"short child memory read at 0x{address:x}")
    return data


def get_regs(pid: int) -> UserRegs:
    regs = UserRegs()
    result = LIBC.ptrace(PTRACE_GETREGS, pid, None, ctypes.byref(regs))
    if result == -1:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))
    return regs


def peek_user(pid: int, offset: int) -> int:
    result = LIBC.ptrace(PTRACE_PEEKUSER, pid, ctypes.c_void_p(offset), None)
    error = ctypes.get_errno()
    if result == -1 and error:
        raise OSError(error, os.strerror(error))
    return ctypes.c_ulonglong(result).value


def module_info(regions: list[dict[str, object]], address: int,
                image_path: str) -> dict[str, object]:
    region = containing_region(regions, address)
    if region is None:
        return {"module": "unmapped", "module_offset": None, "rva": None}
    path = next((str(item["path"]) for item in regions
                 if int(item["start"]) <= address < int(item["end"])), "")
    module = "fixture" if path == image_path else (os.path.basename(path) or "anonymous")
    module_regions = [item for item in regions if str(item["path"]) == image_path]
    start = next(int(item["start"]) for item in regions
                 if int(item["start"]) <= address < int(item["end"]))
    file_offset = next(int(item["offset"]) for item in regions
                       if int(item["start"]) <= address < int(item["end"]))
    module_base = min((int(item["start"]) - int(item["offset"]) for item in module_regions),
                      default=start - file_offset)
    module_offset = address - start + file_offset
    return {"module": module, "module_offset": f"0x{module_offset:x}",
            "rva": f"0x{address - module_base:x}" if module == "fixture" else None}


def bounded_stack(pid: int, regs: UserRegs, regions: list[dict[str, object]],
                  image_path: str) -> list[dict[str, object]]:
    frames = []
    rbp = int(regs.rbp)
    for _ in range(STACK_CAP):
        stack_region = containing_region(regions, rbp)
        if not stack_region or rbp % 8 or rbp < int(regs.rsp):
            break
        try:
            next_rbp, return_pc = struct.unpack("<QQ", read_child(pid, rbp, 16))
        except (OSError, RuntimeError):
            break
        if not return_pc:
            break
        entry = {"address": f"0x{return_pc:x}", **module_info(regions, return_pc, image_path)}
        frames.append(entry)
        if next_rbp <= rbp or next_rbp - rbp > 1024 * 1024:
            break
        rbp = next_rbp
    return frames


def decode_access(pid: int, rip: int, table_address: int) -> dict[str, object]:
    # The fixture intentionally uses the two 7-byte RIP-relative forms below.
    instruction_address = rip - 7
    instruction = read_child(pid, instruction_address, 7)
    if instruction[:3] not in (b"\x48\x89\x05", b"\x48\x8b\x05"):
        raise RuntimeError(
            f"unexpected post-trap instruction at 0x{rip:x}: {instruction.hex()}"
        )
    displacement = struct.unpack("<i", instruction[3:])[0]
    target = instruction_address + 7 + displacement
    if target != table_address:
        raise RuntimeError(f"watchpoint target mismatch: 0x{target:x} != 0x{table_address:x}")
    access = "write" if instruction[1] == 0x89 else "read"
    return {"access": access, "instruction_address": f"0x{instruction_address:x}",
            "posttrap_ip": f"0x{rip:x}", "instruction_bytes": instruction.hex(),
            "target": f"0x{target:x}"}


def load_public_contract() -> dict[str, object]:
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    store = contract["key_store"]
    if store["entry_count"] != TABLE_ROWS or store["entry_size"] != TABLE_WIDTH:
        raise RuntimeError("public contract key-store dimensions are not 12x40")
    flags = store["expected_flags"]
    accepted_padding = store["padding_after_flag"]["accepts"]
    if len(flags) != TABLE_ROWS or any(flag not in (0, 1) for flag in flags):
        raise RuntimeError("public contract flags are invalid")
    paddings = [bytes.fromhex(value) for value in accepted_padding]
    if not paddings or any(len(value) != 3 for value in paddings):
        raise RuntimeError("public contract padding is invalid")
    return {"expected_flags": flags, "padding": paddings[0]}


def compile_fixture(output: Path) -> tuple[Path, dict[str, int], str, list[str]]:
    contract = load_public_contract()
    rows = []
    for row, flag in enumerate(contract["expected_flags"], 1):
        # Deliberately synthetic: only public row metadata is used; no contract
        # key material is copied into the fixture.
        key = hashlib.sha256(f"wowforever-host-synthetic-row:{row}:flag:{flag}".encode()).digest()
        rows.append(struct.pack("<I", row) + key + bytes([flag]) + contract["padding"])
    if len(rows) != TABLE_ROWS or any(len(row) != TABLE_WIDTH for row in rows):
        raise RuntimeError("public contract did not produce a 12x40 fixture")
    key_bytes = ", ".join(f"0x{byte:02x}" for byte in b"".join(rows))
    source = FIXTURE_SOURCE.replace("KEY_BYTES", key_bytes)
    source_path = output / "synthetic_certstore_fixture.c"
    binary_path = output / "synthetic_certstore_fixture"
    write_private(source_path, source.encode())
    compiler = os.environ.get("CC", "cc")
    compile_command = [compiler, "-std=c11", "-O0", "-fno-omit-frame-pointer",
                       "-fno-pie", "-no-pie", "-Wall", "-Wextra", "-Werror",
                       str(source_path), "-o", str(binary_path)]
    subprocess.run(compile_command, check=True, cwd=output, timeout=20)
    os.chmod(binary_path, 0o700)
    return binary_path, parse_nm(binary_path), sha256(source_path), compile_command


def status_details(status: int) -> dict[str, object]:
    if os.WIFEXITED(status):
        return {"raw_wait_status": status, "exited": True,
                "exit_code": os.WEXITSTATUS(status), "signal": None}
    if os.WIFSIGNALED(status):
        return {"raw_wait_status": status, "exited": False,
                "exit_code": None, "signal": os.WTERMSIG(status)}
    return {"raw_wait_status": status, "exited": False,
            "exit_code": None, "signal": None}


def region_properties(region: dict[str, object] | None) -> dict[str, object] | None:
    if region is None:
        return None
    return {key: region[key] for key in
            ("size", "permissions", "private", "shared", "backing", "allocation", "file_offset")}


def drain_pipes(child: subprocess.Popen[str], deadline: float) -> tuple[str, str]:
    streams = {stream.fileno(): stream for stream in (child.stdout, child.stderr)
               if stream is not None}
    buffers = {fd: [] for fd in streams}
    selector = selectors.DefaultSelector()
    try:
        for fd in streams:
            os.set_blocking(fd, False)
            selector.register(fd, selectors.EVENT_READ)
        sizes = {fd: 0 for fd in streams}
        while streams:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("bounded child pipe drain exceeded its deadline")
            for key, _ in selector.select(min(remaining, 0.1)):
                fd = key.fd
                data = os.read(fd, 4096)
                if data:
                    sizes[fd] += len(data)
                    if sizes[fd] > PIPE_CAP:
                        raise RuntimeError("fixture pipe output exceeded bounded cap")
                    buffers[fd].append(data)
                else:
                    selector.unregister(fd)
                    streams.pop(fd)
    finally:
        selector.close()
    values = []
    for stream in (child.stdout, child.stderr):
        if stream is None:
            values.append("")
        else:
            fd = stream.fileno()
            values.append(b"".join(buffers[fd]).decode(errors="replace"))
            stream.close()
    return values[0], values[1]


def reap_after_kill(pid: int, deadline: float) -> int | None:
    while time.monotonic() < deadline:
        waited_pid, status = os.waitpid(pid, os.WNOHANG | os.WUNTRACED)
        if waited_pid == pid:
            if os.WIFEXITED(status) or os.WIFSIGNALED(status):
                return status
        time.sleep(0.005)
    return None


def trace(binary: Path, symbols: dict[str, int]) -> dict[str, object]:
    table_address = symbols["synthetic_table"]
    child = subprocess.Popen([str(binary)], stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True)
    pid = child.pid
    expected_exe = os.path.realpath(binary)
    identity_before = proc_identity(pid)
    expected_identity = {**identity_before, "executable": expected_exe,
                         "ppid": os.getpid()}
    verify_identity(identity_before, expected_identity)
    identity_checks: list[dict[str, object]] = []
    terminal_status: int | None = None
    reaped = False
    deadline = time.monotonic() + TRACE_SECONDS
    events: list[dict[str, object]] = []
    unclassified_accesses: list[dict[str, object]] = []
    region_before: dict[str, object] | None = None
    pipes_drained = False

    def wait_for_stop() -> int:
        nonlocal reaped
        while time.monotonic() < deadline:
            try:
                before = proc_identity(pid)
            except FileNotFoundError:
                before = None
            waited_pid, status = os.waitpid(pid, os.WNOHANG | os.WUNTRACED)
            if waited_pid == 0:
                time.sleep(0.005)
                continue
            if waited_pid != pid:
                raise RuntimeError("wait returned an unexpected child")
            reaped = os.WIFEXITED(status) or os.WIFSIGNALED(status)
            if os.WIFSTOPPED(status):
                if before is None:
                    raise RuntimeError("child stopped after its identity disappeared")
                after = proc_identity(pid)
                verify_identity(before, expected_identity)
                verify_identity(after, expected_identity)
                identity_checks.append({"before": before, "after": after})
            return status
        raise TimeoutError("synthetic trace exceeded 30-second limit")

    try:
        status = wait_for_stop()
        if not os.WIFSTOPPED(status):
            raise RuntimeError("fixture did not stop at its pre-initialization gate")
        region_before = containing_region(maps(pid), table_address)
        if region_before is None:
            raise RuntimeError("synthetic table has no mapped region")
        ptrace(PTRACE_SETOPTIONS, pid, 0, PTRACE_O_EXITKILL)
        ptrace(PTRACE_POKEUSER, pid, DEBUG_REGISTER_BASE, table_address)
        # local DR0, read/write condition, 8-byte length
        ptrace(PTRACE_POKEUSER, pid, DEBUG_CONTROL_OFFSET, 0xB0001)
        ptrace(PTRACE_POKEUSER, pid, DEBUG_STATUS_OFFSET, 0)
        ptrace(PTRACE_CONT, pid)

        while True:
            status = wait_for_stop()
            if os.WIFEXITED(status) or os.WIFSIGNALED(status):
                terminal_status = status
                break
            if not os.WIFSTOPPED(status):
                continue
            stop_signal = os.WSTOPSIG(status)
            if stop_signal == signal.SIGTRAP:
                if len(events) + len(unclassified_accesses) >= EVENT_CAP:
                    raise RuntimeError("watchpoint event cap exceeded")
                regs = get_regs(pid)
                regions = maps(pid)
                try:
                    access = decode_access(pid, int(regs.rip), table_address)
                except (OSError, RuntimeError):
                    # Other code can touch the watched subset. Keep only
                    # bounded instruction metadata and make no causal claim.
                    unclassified_accesses.append({
                        "posttrap_ip": f"0x{int(regs.rip):x}",
                        "module": module_info(regions, int(regs.rip), expected_exe),
                        "classification": "unclassified_access",
                    })
                    ptrace(PTRACE_POKEUSER, pid, DEBUG_STATUS_OFFSET, 0)
                    ptrace(PTRACE_CONT, pid)
                    continue
                instruction_address = int(access["instruction_address"], 16)
                info = module_info(regions, instruction_address, expected_exe)
                event = {
                    **access,
                    "thread_id": pid,
                    "observed_monotonic_ns": time.monotonic_ns(),
                    "phase": ("initialization" if access["access"] == "write"
                              else "later-access"),
                    "module": info,
                    "callstack": bounded_stack(pid, regs, regions, expected_exe),
                    "region": containing_region(regions, table_address),
                }
                events.append(event)
                ptrace(PTRACE_POKEUSER, pid, DEBUG_STATUS_OFFSET, 0)
                ptrace(PTRACE_CONT, pid)
            else:
                raise RuntimeError(f"unexpected child stop signal {stop_signal}")

        stdout, stderr = drain_pipes(child, min(time.monotonic() + 1, deadline))
        pipes_drained = True
        if terminal_status is None or not os.WIFEXITED(terminal_status):
            raise RuntimeError(f"fixture did not exit normally: {status_details(terminal_status or 0)}")
        if os.WEXITSTATUS(terminal_status) != 0:
            raise RuntimeError(f"fixture failed ({status_details(terminal_status)}): {stderr.strip()}")
        status_line = stdout.strip()
        expected_status = ("table_valid=true ids_valid=true keys_valid=true "
                           "flags_valid=true padding_valid=true rows=12 width=40")
        if status_line != expected_status:
            raise RuntimeError(f"fixture did not validate the complete table: {status_line!r}")
        if [event["access"] for event in events] != ["write", "read"]:
            raise RuntimeError(f"expected write then read, got {events!r}")
        region_after = events[-1]["region"] if events else None
        return {
            "kind": "HOST SYNTHETIC (native Linux; not Wine/client validation)",
            "identity": {**expected_identity, "verified": True,
                           "prefix": "none/native-host-synthetic"},
            "identity_checks": identity_checks,
            "watchpoint": {"address": f"0x{table_address:x}", "width": 8,
                            "access": "read-write", "event_cap": EVENT_CAP,
                            "stack_cap": STACK_CAP, "trace_seconds": TRACE_SECONDS},
            "table": {"rows": TABLE_ROWS, "width": TABLE_WIDTH, "bytes": TABLE_SIZE,
                       "complete_validation": True, "ids_valid": True,
                       "keys_valid": True, "flags_valid": True, "padding_valid": True,
                       "values_reported": False},
            "events": events,
            "unclassified_accesses": unclassified_accesses,
            "event_count": len(events) + len(unclassified_accesses),
            "classified_event_count": len(events),
            "exit_status": status_details(terminal_status),
            "region_stability": {
                "before": region_properties(region_before),
                "after": region_properties(region_after),
                "properties_stable": region_properties(region_before) == region_properties(region_after),
                "address_reuse_compared": False,
            },
            "fixture_stdout_status": status_line,
            "limitations": [
                "Synthetic native child only; no actual WoW launch or Wine validation.",
                "The watched 8-byte subset proves the initialized full table only because the child separately validated all 480 bytes.",
                "The writer is the known RIP-relative store; this does not identify an actual-client constructor.",
            ],
        }
    finally:
        if not reaped:
            waited_pid, status = os.waitpid(pid, os.WNOHANG | os.WUNTRACED)
            if waited_pid == pid and (os.WIFEXITED(status) or os.WIFSIGNALED(status)):
                terminal_status = status
                reaped = True
            elif waited_pid in (0, pid):
                os.kill(pid, signal.SIGKILL)
                terminal_status = reap_after_kill(pid, min(time.monotonic() + 1, deadline + 1))
                reaped = terminal_status is not None
        if not pipes_drained and (child.stdout is not None or child.stderr is not None):
            drain_pipes(child, time.monotonic() + 1)


def make_output(path_arg: str | None) -> Path:
    def reject_symlink_components(path: Path) -> None:
        current = Path(path.anchor)
        for part in path.parts[1:]:
            current /= part
            if os.path.lexists(current) and current.is_symlink():
                raise RuntimeError(f"refusing symlink output path component: {current}")

    if path_arg:
        requested = Path(path_arg).absolute()
        reject_symlink_components(requested)
        output = requested.resolve()
        if output == REPO_ROOT or REPO_ROOT in output.parents:
            raise RuntimeError("artifact output must be outside the repository")
        output.mkdir(mode=0o700, parents=False, exist_ok=False)
    else:
        root = Path("/home/adrian/wowforever-artifacts/work")
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        unique = f"x64-certstore-trace-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-{os.getpid()}"
        output = root / unique
        reject_symlink_components(output)
        output.mkdir(mode=0o700, exist_ok=False)
    os.chmod(output, 0o700)
    return output


def fsync_directory(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def tool_metadata(compile_command: list[str]) -> dict[str, object]:
    try:
        version = subprocess.run([compile_command[0], "--version"], check=True,
                                 capture_output=True, text=True, timeout=5).stdout
        compiler_version = version.splitlines()[0] if version.splitlines() else "unknown"
    except (OSError, subprocess.SubprocessError):
        compiler_version = "unavailable"
    kernel = os.uname()
    return {
        "python": sys.version.split()[0],
        "compiler": compile_command[0],
        "compiler_version": compiler_version,
        "kernel": {"sysname": kernel.sysname, "release": kernel.release,
                   "machine": kernel.machine},
        "ptrace": "Linux x86-64 native host only",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", help="new private artifact directory")
    args = parser.parse_args(argv)
    if sys.platform != "linux" or os.uname().machine != "x86_64":
        parser.error("the fixture requires Linux x86-64 debug registers")
    output = make_output(args.output_dir)
    binary, symbols, source_hash, compile_command = compile_fixture(output)
    report = trace(binary, symbols)
    report["artifacts"] = {
        "source": binary.with_suffix(".c").name,
        "source_sha256": source_hash,
        "binary": binary.name,
        "binary_sha256": sha256(binary),
    }
    report["tools"] = tool_metadata(compile_command)
    report["commands"] = {"compile": compile_command,
                          "trace": [sys.executable, str(Path(__file__).resolve()),
                                    *(sys.argv[1:] if argv is None else argv)]}
    report["report_version"] = 1
    report_path = output / "trace-report.json"
    write_private(report_path, (json.dumps(report, indent=2, sort_keys=True) + "\n").encode())
    fsync_directory(output)
    print(json.dumps({"output_dir": str(output), "report": str(report_path),
                      "events": report["event_count"], "kind": report["kind"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
