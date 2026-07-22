#!/usr/bin/env python3
"""Capture a private packet trace from a fixed SSH/tcpdump vantage.

This is a private capture utility for the dual-vantage producer. It does not
classify a release gate or emit a network-evidence observation by itself. Raw
pcap data stays in the caller-owned private directory; only the small metadata
document is suitable for later redacted manifest assembly.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import queue
import re
import shlex
import signal
import stat
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import BinaryIO


SSH = Path("/usr/bin/ssh")
SSH_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.@-]{0,254}")
INTERFACE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:@-]{0,63}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
ROLES = {"client-underlay", "external-observer"}
REMOTE_RUN_DIR = "/run/ripdpi-network-evidence"
READY_LINE = "RIPDPI_CAPTURE_READY"
MAX_CAPTURE_BYTES = 16 * 1024 * 1024
MAX_CAPTURE_PACKETS = 70_000


def validate_ssh_name(value: str, label: str) -> str:
    if SSH_NAME_RE.fullmatch(value) is None or value.startswith("-"):
        raise ValueError(f"{label} has invalid SSH name syntax")
    return value


def validate_interface(value: str) -> str:
    if value == "all" or INTERFACE_RE.fullmatch(value) is None or value.startswith("-"):
        raise ValueError("capture interface has invalid syntax")
    return value


def parse_capture_address(
    value: str,
) -> ipaddress.IPv4Address | ipaddress.IPv6Address:
    try:
        address = ipaddress.ip_address(value)
    except ValueError as exc:
        raise ValueError("capture address must be an IP literal") from exc
    if (
        getattr(address, "scope_id", None) is not None
        or address.is_unspecified
        or address.is_loopback
        or address.is_multicast
        or address.is_link_local
    ):
        raise ValueError("capture address must be an unscoped unicast IP literal")
    return address


def parse_endpoint(
    value: str,
) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, int]:
    try:
        if value.startswith("["):
            close = value.index("]")
            address_text = value[1:close]
            if value[close + 1 : close + 2] != ":":
                raise ValueError
            port_text = value[close + 2 :]
        else:
            address_text, port_text = value.rsplit(":", 1)
        address = parse_capture_address(address_text)
        port = int(port_text)
    except (ValueError, IndexError) as exc:
        raise ValueError("capture endpoint must be an IP:port pair") from exc
    if not 1 <= port <= 65535:
        raise ValueError("capture endpoint port must be between 1 and 65535")
    return address, port


def build_capture_filter(
    peer: ipaddress.IPv4Address | ipaddress.IPv6Address,
    endpoints: list[tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, int]],
) -> str:
    if not endpoints or len(endpoints) > 8 or len(set(endpoints)) != len(endpoints):
        raise ValueError("capture requires one to eight unique endpoint pairs")
    clauses = []
    for address, port in endpoints:
        if address.version != peer.version:
            raise ValueError("capture peer and endpoint address families must match")
        clauses.append(
            "((src host "
            f"{peer.compressed} and dst host {address.compressed} and "
            f"(tcp dst port {port} or udp dst port {port})) or "
            "(src host "
            f"{address.compressed} and dst host {peer.compressed} and "
            f"(tcp src port {port} or udp src port {port})))"
        )
    return "(" + " or ".join(clauses) + ")"


def build_remote_command(
    interface: str, token: str, max_seconds: int, capture_filter: str
) -> str:
    validate_interface(interface)
    if SHA256_RE.fullmatch(token) is None:
        raise ValueError("capture token must be a lowercase SHA-256 value")
    if not 1 <= max_seconds <= 900:
        raise ValueError("capture timeout must be between 1 and 900 seconds")
    script = r"""set -eu
interface=$1
token=$2
max_seconds=$3
capture_filter=$4
run_dir=/run/ripdpi-network-evidence
pid_file="$run_dir/$token.pid"
umask 077
/bin/mkdir -p "$run_dir"
if [ -e "$pid_file" ]; then
  echo stale-capture-session >&2
  exit 72
fi
pid=$$
start_time=
session_exe=
child_pid=
cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [ -n "$child_pid" ] && [ -r "/proc/$child_pid/stat" ]; then
    child_pgid=$(/bin/ps -o pgid= -p "$child_pid" | /usr/bin/tr -d ' ')
    if [ "$child_pgid" = "$pid" ]; then
      /bin/kill -INT "$child_pid" 2>/dev/null || true
    fi
    deadline=50
    while /bin/kill -0 "$child_pid" 2>/dev/null && [ "$deadline" -gt 0 ]; do
      /bin/sleep 0.1
      deadline=$((deadline - 1))
    done
    if /bin/kill -0 "$child_pid" 2>/dev/null; then
      child_pgid=$(/bin/ps -o pgid= -p "$child_pid" | /usr/bin/tr -d ' ')
      if [ "$child_pgid" = "$pid" ]; then
        /bin/kill -KILL "$child_pid" 2>/dev/null || true
      fi
    fi
    wait "$child_pid" 2>/dev/null || true
  fi
  collect_group_members() {
    members=
    for stat_path in /proc/[0-9]*/stat; do
      IFS= read -r stat_line < "$stat_path" 2>/dev/null || continue
      member=${stat_line%% *}
      stat_tail=${stat_line##*) }
      set -- $stat_tail
      member_state=${1:-}
      member_pgid=${3:-}
      if [ "$member_state" != Z ] && [ "$member_pgid" = "$pid" ] \
          && [ "$member" != "$pid" ]; then
        members="$members $member"
      fi
    done
  }
  collect_group_members
  for member in $members; do
    member_pgid=$(/bin/ps -o pgid= -p "$member" | /usr/bin/tr -d ' ')
    if [ "$member_pgid" = "$pid" ]; then
      kill -KILL "$member" 2>/dev/null || true
    fi
  done
  deadline=20
  while [ "$deadline" -gt 0 ]; do
    collect_group_members
    [ -z "$members" ] && break
    /bin/sleep 0.1
    deadline=$((deadline - 1))
  done
  if [ -n "$members" ]; then
    exit 76
  fi
  /bin/rm -f -- "$pid_file"
  exit "$status"
}
trap cleanup EXIT HUP INT TERM
start_time=$(/usr/bin/awk '{print $22}' "/proc/$pid/stat")
session_exe=$(/usr/bin/readlink -f "/proc/$pid/exe")
pgid=$(/bin/ps -o pgid= -p "$pid" | /usr/bin/tr -d ' ')
[ "$pgid" = "$pid" ]
printf '%s %s %s\n' "$pid" "$start_time" "$session_exe" > "$pid_file"
/usr/bin/timeout --foreground --signal=INT --kill-after=5 "$max_seconds" \
  /usr/bin/tcpdump -U -n -s 192 -c 70000 -i "$interface" -w - "$capture_filter" &
child_pid=$!
/bin/sleep 0.2
/bin/kill -0 "$child_pid"
printf 'RIPDPI_CAPTURE_READY\n' >&2
IFS= read -r _ || true
"""
    arguments = [
        "/usr/bin/setsid",
        "/bin/sh",
        "-c",
        script,
        "ripdpi-capture",
        interface,
        token,
        str(max_seconds),
        capture_filter,
    ]
    return "sudo -n " + shlex.join(arguments)


def ssh_arguments(target: str, jump: str | None, remote_command: str) -> list[str]:
    validate_ssh_name(target, "SSH target")
    arguments = [
        str(SSH),
        "-T",
        "-oBatchMode=yes",
        "-oConnectTimeout=10",
        "-oServerAliveInterval=5",
        "-oServerAliveCountMax=2",
    ]
    if jump is not None:
        arguments.extend(("-J", validate_ssh_name(jump, "SSH jump host")))
    arguments.extend(("--", target, remote_command))
    return arguments


def prepare_paths(capture: Path, metadata: Path, ready: Path, stop: Path) -> None:
    paths = (capture, metadata, ready, stop)
    resolved = [path.resolve(strict=False) for path in paths]
    if len(set(resolved)) != len(resolved):
        raise ValueError("capture, metadata, ready, and stop paths must differ")
    for path in paths:
        if path.is_symlink():
            raise ValueError("capture coordination paths must not be symlinks")
        if path.exists():
            raise ValueError("capture coordination paths must not already exist")
    for path in (capture, metadata):
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        parent_stat = path.parent.stat()
        if not stat.S_ISDIR(parent_stat.st_mode) or parent_stat.st_mode & 0o077:
            raise ValueError("capture output directory must be private")


def remove_failed_outputs(capture: Path, metadata: Path, ready: Path) -> None:
    capture.unlink(missing_ok=True)
    metadata.unlink(missing_ok=True)
    ready.unlink(missing_ok=True)


def count_classic_pcap_packets(path: Path) -> int:
    if path.stat().st_size > MAX_CAPTURE_BYTES:
        raise ValueError("capture exceeds the producer-owned size limit")
    magic_to_endian = {
        b"\xd4\xc3\xb2\xa1": "<",
        b"\xa1\xb2\xc3\xd4": ">",
        b"\x4d\x3c\xb2\xa1": "<",
        b"\xa1\xb2\x3c\x4d": ">",
    }
    with path.open("rb") as handle:
        global_header = handle.read(24)
        if len(global_header) != 24 or global_header[:4] not in magic_to_endian:
            raise ValueError("capture is not a complete classic pcap file")
        endian = magic_to_endian[global_header[:4]]
        count = 0
        while True:
            packet_header = handle.read(16)
            if not packet_header:
                return count
            if len(packet_header) != 16:
                raise ValueError("capture has a truncated packet header")
            _seconds, _fraction, captured_length, original_length = struct.unpack(
                f"{endian}IIII", packet_header
            )
            if captured_length > original_length or captured_length > 16 * 1024 * 1024:
                raise ValueError("capture has an invalid packet length")
            if len(handle.read(captured_length)) != captured_length:
                raise ValueError("capture has a truncated packet payload")
            count += 1
            if count > MAX_CAPTURE_PACKETS:
                raise ValueError("capture exceeds the producer-owned packet limit")


def contains_marker(path: Path, marker_sha256: str) -> bool:
    if SHA256_RE.fullmatch(marker_sha256) is None:
        raise ValueError("required marker must be a lowercase SHA-256 value")
    marker = ("RIPDPI-EVIDENCE-V2:" + marker_sha256).encode("ascii")
    overlap = len(marker) - 1
    previous = b""
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            payload = previous + chunk
            if marker in payload:
                return True
            previous = payload[-overlap:] if overlap else b""
    return False


def write_metadata(
    *,
    output: Path,
    capture: Path,
    role: str,
    correlation_id: str,
    started_at: int,
    finished_at: int,
    packet_count: int,
) -> None:
    if role not in ROLES:
        raise ValueError("unsupported capture role")
    if SHA256_RE.fullmatch(correlation_id) is None:
        raise ValueError("correlation id must be a lowercase SHA-256 value")
    digest = hashlib.sha256()
    with capture.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    document = {
        "version": "network_evidence_private_capture_v1",
        "role": role,
        "correlationId": correlation_id,
        "captureStartedAtEpoch": started_at,
        "captureFinishedAtEpoch": finished_at,
        "packetCount": packet_count,
        "rawCaptureSha256": digest.hexdigest(),
    }
    serialized = (
        json.dumps(document, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode("ascii")
    descriptor, temporary_name = tempfile.mkstemp(
        dir=output.parent, prefix=f".{output.name}."
    )
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(serialized)
        os.replace(temporary, output)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _read_stderr(stream: BinaryIO, messages: queue.Queue[bytes]) -> None:
    try:
        while line := stream.readline():
            messages.put(line.rstrip(b"\r\n"))
    finally:
        messages.put(b"")


def _wait_until_ready(
    process: subprocess.Popen[bytes], messages: queue.Queue[bytes]
) -> None:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("remote capture exited before readiness")
        try:
            message = messages.get(timeout=0.2)
        except queue.Empty:
            continue
        if message == READY_LINE.encode("ascii"):
            return
        if message == b"":
            break
    raise RuntimeError("remote capture did not report tcpdump readiness")


def _cleanup_remote(target: str, jump: str | None, token: str) -> None:
    cleanup_script = r"""set -eu
pid_file=$1
if [ -e "$pid_file" ]; then
  IFS=' ' read -r pid expected_start expected_exe extra < "$pid_file"
  case "$pid" in
    ''|*[!0-9]*) exit 73 ;;
  esac
  case "$expected_start" in
    ''|*[!0-9]*) exit 73 ;;
  esac
  [ -z "${extra:-}" ] || exit 73
  [ "$expected_exe" = "$(/usr/bin/readlink -f /bin/sh)" ] || exit 73
  [ -r "/proc/$pid/stat" ] || exit 74
  current_start=$(/usr/bin/awk '{print $22}' "/proc/$pid/stat")
  current_exe=$(/usr/bin/readlink -f "/proc/$pid/exe")
  pgid=$(/bin/ps -o pgid= -p "$pid" | /usr/bin/tr -d ' ')
  [ "$current_start" = "$expected_start" ] || exit 74
  [ "$current_exe" = "$expected_exe" ] || exit 74
  [ "$pgid" = "$pid" ] || exit 74
  /bin/kill -INT -- "-$pid" 2>/dev/null || true
  deadline=50
  while /bin/kill -0 -- "-$pid" 2>/dev/null && [ "$deadline" -gt 0 ]; do
    /bin/sleep 0.1
    deadline=$((deadline - 1))
  done
  if /bin/kill -0 -- "-$pid" 2>/dev/null; then
    /bin/kill -KILL -- "-$pid" 2>/dev/null || true
    deadline=20
    while /bin/kill -0 -- "-$pid" 2>/dev/null && [ "$deadline" -gt 0 ]; do
      /bin/sleep 0.1
      deadline=$((deadline - 1))
    done
  fi
  /bin/kill -0 -- "-$pid" 2>/dev/null && exit 75
  /bin/rm -f -- "$pid_file"
fi
/usr/bin/test ! -e "$pid_file"
"""
    command = "sudo -n " + shlex.join(
        [
            "/bin/sh",
            "-c",
            cleanup_script,
            "ripdpi-cleanup",
            f"{REMOTE_RUN_DIR}/{token}.pid",
        ]
    )
    for attempt in range(3):
        try:
            completed = subprocess.run(
                ssh_arguments(target, jump, command),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=15,
                check=False,
            )
        except subprocess.TimeoutExpired:
            completed = None
        if completed is not None and completed.returncode == 0:
            return
        if attempt < 2:
            time.sleep(0.2)
    raise RuntimeError("remote capture cleanup could not be verified")


def capture(args: argparse.Namespace) -> None:
    capture_path = Path(args.output_pcap)
    metadata_path = Path(args.metadata_output)
    ready_path = Path(args.ready_file)
    stop_path = Path(args.stop_file)
    prepare_paths(capture_path, metadata_path, ready_path, stop_path)
    token = hashlib.sha256(
        f"ripdpi:capture:v1:{args.correlation_id}:{args.role}".encode("ascii")
    ).hexdigest()
    endpoints = [parse_endpoint(value) for value in args.endpoint]
    peer = parse_capture_address(args.peer_address)
    capture_filter = build_capture_filter(peer, endpoints)
    remote_command = build_remote_command(
        args.interface, token, args.max_seconds, capture_filter
    )
    process: subprocess.Popen[bytes] | None = None
    stderr_thread: threading.Thread | None = None
    cleanup_verified = False
    started_at = int(time.time())
    try:
        with capture_path.open("xb", buffering=0) as capture_file:
            os.chmod(capture_path, 0o600)
            process = subprocess.Popen(
                ssh_arguments(args.ssh_target, args.jump, remote_command),
                stdin=subprocess.PIPE,
                stdout=capture_file,
                stderr=subprocess.PIPE,
                start_new_session=True,
            )
            assert process.stderr is not None
            messages: queue.Queue[bytes] = queue.Queue()
            stderr_thread = threading.Thread(
                target=_read_stderr, args=(process.stderr, messages), daemon=True
            )
            stderr_thread.start()
            _wait_until_ready(process, messages)
            ready_path.touch(mode=0o600, exist_ok=False)
            deadline = time.monotonic() + args.max_seconds
            while not stop_path.exists():
                if process.poll() is not None:
                    raise RuntimeError("remote capture exited before stop marker")
                if capture_path.stat().st_size > MAX_CAPTURE_BYTES:
                    raise RuntimeError("capture exceeded the producer-owned size limit")
                if time.monotonic() >= deadline:
                    raise TimeoutError("capture stop marker timeout")
                time.sleep(0.05)
            assert process.stdin is not None
            process.stdin.write(b"stop\n")
            process.stdin.close()
            try:
                status = process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    status = process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    status = process.wait()
            if status != 0:
                raise RuntimeError(
                    f"remote tcpdump capture failed with status {status}"
                )
            stderr_thread.join(timeout=2)
            process.stderr.close()
        _cleanup_remote(args.ssh_target, args.jump, token)
        cleanup_verified = True
        packet_count = count_classic_pcap_packets(capture_path)
        if packet_count == 0:
            raise RuntimeError("capture contains no positive packet control")
        if not contains_marker(capture_path, args.required_marker_sha256):
            raise RuntimeError("capture did not observe the required path marker")
        finished_at = int(time.time())
        if finished_at <= started_at:
            finished_at = started_at + 1
        write_metadata(
            output=metadata_path,
            capture=capture_path,
            role=args.role,
            correlation_id=args.correlation_id,
            started_at=started_at,
            finished_at=finished_at,
            packet_count=packet_count,
        )
    except BaseException:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        if process is not None and process.stdin is not None:
            process.stdin.close()
        if process is not None and process.stderr is not None:
            if stderr_thread is not None:
                stderr_thread.join(timeout=2)
            process.stderr.close()
        if process is not None and not cleanup_verified:
            try:
                _cleanup_remote(args.ssh_target, args.jump, token)
            except BaseException as cleanup_error:
                remove_failed_outputs(capture_path, metadata_path, ready_path)
                raise RuntimeError(
                    "capture failed and remote cleanup could not be verified"
                ) from cleanup_error
        remove_failed_outputs(capture_path, metadata_path, ready_path)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", choices=sorted(ROLES), required=True)
    parser.add_argument("--ssh-target", required=True)
    parser.add_argument("--jump")
    parser.add_argument("--interface", required=True)
    parser.add_argument(
        "--peer-address",
        required=True,
        help="test-flow peer IP exactly as it is visible at this vantage",
    )
    parser.add_argument(
        "--endpoint",
        action="append",
        required=True,
        help="owner-controlled IP:port pair; repeat for at most eight pairs",
    )
    parser.add_argument("--correlation-id", required=True)
    parser.add_argument("--ready-file", required=True)
    parser.add_argument("--stop-file", required=True)
    parser.add_argument("--output-pcap", required=True)
    parser.add_argument("--metadata-output", required=True)
    parser.add_argument("--required-marker-sha256", required=True)
    parser.add_argument("--max-seconds", type=int, default=600)
    return parser.parse_args()


def _raise_termination(signum: int, _frame: object) -> None:
    raise InterruptedError(f"capture interrupted by signal {signum}")


def main() -> None:
    signal.signal(signal.SIGTERM, _raise_termination)
    signal.signal(signal.SIGHUP, _raise_termination)
    args = parse_args()
    validate_ssh_name(args.ssh_target, "SSH target")
    if args.jump is not None:
        validate_ssh_name(args.jump, "SSH jump host")
    validate_interface(args.interface)
    if SHA256_RE.fullmatch(args.correlation_id) is None:
        raise ValueError("correlation id must be a lowercase SHA-256 value")
    if not 1 <= args.max_seconds <= 900:
        raise ValueError("capture timeout must be between 1 and 900 seconds")
    capture(args)


if __name__ == "__main__":
    main()
