"""Host-only lifecycle tests for the bounded Wine ownership supervisor.

The executables made here are deliberately temporary fake launchers.  No Wine,
Android linker, server, device, or APK is involved in this test module.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import textwrap
import time

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-process"
COMMAND_KEYS = {"schema", "runId", "nonce", "seq", "command", "phase"}
EVENT_KEYS = {
    "schema", "runId", "nonce", "seq", "event", "phase", "pid", "startTicks",
    "ppid", "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode",
    "childCount", "echild", "setResult", "getResult", "getValue", "prefixDev",
    "prefixIno", "socketDev", "socketIno", "lockDev", "lockIno",
}


FAKE_C = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef CLIENT_EXIT
#define CLIENT_EXIT 0
#endif
static volatile sig_atomic_t stopping;
static void stop(int sig) { (void)sig; stopping = 1; }
static void fixture_write(int fd, const void *data, size_t length) {
    ssize_t result = write(fd, data, length);
    if (result < 0) _exit(70);
}
static int environment_ok(void) {
    const char *dll = getenv("WINEDLLPATH");
    const char *prefix = getenv("WINEPREFIX");
    const char *home = getenv("HOME");
    const char *tmp = getenv("TMPDIR");
    struct stat home_stat, tmp_stat;
    char expected_home[4096], expected_tmp[4096];
    if (prefix == NULL || home == NULL || tmp == NULL ||
        snprintf(expected_home, sizeof(expected_home), "%s/home", prefix) <= 0 ||
        snprintf(expected_tmp, sizeof(expected_tmp), "%s/tmp", prefix) <= 0 ||
        strcmp(home, expected_home) != 0 || strcmp(tmp, expected_tmp) != 0 ||
        stat(home, &home_stat) != 0 || stat(tmp, &tmp_stat) != 0 || !S_ISDIR(home_stat.st_mode) ||
        !S_ISDIR(tmp_stat.st_mode) || (home_stat.st_mode & 0777) != 0700 || (tmp_stat.st_mode & 0777) != 0700) return 0;
    return getenv("WINEPREFIX") != NULL && getenv("WINELOADER") != NULL && getenv("WINESERVER") != NULL &&
           getenv("PATH") != NULL && getenv("LD_LIBRARY_PATH") != NULL && getenv("HOME") != NULL &&
           getenv("TMPDIR") != NULL && dll != NULL && strstr(dll, "/lib/wine/aarch64-windows") != NULL &&
           getenv("WINESERVERSOCKET") == NULL && getenv("LD_PRELOAD") == NULL &&
           getenv("SUPERVISOR_INHERITED") == NULL;
}

static int server(void) {
    const char *prefix = getenv("WINEPREFIX");
    struct stat st;
    char base[4096], dir[4096], lock_name[4096], socket_name[4096];
    if (prefix == NULL || stat(prefix, &st) != 0 || !environment_ok()) return 41;
    fixture_write(STDOUT_FILENO, "server-stdout\n", 14);
    fixture_write(STDERR_FILENO, "server-stderr\n", 14);
    if (chdir(prefix) != 0) return 40;
    if (access("server-block", F_OK) == 0) {
        signal(SIGTERM, stop); signal(SIGINT, stop);
        while (!stopping && access("server-release", F_OK) != 0) usleep(10000);
    }
    if (access("flood-logs", F_OK) == 0) {
        char flood[4096]; memset(flood, 'F', sizeof(flood));
        for (int index = 0; index < 20; index++) {
            fixture_write(STDOUT_FILENO, flood, sizeof(flood));
            fixture_write(STDERR_FILENO, flood, sizeof(flood));
        }
    }
    snprintf(base, sizeof(base), ".wineserver");
    if (mkdir(base, 0700) != 0 && errno != EEXIST) return 42;
    snprintf(dir, sizeof(dir), "%s/server-%llx-%llx", base,
             (unsigned long long)st.st_dev, (unsigned long long)st.st_ino);
    if (mkdir(dir, 0700) != 0) return 43;
    snprintf(lock_name, sizeof(lock_name), "%s/lock", dir);
    int lock = open(lock_name, O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    struct flock held = {0};
    held.l_type = F_WRLCK; held.l_whence = SEEK_SET; held.l_start = 0; held.l_len = 1;
    if (lock < 0 || fcntl(lock, F_SETLK, &held) != 0) return 44;
    snprintf(socket_name, sizeof(socket_name), "%s/socket", dir);
    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) return 45;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address)); address.sun_family = AF_UNIX;
    if (strlen(socket_name) >= sizeof(address.sun_path)) return 46;
    strcpy(address.sun_path, socket_name);
    if (bind(sock, (struct sockaddr *)&address, sizeof(address)) != 0 || chmod(socket_name, 0600) != 0 ||
        listen(sock, 1) != 0) return 47;
    struct stat owned_socket;
    if (stat(socket_name, &owned_socket) != 0) return 48;
    signal(SIGTERM, stop); signal(SIGINT, stop);
    while (!stopping) {
        if (access("server-exit", F_OK) == 0) break;
        usleep(10000);
    }
    struct stat current_socket;
    if (stat(socket_name, &current_socket) == 0 && current_socket.st_dev == owned_socket.st_dev &&
        current_socket.st_ino == owned_socket.st_ino) unlink(socket_name);
    close(sock); close(lock); unlink(lock_name); rmdir(dir); rmdir(base);
    return 0;
}

static int client(void) {
    if (!environment_ok()) return 50;
    signal(SIGTERM, stop); signal(SIGINT, stop);
    const char *prefix = getenv("WINEPREFIX");
    char block_path[4096];
    if (prefix == NULL || snprintf(block_path, sizeof(block_path), "%s/client-block", prefix) <= 0) return 50;
    if (access(block_path, F_OK) == 0) while (!stopping) usleep(10000);
    fixture_write(STDOUT_FILENO, "client-stdout\n", 14);
    fixture_write(STDERR_FILENO, "client-stderr\n", 14);
    pid_t intermediate = fork();
    if (intermediate < 0) return 51;
    if (intermediate == 0) {
        pid_t unknown = fork();
        if (unknown < 0) _exit(52);
        if (unknown == 0) { fixture_write(STDOUT_FILENO, "adopted-stdout\n", 15); fixture_write(STDERR_FILENO, "adopted-stderr\n", 15); sleep(2); _exit(0); }
        _exit(0);
    }
    int status;
    if (waitpid(intermediate, &status, 0) != intermediate) return 53;
    usleep(100000);
    return CLIENT_EXIT;
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "-f") == 0) return server();
    if (argc > 1 && strcmp(argv[1], "cmd.exe") == 0) return client();
    if (argc < 2) return 61;
    execv(argv[1], argv + 1);
    return 62;
}
"""


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.fixture(scope="session")
def host_fixture(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, Path]:
    base = tmp_path_factory.mktemp("wine-supervisor-host")
    source = base / "fake.c"
    source.write_text(textwrap.dedent(FAKE_C), encoding="ascii")
    fake = base / "fake-main"
    subprocess.run(["gcc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-format-truncation", "-o", fake, source], check=True)
    runtime = base / "runtime"
    (runtime / "bin").mkdir(parents=True)
    (runtime / "lib/wine/aarch64-unix").mkdir(parents=True)
    (runtime / "lib/wine/aarch64-windows").mkdir(parents=True)
    for name in ("linker64", "wineserver"):
        target = runtime / "bin" / name
        target.write_bytes(fake.read_bytes())
        target.chmod(0o755)
    wine = runtime / "lib/wine/aarch64-unix/wine"
    wine.write_bytes(fake.read_bytes())
    wine.chmod(0o755)
    (runtime / "lib/wine/aarch64-unix/ntdll.so").write_bytes(fake.read_bytes())
    (runtime / "bin/wine").symlink_to("../lib/wine/aarch64-unix/wine")
    prefix = base / "prefix/.wine"
    prefix.mkdir(parents=True)
    prefix.chmod(0o700)

    defines = [
        "-DWINE_SUPERVISOR_SERVER_SHA256=\"%s\"" % digest(runtime / "bin/wineserver"),
        "-DWINE_SUPERVISOR_WINE_SHA256=\"%s\"" % digest(wine),
        "-DWINE_SUPERVISOR_NTDLL_SHA256=\"%s\"" % digest(runtime / "lib/wine/aarch64-unix/ntdll.so"),
    ]
    supervisor = base / "wine-supervisor-host"
    subprocess.run(
        ["gcc", "-DSUPERVISOR_HOST_TEST", *defines, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
         "-fPIE", "-pie", "-o", supervisor, SOURCE / "wine_run_supervisor.c"],
        check=True,
    )
    return supervisor, runtime


def launch(supervisor: Path, runtime: Path, prefix: Path) -> subprocess.Popen[bytes]:
    environment = os.environ.copy()
    environment.update({"SUPERVISOR_INHERITED": "must-clear", "WINESERVERSOCKET": "foreign",
                        "LD_PRELOAD": "foreign", "WINEPREFIX": "foreign", "WINEDLLPATH": "foreign"})
    return subprocess.Popen(
        [supervisor, "--run", "WS-" + "a" * 32, "--nonce", "b" * 32,
         "--runtime-root", runtime, "--prefix", prefix],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment,
    )


def command(seq: int, name: str, phase: str = "none") -> bytes:
    payload = {"schema": 2, "runId": "WS-" + "a" * 32, "nonce": "b" * 32,
               "seq": seq, "command": name, "phase": phase}
    assert set(payload) == COMMAND_KEYS
    return (json.dumps(payload, separators=(",", ":")) + "\n").encode()


def read_events(process: subprocess.Popen[bytes], timeout: float = 8.0,
                until: set[str] | None = None) -> list[dict[str, object]]:
    assert process.stdout is not None
    end = time.monotonic() + timeout
    result: list[dict[str, object]] = []
    while time.monotonic() < end:
        readable, _, _ = select.select([process.stdout], [], [], max(0.0, end - time.monotonic()))
        if not readable:
            break
        line = process.stdout.readline()
        if not line:
            break
        event = json.loads(line)
        assert set(event) == EVENT_KEYS
        result.append(event)
        if event["event"] in (until or {"RUN_DONE"}):
            break
    return result


def send(process: subprocess.Popen[bytes], payload: bytes) -> None:
    assert process.stdin is not None
    process.stdin.write(payload)
    process.stdin.flush()


def wait_done(process: subprocess.Popen[bytes], timeout: float = 10.0) -> tuple[list[dict[str, object]], int]:
    events = read_events(process, timeout)
    return events, process.wait(timeout=2)


def test_normal_server_client_adoption_and_echild(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"
    prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "START", "server"))
    ready = read_events(process, until={"SERVER_READY"})
    assert [event["event"] for event in ready] == ["READY", "SPAWN", "SERVER_READY"]
    send(process, command(2, "START", "client"))
    client_events = read_events(process, until={"PHASE_DONE"})
    assert any(event["event"] == "OWNED_ADOPT" for event in client_events)
    assert any(event["event"] == "PHASE_DONE" and event["status"] == "PASS" for event in client_events)
    send(process, command(3, "CLOSE"))
    done, status = wait_done(process)
    transcript = client_events + done
    assert {event["role"] for event in transcript if event["event"] == "EXIT"} >= {"server", "client", "adopted"}
    assert done[-1]["status"] == "PASS"
    assert done[-1]["childCount"] == 0 and done[-1]["echild"] is True
    assert status == 0
    assert (prefix / "home").is_dir() and (prefix / "tmp").is_dir()
    assert (prefix / "home").stat().st_mode & 0o777 == 0o700
    assert (prefix / "tmp").stat().st_mode & 0o777 == 0o700
    assert (prefix / ".supervisor-output/server.stdout").read_bytes() == b"server-stdout\n"
    assert (prefix / ".supervisor-output/server.stderr").read_bytes() == b"server-stderr\n"
    assert b"client-stdout\n" in (prefix / ".supervisor-output/client.stdout").read_bytes()
    assert b"adopted-stdout\n" in (prefix / ".supervisor-output/client.stdout").read_bytes()
    assert b"client-stderr\n" in (prefix / ".supervisor-output/client.stderr").read_bytes()
    assert b"adopted-stderr\n" in (prefix / ".supervisor-output/client.stderr").read_bytes()


def test_cancel_before_start_has_no_child(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "CLOSE"))
    events, status = wait_done(process)
    assert events[0]["event"] == "READY"
    assert events[-1]["status"] == "CANCELLED" and events[-1]["childCount"] == 0
    assert status == 0


@pytest.mark.parametrize("cancel", ["STOP", "CLOSE"])
def test_busy_server_accepts_only_cooperative_cancellation(
    host_fixture: tuple[Path, Path], tmp_path: Path, cancel: str
) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    (prefix / "server-block").touch()
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "START", "server"))
    assert read_events(process, until={"SPAWN"})[-1]["event"] == "SPAWN"
    send(process, command(2, cancel))
    events, status = wait_done(process)
    assert any(event["event"] == "EXIT" and event["role"] == "server" for event in events)
    assert events[-1]["status"] == "CANCELLED" and events[-1]["childCount"] == 0
    assert status == 0


def test_busy_client_accepts_stop_and_reaps_server_and_client(
    host_fixture: tuple[Path, Path], tmp_path: Path
) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "START", "server"))
    assert read_events(process, until={"SERVER_READY"})[-1]["event"] == "SERVER_READY"
    (prefix / "client-block").touch()
    send(process, command(2, "START", "client"))
    assert read_events(process, until={"SPAWN"})[-1]["role"] == "client"
    send(process, command(3, "STOP"))
    events, status = wait_done(process)
    assert {event["role"] for event in events if event["event"] == "EXIT"} >= {"server", "client"}
    assert events[-1]["status"] == "CANCELLED" and events[-1]["childCount"] == 0
    assert status == 0


def test_busy_wrong_nonce_fails_closed_before_server_ready(
    host_fixture: tuple[Path, Path], tmp_path: Path
) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    (prefix / "server-block").touch()
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "START", "server"))
    assert read_events(process, until={"SPAWN"})[-1]["event"] == "SPAWN"
    send(process, command(2, "STOP").replace(b'"nonce":"' + b"b" * 32, b'"nonce":"' + b"c" * 32))
    events, status = wait_done(process)
    assert events[-1]["status"] == "UNKNOWN" and status != 0


@pytest.mark.parametrize("payload", [
    b'{"schema":2,"runId":"WS-' + b"a" * 32 + b'","nonce":"' + b"b" * 32 + b'","seq":2,"command":"CLOSE","phase":"none"}\n',
    b'{"schema":2,"runId":"WS-' + b"a" * 32 + b'","nonce":"' + b"b" * 32 + b'","seq":1,"command":"CLOSE","phase":"none","extra":1}\n',
    b'{"schema":2,"runId":"WS-' + b"a" * 32 + b'","nonce":"' + b"b" * 32 + b'","seq":1,"command":"CLOSE","phase":"none","phase":"none"}\n',
    b'{"schema":2,"runId":"WS-' + b"a" * 32 + b'","nonce":"' + b"b" * 32 + b'","seq":1,"command":"CLOSE","phase":"none","extra":18446744073709551616}\n',
])
def test_wire_errors_fail_closed(host_fixture: tuple[Path, Path], tmp_path: Path, payload: bytes) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    assert read_events(process, until={"READY"})[0]["event"] == "READY"
    send(process, payload)
    events, status = wait_done(process)
    assert any(event["event"] == "ERROR" for event in events)
    assert events[-1]["status"] == "UNKNOWN" and status != 0


def test_eof_is_unknown_and_foreign_namespace_is_not_touched(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    assert read_events(process, until={"READY"})[0]["event"] == "READY"
    assert process.stdin is not None
    process.stdin.close()
    events, status = wait_done(process)
    assert events[-1]["status"] == "UNKNOWN" and status != 0

    foreign = tmp_path / "foreign/.wine"; foreign.mkdir(parents=True); foreign.chmod(0o700)
    st = foreign.stat()
    namespace = foreign / ".wineserver" / (f"server-{st.st_dev:x}-{st.st_ino:x}")
    namespace.mkdir(parents=True)
    socket_path = namespace / "socket"
    witness = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    old_cwd = os.getcwd()
    os.chdir(namespace)
    witness.bind("socket")
    os.chdir(old_cwd)
    socket_path.chmod(0o600)
    blocked = launch(supervisor, runtime, foreign)
    assert blocked.wait(timeout=2) == 2
    assert witness.fileno() >= 0
    assert {entry.name for entry in foreign.iterdir()} == {".wineserver"}
    witness.close()


def test_wrong_nonce_and_prefix_permissions_fail_closed(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    assert read_events(process, until={"READY"})[0]["event"] == "READY"
    send(process, command(1, "CLOSE").replace(b'"nonce":"' + b"b" * 32, b'"nonce":"' + b"c" * 32))
    events, status = wait_done(process)
    assert events[-1]["status"] == "UNKNOWN" and status != 0

    denied = tmp_path / "denied/.wine"; denied.mkdir(parents=True); denied.chmod(0o755)
    blocked = launch(supervisor, runtime, denied)
    assert blocked.wait(timeout=2) == 2


def test_source_pin_and_wrong_paths_fail_before_fork(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    original = runtime / "bin/wineserver"
    original_bytes = original.read_bytes()
    original.write_bytes(b"mutation")
    original.chmod(0o755)
    process = launch(supervisor, runtime, prefix)
    assert process.wait(timeout=2) == 2
    assert list(prefix.iterdir()) == []
    original.write_bytes(original_bytes)
    original.chmod(0o755)
    bad = subprocess.run([supervisor, "--run", "WS-" + "a" * 32, "--nonce", "b" * 32,
                          "--runtime-root", tmp_path / "missing", "--prefix", prefix])
    assert bad.returncode == 2


def test_source_replacement_during_hash_is_rejected(tmp_path: Path) -> None:
    source = tmp_path / "source.bin"
    replacement = tmp_path / "replacement.bin"
    source.write_bytes(b"pinned source\n")
    replacement.write_bytes(b"replacement source\n")
    executable = tmp_path / "source-race"
    source_code = r'''
#define _GNU_SOURCE
#include <errno.h>
#include <sys/syscall.h>
#include <unistd.h>
static const char *target_path;
static const char *replacement_path;
static int replaced;
static ssize_t source_read(int fd, void *buffer, size_t length);
#define read source_read
#include "wine_run_supervisor_common.h"
#undef read
static ssize_t source_read(int fd, void *buffer, size_t length) {
    ssize_t result = (ssize_t)syscall(SYS_read, fd, buffer, length);
    if (result > 0 && !replaced) {
        if (rename(replacement_path, target_path) != 0) _exit(70);
        replaced = 1;
    }
    return result;
}
int main(int argc, char **argv) {
    struct wine_source_file source;
    if (argc != 4) return 64;
    target_path = argv[1]; replacement_path = argv[2];
    return wine_open_source(argv[1], argv[3], &source) == 0 ? 1 : 0;
}
'''
    source_c = tmp_path / "source-race.c"
    source_c.write_text(source_code, encoding="ascii")
    subprocess.run(["gcc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-I", SOURCE, "-o", executable, source_c], check=True)
    expected = hashlib.sha256(source.read_bytes()).hexdigest()
    subprocess.run([executable, source, replacement, expected], check=True, timeout=2)


def test_output_close_still_reaps_owned_children(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "START", "server"))
    events = read_events(process, until={"SPAWN"})
    assert events[-1]["event"] == "SPAWN"
    assert process.stdout is not None
    process.stdout.close()
    assert process.wait(timeout=8) == 2
    assert not (prefix / ".wineserver").exists()


def test_log_overflow_is_bounded_unknown_and_durable_files_stay_capped(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    (prefix / "flood-logs").touch()
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "START", "server"))
    assert read_events(process, until={"SERVER_READY"})[-1]["event"] == "SERVER_READY"
    send(process, command(2, "CLOSE"))
    events, status = wait_done(process)
    assert events[-1]["status"] == "UNKNOWN" and status != 0
    assert (prefix / ".supervisor-output/server.stdout").stat().st_size <= 64 * 1024
    assert (prefix / ".supervisor-output/server.stderr").stat().st_size <= 64 * 1024


def test_preexisting_private_dirs_stop_before_any_child(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    for name in ("home", "tmp"):
        prefix = tmp_path / name / ".wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
        (prefix / name).mkdir(); (prefix / name).chmod(0o700)
        process = launch(supervisor, runtime, prefix)
        assert process.wait(timeout=2) == 2


def test_prefix_replacement_and_unexpected_server_exit_are_unknown(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "START", "server"))
    assert read_events(process, until={"SERVER_READY"})[-1]["event"] == "SERVER_READY"
    old_prefix = tmp_path / "old-prefix"
    prefix.rename(old_prefix)
    prefix.mkdir(); prefix.chmod(0o700)
    send(process, command(2, "START", "client"))
    events, status = wait_done(process)
    assert events[-1]["status"] == "UNKNOWN" and status != 0
    assert not (prefix / ".wineserver").exists()

    prefix_socket = tmp_path / "prefix-socket/.wine"; prefix_socket.mkdir(parents=True); prefix_socket.chmod(0o700)
    process = launch(supervisor, runtime, prefix_socket)
    send(process, command(1, "START", "server"))
    assert read_events(process, until={"SERVER_READY"})[-1]["event"] == "SERVER_READY"
    namespace_dir = next((prefix_socket / ".wineserver").iterdir())
    socket_path = namespace_dir / "socket"
    socket_path.rename(namespace_dir / "socket-owned")
    witness = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    old_cwd = os.getcwd(); os.chdir(namespace_dir); witness.bind("socket"); os.chdir(old_cwd)
    socket_path.chmod(0o600)
    send(process, command(2, "START", "client"))
    events, status = wait_done(process)
    assert events[-1]["status"] == "UNKNOWN" and status != 0
    assert witness.fileno() >= 0 and socket_path.exists()
    witness.close()

    prefix2 = tmp_path / "prefix2/.wine"; prefix2.mkdir(parents=True); prefix2.chmod(0o700)
    process = launch(supervisor, runtime, prefix2)
    send(process, command(1, "START", "server"))
    assert read_events(process, until={"SERVER_READY"})[-1]["event"] == "SERVER_READY"
    send(process, command(2, "START", "client"))
    assert read_events(process, until={"PHASE_DONE"})[-1]["event"] == "PHASE_DONE"
    (prefix2 / "server-exit").touch()
    events, status = wait_done(process)
    assert events[-1]["status"] == "UNKNOWN" and status != 0


def test_log_unlink_after_client_phase_cannot_qualify_pass(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"
    prefix.mkdir(parents=True)
    prefix.chmod(0o700)
    process = launch(supervisor, runtime, prefix)
    send(process, command(1, "START", "server"))
    assert read_events(process, until={"SERVER_READY"})[-1]["event"] == "SERVER_READY"
    send(process, command(2, "START", "client"))
    assert read_events(process, until={"PHASE_DONE"})[-1]["event"] == "PHASE_DONE"
    (prefix / ".supervisor-output/client.stdout").unlink()
    send(process, command(3, "CLOSE"))
    events, status = wait_done(process)
    assert events[-1]["status"] == "UNKNOWN" and status != 0
    assert events[-1]["echild"] is True


def test_android_runtime_cache_and_platform_loader_have_distinct_authorities(tmp_path: Path) -> None:
    source = tmp_path / "android-source-authorities.c"
    executable = tmp_path / "android-source-authorities"
    source.write_text(r'''
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
static uid_t parent_uid = 10330;
static mode_t parent_mode = 0755;
static int fake_stat(const char *path, struct stat *value) {
    memset(value, 0, sizeof(*value));
    value->st_mode = S_IFDIR | parent_mode;
    value->st_uid = strncmp(path, "/apex/", 6) == 0 ? 0 : parent_uid;
    value->st_gid = value->st_uid;
    return 0;
}
static int fake_access(const char *path, int mode) {
    (void)path; (void)mode; errno = EACCES; return -1;
}
#define __ANDROID__ 1
#define stat(path, value) fake_stat(path, value)
#define access(path, mode) fake_access(path, mode)
#define geteuid() ((uid_t)10330)
#define getegid() ((gid_t)10330)
#include "wine_run_supervisor_common.h"
int main(void) {
    const char *cache = "/data/data/com.wowforever.arm64probe/files/imagefs_shared/proton/proton-11.0-90624-arm64ec/bin/wineserver";
    struct stat value;
    memset(&value, 0, sizeof(value));
    value.st_mode = S_IFREG | 0755;
    value.st_uid = value.st_gid = 10330;
    value.st_nlink = 1;
    if (wine_source_permissions(cache, &value) != 0) return 1;
    parent_mode = 0771;
    if (wine_source_permissions(cache, &value) != 0) return 8;
    parent_mode = 0755;
    if (wine_source_permissions("/data/data/foreign/files/bin/wineserver", &value) == 0) return 2;
    value.st_mode = S_IFREG | 0775;
    if (wine_source_permissions(cache, &value) == 0) return 3;
    value.st_mode = S_IFREG | 0755;
    value.st_uid = 1000;
    if (wine_source_permissions(cache, &value) == 0) return 4;
    value.st_uid = 10330;
    parent_uid = 1000;
    if (wine_source_permissions(cache, &value) == 0) return 5;
    parent_uid = 10330; parent_mode = 0777;
    if (wine_source_permissions(cache, &value) == 0) return 6;
    parent_mode = 0755; value.st_uid = value.st_gid = 0;
    if (!wine_platform_linker_path("/apex/com.android.runtime/bin/linker64") ||
        wine_platform_linker_path("/apex/foreign/bin/linker64") ||
        wine_source_permissions("/apex/com.android.runtime/bin/linker64", &value) != 0) return 7;
    return 0;
}
''', encoding="ascii")
    subprocess.run(["gcc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-I", SOURCE, "-o", executable, source], check=True)
    subprocess.run([executable], check=True, timeout=2)


def test_inherited_sigchld_is_rejected_before_spawn(host_fixture: tuple[Path, Path], tmp_path: Path) -> None:
    supervisor, runtime = host_fixture
    prefix = tmp_path / "prefix/.wine"; prefix.mkdir(parents=True); prefix.chmod(0o700)
    process = subprocess.Popen(
        [supervisor, "--run", "WS-" + "a" * 32, "--nonce", "b" * 32,
         "--runtime-root", runtime, "--prefix", prefix],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env=os.environ.copy(), preexec_fn=lambda: signal.signal(signal.SIGCHLD, signal.SIG_IGN),
    )
    assert process.wait(timeout=2) == 2
