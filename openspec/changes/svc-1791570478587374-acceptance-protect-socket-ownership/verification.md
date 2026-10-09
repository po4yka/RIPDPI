---
task_id: SVC-1791570478587374
change: svc-1791570478587374-acceptance-protect-socket-ownership
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PROTECT-OVERLAP | SVC-1791570737195239 | Original c2a Network failure retained; JVM22 tests/3 assertion failures; real Android explicit serial5584 v2: replacement ACK0, old stop removes path, replacement connectIOException/ACKnull, final assertion line88 fails; private protect-wire-red-v2-2ae XML and probe-fields.log | reproduced; corrected runtime required |
| REQ-PROTECT-OWNERSHIP | SVC-1791570737996972 | Endpoint, provider and native registration ownership tests, failed startup, repeated stop, partial rollback and failed release retry | required |
| REQ-PROTECT-CONTRACT | SVC-1791570737996972 | Full service suite, static analysis, all path consumers, non-root fail-closed ACK and native generation compatibility tests | required |
| REQ-PROTECT-EVIDENCE | SVC-1791570738877357 | Genuine clean Android profile, complete Xray and VM-routed repeats, lab verification, independent review and exact published main CI | required |

The original c2a runtime overlap is observed. A separate actual Android LocalSocket/SCM_RIGHTS probe reproduced old cleanup removing the reachable replacement endpoint after a successful VpnService.protect ACK. Its source has unchanged before/after diagnostic diff475bfde8 on base2ae5493aa. The exact historical c2a unlink order remains inferred; the distinct actual boundary proves the source mechanism. The first helper attempt failed before ACK because timeout was set before connect and is retained as setup failure, not causal RED. No required category is accepted at planning time.
