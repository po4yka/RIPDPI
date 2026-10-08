"""Adapt the existing RIPDPI engine packet-smoke oracle to acceptance evidence."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tomllib
import time

ROOT = Path(__file__).resolve().parents[3]


def group_exists(pgid: int) -> bool:
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False


def stop_group(process: subprocess.Popen) -> bool:
    for sig in (signal.SIGTERM, signal.SIGKILL):
        if not group_exists(process.pid):
            process.wait(timeout=1)
            return True
        os.killpg(process.pid, sig)
        deadline = time.monotonic()+5
        while time.monotonic() < deadline:
            process.poll()  # Reap the process leader while descendants exit.
            if not group_exists(process.pid):
                return True
            time.sleep(0.05)
    return not group_exists(process.pid)


def validate_case(directory: Path, case: dict) -> list[dict]:
    checks = []
    case_id = case['id']
    for artifact in case['artifacts']:
        path = directory/artifact
        minimum = 24 if artifact.endswith('.pcap') else 0
        checks.append({'id': case_id+':'+artifact, 'passed': path.is_file() and path.stat().st_size > minimum})
    transcript = (directory/'test-output.txt').read_text() if (directory/'test-output.txt').is_file() else ''
    exact_test = re.search(r'^test '+re.escape(case['testSelector'])+r' \.\.\. ok$', transcript, re.MULTILINE)
    summary = re.search(r'test result: ok\. 1 passed; 0 failed; 0 ignored;', transcript)
    skipped = re.search(r'skipping .+ because|running 0 tests', transcript)
    checks.append({'id': case_id+':exact_test_executed', 'passed': bool(exact_test and summary and not skipped)})
    decoded = directory/'capture.tshark.json'
    try:
        packets = json.loads(decoded.read_text())
        valid = isinstance(packets, list) and bool(packets)
    except (OSError, ValueError):
        valid = False
    checks.append({'id': case_id+':captured_packets', 'passed': valid})
    return checks


def execute(args, result):
    missing = [c for c in ('cargo', 'jq', 'tcpdump', 'tshark', 'bash') if not shutil.which(c)]
    if missing:
        result['status'] = 'blocked'
        raise ValueError('prepare engine tools first: '+', '.join(missing)+'; see vm/prepare-engine.sh')
    registry = json.loads((ROOT/'scripts/ci/packet-smoke-scenarios.json').read_text())
    mandatory = [c for c in registry if c['lane'] == 'cli' and not c.get('generatedTemplate', False)]
    if not mandatory:
        raise ValueError('packet-smoke registry has no required CLI cases')
    output = args.out_dir.resolve()
    env = os.environ.copy()
    for key in list(env):
        if key.startswith('RIPDPI_PACKET_SMOKE_'):
            del env[key]
    toolchain = tomllib.loads((ROOT/'native/rust/rust-toolchain.toml').read_text())['toolchain']['channel']
    env.update(RIPDPI_PACKET_SMOKE_ARTIFACT_DIR=str(output/'engine'),
               RIPDPI_PACKET_SMOKE_CAPTURE_MODE='raw', RIPDPI_PACKET_SMOKE_GENERATED='1',
               RIPDPI_PACKET_SMOKE_GENERATED_BUDGET='8', RIPDPI_PACKET_SMOKE_GENERATED_SEED=args.run_id,
               CARGO_NET_OFFLINE='true', RUSTUP_TOOLCHAIN=toolchain)
    env.setdefault('CARGO_TARGET_DIR', '/var/cache/ripdpi-acceptance/target'
                   if Path('/var/cache/ripdpi-acceptance/target').exists() else str(output.parent/'engine-target'))
    if Path('/var/cache/ripdpi-acceptance/cargo').exists():
        env.setdefault('CARGO_HOME', '/var/cache/ripdpi-acceptance/cargo')
        env.setdefault('RUSTUP_HOME', '/var/cache/ripdpi-acceptance/rustup')
        sysroot = subprocess.run(['rustup', 'run', toolchain, 'rustc', '--print', 'sysroot'], env=env,
                               capture_output=True, text=True, check=True).stdout.strip()
        env['PATH'] = str(Path(sysroot)/'bin')+os.pathsep+env['PATH']
    result['cleanup']['passed'] = False
    with (output/'engine-run.log').open('w') as log:
        process = subprocess.Popen(['bash', str(ROOT/'scripts/ci/run-cli-packet-smoke.sh')], cwd=ROOT/'native/rust',
                                   env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            process.wait(timeout=3600)
        finally:
            # The shell, Cargo, test executable, and raw-capture children share
            # this run-owned process group. Do not leave it after interruption.
            no_orphans = not group_exists(process.pid)
            result['checks'].append({'id': 'engine_no_orphan_processes', 'passed': no_orphans})
            result['cleanup']['passed'] = stop_group(process)
    result['checks'].append({'id': 'packet_engine_process_succeeded', 'passed': process.returncode == 0})
    cases = list(mandatory)
    generated_path = output/'engine/generated-scenarios.json'
    try:
        generated = json.loads(generated_path.read_text())
    except (OSError, ValueError):
        generated = []
    result['checks'].append({'id': 'eight_generated_cases_executed', 'passed': len(generated) == 8})
    template = next(c for c in registry if c['lane'] == 'cli' and c.get('generatedTemplate'))
    cases.extend(dict(c, artifacts=template['artifacts']) for c in generated)
    for case in cases:
        result['checks'].extend(validate_case(output/'engine'/case['id'], case))
    (output/'engine-cases.json').write_text(json.dumps([c['id'] for c in cases], indent=2)+'\n')
