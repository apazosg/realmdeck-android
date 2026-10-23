"""Offline contract tests for the bounded virgl source-build recipe."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
BUILD_DIR = ROOT / "scripts/arm64-prototype/controls/virgl-build"
sys.path.insert(0, str(BUILD_DIR))
import build_diagnostic as recipe  # noqa: E402
import pkg_config  # noqa: E402


def test_recipe_uses_the_pinned_source_and_external_workspace() -> None:
    assert recipe.SOURCE_BYTES == 1_255_383
    assert recipe.SOURCE_SHA256 == "56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce"
    assert not recipe.WORKSPACE.resolve(strict=False).is_relative_to(ROOT)
    assert recipe.WORKSPACE.name == "vtest-android-build-v6-final"


def test_diagnostic_profile_does_not_enable_functional_egl_fix() -> None:
    options = recipe._meson_options()
    assert options["platforms"] == ["egl"]
    assert options["minigbm_allocation"] is False
    assert options["venus"] is False
    assert options["video"] is False
    assert options["tests"] is False
    assert options["fuzzer"] is False
    assert options["tracing"] == "none"
    patch = recipe.PATCH.read_text(encoding="utf-8")
    assert "WFVTEST_DIAG" in patch
    added_lines = "\n".join(line for line in patch.splitlines() if line.startswith("+") and not line.startswith("+++"))
    assert "eglGetPlatformDisplay" not in added_lines
    assert "client.has_surfaceless" not in patch
    assert "client.has_gles" not in patch
    assert "-      else\n+      else {" in patch
    assert "+      }\n #endif" in patch
    assert "-U__ANDROID__" not in (BUILD_DIR / "build_diagnostic.py").read_text(encoding="utf-8")
    assert "-Du64=uint64_t" not in (BUILD_DIR / "build_diagnostic.py").read_text(encoding="utf-8")
    assert "GBM_HEADER =" not in (BUILD_DIR / "build_diagnostic.py").read_text(encoding="utf-8")
    rpath_patch = (BUILD_DIR / "patches/virglrenderer-1.3.0-wfvtest-install-rpath-v1.patch").read_text(encoding="utf-8")
    assert "install_rpath : '$ORIGIN'" in rpath_patch
    assert "install_rpath : '$ORIGIN/../lib'" in rpath_patch


def test_android_default_profile_is_explicit_and_uses_a_separate_workspace() -> None:
    profile = recipe.PROFILES["android-default"]
    assert profile.workspace == recipe.ANDROID_DEFAULT_WORKSPACE
    assert recipe.ANDROID_DEFAULT_V1_WORKSPACE.name == "vtest-android-default-v1"
    assert profile.workspace.name == "vtest-android-default-v2"
    assert profile.functional_egl_fix is True
    assert profile.compatibility_defines == ("WFVTEST_ANDROID_DEFAULT=1",)
    assert [item[1] for item in recipe.ANDROID_DEFAULT_PATCHES][-1] == "android-default"
    assert recipe.ANDROID_DEFAULT_PATCH.name.endswith("android-default-v2.patch")
    assert [item["id"] for item in recipe._patch_records("android-default")][-1] == "android-default"

    args = recipe._compile_args(profile, profile.workspace / "deps")
    assert args == [f"-I{profile.workspace / 'deps/include'}", "-DWFVTEST_ANDROID_DEFAULT=1"]
    assert "-DWFVTEST_DIAG=1" not in args


def test_ndk_public_api_port_is_android_scoped_and_reproducible() -> None:
    script = (BUILD_DIR / "build_diagnostic.py").read_text(encoding="utf-8")
    patch = "\n".join(path.read_text(encoding="utf-8") for path, _name in recipe.NDK_API_PATCHES)
    assert "#  include <android/log.h>" in patch
    assert "#  include <sys/system_properties.h>" in patch
    assert "__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, \"%s\", message)" in patch
    assert "__system_property_get(key, value)" in patch
    added_lines = "\n".join(line for line in patch.splitlines() if line.startswith("+") and not line.startswith("+++"))
    assert "PROPERTY_VALUE_MAX" not in added_lines
    assert "PROPERTY_KEY_MAX" not in added_lines
    assert "int len = property_get(" not in added_lines
    assert "LOG_PRI(" not in added_lines
    assert "if host_machine.system() == 'android'" in patch
    assert "cc.find_library('log', required : true)" in patch
    assert "c_link_args" not in script
    assert [item["id"] for item in recipe._patch_records()] == [
        "diagnostic",
        "install-rpath",
        "public-soname",
        "public-soname-link",
        "ndk-public-api-root",
        "ndk-public-api-mesa-dependency",
        "ndk-public-api-log-header",
        "ndk-public-api-log-call",
        "ndk-public-api-property-limits",
        "ndk-public-api-property-call",
        "ndk-public-api-dmabuf-header",
        "ndk-public-api-dmabuf-call",
    ]
    assert "--fuzz=0" in script


def test_fresh_patch_application_covers_every_declared_patch(tmp_path: Path) -> None:
    source = recipe._extract_source(tmp_path)
    record = recipe._apply_patch(source, tmp_path / "patch.log")
    declared = [item["id"] for item in recipe._patch_records()]
    assert record["declaredPatchIds"] == declared
    assert [item["id"] for item in record["patches"]] == declared
    assert record["fuzz"] == 0
    assert record["sourceTree"]["count"] > 0
    assert not list(source.rglob("*.rej"))
    assert not list(source.rglob("*.orig"))
    os_misc = (source / "src/mesa/util/os_misc.c").read_text(encoding="utf-8")
    assert "<android/log.h>" in os_misc
    assert "__system_property_get(key, value)" in os_misc


def test_android_default_fresh_patch_application_is_exact_and_scoped(tmp_path: Path) -> None:
    source = recipe._extract_source(tmp_path)
    record = recipe._apply_patch(source, tmp_path / "patch.log", "android-default")
    declared = [item["id"] for item in recipe._patch_records("android-default")]
    assert len(declared) == 13
    assert record["declaredPatchIds"] == declared
    assert record["fuzz"] == 0
    assert not list(source.rglob("*.rej"))
    assert not list(source.rglob("*.orig"))

    winsys = (source / "src/vrend/vrend_winsys_egl.c").read_text(encoding="utf-8")
    assert "defined(WFVTEST_ANDROID_DEFAULT) && defined(__ANDROID__)" in winsys
    assert "const bool android_default = gles && !surfaceless && !gbm;" in winsys
    assert "eglGetDisplay(EGL_DEFAULT_DISPLAY)" in winsys
    assert "if (!success) {\n         EGLint error = eglGetError();" in winsys
    assert "glGetString(GL_VENDOR)" in winsys
    assert "eglDestroySyncKHR(egl->egl_display, egl->signaled_fence)" in winsys
    assert "eglTerminate(egl->egl_display)" in winsys
    assert "EGLint error = eglGetError();" in winsys
    assert "stage=eglMakeCurrent result=0" in winsys
    assert "cleanup.context result=%d" in winsys

    patch = recipe.ANDROID_DEFAULT_PATCH.read_text(encoding="utf-8")
    added = "\n".join(line for line in patch.splitlines() if line.startswith("+") and not line.startswith("+++"))
    assert "eglGetDisplay((EGLNativeDisplayType)egl->gbm->device)" not in added
    assert "EGL_DEFAULT_DISPLAY" in added
    assert "EGL_PLATFORM_GBM_KHR" not in added
    assert "EGL_PLATFORM_SURFACELESS_MESA" not in added
    assert "fake" not in added.lower()


def test_android_default_predicate_preserves_non_android_and_non_default_egl_paths(tmp_path: Path) -> None:
    source = recipe._extract_source(tmp_path)
    recipe._apply_patch(source, tmp_path / "patch.log", "android-default")
    winsys = (source / "src/vrend/vrend_winsys_egl.c").read_text(encoding="utf-8")

    assert winsys.count("defined(WFVTEST_ANDROID_DEFAULT) && defined(__ANDROID__)") >= 3
    assert "const bool android_default = gles && !surfaceless && !gbm;" in winsys
    assert "const bool android_default = false;" in winsys
    assert "if (!gbm && (!surfaceless || !has_egl_base) && !android_default)" in winsys
    assert "eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA" in winsys
    assert "eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR" in winsys
    assert "eglGetDisplay((EGLNativeDisplayType)egl->gbm->device)" in winsys
    assert "eglGetDisplay(display_id)" in winsys
    assert "if (!success) {\n         EGLint error = eglGetError();" in winsys
    assert "eglDestroyContext(egl->egl_display, egl->egl_ctx);" in winsys
    assert "eglDestroySyncKHR(egl->egl_display, egl->signaled_fence);" in winsys
    assert "eglTerminate(egl->egl_display);" in winsys
    assert "extension_bits |=" not in recipe.ANDROID_DEFAULT_PATCH.read_text(encoding="utf-8")
    assert "virgl_fill_caps" not in recipe.ANDROID_DEFAULT_PATCH.read_text(encoding="utf-8")


def test_android_default_display_branch_preprocesses_with_and_without_gbm_allocation(tmp_path: Path) -> None:
    source = recipe._extract_source(tmp_path)
    recipe._apply_patch(source, tmp_path / "patch.log", "android-default")
    winsys = (source / "src/vrend/vrend_winsys_egl.c").read_text(encoding="utf-8")
    start = winsys.index("#if defined(WFVTEST_ANDROID_DEFAULT) && defined(__ANDROID__)")
    end = winsys.index("if (has_egl_base)", start)
    snippet = "int probe(void) {\n" + winsys[start:end] + "return 0;\n}\n"

    def preprocess(*defines: str) -> str:
        result = subprocess.run(
            [str(recipe.CLANG), "-E", "-P", "-x", "c", *defines, "-"],
            input=snippet,
            text=True,
            capture_output=True,
            check=True,
        )
        return result.stdout

    allocation_on = preprocess("-DWFVTEST_ANDROID_DEFAULT=1", "-D__ANDROID__=1", "-DENABLE_GBM=1", "-DENABLE_GBM_ALLOCATION=1")
    allocation_off = preprocess("-DWFVTEST_ANDROID_DEFAULT=1", "-D__ANDROID__=1", "-DENABLE_GBM=1")
    default_call = "egl->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);"
    assert default_call in allocation_on
    assert default_call in allocation_off
    assert "if (virgl_egl_get_display(egl))" in allocation_on
    assert "if (virgl_egl_get_display(egl))" not in allocation_off
    assert "egl->egl_display = eglGetDisplay((EGLNativeDisplayType)egl->gbm->device);" not in snippet


def test_fresh_cache_paths_reject_tampering(tmp_path: Path) -> None:
    source_root = tmp_path / "source"
    source_root.mkdir()
    (source_root / "unexpected").write_text("tampered", encoding="utf-8")
    with pytest.raises(recipe.BuildError, match="fresh build refuses to reuse source cache"):
        recipe._extract_source(tmp_path)
    deps_root = tmp_path / "deps"
    deps_root.mkdir()
    with pytest.raises(recipe.BuildError, match="fresh build refuses to reuse dependency cache"):
        recipe._prepare_dependencies(tmp_path)


def test_android_macro_validation_rejects_false_compiler_predefines() -> None:
    with pytest.raises(recipe.BuildError, match="predefined macro mismatch"):
        recipe._validate_compiler_predefines("#define __ANDROID__ 0\n")
    with pytest.raises(recipe.BuildError, match="detection mismatch"):
        recipe._validate_detect_os_predefines("#define DETECT_OS_LINUX 1\n#define DETECT_OS_UNIX 1\n")


def test_build_environment_does_not_inherit_ambient_compiler_flags(tmp_path: Path) -> None:
    env = recipe._build_environment(tmp_path, tmp_path / "deps", tmp_path / "meson")
    assert not recipe.COMPILER_ENV_KEYS.intersection(env)
    assert env["LC_ALL"] == "C"
    assert env["SOURCE_DATE_EPOCH"] == "0"
    assert env["TMPDIR"] == str(tmp_path / "tmp")


def test_config_record_reports_android_detection_and_egl_gbm_macros(tmp_path: Path) -> None:
    config = tmp_path / "config.h"
    config.write_text(
        "#define ENABLE_GBM 1\n"
        "#define ENABLE_GBM_ALLOCATION 1\n"
        "#define HAVE_EPOXY_EGL_H 1\n"
        "/* #undef HAVE_EPOXY_GLX_H */\n",
        encoding="utf-8",
    )
    record = recipe._config_header_record(config)
    assert record["defines"] == {
        "ENABLE_GBM": "1",
        "ENABLE_GBM_ALLOCATION": "1",
        "HAVE_EPOXY_EGL_H": "1",
        "HAVE_EPOXY_GLX_H": None,
    }
    assert record["androidDetection"] == {
        "compilerPredefined": "__ANDROID__",
        "mesaMacro": "DETECT_OS_ANDROID",
        "configHeader": "not emitted by Meson; supplied by src/mesa/util/detect_os.h",
    }


def test_final_elf_requirements_distinguish_executable_and_library_interpreters(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    details = {
        "bin/virgl_test_server": {"elfClass": 64, "elfData": "LE", "elfMachine": 183, "elfType": "ET_DYN", "rpath": [], "runpath": ["$ORIGIN/../lib"], "ptInterp": "/system/bin/linker64", "soname": None, "needed": recipe.EXPECTED_NEEDED["bin/virgl_test_server"]},
        "lib/libvirglrenderer.so": {"elfClass": 64, "elfData": "LE", "elfMachine": 183, "elfType": "ET_DYN", "rpath": [], "runpath": ["$ORIGIN"], "ptInterp": None, "soname": "libvirglrenderer.so.1", "needed": recipe.EXPECTED_NEEDED["lib/libvirglrenderer.so"]},
    }
    monkeypatch.setattr(recipe, "_elf_details", lambda path: details[path.relative_to(tmp_path).as_posix()])
    audited = recipe._assert_final_elf_requirements(tmp_path, set(details))
    assert audited["bin/virgl_test_server"] == {"rpath": [], "runpath": ["$ORIGIN/../lib"], "ptInterp": "/system/bin/linker64", "soname": None}
    assert audited["lib/libvirglrenderer.so"] == {"rpath": [], "runpath": ["$ORIGIN"], "ptInterp": None, "soname": "libvirglrenderer.so.1"}


def test_final_elf_requirements_reject_architecture_needed_and_paths(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    valid = {"elfClass": 64, "elfData": "LE", "elfMachine": 183, "elfType": "ET_DYN", "rpath": [], "runpath": ["$ORIGIN/../lib"], "ptInterp": "/system/bin/linker64", "soname": None, "needed": recipe.EXPECTED_NEEDED["bin/virgl_test_server"]}
    monkeypatch.setattr(recipe, "_elf_details", lambda _path: {**valid, "elfMachine": 62})
    with pytest.raises(recipe.BuildError, match="ELF identity"):
        recipe._assert_final_elf_requirements(tmp_path, {"bin/virgl_test_server"})
    monkeypatch.setattr(recipe, "_elf_details", lambda _path: {**valid, "needed": ["libc.so"]})
    with pytest.raises(recipe.BuildError, match="DT_NEEDED"):
        recipe._assert_final_elf_requirements(tmp_path, {"bin/virgl_test_server"})
    monkeypatch.setattr(recipe, "_elf_details", lambda _path: {**valid, "runpath": ["/home/host"]})
    with pytest.raises(recipe.BuildError, match="host RPATH"):
        recipe._assert_final_elf_requirements(tmp_path, {"bin/virgl_test_server"})


def test_archive_path_validation_does_not_strip_traversal() -> None:
    assert recipe._safe_rel("./include/header.h") == "include/header.h"
    for value in ("../../escape", "./../escape", "/absolute", "a//b", "a\\b"):
        with pytest.raises(recipe.BuildError):
            recipe._safe_rel(value)


def test_tools_do_not_trust_an_installed_meson_cache(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cache = tmp_path / recipe.MESON_WHEEL_FILENAME
    cache.write_bytes(b"not the approved wheel")
    monkeypatch.setattr(recipe, "MESON_WHEEL_CACHE", cache)
    calls = []
    monkeypatch.setattr(recipe, "_run", lambda *args, **kwargs: calls.append(args))
    with pytest.raises(recipe.BuildError, match="independent pin"):
        recipe._ensure_tools(tmp_path, tmp_path / "build.log")
    assert calls == []


def test_isolated_pkg_config_expands_relocatable_pc_variables(tmp_path: Path) -> None:
    pcdir = tmp_path / "lib" / "pkgconfig"
    pcdir.mkdir(parents=True)
    (pcdir / "fixture.pc").write_text(
        "prefix=${pcfiledir}/../..\n"
        "includedir=${prefix}/include\n"
        "Name: fixture\nVersion: 1\n"
        "Cflags: -I${includedir}\n",
        encoding="utf-8",
    )
    env = os.environ.copy()
    env.update({"PKG_CONFIG_PATH": str(pcdir), "PKG_CONFIG_LIBDIR": str(pcdir)})
    result = subprocess.run(
        [sys.executable, str(BUILD_DIR / "pkg_config.py"), "--cflags", "fixture"],
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )
    assert "${" not in result.stdout
    assert result.stdout.strip() == f"-I{pcdir / '../..' / 'include'}"


def test_pkg_config_rejects_cycles_and_failed_version_requirements() -> None:
    with pytest.raises(pkg_config.PkgConfigError, match="cycle"):
        pkg_config._expand({"a": "${b}", "b": "${a}"}, "${a}")
    assert pkg_config._satisfies("1.5.10", ">=", "1.5.0")
    assert not pkg_config._satisfies("1.5.10", ">=", "2.0")
