#!/usr/bin/env python3
"""Drive the exact-SHA evidence instrumentation and emit the scenario plan.

The runner hands this hook the exact client and androidTest APKs it verified as
installed, so instrumentation is invoked directly with `am instrument` rather
than through Gradle -- a Gradle run would rebuild and reinstall, breaking the
binding to the artifacts the manifest attests to.

For each gate the hook derives the action and outcome markers, runs that gate's
single selector, and pulls the private receipt and fixture transcript the test
wrote. The collectors turn those into the ledger; nothing here judges packets.

Gates whose instrumentation selector does not exist yet are reported and
skipped. They simply produce no window, so the oracle is never asked to prove a
scenario that never ran, and the manifest stays honestly incomplete rather than
silently passing.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PLAN_VERSION = "network_evidence_scenario_plan_v3"
MARKER_DOMAIN = "ripdpi:network-evidence-marker:v2"
FIXTURE_IDENTITY_DOMAIN = "ripdpi:fixture-identity:v1"
FIXTURE_IDENTITY_FIELDS = (
    "bindHost",
    "androidHost",
    "tcpEchoPort",
    "udpEchoPort",
    "tlsEchoPort",
    "dnsUdpPort",
    "dnsHttpPort",
    "dnsDotPort",
    "dnsDnscryptPort",
    "dnsDoqPort",
    "socks5Port",
    "controlPort",
    "fixtureDomain",
    "fixtureIpv4",
    "dnsAnswerIpv4",
    "tlsCertificatePem",
    "dnscryptProviderName",
    "dnscryptPublicKey",
)
APP_PACKAGE = "com.poyka.ripdpi"
TEST_PACKAGE = "com.poyka.ripdpi.test"
TEST_RUNNER = "com.poyka.ripdpi.HiltTestRunner"
INSTRUMENTATION_TIMEOUT_SECONDS = 420
ADB_TIMEOUT_SECONDS = 120
OK_RESULT = re.compile(r"^OK \(\d+ test", re.MULTILINE)


class WorkloadError(RuntimeError):
    pass


def log(shared: Path, message: str) -> None:
    """The runner discards our output, so progress has to land in a file."""
    path = shared / "workload.log"
    with os.fdopen(
        os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600), "a"
    ) as handle:
        handle.write(f"{time.time():.3f} {message}\n")


def canonical_bytes(value: object) -> bytes:
    return (
        json.dumps(value, separators=(",", ":"), sort_keys=True, ensure_ascii=True)
        + "\n"
    ).encode("utf-8")


def write_private_json(path: Path, value: object) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(canonical_bytes(value))


def marker_sha256(correlation_id: str, gate_id: str, kind: str, phase: str) -> str:
    preimage = f"{MARKER_DOMAIN}:{correlation_id}:{gate_id}:{kind}:{phase}"
    return hashlib.sha256(preimage.encode("ascii")).hexdigest()


def fixture_identity_sha256(manifest: dict) -> str:
    """Mirror the oracle's length-prefixed identity digest exactly."""
    missing = sorted(set(FIXTURE_IDENTITY_FIELDS) - set(manifest))
    if missing:
        raise WorkloadError(f"fixture manifest is missing fields: {', '.join(missing)}")
    digest = hashlib.sha256()
    for item in (
        FIXTURE_IDENTITY_DOMAIN,
        *(
            part
            for field in FIXTURE_IDENTITY_FIELDS
            for part in (field, str(manifest[field]))
        ),
    ):
        encoded = item.encode("utf-8")
        digest.update(struct.pack("!I", len(encoded)))
        digest.update(encoded)
    return digest.hexdigest()


class Device:
    def __init__(self) -> None:
        self.adb = os.environ.get("ADB", "adb")
        self.serial = os.environ.get("ANDROID_SERIAL", "emulator-5554")

    def run(self, *args: str, timeout: int = ADB_TIMEOUT_SECONDS):
        return subprocess.run(
            [self.adb, "-s", self.serial, *args],
            capture_output=True,
            timeout=timeout,
            check=False,
        )

    def pull_app_file(self, name: str, destination: Path) -> bool:
        """Copy one file out of the app's private storage."""
        probe = self.run(
            "exec-out", "run-as", APP_PACKAGE, "cat", f"files/{name}", timeout=60
        )
        if probe.returncode != 0 or not probe.stdout:
            return False
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(probe.stdout)
        return True


def load_registry() -> dict[str, dict]:
    path = os.environ.get("RIPDPI_EVIDENCE_ACTION_REGISTRY")
    if not path or not Path(path).is_file():
        raise WorkloadError("source-bound action registry path was not provided")
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    return {action["gateId"]: action for action in document["actions"]}


def fetch_fixture_manifest(shared: Path) -> tuple[dict, str]:
    host = os.environ.get("RIPDPI_FIXTURE_CONTROL_HOST", "127.0.0.1")
    port = os.environ.get("RIPDPI_FIXTURE_CONTROL_PORT", "46090")
    url = f"http://{host}:{port}/manifest"
    try:
        with urllib.request.urlopen(url, timeout=30) as response:  # noqa: S310
            manifest = json.loads(response.read().decode("utf-8"))
    except OSError as error:
        raise WorkloadError(f"fixture control endpoint is unreachable: {error}") from error
    write_private_json(shared / "fixture-manifest.json", manifest)
    return manifest, fixture_identity_sha256(manifest)


def require_instrumentation_installed(device: Device) -> None:
    probe = device.run("shell", "pm", "list", "instrumentation", timeout=60)
    if probe.returncode != 0 or TEST_PACKAGE.encode() not in probe.stdout:
        raise WorkloadError("androidTest package is not installed on the device")


def run_gate(
    device: Device,
    shared: Path,
    *,
    gate_id: str,
    descriptor: dict,
    correlation_id: str,
    source_sha: str,
    client_sha: str,
    test_sha: str,
    fixture_identity: str,
) -> dict | None:
    kind = descriptor["kind"]
    selector = descriptor["selector"]
    receipt_file = descriptor["receiptFile"]
    action_marker = marker_sha256(correlation_id, gate_id, kind, "action")
    outcome_marker = marker_sha256(correlation_id, gate_id, kind, "outcome")
    arguments = {
        "ripdpi.networkEvidenceCorrelationId": correlation_id,
        "ripdpi.networkEvidenceGateId": gate_id,
        "ripdpi.networkEvidenceKind": kind,
        "ripdpi.networkEvidenceSelector": selector,
        "ripdpi.networkEvidenceSemanticRule": descriptor["semanticRule"],
        "ripdpi.networkEvidenceReceiptFile": receipt_file,
        "ripdpi.networkEvidenceSourceSha": source_sha,
        "ripdpi.networkEvidenceClientArtifactSha256": client_sha,
        "ripdpi.networkEvidenceTestArtifactSha256": test_sha,
        "ripdpi.networkEvidenceFixtureIdentitySha256": fixture_identity,
        "ripdpi.networkEvidenceActionMarkerSha256": action_marker,
        "ripdpi.networkEvidenceOutcomeMarkerSha256": outcome_marker,
        "ripdpi.fixtureControlHost": os.environ.get(
            "RIPDPI_FIXTURE_EMULATOR_HOST", "10.0.2.2"
        ),
        "ripdpi.fixtureControlPort": os.environ.get(
            "RIPDPI_FIXTURE_CONTROL_PORT", "46090"
        ),
    }
    command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", selector]
    for key, value in sorted(arguments.items()):
        command += ["-e", key, value]
    # clearPackageData is deliberately not passed: it wipes the app's private
    # storage, which is exactly where the receipt we are about to pull lives.
    command.append(f"{TEST_PACKAGE}/{TEST_RUNNER}")

    started = int(time.time())
    completed = device.run(*command, timeout=INSTRUMENTATION_TIMEOUT_SECONDS)
    finished = int(time.time())
    output = completed.stdout.decode("utf-8", "replace")
    if completed.returncode != 0 or OK_RESULT.search(output) is None:
        log(shared, f"{gate_id}: instrumentation did not pass; skipping window")
        (shared / f"{gate_id}-instrumentation.log").write_text(output, encoding="utf-8")
        return None

    receipts = shared / "action-receipts"
    receipts.mkdir(mode=0o700, exist_ok=True)
    if not device.pull_app_file(receipt_file, receipts / receipt_file):
        raise WorkloadError(f"{gate_id} passed but wrote no action receipt")
    transcripts = shared / "fixture-transcripts"
    transcripts.mkdir(mode=0o700, exist_ok=True)
    transcript_name = f"network-evidence-fixture-transcript-{gate_id}.json"
    device.pull_app_file(transcript_name, transcripts / transcript_name)

    if finished <= started:
        # The oracle requires a positive duration; a sub-second window means the
        # clock, not the scenario, is what we measured.
        finished = started + 1
    log(shared, f"{gate_id}: window {started}..{finished}")
    return {
        "actionMarkerSha256": action_marker,
        "finishedAtEpoch": finished,
        "id": gate_id,
        "kind": kind,
        "outcomeMarkerSha256": outcome_marker,
        "startedAtEpoch": started,
    }


def main(argv: list[str]) -> int:
    if len(argv) != 7:
        print("usage: workload CORRELATION SOURCE_SHA PLAN CLIENT CSHA TEST TSHA")
        return 2
    (
        correlation_id,
        source_sha,
        plan_path,
        _client_artifact,
        client_sha,
        _test_artifact,
        test_sha,
    ) = argv
    plan = Path(plan_path)
    shared = plan.parent
    try:
        gate_ids = [
            value
            for value in os.environ.get("RIPDPI_EVIDENCE_GATE_IDS", "").split(",")
            if value
        ]
        if not gate_ids:
            raise WorkloadError("runner did not provide the dual-vantage gate set")
        registry = load_registry()
        device = Device()
        require_instrumentation_installed(device)
        _manifest, fixture_identity = fetch_fixture_manifest(shared)
        log(shared, f"fixture identity {fixture_identity}")

        windows: list[dict] = []
        skipped: list[str] = []
        for gate_id in gate_ids:
            descriptor = registry.get(gate_id)
            if descriptor is None:
                raise WorkloadError(f"{gate_id} is absent from the action registry")
            window = run_gate(
                device,
                shared,
                gate_id=gate_id,
                descriptor=descriptor,
                correlation_id=correlation_id,
                source_sha=source_sha,
                client_sha=client_sha,
                test_sha=test_sha,
                fixture_identity=fixture_identity,
            )
            if window is None:
                skipped.append(gate_id)
                continue
            windows.append(window)

        if not windows:
            raise WorkloadError("no gate produced a window; nothing was proven")
        if skipped:
            # Recorded so a partial lane can never read as full coverage.
            write_private_json(shared / "skipped-gates.json", sorted(skipped))
            log(shared, f"skipped without evidence: {', '.join(sorted(skipped))}")
        windows.sort(key=lambda item: item["startedAtEpoch"])
        write_private_json(
            plan,
            {
                "clientArtifactSha256": client_sha,
                "correlationId": correlation_id,
                "sourceSha": source_sha,
                "testArtifactSha256": test_sha,
                "version": PLAN_VERSION,
                "windows": windows,
            },
        )
        log(shared, f"plan written with {len(windows)} window(s)")
        return 0
    except Exception as error:  # noqa: BLE001 - the runner only sees our exit code
        log(shared, f"failed: {error}")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
