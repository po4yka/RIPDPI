---
task_id: RST-1794677660326601
change: dpi29-ordered-tcp-safety
commit_sha: null
local: required
local_evidence: Owning regressions, independent review and ci-gates must pass.
remote_ci: required
remote_ci_evidence: Hosted CI will be checked after the authorized publication; no remote pass is claimed.
device: required
device_evidence: Privileged device and raw-wire acceptance remain unverified; activation needs separate authorization.
artifact: not_applicable
artifact_evidence: No release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ORDERED-SIZE | RST-1794677880409389 | 22 host tests, 7 pure production-source tests, Android aarch64 --tests check, PR and raw-boundary review passed | passed |
| REQ-ORDERED-DISPATCH | RST-1794677885136148 | runtime activation/source-index matrix and production adapter peer test (14 custom variants, zero committed bytes) passed | passed |
| REQ-HOSTFAKE-DEFAULT | RST-1794677892236274 | literal bytes/offset layout test and documentation review | required |
| REQ-ORDERED-SIZE | RST-1794677898562549 | independent review and combined ci-gates | required |
