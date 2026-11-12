---
task_id: RST-1794477240707368
change: tun05-native-initial-handshake
commit_sha: null
local: passed
local_evidence: Red: /tmp/tun05-native-red2.log, both pending-write regressions failed. Green: /tmp/tun05-native-green.log, both passed. Affected core197 tests passed in /tmp/tun05-native-combined-tests.log.
remote_ci: not_applicable
remote_ci_evidence: Local regression; no hosted CI run is required for this isolated change.
device: not_applicable
device_evidence: Deterministic host cancellation regression; no device behavior claim.
artifact: not_applicable
artifact_evidence: No packaged artifact change is claimed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-STARTUP-STOP | RST-1794477361212948 | cargo test -p ripdpi-warp-core --locked | Passed; see local_evidence |
| REQ-AWG-STARTUP-DEADLINE | RST-1794477361212948 | cargo test -p ripdpi-warp-core --locked | Passed; see local_evidence |
