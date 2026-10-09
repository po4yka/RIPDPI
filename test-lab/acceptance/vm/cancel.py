#!/usr/bin/env python3
"""Cancel a registered adapter; never signal a reused PID or trust output metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import sys
import time

import router


def process_identity(pid: int) -> dict:
    proc = Path('/proc')/str(pid)
    stat = (proc/'stat').read_text()
    # comm may itself contain spaces and parentheses. Fields after its final
    # closing parenthesis start at field 3; starttime is field 22.
    start = stat.rsplit(')', 1)[1].split()[19]
    return {'pid': pid, 'start_ticks': start,
            'command_sha256': hashlib.sha256((proc/'cmdline').read_bytes()).hexdigest()}


def matches(record: dict, current: dict) -> bool:
    return all(record.get(k) == current.get(k) for k in ('pid', 'start_ticks', 'command_sha256'))


def registry_path(run_id: str) -> Path:
    return router.REGISTRY/(router.identity(run_id)['stem']+'.runner.json')


def register(run_id: str, output: Path) -> dict:
    router.REGISTRY.mkdir(mode=0o700, exist_ok=True)
    record = dict(process_identity(os.getpid()), run_id=run_id, out_dir=str(output.resolve()))
    with registry_path(run_id).open('x') as handle:
        json.dump(record, handle)
    (output/'runner.json').write_text(json.dumps(record, indent=2)+'\n')
    return record


def unregister(run_id: str, record: dict):
    path = registry_path(run_id)
    if path.exists() and json.loads(path.read_text()) == record:
        path.unlink()


def cancel(run_id: str, output: Path, timeout: float = 30):
    path = registry_path(run_id)
    record = json.loads(path.read_text())
    if record.get('run_id') != run_id or record.get('out_dir') != str(output.resolve()):
        raise ValueError('runner belongs to another run or output directory')
    if not matches(record, process_identity(record['pid'])):
        raise ValueError('runner process identity changed; refusing to signal a reused PID')
    os.kill(record['pid'], signal.SIGTERM)
    deadline = time.monotonic()+timeout
    while path.exists():
        if time.monotonic() >= deadline:
            raise RuntimeError('runner did not finish cleanup within the cancellation deadline')
        time.sleep(0.1)
    report = json.loads((output/'result.json').read_text())
    if report.get('run_id') != run_id or report.get('status') != 'failed' or not report.get('cleanup', {}).get('passed'):
        raise RuntimeError('cancelled adapter did not confirm failed status and completed cleanup')
    return {'run_id': run_id, 'cancelled': True, 'cleanup': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--out-dir', type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != 'linux' or os.geteuid() != 0:
        parser.error('cancellation requires Linux root in the acceptance VM')
    print(json.dumps(cancel(args.run_id, args.out_dir)))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
