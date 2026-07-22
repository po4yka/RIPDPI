#!/usr/bin/env python3
from __future__ import annotations

import json
import hashlib
import os
import subprocess
import sys
import time
from pathlib import Path


correlation_id, source_sha, plan_path, client_artifact_path, client_artifact_sha256 = (
    sys.argv[1:]
)
if os.environ.get("RIPDPI_TEST_WORKLOAD_FAIL") == "1":
    raise SystemExit(23)
workload_child_pid_file = os.environ.get("RIPDPI_TEST_WORKLOAD_CHILD_PID_FILE")
if workload_child_pid_file:
    child = subprocess.Popen(
        [sys.executable, "-c", "import time; time.sleep(300)"],
    )
    Path(workload_child_pid_file).write_text(str(child.pid), encoding="ascii")
if os.environ.get("RIPDPI_TEST_WORKLOAD_HANG") == "1":
    time.sleep(300)
root = Path(os.environ["RIPDPI_TEST_REPO_ROOT"])
actual_client_artifact_sha256 = hashlib.sha256(
    Path(client_artifact_path).read_bytes()
).hexdigest()
if actual_client_artifact_sha256 != client_artifact_sha256:
    raise SystemExit("client artifact digest mismatch")
policy = json.loads(
    (root / "quality/release-gates/dns-ipv6-killswitch-gates.json").read_text(
        encoding="utf-8"
    )
)
started = int(time.time()) - 1
windows = []
evidence_gate_ids = set(os.environ["RIPDPI_EVIDENCE_GATE_IDS"].split(","))
for gate in policy["gates"]:
    gate_id = gate["id"]
    if gate_id not in evidence_gate_ids:
        continue
    if gate_id.startswith(("dns-", "synthetic-")):
        kind = "dns"
    elif "ipv6" in gate_id or gate_id.startswith(("ipv4only-", "dualstack-")):
        kind = "ipv6"
    else:
        kind = "direct_window"
    windows.append(
        {
            "id": gate_id,
            "kind": kind,
            "startedAtEpoch": started,
            "finishedAtEpoch": started + 1,
            "actionMarkerSha256": hashlib.sha256(
                (
                    "ripdpi:network-evidence-marker:v2:"
                    f"{correlation_id}:{gate_id}:{kind}:action"
                ).encode("ascii")
            ).hexdigest(),
            "outcomeMarkerSha256": hashlib.sha256(
                (
                    "ripdpi:network-evidence-marker:v2:"
                    f"{correlation_id}:{gate_id}:{kind}:outcome"
                ).encode("ascii")
            ).hexdigest(),
        }
    )
Path(plan_path).write_text(
    json.dumps(
        {
            "version": "network_evidence_scenario_plan_v2",
            "correlationId": correlation_id,
            "sourceSha": source_sha,
            "clientArtifactSha256": client_artifact_sha256,
            "windows": windows,
        },
        sort_keys=True,
    ),
    encoding="utf-8",
)
