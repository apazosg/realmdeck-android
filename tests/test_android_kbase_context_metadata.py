"""Host-only tests for the bounded own-context kbase metadata control."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-panvk/KbaseContextMetadata.c"
RUN = "PVK-0123456789abcdef0123456789abcdef"
CWD = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/" + RUN


def _harness(tmp_path: Path) -> Path:
    compiler = shutil.which("cc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    source = tmp_path / "context_harness.c"
    source.write_text(
        f"""
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define KBASE_CONTEXT_TESTING 1
#include "{SOURCE}"

static int mode;
static int ioctl_calls;
static int fstat_calls;
static int close_calls;
static int clock_calls;

static int mock_lstat(const char *path, struct stat *s) {{
    if (strcmp(path, "/dev/mali0")) return -1;
    memset(s, 0, sizeof(*s)); s->st_mode = S_IFCHR; s->st_rdev = makedev(10, 113); s->st_dev = 9; s->st_ino = 10; return 0;
}}
static int mock_open(const char *path, int flags) {{
    return strcmp(path, "/dev/mali0") == 0 && flags == (O_RDWR | O_CLOEXEC | O_NOFOLLOW) ? 77 : -1;
}}
static int mock_fstat(int fd, struct stat *s) {{
    if (fd != 77) return -1;
    memset(s, 0, sizeof(*s)); s->st_mode = S_IFCHR; s->st_rdev = makedev(10, mode == 7 && fstat_calls++ >= 1 ? 114 : 113); s->st_dev = 9; s->st_ino = 10; return 0;
}}
static int mock_close(int fd) {{ ++close_calls; return fd == 77 && mode != 8 ? 0 : -1; }}
static uid_t mock_uid(void) {{ return 10330; }}
static char *mock_cwd(char *out, size_t n) {{ if (n <= strlen("{CWD}")) return NULL; strcpy(out, "{CWD}"); return out; }}
static uint64_t mock_clock(int *ok) {{ static uint64_t now = 100; *ok = 1; ++clock_calls; now += mode == 9 && clock_calls > 4 ? 6000000000ULL : 1; return now; }}

static int mock_ioctl(int fd, unsigned long request, void *arg) {{
    if (fd != 77) return -1;
    ++ioctl_calls;
    if (ioctl_calls == 1 && request == KBASE_IOCTL_VERSION_CHECK_CSF) {{
        struct kbase_ioctl_version_check *v = arg;
        if (mode == 1) {{ v->major = 1; v->minor = 21; return 0; }}
        if (mode == 2) {{ v->major = 2; v->minor = 0; return 0; }}
        if (mode == 3) {{ errno = EPERM; return -1; }}
        if (mode == 4) return 1;
        v->major = 1; v->minor = 18; return 0;
    }}
    if (ioctl_calls == 2 && request == KBASE_IOCTL_SET_FLAGS) {{
        struct kbase_ioctl_set_flags *f = arg;
        if (f->create_flags != 0) return -1;
        if (mode == 5) {{ errno = EPERM; return -1; }}
        if (mode == 6) return 1;
        return 0;
    }}
    if (ioctl_calls >= 3 && request == KBASE_IOCTL_GET_GPUPROPS) {{
        struct kbase_ioctl_get_gpuprops *p = arg;
        if (p->buffer == 0 && p->size == 0 && p->flags == 0) {{
            if (mode == 10) {{ errno = EPERM; return -1; }}
            if (mode == 11) return 0;
            if (mode == 12) return 16385;
            return 8;
        }}
        if (p->size != 8) return -1;
        if (mode == 13) {{ ((unsigned char *)(uintptr_t)p->buffer)[0] = 0; return 8; }}
        if (mode == 14) {{ ((unsigned char *)(uintptr_t)p->buffer)[8] = 0; return 8; }}
        memcpy((void *)(uintptr_t)p->buffer, "\\xde\\x00\\x00\\x00\\x78\\x56\\x34\\x12", 8);
        return 8;
    }}
    errno = ENOTTY; return -1;
}}

static int run(int selected_mode) {{
    struct kbase_probe p; char cwd[512] = {{0}};
    mode = selected_mode; ioctl_calls = 0; fstat_calls = 0; close_calls = 0; clock_calls = 0;
    kbase_context_test_hooks.lstat_fn = mock_lstat; kbase_context_test_hooks.open_fn = mock_open;
    kbase_context_test_hooks.fstat_fn = mock_fstat; kbase_context_test_hooks.ioctl_fn = mock_ioctl;
    kbase_context_test_hooks.close_fn = mock_close; kbase_context_test_hooks.getuid_fn = mock_uid;
    kbase_context_test_hooks.getcwd_fn = mock_cwd; kbase_context_test_hooks.clock_fn = mock_clock;
    kbase_init_probe(&p); kbase_run_probe("{RUN}", &p, cwd, sizeof(cwd));
    printf("%s %d %d %d %d %d %d\\n", p.status, ioctl_calls, p.context_called, p.context_ret, p.flags_inited, p.close_called, p.valid_observation);
    return 0;
}}

int main(int argc, char **argv) {{
    struct kbase_probe p; char out[8192]; char cwd[512] = "{CWD}";
    if (argc != 2) return 90;
    if (!strcmp(argv[1], "json")) {{
        kbase_init_probe(&p); p.status = "PROPERTIES_OBSERVED"; p.valid_observation = 1; p.native_success = 1;
        p.started_ns = 1; p.finished_ns = 2; p.identity_before = p.identity_after = 1; p.close_called = 1;
        p.close_ret = 0; p.version_called = 1; p.version_ret = 0; p.version_errno = 0; p.version_major = 1; p.version_minor = 18;
        p.context_called = 1; p.context_ret = 0; p.context_errno = 0; p.flags_inited = 1; p.properties_valid = 1;
        p.properties_probe_called = 1; p.properties_probe_ret = 8; p.properties_fill_called = 1; p.properties_fill_ret = 8;
        p.properties.present[3] = 1; p.properties.value[3] = 0xa8670000U; p.properties_returned = 8;
        return kbase_build_json(&p, "{RUN}", 7, 10330, 7, 7, cwd, out, sizeof(out)) && fputs(out, stdout) != EOF ? 0 : 1;
    }}
    return run(atoi(argv[1]));
}}
""",
        encoding="utf-8",
    )
    binary = tmp_path / "context_harness"
    built = subprocess.run([compiler, "-D_GNU_SOURCE", "-std=c11", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(binary)], capture_output=True, text=True)
    assert built.returncode == 0, built.stdout + built.stderr
    return binary


def _run(harness: Path, mode: int) -> list[str]:
    return subprocess.check_output([str(harness), str(mode)], text=True).split()


def test_context_order_flags_once_same_fd_and_close(tmp_path: Path) -> None:
    result = _run(_harness(tmp_path), 0)
    assert result[:5] == ["PROPERTIES_OBSERVED", "4", "1", "0", "1"]
    assert result[5:] == ["1", "1"]


@pytest.mark.parametrize("mode", [1, 2])
def test_version_mismatch_stops_before_set_flags(tmp_path: Path, mode: int) -> None:
    result = _run(_harness(tmp_path), mode)
    assert result[:5] == ["VERSION_MISMATCH", "1", "0", "0", "0"]


@pytest.mark.parametrize("mode", [3, 4, 5, 6, 10, 11, 12, 13, 14, 7, 8, 9])
def test_control_failures_stop_or_become_unknown_and_close(tmp_path: Path, mode: int) -> None:
    result = _run(_harness(tmp_path), mode)
    assert result[5] == "1"
    assert result[0] in {"VERSION_FAILED", "FLAGS_BLOCKED", "PROPERTIES_BLOCKED", "UNKNOWN"}


def test_json_encoder_has_context_and_false_execution_flags(tmp_path: Path) -> None:
    document = json.loads(subprocess.check_output([str(_harness(tmp_path)), "json"], text=True))
    assert document["schema"] == "wowforever.android-kbase-context-metadata/version1"
    assert document["context"] == {"called": True, "ioctl": "0x40048001", "createFlags": 0, "ret": 0, "errno": 0}
    assert document["flagsInited"] is True
    for name in ("gpuWorkloadVerified", "driverExecution", "deviceVerified", "hardwareVerified"):
        assert document[name] is False
