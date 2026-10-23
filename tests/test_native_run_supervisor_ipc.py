"""Host-only coverage for the frozen native fixture supervisor IPC."""

from __future__ import annotations

import json
import os
import select
import shutil
import signal
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
NATIVE = ROOT / "scripts/diagnostics/android-process"
EVENT_KEYS = {
    "schema", "runId", "nonce", "seq", "phase", "event", "pid", "startTicks", "ppid",
    "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode", "childCount", "echild",
    "setResult", "getResult", "getValue",
}


def _line(stream, timeout: float = 4.0) -> dict[str, object]:
    ready, _, _ = select.select([stream], [], [], timeout)
    assert ready, "native fixture produced no bounded IPC event"
    line = stream.readline()
    assert line
    return json.loads(line)


@pytest.fixture(scope="module")
def native_paths(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, Path]:
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    output = tmp_path_factory.mktemp("native-run")
    supervisor = output / "libforever_run_supervisor.so"
    fixture = output / "libforever_run_fixture.so"
    flags = [compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "-I", str(NATIVE)]
    subprocess.run([*flags, "-o", str(supervisor), str(NATIVE / "native_run_supervisor.c")], check=True)
    subprocess.run([*flags, "-o", str(fixture), str(NATIVE / "native_run_fixture.c")], check=True)
    return supervisor, fixture


def _ids(index: int) -> tuple[str, str]:
    return f"NS-{index:032x}", f"{index + 1:032x}"


def _command(run_id: str, nonce: str, sequence: int, phase: int, command: str,
             scenario: str = "none", event: dict[str, object] | None = None) -> dict[str, object]:
    return {
        "schema": 1, "runId": run_id, "nonce": nonce, "seq": sequence, "phase": phase,
        "command": command, "scenario": scenario,
        "pid": event["pid"] if event is not None else 0,
        "startTicks": event["startTicks"] if event is not None else 0,
        "eventSeq": event["seq"] if event is not None else 0,
    }


def _send(process: subprocess.Popen[str], document: dict[str, object]) -> None:
    assert process.stdin is not None
    process.stdin.write(json.dumps(document, separators=(",", ":")) + "\n")
    process.stdin.flush()


def _start(native_paths: tuple[Path, Path], index: int, loader: Path | None = None,
           argv0: str | None = None) -> tuple[subprocess.Popen[str], str, str]:
    supervisor, fixture = native_paths
    run_id, nonce = _ids(index)
    command = [str(supervisor), "--run", run_id, "--nonce", nonce, "--fixture", str(fixture)]
    executable = str(supervisor)
    if loader is not None:
        command.insert(0, str(loader))
        executable = None
    if argv0 is not None:
        command[0] = argv0
    process = subprocess.Popen(
        command, executable=executable,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, bufsize=1,
    )
    ready = _line(process.stdout)
    assert ready["event"] == "READY"
    return process, run_id, nonce


def _finish(process: subprocess.Popen[str]) -> None:
    if process.stdin is not None:
        process.stdin.close()
    process.wait(timeout=5)
    assert process.stderr is not None and process.stderr.read() == ""


def _run_phase(process: subprocess.Popen[str], run_id: str, nonce: str, sequence: int,
               phase: int) -> int:
    _send(process, _command(run_id, nonce, sequence, phase, "START", "normal"))
    sequence += 1
    for _ in range(3):
        event = _line(process.stdout)
        assert event["event"] in {"SPAWN", "ADOPT"}
        assert event["status"] == "RUNNING" and event["childCount"] > 0
        _send(process, _command(run_id, nonce, sequence, phase, "ACK", event=event))
        sequence += 1
    first_exit = _line(process.stdout)
    second_exit = _line(process.stdout)
    assert first_exit["event"] == "EXIT" and first_exit["status"] == "REAPED"
    assert second_exit["event"] == "EXIT" and second_exit["status"] == "REAPED"
    assert first_exit["childCount"] == 1 and second_exit["childCount"] == 0
    phase_done = _line(process.stdout)
    assert phase_done["event"] == "PHASE_DONE"
    assert phase_done["status"] == "PASS"
    return sequence


def test_normal_two_phase_exact_schema_and_independent_sequences(native_paths: tuple[Path, Path]) -> None:
    process, run_id, nonce = _start(native_paths, 1)
    try:
        sequence = _run_phase(process, run_id, nonce, 1, 1)
        sequence = _run_phase(process, run_id, nonce, sequence, 2)
        _send(process, _command(run_id, nonce, sequence, 0, "CLOSE"))
        done = _line(process.stdout)
        assert done["event"] == "RUN_DONE" and done["status"] == "PASS" and done["echild"] is True
        assert set(done) == EVENT_KEYS
        assert done["seq"] > 1
        _finish(process)
        assert process.returncode == 0
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()


def test_direct_and_explicit_loader_have_the_same_two_phase_transcript(
    native_paths: tuple[Path, Path],
) -> None:
    loader = next((Path(candidate) for candidate in (
        "/lib64/ld-linux-x86-64.so.2",
        "/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
    ) if Path(candidate).is_file()), None)
    if loader is None:
        pytest.skip("host x86-64 dynamic loader unavailable")
    for index, explicit_loader in enumerate((None, loader), start=50):
        process, run_id, nonce = _start(native_paths, index, explicit_loader)
        try:
            sequence = _run_phase(process, run_id, nonce, 1, 1)
            sequence = _run_phase(process, run_id, nonce, sequence, 2)
            _send(process, _command(run_id, nonce, sequence, 0, "CLOSE"))
            done = _line(process.stdout)
            assert done["event"] == "RUN_DONE" and done["status"] == "PASS"
            _finish(process)
            assert process.returncode == 0
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


def test_cancel_before_start_creates_no_fixture_children(native_paths: tuple[Path, Path]) -> None:
    process, run_id, nonce = _start(native_paths, 2)
    _send(process, _command(run_id, nonce, 1, 0, "CLOSE"))
    done = _line(process.stdout)
    assert done["event"] == "RUN_DONE" and done["status"] == "CANCELLED"
    _finish(process)
    assert process.returncode == 0


def test_cancel_during_double_fork_is_bounded_and_cancelled(native_paths: tuple[Path, Path]) -> None:
    process, run_id, nonce = _start(native_paths, 3)
    _send(process, _command(run_id, nonce, 1, 1, "START", "hold"))
    intermediate = _line(process.stdout)
    assert intermediate["event"] == "SPAWN" and intermediate["role"] == "intermediate"
    _send(process, _command(run_id, nonce, 2, 1, "ACK", event=intermediate))
    grandchild = _line(process.stdout)
    assert grandchild["event"] == "SPAWN" and grandchild["role"] == "grandchild"
    _send(process, _command(run_id, nonce, 3, 1, "STOP"))
    transcript = []
    while transcript == [] or transcript[-1]["event"] != "RUN_DONE":
        transcript.append(_line(process.stdout))
    assert [event["event"] for event in transcript] == ["EXIT", "ADOPT", "EXIT", "RUN_DONE"]
    adopted = transcript[1]
    assert adopted["pid"] == grandchild["pid"] and adopted["startTicks"] == grandchild["startTicks"]
    assert adopted["ppid"] == intermediate["ppid"]
    assert transcript[0]["pid"] == intermediate["pid"]
    assert transcript[2]["pid"] == grandchild["pid"]
    assert transcript[0]["childCount"] == 1 and transcript[2]["childCount"] == 0
    assert transcript[-1]["status"] == "CANCELLED" and transcript[-1]["childCount"] == 0
    assert transcript[-1]["echild"] is True
    _finish(process)
    assert process.returncode == 0


@pytest.mark.parametrize("kind", ["eof", "invalid"])
def test_eof_and_invalid_protocol_never_qualify_success(
    native_paths: tuple[Path, Path], kind: str,
) -> None:
    process, run_id, nonce = _start(native_paths, 10 if kind == "eof" else 11)
    if kind == "eof":
        assert process.stdin is not None
        process.stdin.close()
    else:
        assert process.stdin is not None
        process.stdin.write(
            '{"schema":1,"schema":1,"runId":"%s","nonce":"%s","seq":1,'
            '"phase":0,"command":"CLOSE","scenario":"none","pid":0,'
            '"startTicks":0,"eventSeq":0}\n' % (run_id, nonce)
        )
        process.stdin.flush()
    events = [_line(process.stdout), _line(process.stdout)]
    assert events[0]["event"] == "ERROR"
    assert events[1]["event"] == "RUN_DONE" and events[1]["status"] != "PASS"
    process.wait(timeout=5)
    assert process.returncode != 0


def test_supervisor_failure_is_not_reclassified_from_finite_fixture(native_paths: tuple[Path, Path]) -> None:
    process, run_id, nonce = _start(native_paths, 12)
    process.kill()
    assert process.wait(timeout=3) == -signal.SIGKILL
    assert process.stdout.read() == ""
    assert run_id.startswith("NS-") and len(nonce) == 32


def test_witness_is_separate_process_and_exits_zero(native_paths: tuple[Path, Path]) -> None:
    _, fixture = native_paths
    run_id, nonce = _ids(13)
    witness = subprocess.Popen(
        [str(fixture), "--witness", run_id, nonce], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
    )
    ready = _line(witness.stdout)
    assert ready["role"] == "witness" and ready["status"] == "READY"
    assert ready["pid"] != os.getpid() and set(ready) == EVENT_KEYS
    assert witness.stdin is not None
    witness.stdin.write("Q\n")
    witness.stdin.flush()
    witness.stdin.close()
    assert witness.wait(timeout=3) == 0
    assert witness.stderr.read() == ""


def test_duplicate_unknown_and_oversize_commands_fail_closed(native_paths: tuple[Path, Path]) -> None:
    for index, payload in enumerate((
        '{"schema":1,"schema":1,"runId":"NS-%s","nonce":"%s","seq":1,"phase":0,'
        '"command":"CLOSE","scenario":"none","pid":0,"startTicks":0,"eventSeq":0}\n',
        "{" + "x" * 8200 + "}\n",
    ), start=20):
        process, run_id, nonce = _start(native_paths, index)
        if "x" not in payload:
            payload = payload % (run_id[3:], nonce)
        assert process.stdin is not None
        process.stdin.write(payload)
        process.stdin.flush()
        first = _line(process.stdout)
        done = _line(process.stdout)
        assert first["event"] == "ERROR" and done["event"] == "RUN_DONE" and done["status"] != "PASS"
        process.wait(timeout=5)
        assert process.returncode != 0


def _assert_image_guard_failure(supervisor: Path, fixture: Path, run_id_index: int) -> None:
    run_id, nonce = _ids(run_id_index)
    process = subprocess.Popen(
        [str(supervisor), "--run", run_id, "--nonce", nonce, "--fixture", str(fixture)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, bufsize=1,
    )
    stdout, stderr = process.communicate(timeout=3)
    assert process.returncode == 2
    assert stdout == ""
    assert stderr == "native-run-supervisor: stage=image-guard\n"


def test_image_guard_rejects_external_fixture_and_fixture_symlink(
    native_paths: tuple[Path, Path], tmp_path: Path,
) -> None:
    supervisor, fixture = native_paths
    external = tmp_path / "external"
    external.mkdir()
    external_fixture = external / "libforever_run_fixture.so"
    shutil.copy2(fixture, external_fixture)
    _assert_image_guard_failure(supervisor, external_fixture, 60)

    symlink_dir = tmp_path / "symlink"
    symlink_dir.mkdir()
    symlink_fixture = symlink_dir / "libforever_run_fixture.so"
    symlink_fixture.symlink_to(fixture)
    _assert_image_guard_failure(supervisor, symlink_fixture, 61)


def test_image_guard_rejects_renamed_image_even_when_argv0_is_spoofed(
    native_paths: tuple[Path, Path], tmp_path: Path,
) -> None:
    supervisor, fixture = native_paths
    renamed_dir = tmp_path / "renamed"
    renamed_dir.mkdir()
    renamed = renamed_dir / "renamed-supervisor"
    renamed_fixture = renamed_dir / "libforever_run_fixture.so"
    shutil.copy2(supervisor, renamed)
    shutil.copy2(fixture, renamed_fixture)
    run_id, nonce = _ids(62)
    process = subprocess.Popen(
        ["libforever_run_supervisor.so", "--run", run_id, "--nonce", nonce,
         "--fixture", str(renamed_fixture)],
        executable=str(renamed), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, bufsize=1,
    )
    stdout, stderr = process.communicate(timeout=3)
    assert process.returncode == 2
    assert stdout == ""
    assert stderr == "native-run-supervisor: stage=image-guard\n"


@pytest.mark.parametrize(("permissions", "accepted"), (
    ("rwxp", False),
    ("r-xp", True),
    ("r-xs", True),
))
def test_maps_parser_rejects_writable_code_mapping(
    native_paths: tuple[Path, Path], tmp_path: Path, permissions: str, accepted: bool,
) -> None:
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    supervisor, _ = native_paths
    source = tmp_path / "maps-parser-fixture.c"
    source.write_text(
        """
#include "native_run_common.h"

int main(int argc, char **argv) {
    struct stat file_stat;
    char line[PATH_MAX + 256];
    if (argc != 3 || stat(argv[1], &file_stat) != 0) return 2;
    int length = snprintf(line, sizeof(line),
                          "1000-2000 %s 00000000 %llx:%llx %llu %s\\n", argv[2],
                          (unsigned long long)major(file_stat.st_dev),
                          (unsigned long long)minor(file_stat.st_dev),
                          (unsigned long long)file_stat.st_ino, argv[1]);
    if (length <= 0 || (size_t)length >= sizeof(line)) return 2;
    FILE *stream = fmemopen(line, (size_t)length, "r");
    if (stream == NULL) return 2;
    struct native_run_loaded_image image;
    int result = native_run_loaded_image_stream(stream, 0x1500U, &image);
    (void)fclose(stream);
    return result == 0 ? 0 : 1;
}
""",
        encoding="ascii",
    )
    helper = tmp_path / "maps-parser-fixture"
    flags = [compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie",
             "-I", str(NATIVE)]
    subprocess.run([*flags, "-o", str(helper), str(source)], check=True)
    result = subprocess.run([str(helper), str(supervisor), permissions], check=False)
    assert result.returncode == (0 if accepted else 1)


def test_fixture_identity_revalidation_rejects_replacement_after_guard(
    native_paths: tuple[Path, Path], tmp_path: Path,
) -> None:
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    _, fixture = native_paths
    image = tmp_path / "libforever_run_supervisor.so"
    original = tmp_path / "libforever_run_fixture.so"
    backup = tmp_path / "fixture-original"
    replacement = tmp_path / "fixture-replacement"
    source = tmp_path / "identity-fixture.c"
    shutil.copy2(fixture, original)
    shutil.copy2(fixture, replacement)
    source.write_text(
        """
#include "native_run_common.h"

int main(int argc, char **argv) {
    struct native_run_fixture_guard guard;
    if (argc != 4 || native_run_same_parent_fixture(argv[1], &guard) != 0) return 2;
    if (rename(argv[1], argv[2]) != 0 || rename(argv[3], argv[1]) != 0) return 3;
    return native_run_fixture_guard_current(&guard) == 0 ? 1 : 0;
}
""",
        encoding="ascii",
    )
    flags = [compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie",
             "-I", str(NATIVE)]
    subprocess.run([*flags, "-o", str(image), str(source)], check=True)
    result = subprocess.run([str(image), str(original), str(backup), str(replacement)], check=False)
    assert result.returncode == 0


@pytest.mark.parametrize(("file_mode", "parent_mode", "owner", "access_result", "accepted"), (
    (0o755, 0o755, 0, 1, True),
    (0o775, 0o755, 0, 1, False),
    (0o757, 0o755, 0, 1, False),
    (0o755, 0o775, 0, 1, False),
    (0o755, 0o757, 0, 1, False),
    (0o755, 0o755, 10330, 1, False),
    (0o755, 0o755, 0, 0, False),
))
def test_android_immutable_path_policy_is_fail_closed(
    tmp_path: Path, file_mode: int, parent_mode: int, owner: int,
    access_result: int, accepted: bool,
) -> None:
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    directory = tmp_path / "native-library"
    directory.mkdir(mode=0o755)
    fixture = directory / "libforever_run_fixture.so"
    fixture.write_bytes(b"fixture")
    fixture.chmod(file_mode)
    directory.chmod(parent_mode)
    source = tmp_path / "android-immutable-fixture.c"
    source.write_text(
        """
static int native_run_test_access(const char *path, int mode);
#define access native_run_test_access
#include "native_run_common.h"
#undef access

static int native_run_test_access_result;

static int native_run_test_access(const char *path, int mode) {
    (void)path;
    (void)mode;
    return native_run_test_access_result;
}

int main(int argc, char **argv) {
    uint64_t owner;
    uint64_t access_result;
    struct stat file_stat;
    if (argc != 4 || stat(argv[1], &file_stat) != 0 ||
        native_run_decimal(argv[2], &owner) != 0 || owner > UINT_MAX ||
        native_run_decimal(argv[3], &access_result) != 0 || access_result > INT_MAX) return 2;
    file_stat.st_uid = (uid_t)owner;
    native_run_test_access_result = (int)access_result;
    return native_run_android_immutable_path(argv[1], &file_stat) == 0 ? 0 : 1;
}
""",
        encoding="ascii",
    )
    helper = tmp_path / "android-immutable-fixture"
    flags = [compiler, "-D__ANDROID__", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
             "-fPIE", "-pie", "-I", str(NATIVE)]
    subprocess.run([*flags, "-o", str(helper), str(source)], check=True)
    result = subprocess.run(
        [str(helper), str(fixture), str(owner), str(access_result)], check=False,
    )
    assert result.returncode == (0 if accepted else 1)


def test_ack_identity_mismatch_is_rejected_without_naked_pid_signal(
    native_paths: tuple[Path, Path],
) -> None:
    _, fixture = native_paths
    run_id, nonce = _ids(30)
    witness = subprocess.Popen(
        [str(fixture), "--witness", run_id, nonce], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
    )
    witness_ready = _line(witness.stdout)
    process, _, _ = _start(native_paths, 30)
    _send(process, _command(run_id, nonce, 1, 1, "START", "normal"))
    spawn = _line(process.stdout)
    assert spawn["event"] == "SPAWN"
    bad = dict(witness_ready)
    _send(process, _command(run_id, nonce, 2, 1, "ACK", event=bad))
    transcript = []
    while transcript == [] or transcript[-1]["event"] != "RUN_DONE":
        transcript.append(_line(process.stdout))
    assert any(event["event"] == "ERROR" for event in transcript)
    assert transcript[-1]["event"] == "RUN_DONE" and transcript[-1]["status"] != "PASS"
    assert [event["event"] for event in transcript[:2]] == ["EXIT", "ERROR"]
    assert transcript[0]["pid"] == spawn["pid"] and transcript[0]["startTicks"] == spawn["startTicks"]
    process.wait(timeout=5)
    assert process.returncode != 0
    assert witness.poll() is None
    assert witness.stdin is not None
    witness.stdin.write("Q\n")
    witness.stdin.flush()
    witness.stdin.close()
    assert witness.wait(timeout=3) == 0
    assert witness.stderr.read() == ""


def test_eof_after_registered_spawn_emits_adoption_and_reaped_exits(
    native_paths: tuple[Path, Path],
) -> None:
    process, run_id, nonce = _start(native_paths, 31)
    _send(process, _command(run_id, nonce, 1, 1, "START", "normal"))
    intermediate = _line(process.stdout)
    _send(process, _command(run_id, nonce, 2, 1, "ACK", event=intermediate))
    grandchild = _line(process.stdout)
    assert grandchild["event"] == "SPAWN"
    assert process.stdin is not None
    process.stdin.close()
    transcript = []
    while transcript == [] or transcript[-1]["event"] != "RUN_DONE":
        transcript.append(_line(process.stdout))
    assert [event["event"] for event in transcript] == ["EXIT", "ADOPT", "EXIT", "ERROR", "RUN_DONE"]
    assert transcript[1]["pid"] == grandchild["pid"]
    assert transcript[1]["startTicks"] == grandchild["startTicks"]
    assert transcript[1]["ppid"] == intermediate["ppid"]
    assert transcript[2]["pid"] == grandchild["pid"]
    assert transcript[-1]["childCount"] == 0 and transcript[-1]["echild"] is True
    process.wait(timeout=5)
    assert process.returncode != 0


def test_nonzero_reaped_fixture_exit_cannot_emit_phase_pass(tmp_path: Path) -> None:
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    supervisor = tmp_path / "libforever_run_supervisor.so"
    fixture = tmp_path / "libforever_run_fixture.so"
    fixture_source = (NATIVE / "native_run_fixture.c").read_text()
    marker = "        release == 'R') {\n        _exit(0);\n    }"
    assert fixture_source.count(marker) == 1
    (tmp_path / "fixture-exit7.c").write_text(fixture_source.replace(marker, marker.replace("_exit(0)", "_exit(7)")))
    flags = [compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "-I", str(NATIVE)]
    subprocess.run([*flags, "-o", str(supervisor), str(NATIVE / "native_run_supervisor.c")], check=True)
    subprocess.run([*flags, "-o", str(fixture), str(tmp_path / "fixture-exit7.c")], check=True)

    process, run_id, nonce = _start((supervisor, fixture), 40)
    _send(process, _command(run_id, nonce, 1, 1, "START", "normal"))
    for sequence in range(2, 5):
        event = _line(process.stdout)
        _send(process, _command(run_id, nonce, sequence, 1, "ACK", event=event))
    first_exit = _line(process.stdout)
    grandchild_exit = _line(process.stdout)
    assert first_exit["event"] == "EXIT" and first_exit["exitCode"] == 0
    assert grandchild_exit["event"] == "EXIT" and grandchild_exit["exitCode"] == 7
    error = _line(process.stdout)
    done = _line(process.stdout)
    assert error["event"] == "ERROR"
    assert done["event"] == "RUN_DONE" and done["status"] != "PASS" and done["echild"] is True
    process.wait(timeout=5)
    assert process.returncode != 0
