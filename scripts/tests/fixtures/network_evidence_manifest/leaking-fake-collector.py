#!/usr/bin/env python3
"""Dual-vantage collector fake that injects one test-only sensitive field."""

from __future__ import annotations

import hashlib
import json
import os
import sys
import time
from pathlib import Path


correlation_id, source_sha, ready_path, stop_path, plan_path, output_path = sys.argv[1:]
role = "client-underlay" if "client" in Path(ready_path).name else "external-observer"
leak_role = os.environ["RIPDPI_TEST_LEAK_ROLE"]
leak_case = json.loads(os.environ["RIPDPI_TEST_LEAK_CASE"])
if leak_role not in {"client-underlay", "external-observer"}:
    raise SystemExit("invalid test leak role")
if not isinstance(leak_case, dict) or set(leak_case) != {"field", "value"}:
    raise SystemExit("invalid test leak case")
if not isinstance(leak_case["field"], str) or not leak_case["field"]:
    raise SystemExit("invalid test leak field")

capture_started = int(time.time()) - 2
Path(ready_path).touch()
deadline = time.monotonic() + 30
while not Path(stop_path).exists():
    if time.monotonic() >= deadline:
        raise SystemExit("stop marker timeout")
    time.sleep(0.02)

plan = json.loads(Path(plan_path).read_text(encoding="utf-8"))
plan_sha256 = hashlib.sha256(
    (json.dumps(plan, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
).hexdigest()
capture_finished = int(time.time())
windows = [
    {
        **window,
        "expectedPacketCount": 1,
        "unexpectedPacketCount": 0,
        "captureErrorCount": 0,
        "actionObservedCount": 1,
        "outcomeObservedCount": 1,
    }
    for window in plan["windows"]
]
observation = {
    "version": "network_evidence_observation_v3",
    "sourceSha": source_sha,
    "correlationId": correlation_id,
    "role": role,
    "clientArtifactSha256": plan["clientArtifactSha256"],
    "testArtifactSha256": plan["testArtifactSha256"],
    "scenarioPlanSha256": plan_sha256,
    "captureStartedAtEpoch": capture_started,
    "captureFinishedAtEpoch": capture_finished,
    "rawCaptureSha256": hashlib.sha256(
        (role + correlation_id).encode("ascii")
    ).hexdigest(),
    "windows": windows,
}
if role == leak_role:
    observation[leak_case["field"]] = leak_case["value"]

Path(output_path).write_text(
    json.dumps(observation, separators=(",", ":"), sort_keys=True) + "\n",
    encoding="utf-8",
)
