#!/usr/bin/env python3
"""Supervise one process group under a fail-closed Android device lane lock."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import secrets
import select
import signal
import stat
import subprocess
import sys
import time
from pathlib import Path

AUTH_ENV = "RIPDPI_ANDROID_DEVICE_LOCK_AUTH"
SUPERVISOR_PID_ENV = "RIPDPI_ANDROID_DEVICE_LOCK_SUPERVISOR_PID"
ACQUIRE_QUARANTINE_SECONDS = 0.3
WATCH_INTERVAL_SECONDS = 0.02


def process_start_identity(pid: int) -> str | None:
    result = subprocess.run(
        ["ps", "-o", "lstart=", "-p", str(pid)],
        check=False,
        capture_output=True,
        text=True,
    )
    identity = result.stdout.strip()
    return identity if result.returncode == 0 and identity else None


def identity(metadata: os.stat_result, *, include_links: bool) -> str:
    values = [
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_uid,
        stat.S_IMODE(metadata.st_mode),
    ]
    if include_links:
        values.append(metadata.st_nlink)
    return ":".join(str(value) for value in values)


def path_identities(root_path: Path, root_fd: int, lock_name: str) -> tuple[str, str]:
    root_path_metadata = os.lstat(root_path)
    root_fd_metadata = os.fstat(root_fd)
    root_path_identity = identity(root_path_metadata, include_links=False)
    root_fd_identity = identity(root_fd_metadata, include_links=False)
    if (
        not stat.S_ISDIR(root_path_metadata.st_mode)
        or root_path_identity != root_fd_identity
    ):
        raise RuntimeError("device lock root pathname identity changed")
    lock_metadata = os.stat(lock_name, dir_fd=root_fd, follow_symlinks=False)
    if not stat.S_ISREG(lock_metadata.st_mode):
        raise RuntimeError("device lock pathname is not a regular file")
    return root_fd_identity, identity(lock_metadata, include_links=True)


def kill_group(process_group: int) -> None:
    try:
        os.killpg(process_group, signal.SIGKILL)
    except ProcessLookupError:
        pass


def launch(args: argparse.Namespace) -> int:
    gate = os.read(args.gate_fd, 1)
    os.close(args.gate_fd)
    if gate != b"1":
        return 5
    os.execvpe(args.command[0], args.command, os.environ.copy())


def guard(
    *,
    liveness_fd: int,
    status_fd: int,
    ack_fd: int,
    command: list[str],
    environment: dict[str, str],
    root_fd: int,
    lock_fd: int,
) -> int:
    gate_read = gate_write = -1
    process: subprocess.Popen[bytes] | None = None
    try:
        os.close(root_fd)
        gate_read, gate_write = os.pipe()
        process = subprocess.Popen(
            [
                sys.executable,
                str(Path(__file__).resolve()),
                "launch",
                "--gate-fd",
                str(gate_read),
                *command,
            ],
            env=environment,
            close_fds=True,
            pass_fds=(gate_read,),
            start_new_session=True,
        )
        os.close(gate_read)
        gate_read = -1
        os.write(status_fd, f"{process.pid}\n".encode("ascii"))
        os.close(status_fd)
        status_fd = -1
        if os.read(ack_fd, 1) != b"1":
            kill_group(process.pid)
            process.wait()
            return 5
        os.close(ack_fd)
        ack_fd = -1
        os.write(gate_write, b"1")
        os.close(gate_write)
        gate_write = -1
        while True:
            if process.poll() is not None:
                kill_group(process.pid)
                process.wait()
                return process.returncode or 0
            readable, _, _ = select.select(
                [liveness_fd], [], [], WATCH_INTERVAL_SECONDS
            )
            if readable and os.read(liveness_fd, 1) == b"":
                kill_group(process.pid)
                process.wait()
                return 6
    finally:
        for descriptor in (gate_read, gate_write, status_fd, ack_fd, liveness_fd):
            if descriptor >= 0:
                try:
                    os.close(descriptor)
                except OSError:
                    pass
        if process is not None and process.poll() is None:
            kill_group(process.pid)
            process.wait()
        try:
            os.close(lock_fd)
        except OSError:
            pass


def supervise(args: argparse.Namespace) -> int:
    root_path = args.lock_root.resolve(strict=True)
    if not args.lock_name or "/" in args.lock_name:
        raise RuntimeError("device lock name must be basename-only")
    root_fd = os.open(root_path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        base_flags = os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
        try:
            lock_fd = os.open(
                args.lock_name,
                base_flags | os.O_CREAT | os.O_EXCL,
                0o600,
                dir_fd=root_fd,
            )
            lock_created = True
        except FileExistsError:
            lock_fd = os.open(args.lock_name, base_flags, dir_fd=root_fd)
            lock_created = False
        try:
            lock_metadata = os.fstat(lock_fd)
            if (
                not stat.S_ISREG(lock_metadata.st_mode)
                or lock_metadata.st_uid != os.getuid()
                or stat.S_IMODE(lock_metadata.st_mode) != 0o600
                or lock_metadata.st_nlink != 1
            ):
                raise RuntimeError(
                    "device lock must be a single-link owned mode-0600 file"
                )
            try:
                fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print("physical Android device lane is already in use", file=sys.stderr)
                return 3
            expected = path_identities(root_path, root_fd, args.lock_name)
            if not lock_created:
                os.lseek(lock_fd, 0, os.SEEK_SET)
                try:
                    previous = json.loads(os.read(lock_fd, 4096).decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError) as error:
                    raise RuntimeError(
                        "existing device lock metadata is malformed; remove it manually"
                    ) from error
                previous_pid = previous.get("supervisorPid")
                previous_start = previous.get("supervisorStartIdentity")
                if not isinstance(previous_pid, int) or not isinstance(
                    previous_start, str
                ):
                    raise RuntimeError("existing device lock metadata is incomplete")
                if process_start_identity(previous_pid) == previous_start:
                    raise RuntimeError(
                        "existing device lock metadata names a live supervisor"
                    )
                if (
                    previous.get("rootIdentity") != expected[0]
                    or previous.get("lockIdentity") != expected[1]
                ):
                    raise RuntimeError(
                        "existing device lock metadata identity is stale"
                    )
            nonce = secrets.token_hex(32)
            supervisor_start = process_start_identity(os.getpid())
            if supervisor_start is None:
                raise RuntimeError("could not determine supervisor start identity")
            metadata = {
                "nonce": nonce,
                "supervisorPid": os.getpid(),
                "supervisorStartIdentity": supervisor_start,
                "rootIdentity": expected[0],
                "lockIdentity": expected[1],
            }
            os.ftruncate(lock_fd, 0)
            os.lseek(lock_fd, 0, os.SEEK_SET)
            os.write(lock_fd, json.dumps(metadata, sort_keys=True).encode("utf-8"))
            os.fsync(lock_fd)
            deadline = time.monotonic() + ACQUIRE_QUARANTINE_SECONDS
            while time.monotonic() < deadline:
                if path_identities(root_path, root_fd, args.lock_name) != expected:
                    raise RuntimeError("device lock identity changed during quarantine")
                time.sleep(WATCH_INTERVAL_SECONDS)

            liveness_read, liveness_write = os.pipe()
            status_read, status_write = os.pipe()
            ack_read, ack_write = os.pipe()
            environment = os.environ.copy()
            environment[AUTH_ENV] = nonce
            environment[SUPERVISOR_PID_ENV] = str(os.getpid())
            guard_pid = os.fork()
            if guard_pid == 0:
                try:
                    os.close(liveness_write)
                    os.close(status_read)
                    os.close(ack_write)
                    result = guard(
                        liveness_fd=liveness_read,
                        status_fd=status_write,
                        ack_fd=ack_read,
                        command=args.command,
                        environment=environment,
                        root_fd=root_fd,
                        lock_fd=lock_fd,
                    )
                except BaseException as error:
                    print(f"device lock guard failed: {error}", file=sys.stderr)
                    result = 70
                os._exit(result)
            os.close(liveness_read)
            os.close(status_write)
            os.close(ack_read)
            process_group_raw = os.read(status_read, 32)
            os.close(status_read)
            if not process_group_raw.endswith(b"\n"):
                raise RuntimeError("device lock guard did not report a process group")
            process_group = int(process_group_raw)
            if args.state_path is not None:
                args.state_path.write_text(
                    json.dumps(
                        {"guardPid": guard_pid, "workloadPgid": process_group},
                        sort_keys=True,
                    ),
                    encoding="utf-8",
                )
                args.state_path.chmod(0o600)
            if args.handshake_hold_path is not None:
                while args.handshake_hold_path.exists():
                    waited_pid, status = os.waitpid(guard_pid, os.WNOHANG)
                    if waited_pid == guard_pid:
                        kill_group(process_group)
                        return os.waitstatus_to_exitcode(status)
                    time.sleep(WATCH_INTERVAL_SECONDS)
            os.write(ack_write, b"1")
            os.close(ack_write)
            while True:
                waited_pid, status = os.waitpid(guard_pid, os.WNOHANG)
                if waited_pid == guard_pid:
                    kill_group(process_group)
                    return os.waitstatus_to_exitcode(status)
                if path_identities(root_path, root_fd, args.lock_name) != expected:
                    kill_group(process_group)
                    os.close(liveness_write)
                    os.waitpid(guard_pid, 0)
                    return 4
                time.sleep(WATCH_INTERVAL_SECONDS)
        finally:
            os.close(lock_fd)
    finally:
        os.close(root_fd)


def verify(args: argparse.Namespace) -> int:
    nonce = os.environ.get(AUTH_ENV)
    if not nonce or nonce != args.nonce:
        raise RuntimeError("device lock authentication nonce is missing or mismatched")
    root_path = args.lock_root.resolve(strict=True)
    root_fd = os.open(root_path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        lock_fd = os.open(
            args.lock_name,
            os.O_RDWR | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=root_fd,
        )
        try:
            raw = os.read(lock_fd, 4096)
            metadata = json.loads(raw.decode("utf-8"))
            if (
                metadata.get("nonce") != nonce
                or metadata.get("supervisorPid") != args.supervisor_pid
                or process_start_identity(args.supervisor_pid)
                != metadata.get("supervisorStartIdentity")
                or path_identities(root_path, root_fd, args.lock_name)
                != (metadata.get("rootIdentity"), metadata.get("lockIdentity"))
            ):
                raise RuntimeError("device lock authentication metadata is invalid")
            try:
                fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return 0
            raise RuntimeError("device lock is not currently held by the supervisor")
        finally:
            os.close(lock_fd)
    finally:
        os.close(root_fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="mode", required=True)
    run = subparsers.add_parser("run")
    run.add_argument("--lock-root", required=True, type=Path)
    run.add_argument("--lock-name", required=True)
    run.add_argument("--state-path", type=Path)
    run.add_argument("--handshake-hold-path", type=Path)
    run.add_argument("command", nargs=argparse.REMAINDER)
    launcher = subparsers.add_parser("launch")
    launcher.add_argument("--gate-fd", required=True, type=int)
    launcher.add_argument("command", nargs=argparse.REMAINDER)
    verifier = subparsers.add_parser("verify")
    verifier.add_argument("--lock-root", required=True, type=Path)
    verifier.add_argument("--lock-name", required=True)
    verifier.add_argument("--supervisor-pid", required=True, type=int)
    verifier.add_argument("--nonce", required=True)
    args = parser.parse_args()
    if args.mode == "launch":
        return launch(args)
    if args.mode == "verify":
        return verify(args)
    if not args.command:
        parser.error("run requires a command")
    return supervise(args)


if __name__ == "__main__":
    raise SystemExit(main())
