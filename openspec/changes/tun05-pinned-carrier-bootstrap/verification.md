---
task_id: RST-1794477836924583
change: tun05-pinned-carrier-bootstrap
commit_sha: null
local: passed
local_evidence: /tmp/tun05-native-combined-tests.log: core197 and carrier27 unit plus7 integration passed. Numeric dial reached loopback with original TLS SNI and HTTP identity; empty and invalid numeric lists rejected.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI result is claimed.
device: not_applicable
device_evidence: Device underlay acceptance belongs to the service recovery change.
artifact: not_applicable
artifact_evidence: No packaged artifact is claimed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WSS-PINNED-DIAL | RST-1794478016736497 | cargo test -p ripdpi-wireguard-ws -p ripdpi-warp-core --locked | Passed; see local_evidence |
| REQ-WSS-URL-IDENTITY | RST-1794478016736497 | cargo test -p ripdpi-wireguard-ws --locked | Passed; see local_evidence |
