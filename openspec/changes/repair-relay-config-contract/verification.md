---
task_id: RLY-1794335295387247
change: repair-relay-config-contract
commit_sha: null
local: required
local_evidence: Pending implementation, targeted regression tests, static analysis, source gates, and independent review.
remote_ci: required
remote_ci_evidence: Check after the authorized main push.
device: not_applicable
device_evidence: Host tests cover the changed pure validation and transaction logic. Android transport acceptance remains outside this repair.
artifact: not_applicable
artifact_evidence: No artifact pin or packaging contract changes.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1794335295387247-001 | RLY-1794335371231289 | Pending production activation and Room regression tests | pending |
| REQ-RLY-1794335295387247-002 | RLY-1794335371925105 | Pending parser, validation and durable-state regression tests | pending |
| REQ-RLY-1794335295387247-003 | RLY-1794335372761763 | Pending actual native session decoder schema tests | pending |
| REQ-RLY-1794335295387247-004 | RLY-1794335373340330 | Pending actual backend rejection and allowed-mode tests | pending |
| REQ-RLY-1794335295387247-005 | RLY-1794335373929312 | Pending contract checks, hypothesis evidence and independent review | pending |
| REQ-RLY-1794335295387247-006 | RLY-1794335373929312 | RED real Room race combined old endpoint with new password; GREEN pending | pending |
| REQ-RLY-1794335295387247-007 | RLY-1794335373929312 | RED actual Mieru backend disagreed with planned reusable; GREEN pending | pending |
