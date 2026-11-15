---
task_id: RST-1794722628748844
change: dpi37-privileged-safety
commit_sha: null
local: required
local_evidence: Regression, owning tests, strict Clippy, Android compile and source gates remain required.
remote_ci: required
remote_ci_evidence: Hosted CI will be checked after the requested push; no result is claimed.
device: not_applicable
device_evidence: Root activation and physical-device acceptance are not owned by this code-only repair.
artifact: not_applicable
artifact_evidence: No APK or release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PRIV-REPLY | RST-1794722773040555 | Bounded real Unix-socket response round trip, legacy-peer refusal and control-cap tests | required |
| REQ-PRIV-CHECKSUM | RST-1794722775543185 | Independent odd/even good and corrupt bytes through the production parser | required |
| REQ-PRIV-RESTORE | RST-1794722777883000 | Real early-error exits with scoped syscall faults and no payload emission | required |
| REQ-PRIV-REPLY | RST-1794722780591649 | Combined owning gates and independent review | required |
