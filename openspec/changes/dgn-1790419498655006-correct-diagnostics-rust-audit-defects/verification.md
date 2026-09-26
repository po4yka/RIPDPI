---
task_id: DGN-1790419498655006
change: dgn-1790419498655006-correct-diagnostics-rust-audit-defects
commit_sha: null
local: required
local_evidence: Pending integrated-tree Rust, architecture, and task-contract gates.
remote_ci: required
remote_ci_evidence: Pending authorized push and hosted CI on integrated main.
device: required
device_evidence: Pending Android scan smoke test for runtime deadline and session reuse.
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1790419498655006-001 | DGN-1790419675755267 | `cargo test --locked` on diagnostics-contracts (57), diagnostics-http (40), and diagnostics-fat-header (23) passed after integration rebase. | passed |
| REQ-DGN-1790419498655006-002 | DGN-1790419675755267 | `cargo test --locked` on diagnostics-telegram passed (26); diagnostics-runner passed (76, two pre-existing ignored). | passed |
| REQ-DGN-1790419498655006-003 | DGN-1790419662746612 | `cargo test --locked` on diagnostics-candidates (42) and diagnostics-classification (63) passed. | passed |
| REQ-DGN-1790419498655006-004 | DGN-1790419683474898 | Monitor-engine restart regression and full library suite passed (230 tests). | passed |
| REQ-DGN-1790419498655006-005 | DGN-1790419683474898 | Monitor-engine DNS worker saturation regression and full library suite passed (230 tests). | passed |
| REQ-DGN-1790419498655006-006 | DGN-1790419691105412 | Pending transport deadline regression tests. | pending |
| REQ-DGN-1790419498655006-007 | DGN-1790419691105412 | Pending dormant probe regression tests. | pending |
