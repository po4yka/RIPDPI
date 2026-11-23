---
task_id: AND-1795456111842469
change: improve-root-runtime
commit_sha: 4c271040819dd7ee928d5a3c218b3a1d7ae08f70
local: passed
local_evidence: The focused Android JVM and PCAP tests, 72 helper tests, two screenshot checks, and just ci-gates passed.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: The no-su Android 35 instrumentation case passed. Root-manager grant, refusal, and live VPN cases require an app-granting test device.
artifact: passed
artifact_evidence: The Play, F-Droid, and GitHub arm64 debug APKs passed the edition check. Both extended APKs matched the embedded helper manifest. A signed Play AAB was outside local verification.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ROOT-ACCESS | AND-1795456157619993 | Extended settings state and root check tests, two Roborazzi checks, and no-su instrumentation passed | passed |
| REQ-ROOT-LIFECYCLE | AND-1795456158155043 | Targeted service tests passed. Live VPN acceptance with root is still required | blocked |
| REQ-ROOT-ASSETS | AND-1795456158686649 | 72 Rust helper tests, an Android arm64 helper build, and trust manifests in two APKs passed | passed |
| REQ-ROOT-EDITION | AND-1795456159266816 | All three arm64 debug APK checks and the Play and GitHub capture tests passed | passed |
