---
task_id: DAT-1793534707148297
change: app-lock-reset-notice
commit_sha: 415f31e87bcd2aafd8d802ba7506e0a963d41f5b
local: passed
local_evidence: "Unfiltered :core:data:settings:testDebugUnitTest and :core:data:testDebugUnitTest plus the focused app tests SettingsNoticeStateTest, SettingsViewModelBootstrapperTest and SettingsCustomizationActionsTest, BackupRestoreUseCaseTest and AppLockResetNoticePersistenceTest passed (lockreset/gate7.log). The proto field is bool app_lock_reset_notice_pending = 425 in c4d60f5c6, with a concurrent-PIN-change guard in b767c8281. An independent pr-reviewer found no high-severity issue and its medium and low findings were fixed in b767c8281. 052d02b92 and cb76fc483 after it change only config/static/architecture-health-baseline.json and the native/rust cargo-deny waiver files, so the app and test gates recorded above still apply to cb76fc483 and 415f31e87; check_architecture_health.py --check and cargo deny --locked check pass locally on cb76fc483. 415f31e87 after it is a test-only change to one androidTest file (ComposeStylesInstrumentedTest), so the gates recorded above still apply to it."
remote_ci: passed
remote_ci_evidence: "CI run 41401888905 on 415f31e87 passed on attempt 3: every job succeeded or was skipped by routing, and ci-required passed. Attempts 1 and 2 failed only rust-workspace-tests, each time on a different AmneziaWG interop case (v3-cpa-trailers negative-transport) with initiation trailers not observed. The branch changes no Rust code, and the lead assessed this as a validator flake (a single-initiation sample has a 1 in 340 chance of an empty random trailer); that assessment is not independently proven here. All other jobs passed on attempt 1, including android-instrumented-tests on API 27, 33, 35, 36 and 37, verify-roborazzi, gradle-static-analysis, architecture-health, cargo-deny and the six release-verification builds (github, play and fdroid, full and simple)."
device: not_applicable
device_evidence: "Not run on a device. The change is persistence, and unit tests cover it through a DataStore round trip that stands in for process restart. No device showed the notice after a real process death."
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DAT-1793534707148297-001 | DAT-1793534730084253 | Field 425 added with the contract documented in CONFIG_CONTRACTS.md; AppLockResetNoticePersistenceTest | Passed |
| REQ-DAT-1793534707148297-001 | DAT-1793534730552835 | Reset sets the flag with the PIN wipe; b767c8281 guards a concurrent PIN change; SettingsViewModelBootstrapperTest | Passed |
| REQ-DAT-1793534707148297-002 | DAT-1793534731008449 | SettingsNoticeStateTest and the DataStore round trip show the notice while the flag is set; not run on a device | Passed |
| REQ-DAT-1793534707148297-003 | DAT-1793534730552835 | Acknowledgement and new PIN clear the flag in SettingsCustomizationActionsTest and SettingsNoticeStateTest | Passed |
| REQ-DAT-1793534707148297-004 | DAT-1793534730084253 | AppLockResetNoticePersistenceTest: old bytes decode with the flag off; field 425 decodes | Passed |
| REQ-DAT-1793534707148297-005 | DAT-1793534731479020 | Flag excluded from FULL backups; restore replaces settings so it clears the flag and the PIN; documented in CONFIG_CONTRACTS.md; BackupRestoreUseCaseTest | Passed |
| REQ-DAT-1793534707148297-004 | DAT-1793534731937574 | Same test plus the reset and clear tests; core data unit tests unfiltered green | Passed |
