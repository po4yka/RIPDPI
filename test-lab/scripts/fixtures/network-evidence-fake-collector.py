#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import time
import sys
from pathlib import Path


correlation_id, source_sha, ready_path, stop_path, plan_path, output_path = sys.argv[1:]
role = "client-underlay" if "client" in Path(ready_path).name else "external-observer"
child_pid_path = os.environ.get("RIPDPI_TEST_CHILD_PID_FILE")
if role == "client-underlay" and child_pid_path:
    child = subprocess.Popen(["sleep", "300"])
    Path(child_pid_path).write_text(str(child.pid), encoding="ascii")
capture_started = int(time.time()) - 2
Path(ready_path).touch()
deadline = time.monotonic() + 30
while not Path(stop_path).exists():
    if time.monotonic() >= deadline:
        raise SystemExit("stop marker timeout")
    time.sleep(0.02)
plan = json.loads(Path(plan_path).read_text(encoding="utf-8"))
capture_finished = int(time.time())
windows = [
    {
        **window,
        "expectedPacketCount": 1,
        "unexpectedPacketCount": 0,
        "captureErrorCount": 0,
    }
    for window in plan["windows"]
]
raw_digest = hashlib.sha256((role + correlation_id).encode("ascii")).hexdigest()
Path(output_path).write_text(
    json.dumps(
        {
            "version": "network_evidence_observation_v1",
            "sourceSha": source_sha,
            "correlationId": correlation_id,
            "role": role,
            "captureStartedAtEpoch": capture_started,
            "captureFinishedAtEpoch": capture_finished,
            "rawCaptureSha256": raw_digest,
            "windows": windows,
        },
        separators=(",", ":"),
        sort_keys=True,
    )
    + "\n",
    encoding="utf-8",
)
