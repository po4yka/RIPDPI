---
task_id: UIX-1793534441702605
change: m3-card-hierarchy
commit_sha: 415f31e87bcd2aafd8d802ba7506e0a963d41f5b
local: passed
local_evidence: "DESIGN.md and docs/design-system.md state one card rule and verify_design_md.py reports 0 violations. RipDpiCardTest checks the default variant by pixel colour and the nested tonal colour. RipDpiRoleContrastTest checks the tonal border at 3:1 or more at medium and high contrast. Goldens are blessed by the owner in 498222d19 and 5bbbe1452, with every diff triaged and 0 regressions. Both verifyRoborazzi variants are green locally at the 0.1% threshold. 052d02b92 and cb76fc483 after it change only config/static/architecture-health-baseline.json and the native/rust cargo-deny waiver files, so the app and test gates recorded above still apply to cb76fc483 and 415f31e87; check_architecture_health.py --check and cargo deny --locked check pass locally on cb76fc483. 415f31e87 after it is a test-only change to one androidTest file (ComposeStylesInstrumentedTest), so the gates recorded above still apply to it."
remote_ci: passed
remote_ci_evidence: "CI run 41401888905 on 415f31e87 passed on attempt 3: every job succeeded or was skipped by routing, and ci-required passed. Attempts 1 and 2 failed only rust-workspace-tests, each time on a different AmneziaWG interop case (v3-cpa-trailers negative-transport) with initiation trailers not observed. The branch changes no Rust code, and the lead assessed this as a validator flake (a single-initiation sample has a 1 in 340 chance of an empty random trailer); that assessment is not independently proven here. All other jobs passed on attempt 1, including android-instrumented-tests on API 27, 33, 35, 36 and 37, verify-roborazzi, gradle-static-analysis, architecture-health, cargo-deny and the six release-verification builds (github, play and fdroid, full and simple)."
device: passed
device_evidence: "77 captures on an API 35 phone and tablet, in light and dark, of Home, Diagnostics and Settings. Contrast was measured from edge strips. Every pairing passes. At standard contrast the tonal card against the page is only 1.11 in light and 1.13 in dark, so the weight difference is subtle there and the outline appears at medium and high contrast. No device other than the API 35 images was used."
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-1793534441702605-001 | UIX-1793534635643076 | Rule stated in DESIGN.md and docs/design-system.md; verify_design_md.py 0 violations | Passed |
| REQ-UIX-1793534441702605-002 | UIX-1793534636066544 | Commits 22331bd47, cc3e74d4f, 54a4d9ac8: RipDpiCard defaults to Tonal unless it has its own onClick (125 defaulted sites); Status variant deleted; Diagnostics, History and Home swept; RipDpiCardTest | Passed |
| REQ-UIX-1793534441702605-002 | UIX-1793534636484850 | Same commits: Settings, Detection, DNS, Owned Stack swept; remediation card defaults to Tonal | Passed |
| REQ-UIX-1793534441702605-003 | UIX-1793534636922469 | Owner-approved bless in 498222d19 (372 files) and 5bbbe1452 (26 files); triage in ui-polish/triage-round1.md shows 0 regressions; local verify green | Passed |
| REQ-UIX-1793534441702605-004 | UIX-1793534637396420 | 77 API 35 captures (device-final/index.md); measured contrast passes, but tonal against page is 1.11 light and 1.13 dark at standard contrast | Passed |

## Correction of 2026-11-02: rebase before merge

Before the merge, main had moved to d12cce750, so the branch was rebased onto it. That changed every commit hash cited above. cb76fc483 was dropped, because main's 11670a47f had already made the same advisory-waiver change. On main, 415f31e87 is 790923d88, 22331bd47 is 03925f3b1, cc3e74d4f is 3f3fe83d4, 54a4d9ac8 is b48784430, 498222d19 is afd4d6dcd, 5bbbe1452 is f4aad8d7f, 052d02b92 is 48ab6c78b. CI run 41467262393 on the rebased tip 7b1fccf4c passed all 54 executed jobs on attempt 1, with no AmneziaWG interop failure; it supersedes run 41401888905 as the remote CI evidence. The generated .taskctl receipts keep the pre-rebase SHA, because taskctl writes them at close time.
