---
task_id: ENG-1794501878605236
change: dpi08-oob-contracts
commit_sha: null
local: required
local_evidence: Targeted Rust and Kotlin regression tests, independent review, and combined source gates are required.
remote_ci: not_applicable
remote_ci_evidence: This change owns local source validation, not hosted workflow execution.
device: not_applicable
device_evidence: No device-specific API or visual layout changes; byte contracts use local regression tests. Android and external network acceptance remain unclaimed.
artifact: not_applicable
artifact_evidence: No APK or release artifact is published by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-OOB-ONCE | ENG-1794502121073363 | ripdpi-desync and ripdpi-desync-runtime tests | Pending |
| REQ-OOB-PROGRESS | ENG-1794502122850225 | real-peer partial write regression | Pending |
| REQ-OOB-HEX | ENG-1794502124654578 | ripdpi-config public CLI regression | Pending |
| REQ-OOB-OFFSET | ENG-1794502126010205 | CLI and grouped native admission regression | Pending |
| REQ-OOB-BYTE | ENG-1794502127402016 | Kotlin model, settings, engine and app unit tests | Pending |
| REQ-OOB-COUNT | ENG-1794502129321790 | runtime receipts and normal peer bytes | Pending |
| REQ-OOB-PROJECTION | ENG-1794502510792935 | generic adapter exact urgent action regression | Pending |
