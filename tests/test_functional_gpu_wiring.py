from pathlib import Path
import re


ROOT = Path(__file__).parents[1]
APP = ROOT / 'scripts/arm64-prototype/app'
OVERLAY = APP / 'overlay'


def constant(source: str, name: str) -> str:
    return re.search(rf'const val {name} = "([^"]+)"', source).group(1)


def test_gpu_displayed_profile_matches_actual_fixed_launch_inputs():
    gpu = (OVERLAY / 'ForeverFunctionalGpuCompatibility.kt').read_text()
    runtime = (OVERLAY / 'ForeverRuntimeSetup.kt').read_text()
    poc = (OVERLAY / 'ForeverFunctionalPoc.kt').read_text()
    ux = (OVERLAY / 'ForeverFunctionalLauncherUx.kt').read_text()
    assert constant(gpu, 'PROTON_PROFILE') == constant(runtime, 'PROTON')
    assert constant(gpu, 'DXVK_PROFILE') == constant(runtime, 'DXVK')
    assert constant(gpu, 'PANVK_SHA256') == constant(poc, 'PANVK_SHA256')
    assert constant(gpu, 'CLIENT_BUILD') == constant(ux, 'CLIENT_BUILD')
    assert 'container.setDisplayRenderer("vulkan")' in poc
    assert '-d3d11' in constant(poc, 'EXEC_ARGS_TEMPLATE')


def test_gpu_sources_and_tests_are_staged_by_both_recipes():
    for name in ('apply_functional_poc.py', 'reconstruct_functional_source.py'):
        recipe = (APP / name).read_text()
        assert '"ForeverFunctionalGpuCompatibility.kt"' in recipe
        assert '"ForeverFunctionalGpuCompatibilityTest.kt"' in recipe
    activity = (OVERLAY / 'ForeverFunctionalPocActivity.kt').read_text()
    assert 'ForeverFunctionalGpuCompatibilityCard(' in activity
    assert 'prepared?.let { ForeverFunctionalGpuPreparedConfiguration.fromPrepared(it) }' in activity


def test_detector_does_not_reuse_optimistic_vulkan_fallback_or_start_wine():
    gpu = (OVERLAY / 'ForeverFunctionalGpuCompatibility.kt').read_text()
    assert 'vkGetApiVersionSafe' not in gpu
    assert 'GPUInformation.getRenderer' not in gpu
    assert 'ForeverFunctionalPoc.prepare' not in gpu
    assert 'System.loadLibrary' not in gpu
    assert 'Compatibilidad no comprobada' in gpu
