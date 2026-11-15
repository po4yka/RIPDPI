---
task_id: DGN-1794732375813278
change: correct-dia05-dns-json-contracts
commit_sha: null
local: required
local_evidence: Targeted Cargo tests, independent review and just ci-gates are required before integration.
remote_ci: not_applicable
remote_ci_evidence: This repair requires local source gates; remote CI is reported separately after push.
device: not_applicable
device_evidence: Host-side parser and evidence repair; no device or external probe is required or authorized.
artifact: required
artifact_evidence: Regenerate and check affected Rust public API snapshots.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1794732375813278-001 | DGN-1794732594607662 | Targeted owning-crate regression tests; local evidence pending | required |
| REQ-DGN-1794732375813278-002 | DGN-1794732621573690 | Targeted owning-crate regression tests; local evidence pending | required |
| REQ-DGN-1794732375813278-003 | DGN-1794732644517636 | Targeted owning-crate regression tests; local evidence pending | required |
| REQ-DGN-1794732375813278-004 | DGN-1794732658759810 | Targeted owning-crate regression tests; local evidence pending | required |
| REQ-DGN-1794732375813278-005 | DGN-1794732674757937 | Targeted owning-crate regression tests; local evidence pending | required |
| REQ-DGN-1794732375813278-006 | DGN-1794732697109380 | Targeted owning-crate regression tests; local evidence pending | required |
| Combined source gates | DGN-1794732708545147 | just ci-gates, architecture health, locked cargo metadata and independent review | required |
