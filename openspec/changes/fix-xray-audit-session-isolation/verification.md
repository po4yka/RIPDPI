---
task_id: RLY-1794335291032951
change: fix-xray-audit-session-isolation
commit_sha: null
local: required
local_evidence: Pending owning Kotlin, Rust, Go tests and offline CI gates.
remote_ci: not_applicable
remote_ci_evidence: No manual remote workflow dispatch is needed; push checks are recorded separately.
device: not_applicable
device_evidence: This change uses host regression and real Go/Rust loopback tests for the private hop; Android platform APIs are unchanged and device execution is not claimed.
artifact: required
artifact_evidence: Pending rebuilt pinned patched AAR and verifier receipt.
deployment: not_applicable
deployment_evidence: No deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-XRAY-UDP-SESSION | RLY-1794335407480209 | Pending real Go/Rust authenticated loopback tests | Pending |
| REQ-UDP-LOCAL-SESSION | RLY-1794335407480209 | Pending owning diagnostic Rust tests | Pending |
| REQ-XRAY-STUB-CLEANUP | RLY-1794335408051246 | Actual stub RED before fix; Gradle actual-stub regression + 18 RipDpiXrayRuntime tests GREEN; engine ktlint/detekt PASS; independent review no findings | PASS |
