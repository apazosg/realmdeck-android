"""Synthetic-only guards for the own-process Windows cert-store tracer."""

from __future__ import annotations

import importlib.util
import hashlib
import json
import os
import re
import subprocess
from types import SimpleNamespace
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/reverse-engineering/x64-certstore-trace/run_wow_trace.py"
SOURCE = ROOT / "scripts/diagnostics/reverse-engineering/x64-certstore-trace/wow_trace.c"


def _module():
    spec = importlib.util.spec_from_file_location("run_wow_trace", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_tracer_is_fixed_child_debug_api_and_contract_bounded() -> None:
    source = SOURCE.read_text()
    assert "DEBUG_ONLY_THIS_PROCESS" in source
    assert "CreateProcessW" in source
    assert "OpenProcess" not in source
    assert "WriteProcessMemory" not in source
    assert "VirtualQueryEx" in source
    assert "PAGE_READWRITE" in source
    assert "MAX_SCAN_BYTES" in source
    assert "EVENT_LIMIT 64u" in source
    assert "STACK_LIMIT 16u" in source
    assert "WowB.exe" in source
    assert "BetaTrace.wtf" in source
    assert "-d3d11 -config BetaTrace.wtf" in source
    assert "portal" not in source.lower()


def test_synthetic_mode_is_explicit_and_container_isolated(tmp_path: Path) -> None:
    module = _module()
    command = module.docker_command(
        tmp_path, "x64-certstore-trace-123-456", "synthetic", None,
        tmp_path / "cid", "x64-certstore-trace-123-456",
    )
    joined = " ".join(command)
    for required in (
        "--user 1000:1000",
        "--network none",
        "--cap-drop ALL",
        "--security-opt no-new-privileges",
        "--read-only",
        "--memory 6g",
        "--cpus 2",
        "--pids-limit 128",
        "--entrypoint /bin/sh",
        "--tmpfs /tmp:rw,size=256m,mode=1777",
        "--workdir /lab",
    ):
        assert required in joined
    assert "start-forever.sh" not in joined
    assert "WowB.exe" not in joined
    assert "--real" not in joined
    assert "--cidfile" in joined
    assert "--label com.wowforever.trace-run=x64-certstore-trace-123-456" in joined
    assert "cd /lab" in joined and "wineboot --init" in joined
    assert "mkdir -p /lab/wineprefix" not in joined
    assert "ln -s / /lab/wineprefix/dosdevices/z:" not in joined


def test_real_command_requires_private_gui_and_fixed_config(tmp_path: Path) -> None:
    module = _module()
    command = module.docker_command(
        tmp_path, "x64-certstore-trace-real-123", "real", module.DEFAULT_CLIENT,
        tmp_path / "cid", "x64-certstore-trace-real-123", display=99,
        socket_path=Path("/tmp/.X11-unix/X99"), xauthority=tmp_path / ".xauthority",
    )
    joined = " ".join(command)
    assert "--network none" in joined
    assert "--user 1000:1000" in joined
    assert "xserver_status=host-ready" in joined
    assert "DISPLAY=:99" in joined
    assert "-d3d11 -config BetaTrace.wtf" in SOURCE.read_text()
    assert "BetaSuspendedTest.wtf" not in joined
    assert "start-forever.sh" not in joined
    assert f"src={tmp_path},dst=/lab" not in joined
    assert f"src={tmp_path / 'runtime'},dst=/lab" in joined
    assert f"src={tmp_path / 'wow_trace.exe'},dst=/lab/wow_trace.exe,readonly" in joined
    assert f"src={tmp_path / 'game'},dst=/lab/game" in joined
    assert "dst=/tmp/.X11-unix/X99,readonly" in joined
    assert "dst=/lab/.xauthority,readonly" in joined
    assert ",rw" not in joined
    assert "--tmpfs /home" not in joined
    assert "HOME=/lab/home XDG_CACHE_HOME=/lab/home/.cache" in joined


def test_child_handle_list_and_module_records_are_fail_closed() -> None:
    source = SOURCE.read_text()
    assert "STARTUPINFOEXW" in source
    assert "PROC_THREAD_ATTRIBUTE_HANDLE_LIST" in source
    assert "EXTENDED_STARTUPINFO_PRESENT" in source
    assert "UpdateProcThreadAttribute" in source
    assert "CreateProcessW" in source and "TRUE, flags" in source
    assert "MODULE_LIMIT 128u" in source
    assert "remember_module" in source and "forget_module" in source
    assert "module_for_instruction" in source
    assert "UNLOAD_DLL_DEBUG_EVENT" in source
    assert '\\"base\\"' in source and '\\"size\\"' in source
    assert "event_cap_exceeded" in source


def test_child_creation_flags_keep_debug_only_process_mode_explicit(tmp_path: Path) -> None:
    source = SOURCE.read_text()
    function = re.search(r"static DWORD child_creation_flags\(int debug_child\) \{.*?\n\}", source, re.S)
    assert function is not None
    harness = (
        "#include <assert.h>\n#include <stdint.h>\n"
        "typedef uint32_t DWORD;\n"
        "#define CREATE_UNICODE_ENVIRONMENT 0x00000400u\n"
        "#define EXTENDED_STARTUPINFO_PRESENT 0x00080000u\n"
        "#define DEBUG_ONLY_THIS_PROCESS 0x00000002u\n"
        + function.group(0)
        + "\nint main(void) {\n"
        "DWORD passive = child_creation_flags(0);\n"
        "DWORD traced = child_creation_flags(1);\n"
        "assert((passive & DEBUG_ONLY_THIS_PROCESS) == 0);\n"
        "assert((traced & DEBUG_ONLY_THIS_PROCESS) != 0);\n"
        "assert((passive & EXTENDED_STARTUPINFO_PRESENT) != 0);\n"
        "assert((traced & EXTENDED_STARTUPINFO_PRESENT) != 0);\n"
        "return 0; }\n"
    )
    c_file = tmp_path / "creation_flags.c"
    binary = tmp_path / "creation_flags"
    c_file.write_text(harness)
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", str(c_file), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)


def test_passive_command_preserves_real_container_controls(tmp_path: Path) -> None:
    module = _module()
    command = module.docker_command(
        tmp_path, "x64-certstore-trace-passive-123", "passive-real", module.DEFAULT_CLIENT,
        tmp_path / "cid", "x64-certstore-trace-passive-123", display=99,
        socket_path=Path("/tmp/.X11-unix/X99"), xauthority=tmp_path / ".xauthority",
    )
    joined = " ".join(command)
    assert "--passive-real" in joined
    assert "--network none" in joined and "--cap-drop ALL" in joined
    assert "--user 1000:1000" in joined and "--read-only" in joined
    assert "dst=/tmp/.X11-unix/X99,readonly" in joined
    assert "dst=/lab/.xauthority,readonly" in joined
    assert "dst=/source,readonly" in joined
    traced = module.docker_command(
        tmp_path, "x64-certstore-trace-passive-traced", "passive-real", module.DEFAULT_CLIENT,
        tmp_path / "cid2", "x64-certstore-trace-passive-traced", display=99,
        socket_path=Path("/tmp/.X11-unix/X99"), xauthority=tmp_path / ".xauthority",
        trace_build_config=True,
    )
    traced_text = " ".join(traced)
    assert "WINEDEBUG=-all,+file wine /lab/wow_trace.exe --passive-real" in traced_text
    real = module.docker_command(
        tmp_path, "x64-certstore-trace-real-untraced", "real", module.DEFAULT_CLIENT,
        tmp_path / "cid3", "x64-certstore-trace-real-untraced", display=99,
        socket_path=Path("/tmp/.X11-unix/X99"), xauthority=tmp_path / ".xauthority",
    )
    assert "WINEDEBUG" not in " ".join(real)


def test_build_config_filter_bounds_lines_discards_unmatched_and_keeps_final() -> None:
    module = _module()
    state = module._new_build_config_filter(0.0)
    selected = []
    selected.extend(module._consume_trace_chunk(
        state,
        b"trace:file:CreateFile Z:\\lab\\game\\Data\\config\\05\\21\\05215079e3905ef5922ae0b03ffefb73 flags=GENERIC_READ\n"
        b"trace:file:ReadFile /home/private/account-state token=discarded\n"
        b"trace:file:WriteFile " + b"x" * (module.TRACE_LINE_CAP + 1) + b"\n",
    ))
    selected.extend(module._consume_trace_chunk(state, b'{"type":"final","authentication_claim":false}\n'))
    selected.extend(module._flush_trace_filter(state))
    joined = b"".join(selected).decode()
    assert '"type":"final"' in joined
    assert "account-state" not in joined
    assert module.BUILD_CONFIG_KEY not in joined
    assert state["dropped_count"] == 2
    assert len(state["records"]) == 1
    assert state["records"][0]["operation"] == "CreateFile"
    assert state["records"][0]["match"] == "config-key"


def test_build_config_artifact_is_bounded_and_json_parseable(tmp_path: Path) -> None:
    module = _module()
    state = module._new_build_config_filter(0.0)
    state["dropped_count"] = 91
    state["records"] = [
        {"timestamp_ms": index, "bytes": 2048, "match": "build-info", "operation": "ReadFile", "line": "m" * 2048}
        for index in range(module.BUILD_CONFIG_RECORD_CAP)
    ]
    report = module._write_build_config_artifact(tmp_path, state)
    artifact = tmp_path / report["artifact"]
    assert report["bytes"] <= module.BUILD_CONFIG_ARTIFACT_CAP
    assert artifact.stat().st_mode & 0o777 == 0o600
    parsed = json.loads(artifact.read_text())
    assert parsed["dropped_count"] == 91
    assert len(parsed["records"]) <= module.BUILD_CONFIG_RECORD_CAP
    assert all(len(item["line"].encode()) <= module.BUILD_CONFIG_RECORD_LINE_CAP for item in parsed["records"])


def test_private_data_view_uses_aliases_and_bounded_index_copies(tmp_path: Path) -> None:
    module = _module()
    source = tmp_path / "client"
    data_root = source / "Data"
    data_dir = data_root / "data"
    for name in ("config", "indices", "data"):
        (data_root / name).mkdir(mode=0o700, parents=True)
    archive_bytes: dict[str, str] = {}
    for name in module.DATA_ARCHIVES:
        path = data_dir / name
        path.write_bytes(b"")
        archive_bytes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    index_bytes: dict[str, bytes] = {}
    for index in range(16):
        name = f"{index:010x}.idx"
        payload = f"synthetic-index-{index}".encode()
        (data_dir / name).write_bytes(payload)
        index_bytes[name] = payload
    source_before = {
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in data_dir.iterdir()
    }
    output = tmp_path / "run"
    output.mkdir(mode=0o700)
    module.prepare_runtime(output)
    view = module.prepare_real_data_view(output, source)
    runtime_data = output / "runtime" / "Data"
    scratch = runtime_data / "data"
    assert view["source_archive_count"] == 93 and view["source_index_count"] == 16
    assert (runtime_data / "config").is_symlink()
    assert os.readlink(runtime_data / "config") == "/source/Data/config"
    assert os.readlink(runtime_data / "indices") == "/source/Data/indices"
    assert (scratch / "data.000").is_symlink()
    assert os.readlink(scratch / "data.000") == "/source/Data/data/data.000"
    assert all((scratch / name).stat().st_mode & 0o777 == 0o600 for name in index_bytes)
    assert all(not (scratch / name).is_symlink() for name in index_bytes)
    assert all(hashlib.sha256((scratch / name).read_bytes()).digest() == hashlib.sha256(payload).digest()
               for name, payload in index_bytes.items())
    assert {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in data_dir.iterdir()} == source_before
    assert len(archive_bytes) == 93
    finalized = module.finalize_real_data_view(view, source)
    assert finalized["source_indexes_unchanged"] is True
    assert finalized["source_archives_unchanged"] is True
    assert finalized["source_unchanged"] is True

    with pytest.raises(FileExistsError):
        module.prepare_real_data_view(output, source)
    extra = data_dir / "unexpected"
    extra.write_bytes(b"")
    invalid_output = tmp_path / "invalid-extra"
    invalid_output.mkdir(mode=0o700)
    module.prepare_runtime(invalid_output)
    with pytest.raises(module.TraceError, match="unexpected entry"):
        module.prepare_real_data_view(invalid_output, source)
    extra.unlink()
    bad_index = data_dir / "0000000000.idx"
    bad_index.unlink()
    bad_index.symlink_to(data_dir / "data.000")
    symlink_output = tmp_path / "invalid-symlink"
    symlink_output.mkdir(mode=0o700)
    module.prepare_runtime(symlink_output)
    with pytest.raises(module.TraceError):
        module.prepare_real_data_view(symlink_output, source)


def test_copy_working_archives_makes_independent_private_files_and_preserves_source(tmp_path: Path) -> None:
    module = _module()
    source = tmp_path / "client"
    data_dir = source / "Data" / "data"
    (source / "Data" / "config").mkdir(mode=0o700, parents=True)
    (source / "Data" / "indices").mkdir(mode=0o700)
    data_dir.mkdir(mode=0o700)
    cdn_index = source / "Data" / "indices" / ("a" * 32 + ".index")
    cdn_index.write_bytes(b"synthetic-cdn-index")
    for name in module.DATA_ARCHIVES:
        (data_dir / name).write_bytes((name + "-ordinary").encode())
    for index in range(16):
        (data_dir / f"{index:010x}.idx").write_bytes(f"idx-{index}".encode())
    before = {
        path.name: (path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())
        for path in data_dir.iterdir()
    }
    output = tmp_path / "copy-run"
    output.mkdir(mode=0o700)
    module.prepare_runtime(output)
    view = module.prepare_real_data_view(output, source, copy_working_archives=True)
    scratch = output / "runtime" / "Data" / "data"
    assert view["working_archive_mode"] == "copy"
    assert len(view["working_archive_copies"]) == 93
    assert len(view["cdn_index_copies"]) == 1
    assert view["indices_link"] is None
    assert all(not (scratch / name).is_symlink() for name in module.DATA_ARCHIVES)
    assert all((scratch / name).stat().st_mode & 0o777 == 0o600 for name in module.DATA_ARCHIVES)
    copied_cdn = output / "runtime" / "Data" / "indices" / cdn_index.name
    assert copied_cdn.stat().st_mode & 0o777 == 0o600
    assert not copied_cdn.is_symlink()
    assert hashlib.sha256(copied_cdn.read_bytes()).hexdigest() == hashlib.sha256(cdn_index.read_bytes()).hexdigest()
    record = view["working_archive_copies"][0]
    assert record["source_metadata_stable"] is True
    assert record["destination_identity_distinct"] is True
    assert record["source_sha256"] == record["destination_sha256"]
    assert before == {
        path.name: (path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())
        for path in data_dir.iterdir()
    }
    finalized = module.finalize_real_data_view(view, source, output)
    assert finalized["source_unchanged"] is True
    assert finalized["source_cdn_indices_unchanged"] is True
    assert finalized["working_archive_destination_hashes_postrun"] == "not_collected"
    assert finalized["cdn_index_destination_hashes_postrun"] == "not_collected"


def test_copy_working_archives_is_passive_only_and_checks_disk_before_writes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    module = _module()
    with pytest.raises(module.TraceError, match="passive-real"):
        module.run_container(tmp_path / "no-action", "real", None, copy_working_archives=True)
    source = tmp_path / "client"
    data_dir = source / "Data" / "data"
    (source / "Data" / "config").mkdir(mode=0o700, parents=True)
    (source / "Data" / "indices").mkdir(mode=0o700)
    data_dir.mkdir(mode=0o700)
    for name in module.DATA_ARCHIVES:
        (data_dir / name).write_bytes(b"archive")
    for index in range(16):
        (data_dir / f"{index:010x}.idx").write_bytes(b"idx")
    output = tmp_path / "low-space"
    output.mkdir(mode=0o700)
    module.prepare_runtime(output)
    monkeypatch.setattr(module.shutil, "disk_usage", lambda path: SimpleNamespace(free=0))
    with pytest.raises(module.TraceError, match="disk space"):
        module.prepare_real_data_view(output, source, copy_working_archives=True)
    assert not (output / "runtime" / "Data").exists()


def test_cdn_index_copy_validation_is_strict_and_metadata_change_is_not_stable(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    module = _module()
    assert module.CDN_INDEX_MAX_BYTES == 128 * 1024 * 1024
    assert module.CDN_INDEX_TOTAL_MAX_BYTES == 2 * 1024 * 1024 * 1024
    source = tmp_path / "client"
    data_dir = source / "Data" / "data"
    (source / "Data" / "config").mkdir(mode=0o700, parents=True)
    (source / "Data" / "indices").mkdir(mode=0o700)
    data_dir.mkdir(mode=0o700)
    for name in module.DATA_ARCHIVES:
        (data_dir / name).write_bytes(b"archive")
    for index in range(16):
        (data_dir / f"{index:010x}.idx").write_bytes(b"idx")
    (source / "Data" / "indices" / "not-an-index").write_bytes(b"x")
    output = tmp_path / "invalid-index-name"
    output.mkdir(mode=0o700)
    module.prepare_runtime(output)
    with pytest.raises(module.TraceError, match="unexpected entry"):
        module.prepare_real_data_view(output, source, copy_working_archives=True)
    assert not (output / "runtime" / "Data").exists()

    (source / "Data" / "indices" / "not-an-index").unlink()
    valid = source / "Data" / "indices" / ("b" * 32 + ".index")
    valid.write_bytes(b"012")
    monkeypatch.setattr(module, "CDN_INDEX_MAX_BYTES", 2)
    output_oversize = tmp_path / "invalid-index-size"
    output_oversize.mkdir(mode=0o700)
    module.prepare_runtime(output_oversize)
    with pytest.raises(module.TraceError, match="bounded size"):
        module.prepare_real_data_view(output_oversize, source, copy_working_archives=True)
    assert not (output_oversize / "runtime" / "Data").exists()
    assert not module._metadata_stable({"bytes": 1, "uid": 1, "gid": 1, "mode": 0o644, "dev": 1, "ino": 2, "nlink": 1, "mtime_ns": 3, "ctime_ns": 4},
                                       {"bytes": 1, "uid": 1, "gid": 1, "mode": 0o644, "dev": 1, "ino": 2, "nlink": 1, "mtime_ns": 3, "ctime_ns": 5})


def test_passive_store_observation_is_not_constructor_or_hardware_trap_proof() -> None:
    module = _module()
    protocol = "\n".join([
        '{"type":"launch","fixed_child":true,"target":"WowB.exe","arguments":"-d3d11 -config BetaTrace.wtf"}',
        '{"type":"scan","complete":true,"unique":true}',
        '{"type":"final","mode":"passive-real","real_alive":false,"target_exit_seen":true,"target_exit_code":0,"store_observed":true,"crypto_writes":false,"watch_armed":false,"initializer_construction_proven":false,"authentication_claim":false,"counts":{"watch_traps":0,"events":0},"proof":{"hardware_traps":true,"construction_inferred":false}}',
    ])
    report = module.make_report({}, {"protocol": protocol, "output_truncated": False, "exit_status": 0}, "passive-real")
    assert report["mode"] == "passive-real"
    assert report["status"] == "partial"
    assert report["detector_only"] is True
    assert report["store_observed"] is True
    assert report["crypto_writes"] is False
    assert report["initializer_construction_proven"] is False
    assert report["authentication_claim"] is False
    assert report["process"]["counts"]["watch_traps"] == 0
    assert report["process"]["proof"]["hardware_traps"] is True


def test_display_capture_is_passive_only_and_writes_private_fixed_png(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    module = _module()
    with pytest.raises(module.TraceError, match="passive-real"):
        module.validate_capture_request("real", True)
    with pytest.raises(module.TraceError, match="passive-real"):
        module.validate_capture_request("synthetic", True)

    commands: list[list[str]] = []
    png = (
        b"\x89PNG\r\n\x1a\n" + (13).to_bytes(4, "big") + b"IHDR"
        + (1280).to_bytes(4, "big") + (720).to_bytes(4, "big") + b"\x08\x02\x00\x00\x00" + b"\x00\x00\x00\x00"
    )

    def fake_run(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[bytes]:
        commands.append(command)
        Path(command[-1]).write_bytes(png)
        return subprocess.CompletedProcess(command, 0, stdout=b"", stderr=b"")

    monkeypatch.setattr(module.shutil, "which", lambda name: "/usr/bin/ffmpeg" if name == "ffmpeg" else None)
    monkeypatch.setattr(module.subprocess, "run", fake_run)
    fake_xvfb = SimpleNamespace(
        process=SimpleNamespace(poll=lambda: None), display=7, auth_path=tmp_path / ".xauthority",
    )
    result = module._capture_display_frame(tmp_path, fake_xvfb, 0.0, 20)
    assert result["status"] == "captured"
    target = tmp_path / "screenshots" / "progress-20.png"
    assert target.is_file() and target.stat().st_size == len(png)
    assert target.stat().st_mode & 0o777 == 0o600
    assert (tmp_path / "screenshots").stat().st_mode & 0o777 == 0o700
    assert commands and commands[0][1:6] == ["-hide_banner", "-loglevel", "error", "-nostdin", "-n"]
    assert "-f" in commands[0] and "x11grab" in commands[0]
    assert "-i" in commands[0] and ":7" in commands[0]


def test_exception_dispatch_cap_helper_is_deterministic(tmp_path: Path) -> None:
    source = SOURCE.read_text()
    function = re.search(
        r"static int exception_dispatch_limit_reached\(unsigned int dispatched\) \{.*?\n\}",
        source,
        re.S,
    )
    assert function is not None
    harness = (
        "#include <assert.h>\n"
        "#define EXCEPTION_DISPATCH_LIMIT 4096u\n"
        + function.group(0)
        + "\nint main(void) {\n"
        "assert(!exception_dispatch_limit_reached(0u));\n"
        "assert(!exception_dispatch_limit_reached(4095u));\n"
        "assert(exception_dispatch_limit_reached(4096u));\n"
        "return 0; }\n"
    )
    c_file = tmp_path / "exception_cap.c"
    binary = tmp_path / "exception_cap"
    c_file.write_text(harness)
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", str(c_file), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)


def test_exception_summary_is_bounded_and_does_not_change_constructor_claim() -> None:
    module = _module()
    samples = [
        {
            "code": "0x406d1388",
            "instruction_ip": "0x10001000",
            "thread_id": 7,
            "first_chance": True,
            "classification": "thread_name_notify",
            "module": "WowB.exe",
            "module_rva": "0x1000",
        }
    ]
    final = {
        "type": "final",
        "target_exit_seen": False,
        "complete_unique_store": False,
        "initializer_construction_proven": False,
        "authentication_claim": False,
        "exception_dispatches": 4096,
        "exception_dispatch_cap_exceeded": True,
        "exception_samples_dropped": 4080,
        "exception_code_counts": [{"code": "0x80000003", "count": 4080}] * 8,
        "exception_samples": samples * 32,
    }
    report = module.make_report(
        {}, {"protocol": json.dumps(final), "output_truncated": False, "exit_status": 0}, "real"
    )
    assert len(report["exceptions"]) == module.TRACE_EXCEPTION_SAMPLE_CAP
    assert len(report["process"]["exception_code_counts"]) == module.TRACE_EXCEPTION_CODE_CAP
    assert report["process"]["exception_samples_dropped"] == 4080
    assert report["initializer_construction_proven"] is False
    assert report["authentication_claim"] is False


def test_event_cap_is_partial_even_when_protocol_contains_65_events() -> None:
    module = _module()
    events = [json.dumps({"type": "event", "access": "read", "stack": ["0x1"]}) for _ in range(65)]
    final = json.dumps({
        "type": "final", "target_exit_seen": True, "target_exit_code": 0,
        "complete_unique_store": True, "executable_pe_x64_verified": True,
        "identity": {"owned_process_handle": True, "start_time_double_checked": True},
        "arm_errors": 0, "debugger_error": True, "unexpected_exceptions": 0,
        "unclassified_events": 0, "deadline_expired": False,
        "event_cap_exceeded": True, "events": 64, "watch_traps": 64,
        "synthetic_window_complete": True,
    })
    report = module.make_report({}, {"protocol": "\n".join(events + [final]), "output_truncated": False, "exit_status": 0}, "synthetic")
    assert report["status"] == "partial"
    assert len(report["events"]) == 64


def test_client_log_collection_rejects_symlink_and_oversize_without_unbounded_reads(tmp_path: Path) -> None:
    module = _module()
    game = tmp_path / "game"
    (game / "Logs").mkdir(mode=0o700, parents=True)
    (game / "Errors").mkdir(mode=0o700)
    (game / "Logs" / "gx.log").write_bytes(b"x" * (128 * 1024 + 1))
    (game / "Logs" / "General.log").symlink_to("/etc/passwd")
    result = module._collect_client_logs(tmp_path)
    statuses = {item["path"]: item["status"] for item in result["files"]}
    assert statuses["Logs/gx.log"] == "rejected"
    assert statuses["Logs/General.log"] == "rejected"
    source = SCRIPT.read_text()
    assert "os.O_NOFOLLOW" in source
    assert "os.fstat(fd)" in source
    assert "source.read_bytes()" not in source


def test_two_report_authorities_are_distinct_and_frozen_host_manifest_is_42_files() -> None:
    module = _module()
    assert module.LAB_DATA_REPORT.name == "lab-data-report.json"
    assert module.COMPLETION_REPORT.name == "manifest-completion-report.json"
    assert module.EXPECTED_LAB_DATA_REPORT_SHA256 == "61e98c21e5e4c35899881d2dd4812a348295cbeb3473ab2bd29a850c30293dea"
    assert module.EXPECTED_COMPLETION_REPORT_SHA256 == "810cb5bda4921e0709a3d5ea037c90a1bd5b8044396c9921c9b4fe6a2c2d2ba6"
    _, selected = module._manifest_files(module.DEFAULT_CLIENT)
    assert len(selected) == 42
    assert len({item["path"] for item in selected}) == 42


def test_real_report_never_claims_launch_without_launch_protocol() -> None:
    module = _module()
    run = {"protocol": "xserver_status=missing\n", "output_truncated": False, "exit_status": 126}
    report = module.make_report({}, run, "real")
    assert report["real_wow_launched"] is False
    assert report["real_launch_confirmation"] is False


def test_private_lab_config_sets_installed_locale_without_accounts() -> None:
    source = SCRIPT.read_text()
    assert 'SET textLocale enUS\\nSET audioLocale enUS\\n' in source
    assert 'SET accessibilityScreenNarrationEnabled 0\\n' in source
    assert 'SET rememberAccount 0\\n' in source
    assert 'SET autoComplete 0\\n' in source


def test_real_view_binds_active_build_info_separately_from_42_dependencies() -> None:
    source = SCRIPT.read_text()
    assert 'os.symlink("/source/.build.info", game / ".build.info")' in source
    assert '5f99a2d48e442a51d2b9c2ea4dae860240476499b3726640e783f7cef5b30626' in source
    assert '"build_info_before": build_info_metadata' in source
    assert 'and source_metadata["build_info_unchanged"]' in source


def test_file_diagnostic_keeps_only_known_data_path_class() -> None:
    module = _module()
    key = module.BUILD_CONFIG_KEY
    for root, expected in ((r"Z:\lab\game\Data", "game-data"),
                           (r"Z:\lab\game\..\Data", "parent-data"),
                           (r"Z:\source\Data", "source-data")):
        line, _, _ = module._sanitize_build_config_line(
            f'CreateFileW "{root}\\config\\05\\21\\{key}" access=80000000')
        assert f"path_class={expected}" in line
        assert root not in line


def test_real_parent_data_view_preserves_readonly_source_and_private_coordination() -> None:
    source = SCRIPT.read_text()
    assert 'os.symlink("/lab/Data", game / "Data")' in source
    assert 'runtime_data = output / "runtime" / "Data"' in source
    assert 'os.symlink("/source/Data/config", runtime_data / "config")' in source
    assert 'src={source},dst=/source,readonly' in source


def test_host_xvfb_provenance_is_bounded_and_owned() -> None:
    module = _module()
    source = SCRIPT.read_text()
    assert "-displayfd" in source
    assert "pass_fds=(display_write,)" in source
    assert "-nolisten\", \"tcp" in source
    assert "XVFB_START_SECONDS = 10" in source
    assert "XVFB_STDERR_CAP = 64 * 1024" in source
    assert "socket_preexisting" in source and "socket_reuse" in source
    assert "socket_path.name in existing_sockets" in source
    assert 'number = str(display_number).encode("ascii")' in source
    run = {
        "protocol": "xserver_status=host-ready\n",
        "output_truncated": False,
        "exit_status": 126,
        "host_xvfb": {
            "implementation": "host-Xvfb", "display": ":7",
            "socket_preexisting": False, "socket_reuse": False,
            "tcp_listen": False, "stderr_bytes": 0,
        },
    }
    report = module.make_report({}, run, "real")
    assert report["gui"]["display"] == ":7"
    assert report["gui"]["provenance"]["socket_reuse"] is False


def test_bootstrap_parser_records_only_layout_and_version() -> None:
    module = _module()
    bootstrap = module.parse_bootstrap(
        "bootstrap_wine_version=wine-11.0\n"
        "bootstrap_wineboot_rc=0\nbootstrap_drive_c=true\n"
        "bootstrap_system32=true\nbootstrap_dos_c=true\nbootstrap_dos_z=true\n"
    )
    assert bootstrap == {
        "wine_version": "wine-11.0",
        "wineboot_rc": 0,
        "drive_c": True,
        "system32": True,
        "dos_c": True,
        "dos_z": True,
    }


def test_synthetic_fixture_exercises_cross_chunk_boundary_and_stable_reads() -> None:
    source = SOURCE.read_text()
    assert "65536u - 200u" in source
    assert "SCAN_OVERLAP (TABLE_BYTES - 1u)" in source
    assert "offset = begin + amount" in source
    assert "first_aligned_candidate(base + begin)" in source and "local += 4" in source
    assert source.count("read_process_exact(watched_address") >= 2
    assert "query_errors" in source and "address_overflows" in source
    assert "PAGE_GUARD" in source
    assert "VirtualQueryEx(state.process, watched_region.BaseAddress" in source
    assert "initial_counts.keys == ROWS" in source


def test_stack_and_symbol_metadata_are_real_and_synthetic_window_is_scoped() -> None:
    source = SOURCE.read_text()
    assert "SymLoadModuleExW" in source
    assert "load_dll_debug_symbols" in source
    assert "CONTEXT stack_context = *context" in source
    assert "if (finish_synthetic_window) disarm_all_threads();" in source
    assert "state.synthetic && strcmp(access, \"write\")" in source
    assert "state.synthetic && strcmp(access, \"read\")" in source


def test_missing_stack_cannot_become_synthetic_observed() -> None:
    module = _module()
    protocol = "\n".join([
        '{"type":"scan","complete":true,"unique":true}',
        '{"type":"event","access":"read","stack":[]}',
        '{"type":"event","access":"write","stack":[]}',
        '{"type":"event","access":"read","stack":[]}',
        '{"type":"final","target_exit_seen":true,"target_exit_code":0,',
        '"complete_unique_store":true,"executable_pe_x64_verified":true,',
        '"identity":{"owned_process_handle":true,"start_time_double_checked":true},',
        '"arm_errors":0,"debugger_error":false,"unexpected_exceptions":0,',
        '"unclassified_events":0,"deadline_expired":false,"synthetic_window_complete":true}',
    ])
    report = module.make_report({}, {"protocol": protocol, "output_truncated": False, "exit_status": 0}, "synthetic")
    assert report["status"] == "partial"


def test_production_alignment_keeps_a_cross_chunk_store(tmp_path: Path) -> None:
    source = SOURCE.read_text()
    function = re.search(r"static SIZE_T first_aligned_candidate\(uintptr_t chunk_base\) \{.*?\n\}", source, re.S)
    assert function is not None
    harness = (
        "#include <stdint.h>\n#include <stddef.h>\n#include <assert.h>\n"
        "typedef size_t SIZE_T;\n" + function.group(0) +
        "\nint main(void) {\n"
        "uintptr_t second = 65536u - 479u;\n"
        "uintptr_t wanted = 65536u - 200u;\n"
        "size_t first = first_aligned_candidate(second);\n"
        "assert(first == 3u);\n"
        "assert(wanted >= second + first);\n"
        "assert((wanted - second - first) % 4u == 0);\n"
        "assert(wanted - second + 480u <= 65536u);\n"
        "assert(first_aligned_candidate(0u) == 0u);\n"
        "return 0; }\n"
    )
    c_file = tmp_path / "alignment.c"
    binary = tmp_path / "alignment"
    c_file.write_text(harness)
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", str(c_file), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)


def test_protocol_requires_complete_unique_store_and_bounded_events() -> None:
    module = _module()
    messages, final = module.parse_protocol(
        '{"type":"scan","complete":false,"valid_blocks":1}\n'
        '{"type":"event","access":"write"}\n'
        '{"type":"final","complete_unique_store":false,'
        '"target_exit_seen":true,"target_exit_code":0}\n'
    )
    assert len(messages) == 2
    assert final and final["complete_unique_store"] is False
    assert module.TABLE_BYTES == 480
    assert module.TRACE_EVENT_CAP == 64
    with pytest.raises(module.TraceError, match="duplicate final"):
        module.parse_protocol('{"type":"final"}\n{"type":"final"}\n')


def test_failure_report_never_makes_auth_claim(tmp_path: Path) -> None:
    module = _module()
    output = tmp_path / "trace"
    output.mkdir(mode=0o700)
    report_path = module.report_failure(output, RuntimeError("synthetic blocker"), "synthetic")
    report = json.loads(report_path.read_text())
    assert report["status"] == "blocked"
    assert report["authentication_claim"] is False
    assert report["real_wow_launched"] is False
    assert report_path.stat().st_mode & 0o777 == 0o600
    assert output.stat().st_mode & 0o777 == 0o700
