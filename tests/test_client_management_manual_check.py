from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).parents[1]
MANAGEMENT = ROOT / "scripts/arm64-prototype/app/updater-gate/ForeverClientManagement.kt"


def _text() -> str:
    return MANAGEMENT.read_text(encoding="utf-8")


def test_opening_dialog_performs_only_local_inspection() -> None:
    # Acceptance (preparation-reuse plan): opening the manager must not consult
    # remote versions. The old combined detect() flow is gone.
    src = _text()
    assert "if (!execution.busy.value) inspectLocal()" in src
    assert "detect()" not in src
    local_block = src[src.index("fun inspectLocal()") : src.index("fun checkForUpdates()")]
    assert "service.inspect()" in local_block
    assert "checkVersions" not in local_block
    for anchor in ("LaunchedEffect(Unit)",):
        first = src.index(anchor)
        window = src[first : first + 600]
        assert "checkVersions" not in window
        assert "preparePublishedVersion" not in window
        assert "startUpdate" not in window


def test_remote_check_is_explicit_and_keeps_local_state_on_failure() -> None:
    # A manual check still refreshes the local inspection first, so a network
    # failure surfaces a short error without hiding an installed client.
    src = _text()
    block = src[src.index("fun checkForUpdates()") : src.index("LaunchedEffect(Unit)")]
    assert "service.checkVersions()" in block
    assert block.count("inspection = local") >= 3
    assert "throw failure" in block
    assert "network versions unavailable" in block


def test_play_available_with_installed_client_without_known_remote() -> None:
    # Jugar is offered whenever an installed version exists, even with no
    # published fingerprint (fresh open or failed check). The remote check is
    # a separate explicit button, not a gate.
    src = _text()
    assert "Buscar actualizaciones" in src
    branch = src[src.index("} else if (installed != null) {") :]
    branch = branch[: branch.index("} else if (latest != null) {")]
    assert 'Text("Jugar")' in branch
    assert "onPlay" in branch


def test_update_planning_stays_behind_explicit_confirmed_action() -> None:
    # Metadata consent + planning only run from the explicit update buttons;
    # play-with-installed never triggers them.
    src = _text()
    assert src.count("startUpdate(latest)") == 2
    assert "fun startUpdate(target: ForeverClientUpdateCheck.Fingerprint)" in src
    assert "service.preparePublishedVersion(" in src
