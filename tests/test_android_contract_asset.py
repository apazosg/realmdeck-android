"""Checks that the Android contract asset has one generated source.

The Gradle integration test is opt-in because it needs the local Gradle/Android
toolchain.  When enabled, it runs in a temporary copy, never in ``android/``.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
ANDROID = ROOT / "android"


def test_manual_asset_is_removed_and_generation_is_declared() -> None:
    assert not (ANDROID / "app/src/main/assets/contract.json").exists()
    assert (ROOT / "forever/contract.json").is_file()
    build_script = (ANDROID / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'tasks.register<Sync>("generateContractAsset")' in build_script
    assert "inputs.file(contractSource)" in build_script
    assert "outputs.file(generatedContractDir" in build_script


def _isolated_gradle_project(destination: Path) -> Path:
    project = destination / "android-gradle-fixture"
    (project / "app").mkdir(parents=True)
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
        shutil.copy2(ANDROID / name, project / name)
    shutil.copytree(ANDROID / "gradle", project / "gradle")
    shutil.copy2(ANDROID / "gradlew", project / "gradlew")
    shutil.copy2(ANDROID / "app/build.gradle.kts", project / "app/build.gradle.kts")
    (project / "gradlew").chmod(0o755)
    assert not project.resolve().is_relative_to(ROOT.resolve())
    return project


def _run_generation(project: Path, source: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            str(project / "gradlew"),
            "--offline",
            "--max-workers=1",
            ":app:generateContractAsset",
            f"-PcontractSource={source}",
            "--console=plain",
        ],
        cwd=project,
        capture_output=True,
        text=True,
        timeout=90,
        check=False,
    )


@pytest.mark.skipif(
    os.environ.get("WOWFOREVER_RUN_GRADLE_INTEGRATION") != "1",
    reason="opt-in: requires the local offline Gradle/Android toolchain",
)
def test_contract_task_fixture_invalidates_without_touching_real_build(tmp_path: Path) -> None:
    project = _isolated_gradle_project(tmp_path)
    real_output = ANDROID / "app/build/generated/contract/assets/contract.json"
    real_before = real_output.read_bytes() if real_output.exists() else None
    fixture = tmp_path / "contract.json"
    fixture.write_bytes(b'{"fixture":1}\n')
    first = _run_generation(project, fixture)
    assert first.returncode == 0, first.stdout + first.stderr
    generated = project / "app/build/generated/contract/assets/contract.json"
    assert generated.read_bytes() == fixture.read_bytes()

    fixture.write_bytes(b'{"fixture":2}\n')
    second = _run_generation(project, fixture)
    assert second.returncode == 0, second.stdout + second.stderr
    assert generated.read_bytes() == fixture.read_bytes()
    if real_before is None:
        assert not real_output.exists()
    else:
        assert real_output.read_bytes() == real_before
