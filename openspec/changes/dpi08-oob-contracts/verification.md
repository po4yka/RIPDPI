---
task_id: ENG-1794501878605236
change: dpi08-oob-contracts
commit_sha: c9d964d440741cbd539eaca84d42fa29f581d704
local: passed
local_evidence: Locked cargo tests for ripdpi-config, ripdpi-desync, ripdpi-desync-runtime and ripdpi-proxy-config passed at every repair commit and at the final commit. Five-crate all-target Clippy with -D warnings, just ci-gates, and the Kotlin unit tests of core data model, data settings, data, engine and the app desync settings tests passed. A Linux container run passed the five OOB runtime tests; the old single MSG_OOB send failed both prefix-pressure tests. Independent review found no blocking defect; its receipt count finding is fixed.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is claimed.
device: not_applicable
device_evidence: No physical device or root activation was authorized. Android and external DPI compatibility are not claimed.
artifact: not_applicable
artifact_evidence: No APK or release artifact is published by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-OOB-ONCE | ENG-1794502121073363 | planner once/later/empty tests; runtime peer-byte test with two urgent steps and a later buffer | Passed locally |
| REQ-OOB-PROGRESS | ENG-1794502122850225 | nonblocking partial-prefix and blocking SO_SNDBUF=4096 peer-byte tests; Linux run fails on the old send | Passed locally |
| REQ-OOB-HEX | ENG-1794502124654578 | main 8313d1b995 parser and its fallback-byte tests; no new code | Passed locally |
| REQ-OOB-OFFSET | ENG-1794502126010205 | CLI, UI payload, native planner and Kotlin DSL rejection tests; TLS repeat tests | Passed locally |
| REQ-OOB-BYTE | ENG-1794502127402016 | Kotlin model, settings import/export, engine codec and mapper, app settings tests | Passed locally |
| REQ-OOB-COUNT | ENG-1794502129321790 | runtime bytes_committed and payload receipt equal 6 for 6 bytes; strategy-free accumulation tests | Passed locally |
| REQ-OOB-PROJECTION | ENG-1794502510792935 | generic DesyncStrategy plan keeps WriteUrgent with the exact prefix and byte | Passed locally |

## Not verified

- No packet capture. The prefix and the urgent byte can now leave in separate TCP segments; DPI effect of that change is not measured.
- No device, root or external server run.
- `route_advancement_fires_on_tcp_reset_and_second_group_handles_traffic` in ripdpi-proxy-runtime is intermittent on main and on this branch under machine load; it does not use an OOB path.
