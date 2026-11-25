---
task_id: SVC-1795615846684850
change: svc-1795615846684850-suppress-route-issue-after-passed-connection-test
commit_sha: null
local: required
local_evidence: "cargo test -p ripdpi-android-telemetry-adapter --locked at efc742070f: 26 passed, exit 0; the two local-target regression tests failed before the fix (4 then 2 local counts instead of 0)."
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: "Partial. Pixel 8 Pro 38080DLJG000GX, githubDebug arm64 at c3aa5497f0 (before efc742070f), pm clear, onboarding with AdGuard DNS: the test passed on retry and home showed Connected with no Route issue at 00:01:53. The trigger did not recur: logcat had 0 upstream connect failures and 0 route advances, so this run does not prove the fix. Needs a rerun at efc742070f or later with an observed start-up refusal."
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SVC-1795615846684850-001 | SVC-1795615847257249 | `route_advances_on_local_targets_do_not_count_toward_warnings` and `failures_on_local_targets_do_not_count_toward_warnings` feed 127.0.0.1, 0.0.0.0, and :: targets and assert zero route and failure counts | pass |
| REQ-SVC-1795615846684850-002 | SVC-1795615847721706 | The same tests assert that an advance and a failure on 203.0.113.10 still count 1; `RecentWarningTracker` and its 60 s window are unchanged | pass |
| REQ-SVC-1795615846684850-003 | SVC-1795615848162479 | See device_evidence: Connected with no Route issue observed, trigger not reproduced | partial |
