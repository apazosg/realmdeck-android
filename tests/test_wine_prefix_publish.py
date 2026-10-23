from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-process/wine_prefix_publish.c"


def _header(path: Path) -> None:
    path.write_text(
        """#ifndef WINE_PREFIX_PUBLISH_MANIFEST_H
#define WINE_PREFIX_PUBLISH_MANIFEST_H
#include <stddef.h>
#include <stdint.h>
enum wpp_member_kind { WPP_DIRECTORY = 1, WPP_REGULAR = 2 };
struct wpp_member { const char *path; uint64_t bytes; uint32_t mode; const char *sha256; unsigned kind; };
struct wpp_link { const char *path; const char *target; unsigned target_kind; };
static const struct wpp_member wpp_members[] = {
 {".wine", 0ULL, 448U, "", WPP_DIRECTORY},
 {".wine/dosdevices", 0ULL, 448U, "", WPP_DIRECTORY},
 {".wine/drive_c", 0ULL, 448U, "", WPP_DIRECTORY},
 {".wine/drive_c/windows", 0ULL, 448U, "", WPP_DIRECTORY},
 {".wine/drive_c/windows/system32", 0ULL, 448U, "", WPP_DIRECTORY},
 {".wine/drive_c/windows/system32/winsxs", 0ULL, 511U, "", WPP_DIRECTORY},
 {".wine/drive_c/windows/system32/winsxs/template.txt", 4ULL, 420U, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", WPP_REGULAR},
 {".wine/system.reg", 8ULL, 384U, "872491a30d60d598962de6e7b834ab76b2aa65fbab102c6ebaaae6acdc238822", WPP_REGULAR},
};
static const size_t wpp_member_count = sizeof(wpp_members) / sizeof(wpp_members[0]);
static const struct wpp_link wpp_links[] = {
 {".wine/dosdevices/a:", "<probe_root>", 3U},
 {".wine/dosdevices/c:", "../drive_c", 1U},
 {".wine/dosdevices/z:", "<imagefs_root>", 2U},
};
static const size_t wpp_link_count = sizeof(wpp_links) / sizeof(wpp_links[0]);
#endif
""",
        encoding="ascii",
    )


def _fixture(tmp_path: Path) -> tuple[Path, Path, Path, Path, str]:
    ws = "WS-0123456789abcdef0123456789abcdef"
    run = tmp_path / "files/forever/wine-controls" / ws
    stage = run / "prefix-stage/.wine"
    (stage / "dosdevices").mkdir(parents=True)
    (stage / "drive_c/windows/system32/winsxs").mkdir(parents=True)
    (stage / "drive_c/windows/system32/winsxs/template.txt").write_bytes(b"test")
    (stage / "system.reg").write_bytes(b"registry")
    for path in [stage, stage / "dosdevices", stage / "drive_c", stage / "drive_c/windows", stage / "drive_c/windows/system32"]:
        path.chmod(0o700)
    (stage / "drive_c/windows/system32/winsxs").chmod(0o777)
    (stage / "drive_c/windows/system32/winsxs/template.txt").chmod(0o644)
    (stage / "system.reg").chmod(0o600)
    probe = run / "probe-root"
    imagefs = tmp_path / "files/imagefs_shared"
    imagefs.parent.mkdir(parents=True, exist_ok=True)
    probe.mkdir(mode=0o700)
    imagefs.mkdir(mode=0o700)
    (stage / "dosdevices/a:").symlink_to(probe)
    (stage / "dosdevices/c:").symlink_to("../drive_c")
    (stage / "dosdevices/z:").symlink_to(imagefs)
    (run / "prefix").mkdir(mode=0o700)
    for path in [run, run / "prefix-stage", run / "prefix"]:
        path.chmod(0o700)
    return run, stage, imagefs, probe, ws


@pytest.fixture
def helper(tmp_path: Path) -> Path:
    header = tmp_path / "wine_prefix_publish_manifest.h"
    _header(header)
    binary = tmp_path / "prefix-publish-host"
    result = subprocess.run(
        ["cc", "-std=c11", "-O0", "-Wall", "-Wextra", "-Werror", "-DWINE_PREFIX_PUBLISH_HOST_TEST", f"-I{tmp_path}", "-o", str(binary), str(SOURCE)],
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr
    return binary


def _run(binary: Path, run: Path, stage: Path, imagefs: Path, probe: Path, ws: str, *, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    process_env = os.environ.copy()
    if env:
        process_env.update(env)
    return subprocess.run(
        [str(binary), "--run", ws, "--stage", str(stage), "--imagefs", str(imagefs), "--probe-root", str(probe)],
        check=False,
        capture_output=True,
        text=True,
        env=process_env,
    )


def test_host_fixed_fixture_publishes_once_and_never_replaces(helper: Path, tmp_path: Path):
    run, stage, imagefs, probe, ws = _fixture(tmp_path)
    result = _run(helper, run, stage, imagefs, probe, ws)
    assert result.returncode == 0, result.stderr
    assert (run / "prefix/.wine/system.reg").read_bytes() == b"registry"
    assert not stage.exists()
    sentinel = run / "prefix/.wine/sentinel"
    sentinel.write_text("foreign")
    run2, stage2, imagefs2, probe2, ws2 = _fixture(tmp_path / "second")
    assert _run(helper, run2, stage2, imagefs2, probe2, ws2).returncode == 0
    # A second direct attempt against an already published destination is rejected.
    assert _run(helper, run, run / "prefix-stage/.wine", imagefs, probe, ws).returncode != 0
    assert sentinel.read_text() == "foreign"


def test_host_publisher_rejects_wrong_bytes_symlinked_root_and_foreign_destination(helper: Path, tmp_path: Path):
    run, stage, imagefs, probe, ws = _fixture(tmp_path)
    (stage / "system.reg").write_bytes(b"tampered!")
    result = _run(helper, run, stage, imagefs, probe, ws)
    assert result.returncode != 0
    assert not (run / "prefix/.wine").exists()
    run2, stage2, imagefs2, probe2, ws2 = _fixture(tmp_path / "symlink")
    foreign = tmp_path / "foreign"
    foreign.mkdir()
    imagefs2.rmdir()
    imagefs2.symlink_to(foreign)
    assert _run(helper, run2, stage2, imagefs2, probe2, ws2).returncode != 0
    assert not (foreign / "prefix").exists()


def test_host_publisher_rejects_noncanonical_ws_and_wrong_probe_layout(helper: Path, tmp_path: Path):
    run, stage, imagefs, probe, ws = _fixture(tmp_path)
    assert _run(helper, run, stage, imagefs, probe, ws.removeprefix("WS-")).returncode != 0
    assert _run(helper, run, stage, imagefs, tmp_path / "wrong-probe", ws).returncode != 0
    assert _run(helper, run, stage, imagefs, probe, "WS-0123456789ABCDEF0123456789abcdef").returncode != 0


def test_host_publisher_rejects_missing_link_and_stage_parent_swap(helper: Path, tmp_path: Path):
    run, stage, imagefs, probe, ws = _fixture(tmp_path)
    (stage / "dosdevices/a:").unlink()
    assert _run(helper, run, stage, imagefs, probe, ws).returncode != 0
    run2, stage2, imagefs2, probe2, ws2 = _fixture(tmp_path / "swap")
    result = _run(helper, run2, stage2, imagefs2, probe2, ws2, env={"WPP_TEST_SWAP_STAGE_PARENT": "1"})
    assert result.returncode != 0
    assert not (run2 / "prefix/.wine").exists()


def test_host_publisher_rejects_world_writable_stage_root(helper: Path, tmp_path: Path):
    run, stage, imagefs, probe, ws = _fixture(tmp_path)
    stage.chmod(0o777)
    result = _run(helper, run, stage, imagefs, probe, ws)
    assert result.returncode != 0
    assert not (run / "prefix/.wine").exists()
