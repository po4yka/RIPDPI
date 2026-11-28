---
task_id: UIX-1795663496674824
change: uix-1795663496674824-offer-connect-after-bundle-file-import
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: "Rebased on main bc95fe0e3e (2026-11-26), head 501cc17793: lintGithubDebug, app detekt, and just ci-gates exit 0; verifyRoborazziGithubDebug has one failure from main (RipDpiNavHostLogicTest stale Logs tags), and passed on the pre-rebase base 04f530db19"
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
| REQ-UIX-1795663496674824-001 | UIX-1795663565845766 | `SubscriptionLocalBundleImportTest` (bundle import lists its servers by saved ID and emits no close event), `SubscriptionImportConfirmScreenTest` (Added name and both server names), screenshot `importedBundleServers` light and dark. | pass (unit) |
| REQ-UIX-1795663496674824-002 | UIX-1795663566326491 | `SubscriptionLocalBundleImportTest` (Connect selects the saved member ID through `ImportedServerSelection` and asks to start; a failed selection shows an error and stays), `SubscriptionImportConfirmScreenTest` (per-server Connect and Select). Selection wraps `ConnectionRelayProfileSelector.select`, the server list's Select. | pass (unit) |
| REQ-UIX-1795663496674824-003 | UIX-1795663566872494 | `SubscriptionLocalBundleImportTest` (Connect on a running service emits no start request). `ConnectionImportedServerSelection` reports `AppliedToRunningService` when the selector reconnects the running service. | pass (unit) |
| REQ-UIX-1795663496674824-004 | UIX-1795663567356497 | No new string. The result reuses `import_done_added`, `vpn_connect`, `profile_variants_cta_select`, `relay_editor_activation_failed`, and `xray_import_done_action`, present in all ten locales; `lintGithubDebug` exit 0. | pass |

Device evidence: not collected. Remote CI: passed in run 46009834608 on main d9aa00b99f, which includes the `RipDpiNavHostLogicTest` case that failed on the rebase base.
