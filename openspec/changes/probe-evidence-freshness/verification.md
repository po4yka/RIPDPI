---
task_id: DGN-1795329243655314
change: probe-evidence-freshness
commit_sha: null
local: passed
local_evidence: 241 app JVM tests and 97 service JVM tests passed. App and service Detekt and verifyAppEngineBoundary passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: JVM tests drive callback network generations and the selector probe boundary.
artifact: not_applicable
artifact_evidence: The changes use the ordinary Android app build.
deployment: not_applicable
deployment_evidence: The change affects local reporting and learning.
---

# Verification

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-DGN-1795329243655314-001 | DGN-1795329428750933 | Tests cover stable Wi-Fi, Wi-Fi to LTE, and Wi-Fi to LTE to Wi-Fi. The ledger also checks consent, commit-time handover, unavailable evidence, and duplicate recording. | Passed |
| REQ-DGN-1795329243655314-002 | DGN-1795329429400628 | Tests cover a fresh pair, duplicate and expired signals, same-batch confirmation, intervening success, and changes of run or physical generation. | Passed |
