#!/usr/bin/env python3
"""Fixed child-only DRI forwarding observation; never a graphics acceptance."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import stat
import uuid
from pathlib import Path
from typing import Any

import run_guest_local_logs_usb as logs

context = logs.context
guest = logs.guest
negotiation = logs.negotiation
DIRECTORY = Path(__file__).parent
BASE = Path('/home/adrian/wowforever-artifacts/arm64-prototype/controls/dri-return-observer')
NAME = 'GuestEglDriObservation'
OBSERVER = 'libdri_return_observer.so'
# Filled only from the final independently inspected source/build result.
ARTIFACT_PINS = {
    NAME: (36736, '7e5d7bb979e0d4a6db0a38625cdf22942b54edc04c6efe7ab5c990bab1e5df76'),
    OBSERVER: (17880, 'd09d4aad3241e074cc142f6b5510bba8c505bfaf010fda77d837a9a259d5a4ef'),
}
SOURCE_PINS = {
    'GuestEglContext.c': '32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f',
    'GuestEglLoader.c': '4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6',
    'guest-egl-local-logs.patch': '46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d',
    'DriReturnObserver.c': '2319381505d6c11481408c7bda03042206308698b247d7227ac87eedac64770b',
    'guest-egl-dri-observer.patch': '67bdbc1d0c8b467521d9fdf6d427e161128c78eaba1494bb109d126f72d2d817',
    'build_dri_observer.sh': '8c3d59c5e90bf910db073dcedc6b8b074579e7d2426867c4601d03467290c404',
}
PREFIX = b'WFDRI_RETURN '
FIELDS = {'schema', 'runId', 'pid', 'uid', 'callSequence', 'api', 'numAttribs',
          'threadSafe', 'attributesObserved', 'attributesOversize', 'attributePairs',
          'providerVerified', 'forwarded', 'resultPresent', 'errorPresent', 'error',
          'incomingErrno', 'returnedErrno'}


def artifact(name: str, destination: str, *, executable: bool) -> dict[str, Any]:
    if set(ARTIFACT_PINS) != {NAME, OBSERVER} or not SOURCE_PINS:
        raise ValueError('DRI artifact authority is not installed')
    for source, digest in SOURCE_PINS.items():
        path = DIRECTORY / source
        info = context._regular_nosymlink(path)
        if info.st_size > 1024 * 1024 or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('DRI source authority changed: ' + source)
    size, digest = ARTIFACT_PINS[name]
    path = BASE / name
    info = context._regular_nosymlink(path)
    if info.st_size != size or not 0 < size <= 64 * 1024 or stat.S_IMODE(info.st_mode) != 0o755:
        raise ValueError('DRI artifact size/mode changed')
    raw = path.read_bytes()
    if len(raw) != size or hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError('DRI artifact hash changed')
    guest.loader._elf64_aarch64_dyn(raw, name, require_linker_interp=executable)
    guest._validate_native_dynamic(raw)
    return {'destination': destination, 'path': path, 'bytes': size,
            'sha256': digest, 'mode': '0700' if executable else '0600'}


def probe_file() -> dict[str, Any]:
    return artifact(NAME, 'guest/bin/' + NAME, executable=True)


def observer_file() -> dict[str, Any]:
    return artifact(OBSERVER, 'guest/observer/' + OBSERVER, executable=False)


def validate_copy_inputs(files: list[dict[str, Any]], probe: dict[str, Any]) -> None:
    guest._validate_copy_inputs(files, {'destination': 'guest/bin/GuestEglLoader', 'mode': '0700'})
    if probe != probe_file():
        raise ValueError('DRI copier requires the fixed child')
    observer_file()


def copy_payload(*args: Any, **kwargs: Any) -> tuple[str, dict[str, Any]]:
    relative, info = negotiation.copy_payload_to_existing_run(
        *args, **kwargs, probe_input_validator=validate_copy_inputs,
    )
    item = observer_file()
    stage = guest.STAGE_PREFIX + uuid.uuid4().hex
    staged = stage + '/' + OBSERVER
    directory = relative + '/guest/observer'
    target = relative + '/' + item['destination']
    created = False
    cleanup_errors: list[str] = []
    try:
        guest.loader.require_adb(guest.loader.adb('shell', 'mkdir', '-m', '755', stage), 'DRI staging')
        created = True
        guest.loader.require_adb(guest.loader.adb('push', str(item['path']), staged, timeout=30), 'DRI sync')
        guest.loader.require_adb(guest.loader.adb('shell', 'chmod', '0644', staged), 'DRI staged mode')
        commands = [
            'umask 077', 'set -e', 'set -C',
            f'test -d {guest._q(relative + "/guest")} && test ! -L {guest._q(relative + "/guest")}',
            f'test ! -e {guest._q(directory)} && test ! -L {guest._q(directory)}',
            f'mkdir -m 700 {guest._q(directory)}',
            f'test ! -e {guest._q(target)} && test ! -L {guest._q(target)}',
            f'cat {guest._q(staged)} > {guest._q(target)}',
            f'chmod 0600 {guest._q(target)}', 'printf DRI_OBSERVER_UPLOAD_OK',
        ]
        uploaded = guest.loader.adb('exec-out', 'run-as', context.PACKAGE, 'sh', '-c', '; '.join(commands), timeout=30)
        if uploaded.returncode or uploaded.stdout != b'DRI_OBSERVER_UPLOAD_OK' or uploaded.stderr:
            raise RuntimeError('DRI exclusive upload sentinel was not exact')
        guest._device_check(target, item['sha256'], item['mode'])
    finally:
        if created:
            for operation in [('rm', '-f', staged), ('rmdir', stage)]:
                try:
                    result = guest.loader.adb('shell', *operation)
                    if result.returncode or result.stdout or result.stderr:
                        cleanup_errors.append(operation[0])
                except Exception as exc:
                    cleanup_errors.append(type(exc).__name__)
            if cleanup_errors:
                raise RuntimeError('owned DRI staging cleanup failed: ' + ','.join(cleanup_errors))
    info['observer'] = {key: item[key] for key in ('destination', 'bytes', 'sha256', 'mode')}
    info['observerStagingClean'] = True
    return relative, info


def native_command(private_root: str, run: str) -> list[str]:
    if not re.fullmatch(r'VTEST-[0-9a-f]{32}', run):
        raise ValueError('invalid DRI RUN')
    if private_root not in (
        f'/data/user/0/{context.PACKAGE}/{context.PRIVATE_PREFIX}/{run}',
        f'/data/data/{context.PACKAGE}/{context.PRIVATE_PREFIX}/{run}',
    ):
        raise ValueError('invalid DRI private root')
    root = private_root + '/guest'
    values = [
        'PATH=/system/bin:/system/xbin', 'ANDROID_ROOT=/system', 'ANDROID_DATA=/data',
        'LD_LIBRARY_PATH=' + root + '/lib:/system/lib64', 'TMPDIR=' + private_root,
        '__EGL_VENDOR_LIBRARY_FILENAMES=' + root + '/share/glvnd/egl_vendor.d/50_mesa.json',
        'GALLIUM_DRIVER=virpipe', 'VTEST_SOCKET_NAME=vtest.sock',
        'EGL_LOG_LEVEL=debug', 'MESA_LOG=file', 'MESA_LOG_LEVEL=debug',
        'LD_PRELOAD=' + root + '/observer/' + OBSERVER,
    ]
    inner = (f'cd {shlex.quote(private_root)} && exec /system/bin/env -i ' +
             ' '.join(map(shlex.quote, values)) + ' /system/bin/linker64 ' +
             shlex.quote(root + '/bin/' + NAME) + ' --run-id ' + shlex.quote(run))
    return [context.ADB, '-s', context.SERIAL, 'shell', '-T', 'run-as', context.PACKAGE,
            'sh', '-c', shlex.quote(inner)]


def validate_guest_stderr(raw: bytes) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    mesa_lines: list[bytes] = []
    observer_bytes = 0
    valid = bool(raw) and len(raw) <= context.STDERR_LIMIT and raw.endswith(b'\n')
    for line in raw.splitlines():
        if not line.startswith(PREFIX):
            mesa_lines.append(line + b'\n')
            continue
        observer_bytes += len(line) + 1
        try:
            if len(line) >= 2048 or observer_bytes > 4096 or len(rows) >= 4:
                raise ValueError('observer bounds')
            row = json.loads(line[len(PREFIX):].decode('ascii'), object_pairs_hook=context.unique_json_pairs)
            if not isinstance(row, dict) or set(row) != FIELDS or row['schema'] != 'wowforever.android-dri-return-observer/version1':
                raise ValueError('observer schema')
            if not isinstance(row['runId'], str) or not re.fullmatch(r'VTEST-[0-9a-f]{32}', row['runId']):
                raise ValueError('observer RUN')
            for field in ('pid', 'uid', 'callSequence', 'api', 'numAttribs', 'incomingErrno', 'returnedErrno'):
                if type(row[field]) is not int:
                    raise ValueError('observer integer')
            if row['pid'] <= 0 or row['uid'] != 10330 or not 1 <= row['callSequence'] <= 4 or not 0 <= row['numAttribs'] <= 2**32-1:
                raise ValueError('observer identity/range')
            if not -(2**31) <= row['api'] < 2**31 or any(not -(2**31) <= row[key] < 2**31 for key in ('incomingErrno', 'returnedErrno')):
                raise ValueError('observer signed range')
            for field in ('threadSafe', 'attributesObserved', 'attributesOversize', 'providerVerified', 'forwarded', 'resultPresent', 'errorPresent'):
                if type(row[field]) is not bool:
                    raise ValueError('observer bool')
            if row['providerVerified'] is not True or row['forwarded'] is not True:
                raise ValueError('observer forwarding')
            if row['errorPresent']:
                if type(row['error']) is not int or not 0 <= row['error'] < 2**32:
                    raise ValueError('observer error')
            elif row['error'] is not None:
                raise ValueError('observer null error')
            pairs = row['attributePairs']
            if not isinstance(pairs, list) or len(pairs) > 16 or any(
                not isinstance(pair, list) or len(pair) != 2 or any(type(value) is not int or not 0 <= value < 2**32 for value in pair)
                for pair in pairs
            ):
                raise ValueError('observer pairs')
            if row['attributesObserved']:
                if row['attributesOversize'] or len(pairs) != row['numAttribs']:
                    raise ValueError('observer count')
            elif pairs or row['attributesOversize'] != (row['numAttribs'] > 16):
                raise ValueError('observer unobserved data')
            rows.append(row)
        except (UnicodeError, ValueError, TypeError, KeyError):
            valid = False
    mesa = logs.validate_guest_logs(b''.join(mesa_lines))
    sequences = sorted(row['callSequence'] for row in rows)
    if sequences != list(range(1, len(rows) + 1)):
        valid = False
    return {**mesa, 'valid': valid and mesa['valid'], 'observerRecords': rows,
            'driReturnObserved': any(row['errorPresent'] for row in rows),
            'exactDriErrorIdentified': False}


def validate_output(*args: Any, **kwargs: Any) -> tuple[str, list[dict[str, Any]], str]:
    actual, rows = context.validate_output(*args, **kwargs)
    return ('DRI_CONTROL_OBSERVED' if actual in {'CONTEXT_FAILED', 'GUEST_CONTEXT_PASS'} else 'UNKNOWN', rows, actual)


def bind_observation(observation: dict[str, Any], *, run: str | None,
                     pid: int | None, result_present: bool | None) -> dict[str, Any]:
    """Require a native witness, not merely successful observer loading."""
    rows = observation.get('observerRecords', [])
    last = max(rows, key=lambda row: row['callSequence']) if rows else None
    bound = (
        observation.get('valid') is True and bool(rows) and run is not None
        and type(pid) is int and pid > 0 and type(result_present) is bool
        and all(row['runId'] == run and row['pid'] == pid and row['uid'] == 10330 for row in rows)
        and last['resultPresent'] == result_present
        and last['errorPresent'] is True
    )
    return {**observation, 'valid': bound,
            'status': 'DRI_RETURN_OBSERVED' if bound else 'TRACE_NOT_OBSERVED',
            'exactDriErrorIdentified': bound,
            'observedDriError': last['error'] if bound else None,
            'observedCallSequence': last['callSequence'] if bound else None}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    expected: dict[str, Any] = {'run': None, 'pid': None, 'result_present': None}

    def command(root: str, run: str) -> list[str]:
        expected['run'] = run
        return native_command(root, run)

    def classified(*values: Any, **kwargs: Any) -> tuple[str, list[dict[str, Any]], str]:
        status, rows, actual = validate_output(*values, **kwargs)
        expected['pid'] = None
        expected['result_present'] = None
        if status == 'DRI_CONTROL_OBSERVED' and len(rows) == 10 and rows[1].get('scopeValid') is True:
            expected['pid'] = rows[0]['pid']
            expected['result_present'] = rows[5]['contextCreated']
        return status, rows, actual

    def stderr(raw: bytes) -> dict[str, Any]:
        return bind_observation(validate_guest_stderr(raw), **expected)

    result = context.main(
        argv=['--output', str(args.output)], probe_loader=probe_file, payload_copier=copy_payload,
        native_command_builder=command, classifier=classified, native_name=NAME,
        output_prefix='native-DRI-', pass_status='DRI_CONTROL_OBSERVED',
        boundary='child-only forwarding observation; DRI enum is unclassified until bound to native PID/RUN; no graphics, D3D or WoW acceptance',
        source_candidate=negotiation.source_runner.NEGOTIATION,
        server_trace_validator=negotiation.validate_negotiation_server_stderr,
        guest_stderr_validator=stderr,
    )
    return result


if __name__ == '__main__':
    raise SystemExit(main())
