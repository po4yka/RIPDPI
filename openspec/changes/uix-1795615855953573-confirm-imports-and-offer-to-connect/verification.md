---
task_id: UIX-1795615855953573
change: uix-1795615855953573-confirm-imports-and-offer-to-connect
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: "Rebased on main b213efbfce (2026-11-26): testGithubDebugUnitTest, verifyRoborazziGithubDebug, lintGithubDebug, core:data:runtime-state testDebugUnitTest, taskctl validate, and just ci-gates all exit 0"
remote_ci: passed
remote_ci_evidence: "CI workflow run https://github.com/po4yka/RIPDPI/actions/runs/46009834608 succeeded on exact main SHA d9aa00b99ffd01d299c33993874b39d7b3b98d72: 35 successful jobs and 31 change-routed skips, no failures, including kotlin-coverage, verify-roborazzi and android-instrumented-tests on API 27, 33, 35, 36 and 37. Rust workspace tests skipped there (no Rust change since); they succeeded in run https://github.com/po4yka/RIPDPI/actions/runs/45985235551 on ancestor a0714e6e37."
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-1795615855953573-001 | UIX-1795615856525622 | `ImportResultFeedbackTest` (Added name, Connect and Select shown, Connect emits a start request), `ImportConfirmViewModelTest` (Select uses the import id; a failed selection shows an error), screenshot `ProfileImportConfirmScreenshotTest.savedState`. Link, QR code, and a file holding a link all open this screen. A RIPDPI bundle file import is out of scope and tracked by `UIX-1795663496674824`. | pass (unit) |
| REQ-UIX-1795615855953573-002 | UIX-1795615856992293 | `ClipboardImportViewModelTest` (a typed link after an empty clipboard routes to import, no second clipboard read, no clear). | pass (unit) |
| REQ-UIX-1795615855953573-003 | UIX-1795615857443258 | `SubscriptionInitialImportTest` (Check shows the server count and saves nothing; Add saves that fetch with one request), `SetupInteractionQualityTest` (host name before Check, title and server count after), screenshot `checkedSubscription`. | pass (unit) |
| REQ-UIX-1795615855953573-004 | UIX-1795615857865767 | `SubscriptionInitialImportTest` (404 on Check and 503 on Add carry the status), `SetupInteractionQualityTest` (HTTP status text), screenshot `failedCheck`; network, format, size, expiry, and invalid-link texts in all ten locales. | pass (unit) |

Independent review (pr-reviewer) found a stale Check result, reconnect warnings read as selection failures, HTTP status text hiding a named cause, a double-tap gap, and a possible double start; all are fixed in a4db1a6611 and covered by the focused app tests (proxyimport, subscription, connection, SetupInteractionQualityTest: BUILD SUCCESSFUL). `:core:data:runtime-state:testDebugUnitTest` passes.

Device evidence: not collected. The Kaspresso subscription journeys were updated for the Check step but not run. Remote CI: passed in run 46009834608 on main d9aa00b99f.
