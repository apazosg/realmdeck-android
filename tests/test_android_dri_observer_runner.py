import copy
import importlib.util
import json
import shlex
import sys
from pathlib import Path

import pytest

PATH = Path(__file__).parents[1] / 'scripts/diagnostics/android-vtest'
sys.path.insert(0, str(PATH))
SPEC = importlib.util.spec_from_file_location('dri_runner_tests', PATH / 'run_dri_observer_usb.py')
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)
RUN = 'VTEST-' + 'a' * 32


def record():
    return {
        'schema': 'wowforever.android-dri-return-observer/version1', 'runId': RUN,
        'pid': 123, 'uid': 10330, 'callSequence': 1, 'api': 3, 'numAttribs': 3,
        'threadSafe': False, 'attributesObserved': True, 'attributesOversize': False,
        'attributePairs': [[0, 3], [1, 3], [2, 0]], 'providerVerified': True,
        'forwarded': True, 'resultPresent': False, 'errorPresent': True,
        'error': 3, 'incomingErrno': 13, 'returnedErrno': 13,
    }


def output(row=None):
    return b'MESA-EGL: debug: EGL_BAD_MATCH in dri2_create_context\n' + (
        b'' if row is None else runner.PREFIX + json.dumps(row).encode() + b'\n'
    )


def test_exact_original_error_requires_pid_run_and_result_witness():
    observed = runner.validate_guest_stderr(output(record()))
    assert observed['valid']
    bound = runner.bind_observation(observed, run=RUN, pid=123, result_present=False)
    assert bound['status'] == 'DRI_RETURN_OBSERVED'
    assert bound['observedDriError'] == 3
    for changes in ({'run': 'VTEST-' + 'b' * 32}, {'pid': 124}, {'result_present': True}, {'pid': None}):
        values = {'run': RUN, 'pid': 123, 'result_present': False, **changes}
        assert runner.bind_observation(observed, **values)['valid'] is False


def test_missing_duplicate_malformed_or_unforwarded_never_identifies_error():
    missing = runner.validate_guest_stderr(output())
    assert runner.bind_observation(missing, run=RUN, pid=123, result_present=False)['status'] == 'TRACE_NOT_OBSERVED'
    for change in ({'providerVerified': False}, {'forwarded': False}, {'error': True},
                   {'uid': True}, {'attributesObserved': False}, {'numAttribs': 2},
                   {'extra': 1}, {'attributePairs': [[0, True]]}):
        row = {**record(), **change}
        assert runner.validate_guest_stderr(output(row))['valid'] is False
    doubled = output(record()) + runner.PREFIX + json.dumps(record()).encode() + b'\n'
    assert runner.validate_guest_stderr(doubled)['valid'] is False
    assert runner.validate_guest_stderr(output(record())[:-1])['valid'] is False


def test_record_limit_and_attribute_pair_bounds():
    row = record()
    row.update(numAttribs=16, attributePairs=[[i, i + 1] for i in range(16)])
    assert runner.validate_guest_stderr(output(row))['valid']
    row.update(numAttribs=17, attributesObserved=False, attributesOversize=True, attributePairs=[])
    assert runner.validate_guest_stderr(output(row))['valid']
    raw = b'MESA-EGL: debug: EGL_BAD_MATCH\n'
    for index in range(5):
        row = {**record(), 'callSequence': index + 1}
        raw += runner.PREFIX + json.dumps(row).encode() + b'\n'
    assert runner.validate_guest_stderr(raw)['valid'] is False


def test_reordered_stderr_binds_highest_call_sequence_not_last_write():
    first = {**record(), 'callSequence': 1, 'resultPresent': True, 'error': 0}
    second = {**record(), 'callSequence': 2, 'resultPresent': False, 'error': 3}
    raw = output(second) + runner.PREFIX + json.dumps(first).encode() + b'\n'
    observed = runner.validate_guest_stderr(raw)
    assert observed['valid']
    bound = runner.bind_observation(observed, run=RUN, pid=123, result_present=False)
    assert bound['valid'] and bound['observedDriError'] == 3
    assert bound['observedCallSequence'] == 2


def test_preload_is_only_the_fixed_native_child_not_server():
    root = f'/data/user/0/{runner.context.PACKAGE}/{runner.context.PRIVATE_PREFIX}/{RUN}'
    command = runner.native_command(root, RUN)
    inner = shlex.split(command[-1])[0]
    tokens = shlex.split(inner)
    assert tokens.count('LD_PRELOAD=' + root + '/guest/observer/libdri_return_observer.so') == 1
    assert tokens[-3:] == [root + '/guest/bin/GuestEglDriObservation', '--run-id', RUN]
    assert 'GALLIUM_DRIVER=virpipe' in tokens
    assert 'EGL_LOG_LEVEL=debug' in tokens
    with pytest.raises(ValueError):
        runner.native_command('/data/local/tmp/elsewhere', RUN)


def test_current_artifacts_have_separate_pins_and_source_authority():
    child = runner.probe_file()
    library = runner.observer_file()
    assert child['destination'] == 'guest/bin/GuestEglDriObservation'
    assert library['destination'] == 'guest/observer/libdri_return_observer.so'
    assert child['mode'] == '0700' and library['mode'] == '0600'
    assert child['bytes'] <= 64 * 1024 and library['bytes'] <= 64 * 1024


def test_unknown_native_classification_cannot_bind_an_observer(monkeypatch, tmp_path):
    rows = [dict(pid=123, scopeValid=True, contextCreated=False) for _ in range(10)]
    monkeypatch.setattr(runner.context, 'validate_output', lambda *args, **kwargs: ('UNKNOWN', rows))

    def orchestration(**kwargs):
        root = f'/data/user/0/{runner.context.PACKAGE}/{runner.context.PRIVATE_PREFIX}/{RUN}'
        kwargs['native_command_builder'](root, RUN)
        status, _, actual = kwargs['classifier'](b'ignored', RUN, 1, True)
        assert status == actual == 'UNKNOWN'
        observed = kwargs['guest_stderr_validator'](output(record()))
        assert observed['valid'] is False
        assert observed['status'] == 'TRACE_NOT_OBSERVED'
        return 1

    monkeypatch.setattr(runner.context, 'main', orchestration)
    assert runner.main(['--output', str(tmp_path)]) == 1
