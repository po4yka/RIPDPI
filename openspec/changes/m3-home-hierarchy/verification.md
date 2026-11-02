---
task_id: UIX-1793534654918156
change: m3-home-hierarchy
commit_sha: 415f31e87bcd2aafd8d802ba7506e0a963d41f5b
local: passed
local_evidence: "RipDpiConnectionActuatorTest covers the duration, its spoken form and its digits. New fixtures HomeStatusCardsScreenshotTest.lockdown_unknown in light and dark, full and simple variants. Goldens are blessed by the owner in 498222d19 and 5bbbe1452 (Home primary action width). Both verifyRoborazzi variants are green locally at 0.1%. No measurement of the notice share of the first screen was recorded. 052d02b92 and cb76fc483 after it change only config/static/architecture-health-baseline.json and the native/rust cargo-deny waiver files, so the app and test gates recorded above still apply to cb76fc483 and 415f31e87; check_architecture_health.py --check and cargo deny --locked check pass locally on cb76fc483. 415f31e87 after it is a test-only change to one androidTest file (ComposeStylesInstrumentedTest), so the gates recorded above still apply to it."
remote_ci: passed
remote_ci_evidence: "CI run 41401888905 on 415f31e87 passed on attempt 3: every job succeeded or was skipped by routing, and ci-required passed. Attempts 1 and 2 failed only rust-workspace-tests, each time on a different AmneziaWG interop case (v3-cpa-trailers negative-transport) with initiation trailers not observed. The branch changes no Rust code, and the lead assessed this as a validator flake (a single-initiation sample has a 1 in 340 chance of an empty random trailer); that assessment is not independently proven here. All other jobs passed on attempt 1, including android-instrumented-tests on API 27, 33, 35, 36 and 37, verify-roborazzi, gradle-static-analysis, architecture-health, cargo-deny and the six release-verification builds (github, play and fdroid, full and simple)."
device: passed
device_evidence: "Home was captured connected and disconnected on an API 35 phone and tablet, in light and dark (device-final/index.md). Tablet portrait edges align (device-r5b), tablet landscape title inset matches the column, and Arabic RTL tablet Home mirrors the duration correctly. On disconnected tablet Home the lower area stays empty because there is no more content to show. Only the primary action buttons are capped at 360dp; the hero, strategy panel and cards share the column width."
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-1793534654918156-001 | UIX-1793534695253148 | Session duration in the actuator (7b20017ba), spoken through ICU MeasureFormat, locale digits drawn left to right (578b4b91c) | Passed |
| REQ-UIX-1793534654918156-001 | UIX-1793534695753539 | Lockdown-unknown notice is a one-line row; new light and dark fixtures; share of first screen not measured numerically | Passed with limit |
| REQ-UIX-1793534654918156-002 | UIX-1793534695253148 | Connected Home captured on phone and tablet in device-final/index.md; duration is in the actuator | Passed |
| REQ-UIX-1793534654918156-003 | UIX-1793534696234161 | Primary action buttons capped at 360dp, start-aligned (f7c6c50cc); tablet portrait edges align. Disconnected tablet Home keeps an empty lower area by design, as no more content exists | Passed with limit |
| REQ-UIX-1793534654918156-004 | UIX-1793534696713271 | Owner-approved bless 498222d19 and 5bbbe1452; local verify green | Passed |
| REQ-UIX-1793534654918156-005 | UIX-1793534697204899 | device-final/index.md: phone and tablet, connected and disconnected, light and dark; Arabic RTL tablet checked | Passed |
