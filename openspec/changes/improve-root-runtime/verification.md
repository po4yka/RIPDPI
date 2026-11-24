---
task_id: AND-1795456111842469
change: improve-root-runtime
commit_sha: d0ee0f52e5363b363e34889044c5ebd03f501c4b
local: passed
local_evidence: On the recorded source, 25 focused root app and service JVM tests, the Play PCAP test, 72 host helper tests, and just ci-gates passed. Two screenshot checks had passed during implementation.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: The no-su Android 35 emulator case passed. Physical root-manager grant, refusal, and live VPN tests are outside the approved Android app acceptance scope.
artifact: passed
artifact_evidence: The implementation record at 4c271040819dd7ee928d5a3c218b3a1d7ae08f70 recorded successful Play, F-Droid, and GitHub arm64 debug APK edition scans and matching helper manifests in both extended APKs. Signed Play AAB verification belongs to release promotion.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ROOT-ACCESS | AND-1795456157619993 | Extended settings state and root check tests, two Roborazzi checks, and no-su instrumentation passed | passed |
| REQ-ROOT-LIFECYCLE | AND-1795456158155043 | Targeted service tests passed for helper startup, loss, cleanup, and fallback | passed |
| REQ-ROOT-ASSETS | AND-1795456158686649 | 72 Rust helper tests, an Android arm64 helper build, and trust manifests in two APKs passed | passed |
| REQ-ROOT-EDITION | AND-1795456159266816 | All three arm64 debug APK checks and the Play and GitHub capture tests passed | passed |
