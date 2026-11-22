---
task_id: DGN-1795363995827246
change: raw-https-download-android-e2e
commit_sha: null
local: passed
local_evidence: The fixture TLS and manifest tests, existing runner tests, shell and workflow lint, and source CI gates passed.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: The dedicated JUnit test passed on the arm64 Pixel 6 emulator with Android API 35. One test ran with no skips.
artifact: not_applicable
artifact_evidence: The instrumentation uses a local test APK.
deployment: not_applicable
deployment_evidence: The HTTPS source is a local fixture.
---

# Verification

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-DGN-1795363995827246-001 | DGN-1795364057330051 | Android instrumentation verified the short result, long body timeout, byte counts, and both body timings in saved history | passed |
