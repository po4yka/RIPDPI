#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path


correlation_id, source_sha, plan_path = sys.argv[1:]
if os.environ.get("RIPDPI_TEST_WORKLOAD_FAIL") == "1":
    raise SystemExit(23)
root = Path(os.environ["RIPDPI_TEST_REPO_ROOT"])
policy = json.loads(
    (root / "quality/release-gates/dns-ipv6-killswitch-gates.json").read_text(
        encoding="utf-8"
    )
)
started = int(time.time()) - 1
windows = []
for gate in policy["gates"]:
    gate_id = gate["id"]
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
        }
    )
Path(plan_path).write_text(
    json.dumps(
        {"correlationId": correlation_id, "sourceSha": source_sha, "windows": windows},
        sort_keys=True,
    ),
    encoding="utf-8",
)
