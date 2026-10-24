#!/usr/bin/env python3
"""Temporarily grant this disposable CI runner user access to /dev/kvm."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat
import subprocess

DEVICE = Path('/dev/kvm')


def run(*args: str) -> None:
    subprocess.run(['sudo', '-n', *args], check=True, timeout=20)


def enable(state_path: Path) -> None:
    if os.environ.get('CI') != 'true' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('KVM access may only be changed on a disposable GitHub Actions runner.')
    if state_path.exists():
        raise RuntimeError('An existing KVM restoration receipt must not be overwritten.')
    current = DEVICE.lstat()
    if not stat.S_ISCHR(current.st_mode):
        raise RuntimeError('/dev/kvm is not a character device; refusing permission changes.')
    mode = stat.S_IMODE(current.st_mode)
    print(f'/dev/kvm owner={current.st_uid}:{current.st_gid} mode={mode:04o}; capture user={os.getuid()}', flush=True)
    receipt = {'uid': current.st_uid, 'gid': current.st_gid, 'mode': mode,
               'inode': current.st_ino, 'device': current.st_dev, 'rdev': current.st_rdev}
    with state_path.open('x') as receipt_file:
        receipt_file.write(json.dumps(receipt) + '\n')
    # Record the original state before either command, so failure is recoverable.
    run('chown', f'{os.getuid()}:{current.st_gid}', str(DEVICE))
    run('chmod', f'{mode | stat.S_IRUSR | stat.S_IWUSR:04o}', str(DEVICE))
    if not os.access(DEVICE, os.R_OK | os.W_OK):
        raise RuntimeError('The capture user still cannot access /dev/kvm.')
    print('KVM access granted only to the capture user; original state recorded.', flush=True)


def restore(state_path: Path) -> None:
    if os.environ.get('CI') != 'true' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('KVM access may only be restored on a disposable GitHub Actions runner.')
    if not state_path.exists():
        print('No KVM access change was recorded; nothing to restore.')
        return
    receipt = json.loads(state_path.read_text())
    required = {'uid', 'gid', 'mode', 'inode', 'device', 'rdev'}
    if set(receipt) != required or any(type(receipt[key]) is not int or receipt[key] < 0 for key in required):
        raise RuntimeError('Invalid KVM restoration receipt.')
    if receipt['mode'] > 0o7777:
        raise RuntimeError('Invalid KVM restoration mode.')
    current = DEVICE.lstat()
    if not stat.S_ISCHR(current.st_mode) or (current.st_ino, current.st_dev, current.st_rdev) != (
            receipt['inode'], receipt['device'], receipt['rdev']):
        raise RuntimeError('/dev/kvm changed identity; refusing to modify a replacement device.')
    run('chown', f"{receipt['uid']}:{receipt['gid']}", str(DEVICE))
    run('chmod', f"{receipt['mode']:04o}", str(DEVICE))
    restored = DEVICE.lstat()
    if (restored.st_uid, restored.st_gid, stat.S_IMODE(restored.st_mode)) != (
            receipt['uid'], receipt['gid'], receipt['mode']):
        raise RuntimeError('KVM owner/mode restoration did not take effect.')
    print('Original /dev/kvm owner and mode restored and verified.', flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('enable', 'restore'))
    parser.add_argument('--state', required=True, type=Path)
    args = parser.parse_args()
    try:
        (enable if args.action == 'enable' else restore)(args.state)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        raise SystemExit(f'KVM access error: {exc}') from exc


if __name__ == '__main__':
    main()
