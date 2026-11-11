---
task_id: TRN-1794377577809835
change: yamux-stream-limits
commit_sha: null
local: required
local_evidence: Run regression and affected-crate tests, lint, formatting, locked metadata, source gates and independent review before acceptance.
remote_ci: not_applicable
remote_ci_evidence: This local input-validation repair does not require hosted execution; remote CI is not claimed.
device: not_applicable
device_evidence: The failure occurs in host-independent configuration arithmetic before carrier I/O. No Android behavior changes.
artifact: not_applicable
artifact_evidence: No packaged binary or generated output changes.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-YAMUX-LIMIT | TRN-1794377627725108 | Profile boundaries, constructor compatibility and backend admission tests | Pending |
| REQ-YAMUX-CONSTRUCTOR | TRN-1794377627725108 | Direct session boundaries and no-I/O rejection tests | Pending |
