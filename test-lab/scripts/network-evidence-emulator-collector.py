#!/usr/bin/env python3
"""Capture one dual-vantage emulator network-evidence observation.

Deployed as two copies, one per vantage; the runner distinguishes them by inode
and the role is derived from the ready-marker name, exactly like the synthetic
collector this replaces. Keeping a single source avoids two near-identical
capture backends drifting apart.

client-underlay captures inside the emulator over `adb`, so it sees what the
device believes it is putting on the underlay. external-observer captures on the
host egress interface, so it sees what actually left the machine. A leak that is
invisible at one vantage shows at the other, which is the entire point of the
pair.

This process never derives counters or verdicts. It writes a raw capture plus
canonical capture metadata and then hands both vantages to the source-bound pcap
oracle, which owns every packet judgement. The oracle needs both captures at
once while the runner starts the two collectors in parallel, so the two
processes rendezvous in the runner scratch directory: whoever wins an O_EXCL
lock runs the oracle once and both read their own observation out of it.

The runner discards this process's stdout and stderr, so failures are also
appended to a private log beside the capture.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import signal
import struct
import subprocess
import sys
import time
from pathlib import Path

ROLES = ("client-underlay", "external-observer")
CAPTURE_VERSION = "network_evidence_private_capture_v1"
LEDGER_VERSION = "network_evidence_action_ledger_v2"
STARTUP_GATE_ID = "killswitch-tun-establish-native-ready"
STARTUP_RULE = "tun-establish-native-ready-v1"
DNS_RULES = {
    "dns-virtual-vpn-resolver": "virtual-vpn-resolver-v1",
    "dns-proxied-through-tunnelled-resolver": "proxied-domain-resolver-path-v1",
    "dns-allowlisted-bootstrap-resolution": "allowlisted-bootstrap-v1",
    "dns-no-isp-fallback-on-encrypted-resolver-outage": "encrypted-outage-fail-closed-v1",
    "dns-network-switch-behavior": "network-switch-dns-continuity-v1",
    "dns-core-crash-behavior": "core-crash-dns-fail-closed-v1",
    "dns-android-private-dns-conflict": "android-private-dns-conflict-v1",
}
SOURCE_OWNED_TRANSCRIPT_RULES = {
    "virtual-vpn-resolver-v1",
    "proxied-domain-resolver-path-v1",
    "encrypted-outage-fail-closed-v1",
}
# Matches the oracle's own bound; a larger capture is a runaway, not evidence.
MAX_CAPTURE_BYTES = 64 * 1024 * 1024
SNAP_LENGTH = 192
CAPTURE_FILTER = ("tcp", "or", "udp")
READY_TIMEOUT_SECONDS = 25
STOP_TIMEOUT_SECONDS = 900
DRAIN_TIMEOUT_SECONDS = 20
ORACLE_TIMEOUT_SECONDS = 300
RENDEZVOUS_TIMEOUT_SECONDS = 360


class CollectorError(RuntimeError):
    pass


def log(shared: Path, role: str, message: str) -> None:
    """Record progress where it survives the runner discarding our output."""
    line = f"{time.time():.3f} {role} {message}\n"
    path = shared / "collector.log"
    with os.fdopen(
        os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600), "a"
    ) as handle:
        handle.write(line)


def canonical_bytes(value: object) -> bytes:
    return (
        json.dumps(value, separators=(",", ":"), sort_keys=True, ensure_ascii=True)
        + "\n"
    ).encode("utf-8")


def write_private_json(path: Path, value: object) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(canonical_bytes(value))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def count_pcap_packets(path: Path) -> int:
    """Count records in a classic PCAP without interpreting any payload."""
    size = path.stat().st_size
    if size > MAX_CAPTURE_BYTES:
        raise CollectorError("capture exceeds the size bound")
    with path.open("rb") as handle:
        header = handle.read(24)
        if len(header) != 24:
            raise CollectorError("capture is missing a classic PCAP header")
        magic = header[:4]
        if magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
            prefix = ">"
        elif magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
            prefix = "<"
        else:
            raise CollectorError("capture is not a classic PCAP")
        count = 0
        while True:
            record = handle.read(16)
            if not record:
                return count
            if len(record) != 16:
                raise CollectorError("capture ends inside a record header")
            included = struct.unpack(prefix + "I", record[8:12])[0]
            if included > MAX_CAPTURE_BYTES:
                raise CollectorError("capture record length is out of bounds")
            if len(handle.read(included)) != included:
                raise CollectorError("capture ends inside a record body")
            count += 1


def run(command: list[str], *, timeout: int) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(command, capture_output=True, timeout=timeout, check=False)


class ClientCapture:
    """tcpdump inside the emulator, reached over adb."""

    def __init__(self, shared: Path, correlation_id: str) -> None:
        self.shared = shared
        self.adb = os.environ.get("ADB", "adb")
        self.serial = os.environ.get("ANDROID_SERIAL", "emulator-5554")
        self.interface = os.environ.get("RIPDPI_EVIDENCE_CLIENT_INTERFACE", "any")
        self.remote = f"/data/local/tmp/ripdpi-evidence-{correlation_id[:32]}.pcap"
        self.process: subprocess.Popen[bytes] | None = None

    def _adb(self, *args: str) -> list[str]:
        return [self.adb, "-s", self.serial, *args]

    def start(self) -> None:
        if shutil.which(self.adb) is None:
            raise CollectorError("adb is not available for the client vantage")
        state = run(self._adb("get-state"), timeout=30)
        if state.returncode != 0 or state.stdout.strip() != b"device":
            raise CollectorError("client vantage emulator is not attached")
        # tcpdump inside the guest needs root; a production-image emulator
        # refuses this, which must fail the capture rather than silently
        # degrade to a single vantage.
        if run(self._adb("root"), timeout=60).returncode != 0:
            raise CollectorError("client vantage emulator denied adb root")
        run(self._adb("wait-for-device"), timeout=60)
        run(self._adb("shell", "rm", "-f", self.remote), timeout=30)
        self.process = subprocess.Popen(
            self._adb(
                "shell",
                "tcpdump",
                "-i",
                self.interface,
                "-s",
                str(SNAP_LENGTH),
                "-U",
                "-w",
                self.remote,
                *CAPTURE_FILTER,
            ),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def ready(self) -> bool:
        if self.process is not None and self.process.poll() is not None:
            raise CollectorError("client vantage tcpdump exited before capturing")
        probe = run(self._adb("shell", "ls", "-l", self.remote), timeout=30)
        return probe.returncode == 0

    def finish(self, destination: Path) -> None:
        run(self._adb("shell", "killall", "-INT", "tcpdump"), timeout=60)
        if self.process is not None:
            try:
                self.process.wait(timeout=DRAIN_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=DRAIN_TIMEOUT_SECONDS)
        pulled = run(
            self._adb("pull", self.remote, str(destination)),
            timeout=ORACLE_TIMEOUT_SECONDS,
        )
        run(self._adb("shell", "rm", "-f", self.remote), timeout=30)
        if pulled.returncode != 0 or not destination.exists():
            raise CollectorError("client vantage capture could not be pulled")
        destination.chmod(0o600)


class ObserverCapture:
    """tcpdump on the host egress interface the emulator NATs through."""

    def __init__(self, shared: Path, correlation_id: str) -> None:
        self.shared = shared
        # Defaults to every interface on purpose. Emulator traffic to the local
        # fixture reaches the host over loopback, while a leak to a real ISP
        # resolver egresses elsewhere -- and that leak is the thing this vantage
        # exists to catch. Watching a single interface would miss one or the
        # other silently, so narrowing this is an explicit opt-in.
        self.interface = os.environ.get("RIPDPI_EVIDENCE_OBSERVER_INTERFACE", "any")
        self.destination: Path | None = None
        self.process: subprocess.Popen[bytes] | None = None

    def start(self) -> None:
        if not self.interface:
            raise CollectorError(
                "RIPDPI_EVIDENCE_OBSERVER_INTERFACE must not be empty"
            )
        if shutil.which("tcpdump") is None:
            raise CollectorError("tcpdump is not available for the observer vantage")
        self.destination = self.shared / "external-observer.pcap"
        descriptor = os.open(
            self.destination, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600
        )
        os.close(descriptor)
        self.process = subprocess.Popen(
            [
                "tcpdump",
                "-i",
                self.interface,
                "-s",
                str(SNAP_LENGTH),
                "-U",
                "-n",
                "-w",
                str(self.destination),
                *CAPTURE_FILTER,
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def ready(self) -> bool:
        if self.process is not None and self.process.poll() is not None:
            raise CollectorError("observer vantage tcpdump exited before capturing")
        assert self.destination is not None
        return self.destination.exists() and self.destination.stat().st_size >= 24

    def finish(self, destination: Path) -> None:
        if self.process is not None:
            self.process.send_signal(signal.SIGINT)
            try:
                self.process.wait(timeout=DRAIN_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=DRAIN_TIMEOUT_SECONDS)
        assert self.destination is not None
        if self.destination != destination:
            self.destination.replace(destination)
        destination.chmod(0o600)


def semantic_rules_for(plan: dict, receipts: dict[str, Path]) -> list[dict]:
    """Bind every planned window to the strongest rule the oracle implements."""
    rules: list[dict] = []
    for window in plan["windows"]:
        identifier = window["id"]
        if identifier == STARTUP_GATE_ID:
            name = STARTUP_RULE
        elif identifier in DNS_RULES:
            name = DNS_RULES[identifier]
        else:
            rules.append({"rule": "generic-marker-pair", "windowId": identifier})
            continue
        receipt_path = receipts.get(identifier)
        if receipt_path is None:
            raise CollectorError(f"{identifier} has no action receipt in the bundle")
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        rules.append(
            {
                "actionReceiptSha256": sha256_file(receipt_path),
                "fixtureIdentitySha256": receipt["fixtureIdentitySha256"],
                "rule": name,
                "windowId": identifier,
            }
        )
    return rules


def _index_gate_artifacts(directory: Path) -> dict[str, Path]:
    artifacts: dict[str, Path] = {}
    if directory.is_dir():
        for path in sorted(directory.glob("*.json")):
            document = json.loads(path.read_text(encoding="utf-8"))
            gate_id = document["gateId"]
            if gate_id in artifacts:
                raise CollectorError(f"duplicate private artifact for {gate_id}")
            artifacts[gate_id] = path
    return artifacts


def collect_bundle(
    shared: Path,
) -> tuple[dict[str, Path], dict[str, Path], Path | None]:
    """Read the private artifacts the workload pulled off the device."""
    receipts = _index_gate_artifacts(shared / "action-receipts")
    transcripts = _index_gate_artifacts(shared / "fixture-transcripts")
    manifest = shared / "fixture-manifest.json"
    return receipts, transcripts, manifest if manifest.is_file() else None


def ordered_action_artifacts(
    plan: dict, receipts: dict[str, Path], transcripts: dict[str, Path]
) -> tuple[list[Path], list[Path]]:
    action_gate_ids = [
        window["id"]
        for window in plan["windows"]
        if window["id"] == STARTUP_GATE_ID or window["id"] in DNS_RULES
    ]
    transcript_gate_ids = [
        gate_id
        for gate_id in action_gate_ids
        if DNS_RULES.get(gate_id) in SOURCE_OWNED_TRANSCRIPT_RULES
    ]
    if set(receipts) != set(action_gate_ids):
        raise CollectorError("action receipt inventory does not match the scenario plan")
    if set(transcripts) != set(transcript_gate_ids):
        raise CollectorError("fixture transcript inventory does not match the scenario plan")
    return (
        [receipts[gate_id] for gate_id in action_gate_ids],
        [transcripts[gate_id] for gate_id in transcript_gate_ids],
    )


def run_oracle(shared: Path, correlation_id: str, plan_path: Path) -> None:
    oracle = os.environ.get("RIPDPI_EVIDENCE_ORACLE")
    if not oracle or not Path(oracle).is_file():
        raise CollectorError("source-bound pcap oracle path was not provided")
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    receipts, transcripts, manifest = collect_bundle(shared)
    ordered_receipts, ordered_transcripts = ordered_action_artifacts(
        plan, receipts, transcripts
    )
    ledger = {
        "captures": {
            role: {"rawCaptureSha256": sha256_file(shared / f"{role}.pcap")}
            for role in ROLES
        },
        "scenarioPlan": plan,
        "semanticRules": semantic_rules_for(plan, receipts),
        "version": LEDGER_VERSION,
    }
    ledger_path = shared / "action-ledger.json"
    write_private_json(ledger_path, ledger)
    command = [
        sys.executable,
        oracle,
        "--client-pcap",
        str(shared / "client-underlay.pcap"),
        "--observer-pcap",
        str(shared / "external-observer.pcap"),
        "--client-metadata",
        str(shared / "client-underlay-metadata.json"),
        "--observer-metadata",
        str(shared / "external-observer-metadata.json"),
        "--ledger",
        str(ledger_path),
        "--client-output",
        str(shared / "client-underlay-observation.json"),
        "--observer-output",
        str(shared / "external-observer-observation.json"),
    ]
    for path in ordered_receipts:
        command += ["--action-receipt", str(path)]
    for path in ordered_transcripts:
        command += ["--fixture-transcript", str(path)]
    if manifest is not None:
        command += ["--fixture-manifest", str(manifest)]
    completed = run(command, timeout=ORACLE_TIMEOUT_SECONDS)
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", "replace").strip()
        (shared / "oracle.log").write_text(detail, encoding="utf-8")
        raise CollectorError(f"pcap oracle rejected the captures: {detail[:400]}")


def rendezvous(shared: Path, role: str, correlation_id: str, plan_path: Path) -> Path:
    """Run the oracle exactly once across both vantages, then take our share."""
    observation = shared / f"{role}-observation.json"
    lock = shared / "oracle.lock"
    try:
        os.close(os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600))
    except FileExistsError:
        deadline = time.monotonic() + RENDEZVOUS_TIMEOUT_SECONDS
        while not observation.exists():
            if time.monotonic() >= deadline:
                raise CollectorError("peer vantage did not publish an observation")
            if (shared / "oracle.failed").exists():
                raise CollectorError("peer vantage reported an oracle failure")
            time.sleep(0.1)
        return observation
    # We hold the lock, so we own the single oracle run for both vantages.
    peer = next(name for name in ROLES if name != role)
    deadline = time.monotonic() + RENDEZVOUS_TIMEOUT_SECONDS
    while not (shared / f"{peer}-metadata.json").exists():
        if time.monotonic() >= deadline:
            (shared / "oracle.failed").touch(mode=0o600)
            raise CollectorError("peer vantage did not finish its capture")
        time.sleep(0.1)
    try:
        run_oracle(shared, correlation_id, plan_path)
        if not observation.is_file():
            raise CollectorError("oracle did not publish this vantage's observation")
    except Exception:
        # The peer polls for this marker; without it a failed run would leave
        # the peer blocked until its own timeout with no reason recorded.
        (shared / "oracle.failed").touch(mode=0o600)
        raise
    return observation


def main(argv: list[str]) -> int:
    if len(argv) != 6:
        print("usage: collector CORRELATION SOURCE_SHA READY STOP PLAN OUTPUT")
        return 2
    correlation_id, _source_sha, ready_path, stop_path, plan, output_path = argv
    ready = Path(ready_path)
    stop = Path(stop_path)
    shared = stop.parent
    role = ROLES[0] if "client" in ready.name else ROLES[1]
    backend = (
        ClientCapture(shared, correlation_id)
        if role == ROLES[0]
        else ObserverCapture(shared, correlation_id)
    )
    try:
        log(shared, role, "starting capture")
        backend.start()
        started = int(time.time())
        deadline = time.monotonic() + READY_TIMEOUT_SECONDS
        while not backend.ready():
            if time.monotonic() >= deadline:
                raise CollectorError("capture did not reach a ready state")
            time.sleep(0.1)
        ready.touch(mode=0o600)
        log(shared, role, "ready")

        deadline = time.monotonic() + STOP_TIMEOUT_SECONDS
        while not stop.exists():
            if time.monotonic() >= deadline:
                raise CollectorError("stop marker never arrived")
            time.sleep(0.05)

        capture = shared / f"{role}.pcap"
        backend.finish(capture)
        finished = int(time.time())
        if finished <= started:
            # The oracle requires strictly positive bounds; a sub-second window
            # is a broken capture, not a fast one.
            finished = started + 1
        write_private_json(
            shared / f"{role}-metadata.json",
            {
                "captureFinishedAtEpoch": finished,
                "captureStartedAtEpoch": started,
                "correlationId": correlation_id,
                "packetCount": count_pcap_packets(capture),
                "rawCaptureSha256": sha256_file(capture),
                "role": role,
                "version": CAPTURE_VERSION,
            },
        )
        log(shared, role, "capture sealed")

        observation = rendezvous(shared, role, correlation_id, Path(plan))
        destination = Path(output_path)
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(observation.read_bytes())
        log(shared, role, "observation published")
        return 0
    except Exception as error:  # noqa: BLE001 - the runner only sees our exit code
        log(shared, role, f"failed: {error}")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
